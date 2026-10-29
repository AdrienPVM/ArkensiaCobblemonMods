[← CheeseKart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this page you will have put a player in a kart, built a preset of your
own, and re-tuned it while that player was still driving — which is the workflow the
mod is designed around.

You need a running server with CheeseKart and CheeseDash installed
([installation](installation.md)), operator rights at permission level 2, and a second
player to drive. You can use your own alt; what you cannot easily do is drive and watch
the numbers at the same time.

---

## 1. Check what you have

```mcfunction
/cheesekart presets
```

```text
--- Presets (3) ---
  parkour — Parkour | slip=0.98 step=2.0 fwd=0.04 [locked]
  rally — Rally | slip=0.98 step=1.25 fwd=0.04 [locked]
  drift — Drift | slip=1.1 step=1.25 fwd=0.06 [locked]
```

Three presets, seeded on first launch. If you get `No presets configured`, something
emptied `config/cheesekart.json` after startup — see
[troubleshooting](troubleshooting.md#cheesekart-presets-says-no-presets-configured).

## 2. Put somebody in a kart

Stand your racer somewhere with room — a flat stretch of twenty blocks is plenty.

```mcfunction
/cheesekart start Steve rally
```

```text
Kart started for Steve (preset=rally)
```

**The player comes first, then the preset.** Reversed, you get
`Player not found: rally`.

From Steve's side: the camera drops slightly into a sitting position, nothing is
visible underneath him, and holding forward accelerates him smoothly rather than at
walking pace. Pressing Shift does nothing at all — `rally` is `locked`.

If Steve's kart handles like a plain boat, he does not have OpenBoatUtils. The kart still
works, it is just not a kart — [why](physics.md#why-it-has-to-be-on-the-client). If he
can *see* the boat under him, it is CheeseKart that is missing from his client: the
client-side render mixin is what hides it.

## 3. Watch the session

```mcfunction
/cheesekart list
```

```text
--- Active karts (1) ---
  Steve | preset=rally | 23s [locked]
```

No `species=` because `rally` does not set one and you did not pass one.

## 4. Stop it

```mcfunction
/cheesekart stop Steve
```

```text
Kart stopped for Steve
```

Steve is dismounted, the boat is gone, his physics are back to vanilla, and he is
standing where the kart was. The small teleport you may notice is deliberate
([why](physics.md#stopping-a-kart-is-an-ordered-teardown)).

---

## 5. Make your own preset

Stock presets are a starting point, not a design. Build one for an actual track.

Through the dashboard: open the 🏎 **CheeseKart** module, create a preset, fill the
fields. Or with one request — `$TOKEN` is a session token from
`POST /api/v1/auth/login`:

```bash
curl -X POST http://localhost:44311/api/v1/kart/presets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "id": "ice-sprint",
        "name": "Ice Sprint",
        "slipperiness": 1.12,
        "forwardAccel": 0.07,
        "yawAccel": 2.2,
        "turnForwardAccel": 0.008,
        "locked": true
      }'
```

Any field you leave out takes its default, so this is a complete, usable preset:
slippery, fast, sharp-steering, and ground-only because `jumpForce` defaults to `0.0`.

The id must be lowercase letters, digits, hyphens or underscores — `Ice Sprint` as an id
is rejected with a `400`. Re-using an existing id gives a `409` rather than silently
overwriting it.

Confirm it registered:

```mcfunction
/cheesekart presets
```

```text
  ice-sprint — Ice Sprint | slip=1.12 step=1.25 fwd=0.07 [locked]
```

No reload, no restart. The config file on disk was already rewritten.

## 6. Tune it while it is being driven

This is the part worth learning. Put Steve in your new preset:

```mcfunction
/cheesekart start Steve ice-sprint
```

Have him drive a lap and tell you what is wrong with it. Say it is too loose through
corners. Without stopping him:

```bash
curl -X PUT http://localhost:44311/api/v1/kart/presets/ice-sprint \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"slipperiness": 1.05}'
```

The server logs:

```text
[CheeseKart] Re-applied preset 'ice-sprint' to 1 active session(s)
```

Steve's kart changes under him within a tick. He never left it, and nobody restarted
anything. Iterate: drive, adjust one number, drive again. Changing one value at a time
is the only way to tell what a value actually does.

A `PUT` is a **merge** — the fields you send change, the rest keep their values. So the
request above touched grip and nothing else.

## 7. A whole grid at once

For a real race, starting racers one at a time is wrong: the first player off the line
has a head start measured in seconds.

Use a CheeseGuard timed event and give it a `KART_START_ALL` effect with
`kartPresetId` set to your preset. Every participant is put in a kart on the same tick.
`KART_STOP_ALL` ends them together the same way. See
[dashboard API](dashboard.md#called-from-cheeseguard-events).

---

## Where to go next

- Every argument and every failure message → [commands](commands.md)
- All sixteen preset fields, with ranges and interactions → [configuration](configuration.md)
- What a kart actually is, and the OBU packet protocol → [physics and sessions](physics.md)
- Turning racers into Pokémon → [CobbleShift transforms](physics.md#cobbleshift-transforms)

---

← [Installation](installation.md) · [Commands](commands.md) →
