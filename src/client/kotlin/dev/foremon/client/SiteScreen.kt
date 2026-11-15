package dev.foremon.client

import dev.foremon.rules.Job
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.Heightmap
import dev.foremon.world.Zone
import kotlin.math.min

/** The menu of one job site: pick the job, manage the crew, set the filter, start and pause. */
private val BALLS_CLIENT = dev.foremon.world.BALLS
private val LOAD_COLOR = 0xFF5AB0FF.toInt()
/** Route picker row: "a chest in the world", picked with a sneak-click after the menu closes. */
private const val PICK_IN_WORLD = Long.MIN_VALUE

class SiteScreen(var data: CompoundTag) : ForemonScreen(tr("foremon.gui.site")) {
    private var tab = 0
    private var confirmRemove = 0L
    private var ticks = 0
    /** Routes tab: picking two job sites for a new route (Remote Control); the first one picked, once picked. */
    private var picking = false
    private var pickFrom: Long? = null
    private var crewFor = -1

    /** While the site runs, ask for fresh numbers every half second so the load and stamina bars move. */
    override fun tick() {
        super.tick()
        // Not on the PC tab: each update fetches the PC box again.
        if (++ticks % 10 == 0 && data.getBoolean("active") && tab != 2) op("refresh")
    }

    /** The chest whose filter the Filter tab edits: the site's own chest unless one was picked in the Chests tab. */
    private var filterChest: Long? = null

    private fun chests() = data.getList("chests", Tag.TAG_COMPOUND.toInt()).let { l -> (0 until l.size).map { l.getCompound(it) } }

    private fun selectedChest(): CompoundTag? {
        val all = chests()
        return all.firstOrNull { it.getLong("pos") == (filterChest ?: pos) } ?: all.firstOrNull()
    }

    private fun ballName(id: String) = MonIcons.ball(id).hoverName

    /** The PC box being shown, as last sent by the server; null while it is being fetched. */
    var pc: CompoundTag? = null
    private var pcBox = 0
    private var pcAsked = -1

    /** New data for this site from the server. The PC box is fetched again because who is busy may have changed. */
    fun update(tag: CompoundTag) {
        // A refresh leaves out the party; keep the one we have.
        if (!tag.contains("party")) data.get("party")?.let { tag.put("party", it) }
        data = tag
        if (tab == 2) { pc = null; pcAsked = -1 }
    }

    private fun changeBox(delta: Int, boxes: Int) {
        pcBox = (pcBox + delta).mod(boxes.coerceAtLeast(1))
        pc = null; pcAsked = -1
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        if (tab != 2) return super.mouseScrolled(x, y, dx, dy)
        pc?.let { changeBox(if (dy < 0) 1 else -1, it.getInt("boxes")) }
        return true
    }

    private val pos get() = data.getLong("pos")
    private val job get() = Job.of(data.getString("job")) ?: Job.LUMBER

    private fun op(name: String, fill: (CompoundTag) -> Unit = {}) = send(name) { it.putLong("pos", pos); it.putString("dim", data.getString("dim")); fill(it) }

    private val presets = listOf(
        "#c:ores" to Items.IRON_ORE, "#minecraft:logs" to Items.OAK_LOG, "#c:stones" to Items.STONE, "#minecraft:dirt" to Items.DIRT,
        "#minecraft:crops" to Items.WHEAT, "#c:ingots" to Items.IRON_INGOT, "#c:raw_materials" to Items.RAW_IRON, "#c:gems" to Items.DIAMOND,
    )

