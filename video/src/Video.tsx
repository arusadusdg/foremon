// The whole TikTok: continuous background and grade, the scenes cut on the beat, voice-over, music and whooshes.
import React from "react";
import { AbsoluteFill, Audio, Sequence, staticFile, useVideoConfig } from "remotion";
import { BgMesh, Grade, Grain, Vignette } from "./components/Layers";
import { Crew, Cta, Guard, Hook, House, Jobs, SceneProps, Skills, Timber, Word } from "./scenes/Scenes";
import { theme } from "./theme";
import voiceOver from "./vo.json";

// Scene, and its shortest length in beats (120 BPM: a beat is half a second). Longer voice lines stretch a scene to the
// next whole beat, so every cut still lands on the music.
export const SCENES: [string, React.FC<SceneProps>, number][] = [
  ["Hook", Hook, 5], ["House", House, 8], ["Crew", Crew, 6], ["Timber", Timber, 6], ["Jobs", Jobs, 6],
  ["Guard", Guard, 6], ["Skills", Skills, 6], ["Cta", Cta, 7],
];
type Vo = { scene: string; file: string; duration: number; words: Word[] };
const VO: Record<string, Vo> = Object.fromEntries((voiceOver as Vo[]).map((v) => [v.scene, v]));

const RATE = 1.2; // voice speed: ffmpeg's atempo keeps the pitch, so faster doesn't sound squeaky
const HOOK_RATE = 1.3; // the shouted opener goes faster still
const LEAD = 2; // frames into a scene before its voice starts
const TAIL = 0.12; // seconds of air after a line before the cut
const PAD = 0.04; // seconds kept around each cut of the voice

/** A piece of a voice line: source seconds [src0, src1], starting [at] seconds into the scene. */
type Seg = { src0: number; src1: number; at: number; rate: number };

/**
 * Pieces of the line to play. The hook drops its opening breath and the pause after "LISTEN!", so "TEAM ROCKET"
 * follows straight on; other lines play whole.
 */
const segmentsOf = (name: string, vo: Vo, fps: number): Seg[] => {
  const lead = LEAD / fps;
  const w = vo.words;
  if (name === "Hook" && w.length > 3) {
    const a = { src0: Math.max(0, w[0].start - 0.1), src1: w[1].end + PAD, at: 0, rate: HOOK_RATE };
    const b = { src0: Math.max(0, w[2].start - PAD), src1: vo.duration + PAD, at: (a.src1 - a.src0) / HOOK_RATE + 0.05, rate: HOOK_RATE };
    return [a, b];
  }
  return [{ src0: 0, src1: vo.duration + PAD, at: lead, rate: RATE }];
};

/** Word timings moved onto the scene's own clock (seconds), after cutting and speeding up. */
const remap = (words: Word[], segs: Seg[]): Word[] =>
  words.flatMap((wd) => {
    const s = segs.find((g) => wd.start >= g.src0 - 0.001 && wd.start <= g.src1);
    return s ? [{ ...wd, start: s.at + (wd.start - s.src0) / s.rate, end: s.at + (wd.end - s.src0) / s.rate }] : [];
  });

export const timeline = (fps: number) => {
  const beat = (fps * 60) / theme.bpm;
  let at = 0;
  return SCENES.map(([name, Scene, minBeats]) => {
    const vo = VO[name];
    const segs = vo ? segmentsOf(name, vo, fps) : [];
    const voEnd = segs.reduce((n, g) => Math.max(n, g.at + (g.src1 - g.src0) / g.rate), 0);
    const beats = Math.max(minBeats, vo ? Math.ceil(((voEnd + TAIL) * fps) / beat) : 0);
    const item = { name, Scene, from: at, dur: Math.round(beats * beat), vo, segs, words: vo ? remap(vo.words, segs) : undefined };
    at += item.dur;
    return item;
  });
};
export const totalFrames = (fps: number) => timeline(fps).reduce((n, s) => n + s.dur, 0);

export const Video: React.FC = () => {
  const { fps, durationInFrames } = useVideoConfig();
  const scenes = timeline(fps);
  const musicFrom = scenes[1].from; // the music drops when the hook ends
  const voice = scenes.flatMap((s) => s.segs.map((g) => [s.from + g.at * fps, s.from + (g.at + (g.src1 - g.src0) / g.rate) * fps]));
  // Music under the voice is ducked; it fades out over the last second. Frame is relative to the music's start.
  const musicVolume = (f: number) => {
    const abs = f + musicFrom;
    const talking = voice.some(([a, b]) => abs >= a - 3 && abs <= b + 3);
    const end = Math.min(1, (durationInFrames - abs) / fps);
    return (talking ? 0.32 : 0.7) * Math.max(0, end);
  };
  return (
    <AbsoluteFill>
      <BgMesh />
      {scenes.map(({ name, Scene, from, dur, words }) => (
        <Sequence key={name} from={from} durationInFrames={dur}>
          <Scene dur={dur} words={words} lead={0} />
        </Sequence>
      ))}
      <Grade />
      <Grain />
      <Vignette />
      {scenes.flatMap(({ name, from, vo, segs }) => segs.map((g, i) => (
        <Sequence key={`vo-${name}-${i}`} from={from + Math.round(g.at * fps)} durationInFrames={Math.ceil(((g.src1 - g.src0) / g.rate) * fps) + 1}>
          <Audio src={staticFile(vo!.file)} playbackRate={g.rate} trimBefore={Math.round(g.src0 * fps)} trimAfter={Math.round(g.src1 * fps)}
            volume={name === "Hook" ? 0.8 : 1} />
        </Sequence>
      )))}
      <Sequence from={musicFrom}>
        <Audio src={staticFile("sfx/loop.wav")} loop volume={musicVolume} />
      </Sequence>
      {scenes.slice(2).map(({ from }) => (
        <Sequence key={`wh-${from}`} from={from - 4} durationInFrames={fps}>
          <Audio src={staticFile("sfx/whoosh.wav")} volume={0.45} />
        </Sequence>
      ))}
    </AbsoluteFill>
  );
};
