# Foremon TikTok (Remotion)

Source for Foremon's vertical promo videos, made with [Remotion](https://remotion.dev) and the
`remotion-motion-graphics` skill. 1080x1920, 30 fps, 28 s, cut on a 120 BPM beat.

1. Record footage at 1920x1080 (from the repo root): `FOREMON_PROBE=gif ./gradlew runProbe` (timber, quick build)
   and `FOREMON_PROBE=house ./gradlew runProbe` (crew building a village house). Stills come from
   `FOREMON_PROBE=ui` and `docs/media/cf/shot_*.png`.
2. `python video/scripts/prep.py` copies footage and stills into `public/` and writes `src/footage.json`.
3. `node video/scripts/gen-audio.mjs` synthesizes the music bed and sound effects into `public/sfx/`.
4. Voice-over (optional): put an ElevenLabs key in `video/.env` (see `.env.example`), edit the lines in
   `src/vo-script.json`, then `node video/scripts/gen-voice.mjs`. Scenes stretch to fit their line on the beat grid.
   Free keys only get the built-in voices (default Callum); `node video/scripts/check-voice.mjs <voiceId>` tests one.
   Free-plan audio is non-commercial and must credit elevenlabs.io.
5. In `video/`: `npm install`, then `npm run studio` to preview or `npm run render` for `out/foremon-tiktok-2.mp4`.

Generated folders (`public/footage`, `public/img`, `public/sfx`, `out`, `node_modules`) are not in git.
