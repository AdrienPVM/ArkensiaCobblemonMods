# CobbleCap

> 🇫🇷 [Lire en français](README.fr.md)

> A hard Pokémon level cap that only rises when you beat a gym leader.

A player joins and their Pokémon stop at level 20. Not "level slowly" — stop. Experience
past the cap is refused and discarded, and the ceiling only moves when they beat the next
champion in an ordered list. That one rule turns a Cobblemon server's progression from "how
much time do you have" into "which badge do you hold".

On top of it sits a league. Champions are Radical Cobblemon Trainers NPCs with conditions on
them: a Pokédex count, an FTB quest, a scoreboard score, and always the requirement that you
beat them in order. Clear the last one and you are recorded in a persistent Hall of Fame
with your team snapshotted as it stood, your run timed, and — in competitive mode — your
team written into the final boss, so the next challenger fights you.

Admins get a diagnostic dump that explains exactly why a player cannot progress, a statue
system that poses a champion and their six Pokémon anywhere in the world, and a CheeseDash
panel for the caps.

**Minecraft 1.21.1 · Fabric · Java 21 · 52 source files, ~11 740 lines**

⚠️ **This is the most server-specific mod in this repository.** It was written for one
league on one server and it shows. Read [the warning below](#-read-this-before-installing)
before you deploy it anywhere else — there is one source edit you almost certainly have to
make, and a second thing to run before anyone can win.

---

## Requirements

| | |
|---|---|
| **Required** | Fabric Loader ≥ 0.15.11 · Fabric API · Java 21 |
| **Required in practice** | [Cobblemon](https://modrinth.com/mod/cobblemon) 1.6.1 — declared only as `suggests`, but the mod does nothing without it |
| **Required for champions** | [Radical Cobblemon Trainers](https://modrinth.com/mod/rctmod) 0.16.11-beta — **not declared at all**, and the champion system cannot work without it |
| **Optional** | [CheeseDash](../CheeseDash/) for the admin panel · FTB Quests for quest gates · [CheeseGuard](../CheeseGuard/) for the fireworks zone · [CobbleCheeseScore](../CobbleCheeseScore/) to render the leaderboards |

Cobblemon and rctmod are reached entirely through reflection, with no compile-time
dependency, so the mod loads cleanly without them and correspondingly does less. CheeseDash
is the exception: it is a **build-time** dependency, so it must be built first.

Server-side is mandatory, client-side is optional. A vanilla client gets every cap rule
enforced but no badge, no `/pasturehide` and statues that breathe.

## Install

```bash
cd CheeseDash   && ./gradlew build     # CobbleCap compiles against its jar
cd ../CobbleCap && ./gradlew build
```

Drop `build/libs/cobblecap-1.0.0+1.21.1.jar` in the server's `mods/`. On first start it
writes `config/cobblecap/config.json` with defaults, an **empty** `champions.json`, and
logs:

```text
[CobbleCap] Initialized — startingCap=20, enabled=true, champions=0
[CobbleCap] Cobblemon XP hook registered on field: EXPERIENCE_GAINED_EVENT_PRE
[CobbleCap] Battle victory hook registered on field: BATTLE_VICTORY
```

`champions=0` is expected — the champion list ships empty and nothing gates anything until
you author it. Those two hook lines are the real health check.

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Exact prerequisites, build order, what the first launch creates, the log lines that prove it loaded, clean removal and what it leaves behind |
| [Getting started](docs/getting-started.md) | A tutorial: from a blank server to one working champion who raises a real cap, proven in game |
| [The level cap](docs/level-cap.md) | How the cap is calculated and enforced across four independent layers, what a capped player sees, and why overshoot XP is discarded rather than banked |
| [The champion system](docs/champions.md) | Victory detection, the gates, challenge mode, classic and ranked, the Hall of Fame, the chronos, the Conseil 4 leaderboards and their JSON export |
| [Champion morphing](docs/champion-morphing.md) | How the final trainer takes the last winner's team by rewriting a datapack, the queue, the dead battle check, the NPC fallback — and why this is the fragile part |
| [Statues and dioramas](docs/statues.md) | Invisible trackers and fake-player overlays, the thirteen styles, the dynamic champion diorama, test mode |
| [Commands](docs/commands.md) | `/cobblecap` cap, player and config subcommands, `/explock`, `/pasturehide` — arguments, permissions, output and every failure message |
| [Statue, chrono and champion commands](docs/commands-statues.md) | The three large subtrees, argument by argument |
| [Configuration](docs/configuration.md) | All eight files, every key with its default and clamp, two complete annotated examples, and a list of what is *not* configurable |
| [The client side](docs/client.md) | The cap badge, `/pasturehide` and why the server has to help, the four statue mixins |
| [Dashboard API](docs/dashboard.md) | The nine routes under `/api/v1/cobblecap`, request and response shapes, the role each one requires |
| [Architecture](docs/architecture.md) | Package layout, who owns what, threading and hand-offs, every mixin, extension points, dead code and known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — thirty of them, plus how to file a report that can be acted on |

## Commands at a glance

| Command | Level | What it does |
|---|---|---|
| `/cobblecap` | any player | your cap, your next champion, your unmet conditions |
| `/cobblecap hud on\|off\|position <corner>` | any player | move or hide the badge |
| `/cobblecap legends [by_time]` | any player | the Hall of Fame, by date or by best run |
| `/cobblecap mode classic\|ranked` | any player | pick the Conseil 4 variant |
| `/cobblecap status <player>` | 2 | the diagnostic dump — start here when someone is stuck |
| `/cobblecap get\|set\|reset <player>` | 2 | read, write or wipe a cap |
| `/cobblecap addbeaten\|removebeaten <player> <id>` | 2 | repair champion progress |
| `/cobblecap reload` · `enable` · `disable` | 2 | re-read the configs, flip the master switch |
| `/cobblecap statue …` | 2 | 17 subcommands for dioramas |
| `/cobblecap chrono …` | 2 | 7 subcommands for the run timers |
| `/cobblecap champion …` | 2 | 7 subcommands for morphing and the NPC fallback |
| `/explock [player]` | any player / 2 | toggle XP lock for yourself, or for someone else |
| `/pasturehide [on\|off\|toggle\|status]` | client-side | hide Pokémon tethered to a pasture, for framerate |

`/explock` and `/pasturehide` are separate roots, not `/cobblecap` subcommands.
`/pasturehide` never reaches the server. Everything in full in
[commands](docs/commands.md) and [statue, chrono and champion commands](docs/commands-statues.md).

## Dashboard

Registers under the `cobblecap` prefix, so everything lives at `/api/v1/cobblecap/*`: the
player list with caps, per-player cap and XP-lock writes, the champion list as a whole-list
`GET`/`PUT`, the config, a cap histogram, and a fireworks test trigger. Reads need any
authenticated role; writes need OPERATOR.

CobbleCap publishes **nothing** over WebSocket — a dashboard view polls. It does post to a
Discord webhook on champion victories and League Master promotions, configured by
`discordWebhookUrl`.

⚠️ The four write guards were broken until **29 October 2026**: they discarded
`requireWrite`'s return value, so a `READ_ONLY` account could change caps. Fixed. On an
older build, give nobody a `READ_ONLY` account. [Details](docs/dashboard.md#roles).

## ⚠️ Read this before installing

**1. One constant must change, or champion morphing cannot work.**

```java
// src/main/java/com/cobblecap/logic/RctTrainerMorpher.java, line 57
public static String datapackName = "CacademyMons";
```

That is the name of the world datapack directory holding the rctmod trainer files.
`CacademyMons` is Arkensia's. It is **not a config key** — a comment above it claims it is
"Configurable from CapConfig" and that comment is wrong; no config key reads or writes it.
Changing it means editing the source and rebuilding.

Leave it as it is on any other server and `locateDatapackRoot` tries five paths, finds none
of them, and aborts every morph with:

```text
[CobbleCap] Datapack 'CacademyMons' not found in any of: [...]
```

Nothing crashes and nothing corrupts. But the final champion never takes the last winner's
team, and the dynamic `place_current` dioramas never refresh either, because their refresh
hangs off the same code path. Competitive mode becomes decorative.

**2. Run `/cobblecap champion backup_now` before the first morph.** It copies the pristine
trainer JSON and skin into `config/cobblecap_regis_backup/`. Without that copy there is no
way back — `ensureBackup` refuses to snapshot an already-morphed file, and a morphed
trainer stays morphed even after you uninstall the mod.

**3. The final champion's id is hard-coded** as `champion_regis_challenge`, in four places:
`ChampionManager` lines 548, 605 and 792, and `StatueCommand` line 541. A league whose last
boss has another id never promotes a League Master and never counts a competitive loss.

**4. Other things that are fixed rather than configured:** the Conseil 4 scoreboard names,
the CobbleCheeseScore export filenames, the diorama layout, and several player-facing
strings that are French `Text.literal`s rather than translatable keys — so an English
client sees French on the chrono messages and the competitive crowning broadcasts. The
`champions.json` that drives everything ships **empty**.

**5. The skin morph is not implemented.** The team morphs; the face does not. A datapack
cannot serve textures. [Why](docs/champion-morphing.md#the-skin-is-not-written).

None of this is hard to change. None of it is configurable either. Treat the mod as a strong
starting point rather than a drop-in, and read
[the champion system](docs/champions.md#what-you-must-change-for-your-own-server) for the
full list with file and line numbers.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
