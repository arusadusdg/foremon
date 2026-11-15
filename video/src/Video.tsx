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
  ["Hook", Hook, 6], ["House", House, 10], ["Crew", Crew, 8], ["Timber", Timber, 8], ["Jobs", Jobs, 8],
  ["Guard", Guard, 7], ["Skills", Skills, 7], ["Cta", Cta, 8],
];
const VO = Object.fromEntries((voiceOver as { scene: string; file: string; duration: number; words: Word[] }[]).map((v) => [v.scene, v]));

const LEAD = 3; // frames into a scene before its voice line starts
const TAIL = 0.2; // seconds of air after a line before the cut

export const timeline = (fps: number) => {
  const beat = (fps * 60) / theme.bpm;
  let at = 0;
  return SCENES.map(([name, Scene, minBeats]) => {
    const vo = VO[name];
    const beats = Math.max(minBeats, vo ? Math.ceil((LEAD + (vo.duration + TAIL) * fps) / beat) : 0);
    const item = { name, Scene, from: at, dur: Math.round(beats * beat), vo };
    at += item.dur;
    return item;
  });
};
export const totalFrames = (fps: number) => timeline(fps).reduce((n, s) => n + s.dur, 0);

export const Video: React.FC = () => {
  const { fps, durationInFrames } = useVideoConfig();
  const scenes = timeline(fps);
  const musicFrom = scenes[1].from; // the music drops when the hook ends
  const voice = scenes.filter((s) => s.vo).map((s) => [s.from + LEAD, s.from + LEAD + Math.ceil(s.vo!.duration * fps)]);
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
      {scenes.map(({ name, Scene, from, dur, vo }) => (
        <Sequence key={name} from={from} durationInFrames={dur}>
          <Scene dur={dur} words={vo?.words} lead={LEAD} />
        </Sequence>
      ))}
      <Grade />
      <Grain />
      <Vignette />
      {scenes.filter((s) => s.vo).map(({ name, from, vo }) => (
        <Sequence key={`vo-${name}`} from={from + LEAD}>
          <Audio src={staticFile(vo!.file)} volume={name === "Hook" ? 0.8 : 1} />
        </Sequence>
      ))}
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
