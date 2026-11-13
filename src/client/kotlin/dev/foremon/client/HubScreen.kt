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

/** The whistle's main screen: Foreman's Board, skill tree, Work Dex, the guide and (for operators) server settings. */
class HubScreen : ForemonScreen(tr("foremon.gui.hub")) {
    private val views = HashMap<String, CompoundTag>()
    private var tab = "board"
    private var branch = "foreman"
    private var scroll = 0
    private var search: EditBox? = null
    private var isAdmin = false

    fun accept(kind: String, tag: CompoundTag) {
        views[kind] = tag
        tab = kind
        if (kind == "board") isAdmin = tag.getBoolean("admin")
        search?.visible = tab == "dex"
    }

    override fun init() {
        val w = min(430, width - 16)
        val box = EditBox(font, (width - w) / 2 + 8, (height - min(236, height - 16)) / 2 + 36, 120, 16, tr("foremon.gui.dex_search"))
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

    override fun draw(g: GuiGraphics) {
        val w = min(430, width - 16); val h = min(236, height - 16)
        val x0 = (width - w) / 2; val y0 = (height - h) / 2
        window(g, x0, y0, w, h)
        val tabs = if (isAdmin) listOf("board", "skills", "perks", "dex", "guide", "admin") else listOf("board", "skills", "perks", "dex", "guide")
        val tw = (w - 12) / tabs.size
        for ((i, t) in tabs.withIndex())
            button(g, x0 + 6 + i * tw, y0 + 6, tw - 2, 18, tr("foremon.gui.tab_$t"), accent = tab == t) {
                scroll = 0
                // The guide lives on the client; every other tab asks the server for fresh data.
                if (t == "guide") accept("guide", CompoundTag()) else send(t) { tag -> if (t == "dex") tag.putString("query", "") }
            }
        g.fill(x0 + 1, y0 + 28, x0 + w - 1, y0 + 29, Pal.LINE)
        val v = views[tab]
        if (v == null) { text(g, tr("foremon.gui.loading"), x0 + 8, y0 + 40, Pal.MUTED); return }
        when (tab) {
            "board" -> board(g, v, x0 + 6, y0 + 34, w - 12, h - 40)
            "skills" -> skills(g, v, x0 + 6, y0 + 34, w - 12, h - 40)
            "dex" -> dex(g, v, x0 + 6, y0 + 34, w - 12, h - 40)
            "perks" -> perks(g, v, x0 + 6, y0 + 34, w - 12, h - 40)
            "admin" -> admin(g, v, x0 + 6, y0 + 34, w - 12)
            "guide" -> guide(g, x0 + 6, y0 + 34, w - 12, h - 40)
        }
    }

    // ------------------------------------------------------------ Foreman's Board

    private fun board(g: GuiGraphics, v: CompoundTag, x: Int, y0: Int, w: Int, h: Int) {
        val sites = v.getList("sites", Tag.TAG_COMPOUND.toInt())
        text(g, tr("foremon.gui.board_summary", sites.size, v.getInt("maxSites")), x, y0, Pal.MUTED)
        val xp = tr("foremon.gui.xp", v.getInt("xp"))
        text(g, xp, x + w - font.width(xp), y0, Pal.STAR)
        if (sites.isEmpty()) { text(g, tr("foremon.gui.board_empty"), x, y0 + 20, Pal.MUTED); return }
        val rows = (h - 14) / 30
        scroll = scroll.coerceAtMost((sites.size - rows).coerceAtLeast(0))
        for (i in 0 until min(rows, sites.size - scroll)) {
            val s = sites.getCompound(i + scroll)
            val y = y0 + 13 + i * 30
            val job = Job.of(s.getString("job")) ?: Job.LUMBER
            val pos = BlockPos.of(s.getLong("pos"))
            g.fill(x, y, x + w, y + 28, if (hover(x, y, w, 28)) Pal.CARD_HOVER else Pal.CARD)
            icon(g, ItemStack(JOB_ICONS[job] ?: Items.PAPER), x + 4, y + 6)
            text(g, tr("foremon.job.${job.key}").copy().append(Component.literal("  ${pos.x} ${pos.y} ${pos.z}").withStyle(ChatFormatting.GRAY)), x + 24, y + 4)
            val status = s.getString("status")
            val active = s.getBoolean("active")
            val line = if (status.isNotEmpty()) trArg(status, s.getString("statusArg")) else tr(if (active) "foremon.gui.running" else "foremon.gui.paused")
            clip(g, line, x + 24, y + 15, w - 230, if (status.isNotEmpty()) Pal.WARN else if (active) Pal.GOOD else Pal.MUTED)
            val mons = s.getList("mons", Tag.TAG_COMPOUND.toInt())
            for (m in 0 until min(6, mons.size)) icon(g, MonIcons.of(mons.getCompound(m)), x + w - 200 + m * 16, y + 6)
            if (s.contains("planSize")) bar(g, x + w - 200, y + 24, 96, 2, s.getInt("planIndex").toDouble() / s.getInt("planSize").coerceAtLeast(1), Pal.GOOD)
            val fill: (CompoundTag) -> Unit = { t -> t.putLong("pos", s.getLong("pos")); t.putString("dim", s.getString("dim")) }
            button(g, x + w - 98, y + 6, 46, 16, tr(if (active) "foremon.gui.pause" else "foremon.gui.start"), accent = !active) { send("toggle", fill); send("board") }
            button(g, x + w - 50, y + 6, 46, 16, tr("foremon.gui.open")) { send("open", fill) }
        }
        if (!v.getBoolean("remote")) clip(g, muted(tr("foremon.gui.board_remote_hint")), x, y0 + h - 8, w, Pal.MUTED)
    }

    // ------------------------------------------------------------ skill tree, drawn like the advancements screen

    private val branchBackground = mapOf("foreman" to "stone", "labour" to "adventure", "care" to "husbandry", "logistics" to "end")

    private fun skills(g: GuiGraphics, v: CompoundTag, x: Int, y0: Int, w: Int, h: Int) {
        val nodes = v.getList("nodes", Tag.TAG_COMPOUND.toInt())
        val branches = listOf("foreman", "labour", "care", "logistics")
        for ((i, b) in branches.withIndex()) button(g, x + i * 74, y0, 72, 16, tr("foremon.branch.$b"), accent = branch == b) { branch = b }
        val xp = tr("foremon.gui.xp", v.getInt("xp"))
        text(g, xp, x + w - font.width(xp), y0 + 1, Pal.STAR)
        val mode = tr("foremon.gui.mode", tr("foremon.mode.${v.getString("mode")}"))
        text(g, mode, x + w - font.width(mode), y0 + 11, Pal.MUTED)

        val ay = y0 + 22; val ah = h - 22
        advBackground(g, x, ay, w, ah, branchBackground[branch] ?: "stone")

        val mine = (0 until nodes.size).map { nodes.getCompound(it) }.filter { it.getString("branch") == branch }
        val byId = mine.associateBy { it.getString("id") }
        val cols = (mine.maxOfOrNull { it.getInt("x") } ?: 0) + 1
        val rows = (mine.maxOfOrNull { it.getInt("y") } ?: 0) + 1
        val cellW = min(52, (w - 12) / cols); val cellH = min(44, (ah - 6) / rows)
        val gx = x + (w - cols * cellW) / 2; val gy = ay + (ah - rows * cellH) / 2
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
            plate(g, "$level/$max", nx + 13, ny + 28, if (level >= max) Pal.GOOD else if (blocked.isEmpty()) Pal.STAR else Pal.TEXT)
            if (hover(nx, ny, 26, 26)) {
                g.renderOutline(nx - 1, ny - 1, 28, 28, Pal.BALL_WHITE)
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
        "multi" to listOf(Items.BRICKS, Items.SCAFFOLDING),
    )
    private val perkBackground = mapOf(
        Job.LUMBER to "husbandry", Job.FARMING to "husbandry", Job.MINING to "stone", Job.DEMOLITION to "stone",
        Job.SMELTING to "nether", Job.BUILDING to "adventure", Job.SORTING to "end", Job.HAULING to "adventure",
    )

    private fun perks(g: GuiGraphics, v: CompoundTag, x: Int, y0: Int, w: Int, h: Int) {
        for ((i, j) in Job.entries.withIndex()) {
            val by = y0 + i * 20
            val sel = j == perkJob
            g.fill(x, by, x + 96, by + 18, if (sel) Pal.ACCENT else if (hover(x, by, 96, 18)) Pal.CARD_HOVER else Pal.CARD)
            icon(g, ItemStack(JOB_ICONS[j] ?: Items.PAPER), x + 2, by + 1)
            clip(g, tr("foremon.job.${j.key}"), x + 21, by + 5, 72)
            click(x, by, 96, 18) { perkJob = j }
        }
        val ax = x + 102; val aw = w - 102
        advBackground(g, ax, y0, aw, h, perkBackground[perkJob] ?: "stone")
        plate(g, tr("foremon.gui.perks_title", tr("foremon.job.${perkJob.key}")).string, ax + aw / 2, y0 + 7, Pal.BALL_WHITE)

        val stages = v.getCompound("jobs").getList(perkJob.key, Tag.TAG_COMPOUND.toInt())
        val party = v.getList("party", Tag.TAG_COMPOUND.toInt())
        if (stages.isEmpty()) { plate(g, tr("foremon.gui.perks_none").string, ax + aw / 2, y0 + 40, Pal.MUTED); return }
        val n = stages.size
        val gap = min(70, (aw - 46) / n.coerceAtLeast(1))
        val sx = ax + (aw - ((n - 1) * gap + 26)) / 2
        val sy = y0 + 30
        val best = (0 until party.size).maxOfOrNull { party.getCompound(it).getInt("level") } ?: 0
        fun stageX(i: Int) = sx + i * gap

        for (i in 1 until n) advLine(g, stageX(i - 1) + 13, sy + 13, stageX(i) + 13, sy + 13, best >= stages.getCompound(i).getInt("level"))
        val seen = HashMap<String, Int>()
        for (i in 0 until n) {
            val st = stages.getCompound(i)
            val id = st.getString("id"); val level = st.getInt("level"); val value = st.getDouble("value")
            val nx = stageX(i)
            val reached = best >= level
            // The last stage of a ladder is the big one: it gets the spiky "challenge" frame.
            advFrame(g, nx, sy, if (i == n - 1) "challenge" else "task", reached)
            val idx = seen.merge(id, 1, Int::plus)!! - 1
            val icons = perkIcons[id] ?: listOf(Items.PAPER)
            icon(g, ItemStack(icons[idx.coerceAtMost(icons.size - 1)]), nx + 5, sy + 5)
            plate(g, tr("foremon.gui.perk_lv", level).string, nx + 13, sy + 29, if (reached) Pal.STAR else Pal.TEXT)
            if (hover(nx, sy, 26, 26)) {
                g.renderOutline(nx - 1, sy - 1, 28, 28, Pal.BALL_WHITE)
                val who = (0 until party.size).map { party.getCompound(it) }.filter { it.getInt("level") >= level }.joinToString(", ") { it.getString("name") }
                tip = lines(
                    unlockText(id, value).withStyle(ChatFormatting.WHITE),
                    tr("foremon.gui.perk_level", level).withStyle(ChatFormatting.AQUA),
                    (if (who.isEmpty()) tr("foremon.gui.perk_nobody") else tr("foremon.gui.perk_have", who)).withStyle(if (who.isEmpty()) ChatFormatting.GRAY else ChatFormatting.GREEN),
                )
            }
        }

        // Your party, each Pokemon standing under the last stage it has reached.
        val py = sy + 46
        plate(g, tr("foremon.gui.perks_party").string, ax + aw / 2, py, Pal.MUTED)
        val stacked = HashMap<Int, Int>()
        for (k in 0 until party.size) {
            val m = party.getCompound(k)
            val lvl = m.getInt("level")
            val at = (0 until n).count { lvl >= stages.getCompound(it).getInt("level") } - 1
            val col = at.coerceAtLeast(0)
            val row = stacked.merge(col, 1, Int::plus)!! - 1
            val px = stageX(col) + 5; val pyy = py + 14 + row * 19
            if (pyy + 18 > y0 + h) continue
            if (at < 0) g.fill(px - 2, pyy - 1, px + 18, pyy + 17, 0x90000000.toInt())
            icon(g, MonIcons.of(m), px, pyy)
            if (hover(px, pyy, 16, 16)) tip = lines(
                Component.literal(m.getString("name")).append(tr("foremon.gui.level", lvl).withStyle(ChatFormatting.GRAY)),
                tr(if (at < 0) "foremon.gui.perk_not_yet" else "foremon.gui.perk_reached", if (at < 0) stages.getCompound(0).getInt("level") else at + 1).withStyle(ChatFormatting.GRAY),
            )
        }
    }

    // ------------------------------------------------------------ Work Dex

    private fun dex(g: GuiGraphics, v: CompoundTag, x: Int, y0: Int, w: Int, h: Int) {
        button(g, x + 126, y0 + 1, 50, 18, tr("foremon.gui.search")) { send("dex") { it.putString("query", search?.value ?: "") } }
        var y = y0 + 24
        if (v.contains("notFound")) { text(g, tr("foremon.gui.dex_not_found", v.getString("notFound")), x + 182, y0 + 6, Pal.BAD) }
        if (v.contains("lookup")) {
            val m = v.getCompound("lookup")
            g.fill(x, y, x + w, y + 40, Pal.CARD)
            icon(g, MonIcons.of(m), x + 4, y + 4, 2f)
            text(g, Component.literal(m.getString("name")), x + 40, y + 4)
            text(g, m.getString("types").replace(",", " / "), x + 40, y + 15, Pal.MUTED)
            text(g, tr("foremon.gui.dex_at_50"), x + 40, y + 27, Pal.MUTED)
            val stars = m.getIntArray("stars")
            for ((i, j) in Job.entries.withIndex()) {
                val jx = x + 130 + (i % 4) * ((w - 134) / 4); val jy = y + 4 + (i / 4) * 18
                icon(g, ItemStack(JOB_ICONS[j] ?: Items.PAPER), jx, jy)
                val s = stars.getOrElse(i) { 0 }
                if (s == 0) text(g, "-", jx + 20, jy + 4, Pal.BAD) else stars(g, jx + 19, jy + 4, s)
                if (hover(jx, jy, 60, 16)) tip = lines(tr("foremon.job.${j.key}"))
            }
            y += 44
        }
        text(g, tr("foremon.gui.dex_best"), x, y, Pal.MUTED); y += 11
        val best = v.getCompound("best")
        val colW = w / 2
        for ((idx, j) in Job.entries.withIndex()) {
            val bx = x + (idx % 2) * colW; val by = y + (idx / 2) * 18
            if (by + 17 > y0 + h) break
            val list = best.getList(j.key, Tag.TAG_COMPOUND.toInt())
            icon(g, ItemStack(JOB_ICONS[j] ?: Items.PAPER), bx, by)
            clip(g, tr("foremon.job.${j.key}"), bx + 19, by + 4, 54)
            for (i in 0 until min(3, list.size)) {
                val m = list.getCompound(i)
                val mx0 = bx + 76 + i * 38
                if (mx0 + 36 > bx + colW) break
                if (hover(mx0, by, 36, 17)) {
                    g.fill(mx0, by, mx0 + 36, by + 17, Pal.CARD_HOVER)
                    tip = lines(
                        Component.literal(m.getString("name")).append(tr("foremon.gui.level", m.getInt("level")).withStyle(ChatFormatting.GRAY)),
                        tr("foremon.gui.speed", "%.2f".format(m.getDouble("mult"))).withStyle(ChatFormatting.GOLD),
                        tr("foremon.gui.mastery", tr("foremon.mastery.${m.getInt("mastery")}")).withStyle(ChatFormatting.AQUA),
                    )
                }
                icon(g, MonIcons.of(m), mx0, by)
                text(g, "\u2605${m.getInt("starsFor")}", mx0 + 18, by + 4, Pal.STAR)
            }
            if (list.isEmpty()) text(g, "-", bx + 76, by + 4, Pal.MUTED)
        }
    }

    // ------------------------------------------------------------ guide

    private var page = 0

    private fun guide(g: GuiGraphics, x: Int, y0: Int, w: Int, h: Int) {
        for (i in 0 until GUIDE_PAGES) {
            button(g, x, y0 + i * 18, 104, 16, tr("foremon.guide.$i.title"), accent = page == i) { page = i; scroll = 0 }
        }
        val bx = x + 112; val bw = w - 112
        panel(g, bx, y0, bw, h, Pal.CARD)
        text(g, tr("foremon.guide.$page.title"), bx + 6, y0 + 6, Pal.STAR)
        val wrapped = font.split(tr("foremon.guide.$page.body"), bw - 12)
        val rows = (h - 24) / 10
        scroll = scroll.coerceAtMost((wrapped.size - rows).coerceAtLeast(0))
        for ((i, line) in wrapped.drop(scroll).take(rows).withIndex()) g.drawString(font, line, bx + 6, y0 + 20 + i * 10, Pal.TEXT, false)
    }

    // ------------------------------------------------------------ server settings (operators)

    private fun admin(g: GuiGraphics, v: CompoundTag, x: Int, y0: Int, w: Int) {
        text(g, tr("foremon.gui.admin_summary", v.getInt("_sites"), v.getInt("_workers")), x, y0, Pal.MUTED)
        var y = y0 + 14
        fun set(key: String, value: String) = send("admin_set") { it.putString("key", key); it.putString("value", value) }
        for (key in v.allKeys.filter { !it.startsWith("_") }.sorted().sortedBy { if (it == "mode") 0 else 1 }) {
            val value = v.getString(key)
            g.fill(x, y, x + w, y + 20, Pal.CARD)
            text(g, tr("foremon.config.$key"), x + 6, y + 6)
            if (hover(x, y, w - 130, 20)) tip = lines(tr("foremon.config.$key"), muted(tr("foremon.config.$key.desc")))
            when {
                key == "mode" -> for ((i, m) in listOf("relaxed", "normal", "hardcore").withIndex())
                    button(g, x + w - 186 + i * 62, y + 2, 60, 16, tr("foremon.mode.$m"), accent = value == m) { set(key, m) }
                value == "true" || value == "false" ->
                    button(g, x + w - 62, y + 2, 60, 16, tr(if (value == "true") "foremon.gui.on" else "foremon.gui.off"), accent = value == "true") { set(key, (value != "true").toString()) }
                else -> {
                    val n = value.toIntOrNull() ?: 0
                    button(g, x + w - 86, y + 2, 20, 16, Component.literal("-")) { set(key, (n - 1).coerceAtLeast(0).toString()) }
                    text(g, value, x + w - 54 - font.width(value) / 2, y + 6)
                    button(g, x + w - 22, y + 2, 20, 16, Component.literal("+")) { set(key, (n + 1).toString()) }
                }
            }
            y += 22
        }
        for ((i, line) in font.split(tr("foremon.gui.admin_advanced"), w).withIndex()) g.drawString(font, line, x, y + 3 + i * 10, Pal.MUTED, false)
    }
}
