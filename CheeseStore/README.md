# CheeseStore

> Premium web store with Stripe checkout, order management, wallets and in-game
> delivery.

A real storefront: a public web page where players buy cosmetics, mounts, ranks or
in-game currency with a card, a Stripe checkout flow, webhook-driven fulfilment, and
an admin panel in CheeseDash for products, orders, manual delivery and wallet
balances.

> ## ⚠️ This mod handles real money
>
> It is provided **as is, with no warranty**, as stated in the [LICENSE](../LICENSE).
> It has not been independently audited and makes no claim of PCI-DSS compliance.
> **You** are responsible for your Stripe keys, your compliance obligations, and what
> you expose to the internet. Read [SECURITY.md](../SECURITY.md) before taking a
> single payment.

**Minecraft 1.21.1 · Fabric · Java 21 · 32 files, ~7 100 lines**

---

## Requirements

- **CheeseDash** — required (admin panel)
- **CheeseItems** — required (morpho catalog and codex inter-mod calls)
- Fabric Loader ≥ 0.16.0, Fabric API, Java 21

`sgui` (by Patbox) is bundled inside the jar via Jar-in-Jar, so you do not install it
separately.

## Installation

Build CheeseDash and CheeseItems first, then drop all three in `mods/`.

## Configuration

The config file holds, among other things:

| Key | Value |
|-----|-------|
| `stripePublishableKey` | `pk_…` — safe to expose to the browser |
| `stripeSecretKey` | `sk_live_…` — **secret** |
| `stripeWebhookSecret` | `whsec_…` — **secret**, used to verify webhook signatures |

**Never commit this file, never include it in a server pack you distribute, and never
put it in a public backup.** The repository's `.gitignore` excludes `config/`, but a
pack built by hand will happily sweep it up.

Start with Stripe **test** keys (`sk_test_…`) and the Stripe CLI. Rotate immediately
from the Stripe dashboard if a key leaks.

## Public routes

The storefront and the Stripe webhook are reachable at `/store/api/*` — product
listing, checkout, verify, webhook. **These are public by design**: Stripe and your
buyers have to reach them.

Serve them over HTTPS through a reverse proxy, and do not expose the CheeseDash admin
port on the same opening. **Verify webhook signatures in production** — an
unauthenticated webhook endpoint lets anyone forge a "payment succeeded" event and
grant themselves goods for free.

## Dashboard

Registers under the `store` prefix: products (with image upload), orders, manual
delivery, wallets (read and set balance), and configuration.

## Commands

In-game commands cover cosmetics and mounts (equip, unequip, summon, dismiss), wallet
balances, and admin grants. The tree is in `commands/`.

## Arkensia specifics

`store/` contains the live storefront page as it ran for Arkensia, including its
`cgv.html` terms page. The terms are specific to that server's jurisdiction and
hosting arrangement — **rewrite them for yours**, do not ship them as-is.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators. You may run a monetised store with this; you may not sell the software
itself.
