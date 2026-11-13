package dev.foremon.world

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.isInBattle
import com.cobblemon.mod.common.util.party
import com.cobblemon.mod.common.util.pc
import dev.foremon.Config
import dev.foremon.Foremon
import dev.foremon.Rules
import dev.foremon.rules.Job
import dev.foremon.rules.MonInput
import dev.foremon.rules.Suitability
import dev.foremon.rules.WorkerProfile
import dev.foremon.work.Inv
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * Workers are ordinary Cobblemon entities held by Cobblemon's own pasture "tethering": the Pokemon
 * sits in the owner's PC and its entity is saved with the chunk. That gives correct save/load,
 * no despawning and no riding for free. On top of that every worker carries a Cobblemon "busy lock",
 * which is Cobblemon's own switch for "cannot be battled, caught or hurt".
 *
 * Nothing here can lose a Pokemon: it never leaves the owner's PC while it works.
 */
object Workers {
    /** Identity object used as the busy lock, so we only ever remove our own lock. */
    private val LOCK = Any()
    private val EXP_SOURCE = SidemodExperienceSource(Foremon.ID)
    val MASTERY_TITLES = listOf("novice", "apprentice", "skilled", "expert", "master")

    fun radius(level: ServerLevel, s: Site) = Rules.skill(level.server, s.owner, "work_area").toInt().coerceAtLeast(4)
    fun vertical(level: ServerLevel, s: Site) = Rules.skill(level.server, s.owner, "vertical_range").toInt().coerceAtLeast(4)
    fun maxWorkers(level: ServerLevel, owner: UUID) = Rules.skill(level.server, owner, "crew_size").toInt().coerceAtLeast(1)
    fun maxSites(level: ServerLevel, owner: UUID) =
        minOf(Rules.skill(level.server, owner, "job_sites").toInt().coerceAtLeast(1), Config.d.maxJobSitesPerPlayer)

    fun entity(level: ServerLevel, w: Worker): PokemonEntity? = w.entityId?.let { level.getEntity(it) } as? PokemonEntity

    fun input(p: Pokemon): MonInput {
        val form = p.form
        val scale = form.baseScale * p.scaleModifier
        val moves = HashSet<String>()
        p.moveSet.getMoves().forEach { moves.add(it.name.lowercase()) }
        p.benchedMoves.forEach { moves.add(it.moveTemplate.name.lowercase()) }
        return MonInput(
            species = p.species.resourceIdentifier.path,
            types = p.types.map { it.name.lowercase() },
            stats = mapOf(
                "hp" to p.getStat(Stats.HP), "attack" to p.getStat(Stats.ATTACK), "defence" to p.getStat(Stats.DEFENCE),
                "special_attack" to p.getStat(Stats.SPECIAL_ATTACK), "special_defence" to p.getStat(Stats.SPECIAL_DEFENCE),
                "speed" to p.getStat(Stats.SPEED)
            ),
            level = p.level,
            ability = p.ability.name.lowercase(),
            moves = moves,
            nature = p.nature.name.path,
            width = (form.hitbox.width * scale).toDouble(),
            height = (form.hitbox.height * scale).toDouble(),
            canFly = form.behaviour.moving.fly.canFly,
            breathesUnderwater = form.behaviour.moving.swim.canBreatheUnderwater,
            fireImmune = p.isFireImmune(),
        )
    }

    fun profile(p: Pokemon): WorkerProfile = Suitability.profile(input(p), Rules.set)

    /** The worker's cached profile, rebuilt after a rules reload. */
    fun profile(w: Worker): WorkerProfile? {
        val p = w.pokemon ?: return null
        // Rebuilt after a rules reload and whenever the Pokemon levels up (mining tier and Timber depend on level).
        if (w.profile == null || w.profileVersion != Rules.version || w.profileLevel != p.level) {
            w.profile = profile(p); w.profileVersion = Rules.version; w.profileLevel = p.level
        }
        return w.profile
    }

    // ------------------------------------------------------------ mastery (stored on the Pokemon itself)

    fun masteryXp(p: Pokemon, job: Job): Int = p.persistentData.getCompound(Foremon.ID).getInt(job.key)

    fun masteryLevel(p: Pokemon, job: Job): Int {
        val xp = masteryXp(p, job)
        return Config.a.masteryLevels.count { xp >= it }
    }