    override fun draw(g: GuiGraphics) {
        val w = min(430, width - 16); val h = min(236, height - 16)
        val x0 = (width - w) / 2; val y0 = (height - h) / 2
        window(g, x0, y0, w, h)

        // ---- header
        icon(g, ItemStack(JOB_ICONS[job] ?: Items.PAPER), x0 + 6, y0 + 4)
        text(g, tr("foremon.gui.site_title", tr("foremon.job.${job.key}")), x0 + 26, y0 + 5)
        val xp = tr("foremon.gui.xp", data.getInt("xp"))
        text(g, xp, x0 + w - 8 - font.width(xp), y0 + 5, Pal.STAR)
        val status = data.getString("status")
        val line = if (status.isNotEmpty()) trArg(status, data.getString("statusArg"))
        else tr(if (data.getBoolean("active")) "foremon.gui.running" else "foremon.gui.paused")
        clip(g, line, x0 + 26, y0 + 15, w - 36, if (status.isNotEmpty()) Pal.WARN else if (data.getBoolean("active")) Pal.GOOD else Pal.MUTED)
        g.fill(x0 + 1, y0 + 27, x0 + w - 1, y0 + 28, Pal.LINE)

        // ---- jobs, left
        var y = y0 + 32
        for (j in Job.entries) {
            val sel = j == job
            val hot = hover(x0 + 6, y, 88, 19)
            g.fill(x0 + 6, y, x0 + 94, y + 19, if (sel) Pal.ACCENT else if (hot) Pal.CARD_HOVER else Pal.CARD)
            icon(g, ItemStack(JOB_ICONS[j] ?: Items.PAPER), x0 + 8, y + 1)
            clip(g, tr("foremon.job.${j.key}"), x0 + 27, y + 6, 64)
            if (hot) tip = lines(tr("foremon.job.${j.key}"), muted(tr("foremon.job.${j.key}.hint")))
            click(x0 + 6, y, 88, 19) { op("job") { t -> t.putString("job", j.key) } }
            y += 21
        }

        // ---- controls, right
        val rx = x0 + w - 108
        val active = data.getBoolean("active")
        button(g, rx, y0 + 32, 102, 24, tr(if (active) "foremon.gui.pause" else "foremon.gui.start"), accent = !active) { op("toggle") }
        y = y0 + 62
        text(g, tr("foremon.gui.area", data.getInt("radius") * 2, data.getInt("vertical")), rx, y, Pal.MUTED); y += 11
        // Extra chests: more storage for the site (or the destinations, for Sorting and Hauling).
        val links = data.getInt("links")
        val logistics = job == Job.SORTING || job == Job.HAULING
        text(g, tr("foremon.gui.links", links, data.getInt("maxLinks")), rx, y + 3, if (links == 0 && logistics) Pal.WARN else Pal.TEXT)
        if (hover(rx, y, 86, 14)) tip = lines(tr("foremon.gui.links.title"), muted(tr(if (logistics) "foremon.gui.links.hint_logistics" else "foremon.gui.links.hint")), muted(tr("foremon.gui.links.how")))
        button(g, rx + 88, y, 14, 14, Component.literal("x"), enabled = links > 0, tooltip = lines(tr("foremon.gui.clear_links"))) { op("clear_links") }
        y += 17
        when (job) {
            Job.MINING, Job.DEMOLITION -> {
                if (data.contains("lo") && data.getBoolean("areaDone")) {
                    val lo = BlockPos.of(data.getLong("lo")); val hi = BlockPos.of(data.getLong("hi"))
                    text(g, tr("foremon.gui.marked", hi.x - lo.x + 1, hi.y - lo.y + 1, hi.z - lo.z + 1), rx, y, Pal.TEXT)
                } else text(g, tr(if (data.contains("lo")) "foremon.gui.mark_second" else "foremon.gui.mark_first"), rx, y, Pal.WARN)
                y += 12
                button(g, rx, y, 102, 16, tr("foremon.gui.clear_area"), enabled = data.contains("lo")) { op("clear_area") }; y += 20
            }
            Job.BUILDING -> {
                if (data.contains("plan")) {
                    val p = data.getCompound("plan")
                    clip(g, Component.literal(p.getString("name")), rx, y, 102); y += 11
                    bar(g, rx, y, 102, 6, p.getInt("index").toDouble() / p.getInt("size").coerceAtLeast(1), Pal.GOOD); y += 9
                    text(g, tr("foremon.gui.plan_progress", p.getInt("placed"), p.getInt("size")), rx, y, Pal.MUTED); y += 12
                } else {
                    text(g, tr("foremon.gui.no_plan"), rx, y, Pal.WARN); y += 12
                }
                button(g, rx + 53, y, 49, 16, tr("foremon.gui.clear_plan"), enabled = data.contains("plan")) { op("plan_clear") }
                val lite = LitematicaCompat.available
                button(g, rx, y, 51, 16, tr("foremon.gui.load_litematica"), enabled = lite,
                    tooltip = lines(tr(if (lite) "foremon.gui.load_litematica.hint" else "foremon.gui.load_litematica.missing"))) {
                    LitematicaCompat.upload(BlockPos.of(pos))?.let { err -> mc.player?.displayClientMessage(tr(err), true) }
                }
                y += 20
            }
            else -> {}
        }
        val by = y0 + h - 76
        button(g, rx, by, 102, 16, tr("foremon.gui.restart"), tooltip = lines(tr("foremon.gui.restart.hint"))) { op("restart") }
        button(g, rx, by + 19, 102, 16, tr("foremon.gui.skills")) { send("skills") }
        button(g, rx, by + 38, 102, 16, tr("foremon.gui.board")) { send("board") }
        val armed = System.currentTimeMillis() - confirmRemove < 3000
        button(g, rx, by + 57, 102, 16, tr(if (armed) "foremon.gui.remove_confirm" else "foremon.gui.remove"), accent = armed) {
            if (armed) op("remove") else confirmRemove = System.currentTimeMillis()
        }

        // ---- middle
        val cx = x0 + 100; val cw = w - 100 - 114
        val quarter = cw / 4
        button(g, cx, y0 + 32, quarter - 1, 16, tr("foremon.gui.tab_crew"), accent = tab == 0) { tab = 0 }
        button(g, cx + quarter, y0 + 32, quarter - 1, 16, tr("foremon.gui.tab_pc"), accent = tab == 2,
            tooltip = lines(tr("foremon.gui.tab_pc.hint"))) { tab = 2; pc = null; pcAsked = -1 }
        val hauling = job == Job.HAULING
        val guard = job == Job.GUARD
        button(g, cx + quarter * 2, y0 + 32, quarter - 1, 16,
            when { hauling -> tr("foremon.gui.tab_routes", data.getList("routes", Tag.TAG_COMPOUND.toInt()).size); guard -> tr("foremon.gui.tab_map"); else -> tr("foremon.gui.tab_chests", chests().size) },
            accent = tab == 3, tooltip = lines(tr(when { hauling -> "foremon.gui.routes.how"; guard -> "foremon.gui.tab_map.hint"; else -> "foremon.gui.tab_chests.hint" }))) { tab = 3 }
        button(g, cx + quarter * 3, y0 + 32, cw - quarter * 3, 16, tr("foremon.gui.tab_filter_short"), accent = tab == 1) { tab = 1 }
        when (tab) {
            0 -> crew(g, cx, y0 + 52, cw, h - 66)
            2 -> pcTab(g, cx, y0 + 52, cw, h - 66)
            3 -> if (hauling) routesTab(g, cx, y0 + 52, cw, h - 66) else if (guard) mapTab(g, cx, y0 + 52, cw, h - 66) else chestsTab(g, cx, y0 + 52, cw, h - 66)
            else -> filter(g, cx, y0 + 52, cw)
        }

        clip(g, muted(tr("foremon.gui.hint.${job.key}")), x0 + 6, y0 + h - 12, w - 120, Pal.MUTED)
    }

    private fun factorLine(f: String): Component {
        val (id, mult) = f.split('=').let { it[0] to it.getOrElse(1) { "1" } }
        val kind = id.substringBefore(':'); val detail = id.substringAfter(':', "")
        val value = mult.toDoubleOrNull() ?: 1.0
        val c = Component.literal("x$mult ").withStyle(if (value >= 1.0) ChatFormatting.GREEN else ChatFormatting.RED)
        return c.append(tr("foremon.factor.$kind", detail.replace('+', '/')).withStyle(ChatFormatting.GRAY))
    }

