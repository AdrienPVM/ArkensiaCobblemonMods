[← CheeseMarket](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

The jar contains 23 Java source files, about 6 900 lines, all under `com.cheesemarket`.
The event system is not here: it lives in [CheeseGuard](../../CheeseGuard/).

---

## Package layout

```text
com.cheesemarket
├── CheeseMarket.java            ModInitializer — payloads, receiver, command, lifecycle
├── CheeseMarketClient.java      ClientModInitializer — the two S2C receivers
├── command/
│   └── ShopCommands.java        /cmadmin and the /shop tree
├── data/
│   ├── ShopData.java            shop: colours, display mode, layout, sets
│   ├── ShopSet.java             set: name, rotation, items, sortOrder
│   ├── ShopItem.java            item: icon, outputs, costs, commands, stock
│   ├── ItemEntry.java           { itemId, count, components? }
│   ├── CommandEntry.java        { command, count }
│   ├── StockConfig.java         stock mode, limits, reset cadence
│   ├── RotationConfig.java      ALWAYS / SCHEDULE / MANUAL
│   └── ShopLayout.java          elements, item card template, confirm dialog style
├── manager/
│   └── ShopManager.java         load/save, stock counters, purchase validation, tx log
├── network/
│   └── ShopPayloads.java        three CustomPayload records
├── server/
│   ├── ShopGuiHandler.java      every action from a client; the purchase pipeline
│   ├── MorphoShopRenderer.java  synthesises a shop from the CheeseItems catalogue
│   ├── CheeseStoreHook.java     fromage wallet, by reflection
│   ├── NumismaticHook.java      coin purse, by reflection
│   └── CheeseItemsHook.java     custom item icons, names, stacks, by reflection
├── client/
│   ├── ShopViewScreen.java      the player interface
│   └── CheeseMarketScreen.java  the in-game admin editor
└── dashboard/
    ├── CheeseMarketDashboardModule.java   prefix "market", nav metadata
    ├── MarketApi.java                     the REST dispatch
    └── AssetManager.java                  PNG assets and layout templates

```

---

## Who owns what

**`ShopManager`** is the single mutable authority. A lazy singleton, replaced wholesale
by `init(worldDataDir)` at `SERVER_STARTED`. It owns the shop map, the three stock maps
and the transaction list; it is the only class that writes shop JSON, the stock file or
the transaction file. Nothing else holds a `ShopData` reference across ticks.

Its collections are deliberately mixed: `shops` is a plain `LinkedHashMap` because only
the server thread touches it, while the stock maps are `ConcurrentHashMap` and the
transaction list is a `CopyOnWriteArrayList` because HTTP threads read them while
building API responses.

**`ShopGuiHandler`** is stateless and static. It takes one action string plus a player
and does everything from admin CRUD to the purchase pipeline. At 1 300 lines it is the
largest class in the mod and the place where business logic actually lives.

**`ShopViewScreen`** holds all player-side rendering state: the parsed shop JSON, the
layout, scroll offsets, the confirmation dialog's quantity, and a map of dynamically
registered textures for custom item icons. It destroys those textures in `removed()`.

**`AssetManager`** owns the PNG and template directories, and is the only validation
layer for uploaded files.

The three `*Hook` classes own one cached reflection handle set each, resolved once on
first use behind a `checked` flag, and each logs one line saying whether it found its
target mod.

---

## State and disk writes

| What | Where | Written |
|---|---|---|
| shop definitions | `<world>/data/cheesemarket/shops/<id>.json` | on every mutation, whole file, pretty-printed |
| stock counters | `<world>/data/cheesemarket/cheesemarket_stock.json` | on every purchase, and at `SERVER_STOPPING` |
| transaction log | `<world>/data/cheesemarket/cheesemarket_transactions.json` | on every purchase |
| uploaded images | `<world>/data/cheesemarket/assets/<shopId>/*.png` | on upload |
| layout templates | `<world>/data/cheesemarket/templates/*.json` | on save |

There is no dirty-flag batching and no write queue. A purchase writes two files
synchronously on the server thread; an item edit rewrites the whole shop file. Both are
small, both are fine at the scale this ran at, and both are the first thing to look at
if a shop file ever grows past a few hundred kilobytes.

Reads happen at `SERVER_STARTED` and on `/shop reload`, and nowhere else. A shop file
edited by hand while the server runs is overwritten by the next mutation.

---

## Threading

| Thread | What runs on it |
|---|---|
| server thread | everything in `ShopGuiHandler`, every `ShopManager` mutation, the commands, the lifecycle callbacks |
| CheeseDash HTTP thread | `MarketApi` dispatch, validation, JSON building, `AssetManager` file I/O |
| client render thread | `ShopViewScreen`, `CheeseMarketScreen`, texture registration |

Two hand-offs, both explicit:

- **Client → server thread.** The `cheesemarket:shop_action` receiver registered in
  `CheeseMarket.onInitialize` wraps the handler in `context.server().execute(…)`, so no
  action ever runs on a netty thread.
- **HTTP thread → server thread.** Every mutating handler in `MarketApi` wraps its work
  in `WebDashboard.executeOnServerThread(server, () -> …)` and reads the result out of a
  one-element array afterwards. GET handlers read the thread-safe collections directly
  from the HTTP thread, which is why `transactions` is copy-on-write.

`AssetManager` is the exception: it reads and writes PNG files straight from the HTTP
thread. That is safe — the files are untouched by the server thread — but it means a
slow disk blocks an HTTP request rather than a tick.

---

## Network protocol

Three payloads, all defined in `ShopPayloads`:

| Id | Direction | Shape |
|---|---|---|
| `cheesemarket:shop_action` | C2S | one string, the action |
| `cheesemarket:shop_sync` | S2C | `viewMode` plus a JSON string |
| `cheesemarket:shop_open` | S2C | one boolean, `adminMode` |

`shop_sync` uses a custom large-capacity string codec reading and writing with
`Integer.MAX_VALUE` as the limit, instead of `PacketCodecs.STRING`'s 32 767 characters.
It has to: a shop view embeds base64 PNGs for the background and for every custom item
icon, plus a serialised vanilla tooltip per item id, and that comfortably exceeds the
default cap.

The protocol is a string-splitting one. Actions look like
`item_save:<shopId>:<setId>:<itemId>|<json>`, split on `:` with a limit and on `|` for
the payload. Two separators are in use because item ids contain colons: the
output and cost actions use `|` throughout for exactly that reason. It works, and it is
the part of the mod a maintainer is most likely to break by adding a field.

`viewMode` values: `SHOP_LIST`, `SHOP_EDIT`, `SET_LIST`, `SET_EDIT`, `ITEM_LIST`,
`ITEM_EDIT`, `ITEM_PICKER`, `ERROR`, `SHOP_VIEW`, `PURCHASE_RESULT`, `CLOSE`. The client
routes `SHOP_VIEW` and `PURCHASE_RESULT` to the player screen, `CLOSE` to closing
everything, and all the rest to the admin screen.

---

## Mixins

**None.** `fabric.mod.json` has no `mixins` key, and CheeseMarket needs none: it hooks
the game through Fabric API events, the command registration callback and its own
payloads.

---

## Soft dependencies

Three optional mods, each reached by reflection so the jar loads without them. All three
are `suggests` or absent in `fabric.mod.json` — only `cheesedash` and `fabric-api` are
hard requirements.

| Hook | Target | Used for |
|---|---|---|
| `CheeseStoreHook` | `com.cheesestore.WalletManager` | fromage balance and deduction |
| `NumismaticHook` | `com.glisco.numismaticoverhaul.ModComponents` / `CurrencyComponent` | coin purse balance and deduction |
| `CheeseItemsHook` | `com.cheeseitems.CheeseItemsMod`, `ItemManager`, `CheeseItemsConfig`, `CustomItemDefinition`, `morpho.MorphoCatalog` | icon PNGs, display names, built stacks, morpho catalogue |

`MorphoShopRenderer` and `ShopGuiHandler.MorphoCatalogReflect` add a fourth and fifth
reflection surface onto CheeseItems' morpho classes.

All of it resolves classes and methods **by name**. That is a deliberate trade-off —
the mods stay independently buildable — and a standing fragility: rename
`getPriceCredits`, `hasInCodex` or `buildItemStack` in CheeseItems, or
`CurrencyComponent.modify` in Numismatic Overhaul, and the matching feature switches
itself off with one `WARN` line at startup and no further complaint. Grep the log for
`[Market] Failed to initialize` after updating either mod.

`fabric.mod.json` lists `cheesestore` and `cheeseitems` under `suggests`. Numismatic
Overhaul is not listed at all, even as a suggestion, although the mod reflects into it
by mod id.

---

## The former `com.cheeseguard` copy

Builds before 29 October 2026 carried a second, older copy of CheeseGuard's code inside
this jar: 86 classes under `com.cheeseguard`, a `cheeseguard.mixins.json`, and
`assets/cheeseguard/lang/`. Nothing in CheeseMarket used them and no entrypoint pointed
at them, but they were not harmless. With both mods installed, the same class names
existed in two jars, and which copy the game loads is not something either mod controls;
the duplicated language files could likewise override the real CheeseGuard's
translations. The stale copy also predated CheeseGuard's permission fixes.

All of it was deleted. If an older `cheesemarket` jar is still in a `mods/` folder,
replace it.

---

## Known weaknesses

### The action channel is unauthenticated

`CheeseMarket.onInitialize` registers one global receiver for
`cheesemarket:shop_action` and hands every payload to `ShopGuiHandler.handle` with no
permission check anywhere. `ShopGuiHandler` then dispatches on the action string, and
that string space includes the admin actions: `shop_create`, `shop_delete`,
`set_delete`, `item_save`, `item_delete`, `item_toggle` and the rest.

A modified client can therefore delete a shop, rewrite an item's price or switch a set
on, without ever being an operator. The `/cmadmin` permission check guards the *command*
that opens the admin screen, not the actions the screen sends.

The same applies to `shop_buy`: any client can buy from any shop id it knows, with no
check that the shop was ever opened for them and no proximity test.

This is the most serious issue in the mod. It is not fixed here because fixing it means
deciding what an admin action is allowed to be — the obvious patch is a
`player.hasPermissionLevel(2)` gate on every action that is not `shop_buy`,
`shop_tab` or `open_shop_player`, plus a per-player record of which shop was last opened
to validate `shop_buy` against.

### Payment and delivery are not atomic

Documented step by step in [purchases](purchases.md). The short version: costs are taken
before outputs are delivered, and three cases leave a player charged with nothing to
show for it — an output item id that is not in the registry, an exception during
delivery, and a `CheeseStoreHook.deduct` that returns false after the balance check
passed. The first two log loudly; the third does not.

### `AssetManager` resolves the wrong directory

`AssetManager.init(worldDataDir)` exists and nothing calls it, so the class falls back
to a hardcoded `world` directory name while `ShopManager` uses the real save path. On a
server whose `level-name` is not `world`, uploaded images and shop definitions live in
different trees. One line in `CheeseMarket.onInitialize` would fix it.

### `reset-stock` is a no-op

`POST …/items/:itemId/reset-stock` answers `{"reset":true}` and has no body. It looks
like it worked.

### Three configured features are not implemented

`displayMode` (`TABS`/`FLAT`/`FEATURED`), `showWelcomeMessage`/`welcomeMessage`, and the
`IMAGE` layout element are all editable, stored, served and ignored. The missing tab bar
also means **only the first active set of a shop is reachable in game**, which is the
one to know about: an admin can build a second active set and watch it never appear.

### Unused language keys

Eighteen of the 37 keys in `assets/cheesemarket/lang/en_us.json` are referenced by no
Java code: the whole `cheesemarket.command.*` block (superseded by
`cheesemarket.cmd.*`), the whole `cheesemarket.purchase.*` block (superseded by
`cheesemarket.gui.*`), and the whole `cheesemarket.tooltip.*` block (the tooltips are
now built from real vanilla item tooltips instead). `cheesemarket.purchase.items_dropped`
is the one worth reviving — items *are* dropped at a player's feet and nobody tells
them.

Meanwhile several player-facing strings are hardcoded French literals in Java rather
than lang keys: `Confirmer`, `Fermer`, `Aucun article.`, the delivery-failure message,
the morpho messages, and the stock labels `Illimité`, `Par joueur`, `Quotidien`,
`Toujours actif` and so on. An English client sees French there.

### Dead code

- `ShopManager.logTransaction(…, String type, …)` — the public `BUY`/`SELL` overload —
  is never called. There is no sell path in the mod, despite `shop_sell.png` and the
  `*_sell/` texture directories in the jar.
- `ShopManager.recordPurchase(shopId, setId, itemId, uuid)` — the 4-argument overload —
  is never called.
- `MorphoShopRenderer` writes `rotation.scheduleType` into the synthetic set, and
  `RotationConfig.fromJson` reads `mode`. The key is ignored; the default `ALWAYS` is
  what makes the synthetic set work anyway.
- `ShopPayloads`' documentation comment lists a `picker_search` action and a
  `set_manual_toggle`; both exist, but it also lists client-only prefixes `_scroll` and
  `_setmode` that `ShopViewScreen` no longer handles.

### Stock checks ignore quantity

`canPurchase` takes no quantity, so a single purchase can exceed a global stock or a
per-player limit. Covered in
[purchases](purchases.md#quantity-is-not-part-of-the-stock-check).

---

## Extension points

If you are adding to this mod rather than reading it:

- **A new shop-level field.** Add it to `ShopData` with a default in the constructor, to
  `toJson`/`fromJson`, to `MarketApi.applyShopEdits`, and to
  `ShopGuiHandler.buildPlayerShopViewJson` if the client needs it. The two
  `applyShopEdits` methods — one in `MarketApi`, one in `ShopGuiHandler` — are separate
  copies that drift apart; the `ShopGuiHandler` one already lacks the button
  colours, `morphoCatalog` and `layout`.
- **A new currency.** Follow `CheeseStoreHook`: a reflection hook, an id predicate, and
  four touch points in `ShopGuiHandler.deductCosts` (check phase, deduct phase,
  `countInInventory`, `removeFromInventory`), plus a branch in `ShopViewScreen` for the
  icon.
- **A new layout element type.** Add it to `ShopLayout.ElementType`, teach
  `ShopViewScreen`'s custom-element loop to draw it, and add it to `MK_ELEMENT_TYPES` in
  the dashboard's `view-market.js`. The loop currently `continue`s on three
  types, two of which are drawn elsewhere and one of which is not drawn at all.
- **A new REST route.** Add it to `MarketApi.handle` before the final 404, validate the
  ids with `isValidId` first, and wrap any mutation in
  `WebDashboard.executeOnServerThread`. The role comes free from CheeseDash's dispatch:
  `GET` is readable by anyone, anything else needs `OPERATOR`. Two limits follow from
  that. A route that mutates state must not be exposed as a `GET`, because the dispatch
  keys only on the HTTP method and would not guard it. And if the route deserves
  `SUPER_ADMIN`, the handler has to call `WebDashboard.requireAdmin` itself — the
  dispatch only ever raises the bar to `OPERATOR`.
- **A new WebSocket message.** Copy the guarded broadcast at the end of
  `handlePurchase`: check `isModLoaded("cheesedash")`, catch `NoClassDefFoundError`, and
  publish on the `market` channel.

---

← [Dashboard API](dashboard.md) · [Troubleshooting](troubleshooting.md) →
