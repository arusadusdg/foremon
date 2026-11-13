# Cobblemon Workforce - research notes: which Pokemon data can drive "who is good at which job"

Scope: read-only research against Cobblemon 1.7.3 (source checkout + built jar) and the user's COBBLEVERSE instance.
Nothing in the project was modified, Gradle and Minecraft were not run.

Conventions
- `K:` = path relative to `cobblemon-src/common/src/main/kotlin/com/cobblemon/mod/common/` in the checkout.
- `JAR:` = entry in `fabric-1.7.3+1.21.1.jar`.
- `PACK:` = path under the instance `COBBLEVERSE - Pokemon Adventure [Cobblemon]/`.
- Confidence: **V** = verified by reading the file myself, **L** = likely (strong inference), **G** = guess.
- Web tools: not used. Everything below comes from local source/jar/instance files, which are authoritative for 1.7.3.

Helper scripts and raw outputs (all under `scratchpad/refs/`):
- `scan_species.py` - species scan (run with the jar; optional 2nd arg = a datapack zip to merge `species_additions`).
- `sim_model.py` - dry run of the proposed suitability model over all implemented species.
- `reports/scan_output.txt` (jar: all 1025 + implemented 851), `reports/scan_output_pack.txt` (jar + COBBLEVERSE datapack, 996 implemented), `reports/sim_output.txt`.

---------------------------------------------------------------------------------------------------

## 1. API to read from a Pokemon / species (1.7.3)

All of this is in `com.cobblemon.mod.common.pokemon.Pokemon` (`K:pokemon/Pokemon.kt`, `open class Pokemon` at line 174),
`Species` (`K:pokemon/Species.kt`) and `FormData` (`K:pokemon/FormData.kt`).
**Always read through `pokemon.form` (FormData), not `pokemon.species`** - every form getter falls back to the species
(`FormData.kt:125-199`), and regional forms override type, stats, abilities, learnset, hitbox and behaviour.

### 1.1 Quick table

| Data | How to read | Visibility | Evidence |
|---|---|---|---|
| Types | `pokemon.primaryType`, `pokemon.secondaryType`, `pokemon.types: Iterable<ElementalType>` (delegates to form). Type id: `ElementalType.showdownId` (`"ground"`), `.name` | public | `Pokemon.kt:412-419`, `K:api/types/ElementalType.kt` |
| Base stats | `pokemon.form.baseStats: Map<Stat, Int>` keyed by `Stats.HP/ATTACK/DEFENCE/SPECIAL_ATTACK/SPECIAL_DEFENCE/SPEED` | public | `FormData.kt:125`, `K:api/pokemon/stats/Stats.kt:21-30` |
| Computed stats | `pokemon.getStat(Stats.X)` (open fun) or `pokemon.attack / defence / specialAttack / specialDefence / speed / maxHealth` | public | `Pokemon.kt:540-554, 693`; formula `K:pokemon/stat/CobblemonStatProvider.kt:88-104` |
| IVs / EVs | `pokemon.ivs[Stats.SPEED]` (Int?), `pokemon.ivs.getEffectiveBattleIV(stat)` (honours hyper training), `pokemon.evs.getOrDefault(stat)`; mutate with `pokemon.setIV/setEV`, `pokemon.evs.add(stat, n, SidemodEvSource("workforce", pokemon))` | getters public; the `ivs`/`evs` property *setters* are `internal` | `Pokemon.kt:219-272`, `K:pokemon/IVs.kt`, `K:pokemon/EVs.kt:61`, `K:api/pokemon/stats/EvSource.kt:47` |
| Nature | `pokemon.nature`, `pokemon.mintedNature`, `pokemon.effectiveNature` (`increasedStat`, `decreasedStat`, `favouriteFlavour`, `dislikedFlavour`) | public | `Pokemon.kt:459-470`, `K:pokemon/Nature.kt` |
| Level / XP | `pokemon.level` (1..config.maxPokemonLevel = 100), `pokemon.addExperience(SidemodExperienceSource("workforce"), xp)`, `addExperienceWithPlayer(player, source, xp)` (also sends the XP toast packet and level-up friendship) | public (`experience` setter internal) | `Pokemon.kt:288, 1967, 2012`; `K:api/pokemon/experience/ExperienceSource.kt:30`; `K:config/CobblemonConfig.kt:44` |
| Friendship | `pokemon.friendship` (0..255), `setFriendship/incrementFriendship/decrementFriendship(amount)`; event `CobblemonEvents.FRIENDSHIP_UPDATED` | getter public, setter private, mutators public | `Pokemon.kt:363-372, 1493, 1511, 1529`; `CobblemonConfig.kt:48` |
| Ability | `pokemon.ability.name` = Showdown id (`"sandrush"`, `"pickup"`); `pokemon.ability.template`; species pool `pokemon.form.abilities` (`CommonAbility` / `HiddenAbility`) | getter public, setter internal | `Pokemon.kt:531-538`, `K:api/abilities/Ability.kt`, `Abilities.kt` (ids come from Showdown, lower-case, no separators) |
| Known moves | `pokemon.moveSet` (`Iterable<Move>`, 4 slots; `move.template.name` = Showdown id), `pokemon.benchedMoves` (`Iterable<BenchedMove>`, `.moveTemplate.name`) - every move it ever knew / was taught, swappable any time; `pokemon.allAccessibleMoves` = level-up moves <= level + benched + evolution moves | public | `Pokemon.kt:472, 523, 1594`; `K:api/moves/MoveSet.kt`, `BenchedMove.kt:25,128` |
| Learnset ("can it learn Cut?") | `pokemon.form.moves: Learnset` with `levelUpMoves: Map<Int, List<MoveTemplate>>`, `tmMoves`, `tutorMoves`, `eggMoves`, `legacyMoves`, `specialMoves`, `evolutionMoves`, `formChangeMoves`; query `LearnsetQuery.LEGAL.canLearn(Moves.getByName("cut")!!, pokemon.form.moves)` (or `.ANY` to include legacy/special) | public | `K:api/pokemon/moves/Learnset.kt:82-109`, `LearnsetQuery.kt:26-45`, `K:api/moves/Moves.kt:110` |
| Move template | `MoveTemplate.name, num, elementalType, damageCategory, power, accuracy, pp, priority` - **no Showdown `flags`** (slicing / punch / ...) are exposed | public | `K:api/moves/MoveTemplate.kt:44-57` |
| Held item | `pokemon.heldItem()` returns a **copy**; change with `swapHeldItem(stack, decrement, aiCanDrop)` / `removeHeldItem()`; `heldItemVisible`; events `HELD_ITEM_PRE/POST`. The backing field and `heldItemNoCopy()` are `internal` | see left | `Pokemon.kt:664, 669, 1171, 1181, 1195` |
| Height / weight | `pokemon.form.height` (decimetres), `pokemon.form.weight` (hectograms: Machamp = 1300 = 130 kg) | public | `FormData.kt:189-196`, `Species.kt:115-125` |
| Hitbox / scale | `pokemon.form.hitbox: EntityDimensions` (blocks, before scale), `pokemon.form.baseScale`, `pokemon.scaleModifier`. Real entity size = `hitbox * baseScale * scaleModifier` | public | `FormData.kt:130-133`, `Pokemon.kt:556`, `K:entity/pokemon/PokemonEntity.kt:1254-1255` |
| Egg groups | `pokemon.form.eggGroups: Set<EggGroup>` (MONSTER, WATER_1, BUG, FLYING, FIELD, FAIRY, GRASS, HUMAN_LIKE, WATER_3, MINERAL, AMORPHOUS, WATER_2, DITTO, DRAGON, UNDISCOVERED) | public | `FormData.kt:181`, `K:api/pokemon/egg/EggGroup.kt:20-34` |
| Labels | `pokemon.form.labels: Set<String>`, `pokemon.hasLabels("baby")`, `isLegendary()`, `isMythical()`, `isUltraBeast()`. Constants in `CobblemonPokemonLabels` (`legendary, restricted, mythical, ultra_beast, fossil, powerhouse, baby, paradox, gen1..gen9, *_form, mega, gmax, ...`; the data also uses `starter`, which has no constant) | public | `Pokemon.kt:1124-1149`, `K:api/pokemon/labels/CobblemonPokemonLabels.kt:22-222` |
| Aspects / forms | `pokemon.aspects: Set<String>` (e.g. `"alolan"`, `"shiny"`, `"male"`), `pokemon.forcedAspects` (settable), `pokemon.form.name`, `pokemon.form.aspects`, `pokemon.features` (SpeciesFeature list). Form is re-resolved from aspects server-side (`species.getForm(aspects)`) | getter public; `aspects` setter private | `Pokemon.kt:564, 605-629`, `Species.kt:208` |
| Can fly / swim / walk | `pokemon.form.behaviour.moving.fly.canFly`, `.swim.canSwimInWater`, `.swim.canBreatheUnderwater`, `.swim.canWalkOnWater`, `.swim.avoidsWater`, `.swim.canSwimInLava`, `.swim.canWalkOnLava`, `.swim.canBreatheUnderlava`, `.walk.canWalk`, `.walk.avoidsLand`, `.walk.walkSpeed` (MoLang expr). On a live entity: `entity.canFly()`, `canWalk()`, `canSwimInWater()`, `canWalkOnWater()`, `canSwimInLava()`, `isFlying()`, `setBehaviourFlag(PokemonBehaviourFlag.FLYING, b)` | public | `K:pokemon/ai/FlyBehaviour.kt:18`, `SwimBehaviour.kt:21-28`, `WalkBehaviour.kt:19-20`, `FormPokemonBehaviour.kt:66-67`, `PokemonEntity.kt:2555-2577` |
| Fire / lava immunity | `pokemon.isFireImmune()` = Fire type OR `canSwimInLava` OR `behaviour.fireImmune` | public | `Pokemon.kt:950-952` |
| Shoulder-mountable | `pokemon.form.shoulderMountable` (84 species in the jar, `shoulderEffects` are all empty in 1.7.3 data) | public | `FormData.kt:151`, scan |
| Rideable | `pokemon.form.riding.behaviours != null` (map `RidingStyle{LAND,AIR,LIQUID}` -> settings with stat ranges SPEED / ACCELERATION / STAMINA / JUMP / SKILL); `pokemon.getBaseRideStat(stat)`, `getRideBoost(stat)`, `rideStamina` | public | `K:api/riding/RidingProperties.kt`, `Pokemon.kt:390, 1866-1880` |
| Gender / shiny | `pokemon.gender` (`Gender.MALE/FEMALE/GENDERLESS`), `pokemon.shiny` | public | `Pokemon.kt:333, 446`, `K:pokemon/Gender.kt` |
| Fullness | see 1.3 | public | `Pokemon.kt:374-383, 957-1073` |
| Interaction cooldowns | `pokemon.interactionCooldowns: MutableMap<ResourceLocation, Int>` (ticks), `isOnInteractionCooldown(id)`, `resetInteractionCooldown(id)`, `tickInteractionCooldown(ticks)` (open). Persisted and sent to the client | public | `Pokemon.kt:399, 1035-1056`; codecs `K:util/codec/internal/PokemonP3.kt:104`, `ClientPokemonP3.kt:77` |
| Marks | `pokemon.marks: MutableSet<Mark>`, `activeMark`, `potentialMarks`, `exchangeMark(mark, give = true)`, `addPotentialMark`, `applyPotentialMarks()`; registry `Marks.getByIdentifier(ResourceLocation)` | public | `Pokemon.kt:573-590, 1269-1322`, `K:api/mark/Marks.kt` |
| Drops | `pokemon.form.drops: DropTable` (`entries`, `amount`, `getDrops(amount, pokemon)`, `drop(...)`); event `LOOT_DROPPED` | public | `FormData.kt:168`, `K:api/drop/DropTable.kt:38-87` |
| Free-form addon data | `pokemon.persistentData: CompoundTag` (mutate the tag, then call `pokemon.onChange()` to mark the store dirty). Saved with the Pokemon and included in the client codec | getter public, setter internal, `onChange` public | `Pokemon.kt:653-659, 2134`; `PokemonP2.kt:108`, `ClientPokemonP2.kt:88` |
| Owner | `pokemon.getOwnerUUID()`, `getOwnerPlayer()`, `belongsTo(player)`, `isPlayerOwned()`, `isWild()`, `storeCoordinates` | public | `Pokemon.kt:1459-1482` |
| Blocks travelled | `pokemon.getBlocksTraveled()`, `addBlocksTraveled(n)` (only has effect when the species has the `blocks_traveled` feature) | public | `Pokemon.kt:1939-1947` |

