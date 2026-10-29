[← CheesePatrol](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

Symptom, cause, fix. The first five sections cover why an NPC that should be walking is
not, which is the overwhelming majority of reports.

Before anything else, turn on verbose logging — most of these diagnoses are a single log
line away. Stop the server, set `"debug": true` in `config/cheesepatrol_data.json`, start
it again. Full instructions in [configuration](configuration.md#debug).

---

## The patrol says ACTIVE and the NPC does not move at all

**Cause.** Five distinct things produce this identical symptom. Work down the list.

1. **Fewer than two waypoints.** `tickPatrol` skips any patrol with `waypoints.size() < 2`
   without logging anything. `/npcpatrol info` tells you.
2. **The entity is not a `PathAwareEntity`.** If you selected it with
   `/npcpatrol select <uuid>` rather than by right-clicking, nothing validated it. The tick
   loop returns at the type check, silently, forever. Right-click the NPC with the wand
   instead — that path refuses a bad entity out loud.
3. **The entity is not loaded.** Check for `NPC … not found after 30s - respawn attempt`
   in the log under `debug`. Usually a chunk that is not loading; see
   [the NPC disappears](#an-npc-disappears-and-comes-back-somewhere-else) below.
4. **The EasyNPC reflection is broken.** If EasyNPC has updated and renamed
   `getEntityGoalSelector`, the lookup fails silently and the NPC's own idle goals keep it
   pinned. There is no log line for this at all. Diagnose by elimination: attach a patrol
   to a plain vanilla mob — say a zombie — and see whether that walks. If the zombie
   patrols and the EasyNPC does not, this is your cause. See
   [the EasyNPC dependency](architecture.md#the-easynpc-dependency-is-one-reflective-call).
5. **A player is standing inside the detection radius.** The look-at-player check freezes
   the patrol with absolute priority and no timeout, so an NPC with someone parked next to
   it never moves. Step back more than `radius` blocks and watch again.

**Fix.** For 1, add waypoints. For 2, `/npcpatrol clear` and reselect by right-clicking.
For 3, see the dedicated section. For 4, pin EasyNPC to a version that works and open an
issue. For 5, lower the radius or move the route away from where players loiter.

## Start says the NPC was not found

**Symptom.**

```text
[CheesePatrol] Patrol started at waypoint #1/4 (NPC not found — will start when loaded)
```

**Cause.** `kickStartPatrol` could not find the entity by UUID. It searched the patrol's
recorded dimension first and then every loaded world, so the entity is genuinely not in
memory. Three reasons:

- its chunk is not loaded (most common, and usually transient)
- it is in a dimension that is not loaded on this server
- it is actually gone

**Fix.** Usually nothing — the patrol is active and the mod will force-load the last known
chunk every tick until the NPC appears, then kick-start it automatically. Watch for:

```text
[CheesePatrol] NPC a1b2c3d4 found after 812 ticks missing - kick-starting patrol
```

If it never appears, the respawn ladder takes over after 30 seconds. If that also fails ten
times you get a `failed to respawn 10 times` error and the patrol is system-paused, which
the integrity check retries every five minutes. At that point check whether
`/npcpatrol info` shows a `Dim:` that still exists on this server — a patrol pinned to a
deleted CheeseGuard dimension can never recover, and the log says so:

```text
[CheesePatrol] Cannot respawn NPC a1b2c3d4 - dimension 'cheeseguard:arena_01' not found
```

For that case, `/npcpatrol clear` the patrol and rebuild it on an NPC that exists.

## The NPC walks into a wall and stays there

**Cause.** Working as built. Movement is a per-tick velocity override with no pathfinding
and no obstacle test, so a waypoint the NPC cannot reach in a straight line is a waypoint it
cannot reach.

**Fix.** Redesign the segment. Run `/npcpatrol show` and look along each line — any line
that passes through geometry is a broken segment. Add intermediate waypoints so each
consecutive pair has clear line of sight. Avoid fences, gates, carpets on stairs and
one-block steps larger than the mob's step height.

The stuck watchdog will eventually teleport it onto the waypoint after 60 seconds, so a
badly routed patrol looks like it works from a distance. That is not a fix, it is the
symptom hiding itself.

## The NPC teleports instead of walking a long straight

**Symptom.** With `debug: true`:

```text
[CheesePatrol] NPC a1b2c3d4 stuck for 60s at dist 14.2 - teleporting to waypoint 3
```

and the NPC jumps the last stretch every lap.

**Cause.** The stuck watchdog measures *distance from the waypoint*, not progress. Any
continuous 60 seconds spent more than 2 blocks from the target trips it, including a
perfectly healthy walk. At the default `speed 0.5` the limit is about **70 blocks of
segment**.

**Fix.** Either split the long leg with an intermediate waypoint, or raise the speed. At
`speed 1.0` the limit roughly doubles to 144 blocks. The arithmetic is in
[patrol circuits](patrol-circuits.md#the-stuck-watchdog-60-seconds-teleport).

## The NPC stops for a player who is not even visible

**Cause.** The look-at-player check is a plain spherical distance query with **no
line-of-sight test**. A player behind a wall, below a floor or on the other side of a
closed door counts.

**Fix.** Lower `/npcpatrol radius`, or move the route. There is no configuration for
line-of-sight; adding one means adding a `raycast` call next to the
`getClosestPlayer` call in `tickPatrol`.

Note the knock-on effect: a waypoint wait does not count down while the NPC is looking at a
player, so an NPC with a permanent neighbour never completes its pause either.

---

## The mod does not load at all

**Symptom.** No `[CheesePatrol]` lines in the log, and `/npcpatrol` does not exist.

**Cause and fix**, in order of likelihood:

| Cause | Fix |
|---|---|
| CheeseDash is missing | `"cheesedash": "*"` is in `depends`. The loader refuses to start — look for a Fabric dependency error screen naming `cheesepatrol`, not a silent failure |
| Wrong Minecraft or loader version | `~1.21.1` and loader `≥ 0.16.0` are required |
| Java below 21 | the jar is compiled at release 21 and will not load on 17 |
| You deployed `cheesepatrol-1.0.0+1.21.1-sources.jar` | deploy the plain jar |
| Two copies of the jar in `mods/` | delete the old one |

**Symptom.** The server crashes at startup with a mixin error mentioning
`EntityRemoveMixin`.

**Cause.** `cheesepatrol.mixins.json` sets `"required": true` and `"defaultRequire": 1`, so
a failed injection into `Entity.remove` is fatal rather than skipped. That is deliberate —
silently losing removal protection would be worse. In practice it means a Minecraft version
this mod has not been built against.

**Fix.** Use 1.21.1, or rebuild the mod against your version. Do not work around it by
relaxing the mixin requirement: an NPC-protection mixin that silently does nothing is a
trap.

## The Patrols view is missing from the dashboard sidebar

**Cause.** CheeseDash builds its sidebar from the `cheesedash` entrypoints it discovered at
startup. A missing entry means one of:

- CheesePatrol did not load at all — check for its log lines first
- the CheeseDash jar in `mods/` is older than the `DashboardModule` interface the mod was
  compiled against
- you are looking at a cached page

**Fix.** Confirm `[CheesePatrol] Ready.` is in the log, hard-refresh the dashboard, and
check the module list directly:

```bash
curl http://localhost:44311/api/v1/modules -H 'Authorization: Bearer ses_…'
```

`patrol` should be in the response. If it is and the sidebar is still empty, the problem is
on the CheeseDash side, not here.

## The dashboard answers 503 CheesePatrol not ready

**Cause.** `CheesePatrolDashboardModule` holds its `MinecraftServer` reference only between
`onServerStart` and `onServerStop`. A 503 means the Minecraft server is not running — you
are either in the window before it has started, or after a `/stop` with the HTTP server
still up.

**Fix.** Wait for the server, or start it.

## Editing cheesepatrol_data.json has no effect

**Cause.** The file is read exactly once, at `SERVER_STARTING`. There is no reload command.
Worse, it is rewritten in full from memory by `PatrolManager.save()` on every mutation — so
an edit made while the server runs is not merely ignored, it is overwritten the next time
anyone adds a waypoint or starts a patrol.

**Fix.** Stop the server, edit, start. That is the only supported sequence, and it is how
you change `debug`.

## A command is refused

| What you see | Cause | Fix |
|---|---|---|
| vanilla *Unknown or incomplete command* | you are below permission level 2 | `/op` yourself, or raise the permission level |
| `Player only` | you ran it from the console, RCON or a command block. Only `list` works there | run it in game |
| `[CheesePatrol] No NPC selected. …` | the Patrol Wand is not in your **main hand**, or it is a wand with no selection stored | `/npcpatrol wand`, then right-click the NPC |
| `[CheesePatrol] Hold a Patrol Wand first!` | `select` needs the wand to write the UUID into | get a wand |
| `[CheesePatrol] No entity found with UUID …` | the entity is not loaded, or not in **your** current dimension — `select` only searches your own world | go to its dimension, or right-click it |
| `[CheesePatrol] Invalid waypoint index.` | `remove` or `wait` given an index beyond the list. Indexes are 1-based | `/npcpatrol info` for the real numbering |
| `[CheesePatrol] Need at least 2 waypoints …` | exactly that | add waypoints |
| `[CheesePatrol] Unknown particle type: …` | the name is not one of the 46 | the error lists them all; tab completion is easier |
| vanilla *Incomplete command* after `/npcpatrol add 5000` | a single number above 3600 falls out of the wait branch and is parsed as an `x` | use `/npcpatrol wait` for long pauses, or give all three coordinates |

## `/kill` does nothing to a patrol NPC

**Cause.** The removal mixin blocks `KILLED` and `DISCARDED` for any entity with a patrol
record **or** a leftover backup file in `config/cheesepatrol_entities/`. You will see:

```text
[CheesePatrol] Blocked removal (KILLED) of registered NPC a1b2c3d4 (patrol active)
```

**Fix.** `/npcpatrol clear` on that NPC first — it removes the record *and* deletes the
backup file, lifting both layers of protection. Then kill it.

If you have already removed the mod and are stuck with an unkillable NPC, there is nothing
blocking you any more; the mixin is gone with the jar.

## The log is full of Blocked removal lines

**Cause.** Something is repeatedly trying to delete a protected NPC. The mixin logs every
single attempt at `INFO`, with no rate limit and **not** suppressed by `debug: false`.

**Fix.** Find what is doing the deleting — an entity-cleanup plugin, a repeating command
block with a broad `@e` selector, a mob-cap script. Exclude `tag=cheesepatrol` NPCs from it,
or narrow the selector. The log lines are a symptom, and silencing them is not possible
without changing the mod.

## An NPC disappears and comes back somewhere else

**Cause.** This is the respawn ladder doing its job. The entity went missing for 30 seconds,
the mod rebuilt it from its NBT snapshot, and placed it **on its current waypoint** rather
than where it was lost. Under `debug`:

```text
[CheesePatrol] NPC a1b2c3d4 not found after 30s - respawn attempt 1/10
[CheesePatrol] Respawned NPC a1b2c3d4 at waypoint 128, 65, -340
```

**Fix.** Usually none needed. But a *repeating* cycle means the entity keeps going missing,
and that is worth chasing: the NBT snapshot is up to a minute stale, so each round trip can
lose EasyNPC state changes. Look for a chunk that will not stay loaded, or another mod
removing the entity in a way the mixin's allowed-reasons list lets through.

⚠️ If you see the same NPC existing twice, this is the known duplicate-UUID risk: the
conflict check only sees loaded worlds, so a respawn while the original sits in an unloaded
chunk creates a second copy. `/npcpatrol clear`, remove both entities, and rebuild the
patrol.

## Waypoint markers will not go away

**Cause.** `/npcpatrol markers` only ever deletes-and-replaces. There is no off switch, and
`/npcpatrol clear` removes markers **only in the world you are standing in**.

**Fix.**

```mcfunction
/kill @e[type=armor_stand,tag=cheesepatrol]
```

in each affected world. A clean server shutdown also removes every tagged stand in every
world, so a restart clears them; a crash does not.

## `/forceload` keeps getting removed

**Cause.** A known bug. When a patrol needs a chunk that is already forced, the mod skips
forcing it but still records it as its own, and releases it later when the NPC walks away —
cancelling your forceload.

**Fix.** Keep hand-forced chunks off patrol routes, or re-add them. Details in
[keeping NPCs alive](resilience.md#patrol-chunk-tracking-can-cancel-your-own-forceload).

## Forced chunks remain after uninstalling

**Cause.** Forced chunks are deliberately not released at shutdown, and removing the jar
leaves nothing to release them.

**Fix.**

```mcfunction
/forceload query
/forceload remove <x> <z>
```

Prevent it next time by running `/npcpatrol clear` on every patrol **before** removing the
mod. The full procedure is in
[installation](installation.md#uninstalling-cleanly).

## Trail particles do not appear

Check in this order:

1. **Is the patrol active?** Trail particles are spawned from `tickPatrol`, which only runs
   for active patrols with two or more waypoints. A stopped patrol has no trail.
2. **Is the NPC loaded and found?** No entity, no particles.
3. **Are you inside `particlerange`?** Default is 16 blocks. `/npcpatrol particlerange 48`.
4. **Is the effect `none`?** `/npcpatrol info` does not show the particle effect; read
   `cheesepatrol_data.json`, where `particleEffect` is omitted entirely when it is `none`.
5. **Is the offset pushing them inside the model or into a wall?** Reset with
   `/npcpatrol particleheight 0` and `/npcpatrol particledepth 0`.

Particles are sent with `force = true`, so a client's particle setting is not the problem.

## The route preview shows nothing

**Cause.** `/npcpatrol show` is suppressed, with no message, when you are in a different
dimension than the patrol. `/npcpatrol info` shows which one under `Dim:`.

**Fix.** Go to that dimension. Also note only one patrol can be previewed per player at a
time — showing a second one silently replaces the first.

---

## Producing a useful bug report

Open it at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) with:

**1. The log, with `debug: true` enabled.** `logs/latest.log`. Search it for
`[CheesePatrol]` and include every match, in order, from server start to the failure.
Without the debug flag most of this mod's diagnostics do not exist.

**2. The patrol itself.** The relevant object from `config/cheesepatrol_data.json`, or the
output of `/npcpatrol info` with the NPC selected. The waypoint coordinates matter — most
movement problems are geometry.

**3. Versions.** Minecraft, Fabric Loader, Fabric API, CheeseDash, **EasyNPC** and the
CheesePatrol jar name. EasyNPC's version is the one people forget and the one most likely
to be the cause, because the entire integration is a single reflective call on a method
name.

**4. What the NPC does, in one sentence.** "Walks two corners then stops", "teleports the
last stretch of each lap", "never leaves the spot". Combined with the waypoint coordinates
that is usually enough to identify which of the five causes in the first section applies.

**5. Whether a vanilla mob patrols correctly.** Attach a short patrol to a zombie and say
whether it walks. This one test separates "the mod is broken" from "the EasyNPC integration
is broken", and it is the single most useful thing you can include.

For a crash, include the full stack trace, not the summary line. For anything involving the
dashboard, include the HTTP status and the response body.

---

← [Architecture](architecture.md) · [CheesePatrol](../README.md) →
