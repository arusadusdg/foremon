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

    // Same layout maths as SiteScreen.draw, so the clicks land where a player would click.
    private class Layout(sw: Int, sh: Int) {
        val w = min(430, sw - 16); val h = min(236, sh - 16)
        val x0 = (sw - w) / 2; val y0 = (sh - h) / 2
        val cx = x0 + 100; val cw = w - 214
        fun party(i: Int) = (cx + i * 27 + 12) to (y0 + 52 + (h - 66) - 34 + 16)
        val start get() = (x0 + w - 108 + 51) to (y0 + 32 + 12)
        val crewTab get() = (cx + cw / 8) to (y0 + 40)
        val pcTab get() = (cx + cw / 4 + cw / 8) to (y0 + 40)
        val filterTab get() = (cx + cw / 4 * 3 + cw / 8) to (y0 + 40)
        /** Slot [i] of the PC box grid (6 columns, 5 rows). */
        fun pcSlot(i: Int): Pair<Int, Int> {
            val areaH = h - 66
            val cell = minOf(24, minOf((cw - 4) / 6, (areaH - 36) / 5))
            val gx = cx + (cw - cell * 6) / 2; val gy = y0 + 52 + 22
            return (gx + (i % 6) * cell + cell / 2) to (gy + (i / 6) * cell + cell / 2)
        }
        fun job(i: Int) = (x0 + 50) to (y0 + 32 + i * 22 + 10)
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
        step("switch to building", 20) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).job(6)) }
        step("shot: building menu", 20) { mc -> shot(mc, "41_building_menu") }
        step("press Load plan", 5) { mc ->
            val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height)
            click(mc, (l.x0 + l.w - 108 + 25) to (l.y0 + 102 + 8))
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
        step("open the Chests tab", 5) { mc -> val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height); click(mc, (l.cx + l.cw / 4 * 2 + l.cw / 8) to (l.y0 + 40)) }
        step("shot: chests tab", 20) { mc -> shot(mc, "21b_chests_tab") }
        step("give the extra chest the next ball", 5) { mc -> val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height); click(mc, (l.cx + 11) to (l.y0 + 52 + 11 + 22 + 10)) }
        step("shot: chests tab after a ball change", 20) { mc -> shot(mc, "21c_chests_ball_changed") }
        step("back to crew", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).crewTab) }
        step("pause by clicking", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).start) }
        step("close", 10) { mc -> mc.setScreen(null) }
        step("shot: markers, site paused", 60) { mc -> shot(mc, "22_markers_paused") }
        // Smelting is something a Machop cannot do, which makes it show the "needs something" icon.
        step("open site", 5) { mc -> useWhistleOnChest(mc) }
        step("switch to smelting and start", 30, { it.screen is SiteScreen }) { mc ->
            val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height); click(mc, l.job(5)); click(mc, l.start)
        }
        step("close", 20) { mc -> mc.setScreen(null) }
        step("stand next to the worker", 40) { mc ->
            val worker = mc.level!!.entitiesForRendering().filterIsInstance<PokemonEntity>().minByOrNull { it.distanceToSqr(Vec3.atCenterOf(chest)) } ?: error("no Pokemon entity on the client")
            cmd(mc, "tp @s ${"%.2f".format(java.util.Locale.ROOT, worker.x - 2.6)} ${origin.y} ${"%.2f".format(java.util.Locale.ROOT, worker.z - 1.2)} facing ${"%.2f".format(java.util.Locale.ROOT, worker.x)} ${origin.y + 1.2} ${"%.2f".format(java.util.Locale.ROOT, worker.z)}")
        }
        step("shot: icon above the worker", 30) { mc -> shot(mc, "23_worker_icon"); report(mc, "smelting with a Machop") }
        step("open site for the PC tab", 5) { mc -> cmd(mc, "tp @s ${origin.x + 1} ${origin.y} ${origin.z} facing ${chest.x} ${chest.y} ${chest.z}") }
        step("use whistle", 20) { mc -> useWhistleOnChest(mc) }
        step("switch to lumber and open the PC tab", 30, { it.screen is SiteScreen }) { mc ->
            val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height); click(mc, l.job(0)); click(mc, l.pcTab)
        }
        step("shot: PC box", 40) { mc -> shot(mc, "28_pc_box") }
        step("assign the first Pokemon in the box by clicking it", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).pcSlot(0)) }
        step("shot: PC box after assigning", 40) { mc -> shot(mc, "29_pc_after_assign"); report(mc, "after assigning from the PC") }
        step("crew tab", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).crewTab) }
        step("shot: crew in the Pokemon look", 15) { mc -> shot(mc, "30_crew_pokemon_look") }
        step("open skills", 5) { _ -> send("skills") }
        step("shot: skill tree", 30, { it.screen is HubScreen }) { mc -> shot(mc, "24_skills") }
        step("labour branch", 5) { mc -> val s = mc.screen as HubScreen; val l = Layout(s.width, s.height); click(mc, (l.x0 + 6 + 74 + 36) to (l.y0 + 34 + 8)) }
        step("shot: labour branch", 15) { mc -> shot(mc, "25_skills_labour") }
        step("open perks", 5) { _ -> send("perks") }
        step("shot: perks", 30) { mc -> shot(mc, "26_perks_lumber") }
        step("mining perks", 5) { mc -> val s = mc.screen as HubScreen; val l = Layout(s.width, s.height); click(mc, (l.x0 + 6 + 48) to (l.y0 + 34 + 20 + 9)) }
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
        step("click crew tab, then start", 5) { mc -> val s = mc.screen as SiteScreen; val l = Layout(s.width, s.height); click(mc, l.crewTab); click(mc, l.start) }
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
        step("switch to mining by clicking", 5) { mc -> val s = mc.screen as SiteScreen; click(mc, Layout(s.width, s.height).job(1)) }
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
            click(mc, (l.x0 + 6 + 2 + 20) to (l.y0 + 34 + 26 + 17))
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
