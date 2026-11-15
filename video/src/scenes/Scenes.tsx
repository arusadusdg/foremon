// The seven beats of the TikTok. Each scene gets its length in frames and places its own sound effects.
import React from "react";
import { AbsoluteFill, Audio, Img, Sequence, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { clamp, theme } from "../theme";
import { Card, Chip, Column, Counter, Entrance, Footage, KenBurns, Label, SceneExit, WordReveal } from "../components/Motion";
import footage from "../footage.json";

export type Word = { w: string; start: number; end: number };
/** [dur]: scene length in frames. [words]: the voice-over's words (seconds from [lead], the frame the voice starts). */
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

/** A line of text over the bottom of a card, on a dark fade. */
const CardCaption: React.FC<{ text: string; delay: number }> = ({ text, delay }) => (
  <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, padding: "140px 50px 46px",
    background: `linear-gradient(180deg, transparent, ${theme.colors.shade})`, display: "flex", justifyContent: "center" }}>
    <Entrance delay={delay} rise={24} cfg="snappy">
      <div style={{ fontFamily: theme.fonts.display, fontWeight: 600, fontSize: 50, color: theme.colors.text, textAlign: "center", textWrap: "balance" } as React.CSSProperties}>{text}</div>
    </Entrance>
  </div>
);

/** A word that slams in from big to normal size on a bouncy spring, at frame [at]. */
const Slam: React.FC<{ text: string; at: number; size: number; color: string; glow?: boolean }> = ({ text, at, size, color, glow }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: frame - at, fps, config: theme.spring.bouncy });
  return (
    <span style={{ display: "inline-block", fontFamily: theme.fonts.display, fontWeight: 800, fontSize: size, lineHeight: 1, color,
      letterSpacing: "-0.03em", opacity: interpolate(frame - at, [0, 2], [0, 1], clamp),
      transform: `scale(${interpolate(p, [0, 1], [2.4, 1], clamp)}) rotate(${interpolate(p, [0, 1], [-8, 0], clamp)}deg)`,
      textShadow: glow ? `0 0 60px ${theme.colors.glow}, 0 8px 0 rgba(0,0,0,0.5)` : "0 8px 0 rgba(0,0,0,0.5), 0 0 40px rgba(0,0,0,0.6)" }}>{text}</span>
  );
};

/**
 * The shouted opener: "HEY, LISTEN! Team Rocket doesn't want you to know this..." Each word lands on the frame the
 * voice says it. Two white flash hits (HEY, TEAM), far under three flashes a second, plus screen shake on every slam.
 */
export const Hook: React.FC<SceneProps> = ({ dur, words = [], lead = 0 }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = useSec();
  // When each word is said, with timings that still work if there is no voice-over yet.
  const at = (i: number, fallback: number) => lead + Math.round((words[i]?.start ?? fallback) * fps);
  const hey = at(0, 0.1), listen = at(1, 0.4), team = at(2, 1.3), rocket = at(3, 1.6);
  const rest = ["doesn't", "want", "you", "to", "know", "this..."].map((w, i) => ({ w, f: at(4 + i, 2.0 + i * 0.3) }));
  const flash = Math.max(...[hey, team].map((t) => (frame < t ? 0 : interpolate(frame, [t, t + 8], [0.85, 0], { ...clamp, easing: theme.ease.out }))));
  const shake = [hey, listen, team, rocket].reduce((sum, t) => sum + (frame >= t ? Math.exp(-(frame - t) / 4) : 0), 0);
  const sx = Math.sin(frame * 2.7) * 24 * shake, sy = Math.cos(frame * 3.1) * 18 * shake;
  // "HEY, LISTEN!" makes room when "TEAM ROCKET" lands.
  const up = spring({ frame: frame - team + 6, fps, config: theme.spring.snappy });
  const pulse = 0.35 + 0.25 * Math.max(0, Math.sin(frame / 3));
  return (
    <SceneExit dur={dur}>
      <AbsoluteFill style={{ background: `radial-gradient(circle at 50% 45%, ${theme.colors.accent}, transparent 70%)`, opacity: pulse }} />
      <AbsoluteFill style={{ transform: `translate(${sx}px, ${sy}px)` }}>
        {/* HEY, / LISTEN! stacked big in the middle, then shrinking up out of the way of TEAM ROCKET. */}
        <div style={{ position: "absolute", left: "50%", top: interpolate(up, [0, 1], [900, 640], clamp), display: "flex", flexDirection: "column",
          alignItems: "center", transform: `translate(-50%, -50%) scale(${interpolate(up, [0, 1], [1, 0.55], clamp)})` }}>
          <Slam text="HEY," at={hey} size={220} color={theme.colors.chip} />
          <Slam text="LISTEN!" at={listen} size={220} color={theme.colors.chip} />
        </div>
        <div style={{ position: "absolute", left: "50%", top: 900, display: "flex", gap: 30, transform: "translate(-50%, -50%)", whiteSpace: "nowrap" }}>
          <Slam text="TEAM" at={team} size={130} color={theme.colors.primary} glow />
          <Slam text="ROCKET" at={rocket} size={130} color={theme.colors.primary} glow />
        </div>
        <div style={{ position: "absolute", top: 1010, left: 90, right: 90, display: "flex", flexWrap: "wrap", justifyContent: "center", columnGap: 22 }}>
          {rest.map(({ w, f }) => {
            const p = spring({ frame: frame - f, fps, config: theme.spring.snappy });
            return <span key={w} style={{ display: "inline-block", fontFamily: theme.fonts.display, fontWeight: 800, fontSize: 84, color: theme.colors.text,
              opacity: p, transform: `translateY(${interpolate(p, [0, 1], [30, 0], clamp)}px)`, textShadow: "0 6px 30px rgba(0,0,0,0.6)" }}>{w}</span>;
          })}
        </div>
      </AbsoluteFill>
      <AbsoluteFill style={{ background: "#FFFFFF", opacity: flash, pointerEvents: "none" }} />
      <Sfx at={hey - 1} name="hit" volume={0.45} />
      <Sfx at={team - 1} name="hit" volume={0.45} />
      <Sfx at={dur - s(1.6)} name="riser" volume={0.5} />
    </SceneExit>
  );
};

