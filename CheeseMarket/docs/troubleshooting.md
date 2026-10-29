[← CheeseMarket](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

## The server refuses to start, complaining about `cheesedash`

**Cause.** CheeseDash is a hard dependency declared in `depends`. Fabric Loader will not
start without it.

**Fix.** Put `cheesedash-*.jar` in the same `mods/` directory. If you built it yourself,
build CheeseDash **before** CheeseMarket — the Gradle dependency is a glob on
`../CheeseDash/build/libs/cheesedash-*.jar`, and an empty glob gives you a jar whose
`com.cheesedash` imports were never resolved.

---

## `/shop open` prints nothing and no panel appears

**Cause.** The mod is missing on the client. The server sent a `cheesemarket:shop_open`
payload and the client has no receiver for it, so it is dropped silently. The panel is a
client-side `Screen`; the server cannot draw it.

**Fix.** Install the same `cheesemarket-*.jar` in the client's `mods/` as well, plus
Fabric API. The client does not need CheeseDash. Look for
`[CheeseMarket] Client initialized.` in the client log to confirm.

---

## The shop panel opens but the grid is empty

`Aucun article.` where the cards should be.

**Cause.** One of four, in order of likelihood:

1. The shop has no set.
2. The set's rotation says it is not active right now.
3. Every item in the set has `enabled: false`.
4. The items are in a **second** set, and only the first active set is reachable.

**Fix.** Ask the API what the server thinks:

```bash
curl -H "Authorization: Bearer <token>" \
  http://localhost:44311/api/v1/market/shops/<shopId>
```

Each set carries an `active` boolean and a `rotationLabel`. If `active` is false on a
`SCHEDULE` set, check the window against the **server machine's** clock and time zone —
not yours and not UTC. If `active` is false on a `MANUAL` set, flip `manualActive`.

For case 4: this is a real limitation, not a configuration mistake. `ShopViewScreen`
renders the set at index 0 and nothing in the interface changes that index, so put
everything a player should see in one set and let rotation decide which set that is.
Background in [the shop model](shop-model.md#shop).

---

## A card shows the wrong icon

**Cause.** `getEffectiveDisplayItem()` treats `minecraft:paper` as "unset" — not just a
blank string, the literal paper id too. An item whose `displayItem` is paper falls back
to the first output's icon.

**Fix.** Set `displayItem` to something other than paper. If you genuinely want a paper
icon for an item that outputs something else, there is no way to express it: pick another
paper-like item, or make paper the first output.

---

## A card shows a blank white square

**Cause.** A `cheeseitems:*` icon whose PNG did not arrive. The server logs it while
building the view:

```text
[Market]   my_item → NO image found
```

**Fix.** Either CheeseItems is not installed — check for
`[Market] CheeseItems detected` at first shop open — or that item definition has no
uploaded image in CheeseItems. Give it one, or point the shop item's `displayItem` at an
ordinary Minecraft item and keep the CheeseItems id only in `outputs`.

---

## A player was charged and received nothing

⚠️ This is the one failure mode worth setting up a log alert for.

**Cause.** Payment is taken before delivery, and two things can break in between.

Grep the server log:

```text
[Market] Item not in registry, skipping delivery: mymod:thing for player Steve
[Market] CRITICAL: giveOutputs failed for player Steve buying a1b2c3d4 x1 — payment was already taken!
```

The first line means a shop sells an item id that does not exist on this server, usually
after a mod was removed. The purchase completes "successfully": the player is charged,
told `Achat réussi`, and the transaction is recorded. The second means delivery threw;
the player at least gets `[Market] Erreur de livraison ! Contactez un admin pour un
remboursement.` in chat.

**Fix.** Refund by hand — the transaction entry in
`cheesemarket_transactions.json` carries the per-unit `costs` list and the `quantity`,
so multiply and give it back. Then fix the cause: audit every output id against the
server's registry after removing any mod. A quick sweep:

```bash
grep -o '"itemId":"[^"]*"' <world>/data/cheesemarket/shops/*.json | sort -u
```

Any namespace in that list whose mod is no longer installed is a trap waiting for a
customer.

---

## Items ended up on the ground instead of in the inventory

**Cause.** The space check simulates the 36 main inventory slots only, and treats
`cheeseitems:*` outputs as stacking to 64. When reality disagrees, `insertStack` fails
and the overflow is dropped at the player's feet rather than deleted.

**Fix.** Nothing to fix server-side — the items are not lost. Tell players to clear
space before buying in bulk. The lang key that would have warned them
(`cheesemarket.purchase.items_dropped`) exists and is not wired up; reviving it is a
three-line patch in `giveOutputs`.

---

## A global stock of 1 sold 64 units

**Cause.** `canPurchase` takes no quantity argument. It checks `remaining > 0`, passes,
and `recordPurchase` then clamps the counter at zero. The same applies to per-player
limits.

**Fix.** Treat stock as a soft cap. If an item must be strictly limited, price it so that
64 of it is unaffordable, or set the per-player limit *and* a cost that depletes.
Reasoning in [purchases](purchases.md#quantity-is-not-part-of-the-stock-check).

---

## A player cannot afford an item they clearly have money for

**Cause.** The cost's currency is not available. Each currency has one failure shape:

| Cost id | If its mod is missing |
|---|---|
| `cheesestore:fromage` | balance reads 0, so nothing is ever affordable |
| `numismatic-overhaul:*_coin` | the coin items do not exist, so the inventory count is 0 |
| any `cheeseitems:*` | matched by a `custom_data` tag, so a lookalike item without the tag does not count |

**Fix.** Check the hook verdicts in the log, logged once on first use:

```text
[Market] CheeseStore not found — fromage support disabled
[Market] Numismatic Overhaul not found — purse support disabled
```

If the mod *is* installed and the hook still says not found, read the next entry.

---

## An integration stopped working after updating CheeseStore, CheeseItems or Numismatic Overhaul

**Cause.** All three integrations resolve classes and methods by name through reflection.
A rename on the other side turns the feature off with a single warning and no further
complaint.

```text
[Market] Failed to initialize CheeseStore hook: <message>
[Market] Failed to initialize Numismatic Overhaul hook: <message>
[Market] Failed to initialize CheeseItems hook: <message>
[Market] Morpho catalog reflection unavailable: <message>
```

**Fix.** The message names the class or method that moved. Update the corresponding
`*Hook` class in `com.cheesemarket.server`. The expected signatures are listed in
[architecture](architecture.md#soft-dependencies).

---

## Uploaded background images never appear in game

**Cause.** Almost always the `level-name` mismatch. `AssetManager.init(worldDataDir)` is
never called, so the class falls back to `<config dir>/../world/data` while
`ShopManager` uses the real save path. With `level-name` set to anything but `world`,
images are written and read under a `world/` directory that holds nothing else.

**Fix.** Set `level-name=world`, or move the `assets/` directory to where
`AssetManager` is looking, or patch `CheeseMarket.onInitialize` to call
`AssetManager.init` with the same path it passes to `ShopManager.init`. Details in
[the layout editor](layout-editor.md#uploaded-images).

A second, milder cause: `GET /market/assets/:shopId/:name` is served with
`Cache-Control: public, max-age=3600`, so a replaced image can look unchanged in the
dashboard for an hour. That is a browser cache, not the game; the game receives the
bytes inline and is never stale.

---

## `reset-stock` says it worked and nothing changed

**Cause.** `POST …/items/:itemId/reset-stock` responds `{"reset":true}` and has no
implementation behind it.

**Fix.** Stop the server, edit `<world>/data/cheesemarket/cheesemarket_stock.json`,
start it again. Or edit it and run `/shop reload` before anyone buys anything — the next
purchase rewrites the file from memory and your edit would be lost. The file's shape is
in [configuration](configuration.md#the-stock-file).

---

## An edit to a shop JSON file had no effect

**Cause.** Shops are read at `SERVER_STARTED` and on `/shop reload`, and rewritten from
memory on every mutation. A live hand-edit is overwritten.

**Fix.** `/shop reload` — permission level 4. If the file still does not take, it failed
to parse and the shop was dropped from the map; standard error carries

```text
[CheeseMarket] Failed to load shop: .../starter.json → <message>
```

Validate the JSON. The annotated example in
[configuration](configuration.md#a-shop-file-annotated) carries comments for the
reader's benefit, and Gson rejects those.

---

## A command is refused

| What you see | Cause |
|---|---|
| vanilla *Unknown or incomplete command* | your permission level is below 2; the whole `/cmadmin` and `/shop` tree needs level 2 |
| the same, on `reload` or `delete` only | those two subcommands need level **4** |
| `Joueur requis` | you ran `/cmadmin` or `/shop open` from the console or a command block; both need a player source |
| `Shop introuvable : <id>` | wrong id — there is no tab completion on `shopId`, so check `/shop list` |

---

## The `market` module is missing from the dashboard

**Cause.** CheeseDash discovers modules through the `cheesedash` entrypoint at startup.
If CheeseMarket failed to load, loaded after CheeseDash gave up, or was built against a
CheeseDash too old to have `DashboardModule`, the module is absent.

**Fix.** Check that both jars loaded, then call the API directly:

```bash
curl -H "Authorization: Bearer <token>" http://localhost:44311/api/v1/modules
```

`market` should be in the list with the display name `CheeseMarket`. A `404` on
`/api/v1/market/stats` with `market` absent from `/modules` means the entrypoint never
ran — look for a CheeseMarket exception during startup. A `503`
`{"error":"Server not available"}` instead means the module loaded but
`onServerStart` has not fired yet; wait for the world to finish loading.

---

## A `READ_ONLY` dashboard account can see player names and prices

**Cause.** Not a bug, but worth knowing: CheeseDash's module dispatch allows any
authenticated session to `GET` a module route, and reserves `OPERATOR` for everything
else. Every market `GET` — shop definitions, prices, uploaded images, the full
transaction log with player names and UUIDs — is therefore readable by `READ_ONLY`.

**Fix.** Nothing in this mod. Do not give `READ_ONLY` to anyone who should not see sales
data. Per-route roles are in [the dashboard page](dashboard.md#required-roles).

---

## A player bought something without being able to open the shop

**Cause.** ⚠️ The client action channel checks neither the sender's permissions nor
their proximity to the shop, so a modified client can buy from a shop it was never shown.

**Fix.** There is no configuration switch for this. Do not run this version on a public
server, and watch the transaction log for sales from shops a player should not have
reached. A fix is on the way: see
[architecture](architecture.md#the-action-channel-is-unauthenticated).

---

## An English client sees French text in the shop

**Cause.** Several strings are hardcoded French literals in Java rather than lang keys:
the confirmation buttons `Confirmer` and `Fermer`, `Aucun article.`, the stock labels
(`Illimité`, `Quotidien`, `Par joueur`…), the delivery-failure message and the morpho
messages. The lang files themselves are complete for the keys that *are* used.

**Fix.** Not configurable. The keys are listed in
[architecture](architecture.md#unused-language-keys) if you want to move them into the
lang files.

---

## Reporting a bug

Open an issue at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) with:

1. **Which side the problem is on.** Did the panel fail to open (client), or did a
   purchase go wrong (server)?
2. **The server log**, `logs/latest.log`, filtered to the mod:
   ```bash
   grep -E "CheeseMarket|\[Market\]" logs/latest.log
   ```
   Include the startup block and everything around the moment it broke. The
   `[Market] CRITICAL` and `skipping delivery` lines are the important ones for a
   purchase problem.
3. **The client log** for an interface problem, `.minecraft/logs/latest.log`, filtered
   the same way.
4. **The shop definition** — `<world>/data/cheesemarket/shops/<shopId>.json`. It is
   small, readable and usually contains the answer.
5. **The transaction entry**, if a purchase is involved: the matching object from
   `cheesemarket_transactions.json`, which carries the exact costs and outputs.
6. **Which optional mods are installed** — CheeseStore, CheeseItems, Numismatic
   Overhaul — and their versions, plus the three hook verdict lines from the log.

Questions rather than bugs go to the [Arkensia Discord](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.md) · [CheeseMarket](../README.md) →
