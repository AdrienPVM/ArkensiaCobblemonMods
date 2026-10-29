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
source gets `Player required` and a return value of 0.

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
| `Player required` | the source is not a player — console, command block or `/execute as` on a non-player |

---

## `/shop open`

```mcfunction
/shop open <shopId>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `shopId` | single word | yes | a loaded shop id; **no tab completion** | — |

Permission level 2. Opens the shop **for the player running the command**, and only for
them. There is no target-player argument: `/shop open general` typed at the console does
nothing but report an error. The command uses the source's player, though, so
`/execute as <player> run shop open <shopId>` — from the console, a command block, or an
NPC or quest that runs commands at level 2 or above — opens the shop on that player's
screen. That is the way to push a shop to someone else. The `open_shop_player:<shopId>`
action does the same thing, but it is a client-to-server packet: only the player's own
client can send it, and the only built-in sender is the *▶ Voir* preview button of the
`/cmadmin` screen.

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
| `Player required` | the source is not a player |
| `Shop not found: <id>` | no shop with that id is loaded; check `/shop list` |

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
not the number currently active. With no shops loaded you get `No shop found.` in
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
Shops reloaded from disk.
```

Broadcast to other operators, because the feedback is sent with `true` for the
`broadcastToOps` flag.

Two consequences worth planning around:

- Reload **discards unsaved in-memory state**. In practice every mutation path saves
  immediately, so there is nothing to lose — but a shop file that has become unparseable
  since the last load disappears from the map, with only
  `[CheeseMarket] Failed to load shop: <file> → <message>` on standard error to say so.
- The stock file is re-read **into the existing counters, which are not cleared
  first**: a value you change in `cheesemarket_stock.json` replaces the one in memory,
  but a key you delete from the file survives in memory and is written back on the next
  purchase. To reset a counter this way, set it (`0` for a per-player count, the full
  amount for a global stock) rather than removing it, then reload. Do it while nobody is
  buying: the next purchase rewrites the whole file from memory. The transaction list,
  by contrast, is cleared and fully replaced by the file.

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
validation happens on this path, unlike the dashboard's `isValidId`. Brigadier's
`word()` argument already limits it to letters, digits and `_ - . +`, so a slash cannot
get through here — but the `shop_create:` action of the network channel carries an
arbitrary string to the same `Path.resolve`. Use plain lowercase words.

```mcfunction
/shop create farmers
```

```text
Shop created: farmers
```

Broadcast to operators. The next step is the dashboard: the command gives you an empty
shop and no way to put anything in it.

| Message | Cause |
|---|---|
| `A shop with ID '<id>' already exists.` | the id is taken; ids are not reusable without deleting first |

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
Shop deleted: farmers
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
match again. The counters also stay in memory after the delete, and the server writes
them back to the file whenever it saves stock — on every purchase and when it stops — so
emptying the file while the server runs achieves nothing. If you want a clean slate,
stop the server, remove that shop's keys from `cheesemarket_stock.json`, then start it
and create the shop.

| Message | Cause |
|---|---|
| `Shop not found: <id>` | no shop with that id is loaded |

---

## Driving the mod from somewhere else

Everything above is reachable without a command, and the dashboard is the intended
route:

- **Dashboard** — the `market` module. Shop, set and item CRUD, the layout editor,
  assets, templates, transactions and statistics. See
  [the dashboard API](dashboard.md).
- **The action channel** — `cheesemarket:shop_action` carries both the player actions
  and a full set of admin actions. Only a client can send on it — the server cannot push
  an action on a player's behalf — and every admin action requires permission level 2,
  the same bar as `/cmadmin`; below it, the action is ignored and logged. See
  [architecture](architecture.md#the-action-channel).

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
