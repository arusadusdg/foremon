# Modrinth

Create at https://modrinth.com/dashboard/projects (New project).

## Project

| Field | Value |
|---|---|
| Name | Foremon |
| URL (slug) | `foremon` (checked free on 2026-11-13) |
| Summary | Put your Cobblemon to work: Pokemon chop, mine, farm, smelt, sort and build your Litematica schematics for real. |
| Project type | Mod |
| Visibility | Public (it goes into Modrinth's review first) |
| Categories | Utility, Management, Game Mechanics |
| Environment | Client: required. Server: required |
| License | MIT |
| Icon | `docs/media/icon-512.png` |
| Links | Issues: `https://github.com/arusadusdg/foremon/issues`, Source: `https://github.com/arusadusdg/foremon`, Discord: your invite |

## Description

Paste the whole of [../MODPAGE.md](../MODPAGE.md). Put `docs/media/timber.gif` at the very top (Modrinth
lets you upload images in the gallery and use them in the description). Delete the "Made with AI" box from the
text: Modrinth has its own AI toggle (Settings > Disclosures), which shows the label on the page.

Add at the end:

> **This is version 1.** Please report bugs on [GitHub](https://github.com/arusadusdg/foremon/issues) and
> vote on what comes next in the Discord.

## Gallery

Upload, with these titles:

1. `timber.gif`: "Level 50: the whole tree, leaves and all, in one swing"
2. `build.gif`: "Building a schematic placed with Litematica"
3. `screenshot-site.png`: "The job site menu"
4. `screenshot-chests.png`: "Every chest gets a Poke Ball and its own filter"
5. `screenshot-perks.png`: "Perks unlock as your Pokemon level up"
6. `screenshot-skills.png`: "A 38-upgrade skill tree"
7. `screenshot-tooltip.png`: "See exactly why a Pokemon is good at a job"

## Version 1.0.0

| Field | Value |
|---|---|
| File | `build/libs/foremon-1.0.0.jar` |
| Version number | 1.0.0 |
| Name | Foremon 1.0.0 |
| Release channel | Release (or Beta if you want testers to expect bugs) |
| Loaders | Fabric |
| Game versions | 1.21.1 |
| Dependencies | Required: Fabric API, Fabric Language Kotlin, Cobblemon. Optional: Litematica |
| Changelog | Paste the 1.0.0 section of [../../CHANGELOG.md](../../CHANGELOG.md) |

Modrinth's content rules ask that AI-generated content is disclosed: switch on "Contains AI-generated content"
(Code, Assets, Text) under Settings > Disclosures. Don't repeat it in the summary, description or changelog.
