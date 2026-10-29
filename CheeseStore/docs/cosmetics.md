[← CheeseStore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](cosmetics.fr.md)

# Cosmetics and wallets

Everything a player can buy without a card goes through this subsystem: a credit
balance, a catalogue priced in credits, an ownership list, and one equipped slot per
category. The Stripe side only tops up the balance — it does that by running a
product's commands, which for a credit pack means `premium give {player} <n>`.

---

## The currency

One balance per player, a `long`, stored by `WalletManager` in
`config/cheesestore_wallets.json` keyed by UUID:

```json
{
  "069a79f4-44e9-4726-a5be-fca90e38aaf5": {
    "playerName": "Steve", "balance": 1250, "lastUpdated": 1730000000000
  }
}
```

Its display name comes from `currencyName` in the config, default `crédit`. The code
and the log lines call it *fromage* in places, because that is what it was called on
the server it was written for; the lang files call it *credits*. Same number
throughout, three names.

`WalletManager` holds balances in a `ConcurrentHashMap` and writes the file
**asynchronously** through `CompletableFuture.runAsync` after every change. That
keeps a balance change off the server thread, and it has a consequence: a hard crash
within a few milliseconds of a credit grant can lose that grant. For a few credits
that is acceptable; it is worth knowing before you grant someone 100 000 and
immediately `/stop`.

Four operations exist on a wallet: `getBalance`, `add`, `deduct` (which refuses and
returns `false` rather than going negative) and `setBalance` (which clamps at zero).
The dashboard's wallet editor and `/premium set` both use `setBalance`, so both
**overwrite** rather than adjust.

---

## Two catalogues named the same thing

This is the first thing to get straight, because the naming is actively misleading.
CheeseStore contains **two** unrelated cosmetic systems with similar names, and they
coexist on purpose.

| | Legacy | Playable |
|---|---|---|
| Class | `com.cheesestore.CosmeticItem` | `com.cheesestore.cosmetic.CosmeticEntry` |
| Manager | `com.cheesestore.CosmeticManager` | `com.cheesestore.cosmetic.CosmeticManager` |
| File | `config/cheesestore_cosmetics.json` | `config/cheesestore_playcosmetics.json` |
| Admin routes | `/api/v1/store/cosmetics` | `/api/v1/store/playcosmetics` |
| Public routes | `/store/api/cosmetics` | `/store/api/playcosmetics` |
| What buying does | runs a list of `applyCommands` | records ownership |
| Repeatable? | yes — buying twice debits twice | no — `409 already_owned` |
| In-game surface | `/premium cosmetics` lists them | the whole `/cosmetics` tree |
| Images | `config/cheesestore_assets/cosmetics/<id>.png` | none; an item id is the icon |

The two manager classes have the **same simple name** in different packages, which is
why the source refers to one as `com.cheesestore.cosmetic.CosmeticManager` in full
everywhere it touches both.

The legacy model came first and is a generic "sell anything, run commands" hook: its
`Type` enum is `HAT`, `TRAIL`, `PARTICLE`, `ITEM`, `EFFECT`, used only as filter chips
on the website, and the real behaviour is whatever `applyCommands` does. The playable
model came second, implements its effects itself, and is the one the in-game menu
knows about.

If you are setting up a new store, use the playable system and leave the legacy
catalogue empty. The legacy routes are kept because the live storefront JavaScript
still consumes them.

> ⚠️ `CosmeticEntry`'s own class comment says its catalogue lives in
> `cheesestore_cosmetics.json`. It does not — `CosmeticManager` resolves
> `cheesestore_playcosmetics.json`. The comment is stale; the code is right.

---

## The playable model

Three files, three concerns, and that separation is the thing to understand:

```text
cheesestore_playcosmetics.json   the catalogue — what exists, what it costs
cheesestore_ownership.json       uuid → set of cosmetic ids the player owns
cheesestore_equipped.json        uuid → category → the one id worn there
```

Owning is not wearing. A player can own twelve trails and wear none. Deleting a
catalogue entry does not clean up ownership rows that reference it — they become
dangling ids that resolve to nothing, harmless but never collected. Changing an
entry's `category` while someone wears it leaves them wearing it under the old
category key until they unequip.

A `CosmeticEntry`:

