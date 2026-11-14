package dev.foremon.work

import com.cobblemon.mod.common.CobblemonMemories
import com.cobblemon.mod.common.api.tags.CobblemonItemTags
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.net.messages.client.animation.PlayPosableAnimationPacket
import com.cobblemon.mod.common.pokemon.Pokemon
import dev.foremon.Config
import dev.foremon.Foremon
import dev.foremon.Rules
import dev.foremon.net.Net
import dev.foremon.rules.Capability
import dev.foremon.rules.Job
import dev.foremon.rules.Suitability
import dev.foremon.rules.WorkerProfile
import dev.foremon.world.Emote
import dev.foremon.world.PlayerStore
import dev.foremon.world.Site
import dev.foremon.world.SiteStore
import dev.foremon.world.Step
import dev.foremon.world.Worker
import dev.foremon.world.Workers
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import kotlin.math.min
import kotlin.math.sqrt

/** Everything a job needs to know while one worker is being ticked. Reused, never stored. */
class Ctx(
    val level: ServerLevel, val s: Site, private val main: Storage<ItemVariant>, val now: Long,
) {
    private var pool: Storage<ItemVariant>? = null

    /** Where this site keeps things: its chest plus any extra chests added to it. Resolved at most once per tick. */
    // Sorting and Hauling move items between their chests themselves, so their storage is just the site's own chest.
    // Hauling's chests can be far away: checking them here would load their chunks every tick.
    val storage: Storage<ItemVariant> get() = pool ?: (if (s.job == Job.SORTING || s.job == Job.HAULING) main else Inv.pool(level, s, main, usableLinks)).also { pool = it }

    private var usable: List<BlockPos>? = null

    /** The site's extra chests its owner is still allowed to use. Checked at most once per tick. */
    val usableLinks: List<BlockPos>
        get() = usable ?: (if (s.links.isEmpty()) emptyList() else s.links.filter { Protection.ownerMayUse(level, s, it) }).also { usable = it }

    lateinit var w: Worker
    lateinit var e: PokemonEntity
    lateinit var p: Pokemon
    lateinit var profile: WorkerProfile

    val server get() = level.server
    fun skill(node: String) = Rules.skill(server, s.owner, node)
    fun has(node: String) = Rules.has(server, s.owner, node)
    fun capacity() = (profile.carry * (1.0 + skill("pack_mule"))).toInt().coerceAtLeast(1)
    /** Lumber: bone meal in the site's chests, for the Bone Meal skill. Looked up at most once per tick. */
    val hasBoneMeal: Boolean by lazy { Inv.contains(storage) { it.`is`(net.minecraft.world.item.Items.BONE_MEAL) } }
    fun carriedFull() = w.count(w.carried) >= capacity()
    fun can(cap: Capability) = cap in profile.capabilities
    fun status(key: String, arg: String = "") = Ticker.status(level, s, key, arg)

    fun carry(stack: ItemStack) = merge(w.carried, stack)

    fun merge(list: MutableList<ItemStack>, stack: ItemStack) {
        if (stack.isEmpty) return
        for (c in list) if (ItemStack.isSameItemSameComponents(c, stack) && c.count < c.maxStackSize) {
            val n = min(stack.count, c.maxStackSize - c.count); c.grow(n); stack.shrink(n)
            if (stack.isEmpty) return
        }
        list.add(stack)
    }

    /** True if nobody else is heading for [pos] and it is not on the site's temporary "can't reach" list. */
    fun free(pos: BlockPos): Boolean {
        val until = s.skip[pos]
        if (until != null) { if (until > now) return false else s.skip.remove(pos) }
        for (o in s.workers) if (o !== w && o.step?.pos == pos) return false
        return true
    }
}

/**
 * Runs every job site once per tick, at the START of the world tick. That timing matters: entities
 * tick later in the same world tick, so the movement orders given here are in place before
 * Cobblemon's own AI runs. Doing it afterwards would mean fighting the AI one tick late.
 */
object Ticker {
    /** Block checks left this tick, shared by every site in the dimension. */
    var budget = 0
        private set

