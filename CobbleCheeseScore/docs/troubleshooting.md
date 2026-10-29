[← CobbleCheeseScore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

## The mod does not load at all

**Cause.** One of the hard dependencies is missing, or two copies of the jar are in
`mods/`. Fabric refuses to start rather than loading a mod whose `depends` block is
unsatisfied: `fabricloader >=0.16.0`, `minecraft ~1.21.1`, `java >=21` and
`cheesedash *`.

**Fix.** Read the loader's own error, which names the unsatisfied constraint. Then check:

```bash
ls /path/to/server/mods/ | grep -E 'cobblecheesescore|cheesedash'
java -version
```

Exactly one `cobblecheesescore-*.jar` and one `cheesedash-*.jar`, and Java 21 or later.
Deleting an older jar is the usual fix after an upgrade.

## Nothing happens in single player

**Cause.** The only entrypoint is `server`, implementing `DedicatedServerModInitializer`.
That is not called on an integrated server, so opening a world from your own client never
starts the mod — no config file is written, no module is registered, no log lines appear.

**Fix.** Test on a dedicated server. `"environment": "server"` also means the jar does
nothing useful in a client's `mods/` folder, so there is no client-side install to get
wrong.

## The dashboard has no CobbleCheeseScore section

**Cause.** CheeseDash discovers modules through `FabricLoader.getEntrypoints("cheesedash",
…)` once, at startup. If the mod loaded but the entrypoint was not picked up, or if
CheeseDash started first in a way that missed it, the `scores` prefix is never registered
and every `/api/v1/scores/*` request answers CheeseDash's own `404`.

**Fix.** Look for the one line that proves registration:

```text
[CheeseDash] Public context registered: /scores/
```

If it is absent while `CobbleCheeseScore initialized successfully!` is present, the mod
loaded and the entrypoint did not. Check that the jar in `mods/` is the one you built —
an old jar from before the dashboard module existed will initialise fine and register
nothing. Confirm with:

```bash
unzip -p /path/to/server/mods/cobblecheesescore-*.jar fabric.mod.json | grep -A2 cheesedash
```

You want `"cheesedash": ["com.cobblecheesescore.dashboard.CobbleCheeseScoreDashboardModule"]`.
Then restart the server — nothing registers a module after startup.

## Every board is empty and the log says `Pokedex folder not found`

**Cause.** `DataCollector` is looking in the wrong place, or the world genuinely has no
Cobblemon data. The world path is derived from
`server.getSaveProperties().getLevelName()` resolved against the run directory, which is
wrong when the folder on disk is named differently from the level name, or when the host
launches the jar from a parent directory.

**Fix.** The log prints the path it chose:

```text
[CobbleCheeseScore] Auto-detected world path: /home/container/world
[CobbleCheeseScore] Pokedex folder not found: /home/container/world/pokedex
```

Check where `pokedex/` actually is:

```bash
find /path/to/server -maxdepth 3 -type d -name pokedex
```

If the path differs, pin it in `config/cobblecheesescore.json`:

```json
"autoDetectDataFolder": false,
"manualDataFolderPath": "/home/container/world"
```

Restart. The path is resolved once in the `DataCollector` constructor, so an edit without
a restart changes nothing. A `manualDataFolderPath` that does not exist logs
`Manual path does not exist: <path>` and silently falls back to auto-detection.

If `find` turns up nothing, Cobblemon has never written a Pokédex file — no player has
encountered a Pokémon yet, or Cobblemon is not installed. The mod is not broken; it has
nothing to rank.

## Boards show fewer players than are on the server

**Cause.** Two filters, both deliberate.

A player with no `pokedex/<uuid>.nbt` file does not exist for this mod. The player list is
built by walking that folder, so a player with 200 hours and no Pokémon encounter is
invisible on every board, including `play_time`.

A player whose computed score is exactly 0 is dropped before sorting — on every board
except `survivor`. On a young server most type boards are nearly empty for that reason.

**Fix.** Nothing to fix. Check the distinction with:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/stats
```

`totalPlayersTracked` is how many players the collector found; a board's `playerCount` is
how many of them scored non-zero on it. A large gap between the two is normal.

## A specific player is missing from one board but present on others

**Cause.** The metric for that board reads a source file the player does not have, or a
counter Cobblemon has not written for them. The Cobblemon-derived boards all read
`advancementData.<counter>` out of `cobblemonplayerdata/<uuid>.json`, and a missing key
yields 0, which then gets filtered.

**Fix.** Look for the per-player warnings at DEBUG/WARN level:

```text
[CobbleCheeseScore] Cobblemon data file not found for 5e1b2c3d-…
[CobbleCheeseScore] Minecraft stats file not found for 5e1b2c3d-…
```

Then read the file by hand and check whether the counter exists:

```bash
python3 -c "import json;d=json.load(open('world/cobblemonplayerdata/4a/5e1b2c3d-….json'));print(d.get('advancementData',{}).keys())"
```

If the counter is absent, Cobblemon has not recorded that activity for that player and
there is nothing the mod can do.

## A config change has no effect

**Cause.** Almost nothing in this mod is hot-reloadable, and the running server's
in-memory `Config` is authoritative over the file.

**Fix.** Match your change against the reload table in
[configuration](configuration.md#reload-behaviour). The short version:

- a board's `title`, `description`, `enabled`, `maxPlayers`, `rowsPerColumn` or `colors`
  applied through `PUT /api/v1/scores/scoreboards/:id` takes effect at once;
- everything else takes a server restart;
- `sortOrder`, adding a board and removing a board can **only** be done in the file, then
  restart.

⚠️ Editing `config/cobblecheesescore.json` while the server is running is actively
dangerous: the next dashboard `PUT` serialises the in-memory config over your edit and it
is gone. Stop the server, edit, start it.

## `PUT /config` answered `{"saved":true}` and changed nothing

**Cause.** That is what the route does. `handleUpdateConfig` parses no global key at all;
its one branch, for `scoreboardId` plus `enabled`, looks the board up and **logs** the
request without applying it. Then it writes the unchanged in-memory config to disk and
reports success. The `{"saved":true}` is truthful about the file write and misleading
about your request.

**Fix.** Use the per-board route for board changes:

```bash
curl -X PUT http://localhost:44311/api/v1/scores/scoreboards/survivor \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"enabled":false}'
```

For global keys — `updateIntervalMinutes`, `timeZone`, `showLastUpdate`, `lastUpdateText`,
`defaultColors` — edit the file with the server stopped.

## Images never refresh on their own

**Cause.** `updateIntervalMinutes` is read once, at `SERVER_STARTED`, and passed to
`scheduleAtFixedRate`. Nothing reschedules it. (A value of `0` or a negative number does
not produce this symptom: the executor throws an uncaught `IllegalArgumentException` in
`SERVER_STARTED`, and the server aborts its start with a crash report.)

**Fix.** Look for the line that confirms the scheduler started, and the interval it
started with:

```text
[cobblecheesescore] Scoreboard update scheduler started (interval: 10 minutes)
```

If the server crashed at start with an `IllegalArgumentException` from
`scheduleAtFixedRate` in the crash report, fix `updateIntervalMinutes` to a positive
integer. If the line is present with the old value,
you changed the config without restarting. Meanwhile, force a pass by hand:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/refresh
```

## Every board fails at once with `Failed to update scoreboard`

**Cause.** The most likely culprit is `timeZone`. It is passed to `ZoneId.of(...)` inside
the renderer with no guard — whenever `showLastUpdate` is `true`, the default — so a typo
throws a `DateTimeException` for every board in turn, each one caught and logged
individually.

**Fix.**

```text
[CobbleCheeseScore] Failed to update scoreboard: pokedex_completed
java.time.zone.ZoneRulesException: Unknown time-zone ID: Europe/Pari
```

Set `timeZone` to a valid IANA id (`Europe/Paris`, `America/New_York`, `UTC`) and restart.
A font resolution failure on a badly configured JVM produces the same per-board pattern
with a different exception — read the stack trace rather than guessing.

## An image route returns JSON

**Cause.** Both image routes return `image/png` on success and `application/json` on
failure. A `404` body is `{"error":"No image for scoreboard: <id>"}`, a `503` body is
`{"error":"Cache not initialized"}`.

**Fix.** Read the status code. A `404` means one of three things:

- the board id is wrong — check `GET /api/v1/scores/scoreboards` for the exact ids and
  their `hasImage` flags;
- the board has been disabled since the server started, so no image was ever rendered
  (a board disabled mid-session keeps serving its last image until restart);
- no refresh has completed yet, which on a cold server means the cache is empty.

Force a refresh and retry. A `503` means the mod has not finished initialising — wait for
`All scoreboards updated successfully` in the log.

## The image is a tall thin strip

**Cause.** The column count is `ceil(players / rowsPerColumn)` **clamped to 3**. Past 3 ×
`rowsPerColumn` ranked players, columns get longer instead of more numerous. With
`maxPlayers: 100` and `rowsPerColumn: 10` you get three columns of 34 rows, which is 2 320
pixels tall and 1 880 wide.

**Fix.** Raise `rowsPerColumn` in step with `maxPlayers`, or lower `maxPlayers`. Thirty
players at `rowsPerColumn: 10` gives a 1 880 × 880 image, which is the shape the design
assumed. The arithmetic is in [PNG export](png-export.md#geometry).

## The fonts look wrong

**Cause.** Every `new Font(...)` call asks for the family `"Inter"`. A headless server
rarely has it installed, and AWT silently falls back to a default logical font with
different metrics — so centring drifts and names truncate at a different width.

**Fix.** Install Inter on the host and restart (`apt install fonts-inter` on
Debian-family distributions), or edit the six `new Font("Inter", …)` calls in
`src/main/java/com/cobblecheesescore/image/ImageGenerator.java` to a family the host
really has. There is no config key for it.

## A frame in-game shows nothing, or a placeholder

**Cause.** Four possibilities, in rough order of likelihood.

1. The URL uses `localhost` or a LAN address. The frame is fetched by each **player's**
   game, not by the server.
2. Port 44311 is not reachable from the players' side of the network.
3. The board id in the URL is wrong, or the board is disabled, so the route answers `404`
   with a JSON body that the frame cannot decode.
4. The image-frame mod is not installed on that player's client.

**Fix.** Test the exact URL from a machine on the players' network, not from the server:

```bash
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://your-server:44311/scores/api/images/test/pokedex_completed.png
```

`200 image/png` clears the server side and points at the client. Do not use `curl -I`:
the route answers only `GET`, so a `HEAD` request gets a `404` whatever the board. A
connection refused is a firewall or a port binding. A `404` is a board id problem — list
them with `GET /api/v1/scores/scoreboards`.

Remember `Cache-Control: public, max-age=60`: a client may show a minute-old image after
a forced refresh, which looks like a stale frame and is not.

## A command is refused

There are no commands. `grep -r CommandRegistrationCallback src/main/java` returns
nothing, and no `commands/` package exists. Every operation goes through the dashboard or
the REST API. If you were given a `/cobblecheesescore …` command by some other
documentation, it does not exist in this version.

## An OPERATOR changed something I wanted locked down

**Cause.** This is intended, and it is the ceiling. `ScoresApi` contains no call to
`WebDashboard.requireWrite` or `requireAdmin`, so the only role check is the one
CheeseDash's module dispatcher applies to every non-`GET` request: `OPERATOR` or above.
That covers `PUT /scoreboards/:id`, `POST /refresh`, `PUT /config` and all five mini-game
mutations — a `READ_ONLY` session is refused with `403` on each of them, but an `OPERATOR`
is allowed on all of them equally. There is no finer distinction inside the module.

**Fix.** Nothing here can require `SUPER_ADMIN` without a code change: the dispatcher knows
only the `OPERATOR` bar, and a route that needs more has to call
`WebDashboard.requireAdmin` itself. Until a route does, hand out `OPERATOR` only to people
you would trust with every write in this module. Full detail in
[dashboard](dashboard.md#required-role).

## The mini-game ranking is upside down

**Cause.** `POST /minigames/:id/sessions` stores the `rankings` array **exactly as
submitted** and awards 3, 2 and 1 points to positions 1, 2 and 3 of that array. The raw
`score` values are never used for ordering. Submit a race result sorted by ascending time
in a game where the fastest wins, and the slowest player gets the three points.

**Fix.** Resubmit, best first. A stored session cannot be reordered in place: delete it
with `DELETE /minigames/:id/sessions/:idx` using the `index` from `GET /minigames/:id`,
then post it again. Deleting shifts every higher index down by one, so re-fetch between
deletions.

## The Conseil 4 boards are empty

**Cause.** They read four JSON files that [CobbleCap](../../CobbleCap/) writes into
`config/`. Without CobbleCap, the files do not exist and the boards render as a header
with no rows. `Config.load()` seeds the four entries into every install regardless, which
is why they show up even when they cannot work.

**Fix.** Either install CobbleCap, or disable them:

```bash
for id in conseil4_first_finish conseil4_fastest_time \
          conseil4_ranked_champions conseil4_ranked_failures; do
  curl -s -X PUT "http://localhost:44311/api/v1/scores/scoreboards/$id" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"enabled":false}'
done
```

The seeder only re-adds a board whose id is **missing**, so a disabled entry survives
restarts. With CobbleCap installed and the boards still empty, check the file names
exactly — `cobblecheesescore_external_conseil4_first.json`, `…_fastest.json`,
`…_ranked_champions.json`, `…_ranked_failures.json` — and look for:

```text
[CobbleCheeseScore] Failed to load Conseil 4 board conseil4_fastest_time: <message>
```

A missing file logs at DEBUG and says nothing at the default level, so silence means
"file absent".

## Filing a useful bug report

Open it at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) with:

1. **The jar version.** `ls mods/ | grep cobblecheesescore`, and the CheeseDash jar
   alongside it.
2. **The startup block from the log.** Everything from
   `Initializing CobbleCheeseScore...` to
   `Scoreboard update scheduler started`, including the `Auto-detected world path` line
   and any warnings. That block alone answers most questions about where the mod is
   looking and what it found.
3. **The log for one failing refresh.** Grep it out:

   ```bash
   grep -n -i 'cobblecheesescore\|ScoresApi' logs/latest.log | tail -60
   ```

   If something threw, include the whole stack trace, not the first line.
4. **Your config, with the boards trimmed.** The global keys plus the one board that
   misbehaves is enough. Mention whether you edited the file while the server was
   running.
5. **For an API problem, the exact request and the full response.** `curl -i` rather than
   `curl -s`, so the status code and headers are in the paste. The status code matters
   more than the body on the image routes, where errors come back as JSON.
6. **For an in-game display problem, the output of `curl -s -o /dev/null -w '%{http_code}
   %{content_type}\n' <the exact frame URL>` run from a player's machine** (a `GET`: the
   route answers `HEAD` with a `404`), and the name and version of the image-frame mod. That one
   command separates a server problem from a client problem.

Do not open a public issue for a security problem — `SECURITY.md` at the repository root
explains where to send it. The absence of local role checks documented above is already
known, as is the fact that the core dispatcher covers it.

---

← [Architecture](architecture.md) · [CobbleCheeseScore](../README.md) →
