[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](placement-and-discovery.fr.md)

# Placement and discovery

An egg exists twice: as a `CollectibleEntry` in the registry, and as an
`AdminCollectibleBlock` somewhere in a world. The registry is the authority on what the
egg *is*; the block is where a player can touch it. Most of the mod's complexity is
about keeping those two in agreement.

## The objects

| Object | Where it lives | What it owns |
|---|---|---|
| `CollectibleEntry` | `EggRegistryData`, a `PersistentState` on the overworld | id, type, DNA, position, dimension, scale, `positionLost`, title, subtitle, render-distance override, FTB quest id, the set of players who discovered it, the set of players who received its decorative item |
| `AdminCollectibleBlockEntity` | the chunk it sits in | DNA, collectible type, `registryId`, scale, render-distance override |
| `eggandcheese:admin_egg` item | an admin's inventory | DNA, `registryId`, scale, in its `CUSTOM_DATA` component |

The block entity holds a *copy* of the DNA and scale. That is deliberate: a client that
loads the chunk needs them without asking the server for registry data. The registry
stays the source of truth, and the dashboard writes both sides when it changes one — with
two exceptions: a rename leaves the block on the old id, and a scale sent together with
coordinates (which is what the dashboard's Location tab does) reaches the registry only.

`registryId` on a block entity is the link. An empty `registryId` means the block is
decorative and unlinked: it renders, it cannot be discovered, and the integrity tool
ignores it.

## Placing an egg

The admin item is a `BlockItem`, so placement is a normal right-click against a face.
All the work happens in `AdminCollectibleItem.postPlacement`, server side.

It reads three things out of the stack's `CUSTOM_DATA`: `dna`, `registryId`, `scale`. If
there is no DNA in the stack, a fresh random one is generated, so a `/give` of a bare
`eggandcheese:admin_egg` still produces a working egg.

Then one of two branches runs.

### Branch 1 — the stack names an existing registry id

This is a replacement or a move. Before binding the new block, the code checks whether
that id already has a **loaded** physical block elsewhere, through
`LiveAdminEggIndex`. If it does, it unbinds that block (`setRegistryId("")`) and removes
it, so no stale duplicate is left behind. Then it writes the new position and dimension
into the entry, clears `positionLost`, copies the DNA and scale from the item onto both
the entry and the new block entity, and applies the entry's render-distance override.

The admin sees, in chat:

```text
Egg 'egg_014' replaced successfully — position restored.
```

On a French client:

```text
Oeuf 'egg_014' replacé avec succès — position rétablie.
```

### Branch 2 — the stack has no id, or an id the registry does not know

A new entry is created. The id comes from `EggRegistryData.generateUniqueId()`, which
counts up `egg_001`, `egg_002`, … until it finds one that is free. The entry is added,
the block entity is bound to it, and if FTB Quests integration is enabled a quest is
created for it right away.

```text
Egg created with automatic ID 'egg_015'. Editable from the CheeseDash dashboard.
```

Note the consequence: **placing an egg from the dashboard is not required.** An admin
with a plain admin egg can place as many as they like and tidy up the ids and titles
afterwards. The dashboard's `POST /collectibles` route creates an entry at the
coordinates you supply instead, which is the right call when you already know where the
egg belongs.

### The block's own properties

`AdminCollectibleBlock` is created with `noCollision()` and `nonOpaque()`, is
`Waterloggable`, and carries two state properties: `FACING`
(`Properties.HORIZONTAL_FACING`, set to the opposite of the placing player's facing, so
the egg looks at you) and `WATERLOGGED` (true when placed into water).

No collision means a player walks through an egg. That is what lets an egg sit in a
doorway or in tall grass without blocking anything.

## Indestructibility

Two overrides protect a placed egg:

- `canReplace` returns `false`, so no block placement can overwrite it.
- `calcBlockBreakingDelta` returns `0.0f` for anyone who is not an admin. Mining speed
  zero means the block never breaks in survival, however long you hold the button.

A third one, `onBreak`, returns the state unchanged for a non-admin, but that cancels
nothing: Minecraft removes the block after `onBreak` whatever it returns. ⚠️ So the
protection has two holes: a non-admin in **creative mode** can remove the egg, and so can
an **explosion**. In both cases no item is returned and the entry is
not marked `positionLost`: it keeps pointing at an empty spot, and only the integrity
report's `unloadedEntries` reveals it. Keep non-admins out of creative mode, and place hunt
eggs inside a [CheeseGuard](../../CheeseGuard/README.md) zone with the `explosions` flag
denied; the flag is checked where the explosion starts, so let the zone extend a few blocks
past the egg.

An admin who does break it gets the egg back as an item carrying its DNA, scale and
`registryId`, inserted straight into the inventory (or dropped at their feet if the
inventory is full — never dropped as a world item otherwise). The registry entry is then
marked `positionLost = true` and its position reset to `BlockPos.ORIGIN`, and the admin
gets a gold-coloured warning:

```text
The egg 'egg_014' no longer has a position in the world. Replace it or delete it via the CheeseDash dashboard.
```

`positionLost` matters throughout the API: the dashboard refuses to relocate, sync or
delete a world block for an entry in that state, because there is no block to act on.
Re-placing the item clears the flag.

Permission comes from `PermissionUtil.isAdmin`, which tries
`me.lucko.fabric.api.permissions.v0.Permissions.check(entity, "eggandcheese.admin", 2)`
by reflection and falls back to `hasPermissionLevel(2)` when no permissions mod is
installed. There is no separate "can break" node — admin is admin.

## Discovery

A non-admin right-clicks an egg. `AdminCollectibleBlock.onUse` runs on the server and
calls `AdminCollectibleBlockEntity.handlePlayerDiscovery`, which:

1. Returns immediately if `registryId` is empty or names no registry entry. An unlinked
   egg is scenery.
2. If the player is already in `discoveredBy`, shows `You already found this one!` in
   the action bar and stops.
3. Adds the player to `discoveredBy` **and** to `decoReceivedBy`, and marks the registry
   dirty.
4. Builds the reward — a `collectible_egg` carrying the same DNA — and inserts it into
   the player's inventory, dropping it at their feet if full.
5. Fires the advancement criterion `eggandcheese:egg_found` with the egg's id.
6. Plays `ENTITY_EXPERIENCE_ORB_PICKUP` at the block, pitch 1.2.
7. Shows `You discovered: egg_014!` in the action bar.
8. Completes the linked FTB quest, creating it first if the egg predates the
   integration — see [FTB Quests integration](ftb-quests.md).
9. Broadcasts an `egg_discovery` message on the CheeseDash WebSocket `eggs` channel,
   guarded by `FabricLoader.isModLoaded("cheesedash")` and a `NoClassDefFoundError`
   catch.

What the player sees, in order: the pop sound, the action-bar line, a new decorative egg
in their inventory, and a quest ticking off if one was configured.

⚠️ **The action-bar line shows the raw id, not the title.** `Text.translatable(
"eggandcheese.found", registryId)` passes `registryId`, so a player reads
`You discovered: egg_014!` even when the entry has a nice `title` set. The title is used
for the FTB quest, not for this message. Treat your ids as player-visible and name them
accordingly — `cave_nord` reads better than `egg_014`.

### An admin right-click is not a discovery

If `PermissionUtil.isAdmin` is true, `onUse` takes a different branch entirely: the admin
receives a copy of the decorative egg and the method returns. No discovery is recorded,
no quest completes, no WebSocket message is sent. It is a preview tool — right-click
your own egg to check what the reward looks like.

The branch only *reads* the `decoReceivedBy` set that discoveries use; it never writes
it. `giveDecoItemTo` does not record the admin, so — despite the "once per admin"
comment in `onUse` — an admin gets a fresh decorative copy on every right-click. The
message `You have already received the decorative version of this egg.` appears only to an
admin who had found that egg as a normal player before being given admin rights. Taking
copies as an admin therefore never blocks a later discovery on the same account.

## Per-player state

Two independent sets live on each entry:

| Set | Written by | Meaning |
|---|---|---|
| `discoveredBy` | player discovery, `POST /collectibles/:id/discoveries` | this player found this egg |
| `decoReceivedBy` | player discovery only | this player has had the decorative item from a discovery |

Both persist in the entry's NBT as lists of UUID strings, and both are read back
defensively — an unparseable UUID is skipped rather than failing the load.

The dashboard can add a discovery by player name (`POST`), remove one by UUID
(`DELETE /collectibles/:id/discoveries/:uuid`) or clear them all
(`DELETE /collectibles/:id/discoveries`). None of those touch `decoReceivedBy`, and
`onUse` checks `decoReceivedBy` **before** it ever calls `handlePlayerDiscovery`. So a
player whose discovery you cleared, after a normal discovery, still gets
`You already found this one!` and nothing else: no new discovery, no quest, no item.
Clearing discoveries changes the counts and the list, not what the player can do.

## Persistence

`EggRegistryData` extends `PersistentState` and is fetched with
`server.getWorld(World.OVERWORLD).getPersistentStateManager().getOrCreate(TYPE,
"eggandcheese_registry")`. It is stored in the overworld's data folder, as
`world/data/eggandcheese_registry.dat`, and Minecraft writes it on its own save cycle —
every autosave and on shutdown. Nothing in this mod forces a save.

Every mutating path calls `markDirty()`, which is what schedules the write. A server
killed with `kill -9` between autosaves loses the discoveries made since the last one.

Because the state is attached to the **overworld**, eggs placed in other dimensions are
still recorded in the overworld's data file. Deleting that file deletes every egg entry
in every dimension while leaving the blocks standing — which produces exactly the orphan
situation [the integrity tool](integrity.md) is built to clean up.

---

← [Texture generation and previews](rendering.md) · [Decorative eggs and pedestals](display.md) →
