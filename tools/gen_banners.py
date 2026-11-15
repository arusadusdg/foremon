"""Draws the mod page banners (hero + section titles) into docs/media/cf/.

Usage: python tools/gen_banners.py <ascii.png from the Minecraft client jar>
(assets/minecraft/textures/font/ascii.png)
"""
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/media/cf"
FONT = Image.open(sys.argv[1]).convert("RGBA")

LEATHER = (176, 48, 48)
LEATHER_DARK = (150, 38, 38)
EDGE = (74, 16, 16)
GOLD = (224, 176, 64)
CREAM = (246, 234, 208)
SHADOW = (58, 13, 13)
W = 860


def glyph(c):
    n = ord(c)
    x0, y0 = n % 16 * 8, n // 16 * 8
    g = FONT.crop((x0, y0, x0 + 8, y0 + 8))
    if c == " ":
        return g, 4
    cols = [x for x in range(8) if any(g.getpixel((x, y))[3] for y in range(8))]
    return g, (max(cols) + 2 if cols else 4)


def text_width(s, scale):
    return sum(glyph(c)[1] for c in s) * scale - scale


def draw_text(img, s, x, y, scale, color, shadow=SHADOW):
    for dx, dy, col in ((scale, scale, shadow), (0, 0, color)):
        cx = x
        for c in s:
            g, adv = glyph(c)
            mask = g.split()[3].resize((8 * scale, 8 * scale), Image.NEAREST)
            img.paste(Image.new("RGB", mask.size, col), (cx + dx, y + dy), mask)
            cx += adv * scale


def leather(h, seed):
    rnd = random.Random(seed)
    img = Image.new("RGB", (W, h), LEATHER)
    d = ImageDraw.Draw(img)
    for _ in range(W * h // 120):  # leather grain, 4 px blocks
        x, y = rnd.randrange(0, W, 4), rnd.randrange(0, h, 4)
        d.rectangle((x, y, x + 3, y + 3), fill=LEATHER_DARK)
    d.rectangle((0, 0, W - 1, h - 1), outline=EDGE, width=6)
    d.rectangle((10, 10, W - 11, h - 11), outline=GOLD, width=2)
    return img


def pokeball(px):
    """12x12 pixel Poke Ball scaled by px."""
    s = Image.new("RGBA", (12, 12), (0, 0, 0, 0))
    for y in range(12):
        for x in range(12):
            r = ((x - 5.5) ** 2 + (y - 5.5) ** 2) ** 0.5
            if r > 6:
                continue
            col = (20, 20, 20)
            if r < 5:
                col = (226, 58, 58) if y < 5 else (244, 244, 244)
                if 5 <= y <= 6:
                    col = (20, 20, 20)
                if abs(x - 5.5) < 2 and abs(y - 5.5) < 2:
                    col = (20, 20, 20) if (abs(x - 5.5) > 1 or abs(y - 5.5) > 1) else (244, 244, 244)
            s.putpixel((x, y), col + (255,))
    return s.resize((12 * px, 12 * px), Image.NEAREST)


def section(title, name):
    h = 72
    img = leather(h, name)
    scale = 4
    tw = text_width(title, scale)
    x = (W - tw) // 2
    draw_text(img, title, x, (h - 8 * scale) // 2, scale, CREAM)
    ball = pokeball(3)
    by = (h - ball.height) // 2
    img.paste(ball, (x - ball.width - 28, by), ball)
    img.paste(ball, (x + tw + 28, by), ball)
    img.save(OUT / f"{name}.png")


def hero():
    h = 260
    img = leather(h, "hero")
    icon = Image.open(ROOT / "docs/media/icon-512.png").convert("RGBA").resize((176, 176), Image.NEAREST)
    img.paste(icon, (42, (h - 176) // 2), icon)
    x = 250
    draw_text(img, "FOREMON", x, 58, 9, CREAM)
    draw_text(img, "Put your Cobblemon to work", x + 4, 150, 3, CREAM)
    draw_text(img, "Cobblemon addon for Fabric 1.21.1", x + 4, 192, 2, GOLD, shadow=EDGE)
    img.save(OUT / "hero.png")


OUT.mkdir(parents=True, exist_ok=True)
hero()
for t in ["Features", "Jobs", "Screenshots", "Getting started", "Server owners", "Requirements", "Links"]:
    section(t.upper(), t.lower().replace(" ", "_"))
print("wrote", sorted(p.name for p in OUT.glob("*.png")))
