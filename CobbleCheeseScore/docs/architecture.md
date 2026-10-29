[← CobbleCheeseScore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

16 Java files, about 2 940 lines, no mixins, no registered items, blocks or entities, no
commands. The mod is a disk reader, a sorter, an `awt` renderer and a REST handler bolted
together by a static holder class.

---

## Package layout

```text
com.cobblecheesescore
├── CobbleCheeseScore.java      entrypoint, static holders, lifecycle, scheduler
├── cache/
│   └── CacheManager.java       in-memory PlayerData map, image map, lastUpdate
├── config/
│   ├── Config.java             global config, load/save, Conseil 4 seeding
│   └── ScoreboardConfig.java   one board definition
├── dashboard/
│   ├── CobbleCheeseScoreDashboardModule.java   the `cheesedash` entrypoint
│   └── ScoresApi.java          every REST route, 728 lines
├── data/
│   ├── DataCollector.java      world path detection, per-player file discovery
│   ├── PlayerData.java         one player's three sources + the derived metrics
│   ├── PokedexNBTParser.java   speciesRecords → SpeciesRecord/FormRecord
│   └── CobblemonDataParser.java  the two JSON sources
├── image/
│   └── ImageGenerator.java     Graphics2D rendering, 254 lines
├── scoreboard/
│   ├── ScoreboardManager.java  ranking, the id → measurement switch, Conseil 4
│   ├── PlayerScore.java        one ranked row, Comparable, carries its direction
│   ├── MiniGameManager.java    mini-game ledger + points rule
│   └── MiniGameLegends.java    permanent mini-game archive
└── utils/
    └── NBTReader.java          hand-written NBT parser
```

## Who owns what

**`CobbleCheeseScore`** is the entrypoint and the service locator. Every component is a
private static field with a public static getter, and `ScoresApi` reaches them that way
rather than being handed anything. There is no dependency injection and no interface
between the API and the managers. `onInitializeServer` builds `Config`, `CacheManager`
and `MiniGameManager`; `SERVER_STARTED` builds `DataCollector` and `ScoreboardManager`,
runs one refresh, and starts the scheduler.

**`Config`** is the whole config file as a Gson-mapped object, plus `load()`, `save(Path)`
and the Conseil 4 seeder. It is mutable and shared: `ScoresApi` mutates `ScoreboardConfig`
objects in place and then calls `save`. There is no copy and no validation layer.

**`DataCollector`** resolves the world path once, in its constructor, then walks
`<world>/pokedex` on every collection pass to discover players. It does not cache
anything; the parsers write straight into a fresh `PlayerData` per player per pass.

**`PlayerData`** holds the three raw sources as public mutable maps and exposes one getter
per metric. The 100-odd legendary species ids live here as a `static final Set<String>`,
which is the mod's one piece of hard-coded game knowledge. The 1 025-species total used
for the Pokédex percentage lives in `ScoreboardManager.formatScore` instead — two
Pokémon-generation constants in two different files.

**`ScoreboardManager`** owns the ranking map and the measurement `switch`. It also owns
`dynamicTitles`, the only `ConcurrentHashMap` in the mod, populated from the Conseil 4
exports.

**`ImageGenerator`** is stateless apart from its `Config` reference. It knows nothing
about boards beyond the `ScoreboardConfig` it is handed.

**`CacheManager`** is two `HashMap`s and a `long`. It has a `clearAll()` nobody calls.

**`ScoresApi`** is entirely static methods. It is the only class that imports
`com.cheesedash.*` besides the module.

**`NBTReader`** is a from-scratch NBT parser: GZIP-sniff, then a recursive reader over all
twelve tag types. It exists because reading Cobblemon's Pokédex file through Minecraft's
own `NbtIo` would mean touching world state from a non-server thread. The trade-off is
that it is 117 lines of format code the mod maintains itself.

## State and when it hits disk

| State | Lives in | Written to disk |
|---|---|---|
| global config and board definitions | `Config` | on first load when the file is absent; when a Conseil 4 board is seeded; on `PUT /scoreboards/:id`; on `PUT /config` |
| player data | `CacheManager.playerDataCache` | never |
| rendered images | `CacheManager.imageCache` | never — PNG bytes are produced per request |
| rankings | `ScoreboardManager.scoreboards` | never |
| dynamic titles | `ScoreboardManager.dynamicTitles` | never — re-read from the Conseil 4 export each pass |
| mini-game sessions | `MiniGameManager.games` | on every create, rename, delete, session add and session delete |
| mini-game archive | `MiniGameLegends.records` | on every recorded session |

Nothing is written on shutdown. `onServerStopping` shuts the scheduler down and nulls the
server reference; everything in memory is discarded. That is safe because every mutation
already wrote through, but it means a crash loses nothing and a graceful stop saves
nothing either.

`MiniGameLegends.save()` writes a `.tmp` sibling and moves it with `ATOMIC_MOVE`, falling
back to a plain move. `Config.save()` and `MiniGameManager.save()` do a direct
`Files.writeString` over the live file — a crash mid-write truncates them.

## Threading

Four kinds of thread touch this mod's state.

| Thread | What runs on it |
|---|---|
| the mod-init thread | `onInitializeServer`: config load, `CacheManager`, `MiniGameManager` (which reads its file) |
| the server thread | `SERVER_STARTED` and `SERVER_STOPPING`, including the initial full refresh |
| `pool-N-thread-1` | the `ScheduledExecutorService`, one thread, running `updateAll` every `updateIntervalMinutes` |
| `CheeseDash-HTTP` (4 of them) | every `ScoresApi` handler |

**There is no hand-off to the server thread anywhere.** No `executeOnServerThread`, no
`server.execute`. That is defensible: a refresh reads files off disk and touches no world
state, no entity and no block. The two Minecraft calls in the whole data path are
`server.getPlayerManager().getPlayerList()` and `server.getUserCache().getByUuid(...)` in
`DataCollector.getPlayerName`, both read-only lookups that happen to be reasonably
tolerant of being called from elsewhere — but neither is documented as thread-safe, and
calling them from an HTTP thread is the one genuine thread-safety gamble in the mod.

### The refresh race

`updateAll()` is reachable from the scheduler thread and from any of the four HTTP
threads, and it is not synchronised.

- `ScoreboardManager.scoreboards` is a plain `HashMap`, mutated by `put` during a pass and
  read by `getScoreboard` on HTTP threads.
- `CacheManager.imageCache` and `playerDataCache` are plain `HashMap`s, with
  `setPlayerData` **replacing** the map reference mid-pass.
- Two concurrent passes interleave their `put` calls freely.

In practice the window is a second or two every ten minutes and the consequence of losing
it is a stale or half-updated image. The failure mode that actually bites is a
`ConcurrentModificationException` or a corrupted `HashMap` under a hot loop of
`POST /refresh` calls. Do not script that endpoint on a tight interval.

A single `synchronized` on `updateAll` plus `ConcurrentHashMap` in `CacheManager` would
close this. It has not been done.

### Rendering off the server thread

`Graphics2D`, `BufferedImage` and `ImageIO` all run on whatever thread calls them, with no
AWT event loop involved. Nothing sets `-Djava.awt.headless=true`; it has not been needed,
because none of the classes used require a display. A JVM configured with a broken font
path will throw inside `new Font(...)`, caught per board by `updateAll`.

## Dashboard integration

`fabric.mod.json` declares two entrypoints:

```json
"entrypoints": {
  "server":     ["com.cobblecheesescore.CobbleCheeseScore"],
  "cheesedash": ["com.cobblecheesescore.dashboard.CobbleCheeseScoreDashboardModule"]
}
```

CheeseDash calls `FabricLoader.getEntrypoints("cheesedash", DashboardModule.class)` at
startup and registers whatever it finds. `getPrefix()` returns `scores`, which maps the
module onto `/api/v1/scores/*` behind the auth middleware. `getPublicPrefix()` also
returns `scores`, which makes CheeseDash create an **unauthenticated** context at
`/scores/` — registered before `/api/`, deliberately outside the auth check, so an
in-game image frame can fetch a PNG without a token.

The module itself holds only the `MinecraftServer` reference and answers `503` while it is
null. Everything else is delegated to `ScoresApi` as a static call.

### No role checks

`ScoresApi` never calls `WebDashboard.requireWrite` or `requireAdmin`. Every route,
including the mutating ones, is open to any authenticated session whatever its role. This
is not a subtlety of how `requireWrite` is used — in this repository it returns a boolean
and must be guarded as `if (!WebDashboard.requireWrite(exchange)) return;`, and ignoring
the return value would be worse than useless — the calls are simply absent. Documented
with its consequences in [dashboard](dashboard.md#required-role).

## Mixins

None. `"mixins": []` in `fabric.mod.json`, and no mixin config file exists. The mod reads
files and serves HTTP; it never needs to change Minecraft's behaviour. That is the main
reason it is unusually robust across Cobblemon updates compared with the other mods in
this repository — it depends on Cobblemon's **on-disk format**, not its classes. There is
no compile-time dependency on Cobblemon at all.

## Extension points for a contributor

**A new measurement.** Add a `case` to `ScoreboardManager.calculateScore`, usually backed
by a new getter on `PlayerData`, and optionally a `case` in `formatScore`. Then add a
config entry with that id. Config alone does nothing — an unknown id logs
`Unknown scoreboard ID` and scores 0.

**A new data source.** Add a parser in `com.cobblecheesescore.data` and call it from
`DataCollector.collectPlayerData`, next to the three existing calls. `PlayerData` already
has the shape for raw maps plus derived getters.

**An externally-fed board.** Follow the Conseil 4 pattern: have another mod write a ranked
JSON file into `config/`, add the id prefix to the short-circuit in
`calculateScoreboard`, and a loader modelled on `loadConseil4Board`. That is the way to
rank something this mod cannot see, and it is how CobbleCap feeds the league boards.

**A different look.** `ImageGenerator` is self-contained and takes
`(ScoreboardConfig, List<PlayerScore>, String dynamicTitle)`. Replacing it wholesale
breaks nothing else.

**WebSocket push.** Nothing here broadcasts. `DashboardRegistry.broadcast(...)` from the
end of `updateAll` would give the dashboard live refresh notifications.

---

## Known weaknesses and deliberate trade-offs

Read this section before changing anything.

**No role checks on the REST API.** The most serious one. A READ_ONLY dashboard account
can rewrite board titles, create and delete mini-games, delete recorded sessions and force
refreshes.

**The refresh race.** Described above. Plain `HashMap`s mutated from two kinds of thread
with no synchronisation.

**Nine dead config keys.** `port`, `enableWebInterface`, `enableJsonApi`,
`enableDiscordWebhook`, `discordWebhookUrl`, `enableHistory`, `historyRetentionDays`,
`defaultMaxPlayers`, `defaultRowsPerColumn`, plus `filename` on every board. The first
three are leftovers from a standalone web server on port 44310 that was removed when the
mod moved to CheeseDash. The Discord pair never had an implementation — there is no
webhook code in this mod and never has been in this version of the tree. `enableHistory`
and `historyRetentionDays` likewise describe a history feature that does not exist. They
are kept so existing config files keep parsing, which is also why they keep misleading
people. Removing them would be a kindness.

**Four dead colour keys.** `firstPlaceBackground`, `secondPlaceBackground`,
`thirdPlaceBackground` and `topPlayerText` are in the shipped default config and are
ignored by the renderer; `getRowBackgroundColor(int, ScoreboardConfig)` takes the board
config and never reads it. Podium colours are hard-coded gold / silver / bronze.

**`PUT /config` does nothing.** Its one conditional branch logs the requested change and
does not apply it, behind a stale comment claiming `ScoreboardConfig` has no setter.
`setEnabled` exists. The route still answers `{"saved":true}`.

**`ImageGenerator.java.backup` is checked in.** A dead copy of an older renderer sitting
next to the live file in `src/main/java/com/cobblecheesescore/image/`. It is not compiled —
the extension saves it — but it will drift and confuse. Delete it.

**`fabric.mod.json` points at a missing icon.** `"icon": "assets/cobblecheesescore/icon.png"`,
and `src/main/resources/assets/cobblecheesescore/` contains only `lang/`. Mod Menu shows a
placeholder.

**The font is a wish, not a dependency.** Every `new Font("Inter", …)` call silently falls
back to a logical font on a host without Inter installed, which is most of them. The
images render; the typography is not what was designed.

**Hard-coded game knowledge in two places.** The legendary species set in `PlayerData` and
the 1 025-species Pokédex total in `ScoreboardManager`. Both go stale with each Pokémon
generation, and neither is configurable. A Cobblemon install with a species datapack makes
the Pokédex percentage wrong.

**`survivor` is misnamed.** It ships with `sortOrder: "desc"` on a death count, so it
ranks the *most* deaths first. The title *Roi des Morts* is honest about it; the id is not.

**Unvalidated identifiers.** Mini-game ids are accepted as submitted. One containing `/`
makes its own routes unreachable; one equal to `global` is shadowed by the global-ranking
route. Board ids come from a config file an admin wrote, so they are less exposed.

**`getPlayerName` falls back to eight characters of UUID** when a player is neither online
nor in the user cache. Those entries look like truncated garbage in a rendered image and
there is no way to tell them apart from a real name of that length.

**A hand-written NBT parser.** 117 lines reimplementing a format Minecraft already parses.
It avoids a thread-safety problem and it works, but a format change or an unexpected tag
type throws `Unknown tag type: <n>`, caught by `PokedexNBTParser` and logged as a failure
for that one player.

**Dead code.** `CacheManager.clearAll`, `PlayerScore.isAscending`,
`PlayerData.getPokedexSeen`, `PlayerData.getTotalCaptureCount`,
`MiniGameManager.getLegends`, `MiniGameLegends.getAll` and `getByGameId`,
`ScoreboardManager.getAllScoreboards` and `getDynamicTitle`,
`ScoreboardConfig.getFilename`, and the two-argument
`ImageGenerator.generateScoreboard` overload all have no callers. The legends ones are the
notable pair: the archive is written and never read back by any route, so the mini-game
hall of fame is only readable by opening the JSON file.

---

← [Dashboard API](dashboard.md) · [Troubleshooting](troubleshooting.md) →
