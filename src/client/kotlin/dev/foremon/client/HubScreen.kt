package dev.foremon.client

import dev.foremon.rules.Job
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.min

private const val GUIDE_PAGES = 8

/** The whistle's main screen, as a journal: your job sites, the skill tree, level perks, Work Dex, the guide and settings. */
class HubScreen : ForemonScreen(tr("foremon.gui.hub")) {
    private val views = HashMap<String, CompoundTag>()
    private var tab = "board"
    private var branch = "foreman"
    private var scroll = 0
    private var search: EditBox? = null
    private var isAdmin = false
    private var selSite = 0

    fun accept(kind: String, tag: CompoundTag) {
        views[kind] = tag
        tab = kind
        if (kind == "board") isAdmin = tag.getBoolean("admin")
        search?.visible = tab == "dex"
    }

    override fun init() {
        val b = layout()
        val box = EditBox(font, b.lx + 1, b.top + 1, b.pw - 50, 16, tr("foremon.gui.dex_search"))
        box.setMaxLength(40)
        box.setHint(tr("foremon.gui.dex_search"))
        box.visible = tab == "dex"
        search = addRenderableWidget(box)
    }

    override fun keyPressed(key: Int, scan: Int, mods: Int): Boolean {
        val s = search
        if (s != null && s.isFocused && (key == 257 || key == 335)) { send("dex") { it.putString("query", s.value) }; return true }
        return super.keyPressed(key, scan, mods)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        scroll = (scroll - dy.toInt()).coerceAtLeast(0)
        return true
    }

    private val tabIcons = mapOf(
        "board" to ItemStack(Items.WRITABLE_BOOK), "skills" to ItemStack(Items.EXPERIENCE_BOTTLE), "perks" to ItemStack(Items.NETHER_STAR),
        "dex" to MonIcons.item("cobblemon:pokedex_red"), "guide" to ItemStack(Items.BOOK), "admin" to ItemStack(Items.COMMAND_BLOCK),
    )

    override fun draw(g: GuiGraphics) {
        val b = layout()
        book(g, b)
        val tabs = if (isAdmin) listOf("board", "skills", "perks", "dex", "guide", "admin") else listOf("board", "skills", "perks", "dex", "guide")
        for ((i, t) in tabs.withIndex()) bookmark(g, b, i, tabIcons[t] ?: ItemStack(Items.PAPER), tab == t, tr("foremon.gui.tab_$t")) {
            scroll = 0
            // The guide lives on the client; every other page asks the server for fresh data.
            if (t == "guide") accept("guide", CompoundTag()) else send(t) { tag -> if (t == "dex") tag.putString("query", "") }
        }
        val v = views[tab]
        if (v == null) { ribbon(g, b, tr("foremon.gui.tab_$tab")); text(g, tr("foremon.gui.loading"), b.lx, b.top + 4, Pal.MUTED); return }
        when (tab) {
            "board" -> board(g, v, b)
            "skills" -> skills(g, v, b)
            "dex" -> dex(g, v, b)
            "perks" -> perks(g, v, b)
            "admin" -> admin(g, v, b)
            "guide" -> guide(g, b)
        }
    }

    // ------------------------------------------------------------ your job sites

