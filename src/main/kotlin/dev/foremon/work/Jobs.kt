package dev.foremon.work

import com.cobblemon.mod.common.block.ApricornBlock
import com.cobblemon.mod.common.block.BerryBlock
import com.cobblemon.mod.common.block.entity.BerryBlockEntity
import dev.foremon.Config
import dev.foremon.Rules
import dev.foremon.net.Net
import dev.foremon.rules.Capability
import dev.foremon.rules.Job
import dev.foremon.world.Site
import dev.foremon.world.Step
import dev.foremon.world.Workers
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.SingleRecipeInput
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.SlabType
import kotlin.math.max
import kotlin.math.min

/** One job's behaviour. The ticker handles walking, timing, stamina and rewards; a job only finds work and does it. */
abstract class JobLogic {
    /** Shows block-crack progress on the target while working. */
    open val breaks = false
    /** Picks up dropped items near the worker. */
    open val gathers = false
    /** The target can be worked from below at any height (a tree is felled from its base). */
    open val anyHeight = false

    /** Area this job searches for work, or null if it does not search the world. */
    protected open fun area(c: Ctx): Pair<BlockPos, BlockPos>? = null
    protected open val topDown = false
    protected open fun match(c: Ctx, pos: BlockPos, state: BlockState): Boolean = false

    abstract fun plan(c: Ctx): Step?
    abstract fun act(c: Ctx, step: Step): Boolean
    open fun stillValid(c: Ctx, step: Step): Boolean = true
    open fun hardness(c: Ctx, step: Step): Double = 1.0
    /** Keep working the same target after an action (a tree with logs left). */
    open fun repeats(c: Ctx, step: Step): Boolean = false
    open fun atChest(c: Ctx) {}
    open fun delivered(c: Ctx) {}
    /** True while the worker should hold on to its supplies instead of returning them to the chest. */
    open fun keepsSupplies(c: Ctx): Boolean = false

    /**
     * Looks for work a little at a time. A cursor walks the area and only [Config] many blocks are
     * checked per tick across the whole mod, so a huge area costs the same per tick as a small one.
     * Finds go into the site's queue; when the queue is full the scan simply waits.
     */
    fun scan(c: Ctx) {
        val s = c.s
        if (s.queue.size >= 48 || c.now < s.rescanAt) return
        val (lo, hi) = area(c) ?: return
        var cur = s.scan as? Cursor
        if (cur == null || cur.lo != lo || cur.hi != hi) { cur = Cursor(lo, hi, topDown); s.scan = cur }
        var n = min(Config.a.scanPerSitePerTick, Ticker.budget)
        if (n <= 0) return
        Ticker.spend(n)
        val m = BlockPos.MutableBlockPos()
        while (n-- > 0) {
            if (!cur.next(m)) {
                // Finished a full pass. Rest before the next one; an empty pass rests longer.
                s.scan = null
                s.rescanAt = c.now + if (cur.found == 0) Config.a.rescanIntervalTicks else 40
                if (cur.found == 0 && s.queue.isEmpty()) emptyPass(c)
                return
            }
            if (!c.level.isLoaded(m)) continue
            val state = c.level.getBlockState(m)
            if (state.isAir) continue
            if (match(c, m, state) && s.skip[m] == null) {
                cur.found++
                // A block that is already waiting in the queue or being worked on is not queued twice.
                if (m in s.queue || s.workers.any { it.step?.pos == m }) continue
                s.queue.addLast(m.immutable())
                if (s.queue.size >= 48) return
            }
        }
    }

    protected open fun emptyPass(c: Ctx) {}

    /** Next queued target that is still good and that nobody else has taken. */
    protected fun take(c: Ctx): BlockPos? {
        val q = c.s.queue
        var tries = q.size
        while (tries-- > 0) {
            val p = q.removeFirst()
            if (!c.free(p)) { if (c.s.skip[p] == null) q.addLast(p); continue }
            val state = c.level.getBlockState(p)
            if (!state.isAir && match(c, p, state)) return p
        }
        return null
    }

    protected fun workArea(c: Ctx, down: Int, up: Int): Pair<BlockPos, BlockPos> {
        val r = Workers.radius(c.level, c.s)
        val v = Workers.vertical(c.level, c.s)
        return c.s.pos.offset(-r, -min(down, v), -r) to c.s.pos.offset(r, min(up, v), r)
    }

    /** Water needs a Pokemon that can breathe it, lava one that is fire-proof. Returns the status key, or null if fine. */
    protected fun blockedByFluid(c: Ctx, pos: BlockPos): String? {
        var water = false; var lava = false
        val m = BlockPos.MutableBlockPos()
        for (d in NEIGHBOURS) {
            val f = c.level.getFluidState(m.setWithOffset(pos, d[0], d[1], d[2]))
            if (f.`is`(FluidTags.WATER)) water = true
            if (f.`is`(FluidTags.LAVA)) lava = true
        }
        return when {
            lava && !c.can(Capability.LAVA) -> "foremon.status.needs_fire"
            water && !c.can(Capability.UNDERWATER) -> "foremon.status.needs_water"
            else -> null
        }
    }

