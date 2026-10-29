[← CheeseFixQuests](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](migration.fr.md)

# The migration

Installing CheeseFixQuests changes *where* FTB Quests looks for a player's progress. It
does not, on its own, move the progress that is already there. On a server that has been
running FTB Quests with parties, all of that history sits in party-keyed `TeamData`
records, and the moment the redirects engage every one of those players is looking at an
empty book.

`QuestMigration` exists to close that gap: it copies the old team-keyed records into
per-player records. This page explains what it copies, what it deliberately does not, and
why the answer is not simply "everything".

If your server is brand new, or has never used FTB Teams parties, none of this matters —
there is nothing to migrate and the migration will report zero.

---

## When it runs

Two entry points, the same code:

- **Automatically**, once. `ServerQuestFileMixin` injects at the head of
  `ServerQuestFile.playerLoggedIn`; if `migrationDone` is `false` and
  `forceIndividualProgress` is `true`, it runs `QuestMigration.migrateAll()`, sets
  `migrationDone = true` and saves the config. The first player to join after you install
  the mod triggers it.
- **Manually**, with [`/cheesefixquests migrate`](commands.md#cheesefixquests-migrate) or
  [`/cheesefixquests remigrate`](commands.md#cheesefixquests-remigrate).

Because the automatic path fires on a player login, it runs **on the server thread, inside
the login sequence**, before FTB Quests has synced that player's data to them. On a world
with a few hundred `TeamData` records and a thousand quests this is a visible pause for
that first player. It happens once.

> ⚠️ The automatic migration rewrites quest progress on first login, with no prompt and no
> backup. Back up `world/ftbquests/` (and `config/ftbquests/`) **before** the first start
> with this mod installed.

---

## The data model it works on

FTB Quests keeps one `TeamData` per team. Four maps inside it carry progress:

| Field | Key | Attribution |
|---|---|---|
| `taskProgress` | task id | **team-wide**. No record of which member earned it |
| `started` | quest / chapter id | **team-wide** |
| `completed` | quest / chapter id | **team-wide** |
| `claimedRewards` | `QuestKey(uuid, reward id)` | **per player** — the only map that names a player |

`TeamDataAccessor`, a mixin accessor interface on `TeamData`, is what lets the migration
read and write these four maps directly instead of going through FTB's setters. That is
deliberate: the setters fire completion cascades and reward claims, and a migration that
handed out rewards while copying would be unusable.

The asymmetry in that table is the whole difficulty. Three of the four maps have no idea
who did what. Only `claimedRewards` does, through the player UUID inside each `QuestKey`.

---

## What it copies, per source team

`migrateAll()` iterates every `TeamData` in the quest file. For each one it builds the set
of candidate players — every non-nil UUID found in `claimedRewards`, plus the current
members if the team is still an active party — and then picks one of two modes.

### Active party → full copy

If the source team still exists and `isPartyTeam()` is true, every current member gets a
**full copy** of all four maps. The reasoning: while the party was live, its members
genuinely did share that progress. Splitting it apart after the fact would be guesswork,
and giving each member the shared total is the outcome that matches what the players
experienced.

A side effect you should expect: two players who did half the work each both come out of
the migration with the whole chapter completed.

### Everything else → reward-guided filtering

For a source that is a player team, or a team that no longer exists at all, the migration
**filters**. It copies a `taskProgress` / `started` / `completed` entry only when that id
belongs to a quest for which the player personally claimed a reward.

The id set is built from `claimedRewards`: for each claimed reward, resolve the reward,
then its quest, and collect the quest id, its chapter id, and the ids of all of that
quest's tasks. Anything outside that set is left behind.

This is a real loss of data, chosen on purpose. A defunct team record may contain progress
from several players who left at different times, and only the reward claims say who was
who. Copying everything would hand one player another player's completions. The comment in
the source is blunt about the trade-off: `task_progress/started/completed are per-TEAM, not
per-player`.

Practical consequence: **a quest that a player completed but never claimed the reward for
does not survive a filtered migration.** They will have to complete it again. There is no
way around this with the data FTB Quests stores.

### `claimedRewards`, in both modes

Reward claims are always filtered by UUID, in both modes: an entry is copied to a player
only if its `QuestKey.uuid()` is that player's UUID, or the nil UUID `00000000-0000-…`
which FTB uses for team-level rewards. Nobody inherits anyone else's claim.

---

## Merge semantics

The copy never overwrites, with one exception:

| Map | Rule |
|---|---|
| `taskProgress` | `max(existing, source)` — the higher number wins |
| `started` | put only if the key is absent |
| `completed` | put only if the key is absent |
| `claimedRewards` | put only if the key is absent |

That is what makes `remigrate` safe to repeat: a second run on unchanged data finds every
key already present, copies nothing, and reports zero migrations.

It also means the migration is **additive only**. It never deletes, never lowers a
counter, and never touches the source `TeamData`. The old party records stay on disk
exactly as they were. That is useful when you need to re-run the migration, and it is a
trap when you have deliberately reset something — a later `remigrate` can copy the old
value back in. Reset after migrating, not before.

---

## Reading the log

`migrateAll()` logs one line per source team:

```text
[CheeseFixQuests] Processing team 8f3c1d20-… [PARTY] — 2 reward UUID(s), 3 total player(s), filter=false
```

- the bracketed type is `PARTY`, `PLAYER` or `DEFUNCT` (the team id resolved to nothing)
- `reward UUID(s)` is how many distinct players left reward claims in this record
- `filter=false` means full copy; `filter=true` means reward-guided filtering

Then one line per player:

```text
[CheeseFixQuests]   8f3c1d20-… → PersonalTeam 1a2b3c4d-… [FULL (party member)]: tasks=14, started=6, completed=6, rewards=4
[CheeseFixQuests]   7e5f…-… → PersonalTeam 9c8d…-… [FILTERED (23 quest/task/chapter IDs)]: tasks=9, started=4, completed=4, rewards=3
[CheeseFixQuests]   4b2a…-… — 0 new entries [FILTERED (0 quest IDs — only copying claimedRewards)]
```

The four counters are entries actually written, not entries considered. `0 new entries` on
a second run is the expected, healthy answer.

And one line at the end:

```text
[CheeseFixQuests] Migration saved: 3 player(s) updated
```

or, when nothing moved:

```text
[CheeseFixQuests] No new data to migrate
```

Two warnings are worth knowing about:

```text
[CheeseFixQuests]   <uuid> — getPlayerTeamForPlayerID returned EMPTY, trying getTeamByID fallback...
[CheeseFixQuests]   <uuid> — PlayerTeam NOT FOUND (both methods), skipping
```

FTB Teams creates a player team the first time a player joins. A UUID that appears in an
old reward claim but has no player team — a player who was purged from the teams data, or
a UUID from a world import — cannot be migrated, and is skipped. If the second line names
a player who is still active, say so in a bug report; it should not happen.

---

## The `getId()` / `getTeamId()` distinction

One line in `QuestMigration` carries most of the mod's history:

```java
UUID personalId = playerTeam.getId();   // NOT getTeamId()
```

In FTB Teams, `Team.getTeamId()` on a player team returns the id of that player's
**effective** team — the party, when they are in one. `Team.getId()` returns the player
team's own permanent id. An earlier version of this migration used `getTeamId()`, compared
it against the source team id, concluded "same team, nothing to do", and skipped every
single party member. The migration reported success and copied nothing.

If you extend this code, that distinction is the one to keep in mind. Anywhere this mod
wants "this player's own storage, regardless of parties", it wants `getId()` or
`getPlayerTeamForPlayerID`.

---

## Migrating back out

There is no reverse migration. If you remove CheeseFixQuests, FTB Quests goes back to
reading party-keyed `TeamData`, which still holds whatever it held before the migration —
old, and missing everything that happened while the mod was installed. The per-player
records remain on disk and become invisible to anyone in a party.

Reinstalling the mod picks the per-player records back up, so the round trip is not
destructive. But a stretch of play without the mod will leave progress in the party record
that no later migration will move across, because `migrationDone` is already `true`. Run
`remigrate` in that case, and re-read the filtering rules above before you do.

---

← [Configuration](configuration.md) · [The finalizer sweep](finalizer.md) →
