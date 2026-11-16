package dev.foremon.client

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.foremon.net.Net
import dev.foremon.rules.Job
import dev.foremon.world.BALLS
import dev.foremon.world.Emote
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.AABB

/**
 * Everything the player sees and hears when a Pokemon works. It all runs on the client from one
 * small packet per finished action, so a busy base costs the server nothing extra and each player
 * only pays for the workers near them.
 */
object WorkFx {
    /** Each type works in its own way: fire leaves embers, psychic a shimmer, electric sparks. */
    private fun themed(e: Entity?): ParticleOptions {
        val type = (e as? PokemonEntity)?.pokemon?.types?.firstOrNull()?.name?.lowercase()
        return when (type) {
            "fire" -> ParticleTypes.FLAME
            "water" -> ParticleTypes.BUBBLE_POP
            "grass", "bug" -> ParticleTypes.COMPOSTER
            "electric" -> ParticleTypes.ELECTRIC_SPARK
            "psychic", "fairy" -> ParticleTypes.WITCH
            "ghost" -> ParticleTypes.SOUL
            "ice" -> ParticleTypes.SNOWFLAKE
            "dragon" -> ParticleTypes.DRAGON_BREATH
            "dark", "poison" -> ParticleTypes.SMOKE
            "flying" -> ParticleTypes.CLOUD
            else -> ParticleTypes.CRIT
        }
    }

    fun play(fx: Net.Fx) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val e = level.getEntity(fx.entityId)
        val rnd = level.random
        val pos = fx.pos
        val cx = pos.x + 0.5; val cy = pos.y + 0.5; val cz = pos.z + 0.5

        /** A short streak from the Pokemon to the block: this is what makes it read as "the Pokemon did that". */
        fun trail() {
            if (e == null) return
            val p = themed(e)
            val sx = e.x; val sy = e.y + e.bbHeight * 0.6; val sz = e.z
            for (i in 1..7) {
                val t = i / 8.0
                level.addParticle(p, Mth.lerp(t, sx, cx) + (rnd.nextDouble() - 0.5) * 0.15, Mth.lerp(t, sy, cy) + (rnd.nextDouble() - 0.5) * 0.15, Mth.lerp(t, sz, cz) + (rnd.nextDouble() - 0.5) * 0.15, 0.0, 0.01, 0.0)
            }
        }

        when (fx.kind) {
            Net.FX_BREAK -> {
                val state = Block.stateById(fx.state)
                level.addDestroyBlockEffect(pos, state)
                level.playLocalSound(cx, cy, cz, state.soundType.breakSound, SoundSource.BLOCKS, 0.8f, 0.85f + rnd.nextFloat() * 0.3f, false)
                trail()
            }
            Net.FX_PLACE -> {
                val dust = BlockParticleOption(ParticleTypes.BLOCK, Block.stateById(fx.state))
                repeat(8) { level.addParticle(dust, cx + (rnd.nextDouble() - 0.5) * 0.9, cy + (rnd.nextDouble() - 0.5) * 0.9, cz + (rnd.nextDouble() - 0.5) * 0.9, 0.0, 0.05, 0.0) }
                trail()
            }
            Net.FX_LEAVES -> {
                // A whole crown coming down: one burst for all of it.
                val dust = BlockParticleOption(ParticleTypes.BLOCK, Block.stateById(fx.state))
                repeat(70) { level.addParticle(dust, cx + (rnd.nextDouble() - 0.5) * 7, cy + (rnd.nextDouble() - 0.3) * 5, cz + (rnd.nextDouble() - 0.5) * 7, 0.0, -0.04, 0.0) }
                level.playLocalSound(cx, cy, cz, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 1.0f, 0.8f + rnd.nextFloat() * 0.2f, false)
            }
            Net.FX_HARVEST -> {
                repeat(6) { level.addParticle(ParticleTypes.HAPPY_VILLAGER, cx + (rnd.nextDouble() - 0.5) * 0.8, cy + rnd.nextDouble() * 0.5, cz + (rnd.nextDouble() - 0.5) * 0.8, 0.0, 0.0, 0.0) }
                level.playLocalSound(cx, cy, cz, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.7f, 0.9f + rnd.nextFloat() * 0.3f, false)
                trail()
            }
            Net.FX_SMELT -> if (e != null) {
                repeat(8) { level.addParticle(ParticleTypes.FLAME, e.x + (rnd.nextDouble() - 0.5) * e.bbWidth * 1.4, e.y + rnd.nextDouble() * e.bbHeight, e.z + (rnd.nextDouble() - 0.5) * e.bbWidth * 1.4, 0.0, 0.03, 0.0) }
                repeat(4) { level.addParticle(ParticleTypes.SMOKE, e.x, e.y + e.bbHeight, e.z, (rnd.nextDouble() - 0.5) * 0.04, 0.06, (rnd.nextDouble() - 0.5) * 0.04) }
                level.playLocalSound(e.x, e.y, e.z, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.9f, 1f, false)
            }
            Net.FX_DELIVER -> if (e != null) {
                repeat(4) { level.addParticle(ParticleTypes.POOF, e.x, e.y + e.bbHeight * 0.5, e.z, (rnd.nextDouble() - 0.5) * 0.06, 0.03, (rnd.nextDouble() - 0.5) * 0.06) }
                level.playLocalSound(e.x, e.y, e.z, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.4f, 0.8f + rnd.nextFloat() * 0.4f, false)
            }
            Net.FX_LEVEL_UP -> if (e != null) {
                repeat(30) { level.addParticle(ParticleTypes.TOTEM_OF_UNDYING, e.x, e.y + e.bbHeight * 0.5, e.z, (rnd.nextDouble() - 0.5) * 0.5, rnd.nextDouble() * 0.5, (rnd.nextDouble() - 0.5) * 0.5) }
                level.playLocalSound(e.x, e.y, e.z, SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.7f, 1.2f, false)
            }
        }
    }
}

