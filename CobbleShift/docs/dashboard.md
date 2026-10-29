[← CobbleShift](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

CobbleShift registers a CheeseDash module through the `cheesedash` entrypoint declared in
`fabric.mod.json`. `CobbleShiftDashboardModule.getPrefix()` returns `shift`, so every
route below is reachable at `/api/v1/shift<subRoute>` on CheeseDash's HTTP port, **44311**.

The module's sidebar entry is named `CobbleShift` with the description
`Transformations et mini-jeux`, which is hardcoded French in `getNavDescription()` and not
run through i18n.

Authentication is CheeseDash's, not this mod's. `POST /api/v1/auth/login` returns a session
token; every call below needs `Authorization: Bearer <token>`.

---

## Required roles

CheeseDash has three roles: `READ_ONLY`, `OPERATOR`, `SUPER_ADMIN`. A handler declares what
it needs by calling a guard **and returning on failure**:

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

That form matters. `requireWrite` and `requireAdmin` return a boolean and send the 403
themselves; they do not throw. A handler that calls `WebDashboard.requireWrite(exchange)`
and ignores the result performs the write anyway, and the client gets two responses. Every
guarded handler in `ShiftApi` uses the correct guarded-return form — all nine of them were
checked line by line for this page.

| Guard present | Role needed |
|---|---|
| none | any authenticated session, `READ_ONLY` included |
| `requireWrite` | `OPERATOR` or `SUPER_ADMIN` |
| `requireAdmin` | `SUPER_ADMIN` only |

Applied to the fifteen routes:

| Method | Path | Guard in code | Role |
|---|---|---|---|
| `GET` | `/transforms` | none | any |
| `POST` | `/transform` | `requireWrite` | OPERATOR |
| `POST` | `/revert` | `requireWrite` | OPERATOR |
| `POST` | `/revertall` | `requireWrite` | OPERATOR |
| `GET` | `/abilities` | none | any |
| `POST` | `/ability` | `requireWrite` | OPERATOR |
| `POST` | `/ability/global` | `requireAdmin` | **SUPER_ADMIN** |
| `POST` | `/ability/toggle` | `requireWrite` | OPERATOR |
| `GET` | `/ability/disabled` | none | any |
| `GET` | `/species` | none | any |
| `GET` | `/species/:name` | none | any |
| `GET` | `/config` | none | any |
| `PUT` | `/config` | `requireAdmin` | **SUPER_ADMIN** |
| `POST` | `/scale` | `requireWrite` | OPERATOR |
| `POST` | `/speed` | `requireWrite` | OPERATOR |

Two routes are SUPER_ADMIN: the global ability switch and the config update. Both write
`config/cobbleshift.json` to disk, which is the line the mod draws between an operator
action and an admin one. Note the asymmetry with the commands: in game, `/cshift ability
global` needs only permission level 2, the same as every other subcommand.

Anything unmatched returns `404 {"error":"Route not found"}`. An exception anywhere in the
dispatch is caught and returned as `500` with the exception message in a `detail` field —
that message is escaped by hand, not by Gson, so treat it as diagnostic output rather than
something to parse.

If the module is loaded but the server reference is null — during startup, or after
shutdown — the module answers `503 {"error":"CobbleShift not ready"}` before `ShiftApi`
ever sees the request.

---

## Transforms

### `GET /api/v1/shift/transforms`

Players and NPCs in one response.

```json
{
  "players": [
    {
      "playerUuid": "…",
      "playerName": "Steve",
      "species": "charizard",
      "type1": "fire",
      "type2": "flying",
      "bst": 534,
      "shiny": false,
      "transformedAt": 1730200000000,
      "locked": false
    }
  ],
  "npcs": [
    {
      "npcUuid": "…",
      "species": "snorlax",
      "shiny": false,
      "form": "…",
      "type1": "normal",
      "scale": 1.0,
      "speed": 1.0
    }
  ]
}
```

The player objects come from `TransformData.toJson()`; `form` and `type2` are omitted when
null rather than sent as `null`. `customScale` appears only once `/cshift scale` or
`POST /scale` has set a value other than `1.0`. `locked` is always `false` (the field is
never set), and the object carries neither the `noattacks` state nor a custom speed.
Always `200`.

### `POST /api/v1/shift/transform`

```json
{ "playerName": "Steve", "species": "charizard" }
```

Both fields are required. The call runs `transform(player, species)` — the two-argument
overload, so **shiny and form cannot be requested over the API**. Only the command grammar
exposes those. The mutation is wrapped in `WebDashboard.executeOnServerThread`.

| Status | Body |
|---|---|
| `200` | `{"transformed":true,"player":"Steve","species":"charizard"}` |
| `400` | `{"error":"playerName and species required"}` |
| `400` | `{"error":"Transform failed — species not found or Cobblemon unavailable"}` |
| `404` | `{"error":"Player not online: Steve"}` |
| `500` | `{"error":"Server thread error: …"}` |

The `400` failure also covers the transform cooldown, which returns `false` from
`transform()` with no distinct signal. The player sees the cooldown message in game; the
API caller sees "species not found".

### `POST /api/v1/shift/revert`

```json
{ "playerName": "Steve" }
```

`200` → `{"reverted":true,"player":"Steve"}`. `400` when the player has no active
transform, `404` when they are offline.

### `POST /api/v1/shift/revertall`

No body. `200` → `{"reverted": 4}`, the count of transforms that were active before the
sweep. NPC transforms are untouched.

---

## Abilities

### `GET /api/v1/shift/abilities`

An array, one object per active ability. Each is `AbilityData.toJson()` plus three
computed fields.

```json
[
  {
    "playerUuid": "…",
    "species": "gengar",
    "type1": "ghost",
    "type2": "poison",
    "forced": true,
    "activatedAt": 1730200000000,
    "playerName": "Steve",
    "abilityName": "Ombre Furtive",
    "disabled": false
  }
]
```

`playerName` is `"offline"` when the UUID resolves to nobody online. `abilityName` is the
French display name from the `AbilityType` enum. This endpoint is the only place the API
joins a UUID back to a player name, which is what `/cshift list` fails to do.

### `POST /api/v1/shift/ability`

```json
{ "playerName": "Steve", "species": "gengar" }
```

With a non-blank `species`, forces the ability. Omit `species` or send it blank to clear
the force.

| Status | Body |
|---|---|
| `200` | `{"forced":true,"player":"Steve","species":"gengar"}` |
| `200` | `{"cleared":true,"player":"Steve"}` |
| `400` | `{"error":"playerName required"}` |
| `404` | `{"error":"Player not online: Steve"}` |

The species is not validated. An unknown name is stored and resolves to the `normal` type,
which grants nothing.

### `POST /api/v1/shift/ability/global` — SUPER_ADMIN

```json
{ "enabled": true }
```

A missing `enabled` field counts as `false`: the check is `data.has("enabled") &&
data.get("enabled").getAsBoolean()`, so `POST {}` **disables** the subsystem. Writes the
config and, on disable, schedules `deactivateAllAndSync()` on the server thread.

`200` → `{"abilityGlobalEnabled": true}`.

### `POST /api/v1/shift/ability/toggle`

```json
{ "playerName": "Steve" }
```

`200` → `{"player":"Steve","disabled":true}`, where `disabled` is the state **after** the
flip. `400` without `playerName`, `404` when offline.

This handler mutates `disabledPlayers` directly from the HTTP thread rather than through
`executeOnServerThread`. The set is a `ConcurrentHashMap.newKeySet()`, so it is safe, but
`togglePlayer` also sends a packet from that thread. Flagged in
[architecture](architecture.md#threading).

### `GET /api/v1/shift/ability/disabled`

```json
[ { "uuid": "…", "name": "Steve" } ]
```

`name` is `"offline"` for a UUID with no online player.

---

## Species

### `GET /api/v1/shift/species`

Not a listing, despite the path.

```json
{
  "cobblemonAvailable": true,
  "note": "Use /species/:name to query individual species. Full list requires Cobblemon registry access."
}
```

`SpeciesMapper.getAllSpeciesNames()` does exist and does return the full sorted list — the
command's tab completion uses it. This endpoint predates that method and was never updated.
Treat it as a Cobblemon-present probe.

### `GET /api/v1/shift/species/:name`

Matched by `subRoute.matches("/species/[^/]+")`, so the name may not contain a slash. No
other validation: the name goes straight into the reflective lookup.

```json
{
  "name": "charizard",
  "type1": "fire",
  "type2": "flying",
  "hp": 78, "atk": 84, "def": 78, "spAtk": 109, "spDef": 85, "speed": 100,
  "height": 17.0,
  "mappedSpeed": 0.125,
  "mappedHealth": 15.2,
  "mappedScale": 2.5,
  "bst": 534,
  "attackDamage": 6.36,
  "attackCooldown": 32,
  "projectileCount": 3,
  "ability1Name": "Flamme Intérieure",
  "ability2Name": "Plume Légère"
}
```

⚠️ `mappedScale` here is wrong. The handler calls `mapScale(stats.height())`, passing the
Pokédex height in **decimetres**, while a transform calls `mapScale(effectiveHitboxHeight)`
in blocks. Since `mapScale` is capped at 2.5, almost every species reports `2.5` from this
endpoint. The in-game `/cshift species` output does not have this bug. Do not use the API
value to predict a transform's size.

`404` → `{"error":"Species not found: <name>"}`, which with Cobblemon absent is every name.

---

## Config

### `GET /api/v1/shift/config`

The full config object, exactly `CobbleShiftConfig.toJson()` — all sixteen keys, documented
in [configuration](configuration.md). No guard, so a `READ_ONLY` session can read it.

### `PUT /api/v1/shift/config` — SUPER_ADMIN

A partial object. Each key is applied only if present, through the setter, so the clamps in
[configuration](configuration.md#ranges-and-clamps) apply. The whole file is then saved.

```json
{ "abilityGlobalEnabled": true, "abilityTickInterval": 60, "abilityEffectDuration": 120 }
```

`200` → `{"saved":true}`.

⚠️ Two keys are **not** accepted here: `hotPotatoEnabled` and `hotPotatoPassRange`. They
exist in the config class and in the file, and `GET /config` returns them, but the `PUT`
handler has no branch for them. Sending them is silently ignored. They are vestigial —
[configuration](configuration.md#keys-that-do-nothing) explains why.

Changes apply on the next refresh tick. Nothing needs a restart.

Turning `abilityGlobalEnabled` off here is not the same as `POST /ability/global` or
`/cshift ability global disable`: this handler only calls the setter and never runs
`deactivateAllAndSync()`. Effects stop being refreshed and run out, but the active
abilities stay in memory and in `GET /abilities`, and clients keep showing the ability
HUD.

---

## Scale and speed

### `POST /api/v1/shift/scale`

```json
{ "uuid": "…", "scale": 1.5 }
```

Keyed by UUID, not by name, and it dispatches on what that UUID is: a transformed player
first, then a transformed NPC. `scale` is clamped to `[0.1, 10.0]` and defaults to `1.0`
when absent.

```json
{ "type": "player", "uuid": "…", "scale": 1.5, "success": true }
```

`type` is `"player"` or `"npc"`. `400` for a missing or malformed UUID, `404` for a UUID
that is neither.

### `POST /api/v1/shift/speed`

```json
{ "uuid": "…", "speed": 2.0 }
```

**NPCs only.** Despite the symmetry with `/scale`, this route never touches a player: it
checks `npcMgr.isNpcTransformed(uuid)` and returns `404 {"error":"UUID is not a transformed
NPC"}` otherwise. Player speed is reachable only through `/cshift speed`.

Clamped to `[0.1, 10.0]`. `200` → `{"uuid":"…","speed":2.0,"success":true}`.

---

## WebSocket

**CobbleShift publishes nothing over WebSocket.** There is no `DashboardRegistry.broadcast`
call anywhere in the mod, no channel registered, and no `shift` message type. A dashboard
view showing transforms has to poll `GET /transforms`.

The mod does send its own Minecraft packets — `TransformSyncPayload`,
`AbilitySyncPayload`, `AnimationStateSyncPayload` to clients and `AttackAbilityPayload`
from them — but those travel on the game connection, not CheeseDash's port 44312. They are
described in [architecture](architecture.md#network-payloads).

---

← [NPC transforms](npc-transforms.md) · [Architecture](architecture.md) →
