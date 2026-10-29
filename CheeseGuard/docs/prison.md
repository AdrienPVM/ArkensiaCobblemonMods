[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](prison.fr.md)

# Prison

The prison is a timed confinement system: a player is sentenced for a duration, held inside
a zone, stripped of almost every interaction, and released automatically when the time runs
out. It is a middle ground between a warning and a ban — the player stays on the server and
stays visible, which is the point.

Three classes do the work. `PrisonManager` (`logic/PrisonManager.java`) owns the state and
the config. `PrisonListener` (`logic/PrisonListener.java`) enforces confinement and drives
the join and expiry transitions. `PrisonDoppelgangerManager` keeps a stand-in body in the
cell while the prisoner is offline.

State lives in:

```text
config/cheeseguard/prison.json
```

Note the subdirectory: this file is under `config/cheeseguard/`, unlike the mod's older
files which sit directly in `config/` with a `cheeseguard_` prefix.

## Setting it up

The prison needs a zone before it can hold anybody. Any zone works — see
[Zones and flags](zones-and-flags.md) — but a zone that is actually sealed is a better idea
than one that merely marks an area, because confinement is enforced by teleporting the
player back, not by blocking movement.

```text
/prison setzone <zoneId>
```

Then, optionally, stand where released prisoners should appear and run:

```text
/prison setout
```

That stores world, coordinates and facing. `/prison clearout` removes it, and release falls
back to the overworld spawn point. Both are also editable from the dashboard through
`PUT /prison/config`.

Without a configured zone, `POST /prison` answers:

```json
{"error":"Prison zone not configured"}
```

and the command path answers `No prison zone configured. Use /prison setzone <zoneId>.`

## Entering

Two doors lead in.

**By command**, at permission level 2:

```text
/prison set <player> <duration> <reason>
```

**From the dashboard**, `POST /api/v1/cheeseguard/prison` with a body of
`{uuid|name, durationMs|duration, reason}`.

Either way the result is a `PrisonEntry` (`data/PrisonEntry.java`) stored under the
player's UUID: player uuid and name, reason, `startedAt`, `durationMs`, `endsAt`, and who
issued it. A dashboard sentence records `issuedBy` as `dashboard:<username>` and
`issuedByName` as the session's username, so the audit trail distinguishes the two routes.

Imprisoning a player who is already imprisoned **replaces** the sentence rather than
stacking it — `active.put(uuid, e)` with a freshly computed `startedAt`. To lengthen an
existing sentence, use the extend path instead (below).

### Durations

`PrisonManager.parseDuration()` accepts a concatenation of number-and-unit pairs:

| Input | Result |
|---|---|
| `90s` | 90 seconds |
| `30m` | 30 minutes |
| `2h` | 2 hours |
| `7d` | 7 days |
| `1d12h` | 36 hours |
| `45` | 45 **minutes** — a bare number is read as minutes |

Anything else returns `-1`, which the API turns into
`{"error":"Duration is required and must be > 0"}` and the command into
`Invalid duration: %s (try e.g. 30m, 2h, 7d).`

The parser has no upper bound and no overflow check. `999999999d` still fits (about 2.7
million years), but `999999999999d` overflows `long` arithmetic and wraps around: the
result comes out either negative (refused as invalid) or as an arbitrary value, with a
nonsensical `endsAt`. Keep sentences within reason.

### The player is offline

`resolvePlayer()` tries the online player list first, then the server's user cache. An
offline player who has logged in before can therefore be sentenced by name or by UUID. A
name the server has never seen returns `404 Player not found`.

A sentence handed to an **online** player does not teleport them at once: they receive
`⛓ You are in prison for <time>. Reason: <reason>` in chat, and the next containment check
(within half a second, see below) pulls them into the cell. The welcome message is only sent
on join.

A sentence handed to an offline player takes effect at their next login:
`ServerPlayConnectionEvents.JOIN` sees the active entry, teleports them into the cell one
tick after the join completes, and sends the welcome message. If the sentence expired while
they were away, the same handler releases them instead and teleports them to the release
point.

