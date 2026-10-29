[← CheeseRaids](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

21 source files, about 3 340 lines, no mixins, no client-side code. Written for whoever
maintains this next — including the frank part at the bottom.

---

## Package layout

```text
com.cheeseraids
├── CheeseRaidsMod.java          entrypoint: lifecycle hooks, tick hook, command registration
├── commands/
│   └── CheeseRaidsCommand.java  the whole Brigadier tree (102 lines)
├── data/                        pure data, Gson-friendly public fields, no behaviour
│   ├── RaidDefinition.java      static config of one raid + the default Hoopa definition
│   ├── RaidInstance.java        runtime state of one running raid
│   ├── PlayerRaidState.java     per-player state inside an instance
│   ├── RaidPhase.java           the 8-value lifecycle enum
│   └── Pos.java                 world-qualified position
├── logic/                       everything that happens
│   ├── RaidManager.java         orchestrator: I/O, scheduler, state machine, effects (740 lines)
│   ├── StarArenaGenerator.java  rift creation, boss arena, procedural parkour (844 lines)
│   ├── WaveManager.java         the overworld wave
│   ├── CheckpointTracker.java   per-tick parkour supervision
│   ├── BossProvider.java        boss strategy interface
│   ├── CustomGiantBossProvider.java  the only implementation
│   └── RewardManager.java       portals, fireworks, currency, loot commands
├── hooks/                       reflection bridges to third-party mods
│   ├── CobblemonSpawnHook.java  PokemonProperties → entity
│   ├── CobblemonBattleHook.java BattleRegistry → is the battle over
│   ├── EasyNpcHook.java         the herald's VISIBLE display attribute
│   └── CobbleSafariHook.java    Hoopa Ring portals
├── dashboard/
│   ├── CheeseRaidsDashboardModule.java  CheeseDash entrypoint, WebSocket wiring
│   └── RaidsApi.java            the six REST routes
└── util/
    └── Mc.java                  command execution, world/block/particle/sound resolution
```

`data` has no dependency on anything but Minecraft's `BlockPos`. `hooks` depends on nothing
in the mod except the logger. `logic` is where the coupling lives, and most of it is
`RaidManager` reaching into the other six classes.

## Who owns what

**`CheeseRaidsMod`** owns the `RaidManager` instance and three Fabric registrations:
`SERVER_STARTED` → `onServerStart`, `SERVER_STOPPING` → `onServerStop`,
`END_SERVER_TICK` → `tick`. It also holds the static `DASHBOARD_BROADCAST` consumer, which
is how the mod talks to the dashboard without compiling against it at that point.

**`RaidManager`** owns:

- `definitions` — `LinkedHashMap<String, RaidDefinition>`, mutated on load and reload;
- `active` — `ConcurrentHashMap<String, RaidInstance>`, one entry per running raid.
  Concurrent because the HTTP thread reads it;
- `lastRunDates` — the scheduler's anti-replay stamps;
- the five logic collaborators, and the effects queue `fx`;
- `tickCounter`, incremented every tick and used for every modulo-based cadence.

**`StarArenaGenerator`** owns the reveal queue and two session-scoped sets:
`nightLocked` (rifts whose midnight lock has been persisted this session) and
`builtCenters` (rifts whose boss arena has been built this raid). Both are in-memory only;
a restart mid-raid forgets them, which means the arena is rebuilt and the lock re-saved.
Harmless, but worth knowing.

**`CustomGiantBossProvider`** owns nothing. It is stateless by design: everything lives on
the `RaidInstance`, so `BossProvider.forDefinition()` can return a fresh instance on every
call and two concurrent raids never share state. The comment in `RaidManager` says so
explicitly.

## State and disk

Three files, all under `config/cheeseraids/`:

| Path | Written when | Read when |
|---|---|---|
| `raids/<id>.json` | the default is seeded on first run; `saveDefinition` rewrites one | server start, and every `reload` |
| `instances/<raidId>.json` | on `start`, on every phase transition, on every `enter`, every 100 ticks, and at server stop | server start, for crash recovery |
| `state.json` | whenever the scheduler fires, and at server stop | server start |

Every write is a synchronous `Files.writeString` on the **server thread**. For instance
files that is a few hundred bytes every five seconds per running raid, which is fine; it is
still blocking I/O in the tick loop, and a raid with twelve players writes a larger object.

Crash recovery is deliberate and specific. On start, every instance file whose phase is not
`IDLE` is loaded back into `active`, so the state machine picks up where it stopped. The
per-player state is what makes this safe: `lastCheckpoint` and `seed` survive, and `enter`
restores a returning player to their checkpoint instead of regenerating their branch. The
code comments tie this to two incidents in sibling mods — an inventory duplication and a
crash loop — and the lesson is stated as a hard constraint: **never regenerate, always
restore.**

Two gaps in that recovery. The loaded instance keeps absolute epoch-millisecond deadlines,
so a server down for an hour comes back with every timeout already expired and the raid
races to cleanup. And `builtCenters` being in-memory means the arena is rebuilt under
players who are already on it.

## Threading

Almost everything is on the server thread. That is the point.

| Runs on | What |
|---|---|
| server thread | `tick()` and therefore the whole state machine, the effects queue, the reveal queue, both spawn hooks, block placement, teleports, every command, every disk write |
| HTTP thread (CheeseDash) | `RaidsApi.handle` — parsing, serialising, responding |
| WebSocket thread (CheeseDash) | the `raid_update` broadcast, from `DashboardRegistry.broadcast` |

The hand-off is explicit. `start`, `stop` and `reload` all wrap their mutation in
`WebDashboard.executeOnServerThread(server, …)` and capture the result in a one-element
array so the HTTP response can report it. Reads do not hop threads: `definitions()` and
`activeInstances()` return **copies** (`new ArrayList<>(…)`) precisely so an HTTP thread can
iterate while the server thread mutates.

The copies are shallow. A `GET /instances` iterating a copied list still dereferences live
`RaidInstance` objects whose `players` map the server thread may be writing to. In practice
`LinkedHashMap` iteration during a concurrent `put` can throw, and the API's outer
try/catch turns that into a 500. It has not been seen, but it is the kind of race that only
shows up with twelve players entering at once.

`EasyNpcHook` and `CobblemonSpawnHook` resolve their reflection lazily inside
`synchronized` initialisers with a `volatile boolean init` guard. Both are only ever called
from the server thread, so the synchronisation is belt-and-braces rather than necessary.

## Mixins

**There are none.** No `cheeseraids.mixins.json`, no mixin package, nothing in
`build.gradle`. Everything the mod does, it does through the Fabric API, through
Minecraft's public server-side classes, and through reflection into other mods. For a mod
that spawns giant Pokémon in a generated dimension, that is a deliberate and welcome
restraint: there is no vanilla behaviour to patch.

There is also **no client-side code**. `fabric.mod.json` declares
`"environment": "*"` and a single `main` entrypoint, no `client` entrypoint. Every visual
effect is a server-sent particle, sound or title packet, which is why the raid needs no
client-side installation.

## The effects queue

Worth its own section, because it is the mod's one non-obvious design choice.

`FxTask` is an abstract class with a private `age` counter and a `step(age)` the subclass
implements, returning true when done. `tickFx()` walks the list, calls each one, and
**removes any task that throws** after logging it:

```java
try { done = it.next().tick(); }
catch (Throwable e) { LOGGER.warn("…FX task error: {}", e.getMessage()); done = true; }
```

The comment is *never let an FX bug stall the raid*, and that is the right instinct — with
one consequence that should be understood rather than fixed casually. The boss spawn lives
*inside* an FX task, at age 36 of `bossApparitionFx`. If that task throws at age 20, it is
dropped, the boss is never spawned, `bossSpawned` stays false, the victory check keeps
returning false, and the raid sits in `COMBAT` until `boss.combatTimeoutSeconds` expires —
fifteen minutes by default. Cosmetics and a load-bearing spawn share one fallible task.

There is one guard: if the rift world cannot be resolved, the task spawns the boss at age 0
and returns immediately, so a missing world does not silently skip the spawn.

## Extension points

**`BossProvider`** is the clean one. Five methods plus two defaulted tick hooks, all state
on the instance. Write a class, implement it, then edit `forDefinition()` to read
`def.boss.provider` — it currently ignores the field and always returns
`CustomGiantBossProvider`.

**A new raid** needs no code: drop a JSON file in `config/cheeseraids/raids/`. See
[the raid model](raid-model.md#writing-a-second-raid) for what you will hit.

**`Mc`** is where to add shared helpers. `Mc.run` in particular is worth reusing rather
than writing your own command execution: it builds a level-4 source whose output is
captured and logged, and it calls the dispatcher directly so a `CommandSyntaxException`
propagates to be logged instead of being swallowed. That was a real bug — the previous
`withSilent()` version hid refused `pokespawn` calls completely.

**A new hook** should copy `CobblemonSpawnHook`: lazy synchronized init, an `available()`
predicate, shape-based method matching, a distinct log line per failure, and a return value
the caller checks.

**A new phase** means editing the enum and the `switch` in `advance()`. Nothing is
table-driven; the state machine is a literal switch statement, which is readable and
entirely closed to extension.

## Known weaknesses and deliberate trade-offs

Stated plainly, because the mod is experimental and whoever picks it up deserves the list.

**Never tested in play.** The largest weakness by far. It compiles, it was deployed to
test instances, and no raid has ever been run with players. Nothing below has been
confirmed by observation.

**Two classes hold 47% of the code.** `RaidManager` at 740 lines mixes five
responsibilities: file I/O, the scheduler, the state machine, cinematic effects, and chat
or title formatting. `StarArenaGenerator` at 844 lines mixes dimension management, arena
construction, procedural geometry and the reveal animation. Both would split cleanly along
those seams, and both are the files you have to read entirely to change anything.

**Nine dead config keys.** `wave.spawnCommand`, `wave.entityType`, `wave.detectRadius`,
`wave.captureWindowMs`, `boss.spawnCommand`, `boss.scaleCommand`, `boss.entityType`,
`boss.detectRadius`, `boss.captureWindowMs`, plus `boss.provider`, `arena.model`,
`arena.parkourLength`, `arena.gapMin`/`gapMax`, `arena.riseMax` and
`npc.despawnAfterSeconds`. They are residue from a design where entities were spawned by
command and detected by snapshot diff. They are written to disk on first run, which makes
them look like a configuration surface. They are not.

**One piece of dead code.** `RaidManager.showHerald()` is private, complete, and called
from nowhere — the reveal path went through `heraldRevealFx` instead and this was left
behind. It is the only orphan in the mod.

**Every string is a French literal.** `assets/cheeseraids/lang/en_us.json` and `fr_fr.json`
each contain exactly one key, `cheeseraids.title`, and nothing reads it. Every message a
player sees is `Text.literal` with hardcoded `§` colour codes, in French. Converting the
mod to `Text.translatable` is a mechanical but wide change, touching `RaidManager`,
`CheckpointTracker`, `RewardManager`, `EasyNpcHook` and the command class.

**Four reflection hooks, three of them quiet.** Spawning fails loudly. The battle hook,
the scale hook and the CobbleSafari hook all degrade to a fallback or a warning, which
keeps the raid running and makes a broken dependency hard to notice. CobbleSafari is the
worst of them: it calls a **private** method in another mod, which owes it no stability at
all. [Integrations](integrations.md) has the full matrix.

**Blocking I/O in the tick loop.** Every instance save and `dm.save()` is a synchronous
file write on the server thread. The night-lock save is already guarded to once per rift per
session for exactly this reason; the instance saves are not.

**Large synchronous block operations.** `buildCenter` writes a disk of radius
`centerRadius`; `clearCenter` clears a cube of side `2 × (centerRadius + 17)` and height
`≈27` — roughly 55 000 block updates at the default radius, in one tick, with
`NOTIFY_LISTENERS`. On a void chunk that is cheap per block, but it is one tick's worth of
work either way.

**Shallow copies across the thread boundary.** Described under [threading](#threading).
The copy protects the list, not the objects in it.

**The effects queue swallows a load-bearing spawn.** Described under
[the effects queue](#the-effects-queue).

**Rewards are not gated on participation.** Every player in the instance who is online when
the boss dies is rewarded, whether or not they ever reached the disk. The `arrived` flag
exists and is tracked; the reward path does not read it.

**`stop` does not eject.** Both the command and the REST route clean up the arena without
teleporting anyone out. The eject path exists (`ejectAll`) and is called from the victory
sequence and the combat timeout, but not from `stop`.

**Deadlines are absolute epoch milliseconds.** A long downtime mid-raid resurrects an
instance whose every timeout has already passed.

---

← [Dashboard API](dashboard.md) · [Troubleshooting](troubleshooting.md) →
