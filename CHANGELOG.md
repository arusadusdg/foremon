# Changelog

## 1.3.0

- **NeoForge**: Foremon now runs on NeoForge 1.21.1 too, with Cobblemon for NeoForge and Kotlin for Forge. Same jobs,
  menus and rules, in a separate jar (`foremon-neoforge-1.3.0.jar`). Every in-game test passes on both loaders.
- On NeoForge, workers ask claim and protection mods through NeoForge's block-break event, and modded storage works
  through NeoForge's item handlers, like any chest. The Building job reads placements from Forgematica.

## 1.2.1

- Works on Cobblemon 1.8 too: tested on Cobblemon 1.8.4 (every in-game test and every menu), as well as 1.7.3.
- On big screens (1920x1080 with auto GUI scale) the skill tree's "Click to buy" ran into "Mode", and long page
  titles (Perks, Work Dex) ran off the page. Both fit now.

## 1.2.0

### A new look
- Every screen is now a journal: a red book with cream pages, the page title on a blue ribbon, and bookmarks down
  the side to turn to Crew, Jobs, PC box, Chests (Routes, Map), Filter, Skills and All sites. Pages use both sides:
  the crew on one, your party and the controls on the other; the PC box on one, everything about the Pokemon you
  point at on the other.
- The hub (job sites, skill tree, level perks, Work Dex, guide, server settings) is a journal too. The skill tree
  spreads across both pages; level perks read top to bottom with who in your party has each one.

### Requests and Crafting (new job)
- Sites now ask for what they are short of: a builder out of a block, guards out of torches, a crafter out of an
  ingredient. The site shows what it is asking for, and so do your job sites in the hub.
- A Hauling site with **Bring requests** on (the default) fetches those items from any of your chests in Supply
  Lines range, before working its routes. Haulers pinned to a route with Dispatch stay on their route.
- **Crafting**: list what to make in the Filter (torches, planks, sticks, anything with a crafting recipe). The crew
  crafts it from the chest until there is a stack of each, and asks for what it is short of. Recipes that take one
  of several items (coal or charcoal) accept any of them. Level perk **Assembly Line** (level 20 and 40): 2, then 3
  crafts per action. New skill **Handiwork**: +10% Crafting speed per level.

### Guard (new job)
- Guards fight off hostile mobs in the work area, the ones going for a villager or a player first, then the ones
  closest to a villager. Neutral mobs that leave you alone, and mobs with a name tag, are left alone.
- They put torches from the chest wherever a mob could spawn (no block light), on the ground and in buildings, not
  in deep caves. Loot from the mobs they beat goes into the chest.
- The Guard site has a **Map** page: the area seen from above, with tools to draw **guard areas** (guards only
  work inside them), **keep out** boxes (no hunting, no torches) and **no torches** boxes, and a **patrol route**:
  click stops on the map, and guards with nothing to do walk them in order. Select an area to **post** guards to
  it; a posted guard stays in its area. **Big map** opens the same map as large as the screen allows.
- Level perk **Cleave** (level 25 and 45): one swing hits up to 2, then 3 mobs.

### Skill tree
- New skills: **Vigilance** (+10% Guard speed), **Sharp Claws** (guards hit 25% harder), **Lookout** (guards hunt 8
  blocks further), **Green Thumb** (harvests may grow nearby crops), **Quick Fetch** (builders carry 50% more per
  trip), **Express Lanes** (hauling loads 15% quicker) and **Campfire Rest** (naps 40% shorter with a campfire or a
  bed near the chest). 49 upgrades in all.

### Balance
- Long actions earn more Work XP: a far haul or a smelt counts up to 8 times a quick chop, instead of the same.
- Workers start with 150 stamina instead of 100, and a berry gives 60 instead of 40, so crews stop to eat less
  often. (Servers that already have a config file keep their own numbers.)

### Hauling
- Haulers fly their routes for real when the world is running all the way along them: depot, the chest they take
  from, the chest they deliver to and back, carrying the load in their paws.
- On a far route (a chunk on the way is not running) a hauler is out on the road: it leaves the depot instead of
  standing at the chest.
- Above the Hauling chest, one line shows how far along the next load is (in percent) and the total hauled.
- New skill, **Dispatch** (Logistics branch, up to level 5): send different haulers to different places. Each level
  lets one more route have a crew of its own; press **Crew** on a route to pick its haulers. Haulers without a route
  work every route.
- **Add route** works without Remote Control now: pick from this depot and its chests; other job sites still need
  Remote Control.
- In **Add route**, after picking where to take from, pick **A chest in the world...**: the menu closes, and the next
  chest you sneak-right-click with the whistle is where the route delivers to.

## 1.1.0

### Hauling routes
- Hauling crews no longer walk. They stay at the site's chest (the depot) and work the site's routes on a timer, and
  each hauler's card shows a bar filling up as it works on a load.
- A route goes from any chest to any other: with the Hauling site selected, sneak-right-click the chest to take from,
  then the chest to deliver to. The depot's own chest, extra chests and other job sites' chests all work, so routes
  connect sites (a Lumber site's chest to a Smelting site's chest, say).
- Each route carries what the chest it delivers to allows (that chest's filter). The Routes tab lists them, with
  the distance, a filter button and a button to remove one.
- Longer routes take longer per load, and each extra hauler works its own loads, so more haulers move more.
- New skill, **Supply Lines** (Logistics branch, up to level 5): how far route chests may be from the depot. 500
  blocks to start, 5000 at the top. Far routes are not capped any other way: they just take longer per load.
- A route chest in an unloaded chunk still gets worked: the chunk is loaded for the load and kept loaded (not
  ticking) for a minute, so a Lumber site 600 blocks away keeps feeding the depot while you are at home.
- With Remote Control, the Routes tab has an **Add route** button: pick two of your job sites (or the depot) from the
  list and the route is made without going there.

### Lumber
- New skill, **Bone Meal** (Labour branch, up to level 3, after one level of Chopping): Lumber crews use bone meal
  from the chest on saplings in the work area. Each level makes every bone meal count once more.

### Screens
- The job site screen updates live while the site is running.

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
