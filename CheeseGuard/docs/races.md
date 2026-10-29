[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](races.fr.md)

# Races

A race is a `RACE`-type mini-game event whose track was built in the world and then
*scanned* into a configuration. You place coloured blocks for the start line, the
checkpoints and the power-ups, tell CheeseGuard which block means what, and it flood-fills
the zone to turn those blocks into regions with bounding boxes and centres. From then on,
crossing a checkpoint is a geometric test against a scanned region, not a zone lookup.

Three pieces: `RacesApi` (`dashboard/RacesApi.java`) exposes scanning and the track config,
`RaceTrackScanner` (`logic/RaceTrackScanner.java`) does the scanning, and
`RaceTrackConfig` (`data/RaceTrackConfig.java`) is the stored result. The race itself runs
inside `MiniGameManager`.

Track configs live one file per event:

```text
config/cheeseguard_racetracks/<eventId>.json
```

## Building a track

1. Create a zone that encloses the whole circuit — see [Zones and flags](zones-and-flags.md).
   The scan is bounded by that zone, so anything outside it is invisible to the race.
2. Place marker blocks. Any block type works; what matters is that each marker role gets
   its own block. A workable set: blue wool for checkpoints, gold block for the start line,
   one coloured block per power-up kind.
3. Scan, with `POST /races/scan`, passing the zone id and the block-to-marker mapping.
4. Order the checkpoints, with `PUT /races/:eventId/track` and a `checkpointOrders` array.
5. Create a `MINIGAME` event with `miniGameType: "RACE"` and the same id as the track
   config, then start it like any other event ([Events](events.md)).

### The mapping

```json
{
  "zoneId": "circuit_arkensia",
  "eventId": "evt_41200",
  "blockMappings": {
    "minecraft:blue_wool": "checkpoint",
    "minecraft:gold_block": "start",
    "minecraft:lime_wool": "boost",
    "minecraft:redstone_block": "trap"
  }
}
```

Two marker names are reserved: `checkpoint` and `start`. **Every other value is treated as a
power-up key**, with no validation — a typo like `"checkpointt"` silently becomes a
power-up type named `checkpointt`. The scan rejects a mapping with no `checkpoint` entry:

```json
{"error":"blockMappings must contain at least one 'checkpoint' mapping"}
```

A block id the registry does not know is skipped with a log line and does not fail the
scan:

```text
[CG] Race scan: unknown block in mappings: minecraft:blue_wolo
```

Passing `eventId` makes the scan save straight into that event's track config. Omitting it
gives you a dry run: the result comes back as JSON and nothing is written.

### What the scan does

`RaceTrackScanner.scan()` runs on the server thread, because it reads block states.
`RacesApi` schedules it and waits up to 30 seconds for the result; past that the request
fails with `500 Scan failed: ...` even if the scan eventually finishes.

The algorithm:

1. Resolve each mapped block id to a `Block` object once, for fast identity comparison.
2. Refuse the zone if its volume exceeds 50 million blocks:
   `Zone too large for scanning: <n> blocks (max 50M). Reduce zone bounds.`
3. Walk every block position in the zone bounds, skipping air, and collect positions whose
   block is in the mapping.
4. Flood-fill those positions with a BFS over the six face neighbours, grouping adjacent
   blocks of the **same** marker type into one `MarkerRegion` with a bounding box and a
   centre.
5. Sort the regions: `checkpoint` groups into the checkpoint list, `start` into the start
   line, everything else into power-ups.
6. Sort checkpoints by centre X, then centre Z, which gives a deterministic but
   geographically arbitrary default order.

Diagonal adjacency does **not** join a group. Two checkpoint blocks touching only at a
corner are two checkpoints. This is usually what you want for a line of separate gates, and
is a trap if you built a diagonal ribbon.

If more than one `start` group is found, the first one wins and the rest are dropped:

```text
[CG] Race scan: multiple start line groups found, using first one
```

The response reports the work done:

