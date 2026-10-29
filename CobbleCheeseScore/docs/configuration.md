[← CobbleCheeseScore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

```text
config/cobblecheesescore.json
```

Written on first start from the bundled resource `cobblecheesescore-default.json`, and
rewritten whenever `Config.load()` seeds a missing Conseil 4 board or a dashboard route
saves. Two more files live alongside it and are not configuration — they are data:

| File | Written by | Contents |
|---|---|---|
| `config/cobblecheesescore_minigames.json` | `MiniGameManager` | mini-game sessions, rolling 500 per game |
| `config/cobblecheesescore_legends.json` | `MiniGameLegends` | permanent mini-game hall of fame |
| `config/cobblecheesescore_external_conseil4_*.json` | **CobbleCap**, not this mod | ranked exports for the four Conseil 4 boards |

The file is parsed by Gson straight onto the `Config` class, so a key the class does not
declare is silently dropped, and a key you delete falls back to the field's initialiser.
Comments are not legal JSON and the shipped default contains none; the annotated example
at the end of this page is for reading, not for pasting.

⚠️ **The mod rewrites this file.** `Config.save()` serialises the in-memory object with
pretty printing, so any key ordering or whitespace you prefer is lost the first time a
Conseil 4 board gets seeded or a dashboard `PUT` lands. Keep your own copy if you hand-tune
it.

---

## Global keys

| Key | Type | Default | What it does |
|---|---|---|---|
| `updateIntervalMinutes` | integer | `10` | minutes between automatic refreshes, and the delay before the first scheduled one |
| `timeZone` | string | `"Europe/Paris"` | zone used for the timestamp in each image's footer |
| `autoDetectDataFolder` | boolean | `true` | `true` derives the world path from the server; `false` uses `manualDataFolderPath` |
| `manualDataFolderPath` | string | `""` | absolute or relative path to the world folder. Only consulted when `autoDetectDataFolder` is `false` |
| `showLastUpdate` | boolean | `true` | draws the footer timestamp line. `false` omits it, and the 60 px of footer space stays blank |
| `lastUpdateText` | string | `"Dernière mise à jour :"` | the label before the timestamp |
| `defaultColors` | object | see below | palette every board falls back to |
| `scoreboards` | array | 30 entries, plus 4 seeded | the board definitions. Each field is documented in [leaderboards](leaderboards.md#every-field-of-a-board) |

### `updateIntervalMinutes`

Read exactly once, when `SERVER_STARTED` fires, and passed to
`scheduleAtFixedRate(task, interval, interval, MINUTES)`. **Changing it takes a server
restart** — nothing reschedules the executor, and `PUT /config` does not even parse the
key. There is no lower bound: `1` is accepted and will have the server re-reading every
player's files every minute, which is wasteful rather than dangerous since the work
happens off the server thread. `0` or a negative value is rejected by
`ScheduledExecutorService` with an `IllegalArgumentException` at startup, which will be
logged and leave the mod running with no scheduler and only the initial render.

The unconditional initial refresh during `SERVER_STARTED` is not affected by this key.

### `autoDetectDataFolder` and `manualDataFolderPath`

Auto-detection resolves `server.getSaveProperties().getLevelName()` against the run
directory. That is right for a standard Fabric server launched from its own directory.
It is wrong when the level name on disk differs from the one in `level.dat`, or when a
host launches the jar from a parent directory. The log tells you which path was chosen:

```text
[CobbleCheeseScore] Auto-detected world path: /home/container/world
```

If that path is not where `pokedex/` actually lives, set `autoDetectDataFolder` to
`false` and give the real path:

```json
"autoDetectDataFolder": false,
"manualDataFolderPath": "/home/container/world"
```

The manual path is only used when it exists; a path that does not resolve logs
`Manual path does not exist: <path>` and falls through to auto-detection. If
auto-detection then throws, the path becomes the literal relative `world`.

⚠️ The world path is resolved **once**, in the `DataCollector` constructor, during
`SERVER_STARTED`. Editing either key mid-session changes nothing until restart.

### `timeZone`

Any IANA zone id. It is passed to `ZoneId.of(...)` with no guard, so a typo throws a
`DateTimeException` inside `generateScoreboard`. The per-board `catch` in `updateAll`
turns that into `Failed to update scoreboard: <id>` for every board in turn, and you end
up with no images at all. If every board fails at once, check this key first.

### `defaultColors`

Shipped default:

| Key | Default | Honoured |
|---|---|---|
| `background` | `#0f0c29` | yes |
| `titleBackground` | `#ffcb05` | yes — header gradient and every score value |
| `titleText` | `#FFFFFF` | yes, but only when set per board |
| `text` | `#FFFFFF` | yes |
| `footerText` | `#ffcb05` | yes |
| `firstPlaceBackground` | `#ff715b` | **no** |
| `secondPlaceBackground` | `#ff5b42` | **no** |
| `thirdPlaceBackground` | `#ff2f0f` | **no** |
| `topPlayerText` | `#FFD700` | **no** |

Values are parsed as `#RRGGBB`; the `#` is optional and anything that fails to parse
becomes white. Shorthand (`#fff`) and alpha (`#RRGGBBAA`) are not supported — `#fff`
falls through the `substring` calls and comes out white.

`titleText` behaves differently from the others: it is read from the **board's own**
`colors` map only, never from `defaultColors`. Put it in `defaultColors` and it is
ignored, and the renderer computes black or white from the header's luminance instead.
That auto-contrast is usually what you want; override it per board when it guesses wrong.

The four unhonoured keys are explained in
[PNG export](png-export.md#colour-keys-the-renderer-ignores).

---

## Keys nothing reads

These are serialised into the file, reported by `GET /config` in some cases, and have no
effect whatsoever. They are left in place rather than removed so that an existing config
file keeps parsing.

| Key | Default | Why it is dead |
|---|---|---|
| `port` | `44310` | the mod ran its own HTTP server on this port before moving to CheeseDash. No code reads it; the dashboard serves everything on 44311 |
| `enableWebInterface` | `true` | the HTML interface it enabled was removed with that server |
| `enableJsonApi` | `true` | likewise — the JSON API is now CheeseDash's |
| `enableDiscordWebhook` | `false` | **no webhook code exists in this mod.** Setting it to `true` posts nothing anywhere |
| `discordWebhookUrl` | `""` | same |
| `enableHistory` | `true` | reported by `GET /config`, acted on nowhere. No history is kept |
| `historyRetentionDays` | `30` | same |
| `defaultMaxPlayers` | `30` | reported by `GET /config`. Boards fall back to `ScoreboardConfig`'s own `30`, not to this |
| `defaultRowsPerColumn` | `10` | same, against `ScoreboardConfig`'s own `10` |

Per board, `filename` is dead for the same reason as `port` — it named the PNG file the
old server wrote to disk. Nothing writes PNGs to disk now.

⚠️ If you read documentation claiming this mod posts a Discord notification when a board's
top spot changes hands, that documentation is wrong. Grep for `discord` in
`src/main/java` and you will find two getters and no caller.

---

## Reload behaviour

Nothing is hot-reloadable. There is no reload command, no file watcher and no reload
route.

| Change | Takes effect |
|---|---|
| a board's `title`, `description`, `enabled`, `maxPlayers`, `rowsPerColumn`, `colors` | immediately, **if** made through `PUT /api/v1/scores/scoreboards/:id` — that route applies, saves and re-renders |
| the same fields edited by hand in the file | next server restart |
| a board's `sortOrder` or `id` | next restart only. The `PUT` route does not accept them |
| adding or removing a board | next restart |
| `updateIntervalMinutes`, `timeZone`, `showLastUpdate`, `lastUpdateText`, `defaultColors` | next restart |
| `autoDetectDataFolder`, `manualDataFolderPath` | next restart |

⚠️ Editing the file by hand **while the server is running** is a trap: the in-memory
`Config` object is authoritative, and the next `PUT` on any board writes it back over
your edit. Stop the server, edit, start it.

---

## A complete annotated example

A trimmed config with three boards, the four Conseil 4 entries left out for length. The
`//` annotations explain the keys and are **not valid JSON** — strip them before use.

```json
{
  // Minutes between automatic refreshes. Read once at startup; restart to change it.
  "updateIntervalMinutes": 10,

  // IANA zone for the footer timestamp. A typo here breaks every board at once.
  "timeZone": "Europe/Paris",

  // Leave true unless the log shows the wrong world path.
  "autoDetectDataFolder": true,
  "manualDataFolderPath": "",

  // Reported by GET /config, used by nothing. Boards default to 30 / 10 on their own.
  "defaultMaxPlayers": 30,
  "defaultRowsPerColumn": 10,

  // Footer line. Set showLastUpdate to false to drop it from every image.
  "showLastUpdate": true,
  "lastUpdateText": "Dernière mise à jour :",

  // Dead keys from the removed standalone web server on port 44310.
  "port": 44310,
  "enableWebInterface": true,
  "enableJsonApi": true,

  // Dead keys. No webhook code exists in this mod.
  "enableDiscordWebhook": false,
  "discordWebhookUrl": "",

  // Dead keys. No history is recorded.
  "enableHistory": true,
  "historyRetentionDays": 30,

  // Fallback palette. The last four keys are ignored by the renderer.
  "defaultColors": {
    "background": "#0f0c29",
    "titleBackground": "#ffcb05",
    "titleText": "#FFFFFF",
    "text": "#FFFFFF",
    "footerText": "#ffcb05",
    "firstPlaceBackground": "#ff715b",
    "secondPlaceBackground": "#ff5b42",
    "thirdPlaceBackground": "#ff2f0f",
    "topPlayerText": "#FFD700"
  },

  "scoreboards": [
    {
      // The id selects the measurement. It must match a case in
      // ScoreboardManager.calculateScore, otherwise the board renders empty.
      "id": "pokedex_completed",
      "enabled": true,
      // Dead field — no URL or file path derives from it.
      "filename": "pokedex.png",
      "title": "Pokédex Complété",
      "description": "Espèces capturées",
      // "desc" puts the highest score first. Only changeable by editing this file.
      "sortOrder": "desc",
      // The ranking is truncated to this many players after sorting.
      "maxPlayers": 30,
      // Drives the column count: ceil(players / rowsPerColumn), clamped to 1-3.
      "rowsPerColumn": 10,
      // Empty means "inherit defaultColors for everything".
      "colors": {}
    },
    {
      "id": "shiny_hunter",
      "enabled": true,
      "filename": "shiny.png",
      "title": "Chasseur de Shiny",
      "description": "Shinies capturés",
      "sortOrder": "desc",
      "maxPlayers": 30,
      "rowsPerColumn": 10,
      // Gold header. Scores in the rows take this colour too.
      "colors": {
        "titleBackground": "#FFD700"
      }
    },
    {
      // Ranks the MOST deaths first, despite the id. A wall of shame.
      // It is also the only board that keeps players whose score is zero.
      "id": "survivor",
      "enabled": true,
      "filename": "deaths.png",
      "title": "Roi des Morts",
      "description": "Nombre de morts",
      "sortOrder": "desc",
      "maxPlayers": 30,
      "rowsPerColumn": 10,
      "colors": {
        "titleBackground": "#8B0000",
        // These three are written out by the default config and ignored by the
        // renderer. Kept here to match what the mod actually ships.
        "firstPlaceBackground": "#FF0000",
        "secondPlaceBackground": "#CC0000",
        "thirdPlaceBackground": "#990000"
      }
    }
  ]
}
```

The full shipped default — 30 boards, every type board with its official colour — is
`src/main/resources/cobblecheesescore-default.json`. Delete
`config/cobblecheesescore.json` and restart to get it back.

---

← [Getting started](getting-started.md) · [Leaderboards](leaderboards.md) →
