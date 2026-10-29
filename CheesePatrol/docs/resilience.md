[← CheesePatrol](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](resilience.fr.md)

# Keeping NPCs alive

More than half of this mod is not about walking. It is about making sure the thing that
walks still exists.

The problem is real: an NPC is an entity, entities live in chunks, chunks unload, and an
unloaded entity does not tick. Worse, entities get lost: a chunk corruption, a crash
during save, a `/kill @e` with a careless selector, a mob-cap sweep, an admin cleaning up
"stray mobs". A patrol NPC that vanishes leaves a hole in a town that nobody notices until
a player asks where the guard went.

CheesePatrol answers that with four layers, each of which catches something the others
miss. They also each have a cost, and this page names both.

---

## Layer 1: the removal mixin

`EntityRemoveMixin` injects at the head of `Entity.remove(RemovalReason)` and cancels the
call for protected entities.

```java
if (reason == RemovalReason.UNLOADED_TO_CHUNK
        || reason == RemovalReason.UNLOADED_WITH_PLAYER
        || reason == RemovalReason.CHANGED_DIMENSION) {
    return;   // allowed
}
```

Those three are the legitimate removals: the chunk is unloading, the entity is being
written to disk, or it is moving to another world. Everything else, `KILLED` and
`DISCARDED`, is blocked.

An entity is protected if **either** of two checks passes:

1. **It has a patrol record.** Active or inactive, started or never started — the moment
   you select an NPC with the wand, it becomes unkillable.
2. **It has a backup file.** `Files.exists(config/cheesepatrol_entities/<uuid>.dat)`. This
   second check exists for the window during world load when chunks are ticking but
   `PatrolManager.load()` has not run, so the patrol map is still empty.

The visible consequence: `/kill` on a patrol NPC does nothing, and the only way to delete
one is `/npcpatrol clear` first. The wand's right-click status message
(`[PATROL ACTIVE — /npcpatrol clear before deleting!]`) exists to remind you.

### What blocking `KILLED` actually means

⚠️ Blocking removal is not the same as preventing death. An NPC that takes lethal damage
still dies — `health` reaches zero, `onDeath` runs, the death animation starts — and then
the removal that would clean it up is cancelled. By the code's own logic the entity stays
in the world in that state.

Do not rely on this mixin as damage immunity. **Make patrol NPCs invulnerable in EasyNPC's
own configuration**, and treat the mixin as protection against accidental deletion, which
is what it is good at. This is reasoning from the code path rather than a measured
behaviour; if you have an NPC stuck in a death animation, this is why.

### The mixin logs every block

Every cancelled removal writes a line, at `INFO`, with no rate limit and **no `debug`
guard**:

```text
[CheesePatrol] Blocked removal (KILLED) of registered NPC a1b2c3d4 (patrol active)
[CheesePatrol] Blocked removal (DISCARDED) of NPC a1b2c3d4 via backup-file protection (patrol data not yet loaded)
```

The second one is a `WARN`. A loop that repeatedly tries to discard a protected entity will
fill a log file, and the `debug: false` setting does not suppress it. If your log is full
of these, something is trying to delete your NPCs on a timer (a cleanup plugin, most
likely), and that is worth finding.

### The cost: a filesystem check per entity removal, server-wide

This is the most important performance fact about the mod, and it is not obvious from the
feature list.

The two checks run in order, and the second only runs when the first fails. The first
fails for **every entity that is not a patrol NPC**, which is every entity on the server.
So every time anything is removed anywhere, the mixin performs a `Files.exists` call on the
server thread.

Every arrow that despawns. Every item that is picked up. Every XP orb. Every Pokémon that
faints. Every mob in a farm. On a busy Cobblemon server that is thousands of filesystem
`stat` calls per second, synchronously, on the thread that runs the game.

The fix a contributor should make is to cache the backup directory's UUID set in memory at
startup and update it when a backup is written or deleted, turning the disk check into a
set lookup. Until then: this mod has a measurable, if usually survivable, global cost.

## Layer 2: per-tick entity flags

Cheap, and applied every tick to every found patrol NPC:

```java
mob.setPersistent();      // vanilla will never despawn it
mob.fallDistance = 0;     // it will never take fall damage
```

`setPersistent` is the vanilla mechanism for "this mob is not disposable", the same flag a
named or leashed mob gets. It is also applied by the five-minute integrity check, so an
inactive patrol's NPC gets it too.