```json
{
  "checkpoints": [ { "markerType": "checkpoint", "order": -1, "min": [...], "max": [...], "center": [...] } ],
  "powerups": [ ... ],
  "startLine": { ... },
  "totalBlocksScanned": 2450000,
  "markerBlocksFound": 312,
  "durationMs": 1840,
  "zoneBounds": { "minX": 0, "minY": 60, "minZ": 0, "maxX": 200, "maxY": 80, "maxZ": 200, "world": "minecraft:overworld" }
}
```

`totalBlocksScanned` counts every position visited, including air, so a tall zone inflates
it badly. A 200×200 footprint over 20 vertical blocks is 800 000 positions; the same
footprint over the full world height is 24 million. Keep the zone's Y range tight around
the track — it is the single biggest factor in scan time.

### Re-scanning

Re-scanning is the normal workflow: move a gate, scan again. `applyToConfig()` tries to
preserve the ordering you already set, by matching each new checkpoint against the old ones
on horizontal distance — within 5 blocks (squared distance under 25), the old `order` is
carried over. Y is ignored in that match, so two checkpoints stacked vertically within 5
blocks horizontally can inherit each other's order. Re-check the ordering after moving a
gate vertically.

Checkpoints that match nothing keep `order: -1`, which means unordered.

## The heightmap

`POST /races/heightmap` renders the zone from above so the dashboard can draw a 2D map with
the checkpoints laid on top.

For each `(x, z)` column it finds the topmost non-air block with a real map colour, starting
from the vanilla `WORLD_SURFACE` heightmap as an optimisation, and takes that block's map
colour. A second pass shades each pixel by comparing its height to its northern neighbour —
12 % brighter if higher, 15 % darker if lower — which is what gives the map its relief.
Pixels with nothing found keep the dashboard's own background colour, `#0c0e14`.

The area cap is 500 000 columns:

```json
{"error":"Heightmap failed: Zone area too large for heightmap: 640000 (max 500K)"}
```

That is roughly a 700×700 footprint. Unlike the scan, the cap is on area only — zone height
does not count, because each column stops at its first solid block.

The response gives `width`, `depth`, `minX`, `minZ` and the pixel data, which the dashboard
turns into an image. `minX` and `minZ` are what let a checkpoint's world centre be placed on
the right pixel.

## Track settings

`PUT /races/:eventId/track` merges whatever keys the body contains; absent keys keep their
value. These are read by `MiniGameManager` when the race starts, so changing them mid-race
has no effect.

| Key | Type | Default | Effect |
|---|---|---|---|
| `zoneId` | string | — | the zone the track lives in |
| `orderedCheckpoints` | bool | `true` | checkpoints must be taken in order |
| `laps` | int | `1` | how many times the checkpoint sequence must be completed |
| `countdownSeconds` | int | `5` | pre-start countdown |
| `freezeDuringCountdown` | bool | `true` | hold racers still until the countdown ends |
| `endTimerSeconds` | int | `30` | grace period for the remaining racers after the first finisher |
| `checkpointMargin` | double | `1.5` | blocks of slack added around a checkpoint's bounding box |
| `movementMode` | string | `any` | `any`, `foot`, `shift` or `kart` |
| `nextCheckpointParticle` | string | `HAPPY_VILLAGER` | particle marking the next gate |
| `validatedCheckpointParticle` | string | `FLAME` | particle on gates already taken |
| `hideValidatedCheckpoints` | bool | `false` | stop drawing gates already taken |
| `transformCommands` | string[] | empty | commands run to transform racers at the start |
| `transformMode` | string | `command` | `command` or `species` |
| `revertTransformsOnEnd` | bool | `true` | undo the transform when the race ends |
| `pointsPerCheckpoint` | int | `10` | event score per gate |
| `finishBonusPoints` | int | `100` | event score for finishing |
| `positionBonusPoints` | int | `50` | extra score weighted by finishing position |
| `checkpointReplacementBlock` | string | `minecraft:air` | what a taken gate's blocks become |
| `startLineReplacementBlock` | string | `minecraft:air` | what the start line becomes once the race begins |
| `teleportOnOutOfBounds` | bool | `true` | pull a racer who leaves the zone back to their last gate |
| `powerupTypes` | object | empty | per-power-up configuration, keyed by marker name |
| `checkpointOrders` | int[] | — | write-only: assigns `order` to checkpoints **by index** |

