[← CheeseFixQuests](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](finalizer.fr.md)

# The finalizer sweep

The redirects described in [architecture](architecture.md) send progress to the right
place. They do not fix a second, unrelated family of problems: progress that is written
correctly and then never *acted on*. A task sits at 40 out of 40 and the quest stays
locked. A quest's last task completes and the auto-claim reward never fires. The player
disconnects, reconnects, and everything resolves itself.

`QuestFinalizerHook` is the answer to that class of bug. Every 200 ticks it walks the
online players and nudges FTB Quests through the steps it skipped, on the server thread,
without anyone having to reconnect.

This is the most invasive part of the mod and the part most likely to surprise you. Each
of its four phases has its own kill-switch in
[`cheesefixquests.json`](configuration.md#the-four-finalizer-phases).

---

## Why progress gets stuck

Two mechanisms, both inside FTB Quests.

**`TeamData.setProgress` short-circuits on an unchanged value.** When the new count equals
the previous count, it returns early and skips the
`markTaskCompleted → task.onCompleted → completed.put` cascade. So a task can hold
`taskProgress[id] = max` while `completed[id]` is simply missing. `task.isCompleted(td)`
reads the `completed` map, so it answers `false` forever, and `Quest.isCompletedRaw` —
which AND-checks every task — answers `false` too. The quest is finished and the system
cannot see it.

**Some writers bypass the cascade entirely.** `KeyValueReward.claim` calls
`TeamData.addProgress` directly without firing task completion. A gate quest counting 70
child rewards can reach its threshold without a single completion event.

Both states are invisible to a player: the book shows a full bar on a quest that will not
hand over its reward.

---

## The four phases

All four run inside `QuestFinalizerHook.tick`, registered on
`ServerTickEvents.END_SERVER_TICK`. The hook counts ticks and does nothing at all on 199
out of every 200 — `RESCAN_INTERVAL_TICKS = 200`, so once every 10 seconds. When FTB
Quests is not loaded, the very first check (`FTBQ_LOADED`) returns and the hook costs
nothing.

The whole sweep stops permanently once `ServerLifecycleEvents.SERVER_STOPPING` fires. That
matters: without it the last sweep could mutate `TeamData` while the save thread was
reading it.

### Phase A — inventory rescan

`enableInventoryRescan`, default `true`.

Calls `FTBQuestsInventoryListener.detect(player, ItemStack.EMPTY, 0L)` by reflection, for
every online player. That is FTB's own passive item-task scanner; re-firing it re-validates
`ItemTask`s for items that entered the inventory through a path the vanilla slot listener
never saw — mod APIs, direct NBT inserts, Cobblemon trades.

The method is resolved lazily in `tryArmPhaseA()` and retried on every sweep until it
succeeds, which is why a transient failure during boot is logged at WARN once and then
forgotten:

```text
[CheeseFixQuests] Phase A (detect) armed — sweep every 200 ticks.
```

### Phase B — per-task `submitTask` sweep

`enableTaskSubmitSweep`, default `true`.

For every quest whose tasks the team can start, and every task with `checkOnLogin() ==
true`, call `task.submitTask(td, player)`. That is the same call FTB makes on login, so it
reaches the non-item task types: kill, biome, location, stat, advancement, dimension,
observation, custom. Already-completed tasks exit immediately on their own `isCompleted`
guard.

A task that throws is swallowed and the sweep continues. One broken quest definition
should not stop the rest.

### Phase C — the repair pass

`enableQuestFinalization`, default `true`.

The substantial one. It resolves the player's `TeamData`, then walks every quest in three
sub-steps, skipping quests already completed and quests whose dependencies are not met.

**C0 — direct `ItemTask` inventory scan.** FTB's inventory listener is attached to the
player's own screen handler, not to a chest's, so a shift-click out of a chest is never
seen by it at all. Phases A and B both end up going through `setProgress`, which
short-circuits. So C0 counts the matches itself: it iterates the player's inventory and
calls `ItemTask.test(stack)` — the task's own predicate, so NBT-component matching behaves
exactly as FTB intends — and sums the stack counts.

With `matches` in hand:

- `matches >= max` → `markTaskCompleted(task)` directly
- `matches > current` → `setProgress(task, matches)`

Both under `synchronized (td)`, with the completion and dependency checks re-tested inside
the lock.

**C1 — orphaned task progress.** For every task, item or not: if `progress >= maxProgress`
but `isCompleted(task)` is false, call `markTaskCompleted(task)`. This is precisely what
the short-circuited cascade should have done. `markTaskCompleted` synchronously fires
`task.onCompleted`, which writes the missing `completed[taskId]` and, if that was the
quest's last task, continues into `Quest.onCompleted → checkAutoCompletion → claimReward`.

**C2 — catch-all finalise.** If after C0 and C1 all tasks are complete but the quest
itself is still not marked, build a `QuestProgressEventData` and call
`quest.onCompleted(ev)` once, under `synchronized (td)`. FTB's own `!isCompleted` guard at
the head of `onCompleted` makes this idempotent.

Within one sweep, a `Set<Long>` keyed on `quest.id ^ teamId.getMostSignificantBits()`
stops the same (quest, team) pair from being considered twice when several members of one
team are online. The set is cleared at the start of each sweep.

### Phase D — Cobblemon Quests Pokédex resync

`enableCobblemonPokedexResync`, default `true`. Does nothing unless `cobblemon_quests` is
loaded.

Re-fires `CobblemonQuestsEventHandler.triggerPokeDexUpdate(uuid)` for every online player.
That handler's natural triggers are `PLAYER_JOIN` and the Pokédex-changed events; if one is
missed — mod load order, a race with another listener — the player's Cobblemon Quests
progress falls behind until their next login. Re-firing it only calls
`increaseHaveRegistered → TeamData.addProgress`, which phase C then finalises.

Phase D is reached entirely by reflection, and it distinguishes two kinds of failure.
`eventHandler` being null is transient — Cobblemon Quests' static initialiser may not have
run yet — so it retries next sweep. A missing class, field or method is permanent: the flag
`phaseDPermanentlyFailed` is set, one WARN is logged, and it never retries.

```text
[CheeseFixQuests] Phase D (Cobblemon Pokédex resync) armed.
[CheeseFixQuests] Phase D permanently disabled (class shape mismatch): java.lang.NoSuchMethodException: …
```

The second line is what you will see when Cobblemon Quests updates and moves that method.
It is a warning, not a crash: the rest of the mod keeps working.

---

## The anti-duplication contract

The sweep calls `Quest.onCompleted`, which can claim rewards. A reward that is claimed
twice gives the item twice, or runs its command twice. So the sweep has to be safe against
itself, against FTB's own event-driven path firing for the same quest in the same tick,
and against other threads.

Three guarantees stack up:

1. **`TeamDataClaimRewardSyncMixin`** makes the check-and-set in `TeamData.claimReward`
   atomic. `claimedRewards` is a fastutil `Object2LongMap` and is not thread-safe; in the
   original bytecode the `containsKey` guard and the `put` are separated, so two threads
   landing on the same key can both pass the guard and both run the actual give. The mixin
   redirects the `containsKey` call to a `synchronized (map)` block that checks and
   reserves the key in one step. Every reward claim in the game, from any source, goes
   through it.
2. **`synchronized (td)`** around the finalisation in phases C0, C1 and C2 serialises this
   hook against itself.
3. **FTB's own `!isCompleted` guards**, re-tested inside each lock.

The hook never claims a reward directly. The only path to a claim is FTB's own cascade,
which is covered by point 1.

This is also why `enableClaimRewardLock` should stay `true`. Turning it off restores the
unguarded original behaviour, and with the sweep running on top of it the race window is
wider than stock FTB Quests, not narrower. The config comment names the incident it was
written for: a badge reward given twice, two seconds apart, with a single entry in the
persisted data because the first thread's write overwrote the second's.

---

## The audit log

`auditQuestEvents`, default `false`, writes
`config/cheesefixquests_audit.log`. Turn it on when you have a duplicate-reward report or
a quest that will not finalise, and off again once you are done — the sweep emits a line
per player per sweep, so the file grows steadily even when nothing is wrong.

Each line is a timestamp with millisecond precision, an event name, then
`key=value` fields. The thread name is on every line, which is the point: a genuine
cross-thread race can be reconstructed from this file alone.

| Event | Emitted when |
|---|---|
| `PHASE_C_SWEEP` | end of phase C for one player — always, even with all-zero counters, so you can prove the sweep is running |
| `ITEM_REPAIR` | C0 found a full `ItemTask` and called `markTaskCompleted` |
| `ITEM_SET` | C0 found partial progress and pushed it with `setProgress` |
| `TASK_REPAIR` | C1 repaired an orphaned task |
| `FINALIZE` | C2 called `Quest.onCompleted` |
| `FINALIZE_SKIP` | phase C threw on one quest; carries the exception class and message |
| `CLAIM_RESERVED` | the claim lock reserved a reward key — the claim is going through |
| `CLAIM_BLOCKED` | the claim lock saw the key already present — a second claim was stopped here |
| `POKEDEX_RESYNC` | phase D fired for one player |

Quest and task ids are printed as uppercase hex, matching the quest book and the `.snbt`
files.

```text
2026-10-29 14:02:11.418 PHASE_C_SWEEP thread=Server thread player=Steve/… visited=812 itemScans=14 itemRepairs=0 itemSets=1 orphanRepairs=0 finalizes=0
2026-10-29 14:02:11.420 ITEM_SET thread=Server thread quest=3C1A4E7B9D2F6058 task=5A77… team=… player=Steve old=3 new=7
2026-10-29 14:07:52.004 CLAIM_BLOCKED thread=CobbleCap-Save key=QuestKey[…]
```

A `CLAIM_BLOCKED` on a thread that is not `Server thread` is the race the mixin exists for,
caught in the act. That is the line to paste into a bug report.

Writes are serialised on one lock and flushed per line, so a crash keeps the trail. The
writer is opened lazily on the first event and closed by both
`ServerLifecycleEvents.SERVER_STOPPING` and a JVM shutdown hook. If opening the file fails,
audit logging disables itself after one WARN rather than retrying every line.

---

## Cost, and what to do if it hurts

The sweep is `O(online players × quests)` every 10 seconds, plus one inventory scan per
incomplete `ItemTask` per quest per player in C0. The source comment claims under a
millisecond for 50 players and 1000 quests. That claim is not independently measured here,
and C0's nested inventory scan is the part most likely to cost more than the comment
suggests on a large quest book.

If a tick-time profiler points at `QuestFinalizerHook`, disable phases from the bottom up:
`enableCobblemonPokedexResync`, then `enableTaskSubmitSweep`, then
`enableInventoryRescan`. Keep `enableQuestFinalization` — it is the one that repairs real
breakage — and keep `enableClaimRewardLock` whatever else you turn off. Every flag needs a
restart.

---

← [The migration](migration.md) · [Architecture](architecture.md) →
