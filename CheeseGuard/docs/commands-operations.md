[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands-operations.fr.md)

# Commands — events, prison and operations

The second half of the command surface. [The first page](commands.md) covers zones, flags,
groups and states; this one covers everything that acts on players and on the server:
the `/cg event` subtree, the minigame toggles, per-player worlds, pasture limits,
inventory restore, and the separate `/prison` command.

Permission levels are noted per subcommand. One subcommand here, `/cg adv`, has no gate at
all, and `/cg event` is open by design with the gating done leaf by leaf.

---

## `/cg event`

The `event` node is explicitly declared `requires(src -> true)`, so it is visible to
everyone, and the gating is per leaf. The event model — states, phases, effects,
participants — is in [events](events.md); the timeline language that drives an event's
acts is in [timeline](timeline.md).

An event is **running** when its state is anything other than `IDLE`, and **active** only
in the `ACTIVE` state. The seven states are `IDLE`, `ANNOUNCED`, `QUEUE`, `COUNTDOWN`,
`ACTIVE`, `ENDING`, `CLEANUP`. There is no paused state and no pause or resume command:
an event you want to stop has to be stopped from the dashboard.

### `/cg event join`

```mcfunction
/cg event join
/cg event join <eventId>
```

No permission required. Must be run by a player.

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `eventId` | single word | no | an event id; no tab completion |

Without an argument it walks the event list and joins the **first** running event you are
not already in. The order is the list order in `cheeseguard_events.json`, not a priority,
so with two events running you cannot predict which one you get. Pass the id when it
matters.

You join as `PARTICIPANT` — or as `SPECTATOR` if the event is already full, or if it is an
active minigame (you are told so). Joining records your position so that leaving can put
you back; your inventory is saved only if the event has `saveInventory` on, and only when
the event starts (or immediately, for a late join into an `ACTIVE` event).

```text
Registered for event: Spleef Night
```

| Message | Cause |
|---|---|
| `No event available to join` | no running event accepted you — none is running, you are already in each one, or each is in a state that refuses joins |
| `Event not found` | the id does not match any event |
| `Unable to join this event` | `EventManager.joinEvent` refused — the event is not in `ANNOUNCED`, `COUNTDOWN` or `ACTIVE` (so `IDLE`, `QUEUE`, `ENDING` and `CLEANUP` refuse), or you are already in it. A full event does not refuse: it downgrades you to spectator |

### `/cg event spectate`

```mcfunction
/cg event spectate
/cg event spectate <eventId>
```

The same two forms and the same resolution, but joins with the `SPECTATOR` role.

```text
Spectating: Spleef Night
```

| Message | Cause |
|---|---|
| `No event available to join` | nothing running — the no-argument form reuses the join message |
| `Event not found` | unknown id |
| `Unable to spectate this event` | the event refused the spectator join |

### `/cg event leave`

```mcfunction
/cg event leave
```

No permission required. Three fallbacks, tried in order, which is what makes it the
command to reach for when a player is stuck:

1. If you are a participant of any event, leave it normally — inventory (if it was saved)
   and position restored. Your participant entry, and the score stored on it, is removed
   from the event.
2. Otherwise, if a **pending restore** is waiting for you (the server crashed while you
   were in an event, and your inventory snapshot survived in
   `config/cheeseguard_pending_inv/`), apply it now and remove the leave item.
3. Otherwise, remove the leave item from your inventory and say you were not in an event.

```text
You left the event.
```

```text
Inventory and position restored.
```

```text
You are not participating in any event
```

The third message is a `0` return, the other two are `1`. Case 2 is the recovery path
after a crash; if a player reports losing their inventory to an event, this is the first
thing to try.

### `/cg event launch`

```mcfunction
/cg event launch <eventId>
```

No permission required, but gated at runtime. This is how players start an event
themselves without an admin present.

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `eventId` | single word | yes | an event id |

Checks, in order:

1. the event exists;
2. the event has `playerLaunchable` set;
3. the event is not already running;
4. the event's own cooldown has elapsed, if `cooldownMinutes` is above 0 and it has run
   before;
5. unless the caller has level 2 — **operators skip the rest** — the global launch policy
   in `config/cheeseguard_event_launch.json`: time window, then per-player rate limit,
   then server-wide rate limit.

A successful launch records the timestamp against your UUID and announces the event, which
puts it into `ANNOUNCED` rather than straight into `ACTIVE`.

```mcfunction
/cg event launch spleef_night
```

