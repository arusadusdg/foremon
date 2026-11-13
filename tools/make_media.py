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

# ---------------------------------------------------------------- vertical video, 1080x1920 at 20 fps
W, H, FPS = 1080, 1920, 20
tmp = 'run/video_frames'
shutil.rmtree(tmp, ignore_errors=True); os.makedirs(tmp)
big = ImageFont.truetype(FONT_BOLD, 92)
mid = ImageFont.truetype(FONT_BOLD, 64)
small = ImageFont.truetype(FONT, 40)
RED, WHITE, DARK = (224, 58, 58), (255, 255, 255), (20, 24, 32)


def canvas():
    c = Image.new('RGB', (W, H), DARK)
    d = ImageDraw.Draw(c)
    d.rectangle((0, 0, W, 210), fill=RED)          # Poke Ball top
    d.rectangle((0, 210, W, 226), fill=(20, 20, 20))
    return c, d


def centered(d, y, text, font, fill=WHITE):
    w = d.textlength(text, font=font)
    d.text(((W - w) / 2, y), text, font=font, fill=fill, stroke_width=4, stroke_fill=(0, 0, 0))


n = 0


def emit(img, times=1):
    global n
    for _ in range(times):
        img.save(f'{tmp}/{n:05d}.png'); n += 1


def card(lines, seconds, logo=False):
    c, d = canvas()
    if logo:
        icon = Image.open(f'{OUT}/icon-512.png').resize((520, 520), Image.NEAREST)
        c.paste(icon, ((W - 520) // 2, 520), icon)
    y = 300 if not logo else 1120
    for text, font in lines:
        centered(d, y, text, font); y += font.size + 40
    emit(c, int(seconds * FPS))


def clip(files, top, bottom, repeat=1, hold=0):
    for i, f in enumerate(files):
        c, d = canvas()
        im = Image.open(f).convert('RGB')
        im = im.resize((W, round(im.height * W / im.width)), Image.LANCZOS)
        im = im.crop((0, 0, W, im.height))
        # Zoom the game picture to fill the middle of the screen.
        zoom = 1.6
        zw, zh = round(W * zoom), round(im.height * zoom)
        im = im.resize((zw, zh), Image.LANCZOS).crop(((zw - W) // 2, 0, (zw - W) // 2 + W, zh))
        c.paste(im, (0, (H - im.height) // 2))
        centered(d, 70, top, mid)
        centered(d, H - 300, bottom, small)
        emit(c, hold if (hold and i == len(files) - 1) else repeat)


card([('My Pokemon', big), ('got jobs', big)], 1.6)
if timber_clip: clip(timber_clip, 'Level 55 Machop', 'The whole tree, leaves and all, in one swing', repeat=3, hold=30)
if house: clip(house[::2], 'Building from Litematica', 'A crew of three builds a real village house', repeat=1, hold=40)
card([('Foremon', big), ('Free on Modrinth', mid), ('and CurseForge', mid), ('Made with AI assistance', small)], 3.0, logo=True)

import imageio_ffmpeg
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
out = f'{OUT}/foremon-tiktok.mp4'
subprocess.run([ffmpeg, '-y', '-loglevel', 'error', '-framerate', str(FPS), '-i', f'{tmp}/%05d.png',
                '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '20', '-movflags', '+faststart', out], check=True)
print(out, n, 'frames,', round(n / FPS, 1), 's,', os.path.getsize(out) // 1024, 'KB')
shutil.rmtree(tmp, ignore_errors=True)
