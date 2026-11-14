# Feature list

The single source of truth for what this mod contains. Anything not on this list is not being built.

Name: **Foremon** (mod ID `foremon`).

How to read the boxes:

- `[x]` proven: an automated test on a real server with real Cobblemon Pokemon passes for it, or it was seen working in the self-driving test game (screenshots in `run/screenshots`)
- `[~]` built and compiling, but not yet seen working in a played game
- `[ ]` not built yet

## Ground rules

- Minecraft 1.21.1, Fabric, Cobblemon 1.7.3 or newer. Kotlin.
- Will be published publicly, so it must be safe on multiplayer servers.
- Must run inside the COBBLEVERSE pack without conflicts.
- Must look good (real animations and effects) and must not cause lag.
- A Pokemon can never be lost or duplicated, whatever happens.

## Core loop

- [x] The Work Whistle item, with its own icon
- [~] Crafting recipe for it (two gold ingots and a string)
- [x] Use it on a normal chest to make that chest a Job Site (no new chest block)
- [x] Assign your own Pokemon to a Job Site by clicking it in the menu
- [~] Unassign by clicking the worker; it returns to the party or stays in the PC
- [x] Pick a job for the site and start it
- [~] Pause
- [x] Workers take what they need from the chest and bring results back to it
- [x] Breaking the chest, by any cause, sends every worker home safely (the Pokemon stays in the PC throughout)

## Jobs in the first version

- [x] **Lumber**: chop natural trees in range, replant saplings
- [x] **Mining**: dig an area marked by sneak-clicking two corners with the whistle
- [x] **Sorting**: move items to linked chests that already hold that item
- [x] **Hauling**: routes from any chest to any chest (two sneak-clicks), worked on a timer from the depot (no walking), faster with more haulers; Supply Lines range 500 to 5000 blocks, far chunks loaded per load, Remote Control Add route picker; haulers fly nearby routes, far trips out of sight, percent and total hauled shown above the depot; Dispatch skill pins haulers to routes
- [~] **Farming**: harvest and replant crops and nether wart
- [~] **Berry and apricorn harvesting**: Cobblemon's own crops (part of the Farming job)
- [x] **Smelting**: Pokemon with fire act as the furnace, no fuel
- [x] **Building**: build a plan block by block from the chest (loading the plan from Litematica is `[~]`, see below)
- [~] **Demolition**: take a marked area apart and return the blocks

## Block filters

- [x] Every job site has a filter: "only these" or "all except these"
- [x] Mining: which blocks get mined (unwanted blocks are left in place)
- [~] Lumber: which tree types
- [~] Farming and harvesting: which crops
- [~] Sorting and hauling: which items (one filter per job site)
- [ ] A separate filter for each linked chest
- [~] Building and demolition: optionally skip certain blocks
- [~] One-click groups: Ores, Logs, Stone, Dirt, Crops, Ingots, Raw materials, Gems

## What decides how well a Pokemon works

**No tools.** Nothing in the chest affects what a Pokemon can break. It all comes
from the Pokemon.

- [x] Stats (level, IVs, EVs and nature included) drive speed; which stat depends on the job
- [x] Types give bonuses per job (Ground/Rock/Steel mine, Grass/Bug chop, ...)
- [x] Abilities to work in special places: flying Pokemon reach any height, water-breathers work underwater, fire-proof Pokemon work next to lava
- [x] Dual types: the better speed bonus in full plus half of the second
- [x] Mining tiers come from the Pokemon's level and type, not from a pickaxe
- [x] Some jobs need the right Pokemon (Smelting needs fire)
- [x] **Timber**: how much of a tree a Pokemon fells per swing grows with its level. One log per swing at first, 4 logs from level 20, 12 from level 35, the whole tree from level 50. The levels are in the data file, the owner is told when a worker unlocks the next stage, and the menu shows each Pokemon's Timber
- [~] Environment: the Nether, rain and water help or hinder by type
- [~] Abilities matter: speed bonuses per job, plus Pickup, Harvest, Levitate, Super Luck and Truant doing special things
- [~] Natures shape work style: Eager, Strong, Tireless, Clever, Careful
- [~] Known moves give perks: Cut fells whole trees, Dig takes whole veins, others add speed
- [~] Size matters: big Pokemon carry more and reach further, small ones are better farmers

