[← CheeseStore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this walkthrough you will have made a real Stripe payment — in test
mode, with a test card — watched the webhook arrive, and seen the credits land in your
own wallet in game. That is the whole loop, and it is the only thing worth verifying
before you point a real card at it.

Everything here runs in Stripe **test mode**. No money moves. Do not switch to live
keys until the last step has worked at least once.

Plan on 30 to 45 minutes, mostly waiting on Stripe's dashboard.

---

## Step 1 — get it running

Follow [installation](installation.md) through to the log check: three jars in
`mods/`, server started, `[CheeseStore] Initialized successfully!` in the log.

Confirm the module is reachable:

```bash
curl http://localhost:44311/store/api/config
```

```json
{"storeName":"Arkensia","storeSubtitle":"Boutique Premium","stripePublishableKey":"","storeEnabled":true,"currencyName":"crédit","oauthEnabled":true}
```

A `404` here means CheeseDash is not loading the module; stop and fix that first
([why](troubleshooting.md#the-store-module-is-missing-from-the-dashboard)).

You will also see this warning in the log, and it is correct:

```text
[CheeseStore] ⚠ storeBaseUrl is not configured! Checkout will be blocked.
```

## Step 2 — log in to the dashboard

Open `http://localhost:44311/`, log in with the master token from
`config/cheesedash.json`, and find **CheeseStore** in the sidebar — the diamond icon,
labelled *Boutique premium et paiements*.

If it is not there, the mod loaded but CheeseDash did not register it. Again,
[troubleshooting](troubleshooting.md#the-store-module-is-missing-from-the-dashboard).

## Step 3 — set `storeBaseUrl`

For this walkthrough you need a URL Stripe can redirect a browser to. Localhost will
not do — the mod rejects it explicitly, because a buyer redirected to `localhost`
lands on their own machine.

If you have a domain pointed at the server, use it. Otherwise use a tunnel:

```bash
# one of many options; any HTTPS tunnel to port 44311 works
ngrok http 44311
```

Take the HTTPS URL it gives you and set it in the dashboard's store settings, or
directly in `config/cheesestore.json` with the server stopped:

```json
"storeBaseUrl": "https://a1b2c3d4.ngrok-free.app"
```

No trailing slash. The checkout return URL is built as `<storeBaseUrl>/store/`.

Restart if you edited the file by hand; a dashboard change takes effect immediately.
The warning should stop appearing on the next start.

## Step 4 — paste your Stripe test keys

In the Stripe dashboard, with the **Test mode** toggle on, open *Developers → API
keys* and copy:

- the publishable key, `pk_test_…`
- the secret key, `sk_test_…`

Paste both into the CheeseStore configuration panel and save. The response is
`{"ok":true}` and `refreshStripeClient()` rebuilds the Stripe client — no restart.

Reopen the panel and the secret key now reads `sk_test_5***1a2b`. That is the mask; it
is not what was saved. A value containing `***` is refused on write precisely so the
mask cannot overwrite the real key.

Verify the key took:

```bash
curl http://localhost:44311/store/api/config
```

`stripePublishableKey` should now be populated. The secret never appears on this
route.

## Step 5 — register the webhook

This is the step people skip, and it is the one that makes the whole thing work.

In Stripe, *Developers → Webhooks → Add endpoint*:

- **URL**: `<storeBaseUrl>/store/api/webhook`
- **Events**: `checkout.session.completed` — that one, nothing else is acted on

Stripe then shows a **signing secret**, `whsec_…`. Copy it into
`stripeWebhookSecret` in the dashboard and save.

Do not skip this field. With it empty, `POST /store/api/webhook` answers `503` and
refuses to process anything, deliberately — and
[without the signature check, anyone can forge a paid order](payments.md#why-the-signature-check-is-not-optional).

Stripe's *Send test webhook* button is a quick sanity check. A correct setup logs
nothing (the event type is ignored) and returns `200`. A `400 Invalid signature` means
you copied the wrong secret; a `503` means the field is still empty.

## Step 6 — create a product

In the dashboard's products view, add one:

| Field | Value |
|---|---|
| Name | `500 crédits` |
| Description | `De quoi s'offrir deux traînées.` |
| Price (cents) | `499` |
| Credits | `500` |
| Currency | `eur` |
| Category | `gems` |
| Enabled | yes |
| Commands | `premium give {player} 500` |

Two things to get right here.

**The price is in cents.** `499` is €4.99. Typing `4.99` charges four cents.

**`credits` grants nothing.** It is the number printed on the card. What the buyer
actually receives is the `commands` list, so the `500` in the badge and the `500` in
`premium give {player} 500` have to be kept in agreement by you — nothing checks.

Confirm it is public:

```bash
curl http://localhost:44311/store/api/products
```

```json
[{"id":"a3f91c02","name":"500 crédits","description":"De quoi s'offrir deux traînées.","priceCents":499,"credits":500,"currency":"eur","category":"gems","featured":false,"hasImage":false}]
```

Note what is **not** in that response: `commands` and `enabled`. The public shape
omits them, so your delivery commands are never exposed to buyers.

## Step 7 — test the command before anyone pays for it

Run the delivery command by hand, as the console will run it:

```mcfunction
/premium give <yourname> 500
```

```text
+500 credits given to <yourname>. New balance: 500
```

If that fails, so will every purchase of this product — and it will fail *after* the
money has been taken, as a `FAILED` order. Fix it now.

Then put yourself back to zero so the real test is unambiguous:

```mcfunction
/premium set <yourname> 0
```

## Step 8 — link your account

Open `<storeBaseUrl>/store/` in a browser. The storefront loads, your product card is
on it.

Ask the site for a pairing code:

```bash
curl -c cookies.txt http://localhost:44311/store/api/oauth/link-code
```

```json
{"code":"A1B2C3D4","expiresAt":1730000300000}
```

In the browser this is the sign-in affordance; the code has a 5-minute life. Redeem it
in game:

```mcfunction
/store link A1B2C3D4
```

```text
Store session linked to <yourname>. You can now see your balance and buy morphos from the website.
```

The site now knows which Minecraft account that browser session owns, which is what
the credit-spending routes require. The card checkout in the next step does **not**
need it — it takes the UUID from the request body — but linking lets the page show
your balance, and you need it for step 11.

## Step 9 — buy your own product with a test card

On the storefront, pick your player name and click through to checkout. The page posts
to `/store/api/checkout`, gets a `checkoutUrl` back, and sends you to Stripe's own
hosted payment page.

Pay with Stripe's test card:

```text
4242 4242 4242 4242    any future expiry    any CVC    any postcode
```

Stripe redirects you back to `<storeBaseUrl>/store/?session_id=cs_test_…`.

If checkout returns `503` instead, one of three things is wrong: `storeBaseUrl` is
empty or contains `localhost`, `stripeSecretKey` is empty, or the product is
disabled. The error body says which.

## Step 10 — watch it deliver

Two things should have happened by now, and they race each other on purpose.

In the server log:

```text
[CheeseStore] Delivery created: orderId=cs_test_a1b2..., player=<yourname>, product=500 crédits
[CheeseStore] Delivered orderId=cs_test_a1b2... to player=<yourname>
```

And in game:

```text
🧀 +500 credits received! New balance: 500
```

Confirm it:

```mcfunction
/premium
```

```text
🧀 Credits: 500
```

The order is now in the dashboard's orders view as `DELIVERED`, and
`GET /api/v1/store/stats` reports `deliveredOrders: 1`, `failedOrders: 0`.

Which path delivered it? Probably the redirect: the page calls
`/store/api/session-status`, which retrieves the session from Stripe and processes it
immediately. The webhook arrives a moment later, finds an order with that id already
exists, logs `Duplicate delivery attempt` and does nothing. Both paths are live, the
Stripe session id is the idempotency key, and whichever lands first wins. That is the
design, and seeing the duplicate line is confirmation it works.

**Now test the path that matters more.** Close the browser tab the instant Stripe
redirects, before the page can call `session-status`, and buy again. The webhook alone
should deliver. If it does not, your webhook endpoint is wrong, and you would have
been relying entirely on buyers not closing their tab.

Finally, test an offline delivery: have a second account buy while logged out. The
order stays `PENDING` until they next join, at which point:

```text
[CheeseStore] Delivered 1 pending purchase(s) to <name>
```

## Step 11 — spend the credits

The other half of the store. Pick a cosmetic from the seeded catalogue:

```bash
curl http://localhost:44311/store/api/playcosmetics | head -c 300
```

`trail_flame` costs 400. Buy it through the storefront's cosmetics page, or directly
with the session cookie from step 8:

```bash
curl -b cookies.txt -X POST \
  -H "Origin: http://localhost:44311" \
  http://localhost:44311/store/api/playcosmetics/trail_flame/buy
```

```json
{"success":true,"newBalance":100,"cosmeticId":"trail_flame"}
```

The `Origin` header matters: the route rejects a cross-origin POST with
`403 forbidden_origin`, because it spends money. `401 not_authenticated` means no
cookie, `403 not_linked` means step 8 did not complete.

Then, in game:

```mcfunction
/cosmetics
```

The *Traînées* button now reads `Possédés : 1 / 10`. Click it, click *Sillage de
Flammes*, and walk. Flame particles trail behind you.

```mcfunction
/premium
```

```text
🧀 Credits: 100
```

500 in, 400 out. The loop is closed: a card payment became credits, credits became a
cosmetic, and the cosmetic is on a player.

---

## Before you switch to live keys

The test loop working is necessary, not sufficient. Go through this, in order:

1. **Review the seeded catalogue.** 63 cosmetics are enabled and priced right now,
   with French titles, including 8 capes that are invisible without the
   `factionwarhud` client mod. Disable or rewrite what you are not actually selling.
2. **Serve the store over HTTPS** through a reverse proxy. Stripe will not deliver
   webhooks to plain HTTP on a live account, and the session cookie has no `Secure`
   flag.
3. **Rewrite `store/cgv.html`.** The terms of sale shipped there were written for one
   specific server, in one jurisdiction. Publishing them as your own is worse than
   having none.
4. **Read [SECURITY.md](../../SECURITY.md).**
5. **Treat every CheeseDash account as a full store operator.** A `READ_ONLY` session
   can set wallet balances and overwrite your Stripe keys —
   [why](dashboard.md#the-missing-role-guards).
6. **Decide how you will notice a `FAILED` order.** Nothing alerts you. The counter
   is in `GET /api/v1/store/stats`, and that is the whole mechanism.
7. **Swap test keys for live keys, and create a new webhook endpoint.** The signing
   secret is per-endpoint: a live endpoint has a different `whsec_…` than your test
   one, and pasting the old one means every live payment fails signature verification
   and nothing is delivered.

That last point catches people. Verify it with a €0.50 product and your own card
before you announce the store.

---

← [Installation](installation.md) · [Commands](commands.md) →
