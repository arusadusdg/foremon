package dev.foremon.world

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.world.level.block.state.BlockState

/**
 * A structure to build: world positions and the block wanted at each, already in build order.
 * Stored as a palette plus two flat arrays so a 100k-block build costs about 1.2 MB in memory
 * and saves compactly with the job site.
 */
class Plan(val name: String, val positions: LongArray, val states: IntArray, val palette: List<BlockState>) {
    /** Entries before this are finished (or were skipped for good). */
    var index = 0
    var placed = 0
    var skipped = 0

    val size get() = positions.size
    val finished get() = index >= size

    fun pos(i: Int): BlockPos = BlockPos.of(positions[i])
    fun state(i: Int): BlockState = palette[states[i]]

    val min: BlockPos by lazy { corner { a, b -> minOf(a, b) } }
    val max: BlockPos by lazy { corner { a, b -> maxOf(a, b) } }

    private fun corner(pick: (Int, Int) -> Int): BlockPos {
        if (positions.isEmpty()) return BlockPos.ZERO
        var x = BlockPos.getX(positions[0]); var y = BlockPos.getY(positions[0]); var z = BlockPos.getZ(positions[0])
        for (p in positions) { x = pick(x, BlockPos.getX(p)); y = pick(y, BlockPos.getY(p)); z = pick(z, BlockPos.getZ(p)) }
        return BlockPos(x, y, z)
    }

    fun save() = CompoundTag().also { t ->
        t.putString("name", name); t.putInt("index", index); t.putInt("placed", placed); t.putInt("skipped", skipped)
        t.putLongArray("positions", positions); t.putIntArray("states", states)
        t.put("palette", ListTag().also { l -> palette.forEach { l.add(NbtUtils.writeBlockState(it)) } })
    }

    companion object {
        fun load(t: CompoundTag): Plan? {
            val blocks = BuiltInRegistries.BLOCK.asLookup()
            val palette = t.getList("palette", Tag.TAG_COMPOUND.toInt()).map { NbtUtils.readBlockState(blocks, it as CompoundTag) }
            val positions = t.getLongArray("positions"); val states = t.getIntArray("states")
            // A plan from a removed mod or a damaged save is dropped rather than crashing the world load.
            if (positions.size != states.size || states.any { it !in palette.indices }) return null
            return Plan(t.getString("name"), positions, states, palette).also {
                it.index = t.getInt("index").coerceIn(0, positions.size); it.placed = t.getInt("placed"); it.skipped = t.getInt("skipped")
            }
        }

        /**
         * Build order: bottom layer first; inside a layer, solid blocks before anything that hangs off
         * them (torches, doors, rails, redstone), and gravity-affected or fluid-like blocks last.
         */
        fun ordered(name: String, blocks: List<Pair<BlockPos, BlockState>>): Plan {
            val sorted = blocks.sortedWith(compareBy({ it.first.y }, { rank(it.second) }, { it.first.x }, { it.first.z }))
            val palette = ArrayList<BlockState>(); val lookup = HashMap<BlockState, Int>()
            val positions = LongArray(sorted.size); val states = IntArray(sorted.size)
            sorted.forEachIndexed { i, (p, s) ->
                positions[i] = p.asLong()
                states[i] = lookup.getOrPut(s) { palette.add(s); palette.size - 1 }
            }
            return Plan(name, positions, states, palette)
        }

        private fun rank(s: BlockState): Int = when {
            s.block is net.minecraft.world.level.block.FallingBlock -> 2
            !s.fluidState.isEmpty -> 3
            s.canOcclude() -> 0
            else -> 1
        }
    }
}
