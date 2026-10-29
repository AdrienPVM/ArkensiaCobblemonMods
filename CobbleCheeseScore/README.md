# CobbleCheeseScore

> 🇫🇷 [Lire en français](README.fr.md)

> Thirty-four Cobblemon leaderboards, each rendered to a PNG you can hang on a wall
> in-game and leave there.

The mod reads player data straight off disk — Cobblemon's Pokédex NBT, its player-data
JSON, and vanilla's statistics — ranks everyone on whichever boards you have enabled, and
draws each ranking into an image with `Graphics2D`. Nothing is written back to the world
folder, and nothing is written to disk at all: the images live in memory and are encoded
to PNG bytes on each request.

What makes it worth installing is the last link in that chain. Each board is served on a
stable URL that needs no authentication, which means an image-frame mod on the clients can
point at it and the leaderboard becomes part of the world. A player walks past the spawn
noticeboard and sees who is top of the shiny count, with a render timestamp in the footer
proving it is current. Refresh happens on a timer — ten minutes by default — with no
command to run and no file to copy.

There are no commands. Admins drive everything from the CheeseDash dashboard or its REST
API: recolour a board, retitle it, turn it off, force a refresh. A separate mini-game
ledger records event results that nothing in the game counts automatically, scored on a
podium-points rule.

**Minecraft 1.21.1 · Fabric · Java 21 · 16 source files, ~2 940 lines · server-side only**

---

## Requirements

| | |
|---|---|
| **Required** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Required in practice** | [Cobblemon](https://modrinth.com/mod/cobblemon) — declared under `suggests`, so the mod loads without it, but 30 of the 34 boards then have nothing to rank |
| **Optional** | [CobbleCap](../CobbleCap/) — feeds the four Conseil 4 league boards. Without it they render empty |
| **For in-game display** | an image-frame mod on the **clients**, such as WaterFrames. Not a dependency, not installed by this mod |

`"environment": "server"`, and the only entrypoint is `DedicatedServerModInitializer`. The
jar does nothing in a client's `mods/` folder, and nothing starts in single player.

## Install

CobbleCheeseScore compiles against CheeseDash's jar through a `fileTree` glob, so
CheeseDash has to be **built** first:

```bash
cd CheeseDash && ./gradlew build
cd ../CobbleCheeseScore && ./gradlew build
```

Drop `build/libs/cobblecheesescore-1.0.0+1.21.1.jar` and the CheeseDash jar in the
server's `mods/`. On first start the mod writes `config/cobblecheesescore.json` with 30
boards, seeds four more for Conseil 4, and logs:

```text
[cobblecheesescore] CobbleCheeseScore initialized successfully!
[CheeseDash] Public context registered: /scores/
[cobblecheesescore] Scoreboard update scheduler started (interval: 10 minutes)
```

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, build order, what the first launch creates, the log lines that prove it loaded, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: from a fresh server to a recoloured leaderboard hanging on a wall and refreshing itself |
| [Configuration](docs/configuration.md) | `cobblecheesescore.json` in full, what reloads and what needs a restart, the nine keys that do nothing, and a complete annotated example |
| [Leaderboards](docs/leaderboards.md) | Every board field with its type and default, what each of the 34 ids measures, where the data comes from, refresh timing, and the externally-fed Conseil 4 boards |
| [PNG export](docs/png-export.md) | How the image is drawn, how big it comes out, which fonts it asks for, both routes that serve it, and the full procedure for getting it onto a map in-game |
| [Mini-games](docs/minigames.md) | The event-result ledger, its podium-points rule, the 500-session cap and the permanent hall of fame |
| [Dashboard API](docs/dashboard.md) | Every route under `/api/v1/scores`, request and response shapes, status codes, and the role each one really enforces |
| [Architecture](docs/architecture.md) | Package layout, class responsibilities, threading and the refresh race, extension points, and a candid list of weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — including the four reasons an in-game frame shows nothing |

## Commands at a glance

**None.** The mod registers no commands: there is no `commands/` package and no call to
`CommandRegistrationCallback` anywhere in the source. Every operation goes through
CheeseDash.

| Thing you want to do | How |
|---|---|
| see all boards and their top players | `GET /api/v1/scores/scoreboards` |
| fetch a board's rendered PNG | `GET /api/v1/scores/scoreboards/<id>/image` |
| fetch it with no authentication, for in-game use | `GET /scores/api/images/<name>/<id>.png` |
| retitle, recolour, resize or disable a board | `PUT /api/v1/scores/scoreboards/<id>` |
| force a full refresh now | `POST /api/v1/scores/refresh` |
| record a mini-game result | `POST /api/v1/scores/minigames/<id>/sessions` |

## Dashboard

Registers under the `scores` prefix, giving `/api/v1/scores/*`, plus one unauthenticated
public route at `/scores/api/images/…` so that something inside Minecraft can fetch a PNG
without a token. No WebSocket channel: the module broadcasts nothing, so a dashboard view
has to poll.

⚠️ **No route in this module checks the caller's role.** `ScoresApi` contains no call to
`WebDashboard.requireWrite` or `requireAdmin`, so a **READ_ONLY** CheeseDash account can
rewrite board titles and colours, create and delete mini-games, delete recorded sessions
and force refreshes. Hand out accounts accordingly. Details in
[dashboard](docs/dashboard.md#required-role).

## Honest warnings

- **The public image route is open to anyone who can reach port 44311.** It serves player
  names and statistics with no authentication. That is the deliberate trade-off that makes
  in-game display work; put the port behind a proxy if it is not acceptable to you.
- **Nine config keys do nothing.** `port`, `enableWebInterface` and `enableJsonApi` are
  leftovers from a standalone web server on port 44310 that was removed when the mod moved
  to CheeseDash. `enableDiscordWebhook` and `discordWebhookUrl` describe a feature that has
  no implementation at all — **this mod sends nothing to Discord**, whatever older
  documentation claims. `enableHistory`, `historyRetentionDays`, `defaultMaxPlayers` and
  `defaultRowsPerColumn` are reported by the API and acted on nowhere. Full list in
  [configuration](docs/configuration.md#keys-nothing-reads).
- **`PUT /api/v1/scores/config` answers `{"saved":true}` and applies nothing.** Use
  `PUT /scoreboards/:id` for board changes and edit the file for global ones.
- **The images ask for the Inter font**, which a headless server almost never has
  installed. They render in a fallback font with different metrics.
- **`survivor` ranks the most deaths first**, not the fewest. Its title, *Roi des Morts*,
  is accurate; its id is not.
- **Two Pokémon constants are hard-coded** and go stale with each generation: the
  legendary species list in `PlayerData` and the 1 025-species total used for the Pokédex
  percentage.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
