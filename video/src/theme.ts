// theme.ts: the one source of colors, fonts, easings and springs. Foremon's book: red leather, gold, cream.
import { Easing } from "remotion";
import { loadFont as loadRubik } from "@remotion/google-fonts/Rubik";
import { loadFont as loadSilkscreen } from "@remotion/google-fonts/Silkscreen";

const rubik = loadRubik("normal", { weights: ["600", "800"], subsets: ["latin", "latin-ext"] });
const silkscreen = loadSilkscreen("normal", { weights: ["400", "700"], subsets: ["latin"] });

export const theme = {
  colors: {
    bg: "#170909",
    bgAlt: "#2B1111",
    primary: "#F5C542", // gold: THE hero color, at most one element per frame
    accent: "#C9362E", // Poke Ball / leather red, for the background mesh
    text: "#FFF6E6",
    textDim: "#E2CDB0",
    ink: "#2A1A0F", // dark text on cream chips
    chip: "#F6EAD0", // cream, like the book's pages
    shade: "rgba(14, 5, 5, 0.78)",
    glow: "rgba(245, 197, 66, 0.45)",
    edge: "rgba(255, 240, 210, 0.16)",
  },
  fonts: {
    display: rubik.fontFamily,
    pixel: silkscreen.fontFamily,
  },
  ease: {
    out: Easing.bezier(0.16, 1, 0.3, 1), // entrances
    inOut: Easing.bezier(0.83, 0, 0.17, 1), // moves, Ken Burns
    in: Easing.bezier(0.7, 0, 0.84, 0), // exits only
  },
  spring: {
    snappy: { damping: 14, stiffness: 160, mass: 0.6 },
    smooth: { damping: 20, stiffness: 90, mass: 1 },
    bouncy: { damping: 11, stiffness: 170, mass: 0.7 },
    counter: { damping: 30, stiffness: 60, mass: 1 },
  },
  // 9:16 layout: platform UI covers the top and bottom, so text stays between these lines.
  layout: { safeTop: 230, safeBottom: 1690, side: 60, cardX: 40, cardY: 600, cardW: 1000, cardH: 1000 },
  bpm: 120,
} as const;

export const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
