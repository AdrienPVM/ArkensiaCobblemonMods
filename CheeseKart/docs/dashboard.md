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

## What the core actually enforces

Every mutating branch in `KartApi.handle` guards itself in the correct form — it tests the
return value and returns:

```java
} else if (path.equals("/presets") && method.equals("POST")) {
    if (!WebDashboard.requireWrite(exchange)) return;
    handleCreatePreset(exchange);
```

`requireWrite` does not throw: it sends the `403` itself and reports the outcome as a
boolean, so its result has to be acted on, and here it is.

The same boundary is applied a second time, before CheeseKart sees the request at all, by
CheeseDash's module dispatcher in `DashboardApi`:

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

So the roles listed below are enforced: `GET` accepts any role including `READ_ONLY`, and
every other method requires `OPERATOR` or above. The module's own guards are defence in
depth — useful, and redundant with the central one. Three consequences follow from where
the real boundary sits:

- **No route in CheeseKart can require `SUPER_ADMIN`.** The central guard only knows
  `requireWrite`, so `OPERATOR` is the highest bar reachable through it. A route that
  deserved more would have to call `requireAdmin` itself; none does.
- **A mutating route exposed as `GET` would not be guarded centrally at all** — the
  dispatcher keys purely on the HTTP method. None exists here today.
- **A single change to that dispatcher would drop the central check everywhere at once**,
  with no module noticing. On this module the per-branch guards would still hold.

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

The re-application runs on the server thread, and `executeOnServerThread` waits for it
(up to five seconds) before the response is written — so by the time you get the `200`, the
new physics packets have been sent; the karts change as soon as the clients receive them.
The server logs each batch:

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

> ⚠️ `{"started":true}` means the start **ran** on the server thread —
> `executeOnServerThread` waits for the task, up to five seconds — not that it succeeded:
> the `boolean` that `startKart` returns is discarded. A refused force-mount (rare, see
> [troubleshooting](troubleshooting.md#a-start-command-reports-failed-to-start-kart))
> appears in the server log, not in this response. Confirm with `GET /active` if it
> matters. An exception inside the task, or the five-second timeout, comes back as a `500`.

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
each racer gets the preset's own `species`. In a timeline block, a missing
`kartPresetId` falls back to `rally` (`TimelineExecutor` reads it with that default);
elsewhere, a missing or unknown `kartPresetId` is logged by CheeseGuard and the effect
does nothing:

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
