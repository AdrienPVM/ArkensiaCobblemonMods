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
                                       └── … 14 more modules
```

---

## The mods

### Core

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[CheeseDash](CheeseDash/)** | Self-hosted web admin dashboard (HTTP + WebSocket), session auth with roles, audit log, Discord webhooks. Exposes the module API every other mod registers against. | — |

### World and players

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[CheeseGuard](CheeseGuard/)** | Region protection with per-zone flags, custom dimensions with snapshots and resets, a full timed-event system, player management, chunk tooling. The largest mod here. | CheeseDash |
| **[CheeseMusic](CheeseMusic/)** | Zone-based ambient music that fades as players cross region borders. | — |
| **[CheesePatrol](CheesePatrol/)** | Patrol waypoints for EasyNPC entities: NPCs walk a loop and pause to look at nearby players. | CheeseDash |
| **[AetherialBridge](AetherialBridge/)** | Registers Aetherial Islands floating-island worldgen as a CheeseGuard dimension generation type. The vanilla overworld is left untouched. | CheeseGuard, Aetherial Islands |

### Economy

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[CheeseMarket](CheeseMarket/)** | Player-facing shops with a custom full-screen GUI and a dashboard layout editor. | CheeseDash |
| **[CheeseStore](CheeseStore/)** | Premium web store with Stripe checkout, order management, manual delivery and wallets. ⚠️ Handles real payments — read its [README](CheeseStore/README.md) and [SECURITY.md](SECURITY.md) first. | CheeseDash, CheeseItems |
| **[CheesePurse](CheesePurse/)** | Automatically deposits picked-up Numismatic Overhaul coins into the player's purse. | Numismatic Overhaul |

### Cobblemon gameplay

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[CobbleCap](CobbleCap/)** | Badge-based Pokémon level cap progression, champion detection, a Hall of Fame, run timers and decorative statue dioramas. | Cobblemon |
| **[CobbleShift](CobbleShift/)** | Transforms players into Pokémon with passive abilities, plus the CobbleHunt and Hot Potato minigames. Talks to Cobblemon through reflection, so it degrades gracefully. | Cobblemon (soft) |
| **[CobbleCheeseScore](CobbleCheeseScore/)** | Multiple configurable leaderboards with PNG export for in-world map rendering. | CheeseDash |
| **[CobblemonDungeons](CobblemonDungeons/)** | Configurable dungeon system: instances, tickets, trainers, leaderboards and run history. | Cobblemon, RCTApi, CheeseDash |
| **[CheeseExtraCommands](CheeseExtraCommands/)** | Verification commands used by NPC quest chains: EV, IV, species and ball-variety checks. | Cobblemon (soft) |

### Events and content

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[EggAndCheese](EggAndCheese/)** | Procedurally generated collectible egg hunt with immutable DNA-based generation and a 3D preview in the dashboard. | CheeseDash |
| **[CheeseRaids](CheeseRaids/)** | Modular timed multiplayer raid framework with procedural arenas, co-op bosses and rewards. ⚠️ Experimental — see its README. | CheeseDash, CheeseGuard |
| **[CheeseKart](CheeseKart/)** | Kart racing mode built on invisible boats and OpenBoatUtils physics, with tunable presets. | CheeseDash |
| **[CheeseItems](CheeseItems/)** | Custom quest items with NBT, effects and hot reload, authored entirely from the dashboard. | CheeseDash |
| **[CheeseVanity](CheeseVanity/)** | Lets players toggle the visibility of trinkets and armor on their own model. | Trinkets (soft) |

### Utilities and patches

| Mod | What it does | Requires |
|-----|--------------|----------|
| **[ScheduleRestart](ScheduleRestart/)** | Scheduled server restarts and shutdowns with player countdowns. | CheeseDash |
| **[CheeseFixQuests](CheeseFixQuests/)** | Forces individual quest progress in FTB Quests even when players share an FTB Teams party. Useful well beyond this server. | FTB Quests, FTB Teams |
| **[CheeseCompatModsFix](CheeseCompatModsFix/)** | ⚠️ Compatibility patches whose mixins target third-party mods. Built for one specific modpack and liable to break whenever those mods update. Provided as is, with no support. | — |

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
`<modid>-<version>+1.21.1.jar`, where `+1.21.1` is the Minecraft version they target.

### Two mods need jars you must supply

**CheeseFixQuests** and **CheeseCompatModsFix** mix into FTB Quests, FTB Teams, FTB
Library and StarAcademy. Those four mods are CurseForge-exclusive — published on
neither Modrinth nor any public Maven repository — so Gradle cannot download them,
and this repository does not redistribute them.

Download them yourself into [`localdeps/`](localdeps/README.md). Both builds stop
immediately with a message naming exactly what is missing, so you will not be left
guessing. **The other 19 mods need none of this.**

Install order on a server is the same idea: CheeseDash first, then anything that
depends on it.

---

## License

Released under the **[PolyForm Noncommercial License 1.0.0](LICENSE)**, with an
Additional Permission for server operators.

**You may**

- read, study and learn from this code
- modify it, fork it, and keep maintaining it
- redistribute it, including your changes
- run it on your Minecraft server — **even a server that takes donations, sells
  ranks, or runs a store**

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
