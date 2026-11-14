"""Turns the self-driving game's frames into the mod page GIFs, the big icon, the screenshots and a vertical
TikTok/Shorts video. Usage: make_media.py  (run from the project folder after `FOREMON_PROBE=gif` and
`FOREMON_PROBE=house` runs of the probe client)."""
import glob, os, shutil, subprocess
from PIL import Image, ImageDraw, ImageFont, ImageChops

SHOTS = 'run/screenshots'
OUT = 'docs/media'
os.makedirs(OUT, exist_ok=True)
FONT_BOLD = 'C:/Windows/Fonts/arialbd.ttf'
FONT = 'C:/Windows/Fonts/arial.ttf'


def frames(prefix):
    return sorted(glob.glob(f'{SHOTS}/gif_{prefix}_*.png'))


def busiest_window(files, length):
    """The run of [length] frames where the most changes happen (where the tree comes down)."""
    small = [Image.open(f).convert('L').resize((160, 90)) for f in files]
    change = [0] + [sum(1 for p in ImageChops.difference(a, b).get_flattened_data() if p > 30) for a, b in zip(small, small[1:])]
    best, at = -1, 0
    for i in range(0, max(1, len(files) - length)):
        s = sum(change[i:i + length])
        if s > best: best, at = s, i
    return files[at:at + length]


