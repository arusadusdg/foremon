// The beats of the TikTok, cut for a half-second attention span: full-screen shots, zoom punch on every cut, big
// numbers. Words come from the voice-over captions (Video.tsx), except where a scene draws its own.
import React from "react";
import { AbsoluteFill, Audio, Img, Sequence, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { clamp, theme } from "../theme";
import { Chip, Counter, Entrance, Footage, KenBurns, Punch, SceneExit, WordReveal } from "../components/Motion";
import footage from "../footage.json";

export type Word = { w: string; start: number; end: number };
/** [dur]: scene length in frames. [words]: the voice-over's words, in seconds on the scene's own clock. */
export type SceneProps = { dur: number; words?: Word[]; lead?: number };

const useSec = () => {
  const { fps } = useVideoConfig();
  return (sec: number) => Math.round(sec * fps);
};

/** A sound effect starting at frame [at] of the scene (place it 2-3 frames before the visual lands). */
const Sfx: React.FC<{ at: number; name: "whoosh" | "pop" | "tick" | "hit" | "riser"; volume?: number }> = ({ at, name, volume = 0.7 }) => {
  const { fps } = useVideoConfig();
  return (
    <Sequence from={Math.max(0, at)} durationInFrames={fps}>
      <Audio src={staticFile(`sfx/${name}.wav`)} volume={volume} />
    </Sequence>
  );
};

/** A full-screen shot under the captions: darkened at the edges so white words read anywhere. */
const Shot: React.FC<{ children: React.ReactNode; dim?: number }> = ({ children, dim = 0.85 }) => (
  <Punch>
    <AbsoluteFill style={{ filter: `brightness(${dim})` }}>{children}</AbsoluteFill>
    <AbsoluteFill style={{ background: "linear-gradient(180deg, rgba(0,0,0,0.35), transparent 30%, transparent 50%, rgba(0,0,0,0.55) 80%)" }} />
  </Punch>
);

/** A word that slams in from big to normal size, visible from its first frame. */
const Slam: React.FC<{ text: string; at: number; size: number; color: string; glow?: boolean }> = ({ text, at, size, color, glow }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: frame - at, fps, config: { damping: 11, stiffness: 260, mass: 0.6 } });
  return (
    <span style={{ display: "inline-block", fontFamily: theme.fonts.display, fontWeight: 800, fontSize: size, lineHeight: 1, color,
      letterSpacing: "-0.03em", opacity: frame >= at ? 1 : 0, WebkitTextStroke: "12px #000", paintOrder: "stroke fill",
      transform: `scale(${interpolate(p, [0, 1], [1.9, 1], clamp)}) rotate(${interpolate(p, [0, 1], [-8, 0], clamp)}deg)`,
      textShadow: glow ? `0 0 60px ${theme.colors.glow}, 0 10px 0 rgba(0,0,0,0.55)` : "0 10px 0 rgba(0,0,0,0.55)" }}>{text}</span>
  );
};

/**
 * The shouted opener over the tree bursting apart: "HEY," is on screen from frame 0, each word lands on the frame the
 * voice says it. Two flash hits (HEY, TEAM), far under three flashes a second, drawn under the words so they stay sharp.
 */