While the prisoner is offline, `PrisonDoppelgangerManager` shows a stand-in in the cell
with their profile, so other players can see who is serving time. It is spawned when an
imprisoned player disconnects, and at server start for every offline prisoner the user
cache knows (those boot-time stand-ins may lack the skin and render as Steve). A player
sentenced while already offline therefore gets no stand-in until the next restart. It is
despawned on reconnection and on release, including a release triggered by expiry or by an
admin while the player is still offline.

## Inside

An imprisoned player has almost nothing left. `PrisonListener` registers five Fabric
callbacks, all of which return failure for a prisoner and send
`⛓ You are in prison. This action is forbidden.` above the hotbar:

| Callback | Blocked |
|---|---|
| `PlayerBlockBreakEvents.BEFORE` | breaking blocks |
| `UseBlockCallback` | right-clicking blocks, so no chests, doors or crafting tables |
| `UseItemCallback` | using items in hand |
| `UseEntityCallback` | interacting with entities |
| `AttackEntityCallback` | hitting entities |

Two mixins cover what callbacks cannot:

- `ItemDropMixin` refuses item drops from a prisoner.
- `PrisonCommandMixin` cancels every command whose first word is not in a whitelist of
  exactly one entry — `help`. The message is
  `⛓ You are in prison. This command is forbidden.` **Operators are not exempt**, and the
  source comment says so on purpose: an op who gets imprisoned is as confined as anyone.
  There is no `/prison release` escape hatch from inside the cell; release has to come from
  another operator, from the console, or from the dashboard.

Confinement itself is a containment check, not a wall. Every 10 ticks (twice a second)
`onTick()` walks the online players, and for each prisoner outside the prison zone,
teleports them back to the zone centre and sends `⛓ You cannot leave the prison.` The
landing spot is chosen by scanning the zone's centre column downwards for the highest
non-air block with two air blocks above it, and `fallDistance` is zeroed afterwards.

Half a second is long enough to cross a few blocks, so a prisoner can briefly step outside
a small cell before being pulled back. Build the cell with some margin.

If the configured zone is deleted while people are serving time, the containment check
simply returns, and every 30 seconds the log says so:

```text
[CG/Prison] Configured prison zone 'jail' no longer exists; 2 prisoner(s) are unconstrained.
```

Prisoners then keep all their other restrictions — no blocks, no items, no commands — but
can walk anywhere. Do not delete the prison zone with occupants in it.

### The welcome message

`welcomeMessage` is a free-form string stored in `prison.json`, sent line by line on join,
with two placeholders substituted:

| Placeholder | Replaced by |
|---|---|
| `{reason}` | the sentence's reason, or an empty string |
| `{time}` | the remaining time, formatted as `2d 3h 15m` |

It is sent with `Text.literal`, so `§` colour codes work and nothing is translated. The
default value is in French and contains `§c`, `§7` and `§f` codes. Change it through
`PUT /prison/config` if your server is not French-speaking.

## Leaving

Three ways out.

**Expiry.** Once a second, `tickExpiry()` moves every entry whose `endsAt` has passed into
history with `releaseReason: "EXPIRED"`. An online player is teleported to the release
point and told `✨ Your sentence is over. You are free.`

**Pardon.** `/prison release <player>`, or `DELETE /api/v1/cheeseguard/prison/:uuid`.
History records `releaseReason: "PARDONED"` plus who did it. The player sees
`✨ You have been pardoned by an admin.` Neither path checks first that the player is
actually imprisoned when they are online: `/prison release` on an online player who is not
in prison still answers `<player> has been released from prison.`, and `DELETE` always
answers `200 {"released":true}`. Only `/prison release` on an offline player says
`<player> is not in prison.`

**Adjustment, which can shorten to the point of release.**
`PUT /api/v1/cheeseguard/prison/:uuid` moves `endsAt`. It takes whichever of these it finds
first:

