[← CheeseStore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

**Server-side.** Every file in the jar runs on the server: the commands, the order
queue, the cosmetic managers, the HTTP routes. `fabric.mod.json` declares
`"environment": "*"` and only a `main` entrypoint, so a client can load the jar
without error, but it gains nothing from it. Players need no mod to buy, to spend
credits, or to use `/cosmetics`.

One exception, and it is a real one: **capes are rendered client-side** by a separate
companion mod, `factionwarhud`, which is not in this repository. Without it the cape
cosmetics sell and equip and nobody sees them. If you are not shipping that mod,
disable the cape category before opening your store.

---

## Prerequisites

| | Version | Where |
|---|---|---|
| Minecraft | 1.21.1 | — |
| Fabric Loader | ≥ 0.16.0 | [fabricmc.net](https://fabricmc.net/use/installer/) |
| Fabric API | 0.103.0+1.21.1 or later | [modrinth.com/mod/fabric-api](https://modrinth.com/mod/fabric-api) |
| Java | 21 | — |
| [CheeseDash](../../CheeseDash/) | matching build | this repository, **required** |
| [CheeseItems](../../CheeseItems/) | matching build | this repository, **required** |

Both sibling mods are hard dependencies in `fabric.mod.json`. Fabric refuses to start
without either, with a dependency error naming the missing id.

CheeseDash supplies the HTTP server; CheeseStore has none of its own, so without it
there is no storefront, no webhook endpoint and no admin panel. CheeseItems supplies
the morpho catalogue and codex that the website's morpho purchases read and write,
imported directly rather than through reflection.

`sgui` (by Patbox) is bundled inside the jar with Jar-in-Jar. Do not install it
separately.

And one you supply yourself: **a Stripe account**. Use test mode until a test purchase
has delivered end to end.

---

## Build order

Sibling dependencies resolve through a `fileTree` glob on each mod's
`build/libs/`, not a pinned filename, so both must be **built** before CheeseStore
compiles:

```bash
cd CheeseDash   && ./gradlew build
cd ../CheeseItems && ./gradlew build
cd ../CheeseStore && ./gradlew build
```

`tools/build-all.sh` at the repository root walks every mod in dependency order and
is the easier route if you are building the whole set.

A missing sibling jar shows up as a compile error on `com.cheesedash.*` or
`com.cheeseitems.morpho.*` imports, not as a friendly message — see
[troubleshooting](troubleshooting.md#the-build-fails-on-comcheesedash-or-comcheeseitems-imports).

The output is:

```text
CheeseStore/build/libs/cheesestore-1.0.0+1.21.1.jar
```

The version comes from `mod_version` in `gradle.properties`. Ignore
`-sources.jar`; it is not for the server.

## Install

Drop all three jars in the server's `mods/`:

```text
mods/
├── cheesedash-<version>+1.21.1.jar
├── cheeseitems-<version>+1.21.1.jar
├── cheesestore-1.0.0+1.21.1.jar
└── fabric-api-<version>.jar
```

Delete the old jar before copying a new one. Two versions of the same mod in `mods/`
is a startup failure, not a warning.

---

## What the first launch creates

Under `config/`:

| Path | Contents |
|---|---|
| `cheesestore.json` | all keys at their defaults, empty Stripe keys, empty `products` and `gallery` |
| `cheesestore_playcosmetics.json` | **63 seeded cosmetics**, titles and descriptions in French |
| `cheesestore_wallets.json` | not created until the first balance change |
| `cheesestore_deliveries.json` | not created until the first order |
| `cheesestore_ownership.json`, `cheesestore_equipped.json` | created on first grant / equip |
| `cheesestore_assets/` | created on the first image upload |

The seeded cosmetic catalogue is the one surprise: you get a full, enabled, priced
catalogue out of the box, in French, including 8 cape entries that need the client
mod to be visible. Review it before opening the store. Details in
[cosmetics and wallets](cosmetics.md#the-default-catalogue).

## Verifying it loaded

The log lines to look for, in order:

```text
[CheeseStore] Initializing...
[CheeseStore] No wallets file found, starting fresh
[CheeseStore] No cosmetic catalogue found — writing default catalogue
[CheeseStore] Default cosmetic catalogue written (63 entries)
[CheeseStore] Initialising cosmetics module...
[CheeseStore] TrailManager initialised (render every 2 ticks)
[CheeseStore] MountManager initialised (combat recall via AFTER_DAMAGE + hurtTime poll)
[CheeseStore] CosmeticsCommands registered (/cosmetics)
[CheeseStore] MountCommand registered (/mount, /monture)
[CheeseStore] Cosmetics module initialised
[CheeseStore] ⚠ storeBaseUrl is not configured! Checkout will be blocked. Set it in config/cheesestore.json or via CheeseDash admin.
[CheeseStore] Initialized successfully!
[CheeseStore] Server started
```

On later starts the first block reads `Config loaded (N products, N gallery items)`,
`Loaded N wallet(s)` and `Loaded N deliveries` instead.

That `storeBaseUrl` warning is **expected on a fresh install** and is the one line you
must act on before taking a payment. It is not noise: with no base URL, checkout is
blocked on purpose rather than sending a paying customer to a dead redirect.

Then check in game and in the browser:

```mcfunction
/premium
```

```text
🧀 Credits: 0
```

```mcfunction
/cosmetics
```

A chest menu titled *Cosmétiques* with eight category buttons. All counts read `0 /
N` — the catalogue exists, you own nothing.

And the HTTP side, with CheeseDash running:

```bash
curl http://localhost:44311/store/api/config
```

```json
{"storeName":"Arkensia","storeSubtitle":"Boutique Premium","stripePublishableKey":"","storeEnabled":true,"currencyName":"crédit","oauthEnabled":true}
```

If that returns `404`, CheeseDash did not pick up the module — the `cheesedash`
entrypoint is not being read. See
[troubleshooting](troubleshooting.md#the-store-module-is-missing-from-the-dashboard).

Visiting `http://localhost:44311/store/` serves the storefront page itself.

---

## Before you take a real payment

Working through [getting started](getting-started.md) covers this properly. The short
list:

1. Set `storeBaseUrl` to the public origin of your store, and serve it over **HTTPS**
   through a reverse proxy. Stripe will not send webhooks to plain HTTP on a live
   account, and the `store_session` cookie carries no `Secure` flag.
2. Paste your Stripe **test** keys, create a webhook endpoint pointing at
   `<storeBaseUrl>/store/api/webhook`, subscribe it to
   `checkout.session.completed`, and paste the endpoint's `whsec_…` into
   `stripeWebhookSecret`. With that field empty the webhook refuses to act at all.
3. Make one test purchase and confirm the commands ran. Not that the payment
   succeeded — that the *commands ran*.
4. Read [SECURITY.md](../../SECURITY.md).
5. Rewrite `store/cgv.html`. The terms shipped there belong to someone else's server.
6. Give nobody a CheeseDash account you would not trust with your Stripe keys, for the
   reason in [the dashboard API](dashboard.md#the-missing-role-guards).

---

## Uninstalling

Remove `cheesestore-*.jar` from `mods/` and restart. Nothing else is required —
there are no mixins, no registered blocks or items, and no world data.

What is left behind in `config/`:

```text
cheesestore.json                  Stripe keys, products, gallery — contains secrets
cheesestore_deliveries.json       order history
cheesestore_wallets.json          credit balances
cheesestore_playcosmetics.json    the cosmetic catalogue
cheesestore_cosmetics.json        the legacy catalogue
cheesestore_ownership.json        who owns what
cheesestore_equipped.json         who wears what
cheesestore_oauth_links.json      browser session → Minecraft UUID links
cheesestore_assets/               uploaded PNGs
```

Delete them to finish the removal. **Revoke your Stripe keys from the Stripe
dashboard as well** — deleting the file removes your copy, not Stripe's, and a key
that reached a backup is still live until you rotate it.

Three things in the world are not cleaned up by uninstalling, because the managers
that would despawn them are gone: summoned pets and mounts tagged `cos_pet` and
`cos_mount`, and any chat-tag or name-colour display name that was applied when the
server last shut down. Clear the entities with the tags before you remove the mod:

```mcfunction
/kill @e[tag=cos_pet]
/kill @e[tag=cos_mount]
```

Display names reset on the next login regardless, since they are not persisted to the
player file.

---

← [CheeseStore](../README.md) · [Getting started](getting-started.md) →