### 1.2 Stat formula (matters for balancing)

`K:pokemon/stat/CobblemonStatProvider.kt:88-104` (V):

```kotlin
// non-HP
pokemon.effectiveNature.modifyStat(stat, ((2 * base + iv + (ev / 4)) * level) / 100 + 5)   // nature = x1.1 / x0.9
// HP
truncate(truncate(2.0 * base + iv + truncate(ev / 4.0) + 100) * level / 100.0 + 10)
```

Typical values of a non-HP computed stat: L5 ~ 10, L20 base 50 ~ 28, L50 base 70 (IV 15) = 82, L50 base 130 = 142,
L100 base 100 (IV 31, no EV) = 236, L100 base 130 fully trained with a boosting nature = 394. So the computed stat
already blends species identity, level, IVs, EVs and nature - one number per job is enough.

### 1.3 Fullness (the 1.7 "hunger" system) - full description

Fields and methods, all public on `Pokemon` (V):

| Member | Meaning | Line |
|---|---|---|
| `var currentFullness: Int` | 0..`getMaxFullness()`. Setter clamps, posts `CobblemonEvents.FULLNESS_UPDATED` (`FullnessUpdatedEvent(pokemon, newFullness)`, `newFullness` is mutable) and sends `FullnessUpdatePacket` | `Pokemon.kt:374-383` |
| `fun getMaxFullness(): Int` | `(getGrassKnotPower(species.weight) / 10 / 2) + 1` | `:957` |
| `fun getGrassKnotPower(weight)` | 20/40/60/80/100/120 by weight bracket (thresholds 21.8 / 54.9 / 110.1 / 220.3 / 440.8) | `:960-968` |
| `fun feedPokemon(feedCount: Int, playSound: Boolean = true)` | plays eat sound, returns early if `isFull()`, else `currentFullness += feedCount` (clamped); resets the metabolism cycle when it goes 0 -> 1 | `:970-995` |
| `fun loseFullness(value: Int)` | subtract, floor 0 | `:998` |
| `fun getMetabolismRate(): Int` | ticks per lost point: `((20 - (baseSpeed / BST) * 20 * 4) * 60) s * 20`, minimum 60 s | `:1003-1021` |
| `fun isFull()` | `currentFullness >= getMaxFullness()` | `:1024` |
| `var metabolismCycle: Int`, `resetMetabolismCycle()` | tick accumulator (not persisted) | `:1027-1032` |
| `open fun tickMetabolism(ticksPassed: Int)` | accumulates; when >= rate, loses 1 point and resets | `:1061-1073` |

Facts:
- **Max fullness is only 2..7.** The code feeds `species.weight` (hectograms) into thresholds commented as pounds, so
  the real brackets are: < 2.2 kg -> 2, < 5.5 kg -> 3, < 11 kg -> 4, < 22 kg -> 5, < 44 kg -> 6, >= 44 kg -> 7.
  Distribution over the 851 implemented species: `{2: 70, 3: 76, 4: 117, 5: 132, 6: 170, 7: 286}` (scan). It uses
  `species.weight`, not `form.weight`.
- **Drain**: one point every `getMetabolismRate()` ticks. Seconds per point over implemented species:
  p10 72 s, p25 229 s, median 463 s, p75 646 s, p90 793 s, max 1152 s (Shuckle; Munchlax 1138 s). 266 species need 10+ minutes per point.
- **Formula quirk (matters if the mod uses it as upkeep)**: the rate is `(20 - 80 * baseSpeed / BST)` minutes and only a
  result <= 0 is replaced by the 60 s floor. So species whose Speed is at or above 25% of BST get exactly 60 s (48 implemented
  species, e.g. Ninjask, Zigzagoon, Wattrel), while species just *below* 25% get absurdly short values: Drakloak 5 s,
  Purugly 10 s, Jolteon 11 s, Boltund 14 s, Tandemaus 19 s, Weavile 23 s, Cinccino 25 s, Crobat 33 s. 27 implemented
  species lose a point in under 60 s and 69 in under 2 minutes. It is non-monotonic and too fast to build a work economy on as is.
- **Who ticks it**: only two places call `tickMetabolism`:
  `PlayerPartyStore.tick` once per second with 20 ticks, for party members (`K:api/storage/party/PlayerPartyStore.kt:155-158`),
  and the pasture block-entity ticker with 1 tick per tick for tethered Pokemon
  (`K:block/entity/PokemonPastureBlockEntity.kt:107-117`). A Pokemon sitting in the PC and not tethered to a pasture
  **does not drain**, and its interaction cooldowns do not tick either.
