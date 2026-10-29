[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](chunks.fr.md)

# Chunks

Chunk sets exist to solve one problem: a mined-out wilderness. Over months, the area around
spawn is stripped, caves are hollowed out and every tree is gone. The fix is to delete the
region files for that area so the world regenerates from scratch next time somebody walks
there. Chunk sets are the bookkeeping around that deletion — which area, in which dimension,
on what schedule, with a verified backup every single time.

Two pieces of state, both under `config/cheeseguard/`:

```text
config/cheeseguard/chunks.json             ← chunk sets
config/cheeseguard/chunk_suggestions.json  ← player-submitted locations
config/cheeseguard/backups/                ← pre-deletion region backups, last 5 kept
```

⚠️ This subsystem deletes region files from the world save. A reset is not a soft operation
and there is no undo beyond the automatic backup. Read the whole page before running one on
a live world.

## Chunk sets

A `ChunkSet` is a named list of chunk entries, all in one dimension, plus an optional reset
schedule.

| Field | Default | Meaning |
|---|---|---|
| `name` | — | the key; also the backup directory prefix |
| `dimension` | `minecraft:overworld` | which world's region files get deleted |
| `chunks` | empty | the entries |
| `resetTrigger` | `MANUAL` | `MANUAL` or `SCHEDULED` |
| `resetHour` / `resetMinute` | `6` / `0` | local server time for a scheduled reset |
| `resetDays` | empty | day names in English uppercase; empty means every day |
| `lastAutoReset` | `0` | epoch milliseconds, used to stop a double fire |

A `ChunkEntry` is a chunk coordinate plus a radius and a label:

| Field | Meaning |
|---|---|
| `x`, `z` | **chunk** coordinates, not block coordinates |
| `radius` | how many chunks out from that chunk to include, in every direction |
| `label` | free text, shown in the dashboard |
| `centerX`, `centerZ` | derived block coordinates of the chunk's centre, `x*16+8` |

The radius is a square, not a circle: radius 2 covers a 5×5 block of chunks, 25 in total.
`totalAffectedChunks` on a set is the sum of `(2r+1)²` over its entries, with no
deduplication — two overlapping entries are counted twice, so the number is an upper bound
rather than a count of distinct chunks.

Entries are stored as chunk coordinates, but deletion works on **region files**, each of
which holds 32×32 chunks. `ChunkEntry.getRegionFileName()` maps an entry to its `r.X.Z.mca`.
This is the single most important thing to understand about the subsystem: asking to reset
one chunk deletes the whole 512×512-block region file that contains it. There is no
chunk-level deletion. Plan sets at region granularity, or you will wipe far more than you
meant to.

Entry equality is `(x, z, radius)` — the label is not part of it. That is why removing an
entry requires all three values.

## Resetting

`POST /chunks/sets/:name/reset` runs the deletion. `ChunkDeleter`
(`logic/ChunkDeleter.java`) does the work, and its contract is deliberately strict:

1. Force-save the target dimension's world, so the files on disk are current.
2. Expand the set into affected chunks, then into affected region files, then filter to the
   ones that actually exist.
3. **Create a verified backup.** Each region file is copied into
   `config/cheeseguard/backups/<set>-<yyyy-MM-dd_HH-mm-ss>/` (characters outside
   `[a-zA-Z0-9_-]` in the set name become `_`), and the copy's size is compared against the
   source. Any mismatch deletes the partial backup directory and throws.
4. Write a `manifest.json` into the backup directory with the metadata needed to restore by
   hand. This closes the backup step, before anything is deleted.
5. Only if the backup completed, delete the region files.
6. Rotate backups, keeping the 5 most recently modified backup directories, all sets
   combined.

If the backup fails, **nothing is deleted**:

```text
[CG] ABORTING chunk reset 'spawn_ring': backup failed — Integrity check failed for r.0.0.mca: source=4194304B, backup=2097152B
```

The response is the `DeletionResult`:

