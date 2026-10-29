# CheesePatrol

> 🇫🇷 [Lire en français](README.fr.md)

> Waypoint circuits for EasyNPC entities: an NPC walks a loop and pauses to look at
> players who come close.

EasyNPC gives you NPCs that stand where you put them. This makes them walk. You mark a
circuit with a wand — right-click the NPC, then right-click the blocks you want it to
visit — give it a speed, and start it. The NPC walks waypoint 1 → 2 → 3 → back to 1,
forever, and whenever a player steps inside its detection radius it stops dead and turns
to face them until they leave.

The walking is not EasyNPC's pathfinding. CheesePatrol overrides the mob's velocity
directly every tick and wipes EasyNPC's AI goals every five seconds, because leaving
those goals in place stops the NPC moving at all. That makes the movement reliable and
dumb: an NPC walks in a straight line toward its next waypoint and will press itself
against a fence rather than route around it. Put waypoints where the NPC can see the next
one.

Around that core sits a lot of machinery that exists for one reason — a patrol NPC must
never disappear. The mod mixes into `Entity.remove` to block kills and despawns, keeps
the NPC's chunk force-loaded, snapshots every NPC's NBT to disk once a minute, and
respawns one from that snapshot if it vanishes anyway. That machinery has costs, and
[architecture](docs/architecture.md) is candid about them.

**Minecraft 1.21.1 · Fabric · Java 21 · 10 source files, ~2 310 lines · server-side**

---

## Requirements

| | |
|---|---|
| **Required** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Required in practice** | [EasyNPC](https://modrinth.com/mod/easy-npc) — or any mod whose NPCs extend `PathAwareEntity` |
| **Clients** | nothing. `environment` is `server` |

CheesePatrol never imports an EasyNPC class. It accepts any entity that extends vanilla
`PathAwareEntity`, and reaches EasyNPC through a single reflective call looking for a
`getEntityGoalSelector()` method. ⚠️ That method name is **hard-coded** in
`PatrolManager.getEasyNPCGoalSelector` — when EasyNPC renames or removes it, the lookup
fails silently and NPCs stop moving. See
[how the EasyNPC dependency works](docs/architecture.md#the-easynpc-dependency-is-one-reflective-call).

## Install

```bash
cd CheeseDash      && ./gradlew build     # CheesePatrol compiles against its jar
cd ../CheesePatrol && ./gradlew build
```

Drop `build/libs/cheesepatrol-1.0.0+1.21.1.jar` and the CheeseDash jar into the server's
`mods/`, next to EasyNPC. The mod writes nothing to disk until you create your first
patrol or stop the server, whichever comes first — the shutdown hook always saves, so the
first clean stop leaves an empty `config/cheesepatrol_data.json`. On start it logs:

```text
[CheesePatrol] Initializing...
[CheesePatrol] Ready.
[CheesePatrol] Early-loaded 0 patrol(s) for mixin protection
[CheesePatrol] Forceloaded chunks for 0 patrol(s) (0 active, 0 inactive)
```

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, build order, what the first patrol creates on disk, the log lines that prove it loaded, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: from a fresh server to an NPC genuinely walking a four-point circuit and stopping for you |
| [Commands](docs/commands.md) | All 18 subcommands, argument by argument, with permission levels, output and every failure message |
| [Configuration](docs/configuration.md) | `cheesepatrol_data.json` in full — there is no settings file, only this state file and one `debug` flag |
| [Patrol circuits](docs/patrol-circuits.md) | The model: how waypoints are ordered, what happens at the last one, arrival thresholds, waiting, flying segments, the look-at-player pause, the two stuck watchdogs |
| [Visuals](docs/visuals.md) | Route preview particles, armor-stand markers, and the 46 trail effects with their offsets and range |
| [Keeping NPCs alive](docs/resilience.md) | The removal-blocking mixin, chunk force-loading, NBT backups, the respawn ladder and the integrity check |
| [Dashboard API](docs/dashboard.md) | The two REST routes under `/api/v1/patrol`, required roles, and why there is no WebSocket channel |
| [Architecture](docs/architecture.md) | Package layout, class responsibilities, threading, the mixin, extension points, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — starting with the three reasons an NPC that should be walking is not |

## Commands at a glance

One command, `/npcpatrol`, 18 subcommands, all at **permission level 2**. Every
subcommand except `wand` and `list` reads the selected NPC out of the **Patrol Wand you
are holding**, so the wand has to be in your main hand.

| Command | What it does |
|---|---|
| `/npcpatrol wand` | gives you a Patrol Wand (a named Blaze Rod) |
| `/npcpatrol select <uuid>` | selects an NPC by UUID instead of right-clicking it |
| `/npcpatrol add [waitSeconds]` | adds a waypoint at your feet |
| `/npcpatrol add <x> <y> <z> [waitSeconds]` | adds a waypoint at coordinates |
| `/npcpatrol remove <index>` | deletes waypoint `index` (1-based) |
| `/npcpatrol clear` | deletes the whole patrol, its markers and its backup |
| `/npcpatrol start` · `stop` | runs or halts the circuit |
| `/npcpatrol speed <value>` | walk speed, `0.1`–`5.0`, default `0.5` |
| `/npcpatrol radius <value>` | player detection radius in blocks, `1.0`–`32.0`, default `5.0` |
| `/npcpatrol wait <index> <seconds>` | pause at one waypoint, `0`–`3600` |
| `/npcpatrol list` · `info` | all patrols, or the selected one in detail |
| `/npcpatrol show` · `markers` | draw the route in particles, or plant named armor stands |
| `/npcpatrol particles <type>` | trail effect behind the NPC, 46 values |
| `/npcpatrol particleheight` · `particledepth` · `particlerange` | place and limit the trail |

`wait` takes a **waypoint index and a number of seconds**, in that order — this mod's
earlier README described it as a single tick count. Details in
[commands](docs/commands.md).

## Dashboard

Registers under the `patrol` prefix. The view is a read-only table of every patrol — NPC
name, dimension, current waypoint over total, status, speed — plus one action: teleport
yourself to the waypoint *after* the one the NPC is heading for, so you intercept it
instead of arriving behind it. Creating and editing circuits is commands-only.

## Known limits

- **Movement is a velocity override, not pathfinding.** NPCs do not route around
  obstacles. A waypoint behind a wall is a waypoint the NPC never reaches on its own.
- **Long segments get teleported.** The stuck watchdog teleports the NPC to its waypoint
  after 60 seconds spent more than 2 blocks from it, which a legitimate walk of roughly
  70 blocks or more will trip. Keep segments short.
- **The look-at-player pause has no line-of-sight check.** An NPC turns toward a player
  standing behind a wall, and will not resume while they stay there.
- **Patrol NPCs cannot be killed** while their patrol is registered. `/kill` is blocked
  by a mixin. Run `/npcpatrol clear` first.
- **No patrol route can require SUPER_ADMIN.** `POST /api/v1/patrol/tp` needs OPERATOR,
  enforced both by the route itself and by CheeseDash's module dispatch; OPERATOR is also
  the ceiling, because the dispatch knows no stricter check and neither handler calls
  `requireAdmin`. See [dashboard](docs/dashboard.md#post-tp).
- Only ever run against EasyNPC. Behaviour with another NPC mod is an expectation, not a
  measurement.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
