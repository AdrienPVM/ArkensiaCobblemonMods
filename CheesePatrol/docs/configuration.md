[← CheesePatrol](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

**CheesePatrol has no settings file.** Every sibling mod in this repository writes a
`config/<modid>.json` on first launch with tunables in it; this one does not. There is no
`CheesePatrolConfig` class, no defaults to override, and no reload command.

What exists instead is a **state file** — the patrols themselves, written by the mod and
read back at startup — plus exactly one global flag that happens to live in the same file.

```text
config/cheesepatrol_data.json
```

The path is hard-coded as `Path.of("config", "cheesepatrol_data.json")`, relative to the
server's working directory. It does not go through
`FabricLoader.getInstance().getConfigDir()`, so on an unusual launch setup where the
working directory is not the server root, the file lands somewhere else. Worth knowing if
you cannot find it.

Everything in this file is authored through `/npcpatrol` subcommands. Hand-editing it is
possible and documented below, with real caveats.

---

## Top-level shape

```json
{
  "debug": false,
  "patrols": [ … ]
}
```

| Key | Type | Default | What it does |
|---|---|---|---|
| `debug` | boolean | `false` | Enables verbose per-patrol logging in the server console |
| `patrols` | array | `[]` | One object per patrol. Written from memory on every change |

Both keys are written on every save. A missing `debug` reads as `false`; a missing
`patrols` reads as an empty list. A corrupt or unparseable file is logged once and treated
as empty:

```text
[CheesePatrol] Failed to load data
```

⚠️ The mod never backs this file up before rewriting it. A truncated write during a crash
loses every patrol — recoverable only from your own world backups. The NPC entities
themselves survive; their routes do not.

### `debug`

The only key here that is genuinely a setting rather than state, and the only one you have
to edit by hand because **no command or endpoint toggles it**.

With `debug: false` the console sees only errors: a corrupt data file, a failed entity
backup, a patrol that gave up after ten respawn attempts, a respawn that failed outright.

With `debug: true` you additionally get, per patrol and per event:

```text
[CheesePatrol] Kick-started patrol for NPC a1b2c3d4 at waypoint 2/4
[CheesePatrol] NPC a1b2c3d4 not found after 30s - respawn attempt 1/10
[CheesePatrol] NPC a1b2c3d4 found after 812 ticks missing - kick-starting patrol
[CheesePatrol] NPC a1b2c3d4 stuck for 60s at dist 14.2 - teleporting to waypoint 3
[CheesePatrol] Integrity check: NPC a1b2c3d4 missing (patrol active) - respawning from backup
[CheesePatrol] Auto-resuming patrol a1b2c3d4 - entity found, was system-paused
[CheesePatrol] Startup: kick-started 3 active patrols
```

That is the output you want when an NPC is misbehaving and you cannot tell whether it is
getting stuck, getting teleported, or quietly dying and being respawned. Turn it off
afterwards — these lines are per-event, not per-tick, but a dozen flaky patrols will fill
a log.

**How to change it, precisely:**

1. Stop the server. This is not optional, and the reason is the save path: any
   `/npcpatrol` action rewrites the whole file from the in-memory state, so an edit made
   while the server runs is overwritten the next time anyone adds a waypoint.
2. Edit `"debug": false` to `"debug": true`.
3. Start the server. `PatrolManager.load()` runs at `SERVER_STARTING` and reads the flag.

The flag is preserved across saves — the mod writes back whatever it loaded — so you only
have to do this once.

One thing `debug` does **not** suppress: the removal-blocking mixin logs every blocked
removal at `INFO`, unconditionally. See
[keeping NPCs alive](resilience.md#the-mixin-logs-every-block).

## A patrol object

One per entry in `patrols`. Keys are omitted when they hold their default value, which is
why a freshly created patrol is three lines long.

| Key | Type | Default | Written when | What it does |
|---|---|---|---|---|
| `npcUuid` | string | — | always | The entity UUID this patrol drives. The identity of the patrol; there is no other id |
| `dimension` | string | `"minecraft:overworld"` | always | Dimension id, as `namespace:path`. Set when you select the NPC, never changed afterwards |
| `active` | boolean | `false` | always | Whether the circuit is running. Restored on restart, which is why patrols resume by themselves |
| `speed` | number | `0.5` | always | Walk speed. Clamped to `0.1`–`5.0` |
| `playerDetectionRadius` | number | `5.0` | always | Look-at-player radius in blocks. Clamped to `1.0`–`32.0` |
| `currentWaypointIndex` | integer | `0` | always | Zero-based index of the waypoint the NPC is walking **toward** |
| `waypoints` | array | `[]` | always | The circuit, in running order |
| `lastX` `lastY` `lastZ` | integer | absent | once the NPC has been seen | Last known block position, updated every tick the entity is found. Used to force-load the right chunk at startup |
| `particleEffect` | string | `"none"` | when not `"none"` | Trail effect name |
| `particleYOffset` | number | `0.0` | when not `0` | Vertical trail offset |
| `particleZOffset` | number | `0.0` | when not `0` | Forward/backward trail offset. Positive is behind |
| `particleRange` | integer | `16` | when not `16` | Trail visibility range in blocks |
| `pausedBySystem` | boolean | `false` | when `true` | `true` means the mod disabled this patrol itself after ten failed respawns, and will re-enable it when the NPC reappears. `/npcpatrol stop` sets it `false` |

A waypoint:

| Key | Type | Default | What it does |
|---|---|---|---|
| `x` `y` `z` | integer | — | Block position. The NPC aims at the block centre, `x + 0.5` / `y` / `z + 0.5` |
| `wait` | integer | absent | Pause in **ticks**, omitted when zero. `/npcpatrol wait` multiplies your seconds by 20 |

### Keys with a non-obvious effect

**`active` and `pausedBySystem` together** decide what happens on restart and during the
five-minute integrity check. `active: true` means the patrol is kick-started five seconds
after `SERVER_STARTED`. `active: false, pausedBySystem: true` means "the mod gave up on
this NPC" and the integrity check will flip it back to active the moment the entity is
found again. `active: false, pausedBySystem: false` means a human stopped it, and nothing
will restart it but a human. If you hand-edit one, consider the other.

**`currentWaypointIndex` is read modulo the waypoint count** at every use, so an
out-of-range value does not crash — it wraps. `/npcpatrol start` resets it to 0 when it is
past the end. Setting it by hand is a legitimate way to park an NPC at a specific point
of its circuit before a restart.

**`dimension` is set once**, when you select the NPC, and nothing updates it afterwards.
Move an NPC to another dimension and the patrol keeps the old id. The entity lookup has a
fallback that searches every loaded world, so the patrol usually keeps working, but
startup chunk force-loading and the `show` display both use the stored value and will
target the wrong world. Clear and recreate the patrol rather than editing the field, so
the force-load bookkeeping stays consistent.

**`lastX` / `lastY` / `lastZ` are written every tick the entity is found** but only
persisted on save. After a crash they can be a few minutes stale, which only matters for
the startup force-load: a stale position force-loads the wrong chunk, the entity is not
found, and the respawn ladder takes over.

**`wait` is in ticks in the file and seconds in the command.** A file showing
`"wait": 100` is five seconds. Values are clamped to `≥ 0` on write but the command's
`0`–`3600` second ceiling is not enforced on load, so a hand-written `"wait": 999999` is
accepted and the NPC stands there for about fourteen hours.

**Speed and radius are clamped on write, not on load.** `PatrolData.fromJson` assigns the
fields directly with no clamping, so a hand-edited `"speed": 50` survives the load and the
NPC moves at six blocks per tick. The clamps are in the setters, which only the commands
call. Treat this as a way to exceed the documented ranges at your own risk, not as a
feature.

## Entity backups

A second directory, written by the same subsystem but not configurable:

```text
config/cheesepatrol_entities/<npc-uuid>.dat
```

One GZip-compressed NBT snapshot per registered patrol NPC, produced by `entity.saveNbt`.
Written 60 seconds after startup and every 60 seconds after that, whenever a missing NPC
reappears, and at `SERVER_STOPPING`. Deleted by `/npcpatrol clear`, and deleted
automatically if it ever fails to parse, to break a respawn retry loop.

These files are what makes an NPC recoverable, and their presence alone is enough to make
an entity unkillable — the removal mixin checks the directory for any entity it does not
recognise. Deleting one by hand on a running server therefore has two effects: the NPC
loses its safety net, and it loses removal protection until the next backup pass rewrites
the file. Full behaviour in [keeping NPCs alive](resilience.md).

There is no retention policy and no size cap. One file per patrol NPC, overwritten in
place, each a few kilobytes.

## A complete annotated example

Three patrols: one running, one stopped by an admin, one the mod gave up on. JSON does not
allow comments, so the annotations are below the block.

```json
{
  "debug": false,
  "patrols": [
    {
      "npcUuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "speed": 1.0,
      "playerDetectionRadius": 6.0,
      "active": true,
      "currentWaypointIndex": 2,
      "dimension": "minecraft:overworld",
      "lastX": 126,
      "lastY": 65,
      "lastZ": -331,
      "particleEffect": "dust_cyan",
      "particleZOffset": 0.6,
      "particleRange": 48,
      "waypoints": [
        { "x": 112, "y": 65, "z": -340 },
        { "x": 128, "y": 65, "z": -340, "wait": 100 },
        { "x": 128, "y": 65, "z": -324 },
        { "x": 112, "y": 65, "z": -324 }
      ]
    },
    {
      "npcUuid": "9f8e7d6c-5b4a-3928-1716-0f0e0d0c0b0a",
      "speed": 0.5,
      "playerDetectionRadius": 5.0,
      "active": false,
      "currentWaypointIndex": 0,
      "dimension": "minecraft:the_nether",
      "lastX": 48,
      "lastY": 72,
      "lastZ": 19,
      "waypoints": [
        { "x": 44, "y": 72, "z": 16 },
        { "x": 52, "y": 72, "z": 16 }
      ]
    },
    {
      "npcUuid": "11112222-3333-4444-5555-666677778888",
      "speed": 0.8,
      "playerDetectionRadius": 5.0,
      "active": false,
      "currentWaypointIndex": 1,
      "dimension": "cheeseguard:arena_01",
      "pausedBySystem": true,
      "waypoints": [
        { "x": 0, "y": 80, "z": 0 },
        { "x": 0, "y": 92, "z": 0 },
        { "x": 20, "y": 92, "z": 0 }
      ]
    }
  ]
}
```

- **Patrol 1** is the result of following [getting started](getting-started.md): a
  four-corner square, 5-second pause at corner #2 (`"wait": 100` ticks), cyan trail pushed
  0.6 blocks behind the NPC and visible out to 48 blocks. It is currently walking toward
  waypoint #3, and was last seen at `126 65 -331`, between #2 and #3.
- **Patrol 2** is stopped by a human: `active: false` with no `pausedBySystem`. Nothing
  restarts it automatically. Two waypoints is the minimum; it will pace back and forth
  between them when started, since a two-point loop is a line.
- **Patrol 3** is one the mod disabled itself. `pausedBySystem: true` means the entity went
  missing and ten respawn attempts failed — most likely because `cheeseguard:arena_01` is a
  dimension that no longer exists, in which case the respawn logs
  `Cannot respawn NPC 11112222 - dimension 'cheeseguard:arena_01' not found`. The
  five-minute integrity check keeps retrying. Note the waypoints at y 80 and y 92 over open
  space: that makes the middle segments **flying** segments, and the mod will apply
  `NoGravity` and move the NPC by direct velocity. See
  [flying segments](patrol-circuits.md#flying-segments).

No `debug` entry needs adding — it is already there, written on every save.

---

← [Commands](commands.md) · [Patrol circuits](patrol-circuits.md) →
