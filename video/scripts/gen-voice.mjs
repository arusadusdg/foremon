// Makes the voice-over with ElevenLabs, one MP3 per scene, plus word timings for captions.
// Works on the free plan: premade voices only (the Voice Library isn't available to free accounts over the API),
// about 400 credits per run of the whole script. Free-plan audio is non-commercial and needs "elevenlabs.io" credited.
//
// Needs ELEVENLABS_API_KEY in video/.env (git ignores it). Optional: ELEVENLABS_VOICE=<premade voice name>, or
// ELEVENLABS_VOICE_ID=<id> (Voice Library voices need a paid plan over the API).
// Each line in src/vo-script.json may set "model" and "settings"; if that model fails, "fallback" text runs on v2.
// Run: node video/scripts/gen-voice.mjs            (list voices: node video/scripts/gen-voice.mjs --voices)
// Writes public/vo/<Scene>.mp3 and src/vo.json: [{ scene, file, duration, words: [{ w, start, end }] }].
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const envFile = join(root, ".env");
if (!existsSync(envFile)) throw new Error("No video/.env yet: copy video/.env.example to video/.env and put your key in it.");
const env = Object.fromEntries(readFileSync(envFile, "utf8").split(/\r?\n/).filter((l) => l.includes("=") && !l.trim().startsWith("#"))
  .map((l) => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1).trim()]));
const KEY = env.ELEVENLABS_API_KEY;
if (!KEY || KEY.startsWith("paste")) throw new Error("ELEVENLABS_API_KEY is empty in video/.env");
const API = "https://api.elevenlabs.io/v1";
const headers = { "xi-api-key": KEY, "content-type": "application/json" };

const res = await fetch(`${API}/voices`, { headers });
if (!res.ok) throw new Error(`ElevenLabs /voices: ${res.status} ${await res.text()}`);
const premade = (await res.json()).voices.filter((v) => v.category === "premade");
if (process.argv.includes("--voices")) {
  for (const v of premade) console.log(v.name.padEnd(12), Object.values(v.labels ?? {}).join(", "));
  process.exit(0);
}
// Default: Callum, the closest free voice to "Joe - Deep Villain" (RsWhJadjwfxA0DsrtYJe, Voice Library, paid plans only).
const want = (env.ELEVENLABS_VOICE || "Callum").toLowerCase();
const voice = env.ELEVENLABS_VOICE_ID ? { voice_id: env.ELEVENLABS_VOICE_ID, name: env.ELEVENLABS_VOICE_ID }
  : premade.find((v) => v.name.toLowerCase().startsWith(want)) ?? premade[0];
console.log("voice:", voice.name);

const lines = JSON.parse(readFileSync(join(root, "src", "vo-script.json"), "utf8"));
const outDir = join(root, "public", "vo");
mkdirSync(outDir, { recursive: true });
const result = [];
const tts = (text, model, settings) => fetch(`${API}/text-to-speech/${voice.voice_id}/with-timestamps?output_format=mp3_44100_128`, {
  method: "POST", headers,
  body: JSON.stringify({ text, model_id: model, voice_settings: settings ?? { stability: 0.4, similarity_boost: 0.8, style: 0.35, use_speaker_boost: true } }),
});
let used = 0;
for (const { scene, text, model = "eleven_multilingual_v2", settings, fallback } of lines) {
  let r = await tts(text, model, settings);
  used += text.length;
  if (!r.ok && fallback) {
    console.log(`${scene}: ${model} failed (${r.status} ${(await r.text()).slice(0, 160)}), using the fallback on v2`);
    r = await tts(fallback, "eleven_multilingual_v2", settings && { ...settings, stability: Math.max(settings.stability, 0.25) });
    used += fallback.length;
  }
  if (!r.ok) throw new Error(`ElevenLabs TTS for ${scene}: ${r.status} ${await r.text()}`);
  const { audio_base64, alignment } = await r.json();
  writeFileSync(join(outDir, `${scene}.mp3`), Buffer.from(audio_base64, "base64"));
  // Characters -> words, each with when it starts and ends.
  const words = [];
  let cur = null;
  alignment.characters.forEach((ch, i) => {
    if (/\s/.test(ch)) { if (cur) words.push(cur); cur = null; return; }
    if (!cur) cur = { w: "", start: alignment.character_start_times_seconds[i], end: 0 };
    cur.w += ch;
    // Ends at its last letter or digit: ElevenLabs stretches trailing punctuation over the pause that follows.
    if (/[\p{L}\p{N}]/u.test(ch) || !cur.end) cur.end = alignment.character_end_times_seconds[i];
  });
  if (cur) words.push(cur);
  // Audio tags like [shouting] are directions, not words.
  for (let i = words.length - 1; i >= 0; i--) if (/^\[.*\]$/.test(words[i].w)) words.splice(i, 1);
  const duration = alignment.character_end_times_seconds.at(-1);
  result.push({ scene, file: `vo/${scene}.mp3`, duration, words });
  console.log(scene.padEnd(7), duration.toFixed(2) + "s", text);
}
writeFileSync(join(root, "src", "vo.json"), JSON.stringify(result, null, 2));
console.log(`done: about ${used} credits used (free plan: 10,000 a month)`);
