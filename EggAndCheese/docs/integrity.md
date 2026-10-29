[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](integrity.fr.md)

# Integrity: duplicates and orphans

In June 2026 a production map ended up with two physical blocks claiming `egg_026` and
one block carrying an id that no registry entry used. Both failures are invisible in
game: an egg looks like an egg. The machinery on this page exists so an admin can find
and fix them without opening a region file.

If your map has drifted — eggs copied with WorldEdit, a schematic pasted twice, a
restored backup, a registry file replaced by hand — this is the page you want.

## The two failure modes

**A duplicate** is one `registryId` claimed by more than one block. The registry entry
points at one position; the other blocks are decoys. A player discovering a decoy gets
the reward and the quest, and then the admin teleporting to the registry position finds
an egg that has already been found. Worse, a dashboard `PUT` that relocates the egg moves
only the canonical block and leaves the decoy behind for good.

**An orphan** is a block with a non-empty `registryId` that matches no registry entry.
Right-clicking it does nothing at all: `handlePlayerDiscovery` returns at its first
check. It renders, it is indestructible to non-admins, and it is worthless.

## `LiveAdminEggIndex`

`src/main/java/com/roicheese/eggandcheese/data/LiveAdminEggIndex.java` — a static map
from `registryId` to a set of `EggLoc(String dim, BlockPos pos)`.

Its invariant, stated in its own Javadoc: it contains exactly the positions of every
**currently loaded** `AdminCollectibleBlockEntity` with a non-empty `registryId`. It is
maintained from three places:

| Event | Action |
|---|---|
| `ServerBlockEntityEvents.BLOCK_ENTITY_LOAD` | `add(id, dim, pos)` |
| `ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD` | `remove(id, dim, pos)` |
| `AdminCollectibleBlockEntity.setRegistryId` (server side) | removes the old id, adds the new one |

The invariant is the whole point. Because the index only holds loaded blocks, any
position it reports is in a loaded chunk, so a caller may do `getBlockEntity()` on it
without ever triggering a chunk load. Every repair path in this subsystem relies on
that, and breaking the invariant would reintroduce the crash described at the end of this
page.

All access is on the server thread — block-entity events, plus the dashboard through
`WebDashboard.executeOnServerThread`. The methods are `synchronized` anyway, as a guard
against a stray off-thread read.

`duplicateIds()` returns every id with more than one loaded position. That is a true
duplicate among loaded chunks, and nothing can be said about unloaded ones.

## Reconciliation on load

