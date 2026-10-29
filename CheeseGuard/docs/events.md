[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](events.fr.md)

# Events

An *event* in CheeseGuard is a timed server-wide happening with a participant list, a
score table, a lifecycle and — optionally — a scripted timeline. It is the subsystem that
turns a static zone into a tournament arena, a fishing contest or a battle royale.

Events live in `config/cheeseguard_events.json`. `EventManager` (`logic/EventManager.java`)
owns them in memory, ticks them once per second from the server thread, and writes the
file on every meaningful mutation. The dashboard's Events view is the only supported
editor — there is no in-game GUI for building an event.

## The model

Each event is a `ServerEvent` (`data/ServerEvent.java`). The fields fall into groups, and
you rarely touch more than two or three groups for a given event.

| Group | Fields | What it decides |
|---|---|---|
| Identity | `id`, `name`, `description`, `displayColor` | what players see in broadcasts and the bossbar |
| Mode | `mode`, `miniGameType`, `miniGameConfig` | plain server event, or a mini-game driven by `MiniGameManager` |
| Trigger | `trigger`, `scheduledDelayMinutes`, `recurringIntervalMinutes`, `lunarEventId`, `triggerZoneId`, `playerLaunchable` | how the event starts without an admin |
| Timing | `announceDurationSeconds`, `countdownSeconds`, `cooldownMinutes`, `lastRunTimestamp` | the length of each pre-start phase and the gap between runs |
| Scope | `scopeZoneIds` | which zones the event's effects apply to (empty = whole server) |
| Participation | `minParticipants`, `maxParticipants`, `teamMode`, `teamSize`, `deathBehavior`, `endOnAllDead` | who may join and what happens when they die |
| Messaging | `announceMessage`, `startMessage`, `endMessage` plus their colors, `bossbarEnabled`, `bossbarColor`, `discordNotify` | the player-facing noise |
| Teleports | `tpParticipant*`, `tpSpectator*`, `tpParticipantZoneIds`, `tpSpectatorZoneIds`, `returnMode`, `return*` | where people go at start and where they come back to |
| Inventory | `saveInventory`, `forceEmptyInventory`, `kitEntryCommands`, `kitExitCommands`, `blacklistItems`, `blacklistEnchants`, `blacklistPokemon`, `disablePokemonSummon` | the equipment contract |
| Victory | `victoryCondition`, `victoryTargetScore`, `scoreTriggers`, `rewards` | how points are earned and who gets paid |
| Structure | `useActSystem`, `acts`, `phases` | the scripted content — see [Timeline](timeline.md) |
| Chaining | `chainEventId`, `exclusionGroup`, `exclusionPriority` | what runs after, and what must not run at the same time |

`maxParticipants = 0` means unlimited, not "nobody". `scopeZoneIds` empty means global,
not "no zones". Both defaults are deliberate: a fresh event is server-wide and open.

### Enumerations

```text
EventMode          SERVER_EVENT | MINIGAME
TriggerType        MANUAL | SCHEDULED | RECURRING | LUNAR | ZONE_ENTRY
TeamMode           SOLO | DUOS | TEAMS | COOP
VictoryCondition   TIMER | SCORE_MAX | FIRST_TO_X | SURVIVAL | COOP_GOAL
DeathBehavior      RESPAWN_EVENT | BECOME_SPECTATOR
```

`LUNAR` only fires when Enhanced Celestials is installed; `EventManager` probes for it at
startup and, if the mod is absent, a `LUNAR` event simply never auto-triggers. Nothing is
logged per tick about it, so an event that "never starts" is worth checking against this
first.

## Lifecycle

`ServerEvent.State` is:

```text
IDLE | ANNOUNCED | QUEUE | COUNTDOWN | ACTIVE | ENDING | CLEANUP
```

