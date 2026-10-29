[← CobbleCheeseScore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

The mod registers a CheeseDash module under the prefix **`scores`**, through the
`cheesedash` entrypoint in `fabric.mod.json`:

```json
"entrypoints": {
  "cheesedash": ["com.cobblecheesescore.dashboard.CobbleCheeseScoreDashboardModule"]
}
```

Authenticated routes live under `/api/v1/scores/`. The module also declares a public
prefix, also `scores`, which gives it one unauthenticated route under `/scores/api/`.

| | |
|---|---|
| Display name | CobbleCheeseScore |
| Nav icon | 🏆 |
| Nav description | Classements et scoreboards |
| HTTP port | 44311 (CheeseDash) |

---

## Required role

**Every authenticated route in this module requires only a valid session.** `ScoresApi`
contains no call to `WebDashboard.requireWrite` or `WebDashboard.requireAdmin` — grep it
and you get nothing. The only gate is CheeseDash's `/api/` middleware, which rejects a
request with no valid `Authorization: Bearer <token>` with `401` and otherwise lets it
through whatever the session's role is.

⚠️ That means a **READ_ONLY** account can create a mini-game, delete a session, rewrite a
board's title and colours, and force a refresh. If that matters to you, do not hand out
READ_ONLY accounts expecting them to be read-only across this module.

For reference, the correct form in this repository is:

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

`requireWrite` returns a boolean and sends the `403` itself; ignoring the return value
sends the 403 **and then continues to execute the handler**, which is worse than not
calling it. This module does neither — it does not call it at all. The role column below
reports what the code actually enforces, not what the route arguably should.

| Role | What it can do here |
|---|---|
| READ_ONLY | everything |
| OPERATOR | everything |
| SUPER_ADMIN | everything |

---

## Scoreboard routes

### `GET /api/v1/scores/scoreboards`

Every configured board, enabled or not, in config order. Role: any.

```json
[
  {
    "id": "pokedex_completed",
    "title": "Pokédex Complété",
    "description": "Espèces capturées",
    "enabled": true,
    "maxPlayers": 30,
    "sortOrder": "desc",
    "playerCount": 25,
    "topPlayer": { "name": "RoiCheese", "score": "906/1025 (88%)" },
    "hasImage": true
  }
]
```

`topPlayer` is omitted entirely when the ranking is empty. `playerCount` is the length of
the computed ranking, which excludes players whose score is zero.

| Status | Condition |
|---|---|
| `200` | always, even with every board empty |
| `503` | `ScoreboardManager` or `Config` is null — the mod has not finished starting |

### `GET /api/v1/scores/scoreboards/:id`

One board with its full ranking. Role: any.

| Parameter | In | Type | Notes |
|---|---|---|---|
| `id` | path | string | a board id from the config. Matched against `[^/]+`, so it cannot contain a slash |

```json
{
  "id": "shiny_hunter",
  "title": "Chasseur de Shiny",
  "description": "Shinies capturés",
  "enabled": true,
  "maxPlayers": 30,
  "sortOrder": "desc",
  "rankings": [
    {
      "rank": 1,
      "uuid": "5e1b2c3d-7a8f-4b6c-9d0e-1f2a3b4c5d6e",
      "name": "RoiCheese",
      "score": 69.0,
      "formattedScore": "69"
    }
  ],
  "colors": { "titleBackground": "#FFD700" },
  "hasImage": true,
  "lastUpdate": 1761700000000
}
```

`score` is the raw double, `formattedScore` the string the image shows. `lastUpdate` is
the epoch-millisecond timestamp of the last full refresh, shared by all boards; `0` means
no refresh has completed.

| Status | Condition |
|---|---|
| `200` | found |
| `404` | `{"error":"Scoreboard not found: <id>"}` |
| `503` | not initialised |

### `PUT /api/v1/scores/scoreboards/:id`

Edit a board's presentation. Role: **any** — see the warning above. Body is a partial
object; only the keys present are applied.

| Body key | Type | Effect |
|---|---|---|
| `title` | string | replaces `title` |
| `description` | string | replaces `description` |
| `enabled` | boolean | enables or disables the board |
| `maxPlayers` | integer | replaces `maxPlayers` |
| `rowsPerColumn` | integer | replaces `rowsPerColumn` |
| `colors` | object | merged into the existing map; a `null` or `""` value **removes** that key |

`sortOrder` and `id` cannot be changed through this route even though
`ScoreboardConfig` has setters for both. Edit the config file and restart for those.

```bash
curl -X PUT http://localhost:44311/api/v1/scores/scoreboards/shiny_hunter \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Shiny Hunters","colors":{"titleBackground":"#FFD700","text":""}}'
```

```json
{"updated":true}
```

Side effects, in order: the whole config is written back to
`config/cobblecheesescore.json`, then `ScoreboardManager.updateAll()` runs, which
recollects every player's data and re-renders **every** board. The call does not return
until all of that finishes, so expect it to take as long as a full refresh.

| Status | Condition |
|---|---|
| `200` | `{"updated":true}` |
| `404` | no board with that id |
| `500` | `{"error":"Failed to save config"}` — the config file could not be written. Edits are already in memory at that point and will be lost on restart |
| `503` | config not loaded |

A refresh that throws after a successful save is logged as a warning and still answers
`200`: the config change stuck, the images did not.

### `GET /api/v1/scores/scoreboards/:id/image`

**This route returns an image, not JSON.** Role: any.

```text
GET /api/v1/scores/scoreboards/:id/image
GET /api/v1/scores/scoreboards/:id/image/:filename.png
```

The optional trailing segment is matched and discarded; it exists so a browser download
gets a useful filename. Both forms return identical bytes.

| | |
|---|---|
| Success `Content-Type` | `image/png` |
| Success headers | `Cache-Control: public, max-age=60` |
| Body | the PNG encoded from the cached `BufferedImage` by `ImageIO` |

```bash
curl -H "Authorization: Bearer $TOKEN" \
  -o pokedex.png \
  http://localhost:44311/api/v1/scores/scoreboards/pokedex_completed/image
```

| Status | Content type | Condition |
|---|---|---|
| `200` | `image/png` | an image is cached for that id |
| `404` | `application/json` | `{"error":"No image for scoreboard: <id>"}` — board disabled, unknown id, or no refresh yet |
| `503` | `application/json` | `{"error":"Cache not initialized"}` |

⚠️ The error responses are JSON while the success response is a PNG. Check the status
code, not the content type, and never pipe the body straight into an image decoder.

### `POST /api/v1/scores/refresh`

Force a full recollection and re-render. Role: **any** — see the warning above. No body.

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/refresh
```

```json
{"refreshed":true,"lastUpdate":1761700000000}
```

Runs synchronously on the calling HTTP thread. It reads NBT and JSON files and touches no
Minecraft state, so it does not hop to the server thread — but it does mean the HTTP
request is held open for the whole pass, and two concurrent refreshes race. See
[architecture](architecture.md#threading).

| Status | Condition |
|---|---|
| `200` | done |
| `500` | `{"error":"Refresh failed: <message>"}` |
| `503` | `ScoreboardManager` not initialised |

---

## Config routes

### `GET /api/v1/scores/config`

Role: any. Returns a curated subset of the global config plus every board's settings.

```json
{
  "updateIntervalMinutes": 10,
  "timeZone": "Europe/Paris",
  "defaultMaxPlayers": 30,
  "defaultRowsPerColumn": 10,
  "showLastUpdate": true,
  "enableHistory": true,
  "historyRetentionDays": 30,
  "defaultColors": { "background": "#0f0c29", "titleBackground": "#ffcb05" },
  "scoreboards": [
    {
      "id": "pokedex_completed",
      "enabled": true,
      "title": "Pokédex Complété",
      "description": "Espèces capturées",
      "maxPlayers": 30,
      "rowsPerColumn": 10,
      "sortOrder": "desc",
      "colors": {}
    }
  ]
}
```

`enableHistory`, `historyRetentionDays`, `defaultMaxPlayers` and `defaultRowsPerColumn`
are reported here and nowhere else — no code acts on them. `port`,
`enableWebInterface`, `enableJsonApi`, `enableDiscordWebhook`, `discordWebhookUrl`,
`autoDetectDataFolder`, `manualDataFolderPath` and `lastUpdateText` are not reported at
all, though the last three do have an effect. See
[configuration](configuration.md#keys-nothing-reads).

| Status | Condition |
|---|---|
| `200` | always |
| `503` | config not loaded |

### `PUT /api/v1/scores/config`

Role: **any** — see the warning above.

⚠️ **This route changes almost nothing.** Read `handleUpdateConfig` and it does exactly
two things: if the body has both `scoreboardId` and `enabled`, it looks the board up and
**logs** the requested change without applying it; then it writes the in-memory config
back to disk and answers `{"saved":true}`. No global key is parsed. A stale comment in
the source still claims `ScoreboardConfig` needs a setter — `setEnabled` has existed for
a while, it is simply not called here.

So:

- to enable or disable a board, use `PUT /scoreboards/:id` with `{"enabled": false}`;
- to change `updateIntervalMinutes`, `timeZone`, `showLastUpdate` or `defaultColors`,
  edit `config/cobblecheesescore.json` and restart the server.

```bash
curl -X PUT http://localhost:44311/api/v1/scores/config \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"scoreboardId":"survivor","enabled":false}'
```

```json
{"saved":true}
```

That `{"saved":true}` is honest about what happened — the file was saved — and misleading
about what you asked for. `survivor` is still enabled.

| Status | Condition |
|---|---|
| `200` | `{"saved":true}` |
| `404` | `{"error":"Scoreboard not found"}` when `scoreboardId` names nothing |
| `500` | `{"error":"Failed to save config"}` |
| `503` | config not loaded |

### `GET /api/v1/scores/stats`

Role: any. Summary counters for a dashboard tile.

```json
{
  "totalScoreboards": 34,
  "enabledScoreboards": 34,
  "totalPlayersTracked": 30,
  "lastUpdate": 1761700000000,
  "updateIntervalMinutes": 10
}
```

`totalPlayersTracked` is the size of the cached `PlayerData` map, which is only replaced
when a collection pass found at least one player. After a pass that found none, it keeps
reporting the previous number. When the config is null the route answers `200` with every
counter at `0` and no `updateIntervalMinutes` key.

---

## Mini-game routes

The model and the point-scoring rule are described in [mini-games](minigames.md). All of
these need only a valid session.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/minigames` | every game with its session count and top player |
| `POST` | `/minigames` | create a game |
| `GET` | `/minigames/global` | aggregate ranking across all games |
| `GET` | `/minigames/:id` | one game, its sessions newest-first, and its ranking |
| `PUT` | `/minigames/:id` | rename a game |
| `DELETE` | `/minigames/:id` | delete a game and all its sessions |
| `POST` | `/minigames/:id/sessions` | record a session result |
| `DELETE` | `/minigames/:id/sessions/:idx` | delete one session by index |

`/minigames/global` is matched before `/minigames/:id`, so `global` is a reserved id: a
game created with that id can be listed but never fetched individually.

### `GET /minigames`

```json
[
  {
    "id": "chasse_au_tresor",
    "displayName": "Chasse au trésor",
    "sessionCount": 12,
    "playerCount": 8,
    "topPlayer": { "name": "RoiCheese", "score": "21 pts" }
  }
]
```

`topPlayer` is omitted when no session has ever been recorded. `503` if the manager is
null.

### `POST /minigames`

| Body key | Type | Required |
|---|---|---|
| `id` | string | yes, non-empty after trimming |
| `displayName` | string | yes, non-empty after trimming |

```bash
curl -X POST http://localhost:44311/api/v1/scores/minigames \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"id":"chasse_au_tresor","displayName":"Chasse au trésor"}'
```

```json
{"created":true,"id":"chasse_au_tresor"}
```

Writes `config/cobblecheesescore_minigames.json` immediately.

| Status | Condition |
|---|---|
| `200` | created |
| `400` | `{"error":"id and displayName required"}` |
| `409` | `{"error":"Game already exists: <id>"}` |
| `503` | manager null |

Nothing validates the id beyond non-emptiness. A slash or a space in it will make
`/minigames/:id` unreachable, because the dispatcher matches `[^/]+` against a raw path.

### `GET /minigames/:id`

```json
{
  "id": "chasse_au_tresor",
  "displayName": "Chasse au trésor",
  "sessions": [
    {
      "index": 11,
      "date": 1761700000000,
      "rankings": [
        { "uuid": "5e1b2c3d-…", "name": "RoiCheese", "score": 420.0 }
      ]
    }
  ],
  "ranking": [
    {
      "rank": 1,
      "uuid": "5e1b2c3d-…",
      "name": "RoiCheese",
      "score": 21.0,
      "formattedScore": "21 pts"
    }
  ]
}
```

Sessions come back newest first, and each carries its `index` **in the stored
chronological list** — that is the number `DELETE /minigames/:id/sessions/:idx` wants.
The indices are not contiguous in the response order; read them off the objects rather
than counting positions.

`404` when the id is unknown, `503` when the manager is null.

### `PUT /minigames/:id`

| Body key | Type | Effect |
|---|---|---|
| `displayName` | string | renamed, trimmed. Any other key is ignored |

`200` `{"updated":true}`, or `404` `{"error":"Game not found"}` — the same `404` is
returned when the manager itself is null.

### `DELETE /minigames/:id`

`200` `{"deleted":true}`, `404` `{"error":"Game not found: <id>"}`, `503` manager null.
The game's sessions go with it from `cobblecheesescore_minigames.json`. Its history
survives in `cobblecheesescore_legends.json`, which nothing deletes from and no route
exposes.

### `POST /minigames/:id/sessions`

| Body key | Type | Required |
|---|---|---|
| `rankings` | array | yes, must be a JSON array |

Each element: `uuid` (string, defaults to `""`), `name` (string, defaults to `""`),
`score` (number, defaults to `0`). **The array must already be sorted best-first** —
nothing sorts or validates it, and the points go to positions 1, 2 and 3 of the array as
submitted.

```bash
curl -X POST http://localhost:44311/api/v1/scores/minigames/chasse_au_tresor/sessions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"rankings":[
        {"uuid":"5e1b2c3d-7a8f-4b6c-9d0e-1f2a3b4c5d6e","name":"RoiCheese","score":420},
        {"uuid":"0b1c2d3e-…","name":"Alex","score":310}
      ]}'
```

```json
{"added":true,"sessionCount":13}
```

Side effects: appends the session, trims the game to the newest 500 sessions, writes
`cobblecheesescore_minigames.json`, then appends a permanent record to
`cobblecheesescore_legends.json`. A failure in that last step is logged and swallowed —
the response is still `200`.

| Status | Condition |
|---|---|
| `200` | recorded |
| `400` | `{"error":"rankings array required"}` |
| `404` | `{"error":"Game not found: <id>"}` |
| `503` | manager null |

An empty `rankings` array is accepted: the session is stored with no entries, and the
legends archive skips it.

### `DELETE /minigames/:id/sessions/:idx`

`idx` must match `\d+` or the route falls through to the module's `404`. Out-of-range and
unknown-game both answer `404` `{"error":"Session not found"}`.

⚠️ Deleting a session renumbers every index above it, because the list is an
`ArrayList` and the index is its position. Re-fetch `GET /minigames/:id` between
deletions or you will delete the wrong one.

### `GET /minigames/global`

```json
{
  "ranking": [
    { "rank": 1, "uuid": "5e1b2c3d-…", "name": "RoiCheese",
      "score": 48.0, "formattedScore": "48 pts" }
  ],
  "totalGames": 3
}
```

---

## Public route, no authentication

```text
GET /scores/api/images/<name>/<id>.png
```

Registered from `getPublicPrefix()` returning `scores`, served outside the `/api/` auth
middleware, with CheeseDash's public security headers. `<name>` is filler for the
downloaded filename; `<id>` is the board id, URL-decoded. Same bytes, same status codes
and same JSON error bodies as the authenticated image route.

This is the route to point an in-game image frame at. Full procedure in
[PNG export](png-export.md#getting-it-onto-a-map-in-game).

Anything else under `/scores/api/` answers `404` `{"error":"Not found"}`. The public
prefix also makes CheeseDash serve classpath resources from `/scores/`, but the mod ships
none, so `http://host:44311/scores/` is a `404`.

---

## WebSocket

**This module publishes nothing over WebSocket.** There is no call to
`DashboardRegistry.broadcast(...)` anywhere in the mod. A dashboard view showing
leaderboard data has to poll `GET /scoreboards` or `GET /stats`; nothing will push a
refresh notification at it.

---

## Unhandled routes and errors

Anything the dispatcher does not match answers `404` `{"error":"Route not found"}`. Any
exception that escapes a handler is caught at the top of `ScoresApi.handle`, logged as
`[ScoresApi] Error handling <subRoute>: <message>`, and returned as:

```json
{"error":"Internal server error","detail":"<exception message>"}
```

A malformed JSON body reaches that catch-all, so a body typo comes back as a `500` rather
than a `400`.

---

← [Mini-games](minigames.md) · [Architecture](architecture.md) →