/** The small icon bobbing over a worker that says what is wrong (or that it is asleep). */
object Emotes {
    private val current = Int2IntOpenHashMap()

    private val ICONS = mapOf(
        Emote.TIRED to ItemStack(Items.FEATHER), Emote.HUNGRY to ItemStack(Items.SWEET_BERRIES),
        Emote.NEED to ItemStack(Items.CHEST), Emote.STUCK to ItemStack(Items.BARRIER),
        Emote.DONE to ItemStack(Items.EMERALD), Emote.SLEEP to ItemStack(Items.RED_BED),
    )

    fun set(entityId: Int, emote: Int) {
        if (emote == Emote.NONE.ordinal || emote == Emote.WORKING.ordinal) current.remove(entityId) else current.put(entityId, emote)
    }

    fun clear() = current.clear()

    fun render(pose: PoseStack, buffers: MultiBufferSource, camera: Camera, partial: Float) {
        if (current.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val cam = camera
        val camPos = cam.position
        val time = (level.gameTime % 100000) + partial

        val it = current.int2IntEntrySet().iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val e = level.getEntity(entry.intKey)
            if (e == null) { it.remove(); continue }
            if (e.isInvisible) continue
            val stack = ICONS[Emote.entries.getOrNull(entry.intValue)] ?: continue
            if (e.distanceToSqr(camPos) > 40.0 * 40.0) continue
            val x = Mth.lerp(partial.toDouble(), e.xo, e.x) - camPos.x
            val y = Mth.lerp(partial.toDouble(), e.yo, e.y) - camPos.y + e.bbHeight + 0.55 + Mth.sin(time * 0.12f) * 0.06
            val z = Mth.lerp(partial.toDouble(), e.zo, e.z) - camPos.z
            pose.pushPose()
            pose.translate(x, y, z)
            pose.mulPose(cam.rotation())
            pose.mulPose(Axis.YP.rotationDegrees(180f))
            pose.scale(0.45f, 0.45f, 0.45f)
            mc.itemRenderer.renderStatic(stack, ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, level, 0)
            pose.popPose()
        }
    }
}

/**
 * Makes job site chests look different from ordinary chests: a coloured frame around the chest
 * and the icon of its job turning above it. Extra chests added to a site get a blue frame and a
 * chain icon. A paused site is grey and its icon stands still.
 */
object ChestMarkers {
    private var positions = LongArray(0)
    private var kinds = ByteArray(0)
    private var balls = ByteArray(0)
    private val icons = HashMap<Int, ItemStack>()

    fun set(p: LongArray, k: ByteArray, b: ByteArray) { if (p.size == k.size && p.size == b.size) { positions = p; kinds = k; balls = b } }
    fun clear() { positions = LongArray(0); kinds = ByteArray(0); balls = ByteArray(0) }

