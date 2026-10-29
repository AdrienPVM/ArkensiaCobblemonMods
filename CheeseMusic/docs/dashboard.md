[← CheeseMusic](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

CheeseMusic registers a [CheeseDash](../../CheeseDash/) module, so it has no commands and
no in-game editor: the REST API below, or hand-editing
[`config/cheesemusic.json`](configuration.md) and restarting, are the only ways to manage
assignments.

The module is declared in `fabric.mod.json` under the `cheesedash` custom entrypoint:

```json
"entrypoints": { "cheesedash": ["com.cheesemusic.dashboard.CheeseMusicDashboardModule"] }
```

CheeseDash discovers it at start-up. With CheeseDash absent — it sits in `suggests`, not
`depends` — the entrypoint is never invoked, the module never loads, and the mod still
starts normally. Zone music keeps working off whatever is already in the config file; you
simply have no way to change it without a restart.

| | |
|---|---|
| **Prefix** | `music` |
| **Base path** | `/api/v1/music` |
| **Sidebar label** | CheeseMusic |
| **Sidebar icon** | 🎵 |
| **Sidebar description** | `Musique de zones` — hard-coded French in `CheeseMusicDashboardModule`, not translated |
| **WebSocket channels** | **none** — see [No WebSocket](#no-websocket) |

## Authentication

Every route needs a session token, as everywhere in CheeseDash. The login response field
is `sessionId`, and that value is what goes in the `Authorization` header:

```bash
TOKEN=$(curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"token":"<your master token>"}' | jq -r .sessionId)

curl -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/assignments
```

## Roles

`MusicApi` calls `WebDashboard.requireWrite(exchange)` on its six mutating routes and
nothing on its five read routes. `requireWrite` is `requireRole(exchange, OPERATOR)`,
which answers `403` and returns `false` when the session's role is below `OPERATOR`.

So: **reads are open to any authenticated session including `READ_ONLY`; writes need
`OPERATOR` or `SUPER_ADMIN`.** No route in this mod requires `SUPER_ADMIN`;
`requireAdmin` is never called.

Those calls are not, however, the boundary. CheeseDash's own module dispatch in
`DashboardApi` already gates every module:

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
```

Every non-`GET` request to `/api/v1/music/` therefore needs `OPERATOR` before `MusicApi`
is ever reached. `MusicApi`'s own six calls are defence in depth: useful, and redundant
in this configuration. Three consequences of where the real boundary sits:

- **No music route can require `SUPER_ADMIN` from the dispatch alone.** The core only
  knows `requireWrite`, so `OPERATOR` is the ceiling unless a handler calls
  `requireAdmin` itself. None here does.
- **A mutating `GET` would be guarded only by the module.** None exists today — the five
  read routes are genuinely read-only — but the core would not catch one.
- **A change to the dispatcher would not disarm this mod.** Unlike modules that call no
  guard at all, `MusicApi`'s six write routes would still check the role themselves.

> The module-level guards were repaired on 29 October 2026. They were previously written
> `WebDashboard.requireWrite(exchange);` as a bare statement, with the return value
> discarded — which sent the `403` and then carried on and applied the change anyway.
> That was a real defect in the module, but it was not an exploitable hole through
> CheeseDash: the core dispatch above had already rejected any non-`GET` request from a
> session below `OPERATOR`, so a `READ_ONLY` account never reached those handlers. The
> current form is `if (!WebDashboard.requireWrite(exchange)) return;` on all six write
> routes, so the module enforces the role on its own as well. If you are reading an older
> build of this mod, audit that jar rather than trusting this page.

## Module readiness

`CheeseMusicDashboardModule.handleRequest` answers `503` with
`{"error":"CheeseMusic not ready"}` before reaching `MusicApi` when the server reference,
the mod instance or the config is still null. That is the window between CheeseDash
accepting HTTP connections and CheeseMusic's `SERVER_STARTED` handler running. It is
short, and it is also what you get if CheeseMusic's own start-up threw.

---

## Assignments

### `GET /api/v1/music/assignments`

Every assignment, as a JSON array of assignment objects. Role: any.

```json
[
  {
    "zoneId": "volcano_cave",
    "zoneName": "Volcano Cave",
    "tracks": ["volcano_theme"],
    "mode": "SEQUENTIAL",
    "volume": 0.5,
    "fadeInTicks": 40,
    "fadeOutTicks": 80
  }
]
```

Note the response is built from the map's **values**, so the lookup key is not in the
output. An entry created by `POST` has its key equal to its `zoneId` (a tag assignment
carries `"zoneId": "tag:cave"`), so the response is enough in that case. Only an entry
whose `zoneId` was changed by `PUT` or by hand shows a value that is not its key. `[]`
when nothing is assigned.

### `POST /api/v1/music/assignments`

Create or replace an assignment. Role: **OPERATOR**.

Body: an assignment object, parsed by `MusicAssignment.fromJson`. Only `zoneId` is
mandatory; every other field falls back to the literal defaults listed in
[configuration](configuration.md#assignment-objects).

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"zoneId":"spawn_plaza","zoneName":"Spawn Plaza","tracks":["spawn_theme"],"mode":"RANDOM","volume":0.7,"fadeInTicks":60,"fadeOutTicks":60}' \
  http://localhost:44311/api/v1/music/assignments
```

The response is the stored assignment, echoed through `toJson()`. The map key is taken
from the body's `zoneId`, and `config.save()` rewrites the whole config file before the
response is sent. An existing entry under the same key is overwritten with no warning —
there is no "already exists" error.

| Status | Body | When |
|---|---|---|
| `200` | the assignment | stored and saved |
| `400` | `{"error":"Missing zoneId"}` | `zoneId` is present but empty or whitespace |
| `403` | CheeseDash's own | role below `OPERATOR` |
| `500` | `{"error":"Internal error"}` | `zoneId` **absent**, malformed JSON, or a non-object body |

That last row is a rough edge. A body with no `zoneId` at all makes
`obj.get("zoneId").getAsString()` throw an `NPE`, which the handler's outer `catch`
converts into a `500`. Only a present-but-blank `zoneId` reaches the intended `400`. The
cause is in the log either way:

```text
[CheeseMusic] API error: null
```

### `PUT /api/v1/music/assignments/{key}`

Replace an existing assignment. Role: **OPERATOR**.

| Parameter | Where | Notes |
|---|---|---|
| `key` | path | the lookup key. A `tag:` key works as is (`tag:cave`) or percent-encoded (`tag%3Acave`): the path is decoded by `getPath()` and once more by `URLDecoder`, so a literal `+` in a key turns into a space |

Body: a full assignment object. This is a replace, not a patch — omitted fields fall back
to their defaults rather than keeping their old values.

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"zoneId":"volcano_cave","zoneName":"Volcano Cave","tracks":["volcano_theme","volcano_calm"],"mode":"SHUFFLE","volume":0.45,"fadeInTicks":40,"fadeOutTicks":120}' \
  http://localhost:44311/api/v1/music/assignments/volcano_cave
```

| Status | Body | When |
|---|---|---|
| `200` | the updated assignment | replaced and saved |
| `403` | CheeseDash's own | role below `OPERATOR` |
| `404` | `{"error":"Assignment not found"}` | no entry under that key |
| `500` | `{"error":"Internal error"}` | body missing `zoneId`, or unparseable |

⚠️ The stored key is the **path** key, while the stored object's `zoneId` comes from the
**body**. Sending a body whose `zoneId` disagrees with the path leaves the two
permanently out of step. Lookups use the key, so music keeps working; the dashboard and
the `GET` response show the body's value. Keep them equal.

### `DELETE /api/v1/music/assignments/{key}`

Remove an assignment. Role: **OPERATOR**.

```bash
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/music/assignments/volcano_cave
```

```json
{"ok":true}
```

`404` with `{"error":"Assignment not found"}` if the key is unknown. The config file is
rewritten immediately. Players currently inside the region are not sent a `stop` here —
the tracker notices at its next check and sends a `stop` carrying `defaultFadeOutTicks`:
the track is cut and ReactiveMusic comes back over that duration.

---

## Tracks

These routes operate on `.ogg` files inside the server's resource pack directory. Read
[sound packs](sound-packs.md) first; a file on the server reaches no client by itself.

### `GET /api/v1/music/tracks`

The sanitised names `SoundScanner` found on its last scan. Role: any.

```json
["spawn_theme", "volcano_calm", "volcano_theme"]
```

Sorted alphabetically. `[]` if the scanner is null (it cannot be, once the module answers
at all) or the directory is empty. These are the exact strings an assignment's `tracks`
entries must use.

### `POST /api/v1/music/tracks/upload?name={filename}`

Upload one `.ogg`. Role: **OPERATOR**.

| Parameter | Where | Notes |
|---|---|---|
| `name` | query | the original filename, URL-encoded, **must end in `.ogg`** |
| — | body | the raw bytes of the file. Not multipart, not base64 |

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  --data-binary "@Volcano Theme.ogg" \
  "http://localhost:44311/api/v1/music/tracks/upload?name=Volcano%20Theme.ogg"
```

```json
{"ok":true,"track":"volcano_theme"}
```

`track` is the sanitised name, which is what you must put in an assignment. The upload
writes the file, then triggers a full rescan, which rewrites `sounds.json`. The server
logs:

```text
[CheeseMusic] Track uploaded: Volcano Theme.ogg -> volcano_theme
```

| Status | Body | When |
|---|---|---|
| `200` | `{"ok":true,"track":"..."}` | written and rescanned |
| `400` | `{"error":"Invalid filename. Must be a .ogg file."}` | `name` missing, not ending in `.ogg`, or containing `..`, `/` or `\` |
| `400` | `{"error":"Empty file"}` | zero-length body |
| `400` | `{"error":"File too large (max 50MB)"}` | body over 52 428 800 bytes |
| `403` | CheeseDash's own | role below `OPERATOR` |
| `503` | `{"error":"SoundScanner not ready"}` | the scanner is null |

The content is never inspected. Upload a JPEG renamed to `.ogg` and the server accepts
it, lists it as a track, and the client fails silently when asked to play it. The whole
body is read into memory before the size check, so a 4 GB upload is a 4 GB allocation on
the HTTP thread — do not expose port 44311 to the internet.

### `DELETE /api/v1/music/tracks/{name}`

Delete one `.ogg` and rescan. Role: **OPERATOR**.

`name` is the sanitised track name **without** the `.ogg` extension, URL-decoded.

```bash
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/music/tracks/volcano_theme
```

```json
{"ok":true}
```

| Status | Body | When |
|---|---|---|
| `200` | `{"ok":true}` | deleted, or the file was already absent — `deleteTrack` checks existence and stays quiet |
| `400` | `{"error":"Invalid track name"}` | empty, literally `upload`, or containing `..`, `/` or `\` |
| `403` | CheeseDash's own | role below `OPERATOR` |
| `503` | `{"error":"SoundScanner not ready"}` | the scanner is null |

The `upload` rejection exists because the dispatch checks `path.startsWith("/tracks/")`
before anything else, so `DELETE /api/v1/music/tracks/upload` would otherwise try to
delete a file called `upload.ogg`.

Deleting a track that an assignment still references leaves a dangling reference. Nothing
warns you, and the client that receives it logs one `warn` and plays nothing. Check
`GET /assignments` before deleting.

---

## Zones

### `GET /api/v1/music/zones`

Every CheeseGuard region, for populating a picker. Role: any.

```json
[
  {"id": "volcano_cave", "name": "Volcano Cave", "priority": 10, "tags": ["cave", "danger"]}
]
```

Read through `CheeseGuardHook.getAllZones()`, which goes `CheeseGuardMod.INSTANCE` →
`getRegionManager()` → `getStorage()` → `getRegions()` by reflection. With CheeseGuard
absent, or with the hook broken, the answer is `[]` and the status is still `200`. An
empty array therefore means either "no regions" or "no CheeseGuard" — the server log at
start-up is what tells you which:

```text
[CheeseMusic] CheeseGuard detected — zone music enabled
[CheeseMusic] CheeseGuard not installed — zone music disabled
```

`priority` and `tags` come straight from the region. `tags` is what a `tag:<tag>`
assignment key matches against.

---

## Config

### `GET /api/v1/music/config`

The five global keys. Role: any. Assignments are **not** included.

```json
{
  "enabled": true,
  "defaultFadeInTicks": 60,
  "defaultFadeOutTicks": 60,
  "checkIntervalTicks": 10,
  "defaultVolume": 0.7
}
```

### `PUT /api/v1/music/config`

Patch any subset of those five. Role: **OPERATOR**.

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true,"checkIntervalTicks":5}' \
  http://localhost:44311/api/v1/music/config
```

```json
{"ok":true}
```

Each key is applied only if present, so this is a genuine patch. Two setters clamp:
`checkIntervalTicks` to 1–100 and `defaultVolume` to 0.0–1.0. The fade keys are not
clamped and accept negatives, which the client then floors at 20 ticks anyway.

All five take effect on the next server tick — there is nothing to reload.
`defaultFadeInTicks` and `defaultVolume` are accepted, saved, and then
[never read by anything](configuration.md#keys-that-do-nothing-yet).

A `500` here means the body was not a JSON object, or a field held the wrong type
(`"enabled":"yes"` throws inside Gson's `getAsBoolean`).

---

## Status

### `GET /api/v1/music/status`

Who is hearing what, right now. Role: any.

```json
[
  {"playerUuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "zoneId": "volcano_cave", "playerName": "Notch"}
]
```

Built from `ZoneMusicTracker.getActiveZoneMusic()`, which includes a player only when
their `currentZoneId` is non-null **and** they are not flagged as in a Cobblemon battle.
A player whose music was suppressed by a battle disappears from this list and comes back
when the fight ends, which makes it a usable battle indicator as a side effect.

`playerName` is `?` when the UUID no longer resolves to an online player. That is a race
with disconnection, not a bug worth chasing.

This reflects **what the server told the client to play**, not what the client is actually
emitting. A client stuck in `FADING_RM_OUT` for want of ReactiveMusic appears here as
happily listening. When a player reports silence, this endpoint confirms the server did
its half and points you at [playback](playback.md#what-happens-without-reactivemusic).

---

## Unknown routes

Anything the dispatch does not match falls through to `404` with
`{"error":"Not found"}` — including a method the path does not support, such as
`POST /api/v1/music/config`. There is no `405`.

Every uncaught exception anywhere in `MusicApi.handle` becomes `500`
`{"error":"Internal error"}`, with the real message and a full stack trace in the server
log under `[CheeseMusic] API error:`.

## No WebSocket

CheeseMusic publishes **nothing** on any WebSocket channel. There is no call to
`DashboardRegistry.broadcast` or any other broadcast method anywhere in the mod, so
nothing arrives on port 44312 from it. A dashboard view that wants live state must poll
`GET /api/v1/music/status`.

Two things that would be natural to broadcast and are not: a player entering a region
with music, and a track upload finishing. Both would be small additions in
`ZoneMusicTracker.tickPlayer` and `MusicApi` respectively.

---

← [Configuration](configuration.md) · [Architecture](architecture.md) →