| Body field | New end |
|---|---|
| `endsAt` | that absolute epoch-millisecond timestamp |
| `durationMs` | `startedAt + durationMs` — re-dates from the original start, not from now |
| `duration` | same, parsed from a string like `2h` |
| `extendMs` | `endsAt + extendMs` — the only field that adds to the current end |

An `endsAt` in the past is refused with `400 endsAt must be in the future`, so you cannot
release someone this way; use `DELETE`. `durationMs` and `duration` recompute from
`startedAt`: sending `{"duration":"1h"}` to someone two hours into a sentence sets an end
time in the past and is refused, which surprises people who expected "one more hour".

Release always goes through `PrisonManager.release()`, which removes the active entry, marks
it released, pushes it to the front of the history list, trims that list to 500 entries,
saves, and despawns the doppelgänger.

## Inventory

The prison does **not** touch inventories. There is no snapshot, no clearing, no restore: a
prisoner keeps everything they were carrying, and simply cannot use it. That is deliberate —
the restrictions make the inventory inert, and not touching it means there is nothing to
get wrong.

This is worth stating plainly because CheeseGuard *does* have an inventory snapshot-and-
restore path, and it did go wrong once. It belongs to the **dimension** subsystem, not to
the prison: `DimensionManager.exitClearInventory()`, used by dimensions configured with
clear-inventory mode. In June 2026 that path duplicated items. The old code restored the
entry snapshot with `player.readNbt(snapshot)` and then re-inserted the player's *entire*
current inventory on top — so every item that had been carried in came back twice.

The current implementation computes the genuinely-gained set instead: snapshot the current
inventory, wipe it, restore the entry NBT, then subtract the items held at entry from the
items held at exit (matching by item and components, decrementing counts) and re-insert only
the remainder. It also distinguishes a trusted exit from a crash-recovered one: if the
in-memory record of the entry inventory is gone, the current inventory cannot be trusted and
the exit is restore-only, with a warning:

```text
[CG] Clear inventory mode exited for Steve (crash-recovered snapshot) — restore-only, current inventory discarded to prevent duplication
```

Full details live in [Dimensions](dimensions.md). The reason it is mentioned here is that
"prison" and "clear-inventory dimension" are easy to confuse when both are used as holding
areas, and only one of them moves items. If you are tempted to add an inventory snapshot to
the prison, read that function first and keep the subtract-then-restore shape: the naive
version is the bug.

## Dashboard and WebSocket

`PrisonApi` is one of the two CheeseGuard APIs (with `RacesApi`) that call
`WebDashboard.requireWrite(exchange)` themselves — `if (!WebDashboard.requireWrite(ex)) return;` on every mutating route. Reads
accept any role, including `READ_ONLY`. Routes and bodies are in
[Dashboard API](dashboard.md#prison).

Every state change is broadcast on the WebSocket channel `prison` by
`PrisonListener.broadcastPrisonEvent()`:

| `type` | Sent when |
|---|---|
| `prison_added` | a sentence starts |
| `prison_released` | expiry or pardon |
| `prison_updated` | `endsAt` is adjusted from the dashboard |

The payload carries the full entry under `entry`, plus flattened `playerName`, `reason`,
`durationMs`, `duration`, `byName`, `releaseReason` and `releasedByName` at the top level.
That flattening exists so `DiscordWebhookManager` can pick fields out without walking into
the nested object.

The same function also broadcasts a public chat line for `prison_added` and
`prison_released` — translated, so each player reads it in their own language.
`prison_updated` is deliberately silent in chat: a duration tweak from the dashboard is
bookkeeping, not news.

## Known rough edges

- The containment check runs at 2 Hz, so brief escapes from a small cell are possible.
- Deleting the prison zone leaves prisoners unconstrained, with only a log line every 30
  seconds.
- `parseDuration` has no upper bound; an absurd duration produces an absurd `endsAt`.
- `PUT` with `durationMs` or `duration` re-dates from `startedAt`, which reads as "extend"
  but is not.
- The default `welcomeMessage` is French with hard-coded `§` colour codes, and is not
  translated.
- History is capped at 500 entries, oldest dropped silently.

---

← [Chunks](chunks.md) · [Races](races.md) →
