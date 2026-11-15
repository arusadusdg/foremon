// The whole TikTok: continuous background and grade, the seven scenes cut on the beat, music and whooshes.
import React from "react";
import { AbsoluteFill, Audio, Sequence, staticFile, useVideoConfig } from "remotion";
import { BgMesh, Grade, Grain, Vignette } from "./components/Layers";
import { Crew, Cta, Guard, House, Jobs, SceneProps, Skills, Timber } from "./scenes/Scenes";
import { theme } from "./theme";

// Scene lengths in beats (120 BPM: one beat is half a second), so every cut lands on the music.
export const SCENES: [React.FC<SceneProps>, number][] = [
  [House, 10], [Crew, 8], [Timber, 8], [Jobs, 8], [Guard, 7], [Skills, 7], [Cta, 8],
];

export const beatFrames = (fps: number) => (fps * 60) / theme.bpm;
export const totalFrames = (fps: number) => SCENES.reduce((n, [, beats]) => n + Math.round(beats * beatFrames(fps)), 0);

export const Video: React.FC = () => {
  const { fps } = useVideoConfig();
  const starts: number[] = [];
  let at = 0;
  for (const [, beats] of SCENES) { starts.push(at); at += Math.round(beats * beatFrames(fps)); }
  return (
    <AbsoluteFill>
      <BgMesh />
      {SCENES.map(([Scene, beats], i) => {
        const dur = Math.round(beats * beatFrames(fps));
        return (
          <Sequence key={i} from={starts[i]} durationInFrames={dur}>
            <Scene dur={dur} />
          </Sequence>
        );
      })}
      <Grade />
      <Grain />
      <Vignette />
      <Audio src={staticFile("sfx/track.wav")} volume={0.95} />
      {starts.slice(1).map((f) => (
        <Sequence key={f} from={f - 4} durationInFrames={fps}>
          <Audio src={staticFile("sfx/whoosh.wav")} volume={0.55} />
        </Sequence>
      ))}
    </AbsoluteFill>
  );
};