    private fun monTip(m: CompoundTag, action: String): List<Component> {
        val out = ArrayList<Component>()
        out.add(Component.literal("${m.getString("name")}  ").append(tr("foremon.gui.level", m.getInt("level")).withStyle(ChatFormatting.GRAY)))
        val stars = m.getIntArray("stars")
        val s = stars.getOrElse(job.ordinal) { 0 }
        if (s == 0) out.add(tr("foremon.gui.cannot", tr("foremon.job.${job.key}")).withStyle(ChatFormatting.RED))
        else {
            out.add(tr("foremon.gui.speed", "%.2f".format(m.getDouble("mult"))).withStyle(ChatFormatting.GOLD))
            m.getList("factors", Tag.TAG_STRING.toInt()).forEach { out.add(factorLine(it.asString)) }
        }
        val mastery = m.getIntArray("mastery").getOrElse(job.ordinal) { 0 }
        out.add(tr("foremon.gui.mastery", tr("foremon.mastery.$mastery")).withStyle(ChatFormatting.AQUA))
        out.add(tr("foremon.gui.style", tr("foremon.style.${m.getString("style")}"), m.getInt("carry")).withStyle(ChatFormatting.GRAY))
        val caps = m.getString("caps")
        if (caps.isNotEmpty()) out.add(tr("foremon.gui.caps", caps.split(',').joinToString(", ") { tr("foremon.cap.$it").string }).withStyle(ChatFormatting.GRAY))
        if (job == Job.MINING) out.add(tr("foremon.gui.tier", tr("foremon.tier.${m.getInt("tier")}")).withStyle(ChatFormatting.GRAY))
        // Level perks this Pokemon has for the job, and the next one coming.
        m.getList("unlocks", Tag.TAG_STRING.toInt()).forEach { u ->
            val parts = u.asString.split('=')
            out.add(unlockText(parts[0], parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0).withStyle(ChatFormatting.GREEN))
        }
        m.getString("nextUnlock").split('|').takeIf { it.size == 3 }?.let { n ->
            out.add(tr("foremon.gui.unlock_next", n[0], unlockText(n[1], n[2].toDoubleOrNull() ?: 1.0)).withStyle(ChatFormatting.GRAY))
        }
        out.add(tr(action).withStyle(ChatFormatting.YELLOW))
        return out
    }

    private fun crew(g: GuiGraphics, x: Int, y0: Int, w: Int, h: Int) {
        val workers = data.getList("workers", Tag.TAG_COMPOUND.toInt())
        text(g, tr("foremon.gui.crew", workers.size, data.getInt("maxWorkers")), x, y0, Pal.MUTED)
        val cardW = (w - 4) / 2
        val upkeep = data.getBoolean("upkeep")
        for (i in 0 until workers.size) {
            val m = workers.getCompound(i)
            val cx = x + (i % 2) * (cardW + 4); val cy = y0 + 11 + (i / 2) * 38
            val hot = hover(cx, cy, cardW, 36)
            pokePanel(g, cx, cy, cardW, 36, hot)
            icon(g, MonIcons.of(m), cx + 2, cy + 2, 2f)
            pokeball(g, cx + 3, cy + 26)
            // Name gets the whole top line; the level sits beside the stars, like the party screen in the games.
            g.drawString(font, font.plainSubstrByWidth(m.getString("name"), cardW - 40), cx + 36, cy + 3, 0xFFFFFFFF.toInt(), true)
            val lv = tr("foremon.gui.perk_lv", m.getInt("level")).string
            stars(g, cx + 36, cy + 13, m.getIntArray("stars").getOrElse(job.ordinal) { 0 })
            if (job == Job.HAULING) {
                // Haulers: stamina on top, and the load they are working on underneath, filling up as it goes.
                if (upkeep) bar(g, cx + 36, cy + 22, cardW - 40, 2, m.getDouble("stamina") / m.getDouble("maxStamina").coerceAtLeast(1.0), if (m.getBoolean("resting")) Pal.WARN else Pal.GOOD)
                bar(g, cx + 36, cy + 25, cardW - 40, 2, m.getFloat("progress").toDouble(), LOAD_COLOR)
            } else if (upkeep) bar(g, cx + 36, cy + 24, cardW - 40, 3, m.getDouble("stamina") / m.getDouble("maxStamina").coerceAtLeast(1.0), if (m.getBoolean("resting")) Pal.WARN else Pal.GOOD)
            val mastery = m.getIntArray("mastery").getOrElse(job.ordinal) { 0 }
            // Level on the bottom line, right-aligned, so it never covers the stars or the name.
            g.drawString(font, lv, cx + cardW - 3 - font.width(lv), cy + 28, Pal.POKE_TEXT_SOFT, true)
            clip(g, tr(if (m.getBoolean("resting")) "foremon.gui.resting" else "foremon.mastery.$mastery"), cx + 36, cy + 28, cardW - 42 - font.width(lv), Pal.POKE_TEXT_SOFT)
            if (hot) tip = monTip(m, "foremon.gui.click_unassign")
            click(cx, cy, cardW, 36) { op("unassign") { t -> t.putUUID("pokemon", m.getUUID("id")) } }
        }
        // ---- the player's party, to pick workers from
        val party = data.getList("party", Tag.TAG_COMPOUND.toInt())
        val py = y0 + h - 34
        text(g, tr("foremon.gui.party"), x, py - 11, Pal.MUTED)
        for (i in 0 until party.size) {
            val m = party.getCompound(i)
            val px = x + i * 27
            val hot = hover(px, py, 25, 32)
            val s = m.getIntArray("stars").getOrElse(job.ordinal) { 0 }
            pokePanel(g, px, py, 25, 32, hot)
            icon(g, MonIcons.of(m), px + 1, py, 1.4f)
            val label = if (s == 0) "-" else "★$s"
            g.drawString(font, label, px + (25 - font.width(label)) / 2, py + 23, if (s >= 4) Pal.STAR else if (s == 0) 0xFFFFB0A8.toInt() else Pal.POKE_TEXT_SOFT, true)
            if (hot) tip = monTip(m, if (m.getBoolean("fainted")) "foremon.fail.fainted_short" else "foremon.gui.click_assign")
            click(px, py, 25, 32) { op("assign") { t -> t.putUUID("pokemon", m.getUUID("id")) } }
        }
        if (party.isEmpty() && !data.getBoolean("mine")) text(g, tr("foremon.gui.not_yours", data.getString("owner")), x, py + 10, Pal.MUTED)
    }