    fun spend(n: Int) { budget -= n }

    /** Time Foremon spent in its tick since the last [resetTiming], for /foremon admin perf and the stress test. */
    @Volatile var spentNanos = 0L
        private set
    @Volatile var measuredTicks = 0L
        private set

    fun resetTiming() { spentNanos = 0L; measuredTicks = 0L }

    fun tick(level: ServerLevel) {
        val t0 = System.nanoTime()
        try { tickSites(level) } finally {
            spentNanos += System.nanoTime() - t0
            if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) measuredTicks++
        }
    }

    private fun tickSites(level: ServerLevel) {
        val store = SiteStore.of(level)
        if (store.sites.isEmpty()) return
        if (level.dimension().location().toString() in Config.a.blockedDimensions) return
        val now = level.gameTime
        budget = Config.a.scanBudgetPerTick
        val nightShiftAllowed = Config.d.allowWorkWhileOwnerOffline

        val it = store.sites.values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (!level.isLoaded(s.pos) || !level.shouldTickBlocksAt(s.pos)) continue
            val storage = Inv.storage(level, s.pos)
            if (storage == null) {
                // The chest is gone, whatever removed it: nobody gets lost, everyone goes home.
                Workers.dissolve(level, s); it.remove(); store.setDirty()
                continue
            }
            val owner = level.server.playerList.getPlayer(s.owner)
            if (owner == null && !(nightShiftAllowed && Rules.has(level.server, s.owner, "night_shift"))) continue

            // Staggered so sites created on the same tick do not all do their once-a-second work together.
            if (now - s.validatedAt >= 20 && (now + s.pos.asLong()) % 20 == 0L || now - s.validatedAt > 40) {
                s.validatedAt = now
                Workers.validate(level, s)
                if (owner != null) Outline.show(level, s, owner)
            }
            if (s.workers.isEmpty()) continue
            val c = Ctx(level, s, storage, now)
            try {
                val logic = Jobs.of(s.job)
                if (s.active) logic.scan(c)
                for (w in s.workers) {
                    val e = Workers.entity(level, w) ?: continue
                    c.w = w; c.e = e; c.p = w.pokemon ?: continue; c.profile = Workers.profile(w) ?: continue
                    if (s.active) tickWorker(c, logic) else idle(c)
                }
            } catch (ex: Exception) {
                // One broken job site must never take the server down with it.
                Foremon.LOG.error("Job site at ${s.pos} failed and was paused", ex)
                s.active = false
                status(level, s, "foremon.status.error")
            }
        }
        if (now % 200 == 0L) store.setDirty()
    }

    fun status(level: ServerLevel, s: Site, key: String, arg: String = "") {
        if (s.status == key && s.statusArg == arg) return
        val sameKind = s.status == key
        s.status = key; s.statusArg = arg
        if (key.isEmpty()) return
        // The same kind of message with new details (another unreachable block, say) is not repeated more than every 10 seconds.
        if (sameKind && level.gameTime - s.notifiedAt < 200) return
        s.notifiedAt = level.gameTime
        level.server.playerList.getPlayer(s.owner)?.let { Net.notify(it, key, arg, s.pos) }
    }

    private fun idle(c: Ctx) {
        val w = c.w
        if (w.step != null || w.emote != Emote.NONE) { w.step = null; w.progress = 0; emote(c, Emote.NONE) }
    }

    private fun tickWorker(c: Ctx, logic: JobLogic) {
        val w = c.w; val e = c.e; val now = c.now
        if (e.isBattling) return
        val score = c.profile.score(c.s.job)
        if (!score.able) { emote(c, Emote.NEED); c.status("foremon.status.cannot_do", c.p.species.name); return }

        // ---- upkeep: rest and food
        if (w.restUntil > now) {
            hold(e, null)
            if (e.brain.checkMemory(CobblemonMemories.POKEMON_SLEEPING, MemoryStatus.REGISTERED)) e.brain.setMemory(CobblemonMemories.POKEMON_SLEEPING, true)
            if (w.emote != Emote.HUNGRY || now % 100 == 0L) emote(c, Emote.SLEEP)
            return
        }
        if (w.restUntil != 0L) {
            w.restUntil = 0L
            w.stamina = Workers.maxStamina(c.level, c.s, w)
            e.brain.eraseMemory(CobblemonMemories.POKEMON_SLEEPING)
        }
        if (Config.d.upkeep && w.stamina <= 0.0) {
            val berry = Inv.extract(c.storage, 1) { it.`is`(CobblemonItemTags.BERRIES) }
            if (!berry.isEmpty) {
                w.stamina = min(Workers.maxStamina(c.level, c.s, w), Config.a.staminaPerBerry * (1.0 + c.skill("hearty_meals")))
                c.p.feedPokemon(1, false)
                c.level.sendParticles(ParticleTypes.HEART, e.x, e.y + e.bbHeight, e.z, 3, 0.3, 0.2, 0.3, 0.0)
            } else {
                w.restUntil = now + (Config.a.restTicks * (1.0 - c.skill("power_nap")).coerceAtLeast(0.2)).toLong()
                w.step = null; w.progress = 0
                c.status("foremon.status.resting", c.p.species.name)
                // No berries left: it goes to sleep hungry, and the owner can see why above its head until it dozes off.
                emote(c, Emote.HUNGRY)
            }
            return
        }
        if (w.truantRest) {
            // Truant: loafs around for one action after every action.
            hold(e, null)
            if (++w.progress >= w.need) { w.progress = 0; w.truantRest = false }
            return
        }

        if (logic.gathers && now % 10 == 0L) vacuum(c)
        show(c)

        // ---- decide what to do
        var step = w.step
        if (step == null) {
            if (now < w.planAt) { emote(c, Emote.NONE); return }
            step = logic.plan(c)
            if (step == null && (w.carried.isNotEmpty() || w.supplies.isNotEmpty()) && !logic.keepsSupplies(c)) step = Step(c.s.pos, Step.Kind.CHEST)
            if (step == null && c.has("tidy_up")) step = stray(c)
            // Nothing left to do: a finished site shows "done" over its workers instead of nothing.
            if (step == null) { w.planAt = now + 40; emote(c, if (c.s.status in DONE_STATUSES) Emote.DONE else Emote.NONE); return }
            w.step = step; w.progress = 0; w.stuck = 0; w.bestDist = Double.MAX_VALUE
            debug(c) { "new step ${step.kind} at ${step.pos.toShortString()}" }
        }

        // ---- get there
        if (!near(c, step.pos, step.kind == Step.Kind.WORK && logic.anyHeight)) { travel(c, step); return }
        w.stuck = 0; w.failures = 0

        when (step.kind) {
            Step.Kind.CHEST -> {
                w.step = null
                deposit(c, c.storage)
                if (w.carried.isNotEmpty()) { c.status("foremon.status.chest_full"); emote(c, Emote.NEED); w.planAt = now + 100 }
                logic.atChest(c)
            }
            Step.Kind.DELIVER -> {
                w.step = null
                val target = Inv.storage(c.level, step.pos)
                val before = w.count(w.carried)
                if (target == null) c.s.links.remove(step.pos) else deposit(c, target)
                animate(c)
                if (w.count(w.carried) < before) { logic.delivered(c); finished(c) }
            }
            Step.Kind.PICKUP -> { w.step = null; vacuum(c) }
            Step.Kind.WORK -> work(c, logic, step, score.mult)
        }
    }

    private fun work(c: Ctx, logic: JobLogic, step: Step, mult: Double) {
        val w = c.w; val e = c.e
        hold(e, step.pos)
        // Below a fifth of its stamina a worker looks tired, so the owner can top up berries before it falls asleep.
        val tired = Config.d.upkeep && w.stamina < Workers.maxStamina(c.level, c.s, w) * 0.2
        emote(c, if (tired) Emote.TIRED else Emote.WORKING)
        e.brain.eraseMemory(CobblemonMemories.POKEMON_SLEEPING)
        if (w.progress == 0) {
            if (!logic.stillValid(c, step)) { debug(c) { "target ${step.pos.toShortString()} is no longer valid" }; w.step = null; return }
            w.need = Suitability.actionTicks(Rules.set.job(c.s.job), mult, extra(c), logic.hardness(c, step))
        }
        if (w.progress % 20 == 0) animate(c)
        w.progress++
        if (logic.cracks(step)) c.level.destroyBlockProgress(e.id, step.pos, min(9, w.progress * 10 / w.need))
        if (w.progress < w.need) return

        w.progress = 0
        if (logic.cracks(step)) c.level.destroyBlockProgress(e.id, step.pos, -1)
        val done = logic.act(c, step)
        debug(c) { "acted at ${step.pos.toShortString()}, worked=$done" }
        if (!logic.repeats(c, step)) w.step = null
        if (!done) return

        finished(c)
    }

    /** An action was completed: pay for it in stamina and reward it with XP, mastery, EXP and friendship. */
    private fun finished(c: Ctx) {
        val w = c.w; val e = c.e
        val style = c.profile.style
        if (Config.d.upkeep && c.level.random.nextDouble() >= c.skill("second_wind"))
            w.stamina -= style.staminaDrain * Config.upkeepFactor
        w.actions++; c.s.done++
        Rules.tree().earn(Rules.state(c.server, c.s.owner), Config.a.workXpPerAction * style.xp)
        PlayerStore.of(c.server).setDirty()
        // Fast Learner is a chance of double mastery XP, so it works in whole points.
        val level = Workers.addMastery(c.p, c.s.job, if (c.level.random.nextDouble() < c.skill("fast_learner")) 2 else 1)
        if (level > 0) {
            e.cry()
            Net.fx(c.level, e, Net.FX_LEVEL_UP, e.blockPosition(), 0)
            c.server.playerList.getPlayer(c.s.owner)?.let { Net.notify(it, "foremon.msg.mastery", "${c.p.species.name}|${c.s.job.key}|${Workers.MASTERY_TITLES[level]}", c.s.pos) }
        }
        val every = Config.a.pokemonRewardEveryActions
        if (every > 0 && w.actions % every == 0) {
            val stagesBefore = c.profile.stages
            Workers.reward(c.p, 1.0 + c.skill("training"), 1.0 + c.skill("bonding"))
            // Levelling up from work can unlock the next level perk: tell the owner when it does.
            if ((Workers.profile(w)?.stages ?: stagesBefore) > stagesBefore) {
                e.cry()
                Net.fx(c.level, e, Net.FX_LEVEL_UP, e.blockPosition(), 0)
                c.server.playerList.getPlayer(c.s.owner)?.let { Net.notify(it, "foremon.msg.unlock", "${c.p.species.name}|${c.p.level}", c.s.pos) }
            }
        }
        if ("truant" in c.profile.quirks) { w.truantRest = true; w.need = 40 }
        if (c.s.status.startsWith("foremon.status.")) status(c.level, c.s, "")
    }

    /** Everything that changes while working: friendship, food, skill tree, mastery and the surroundings. */
    private fun extra(c: Ctx): Double {
        val g = Rules.set.global
        var m = Suitability.care(c.p.friendship, c.p.currentFullness > 0, g)
        m *= 1.0 + c.skill("work_ethic") + c.skill(speedNode(c.s.job))
        m *= 1.0 + Workers.masteryLevel(c.p, c.s.job) * Config.a.masterySpeedPerLevel
        m *= environment(c)
        if ("slow_start" in c.profile.quirks) {
            if (c.w.slowStartUntil == 0L) c.w.slowStartUntil = c.now + 6000
            m *= if (c.now < c.w.slowStartUntil) 0.5 else 1.2
        }
        return m
    }

    private fun speedNode(job: Job) = when (job) {
        Job.SORTING, Job.HAULING -> "speed_logistics"
        else -> "speed_${job.key}"
    }

    /** Where the Pokemon is matters: the Nether, rain and water each favour some types and hinder others. */
    private fun environment(c: Ctx): Double {
        fun type(t: String) = c.p.types.any { it.name.equals(t, true) }
        var m = 1.0
        val fire = type("fire"); val water = type("water")
        if (c.level.dimension() == Level.NETHER) m *= when {
            fire -> 1.3
            c.can(Capability.LAVA) -> 1.1
            type("grass") || type("ice") -> 0.6
            else -> 0.8
        }
        if (c.e.isInWater) m *= if (water) 1.3 else if (c.can(Capability.UNDERWATER)) 1.0 else 0.6
        else if (c.level.isRainingAt(c.e.blockPosition().above())) {
            // A Fire/Water type shrugs off rain: abilities cancel penalties.
            if (fire && !water) m *= 0.6
            if (water) m *= 1.2
        }
        // Safety Gear (skill): bad surroundings no longer slow anyone down; good ones still help.
        if (m < 1.0 && c.has("safety_gear")) m = 1.0
        return m
    }

    private val DONE_STATUSES = setOf("foremon.status.no_trees", "foremon.status.area_done", "foremon.status.build_done", "foremon.status.routes_idle")

    private inline fun debug(c: Ctx, msg: () -> String) {
        if (Foremon.DEBUG) Foremon.LOG.info("[worker {} at {}] {}", c.p.species.name, c.e.blockPosition().toShortString(), msg())
    }

    // ------------------------------------------------------------ movement

    fun reach(c: Ctx): Double = 2.6 + c.e.bbWidth / 2 + c.profile.reachBonus + if (c.can(Capability.FLIGHT)) 2.0 else 0.0

    private fun near(c: Ctx, t: BlockPos, anyHeight: Boolean): Boolean {
        val e = c.e
        val dx = e.x - (t.x + 0.5); val dz = e.z - (t.z + 0.5)
        if (sqrt(dx * dx + dz * dz) > reach(c)) return false
        // Pokemon that can fly reach any height: that is what makes them the sky-base builders.
        if (anyHeight || c.can(Capability.FLIGHT)) return true
        val dy = t.y - e.y
        return dy <= 4.5 + c.profile.reachBonus && dy >= -3.5
    }

    /** Stand still and face the work. Clearing the walk target before the AI ticks is what keeps it from wandering off. */
    private fun hold(e: PokemonEntity, look: BlockPos?) {
        val brain = e.brain
        brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        brain.eraseMemory(MemoryModuleType.PATH)
        if (!e.navigation.isDone) e.navigation.stop()
        val v = e.deltaMovement
        if (v.x != 0.0 || v.z != 0.0) e.setDeltaMovement(0.0, v.y, 0.0)
        if (look != null) {
            if (brain.checkMemory(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED)) brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(look))
            e.lookControl.setLookAt(look.x + 0.5, look.y + 0.5, look.z + 0.5)
            // Turn the whole body to the work. Without this a Pokemon keeps turning to watch a nearby player while it chops.
            val dx = look.x + 0.5 - e.x; val dz = look.z + 0.5 - e.z
            if (dx * dx + dz * dz > 0.01) {
                val yaw = (kotlin.math.atan2(dz, dx) * (180.0 / Math.PI)).toFloat() - 90f
                e.yRot = yaw; e.yBodyRot = yaw; e.yHeadRot = yaw
            }
        }
    }

    private fun travel(c: Ctx, step: Step) {
        val w = c.w; val e = c.e; val t = step.pos
        emote(c, if (w.failures > 0) Emote.STUCK else Emote.NONE)
        val speed = (0.65 * (1.0 + c.skill("fleet_feet"))).toFloat()
        val brain = e.brain
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            val cur = brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null)
            if (cur == null || cur.target.currentBlockPosition() != t) brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(t, speed, 1))
        }
        if (w.stuck % 10 == 0 && e.navigation.isDone) e.navigation.moveTo(t.x + 0.5, t.y.toDouble(), t.z + 0.5, speed.toDouble())

        // Progress means getting closer. Standing still or circling counts as stuck.
        val d = e.distanceToSqr(t.x + 0.5, t.y + 0.5, t.z + 0.5)
        if (d < w.bestDist - 0.5) { w.bestDist = d; w.stuck = 0; return }
        if (++w.stuck < Config.a.stuckTicks) return
        w.stuck = 0; w.bestDist = Double.MAX_VALUE; w.failures++
        debug(c) { "stuck #${w.failures} heading to ${t.toShortString()} dist=${"%.1f".format(kotlin.math.sqrt(d))} reach=${"%.1f".format(reach(c))} navDone=${e.navigation.isDone} hasPath=${e.navigation.path != null} walkTarget=${brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)} onGround=${e.onGround()}" }

        // Escalate: first a short hop next to the target, then give the target up for a while.
        val cooldown = (Config.a.teleportCooldownTicks * (1.0 - c.skill("quick_recall")).coerceAtLeast(0.2)).toLong()
        if (step.kind == Step.Kind.CHEST && w.failures >= 2) {
            Workers.home(c.level, c.s, e); w.teleportAt = c.now + cooldown; w.failures = 0
            return
        }
        if (Config.a.teleportWhenStuck && c.now >= w.teleportAt && w.failures == 1) {
            val spot = standNear(c, t)
            debug(c) { "hop spot near ${t.toShortString()}: ${spot?.toShortString()}" }
            if (spot != null) {
                e.teleportTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
                e.navigation.stop()
                c.level.sendParticles(ParticleTypes.POOF, e.x, e.y + 0.5, e.z, 6, 0.3, 0.3, 0.3, 0.02)
                w.teleportAt = c.now + cooldown
                return
            }
        }
        if (step.pos != c.s.pos) c.s.skip[step.pos] = c.now + 1200
        debug(c) { "gave up on ${t.toShortString()}" }
        w.step = null; w.failures = 0
        emote(c, Emote.STUCK)
        c.status(if (!c.can(Capability.FLIGHT) && t.y - e.y > 5) "foremon.status.needs_flyer" else "foremon.status.cannot_reach", "${t.x} ${t.y} ${t.z}")
    }

    private fun standNear(c: Ctx, t: BlockPos): BlockPos? {
        val e = c.e; val level = c.level
        var best: BlockPos? = null; var bestD = Double.MAX_VALUE
        val box = e.tethering?.box
        for (q in BlockPos.betweenClosed(t.offset(-2, -3, -2), t.offset(2, 2, 2))) {
            if (!level.getBlockState(q.below()).isSolid) continue
            if (box != null && !box.contains(q.x + 0.5, q.y.toDouble(), q.z + 0.5)) continue
            if (!level.noCollision(e, e.type.dimensions.makeBoundingBox(q.x + 0.5, q.y.toDouble(), q.z + 0.5))) continue
            val d = q.distSqr(t)
            if (d < bestD) { bestD = d; best = q.immutable() }
        }
        Ticker.spend(100)
        return best
    }

    // ------------------------------------------------------------ small shared actions

    private fun deposit(c: Ctx, into: Storage<ItemVariant>) {
        val left = c.w.carried.map { Inv.insert(into, it) }.filter { !it.isEmpty }
        c.w.carried.clear(); c.w.carried.addAll(left)
    }

    private fun vacuum(c: Ctx) {
        var room = c.capacity() - c.w.count(c.w.carried)
        if (room <= 0) return
        val r = c.skill("item_magnet").coerceAtLeast(1.0) + if ("vacuum" in c.profile.perks) 2.0 else 0.0
        for (item in c.level.getEntitiesOfClass(ItemEntity::class.java, c.e.boundingBox.inflate(r))) {
            if (room <= 0) return
            if (!item.isAlive || item.hasPickUpDelay()) continue
            val take = item.item.split(min(room, item.item.count))
            room -= take.count
            c.carry(take)
            if (item.item.isEmpty) item.discard()
        }
    }

    /** Tidy Up (skill): with nothing else to do, a worker goes and fetches items lying around the work area. */
    private fun stray(c: Ctx): Step? {
        if (c.carriedFull()) return null
        val r = Workers.radius(c.level, c.s).toDouble()
        val box = net.minecraft.world.phys.AABB(c.s.pos).inflate(r, Workers.vertical(c.level, c.s).toDouble(), r)
        val item = c.level.getEntitiesOfClass(ItemEntity::class.java, box) { it.isAlive && !it.hasPickUpDelay() }
            .minByOrNull { it.distanceToSqr(c.e) } ?: return null
        return Step(item.blockPosition(), Step.Kind.PICKUP)
    }

    /** Shows what the worker carries on its model, using the item slot Cobblemon already renders. */
    private fun show(c: Ctx) {
        val top = c.w.supplies.lastOrNull() ?: c.w.carried.lastOrNull() ?: ItemStack.EMPTY
        if (ItemStack.isSameItem(top, c.w.shown) || top.isEmpty && c.w.shown.isEmpty) return
        c.w.shown = top.copyWithCount(1)
        c.e.setItemInHand(InteractionHand.MAIN_HAND, c.w.shown.copy())
    }

    private fun emote(c: Ctx, emote: Emote) {
        if (c.w.emote == emote) return
        c.w.emote = emote
        Net.emote(c.level, c.e, emote)
    }

    private fun animate(c: Ctx) {
        // Every Cobblemon model ships battle animations; the client plays the first of these the species has.
        val telekinetic = c.s.job == Job.SORTING || c.p.types.any { it.name.equals("psychic", true) || it.name.equals("ghost", true) }
        val names = if (telekinetic) linkedSetOf("special", "physical") else linkedSetOf("physical", "special")
        PlayPosableAnimationPacket(c.e.id, names, emptyList())
            .sendToPlayersAround(c.e.x, c.e.y, c.e.z, Config.a.effectRange.toDouble(), c.level.dimension())
    }
}

