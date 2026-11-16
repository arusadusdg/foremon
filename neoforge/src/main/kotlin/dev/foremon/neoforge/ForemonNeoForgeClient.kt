package dev.foremon.neoforge

import dev.foremon.Foremon
import dev.foremon.client.ForemonClient
import dev.foremon.client.LitematicaCompat
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge

/** NeoForge client entry point: forwards NeoForge's client events to the shared client code. */
@Mod(value = Foremon.ID, dist = [Dist.CLIENT])
class ForemonNeoForgeClient {
    init {
        ForemonNeoForge.client = ForemonClient::receive
        val bus = NeoForge.EVENT_BUS
        bus.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { ForemonClient.onDisconnect() }
        bus.addListener(ClientTickEvent.Post::class.java) { LitematicaCompat.tick(Minecraft.getInstance()) }
        bus.addListener(RenderLevelStageEvent::class.java) { e ->
            if (e.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return@addListener
            // Same spot and pose as Fabric's AFTER_ENTITIES; the game draws these buffers later in the frame.
            ForemonClient.render(e.poseStack, Minecraft.getInstance().renderBuffers().bufferSource(), e.camera, e.partialTick.getGameTimeDeltaPartialTick(false))
        }
    }
}
