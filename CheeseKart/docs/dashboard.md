[← CheeseKart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

CheeseKart registers itself with CheeseDash through the `cheesedash` Fabric entrypoint,
under the prefix `kart`. Everything below therefore sits at:

```text
http://<server>:44311/api/v1/kart/<route>
```

In the dashboard sidebar it appears as **CheeseKart** 🏎 with the description
*Mode kart et courses*.

## Authentication

Every route needs a CheeseDash session:

```http
Authorization: Bearer <session-token>
```

Get one from `POST /api/v1/auth/login`. Read routes accept any role, including
`READ_ONLY`.

> ⚠️ **The write guard on this module does not actually stop anything.** Every mutating
> route calls `WebDashboard.requireWrite(exchange)` and **discards its return value**.
> That method does not throw — it sends the `403` itself and reports the outcome as a
> boolean. The correct call is `if (!WebDashboard.requireWrite(exchange)) return;`, which
> is what CheeseDash's own routes do. Here, a `READ_ONLY` session receives a `403`
> **and** the action is carried out anyway.
>
> So treat the roles below as the intent, not as an enforced boundary: in this build, any
> authenticated session of any role can start a kart, stop one, or create, edit and delete
> presets. Until it is fixed, do not hand out `READ_ONLY` accounts expecting them to be
> read-only. The same mistake is present in CheeseMusic, CheesePatrol, CobbleCap and
> CheeseGuard's races module.

If CheeseKart has not finished starting — or the server is not up — every route answers
`503` with `{"error":"CheeseKart not ready"}` instead of failing in an unhelpful way.

An unrecognised route under this prefix answers `404` with the path echoed back:
`{"error":"Unknown kart route: /whatever"}`. Any uncaught exception becomes a `500`
carrying the exception message.

---

## Preset routes

### `GET /presets`

Every configured preset, as a JSON array. No parameters. Role: any.

```json
[
  {
    "id": "rally",
    "name": "Rally",
    "slipperiness": 0.98,
    "stepHeight": 1.25,
    "gravity": -0.04,
    "forwardAccel": 0.04,
    "backwardAccel": 0.005,
    "yawAccel": 1.0,
    "turnForwardAccel": 0.005,
    "accelStacking": false,
    "airControl": true,
    "airStepping": true,
    "jumpForce": 0.0,
    "fallDamage": false,
    "locked": true,
    "species": ""
  }
]
```

Every preset always carries all sixteen fields. Field meanings are in
[configuration](configuration.md#preset-fields).

### `GET /presets/{id}`

One preset, same shape as an array element above. Role: any.

| Status | Body |
|---|---|
| `200` | the preset |
| `404` | `{"error":"Preset not found: <id>"}` |

### `POST /presets`

Creates a preset. Role: `OPERATOR` or above.

| Body field | Required | Notes |
|---|---|---|
| `id` | yes | must match `[a-z0-9_-]+` — lowercase letters, digits, hyphen, underscore |
| `name` | yes | free text |
| any other preset field | no | omitted fields take the defaults from [configuration](configuration.md#preset-fields) |

```bash
curl -X POST http://localhost:44311/api/v1/kart/presets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"id":"ice-sprint","name":"Ice Sprint","slipperiness":1.12,"forwardAccel":0.07}'
```

| Status | Body |
|---|---|
| `201` | the created preset, all sixteen fields resolved |
| `400` | `{"error":"Missing id or name"}` |
| `400` | `{"error":"Invalid ID (lowercase alphanumeric, -, _ only)"}` |
| `409` | `{"error":"Preset already exists: <id>"}` |

The config file is written before the response is sent, so a `201` means the preset is
on disk.

### `PUT /presets/{id}`

Updates a preset, and **re-applies it to every session currently using it**. Role:
`OPERATOR` or above.

This is a merge, not a replace: only the fields present in the body are changed, and
everything else keeps its current value. Sending `{"slipperiness": 1.2}` changes grip
and nothing else. `id` cannot be changed this way — delete and recreate instead.

```bash
curl -X PUT http://localhost:44311/api/v1/kart/presets/drift \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"slipperiness": 1.15, "yawAccel": 2.4}'
```

| Status | Body |
|---|---|
| `200` | the full updated preset |
| `404` | `{"error":"Preset not found: <id>"}` |

The re-application is scheduled onto the server thread, so the response can come back
marginally before the racers' karts actually change. In practice this is one tick. The
server logs each batch:

```text
[CheeseKart] Re-applied preset 'drift' to 3 active session(s)
```

Sessions using a *different* preset are untouched, and the `locked` flag of a running
session does not change even if you edit it here — it was copied at session start.

This endpoint is the reason the mod exists in this shape: tune a corner, send a `PUT`,
feel the difference on the next lap without anybody leaving their kart.

### `DELETE /presets/{id}`

Removes a preset. Role: `OPERATOR` or above.

| Status | Body |
|---|---|
| `200` | `{"deleted":true}` |
| `404` | `{"error":"Preset not found: <id>"}` |

Sessions started from that preset keep running — they hold their own copy of the physics
values. They just can no longer be re-tuned, and the preset id in `/active` will no
longer resolve.

---

## Session routes

### `GET /active`

Active sessions, as an array. Role: any. Empty array when nothing is running.

```json
[
  {
    "playerName": "Steve",
    "playerUuid": "f84c6a79-0a4e-45e0-879b-cd49ebd4c4e2",
    "presetId": "drift",
    "species": "pikachu",
    "locked": true,
    "durationMs": 94213,
    "durationFormatted": "1m 34s"
  }
]
```

| Field | Notes |
|---|---|
| `species` | empty string when no transform is active |
| `durationMs` | milliseconds since the session started, computed per request |
| `durationFormatted` | `"45s"` under a minute, `"1m 34s"` above |

### `POST /start`

Puts an online player in a kart. Role: `OPERATOR` or above.

| Body field | Required | Notes |
|---|---|---|
| `playerName` | yes | must be online |
| `presetId` | yes | must exist |
| `species` | no | Cobblemon species; overrides the preset's own `species` |

```bash
curl -X POST http://localhost:44311/api/v1/kart/start \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"playerName":"Steve","presetId":"drift"}'
```

| Status | Body |
|---|---|
| `200` | `{"started":true}` |
| `400` | `{"error":"Missing playerName or presetId"}` |
| `404` | `{"error":"Player not found: <name>"}` |
| `404` | `{"error":"Preset not found: <id>"}` |

> ⚠️ `{"started":true}` means the start was **scheduled onto the server thread**, not
> that it succeeded. The force-mount can still fail — if a player is already riding
> something else, for instance — and that failure appears in the server log, not in this
> response. Confirm with `GET /active` if it matters.

### `POST /stop`

Ends one player's session. Role: `OPERATOR` or above.

| Body field | Required |
|---|---|
| `playerName` | yes |

| Status | Body |
|---|---|
| `200` | `{"stopped":true}` |
| `400` | `{"error":"Missing playerName"}` |
| `404` | `{"error":"Player not found: <name>"}` |

A player who is online but has no session returns `200` — unlike the command, which
tells you they were not in a kart.

### `POST /stopall`

Ends every session. Role: `OPERATOR` or above. No body.

| Status | Body |
|---|---|
| `200` | `{"stoppedAll":true}` |

---

## No WebSocket channel

CheeseKart does not publish to CheeseDash's WebSocket. Kart state changes reach the
*game* clients over the mod's own `cheesekart:sync` payload, which is a different
mechanism entirely and not something a dashboard can subscribe to.

A dashboard view that wants live session data has to poll `GET /active`. That is what
the built-in view does.

---

## Called from CheeseGuard events

Two CheeseGuard effect types reach this mod directly, through a Java hook rather than
over HTTP:

| Effect | Parameters | Behaviour |
|---|---|---|
| `KART_START_ALL` | `kartPresetId`, `kartSpeciesOverride` | puts every participant of the event into a kart |
| `KART_STOP_ALL` | — | ends every participant's session |

`kartSpeciesOverride` plays the same role as the command's third argument: left blank,
each racer gets the preset's own `species`. A missing or unknown `kartPresetId` is
logged by CheeseGuard and the effect does nothing:

```text
[CG] KART_START_ALL: no presetId specified
[CG] KART_START_ALL: preset not found: <id>
```

CheeseGuard reaches CheeseKart by reflection, the same way CheeseKart reaches
CobbleShift, so neither mod requires the other at compile time.

A race event therefore starts for everybody on the same tick, which is what you want for
a timed race and what a sequence of individual API calls cannot give you.

---

← [Physics and sessions](physics.md) · [Architecture](architecture.md) →