- **What feeds it**: every item in `#cobblemon:poke_food` (`#cobblemon:berries/filling`, `#cobblemon:mochis`,
  `#cobblemon:aprijuices`, `cobblemon:poke_snack` - `JAR:data/cobblemon/tags/item/poke_food.json`) is refused when
  `isFull()` (`K:api/item/PokemonSelectingItem.kt:109-113`). Amounts: ordinary berries / mochi / aprijuice / berry juice +1,
  portion-healing berries +5, Poke Puff +4 (and friendship by flavour vs nature) (`K:item/PokePuffItem.kt:94-113`,
  `K:item/berry/*.kt`, `K:item/AprijuiceItem.kt:95`). AI: `EatHeldItemTask` lets a Pokemon eat its held item and calls
  `feedPokemon(itemConfig.fullnessValue, false)` (`K:entity/pokemon/ai/tasks/EatHeldItemTask.kt:57-95`); per-species
  "desired items" live in `behaviour.itemInteract.desiredItems` (only Pidgey, Vulpix, Aron, Sableye in 1.7.3).
- **What fullness does in vanilla Cobblemon 1.7.3: nothing except gate further feeding.** I grepped every use
  (`fullness` appears in 20 source files): events, MoLang (`q.pokemon.fullness`, `max_fullness`, `lose_fullness(n)`,
  `feed_pokemon(n)` - `K:api/molang/MoLangFunctions.kt:1639-1649`), the `fullness=` PokemonProperty, the summary-screen
  bar, codecs. No stat, speed, riding or battle effect reads it. The summary bar is even coloured green when empty and
  red when full (`K:client/gui/summary/widgets/screens/stats/features/FullnessFeatureRenderer.kt:36-41`) - Cobblemon
  frames it as "how stuffed", a cap on feeding, not as hunger.
- Persisted (`PokemonP3.kt:103`, int 0..100) and synced to the client (`ClientPokemonP3.kt:76`).

Can the addon reuse it as worker stamina?
- **Not as the stamina bar itself**: 2..7 integer points is too coarse, 0 is the normal resting state in vanilla,
  other mods (cobblecuisine, PastureLoot in this pack) and datapack callbacks (`fullness_updated`) share it, and
  draining it to model effort would let a worker be fed infinitely (feeding is the only thing fullness limits).
- **Yes as the food / upkeep hook** (recommended): read `currentFullness > 0` as a "well fed" buff. Because workers kept
  in the PC are not ticked by Cobblemon, the mod decides the drain itself. Do not pass work time straight into
  `tickMetabolism` (see the quirk above: Jolteon would empty a full stomach in about a minute); instead keep one counter
  per worker and call `pokemon.loseFullness(1)` every `clamp(getMetabolismRate(), 3 min, 15 min)` of work time
  (halve it for heavy jobs). Call `tickInteractionCooldown(20)` once per second too if the mod uses interaction
  cooldowns. Feeding then works with every existing food item, with the
  1.7 cooking content (Poke Puffs = 4 points) and can be automated: a worker on break takes one `#cobblemon:poke_food`
  item from the site chest and the mod calls `feedPokemon(n)`.
- React to player feeding with `CobblemonEvents.FULLNESS_UPDATED.subscribe { ... }`.

### 1.4 Things that are internal / private (an addon cannot call them without reflection or a mixin)

- `Pokemon.heldItem` field and `heldItemNoCopy()` - use `heldItem()` (copy) and `swapHeldItem`.
- Setters: `ivs`, `evs`, `benchedMoves`, `ability`, `experience`, `persistentData`, `originalTrainer*` are `internal set`;
  `friendship`, `aspects`, `characteristic`, `evolutionProxy` are `private set`. Getters are public.
- `Pokemon.isClient` is `internal`.
- `Ability` constructor is `internal`; create abilities with `AbilityTemplate.create(forced, priority)`.
- `SpeciesAdditions` object is `internal` (not needed; it is data driven).
- `Abilities.abilityScripts`, `Moves.moveScripts` are `internal`.
- `Mark.identifier` setter is internal (marks come from JSON, see 4.4).

### 1.5 What the CLIENT does not know (load-bearing for the GUI)

`Species.encode` (`Species.kt:224-263`) and `FormData.encode` (`FormData.kt:278-312`) send: name, dex number, base stats,
types, experience group, height, weight, male ratio, base scale, hitbox, **level-up moves only**, pokedex text, forms,
battle theme, features, lighting, drops, ability pool, riding. They do **not** send: `labels`, `eggGroups`,
`shoulderMountable`, `catchRate`, `baseFriendship`, TM / tutor / egg / legacy moves (`Learnset.kt:111-134`
"We only sync level up moves atm"), and of the whole behaviour tree only `combat` is sent
(`K:pokemon/ai/PokemonBehaviour.kt:49-51`). So on a client, `form.behaviour.moving.fly.canFly` is always the default
`false`, `isLegendary()` is always `false`, and "can learn Cut by TM" cannot be answered.

Consequence: **compute suitability on the server and send the result** (per job: final multiplier + a short list of
contributing factor ids) to the GUI in the mod's own packet. Do not recompute client-side.

---------------------------------------------------------------------------------------------------

## 2. Species data scan (`JAR:data/cobblemon/species/**.json`)

1025 species files; 851 have `"implemented": true` in the jar. **In the user's pack the COBBLEVERSE datapack
(`PACK:datapacks/COBBLEVERSE-DP-v31.zip`, 376 `species_additions` + 14 species overrides) raises this to 996
implemented**, adds `riding` to 233 species and replaces `behaviour` / `hitbox` / `baseScale` for ~140-180 species
(additions replace the whole property - `K:pokemon/SpeciesAdditions.kt:43-58`). Numbers below are given for the jar's
implemented set (851) and, where they differ noticeably, for the pack-merged set (996). The mod must read the live
registries at runtime and never bake a species list.

JSON shape reminder (Diglett): top-level `primaryType`, `secondaryType`, `abilities: ["sandveil","arenatrap","h:sandforce"]`
(`h:` = hidden), `baseStats{hp,attack,defence,special_attack,special_defence,speed}`, `height`, `weight`, `baseScale`,
`hitbox{width,height,fixed}`, `labels[]`, `eggGroups[]`, `moves["32:dig","tm:dig","egg:headbutt","tutor:..","legacy:.."]`,
`behaviour{moving{walk,swim,fly},resting,herd,combat,fireImmune,itemInteract}`, `drops`, `evolutions`, `forms[]`,
optional `riding`, `shoulderMountable`, `ai`, `lightingData`.

### 2.1 Species per type (a dual type counts in both)

| Type | impl. 851 | all 1025 | pack 996 |
|---|---|---|---|
| water | 142 | 154 | 153 |
| normal | 117 | 131 | 127 |
| grass | 108 | 127 | 120 |
| flying | 95 | 109 | 102 |
| psychic | 77 | 102 | 96 |
| poison | 70 | 83 | 83 |
| bug | 70 | 92 | 88 |
| fire | 69 | 81 | 80 |
| ground | 65 | 75 | 74 |
| rock | 62 | 74 | 72 |
| ghost | 54 | 65 | 64 |
| fairy | 52 | 64 | 59 |
| fighting | 52 | 73 | 72 |
| electric | 51 | 69 | 67 |
| dark | 48 | 69 | 69 |
| dragon | 46 | 70 | 70 |
| steel | 46 | 65 | 63 |
| ice | 37 | 48 | 46 |

Mono-typed: 441 of 851 (water 69, normal 66, grass 43, fire 35, psychic 31, electric 31, fighting 24, fairy 20, bug 16,
ground 16, ice 15, rock 14, poison 13, dragon 13, ghost 13, dark 12, steel 8, flying 2). Dual-typed: 410 in 118 distinct pairs.

Top 25 dual-type pairs (implemented 851): flying/normal 27, grass/poison 14, bug/poison 11, bug/flying 11, rock/water 11,
ghost/grass 10, ground/rock 9, flying/water 8, ice/water 7, ground/water 7, dragon/flying 6, fairy/psychic 6,
psychic/water 6, poison/water 6, flying/grass 6, flying/psychic 6, bug/rock 6, rock/steel 6, fighting/fire 6,
fire/flying 5, bug/grass 5, psychic/steel 5, dark/grass 5, dragon/ground 5, fairy/steel 5.
(All 1025: flying/normal 27, grass/poison 14, bug/flying 13, bug/poison 12, rock/water 11, ghost/grass 10, ground/rock 9,
ground/water 9, ice/water 8, flying/water 8, fairy/psychic 8, psychic/steel 8, dark/flying 7, rock/steel 7, dark/grass 7, ...)

