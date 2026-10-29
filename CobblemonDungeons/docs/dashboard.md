[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard integration

CobblemonDungeons registers a CheeseDash module through the `cheesedash` entrypoint in its
`fabric.mod.json`:

```json
"cheesedash": ["com.sebastian.cobblemondungeons.dashboard.CobblemonDungeonsDashboardModule"]
```

The module declares the prefix `dungeons`, so every route below lives under
`/api/v1/dungeons/`. CheeseDash serves HTTP on port **44311** and the WebSocket on
**44312**.

Authentication is CheeseDash's: `POST /api/v1/auth/login` returns a session token you pass
as `Authorization: Bearer <token>` on every other request. The module answers `503` with
`{"error":"CobblemonDungeons not ready"}` for any request that arrives before
`onServerStart` has handed it the `MinecraftServer`, or after `onServerStop`.

---

## What the core actually enforces

`DungeonsApi` contains no role check of its own. It never calls
`WebDashboard.requireWrite` or `WebDashboard.requireAdmin` — not once, anywhere in the
file, across its eight mutating routes.

What stands in for those checks is CheeseDash's own module dispatcher, in `DashboardApi`:

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

That is the correct form — it tests the return value and returns — so in this build every
non-`GET` request to any route under `/api/v1/dungeons/` does require `OPERATOR` or above,
and `GET` requests accept any role including `READ_ONLY`. The boundary holds.

What does **not** hold is the reason it holds. The protection is a property of the core's
dispatch, keyed purely on the HTTP method, and the module contributes nothing. Three
consequences:

- **No route in CobblemonDungeons can require `SUPER_ADMIN`.** Deleting a dungeon and its
  file, overwriting any trainer JSON, and force-failing a live run are all `OPERATOR`-level
  operations, because `OPERATOR` is the only bar the dispatcher knows.
- **A mutating `GET` would be completely unguarded.** None exists today, but nothing in the
  module would stop one from being added, and nothing would flag it.
- **A change to the dispatcher removes every check at once.** This API has no second line
  of defence.

The eight mutating routes, all `OPERATOR` or above by the core's dispatch:

| Route | What it does |
|---|---|
| `POST /configs` | Create a dungeon |
| `PUT /configs/:id` | Overwrite a dungeon's whole config |
| `DELETE /configs/:id` | Delete a dungeon and its file |
| `POST /configs/reload` | Reload every config from disk |
| `PUT /tickets` | Rewrite the ticket templates |
| `PUT /trainers/:id` | Write any trainer JSON file |
| `POST /active/fail` | Force-fail a running dungeon |
| `POST /active/leave` | Remove a player from a running dungeon |

A local `if (!WebDashboard.requireWrite(exchange)) return;` at the top of each of those
handlers would be defence in depth — useful, but redundant with the dispatcher, and not the
boundary itself.

For reference, `requireWrite` means `OPERATOR` or above and `requireAdmin` means
`SUPER_ADMIN`; both send their own `403` and report the outcome as a boolean. The role
column in the tables below states the role **actually** required in this build, and marks
with (core) the cases where the guarantee comes from CheeseDash's dispatch rather than from
the module.

## Dungeon configs

### `GET /configs`

Summaries of every registered dungeon. Role: any role, `READ_ONLY` included.

```json
[
  {
    "id": "generic_dungeon",
    "name": "Generic Dungeon",
    "icon": "minecraft:paper",
    "tier": 1,
    "teleportMode": "CROSS_SERVER",
    "activeInstances": 0,
    "activePlayers": 0,
    "slotsTotal": 2,
    "timeLimitSeconds": 1800,
    "partyLimit": 5
  }
]
```

`slotsTotal` is `1 + additionalLocations.length`. `activeInstances` and `activePlayers`
are counted live from the instance maps.

### `GET /configs/:id`

The dungeon's full `DungeonConfig`, serialised by Gson exactly as the file on disk would
be. Role: any role, `READ_ONLY` included. `404` with `{"error":"Dungeon not found"}` if
the id is unknown.

Path ids are validated: an id containing `..`, `/`, `\` or `%`, or 128 characters or more,
is rejected with `400` and `{"error":"Invalid config ID"}`.

### `POST /configs`

Creates a dungeon. Role: `OPERATOR` or above (core).

```json
{ "id": "ruins_of_arkensia", "name": "Ruins of Arkensia" }
```

| Field | Required | Default |
|---|---|---|
| `id` | yes | — |
| `name` | no | falls back to `id` |

The rest of the config is the `DungeonConfig` defaults, with one override:
`networking.teleportMode` is forced to `SAME_SERVER`, because the dashboard cannot know
your proxy layout. The dungeon is registered and written to
`config/cobblemon_dungeons/dungeons/<id>.json` on the server thread.

Responses: `200` with the full config, `400` `{"error":"id is required"}` for a missing or
blank id, `409` `{"error":"Dungeon ID already exists: <id>"}`, `500` with the exception
message.

### `PUT /configs/:id`

Replaces the whole config. Role: `OPERATOR` or above (core). The body is a complete
`DungeonConfig` document; anything you omit comes back as the field default, so this is a
replace and not a merge. `general.id` in the body is ignored — the path id always wins, so
a dungeon cannot be renamed through this route.

The new config is registered and saved on the server thread. Running instances keep the
`DungeonConfig` object they were built with, so an edit does not disturb a live run.

Responses: `200` with the stored config, `404` if unknown, `400`
`{"error":"Invalid config JSON"}` if the body does not deserialise, `500` on an exception.

### `DELETE /configs/:id`

Deletes the file, then runs a full `ConfigLoader.load()` to rebuild the registry. Role:
`OPERATOR` or above (core). Answers `200 {"deleted":true}`, or `404` if unknown.

A live run of the deleted dungeon keeps going — its instance holds its own config object.

### `POST /configs/reload`

`ConfigLoader.load()` on the server thread: clears the registry, re-reads
`config.json`, `dungeon_tickets.json`, every `dungeons/*.json`, and then the run history,
cooldowns and leaderboard. Role: `OPERATOR` or above (core). Answers
`200 {"reloaded":true}`.

Trainers are **not** reloaded — `TrainerLoader.load` only runs at server start.

## Tickets

### `GET /tickets`

The raw contents of `dungeon_tickets.json`. Role: any role, `READ_ONLY` included.

```json
{ "tickets": [ { "dungeonId": "generic_dungeon", "itemName": "§6Copper Dungeon Ticket", "tier": 1 } ] }
```

### `PUT /tickets`

Replaces the ticket config and saves it to disk. Role: `OPERATOR` or above (core).
Returns the stored config.

A body that is not valid JSON is swallowed: `ConfigLoader.updateTicketConfig` catches the
exception, logs `Failed to update dungeon tickets config from server`, and the route still
answers `200` with the **unchanged** config. Compare the response to what you sent if you
need certainty.

Connected clients are not re-synced; they pick the change up on their next join.

## Trainers

### `GET /trainers`

Lists the files in `config/cobblemon_dungeons/trainers/`. Role: any role, `READ_ONLY`
included.

```json
[ { "id": "example_trainer", "filename": "example_trainer.json" } ]
```

An absent directory yields an empty array.

### `GET /trainers/:id`

The raw file contents of `<id>.json`, served as-is. Role: any role, `READ_ONLY` included.
`404` `{"error":"Trainer file not found"}` if the file does not exist.

### `PUT /trainers/:id`

Validates that the body parses as JSON, creates the directory if needed, and writes the
body verbatim to `<id>.json`. Role: `OPERATOR` or above (core). Returns the body on
success, `400` `{"error":"Invalid JSON: …"}` otherwise.

Only JSON syntax is checked — not whether RCTApi can make a trainer out of it. A
syntactically valid file with a nonsense schema is accepted here and fails at the next
server start. And since trainers load once at start, nothing written through this route
takes effect until you restart.

Both trainer routes validate the id the same way config ids are validated, so the path
cannot escape the trainers directory.

## Active runs

### `GET /active`

Every live instance, deduplicated — the instance map is keyed per player, so a four-person
party would otherwise appear four times. Role: any role, `READ_ONLY` included.

```json
{
  "instances": [
    {
      "dungeonId": "generic_dungeon",
      "dungeonName": "Generic Dungeon",
      "tier": 2,
      "timeLeft": 1420,
      "lives": 5,
      "alphasDefeated": 1, "alphasRequired": 2,
      "gemsFound": 0, "gemsRequired": 3,
      "guardiansDefeated": 2, "guardiansRequired": 2,
      "stakesFound": 4, "stakesRequired": 4,
      "bossSpawned": false,
      "bossDefeated": false,
      "bossSpecies": "charizard",
      "startTime": 1761000000,
      "players": [
        { "uuid": "…", "name": "RoiCheese", "online": true }
      ]
    }
  ],
  "countdownCount": 0
}
```

`bossSpecies` is present only once a boss has spawned. `startTime` is Unix seconds.
`countdownCount` is the number of players sitting in the 5-second pre-start countdown, not
a number of instances.

### `POST /active/fail`

Force-fails the run the given player is in. Role: `OPERATOR` or above (core).

```json
{ "playerUuid": "0b1c…" }
```

Resolves the player's instance, then runs the normal failure path on the server thread:
history entry with `success: false`, a `dungeon_fail` broadcast, entity cleanup, and every
member told *Dungeon forcefully terminated by an administrator.* before being sent out.

Responses: `200 {"failed":true}`, `400 {"error":"playerUuid is required"}`, `500` with the
exception message. A UUID that is well-formed but matches nobody still answers
`200 {"failed":true}` — the work is scheduled and silently does nothing.

### `POST /active/leave`

Removes one player from their run, leaving the rest of the party in it. Role: `OPERATOR`
or above (core). Same body shape. The player sees *You have been removed from the
dungeon by an administrator.* and goes through the normal leave path, which on a
non-completed run means their dungeon progress is not saved.

Responses: `200 {"left":true}`, `400 {"error":"playerUuid is required"}`, `500` on an
exception. Requires the player to be online; an offline UUID is a no-op that still reports
success.

## Statistics, history, leaderboard

### `GET /stats`

Aggregates computed on the fly from the in-memory run history. Role: any role, `READ_ONLY`
included. See [leaderboards and run history](records.md#statistics) for the exact
definitions.

```json
{
  "totalRuns": 42, "successCount": 17, "successRate": 40,
  "avgDurationSeconds": 643,
  "configCount": 3, "activeInstances": 1,
  "runsByDungeon": { "generic_dungeon": { "total": 42, "success": 17 } },
  "runsByTier": { "2": { "total": 42, "success": 17 } }
}
```

### `GET /stats/history?n=50`

The most recent runs, newest first. Role: any role, `READ_ONLY` included.

| Parameter | Type | Default | Range |
|---|---|---|---|
| `n` | int | `50` | clamped to 1–1000; an unparseable value falls back to 50 |

```json
{ "history": [ { "dungeonId": "…", "players": ["RoiCheese"], "durationSeconds": 612, "success": true, "…": "…" } ],
  "total": 42, "returned": 42 }
```

`total` is the whole history size, `returned` how many are in this response.

### `GET /leaderboard` and `GET /leaderboard/:dungeonId`

Role: any role, `READ_ONLY` included. Both return the same nested shape — dungeon id,
then tier as a string key, then an array of entries sorted fastest first:

```json
{
  "generic_dungeon": {
    "2": [ { "playerName": "RoiCheese", "playerUuid": "…", "durationSeconds": 612, "date": 1761000000 } ]
  }
}
```

The per-dungeon form is a single-key object, not a bare array. A dungeon with no recorded
times returns `{"<dungeonId>": {}}`.

## Main config

### `GET /config`

The contents of `config.json`, with `database.password` replaced by `"***"`. Role: any
role, `READ_ONLY` included.

```json
{ "database": { "useSql": false, "host": "localhost", "port": 3306,
                "database": "minecraft", "username": "root",
                "password": "***", "tablePrefix": "cobblemon_dungeons_" } }
```

Read-only — there is no `PUT /config`. Database settings are edited in the file and
applied by restarting the server. The username, host and table prefix are **not** masked.

## Unknown routes

Anything that matches none of the above gets `404` with
`{"error":"Unknown dungeons route: <route>"}`. A method that is wrong for a matched path
with an id gets `405 {"error":"Method not allowed"}`. `GET /configs/reload` specifically
answers `404 {"error":"Use POST /configs/reload"}`.

## WebSocket messages

The mod broadcasts on the CheeseDash channel **`dungeons`**. Subscribe with:

```json
{ "type": "subscribe", "channels": ["dungeons"] }
```

Five message types exist, all produced by the same builder and therefore all carrying the
same payload:

| `type` | Sent when |
|---|---|
| `dungeon_start` | The countdown has finished and a member's run begins |
| `dungeon_player_death` | A player died and a life was consumed |
| `dungeon_boss_spawn` | The boss has been spawned |
| `dungeon_complete` | The boss was defeated |
| `dungeon_fail` | The run failed — timer, lives, or a dashboard force-fail |

```json
{
  "type": "dungeon_complete",
  "dungeonId": "generic_dungeon",
  "dungeonName": "Generic Dungeon",
  "tier": 2,
  "players": ["RoiCheese", "Alex"],
  "timeLeft": 1105,
  "lives": 4,
  "bossSpecies": "charizard"
}
```

`bossSpecies` is omitted when no boss has spawned yet. There is no timestamp in the
payload — use the receipt time.

Two caveats from the code. `dungeon_start` is broadcast once **per member** of the party,
because it fires inside the per-player start routine, so a party of four produces four
identical messages. And the broadcast is wrapped in a bare `catch (Exception) {}` after a
`FabricLoader.isModLoaded("cheesedash")` guard, so a failing broadcast is silent — if
messages stop arriving, nothing will appear in the log.

## CheeseGuard event effects

CheeseGuard drives this mod during timed events through three timeline effect types, all
routed by `CobblemonDungeonsHook` in
`CheeseGuard/src/main/java/com/cheeseguard/logic/CobblemonDungeonsHook.java`. The hook
uses reflection — CheeseGuard has no compile-time dependency on this mod — and is
initialised once from `EventManager.init()`. On success it logs
`[CG] CobblemonDungeons detected — dungeon effects enabled`; without the mod,
`[CG] CobblemonDungeons not installed — dungeon effects disabled`, and the three effects
become no-ops.

| Effect | Properties | What it does |
|---|---|---|
| `DUNGEON_OPEN` | `dungeonId`, `dungeonTier` (default `1`) | Calls `DungeonManager.startDungeon(leader, id, tier)` for the **first** participant only, as the leader; the rest come along as that player's party |
| `DUNGEON_TICKET_GIVE` | `dungeonId`, `dungeonTier` (default `1`), `ticketCount` (default `1`) | Builds a ticket per participant via `DungeonTicketItem.createStack`, inserted in inventory or dropped at their feet |
| `DUNGEON_MODIFIER` | `dungeonId`, `modifierType` (default `DOUBLE_LOOT`), `modifierValue` (default `2.0`) | Logs and announces, and nothing else |

`DUNGEON_OPEN` checks that the dungeon exists first and warns
`[CG-TL] DUNGEON_OPEN: dungeon '<id>' not found` otherwise. The normal entry checks still
apply, ticket included — the effect does not bypass them, so a participant without a
ticket simply does not enter.

⚠️ `DUNGEON_MODIFIER` does not modify anything. Its own source comment says so: the effect
stores metadata and broadcasts a notification, and integration with `DungeonManager` is
listed as future work. It appears in the CheeseGuard effect list and in the timeline
editor, and it will not double your loot.

Tickets created by `DUNGEON_TICKET_GIVE` are named
`§6Dungeon Ticket [<dungeonId> T<tier>]`, built from a template constructed in the hook
rather than read from `dungeon_tickets.json`.

---

← [Leaderboards and run history](records.md) · [Architecture](architecture.md) →
