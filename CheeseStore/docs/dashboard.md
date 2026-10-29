[← CheeseStore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

CheeseStore registers a CheeseDash module under the prefix **`store`** through the
`cheesedash` entrypoint in `fabric.mod.json`. `CheeseStoreDashboardModule` declares
two surfaces, and the distinction between them matters more here than in any other
mod in this repository:

| Surface | Served at | Who reaches it | Handler |
|---|---|---|---|
| **Admin** | `http://<host>:44311/api/v1/store/*` | an authenticated CheeseDash session | `StoreApi.handle` |
| **Public** | `http://<host>:44311/store/api/*` | anyone on the internet | `StoreApi.handlePublic` |

The public surface exists because it has to: a buyer's browser and Stripe's webhook
servers both need to reach it, and neither can hold a CheeseDash session token. It is
therefore unauthenticated **by design**, and it defends itself instead — per-IP rate
limiting, a same-origin check on anything that spends money, an OAuth session plus a
linked Minecraft account on every credit debit, and an HMAC signature on the webhook.
Those guards are described route by route below, and the payment path is traced in
[the payment flow](payments.md).

Both surfaces share the mod's one WebSocket channel, `store`, on port 44312.

> ⚠️ **The admin surface's role boundary is held by CheeseDash, not by CheeseStore.**
> It does hold — writes need `OPERATOR` — but read
> [what the core actually enforces](#what-the-core-actually-enforces) for what that
> buys you and what it does not. The public surface has no role boundary at all, by
> design; one of its routes leaks more than you may expect — see
> [`GET /players`](#catalogue-read-only).

---

## Authentication

Admin routes inherit CheeseDash's session auth: `POST /api/v1/auth/login` with the
master token returns a session token, which every later request sends as
`Authorization: Bearer <token>`. CheeseDash rejects an unauthenticated request before
`StoreApi.handle` is ever called.

Public routes send no Authorization header at all. The three credit-spending routes
use a different mechanism entirely: a session cookie issued by the website's
account-link flow, resolved through `GoogleOAuth.currentSession`, then matched to a
Minecraft UUID through `AccountLinker`. `currentSession` already returns nothing for a
cookie that has no link behind it, so **both** "no cookie" and "cookie but no completed
`/store link`" answer `401 not_authenticated`. The `403 not_linked` branch that follows
is only reachable if the link disappears between those two lookups (a logout racing the
purchase).

---

## What the core actually enforces

CheeseDash gives a module three helpers to gate a route by role:
`WebDashboard.requireWrite(exchange)` for OPERATOR-or-higher,
`WebDashboard.requireAdmin(exchange)` for SUPER_ADMIN, and `getSession(exchange)` to
inspect the caller. The correct shape is one line at the top of a handler:

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

**`StoreApi.handle` calls none of them.** Not once, anywhere in the file — the
strings `requireWrite`, `requireAdmin` and `getSession` do not appear in CheeseStore's
source at all. What stands in for them is CheeseDash's own module dispatcher, in
`DashboardApi`:

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
    ...
}
```

That is the correct form — it tests the return value and returns — so in this build
every non-`GET` request under `/api/v1/store/` does require `OPERATOR` or above, and
`GET` requests accept any role including `READ_ONLY`. A `READ_ONLY` session cannot
change a product price, set a wallet balance, re-deliver an order, or overwrite the
Stripe keys. The boundary holds.

What does **not** hold is the reason it holds. The protection is a property of the
core's dispatch, keyed purely on the HTTP method, and CheeseStore contributes nothing.
Three consequences:

- **No route in CheeseStore can require `SUPER_ADMIN`.** `PUT /config`, which writes
  the Stripe secret key and the webhook secret, is an `OPERATOR`-level operation,
  because `OPERATOR` is the only bar the dispatcher knows. That is the sharpest one:
  writing a new `stripeWebhookSecret` immediately invalidates signatures from the real
  Stripe endpoint, which silently stops all deliveries, and writing a new
  `stripeSecretKey` points checkout at a different Stripe account. Setting arbitrary
  wallet balances (`PUT /wallets/:uuid`) sits at the same level.
- **A mutating `GET` would be completely unguarded.** None exists today in
  `StoreApi.handle`, but nothing in the module would stop one from being added, and
  nothing would flag it.
- **A change to the dispatcher removes every check at once.** CheeseStore's admin
  surface has no second line of defence.

So an `OPERATOR` on this server is a full store operator — that is the intended
design, not an accident, but it is worth stating plainly before you hand out the role.
If you need a genuinely read-only viewer, `READ_ONLY` is the role to give them, and it
behaves as its name says.

The public routes sit outside all of this. They are unauthenticated by design and
never relied on role checks; they have their own guards — rate limits, same-origin,
OAuth plus a linked account, HMAC — and those guards are present. One of them,
`GET /players`, returns the full name-and-UUID roster to anyone who asks; see
[the catalogue routes](#catalogue-read-only).

---

## Admin routes

All paths below are relative to `/api/v1/store`. The role stated is the role actually
required in this build: every `GET` accepts any role, `READ_ONLY` included, and every
non-`GET` requires `OPERATOR` or above. The `(core)` mark is a reminder that the
guarantee comes from CheeseDash's dispatch and not from CheeseStore — see
[what the core actually enforces](#what-the-core-actually-enforces). The rule is
uniform across every table on this page, so only the first one carries the column.

### Products

| Method | Path | Role | Body | Response |
|---|---|---|---|---|
| `GET` | `/products` | any role | — | `200` array of full product objects |
| `POST` | `/products` | `OPERATOR` (core) | product object, optional `imageBase64` | `201` the created product |
| `PUT` | `/products/:id` | `OPERATOR` (core) | partial product, optional `imageBase64` | `200` the merged product |
| `DELETE` | `/products/:id` | `OPERATOR` (core) | — | `200 {"deleted":true}` |
| `POST` | `/products/:id/image` | `OPERATOR` (core) | `{"imageBase64":"..."}` | `200 {"ok":true}` |
| `GET` | `/products/:id/image` | any role | — | `200 image/png`, or `404` |

A product object, as returned by `StoreProduct.toJson()`:

```json
{
  "id": "a3f91c02",
  "name": "Starter pack",
  "description": "500 credits and a rank",
  "priceCents": 499,
  "credits": 500,
  "currency": "eur",
  "category": "pack",
  "enabled": true,
  "featured": false,
  "sortOrder": 0,
  "hasImage": true,
  "commands": ["premium give {player} 500", "lp user {player} parent add vip"]
}
```

`POST` accepts the same shape. Omitting `id` makes `StoreProduct`'s constructor
generate one: the first 8 characters of a random UUID. Omitting `enabled` defaults it
to `true`, so a product created without that field is immediately on sale.

`PUT` is a **merge**, through `StoreProduct.mergeFrom` — fields absent from the body
keep their current value. `id` is deliberately not mergeable: you cannot rename a
product's id, only delete and recreate it. `hasImage` is also not mergeable
except as a side effect of uploading an image.

`imageBase64` accepts either a bare base64 payload or a full `data:` URI; the prefix
up to the first comma is stripped. The bytes are written to
`config/cheesestore_assets/<id>.png` with **no validation that they are a PNG** and
no size cap. Deleting the product deletes the file.

| Status | When |
|---|---|
| `400 {"error":"Invalid ID"}` | the id contains `..`, `/`, `\`, `%`, or is 128+ characters |
| `404 {"error":"Product not found"}` | no product with that id |
| `500 {"error":"Internal server error"}` | anything thrown, including malformed JSON and bad base64 |

Every successful create and update broadcasts `store_update` on the `store` channel.

### Gallery

A read-only reward showcase: entries appear on the storefront but cannot be bought.
Managed by `GalleryItem`, whose shape is its own — `id`, `title`, `description`,
`priceCredits`, `hasImage`, `isNew`, `sortOrder`, `createdAt` — and is served
unchanged on the public route. `PUT` merges `title`, `description`, `priceCredits`,
`isNew` and `sortOrder` only.

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/gallery` | — | `200` array, sorted `isNew` first then by `sortOrder` |
| `POST` | `/gallery` | gallery item, optional `imageBase64` | `201` the created item |
| `POST` | `/gallery/reorder` | `{"ids":["a","b",...]}` | `200 {"ok":true}` |
| `PUT` | `/gallery/:id` | partial item | `200` the merged item |
| `DELETE` | `/gallery/:id` | — | `200 {"deleted":true}` |
| `POST` | `/gallery/:id/image` | `{"imageBase64":"..."}` | `200 {"ok":true}` |
| `GET` | `/gallery/:id/image` | — | `200 image/png` |

`POST /gallery` assigns `sortOrder = max(existing) + 1`, so a new entry lands at the
end. `/gallery/reorder` rewrites `sortOrder` from the array's index, ignoring ids it
cannot find rather than erroring — pass the complete list, not a partial one. Missing
`ids` → `400 {"error":"Missing ids"}`.

Gallery images live under `config/cheesestore_assets/gallery/<id>.png`.

### Orders

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/orders` | — | `200` array of deliveries, **newest first** |
| `POST` | `/orders/:id/deliver` | — | `200 {"delivered":true}` or `400` |

#### `POST /orders/:id/deliver`

Re-runs a delivery's stored commands on the server thread, through
`WebDashboard.executeOnServerThread`. The `:id` is the Stripe Checkout Session id.

```text
400 {"error":"Delivery failed or already delivered"}
```

That one message covers three different situations, which is awkward when you are
debugging: the order id does not exist, the order is already `DELIVERED`, or the
call that runs a command threw. The last one is rare — Minecraft's command manager
catches a failing command itself, so a typo still answers `200 {"delivered":true}`
(see [payments](payments.md#the-failure-that-actually-costs-you-money)). When it does
happen, `manualDeliver` logs nothing; it sets the order's `FAILED` status and
`failReason`, which `GET /orders` then shows.

The route works on `PENDING` and `FAILED` orders alike and does not check that the
buyer is online.

A `500` with the exception message means the server-thread hand-off itself failed,
not the commands.

An order object:

```json
{
  "orderId": "cs_live_a1...",
  "playerUUID": "...", "playerName": "Steve",
  "productId": "a3f91c02", "productName": "Starter pack",
  "priceCents": 499,
  "commands": ["premium give Steve 500"],
  "status": "PENDING",
  "createdAt": 1730000000000,
  "deliveredAt": 0
}
```

`failReason` is present only on an order that has one; it is omitted, not `null`,
otherwise. `status` is one of `PENDING`, `DELIVERED`, `FAILED`. `commands` are already resolved
— `{player}` was substituted when the order was created, so re-delivery runs the
commands as they were priced, not as the product reads today.

### Wallets

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/wallets` | — | `200` array of `{uuid, playerName, balance, lastUpdated}` |
| `GET` | `/wallets/:uuid` | — | `200` one wallet, or `404` |
| `PUT` | `/wallets/:uuid` | `{"balance":1000,"playerName":"Steve"}` | `200` the new state |

`PUT` **sets** an absolute balance, it does not add. A missing `balance` field is
read as `0`, so an empty body wipes the wallet — there is no "did you mean to" guard.
Negatives are clamped to zero by `WalletManager.setBalance`.

`playerName` is optional: left out or empty, the existing wallet's name is kept, and
for a wallet that does not exist yet the first 8 characters of the UUID are used as a
placeholder name. Pass the real name when creating a wallet from scratch.

If the balance changed and the player is online, they are messaged on the server
thread with `cheesestore.notify.received` or `cheesestore.notify.deducted` — the same
strings `/premium give` uses, so a dashboard adjustment is indistinguishable in chat
from a command one.

| Status | When |
|---|---|
| `400 {"error":"Invalid UUID"}` | fails the generic id check |
| `400 {"error":"Invalid UUID format"}` | not parseable by `UUID.fromString` |
| `404 {"error":"Wallet not found"}` | `GET` on a UUID with no wallet. `PUT` creates one instead |

### Stats

`GET /stats` → `200`:

```json
{
  "totalProducts": 12, "enabledProducts": 9,
  "totalOrders": 340, "pendingOrders": 2,
  "deliveredOrders": 337, "failedOrders": 1,
  "totalRevenueCents": 128400,
  "totalFromage": 54210, "walletCount": 188
}
```

`totalRevenueCents` sums only `DELIVERED` orders, so it is revenue you actually
honoured, not revenue Stripe collected. It does not subtract refunds — nothing here
knows about them. `failedOrders` only counts orders whose delivery call threw, which
almost never happens: a command that fails in the console is counted in
`deliveredOrders`. A zero there is not proof that every buyer received their goods; see
[the failure that costs money](payments.md#the-failure-that-actually-costs-you-money).

### Configuration

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/config` | — | `200` config with secrets masked |
| `PUT` | `/config` | any subset of the writable keys | `200 {"ok":true}` |

`GET` returns `stripePublishableKey` in full — it is meant to be public — and masks
`stripeSecretKey` and `stripeWebhookSecret` as `<first 8>***<last 4>`, or `***`
outright for anything under 12 characters. The Google OAuth keys are **not** exposed
by this route at all, in either direction: `GET /config` omits them and `PUT /config`
ignores them. They are editable only by hand in the file.

`PUT` accepts `stripePublishableKey`, `stripeSecretKey`, `stripeWebhookSecret`,
`storeName`, `storeSubtitle`, `storeBaseUrl`, `storeEnabled` and `currencyName`. A
secret whose value contains `***` is skipped rather than saved, which is what lets the
dashboard round-trip the masked form it was given without destroying the real key.
The implication is worth knowing: a genuine key containing `***` cannot be set through
this route.

After saving, `refreshStripeClient()` rebuilds the `StripeClient` — a new secret key
takes effect on the next checkout with no restart. Clearing the secret key sets the
client to `null` and checkout starts answering `503`.

### Legacy cosmetics (`CosmeticItem`)

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/cosmetics` | — | `200` array |
| `POST` | `/cosmetics` | cosmetic object | `200` the upserted object (**not** `201`) |
| `PUT` | `/cosmetics/:id` | partial | `200` merged |
| `DELETE` | `/cosmetics/:id` | — | `200 {"deleted":true}` |
| `POST` | `/cosmetics/:id/image` | `{"imageBase64":"..."}` | `200 {"ok":true}` |
| `GET` | `/cosmetics/:id/image` | — | `200 image/png` |

`POST` is an upsert keyed on `id`, so posting an existing id overwrites it silently
rather than refusing. These are the website-purchasable items priced in credits whose
effect is a list of `applyCommands`. They are a different catalogue from the playable
cosmetics below — [why there are two](cosmetics.md#two-catalogues-named-the-same-thing).

### Playable cosmetics (`CosmeticEntry`)

Served under `/playcosmetics` specifically to avoid colliding with the legacy
`/cosmetics` routes above.

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/playcosmetics` | — | `200 {"cosmetics":[...]}` |
| `POST` | `/playcosmetics` | entry with `id` and `category` | `201` the entry |
| `PUT` | `/playcosmetics/:id` | partial | `200` merged |
| `DELETE` | `/playcosmetics/:id` | — | `200 {"deleted":true}` |
| `POST` | `/playcosmetics/reorder` | `{"ids":[...]}` | `200 {"ok":true}` |
| `POST` | `/playcosmetics/grant` | `{"uuid":"..."}` or `{"player":"Name"}` plus `"id"` | `200 {ok, uuid, id}` |
| `GET` | `/playcosmetics/enums` | — | `200` dropdown source lists |

This is the only admin group that returns its list wrapped in an object
(`{"cosmetics":[...]}`) rather than a bare array. `POST` validates three things up
front: `id` present, `id` passing the path-safety check, and `category` valid against
`CosmeticEntry.Category` — each failure a distinct `400`. Like the legacy route it
replaces an entry with the same id rather than refusing.

`/playcosmetics/grant` resolves its target UUID-first: an explicit `uuid` wins, then a
`player` name is matched against online players, then against `usercache.json`
case-insensitively. Nothing resolvable → `400` naming both accepted forms. Unknown
cosmetic id → `404`.

`/playcosmetics/enums` returns curated vanilla-id lists so an admin never types a raw
Minecraft identifier: `categories` (8), `particles` (~42), `effects` (6 kinds),
`mobs` (~29, pets and rideable mounts), `colors` (the 16 chat colours), `iconItems`
(~65), `shapes` (`trail`, `halo`, `spiral`, `orbit`) and `sounds` (~15). These are
hard-coded in `buildEnumsJson()`, not read from the registries — a modded particle or
mob will not appear, and you have to edit the Java to add one.

If the cosmetic managers are not initialised, every route in this group answers
`503 {"error":"Cosmetic system not ready"}`.

---

## Public routes

All paths below are relative to `/store/api`. No Authorization header. The storefront
page itself is served from `/store/` out of the jar's `resources/store/`.

### Catalogue, read-only

| Method | Path | Guards | Response |
|---|---|---|---|
| `GET` | `/products` | `storeEnabled` | enabled products, `toPublicJson()` |
| `GET` | `/products/:id/image` | id validation | `image/png` with revalidation headers |
| `GET` | `/gallery` | `storeEnabled` | sorted gallery, public shape |
| `GET` | `/gallery/:id/image` | id validation | `image/png` |
| `GET` | `/cosmetics` | `storeEnabled` | enabled legacy cosmetics |
| `GET` | `/cosmetics/image?id=...` | id validation | `image/png` |
| `GET` | `/cosmetics/:id/image` | id validation | the same image, path form |
| `GET` | `/playcosmetics` | `storeEnabled` | enabled playable entries |
| `GET` | `/morpho/list` | `storeEnabled` | CheeseItems catalogue entries flagged for the website |
| `GET` | `/morpho/list/:id/image` | id validation | full-resolution PNG |
| `GET` | `/morpho/list/:id/thumb` | id validation | a ≤480px thumbnail, generated and cached on first request |
| `GET` | `/config` | — | `storeName`, `storeSubtitle`, `stripePublishableKey`, `storeEnabled`, `currencyName`, `oauthEnabled` |
| `GET` | `/players` | — | **every** known player name and UUID |

`storeEnabled: false` makes the guarded routes answer
`503 {"error":"Store is disabled"}`. That is the switch to use while you are setting
prices.

Two of these deserve a second look. `GET /config` publishes the publishable key,
which is correct — that key is designed for browsers — but confirm you have not
pasted a secret key into that field, because this route would hand it to anyone.
`GET /players` reads `usercache.json` and the online list and returns the lot,
unauthenticated and unpaginated: the full name-and-UUID roster of everyone who has
ever joined. The storefront needs it to populate its player picker. If you consider
that roster private, this route is the reason to keep the port behind a proxy that can
restrict it.

### Checkout and payment

| Method | Path | Guards | Response |
|---|---|---|---|
| `POST` | `/checkout` | same-origin, **8 per 60 s per IP**, Stripe configured, `storeBaseUrl` set | `{"checkoutUrl":"..."}` |
| `GET` | `/session-status?session_id=...` | none | `{"status":"complete"\|...}` |
| `POST` | `/webhook` | webhook secret set, HMAC-SHA256 signature, 300 s timestamp window | `{"received":true}` |

Full sequence, failure by failure, in [the payment flow](payments.md#the-sequence).

Put a request limit on `/store/api/session-status` at your reverse proxy. Each call
reads the session back from Stripe, so it counts against your Stripe API quota, and the
route is meant to be called a handful of times per purchase, not more.

### Credit purchases

| Method | Path | Guards | Spends |
|---|---|---|---|
| `POST` | `/cosmetics/buy` | same-origin, **10 per 60 s per IP**, OAuth session, linked account | credits → `applyCommands` |
| `POST` | `/morpho/buy` | same, **10 per 60 s** shared bucket | credits → CheeseItems codex entry |
| `POST` | `/playcosmetics/:id/buy` | same, same bucket | credits → ownership grant |

The three share one rate-limit bucket, keyed `buy:<ip>` — ten purchases a minute
across all three kinds, not ten each.

Each one runs the same five steps: same-origin check, rate limit, OAuth session,
linked account, catalogue lookup. Then the balance is checked on the HTTP thread and
checked **again** inside `executeOnServerThread`, because two simultaneous requests
would both pass the first check. That second check is what prevents a double debit;
the comments in the source call it the TOCTOU window and they are right to.

| Status | Error | Meaning |
|---|---|---|
| `403` | `forbidden_origin` | `Origin`/`Referer` did not match `Host` |
| `429` | `too_many_requests` | the per-IP bucket is full |
| `401` | `not_authenticated` | no session cookie, **or** a cookie with no completed `/store link` |
| `403` | `not_linked` | practically unreachable: the link vanished mid-request |
| `400` | `cosmeticId required` / `morphoId required` | missing or path-unsafe id |
| `404` | `cosmetic_not_found` / `morpho_not_found` | unknown, or disabled, or not flagged for the website |
| `409` | `already_owned` | morphos and playable cosmetics only; **no debit happened** |
| `402` | `insufficient_credits` | includes `currentBalance` and `required` in the body |
| `503` | `server_not_ready` | the Minecraft server is not up; nothing debited |
| `500` | `buy_failed` | the server-thread work threw for another reason |

`/cosmetics/buy` has **no** `already_owned` guard: legacy cosmetics are repeatable
purchases, and buying one twice debits twice and runs `applyCommands` twice. If a
cosmetic should be one-per-player, that has to be enforced inside its commands.

Success is `200 {"success":true,"newBalance":N,"<kind>Id":"..."}`. `/cosmetics/buy` and
`/playcosmetics/:id/buy` also broadcast a purchase event to the dashboard;
`/morpho/buy` broadcasts nothing.

### Web sessions and account linking

Paths under `/oauth/*` go to `GoogleOAuth.dispatch`, which despite the class name no
longer speaks to Google at all.

| Path | What it does |
|---|---|
| `/oauth/me` | issues or reads the `store_session` cookie; returns `sessionId`, and `linkedUuid`, `playerName`, `credits` once linked |
| `/oauth/link-code` | issues a pairing code, valid 5 minutes. **12 per 60 s per IP** |
| `/oauth/logout` | drops the link for that session and clears the cookie |
| `/oauth/start`, `/oauth/callback` | legacy. `302` to `/store/`, nothing else |

`/oauth/link-code` answers `{"alreadyLinked":true,"playerName":"..."}` instead of a
new code when the session is already bound. The mechanism, and what it does and does
not prove about the person spending credits, is in
[architecture](architecture.md#account-linking).

---

## WebSocket

One channel, `store`, on port 44312. Subscribe with
`{"type":"subscribe","channels":["store"]}` after the usual
`{"type":"auth","token":"..."}` handshake.

| Type | Fired by | Payload |
|---|---|---|
| `store_update` | product create / update | `action`, `product` (full object) |
| `store_update` | product delete | `action: "deleted"`, `productId` |
| `store_gallery_update` | gallery create / update / reorder / delete | `action`, `item` or `itemId`; `reordered` sends neither |
| `store_cosmetic_update` | legacy cosmetic upsert / update / delete | `action`, `cosmetic` or `cosmeticId` |
| `store_playcosmetic_update` | playable cosmetic create / update / reorder / delete | `action`, `cosmetic` or `cosmeticId` |
| `store_purchase` | a Stripe payment became an order | `playerName`, `productName`, `priceCents` |
| `store_cosmetic_purchase` | `/cosmetics/buy` succeeded | `playerName`, `cosmeticName`, `priceCredits` |
| `store_playcosmetic_purchase` | `/playcosmetics/:id/buy` succeeded | `playerName`, `cosmeticName`, `priceCredits` |

Two gaps to be aware of. `store_purchase` fires only when `createDelivery` returned
`true`, so the webhook arriving second for an order the verify path already created
is silent — correct behaviour, but it means the feed is a stream of distinct orders,
not of Stripe events. And **nothing broadcasts on a delivery failure**: a `FAILED`
order appears in `GET /orders`, but no WebSocket message announces it. A dashboard
watching only this channel will not notice.

Wallet changes do not broadcast either, from any path, and neither do a morpho
purchase nor a `/playcosmetics/grant`.

---

← [Cosmetics and wallets](cosmetics.md) · [Architecture](architecture.md) →