    /** Breaks a block as the owner would be allowed to, and hands the worker what it yields. */
    protected fun breakBlock(c: Ctx, pos: BlockPos, drops: List<ItemStack>?): Boolean {
        val state = c.level.getBlockState(pos)
        if (state.isAir) return false
        if (!Protection.canBreak(c.level, c.s, pos, state)) {
            c.s.skip[pos] = c.now + 6000
            c.status("foremon.status.not_allowed", "${pos.x} ${pos.y} ${pos.z}")
            return false
        }
        val loot = drops ?: Block.getDrops(state, c.level, pos, c.level.getBlockEntity(pos), c.e, VIRTUAL_TOOL)
        loot.forEach { c.carry(it) }
        c.level.removeBlock(pos, false)
        Net.fx(c.level, c.e, Net.FX_BREAK, pos, Block.getId(state))
        Protection.audit(c.level, c.s, "broke", pos, state)
        return true
    }

    companion object {
        private val NEIGHBOURS = arrayOf(intArrayOf(0, 0, 0), intArrayOf(0, 1, 0), intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1))
        /** There are no tools in this mod; this only tells loot tables "a proper tool was used". What may be broken is decided by the Pokemon's mining tier. */
        val VIRTUAL_TOOL = ItemStack(Items.NETHERITE_PICKAXE)
    }
}

/** Walks a box one block at a time and remembers where it stopped. */
class Cursor(val lo: BlockPos, val hi: BlockPos, private val topDown: Boolean) {
    private var x = lo.x
    private var y = if (topDown) hi.y else lo.y
    private var z = lo.z
    private var done = false
    /** Matches seen during this pass, queued or not. */
    var found = 0

    fun next(out: BlockPos.MutableBlockPos): Boolean {
        if (done) return false
        out.set(x, y, z)
        if (++x > hi.x) {
            x = lo.x
            if (++z > hi.z) {
                z = lo.z
                if (topDown) { if (--y < lo.y) done = true } else { if (++y > hi.y) done = true }
            }
        }
        return true
    }
}

object Jobs {
    fun of(job: Job): JobLogic = when (job) {
        Job.LUMBER -> Lumber
        Job.MINING -> Mining
        Job.SORTING, Job.HAULING -> Logistics
        Job.FARMING -> Farming
        Job.SMELTING -> Smelting
        Job.BUILDING -> Building
        Job.DEMOLITION -> Demolition
    }

    fun box(s: Site): Pair<BlockPos, BlockPos>? {
        val a = s.cornerA ?: return null
        val b = s.cornerB ?: a
        return BlockPos(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z)) to BlockPos(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z))
    }
}

// ============================================================================================ Lumber

object Lumber : JobLogic() {
    override val breaks = true
    override val gathers = true
    override val anyHeight = true

    override fun area(c: Ctx) = workArea(c, 8, 12)

    private fun isLog(s: BlockState) = s.`is`(BlockTags.LOGS)

    /** A tree base: a log standing on soil. Whether it is a real tree is checked when a worker claims it. */
    override fun match(c: Ctx, pos: BlockPos, state: BlockState): Boolean =
        isLog(state) && c.s.filter.allows(state) && c.level.getBlockState(pos.below()).`is`(BlockTags.DIRT)

    override fun emptyPass(c: Ctx) = c.status("foremon.status.no_trees")

    override fun plan(c: Ctx): Step? {
        if (c.carriedFull()) return Step(c.s.pos, Step.Kind.CHEST)
        var tries = 6
        while (tries-- > 0) {
            val p = take(c) ?: return null
            if (natural(c, p)) return Step(p, Step.Kind.WORK)
        }
        return null
    }

    /** Only real trees: the trunk must end in leaves that no player placed. Protects log cabins. */
    private fun natural(c: Ctx, base: BlockPos): Boolean {
        val m = base.mutable()
        var h = 0
        while (isLog(c.level.getBlockState(m.move(0, 1, 0))) && h++ < 48) { /* climb the trunk */ }
        m.move(0, -1, 0)
        Ticker.spend(h + 27)
        for (q in BlockPos.betweenClosed(m.offset(-1, -1, -1), m.offset(1, 1, 1))) {
            val st = c.level.getBlockState(q)
            if (st.block is LeavesBlock && !st.getValue(LeavesBlock.PERSISTENT)) return true
        }
        return false
    }

    private fun logs(c: Ctx, base: BlockPos): List<BlockPos> {
        val seen = LinkedHashSet<BlockPos>(); val queue = ArrayDeque<BlockPos>()
        seen.add(base); queue.add(base)
        while (queue.isNotEmpty() && seen.size < 256) {
            val cur = queue.removeFirst()
            for (q in BlockPos.betweenClosed(cur.offset(-1, 0, -1), cur.offset(1, 1, 1))) {
                if (q !in seen && isLog(c.level.getBlockState(q))) { val i = q.immutable(); seen.add(i); queue.add(i) }
            }
        }
        Ticker.spend(seen.size * 18)
        return seen.toList()
    }