def gif(files, out, width, ms, hold_last=0):
    imgs = []
    for f in files:
        im = Image.open(f).convert('RGB')
        im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
        imgs.append(im.convert('P', palette=Image.ADAPTIVE, colors=128))
    durations = [ms] * len(imgs)
    if hold_last: durations[-1] = hold_last
    imgs[0].save(out, save_all=True, append_images=imgs[1:], duration=durations, loop=0, optimize=True)
    print(out, len(imgs), 'frames', os.path.getsize(out) // 1024, 'KB')


timber = frames('timber')
house = frames('house')
timber_clip = busiest_window(timber, 28) if timber else []
if timber_clip: gif(timber_clip, f'{OUT}/timber.gif', 480, 110, hold_last=1500)
# The house: every second frame, as a time-lapse, holding on the finished house.
house_clip = house[::2]
if house_clip: gif(house_clip, f'{OUT}/build.gif', 480, 70, hold_last=2000)

# Icon at 512 for the mod pages, scaled crisply from the 128 one.
Image.open('src/main/resources/assets/foremon/icon.png').resize((512, 512), Image.NEAREST).save(f'{OUT}/icon-512.png')

# Screenshots for the gallery.
for src, dst in [('probe_02_site_assigned.png', 'screenshot-site.png'), ('probe_21c_chests_ball_changed.png', 'screenshot-chests.png'),
                 ('probe_26_perks_lumber.png', 'screenshot-perks.png'), ('probe_25_skills_labour.png', 'screenshot-skills.png'),
                 ('probe_50_worker_tooltip.png', 'screenshot-tooltip.png'), ('probe_28_pc_box.png', 'screenshot-pc.png'),
                 ('probe_13_dex.png', 'screenshot-dex.png')]:
    if os.path.exists(f'{SHOTS}/{src}'): shutil.copy(f'{SHOTS}/{src}', f'{OUT}/{dst}')

# ---------------------------------------------------------------- vertical video, 1080x1920 at 30 fps
# Cuts sit on a 0.5 s grid (120 BPM) so most trending sounds line up; the sound itself is picked in TikTok.
from PIL import ImageFilter
import random
W, H, FPS = 1080, 1920, 30
tmp = 'run/video_frames'
shutil.rmtree(tmp, ignore_errors=True); os.makedirs(tmp)
WHITE, YELLOW, PANEL_Y = (255, 255, 255), (255, 214, 64), 540
_fonts = {}


def text(d, y, s, size, fill=WHITE, age=99):
    """Centred caption that pops in over its first 5 frames, kept clear of TikTok's top tabs and screen edges."""
    size = round(size * (1 + 0.3 * max(0, 1 - age / 5)))
    while True:
        f = _fonts.get(size) or _fonts.setdefault(size, ImageFont.truetype(FONT_BOLD, size))
        w = d.textlength(s, font=f)
        if w <= W - 80: break
        size -= 4
    y += 80
    d.text(((W - w) / 2, y - size / 2), s, font=f, fill=fill, stroke_width=max(4, size // 12), stroke_fill=(0, 0, 0))


def backdrop(im, cx):
    """The same frame, blurred and dimmed, filling the whole phone screen."""
    x0 = min(max(cx - 135, 0), im.width - 270)
    bg = im.crop((x0, 0, x0 + 270, 480)).resize((108, 192)).filter(ImageFilter.GaussianBlur(3)).resize((W, H))
    return Image.eval(bg, lambda v: v // 2)


def game(src, z=1.0, cx=427, cy=240, shake=0, flash=0.0):
    im = Image.open(src).convert('RGB')
    c = backdrop(im, cx)
    s = 480 / z
    x0, y0 = min(max(cx - s / 2, 0), im.width - s), min(max(cy - s / 2, 0), 480 - s)
    panel = im.crop((round(x0), round(y0), round(x0 + s), round(y0 + s))).resize((W, W), Image.LANCZOS)
    c.paste(panel, (random.randint(-shake, shake), PANEL_Y + random.randint(-shake, shake)))
    if flash: c = Image.blend(c, Image.new('RGB', c.size, WHITE), flash)
    return c, ImageDraw.Draw(c)


def ui(src, z):
    im = Image.open(src).convert('RGB')
    c = backdrop(im, 427)
    w = round(W * z); h = round(w * 480 / 854)
    shot = im.resize((w, h), Image.LANCZOS).crop(((w - W) // 2, 0, (w - W) // 2 + W, h))
    c.paste(shot, (0, PANEL_Y + (W - h) // 2))
    return c, ImageDraw.Draw(c)


n = 0


def emit(img):
    global n
    img.save(f'{tmp}/{n:05d}.jpg', quality=92); n += 1


random.seed(7)
HIT, TREE, HOUSE = 24, (420, 200), (450, 230)
if timber:
    for i in range(36):  # hook: the tree still standing, slow push-in
        c, d = game(timber[min(i // 3, HIT - 11)], 1.0 + 0.2 * i / 36, *TREE)
        text(d, 230, 'My Pokémon', 120, age=i); text(d, 370, 'got a JOB', 140, YELLOW, age=i - 6)
        emit(c)
    for i in range(75):  # the swing: 3 output frames per game frame, impact at i = 30
        k = i - 30
        hit = max(0, 1 - k / 8) if k >= 0 else 0
        c, d = game(timber[HIT - 10 + i // 3], 1.2 + 0.25 * hit + 0.1 * i / 75, *TREE, shake=round(14 * hit), flash=0.75 * hit)
        if k < 0: text(d, 230, 'Lv 55 Machop', 110, age=i); text(d, 360, 'vs. a whole tree', 90, age=i - 6)
        else: text(d, 260, 'ONE SWING.', 150, YELLOW, age=k); text(d, 400, 'leaves and all', 90, age=k - 6)
        emit(c)
    for i in range(30):  # the drops fly back to the chest
        c, d = game(timber[min(HIT + 15 + i, len(timber) - 1)], 1.3 - 0.2 * i / 30, *TREE)
        text(d, 260, 'loot goes to the chest', 90, age=i)
        emit(c)
if house:
    clip = house[::3]
    for i, f in enumerate(clip + [clip[-1]] * 15):  # time-lapse, then a short hold on the finished house
        c, d = game(f, 1.0 + 0.3 * min(i, len(clip)) / len(clip), *HOUSE)
        if i < 50: text(d, 230, 'it builds your', 100, age=i); text(d, 360, 'Litematica schematics', 90, YELLOW, age=i - 6)
        elif i < len(clip): text(d, 260, 'block by block', 120, age=i - 50)
        else: text(d, 260, 'real village house', 110, YELLOW, age=i - len(clip))
        emit(c)
for src, line in [('probe_25_skills_labour.png', '38-upgrade skill tree'), ('probe_28_pc_box.png', 'workers from your PC'),
                  ('probe_21c_chests_ball_changed.png', 'Poké Ball chests'), ('probe_26_perks_lumber.png', 'level-up perks')]:
    if not os.path.exists(f'{SHOTS}/{src}'): continue
    for i in range(15):
        c, d = ui(f'{SHOTS}/{src}', 1.0 + 0.06 * i / 15)
        text(d, 300, line, 100, age=i)
        emit(c)
last = Image.open((house or timber)[-1]).convert('RGB')
icon = Image.open(f'{OUT}/icon-512.png').convert('RGBA').resize((420, 420), Image.NEAREST)
for i in range(75):  # end card
    c = backdrop(last, HOUSE[0]); d = ImageDraw.Draw(c)
    c.paste(icon, ((W - 420) // 2, 460), icon)
    text(d, 960, 'FOREMON', 160, YELLOW, age=i)
    text(d, 1110, 'free Cobblemon addon', 80, age=i - 4)
    text(d, 1220, 'Modrinth + CurseForge', 80, age=i - 8)
    text(d, 1340, 'link in bio', 90, YELLOW, age=i - 12)
    text(d, 1460, 'made with AI assistance', 48, age=i - 16)
    emit(c)

import imageio_ffmpeg
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
out = f'{OUT}/foremon-tiktok.mp4'
subprocess.run([ffmpeg, '-y', '-loglevel', 'error', '-framerate', str(FPS), '-i', f'{tmp}/%05d.jpg',
                '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '18', '-movflags', '+faststart', out], check=True)
print(out, n, 'frames,', round(n / FPS, 1), 's,', os.path.getsize(out) // 1024, 'KB')
shutil.rmtree(tmp, ignore_errors=True)