```text
Event 'Spleef Night' launched!
```

| Message | Cause |
|---|---|
| `Event not found` | unknown id |
| `This event cannot be launched by players.` | `playerLaunchable` is off |
| `Event is already running` | state is not `IDLE` |
| `This event is on cooldown, try again later.` | the per-event cooldown has not elapsed |
| `Events can only be launched during allowed hours.` | outside the configured time window |
| `You have reached your launch limit for this period.` | your own rate limit |
| `The global launch limit has been reached for this period.` | the server-wide rate limit |

The last three come from `EventManager.checkLaunchAllowed`, which returns a short code
that the command turns into the key `cheeseguard.cmd.launch_<code>`. Adding a new code in
`checkLaunchAllowed` without adding the matching language key would print the raw key to
players — worth knowing before you extend it.

### `/cg event list`

```mcfunction
/cg event list
```

No permission required. Running events only, each with a clickable join button.

```text
🎉 Spleef Night — ACTIVE (4m12s) [Join]
```

The state name is printed raw from the enum, so players see `COUNTDOWN` and `CLEANUP`
untranslated. With nothing running:

```text
No events running
```

### `/cg event announce`

```mcfunction
/cg event announce <eventId>
```

**Level 2.** Moves an idle event to `ANNOUNCED` and broadcasts it, which is the normal
admin way to open registration.

```text
Event 'spleef_night' announced!
```

| Message | Cause |
|---|---|
| `Event not found` | unknown id |
| `Event is already running` | the event is not `IDLE` |

### `/cg event score add`

```mcfunction
/cg event score add <target> <amount>
```

**Level 2.**

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `target` | single word | yes | an online player's name |
| `amount` | integer | yes | 1 or more, enforced by Brigadier |

Finds the first **active** event the target participates in and adds the points there.
Only `ACTIVE` counts: a player in an event still in `COUNTDOWN` cannot be scored.

The minimum of 1 means there is no way to subtract points from a command. Use the
dashboard for corrections.

The points actually credited are multiplied by any score `MULTIPLIER` effect of the
current phase, and a target who is in the event as a **spectator** gets nothing — yet the
command still reports `+<amount> pts` in both cases, because it prints the raw amount
after finding the target in the event.

```mcfunction
/cg event score add Steve 50
```

```text
+50 pts for Steve
```

| Message | Cause |
|---|---|
| `Player not found` | not online |
| `This player is not in any active event` | no participation, or the event is not in `ACTIVE` |

### `/cg event snapshot`

```mcfunction
/cg event snapshot save <snapId> <regionId>
/cg event snapshot restore <snapId>
```