/** Shows the marked area, links and build footprint to the owner as particles while they hold the whistle. */
object Outline {
    fun show(level: ServerLevel, s: Site, owner: ServerPlayer) {
        if (owner.level() != level || owner.distanceToSqr(s.pos.x + 0.5, s.pos.y + 0.5, s.pos.z + 0.5) > 64.0 * 64.0) return
        if (!owner.mainHandItem.`is`(Foremon.WHISTLE) && !owner.offhandItem.`is`(Foremon.WHISTLE)) return
        for (l in s.links) level.sendParticles(owner, ParticleTypes.HAPPY_VILLAGER, true, l.x + 0.5, l.y + 1.2, l.z + 0.5, 3, 0.2, 0.1, 0.2, 0.0)
        val (lo, hi) = when (s.job) {
            Job.MINING, Job.DEMOLITION -> Jobs.box(s) ?: return
            Job.BUILDING -> s.plan?.let { it.min to it.max } ?: return
            else -> {
                val r = Workers.radius(level, s); s.pos.offset(-r, 0, -r) to s.pos.offset(r, 0, r)
            }
        }
        val x0 = lo.x.toDouble(); val y0 = lo.y.toDouble(); val z0 = lo.z.toDouble()
        val x1 = hi.x + 1.0; val y1 = hi.y + 1.0; val z1 = hi.z + 1.0
        // The step grows with the box, so a huge area never sends more than a few hundred particles.
        val step = maxOf(1.0, ((x1 - x0) + (y1 - y0) + (z1 - z0)) / 60.0)
        fun dot(x: Double, y: Double, z: Double) = level.sendParticles(owner, ParticleTypes.END_ROD, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
        for (y in doubleArrayOf(y0, y1)) for (z in doubleArrayOf(z0, z1)) { var x = x0; while (x <= x1) { dot(x, y, z); x += step } }
        for (x in doubleArrayOf(x0, x1)) for (z in doubleArrayOf(z0, z1)) { var y = y0; while (y <= y1) { dot(x, y, z); y += step } }
        for (x in doubleArrayOf(x0, x1)) for (y in doubleArrayOf(y0, y1)) { var z = z0; while (z <= z1) { dot(x, y, z); z += step } }
    }
}
