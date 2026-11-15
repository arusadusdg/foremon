"""Copies footage and stills into public/ for the video.

Footage: the UI probe's frames (FOREMON_PROBE=gif and =house, run/screenshots/gif_<name>_NNN.png) as JPGs.
Stills: the mod page shots in docs/media/cf and the mod icon. Writes src/footage.json with the frame counts.
Run: python video/scripts/prep.py
"""
import glob
import json
import os
import shutil

from PIL import Image

VIDEO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(VIDEO)
counts = {}
for name in ("house", "timber", "build"):
    out = os.path.join(VIDEO, "public", "footage", name)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    frames = sorted(glob.glob(os.path.join(REPO, "run", "screenshots", f"gif_{name}_*.png")))
    for i, f in enumerate(frames):
        Image.open(f).convert("RGB").save(os.path.join(out, f"{i:03d}.jpg"), quality=90)
    counts[name] = len(frames)
img = os.path.join(VIDEO, "public", "img")
os.makedirs(img, exist_ok=True)
for k in ("crew", "jobs", "pc", "map", "skills", "perks", "dex"):
    shutil.copy(os.path.join(REPO, "docs", "media", "cf", f"shot_{k}.png"), os.path.join(img, f"{k}.png"))
shutil.copy(os.path.join(REPO, "docs", "media", "icon-512.png"), os.path.join(img, "icon.png"))
with open(os.path.join(VIDEO, "src", "footage.json"), "w") as f:
    json.dump(counts, f)
print(counts)
