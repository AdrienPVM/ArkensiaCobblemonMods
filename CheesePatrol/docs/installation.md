[← CheesePatrol](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

CheesePatrol is **server-side only**. `fabric.mod.json` declares
`"environment": "server"`, and there is no client entrypoint, no client mixin and no
custom payload. Players connecting to a server running it need nothing installed; they
see armor-stand markers and particles like any other server-sent effect.

---

## Prerequisites

| | Version | Why |
|---|---|---|
| Minecraft | `~1.21.1` | declared in `depends` |
| Fabric Loader | `≥ 0.16.0` | declared in `depends` |
| Fabric API | any | events, commands, networking, items |
| Java | `≥ 21` | `options.release = 21` in `build.gradle` |
| [CheeseDash](../../CheeseDash/) | any | **hard dependency** — `"cheesedash": "*"` in `depends` |
| [EasyNPC](https://modrinth.com/mod/easy-npc) | 6.2.0 tested | **suggested**, not required by the loader |

CheeseDash is a `depends` entry, not a `suggests`: the loader refuses to start without
it. That is stricter than the code needs — the patrol logic itself never calls into
CheeseDash — but it is what the manifest says, so install CheeseDash.

EasyNPC is the reverse. It sits in `suggests`, the loader starts happily without it, and
the mod will even let you attach a patrol to a vanilla zombie. What EasyNPC adds is the
`getEntityGoalSelector()` method CheesePatrol reflects on to wipe idle goals; without a
goal selector to clear, an NPC from another mod may refuse to move. See
[the EasyNPC dependency](architecture.md#the-easynpc-dependency-is-one-reflective-call).

## Build order

CheesePatrol compiles against CheeseDash's built jar, resolved by a glob over
`../CheeseDash/build/libs/cheesedash-*.jar`. CheeseDash must therefore be **built
first** — not merely present as a source directory.

```bash
cd CheeseDash      && ./gradlew build
cd ../CheesePatrol && ./gradlew build
```

`tools/build-all.sh` at the repository root walks every mod in dependency order and is
the easier route if you are building the whole suite.

The EasyNPC jar is pulled from the Modrinth Maven as `maven.modrinth:easy-npc:PG3ZdKaC`,
so a clean clone needs no manual download. It is a `compileOnly` dependency and nothing
from it is bundled.

The result is `CheesePatrol/build/libs/cheesepatrol-1.0.0+1.21.1.jar`. The version comes
from `mod_version` in `gradle.properties`; `-sources.jar` is also produced and must not
be deployed.

## Installing on a server

Copy into the server's `mods/`:

- `cheesepatrol-1.0.0+1.21.1.jar`
- the CheeseDash jar
- the EasyNPC jar

Nothing else. There is no config file to pre-create.

## What the first launch creates

Nothing. This is worth stating because every sibling mod writes a config file on first
start and CheesePatrol does not. `PatrolManager.load()` reads
`config/cheesepatrol_data.json` **if it exists** and is otherwise silent; the file is
only written by `PatrolManager.save()`, which runs the first time you right-click an NPC
with the wand, add a waypoint, change a setting, start or stop a patrol — and,
unconditionally, at every `SERVER_STOPPING`. So the first clean shutdown writes the file
even if you never touched a patrol: `{"debug": false, "patrols": []}`.

Once you have created a patrol, two things exist:

```text
config/
├── cheesepatrol_data.json          every patrol: waypoints, speed, radius, state
└── cheesepatrol_entities/
    └── <npc-uuid>.dat              compressed NBT snapshot of the NPC entity
```

The `.dat` files appear on the next periodic backup — the pass runs every 60 seconds,
counted from server start, and covers every patrol whose NPC is loaded at that moment —
and at shutdown. Both are documented in
[configuration](configuration.md) and [keeping NPCs alive](resilience.md).

A third, invisible side effect: force-loaded chunks. CheesePatrol force-loads the chunk
each NPC stands in and the chunk of its target waypoint, and **deliberately does not
release them at shutdown**, so the entity is still loaded on the next start. They are
real vanilla forced chunks stored in the world data and `/forceload query` will list
them. Full reasoning in [keeping NPCs alive](resilience.md#chunk-force-loading).

## Verifying it loaded

Four log lines, in this order:

```text
[CheesePatrol] Initializing...
[CheesePatrol] Ready.
[CheesePatrol] Early-loaded 0 patrol(s) for mixin protection
[CheesePatrol] Forceloaded chunks for 0 patrol(s) (0 active, 0 inactive)
```

The first two come from `onInitialize`, during mod loading. The third comes from the
`SERVER_STARTING` event, which is why it appears after `Ready.` even though it happens
later. The count is the number of patrols read from `cheesepatrol_data.json`. The fourth comes from `SERVER_STARTED` and is printed on
every start, even with zero patrols.

Once you have patrols, the `SERVER_STARTED` output can grow to two lines:

```text
[CheesePatrol] Pre-loaded 7 chunks for 3 patrol(s)
[CheesePatrol] Forceloaded chunks for 3 patrol(s) (2 active, 1 inactive)
```

`Pre-loaded` only appears when at least one chunk actually had to be forced; if every
chunk was already forced from the previous session, the line is skipped and only
`Forceloaded chunks for …` shows.

In game, check two things:

1. `/npcpatrol list` answers. If the command does not tab-complete, you are below
   permission level 2 or the mod did not load.
2. `/npcpatrol wand` puts a gold, glinting **Patrol Wand** in your inventory. It is a
   Blaze Rod carrying the `cheesepatrol_wand` custom-data tag; a plain Blaze Rod will not
   work.

For the dashboard side, open CheeseDash and look for **Patrols** in the sidebar under the
CheesePatrol section. It is registered through the `cheesedash` entrypoint declared in
`fabric.mod.json`; if it is missing, see
[troubleshooting](troubleshooting.md#the-patrols-view-is-missing-from-the-dashboard-sidebar).

## Uninstalling cleanly

Order matters here more than for most mods, because the removal-blocking mixin and the
forced chunks both outlive a naive jar deletion.

1. **Stop and clear every patrol first**, while the mod is still installed:

   ```mcfunction
   /npcpatrol list
   ```

   Then, for each one, select its NPC (hold the wand, right-click it or
   `/npcpatrol select <uuid>`) and run:

   ```mcfunction
   /npcpatrol clear
   ```

   `clear` releases that patrol's forced chunks, deletes its armor-stand markers in your
   current world, removes it from the data file and deletes its NBT backup. It also lifts
   the removal protection, so from that point the NPC can be killed or deleted normally.

2. **Deal with the NPCs.** They are plain EasyNPC entities with `setPersistent()` applied.
   Leave them standing or `/kill` them — the choice is yours, but do it before removing
   the jar, since afterwards they are only NPCs with no patrol.

3. **Remove leftover markers** if any survive. They are invisible armor stands tagged
   `cheesepatrol`:

   ```mcfunction
   /kill @e[type=armor_stand,tag=cheesepatrol]
   ```

   A clean shutdown removes every one that is in a loaded chunk, in every world, so this
   is only needed after a crash, or for markers left in chunks that were not loaded when
   the server stopped.

4. **Stop the server, delete the jar**, and delete what you no longer want:

   ```text
   config/cheesepatrol_data.json
   config/cheesepatrol_entities/
   ```

   Leaving them is harmless — nothing else reads them — but they will be picked up again
   if you ever reinstall.

5. **Check for orphaned forced chunks.** If you skipped step 1, chunks force-loaded for
   patrol NPCs stay forced forever, because the mod is no longer there to release them:

   ```mcfunction
   /forceload query
   /forceload remove <x> <z>
   ```

What is left behind after all that: the NPC entities themselves (if you kept them), and
nothing else.

---

← [CheesePatrol](../README.md) · [Getting started](getting-started.md) →
