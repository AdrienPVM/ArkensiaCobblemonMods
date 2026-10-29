# CheeseStore

> 🇫🇷 [Lire en français](README.fr.md)

> A web storefront with Stripe checkout, in-game delivery, a credit currency and a
> playable cosmetic catalogue.

> ## ⚠️ This mod takes real money
>
> It creates Stripe Checkout sessions, verifies webhooks and runs server commands on
> payment. It is provided **as is, with no warranty**, per the [LICENSE](../LICENSE).
> It has not been independently audited and claims no PCI-DSS compliance.
>
> **Neither the author nor this documentation guarantees that a store you run with
> this mod is legally or fiscally compliant anywhere.** Consumer-protection rules,
> VAT, invoicing, refund obligations and the right of withdrawal are yours to get
> right, and they differ by jurisdiction. The terms of sale shipped in
> `store/cgv.html` were written for one specific server and **must be rewritten by
> the operator** — publishing them as your own is worse than having none.
>
> Read [SECURITY.md](../SECURITY.md) before you take a single payment. Then read
> [the payment flow](docs/payments.md), which is the page that matters.

A buyer opens a web page, picks a pack, pays by card on Stripe's own hosted checkout,
and a command runs on your server. That is the whole product. The mod holds the
catalogue, creates the Stripe session, verifies the webhook signature, records the
order, and runs the delivery commands — retrying on the buyer's next login if they
were offline when they paid.

Alongside the card path there is a second economy that costs you nothing to run: a
credit balance per player, topped up by a product's delivery command, spent on a
catalogue of 99 playable cosmetics — particle trails, kill and death effects, chat
tags, name colours, pets, rideable mounts and capes. Players browse and equip them
from a chest menu with `/cosmetics`; one slot per category, and a summoned mount is
recalled automatically when a fight starts.

Admins never touch a command for any of this. Products, orders, manual re-delivery,
wallet balances and the Stripe keys all live in the CheeseDash panel.

**Minecraft 1.21.1 · Fabric · Java 21 · 32 source files, ~8 100 lines**

---

## Requirements

