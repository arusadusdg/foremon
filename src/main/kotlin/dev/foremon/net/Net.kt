package dev.foremon.net

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import dev.foremon.Config
import dev.foremon.Foremon
import dev.foremon.world.Emote
import dev.foremon.world.SiteStore
import dev.foremon.world.ballIndex
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * All packets of the mod. Work effects are tiny fixed packets because they are frequent; menus use
 * one generic NBT packet each way because they are rare and their contents change as features grow.
 */
object Net {
    const val FX_BREAK = 0
    const val FX_PLACE = 1
    const val FX_HARVEST = 2
    const val FX_SMELT = 3
    const val FX_DELIVER = 4
    const val FX_LEVEL_UP = 5
    const val FX_LEAVES = 6

    /** Server to client: a worker did something at a block. The client turns it into particles and sound. */
    class Fx(val entityId: Int, val kind: Int, val pos: BlockPos, val state: Int) : CustomPacketPayload {
        override fun type() = TYPE
        companion object {
            val TYPE = CustomPacketPayload.Type<Fx>(Foremon.id("fx"))
            val CODEC: StreamCodec<FriendlyByteBuf, Fx> = StreamCodec.of(
                { b, p -> b.writeVarInt(p.entityId); b.writeByte(p.kind); b.writeBlockPos(p.pos); b.writeVarInt(p.state) },
                { b -> Fx(b.readVarInt(), b.readByte().toInt(), b.readBlockPos(), b.readVarInt()) })
        }
    }

    /** Server to client: the icon to show above a worker's head. */
    class EmoteP(val entityId: Int, val emote: Int) : CustomPacketPayload {
        override fun type() = TYPE
        companion object {
            val TYPE = CustomPacketPayload.Type<EmoteP>(Foremon.id("emote"))
            val CODEC: StreamCodec<FriendlyByteBuf, EmoteP> = StreamCodec.of(
                { b, p -> b.writeVarInt(p.entityId); b.writeByte(p.emote) },
                { b -> EmoteP(b.readVarInt(), b.readByte().toInt()) })
        }
    }

    /** Server to client: the job site chests and extra chests near the player, so the client can mark them. */
    class Markers(val positions: LongArray, val kinds: ByteArray, val balls: ByteArray) : CustomPacketPayload {
        override fun type() = TYPE
        companion object {
            const val ACTIVE = 0x10
            const val EXTRA = 0x20
            val TYPE = CustomPacketPayload.Type<Markers>(Foremon.id("markers"))
            val CODEC: StreamCodec<FriendlyByteBuf, Markers> = StreamCodec.of(
                { b, p -> b.writeLongArray(p.positions); b.writeByteArray(p.kinds); b.writeByteArray(p.balls) },
                { b -> Markers(b.readLongArray(), b.readByteArray(4096), b.readByteArray(4096)) })
        }
    }

    /** Server to client: data for a screen, or a notification. */
    class View(val kind: String, val tag: CompoundTag) : CustomPacketPayload {
        override fun type() = TYPE
        companion object {
            val TYPE = CustomPacketPayload.Type<View>(Foremon.id("view"))
            val CODEC: StreamCodec<FriendlyByteBuf, View> = StreamCodec.of(
                { b, p -> b.writeUtf(p.kind, 32); b.writeNbt(p.tag) },
                { b -> View(b.readUtf(32), b.readNbt() ?: CompoundTag()) })
        }
    }

    /** Client to server: something the player did in a screen. Everything in it is checked on the server. */
    class Action(val tag: CompoundTag) : CustomPacketPayload {
        override fun type() = TYPE
        companion object {
            val TYPE = CustomPacketPayload.Type<Action>(Foremon.id("action"))
            val CODEC: StreamCodec<FriendlyByteBuf, Action> = StreamCodec.of(
                { b, p -> b.writeNbt(p.tag) }, { b -> Action(b.readNbt() ?: CompoundTag()) })
        }
    }

    /** Client to server: one piece of a Litematica placement being uploaded for the Building job. */
    class PlanPart(val site: BlockPos, val name: String, val index: Int, val total: Int, val data: ByteArray) : CustomPacketPayload {
        override fun type() = TYPE
        companion object {
            const val MAX_BYTES = 24_000
            val TYPE = CustomPacketPayload.Type<PlanPart>(Foremon.id("plan_part"))
            val CODEC: StreamCodec<FriendlyByteBuf, PlanPart> = StreamCodec.of(
                { b, p -> b.writeBlockPos(p.site); b.writeUtf(p.name, 64); b.writeVarInt(p.index); b.writeVarInt(p.total); b.writeByteArray(p.data) },
                { b -> PlanPart(b.readBlockPos(), b.readUtf(64), b.readVarInt(), b.readVarInt(), b.readByteArray(MAX_BYTES + 64)) })
        }
    }

