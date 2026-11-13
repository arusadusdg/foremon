# Foremon

Put your Cobblemon to work. A Fabric addon for **Cobblemon 1.7.3+** on **Minecraft 1.21.1**.

> **Made with AI.** Foremon was designed by its author and written with the help of an AI coding
> assistant (Anthropic's Claude), which wrote most of the code, tests and texts under the author's
> direction. Every feature listed here is checked by automated tests in a real Minecraft server.

Turn any chest into a job site, assign Pokemon from your party, and they chop trees, dig out
areas, sort and haul items, farm, smelt, take buildings apart, and build straight from your
Litematica placements. Who is good at what depends on the Pokemon: stats, types, ability,
moves, size and nature all count. There are no tools.

## Playing

1. Craft a **Work Whistle**: two gold ingots in the bottom row, a string above the right one.
2. Right-click a chest with it. That chest is now a job site.
3. Pick a job, click a party Pokemon to assign it, press **Start**.
4. Right-click the air with the whistle for the Foreman's Board, the skill tree, the Work Dex
   and the in-game guide.

Sneak + right-click with the whistle (after opening a site) marks things for that site:
other chests become linked chests (Sorting, Hauling, restocking), other blocks become the
corners of the Mining or Demolition area.

Assigned Pokemon stay in your PC the whole time and come back if the chest is broken, so a
worker can never be lost.

## Jobs

| Job | What it does | Good at it |
|---|---|---|
| Lumber | Fells natural trees, replants | Grass, Bug, Fighting, Steel; Cut fells whole trees |
| Mining | Digs the marked area top-down | Ground, Rock, Steel; Dig takes whole veins |
| Sorting | Tops up linked chests holding the same item | Psychic, Ghost |
| Hauling | Moves items to linked chests | Flying, Fighting |
| Farming | Crops, nether wart, berries, apricorns | Grass, Water, Bug; Harvest ability |
| Smelting | Smelts the chest contents, no fuel | Needs fire: Fire type, fiery ability or fire move |
| Building | Builds a Litematica placement from the chest | Fighting, Rock, Steel, Psychic |
| Demolition | Takes the marked area apart, returns blocks | Fighting, Rock, Ground |

**Timber** belongs to the Pokemon: one log per swing at first, 4 logs from level 20, 12 from
level 35, and whole trees in one swing from level 50 (levels are set in `rules.json`).

Placement limits come from the Pokemon: high blocks need a Pokemon that can fly (or Levitate,
Fly, Rock Climb), underwater blocks need one that breathes water, blocks by lava need a
fire-proof one. What can be mined comes from the Pokemon's level; Rock, Ground and Steel types
dig one tier harder.

## Server owners

**Simple:** the Server tab of the whistle menu (operators only), or `/foremon admin config`.
Pick a mode (Relaxed, Normal, Hardcore) and flip a few switches: may workers break or place
blocks, may they work while their owner is offline, worker and site limits, stamina on/off.

**Advanced:** `config/foremon.json` has every number under `advanced` (scan budget per tick,
workers per chunk, stuck handling, stamina, rewards, blocked dimensions, audit log...).
The rules themselves are data, so a data pack can override them:

- `data/<ns>/foremon/rules.json`: stat weights, size classes, mining tiers, natures, abilities with special effects
- `data/<ns>/foremon/jobs/<job>.json`: per-job stat, type, ability, move, size and species bonuses
- `data/<ns>/foremon/skills.json`: the whole skill tree, including which nodes exist, costs, maximum levels and where each mode starts

Commands (operators): `/foremon admin list | pauseall | remove <player> | reset <player> | xp <player> <amount> | config [key value]`.

Workers ask for permission as their owner before changing a block, so claim and protection
mods that use Fabric's block-break event are respected.

## Building

```bash
./gradlew build
```

The jar is in `build/libs/`. Tests:

```bash
./gradlew test
```

```bash
./gradlew runGametest
```

`test` checks the rule maths against the shipped data files. `runGametest` starts a headless
server with real Cobblemon Pokemon and checks that every job actually gets its work done in
the world.

```bash
./gradlew runProbe
```

`runProbe` opens a real game that plays itself: it makes a test world, uses the whistle, clicks
through the menus and saves a screenshot of each stage to `run/screenshots/probe_*.png`, then
quits. Add `-Dforemon.debug=true` to any run to log every decision a worker makes.

The default data files and the language file are generated: edit `tools/gen_rules.py` or
`tools/gen_assets.py` and run them with `src/main/resources/data/foremon/foremon` and
`src/main/resources` as their argument. 

## Licence

MIT. Contains no Cobblemon assets.