```json
{
  "setName": "spawn_ring",
  "status": "SUCCESS",
  "backupDir": "spawn_ring-2026-10-29_04-00-00",
  "totalChunks": 225,
  "totalRegions": 4,
  "deleted": 4,
  "notFound": 0,
  "failed": 0,
  "deletedFiles": ["r.0.0.mca", "r.0.-1.mca", "r.-1.0.mca", "r.-1.-1.mca"],
  "notFoundFiles": [],
  "failedFiles": [],
  "needsRestart": true
}
```

| `status` | Meaning | HTTP |
|---|---|---|
| `SUCCESS` | every targeted file was backed up and deleted | `200` |
| `PARTIAL` | some files could not be deleted; the backup succeeded | `200` |
| `NO_REGIONS` | the set expands to no region files | `200` |
| `NO_FILES_FOUND` | the regions exist in theory but no `.mca` file is on disk | `200` |
| `BACKUP_FAILED` | aborted before deleting anything | `500` |

Only `BACKUP_FAILED` is treated as an HTTP failure. A `PARTIAL` comes back as `200`, so
check `status` rather than the status code.

`needsRestart` is `true` whenever anything was deleted. It is advice, not enforcement: the
server keeps running, but any chunk that was still loaded in memory may be written back to
disk, recreating part of what you just deleted. Deleting regions that nobody is standing in,
then restarting at the next convenient moment, is the reliable sequence. The force-save in
step 1 reduces the window but does not close it.

An empty set is refused with `400 {"error":"Chunk set is empty"}`, which stops the obvious
accident of resetting a set you have not populated yet.

## Scheduling

With `resetTrigger: "SCHEDULED"`, `CheeseGuardMod.tickChunkAutoReset()` checks every set
against the wall clock, every 600 ticks (about 30 seconds). The conditions, in order: the set is scheduled, the set is not
empty, the current hour equals `resetHour`, the current minute is within 1 of `resetMinute`,
the current day is in `resetDays` (or the list is empty), and the last auto-reset was more
than 90 seconds ago.

That last gate is what keeps the ±1-minute tolerance from firing the reset two or three
times. `lastAutoReset` is written and the storage saved *before* the deletion is scheduled,
so a crash mid-deletion does not cause a re-run at the next tick.

The scheduler uses `LocalDateTime.now()` — the **server machine's** local time zone, not
UTC and not Minecraft time. Day names are `java.time.DayOfWeek` names in English uppercase:
`MONDAY`, `TUESDAY`, `WEDNESDAY`, `THURSDAY`, `FRIDAY`, `SATURDAY`, `SUNDAY`. The API
upper-cases whatever you send, but it does not validate the names: a misspelled day is
stored and silently never matches.

A successful auto-reset announces itself in chat to every online player, translated, and
logs:

```text
[CG] Auto-reset triggered for chunk set: spawn_ring
[CG] Auto-reset completed for 'spawn_ring' (dim: minecraft:overworld): 4 deleted, 0 failed, backup: spawn_ring-2026-10-29_06-00-00
```

When CheeseDash is installed it also sends a `SCHEDULED_TASK_FIRED` Discord notification.
A `BACKUP_FAILED`, `NO_REGIONS` or `NO_FILES_FOUND` result sends nothing to players and logs
`[CG] Auto-reset failed for '<set>': <status> — <error>`.

`PUT /chunks/sets/:name/schedule` configures it. `trigger` must be exactly `MANUAL` or
`SCHEDULED` (case-sensitive); anything else gives `400`, and an omitted `trigger` sets
`MANUAL`. The body may also carry `dimension`, `hour`, `minute` and `days`. `hour` is clamped to 0–23 and `minute` to 0–59, so out-of-range
values are silently corrected rather than refused.

## Suggestions

A suggestion is a player saying "this area is wrecked, consider resetting it". Players
submit one with:

```text
/chunksuggest
```