## Upkeep

- [~] Stamina: working drains it
- [~] Rest: a worn-out Pokemon sleeps and recovers
- [~] Berries in the chest feed workers and restore stamina
- [~] Friendship raises how long a Pokemon can work
- No tool wear (there are no tools)

## Progression

- [x] Work XP earned whenever your Pokemon work
- [x] Player skill tree bought with Work XP by clicking a node (full list below)
- [~] Job mastery per Pokemon: each Pokemon ranks up in the jobs it does, Novice to Master, getting faster. Stored on the Pokemon, so it survives trades
- [~] Working gives the Pokemon a little EXP and friendship

## Skill tree

Everything is upgradable. You start small: **one chunk of work area, one worker,
one job site.** Four branches, 39 upgrades. The rules of the tree (costs, limits,
requirements, modes) are covered by unit tests, and buying through the menu was seen working.
Each upgrade below is `[~]` until its own effect has been checked in play.

Rules:

- [~] Starting levels depend on the server's mode. **Normal** is the default;
      **Relaxed** starts with more unlocked and cheaper costs; **Hardcore** costs more
- [~] The admin picks the mode and can customise the whole tree with a data pack: which
      nodes exist, costs, starting levels, maximum levels, what each level gives
- [~] Some nodes need another node first (shown as a tree in the screen)
- [~] Admin can grant Work XP to, or reset the tree of, any player
- [ ] Refunding single upgrades

**Foreman** (crew and territory)

- [x] Work Area: starts at one chunk, each level adds 8 blocks in every direction
- [~] Depth and Height: how far below and above the chest workers may go
- [~] Crew Size: workers per job site
- [~] Job Sites: how many sites you can run
- [~] Linked Chests: how many chests one site can link to
- [~] Filter Slots: how many entries a filter can hold
- [~] Remote Control: manage any of your job sites from anywhere
- [x] Night Shift: workers keep going while you are offline (only if the admin allows it)

**Labour** (how well the work gets done)

- [~] Work Ethic: speed on every job
- [~] One speed node per job: Lumber, Mining, Hauling and Sorting, Farming, Smelting, Building, Demolition
- [~] Deep Digging: workers mine one tier harder than their level allows
- [~] Tree Feller: a whole trunk comes down in one action
- [~] Vein Miner: a whole ore vein in one action
- [~] Prospector: chance of bonus ore
- [~] Bountiful Harvest: chance of a double harvest
- [~] Twin Furnace: smelt two items at once
- [~] Clean Salvage: demolition returns more of the blocks
- [~] Master Builder: a second block per action when one is in reach

**Care** (keeping the crew happy)

- [~] Endurance: more stamina
- [~] Power Nap: shorter rests
- [~] Hearty Meals: berries restore more
- [~] Second Wind: chance an action costs no stamina
- [~] Bonding: friendship from work grows faster
- [~] Training: more EXP from work
- [~] Fast Learner: job mastery grows faster
- [~] Safety Gear: rain, deep water and the Nether no longer slow workers down

**Logistics** (moving things around)

- [~] Pack Mule: carry more per trip
- [~] Fleet Feet: workers move faster
- [~] Long Haul: longer distance between a site and its linked chests
- [~] Item Magnet: pick up dropped items from further away
- [~] Auto-Restock: builders also take materials from linked chests
- [~] Tidy Up: workers with nothing to do collect items lying around the work area
- [~] Quick Recall: a stuck worker gets back on track sooner

## Building from Litematica

- [~] Place a schematic with Litematica as normal, select it, press Load Litematica in the job site
- [x] Pokemon build the plan in a sensible order from blocks in the chest (bottom up; torches, doors and other attached blocks after what they hang from)
- [x] The site stops by itself when the build is finished, and uses exactly the blocks it needs
- [~] Missing blocks pause the build and the site says which block is needed
- [ ] A full material checklist before starting
- [~] Progress survives restarts
- [~] Works on servers (the placement is sent to the server; Litematica stays client-side)
- Litematica is optional: every other job works without it
- Not placed in this version: water and lava, and the contents of chests, signs and other blocks that hold data

## Look and feel