    private fun board(g: GuiGraphics, v: CompoundTag, b: Book) {
        val sites = v.getList("sites", Tag.TAG_COMPOUND.toInt())
        ribbon(g, b, tr("foremon.gui.tab_board"), tr("foremon.gui.xp", v.getInt("xp")))
        text(g, tr("foremon.gui.board_summary", sites.size, v.getInt("maxSites")), b.lx, b.top, Pal.MUTED)
        if (sites.isEmpty()) { para(g, tr("foremon.gui.board_empty"), b.lx, b.top + 16, b.pw, Pal.MUTED); return }
        val rowH = 26
        val rows = ((b.bottom - b.top - 12) / rowH).coerceAtLeast(1)
        scroll = scroll.coerceAtMost((sites.size - rows).coerceAtLeast(0))
        if (selSite >= sites.size) selSite = 0
        for (i in 0 until min(rows, sites.size - scroll)) {
            val idx = i + scroll
            val s = sites.getCompound(idx)
            val y = b.top + 12 + i * rowH
            val job = Job.of(s.getString("job")) ?: Job.LUMBER
            val pos = BlockPos.of(s.getLong("pos"))
            val hot = hover(b.lx, y, b.pw, rowH - 2)
            row(g, b.lx, y, b.pw, rowH - 2, hot, selected = idx == selSite)
            icon(g, ItemStack(JOB_ICONS[job] ?: Items.PAPER), b.lx + 3, y + 3)
            text(g, tr("foremon.job.${job.key}"), b.lx + 22, y + 3, Pal.INK)
            val where = "${pos.x} ${pos.y} ${pos.z}"
            text(g, where, b.lx + b.pw - 4 - font.width(where), y + 3, Pal.MUTED)
            clip(g, statusLine(s), b.lx + 22, y + 13, b.pw - 26, statusColor(s))
            click(b.lx, y, b.pw, rowH - 2) { selSite = idx }
        }
        if (sites.size > rows) text(g, "${scroll + 1}-${min(sites.size, scroll + rows)} / ${sites.size}", b.lx, b.bottom - 8, Pal.MUTED)

        // Right page: the site picked on the left.
        val s = sites.getCompound(selSite)
        val job = Job.of(s.getString("job")) ?: Job.LUMBER
        val pos = BlockPos.of(s.getLong("pos"))
        icon(g, ItemStack(JOB_ICONS[job] ?: Items.PAPER), b.rx + b.pw / 2 - 8, b.rtop)
        heading(g, tr("foremon.gui.site_title", tr("foremon.job.${job.key}")), b.rx + b.pw / 2, b.rtop + 19)
        val where = "${pos.x} ${pos.y} ${pos.z}  ${s.getString("dim").substringAfter(':')}"
        text(g, where, b.rx + (b.pw - font.width(where)) / 2, b.rtop + 31, Pal.MUTED)
        var y = b.rtop + 46
        y += para(g, statusLine(s), b.rx, y, b.pw, statusColor(s), 3) + 6
        text(g, tr("foremon.gui.board_workers", s.getInt("workers"), s.getLong("done")), b.rx, y, Pal.INK); y += 12
        val mons = s.getList("mons", Tag.TAG_COMPOUND.toInt())
        for (m in 0 until min(6, mons.size)) {
            val mx0 = b.rx + m * 30
            row(g, mx0, y, 28, 26)
            icon(g, MonIcons.of(mons.getCompound(m)), mx0 + 2, y + 1, 1.5f)
            if (hover(mx0, y, 28, 26)) tip = lines(Component.literal(mons.getCompound(m).getString("name")))
        }
        y += 32
        if (s.contains("planSize")) {
            text(g, tr("foremon.gui.plan_progress", s.getInt("planIndex"), s.getInt("planSize")), b.rx, y, Pal.MUTED); y += 10
            bar(g, b.rx, y, b.pw, 5, s.getInt("planIndex").toDouble() / s.getInt("planSize").coerceAtLeast(1), Pal.GOOD)
        }
        val active = s.getBoolean("active")
        val fill: (CompoundTag) -> Unit = { t -> t.putLong("pos", s.getLong("pos")); t.putString("dim", s.getString("dim")) }
        val half = (b.pw - 4) / 2
        val by = b.bottom - 18
        button(g, b.rx, by, half, 18, tr(if (active) "foremon.gui.pause" else "foremon.gui.start"), accent = !active) { send("toggle", fill); send("board") }
        button(g, b.rx + half + 4, by, half, 18, tr("foremon.gui.open")) { send("open", fill) }
        if (!v.getBoolean("remote")) para(g, muted(tr("foremon.gui.board_remote_hint")), b.rx, by - 33, b.pw, Pal.MUTED, 3)
    }

    private fun statusLine(s: CompoundTag): Component {
        val status = s.getString("status")
        return if (status.isNotEmpty()) trArg(status, s.getString("statusArg")) else tr(if (s.getBoolean("active")) "foremon.gui.running" else "foremon.gui.paused")
    }

