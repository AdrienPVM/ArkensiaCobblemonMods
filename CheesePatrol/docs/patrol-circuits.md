[← CheesePatrol](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](patrol-circuits.fr.md)

# Patrol circuits

Everything on this page happens inside one method, `PatrolManager.tickPatrol`, which runs
once per server tick for every patrol that is **active and has at least two waypoints**. A
patrol that fails either condition is skipped entirely — no movement, no trail particles,
no stuck detection, nothing.

Read this page if you are designing a route, or if an NPC is doing something you did not
ask for.

---

## The model

A patrol is three things: an NPC UUID, an ordered list of block positions, and an index
into that list.

```text
PatrolData
├── npcUuid                 the entity it drives. Also the patrol's only identity
├── waypoints[]             ordered block positions
├── waypointWaitTicks[]     parallel list, one pause per waypoint
├── currentWaypointIndex    the waypoint being walked TOWARD
├── speed, playerDetectionRadius
└── transient flags         waiting, lookingAtPlayer, missingTicks, stuckTicks, stallTicks
```

The waits are a **parallel list**, not a field on a waypoint object. `add` and `remove`
keep the two lists in step; nothing else touches them. The only way they could drift is a
hand-edited data file, and the loader rebuilds both from the same JSON array, so they
cannot.

`currentWaypointIndex` is the waypoint the NPC is heading for, not the one it just left.
`/npcpatrol info` marks it `<-- current`, which reads as "this is the next stop".

There is no concept of a route, a path, or a schedule. There is a list and a cursor.

## The order of travel, and the last waypoint

Waypoints are visited in list order, and `advanceToNextWaypoint` is:

```java
currentWaypointIndex = (currentWaypointIndex + 1) % waypoints.size();
```

So the circuit is a **closed loop**. After the last waypoint the NPC walks back to the
first and keeps going. There is no end, no "patrol complete", no reversal, no ping-pong
mode and no way to configure one. The segment from the last waypoint to the first is a
real segment that the NPC will walk, and `/npcpatrol show` draws it, which is the easiest
way to notice that it cuts diagonally through a building.

Two consequences for route design:

- **Two waypoints make a line, not a loop.** The NPC paces A → B → A → B. That is the
  minimum a patrol will start with and it is a perfectly good idle animation for a shop
  keeper.
- **Close your own loop.** If you want the NPC to walk the perimeter of a square, place
  four waypoints at the corners, not five with a duplicate. The fifth would make the NPC
  arrive at the first corner, immediately register as arrived at the duplicate, and skip.

Reordering is not supported. `add` appends. To fix the middle of a circuit, remove every
waypoint after the mistake and re-add them, remembering that `remove` renumbers.

## Arrival, and what "close enough" means

Each tick, the NPC's squared distance to the target waypoint centre
(`x + 0.5`, `y`, `z + 0.5`) is compared to a threshold:

| Segment type | Threshold | In blocks |
|---|---|---|
| Ground | `2.25` | 1.5 |
| Flying | `25.0` | 5.0 |

Inside the threshold, the NPC counts as arrived. The flying threshold is deliberately
loose — holding an exact position by velocity override does not work, so a flying NPC that
gets within five blocks is treated as having made it.

On arrival, one of two things happens:

- the waypoint has a wait > 0 → navigation stops, velocity is zeroed, and the wait begins
- the waypoint has no wait → the index advances and navigation is pushed at the next
  waypoint in the same tick, with no pause at all

The second case is what makes a smooth corner smooth. The NPC never stops; it starts
aiming somewhere else 1.5 blocks before the corner, which rounds the turn.

## Waiting at a waypoint

A wait is stored per waypoint, in ticks, and set in seconds by `/npcpatrol wait <index>
<seconds>` or the trailing argument of `/npcpatrol add`. While waiting:

```text
remainingWaitTicks -= 1   each tick
```

When it reaches zero the flag clears, the index advances, and navigation is pushed at the
next waypoint.

Two things about the countdown that surprise people:

1. **It does not run while a player is nearby.** The look-at-player check happens before
   the wait handling and returns out of the method, so the decrement is skipped entirely.
   A 5-second wait with a player standing next to the NPC lasts as long as the player
   does, then takes its five seconds once they leave.
2. **It survives nothing.** `waitingAtWaypoint` and `remainingWaitTicks` are transient
   fields, not saved to disk. After a restart mid-wait the per-tick loop finds the NPC
   already on the waypoint and starts the wait over — but five seconds after start the
   startup kick-start clears the waiting flag and, since the NPC is still within 1.5 blocks,
   advances it. A long wait interrupted by a restart is therefore cut to about five
   seconds.