export const Hook: React.FC<SceneProps> = ({ dur, words = [], lead = 0 }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = useSec();
  const at = (i: number, fallback: number) => Math.max(0, lead + Math.round((words[i]?.start ?? fallback) * fps));
  const hey = 0, listen = at(1, 0.3), team = at(2, 0.8), rocket = at(3, 1.0);
  const rest = ["doesn't", "want", "you", "to", "know", "this..."].map((w, i) => ({ w, f: at(4 + i, 1.3 + i * 0.2) }));
  const flash = Math.max(...[hey, team].map((t) => (frame < t ? 0 : interpolate(frame, [t, t + 6], [0.7, 0], { ...clamp, easing: theme.ease.out }))));
  const shake = [hey, listen, team, rocket].reduce((sum, t) => sum + (frame >= t ? Math.exp(-(frame - t) / 3) : 0), 0);
  const sx = Math.sin(frame * 2.7) * 26 * shake, sy = Math.cos(frame * 3.1) * 20 * shake;
  const up = spring({ frame: frame - team + 4, fps, config: { damping: 16, stiffness: 260, mass: 0.6 } });
  return (
    <SceneExit dur={dur}>
      {/* The tree bursts about a tenth of a second in. */}
      <AbsoluteFill style={{ filter: "brightness(0.62) saturate(1.2)" }}>
        <Footage dir="timber" first={22} last={52} dur={dur} pos="47% 40%" zoomTo={1.18} />
      </AbsoluteFill>
      <AbsoluteFill style={{ background: "#FFFFFF", opacity: flash, pointerEvents: "none" }} />
      <AbsoluteFill style={{ transform: `translate(${sx}px, ${sy}px)` }}>
        <div style={{ position: "absolute", left: "50%", top: interpolate(up, [0, 1], [860, 600], clamp), display: "flex", flexDirection: "column",
          alignItems: "center", transform: `translate(-50%, -50%) scale(${interpolate(up, [0, 1], [1, 0.55], clamp)})` }}>
          <Slam text="HEY," at={hey} size={240} color={theme.colors.chip} />
          <Slam text="LISTEN!" at={listen} size={240} color={theme.colors.chip} />
        </div>
        <div style={{ position: "absolute", left: "50%", top: 880, display: "flex", gap: 30, transform: "translate(-50%, -50%)", whiteSpace: "nowrap" }}>
          <Slam text="TEAM" at={team} size={136} color={theme.colors.primary} glow />
          <Slam text="ROCKET" at={rocket} size={136} color={theme.colors.primary} glow />
        </div>
        <div style={{ position: "absolute", top: 990, left: 70, right: 70, display: "flex", flexWrap: "wrap", justifyContent: "center", columnGap: 22 }}>
          {rest.map(({ w, f }) => {
            const p = spring({ frame: frame - f, fps, config: { damping: 12, stiffness: 300, mass: 0.5 } });
            return <span key={w} style={{ display: "inline-block", fontFamily: theme.fonts.display, fontWeight: 800, fontSize: 92, color: theme.colors.text,
              opacity: frame >= f ? 1 : 0, transform: `scale(${interpolate(p, [0, 1], [0.6, 1], clamp)})`, WebkitTextStroke: "10px #000",
              paintOrder: "stroke fill", textShadow: "0 8px 0 rgba(0,0,0,0.5)" }}>{w}</span>;
          })}
        </div>
      </AbsoluteFill>
      <Sfx at={0} name="hit" volume={0.5} />
      <Sfx at={team - 1} name="hit" volume={0.45} />
      <Sfx at={dur - s(1.0)} name="riser" volume={0.5} />
    </SceneExit>
  );
};

/** The crew builds a whole village house in two seconds. */
export const House: React.FC<SceneProps> = ({ dur }) => (
  <SceneExit dur={dur}>
    <Shot><Footage dir="house" first={0} last={footage.house} dur={dur} pos="53% 50%" zoomTo={1.1} /></Shot>
    <Sfx at={0} name="hit" volume={0.55} />
  </SceneExit>
);

/** The Work Dex: the best Pokemon for each job. */
export const Crew: React.FC<SceneProps> = ({ dur }) => (
  <SceneExit dur={dur}>
    <Shot dim={0.9}><KenBurns src="img/dex.png" dur={dur} pos="96% 40%" zoomTo={1.12} panX={-20} /></Shot>
  </SceneExit>
);

/** "10" and the ten job names, bursting in two frames apart. */
export const Jobs: React.FC<SceneProps> = ({ dur }) => {
  const jobs = ["Lumber", "Mining", "Farming", "Smelting", "Sorting", "Hauling", "Building", "Demolition", "Guard", "Crafting"];
  return (
    <SceneExit dur={dur}>
      <Shot dim={0.2}><KenBurns src="img/jobs.png" dur={dur} pos="15% 50%" zoomTo={1.15} /></Shot>
      <div style={{ position: "absolute", top: 300, left: 0, right: 0, display: "flex", flexDirection: "column", alignItems: "center" }}>
        <Counter to={10} delay={0} size={380} />
        <WordReveal text="JOBS" delay={3} size={130} />
      </div>
      <div style={{ position: "absolute", top: 960, left: 70, right: 70, display: "flex", flexWrap: "wrap", justifyContent: "center", gap: 20 }}>
        {jobs.map((j, i) => <Chip key={j} text={j} delay={4 + i * 2} size={56} />)}
      </div>
      {jobs.map((j, i) => (i % 2 === 0 ? <Sfx key={j} at={3 + i * 2} name="tick" volume={0.35} /> : null))}
    </SceneExit>
  );
};

