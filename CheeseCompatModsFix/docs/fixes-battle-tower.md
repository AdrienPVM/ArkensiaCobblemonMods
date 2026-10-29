[← CheeseCompatModsFix](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](fixes-battle-tower.fr.md)

# Fix catalogue — Battle Tower

> ⚠️ Every fix on this page reaches into another author's code, by mixin or by
> reflection, against **one** version of it. Read the version line before you assume
> anything here applies to your server.

**Target mod:** Cobblemon Battle Tower (`cobblemon_battle_tower`).
**Version compiled against:** `1.7.11+1.6.1` (Fabric), Modrinth version id `NRBIX8Ty`
— the coordinate is in `build.gradle`.
**Inert when absent:** yes, all of them. `CheeseCompatMixinPlugin` returns `BT_LOADED`
for any mixin whose class name contains `BattleTower`, `TeamSnapshot`, `NetworkHelper`,
`TeamFullRestore` or `BattleArenaPosition`, and `BattleTowerDisconnectFix.register()` is
behind an `isModLoaded("cobblemon_battle_tower")` check in `CheeseCompatMod`. With the
mod absent, none of this code is ever reached.

This is the largest family in the mod — twelve fixes — because the Battle Tower holds a
player's whole party hostage for the duration of a run. It copies the party, forces
every Pokémon to a fixed level, and restores the original afterwards. Anything that
interrupts that cycle (a disconnect, a crash, a forfeit, a second battle started too
fast) leaves a player with the wrong party, and "wrong party" on a live server means a
support ticket about lost Pokémon.

---

## 1. Academy presence forced to true

**Symptom.** NPC trainers in the tower fight at their own levels instead of the level
the floor is supposed to force. A floor meant to be a flat Level 50 match is not.

**Cause.** `BattleTowerManager.isAcademyPresent()` gates the NPC level-forcing path. It
returns false on a server where the mod's expected StarAcademy integration is not
detected the way it expects.

**How.** `BattleTowerManagerMixin` — `@Inject` at `HEAD` of `isAcademyPresent`,
`cancellable`, `cir.setReturnValue(true)`. The method never runs its own logic.

**Status.** Active.

This is the bluntest patch in the whole mod: a one-line forced return. It is listed
first because it is also the one most likely to be wrong on a different modpack. If
your server does not want forced NPC levels, this fix alone makes the tower behave
differently from upstream.

## 2. Starting a tower battle while already in a battle

**Symptom.** A player in a wild or trainer battle walks up to the tower, starts a
battle, and ends up in two battles at once. From the player's side the screen goes
unresponsive; from the server's side Showdown has two sessions that both think they own
the same party.

**How.** `@Inject` at `HEAD` of `BattleTowerManager.startBattle`, `cancellable`. The
handler reads Cobblemon's `BattleRegistry` by reflection (the class is resolved by name
and cached, with a `reflectionFailed` flag so a failed lookup degrades to no check
rather than to an exception per call). If the player still has a battle in the registry,
the injection treats it as stale and **force-closes it** — `BattleRegistry.closeBattle`,
or `battle.end()` if no such method is found — then lets the tower battle proceed. Only
if neither close succeeds does it set the return value to `null` and send
`cheesecompat.bt.already_in_battle`:

```text
You cannot enter the Battle Tower while in a battle!
```

**Status.** Active.

The same `HEAD` injection also handles two other guards, described next.

## 3. Starting a battle while a disk restore is still running

**Symptom.** A player reconnects after a mid-battle disconnect, runs straight back to
the tower, and the party that gets locked in is the one with the tower's forced levels
still applied. The original levels are then gone for good.

