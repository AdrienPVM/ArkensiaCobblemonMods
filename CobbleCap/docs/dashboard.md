[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

CobbleCap registers a CheeseDash module through the `cheesedash` entrypoint declared in
`fabric.mod.json`:

```json
"entrypoints": {
  "cheesedash": ["com.cobblecap.dashboard.CobbleCapDashboardModule"]
}
```

The module's prefix is `cobblecap`, so every route below hangs off `/api/v1/cobblecap`.
In the sidebar it shows as **CobbleCap**, icon 🏅, described as *Level cap progression*.

CheeseDash serves HTTP on port **44311**. Authentication is session-based: `POST
/api/v1/auth/login` returns a token you then pass as `Authorization: Bearer <token>` on
every other request. A request with no valid session never reaches CobbleCap's handler.

## Module readiness

`CobbleCapDashboardModule.handleRequest` refuses to dispatch until both the
`MinecraftServer` reference (set in `onServerStart`) and `CobbleCapMod.INSTANCE` are
non-null:

```json
{"error":"CobbleCap not ready"}
```

That is a **503**. It means the dashboard is up but the Minecraft server has not finished
starting, or CobbleCap's initializer did not run.

## Roles

CheeseDash accounts carry one of three roles: `READ_ONLY`, `OPERATOR`, `SUPER_ADMIN`.

**The role boundary for this module is held by the core, not by CobbleCap.** CheeseDash's
module dispatcher, in `DashboardApi`, checks the method and the role before it hands a
request to any module:

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
    ...
}
```

That is the correct form — it tests the return value and returns — so every non-`GET`
request to any route under `/api/v1/cobblecap/` requires `OPERATOR` or above, and every
`GET` is open to any role including `READ_ONLY`. Nothing a module does or omits changes
that.

CobbleCap also calls `WebDashboard.requireWrite(exchange)` itself in `handleSetCap`,
`handleToggleExpLock`, `handleChampions` (the `PUT` branch), `handleConfig` (the `PUT`
branch) and the `/fireworks/test` dispatch. Those calls are **defence in depth** — useful,
but redundant with the check that already ran. They are not the boundary, and a write route
that lacked one would be gated at `OPERATOR` just the same.

What the core's design does cost, here as in every module:

- **No route in CobbleCap can require `SUPER_ADMIN`.** `requireAdmin` is never called, and
  the dispatcher only knows the `OPERATOR` bar, so rewriting the whole champion list sits at
  the same level as toggling one player's XP lock. A route that deserves more has to call
  `WebDashboard.requireAdmin` itself.
- **A mutating route exposed as a `GET` would not be guarded at all.** None exists today —
  every write here is a `POST` or a `PUT` — but the dispatcher keys purely on the HTTP
  method, so nothing would catch one.
- **A single change to that dispatcher would drop the checks for every module at once.**
  CobbleCap's own `requireWrite` calls are the reason it would survive that, which is
  exactly what defence in depth is for.

Historical note: until **29 October 2026** four of CobbleCap's own guards were written
`WebDashboard.requireWrite(exchange);` as a bare statement, discarding the boolean return
value, so the 403 was sent *and the handler carried on anyway*. On those builds the module
contributed nothing and `OPERATOR` was enforced by the dispatcher alone. They now read
`if (!WebDashboard.requireWrite(exchange)) return;`. The fifth call site,
`/fireworks/test`, was always a guarded `else if` and was never affected.

---

## `GET /api/v1/cobblecap/players`

Every player CobbleCap has ever recorded, as a flat array. Role: any.

```json
[
  {
    "uuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1",
    "playerName": "Steve",
    "capLevel": 45,
    "championsBeatenCount": 4,
    "lastCapChange": 1761700000000,
    "expLocked": false
  }
]
```

`lastCapChange` is epoch milliseconds. `championsBeatenCount` is a count, not the list —
fetch the detail route for the ids.

## `GET /api/v1/cobblecap/players/{uuid}`

One player, with the fields the list route omits. Role: any.

| Parameter | Where | Notes |
|---|---|---|
| `uuid` | path | canonical dashed UUID; anything `UUID.fromString` rejects gives a 400 |

```json
{
  "uuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1",
  "playerName": "Steve",
  "capLevel": 45,
  "championsBeaten": ["gym_leader_pierre_0395", "gym_leader_ondine_0396"],
  "lastCapChange": 1761700000000,
  "hudVisible": true,
  "hudPosition": 0,
  "expLocked": false
}
```

| Status | Body |
|---|---|
| 200 | the object above |
| 400 | `{"error":"Invalid UUID"}` |
| 404 | `{"error":"Player not found"}` |

`challengeBeaten` and `lastChallengeTimes` are stored on disk but **not**
exposed by this route. Read `config/cobblecap/players.json` if you need them.

## `POST /api/v1/cobblecap/players/{uuid}/cap`

Set a player's cap outright. Role: **OPERATOR**.

```json
{"capLevel": 60}
```

| Field | Type | Required | Accepted |
|---|---|---|---|
| `capLevel` | integer | yes | 1–100 inclusive |

The mutation runs through `WebDashboard.executeOnServerThread`, and if the player is
online a `CapSyncPayload` is pushed to their client straight after, so the HUD updates
without a relog.

```json
{"updated": true, "capLevel": 60}
```

| Status | Body | Cause |
|---|---|---|
| 400 | `{"error":"Missing capLevel"}` | body absent, unparseable, or without the key |
| 400 | `{"error":"capLevel must be between 1 and 100"}` | out of range |
| 400 | `{"error":"Invalid UUID"}` | malformed path UUID |
| 403 | CheeseDash's own role refusal | `READ_ONLY` session |
| 500 | `{"error":"Failed to set cap level"}` | the server-thread dispatch threw |

This writes through `CapStorage.setCapLevel`, which persists. It does **not** touch
`championsBeaten`, so the champion-ordering gate still sees the old progress. A later
victory goes through `CapStorage.raiseCapLevel`, which only ever raises: a cap set above
that champion's `capGranted` survives it, a cap set below is lifted to `capGranted`. To make
the progress match the number, record it too with
`/cobblecap addbeaten <player> "<championId>"`.

The route does not create a missing row: for a UUID CobbleCap has never seen,
`setCapLevel` does nothing and the answer is still `200 {"updated": true, …}`.

## `POST /api/v1/cobblecap/players/{uuid}/explock`

Toggle the player's XP lock. Role: **OPERATOR**. No request body — the route flips the
current value.

```json
{"uuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1", "expLocked": true}
```

| Status | Body |
|---|---|
| 200 | the object above |
| 400 | `{"error":"Invalid UUID"}` |
| 404 | `{"error":"Player not found"}` |
| 500 | `{"error":"Failed to toggle explock"}` |

The lock is checked before the cap in `CapManager.handleXpEvent`, and unlike the cap it
applies even when `enabled` is `false`. See [the level cap](level-cap.md#the-xp-lock-is-a-separate-switch).

## `GET /api/v1/cobblecap/champions`

The ordered champion list, exactly as `ChampionEntry.toJson()` serialises it. Role: any.

```json
[
  {
    "id": "gym_leader_pierre_0395",
    "displayName": "Pierre",
    "capGranted": 30,
    "requiredPokedex": 0,
    "requiredQuestId": "",
    "victoryQuestId": "1A2B3C4D5E6F7081",
    "challengeId": "gym_leader_pierre_challenge",
    "challengeQuestId": "",
    "challengeCooldownHours": 24,
    "allowRefight": true
  }
]
```

Three fields are written conditionally and are simply **absent** when unset, rather than
present-and-zero: `requiredScoreboard`/`requiredScoreMin`/`challengeRequiredScoreMin`
appear only when `requiredScoreboard` is non-empty, `challengeRequiresAllBeaten` only when
it is `false`, and `challengeCapGranted` only when it is greater than zero. A client that
reads these must supply the defaults itself (`""`, `0`, `true`, `0`).

## `PUT /api/v1/cobblecap/champions`

Replace the **whole** list. Role: **OPERATOR**. The body is a bare JSON array of champion
objects, not an object wrapping one.

```json
[
  {"id": "gym_leader_pierre_0395", "displayName": "Pierre", "capGranted": 30},
  {"id": "gym_leader_ondine_0396", "displayName": "Ondine", "capGranted": 35}
]
```

Every field except `id` and `displayName` falls back to a default in
`ChampionEntry.fromJson` (`capGranted` 20, `allowRefight` true,
`challengeRequiresAllBeaten` true, the rest `""` or `0`), so a short object is legal and
silently filled in.

```json
{"saved": true, "count": 2}
```

| Status | Body |
|---|---|
| 200 | the object above |
| 400 | `{"error":"Expected JSON array"}` |
| 403 | role refusal |

This is a destructive overwrite: it calls `ChampionConfig.setChampions`, which replaces
the in-memory list and immediately rewrites `config/cobblecap/champions.json`. There is no
merge, no partial update and no undo. **`PUT` the result of a `GET` with your edits
applied**, never a fragment. Order matters — the first entry a player has not beaten is
their current gate.

## `GET /api/v1/cobblecap/config`

Role: any. The whole of `CapConfig`, plus one computed field.

```json
{
  "enabled": true,
  "startingCap": 20,
  "discordWebhookUrl": "",
  "bannedSpecies": ["mewtwo", "arceus"],
  "fireworksEnabled": false,
  "fireworksZoneId": "",
  "fireworksCount": 12,
  "fireworksIntervalMs": 800,
  "fireworksDurationSec": 12,
  "availableZones": ["league_arena", "spawn"]
}
```

`availableZones` is not stored anywhere. It comes from
`FireworksManager.listAvailableZones()`, which reaches into CheeseGuard reflectively and
returns an empty array when CheeseGuard is not installed. It exists so the dashboard can
render a zone dropdown.

## `PUT /api/v1/cobblecap/config`

Role: **OPERATOR**. Partial update — only the keys present in the body are applied, each
one through its `CapConfig` setter, and each setter calls `save()`, so one `PUT` with five
keys rewrites `config.json` five times.

| Key | Type | Clamping applied by the setter |
|---|---|---|
| `enabled` | boolean | none |
| `discordWebhookUrl` | string | `null` becomes `""`; also pushed into `CobbleCapWebhook` live |
| `bannedSpecies` | array of strings | lower-cased |
| `fireworksEnabled` | boolean | none |
| `fireworksZoneId` | string | `null` becomes `""` |
| `fireworksCount` | integer | `max(1, v)` |
| `fireworksIntervalMs` | integer | `max(50, v)` |
| `fireworksDurationSec` | integer | `max(1, v)` |

`startingCap` is **read-only over the API**. The `GET` returns it, the `PUT` ignores it.
To change it, edit `config/cobblecap/config.json` and run `/cobblecap reload` (or restart); it then applies to rows created afterwards.

```json
{"updated": true}
```

A body that is not a JSON object gives `400 {"error":"Invalid JSON body"}`. Note the
asymmetry with the clamping: send `fireworksIntervalMs: 10` and you get `200
{"updated":true}` while the stored value becomes `50`. Re-`GET` if you need to know what
was actually kept.

## `POST /api/v1/cobblecap/fireworks/test`

Role: **OPERATOR**. Fires the fireworks show right now, using whatever is currently saved
in the config, and deliberately **ignores `fireworksEnabled`** so you can aim the show
before switching it on for real crownings.

No body. The only precondition is a non-blank `fireworksZoneId`.

```json
{
  "started": true,
  "zoneId": "league_arena",
  "count": 12,
  "intervalMs": 800,
  "durationSec": 12
}
```

| Status | Body | Cause |
|---|---|---|
| 200 | the object above | dispatched to the server thread |
| 400 | `{"error":"No zone configured — set fireworksZoneId first"}` | `fireworksZoneId` empty |
| 405 | `{"error":"Method not allowed"}` | anything but `POST` |
| 500 | `{"error":"Failed to dispatch on server thread"}` | `executeOnServerThread` threw |

`started: true` means the task was handed to the server thread, not that rockets went up.
If the zone id does not resolve in CheeseGuard the show simply does not happen and the
reason is in the server log, not in this response.

## `GET /api/v1/cobblecap/stats`

Role: any. A histogram of caps across every recorded player, sorted ascending by cap
(`TreeMap`).

```json
{
  "tiers": [
    {"cap": 20, "count": 31},
    {"cap": 30, "count": 12},
    {"cap": 100, "count": 2}
  ],
  "totalPlayers": 45
}
```

## Unknown routes

Anything the dispatcher does not recognise returns `404 {"error":"Not found"}`. Any
exception thrown inside a handler is caught at the top of `CobbleCapApi.handle`, logged as
`[CobbleCap API] Error handling <route>: <message>`, and answered with `500
{"error":"Internal server error"}`.

## WebSocket

**CobbleCap publishes nothing over WebSocket.** There are no calls to
`DashboardRegistry.broadcast(...)` anywhere in the mod, so no channel carries cap changes,
champion victories or chrono events. A dashboard view that wants live data has to poll
`/players` and `/stats`.

Server-side announcements do exist, but they go to Minecraft chat
(`PlayerManager.broadcast`) and to Discord through `CobbleCapWebhook`, not to the
dashboard socket.

---

← [Commands: statues](commands-statues.md) · [Architecture](architecture.md) →
