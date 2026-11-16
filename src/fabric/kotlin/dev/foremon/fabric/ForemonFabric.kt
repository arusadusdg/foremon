package dev.foremon.fabric

import dev.foremon.Config
import dev.foremon.Foremon
import dev.foremon.Rules
import dev.foremon.net.Net
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.item.CreativeModeTabs

/** Fabric entry point: registers the mod's things and forwards Fabric's events to the shared code. */
object ForemonFabric : ModInitializer {
    override fun onInitialize() {
        Registry.register(BuiltInRegistries.ITEM, Foremon.id("whistle"), Foremon.WHISTLE)
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register { it.accept(Foremon.WHISTLE) }
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(object : SimpleSynchronousResourceReloadListener {
            override fun getFabricId() = Foremon.id("rules")
            override fun onResourceManagerReload(manager: ResourceManager) = Rules.reload(manager)
        })

        PayloadTypeRegistry.playS2C().register(Net.Fx.TYPE, Net.Fx.CODEC)
        PayloadTypeRegistry.playS2C().register(Net.EmoteP.TYPE, Net.EmoteP.CODEC)
        PayloadTypeRegistry.playS2C().register(Net.View.TYPE, Net.View.CODEC)
        PayloadTypeRegistry.playS2C().register(Net.Markers.TYPE, Net.Markers.CODEC)
        PayloadTypeRegistry.playC2S().register(Net.Action.TYPE, Net.Action.CODEC)
        PayloadTypeRegistry.playC2S().register(Net.PlanPart.TYPE, Net.PlanPart.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(Net.Action.TYPE) { p, ctx -> ctx.server().execute { Net.onAction(ctx.player(), p) } }
        ServerPlayNetworking.registerGlobalReceiver(Net.PlanPart.TYPE) { p, ctx -> ctx.server().execute { Net.onPlanPart(ctx.player(), p) } }
        EntityTrackingEvents.START_TRACKING.register(Net::onStartTracking)

        ServerLifecycleEvents.SERVER_STARTING.register { Config.load() }
        ServerTickEvents.START_WORLD_TICK.register(Foremon::tick)
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> Foremon.onDisconnect(handler.player) }
        UseBlockCallback.EVENT.register { player, level, hand, hit -> Foremon.useOn(player, level, hand, hit.blockPos) }
        UseItemCallback.EVENT.register { player, _, hand -> Foremon.use(player, hand) }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> dispatcher.register(Foremon.command()) }
    }
}