**Cause.** `BattleTowerDisconnectFix` restores a party from a disk backup over several
ticks (it waits for Cobblemon's asynchronous party loader). A new `startBattle` during
that window captures a party that is mid-rebuild.

**How.** Same `HEAD` injection on `startBattle`. It calls
`BattleTowerDisconnectFix.isRestorePending(uuid)` first, before any other check, and if
a restore is pending it cancels with `cheesecompat.bt.restore_pending`:

```text
Battle Tower team restore still in progress — please wait a few seconds before starting a new battle.
```

**Status.** Active.

## 4. Stale team lock after a forfeit

**Symptom.** A player forfeits or disconnects, comes back, and the tower refuses to
start a battle because it believes the team changed — a ghost lock that no in-game
action clears.

**How.** When the team is locked in and the player is still on floor 1 or below, the
`startBattle` `HEAD` injection compares the current party's Pokémon UUIDs against the
stored `TeamSnapshot`'s UUIDs (`cheesecompat$validateTeam`). If they differ, it
**re-locks** the team: it re-captures the snapshot from the current non-fainted party,
saves the tower data, rewrites the disk backup, and lets the battle proceed. The mixin
also keeps two `@Unique` maps — a per-player floor number and a per-player battle count,
both in-memory and lost on restart — and, from floor 2 on, applies an adaptive cooldown:
1500 ms up to floor 5, 2000 ms to floor 10, 2500 ms to floor 15, 3000 ms above. The
cooldown is a `Thread.sleep` inside `startBattle`, followed by a second stale-battle check
(and another 500 ms if one had to be closed). It gives Showdown more time to tear down its
state as a run accumulates it.

**Status.** Active.

The cooldown numbers are empirical. They were raised until the symptom stopped on one
server. There is no reason to believe they are right anywhere else.

## 5. IV and EV corruption in the team snapshot

**Symptom.** A party comes back from a tower run with rerolled IVs and zeroed EVs. This
is the worst bug in the family, because it is silent and permanent.

**Cause.** In Cobblemon 1.6.1, `Pokemon.copyFrom()` has lambdas that write IVs and EVs
in the wrong direction: instead of copying source onto target, they overwrite the
*source* with the target's freshly randomised IVs and zeroed EVs. Battle Tower calls
`copyFrom` when it captures a team.

**How.** `TeamSnapshotMixin` on `battle.tower.data.TeamSnapshot`, four injections:
`@Inject` at `HEAD` and `RETURN` of `captureTeam`, and the same pair on `getTeam`. The
`HEAD` handler saves the real IVs and EVs; the `RETURN` handler writes them back over
whatever `copyFrom` produced. `getTeam` needs the same treatment because the client UI
calls it on every refresh, so the corruption would otherwise be reapplied continuously.

**Status.** Active.

This is a patch for a bug in Cobblemon, applied at a Battle Tower call site. If you run
Cobblemon 1.7.x, where the lambda is fixed, this mixin is doing redundant work — not
harmful, but pointless.

## 6. Full team restore, not just levels

**Symptom.** A party comes back from a run with the right levels and nothing else:
held items gone, PP full or wrong, friendship reset, moves reverted, status cleared.

**Cause.** `BattleTowerManager.restoreForcedLevelsSnapshot()` only restores levels and
calls `heal()`. Everything else in the snapshot is ignored.

**How.** `TeamFullRestoreMixin`, two injection sites on `BattleTowerManager`:

- `@Inject` at `RETURN` of `lockInTeam` — serialises the `TeamSnapshot` to disk through
  `BattleTowerDisconnectFix.writeBackupFromSnapshot`, so the full team state survives a
  crash mid-battle. The file is deleted only after a restore has provably succeeded.
- `@Inject` at `HEAD` of `restoreForcedLevelsSnapshot` — rebuilds each party Pokémon in
  place via `Pokemon.loadFromNBT`, the same code path Cobblemon uses to load from disk,
  from the in-memory snapshot first and from the disk backup if that fails. It logs the
  outcome, deletes the backup on success, and force-closes any battle still registered
  for the player. It does **not** cancel: Battle Tower's own level restore and `heal()`
  still run afterwards as a safety net. A `RETURN` injection only clears the CobbleCap
  bypass set at `HEAD`.

Offline players are skipped on purpose: the reconnect path belongs to
`BattleTowerDisconnectFix`, which reads the untouched backup file instead.

**Status.** Active.

## 7. Disconnect and reconnect mid-battle

**Symptom.** A player disconnects during a tower battle and comes back with the tower's
forced levels as their real levels. Or with an empty party. Or with the party they had
three runs ago.

**How.** `BattleTowerDisconnectFix` — a plain event handler class, not a mixin:

| Hook | What it does |
|---|---|
| `ServerLifecycleEvents.SERVER_STARTED` | sweeps backups older than 30 days (`BACKUP_MAX_AGE_MS`) |
| `ServerPlayConnectionEvents.DISCONNECT` | if Battle Tower still holds forced-level data for the player, calls its `restoreForcedLevels` right away |
| `ServerPlayConnectionEvents.JOIN` | the same in-memory restore, then queues the disk restore if a backup file exists |
| `ServerTickEvents.END_SERVER_TICK` | drives the restore loop |

Backups live in `config/cheesecompat_bt_backups/<uuid>.dat`, written with
`NbtIo.writeCompressed` to a `.dat.tmp` and renamed atomically, so a crash during the
write cannot leave a half-file. The restore loop retries every `WAIT_INTERVAL_TICKS`
(20 ticks, one second) up to `MAX_ATTEMPTS` (60, so one minute) and waits until
**every** UUID in the backup is present in the live party. That condition exists
because Cobblemon loads a party asynchronously: restoring too early writes into a list
that is still being filled. After 60 attempts it stops waiting and restores whatever it
can find. The backup is deleted when every UUID matched and reloaded — but also when
that last-chance partial restore fails, when the file is unreadable, and when it has no
`team` key or an empty team. It is **kept** only if the party never loaded at all within
the minute, or if a restore fails before the give-up point.

A missing `team` key and an empty team list are logged differently (an `ERROR` naming a
possible schema drift, a `WARN` for the empty list), but both end with the backup being
deleted.

**Status.** Active.

The class also reaches into `com.cobblecap.CobbleCapMod` by reflection when
[CobbleCap](../../CobbleCap/) is installed, to suspend level-cap enforcement while the
tower forces levels. That interaction is in this repository, not third-party, but it is
still reflection against a name.

## 8. Arena teleport offsets

**Symptom.** Players and NPC trainers spawn in the wrong places in a custom arena — the
player five blocks behind where the arena was anchored, the NPC five in front of it,
both standing in the middle of nothing.

**Cause.** `BattleArenaService.buildPositions()` treats the configured anchor as the
*centre* of the arena: the player is placed at `anchor.offset(facing, -5)` and the NPC
at `anchor.offset(facing, 5)`.

**How.** `BattleArenaPositionMixin` — two `@ModifyConstant` on `buildPositions`,
changing the constant `-5` to `0` (player spawns at the anchor) and `5` to `18` (NPC
spawns on the far platform, 18 blocks away). Coop offsets, ±2 blocks sideways, are
untouched.

**Status.** Active.

⚠️ `18` is the distance between the platforms in **one specific arena build**. On any
other arena this fix puts the NPC in a wall. It is a hard-coded number for one map,
documented here as such.

## 9. Invisible NPC Pokémon

**Symptom.** The battle starts, the health bar for the NPC's Pokémon appears, and the
Pokémon itself never does. The battle is then stuck: the player cannot target something
that was never rendered.

**Cause.** A direct consequence of fix 8. Moving the NPC to anchor + 18 can put it in a
different chunk from the anchor, and `BattleTowerManager.ensureChunkLoaded` only loads
the anchor's chunk. The `ClientboundAddEntityPacket` for the spawned Pokémon is then
dropped by the entity tracker because the chunk is outside the player's tracking
radius.

**How.** `BattleTowerChunkPreloadMixin` — `@Inject` at `HEAD` of `createTrainer`,
force-loading a 3×3 chunk square around the midpoint between player and NPC. Cost is
one force-load per battle creation; the comment in the code puts the TPS spike at 50 to
100 ms and calls it acceptable. Failures are caught and logged at `ERROR` without
breaking the battle.

**Status.** Active.

## 10. Alt-form Pokémon rendered wrong in the party screen

**Symptom.** In the tower's party-select screen, a regional variant, a mega or an
origin form renders as the base species. Shiny still shows; everything else is lost.

**Cause.** `BattleTowerScreen.renderPokemonInSlot` passes only `Set.of("shiny")` (or an
empty set) to `FloatingState.setCurrentAspects()`. The real aspects never crossed the
network: `BattleTowerNetwork$PartyEntry` carries a slot, a `speciesId` and the shiny
flag, and no aspects.

**How.** `client.BattleTowerScreenAspectMixin`, client-side only. An `@Inject` at
`HEAD` of `renderPokemonInSlot` to establish which slot is being drawn, a `@ModifyArg`
on the `setCurrentAspects` call to merge in the real aspects read from the Cobblemon
client party mirror, and an `@Inject` at `RETURN` to clean up. On any failure it returns
the original broken set, so the worst case is the existing behaviour.

Purely visual. No Pokémon object is mutated.

**Status.** Active.

## 11. Forced levels not restored at shutdown

**Symptom.** Stop the server while someone is in a tower battle; their party keeps the
forced levels forever.

**Cause.** `BattleTower.onServerStopping` clears its maps but never calls
`restoreForcedLevels`.

**How.** `BattleTowerShutdownMixin` — `@Inject` at `HEAD` of `onServerStopping`, so the
restore runs while players are still online and their parties still writable.

**Status.** Active.

## 12. BP shop: quantity overflow and silently lost BP

**Symptom.** Two separate bugs with the same shape — BP disappears and nothing arrives.

The first is an exploit in the stock Battle Tower: a modified client can send an
out-of-range quantity, which can credit BP instead of debiting it and freeze the server.
The patch blocks out-of-range quantities.

The second was observed in play: a shop entry whose Minecraft item id is not registered
on this server. `handlePurchase` debits the BP before it resolves the item, and
`Registries.ITEM.get(id)` on an unregistered id returns `minecraft:air` rather than
`null` in a `DefaultedRegistry`. The empty stack inserts "successfully", no exception is
thrown, no rollback happens. BP gone, nothing delivered, no message.

**How.** `NetworkHelperImplMixin` — `@Inject` at `HEAD` of `handlePurchase`,
`cancellable`. A quantity outside `[1, 64]` is cancelled outright, with no message; then
it validates the item before any debit, and on failure tells the player, in hard-coded
French, `[Battle Tower] <reason>. Achat annulé, aucun BP débité.` Cancelling at `HEAD`
means the whole method, debit included, never runs. If the validation itself throws, the
purchase is let through (fail-open).

The subtlety worth repeating, because the first attempt got it backwards: the `itemId`
parameter of `handlePurchase` is the **`ShopItem` slug** (`ShopItem.id()`), not a
Minecraft item id. The real item id is `ShopItem.itemId()`. Validating the parameter
directly would have rejected every legitimate purchase. The fix resolves the `ShopItem`
by slug, then validates *its* `itemId()`. Command items, whose reward is a command and
not an item, are exempt from the registry check.

**Status.** Active.

---

## What this family tells you

The Battle Tower fixes are the clearest illustration of the pattern this whole mod
repeats: a third-party mod holds mutable player state across an asynchronous boundary,
and every fix here is either a guard on entry, a snapshot taken earlier, or a restore
done later. Three of them (3, 6, 7) only work because they agree on one backup file and
one "restore pending" flag. If you remove one, check what the other two assumed.

---

← [Configuration](configuration.md) · [Fix catalogue — Cobblemon and battles](fixes-cobblemon.md) →
