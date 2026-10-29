# CobblemonDungeons

> 🇫🇷 [Lire en français](README.fr.md)

> Ticketed, instanced dungeons for Cobblemon: objectives, Guardian trainers, a giant boss,
> loot and leaderboards, all defined in JSON.

A party spends a ticket, waits out a five-second countdown, and lands in a hub while the
server places the dungeon's contents around them. Then they are teleported in. Alpha
Pokémon — oversized, aggressive, uncatchable, fought with a sword rather than a team —
guard the area. Guardian trainers stand at their spawners waiting for a real Cobblemon
battle. Gems hide in chests, Stakes in the ground. A counter on the HUD tracks all of it
against the dungeon's requirements, next to a shared pool of lives and a timer.

Clear every objective and a portal that was invisible until then lights up. Right-click it,
accept the prompt, and the whole party is dropped in the boss room with a Charizard four
times normal size. Kill it
and everyone gets their loot, the boss itself in their PC, and a time on the leaderboard
for that dungeon at that tier. Fail — run out of lives, run out of time, or walk out — and
on a cross-server dungeon nothing you picked up inside comes home with you.

Admins never touch a command for any of this. Dungeons are JSON files, hot-reloadable,
editable from the CheeseDash dashboard; tiers 1 to 5 let one dungeon serve a level-20 party
and a level-115 one from the same geometry.

**Minecraft 1.21.1 · Fabric · Java 21 · 45 source files, ~12 000 lines**

---

## Requirements

| | |
|---|---|
| **Required** | [Cobblemon](https://modrinth.com/mod/cobblemon) ≥ 1.6.1 · [RCTApi](https://www.curseforge.com/projects/1152792) ≥ 0.13.7-beta · [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.10 · Fabric API · Java 21 |
| **Optional** | [Flan](https://modrinth.com/mod/flan) · Radical Cobblemon Trainers |
| **Server and client** | Both. The HUD, the screens and the cross-server transfer all live on the client |

Cobblemon resolves from Modrinth Maven and RCTApi from CurseMaven, so a clean clone builds
with no manual downloads and nothing third-party is redistributed here.

## Install

```bash
cd CheeseDash && ./gradlew build        # CobblemonDungeons compiles against its jar
cd ../CobblemonDungeons && ./gradlew build
```

Note the Kotlin DSL: this mod's build script is `build.gradle.kts`, and its version lives
there rather than in `gradle.properties`. Drop
`build/libs/cobblemon-dungeons-1.1.147+1.21.1.jar` and the CheeseDash jar in the server's
`mods/`, and the same two jars in every client's `mods/` — CheeseDash is a hard dependency
on the client too, though it does nothing there. First start writes
`config/cobblemon_dungeons/` with a worked example dungeon, a ticket template and an
example trainer, and logs:

```text
Initializing Cobblemon Dungeons...
Registered dungeon from file: example_dungeon.json
Cobblemon Dungeons server role: main
Registered custom trainer from file: example_trainer.json
```

⚠️ `velocity-party-forwarder/` is **not** a mod. It is a separate Velocity proxy plugin,
needed only for cross-server dungeons, and it goes in the proxy's `plugins/` — never in
`mods/`. It is not part of the main Gradle build. [Installation](docs/installation.md) has
the details.

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, build order, the Velocity plugin and where it goes, what first launch creates, how to verify, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: from a bare server to a dungeon you built, entered, cleared and found on the leaderboard |
| [Commands](docs/commands.md) | Both command trees, argument by argument, with permission levels, output and every failure message |
| [Configuration](docs/configuration.md) | `config.json`, the database block, the server-role property, and exactly what reloads and what needs a restart |
| [The dungeon definition](docs/dungeons.md) | Every field of a dungeon file: type, default, effect, and how tiers select content |
| [Instances and their lifecycle](docs/instances.md) | How a run is created, prepared, won and lost; disconnects, rejoins, cleanup, and cross-server transfers |
| [Tickets](docs/tickets.md) | What a ticket is, the three ways to get one, the exchange rates, and how the gate reads it |
| [Trainers and RCTApi](docs/trainers.md) | Writing a Guardian, placing it, fighting it, and how a victory becomes an objective |
| [Leaderboards and run history](docs/records.md) | What is recorded, where, when, and the places the figures are approximate |
| [Dashboard integration](docs/dashboard.md) | All 19 REST routes under `/api/v1/dungeons`, the five WebSocket messages, CheeseGuard's three effects, and a role-check warning |
| [Architecture](docs/architecture.md) | Package layout, the two files that hold everything, state and disk writes, threading, the three mixins, extension points, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — including what really keeps a party in the hub |

## Commands at a glance

| Command | Permission | What it does |
|---|---|---|
| `/dungeons` · `/dungeon` | any player | Opens the dungeon selection GUI |
| `/dungeons leave` | any player | Leaves the current dungeon |
| `/dungeons sql test` | level 2 | Reports database connectivity |
| `/dungeons partydebug <on\|off\|dump\|status\|ping>` | level 2 | Cross-server party sync diagnostics |
| `/DungeonsParty [create\|invite\|join\|leave]` | any player | Party management, main server only |

Nothing here starts a dungeon: that goes through the GUI, which calls
`DungeonManager.startDungeon` for you. `/DungeonsParty` is case-sensitive and must be typed
with its capitals. Details in [commands](docs/commands.md).

## Dashboard

Registers under the `dungeons` prefix, so everything lives at `/api/v1/dungeons/*`: dungeon
config CRUD, hot reload, ticket templates, trainer files, live instances with force-fail
and force-leave, statistics, run history and the leaderboard. Five `dungeon_*` messages are
pushed over the CheeseDash WebSocket on the `dungeons` channel.

`DungeonsApi` never calls `WebDashboard.requireWrite` or `requireAdmin` itself, but the role
boundary still holds: CheeseDash's module dispatcher requires `OPERATOR` or above for every
non-`GET` request to any module. The eight mutating routes — including deleting a dungeon
and force-failing a live run — are therefore `OPERATOR`-level, and `GET` routes accept any
role, `READ_ONLY` included. The bar is set by the core rather than by the module, which
means no route here can require `SUPER_ADMIN`. Details in
[the dashboard page](docs/dashboard.md).

CheeseGuard can also drive the mod during a timed event through three effect types,
`DUNGEON_OPEN`, `DUNGEON_TICKET_GIVE` and `DUNGEON_MODIFIER` — the last of which, honestly,
does nothing but log and announce.

## Honest warnings

⚠️ `config.json` ships with `database.password` set to the literal string `"password"`. It
is a placeholder written by `MainConfig.DatabaseConfig`, and it must be replaced before you
enable SQL. See [configuration](docs/configuration.md).

Beyond that, three things are worth knowing before you build a season around this mod:

- **Runs do not survive a restart.** Instances live in memory only. A stop or crash
  mid-run leaves the party with no instance and no exit, and on a cross-server setup
  possibly on the wrong server.
- **Disconnecting mid-run is a silent failure.** Nothing is logged, no cooldown is
  recorded, and a solo player cannot rejoin.
- **The Alpha, boss and Guardian systems hook into Cobblemon and RCT internals** through
  aspect strings, command tags and API calls. They will need maintenance on a Cobblemon or
  RCT major update, and the symptom will be Guardians that do not count or bosses at normal
  size rather than a crash.

The package is `com.sebastian.cobblemondungeons` and is deliberately not renamed: it would
break the mixin config, the resource and datapack identifiers, and the CheeseDash
entrypoint, for no functional gain. [Architecture](docs/architecture.md) explains.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself. Cobblemon and RCT API belong to their own authors and are not
redistributed here.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