    private fun statusColor(s: CompoundTag) = if (s.getString("status").isNotEmpty()) Pal.WARN else if (s.getBoolean("active")) Pal.GOOD else Pal.MUTED

    // ------------------------------------------------------------ skill tree, across both pages

    private fun skills(g: GuiGraphics, v: CompoundTag, b: Book) {
        ribbon(g, b, tr("foremon.gui.tab_skills"), tr("foremon.gui.xp", v.getInt("xp")))
        val nodes = v.getList("nodes", Tag.TAG_COMPOUND.toInt())
        val branches = listOf("foreman", "labour", "care", "logistics")
        // Two branches on each page.
        val bw = (b.pw - 3) / 2
        for ((i, br) in branches.withIndex()) button(g, (if (i < 2) b.lx else b.rx) + (i % 2) * (bw + 3), b.top, bw, 15, tr("foremon.branch.$br"), accent = branch == br) { branch = br }
        val mode = tr("foremon.gui.mode", tr("foremon.mode.${v.getString("mode")}"))
        text(g, mode, b.rx + b.pw - font.width(mode), b.rtop + 2, Pal.MUTED)
        text(g, tr("foremon.gui.skill_how"), b.rx, b.rtop + 2, Pal.MUTED)

        val ax = b.lx; val aw = b.rx + b.pw - b.lx
        val ay = b.top + 20; val ah = b.bottom - ay
        val mine = (0 until nodes.size).map { nodes.getCompound(it) }.filter { it.getString("branch") == branch }
        val byId = mine.associateBy { it.getString("id") }
        val cols = (mine.maxOfOrNull { it.getInt("x") } ?: 0) + 1
        val rows = (mine.maxOfOrNull { it.getInt("y") } ?: 0) + 1
        val cellW = min(56, aw / cols); val cellH = min(46, ah / rows)
        val gx = ax + (aw - cols * cellW) / 2; val gy = ay + (ah - rows * cellH) / 2
        fun fx(n: CompoundTag) = gx + n.getInt("x") * cellW + (cellW - 26) / 2
        fun fy(n: CompoundTag) = gy + n.getInt("y") * cellH + 1

        // Connectors first, so the frames sit on top of them.
        for (n in mine) for (req in n.getString("requires").split(',').filter { it.isNotEmpty() }) {
            val r = byId[req.substringBefore(':')] ?: continue
            val met = r.getInt("level") >= (req.substringAfter(':').toIntOrNull() ?: 1)
            advLine(g, fx(r) + 12, fy(r) + 13, fx(n) + 12, fy(n) + 13, met)
        }
        for (n in mine) {
            val nx = fx(n); val ny = fy(n)
            val level = n.getInt("level"); val max = n.getInt("max")
            val blocked = n.getString("blocked")
            // One-time unlocks get the rounded "goal" frame; anything bought at least once turns gold.
            advFrame(g, nx, ny, if (max == 1) "goal" else "task", level > 0)
            icon(g, MonIcons.item(n.getString("icon")), nx + 5, ny + 5)
            plate(g, "$level/$max", nx + 13, ny + 29, if (level >= max) Pal.GOOD else if (blocked.isEmpty()) Pal.STAR else Pal.MUTED)
            if (hover(nx, ny, 26, 26)) {
                g.renderOutline(nx - 1, ny - 1, 28, 28, Pal.INK)
                val id = n.getString("id")
                val t = ArrayList<Component>()
                t.add(tr("foremon.skill.$id").withStyle(ChatFormatting.WHITE))
                t.add(tr("foremon.skill.$id.desc").withStyle(ChatFormatting.GRAY))
                t.add(tr("foremon.gui.skill_level", level, max).withStyle(ChatFormatting.AQUA))
                when {
                    blocked == "maxed" -> t.add(tr("foremon.gui.skill_maxed").withStyle(ChatFormatting.GREEN))
                    blocked.startsWith("requires") -> blocked.split(':').let { p -> t.add(tr("foremon.gui.skill_requires", tr("foremon.skill.${p[1]}"), p[2]).withStyle(ChatFormatting.RED)) }
                    blocked.startsWith("xp") -> t.add(tr("foremon.gui.skill_cost_short", n.getInt("cost"), n.getInt("cost") - v.getInt("xp")).withStyle(ChatFormatting.RED))
                    else -> t.add(tr("foremon.gui.skill_cost", n.getInt("cost")).withStyle(ChatFormatting.YELLOW))
                }
                tip = t
            }
            click(nx, ny, 26, 26) { send("buy") { t -> t.putString("node", n.getString("id")) } }
        }
    }

