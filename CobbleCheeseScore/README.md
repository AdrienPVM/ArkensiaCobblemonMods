# CobbleCheeseScore

Advanced multi-scoreboard mod for Cobblemon Minecraft servers.

## Features

✅ **30+ Scoreboards** including:
- Pokédex Completed (from real Pokédex NBT file)
- Shiny Hunter
- Play Time
- Battle stats (Elite, Trainers, PvP)
- 18 Type Masters (one per Pokémon type)
- Distance, Evolutions, Trades, Pokéball Crafter, Survivor

✅ **Multiple Output Formats**:
- Individual PNG images per scoreboard (http://server:port/pokedex.png)
- JSON API (http://server:port/api/pokedex.json)
- Interactive web interface (http://server:port/)

✅ **Advanced Features**:
- Auto-refresh every X minutes (configurable)
- Per-scoreboard color customization
- Discord webhooks for top rank changes
- Historical data tracking (30 days retention)
- Smart caching system for performance

✅ **Data Sources**:
- `/world/pokedex/[uuid].nbt` - Real Pokédex data (most reliable)
- `/world/cobblemonplayerdata/[uuid].json` - Cobblemon stats
- `/world/stats/[uuid].json` - Minecraft vanilla stats

## Installation

1. Download `cobblecheesescore-1.0.0.jar`
2. Place in server `/mods/` folder
3. Start server once to generate config
4. Edit `/config/cobblecheesescore.json`
5. Restart server

## Configuration

Config file: `/config/cobblecheesescore.json`

### Global Settings

```json
{
  "port": 44310,
  "updateIntervalMinutes": 10,
  "timeZone": "Europe/Paris",
  "defaultMaxPlayers": 30,
  "enableWebInterface": true,
  "enableJsonApi": true,
  "enableDiscordWebhook": false,
  "discordWebhookUrl": "https://discord.com/api/webhooks/...",
  "enableHistory": true
}
```

### Scoreboard Configuration

Each scoreboard can be customized:

```json
{
  "id": "pokedex_completed",
  "enabled": true,
  "filename": "pokedex.png",
  "title": "Pokédex Complété",
  "description": "Nombre d'espèces capturées",
  "sortOrder": "desc",
  "maxPlayers": 30,
  "rowsPerColumn": 10,
  "colors": {
    "titleBackground": "#009B72"
  }
}
```

### Color Overrides

Override default colors per scoreboard:

```json
"colors": {
  "background": "#141414",
  "titleBackground": "#FFD700",
  "titleText": "#FFFFFF",
  "text": "#FFFFFF",
  "footerText": "#FFFF00",
  "firstPlaceBackground": "#ff715b",
  "secondPlaceBackground": "#ff5b42",
  "thirdPlaceBackground": "#ff2f0f"
}
```

## API Endpoints

### PNG Images
- `http://localhost:44310/pokedex.png`
- `http://localhost:44310/shiny.png`
- `http://localhost:44310/type_water.png`
- ... (one per scoreboard)

### JSON API
- `http://localhost:44310/api/pokedex.json`
- `http://localhost:44310/api/shiny.json`
- `http://localhost:44310/api/all.json` - All scoreboards

Example JSON response:
```json
{
  "scoreboard": "pokedex_completed",
  "title": "Pokédex Complété",
  "lastUpdate": "2025-02-14T12:00:00Z",
  "players": [
    {
      "rank": 1,
      "uuid": "...",
      "name": "PlayerName",
      "score": 906,
      "formattedScore": "906/1025 (88%)"
    }
  ]
}
```

### Web Interface
- `http://localhost:44310/` - Interactive dashboard with all scoreboards

## Discord Webhooks

Enable notifications when top 1 changes:

```json
"enableDiscordWebhook": true,
"discordWebhookUrl": "https://discord.com/api/webhooks/YOUR_WEBHOOK"
```

Notification format:
```
🏆 **Nouveau Champion - Pokédex Complété!**
PlayerName a pris la 1ère place avec 906 espèces capturées!
```

## Building from Source

### Prerequisites
- JDK 21
- Gradle 8.x

### Build

```bash
./gradlew build
```

Output JAR: `build/libs/cobblecheesescore-1.0.0.jar`

## Project Structure

```
CobbleCheeseScore/
├── src/main/java/com/cobblecheesescore/
│   ├── CobbleCheeseScore.java           # Main mod class
│   ├── config/
│   │   ├── Config.java                  # Config loader
│   │   └── ScoreboardConfig.java        # Scoreboard definition
│   ├── data/
│   │   ├── DataCollector.java           # Main data collector
│   │   ├── PokedexNBTParser.java        # NBT file parser
│   │   ├── CobblemonDataParser.java     # JSON parser
│   │   └── MinecraftStatsParser.java    # Stats parser
│   ├── scoreboard/
│   │   ├── ScoreboardManager.java       # Manages all scoreboards
│   │   ├── PlayerScore.java             # Player score data class
│   │   └── ScoreCalculator.java         # Score calculation logic
│   ├── image/
│   │   └── ImageGenerator.java          # PNG generation
│   ├── server/
│   │   ├── HttpServer.java              # HTTP server
│   │   ├── PngHandler.java              # PNG endpoint handler
│   │   ├── JsonApiHandler.java          # JSON API handler
│   │   └── WebInterfaceHandler.java     # HTML interface
│   ├── cache/
│   │   └── CacheManager.java            # Caching system
│   ├── webhook/
│   │   └── DiscordWebhook.java          # Discord integration
│   └── history/
│       └── HistoryTracker.java          # Historical data
└── src/main/resources/
    ├── fabric.mod.json
    ├── cobblecheesescore-default.json   # Default config
    └── web/
        └── index.html                    # Web interface template
```

## Performance

- **Cache**: Parsed data cached between updates
- **Update interval**: Configurable (default 10 minutes)
- **Image caching**: Generated PNG images cached in memory
- **30 players**: ~1-2 seconds for full update
- **Memory**: ~50-100MB for cache + images

## Troubleshooting

### Scoreboards show 0 for all players
- Check data folder detection: `/config/cobblecheesescore.json` → `autoDetectDataFolder: true`
- Manually set path: `"manualDataFolderPath": "/path/to/world"`

### Images not updating
- Check update interval: `updateIntervalMinutes`
- Check logs for errors
- Verify file permissions

### Discord webhooks not working
- Verify webhook URL is valid
- Check `enableDiscordWebhook: true`
- Check server has internet access

## License

MIT License - Free to use and modify

## Credits

Built for the Cacademy Cobblemon server.

## Support

For issues or feature requests, contact the server admin.