export const House: React.FC<SceneProps> = ({ dur }) => {
  const s = useSec();
  return (
    <SceneExit dur={dur}>
      <Card>
        <Footage dir="house" first={0} last={footage.house} dur={dur} pos="55% 50%" zoomTo={1.08} />
        <CardCaption text="Block by block, from a Litematica plan" delay={s(2.4)} />
      </Card>
      <Column top={theme.layout.safeTop} gap={20}>
        <Label text="COBBLEMON ADDON" delay={2} />
        <WordReveal text="Your Pokémon | build your house" hero={2} delay={s(0.15)} size={110} />
      </Column>
      <Sfx at={0} name="hit" volume={0.55} />
      <Sfx at={s(2.4) - 2} name="pop" volume={0.5} />
    </SceneExit>
  );
};

export const Crew: React.FC<SceneProps> = ({ dur }) => {
  const s = useSec();
  const types = ["Fighting", "Rock", "Steel", "Psychic"];
  return (
    <SceneExit dur={dur}>
      <Card delay={2} h={760}>
        <KenBurns src="img/dex.png" dur={dur} pos="100% 50%" zoomTo={1.08} panX={-18} />
      </Card>
      <Column top={theme.layout.cardY + 760 + 44} gap={22}>
        <div style={{ display: "flex", flexWrap: "wrap", justifyContent: "center", gap: 16 }}>
          {types.map((t, i) => <Chip key={t} text={t} delay={s(0.9) + i * 4} size={48} />)}
        </div>
        <Entrance delay={s(1.7)} rise={20} cfg="snappy">
          <div style={{ fontFamily: theme.fonts.display, fontWeight: 600, fontSize: 50, color: theme.colors.text }}>build fastest. Flyers do the roof.</div>
        </Entrance>
      </Column>
      <Column top={theme.layout.safeTop + 40}>
        <WordReveal text="Types pick | the crew" hero={0} delay={3} size={118} />
      </Column>
      {types.map((t, i) => <Sfx key={t} at={s(0.9) + i * 4 - 2} name="pop" volume={0.35} />)}
    </SceneExit>
  );
};

export const Timber: React.FC<SceneProps> = ({ dur }) => {
  const s = useSec();
  return (
    <SceneExit dur={dur}>
      <Card delay={2}>
        <Footage dir="timber" first={14} last={45} dur={dur} pos="50% 45%" zoomTo={1.1} />
        <CardCaption text="A level 50 Machop and its Timber perk" delay={s(1.6)} />
      </Card>
      <Column top={theme.layout.safeTop + 10} gap={20}>
        <Label text="LEVEL PERKS" delay={3} />
        <WordReveal text="One swing. | Whole tree." hero={2} delay={5} size={112} />
      </Column>
      <Sfx at={s(0.6)} name="hit" volume={0.6} />
    </SceneExit>
  );
};