Forms: 167 implemented species have forms (260 forms; 83 are `battleOnly` megas/gmax). **73 forms change the typing**
(Alolan Diglett ground -> ground/steel, Hisuian Growlithe fire -> fire/rock, Galarian Ponyta fire -> psychic,
Paldean Tauros, ...) and 107 forms declare their own `behaviour.moving`. Reading through `pokemon.form` handles all of it.

### 2.2 Movement capabilities (defaults: `canWalk = true`, `canSwimInWater = true`, `canFly = false`, `canBreatheUnderwater = false`)

| Flag | impl. 851 | pack 996 |
|---|---|---|
| `fly.canFly` | **217** (25%) | 254 |
| ... of which not Flying-type | 130 (ghost 38, psychic 33, normal 32, bug 25, steel 24, fairy 20, dragon 19, ...) | 161 |
| ... of which `walk.canWalk = false` (pure hoverers) | 97 | 105 |
| Flying-type that can NOT fly | 8: Dodrio, Doduo, Gyarados, Mantine, Natu, Mantyke, Archen, Hawlucha | 9 |
| `swim.canSwimInWater` true (default) | 803 | 918 |
| `canSwimInWater = false` explicitly | 48 | 78 |
| `swim.avoidsWater` | 666 | 708 |
| `swim.canBreatheUnderwater` | **155** (18%): water 126, rock 15, psychic 12, dragon 11, ... | 172 |
| Water-type that does NOT breathe underwater | 16: Marill, Lotad line, Pelipper, Wingull, Surskit, Ducklett, Swanna, Panpour, Simipour, Cramorant, Dracovish, Walking Wake, Wiglett, Wugtrio | - |
| Non-water species that breathe underwater | 29 | 39 |
| `swim.canWalkOnWater` | 39 | 41 |
| `walk.canWalk = false` | 104 | 114 |
| `swim.canSwimInLava` | 89 | 92 |
| `behaviour.fireImmune` | 98 | 102 |
| `Pokemon.isFireImmune()` true (fire type OR lava swim OR flag) | **99** - includes 30 non-Fire rock/steel species (Geodude line, Onix, Steelix, Rhyhorn line, Aron line, Nosepass, Regirock, Roggenrola line, Carbink, Tyrunt, Nacli line, ...) | 110 |

Take-away: "can swim" is useless as a discriminator (94% true); use `canBreatheUnderwater` (or Water type) for underwater
work and `avoidsWater` as a soft dislike. "Flying type" and "can fly" are different sets - gate height work on
`canFly`, give the speed flavour bonus on the Flying type.

### 2.3 Size

Entity size = `hitbox * baseScale` (times the per-individual `scaleModifier`).

- Large by the task's rule (raw hitbox width > 1.5 or height > 3): **114** species; with baseScale applied: **112** (13% of 851). Pack: 161 raw / 143 scaled of 996.
- Scaled width percentiles (851): p10 0.4, p25 0.5, p50 0.7, p75 1.1, p90 1.6, p99 2.9, max 8.0 (Wailord).
- Scaled height percentiles: p10 0.5, p25 0.7, p50 1.0, p75 1.7, p90 2.5, p99 4.5, max 6.5.
- Fits a 1x1x1 hole (w <= 0.9, h <= 0.95): 347. Fits a 1-wide 2-high corridor (w <= 0.9, h <= 1.95): 540. Tiny (w <= 0.5 and h <= 0.6): 86.
- Widest: Wailord 8.0, Regigigas 4.0, Rayquaza 3.75, Avalugg 3.6, Copperajah 3.5, Dondozo 3.5, Drampa 3.3, Tyrantrum 3.05, Aurorus 3.0, Hariyama 2.86, Mamoswine 2.7, Salamence 2.7, Lapras 2.64, Venusaur 2.64, Golurk 2.5, Snorlax 2.3.
- Dex height (dm): p50 10, p90 19. Dex weight (hg): p10 28, p25 85, p50 260, p75 605, p90 1258, p99 3990.

Roughly 37% of workers do not fit a 1-wide tunnel: workers must act at reach from where they can stand, not squeeze
to the target block.

### 2.4 Base stat percentiles (implemented 851; all-1025 in brackets)

| Stat | p10 | p25 | p50 | p75 | p90 | p99 | max |
|---|---|---|---|---|---|---|---|
| **Speed** | 30 (30) | 45 (45) | 63 (65) | 85 (89) | 100 (105) | 130 (136) | 200 |
| Attack | 40 | 53 | 73 | 95 | 115 | 140 | 165 (181) |
| Sp. Attack | 35 | 45 | 62 | 85 | 105 | 134.5 | 154 (173) |
| Defence | 40 | 50 | 65 | 85 | 105 | 151 | 230 |
| Sp. Defence | 40 | 50 | 65 | 81.5 | 100 | 146 | 230 |
| HP | 40 | 50 | 65 | 80 | 98 | 150 | 255 |
| BST | 275 | 315 | 420 | 495 | 528 | 600 | - |

### 2.5 Top 60 abilities by number of species that can have them (implemented 851)

swiftswim 41, sturdy 40, chlorophyll 36, intimidate 35, keeneye 34, torrent 29, overgrow 29, runaway 29, blaze 29,
sheerforce 29, owntempo 28, innerfocus 27, levitate 27, frisk 26, shellarmor 25, thickfat 25, sandveil 25, oblivious 25,
unnerve 24, guts 24, infiltrator 24, swarm 23, waterabsorb 22, flashfire 21, gluttony 21, weakarmor 21, regenerator 21,
damp 20, hustle 20, leafguard 20, rockhead 19, static 19, sapsipper 19, overcoat 18, hydration 18, flamebody 18,
pickup 18, technician 18, poisonpoint 18, moldbreaker 18, clearbody 18, pressure 17, sniper 17, lightningrod 17,
insomnia 17, unburden 17, rattled 17, rivalry 17, synchronize 16, shedskin 16, icebody 16, earlybird 16, moxie 16,
prankster 16, naturalcure 14, limber 14, pickpocket 14, telepathy 14, raindish 13, unaware 13.

(All 1025: swiftswim 44, sturdy 42, keeneye 39, chlorophyll 37, innerfocus 35, intimidate 35, levitate 32, swarm 31,
runaway 30, sheerforce 30, pressure 29, torrent/overgrow/blaze 29, owntempo 28, frisk 28, gluttony 27, sandveil 27,
thickfat 26, waterabsorb 26, unnerve 25, flashfire 25, shellarmor 25, infiltrator 25, oblivious 25, telepathy 25,
guts 24, weakarmor 23, regenerator 23, overcoat 22, hydration 22, flamebody 21, hustle 21, ... full list in scan_output.txt.)
239 distinct abilities appear on implemented species (284 over all files; Showdown defines 314).

### 2.6 Abilities that thematically fit jobs, with species counts (implemented 851 / all 1025)

