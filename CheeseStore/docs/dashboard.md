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

> ⚠️ **The admin surface has no role enforcement.** Read
> [the missing role guards](#the-missing-role-guards) before you create a
> `READ_ONLY` account and assume it is read-only. It is not.

---

## Authentication

Admin routes inherit CheeseDash's session auth: `POST /api/v1/auth/login` with the
master token returns a session token, which every later request sends as
`Authorization: Bearer <token>`. CheeseDash rejects an unauthenticated request before
`StoreApi.handle` is ever called.

Public routes send no Authorization header at all. The three credit-spending routes
use a different mechanism entirely: a session cookie issued by the website's
account-link flow, resolved through `GoogleOAuth.currentSession`, then matched to a
Minecraft UUID through `AccountLinker`. No cookie → `401 not_authenticated`. Cookie
but no completed `/store link` → `403 not_linked`.

---

## The missing role guards

CheeseDash gives a module three helpers to gate a route by role:
`WebDashboard.requireWrite(exchange)` for OPERATOR-or-higher,
`WebDashboard.requireAdmin(exchange)` for SUPER_ADMIN, and `getSession(exchange)` to
inspect the caller. The correct shape is one line at the top of a handler:

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

**`StoreApi.handle` calls none of them.** Not once, anywhere in the file — the
strings `requireWrite`, `requireAdmin` and `getSession` do not appear in CheeseStore's
source at all. Every mutating admin route listed on this page — every `POST`, `PUT`
and `DELETE`, the whole of products, gallery, cosmetics, playable cosmetics, manual
delivery, wallet balances and the Stripe configuration — therefore accepts **any
authenticated CheeseDash session**, `READ_ONLY` included.

The practical consequences, in plain terms. A holder of a `READ_ONLY` account can:

- set any player's credit balance to any number (`PUT /wallets/:uuid`)
- change a product's price, or its delivery commands (`PUT /products/:id`)
- re-deliver an order (`POST /orders/:id/deliver`)
- overwrite the Stripe secret key and webhook secret (`PUT /config`)

The last one is the sharpest: `PUT /config` writing a new `stripeWebhookSecret`
immediately invalidates signatures from the real Stripe endpoint, which silently
stops all deliveries; writing a new `stripeSecretKey` points checkout at a different
Stripe account.

This is documented rather than fixed, deliberately, because changing it changes
behaviour for anyone already running the mod with non-SUPER_ADMIN accounts. Until it
is fixed, treat **every** CheeseDash account that can reach this module as a full
store operator. If you need a genuinely read-only viewer, do not give them a
CheeseDash login.

The public routes are not affected by this gap. They never relied on role checks;
they have their own guards, and those guards are present.

---

## Admin routes

All paths below are relative to `/api/v1/store`. Required role is stated as the code
actually behaves — see above for why it is uniform.

### Products

| Method | Path | Role in practice | Body | Response |
|---|---|---|---|---|
| `GET` | `/products` | any session | — | `200` array of full product objects |
| `POST` | `/products` | any session | product object, optional `imageBase64` | `201` the created product |
| `PUT` | `/products/:id` | any session | partial product, optional `imageBase64` | `200` the merged product |
| `DELETE` | `/products/:id` | any session | — | `200 {"deleted":true}` |
| `POST` | `/products/:id/image` | any session | `{"imageBase64":"..."}` | `200 {"ok":true}` |
| `GET` | `/products/:id/image` | any session | — | `200 image/png`, or `404` |

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
Same shapes as products, managed by `GalleryItem`.

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
debugging: the order id does not exist, the order is already `DELIVERED`, or a
command threw. Check the server log to tell them apart — a throw is logged by
`DeliveryManager` and sets the order's `failReason`, which `GET /orders` then shows.

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
  "deliveredAt": 0,
  "failReason": null
}
```

`status` is one of `PENDING`, `DELIVERED`, `FAILED`. `commands` are already resolved
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
knows about them. `failedOrders` is the number to watch; see
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

`/session-status` carries **no** rate limit and no origin check. It is a
read of Stripe's own state followed by an idempotent delivery, so the worst a flood
achieves is one outbound Stripe API call per request — which still counts against
your Stripe rate limits and is worth a proxy-level limit if your store is busy.

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
| `401` | `not_authenticated` | no OAuth session cookie |
| `403` | `not_linked` | signed in on the web, but no `/store link` completed |
| `400` | `cosmeticId required` / `morphoId required` | missing or path-unsafe id |
| `404` | `cosmetic_not_found` / `morpho_not_found` | unknown, or disabled, or not flagged for the website |
| `409` | `already_owned` | morphos and playable cosmetics only; **no debit happened** |
| `402` | `insufficient_credits` | includes `currentBalance` and `required` in the body |
| `503` | `server_not_ready` | the Minecraft server is not up; nothing debited |
| `500` | `buy_failed` | the server-thread work threw for another reason |

`/cosmetics/buy` has **no** `already_owned` guard: legacy cosmetics are repeatable
purchases, and buying one twice debits twice and runs `applyCommands` twice. If a
cosmetic should be one-per-player, that has to be enforced inside its commands.

Success is `200 {"success":true,"newBalance":N,"<kind>Id":"..."}`, and each kind also
broadcasts its own purchase event to the dashboard.

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
order appears in `GET /orders` and in the log, but no WebSocket message announces it.
A dashboard watching only this channel will not notice.

Wallet changes do not broadcast either, from any path.

---

← [Cosmetics and wallets](cosmetics.md) · [Architecture](architecture.md) →