    /** Returns the new level if this action levelled the Pokemon up in the job, else -1. */
    fun addMastery(p: Pokemon, job: Job, amount: Int): Int {
        val before = masteryLevel(p, job)
        val tag = p.persistentData.getCompound(Foremon.ID)
        tag.putInt(job.key, (tag.getInt(job.key) + amount).coerceAtMost(1_000_000))
        p.persistentData.put(Foremon.ID, tag)
        p.onChange()
        val after = masteryLevel(p, job)
        return if (after > before) after else -1
    }

    fun reward(p: Pokemon, expBonus: Double, friendshipBonus: Double) {
        p.addExperience(EXP_SOURCE, (Config.a.pokemonExpPerReward * expBonus).toInt())
        p.incrementFriendship((1 * friendshipBonus).toInt().coerceAtLeast(1))
    }

    // ------------------------------------------------------------ assigning

    /** Returns a translation key describing why it failed, or null on success. */
    fun assign(player: ServerPlayer, level: ServerLevel, s: Site, p: Pokemon): String? {
        if (s.workers.size >= maxWorkers(level, s.owner)) return "foremon.fail.crew_full"
        if (SiteStore.workersFor(level.server, s.owner) >= Config.d.maxWorkersPerPlayer) return "foremon.fail.worker_limit"
        if (p.isFainted()) return "foremon.fail.fainted"
        if (player.isInBattle()) return "foremon.fail.in_battle"
        if (p.tetheringId != null) return "foremon.fail.busy"
        if (!Config.a.allowLegendaries && (p.isLegendary() || p.isMythical())) return "foremon.fail.legendary"
        val chunk = net.minecraft.world.level.ChunkPos(s.pos)
        val inChunk = SiteStore.of(level).sites.values.filter { net.minecraft.world.level.ChunkPos(it.pos) == chunk }.sumOf { it.workers.size }
        if (inChunk >= Config.a.maxWorkersPerChunk) return "foremon.fail.chunk_full"

        val party = player.party(); val pc = player.pc()
        val fromParty = party.any { it.uuid == p.uuid }
        if (fromParty) {
            if (party.count() <= 1) return "foremon.fail.last_pokemon"
            if (pc.getFirstAvailablePosition() == null) return "foremon.fail.pc_full"
            p.recall()
            if (!party.remove(p)) return "foremon.fail.generic"
            // If the PC refuses it for any reason the Pokemon goes straight back to the party.
            if (!pc.add(p)) { party.add(p); return "foremon.fail.pc_full" }
        } else if (pc[p.uuid] == null) return "foremon.fail.generic"

        val w = Worker(p.uuid)
        w.pokemon = p
        w.fromPc = !fromParty
        w.stamina = maxStamina(level, s, w)
        s.workers.add(w)
        spawn(level, s, w, p)
        SiteStore.of(level).setDirty()
        return null
    }

    fun maxStamina(level: ServerLevel, s: Site, w: Worker): Double {
        val style = profile(w)?.style?.stamina ?: 1.0
        return (Config.a.baseStamina + Rules.skill(level.server, s.owner, "endurance") + (w.pokemon?.friendship ?: 0) / 5.0) * style
    }

    /** Sends the worker home. With a [player] who owns it and has party space, it returns to the party; otherwise it stays in the PC. */
    fun unassign(level: ServerLevel, s: Site, w: Worker, player: ServerPlayer?) {
        val pc = Cobblemon.storage.getPC(s.owner, level.registryAccess())
        val p = pc[w.pokemonId]
        entity(level, w)?.let { e -> e.busyLocks.remove(LOCK); e.discard() }
        if (p != null && p.tetheringId == w.tetherId) p.tetheringId = null
        returnItems(level, s, w)
        s.workers.remove(w)
        SiteStore.of(level).setDirty()
        if (player != null && p != null && player.uuid == s.owner && !w.fromPc) {
            val party = player.party()
            if (party.getFirstAvailablePosition() != null && pc.remove(p) && !party.add(p)) pc.add(p)
        }
    }

    /** The site is gone (chest broken by anything, or removed by its owner): everyone goes back to the PC. */
    fun dissolve(level: ServerLevel, s: Site) {
        s.workers.toList().forEach { unassign(level, s, it, null) }
        level.server.playerList.getPlayer(s.owner)?.let { Foremon.tell(it, "foremon.msg.site_removed") }
    }

