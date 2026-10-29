[← CobbleCheeseScore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

## Prerequisites

| | Version | Notes |
|---|---|---|
| Minecraft | 1.21.1 | `"minecraft": "~1.21.1"` in `fabric.mod.json` |
| Fabric Loader | ≥ 0.16.0 | hard dependency |
| Java | 21 | `"java": ">=21"`, and the build targets release 21 |
| Fabric API | 0.103.0+1.21.1 | the build compiles against it; the mod uses `ServerLifecycleEvents` |
| [CheeseDash](../../CheeseDash/) | any | **hard dependency**: `"cheesedash": "*"` |
| [Cobblemon](https://modrinth.com/mod/cobblemon) | any | declared under `suggests`, so the mod loads without it. Without it there is no Pokédex data and most boards stay empty |
| [CobbleCap](../../CobbleCap/) | any | optional. Only needed for the four Conseil 4 boards |

### Server-side only

```json
"environment": "server"
```

The only entrypoint is `server`, implementing `DedicatedServerModInitializer`. Nothing
here runs on a client, and there is nothing to install on a player's machine. Two
consequences:

- putting the jar in a client's `mods/` does nothing, and does not break anything;
- `DedicatedServerModInitializer` is not called on a **single-player integrated
  server**. Opening a world in your own client will not start the mod, even with
  CheeseDash present. Test it on a real dedicated server.

If you want the leaderboard images visible in the world, each player's client needs a
third-party image-frame mod. That is not a dependency of this mod and it is not
installed by it — see [PNG export](png-export.md#getting-it-onto-a-map-in-game).

## Build order

The mod compiles against CheeseDash's jar, resolved by a glob rather than a fixed
filename:

```groovy
modImplementation files(fileTree(dir: "../CheeseDash/build/libs",
    includes: ["cheesedash-*.jar"], excludes: ["*-sources.jar", "*-dev.jar"]))
```

So CheeseDash has to be **built** first, not merely present. An empty
`CheeseDash/build/libs` gives you compile errors on `com.cheesedash.WebDashboard` and
`com.cheesedash.DashboardModule`.

```bash
cd CheeseDash && ./gradlew build
cd ../CobbleCheeseScore && ./gradlew build
```

`tools/build-all.sh` at the repository root walks every mod in dependency order and is
the easier route if you are building more than one.

The artefact is `build/libs/cobblecheesescore-1.0.0+1.21.1.jar`, from `mod_version` in
`gradle.properties`. A sources jar is produced alongside it; do not deploy that one.

> Until 29 October 2026 `fabric.mod.json` carried a hard-coded version string instead of
> `${version}`, so the jar name and the loaded mod disagreed about the version. That is
> fixed: `version` is `${version}`, `processResources` expands it, and the reported
> version matches the filename.

## Install

```bash
cp CheeseDash/build/libs/cheesedash-*.jar        /path/to/server/mods/
cp CobbleCheeseScore/build/libs/cobblecheesescore-1.0.0+1.21.1.jar /path/to/server/mods/
```

Remove any older `cobblecheesescore-*.jar` from `mods/` first. Fabric will refuse to
start with two copies of the same mod id.

## What the first start creates

In the server's `config/` directory:

| Path | When | Contents |
|---|---|---|
| `config/cobblecheesescore.json` | on `onInitializeServer`, if absent | the 30 shipped boards plus the 4 seeded Conseil 4 boards, and every global key |
| `config/cobblecheesescore_minigames.json` | on the first mini-game mutation | `{"games":{}}` and upwards |
| `config/cobblecheesescore_legends.json` | on the first recorded session | the permanent mini-game archive |

Nothing is created in the world folder, and nothing in the world folder is written to.
The mod only reads `pokedex/`, `cobblemonplayerdata/` and `stats/`.

The config is **re-saved on the first start even when it already exists**, if
any of the four Conseil 4 boards is missing from it. That is how an install from before
May 2026 gets them.

## Verifying it loaded

Three groups of log lines, in this order. The logger is `cobblecheesescore` for the lines
from the main class and `CobbleCheeseScore` for everything else, so your log formatter may
render the prefix two different ways.

During mod initialisation:

```text
[cobblecheesescore] Initializing CobbleCheeseScore...
[CobbleCheeseScore] Creating default configuration...
[CobbleCheeseScore] Seeded scoreboard: conseil4_first_finish
[CobbleCheeseScore] Seeded scoreboard: conseil4_fastest_time
[CobbleCheeseScore] Seeded scoreboard: conseil4_ranked_champions
[CobbleCheeseScore] Seeded scoreboard: conseil4_ranked_failures
[CobbleCheeseScore] Configuration saved to config/cobblecheesescore.json
[cobblecheesescore] CobbleCheeseScore initialized successfully!
```

On an existing install, `Creating default configuration...` is replaced by
`Configuration loaded from config/cobblecheesescore.json` and the `Seeded` lines are
absent.

From CheeseDash, proving the module was discovered through the `cheesedash` entrypoint:

```text
[CheeseDash] Public context registered: /scores/
```

When the world is up:

```text
[cobblecheesescore] Server started - Initializing scoreboards...
[CobbleCheeseScore] Auto-detected world path: /path/to/world
[cobblecheesescore] Running initial scoreboard update...
[CobbleCheeseScore] Collecting player data...
[CobbleCheeseScore] Collected data for 30 players
[CobbleCheeseScore] Updated scoreboard: pokedex_completed (25 players)
[CobbleCheeseScore] All scoreboards updated successfully
[cobblecheesescore] Initial update complete
[cobblecheesescore] Scoreboard update scheduler started (interval: 10 minutes)
```

On a brand-new world with no Cobblemon data yet you get a warning instead, and the start
is still successful:

```text
[CobbleCheeseScore] Pokedex folder not found: /path/to/world/pokedex
[CobbleCheeseScore] No player data found — continuing to render external-source boards (Conseil 4)
```

### Verifying from outside

There is no in-game command. Check it over HTTP.

```bash
TOKEN=$(curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"token":"<your master token>"}' | sed 's/.*"sessionId":"\([^"]*\)".*/\1/')

curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/stats
```

```json
{"totalScoreboards":34,"enabledScoreboards":34,"totalPlayersTracked":30,
 "lastUpdate":1761700000000,"updateIntervalMinutes":10}
```

`totalScoreboards: 34` and a non-zero `lastUpdate` mean the module is registered and a
refresh has completed. The public image route needs no token at all, which makes it the
quickest smoke test. Use a plain `GET` — the route answers nothing but `GET`, so a
`curl -I` (a `HEAD` request) gets a `404` even when the image exists:

```bash
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://localhost:44311/scores/api/images/test/pokedex_completed.png
```

```text
200 image/png
```

In the dashboard itself, the sidebar gains a **CobbleCheeseScore** section holding a
🏆 **Leaderboards** entry. If the entry
is missing, see
[troubleshooting](troubleshooting.md#the-dashboard-has-no-cobblecheesescore-section).

## Uninstalling

1. Stop the server.
2. Delete `mods/cobblecheesescore-*.jar`.
3. Start the server. Nothing else references the mod: CheeseDash discovers modules
   through the entrypoint list at startup, so the `scores` prefix and the `/scores/`
   public context simply stop being registered, and the dashboard's sidebar loses the
   entry.

What is left behind, all of it in `config/` and all of it safe to delete:

| File | Why you might keep it |
|---|---|
| `cobblecheesescore.json` | your board definitions and palette |
| `cobblecheesescore_minigames.json` | the rolling 500 sessions per mini-game |
| `cobblecheesescore_legends.json` | the permanent mini-game hall of fame. Nothing else holds this data |
| `cobblecheesescore_external_conseil4_*.json` | written by CobbleCap, not by this mod. Removing this mod does not stop CobbleCap writing them |

Nothing is left in the world folder, because nothing was ever written there. Rendered
images only ever existed in memory, so there are no stale PNGs to clean up.

⚠️ Any in-game image frame pointed at `http://host:44311/scores/api/images/…` will start
failing to load the moment the mod is gone. Remove or repoint those frames before you
uninstall, or players will be looking at blank frames.

---

← [CobbleCheeseScore](../README.md) · [Getting started](getting-started.md) →
