[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

CheeseGuard registers itself with CheeseDash through the `cheesedash` Fabric entrypoint,
under the prefix `cheeseguard`. Every route below sits at:

```text
http://<server>:44311/api/v1/cheeseguard/<route>
```

In the dashboard sidebar it appears as **CheeseGuard** 🛡️ with the description
*Zones, dimensions et événements*.

## Authentication

Every route needs a CheeseDash session:

```http
Authorization: Bearer <session-token>
```

Get one from `POST /api/v1/auth/login`.

## What the core actually enforces

> ⚠️ **Six of CheeseGuard's eight APIs contain no role check at all.** `ZonesApi`,
> `DimensionsApi`, `EventsApi`, `GroupsApi`, `PlayersApi` and `ChunksApi` never call
> `WebDashboard.requireWrite` or `WebDashboard.requireAdmin` — not once, across 65 mutating
> routes. Only `PrisonApi` and `RacesApi` guard themselves.
>
> What stands in for those checks is CheeseDash's own module dispatcher, in
> `DashboardApi`:
>
> ```java
> DashboardModule module = DashboardRegistry.getModule(prefix);
> if (module != null) {
>     if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
>         return;
>     }
>     module.handleRequest(exchange, subRoute);
>     ...
> }
> ```
>
> That is the correct form — it tests the return value and returns — so in this build every
> non-`GET` request to any route under `/api/v1/cheeseguard/` does require `OPERATOR` or
> above, and `GET` requests accept any role including `READ_ONLY`. The boundary holds.
>
> What does **not** hold is the reason it holds. The protection is a property of the core's
> dispatch, keyed purely on the HTTP method, and the module contributes nothing. Three
> consequences:
>
> - **No route in CheeseGuard can require `SUPER_ADMIN`.** Resetting a dimension from a
>   snapshot, deleting region files from the world save, and deleting a zone are all
>   `OPERATOR`-level operations, because `OPERATOR` is the only bar the dispatcher knows.
> - **A mutating `GET` would be completely unguarded.** None exists today in these six APIs,
>   but nothing in the module would stop one from being added, and nothing would flag it.
> - **A change to the dispatcher removes every check at once.** The six APIs have no second
>   line of defence.
>
> This is documented rather than fixed, deliberately. The role column in the tables below
> states the role **actually** required in this build, and marks with (core) the cases where
> the guarantee comes from CheeseDash's dispatch rather than from the module.

For reference, `requireWrite` means `OPERATOR` or above and `requireAdmin` means
`SUPER_ADMIN`; both send their own `403` and report the outcome as a boolean:

```json
{"error":"Insufficient permissions — requires OPERATOR"}
```

Every non-`GET` module request is also written to CheeseDash's audit log as
`cheeseguard.<method>` with the full path, regardless of which API handled it.

## Routing

`CheeseGuardDashboardModule.handleRequest()` answers `503` if the mod or the server is not
ready:

```json
{"error":"CheeseGuard not ready"}
```

Otherwise it hands off to `CheeseGuardApiRouter.handle()`, which dispatches on a prefix
match of the sub-route, in this order:

| Sub-route starts with | Handler |
|---|---|
| `/` or `/status` (GET) | the router's own status handler |
| `/zones` | `ZonesApi` |
| `/groups` | `GroupsApi` |
| `/dimensions` | `DimensionsApi` |
| `/events` | `EventsApi` |
| `/players` | `PlayersApi` |
| `/chunks` | `ChunksApi` |
| `/races` | `RacesApi` |
| `/prison` | `PrisonApi` |

Because the match is `startsWith` and not an exact segment comparison, a sub-route like
`/zonesfoo` is routed to `ZonesApi`, which then fails to match any of its own patterns and
answers its generic `404 {"error":"Route not found"}`. The outcome is right; the path taken
is not what you would guess from the router.

An unmatched prefix answers:

```json
{"error":"CheeseGuard route not found: /whatever"}
```

Any exception escaping a handler is caught by the router and turned into a `500`:

```json
{"error":"Internal server error","detail":"<message with double quotes replaced by apostrophes>"}
```

Individual APIs catch their own exceptions first, so most errors arrive as a `500` with an
`error` field rather than this envelope. Only `ChunksApi` and `RacesApi` unwrap the
server-thread cause; the zone, group, dimension, event and player routes report the
message of the wrapping `ExecutionException`, so a failure inside the server-thread task
reads `java.lang.RuntimeException: <message>` (or the name of whatever exception was
thrown). The messages quoted on this page are shown without that prefix.

### `GET /` and `GET /status`

Role: any. No parameters. A small summary used by the dashboard's overview:

```json
{ "zoneCount": 42, "dimensionCount": 7, "activeEvents": 1, "eventCount": 12 }
```

Each field is present only if the corresponding manager exists, so an early request during
startup can return a partial object or `{}`.

---

## Zones

`ZonesApi`, 1056 lines, the largest of the eight. Field semantics are in
[Zones and flags](zones-and-flags.md); this is the route surface.

| Method | Path | Body / params | Response | Statuses | Role |
|---|---|---|---|---|---|
| `GET` | `/zones` | `?world=<id>` filters by world | array of zone summaries | `200` | any |
| `POST` | `/zones` | `{name, playerName?}` | the created zone | `200` `400` `404` `409` `500` | `OPERATOR` (core) |
| `GET` | `/zones/:id` | — | zone detail | `200` `400` `404` | any |
| `PUT` | `/zones/:id` | partial zone fields | zone detail | `200` `400` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/zones/:id` | — | `{"deleted":true}` | `200` `404` `409` | `OPERATOR` (core) |
| `GET` | `/zones/tags` | — | the tags in use | `200` | any |
| `GET` | `/zones/tag-configs` | — | all tag configs | `200` `503` | any |
| `GET` | `/zones/tag-configs/:tagId` | — | one tag config | `200` `400` `404` | any |
| `PUT` | `/zones/tag-configs/:tagId` | tag config fields | the config | `200` `400` `500` | `OPERATOR` (core) |
| `DELETE` | `/zones/tag-configs/:tagId` | — | `{"deleted":true}` | `200` `400` `500` | `OPERATOR` (core) |
| `GET` | `/zones/:id/states` | — | saved zone states | `200` | any |
| `POST` | `/zones/:id/states` | `{name}` | the captured state | `200` `500` | `OPERATOR` (core) |
| `POST` | `/zones/:id/states/cancel` | — | `{...}` | `200` | `OPERATOR` (core) |
| `PUT` | `/zones/:id/states/:stateId` | `{name}` | the state | `200` `400` `404` | `OPERATOR` (core) |
| `DELETE` | `/zones/:id/states/:stateId` | — | `{"deleted":true}` | `200` `404` | `OPERATOR` (core) |
| `POST` | `/zones/:id/states/:stateId/apply` | — | result | `200` `400` `500` | `OPERATOR` (core) |
| `PUT` | `/zones/:id/sale` | sale config | the sale config | `200` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/zones/:id/sale` | — | `{...}` | `200` `404` `500` | `OPERATOR` (core) |
| `POST` | `/zones/:id/sale/hologram` | — | `{...}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/zones/:id/sale/hologram` | — | `{...}` | `200` `404` `500` | `OPERATOR` (core) |
| `POST` | `/zones/:id/sale/sign` | `?player=<name>` | `{...}` | `200` `400` `404` | `OPERATOR` (core) |
| `POST` | `/zones/:id/sale/relist` | — | `{...}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `GET` | `/zones/:id/members` | — | array of members | `200` `404` | any |
| `POST` | `/zones/:id/members` | `{playerName}` (any `role` is ignored) | `{...}` | `200` `400` `404` `409` `500` | `OPERATOR` (core) |
| `DELETE` | `/zones/:id/members/:uuid` | — | `{...}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `PUT` | `/zones/:id/owner` | `{playerName}` | `{...}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/zones/:id/owner` | — | `{...}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `POST` | `/zones/:id/replace-selection` | `{playerName?}` | the zone | `200` `400` `404` `500` | `OPERATOR` (core) |

Zone ids are validated with the standard helper — no `..`, `/`, `\`, `%`, and under 128
characters — and a bad one answers `400 {"error":"Invalid zone ID"}` before any lookup.

`POST /zones` creates a zone **from a wand selection**, not from coordinates. With no
`playerName` it uses the first online operator; if there is none, `400 No playerName
provided and no op player online`. If the named player has no complete two-corner selection,
`400 No complete wand selection for player <name>`. A colliding id gives `409`.

`PUT /zones/:id` is a patch. Flags are sent as an object of flag id to `"ALLOW"`, `"DENY"`
or `"INHERIT"`, case-insensitive; an unknown flag id is skipped silently rather than
refused. Setting `groupId` also maintains the group's own member list on both sides, and an
empty string is treated as `null`.

```bash
curl -X PUT http://localhost:44311/api/v1/cheeseguard/zones/arena \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"priority":5,"flags":{"pvp":"ALLOW","block-break":"DENY"}}'
```

`DELETE /zones/:id` answers `409` when the zone is the configured prison zone and still
holds prisoners. It does not look at child zones: zones whose `parentId` points at the
deleted one keep that dangling id.

`PUT /zones/tag-configs/:tagId` replaces the whole config: a `label` or `color` left out
becomes empty, and an `ownershipLimit` left out becomes `0` (unlimited).

`DELETE /zones/:id/members/:uuid` refuses to remove the owner:
`400 Cannot remove owner via members endpoint. Use DELETE /zones/:id/owner instead.`

Tag ids are validated separately against lowercase letters, digits and hyphens:
`400 {"error":"Invalid tag ID. Use lowercase letters, digits, and hyphens only."}`

---

## Groups

`GroupsApi`. Groups are named collections of zones that share flags — see
[Groups](groups.md).

| Method | Path | Body | Response | Statuses | Role |
|---|---|---|---|---|---|
| `GET` | `/groups` | — | array of group summaries | `200` | any |
| `POST` | `/groups` | `{name}` | group detail | `200` `400` `409` `500` | `OPERATOR` (core) |
| `GET` | `/groups/:id` | — | group detail | `200` `404` | any |
| `PUT` | `/groups/:id` | partial group fields | group detail | `200` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/groups/:id` | — | `{"deleted":true}` | `200` `404` `500` | `OPERATOR` (core) |
| `GET` | `/groups/:id/zones` | — | the group's zones | `200` `404` | any |
| `POST` | `/groups/:id/zones/:zoneId` | — | `{"assigned":true}` | `200` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/groups/:id/zones/:zoneId` | — | `{"unassigned":true}` | `200` `404` `500` | `OPERATOR` (core) |

The group id is **derived** from the name by slugifying it — you do not choose it. A name
that slugifies to an empty string gives `400 {"error":"Invalid group name"}`, and a
collision gives `409 {"error":"Group already exists: <id>"}`. Two different names can slugify
to the same id, in which case the second one is refused.

---

## Dimensions

`DimensionsApi`, 806 lines. Field semantics are in [Dimensions](dimensions.md).

| Method | Path | Body / params | Response | Statuses | Role |
|---|---|---|---|---|---|
| `GET` | `/dimensions/gen-types` | — | available generation types | `200` | any |
| `GET` | `/dimensions` | — | array of enriched summaries | `200` | any |
| `POST` | `/dimensions` | `{name, genType\|customGenTypeId, ...}` | dimension detail | `200` `400` `500` | `OPERATOR` (core) |
| `GET` | `/dimensions/:id` | — | dimension detail | `200` `400` `404` | any |
| `PUT` | `/dimensions/:id` | partial fields | dimension detail | `200` `400` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/dimensions/:id` | — | `{"deleted":true}` | `200` `404` `500` | `OPERATOR` (core) |
| `POST` | `/dimensions/:id/snapshot` | — | result | `200` `404` `500` | `OPERATOR` (core) |
| `POST` | `/dimensions/:id/reset` | — | result | `200` `404` `500` | `OPERATOR` (core) |
| `POST` | `/dimensions/:id/toggle` | `{key}` | dimension detail | `200` `400` `404` `500` | `OPERATOR` (core) |
| `PUT` | `/dimensions/:id/snapshot/config` | `{trigger, hour?}` | `{trigger, hour, updated}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `POST` | `/dimensions/:id/whitelist` | `{playerName}` | `{...}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/dimensions/:id/whitelist/:uuid` | — | `{...}` | `200` `400` `404` | `OPERATOR` (core) |
| `POST` | `/dimensions/:id/builders` | `{playerName}` | `{...}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/dimensions/:id/builders/:uuid` | — | `{...}` | `200` `400` `404` | `OPERATOR` (core) |
| `GET` | `/dimensions/:id/players` | — | per-player dimensions | `200` `404` | any |
| `DELETE` | `/dimensions/:id/players/:uuid8` | — | `{"deleted":true}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `POST` | `/dimensions/:id/players/:uuid8/reset` | — | `{"reset":true}` | `200` `400` `404` `500` | `OPERATOR` (core) |

`POST /dimensions` requires `name` and one of `genType` or `customGenTypeId`:
`400 {"error":"name and (genType or customGenTypeId) are required"}`. An unknown
`customGenTypeId` gives `400 {"error":"Unknown customGenTypeId: <id>"}`; an unknown
`genType` gives `400 {"error":"Invalid genType: <value>"}`. With a custom gen type, an
invalid `genType` is *not* an error — it falls back to `NORMAL`, because the enum only
selects the dimension type and the default spawn Y.

The id is slugified from the name — lowercase, non-alphanumerics collapsed to `_`, leading
and trailing `_` stripped, falling back to `dim_<millis % 10000>` if nothing is left. You do
not choose it.

Some body fields are applied **before** world creation because they affect generation —
`seed`, `flatLayers`, `generateStructures` — and the rest afterwards. That split is why the
creation path uses a configurator callback rather than setting everything on the returned
object.

`POST /dimensions/:id/toggle` flips one boolean, named by `key`. Accepted keys: `pvp`,
`mobSpawning`, `keepInventory`, `clearInventory`, `constructionMode`, `perPlayerMode`,
`commandBlocks`, `enderChest`, `generateStructures`, `cobblemonBlocked`, `enabled`,
`timeLocked`, `weatherLocked`, `allowHome`, `raidDensEnabled`, `borderEnabled`, plus
`flag.<flagId>` for a dimension-level denied flag. A missing `key` gives `400`; any other
key gives `500 {"error":"java.lang.RuntimeException: Unknown toggle key: <key>"}`.

The per-player routes address a sub-dimension by the first 8 hex characters of the owner's
UUID, validated against `[0-9a-f]{8}`. An id that does not resolve gives
`404 {"error":"Player dimension not found for uuid8: <uuid8>"}`.

Built-in dimensions such as `minecraft:overworld` are listed and readable, but their ids
contain a colon, which is not a legal Windows path character. The summary builder skips
snapshot and per-player lookups for them rather than constructing a path that cannot exist.
Do not expect snapshot routes to work on a built-in dimension.

---

## Events

`EventsApi`, 696 lines. The model and the lifecycle are in [Events](events.md); the
timeline inside an event is in [Timeline](timeline.md).

| Method | Path | Body | Response | Statuses | Role |
|---|---|---|---|---|---|
| `GET` | `/events` | — | array of summaries | `200` | any |
| `POST` | `/events` | `{name?}` or `{templateName}` | event detail | `200` `404` `500` | `OPERATOR` (core) |
| `GET` | `/events/:id` | — | event detail | `200` `400` `404` | any |
| `PUT` | `/events/:id` | partial event fields | event detail | `200` `400` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/events/:id` | — | `{"deleted":true}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `POST` | `/events/:id/announce` | — | event detail | `200` `404` `500` | `OPERATOR` (core) |
| `POST` | `/events/:id/start` | — | event detail | `200` `404` `500` | `OPERATOR` (core) |
| `POST` | `/events/:id/stop` | — | event detail | `200` `404` `500` | `OPERATOR` (core) |
| `GET` | `/events/:id/participants` | — | array, score-descending | `200` `404` | any |
| `POST` | `/events/:id/participants` | `{playerName, role?}` | `{"added":true}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/events/:id/participants/:uuid` | — | `{"removed":true}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `POST` | `/events/:id/score` | `{playerName, amount, operation?}` | `{"updated":true}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `POST` | `/events/:id/save-template` | `{name?}` | `{"saved":true,"name":"..."}` | `200` `500` | `OPERATOR` (core) |
| `GET` | `/events/templates` | — | `[{"name":"..."}]` | `200` | any |
| `POST` | `/events/templates/:name/load` | — | event detail | `200` `400` `404` `500` | `OPERATOR` (core) |
| `DELETE` | `/events/templates/:name` | — | `{"deleted":true}` | `200` `400` `500` | `OPERATOR` (core) |
| `GET` | `/events/minigame-types` | — | the mini-game catalogue | `200` | any |
| `GET` | `/events/launch-config` | — | launch config | `200` | any |
| `PUT` | `/events/launch-config` | launch config fields | launch config | `200` | `OPERATOR` (core) |
| `GET` | `/events/prophunt/icons` | — | `[{"key":"..."}]` | `200` | any |
| `GET` | `/events/prophunt/icons/:key` | — | **PNG bytes** | `200` `400` `404` | any |
| `POST` | `/events/prophunt/icons/:key` | `{imageBase64}` | `{"uploaded":true}` | `200` `400` | `OPERATOR` (core) |
| `DELETE` | `/events/prophunt/icons/:key` | — | `{"deleted":true}` | `200` `400` | `OPERATOR` (core) |

`POST /events` behaves differently depending on the body. With `templateName` it resolves
the template and returns the resulting event **without registering or saving it** — a
preview. With `name` (or nothing, defaulting to `Nouvel evenement`) it creates, registers
and saves a new event. To load a template *for real*, use
`POST /events/templates/:name/load`.

`PUT /events/:id` is a patch, except for `kitEntryCommands`, `kitExitCommands`,
`blacklistItems` and `acts`, which are cleared and refilled when present. An invalid enum
value throws inside the server-thread task, surfaces as a `500`, and leaves the event
partially patched in memory — not saved by this request, but written by the next save.

`POST /events/:id/start` refuses an event that is already running
(`500 {"error":"Event already running"}`); `stop` refuses one that is not
(`500 {"error":"Event not running"}`); `announce` refuses one already running. There is no
pause route, because `ServerEvent.State` has no `PAUSED` — see
[Events](events.md#lifecycle).

`POST /events/:id/participants` requires the player to be **online**:
`500 {"error":"Player not online: <name>"}`. A join outside the `ANNOUNCED`, `COUNTDOWN` or
`ACTIVE` states fails with `500 {"error":"Failed to add participant"}`.

`POST /events/:id/score` takes `operation` of `add` (the default), `subtract` or `set`. An
unrecognised value silently behaves as `add`. The target is matched by name,
case-insensitively, among existing participants; a non-participant gives
`500 {"error":"Player not a participant"}`.

`/events/:id/participants` is backed by a `Map<UUID, EventParticipant>`. In Java,
`EventsApi.getParticipants()` returns a **`Map`**, not a `List` — iterate `.values()`.

PropHunt icon keys are validated against `[a-z0-9_]+` and must be under 128 characters:
`400 {"error":"Invalid key"}`. The upload body takes either a bare base64 string or a full
`data:image/png;base64,...` URI — anything before the first comma is stripped. `GET` on an
icon returns `image/png` with `Cache-Control: public, max-age=60`, not JSON. An unsupported
method on an icon key answers `405 {"error":"Method not allowed"}` — one of the module's
two `405`s, the other being `/races/:eventId/track`.

---

## Players

`PlayersApi`. Behaviour and caveats are in [Players](players.md).

| Method | Path | Body / params | Response | Statuses | Role |
|---|---|---|---|---|---|
| `GET` | `/players` | — | array of online players | `200` `503` | any |
| `GET` | `/players/offline` | `?n=1..100`, default 20 | array of offline players | `200` | any |
| `GET` | `/players/:name` | — | detail, or a stub when offline | `200` | any |
| `POST` | `/players/:name/kick` | `{message?}` | `{"kicked":true}` | `200` `404` `500` | `OPERATOR` (core) |
| `POST` | `/players/:name/tp` | `{x?,y?,z?,dimension?}` | `{"teleported":true}` | `200` `404` `500` | `OPERATOR` (core) |
| `POST` | `/players/:name/gamemode` | `{mode}` | `{"changed":true}` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `POST` | `/players/:name/message` | `{text, type?}` | `{"sent":true}` | `200` `400` `404` `500` | `OPERATOR` (core) |

`:name` is a player **name**, URL-decoded. `GET /players/:name` never answers `404` — an
unknown name returns `{"name":"...","online":false}`. The four action routes do answer
`404 {"error":"Player not online"}`.

`mode` must be `SURVIVAL`, `CREATIVE`, `ADVENTURE` or `SPECTATOR` after upper-casing;
anything else is `500 {"error":"Invalid gamemode: <value>"}`. `type` on the message route is
`chat`, `actionbar`, `title` or `subtitle`, with anything unrecognised falling back to
`chat`.

---

## Chunks

`ChunksApi`. The semantics, the backup contract and the region-file granularity are in
[Chunks](chunks.md).

| Method | Path | Body | Response | Statuses | Role |
|---|---|---|---|---|---|
| `GET` | `/chunks/sets` | — | array of set summaries | `200` `503` | any |
| `POST` | `/chunks/sets` | `{name, dimension?, chunks?}` | `{"created":true,"name":"..."}` | `200` `400` `500` | `OPERATOR` (core) |
| `GET` | `/chunks/sets/:name` | — | set detail with entries | `200` `404` | any |
| `DELETE` | `/chunks/sets/:name` | — | `{"deleted":true}` | `200` `500` | `OPERATOR` (core) |
| `DELETE` | `/chunks/sets/:name/entry` | `{x, z, radius}` | `{"removed":true}` | `200` `400` `500` | `OPERATOR` (core) |
| `POST` | `/chunks/sets/:name/reset` | — | a `DeletionResult` | `200` `400` `404` `500` | `OPERATOR` (core) |
| `PUT` | `/chunks/sets/:name/schedule` | `{trigger, dimension?, hour?, minute?, days?}` | the schedule | `200` `400` `404` | `OPERATOR` (core) |
| `GET` | `/chunks/suggestions` | — | array of suggestions | `200` | any |
| `DELETE` | `/chunks/suggestions/:id` | — | `{"dismissed":true}` | `200` `500` | `OPERATOR` (core) |
| `POST` | `/chunks/suggestions/:id/accept` | `{setName, radius?}` | `{"accepted":true,"setName":"..."}` | `200` `400` `409` `500` | `OPERATOR` (core) |

Set names are URL-decoded and used as the storage key directly, with no id validation — a
name with a slash in it cannot be addressed, because the router splits on the first `/`.

⚠️ `POST /chunks/sets/:name/reset` **deletes region files from the world save**. It is
protected by `OPERATOR`, like every other mutation here, because the core dispatcher knows
only that bar. A pre-deletion backup is mandatory and verified, and the deletion is aborted
if the backup fails — but there is no role-level distinction between resetting the world and
renaming a chunk set.

`POST /chunks/suggestions/:id/accept` is the one route in the module that returns `409`
through a message check: a dimension mismatch between the suggestion and the target set is
turned into `409`, everything else into `500`.

`DELETE /chunks/sets/:name/entry` needs all three of `x`, `z` and `radius`, because entry
equality is on that triple. Getting the radius wrong gives
`500 {"error":"Entry not found in set"}`.

---

## Races

`RacesApi`. Scanning, the heightmap and the track settings are in [Races](races.md).

| Method | Path | Body | Response | Statuses | Role |
|---|---|---|---|---|---|
| `POST` | `/races/scan` | `{zoneId, blockMappings, eventId?}` | a `ScanResult` | `200` `400` `500` | `OPERATOR` (`requireWrite`) |
| `POST` | `/races/heightmap` | `{zoneId}` | `{width, height, minX, minZ, image}` | `200` `400` `500` | `OPERATOR` (core) |
| `GET` | `/races/:eventId/track` | — | the track config | `200` `400` | any |
| `PUT` | `/races/:eventId/track` | partial track settings | the track config | `200` `400` | `OPERATOR` (`requireWrite`) |
| `DELETE` | `/races/:eventId/track` | — | `{"ok":true}` | `200` `400` | `OPERATOR` (`requireWrite`) |

Three of these guard themselves properly, as of 29 October 2026:

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

Before that date the same calls discarded the return value, which sent a `403` and then did
the work anyway. `POST /races/heightmap` still has no guard of its own and relies on the
core, as marked.

`GET /races/:eventId/track` returns an **empty default config** rather than a `404` when no
track has been scanned for that event, because "not configured yet" is the normal state. A
`PUT` to an event with no config creates one.

`/races/:eventId/track` is matched by the regex `/races/[^/]+/track`, and the event id is
then validated with the standard helper. A method other than `GET`, `PUT` or `DELETE` on
that path answers `405`.

---

## Prison

`PrisonApi`. Behaviour is in [Prison](prison.md).

| Method | Path | Body | Response | Statuses | Role |
|---|---|---|---|---|---|
| `GET` | `/prison` | — | `{active, history, config}` | `200` `503` | any |
| `GET` | `/prison/active` | — | active entries with `remainingMs` | `200` | any |
| `GET` | `/prison/history` | — | released entries, newest first | `200` | any |
| `GET` | `/prison/config` | — | zone id, welcome message, release point | `200` | any |
| `PUT` | `/prison/config` | `{prisonZoneId?, welcomeMessage?, releasePoint?}` | the config | `200` `400` | `OPERATOR` (`requireWrite`) |
| `POST` | `/prison` | `{uuid\|name, durationMs\|duration, reason?}` | the new entry | `200` `400` `404` `500` | `OPERATOR` (`requireWrite`) |
| `PUT` | `/prison/:uuid` | `{endsAt\|durationMs\|duration\|extendMs}` | the updated entry | `200` `400` `404` | `OPERATOR` (`requireWrite`) |
| `DELETE` | `/prison/:uuid` | — | `{"released":true}` | `200` `500` | `OPERATOR` (`requireWrite`) |

This is the API to copy if you are adding a ninth: every mutating route tests
`requireWrite`'s return value, the reads are open, and the write paths validate before
touching state.

`PUT /prison/config` validates `prisonZoneId` against the zone storage and answers
`400 {"error":"Zone not found: <id>"}`. A `releasePoint` of `null` clears it; an object
needs all four of `world`, `x`, `y`, `z`, with optional `yaw` and `pitch`, and an object
missing any of the four is **ignored silently** rather than refused.

`POST /prison` refuses to work without a configured prison zone
(`400 {"error":"Prison zone not configured"}`), requires a positive duration
(`400 {"error":"Duration is required and must be > 0"}`), and resolves the player through
the online list then the user cache (`404 {"error":"Player not found"}`).

`PUT /prison/:uuid` on someone not currently imprisoned gives `404 {"error":"Not
imprisoned"}`, and an `endsAt` in the past gives `400 {"error":"endsAt must be in the
future"}`.

A malformed JSON body on any prison route gives `400 {"error":"Invalid JSON"}` — an **empty**
body is treated as an empty object instead, so a `PUT` with no body is a no-op that returns
the current state.

---

## WebSocket

CheeseGuard publishes to exactly **one** channel: `prison`. Subscribe to it the usual way:

```json
{"type":"subscribe","channels":["prison"]}
```

| `type` | Sent when |
|---|---|
| `prison_added` | a sentence starts, from either the command or the API |
| `prison_released` | a sentence expires or is pardoned |
| `prison_updated` | `endsAt` is adjusted through `PUT /prison/:uuid` |

The payload carries the full `PrisonEntry` under `entry`, plus flattened `playerName`,
`reason`, `durationMs`, `duration`, `byName`, `releaseReason` and `releasedByName` at the top
level for `DiscordWebhookManager` to pick up:

```json
{
  "type": "prison_added",
  "entry": { "playerUuid": "...", "playerName": "Steve", "reason": "griefing", "startedAt": 1761700000000, "durationMs": 7200000, "endsAt": 1761707200000, "issuedBy": "dashboard:roicheese", "issuedByName": "roicheese" },
  "playerName": "Steve",
  "reason": "griefing",
  "durationMs": 7200000,
  "duration": "2h",
  "byName": "roicheese"
}
```

**Nothing else is broadcast.** There is no `event_update`, no `dim_update`, no
`snapshot_done` coming from this mod: `DashboardRegistry.broadcast` appears exactly once in
the whole codebase, in `PrisonListener`. Channel names are free-form strings in CheeseDash's
WebSocket server, so a dashboard view can subscribe to `events` or `dimensions` without
error — it will simply never receive anything. Those views poll their `GET` routes instead,
and so should anything you build.

---

← [Races](races.md) · [Architecture](architecture.md) →
