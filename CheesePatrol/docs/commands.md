[← CheesePatrol](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

CheesePatrol registers one command, `/npcpatrol`, with 18 subcommands. The whole tree is
gated on `hasPermissionLevel(2)`, the level a vanilla server grants an operator. A player
below that does not see the command in tab completion and gets the vanilla *Unknown or
incomplete command* error if they type it.

There is no player-facing command and no aliases. `/npcpatrol` is the only entry point.

---

## The wand holds your selection

This is the single thing to understand before reading the rest of the page.

Fifteen of the 18 subcommands act on "the selected NPC", and the selection is **not** a
per-player server-side variable. It lives in NBT on the Patrol Wand item, under the key
`cheesepatrol_npc`, and every one of those subcommands reads it out of
`player.getMainHandStack()` at the moment you run it.

The consequences are worth spelling out:

- The wand must be **in your main hand**, not the off hand, not the hotbar.
- Two admins can hold two wands pointed at two different NPCs with no interference.
- Dropping, storing or losing the wand loses the selection. Get a new one and reselect.
- Copying the wand (creative middle-click) copies the selection with it.
- `wand`, `list` and the `select` entry point are the only subcommands that do not need an
  existing selection.

The three ways to select are: right-click an NPC while holding the wand, `/npcpatrol
select <uuid>`, or inherit whatever the wand already pointed at.

When a subcommand needs a selection and does not find one:

```text
[CheesePatrol] No NPC selected. Hold a Patrol Wand and /npcpatrol select <uuid>, or right-click an EasyNPC.
```

## Running from a console, RCON or a command block

Possible for exactly one subcommand: `list`. Every other subcommand either needs a player
directly or needs a wand, and both paths bail out with:

```text
Player only
```

(no `[CheesePatrol]` prefix on that one — it comes from a different code path). A command
block has permission level 2 and passes the `requires` check, then fails the player check.
There is no way to drive patrols from a datapack.

---

## `/npcpatrol wand`

```mcfunction
/npcpatrol wand
```

No arguments. Inserts a Patrol Wand into your inventory: a Blaze Rod with a gold bold
custom name `Patrol Wand`, a forced enchantment glint, and a `cheesepatrol_wand` boolean
in its `minecraft:custom_data` component. That component is what makes it a wand; an
anvil-renamed Blaze Rod is not one.

```text
[CheesePatrol] Patrol Wand given. Right-click an EasyNPC to select it, then use /npcpatrol add.
```

The stack is inserted with normal inventory rules, so with a full inventory it goes
nowhere and the success message still prints. Run it as many times as you like; nothing
deduplicates wands.

| Message | Cause |
|---|---|
| `Player only` | run from console, RCON or a command block |

## `/npcpatrol select <uuid>`

```mcfunction
/npcpatrol select <uuid>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `uuid` | UUID | yes | a full entity UUID, dashed form; no completions offered | — |

Writes `uuid` into the wand you are holding and creates a patrol record for it if there
is none, pinning the patrol's dimension to **the world you are standing in**. The
alternative to right-clicking, useful when the NPC is far away or buried in a crowd of
entities.

```mcfunction
/npcpatrol select a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

```text
[CheesePatrol] Selected NPC: a1b2c3d4... (Village Guard)
```

Two things it does **not** do, both of which bite:

- It looks the entity up with `player.getServerWorld().getEntity(uuid)`, so the NPC has to
  be in **your** current world and loaded. An NPC in another dimension cannot be selected
  this way; go there, or right-click it.
- Unlike the right-click path, it does **not** check that the entity is a
  `PathAwareEntity`. You can select an armor stand or a dropped item by UUID, build a full
  circuit on it, start it, and get no error and no movement: the per-tick loop silently
  returns for anything that is not path-aware.

| Message | Cause |
|---|---|
| `Player only` | not run by a player |
| `[CheesePatrol] Hold a Patrol Wand first! (/npcpatrol wand)` | your main hand is not a wand; the UUID is discarded |
| `[CheesePatrol] No entity found with UUID <uuid>` | not loaded, or not in your current dimension |

## Selecting by right-click

Not a subcommand, but the same operation, and the one you will use. Hold the wand and
right-click any entity:

```text
[CheesePatrol] Selected: Village Guard (a1b2c3d4...)
```

in chat, with a status suffix when the NPC already has a circuit:
`[6 waypoints, inactive]`, or in red `[PATROL ACTIVE — /npcpatrol clear before deleting!]`.

This path **does** check `PathAwareEntity` and refuses otherwise, on your action bar:

```text
[CheesePatrol] Not a valid NPC (not PathAwareEntity).
```

Both the entity and block right-click handlers require permission level 2 and main hand.
Without the level, the click falls through to vanilla behaviour as though CheesePatrol
were not installed.

## `/npcpatrol add`

Four forms, all appending **one waypoint to the end of the list**. There is no insert.

```mcfunction
/npcpatrol add
/npcpatrol add <waitSeconds>
/npcpatrol add <x> <y> <z>
/npcpatrol add <x> <y> <z> <waitSecondsPos>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `waitSeconds` | integer | no | `0`–`3600` | `0` |
| `x` `y` `z` | integer | together, or not at all | any integer; **absolute block coordinates only** — `~` and `^` are not supported | your feet |
| `waitSecondsPos` | integer | no | `0`–`3600` | `0` |

The coordinate arguments are plain integers, not Minecraft's position argument type, so
relative and local coordinates do not work. Type the numbers.

The feet forms use `player.getBlockPos()`, the block your feet occupy, which standing on
the ground is the air block you are in. The wand's block right-click instead uses the
clicked block **plus one in Y**, so both land the waypoint where an NPC's feet belong.

```mcfunction
/npcpatrol add 5
```

```text
[CheesePatrol] Added waypoint #3 at 128 65 -340 (wait 5s)
```

```mcfunction
/npcpatrol add 112 65 -324
```

```text
[CheesePatrol] Added waypoint #4 at 112 65 -324
```

Both forms save `cheesepatrol_data.json` immediately.

⚠️ The one-number and three-number forms occupy the same slot in the Brigadier tree, so a
single number is read as a wait time and three numbers as coordinates. A single number
**above 3600** falls out of the wait branch, is read as an `x`, and then the command is
incomplete — you get the vanilla *Incomplete command* error rather than anything about
ranges.

| Message | Cause |
|---|---|
| `Player only` | not run by a player, including for the coordinate forms |
| `[CheesePatrol] No NPC selected. …` | no wand, or wand with no selection |

## `/npcpatrol remove <index>`

```mcfunction
/npcpatrol remove <index>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `index` | integer | yes | `≥ 1`; the numbering `/npcpatrol info` prints |

Deletes one waypoint and its wait time. **Everything after it renumbers**, so removing
#2 from a five-point circuit leaves #1…#4 with the old #3 now called #2. Remove from the
back if you are deleting several.

```mcfunction
/npcpatrol remove 3
```

```text
[CheesePatrol] Removed waypoint #3. 3 remaining.
```

If the NPC was heading for a waypoint index that no longer exists, the index resets to 0
and the NPC turns toward waypoint #1 on the next tick. A running patrol that drops below
two waypoints stops moving but stays marked active; the per-tick loop skips it. Removing
the waypoint the NPC is currently standing at does not interrupt a wait in progress.

| Message | Cause |
|---|---|
| `[CheesePatrol] Invalid waypoint index.` | no patrol for this NPC, or `index` beyond the list |
| `[CheesePatrol] No NPC selected. …` | no selection |

## `/npcpatrol start`

```mcfunction
/npcpatrol start
```

No arguments. Marks the patrol active, clears the system-paused flag, resets the
missing-entity counter, and resets the target to waypoint #1 if the stored index is past
the end of the list. Then it **kick-starts**, which is the interesting part:

1. find the entity by UUID in the patrol's dimension, falling back to every loaded world
2. wipe the EasyNPC goal selector completely (`clear(g -> true)`)
3. stop navigation, zero velocity
4. clear the looking-at-player and waiting flags
5. apply `NoGravity` if the current waypoint is a flying one
6. if the NPC is already within 1.5 blocks of its current waypoint, advance to the next —
   so `start` never waits at the point it is standing on
7. push navigation toward the current waypoint
8. force-load the last-known-position chunk and **every waypoint chunk**

```text
[CheesePatrol] Patrol started at waypoint #1/4 (NPC kick-started)
```

The suffix tells you whether step 1 found anything:

| Suffix | Meaning |
|---|---|
| `(NPC kick-started)` | the entity was found and pushed; it is moving now |
| `(NPC not found — will start when loaded)` | the patrol is active but nothing moved. The NPC is unloaded, dead, or in a different dimension than the patrol recorded |

The second case is not a failure the command reports as an error: the patrol really is
active, and the per-tick loop will start force-loading chunks and, after 30 seconds,
trying to respawn the NPC from its NBT backup. See
[keeping NPCs alive](resilience.md#the-respawn-ladder).

| Message | Cause |
|---|---|
| `[CheesePatrol] Need at least 2 waypoints to start a patrol.` | fewer than two waypoints, or no patrol record at all |
| `[CheesePatrol] No NPC selected. …` | no selection |

## `/npcpatrol stop`

```mcfunction
/npcpatrol stop
```

No arguments. Marks the patrol inactive, clears the system-paused flag so the integrity
check will not auto-resume it, releases this patrol's force-loaded chunks, and saves.

```text
[CheesePatrol] Patrol stopped.
```

The NPC stops wherever it stands, mid-stride, with whatever velocity the last tick gave
it, so it may drift a block before friction takes over. The waypoint index is preserved,
so `start` resumes the lap rather than restarting it.

What `stop` does **not** do: it does not lift removal protection, does not stop the
minute-by-minute NBT backups, and does not stop the five-minute integrity check from
respawning the NPC if it vanishes. An inactive patrol is still a protected NPC. Only
`clear` undoes that.

Distinguishing a manual stop from an automatic one matters: `stop` sets
`pausedBySystem = false`, which is what tells the integrity check "a human did this, leave
it alone". A patrol disabled by ten failed respawns sets it to `true` and gets resumed
automatically when its NPC comes back.

| Message | Cause |
|---|---|
| `[CheesePatrol] No patrol data for this NPC.` | the wand points at a UUID with no patrol record |
| `[CheesePatrol] No NPC selected. …` | no selection |

## `/npcpatrol clear`

```mcfunction
/npcpatrol clear
```

No arguments, no confirmation, irreversible. In order: release force-loaded chunks, empty
the waypoint list, delete this NPC's armor-stand markers **in your current world only**,
remove the patrol from the registry, delete `config/cheesepatrol_entities/<uuid>.dat`, and
save.

```text
[CheesePatrol] Patrol cleared and all waypoints removed.
```

This is also the only way to make a patrol NPC killable again. While a patrol record or a
backup file exists for an entity, the removal mixin blocks `/kill`, damage and despawn —
so the order to delete an NPC is always `clear` first, kill second. The right-click status
suffix exists to remind you.

The marker cleanup is dimension-scoped to where you are standing, so clearing a patrol
from the wrong world leaves its armor stands behind. Kill them by tag:

```mcfunction
/kill @e[type=armor_stand,tag=cheesepatrol]
```

| Message | Cause |
|---|---|
| `[CheesePatrol] No NPC selected. …` | no selection |

Running `clear` on a UUID with no patrol record succeeds and reports success. It is
idempotent.

## `/npcpatrol list`

```mcfunction
/npcpatrol list
```

No arguments, no selection needed, works from the console. Every patrol on the server,
active or not, in no defined order (it walks a `ConcurrentHashMap`).

```text
Patrols (2):
[ACTIVE] a1b2c3d4... | 4 WP | speed=1.0 | radius=6.0
[INACTIVE] 9f8e7d6c... | 6 WP | speed=0.5 | radius=5.0
```

`[ACTIVE]` is green, `[INACTIVE]` grey. The UUID is truncated to eight characters, enough
to identify an NPC, not enough to paste into `/npcpatrol select`. For the full UUID use
`info`, or the dashboard, which returns it in full.

`[INACTIVE]` covers both "an admin stopped it" and "the system paused it after
ten failed respawns". `list` does not distinguish them; the dashboard does, through the
`pausedBySystem` field.

With nothing configured:

```text
[CheesePatrol] No patrols configured.
```

## `/npcpatrol info`

```mcfunction
/npcpatrol info
```

No arguments. Full detail on the selected patrol, sent only to you.

```text
=== Patrol Info ===
NPC: a1b2c3d4-e5f6-7890-abcd-ef1234567890
Dim: minecraft:overworld
Status: ACTIVE
Speed: 1.0
Radius: 6.0
Waypoints (4):
  #1: 112 65 -340
  #2: 128 65 -340 [wait 5s]
  #3: 128 65 -324 <-- current
  #4: 112 65 -324
```

| Field | Meaning |
|---|---|
| `NPC` | the full UUID, the one to paste into `select` or the dashboard |
| `Dim` | the dimension the patrol is pinned to, set when you selected the NPC |
| `Status` | `ACTIVE` green or `INACTIVE` red. Does not distinguish a system pause |
| `Speed` | the stored value, after clamping |
| `Radius` | player detection radius in blocks, after clamping |
| `[wait Ns]` | per-waypoint pause, omitted when zero |
| `<-- current` | the waypoint the NPC is walking **toward**, not the one it left |

`info` reads the live in-memory record, so a change made a tick ago shows immediately. It
reports nothing about the entity: an NPC that has been missing for an hour still shows
`ACTIVE` with a current waypoint. For entity state, read the server log or the dashboard.

| Message | Cause |
|---|---|
| `[CheesePatrol] No patrol data for this NPC.` | the wand's UUID has no patrol record |
| `[CheesePatrol] No NPC selected. …` | no selection |

## `/npcpatrol speed <value>`

```mcfunction
/npcpatrol speed <value>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `value` | double | yes | `0.1`–`5.0`, enforced by Brigadier and clamped again on set | `0.5` |

```text
[CheesePatrol] Speed set to 1.0
```

Takes effect on the next tick; no restart, no `start` needed. It is not a blocks-per-second
figure — the per-tick code multiplies it by `0.12` for ground movement and `0.15` for
flight to get a velocity. `0.5` gives roughly 1.2 blocks per second on the ground. Details
in [patrol circuits](patrol-circuits.md#speed-is-not-blocks-per-second).

Creates a patrol record if there is none, so you can set a speed before placing any
waypoints.

## `/npcpatrol radius <value>`

```mcfunction
/npcpatrol radius <value>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `value` | double | yes | `1.0`–`32.0` | `5.0` |

```text
[CheesePatrol] Player detection radius set to 6.0
```

The sphere inside which the NPC stops and turns to face the nearest player. Spherical, not
cylindrical — a player 10 blocks above counts as 10 blocks away. Spectators are excluded;
there is no line-of-sight check and no cooldown. Behaviour in full:
[the look-at-player pause](patrol-circuits.md#the-look-at-player-pause).

Large values are a real cost. At `32.0` on a busy spawn an NPC will essentially never
move, because someone is always inside the radius.

## `/npcpatrol wait <index> <seconds>`

```mcfunction
/npcpatrol wait <index> <seconds>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `index` | integer | yes | `≥ 1`, an existing waypoint number | — |
| `seconds` | integer | yes | `0`–`3600` | — |

⚠️ **Index first, seconds second.** This mod's earlier README described `wait` as taking
a single tick count; it takes two arguments and the second is in seconds, converted to
ticks internally (`seconds * 20`).

```mcfunction
/npcpatrol wait 2 5
```

```text
[CheesePatrol] Waypoint #2 wait set to 5s.
```

```mcfunction
/npcpatrol wait 2 0
```

```text
[CheesePatrol] Waypoint #2 set to smooth (no wait).
```

The pause starts when the NPC gets within 1.5 blocks of the waypoint (5 blocks on a flying
segment), and it is **not** a hard guarantee of duration: the countdown is suspended
entirely while a player is inside the detection radius, because the look-at-player check
returns before the wait handling runs. A 5-second wait with a player standing next to the
NPC lasts as long as the player does.

Both `add` forms can set a wait at creation time, which is usually less typing.

| Message | Cause |
|---|---|
| `[CheesePatrol] Invalid waypoint index.` | no patrol, or `index` beyond the list |
| `[CheesePatrol] No NPC selected. …` | no selection |

## `/npcpatrol show`

```mcfunction
/npcpatrol show
```

No arguments. Toggles a private route preview for the selected NPC: coloured dust on each
waypoint, a gold line along every segment including the closing one, redrawn every 10
ticks.

```text
[CheesePatrol] Particles ON. Run again to hide.
```

```text
[CheesePatrol] Particles OFF.
```

One NPC at a time per player — showing a second patrol replaces the first. The display is
suppressed while you are in a different dimension than the patrol, and dropped
automatically if you disconnect or the patrol is cleared. Colours and geometry:
[visuals](visuals.md#the-route-preview).

Works on an inactive patrol, which is the point — check the route before starting it.

| Message | Cause |
|---|---|
| `Player only` | not run by a player |
| `[CheesePatrol] No waypoints to show.` | no patrol record, or an empty waypoint list |
| `[CheesePatrol] No NPC selected. …` | no selection |

## `/npcpatrol markers`

```mcfunction
/npcpatrol markers
```

No arguments. Places an invisible, weightless, invulnerable armor stand with a visible
name tag `WP #1`, `WP #2` … at every waypoint of the selected patrol, in **your current
world**.

```text
[CheesePatrol] Armor stand markers placed at 4 waypoint(s). Run again to refresh.
```

⚠️ The message says *refresh*, and refresh is all it does — each run deletes this NPC's
existing markers and places a fresh set. **There is no way to turn markers off with this
command.** They are removed by `/npcpatrol clear`, by a clean server shutdown (which
removes every `cheesepatrol`-tagged stand in every world), or by hand:

```mcfunction
/kill @e[type=armor_stand,tag=cheesepatrol]
```

The dashboard guide text describes this subcommand as a toggle. The code does not toggle.

Each stand carries three command tags: `cheesepatrol`, `npc:<full-uuid>` and
`wp:<zero-based-index>`, so you can target one NPC's markers specifically with a selector.
The name tags are visible to all players, which makes this an editing tool and not a
decoration.

| Message | Cause |
|---|---|
| `Player only` | not run by a player |
| `[CheesePatrol] No waypoints to mark.` | no patrol record, or an empty waypoint list |
| `[CheesePatrol] No NPC selected. …` | no selection |

## `/npcpatrol particles <type>`

```mcfunction
/npcpatrol particles <type>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `type` | single word | yes | one of 46 names, tab-completed; lowercased before matching | `none` |

Sets the trail effect that follows the NPC while its patrol is active. Spawned every 2
ticks, three puffs when moving and one when stationary.

```mcfunction
/npcpatrol particles dust_cyan
```

```text
[CheesePatrol] Particle trail set to: dust_cyan
```

```mcfunction
/npcpatrol particles none
```

```text
[CheesePatrol] Particle trail disabled.
```

The full list, with what each one renders, is in [visuals](visuals.md#trail-effects).
Tab completion is prefix-matched against the same list, so typing `dust` and pressing Tab
gives you the eight dust colours.

| Message | Cause |
|---|---|
| `[CheesePatrol] Unknown particle type: <type>. Available: none, sparkle, flame, …` | the name is not in the list. The error prints all 46 |
| `[CheesePatrol] No NPC selected. …` | no selection |

## `/npcpatrol particleheight <offset>`

```mcfunction
/npcpatrol particleheight <offset>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `offset` | double | yes | `-5.0`–`5.0` | `0.0` |

```text
[CheesePatrol] Particle Y offset set to 0.4
```

Vertical offset from the trail's natural anchor, which is the NPC's mid-body
(`y + height * 0.5`). Positive is up. Takes effect on the next particle tick.

## `/npcpatrol particledepth <depth>`

```mcfunction
/npcpatrol particledepth <depth>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `depth` | double | yes | `-5.0`–`5.0` | `0.0` |

```text
[CheesePatrol] Particle depth offset set to 0.6 (negative=ahead, positive=behind)
```

Offset along the NPC's forward axis. **Positive puts the trail behind the NPC, negative
in front.** Forward is taken from the velocity vector while moving and from the body yaw
while stopped, so the trail swings round as the NPC turns to face a player.

## `/npcpatrol particlerange <range>`

```mcfunction
/npcpatrol particlerange <range>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `range` | integer | yes | `16`–`512` blocks | `16` |

```text
[CheesePatrol] Particle visibility range set to 48 blocks
```

The trail is sent per-player, and only to players within this spherical distance of the
trail's anchor point. It bounds bandwidth, not brightness: a packet per eligible player
per position per particle tick, so a 512-block range on a crowded server means every
nearby player gets the trail of every NPC. Raise it for a landmark NPC, leave it at 16 for
a town guard.

The range applies only to the trail. The `show` route preview is always sent to the one
player who asked for it, at any distance within the dimension.

---

## What has no command

Worth knowing so you do not go looking:

- **No reload.** `cheesepatrol_data.json` is read once, at `SERVER_STARTING`. Hand edits
  to a running server are ignored and then overwritten by the next save.
- **No debug toggle.** The `debug` flag that controls the verbose patrol logging is
  file-only. See [configuration](configuration.md#debug).
- **No insert or reorder.** `add` appends; fixing the middle of a circuit means removing
  and re-adding the tail.
- **No `markers off`**, as described above.
- **No per-patrol name.** Patrols are identified by NPC UUID everywhere.
- **No create, edit or delete from the dashboard.** It reads and teleports; that is all.
  See [dashboard](dashboard.md).

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
