# Prior art: creatures that work for you (Cobblemon addons, MC worker mods, game-design references)

Research date: 2026-11-13. Read-only research; nothing in the project was modified.
All numbers (downloads, versions) are snapshots from that date.

Local clones used (all under `scratchpad/refs/`):

| Folder | What | Commit / version | License |
|---|---|---|---|
| `cobbleworkers/` | Accieo/cobbleworkers, LAST open-source release | 2986bde, mod_version 1.8.0+1.7.0 | MPL-2.0 |
| `cobblebase/` | notlown/cobblebase | 00a05e2, 2.0.1+1.8.0 | MPL-2.0 |
| `minecolonies/` (sparse) | ldtteam/minecolonies pathfinding + worker AI | 3438c06 (version/main) | GPL-3.0 |
| `create/` (cloned by another agent) | Create mc1.21.1/dev | 4148fd8 | code MIT, assets ARR |
| `structurize/`, `litematica/` (other agents) | | | GPL-3.0 / LGPL-3.0 |

Paths below are relative to those folders unless stated. "Cobblemon src" = the 1.7.3 sparse checkout.

---

## 0. TL;DR

1. **The idea is not new and the space got crowded during 2026.** At least 13 Cobblemon addons put Pokemon to work. Two are big: Cobbleworkers (692k Modrinth downloads) and Cobblebase (98k). Neither is in the user's pack.
2. **The name "Cobblemon Workforce" is taken** (CurseForge "Cobblemon WorkForce" by ZungGames, mod id `cobblemon_workforce`, Fabric+NeoForge 1.21.1, 11k downloads). "Cobbleworks" is taken too. "Cobblemon Workers" is one letter from Cobbleworkers. Rename before anything is published.
3. **What nobody does yet** (verified against every page/source read): (a) Pokemon that physically walk and build from a player-supplied `.litematic`; (b) marked-area excavation with real block breaking; (c) speed from types + stats/IVs + moves + abilities combined; (d) a player skill tree; (e) a plain chest as the job site. Farming, hauling, sorting, tree chopping are commodity features that 4-6 mods already ship.
4. **The whole genre has the same four bug classes**: unreachable-target loops, leaked claims / in-memory state, crashes and dupes at the inventory boundary, and "it stops when I leave". Every one is documented in issue trackers below. Design against those four first.
5. **Looks vs TPS is a real trade-off the incumbents lost**: Cobblebase stripped all job particles for TPS, Cobbleworkers has zero animation, Cobblemon Operators avoids real entities entirely. A mod that looks good AND stays cheap needs client-driven effects, not server-spawned particles.

---

## 1. Cobblemon addons where Pokemon work

### 1.1 Overview table

| Mod | Where | MC / Cobblemon | Loader | Popularity | License / source | Model |
|---|---|---|---|---|---|---|
| **Cobbleworkers** | modrinth.com/mod/cobbleworkers (id i3XVG2gn), CF project 1296055 | 1.21.1; 2.0.5+1.7.0 is last 1.7 build (2026-07-01); current 2.0.7+1.8.3 (2026-11-01) | Fabric + NeoForge, **server-only** | 691,906 dl, 122 followers | MPL-2.0 up to 1.8.0+1.7.0; **2.x is closed source** (README notice) | Pasture block = job hub; ~30 jobs; datapack jobs in 2.x |
| **Cobblebase** | modrinth.com/mod/cobblebase (kPojQdms) | 1.21.1; 2.0.0+1.7.0 last 1.7 build (2026-05-19, 83k dl); 2.0.1+1.8.0 current | Fabric + NeoForge, client+server | 98,164 dl | MPL-2.0, github.com/notlown/cobblebase | "Palworld-style": pasture = base, 42-43 jobs, per-species proficiency 1-5, big GUI, **has a Builder job** |
| **Cobblemon Pasture Loot** | modrinth.com/mod/cobblemon-pasture-loot (XP2jcAo0) | 1.20.1, 1.21.1; needs Cobblemon >=1.7.1 | Fabric + NeoForge | 4,532,608 dl | Modrinth says AGPL-3.0-or-later; the jar in the pack says "All-Rights-Reserved" | Pastured Pokemon passively drop their drop-table items. **INSTALLED in the user's pack.** |
| **Cobblemon WorkForce** | curseforge.com/minecraft/mc-mods/cobblemon-workforce (CF 1589364) | 1.21.1; Cobblemon version not stated | Fabric + NeoForge | 11,042 dl | MIT, no source link | Machines powered by Pokemon: Poke Generator, Miner, Lumber, Farm, Furnace, Crusher, Breeder, Vacuum; slot upgrades by type |
| **Cobblemon Operators** | curseforge.com/minecraft/mc-mods/cobblemon-operators | 1.21.1 / **1.7.3** | Fabric only; needs GeckoLib 4.7.6 | 1,302 dl (created ~Oct 2026) | MIT, no source link | 17 machines (lumberjack, farm, mine, crusher, crafter, sorter, guard, ...). Assign from a filtered PC GUI. **Client-side Pokemon models** animate; mining is loot generation |
| **Pokework** | curseforge.com/minecraft/mc-mods/pokework (CF 1759785) | 1.21.1 / **1.8.2** | Fabric + NeoForge | 502 dl (created Oct 2026) | MIT, no source link | Send out a party Pokemon, sneak-right-click, pick a job on the interaction wheel. 13 jobs by type. Stats matter (see 1.3) |
| **Cobblemon Environment Interactions** | modrinth Gso5otOx | 1.21.1 / 1.7.3 | Fabric + NeoForge | 43,952 dl | Apache-2.0, no source | Mining/utility while riding or commanding ONE companion Pokemon |
| **Cobblemon Mike Skills** | modrinth Pz7mD5PJ | many | Fabric/Forge/NeoForge | 25,428 dl | MIT, no source | Player skill progression; moves like Cut / Dig drive player-side effects. Not worker automation |
| **Cobblemon Farmers** | modrinth cLpjYd7Z, github.com/Chakyl/cobblemon-farmers | **1.20.1 Forge only** | Forge | 20,162 dl | MIT, open source | Workstations: Craft Station, Mystery Mine, Ranching, Gardening; job by type |
| **CobblemonJob** | modrinth.com/mod/cobblemonjob | 1.21.1 | Fabric | 4,034 dl; last update 2025-08-22 (stale beta) | Apache-2.0, no source | Pokemon break stone/wood/crops, light furnaces, pick up items; job level + stamina; JSON config |
| **Gotta Work'Em All** | curseforge .../workem | 1.21.1 | NeoForge | 1,272 dl | MIT | A treadmill: Pokemon generate Create rotation |
| **CobbleMiners** | modrinth adDZk9Td | 1.21.1 | NeoForge only | 506 dl | ARR | Cobbleworkers add-on: Ground types mine ore blocks placed under the pasture (8 wide / 5 high) into dust |
| **Cobblemon Mining Site** | curseforge .../cobblemon-mining-site | 1.21.1 / >=1.8.0 | Fabric + NeoForge | 95 dl | MIT | Craftable site block; nearby pastured/party Ground type produces ore per cycle; skill level by evolution stage |
| **Cobblemon Field Survival** | modrinth WgKLo7jS | 1.21.1 / 1.7.3 | NeoForge | 3 dl | ARR | Fielded Pokemon as survival companions |