/** The Guard map with a patrol route. */
export const Guard: React.FC<SceneProps> = ({ dur }) => (
  <SceneExit dur={dur}>
    <Shot dim={0.9}><KenBurns src="img/map.png" dur={dur} pos="17% 45%" zoomTo={1.14} panX={18} /></Shot>
  </SceneExit>
);

/** "49 upgrades" over the skill tree. */
export const Skills: React.FC<SceneProps> = ({ dur }) => (
  <SceneExit dur={dur}>
    <Shot dim={0.4}><KenBurns src="img/skills.png" dur={dur} pos="50% 50%" zoomTo={1.15} /></Shot>
    <div style={{ position: "absolute", top: 560, left: 0, right: 0, display: "flex", flexDirection: "column", alignItems: "center" }}>
      <Counter to={49} delay={0} size={380} />
      <WordReveal text="UPGRADES" delay={3} size={130} />
    </div>
    <Sfx at={6} name="hit" volume={0.5} />
  </SceneExit>
);

/** The end card: logo, name, where to get it. Holds still at the end so the loop restarts cleanly. */
export const Cta: React.FC<SceneProps> = ({ dur }) => {
  const s = useSec();
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const icon = spring({ frame, fps, config: theme.spring.bouncy });
  const float = Math.sin(frame / 26) * 6;
  const last = String(footage.house - 1).padStart(3, "0");
  return (
    <SceneExit dur={dur}>
      <Punch>
        <AbsoluteFill style={{ opacity: 0.35, filter: "blur(10px)" }}>
          <Img src={staticFile(`footage/house/${last}.jpg`)} style={{ width: "100%", height: "100%", objectFit: "cover", objectPosition: "53% 50%",
            transform: `scale(${interpolate(frame, [0, dur], [1.15, 1.05], { ...clamp, easing: theme.ease.inOut })})` }} />
        </AbsoluteFill>
      </Punch>
      <div style={{ position: "absolute", top: 400, left: theme.layout.side, right: theme.layout.side, display: "flex", flexDirection: "column",
        alignItems: "center", gap: 30 }}>
        <Img src={staticFile("img/icon.png")} style={{ width: 300, height: 300, imageRendering: "pixelated", borderRadius: 36, opacity: icon,
          transform: `translateY(${interpolate(icon, [0, 1], [60, 0], clamp) + float}px) scale(${interpolate(icon, [0, 1], [0.6, 1], clamp)}) rotate(${interpolate(icon, [0, 1], [-12, 0], clamp)}deg)`,
          boxShadow: "0 40px 90px rgba(0,0,0,0.7)" }} />
        <Entrance delay={3} cfg="snappy">
          <div style={{ fontFamily: theme.fonts.pixel, fontWeight: 700, fontSize: 124, color: theme.colors.chip, letterSpacing: "0.02em",
            textShadow: "0 8px 0 rgba(0,0,0,0.5)" }}>FOREMON</div>
        </Entrance>
        <Chip text="Free on CurseForge" delay={s(0.45)} size={70} hero />
        <Entrance delay={s(0.7)} rise={16} cfg="snappy">
          <div style={{ fontFamily: theme.fonts.display, fontWeight: 600, fontSize: 42, color: theme.colors.textDim }}>
            Fabric 1.21.1 · Cobblemon 1.7.3 and 1.8
          </div>
        </Entrance>
      </div>
      <Sfx at={0} name="hit" volume={0.6} />
      <Sfx at={s(0.45) - 2} name="pop" volume={0.6} />
    </SceneExit>
  );
};
