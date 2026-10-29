[← CheesePatrol](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

CheesePatrol registers a CheeseDash module under the prefix **`patrol`**, so everything
lives at `/api/v1/patrol/*` on CheeseDash's HTTP server, port **44311**.

It is the smallest dashboard surface in the repository: **two routes**. The dashboard is a
read-only window on the patrols plus one convenience action. Creating, editing, starting
and stopping circuits is commands-only, by omission rather than by design — there is no
endpoint for it.

---

## Registration

`fabric.mod.json` declares the entrypoint:

```json
"entrypoints": {
  "cheesedash": ["com.cheesepatrol.dashboard.CheesePatrolDashboardModule"]
}
```

CheeseDash discovers it at startup through
`FabricLoader.getEntrypoints("cheesedash", DashboardModule.class)`. What the module reports
for the sidebar:

| Method | Value |
|---|---|
| `getPrefix()` | `patrol` |
| `getDisplayName()` | `CheesePatrol` |
| `getNavIcon()` | 🚶 |
| `getNavDescription()` | `Patrouilles NPC` |

The description is hard-coded French, unlike the dashboard's own strings which go through
i18n. It is a loose end, not a deliberate choice.

The module holds the `MinecraftServer` reference between `onServerStart` and
`onServerStop`. While that reference is null — before the server starts, after it stops —
both routes answer:

```json
{"error":"CheesePatrol not ready"}
```

with status **503**.

## Authentication

Standard CheeseDash session auth. Log in once:

```bash
curl -X POST http://localhost:44311/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"token":"<master-token-or-account-token>"}'
```

and pass the session token it returns on every subsequent call:

```bash
curl http://localhost:44311/api/v1/patrol/list \
  -H 'Authorization: Bearer ses_…'
```

Sessions last 24 hours with a sliding expiry. Everything under `/api/v1/` except the login
route requires the header.

---

## `GET /list`

```text
GET /api/v1/patrol/list
```

No path or query parameters. Returns every patrol, active or not, as a JSON array.

**Required role: any authenticated session.** There is no `requireWrite` or `requireAdmin`
call on this route, so a `READ_ONLY` account can read it, which is correct for a listing.

```json
[
  {
    "npcUuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "npcName": "Village Guard",
    "dimension": "minecraft:overworld",
    "active": true,
    "waypointCount": 4,
    "currentWaypointIndex": 2,
    "speed": 1.0,
    "pausedBySystem": false
  }
]
```

| Field | Type | Notes |
|---|---|---|
| `npcUuid` | string | The full UUID. This is what `POST /tp` wants, and what `/npcpatrol select` wants |
| `npcName` | string | The entity's display name if it is loaded and findable. Otherwise the first 8 characters of the UUID followed by `...` — so a name that looks like `a1b2c3d4...` means the entity could not be found |
| `dimension` | string | The dimension the patrol is pinned to |
| `active` | boolean | Whether the circuit is running |
| `waypointCount` | integer | Length of the waypoint list |
| `currentWaypointIndex` | integer | **Zero-based.** The in-game commands display waypoints 1-based, so index `2` here is the waypoint `/npcpatrol info` calls `#3` |
| `speed` | number | Stored speed |
| `pausedBySystem` | boolean | `true` means the mod disabled this patrol itself after ten failed respawns. This is the one piece of state the dashboard exposes and `/npcpatrol list` does not |

| Status | When |
|---|---|
| `200` | always, on success. An empty array when there are no patrols |
| `401` | missing or expired session |
| `503` | the server reference is null |
| `500` | an unexpected exception; the body carries its message |

Two things worth knowing about this route:

- **The order is undefined.** It iterates a `ConcurrentHashMap`. The dashboard view sorts
  client-side, active first then by name; a script should sort too rather than relying on
  the response order being stable.
- **It resolves entity names from the HTTP thread.** `resolveNpcName` walks
  `server.getWorlds()` and calls `world.getEntity(uuid)` directly, without hopping to the
  server thread. See [the threading note](architecture.md#threading).

## `POST /tp`

```text
POST /api/v1/patrol/tp
```

Teleports a **player** to the waypoint one step beyond the one its NPC is currently walking
toward, so the player arrives in front of the NPC rather than behind it. It does not move
the NPC.

**Required role: OPERATOR or higher.** Enforced twice — see
[the note on where the role check lives](#where-the-role-check-lives) below.

**Request body:**

```json
{
  "npcUuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "playerName": "RoiCheese"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `npcUuid` | string | yes | Full dashed UUID. Not the truncated form from `/npcpatrol list` |
| `playerName` | string | yes | An **online** player's name, case-sensitive as `PlayerManager.getPlayer` matches it |

**Response:**

```json
{
  "success": true,
  "waypointIndex": 3,
  "x": 112,
  "y": 65,
  "z": -324,
  "dimension": "minecraft:overworld"
}
```

`waypointIndex` is `(currentWaypointIndex + 1) % waypointCount`, zero-based — so a patrol
heading for index 2 of 4 teleports you to index 3, which `/npcpatrol info` calls `#4`. On a
patrol at its last waypoint the index wraps to 0 and you land on waypoint `#1`.

The teleport is cross-dimension: it targets the patrol's own dimension, not the player's
current one, and preserves the player's yaw and pitch.

| Status | Body | When |
|---|---|---|
| `200` | the object above | scheduled successfully |
| `400` | `{"error":"Missing npcUuid or playerName"}` | one of the two fields is absent |
| `400` | `{"error":"Invalid UUID"}` | `npcUuid` is not parseable |
| `400` | `{"error":"Patrol has no waypoints"}` | the patrol exists but its list is empty |
| `400` | `{"error":"Dimension not found: <id>"}` | the patrol's dimension is not loaded on this server |
| `404` | `{"error":"Patrol not found"}` | no patrol for that UUID |
| `404` | `{"error":"Player not found: <name>"}` | not online |
| `403` | CheeseDash's own | the session's role is below OPERATOR |
| `500` | `{"error":"<exception message>"}` | anything else, including malformed JSON |

### Where the role check lives

`CheesePatrolApi` guards this route itself, in the correct form:

```java
} else if (path.equals("/tp") && method.equals("POST")) {
    if (!WebDashboard.requireWrite(exchange)) return;
    handleTp(exchange, server);
```

`requireWrite` sends the `403` and returns `false` when the session lacks OPERATOR, and
testing the result is what makes the guard real — a bare `WebDashboard.requireWrite(exchange);`
would send the `403` and then run the handler anyway.

That local call is not, though, the only thing standing in the way. CheeseDash's module
dispatch in `DashboardApi` already gates every module before the module sees the request:

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
```

So the module's own call is defence in depth: useful, and redundant in this configuration.
Three consequences of the real boundary living in the core:

- **No patrol route can require `SUPER_ADMIN`.** The dispatch only knows `requireWrite`, so
  `OPERATOR` is the ceiling unless a handler calls `requireAdmin` itself. Neither does.
- **A mutating `GET` would be caught only by the module.** `/tp` is a `POST`, so the point
  is theoretical here, but the dispatch keys purely on the HTTP method and would wave a
  mutating `GET` straight through.
- **A change to the dispatcher would not disarm `/tp`**, because of the local call — unlike
  modules that guard nothing themselves.

**The 200 means scheduled, not done.** The teleport goes through
`WebDashboard.executeOnServerThread`, which waits up to 5 seconds for the server thread to
run it. In practice it has happened by the time you read the response; under a server-thread
stall the call times out and you get a 500.

**Example:**

```bash
curl -X POST http://localhost:44311/api/v1/patrol/tp \
  -H 'Authorization: Bearer ses_…' \
  -H 'Content-Type: application/json' \
  -d '{"npcUuid":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","playerName":"RoiCheese"}'
```

## Unknown routes

Anything else under the prefix:

```json
{"error":"Unknown patrol route: /start"}
```

with status **404**. The path is echoed back unescaped beyond basic JSON quoting, which is
worth knowing if you are parsing the error.

## WebSocket channels

**CheesePatrol publishes nothing over WebSocket.** There are no `broadcast(...)` calls
anywhere in the mod and no channel registered with `DashboardRegistry`.

The practical consequence: the dashboard view is a snapshot, not a live feed. An NPC's
`currentWaypointIndex` in the table is whatever it was when you loaded the page, and the
page does not poll. Reload to see it move.

Adding live updates would be the obvious improvement — `patrol_update` on waypoint
advance, or at least on active/inactive transitions — and the hook point is `tickPatrol`'s
`advanceToNextWaypoint` call plus the system-pause path. At 20 ticks per second a per-advance
broadcast would be cheap; a per-tick one would not.

## The dashboard view

One page, registered in CheeseDash's front end as `view-patrol.js`, reached from the
sidebar under **CheesePatrol → Patrols**.

It shows a table, sorted active-first then alphabetically by NPC name:

| Column | Source |
|---|---|
| NPC | `npcName` |
| Dimension | `dimension`, with the namespace stripped — `minecraft:overworld` shows as `overworld` |
| Checkpoint | `currentWaypointIndex + 1` over `waypointCount`, converted to the 1-based form the commands use |
| Status | a coloured dot: green active, orange `pausedBySystem`, grey inactive |
| Speed | `speed` |
| — | a **TP** button per row |

The header counts active over total. An empty list shows an empty state rather than a bare
table.

The **TP** button calls `POST /tp` with the NPC's UUID and the admin player currently
selected in the dashboard header, read from `localStorage` under `cg_admin_player`. With no
admin player selected it refuses and tells you to pick one — that selector is a CheeseDash
feature, shared by every mod that needs to act on an admin's own character.

What the view does **not** offer: creating a patrol, adding or removing waypoints, changing
speed or radius, starting or stopping a circuit, or clearing one. All of that is
`/npcpatrol` in game, and it has to be, because selecting an NPC requires pointing at it.

There is also a built-in guide, reachable from the question-mark button in the view header,
covering the same ground as these pages in short form. ⚠️ Two of its claims are wrong
against the current code: it describes `/npcpatrol markers` as a toggle (it only refreshes)
and lists a `drip` particle effect that does not exist.

---

← [Keeping NPCs alive](resilience.md) · [Architecture](architecture.md) →