A wait of `3600` seconds is accepted, which is the supported way to park an NPC somewhere
for an hour at a time. A hand-edited data file can exceed that, because the loader does
not re-validate the ceiling.

## The look-at-player pause

The behaviour the mod exists for, and the simplest piece of code in it:

```java
var nearestPlayer = mob.getWorld().getClosestPlayer(mob, patrol.getPlayerDetectionRadius());
if (nearestPlayer != null) {
    mob.getNavigation().stop();
    mob.setVelocity(Vec3d.ZERO);
    mob.getLookControl().lookAt(nearestPlayer, 30.0f, 30.0f);
    patrol.setLookingAtPlayer(true);
    return;
}
```

| Question | Answer |
|---|---|
| **Radius?** | `playerDetectionRadius`, default `5.0`, settable `1.0`–`32.0` per patrol |
| **Shape?** | A sphere. A player 10 blocks overhead is 10 blocks away and does not trigger a 5-block radius |
| **Duration?** | None. It lasts exactly as long as a player is inside the radius |
| **Line of sight?** | Not checked. An NPC turns toward a player through a wall, a floor or a closed door |
| **Which player?** | The closest one. Spectators are excluded by vanilla's own filter |
| **Priority?** | Absolute. It is checked before waiting and before movement, and returns out of the tick |
| **Turn rate?** | `lookAt(player, 30.0f, 30.0f)` — up to 30° of yaw and 30° of pitch per tick, so the head snaps round in a few ticks rather than instantly |

That `return` is the whole priority system. While a player is in range, the NPC does not
move, does not advance its waypoint, does not count down a wait, and does not accumulate
stuck or stall ticks. The patrol is frozen, pointed at the player.

The absence of a line-of-sight check is the limitation you will notice first. An NPC
inside a two-block-thick shop wall stops every time a customer walks past outside. The
fix is a smaller radius, or a route that keeps the NPC away from walls players use.

## Speed is not blocks per second

`speed` feeds two different systems with two different scales, which is why the number
feels arbitrary.

**For the per-tick velocity override**, which is what you actually see:

| | Multiplier | At `speed 0.5` | At `speed 1.0` |
|---|---|---|---|
| Ground | `speed × 0.12` | 0.06 blocks/tick ≈ **1.2 blocks/s** | ≈ 2.4 blocks/s |
| Flying | `speed × 0.15` | 0.075 blocks/tick ≈ **1.5 blocks/s** | ≈ 3.0 blocks/s |

A vanilla player walks at about 4.3 blocks per second, so `speed 0.5` is a genuine stroll
and `speed 2.0`, at about 4.8 blocks per second, already outpaces a walking player.

**For vanilla navigation**, `moveToCurrentWaypoint` passes the same number straight into
`mob.getNavigation().startMovingTo(x, y, z, speed)`, where it is a multiplier on the
entity's movement-speed attribute — a completely different scale, on which `1.0` means
"the mob's normal speed".

Both are in play on a ground segment: navigation is started when the waypoint changes, and
then the velocity override is applied every tick. From watching NPCs, the override is what
determines the apparent speed. That the same field drives both scales is a wart, not a
design.

## Ground movement

Each tick, on a ground segment, if the horizontal distance to the waypoint is more than
0.3 blocks:

- horizontal velocity is set to the unit vector toward the waypoint times `speed × 0.12`
- **vertical velocity is left alone**, so gravity, slabs and stairs still work
- `velocityModified` is set, so the change is sent to clients
- yaw, head yaw and body yaw are all interpolated 15% of the way toward the target
  direction each tick, which is what makes the turn look like a turn rather than a snap

Note what is missing: no jumping, no path, no obstacle test. The NPC walks into whatever
is in front of it. A one-block step up is climbed only if the mob's own step height allows
it; a fence is a wall. This is the single most important thing to design around.

Every tick the mod also calls `mob.setPersistent()` and sets `fallDistance = 0`. The first
stops vanilla despawning; the second means a patrol NPC never takes fall damage, which
matters because of the next section.

## Flying segments

A waypoint is a *flying* waypoint if there is no solid block at it or within three blocks
below it:

```java
for (int dy = 0; dy >= -3; dy--) {
    if (world.getBlockState(target.add(0, dy, 0)).isSolidBlock(...)) return false;
}
return true;   // nothing solid within 3 below — fly
```

This is evaluated **per target waypoint, every tick**, not per segment. The consequences:

