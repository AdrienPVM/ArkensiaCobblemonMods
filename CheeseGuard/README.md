# CheeseGuard

> 🇫🇷 [Lire en français](README.fr.md)

> Region protection, runtime dimensions and a timed-event engine, administered from
> CheeseDash.

The largest mod in this repository, and the one most of the others hang off. It started as
a protection mod: a zone is a box with 31 flags attached, deciding whether a player can
break blocks in it, fight in it, send out a Pokémon in it, take fall damage in it or even
enter it at all. Members and groups do what you expect — the zone's owner can build in
their own plot, and a group of zones shares one set of rules.

Then it grew. The same notion of "a region with rules" turned out to be what an event
needs, so there is an event engine: participants, teams, scoring from block breaks and mob
kills and fish caught, phases driven by a timeline, and effects that reach into CobbleShift
or CheeseKart to transform everyone or put them all in karts on the same tick. Dimensions
needed it too, so the mod creates and deletes worlds at runtime, gives each player a
private copy of one, snapshots them and resets them. A prison, a land sale system with
clickable signs, chunk sets that regenerate part of the map on a schedule, prop hunt and
spleef and competitive fishing all accreted on top.

In play it is invisible when it works. A player walks into the market and cannot grief it,
clicks a *for sale* sign and owns a plot, types `/cg event join` and gets teleported into a
minigame with their inventory saved, finishes last and gets it back. The admin never opens
a chat window: everything but the few commands below lives in the dashboard.

**Minecraft 1.21.1 · Fabric · Java 21 · 140 source files, ~35 100 lines**

---

## ⚠️ Read this before a public server