| Ability | Count | Example species | Job fit |
|---|---|---|---|
| pickup | 18 / 19 | Meowth, Aipom, Phanpy, Teddiursa, Zigzagoon, Linoone, Munchlax, Pachirisu, Lillipup | hauling, bonus finds |
| harvest | 8 / 8 | Exeggcute, Exeggutor, Tropius, Phantump, Trevenant, Smoliv, Dolliv, Arboliva | farming |
| honeygather | 4 / 4 | Teddiursa, Combee, Cutiefly, Ribombee | farming by-product |
| sandrush | 8 / 10 | Sandshrew, Sandslash, Drilbur, Excadrill, Herdier, Stoutland, Dracovish, Dracozolt | mining (sand/desert) |
| sandforce | 13 / 14 | Diglett, Dugtrio, Nosepass, Probopass, Hippopotas, Hippowdon, Shellos, Gastrodon, Boldore, Drilbur | mining |
| levitate | 27 / 32 | Gastly, Haunter, Koffing, Weezing, Misdreavus, Baltoy, Claydol, Chimecho, Duskull, Bronzor | height access |
| flamebody | 18 / 21 | Magmar, Moltres, Ponyta, Rapidash, Magby, Slugma, Magcargo, Magmortar, Chandelure, Lampent | Nether / heat |
| flashfire | 21 / 25 | Arcanine, Growlithe, Vulpix, Ninetales, Ponyta, Cyndaquil, Houndour, Houndoom | Nether / heat |
| magmaarmor | 3 / 3 | Slugma, Magcargo, Camerupt | Nether / lava |
| swiftswim | 41 / 44 | Goldeen, Golduck, Horsea, Kabuto, Kabutops, Magikarp, Omanyte, Poliwag line | water / rain |
| chlorophyll | 36 / 37 | Bulbasaur line, Oddish, Gloom, Bellsprout line, Exeggcute, Tangela | daylight, farming |
| guts | 24 / 24 | Machop line, Rattata, Raticate, Heracross, Larvitar, Ursaring, Tyrogue, Flareon | works through hunger |
| sheerforce | 29 / 30 | Krabby, Kingler, Nidoking, Nidoqueen, Tauros, Totodile line, Steelix, Bagon | raw break power |
| ironfist | 12 / 18 | Hitmonchan, Ledian, Chimchar line, Timburr, Gurdurr, Conkeldurr, Golett, Golurk | mining / building |
| runaway | 29 / 30 | Caterpie, Doduo, Dodrio, Eevee, Oddish, Ponyta, Rapidash, Rattata, Venonat | hauling speed |
| truant | 3 / 3 | Slakoth, Slaking, Durant | burst-then-rest |
| slowstart | 2 / 2 | Regigigas, Varoom | slow warm-up |
| klutz | 11 / 12 | Buneary, Lopunny, Audino, Golett, Golurk, Woobat, Swoobat, Stufful, Bewear | no tool bonus |
| hustle | 20 / 21 | Nidoran, Rattata, Corsola, Delibird, Remoraid, Togepi, Durant | faster, sloppier |
| hugepower / purepower | 5 + 2 | Marill, Azumarill, Azurill, Bunnelby, Diggersby / Meditite, Medicham | small but strong |
| speedboost | 13 / 13 | Yanma, Yanmega, Torchic line, Carvanha, Sharpedo, Ninjask, Venipede, Scolipede | ramps up |
| quickfeet / unburden | 9 / 17 | Jolteon, Linoone, Ursaring / Hitmonlee, Treecko line, Drifloon, Drifblim, Hawlucha | hauling |
| sturdy / rockhead | 40 / 19 | Geodude line, Onix, Magnemite, Aron line / Cubone, Marowak, Rhyhorn, Aerodactyl | mining endurance |
| moldbreaker | 18 / 20 | Pinsir, Cranidos, Rampardos, Axew line, Drilbur, Excadrill, Druddigon | hard blocks |
| toughclaws / strongjaw / sharpness | 3 / 9 / 3 | Binacle, Barbaracle / Tyrunt, Yungoos, Chewtle, Drednaw / Gallade, Kleavor, Veluza | chopping |
| technician / skilllink | 18 / 9 | Meowth, Scyther, Scizor, Mr. Mime, Breloom, Ambipom / Shellder, Cinccino, Toucannon | building, sorting |
| frisk / keeneye / compoundeyes | 26 / 34 / 9 | Sentret, Furret, Shuppet, Banette, Duskull / Pidgey line, Hoothoot / Butterfree, Joltik | sorting |
| telepathy / synchronize / magician / pickpocket | 14 / 16 / 4 / 14 | Ralts line, Elgyem, Beheeyem / Abra line, Espeon, Natu / Fennekin line, Klefki / Sneasel, Seedot line | sorting |
| magnetpull | 5 / 6 | Magnemite, Magneton, Magnezone, Nosepass, Probopass | ores, metal items |
| serenegrace / superluck | 10 / 9 | Chansey, Blissey, Togepi line, Dunsparce / Murkrow, Honchkrow, Absol, Togekiss | bonus yield |
| drought / drizzle / sandstream / snowwarning | 3 / 2 / 4 / 3 | Vulpix, Ninetales, Torkoal / Politoed, Pelipper / Tyranitar, Hippowdon, Gigalith / Snover, Vanilluxe, Amaura | site-wide weather aura |
| raindish / hydration / solarpower / leafguard | 13 / 18 / 6 / 20 | Squirtle line, Lotad line / Seel, Lapras, Vaporeon / Charmander line, Sunkern / Chikorita line, Leafeon | weather-conditional |
| regenerator / naturalcure / shedskin | 21 / 14 / 16 | Slowpoke line, Tangela, Audino / Chansey, Celebi, Roselia / Dratini, Ekans | faster recovery |
| vitalspirit / insomnia / earlybird / stamina | 13 / 17 / 16 / 2 | Mankey, Electabuzz / Hoothoot, Drowzee, Murkrow / Doduo, Kangaskhan, Houndour / Mudbray | night shifts, less rest |
| gluttony / cheekpouch | 21 / 6 | Snorlax, Bellsprout, Gulpin, Shuckle / Bunnelby, Dedenne, Skwovet, Greedent | food economy |
| intimidate / pressure / unnerve | 35 / 17 / 24 | Arcanine, Gyarados, Tauros / legendaries, Aerodactyl / Meowth, Tyranitar | keeps hostile mobs off the site |
| blaze / torrent / overgrow / swarm | 29 / 29 / 29 / 23 | all starters, most Bug lines | "pinch" boost when tired |
| lightmetal / heavymetal | 5 / 7 | Scizor, Beldum line / Aron line, Bronzor, Cufant | speed vs carry |
| healer / friendguard / plus / minus | 10 / 9 / 9 / 6 | Chansey, Audino / Clefairy, Jigglypuff / Mareep line, Klink line / Electrike, Minun | co-worker aura |
| defeatist / stall | 2 / 1 | Archen, Archeops / Sableye | penalties |
| ballfetch | 1 / 1 | Yamper | fetches dropped items |

With the ability lists used in the dry run (section 6), 622 / 851 implemented species have at least one ability with an
always-on job effect, and 812 / 851 when the conditional and aura abilities are included; only 39 species have nothing mapped.

---------------------------------------------------------------------------------------------------

## 3. Field moves

Moves and abilities are **not** JSON data in the jar: both registries are filled from the Showdown runtime
(`K:api/moves/Moves.kt:41-90`, `K:api/abilities/Abilities.kt:36-63`; ids are Showdown ids). The data lives in
`PACK:showdown/data/moves.js` (951 entries) and `abilities.js` (314 entries). All 15 classic field moves exist there (V):

| Move id | Type / category / power | Showdown `isNonstandard` | Species that can learn it - implemented 851: any source / legal (level, TM, tutor, egg) / by level-up | all 1025: legal |
|---|---|---|---|---|
| `cut` | Normal physical 50 | Unobtainable | 228 / 228 / 1 | 259 |
| `dig` | Ground physical 80 | - | 337 / 330 / 36 | 379 |
| `strength` | Normal physical 80 | - | 316 / 314 / 10 | 348 |
| `rocksmash` | Fighting physical 40 | - | 380 / 380 / 27 | 430 |
| `surf` | Water special 90 | - | 211 / 210 / 17 | 229 |
| `fly` | Flying physical 90 | - | 84 / 81 / 7 | 105 |
| `flash` | Normal status | Past | 266 / 264 / 2 | 299 |
| `waterfall` | Water physical 80 | - | 139 / 134 / 3 | 141 |
| `dive` | Water physical 80 | - | 132 / 132 / 14 | 140 |
| `rockclimb` | Normal physical 90 | Past | 100 / 95 / 1 | 107 |
| `defog` | Flying status | - | 128 / 117 / 6 | 140 |
| `whirlpool` | Water special 35 | - | 161 / 142 / 20 | 149 |
| `teleport` | Psychic status | - | 48 / **12** / 12 (36 more are `legacy:` only) | 17 |
| `sweetscent` | Normal status | - | 90 / 67 / 52 | 74 |
| `headbutt` | Normal physical 70 | - | 430 / 379 / 89 | 421 |

Notes:
- In Cobblemon's data the old HMs are simply `tm:` entries, so "legal" is almost entirely TM. **Vanilla Cobblemon 1.7.3
  has no TM items** (only `/teach`, `K:command/TeachCommand.kt`); a Pokemon gets non-level-up moves through sidemods.
  This pack ships **TMCraft** (`PACK:mods/tmcraft-1.4.18+1.7.3.jar`), which has craftable `tmcraft:tm_cut`, `tm_dig`,
  `tm_strength`, `tm_rocksmash`, `tm_surf`, `tm_fly`, `tm_flash`, `tm_waterfall`, `tm_dive`, `tm_rockclimb`, `tm_defog`,
  `tm_whirlpool`, `tm_teleport`, `tm_sweetscent`, `tm_headbutt`. Its `MoveTeachingItem` checks the learnset
  (`getTmMoves/getTutorMoves/getEggMoves/getLevelUpMoves`) and then adds to `moveSet` or `benchedMoves` (javap of the class). So in this pack "teach it Cut" is a real player action.