    /** Every chest of the site, each with its Poke Ball, its filter and where it is. */
    private fun chestsTab(g: GuiGraphics, x: Int, y0: Int, w: Int, h: Int) {
        val all = chests()
        text(g, tr("foremon.gui.links", all.size - 1, data.getInt("maxLinks")), x, y0, Pal.MUTED)
        val rowH = 22
        val rows = ((h - 24) / rowH).coerceAtLeast(1)
        for ((i, c) in all.take(rows).withIndex()) {
            val ry = y0 + 11 + i * rowH
            val cpos = BlockPos.of(c.getLong("pos"))
            val main = c.getBoolean("main")
            pokePanel(g, x, ry, w, rowH - 2, false)
            // The ball: click for the next one, right-click for the one before.
            val ball = c.getString("ball")
            val bi = BALLS_CLIENT.indexOf(ball).coerceAtLeast(0)
            if (hover(x + 2, ry + 1, 18, 18)) {
                g.fill(x + 2, ry + 1, x + 20, ry + 19, 0x60FFFFFF)
                tip = lines(ballName(ball), muted(tr("foremon.gui.ball_hint")))
            }
            icon(g, MonIcons.ball(ball), x + 3, ry + 2)
            click(x + 2, ry + 1, 18, 18) { b ->
                val next = BALLS_CLIENT[(bi + if (b == 1) BALLS_CLIENT.size - 1 else 1) % BALLS_CLIENT.size]
                op("chest_ball") { t -> t.putLong("chest", c.getLong("pos")); t.putString("ball", next) }
            }
            val name = if (main) tr("foremon.gui.chest_main") else tr("foremon.gui.chest_extra", i)
            g.drawString(font, name, x + 23, ry + 2, 0xFFFFFFFF.toInt(), true)
            val entries = c.getList("entries", Tag.TAG_STRING.toInt()).size
            val summary = if (!c.getBoolean("present")) tr("foremon.gui.chest_missing")
                else if (entries == 0) tr("foremon.gui.chest_takes_all")
                else tr(if (c.getBoolean("only")) "foremon.gui.chest_only_n" else "foremon.gui.chest_except_n", entries)
            val bw = 36
            // The text stops before the buttons; the position is in the tooltip when it does not fit.
            val textW = w - 23 - bw - (if (main) 6 else 24)
            val line = summary.string + "  ${cpos.x} ${cpos.y} ${cpos.z}"
            g.drawString(font, font.plainSubstrByWidth(line, textW), x + 23, ry + 11, Pal.POKE_TEXT_SOFT, true)
            if (hover(x + 23, ry, textW, rowH - 2)) tip = lines(name, Component.literal(line))
            button(g, x + w - bw - (if (main) 2 else 20), ry + 2, bw, 16, tr("foremon.gui.tab_filter_short")) { filterChest = c.getLong("pos"); tab = 1 }
            if (!main) button(g, x + w - 18, ry + 2, 16, 16, Component.literal("x"), tooltip = lines(tr("foremon.gui.chest_remove"))) {
                op("link_remove") { t -> t.putLong("chest", c.getLong("pos")) }
                if (filterChest == c.getLong("pos")) filterChest = null
            }
        }
        if (all.size > rows) text(g, tr("foremon.gui.more", all.size - rows), x, y0 + 11 + rows * rowH, Pal.MUTED)
        clip(g, muted(tr("foremon.gui.links.how")), x, y0 + h - 10, w, Pal.MUTED)
    }

    /** Hauling: every route, from one chest to another, what it carries, how far it goes and who works it. */
    private fun routesTab(g: GuiGraphics, x: Int, y0: Int, w: Int, h: Int) {
        if (picking) { sitePicker(g, x, y0, w, h); return }
        val routes = data.getList("routes", Tag.TAG_COMPOUND.toInt())
        if (crewFor >= routes.size) crewFor = -1
        if (crewFor >= 0) { crewPicker(g, x, y0, w, h, routes); return }
        val all = chests()
        text(g, Component.empty().append(tr("foremon.gui.routes", routes.size, data.getInt("maxLinks"))).append("  ")
            .append(tr("foremon.gui.route_reach", data.getInt("routeReach"))), x, y0, Pal.MUTED)
        button(g, x + w - 70, y0 - 3, 70, 12, tr("foremon.gui.route_add"), accent = true,
            tooltip = lines(tr("foremon.gui.route_add_tip"))) { picking = true; pickFrom = null }
        fun chestAt(pos: Long) = all.indexOfFirst { it.getLong("pos") == pos }
        fun nameOf(i: Int): Component = if (i <= 0) tr("foremon.gui.chest_main") else tr("foremon.gui.chest_extra", i)
        val rowH = 22
        val rows = ((h - 24) / rowH).coerceAtLeast(1)
        for (i in 0 until min(routes.size, rows)) {
            val r = routes.getCompound(i)
            val ry = y0 + 11 + i * rowH
            pokePanel(g, x, ry, w, rowH - 2, false)
            val a = chestAt(r.getLong("from")); val b = chestAt(r.getLong("to"))
            all.getOrNull(a)?.let { icon(g, MonIcons.ball(it.getString("ball")), x + 3, ry + 2) }
            g.drawString(font, "\u2192", x + 22, ry + 6, 0xFFFFFFFF.toInt(), true)
            all.getOrNull(b)?.let { icon(g, MonIcons.ball(it.getString("ball")), x + 31, ry + 2) }
            val bw = 36; val cw = 34
            val textW = w - 51 - bw - cw - 26
            clip(g, Component.empty().append(nameOf(a)).append(" \u2192 ").append(nameOf(b)), x + 51, ry + 2, textW, 0xFFFFFFFF.toInt())
            // What it carries is the filter of the chest it delivers to.
            val dest = all.getOrNull(b)
            val entries = dest?.getList("entries", Tag.TAG_STRING.toInt())?.size ?: 0
            val carries = if (dest == null || entries == 0) tr("foremon.gui.chest_takes_all")
                else tr(if (dest.getBoolean("only")) "foremon.gui.chest_only_n" else "foremon.gui.chest_except_n", entries)
            val crew = r.getList("crew", Tag.TAG_STRING.toInt()).size
            val line = carries.string + "  ${r.getInt("dist")}m" + (if (r.getBoolean("loaded")) "" else " \u00b7 " + tr("foremon.gui.route_unloaded").string) +
                (if (crew > 0) " \u00b7 " + tr("foremon.gui.route_crew_n", crew).string else "")
            clip(g, Component.literal(line), x + 51, ry + 11, textW, Pal.POKE_TEXT_SOFT)
            button(g, x + w - bw - cw - 22, ry + 2, cw, 16, tr("foremon.gui.route_crew"), tooltip = lines(tr("foremon.gui.route_crew_tip"))) { crewFor = i }
            button(g, x + w - bw - 20, ry + 2, bw, 16, tr("foremon.gui.tab_filter_short"), tooltip = lines(tr("foremon.gui.route_filter_tip"))) { filterChest = r.getLong("to"); tab = 1 }
            button(g, x + w - 18, ry + 2, 16, 16, Component.literal("x"), tooltip = lines(tr("foremon.gui.route_remove"))) { op("route_remove") { t -> t.putInt("index", i) } }
        }
        if (routes.size > rows) text(g, tr("foremon.gui.more", routes.size - rows), x, y0 + 11 + rows * rowH, Pal.MUTED)
        if (routes.isEmpty()) clip(g, muted(tr("foremon.status.make_routes")), x, y0 + 14, w, Pal.MUTED)
        clip(g, muted(tr("foremon.gui.routes.how")), x, y0 + h - 10, w, Pal.MUTED)
    }

