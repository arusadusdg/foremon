package dev.foremon.probe

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import dev.foremon.Foremon
import dev.foremon.client.HubScreen
import dev.foremon.client.SiteScreen
import dev.foremon.client.send
import dev.foremon.work.Inv
import dev.foremon.world.SiteStore
import dev.foremon.world.Workers
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.Difficulty
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import kotlin.math.min

/**
 * A self-driving client for development. Started with `gradlew runProbe`, it creates a flat test
 * world, uses the whistle like a player would, clicks through the menus with real mouse-click
 * events, and saves a screenshot at every stage to run/screenshots/probe_*.png. Then it quits.
 * It exists because nothing else can show what the screens and the workers actually look like.
 */
object ForemonProbe : ClientModInitializer {
    private val log = LoggerFactory.getLogger("foremon-probe")

    private class Step(val name: String, val delay: Int, val until: ((Minecraft) -> Boolean)?, val run: (Minecraft) -> Unit)

    private val steps = ArrayList<Step>()
    private var index = 0
    private var ticks = 0
    private lateinit var origin: BlockPos
    private lateinit var chest: BlockPos

    private fun step(name: String, delay: Int, until: ((Minecraft) -> Boolean)? = null, run: (Minecraft) -> Unit) { steps.add(Step(name, delay, until, run)) }
    private fun cmd(mc: Minecraft, c: String) = mc.player!!.connection.sendCommand(c)
    private fun shot(mc: Minecraft, name: String) = Screenshot.grab(mc.gameDirectory, "probe_$name.png", mc.mainRenderTarget) { }

