[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dimensions.fr.md)

# Dimensions

CheeseGuard creates Minecraft dimensions at runtime, without a datapack and without a
restart. A `ServerWorld` is built by hand, inserted into the server's world map, and
registered on disk so it comes back on the next boot. On top of that sit the per-dimension
rules, the world-file snapshots that let an arena be reset to a known state, and the
per-player mode that hands every player their own private copy of a world.

The manager is `com.cheeseguard.logic.DimensionManager`, 2 570 lines and the third-largest
file in the mod, after `MiniGameManager` and `EventManager`. The config object is `com.cheeseguard.data.DimensionConfig`.

> ⚠️ This subsystem writes and deletes world files on disk, outside anything Minecraft
> considers its own business. A snapshot restore unloads a live world, swaps its region
> files and reloads it. Back up your save before using it on a server you care about, and
> read [the snapshot section](#snapshots) before enabling an automatic reset trigger.

---

## Two kinds of entry in one list

`DimensionManager` keeps a single list, `customDimensions`, that holds both of these:

**Custom dimensions** — created by CheeseGuard, `builtIn = false`. Their world identifier
is `cheeseguard:<id>` and their files live in
`<level>/dimensions/cheeseguard/<id>/`. They can be deleted, snapshotted and reset.

**Built-in stubs** — `builtIn = true`, auto-registered at startup for every world the
server already has that does not yet have a config: `minecraft:overworld`,
`minecraft:the_nether`, modded dimensions like `cobblemon:safari`. Their `id` **is** the
full world identifier, colon included, and `getWorldId()` returns it unchanged. They exist
so that an admin can set rules and denied flags on vanilla and modded worlds through the
same interface. They cannot be deleted, snapshotted or reset.

`getAllDimensions()` re-scans the server's worlds on every call and registers a stub for
anything new, so a dimension added by another mod appears without a restart.

Two configs can point at the same `worldId` — a stub discovered at boot, and a real config
created later. `getConfigByWorldId()` deliberately prefers the **non-built-in** one,
because that is the config carrying the real settings. Deleting a custom dimension also
purges any leftover stub pointing at the same world, so the pair cannot persist in the
config file.

---

## Creating a dimension

### Generation types

Five built-in types, from `DimensionConfig.GenType`:

| `genType` | Dimension type | Chunk generator | Default spawn Y | Structures |
|---|---|---|---|---|
| `VOID` | overworld | Flat with **no layers**, biome `the_void` | `65` | Never |
| `FLAT` | overworld | Flat from `flatLayers`, biome `plains` | `5` | Per `generateStructures` |
| `NORMAL` | overworld | A copy of the overworld's own generator | `80` | As the overworld |
| `NETHER` | the_nether | A copy of the nether's generator | `64` | As the nether |
| `END` | the_end | A copy of the end's generator | `64` | As the end |

`NORMAL`, `NETHER` and `END` take the live generator object from the matching vanilla
world, so they inherit whatever biome mods have done to it. If the source world is somehow
missing, the generator falls back to void.

`flatLayers` is a comma-separated layer spec, bottom first:

```text
bedrock,3*stone,grass_block
```

`<count>*<block>` repeats a layer. A block id without a namespace is assumed to be
`minecraft:`. An unresolvable block becomes stone rather than failing the creation. The
default spec is `bedrock,3*stone,grass_block`; `VOID` clears it to an empty string.

A sixth path exists: **plugin generation types**. Another mod registers a
`GenTypeProvider` through the Fabric entrypoint `cheeseguard-gentype`, and a dimension
whose `customGenTypeId` names it uses that provider's dimension type, chunk generator and
default spawn Y instead of the table above. `genType` stays set — usually `NORMAL` — because
it still drives the dimension type when the provider does not override it. A
`customGenTypeId` that no longer resolves logs
`Unknown customGenTypeId '<id>' — falling back to VOID` and the dimension generates empty.
A provider that throws during generator creation gets the same fallback, with the
exception logged.

### The id, and the seed

```java
id = id.toLowerCase().replaceAll("[^a-z0-9_]", "_");
```

Hyphens are replaced too, unlike zone ids. The dashboard route additionally collapses
runs of underscores and trims leading and trailing ones. A blank result becomes
`dim_<4 digits>` from the current clock. The world identifier is then
`cheeseguard:<id>`, and creation returns `null` — HTTP 500
`java.lang.RuntimeException: Dimension already exists or creation failed` — if that
identifier is taken.

If you do not give a seed, one is generated at random, deliberately:

```java
if (config.getSeed() == 0L) { do { random = new Random().nextLong(); } while (random == 0L); }
```

`0` means "use the overworld's seed" everywhere else in the code, so without that step two
dimensions you created without naming a seed would share the overworld's terrain. The
loop excludes `0` so the random seed is never mistaken for "unset".

The per-world seed is pushed into `SeedRegistry` **before** the `ServerWorld` is
constructed, because the chunk loading manager built inside that constructor calls
`world.getSeed()` to set up its noise config. A mixin reads the registry and returns the
right value. Get that order wrong and the dimension generates with the overworld's
terrain whatever its config says.

### The lifecycle of one dimension

1. **Create.** `POST /dimensions` with at least `name` and either `genType` or
   `customGenTypeId`. Fields that affect generation — `seed`, `flatLayers`,
   `generateStructures` — are applied by a pre-creation callback, *before* the world is
   built. Everything else (`spawnX/Y/Z`, `pvp`, `mobSpawning`, `keepInventory`,
   `difficulty`, the locks, `clearInventory`, `constructionMode`, `perPlayerMode`,
   `raidDensEnabled`) is applied after.
2. **Runtime registration.** `createWorldRuntime()` builds the `DimensionOptions`,
   initialises TerraBlender for the new world if it is present, derives the level
   properties from the overworld, constructs the `ServerWorld` and puts it in the server's
   world map through a mixin accessor. It then fires Fabric's world-load event by hand,
   because bypassing `MinecraftServer.createWorlds` means nothing else fires it — without
   that call, mods that hook world load miss the dimension entirely.
3. **Persist.** The config is appended to `customDimensions` and
   `config/cheeseguard_dimensions.json` is rewritten.
4. **On every boot.** `init()` walks the saved configs and calls `createWorldRuntime()`
   for each non-built-in one, then applies time locks and world borders, then attaches a
   border listener to every world.
5. **Delete.** `DELETE /dimensions/:id`, refused for built-in dimensions and hard-refused
   for `minecraft:overworld` and `minecraft:the_nether` a second time inside
   `deleteDimension()`. In order: every per-player child is deleted first, Waystones
   registered in the dimension are removed through the Waystones hook, players inside are
   teleported to overworld spawn with
   `Dimension %s has been deleted.`, the world is saved and pulled out of the server's
   map, the world folder is deleted, the snapshot folder is deleted, every in-memory map
   keyed by the config id is cleared, and the config is dropped.

TerraBlender and Waystones are optional-mod bridges. Both are observations of how those
mods behave today, not contracts: a breaking change on their side breaks the biome
routing or the waystone cleanup, and the dimension itself keeps working.

### Per-dimension rules

| Key | Type | Default | Effect |
|---|---|---|---|
| `displayName` | string | the id | Shown in listings and in messages |
| `spawnX` `spawnY` `spawnZ` | double | `0.5`, per gen type, `0.5` | Where `tpToDimension` drops a player |
| `spawnYaw` `spawnPitch` | float | `0` | Facing on arrival |
| `timeLocked` | boolean | `false` | Freeze the time of day |
| `lockedTime` | int | `6000` | The frozen time. `6000` is noon |
| `weatherLocked` | boolean | `false` | Freeze the weather |
| `lockedWeather` | string | `"clear"` | The frozen weather |
| `pvp` | boolean | `true` | Dimension-wide PvP. Checked **after** the zone flag and overrides it: a zone that allows PvP in a dimension with `pvp: false` still refuses |
| `mobSpawning` | boolean | `true` | Natural mob spawns |
| `keepInventory` | boolean | `false` | Inventory survives death in this dimension |
| `difficulty` | string | `"default"` | See the note below |
| `enabled` | boolean | `true` | When false, non-whitelisted players inside are teleported to overworld spawn |
| `enabledMessage` | string | `"Cette dimension est fermee pour le moment."` | Shown, prefixed with ⛔, when that happens |
| `whitelist` | UUID set | empty | Players who may enter a closed dimension |
| `clearInventory` | boolean | `false` | Save and wipe the inventory on entry, restore on exit |
| `constructionMode` | boolean | `false` | Creative for listed builders, plus item restrictions |
| `constructionBuilders` | UUID set | empty | Who gets creative in construction mode |
| `commandBlocks` | boolean | `false` | Using (right-clicking) command and structure blocks. Placing one is not checked |
| `enderChest` | boolean | `true` | Placing or using ender chests |
| `generateStructures` | boolean | `true` | Structure generation. Always off for `VOID` |
| `cobblemonBlocked` | boolean | `false` | Blocks all Cobblemon spawning, and purges what is already there |
| `allowHome` | boolean | `true` | Whether `/home` works here |
| `raidDensEnabled` | boolean | `true` | Natural Cobblemon raid dens |
| `commandsOnBattleLoss` | string list | empty | Commands run when a player loses a Pokémon battle here |
| `commandsOnLeave` | string list | empty | Commands run when a player leaves this dimension |
| `deniedFlags` | string set | empty | Zone flag ids denied dimension-wide |
| `borderEnabled` … `borderWarningTime` | mixed | see below | Per-dimension world border |
| `perPlayerMode` | boolean | `false` | See [per-player dimensions](#per-player-dimensions) |
| `builtIn` | boolean | `false` | Set by the mod, not by you |

> `difficulty` is honest about itself in its own Javadoc, and so is this page: Minecraft's
> difficulty is server-global, so `easy`, `normal` and `hard` are **cosmetic labels with
> no effect**. Only `peaceful` does anything — hostile mob aggro is cleared on a tick
> handler, which simulates peaceful inside that one dimension. `default` means no
> override.

`deniedFlags` is the dimension-level fallback in flag resolution: a flag id listed here is
denied throughout the dimension *unless* a zone or a group has an explicit value for it.
It is the last pass, so any zone flag beats it. The full order is in
[zones and flags](zones-and-flags.md#the-full-resolution-order). Per-player dimensions
inherit their master's denied set, and that inheritance is checked even when the instance
has a config of its own — the instance config is usually an empty stub, and the earlier
"only fall back when there is no config" logic meant master flags were never consulted.

The world border is per dimension, and keeping it that way took work. Vanilla attaches a
`WorldBorderSyncer` from the overworld to every other world, so setting a border on the
overworld rewrites the nether's and the end's to match. `init()` detaches those syncers,
which makes each world's border genuinely independent. A listener on each border captures
any change — from `/worldborder`, from the dashboard, from another mod — and saves it back
into the config, debounced one second, so it survives a restart. A drift watchdog
re-checks every 200 ticks that each managed border still matches its stored config, and a
border packet is re-sent whenever a player changes world, because vanilla only auto-syncs
the overworld's.

### Toggling a rule

`POST /dimensions/:id/toggle` with `{"key": "<name>"}` flips one boolean. Accepted keys:
`pvp`, `mobSpawning`, `keepInventory`, `clearInventory`, `constructionMode`,
`perPlayerMode`, `commandBlocks`, `enderChest`, `generateStructures`, `cobblemonBlocked`,
`enabled`, `timeLocked`, `weatherLocked`, `allowHome`, `raidDensEnabled`, `borderEnabled`,
plus `flag.<flagId>` to toggle a dimension-level denied flag. Anything else answers 500
`java.lang.RuntimeException: Unknown toggle key: <key>`. A `flag.<flagId>` key is not
validated: an unknown flag id is stored in `deniedFlags` and simply never matches.

Five of them do more than set a field. `constructionMode` turned on also turns
`clearInventory` on, because construction mode has no inventory handling of its own.
`cobblemonBlocked` turned on immediately purges Cobblemon entities from the world.
`timeLocked`, `weatherLocked` and `borderEnabled` re-apply their effect at once instead of
waiting for the next periodic pass.

---

## Snapshots

A snapshot is a **file-level copy of the dimension's region folder**, nothing cleverer. It
captures whatever Minecraft has written to disk for that dimension: terrain, block
entities, entities, structures, the lot. It does **not** capture player data — inventories,
positions and advancements live elsewhere and are untouched by a reset.

```text
config/snapshots/<dimId>/          ← the snapshot
<level>/dimensions/cheeseguard/<dimId>/   ← the live world
```

The snapshot path is resolved relative to the parent of the dimension config file, so it
sits next to `cheeseguard_dimensions.json` in `config/`. That is also why built-in
dimensions are excluded: their id contains a colon, which is not a legal path character on
Windows.

### Taking one

`POST /dimensions/:id/snapshot`, refused with 400 for a built-in dimension. The sequence:

1. Save the live world, if it is loaded.
2. Delete the old snapshot folder, if any.
3. Walk the live folder and copy every file.
4. Set `snapshotEnabled = true` and save the config.

A dimension whose chunks have never generated has no folder on disk; the snapshot is then
created **empty** and still marked enabled, logged as `Snapshot created (empty) for dim
'<id>'`. A reset from an empty snapshot gives you an empty world, which is a legitimate
thing to want for a void arena and a surprise if you expected terrain. The success log
otherwise reports the size: `Snapshot created for dim '<id>' (~<n>MB)`.

`GET /dimensions/:id` reports `snapshotSizeKb`, computed by walking the folder, and
`snapshotEnabled`.

### Resetting

`POST /dimensions/:id/reset` returns one of four strings in `{"status": …}`:

| Status | Meaning |
|---|---|
| `ok` | The dimension was empty; the reset started |
| `pending` | Players were inside. They were teleported to overworld spawn and told `This dimension is being reset.`, and the reset is queued |
| `no_snapshot` | Neither this dimension nor, for an instance, its master has a snapshot |
| `error` | No such config, or a built-in dimension |

A queued reset is picked up by `checkAutoResets()`, which runs once a second, as soon as
the dimension is empty.

The reset itself, in `executeReset()`:

1. Save the live world and **remove it from the server's world map** through the mixin
   accessor. The dimension is now unloaded.
2. Asynchronously, off the server thread: wait 500 ms, delete the live folder, recreate
   it, copy every file from the snapshot.
3. Back on the server thread: `createWorldRuntime()` to reload the dimension,
   `lastResetAt` stamped with the current epoch milliseconds, config saved, and every
   online operator told `Dimension '%s' has been reset.`

That 500 ms pause, and the matching 400 ms pause before the folder deletion in
`deletePlayerDimension()` (and before deleting each per-player child in
`deleteDimension()` — the deleted dimension's own folder is removed at once, with no pause),
are there for **Windows file locks**.
Removing a `ServerWorld` from the server map does not immediately release the handles its
chunk storage holds, and on Windows an open handle makes `Files.delete` fail outright
rather than defer the unlink the way Linux does. The delay gives the handles time to
close. It is a heuristic, not a guarantee: on a loaded server the copy can still fail,
and if it does the code logs `Failed to copy snapshot files for '<id>'` and immediately
calls `createWorldRuntime()` again so the dimension comes back — with whatever state the
half-finished copy left on disk. Check the log after any reset that does not announce
itself as done.

There is no reason to run this on Linux, where the delay is pure latency, and no
platform check guards it. Both paths pay it.

### The three triggers

`PUT /dimensions/:id/snapshot/config` with `{"trigger": "...", "hour": 4}`:

| Trigger | When it fires |
|---|---|
| `MANUAL` | Never on its own. Only `POST /reset` resets the dimension |
| `ON_EMPTY` | When the last player leaves |
| `SCHEDULED` | Once a day, at `snapshotResetHour` in the **server's local wall-clock time** |

`hour` is clamped to 0–23 and defaults to `4`. An invalid trigger name answers 400
`Invalid trigger: <name>`. A dimension with `snapshotEnabled = false` is skipped by the
checker whatever its trigger says, so setting `ON_EMPTY` without taking a snapshot first
does nothing at all.

`ON_EMPTY` fires on the **transition**, not on the state. The checker remembers which
dimensions currently hold players in an `onEmptyOccupied` set; the reset queues only when
a dimension that was in that set becomes empty. A dimension that has been empty since
boot, or that was just reset, does not reset again — otherwise an unvisited arena would
reset once a second forever.

`SCHEDULED` compares `LocalTime.now().getHour()` against the configured hour and records
the day it last fired, as `dayOfYear * 10000 + year`, so it cannot fire twice in one day.
That record lives in memory only: a server restarted during the matching hour fires the
reset again.
The one-hour granularity means the reset happens at some point during the matching hour,
whenever the next one-second check lands — not at the top of it. If the server is down for
that whole hour, the reset is simply skipped; it does not catch up on the next boot.

A pending reset suppresses both triggers until it has executed, so a trigger cannot stack
on top of a queued one.

---

## Per-player dimensions

A dimension with `perPlayerMode = true` is a **master**: a template nobody plays in. When
a non-operator player ends up inside it, they are silently moved into their own private
copy, which is created on the spot if it does not exist. Operators are never redirected,
so an admin can stand in the master and edit it.

### The identifier

```text
<masterDimId>_<uuid8>
```

where `uuid8` is the first **eight hexadecimal characters** of the player's UUID with the
dashes stripped — `player.getUuid().toString().replace("-","").substring(0,8)`. So master
`conseil4` plus a player whose UUID begins `abc12345-…` gives config id
`conseil4_abc12345` and world identifier `cheeseguard:conseil4_abc12345`.

Eight hex characters is 32 bits. On a server with a few thousand players a collision is
unlikely but not impossible, and nothing checks for one: two colliding players would share
a world. It is the shape the format is, not a shape this page is defending.

The reverse direction — recognising an instance by its id — is a strict pattern match
everywhere it happens: the last underscore must be followed by exactly eight characters
matching `[0-9a-f]{8}`. `RegionManager`, `DimensionManager` and `DimensionsApi` each
implement that test separately, with the same rule. A master dimension whose own id ends
in an underscore plus eight hex characters would be misread as an instance; do not name
one `arena_deadbeef`.

### Creation and inheritance

`createPlayerDimension()` clones the master's settings — generation type, seed, flat
layers, spawn, time and weather locks, `pvp`, `mobSpawning`, `keepInventory`,
`difficulty`, `commandBlocks`, `enderChest`, `generateStructures`, `cobblemonBlocked`,
`clearInventory`, `allowHome`, `snapshotEnabled`, `snapshotResetTrigger` and
`commandsOnBattleLoss` — and forces four values:

- `constructionMode = false`
- `perPlayerMode = false` (an instance is never itself a master)
- `enabled = true`
- `builtIn = false`

The world files are copied from the **master's snapshot** if one exists, and from the
**live master world** otherwise. If neither exists, nothing is copied and the instance
generates fresh from its generator.

The owner's UUID is added to the instance's whitelist, which is how the instance stays
private. The player is then teleported in at the exact coordinates they reached in the
master, so a portal or a command that lands them at a specific spot keeps working, and
they get `[CheeseGuard] Welcome to your personal dimension!` on the action bar.

If creation fails they get `[CheeseGuard] Error creating your personal dimension. Contact
an admin.` and are sent to overworld spawn.

The redirect check runs once a second, from the same 20-tick block as the reset checker.
A player therefore spends up to a second standing in the master before being moved. It
also notices a config whose world folder or `region/` subfolder has gone missing and
rebuilds the instance from the master, logging `Personal dim '<id>' config exists but
world folder missing — recreating from snapshot`.

### Zones cover every instance at once

A zone drawn in the master world applies inside every instance. `getRegionsAt()` extracts
the master world identifier from an instance identifier and matches zones belonging to
either. Draw your arena's zones once, in the master, and they protect every copy.

### Managing instances

| Route | Effect |
|---|---|
| `GET /dimensions/:id/players` | List the instances of this master |
| `DELETE /dimensions/:id/players/:uuid8` | Delete one instance |
| `POST /dimensions/:id/players/:uuid8/reset` | Rebuild one instance from the master |

and from the console, at permission level 2:

```text
/cg playerworld list <masterDimId>
/cg playerworld delete <masterDimId> <player>
```

Deleting an instance removes its waystones, teleports anyone inside to overworld spawn
with `[CheeseGuard] Your personal dimension has been deleted by an administrator.`,
unloads the world, deletes the folder after the 400 ms pause, deletes the instance's own
snapshot if it has one, and drops the config. Resetting does the same and then rebuilds
from the master's snapshot — or the live master — announcing `[CheeseGuard] Your dimension
has been reset from the master snapshot.`

`GET /dimensions` hides instances: they appear only under their master's `players`
sub-resource, so the dimension list stays readable on a server with a hundred of them.

Resolving a full UUID back from eight hex characters is the awkward part of this design.
The API cannot do it from the id alone, so it reads the instance config's whitelist and
takes the entry whose UUID starts with those eight characters. An instance whose whitelist
was cleared by hand can no longer be deleted or reset through the dashboard.
`/cg playerworld delete` still works for it only while the owner is online: the command
takes the UUID of the online player of that name, and for an offline player falls back to
the same whitelist.

Snapshot lookups for an instance fall back to the master: `getEffectiveSnapshotFolder()`
returns the instance's own folder if it exists, then the master's, then the instance's
missing path for the caller to reject. So `POST /reset` on an instance restores the
master's snapshot, which is the behaviour an instanced arena wants.

---

## Built-in dimensions and the colon

A built-in config's id is the whole world identifier — `minecraft:overworld`,
`cobblemon:safari` — colon included. That one character is why two lookups are skipped for
these dimensions, and the reason is worth stating because it is not obvious from the code.

**The path problem.** Snapshot and world folders are built by resolving the id against a
directory. A colon is not a legal character in a Windows path, and on any platform
`minecraft:overworld` is not a folder the mod owns. A snapshot of a built-in dimension is
therefore meaningless, and `buildDimSummary()` reports `snapshotSizeKb: 0` and
`playerDimCount: 0` for `builtIn` configs instead of walking a path that cannot exist.
`hasPendingReset` is reported as `false` the same way. The create, reset and delete routes
all refuse built-in dimensions outright with 400.

**The route problem.** The dimension API splits its path on the first `/` after
`/dimensions/`, and `isValidId()` rejects `..`, `/`, `\` and `%` but allows `:` — so
`GET /api/v1/cheeseguard/dimensions/minecraft:overworld` does reach the right config and
read and toggle work normally on it. What a colon does break is anything that would treat
the id as a path segment of its own, which is exactly what the skipped lookups were doing.

So a built-in dimension is fully configurable — rules, denied flags, world border, closed
state, leave commands — and is never snapshotted, reset, deleted, or given per-player
instances. That is not a limitation waiting to be lifted; the two features need a folder
the mod owns, and a vanilla dimension is not one.

---

## Persistence

```text
config/cheeseguard_dimensions.json
```

Note where this one is: directly under `config/`, **not** inside `config/cheeseguard/`
where the zones live. Snapshots then sit in `config/snapshots/<dimId>/`, resolved as a
sibling of the config file.

Written whenever a dimension is created, deleted, toggled or edited, whenever a world
border changes (debounced one second), and once at the end of `init()` to persist the
built-in stubs discovered on that boot.

```json
{
  "spawnRedirect": "cheeseguard:lobby",
  "dimensions": [
    {
      "id": "arena_pvp",
      "displayName": "Arène PvP",
      "genType": "FLAT",
      "seed": -4218904411238871040,
      "flatLayers": "bedrock,3*stone,grass_block",
      "spawnX": 0.5, "spawnY": 5.0, "spawnZ": 0.5,
      "timeLocked": true, "lockedTime": 6000,
      "weatherLocked": true, "lockedWeather": "clear",
      "pvp": true, "mobSpawning": false, "keepInventory": true,
      "difficulty": "peaceful",
      "builtIn": false, "enabled": true,
      "enabledMessage": "Cette dimension est fermee pour le moment.",
      "whitelist": [],
      "clearInventory": true,
      "constructionMode": false,
      "commandBlocks": false, "enderChest": false,
      "generateStructures": false,
      "cobblemonBlocked": false, "allowHome": false, "raidDensEnabled": false,
      "snapshotEnabled": true,
      "snapshotResetTrigger": "ON_EMPTY",
      "snapshotResetHour": 4,
      "lastResetAt": 1761700000000,
      "perPlayerMode": false,
      "constructionBuilders": [],
      "borderEnabled": true,
      "borderCenterX": 0.0, "borderCenterZ": 0.0,
      "borderSize": 300.0, "borderDamage": 0.2,
      "borderWarningBlocks": 5, "borderWarningTime": 15,
      "deniedFlags": [ "fall-damage", "cobblemon-summon" ]
    }
  ]
}
```

An array, not a map — the id is a field here, unlike in `regions.json`. `spawnRedirect` is
a top-level string naming a dimension that overworld spawns are redirected to; it is
absent when unset.

Loading is more forgiving than the zone file: each entry is parsed in its own `try`, and a
broken one logs `Skipping invalid dim config: <message>` and is dropped while the rest
load. Only `id` is read without a guard, so an entry missing its id is the one that gets
skipped. Every other field falls back to its default when absent, which makes the file
safe to trim by hand.

Editing it while the server runs still achieves nothing — the in-memory list is
authoritative and the next write overwrites the file.

### What else a dimension writes

`clearInventory` dimensions persist their inventory snapshots separately, so that a crash
while a player is inside does not eat their items:

```text
config/cheeseguard_clearinv_snapshots/<uuid>.nbt
```

Written when a player enters, deleted when they leave with their inventory restored, and
scanned at startup so a snapshot that survived a crash can be restored on the player's
next join. Files from the older `config/cheeseguard_construction_snapshots/` directory are
migrated into the new one on boot and the old directory is removed.

A crash-recovered snapshot is restored **restore-only**: the inventory held at the moment
of recovery is discarded rather than merged. The merge path needs the list of items the
player held at entry, which lives only in memory, and without it a merge would duplicate
every item present in both sets. Logged as `Clear inventory mode exited for <player>
(crash-recovered snapshot) — restore-only, current inventory discarded to prevent
duplication`. Losing what a player picked up during the crashed session is the deliberate
choice over handing them two of everything they walked in with.

---

← [Groups](groups.md) · [Events](events.md) →
