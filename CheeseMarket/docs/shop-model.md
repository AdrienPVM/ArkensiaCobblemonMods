[← CheeseMarket](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](shop-model.fr.md)

# The shop model

Three objects nest inside each other: a **shop** holds **sets**, a set holds **items**.
One JSON file per shop on disk, and that file is the whole definition — there is no
global catalogue and no cross-shop sharing. Copy an item into two shops and you have two
independent items that happen to look alike.

This page covers what each level stores, how a set decides whether it is open, how an
item's stock limit works, and how a price is actually paid.

---

## Where it lives

```text
<world>/data/cheesemarket/
├── shops/
│   ├── general.json              one file per shop, named after its id
│   └── metamorph.json
├── cheesemarket_stock.json       runtime counters, not part of the definitions
├── cheesemarket_transactions.json
├── assets/<shopId>/*.png         uploaded images for the layout
└── templates/*.json              saved layouts
```

The directory is resolved at `SERVER_STARTED` from
`server.getSavePath(WorldSavePath.ROOT).resolve("data")`, so `shops/` and the two
counter files follow your `level-name` rather than being hardcoded. `assets/` and
`templates/` do not: `AssetManager` is never initialised with that path and always uses
`./world/data/cheesemarket/` — see
[the layout editor](layout-editor.md#uploaded-images). Every save rewrites the whole shop file with
Gson pretty-printing, so the files stay readable and diffable.

---

## Shop

A shop is `ShopData`. The id comes from the `/shop create` argument or the dashboard and
becomes the file name; it is never editable afterwards.

| Field | Type | Default | What it does |
|---|---|---|---|
| `id` | string | — | identity and file name |
| `name` | string | `Nouveau shop` | title shown in the player interface |
| `description` | string | `""` | subtitle under the title |
| `displayMode` | `TABS` \| `FLAT` \| `FEATURED` | `TABS` | **not implemented** — see below |
| `showWelcomeMessage` | boolean | `false` | sent to the client, never rendered |
| `welcomeMessage` | string | `Bienvenue dans le shop !` | sent to the client, never rendered |
| `titleColor` | string | `gold` | a vanilla colour name or a hex triplet, used for the title |
| `bgColor` | string | `1A1A2E` | hex `RRGGBB`; sent to the client, never rendered |
| `borderColor` | string | `2A5A8A` | hex `RRGGBB`; sent to the client, never rendered |
| `accentColor` | string | `4AC8EA` | hex `RRGGBB`; sent to the client, never rendered |
| `btnBuyColor` | string | `1A5C2A` | meant for the confirm button background; never rendered |
| `btnBuyTextColor` | string | `FFFFFF` | meant for the confirm button text; never rendered |
| `btnCloseColor` | string | `1E3A5F` | meant for the cancel button background; never rendered |
| `btnCloseTextColor` | string | `CCCCCC` | meant for the cancel button text; never rendered |
| `layout` | object or absent | absent | element positions — see [the layout editor](layout-editor.md) |
| `morphoCatalog` | boolean | `false` | replaces the shop's own sets with the CheeseItems morpho catalogue at view time |
| `sets` | array | `[]` | the sets, in file order |

`displayMode` is parsed, stored, exposed in the API and sent to the client, and the
client never reads it. `ShopViewScreen` keeps a `displayMode` field that no rendering
code consults. In practice every shop behaves like a single-set shop:
`ShopViewScreen` renders the set at index `tabIdx`, `tabIdx` starts at zero, and nothing
in the stock interface ever changes it. A layout `BUTTON` cannot change it either — the
only custom button action the client understands is `close`.

**So in the player interface, only the first currently-active set is reachable.** Design
your shops accordingly: one active set at a time, with rotation deciding which one that
is. The second active set is loaded, validated, purchasable through a crafted payload,
and invisible.

`morphoCatalog` is a different beast and is described in
[purchases](purchases.md#the-morpho-catalog-path).

---

## Set

A set is `ShopSet` — a named group of items with one rotation rule. Its id is a random
8-character hex fragment generated on creation.

| Field | Type | Default | What it does |
|---|---|---|---|
| `id` | string | random 8 chars | identity inside the shop |
| `name` | string | `Nouveau set` | shown wherever a set is listed |
| `rotation` | object | see below | when the set is active |
| `items` | array | `[]` | the items |
| `sortOrder` | int | index at creation | ordering; `getActiveSets()` sorts on it |

`getActiveSets()` filters on `rotation.isCurrentlyActive()` and sorts by `sortOrder`,
and `getEnabledItems()` filters on `enabled` and sorts by `sortOrder`. Those two methods
are what the player ever sees; the dashboard sees the unfiltered lists.

### Rotation

| Key | Type | Default | What it does |
|---|---|---|---|
| `mode` | `ALWAYS` \| `SCHEDULE` \| `MANUAL` | `ALWAYS` | which rule applies |
| `scheduleStart` | `HH:mm` | `08:00` | window opening, `SCHEDULE` only |
| `scheduleEnd` | `HH:mm` | `20:00` | window closing, `SCHEDULE` only |
| `manualActive` | boolean | `true` | the switch, `MANUAL` only |

`SCHEDULE` compares `LocalTime.now()` — the **server machine's wall clock and time
zone**, not Minecraft time and not UTC. A window whose end is before its start is read
as crossing midnight: `22:00` → `04:00` is active from ten at night until four in the
morning. If either time fails to parse, `isInScheduleWindow` catches the exception and
returns `true`, so a typo makes the set permanently open rather than permanently closed.

`MANUAL` is flipped from the dashboard (`PUT` on the set with
`rotation.manualActive`) or from the in-game admin screen. An unknown `mode` string
falls back to `ALWAYS`.

The check runs on every view build and every purchase, so a set closes for a player
with their shop still open: the window closes, the next purchase answers
`This set is not active right now.`

---

## Item

A `ShopItem` is one card in the grid. Its id is also a random 8-character fragment.

| Field | Type | Default | What it does |
|---|---|---|---|
| `id` | string | random 8 chars | identity inside the set |
| `displayItem` | string | `minecraft:paper` | the icon |
| `displayName` | string | `""` | name override; empty falls back to the icon id |
| `outputs` | array of `ItemEntry` | `[]` | what the player receives |
| `costs` | array of `ItemEntry` | `[]` | what the player pays |
| `commands` | array of `CommandEntry` | `[]` | commands run on purchase |
| `stock` | object | `UNLIMITED` | the limit — see below |
| `enabled` | boolean | `true` | false hides it from players, keeps it in the dashboard |
| `sortOrder` | int | index at creation | position in the grid |

The icon the player sees is chosen by the client (`ShopViewScreen.effectiveIcon`), in
this order: the first output, **if it carries `components`** — it then wins over
`displayItem`, so the card shows the exact stack being sold; otherwise `displayItem` if
it is not blank, paper included; otherwise the first output's id. `ShopItem` also has a
`getEffectiveDisplayItem()` that treats `minecraft:paper` as "unset", but nothing calls
it. In practice: an item created through the API or the in-game editor without a
`displayItem` keeps the default `minecraft:paper` and shows paper; the dashboard editor
avoids that by filling a blank icon field with the first output's id when you save.

`getEffectiveName()` returns `displayName` when set, otherwise the icon id with its
namespace stripped and underscores turned into spaces (`minecraft:diamond_sword` →
`diamond sword`). The client improves on this: a blank `displayName` makes it look up the
vanilla translated name of the icon instead.

### ItemEntry

An entry is `{ itemId, count }` with an optional `components` string.

| Field | Type | What it does |
|---|---|---|
| `itemId` | string | a registry id, or one of the two virtual ids below |
| `count` | int | forced to at least 1 on both construction and `setCount` |
| `components` | string, optional | a MC 1.21.1 component bracket block, e.g. `[custom_data={quest:1}]` |

An entry with `components` is delivered through the vanilla `give` command rather than
by direct insertion, which is how a shop can sell an item with NBT it could not
otherwise construct. Components are only honoured on **outputs**: payment matching
compares item types and never looks at `components`, so an NBT-bearing cost is paid with
any plain stack of the same item.

### CommandEntry

`{ command, count }`. `{player}` in the string is replaced by the buyer's name, a leading
`/` is stripped, and the command runs `count × quantity` times from the console command
source. Details and failure handling in
[purchases](purchases.md#step-5--purchase-commands).

---

## Stock and limits

`StockConfig` lives on the item; the counters live separately in
`cheesemarket_stock.json`, keyed by string so they survive a shop being edited.

| Key | Type | Default | What it does |
|---|---|---|---|
| `mode` | `UNLIMITED` \| `GLOBAL` \| `PER_PLAYER` \| `PER_PLAYER_RESET` | `UNLIMITED` | which counter applies |
| `globalStock` | int | `100` | total units for the whole server, `GLOBAL` only; setter forces ≥ 1 |
| `perPlayerLimit` | int | `1` | units per player, the two `PER_PLAYER` modes; setter forces ≥ 1 |
| `resetType` | `DAILY` \| `WEEKLY` \| `MONTHLY` \| `CUSTOM` | `DAILY` | reset cadence, `PER_PLAYER_RESET` only |
| `resetIntervalSeconds` | long | `86400` | `CUSTOM` cadence; setter forces ≥ 60 |

The counters are keyed `shopId:setId:itemId` for global stock and
`shopId:setId:itemId:playerUuid` for per-player counts. Change an item's id — which the
dashboard never does, but a hand-edited file might — and its history is orphaned, not
migrated.

### How the reset actually works

`DAILY`, `WEEKLY` and `MONTHLY` are **not** calendar resets, despite what `StockConfig`'s
own class comment says ("resets at midnight server time", "every Monday midnight",
"on the 1st of each month"). `processResets` compares `now - lastReset` against a fixed
interval:

| `resetType` | Interval used |
|---|---|
| `DAILY` | 86 400 s |
| `WEEKLY` | 604 800 s |
| `MONTHLY` | 2 592 000 s (30 days) |
| `CUSTOM` | `resetIntervalSeconds` |

So a quota first bought at 17:40 resets at 17:40, and a `MONTHLY` quota drifts a day or
two per month. The code is the truth here; the comment is aspirational.

The reset is also **lazy**. `processResets` only runs when someone calls `canPurchase`
or `stockDisplay` for that exact item. An item nobody looks at never resets, and the
first player to open the shop after the interval is the one who triggers the wipe for
everybody. `lastReset` starts at 0, which is 1970, so the first access always resets and
sets the real timestamp.

When a reset fires, every per-player key with the prefix
`shopId:setId:itemId:` is removed from the map.

### Resetting by hand

`POST /api/v1/market/shops/:shopId/sets/:setId/items/:itemId/reset-stock` responds
`{"reset":true}` and **does nothing at all** — the handler has no body. To actually
clear a counter, stop the server, edit `cheesemarket_stock.json`, start it again. The
file is read once at load and written on every purchase, so editing it live gets
overwritten.

### What the player sees

`stockDisplay` builds a stock line for each item and the server sends it in the
`SHOP_VIEW` payload — but **the client never draws it**, so a player sees no stock figure
at all, only a greyed-out card once `canBuy` is false. The strings it would show:

| Mode | Shown |
|---|---|
| `UNLIMITED` | `∞` |
| `GLOBAL` | `remaining/total`, e.g. `42/100` |
| `PER_PLAYER` | `remaining/limit (vous)` |
| `PER_PLAYER_RESET` | `remaining/limit (<reset label>)`, e.g. `2/3 (Quotidien)` |

Those labels come from `StockConfig.resetLabel()` and are hardcoded French in the Java
source, not in a lang file.

---

## How a price is paid

An item's price is its `costs` list, every entry multiplied by the purchase quantity.
There is no price field and no currency setting: the currency *is* the item id you put
in the cost list. Three kinds exist, and each is settled differently.

### Ordinary items

Any registry id that is not one of the special cases below is counted and consumed from
the player's inventory by item type, ignoring components and NBT. A `cheeseitems:*` cost
is the exception: it is matched by scanning each stack's `custom_data` for a
`cheeseitems` string equal to the id after the namespace.

### Numismatic Overhaul coins

`numismatic-overhaul:bronze_coin`, `silver_coin` and `gold_coin` are recognised by
`NumismaticHook`. When Numismatic Overhaul is loaded, these are paid **inventory first,
then purse**: the hook counts loose coins in the inventory, takes those, converts the
shortfall to bronze (bronze 1, silver 100, gold 10 000) and deducts it from the purse
component by reflection.

When Numismatic Overhaul is **not** loaded, `isAvailable()` is false and coin ids fall
back to being ordinary inventory items — which means they cannot be found at all, since
the items themselves do not exist, and the purchase fails on insufficient funds. The hook
logs its decision once at first use:

```text
[Market] Numismatic Overhaul detected — purse support enabled
[Market] Numismatic Overhaul not found — purse support disabled
```

The hook reaches `com.glisco.numismaticoverhaul.ModComponents.CURRENCY` and
`CurrencyComponent.getValue()` / `modify(long)` by name. That is an observation of the
current Numismatic Overhaul, not a contract: a rename there turns purse support off
with one warning line, and purchases then need the coins loose in the inventory.

### Fromage (CheeseStore credits)

The id `cheesestore:fromage` is a virtual currency, not an item. `CheeseStoreHook`
reflects into `com.cheesestore.WalletManager` for `getBalance(UUID)` and
`deduct(UUID, String, long)`. In the interface it renders with its own cheese texture
and is labelled `Crédit`.

With CheeseStore absent, `getBalance` returns 0, so any fromage cost makes the item
unaffordable. The mod does not hide the item or warn the admin; the player just cannot
buy it.

```text
[Market] CheeseStore detected — fromage support enabled
[Market] CheeseStore not found — fromage support disabled
```

### Free items

An empty `costs` list is legal. `deductCosts` has nothing to check and nothing to
deduct, returns true, and the player receives the outputs for nothing. That is how you
build a reward dispenser gated only by a per-player limit.

---

← [Configuration](configuration.md) · [Player interface](player-interface.md) →
