[← CheeseMarket](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](purchases.fr.md)

# Purchases and transactions

A purchase is the only thing in this mod that moves real value, so it is the part worth
reading line by line. Everything happens inside one method,
`ShopGuiHandler.handlePurchase`, on the server thread, in a fixed order: validate,
check inventory space, take payment, deliver, run commands, record. No database
transaction wraps it. If a step after payment fails, the payment stays taken — the mod
logs it loudly and tells the player to find an admin.

This page documents what the code actually does at each step, including the three ways
a purchase can end badly.

---

## How a buy request reaches the server

The player clicks a card, a confirmation panel opens, they set a quantity and click
**Confirmer**. The client then sends one payload:

```text
cheesemarket:shop_action   →   "shop_buy:<shopId>:<setId>:<itemId>:<quantity>"
```

The receiver registered in `CheeseMarket.onInitialize` wraps the handler in
`context.server().execute(...)`, so the whole purchase runs on the server thread and
never races with a tick.

The quantity is parsed server-side and clamped: `Math.max(1, Math.min(64, …))`. A
missing fourth segment means 1, and an unparseable one also means 1 (`parseInt` returns
1 on failure). So a crafted packet cannot ask for 10 000 of something.

⚠️ There is **no permission check and no proximity check** on this payload. Any client
that can send `cheesemarket:shop_action` can buy from any shop id it knows, without
ever having been shown the shop. The same receiver also accepts the admin actions —
see [the architecture page](architecture.md#the-action-channel-is-unauthenticated).

---

## Step 1 — eligibility

`ShopManager.canPurchase(shopId, setId, itemId, playerUuid)` returns a
`PurchaseResult`:

| Result | When |
|---|---|
| `SHOP_NOT_FOUND` | no shop with that id is loaded |
| `SET_INACTIVE` | the set does not exist, **or** its rotation says it is not active right now |
| `ITEM_DISABLED` | the item does not exist, **or** `enabled` is false |
| `OUT_OF_GLOBAL_STOCK` | `GLOBAL` mode and the remaining counter is at or below zero |
| `PLAYER_LIMIT_REACHED` | `PER_PLAYER` or `PER_PLAYER_RESET` and this player has already bought their quota |
| `OK` | none of the above |

`INSUFFICIENT_FUNDS` is a member of the enum but `canPurchase` never returns it. Money
is checked later, in step 3.

Anything other than `OK` sends a red chat line and a `PURCHASE_RESULT` payload carrying
the enum name, and stops. Nothing has been touched.

| Result | Chat message (fr_fr) |
|---|---|
| `OUT_OF_GLOBAL_STOCK` | `Stock épuisé pour cet item.` |
| `PLAYER_LIMIT_REACHED` | `Vous avez atteint votre limite d'achat pour cet item.` |
| `ITEM_DISABLED` | `Cet item n'est pas disponible.` |
| `SET_INACTIVE` | `Ce set n'est pas actif en ce moment.` |
| `SHOP_NOT_FOUND` and anything else | `Achat impossible.` |

### Quantity is not part of the stock check

`canPurchase` takes no quantity argument. A `GLOBAL` item with 1 unit left accepts a
buy of 64: the check sees `remaining > 0` and passes, and `recordPurchase` then does
`Math.max(0, remaining - quantity)`, clamping the counter to zero. The player gets 64
units for 64 times the price. The same applies to per-player limits: a limit of 1 does
not stop a single purchase of 64.

If that matters on your server, give those items a cost the player cannot pay 64 times,
or treat the limit as a per-transaction limit rather than a hard cap. It is a real gap,
not a subtlety of reading.

---

## Step 2 — inventory space

`canFitOutputs` simulates the insertion before any payment is taken, which is the right
order and the reason an inventory-full purchase costs nothing.

The simulation copies the 36 slots of `PlayerInventory.main`, fills existing matching
stacks first, then empty slots, and returns false if anything is left over. On false the
player gets `Ton inventaire est plein ! Libère de la place avant d'acheter.` and a
`PURCHASE_RESULT` of `INVENTORY_FULL`.

Three limits of the simulation are worth knowing:

- Only the main 36 slots count. The off-hand and armour slots are ignored, both as
  destinations and as existing stacks.
- A `cheeseitems:*` output is assumed to stack to 64. If the underlying base item stacks
  to 1 or 16, the simulation is optimistic.
- An unknown item id is skipped by `continue`, so it never blocks the purchase — which
  is exactly the case that gets the player charged for nothing in step 4.

---

## Step 3 — payment

`deductCosts` runs in four phases: everything is checked before anything is deducted.
Returning false at any point in the checking phases means no value has moved, and the
player gets `Pas assez de ressources pour acheter x<quantity>.` with a
`PURCHASE_RESULT` of `INSUFFICIENT_FUNDS`.

| Phase | What it does |
|---|---|
| 0 | For every cost whose `itemId` is `cheesestore:fromage`, compares `count × quantity` against the CheeseStore balance |
| 1 | For every ordinary item cost, counts matching stacks in the inventory and compares against `count × quantity` |
| 2 | For Numismatic Overhaul coin costs, adds the inventory coins and the purse together |
| 3a | Deducts the fromage, then tells the player `🧀 -<n> Crédits (solde : <balance>)` |
| 3b | Removes the ordinary item costs from the inventory |
| 4 | Removes coin costs from the inventory first, then takes the shortfall out of the purse in bronze |

How each kind of cost is counted and consumed is in
[the currency section of the shop model](shop-model.md#how-a-price-is-paid).

Two things the phases do not protect against:

- `CheeseStoreHook.deduct` returns a boolean, and phase 3a ignores it. If the wallet
  refuses the deduction after phase 0 said the balance was sufficient, the purchase
  carries on as though it had been paid.
- Phase 1 counts `cheeseitems:*` costs by scanning `custom_data` for the
  `cheeseitems` tag. Two different CheeseItems definitions sharing a tag would be
  interchangeable as payment.

---

## Step 4 — delivery

`giveOutputs` walks the `outputs` list and picks a delivery route per entry:

1. **Entry with a `components` string** — delivered through the vanilla command
   `give <player> <itemId><components> <n>`, run from a silent server command source, in
   batches of 64. A failing command is logged and the loop for that entry breaks.
2. **`cheeseitems:*` entry** — the stack is built through the CheeseItems reflection
   hook, in batches of 64, and inserted with `insertStack`. If the hook returns
   something that is not an `ItemStack`, the mod logs
   `Failed to build CheeseItems stack for output: <id>` and breaks.
3. **Plain item id** — `insertStack` in batches of the item's own max stack size.

In routes 2 and 3, a failed `insertStack` falls back to `player.dropItem(stack, false)`:
the items land at the player's feet rather than vanishing. The player is **not** told.
A lang key for that message exists (`cheesemarket.purchase.items_dropped`) and is never
used — see [the unused keys](architecture.md#unused-language-keys).

### Unknown item id

```text
[Market] Item not in registry, skipping delivery: mymod:thing for player Steve
```

That line means the cost was already taken in step 3 and the output silently did not
exist. The purchase then continues to completion: commands run, stock is decremented, a
transaction is recorded, and the player is told `Achat réussi : <name>`. Nothing in the
code refunds them.

This is the failure mode to watch for after removing a mod whose items a shop still
sells. Grep the server log for `skipping delivery` before anyone complains.

### Exception during delivery

Everything in step 4 is wrapped in a try/catch. On an exception the server logs

```text
[Market] CRITICAL: giveOutputs failed for player Steve buying a1b2c3d4 x1 — payment was already taken! Error: …
```

and the player gets, in chat and not as a translatable message:

```text
[Market] Erreur de livraison ! Contactez un admin pour un remboursement.
```

The refund is manual. The log line carries the player name, the shop item id and the
quantity, which is enough to reconstruct what to give back; the matching transaction
entry carries the full cost list.

### Forced inventory resync

Right after delivery the mod pushes every inventory slot to the client as a
`ScreenHandlerSlotUpdateS2CPacket` with window id `-2`. That bypasses the open screen
handler, which is what makes a purchase visible while the player is standing in an
EasyNPC dialogue screen rather than in their own inventory. Any exception there is
swallowed.

---

## Step 5 — purchase commands

`executeCommands` runs each `CommandEntry` on the item:

- `{player}` is replaced by the buyer's name.
- A leading `/` is stripped.
- The command runs `cmd.getCount() × quantity` times.
- The source is `server.getCommandSource().withSilent()` — the console source, so the
  command has full permission regardless of the buyer's own level.
- A throwing command is logged as `Command failed for <player>: <command>` and the loop
  carries on with the next one.

A purchase command runs after payment and after delivery. If it fails, the player keeps
the items and loses whatever the command was supposed to grant.

---

## Step 6 — recording

`ShopManager.recordPurchase(shopId, setId, itemId, uuid, playerName, quantity)` does
three things, in this order:

1. Updates the stock counters — `GLOBAL` subtracts the quantity and clamps at zero,
   `PER_PLAYER` and `PER_PLAYER_RESET` add the quantity to this player's counter,
   `UNLIMITED` touches nothing.
2. Writes `cheesemarket_stock.json`.
3. Appends a transaction entry at index 0 and writes
   `cheesemarket_transactions.json`.

Both writes are synchronous, on the server thread, on every purchase. Two small files,
pretty-printed, so the cost is a few hundred microseconds — but it is two file writes per
purchase, which is why the transaction log is capped.

### What a transaction entry contains

```json
{
  "timestamp": "2026-10-29T14:03:11.284Z",
  "playerUUID": "9d4f…",
  "playerName": "Steve",
  "shopId": "general",
  "shopName": "Magasin général",
  "setId": "a1b2c3d4",
  "setName": "Consommables",
  "itemId": "e5f6a7b8",
  "itemName": "Potion de soin",
  "quantity": 3,
  "costs":   [ { "itemId": "numismatic-overhaul:silver_coin", "count": 5 } ],
  "outputs": [ { "itemId": "minecraft:potion", "count": 1 } ]
}
```

`costs` and `outputs` are the **per-unit** lists copied from the item definition, not
multiplied by `quantity`. To get what was actually paid, multiply by `quantity`
yourself. `itemId` is the shop item's internal id, `itemName` its effective display
name at the time of purchase.

The list is kept newest-first and capped at 500 entries (`MAX_TRANSACTIONS`); entry 501
pushes the oldest out of the file permanently. There is no archive. If you need long-term
sales history, copy `cheesemarket_transactions.json` on a schedule.

`ShopManager` also exposes a public `logTransaction(…, String type, …)` overload that
writes a shorter `BUY`/`SELL` shaped entry. Nothing calls it. There is no sell path in
the mod at all, despite the `shop_sell.png` and `*_sell/` textures shipped in the jar.

---

## Step 7 — notifications

After recording, in order:

1. Chat: `Achat réussi : <itemName>` (plus ` x<n>` when quantity is above 1), green.
2. A `PURCHASE_RESULT` payload with `purchaseResult: "OK"`, sent before anything else so
   the client closes the confirmation panel without waiting.
3. If CheeseDash is loaded, a `market_purchase` message on the `market` WebSocket
   channel — payload documented in [the dashboard page](dashboard.md#websocket).
   The call is guarded by `isModLoaded("cheesedash")` and wrapped against
   `NoClassDefFoundError`, so a CheeseDash-less install does not crash here.
4. A fresh `SHOP_VIEW` payload with `skipImages = true`, so the grid shows the new stock
   numbers without re-sending the base64 background the client already holds.

---

## The morpho catalog path

A shop with `morphoCatalog: true` never reaches any of the above. `handlePurchase`
detects the flag on the raw shop and hands over to `handleMorphoPurchase`, because the
items of such a shop are synthesised at view time and are not on disk, so
`ShopManager.canPurchase` would find nothing to validate against.

That path, in order:

1. The item id must start with `morpho_`, otherwise `INVALID_ITEM`.
2. The catalog entry is looked up through reflection into
   `com.cheeseitems.morpho.MorphoCatalog`. Missing entry → `ITEM_DISABLED`.
3. If the player already has the morpho in their codex, they get
   `Vous possédez déjà cette morpho dans votre codex.` and `PLAYER_LIMIT_REACHED`.
4. The fromage balance is compared against the entry's `priceCredits`; too low gives
   `Solde insuffisant : <balance> / <price> crédits.` and `INSUFFICIENT_FUNDS`.
5. `CheeseStoreHook.deduct` is called and **its result is checked** here — a false
   return ends the purchase with `INSUFFICIENT_FUNDS`.
6. The morpho is granted by running `cheeseitem morpho grant <player> <morphoId>` from
   the server command source. An exception is logged as
   `Failed to grant morpho <id>: <message>` — and the fromage has already gone.
7. A sound plays, `PURCHASE_RESULT: OK` goes out, and the materialised view is re-sent so
   the entry shows as owned.

This path writes **no transaction entry and no stock entry**. Morpho sales do not appear
in the dashboard's transaction list and are not counted in `market_purchase` broadcasts.
The quantity argument is ignored entirely: one purchase grants one morpho.

Everything about it depends on CheeseItems class and method names resolved by
reflection. This is an observation of how the two mods currently fit together, not a
contract: rename `getPriceCredits` or `hasInCodex` in CheeseItems and this path stops
working, silently, with a single warning line at startup.

---

← [Layout editor](layout-editor.md) · [Dashboard API](dashboard.md) →