    // ------------------------------------------------------------ level perks: what a Pokemon unlocks as it levels up

    private var perkJob = Job.LUMBER

    private val perkIcons = mapOf(
        "leaf_sweep" to listOf(Items.OAK_LEAVES), "timber" to listOf(Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE),
        "vein" to listOf(Items.RAW_COPPER, Items.RAW_IRON, Items.RAW_GOLD), "sweep" to listOf(Items.WHEAT, Items.HAY_BLOCK),
        "batch" to listOf(Items.FURNACE, Items.BLAST_FURNACE), "load" to listOf(Items.BUNDLE, Items.CHEST_MINECART),
        "multi" to listOf(Items.BRICKS, Items.SCAFFOLDING), "cleave" to listOf(Items.IRON_SWORD, Items.DIAMOND_SWORD),
    )

    private fun perks(g: GuiGraphics, v: CompoundTag, b: Book) {
        ribbon(g, b, tr("foremon.gui.tab_perks"))
        for ((i, j) in Job.entries.withIndex()) {
            val y = b.top + i * 20
            val hot = hover(b.lx, y, b.pw, 18)
            row(g, b.lx, y, b.pw, 18, hot, selected = j == perkJob)
            icon(g, ItemStack(JOB_ICONS[j] ?: Items.PAPER), b.lx + 2, y + 1)
            text(g, tr("foremon.job.${j.key}"), b.lx + 22, y + 5, Pal.INK)
            click(b.lx, y, b.pw, 18) { perkJob = j }
        }
        // Right page: the ladder, top to bottom, with who in your party has reached each step.
        heading(g, tr("foremon.gui.perks_title", tr("foremon.job.${perkJob.key}")), b.rx + b.pw / 2, b.rtop + 2)
        val stages = v.getCompound("jobs").getList(perkJob.key, Tag.TAG_COMPOUND.toInt())
        val party = v.getList("party", Tag.TAG_COMPOUND.toInt()).let { l -> (0 until l.size).map { l.getCompound(it) } }
        if (stages.isEmpty()) { para(g, tr("foremon.gui.perks_none"), b.rx, b.rtop + 18, b.pw, Pal.MUTED); return }
        val seen = HashMap<String, Int>()
        val n = stages.size
        for (i in 0 until n) {
            val st = stages.getCompound(i)
            val id = st.getString("id"); val level = st.getInt("level"); val value = st.getDouble("value")
            val y = b.rtop + 16 + i * 40
            if (y + 36 > b.bottom) break
            val who = party.filter { it.getInt("level") >= level }
            if (i < n - 1) advLine(g, b.rx + 12, y + 26, b.rx + 12, y + 40, who.isNotEmpty())
            advFrame(g, b.rx, y, if (i == n - 1) "challenge" else "task", who.isNotEmpty())
            val idx = seen.merge(id, 1, Int::plus)!! - 1
            val icons = perkIcons[id] ?: listOf(Items.PAPER)
            icon(g, ItemStack(icons[idx.coerceAtMost(icons.size - 1)]), b.rx + 5, y + 5)
            text(g, tr("foremon.gui.perk_level", level), b.rx + 30, y + 1, if (who.isNotEmpty()) Pal.GOOD else Pal.MUTED)
            clip(g, unlockText(id, value), b.rx + 30, y + 11, b.pw - 30, Pal.INK)
            for ((k, m) in who.take(8).withIndex()) {
                icon(g, MonIcons.of(m), b.rx + 30 + k * 13, y + 21, 0.75f)
                if (hover(b.rx + 30 + k * 13, y + 21, 12, 12)) tip = lines(Component.literal(m.getString("name")).append(tr("foremon.gui.level", m.getInt("level")).withStyle(ChatFormatting.GRAY)))
            }
            if (who.isEmpty()) clip(g, tr("foremon.gui.perk_nobody"), b.rx + 30, y + 23, b.pw - 30, Pal.MUTED)
        }
    }

