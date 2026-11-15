package dev.foremon.client

import dev.foremon.rules.Job
import dev.foremon.world.Zone
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs
import kotlin.math.min

private val BALLS_CLIENT = dev.foremon.world.BALLS
/** Route picker row: "a chest in the world", picked with a sneak-click after the menu closes. */
private const val PICK_IN_WORLD = Long.MIN_VALUE
private const val P_CREW = 0
private const val P_FILTER = 1
private const val P_PC = 2
private const val P_CHESTS = 3
private const val P_JOBS = 4

/** The menu of one job site, drawn as a journal: bookmarks down the left pick the page, each page uses both sides. */
class SiteScreen(var data: CompoundTag) : ForemonScreen(tr("foremon.gui.site")) {
    private var tab = P_CREW
    private var confirmRemove = 0L
    private var ticks = 0
    /** Routes page: picking where a new route goes from and to; the first one picked, once picked. */
    private var picking = false
    private var pickFrom: Long? = null
    private var crewFor = -1
    private var hoverJob: Job? = null

    /** While the site runs, ask for fresh numbers every half second so the load and stamina bars move. */
    override fun tick() {
        super.tick()
        // Not on the PC page: each update fetches the PC box again.
        if (++ticks % 10 == 0 && data.getBoolean("active") && tab != P_PC) op("refresh")
    }