- **Check `moveSet` OR `benchedMoves`**, not only the four active moves: benched moves are Cobblemon's "known but not
  equipped" list, and requiring an active slot would force players to ruin battle movesets. Moves reachable by level are
  in `allAccessibleMoves` even if never equipped.
- Without any TM mod only the "by level-up" column is reachable (Cut 1 species, Fly 7, Strength 10, Dig 36, Headbutt 89).
  The model therefore must not *require* field moves; they should be bonuses / conveniences, plus a broad fallback (next point).
- Broad move coverage without long tables: Showdown tags moves with flags; `slicing` (27 moves: cut, aerialace, aircutter,
  airslash, furycutter, leafblade, nightslash, psychocut, razorleaf, xscissor, slash, ...) is known by level-up by 222
  implemented species (450 legal); `punch` 84 (283 legal); `bite` 194 (250); `wind` 182 (575). Cobblemon's `MoveTemplate`
  does not expose flags, so ship these as static id lists in the mod's data (generated once at dev time from `moves.js`).
- Other work-flavoured moves and how many implemented species learn them legally: helpinghand 490, bulldoze ~280,
  earthquake ~245, flamethrower ~170, psychic ~185, telekinesis 119, trick 139, covet 184, fling ~320, recycle 90,
  growth 68, synthesis 92, leechseed 86, ingrain 43, watergun ~140, raindance 596, sunnyday 572, explosion 73,
  selfdestruct 79, drillrun 42, rototiller 22, stringshot 56, stickyweb 18, payday 43, magnetrise 72, tailwind ~115, roost 111.

---------------------------------------------------------------------------------------------------

## 4. Existing Cobblemon systems that reward non-battle use (hook into or respect)

### 4.1 Friendship
- 0..255 (`maxPokemonFriendship`), base value from species (`baseFriendship`: 50 for 761 of 851 species, 35 for 60, 0 for 12, 140 for 7).
- Passive gain: +1 every 120 s for **party** Pokemon that are sent out or shouldered, only while below 160 (`PlayerPartyStore.kt:171-181`).
  Level-up gives +3 (<100) / +2 (<200) through `addExperienceWithPlayer` (`Pokemon.kt:1967-1980`, `FriendshipMutationCalculator`). Fainting -1 (`Pokemon.kt:320`).
  Friendship berries: +10 / +5 / +1 by bracket (`JAR:data/cobblemon/mechanics/berries.json`). Poke Puffs by flavour vs nature.
- 21 evolutions need friendship (mostly 160) - e.g. Chansey, Eevee -> Espeon/Umbreon, Golbat, Riolu.
- Hook: workers are not in the party, so they get no passive gain. Mirror the vanilla rule from the site ticker
  (`incrementFriendship(1)` every 120 s of actual work while < 160) and use friendship as a mild multiplier.

### 4.2 Fullness and cooking (1.7)
See 1.3. Cooking (campfire pot, seasonings in `JAR:data/cobblemon/seasonings`, Poke Puffs, aprijuice ride boosts) all funnel into
`feedPokemon`. `cobblecuisine` is also in the pack. Use `#cobblemon:poke_food` as the worker food tag instead of inventing food.

### 4.3 Evolution requirements
Evolution requirement variants in species data (jar): level 457, held_item 93, time_range 88, biome 75, properties 63,
friendship 21, has_move 19, structure 19, property_range 14, party_member 3, blocks_traveled 3 (Pawmo, Bramblin, Rellor: 1000),
stat_compare 2, weather 2, use_move 1, recoil 1, damage_taken 1, defeat 1, battle_critical_hits 1, moon_phase 1.
- Passive evolutions are only attempted for party members each second (`PlayerPartyStore.kt:146-153`). A worker in the PC that
  levels up from work XP will not be offered its evolution until it is back in the party. That is safe (no surprise
  evolution mid-job that changes type, size and suitability) - but tell the player.
- `LEVEL_UP_EVENT`, `EXPERIENCE_GAINED_EVENT_PRE/POST`, `EV_GAINED_EVENT_PRE/POST` fire for sidemod sources too; `capturexp`
  and other pack mods may listen. Use `SidemodExperienceSource("workforce")` / `SidemodEvSource("workforce", pokemon)`.
- Walking workers can legitimately call `addBlocksTraveled(n)` (harmless for species without the feature).

### 4.4 Marks / ribbons
`Marks` is a `JsonDataRegistry` with `resourcePath = "marks"` that loads **every namespace** (`K:api/data/JsonDataRegistry.kt:47-60`,
`K:api/mark/Marks.kt`). A mark is one JSON file: `{name, title, titleColor, description, texture, indexNumber, chance?, group?, replace?}`
(`JAR:data/cobblemon/marks/mark_itemfinder.json`). So the mod can ship `data/workforce/marks/ribbon_hard_worker.json` plus a
texture and award it with `pokemon.exchangeMark(Marks.getByIdentifier(id)!!, true)`. The mark shows in the vanilla summary
screen and its title can be displayed after the name (`getDisplayName(showTitle = true)`). Zero GUI code - a cheap, very
"Pokemon" reward for work milestones. Existing thematic marks: `mark_itemfinder`, `mark_partner` (awarded by a datapack
callback at friendship >= 200 after 10 000 blocks walked, 1% chance), `ribbon_best_friends`, `ribbon_effort`, `ribbon_footprint`.

### 4.5 Pokemon item drops, interactions, pickup AI
- `form.drops` (831 of 851 species have a table): feathers, berries, bones, raw iron, clay, honey, ... Dropped on defeat.
- `JAR:data/cobblemon/pokemon_interactions/*.json` (138 files): data-driven owner interactions with a per-Pokemon cooldown
  stored in `interactionCooldowns` - brushing (106 species; feathers 76, string, scutes, sand, coal, ...), bone meal (20),
  bucket (lava bucket from Slugma/Camerupt..., milk, powder snow), milking Miltank, shears. Cooldowns 0 / 6000 / 12000 / 18000 ticks.
- 1.7 AI already does small world jobs: `PickUpItemTask` + `EatHeldItemTask` (behaviour `cobblemon:pokemon_picks_up_items`,
  fox behaviour on Vulpix), `FertilizerTask` (bone-meals `NEARBY_GROWABLE_CROPS`), `HarvestSweetBerryBushTask`,
  `PlaceHoneyInHiveTask` / pollination (Combee `cobblemon:pokemon_bee`), `EatGrassTask` (Mareep, Wooloo, Dubwool),
  `IgniteTask` (lights campfires). These are precedent and code references for worker tasks
  (`K:entity/pokemon/ai/tasks/`). The bee tasks are conditioned on `!q.entity.is_in_party`
  (`JAR:data/cobblemon/behaviours/pokemon/pokemon_bee.json`), i.e. they are meant for wild and pastured Pokemon.
- The pack has **PastureLoot** (`PACK:mods/pastureLoot-1.0.5`, `config/PastureLoot.json`): pastured Pokemon already
  generate loot. Do not add a second passive-loot source; workers should only produce what their job produces.

### 4.6 Apricorn and berry farming
- `ApricornBlock.harvest(world, state, pos): BlockState` - public, drops via loot table and resets AGE, no player needed
  (`K:block/ApricornBlock.kt:194-201`). `ApricornBlock : ShearableBlock` (`attemptShear`).
- `BerryBlockEntity.harvest(world, state, pos): Collection<ItemStack>` - public player-less overload, returns the stacks
  and resets the tree (`K:block/entity/BerryBlockEntity.kt:228-253`); `BerryBlock.AGE` 0..5, fruit at `FRUIT_AGE = 5`
  (`K:block/BerryBlock.kt:257-261`). Berry mutation and mulch stay intact.
- Events `APRICORN_HARVESTED` and `BERRY_HARVEST` only fire for a `ServerPlayer`; worker harvests are silent, which is fine.
- So the Farmer job can support Cobblemon berries and apricorns with two public calls; also mints, revival herbs,
  vivichoke, hearty grains, medicinal leek are ordinary blocks in `K:block/`.

### 4.7 PokeSnack
`PokeSnackBlock` (`K:block/PokeSnackBlock.kt`): placeable bait cake with `BITES` 0..8 that attracts wild spawns;
`eat(level, pos, state, player?)` is public (`:341`). The item is in `#cobblemon:poke_food`. A PokeSnack near a job site
could serve as the workers' canteen (take a bite = `feedPokemon(1)`), reusing an existing block and its particles.

