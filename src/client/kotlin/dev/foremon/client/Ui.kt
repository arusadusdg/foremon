package dev.foremon.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.item.PokemonItem
import dev.foremon.net.Net
import dev.foremon.rules.Job
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
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

/** Colours used by every screen, so the mod has one look. */
object Pal {
    const val DIM = 0xB0080A0E.toInt()
    const val PANEL = 0xFF161B22.toInt()
    const val CARD = 0xFF1F2630.toInt()
    const val CARD_HOVER = 0xFF2A3442.toInt()
    const val LINE = 0xFF34404F.toInt()
    const val ACCENT = 0xFFE5533D.toInt()
    const val ACCENT_HOVER = 0xFFF26B55.toInt()
    const val GOOD = 0xFF4CC38A.toInt()
    const val WARN = 0xFFE9B949.toInt()
    const val BAD = 0xFFE5533D.toInt()
    const val TEXT = 0xFFE6EDF3.toInt()
    const val MUTED = 0xFF8B98A8.toInt()
    const val STAR = 0xFFF7C948.toInt()
    const val STAR_OFF = 0xFF3A4452.toInt()
    const val BALL_RED = 0xFFE03A3A.toInt()
    const val BALL_WHITE = 0xFFF4F4F4.toInt()
    const val BALL_BLACK = 0xFF141414.toInt()
    // The blues of the Pokemon party menu and the greens of a PC box.
    const val POKE_PANEL = 0xFF2A6FB5.toInt()
    const val POKE_PANEL_HI = 0xFF4A93DC.toInt()
    const val POKE_PANEL_HOT = 0xFF4E9BE6.toInt()
    const val POKE_PANEL_HOT_HI = 0xFF7DBCF5.toInt()
    const val POKE_EDGE = 0xFF0F2F57.toInt()
    const val POKE_TEXT_SOFT = 0xFFD3E6FA.toInt()
    const val BOX_HEADER = 0xFF2E9E5B.toInt()
    const val BOX_HEADER_HI = 0xFF4CC07A.toInt()
    const val BOX_A = 0xFFCDEED4.toInt()
    const val BOX_B = 0xFFB9E3C2.toInt()
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
    ClientPlayNetworking.send(Net.Action(CompoundTag().also { it.putString("op", op); fill(it) }))

val JOB_ICONS = mapOf(
    Job.LUMBER to Items.IRON_AXE, Job.MINING to Items.IRON_PICKAXE, Job.SORTING to Items.HOPPER, Job.HAULING to Items.CHEST_MINECART,
    Job.FARMING to Items.IRON_HOE, Job.SMELTING to Items.BLAST_FURNACE, Job.BUILDING to Items.BRICKS, Job.DEMOLITION to Items.TNT,
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

/**
 * Base for all screens. Immediate-mode: a screen describes itself from scratch every frame in [draw],
 * and every button it draws registers its own click area for that frame. No widget bookkeeping,
 * so the screen can never show stale state after the server sends an update.
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
        for (h in hits.asReversed()) if (x >= h.x && x < h.x + h.w && y >= h.y && y < h.y + h.h) {
            mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
            h.action(button)
            return true
        }
        return super.mouseClicked(x, y, button)
    }

    protected fun hover(x: Int, y: Int, w: Int, h: Int) = mx >= x && mx < x + w && my >= y && my < y + h

    protected fun click(x: Int, y: Int, w: Int, h: Int, action: (Int) -> Unit) { hits.add(Hit(x, y, w, h, action)) }

    protected fun panel(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, color: Int = Pal.PANEL) {
        g.fill(x, y, x + w, y + h, color)
        g.renderOutline(x, y, w, h, Pal.LINE)
    }

    /** The main window of a screen: a panel with a Poke Ball seam and button along its top edge. */
    protected fun window(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        g.fill(x, y - 5, x + w, y - 1, Pal.BALL_RED)
        g.fill(x, y - 6, x + w, y - 5, Pal.BALL_BLACK)
        g.fill(x - 1, y - 6, x, y, Pal.BALL_BLACK); g.fill(x + w, y - 6, x + w + 1, y, Pal.BALL_BLACK)
        g.fill(x, y - 1, x + w, y + 1, Pal.BALL_BLACK)
        panel(g, x, y, w, h)
        val cx = x + w / 2
        g.fill(cx - 6, y - 7, cx + 6, y + 5, Pal.BALL_BLACK)
        g.fill(cx - 5, y - 6, cx + 5, y + 4, Pal.BALL_WHITE)
        g.fill(cx - 3, y - 4, cx + 3, y + 2, Pal.BALL_BLACK)
        g.fill(cx - 2, y - 3, cx + 2, y + 1, Pal.BALL_WHITE)
    }

    /** A panel in the style of the Pokemon party menu: blue, lighter along the top, with a dark edge. */
    protected fun pokePanel(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, hot: Boolean) {
        g.fill(x, y, x + w, y + h, Pal.POKE_EDGE)
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, if (hot) Pal.POKE_PANEL_HOT else Pal.POKE_PANEL)
        g.fill(x + 1, y + 1, x + w - 1, y + 1 + (h - 2) / 3, if (hot) Pal.POKE_PANEL_HOT_HI else Pal.POKE_PANEL_HI)
    }

