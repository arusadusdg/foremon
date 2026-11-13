# Changelog

## 1.0.0

First release. Minecraft 1.21.1, Fabric, Cobblemon 1.7.3 or newer.

### Jobs
- Turn any chest into a job site with the Work Whistle and put your Pokemon to work on it.
- Eight jobs: Lumber, Mining, Sorting, Hauling, Farming (crops, nether wart, berries, apricorns), Smelting,
  Building (from a Litematica placement) and Demolition.
- Workers walk to the work, really break and place blocks, and bring the results back to the chest.

### Pokemon
- How well a Pokemon works comes from its stats, types, ability, known moves, size and nature. There are no tools.
- Pokemon that can fly reach any height, water-breathers work under water, fire-proof Pokemon work next to lava.
- Level perks per job, unlocked by the Pokemon's level: Leaf Sweep and Timber for lumber, Vein Miner for mining,
  Wide Harvest, Big Batch, Heavy Load and Quick Hands.
- Job mastery from Novice to Master, stored on the Pokemon. Working gives a little EXP and friendship.
- Stamina, naps and berries.

### Your base
- Extra chests: sneak and right-click chests with the whistle to add them to a job site.
- Every chest shows a Poke Ball above it (16 kinds, Poke Ball to Master Ball) and has its own filter.
- Pick workers from your party or straight from your PC boxes.
- A 38-upgrade skill tree in four branches, bought with Work XP. You start with one chunk, one worker and one site.

### Screens
- Job site menu, Foreman's Board, skill tree and perks in the style of the advancement screen, Work Dex, and an
  eight-page guide.

### Server owners
- Simple settings in game (Relaxed, Normal or Hardcore mode, a few on/off switches) and every number in
  config/foremon.json. Rules and the skill tree can be changed with a data pack.
- Workers only change blocks and open chests their owner could. Admin commands: list, pause all, remove, reset,
  grant XP, config, and `/foremon admin perf` to see the mod's cost per tick.

### Tested
- 15 unit tests, 31 in-game server tests with real Cobblemon Pokemon (every job, perks, skills, upkeep, saving
  items, a 20-worker stress test), and a self-driving client that plays through the menus, building from
  Litematica, and saving and reloading a world.
- In the stress test 20 workers on 5 sites cost Foremon under 1 ms per server tick.