    // ---- Guard: the map. Which boxes are shown (torches or patrol), what a drag draws, and the drag going on.
    private var layer = Zone.TORCHES
    private var brushAllow = false
    private var dragFrom: IntArray? = null
    private var map: IntArray? = null
    private var mapAt = 0
    private var mapR = -1
    // Where the map was drawn last frame, for the mouse: its corner on screen, cell size, size, cells across, world x/z of its corner.
    private var mapX = 0; private var mapY = 0; private var mapCell = 1; private var mapSize = 0; private var mapN = 0; private var mapWx = 0; private var mapWz = 0

    /** Guard: the work area seen from above, to draw boxes on: where torches go or never go, and where guards hunt. */
    private fun mapTab(g: GuiGraphics, x: Int, y0: Int, w: Int, h: Int) {
        val r = data.getInt("radius"); val n = 2 * r + 1
        val c = BlockPos.of(pos)
        val zones = Zone.unpack(data.getIntArray("zones"))
        val side = 92
        val size = min(w - side - 4, h - 12)
        if (size < 16) return
        val cell = (size / n).coerceAtLeast(1)
        mapX = x; mapY = y0; mapCell = cell; mapSize = min(size, n * cell); mapN = n; mapWx = c.x - r; mapWz = c.z - r
        // The ground changes slowly; looking it up again every two seconds is plenty.
        if (map == null || mapR != r || ticks - mapAt >= 40) { map = colors(c, r); mapR = r; mapAt = ticks }
        val colors = map!!
        g.fill(x - 1, y0 - 1, x + mapSize + 1, y0 + mapSize + 1, Pal.POKE_EDGE)
        g.enableScissor(x, y0, x + mapSize, y0 + mapSize)
        // One fill per run of the same colour along a row: flat ground is a handful of quads instead of one per block.
        for (j in 0 until n) {
            var i = 0
            while (i < n) {
                val col = colors[j * n + i]; var e = i + 1
                while (e < n && colors[j * n + e] == col) e++
                g.fill(x + i * cell, y0 + j * cell, x + e * cell, y0 + (j + 1) * cell, col)
                i = e
            }
        }
        fun box(x1: Int, z1: Int, x2: Int, z2: Int, fill: Int, edge: Int) {
            val sx1 = x + (minOf(x1, x2) - mapWx) * cell; val sz1 = y0 + (minOf(z1, z2) - mapWz) * cell
            val sx2 = x + (maxOf(x1, x2) - mapWx + 1) * cell; val sz2 = y0 + (maxOf(z1, z2) - mapWz + 1) * cell
            g.fill(sx1, sz1, sx2, sz2, fill); g.renderOutline(sx1, sz1, sx2 - sx1, sz2 - sz1, edge)
        }
        fun fillOf(allow: Boolean) = if (allow) 0x5550E070 else 0x66FF4040
        for (b in zones) if (b.layer == layer) box(b.x1, b.z1, b.x2, b.z2, fillOf(b.allow), if (b.allow) 0xFF50E070.toInt() else 0xFFFF4040.toInt())
        // The chest in the middle, and you, when you are on the map.
        box(c.x, c.z, c.x, c.z, 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        mc.player?.let { p -> if (kotlin.math.abs(p.blockX - c.x) <= r && kotlin.math.abs(p.blockZ - c.z) <= r) box(p.blockX, p.blockZ, p.blockX, p.blockZ, Pal.STAR, 0xFF000000.toInt()) }
        val hot = cellAt(mx.toDouble(), my.toDouble())
        dragFrom?.let { f -> val to = cellAt(mx.toDouble(), my.toDouble(), clamp = true)!!; box(f[0], f[1], to[0], to[1], fillOf(brushAllow), 0xFFFFFFFF.toInt()) }
        g.disableScissor()
        if (hot != null && dragFrom == null) tip = lines(Component.literal("${hot[0]}, ${hot[1]}"),
            tr(if (Zone.allows(zones, Zone.TORCHES, hot[0], hot[1])) "foremon.gui.map_torch_yes" else "foremon.gui.map_torch_no"),
            tr(if (Zone.allows(zones, Zone.PATROL, hot[0], hot[1])) "foremon.gui.map_patrol_yes" else "foremon.gui.map_patrol_no"))
        // Left: start a box (it is sent when the button is let go). Right: remove the box under the mouse.
        click(x, y0, mapSize, mapSize) { b ->
            val at = cellAt(mx.toDouble(), my.toDouble()) ?: return@click
            if (b == 0) dragFrom = at
            else zones.indexOfLast { it.layer == layer && it.has(at[0], at[1]) }.takeIf { it >= 0 }?.let { i -> op("zone_remove") { t -> t.putInt("index", i) } }
        }

        val sx = x + mapSize + 4; var sy = y0
        text(g, tr("foremon.gui.map_show"), sx, sy, Pal.MUTED); sy += 10
        button(g, sx, sy, side, 14, tr("foremon.gui.map_torches"), accent = layer == Zone.TORCHES, tooltip = lines(tr("foremon.gui.map_torches.hint"))) { layer = Zone.TORCHES }; sy += 16
        button(g, sx, sy, side, 14, tr("foremon.gui.map_patrol"), accent = layer == Zone.PATROL, tooltip = lines(tr("foremon.gui.map_patrol.hint"))) { layer = Zone.PATROL }; sy += 20
        text(g, tr("foremon.gui.map_draw"), sx, sy, Pal.MUTED); sy += 10
        button(g, sx, sy, side, 14, tr("foremon.gui.map_only"), accent = brushAllow, tooltip = lines(tr("foremon.gui.map_only.hint"))) { brushAllow = true }; sy += 16
        button(g, sx, sy, side, 14, tr("foremon.gui.map_keep_out"), accent = !brushAllow, tooltip = lines(tr("foremon.gui.map_keep_out.hint"))) { brushAllow = false }; sy += 20
        val mine = zones.count { it.layer == layer }
        button(g, sx, sy, side, 14, tr("foremon.gui.map_clear", mine), enabled = mine > 0) { op("zone_clear") { t -> t.putInt("layer", layer) } }
        clip(g, muted(tr("foremon.gui.map_how")), x, y0 + h - 10, w, Pal.MUTED)
    }

    /** The world x/z under screen point [x], [y] on the map, or null off the map (or the nearest edge, with [clamp]). */
    private fun cellAt(x: Double, y: Double, clamp: Boolean = false): IntArray? {
        val vis = min(mapN, mapSize / mapCell)
        var i = kotlin.math.floor((x - mapX) / mapCell).toInt(); var j = kotlin.math.floor((y - mapY) / mapCell).toInt()
        if (clamp) { i = i.coerceIn(0, vis - 1); j = j.coerceIn(0, vis - 1) } else if (i !in 0 until vis || j !in 0 until vis) return null
        return intArrayOf(mapWx + i, mapWz + j)
    }

    override fun mouseReleased(x: Double, y: Double, button: Int): Boolean {
        val from = dragFrom ?: return super.mouseReleased(x, y, button)
        dragFrom = null
        val to = cellAt(x, y, clamp = true) ?: return true
        op("zone_add") { t ->
            t.putInt("layer", layer); t.putBoolean("allow", brushAllow)
            t.putInt("x1", from[0]); t.putInt("z1", from[1]); t.putInt("x2", to[0]); t.putInt("z2", to[1])
        }
        return true
    }

    /** The work area's colours as on a map, shaded by height like one. Grey where this client has no chunk loaded. */
    private fun colors(c: BlockPos, r: Int): IntArray {
        val n = 2 * r + 1
        val out = IntArray(n * n) { 0xFF2A2F36.toInt() }
        val level = mc.level ?: return out
        if (level.dimension().location().toString() != data.getString("dim")) return out
        val m = BlockPos.MutableBlockPos()
        for (j in 0 until n) for (i in 0 until n) {
            val x = c.x - r + i; val z = c.z - r + j
            if (!level.hasChunk(x shr 4, z shr 4)) continue
            val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
            val north = if (level.hasChunk(x shr 4, (z - 1) shr 4)) level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1) - 1 else top
            val rgb = level.getBlockState(m.set(x, top, z)).getMapColor(level, m).col
            val f = if (top > north) 255 else if (top == north) 220 else 180
            out[j * n + i] = (0xFF shl 24) or ((rgb shr 16 and 0xFF) * f / 255 shl 16) or ((rgb shr 8 and 0xFF) * f / 255 shl 8) or ((rgb and 0xFF) * f / 255)
        }
        return out
    }

