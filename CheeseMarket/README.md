# CheeseMarket

> 🇫🇷 [Lire en français](README.fr.md)

> Player-facing shops with a full-screen in-game interface, authored from the dashboard
> down to the pixel.

A shop here is not a chest with items in it. A player opens a 350 × 250 panel with its
own background, its own grid of cards, a confirmation dialog with a quantity slider from
1 to 64, and real vanilla tooltips on every item — the server pre-renders those and ships
them with the shop data, so an enchanted book shows its enchantments exactly as it would
in an inventory.

Admins never touch a config file. Shops, sets, items, prices, stock limits and the visual
arrangement are all authored in CheeseDash, and the layout tab is a genuine editor:
drag elements on a canvas the size of the shop texture, snap to a grid, undo thirty
steps, upload a background PNG, save the arrangement as a reusable template.

There is no price field anywhere, because the currency *is* the item id you put in the
cost list. Emeralds, a custom quest item, Numismatic Overhaul coins paid from the
player's purse, or CheeseStore credits — same field, different id. An item can also run
server commands on purchase, which is how a shop sells a rank or a permission rather
than a stack.

**Minecraft 1.21.1 · Fabric · Java 21 · 109 source files, ~25 800 lines**

⚠️ Only 23 of those files are CheeseMarket (~6 900 lines). The other 86 are a
duplicated, dead copy of `com.cheeseguard` — compiled into the jar and never executed.
It is documented in
[architecture](docs/architecture.md#the-duplicated-comcheeseguard-package) and
deliberately left in place.

---

## Requirements

| | |
|---|---|
| **Required** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.15.11 · Fabric API · Java 21 |
| **Required on both sides** | the same jar on the server **and** on every client — the interface is a client screen |
| **Optional** | [CheeseStore](../CheeseStore/) for credit prices · [CheeseItems](../CheeseItems/) for custom item icons and the morpho catalogue · [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) for coin prices paid from the purse |

All three optional integrations are reached by reflection, so the jar loads without
them. Each logs one line saying whether it found its target, and the matching feature
switches itself off quietly if it did not.

## Install

```bash
cd CheeseDash      && ./gradlew build     # CheeseMarket compiles against its jar
cd ../CheeseMarket && ./gradlew build
```

Drop `build/libs/cheesemarket-1.0.0+1.21.1.jar` and the CheeseDash jar in the server's
`mods/`, and the CheeseMarket jar in the client's. There is no config file: the first
launch creates `<world>/data/cheesemarket/shops/` and logs

```text
[CheeseMarket] Initialized.
[Market] Loading shops from .../world/data/cheesemarket/shops
```

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, build order, why both sides need the jar, what the first launch creates, how to verify, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: from an empty server to a player paying five emeralds for a pickaxe, with the sale visible in the dashboard |
| [Commands](docs/commands.md) | `/cmadmin` and the five `/shop` subcommands, argument by argument, with permission levels, output and failure messages |
| [Configuration](docs/configuration.md) | There is no config file — what exists instead, where it lives, what is safe to edit live, plus a fully annotated shop file |
| [The shop model](docs/shop-model.md) | Shop, set and item: every field, how rotation decides a set is open, how stock limits and resets really work, how a price is paid |
| [Player interface](docs/player-interface.md) | What a buyer sees and does, written from their side — and an honest list of what the interface does not do |
| [Layout editor](docs/layout-editor.md) | The five element types, the item card template, the confirmation dialog, uploaded images, templates, and what is validated |
| [Purchases](docs/purchases.md) | The purchase pipeline step by step, what is recorded and where, and the three ways a purchase ends with a charged player and no goods |
| [Dashboard API](docs/dashboard.md) | Every route under `/api/v1/market`, request and response shapes, the role each one needs, and the `market_purchase` WebSocket message |
| [Architecture](docs/architecture.md) | Package layout, class responsibilities, threading, the string-splitting protocol, the dead `com.cheeseguard` package, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — including the log lines that mean a player was charged for nothing |

## Commands at a glance

| Command | Level | What it does |
|---|---|---|
| `/cmadmin` | 2 | opens the in-game admin editor |
| `/shop open <shopId>` | 2 | opens a shop for **yourself** |
| `/shop list` | 2 | every loaded shop with its set count |
| `/shop create <shopId>` | 2 | creates an empty shop and its JSON file |
| `/shop reload` | **4** | re-reads shops, stock and transactions from disk |
| `/shop delete <shopId>` | **4** | deletes the shop and its file, no confirmation |

There is no player command. Players reach a shop because an NPC, a quest or a command
opened it for them. And `/shop open` has no target-player argument: it opens the shop
for whoever ran it.

An in-game admin editor does exist, behind `/cmadmin` — this README used to say it did
not. The dashboard is the better tool and the one that gets maintained, but the in-game
screen is there and works.

## Dashboard

Registers under the `market` prefix, so everything lives at `/api/v1/market/*`: shop,
set and item CRUD, the layout editor's own routes, PNG asset upload, layout templates,
the transaction log and statistics. `GET` is readable by any authenticated session;
every other method needs `OPERATOR` — a rule enforced by CheeseDash's own module
dispatch, not by this mod, which never calls `requireWrite` itself. No market route can
require `SUPER_ADMIN`, because `OPERATOR` is the only bar that dispatch knows: deleting
a shop is an operator action. See
[the dashboard page](docs/dashboard.md#required-roles) for what that does and does not
guarantee.

A `market_purchase` message goes out on the `market` WebSocket channel after every
successful purchase, and CheeseDash forwards it to Discord if a webhook is configured.

## Honest warnings

⚠️ **The action channel has no permission check.** Every client action — including
`shop_create`, `shop_delete` and `item_save` — reaches the handler with no verification
that the sender is an operator, and `shop_buy` is accepted for any shop id without
checking that the shop was ever opened for that player. If your server has untrusted
players with modified clients, read
[the architecture note](docs/architecture.md#the-action-channel-is-unauthenticated)
before deploying this.

⚠️ **Payment is not atomic with delivery.** Costs are taken before outputs are handed
over. An output whose item id is not in the registry is skipped with a log line while
the purchase still reports success, and a delivery exception leaves the player charged
with a chat message telling them to find an admin. Both cases are refundable by hand
from the transaction log.
[What the code does in each case](docs/purchases.md#step-4--delivery).

Three features are configurable and not implemented: the `TABS`/`FLAT`/`FEATURED`
display modes, the welcome message, and the `IMAGE` layout element. The missing tab bar
has a visible consequence — **only the first active set of a shop is reachable in
game** — so build one active set at a time and let rotation choose which.

This mod ran on a live server with hundreds of players, so the purchase path is tested
in anger. The layout editor and the morpho catalogue are newer and have seen less.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may
not sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
