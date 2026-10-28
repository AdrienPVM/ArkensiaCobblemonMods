# CheeseGuard

> Region protection, custom dimensions, and a full timed-event system — managed
> entirely from CheeseDash.

The largest mod in this repository, and the one most other things hang off. It covers
four fairly distinct areas that grew together because they all need the same notion of
"a region of the world with rules attached":

- **Zones** — per-region flags (block breaking, PvP, mob spawning, fire spread, item
  pickup, fall damage, hunger, and many more), with members and permission groups.
- **Dimensions** — create and delete dimensions at runtime, snapshot them, reset them
  from a snapshot, and give each player their own private copy of one.
- **Events** — timed multiplayer events with participants, scoring, phases, a timeline
  editor, templates, and effect hooks into other mods.
- **Chunks and players** — chunk sets and deletion tooling, player management
  (kick, teleport, gamemode, messaging).

**Minecraft 1.21.1 · Fabric · Java 21 · 140 files, ~31 000 lines**

---

## Requirements

- **CheeseDash** — required, this mod is administered through it
- Fabric Loader ≥ 0.15.11, Fabric API, Fabric Resource Loader, Java 21

Optional: **Waystones** (zone flags can restrict waystone use).

Several integrations activate only if the other mod is present, through reflection
hooks: Cobblemon, CobbleShift, CheeseKart, CobblemonDungeons, CheeseStore, Numismatic
Overhaul, Jade, JourneyMap.

## Installation

Build CheeseDash first (this mod compiles against its jar), then drop both in `mods/`.
Zone and event data is written under `config/`.

## Commands

Everything is under `/cg`. The in-game commands are mostly selection and quick
inspection — the real administration happens in the dashboard.

| Command | Purpose |
|---------|---------|
| `/cg wand` | get the selection tool |
| `/cg pos1` · `/cg pos2` | set selection corners manually |
| `/cg create <name>` · `/cg delete <id>` | create or remove a zone |
| `/cg list` · `/cg info <id>` | inspect zones |
| `/cg flag <id> <flag> <value>` | set a zone flag |
| `/cg addmember` · `/cg removemember` | zone membership |
| `/cg group ...` | permission groups |
| `/cg event ...` | event lifecycle (announce, launch, join, leave, stop) |
| `/cg chunk ...` · `/cg chunksuggest` | chunk tooling |
| `/cg player ...` | player actions |

The full tree is in `commands/CheeseGuardCommand.java`.

## Configuration

Written automatically; edit through the dashboard rather than by hand.

| File | Contents |
|------|----------|
| `config/cheeseguard_*.json` | zones, groups, dimensions, events |
| `config/cheeseguard_webhook.json` | Discord webhook for event notifications |
| `config/cheeseguard_templates/` | event templates |
| `config/snapshots/<dimId>/` | dimension snapshots |

## Dashboard

Registers under the `cheeseguard` prefix, serving `/api/v1/cheeseguard/*` across
zones, groups, dimensions, events, players, chunks and races.

## Things worth knowing before you dig in

- **Event states are `IDLE → ANNOUNCED → QUEUE → COUNTDOWN → ACTIVE → ENDING →
  CLEANUP`.** There is no `PAUSED`, and adding one is not as simple as it looks —
  several handlers assume forward-only progression.
- **Per-player dimension ids are `<masterDimId>_<first 8 hex of player UUID>`.**
- **Built-in dimensions contain a colon** (`minecraft:overworld`), which breaks naive
  path parsing. Snapshot and per-player lookups are deliberately skipped for them.
- **Dimension deletion waits 400–500 ms before removing files on Windows**, because
  file locks outlive the unload.
- Event participants are returned as a `Map`, not a `List` — iterate `.values()`.

## Known rough edges

This mod grew under deadline pressure on a live server. It works, it has run for
months with hundreds of players, and parts of it are not pretty. In particular,
**CheeseMarket currently contains a duplicated copy of the `com.cheeseguard` package**
(`TimelineExecutor`, `gui/MainGui`) — a wart we have not untangled yet because it sits
on the event execution path.

## Arkensia specifics

Some default labels and webhook messages mention Arkensia. They are cosmetic; the
language files are under `src/main/resources/assets/cheeseguard/lang/`.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