- The moment the NPC targets a flying waypoint, `NoGravity` is switched on and the NPC
  lifts off the ground it is standing on. It does not walk to the edge first.
- The moment it targets a ground waypoint again, `NoGravity` is switched off and it drops.
  The per-tick `fallDistance = 0` is what stops that killing it.
- A waypoint placed one block above a floor is a **ground** waypoint, because the floor is
  within three blocks. You have to be genuinely over a void or four blocks up to get
  flight.

Flying movement is pure velocity, with one nicety. Within 8 blocks of the target, the
direction blends toward the *next* waypoint on a cubic ease, up to 70%:

```java
double t = 1.0 - (dist / 8.0);
double blend = t * t * t * 0.7;
```

That produces an arc through the waypoint instead of a stop-and-turn, which is why a
flying circuit reads as a circling bird rather than a drone hitting marks. Yaw is
interpolated at 8% per tick, gentler than the ground's 15%, for the same reason.

There is no pitch control and no collision handling in flight. A flying NPC will fly into
a cliff face and stay there until the stuck watchdog teleports it.

## When an NPC gets stuck

Two independent watchdogs, with different triggers and different cures. Both are skipped
while the NPC is waiting at a waypoint or looking at a player — those flags are read from
the previous tick, so there is a one-tick window where a counter can tick up as a player
arrives. Harmless.

### The stall watchdog: 5 seconds, re-navigate

Every tick, if horizontal velocity squared is below `0.001` (about 0.03 blocks/tick), a
counter runs. At **100 ticks — 5 seconds — of near-zero velocity**:

1. wipe the EasyNPC goal selector
2. stop navigation
3. if the NPC is already within 1.5 blocks of its waypoint, advance to the next one
4. push navigation at the waypoint again

This is the gentle cure, and the one that fires most often. It catches the common failure
where EasyNPC's own idle goals reassert themselves and pin the NPC in place, and the case
where the NPC arrived at a waypoint in a way the arrival check missed.

The code comment above this block says "2s"; the constant is 100 ticks, which is 5
seconds. Trust the constant.

### The stuck watchdog: 60 seconds, teleport

Every tick, if the NPC is more than 2 blocks from its target waypoint, a second counter
runs. At **1200 ticks — 60 seconds — more than 2 blocks away**:

```java
mob.refreshPositionAndAngles(target.x + 0.5, target.y, target.z + 0.5, yaw, pitch);
```

The NPC is teleported onto the waypoint and the patrol is kick-started. Coming within 2
blocks at any point resets the counter.

⚠️ **This is not a progress check, despite the comment calling it one.** It measures
distance, not movement. An NPC walking perfectly along a long straight accumulates stuck
ticks the whole way, and gets teleported if the walk takes more than a minute. At the
default `speed 0.5` that is roughly **70 blocks**:

| Speed | Blocks walked in 1200 ticks |
|---|---|
| `0.5` | ≈ 72 |
| `1.0` | ≈ 144 |
| `0.25` | ≈ 36 |

So the practical rule is: **keep segments well under 70 blocks at default speed**, or raise
the speed, or split the long leg with an intermediate waypoint. A route that teleports the
NPC every lap looks exactly like a route that works, until someone is standing there to
watch.

## Where the per-tick decisions happen, in order

For reference when reading the code or debugging a patrol that is doing two things at once:

```text
1.  find the entity               missing → force-load, count, respawn ladder, STOP
2.  not a PathAwareEntity?        STOP — permanently, for this patrol
3.  was missing, now found?       back it up and kick-start
4.  setPersistent, fallDistance=0
5.  every 100 server ticks        wipe the EasyNPC goal selector
6.  record last known position
7.  stuck watchdog                may teleport, then kick-start
8.  update force-loaded chunks    NPC chunk + target waypoint chunk
9.  every 2 server ticks          spawn trail particles
10. stall watchdog                may re-navigate
11. player within radius?         stop, look at them, STOP
12. waiting at a waypoint?        count down, maybe advance, STOP
13. flying or ground?             set NoGravity accordingly
14. within arrival threshold?     start a wait, or advance, STOP
15. move                          velocity override + yaw interpolation
```

Step 2 is worth noticing: if the UUID belongs to something that is not a `PathAwareEntity`
— which `/npcpatrol select` will happily let you do — every tick reaches step 2 and gives
up, forever, with no log line and no error. The patrol shows `ACTIVE` and nothing moves.

---

← [Configuration](configuration.md) · [Visuals](visuals.md) →
