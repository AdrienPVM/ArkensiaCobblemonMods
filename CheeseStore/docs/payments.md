[← CheeseStore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](payments.fr.md)

# The payment flow, end to end

This is the page to read before you accept a single euro. It traces one purchase from
the moment a buyer clicks a product card to the moment a command runs on the server,
and it says what happens when each step fails. The step that matters most is the last
one: a payment can succeed at Stripe and still fail to deliver in Minecraft, and the
mod's design assumes that will happen sometimes.

> ⚠️ Nothing on this page is a compliance statement. The mod talks to the Stripe API
> correctly as far as the code goes, but neither the code nor this documentation
> guarantees that a store you run with it satisfies your consumer-protection, tax or
> accounting obligations. Read [SECURITY.md](../../SECURITY.md) too.

---

## The objects involved

| Object | Class | Lives in |
|---|---|---|
| Product | `StoreProduct` | `config/cheesestore.json`, under `products` |
| Stripe Checkout Session | — (a Stripe object) | Stripe's side; its `id` is the order id |
| Order / delivery record | `PendingDelivery` | `config/cheesestore_deliveries.json` |
| Wallet | `WalletManager.WalletEntry` | `config/cheesestore_wallets.json` |

A `StoreProduct` carries a price in cents (`priceCents`), a currency code
(`currency`, default `"eur"`), and a list of `commands`. The commands are what the
buyer actually receives: each one has `{player}` substituted with the buyer's
Minecraft name and is then run from the server console. The `credits` field is a
badge number shown on the storefront card; it does **not** credit a wallet by itself.
If you sell currency, the product's `commands` must contain the command that grants
it.

A `PendingDelivery` has three states, declared in `PendingDelivery.Status`:

| Status | Meaning |
|---|---|
| `PENDING` | paid, commands resolved, not yet executed |
| `DELIVERED` | every command was handed to the console without the call throwing |
| `FAILED` | the call that runs a command threw; `failReason` holds the exception message |