- [~] Work animations on the existing Cobblemon models (their own attack animations) for every job
- [x] Particles per action, themed by the Pokemon's type (sounds not checked)
- [x] Block-crack progress while breaking
- [x] Workers turn to face what they are working on
- [x] The marked area and work area are outlined while you hold the whistle
- [~] Workers visibly carry what they hold (where the model supports it)
- [~] Icons above the head: needs something, can't reach, asleep
- [~] Resting Pokemon actually sleep

## Screens

- [x] **Job Site menu**: job, crew with 3D Pokemon icons and star ratings, filter tab, start/pause, status
- [x] **Skill tree**
- [x] **Work Dex**: star ratings per job for any species, and your best Pokemon for each job
- [x] **Foreman's Board**: every job site, who is working, what it is waiting for (its pause and open buttons are `[~]`)
- [x] **Guide**: eight pages explaining the mod in game
- [~] Tooltips on hover (the breakdown of why a Pokemon is good at a job)

## Notifications

- [~] Job finished
- [~] Materials ran out / chest full
- [~] A Pokemon is resting
- [~] A worker can't reach its target

## Server owner controls

Simple (for new owners):

- [~] One mode switch: Relaxed / Normal / Hardcore
- [~] Plain on/off switches: may workers break blocks, place blocks, work while the
      owner is offline; stamina on or off; max workers and job sites per player
- [x] In-game Server tab for operators shows the settings (changing them from there is `[~]`)
- [~] Safe defaults: workers only change blocks their owner could, never touch other players' sites or linked chests

Advanced (for experienced owners):

- [~] Full config file with every number exposed
- [x] The rules load from data packs: which types, abilities and moves help which job, per-species adjustments, the whole skill tree
- [ ] Permission nodes so limits can differ by rank (operators only for now)
- [~] Dimensions can be switched off
- [ ] Per-region rules
- [~] Performance knobs: work budget per tick, workers per chunk, search frequency
- [~] Audit log of what workers changed
- [~] Admin commands to list, pause or remove any player's job sites

## Needed for a public release

- [ ] Tooltip support: worker status when you look at a Pokemon
- [ ] Recipe-viewer support: job info pages
- [~] In-game guide (the Guide tab)
- [x] Original art only (nothing copied from Cobblemon)
- [x] Final name and mod ID that are not already taken on Modrinth
- [x] Licence file
- [ ] Mod page text and screenshots
- [x] Automated tests: 15 unit tests for the rules, 11 in-game server tests for the jobs, and a self-driving client that plays through the mod and saves screenshots

## Performance

- [~] A fixed work budget per tick, shared across all job sites
- [~] No full-area scans every tick; searches are spread out and cached
- [~] Idle job sites cost close to nothing
- [~] Effects run on the client from one small packet per finished action
- [ ] Stress test: 20 workers across 5 sites stays inside the lag budget
- [x] The mod-optimizer check passes (build settings, jar around 350 KB, no per-tick problems found by review)

## Later updates (not in the first version)

- Fishing (Water types)
- Ranching (shear, collect eggs, milk)
- Lighting (Electric and Fire types place torches in dark spots)
- Guarding (Fighting and Dark types defend the site at night)
- Terraforming (flatten or fill an area)
- Team synergies between workers of different types
- Day and night preferences by type
- Rest spots (beds and campfires speed up recovery)
- Build hologram (percent done, time left, checklist floating over the build)
- Advancements
- Loading schematic files without Litematica, Create schematics, WorldEdit files
- NeoForge version
- A build for Cobblemon 1.8

## Decisions made by the owner

| Date | Decision |
|---|---|
| 2026-11-13 | Publish publicly |
| 2026-11-13 | Schematics come from live Litematica placements only |
| 2026-11-13 | Upkeep (stamina, rest, berries) but no tools; work ability comes from the Pokemon |
| 2026-11-13 | Block filters on every job |
| 2026-11-13 | First-version extras: mastery, abilities, natures, Work Dex, Foreman's Board, notifications, EXP from work, Smelting, Berry harvesting, Demolition |
| 2026-11-13 | Simple presets for new server owners, full advanced settings for experienced ones |
| 2026-11-13 | Name: **Foremon** ("Cobblemon Workforce" and "Cobbleworks" were already taken) |
| 2026-11-13 | Skill tree: work area starts at one chunk, everything is upgradable, modes start at different levels, admin can customise all of it |
| 2026-11-13 | Timber: a Pokemon-only ability that unlocks at set Pokemon levels |
