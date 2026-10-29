[← CheeseStore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

32 Java files, about 8 100 lines, no mixins. The mod is a Fabric `ModInitializer` plus
a CheeseDash module, and almost everything it does is state in JSON files guarded by
one HTTP handler and a handful of Fabric event listeners.

---

## Packages

```text
com.cheesestore
├── CheeseStoreMod            ModInitializer — wires everything, owns the singletons
├── CheeseStoreConfig         cheesestore.json: Stripe keys, store settings, products, gallery
├── StripeClient              Stripe REST over JDK HttpClient; webhook HMAC verification
├── StoreProduct              a sellable product (price in cents + delivery commands)
├── GalleryItem               a non-sellable showcase entry
├── PendingDelivery           one order: resolved commands + PENDING/DELIVERED/FAILED
├── DeliveryManager           the order queue, its persistence, and command execution
├── WalletManager             credit balance per UUID, async persistence
├── CosmeticItem              legacy website cosmetic (priced in credits, runs commands)
├── CosmeticManager           legacy cosmetic catalogue + its image store
├── PremiumCommand            /premium and /balance
├── StoreCommand              /store link <code>
├── auth
│   ├── AccountLinker         pairing codes, session → Minecraft UUID links
│   └── GoogleOAuth           the store_session cookie and /oauth/* routes
├── cosmetic                  the playable cosmetic system (16 files, ~2 800 lines)
│   ├── CosmeticsModule       single init() that wires the whole subsystem
│   ├── CosmeticEntry         the shared model: category + a params string map
│   ├── CosmeticManager       catalogue, seeded from DefaultCatalog on first run
│   ├── DefaultCatalog        the 63 seeded entries
│   ├── OwnershipManager      uuid → owned ids
│   ├── EquipManager          uuid → category → the one equipped id
│   ├── TrailManager          particle trails, every 2 ticks
│   ├── CombatEffectsManager  kill and death effects
│   ├── CosmeticCombatBridge  reflective entry point for the factionwar mod
│   ├── NameTagManager        chat tags and name colours
│   ├── PetManager            follower mobs
│   ├── MountManager          rideable cosmetic mobs, combat recall
│   ├── CosmeticsCommands     /cosmetics and its sgui menu
│   └── MountCommand          /mount
├── net
│   ├── CapePayload           the cheesestore:capes custom payload
│   └── CapeNetworking        broadcasts the uuid → capeId map
└── dashboard
    ├── CheeseStoreDashboardModule   the DashboardModule: prefix "store", public prefix "store"
    └── StoreApi                     1 791 lines: every admin and public route
```

`store/` at the mod root is not Java. It is the storefront — HTML, CSS, JS, fonts and
images — copied into the jar at `resources/store/` by a `from(...) { into "store" }`
block in `build.gradle`, and served by CheeseDash under the module's public prefix.

---

## Who owns what state

Every manager is a singleton initialised from `CheeseStoreMod.onInitialize()`, and
each owns exactly one file under `config/`. There is no database and no shared
transaction: a write touches one file.

| Class | File | Write trigger | Written on |
|---|---|---|---|
| `CheeseStoreConfig` | `cheesestore.json` | any product, gallery or settings change | the calling thread, `synchronized` |
| `DeliveryManager` | `cheesestore_deliveries.json` | order created, delivered or failed | the calling thread, `synchronized` |
| `WalletManager` | `cheesestore_wallets.json` | any balance change | **a `CompletableFuture` pool thread** |
| `CosmeticManager` (legacy) | `cheesestore_cosmetics.json` | catalogue change | the calling thread |
| `cosmetic.CosmeticManager` | `cheesestore_playcosmetics.json` | catalogue change | the calling thread |
| `OwnershipManager` | `cheesestore_ownership.json` | grant or revoke | the calling thread |
| `EquipManager` | `cheesestore_equipped.json` | equip or unequip | the calling thread |
| `AccountLinker` | `cheesestore_oauth_links.json` | link completed or dropped | the calling thread |

Writes are whole-file rewrites through `Files.writeString`, not appends and not
atomic — no temp-file-and-rename anywhere. A crash during a write can leave a
truncated file. Every loader catches its own parse failure and logs it rather than
throwing, so a corrupt file degrades to *empty* rather than preventing startup: a
truncated `cheesestore_wallets.json` means **every balance reads zero**, silently,
with one `Failed to load wallets` line in the log. Back these files up.

`DeliveryManager` caps itself at `MAX_DELIVERIES = 5000`, dropping the oldest entries
from the head of the list on save. Past that point your order history is lossy. The
cap is a constant in the class, not configurable.

`CheeseStoreConfig` rewrites the **entire** file on every save, products and gallery
included. A hand edit made while the server is running is therefore lost on the next
dashboard change.

---

## Threading

Three kinds of thread touch this mod, and the hand-off points are what to look at
when something behaves strangely.

**The server thread** runs the commands, the Fabric event listeners, and every
cosmetic manager's tick work. `TrailManager`, `PetManager` and `MountManager` all run
on `END_SERVER_TICK`; the trail is throttled to every 2 ticks and mount
equip/orphan re-evaluation to every N ticks, while the mount's combat poll runs every
tick.

**CheeseDash HTTP threads** run `StoreApi.handle` and `StoreApi.handlePublic`. These
read the config, the catalogues and the wallets directly — all `ConcurrentHashMap` or
`CopyOnWriteArrayList`, so a read is safe — and they hand anything that touches the
Minecraft world over with `WebDashboard.executeOnServerThread(server, () -> {...})`,
which blocks the HTTP thread until the server thread has run the lambda.

Four places do that hand-off, and each has a reason:

| Site | Why it must be on the server thread |
|---|---|
| `POST /orders/:id/deliver` | runs commands |
| `PUT /wallets/:uuid` | messages the player |
| `/cosmetics/buy`, `/morpho/buy`, `/playcosmetics/:id/buy` | atomic debit, plus commands or a grant |
| `processPayment` → `deliverPending` | runs commands — via `server.execute`, fire-and-forget |

The purchase routes are the interesting ones. Each checks the balance on the HTTP
thread for a cheap rejection, then checks it **again** inside the lambda, because two
concurrent requests both pass the first check. The second check, running on the
single server thread, serialises them. The source comments call this the TOCTOU
window; they are correct, and that double check is the only thing standing between a
busy store and a double debit.

Note the asymmetry in how `processPayment` dispatches: it uses `server.execute(...)`,
which queues and returns, rather than `executeOnServerThread`, which waits. That is
right for a webhook — Stripe wants a fast `200` and will retry if it does not get
one — but it means the HTTP response says nothing about whether delivery worked.

**A `CompletableFuture` common-pool thread** writes `cheesestore_wallets.json`.
`WalletManager.saveAsync()` is the only asynchronous persistence in the mod. The
upside is that a balance change never blocks a tick; the downside is a window of a
few milliseconds in which a granted credit is in memory but not on disk.

Nothing here holds a lock across a thread boundary, and nothing calls back from the
server thread into an HTTP thread, so there is no deadlock path that I can find.

---

## Request routing

CheeseDash discovers the module through the `cheesedash` entrypoint and reads two
prefixes off it:

```text
getPrefix()       = "store"  →  /api/v1/store/*   → handleRequest  → StoreApi.handle
getPublicPrefix() = "store"  →  /store/api/*      → handlePublicRequest → StoreApi.handlePublic
                                /store/           → the storefront from resources/store/
```

CheeseDash authenticates before `handleRequest` is called and does not authenticate
before `handlePublicRequest`. That single difference is the whole security boundary
of this mod. Both handlers answer `503 CheeseStore not ready` if the module has no
server reference yet, which is what you get during startup.

Inside both handlers, dispatch is a flat chain of `if (subRoute.equals(...) &&
method.equals(...))` with a `404` at the bottom and one `try`/`catch (Exception)`
wrapping everything, returning `500 Internal server error` and logging the cause.
There is no router, no annotation, no path-parameter extraction beyond
`substring` arithmetic. For 30-odd routes that is readable; it is also why a route
added in the wrong place can be shadowed by an earlier prefix match.

Path ids go through one guard, `isValidId`, rejecting `..`, `/`, `\`, `%` and
anything 128 characters or longer. That is what keeps `GET /products/<id>/image`
from reading an arbitrary file, since the id is concatenated into a path.

---

## Account linking

`GoogleOAuth` is a misnomer: the Google flow was removed, and `/oauth/start` and
`/oauth/callback` are stubs that redirect to `/store/`. What remains is a pairing
scheme.

1. The browser hits `/store/api/oauth/me`. If it has no `store_session` cookie, one
   is minted from `AccountLinker.randomSessionId()` and set `HttpOnly; Path=/;
   SameSite=Lax; Max-Age=14 days`.
2. The browser asks `/store/api/oauth/link-code`. `AccountLinker.issueCode` returns a
   code bound to that session id, valid `PENDING_TTL_MS` = **5 minutes**. The route is
   rate-limited to 12 requests per minute per IP.
3. The player runs `/store link <code>` in game. `completeLink` matches the code,
   writes a `Link` of `{sessionId, minecraftUuid, minecraftName, createdAt}` to
   `cheesestore_oauth_links.json`, and allows **5 attempts per 60 seconds** per
   player before answering `RATE_LIMITED`.
4. From then on, `currentSession(exchange)` reads the cookie, finds the `Link`, and
   every credit route knows which UUID is spending.

What this proves is narrow and worth stating plainly: *the holder of this browser
session also had a logged-in Minecraft client on this server*. It is not an
authenticated identity. The cookie has no `Secure` flag, so it is not marked
HTTPS-only even behind a TLS proxy. There is no logout-everywhere, no session list,
and no expiry on the `Link` itself — once bound, a session stays bound until
`/oauth/logout`.

`Session.email` is misleadingly named: it holds the session id. The field is kept
under the old name, per its own comment, for compatibility with the morpho purchase
path.

---

## Inter-mod coupling

**CheeseDash** — a hard dependency, declared in `fabric.mod.json` and resolved at
build time through a `fileTree` glob on `../CheeseDash/build/libs/cheesedash-*.jar`.
CheeseStore has no HTTP server of its own, so without CheeseDash there is no
storefront, no webhook endpoint and no admin surface. It also borrows CheeseDash's
helpers: `WebDashboard.respond`, `readBody`, `executeOnServerThread`,
`servePngWithRevalidation`, and `DashboardRegistry.broadcast`.

**CheeseItems** — also a hard dependency, and the coupling is tighter than it looks.
`StoreApi` imports `com.cheeseitems.morpho.MorphoCatalog`, `MorphoEntry`,
`MorphoCodex`, `MorphoCodexEntry`, `MorphoThumbnail` and `MorphoManager`, and calls
`CheeseItemsMod.INSTANCE.getMorphoCodex()` directly — no reflection, no interface in
between. A signature change on any of those breaks the CheeseStore build.

**sgui** (Patbox) — bundled into the jar with `include`, so it needs no separate
install. It powers the `/cosmetics` chest menu, and every call into it is wrapped so
that a failure falls back to the text listing instead of erroring.

**factionwar** — not a declared dependency and not in this repository. It looks up
`com.cheesestore.cosmetic.CosmeticCombatBridge` **by name** and invokes `onKill` and
`onDeath` reflectively. Rename that class or change a signature and kill and death
effects stop firing with no error on either side. This is an observation of how the
two are wired, from this mod's comments; I have not read the other mod.

**factionwarhud** — likewise absent here. Capes are rendered client-side from
`factionwarhud:textures/entity/cape/<capeId>.png`; the server only broadcasts the
`uuid → capeId` map on the `cheesestore:capes` payload. Without that client mod,
capes sell and equip and nobody sees them.

---

## Mixins

There are none. No `cheesestore.mixins.json`, no `mixins` key in `fabric.mod.json`.
Everything is done through Fabric events and public APIs, which is why this mod
survives Minecraft patches better than most of its siblings.

`fabric.mod.json` declares `"environment": "*"` and a single `main` entrypoint — no
`client` entrypoint, even though `net/CapeNetworking` registers an S2C payload.
Registering the payload type from the common initialiser works for the server side;
the client that renders it is `factionwarhud`, not this mod.

---

## Extension points

**A new product behaviour** needs no code. Products deliver through console commands,
so anything you can express as a command is already sellable.

**A new cosmetic category** is the one place the design invites extension. Add a
constant to `CosmeticEntry.Category`, a manager with a static idempotent `init()` that
registers its own Fabric events, a line in `CosmeticsModule.init()`, a branch in
`CosmeticsCommands.applyVisuals` if it needs refreshing on equip, and entries in
`prettyCategory` and `categoryIcon` for the GUI. Then add the category to the
`categories` array in `StoreApi.buildEnumsJson()` so the dashboard offers it. Six
touch points, all small, none hidden.

**A new public route** goes in `StoreApi.handlePublic`. If it spends anything, copy
the guard chain from `handlePlayCosmeticBuy` verbatim — same-origin, rate limit,
session, link, double balance check — rather than writing a new one.

**A new admin route** goes in `StoreApi.handle`, and should open with
`if (!WebDashboard.requireWrite(exchange)) return;` even though no existing route
does. See below.

---

## Known weaknesses

Stated plainly, because whoever maintains this next will meet all of them.

**No role enforcement on the admin surface.** `StoreApi.handle` never calls
`requireWrite` or `requireAdmin` — the strings do not occur in the mod at all — so
every mutating admin route accepts any authenticated CheeseDash session, `READ_ONLY`
included. That includes setting wallet balances, rewriting delivery commands, and
overwriting the Stripe secret and webhook secret. The fix is one line per handler;
it has not been applied here because it changes behaviour for anyone already running
non-SUPER_ADMIN accounts. Fully described in
[the dashboard API](dashboard.md#the-missing-role-guards).

**A paying buyer's UUID is unverified.** `POST /store/api/checkout` accepts
`playerUUID` and `playerName` from the request body and takes them on trust. The
credit routes do not have this problem — they resolve the UUID from a linked session —
but the card path does.

**No PNG validation, no size cap on uploads.** `saveBase64Image` base64-decodes the
body and writes it to `<id>.png` with no check that it is an image and no limit on
length. A large body is a memory spike and a disk write; a non-image is served to
browsers with a PNG content type. Any authenticated session can do it.

**Non-atomic whole-file writes.** No temp-and-rename. A crash mid-write can truncate
a file, and every loader degrades a corrupt file to empty rather than failing loudly.
The worst case is `cheesestore_wallets.json`: every balance silently becomes zero.

**Order history is capped and lossy.** `MAX_DELIVERIES = 5000`, oldest dropped. For a
store that has taken real money, the authoritative record is Stripe's, and this file
should be treated as a convenience cache rather than accounting.

**`FAILED` orders are invisible unless you look.** No WebSocket event, no Discord
hook, no retry. A paid order whose commands failed sits in `GET /orders` until
someone notices. The counter is in `GET /stats` as `failedOrders`.

**Delivery success is "the command did not throw".** A command that silently does
nothing is recorded `DELIVERED`.

**Two cosmetic systems with the same class simple name.** `CosmeticItem` /
`CosmeticManager` in the root package, `CosmeticEntry` /
`cosmetic.CosmeticManager` in the subpackage. The root one is legacy, kept because the
live storefront JS still consumes `/store/api/cosmetics`. Every site that touches both
has to spell one of them out fully. This is a naming debt, not a bug, and merging
them would be a storefront-breaking change.

**Stale comment on `CosmeticEntry`.** Its javadoc names
`config/cheesestore_cosmetics.json` as its catalogue file. The manager resolves
`cheesestore_playcosmetics.json`. Trust the code.

**Dead configuration.** `googleClientId`, `googleClientSecret` and
`googleRedirectUri` are loaded, saved, and read by nothing. `oauthJwtSecret`
generates itself and signs nothing. `GET /store/api/config` reports
`oauthEnabled: true` as a hard-coded literal.

**Hard-coded French in the cosmetic subsystem.** `CosmeticsCommands`, `MountCommand`,
`MountManager` and the whole of `DefaultCatalog` use `Text.literal` with French
strings rather than translation keys, while the rest of the mod uses
`Text.translatable` with proper `en_us` and `fr_fr` files. An English server gets
French cosmetic messages and 63 French catalogue titles.

**`GET /store/api/players` is an unauthenticated roster dump.** Every name and UUID
from `usercache.json` plus the online list, unpaginated. The storefront's player
picker needs it. Nothing else.

**The storefront's terms page is one server's terms.** `store/cgv.html` was written
for a specific jurisdiction and hosting arrangement. It is shipped as an example of
the shape, not as something to publish.

---

← [Dashboard API](dashboard.md) · [Troubleshooting](troubleshooting.md) →
