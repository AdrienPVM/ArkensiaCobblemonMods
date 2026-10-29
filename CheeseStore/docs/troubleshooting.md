[← CheeseStore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

Symptoms are grouped by where you notice them: startup, payments, the dashboard,
credits and cosmetics. Payment problems come first, because they are the ones that
cost money.

---

## A buyer paid and received nothing

**Cause.** One of four, and they are distinguishable. Find the order in the
dashboard's orders view, or:

```bash
curl -H "Authorization: Bearer <session>" http://localhost:44311/api/v1/store/orders | head -c 400
```

| What you see | What happened |
|---|---|
| the order, `status: "DELIVERED"` | the most common case: a delivery command failed in the console (typo, missing mod) — Minecraft swallows the error, so the order still reads `DELIVERED` |
| the order, `status: "FAILED"`, with a `failReason` | rare: the call that runs a command threw |
| the order, `status: "PENDING"` | the payment landed, the buyer is offline (or `playerUUID` is not a valid UUID) |
| **no order at all** | the payment never reached the mod, or the product was deleted / metadata was missing |

**Fix, for `DELIVERED` with nothing received.** Search the server log around
`Delivered orderId=<id>` for the command manager's error, fix the product's
`commands`, and run the corrected command by hand for this buyer: the dashboard
refuses to re-deliver a `DELIVERED` order.

**Fix, for `FAILED`.** Read `failReason` — it is the exception message from the
command. Usually a typo, or a command belonging to a mod that is no longer installed.
Fix the product's `commands` for future buyers, then re-deliver this one with the
dashboard's delivery button. Note the order's stored commands were resolved at
purchase time, so re-delivery runs the **old** commands; if the command itself was
wrong, edit it in the product, delete nothing, and run the corrected command by hand
for this buyer.

**Fix, for `PENDING`.** Nothing to do. The buyer gets it on their next login, and the
log will say `Delivered 1 pending purchase(s) to <name>`. If they have logged in
since and it is still pending, that is a bug — check whether `deliverPending` logged a
failure for them.

**Fix, for no order.** The webhook never arrived or was rejected. Open Stripe's
webhook endpoint page and look at the delivery attempts:

- `503 Webhook secret not configured` → `stripeWebhookSecret` is empty. The mod
  refuses to act without it, on purpose.
- `400 Invalid signature` → the secret is wrong for this endpoint. The signing secret
  is **per endpoint**: a live endpoint's `whsec_…` differs from your test one. This is
  the single most common live-launch failure.
- connection refused or timeout → your URL is wrong, or the port is not reachable from
  the internet.
- no attempts at all → the endpoint is not subscribed to
  `checkout.session.completed`.

Stripe retries a failed webhook for up to three days, so fixing the secret can
recover orders that failed earlier. Check the endpoint's attempt log before you
refund anyone.

## Checkout answers 503

**Cause.** Read the body; it names the problem.

| Body | Cause |
|---|---|
| `Stripe not configured` | `stripeSecretKey` is empty, so no `StripeClient` exists |
| `Store base URL is not configured…` | `storeBaseUrl` is empty **or contains `localhost`** |

**Fix.** Set the key, or set a real public origin with no trailing slash. The
`localhost` rejection is deliberate: Stripe would redirect the buyer to their own
machine and they would land nowhere after paying. The check is a substring test, so a
genuine hostname containing the word `localhost` is also rejected.

The same condition logs at every startup:

```text
[CheeseStore] ⚠ storeBaseUrl is not configured! Checkout will be blocked.
```

## Checkout answers 403 forbidden_origin

**Cause.** `checkSameOriginPost` found an `Origin` or `Referer` that does not match
the request's `Host` header. Almost always a reverse proxy that rewrites `Host`
without rewriting the rest, or a storefront served from a different domain than the
one the API is reached on.

**Fix.** Make the proxy pass `Host` through unchanged, so what the browser sent as
`Origin` matches what the mod sees as `Host`. If you are calling the route with
`curl`, send an `Origin` header matching the host. Note the rule's one deliberate
hole: a request with **neither** header is allowed, because some privacy-strict
browsers strip both on same-origin POSTs.

## Checkout answers 502

**Cause.** Stripe itself rejected the session creation. The body carries Stripe's own
message.

**Fix.** Usual suspects: an unsupported `currency` on the product, a `priceCents`
below the minimum charge for that currency, or an empty product `name` — Stripe
requires a line-item name. The mod passes the product's fields through untouched, so
Stripe's message is about your product data.

## Everything was delivered twice

**Cause.** This should almost never happen. `DeliveryManager.createDelivery` refuses
a second order with the same Stripe session id, and logs it:

```text
[CheeseStore] Duplicate delivery attempt for orderId=cs_live_...
```

Seeing that line is the system working — it is the redirect path and the webhook
racing, which is normal and expected. In rare cases the check can still let a second
order with the **same** `orderId` through. That is a code bug, not a buyer paying
twice: deliver by hand only what is missing, and compare against Stripe.

**If goods really arrived twice**, the two orders have different ids, which means two
real checkout sessions. Check the orders view: if there are two distinct `orderId`s
your buyer paid twice, and that is a refund, not a bug.

The one genuine double-grant path is `POST /store/api/cosmetics/buy`, which has no
`already_owned` guard by design — legacy cosmetics are repeatable purchases. Buying
one twice debits twice and runs `applyCommands` twice. If a legacy cosmetic should be
once-only, enforce it inside its commands.

## Everyone's credit balance is suddenly zero

**Cause.** `cheesestore_wallets.json` failed to parse. Every loader in this mod
catches its own parse error and degrades to empty rather than failing startup, so a
truncated or corrupted wallet file reads as *no wallets*. One line in the log:

```text
[CheeseStore] Failed to load wallets: <reason>
```

Writes are whole-file rewrites with no temp-and-rename, so a crash during a save can
truncate it. The file is also written on a `CompletableFuture` pool thread, which
means a hard kill within milliseconds of a balance change can lose that change.

**Fix.** Stop the server, restore the file from a backup, start. If you have no
backup, the balances are gone — the only partial reconstruction is the orders file,
which records what was sold but not what was spent since.

Back these files up. They are the only record of credits that exists.

---

## The mod does not load

**Cause.** A missing dependency. CheeseStore declares `cheesedash` and `cheeseitems`
as hard requirements, plus `fabric-api`, Fabric Loader ≥ 0.16.0 and Java 21.

**Fix.** Read the Fabric error at the top of `logs/latest.log` — it names the missing
id. Build and install the sibling mods ([installation](installation.md#build-order)).
Also check you do not have two versions of the same jar in `mods/`; delete the old one
rather than leaving both.

## The build fails on `com.cheesedash` or `com.cheeseitems` imports

**Cause.** Sibling dependencies resolve through a `fileTree` glob on
`../CheeseDash/build/libs/` and `../CheeseItems/build/libs/`. Those directories are
empty until each mod is built, and an empty glob is not an error — Gradle just
compiles with the import unresolved.

**Fix.** Build them first, in order:

```bash
cd CheeseDash && ./gradlew build && cd ../CheeseItems && ./gradlew build && cd ../CheeseStore && ./gradlew build
```

Or run `tools/build-all.sh` from the repository root, which walks the whole set in
dependency order.

## The `store` module is missing from the dashboard

**Cause.** CheeseDash discovers modules through the `cheesedash` entrypoint in
`fabric.mod.json`. If the mod loaded but the module is absent, either CheeseDash
started before CheeseStore registered, or the entrypoint class failed to construct.

**Fix.** Check for `[CheeseStore] Initialized successfully!` in the log. If it is
there and the module is still missing, test the route directly:

```bash
curl http://localhost:44311/store/api/config
```

`503 {"error":"CheeseStore not ready"}` means the module *is* registered but
`CheeseStoreMod.INSTANCE` is still null — you are querying during startup, so wait
for `[CheeseStore] Server started` and retry. A `404` means CheeseDash never
registered the module at all; look for an entrypoint exception in the CheeseDash
startup block.

## A config change had no effect

**Cause.** Depends which key, and this catches people regularly.

| Change | Behaviour |
|---|---|
| Stripe keys, store name, base URL, `storeEnabled`, `currencyName` | live through `PUT /api/v1/store/config` |
| the same keys edited **in the file while running** | overwritten by the next dashboard save |
| the four web-session keys | restart required, and none of them does anything |

**Fix.** Edit through the dashboard, or stop the server before editing the file.
`CheeseStoreConfig` reads the file once at `onInitialize` and treats its in-memory
copy as authoritative — and rewrites the whole file, products included, on every
save.

If you edited `googleClientId`, `googleClientSecret` or `googleRedirectUri` and
nothing changed: those are dead configuration. Nothing reads them. See
[configuration](configuration.md#web-session-keys).

## A command is rejected, or does not tab-complete

**Cause.** The admin branches of `/premium` and `/cosmetics` require **permission
level 2**. Below that, Brigadier hides the branch from completion and the vanilla
*Unknown or incomplete command* error is what you get.

**Fix.** Op the player, or raise their level. The gated branches are
`/premium give|take|set|balance` and `/cosmetics grant|grantall|revoke`. Everything
else — `/premium`, `/balance`, `/premium open|top|cosmetics`, `/store link`,
`/cosmetics`, `/cosmetics list|equip|unequip`, `/mount` — is open to any player.

`This command must be executed by a player.` means you ran a player-only command from
the console. `/premium give` works from the console; `/premium` alone does not.

## `Player not found: <name>` on a player who exists

**Cause.** `/premium` resolves a name against online players first, then the server's
user cache. A player who has never joined this server is in neither.

**Fix.** Have them join once. Tab completion only offers **online** names,
but an offline name typed in full resolves fine through the cache — the completion
list being short does not mean the name will be rejected.

---

## Cosmetic messages are in French on an English server

**Cause.** The cosmetic subsystem — `CosmeticsCommands`, `MountCommand`,
`MountManager` — sends its feedback through `Text.literal` with hard-coded French
strings instead of translation keys. The 99 seeded catalogue titles in
`DefaultCatalog` are French literals too. The rest of the mod uses
`Text.translatable` with proper `en_us.json` and `fr_fr.json`.

**Fix.** Rewrite the catalogue titles through the dashboard — those are data. The
command feedback is not fixable without patching the Java; it is recorded as a known
weakness in [architecture](architecture.md#known-weaknesses).

## Capes are bought and equipped but nobody sees them

**Cause.** Working as built. A cape is rendered **client-side** by a companion mod,
`factionwarhud`, which is not in this repository. The server only broadcasts the
`uuid → capeId` map on the `cheesestore:capes` payload; the texture is expected at
`factionwarhud:textures/entity/cape/<capeId>.png`.

**Fix.** Ship that client mod, or disable the cape category. The 32 seeded cape
entries are enabled and priced out of the box, so on a vanilla-client server you are
selling something invisible until you turn them off.

## A player's mount will not summon

**Cause.** Most likely the combat cooldown. `MountManager` recalls a mount when its
owner takes or deals damage, and locks re-summoning for `COOLDOWN_TICKS` = 200 ticks,
about 10 seconds. The refusal message states the remaining seconds.

Two detectors trigger the recall: the `AFTER_DAMAGE` event and a per-tick `hurtTime`
poll. The poll is a deliberate fallback, which means an unusual damage source that
bypasses the event still recalls the mount.

Otherwise: nothing equipped in the `MOUNT` category (equipping only selects, it never
summons — use `/mount`), the player is dead or in spectator, or the `mob` param names
an entity that is not rideable.

**Fix.** Wait out the cooldown. Check `/cosmetics` shows something equipped under
*Montures*. Check the entry's `mob` param is one of the rideable ids —
`horse`, `donkey`, `mule`, `camel`, `skeleton_horse` are what
`/playcosmetics/enums` offers.

## A trail or kill effect does nothing

**Cause.** Different causes for the two.

A **trail** renders on `END_SERVER_TICK`, every 2 ticks. If it appears once and then
never again, its `particle` is `minecraft:flame`, missing, unknown or parameterised:
`TrailManager` caches the failure sentinel as `FLAME` itself, so a flame trail and a
failed lookup look identical in the cache and both stop rendering after the first
frame (the seeded `trail_flame` is affected; a warning is logged once for a bad id).

A **kill effect** is not hooked by this mod at all. It is driven by
`CosmeticCombatBridge`, which a separate mod (`factionwar`) looks up **by class name**
and invokes reflectively. Without that mod installed, kill effects never fire. If the
class were renamed, they would stop firing silently — the reflective lookup just
fails. A **death effect** does not depend on it: `CombatEffectsManager` also listens
to `AFTER_DEATH`, so if a death effect does nothing, check that it is equipped and
enabled and that its `effect` is one of the six known kinds.

**Fix.** For a trail: set the `particle` param to another simple particle from the
`particles` list of `GET /api/v1/store/playcosmetics/enums` — not `minecraft:flame`
(and not parameterised ones such as `minecraft:dust` or `minecraft:falling_dust`) —
then restart to clear the cache. For combat effects: confirm `factionwar` is
installed (for kill effects). This is an observation about how the two mods are
wired, read from this mod's comments; I have not verified it against `factionwar`
itself.

## A credit purchase on the website fails

**Cause.** Read the status code; each one is a distinct cause.

| Status | Body | Cause |
|---|---|---|
| `401` | `not_authenticated` | no `store_session` cookie, **or** the browser session has no `/store link` behind it |
| `403` | `not_linked` | practically unreachable: the link was removed during the request |
| `403` | `forbidden_origin` | `Origin`/`Referer` did not match `Host` |
| `429` | `too_many_requests` | 10 purchases per 60 s per IP, shared across all three buy routes |
| `402` | `insufficient_credits` | the body carries `currentBalance` and `required` |
| `409` | `already_owned` | morphos and playable cosmetics only; nothing was debited |
| `503` | `server_not_ready` | the Minecraft server is down; nothing was debited |

**Fix, for `429` specifically.** If every visitor shares the limit, your reverse proxy
is not on an address the mod trusts. `publicClientIp` honours `X-Forwarded-For` only
when the immediate peer is loopback or RFC1918 (`10.`, `192.168.`,
`172.16`–`172.31`); from any other address every request is counted against the
proxy's single IP. Either move the proxy onto a private address, or edit
`isTrustedProxy` in `StoreApi.java`.

## An OPERATOR changed the Stripe keys, and I wanted that to need SUPER_ADMIN

**Cause.** Not a bug, and not something you can configure away. `StoreApi.handle`
never calls `WebDashboard.requireWrite` or `requireAdmin`; CheeseDash's module
dispatcher applies the check instead, and it only knows one bar — `OPERATOR` for any
non-`GET`. So `OPERATOR` is the ceiling for everything on this module: products,
orders, wallet balances and the Stripe keys. A `READ_ONLY` account cannot write any of
them; an `OPERATOR` can write all of them.

**Fix.** Give `OPERATOR` only to people you would trust with your Stripe secret key,
and `READ_ONLY` to everyone who just needs to look. If a route genuinely has to
require `SUPER_ADMIN`, that has to be added inside the handler with `requireAdmin` —
the dispatcher cannot express it. Full description in
[the dashboard API](dashboard.md#what-the-core-actually-enforces).

---

## Reporting a bug

Open an issue at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) with:

1. **The `[CheeseStore]` lines from `logs/latest.log`.** Start from
   `[CheeseStore] Initializing...` and include everything up to the failure. If the
   server crashed, the whole stack trace.
2. **Mod versions** — CheeseStore, CheeseDash, CheeseItems, Fabric Loader, Fabric API.
3. **The exact HTTP request and response** if it is a route problem: method, path,
   status, body. Use `curl -i`.
4. **Which path you were on** for a payment problem: the redirect
   (`/store/api/session-status`) or the webhook (`/store/api/webhook`). Stripe's
   webhook attempt log is worth a screenshot.
5. **The order's `status` and `failReason`** for a delivery problem, from
   `GET /api/v1/store/orders`.

> ⚠️ **Redact before you post.** `config/cheesestore.json` holds live secrets, and so
> do some log lines. Never paste `stripeSecretKey`, `stripeWebhookSecret` or a raw
> `Authorization: Bearer` header into a public issue. A `sk_live_` key in a GitHub
> issue is your Stripe account compromised; rotate it immediately if it happens.
> A **security** problem goes to [SECURITY.md](../../SECURITY.md), not to a public
> issue.

Questions that are not bugs → [Arkensia Discord](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.md) · [CheeseStore](../README.md) →