**Level 2.** A lighter mechanism than [saved zone states](commands.md#saved-zone-states):
one NBT file per snapshot in `config/cheeseguard_snapshots/<snapId>.nbt`, non-air blocks
only, no animation and no per-zone index. Only each block's **id** is written to disk, so a
snapshot loaded back restores every block in its default state — stair orientation, slab
halves, chest contents and the like are lost.

```mcfunction
/cg event snapshot save arena_clean boss_arena
```

```text
Snapshot 'arena_clean': 18432 blocks
```

```mcfunction
/cg event snapshot restore arena_clean
```

```text
18432 blocks restored
```

| Message | Cause |
|---|---|
| `No blocks in zone <id>` | capture found nothing — a wrong zone id, a world that is not loaded, or a box that contains only air (air is skipped) |
| `Snapshot not found: <id>` | no file with that id |

`restore` takes no zone argument: the snapshot remembers the region it came from. Nothing
stops two snapshots sharing an id-derived filename, and a `save` over an existing id
overwrites it without asking.

### `/cg event template`

```mcfunction
/cg event template save <eventId> <tplName>
/cg event template load <tplName>
/cg event template list
```

**Level 2.** Templates are stored in **`config/cheeseguard_templates/`** — not
`config/templates/`. One JSON file per template, named from the template name with
everything outside `[a-zA-Z0-9_-]` replaced by `_` and lowercased.

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `eventId` | single word | yes (for `save`) | an existing event id |
| `tplName` | greedy string | yes (for `save` and `load`) | any text |

`load` creates a **new event** from the template and appends it to the event list, then
saves `cheeseguard_events.json`. It does not replace an existing event, so loading the
same template twice gives you two events.

```mcfunction
/cg event template save spleef_night Spleef Standard
```

```text
Template saved: Spleef Standard
```

```mcfunction
/cg event template list
```

```text
  📄 Spleef Standard
```

`list` prints the `templateName` stored inside each file (falling back to the filename),
sorted alphabetically.

| Message | Cause |
|---|---|
| `Event not found` | unknown event id on `save` |
| `Error saving template` | the write failed — check the server log for the IO error |
| `Template not found: <name>` | no file matched, after slugging |
| `No templates` | the directory is empty or absent |

`load` matches the slugged filename first and the raw name second, so a template saved as
`Spleef Standard` loads under either spelling.

---

## `/cg hotpotato`

```mcfunction
/cg hotpotato on
/cg hotpotato off
/cg hotpotato bomb
/cg hotpotato explode
```

**Level 2** on the whole subtree. A standalone party game: one player holds a bomb and
passes it by right-clicking another player.

| Subcommand | Effect |
|---|---|
| `on` | arms the handler — right-clicking a player passes the bomb |
| `off` | disarms it **and** removes the bomb item from every online player |
| `bomb` | gives the bomb to a random online player |
| `explode` | detonates on whoever currently holds it |

```text
Hot Potato enabled!
```

```text
Bomb given to Steve
```

```text
Steve eliminated!
```

```text
Hot Potato disabled — bombs removed
```

| Message | Cause |
|---|---|
| `No players online` | `bomb` with an empty server |
| `No player holds the bomb` | `explode` with no holder — either nobody was given one, or they logged off |

`HotPotatoHandler` keeps its active flag in a static field, not in a config file, so the
game is always off after a restart even if you left it on.

---

## `/cg playerworld`

```mcfunction
/cg playerworld list <masterDimId>
/cg playerworld delete <masterDimId> <player>
```

**Level 2.** Per-player copies of a master dimension. Each copy's id is
`<masterDimId>_<uuid8>` — the master's id, an underscore, and the first eight hex
characters of the owner's UUID. The model is in [dimensions](dimensions.md).

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `masterDimId` | single word | yes | the **master** dimension's config id, not a per-player id |
| `player` | single word | yes (for `delete`) | a player name |

```mcfunction
/cg playerworld list personal_island
```

```text
Personal dimensions of 'personal_island' (2)
  • Steve's island [personal_island_f81d4fae]
  • Alex's island [personal_island_7d4448d9]
```

```text
No personal dimensions for 'personal_island'
```

`delete` resolves the player in two steps: an online player gives their UUID directly;
otherwise it scans the master's per-player dimensions for one whose display name
*contains* the given name, then takes the first UUID from that dimension's whitelist.
That substring match is loose — `Al` matches `Alex` — and the whitelist fallback assumes
the first whitelisted UUID is the owner. Prefer deleting while the player is online, or do
it from the dashboard where the mapping is explicit.

```text
Personal dimension of 'Steve' deleted.
```

| Message | Cause |
|---|---|
| `DimensionManager not available` | the manager is null — the server has not finished starting |
| `Personal dimension not found for '<player>' in '<master>'` | no online match and no name match among the stored dimensions |
| `Deletion failed.` | `deletePlayerDimension` returned false. On Windows this is usually a file lock: the world directory could not be removed because the dimension had just been unloaded |

Deletion removes the world files. There is no confirmation step and no undo.

---

## `/cg adv`

```mcfunction
/cg adv check <player>
/cg adv list
```

**No permission gate** — any player can run both. `check` prints every completed
advancement of the target, `list` prints every advancement the server knows. Recipe
advancements (`recipes/…`) are filtered out of both.

```mcfunction
/cg adv check Steve
```

```text
Advancements of Steve
  ✅ Stone Age [minecraft:story/mine_stone]
  ✅ Getting an Upgrade [minecraft:story/upgrade_tools]
147/832 advancements completed
```

| Message | Cause |
|---|---|
| `Player not found.` | the target is not online |

Both forms send one chat line per advancement. On a modded server with a few hundred
advancements that is a few hundred messages at once, which the client will throttle and
which can disconnect a player on a slow connection. It is a debugging tool that was never
gated or paged; treat it as such.

---

## `/cg setspawn`

```mcfunction
/cg setspawn
```

**Level 2.** Must be run by a player. Writes your current position **and facing** as the
spawn point of the CheeseGuard dimension config matching the world you are standing in,
then saves `config/cheeseguard_dimensions.json`.

```text
Spawn set to 118.5, 64.0, -302.5 in Boss Arena
```

| Message | Cause |
|---|---|
| `Dimension not found in config: <worldId>` | the world you are in has no CheeseGuard dimension config. Rare in practice: `DimensionManager` auto-registers every loaded world as a built-in dimension — the vanilla ones at start, later ones as they load — so `/cg setspawn` works in the overworld too |

This sets the dimension's *CheeseGuard* spawn, used when the mod teleports a player in. It
does not touch the vanilla world spawn.

---

## `/cg pasture`

```mcfunction
/cg pasture limits
/cg pasture chunk <max>
/cg pasture player <max>
```

**Level 2.** Caps on Cobblemon pasture blocks per chunk, to stop a handful of players
making a chunk unloadable.

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `max` | integer | yes | 0 or more | both limits default to `1` |

`limits` prints the current pair. `chunk` sets the total per chunk; `player` sets the
total per chunk **per player**. `0` disables that limit (unlimited), it does not forbid
pastures; with both at `0` CheeseGuard does not limit pastures at all.

```text
Pastures — max per chunk: 1 | max per chunk/player: 1
```

```mcfunction
/cg pasture chunk 4
```

```text
Per-chunk limit set to 4 pasture(s).
```

Each set saves `config/cheeseguard_pastures.json` immediately and takes effect on the next
placement attempt. No restart.

---

## `/cg restoreinv`

```mcfunction
/cg restoreinv list <player>
/cg restoreinv apply <player>
/cg restoreinv apply <player> <filename>
```

**Level 2.** Restores a player's inventory from the archive CheeseGuard writes in
`config/cheeseguard_inv_archive/` every time an event saves a player's inventory (file
name `<uuid8>_<yyyy-MM-dd_HH-mm-ss>_<playerName>.nbt`; these files are never cleaned up
automatically). This is the recovery tool for the worst class of bug this mod can cause,
so it is deliberately verbose.

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `player` | single word | yes | a player name — resolved from the online list, then the server's user cache, then the archive filenames |
| `filename` | greedy string | no | an exact archive filename from `list`; omitted means the most recent archive for that UUID |