    override fun stillValid(c: Ctx, step: Step) = isLog(c.level.getBlockState(step.pos))
    override fun repeats(c: Ctx, step: Step) = isLog(c.level.getBlockState(step.pos))

    override fun act(c: Ctx, step: Step): Boolean {
        val base = step.pos
        val baseState = c.level.getBlockState(base)
        if (!isLog(baseState)) return false
        val all = logs(c, base)
        // Timber: how many logs come down per swing grows with the level of the Pokemon. The Tree Feller skill or
        // knowing Cut fells the whole tree whatever the level. Top first, the base last, so nothing is left floating.
        val whole = c.has("tree_feller") || "tree_feller" in c.profile.perks
        val perSwing = if (whole) all.size else c.profile.unlock(Job.LUMBER, "timber", 1.0).toInt()
        val targets = all.sortedWith(compareByDescending<BlockPos> { it.y }.thenByDescending { it.distManhattan(base) }).take(perSwing.coerceAtLeast(1))
        var any = false
        for (t in targets) if (breakBlock(c, t, null)) any = true
        if (c.level.getBlockState(base).isAir) {
            // Leaf Sweep (level perk): once the trunk is gone the leaves go too, and what they drop is collected.
            if (whole || c.profile.unlock(Job.LUMBER, "leaf_sweep") > 0.0) sweepLeaves(c, all)
            replant(c, base, baseState)
        }
        return any
    }

    /**
     * Removes the leaves a felled tree leaves behind. Leaves still held up by another tree are kept:
     * support is spread from every remaining log through up to six leaves, the same rule the game
     * uses for leaf decay, and only leaves without support inside the felled tree's own space go.
     */
    private fun sweepLeaves(c: Ctx, logs: List<BlockPos>) {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var z0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE; var z1 = Int.MIN_VALUE
        for (p in logs) {
            x0 = min(x0, p.x); y0 = min(y0, p.y); z0 = min(z0, p.z)
            x1 = max(x1, p.x); y1 = max(y1, p.y); z1 = max(z1, p.z)
        }
        val lo = BlockPos(x0 - 4, y0, z0 - 4); val hi = BlockPos(x1 + 4, y1 + 4, z1 + 4)
        val leaves = HashSet<BlockPos>()
        var frontier = ArrayList<BlockPos>()
        for (q in BlockPos.betweenClosed(lo.offset(-6, -2, -6), hi.offset(6, 2, 6))) {
            val st = c.level.getBlockState(q)
            if (st.block is LeavesBlock) { if (!st.getValue(LeavesBlock.PERSISTENT)) leaves.add(q.immutable()) }
            else if (isLog(st)) frontier.add(q.immutable())
        }
        Ticker.spend((hi.x - lo.x + 13) * (hi.y - lo.y + 5) * (hi.z - lo.z + 13))
        if (leaves.isEmpty()) return
        val held = HashSet<BlockPos>()
        repeat(6) {
            val nextFrontier = ArrayList<BlockPos>()
            for (p in frontier) for (d in net.minecraft.core.Direction.entries) {
                val n = p.relative(d)
                if (n in leaves && held.add(n)) nextFrontier.add(n)
            }
            frontier = nextFrontier
        }
        var removed = 0
        var sample: BlockState? = null
        for (leaf in leaves) {
            if (leaf in held || leaf.x < lo.x || leaf.x > hi.x || leaf.y < lo.y || leaf.y > hi.y || leaf.z < lo.z || leaf.z > hi.z) continue
            val st = c.level.getBlockState(leaf)
            if (!Protection.canBreak(c.level, c.s, leaf, st)) continue
            Block.getDrops(st, c.level, leaf, null, c.e, ItemStack.EMPTY).forEach { c.carry(it) }
            c.level.removeBlock(leaf, false)
            sample = st; removed++
        }
        // One packet for the whole crown, however many leaves it had.
        if (removed > 0 && sample != null) Net.fx(c.level, c.e, Net.FX_LEAVES, BlockPos((x0 + x1) / 2, y1, (z0 + z1) / 2), Block.getId(sample))
    }

    private fun replant(c: Ctx, base: BlockPos, log: BlockState) {
        val id = BuiltInRegistries.BLOCK.getKey(log.block)
        val name = id.path.removePrefix("stripped_").replace("_log", "").replace("_wood", "").replace("_stem", "").replace("_hyphae", "")
        val sapling = sequenceOf("${name}_sapling", "${name}_propagule", "${name}_fungus")
            .map { BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(id.namespace, it)) }
            .firstOrNull { it != Blocks.AIR } ?: return
        val state = sapling.defaultBlockState()
        if (!c.level.getBlockState(base).canBeReplaced() || !state.canSurvive(c.level, base) || !Protection.canPlace(c.level, c.s, base)) return
        val item = sapling.asItem()
        val held = c.w.carried.firstOrNull { it.`is`(item) }
        if (held != null) { held.shrink(1); c.w.carried.removeIf { it.isEmpty } }
        else if (Inv.extract(c.storage, item, 1).isEmpty) return
        c.level.setBlockAndUpdate(base, state)
    }
}

