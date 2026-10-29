[← CobbleCheeseScore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](leaderboards.fr.md)

# Leaderboards

A leaderboard is an entry in the `scoreboards` array of
`config/cobblecheesescore.json`. The entry describes how the board looks and how many
players it holds. What it **measures** is decided by its `id`, in a `switch` inside
`ScoreboardManager.calculateScore`. That split is the single most important thing to
understand about this mod: the config cannot create a new kind of measurement, only a new
presentation of an existing one.

---

## The object model

```text
Config                                  config/cobblecheesescore.json
 ├── global settings                     interval, time zone, footer…
 ├── defaultColors : Map<String,String>  fallback palette
 └── scoreboards   : List<ScoreboardConfig>
                       └── id, enabled, title, description,
                           sortOrder, maxPlayers, rowsPerColumn,
                           colors : Map<String,String>

ScoreboardManager
 ├── scoreboards : Map<id, List<PlayerScore>>   the computed rankings
 └── dynamicTitles : Map<id, String>            title overrides from external JSON

PlayerScore   uuid · playerName · score (double) · formattedScore · ascending
```

`PlayerScore` carries its own sort direction and implements `Comparable`, so
`Collections.sort` on a list of them orders the board. Sorting a list whose entries
disagree about the direction is undefined; in practice every entry in one board shares
one value.

## Every field of a board

| Field | Type | Default | What it does |
|---|---|---|---|
| `id` | string | none | selects the measurement, names the API route, keys the image cache |
| `enabled` | boolean | `true` | `false` skips the board on every pass — no new ranking, no new image. Whatever was cached before stays served until restart |
| `filename` | string | none | **dead field.** No code reads it. See the note below |
| `title` | string | none | the big line in the image header |
| `description` | string | none | the small line under it |
| `sortOrder` | string | `"desc"` | `"asc"` ranks the smallest score first; any other value, including a typo, means descending |
| `maxPlayers` | integer | `30` | the ranking is truncated to this length after sorting |
| `rowsPerColumn` | integer | `10` | drives the column count in the rendered image |
| `colors` | object | `{}` | per-board overrides on top of `defaultColors` |

`filename` survives from the version of the mod that wrote PNG files to disk and served
them from its own web server on port 44310. That server is gone. The field is still
written out, and the Conseil 4 seeder still fills it in, but no URL and no file path
derives from it. The id is what addresses a board now.

Missing fields fall back to the field initialisers in `ScoreboardConfig.java`, not to
`defaultMaxPlayers` / `defaultRowsPerColumn` in the global config — those two global keys
are reported by `GET /config` and otherwise unused. A board with no `maxPlayers` gets 30
because `ScoreboardConfig` declares `private int maxPlayers = 30`, which happens to be
the same number.

### Colour resolution

`getColorOrDefault(key, defaults)` tries the board's own `colors`, then
`defaultColors`, then `#FFFFFF`. Five keys actually change the image:

| Key | Effect |
|---|---|
| `background` | canvas gradient start |
| `titleBackground` | header gradient start, **and** the colour of every score value |
| `titleText` | title and description text; omit it and the renderer computes black or white from the header's luminance |
| `text` | player names below rank 1 |
| `footerText` | the timestamp line |

