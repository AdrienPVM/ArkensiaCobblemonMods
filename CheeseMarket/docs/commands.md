[← CheeseMarket](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

Two command roots, both registered in `ShopCommands.register`: `/cmadmin` and `/shop`.
Both require **permission level 2** at the root, and two `/shop` subcommands raise that
to **level 4**. Nothing here is meant for players — a player below level 2 does not see
either command in tab completion and gets the vanilla *Unknown or incomplete command*
error if they type it.

No aliases exist. No command works only in a particular state.

---

## `/cmadmin`

```mcfunction
/cmadmin
```

No arguments. Permission level 2. Must be run by a player: a console or command-block
source gets `Joueur requis` and a return value of 0.

Opens the in-game admin screen — a full-screen editor for shops, sets and items. The
command sends two packets: a `ShopOpenPayload(adminMode = true)` that tells the client
to open `CheeseMarketScreen`, then the result of the `open_shop_list` action so the
screen has data to draw.

⚠️ The repository's own earlier README claimed there is no in-game admin editor "by
design". There is: `CheeseMarketScreen` is about a thousand lines and is fully wired
through this command. The dashboard is the better editor and the one that gets
maintained, but the in-game one exists and works.

```mcfunction
/cmadmin
```

No chat output. The screen opens.

| Message | Cause |
|---|---|
| `Joueur requis` | the source is not a player — console, command block or `/execute as` on a non-player |

---

## `/shop open`

```mcfunction
/shop open <shopId>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `shopId` | single word | yes | a loaded shop id; **no tab completion** | — |

Permission level 2. Opens the shop **for the player running the command**, and only for
them. There is no target-player argument: `/shop open general` run from the console does
nothing but report an error, and there is no way to push a shop to someone else with
this command. To open a shop for another player, send the
`open_shop_player:<shopId>` action from whatever is driving them — that is what the NPC
integration does.

What it does, in order:

1. Resolves the shop from `ShopManager`.
2. Runs it through `MorphoShopRenderer.materialize`, which returns the shop unchanged
   unless `morphoCatalog` is true.
3. Sends `ShopOpenPayload(adminMode = false)`.
4. Builds the player-facing view JSON — active sets, enabled items, per-player stock
   figures, resolved tooltips, base64 images — and sends it as a `SHOP_VIEW` payload.

Nothing is written to disk and no state changes. Argument completion is not wired up:
Brigadier sees a bare `StringArgumentType.word()`, so you have to know the id. Get it
from `/shop list`.

```mcfunction
/shop open general
```

The shop panel opens. No chat output on success.

| Message | Cause |
|---|---|
| `Joueur requis` | the source is not a player |
| `Shop introuvable : <id>` | no shop with that id is loaded; check `/shop list` |

---

## `/shop list`

```mcfunction
/shop list
```

No arguments. Permission level 2. Prints every loaded shop to the caller, one line each,
reading the in-memory map — so a shop created from the dashboard a second ago is listed
immediately.

```text
── Shops CheeseMarket ──
general — Magasin général (3 sets)
metamorph — Métamorph (0 sets)
```

The header is gold and the lines yellow. The set count is the **total** number of sets,
not the number currently active. With no shops loaded you get `Aucun shop trouvé.` in
yellow — a success, not an error.

---

## `/shop reload`

```mcfunction
/shop reload
```

No arguments. **Permission level 4.** Calls `ShopManager.loadAll()`, which:

1. clears the in-memory shop map;
2. re-reads every `*.json` in `<world>/data/cheesemarket/shops/`;
3. re-reads `cheesemarket_stock.json`;
4. re-reads `cheesemarket_transactions.json`.

```text
Shops rechargés depuis le disque.
```

Broadcast to other operators, because the feedback is sent with `true` for the
`broadcastToOps` flag.

Two consequences worth planning around:

- Reload **discards unsaved in-memory state**. In practice every mutation path saves
  immediately, so there is nothing to lose — but a shop file that has become unparseable
  since the last load disappears from the map, with only
  `[CheeseMarket] Failed to load shop: <file> → <message>` on standard error to say so.
- The stock and transaction files are re-read from disk, so hand-editing
  `cheesemarket_stock.json` and then reloading is the supported way to reset a counter.
  Do it while nobody is buying: the next purchase rewrites the whole file from memory.

A player holding an open shop panel is not refreshed. They keep looking at the old data
until the panel is closed and reopened.

---

## `/shop create`

```mcfunction
/shop create <shopId>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `shopId` | single word | yes | any single word not already used | — |

Permission level 2 (declared explicitly on the subcommand as well as inherited).
Creates a shop with default values — name `Nouveau shop`, `TABS` display mode, the stock
colour set, no sets — and **writes `<shopId>.json` immediately**.

The id becomes the file name. It is checked for collision and nothing else: no character
validation happens on this path, unlike the dashboard's `isValidId`. A shop id with a
slash or a `..` in it will be handed straight to `Path.resolve`. Use plain lowercase
words.

```mcfunction
/shop create farmers
```

```text
Shop créé : farmers
```

Broadcast to operators. The next step is the dashboard: the command gives you an empty
shop and no way to put anything in it.

| Message | Cause |
|---|---|
| `Un shop avec l'ID '<id>' existe déjà.` | the id is taken; ids are not reusable without deleting first |

---

## `/shop delete`

```mcfunction
/shop delete <shopId>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `shopId` | single word | yes | a loaded shop id; no tab completion | — |

**Permission level 4.** Removes the shop from the map and deletes its JSON file. There
is no confirmation and no undo.

```mcfunction
/shop delete farmers
```

```text
Shop supprimé : farmers
```

Red, broadcast to operators.

What it leaves behind:

- `<world>/data/cheesemarket/assets/<shopId>/*.png` — the uploaded images. Only the
  dashboard's `DELETE /shops/:id` cleans those up; the command does not.
- the stock counters keyed on that shop id in `cheesemarket_stock.json`, which stay in
  the file and are rewritten on the next purchase of any item;
- every transaction entry naming that shop, which is the point — sales history should
  outlive the shop.

Recreating a shop with the same id resurrects its old stock counters, because the keys
match again. If that is not what you want, clear the stock file between the delete and
the create.

| Message | Cause |
|---|---|
| `Shop introuvable : <id>` | no shop with that id is loaded |

---

## Driving the mod from somewhere else

Everything above is reachable without a command, and the dashboard is the intended
route:

- **Dashboard** — the `market` module. Shop, set and item CRUD, the layout editor,
  assets, templates, transactions and statistics. See
  [the dashboard API](dashboard.md).
- **The action channel** — `cheesemarket:shop_action` carries the actions of the player
  screen and of the admin screen. That is how an NPC opens a shop for a player. ⚠️ In
  this version it does not check the sender's permissions: see
  [architecture](architecture.md#the-action-channel-is-unauthenticated).

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