    /** Dispatch: pick which haulers work only route [crewFor]. Anyone not picked for any route works them all in turn. */
    private fun crewPicker(g: GuiGraphics, x: Int, y0: Int, w: Int, h: Int, routes: net.minecraft.nbt.ListTag) {
        text(g, tr("foremon.gui.crew_title", crewFor + 1), x, y0, Pal.STAR)
        button(g, x + w - 50, y0 - 3, 50, 12, tr("foremon.gui.done")) { crewFor = -1 }
        // Which route each hauler is on, if any.
        val on = HashMap<String, Int>()
        for (k in 0 until routes.size) routes.getCompound(k).getList("crew", Tag.TAG_STRING.toInt()).forEach { on[it.asString] = k }
        val workers = data.getList("workers", Tag.TAG_COMPOUND.toInt()).let { l -> (0 until l.size).map { l.getCompound(it) } }
        val rowH = 18
        val rows = ((h - 24) / rowH).coerceAtLeast(1)
        for ((i, m) in workers.take(rows).withIndex()) {
            val ry = y0 + 11 + i * rowH
            val id = m.getUUID("id")
            val route = on[id.toString()]
            val hot = hover(x, ry, w, rowH - 2)
            pokePanel(g, x, ry, w, rowH - 2, hot || route == crewFor)
            icon(g, MonIcons.of(m), x + 2, ry)
            val where = when (route) { crewFor -> tr("foremon.gui.crew_here"); null -> tr("foremon.gui.crew_any"); else -> tr("foremon.gui.crew_other", route + 1) }
            clip(g, Component.literal(m.getString("name") + "  ").append(where), x + 21, ry + 4, w - 24, if (route == crewFor) Pal.STAR else 0xFFFFFFFF.toInt())
            click(x, ry, w, rowH - 2) { op("route_crew") { t -> t.putInt("index", crewFor); t.putUUID("mon", id) } }
        }
        if (workers.isEmpty()) clip(g, muted(tr("foremon.gui.crew_none")), x, y0 + 14, w, Pal.MUTED)
        clip(g, muted(tr("foremon.gui.crew_how", data.getInt("dispatch"))), x, y0 + h - 10, w, Pal.MUTED)
    }