There is **no `PAUSED` state**, and there is no pause or resume anywhere in the mod — not
in the API, not in the commands, not in the manager. An event that has started runs to its
end or is stopped outright. If you need a break in the middle of a run, that is what a
`MANUAL`-transition act is for ([Timeline](timeline.md#act-transitions)).

The states that the running code actually assigns are `IDLE`, `ANNOUNCED`, `COUNTDOWN`,
`ACTIVE` and `ENDING`. `QUEUE` and `CLEANUP` are declared in the enum but no code path
sets them: the queue is tracked separately by `MiniGameManager`, and cleanup runs
synchronously inside `cleanupEvent()` and leaves the event in `IDLE`. Treat those two
constants as reserved.

The transitions, as `EventManager.tickEvent()` drives them at 1 Hz:

| From | To | When |
|---|---|---|
| `IDLE` | `ANNOUNCED` | `announceEvent()` — API, command, or a matching auto-trigger |
| `IDLE` | `ACTIVE` | `startEventDirect()` — skips announce and countdown entirely |
| `ANNOUNCED` | `COUNTDOWN` | after `announceDurationSeconds`, if `participantCount >= minParticipants` |
| `ANNOUNCED` | `IDLE` | after `announceDurationSeconds` with too few participants — broadcast and cancel |
| `COUNTDOWN` | `ACTIVE` | the per-second counter reaches zero |
| `ACTIVE` | `ENDING` | `endEvent()`, or a victory condition is met |
| `ENDING` | `IDLE` | 5 seconds after entering `ENDING`, via `cleanupEvent()` |

The countdown is decremented once per 1 Hz tick rather than compared against wall-clock
time, so a lagging server stretches the countdown instead of skipping numbers. Titles are
pushed to participants at 30, 10 and then every second from 5 down.

`isRunning()` is `state != IDLE`. `isActive()` is `state == ACTIVE`. The dashboard's
summary payload exposes both, because "running" covers the pre-start phases and "active"
does not.

### Auto-triggers

Every 5 seconds `tickAutoTriggers()` walks the `IDLE` events and decides whether to
announce them. Before looking at the trigger it applies two gates:

1. **Cooldown** — if `cooldownMinutes > 0` and the last run was more recent than that, skip.
2. **Exclusion group** — if `exclusionGroup` is non-empty and any *other* event in the same
   group is running, skip.

Then, by trigger type: `RECURRING` announces when `recurringIntervalMinutes` has elapsed
since the last run (or immediately if it has never run); `SCHEDULED` announces once
`scheduledDelayMinutes` has elapsed since the last run, or since server start if there was
none; `LUNAR` announces while the named Enhanced Celestials event is active; `ZONE_ENTRY`
announces as soon as any online player stands inside `triggerZoneId`. `MANUAL` does
nothing here, by design.

A `MINIGAME`-mode event with a non-empty queue is also announced when
`MiniGameManager.checkQueueReady()` says there are enough players.

### Cleanup

`cleanupEvent()` is the part worth reading before you change anything, because the order
matters and it was arrived at by fixing things that went wrong:

1. Stop the timeline executor.
2. Kill mobs left over from the end-of-event lightning, if `lightningMobsOnEnd` was set.
3. Leave the voice-chat group, if `voiceChatGroup` was set.
4. Run `kitExitCommands` **while players are still in the event world**, so that a command
   like `effect clear @s` resolves in the right context.
5. Restore saved inventories; for players who went offline mid-event, write a pending
   restore to disk instead.
6. Distribute rewards — **after** the inventory restore, so prizes are not wiped by it.
7. Teleport participants back according to `returnMode`.
8. Remove the bossbar and the scoreboard.
9. Stamp `lastRunTimestamp`, announce the chained event if `chainEventId` is set, reset the
   runtime fields, save.

### Crash safety

When `saveInventory` is on, each participant's inventory is written to disk as NBT under
the event's pending-restore directory at the moment they enter, not only kept in memory.
If the server dies mid-event, the restore is replayed the next time that player logs in —
`tickPendingRestores()` retries every 3 seconds while anything is pending. Spectators who
crashed get a lighter record that only restores their game mode, and a CobbleShift revert
is queued for them so they do not come back as a Pokémon.

## Participants

A participant is an `EventParticipant` (`data/EventParticipant.java`), keyed by UUID. The
collection is a `Map<UUID, EventParticipant>` — `evt.getParticipants()` returns the map, so
the iteration idiom everywhere in the codebase is `getParticipants().values()`. The same is
true of the dashboard helper: `EventsApi.getParticipants()` hands back a `Map`, **not** a
`List`. Code that assumes a list compiles against the wrong type and fails at the first
`for`.

Roles are `PARTICIPANT` and `SPECTATOR`. Joining is only accepted while the event is
`ANNOUNCED`, `COUNTDOWN` or `ACTIVE` — `joinEvent()` returns `false` outside those three
states, and the API turns that into a `500` with `Failed to add participant`.

Spectators are protected in two small ways that are easy to miss: anything riding a
spectator is dismounted once per second, and a spectator's original game mode is part of
the crash-restore record.

## Scores

Points come from three directions.

**Score triggers.** `ServerEvent.ScoreTrigger` pairs a trigger type with a filter and a
point value. The types are:

```text
KILL_MOB | KILL_POKEMON | CATCH_POKEMON | FISH_CATCH | FISH_POKEMON | BLOCK_BREAK
BLOCK_PLACE | ITEM_PICKUP | PROJECTILE_HIT | ENTER_ZONE | PLAYER_KILL
STAY_IN_ZONE | REACH_ZONE | CRAFT_ITEM | WIN_BATTLE | CUSTOM_COMMAND
```

`filter` narrows the trigger to one entity, item or zone id; left empty, it matches
anything of that kind. The detection points are mixins — `EventMobKillMixin`,
`EventBlockBreakMixin`, `EventFishCatchMixin`, `EventCraftItemMixin` and the rest — which
is why scoring works for vanilla actions without any command plumbing.

**Timeline multipliers.** A `MULTIPLIER` block raises or lowers the points awarded for a
score type while it is active. See [Timeline](timeline.md).

**Direct adjustment.** `POST /events/:id/score` with an `operation` of `add`, `subtract` or
`set`. This is the admin override, and it is also how an external mod feeds points in
through `EventScoreHook`.

`victoryCondition` decides what the score is for:

| Condition | Ends the event when |
|---|---|
| `TIMER` | the configured duration runs out; highest score wins |
| `SCORE_MAX` | the duration runs out; same as `TIMER` but the scoreboard is the point |
| `FIRST_TO_X` | any player reaches `victoryTargetScore` |
| `SURVIVAL` | one participant is left alive |
| `COOP_GOAL` | the *combined* score reaches `victoryTargetScore` |

Rewards are `EventReward` entries: a `rank` of `"1"`, `"2"`, `"3"`, `"top10"`, `"all"` or
`"lottery"`, a list of commands with a `{player}` placeholder, and `lotteryCount` for the
lottery rank. They are run against the ranked snapshot taken when the event ended, not
against the live table.

## Announcements

`announceEvent()` moves the event to `ANNOUNCED` and broadcasts `announceMessage` in
`announceColor`. From then until the countdown ends, players may join. The bossbar, when
`bossbarEnabled` is set, is updated every second for any non-`IDLE` event, and
`discordNotify` sends the announcement through `logic/DiscordWebhook.java` as well.

The three messages — `announceMessage`, `startMessage`, `endMessage` — are stored on the
event as literal strings with their own color names, and broadcast with `Text.literal`.
They are therefore not translated: whatever you type in the dashboard is what every player
sees, in every language.

## Templates

A template is a frozen copy of an event's configuration, saved as JSON so that the same
tournament can be rebuilt next week without re-entering forty fields.

Templates live in:

```text
config/cheeseguard_templates/
```

Not `config/templates/`. The directory is created on first save.

`EventTemplate.save()` serialises the event, strips `lastRun`, and adds `templateName`,
`templateCreated` and `templateSource`. The filename is the template name lowercased with
every character outside `[a-zA-Z0-9_-]` replaced by `_`, so `Tournoi d'été #2` becomes
`tournoi_d_t__2.json` — two different names can therefore collide on one file. Keep
template names simple.

`EventTemplate.load()` reads the file, assigns a **new** id of the form
`evt_<millis % 100000>`, zeroes `lastRun`, and returns a fresh `ServerEvent`. The modulo
means the id space is five digits wide: creating two events in the same millisecond window
of a 100-second cycle can produce the same id. In practice this has not bitten, but it is
not a UUID and should not be treated as one.

The load path is also where the two API routes differ. `POST /events` with a
`templateName` builds the event and returns it **without adding it to the manager** —
nothing is persisted. `POST /events/templates/:name/load` builds it, appends it to the
event list and saves. The second is almost always what you want; the first is useful only
for previewing a template's resolved fields.

```text
[CG] Template saved: Tournoi Hiver
[CG] Template loaded: Tournoi Hiver -> event evt_48213
```

## REST routes

The full reference, with bodies and status codes, is in [Dashboard API](dashboard.md#events).
In short:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/events` | summaries of every event |
| `POST` | `/events` | create from scratch or resolve a template |
| `GET` `PUT` `DELETE` | `/events/:id` | read, patch, delete one event |
| `POST` | `/events/:id/announce` `start` `stop` | drive the lifecycle |
| `GET` `POST` | `/events/:id/participants` | list, add |
| `DELETE` | `/events/:id/participants/:uuid` | remove |
| `POST` | `/events/:id/score` | adjust a participant's score |
| `POST` | `/events/:id/save-template` | freeze this event as a template |
| `GET` | `/events/templates` | template names |
| `POST` `DELETE` | `/events/templates/:name/load`, `/events/templates/:name` | load, delete |
| `GET` | `/events/minigame-types` | the mini-game catalogue |
| `GET` `PUT` | `/events/launch-config` | the player-launch settings |
| `GET` `POST` `DELETE` | `/events/prophunt/icons[/:key]` | PropHunt icon store |

`PUT /events/:id` is a patch, not a replacement: only the keys present in the body are
applied. Sending `{"countdownSeconds": 5}` changes the countdown and nothing else. Three
list fields are exceptions — `kitEntryCommands`, `kitExitCommands`, `blacklistItems` and
`acts` are *cleared and refilled* when present, so sending an empty array deletes their
contents.

A bad enum value in a `PUT` body — `"mode": "MINIGAMES"`, say — throws inside the
server-thread task and surfaces as a `500` carrying the `IllegalArgumentException`
message. The event is left partially patched, because the setters before the bad one have
already run. Validate enum values client-side.

## Known rough edges

- Event ids from templates are not collision-proof (see above).
- A `PUT` that fails mid-way leaves the event half-updated and still saved.
- `QUEUE` and `CLEANUP` exist in the state enum but are never assigned.
- `phases` is the legacy structure, superseded by `acts`. Old events are migrated through
  `EventAct.fromLegacyPhase()` on load, which distributes every phase effect as a timeline
  block starting at second 0 of the act. The mapping is mechanical and rarely produces the
  timing you would have chosen by hand — re-check any migrated event before running it.

---

← [Dimensions](dimensions.md) · [Timeline](timeline.md) →