`list` prints the archives newest first. Each line is clickable and fills the matching
`apply` command into your chat box, which is the intended way to use it — the filenames
are long.

```mcfunction
/cg restoreinv list Steve
```

```text
Archives for Steve (UUID 00000000-0000-0000-0000-000000000000) — most recent first:
  2026-10-28_19-04-11  f81d4fae_2026-10-28_19-04-11_Steve.nbt
  2026-10-27_21-40-02  f81d4fae_2026-10-27_21-40-02_Steve.nbt
```

`apply` prints the resolved UUID *before* doing anything destructive, so you can check it:

```text
Resolved Steve → UUID 00000000-0000-0000-0000-000000000000
Inventory restored online for Steve from f81d4fae_2026-10-28_19-04-11_Steve.nbt
```

The target also gets told:

```text
Your inventory has been restored from a CheeseGuard archive (2026-10-28_19-04-11).
```

Two paths, chosen automatically:

- **Online** — the archive's NBT is read into the live player and synced.
- **Offline** — the player's `playerdata` file is swapped, after a safety copy is written
  next to it. The feedback names the safety copy.

```text
Offline inventory swap done for Steve from f81d4fae_2026-10-28_19-04-11_Steve.nbt (safety copy: 00000000-0000-0000-0000-000000000000.nbt.cgrestore-1761678251000.bak)
```

If an explicit `filename` belongs to a different UUID than the player you named, the
command warns but **still applies it**:

```text
WARNING: archive UUID prefix 7d4448d9 does not match Steve (f81d4fae)
```

| Message | Cause |
|---|---|
| `Player not found: <name> (not online, not in user cache, no matching archive)` | all three resolution paths failed |
| `No archives found for <name>` | the UUID resolved but has no archive |
| `Archive file not found: <filename>` | the explicit filename does not exist |
| `Failed to read archive: <detail>` | IO error reading the NBT |
| `Archive unreadable: missing playerData/Inventory or unsupported format. See server log.` | the file parsed but has no inventory in it |
| `Restore failed — see server log` | the online write failed |
| `Offline restore failed: <detail>` | the playerdata swap failed |

Every apply is logged at INFO with the actor, the target, the archive and the mode:

```text
[CG] /cg restoreinv: actor=RoiCheese target=Steve (00000000-...) archive=f81d4fae_2026-10-28_19-04-11_Steve.nbt mode=online
```