// ============================================================================================ Mining

object Mining : JobLogic() {
    override val breaks = true
    override val gathers = true
    override val topDown = true

    override fun area(c: Ctx): Pair<BlockPos, BlockPos>? {
        val box = Jobs.box(c.s)
        if (box == null || c.s.cornerB == null) { c.status("foremon.status.mark_area"); return null }
        return box
    }

    fun requiredTier(state: BlockState): Int = when {
        state.`is`(BlockTags.NEEDS_DIAMOND_TOOL) -> 3
        state.`is`(BlockTags.NEEDS_IRON_TOOL) -> 2
        state.`is`(BlockTags.NEEDS_STONE_TOOL) -> 1
        else -> 0
    }

    override fun match(c: Ctx, pos: BlockPos, state: BlockState): Boolean =
        state.getDestroySpeed(c.level, pos) >= 0f && !state.hasBlockEntity() && state.fluidState.isEmpty
            && pos != c.s.pos && pos !in c.s.links && c.s.filter.allows(state)

    override fun emptyPass(c: Ctx) = c.status("foremon.status.area_done")

    override fun plan(c: Ctx): Step? {
        if (c.carriedFull()) return Step(c.s.pos, Step.Kind.CHEST)
        val tier = c.profile.miningTier + Rules.skillLevel(c.server, c.s.owner, "deep_digging")
        var tries = 8
        while (tries-- > 0) {
            val p = take(c) ?: return null
            val state = c.level.getBlockState(p)
            val need = requiredTier(state)
            if (need > tier) {
                // Too hard for this Pokemon: leave it for a stronger one and say why.
                c.s.skip[p] = c.now + 1200
                c.status("foremon.status.too_hard", "${state.block.name.string}|$need")
                continue
            }
            val fluid = blockedByFluid(c, p)
            if (fluid != null) { c.s.skip[p] = c.now + 1200; c.status(fluid); continue }
            return Step(p, Step.Kind.WORK)
        }
        return null
    }

    override fun stillValid(c: Ctx, step: Step) = !c.level.getBlockState(step.pos).isAir

    override fun hardness(c: Ctx, step: Step): Double =
        (c.level.getBlockState(step.pos).getDestroySpeed(c.level, step.pos) / 1.5).coerceIn(0.3, 4.0)

    override fun act(c: Ctx, step: Step): Boolean {
        val state = c.level.getBlockState(step.pos)
        val ore = state.`is`(ORES)
        // Vein Miner: a level perk that takes more ore per swing as the Pokemon levels up.
        // The Vein Miner skill or knowing Dig takes the whole vein whatever the level.
        val perSwing = if (c.has("vein_miner") || "vein_miner" in c.profile.perks) 24 else c.profile.unlock(Job.MINING, "vein", 1.0).toInt()
        val targets = if (ore && perSwing > 1) vein(c, step.pos, state.block, perSwing) else listOf(step.pos)
        var any = false
        for (t in targets) {
            val st = c.level.getBlockState(t)
            val drops = Block.getDrops(st, c.level, t, null, c.e, VIRTUAL_TOOL)
            // Prospector (skill) and luck abilities sometimes turn up extra ore.
            val luck = c.skill("prospector") + if ("fortune" in c.profile.perks) 0.15 else 0.0
            if (ore && c.level.random.nextDouble() < luck) drops.firstOrNull()?.let { drops.add(it.copy()) }
            if (breakBlock(c, t, drops)) any = true
        }
        return any
    }

    private val ORES = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores"))

    private fun vein(c: Ctx, start: BlockPos, block: Block, limit: Int): List<BlockPos> {
        val seen = LinkedHashSet<BlockPos>(); val queue = ArrayDeque<BlockPos>()
        seen.add(start); queue.add(start)
        while (queue.isNotEmpty() && seen.size < limit) {
            val cur = queue.removeFirst()
            for (q in BlockPos.betweenClosed(cur.offset(-1, -1, -1), cur.offset(1, 1, 1))) {
                if (seen.size < limit && q !in seen && c.level.getBlockState(q).`is`(block)) { val i = q.immutable(); seen.add(i); queue.add(i) }
            }
        }
        Ticker.spend(seen.size * 27)
        return seen.toList()
    }
}

// ============================================================================================ Demolition

object Demolition : JobLogic() {
    override val breaks = true
    override val gathers = true
    override val topDown = true

