package dev.foremon.client

import dev.foremon.net.Net
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component

object ForemonClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(Net.Fx.TYPE) { p, ctx -> ctx.client().execute { WorkFx.play(p) } }
        ClientPlayNetworking.registerGlobalReceiver(Net.EmoteP.TYPE) { p, ctx -> ctx.client().execute { Emotes.set(p.entityId, p.emote) } }
        ClientPlayNetworking.registerGlobalReceiver(Net.View.TYPE) { p, ctx -> ctx.client().execute { view(ctx.client(), p.kind, p.tag) } }
        ClientPlayNetworking.registerGlobalReceiver(Net.Markers.TYPE) { p, ctx -> ctx.client().execute { ChestMarkers.set(p.positions, p.kinds, p.balls) } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Emotes.clear(); ChestMarkers.clear() }
        WorldRenderEvents.AFTER_ENTITIES.register { Emotes.render(it); ChestMarkers.render(it) }
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
        }
    }
}