    fun register() {
        PayloadTypeRegistry.playS2C().register(Fx.TYPE, Fx.CODEC)
        PayloadTypeRegistry.playS2C().register(EmoteP.TYPE, EmoteP.CODEC)
        PayloadTypeRegistry.playS2C().register(View.TYPE, View.CODEC)
        PayloadTypeRegistry.playS2C().register(Markers.TYPE, Markers.CODEC)
        PayloadTypeRegistry.playC2S().register(Action.TYPE, Action.CODEC)
        PayloadTypeRegistry.playC2S().register(PlanPart.TYPE, PlanPart.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(Action.TYPE) { p, ctx ->
            val player = ctx.player()
            ctx.server().execute { Actions.guarded(player) { Actions.handle(player, p.tag) } }
        }
        ServerPlayNetworking.registerGlobalReceiver(PlanPart.TYPE) { p, ctx ->
            val player = ctx.player()
            ctx.server().execute { Actions.guarded(player) { PlanUpload.receive(player, p) } }
        }
        // Someone who walks up to a worker later still sees its current emote.
        EntityTrackingEvents.START_TRACKING.register { entity, player ->
            if (entity !is PokemonEntity) return@register
            val tether = entity.tethering ?: return@register
            val site = SiteStore.of(player.serverLevel()).sites[tether.pasturePos] ?: return@register
            val w = site.workers.firstOrNull { it.entityId == entity.uuid } ?: return@register
            if (w.emote != Emote.NONE) ServerPlayNetworking.send(player, EmoteP(entity.id, w.emote.ordinal))
        }
    }

    private fun canReceive(p: ServerPlayer) = ServerPlayNetworking.canSend(p, Fx.TYPE)

    private val lastMarkers = HashMap<java.util.UUID, Int>()

    fun forgetMarkers(player: java.util.UUID) { lastMarkers.remove(player) }

    /**
     * Every two seconds each player is told which chests near them are job sites or extra chests,
     * but only when that list changed since last time, so in a quiet world this sends nothing.
     */
    fun syncMarkers(level: ServerLevel) {
        if (level.gameTime % 40 != 0L) return
        val players = level.players()
        if (players.isEmpty()) return
        val sites = SiteStore.of(level).sites
        for (p in players) {
            if (!canReceive(p)) continue
            val pos = it.unimi.dsi.fastutil.longs.LongArrayList()
            val kinds = it.unimi.dsi.fastutil.bytes.ByteArrayList()
            val balls = it.unimi.dsi.fastutil.bytes.ByteArrayList()
            if (sites.isNotEmpty()) {
                val at = p.blockPosition()
                for (s in sites.values) {
                    if (s.pos.distSqr(at) > 96.0 * 96.0) continue
                    pos.add(s.pos.asLong()); kinds.add((s.job.ordinal or if (s.active) Markers.ACTIVE else 0).toByte()); balls.add(ballIndex(s.ball).toByte())
                    // A route end can be another job site's chest; that chest keeps its own site marker.
                    for (l in s.links) { if (sites.containsKey(l)) continue; pos.add(l.asLong()); kinds.add(Markers.EXTRA.toByte()); balls.add(ballIndex(s.chest(l).ball).toByte()) }
                }
            }
            val hash = 31 * (31 * (31 * pos.hashCode() + kinds.hashCode()) + balls.hashCode()) + level.dimension().location().hashCode()
            if (lastMarkers.put(p.uuid, hash) != hash) ServerPlayNetworking.send(p, Markers(pos.toLongArray(), kinds.toByteArray(), balls.toByteArray()))
        }
    }

    fun fx(level: ServerLevel, e: PokemonEntity, kind: Int, pos: BlockPos, state: Int) {
        val range = Config.a.effectRange.toDouble().let { it * it }
        var packet: Fx? = null
        for (p in PlayerLookup.tracking(e)) {
            if (p.distanceToSqr(e) > range || !canReceive(p)) continue
            ServerPlayNetworking.send(p, packet ?: Fx(e.id, kind, pos, state).also { packet = it })
        }
    }

    fun emote(level: ServerLevel, e: PokemonEntity, emote: Emote) {
        for (p in PlayerLookup.tracking(e)) if (canReceive(p)) ServerPlayNetworking.send(p, EmoteP(e.id, emote.ordinal))
    }

    fun view(p: ServerPlayer, kind: String, tag: CompoundTag) {
        if (canReceive(p)) ServerPlayNetworking.send(p, View(kind, tag))
    }

    /** A short message for the player about one of their job sites. Shown as a toast by the client. */
    fun notify(p: ServerPlayer, key: String, arg: String, pos: BlockPos) {
        if (canReceive(p)) {
            view(p, "notify", CompoundTag().also { it.putString("key", key); it.putString("arg", arg); it.putLong("pos", pos.asLong()) })
        } else {
            // A player without the mod on their client (possible on some servers) still gets the message in plain text.
            p.displayClientMessage(net.minecraft.network.chat.Component.translatableWithFallback(key, key, *arg.split('|').toTypedArray()), true)
        }
    }
}