`firstPlaceBackground`, `secondPlaceBackground`, `thirdPlaceBackground` and
`topPlayerText` are ignored — the podium styling is hard-coded. Details in
[PNG export](png-export.md#colour-keys-the-renderer-ignores).

---

## Data sources

Every non-Conseil-4 board is computed from three files per player, read straight off disk
by `DataCollector`. The mod never writes to the world folder.

```text
<world>/
├── pokedex/<xx>/<uuid>.nbt            Cobblemon Pokédex, GZIP NBT
├── cobblemonplayerdata/<xx>/<uuid>.json   Cobblemon counters
└── stats/<uuid>.json                   vanilla statistics
```

`<xx>` is the first two characters of the UUID. `DataCollector` tries the sharded path
first, then the flat one (`pokedex/<uuid>.nbt`), for both Cobblemon folders. Vanilla
stats are only looked up flat, because that is where the vanilla server puts them.

The world folder is found from `server.getSaveProperties().getLevelName()` resolved
against the run directory. Set `autoDetectDataFolder` to `false` and fill
`manualDataFolderPath` to override it.

### The player list comes from the Pokédex folder

`collectAllPlayerData()` walks `<world>/pokedex` two levels deep, treats every `*.nbt`
filename as a UUID, and builds one `PlayerData` per file. A player with no Pokédex file
does not exist as far as this mod is concerned, no matter how many hours or deaths they
have in `stats/`. Cobblemon writes that file the first time a player encounters a
Pokémon.

If the `pokedex` folder itself is absent, `collectAllPlayerData` logs
`Pokedex folder not found: <path>` and returns an empty map. `updateAll` then logs
`No player data found — continuing to render external-source boards (Conseil 4)` and
carries on, so the Conseil 4 boards still render and every other board comes out empty.
In that case the previous `playerData` cache is **not** cleared: `/stats` keeps
reporting the old `totalPlayersTracked`.

### What each board measures

Twelve boards read real data. Everything under `advancementData` comes from the Cobblemon
JSON; the counter names are Cobblemon's, not this mod's.

| `id` | Source | Field or computation | `formattedScore` |
|---|---|---|---|
| `pokedex_completed` | Pokédex NBT | species with at least one form whose `knowledge` is `CAUGHT` | `906/1025 (88%)` |
| `shiny_hunter` | Cobblemon | `advancementData.totalShinyCaptureCount` | `69` |
| `play_time` | vanilla stats | `minecraft:play_time` ÷ 20 ÷ 3600 | `141.4h` |
| `battle_elite` | Cobblemon | `advancementData.totalBattleVictoryCount` | `443` |
| `trainer_tamer` | Cobblemon | `advancementData.totalPvWBattleVictoryCount` | `347` |
| `distance_traveled` | vanilla stats | sum of `walk_one_cm`, `sprint_one_cm`, `fly_one_cm`, `swim_one_cm`, ÷ 100 000 | `829.7 km` |
| `evolutionist` | Cobblemon | `advancementData.totalEvolvedCount` | `225` |
| `pokeball_crafter` | vanilla stats | every `minecraft:crafted` entry whose key contains both `ball` and `cobblemon` | `5964` |
| `pvp_champion` | Cobblemon | `advancementData.totalPvPBattleVictoryCount` | `4` |
| `trader` | Cobblemon | `advancementData.totalTradedCount` | `56` |
| `survivor` | vanilla stats | `minecraft:deaths` | `10 morts` |
| `legendary_hunter` | Pokédex NBT | caught species matching a hard-coded list of 100-odd legendaries and mythicals | `7` |

Eighteen type boards read `advancementData.totalTypeCaptureCounts`, keyed by the
lowercase type name: `type_normal`, `type_water`, `type_fire`, `type_grass`,
`type_electric`, `type_ice`, `type_fighting`, `type_poison`, `type_ground`,
`type_flying`, `type_psychic`, `type_bug`, `type_rock`, `type_ghost`, `type_dragon`,
`type_dark`, `type_steel`, `type_fairy`. Each ships with that type's standard colour as
its `titleBackground`.

`pokedex_completed` divides by a hard-coded total of 1 025 species. A Cobblemon install
with fewer species implemented, or a datapack with more, makes that percentage wrong. The
constant is in `ScoreboardManager.formatScore`.

`survivor` is titled *Roi des Morts* and ships with `sortOrder: "desc"`, so it ranks the
player with the **most** deaths first. It is a wall of shame, not a survival board, and
it is the only board where a score of zero is kept rather than filtered out.

An id with no case in the `switch` logs `Unknown scoreboard ID: <id>` once per player per
refresh and scores 0, which means the board filters everyone out and renders empty.

### A player with a zero score is dropped

Any player whose computed score is exactly 0 is skipped before sorting, with `survivor`
as the sole exception. The practical effect: `playerCount` on a board is the number of
players with a non-zero value, not the number of players on the server. On a new server
most type boards are empty.

---

## Refresh periodicity

`updateIntervalMinutes` (default `10`) is read **once**, when `SERVER_STARTED` fires, and
handed to `scheduler.scheduleAtFixedRate` as both the initial delay and the period. So:

- the first scheduled run happens `updateIntervalMinutes` after startup, on top of the
  unconditional initial update that runs during `SERVER_STARTED`;
- changing the value later, by hand or through the dashboard, has no effect until the
  server restarts. Nothing reschedules the executor.

A full pass reads three files per player and renders every enabled board. On a world with
30 players and 34 boards that is around 90 file reads and 34 images. The log brackets it:

```text
[CobbleCheeseScore] Collecting player data...
[CobbleCheeseScore] Collected data for 30 players
[CobbleCheeseScore] Updated scoreboard: pokedex_completed (25 players)
...
[CobbleCheeseScore] All scoreboards updated successfully
```

Time the gap between the first and last line on your own hardware before raising
`maxPlayers` or lowering the interval. No benchmark is shipped with the mod and the
figures in the older README were not measured.

A single board failing does not abort the pass: the loop catches per board and logs
`Failed to update scoreboard: <id>`, leaving that board's previous image in the cache.

---

## External-source boards: Conseil 4

Four board ids bypass `PlayerData` completely. `calculateScoreboard` short-circuits on
the `conseil4_` prefix and reads a JSON file written by [CobbleCap](../../CobbleCap/).

| Board id | File read from `config/` | Ranked by |
|---|---|---|
| `conseil4_first_finish` | `cobblecheesescore_external_conseil4_first.json` | `rank`, ascending |
| `conseil4_fastest_time` | `cobblecheesescore_external_conseil4_fastest.json` | `bestTimeMs`, ascending |
| `conseil4_ranked_champions` | `cobblecheesescore_external_conseil4_ranked_champions.json` | `rank`, ascending |
| `conseil4_ranked_failures` | `cobblecheesescore_external_conseil4_ranked_failures.json` | `failures`, descending |

`Config.load()` seeds these four entries into the config if they are absent and re-saves
the file, so they appear in every install without an edit. They are enabled by default.

The file shape:

```json
{
  "dynamicTitle": "Échecs face à RoiCheese",
  "ranking": [
    {
      "playerUuid": "5e1b2c3d-7a8f-4b6c-9d0e-1f2a3b4c5d6e",
      "playerName": "RoiCheese",
      "rank": 1,
      "bestTimeMs": 184320,
      "bestTimeFormatted": "3:04",
      "startedAt": 1747440000000,
      "isCurrent": true,
      "failures": 3
    }
  ]
}
```

Which fields matter depends on the board. `playerUuid` and `playerName` always;
`bestTimeMs` and `bestTimeFormatted` only for the fastest board; `startedAt` (or
`completedAt` as a fallback) and `isCurrent` only for the champions board, which formats
its score as `👑 dd/MM/yyyy` for the sitting champion and a bare date for everyone else;
`failures` only for the failures board, formatted as `3 échecs`. The first-finish board
formats its score as `#1`.

`dynamicTitle` is optional and overrides the board's configured `title` in the rendered
image. Only CobbleCap's failures export sets one, so that the picture carries the current
champion's name. When a file stops supplying `dynamicTitle`, the override is removed and
the configured title comes back. If the file disappears altogether, or fails to parse, the
last title read stays in place until a readable file says otherwise.

> The field names and the pre-sorted `ranking` array are CobbleCap's contract. Nothing
> validates them, and nothing here produces them. Both halves have to be kept in step by
> hand; if CobbleCap changes its export, these four boards go empty or wrong without a
> log line saying why.

### When the source is absent

This is the normal case for anyone who is not running CobbleCap.

| Situation | Behaviour |
|---|---|
| file does not exist | `DEBUG` log `Conseil 4 export file missing: <path> — empty board`, empty ranking, image renders with just the header and footer |
| file exists but has no `ranking` key | empty ranking, no warning |
| file is malformed JSON | `WARN` log `Failed to load Conseil 4 board <id>: <message>`, empty ranking |
| board id starts with `conseil4_` but is not one of the four | `WARN` log `Unknown Conseil 4 board ID: <id>`, empty ranking |

Nothing throws, nothing is disabled automatically. You get four empty boards in the
dashboard list and four nearly-blank images. Set `"enabled": false` on all four in
`config/cobblecheesescore.json` if you do not want them — the seeder only adds a board
when its id is missing, so a disabled entry stays disabled across restarts.

---

## Adding a measurement

Config alone cannot do it. Adding a board that counts something new takes a code change
in two places:

1. a `case` in `ScoreboardManager.calculateScore(String, PlayerData)` returning the
   number, usually by way of a new helper on `PlayerData`;
2. optionally a `case` in `formatScore` if a bare integer is not the right display.

Then add the config entry with that id. Adding the config entry alone gets you a board
that logs `Unknown scoreboard ID` and renders empty.

A measurement that is not in the three source files needs more: either a new parser in
`com.cobblecheesescore.data`, or the Conseil 4 pattern — have another mod write a ranked
JSON file into `config/` and read it the way `loadConseil4Board` does.

---

← [Configuration](configuration.md) · [PNG export](png-export.md) →
