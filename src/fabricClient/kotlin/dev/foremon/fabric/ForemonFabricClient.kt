package dev.foremon.fabric

import dev.foremon.client.ForemonClient
import dev.foremon.client.LitematicaCompat
import dev.foremon.net.Net
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents

/** Fabric client entry point: forwards Fabric's client events to the shared client code. */
object ForemonFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(Net.Fx.TYPE) { p, ctx -> ctx.client().execute { ForemonClient.receive(p) } }
        ClientPlayNetworking.registerGlobalReceiver(Net.EmoteP.TYPE) { p, ctx -> ctx.client().execute { ForemonClient.receive(p) } }
        ClientPlayNetworking.registerGlobalReceiver(Net.View.TYPE) { p, ctx -> ctx.client().execute { ForemonClient.receive(p) } }
        ClientPlayNetworking.registerGlobalReceiver(Net.Markers.TYPE) { p, ctx -> ctx.client().execute { ForemonClient.receive(p) } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> ForemonClient.onDisconnect() }
        ClientTickEvents.END_CLIENT_TICK.register(LitematicaCompat::tick)
        WorldRenderEvents.AFTER_ENTITIES.register { ctx ->
            ForemonClient.render(ctx.matrixStack() ?: return@register, ctx.consumers() ?: return@register, ctx.camera(), ctx.tickCounter().getGameTimeDeltaPartialTick(false))
        }
    }
}
