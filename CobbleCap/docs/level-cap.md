[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](level-cap.fr.md)

# The level cap

This is the whole point of the mod. A player has a number, their cap, and no Pokémon they
own can go above it. The number only moves when they beat a champion. Everything else in
CobbleCap exists to decorate that one rule.

## The model

| Object | Lives in | Holds |
|---|---|---|
| `PlayerCapData` | `config/cobblecap/players.json`, keyed by UUID | `capLevel`, `championsBeaten`, `challengeBeaten`, `expLocked`, HUD preferences, challenge cooldown timestamps |
| `ChampionEntry` | `config/cobblecap/champions.json`, an **ordered** array | an rctmod trainer id, a display name, the `capGranted` on victory, and the gates |
| `CapConfig` | `config/cobblecap/config.json` | `startingCap`, the `enabled` master switch, banned species, the webhook, fireworks |

A player's row is created the first time they join (`CapListener` calls
`CapStorage.ensureExists`), with `capLevel` set to `startingCap` — **20** out of the box.

## How the cap is calculated

There is no formula. The cap is a stored integer, and it changes in exactly five ways:

1. **A champion victory.** `ChampionManager` resolves the champion's `capGranted` and calls
   `CapStorage.raiseCapLevel`. That method only ever raises: if the player's cap is already
   at or above the new value, nothing happens and it returns `false`. So beating champions
   out of order, or re-beating one, cannot lower anyone's cap.
2. **A challenge victory,** when that champion's `challengeCapGranted` is above zero — again
   raise-only. [The champion system](champions.md) covers that path.
3. **An admin setting it** — `/cobblecap set <player> <level>`, or
   `POST /api/v1/cobblecap/players/{uuid}/cap`. Both write the value directly with no
   raise-only guard, so an admin can lower a cap.
4. **A reset** — `/cobblecap reset <player>` puts it back to `startingCap`.
5. **A repair** — `/cobblecap addbeaten <player> <championId>` calls the same raise-only
   `raiseCapLevel` with that champion's `capGranted`, without any battle.

The order of `champions.json` is the progression. `ChampionConfig.nextUnbeaten` returns the
first entry whose `id` is not in the player's `championsBeaten` list, and that entry is the
player's current gate: the champion whose name the HUD shows as *Next*, and the only one
they are allowed to fight.

## How the cap is enforced

Four independent layers, because each one catches something the others miss. Three of them
live on the XP path and one on the battle path.

### 1. The Cobblemon event hook — `CobblemonHook` + `CapManager`

`CobblemonHook.tryInit` runs on `SERVER_STARTED` and subscribes to Cobblemon's event bus
**entirely through reflection**: `Class.forName` on
`com.cobblemon.mod.common.api.events.CobblemonEvents`, then a `java.lang.reflect.Proxy`
implementing `kotlin.jvm.functions.Function1` handed to the observable's `subscribe`
method. No compile-time Cobblemon dependency is involved on this path.

It tries several field names for each event and takes the first that resolves:

| Purpose | Field names tried, in order | Handler |
|---|---|---|
| Battle XP | `EXPERIENCE_GAINED_EVENT_PRE`, `EXP_GAINED`, `EXPERIENCE_GAINED` | `CapManager.handleXpEvent` |
| Exp candy | `EXPERIENCE_CANDY_USE_PRE`, `EXPERIENCE_CANDY_PRE` | `CapManager.handleXpEvent` |
| Battle start | `BATTLE_STARTED_PRE`, `PRE_BATTLE_START`, `BATTLE_START_PRE` | the inline battle guard |
| Battle victory | `BATTLE_VICTORY`, `BATTLE_WON` | `ChampionManager.handleBattleVictoryEvent` |

Each one logs what it bound to, or warns that it found nothing:

```text
[CobbleCap] Cobblemon XP hook registered on field: EXPERIENCE_GAINED_EVENT_PRE
[CobbleCap] Cobblemon candy XP hook registered on field: EXPERIENCE_CANDY_USE_PRE
[CobbleCap] Battle pre-check hook registered on field: BATTLE_STARTED_PRE
[CobbleCap] Battle victory hook registered on field: BATTLE_VICTORY
```

A `Could not find … field` warning means Cobblemon renamed something and that layer is off.
That is the line to grep for after any Cobblemon update.

`CapManager.handleXpEvent` then does its own reflection on the event object: pull the
Pokémon, resolve the owning player (from the event, else from the Pokémon), read the level,
read the Pokémon UUID for rate limiting. If the level is **at or above** the cap — note the
boundary, `currentLevel < cap` is the only passing case — it cancels. Cancelling itself is
two strategies: call `cancel()`/`setCanceled(true)` on the event, and if no such method
exists, set the XP amount to `0` through `setExperience`/`setExpToAdd`/`setExp`/`setAmount`.

Every step is wrapped so that a reflection failure logs at debug and **lets the XP
through**. The mod fails open by design: a Cobblemon rename degrades the cap, it does not
crash the server.

### 2. `PokemonAddExperienceMixin` — the shaping layer

This one mixes into `com.cobblemon.mod.common.pokemon.Pokemon` (compile-time, `remap =
false`) at the head of `addExperienceWithPlayer` and `addExperience`. Its job is not to
block but to **trim**, so a player under the cap never sees a level-up message that gets
rolled back.

```java
int xpForCap  = pokemon.getExperienceGroup().getExperience(cap);
int headroom  = xpForCap - currentXp - 1;   // -1 keeps the mon one XP short of the cap level
```

- At or above the cap, or `headroom <= 0`: the call returns a synthetic
  `AddExperienceResult(lvl, lvl, Set.of(), 0)`. Zero XP, no level change, no message.
