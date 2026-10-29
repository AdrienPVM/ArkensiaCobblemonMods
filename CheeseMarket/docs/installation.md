[← CheeseMarket](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

CheeseMarket is **required on both sides**. The server owns the shop data and the
purchase logic; the client draws the full-screen interface. `fabric.mod.json` declares
`"environment": "*"` and a `client` entrypoint, and the shop panel is a client `Screen`
class. Install it server-side only and the server will cheerfully send `shop_open`
payloads that no client knows how to handle: nothing opens, and no error is printed.

---

## Prerequisites

| | |
|---|---|
| **Minecraft** | 1.21.1 |
| **Fabric Loader** | ≥ 0.15.11 (`fabricloader` in `depends`) |
| **Fabric API** | any version for 1.21.1 — [download](https://modrinth.com/mod/fabric-api) |
| **Java** | 21 or newer |
| **[CheeseDash](../../CheeseDash/)** | **required** — declared in `depends`, the mod will not load without it |

Optional, each detected at runtime by reflection:

| | |
|---|---|
| [CheeseStore](../../CheeseStore/) | lets items be priced in fromage (`cheesestore:fromage`) |
| [CheeseItems](../../CheeseItems/) | lets shops sell custom quest items with their own icons, and enables the morpho catalogue |
| [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) | lets items be priced in coins, paid from the player's purse |

CheeseStore and CheeseItems are listed under `suggests`. Numismatic Overhaul is not
listed at all, although the mod reflects into it by mod id — so Fabric will not warn you
if it is missing, and coin prices will be unpayable.

---

## Build order

CheeseMarket compiles against CheeseDash's jar, resolved by a glob rather than a fixed
filename:

```groovy
modCompileOnly files(fileTree(dir: "../CheeseDash/build/libs",
    includes: ["cheesedash-*.jar"], excludes: ["*-sources.jar", "*-dev.jar"]))
```

So **CheeseDash has to be built first**, or the glob matches nothing and the
`com.cheesedash` imports in `MarketApi` and `CheeseMarketDashboardModule` fail to
resolve.

```bash
cd CheeseDash   && ./gradlew build
cd ../CheeseMarket && ./gradlew build
```

`tools/build-all.sh` walks the whole repository in the right order if you would rather
not think about it.

The result is `CheeseMarket/build/libs/cheesemarket-1.0.0+1.21.1.jar`. The version comes
from `mod_version` in `gradle.properties`; if you change it, the jar name changes with
it. Ignore the `-sources.jar` next to it.

---

## Installing

Drop both jars in the server's `mods/`:

```text
mods/
├── cheesedash-1.0.0+1.21.1.jar
├── cheesemarket-1.0.0+1.21.1.jar
└── fabric-api-*.jar
```

Then the same `cheesemarket` jar — and Fabric API — in the client's `mods/`. The client
does not need CheeseDash.

---

## What the first launch creates

No configuration file. Nothing is written to `config/`. Everything lands in the world
save:

```text
<world>/data/cheesemarket/
├── shops/        created at SERVER_STARTED, empty
├── assets/       created on first dashboard use
└── templates/    created on first dashboard use
```

`cheesemarket_stock.json` and `cheesemarket_transactions.json` appear on the first
purchase, not before.

No shops are created by default. A fresh install has an empty shop list, and
`/shop list` answers `Aucun shop trouvé.`

⚠️ Keep `level-name=world` in `server.properties` unless you are prepared to patch the
mod. `ShopManager` resolves its directory from the real save path, but `AssetManager`
falls back to a hardcoded `world` because nothing calls its `init` method — so with a
different `level-name`, uploaded images go somewhere the rest of the mod never looks.
Details in [the layout editor](layout-editor.md#uploaded-images).

---

## Verifying it loaded

Server log, at startup:

```text
[CheeseMarket] Initialized.
```

That line is printed with `System.out.println` from `onInitialize`, so it may not carry
a logger prefix depending on your log configuration.

Then, once the world is up, from the `CheeseMarket` logger:

```text
[Market] Loading shops from .../world/data/cheesemarket/shops
```

and one line per shop found:

```text
[Market]   Loaded shop 'general': jsonHasLayout=true, parsedLayout=5 elements
```

On the client:

```text
[CheeseMarket] Client initialized.
```

Each optional integration logs its verdict the first time it is asked for, which is
usually when a shop view is first built rather than at startup:

```text
[Market] CheeseStore detected — fromage support enabled
[Market] Numismatic Overhaul not found — purse support disabled
[Market] CheeseItems detected — custom item icon support enabled
```

### In game

```mcfunction
/shop list
```

If the command exists and answers — even with `Aucun shop trouvé.` — the server side is
loaded. Then:

```mcfunction
/shop create test
/shop open test
```

An empty shop panel on a dark background means both sides are working. If the second
command prints nothing and nothing opens, the client is missing the mod.

### Dashboard

Open CheeseDash on port 44311 and look for **CheeseMarket 🛒** in the sidebar, or call
the API directly:

```bash
curl -H "Authorization: Bearer <token>" http://localhost:44311/api/v1/market/stats
```

```json
{"shopCount":1,"totalSets":0,"totalItems":0,"enabledItems":0,"transactionCount":0}
```

A `404` on that path means CheeseDash did not pick the module up — check that both jars
loaded and that CheeseDash logs the module at startup.

---

## Uninstalling

1. Remove `cheesemarket-*.jar` from the server's `mods/` and from every client's.
2. Restart.

What is left behind, in the world save:

```text
<world>/data/cheesemarket/     shops, stock, transactions, assets, templates
```

Nothing in `config/`. Nothing in the player NBT, nothing in the world's own data — the
mod registers no items, no blocks and no entities, so a world opened without it has no
missing-content warnings.

Fromage and coin balances belong to CheeseStore and Numismatic Overhaul and are
untouched by removing this mod. Items already sold stay in players' inventories.

Delete `<world>/data/cheesemarket/` to remove the shop definitions and the sales
history as well. That is irreversible, and it is the only place shops exist — keep a
copy if there is any chance you will reinstall.

---

← [CheeseMarket](../README.md) · [Getting started](getting-started.md) →
