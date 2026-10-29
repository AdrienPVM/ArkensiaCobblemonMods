[← CheeseFixQuests](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

One file: **`config/cheesefixquests.json`**, relative to the server's working directory.
It is created with every default on the first start if it does not exist.

Nine keys, all booleans. There is nothing to tune — each one turns a piece of the mod on or
off. On a healthy server you never touch any of them; they exist so that a regression can
be isolated to one mechanism without rebuilding the jar.

> ⚠️ Nothing here reloads. `CheeseFixQuestsConfig.load()` runs once in `onInitialize` and
> has no other caller. Edit the file with the server stopped, or restart after editing.

---

## The main switch

| Key | Type | Default | What it does |
|---|---|---|---|
| `forceIndividualProgress` | boolean | `true` | the mod. When `false`, every redirect falls through to FTB Quests' original behaviour |

### `forceIndividualProgress`

Checked at the top of all six team-lookup `@Redirect`s, both `isPlayerOnTeam` overrides
(server and client), both `mergeData` blocks, the finalizer's team resolution and the
auto-migration. Setting it to `false` makes the jar
inert: FTB Quests goes back to party-keyed progress, `mergeData` works again, and the
finalizer sweep resolves the *effective* team rather than the player team — so it keeps
running, against party data.

One thing it does **not** disable: `TeamDataClaimRewardSyncMixin`. The reward claim lock
has its own flag, `enableClaimRewardLock`, and stays active regardless.

Turning this off does not undo a migration. Per-player records stay on disk, invisible,
until you turn it back on. See [migrating back out](migration.md#migrating-back-out).

---

## Migration state

| Key | Type | Default | What it does |
|---|---|---|---|
| `migrationDone` | boolean | `false` | whether the one-shot progress migration has run |

This is state, not a setting, and it shares a file with the settings. `false` means the
migration will run automatically the next time a player logs in. The mod sets it to `true`
and saves the file immediately afterwards, and `/cheesefixquests migrate` and `remigrate`
do the same.

Setting it back to `false` by hand re-arms the automatic migration for the next login.
Read [the migration](migration.md) first — a second run is additive and will not corrupt
anything, but it can copy back progress you deliberately removed.

---

## Logging

| Key | Type | Default | What it does |
|---|---|---|---|
| `debugLogging` | boolean | `false` | one INFO line per redirect |
| `auditQuestEvents` | boolean | `false` | append every finalisation, claim and resync to `config/cheesefixquests_audit.log` |

### `debugLogging`

Verbose. Every team-lookup redirect logs a line, and the inventory listener fires on every
inventory mutation of every player. On a populated server this produces thousands of lines
a minute. Turn it on to confirm that a specific redirect is reached, read the handful of
lines you need, turn it off.

With it off, the mod still logs once per player the first time
`FTBQuestsInventoryListenerMixin` redirects for them:

```text
[CheeseFixQuests] InventoryListener.detect: redirected to PlayerTeam for Steve (first redirect for this player; total redirects since startup: 1423)
```

That rate-limited line is usually all the proof you need that the mixin is live.

### `auditQuestEvents`

The forensic trail, documented in full under
[the audit log](finalizer.md#the-audit-log). Off by default because the sweep writes a
`PHASE_C_SWEEP` line per online player every 10 seconds whether anything happened or not.
Turn it on when investigating a duplicated reward or a quest that will not complete, and
off again afterwards.

---

## The four finalizer phases

Each phase of [the finalizer sweep](finalizer.md) has its own kill-switch. They are
independent: disabling one leaves the others running.

| Key | Type | Default | What it does |
|---|---|---|---|
| `enableInventoryRescan` | boolean | `true` | phase A — re-fire `FTBQuestsInventoryListener.detect` for every online player |
| `enableTaskSubmitSweep` | boolean | `true` | phase B — call `submitTask` on every task whose `checkOnLogin()` is true |
| `enableQuestFinalization` | boolean | `true` | phase C — scan item tasks, repair orphaned task progress, finalise completed-but-unmarked quests |
| `enableCobblemonPokedexResync` | boolean | `true` | phase D — re-fire Cobblemon Quests' `triggerPokeDexUpdate`; inert without that mod |

Phase C is the one that repairs real breakage; the other three are belt-and-braces. If the
sweep ever needs trimming for tick time, turn them off in the order D, B, A and leave C.

Phase D additionally disables itself permanently, with one WARN, if Cobblemon Quests'
class shape no longer matches. You do not need to set the flag in that case.

---

## The claim lock

| Key | Type | Default | What it does |
|---|---|---|---|
| `enableClaimRewardLock` | boolean | `true` | makes the check-and-set inside `TeamData.claimReward` atomic |

> ⚠️ Keep this `true`. `TeamData.claimedRewards` is a fastutil map with no thread safety,
> and in FTB's original bytecode the "already claimed?" check and the write that records
> the claim are separate steps. Two threads reaching the same reward key between those two
> steps both pass the check and both run the give. Setting this to `false` restores exactly
> that window.

The flag exists only so a regression traced to this mixin can be switched off without
rebuilding. The incident it was written for is recorded in the source: a badge reward given
twice and its attached command run twice, two seconds apart, with a single entry in the
persisted data because one thread's write overwrote the other's.

Unlike the other flags, this one is honoured even when `forceIndividualProgress` is
`false`. The race it closes is in stock FTB Quests, not in this mod.

---

## Complete example

Every key, at its default. This is byte for byte what the mod writes on first start, with
annotations added.

```json
{
  "forceIndividualProgress": true,    // the mod itself; false makes the jar inert
  "debugLogging": false,              // one line per redirect — very noisy
  "migrationDone": false,             // flips to true after the migration runs
  "enableInventoryRescan": true,      // phase A
  "enableTaskSubmitSweep": true,      // phase B
  "enableQuestFinalization": true,    // phase C — the one that repairs stuck quests
  "enableCobblemonPokedexResync": true, // phase D — needs Cobblemon Quests
  "enableClaimRewardLock": true,      // anti-duplication lock; keep true
  "auditQuestEvents": false           // forensic log, for investigations only
}
```

JSON has no comments. Copy the structure, not the `//` lines.

A missing key falls back to the default declared in `ConfigData`, which is the same value
shown above. An unparseable file is logged at ERROR, the defaults are used, and the file is
overwritten with them:

```text
[CheeseFixQuests] Failed to load config, using defaults
```

---

← [Commands](commands.md) · [The migration](migration.md) →