**Six of the eight dashboard APIs never check the account's role themselves.** The role
boundary still holds, but it is held by CheeseDash rather than by CheeseGuard: the core's
module dispatcher requires `OPERATOR` or above for every non-`GET` request to any module,
so a `READ_ONLY` session can read and nothing more. What the missing module-level checks
cost is precision — no CheeseGuard route can require `SUPER_ADMIN`, so deleting a zone and
wiping a dimension sit at the same `OPERATOR` bar as editing a flag, and sixty-five
mutating routes have no second line of defence if that dispatcher ever changes.
[Why, and what it means](docs/architecture.md#known-weaknesses).

**On a jar built before 29 October 2026, four mutating commands had no permission gate**
— `/cg flag`, `/cg addmember`, `/cg removemember` and the whole `/cg group` subtree were
callable by any player, on any zone id they could guess. Fixed; check which jar you are
running. [The current gate table](docs/commands.md#permission-levels).

---

## Requirements

| | |
|---|---|
| **Required** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.15.11 · Fabric API · Java 21 |
| **Suggested** | [Waystones](https://modrinth.com/mod/waystones) — dimension deletion also removes its waystones |
| **Optional** | [Cobblemon](https://modrinth.com/mod/cobblemon) · [CobbleShift](../CobbleShift/) · [CheeseKart](../CheeseKart/) · [CobblemonDungeons](../CobblemonDungeons/) · [CheeseStore](../CheeseStore/) · Numismatic Overhaul · Jade · JourneyMap · FTB Quests |

CheeseDash is a **hard** dependency: the mod will not load without it.

The jar belongs on the server and on the client. Everything works server-side alone; the
client adds the `nametag-hide` and `minimap-hide` flags, two HUDs and the zone-owner menu.

## Install

```bash
cd CheeseDash && ./gradlew build     # CheeseGuard compiles against its jar
cd ../CheeseGuard && ./gradlew build
```

Drop `build/libs/cheeseguard-1.0.0+1.21.1.jar` and the CheeseDash jar into the server's
`mods/`. On first start the mod creates an empty `config/cheeseguard/` and logs:

```text
[CheeseGuard] CheeseGuard loaded!
```

Full walkthrough, including the client side and clean removal:
[installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, build order, server vs client, what the first launch creates, how to verify it loaded, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: from a fresh server to a protected zone, a group, a restorable arena and a working prison |
| [Commands](docs/commands.md) | Zones, selection, flags, members, groups, saved states, `/cg buy`, `/chunksuggest` — with the permission gaps |
| [Events, prison and operations](docs/commands-operations.md) | The `/cg event` subtree, hot potato, per-player worlds, pasture limits, inventory restore, the `/prison` tree |
| [Configuration](docs/configuration.md) | All fourteen config files and nine directories: what each holds, which four are safe to hand-edit, every key |
| [Zones and flags](docs/zones-and-flags.md) | The zone model: geometry, the 31 flags, inheritance, priority, overlap, members, land sales |
| [Groups](docs/groups.md) | Grouping zones so they share flag defaults, and how resolution falls through |
| [Dimensions](docs/dimensions.md) | Runtime dimension creation, per-player worlds, snapshots, resets, borders, entry rules |
| [Events](docs/events.md) | The event lifecycle, participants, teams, scoring, templates, the minigame modes |
| [Timeline](docs/timeline.md) | The act-and-effect language an event's schedule is written in |
| [Players](docs/players.md) | Player management from the dashboard: kick, teleport, gamemode, messaging, state flags |
| [Chunks](docs/chunks.md) | Chunk sets, scheduled map regeneration, player suggestions, the deletion and backup path |
| [Prison](docs/prison.md) | Confinement, blocked actions, sentences and history, the offline doppelgänger |
| [Races](docs/races.md) | Race track definitions, scanning and timing |
| [Dashboard API](docs/dashboard.md) | Every REST route under `/api/v1/cheeseguard`, with required roles and the `prison` WebSocket channel |
| [Architecture](docs/architecture.md) | Package map, key classes, threading, persistence, the 44 mixins grouped by target, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — including the three ways zones can silently disappear |

## Commands at a glance

Three root commands. **`/cg`, not `/cheeseguard`** — there is no long alias.

| Command | What it does | Level |
|---|---|---|
| `/cg wand` · `/cg pos1` · `/cg pos2` | select a volume | 2 |
| `/cg create <name>` · `/cg delete <id>` | create or remove a zone | 2 |
| `/cg list` · `/cg info <id>` | inspect zones | 2 |
| `/cg tp <id>` · `/cg toggle` | teleport to a zone, outline zones | none |
| `/cg flag <zone> <flag> <value>` | set a flag | 2 |
| `/cg addmember` · `/cg removemember` | zone membership | 2 |
| `/cg group create\|delete\|add` | groups | 2 |
| `/cg state capture\|list\|delete\|apply` | saved zone states | 2 |
| `/cg buy <zone> [confirm]` | buy a zone on sale | none |
| `/cg event join\|leave\|spectate\|list\|launch` | player-facing event commands | none |
| `/cg event announce\|score\|snapshot\|template` | event administration | 2 |
| `/cg hotpotato on\|off\|bomb\|explode` | the hot potato game | 2 |
| `/cg playerworld list\|delete` | per-player dimensions | 2 |
| `/cg setspawn` · `/cg pasture` · `/cg restoreinv` | dimension spawn, pasture caps, inventory recovery | 2 |
| `/cg adv check\|list` | advancement dump | none |
| `/prison set\|release\|list\|info\|setzone\|setout\|clearout` | the prison | 2 |
| `/chunksuggest` | suggest your location for map regeneration | none |

The rows marked *none* are open to every player: two of them deliberately (`/cg buy`,
`/chunksuggest`), the rest because nobody got round to gating them. Full detail in
[commands](docs/commands.md) and [operations](docs/commands-operations.md).

## Dashboard

Registers under the `cheeseguard` prefix, so everything lives at
`/api/v1/cheeseguard/*`, dispatched to eight APIs: zones, groups, dimensions, events,
players, chunks, races, prison. One WebSocket channel is published — `prison`; everything
else is polled. Group flags, dimension creation, the event timeline editor, chunk sets,
race tracks, webhooks and zone sale configuration exist **only** here, with no command
equivalent.

## Things worth knowing before you dig in

- **Event states are `IDLE → ANNOUNCED → QUEUE → COUNTDOWN → ACTIVE → ENDING → CLEANUP`.**
  There is no `PAUSED`, there is no pause or resume anywhere, and adding one is harder than
  it looks — remaining time is computed from wall-clock timestamps, several of which are
  persisted.
- **Per-player dimension ids are `<masterDimId>_<first 8 hex of the player's UUID>`.**
- **Built-in dimension ids contain a colon** (`minecraft:overworld`), which breaks naive
  path parsing. Snapshot and per-player lookups are deliberately skipped for them.
- **Event templates live in `config/cheeseguard_templates/`**, not `config/templates/`.
- **Event participants come back as a `Map`, not a `List`** — iterate `.values()`.
- **Dimension deletion waits 400–500 ms before removing files on Windows**, because file
  locks outlive the unload, and it can still fail.
- **Saves are not atomic.** A crash mid-save of `regions.json` loses every zone, silently,
  because the loader swallows parse errors. Back up `config/cheeseguard/` with your world.

## Known rough edges

This mod grew under deadline pressure on a live server. It works, it has run for months
with hundreds of players, and parts of it are not pretty: `MiniGameManager` alone is
5 244 lines covering four unrelated games, every class reaches every other through a
static `CheeseGuardMod.INSTANCE`, there are no tests, and four mixins target third-party
mods by class name and will break when those mods update. The candid list is the last
section of [architecture](docs/architecture.md#known-weaknesses).

Some default labels — flag display names, the prison welcome message, the dashboard
sidebar description — are hardcoded **French** strings rather than language keys. They are
cosmetic; the real language files are under
`src/main/resources/assets/cheeseguard/lang/`.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
