[← CheeseItems](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

28 source files, about 4 980 lines, one mixin, two dashboard modules, four network
payloads. The mod has two halves that share only the jar and the logger: custom items, and
the morpho catalog.

## Package layout

```text
com.cheeseitems
├── CheeseItemsMod.java          entry point: wiring, lifecycle, resource pack push
├── CheeseItemsConfig.java       persistence for items, CustomModelData, asset files
├── CustomItemDefinition.java    the item model + its four inner classes
├── ItemManager.java             CRUD, ItemStack building, inventory refresh
├── UseHandler.java              right-click, conditions, cooldowns, effects, actions, per-item endurance
├── CheeseItemsCommand.java      /cheeseitem
├── MorphoCommand.java           /morpho
├── ResourcePackGenerator.java   builds the pack ZIP in memory
├── client/                      client-only: codex screen, image cache, keybinds
├── dashboard/                   two DashboardModule implementations + their APIs
├── integration/                 CheeseStoreHook — reflection into CheeseStore
├── mixin/                       TotemMixin
├── morpho/                      catalog, codex, manager, endurance tracker, thumbnails
└── network/                     four CustomPayload records
```

### What each class owns

| Class | Owns |
|---|---|
| `CheeseItemsMod` | the singleton `INSTANCE`, the object graph, every event registration, the resource-pack send |
| `CheeseItemsConfig` | the in-memory item list, the `customModelData` map, `resourcePackHost`, and all asset-file I/O |
| `CustomItemDefinition` | one item's fields plus `PotionEffectEntry`, `CommandAction`, `UseConditions`, `EnduranceConfig` |
| `ItemManager` | the only code that builds or repairs an `ItemStack`, and the static NBT helpers |
| `UseHandler` | all in-play item behaviour, the cooldown maps, the delayed-task list, the per-item endurance pools, the soulbound stash |
| `MorphoCatalog` | the catalog, a lazy singleton, its own debounced save, and the global endurance defaults |
| `MorphoCodex` | per-player ownership and the active id, with its own debounced save |
| `MorphoManager` | the bridge to `cshift`, the transformed-player map, the sync payload |
| `MorphoEnduranceTracker` | an eager singleton holding one pool per player, ticked every tick |
| `MorphoThumbnail` | on-disk thumbnail cache for catalog images |
| `CheeseItemsClient` | keybinds, the client-side codex snapshot, the two receivers |

Two singletons are reachable without going through `CheeseItemsMod`:
`MorphoCatalog.getInstance()` (lazy, loads on first touch, which is why
`onInitialize` touches it deliberately) and `MorphoEnduranceTracker.getInstance()` (eager
static field). Everything else hangs off `CheeseItemsMod.INSTANCE`.

## State and when it hits disk

| State | Where | Written |
|---|---|---|
| Item definitions | `config/cheeseitems.json` | synchronously on every create, update and delete, and after a `customModelData` assignment |
| `customModelData`, `resourcePackHost` | same file | with the definitions |
| Item icons | `config/cheeseitems_assets/<id>.png` | immediately on upload |
| Morpho catalog | `config/cheeseitems_morphos.json` | debounced 5 s, flushed at `SERVER_STOPPING` |
| Player codex | `config/cheeseitems_morpho.json` | debounced 5 s, flushed at `SERVER_STOPPING` |
| Catalog images | `config/cheeseitems_assets/morphos/<id>.png` | immediately on upload |
| Thumbnails | next to the originals | on first request |
| Resource pack ZIP | nowhere — memory only | rebuilt on demand |
| Cooldowns, delayed tasks, soulbound stash, both endurance pools, the transformed map | memory | never |

The debounce is the weak point. `markDirty()` saves only if more than five seconds have
passed since the last save; otherwise it sets a flag and relies on a **later mutation** to
trigger the write. There is no timer. A crash between the last mutation and
`SERVER_STOPPING` loses whatever happened in that window.

Item definitions do not have that problem — every mutation calls `config.save()`
directly, which rewrites the whole file.

## Threading

| Runs on | What |
|---|---|
| Server thread | `UseItemCallback`, `END_SERVER_TICK` (both the `UseHandler` tick and the morpho endurance tick), command execution, all inventory mutation, `ALLOW_DEATH`, `DISCONNECT`, `JOIN` |
| HTTP thread (CheeseDash's `HttpServer`) | every handler in `ItemsApi` and `MorphoCatalogApi` |
| Netty network thread | payload receivers, which immediately hand off with `ctx.server().execute(...)` |
| Client thread | keybinds, the codex screen; sync application goes through `context.client().execute(...)` |
| An HTTP thread, with its own executor | `MorphoThumbnail` image scaling |

The hand-off from an HTTP thread to the server thread is
`WebDashboard.executeOnServerThread(server, () -> …)`, and the APIs use it where they must:
the item `PUT` (it refreshes inventories), `give`, `reload`, the icon routes' refresh, the
morpho `grant` and the post-delete resync.

They do **not** use it for the rest, and that is a deliberate-looking but undocumented
choice worth knowing about:

- `ItemsApi` create and delete mutate the definition list and write the file straight from
  the HTTP thread. The list is a plain `ArrayList`, read on the server thread by every
  right-click. A create concurrent with a use is a data race in theory; in practice the
  window is microseconds and no crash has been reported. It is still wrong.
- `MorphoCatalog` and `MorphoCodex` use `ConcurrentHashMap` and `synchronized` methods, so
  the catalog routes are genuinely safe to call off-thread.

`MorphoEnduranceTracker.pools` is a `ConcurrentHashMap` but is only ever touched from the
server thread.

## The mixin

One mixin, `cheeseitems.mixins.json`, `required: true`, no client/server split.

| Mixin | Target | Injection | Why |
|---|---|---|---|
| `TotemMixin` | `LivingEntity#tryUseTotem` | `@Inject(at = HEAD, cancellable = true)` | stops a custom item built on `minecraft:totem_of_undying` from resurrecting its holder |

The logic scans both hands. If a hand holds a totem carrying the `cheeseitems` tag and no
hand holds a plain totem, the method returns `false` and the death proceeds. A player
holding both a custom totem and a real one keeps the real one's effect.

The target is a vanilla method name, so this mixin is the one piece of the mod that a
Minecraft version bump can break outright. It is also the only reason a custom item can
safely use the totem texture.

## Extension points

- **Items**: add a field to `CustomItemDefinition`, handle it in `toJson`, `fromJson` and
  `mergeFrom` (all three — forgetting `mergeFrom` is how a field silently fails to save
  from the dashboard), then read it in `UseHandler` or `ItemManager.applyVisuals`. Nothing
  else needs touching; the API serves whatever `toJson` produces.
- **Resource pack**: `ResourcePackGenerator.addExternalEntry(...)` lets another mod put
  textures into the same pack. See [the resource pack](resource-pack.md#entries-from-other-mods).
- **Morphos**: `CheeseItemsMod.INSTANCE.getMorphoManager().addToCodex(player, entry)` grants
  a morpho from Java. `MorphoCatalog.getSortedForWebsite()` and `getSortedForNpcShop()`
  exist for shop surfaces outside this mod.
- **A new dashboard module**: add the class to the `cheesedash` entrypoint array in
  `fabric.mod.json`. CheeseDash discovers it at startup; nothing in this mod registers it.

## Known weaknesses

Written down because the next person will hit them.

1. **An `OPERATOR` here is a console.** An item's `useActions` run arbitrary commands at
   permission level 4, so anyone able to author an item can run anything. Mutating routes
   are restricted to `OPERATOR` by a method-level guard in both API files, and again by
   CheeseDash's module dispatcher — which is also the ceiling: because the central guard
   only knows `requireWrite`, no route here can require `SUPER_ADMIN`. The escalation path
   from `OPERATOR` remains by design.
   [Details](dashboard.md#authentication-and-role-guards).
2. **`soulbound` deletes items.** `UseHandler.onPlayerDeath` stashes soulbound stacks and
   clears them from the inventory; `onPlayerRespawn`, which would give them back, has no
   caller. The `AFTER_DEATH` handler in `CheeseItemsMod` is an empty lambda with a comment
   saying the restore belongs somewhere else. Fixing it means registering
   `ServerPlayerEvents.AFTER_RESPAWN` and calling `onPlayerRespawn` from there.
3. **Five fields and one effect list are dead.** `droppable` (its enforcement method has no
   caller), `onPickupEffects` (same), `tradeable`, `placeable`, `useConditions.zoneId`,
   `endurance.displayName`. All six round-trip through the API and the editor.
   [Table](items.md#flags-that-do-nothing).
4. **Two endurance systems.** `UseHandler` has one keyed by `tag` + player for items, and
   `MorphoEnduranceTracker` has another keyed by player for morphos. They duplicate the
   drain, regen, actionbar-rendering and depletion logic almost line for line, including
   the bar-drawing code. A change to one has to be mirrored by hand.
5. **The depleted-transform hole.** Transforming with an empty pool executes
   `cshift transform` and *then* refuses to start the drain, leaving the player
   transformed with nothing to revert them.
   [Where](morphos.md#the-endurance-pool).
6. **The transformed map is not persisted.** A restart desynchronises CheeseItems' idea of
   who is transformed from CobbleShift's.
7. **`cshift` is called as a command string.** No API, no version check, no declared
   dependency. It breaks quietly when CobbleShift changes.
8. **No catalog reload.** `MorphoCatalog.load()` runs once. Hand edits to
   `cheeseitems_morphos.json` on a running server are overwritten.
9. **`baseItem` is never validated.** An unknown identifier resolves to `minecraft:air`,
   `give` reports success, and the player receives nothing.
10. **A parse error in `cheeseitems.json` empties the item list** instead of refusing to
    start, and the next save overwrites the broken file with `{"items":[]}`.
11. **The generated pack replaces whole vanilla model and font files**, so it conflicts
    with any other pack touching the same base items or `default.json`.
12. **`CheeseStore` is not declared** in `fabric.mod.json`, not even as a `suggests`,
    although `CheeseStoreHook`'s javadoc says it is. The reflection is guarded, so this
    costs nothing at runtime — it only misleads whoever reads the manifest.
13. **`/items/:id/give` skips the id validation** that `GET`, `PUT` and `DELETE` run,
    because the `/give` branch is matched before the `isValidId` check. The id is only ever
    used as a map key, so there is no path traversal, but the asymmetry is accidental.

## Environment

`"environment": "*"` with a `client` entrypoint. The jar loads on both sides. The server
half is the whole item system and the morpho logic; the client half is the Codex screen,
the two keybinds and the image cache. A player without the jar can be given items, sees
them correctly (the data travels in the stack's components), receives the resource pack,
and has no Codex GUI and no way to toggle a morpho.

`cheesedash` is a hard `depends`, so the mod cannot load without it — both halves of the
mod reach into CheeseDash classes directly (`CheeseDashMod.INSTANCE.getConfig().getPort()`
among others), and there is no fallback path.

---

← [Dashboard API](dashboard.md) · [Troubleshooting](troubleshooting.md) →
