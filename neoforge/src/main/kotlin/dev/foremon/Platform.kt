package dev.foremon

import com.mojang.authlib.GameProfile
import dev.foremon.work.Store
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.ItemHandlerHelper
import java.nio.file.Path

/** What differs between loaders, NeoForge side. The Fabric build has its own Platform with the same members. */
object Platform {
    val configDir: Path get() = FMLPaths.CONFIGDIR.get()
    val gameDir: Path get() = FMLPaths.GAMEDIR.get()

    fun isModLoaded(id: String) = ModList.get().isLoaded(id)

    /** The container at [pos], through NeoForge's item handler capability, so modded storage works like a vanilla chest. */
    fun storage(level: ServerLevel, pos: BlockPos): Store? =
        (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null)
            ?: level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP))?.let(::HandlerStore)

    fun fakePlayer(level: ServerLevel, profile: GameProfile): ServerPlayer = FakePlayerFactory.get(level, profile)

    /** Asks claim and protection mods whether [player] may break the block at [pos]. */
    fun mayBreak(level: ServerLevel, player: Player, pos: BlockPos, state: BlockState, be: BlockEntity?): Boolean =
        !NeoForge.EVENT_BUS.post(BlockEvent.BreakEvent(level, pos, state, player)).isCanceled

    /** False for a player whose client does not have the mod. */
    fun canSend(player: ServerPlayer, type: CustomPacketPayload.Type<*>) = player.connection.hasChannel(type)
}

private class HandlerStore(val h: IItemHandler) : Store {
    override fun contents(): Sequence<ItemStack> =
        (0 until h.slots).asSequence().map { h.getStackInSlot(it) }.filter { !it.isEmpty }.map { it.copy() }

    override fun insert(stack: ItemStack, simulate: Boolean): Int =
        if (stack.isEmpty) 0 else stack.count - ItemHandlerHelper.insertItemStacked(h, stack.copy(), simulate).count

    override fun extract(like: ItemStack, max: Int, simulate: Boolean): Int {
        var got = 0
        for (i in 0 until h.slots) {
            if (got >= max) break
            if (ItemStack.isSameItemSameComponents(h.getStackInSlot(i), like)) got += h.extractItem(i, max - got, simulate).count
        }
        return got
    }
}
