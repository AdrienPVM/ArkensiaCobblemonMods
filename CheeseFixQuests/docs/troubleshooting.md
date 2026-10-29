[← CheeseFixQuests](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

---

## The server crashes on startup with a mixin error

**Cause.** A mixin could not apply, because the FTB class, method or instruction it targets
has changed. `cheesefixquests.mixins.json` sets `required: true` and `defaultRequire: 1`, so
a redirect that finds nothing to redirect is a hard failure, not a warning. The stack trace
names the mixin:

```text
Mixin apply failed cheesefixquests.mixins.json:BaseQuestFileMixin -> …
org.spongepowered.asm.mixin.injection.throwables.InjectionError:
  Redirector forcePlayerTeamInGetTeamData could not find any targets
```

**Fix.** Check your FTB versions. This mod is verified against **FTB Quests 2101.1.13**,
FTB Teams 2101.1.2 and FTB Library 2101.1.18. If you updated any of them, roll back to
those versions, or open an issue with the crash report and the versions you are running —
the mixin has to be re-written against the new bytecode, and there is no configuration that
works around it.

If the named mixin is `CobblemonQuestsHandlerMixin` or `ExtraQuestsTimerMixin`, removing
that optional mod is also a valid workaround: both are `suggests`, and the mixin does not
apply when the mod is absent.

---

## The mod does not appear in the log at all

**Cause.** The jar is not loaded. Usually FTB Quests or FTB Teams is missing — both are
declared in `depends`, so Fabric refuses to load CheeseFixQuests without them and says so:

```text
Mod 'CheeseFixQuests' (cheesefixquests) 1.0.1+1.21.1 requires any version of mod 'ftbquests', which is missing!
```

**Fix.** Install FTB Quests and FTB Teams (and FTB Library, which they need). Also check the
jar is in the server's `mods/` and not in a client instance's by mistake, and that Fabric
Loader is at least 0.16.0 and Java is 21.

---

## Quest progress is still shared inside a party

**Cause, most likely.** `forceIndividualProgress` is `false`. Check first:

```mcfunction
/cheesefixquests status
```

```text
§e[CheeseFixQuests] forceIndividual=true migrationDone=true debug=false
```

**Cause, otherwise.** Progress is reaching the party through a code path with no redirect.
Each of the known ones has its own mixin — FTB's inventory listener, the login sync,
Cobblemon Quests' handler, ExtraQuests' timer — but any other mod that resolves the
effective team itself and writes quest progress will keep writing to the party. That is a
structural limit, explained under [scope](architecture.md#scope-what-is-covered-and-what-is-not).

**Fix.** Narrow it down by task type. Turn on `auditQuestEvents`, restart, and reproduce.
Then look at what else could be writing: is the shared task an item task (the inventory
listener), a Cobblemon capture (Cobblemon Quests), a daily reset (ExtraQuests), or something
a fourth mod submits? Open an issue naming the mod and the task type; the fix is a new
redirect, modelled on the smallest existing one.

Worth confirming the mixin is live at all. Without `debugLogging`, the inventory listener
logs once per player:

```text
[CheeseFixQuests] InventoryListener.detect: redirected to PlayerTeam for Steve (first redirect for this player; total redirects since startup: 1423)
```

If that line never appears for anyone, `FTBQuestsInventoryListenerMixin` is not applying —
see the first symptom on this page.

---

## Players lost their quest progress after installing the mod

**Cause.** The migration ran and did not copy everything. This is expected behaviour in one
specific case, and it is worth understanding rather than treating as a bug.

Only `claimedRewards` records *which player* did what. `taskProgress`, `started` and
`completed` are team-wide with no attribution at all. So for a source team that is not an
active party — a player's own old record, or a team that no longer exists — the migration
copies progress only for quests where that player personally claimed a reward. **A quest
completed without claiming its reward cannot be attributed to anyone, and is not copied.**

**Fix.** There is no way to recover it from the data FTB Quests stores; the information
simply is not there. Your options are to restore the backup you took before installing, or
to grant the missing completions by hand from the quest book in edit mode. Read
[the migration](migration.md) before trying a `remigrate` — a second run is additive and
will not help here, because the first run already copied everything it was able to
attribute.

---

## A quest sits at a full progress bar and never completes

**Cause.** The classic FTB Quests stuck state. `TeamData.setProgress` returns early when the
new count equals the old one, which skips the completion cascade, so a task can hold
`progress == max` while the `completed` map has no entry for it. The quest is finished and
the system cannot see it. Historically the workaround was to disconnect and reconnect.

**Fix.** This is what phase C of [the finalizer sweep](finalizer.md) repairs, and it should
resolve on its own within ten seconds. If it does not:

1. Check `enableQuestFinalization` is `true` in `config/cheesefixquests.json`.
2. Turn on `auditQuestEvents`, restart, and watch
   `config/cheesefixquests_audit.log`. A `PHASE_C_SWEEP` line for that player every ten
   seconds proves the sweep is running; `visited=0` means it is not reaching any quest, and
   a `FINALIZE_SKIP` line names the exception it hit on this specific quest.
3. If the stuck task is an item task and the item came out of a chest, that is phase C0's
   territory — look for `ITEM_REPAIR` or `ITEM_SET` on that task id.
4. If the player is in an FTB Teams party, the sweep does not reach their own record at
   all: it resolves the party's `TeamData` (the `team=` field of the audit lines is the
   party's id), so their own stuck quest is never repaired. That is a known weakness of the
   code, described in [architecture](architecture.md#known-weaknesses-and-deliberate-trade-offs).

The ids in the audit log are uppercase hex, the same form the quest book and the `.snbt`
files use, so you can match them directly.

---

## A reward was handed out twice

**Cause.** A cross-thread race on `TeamData.claimedRewards`, which is a fastutil map with no
thread safety. In FTB's original code the "already claimed?" check and the write that
records the claim are separate instructions; two threads reaching the same reward key in
between both pass the check and both run the give. The persisted data then looks clean,
because one thread's write overwrote the other's — which is what makes this bug so hard to
believe when you are staring at the save file.

**Fix.** `enableClaimRewardLock` closes the window, and it is `true` by default. Verify it
has not been turned off:

```text
[CheeseFixQuests] Boot complete — finalizer=true claim-lock=true audit=false
```

If `claim-lock=false`, set `enableClaimRewardLock` back to `true` and restart.

If it is already `true` and a duplicate still happened, that is worth a bug report. Turn on
`auditQuestEvents`, reproduce, and look for `CLAIM_RESERVED` / `CLAIM_BLOCKED` pairs on the
same key from different threads. A `CLAIM_BLOCKED` from a thread that is not
`Server thread` is the race being caught; two `CLAIM_RESERVED` lines on one key would mean
the lock itself failed, which should be impossible — include those lines.

---

## A config change has no effect

**Cause.** Nothing reloads the config. `CheeseFixQuestsConfig.load()` runs once in
`onInitialize` and has no other caller. There is no reload command.

**Fix.** Restart the server.

And one trap worth knowing: `/cheesefixquests migrate`, `remigrate` and the automatic
migration all call `save()`, which writes the whole file from the current in-memory values.
A hand edit made while the server was running is silently overwritten. Edit with the server
stopped.

---

## `/cheesefixquests` is not recognised

**Cause.** The whole command tree requires **permission level 4**, not the level 2 a vanilla
server grants an operator. Below that, Brigadier hides the command entirely and typing it
gives the vanilla *Unknown or incomplete command* error.

**Fix.** Run it from the server console, or raise the account to level 4 — in `ops.json`, or
with `/op` on a server whose `op-permission-level` is 4.

---

## Daily quests stopped resetting

**Cause.** ExtraQuests' `questReset` resolved the player's effective team, which is the party,
and reset *that* team's `TeamData` — which is empty under this mod. The timer expired, the
reset hit nothing, and the quests stayed completed.

**Fix.** `ExtraQuestsTimerMixin` redirects that lookup, so new resets land on the right data
and this should not recur. Chapters that got stuck before the mixin existed need a manual
repair:

```mcfunction
/cheesefixquests resetchapter <chapterHexId>
```

Read [the command's section](commands.md#cheesefixquests-resetchapter) first. It un-claims
rewards, so players who already took a reward from a reset quest can take it again, and it
only touches quests that are marked complete.

---

## Edit mode in the quest book does nothing

**Cause.** The client does not have the jar. On the client, FTB Quests compares the team id
it was told about (the party's) against the id its own quest data carries (the player
team's); the mismatch makes `getOrCreatePlayerData` return empty, which breaks
`setCanEdit` / `getCanEdit`. `ClientQuestFileMixin` is the fix and it is a client-side
mixin.

**Fix.** Install the same jar on the client. See
[which side](installation.md#which-side).

---

## Phase D disabled itself

**Cause.** Cobblemon Quests changed the shape of the class phase D reaches by reflection —
the singleton field `CobblemonQuests.eventHandler` or the method
`triggerPokeDexUpdate(UUID)`. The mod logs one WARN and never retries:

```text
[CheeseFixQuests] Phase D permanently disabled (class shape mismatch): java.lang.NoSuchMethodException: …
```

**Fix.** Nothing is broken; Cobblemon Quests progress just falls back on its own event
triggers, as it does without this mod. Either roll Cobblemon Quests back to 1.1.14, or
report it — the reflection target needs updating.

A different line, `Phase D init transient failure (will retry)`, is not the same thing. That
one is harmless: Cobblemon Quests' static initialiser had not populated the field yet, and
the next sweep will arm the phase.

---

## There is no dashboard module

**Cause.** By design. CheeseFixQuests registers no `cheesedash` entrypoint, has no REST
endpoints and does not depend on CheeseDash at all — it is the one mod in this repository
that is wholly independent of the rest.

**Fix.** Nothing to fix. Everything is driven from the console with
[`/cheesefixquests`](commands.md) and from
[`config/cheesefixquests.json`](configuration.md).

---

## Writing a useful bug report

Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues).

Include:

1. **Versions.** CheeseFixQuests, FTB Quests, FTB Teams, FTB Library, Fabric Loader,
   Minecraft, and Cobblemon Quests / ExtraQuests if installed. The FTB versions are the
   first thing anyone will ask for.
2. **`config/cheesefixquests.json`**, in full. Nine booleans; one of them is usually the
   explanation.
3. **The startup block.** Every `[CheeseFixQuests]` line from server start up to the first
   player login — `grep CheeseFixQuests logs/latest.log`.
4. **The full crash report**, if it crashed. The mixin name in the stack trace is the
   whole diagnosis.
5. **For a progress or duplication bug**, the audit log. Set `auditQuestEvents` to `true`,
   restart, reproduce, and attach the relevant window of
   `config/cheesefixquests_audit.log` — including the `PHASE_C_SWEEP` lines around the
   event, not only the event itself. Give the quest id in hex if you have it.
6. **For a sharing bug**, what the two players did, in order, and which task type was
   involved. Item task, kill task, Cobblemon capture, daily reset — each has a different
   code path and a different mixin.

---

← [Architecture](architecture.md) · [CheeseFixQuests](../README.md) →
