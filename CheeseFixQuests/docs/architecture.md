[← CheeseFixQuests](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

CheeseFixQuests is nine mixins and two helper classes. It registers no block, no item, no
network packet and no dashboard module. Everything it does, it does by standing in front
of one specific question that FTB Quests asks over and over: *which team's data belongs to
this player?*

---

## What FTB Quests does by default, and why it is a problem

FTB Quests stores progress per team, in a `TeamData` object keyed by a team UUID. FTB Teams
gives every player a permanent **player team** of their own, and lets them additionally
join a **party team**. When a player is in a party, FTB Teams' `getTeamForPlayerID(uuid)`
returns the party — the player's *effective* team — and FTB Quests therefore reads and
writes that party's `TeamData`.

That design is correct for its intended use: a modpack where a party is a co-op group with
one shared questline. It is wrong for every server where parties exist for other reasons —
land claims, chunk loading, a shared chat channel, a guild. Those players did not ask to
share a quest book, and the first time one of them kills a boss the quest vanishes from
everyone else's.

FTB Teams also exposes `getPlayerTeamForPlayerID(uuid)`, which always returns the
permanent player team regardless of parties. Almost the whole mod is one idea: find every
place FTB Quests calls the first method, and make it call the second.

---

## Scope: what is covered, and what is not

Worth being precise about, because the honest answer is "most paths, not all paths".

**Covered.** `BaseQuestFile.getTeamData(PlayerEntity)` and
`BaseQuestFile.getOrCreateTeamData(Entity)` are the funnels through which most of FTB
Quests reaches a player's data. Redirecting the lookup inside them makes **all quest types
and all task types** individual — there is no per-task-type logic anywhere in this mod.
The redirect is at the storage-resolution layer, below any notion of what kind of task is
being written.

**Not covered by that redirect, and patched separately.** Three call sites resolve the team
themselves instead of going through `BaseQuestFile`, and each needed its own mixin:

- `FTBQuestsInventoryListener.detect` calls the **player-keyed** overload
  `getTeamForPlayer(ServerPlayer)`, not the UUID-keyed one. This is FTB's highest-frequency
  write path — every pickup, craft, shift-click, drop and container move — so missing it
  leaked item-task progress into the party on a timescale of minutes.
- `ServerQuestFile.playerLoggedIn` takes the team from the login event, not from a lookup.
- Cobblemon Quests and ExtraQuests each call FTB Teams directly from their own code.

**Still not covered.** Any other mod, or any future FTB code path, that resolves the
effective team on its own will keep writing to the party. There is no catch-all here. The
pattern for adding one is below under [extension points](#extension-points).

**Quests already completed by a team before you install the mod** are handled by a separate
one-shot data copy, not by the redirects. Progress is migrated, not reset — but the copy is
lossy in a specific, documented way. [The migration](migration.md) covers it in full; the
short version is that only `claimedRewards` records which player did what, so for teams
that are no longer active parties, a quest completed without claiming its reward cannot be
attributed and does not survive.

---

## Package layout

```text
com.cheesefixquests
├── CheeseFixQuestsMod.java        ModInitializer — config load, command registration,
│                                  tick hook, shutdown hook, boot log lines
├── CheeseFixQuestsConfig.java     9 static flags + Gson load/save of config/cheesefixquests.json
├── CheeseFixQuestsCommand.java    the /cheesefixquests Brigadier tree (level 4)
├── QuestMigration.java            one-shot copy of team-keyed progress into per-player storage
├── logic
│   ├── QuestFinalizerHook.java    the 200-tick, four-phase repair sweep
│   └── QuestAuditLogger.java      append-only forensic log, config/cheesefixquests_audit.log
└── mixin
    ├── BaseQuestFileMixin.java            the main team-lookup redirect
    ├── ServerQuestFileMixin.java          login sync + auto-migration + isPlayerOnTeam
    ├── ClientQuestFileMixin.java          client-side isPlayerOnTeam
    ├── TeamDataMixin.java                 blocks mergeData / mergeClaimedRewards
    ├── TeamDataAccessor.java              @Accessor interface onto TeamData's four maps
    ├── TeamDataClaimRewardSyncMixin.java  atomic check-and-reserve on claimReward
    ├── FTBQuestsInventoryListenerMixin.java  the item-task write path
    ├── CobblemonQuestsHandlerMixin.java   Cobblemon Quests' own team lookup
    └── ExtraQuestsTimerMixin.java         ExtraQuests' daily timer reset
```

15 source files, roughly 1 920 lines, of which `QuestFinalizerHook` is more than a quarter.

---

## The mixins, one by one

All nine are declared in `cheesefixquests.mixins.json`, package
`com.cheesefixquests.mixin`, compatibility level `JAVA_21`, `required: true`,
`defaultRequire: 1`. `ClientQuestFileMixin` is in the `client` list; the other eight are in
`mixins`.

> ⚠️ Every one of these targets a third-party mod's internals — classes, method
> descriptors and in two cases private fields. Nothing here is a public API. **An FTB
> Quests, FTB Teams, Cobblemon Quests or ExtraQuests update can break this mod, and the
> failure mode is a crash on startup, not a graceful degradation**, because
> `cheesefixquests.mixins.json` sets `required: true` and `defaultRequire: 1`: a redirect
> whose target method or INVOKE no longer exists fails the mixin apply.
>
> The versions this mod is verified against are the ones pinned in `build.gradle` and
> `localdeps/README.md`: **FTB Quests 2101.1.13**, FTB Teams 2101.1.2, FTB Library
> 2101.1.18, and optionally Cobblemon Quests 1.1.14 and ExtraQuests 1.5.3. Treat any other
> version as untested.

| Mixin | Target | Injection | Why |
|---|---|---|---|
| `BaseQuestFileMixin` | `BaseQuestFile` (FTB Quests) | two `@Redirect` on `TeamManager.getTeamForPlayerID` inside `getTeamData(PlayerEntity)` and `getOrCreateTeamData(Entity)` | the main fix: resolve the player team instead of the party |
| `ServerQuestFileMixin` | `ServerQuestFile` | `@Inject` HEAD on `playerLoggedIn`; `@Redirect` on `PlayerLoggedInAfterTeamEvent.getTeam()`; `@Inject` HEAD cancellable on `isPlayerOnTeam` | runs the auto-migration, syncs the right `TeamData` to the client on login, and fixes the team-membership test |
| `ClientQuestFileMixin` | `ClientQuestFile` | `@Inject` HEAD cancellable on `isPlayerOnTeam` | on the client, `KnownClientPlayer.teamId()` is the party id while `selfTeamData` carries the player-team id; the mismatch made `getOrCreatePlayerData` return empty and broke the quest book's edit mode |
| `TeamDataMixin` | `TeamData` | `@Inject` HEAD cancellable on `mergeData` and `mergeClaimedRewards` | FTB merges progress on party join and claims on party leave; with individual progress both are pure contamination |
| `TeamDataAccessor` | `TeamData` | `@Accessor` ×4 | read/write access to `taskProgress`, `started`, `completed`, `claimedRewards` for the migration, bypassing the setters and their completion cascades |
| `TeamDataClaimRewardSyncMixin` | `TeamData` | `@Redirect` on `Object2LongMap.containsKey` inside `claimReward(UUID, Reward, long)` | makes the check-and-set atomic; see [the anti-duplication contract](finalizer.md#the-anti-duplication-contract) |
| `FTBQuestsInventoryListenerMixin` | `FTBQuestsInventoryListener` | `@Redirect` on `TeamManager.getTeamForPlayer` inside `detect` | the player-keyed overload the main redirect does not cover |
| `CobblemonQuestsHandlerMixin` | `CobblemonQuestsEventHandler` (Cobblemon Quests) | `@Redirect` on `TeamManagerImpl.getTeamForPlayer` inside `getTeamData(ServerPlayerEntity)` | Cobblemon Quests resolves the team itself, so catch/defeat/evolve/trade progress bypassed every other redirect |
| `ExtraQuestsTimerMixin` | `com.vecoo.extraquests.util.Utils` (ExtraQuests) | static `@Redirect` on `TeamManager.getTeamForPlayerID` inside `questReset` | daily timers were resetting the party's empty `TeamData`, so dailies never came back |

Every redirect follows the same shape: if `forceIndividualProgress` is off, or the player
has no player team, call the original method. The mod degrades to stock behaviour rather
than to an exception.

### Remapping

Eight of the nine mixins declare `remap = false`, because their targets live in a
third-party package outside the Yarn-mapped namespace. `BaseQuestFileMixin` does **not**:
it omits the flag, and its method descriptors use Yarn names
(`Lnet/minecraft/entity/player/PlayerEntity;`) accordingly. `CobblemonQuestsHandlerMixin`
and `FTBQuestsInventoryListenerMixin` are `remap = false` but reference Minecraft classes
in their descriptors, so they spell them with intermediary names
(`Lnet/minecraft/class_3222;`). Mixing the two conventions in one file will not work;
match whichever the surrounding mixin uses.

`build.gradle` also reflects this split. FTB Quests / Teams / Library are `modCompileOnly`
(Loom remaps them to Yarn for development), while Cobblemon Quests and ExtraQuests are
plain `compileOnly` — a comment there records that `modCompileOnly` on Cobblemon Quests
caused a remap conflict with Cobblemon itself.

---

## State and when it reaches disk

Three files, all relative to the server's working directory:

| Path | Written by | When |
|---|---|---|
| `config/cheesefixquests.json` | `CheeseFixQuestsConfig.save()` | created on first start if absent; rewritten after `migrate` / `remigrate` and after the auto-migration |
| `config/cheesefixquests_audit.log` | `QuestAuditLogger` | appended and flushed per line, only while `auditQuestEvents` is `true` |
| FTB Quests' own quest data | `ServerQuestFile.saveNow()` | at the end of a migration that copied something, and at the end of `resetchapter` |

The config path is built as `Path.of("config", "cheesefixquests.json")`, not through
`FabricLoader.getConfigDir()`. On a normal server launch the two are the same directory. If
you start the server with a working directory other than the server root, this mod writes
its config somewhere else than every other mod does.

Config values are read once in `onInitialize` into static fields. Nothing reloads them;
`load()` has no second caller. `save()` serialises the current static fields, which means
a migration overwrites any hand edit made to the file while the server was running.

`migrationDone` is persisted state, not a setting, and it lives in the same file as the
settings. Deleting the config file to reset the flags also resets that flag, which will
re-run the migration on the next login.

---

## Threading

Almost everything is on the server thread.

- **Server thread** — `onInitialize`, the whole `/cheesefixquests` tree, the finalizer
  sweep (`END_SERVER_TICK`), and the auto-migration (inside the login sequence).
- **Any thread** — `TeamDataClaimRewardSyncMixin`. This is the point of it. A reward claim
  can arrive from an async event listener, a mod's own worker, or a save thread, and the
  redirect's `synchronized (map)` block is what makes the check-and-reserve atomic across
  all of them.
- **Audit writer** — called from whichever thread raised the event; all writes serialise on
  one lock inside `QuestAuditLogger`.
- **Shutdown** — `ServerLifecycleEvents.SERVER_STOPPING` sets `serverStopping` so the sweep
  stops before the world save, and closes the audit writer. A JVM shutdown hook flushes the
  writer again as a backstop.

There is no HTTP thread anywhere: the mod has no dashboard module and no REST surface.

---

## Extension points

**Another mod resolves the effective team and writes quest progress.** Add a mixin on its
lookup, modelled on `ExtraQuestsTimerMixin` — it is the smallest of the set. Keep the
`forceIndividualProgress` check and the fall-through to the original call, add the class to
`cheesefixquests.mixins.json`, and declare the mod in `suggests` rather than `depends` so
the jar stays loadable without it. Add a `compileOnly` dependency for the compile-time
target check: the Mixin annotation processor resolves and validates every target at compile
time, even for a `targets = "…"` string.

**A new stuck-progress bug class.** Add a phase to `QuestFinalizerHook` with its own
config flag, following the existing pattern: a `try`/`catch` around it in `tick`, a
`logOnce` call on failure, and a counter in the `PHASE_C_SWEEP` audit line if it belongs in
phase C.

**A new audit event.** One static method on `QuestAuditLogger` calling `write(EVENT,
fields…)`, guarded at the call site by `if (CheeseFixQuestsConfig.auditQuestEvents)`.

---

## Known weaknesses and deliberate trade-offs

**The mixins target third-party internals.** Repeated from the warning above because it is
the single most important thing to know: this mod will break on an FTB update, loudly, at
startup. That is preferable to breaking silently, but it means you pin your FTB versions.

**Hardcoded French, no language file.** Every message in `CheeseFixQuestsCommand` is a
`Text.literal` with a French string and `§` colour codes. There is no
`assets/cheesefixquests/lang/` directory. An English-speaking admin gets French output.
Fixing this means converting four methods to `Text.translatable` and adding the lang files.

**`migrate` reports success when the quest file is not loaded.** When
`ServerQuestFile.INSTANCE` is still null (or the FTB Teams manager is unavailable),
`QuestMigration.migrateAll()`
returns `0` and logs a WARN, but the command sets `migrationDone = true` and prints
*Migration terminée: 0 joueur(s)*. You then need `remigrate` to actually migrate.

**The migration never deletes its source.** Additive-only is what makes `remigrate` safe,
and it also means an old party record can re-seed progress you deliberately removed.

**`resetchapter` resets completed quests only, and only for player teams.** Partially
progressed quests keep their task progress, and any `TeamData` whose team is a party or no
longer exists is skipped entirely. It also un-claims rewards, so it can hand out a second
copy of a reward if used outside its intended repair scenario.

**Phase C0's inventory scan is the hot spot.** For each online player, for each incomplete
`ItemTask` on each startable quest, it iterates the whole inventory. The source comment
claims the full sweep stays under a millisecond at 50 players and 1000 quests; that figure
is not independently verified here.

**Two lookups use the effective team id.** FTB Teams' `PlayerTeam.getTeamId()` returns
the id of the team the player is *effectively* on — the party, when they are in one; only
`getId()` is the permanent player-team id. `QuestMigration` uses `getId()` for exactly this
reason, but two other places call `getTeamId()` on the resolved player team:
`QuestFinalizerHook.resolveTeamData`, so for a player in a party the sweep loads and repairs
the **party's** `TeamData`, not the player's own; and `ServerQuestFileMixin.isPlayerOnTeam`,
which for a party member therefore answers `true` for the party's record and `false` for
their own. Players who are not in a party are unaffected.

**Stale comments.** `QuestFinalizerHook`'s class javadoc says the phases run "every 5 s"
while `RESCAN_INTERVAL_TICKS = 200` is 10 seconds. The per-mixin javadocs number
themselves inconsistently — two different classes call themselves "Mixin 4", and a third
"Mixin 8", out of nine. Trust the code.

**`build.gradle` copies a `LICENSE` file that no longer exists.** The mod used to ship a
local MIT `LICENSE`, which contradicted the `PolyForm-Noncommercial-1.0.0` declared in
`fabric.mod.json`; it was removed on 29 October 2026. The `jar { from("LICENSE") … }` block
is now a no-op — Gradle's `from` on a missing path copies nothing and does not fail. The
mod is under the repository's [PolyForm Noncommercial 1.0.0](../../LICENSE) like everything
else here.

**No dashboard module.** Unlike most mods in this repository, CheeseFixQuests registers no
`cheesedash` entrypoint and has no REST or WebSocket surface. It does not depend on
CheeseDash at all. Everything is done from the console or `cheesefixquests.json`.

---

← [The finalizer sweep](finalizer.md) · [Troubleshooting](troubleshooting.md) →
