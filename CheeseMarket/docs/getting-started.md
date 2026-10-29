[← CheeseMarket](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this page a player standing on your server will open a shop panel, click a
card, drag a quantity slider, pay five emeralds and walk away with a diamond pickaxe —
and you will see the sale in the dashboard's transaction list. Allow twenty minutes.

You need a 1.21.1 Fabric server with CheeseDash and CheeseMarket installed on the
server, CheeseMarket installed on your own client, and your dashboard token to hand. If
any of that is not true yet, start with [installation](installation.md).

---

## 1. Create the shop

In game, as an operator:

```mcfunction
/shop create starter
```

```text
Shop créé : starter
```

That wrote `<world>/data/cheesemarket/shops/starter.json` with default values and an
empty set list. Confirm it is loaded:

```mcfunction
/shop list
```

```text
── Shops CheeseMarket ──
starter — Nouveau shop (0 sets)
```

The name is `Nouveau shop` because the command does not take one. You will fix that in
the next step.

---

## 2. Open it in the dashboard

Go to `http://<your-server>:44311`, log in, and click **CheeseMarket 🛒** in the
sidebar. The shop list shows `starter` with 0 sets and 0 items. Click it.

Four tabs: **Configuration**, **Layout**, **Sets & Items**, **Transactions**.

On **Configuration**, set:

- *Name* → `Boutique de départ`
- *Description* → `Outils et consommables`

The edit is a `PUT` on the shop and is saved as you make it. If you would rather use the
API:

```bash
curl -X PUT -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"Boutique de départ","description":"Outils et consommables"}' \
  http://localhost:44311/api/v1/market/shops/starter
```

Leave *Display mode* alone. It is stored and the client ignores it — see
[the shop model](shop-model.md#shop).

---

## 3. Add a set

A shop with no set shows nothing, because items live in sets and only an active set is
rendered.

On **Sets & Items**, click to add a set and name it `Outils`. Leave the rotation on
`ALWAYS` — the set is open all the time, which is what you want for a first test.

```bash
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"Outils"}' \
  http://localhost:44311/api/v1/market/shops/starter/sets
```

The response carries the set's generated id — eight hex characters, something like
`a1b2c3d4`. Note it down; the item routes need it.

⚠️ Add exactly **one** set for now. A shop can hold several, and only the first active
one is reachable in the player interface. A second set would be invisible, which is a
confusing way to start.

---

## 4. Add an item

Still on **Sets & Items**, add an item inside `Outils` and fill in:

- *Display item* → `minecraft:diamond_pickaxe`
- *Display name* → `Pioche de mineur`
- *Output* → `minecraft:diamond_pickaxe` × 1
- *Cost* → `minecraft:emerald` × 5
- *Stock* → `PER_PLAYER`, limit 2

Or in one call, substituting your set id:

```bash
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{
        "displayItem": "minecraft:diamond_pickaxe",
        "displayName": "Pioche de mineur",
        "outputs": [{"itemId":"minecraft:diamond_pickaxe","count":1}],
        "costs":   [{"itemId":"minecraft:emerald","count":5}],
        "stock":   {"mode":"PER_PLAYER","perPlayerLimit":2},
        "enabled": true
      }' \
  http://localhost:44311/api/v1/market/shops/starter/sets/a1b2c3d4/items
```

Two things worth understanding right now, because they shape every item you will ever
build:

- **The currency is the item id.** There is no price field. `costs` is a list of item
  ids and counts, and whatever you put there is what the player pays. Emeralds here;
  it could be `cheesestore:fromage` for credits or
  `numismatic-overhaul:silver_coin` for coins. See
  [how a price is paid](shop-model.md#how-a-price-is-paid).
- **`displayItem` set to `minecraft:paper` means "no icon"**, and falls back to the
  first output. Anything else is used as-is.

---

## 5. Look at it in game

```mcfunction
/shop open starter
```

The world dims and the panel appears: your shop name in gold, the description under it,
a close button top right, and a single card in the middle of the grid showing a diamond
pickaxe with `5` and an emerald icon under it. Hovering the card gives you the pickaxe's
real tooltip. Under the icon, `2/2 (vous)` — your personal quota.

If nothing happens at all, your client is missing the mod. If the panel opens empty, the
set is not active or the item is disabled — check the `active` flag in
`GET /api/v1/market/shops/starter`, and see
[troubleshooting](troubleshooting.md#the-shop-panel-opens-but-the-grid-is-empty).

---

## 6. Buy something

Give yourself the money first — the shop takes emeralds from your inventory, not from
thin air:

```mcfunction
/give @s minecraft:emerald 10
```

Then, in the panel:

1. **Click the card.** The confirmation panel opens: what you pay on the left, what you
   get on the right, a slider, and `Fermer` / `Confirmer`.
2. **Drag the slider to 2.** The cost column updates to 10 emeralds.
3. **Click `Confirmer`.**

```text
Achat réussi : Pioche de mineur x2
```

The panel closes, your emeralds are gone, two pickaxes are in your inventory, and the
card now reads `0/2 (vous)` and is greyed out. You are at your limit; clicking it does
nothing.

That greying is the per-player limit doing its job. The counter is written to
`<world>/data/cheesemarket/cheesemarket_stock.json` straight away, so it survives a
restart.

---

## 7. See the sale

Back in the dashboard, open the **Transactions** tab of the shop. One line: your name,
the item, quantity 2, the cost list, a timestamp.

```bash
curl -H "Authorization: Bearer <token>" \
  "http://localhost:44311/api/v1/market/transactions?shopId=starter&n=10"
```

```json
{"transactions":[{"timestamp":"2026-10-29T14:03:11.284Z","playerName":"Steve",
  "shopId":"starter","shopName":"Boutique de départ","itemName":"Pioche de mineur",
  "quantity":2,"costs":[{"itemId":"minecraft:emerald","count":5}],
  "outputs":[{"itemId":"minecraft:diamond_pickaxe","count":1}]}],
 "total":1,"returned":1}
```

The `costs` and `outputs` lists are **per unit** — multiply by `quantity` to get what
actually changed hands. The log keeps the last 500 sales and then starts discarding the
oldest, so copy the file if you want history.

If you have a dashboard tab open while someone buys, the sale also arrives live on the
`market` WebSocket channel as a `market_purchase` message.

---

## 8. Make it look like yours

The shop works. Now the part that makes it not look like everyone else's.

Open the **Layout** tab. You get the 350 × 250 canvas with the default arrangement laid
out on it: a background, the title, the description, the close button and the item grid.

Try three things, in this order:

1. **Move the grid.** Select the `ITEM_GRID` element and drag it, or nudge it with the
   arrow keys. Set its `cols` to 4 in the properties panel. Reopen the shop in game: four
   cards per row, centred.
2. **Upload a background.** Click *Upload image*, pick a 350 × 250 PNG under 2 MB, then
   select the `BACKGROUND` element and set its `src` to the name you gave. The image is
   shipped to the client inline the next time a player opens the shop.
3. **Save it as a template.** Click *Templates*, save under a name, and the arrangement
   becomes reusable on your next shop.

Autosave runs two seconds after you stop editing, Ctrl+Z undoes thirty steps, and
`Reset` puts the default arrangement back. The full element and property reference is in
[the layout editor](layout-editor.md).

---

## 9. Put it behind an NPC

`/shop open` needs permission level 2, so it is not how players reach a shop. Players
get there because something else opens it for them: an NPC dialogue, a quest reward, a
command block, or any mod that can send the `open_shop_player:<shopId>` action for a
player.

That is also the point at which the shop stops being a test. Two things to settle before
you let anyone else in:

- **Rotation.** A set on `SCHEDULE` opens and closes on the server's wall clock, which
  is how you run a night market. A set on `MANUAL` is a switch you flip from the
  dashboard.
- **Limits.** `UNLIMITED` is fine for a general store, `GLOBAL` for a limited run,
  `PER_PLAYER_RESET` for a daily allowance. Be aware that a single purchase is not
  clamped by the limit — a `GLOBAL` item with one unit left will still sell 64 at once.
  [Why](purchases.md#quantity-is-not-part-of-the-stock-check).

---

← [Installation](installation.md) · [Commands](commands.md) →
