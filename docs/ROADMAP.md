# Roadmap: what is unfinished, and what players will ask for

Written from the code as it is today, the tests that exist, and the test runs in your pack.
Effort: **S** = under an hour, **M** = an afternoon, **L** = days.
The full feature status (proven / built but unseen / not built) is in [FEATURES.md](FEATURES.md).

## 1. Fix or check before anyone else plays

Status after testing everything on 2026-11-13:

| What | Result |
|---|---|
| The freeze in your pack | **Not Foremon.** One hang of more than 200 seconds in one new world, inside C2ME while it loaded an entity riding another. The same Foremon jar ran in another new world with no freeze while its menus were used. C2ME is now updated in your pack from 0.4.0-alpha.0.23 to alpha.0.30 (the old file is kept as `.disabled`). Not yet confirmed in your pack |
| Litematica building | **Fixed and proven.** The test found two real bugs: the Load plan button asked Litematica for a box it only fills in while "render enclosing box" is switched on, and the call it used instead does not exist in older Litematica builds. Both are fixed, and in the self-driving game a Machop built a 42-block schematic placed with Litematica (floor, walls and a torch) exactly, and the site stopped by itself |
| Farming | **Proven** by an in-game test: ripe wheat harvested, replanted, wheat in the chest |
| Hauling | **Proven**, including each chest's own filter: cobblestone and dirt end up in the right chests |
| Demolition | **Proven:** the marked blocks come down, nothing below them, blocks come back |
| Stamina, berries, sleeping | **Proven:** a tired worker eats a berry from the chest, and with none left it falls asleep and shows the icon |
| Sending a worker home | **Proven:** the Pokemon stays safe in the PC, no longer marked as working |
| Crafting recipe | **Proven:** the server has the recipe and it makes the whistle |
| Worker blinking after a Work Area upgrade | **Fixed and proven:** the roaming box now grows straight away (16 blocks per side for two upgrades) |
| Tired, hungry and done icons | **Fixed:** tired below a fifth of stamina, hungry when there are no berries, done when a site has finished. Hungry is covered by the upkeep test |
| Release basics | **Fixed:** mod icon, author line, and a fixed build tool version (Loom 1.11.8) |
| Extra chests in someone else's claim | **Fixed:** only chests you could open yourself can be added, and workers re-check every time |
| Sounds | **Not checked:** the self-driving game can only take pictures |
| Two players on one server | **Not checked:** needs a second player. Each site's work is tied to its owner, so two players' sites should not interact, but nobody has seen it |
| A real dedicated server | **Partly:** all 23 in-game tests run on a headless server that loads the mod the way a dedicated server does |

## 2. Built halfway

| What | What is missing | Effort |
|---|---|---|
| Building | A material list before you start, and a "still missing" list while it runs | M |
| Building | Plans bigger than the work area: today the part outside it is dropped | M |
| Building | Chest contents, signs, banners, water and lava are not placed | L |
| Farming | Melons, pumpkins, sugar cane, cocoa, sweet berries, bamboo, cactus are ignored | M |
| Lumber | Big 2x2 trees are cut but only one sapling is replanted | S |
| Filters | One filter per site; Sorting cannot have a different filter per chest | M |
| Skill tree | No way to refund one upgrade, only a full reset by an admin | S |
| Server controls | Limits per rank (permission mods), regions where workers may not work | M |
| Server controls | Settings only for operators; nothing to see lag per player or per site | M |
| Pack support | No Jade/WTHIT tooltip on workers, no REI/EMI pages, no advancements | M each |
| Languages | English only | S per language |
| Cobblemon 1.8 | The mod targets 1.7.3; most players have moved to 1.8.x | M to L |

## 3. What players will want, in my opinion

### First evening (new players)

- **A clear first step.** Today you have to know the whistle exists. A short "Foremon" advancement line (craft the whistle, first site, first log, first upgrade) teaches the mod and gives a reason to come back. *M*
- **Something to do at level 5 to 15.** Starters are slow and every perk starts at level 10 or later. A small early perk per job keeps the first hour fun. *S*
- **Calling workers back by name** from the Foreman's Board, and a "where is my Pokemon" glow. *S*
- **A daily report:** "Machop chopped 214 logs today". Players love numbers about their Pokemon. *S*

### Weeks in (long-term and automation players)

- **Chains between sites:** lumber feeds a smelter, the smelter feeds a builder. Hauling between job sites is half there. *M*
- **Per-Pokemon history and ribbons.** Cobblemon has ribbons (marks); give one at 1,000 logs, 10,000 blocks, and so on. They show in the summary screen for free. *S*
- **Day and night shifts**, and Ghost and Dark types working better at night. *S*
- **Rest spots:** a bed or campfire near the site makes naps shorter. *S*
- **New jobs:** fishing (Water), ranching (shear, eggs, milk), lighting (place torches), guarding (fight mobs at night). *M each*
- **Off-screen work** when nobody is near a site (like Poke Jobs: the Pokemon is "away" and brings back results), so big bases do not need loaded chunks. *L*

### Builders

- **A material list with a "fill the chest" button**: pull what is needed from extra chests and say what is short. *M*
- **Builders that use scaffolding** so non-flyers can build tall things, and take it down after. *L*
- **Move or re-anchor a plan** without uploading again. *S*

### Server owners

- **Ranks and permissions** (LuckPerms through the Fabric permissions API). *M*
- **Named claim mod support** (Open Parties and Claims, FTB Chunks, Flan) instead of relying on the general break check. *M*
- **A lag command:** time spent per site and per player. *S*
- **Remove a banned player's sites** in one command (exists for online players only). *S*

### Pack makers and video makers

- **Tooltips** on workers (job, status, stamina) with Jade. *S*
- **Recipe viewer pages** that explain each job. *S*
- **A memorable moment on camera:** the whole tree and its leaves coming down at once already looks good. A sound cue and the Pokemon's cry on level-ups would sell it. *S*

### Pokemon fans

- **Moves that look like moves:** Cut leaves a slash, Rock Smash cracks rocks, Psychic lifts items with a purple glow. *M*
- **Personality:** Truant naps, Pickup brings random items home, shinies leave sparkles, friendship hearts while working. Some of these exist in the rules but are invisible. *S to M*
- **Hats and outfits** using Cobblemon's own cosmetic items (hard hats for miners). *M*

## 4. Skill tree ideas

Already in: 38 upgrades in four branches (Foreman, Labour, Care, Logistics).

| New upgrade | What it does | Branch |
|---|---|---|
| **Roster** | More workers in total across all sites (today only the server setting limits that) | Foreman |
| **Type Training: [type]** | One upgrade per Pokemon type: that type works faster everywhere. 18 small upgrades that make favourite types matter | Labour |
| **Field Manual** | Workers get the Cut and Dig perks without knowing the move | Labour |
| **Team Spirit** | A bonus when a site has workers of different types | Care |
| **Night Shift Crew** | Ghost and Dark types work faster at night, Grass types by day | Care |
| **Rest Camp** | A bed or campfire near the site makes naps shorter | Care |
| **Scaffolding** | Builders and miners place and remove their own scaffolding | Labour |
| **Delivery Network** | Hauling between job sites, not just to chests | Logistics |
| **Foreman's Eye** | See what every worker is doing, and a glow through walls | Foreman |
| **Expeditions** | Sites keep producing while unloaded, at reduced speed | Foreman |
