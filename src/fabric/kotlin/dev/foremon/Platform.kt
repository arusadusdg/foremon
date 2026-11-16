package dev.foremon

import com.mojang.authlib.GameProfile
import dev.foremon.work.Store
import net.fabricmc.fabric.api.entity.FakePlayer
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.nio.file.Path

/** What differs between loaders, Fabric side. The NeoForge build has its own Platform with the same members. */
object Platform {
    val configDir: Path get() = FabricLoader.getInstance().configDir
    val gameDir: Path get() = FabricLoader.getInstance().gameDir

    fun isModLoaded(id: String) = FabricLoader.getInstance().isModLoaded(id)

    /** The container at [pos], through Fabric's transfer API, so modded storage works like a vanilla chest. */
    fun storage(level: ServerLevel, pos: BlockPos): Store? =
        (ItemStorage.SIDED.find(level, pos, null) ?: ItemStorage.SIDED.find(level, pos, Direction.UP))?.let(::FabricStore)

    fun fakePlayer(level: ServerLevel, profile: GameProfile): ServerPlayer = FakePlayer.get(level, profile)

    /** Asks claim and protection mods whether [player] may break the block at [pos]. */
    fun mayBreak(level: ServerLevel, player: Player, pos: BlockPos, state: BlockState, be: BlockEntity?): Boolean =
        PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, be)

    /** False for a player whose client does not have the mod. */
    fun canSend(player: ServerPlayer, type: CustomPacketPayload.Type<*>) = ServerPlayNetworking.canSend(player, type)
}

/** Every move is its own transaction, so a take or put that fails half way changes nothing. */
private class FabricStore(val st: Storage<ItemVariant>) : Store {
    override fun contents(): Sequence<ItemStack> =
        st.nonEmptyViews().asSequence().map { it.resource.toStack(it.amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }

    override fun insert(stack: ItemStack, simulate: Boolean): Int {
        if (stack.isEmpty) return 0
        Transaction.openOuter().use { tx ->
            val moved = st.insert(ItemVariant.of(stack), stack.count.toLong(), tx).toInt()
            if (!simulate) tx.commit()
            return moved
        }
    }

    override fun extract(like: ItemStack, max: Int, simulate: Boolean): Int {
        if (max <= 0 || like.isEmpty) return 0
        Transaction.openOuter().use { tx ->
            val moved = st.extract(ItemVariant.of(like), max.toLong(), tx).toInt()
            if (!simulate) tx.commit()
            return moved
        }
    }

    override fun equals(other: Any?) = other is FabricStore && other.st === st
    override fun hashCode() = System.identityHashCode(st)
}