    /** Everything the worker holds goes back to the chest, or onto the ground beside it if the chest is full or gone. */
    private fun returnItems(level: ServerLevel, s: Site, w: Worker) {
        val storage = Inv.storage(level, s.pos)
        for (st in w.carried + w.supplies) {
            val left = if (storage != null) Inv.insert(storage, st) else st
            if (!left.isEmpty) level.addFreshEntity(ItemEntity(level, s.pos.x + 0.5, s.pos.y + 1.2, s.pos.z + 0.5, left))
        }
        w.carried.clear(); w.supplies.clear()
    }

    /** Refreshes Pokemon references, re-applies the busy lock and respawns missing entities. Called once a second per site. */
    fun validate(level: ServerLevel, s: Site) {
        val pc = Cobblemon.storage.getPC(s.owner, level.registryAccess())
        for (w in s.workers.toList()) {
            val p = pc[w.pokemonId]
            // Moved to the party, traded, released, or tethered by something else: it is no longer ours.
            if (p == null || (p.tetheringId != null && p.tetheringId != w.tetherId)) {
                entity(level, w)?.let { e -> e.busyLocks.remove(LOCK); e.discard() }
                returnItems(level, s, w); s.workers.remove(w); SiteStore.of(level).setDirty()
                continue
            }
            if (w.pokemon !== p) { w.pokemon = p; w.profile = null }
            val e = entity(level, w)
            if (e == null || !e.isAlive || p.tetheringId == null) {
                if (p.isFainted()) p.heal()
                spawn(level, s, w, p)
            } else {
                if (LOCK !in e.busyLocks) e.busyLocks.add(LOCK)
                if (e.y < level.minBuildHeight - 4) home(level, s, e)
                // After a Work Area or Depth upgrade the worker gets a bigger roaming box straight away. Without this
                // Cobblemon would recall it the moment it walked past the old edge, and it would blink back.
                val box = tetherBox(level, s)
                val t = e.tethering
                if (t != null && (t.minRoamPos != box.first || t.maxRoamPos != box.second)) e.tethering = tether(level, s, w, p, e, box)
            }
        }
    }

    fun spawn(level: ServerLevel, s: Site, w: Worker, p: Pokemon) {
        // A fresh tether id makes any stale copy (say, in a chunk that was unloaded) discard itself when it loads.
        w.tetherId = UUID.randomUUID()
        p.tetheringId = w.tetherId
        val e = PokemonEntity(level, p)
        val spot = spotNear(level, s.pos)
        e.setPos(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
        e.tethering = tether(level, s, w, p, e, tetherBox(level, s))
        e.busyLocks.add(LOCK)
        e.setDropChance(EquipmentSlot.MAINHAND, 0f)
        if (level.addFreshEntity(e)) w.entityId = e.uuid
        w.step = null; w.progress = 0; w.stuck = 0; w.shown = ItemStack.EMPTY
    }

    /** The box a worker may roam in: the work area plus a margin, so it can stand beside blocks at the edge. */
    fun tetherBox(level: ServerLevel, s: Site): Pair<BlockPos, BlockPos> {
        val r = radius(level, s) + 8
        val v = vertical(level, s) + 8
        return s.pos.offset(-r, -v, -r) to s.pos.offset(r, v, r)
    }

    private fun tether(level: ServerLevel, s: Site, w: Worker, p: Pokemon, e: PokemonEntity, box: Pair<BlockPos, BlockPos>) =
        PokemonPastureBlockEntity.Tethering(
            box.first, box.second, s.owner, s.ownerName, w.tetherId, p.uuid,
            Cobblemon.storage.getPC(s.owner, level.registryAccess()).uuid, e.id, s.pos
        )

    fun home(level: ServerLevel, s: Site, e: PokemonEntity) {
        val spot = spotNear(level, s.pos)
        e.teleportTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
        e.navigation.stop()
        level.sendParticles(ParticleTypes.POOF, e.x, e.y + 0.5, e.z, 8, 0.3, 0.3, 0.3, 0.02)
    }

    fun spotNear(level: Level, pos: BlockPos): BlockPos {
        for (d in 1..3) for (dir in Direction.Plane.HORIZONTAL) for (dy in intArrayOf(0, 1, -1)) {
            val c = pos.relative(dir, d).above(dy)
            if (level.getBlockState(c).getCollisionShape(level, c).isEmpty && level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty
                && level.getBlockState(c.below()).isSolid) return c
        }
        return pos.above()
    }
}