    override fun onInitializeClient() {
        if (System.getProperty("foremon.probe") == null) return
        // FOREMON_PROBE=markers runs the short script that only checks the chest markers and the icons above workers.
        when (System.getenv("FOREMON_PROBE")) {
            "markers" -> markersScript()
            "litematica" -> litematicaScript()
            "restart" -> restartScript()
            "ui" -> uiScript()
            "gif" -> gifScript()
            "house" -> houseScript()
            else -> script()
        }
        // The test must keep running when its window is not in front; a paused game would look like a stuck worker.
        ClientTickEvents.START_CLIENT_TICK.register { mc -> if (mc.options.pauseOnLostFocus) mc.options.pauseOnLostFocus = false }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            try { tick(mc) } catch (e: Throwable) { log.error("PROBE FAIL in step ${steps.getOrNull(index)?.name}", e); mc.stop() }
        }
    }

    private fun tick(mc: Minecraft) {
        val s = steps.getOrNull(index) ?: return
        ticks++
        if (ticks < s.delay) return
        if (s.until != null && !s.until.invoke(mc)) {
            if (ticks > s.delay + 1200) { log.error("PROBE FAIL: timed out waiting before step '${s.name}' (screen=${mc.screen?.javaClass?.simpleName})"); index = steps.size; mc.stop() }
            return
        }
        log.info("PROBE step: {}", s.name)
        s.run(mc)
        index++; ticks = 0
    }

    // Same layout maths as ForemonScreen.layout() and the pages, so the clicks land where a player would click.
    private class Layout(sw: Int, sh: Int) {
        val w = min(420, sw - 60); val h = min(248, sh - 16)
        val x0 = (sw - w) / 2; val y0 = (sh - h) / 2
        val mid = x0 + w / 2
        val lx = x0 + 16; val rx = mid + 10; val pw = w / 2 - 26
        val top = y0 + 30; val rtop = y0 + 14; val bottom = y0 + h - 13
        /** Bookmark [i] down the left edge of the book. */
        fun mark(i: Int) = (x0 - 10) to (y0 + 34 + i * 23 + 10)
        val crewTab get() = mark(0)
        val jobsTab get() = mark(1)
        val pcTab get() = mark(2)
        val chestsTab get() = mark(3)
        val filterTab get() = mark(4)
        /** Party slot [i] on the crew page. */
        fun party(i: Int) = (rx + i * 30 + 14) to (bottom - 32 + 16)
        /** Start / Pause on the crew page. A two-line status pushes it down a line, so aim at the lower half. */
        val start get() = (lx + pw / 2) to (top + 28)
        /** The first worker on the crew page. */
        val worker get() = (rx + pw / 2) to (rtop + 14 + 11)
        /** Job [i] on the jobs page. */
        fun job(i: Int) = (lx + pw / 2) to (top + i * 20 + 9)
        /** Slot [i] of the PC box grid (6 columns, 5 rows). */
        fun pcSlot(i: Int): Pair<Int, Int> {
            val cell = minOf(26, pw / 6)
            val gx = lx + (pw - cell * 6) / 2; val gy = top + 22
            return (gx + (i % 6) * cell + cell / 2) to (gy + (i / 6) * cell + cell / 2)
        }
        /** Skill tree branch button [i]. */
        fun branch(i: Int): Pair<Int, Int> { val bw = (pw - 3) / 2; return ((if (i < 2) lx else rx) + (i % 2) * (bw + 3) + bw / 2) to (top + 7) }
        /** The centre of skill node ([nx], [ny]) in a branch [cols] wide and [rows] high. */
        fun skillNode(cols: Int, rows: Int, nx: Int, ny: Int): Pair<Int, Int> {
            val aw = rx + pw - lx; val ay = top + 20; val ah = bottom - ay
            val cellW = minOf(56, aw / cols); val cellH = minOf(46, ah / rows)
            val gx = lx + (aw - cols * cellW) / 2; val gy = ay + (ah - rows * cellH) / 2
            return (gx + nx * cellW + (cellW - 26) / 2 + 13) to (gy + ny * cellH + 14)
        }
        /** Cell ([i], [j]) of the Guard map on the left page, for a map [n] cells across. */
        fun mapCell(n: Int, i: Int, j: Int): Pair<Int, Int> { val cell = maxOf(1, minOf(pw, bottom - top - 2) / n); return (lx + i * cell + cell / 2) to (top + j * cell + cell / 2) }
        /** Map tool [i] on the right page. */
        fun mapTool(i: Int) = ((pw - 3) / 2).let { cw -> (rx + (i % 2) * (cw + 3) + cw / 2) to (rtop + 14 + (i / 2) * 16 + 7) }
        val bigMapButton get() = ((pw - 3) / 2).let { half -> (rx + half + 3 + (pw - half - 3) / 2) to (bottom - 8) }
    }

    /**
     * Steps that pick job [i] on the jobs page, then go back to the crew page. One click per step: a screen only knows
     * where its buttons are once it has drawn the page they are on.
     */
    private fun pickJob(i: Int, delay: Int = 5, until: ((Minecraft) -> Boolean)? = null) {
        step("open the jobs page", delay, until) { mc -> val s = mc.screen!!; click(mc, Layout(s.width, s.height).jobsTab) }
        step("pick job $i", 3) { mc -> val s = mc.screen!!; click(mc, Layout(s.width, s.height).job(i)) }
        step("back to the crew page", 3) { mc -> val s = mc.screen!!; click(mc, Layout(s.width, s.height).crewTab) }
    }

    private fun click(mc: Minecraft, p: Pair<Int, Int>) { mc.screen!!.mouseClicked(p.first.toDouble(), p.second.toDouble(), 0) }

    private fun useWhistleOnChest(mc: Minecraft) {
        val player = mc.player!!
        val slot = (0..8).firstOrNull { player.inventory.getItem(it).`is`(Foremon.WHISTLE) } ?: error("no whistle in the hotbar")
        player.inventory.selected = slot
        mc.gameMode!!.useItemOn(player, InteractionHand.MAIN_HAND, BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false))
    }

    private fun report(mc: Minecraft, label: String) {
        val server = mc.singleplayerServer ?: return
        server.execute {
            val level = server.overworld()
            val s = SiteStore.of(level).sites[chest]
            if (s == null) { log.info("PROBE {}: NO SITE at chest", label); return@execute }
            val storage = Inv.storage(level, chest)
            var logs = 0; var items = 0
            if (storage != null) for (v in storage.nonEmptyViews()) { items += v.amount.toInt(); if (v.resource.toStack().`is`(net.minecraft.tags.ItemTags.LOGS)) logs += v.amount.toInt() }
            log.info("PROBE {}: job={} active={} status='{}' arg='{}' done={} queue={} chestItems={} logsInChest={}", label, s.job.key, s.active, s.status, s.statusArg, s.done, s.queue.size, items, logs)
            for (w in s.workers) {
                val e = Workers.entity(level, w)
                log.info("PROBE {}:   worker {} entity={} pos={} step={} emote={} carried={} stamina={} hand={}", label, w.pokemon?.species?.name, e != null,
                    e?.blockPosition()?.toShortString(), w.step?.let { "${it.kind}@${it.pos.toShortString()}" }, w.emote, w.count(w.carried), w.stamina.toInt(), e?.mainHandItem)
            }
        }
    }

    private fun sneak(mc: Minecraft, on: Boolean) {
        val action = if (on) ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY else ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY
        mc.player!!.connection.send(ServerboundPlayerCommandPacket(mc.player!!, action))
    }

    private fun useWhistleOn(mc: Minecraft, pos: BlockPos) {
        mc.gameMode!!.useItemOn(mc.player!!, InteractionHand.MAIN_HAND, BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false))
    }

    /** Loads a schematic file in Litematica and places it [where], exactly as a player would through Litematica's menus. */
    private fun placeSchematic(mc: Minecraft, file: String, where: BlockPos) {
        val schemCls = Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic")
        val schematic = schemCls.getMethod("createFromFile", java.io.File::class.java, String::class.java)
            .invoke(null, java.io.File(mc.gameDirectory, "schematics"), file) ?: error("Litematica could not read $file")
        val placementCls = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement")
        val placement = placementCls.getMethod("createFor", schemCls, BlockPos::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(null, schematic, where, "foremon test house", true, true)
        val manager = Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getSchematicPlacementManager").invoke(null)
        manager.javaClass.getMethod("addSchematicPlacement", placementCls, Boolean::class.javaPrimitiveType).invoke(manager, placement, false)
        manager.javaClass.getMethod("setSelectedSchematicPlacement", placementCls).invoke(manager, placement)
        log.info("PROBE placed the schematic at {}", where.toShortString())
    }

    private fun builtCount(mc: Minecraft, where: BlockPos): Pair<Int, Int> {
        val level = mc.singleplayerServer!!.overworld()
        var good = 0; var torch = false
        for (x in 0 until 5) for (z in 0 until 5) {
            if (level.getBlockState(where.offset(x, 0, z)).`is`(net.minecraft.world.level.block.Blocks.OAK_PLANKS)) good++
            if ((x == 0 || x == 4 || z == 0 || z == 4) && level.getBlockState(where.offset(x, 1, z)).`is`(net.minecraft.world.level.block.Blocks.COBBLESTONE)) good++
        }
        if (level.getBlockState(where.offset(2, 1, 2)).`is`(net.minecraft.world.level.block.Blocks.TORCH)) { good++; torch = true }
        return good to (if (torch) 1 else 0)
    }

    private fun litematicaScript() {
        lateinit var where: BlockPos
        step("create world", 60, { it.screen is TitleScreen }) { mc ->
            mc.createWorldOpenFlows().createFreshLevel(
                "Foremon Test",
                LevelSettings("Foremon Test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT),
                WorldOptions(1L, false, false),
                { ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },
                mc.screen
            )
        }
        step("commands", 100, { it.player != null && it.level != null }) { mc ->
            origin = mc.player!!.blockPosition()
            chest = origin.offset(3, 0, 0)
            where = origin.offset(-3, 0, 3)
            for (c in listOf(
                "gamerule doDaylightCycle false", "time set noon", "weather clear", "gamerule doMobSpawning false",
                "give @s foremon:whistle", "pokegive machop level=30", "pokegive bulbasaur level=10",
                "setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest",
                "item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:oak_planks 30",
                "item replace block ${chest.x} ${chest.y} ${chest.z} container.1 with minecraft:cobblestone 20",
                "item replace block ${chest.x} ${chest.y} ${chest.z} container.2 with minecraft:torch 2",
            )) cmd(mc, c)
        }
        step("close any screen", 60) { mc -> mc.setScreen(null) }
        step("place the schematic in Litematica", 10) { mc -> placeSchematic(mc, "foremon_test_house.litematic", where) }
        step("shot: the Litematica ghost", 60) { mc ->
            cmd(mc, "tp @s ${origin.x + 4.5} ${origin.y + 2} ${origin.z - 4.5} facing ${where.x + 2.5} ${where.y} ${where.z + 2.5}")
        }
        step("shot: ghost", 40) { mc -> shot(mc, "40_litematica_ghost") }
        step("back to the chest", 5) { mc -> cmd(mc, "tp @s ${origin.x + 1} ${origin.y} ${origin.z} facing ${chest.x} ${chest.y} ${chest.z}") }
        step("use whistle on chest", 20) { mc -> useWhistleOnChest(mc) }
        step("assign machop", 30, { it.screen is SiteScreen }) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).party(0)) }
        pickJob(6, 20)
        step("shot: building menu", 20) { mc -> shot(mc, "41_building_menu") }
        step("press Load plan", 5) { mc ->
            dev.foremon.client.LitematicaCompat.upload(chest)
            log.info("PROBE action bar after Load plan: {}", mc.gui.javaClass.getDeclaredField("overlayMessageString").let { f -> f.isAccessible = true; (f.get(mc.gui) as? net.minecraft.network.chat.Component)?.string })
        }
        step("check the plan arrived", 60) { mc ->
            val server = mc.singleplayerServer!!
            server.execute {
                val site = SiteStore.of(server.overworld()).sites[chest]
                log.info("PROBE plan after Load plan: {} blocks, job={}", site?.plan?.size ?: -1, site?.job?.key)
            }
        }
        step("shot: plan loaded", 10) { mc -> shot(mc, "42_plan_loaded") }
        step("press Start", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("watch", 10) { mc -> mc.setScreen(null); cmd(mc, "tp @s ${origin.x + 4.5} ${origin.y + 2} ${origin.z - 4.5} facing ${where.x + 2.5} ${where.y} ${where.z + 2.5}") }
        step("shot: building", 200) { mc -> shot(mc, "43_building"); log.info("PROBE built so far: {} of 42", builtCount(mc, where).first) }
        step("shot: built", 600) { mc ->
            shot(mc, "44_built")
            val (n, torch) = builtCount(mc, where)
            log.info("PROBE BUILD RESULT: {} of 42 blocks match the schematic, torch placed: {}", n, torch == 1)
            report(mc, "after building")
        }
        step("done", 10) { mc -> log.info("PROBE DONE") }
        step("quit", 10) { mc -> mc.stop() }
    }

    /** What the probe counted on the server: materials everywhere (chest + worker + placed) and the site's state. */
    private class Count(val total: Int, val placed: Int, val workers: Int, val entities: Int, val planIndex: Int, val active: Boolean, val chestPlanks: Int)

    @Volatile private var counted: Count? = null

    private fun countMaterials(mc: Minecraft, where: BlockPos, label: String) {
        val server = mc.singleplayerServer ?: run { log.info("PROBE {}: no server", label); return }
        server.execute {
            val level = server.overworld()
            val site = SiteStore.of(level).sites[chest]
            if (site == null) { log.info("PROBE {}: NO SITE", label); counted = Count(-1, -1, 0, 0, -1, false, -1); return@execute }
            val wanted = setOf(net.minecraft.world.item.Items.OAK_PLANKS, net.minecraft.world.item.Items.COBBLESTONE, net.minecraft.world.item.Items.TORCH)
            var inChest = 0; var planks = 0
            Inv.storage(level, chest)?.let { st -> for (v in st.nonEmptyViews()) if (v.resource.item in wanted) { inChest += v.amount.toInt(); if (v.resource.item == net.minecraft.world.item.Items.OAK_PLANKS) planks += v.amount.toInt() } }
            var onWorkers = 0
            for (w in site.workers) for (st in w.carried + w.supplies) if (st.item in wanted) onWorkers += st.count
            var placed = 0
            for (x in 0 until 5) for (z in 0 until 5) {
                if (level.getBlockState(where.offset(x, 0, z)).`is`(net.minecraft.world.level.block.Blocks.OAK_PLANKS)) placed++
                if (level.getBlockState(where.offset(x, 1, z)).`is`(net.minecraft.world.level.block.Blocks.COBBLESTONE)) placed++
            }
            if (level.getBlockState(where.offset(2, 1, 2)).`is`(net.minecraft.world.level.block.Blocks.TORCH)) placed++
            val entities = site.workers.count { Workers.entity(level, it) != null }
            val c = Count(inChest + onWorkers + placed, placed, site.workers.size, entities, site.plan?.index ?: -1, site.active, planks)
            counted = c
            log.info("PROBE {}: materials total={} (chest={} on workers={} placed={}), workers={} with entity={}, plan index={}, active={}",
                label, c.total, inChest, onWorkers, placed, c.workers, c.entities, c.planIndex, c.active)
        }
    }

    /**
     * Save and reload: a build half done, a worker carrying materials, then Save and Quit and open the world again.
     * Checks that nothing is lost or doubled, the worker comes back, and the build carries on and finishes.
     */
    private fun restartScript() {
        lateinit var where: BlockPos
        var before: Count? = null
        step("create world", 60, { it.screen is TitleScreen }) { mc ->
            mc.createWorldOpenFlows().createFreshLevel(
                "Foremon Test",
                LevelSettings("Foremon Test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT),
                WorldOptions(1L, false, false),
                { ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },
                mc.screen
            )
        }
        step("commands", 100, { it.player != null && it.level != null }) { mc ->
            origin = mc.player!!.blockPosition()
            chest = origin.offset(3, 0, 0)
            where = origin.offset(-3, 0, 3)
            for (c in listOf(
                "gamerule doDaylightCycle false", "time set noon", "weather clear", "gamerule doMobSpawning false",
                "give @s foremon:whistle", "pokegive machop level=30", "pokegive bulbasaur level=10",
                "setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest",
                "item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:oak_planks 30",
                "item replace block ${chest.x} ${chest.y} ${chest.z} container.1 with minecraft:cobblestone 20",
                "item replace block ${chest.x} ${chest.y} ${chest.z} container.2 with minecraft:torch 2",
            )) cmd(mc, c)
        }
        step("close any screen", 60) { mc -> mc.setScreen(null) }
        step("use whistle on chest", 20) { mc -> useWhistleOnChest(mc) }
        step("assign machop", 30, { it.screen is SiteScreen }) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).party(0)) }
        pickJob(6, 20)
        step("give the site a plan", 10) { mc ->
            val server = mc.singleplayerServer!!
            server.execute {
                val site = SiteStore.of(server.overworld()).sites[chest] ?: return@execute
                val blocks = ArrayList<Pair<BlockPos, net.minecraft.world.level.block.state.BlockState>>()
                for (x in 0 until 5) for (z in 0 until 5) {
                    blocks.add(where.offset(x, 0, z) to net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState())
                    if (x == 0 || x == 4 || z == 0 || z == 4) blocks.add(where.offset(x, 1, z) to net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState())
                }
                blocks.add(where.offset(2, 1, 2) to net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState())
                site.plan = dev.foremon.world.Plan.ordered("restart test", blocks)
            }
        }
        step("press Start", 10) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("close", 5) { mc -> mc.setScreen(null) }
        step("count before saving", 120) { mc -> countMaterials(mc, where, "BEFORE SAVE") }
        step("save and quit to the title screen", 10) { mc ->
            before = counted
            mc.level?.disconnect()
            mc.disconnect(net.minecraft.client.gui.screens.GenericMessageScreen(net.minecraft.network.chat.Component.literal("Saving")))
            mc.setScreen(TitleScreen())
        }
        step("open the world again", 60, { it.screen is TitleScreen && it.level == null && it.singleplayerServer == null }) { mc ->
            mc.createWorldOpenFlows().openWorld("Foremon Test") { log.error("PROBE FAIL: opening the world was cancelled") }
        }
        step("count right after loading", 100, { it.player != null && it.level != null }) { mc ->
            mc.setScreen(null)
            countMaterials(mc, where, "AFTER LOAD")
        }
        step("compare", 20) { _ ->
            val a = before; val b = counted
            if (a == null || b == null) log.error("PROBE FAIL: missing counts")
            else {
                log.info("PROBE RESTART CHECK: materials {} -> {} ({}), workers {} -> {}, worker entities after load {}, plan index {} -> {}, active {} -> {}",
                    a.total, b.total, if (a.total == b.total) "nothing lost or doubled" else "MISMATCH",
                    a.workers, b.workers, b.entities, a.planIndex, b.planIndex, a.active, b.active)
            }
        }
        step("let it finish", 600) { mc -> countMaterials(mc, where, "FINISHED") }
        step("final check", 20) { _ ->
            val c = counted
            log.info("PROBE RESTART FINAL: placed {} of 42, materials total {} (started with 52), active {}", c?.placed, c?.total, c?.active)
            log.info("PROBE DONE")
        }
        step("quit", 10) { mc -> mc.stop() }
    }

    /** Moves the real mouse pointer to a GUI position, so hover tooltips show up as they would for a player. */
    private fun pointAt(mc: Minecraft, p: Pair<Int, Int>) {
        // The game only reads the pointer when the OS reports a move, so the position is set where it keeps it.
        val scale = mc.window.guiScale
        for ((name, v) in listOf("xpos" to p.first * scale, "ypos" to p.second * scale)) {
            val f = mc.mouseHandler.javaClass.getDeclaredField(name); f.isAccessible = true; f.setDouble(mc.mouseHandler, v)
        }
    }

    private fun siteState(mc: Minecraft, label: String) {
        val server = mc.singleplayerServer ?: return
        server.execute {
            val site = SiteStore.of(server.overworld()).sites[chest]
            val party = com.cobblemon.mod.common.Cobblemon.storage.getParty(mc.player!!.uuid, server.registryAccess()).count()
            log.info("PROBE {}: active={} workers={} party={} upkeep setting={}", label, site?.active, site?.workers?.size, party, dev.foremon.Config.d.upkeep)
        }
    }

    /** The menu controls nobody had seen working: Start and Pause, hover tooltips, sending a worker home, and a server setting. */
    private fun uiScript() {
        step("create world", 60, { it.screen is TitleScreen }) { mc ->
            mc.createWorldOpenFlows().createFreshLevel(
                "Foremon Test",
                LevelSettings("Foremon Test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT),
                WorldOptions(1L, false, false),
                { ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },
                mc.screen
            )
        }
        step("commands", 100, { it.player != null && it.level != null }) { mc ->
            origin = mc.player!!.blockPosition()
            chest = origin.offset(3, 0, 0)
            for (c in listOf(
                "gamerule doDaylightCycle false", "time set noon", "weather clear", "gamerule doMobSpawning false",
                "give @s foremon:whistle", "pokegive machop level=30", "pokegive bulbasaur level=10",
                "setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest",
                "place feature minecraft:oak ${origin.x + 7} ${origin.y} ${origin.z - 4}",
            )) cmd(mc, c)
        }
        step("close any screen", 60) { mc -> mc.setScreen(null) }
        step("use whistle on chest", 20) { mc -> useWhistleOnChest(mc) }
        step("assign machop", 30, { it.screen is SiteScreen }) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).party(0)) }
        step("press Start", 20) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("check started", 20) { mc -> siteState(mc, "after Start") }
        step("press Pause", 10) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("check paused", 20) { mc -> siteState(mc, "after Pause") }
        step("hover the worker card", 5) { mc ->
            val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height)
            pointAt(mc, l.worker)
        }
        step("shot: worker tooltip", 20) { mc -> shot(mc, "50_worker_tooltip") }
        step("hover a party Pokemon", 5) { mc -> val s = mc.screen as SiteScreen; pointAt(mc, Layout(s.width, s.height).party(0)) }
        step("shot: party tooltip", 20) { mc -> shot(mc, "51_party_tooltip") }
        step("click the worker card to send it home", 5) { mc ->
            val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height)
            click(mc, l.worker)
        }
        step("check sent home", 30) { mc -> siteState(mc, "after sending home"); shot(mc, "52_after_send_home") }
        // ---- every page of the new book, for a look
        step("assign machop again", 10) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).party(0)) }
        step("shot: crew page", 30) { mc -> shot(mc, "60_crew") }
        step("jobs page", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).jobsTab) }
        step("hover guard", 5) { mc -> val s = mc.screen as SiteScreen; pointAt(mc, Layout(s.width, s.height).job(8)) }
        step("shot: jobs page", 15) { mc -> shot(mc, "61_jobs") }
        step("PC page", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).pcTab) }
        step("hover the first PC slot", 30) { mc -> val s = mc.screen as SiteScreen; pointAt(mc, Layout(s.width, s.height).pcSlot(0)) }
        step("shot: PC page", 15) { mc -> shot(mc, "62_pc") }
        step("filter page", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).filterTab) }
        step("shot: filter page", 15) { mc -> shot(mc, "63_filter") }
        step("chests page", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).chestsTab) }
        step("shot: chests page", 15) { mc -> shot(mc, "64_chests") }
        pickJob(8)
        step("map page", 30) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).chestsTab) }
        step("draw a guard area", 10) { mc ->
            val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height); val n = 2 * s.data.getInt("mapRadius") + 1
            val a = l.mapCell(n, 2, 2); val b = l.mapCell(n, 8, 9)
            s.mouseClicked(a.first.toDouble(), a.second.toDouble(), 0); s.mouseReleased(b.first.toDouble(), b.second.toDouble(), 0)
        }
        step("route tool", 10) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).mapTool(3)) }
        step("add stops", 5) { mc ->
            val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height); val n = 2 * s.data.getInt("mapRadius") + 1
            click(mc, l.mapCell(n, 12, 12))
        }
        step("add a second stop", 3) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).mapCell(2 * s.data.getInt("mapRadius") + 1, 14, 4)) }
        step("add a third stop", 3) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).mapCell(2 * s.data.getInt("mapRadius") + 1, 4, 14)) }
        step("select tool", 10) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).mapTool(4)) }
        step("select the area", 5) { mc -> val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height); click(mc, l.mapCell(2 * s.data.getInt("mapRadius") + 1, 5, 5)) }
        step("shot: map page", 20) { mc -> shot(mc, "65_map"); siteState(mc, "after drawing on the map") }
        step("big map", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).bigMapButton) }
        step("shot: big map", 15) { mc -> shot(mc, "66_big_map") }
        step("back to the book", 5) { mc -> mc.screen!!.keyPressed(256, 0, 0) }
        step("shot: back from the big map", 10) { mc -> shot(mc, "67_back_from_big_map") }
        step("open skills", 5) { _ -> send("skills") }
        step("shot: skills", 30, { it.screen is HubScreen }) { mc -> shot(mc, "68_skills") }
        step("labour", 5) { mc -> val s = mc.screen as HubScreen; click(mc, Layout(s.width, s.height).branch(1)) }
        step("shot: labour", 10) { mc -> shot(mc, "69_skills_labour") }
        step("open perks", 5) { _ -> send("perks") }
        step("shot: perks", 30) { mc -> shot(mc, "70_perks") }
        step("open dex", 5) { _ -> send("dex") { it.putString("query", "machop") } }
        step("shot: dex", 30) { mc -> shot(mc, "71_dex") }
        step("open guide", 5) { mc -> (mc.screen as HubScreen).accept("guide", CompoundTag()) }
        step("shot: guide", 10) { mc -> shot(mc, "72_guide") }
        step("open board", 5) { _ -> send("board") }
        step("shot: board", 30) { mc -> shot(mc, "73_board") }
        step("open the server tab", 5) { _ -> send("admin") }
        step("click Stamina and food", 30, { it.screen is HubScreen }) { mc ->
            // Rows: mode (34 high), then the others alphabetically; upkeep is the fourth after mode.
            val s = mc.screen as HubScreen; val l = Layout(s.width, s.height)
            click(mc, (l.lx + l.pw - 22) to (l.top + 14 + 34 + 3 * 22 + 10))
        }
        step("check the setting changed", 30) { mc -> siteState(mc, "after clicking Stamina and food"); shot(mc, "53_server_setting") }
        step("click it again to put it back", 5) { mc ->
            val s = mc.screen as HubScreen; val l = Layout(s.width, s.height)
            click(mc, (l.lx + l.pw - 22) to (l.top + 14 + 34 + 3 * 22 + 10))
        }
        step("check it is back", 30) { mc -> siteState(mc, "after clicking again") }
        step("done", 10) { _ -> log.info("PROBE DONE") }
        step("quit", 10) { mc -> mc.stop() }
    }

    /** Saves [count] frames, one every [every] ticks, as run/screenshots/gif_<name>_NNN.png. */
    private fun frames(name: String, count: Int, every: Int) {
        for (i in 0 until count) step("frame $name $i", every) { mc -> Screenshot.grab(mc.gameDirectory, "gif_${name}_%03d.png".format(i), mc.mainRenderTarget) { } }
    }

    /** Footage for the mod page: a level 55 Machop felling a big tree in one swing, then building a Litematica house. */
    private fun gifScript() {
        lateinit var where: BlockPos
        step("create world", 60, { it.screen is TitleScreen }) { mc ->
            mc.createWorldOpenFlows().createFreshLevel(
                "Foremon Test",
                LevelSettings("Foremon Test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT),
                WorldOptions(1L, false, false),
                { ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },
                mc.screen
            )
        }
        step("commands", 100, { it.player != null && it.level != null }) { mc ->
            origin = mc.player!!.blockPosition()
            chest = origin.offset(3, 0, 0)
            where = origin.offset(-4, 0, 3)
            for (c in listOf(
                "gamerule doDaylightCycle false", "time set 1000", "weather clear", "gamerule doMobSpawning false",
                "give @s foremon:whistle", "pokegive machop level=55", "pokegive bulbasaur level=10",
                "setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest",
                "place feature minecraft:fancy_oak ${origin.x + 8} ${origin.y} ${origin.z - 3}",
                "item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:oak_planks 30",
                "item replace block ${chest.x} ${chest.y} ${chest.z} container.1 with minecraft:cobblestone 20",
                "item replace block ${chest.x} ${chest.y} ${chest.z} container.2 with minecraft:torch 2",
            )) cmd(mc, c)
        }
        step("close any screen", 60) { mc -> mc.setScreen(null) }
        step("use whistle on chest", 20) { mc -> useWhistleOnChest(mc) }
        step("assign machop", 30, { it.screen is SiteScreen }) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).party(0)) }
        step("press Start", 20) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("camera on the tree", 5) { mc ->
            mc.setScreen(null)
            mc.options.hideGui = true
            mc.toasts.clear()
            mc.player!!.abilities.flying = true; mc.player!!.onUpdateAbilities()
            cmd(mc, "tp @s ${origin.x - 1.5} ${origin.y + 3} ${origin.z - 9.5} facing ${origin.x + 6.5} ${origin.y + 4} ${origin.z - 2.5}")
        }
        frames("timber", 70, 3)
        step("camera on the build spot", 10) { mc ->
            mc.options.hideGui = false
            placeSchematic(mc, "foremon_test_house.litematic", where)
            cmd(mc, "tp @s ${origin.x + 1} ${origin.y} ${origin.z} facing ${chest.x} ${chest.y} ${chest.z}")
        }
        step("open the site", 30) { mc -> useWhistleOnChest(mc) }
        pickJob(6, 30, { it.screen is SiteScreen })
        step("load plan", 20) { _ -> dev.foremon.client.LitematicaCompat.upload(chest) }
        step("start building", 40) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("camera on the house", 5) { mc ->
            mc.setScreen(null)
            mc.options.hideGui = true
            mc.toasts.clear()
            cmd(mc, "tp @s ${where.x + 7.5} ${origin.y + 3} ${where.z - 4.5} facing ${where.x + 2.5} ${origin.y} ${where.z + 2.5}")
        }
        frames("build", 80, 4)
        step("done", 10) { mc -> mc.options.hideGui = false; log.info("PROBE DONE") }
        step("quit", 10) { mc -> mc.stop() }
    }

    /**
     * Footage of a crew of three building a real village house (Minecraft's own plains big house, converted to a
     * Litematica file). Materials are worked out from the plan and spread over the site chest and two extra chests.
     */
    private fun houseScript() {
        lateinit var where: BlockPos
        val extras = ArrayList<BlockPos>()
        step("create world", 60, { it.screen is TitleScreen }) { mc ->
            mc.createWorldOpenFlows().createFreshLevel(
                "Foremon Test",
                LevelSettings("Foremon Test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT),
                WorldOptions(1L, false, false),
                { ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },
                mc.screen
            )
        }
        step("commands", 100, { it.player != null && it.level != null }) { mc ->
            origin = mc.player!!.blockPosition()
            chest = origin.offset(4, 0, 0)
            extras.add(origin.offset(4, 0, -2)); extras.add(origin.offset(4, 0, -4))
            where = origin.offset(-6, 0, 3)
            val cmds = arrayListOf(
                "gamerule doDaylightCycle false", "time set 2000", "weather clear", "gamerule doMobSpawning false",
                "give @s foremon:whistle", "pokegive machop level=55", "pokegive machoke level=52", "pokegive pidgeot level=50", "pokegive bulbasaur level=10",
                "setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest",
            )
            for (e in extras) cmds.add("setblock ${e.x} ${e.y} ${e.z} minecraft:chest")
            cmds.forEach { cmd(mc, it) }
            // A bigger work area, a taller one and room for three workers: what a player would have bought by then.
            val server = mc.singleplayerServer!!
            server.execute {
                val st = dev.foremon.Rules.state(server, mc.player!!.uuid)
                st.bought["work_area"] = 1; st.bought["vertical_range"] = 1; st.bought["crew_size"] = 2; st.bought["linked_chests"] = 1
            }
        }
        step("close any screen", 60) { mc -> mc.setScreen(null) }
        step("place the village house in Litematica", 10) { mc -> placeSchematic(mc, "plains_big_house_1.litematic", where) }
        step("use whistle on chest", 40) { mc -> useWhistleOnChest(mc) }
        pickJob(6, 30, { it.screen is SiteScreen })
        step("load plan", 20) { _ -> dev.foremon.client.LitematicaCompat.upload(chest) }
        step("crew, extra chests and materials", 60) { mc ->
            val server = mc.singleplayerServer!!
            server.execute {
                val level = server.overworld()
                val site = SiteStore.of(level).sites[chest] ?: return@execute
                val plan = site.plan ?: run { log.error("PROBE FAIL: no plan loaded"); return@execute }
                for (e in extras) { site.links.add(e); site.chest(e) }
                // Three workers from the party, keeping one Pokemon in it.
                val player = server.playerList.players.first()
                val party = com.cobblemon.mod.common.Cobblemon.storage.getParty(player)
                for (p in party.toList().filter { it.species.resourceIdentifier.path != "bulbasaur" })
                    Workers.assign(player, level, site, p)?.let { log.info("PROBE could not assign {}: {}", p.species.name, it) }
                // Everything the plan costs, plus a little spare, spread over the chests.
                val need = HashMap<net.minecraft.world.item.Item, Int>()
                for (i in 0 until plan.size) {
                    val c = dev.foremon.work.Building.cost(plan.state(i))
                    if (c.count > 0) need.merge(c.item, c.count, Int::plus)
                }
                val stores = (listOf(chest) + extras).mapNotNull { Inv.storage(level, it) }
                for ((item, n) in need) {
                    var left = net.minecraft.world.item.ItemStack(item, n + 2)
                    for (st in stores) { if (left.isEmpty) break; left = Inv.insert(st, left) }
                    if (!left.isEmpty) log.info("PROBE no room for {} x{}", item, left.count)
                }
                log.info("PROBE house: {} blocks in the plan, {} kinds of material, {} workers", plan.size, need.size, site.workers.size)
            }
        }
        step("press Start", 20) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("camera", 5) { mc ->
            mc.setScreen(null)
            mc.options.hideGui = true
            mc.toasts.clear()
            // Fly, or the camera drops to the ground the moment it is placed in the air.
            mc.player!!.abilities.flying = true; mc.player!!.onUpdateAbilities()
            cmd(mc, "tp @s ${where.x + 10.5} ${origin.y + 7} ${where.z - 1.5} facing ${where.x + 3.5} ${origin.y + 4} ${where.z + 5.5}")
        }
        frames("house", 320, 15)
        step("report", 5) { mc ->
            val server = mc.singleplayerServer!!
            server.execute {
                val site = SiteStore.of(server.overworld()).sites[chest]
                log.info("PROBE house result: placed {} of {}, skipped {}, status {} {}, active {}", site?.plan?.placed, site?.plan?.size, site?.plan?.skipped, site?.status, site?.statusArg, site?.active)
                site?.workers?.forEach { w -> log.info("PROBE house worker {} holds {}", w.pokemon?.species?.name, w.supplies.joinToString { "${it.count} ${it.item}" }) }
            }
        }
        step("done", 10) { mc -> mc.options.hideGui = false; log.info("PROBE DONE") }
        step("quit", 10) { mc -> mc.stop() }
    }

    private fun markersScript() {
        lateinit var extra: BlockPos
        step("create world", 60, { it.screen is TitleScreen }) { mc ->
            mc.createWorldOpenFlows().createFreshLevel(
                "Foremon Test",
                LevelSettings("Foremon Test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT),
                WorldOptions(1L, false, false),
                { ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },
                mc.screen
            )
        }
        step("commands", 100, { it.player != null && it.level != null }) { mc ->
            origin = mc.player!!.blockPosition()
            chest = origin.offset(3, 0, 0)
            extra = origin.offset(3, 0, 3)
            for (c in listOf(
                "gamerule doDaylightCycle false", "time set noon", "weather clear", "gamerule doMobSpawning false",
                "give @s foremon:whistle", "pokegive machop level=30", "pokegive geodude level=30",
                "pokegive bulbasaur level=12", "pokegive charmander level=22", "pokegive squirtle level=8", "pokegive pidgey level=40",
                "pokegive abra level=18", "pokegive onix level=52", "pokegive scyther level=36", "pokegive gastly level=25",
                "setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest",
                "setblock ${chest.x} ${chest.y} ${chest.z + 1} minecraft:chest",
                "setblock ${extra.x} ${extra.y} ${extra.z} minecraft:chest",
                "setblock ${origin.x + 6} ${origin.y} ${origin.z + 6} minecraft:chest",
            )) cmd(mc, c)
        }
        step("close any screen", 60) { mc -> mc.setScreen(null) }
        step("use whistle on chest", 20) { mc -> useWhistleOnChest(mc) }
        step("assign machop by clicking", 30, { it.screen is SiteScreen }) { mc ->
            val s = mc.screen as SiteScreen
            click(mc, Layout(s.width, s.height).party(0))
        }
        step("start", 20) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("close", 10) { mc -> mc.setScreen(null) }
        step("sneak", 10) { mc -> sneak(mc, true) }
        step("add the extra chest", 5) { mc -> useWhistleOn(mc, extra) }
        step("stop sneaking", 5) { mc -> sneak(mc, false) }
        step("look at the chests", 5) { mc -> cmd(mc, "tp @s ${origin.x - 1.5} ${origin.y} ${origin.z + 1.5} facing ${origin.x + 3.5} ${origin.y + 0.5} ${origin.z + 1.5}") }
        step("shot: markers, site running", 70) { mc -> shot(mc, "20_markers_running"); report(mc, "markers") }
        step("open site", 5) { mc -> useWhistleOnChest(mc) }
        step("shot: site with extra chest", 30, { it.screen is SiteScreen }) { mc -> shot(mc, "21_site_extra_chest") }
        step("open the Chests tab", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).chestsTab) }
        step("shot: chests tab", 20) { mc -> shot(mc, "21b_chests_tab") }
        step("give the extra chest the next ball", 5) { mc -> val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height); click(mc, (l.lx + 11) to (l.top + 30 + 11)) }
        step("shot: chests tab after a ball change", 20) { mc -> shot(mc, "21c_chests_ball_changed") }
        step("back to crew", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).crewTab) }
        step("pause by clicking", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("close", 10) { mc -> mc.setScreen(null) }
        step("shot: markers, site paused", 60) { mc -> shot(mc, "22_markers_paused") }
        // Smelting is something a Machop cannot do, which makes it show the "needs something" icon.
        step("open site", 5) { mc -> useWhistleOnChest(mc) }
        pickJob(5, 30, { it.screen is SiteScreen })
        step("start", 3) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("close", 20) { mc -> mc.setScreen(null) }
        step("stand next to the worker", 40) { mc ->
            val worker = mc.level!!.entitiesForRendering().filterIsInstance<PokemonEntity>().minByOrNull { it.distanceToSqr(Vec3.atCenterOf(chest)) } ?: error("no Pokemon entity on the client")
            cmd(mc, "tp @s ${"%.2f".format(java.util.Locale.ROOT, worker.x - 2.6)} ${origin.y} ${"%.2f".format(java.util.Locale.ROOT, worker.z - 1.2)} facing ${"%.2f".format(java.util.Locale.ROOT, worker.x)} ${origin.y + 1.2} ${"%.2f".format(java.util.Locale.ROOT, worker.z)}")
        }
        step("shot: icon above the worker", 30) { mc -> shot(mc, "23_worker_icon"); report(mc, "smelting with a Machop") }
        step("open site for the PC tab", 5) { mc -> cmd(mc, "tp @s ${origin.x + 1} ${origin.y} ${origin.z} facing ${chest.x} ${chest.y} ${chest.z}") }
        step("use whistle", 20) { mc -> useWhistleOnChest(mc) }
        pickJob(0, 30, { it.screen is SiteScreen })
        step("open the PC tab", 3) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).pcTab) }
        step("shot: PC box", 40) { mc -> shot(mc, "28_pc_box") }
        step("assign the first Pokemon in the box by clicking it", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).pcSlot(0)) }
        step("shot: PC box after assigning", 40) { mc -> shot(mc, "29_pc_after_assign"); report(mc, "after assigning from the PC") }
        step("crew tab", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).crewTab) }
        step("shot: crew in the Pokemon look", 15) { mc -> shot(mc, "30_crew_pokemon_look") }
        step("open skills", 5) { _ -> send("skills") }
        step("shot: skill tree", 30, { it.screen is HubScreen }) { mc -> shot(mc, "24_skills") }
        step("labour branch", 5) { mc -> val s = mc.screen as HubScreen; val l = Layout(s.width, s.height); click(mc, l.branch(1)) }
        step("shot: labour branch", 15) { mc -> shot(mc, "25_skills_labour") }
        step("open perks", 5) { _ -> send("perks") }
        step("shot: perks", 30) { mc -> shot(mc, "26_perks_lumber") }
        step("mining perks", 5) { mc -> val s = mc.screen as HubScreen; val l = Layout(s.width, s.height); click(mc, l.job(1)) }
        step("shot: mining perks", 15) { mc -> shot(mc, "27_perks_mining") }
        step("done", 10) { mc -> log.info("PROBE DONE") }
        step("quit", 10) { mc -> mc.stop() }
    }

    private fun script() {
        step("create world", 60, { it.screen is TitleScreen }) { mc ->
            mc.createWorldOpenFlows().createFreshLevel(
                "Foremon Test",
                LevelSettings("Foremon Test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT),
                WorldOptions(1L, false, false),
                { ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },
                mc.screen
            )
        }
        step("commands", 100, { it.player != null && it.level != null }) { mc ->
            origin = mc.player!!.blockPosition()
            chest = origin.offset(3, 0, 0)
            for (c in listOf(
                "gamerule doDaylightCycle false", "time set noon", "weather clear", "gamerule doMobSpawning false",
                "give @s foremon:whistle", "pokegive machop level=30", "pokegive geodude level=30", "pokegive charmander level=30",
                "setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest",
                "place feature minecraft:oak ${origin.x + 7} ${origin.y} ${origin.z - 4}",
                "place feature minecraft:birch ${origin.x + 8} ${origin.y} ${origin.z + 2}",
                "fill ${origin.x - 5} ${origin.y} ${origin.z + 2} ${origin.x - 3} ${origin.y + 1} ${origin.z + 4} minecraft:stone",
                "foremon admin xp @s 500",
            )) cmd(mc, c)
        }
        step("close any screen", 60) { mc -> if (mc.screen != null) { log.info("PROBE: a screen was open after joining: {}", mc.screen!!.javaClass.name); mc.setScreen(null) } }
        step("use whistle on chest", 20) { mc -> useWhistleOnChest(mc) }
        step("shot: new site", 30, { it.screen is SiteScreen }) { mc ->
            val s = mc.screen as SiteScreen
            log.info("PROBE gui size {}x{} window {}x{}", s.width, s.height, mc.window.width, mc.window.height)
            shot(mc, "01_site_new")
        }
        step("assign machop by clicking", 10) { mc ->
            val s = mc.screen as SiteScreen
            val party = s.data.getList("party", Tag.TAG_COMPOUND.toInt())
            val i = (0 until party.size).firstOrNull { party.getCompound(it).getString("species").contains("machop") } ?: error("machop is not in the party list: $party")
            click(mc, Layout(s.width, s.height).party(i))
        }
        step("shot: assigned", 30) { mc -> shot(mc, "02_site_assigned") }
        step("click filter tab", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).filterTab) }
        step("shot: filter", 15) { mc -> shot(mc, "03_filter") }
        step("click crew tab", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).crewTab) }
        step("then start", 3) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("close the menu", 5) { mc -> mc.setScreen(null) }
        // Stand right next to the worker and take a burst of frames, to see the animation, the particles and the log it carries.
        step("stand next to the worker", 60) { mc ->
            val tree = Vec3.atCenterOf(origin.offset(7, 0, -4))
            val worker = mc.level!!.entitiesForRendering().filterIsInstance<PokemonEntity>().minByOrNull { it.distanceToSqr(tree) } ?: error("no Pokemon entity on the client")
            log.info("PROBE worker on client: {} at {} pose={} hand={}", worker.pokemon.species.name, worker.blockPosition().toShortString(), worker.entityData.get(PokemonEntity.POSE_TYPE), worker.mainHandItem)
            cmd(mc, "tp @s ${"%.2f".format(java.util.Locale.ROOT, worker.x - 2.4)} ${origin.y} ${"%.2f".format(java.util.Locale.ROOT, worker.z - 1.6)} facing ${"%.2f".format(java.util.Locale.ROOT, worker.x)} ${origin.y + 0.9} ${"%.2f".format(java.util.Locale.ROOT, worker.z)}")
        }
        for ((n, d) in listOf(12, 3, 3, 3, 3, 3, 3, 3, 3, 3).withIndex()) step("shot: chopping $n", d) { mc -> shot(mc, "04_chop_$n") }
        step("report", 1) { mc -> report(mc, "while chopping") }
        step("wide view", 60) { mc -> cmd(mc, "tp @s ${origin.x + 3} ${origin.y} ${origin.z - 12} facing ${origin.x + 5} ${origin.y + 2} ${origin.z}") }
        step("shot: wide", 200) { mc -> shot(mc, "05_wide"); report(mc, "after 17s") }
        step("wait for lumber to finish", 300) { mc -> report(mc, "lumber end") }

        // ---- mining: mark two corners by sneak-clicking, like a player
        step("walk to the chest", 5) { mc -> cmd(mc, "tp @s ${origin.x + 1} ${origin.y} ${origin.z} facing ${chest.x} ${chest.y} ${chest.z}") }
        step("reopen site", 20) { mc -> useWhistleOnChest(mc) }
        step("shot: site after lumber", 30, { it.screen is SiteScreen }) { mc -> shot(mc, "06_site_after_lumber") }
        pickJob(1)
        step("walk to the stone", 20) { mc -> mc.setScreen(null); cmd(mc, "tp @s ${origin.x - 1} ${origin.y} ${origin.z + 3} facing ${origin.x - 4} ${origin.y + 1} ${origin.z + 3}") }
        step("sneak", 20) { mc -> sneak(mc, true) }
        step("corner a", 5) { mc -> useWhistleOn(mc, origin.offset(-5, 0, 2)) }
        step("corner b", 5) { mc -> useWhistleOn(mc, origin.offset(-3, 1, 4)) }
        step("stop sneaking", 5) { mc -> sneak(mc, false); report(mc, "corners marked") }
        step("shot: mining a", 60) { mc -> shot(mc, "07_mining_a") }
        step("shot: mining b", 160) { mc -> shot(mc, "08_mining_b"); report(mc, "mining 11s") }
        step("wait for mining", 400) { mc -> shot(mc, "09_mining_c"); report(mc, "mining 31s") }

        // ---- hub screens
        step("open skills", 5) { _ -> send("skills") }
        step("shot: skills", 30, { it.screen is HubScreen }) { mc -> shot(mc, "10_skills") }
        step("buy Work Area by clicking its node", 5) { mc ->
            val s = mc.screen as HubScreen; val l = Layout(s.width, s.height)
            click(mc, l.skillNode(2, 4, 0, 0))
        }
        step("shot: skills bought", 30) { mc -> shot(mc, "11_skills_bought") }
        step("open board", 5) { _ -> send("board") }
        step("shot: board", 30) { mc -> shot(mc, "12_board") }
        step("open dex", 5) { _ -> send("dex") { it.putString("query", "geodude") } }
        step("shot: dex", 30) { mc -> shot(mc, "13_dex") }
        step("open guide", 5) { mc -> (mc.screen as HubScreen).accept("guide", CompoundTag()) }
        step("shot: guide", 15) { mc -> shot(mc, "14_guide") }
        step("open admin", 5) { _ -> send("admin") }
        step("shot: admin", 30) { mc -> shot(mc, "15_admin") }
        step("done", 20) { mc -> report(mc, "end"); log.info("PROBE DONE") }
        step("quit", 20) { mc -> mc.stop() }
    }
}