    /** Pick where a new route takes from, then where it delivers to: this depot, its chests, or (Remote Control) any job site. */
    private fun sitePicker(g: GuiGraphics, x: Int, y0: Int, w: Int, h: Int) {
        text(g, tr(if (pickFrom == null) "foremon.gui.route_pick_from" else "foremon.gui.route_pick_to"), x, y0, Pal.STAR)
        button(g, x + w - 50, y0 - 3, 50, 12, tr("foremon.gui.cancel")) { picking = false; pickFrom = null }
        val reach = data.getInt("routeReach")
        val remote = data.getBoolean("remote")
        class Opt(val p: Long, val icon: ItemStack, val name: Component, val dist: Int, val locked: Boolean)
        val sites = data.getList("sites", Tag.TAG_COMPOUND.toInt()).let { l -> (0 until l.size).map { l.getCompound(it) } }.sortedBy { it.getInt("dist") }
        val sitePos = sites.map { it.getLong("pos") }.toSet()
        val options = ArrayList<Opt>()
        // Where it delivers to can also be any chest out in the world: close the menu and sneak-click it with the whistle.
        if (pickFrom != null) options += Opt(PICK_IN_WORLD, ItemStack(dev.foremon.Foremon.WHISTLE), tr("foremon.gui.route_pick_world"), -1, false)
        options += Opt(pos, ItemStack(JOB_ICONS[Job.HAULING] ?: Items.PAPER), tr("foremon.gui.route_this_depot"), -1, false)
        for ((k, c) in chests().withIndex()) if (k > 0 && c.getLong("pos") !in sitePos)
            options += Opt(c.getLong("pos"), MonIcons.ball(c.getString("ball")), tr("foremon.gui.chest_extra", k), c.getInt("dist"), false)
        for (st in sites) options += Opt(st.getLong("pos"), ItemStack(JOB_ICONS[Job.of(st.getString("job")) ?: Job.LUMBER] ?: Items.PAPER),
            tr("foremon.gui.route_site", tr("foremon.job.${st.getString("job")}")), st.getInt("dist"), !remote)
        val rowH = 18
        val rows = ((h - 22) / rowH).coerceAtLeast(1)
        for ((i, o) in options.take(rows).withIndex()) {
            val ry = y0 + 11 + i * rowH
            val tooFar = o.dist > reach
            val picked = o.p == pickFrom
            val off = tooFar || o.locked || picked
            val hot = !off && hover(x, ry, w, rowH - 2)
            pokePanel(g, x, ry, w, rowH - 2, hot)
            icon(g, o.icon, x + 2, ry)
            val bp = BlockPos.of(o.p)
            val where = if (o.dist < 0) "" else "  ${o.dist}m  (${bp.x} ${bp.y} ${bp.z})"
            clip(g, Component.empty().append(o.name).append(where), x + 21, ry + 4, w - 24, if (off) Pal.MUTED else if (o.p == PICK_IN_WORLD) Pal.STAR else 0xFFFFFFFF.toInt())
            if (hover(x, ry, w, rowH - 2)) when {
                o.p == PICK_IN_WORLD -> tip = lines(tr("foremon.gui.route_pick_world"), muted(tr("foremon.gui.route_pick_world_tip")))
                o.locked -> tip = lines(tr("foremon.gui.route_needs_remote"))
                tooFar -> tip = lines(tr("foremon.fail.route_far", reach))
            }
            if (!off) click(x, ry, w, rowH - 2) {
                val from = pickFrom
                if (from == null) pickFrom = o.p
                else if (o.p == PICK_IN_WORLD) { op("route_start") { t -> t.putLong("from", from) }; picking = false; pickFrom = null; onClose() }
                else { op("route_add") { t -> t.putLong("from", from); t.putLong("to", o.p) }; picking = false; pickFrom = null }
            }
        }
        if (options.size > rows) text(g, tr("foremon.gui.more", options.size - rows), x, y0 + 11 + rows * rowH, Pal.MUTED)
    }

    /** A PC box, drawn like the storage system in the games: pick any Pokemon in it to put it to work. */
    private fun pcTab(g: GuiGraphics, x: Int, y0: Int, w: Int, h: Int) {
        val d = pc
        if (d == null) {
            if (pcAsked != pcBox) { pcAsked = pcBox; op("pc") { it.putInt("box", pcBox) } }
            text(g, tr("foremon.gui.loading"), x, y0 + 4, Pal.MUTED)
            return
        }
        val boxes = d.getInt("boxes")
        if (boxes == 0) { text(g, tr("foremon.gui.not_yours", data.getString("owner")), x, y0 + 4, Pal.MUTED); return }
        val box = d.getInt("box")

        g.fill(x, y0, x + w, y0 + 18, Pal.POKE_EDGE)
        g.fill(x + 1, y0 + 1, x + w - 1, y0 + 17, Pal.BOX_HEADER)
        g.fill(x + 1, y0 + 1, x + w - 1, y0 + 6, Pal.BOX_HEADER_HI)
        button(g, x + 2, y0 + 2, 14, 14, Component.literal("<")) { changeBox(-1, boxes) }
        button(g, x + w - 16, y0 + 2, 14, 14, Component.literal(">")) { changeBox(1, boxes) }
        val title = d.getString("name").ifEmpty { tr("foremon.gui.pc_box", box + 1).string } + "   ${box + 1}/$boxes"
        g.drawString(font, title, x + (w - font.width(title)) / 2, y0 + 5, 0xFFFFFFFF.toInt(), true)

        val cell = min(24, min((w - 4) / 6, (h - 36) / 5))
        val gx = x + (w - cell * 6) / 2; val gy = y0 + 22
        g.fill(gx - 2, gy - 2, gx + cell * 6 + 2, gy + cell * 5 + 2, Pal.POKE_EDGE)
        val slots = d.getList("slots", Tag.TAG_COMPOUND.toInt())
        for (i in 0 until 30) {
            val sx = gx + (i % 6) * cell; val sy = gy + (i / 6) * cell
            g.fill(sx, sy, sx + cell, sy + cell, if ((i % 6 + i / 6) % 2 == 0) Pal.BOX_A else Pal.BOX_B)
            val m = slots.getCompound(i)
            if (!m.contains("species")) continue
            val s = m.getIntArray("stars").getOrElse(job.ordinal) { 0 }
            val busy = m.getBoolean("busy"); val fainted = m.getBoolean("fainted")
            icon(g, MonIcons.of(m), sx + (cell - 16) / 2, sy + (cell - 16) / 2)
            // Greyed out: already working, fainted, or not able to do this job. Gold edge: four stars or more.
            if (busy || fainted || s == 0) g.fill(sx, sy, sx + cell, sy + cell, 0xA0203040.toInt())
            else if (s >= 4) g.renderOutline(sx, sy, cell, cell, Pal.STAR)
            if (hover(sx, sy, cell, cell)) {
                g.renderOutline(sx, sy, cell, cell, 0xFFFFFFFF.toInt())
                tip = monTip(m, when { busy -> "foremon.gui.pc_busy"; fainted -> "foremon.fail.fainted_short"; else -> "foremon.gui.click_assign" })
            }
            click(sx, sy, cell, cell) { op("assign") { t -> t.putUUID("pokemon", m.getUUID("id")) } }
        }
        clip(g, muted(tr("foremon.gui.pc_hint")), x, gy + cell * 5 + 5, w, Pal.MUTED)
    }

