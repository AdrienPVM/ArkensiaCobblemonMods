[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

49 source files, roughly 7 100 lines of Java. The mod uses
`loom.splitEnvironmentSourceSets()`, so the division between server and client code is
enforced by the build rather than by convention: anything in `src/client/java` cannot be
referenced from `src/main/java` at all.

## Package layout

```text
src/main/java/com/roicheese/eggandcheese/
├── EggAndCheese.java          ModInitializer — registries, criterion, packets, events, commands
├── advancement/               the egg_found criterion
├── block/                     the four block classes + the load/unload reconciliation handler
├── blockentity/               state for admin eggs, deco eggs and pedestals
├── dashboard/                 CheeseDash module + the /api/v1/eggs/* dispatcher
├── data/                      persistent registry, entries, and the live loaded-block index
├── dna/                       the DNA record and its seven enums
├── ftb/                       the FTB Quests reflection hook
├── item/                      admin item, deco item, pedestal item
├── network/                   payload and packet plumbing
├── registry/                  block, item and block-entity registration
└── util/                      commands, permission check, NBT helpers

src/client/java/com/roicheese/eggandcheese/
├── EggAndCheeseClient.java    ClientModInitializer — renderers + the sync receiver
├── JourneyMapIntegration.java optional waypoint hook (see Dead code)
├── renderer/                  three BESRs, the item renderer, the render config
└── texture/                   the procedural generator, its cache, and a seeded RNG
```

| Package | What it owns |
|---|---|
| `advancement` | `EggFoundCriterion` — the `eggandcheese:egg_found` trigger and its `egg_id` condition codec |
| `block` | `AdminCollectibleBlock` (indestructible, the hunt target), `CollectibleDecoBlock` (the reward, breakable), `PedestalBlock` + `PedestalExtensionBlock` (furniture), `PedestalVariant`, and `AdminEggPlacedEvent` which is not a block at all but the reconciliation event handler |
| `blockentity` | `AdminCollectibleBlockEntity` (DNA, registryId, scale, the discovery logic), `CollectibleDecoBlockEntity` (DNA only), `PedestalBlockEntity` (displayed stack, scale, height, pillar, spin, collision shapes) |
| `dashboard` | `EggAndCheeseDashboardModule` (prefix, nav metadata, server reference) and `EggsApi` (924 lines, every route) |
| `data` | `EggRegistryData` (the `PersistentState`), `CollectibleEntry` (one egg), `LiveAdminEggIndex` (loaded blocks by id) |
| `dna` | `CollectibleDNA` plus `PaletteType`, `PatternType`, `EmissiveType`, `StampType`, `MicroTextureType`, `RarityType`, `CollectibleType` |
| `ftb` | `FtbQuestsHook` — 733 lines of reflection, the third-largest file after `ProceduralTextureGenerator` and `EggsApi` |
| `item` | `AdminCollectibleItem` (placement and the replace/create branch), `CollectibleItem` (tooltip, placement, the display-case fix), `PedestalItem` |
| `network` | `ModPayloads` (one S2C payload), `ModPackets` (serialisation and the sender), `PacketExtras` (a no-op stub) |
| `registry` | `ModBlocks` (2 collectible blocks + 23 pedestals + 1 extension), `ModItems` (2 collectible items + 23 pedestal items), `ModBlockEntities` (3 types) |
| `util` | `AdminCommand`, `PedestalCommand`, `PermissionUtil`, `NbtUtils` |
| `renderer` (client) | `AdminCollectibleBESR`, `CollectibleDecoBESR`, `PedestalBESR`, `EggItemRenderer`, `CollectibleModelRenderer` (the 472-voxel mesh), `EggRenderConfig` |
| `texture` (client) | `ProceduralTextureGenerator` (1 245 lines), `CollectibleTextureCache`, `SeededRandom` |

## Entry points

`fabric.mod.json` declares three:

```json
"entrypoints": {
  "main":      ["com.roicheese.eggandcheese.EggAndCheese"],
  "client":    ["com.roicheese.eggandcheese.EggAndCheeseClient"],
  "cheesedash":["com.roicheese.eggandcheese.dashboard.EggAndCheeseDashboardModule"]
}
```

`"mixins": []` — the mod has no mixins at all, and no `eggandcheese.mixins.json`. Nothing
is injected into Minecraft or into another mod. That is unusual for this collection and it
is a strength: there is no version-specific bytecode to break on a Minecraft update.

`EggAndCheese.onInitialize` runs, in order: `ModBlocks.init()`, `ModItems.init()`,
`ModBlockEntities.init()`, the criterion registration, `ModPayloads.registerAll()`,
`ModPackets.registerServerHandlers()`, `PacketExtras.register()`,
`AdminEggPlacedEvent.register()`, then the two command registrations. The payload
registration must precede the handler registration, and the source says so in a comment.

```text
[EggAndCheese] Initializing...
[EggAndCheese] Ready!
```

## Where state lives

| State | Storage | Written when |
|---|---|---|
| Collectible entries, the four settings | `EggRegistryData`, a `PersistentState` keyed `eggandcheese_registry` on the **overworld** | Minecraft's save cycle, after any `markDirty()` |
| A block's DNA, id, scale, render override | `AdminCollectibleBlockEntity` NBT in its chunk | chunk save, after `markDirty()` |
| A deco block's DNA | `CollectibleDecoBlockEntity` NBT | chunk save |
| A pedestal's displayed item and display settings | `PedestalBlockEntity` NBT | chunk save |
| An item's DNA, id, scale | `DataComponentTypes.CUSTOM_DATA` on the stack | with the inventory |
| Loaded admin blocks by id | `LiveAdminEggIndex`, static, memory only | never persisted — rebuilt from load events |
| Client render distance | `EggRenderConfig`, static | never persisted — overwritten by each sync packet |

Nothing in this mod ever writes a file itself. There is no `getConfigDir()` call, no JSON
on disk, no manual save. Every write goes through Minecraft's own persistence, which means
a crash between autosaves loses recent discoveries, and a world rollback rolls back the
egg registry along with the terrain — usually what you want, since the blocks roll back
too.

The registry living on the overworld rather than per-dimension is deliberate: an egg's
entry has to be findable from the dashboard without knowing which world it is in.

## Threading

Three threads matter.

**The server thread.** Block interactions, block-entity load and unload, the tick-end
reconcile drain, commands, and every registry mutation. All of `LiveAdminEggIndex`'s
callers are here.

**A CheeseDash HTTP thread.** `EggsApi.handle` runs on it. Every handler that touches game
state wraps its work in `WebDashboard.executeOnServerThread(server, () -> { ... })`,
collects the result into a one-element array, and responds from the HTTP thread once the
server-thread work has run. `GET /enums` is the only handler that skips the hop, because
it reads nothing but Java enums.

The one-element-array idiom is how a `Runnable` returns a value. It looks odd and it is
correct: the array is written on the server thread and read on the HTTP thread after
`executeOnServerThread` has returned, which is the synchronisation point.

**The client render thread.** `ProceduralTextureGenerator` and
`CollectibleTextureCache` live here. Texture generation is synchronous on first sight of
a DNA, which is the source of the brief hitch when a player enters a field of many
distinct eggs.

### The deferral that keeps the server alive

`AdminEggPlacedEvent` never does work inside its own event handler. `BLOCK_ENTITY_LOAD`
enqueues onto a `ConcurrentLinkedQueue`; `ServerTickEvents.END_SERVER_TICK` drains it, up
to 8192 tasks per tick.

**Never force-load a chunk from a `BLOCK_ENTITY_LOAD` or `CHUNK_LOAD` handler.** This is
the hardest-won rule in the mod. A June 2026 fix called `getBlockEntity` on a possibly
unloaded position from inside the load handler. `BLOCK_ENTITY_LOAD` fires inside c2me's
chunk-upgrade pipeline, so that call re-entered the chunk system from within itself and
blocked the server thread on work that could not proceed. The watchdog fired, production
crashed twice in one night, and then crash-looped because the same chunk reloaded on every
start.

Two things in the code keep that from recurring, and a contributor must preserve both:

1. **The tick-end queue**, not `server.execute`. `ThreadExecutor.execute` runs a runnable
   inline when the caller is already on the server thread — which, inside a load handler,
   it always is. `server.execute` would reintroduce the deadlock exactly. The source
   carries a comment saying so; do not "simplify" it away.
2. **`isChunkLoaded` before any cross-chunk read.** `unbindAt` asks
   `world.getChunkManager().isChunkLoaded(cx, cz)` — a non-blocking state query with no
   `getChunkBlocking` beneath it — and gives up when the answer is no. The block is
   reconciled later, when its own load event fires.

The full account is in [integrity](integrity.md#never-force-load-a-chunk-from-a-block-entity-load-handler).

## Networking

One payload, server to client:

```java
// ModPayloads
record SyncRegistryPayload(byte[] data) implements CustomPayload
// id: eggandcheese:sync_registry
```

It carries a hand-rolled buffer: the entry count, then each entry (id, type, DNA as NBT,
position, dimension, scale, discovery count, positionLost, title, subtitle), then
`ftbQuestsEnabled`, `ftbQuestsChapter`, and `textureRenderDistance`.

The client reads only the last field. `EggAndCheeseClient.registerClientPackets` discards
the entry list and assigns `data[3]` to `EggRenderConfig.textureRenderDistance`. The whole
registry is serialised and sent so that one integer can be delivered.

`ModPackets.registerServerHandlers()` registers nothing — there are no client-to-server
packets. Every admin action arrives over HTTP instead.

## Extension points

If you are adding to this mod, these are the seams.

**A new DNA component.** Add the field to the `CollectibleDNA` record, then update, in
this order: `toNbt`/`fromNbt`, `toDnaCode`/`fromDnaCode`, `cacheKey()`, `randomDNA`,
`EggsApi.applyEdits`, `EggsApi.buildDetail`, and both texture generators. Forgetting
`cacheKey()` makes two different eggs render identically; forgetting `applyEdits` makes
the field unwritable through the API. The record's field list is effectively duplicated in
seven places, which is the main cost of this design.

**A new pattern, emissive type, stamp, micro-texture or palette.** Add the enum constant,
then implement its branch in `ProceduralTextureGenerator` **and** in
`CheeseDash/assets/egg-preview.js`. `GET /enums` picks up the new value with no further
work, so the dashboard dropdown fills itself. Use the seamless noise and wrapped drawing
helpers or your pattern will show a vertical seam.

**A new API route.** Add a branch in `EggsApi.handle` or
`EggsApi.handleCollectibleRoutes`. Follow the existing shape: validate first, wrap state
access in `executeOnServerThread`, respond with Gson. Keep mutations on `POST`, `PUT` or
`DELETE`: CheeseDash's dispatcher is what enforces `OPERATOR` on this module, and it keys
purely on the HTTP method, so a mutating `GET` would arrive unguarded. Starting a mutating
handler with `if (!WebDashboard.requireWrite(exchange)) return;` adds a second line of
defence — none of them do today; see below.

**A new pedestal variant.** One entry in `ModBlocks`, one in `ModItems`, one in the
`ModBlockEntities.PEDESTAL` builder's block list, a `PedestalVariant` constant, blockstate
and model JSON, a recipe, and two lang keys. Nothing else.

**Watching discoveries from another mod.** Listen for the advancement criterion
`eggandcheese:egg_found` rather than touching this mod's classes. It is registered in
`Registries.CRITERION` and carries the egg id in its conditions.

## Known weaknesses

Stated plainly, because whoever maintains this next will hit them.

**The API's role boundary lives entirely in the core.** `EggsApi` contains no call to
`requireWrite` or `requireAdmin`. Writes are still `OPERATOR`-only, because CheeseDash's
module dispatcher rejects any non-`GET` request that fails `requireWrite` before the module
is called, and `GET` stays open to every role including `READ_ONLY`. The boundary holds;
what is weak is where it sits. No route in this mod can require `SUPER_ADMIN`, since
`OPERATOR` is the only bar the dispatcher knows; a mutating `GET` would be unguarded, and
nothing here would flag it; and one change to that dispatcher would drop every check at
once, with no second line of defence in `EggsApi`. The form that would provide one is
`if (!WebDashboard.requireWrite(exchange)) return;` — the method returns a boolean and
enforces nothing if its result is ignored.

**Rename orphans the world block.** `POST /collectibles/:id/rename` changes the registry
key and leaves the block entity carrying the old `registryId`. The result is one orphan
block and one entry with no block, every time. Rename should also rewrite the block
entity's id, or at minimum refuse when the block is loaded.

**`CollectibleType.CHEESE` is half-implemented.** The enum has it, the API accepts it,
`GET /stats` counts it, blockstate and model JSON exist for `admin_cheese` and
`cheese_deco` — and no cheese block or item is ever registered. `CollectibleItem.getBlock`
ignores its `type` argument and always returns `ModBlocks.EGG_DECO`. Creating a collectible
with `"type":"cheese"` gives you an egg wearing a cheese label. Either finish it or remove
it.

**Three disagreeing defaults for one setting.** `textureRenderDistance` is `20` on the
server, `20` on the client with a Javadoc claiming `10`, and `10` in the packet-read
fallback.

**Render distance never reaches a joining client.** `sendRegistrySync` is called from
exactly one place: `PUT /config`. A client that joins and never sees a config change keeps
its compiled-in default. The fix is a send on `ServerPlayConnectionEvents.JOIN`.

**The sync packet sends the whole registry to deliver one integer.** Every entry's DNA is
serialised into a byte array the client immediately discards. With a few hundred eggs this
is a measurable packet for no benefit.

**`ftbAutoSync` is a dead setting.** Persisted, exposed by the API, shown in the
dashboard, read by nothing.

**`/eac reload` reloads nothing.** It prints the entry count. The name is a leftover.

**`/pedestal` has no permission requirement.** Any player at permission level 0 can
retune any pedestal on the server, including in protected builds.

**The FTB hook is pure reflection.** 733 lines against `dev.ftb.mods.ftbquests.*`
internals. It degrades gracefully and logs what it resolved, but it **will** break when
FTB Quests restructures, and the failure mode is silence. Details and the fallback path
are in [FTB Quests integration](ftb-quests.md).

**Two texture pipelines kept in step by hand.** The Java generator and
`egg-preview.js` implement the same 13 steps twice, in two languages, in two
repositories' worth of files. They drift whenever someone edits one and not the other, and
nothing detects it.

**`PedestalExtensionBlock` can be orphaned.** Removing a pedestal with a tool that skips
block updates — WorldEdit, a chunk rollback — leaves unbreakable extension blocks behind,
removable only by `/setblock`.

**Admin deco copies are unlimited.** `giveDecoItemTo` never records the admin in
`decoReceivedBy`, despite the "once per admin" comment in `onUse`, so every admin
right-click hands out another decorative egg. And because `onUse` checks
`decoReceivedBy` before anything else, clearing a player's discovery from the dashboard
never lets them find the egg again. See
[placement and discovery](placement-and-discovery.md#an-admin-right-click-is-not-a-discovery).

**The hunt egg is not as indestructible as it looks.** `onBreak` returning the old state
cancels nothing; only the zero breaking delta stops survival players. A non-admin in
creative mode, or an explosion, removes the block without marking the entry
`positionLost`; protect the area, for instance with CheeseGuard's `explosions` flag. See
[placement and discovery](placement-and-discovery.md#indestructibility).

**The shipped advancement is never loaded.** `egg_found.json` sits in
`data/eggandcheese/advancements/`, the pre-1.21 plural folder; 1.21.1 reads
`advancement/`. The criterion trigger works, the advancement does not exist. See
[the advancement](display.md#the-advancement).

**A `PUT` with coordinates is blunt.** It clears the old position with `removeBlock`
whatever stands there and overwrites the target, and a body that repeats the current
coordinates skips the in-place sync — which is why the dashboard's scale field never
resizes a placed egg. See [the dashboard API](dashboard.md#put-collectiblesid).

**Any `PUT` on an untitled egg renames its quest.** `updateQuest` writes the fixed text
`Trouver un oeuf` when the title is blank, instead of the `Trouver : <id>` used at
creation. See [FTB Quests integration](ftb-quests.md#updating-and-deleting).

**Discovery messages show the raw id.** `title` exists and is used for the FTB quest, but
the player-facing action-bar line passes `registryId`.

**Enum display names are French in the Java source.** `getDisplayName()` returns
`"Cristaux"`, `"Glace"`, `"Épique"` and so on, which is what an English client reads in an
item tooltip and what `GET /enums` returns as `displayName`. There is no translation layer
for them.

**Quest layout is one long row.** `x = index * 2.5`, `y = 0`. Fifty eggs make a 125-unit
row in the quest book.

## Dead code

**`JourneyMapIntegration`** is referenced by nothing. Its `addWaypoint` only logs
`[EggAndCheese] Would add JourneyMap waypoint for: <id>`, and `addClientWaypoint` builds a
waypoint by reflection but is never called. It also declares
`package com.roicheese.eggandcheese.integration` while sitting in
`src/client/java/com/roicheese/eggandcheese/` — the directory and the package do not
match. `javac` tolerates this, so it compiles, but no tool will find the file where the
package says it should be. Either wire it up, move it, or delete it.

**`PacketExtras.register()`** is an intentional no-op, kept so the call in
`EggAndCheese.onInitialize` still compiles after the in-game admin GUI was removed. Its
comment also names `AdminCommand`, which no longer refers to it.

**`NbtUtils.getOrCreate`** is written but unused in the current source. `setSubNbt` is
likewise unreferenced.

**Never called either:** `CollectibleDNA.fromDnaCode`, `hasCustomPalette` and
`buildCustomPalette`; `RarityType.getPatternLayers`, `hasBorder` and `isAnimated` (rarity
has no visual effect at all); the frame-1 emissive texture, generated for every DNA and
never drawn; `PedestalBlockEntity.canAccept`; `CollectibleModelRenderer.renderGui` and
`emitQGui`; `LiveAdminEggIndex.duplicateIds`.

**The legacy flag aliases** `FLAG_OUTLINE` and `FLAG_EDGE_GLOW` are kept as aliases for
`FLAG_EMBOSS` and `FLAG_RIM_GLOW`.

**`egg-preview.js`'s section header** says `PATTERNS (18 types)` above 26 branches. A stale
comment, harmless, but it tells you how the file grew.

## Package rename

The Java package root is `com.roicheese.eggandcheese`. An addon has to be compiled
against that root to load alongside this jar.

Nothing in the resources changed. The asset and data namespace is `eggandcheese`, derived
from the mod id and not from the package, so `eggandcheese:egg_found`,
`eggandcheese:admin_egg` and every recipe id are unchanged across the rename. A datapack
written for the old version still works.

No trace of the old package name remains in `src/`, `build.gradle` or `fabric.mod.json` —
`maven_group` is `com.roicheese`. If you find one in a branch, report it rather than
fixing it in place, because the fix has to be consistent across the whole collection.

---

← [Dashboard API](dashboard.md) · [Troubleshooting](troubleshooting.md) →
