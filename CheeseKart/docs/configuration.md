[← CheeseKart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

Everything CheeseKart stores lives in one file:

```text
config/cheesekart.json
```

There are no global settings — no master toggle, no limits, no cooldowns. The file is
nothing but a list of presets, and a preset is a bundle of physics values plus two
behaviour flags. Starting a kart means picking one of these bundles and pushing it to a
player's client.

The file is created on first start if it is missing or contains no presets, seeded with
the three stock presets described [below](#the-three-stock-presets). It is rewritten
whenever a preset is created, updated or deleted from the dashboard or the API — no
command edits presets — and it is written with pretty-printing so it stays readable after the mod has
touched it.

> ⚠️ The file is read **once**, at server start. Editing it by hand while the server is
> running has no effect, and the next preset change made from the dashboard will
> overwrite your edits from memory. Edit presets through the dashboard, or stop the
> server first.

---

## Shape of the file

```json
{
  "presets": [
    { "id": "rally", "name": "Rally", "...": "..." },
    { "id": "drift", "name": "Drift", "...": "..." }
  ]
}
```

A top-level object with a single `presets` array. A preset whose `id` key is missing is
dropped silently while loading. An explicit `"id": null` is worse: it throws, which aborts
the whole load with `[CheeseKart] Failed to load config: …` and keeps only the presets read
before it. Whenever a load ends with zero presets — that error on the first entry, a
missing `presets` array, every entry without an `id` — the three stock presets are seeded
and **saved over your file**. Every unknown key is ignored — so adding
your own notes field to a preset is harmless, but it will be erased the next time the
mod saves the file.

---

## Preset fields

Sixteen fields. Twelve of them are physics values forwarded to OpenBoatUtils, two are
identity, two change how the session behaves.

> The **types, defaults and stock values** below come straight out of `KartPreset.java`
> and are exact. The **descriptions of what each value feels like** are a different
> matter: CheeseKart only transmits these numbers, and OpenBoatUtils is what interprets
> them. Those notes come from driving the presets, not from reading OBU's source, so
> treat them as a tuning guide rather than a specification. If OBU changes how it reads
> a setting, the number stays correct and the description may not.

### Identity

| Key | Type | Default | What it does |
|---|---|---|---|
| `id` | string | — | The handle used by commands, the API and the save file. Required. The dashboard enforces `[a-z0-9_-]+`; a hand-written file is not checked, so keep to that shape yourself |
| `name` | string | — | Display label, shown in `/cheesekart presets` and in the dashboard. Free text |

### Grip and drive

| Key | Type | Default | What it does |
|---|---|---|---|
| `slipperiness` | float | `0.98` | How little the kart sheds speed per tick. Below 1 the kart grips; at 1 it keeps its speed; above 1 it accelerates on its own and slides through corners |
| `forwardAccel` | float | `0.04` | Acceleration while holding forward. The single biggest lever on how fast a track feels |
| `backwardAccel` | float | `0.005` | Acceleration in reverse. Kept an order of magnitude below forward in every stock preset, so reverse is a recovery move and not a driving style |
| `accelStacking` | boolean | `false` | When true, acceleration adds onto current speed instead of being capped against it. Produces no top speed at all. Leave it off unless that is the effect you want |

`slipperiness` is the value to reach for first. `0.98` is firm tarmac; `1.1` — the
`drift` preset — is loose enough that the kart carries momentum through a corner instead
of scrubbing it off. Past roughly `1.15` a kart stops being driveable in a straight
line, because it gains more speed than the player can steer against.

### Steering

| Key | Type | Default | What it does |
|---|---|---|---|
| `yawAccel` | float | `1.0` | How fast the kart rotates when steering. `2.0`, as in `drift`, turns almost twice as sharply |
| `turnForwardAccel` | float | `0.005` | Extra forward acceleration applied while turning. Small positive values keep a kart from dying in a hairpin |

High `yawAccel` with high `slipperiness` is what makes a drift track: the kart rotates
quickly but keeps travelling the way it was already going.

### Vertical behaviour

| Key | Type | Default | What it does |
|---|---|---|---|
| `stepHeight` | float | `1.25` | How tall a block the kart climbs without jumping. `1.25` clears a full block with a quarter-block margin (not a block plus a slab, which is `1.5`); `2.0` — the `parkour` preset — walks up two-block steps |
| `gravity` | double | `-0.04` | Downward acceleration. **Negative is down.** Vanilla boats are near `-0.04`; `-0.03` gives longer, floatier jumps |
| `jumpForce` | float | `0.0` | Upward impulse on jump. At `0.0` the kart cannot jump at all, which is correct for a ground race |
| `airControl` | boolean | `true` | Whether steering still works mid-air. Off makes a jump commit the racer to their trajectory |
| `airStepping` | boolean | `true` | Whether `stepHeight` applies while airborne, letting a kart catch a ledge on the way past |
| `fallDamage` | boolean | `false` | Whether a hard landing hurts. Off in every stock preset — a race ending in a death screen is not a race |

Setting a positive `gravity` makes the kart rise continuously. It is not useful, but
nothing stops you.

### Session behaviour

| Key | Type | Default | What it does |
|---|---|---|---|
| `locked` | boolean | `true` | Whether the racer is held in the kart. See [locked mode](physics.md#locked-mode) |
| `species` | string | `""` | A Cobblemon species the racer is transformed into for the session, through CobbleShift. Empty means no transform. The `species` argument of `/cheesekart start` overrides this |

`locked` deserves a moment. With it on — the default — pressing Shift does nothing: the
client-side dismount is cancelled, and if the player gets out anyway the server
re-mounts them on the next tick. With it off, dismounting ends the session cleanly. Use
`locked: true` for a timed race nobody should be able to walk out of, and `false` for a
free-roam kart someone can park.

---

## The three stock presets

Written on first start, and a reasonable starting point for your own. Any field not
listed here sits at its default from the table above.

### `rally` — firm grip, predictable

```json
{
  "id": "rally",
  "name": "Rally",
  "slipperiness": 0.98,
  "stepHeight": 1.25,
  "gravity": -0.04,
  "forwardAccel": 0.04,
  "backwardAccel": 0.005,
  "yawAccel": 1.0,
  "airControl": true,
  "fallDamage": false
}
```

The baseline. Grips, turns at a sane rate, climbs a single block.

### `drift` — loose and fast

```json
{
  "id": "drift",
  "name": "Drift",
  "slipperiness": 1.1,
  "stepHeight": 1.25,
  "gravity": -0.04,
  "forwardAccel": 0.06,
  "backwardAccel": 0.005,
  "yawAccel": 2.0,
  "airControl": true,
  "fallDamage": false
}
```

Half again the acceleration, double the steering rate, and slipperiness past 1 so speed
survives a corner. Rewards a racer who knows the track; punishes one who does not.

### `parkour` — climbs and jumps

```json
{
  "id": "parkour",
  "name": "Parkour",
  "slipperiness": 0.98,
  "stepHeight": 2.0,
  "gravity": -0.03,
  "forwardAccel": 0.04,
  "backwardAccel": 0.005,
  "yawAccel": 1.0,
  "jumpForce": 0.6,
  "airControl": true,
  "airStepping": true,
  "fallDamage": false
}
```

The only stock preset that can jump. Two-block steps, lighter gravity and air stepping
turn a kart into something that can take a vertical course.

---

## A complete annotated file

Copy this as a starting point. The comments are **not** valid JSON — strip them before
use; they are here to be read, not parsed.

```json
{
  "presets": [
    {
      "id": "ice-sprint",            // handle used by /cheesekart start
      "name": "Ice Sprint",          // shown in listings

      "slipperiness": 1.12,          // very loose: carries speed through corners
      "forwardAccel": 0.07,          // fast
      "backwardAccel": 0.005,        // reverse stays a recovery move
      "accelStacking": false,        // keep a top speed

      "yawAccel": 2.2,               // sharp steering to fight the slide
      "turnForwardAccel": 0.008,     // do not die in hairpins

      "stepHeight": 1.25,            // one full block
      "gravity": -0.04,              // standard
      "jumpForce": 0.0,              // ground race, no jumping
      "airControl": true,
      "airStepping": false,          // a jump commits you
      "fallDamage": false,           // never kill a racer

      "locked": true,                // held in the kart until an admin stops it
      "species": ""                  // no CobbleShift transform
    }
  ]
}
```

With the server running, the same preset is created from the dashboard or with one
request — and that route validates the id and refuses a duplicate, while a later `PUT`
applies changes to live sessions. See [dashboard API](dashboard.md#post-presets).

---

← [Commands](commands.md) · [Physics and sessions](physics.md) →
