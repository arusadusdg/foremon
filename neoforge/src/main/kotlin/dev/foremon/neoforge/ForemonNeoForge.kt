package dev.foremon.neoforge

import dev.foremon.Config
import dev.foremon.Foremon
import dev.foremon.Rules
import dev.foremon.net.Net
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.AddReloadListenerEvent
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.registries.RegisterEvent

/** NeoForge entry point: registers the mod's things and forwards NeoForge's events to the shared code. */
@Mod(Foremon.ID)
class ForemonNeoForge(modBus: IEventBus) {
    init {
        modBus.addListener(RegisterEvent::class.java) { e -> e.register(Registries.ITEM, Foremon.id("whistle")) { Foremon.WHISTLE } }
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { e ->
            if (e.tabKey == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(Foremon.WHISTLE)
        }
        modBus.addListener(RegisterPayloadHandlersEvent::class.java, ::payloads)

        val bus = NeoForge.EVENT_BUS
        bus.addListener(AddReloadListenerEvent::class.java) { e -> e.addListener(ResourceManagerReloadListener(Rules::reload)) }
        bus.addListener(ServerAboutToStartEvent::class.java) { Config.load() }
        bus.addListener(LevelTickEvent.Pre::class.java) { e -> (e.level as? ServerLevel)?.let(Foremon::tick) }
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { e -> (e.entity as? ServerPlayer)?.let(Foremon::onDisconnect) }
        bus.addListener(PlayerEvent.StartTracking::class.java) { e -> (e.entity as? ServerPlayer)?.let { Net.onStartTracking(e.target, it) } }
        bus.addListener(PlayerInteractEvent.RightClickBlock::class.java) { e ->
            val r = Foremon.useOn(e.entity, e.level, e.hand, e.pos)
            if (r != InteractionResult.PASS) { e.isCanceled = true; e.cancellationResult = r }
        }
        bus.addListener(PlayerInteractEvent.RightClickItem::class.java) { e ->
            val r = Foremon.use(e.entity, e.hand).result
            if (r != InteractionResult.PASS) { e.isCanceled = true; e.cancellationResult = r }
        }
        bus.addListener(RegisterCommandsEvent::class.java) { e -> e.dispatcher.register(Foremon.command()) }
    }

    private fun payloads(e: RegisterPayloadHandlersEvent) {
        // Optional, so a player without the mod can still join a server that has it; they get plain-text messages instead of menus.
        val r = e.registrar("1").optional()
        r.playToClient(Net.Fx.TYPE, Net.Fx.CODEC) { p, _ -> client?.invoke(p) }
        r.playToClient(Net.EmoteP.TYPE, Net.EmoteP.CODEC) { p, _ -> client?.invoke(p) }
        r.playToClient(Net.View.TYPE, Net.View.CODEC) { p, _ -> client?.invoke(p) }
        r.playToClient(Net.Markers.TYPE, Net.Markers.CODEC) { p, _ -> client?.invoke(p) }
        r.playToServer(Net.Action.TYPE, Net.Action.CODEC) { p, ctx -> Net.onAction(ctx.player() as ServerPlayer, p) }
        r.playToServer(Net.PlanPart.TYPE, Net.PlanPart.CODEC) { p, ctx -> Net.onPlanPart(ctx.player() as ServerPlayer, p) }
    }

    companion object {
        /** Set by the client entry point. Null on a dedicated server, which never receives these packets. */
        @Volatile var client: ((CustomPacketPayload) -> Unit)? = null
    }
}