It takes no arguments and is **open to every player**, not just operators. It is registered
as a standalone command rather than under `/cg` on purpose — the comment in the source says
this avoids clobbering the permission gate of the `chunkstoclear` mod, whose root node
requires operator level.

What the command does:

1. Compute the player's region coordinates, `floor(x) >> 9` and `floor(z) >> 9`.
2. Walk every chunk set that targets the player's **current dimension**, expand it to its
   affected regions, and check whether the player's region is already covered. If it is, the
   player is told which set covers it and nothing is stored.
3. Otherwise store a `ChunkSuggestion` with an 8-character random id, the player's name and
   UUID, their exact position, their world and a timestamp, and confirm with the rounded
   coordinates.

The coverage check is what keeps the queue from filling with duplicates for an area already
scheduled. It is per-dimension, so the same spot in the Nether and in the overworld are
independent.

Suggestions are listed with `GET /chunks/suggestions`. Position is truncated to integers in
the response, even though it is stored as a double.

### Accepting one

`POST /chunks/suggestions/:id/accept` with `{"setName": "...", "radius": 1}` converts a
suggestion into a chunk entry on an **existing** set — it does not create the set. A missing
or empty `setName` gives `400`, an unknown set gives `500 Chunk set not found: <name>`.

Two details are deliberate and worth keeping:

**Dimension safety.** If the suggestion's world does not match the set's dimension, the
request is refused with `409`:

```json
{"error":"Dimension mismatch: suggestion is in 'minecraft:the_nether' but chunk set 'spawn_ring' targets 'minecraft:overworld'"}
```

Without that check, accepting a Nether suggestion into an overworld set would eventually
delete overworld region files at Nether coordinates — the wrong world entirely. `409` rather
than `400` because the request is well-formed; it is the pairing that conflicts.

**Chunk coordinate conversion.** The position is converted with
`(int) Math.floor(x) >> 4`, not `(int) x >> 4`. A plain cast truncates toward zero, so a
player at `x = -0.5` would land in chunk 0 instead of chunk -1, and the set would target the
region next door. `Math.floor` first is the fix; keep it if you touch this code.

The accepted entry is labelled `Suggestion by <playerName>`, both stores are saved, and the
suggestion is removed in the same server-thread task.

`DELETE /chunks/suggestions/:id` dismisses one without acting on it. There is no notification
to the player either way.

## Warnings on entry

Every 40 ticks (2 seconds), each player's current region file is compared with the regions
covered by every chunk set of their dimension. Walking into one prints, in gold:

```text
You are entering a region of the set "spawn_ring" that may be regenerated by the administrator. Do not build your base here!
```

and leaving it prints `You are leaving the regenerable zone "spawn_ring".` in grey. No
configuration turns this off; a set with entries is always announced.

## Authorisation

`ChunksApi` contains no calls to `WebDashboard.requireWrite` or `requireAdmin`. The mutating
routes here are `POST`, `PUT` and `DELETE`, so CheeseDash's module dispatcher guards them at
`OPERATOR` or above, and the two `GET` routes accept any role — see
[Dashboard API](dashboard.md#what-the-core-actually-enforces) for exactly what the core
checks and what it does not.

It is worth saying plainly that `POST /chunks/sets/:name/reset` deletes world data and is
protected by `OPERATOR`, not by `SUPER_ADMIN`. If that is not the boundary you want, the
module is where the check would have to be added.

Route details, bodies and status codes are in [Dashboard API](dashboard.md#chunks).

## Known rough edges

- `totalAffectedChunks` double-counts overlapping entries.
- Deletion granularity is the region file (32×32 chunks), never the chunk.
- `PARTIAL` is reported with HTTP `200`.
- A chunk still loaded in memory can be written back after its region file is deleted;
  restart after a reset.
- `resetDays` entries are not validated against real day names.
- The scheduler follows the host machine's time zone.
- Backups are capped at 5 directories **across all sets** and rotate silently, so a single
  set reset weekly keeps about five weeks of history, and several sets share those five slots.

---

← [Players](players.md) · [Prison](prison.md) →