    override fun area(c: Ctx): Pair<BlockPos, BlockPos>? {
        val box = Jobs.box(c.s)
        if (box == null || c.s.cornerB == null) { c.status("foremon.status.mark_area"); return null }
        return box
    }

    override fun match(c: Ctx, pos: BlockPos, state: BlockState): Boolean =
        state.getDestroySpeed(c.level, pos) >= 0f && !state.hasBlockEntity() && state.fluidState.isEmpty
            && pos != c.s.pos && pos !in c.s.links && c.s.filter.allows(state)

    override fun emptyPass(c: Ctx) = c.status("foremon.status.area_done")

    override fun plan(c: Ctx): Step? {
        if (c.carriedFull()) return Step(c.s.pos, Step.Kind.CHEST)
        return take(c)?.let { Step(it, Step.Kind.WORK) }
    }

    override fun stillValid(c: Ctx, step: Step) = !c.level.getBlockState(step.pos).isAir

    override fun hardness(c: Ctx, step: Step): Double =
        (c.level.getBlockState(step.pos).getDestroySpeed(c.level, step.pos) / 1.5).coerceIn(0.3, 3.0)

    override fun act(c: Ctx, step: Step): Boolean {
        val state = c.level.getBlockState(step.pos)
        // Taking things apart gives the block itself back, not its mined drops. Clean Salvage (skill) loses fewer.
        val item = state.block.asItem()
        val back = if (item != Items.AIR && c.level.random.nextDouble() < c.skill("clean_salvage").coerceIn(0.0, 1.0)) listOf(ItemStack(item)) else emptyList()
        val done = breakBlock(c, step.pos, back)
        // Quick Hands (level perk): more blocks in the same action, as long as they are right next to the first.
        var more = c.profile.unlock(Job.DEMOLITION, "multi", 1.0).toInt() - 1
        while (done && more-- > 0) {
            val next = c.s.queue.firstOrNull() ?: break
            if (next.distSqr(step.pos) > 9 || !c.free(next)) break
            c.s.queue.removeFirst()
            val st = c.level.getBlockState(next)
            if (st.isAir || !match(c, next, st)) continue
            val it2 = st.block.asItem()
            breakBlock(c, next, if (it2 != Items.AIR && c.level.random.nextDouble() < c.skill("clean_salvage").coerceIn(0.0, 1.0)) listOf(ItemStack(it2)) else emptyList())
        }
        return done
    }
}

// ============================================================================================ Farming

object Farming : JobLogic() {
    override val gathers = true

    override fun area(c: Ctx) = workArea(c, 4, 6)

    override fun match(c: Ctx, pos: BlockPos, state: BlockState): Boolean {
        val b = state.block
        val ripe = when (b) {
            is CropBlock -> b.isMaxAge(state)
            is NetherWartBlock -> state.getValue(NetherWartBlock.AGE) >= 3
            is ApricornBlock -> state.getValue(ApricornBlock.AGE) == ApricornBlock.MAX_AGE
            is BerryBlock -> state.getValue(BerryBlock.AGE) == BerryBlock.FRUIT_AGE
            else -> false
        }
        return ripe && c.s.filter.allows(state)
    }

    override fun plan(c: Ctx): Step? {
        if (c.carriedFull()) return Step(c.s.pos, Step.Kind.CHEST)
        return take(c)?.let { Step(it, Step.Kind.WORK) }
    }

    override fun stillValid(c: Ctx, step: Step) = match(c, step.pos, c.level.getBlockState(step.pos))

    override fun act(c: Ctx, step: Step): Boolean {
        if (!harvest(c, step.pos)) return false
        // Wide Harvest (level perk): every ripe crop around the one it went for comes in with the same action.
        val r = c.profile.unlock(Job.FARMING, "sweep").toInt()
        if (r > 0) for (q in BlockPos.betweenClosed(step.pos.offset(-r, -1, -r), step.pos.offset(r, 1, r))) {
            if (q != step.pos && c.free(q)) harvest(c, q.immutable())
        }
        return true
    }