Not worker mods despite the name: "Jobs Cobb" (player professions), "Cobbleworks [Cobblemon]" (Fakemon pack), "WorkForce" (Forge 1.20.1 roleplay job restrictions).

Sources: Modrinth API (`api.modrinth.com/v2/project/<slug>`, `/version`, `/search`), CurseForge project pages, fetched 2026-11-13.

### 1.2 Cobbleworkers (the incumbent) - how it is built

Read from the last open-source tree (1.8.0+1.7.0, Yarn mappings, `gradle.properties:6,16,28`).

- **One mixin does everything**: `common/src/main/java/accieo/cobbleworkers/mixin/PokemonPastureBlockEntityMixin.java:30` injects at TAIL of `PokemonPastureBlockEntity.TICKER$lambda$0`. That is the synthetic method of Cobblemon's `internal val TICKER = BlockEntityTicker<...> { ... }` (Cobblemon src `block/entity/PokemonPastureBlockEntity.kt:100`). Each tick it walks `pastureBlock.getTetheredPokemon()`, resolves `tethering.getPokemon()` then `pokemon.getEntity()`, skips fainted / null entity / `PoseType.SLEEP` (mixin lines 40-58), and calls `WorkerDispatcher.tickPokemon`.
- **Job model**: `interfaces/Worker.kt:16-38` - `jobType`, `blockValidator`, `shouldRun(pokemonEntity)`, `tick(world, origin, pokemonEntity)`. `jobs/WorkerDispatcher.kt:24-47` is a hard-coded list of 22 singleton objects. A Pokemon runs EVERY job it qualifies for each tick (`:72-76`).
- **Deferred area scan** (worth copying): `utilities/DeferredBlockScanner.kt:36-81` iterates `BlockPos.stream(Box(pasture).expand(radius,height,radius))` at `blocksScannedPerTick` positions per tick (default 15, `config/CobbleworkersConfig.kt:89`; radius 8, height 5 at `:91,93`), caches hits per job type in `CobbleworkersCacheManager`, then sleeps 60 s (`SCAN_COOLDOWN_TICKS`, `:23`).
- **Navigation**: plain vanilla `pokemonEntity.navigation.startMovingTo(x, y, z, speed)` throttled to once per 20 ticks per Pokemon (`utilities/CobbleworkersNavigationUtils.kt:35,56-71`). "Arrived" = bounding box intersects the target block expanded by 1 (`:40-43`). No reachability check.
- **Claims**: global maps `pokemonToTarget` / `targetedBlocks`, claim timeout 140 ticks, "recently expired" blacklist 300 ticks (`:23-35,166-192`).
- **Items**: carried items live in a per-job in-memory map `heldItemsByPokemon` (`jobs/CropHarvester.kt:30`) - lost on restart. Inventories are a hard-coded block set: chest, trapped chest, barrel, Cobblemon gilded chests (`utilities/CobbleworkersInventoryUtils.kt:28-39`), accessed through vanilla `Inventory`. If no inventory is found, items are dropped at the Pokemon's feet (`:197`).
- **Eligibility**: by type list or by species name from config; species matched via `species.translatedName.string.lowercase()` (`jobs/CropHarvester.kt:99`) which broke in non-English locales (issue #35).
- **No speed model at all**: no stats, IVs, level, moves or abilities. No animations, no particles, no GUI (server-only).

**Issue tracker (github.com/Accieo/cobbleworkers/issues, 55 issues, all read by title, key ones by body):**

| # | Complaint | Lesson |
|---|---|---|
| 7, 59, 66, 69 | Workers path forever toward an unreachable apricorn / a block behind a wall / get hard-stuck on a 1-pixel hitbox corner / stuck on the deposit chest until pushed | Need reachability test before claiming, a per-worker blacklist, and an escalation ladder |
| 73 | "Harvest workers permanently stop claiming targets after long uptime - leaked ClaimService block claims (no TTL)". Dedicated server, ~16 h uptime, restart fixes it | Global claim maps leak. Claims must be owned by the worker/site state and die with it |
| 65 | "Working cobblemons stop working when the owner is in a different dimension", even with force-loaded chunks and another player nearby | Decide and test owner-offline / other-dimension behaviour explicitly |
| 71, 68 | **Server crash on the Cobbleverse pack** when a Bug type harvests an apricorn near Sophisticated Storage; world crash when a Pokemon picks a mint near a Fossil Machine (Cobbleverse 1.7.31, Cobblemon 1.7.3) | The user's exact pack. The inventory boundary with modded storage is where this genre crashes |
| 79 | Cauldron dupe | Take-then-place must be atomic |
| 48 | "PKMN run like the damn Flash ... zipping around" | Do not express work speed as run speed; use species walk speed and scale the action time |
| 6, 41, 55, 60 | Only vanilla containers; requests for Iron Furnaces, Pam's, Create | Use a storage API, not a block list |
| 57, 70, 75 | No way to turn workers off per pasture / per job | Per-site on/off and per-job toggles from day one |
| 13, 38 | Requests: IV/EV-based productivity; legendaries more productive | Demand exists for our stat-based speed model |
| 11, 25 | Requests: miner workers, ore generation | Demand exists for mining |
| 72, 62 | Update 1.8.0 -> 2.0.2 "stopped all pasture jobs from functioning" | Version saved data; write migrations |
| 35 | Hard-coded species names break in other languages | Use resource identifiers |

### 1.3 Pokework - closest in spirit to our stat model

CurseForge page only (closed source). Jobs by type: Chop (Fighting, whole natural trees), Mine (Steel/Rock, exposed natural stone and ores), Dig (Ground), Courier (Flying, moves items from workers to chests), Cook (Fire), Water, Harvest (Bug), Plant (Grass), Haul (Normal), Guard, Repel (Ghost), Freeze (Ice), Bloom (Fairy). Dual types get both job sets.

Stat mapping: **Attack / Sp. Atk = larger work area and Fortune I-III; Speed = every job faster; HP = larger pack (9 to 54 slots); Level = tool tier for mining (stone 15, iron 30, diamond 50, netherite 70).** Each Pokemon has its own pack saved on the Pokemon.

Anti-grief rules worth copying verbatim as a concept: it will not break player-placed blocks, generated structures, blocks that hold items, logs of trees without natural leaves, or configured protected areas. Block tags `pokework:chop|mine|dig` are datapack-editable.

Differences from us: no job site (the Pokemon works "where it stands" until recalled), needs Cobblemon 1.8.2 (not usable in the 1.7.3 pack), no building, no skill tree, no area marking.

### 1.4 Cobblebase - the one with a Builder

Read from source (MPL-2.0).

- **Same pasture hook**: `common/src/main/java/notlown/cobblebase/mixin/PokemonPastureBlockEntityMixin.java:142` injects TAIL of `TICKER$lambda$0`; also HEAD of `releasePokemon` (`:39`) and HEAD + TAIL of `checkPokemon` (`:57,110`). It forces `ticksUntilCheck` down to 100 to "keep entities alive" (`:153-154`).
- **Extra global mixins**: `PokemonEntitySleepLockMixin.java:53-56` (HEAD of `PokemonEntity.tick`), `PastureLeafCollisionMixin.java:27-32` (HEAD of `AbstractBlock.getCollisionShape` - a very hot method - to let pasture Pokemon pass through leaves), plus two client "crash fix" mixins into Cobblemon rendering.
- **Speed model**: hand-authored per-species skills with proficiency 1-5 (1,367 species JSON). README: "not based on type, but on identity". No stats/IVs/moves.
- **Builder** (`common/src/main/kotlin/notlown/cobblebase/core/executors/BuilderExecutor.kt`):
  - The file's own doc comment (`:28-42`): "Resource flow is 'magic': blocks are pulled from a nearby chest and placed at the target position with a brief particle + sound flourish - the Pokemon doesn't physically walk to either point. This keeps the implementation tractable for the MVP and avoids pathfinding through partially-built structures."
  - One block per cooldown; plan sorted bottom-up `compareBy(y, x, z)` (`:223`); each tick does a linear `plan.firstOrNull { world.getBlockState(pos) != state }` over the whole plan (`:97`).
  - Item cost = the item with the same registry id as the block (`:103-104`); blocks with no item form (fire, water, portals) are **placed for free** (`:105-110`).
  - `world.setBlockState(next.worldPos, next.state, 3)` (`:121`) overwrites whatever is there. No drop of the replaced block, no protection/claim check, no block-entity data, no deferral of attached blocks (torches, doors, beds).
  - Templates are **vanilla structure `.nbt` only**, discovered from datapacks: `StructureTemplateRegistry.kt:12-23,88-133` (vanilla `structures/` via `StructureTemplateManager.streamTemplates()`, plus Cobblemon's singular `structure/` path read with `NbtIo.readCompressed`). No `.litematic`, no player uploads. The scan of 5000+ NBT files took 5+ s on the main thread and was moved to a daemon thread (`:59-79`).
  - Client: ghost-block preview + wireframe + HUD (`fabric/.../client/render/BuildPreviewRenderer.kt`, `BuildPreviewHud.kt`), job status panel polled every 2 s while the tab is open (CHANGELOG-1.5.5.md:390-409), top-8 material histogram per template (`:414-424`).
  - "Builder helpers": other Pokemon fetch missing materials via a claim ledger with 30 s TTL (`BuilderHelperCoordinator.kt`, CHANGELOG-1.5.5.md:349-386).
- **Mining is fake**: `executors/MiningExecutor.kt:29-36` - "Cooldown-based loot generation (like FinderExecutor), not block harvesting."
- **Animations**: `core/effects/SkillEffects.kt:40-52` builds `PlayPosableAnimationPacket(pokemonEntity.id, setOf(name, "<species>:<name>"), emptyList())` and sends it with `CobblemonNetwork.sendPacketToPlayer` to a cached nearby-player list. Names used: "physical", "special", "sleep", "faint", "recoil", "cry" (`AmbientBehavior.kt:51-52,173-198`). Cobblemon src confirms the packet is a public class: `net/messages/client/animation/PlayPosableAnimationPacket.kt:25-28` (`entityId: Int, animation: Set<String>, expressions: List<String>`), and `CobblemonNetwork.sendPacketToPlayer` is public (`CobblemonNetwork.kt:620`).
- **Hard-won notes in their code/changelogs** (each is a trap we would otherwise walk into):
  - `setAiDisabled(true)` on a PokemonEntity stops movement but **breaks Cobblemon animations** (`PokemonEntitySleepLockMixin.java:34-36`). Clearing `WALK_TARGET` / `PATH` / `LOOK_TARGET` brain memories from a block-entity tick races with the entity's own brain tick; it must happen at HEAD of the entity tick (`:21-33`).
  - "Tether resize REMOVED - shrinking Cobblemon's 64-block tether caused mons to despawn into the PC" (`CHANGELOG-2.0.md:176`).
  - "Particle stripping - every job except Healer no longer emits its working particles. The visual noise was costing real TPS on busy bases" (`CHANGELOG-1.5.0.md:11`).
  - A stale-state sweep that used the cooldown timestamp as heartbeat silently disabled 12 jobs for a whole release (`CHANGELOG-1.5.5.md:75-83`).
  - Held items map was memory-only; "on server restart any in-flight items were silently lost" (`CHANGELOG-1.5.5.md:165`).
  - Buff executors re-applied effects and scanned players every tick: dominant tick cost until throttled (`CHANGELOG-1.5.5.md:188-190,248-249`).
  - Stuck handling went through at least four designs: 15 s auto-teleport -> removed because mons escaped pens -> random-direction redirect + velocity nudge -> clipped-in-solid escape, breadcrumb retrace, leaf escape (`CHANGELOG.md:88-89,164,267-268`; `NavigationHelper.kt:346-420`, stuck threshold 140 ticks at `:553`).
  - Item dupes: the "visual item entity" a gatherer shows above its head was picked up by the pickup search (`CHANGELOG-1.5.0.md:96`).
- **Issues** (github.com/notlown/cobblebase/issues, 8 total): #2 crash with **C2ME** ("ThreadLocalRandom accessed from a different thread") caused by using `world.random` off-thread, fixed by switching to `ThreadLocalRandom`; #5 RAM leak report (~1.5 GB); #7 `NoSuchMethodError: SpawnDetail.getBucket()` on Cobblemon 1.8.0 - **Cobblemon 1.7 -> 1.8 broke binary compatibility**; #3 produced diamonds had extra NBT and would not stack.

### 1.5 Cobblemon Operators - the performance-first design

CurseForge page only. Notable choices: assignment through a **filtered Cobblemon PC interface**; workers are **client-side models** (no server entity pathfinding at all); Mine/Crusher "generate loot without breaking terrain" inside a 9x3x9 protected structure; uses **Fabric's storage API** for adjacent inventories and hoppers; a **recovery queue** returns the Pokemon to party/PC if delivery fails when a machine is broken; `/operators machines disable|enable|status` global kill switch that persists across restarts; "scheduling budgets" are configurable. Targets exactly Cobblemon 1.7.3 + Fabric, so it is the mod a Cobbleverse player would most likely compare us with.

### 1.6 Conflict / overlap with our mod

- **Installed in the user's pack** (checked all 137 file names in the `mods` folder):
  - `pastureLoot-1.0.5+1.21.1.jar` (mod id `pasture-loot`). javap of its mixin: `@Inject(method="TICKER$lambda$0", at=HEAD)` on `PokemonPastureBlockEntity`. Passive drops only. No functional overlap, no conflict unless we also touch the pasture ticker.
  - `SafePastures-1.1.1+1.21.1.jar`. javap: `@Inject(method="damage", at=HEAD)` on `PokemonEntity`, gated on `getTethering()`. It protects **tethered (pastured) Pokemon only**. Our workers will not be covered unless they are real pasture tetherings, so we need our own damage rule.
  - `fightorflight-fabric-0.10.9.jar` (wild Pokemon aggression) and `neruina` (ticking-entity crash guard) are present - relevant to worker safety but not prior art.
  - **Not installed**: Cobbleworkers, Cobblebase, Operators, WorkForce, Pokework, Create, Litematica, MineColonies, any claim mod (no FTB Chunks / Flan / Open Parties and Claims file names).
- **If a player adds Cobbleworkers or Cobblebase later**: both only act on Pokemon tethered to a Pasture block, so a Pokemon cannot be in both systems as long as our assignment makes it unavailable to pastures. Three mods already stack injections on `TICKER$lambda$0` (Cobbleworkers TAIL, Cobblebase TAIL, PastureLoot HEAD); staying off that method avoids the pile-up. Cobblebase's `AbstractBlock.getCollisionShape` mixin only changes leaves tracked around a pasture, for `PokemonEntity` contexts - a lumberjack worker of ours standing near someone's Cobblebase pasture would walk through those leaves.
- **Name/ID**: the draft uses name "Cobblemon Workforce" and mod id `workforce` (`cobblemon-workforce/src/main/resources/fabric.mod.json`). Different id from ZungGames' `cobblemon_workforce`, so both could load together, but the display name is identical. See section 2.

---

## 2. Name check

| Candidate | Modrinth | CurseForge | Verdict |
|---|---|---|---|
| Cobblemon Workforce | slug `cobblemon-workforce` free; slug `workforce` TAKEN (WorkForce, Forge 1.20.1 roleplay mod, 154 dl) | **TAKEN**: "Cobblemon WorkForce", slug `cobblemon-workforce`, ZungGames, 1.21.1 Fabric+NeoForge, MIT, 11k dl, jar `cobblemon_workforce-fabric-1.0.1` | Do not use |
| Cobbleworks | TAKEN: modpack `cobbleworks` + mod `cobbleworks-fw` "Cobbleworks [Cobblemon]" (16k dl) | TAKEN: "CobbleWorks" mod and modpack, "CobbleWorks Industrial Era" | Do not use |
| PokeJobs | free (404, 0 search hits) | no exact match (closest: "The Poke Isles Reworked Jobs+") | Free, but "Poke Jobs" is Nintendo's own feature name from Sword/Shield, and generic |
| Cobblemon Workers | free (404) | no exact match | Avoid: one letter from "Cobbleworkers" (692k dl); guaranteed confusion |

Five distinctive free names (Modrinth slug API returned 404 and search returned 0 relevant hits for all five; CurseForge search checked where noted):

| Name | mod id | Why | CurseForge |
|---|---|---|---|
| **Foremon** | `foremon` | foreman + mon; one word, says "boss of a work crew", searchable | no exact match (fuzzy hits only) |
| **Cobblemon: Jobsite** | `cobblejobsite` | names our unique mechanic (chest -> job site) | 0 results for "cobblemon jobsite" |
| **Mons at Work** | `monsatwork` | plain, memorable, reads like a road sign | not checked |
| **Fieldhands** | `fieldhands` | echoes "field moves"; short | not checked |
| **Taskmon** | `taskmon` | short, obvious | not checked |

Rejected after checking: "Helping Hand" (Modrinth slug `helpinghand` exists), "CobbleCrew" (a CurseForge modpack `cobble-crew` exists), "Workmons" (CF search surfaces Cobblemon WorkForce first).

---

## 3. Non-Cobblemon worker mods: what they had to solve

### 3.1 MineColonies (GPL-3.0; builder, miner, lumberjack, farmer, courier)

- **Pathfinding was rewritten from scratch.** `core/entity/pathfinding/Pathfinding.java:50` runs path jobs on a `ThreadPoolExecutor(1, 1, ...)`; jobs read the world through a snapshot `world/ChunkCache.java` (implements `LevelReader`) so the worker thread never touches live chunks. There is one path-job class per intent (`pathjobs/PathJobMoveToLocation`, `PathJobFindTree`, `PathJobMoveCloseToXNearY`, `PathJobRandomPos`, ...). This is years of work; it exists because vanilla navigation gives up on long or vertical routes.
- **Escalating stuck handler**: `navigation/PathingStuckHandler.java:329-427`. Ladder: skip one path node -> clear and recalc path -> walk away 20-40 blocks in a rotating direction -> teleport N nodes along the existing path -> place ladders / leaf bridges -> break blocks -> "complete stuck": teleport to goal. Guard rails: `MIN_TP_DELAY = 120 * 20` ticks, `MIN_DIST_FOR_TP = 10` (`:45-46`), random chance to step the level back down (`:431-438`). Each capability is opt-in per entity (`withTeleportSteps`, `withBlockBreaks`, `withPlaceLadders`, `:622-701`).
- **Player-facing escape hatch**: the official troubleshooting advice for stuck workers is "pause the hut, press Restart" (minecolonies.com/wiki/misc/troubleshooting via search summary). A manual reset button is part of the design, not an admission of failure.
- **Logistics is its own game**: request system + warehouse + couriers (minecolonies.com/wiki/systems/request). Common complaints: builder idle because it "can't reach" the site, waiting on deliveries, a full warehouse stalling crafters, too few couriers. Builders have a work radius around the hut.
- **Take-away**: do not build a request system or custom A*. Take the escalation ladder idea and the "restart worker" button.

### 3.2 Create Schematicannon (code MIT)

It is not an AI worker but it is the best reference for "build a schematic from a chest of materials in survival".

- **State machine**: `content/schematics/cannon/SchematicannonBlockEntity.java` - `State.STOPPED / RUNNING / PAUSED`, with explicit pause reasons `missingItem` (`:92`) and `positionNotLoaded` (`:93`). When the next target is in an unloaded chunk it pauses instead of loading it (`:381-389`), and resumes by itself.
- **Options** (`:114-116`): `replaceMode` (don't replace solid / replace solid with solid / replace any / replace with empty), `skipMissing`, `replaceBlockEntities`.
- **Deferred placement**: `content/schematics/SchematicPrinter.java:54,298-312,350-352` - blocks that need support (`BlockMovementChecks.isBrittle`: torches, rails, doors, redstone...) go to `deferredBlocks` and are placed after everything else.
- **Block -> item cost**: `content/schematics/requirement/ItemRequirement.java:98-134`. Special cases: double slab = 2 slabs, turtle eggs / sea pickles / snow layers = count from state, farmland and dirt path = dirt, banners need the exact item, tall grass = 2 short grass, `INVALID` for unobtainable blocks. `ItemUseType.CONSUME | DAMAGE` (`:190-191`). Registry for mod overrides: `api/schematic/requirement/SchematicRequirementRegistries.java`.
- **Material checklist**: `cannon/MaterialChecklist.java` - prints required vs. gathered; this is the UX players expect.
- **Upload safety**: `content/schematics/ServerSchematicLoader.java:104-155,179` - `.nbt` only, `maxTotalSchematicSize`, `maxSchematics` per player, `maxSchematicPacketSize`, idle timeout. Block-entity NBT goes through `api/schematic/nbt/SafeNbtWriterRegistry.java` and `ComponentProcessors.withUnsafeComponentsDiscarded` so a schematic cannot smuggle filled containers, spawners or command blocks.

### 3.3 Minion / golem / villager-in-a-box mods (Easy Villagers, Operators-style machines)

The lesson from this family (and from Cobblemon Operators and Cobblemon WorkForce, which follow it): **no world AI means no AI bugs**. The worker is a block with a timer and a pretty model. It is cheap and reliable and players accept it. Cobblebase's Builder and Mining, and Operators' Mine, all quietly use this trick. Our pitch ("real work in the world") is the expensive road; every place we fall back to "magic" should be a deliberate choice, not an accident.

### 3.4 Problem -> known solution table

| Problem | Who hit it | What worked |
|---|---|---|
| Target unreachable, worker loops forever | Cobbleworkers #7/#59/#66, MineColonies | Reachability check before claim; blacklist target for N seconds; escalate |
| Worker physically stuck | all | Ladder: repath -> sidestep -> short teleport along path -> teleport to site. Cobblebase found blind teleport lets mons escape pens; MineColonies rate-limits teleport to once per 120 s |
| Claims / per-worker state leak | Cobbleworkers #73, Cobblebase 1.5.5 | State owned by a worker object stored with the site; TTL on every claim; one sweep with a real heartbeat |
| Items lost or duped | Cobbleworkers #79, Cobblebase 1.5.0/1.5.5 | Persist carried items; atomic take+place; never represent carried items as real ItemEntities |
| Modded storage crashes | Cobbleworkers #71/#68 (on Cobbleverse) | Fabric Transfer API (`ItemStorage.SIDED`) with transactions (Operators does this) |
| Chunk not loaded | Create | Pause with a visible reason; do not force-load |
| Area scan cost | Cobbleworkers | N blocks per tick, cached, rescan cooldown |
| Every-tick work in hot loops | Cobblebase 1.5.5 | Throttle scans to 1/s when idle; per-site tick budget |
| Particles cost TPS | Cobblebase 1.5.0 | They removed them. Better: one small "action" packet, effects spawned client-side |
| Griefing | Pokework | Never break player-placed blocks, containers, structures; tag-driven allow lists; Create's replace modes |
| Schematic abuse | Create | Size caps, sanitised block-entity NBT, no free unobtainable blocks |
| Cobblemon update breaks addon | Cobblebase #7, Cobbleworkers #72/#74 | Pin the Cobblemon version; avoid `internal` members and synthetic lambda names |
| Off-thread world access | Cobblebase #2 (C2ME is in the user's pack) | All world reads/writes and `level.random` on the server thread only |

---

## 4. Game-design references

### 4.1 Palworld base work

- 12 work suitabilities: Kindling, Watering, Planting, Generating Electricity, Handiwork, Gathering, Lumbering, Mining, Medicine Production, Cooling, Transporting, Farming; each Pal has a level per suitability (palworld.wiki.gg/wiki/Work_suitability). Transporting level = more items per trip.
- What is fun: you can read a Pal's usefulness at a glance (icons + numbers); catching a new species immediately changes your base; the base visibly hums.
- What is annoying (guides and patch notes): Pals stuck on each other or terrain, large Pals worst (upcomer.com/how-to-fix-stuck-or-lost-pals-in-your-palworld-base); stuck Pals cannot eat, then go hungry and stop; Pals pick the wrong job until the Monitoring Stand "Set Work Preference" was added; v0.1.4.0 patch notes list fixes for Pals stuck in geometry and "dropping items on the spot mid-transport"; hunger/SAN upkeep reads as a chore once the base is large. The standard player fix is "put it back in the Palbox and redeploy".
- Take-away: clear suitability display - yes. Explicit job assignment instead of auto-priority - yes. Hunger/sanity upkeep - no (or strictly optional). A one-click "recall and redeploy" - yes.

### 4.2 Pokemon's own systems

- **HMs / field moves** (Cut, Strength, Rock Smash, Surf...): fun because your own partner changes the world. Hated because they cost a move slot and forced "HM slaves"; replaced by Poke Ride in Sun/Moon, which some players found impersonal (thegamer.com/pokemon-best-changes-games, nintendosoup HM removal article, pokecommunity thread). Lesson: **reward knowing Cut / Dig / Rock Smash / Strength, never require it.**
- **Poke Jobs (Sword/Shield)**: send boxed Pokemon for 1-24 h; each job asks for a type and matching Pokemon earn about 20% more; more Pokemon = better reward tiers (serebii.net/swordshield/pokejobs.shtml). Fun: uses the box, type flavour text. Weak: nothing to watch, purely a timer.
- **Poke Pelago (Sun/Moon)**: islands with 3 development ranks that raise capacity (6/12/18 Pokemon) and yields; beans as a temporary speed-up (serebii.net/sunmoon/pokepelago.shtml). Lesson: capacity-by-rank is exactly our "more workers per site" skill node, and it felt good there.
- **Pokemon Sleep**: per-Pokemon helping speed, **carry limit**, specialty (berries / ingredients / skills), sub-skills like Inventory Up and Helping Speed (serebii.net/pokemonsleep/helperpokemon.shtml, game8 Pokemon Sleep wiki). Lesson: speed + carry capacity + one specialty is a proven minimal stat set for helpers.
- **Legends: Arceus**: throw a partner at a tree or ore deposit and it gathers for you - short, visible, satisfying (from memory, not re-fetched; confidence "likely").
- **Pokemon Pokopia (2026)**: each Pokemon has up to two **specialties**: Build ("leads building projects"), Bulldoze, Chop, Burn, Crush, Gather ("picks up ground items and puts them in a community box"), Grow, Water, Generate, Storage, Fly... (serebii.net/pokemonpokopia/specialty.shtml; nintendolife specialties guide). Reviews praise the loop (Gaming Trend 95, Press Start, Engadget); the most cited weakness is inventory management. Lesson: the official game now ships "a Build-specialty Pokemon leads a construction project" - players arriving in 2026-27 will expect that vocabulary, and "leader + helpers" is the accepted fiction for multi-Pokemon builds.

### 4.3 What makes it fun vs annoying (synthesis)

Fun: the specific Pokemon matters and you can see why; visible progress in the world; set-and-forget that still works when you come back; small readable numbers.
Annoying: silent stalls with no reason shown; babysitting stuck workers; upkeep meters; mandatory moves; workers that run at absurd speed; needing to stand next to them.

---

## 5. Recommendations

### Copy
1. **Deferred, budgeted area scan** with cache and rescan cooldown (Cobbleworkers `DeferredBlockScanner.kt`). Idea only; rewrite in Mojang mappings.
2. **Explicit state machine with a visible pause reason** per site: Running / Paused(missing item X) / Paused(area not loaded) / Paused(no path) / Done (Create `SchematicannonBlockEntity`). Show the reason and the missing item icon in the GUI and above the chest.
3. **Deferred placement of attached blocks, replace modes, skip-missing, block->item requirement table with special cases, material checklist** (Create `SchematicPrinter`, `ItemRequirement`, `MaterialChecklist`; MIT, may be adapted with attribution).
4. **Escalating unstuck ladder with rate-limited teleport** (MineColonies `PathingStuckHandler`; GPL - reimplement the idea, do not copy code).
5. **Pokework's protection rules and its stat mapping** as a starting point: Speed -> rate, Attack/Sp.Atk -> reach or yield, HP -> carry capacity, level -> harvest tier.
6. **Claims with TTL owned by the site**, per-site on/off, a "restart workers" button, a global admin kill switch (Operators), a recovery path that returns the Pokemon to party/PC if anything goes wrong.
7. **Fabric Transfer API** for every container interaction.
8. **Ghost preview + progress + "next needed block"** for builds (Cobblebase did this well).

### Avoid
1. Hooking `PokemonPastureBlockEntity.TICKER$lambda$0` or any synthetic name; three mods already sit there and it is `internal`.
2. Global singletons keyed by UUID with no owner (`heldItemsByPokemon`, `targetedBlocks`): the source of #73 and of lost items.
3. Expressing work speed as movement speed.
4. Server-side `spawnParticles` every few ticks per worker.
5. `setAiDisabled(true)` to hold a Pokemon still (breaks Cobblemon animation).
6. Hunger / sanity upkeep; mandatory HM-style moves.
7. Free placement of blocks that have no item; overwriting existing blocks without a rule.
8. Hard-coded container block lists; species matched by translated name.
9. Any world or RNG access off the server thread (C2ME in the pack will crash it).
10. Scope creep toward 40 jobs. Cobblebase has 43 and its changelog is mostly regressions.

### Differentiate
1. **Real building from the player's own `.litematic` / `.nbt`, with Pokemon that visibly walk, fetch and place.** Nobody has it; Cobblebase's builder is admitted "magic" and datapack-only.
2. **Marked-area excavation that really breaks blocks.** Everyone else rolls loot tables.
3. **Speed from the individual Pokemon**: types (dual types combine) + stats/IVs + known moves + ability, shown as a readable breakdown in the GUI. Cobbleworkers users asked for this (#13) and it was closed.
4. **Chest as job site**: no new machine block to learn, works with what the player already built.
5. **Player skill tree** (sites, workers per site, range, per-job speed).
6. **Presentation**: client-side effects and real Cobblemon animations, which the two leaders either removed or never had.

Suggested order (my opinion, for the planner to weigh): lead with Lumber + Mining + Build since those are the differentiators; Farming, Sorting and Hauling exist in 4-6 mods already and can ship later without weakening the pitch.

### Open-source code worth reading before design

| Code | License | Read for | Can we reuse? |
|---|---|---|---|
| `refs/cobbleworkers` (whole tree is ~45 files) | MPL-2.0 | Pasture mixin, scanner, claims, deposit loop; the bug list in section 1.2 | File-level copyleft: copied files stay MPL and must be published. Cleaner to reimplement ideas. It is Yarn-mapped anyway |
| `refs/cobblebase` `core/executors/BuilderExecutor.kt`, `StructureTemplateRegistry.kt`, `NavigationHelper.kt`, `effects/SkillEffects.kt`, `mixin/PokemonEntitySleepLockMixin.java`, all CHANGELOG files | MPL-2.0 | Builder plan/transform, template discovery, stuck recovery history, how to send Cobblemon animations, the brain-vs-tick race | Same as above |
| `refs/create` `content/schematics/**`, `api/schematic/**` | MIT (code) | Printer, deferred blocks, ItemRequirement, checklist, upload limits, NBT sanitising | Yes with attribution (code only, not assets) |
| `refs/minecolonies` `core/entity/pathfinding/**` | GPL-3.0 | Stuck ladder, async path jobs, chunk snapshot | Ideas only; copying would force GPL on the mod |
| `refs/structurize`, `refs/litematica` | GPL-3.0 / LGPL-3.0 | Blueprint and `.litematic` formats | Formats are facts; do not copy code into an MIT jar |
| github.com/Chakyl/cobblemon-farmers | MIT (Forge 1.20.1) | Workstation pattern, type->job mapping | Not cloned; low priority |

---

## 6. Risks

- Name collision is certain if nothing changes (section 2).
- The ecosystem moved to Cobblemon 1.8.x (latest 1.8.4, 2026-10-27) and 1.8 broke binary compatibility for at least one addon; the pack is on 1.7.3. A 1.7.3-only build will not load for most new players, and a later port is not free.
- "Real work in the world" is the road with the most documented failure modes. The two most-downloaded competitors both fell back to magic placement or loot tables for the hard jobs.
- Cobbleworkers went closed source after 1.8.0; the tree I read may not reflect how 2.x solved things (issue #73 was filed against 2.0.4, so some problems clearly persisted).
- The user's pack already produced two Cobbleworkers crash reports around modded storage (Sophisticated Storage, Fossil Machine). Both mods' storage is in the pack (`sophisticatedstorage`, `toms_storage`).
- MPL/GPL contamination if code is copied rather than reimplemented.
- Closed-source rivals (Operators, Pokework, WorkForce) were assessed from store pages only; their real behaviour may differ.

## 7. Open questions

1. What actually caused Cobbleworkers #65 (work stops when the owner is in another dimension)? Is it Cobblemon's PC-store lookup in `Tethering.getPokemon()` (src `PokemonPastureBlockEntity.kt:70-74`), entity despawn, or the addon? It decides what our workers do when the owner is away or offline.
2. Root cause of the two Cobbleverse crashes (#71, #68) was never posted. Unknown whether it was the vanilla `Inventory` path, a Cobblemon block entity, or another mod.
3. Does the user ever intend to publish? If not, the name clash only matters cosmetically; if yes, also decide 1.7.3-only vs 1.7.3 + 1.8.x.
4. CurseForge availability of "Mons at Work", "Fieldhands", "Taskmon" was not checked (only Modrinth).
5. How do Operators' client-side worker models look in practice, and is that an acceptable fallback for distant / unloaded sites?
6. Should our workers be covered by SafePastures-style invulnerability by default, given Fight or Flight is in the pack?