    private fun entryIcon(entry: String): ItemStack {
        presets.firstOrNull { it.first == entry }?.let { return ItemStack(it.second) }
        return if (entry.startsWith("#")) ItemStack(Items.NAME_TAG) else MonIcons.item(entry)
    }

    private fun entryName(entry: String): Component =
        if (entry.startsWith("#")) tr("foremon.filter.tag", entry.substring(1)) else MonIcons.item(entry).hoverName

    /** A filter action for the chest being edited. */
    private fun fop(name: String, fill: (CompoundTag) -> Unit = {}) = op(name) { t -> selectedChest()?.let { t.putLong("chest", it.getLong("pos")) }; fill(t) }

    private fun filter(g: GuiGraphics, x: Int, y0: Int, w: Int) {
        val chest = selectedChest() ?: return
        val entries = chest.getList("entries", Tag.TAG_STRING.toInt()).map { it.asString }
        val only = chest.getBoolean("only")
        // Which chest this filter belongs to: its ball and name. Click to step through the site's chests.
        val all = chests()
        val idx = all.indexOfFirst { it.getLong("pos") == chest.getLong("pos") }
        val hot = hover(x, y0, w, 18)
        pokePanel(g, x, y0, w, 18, hot)
        icon(g, MonIcons.ball(chest.getString("ball")), x + 2, y0 + 1)
        val label = if (chest.getBoolean("main")) tr("foremon.gui.chest_main") else tr("foremon.gui.chest_extra", idx)
        g.drawString(font, label.string + if (all.size > 1) "   ${idx + 1}/${all.size}" else "", x + 21, y0 + 5, 0xFFFFFFFF.toInt(), true)
        if (hot && all.size > 1) tip = lines(tr("foremon.gui.filter_which"))
        if (all.size > 1) click(x, y0, w, 18) { filterChest = all[(idx + 1) % all.size].getLong("pos") }
        val by = y0 + 21
        button(g, x, by, w - 44, 16, tr(if (only) "foremon.gui.filter_only" else "foremon.gui.filter_except"), tooltip = lines(tr("foremon.gui.filter_mode.hint"))) { fop("filter_mode") }
        button(g, x + w - 42, by, 42, 16, tr("foremon.gui.clear"), enabled = entries.isNotEmpty()) { fop("filter_clear") }

        var y = by + 20
        text(g, tr("foremon.gui.filter_entries", entries.size, data.getInt("maxFilter")), x, y, Pal.MUTED); y += 10
        val perRow = (w / 18).coerceAtLeast(1)
        for ((i, e) in entries.withIndex()) {
            val ix = x + (i % perRow) * 18; val iy = y + (i / perRow) * 18
            if (hover(ix, iy, 18, 18)) { g.fill(ix, iy, ix + 18, iy + 18, Pal.CARD_HOVER); tip = lines(entryName(e), muted(tr("foremon.gui.click_remove"))) }
            icon(g, entryIcon(e), ix + 1, iy + 1)
            click(ix, iy, 18, 18) { fop("filter_remove") { t -> t.putString("entry", e) } }
        }
        y += ((entries.size + perRow - 1) / perRow).coerceAtLeast(1) * 18 + 4

        text(g, tr("foremon.gui.presets"), x, y, Pal.MUTED); y += 10
        for ((i, p) in presets.withIndex()) {
            val ix = x + i * 18
            val has = p.first in entries
            if (has) g.fill(ix, y, ix + 18, y + 18, Pal.ACCENT)
            else if (hover(ix, y, 18, 18)) g.fill(ix, y, ix + 18, y + 18, Pal.CARD_HOVER)
            if (hover(ix, y, 18, 18)) tip = lines(entryName(p.first))
            icon(g, ItemStack(p.second), ix + 1, y + 1)
            click(ix, y, 18, 18) { fop(if (has) "filter_remove" else "filter_add") { t -> t.putString("entry", p.first) } }
        }
        y += 22

        // ---- the player's own inventory: click anything in it to add it to the filter
        text(g, tr("foremon.gui.from_inventory"), x, y, Pal.MUTED); y += 10
        val inv = mc.player?.inventory ?: return
        val seen = HashSet<String>()
        var i = 0
        for (slot in 0 until 36) {
            val st = inv.getItem(slot)
            if (st.isEmpty) continue
            val id = BuiltInRegistries.ITEM.getKey(st.item).toString()
            if (!seen.add(id)) continue
            val ix = x + (i % perRow) * 18; val iy = y + (i / perRow) * 18
            if (iy + 18 > height - 20) break
            val has = id in entries
            if (has) g.fill(ix, iy, ix + 18, iy + 18, Pal.ACCENT)
            else if (hover(ix, iy, 18, 18)) g.fill(ix, iy, ix + 18, iy + 18, Pal.CARD_HOVER)
            if (hover(ix, iy, 18, 18)) tip = lines(st.hoverName, muted(tr(if (has) "foremon.gui.click_remove" else "foremon.gui.click_add")))
            icon(g, st, ix + 1, iy + 1)
            click(ix, iy, 18, 18) { fop(if (has) "filter_remove" else "filter_add") { t -> t.putString("entry", id) } }
            i++
        }
    }
}