The `fallDistance` reset exists because of flying segments: an NPC with `NoGravity`
switched off at altitude drops, and without the reset the drop would kill it. See
[flying segments](patrol-circuits.md#flying-segments).

## Chunk force-loading

An entity in an unloaded chunk does not tick, does not walk, and cannot be found by UUID.
So CheesePatrol force-loads chunks: real vanilla forced chunks, the kind `/forceload`
creates and the kind that persist in world data.

**While a patrol is running**, each tick keeps exactly two chunks forced per NPC:

- the chunk the NPC is standing in
- the chunk of the waypoint it is walking toward

As the NPC moves, chunks that drop out of that pair are released. Two chunks per patrolling
NPC is a modest, bounded cost, and a patrol crossing a chunk boundary loads the next chunk
before it arrives.

**At `SERVER_STARTED`**, before anything has had a chance to walk, the mod forces more:

| Patrol state | Chunks forced at startup |
|---|---|
| Active, ≥2 waypoints | the last known position chunk **plus every waypoint chunk** |
| Anything else | the last known position chunk only |

```text
[CheesePatrol] Pre-loaded 7 chunks for 3 patrol(s)
```

The reason for forcing every waypoint chunk on an active patrol is a race: the patrol gets
kick-started five seconds after startup, and whichever waypoint it targets has to be
loadable right then. The reason for forcing the last-known-position chunk even on an
inactive patrol is simpler — that is where the entity is, and if that chunk never loads the
entity never registers and the integrity check starts trying to respawn something that was
never lost.

**When an NPC goes missing**, the mod force-loads the last known position chunk on every
tick until it either appears or the respawn ladder gives up. This is the mechanism that
usually recovers an NPC: it was not lost, its chunk was not loaded.

**Chunks are released** by `/npcpatrol stop`, by `/npcpatrol clear`, and as a patrol walks
away from them.

### They are deliberately not released at shutdown

`SERVER_STOPPING` backs up entities and saves the data file, and explicitly does **not**
release forced chunks. The source comment says why:

> Do NOT release forced chunks — they persist in world data so entities stay loaded
> immediately on next restart (prevents despawn race condition)

That is a defensible trade and it has two consequences you should know about:

- Your world permanently carries forced chunks for every patrol. `/forceload query` lists
  them, and they keep ticking (mobs spawn, crops grow, hoppers move) for as long as the
  patrol exists.
- **Removing the mod without clearing patrols first leaves those chunks forced forever**,
  because nothing is left to release them. The uninstall procedure in
  [installation](installation.md#uninstalling-cleanly) covers this.

`PatrolManager.releaseAllForcedChunks` exists for the opposite behaviour and **nothing
calls it**. It is dead code, left from before this decision.

### Patrol chunk tracking can cancel your own `/forceload`

A bug worth knowing if you force-load chunks by hand.

When a patrol needs a chunk that is already forced, the mod skips forcing it — correctly,
to avoid interfering. But it still records that chunk as one of its own. Later, when the
NPC walks away and the chunk is no longer needed, the release path does not re-check who
forced it and unforces it anyway.

Net effect: an admin `/forceload add` on a chunk a patrol NPC later walks through gets
silently cancelled when the NPC leaves. If a forceload of yours keeps disappearing, check
whether a patrol route crosses it.

## The respawn ladder

Four layers of protection still leave a case: the entity is genuinely gone. For that there
are NBT snapshots.

### Backups

`config/cheesepatrol_entities/<npc-uuid>.dat`, one per registered patrol NPC, GZip-compressed
`entity.saveNbt` output. Written:

- every **1200 ticks (60 seconds)**, for every patrol whose entity is currently found —
  active or inactive
- the moment a previously missing NPC is found again
- at `SERVER_STOPPING`

Deleted by `/npcpatrol clear`, and deleted automatically if it fails to parse — which is
what stops a corrupt snapshot from driving an endless respawn retry:

```text
[CheesePatrol] Corrupted backup for NPC a1b2c3d4 - deleting to prevent retry loop: <message>
```

A snapshot is up to a minute stale, so a respawned NPC can lose up to a minute of EasyNPC
state changes. For a patrol NPC that state rarely changes, which is why a minute was
judged good enough.

### The ladder itself

For an **active** patrol, when the entity is not found, `tickPatrol` counts ticks:

| Elapsed | What happens |
|---|---|
| every tick | force-load the target waypoint chunk and the last known position chunk |
| **600 ticks (30 s)** | attempt a respawn from the backup, increment the attempt counter, reset the 30-second timer |
| every 30 s after | another attempt |
| **after 10 attempts** (≈5 minutes) | give up: set `active = false`, set `pausedBySystem = true`, save, log an error |

```text
[CheesePatrol] NPC a1b2c3d4 failed to respawn 10 times - pausing patrol (integrity check retries every 5 min)
```

Giving up is not giving up permanently. `pausedBySystem = true` is the flag that tells the
integrity check "this was not a human decision", and the integrity check keeps retrying
every five minutes forever, re-enabling the patrol the moment the NPC exists again. The
only thing that stops the retries is `/npcpatrol stop` or `/npcpatrol clear`.

The counter resets completely, missing ticks *and* attempts, the first tick the entity is
found.

### What a respawn does

1. Read `<uuid>.dat`
2. Resolve the patrol's dimension. Missing dimension is a hard failure:
   `Cannot respawn NPC a1b2c3d4 - dimension 'cheeseguard:arena_01' not found`
3. `EntityType.loadEntityWithPassengers` to rebuild the entity, then `world.spawnEntity`
4. Teleport it onto the **current waypoint**, so it rejoins its circuit rather than walking
   back across the map from wherever the snapshot was taken
5. Re-apply `NoGravity` if that waypoint is a flying one, reset fall distance, make it
   persistent, and kick-start the patrol

If `spawnEntity` returns `false`, the respawn is abandoned:

```text
[CheesePatrol] spawnEntity returned false for NPC a1b2c3d4 - UUID conflict or invalid NBT
```

A UUID conflict is the safe outcome: it means the entity does exist in a loaded world, and
spawning a second copy would be worse than doing nothing.

⚠️ **The risk the code does not cover.** The conflict check only sees loaded worlds. If the
original NPC is sitting in an *unloaded* chunk and the respawn succeeds, two entities now
claim the same UUID — one in the world, one on disk. When that chunk eventually loads,
vanilla's duplicate-UUID handling discards one of them, and which one is not something this
mod controls. Keeping the right chunk force-loaded is what makes this unlikely, which is
part of why the force-loading is so aggressive.

## The integrity check

The safety net under the safety net, because the respawn ladder only runs for active
patrols. It runs:

- **once, 100 ticks (5 seconds) after server start** — at the same moment, every active
  patrol with two or more waypoints is kick-started, because entities loaded with EasyNPC
  goals intact will not move until those goals are wiped
- **every 6000 ticks (5 minutes)** thereafter

For every patrol, active or not:

| Entity state | Action |
|---|---|
| found | make it persistent; if the patrol was system-paused and has ≥2 waypoints, resume and kick-start it |
| missing, no backup file | log a warning (under `debug`) and move on — nothing can be done |
| missing, backup exists | force-load the last known chunk, respawn, make persistent, reset counters; resume the patrol if it was system-paused |

The whole data file is saved if anything changed.

That first run is what makes patrols resume by themselves after a restart. With
`debug: true`:

```text
[CheesePatrol] Startup: kick-started 3 active patrols
```

## Reading the resilience system from the log

With `debug: true`, a full recovery cycle looks like this:

```text
[CheesePatrol] NPC a1b2c3d4 not found after 30s - respawn attempt 1/10
[CheesePatrol] Respawned NPC a1b2c3d4 at waypoint 128, 65, -340
[CheesePatrol] Kick-started patrol for NPC a1b2c3d4 at waypoint 2/4
```

A failed cycle, ending in a system pause:

```text
[CheesePatrol] NPC a1b2c3d4 not found after 30s - respawn attempt 1/10
[CheesePatrol] No backup found for NPC a1b2c3d4
…
[CheesePatrol] NPC a1b2c3d4 failed to respawn 10 times - pausing patrol (integrity check retries every 5 min)
```

And a recovery that came from the chunk finally loading rather than from a respawn:

```text
[CheesePatrol] NPC a1b2c3d4 found after 812 ticks missing - kick-starting patrol
```

That last line is the common one, and it is the reason the force-loading exists: most
"lost" NPCs were never lost.

---

← [Visuals](visuals.md) · [Dashboard API](dashboard.md) →
