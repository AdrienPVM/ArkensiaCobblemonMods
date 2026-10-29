[← CheeseFixQuests](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

CheeseFixQuests exposes one command, `/cheesefixquests`, with four subcommands. They are
administrative tools around the data migration — the per-player redirection itself needs
no command at all and runs from the moment the mod loads.

The whole tree is declared with `requires(src -> src.hasPermissionLevel(4))`, so it needs
**permission level 4**: a server owner, or the console. Level 2 operators are not enough.
A player below level 4 does not see `cheesefixquests` in tab completion and gets the
vanilla *Unknown or incomplete command* error if they type it anyway.

All four subcommands are declared in `CheeseFixQuestsCommand.register`. There are no
aliases and no argument completions — the one argument the tree takes is a plain word,
and Brigadier has nothing to suggest for it.

> ⚠️ Every message these commands print is hardcoded French, with legacy `§` colour
> codes. The mod has no language file, so an English client sees French text too. The
> output blocks below are what the server actually sends.

---

## `/cheesefixquests status`

```mcfunction
/cheesefixquests status
```

No arguments. Prints the three flags that decide whether the mod is doing anything, read
straight out of the in-memory config.

```text
§e[CheeseFixQuests] forceIndividual=true migrationDone=true debug=false
```

| Field | Config key | Meaning |
|---|---|---|
| `forceIndividual` | `forceIndividualProgress` | `false` means every redirect is inert and FTB Quests behaves stock |
| `migrationDone` | `migrationDone` | `false` means the automatic migration has not run yet and will fire on the next player login |
| `debug` | `debugLogging` | per-redirect INFO logging |

The feedback is sent with `broadcastToOps = false`, so only the caller sees it. It cannot
fail, and it returns `1`.

This is the first thing to run when the mod looks like it is not working. If
`forceIndividual=false`, nothing else in this documentation applies — see
[configuration](configuration.md#forceindividualprogress).

---

## `/cheesefixquests migrate`

```mcfunction
/cheesefixquests migrate
```

No arguments. Copies existing quest progress out of team-keyed storage into each
player's own per-player storage, then marks the migration as done.

**In most cases you will never need to type this.** `ServerQuestFileMixin` already runs
the same migration automatically, at the head of `ServerQuestFile.playerLoggedIn`, the
first time anyone logs in while `migrationDone` is `false`. The command exists for the
case where you want to run it from the console before letting players back in, and so
that the migration has a visible entry point at all.

### What it does

1. If `migrationDone` is already `true`, it prints a refusal and stops. Nothing is
   touched.
2. Otherwise it calls `QuestMigration.migrateAll()`, which walks every `TeamData` the
   quest file knows about and copies entries to per-player `TeamData`. The copy rules are
   not uniform, and they matter: read [the migration](migration.md) before running this on
   a world you care about.
3. Sets `migrationDone = true` and writes `config/cheesefixquests.json`.
4. If anything was copied, marks the quest file dirty and calls `saveNow()` — the new
   progress is on disk before the command returns.

### Side effects

- rewrites `config/cheesefixquests.json` from the current in-memory values, so any manual
  edit you made to that file while the server was running is lost
- writes the quest file to disk synchronously
- logs one line per source team and one per migrated player, at INFO, under
  `[CheeseFixQuests]`

The command's return value is the number of player migrations performed, which is what
`execute if` and command blocks will see.

### Example

```mcfunction
/cheesefixquests migrate
```

```text
§a[CheeseFixQuests] Migration terminée: 3 joueur(s) migrés.
```

That feedback is sent with `broadcastToOps = true`, so every operator online sees it. The
server log carries the detail:

```text
[CheeseFixQuests] Processing team 8f3c…-… [PARTY] — 2 reward UUID(s), 3 total player(s), filter=false
[CheeseFixQuests]   8f3c…-… → PersonalTeam 1a2b…-… [FULL (party member)]: tasks=14, started=6, completed=6, rewards=4
[CheeseFixQuests] Migration saved: 3 player(s) updated
```

### Failures

| Message | Cause |
|---|---|
| `§e[CheeseFixQuests] Migration déjà effectuée. Utilisez /cheesefixquests remigrate pour forcer.` | `migrationDone` is `true`. Use `remigrate` if you really want to run it again |

There is no message for "FTB Quests is not loaded". In that case `QuestMigration` logs
`ServerQuestFile not loaded, cannot migrate` or `FTB Teams manager not available` at WARN,
returns `0`, and the command still reports a successful migration of zero players — and
still sets `migrationDone = true`. That is a wart: if you run `migrate` too early in the
boot sequence you will have flagged the migration as done without migrating anything, and
you then need `remigrate` to recover.

---

## `/cheesefixquests remigrate`

```mcfunction
/cheesefixquests remigrate
```

No arguments. The same migration, with the `migrationDone` guard skipped.

`QuestMigration` is written to be idempotent: task progress merges with `max(existing,
source)`, `started` / `completed` / `claimedRewards` are put only when the key is absent.
Running it a second time on unchanged data copies nothing and reports zero. So
`remigrate` is safe to repeat, in the narrow sense that it will not corrupt what is
already there.

It is not safe in the broader sense: if a player has since *lost* progress on purpose —
you reset a chapter, say — a remigration can copy that progress back in from the old team
record, because the old record still exists. The migration never deletes its source.

```mcfunction
/cheesefixquests remigrate
```

```text
§a[CheeseFixQuests] Re-migration terminée: 0 joueur(s) migrés.
```

Zero is the expected answer on a world that has already been migrated and has not
changed. Same side effects as `migrate`: config rewritten, quest file saved, same log
lines. Returns the number of player migrations performed.

---

## `/cheesefixquests resetchapter`

```mcfunction
/cheesefixquests resetchapter <chapterHexId>
```

Wipes every **completed** quest of one chapter, for every player, and makes those quests
available again from scratch.

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `chapterHexId` | single word (`StringArgumentType.word()`) | yes | a chapter's FTB Quests id, in the hexadecimal form the quest book and the `.snbt` files use, e.g. `3C1A4E7B9D2F6058`. No tab completion | — |

The id is the chapter's, not a quest's. You can read it from the chapter's file name under
`config/ftbquests/quests/chapters/`, or from the quest book in edit mode.

### What it does

For each `TeamData` in the quest file:

1. Resolve the owning team. If it is not a player team — a party, or a team that no longer
   exists — **the whole `TeamData` is skipped**. Party-held progress is not reset.
2. For each quest in the chapter, only if that team has it marked completed:
   - every task's progress is set to `0`
   - `started` and `completed` are cleared for the quest
   - every reward is un-claimed with `resetReward(playerUUID, reward)`
3. If anything changed, the `TeamData` is marked dirty and one INFO line is logged.

Then the quest file is marked dirty and `saveNow()` is called.

Two consequences worth stating plainly. Quests that were **in progress** but not completed
are left completely untouched, including their partial task progress. And because rewards
are un-claimed, players who already took a reward from a reset quest can take it again —
that is the point when you are repairing a broken daily chapter, and a duplication bug
when you are not.

### Why this exists

Before `ExtraQuestsTimerMixin` was written, ExtraQuests' daily timer resolved the party
team and reset *its* `TeamData`, which under this mod is empty. The timers expired, the
reset silently hit nothing, and the daily quests stayed completed forever. `resetchapter`
is the manual repair for chapters that got stuck that way. With the mixin in place, new
timer resets land on the right data and you should not need this command again.

### Example

```mcfunction
/cheesefixquests resetchapter 3C1A4E7B9D2F6058
```

```text
§e[CheeseFixQuests] Resetting 12 quests in chapter '3C1A4E7B9D2F6058' for all players...
§a[CheeseFixQuests] Reset terminé: 7 quest(s) réinitialisées au total.
```

Both lines are broadcast to operators. The count is cumulative across players: 7 means
seven (player, quest) pairs were reset, not seven distinct quests. Returns that same
total.

### Failures

| Message | Cause |
|---|---|
| `§c[CheeseFixQuests] ServerQuestFile not loaded` | `ServerQuestFile.INSTANCE` is null — FTB Quests has not finished loading, or is absent |
| `§c[CheeseFixQuests] Invalid hex ID: <id>` | `ServerQuestFile.getID(String)` threw on the argument |
| `§c[CheeseFixQuests] ID <id> is not a chapter` | the id resolved, but to something that is not a `Chapter` — a quest, a task, or nothing at all |

The last two overlap in practice. FTB Quests' `getID(String)` is third-party code this mod
does not control; from the observed behaviour, a well-formed hex string that matches no
object does not throw, so it falls through to the *is not a chapter* message rather than to
*Invalid hex ID*. Treat that message as "I could not find a chapter with this id", whatever
the reason.

All three failures return `0` and change nothing.

---

## No command for the redirects

There is no command to turn individual progress on or off at runtime, and no reload
command. Every flag in [`cheesefixquests.json`](configuration.md) is read once in
`onInitialize` into a static field; changing the file needs a server restart to take
effect. The flags are read live on every redirect, so a code path that mutated them would
work — nothing does.

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