`checkpointOrders` is positional: element *i* sets the order of checkpoint *i* in the stored
list, and the loop stops at whichever array is shorter. The stored list's order is the scan
order (X then Z), not your racing order — so the array you send is "for each scanned
checkpoint, what position is it in the lap". Re-scanning can change the stored order, which
is why `applyToConfig` tries to carry the values across.

`movementMode` is only partly implemented. Only `foot` is enforced, and the enforcement is
to dismount anyone riding anything, once per tick, with
`⚠ On foot only! You have been dismounted.` `shift` and `kart` are accepted, stored, and
have no effect in this build.

### Power-up types

Each power-up marker name gets an entry in `powerupTypes`:

| Key | Type | Default | Effect |
|---|---|---|---|
| `effectId` | string | `minecraft:speed` | the status effect granted |
| `amplifier` | int | `0` | effect amplifier |
| `durationTicks` | int | `100` | effect duration, 5 seconds |
| `targetSelf` | bool | `true` | `false` applies it to **other** racers within the radius |
| `targetRadius` | int | `5` | radius used when `targetSelf` is false |
| `cooldownSeconds` | int | `10` | per-player cooldown before the same pad fires again |
| `singleUse` | bool | `false` | the pad is consumed by the first racer to take it |
| `replacementBlock` | string | `minecraft:air` | what the pad's blocks become once used |
| `sound` | string | `block.beacon.activate` | played on pickup |

`targetSelf: false` with a negative effect is how you build a trap: the racer who drives
over it inflicts it on everyone near them.

## Running the race

The race runs inside `MiniGameManager`, and the checkpoint test is the part worth knowing
about. Checkpoint detection does not sample the player's position once per tick — it
interpolates the segment between the previous tick's position and this one, so a racer
moving fast enough to pass through a one-block-thick gate between two ticks still triggers
it. That is why `tickRaceCheckpoints` runs every tick, while everything else in the race
updates at 1 Hz.

With `orderedCheckpoints: true` each racer has a next-checkpoint index. Only that gate
counts; standing in any other scanned checkpoint produces
`⚠ Wrong checkpoint! You need checkpoint #N.` above the hotbar. Taking the last gate
completes a lap: under `laps`, the index resets to 0 and the racer sees
`🔄 Lap 2/3 complete!`; on the final lap it is a finish.

With `orderedCheckpoints: false` the racer accumulates a set of validated gates in any
order, and finishes once the set is complete.

Out-of-bounds handling, when `teleportOnOutOfBounds` is on, teleports the racer to their
last taken checkpoint's centre, or to the start line if they have not taken one yet, with
`⚠ You left the track! Teleported to last checkpoint.`

An event whose track config has no checkpoints is cancelled outright:
`⚠ Race cancelled — no checkpoints configured.`

Scores feed the event's normal score table, so the race's own ranking and the event
scoreboard agree: gates give `pointsPerCheckpoint`, finishing gives `finishBonusPoints`, and
`positionBonusPoints` is scaled by finishing position.

### Legacy mode

A `RACE` event with **no** track config file still runs, in what the code calls legacy mode:
checkpoints are plain CheeseGuard zones listed in the event's `miniGameConfig`, and the
scoring keys (`pointsPerCheckpoint`, `finishBonusPoints`, `positionBonusPoints`,
`checkpointSound`, `finishSound`) are read from that config object instead of from the track
file. There is no interpolated detection and no power-ups in legacy mode. New tracks should
use the scanner.

## Authorisation

As of 29 October 2026 the write guards on this module are correct:

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

Before that fix they called `requireWrite` and discarded the boolean, which sent a `403` and
then carried out the action anyway. If you are reading an older build, treat the roles in
[Dashboard API](dashboard.md#races) as intent rather than enforcement.

`POST /races/heightmap` has no module-level guard of its own. It is still reachable only by
an `OPERATOR` or above, because CheeseDash's own dispatcher guards every non-`GET` request
to a module — see the note in [Dashboard API](dashboard.md#what-the-core-actually-enforces).
It reads world data and changes nothing, so the practical risk is a wasted scan, but a read
exposed as a `POST` is worth knowing about.

---

← [Prison](prison.md) · [Dashboard API](dashboard.md) →