export const Jobs: React.FC<SceneProps> = ({ dur }) => {
  const s = useSec();
  const jobs = ["Lumber", "Mining", "Farming", "Smelting", "Sorting", "Hauling", "Building", "Demolition", "Guard", "Crafting"];
  return (
    <SceneExit dur={dur}>
      <AbsoluteFill style={{ opacity: 0.1, filter: "blur(12px)" }}>
        <KenBurns src="img/jobs.png" dur={dur} pos="20% 50%" zoomTo={1.12} />
      </AbsoluteFill>
      <Column top={theme.layout.safeTop + 90} gap={0}>
        <Counter to={10} delay={2} size={340} />
        <WordReveal text="jobs to hand out" delay={s(0.5)} size={92} />
      </Column>
      <div style={{ position: "absolute", top: 930, left: 90, right: 90, display: "flex", flexWrap: "wrap", justifyContent: "center", gap: 22 }}>
        {jobs.map((j, i) => <Chip key={j} text={j} delay={s(0.9) + i * 4} size={52} />)}
      </div>
      <Sfx at={0} name="hit" volume={0.55} />
      {jobs.map((j, i) => <Sfx key={j} at={s(0.9) + i * 4 - 2} name="tick" volume={0.4} />)}
    </SceneExit>
  );
};

export const Guard: React.FC<SceneProps> = ({ dur }) => {
  const s = useSec();
  return (
    <SceneExit dur={dur}>
      <Card delay={2}>
        <KenBurns src="img/map.png" dur={dur} pos="0% 50%" zoomTo={1.14} panX={20} />
        <CardCaption text="Guards fight mobs and put up torches" delay={s(1.4)} />
      </Card>
      <Column top={theme.layout.safeTop + 10} gap={20}>
        <Label text="NEW: GUARD JOB" delay={3} />
        <WordReveal text="Draw a patrol. | They guard it." hero={4} delay={5} size={100} />
      </Column>
      <Sfx at={s(1.4) - 2} name="pop" volume={0.45} />
    </SceneExit>
  );
};

export const Skills: React.FC<SceneProps> = ({ dur }) => {
  const s = useSec();
  return (
    <SceneExit dur={dur}>
      <Card delay={s(0.4)} y={760} h={840}>
        <KenBurns src="img/skills.png" dur={dur} pos="50% 50%" zoomTo={1.1} />
      </Card>
      <Column top={theme.layout.safeTop + 10} gap={0}>
        <Counter to={49} delay={2} size={300} />
        <WordReveal text="upgrades to unlock" delay={s(0.55)} size={88} />
      </Column>
      <Sfx at={s(0.9)} name="hit" volume={0.5} />
    </SceneExit>
  );
};

export const Cta: React.FC<SceneProps> = ({ dur }) => {
  const s = useSec();
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const icon = spring({ frame: frame - 2, fps, config: theme.spring.bouncy });
  const float = Math.sin(frame / 26) * 6;
  const last = String(footage.house - 1).padStart(3, "0");
  return (
    <SceneExit dur={dur}>
      <AbsoluteFill style={{ opacity: 0.3, filter: "blur(10px)" }}>
        <Img src={staticFile(`footage/house/${last}.jpg`)} style={{ width: "100%", height: "100%", objectFit: "cover",
          transform: `scale(${interpolate(frame, [0, dur], [1.15, 1.05], { ...clamp, easing: theme.ease.inOut })})` }} />
      </AbsoluteFill>
      <Column top={430} gap={34}>
        <Img src={staticFile("img/icon.png")} style={{ width: 300, height: 300, imageRendering: "pixelated", borderRadius: 36,
          opacity: icon, transform: `translateY(${interpolate(icon, [0, 1], [60, 0], clamp) + float}px) scale(${interpolate(icon, [0, 1], [0.6, 1], clamp)}) rotate(${interpolate(icon, [0, 1], [-12, 0], clamp)}deg)`,
          boxShadow: "0 40px 90px rgba(0,0,0,0.7)" }} />
        <Entrance delay={s(0.25)} cfg="smooth">
          <div style={{ fontFamily: theme.fonts.pixel, fontWeight: 700, fontSize: 124, color: theme.colors.chip, letterSpacing: "0.02em",
            textShadow: "0 8px 0 rgba(0,0,0,0.5)" }}>FOREMON</div>
        </Entrance>
        <WordReveal text="Put your Pokémon to work" delay={s(0.5)} size={70} color={theme.colors.textDim} />
        <div style={{ height: 20 }} />
        <Chip text="Free on CurseForge" delay={s(1.0)} size={66} hero />
        <Entrance delay={s(1.35)} rise={16} cfg="snappy">
          <div style={{ fontFamily: theme.fonts.display, fontWeight: 600, fontSize: 40, color: theme.colors.textDim }}>
            Fabric 1.21.1 · Cobblemon 1.7.3 and 1.8
          </div>
        </Entrance>
      </Column>
      <Sfx at={0} name="hit" volume={0.55} />
      <Sfx at={s(1.0) - 2} name="pop" volume={0.6} />
    </SceneExit>
  );
};