    /** A tiny Poke Ball, 7 pixels across. */
    protected fun pokeball(g: GuiGraphics, x: Int, y: Int) {
        g.fill(x + 1, y, x + 6, y + 7, Pal.BALL_BLACK); g.fill(x, y + 1, x + 7, y + 6, Pal.BALL_BLACK)
        g.fill(x + 1, y + 1, x + 6, y + 3, Pal.BALL_RED)
        g.fill(x + 1, y + 4, x + 6, y + 6, Pal.BALL_WHITE)
        g.fill(x + 3, y + 3, x + 4, y + 4, Pal.BALL_WHITE)
    }

    /** A tiled background like the advancements screen, using the game's own advancement backgrounds. */
    protected fun advBackground(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, name: String) {
        val tex = ResourceLocation.withDefaultNamespace("textures/gui/advancements/backgrounds/$name.png")
        g.enableScissor(x, y, x + w, y + h)
        var ty = y
        while (ty < y + h) {
            var tx = x
            while (tx < x + w) { g.blit(tex, tx, ty, 0f, 0f, 16, 16, 16, 16); tx += 16 }
            ty += 16
        }
        g.disableScissor()
        g.fill(x, y, x + w, y + h, 0x48000000)
        g.renderOutline(x, y, w, h, 0xFF000000.toInt())
        g.renderOutline(x + 1, y + 1, w - 2, h - 2, 0xFF555555.toInt())
    }

    /** One of the game's advancement frames: "task", "goal" or "challenge", gold once obtained. */
    protected fun advFrame(g: GuiGraphics, x: Int, y: Int, kind: String, obtained: Boolean) {
        g.blitSprite(ResourceLocation.withDefaultNamespace("advancements/${kind}_frame_${if (obtained) "obtained" else "unobtained"}"), x, y, 26, 26)
    }

    /** A connector between two frames, drawn like the advancements screen: black with a light core. */
    protected fun advLine(g: GuiGraphics, x1: Int, y1: Int, x2: Int, y2: Int, lit: Boolean) {
        val inner = if (lit) 0xFFFFFFFF.toInt() else 0xFF7A7A7A.toInt()
        val mid = (y1 + y2) / 2
        for ((pad, color) in listOf(1 to 0xFF000000.toInt(), 0 to inner)) {
            fun seg(ax: Int, ay: Int, bx: Int, by: Int) =
                g.fill(minOf(ax, bx) - pad, minOf(ay, by) - pad, maxOf(ax, bx) + pad + 1, maxOf(ay, by) + pad + 1, color)
            if (y1 == y2) seg(x1, y1, x2, y2) else { seg(x1, y1, x1, mid); seg(x1, mid, x2, mid); seg(x2, mid, x2, y2) }
        }
    }

    /** Text on a dark plate, so it stays readable on a textured background. */
    protected fun plate(g: GuiGraphics, s: String, cx: Int, y: Int, color: Int) {
        val w = font.width(s)
        g.fill(cx - w / 2 - 2, y - 1, cx + w / 2 + 2, y + 9, 0xC0000000.toInt())
        g.drawString(font, s, cx - w / 2, y, color, false)
    }

    protected fun text(g: GuiGraphics, c: Component, x: Int, y: Int, color: Int = Pal.TEXT) { g.drawString(font, c, x, y, color, false) }
    protected fun text(g: GuiGraphics, s: String, x: Int, y: Int, color: Int = Pal.TEXT) { g.drawString(font, s, x, y, color, false) }

    /** Text cut to [w] pixels with an ellipsis. */
    protected fun clip(g: GuiGraphics, c: Component, x: Int, y: Int, w: Int, color: Int = Pal.TEXT) {
        if (font.width(c) <= w) { text(g, c, x, y, color); return }
        text(g, font.plainSubstrByWidth(c.string, w - 6) + "...", x, y, color)
    }

    protected fun button(
        g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, label: Component,
        enabled: Boolean = true, accent: Boolean = false, tooltip: List<Component>? = null, action: () -> Unit,
    ) {
        val hot = hover(x, y, w, h)
        val bg = when {
            !enabled -> 0xFF161B22.toInt()
            accent -> if (hot) Pal.ACCENT_HOVER else Pal.ACCENT
            else -> if (hot) Pal.CARD_HOVER else Pal.CARD
        }
        g.fill(x, y, x + w, y + h, bg)
        g.renderOutline(x, y, w, h, if (hot && enabled) Pal.TEXT else Pal.LINE)
        val tw = font.width(label)
        g.drawString(font, label, x + (w - tw) / 2, y + (h - 8) / 2, if (enabled) Pal.TEXT else Pal.MUTED, false)
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
        g.fill(x, y, x + w, y + h, 0xFF0E1217.toInt())
        g.fill(x, y, x + (w * frac.coerceIn(0.0, 1.0)).toInt(), y + h, color)
    }

    protected fun stars(g: GuiGraphics, x: Int, y: Int, n: Int) {
        for (i in 0 until 5) g.drawString(font, "★", x + i * 7, y, if (i < n) Pal.STAR else Pal.STAR_OFF, false)
    }

    protected fun lines(vararg c: Component): List<Component> = c.toList()
    protected fun muted(c: Component): Component = c.copy().withStyle(ChatFormatting.GRAY)
}