    /** The chest whose filter the Filter page edits: the site's own chest unless one was picked in the Chests page. */
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
        if (tab == P_PC) { pc = null; pcAsked = -1 }
    }

    private fun changeBox(delta: Int, boxes: Int) {
        pcBox = (pcBox + delta).mod(boxes.coerceAtLeast(1))
        pc = null; pcAsked = -1
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        if (tab != P_PC) return super.mouseScrolled(x, y, dx, dy)
        pc?.let { changeBox(if (dy < 0) 1 else -1, it.getInt("boxes")) }
        return true
    }

    override fun keyPressed(key: Int, scan: Int, mods: Int): Boolean {
        // Escape on the big map goes back to the book instead of closing it.
        if (bigMap && key == 256) { bigMap = false; return true }
        return super.keyPressed(key, scan, mods)
    }

    private val pos get() = data.getLong("pos")
    private val job get() = Job.of(data.getString("job")) ?: Job.LUMBER

    private fun op(name: String, fill: (CompoundTag) -> Unit = {}) = send(name) { it.putLong("pos", pos); it.putString("dim", data.getString("dim")); fill(it) }

    private fun open(page: Int) {
        tab = page; picking = false; pickFrom = null; crewFor = -1
        if (page == P_PC) { pc = null; pcAsked = -1 }
    }

    private val presets = listOf(
        "#c:ores" to Items.IRON_ORE, "#minecraft:logs" to Items.OAK_LOG, "#c:stones" to Items.STONE, "#minecraft:dirt" to Items.DIRT,
        "#minecraft:crops" to Items.WHEAT, "#c:ingots" to Items.IRON_INGOT, "#c:raw_materials" to Items.RAW_IRON, "#c:gems" to Items.DIAMOND,
    )

    override fun draw(g: GuiGraphics) {
        val guard = job == Job.GUARD
        if (bigMap && guard) { bigMapView(g); return }
        val b = layout()
        book(g, b)
        val hauling = job == Job.HAULING
        val routes = data.getList("routes", Tag.TAG_COMPOUND.toInt()).size
        val third = when {
            hauling -> ItemStack(Items.CHEST_MINECART) to tr("foremon.gui.tab_routes", routes)
            guard -> ItemStack(Items.FILLED_MAP) to tr("foremon.gui.tab_map")
            else -> ItemStack(Items.CHEST) to tr("foremon.gui.tab_chests", chests().size)
        }
        val marks = listOf(
            Triple(P_CREW, MonIcons.ball("poke_ball"), tr("foremon.gui.tab_crew")),
            Triple(P_JOBS, ItemStack(JOB_ICONS[job] ?: Items.PAPER), tr("foremon.gui.tab_jobs")),
            Triple(P_PC, MonIcons.item("cobblemon:pc"), tr("foremon.gui.tab_pc")),
            Triple(P_CHESTS, third.first, third.second),
            Triple(P_FILTER, ItemStack(Items.HOPPER), tr("foremon.gui.tab_filter_short")),
        )
        for ((i, m) in marks.withIndex()) bookmark(g, b, i, m.second, tab == m.first, m.third) { open(m.first) }
        bookmark(g, b, 0, ItemStack(Items.EXPERIENCE_BOTTLE), false, tr("foremon.gui.skills"), left = false) { send("skills") }
        bookmark(g, b, 1, ItemStack(Items.WRITABLE_BOOK), false, tr("foremon.gui.board"), left = false) { send("board") }
        when (tab) {
            P_CREW -> crewPage(g, b)
            P_JOBS -> jobsPage(g, b)
            P_PC -> pcPage(g, b)
            P_CHESTS -> if (hauling) routesPage(g, b) else if (guard) mapPage(g, b) else chestsPage(g, b)
            else -> filterPage(g, b)
        }
    }

    // ------------------------------------------------------------ crew

    private fun crewPage(g: GuiGraphics, b: Book) {
        ribbon(g, b, tr("foremon.gui.site_title", tr("foremon.job.${job.key}")), tr("foremon.gui.xp", data.getInt("xp")))
        var y = b.top
        val status = data.getString("status")
        val active = data.getBoolean("active")
        val line = if (status.isNotEmpty()) trArg(status, data.getString("statusArg")) else tr(if (active) "foremon.gui.running" else "foremon.gui.paused")
        y += para(g, line, b.lx, y, b.pw, if (status.isNotEmpty()) Pal.WARN else if (active) Pal.GOOD else Pal.MUTED, 2) + 4
        button(g, b.lx, y, b.pw, 18, tr(if (active) "foremon.gui.pause" else "foremon.gui.start"), accent = !active) { op("toggle") }
        y += 24
        text(g, tr("foremon.gui.area", data.getInt("radius") * 2, data.getInt("vertical")), b.lx, y, Pal.MUTED); y += 12
        // Extra chests: more storage for the site (or the destinations, for Sorting and Hauling).
        val links = data.getInt("links")
        val logistics = job == Job.SORTING || job == Job.HAULING
        text(g, tr("foremon.gui.links", links, data.getInt("maxLinks")), b.lx, y + 3, if (links == 0 && logistics) Pal.WARN else Pal.INK)
        if (hover(b.lx, y, b.pw - 18, 14)) tip = lines(tr("foremon.gui.links.title"), muted(tr(if (logistics) "foremon.gui.links.hint_logistics" else "foremon.gui.links.hint")), muted(tr("foremon.gui.links.how")))
        button(g, b.lx + b.pw - 14, y, 14, 14, Component.literal("x"), enabled = links > 0, tooltip = lines(tr("foremon.gui.clear_links"))) { op("clear_links") }
        y += 18
        // What this site is asking haulers for.
        val reqs = data.getList("requests", Tag.TAG_COMPOUND.toInt())
        if (reqs.size > 0) {
            text(g, tr("foremon.gui.asking_for"), b.lx, y, Pal.WARN)
            if (hover(b.lx, y, b.pw, 9)) tip = lines(tr("foremon.gui.asking_for"), muted(tr("foremon.gui.asking_for.hint")))
            requestIcons(g, reqs, b.lx, y + 10, b.pw); y += 30
        }
        when (job) {
            Job.MINING, Job.DEMOLITION -> {
                if (data.contains("lo") && data.getBoolean("areaDone")) {
                    val lo = BlockPos.of(data.getLong("lo")); val hi = BlockPos.of(data.getLong("hi"))
                    text(g, tr("foremon.gui.marked", hi.x - lo.x + 1, hi.y - lo.y + 1, hi.z - lo.z + 1), b.lx, y, Pal.INK)
                } else text(g, tr(if (data.contains("lo")) "foremon.gui.mark_second" else "foremon.gui.mark_first"), b.lx, y, Pal.WARN)
                y += 12
                button(g, b.lx, y, b.pw, 16, tr("foremon.gui.clear_area"), enabled = data.contains("lo")) { op("clear_area") }
            }
            Job.BUILDING -> {
                if (data.contains("plan")) {
                    val p = data.getCompound("plan")
                    clip(g, Component.literal(p.getString("name")), b.lx, y, b.pw, Pal.NAME); y += 11
                    bar(g, b.lx, y, b.pw, 5, p.getInt("index").toDouble() / p.getInt("size").coerceAtLeast(1), Pal.GOOD); y += 8
                    text(g, tr("foremon.gui.plan_progress", p.getInt("placed"), p.getInt("size")), b.lx, y, Pal.MUTED); y += 12
                } else { text(g, tr("foremon.gui.no_plan"), b.lx, y, Pal.WARN); y += 12 }
                val half = (b.pw - 4) / 2
                val lite = LitematicaCompat.available
                button(g, b.lx, y, half, 16, tr("foremon.gui.load_litematica"), enabled = lite,
                    tooltip = lines(tr(if (lite) "foremon.gui.load_litematica.hint" else "foremon.gui.load_litematica.missing"))) {
                    LitematicaCompat.upload(BlockPos.of(pos))?.let { err -> mc.player?.displayClientMessage(tr(err), true) }
                }
                button(g, b.lx + half + 4, y, half, 16, tr("foremon.gui.clear_plan"), enabled = data.contains("plan")) { op("plan_clear") }
            }
            else -> {}
        }
        // Bottom of the left page: what this job needs, then restart and remove.
        val by = b.bottom - 18
        para(g, muted(tr("foremon.gui.hint.${job.key}")), b.lx, by - 22, b.pw, Pal.MUTED, 2)
        val half = (b.pw - 4) / 2
        button(g, b.lx, by, half, 16, tr("foremon.gui.restart"), tooltip = lines(tr("foremon.gui.restart.hint"))) { op("restart") }
        val armed = System.currentTimeMillis() - confirmRemove < 3000
        button(g, b.lx + half + 4, by, half, 16, tr(if (armed) "foremon.gui.remove_confirm" else "foremon.gui.remove"), danger = armed) {
            if (armed) op("remove") else confirmRemove = System.currentTimeMillis()
        }

        // ---- right page: the crew, then your party to pick from
        val workers = data.getList("workers", Tag.TAG_COMPOUND.toInt())
        heading(g, tr("foremon.gui.crew", workers.size, data.getInt("maxWorkers")), b.rx + b.pw / 2, b.rtop + 2)
        val upkeep = data.getBoolean("upkeep")
        for (i in 0 until workers.size) {
            val m = workers.getCompound(i)
            val ry = b.rtop + 14 + i * 24
            if (ry + 22 > b.bottom - 46) break
            val hot = hover(b.rx, ry, b.pw, 22)
            row(g, b.rx, ry, b.pw, 22, hot)
            icon(g, MonIcons.of(m), b.rx + 2, ry + 1, 1.25f)
            val lv = tr("foremon.gui.perk_lv", m.getInt("level")).string
            clip(g, Component.literal(m.getString("name")), b.rx + 25, ry + 3, b.pw - 32 - font.width(lv), Pal.NAME)
            text(g, lv, b.rx + b.pw - 4 - font.width(lv), ry + 3, Pal.MUTED)
            stars(g, b.rx + 25, ry + 12, m.getIntArray("stars").getOrElse(job.ordinal) { 0 })
            val bx = b.rx + 64; val bw = b.pw - 68
            if (m.getBoolean("resting")) text(g, tr("foremon.gui.resting"), bx, ry + 12, Pal.WARN)
            else if (upkeep) bar(g, bx, ry + 14, bw, 3, m.getDouble("stamina") / m.getDouble("maxStamina").coerceAtLeast(1.0), Pal.GOOD)
            // Haulers: the load they are working on, filling up as it goes.
            if (job == Job.HAULING) bar(g, bx, ry + 18, bw, 2, m.getFloat("progress").toDouble(), Pal.LOAD)
            if (hot) tip = monTip(m, "foremon.gui.click_unassign")
            click(b.rx, ry, b.pw, 22) { op("unassign") { t -> t.putUUID("pokemon", m.getUUID("id")) } }
        }
        if (workers.isEmpty()) para(g, muted(tr("foremon.gui.crew_empty")), b.rx, b.rtop + 16, b.pw, Pal.MUTED)
        val party = data.getList("party", Tag.TAG_COMPOUND.toInt())
        val py = b.bottom - 32
        text(g, tr("foremon.gui.party_add"), b.rx, py - 11, Pal.MUTED)
        for (i in 0 until party.size) {
            val m = party.getCompound(i)
            val px = b.rx + i * 30
            val hot = hover(px, py, 28, 32)
            val s = m.getIntArray("stars").getOrElse(job.ordinal) { 0 }
            row(g, px, py, 28, 32, hot)
            icon(g, MonIcons.of(m), px + 2, py + 1, 1.5f)
            val label = if (s == 0) "-" else "★$s"
            text(g, label, px + (28 - font.width(label)) / 2, py + 23, if (s >= 4) Pal.STAR else if (s == 0) Pal.BAD else Pal.MUTED)
            if (hot) tip = monTip(m, if (m.getBoolean("fainted")) "foremon.fail.fainted_short" else "foremon.gui.click_assign")
            click(px, py, 28, 32) { op("assign") { t -> t.putUUID("pokemon", m.getUUID("id")) } }
        }
        if (party.isEmpty() && !data.getBoolean("mine")) text(g, tr("foremon.gui.not_yours", data.getString("owner")), b.rx, py + 10, Pal.MUTED)
    }

    // ------------------------------------------------------------ jobs

    private fun jobsPage(g: GuiGraphics, b: Book) {
        ribbon(g, b, tr("foremon.gui.tab_jobs"))
        hoverJob = null
        // Rows shrink a little on small screens so every job fits.
        val rh = min(20, (b.bottom - b.top) / Job.entries.size)
        for ((i, j) in Job.entries.withIndex()) {
            val y = b.top + i * rh
            val hot = hover(b.lx, y, b.pw, rh - 2)
            if (hot) hoverJob = j
            row(g, b.lx, y, b.pw, rh - 2, hot, selected = j == job)
            icon(g, ItemStack(JOB_ICONS[j] ?: Items.PAPER), b.lx + 2, y + (rh - 18) / 2)
            text(g, tr("foremon.job.${j.key}"), b.lx + 22, y + (rh - 10) / 2, Pal.INK)
            if (j == job) text(g, "✔", b.lx + b.pw - 10, y + (rh - 10) / 2, Pal.GOOD)
            click(b.lx, y, b.pw, rh - 2) { op("job") { t -> t.putString("job", j.key) } }
        }
        // Right page: the job under the mouse (or the current one), and who in your party is best at it.
        val shown = hoverJob ?: job
        icon(g, ItemStack(JOB_ICONS[shown] ?: Items.PAPER), b.rx + b.pw / 2 - 8, b.rtop)
        heading(g, tr("foremon.job.${shown.key}"), b.rx + b.pw / 2, b.rtop + 19)
        var y = b.rtop + 32
        y += para(g, tr("foremon.job.${shown.key}.hint"), b.rx, y, b.pw, Pal.INK, 6) + 6
        text(g, tr("foremon.gui.jobs_best"), b.rx, y, Pal.MUTED); y += 11
        val party = data.getList("party", Tag.TAG_COMPOUND.toInt()).let { l -> (0 until l.size).map { l.getCompound(it) } }
            .sortedByDescending { it.getIntArray("stars").getOrElse(shown.ordinal) { 0 } }
        for (m in party) {
            if (y + 17 > b.bottom) break
            val s = m.getIntArray("stars").getOrElse(shown.ordinal) { 0 }
            icon(g, MonIcons.of(m), b.rx, y)
            clip(g, Component.literal(m.getString("name")), b.rx + 18, y + 4, b.pw - 58, Pal.NAME)
            if (s == 0) text(g, "-", b.rx + b.pw - 36, y + 4, Pal.BAD) else stars(g, b.rx + b.pw - 36, y + 4, s)
            y += 18
        }
    }

    // ------------------------------------------------------------ PC box

    private fun pcPage(g: GuiGraphics, b: Book) {
        ribbon(g, b, tr("foremon.gui.tab_pc"))
        val d = pc
        if (d == null) {
            if (pcAsked != pcBox) { pcAsked = pcBox; op("pc") { it.putInt("box", pcBox) } }
            text(g, tr("foremon.gui.loading"), b.lx, b.top + 4, Pal.MUTED)
            return
        }
        val boxes = d.getInt("boxes")
        if (boxes == 0) { text(g, tr("foremon.gui.not_yours", data.getString("owner")), b.lx, b.top + 4, Pal.MUTED); return }
        val box = d.getInt("box")
        button(g, b.lx, b.top, 16, 16, Component.literal("<")) { changeBox(-1, boxes) }
        button(g, b.lx + b.pw - 16, b.top, 16, 16, Component.literal(">")) { changeBox(1, boxes) }
        val title = d.getString("name").ifEmpty { tr("foremon.gui.pc_box", box + 1).string } + "   ${box + 1}/$boxes"
        heading(g, Component.literal(title), b.lx + b.pw / 2, b.top + 4)

        val cell = min(26, b.pw / 6)
        val gx = b.lx + (b.pw - cell * 6) / 2; val gy = b.top + 22
        g.fill(gx - 1, gy - 1, gx + cell * 6 + 1, gy + cell * 5 + 1, Pal.ROW_EDGE)
        val slots = d.getList("slots", Tag.TAG_COMPOUND.toInt())
        var hovered: CompoundTag? = null
        var hoveredAction = ""
        for (i in 0 until 30) {
            val sx = gx + (i % 6) * cell; val sy = gy + (i / 6) * cell
            g.fill(sx, sy, sx + cell, sy + cell, if ((i % 6 + i / 6) % 2 == 0) Pal.ROW else Pal.PAGE)
            val m = slots.getCompound(i)
            if (!m.contains("species")) continue
            val s = m.getIntArray("stars").getOrElse(job.ordinal) { 0 }
            val busy = m.getBoolean("busy"); val fainted = m.getBoolean("fainted")
            icon(g, MonIcons.of(m), sx + (cell - 16) / 2, sy + (cell - 16) / 2)
            // Faded: already working, fainted, or not able to do this job. Gold edge: four stars or more.
            if (busy || fainted || s == 0) g.fill(sx, sy, sx + cell, sy + cell, 0xA0D8CDB0.toInt())
            else if (s >= 4) g.renderOutline(sx, sy, cell, cell, Pal.STAR)
            if (hover(sx, sy, cell, cell)) {
                g.renderOutline(sx, sy, cell, cell, Pal.INK)
                hovered = m
                hoveredAction = when { busy -> "foremon.gui.pc_busy"; fainted -> "foremon.fail.fainted_short"; else -> "foremon.gui.click_assign" }
            }
            click(sx, sy, cell, cell) { op("assign") { t -> t.putUUID("pokemon", m.getUUID("id")) } }
        }
        para(g, muted(tr("foremon.gui.pc_hint")), b.lx, gy + cell * 5 + 5, b.pw, Pal.MUTED, 3)
        // Right page: everything about the Pokemon under the mouse, written out instead of in a tooltip.
        val m = hovered
        if (m == null) { para(g, tr("foremon.gui.pc_pick"), b.rx, b.rtop + 4, b.pw, Pal.MUTED); return }
        monPage(g, m, b.rx, b.rtop, b.pw, b.bottom, hoveredAction)
    }

    /** A Pokemon written out on a page: name, rating for this job and why, mastery, style and perks. */
    private fun monPage(g: GuiGraphics, m: CompoundTag, x: Int, y0: Int, w: Int, bottom: Int, action: String) {
        icon(g, MonIcons.of(m), x, y0, 2f)
        text(g, Component.literal(m.getString("name")), x + 36, y0 + 4, Pal.NAME)
        text(g, tr("foremon.gui.level", m.getInt("level")), x + 36, y0 + 14, Pal.MUTED)
        val s = m.getIntArray("stars").getOrElse(job.ordinal) { 0 }
        if (s > 0) stars(g, x + 36, y0 + 24, s)
        var y = y0 + 38
        fun line(c: Component, color: Int) { if (y + 9 <= bottom) { clip(g, c, x, y, w, color); y += 10 } }
        if (s == 0) line(tr("foremon.gui.cannot", tr("foremon.job.${job.key}")), Pal.BAD)
        else {
            line(tr("foremon.gui.speed", "%.2f".format(m.getDouble("mult"))), Pal.STAR)
            m.getList("factors", Tag.TAG_STRING.toInt()).forEach { f ->
                val (id, mult) = f.asString.split('=').let { it[0] to it.getOrElse(1) { "1" } }
                val kind = id.substringBefore(':'); val detail = id.substringAfter(':', "")
                line(Component.literal("x$mult ").append(tr("foremon.factor.$kind", detail.replace('+', '/'))), if ((mult.toDoubleOrNull() ?: 1.0) >= 1.0) Pal.GOOD else Pal.BAD)
            }
        }
        line(tr("foremon.gui.mastery", tr("foremon.mastery.${m.getIntArray("mastery").getOrElse(job.ordinal) { 0 }}")), Pal.NAME)
        line(tr("foremon.gui.style", tr("foremon.style.${m.getString("style")}"), m.getInt("carry")), Pal.MUTED)
        m.getList("unlocks", Tag.TAG_STRING.toInt()).forEach { u ->
            val parts = u.asString.split('=')
            line(unlockText(parts[0], parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0), Pal.GOOD)
        }
        if (bottom - y >= 10) clip(g, tr(action), x, bottom - 9, w, Pal.WARN)
    }

    private fun factorLine(f: String): Component {
        val (id, mult) = f.split('=').let { it[0] to it.getOrElse(1) { "1" } }
        val kind = id.substringBefore(':'); val detail = id.substringAfter(':', "")
        val value = mult.toDoubleOrNull() ?: 1.0
        val c = Component.literal("x$mult ").withStyle(if (value >= 1.0) ChatFormatting.GREEN else ChatFormatting.RED)
        return c.append(tr("foremon.factor.$kind", detail.replace('+', '/')).withStyle(ChatFormatting.GRAY))
    }

    /** Tooltip version, for the dark tooltip box. */
    private fun monTip(m: CompoundTag, action: String): List<Component> {
        val out = ArrayList<Component>()
        out.add(Component.literal("${m.getString("name")}  ").append(tr("foremon.gui.level", m.getInt("level")).withStyle(ChatFormatting.GRAY)))
        val s = m.getIntArray("stars").getOrElse(job.ordinal) { 0 }
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

    // ------------------------------------------------------------ chests

    /** Every chest of the site, each with its Poke Ball, its filter and where it is. */
    private fun chestsPage(g: GuiGraphics, b: Book) {
        val all = chests()
        ribbon(g, b, tr("foremon.gui.tab_chests", all.size))
        val rowH = 30
        val rows = ((b.bottom - b.top) / rowH).coerceAtLeast(1)
        for ((i, c) in all.take(rows).withIndex()) {
            val ry = b.top + i * rowH
            val cpos = BlockPos.of(c.getLong("pos"))
            val main = c.getBoolean("main")
            row(g, b.lx, ry, b.pw, rowH - 3)
            // The ball: click for the next one, right-click for the one before.
            val ball = c.getString("ball")
            val bi = BALLS_CLIENT.indexOf(ball).coerceAtLeast(0)
            if (hover(b.lx + 2, ry + 2, 18, 18)) { g.fill(b.lx + 2, ry + 2, b.lx + 20, ry + 20, 0x40000000); tip = lines(ballName(ball), muted(tr("foremon.gui.ball_hint"))) }
            icon(g, MonIcons.ball(ball), b.lx + 3, ry + 3)
            click(b.lx + 2, ry + 2, 18, 18) { btn ->
                val next = BALLS_CLIENT[(bi + if (btn == 1) BALLS_CLIENT.size - 1 else 1) % BALLS_CLIENT.size]
                op("chest_ball") { t -> t.putLong("chest", c.getLong("pos")); t.putString("ball", next) }
            }
            val name = if (main) tr("foremon.gui.chest_main") else tr("foremon.gui.chest_extra", i)
            text(g, name, b.lx + 23, ry + 3, Pal.INK)
            val entries = c.getList("entries", Tag.TAG_STRING.toInt()).size
            val summary = if (!c.getBoolean("present")) tr("foremon.gui.chest_missing")
                else if (entries == 0) tr("foremon.gui.chest_takes_all")
                else tr(if (c.getBoolean("only")) "foremon.gui.chest_only_n" else "foremon.gui.chest_except_n", entries)
            val textW = b.pw - 23 - (if (main) 40 else 58)
            clip(g, Component.literal(summary.string + "  ${cpos.x} ${cpos.y} ${cpos.z}"), b.lx + 23, ry + 14, textW, Pal.MUTED)
            button(g, b.lx + b.pw - (if (main) 38 else 56), ry + 5, 36, 16, tr("foremon.gui.tab_filter_short")) { filterChest = c.getLong("pos"); tab = P_FILTER }
            if (!main) button(g, b.lx + b.pw - 18, ry + 5, 16, 16, Component.literal("x"), tooltip = lines(tr("foremon.gui.chest_remove"))) {
                op("link_remove") { t -> t.putLong("chest", c.getLong("pos")) }
                if (filterChest == c.getLong("pos")) filterChest = null
            }
        }
        if (all.size > rows) text(g, tr("foremon.gui.more", all.size - rows), b.lx, b.bottom - 8, Pal.MUTED)
        heading(g, tr("foremon.gui.links", all.size - 1, data.getInt("maxLinks")), b.rx + b.pw / 2, b.rtop + 2)
        var y = b.rtop + 16
        y += para(g, tr(if (job == Job.SORTING) "foremon.gui.links.hint_logistics" else "foremon.gui.links.hint"), b.rx, y, b.pw) + 6
        y += para(g, muted(tr("foremon.gui.links.how")), b.rx, y, b.pw, Pal.MUTED) + 8
        button(g, b.rx, y, b.pw, 16, tr("foremon.gui.clear_links"), enabled = all.size > 1) { op("clear_links") }
    }

    // ------------------------------------------------------------ routes (Hauling)

    /** Hauling: every route on the left page; adding a route or picking its crew on the right. */
    private fun routesPage(g: GuiGraphics, b: Book) {
        val routes = data.getList("routes", Tag.TAG_COMPOUND.toInt())
        ribbon(g, b, tr("foremon.gui.tab_routes", routes.size), tr("foremon.gui.route_reach", data.getInt("routeReach")))
        if (crewFor >= routes.size) crewFor = -1
        val all = chests()
        fun chestAt(pos: Long) = all.indexOfFirst { it.getLong("pos") == pos }
        fun nameOf(i: Int): Component = if (i <= 0) tr("foremon.gui.chest_main") else tr("foremon.gui.chest_extra", i)
        val rowH = 34
        val rows = ((b.bottom - b.top) / rowH).coerceAtLeast(1)
        for (i in 0 until min(routes.size, rows)) {
            val r = routes.getCompound(i)
            val ry = b.top + i * rowH
            row(g, b.lx, ry, b.pw, rowH - 3, selected = i == crewFor)
            val a = chestAt(r.getLong("from")); val bb = chestAt(r.getLong("to"))
            all.getOrNull(a)?.let { icon(g, MonIcons.ball(it.getString("ball")), b.lx + 2, ry + 2) }
            text(g, "→", b.lx + 20, ry + 6, Pal.INK)
            all.getOrNull(bb)?.let { icon(g, MonIcons.ball(it.getString("ball")), b.lx + 28, ry + 2) }
            clip(g, Component.empty().append(nameOf(a)).append(" → ").append(nameOf(bb)), b.lx + 47, ry + 6, b.pw - 50, Pal.INK)
            // What it carries is the filter of the chest it delivers to.
            val dest = all.getOrNull(bb)
            val entries = dest?.getList("entries", Tag.TAG_STRING.toInt())?.size ?: 0
            val carries = if (dest == null || entries == 0) tr("foremon.gui.chest_takes_all")
                else tr(if (dest.getBoolean("only")) "foremon.gui.chest_only_n" else "foremon.gui.chest_except_n", entries)
            val crew = r.getList("crew", Tag.TAG_STRING.toInt()).size
            val line = "${r.getInt("dist")}m" + (if (r.getBoolean("loaded")) "" else " · " + tr("foremon.gui.route_unloaded").string) +
                (if (crew > 0) " · " + tr("foremon.gui.route_crew_n", crew).string else "") + " · " + carries.string
            clip(g, Component.literal(line), b.lx + 3, ry + 20, b.pw - 92, Pal.MUTED)
            button(g, b.lx + b.pw - 88, ry + 16, 34, 13, tr("foremon.gui.route_crew"), tooltip = lines(tr("foremon.gui.route_crew_tip"))) { crewFor = i; picking = false }
            button(g, b.lx + b.pw - 52, ry + 16, 34, 13, tr("foremon.gui.tab_filter_short"), tooltip = lines(tr("foremon.gui.route_filter_tip"))) { filterChest = r.getLong("to"); tab = P_FILTER }
            button(g, b.lx + b.pw - 16, ry + 16, 13, 13, Component.literal("x"), tooltip = lines(tr("foremon.gui.route_remove"))) { op("route_remove") { t -> t.putInt("index", i) } }
        }
        if (routes.size > rows) text(g, tr("foremon.gui.more", routes.size - rows), b.lx, b.bottom - 8, Pal.MUTED)
        if (routes.isEmpty()) para(g, muted(tr("foremon.status.make_routes")), b.lx, b.top + 2, b.pw, Pal.MUTED)

        when {
            picking -> sitePicker(g, b)
            crewFor >= 0 -> crewPicker(g, b, routes)
            else -> {
                heading(g, tr("foremon.gui.routes_new"), b.rx + b.pw / 2, b.rtop + 2)
                button(g, b.rx, b.rtop + 16, b.pw, 18, tr("foremon.gui.route_add"), accent = true, tooltip = lines(tr("foremon.gui.route_add_tip"))) { picking = true; pickFrom = null; crewFor = -1 }
                var y = b.rtop + 40
                y += para(g, muted(tr("foremon.gui.routes.how")), b.rx, y, b.pw, Pal.MUTED, 4) + 6
                // Requests: the depot's haulers also bring your other sites what they ask for.
                val fill = data.getBoolean("fillRequests")
                heading(g, tr("foremon.gui.requests"), b.rx + b.pw / 2, y); y += 12
                button(g, b.rx, y, b.pw, 16, tr(if (fill) "foremon.gui.fill_requests_on" else "foremon.gui.fill_requests_off"), accent = fill,
                    tooltip = lines(tr("foremon.gui.fill_requests.hint"))) { op("fill_requests") }
                y += 20
                val open = data.getList("openRequests", Tag.TAG_COMPOUND.toInt())
                if (open.isEmpty()) para(g, muted(tr("foremon.gui.requests_none")), b.rx, y, b.pw, Pal.MUTED, 2)
                for (i in 0 until open.size) {
                    if (y + 17 > b.bottom) { text(g, tr("foremon.gui.more", open.size - i), b.rx, y, Pal.MUTED); break }
                    val r = open.getCompound(i)
                    val st = MonIcons.item(r.getString("item"))
                    icon(g, ItemStack(JOB_ICONS[Job.of(r.getString("job")) ?: Job.LUMBER] ?: Items.PAPER), b.rx, y)
                    text(g, "←", b.rx + 18, y + 4, Pal.MUTED)
                    icon(g, st, b.rx + 26, y)
                    clip(g, Component.literal("${r.getInt("count")} ").append(st.hoverName), b.rx + 44, y + 4, b.pw - 72, Pal.INK)
                    val d = "${r.getInt("dist")}m"
                    text(g, d, b.rx + b.pw - font.width(d), y + 4, Pal.MUTED)
                    y += 17
                }
            }
        }
    }

    /** Dispatch, on the right page: pick which haulers work only route [crewFor]. Anyone not picked works every route. */
    private fun crewPicker(g: GuiGraphics, b: Book, routes: ListTag) {
        heading(g, tr("foremon.gui.crew_title", crewFor + 1), b.rx + b.pw / 2, b.rtop + 2)
        val on = HashMap<String, Int>()
        for (k in 0 until routes.size) routes.getCompound(k).getList("crew", Tag.TAG_STRING.toInt()).forEach { on[it.asString] = k }
        val workers = data.getList("workers", Tag.TAG_COMPOUND.toInt()).let { l -> (0 until l.size).map { l.getCompound(it) } }
        for ((i, m) in workers.withIndex()) {
            val ry = b.rtop + 16 + i * 20
            if (ry + 18 > b.bottom - 20) break
            val id = m.getUUID("id")
            val route = on[id.toString()]
            val hot = hover(b.rx, ry, b.pw, 18)
            row(g, b.rx, ry, b.pw, 18, hot, selected = route == crewFor)
            icon(g, MonIcons.of(m), b.rx + 1, ry + 1)
            val where = when (route) { crewFor -> tr("foremon.gui.crew_here"); null -> tr("foremon.gui.crew_any"); else -> tr("foremon.gui.crew_other", route + 1) }
            clip(g, Component.literal(m.getString("name") + "  ").append(where), b.rx + 19, ry + 5, b.pw - 22, if (route == crewFor) Pal.GOOD else Pal.INK)
            click(b.rx, ry, b.pw, 18) { op("route_crew") { t -> t.putInt("index", crewFor); t.putUUID("mon", id) } }
        }
        if (workers.isEmpty()) para(g, muted(tr("foremon.gui.crew_none")), b.rx, b.rtop + 18, b.pw, Pal.MUTED)
        button(g, b.rx, b.bottom - 16, b.pw, 16, tr("foremon.gui.done")) { crewFor = -1 }
    }

    /** Pick where a new route takes from, then where it delivers to: this depot, its chests, any job site, or the world. */
    private fun sitePicker(g: GuiGraphics, b: Book) {
        heading(g, tr(if (pickFrom == null) "foremon.gui.route_pick_from" else "foremon.gui.route_pick_to"), b.rx + b.pw / 2, b.rtop + 2)
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
        val rowH = 19
        val rows = ((b.bottom - 20 - b.rtop - 16) / rowH).coerceAtLeast(1)
        for ((i, o) in options.take(rows).withIndex()) {
            val ry = b.rtop + 16 + i * rowH
            val tooFar = o.dist > reach
            val off = tooFar || o.locked || o.p == pickFrom
            val hot = !off && hover(b.rx, ry, b.pw, rowH - 2)
            row(g, b.rx, ry, b.pw, rowH - 2, hot, selected = o.p == PICK_IN_WORLD)
            icon(g, o.icon, b.rx + 1, ry)
            val where = if (o.dist < 0) "" else "  ${o.dist}m"
            clip(g, Component.empty().append(o.name).append(where), b.rx + 19, ry + 5, b.pw - 22, if (off) Pal.MUTED else Pal.INK)
            if (hover(b.rx, ry, b.pw, rowH - 2)) when {
                o.p == PICK_IN_WORLD -> tip = lines(tr("foremon.gui.route_pick_world"), muted(tr("foremon.gui.route_pick_world_tip")))
                o.locked -> tip = lines(tr("foremon.gui.route_needs_remote"))
                tooFar -> tip = lines(tr("foremon.fail.route_far", reach))
                o.dist >= 0 -> BlockPos.of(o.p).let { bp -> tip = lines(Component.literal("${bp.x} ${bp.y} ${bp.z}")) }
            }
            if (!off) click(b.rx, ry, b.pw, rowH - 2) {
                val from = pickFrom
                if (from == null) pickFrom = o.p
                else if (o.p == PICK_IN_WORLD) { op("route_start") { t -> t.putLong("from", from) }; picking = false; pickFrom = null; onClose() }
                else { op("route_add") { t -> t.putLong("from", from); t.putLong("to", o.p) }; picking = false; pickFrom = null }
            }
        }
        if (options.size > rows) text(g, tr("foremon.gui.more", options.size - rows), b.rx, b.bottom - 28, Pal.MUTED)
        button(g, b.rx, b.bottom - 16, b.pw, 16, tr("foremon.gui.cancel")) { picking = false; pickFrom = null }
    }

    // ------------------------------------------------------------ map (Guard)

    private val TOOL_COLORS = intArrayOf(0xFF3E9E4F.toInt(), 0xFFD0453A.toInt(), 0xFFD9A21E.toInt(), Pal.RIBBON, Pal.INK)
    private val TOOL_KEYS = listOf("area", "keep_out", "no_torch", "route", "select")
    private var tool = 0
    private var selZone = 0
    private var bigMap = false
    private var dragFrom: IntArray? = null
    private var map: IntArray? = null
    private var mapAt = 0
    private var mapR = -1
    // Where the map was drawn last frame, for the mouse: corner on screen, cell size, size, cells across, world x/z of its corner.
    private var mapX = 0; private var mapY = 0; private var mapCell = 1; private var mapSize = 0; private var mapN = 0; private var mapWx = 0; private var mapWz = 0

    private fun zones() = Zone.unpack(data.getIntArray("zones"))
    private fun route() = data.getLongArray("patrol").map { BlockPos.of(it) }

    /** Guard, in the book: the map on the left page, the drawing tools and the selected box on the right. */
    private fun mapPage(g: GuiGraphics, b: Book) {
        ribbon(g, b, tr("foremon.gui.tab_map"))
        val size = min(b.pw, b.bottom - b.top - 2)
        drawMap(g, b.lx, b.top, size)
        mapSide(g, b.rx, b.rtop, b.pw, b.bottom, big = false)
    }

    /** The map as big as the screen allows, with the same tools beside it. Escape or Back returns to the book. */
    private fun bigMapView(g: GuiGraphics) {
        val side = 150
        val size = min(width - side - 40, height - 24)
        val x = (width - size - side - 14) / 2; val y = (height - size) / 2
        g.fill(x - 6, y - 6, x + size + side + 20, y + size + 6, Pal.COVER_DARK)
        g.fill(x - 5, y - 5, x + size + side + 19, y + size + 5, Pal.COVER)
        g.fill(x - 2, y - 2, x + size + side + 16, y + size + 2, Pal.PAGE)
        drawMap(g, x, y, size)
        mapSide(g, x + size + 10, y, side, y + size, big = true)
    }

    /** The tools, what is under the mouse, and the selected box (with who is posted to it). */
    private fun mapSide(g: GuiGraphics, x: Int, y0: Int, w: Int, bottom: Int, big: Boolean) {
        val zones = zones(); val route = route()
        heading(g, tr("foremon.gui.map_tools"), x + w / 2, y0 + 2)
        // The tools, two to a row.
        val cw = (w - 3) / 2
        for ((i, k) in TOOL_KEYS.withIndex()) {
            val tx = x + (i % 2) * (cw + 3); val ty = y0 + 14 + (i / 2) * 16
            val hot = hover(tx, ty, cw, 15)
            row(g, tx, ty, cw, 15, hot, selected = tool == i)
            g.fill(tx + 3, ty + 3, tx + 12, ty + 12, TOOL_COLORS[i])
            clip(g, tr("foremon.gui.map_tool_$k"), tx + 15, ty + 4, cw - 17, Pal.INK)
            if (hot) tip = lines(tr("foremon.gui.map_tool_$k"), muted(tr("foremon.gui.map_tool_$k.hint")))
            click(tx, ty, cw, 15) { tool = i; dragFrom = null }
        }
        var y = y0 + 14 + 3 * 16 + 3
        // What is under the mouse, or how to use the map.
        val hot = cellAt(mx.toDouble(), my.toDouble())
        if (hot != null) {
            val hunt = Zone.allows(zones, Zone.PATROL, hot[0], hot[1]); val torch = Zone.allows(zones, Zone.TORCHES, hot[0], hot[1])
            text(g, "${hot[0]}, ${hot[1]}", x, y, Pal.MUTED)
            val h = tr(if (hunt) "foremon.gui.map_hunt_yes" else "foremon.gui.map_hunt_no")
            text(g, h, x, y + 10, if (hunt) Pal.GOOD else Pal.BAD)
            text(g, tr(if (torch) "foremon.gui.map_torch_yes" else "foremon.gui.map_torch_no"), x + font.width(h) + 8, y + 10, if (torch) Pal.GOOD else Pal.BAD)
        } else para(g, muted(tr("foremon.gui.map_how")), x, y, w, Pal.MUTED, 2)
        y += 24
        // The selected box: its size, who is posted there (areas only), remove. It stops above the bottom buttons.
        val limit = bottom - 38
        val sel = zones.firstOrNull { it.id == selZone }
        if (sel != null && y + 26 <= limit) {
            clip(g, tr("foremon.gui.map_selected", tr("foremon.zone.${sel.kind}"), sel.x2 - sel.x1 + 1, sel.z2 - sel.z1 + 1), x, y, w, TOOL_COLORS[sel.kind]); y += 11
            if (sel.kind == Zone.AREA) {
                clip(g, tr("foremon.gui.map_post_title"), x, y, w, Pal.MUTED); y += 10
                val workers = data.getList("workers", Tag.TAG_COMPOUND.toInt())
                for (i in 0 until workers.size) {
                    if (y + 15 > limit - 16) break
                    val m = workers.getCompound(i)
                    val post = m.getInt("post")
                    val rhot = hover(x, y, w, 15)
                    row(g, x, y, w, 15, rhot, selected = post == sel.id)
                    icon(g, MonIcons.of(m), x + 1, y, 0.85f)
                    val where = when (post) { sel.id -> tr("foremon.gui.map_posted"); 0 -> tr("foremon.gui.map_free"); else -> tr("foremon.gui.map_posted_other") }
                    clip(g, Component.literal(m.getString("name") + " ").append(where), x + 16, y + 4, w - 18, if (post == sel.id) Pal.GOOD else Pal.INK)
                    click(x, y, w, 15) { op("guard_post") { t -> t.putUUID("mon", m.getUUID("id")); t.putInt("id", sel.id) } }
                    y += 16
                }
            }
            button(g, x, minOf(y + 2, limit - 14), w, 14, tr("foremon.gui.map_remove_box"), danger = true) { op("zone_remove") { t -> t.putInt("id", sel.id) }; selZone = 0 }
        }
        // Bottom: the route, clearing, and the big map.
        val by = bottom - 16
        button(g, x, by - 18, w, 14, tr("foremon.gui.map_clear_route", route.size), enabled = route.isNotEmpty()) { op("patrol_clear") }
        val half = (w - 3) / 2
        button(g, x, by, half, 16, tr("foremon.gui.map_clear_all"), enabled = zones.isNotEmpty()) { op("zone_clear"); selZone = 0 }
        button(g, x + half + 3, by, w - half - 3, 16, tr(if (big) "foremon.gui.map_back" else "foremon.gui.map_big"), accent = !big) { bigMap = !big; dragFrom = null }
    }

    /** The area seen from above with its boxes, the patrol route, the chest and you. Mouse: see [mapClick] and [mouseReleased]. */
    private fun drawMap(g: GuiGraphics, x: Int, y0: Int, size: Int) {
        val r = if (data.contains("mapRadius")) data.getInt("mapRadius") else data.getInt("radius")
        val n = 2 * r + 1
        val c = BlockPos.of(pos)
        if (size < 16) return
        val cell = (size / n).coerceAtLeast(1)
        mapX = x; mapY = y0; mapCell = cell; mapSize = min(size, n * cell); mapN = n; mapWx = c.x - r; mapWz = c.z - r
        // The ground changes slowly; looking it up again every two seconds is plenty.
        if (map == null || mapR != r || ticks - mapAt >= 40) { map = colors(c, r); mapR = r; mapAt = ticks; upload(map!!, n) }
        g.fill(x - 1, y0 - 1, x + mapSize + 1, y0 + mapSize + 1, Pal.BTN_EDGE)
        g.enableScissor(x, y0, x + mapSize, y0 + mapSize)
        val vis = mapSize / cell
        mapTexId?.let { g.blit(it, x, y0, vis * cell, vis * cell, 0f, 0f, vis, vis, n, n) }
        fun sx(wx: Int) = x + (wx - mapWx) * cell
        fun sz(wz: Int) = y0 + (wz - mapWz) * cell
        fun box(x1: Int, z1: Int, x2: Int, z2: Int, fill: Int, edge: Int) {
            val a = sx(minOf(x1, x2)); val b = sz(minOf(z1, z2)); val e = sx(maxOf(x1, x2) + 1); val f = sz(maxOf(z1, z2) + 1)
            g.fill(a, b, e, f, fill); g.renderOutline(a, b, e - a, f - b, edge)
        }
        val fills = intArrayOf(0x403E9E4F, 0x55D0453A, 0x50D9A21E)
        val workers = data.getList("workers", Tag.TAG_COMPOUND.toInt()).let { l -> (0 until l.size).map { l.getCompound(it) } }
        for (z in zones()) {
            box(z.x1, z.z1, z.x2, z.z2, fills[z.kind], TOOL_COLORS[z.kind])
            if (z.id == selZone) g.renderOutline(sx(z.x1) - 1, sz(z.z1) - 1, sx(z.x2 + 1) - sx(z.x1) + 2, sz(z.z2 + 1) - sz(z.z1) + 2, 0xFFFFFFFF.toInt())
            // How many guards are posted to an area, in its corner.
            val posted = workers.count { it.getInt("post") == z.id }
            if (posted > 0) g.drawString(font, posted.toString(), sx(z.x1) + 2, sz(z.z1) + 2, 0xFFFFFFFF.toInt(), true)
        }
        // The patrol route: a line from stop to stop (and back to the first), then the stops, numbered.
        val route = route()
        for ((i, p) in route.withIndex()) {
            val q = route[(i + 1) % route.size]
            // A two-stop route is one line walked both ways: no need to draw it twice.
            if (route.size > 2 || route.size == 2 && i == 0) line(g, sx(p.x) + cell / 2, sz(p.z) + cell / 2, sx(q.x) + cell / 2, sz(q.z) + cell / 2, Pal.RIBBON)
        }
        for ((i, p) in route.withIndex()) {
            val px = sx(p.x) + cell / 2; val pz = sz(p.z) + cell / 2
            g.fill(px - 2, pz - 2, px + 3, pz + 3, Pal.RIBBON_DARK); g.fill(px - 1, pz - 1, px + 2, pz + 2, 0xFFFFFFFF.toInt())
            if (cell >= 3) g.drawString(font, (i + 1).toString(), px + 3, pz - 8, 0xFFFFFFFF.toInt(), true)
        }
        // The chest in the middle, and you, when you are on the map.
        box(c.x, c.z, c.x, c.z, 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        mc.player?.let { p -> if (abs(p.blockX - c.x) <= r && abs(p.blockZ - c.z) <= r) box(p.blockX, p.blockZ, p.blockX, p.blockZ, 0xFFFFD84A.toInt(), 0xFF000000.toInt()) }
        val hot = cellAt(mx.toDouble(), my.toDouble())
        dragFrom?.let { f -> val to = cellAt(mx.toDouble(), my.toDouble(), clamp = true)!!; box(f[0], f[1], to[0], to[1], fills[tool.coerceAtMost(2)], 0xFFFFFFFF.toInt()) }
        if (hot != null && dragFrom == null && cell >= 2) g.renderOutline(sx(hot[0]) - 1, sz(hot[1]) - 1, cell + 2, cell + 2, 0xFFFFFFFF.toInt())
        g.disableScissor()
        click(x, y0, mapSize, mapSize) { btn -> mapClick(btn) }
    }

    private var mapTex: net.minecraft.client.renderer.texture.DynamicTexture? = null
    private var mapTexId: net.minecraft.resources.ResourceLocation? = null

    /** Puts the map colours in a texture, so the ground is one picture per frame instead of a square per block. */
    private fun upload(colors: IntArray, n: Int) {
        var tex = mapTex
        if (tex == null || tex.pixels?.width != n) {
            mapTexId?.let { mc.textureManager.release(it) }
            tex = net.minecraft.client.renderer.texture.DynamicTexture(n, n, false)
            mapTex = tex; mapTexId = mc.textureManager.register("foremon_map", tex)
        }
        val img = tex.pixels ?: return
        // The image wants ABGR: swap red and blue.
        for (j in 0 until n) for (i in 0 until n) { val c = colors[j * n + i]; img.setPixelRGBA(i, j, (c and 0xFF00FF00.toInt()) or (c shr 16 and 0xFF) or ((c and 0xFF) shl 16)) }
        tex.upload()
    }

    override fun removed() {
        super.removed()
        mapTexId?.let { mc.textureManager.release(it) }
        mapTex = null; mapTexId = null
    }

    /** A line of small squares from one point to another, for the patrol route. */
    private fun line(g: GuiGraphics, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        val steps = maxOf(abs(x2 - x1), abs(y2 - y1)).coerceAtLeast(1)
        for (s in 0..steps step 3) {
            val px = x1 + (x2 - x1) * s / steps; val py = y1 + (y2 - y1) * s / steps
            g.fill(px, py, px + 2, py + 2, color)
        }
    }

    /**
     * Left: a box tool starts a box (sent when the button is let go), the route tool adds a stop, select picks the box
     * under the mouse. Right, with any tool: remove the stop or the box under the mouse.
     */
    private fun mapClick(btn: Int) {
        val at = cellAt(mx.toDouble(), my.toDouble()) ?: return
        val route = route()
        if (btn == 1) {
            val near = route.indexOfFirst { abs(it.x - at[0]) <= 1 && abs(it.z - at[1]) <= 1 }
            if (near >= 0) { op("patrol_remove") { t -> t.putInt("index", near) }; return }
            zones().lastOrNull { it.has(at[0], at[1]) }?.let { z -> op("zone_remove") { t -> t.putInt("id", z.id) }; if (selZone == z.id) selZone = 0 }
            return
        }
        when (tool) {
            0, 1, 2 -> dragFrom = at
            3 -> op("patrol_add") { t -> t.putInt("x", at[0]); t.putInt("z", at[1]) }
            else -> selZone = zones().lastOrNull { it.has(at[0], at[1]) }?.id ?: 0
        }
    }

    /** The world x/z under screen point [x], [y] on the map, or null off the map (or the nearest edge, with [clamp]). */
    private fun cellAt(x: Double, y: Double, clamp: Boolean = false): IntArray? {
        if (mapN == 0) return null
        val vis = min(mapN, mapSize / mapCell)
        if (vis <= 0) return null
        var i = kotlin.math.floor((x - mapX) / mapCell).toInt(); var j = kotlin.math.floor((y - mapY) / mapCell).toInt()
        if (clamp) { i = i.coerceIn(0, vis - 1); j = j.coerceIn(0, vis - 1) } else if (i !in 0 until vis || j !in 0 until vis) return null
        return intArrayOf(mapWx + i, mapWz + j)
    }

    override fun mouseReleased(x: Double, y: Double, button: Int): Boolean {
        val from = dragFrom ?: return super.mouseReleased(x, y, button)
        dragFrom = null
        val to = cellAt(x, y, clamp = true) ?: return true
        op("zone_add") { t ->
            t.putInt("kind", tool.coerceAtMost(2))
            t.putInt("x1", from[0]); t.putInt("z1", from[1]); t.putInt("x2", to[0]); t.putInt("z2", to[1])
        }
        return true
    }

    /** The area's colours as on a map, shaded by height like one. Grey where this client has no chunk loaded. */
    private fun colors(c: BlockPos, r: Int): IntArray {
        val n = 2 * r + 1
        val out = IntArray(n * n) { 0xFF8A8270.toInt() }
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

    // ------------------------------------------------------------ filter

    private fun entryIcon(entry: String): ItemStack {
        presets.firstOrNull { it.first == entry }?.let { return ItemStack(it.second) }
        return if (entry.startsWith("#")) ItemStack(Items.NAME_TAG) else MonIcons.item(entry)
    }

    private fun entryName(entry: String): Component =
        if (entry.startsWith("#")) tr("foremon.filter.tag", entry.substring(1)) else MonIcons.item(entry).hoverName

    /** A filter action for the chest being edited. */
    private fun fop(name: String, fill: (CompoundTag) -> Unit = {}) = op(name) { t -> selectedChest()?.let { t.putLong("chest", it.getLong("pos")) }; fill(t) }

    /** Left page: which chest, its mode and what is on its list. Right page: groups and your inventory to add from. */
    private fun filterPage(g: GuiGraphics, b: Book) {
        val crafting = job == Job.CRAFTING
        ribbon(g, b, tr(if (crafting) "foremon.gui.craft_title" else "foremon.gui.tab_filter_short"))
        if (crafting) para(g, muted(tr("foremon.gui.craft_how")), b.lx, b.bottom - 30, b.pw, Pal.MUTED, 3)
        val chest = selectedChest() ?: return
        val entries = chest.getList("entries", Tag.TAG_STRING.toInt()).map { it.asString }
        val only = chest.getBoolean("only")
        // Which chest this filter belongs to: its ball and name. Click to step through the site's chests.
        val all = chests()
        val idx = all.indexOfFirst { it.getLong("pos") == chest.getLong("pos") }
        val hot = hover(b.lx, b.top, b.pw, 20)
        row(g, b.lx, b.top, b.pw, 20, hot)
        icon(g, MonIcons.ball(chest.getString("ball")), b.lx + 2, b.top + 2)
        val label = if (chest.getBoolean("main")) tr("foremon.gui.chest_main") else tr("foremon.gui.chest_extra", idx)
        text(g, label.string + if (all.size > 1) "   ${idx + 1}/${all.size}" else "", b.lx + 21, b.top + 6, Pal.INK)
        if (hot && all.size > 1) tip = lines(tr("foremon.gui.filter_which"))
        if (all.size > 1) click(b.lx, b.top, b.pw, 20) { filterChest = all[(idx + 1) % all.size].getLong("pos") }
        val by = b.top + 24
        button(g, b.lx, by, b.pw - 46, 16, tr(if (only) "foremon.gui.filter_only" else "foremon.gui.filter_except"), tooltip = lines(tr("foremon.gui.filter_mode.hint"))) { fop("filter_mode") }
        button(g, b.lx + b.pw - 42, by, 42, 16, tr("foremon.gui.clear"), enabled = entries.isNotEmpty()) { fop("filter_clear") }
        var y = by + 22
        text(g, tr("foremon.gui.filter_entries", entries.size, data.getInt("maxFilter")), b.lx, y, Pal.MUTED); y += 11
        val perRow = (b.pw / 18).coerceAtLeast(1)
        g.fill(b.lx, y, b.lx + perRow * 18, y + ((entries.size + perRow - 1) / perRow).coerceAtLeast(1) * 18, 0x20000000)
        for ((i, e) in entries.withIndex()) {
            val ix = b.lx + (i % perRow) * 18; val iy = y + (i / perRow) * 18
            if (hover(ix, iy, 18, 18)) { g.fill(ix, iy, ix + 18, iy + 18, 0x40000000); tip = lines(entryName(e), muted(tr("foremon.gui.click_remove"))) }
            icon(g, entryIcon(e), ix + 1, iy + 1)
            click(ix, iy, 18, 18) { fop("filter_remove") { t -> t.putString("entry", e) } }
        }
        if (entries.isEmpty()) text(g, tr("foremon.gui.filter_empty"), b.lx + 3, y + 5, Pal.MUTED)

        // ---- right page
        y = b.rtop
        heading(g, tr("foremon.gui.presets"), b.rx + b.pw / 2, y + 2); y += 14
        val rPer = (b.pw / 18).coerceAtLeast(1)
        for ((i, p) in presets.withIndex()) {
            val ix = b.rx + (i % rPer) * 18; val iy = y + (i / rPer) * 18
            val has = p.first in entries
            if (has) g.fill(ix, iy, ix + 18, iy + 18, Pal.BTN_ACCENT) else if (hover(ix, iy, 18, 18)) g.fill(ix, iy, ix + 18, iy + 18, 0x40000000)
            if (hover(ix, iy, 18, 18)) tip = lines(entryName(p.first))
            icon(g, ItemStack(p.second), ix + 1, iy + 1)
            click(ix, iy, 18, 18) { fop(if (has) "filter_remove" else "filter_add") { t -> t.putString("entry", p.first) } }
        }
        y += ((presets.size + rPer - 1) / rPer) * 18 + 8
        // The player's own inventory: click anything in it to add it to the filter.
        heading(g, tr("foremon.gui.from_inventory"), b.rx + b.pw / 2, y); y += 12
        val inv = mc.player?.inventory ?: return
        val seen = HashSet<String>()
        var i = 0
        for (slot in 0 until 36) {
            val st = inv.getItem(slot)
            if (st.isEmpty) continue
            val id = BuiltInRegistries.ITEM.getKey(st.item).toString()
            if (!seen.add(id)) continue
            val ix = b.rx + (i % rPer) * 18; val iy = y + (i / rPer) * 18
            if (iy + 18 > b.bottom) break
            val has = id in entries
            if (has) g.fill(ix, iy, ix + 18, iy + 18, Pal.BTN_ACCENT) else if (hover(ix, iy, 18, 18)) g.fill(ix, iy, ix + 18, iy + 18, 0x40000000)
            if (hover(ix, iy, 18, 18)) tip = lines(st.hoverName, muted(tr(if (has) "foremon.gui.click_remove" else "foremon.gui.click_add")))
            icon(g, st, ix + 1, iy + 1)
            click(ix, iy, 18, 18) { fop(if (has) "filter_remove" else "filter_add") { t -> t.putString("entry", id) } }
            i++
        }
    }
}