    // ------------------------------------------------------------ Work Dex

    private fun dex(g: GuiGraphics, v: CompoundTag, b: Book) {
        ribbon(g, b, tr("foremon.gui.tab_dex"))
        button(g, b.lx + b.pw - 46, b.top, 46, 18, tr("foremon.gui.search")) { send("dex") { it.putString("query", search?.value ?: "") } }
        var y = b.top + 24
        if (v.contains("notFound")) { para(g, tr("foremon.gui.dex_not_found", v.getString("notFound")), b.lx, y, b.pw, Pal.BAD, 2); y += 22 }
        if (v.contains("lookup")) {
            val m = v.getCompound("lookup")
            icon(g, MonIcons.of(m), b.lx, y, 2f)
            text(g, Component.literal(m.getString("name")), b.lx + 36, y + 3, Pal.NAME)
            text(g, m.getString("types").replace(",", " / "), b.lx + 36, y + 13, Pal.MUTED)
            text(g, tr("foremon.gui.dex_at_50"), b.lx + 36, y + 23, Pal.MUTED)
            y += 38
            val stars = m.getIntArray("stars")
            val cw = b.pw / 3
            for ((i, j) in Job.entries.withIndex()) {
                val jx = b.lx + (i % 3) * cw; val jy = y + (i / 3) * 19
                icon(g, ItemStack(JOB_ICONS[j] ?: Items.PAPER), jx, jy)
                val s = stars.getOrElse(i) { 0 }
                if (s == 0) text(g, "-", jx + 19, jy + 4, Pal.BAD) else stars(g, jx + 18, jy + 4, s)
                if (hover(jx, jy, cw, 18)) tip = lines(tr("foremon.job.${j.key}"))
            }
        } else if (!v.contains("notFound")) para(g, muted(tr("foremon.gui.dex_how")), b.lx, y, b.pw, Pal.MUTED)

        // Right page: your best Pokemon for each job.
        heading(g, tr("foremon.gui.dex_best"), b.rx + b.pw / 2, b.rtop + 2)
        val best = v.getCompound("best")
        for ((idx, j) in Job.entries.withIndex()) {
            val by = b.rtop + 16 + idx * 21
            if (by + 19 > b.bottom) break
            val list = best.getList(j.key, Tag.TAG_COMPOUND.toInt())
            row(g, b.rx, by, b.pw, 19)
            icon(g, ItemStack(JOB_ICONS[j] ?: Items.PAPER), b.rx + 2, by + 1)
            clip(g, tr("foremon.job.${j.key}"), b.rx + 20, by + 6, 52, Pal.INK)
            for (i in 0 until min(3, list.size)) {
                val m = list.getCompound(i)
                val mx0 = b.rx + 74 + i * 36
                if (mx0 + 34 > b.rx + b.pw) break
                if (hover(mx0, by, 34, 19)) tip = lines(
                    Component.literal(m.getString("name")).append(tr("foremon.gui.level", m.getInt("level")).withStyle(ChatFormatting.GRAY)),
                    tr("foremon.gui.speed", "%.2f".format(m.getDouble("mult"))).withStyle(ChatFormatting.GOLD),
                    tr("foremon.gui.mastery", tr("foremon.mastery.${m.getInt("mastery")}")).withStyle(ChatFormatting.AQUA),
                )
                icon(g, MonIcons.of(m), mx0, by + 1)
                text(g, "★${m.getInt("starsFor")}", mx0 + 17, by + 6, Pal.STAR)
            }
            if (list.isEmpty()) text(g, "-", b.rx + 74, by + 6, Pal.MUTED)
        }
    }

    // ------------------------------------------------------------ guide: chapters on the left, the text on the right

    private var page = 0

