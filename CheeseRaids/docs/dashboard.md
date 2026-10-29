[← CheeseRaids](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

CheeseRaids registers a [CheeseDash](../../CheeseDash/) module under the prefix **`raids`**,
so every route lives at `/api/v1/raids/*`. The module is discovered through the Fabric
custom entrypoint declared in `fabric.mod.json`:

```json
"entrypoints": { "cheesedash": ["com.cheeseraids.dashboard.CheeseRaidsDashboardModule"] }
```

In the sidebar it appears as **CheeseRaids** with the description *Raids serveur
(invasions)*.

CheeseDash serves HTTP on port **44311** and the WebSocket on **44312**. Every route below
needs a session token in the `Authorization` header; you get one from
`POST /api/v1/auth/login`, which is the only unauthenticated endpoint on the whole
dashboard.

```bash
TOKEN=$(curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"token":"<master token>"}' | jq -r .token)

curl -s http://localhost:44311/api/v1/raids/ -H "Authorization: Bearer $TOKEN"
```

---

## Roles

Six routes, two role tiers. Read routes call no guard, so any authenticated session can
use them, including **READ_ONLY**. The three mutating routes call
`WebDashboard.requireWrite`, which means **OPERATOR or SUPER_ADMIN**. No route requires
`requireAdmin`, so nothing here is SUPER_ADMIN-only.

| Method | Path | Role | What it does |
|---|---|---|---|
| `GET` | `/api/v1/raids/` | any | combined overview: definitions summary + active instances |
| `GET` | `/api/v1/raids/overview` | any | identical to the above |
| `GET` | `/api/v1/raids/definitions` | any | every definition, serialised in full |
| `GET` | `/api/v1/raids/instances` | any | active instances only |
| `POST` | `/api/v1/raids/start` | OPERATOR | start a raid |
| `POST` | `/api/v1/raids/stop` | OPERATOR | stop and clean up a raid |
| `POST` | `/api/v1/raids/reload` | OPERATOR | re-read the definition files |

Those `requireWrite` calls are not the only thing enforcing that column. CheeseDash's
module dispatch in `DashboardApi` gates every module before `RaidsApi` is reached:

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
```

`RaidsApi`'s own three calls are therefore defence in depth: useful, and redundant in this
configuration. Three consequences of the real boundary sitting in the core:

- **No raid route can require `SUPER_ADMIN`.** The dispatch only knows `requireWrite`, so
  `OPERATOR` is the ceiling unless a handler calls `requireAdmin` itself, and none does.
  Starting a raid, stopping one, and reloading every definition file from disk are all
  `OPERATOR`-level operations.
- **A mutating `GET` would be caught only by the module.** The four read routes genuinely
  only read, but the dispatch keys purely on the HTTP method and would wave such a route
  straight through.
- **A change to the dispatcher would not disarm these three routes**, because they check
  the role themselves — unlike modules that guard nothing.

Anything else under the prefix returns `404 {"error":"Not found"}`. A request that arrives
before the server has finished starting returns `503 {"error":"CheeseRaids not ready"}`.
An unhandled exception returns `500 {"error":"Internal server error"}` and logs the cause
server-side.

---

## `GET /api/v1/raids/` · `GET /api/v1/raids/overview`

No parameters. The route the dashboard view actually calls.

```json
{
  "definitions": [
    {
      "id": "hoopa",
      "displayName": "Invasion Hoopa",
      "icon": "👻",
      "enabled": true,
      "autoTrigger": false,
      "hour": 20,
      "minute": 0,
      "everyNDays": 1,
      "active": true,
      "phase": "OPEN"
    }
  ],
  "instances": [ /* see below */ ]
}
```

`definitions[]` here is a **summary**, not the whole definition: ten fields, enough to
render a card. `active` says whether an instance exists, and `phase` is present only when
it does.

## `GET /api/v1/raids/definitions`

No parameters. The complete `RaidDefinition` objects, Gson-serialised verbatim — every
nested block, including the keys [configuration](configuration.md) marks as unused. This is
the one route that shows you exactly what the server has in memory, which makes it the
right way to confirm a `reload` picked up your edit.

```json
[
  {
    "id": "hoopa",
    "displayName": "Invasion Hoopa",
    "icon": "👻",
    "enabled": true,
    "schedule": { "autoTrigger": false, "hour": 20, "minute": 0, "everyNDays": 1 },
    "announce": { "title": "Invasion Hoopa", "subtitle": "…", "chat": "…", "discord": true, "discordMessage": "…" },
    "wave":  { "arena": { "world": "minecraft:overworld", "x": 0.5, "y": 65.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0 }, "spread": 6.0, "spawns": [ … ], "…": "…" },
    "npc":   { "easyNpcName": "hooh-raid", "entityType": "easy_npc:humanoid_slim", "pos": { … }, "…": "…" },
    "arena": { "model": "SHARED", "riftDimId": "hoopa_rift", "center": { … }, "…": "…" },
    "boss":  { "provider": "CUSTOM_GIANT", "species": "hoopa", "aspects": "unbound", "…": "…" },
    "reward":{ "portalDivisor": 3, "minPortals": 1, "portalAnchor": { … }, "…": "…" },
    "exitPos": { "world": "minecraft:overworld", "x": 0.5, "y": 65.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0 }
  }
]
```

There is **no write counterpart.** Definitions are read-only over REST: no `PUT`, no
`POST /definitions`, no `DELETE`. Editing a raid means editing the JSON file on disk and
calling `reload`.

## `GET /api/v1/raids/instances`

No parameters. One object per active instance; an empty array when nothing is running.

```json
[
  {
    "raidId": "hoopa",
    "phase": "COMBAT",
    "participants": 4,
    "waveTotal": 5,
    "bossSpawned": true,
    "players": [
      { "name": "RoiCheese", "slot": 0, "entered": true, "arrived": true,  "finished": false },
      { "name": "Steve",     "slot": 1, "entered": true, "arrived": false, "finished": false }
    ]
  }
]
```

| Field | Meaning |
|---|---|
| `phase` | the `RaidPhase` enum name |
| `participants` | `players.length` — everyone who entered, online or not |
| `waveTotal` | how many wave mobs actually spawned. Stays at its final value after the wave |
| `bossSpawned` | false until tick 36 of the boss apparition, so a `COMBAT` instance can legitimately show false |
| `players[].slot` | the angular branch slot, 0-based |
| `players[].entered` | teleported into the rift at least once |
| `players[].arrived` | reached the boss disk |
| `players[].finished` | ejected at the end of the raid |

What is **not** exposed, though the instance holds it: the boss's entity and battle UUIDs,
the wave entity list, every deadline, each player's last checkpoint, slot seeds, and the
respawn counter. If you are debugging a stuck raid, the REST API will not tell you why —
read `config/cheeseraids/instances/<raidId>.json`, which holds the whole object.

## `POST /api/v1/raids/start`

Requires **OPERATOR**. Body:

```json
{ "raid": "hoopa" }
```

A missing or unparseable body degrades to `raid = ""`, which is never a valid id, so the
call fails rather than starting something unexpected.

The start runs on the server thread through `WebDashboard.executeOnServerThread`, and the
HTTP thread waits for the result — so the status code reflects the real outcome.

```json
{ "started": true, "raid": "hoopa" }
```

| Status | When |
|---|---|
| `200` | the raid started |
| `409` | refused: unknown id, `enabled: false`, or already active. The body is `{"started":false,…}` |

```bash
curl -s -X POST http://localhost:44311/api/v1/raids/start \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"raid":"hoopa"}'
```

## `POST /api/v1/raids/stop`

Requires **OPERATOR**. Same body shape.

```json
{ "stopped": true }
```

| Status | When |
|---|---|
| `200` | the instance was found and cleaned up |
| `404` | nothing active under that id |

The same caveat as the command applies: **this does not teleport players out.** Stopping a
raid whose players are mid-parkour deletes the blocks under them. The dashboard view asks
for confirmation (*Stopper le raid « … » et nettoyer l'arène ?*) but the confirmation does
not warn about this.

## `POST /api/v1/raids/reload`

Requires **OPERATOR**. No body.

```json
{ "reloaded": true }
```

Always `200`, always `true`, even when every file failed to parse. Like the in-game
command, the only signal is the server log. If you are automating a config push, read
`GET /definitions` afterwards and check your change is actually there.

---

## WebSocket

CheeseRaids publishes on **one** channel, `raids`, with **one** message type.

Subscribe the usual way, after authenticating the socket:

```json
{"type":"auth","token":"<session token>"}
{"type":"subscribe","channels":["raids"]}
```

### `raid_update`

```json
{
  "type": "raid_update",
  "raidId": "hoopa",
  "phase": "COMBAT",
  "participants": 4
}
```

Emitted on **phase transitions only** — every call to `transition()`, which covers
`ANNOUNCE` through `CLEANUP`. Nothing else broadcasts: no message when a player enters, no
message when a checkpoint is crossed, no message when the boss spawns or dies, no message
when the raid is cleaned up and removed.

That makes the channel a coarse notification rather than a live feed. The dashboard view
subscribes to it, but the card's participant table is only redrawn when you press
*Rafraîchir* or when a phase changes. A player joining mid-`OPEN` will not appear until
something else triggers a refresh.

The broadcast is wired through a static `Consumer<RaidInstance>` on `CheeseRaidsMod`, set
by the dashboard module at server start and cleared at stop. With CheeseDash present but
the module not yet started, the consumer is null and the broadcast is skipped silently.

---

## The dashboard view

The `raids` module renders one card per definition, in a responsive grid.

Each card shows the icon and display name, the id as a badge, a status dot (green when
active, grey when idle), the current phase in monospace, and the schedule — either
`auto HH:MM · tous les Nj` or `déclenchement manuel`.

An active raid expands to a participant table: slot number, player name, and a progress
label derived from the three booleans — *En attente* (entered false), *Parkour* (entered,
not arrived), *Au centre* (arrived), *Terminé* (finished). `· boss actif` appears next to
the participant count once `bossSpawned` is true.

The buttons are **Démarrer** (disabled when `enabled` is false), **Stopper** (with a
confirmation), plus **Rafraîchir** and **Recharger les définitions** in the toolbar above
the grid. With no definitions loaded the view explains that a default `hoopa.json` is
created in `config/cheeseraids/raids/`.

There is no editor. Everything the config file controls — positions, spawns, boss,
rewards — is edited on disk, not in the browser. For a mod whose main setup problem is
four placeholder coordinates, that is the gap most worth closing.

---

← [External integrations](integrations.md) · [Architecture](architecture.md) →
