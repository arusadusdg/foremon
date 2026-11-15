# Launch kit

Everything needed to publish Foremon and tell people about it. Each file has text ready to paste.
Current version: **1.2.1** (15 Nov 2026).

Signing up, signing in, posting and sending messages are yours to do: they go out under your name, and some need a
password or a verification only you can do.

## Order

| # | What | File | Status on 15 Nov 2026 |
|---|---|---|---|
| 1 | An email address just for the mod | [email.md](email.md) | Optional. The contact on every page and message is GitHub issues |
| 2 | GitHub repository: code and bug reports | [github.md](github.md) | Done. Release v1.2.1 is up |
| 3 | Modrinth page | [modrinth.md](modrinth.md) | Still under review. The page shows "not found" to others until approved |
| 4 | CurseForge page | [curseforge.md](curseforge.md) | Done. 1.2.1 is live |
| 5 | Your own Discord server | [discord-server.md](discord-server.md) | Not made yet |
| 6 | Post in the official Cobblemon Discord | [posts.md](posts.md) | Yours to post |
| 7 | Post on r/Cobblemon, r/feedthebeast, r/fabricmc | [posts.md](posts.md) | Yours to post (Reddit is blocked from the tools here) |
| 8 | TikTok and YouTube Shorts | [tiktok.md](tiktok.md) | TikTok @foremoncobblemon has its first video |
| 9 | Creators to message, with contacts | [youtubers.md](youtubers.md) | 17 YouTube channels and 3 TikTok accounts, checked 15 Nov. Kept out of git (.gitignore), since it lists other people's contacts |
| 10 | Message templates (email, DM, Discord, modpacks, list sites) | [outreach.md](outreach.md) | Send them yourself, to people you pick |
| 11 | Where it shows up in search, and what to change | [posts.md](posts.md) (Search section) | Foremon isn't in search results yet |

## Files for the pages

- Mod jar: `build/libs/foremon-1.2.1.jar`
- Icon: `src/main/resources/assets/foremon/icon.png` (128x128) and `docs/media/icon-512.png`
- GIFs: `docs/media/build.gif` (a Litematica house, use this first) and `docs/media/timber.gif` (a whole tree in one swing)
- TikTok video: `docs/media/foremon-tiktok.mp4` (vertical, 1080x1920)
- Screenshots: `docs/media/screenshot-*.png`, and the journal screenshots and banners in `docs/media/cf/`
- Description: [../MODPAGE.md](../MODPAGE.md). Changelog: [../../CHANGELOG.md](../../CHANGELOG.md)

## Things to say everywhere

- Lead with: **your Pokemon build your Litematica schematics for you, and types decide the crew.**
- It is **new**: ask people to report bugs on GitHub issues (https://github.com/arusadusdg/foremon/issues).
- It was **developed with an AI coding assistant**. Disclose it on every page and post. Where the site has an AI
  toggle (Modrinth's "Contains AI-generated content", TikTok's "AI-generated content" switch in the upload form),
  use the toggle and leave it out of the text. Where there is none (CurseForge, GitHub, Reddit, Discord, emails and
  DMs), say it in the text, at the end.
- Stamina and food can be switched off in the settings, for players who dislike upkeep.
- Requirements: Minecraft 1.21.1, Fabric, Fabric API, Fabric Language Kotlin, Cobblemon 1.7.3 or newer (tested on 1.7.3 and
  1.8.4). Litematica is optional.
