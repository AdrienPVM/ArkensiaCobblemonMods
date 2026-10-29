# Arkensia Cobblemon Mods

> 🇫🇷 [Lire ce README en français](README.fr.md)

The complete suite of custom Fabric mods built for **Arkensia**, a Minecraft 1.21.1
Cobblemon server. 21 mods, from a full web administration dashboard to region
protection, player-driven shops, dungeons, raids and a badge-based level cap system.

These mods ran a live server with hundreds of players. They are released here so the
community can read them, learn from them, fix them, and keep them alive.

**Minecraft 1.21.1 · Fabric · Java 21**

---

## Start here: CheeseDash

Most of these mods are not standalone. **[CheeseDash](CheeseDash/)** is the hub: a
self-hosted web dashboard that other mods plug into through a Fabric custom
entrypoint. If you only install one mod from this repository, install that one.

Admins manage everything from the dashboard. Players use in-game GUIs. There are no
admin GUIs in-game by design.

```
Browser ──HTTP 44311──┐
                      ├── CheeseDash ──┬── CheeseGuard
Browser ──WS   44312──┘                ├── CheeseMarket
                                       ├── CheeseStore
                                       └── … 10 more modules
```

---

## The mods

### Core

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[CheeseDash](CheeseDash/)** | Self-hosted web admin dashboard (HTTP + WebSocket), session auth with roles, audit log, Discord webhooks. Exposes the module API the other mods register against. | — |

### World and players

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[CheeseGuard](CheeseGuard/)** | Region protection with per-zone flags, custom dimensions with snapshots and resets, a full timed-event system, player management, chunk tooling. The largest mod here. | CheeseDash |
| **[CheeseMusic](CheeseMusic/)** | Zone-based ambient music that fades as players cross region borders. | CheeseGuard, ReactiveMusic on clients (in practice) |
| **[CheesePatrol](CheesePatrol/)** | Patrol waypoints for EasyNPC entities: NPCs walk a loop and pause to look at nearby players. | CheeseDash, EasyNPC (in practice) |
| **[AetherialBridge](AetherialBridge/)** | Registers Aetherial Islands floating-island worldgen as a CheeseGuard dimension generation type. The vanilla overworld is left untouched. | CheeseGuard, Aetherial Islands |

### Economy

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[CheeseMarket](CheeseMarket/)** | Player-facing shops with a custom full-screen GUI and a dashboard layout editor. | CheeseDash |
| **[CheeseStore](CheeseStore/)** | Web storefront with Stripe checkout, in-game delivery, a credit currency and a playable cosmetic catalogue. ⚠️ Handles real payments — read its [README](CheeseStore/README.md) and [SECURITY.md](SECURITY.md) first. | CheeseDash, CheeseItems |
| **[CheesePurse](CheesePurse/)** | An inventory-screen toggle that sweeps a player's Numismatic Overhaul coins into their purse, twice a second. | Numismatic Overhaul |

### Cobblemon gameplay

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[CobbleCap](CobbleCap/)** | Badge-based Pokémon level cap progression, champion detection, a Hall of Fame, run timers and decorative statue dioramas. | Cobblemon (in practice), Radical Cobblemon Trainers (for champions) |
| **[CobbleShift](CobbleShift/)** | Transforms players and EasyNPC characters into Pokémon, with type-based passive abilities and ranged attacks. Reaches Cobblemon through reflection, so it loads without it — but every species lookup then fails. | Cobblemon (in practice) |
| **[CobbleCheeseScore](CobbleCheeseScore/)** | Thirty-four Cobblemon leaderboards, each rendered to a PNG served on a stable URL for in-game image frames. | CheeseDash, Cobblemon (in practice) |
| **[CobblemonDungeons](CobblemonDungeons/)** | Configurable dungeon system: instances, tickets, trainers, leaderboards and run history. | Cobblemon, RCTApi, CheeseDash |
| **[CheeseExtraCommands](CheeseExtraCommands/)** | Verification commands used by NPC quest chains: EV, IV, species and ball-variety checks, plus the Arkensia island tour tracker. | Cobblemon (in practice) |

### Events and content

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[EggAndCheese](EggAndCheese/)** | Procedurally generated collectible egg hunt with immutable DNA-based generation and a 3D preview in the dashboard. | CheeseDash |
| **[CheeseRaids](CheeseRaids/)** | Modular timed multiplayer raid framework with procedural arenas, co-op bosses and rewards. ⚠️ Experimental — see its README. | CheeseDash, CheeseGuard, Cobblemon (in practice) |
| **[CheeseKart](CheeseKart/)** | Kart racing mode built on invisible boats and OpenBoatUtils physics, with tunable presets. | CheeseDash, OpenBoatUtils on clients (strongly recommended) |
| **[CheeseItems](CheeseItems/)** | Custom quest items with NBT, effects and hot reload, authored entirely from the dashboard, plus the Morpho transformation catalog. | CheeseDash |
| **[CheeseVanity](CheeseVanity/)** | Lets players toggle the visibility of trinkets and armor on their own model. | Trinkets (soft) |

### Utilities and patches

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[ScheduleRestart](ScheduleRestart/)** | Scheduled server restarts and shutdowns with player countdowns. | CheeseDash |
| **[CheeseFixQuests](CheeseFixQuests/)** | Forces individual quest progress in FTB Quests even when players share an FTB Teams party. Useful well beyond this server. | FTB Quests, FTB Teams |
| **[CheeseCompatModsFix](CheeseCompatModsFix/)** | ⚠️ Compatibility patches whose mixins target third-party mods. Built for one specific modpack and liable to break whenever those mods update. Provided as is, with no support. | — |

