// Checks the ElevenLabs plan and whether a voice can be used with this key: node scripts/check-voice.mjs <voiceId>
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const env = Object.fromEntries(readFileSync(join(root, ".env"), "utf8").split(/\r?\n/).filter((l) => l.includes("=") && !l.trim().startsWith("#"))
  .map((l) => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1).trim()]));
const headers = { "xi-api-key": env.ELEVENLABS_API_KEY };
const id = process.argv[2];
const get = async (path) => { const r = await fetch(`https://api.elevenlabs.io/v1${path}`, { headers }); return { status: r.status, body: await r.json().catch(() => null) }; };

const sub = await get("/user/subscription");
console.log("plan:", sub.status, sub.body ? { tier: sub.body.tier, used: sub.body.character_count, limit: sub.body.character_limit } : sub.body);
const v = await get(`/voices/${id}`);
console.log("voice:", v.status, v.body?.name ? { name: v.body.name, category: v.body.category, labels: v.body.labels } : v.body?.detail ?? v.body);
// A 2-word test costs ~10 credits and shows whether TTS with this voice is allowed.
const r = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${id}?output_format=mp3_22050_32`, {
  method: "POST", headers: { ...headers, "content-type": "application/json" },
  body: JSON.stringify({ text: "Hey, listen!", model_id: "eleven_multilingual_v2" }),
});
console.log("tts test:", r.status, r.ok ? `${(await r.arrayBuffer()).byteLength} bytes of audio` : JSON.stringify(await r.json().catch(() => null)));