    private fun harvest(c: Ctx, pos: BlockPos): Boolean {
        val state = c.level.getBlockState(pos)
        if (!match(c, pos, state) || !Protection.canBreak(c.level, c.s, pos, state)) return false
        val drops = ArrayList<ItemStack>()
        when (val b = state.block) {
            is CropBlock -> {
                drops.addAll(Block.getDrops(state, c.level, pos, null, c.e, ItemStack.EMPTY))
                // One seed goes straight back into the ground.
                drops.firstOrNull { it.`is`(b.asItem()) }?.shrink(1)
                c.level.setBlockAndUpdate(pos, b.getStateForAge(0))
            }
            is NetherWartBlock -> {
                drops.addAll(Block.getDrops(state, c.level, pos, null, c.e, ItemStack.EMPTY))
                drops.firstOrNull { it.`is`(Items.NETHER_WART) }?.shrink(1)
                c.level.setBlockAndUpdate(pos, state.setValue(NetherWartBlock.AGE, 0))
            }
            // Cobblemon's own harvest calls keep berry mutations and mulch working exactly as if a player picked them.
            is ApricornBlock -> { drops.addAll(Block.getDrops(state, c.level, pos, null, c.e, ItemStack.EMPTY)); c.level.setBlockAndUpdate(pos, state.setValue(ApricornBlock.AGE, ApricornBlock.MIN_AGE)) }
            is BerryBlock -> (c.level.getBlockEntity(pos) as? BerryBlockEntity)?.let { drops.addAll(it.harvest(c.level, state, pos)) } ?: return false
            else -> return false
        }
        // Bountiful Harvest (skill), the Harvest ability and careful natures sometimes double the yield.
        val bonus = c.skill("bountiful_harvest") + c.profile.style.bonusYield + if ("double_harvest" in c.profile.perks) 0.25 else 0.0
        if (c.level.random.nextDouble() < bonus) drops.toList().forEach { drops.add(it.copy()) }
        drops.forEach { c.carry(it) }
        Net.fx(c.level, c.e, Net.FX_HARVEST, pos, Block.getId(state))
        return true
    }
}

// ============================================================================================ Sorting and Hauling

object Logistics : JobLogic() {
    /** Where a load of [stack] should go, or null if no linked chest wants it. */
    private fun destination(c: Ctx, stack: ItemStack): BlockPos? {
        val sorting = c.s.job == Job.SORTING
        for (link in c.usableLinks) {
            val target = Inv.storage(c.level, link) ?: continue
            // A chest's own filter decides what may go into it.
            val f = c.s.filterFor(link)
            if (!f.allows(stack)) continue
            // Sorting tops up chests that already hold the item, or whose filter lists it; hauling fills the first chest with room.
            if (sorting && !(f.only && f.entries.isNotEmpty()) && !Inv.contains(target) { ItemStack.isSameItem(it, stack) }) continue
            if (Inv.fits(target, stack)) return link
        }
        return null
    }

    private fun movable(c: Ctx, stack: ItemStack) = c.s.filter.allows(stack) && destination(c, stack) != null

    override fun plan(c: Ctx): Step? {
        if (c.s.links.isEmpty()) { c.status("foremon.status.link_chests"); return null }
        if (c.w.carried.isNotEmpty()) {
            val dest = destination(c, c.w.carried[0])
            return if (dest != null) Step(dest, Step.Kind.DELIVER) else Step(c.s.pos, Step.Kind.CHEST)
        }
        return if (Inv.contains(c.storage) { movable(c, it) }) Step(c.s.pos, Step.Kind.CHEST) else null
    }

    override fun atChest(c: Ctx) {
        if (c.w.carried.isNotEmpty()) return
        // Heavy Load (level perk): bigger loads per trip.
        val load = (c.capacity() * c.profile.unlock(c.s.job, "load", 1.0)).toInt()
        val taken = Inv.extract(c.storage, load) { movable(c, it) }
        if (!taken.isEmpty) c.carry(taken)
    }

    override fun delivered(c: Ctx) {
        Net.fx(c.level, c.e, Net.FX_DELIVER, c.e.blockPosition(), 0)
    }

    // This job has no WORK step: the ticker counts each delivery as the finished action.
    override fun act(c: Ctx, step: Step) = false
}

// ============================================================================================ Smelting

object Smelting : JobLogic() {
    private val cache = HashMap<Item, ItemStack>()
    private var cacheVersion = -1

    private fun result(c: Ctx, stack: ItemStack): ItemStack {
        if (cacheVersion != Rules.version) { cache.clear(); cacheVersion = Rules.version }
        return cache.getOrPut(stack.item) {
            c.level.recipeManager.getRecipeFor(RecipeType.SMELTING, SingleRecipeInput(stack), c.level)
                .map { it.value().getResultItem(c.level.registryAccess()).copy() }.orElse(ItemStack.EMPTY)
        }
    }

    private fun smeltable(c: Ctx, stack: ItemStack) = c.s.filter.allows(stack) && !result(c, stack).isEmpty

    override fun keepsSupplies(c: Ctx) = c.w.supplies.isNotEmpty()

    override fun plan(c: Ctx): Step? {
        if (c.w.supplies.isNotEmpty() && !c.carriedFull()) return Step(c.s.pos, Step.Kind.WORK)
        if (c.w.carried.isNotEmpty()) return Step(c.s.pos, Step.Kind.CHEST)
        return if (Inv.contains(c.storage) { smeltable(c, it) }) Step(c.s.pos, Step.Kind.CHEST) else null
    }

    override fun atChest(c: Ctx) {
        if (c.w.supplies.isNotEmpty()) return
        val taken = Inv.extract(c.storage, min(c.capacity(), 64)) { smeltable(c, it) }
        if (!taken.isEmpty) c.w.supplies.add(taken)
    }