| | |
|---|---|
| **Required** | [CheeseDash](../CheeseDash/) · [CheeseItems](../CheeseItems/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Required for capes** | `factionwarhud`, on the client — not in this repository |
| **Related** | `factionwar` drives kill effects reflectively (death effects work without it) — not in this repository |
| **Bundled** | `sgui` by Patbox, Jar-in-Jar; no separate install |

Server-side. Players need no mod to buy, spend or equip — with the single exception
of capes, which are rendered by that client companion mod. Without it the 32
seeded cape cosmetics sell, equip, and remain invisible. Disable the category if you
are not shipping it.

Plus a Stripe account of your own. Use test keys until a test purchase has delivered
end to end.

## Install

```bash
cd CheeseDash   && ./gradlew build     # CheeseStore compiles against its jar
cd ../CheeseItems && ./gradlew build   # and against this one
cd ../CheeseStore && ./gradlew build
```

Drop `build/libs/cheesestore-1.0.0+1.21.1.jar` and both sibling jars in the server's
`mods/`. On first start the mod writes `config/cheesestore.json` with empty Stripe
keys, seeds a 99-entry cosmetic catalogue, and logs the one warning you must act on:

```text
[CheeseStore] Default cosmetic catalogue written (99 entries)
[CheeseStore] ⚠ storeBaseUrl is not configured! Checkout will be blocked.
[CheeseStore] Initialized successfully!
```

Checkout stays blocked until `storeBaseUrl` names a real public origin —
deliberately, because a buyer redirected to `localhost` after paying lands nowhere.

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, build order, what the first launch creates, the log lines that prove it loaded, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: from a blank server to a real test-mode payment that delivers credits to your own wallet |
| [Commands](docs/commands.md) | `/premium`, `/balance`, `/store link`, `/cosmetics`, `/mount` — every argument, permission level, output and failure message |
| [Configuration](docs/configuration.md) | `cheesestore.json` in full: Stripe keys, store settings, product fields, what applies live and what needs a restart, and which keys are dead |
| [The payment flow](docs/payments.md) | Checkout session to delivered command, step by step, with what happens when each step fails |
| [Cosmetics and wallets](docs/cosmetics.md) | The credit currency, the two cosmetic catalogues, the eight categories, ownership versus equipping |
| [Dashboard API](docs/dashboard.md) | Every REST route under `/api/v1/store` and `/store/api`, the admin/public split, real rate limits, WebSocket messages |
| [Architecture](docs/architecture.md) | Package layout, state ownership, threading and hand-off points, inter-mod coupling, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — starting with "a buyer paid and received nothing" |

## Commands at a glance

| Command | Level | What it does |
|---|---|---|
| `/premium` · `/balance` | any | your own credit balance |
| `/premium open` | any | a clickable link to the storefront |
| `/premium top` | any | the ten highest balances |
| `/premium cosmetics` | any | the legacy website cosmetic list |
| `/premium give\|take\|set <player> <amount>` | 2 | adjust a balance |
| `/premium balance <player>` | 2 | read someone else's balance |
| `/store link <code>` | any | bind your account to a web session |
| `/cosmetics` | any | the chest menu: browse and equip what you own |
| `/cosmetics list\|equip\|unequip` | any | the same, as text commands |
| `/cosmetics grant\|grantall\|revoke` | 2 | hand out ownership for testing |
| `/mount` · `/monture` | any | summon or dismiss your equipped mount |

No command creates a product, lists an order or touches a Stripe key. That is all
dashboard-only. Details in [commands](docs/commands.md).

## Dashboard

Registers under the `store` prefix, with two separate surfaces:

- **`/api/v1/store/*`** — authenticated. Products with image upload, a gallery,
  orders and manual re-delivery, wallets, both cosmetic catalogues, store statistics
  and the Stripe configuration.
- **`/store/api/*`** — public, unauthenticated by necessity, because buyers and
  Stripe's webhook servers both have to reach it. Defended instead by per-IP rate
  limiting, a same-origin check on anything that spends, and HMAC signature
  verification on the webhook.

> ⚠️ **The admin surface's role boundary is CheeseDash's, not CheeseStore's.**
> `StoreApi.handle` never calls `WebDashboard.requireWrite` or `requireAdmin`; the
> core's module dispatcher does it for every non-`GET` request, so writes do require
> `OPERATOR` and a `READ_ONLY` session really is read-only. The consequence is the
> ceiling: no route here can require `SUPER_ADMIN`, so an `OPERATOR` can change
> product prices and delivery commands, re-deliver orders, set any player's credit
> balance, and overwrite your Stripe secret and webhook secret.
> [The detail](docs/dashboard.md#what-the-core-actually-enforces).
>
> ⚠️ **`GET /store/api/players` is public.** It is served by `handlePublic`, with no
> authentication of any kind, and returns every known player name and UUID to anyone
> who can reach the port. If that roster is private to you, keep 44311 behind a proxy
> that restricts it.

## Honest limits

- **Tested in test mode, by its author, on one server.** The payment loop works; the
  edge cases around refunds, disputes and chargebacks are not handled at all, because
  the mod records what it delivered and nothing reconciles against Stripe.
- **A failed delivery is silent — and usually not even recorded as failed.** No
  alert, no retry. Minecraft's command manager swallows a command's error, so an order
  whose command failed still reads `DELIVERED`; `failedOrders` in
  `GET /api/v1/store/stats` almost never moves.
  [Details](docs/payments.md#the-failure-that-actually-costs-you-money).
- **A paying buyer's UUID and name are taken on trust** from the checkout request
  body, and the name is inserted into the delivery commands without validation: write
  product commands that stay safe with any name. Credit purchases are different —
  those resolve the account from a linked session.
- **Order history caps at 5 000 entries**, oldest dropped. Stripe's records are the
  authoritative ones.
- **Four config keys are dead** (`googleClientId`, `googleClientSecret`,
  `googleRedirectUri`, `oauthJwtSecret`) and the cosmetic subsystem's player-facing messages are
  hard-coded French. Both are listed in
  [architecture](docs/architecture.md#known-weaknesses), along with everything else I
  know is wrong with it.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run a monetised store with this — donations, ranks, a real
storefront; you may not sell the software itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
- Security issues → [SECURITY.md](../SECURITY.md). Please do not open a public issue
  for a vulnerability, and never paste a Stripe key into one.
