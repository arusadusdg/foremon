// Synthesizes the music bed and sound effects as 16-bit mono WAVs into public/sfx/. No downloads.
// Music: an upbeat 120 BPM game-style loop in C major (C, G, Am, F), 4 bars = 8 s, seamless (the video loops it).
// Based on the generator pattern in haidrrrry/claude-remotion-skill's examples/scripts.
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const SR = 44100;
const OUT = join(dirname(fileURLToPath(import.meta.url)), "..", "public", "sfx");
mkdirSync(OUT, { recursive: true });

const wav = (samples, peak = 0.9) => {
  let max = 0;
  for (const s of samples) max = Math.max(max, Math.abs(s));
  const g = max > 0 ? peak / max : 1;
  const buf = Buffer.alloc(44 + samples.length * 2);
  buf.write("RIFF", 0); buf.writeUInt32LE(36 + samples.length * 2, 4); buf.write("WAVE", 8);
  buf.write("fmt ", 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(SR, 24); buf.writeUInt32LE(SR * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34);
  buf.write("data", 36); buf.writeUInt32LE(samples.length * 2, 40);
  samples.forEach((s, i) => buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, s * g)) * 32767), 44 + i * 2));
  return buf;
};

let seed = 7;
const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647 - 0.5; };

// ---- music bed
const BEAT = 0.5;
const DUR = 16 * BEAT;
const N = Math.round(SR * DUR);
const out = new Float32Array(N);
const add = (start, dur, fn) => {
  const s0 = Math.floor(start * SR), n = Math.floor(dur * SR);
  for (let i = 0; i < n && s0 + i < N; i++) out[s0 + i] += fn(i / SR, i / n);
};
const square = (f, t) => { let v = 0; for (let k = 1; k <= 7; k += 2) v += Math.sin(2 * Math.PI * f * k * t) / k; return v; };
const tri = (f, t) => (2 / Math.PI) * Math.asin(Math.sin(2 * Math.PI * f * t));

const kick = (t, v = 0.9) => add(t, 0.25, (ts, p) => Math.sin(2 * Math.PI * (150 - 100 * Math.min(1, ts * 10)) * ts) * Math.exp(-p * 7) * v);
const hat = (t) => add(t, 0.04, (ts, p) => rnd() * 2 * Math.exp(-p * 10) * 0.09);
const clap = (t) => add(t, 0.16, (ts, p) => rnd() * 2 * Math.exp(-p * 9) * 0.22);
const bass = (t, f, d = 0.2) => add(t, d, (ts, p) => square(f, ts) * Math.min(1, ts * 120) * Math.exp(-p * 3) * 0.2);
const pluck = (t, f, d = 0.12) => add(t, d, (ts, p) => tri(f, ts) * Math.min(1, ts * 200) * Math.exp(-p * 5) * 0.1);

// One chord per bar of 4 beats: root (bass) and chord tones for the arpeggio.
const chords = [
  { root: 130.81, tones: [261.63, 329.63, 392.0, 523.25] }, // C
  { root: 98.0, tones: [196.0, 246.94, 293.66, 392.0] }, // G
  { root: 110.0, tones: [220.0, 261.63, 329.63, 440.0] }, // Am
  { root: 87.31, tones: [174.61, 220.0, 261.63, 349.23] }, // F
];
for (let b = 0; b < 16; b++) {
  const t = b * BEAT;
  const ch = chords[Math.floor(b / 4)];
  kick(t);
  hat(t + BEAT / 2);
  if (b % 2 === 1) clap(t);
  // bass: 8th notes, root and octave
  bass(t, ch.root, 0.2);
  bass(t + BEAT / 2, ch.root * 2, 0.18);
  // arpeggio: 16th notes up the chord
  for (let k = 0; k < 4; k++) pluck(t + (k * BEAT) / 4, ch.tones[(b * 4 + k) % 4] * 2, 0.12);
}
// soft pad, one chord per bar, faded at bar edges so the loop point doesn't click
for (let bar = 0; bar < 4; bar++) add(bar * 4 * BEAT, 4 * BEAT, (ts, p) => {
  const ch = chords[bar];
  const env = Math.min(1, p * 80, (1 - p) * 80);
  return (Math.sin(2 * Math.PI * ch.tones[0] * ts) + Math.sin(2 * Math.PI * ch.tones[1] * ts)) * 0.03 * env;
});
writeFileSync(join(OUT, "loop.wav"), wav(out, 0.85));

// ---- sound effects
const fx = (dur, fn) => { const n = Math.round(dur * SR); const a = new Float32Array(n); for (let i = 0; i < n; i++) a[i] = fn(i / SR, i / n); return a; };
let lp = 0;
writeFileSync(join(OUT, "whoosh.wav"), wav(fx(0.35, (ts, p) => {
  const env = Math.sin(Math.PI * Math.pow(p, 0.6)) ** 2;
  lp += (0.04 + 0.3 * Math.sin(Math.PI * p)) * (rnd() * 2 - lp);
  return lp * env;
}), 0.8));
let ph = 0;
writeFileSync(join(OUT, "pop.wav"), wav(fx(0.12, (ts, p) => { ph += (2 * Math.PI * (760 - 400 * p)) / SR; return Math.sin(ph) * Math.exp(-p * 9); }), 0.8));
writeFileSync(join(OUT, "tick.wav"), wav(fx(0.05, (ts, p) => Math.sin(2 * Math.PI * 1800 * ts) * Math.exp(-p * 14)), 0.5));
writeFileSync(join(OUT, "hit.wav"), wav(fx(0.5, (ts, p) => Math.sin(2 * Math.PI * (88 - 30 * p) * ts) * Math.exp(-p * 6) + rnd() * Math.exp(-p * 30) * 0.4), 0.95));
// riser into the drop after the hook: rising filtered noise and pitch
writeFileSync(join(OUT, "riser.wav"), wav(fx(1.0, (ts, p) => {
  lp += (0.02 + 0.5 * p * p) * (rnd() * 2 - lp);
  return (lp * 0.8 + Math.sin(2 * Math.PI * (200 + 900 * p * p) * ts) * 0.15) * p * p;
}), 0.7));
console.log("audio written to", OUT, "loop", DUR, "s");
