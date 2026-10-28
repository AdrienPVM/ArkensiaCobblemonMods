# CobblemonDungeons

> A configurable dungeon system for Cobblemon: instances, tickets, trainers,
> leaderboards and run history.

Players spend a ticket to enter a dungeon instance, fight their way through trainer
battles, and are ranked on a leaderboard. Dungeon layouts, trainer rosters, difficulty
and rewards are all configuration.

**Minecraft 1.21.1 · Fabric · Java 21 · 46 files, ~11 000 lines**

---

## Requirements

- **Cobblemon** ≥ 1.6.1 — required
- **RCTApi** (Radical Cobblemon Trainers) ≥ 0.13.7-beta — required, supplies the
  trainers
- **CheeseDash** — required (admin panel)
- Fabric Loader ≥ 0.16.10, Fabric API, Java 21

Optional: **Flan**, **Radical Trainers**.

Everything third-party is resolved automatically — Cobblemon from Modrinth Maven, RCT
API from CurseMaven. Build CheeseDash first, then this.

## Commands

| Command | Purpose |
|---------|---------|
| `/dungeon create` · `/dungeons list` | authoring and listing |
| `/dungeon join` · `leave` · `status` | playing |
| `/dungeons party invite \| join \| leave` | grouping |
| `/dungeons on` · `off` | master switch |
| `/dungeons sql status` | database connectivity |
| `/dungeons dump` · `partydebug` · `ping` · `test` | diagnostics |

## Database

Run history and leaderboards can be stored in **MariaDB** through HikariCP, both
bundled into the jar via Jar-in-Jar. If you do not configure a database, the mod falls
back to local storage — check `/dungeons sql status` to see which is active.

## Dashboard

Registers under the `dungeons` prefix: config CRUD, reload, tickets, trainers, active
instances, statistics, history and the leaderboard.

`dungeon_start`, `dungeon_complete`, `dungeon_fail`, `dungeon_boss` and `dungeon_death`
events are pushed over the CheeseDash WebSocket.

## Notes on the source

The package is `com.sebastian.cobblemondungeons` and some internals carry that naming.
It is kept as-is deliberately: renaming it would break the mixin configuration,
datapack identifiers and the CheeseDash entrypoint, for no functional gain.

`velocity-party-forwarder/` is an **optional, separate subproject** for forwarding
party state across a Velocity proxy network. It is not part of the main build —
`settings.gradle.kts` does not include it — so it will not be built or break your
build. It needs `velocity-proxy.jar` in its own `libs/` if you want to compile it.

CheeseGuard can drive dungeons during events through three effect types
(`DUNGEON_OPEN`, `DUNGEON_MODIFIER`, `DUNGEON_TICKET_GIVE`).

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators. Cobblemon and RCT API belong to their own authors and are not redistributed
here.
