package dev.foremon.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.item.PokemonItem
import dev.foremon.net.Net
import dev.foremon.rules.Job
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Colours of the field journal every screen is drawn as: a red book with cream pages, a blue ribbon for the page
 * title, red bookmarks down the side, and dark ink. All drawn in code, no textures.
 */
object Pal {
    const val DIM = 0xB0080A0E.toInt()
    // The book
    const val COVER = 0xFF9E2B25.toInt()
    const val COVER_DARK = 0xFF5A1612.toInt()
    const val COVER_HI = 0xFFBF4237.toInt()
    const val PAGE = 0xFFF4E9CF.toInt()
    const val PAGE_EDGE = 0xFFD3C29C.toInt()
    const val FRAME = 0xFF9C8259.toInt()
    const val RIBBON = 0xFF2F62B0.toInt()
    const val RIBBON_DARK = 0xFF1C3F7A.toInt()
    const val TAB = 0xFFB3362D.toInt()
    const val TAB_ACTIVE = 0xFFE0563F.toInt()
    // Ink
    const val INK = 0xFF2E2418.toInt()
    const val TEXT = INK
    const val MUTED = 0xFF7A6A52.toInt()
    const val GOOD = 0xFF2E7D32.toInt()
    const val WARN = 0xFFB25E00.toInt()
    const val BAD = 0xFFB3261E.toInt()
    const val STAR = 0xFFB8860B.toInt()
    const val STAR_OFF = 0xFFCDBB94.toInt()
    const val NAME = 0xFF1F4E9C.toInt()
    const val LINE = 0xFFB9A57E.toInt()
    // Controls
    const val BTN = 0xFFF8EECD.toInt()
    const val BTN_HOT = 0xFFFFF8E0.toInt()
    const val BTN_EDGE = 0xFF6B4F2A.toInt()
    const val BTN_SHADE = 0xFFD9C79B.toInt()
    const val BTN_ACCENT = 0xFFE8BE4F.toInt()
    const val BTN_ACCENT_HOT = 0xFFF3CF6B.toInt()
    const val BTN_OFF = 0xFFD8CDB0.toInt()
    const val BTN_DANGER = 0xFFC94B3C.toInt()
    const val ROW = 0xFFFAF3DF.toInt()
    const val ROW_HOT = 0xFFFFF9E8.toInt()
    const val ROW_SEL = 0xFFF0DCA0.toInt()
    const val ROW_EDGE = 0xFF9C8259.toInt()
    const val BAR_BG = 0xFFD9CBA6.toInt()
    const val LOAD = 0xFF3D7CC9.toInt()
    const val BALL_RED = 0xFFE03A3A.toInt()
    const val BALL_WHITE = 0xFFF4F4F4.toInt()
    const val BALL_BLACK = 0xFF141414.toInt()
}

fun tr(key: String, vararg args: Any): MutableComponent = Component.translatable(key, *args)

/** Translates a server status or message key whose arguments arrive packed as "a|b|c". */
fun trArg(key: String, arg: String): MutableComponent = if (key.isEmpty()) Component.empty() else Component.translatable(key, *arg.split('|').toTypedArray())

/** The player-facing line for a level perk, e.g. "Timber: fells 4 logs per swing". */
fun unlockText(id: String, value: Double): MutableComponent = when {
    id == "timber" && value >= 256 -> tr("foremon.unlock.timber.max")
    value == Math.floor(value) -> tr("foremon.unlock.$id", value.toInt())
    else -> tr("foremon.unlock.$id", "%.1f".format(java.util.Locale.ROOT, value))
}

fun send(op: String, fill: (CompoundTag) -> Unit = {}) =
    ForemonClient.send(Net.Action(CompoundTag().also { it.putString("op", op); fill(it) }))

val JOB_ICONS = mapOf(
    Job.LUMBER to Items.IRON_AXE, Job.MINING to Items.IRON_PICKAXE, Job.SORTING to Items.HOPPER, Job.HAULING to Items.CHEST_MINECART,
    Job.FARMING to Items.IRON_HOE, Job.SMELTING to Items.BLAST_FURNACE, Job.BUILDING to Items.BRICKS, Job.DEMOLITION to Items.TNT,
    Job.GUARD to Items.IRON_SWORD, Job.CRAFTING to Items.CRAFTING_TABLE,
)