`AdminEggPlacedEvent.register()` installs the handlers. On `BLOCK_ENTITY_LOAD`, for an
admin collectible with a non-empty id, it adds the position to the index and queues a
reconcile. It does **not** reconcile inline — see [the deferral rule](#never-force-load-a-chunk-from-a-block-entity-load-handler).

`reconcile(server, id, justLoaded)` enforces one loaded physical block per id:

1. Take a snapshot of the index entry for `id`. Empty means nothing to do.
2. Look the id up in the registry. **No entry means orphan, and reconcile returns**
   without touching anything. Orphans are deliberately left to the dashboard tool,
   because unbinding a block automatically could destroy work an admin is mid-way
   through.
3. Decide which position to keep:
   - if the entry's own position is in the snapshot, keep that one — the registry wins;
   - if the entry is unclaimed (`positionLost`, or its position is `BlockPos.ORIGIN`),
     adopt the block that just loaded, and heal the registry: clear `positionLost`, write
     the adopted position and dimension, mark dirty;
   - otherwise the entry claims a position that is not currently loaded. Keep one block
     deterministically and leave the entry alone.
4. Unbind every other position in the snapshot.

"Deterministically" means `pickDeterministic`: lowest dimension id by string comparison,
then lowest `BlockPos.asLong()`. No randomness, so two servers with the same data
reconcile to the same block, and re-running it is a no-op.

Unbinding is `unbindAt`, and it is the opposite of destructive: it sets the block's
`registryId` to `""`. The block stays, keeps its DNA, keeps rendering, and becomes a
decorative unlinked egg. Nothing a builder placed is deleted. The log line is:

```text
[EggAndCheese] Unbound duplicate egg 'egg_026' at BlockPos{x=812, y=71, z=-304}
```

### Re-placing an egg removes the old block

The third part of the duplicate protection lives in `AdminCollectibleItem.postPlacement`.
When an admin re-places an egg whose id already has a loaded block elsewhere, that block
is unbound and then **removed** before the new one is bound. This mirrors what the
dashboard's `PUT` relocation does and closes the path that created the original
`egg_026` duplicate: break the egg, walk away, place it somewhere else, and find the
first one still standing.

## The dashboard endpoints

### `GET /api/v1/eggs/integrity`

A report, no mutation.

```json
{
  "loadedBlocks": 54,
  "duplicateCount": 1,
  "orphanCount": 2,
  "duplicates": [
    {
      "id": "egg_026",
      "positions": [
        { "dimension": "minecraft:overworld", "x": 812, "y": 71, "z": -304 },
        { "dimension": "minecraft:overworld", "x": 940, "y": 64, "z": -190 }
      ]
    }
  ],
  "orphans": [
    { "dimension": "minecraft:overworld", "x": 101, "y": 70, "z": 55, "id": "egg_099" }
  ],
  "unloadedEntries": ["egg_031", "egg_044"]
}
```

| Field | Meaning |
|---|---|
| `loadedBlocks` | total loaded admin egg blocks with a bound id |
| `duplicates` | one object per id with more than one loaded position |
| `orphans` | one object per loaded block whose id is not in the registry |
| `unloadedEntries` | registry entries, not `positionLost`, whose canonical block is not currently loaded. **Informational only** — the server cannot verify a block in an unloaded chunk without loading it, and it will not do that. |

`unloadedEntries` is the field people misread. An id listed there is not broken. On a
server with few players online, most of your eggs will be listed, because most of your
chunks are unloaded.

### `POST /api/v1/eggs/integrity/reconcile`

The repair. For every id in the index snapshot:

- no registry entry → `unbindAt` every position claiming it, counting each success in
  `orphansCleared`;
- more than one loaded position → `reconcile(server, id, null)`, adding
  `positions - 1` to `duplicatesResolved`.

```json
{ "duplicatesResolved": 1, "orphansCleared": 2 }
```

Both numbers are counts of *unbind* operations, never of deletions. Running it twice is
safe: the second run finds nothing left to unbind.

### The dashboard button

The **Integrity** button in the Eggs view (`CheeseDash/assets/view-eggs.js`, function
`checkIntegrity`) calls the `GET`, and:

- on a clean report, shows a success toast — *"Integrity OK — 54 loaded egg blocks, no
  duplicates or orphans."*
- otherwise lists each duplicate and orphan with its coordinates and asks for
  confirmation — *"1 duplicate ID(s) and 2 orphan block(s) found (loaded chunks only).
  Fix now?"* — then calls the `POST` on yes and reports *"Integrity fixed: 1
  duplicate(s) unbound, 2 orphan(s) cleared."*

Nothing is repaired without a confirmation.

## How to actually use it on a drifted map

The limitation to plan around is that the tool only sees **loaded chunks**. A duplicate
in a chunk nobody has visited since the restart is not in the index and cannot be found.

A workable procedure:

1. Make a backup. The repair unbinds rather than deletes, but a mistaken registry edit
   is not recoverable.
2. Open the dashboard's Eggs view and note the entry count.
3. Load the area you suspect — teleport through it, or use a chunk-loading tool if your
   pack has one. Reconciliation also runs on its own as each block loads, so simply
   walking the area fixes duplicates where the registry's canonical position is among the
   loaded blocks.
4. Press **Integrity**. Read the report before confirming: check that the position the
   report wants to keep is the one you want kept. If it is not, move the registry entry
   first with a `PUT` on the egg's coordinates, then reconcile.
5. Repeat per region. The report is per-snapshot, not cumulative.

For blocks unbound by mistake, re-binding is a placement: give yourself the admin item
for that id (`/eac give <id>` or the dashboard's give-admin button) and place it where
you want it. The old unbound block can be broken as an admin and will not touch the
registry, because it no longer has an id.

## Never force-load a chunk from a block-entity-load handler

This is the rule the subsystem was rebuilt around, and it was learned the expensive way.

A fix shipped on 18 June 2026 reconciled eggs directly inside the `BLOCK_ENTITY_LOAD`
handler, and that reconcile called `getBlockEntity` on a position that might be in an
unloaded chunk. `BLOCK_ENTITY_LOAD` fires **inside** c2me's chunk-upgrade pipeline. A
`getBlockEntity` that has to load a chunk re-enters the chunk system from inside it and
blocks the server thread waiting for work that cannot proceed. The server thread
deadlocked, the watchdog fired, and production crashed twice in one night — 00:38 and
01:11 on 19 June — then went into a crash loop, because the crash happened again as soon
as the same chunk reloaded.

Two defences are in the code now, and both must stay.

**The reconcile is deferred, not immediate.** `BLOCK_ENTITY_LOAD` only enqueues onto a
`ConcurrentLinkedQueue`, and `ServerTickEvents.END_SERVER_TICK` drains it, up to 8192
tasks per tick, each in its own try/catch. A failed task logs a warning and the drain
continues.

```text
[EggAndCheese] deferred reconcile failed: <message>
```

⚠️ The deferral is a **tick-end queue**, not `server.execute(...)`. The comment in
`AdminEggPlacedEvent` is explicit about why, and it is worth repeating because
`server.execute` is the reflex answer: `ThreadExecutor.execute` runs a runnable
**inline** when the caller is already on the server thread, which is exactly the
situation here. `server.execute` would have run the reconcile synchronously inside the
load callback and deadlocked just the same. If you ever "simplify" the queue into a
`server.execute`, you reintroduce the outage.

**Chunk reads are non-blocking.** `unbindAt` asks
`world.getChunkManager().isChunkLoaded(cx, cz)` first — a plain state query with no
`getChunkBlocking` underneath, and therefore safe under c2me. Only if the chunk is
already loaded does it touch `getBlockEntity`, which then returns from cache. If the
duplicate's chunk is not loaded, `unbindAt` returns `false` and gives up; that block gets
reconciled when its own `BLOCK_ENTITY_LOAD` eventually fires.

The general rule, applicable well beyond this mod: **a `BLOCK_ENTITY_LOAD` or
`CHUNK_LOAD` handler must not force a chunk to load, directly or through any API that
might.** Queue the work for a later tick and check `isChunkLoaded` before you touch
anything outside the chunk you were handed.

---

← [FTB Quests integration](ftb-quests.md) · [Dashboard API](dashboard.md) →
