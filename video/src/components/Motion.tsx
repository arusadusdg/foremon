// Reusable motion pieces: entrances, word reveals, exits, Ken Burns stills, footage, cards, chips, counters.
import React from "react";
import { AbsoluteFill, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { clamp, theme } from "../theme";

type Spring = keyof typeof theme.spring;

/** Fade + rise + scale, on a spring. */
export const Entrance: React.FC<{ delay?: number; rise?: number; cfg?: Spring; style?: React.CSSProperties; children: React.ReactNode }> = ({
  delay = 0, rise = 40, cfg = "smooth", style, children,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: frame - delay, fps, config: theme.spring[cfg] });
  return (
    <div style={{ opacity: p, transform: `translateY(${interpolate(p, [0, 1], [rise, 0], clamp)}px) scale(${interpolate(p, [0, 1], [0.94, 1], clamp)})`, ...style }}>
      {children}
    </div>
  );
};

/** Word-by-word reveal. The word at [hero] is drawn in the hero color; a "|" in [text] forces a line break. */
export const WordReveal: React.FC<{ text: string; delay?: number; per?: number; hero?: number; size?: number; color?: string; style?: React.CSSProperties }> = ({
  text, delay = 0, per = 3, hero = -1, size = 112, color = theme.colors.text, style,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return (
    <div style={{ display: "flex", flexWrap: "wrap", justifyContent: "center", columnGap: Math.round(size * 0.26), rowGap: 0,
      fontFamily: theme.fonts.display, fontWeight: 800, fontSize: size, lineHeight: 1.05, letterSpacing: "-0.03em", color, ...style }}>
      {text.split(" ").reduce<{ w: string; i: number }[]>((acc, w) => {
        acc.push({ w, i: w === "|" ? -1 : acc.filter((x) => x.i >= 0).length });
        return acc;
      }, []).map(({ w, i }, k) => {
        if (i < 0) return <span key={`br${k}`} style={{ flexBasis: "100%", height: 0 }} />;
        const p = spring({ frame: frame - delay - i * per, fps, config: theme.spring.snappy });
        const isHero = i === hero;
        return (
          <span key={i} style={{ display: "inline-block", opacity: p,
            transform: `translateY(${interpolate(p, [0, 1], [34, 0], clamp)}px) scale(${interpolate(p, [0, 1], [0.9, 1], clamp)})`,
            color: isHero ? theme.colors.primary : undefined,
            textShadow: isHero ? `0 0 50px ${theme.colors.glow}` : "0 6px 30px rgba(0,0,0,0.55)" }}>{w}</span>
        );
      })}
    </div>
  );
};

/** Wraps a scene: everything leaves in its last frames, faster than it came in. */
export const SceneExit: React.FC<{ dur: number; children: React.ReactNode }> = ({ dur, children }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const len = Math.round(fps * 0.33);
  const range = [dur - len, dur - 1];
  const y = interpolate(frame, range, [0, -60], { ...clamp, easing: theme.ease.in });
  const o = interpolate(frame, range, [1, 0], { ...clamp, easing: theme.ease.in });
  const s = interpolate(frame, range, [1, 1.04], { ...clamp, easing: theme.ease.in });
  return <AbsoluteFill style={{ opacity: o, transform: `translateY(${y}px) scale(${s})` }}>{children}</AbsoluteFill>;
};

/** Slow push-in and pan on a still. [pos] picks the part of the image to show. */
export const KenBurns: React.FC<{ src: string; dur: number; pos?: string; zoomTo?: number; panX?: number }> = ({
  src, dur, pos = "50% 50%", zoomTo = 1.1, panX = -24,
}) => {
  const frame = useCurrentFrame();
  const scale = interpolate(frame, [0, dur], [1, zoomTo], { ...clamp, easing: theme.ease.inOut });
  const pan = interpolate(frame, [0, dur], [0, panX], { ...clamp, easing: theme.ease.inOut });
  return <Img src={staticFile(src)} style={{ width: "100%", height: "100%", objectFit: "cover", objectPosition: pos,
    transform: `scale(${scale}) translateX(${pan}px)`, imageRendering: "pixelated" }} />;
};

/** Gameplay footage from numbered JPGs: frames [first, last) spread over [dur] video frames, with a gentle push-in. */
export const Footage: React.FC<{ dir: string; first: number; last: number; dur: number; pos?: string; zoomTo?: number }> = ({
  dir, first, last, dur, pos = "50% 50%", zoomTo = 1.06,
}) => {
  const frame = useCurrentFrame();
  // ponytail: footage time is a straight frame lookup, not an animation curve.
  const at = (frame * (last - first)) / dur;
  const i0 = Math.min(last - 1, first + Math.floor(at));
  const i1 = Math.min(last - 1, i0 + 1);
  // Played slower than recorded: cross-fade into the next frame so it doesn't stutter.
  const blend = (last - first) < dur && i1 !== i0 ? at - Math.floor(at) : 0;
  const scale = interpolate(frame, [0, dur], [1, zoomTo], { ...clamp, easing: theme.ease.inOut });
  const img = (i: number, opacity = 1) => (
    <Img src={staticFile(`footage/${dir}/${String(i).padStart(3, "0")}.jpg`)} style={{ position: "absolute", inset: 0, width: "100%", height: "100%",
      objectFit: "cover", objectPosition: pos, opacity }} />
  );
  return (
    <AbsoluteFill style={{ transform: `scale(${scale})` }}>
      {img(i0)}
      {blend > 0 && img(i1, blend)}
    </AbsoluteFill>
  );
};

/** A rounded card that springs in and then breathes. */
export const Card: React.FC<{ delay?: number; x?: number; y?: number; w?: number; h?: number; children: React.ReactNode }> = ({
  delay = 0, x = theme.layout.cardX, y = theme.layout.cardY, w = theme.layout.cardW, h = theme.layout.cardH, children,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: frame - delay, fps, config: theme.spring.smooth });
  const breathe = 1 + Math.sin(frame / 22) * 0.006;
  return (
    <div style={{ position: "absolute", left: x, top: y, width: w, height: h, borderRadius: 40, overflow: "hidden",
      border: `2px solid ${theme.colors.edge}`, boxShadow: "0 50px 100px -20px rgba(0,0,0,0.75)", opacity: p,
      transform: `translateY(${interpolate(p, [0, 1], [80, 0], clamp)}px) scale(${interpolate(p, [0, 1], [0.9, 1], clamp) * breathe})` }}>
      {children}
    </div>
  );
};