---

## Documentation

Every mod has a manual, not just a readme. Its `README.md` is the overview — what it
does, what it needs, how to install it — and its `docs/` directory holds the detail:

| Page | What is in it |
|---|---|
| `installation.md` | exact prerequisites, build order, what the first launch creates, the log line that proves it loaded, clean removal |
| `getting-started.md` | a tutorial, from a blank server to a working result |
| `commands.md` | every command, argument by argument, with permission levels, worked examples and every failure message |
| `configuration.md` | every config key with its type, default and effect, plus a complete annotated file |
| one page per subsystem | the deep explanation — zones and flags, dimensions, events, the DNA of a collectible, the OpenBoatUtils protocol, whatever the mod actually has |
| `dashboard.md` | every REST route and WebSocket message, with the role each one requires |
| `architecture.md` | package layout, threading, mixins, extension points, and a frank list of known weaknesses |
| `troubleshooting.md` | symptom, cause, fix |

`installation`, `getting-started`, `architecture` and `troubleshooting` exist for every
mod; `commands`, `configuration` and `dashboard` only where the mod has commands, a
config file or a dashboard module.

**All of it exists in French too**, as `*.fr.md` next to each page. Every page carries a
language switch in its first line.

The documentation was written by reading the source, not by tidying up the old readmes —
which turned out to describe commands that do not exist, arguments in the wrong order,
and features that were never wired up. Where a mod is fragile, experimental, or has a
default you must change before going live, its pages say so rather than letting you find
out in production.

---

## Download

Prebuilt jars are on [GitHub Releases](https://github.com/AdrienPVM/ArkensiaCobblemonMods/releases): one release per mod, tagged
`<jar name>-v<version>+1.21.1`. Every mod is at version 1.0.0 except CheeseFixQuests
(1.0.1), CheeseCompatModsFix (1.2.53) and CobblemonDungeons (1.1.147). Whether a jar
goes on the server, the clients or both is spelled out in the Install section of that
mod's README.

---

## Building

You need **JDK 21**. Gradle comes with the wrapper, and every third-party mod
dependency is fetched automatically from Maven — **19 of the 21 mods build from a
clean clone with no manual downloads.**

Each mod is an independent Gradle project:

```bash
cd CheeseDash
./gradlew build          # gradlew.bat on Windows
```

The jar lands in `build/libs/`. Ignore the `-sources.jar` and `-dev.jar` variants when
deploying.

### Build order is not optional

Mods that depend on another mod in this repository compile against its **built jar**,
not its source. Build the three hubs first, in this order:

```bash
cd CheeseDash     && ./gradlew build   # almost everything needs it
cd ../CheeseGuard && ./gradlew build   # needed by CheeseRaids, AetherialBridge
cd ../CheeseItems && ./gradlew build   # needed by CheeseStore
# then any other mod, in any order
```

A compile error about missing `com.cheesedash` classes means you skipped step one.
There is also a script that walks the three tiers for you:

```bash
tools/build-all.sh            # build everything, in order
tools/build-all.sh --clean    # wipe build/libs first — do this before a release
```

Cross-mod dependencies resolve through a glob on `../<Mod>/build/libs/`, so bumping a
mod's version never breaks whatever depends on it. Jars are named
`<name>-<version>+1.21.1.jar`, where `+1.21.1` is the Minecraft version they target and
`<name>` is the mod id for most of them. Six differ: `AetherialBridge`,
`CheeseExtraCommands`, `CheesePatrol`, `CobbleCap` and `ScheduleRestart` carry their
folder name, and CobblemonDungeons builds `cobblemon-dungeons` (its mod id is
`cobblemon_dungeons`). CheesePurse's jar is `numismatic-cheesepurse`, its mod id.

### Two mods need jars you must supply

**CheeseFixQuests** and **CheeseCompatModsFix** mix into FTB Quests, FTB Teams, FTB
Library and StarAcademy. Those four mods are CurseForge-exclusive — published on
neither Modrinth nor any public Maven repository — so Gradle cannot download them,
and this repository does not redistribute them.

Download them yourself into [`localdeps/`](localdeps/README.md). Both builds stop
immediately with a message naming exactly what is missing, so you will not be left
guessing. **The other 19 mods need none of this.**

On a server, order does not matter — Fabric loads every jar in `mods/` at once — but
each mod's dependencies have to be there alongside it, CheeseDash first among them.

---

## License

Released under the **[PolyForm Noncommercial License 1.0.0](LICENSE)**, with an
Additional Permission for server operators.

**You may**

- read, study and learn from this code
- modify it, fork it, and keep maintaining it
- redistribute it, including your changes
- run it on your Minecraft server — **even a server that takes donations, sells
  ranks, runs a store, or is run as a business**

**You may not**

- sell the mods themselves, in any form
- include them in a paid modpack or server pack
- sell a service built around installing or hosting them
- remove the attribution

This is a *source-available* license, not an OSI-approved open source license: the
noncommercial restriction is deliberate. The full terms, including exactly where the
line falls, are in [LICENSE](LICENSE).

---

## Support and contributing

- **Bugs and feature requests** → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- **Questions and discussion** → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
- **Security issues** → see [SECURITY.md](SECURITY.md). Please do not open a public
  issue for a vulnerability.

Pull requests are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers the build setup,
the conventions these mods follow, and what makes a change easy to merge.

---

Built for [Arkensia](https://discord.gg/fYmPwMBjdB) by **RoiCheese**.
