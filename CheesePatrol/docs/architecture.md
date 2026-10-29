[← CheesePatrol](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

Ten source files, 2 313 lines, and 881 of those are one class. Small enough that this page
can cover all of it honestly, including the parts that are wrong.

---

## Package layout

```text
com.cheesepatrol
├── CheesePatrolMod.java            entrypoint: four lifecycle hooks, the tick, the command, the handlers
├── PatrolManager.java              881 lines — all behaviour and all state. The mod, really
├── PatrolData.java                 one patrol: waypoints, settings, transient flags, JSON in/out
├── PatrolCommand.java              the /npcpatrol Brigadier tree, 18 subcommands
├── PatrolWand.java                 the Blaze Rod and its custom-data tags
├── PatrolInteractionHandler.java   right-click entity → select, right-click block → add waypoint
├── PatrolMarker.java               armor-stand markers, route preview, 46 trail effects
├── mixin
│   └── EntityRemoveMixin.java      cancels Entity.remove for protected NPCs
└── dashboard
    ├── CheesePatrolDashboardModule.java   registers the "patrol" prefix with CheeseDash
    └── CheesePatrolApi.java               the two REST routes
```

Nothing is registered with a Minecraft registry. No items, no blocks, no entity types, no
sounds, no data components of its own. The wand is a vanilla `BLAZE_ROD` carrying a
`minecraft:custom_data` component, and the markers are vanilla armor stands with command
tags. That is why the mod has no resource pack, no textures and no model files.

## Who owns what

**`CheesePatrolMod`** does nothing but wire. `onInitialize` registers, in order:

| Hook | What it does |
|---|---|
| `SERVER_STARTING` | `PatrolManager.load()` — deliberately early, so the mixin has data before any chunk ticks |
| `SERVER_STARTED` | `forceloadActivePatrolChunks` |
| `SERVER_STOPPING` | back up every entity, save, remove every marker. Does **not** release forced chunks |
| `START_SERVER_TICK` | `PatrolManager.tick(server)` |
| `CommandRegistrationCallback` | the command tree |
| — | `PatrolInteractionHandler.register()` |

There is no `INSTANCE` and no singleton object. Everything in `PatrolManager` is `static`,
which is the mod's central structural decision and the source of several of the weaknesses
below.

**`PatrolManager`** owns the behaviour and all of the state:

```java
private static final Map<UUID, PatrolData> patrols            = new ConcurrentHashMap<>();
private static final Map<UUID, UUID>       showingParticles   = new ConcurrentHashMap<>();
private static final Map<UUID, Set<Long>>  patrolForcedChunks = new ConcurrentHashMap<>();
private static boolean debug = false;
private static volatile boolean loaded = false;
private static int backupTickCounter, recoveryTickCounter, particleTickCounter, startupDelayTicks;
```

The maps are concurrent because the HTTP thread reads `patrols`. The `Set<Long>` values
inside `patrolForcedChunks` are plain `HashSet`s, which is safe because only the server
thread touches them. The tick counters and `debug` are plain statics, server-thread only.

`tick(server)` is the whole runtime: it walks every patrol and calls `tickPatrol` for the
active ones with two or more waypoints, then runs four counters: startup integrity at 100
ticks, backups every 1200, the integrity check every 6000, particles every 10.

**`PatrolData`** is a mutable bean, one per patrol, with its persisted fields and a block of
`transient` ones that are never written:

| Persisted | Transient |
|---|---|
| `waypoints`, `waypointWaitTicks`, `speed`, `playerDetectionRadius`, `active`, `currentWaypointIndex`, `dimension`, `lastX/Y/Z`, the four particle fields, `pausedBySystem` | `waitingAtWaypoint`, `remainingWaitTicks`, `lookingAtPlayer`, `missingTicks`, `respawnAttempts`, `stuckTicks`, `stallTicks` |

`toJson` **always writes the seven core fields** (`npcUuid`, `speed`,
`playerDetectionRadius`, `active`, `currentWaypointIndex`, `dimension`, `waypoints`) and
omits the optional ones — `lastX/Y/Z`, the four particle fields, `pausedBySystem`, a
waypoint's `wait` — while they hold their default; `fromJson` leaves absent fields at their
constructor defaults. That keeps the file small and means adding a field requires touching
both methods or it silently never persists.

`fromJson` assigns fields **directly**, bypassing the clamping setters. The ranges
documented in [configuration](configuration.md) are enforced by the commands, not by the
loader.

**`PatrolWand`** is six static helpers over the `CUSTOM_DATA` component. It holds the
selection, which is why the wand has to be in your main hand for almost every subcommand.
Two of its six methods are never called.

**`PatrolMarker`** is three unrelated display systems sharing a file. See
[visuals](visuals.md).

## State and when it hits disk

Two files, both written synchronously from the server thread.

**`config/cheesepatrol_data.json`** is rewritten in full by `PatrolManager.save()`. Called
from: every mutating subcommand except `select`, both wand right-clicks (entity and block),
the respawn give-up path, the integrity check when anything changed, and `SERVER_STOPPING`.
`/npcpatrol select` creates the patrol record and pins its dimension in memory only; it
reaches disk with the next save. So adding a waypoint to a 50-patrol server
serialises and writes all 50 patrols, on the tick, before the command returns.

It is written with `Files.writeString` straight over the existing file. **No temporary file,
no atomic rename, no backup copy.** A crash mid-write loses every route.

**`config/cheesepatrol_entities/<uuid>.dat`** is one compressed NBT snapshot per patrol NPC,
written by `backupAllEntities` every 1200 ticks, by `tickPatrol` when a missing NPC
reappears, and at shutdown. The periodic pass writes *N* files back to back on the server
thread, which is the mod's largest single-tick cost at scale.

Reads happen once each: `load()` at `SERVER_STARTING`, and a `.dat` file only when a respawn
is attempted.

## Threading

| Thread | What runs there |
|---|---|
| **Server (main)** | everything in `PatrolManager`, the command tree, both interaction callbacks, all disk I/O |
| **HTTP** (CheeseDash) | `CheesePatrolApi.handle` and its two handlers |
| **Netty IO** | nothing. The mod registers no payloads and no network handlers |

The hand-off is `WebDashboard.executeOnServerThread(server, …)`, used by `POST /tp` for the
teleport. It blocks the HTTP thread for up to five seconds waiting for the task to
complete on the server thread, so a `200` means *finished*; a timeout surfaces as a 500.

⚠️ **`GET /list` touches entities from the HTTP thread.** `resolveNpcName` walks
`server.getWorlds()` and calls `world.getEntity(uuid)` with no hop to the server thread.
`ServerWorld.getEntity` reads the entity lookup, which the server thread mutates every tick
as entities are added and removed. It is not a documented thread-safe read. In practice it
returns a name or null and has not been seen to fail; correctly it should be wrapped in
`executeOnServerThread`, or the name should be cached on the `PatrolData` when the server
thread last saw the entity. The repository's own rule — read immutable or concurrent
structures from HTTP threads, hop for anything else — says this is on the wrong side of the
line.

The rest of the API is clean: `patrols` is a `ConcurrentHashMap` and the fields read from a
`PatrolData` are primitives, so a listing can tear across a tick boundary but cannot
corrupt anything.

## The mixin

One mixin, server-side, declared in `cheesepatrol.mixins.json` with `"required": true` and
`"defaultRequire": 1`.

```java
@Mixin(Entity.class)
public abstract class EntityRemoveMixin {
    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void cheesepatrol$preventPatrolRemoval(Entity.RemovalReason reason, CallbackInfo ci)
}
```

| | |
|---|---|
| **Target** | `net.minecraft.entity.Entity#remove(Entity.RemovalReason)`, at HEAD |
| **Why** | A patrol NPC that gets killed or discarded breaks a town silently. This is the only layer that stops a deliberate deletion |
| **Scope** | Every entity in the game. The filter is inside the method, not in the injection |
| **On failure** | The server **crashes at startup**. `defaultRequire: 1` makes a missed injection fatal |

Fatal-on-failure is the right choice here, and the opposite of what CheeseKart does with its
cosmetic client mixins. Silently losing removal protection would mean NPCs quietly
disappearing with no indication why — far worse than a server that refuses to boot after a
Minecraft update. If the method signature changes, you will know immediately.

The two real problems with it — a filesystem check per entity removal server-wide, and
unconditional `INFO` logging — are in
[keeping NPCs alive](resilience.md#layer-1-the-removal-mixin).

## The EasyNPC dependency is one reflective call

This is the mod's only contact with EasyNPC, and it is worth reading carefully because it is
the thing most likely to break.

**Where:** `PatrolManager.getEasyNPCGoalSelector(Entity)`, around line 655 of
`PatrolManager.java`.

```java
public static GoalSelector getEasyNPCGoalSelector(Entity entity) {
    try {
        Method method = entity.getClass().getMethod("getEntityGoalSelector");
        Object result = method.invoke(entity);
        if (result instanceof GoalSelector gs) return gs;
    } catch (Exception ignored) {
        // Not an EasyNPC entity or method not found
    }
    return null;
}
```

**Called from** three places, all of which clear the returned selector completely with
`gs.clear(g -> true)`:

- every 100 server ticks in `tickPatrol`, as maintenance
- `kickStartPatrol`, so `/npcpatrol start` takes effect immediately
- the stall watchdog, after 5 seconds of near-zero velocity

**Why it exists:** EasyNPC installs its own AI goals — look-around, idle wander, whatever
the NPC is configured for. Those goals fight the patrol for control of navigation, and
leaving them in place stops the NPC moving. Wiping them is the blunt fix. It also means a
patrolling NPC loses all of its EasyNPC behaviour, permanently, for as long as the patrol
exists.

⚠️ **The method name is hard-coded and will break when EasyNPC changes.**
`getEntityGoalSelector` is EasyNPC's own API method, not a Minecraft method, so it is not
remapped at runtime and a `getMethod(name)` lookup is legitimate — this is not the
intermediary-mapping trap that the repository's conventions warn about. The brittleness is
plain API stability: EasyNPC renames it, moves it to another interface, or returns a
different type, and the lookup starts returning `null`.

**And it fails silently.** The `catch` swallows everything with a comment, there is no log
line even under `debug`, and callers treat `null` as "not an EasyNPC, nothing to clear". The
symptom of a broken EasyNPC version is therefore not an error — it is NPCs that stop walking
for no stated reason, which is the single hardest failure in this mod to diagnose. A
contributor's first improvement should be a one-time warning the first time the lookup fails
on an entity whose class name contains `easy_npc`.

**`isAirWaypoint`, `PathAwareEntity` and everything else** are vanilla. The mod will drive
any `PathAwareEntity` from any mod; what it cannot do for a non-EasyNPC entity is clear that
mod's goals, so whether it moves depends on whether those goals fight it.

## The `compileOnly` oddity in `build.gradle`

CheesePatrol is the **only** mod in this repository that declares CheeseDash with plain
`compileOnly` rather than `modCompileOnly`:

```groovy
compileOnly files(fileTree(dir: "../CheeseDash/build/libs", includes: ["cheesedash-*.jar"], …))
```

Every sibling uses `modCompileOnly` or `modImplementation`. The difference matters: Loom's
`mod*` configurations remap the jar's Minecraft type references from the production
(intermediary) names into the dev-environment (Yarn) names before putting it on the compile
classpath. Plain `compileOnly` puts the production jar on the classpath untouched.

It compiles anyway, by luck rather than by design. Every CheeseDash API CheesePatrol touches
uses only types whose names are identical in both mappings:

- `HttpExchange`, `IOException`, `String`, `int`: JDK and primitives
- `MinecraftServer`: one of the few Minecraft classes that is not obfuscated
- `DashboardModule`, `WebDashboard`: CheeseDash's own classes

The first time someone calls a CheeseDash method taking a `ServerWorld`, a
`ServerPlayerEntity` or a `Text`, this build will fail with an unresolvable
`net.minecraft.class_NNNN`. If you extend the dashboard layer and get that error, change the
line to `modCompileOnly` rather than working around it.

The `compileOnly "maven.modrinth:easy-npc:PG3ZdKaC"` line is also vestigial: no EasyNPC
class is imported anywhere in the mod, so nothing needs it at compile time.

## Extension points

**A new subcommand.** Add a `literal` under the root in `PatrolCommand.register`, and a
handler that starts with `UUID npcUuid = getSelectedNpc(ctx); if (npcUuid == null) return 0;`
— that helper does the player check, the wand check and the error message. Call
`PatrolManager.save()` if you mutate anything.

**A new persisted patrol field.** Four places in `PatrolData`: the field, a getter and a
clamping setter, a line in `toJson` (guarded on non-default if you want the file to stay
small), and a line in `fromJson`. Forgetting `fromJson` is the easy mistake and makes the
field silently reset on every restart.

**A new particle effect.** Two places in `PatrolMarker`: a `case` in `resolveParticle` and
the name in the `PARTICLE_EFFECTS` list. Only the list feeds tab completion and the command's
validation, and only the switch feeds rendering, so missing either gives you a name that
cannot be set or a name that sets but shows nothing.

**A new REST route.** Add a branch in `CheesePatrolApi.handle`. Write the role guard as
`if (!WebDashboard.requireWrite(exchange)) return;`, the form `/tp` already uses — testing
the return value is what makes it a guard. Wrap anything touching the world in
`WebDashboard.executeOnServerThread`. That local guard is defence in depth: CheeseDash's
module dispatch already demands `OPERATOR` for every non-`GET` request before the module
runs. Two things it will not do for you: it keys only on the HTTP method, so a mutating
`GET` would arrive unguarded; and it never raises the bar above `OPERATOR`, so a route
that deserves `SUPER_ADMIN` must call `WebDashboard.requireAdmin` itself.

**WebSocket updates.** Nothing exists yet. `DashboardRegistry.broadcast(...)` from the
server thread is the mechanism; `advanceToNextWaypoint` and the active/inactive transitions
are the useful hook points. Do not broadcast per tick.

**Supporting another NPC mod.** Generalise `getEasyNPCGoalSelector` to scan
`entity.getClass().getMethods()` for a zero-argument method whose return type is
`GoalSelector`, instead of matching one name. That covers any mod exposing its selector
under any name, and it is the same technique the repository already mandates for reflecting
into Minecraft classes.

**Reaching this mod from another mod.** Everything in `PatrolManager` is public and static,
so `PatrolManager.get(uuid)`, `getAll()`, `kickStartPatrol(server, patrol)` and `save()` are
callable directly. Convenient, and the reason there is no instance to pass around.

## Known weaknesses

Stated plainly, because finding these by surprise is worse.

### Behaviour

- **Movement is a per-tick velocity override, not pathfinding.** NPCs walk straight at the
  next waypoint and do not route around obstacles. On a ground segment the mod *also* starts
  vanilla navigation toward the waypoint, so two systems are driving the same mob; the
  override is what you see. This is a wart, not a design.
- **The stuck watchdog measures distance, not progress**, despite its own comment. A
  legitimate walk longer than about 70 blocks at the default speed gets teleported every
  lap. See [patrol circuits](patrol-circuits.md#the-stuck-watchdog-60-seconds-teleport).
- **The look-at-player check has no line of sight and no cooldown**, so an NPC freezes for a
  player behind a wall and stays frozen while they are there — including suspending a
  waypoint wait countdown.
- **Blocking `RemovalReason.KILLED` does not prevent death.** By the code path, a patrol NPC
  that takes lethal damage dies and then is not removed. Make NPCs invulnerable in EasyNPC
  instead of relying on the mixin.
- **A respawn can create a duplicate UUID.** The conflict check only sees loaded worlds; if
  the original NPC is in an unloaded chunk, two entities end up claiming the same UUID and
  vanilla resolves it arbitrarily when that chunk loads.
- **`/npcpatrol select <uuid>` does not check `PathAwareEntity`**, unlike the right-click
  path. Selecting the wrong entity builds a patrol that reports `ACTIVE` and never moves,
  with no error anywhere.
- **Waits do not survive a restart.** `waitingAtWaypoint` is transient, so an NPC mid-pause
  restarts the pause after a reboot, and the startup kick-start five seconds later cuts it
  short. See [waiting at a waypoint](patrol-circuits.md#waiting-at-a-waypoint).

### Performance

- ⚠️ **The mixin does a `Files.exists` on every entity removal in the game**, on the server
  thread, for every entity that is not a patrol NPC, which is all of them. Thousands of
  filesystem stats per second on a busy Cobblemon server. The fix is an in-memory UUID set.
- **`save()` rewrites the entire data file synchronously on the server thread** on every
  mutation, including every single waypoint added by right-clicking blocks.
- **`backupAllEntities` writes one compressed NBT file per patrol, back to back, on the
  server thread**, every 60 seconds.
- **`removeMarkers` iterates every entity in the world** to find tagged armor stands.
- **`findEntity` loops over every loaded world** per patrol per tick, with a second full
  loop as a fallback when the dimension does not match.
- **The tick loop runs with no players online**, and a 512-block particle range multiplies
  packets by the number of players in range.

### Correctness and hygiene

- **No atomic write and no backup of `cheesepatrol_data.json`.** A crash mid-write loses
  every route.
- **`fromJson` does not clamp.** Hand-edited values outside the documented ranges are
  accepted and used.
- **No route can be stricter than `OPERATOR`.** `/tp` guards itself with `requireWrite`,
  and CheeseDash's module dispatch applies the same check to every non-`GET` request
  anyway; nothing calls `requireAdmin`, and the dispatch offers no way to ask for
  `SUPER_ADMIN`. An operator here can teleport any online player, by design.
- **`GET /list` reads entities from the HTTP thread** without hopping to the server thread.
- **Patrol chunk bookkeeping can cancel an admin `/forceload`** on a chunk a patrol walks
  through. See [keeping NPCs alive](resilience.md#patrol-chunk-tracking-can-cancel-your-own-forceload).
- **Forced chunks are never released at shutdown** by design, and
  `releaseAllForcedChunks`, the method that would, is never called. Removing the mod
  without clearing patrols first leaves them forced permanently.
- **The mixin logs at `INFO` with no `debug` guard** and no rate limit.
- **No `reload`.** The data file is read once at `SERVER_STARTING` and hand edits to a
  running server are overwritten.
- **No i18n.** Every player-facing string is an English `Text.literal`, and there is no
  `assets/cheesepatrol/lang/` directory at all. The repository's convention is
  `Text.translatable` with `en_us.json` and `fr_fr.json`; this mod predates or ignores it,
  which is also why the French documentation quotes English command output verbatim.
- **`getNavDescription()` returns hard-coded French** (`"Patrouilles NPC"`) while the rest
  of the dashboard is translated.
- **Two code comments contradict their constants.** The stall watchdog's comment says "2s"
  where the constant is 100 ticks (5 s); `particleYOffset`'s comment says "-1.0 to 1.0"
  where the clamp is ±5.
- **All state is `static`.** Fine for a dedicated server. On a client's integrated server
  the maps are global across world loads, saved only by `load()` clearing them first.
- **Dead code:** `PatrolWand.clearSelectedNpc`, `PatrolWand.sendWandStatus`,
  `PatrolData.getWaypointWaitTicks`, `PatrolData.getLastY`, `PatrolManager.isLoaded` and
  `PatrolManager.releaseAllForcedChunks` are all unreferenced.
- **A stray file in the mod directory:** `data/cobblemon/species/generation1/charizard.json`,
  322 lines, a Cobblemon species override sitting outside `src/main/resources` and therefore
  not packaged into the jar. It has nothing to do with patrols and nothing reads it.
- **A leftover licence block in `build.gradle`.** The `jar { from("LICENSE") … }` block
  still asks Gradle to bundle a `CheesePatrol/LICENSE`, but that file — an old MIT licence —
  has been deleted, so nothing is bundled. `fabric.mod.json` declares
  `PolyForm-Noncommercial-1.0.0`, and the repository-level [LICENSE](../../LICENSE) is the
  authoritative one.

---

← [Dashboard API](dashboard.md) · [Troubleshooting](troubleshooting.md) →
