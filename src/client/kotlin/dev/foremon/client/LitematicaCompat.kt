package dev.foremon.client

import dev.foremon.Foremon
import dev.foremon.net.Net
import io.netty.buffer.Unpooled
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the schematic the player has placed with Litematica and sends it to the server as a build plan.
 *
 * Litematica is optional and only exists on the client, so it is reached by reflection: the mod
 * compiles and runs without it, and nothing here loads unless the player presses the button.
 * We read Litematica's own "schematic world", which already has the placement's position, rotation
 * and mirroring applied, so what the Pokemon build is exactly the ghost the player sees.
 */
object LitematicaCompat {
    val available: Boolean by lazy { FabricLoader.getInstance().isModLoaded("litematica") }

    private val outbox = ArrayDeque<Net.PlanPart>()

    init {
        // A big schematic is many packets; send a few per tick so the connection never stalls.
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (mc.player == null) { outbox.clear(); return@register }
            repeat(4) { outbox.removeFirstOrNull()?.let { ClientPlayNetworking.send(it) } }
        }
    }

    /**
     * The boxes of a placement's sub-regions. Litematica renamed this over time, so each known way is tried:
     * newer builds have getAllSubRegionBoxes(), older ones getSubRegionBoxes(RequiredEnabled.ANY), and as a last
     * resort the enclosing box (only filled in while Litematica renders it).
     */
    private fun regionBoxes(placement: Any): List<Any>? {
        val cls = placement.javaClass
        runCatching { return (cls.getMethod("getAllSubRegionBoxes").invoke(placement) as Map<*, *>).values.filterNotNull() }
        runCatching {
            val req = Class.forName("fi.dy.masa.litematica.schematic.placement.SubRegionPlacement\$RequiredEnabled")
            val any = req.enumConstants.first { (it as Enum<*>).name == "ANY" }
            return (cls.getMethod("getSubRegionBoxes", req).invoke(placement, any) as Map<*, *>).values.filterNotNull()
        }
        return runCatching { listOfNotNull(cls.getMethod("getEclosingBox").invoke(placement)) }.getOrNull()
    }

    /** Starts the upload. Returns a translation key describing the problem, or null if it started. */
    fun upload(site: BlockPos): String? {
        if (!available) return "foremon.gui.load_litematica.missing"
        if (outbox.isNotEmpty()) return "foremon.fail.upload_busy"
        try {
            val manager = Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getSchematicPlacementManager").invoke(null)
            fun missing(what: String): String { if (Foremon.DEBUG) Foremon.LOG.info("Litematica: {} is missing", what); return "foremon.fail.no_placement" }
            val placement = manager.javaClass.getMethod("getSelectedSchematicPlacement").invoke(manager) ?: return missing("the selected placement")
            val name = placement.javaClass.getMethod("getName").invoke(placement) as? String ?: "schematic"
            // Not getEclosingBox(): Litematica only fills that in while "render enclosing box" is switched on.
            // The boxes of the placement's sub-regions are always there, so the area is built from those.
            val boxes = regionBoxes(placement)
            if (boxes.isNullOrEmpty()) return missing("the placement's regions")
            var p1: BlockPos? = null; var p2: BlockPos? = null
            for (b in boxes) {
                val a = b.javaClass.getMethod("getPos1").invoke(b) as? BlockPos ?: continue
                val c = b.javaClass.getMethod("getPos2").invoke(b) as? BlockPos ?: continue
                val lo0 = BlockPos(min(a.x, c.x), min(a.y, c.y), min(a.z, c.z)); val hi0 = BlockPos(max(a.x, c.x), max(a.y, c.y), max(a.z, c.z))
                p1 = p1?.let { BlockPos(min(it.x, lo0.x), min(it.y, lo0.y), min(it.z, lo0.z)) } ?: lo0
                p2 = p2?.let { BlockPos(max(it.x, hi0.x), max(it.y, hi0.y), max(it.z, hi0.z)) } ?: hi0
            }
            if (p1 == null || p2 == null) return missing("the placement's corners")
            val rawWorld = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler").getMethod("getSchematicWorld").invoke(null)
            val world = rawWorld as? Level ?: return missing("the schematic world (got ${rawWorld?.javaClass?.name})")

            val lo = BlockPos(min(p1.x, p2.x), min(p1.y, p2.y), min(p1.z, p2.z))
            val hi = BlockPos(max(p1.x, p2.x), max(p1.y, p2.y), max(p1.z, p2.z))
            val volume = (hi.x - lo.x + 1L) * (hi.y - lo.y + 1L) * (hi.z - lo.z + 1L)
            if (volume > 16_000_000L) return "foremon.fail.plan_huge"

            val body = FriendlyByteBuf(Unpooled.buffer())
            var count = 0
            for (pos in BlockPos.betweenClosed(lo, hi)) {
                val state = world.getBlockState(pos)
                if (state.isAir) continue
                body.writeLong(pos.asLong()); body.writeVarInt(Block.getId(state))
                count++
            }
            if (Foremon.DEBUG) Foremon.LOG.info("Litematica placement '{}' read: {} blocks between {} and {}", name, count, lo.toShortString(), hi.toShortString())
            if (count == 0) return "foremon.fail.placement_empty"
            val all = FriendlyByteBuf(Unpooled.buffer())
            all.writeVarInt(count); all.writeBytes(body)
            val bytes = ByteArray(all.readableBytes()).also { all.readBytes(it) }
            val total = (bytes.size + Net.PlanPart.MAX_BYTES - 1) / Net.PlanPart.MAX_BYTES
            for (i in 0 until total) {
                val from = i * Net.PlanPart.MAX_BYTES
                outbox.addLast(Net.PlanPart(site, name.take(60), i, total, bytes.copyOfRange(from, min(bytes.size, from + Net.PlanPart.MAX_BYTES))))
            }
            return null
        } catch (e: ReflectiveOperationException) {
            Foremon.LOG.warn("This Litematica version does not look the way Foremon expects", e)
            return "foremon.fail.litematica_api"
        } catch (e: ClassCastException) {
            Foremon.LOG.warn("This Litematica version does not look the way Foremon expects", e)
            return "foremon.fail.litematica_api"
        }
    }
}
