[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](records.fr.md)

# Leaderboards and run history

Three JSON files in `config/cobblemon_dungeons/` hold everything the mod remembers between
restarts. All three are loaded by `ConfigLoader.load()` — which means a config reload from
the dashboard reloads them too — and each is rewritten in full whenever it changes.

| File | Holds | Written when |
|---|---|---|
| `run_history.json` | The last 1000 runs, successful or not | Every time a run ends |
| `leaderboard.json` | Top 10 fastest times per dungeon per tier | Every successful run |
| `cooldowns.json` | Last completion time per dungeon per player | Every successful run |

None of them use the SQL database. The database is only ever used for cross-server player
data, never for records — the README's claim that run history and leaderboards can live in
MariaDB does not match the code.

---

## Run history

`DungeonManager.logRun` is called from exactly two places: `handleBossDefeated` with
`success = true`, and `failDungeon` with `success = false`. A run that ends because the
last player disconnected is never logged at all.

Each entry is a `DungeonRunLog` record:

| Field | Type | Meaning |
|---|---|---|
| `dungeonId` | string | `general.id` of the dungeon |
| `dungeonName` | string | `general.name` at the time of the run |
| `tier` | int | Tier the run was started at |
| `players` | array of strings | Player **names**, or the first 8 characters of the UUID for anyone offline |
| `startTime` | long | Unix seconds, from the instance's creation time |
| `endTime` | long | Unix seconds, at logging |
| `success` | boolean | Whether the boss was defeated |
| `bossSpecies` | string | Species of the boss that spawned, empty if none did |
| `deathCount` | int | Lives consumed during the run |
| `durationSeconds` | long | `endTime - startTime`, computed when serialising |

Names, not UUIDs. A player who renames themselves appears under both names across
history, and there is no way to query history by account.

`deathCount` is derived, not counted: it is the party's starting life total minus the
lives left. The starting total is recomputed at logging time from
`instance.getPlayers().size()`, which has already shrunk if anyone left or disconnected
during the run. For a party that lost members, `deathCount` is therefore wrong — usually
too low, and it can be clamped to zero.

New entries go to the **front** of the list, so index 0 is the most recent run. Above 1000
entries the oldest is dropped. The whole list is re-serialised to disk on every write,
which at 1000 entries is a few hundred kilobytes per run — fine at human rates, not
something to trigger in a loop.

Served to the dashboard as `GET /api/v1/dungeons/stats/history?n=50`.

## Statistics

There is no stored statistics object. `GET /api/v1/dungeons/stats` walks the in-memory
history on every request and computes:

- `totalRuns`, `successCount`, `successRate` (rounded percent)
- `avgDurationSeconds` — the mean of `endTime - startTime` over runs with a positive
  duration, divided by the **total** run count
- `configCount` and `activeInstances`, read live
- `runsByDungeon` and `runsByTier`, each `{total, success}`

The average is slightly off by construction: the numerator skips runs with a
non-positive duration while the denominator counts them. With a clean history the
difference is nil; with corrupted timestamps it drags the average down.

Everything is capped by the 1000-entry history. There is no all-time counter.

## Leaderboard

`updateLeaderboard` runs only on success, from `handleBossDefeated`. The structure is
`dungeonId → tier → list of entries`, each list sorted ascending by duration and trimmed
to ten.

| Field | Type | Meaning |
|---|---|---|
| `playerName` | string | Name at the time, or the UUID prefix if offline |
| `playerUuid` | string | The player's UUID |
| `durationSeconds` | long | Run duration in seconds |
| `date` | long | Unix seconds when the entry was recorded |

Every member of the party gets their own entry with the **same** duration — the run's, not
a personal one. A party of four finishing in 300 seconds puts four 300-second entries into
the same tier's top ten.

One entry per player per dungeon per tier: an existing entry is replaced only when the new
time is strictly faster, and left alone otherwise. Keeping only ten per tier means a
dungeon with a large regular group can have its board fully occupied by one party's run.

Duration here is computed as `now - (startTime / 1000)` in seconds, independently of the
`durationSeconds` the history entry records. The two agree, but they are two separate
calculations over the same clocks.

Served as `GET /api/v1/dungeons/leaderboard` for everything, or
`GET /api/v1/dungeons/leaderboard/<dungeonId>` for one dungeon.

## Cooldowns

`cooldowns.json` is `dungeonId → { playerUuid → lastCompletionEpochSeconds }`. An entry is
written for every party member on success, and nothing is written on failure.

The remaining time is computed on demand from `requirements.cooldownSeconds`, so lowering
that value in the config immediately shortens every outstanding cooldown, and setting it to
`0` disables the check without clearing the file. Raising it retroactively extends them.

Entries are never pruned. The file grows with one UUID per player per dungeon and stays
that size forever; at a few dozen bytes per entry this is not a practical problem, but it
is also not garbage-collected.

The cooldown map is exposed internally through `getAllCooldowns()`, which no dashboard
route currently calls — there is no REST endpoint for cooldowns. To clear one, stop the
server, edit `cooldowns.json`, start it again.

## What a reload does to all this

`POST /api/v1/dungeons/configs/reload` calls `ConfigLoader.load()`, which clears the
dungeon registry, re-reads every config file, and then re-reads the history, cooldowns and
leaderboard from disk. Since those three are also written to disk as they change, the
round trip is normally a no-op. If you hand-edited one of the files while the server was
running, a reload is what picks the change up — and a run finishing before you reload will
overwrite your edit.

---

← [Trainers and RCTApi](trainers.md) · [Dashboard integration](dashboard.md) →
