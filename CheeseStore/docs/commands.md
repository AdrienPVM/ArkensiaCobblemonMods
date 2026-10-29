[← CheeseStore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

CheeseStore registers five command roots: `/premium`, `/balance`, `/store`,
`/cosmetics` and `/mount`. Two of them carry admin branches gated at **permission
level 2** — the level a vanilla server grants an operator. Everything else is open to
any player.

Nothing on the payment side is driven by a command. Products, orders and manual
delivery live in [the dashboard](dashboard.md); the commands here cover the credit
wallet, the account link, and the cosmetics a player has already bought.

The player-facing feedback is translated: the mod ships
`assets/cheesestore/lang/en_us.json` and `fr_fr.json`. The examples below give the
English strings. One exception, worth knowing: the `/cosmetics` and `/mount` trees
send their messages through `Text.literal` with **hard-coded French** rather than
translation keys, so those outputs are French on an English client. That is a code
defect, noted in [architecture](architecture.md#known-weaknesses).

---

## `/premium`

The credit wallet. Balances live in `config/cheesestore_wallets.json`, keyed by
player UUID, and are managed by `WalletManager`.

### `/premium` (no arguments)

```mcfunction
/premium
```

Shows the caller's own balance. Player-only — the console gets
`This command must be executed by a player.`

```text
🧀 Credits: 1250
```

`/balance` is a second root registered as a convenience alias for exactly this form.
It takes no arguments; an admin inspecting someone else still uses
`/premium balance <player>`.

### `/premium open`

```mcfunction
/premium open
```

Sends the caller a clickable chat line pointing at `<storeBaseUrl>/store/`. Player-only.

```text
🛒 Click here to open the store
```

If `storeBaseUrl` is unset, the link is built anyway and resolves to `/store/` with
no host, which the client will refuse to open. Set the key first — see
[configuration](configuration.md#storebaseurl).

### `/premium top`

```mcfunction
/premium top
```

The ten highest balances, in descending order. No permission requirement, so this is
a public leaderboard; if you do not want balances visible to everyone, you have no
switch for it short of patching the command.

```text
=== Top Credits ===
1. Steve — 4200
2. Alex — 1250
```

Returns the number of entries printed, or `0` with `No player has any credits.` when
no wallet exists yet.

### `/premium cosmetics`

```mcfunction
/premium cosmetics
```

Lists the enabled entries of the **legacy** `CosmeticItem` catalogue — the
website-purchasable list managed by `com.cheesestore.CosmeticManager`, not the
playable cosmetics of `/cosmetics`. The two catalogues are separate; see
[cosmetics and wallets](cosmetics.md#two-catalogues-named-the-same-thing).

```text
=== Cosmetics (credits) ===
Golden Trail [TRAIL] — 500 credits
```

Empty catalogue → `No cosmetics available right now.`

### `/premium give <player> <amount>`

```mcfunction
/premium give <player> <amount>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `player` | single word | yes | a player name; tab-completes from **online** players, but an offline name is resolved through the server's user cache | — |
| `amount` | long | yes | `1` or more (Brigadier rejects `0` and negatives) | — |

**Permission level 2.** A player below it does not see the `give` branch in tab
completion and gets the vanilla *Unknown or incomplete command* error.

Adds `amount` to the target's balance and writes the wallet file asynchronously. The
feedback is broadcast to other operators (`sendFeedback(..., true)`) and logged:

```text
+500 credits given to Alex. New balance: 1750
```

```text
[CheeseStore] Server gave 500 fromage to Alex (new balance: 1750)
```

If the target is online — and is not the admin running the command — they are
notified directly:

```text
🧀 +500 credits received! New balance: 1750
```

| Message | Cause |
|---|---|
| `Player not found: <name>` | neither an online player nor a user-cache entry matches; nothing changed |

### `/premium take <player> <amount>`

Same arguments and same permission level as `give`, with `amount` again clamped to
`1` or more by Brigadier.

The balance floors at zero rather than going negative. Taking 500 from a player who
holds 200 removes 200, and the feedback reports **200**, not 500 — the command
computes `min(amount, currentBalance)` for the message while setting the balance to
`max(0, current - amount)`.

```text
-200 credits taken from Alex. New balance: 0
```

### `/premium set <player> <amount>`

Same as above, except `amount` accepts `0` (`longArg(0)`), because setting a balance
to zero is a legitimate operation. `WalletManager.setBalance` clamps negatives to
zero as a second line of defence.

```text
Credits of Alex set to: 1000
```

The target, if online, sees a received or deducted notification depending on the
direction of the change. An unchanged balance sends nothing.

### `/premium balance <player>`

```mcfunction
/premium balance <player>
```

Reads another player's balance. **Permission level 2** — a plain player can see only
their own. Works on offline players through the user cache.

```text
🧀 Credits of Alex: 1250
```

---

## `/store link <code>`

```mcfunction
/store link <code>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `code` | single word | yes | the code shown on the website after signing in |

No permission requirement, and player-only: the code binds *your* UUID to the web
session that generated it. This is the second half of the account-link flow — the
website issues a code, you paste it in-game, and from then on the storefront knows
which Minecraft account your browser session owns. The credit-spending routes
(`/cosmetics/buy`, `/morpho/buy`, `/playcosmetics/{id}/buy`) all refuse without a
completed link.

`/store` has no other subcommand. There is no `/store list`, no `/store buy`.

```text
Store session linked to Steve. You can now see your balance and buy morphos from the website.
```

| Message | Cause |
|---|---|
| `Invalid or expired link code. Generate a fresh one from the store.` | the code never existed, was already used, or timed out |
| `Too many link attempts. Wait a minute before trying again.` | `AccountLinker`'s rate limit tripped — the guard that stops someone brute-forcing short codes |
| `This command must be executed by a player.` | run from the console |

---

## `/cosmetics`

The playable cosmetics: trails, kill and death effects, chat tags, name colours,
pets, mounts, capes. Alias: `/cosmetic`, registered as a Brigadier redirect to the
same node, so every subcommand works under both names.

A player can only equip what they own. Ownership comes from a web purchase, from an
admin `grant`, or from nowhere at all — the catalogue being visible does not mean the
cosmetic is reachable. The model is in [cosmetics and wallets](cosmetics.md).

### `/cosmetics` (no arguments)

Opens a chest-GUI menu built on `sgui` (bundled in the jar). The top level is a
3-row chest with one button per category, each showing the owned/total count and
what is currently equipped; the wallet balance sits in the bottom-centre slot.
Clicking a category opens a 6-row screen listing only the cosmetics you own there,
with a click-to-toggle on each one, a **Tout retirer** button at slot 49 and a back
arrow at slot 53. The `MOUNT` category gets an extra summon/dismiss button at slot 48.

If `sgui` throws for any reason, the command falls back to the text listing of
`/cosmetics list` rather than failing. Player-only.

### `/cosmetics list [category]`

```mcfunction
/cosmetics list [category]
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `category` | single word | no | `TRAIL` `KILL_EFFECT` `DEATH_EFFECT` `NAME_COLOR` `CHAT_TAG` `PET` `MOUNT` `CAPE` — tab-completes | all categories |

Prints the cosmetics you own, grouped by category, with a clickable `[équiper]` on
each unequipped entry and a clickable unequip link on the equipped one. Categories
where you own nothing are skipped entirely.

### `/cosmetics equip <id>`

```mcfunction
/cosmetics equip <id>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `id` | single word | yes | a cosmetic id; tab-completes **from the ids you own** |

Equipping replaces whatever was equipped in that cosmetic's own category — one slot
per category, enforced by `EquipManager`. The relevant visual manager is refreshed
immediately: `NAME_COLOR` and `CHAT_TAG` go through `NameTagManager`, `PET` through
`PetManager`, `MOUNT` through `MountManager`, `CAPE` re-broadcasts the cape map to
every client. `TRAIL`, `KILL_EFFECT` and `DEATH_EFFECT` are read live and need no
refresh.

Equipping a `MOUNT` selects it; it does not summon it. Use `/mount`.

| Message | Cause |
|---|---|
| `Cosmétique inconnu : <id>` | no such id in the catalogue |
| `Ce cosmétique est désactivé.` | the entry exists but `enabled` is false |
| `Tu ne possèdes pas ce cosmétique.` | not in your ownership list |
| `Impossible d'équiper ce cosmétique.` | `EquipManager.equip` returned null — the entry disappeared between the lookup and the equip |

### `/cosmetics unequip <category>`

```mcfunction
/cosmetics unequip <category>
```

Clears that category's slot and refreshes the matching visual manager. An invalid
category name is rejected against `CosmeticEntry.Category.isValid`; an empty slot
answers `Rien d'équipé dans <category>.` and returns `0`.

### `/cosmetics grant <player> <id>` — level 2

```mcfunction
/cosmetics grant <player> <id>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `player` | entity selector (player) | yes | a selector or name resolving to **one online player** |
| `id` | single word | yes | any catalogue id; tab-completes from the full catalogue, enabled or not |

Grants ownership without charging anything. `player` is an `EntityArgumentType`, not
a bare word, so it accepts `@p`, `@a[limit=1]` and the like — but it resolves against
online players only. The target is told what they received and pointed at
`/cosmetics`.

This is the intended way to test a cosmetic you have just authored in the dashboard.

| Message | Cause |
|---|---|
| `Joueur introuvable.` | the selector matched nothing, or matched something that is not a player |
| `Cosmétique inconnu : <id>` | no such id |
| `Système de cosmétiques indisponible.` | the managers are not initialised — the mod failed to start cleanly |

### `/cosmetics grantall <player>` — level 2

Grants every **enabled** catalogue entry at once and reports the count. Disabled
entries are skipped. There is no `revokeall` counterpart; undoing this means one
`revoke` per id.

```text
Accordé 24 cosmétiques à Alex
```

### `/cosmetics revoke <player> <id>` — level 2

Removes ownership. If the target had that cosmetic equipped, it is also unequipped
and the visual refreshed, so nobody keeps wearing something they no longer own.
Revoking an id the player never owned is accepted silently and still reports success.

---

## `/mount`

```mcfunction
/mount
/mount summon
/mount dismiss
```

Alias: `/monture`. No arguments, no permission requirement, player-only.

Bare `/mount` toggles: it summons the equipped `MOUNT` cosmetic if none is out, and
dismisses it otherwise. `summon` and `dismiss` force one direction, which is what you
want in a command block or a macro where the current state is unknown.

A mount is summoned on demand and never automatically. Equipping one in
`/cosmetics` only selects which mob appears. `MountManager` recalls the mount when
its owner enters combat — behaviour described in
[cosmetics and wallets](cosmetics.md#mounts).

All feedback for this command comes from `MountManager` and is French-only literal
text.

---

## Commands this mod does not have

Worth stating, because the shape of the mod invites the assumption:

- no command creates, edits or prices a product
- no command lists or re-delivers an order
- no command reads or writes the Stripe keys

All three are dashboard-only. If CheeseDash is not running, the store has no admin
surface at all.

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
