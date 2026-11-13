package dev.foremon.work

import com.mojang.authlib.GameProfile
import dev.foremon.Config
import dev.foremon.Foremon
import dev.foremon.world.Site
import net.fabricmc.fabric.api.entity.FakePlayer
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage
import net.fabricmc.fabric.api.transfer.v1.storage.base.FilteringStorage
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * All item movement goes through Fabric's transfer API with transactions. That makes every
 * take-and-put atomic (no duplication if something fails half way) and makes modded storage
 * work the same as vanilla chests, with no hard-coded list of container blocks.
 */
object Inv {
    fun storage(level: ServerLevel, pos: BlockPos): Storage<ItemVariant>? =
        ItemStorage.SIDED.find(level, pos, null) ?: ItemStorage.SIDED.find(level, pos, Direction.UP)

    /**
     * A job site's whole storage: its own chest first, then every extra chest added to it.
     * Looked up fresh each time it is needed, never cached, so a chest that was just broken can
     * never be written to. Sorting and Hauling keep the extra chests as destinations instead.
     */
    fun pool(level: ServerLevel, s: Site, main: Storage<ItemVariant>, links: List<BlockPos>): Storage<ItemVariant> {
        if (links.isEmpty() || s.job == dev.foremon.rules.Job.SORTING || s.job == dev.foremon.rules.Job.HAULING) return main
        val parts = ArrayList<Storage<ItemVariant>>(links.size + 1)
        parts.add(main)
        for (l in links) storage(level, l)?.let { st ->
            if (st === main) return@let
            // An extra chest with a filter only takes the items its filter allows; anything may be taken out of it.
            val f = s.filterFor(l)
            parts.add(if (f.entries.isEmpty()) st else object : FilteringStorage<ItemVariant>(st) {
                override fun canInsert(resource: ItemVariant) = f.allows(resource.toStack())
            })
        }
        return if (parts.size == 1) main else CombinedStorage(parts)
    }

    /** Inserts as much as fits and returns what did not. */
    fun insert(storage: Storage<ItemVariant>, stack: ItemStack): ItemStack {
        if (stack.isEmpty) return ItemStack.EMPTY
        Transaction.openOuter().use { tx ->
            val moved = storage.insert(ItemVariant.of(stack), stack.count.toLong(), tx).toInt()
            tx.commit()
            return stack.copyWithCount(stack.count - moved)
        }
    }

    /** Takes up to [max] of the first stored item that passes [test]. */
    fun extract(storage: Storage<ItemVariant>, max: Int, test: (ItemStack) -> Boolean): ItemStack {
        if (max <= 0) return ItemStack.EMPTY
        for (view in storage.nonEmptyViews()) {
            val variant = view.resource
            if (!test(variant.toStack())) continue
            Transaction.openOuter().use { tx ->
                val moved = storage.extract(variant, max.toLong(), tx).toInt()
                tx.commit()
                if (moved > 0) return variant.toStack(moved)
            }
        }
        return ItemStack.EMPTY
    }

    fun extract(storage: Storage<ItemVariant>, item: Item, max: Int): ItemStack = extract(storage, max) { it.`is`(item) }

    fun contains(storage: Storage<ItemVariant>, test: (ItemStack) -> Boolean): Boolean {
        for (view in storage.nonEmptyViews()) if (test(view.resource.toStack())) return true
        return false
    }

    /** True if at least one of [stack] would fit. Simulated: the transaction is never committed. */
    fun fits(storage: Storage<ItemVariant>, stack: ItemStack): Boolean =
        Transaction.openOuter().use { tx -> storage.insert(ItemVariant.of(stack), 1, tx) > 0 }
}

/** Decides whether a worker may change a block. Asks as the owner, so claim and protection mods get their say. */
object Protection {
    private fun fake(level: ServerLevel, s: Site): FakePlayer =
        FakePlayer.get(level, GameProfile(s.owner, s.ownerName.ifBlank { "foremon" }.take(16)))

    private fun allowedHere(level: ServerLevel, s: Site, pos: BlockPos): Boolean {
        if (pos == s.pos || pos in s.links) return false
        if (!level.isInWorldBounds(pos)) return false
        return level.mayInteract(fake(level, s), pos)
    }

    fun canBreak(level: ServerLevel, s: Site, pos: BlockPos, state: BlockState): Boolean {
        if (!Config.d.workersMayBreakBlocks || !allowedHere(level, s, pos)) return false
        if (state.getDestroySpeed(level, pos) < 0f) return false
        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, fake(level, s), pos, state, level.getBlockEntity(pos))
    }

    fun canPlace(level: ServerLevel, s: Site, pos: BlockPos): Boolean {
        if (!Config.d.workersMayPlaceBlocks || !allowedHere(level, s, pos)) return false
        // There is no common "may place" event on Fabric; the break check is what claim mods hook, so ask that for this spot.
        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, fake(level, s), pos, level.getBlockState(pos), null)
    }

    /**
     * May this real player use the container at [pos]? There is no shared "may open" event on Fabric,
     * so this asks the block-break event, which is the one claim and protection mods guard.
     */
    fun playerMayUse(level: ServerLevel, player: net.minecraft.server.level.ServerPlayer, pos: BlockPos): Boolean =
        level.mayInteract(player, pos) &&
            PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, level.getBlockState(pos), level.getBlockEntity(pos))

    /**
     * May this site's workers use the container at [pos] on their owner's behalf? Asked again every
     * time it matters rather than once when the chest was added, so a claim made later still counts
     * and an extra chest can never become a way into somebody else's storage.
     */
    fun ownerMayUse(level: ServerLevel, s: Site, pos: BlockPos): Boolean {
        val fake = fake(level, s)
        return level.mayInteract(fake, pos) &&
            PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, fake, pos, level.getBlockState(pos), level.getBlockEntity(pos))
    }

    private val auditFile by lazy { FabricLoader.getInstance().gameDir.resolve("logs/foremon-audit.log") }

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
