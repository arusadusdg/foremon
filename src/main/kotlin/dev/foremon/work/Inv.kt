package dev.foremon.work

import com.mojang.authlib.GameProfile
import dev.foremon.Config
import dev.foremon.Foremon
import dev.foremon.Platform
import dev.foremon.world.Site
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * A container the workers use: a chest, a barrel or a modded storage. Each loader wraps its own item API in this
 * (Fabric's transfer API, NeoForge's item handlers), so modded storage works like a vanilla chest on both.
 */
interface Store {
    /** What is stored, one stack per slot, read as it is walked so a search can stop early. Copies: changing them changes nothing. */
    fun contents(): Sequence<ItemStack>
    /** Puts up to stack.count of [stack] in and returns how many went in. */
    fun insert(stack: ItemStack, simulate: Boolean): Int
    /** Takes up to [max] items like [like] (same item and components) and returns how many came out. */
    fun extract(like: ItemStack, max: Int, simulate: Boolean): Int
}

/** Several stores as one: put into the first that takes it, take from wherever it is. */
private class Pool(val parts: List<Store>) : Store {
    override fun contents() = parts.asSequence().flatMap { it.contents() }
    override fun insert(stack: ItemStack, simulate: Boolean): Int {
        var moved = 0
        for (p in parts) { if (moved >= stack.count) break; moved += p.insert(stack.copyWithCount(stack.count - moved), simulate) }
        return moved
    }
    override fun extract(like: ItemStack, max: Int, simulate: Boolean): Int {
        var moved = 0
        for (p in parts) { if (moved >= max) break; moved += p.extract(like, max - moved, simulate) }
        return moved
    }
}

/** A store that only takes items [allows] lets through; anything may be taken out. */
private class Filtered(val inner: Store, val allows: (ItemStack) -> Boolean) : Store by inner {
    override fun insert(stack: ItemStack, simulate: Boolean) = if (allows(stack)) inner.insert(stack, simulate) else 0
}

/** An item with its components, usable as a map key. */
class Kind(val stack: ItemStack) {
    override fun equals(other: Any?) = other is Kind && ItemStack.isSameItemSameComponents(stack, other.stack)
    override fun hashCode() = ItemStack.hashItemAndComponents(stack)
}

object Inv {
    fun storage(level: ServerLevel, pos: BlockPos): Store? = Platform.storage(level, pos)

    /**
     * A job site's whole storage: its own chest first, then every extra chest added to it.
     * Looked up fresh each time it is needed, never cached, so a chest that was just broken can
     * never be written to. Sorting and Hauling keep the extra chests as destinations instead.
     */
    fun pool(level: ServerLevel, s: Site, main: Store, links: List<BlockPos>): Store {
        if (links.isEmpty()) return main
        val parts = ArrayList<Store>(links.size + 1)
        parts.add(main)
        for (l in links) storage(level, l)?.let { st ->
            if (st == main) return@let
            // An extra chest with a filter only takes the items its filter allows; anything may be taken out of it.
            val f = s.filterFor(l)
            parts.add(if (f.entries.isEmpty()) st else Filtered(st) { f.allows(it) })
        }
        return if (parts.size == 1) main else Pool(parts)
    }

    /** Inserts as much as fits and returns what did not. */
    fun insert(storage: Store, stack: ItemStack): ItemStack {
        if (stack.isEmpty) return ItemStack.EMPTY
        return stack.copyWithCount(stack.count - storage.insert(stack, false))
    }

    /** Takes up to [max] of the first stored item that passes [test]. */
    fun extract(storage: Store, max: Int, test: (ItemStack) -> Boolean): ItemStack {
        if (max <= 0) return ItemStack.EMPTY
        for (s in storage.contents()) {
            if (!test(s)) continue
            val moved = storage.extract(s, max, false)
            if (moved > 0) return s.copyWithCount(moved)
        }
        return ItemStack.EMPTY
    }

    fun extract(storage: Store, item: Item, max: Int): ItemStack = extract(storage, max) { it.`is`(item) }

    fun contains(storage: Store, test: (ItemStack) -> Boolean): Boolean = storage.contents().any(test)

    /** How many of [item] are stored. */
    fun count(storage: Store, item: Item): Long = storage.contents().sumOf { if (it.`is`(item)) it.count.toLong() else 0L }

    /** The first stack [extract] would take, without taking it. */
    fun peek(storage: Store, max: Int, test: (ItemStack) -> Boolean): ItemStack {
        for (s in storage.contents()) {
            val stack = s.copyWithCount(minOf(s.count, max))
            if (test(stack)) return stack
        }
        return ItemStack.EMPTY
    }

    /** True if at least one of [stack] would fit. Simulated: nothing is moved. */
    fun fits(storage: Store, stack: ItemStack): Boolean = storage.insert(stack.copyWithCount(1), true) > 0
}

/** Decides whether a worker may change a block. Asks as the owner, so claim and protection mods get their say. */
object Protection {
    private fun fake(level: ServerLevel, s: Site): ServerPlayer =
        Platform.fakePlayer(level, GameProfile(s.owner, s.ownerName.ifBlank { "foremon" }.take(16)))

    private fun allowedHere(level: ServerLevel, s: Site, pos: BlockPos): Boolean {
        if (pos == s.pos || pos in s.links) return false
        if (!level.isInWorldBounds(pos)) return false
        return level.mayInteract(fake(level, s), pos)
    }

    fun canBreak(level: ServerLevel, s: Site, pos: BlockPos, state: BlockState): Boolean {
        if (!Config.d.workersMayBreakBlocks || !allowedHere(level, s, pos)) return false
        if (state.getDestroySpeed(level, pos) < 0f) return false
        return Platform.mayBreak(level, fake(level, s), pos, state, level.getBlockEntity(pos))
    }

    fun canPlace(level: ServerLevel, s: Site, pos: BlockPos): Boolean {
        if (!Config.d.workersMayPlaceBlocks || !allowedHere(level, s, pos)) return false
        // Not every loader has a common "may place" event; the break check is what claim mods hook, so ask that for this spot.
        return Platform.mayBreak(level, fake(level, s), pos, level.getBlockState(pos), null)
    }

    /**
     * May this real player use the container at [pos]? There is no shared "may open" event,
     * so this asks the block-break event, which is the one claim and protection mods guard.
     */
    fun playerMayUse(level: ServerLevel, player: ServerPlayer, pos: BlockPos): Boolean =
        level.mayInteract(player, pos) && Platform.mayBreak(level, player, pos, level.getBlockState(pos), level.getBlockEntity(pos))

    /**
     * May this site's workers use the container at [pos] on their owner's behalf? Asked again every
     * time it matters rather than once when the chest was added, so a claim made later still counts
     * and an extra chest can never become a way into somebody else's storage.
     */
    fun ownerMayUse(level: ServerLevel, s: Site, pos: BlockPos): Boolean {
        val fake = fake(level, s)
        return level.mayInteract(fake, pos) && Platform.mayBreak(level, fake, pos, level.getBlockState(pos), level.getBlockEntity(pos))
    }

    private val auditFile by lazy { Platform.gameDir.resolve("logs/foremon-audit.log") }

    fun audit(level: ServerLevel, s: Site, action: String, pos: BlockPos, state: BlockState) {
        if (!Config.a.auditLog) return
        try {
            Files.writeString(
                auditFile,
                "${java.time.Instant.now()} ${s.ownerName} $action ${state.block.descriptionId} ${level.dimension().location()} ${pos.x} ${pos.y} ${pos.z}\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
        } catch (e: Exception) {
            Foremon.LOG.warn("Could not write the audit log: {}", e.message)
        }
    }
}