### 4.8 Visible held items
- The client renders `PokemonEntity.shownItem` on the model's item locators (`HeldItemRenderer`). The server recomputes it
  every tick in `PokemonServerDelegate.updateShownItem()` (`K:entity/pokemon/PokemonServerDelegate.kt:353-366`):
  the Pokemon's held item if `heldItemVisible` and not empty, **otherwise `entity.mainHandItem`**, hidden if in the item
  tag `cobblemon:held/visibility/hidden` (`CobblemonItemTags.HIDDEN_ITEMS`).
- So a hauler can visibly carry its cargo, or a miner a pickaxe, with `entity.setItemInHand(InteractionHand.MAIN_HAND, stack)`
  - no packet, no renderer, no mixin - **as long as the Pokemon has no visible battle held item**. Do not write directly to
  `shownItem` (overwritten next tick) and do not use `swapHeldItem` for cargo (it is the real held item and fires `HELD_ITEM_*`).
  Treat the main-hand stack as a display copy only (keep the real cargo in the mod's own worker data) and set
  `entity.setDropChance(EquipmentSlot.MAINHAND, 0f)`: `PokemonEntity` does not override vanilla mob equipment drops
  (no `dropCustomDeathLoot` / drop-chance code in `PokemonEntity.kt`), so a fainting worker could otherwise drop a duplicate.
- Cosmetic items (`pokemon.cosmeticItem`, `data/cobblemon/cosmetic_items`) are a second slot that adds aspects (hats etc.).

---------------------------------------------------------------------------------------------------

## 5. Recommended suitability model

Design goals: authentic, legible in a GUI, many species worth using, cheap to compute, all numbers in data files.

### 5.1 Shape

```
workSpeed(job) = clamp( stat x type x ability x moves x size x condition x care , 0.25 , 3.0 ) x skillTree
```

Computed **once** when the worker is assigned (and again on level-up, evolution, move change, held-item change, feeding
state change) into a small immutable `WorkerProfile`; the job tick only multiplies cached floats. No per-tick registry lookups.

### 5.2 Stat term - which stat drives which job

`statMult = clamp(0.5 + (0.7 * primary + 0.3 * secondary) / 200, 0.5, 2.0)` using **computed** stats (`pokemon.getStat`).
L50 average species ~0.9, L50 specialist ~1.2, fully trained L100 specialist 2.0, fresh L5 catch ~0.55.

| Job | Primary (70%) | Secondary (30%) | Why |
|---|---|---|---|
| Lumberjack | Attack | Speed | physical force; moving between trees |
| Miner | Attack | Defence | force; toughness underground |
| Hauler | Speed | HP (x0.6) | travel time; HP also sets carry capacity |
| Sorter (telekinetic logistics) | Sp. Attack | Sp. Defence | mental power and focus |
| Farmer | Sp. Defence | Speed | care / patience; covering the field |
| Builder | max(Attack, Sp. Attack) | Speed | place by hand or by mind; trips to the chest |

Every one of the six stats is primary or secondary somewhere, so IV/EV/nature investment always matters for some job.
Carry capacity (items per trip): `clamp(8 + maxHealth / 3, 4, 256)`, x2 for large, x0.5 for tiny, +64 with Strength.
Work could also train EVs: +1 EV in the job's primary stat per N actions through `evs.add(stat, 1, SidemodEvSource(...))`.

### 5.3 Type term - dual types add up

`typeMult = clamp(1 + bonus(type1) + bonus(type2), 0.7, 1.6)`

| Job | Bonuses | Penalties |
|---|---|---|
| Lumberjack | Fighting +20, Steel +20, Bug +20, Normal +10, Dark +10, Dragon +5 | Fairy -10, Psychic -10, Ghost -10, Water -5 |
| Miner | Ground +30, Rock +25, Steel +20, Fighting +15, Fire +5, Dragon +5 | Flying -20, Fairy -10, Grass -10, Bug -5, Ghost -5 |
| Hauler | Flying +20, Fighting +15, Normal +10, Dragon +10, Electric +10, Dark +5 | Rock -10, Grass -5, Poison -5 |
| Sorter | Psychic +30, Ghost +20, Electric +10, Fairy +10, Steel +5, Dark +5 | Fighting -10, Rock -10, Ground -5 |
| Farmer | Grass +30, Water +20, Bug +20, Ground +15, Fairy +10, Normal +5 | Fire -20, Ice -15, Poison -10, Rock -5, Steel -5 |
| Builder | Fighting +20, Rock +15, Steel +15, Psychic +15, Ground +10, Flying +10, Normal +5, Ghost +5, Bug +5 | Poison -5, Ice -5 |

Examples of combination: Ground/Rock miner 1.55, Ground/Steel 1.50, Rock/Flying 1.05, Water/Grass farmer 1.50, Fire/Flying farmer 0.80.

### 5.4 Environment / condition term (independent of job; this is where Water, Fire, Flying, Ice shine)

| Situation | Rule |
|---|---|
| Target block is under water | allowed if `canBreatheUnderwater` or Water type or knows Dive; x1.3 for Water type, x1.5 with Swift Swim; others x0.5; never if `canSwimInWater = false` |
| Nether, or target next to lava / fire | x1.25 if `pokemon.isFireImmune()` (99 species, includes 30 rock/steel ones); x1.4 if also Fire type; Grass / Ice / Bug x0.8; lava-adjacent blocks are skipped by non-immune workers |
| Target more than 3 blocks above standable ground | needs `fly.canFly` (217 species) or Levitate (27) or knows Fly / Rock Climb; Flying type x1.2 there; everyone else leaves the block for someone else |
| Raining at the site | Swift Swim +50% move speed, Rain Dish / Hydration recover faster, Water type +10%, Fire type -15% |
| Daylight with open sky | Chlorophyll +25%, Solar Power +20%, Leaf Guard +10% |
| Desert / badlands biome, or breaking sand-like blocks | Sand Rush +30%, Sand Force +15%, Sand Veil +10% |
| Snowy biome | Slush Rush +30%, Ice Body / Snow Cloak / Thick Fat +10%, Ice type +15% |
| Dark (block light < 4) | -15% unless Ghost / Dark type, Illuminate, knows Flash, or the species has `lightingData` |
| Night | Insomnia / Vital Spirit: no penalty; species whose `behaviour.resting` says they sleep at night -20% |
| Weather setters on the site (Drought 3, Drizzle 2, Sand Stream 4, Snow Warning 3) | make the matching condition true for all co-workers - team building like in the games |

### 5.5 Ability term (data table: ability id -> list of small effects)

Implement **about eight effect kinds** (`speed[job]`, `conditionalSpeed[condition]`, `capability[FLY/DIVE/LAVA/NIGHT]`,
`carry`, `bonusDropChance`, `foodDrain`, `aura`, `quirk`) and put the breadth in JSON. Starter set:

| Ability | Effect |
|---|---|
| pickup | Hauler +15%; collects item entities within 3 blocks without walking; 3% bonus find on any job |
| harvest / seedsower | Farmer +30%; 20% chance the replant costs no seed |
| honeygather | Farmer +20%; small chance of a honeycomb while crops grow nearby |
| sandrush / sandforce | Miner +20% / +15% (more in sand, see 5.4) |
| sheerforce | Lumber / Miner +20%, Builder +10%, but no bonus drops |
| ironfist | Miner +15%, Builder +20%, Lumber +10% |
| hugepower / purepower | physical jobs +30% |
| moldbreaker | Miner +20% and ignores the hardness slowdown on deepslate / obsidian-class blocks |
| toughclaws / sharpness / strongjaw | Lumber +20% |
| technician / skilllink | Builder +20% / +15%, Sorter +15% |
| frisk / telepathy / magician / analytic / download | Sorter +20% (telepathy also +50% sorter range) |
| keeneye / compoundeyes / forewarn / anticipation / synchronize / innerfocus | Sorter +10..15%, Builder +10% (innerfocus) |
| magnetpull | Miner +15% on ores; Sorter / Hauler +10% |
| runaway / quickfeet / unburden / speedboost / galewings / lightmetal | Hauler +15..20% (unburden only when empty-handed; speedboost ramps +5% per minute to +30%) |
| levitate | counts as flight for height access; Hauler / Builder +10% |
| sturdy / rockhead / battlearmor / shellarmor | Miner +10%; food drain -20% on heavy jobs |
| guts | +10..15% on physical jobs and no penalty when unfed |
| hustle | +15% speed, 10% of actions are wasted |
| serenegrace / superluck | bonus-yield chance doubled / +5% bonus drop |
| chlorophyll / overgrow / leafguard / naturalcure / sapsipper / effectspore / flowerveil / grassysurge / drizzle | Farmer +10..25% |
| blaze / torrent / overgrow / swarm | +20% while fullness is 0 ("pinch") - one rule covers 110 starter and bug species |
| regenerator / naturalcure / shedskin / earlybird | break time halved |
| vitalspirit / insomnia / stamina | no night penalty; stamina: food drain -30% |
| gluttony / cheekpouch | gluttony: food drains x2, +10% while fed; cheekpouch: each food item counts double |
| intimidate / pressure / unnerve | hostile mobs do not target workers of this site |
| healer / friendguard / plus / minus | co-workers +5% (plus/minus +10% when both are on the site) |
| truant | works at x2, then idles for an equal time (Slaking: huge burst, zero sustain) |
| slowstart | x0.5 for the first 5 minutes of a shift, x1.2 afterwards |
| klutz | no tool / held-item bonus; Builder -15% |
| defeatist | x0.5 while unfed |
| stall | -10% |
| heavymetal | carry +50%, move speed -10% |

### 5.6 Move term (`known` = in `moveSet` or `benchedMoves`; capped at +35% per job)

| Move | Effect |
|---|---|
| cut | Lumber +25%; fells the whole trunk in one action; Farmer +5% |
| headbutt | Lumber +5% and shakes bonus drops out of leaves (saplings, apples, apricorns) - the Gen 2 mechanic |
| dig | Miner +20%; dirt / sand / gravel break instantly; Farmer +5% (tilling) |
| rocksmash | Miner +15% on stone and ores; Builder +5% |
| strength | Hauler +15% and +64 carry; Builder +15%; Miner / Lumber +5% |
| fly | Hauler +15% (straight-line trips), Builder +10%; grants height access |
| rockclimb | Builder +10%; grants height access up to 16 blocks without flying |
| surf | may path across water surfaces; +20% for water-side jobs |
| dive | may work under water without being a water breather |
| waterfall / whirlpool | +10% under water |
| flash | removes the darkness penalty; Sorter +5% |
| teleport | Hauler +30%, Sorter +20% (instant return trip). Only 12 legal learners (Abra line, Ralts line, Natu line...) - a real niche |
| defog | Lumber: leaves of a felled tree vanish immediately |
| sweetscent | Farmer +10% |
| any `slicing` move | Lumber / Farmer +10% (not cumulative with Cut) |
| any `punch` move | Miner / Builder +5% (x2 with Iron Fist) |
| helpinghand | co-workers +5% (490 learners - keep it small) |
| Generic fallback | each active move whose type is one of the job's top three types: +3% (max +12%) |

### 5.7 Size term (scaled hitbox: `w = hitbox.width * baseScale * scaleModifier`)

| Class | Rule | Effects |
|---|---|---|
| Tiny | w <= 0.5 and h <= 0.8 (~10%) | Farmer +10% and never tramples; Sorter +5%; Lumber -10%; carry x0.5 |
| Medium | everything else | none |
| Large | w > 1.5 or h > 3 (114 species, 13%) | Lumber / Miner +10%, Builder +5%; carry x2; reach +2 blocks; Farmer -20% (tramples farmland unless it flies / levitates); Sorter -5%; never asked to enter gaps narrower than its hitbox |

### 5.8 Care term (friendship and food)

`care = (0.90 + 0.20 * friendship / 255) x (1.15 if currentFullness > 0 else 1.0)`
- No penalty for an empty stomach (matches vanilla, where 0 is normal); being fed is a +15% bonus.
- Drain is the mod's own timer: `loseFullness(1)` every `clamp(getMetabolismRate(), 3 min, 15 min)` of work time (see 1.3
  for why the raw vanilla rate cannot be used). With max fullness 2..7 a full stomach lasts roughly 6 to 105 minutes of work.