Unlike the rest of the mod, this subcommand's messages are `Text.literal` and therefore
**English only**, whatever the client's language.

---

## `/prison`

```mcfunction
/prison
```

A separate root command, registered by `PrisonCommand`, with **level 2 on the root node** —
so the whole tree is operators only, including `list` and `info`. Bare `/prison` prints the
help:

```text
— /prison commands —
/prison set <player> <duration> <reason>
/prison release <player>
/prison list
/prison info <player>
/prison setzone <zoneId>
/prison setout
/prison clearout
```

The confinement mechanism, the doppelgänger that stands in for an offline prisoner and the
list of blocked actions are in [prison](prison.md).

### `/prison setzone`

```mcfunction
/prison setzone <zoneId>
```

Tab-completes from every zone id. Nothing else in the tree works until this is set.

```text
Prison zone set: prison
```

| Message | Cause |
|---|---|
| `Zone not found: <id>` | no zone with that id |

### `/prison set`

```mcfunction
/prison set <player> <duration> <reason>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `player` | single word | yes | tab-completes from online players, but an offline player found in the user cache also works |
| `duration` | single word | yes | a compound duration, see below |
| `reason` | greedy string | yes | any text; stored and shown to the prisoner |

Durations are parsed by `PrisonManager.parseDuration`: a sequence of digits each followed
by a unit, `s`, `m`, `h` or `d`, concatenated. `90s`, `30m`, `2h`, `7d`, and `1h30m` are
all valid, and they add up. **A bare number with no unit is read as minutes**, so `30` is
thirty minutes. Anything else — a negative sign, a unit with no digits, `2w` — returns
`-1` and is rejected. The total must be above zero.

```mcfunction
/prison set Steve 2h griefing the market
```

```text
Steve has been sent to prison for 2h.
```

The prisoner, if online, is told:

```text
⛓ You are in prison for 2h. Reason: griefing the market
```

| Message | Cause |
|---|---|
| `No prison zone configured. Use /prison setzone <zoneId>.` | run `setzone` first |
| `Zone not found: <id>` | the configured prison zone id no longer exists in `regions.json` |
| `Invalid duration: <value> (try e.g. 30m, 2h, 7d).` | the duration did not parse |
| `Player not found: <name>` | neither online nor in the user cache |

An online target is **not teleported by this command**. The handler sends the message and
leaves the tick-based confinement in `PrisonListener` to pull them into the zone on a
later tick. If the prison zone is far away, expect a visible delay and a brief moment
where the player can still act.

### `/prison release`

```mcfunction
/prison release <player>
```

Tab-completes from the active prisoner list. Releases, teleports to the configured release
point (or world spawn), and broadcasts.

```text
Steve has been released from prison.
```

```text
✨ Steve has been released from prison (pardoned).
```

| Message | Cause |
|---|---|
| `Player not found: <name>` | unresolvable name |
| `<name> is not in prison.` | no active sentence. Only reported for an **offline** target — for an online one the online release helper runs first and the command reports success regardless |

### `/prison list`

```mcfunction
/prison list
```

```text
Active prisoners (2)
• Steve — 1h 47m — griefing the market
• Alex — 12m — chat spam
```

An empty prison prints the header with `(0)` and nothing else.

### `/prison info`

```mcfunction
/prison info <player>
```

```text
Prison — Steve
Reason: griefing the market
Time remaining: 1h 47m
Imprisoned by: RoiCheese
```

| Message | Cause |
|---|---|
| `Player not found: <name>` | unresolvable name |
| `<name> is not in prison.` | no active sentence |

### `/prison setout` / `/prison clearout`

```mcfunction
/prison setout
/prison clearout
```

`setout` stores your current position, facing and dimension as the release point for every
future release. `clearout` drops it, and releases then fall back to the world spawn.

```text
Prison release point set: 118.5, 64.0, -302.5 (minecraft:overworld).
```

```text
Release point cleared (falls back to world spawn).
```

| Message | Cause |
|---|---|
| `This command must be run by a player.` | `setout` from the console |

Both write `config/cheeseguard/prison.json` immediately.

---

## What has no command

Several subsystems are dashboard-only, by design. There is no command for timelines, race
tracks, chunk sets, Discord webhooks, zone sale configuration, group flag editing or
dimension creation. Zone owners also have an in-game menu bound to a keybind on the
client, handled by `ZoneOwnerMenuHandler`, which is not a command either.

---

← [Commands](commands.md) · [Configuration](configuration.md) →