- Below the cap but overshooting: the amount is reduced to `headroom` and the real method
  is re-invoked with the smaller number. A `ThreadLocal` re-entry guard
  (`cobblecap$capping`) stops that second call from being trimmed again.
- Otherwise it does nothing and the original amount goes through.

The `-1` is the reason a capped Pokémon sits at *cap − 1* with a nearly-full XP bar rather
than exactly at the cap. That is deliberate: landing on the cap level itself would fire
Cobblemon's level-up path.

### 3. `PokemonSetLevelMixin` — the safety net

Head injection on `Pokemon.setLevel(int)`, cancellable. This catches anything that sets a
level without going through XP at all — the mod's own comment names Cobblemon Utility+'s
MasterCandyItem as the case that motivated it.

Rules, in order:

1. If `CobbleCapMod.LEVEL_FORCE_ACTIVE` is true on this thread, return immediately. That
   `ThreadLocal<Boolean>` is public API for other mods; CheeseCompat flips it so Battle
   Tower can force levels above the cap.
2. `newLevel <= currentLevel` returns. Decreases are always allowed, for admin and debug.
3. The owning player is resolved by **scanning methods by return type** — every
   zero-argument method of the Pokémon class whose return type is assignable to
   `ServerPlayerEntity` is invoked until one yields a player. Names are not used, because at
   runtime a Yarn method name is an intermediary name and would not match.
4. XP lock set: clamp XP to the current level, cancel.
5. Cap system enabled and `newLevel > cap`: clamp XP to the cap, cancel.

`clampExperience` sets the raw XP down to `getExperienceGroup().getExperience(targetLevel)`
through `setExperience$common`, reflectively. Without it the internal XP total keeps
climbing behind a blocked `setLevel`, and the next XP trigger produces repeated level-up
messages and a visually wrong bar.

### 4. `BATTLE_STARTED_PRE` — the party check

Before a battle begins, the hook walks the actors and, for any player actor:

- `ChampionManager.findBannedSpeciesInParty` — if anything in `bannedSpecies` is in the
  party, the battle is cancelled with `cobblecap.msg.battle_cancelled_banned`.
- `ChampionManager.playerPartyViolatesCap` — if any party member is above the player's cap,
  the battle is cancelled with `cobblecap.msg.battle_cancelled_cap`.

The cap part is skipped while `LEVEL_FORCE_ACTIVE` is set. The banned-species part is not.

That same handler is where champion ordering and the challenge swap are enforced; see
[the champion system](champions.md#the-pre-battle-gate).

## What a capped player sees

An action-bar message, rate-limited to one per **30 seconds** per (player, Pokémon) pair by
`CapManager.XP_MSG_COOLDOWN_MS` — but in practice **only when an exp candy is used** on a
Pokémon at or above the cap. Cobblemon fires `EXPERIENCE_GAINED_EVENT_PRE` from inside
`Pokemon.addExperience`, and layer 2 has already returned zero XP at the head of that method
for a capped Pokémon, so battle XP is refused without any message. The candy event fires in
the candy item, before any XP is added, so it does reach `CapManager`. Two variants, from
`assets/cobblecap/lang/en_us.json`:

```text
Pikachu cannot gain XP — level cap is 45. Next champion: Erika
```

```text
Pikachu cannot gain XP — level cap is 45. No more champions to beat!
```

The second appears when `nextUnbeaten` returns nothing. The Pokémon's name comes from
`getDisplayName()`, falling back to the species name with its first letter capitalised, and
finally to the literal string `your Pokemon` if both reflection attempts fail. Seeing
`your Pokemon cannot gain XP` in chat is a reliable sign that Cobblemon's getters moved.

Alongside that, the [client HUD](client.md) shows `Cap: 45` and `Next: Erika` permanently.

## The XP lock is a separate switch

`expLocked` on `PlayerCapData` blocks **all** XP gain for that player, at any level, and it
is checked *before* the `enabled` flag in all three enforcement layers. Turning the cap
system off with `/cobblecap disable` does not release an XP lock.

It is enforced three ways: `CapManager` cancels the event and sends
`cobblecap.msg.xp_locked`, `PokemonAddExperienceMixin` returns an effective cap of `0`
which short-circuits to a full block, and `PokemonSetLevelMixin` cancels every increase.

Players toggle it themselves with `/explock`; admins set it for someone else with
`/explock <player>` or the dashboard route. See [commands](commands.md#explock).

## Overshoot XP is discarded, not banked

There is no reservoir. XP beyond the cap is not stored anywhere for later — the trimming
layer reduces the amount to `headroom` and the surplus is simply never added, and the
clamping layer actively writes the Pokémon's XP total *back down* to the threshold. When a
champion victory raises the cap, Pokémon resume from where they were stopped. They do not
jump several levels from banked experience.

This is a design decision and it is worth stating to players, because the usual
expectation from other cap mods is the opposite.

## What `enabled: false` turns off

`/cobblecap disable` (and `/cobblecap enable`) flips `CapConfig.enabled` and saves. With it off:

- XP cap enforcement stops in all three layers
- the `BATTLE_STARTED_PRE` handler returns at its first line, so banned species and party
  cap checks stop too
- `UseEntityCallback` stops intercepting rctmod NPCs, so champions become ordinary trainers
- champion victory detection stops, so caps stop rising

What keeps working: XP locks, the [statue subsystem](statues.md), the chronos, the
scoreboards and the dashboard. The flag is an XP-and-champions switch, not a mod switch.

---

← [Getting started](getting-started.md) · [The champion system](champions.md) →