- Labels: `baby` carry x0.5 and friendship gain x2; `legendary` / `mythical` / `restricted` / `ultra_beast` / `paradox`
  gated by one config flag (default: allowed, capped at x1.5 total) - there are only 22 such implemented species in the jar,
  but 56 legendary + 19 mythical in the pack.

### 5.9 Dry run of the model (script `sim_model.py`, L50, IV 15, best ability, legal field moves, unconditional bonuses only)

Multiplier distributions over 851 species: lumber p50 1.04 / p90 1.49 / max 2.34; miner 1.08 / 1.77 / 3.09;
hauler 0.99 / 1.35 / 2.29; sorter 0.94 / 1.30 / 2.46; farmer 0.99 / 1.34 / 2.00; builder 1.13 / 1.55 / 2.12.
(Miner stacks highest - that is why the final clamp at 3.0 exists.)

Example species per job (top of the dry run):
- **Lumberjack**: Heracross 2.34, Scizor 2.31, Durant 2.31, Slaking 2.08, Haxorus 2.02, Pinsir 2.00, Breloom 2.00, Gallade 1.99 (Sharpness), Scyther 1.89, Kingler 1.84.
- **Miner**: Steelix 3.09, Aggron 2.88, Rhyperior 2.87, Rhydon 2.75, Excadrill 2.73, Golem 2.71, Hippowdon 2.51, Rampardos 2.49, Graveler 2.45, Onix 2.42, Geodude 2.28 (BST 300), Sandslash 2.26.
- **Hauler**: Hawlucha 2.29, Talonflame 1.84, Dodrio 1.79, Hydreigon 1.76, Salamence 1.75, Ninjask 1.75, Jolteon 1.69, Drifblim 1.69, Flygon 1.68, Swellow 1.64, Fletchinder 1.58.
- **Sorter**: Gardevoir 2.46, Beheeyem 2.22, Alakazam 2.10, Kadabra 1.94, Mr. Mime 1.84, Elgyem 1.83, Kirlia 1.83, Abra 1.80, Xatu 1.78, Starmie 1.70, Ralts 1.70 (BST 198), Magnezone 1.67.
- **Farmer**: Shuckle 2.00 (fitting: it ferments berries), Arboliva 1.93, Dolliv 1.87, Bellossom 1.86, Eldegoss 1.85, Ludicolo 1.84, Leavanny 1.77, Parasect 1.75, Ribombee 1.70, Meganium 1.69, Smoliv 1.66 (BST 260), Politoed 1.60 (Drizzle).
- **Builder**: Lucario 2.12, Golurk 2.12, Infernape 2.03, Heracross 2.02, Scizor 2.00, Hariyama 1.99, Machamp 1.97, Hitmonchan 1.95, Conkeldurr 1.93, Metagross 1.94, Dragonite 1.90.

Diversity check:
- 353 / 851 species (41%) are in the top 10% of at least one job; 569 (67%) in the top 20%; 709 (83%) in the top third.
- 78 species with BST < 400 make a top-10% list (Abra, Geodude, Graveler, Machop, Magnemite, Oddish, Onix, ...) - early-game catches matter.
- Only 7 species are top-10% in four or more jobs (Heracross, Claydol, Metagross, Slaking, Gallade, Garchomp, Infernape); legendaries do not dominate (2-4 per top-22 list).
- Overlap of top-10% sets is low except builder with lumber (46 of 85) and miner (40): strong physical species are generalists
  at construction. The Builder job is differentiated by height / underwater / Nether access rather than by its multiplier.
- "Best job" histogram: farmer 209, sorter 187, hauler 147, miner 147, lumber 110, builder 51.

---------------------------------------------------------------------------------------------------

## 6. Risks and open questions (short)

- The pack changes species data (996 implemented, 280 rideable, behaviour replaced for ~140 species); Mega Showdown
  probably patches Showdown data (`PACK:showdown/MSDPatch.json` exists; I did not diff it). Other mod jars in the pack may
  add further species data that I did not merge. Balance numbers above are from the base jar; ids are stable.
- Fullness semantics are owned by Cobblemon and may change in 1.8; keep the mod's use to `currentFullness`, `feedPokemon`,
  `tickMetabolism` behind one small adapter.
- Client lacks labels / behaviour / TM learnsets: any client-side suitability preview must come from the server.
- Not verified at runtime (nothing was launched): that `Moves.getByName("cut")` resolves for `isNonstandard` moves
  (strongly implied: species JSON lists `tm:cut` for 259 species and the registry takes Showdown's full move list), and that
  setting the main-hand item of a PokemonEntity renders on every model (depends on each model defining an item locator).
- First draft (`Workers.kt:142-155`) uses `p.speed` for every job and only `p.moveSet` for move checks; both should change.
