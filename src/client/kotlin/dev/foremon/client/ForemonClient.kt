package dev.foremon.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.foremon.net.Net
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** The client side of the mod. Each loader's client entry point forwards its events here. */
object ForemonClient {
    /** A packet from the server, on the client thread. */
    fun receive(p: CustomPacketPayload) {
        when (p) {
            is Net.Fx -> WorkFx.play(p)
            is Net.EmoteP -> Emotes.set(p.entityId, p.emote)
            is Net.View -> view(Minecraft.getInstance(), p.kind, p.tag)
            is Net.Markers -> ChestMarkers.set(p.positions, p.kinds, p.balls)
        }
    }

    fun send(p: CustomPacketPayload) { Minecraft.getInstance().connection?.send(ServerboundCustomPayloadPacket(p)) }

    fun onDisconnect() { Emotes.clear(); ChestMarkers.clear(); HaulBoards.clear() }

    /** Drawn in the world after entities: emotes, chest markers and Hauling boards. */
    fun render(pose: PoseStack, buffers: MultiBufferSource, camera: Camera, partial: Float) {
        Emotes.render(pose, buffers, camera, partial); ChestMarkers.render(pose, buffers, camera, partial); HaulBoards.render(pose, buffers, camera, partial)
    }

    private fun view(mc: Minecraft, kind: String, tag: CompoundTag) {
        when (kind) {
            "site" -> {
                val cur = mc.screen
                if (cur is SiteScreen && cur.data.getLong("pos") == tag.getLong("pos")) cur.update(tag)
                // A refresh (no party in it) that arrives after the screen was closed must not open it again.
                else if (tag.contains("party")) mc.setScreen(SiteScreen(tag))
            }
            "board", "skills", "dex", "admin", "perks" -> {
                val hub = mc.screen as? HubScreen ?: HubScreen().also { mc.setScreen(it) }
                hub.accept(kind, tag)
            }
            "pc" -> (mc.screen as? SiteScreen)?.let { if (it.data.getLong("pos") == tag.getLong("pos")) it.pc = tag }
            "notify" -> if (mc.screen !is ForemonScreen) {
                val text = trArg(tag.getString("key"), tag.getString("arg"))
                SystemToast.addOrUpdate(mc.toasts, SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal("Foremon"), text)
            }
            "close" -> if (mc.screen is ForemonScreen) mc.setScreen(null)
            "haul" -> HaulBoards.set(tag)
        }
    }
}