/** Item stacks that render as a Pokemon's 3D model, made by Cobblemon's own item. Cached: screens ask for them every frame. */
object MonIcons {
    private val cache = HashMap<String, ItemStack>()

    fun of(t: CompoundTag): ItemStack {
        val species = t.getString("species")
        val aspects = t.getList("aspects", Tag.TAG_STRING.toInt()).map { it.asString }.toSet()
        return cache.getOrPut(species + aspects) {
            val s = ResourceLocation.tryParse(species)?.let { PokemonSpecies.getByIdentifier(it) }
            if (s == null) ItemStack(Items.BARRIER) else runCatching { PokemonItem.from(s, aspects) }.getOrDefault(ItemStack(Items.BARRIER))
        }
    }

    /** A Cobblemon Poke Ball item, by its id without the namespace (e.g. "master_ball"). */
    fun ball(id: String): ItemStack = item("cobblemon:$id")

    fun item(id: String): ItemStack = cache.getOrPut("item:$id") {
        ResourceLocation.tryParse(id)?.let { BuiltInRegistries.ITEM.getOptional(it).map(::ItemStack).orElse(null) } ?: ItemStack(Items.PAPER)
    }
}

/** Where a book sits on screen, and its two pages. Content goes between [top] and [bottom]. */
class Book(val x: Int, val y: Int, val w: Int, val h: Int) {
    val mid get() = x + w / 2
    /** Left edge and width of each page's writing area. */
    val lx get() = x + 16
    val rx get() = mid + 10
    val pw get() = w / 2 - 26
    /** Top of the writing area, under the ribbon on the left page. */
    val top get() = y + 30
    /** Top of the right page, which has no ribbon. */
    val rtop get() = y + 14
    val bottom get() = y + h - 13
}

/**
 * Base for all screens. Immediate-mode: a screen describes itself from scratch every frame in [draw],
 * and every button it draws registers its own click area for that frame. No widget bookkeeping,
 * so the screen can never show stale state after the server sends an update.
 *
 * Every screen is a book, like a colony journal: [book] draws it, [ribbon] titles the left page, [bookmark] adds a
 * tab down the left (or right) edge, and [button] / [row] are the controls written on its pages.
 */
