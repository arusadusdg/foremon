// Renders check frames as PNGs into out/ (bundles once): node scripts/stills.mjs 40 130 210 ...
import { bundle } from "@remotion/bundler";
import { renderStill, selectComposition } from "@remotion/renderer";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const serveUrl = await bundle({ entryPoint: join(root, "src", "index.ts") });
const composition = await selectComposition({ serveUrl, id: "ForemonTikTok" });
for (const f of process.argv.slice(2).map(Number)) {
  const output = join(root, "out", `check_${String(f).padStart(3, "0")}.png`);
  await renderStill({ serveUrl, composition, frame: f, output, overwrite: true });
  console.log("wrote", output);
}