    override fun repeats(c: Ctx, step: Step) = c.w.supplies.isNotEmpty() && !c.carriedFull()

    override fun act(c: Ctx, step: Step): Boolean {
        val input = c.w.supplies.firstOrNull() ?: return false
        val out = result(c, input)
        if (out.isEmpty) { c.carry(input); c.w.supplies.clear(); return false }
        // Twin Furnace (skill) cooks two at once.
        // Big Batch (level perk) or the Twin Furnace skill: several items per action.
        val n = min(input.count, max(c.profile.unlock(Job.SMELTING, "batch", 1.0).toInt(), if (c.has("twin_furnace")) 2 else 1))
        input.shrink(n)
        c.w.supplies.removeIf { it.isEmpty }
        c.carry(out.copyWithCount(out.count * n))
        Net.fx(c.level, c.e, Net.FX_SMELT, c.e.blockPosition(), 0)
        return true
    }
}

// ============================================================================================ Building

object Building : JobLogic() {
    /** What a block costs to place. Empty with free=true means it comes with its other half; empty with free=false means it cannot be built. */
    class Cost(val item: Item, val count: Int, val free: Boolean)

    private val NEVER = setOf(
        Blocks.BEDROCK, Blocks.BARRIER, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
        Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW, Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME, Blocks.NETHER_PORTAL, Blocks.END_GATEWAY,
        Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.BUDDING_AMETHYST, Blocks.REINFORCED_DEEPSLATE, Blocks.LIGHT,
        Blocks.PISTON_HEAD, Blocks.MOVING_PISTON, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.FROSTED_ICE
    )

    fun cost(state: BlockState): Cost {
        val b = state.block
        if (b in NEVER || !state.fluidState.isEmpty && b.asItem() == Items.AIR) return Cost(Items.AIR, 0, false)
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return Cost(Items.AIR, 0, true)
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return Cost(Items.AIR, 0, true)
        // Blocks whose state is not what you hold in your hand.
        val item = when (b) {
            Blocks.FARMLAND, Blocks.DIRT_PATH -> Items.DIRT
            else -> b.asItem()
        }
        if (item == Items.AIR) return Cost(Items.AIR, 0, false)
        val count = when {
            state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE -> 2
            state.hasProperty(BlockStateProperties.LAYERS) -> state.getValue(BlockStateProperties.LAYERS)
            state.hasProperty(BlockStateProperties.CANDLES) -> state.getValue(BlockStateProperties.CANDLES)
            state.hasProperty(BlockStateProperties.PICKLES) -> state.getValue(BlockStateProperties.PICKLES)
            state.hasProperty(BlockStateProperties.EGGS) -> state.getValue(BlockStateProperties.EGGS)
            state.hasProperty(BlockStateProperties.FLOWER_AMOUNT) -> state.getValue(BlockStateProperties.FLOWER_AMOUNT)
            else -> 1
        }
        return Cost(item, count, false)
    }

