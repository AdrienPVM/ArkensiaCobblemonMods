[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](rest-api.fr.md)

# REST API

Everything is JSON over HTTP on port 44311. This page covers the **core** routes, the
ones `DashboardApi` handles itself. Routes contributed by other mods live under
`/api/v1/<prefix>/` and are documented by those mods; how they are dispatched is
[at the bottom](#module-routes).

```text
http://<server>:44311/api/v1/<route>
```

Every route needs `Authorization: Bearer <session id or token>`, except
`POST /auth/login`. Roles are explained in
[authentication and roles](auth-and-roles.md#what-each-role-can-do); in the tables
below, **read** means any authenticated role, **write** means `OPERATOR` or above, and
**admin** means `SUPER_ADMIN`.

---

## Conventions

**The version prefix is optional.** The dispatcher strips `^/api/v1` and then `^/api`
from the path, so `/api/v1/ping`, `/api/ping` and even `/api/v1/api/ping` all reach the
same handler. Use `/api/v1/` — it is what the frontend sends and what will keep working
if a `/v2` ever appears.

**Responses are `application/json; charset=utf-8`**, always, including errors. An error
is `{"error":"<message>"}`; a `500` adds `{"detail":"<exception message>"}`.

**Status codes used by the core routes:**

| Code | When |
|---|---|
| `200` | Success |
| `204` | `OPTIONS` preflight |
| `400` | Missing or invalid field in the body, or an out-of-range value |
| `401` | No `Authorization` header, an unparseable one, or an unknown credential |
| `403` | Authenticated but the role is too low |
| `404` | Unknown route, or a named resource that does not exist |
| `409` | An account with that username already exists |
| `429` | Rate limit: more than 20 failed authentications in 60 s from this IP |
| `500` | Unhandled exception — including, on several routes, malformed JSON |
| `503` | The Minecraft server reference is not set yet |

> ⚠️ Several handlers call `JsonParser.parseString(body).getAsJsonObject()` without a
> `try`, so **invalid JSON produces a `500`, not a `400`**, on `POST /admin/accounts`,
> `PUT /admin/accounts/{username}`, `POST /server/command` and
> `PUT /dashboard/admin-player`. The login, scheduler and webhook routes do catch it and
> answer `400 {"error":"Invalid JSON"}`. Treat a `500` with a parse error in `detail` as
> your bug, not the server's.

**`503` is a defensive branch you should not see.** The routes that need the game —
`/status`, `/server/stats`, `/server/logs`, `/server/command` — answer
`503 {"error":"Server not ready"}` while the Minecraft server reference is null. But in
the `SERVER_STARTED` handler that reference is set (`CheeseDashMod.java:180`) *before*
the HTTP server is started (line 195), so by the time the port accepts connections it
is already there. During startup the symptom is a refused connection, not a `503`.
`/ping` never checks the reference and touches no game state, which makes it the
cheapest probe once the port is open.

---

## Health and status

### `GET /ping`

No role check at all beyond being authenticated. The cheapest possible liveness probe:
it does not touch the game.

```json
{"status":"ok","mod":"cheesedash"}
```

### `GET /status`

**read.** The overview page's main source. `503` before the server is ready.

```json
{
  "online": true,
  "playerCount": 7,
  "maxPlayers": 60,
  "motd": "Arkensia",
  "tps": 19.8,
  "uptime": 1772131331004,
  "version": "1.21.1",
  "moduleCount": 11,
  "wsPort": 44312,
  "username": "admin",
  "role": "SUPER_ADMIN"
}
```

| Field | Notes |
|---|---|
| `tps` | Measured by CheeseDash itself every 20 ticks, clamped to a maximum of 20.0, rounded to one decimal |
| `uptime` | ⚠️ **This is the current wall-clock time in epoch milliseconds**, not an uptime. For an actual uptime use `GET /server/stats`. The bundled overview page falls into the trap and displays this value as an uptime of tens of thousands of days |
| `wsPort` | Always `port + 1`. The frontend reads it from here rather than assuming |
| `username`, `role` | The caller's own session, omitted if somehow absent |

### `GET /modules`

**read.** What is installed. The frontend filters its sidebar against the prefixes in
this array, which is why the dashboard adapts to any combination of mods with no
configuration; the overview's module cards show the other three fields.

```json
[
  {"prefix":"cheeseguard","displayName":"CheeseGuard","icon":"🛡","description":"Zones et protections"},
  {"prefix":"kart","displayName":"CheeseKart","icon":"🏎","description":"Mode kart et courses"}
]
```

All four fields come straight from the module's `DashboardModule` implementation. An
empty array means CheeseDash is running alone.

---

## Server

### `GET /server/stats`

**read.** `503` before the server is ready. Heap figures come from `Runtime`, so they
describe the whole JVM, not just Minecraft.

```json
{
  "heapUsedMb": 4210,
  "heapMaxMb": 8192,
  "heapTotalMb": 6144,
  "tps": 19.8,
  "playerCount": 7,
  "maxPlayers": 60,
  "uptime": 7384112,
  "version": "1.21.1"
}
```

`uptime` here **is** a duration in milliseconds, measured from when the mod
initialized — unlike the field of the same name in `/status`.

### `GET /server/logs`

**read.** Reads `logs/latest.log` from the server's run directory and returns the tail.

| Parameter | Type | Default | Range |
|---|---|---|---|
| `n` | int | `100` | clamped to 1–500; a non-numeric value is ignored and the default used |

```json
{"lines":["[18:42:11] [Server thread/INFO]: …"],"totalLines":84120,"returned":100}
```

A missing log file is not an error: you get an empty `lines` array with both counts at
zero. A read failure is `500`.

> ⚠️ This reads the **entire** file into memory to take the last `n` lines. On a server
> that has been up for a week with a large `latest.log`, each call allocates the whole
> file. Fine occasionally, bad in a polling loop. And note there is no role check: a
> `READ_ONLY` account can read your log, which routinely contains player IPs.

### `GET /server/mods`

**read.** Every mod Fabric loaded, not just CheeseDash modules.

```json
[{"id":"cheesedash","name":"CheeseDash","version":"1.0.0+1.21.1"}]
```

### `POST /server/command`

**admin.** Runs a Minecraft command as the server console.

```json
{"command": "say hello"}
```

```json
{"executed":true,"command":"say hello"}
```

| Failure | Code |
|---|---|
| Server not ready | `503` |
| `command` key absent | `400 {"error":"command is required"}` |
| `command` empty or whitespace | `400 {"error":"command cannot be empty"}` |
| The command threw, or the server thread did not answer within 5 seconds | `500` with the exception message |

Three things happen before the command runs: the call is logged with the client IP, an
audit entry `server.command` is written with the full command string, and the execution
is handed to the server tick thread through `executeOnServerThread`, which waits up to
five seconds for it.

`{"executed":true}` means the server thread ran the command without throwing. It does
**not** mean the command succeeded — a `/give` to an offline player returns `200` here
and an error in the game log. Command output is not captured and not returned.

---

## Authentication

### `POST /auth/login`

**No authentication.** The only such route.

```json
{"token": "<master or account token>"}
```

```json
{"sessionId":"ses_9mJ…","username":"admin","role":"SUPER_ADMIN","expiresIn":86400000}
```

| Failure | Code |
|---|---|
| Body is not valid JSON | `400 {"error":"Invalid JSON"}` |
| `token` key absent | `400 {"error":"token is required"}` |
| Token not recognised | `401 {"error":"Invalid token"}`, audited as `auth.login_failed` |

The token is trimmed before comparison, so stray whitespace from a copy-paste is
tolerated. Not rate-limited — see
[rate limiting](auth-and-roles.md#rate-limiting).

### `POST /auth/logout`

**read.** Revokes the calling session. Always `200 {"ok":true}`, even if there was
nothing to revoke.

### `GET /auth/session`

**read.** The calling session, plus the server-wide count of active sessions.

```json
{
  "sessionId": "ses_9mJ…",
  "username": "admin",
  "role": "SUPER_ADMIN",
  "ip": "127.0.0.1",
  "createdAt": 1772131331004,
  "lastActivity": 1772131901551,
  "expiresIn": 85976553,
  "activeSessions": 2
}
```

`401 {"error":"No active session"}` is unreachable in practice: the middleware would
have rejected the request first.

---

## Administration

All of these are `SUPER_ADMIN` except the audit log.

### `GET /admin/accounts`

**admin.** Every additional account, never including its token. The master `admin`
account is not an entry in `accounts[]` and does not appear here.

```json
[{"username":"moderator","role":"OPERATOR","createdAt":1772131331004}]
```

### `POST /admin/accounts`

**admin.** Creates an account and generates its token.

```json
{"username": "moderator", "role": "OPERATOR"}
```

```json
{"username":"moderator","role":"OPERATOR","createdAt":1772131331004,"token":"Jq7x…-WkR"}
```

⚠️ The `token` field appears **only** in this response and in the regenerate response.
Nothing can retrieve it later; the only recovery is to regenerate, which invalidates the
old one.

| Failure | Code |
|---|---|
| `username` or `role` absent | `400 {"error":"username and role are required"}` |
| Username empty, over 32 characters, or outside `[a-zA-Z0-9_-]` | `400` with the rule in the message |
| Username is `admin` | `400 {"error":"'admin' is reserved for the master token"}` |
| Username already taken | `409 {"error":"Account already exists: …"}` |
| `role` not one of the three | `400 {"error":"Invalid role. Must be READ_ONLY, OPERATOR, or SUPER_ADMIN"}` |

`role` is upper-cased before being parsed, so `"operator"` is accepted. The account is
written to `config/cheesedash.json` immediately, and the action is audited as
`admin.account_create`.

### `PUT /admin/accounts/{username}`

**admin.** Changes the role, and only the role. There is no way to rename an account or
set its token to a chosen value.

```json
{"role": "READ_ONLY"}
```

```json
{"updated":true,"username":"moderator","role":"READ_ONLY"}
```

`400` if `role` is missing or invalid, `404 {"error":"Account not found: …"}` otherwise.
Audited as `admin.account_update`.

> The account's **existing sessions keep their old role**, because a session captures
> the role at creation. Demoting someone does not take effect until their session
> expires or you revoke it through `DELETE /admin/sessions/{id}`.

### `POST /admin/accounts/{username}/regenerate`

**admin.** Issues a new token and returns it once.

```json
{"username":"moderator","token":"Zp2v…-9tQ"}
```

Every session belonging to that username is revoked. `404` if there is no such account.
Audited as `admin.account_regen_token`.

### `DELETE /admin/accounts/{username}`

**admin.** `200 {"deleted":true}` or `404`. All of the account's sessions are revoked.
Audited as `admin.account_delete`.

### `GET /admin/sessions`

**admin.** Every active, non-expired session, with the same shape as
`GET /auth/session` minus `activeSessions`. Useful mainly for spotting a session you do
not recognise, or the session pile-up described in
[the note on raw tokens](auth-and-roles.md#a-raw-token-also-works-as-a-bearer-credential).

### `DELETE /admin/sessions/{id}`

**admin.** `200 {"revoked":true}` if it existed, `200 {"revoked":false}` if not. Note
this is not a `404` — the status code is `200` either way. Not audited.

### `GET /admin/audit`

**read** — deliberately, so any admin can see what the others did.

| Parameter | Type | Default | Range |
|---|---|---|---|
| `n` | int | `100` | clamped to 1–500 |
| `offset` | int | `0` | clamped to ≥ 0; counts back from the most recent entry |

```json
{
  "entries": [
    {"timestamp":"2026-03-09T18:42:11.004Z","username":"admin","role":"SUPER_ADMIN",
     "action":"server.command","detail":"say hello","ip":"127.0.0.1"}
  ],
  "total": 412,
  "offset": 0,
  "limit": 100
}
```

Newest first. `total` is the whole stored history, capped at 10 000 entries. Full
semantics in [audit log](audit.md).

---

## Dashboard preferences

### `POST /dashboard/token/regenerate`

**admin.** Replaces the master token.

```json
{"confirm": true}
```

```json
{"token":"<the new master token>"}
```

Without `confirm:true` in the body: `400 {"error":"Missing confirm:true in body"}`. The
check is a substring match on the raw body text — `{"confirm":true}` and
`{"confirm": true}` both pass, and any other spacing does not. Audited as
`admin.token_regenerate` and logged at `WARN` with the caller's IP.

⚠️ Existing sessions survive this. If you are regenerating because the token leaked,
restart the server afterwards.

### `GET /dashboard/admin-player` · `PUT /dashboard/admin-player`

**read** / **write.** The player name the dashboard treats as "you".

```json
{"name": "RoiCheese"}
```

The `PUT` echoes the trimmed name back and saves `config/cheesedash.json` immediately.
`400 {"error":"name is required"}` if the key is absent. No validation that the player
exists. Not audited.

### `GET /dashboard/admin-player/position`

**read.** Where that player is right now.

```json
{"online":true,"x":128.5,"y":64.0,"z":-310.2,"world":"minecraft:overworld"}
```

`{"online":false}` when the server is not ready, the name is empty, or the player is
offline. Never an error, which makes it safe to poll.

### Zone folders

**read** for the `GET`, **write** for the rest. Cosmetic grouping of CheeseGuard zones,
stored in CheeseDash's own config; see
[configuration](configuration.md#the-zonefolders-array).

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/dashboard/zone-folders` | — | the array of folder objects |
| POST | `/dashboard/zone-folders` | `{"name":"Spawn area"}` | the created folder, with a generated `id` |
| PUT | `/dashboard/zone-folders` | the **whole** array | `{"ok":true}` |
| DELETE | `/dashboard/zone-folders/{id}` | — | `{"ok":true}` |

The `PUT` replaces the entire list, so read-modify-write it; sending a partial array
deletes the folders you left out. `POST` answers `400 {"error":"name is required"}` on
an empty name. `DELETE` validates the id against `..` and `/` and answers `400` on a
suspicious one, `404` if no folder matches. None of these are audited.

---

## Scheduler

Reading is open to any role; everything that changes a schedule is `SUPER_ADMIN`.
Behaviour, countdown announcements and what `RESTART` does to the process are covered in
[scheduler](scheduler.md).

### `GET /server/schedule`

**read.**

```json
{
  "active": {
    "id": "sa_3f8a1c",
    "type": "RESTART",
    "message": "Weekly maintenance",
    "scheduledAt": 1772131931004,
    "createdAt": 1772131331004,
    "createdBy": "admin",
    "remainingSeconds": 582
  },
  "recurring": [
    {"id":"rec_9b21ef","type":"RESTART","hour":5,"minute":0,
     "daysOfWeek":[1,2,3,4,5,6,7],"message":"Nightly restart","enabled":true}
  ]
}
```

`active` is `null` when nothing is pending. `daysOfWeek` is ISO: 1 is Monday, 7 is
Sunday.

### `POST /server/schedule`

**admin.** Schedules a one-shot action. Only one can be pending; creating a second
cancels the first, and that cancellation is not silent: players get the usual
"… cancelled." chat line and a `schedule_cancelled` message goes out on the WebSocket,
just before the new countdown starts.

```json
{"type": "RESTART", "delaySeconds": 600, "message": "Weekly maintenance"}
```

| Field | Required | Notes |
|---|---|---|
| `type` | yes | `RESTART`, `STOP` or `MAINTENANCE`, upper-cased before parsing |
| `delaySeconds` | yes | 5 to 86 400 inclusive |
| `message` | no | Free text shown to players, `""` if absent |

Returns the created action in the shape shown above. `400` on invalid JSON, a missing
field, an unknown `type`, or a delay out of range. Audited as `schedule.create`, and a
`RESTART_SCHEDULED` Discord event is fired.

One-shot actions are **not persisted**. A server restart during a countdown forgets it.

### `DELETE /server/schedule/active`

**admin.** `200 {"cancelled":true}`, or `404 {"error":"No active scheduled action"}`.
Audited as `schedule.cancel`, fires `RESTART_CANCELLED` on Discord.

### `POST /server/schedule/recurring`

**admin.** Adds a daily or weekly schedule, persisted to
`config/cheesedash_schedules.json`.

```json
{"type":"RESTART","hour":5,"minute":0,"daysOfWeek":[1,3,5],"message":"Restart"}
```

| Field | Required | Notes |
|---|---|---|
| `type` | yes | as above |
| `hour` | yes | 0–23 |
| `minute` | yes | 0–59 |
| `daysOfWeek` | no | ISO day numbers; **all seven days** if omitted |
| `message` | no | `""` if absent |

`400` on invalid JSON, a missing field, an unknown `type`, or an hour/minute out of
range. Audited as `schedule.recurring_add`. Note the days are not validated: a
`daysOfWeek` of `[0]` or `[9]` is accepted and will never match.

### `PUT /server/schedule/recurring/{id}`

**admin.** Partial update. Only the keys present are applied: `hour`, `minute`,
`message`, `enabled`, `type`, `daysOfWeek` (which replaces the whole set). Returns the
updated schedule, or `404` if the id is unknown. Audited as
`schedule.recurring_update`.

⚠️ Unlike the create route, this one does **not** range-check `hour`, `minute` or `type`.
An unknown `type` throws and surfaces as a `500`, and an `hour` of `99` is stored and
never fires.

### `DELETE /server/schedule/recurring/{id}`

**admin.** `200 {"deleted":true}` or `404`. Audited as `schedule.recurring_delete`.

---

## Discord webhooks

Reading is open to any role; creating, changing, deleting and testing are
`SUPER_ADMIN`. Message formatting and the event catalogue are in
[Discord webhooks](discord-webhooks.md).

| Method | Path | Role | What it does |
|---|---|---|---|
| GET | `/webhooks/event-types` | read | The 23 event types with `id`, `label`, `description`, `color` and `category` |
| GET | `/webhooks` | read | Every webhook, with the middle of its URL masked |
| GET | `/webhooks/{id}` | read | One webhook, **URL unmasked** |
| POST | `/webhooks` | admin | Creates one |
| PUT | `/webhooks/{id}` | admin | Partial update |
| DELETE | `/webhooks/{id}` | admin | Removes it |
| POST | `/webhooks/{id}/test` | admin | Posts a test message and reports the result |

`POST /webhooks` takes `{"name":"Staff alerts","url":"https://discord.com/api/webhooks/…"}`.
`name` must be 1 to 64 characters, and the URL must start with
`https://discord.com/api/webhooks/` or `https://discordapp.com/api/webhooks/` — anything
else is `400 {"error":"URL must be a valid Discord webhook URL"}`. The same URL check
runs on `PUT` when a `url` key is present. A new webhook starts `enabled` with an empty
event set, so it subscribes to nothing until you `PUT` an `events` array.

`POST /webhooks/{id}/test` is the odd one out: it answers `200` whether the test
succeeded or not, with `{"success":true}` or `{"success":false,"error":"…"}`. A timeout
after 10 seconds is a `500` with the same `success:false` shape. It is not audited,
unlike create, update and delete.

> ⚠️ `GET /webhooks/{id}` returns the full Discord URL to any authenticated role,
> `READ_ONLY` included. That URL is a credential.

---

## Module routes

Anything the core does not recognise is treated as a module prefix:

```text
/api/v1/kart/presets/rally
         └┬─┘ └──────┬────┘
       prefix     subRoute, passed to the module as "/presets/rally"
```

The dispatcher takes the first path segment, looks it up in `DashboardRegistry`, and
calls `module.handleRequest(exchange, subRoute)`. A prefix with no segment after it
yields a subRoute of `"/"`.

Three things happen around that call, and they are the reason a module author has less
to write than they expect:

1. **Authentication is already done.** The session is attached to the exchange before
   dispatch, retrievable with `WebDashboard.getSession(exchange)`.
2. **Non-`GET` requests are gated by `requireWrite`** before the module sees them, so no
   module can accidentally let a `READ_ONLY` account write. A module that needs
   `SUPER_ADMIN` on a specific route still has to call `requireAdmin` itself.
3. **Non-`GET` requests are audited** afterwards, as `<prefix>.<method>` with the detail
   `POST /api/v1/kart/start`. A module that wants a more descriptive entry writes its
   own audit line in addition to this one.

An unknown prefix is `404 {"error":"Route not found: /nope/whatever"}`. Any exception
thrown out of a module handler becomes `500` with the exception message in `detail`, and
is logged with a stack trace.

> ⚠️ **These prefixes collide with core routes** and must not be used by a module:
> `ping`, `status`, `modules`, `auth`, `admin`, `dashboard`, `server`, `webhooks`. The
> core checks run first, so a module registering `server` would only receive the paths
> and methods the core does not handle itself — `GET /server/foo` would reach it,
> `GET /server/logs` never would — and under `admin/` it would receive nothing at all,
> since the core answers `404` for any unknown admin route. Nothing warns you about it. Registry collisions between two modules *are* warned
> about, at startup: `Duplicate module prefix 'x' — skipping …`.

A module can also serve an **unauthenticated public context** outside `/api/`, which is
how CheeseStore publishes its storefront. See
[writing a module](writing-a-module.md#public-unauthenticated-contexts).

---

← [Authentication and roles](auth-and-roles.md) · [WebSocket](websocket.md) →