abstract class ForemonScreen(title: Component) : Screen(title) {
    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: (Int) -> Unit)

    private val hits = ArrayList<Hit>()
    protected var tip: List<Component>? = null
    protected var mx = 0
    protected var my = 0
    protected val mc: Minecraft get() = Minecraft.getInstance()

    protected abstract fun draw(g: GuiGraphics)

    override fun isPauseScreen() = false

    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        g.fill(0, 0, width, height, Pal.DIM)
        hits.clear(); tip = null; mx = mouseX; my = mouseY
        draw(g)
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(g, mouseX, mouseY, delta)
        tip?.let { g.renderComponentTooltip(font, it, mouseX, mouseY) }
    }

    override fun mouseClicked(x: Double, y: Double, button: Int): Boolean {
        // Actions that look at the mouse (the map) see where the click was, not where the mouse was last frame.
        mx = x.toInt(); my = y.toInt()
        for (h in hits.asReversed()) if (x >= h.x && x < h.x + h.w && y >= h.y && y < h.y + h.h) {
            mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.6f, 0.6f))
            h.action(button)
            return true
        }
        return super.mouseClicked(x, y, button)
    }

    protected fun hover(x: Int, y: Int, w: Int, h: Int) = mx >= x && mx < x + w && my >= y && my < y + h

    protected fun click(x: Int, y: Int, w: Int, h: Int, action: (Int) -> Unit) { hits.add(Hit(x, y, w, h, action)) }

    // ------------------------------------------------------------ the book

    /** The book for this screen size: as big as fits, up to 420 x 248, with room for bookmarks on both sides. */
    protected fun layout(): Book {
        val w = minOf(420, width - 60); val h = minOf(248, height - 16)
        return Book((width - w) / 2, (height - h) / 2, w, h)
    }

    /** Red leather cover, two cream pages with a shaded spine, and a thin frame on each page. */
    protected fun book(g: GuiGraphics, b: Book) {
        val x = b.x; val y = b.y; val w = b.w; val h = b.h; val mid = b.mid
        g.fill(x + 1, y, x + w - 1, y + h, Pal.COVER_DARK); g.fill(x, y + 1, x + w, y + h - 1, Pal.COVER_DARK)
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, Pal.COVER)
        g.fill(x + 2, y + 1, x + w - 2, y + 2, Pal.COVER_HI)
        // Page block: the edges of the pages below show along the bottom.
        g.fill(x + 5, y + 4, x + w - 5, y + h - 3, Pal.PAGE_EDGE)
        g.fill(x + 5, y + 4, x + w - 5, y + h - 5, Pal.PAGE)
        for (i in 1..2) g.fill(x + 5, y + h - 5 + i - 1, x + w - 5, y + h - 4 + i - 1, if (i == 1) Pal.LINE else Pal.PAGE_EDGE)
        // The spine: pages darken towards the fold.
        for (i in 0 until 7) {
            val a = (0x07 + i * 0x06) shl 24
            g.fill(mid - 7 + i, y + 4, mid - 6 + i, y + h - 5, a)
            g.fill(mid + 6 - i, y + 4, mid + 7 - i, y + h - 5, a)
        }
        g.fill(mid, y + 4, mid + 1, y + h - 5, 0x50000000)
        g.fill(mid - 3, y, mid + 3, y + 5, Pal.COVER_DARK); g.fill(mid - 3, y + h - 5, mid + 3, y + h, Pal.COVER_DARK)
        // A thin double frame on each page, with dots in the corners.
        for ((px, pw) in listOf(x + 9 to mid - x - 17, mid + 8 to x + w - mid - 17)) {
            g.renderOutline(px, y + 8, pw, h - 18, Pal.FRAME)
            g.renderOutline(px + 2, y + 10, pw - 4, h - 22, 0x609C8259)
            for (cx in listOf(px - 1, px + pw - 2)) for (cy in listOf(y + 7, y + h - 12)) g.fill(cx, cy, cx + 3, cy + 3, Pal.FRAME)
        }
    }

    /** The page title: a blue ribbon across the top of the left page, hanging out past the cover. */
    protected fun ribbon(g: GuiGraphics, b: Book, title: Component, right: Component? = null) {
        val y = b.y + 12
        g.fill(b.x - 12, y + 3, b.x - 4, y + 16, Pal.RIBBON_DARK)
        g.fill(b.x - 12, y + 8, b.x - 9, y + 11, 0xFF3A1010.toInt())
        g.fill(b.x - 8, y, b.mid - 10, y + 13, Pal.RIBBON)
        g.fill(b.x - 8, y + 12, b.mid - 10, y + 13, Pal.RIBBON_DARK)
        g.fill(b.x - 8, y, b.mid - 10, y + 1, 0xFF4A7CC8.toInt())
        g.drawString(font, font.plainSubstrByWidth(title.string, b.mid - b.x - 30), b.x, y + 3, 0xFFFFFFFF.toInt(), true)
        if (right != null) g.drawString(font, right, b.mid - 14 - font.width(right), y + 3, 0xFFFFE9A8.toInt(), true)
    }

    /**
     * A bookmark tab sticking out of the book's [left] (or right) edge, [i]-th from the top, with an item for an
     * icon. The open one sticks out further and is brighter.
     */
    protected fun bookmark(g: GuiGraphics, b: Book, i: Int, icon: ItemStack, active: Boolean, label: Component, left: Boolean = true, action: () -> Unit) {
        val ty = b.y + 34 + i * 23
        val out = if (active) 26 else 22
        val x1 = if (left) b.x - out else b.x + b.w - 2
        val x2 = if (left) b.x + 2 else b.x + b.w - 2 + out
        val hot = hover(x1, ty, x2 - x1, 20)
        g.fill(x1 + 1, ty, x2 - 1, ty + 20, Pal.COVER_DARK); g.fill(x1, ty + 1, x2, ty + 19, Pal.COVER_DARK)
        g.fill(x1 + 1, ty + 1, x2 - 1, ty + 19, if (active || hot) Pal.TAB_ACTIVE else Pal.TAB)
        g.fill(x1 + 2, ty + 1, x2 - 2, ty + 2, 0x40FFFFFF)
        icon(g, icon, if (left) x1 + 4 else x2 - 20, ty + 2)
        if (hot) tip = listOf(label)
        click(x1, ty, x2 - x1, 20) { action() }
    }

    /** A heading in ink, centred on [cx], cut to a page's width. */
    protected fun heading(g: GuiGraphics, c: Component, cx: Int, y: Int, color: Int = Pal.INK) {
        val s = c.copy().withStyle(ChatFormatting.BOLD)
        val max = layout().pw
        if (font.width(s) <= max) { g.drawString(font, s, cx - font.width(s) / 2, y, color, false); return }
        // Measured in bold: bold letters are a pixel wider, so a cut measured in plain text still ran off the page.
        var t = c.string
        fun cut() = Component.literal("$t...").withStyle(ChatFormatting.BOLD)
        while (t.isNotEmpty() && font.width(cut()) > max) t = t.dropLast(1)
        g.drawString(font, cut(), cx - font.width(cut()) / 2, y, color, false)
    }

    /** A boxed line of a list: a light card with a brown edge. */
    protected fun row(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, hot: Boolean = false, selected: Boolean = false) {
        g.fill(x, y, x + w, y + h, Pal.ROW_EDGE)
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, if (selected) Pal.ROW_SEL else if (hot) Pal.ROW_HOT else Pal.ROW)
    }

    /** Text wrapped to [w] pixels, at most [maxLines] lines. Returns the height used. */
    protected fun para(g: GuiGraphics, c: Component, x: Int, y: Int, w: Int, color: Int = Pal.INK, maxLines: Int = 20): Int {
        val lines = font.split(c, w).take(maxLines)
        for ((i, l) in lines.withIndex()) g.drawString(font, l, x, y + i * 10, color, false)
        return lines.size * 10
    }

    /** A tiny Poke Ball, 7 pixels across. */
    protected fun pokeball(g: GuiGraphics, x: Int, y: Int) {
        g.fill(x + 1, y, x + 6, y + 7, Pal.BALL_BLACK); g.fill(x, y + 1, x + 7, y + 6, Pal.BALL_BLACK)
        g.fill(x + 1, y + 1, x + 6, y + 3, Pal.BALL_RED)
        g.fill(x + 1, y + 4, x + 6, y + 6, Pal.BALL_WHITE)
        g.fill(x + 3, y + 3, x + 4, y + 4, Pal.BALL_WHITE)
    }

    /** One of the game's advancement frames: "task", "goal" or "challenge", gold once obtained. */
    protected fun advFrame(g: GuiGraphics, x: Int, y: Int, kind: String, obtained: Boolean) {
        g.blitSprite(ResourceLocation.withDefaultNamespace("advancements/${kind}_frame_${if (obtained) "obtained" else "unobtained"}"), x, y, 26, 26)
    }

    /** A connector between two frames, in ink: brown with a gold core once the step before it is done. */
    protected fun advLine(g: GuiGraphics, x1: Int, y1: Int, x2: Int, y2: Int, lit: Boolean) {
        val inner = if (lit) Pal.BTN_ACCENT else Pal.STAR_OFF
        val mid = (y1 + y2) / 2
        for ((pad, color) in listOf(1 to Pal.BTN_EDGE, 0 to inner)) {
            fun seg(ax: Int, ay: Int, bx: Int, by: Int) =
                g.fill(minOf(ax, bx) - pad, minOf(ay, by) - pad, maxOf(ax, bx) + pad + 1, maxOf(ay, by) + pad + 1, color)
            if (y1 == y2) seg(x1, y1, x2, y2) else { seg(x1, y1, x1, mid); seg(x1, mid, x2, mid); seg(x2, mid, x2, y2) }
        }
    }

    /** A small label on a light card, centred on [cx]. */
    protected fun plate(g: GuiGraphics, s: String, cx: Int, y: Int, color: Int) {
        val w = font.width(s)
        g.fill(cx - w / 2 - 3, y - 2, cx + w / 2 + 3, y + 9, Pal.ROW_EDGE)
        g.fill(cx - w / 2 - 2, y - 1, cx + w / 2 + 2, y + 8, Pal.ROW)
        g.drawString(font, s, cx - w / 2, y, color, false)
    }

    protected fun text(g: GuiGraphics, c: Component, x: Int, y: Int, color: Int = Pal.INK) { g.drawString(font, c, x, y, color, false) }
    protected fun text(g: GuiGraphics, s: String, x: Int, y: Int, color: Int = Pal.INK) { g.drawString(font, s, x, y, color, false) }

    /** Text cut to [w] pixels with an ellipsis. */
    protected fun clip(g: GuiGraphics, c: Component, x: Int, y: Int, w: Int, color: Int = Pal.INK) {
        if (font.width(c) <= w) { text(g, c, x, y, color); return }
        text(g, font.plainSubstrByWidth(c.string, w - 6) + "...", x, y, color)
    }

    /** A cream button with a brown edge. [accent]: gold, for the main action or the one picked. [danger]: red. */
    protected fun button(
        g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, label: Component,
        enabled: Boolean = true, accent: Boolean = false, tooltip: List<Component>? = null, danger: Boolean = false, action: () -> Unit,
    ) {
        val hot = hover(x, y, w, h)
        val bg = when {
            !enabled -> Pal.BTN_OFF
            danger -> Pal.BTN_DANGER
            accent -> if (hot) Pal.BTN_ACCENT_HOT else Pal.BTN_ACCENT
            else -> if (hot) Pal.BTN_HOT else Pal.BTN
        }
        g.fill(x + 1, y, x + w - 1, y + h, Pal.BTN_EDGE); g.fill(x, y + 1, x + w, y + h - 1, Pal.BTN_EDGE)
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg)
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, if (danger) 0xFF9E3528.toInt() else Pal.BTN_SHADE)
        val s = font.plainSubstrByWidth(label.string, w - 4)
        val tc = if (!enabled) Pal.MUTED else if (danger) 0xFFFFFFFF.toInt() else Pal.INK
        if (s.length == label.string.length) g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 7) / 2, tc, false)
        else g.drawString(font, s, x + (w - font.width(s)) / 2, y + (h - 7) / 2, tc, false)
        if (hot && tooltip != null) tip = tooltip
        if (enabled) click(x, y, w, h) { action() }
    }

    protected fun icon(g: GuiGraphics, stack: ItemStack, x: Int, y: Int, scale: Float = 1f) {
        if (scale == 1f) { g.renderItem(stack, x, y); return }
        g.pose().pushPose()
        g.pose().translate(x.toFloat(), y.toFloat(), 0f)
        g.pose().scale(scale, scale, 1f)
        g.renderItem(stack, 0, 0)
        g.pose().popPose()
    }

    protected fun bar(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, frac: Double, color: Int) {
        g.fill(x, y, x + w, y + h, Pal.BAR_BG)
        g.fill(x, y, x + (w * frac.coerceIn(0.0, 1.0)).toInt(), y + h, color)
    }

    protected fun stars(g: GuiGraphics, x: Int, y: Int, n: Int) {
        for (i in 0 until 5) g.drawString(font, "\u2605", x + i * 7, y, if (i < n) Pal.STAR else Pal.STAR_OFF, false)
    }

    /** Requested items as icons with how many are wanted, in a row up to [w] wide. Returns true if there were any. */
    protected fun requestIcons(g: GuiGraphics, list: net.minecraft.nbt.ListTag, x: Int, y: Int, w: Int): Boolean {
        for (i in 0 until list.size) {
            val ix = x + i * 34
            if (ix + 32 > x + w) break
            val r = list.getCompound(i)
            val st = MonIcons.item(r.getString("item"))
            icon(g, st, ix, y)
            text(g, "x${r.getInt("count")}", ix + 17, y + 5, Pal.INK)
            if (hover(ix, y, 32, 16)) tip = listOf(st.hoverName, Component.translatable("foremon.gui.requested", r.getInt("count")))
        }
        return list.size > 0
    }

    protected fun lines(vararg c: Component): List<Component> = c.toList()
    protected fun muted(c: Component): Component = c.copy().withStyle(ChatFormatting.GRAY)
}