```json
{
  "id": "trail_flame",
  "title": "Sillage de Flammes",
  "description": "Une traînée de feu derrière vous.",
  "category": "TRAIL",
  "icon": "minecraft:blaze_powder",
  "price": 400,
  "isNew": false,
  "sortOrder": 0,
  "enabled": true,
  "params": { "particle": "minecraft:flame", "shape": "trail", "density": "3" }
}
```

`id` is yours to choose and is the ownership key, so never reuse one for a different
cosmetic. `price` and `sortOrder` are plain integers; `price` is clamped to zero or
more on both construction and merge. `icon` must be a vanilla item id — an
unresolvable one falls back to `minecraft:paper` in the GUI, and a missing one to
`minecraft:barrier`.

Everything category-specific lives in `params`, a flat string-to-string map. That is
what lets one POJO serve eight behaviours, at the cost of no type checking: a
`density` of `"banana"` is accepted at write time and read back as the default by
`paramInt`.

### The eight categories and their params

| Category | Params | Notes |
|---|---|---|
| `TRAIL` | `particle`, `shape`, `density` | `shape` ∈ `trail` `halo` `spiral` `orbit`; `density` an int |
| `KILL_EFFECT` | `effect`, `sound` (opt), `particle` (opt) | `effect` ∈ `firework` `lightning` `explosion` `souls` `totem` `blood` |
| `DEATH_EFFECT` | `effect`, `particle` (opt), `sound` (opt) | same `effect` vocabulary |
| `CHAT_TAG` | `tag`, `color` | `tag` is a prefix string, e.g. `✦ VIP` |
| `NAME_COLOR` | `color` | one of the 16 vanilla chat colours, lower-case |
| `PET` | `mob`, `petName` (opt), `baby` (opt) | `mob` a vanilla entity id |
| `MOUNT` | `mob`, `saddle`, `variant` (opt), `baby` (opt) | `mob` must be rideable |
| `CAPE` | `texture` | the cape id; see [capes](#capes-need-a-client-mod) |

`GET /api/v1/store/playcosmetics/enums` returns curated id lists for each of these so
the dashboard can offer dropdowns. Those lists are hard-coded in `StoreApi`, not read
from the registries, so a modded particle or mob is not offered — but nothing stops
you putting one in `params` by hand, and the managers will try to resolve it.

### The default catalogue

On first launch, with no `cheesestore_playcosmetics.json` present,
`CosmeticManager.load()` writes a seeded catalogue from `DefaultCatalog.build()`:

```text
[CheeseStore] No cosmetic catalogue found — writing default catalogue
[CheeseStore] Default cosmetic catalogue written (63 entries)
```

The composition, per the class comment: 10 trails, 8 kill effects, 8 death effects,
16 name colours (one per chat colour), 6 chat tags, 8 pets, 7 mounts, 8 capes. Every
title and description in it is **in French** — these are literal strings in
`DefaultCatalog.java`, not translation keys, so an English-speaking server has to
rewrite them through the dashboard or edit the file.

An empty-but-valid JSON array in that file is treated as a mistake and repopulated
with the defaults on the next start, with a warning. To run with no cosmetics at all,
disable the entries rather than emptying the file.

---

## How each category is applied

Each subsystem registers its own Fabric events from `CosmeticsModule.init()` and is
idempotent. None of them throws into the server tick: every handler wraps its work in
a `try`/`catch` on `Throwable` and logs a warning, because a cosmetic that crashes the
tick loop would be a far worse bug than a cosmetic that silently stops working.

### Trails

`TrailManager` renders on `END_SERVER_TICK`, but only every **2 ticks** — the
`TICK_INTERVAL` constant. Particle counts are bounded and positions cached per player.
A particle id that fails to resolve is remembered as a sentinel so the manager does
not retry the lookup every tick.

### Kill and death effects

`CombatEffectsManager` exposes `handleKill(killer, victim)` and `handleDeath(victim)`.
It does not hook combat itself. Instead `CosmeticCombatBridge` is a deliberately
stable façade that a third-party mod, `factionwar`, looks up **by name and invokes
reflectively**:

```java
com.cheesestore.cosmetic.CosmeticCombatBridge
  public static void onKill(ServerPlayerEntity killer, ServerPlayerEntity victim)
  public static void onDeath(ServerPlayerEntity victim)
```

Rename that class or change either signature and kill effects stop firing, with no
error anywhere — the caller is on the other side of a reflective lookup that will
simply fail. This is an observation about how the two mods are wired, not a
specification of `factionwar`, which is not part of this repository and which I have
not read. Without that mod installed, kill and death effects never fire at all unless
something else calls the bridge.

### Chat tags and name colours

`NameTagManager.refresh(player)` rebuilds the player's display name from the equipped
`CHAT_TAG` prefix and `NAME_COLOR`. It is called on equip, on unequip, and after bulk
grants. `cleanup(player)` reverses it.

### Pets

`PetManager` spawns a tagged mob (`cos_pet`, plus `cos_pet_owner.<uuid>`) that follows
its owner. `refresh(player)` re-syncs one player and `despawnFor` removes an orphan.
The tags are how a pet left behind by a crash is identified and cleaned on the next
start.

### Mounts

The most involved of the eight, and the one with rules a player will notice.

Equipping a `MOUNT` selects it and nothing more. The mob appears only on an explicit
`/mount`, `/mount summon`, or the summon button in the `/cosmetics` menu. A summoned
mount is invulnerable, silent, persistent and AI-disabled — it is steered by the
rider and has no stats, so it cannot win a fight for anyone.

It is recalled automatically when its owner enters combat. Two detectors run: the
`AFTER_DAMAGE` event on both attacker and victim, and a per-tick `hurtTime` poll as a
belt-and-braces fallback. After a combat recall the owner cannot re-summon for
`COOLDOWN_TICKS` = **200 ticks, about 10 seconds**, and an attempt during that window
answers with the remaining seconds.

Mounts are also despawned on death, disconnect, world change and unequip. The
equip/orphan re-evaluation is throttled to run every N ticks; only the combat poll
runs every tick.

### Capes need a client mod

A `CAPE` is rendered entirely client-side. The server does one thing: broadcast the
whole `uuid → capeId` map over a `cheesestore:capes` custom payload whenever anything
changes. The texture itself is expected at
`factionwarhud:textures/entity/cape/<capeId>.png`, in the 64×32 vanilla cape format,
inside a companion client mod called `factionwarhud`.

So: **capes do nothing on a vanilla client.** The eight seeded cape entries will sell,
equip, and show as equipped in the menu, and no one will see them. If you are not
shipping that companion mod, disable the cape category before opening the store.
`factionwarhud` is not in this repository, and its texture path is read from this
mod's comments, not verified against it.

---

## Buying with credits

Three routes spend credits, all on the public surface, all with the same guard chain —
the detail is in [the dashboard API](dashboard.md#credit-purchases). What matters
here is what they do once they have decided to let you through:

| Route | Buys | Effect |
|---|---|---|
| `POST /store/api/playcosmetics/:id/buy` | a `CosmeticEntry` | debit, then `OwnershipManager.grant` |
| `POST /store/api/cosmetics/buy` | a `CosmeticItem` | debit, then run `applyCommands` |
| `POST /store/api/morpho/buy` | a CheeseItems catalogue entry | debit, then add to that player's codex |

All three require a web session **and** a completed `/store link`, so the mod knows
which Minecraft UUID is spending. All three check the balance twice: once on the HTTP
thread for a fast rejection, then again inside `executeOnServerThread` before the
debit. The second check is the one that counts. Two simultaneous requests both pass
the first; only one passes the second.

Two of the three also re-check ownership inside the server-thread block, so a double
request cannot grant twice. `/cosmetics/buy` does not, by design — legacy cosmetics
are repeatable. If one of yours should be once-only, enforce it in the commands.

The whole purchase runs on the server thread, which means `applyCommands` runs there
too, from the console, with full permissions. A slow or blocking command in that list
stalls the tick. Keep them short.

### Admin grants

`POST /api/v1/store/playcosmetics/grant` and `/cosmetics grant <player> <id>` both
grant ownership with **no debit and no price check**. That is how you test a cosmetic
you have just authored, and it is also something any `OPERATOR` session can do: the
route sits behind [the core's write guard](dashboard.md#what-the-core-actually-enforces)
and nothing stricter.

`/cosmetics grantall <player>` grants every enabled entry. There is no `revokeall`;
undoing it is one `revoke` per id.

---

## What is not here

No expiry: ownership is permanent once granted. No trading or gifting between players.
No per-cosmetic purchase history — once granted, nothing records when or for how much,
so the only trace of a credit purchase is the dashboard WebSocket event, which is not
persisted anywhere. If you need an audit trail of credit spending, you will have to
add one.

---

← [The payment flow](payments.md) · [Dashboard API](dashboard.md) →
