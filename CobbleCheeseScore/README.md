# CobbleCheeseScore

> Thirty-odd leaderboards for a Cobblemon server, rendered as PNG images you can hang
> on an in-world map or embed elsewhere.

The mod reads player data straight off disk — Pokédex NBT, Cobblemon player data,
vanilla stats — ranks everyone, and renders each leaderboard to a PNG with
configurable colours. Images refresh on a timer and are served through CheeseDash.

**Minecraft 1.21.1 · Fabric · Java 21 · 16 files, ~2 700 lines · server-side**

---

## Requirements

- **CheeseDash** — required. Leaderboards are configured and served through it.
- Fabric Loader ≥ 0.16.0, Java 21
- **Cobblemon** — optional at build time, required in practice: without it there is no
  data to rank.

Build CheeseDash first, then drop both in `mods/`. `environment` is `server`.

## Where the data comes from

The mod reads these files out of your world folder. Nothing is written back.

```
world/
├── pokedex/<uuid>.nbt                 ← player Pokédex
├── cobblemonplayerdata/<uuid>.json    ← Cobblemon stats
└── stats/<uuid>.json                  ← vanilla Minecraft stats
```

A player with no Pokédex file simply does not appear — they have to have caught at
least one Pokémon first.

## How you reach the leaderboards

Through **CheeseDash**, under the `scores` section. The API lives at
`/api/v1/scores/*`:

| Method | Route | Purpose |
|--------|-------|---------|
| GET | `/scoreboards` | list every board |
| GET | `/scoreboards/<id>` | one board's data |
| GET | `/scoreboards/<id>/image` | the rendered **PNG** |
| PUT | `/scoreboards/<id>` | edit a board |
| POST | `/refresh` | force a regeneration |
| GET/PUT | `/config` | global configuration |
| GET | `/stats` | collection statistics |
| GET/POST/PUT/DELETE | `/minigames[/<id>]` | minigame boards and sessions |

> ### ⚠️ If you read an older guide, ignore its access section
>
> An earlier version ran **its own web server on port 44310**, serving the PNGs and a
> JSON API publicly with no authentication. **That server no longer exists** — it was
> removed when the mod moved to CheeseDash.
>
> The `port`, `enableWebInterface` and `enableJsonApi` keys are still written to the
> config file but are **dead**: no code reads them. Changing them does nothing.
>
> The practical consequence is good news — leaderboard images now sit behind
> CheeseDash's authentication instead of being world-readable. The flip side is that
> embedding an image in a public website now needs something that holds a CheeseDash
> session to proxy it, or an export of your own.

## Configuration

`config/cobblecheesescore.json`, created on first boot.

### Global

```json
{
  "updateIntervalMinutes": 10,
  "timeZone": "Europe/Paris",
  "autoDetectDataFolder": true,
  "manualDataFolderPath": "",
  "defaultMaxPlayers": 30,
  "defaultRowsPerColumn": 10,
  "showLastUpdate": true,
  "lastUpdateText": "Last updated:",
  "enableDiscordWebhook": false,
  "discordWebhookUrl": "",
  "enableHistory": true,
  "historyRetentionDays": 30
}
```

If auto-detection of the world folder fails, set `autoDetectDataFolder` to `false` and
fill in `manualDataFolderPath`.

### Colours

```json
"defaultColors": {
  "background": "#141414",
  "titleBackground": "#009B72",
  "titleText": "#FFFFFF",
  "text": "#FFFFFF",
  "footerText": "#FFFF00",
  "firstPlaceBackground": "#ff715b",
  "secondPlaceBackground": "#ff5b42",
  "thirdPlaceBackground": "#ff2f0f"
}
```

### One board

Any colour key can be overridden per board:

```json
{
  "id": "shiny_hunter",
  "enabled": true,
  "filename": "shiny.png",
  "title": "Shiny Hunter",
  "description": "Shinies caught",
  "sortOrder": "desc",
  "maxPlayers": 30,
  "rowsPerColumn": 10,
  "colors": { "titleBackground": "#FFD700" }
}
```

Set `"enabled": false` to stop generating a board.

## The boards

### Main (11)

| Id | Measures | Score format |
|----|----------|--------------|
| `pokedex_completed` | species caught | `906/1025 (88%)` |
| `shiny_hunter` | shinies caught | `69` |
| `play_time` | hours played | `141.4h` |
| `battle_elite` | total battle wins | `443` |
| `trainer_tamer` | wins against trainers | `347` |
| `distance_traveled` | distance covered | `829.7 km` |
| `evolutionist` | evolutions performed | `225` |
| `pokeball_crafter` | Poké Balls crafted | `5964` |
| `pvp_champion` | PvP wins | `4` |
| `trader` | trades completed | `56` |
| `survivor` | fewest deaths (ascending) | `10 deaths` |

### By type (18)

`type_normal`, `type_water`, `type_fire`, `type_grass`, `type_electric`, `type_ice`,
`type_fighting`, `type_poison`, `type_ground`, `type_flying`, `type_psychic`,
`type_bug`, `type_rock`, `type_ghost`, `type_dragon`, `type_dark`, `type_steel`,
`type_fairy` — each pre-coloured with that type's official colour.

### Minigames

A separate board family with its own sessions and a persistent legends list.

## Adding your own board

Add an entry to `scoreboards` in the config, **then implement its score calculation in
`ScoreboardManager.java`**. Config alone is not enough — the mod has to be told how to
compute the number.

## Discord notifications

```json
{
  "enableDiscordWebhook": true,
  "discordWebhookUrl": "https://discord.com/api/webhooks/..."
}
```

Posts when the top spot on a board changes hands.

## Performance

| Players | Full refresh | Memory | Suggested interval |
|---------|--------------|--------|--------------------|
| ~30 | 1–2 s | 50–100 MB | 10 min |
| 100+ | 3–5 s | 100–200 MB | 15–20 min |

Lower `defaultMaxPlayers` and raise `updateIntervalMinutes` if it costs too much.

## Troubleshooting

**No data at all.** Check the three source folders exist under `world/` and that
players have actually caught Pokémon. Look for
`[CobbleCheeseScore] Collected data for N players` in the log.

**Images look stale.** Check `updateIntervalMinutes`, then force a refresh with
`POST /api/v1/scores/refresh`.

**A player is missing.** They have no Pokédex `.nbt` file yet.

Useful log lines:

```
[CobbleCheeseScore] Configuration loaded from /config/cobblecheesescore.json
[CobbleCheeseScore] Auto-detected world path: /world
[CobbleCheeseScore] Collected data for 30 players
[CobbleCheeseScore] Updated scoreboard: pokedex_completed (25 players)
```

## Arkensia specifics

`ScoreboardManager` can load four "Conseil 4" ranking boards from a JSON export
produced by **CobbleCap**. Those are tied to Arkensia's league format; without
CobbleCap's export file the boards are simply empty and nothing breaks.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