    private fun guide(g: GuiGraphics, b: Book) {
        ribbon(g, b, tr("foremon.gui.tab_guide"))
        for (i in 0 until GUIDE_PAGES) {
            val y = b.top + i * 21
            val hot = hover(b.lx, y, b.pw, 19)
            row(g, b.lx, y, b.pw, 19, hot, selected = page == i)
            text(g, "${i + 1}.", b.lx + 4, y + 6, Pal.MUTED)
            clip(g, tr("foremon.guide.$i.title"), b.lx + 18, y + 6, b.pw - 22, Pal.INK)
            click(b.lx, y, b.pw, 19) { page = i; scroll = 0 }
        }
        heading(g, tr("foremon.guide.$page.title"), b.rx + b.pw / 2, b.rtop + 2)
        val wrapped = font.split(tr("foremon.guide.$page.body"), b.pw)
        val rows = (b.bottom - b.rtop - 26) / 10
        scroll = scroll.coerceAtMost((wrapped.size - rows).coerceAtLeast(0))
        for ((i, line) in wrapped.drop(scroll).take(rows).withIndex()) g.drawString(font, line, b.rx, b.rtop + 16 + i * 10, Pal.INK, false)
        if (wrapped.size > rows) {
            val pages = "${scroll / rows.coerceAtLeast(1) + 1} / ${(wrapped.size + rows - 1) / rows}"
            text(g, pages, b.rx + b.pw - font.width(pages), b.bottom - 8, Pal.MUTED)
            text(g, tr("foremon.gui.scroll_more"), b.rx, b.bottom - 8, Pal.MUTED)
        }
    }

    // ------------------------------------------------------------ server settings (operators)

    private fun admin(g: GuiGraphics, v: CompoundTag, b: Book) {
        ribbon(g, b, tr("foremon.gui.tab_admin"))
        text(g, tr("foremon.gui.admin_summary", v.getInt("_sites"), v.getInt("_workers")), b.lx, b.top, Pal.MUTED)
        fun set(key: String, value: String) = send("admin_set") { it.putString("key", key); it.putString("value", value) }
        val keys = v.allKeys.filter { !it.startsWith("_") }.sorted().sortedBy { if (it == "mode") 0 else 1 }
        var x = b.lx; var y = b.top + 14; var onRight = false
        for (key in keys) {
            val value = v.getString(key)
            val h = if (key == "mode") 34 else 22
            if (y + h > b.bottom && !onRight) { onRight = true; x = b.rx; y = b.rtop + 2 }
            if (y + h > b.bottom) break
            row(g, x, y, b.pw, h - 2)
            if (hover(x, y, b.pw - 70, h - 2)) tip = lines(tr("foremon.config.$key"), muted(tr("foremon.config.$key.desc")))
            when {
                key == "mode" -> {
                    clip(g, tr("foremon.config.$key"), x + 4, y + 4, b.pw - 8, Pal.INK)
                    val mw = (b.pw - 10) / 3
                    for ((i, m) in listOf("relaxed", "normal", "hardcore").withIndex())
                        button(g, x + 4 + i * (mw + 1), y + 15, mw, 15, tr("foremon.mode.$m"), accent = value == m) { set(key, m) }
                }
                value == "true" || value == "false" -> {
                    clip(g, tr("foremon.config.$key"), x + 4, y + 6, b.pw - 50, Pal.INK)
                    button(g, x + b.pw - 40, y + 2, 36, 16, tr(if (value == "true") "foremon.gui.on" else "foremon.gui.off"), accent = value == "true") { set(key, (value != "true").toString()) }
                }
                else -> {
                    clip(g, tr("foremon.config.$key"), x + 4, y + 6, b.pw - 72, Pal.INK)
                    val n = value.toIntOrNull() ?: 0
                    button(g, x + b.pw - 64, y + 2, 16, 16, Component.literal("-")) { set(key, (n - 1).coerceAtLeast(0).toString()) }
                    text(g, value, x + b.pw - 37 - font.width(value) / 2, y + 6, Pal.INK)
                    button(g, x + b.pw - 20, y + 2, 16, 16, Component.literal("+")) { set(key, (n + 1).toString()) }
                }
            }
            y += h
        }
        if (!onRight) { x = b.rx; y = b.rtop + 2 }
        para(g, muted(tr("foremon.gui.admin_advanced")), x, y + 4, b.pw, Pal.MUTED)
    }
}