/** A cream pill with dark text, like a label in Foremon's book. */
export const Chip: React.FC<{ text: string; delay?: number; size?: number; hero?: boolean }> = ({ text, delay = 0, size = 46, hero = false }) => (
  <Entrance delay={delay} rise={30} cfg="snappy">
    <div style={{ background: hero ? theme.colors.primary : theme.colors.chip, color: theme.colors.ink, fontFamily: theme.fonts.display,
      fontWeight: 800, fontSize: size, padding: `${size * 0.32}px ${size * 0.62}px`, borderRadius: 999, whiteSpace: "nowrap",
      boxShadow: hero ? `0 0 60px ${theme.colors.glow}, 0 0 120px ${theme.colors.glow}` : "0 14px 30px rgba(0,0,0,0.45)" }}>{text}</div>
  </Entrance>
);

/** Small pixel-font label, like the mod's in-game text. */
export const Label: React.FC<{ text: string; delay?: number }> = ({ text, delay = 0 }) => (
  <Entrance delay={delay} rise={20} cfg="snappy">
    <div style={{ fontFamily: theme.fonts.pixel, fontSize: 40, letterSpacing: "0.06em", color: theme.colors.textDim,
      background: theme.colors.shade, border: `2px solid ${theme.colors.edge}`, borderRadius: 14, padding: "10px 22px" }}>{text}</div>
  </Entrance>
);

/** A number that counts up on a soft spring, in tabular figures. */
export const Counter: React.FC<{ to: number; delay?: number; size?: number; hero?: boolean }> = ({ to, delay = 0, size = 300, hero = true }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: frame - delay, fps, config: theme.spring.counter });
  const pop = spring({ frame: frame - delay, fps, config: theme.spring.bouncy });
  return (
    <div style={{ fontFamily: theme.fonts.display, fontWeight: 800, fontSize: size, lineHeight: 0.9, fontVariantNumeric: "tabular-nums",
      color: hero ? theme.colors.primary : theme.colors.text, textShadow: hero ? `0 0 70px ${theme.colors.glow}` : undefined,
      opacity: interpolate(pop, [0, 1], [0, 1], clamp), transform: `scale(${interpolate(pop, [0, 1], [0.6, 1], clamp)})` }}>
      {Math.round(interpolate(p, [0, 1], [0, to], clamp))}
    </div>
  );
};

/** Centered column inside the 9:16 safe zone, starting at [top]. */
export const Column: React.FC<{ top: number; gap?: number; children: React.ReactNode }> = ({ top, gap = 24, children }) => (
  <div style={{ position: "absolute", top, left: theme.layout.side, right: theme.layout.side, display: "flex", flexDirection: "column",
    alignItems: "center", gap }}>{children}</div>
);