`FAILED` is close to unreachable in practice. Minecraft's command manager catches
parse errors and exceptions raised by the command itself, reports them to the
console, and returns normally — so an unknown command, a typo or a command that
crashes still leaves the order `DELIVERED`. See
[the failure that actually costs you money](#the-failure-that-actually-costs-you-money).

---

## The sequence

### 1. The buyer picks a product

The storefront JavaScript calls `GET /store/api/products`. That returns only enabled
products, sorted by `sortOrder`, through `StoreProduct.toPublicJson()` — which omits
`commands` and `enabled`, so the public catalogue never reveals what command a
purchase runs.

**If the store is switched off** (`storeEnabled: false`), this route answers `503`
with `{"error":"Store is disabled"}` and the page has nothing to show.

### 2. The browser requests a checkout session

```text
POST /store/api/checkout
{ "productId": "...", "playerUUID": "...", "playerName": "..." }
```

Five gates run before Stripe is contacted, in this order, in
`StoreApi.handleCheckout`:

1. **Same-origin check** (`checkSameOriginPost`). The request's `Origin` must match
   the `Host` header, or failing that its `Referer` must. A request with neither
   header is allowed through, because some privacy-strict browsers strip both on
   same-origin POSTs. Rejection → `403 {"error":"forbidden_origin"}`.
2. **Per-IP rate limit**: 8 checkout attempts per 60 seconds, keyed
   `checkout:<ip>`. Over that → `429 {"error":"too_many_requests"}`.
3. **Stripe configured?** If `stripeSecretKey` is empty, `CheeseStoreMod` never built
   a `StripeClient` and the route answers `503 {"error":"Stripe not configured"}`.
4. **Body complete?** Missing `productId`, `playerUUID` or `playerName` →
   `400`. Unknown or disabled product → `404 {"error":"Product not found or disabled"}`.
5. **`storeBaseUrl` sane?** Empty, or containing `localhost`, blocks checkout with a
   `503` naming the key. The mod also logs that condition as a warning at startup, so
   a misconfigured store is visible before anyone tries to buy.

`storeEnabled` is **not** one of the gates: with the store switched off, the catalogue
routes answer `503`, but a direct `POST /store/api/checkout` for an enabled product
still creates a Stripe session. Disable the products themselves, or clear
`stripeSecretKey`, if you need checkout to stop. Nor is `playerUUID` checked for format
at this stage (see [step 7](#7-delivery)).

Only then does `StripeClient.createCheckoutSession` POST to
`https://api.stripe.com/v1/checkout/sessions`, as a form-encoded body, authenticated
with the secret key as HTTP Basic (`<key>:` base64-encoded). There is no Stripe SDK;
the client is a few dozen lines over the JDK's `HttpClient`.

The session is created in `mode=payment` with one inline `price_data` line item built
from the product's name, currency and `priceCents`. Three metadata fields ride along:

```text
metadata[player_uuid]   metadata[player_name]   metadata[product_id]
```

Those three are the only link between a Stripe payment and a Minecraft account. The
webhook reads them back; nothing else identifies the buyer.

`success_url` is `<storeBaseUrl>/store/?session_id={CHECKOUT_SESSION_ID}` and
`cancel_url` is the same page without the parameter. Stripe hosts the payment page
itself, which is why the mod's own HTTP listener does not need TLS for the card form
— but see [the warning about HTTPS](#what-this-flow-does-not-protect-you-from).

A Stripe error (HTTP ≥ 400) comes back as `502` with Stripe's own message. A network
failure is caught and also returned as an error object. In both cases nothing was
created on either side.

### 3. The buyer pays on Stripe's page

The response to step 2 is `{"checkoutUrl":"https://checkout.stripe.com/..."}` and the
browser is sent there. From here until the redirect back, the mod is not involved.
The card details never touch your server.

### 4. The redirect back, and the verify call

Stripe returns the buyer to `success_url` with `session_id` in the query string. The
storefront then calls:

```text
GET /store/api/session-status?session_id=cs_live_...
```

`handleSessionStatus` retrieves the session straight from Stripe
(`GET /v1/checkout/sessions/<id>`) rather than trusting anything the browser said.
If the retrieved session's `status` is `"complete"`, it calls `processPayment` —
exactly what the webhook calls — and then answers `{"status":"<status>"}`.

This path exists because it is fast: the buyer sees their purchase land while the
page is still open. It is **not** the authoritative path. A buyer who closes the tab
mid-redirect never triggers it.

**Failures here:** no `session_id` → `400`; Stripe unreachable or the retrieve threw
→ `502 {"error":"Failed to retrieve session"}`; Stripe not configured → `503`. A
session id Stripe does not know is not an error on this side: Stripe's error body has
no `status`, so the route answers `200 {"status":""}`. None of these lose the payment,
because the webhook still arrives. The route has no rate limit.

### 5. The webhook — the authoritative path

```text
POST /store/api/webhook
Stripe-Signature: t=...,v1=...
```

Configure this URL as an endpoint in your Stripe dashboard, subscribed to
`checkout.session.completed`. `handleWebhook` does two things before parsing the
body as JSON:

1. **Refuses to run without a secret.** If `stripeWebhookSecret` is empty, the
   request is answered `503 {"error":"Webhook secret not configured"}` and an error
   is logged. Read [why this matters](#why-the-signature-check-is-not-optional).
2. **Verifies the signature.** `StripeClient.verifyWebhookSignature` parses the
   `Stripe-Signature` header for `t` and `v1`, rejects a timestamp more than 300
   seconds from now, recomputes `HMAC-SHA256(secret, "<t>.<raw body>")` as lowercase
   hex, and compares it to `v1` in constant time. Failure →
   `400 {"error":"Invalid signature"}` and a warning in the log.

Only `checkout.session.completed` is acted on. Every other event type is accepted
and ignored, so a broadly-subscribed endpoint is harmless — the endpoint always
answers `200 {"received":true}` once the signature checks out, which is what Stripe
needs to stop retrying.

### 6. `processPayment` creates the order

`processPayment` reads the three metadata fields and the session `id`:

- metadata missing a field → logged as an error, nothing created
- `product_id` no longer in the config → logged as an error, nothing created

In both cases the webhook still answers `200 {"received":true}`, so Stripe does not
retry: the payment is taken and no order exists in the mod. **Do not delete a product
while payments for it may still be in flight.** The session's `payment_status` is not
read either — any `checkout.session.completed` event, or a retrieved session whose
`status` is `"complete"`, creates the order.

Then `DeliveryManager.createDelivery` runs. **This is the idempotency boundary.** The
Stripe session id is the order id, and `createDelivery` returns `false` if a delivery
with that id already exists, logging `Duplicate delivery attempt for orderId=...`.
That is what keeps the verify path (step 4) and the webhook (step 5) from delivering
the same purchase twice — whichever arrives first wins, the second is a no-op.
The check-then-append is not atomic, though (`createDelivery` is not synchronized and
CheeseDash serves HTTP on a four-thread pool), so a verify call and a webhook landing
in the same instant could in principle both pass the check and create two orders.

A created delivery stores the **resolved** commands: `{player}` is substituted at
creation time, from the name in the Stripe metadata. Renaming the product's commands
afterwards does not change a delivery already in the queue.

The record is appended to `config/cheesestore_deliveries.json` immediately, and a
`store_purchase` message goes out on the `store` WebSocket channel.

### 7. Delivery

If the server is up, `processPayment` schedules `deliverPending` on the server thread
right away. `deliverPending` collects that player's `PENDING` deliveries and, **if
the player is online**, runs each command through
`server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd)` — that
is, from the console, with full permissions.

| Outcome | What is recorded |
|---|---|
| all commands were submitted | `DELIVERED`, `deliveredAt` stamped — even if a command errored in the console |
| the submitting call threw | `FAILED`, `failReason` = the exception message (rare, see above) |
| player offline | nothing; the delivery stays `PENDING` |

The file is only rewritten when at least one delivery in the batch succeeded, so a
`FAILED` status set on its own lives in memory and reverts to `PENDING` at the next
restart. A `playerUUID` that is not a valid UUID makes `deliverPending` throw before any
command runs; that order stays `PENDING` forever and only manual delivery can clear it.

A `PENDING` delivery is retried on every subsequent login: `CheeseStoreMod` hooks
`ServerPlayConnectionEvents.JOIN` and calls `deliverPending` for the joining player.
A buyer who paid while offline receives their purchase the next time they connect,
and the server logs `Delivered N pending purchase(s) to <name>`.

**`FAILED` is never retried automatically.** It sits in the orders list until an
admin presses the delivery button, which calls
`POST /api/v1/store/orders/<orderId>/deliver` and re-runs the stored commands
([manual delivery](dashboard.md#post-ordersiddeliver)). The same button works on a
`PENDING` order, and it does **not** check that the buyer is online: the commands run
immediately whatever their effect on an offline player. That endpoint refuses an order
already `DELIVERED`, so a double-click cannot double-grant — and an order recorded
`DELIVERED` whose commands did nothing cannot be re-delivered from the dashboard; run
the command by hand.

---

## The failure that actually costs you money

A paid order whose commands fail is the only state where the buyer is out of pocket
and has nothing. It happens for ordinary reasons: a typo in a product's command, a
mod that owned that command no longer being installed, a rank plugin that was
removed.

**The mod does not detect it.** The commands go through
`executeWithPrefix`, and Minecraft's command manager swallows both syntax errors
("Unknown or incomplete command") and exceptions thrown by the command, reporting them
to the console source instead of to the caller. The order is therefore recorded
`DELIVERED`, with no `failReason`, and `failedOrders` in `GET /api/v1/store/stats`
stays at `0`. The only trace is the command manager's own error in the server
console/log, next to `[CheeseStore] Delivered orderId=<id> to player=<name>`.

There is no alerting. What to do instead:

- Before enabling a product, run each of its commands by hand from the server console,
  with a real player name in place of `{player}`, and check the result in game.
- After a sale, read the console lines around `Delivered orderId=` for a command error.
- A non-zero `failedOrders` still means something went wrong, but a zero does not mean
  everything went right.

Since a `DELIVERED` order cannot be re-delivered from the dashboard, fix the command on
the product (for future buyers) and run the corrected command by hand for the buyer who
was missed.

---

## Why the signature check is not optional

The webhook URL is, by necessity, reachable by anyone on the internet — Stripe's
servers have to POST to it, and they do so from addresses you cannot enumerate in
advance. The signature is the only thing distinguishing Stripe from an attacker.

Without it, the request body is simply believed. An attacker who knows your store
exists would craft:

```json
{
  "type": "checkout.session.completed",
  "data": { "object": {
    "id": "forged-1",
    "metadata": { "player_uuid": "<their uuid>", "player_name": "<their name>",
                  "product_id": "<your most expensive product>" }
  }}
}
```

POST it, and `processPayment` would create a delivery and run the product's commands.
One request, any product, no payment. Repeat with a different `id` for each grant,
since the only duplicate check is on the order id.

That is why `handleWebhook` treats an empty `stripeWebhookSecret` as a hard failure
rather than a warning. There is no "insecure mode" to turn on. Also note what the
signature check buys you beyond authenticity: the 300-second timestamp window means
a genuine Stripe payload captured off the wire cannot be replayed hours later.

---

## What this flow does not protect you from

Read this part as a list of your responsibilities, not the mod's.

**The `playerUUID` is supplied by the browser.** Step 2 takes `playerUUID` and
`playerName` from the request body and does not verify that the person paying owns
that account. The storefront resolves names through
`GET /store/api/players` (which reads `usercache.json` plus the online list), but
nothing stops a hand-crafted POST from naming someone else's UUID. In practice that
means a buyer can gift a purchase to an arbitrary account — usually harmless, but it
also means you cannot treat a delivery record as proof of who paid. The credit-based
purchase routes (`/cosmetics/buy`, `/morpho/buy`, `/playcosmetics/{id}/buy`) are
different: those require an OAuth session **and** a linked Minecraft account, so they
know who is spending.

**No TLS of its own.** CheeseDash's listener speaks plain HTTP. Stripe hosts the card
page, so card data is never at risk, but your storefront's own traffic — session
cookies included — is in the clear unless you put a reverse proxy with a certificate
in front of it. Stripe will also refuse to send webhooks to a plain-HTTP endpoint on
a live account.

**`X-Forwarded-For` is trusted only behind a known proxy.** `publicClientIp` honours
the header only when the immediate peer is loopback or in an RFC1918 range
(`10.`, `192.168.`, `172.16`–`172.31`). If your proxy sits on a public address, every
request will be rate-limited against the proxy's single IP instead of the real
client's. Adjust `isTrustedProxy` in `StoreApi.java` if that is your topology.

**No refunds, no ledger, no invoices.** The mod records what it delivered. Refunds,
chargebacks, VAT and invoicing all live in your Stripe dashboard and your accounting,
and nothing in the mod reconciles against them. A refunded payment leaves its
delivery `DELIVERED` here.

**The terms of sale shipped in `store/cgv.html` are not yours.** They were written
for one specific server, in one jurisdiction, with one hosting arrangement. Replace
them.

---

← [Configuration](configuration.md) · [Cosmetics and wallets](cosmetics.md) →