    fun render(pose: PoseStack, buffers: MultiBufferSource, camera: Camera, partial: Float) {
        if (positions.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val cam = camera.position
        val time = (level.gameTime % 100000) + partial

        for (i in positions.indices) {
            val pos = BlockPos.of(positions[i])
            if (pos.distToCenterSqr(cam) > 48.0 * 48.0) continue
            val kind = kinds[i].toInt()
            val extra = kind and Net.Markers.EXTRA != 0
            val active = kind and Net.Markers.ACTIVE != 0

            // A double chest is framed as one box, whichever half the site was made on.
            var box = AABB(pos)
            val state = level.getBlockState(pos)
            if (state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
                box = box.minmax(AABB(pos.relative(ChestBlock.getConnectedDirection(state))))

            val r: Float; val g: Float; val b: Float
            when {
                extra -> { r = 0.35f; g = 0.75f; b = 1.0f }
                active -> { r = 1.0f; g = 0.78f; b = 0.2f }
                else -> { r = 0.72f; g = 0.72f; b = 0.72f }
            }
            val shown = box.inflate(0.03).move(-cam.x, -cam.y, -cam.z)
            val lines = buffers.getBuffer(RenderType.lines())
            LevelRenderer.renderLineBox(pose, lines, shown, r, g, b, 1f)
            LevelRenderer.renderLineBox(pose, lines, shown.inflate(0.035), r, g, b, 0.55f)

            // Every chest shows the Poke Ball it was marked with; a job site chest also shows its job underneath.
            val c = box.center
            val bob = if (active) Mth.sin(time * 0.1f) * 0.06 else 0.0
            pose.pushPose()
            pose.translate(c.x - cam.x, box.maxY - cam.y + 0.75 + bob, c.z - cam.z)
            pose.mulPose(Axis.YP.rotationDegrees(if (active || extra) time * 3f % 360f else 45f))
            pose.scale(0.8f, 0.8f, 0.8f)
            mc.itemRenderer.renderStatic(MonIcons.ball(BALLS.getOrElse(balls[i].toInt()) { BALLS[0] }), ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, level, 0)
            pose.popPose()
            if (!extra) {
                val job = icons.getOrPut(kind and 0x0F) { ItemStack(JOB_ICONS[Job.entries.getOrNull(kind and 0x0F)] ?: Items.CHEST) }
                pose.pushPose()
                pose.translate(c.x - cam.x, box.maxY - cam.y + 0.3 + bob, c.z - cam.z)
                pose.mulPose(Axis.YP.rotationDegrees(if (active) time * 3f % 360f else 45f))
                pose.scale(0.45f, 0.45f, 0.45f)
                mc.itemRenderer.renderStatic(job, ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, level, 0)
                pose.popPose()
            }
        }
    }
}

/** Hauling depots: one line above the chest, how far along the next load is and how much has been hauled in all. */
object HaulBoards {
    private val boards = HashMap<Long, CompoundTag>()
    private var seenLevel: Any? = null
    private const val S = 0.025f

    fun set(t: CompoundTag) { val k = t.getLong("pos"); if (t.getBoolean("gone")) boards.remove(k) else boards[k] = t }
    fun clear() = boards.clear()

    fun render(pose: PoseStack, buffers: MultiBufferSource, camera: Camera, partial: Float) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        // A new world or dimension: lines from the old one do not belong here.
        if (level !== seenLevel) { seenLevel = level; clear(); return }
        if (boards.isEmpty()) return
        val cam = camera; val cp = cam.position
        val now = level.gameTime + partial
        val font = mc.font
        for ((key, t) in boards) {
            val pos = BlockPos.of(key)
            if (pos.distToCenterSqr(cp) > 32.0 * 32.0) continue
            // The load that arrives next.
            var next = -1f
            val loads = t.getList("loads", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until loads.size) {
                val r = loads.getCompound(i)
                next = maxOf(next, ((now - r.getLong("start")) / r.getInt("need").coerceAtLeast(1)).coerceIn(0f, 1f))
            }
            val total = tr("foremon.board.total", "%,d".format(t.getLong("total")))
            val line: Component = if (next < 0) total else Component.literal("${(next * 100).toInt()}%  ·  ").withColor(0xFFC94A).append(total)
            pose.pushPose()
            pose.translate(pos.x + 0.5 - cp.x, pos.y + 2.6 - cp.y, pos.z + 0.5 - cp.z)
            pose.mulPose(cam.rotation())
            pose.scale(S, -S, S)
            font.drawInBatch(line, -font.width(line) / 2f, 0f, -1, true, pose.last().pose(), buffers, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT)
            pose.popPose()
        }
    }
}