    /** What actually gets placed: never waterlogged, since workers do not carry water. */
    private fun placeable(state: BlockState): BlockState =
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) state.setValue(BlockStateProperties.WATERLOGGED, false) else state

    private fun done(have: BlockState, want: BlockState) = have == want || have == placeable(want)

    /** Moves the plan's front past everything already built or unbuildable, and returns false when the build is finished. */
    private fun advance(c: Ctx): Boolean {
        val plan = c.s.plan ?: return false
        var guard = 400
        while (plan.index < plan.size && guard-- > 0) {
            val pos = plan.pos(plan.index); val want = plan.state(plan.index)
            if (!c.level.isLoaded(pos)) { c.status("foremon.status.not_loaded"); return true }
            val have = c.level.getBlockState(pos)
            val cost = cost(want)
            val unbuildable = !cost.free && cost.count == 0
            val blocked = !have.canBeReplaced() && !done(have, want)
            if (done(have, want)) plan.index++
            else if (unbuildable || blocked || !c.s.filter.allows(want)) { plan.index++; plan.skipped++ }
            else break
        }
        Ticker.spend(400 - guard)
        if (plan.finished) {
            c.s.active = false
            c.status("foremon.status.build_done", "${plan.placed}|${plan.skipped}")
            c.s.workers.forEach { w -> Workers.entity(c.level, w)?.cry() }
            return false
        }
        return true
    }

    override fun keepsSupplies(c: Ctx) = c.w.supplies.isNotEmpty()

    override fun plan(c: Ctx): Step? {
        val plan = c.s.plan ?: run { c.status("foremon.status.no_plan"); return null }
        if (!advance(c)) return null
        // Look a little ahead for an entry this worker can do right now.
        var i = plan.index
        var looked = 0
        var needsFetch = false
        while (i < plan.size && looked < 96) {
            val pos = plan.pos(i); val want = plan.state(i)
            i++
            val have = c.level.getBlockState(pos)
            if (done(have, want) || !have.canBeReplaced()) continue
            val cost = cost(want)
            if (!cost.free && cost.count == 0) continue
            looked++
            if (!c.free(pos)) continue
            if (cost.free || c.w.supplies.any { it.`is`(cost.item) && it.count >= cost.count }) {
                val fluid = blockedByFluid(c, pos)
                if (fluid != null) { c.s.skip[pos] = c.now + 1200; c.status(fluid); continue }
                return Step(pos, Step.Kind.WORK, i - 1)
            }
            needsFetch = true
        }
        Ticker.spend(looked * 2)
        return if (needsFetch) Step(c.s.pos, Step.Kind.CHEST) else null
    }

    /** At the chest: put back what is left over, then load up for the next stretch of the plan. */
    override fun atChest(c: Ctx) {
        val plan = c.s.plan ?: return
        for (st in c.w.supplies) { val left = Inv.insert(c.storage, st); if (!left.isEmpty) c.w.carried.add(left) }
        c.w.supplies.clear()
        var room = c.capacity()
        var i = plan.index
        var looked = 0
        var missing: Item? = null
        val wanted = LinkedHashMap<Item, Int>()
        // What the other builders already hold covers the first entries that need it, so a crew shares the
        // materials out instead of everyone grabbing the same blocks and leaving the chest empty for the rest.
        val heldByOthers = HashMap<Item, Int>()
        for (o in c.s.workers) if (o !== c.w) for (st in o.supplies) heldByOthers.merge(st.item, st.count, Int::plus)
        while (i < plan.size && looked < 256 && room > 0) {
            val pos = plan.pos(i); val want = plan.state(i); i++
            if (!c.level.isLoaded(pos)) break
            val have = c.level.getBlockState(pos)
            if (done(have, want) || !have.canBeReplaced()) continue
            val cost = cost(want)
            if (cost.count == 0) continue
            looked++
            val covered = heldByOthers[cost.item] ?: 0
            if (covered >= cost.count) { heldByOthers[cost.item] = covered - cost.count; continue }
            val n = min(cost.count, room)
            wanted.merge(cost.item, n, Int::plus); room -= n
        }
        // c.storage is the site chest plus every extra chest added to it, so big builds can keep their materials in many chests.
        for ((item, n) in wanted) {
            val got = Inv.extract(c.storage, item, n)
            if (!got.isEmpty) c.merge(c.w.supplies, got)
            else if (missing == null) missing = item
        }
        if (c.w.supplies.isEmpty() && missing != null) {
            c.status("foremon.status.need_item", ItemStack(missing).hoverName.string)
            c.w.planAt = c.now + 60
        }
    }

    override fun stillValid(c: Ctx, step: Step): Boolean {
        val plan = c.s.plan ?: return false
        return step.data in 0 until plan.size && plan.pos(step.data) == step.pos && !done(c.level.getBlockState(step.pos), plan.state(step.data))
    }

    override fun act(c: Ctx, step: Step): Boolean {
        val plan = c.s.plan ?: return false
        var placed = place(c, plan.state(step.data), step.pos)
        // Quick Hands (level perk) or the Master Builder skill: more blocks in the same action when they are in reach.
        var more = max(c.profile.unlock(Job.BUILDING, "multi", 1.0).toInt() - 1, if (c.has("master_builder")) 1 else 0)
        if (placed && more > 0) {
            var i = plan.index
            var looked = 0
            while (i < plan.size && looked++ < 32 && more > 0) {
                val pos = plan.pos(i); val want = plan.state(i); i++
                if (pos == step.pos || pos.distSqr(step.pos) > 9 || done(c.level.getBlockState(pos), want) || !c.free(pos)) continue
                if (place(c, want, pos)) { more--; plan.placed++ }
            }
        }
        if (placed) plan.placed++
        return placed
    }

    private fun place(c: Ctx, want: BlockState, pos: BlockPos): Boolean {
        val have = c.level.getBlockState(pos)
        if (done(have, want) || !have.canBeReplaced()) return false
        if (!Protection.canPlace(c.level, c.s, pos)) { c.s.skip[pos] = c.now + 6000; c.status("foremon.status.not_allowed", "${pos.x} ${pos.y} ${pos.z}"); return false }
        val cost = cost(want)
        if (!cost.free) {
            if (cost.count == 0) return false
            val stack = c.w.supplies.firstOrNull { it.`is`(cost.item) && it.count >= cost.count } ?: return false
            stack.shrink(cost.count)
            c.w.supplies.removeIf { it.isEmpty }
        }
        val state = placeable(want)
        // UPDATE_KNOWN_SHAPE: half-built neighbours must not knock torches, doors and the like off again.
        c.level.setBlock(pos, state, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE)
        c.level.playSound(null, pos, state.soundType.placeSound, SoundSource.BLOCKS, 1f, 1f)
        Net.fx(c.level, c.e, Net.FX_PLACE, pos, Block.getId(state))
        Protection.audit(c.level, c.s, "placed", pos, state)
        return true
    }
}
