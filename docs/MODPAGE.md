# Foremon

**Put your Cobblemon to work.**

> **Made with AI.** Foremon was designed by its author and written with the help of an AI coding
> assistant (Anthropic's Claude), which wrote most of the code, tests and texts under the author's
> direction. Every feature listed here is checked by automated tests in a real Minecraft server.

Turn any chest into a job site, assign your Pokemon, and watch them chop trees, dig out mines, farm,
smelt, sort your storage and build your Litematica schematics, block by block, for real.

## Why Foremon

- **Your Pokemon matter.** Every species is rated for every job from its stats, types, ability, moves,
  size and nature. A Machop chops, a Geodude digs, a Charmander smelts, a Pidgey hauls. Hover over any
  Pokemon to see exactly why.
- **They really do the work.** No loot tables and no magic: workers walk to the tree, the ore, the crop or
  the scaffold, play their own attack animations, and carry everything back.
- **They grow.** Level perks unlock as a Pokemon levels up: at level 50 a lumberjack brings down a whole
  tree, leaves and all, in one swing. Each Pokemon also ranks up from Novice to Master in its jobs.
- **Build from Litematica.** Place a schematic, press Load plan, put the blocks in the chest. Your crew
  builds it from the bottom up.
- **Your base, your way.** Mark every chest with a Poke Ball, from Poke Ball to Master Ball, give each its
  own filter, and pick workers straight from your PC boxes.
- **Something to work toward.** A 42-upgrade skill tree: start with one chunk and one worker, end with a
  whole crew across many sites.

## Jobs

Lumber, Mining, Sorting, Hauling, Farming (crops, berries, apricorns), Smelting, Building and Demolition.

## Getting started

1. Craft a Work Whistle (two gold ingots and a string).
2. Right-click a chest with it.
3. Pick a job, click a Pokemon, press Start.

Right-click the air with the whistle for your job sites, the skill tree, the perks, the Work Dex and a
guide.

## For server owners

- Safe by default: workers only touch blocks and chests their owner could, and claim mods that use
  Fabric's block-break event are respected.
- One switch for Relaxed, Normal or Hardcore, plus every number in `config/foremon.json`.
- Rules and the skill tree are data, so a data pack can retune everything.
- `/foremon admin perf` shows the mod's cost per tick. In testing, 20 workers on 5 sites cost under
  1 ms per tick.

## Requirements

Minecraft 1.21.1, Fabric Loader, Fabric API, Fabric Language Kotlin, Cobblemon 1.7.3 or newer.
Litematica is optional (only for building from schematics).
