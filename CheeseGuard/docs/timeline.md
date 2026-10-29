[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](timeline.fr.md)

# Timeline

The timeline is how an event gets a script. Instead of "this event lasts ten minutes and
applies Speed", you lay out blocks on tracks: a title at second 0, a shrinking border from
second 30, a Pokémon spawn every 45 seconds, a vote at second 300. `TimelineExecutor`
(`logic/TimelineExecutor.java`, ~1300 lines) reads that layout once per second and fires
what is due.

The editor is the dashboard's Events view, in the timeline tab. There is no in-game editor.

## Acts, tracks, blocks

Three nested things:

**An act** (`data/EventAct.java`) is a chapter. An event is a list of acts played in order,
each with its own timeline. An act has an entry title and sound, a transition rule, and
optionally a death-behavior override that replaces the event's own for its duration.

**A track** is a horizontal row in the editor. Tracks have no behaviour of their own — they
group blocks visually and give each one a colour and an icon. There are 22, declared as
constants in `data/TimelineBlock.java`:

```text
messages · potions · zones · spawns · commands · weather · sounds · score
flags · roles · votes · players · state_flags · random · objectives
death · dimension · inventory · visibility · redstone · stats · logic
```

A block's `track` value is cosmetic. Moving a `POTION` block onto the `weather` row changes
nothing about what it does. The only code that derives a track from an effect type is
`EventAct.fromLegacyPhase()`, and nothing calls that method (see
[legacy phases](#legacy-phases)).

**A block** (`data/TimelineBlock.java`) is one effect, placed in time:

| Field | JSON key | Meaning |
|---|---|---|
| `id` | `id` | 8 hex characters, generated on creation |
| `track` | `track` | which row it sits on |
| `lane` | `lane` | sub-row within the track, for overlapping blocks (omitted when 0) |
| `effectType` | `effectType` | one of the types below |
| `startSeconds` | `start` | seconds from the start of the **act**, not the event |
| `durationSeconds` | `duration` | `0` means instant/one-shot |
| `repeatIntervalSeconds` | `repeat` | `0` = no repeat; otherwise re-fire every N seconds |
| `conditionType` | `condType` | a gate evaluated before each firing |
| `conditionParam` | `condParam` | the condition's string argument |
| `conditionValue` | `condValue` | the condition's numeric argument |
| `properties` | `props` | the effect's own parameters, an arbitrary JSON object |
| `label` | `label` | overrides the auto-generated block caption |
| `colorOverride` | `color` | hex override for the block's colour |

A minimal instant block, as it is stored inside an act's `blocks` array:

```json
{
  "id": "a1b2c3d4",
  "track": "messages",
  "effectType": "TITLE",
  "start": 0,
  "duration": 0,
  "props": {
    "titleText": "Round 1",
    "subtitleText": "Good luck",
    "titleColor": "gold",
    "fadeIn": 10,
    "stay": 60,
    "fadeOut": 10
  }
}
```

A duration block with a repeat and a condition:

```json
{
  "id": "9f8e7d6c",
  "track": "potions",
  "effectType": "POTION",
  "start": 30,
  "duration": 120,
  "repeat": 10,
  "condType": "PLAYER_COUNT_ABOVE",
  "condValue": 4,
  "props": { "potionId": "minecraft:speed", "amplifier": 1, "potionDuration": 240 }
}
```

### How a block fires

`tickBlocks()` runs once per second with `elapsed` = seconds since the act began.

- **Instant block** (`duration == 0`): fires once, the first tick where
  `elapsed >= start`. With `repeat > 0` it then re-fires every `repeat` seconds, for as long
  as the act lasts — and on that first tick it actually fires **twice**, because the
  one-shot firing and the first repeat both trigger (the repeat clock starts at
  `start - repeat`).
- **Duration block**: on the first tick inside `[start, start+duration)` it runs its *start*
  handler; then its *tick* handler every second, or every `repeat` seconds if `repeat > 0`;
  then its *end* handler on the first tick at or after `start+duration`.

The act's first tick is second 1, not 0: `elapsed` is incremented before the blocks are
evaluated, so a block at `start: 0` and one at `start: 1` fire on the same tick. The block's
condition is checked before anything else, every second; while it is false the block is
skipped entirely, end handler included.

Three consequences worth knowing. A block whose `start` is beyond the act's duration never
fires. A duration block's end handler is what undoes a `MULTIPLIER` or a `FLAG_OVERRIDE`, so
a block that is still active when the act ends never gets its end handler — the event-level
cleanup unfreezes players and clears flags, but a multiplier set by an unfinished block
stays on the event object until `resetRuntime()`. And `repeat` on a duration block changes
the *tick* rate, not an extra firing: a `POTION` block with `repeat: 10` re-applies the
potion every 10 seconds instead of every second.

### Block conditions

Evaluated before each firing. `NONE` means always.

| `condType` | Uses | True when |
|---|---|---|
| `PLAYER_COUNT_BELOW` | `condValue` | fewer than N participants (spectators excluded) |
| `PLAYER_COUNT_ABOVE` | `condValue` | more than N participants |
| `SCORE_ABOVE` | `condValue` | any participant's score is above N |
| `SCORE_BELOW` | `condValue` | any participant's score is below N |
| `TEAM_SCORE_ABOVE` | `condValue` | declared, falls through to `true` — not implemented |
| `TIME_OF_DAY_DAY` | — | declared, falls through to `true` — not implemented |
| `TIME_OF_DAY_NIGHT` | — | declared, falls through to `true` — not implemented |
| `PLAYER_FLAG_TRUE` | `condParam` | at least one participant has that state flag set to `true` |
| `PLAYER_FLAG_FALSE` | `condParam` | no participant has it set to `true` |
| `RANDOM_CHANCE` | `condValue` | a fresh roll under N percent |

The three unimplemented conditions hit the `default -> true` branch, which means a block
gated on them fires unconditionally. That is the dangerous direction for a gate to fail, so
do not use them.

`SCORE_ABOVE` and `SCORE_BELOW` are "any participant", not "every participant". With a
mixed field both can be true at the same time.

## Act transitions

`checkActTransition()` runs after the blocks each second.

| `transitionType` | Advances when |
|---|---|
| `DURATION` | `actElapsedSeconds >= durationSeconds` |
| `CONDITION` | the configured `transitionCondition` is satisfied |
| `MANUAL` | never: `TimelineExecutor.advanceAct()` exists but no command or route calls it |

When the **last** act finishes, the executor sets `signalEnd` on the event and
`EventManager` takes it to `ENDING` on its next tick. A `MANUAL` act therefore never ends on
its own: the event stays in it until it is stopped (`POST /events/:id/stop`) or a victory
condition ends it.

Transition conditions:

| `transitionCondition` | Uses | Satisfied when |
|---|---|---|
| `PLAYERS_ALIVE_BELOW` | `transitionCondValue` | fewer than N participants remain |
| `SCORE_REACHED` | `transitionCondValue` | any participant's score reaches N |
| `ALL_TASKS_COMPLETE` | `transitionCondValue` | every participant's `tasks_done` flag is ≥ N |
| `VOTE_COMPLETE` | — | no vote is active for this event |
| `CUSTOM_FLAG` | `transitionCondParam`, `transitionCondValue` | any participant's named flag equals the value |
| `TIME_OF_DAY` | — | declared, returns `false` — not implemented |

`VOTE_COMPLETE` is satisfied when *no* vote is running, which includes the case where no
vote was ever started. Pair it with a `START_VOTE` block earlier in the same act, or it
advances on the first tick.

Starting an act clears all per-block runtime state — fired flags, active set, last-fired
times. A block in act 2 is evaluated from a clean slate even if an identical block ran in
act 1.

## Effect types

61 types are declared in `data/EventEffect.Type`. Not all of them are implemented in the
timeline executor; the table marks each one. An unimplemented type is still selectable in
the editor and still saved to disk — it just logs at debug level and does nothing:

```text
[CG-TL] Unhandled instant block type: APPLY_KNOCKBACK
```

### Messages and HUD

| Type | Mode | Properties | Behaviour |
|---|---|---|---|
| `BROADCAST` | instant / duration-start | `message`, `color`, `channel` (`chat`/`actionbar`/`title`), `subtitle`, `global` | sends to participants; `global: true` sends server-wide |
| `TITLE` | instant / duration-start | `titleText`, `subtitleText`, `titleColor`, `fadeIn` (10), `stay` (60), `fadeOut` (10) | title screen, times in ticks |
| `ACTIONBAR_PERSISTENT` | duration-tick | `message`, `color` | rewritten every tick interval, so it stays up |
| `OBJECTIVE_HUD_UPDATE` | — | — | **not implemented** |
| `BROADCAST_SCORE_BOARD` | — | — | **not implemented** |
| `STATS_BROADCAST` | — | — | **not implemented** |

### Potions, sounds, weather, time

| Type | Mode | Properties | Behaviour |
|---|---|---|---|
| `POTION` | duration-tick | `potionId` (`minecraft:speed`), `amplifier` (0), `potionDuration` (1200 ticks), `target` | re-applies every tick interval |
| `SOUND` | instant | `soundId`, `volume` (1.0), `pitch` (1.0), `target` | played per player |
| `WEATHER` | instant / duration-start | `weatherType` (`clear`) | sets the event world's weather |
| `TIME` | instant / duration-start / tick | `timeValue` (6000), `freeze` | with `freeze: true` the time is re-set every tick, which is what holds it still |
| `PARTICLE` | — | — | **not implemented** in the timeline (legacy phase path only) |

`potionDuration` should comfortably exceed the tick interval, or players flicker in and out
of the effect. The default of 1200 ticks (60 s) against a 1 s tick is deliberately generous.

### Commands and randomness

| Type | Mode | Properties | Behaviour |
|---|---|---|---|
| `COMMAND` | instant / duration-start + tick | `command`, `target` | run from the console source (level 4) once **per targeted player**, with `{player}`, `{uuid}`, `{x}`, `{y}`, `{z}` substituted |
| `COMMAND_PLAYER` | instant | `command`, `target` | run as each targeted player |
| `WEIGHTED_RANDOM_COMMAND` | instant | `cmd0`, `weight0`, `cmd1`, `weight1`, … , `target` | picks one command by weight |
| `CONDITIONAL_BRANCH` | — | — | **not implemented** |

The weighted-random block reads `cmd0`, `cmd1`, … until a key is missing, with `weightN`
defaulting to 1. A gap in the numbering silently truncates the list.

`COMMAND` on a duration block fires at *start* **and** on every tick. A block with
`duration: 60` and no `repeat` runs its command 61 times per targeted player (60 if it starts
at second 0, since the first tick is second 1). Use `duration: 0` for a one-shot.

`target`, wherever a block reads it, accepts `all` (the default), `role` (players whose
`role` state flag equals the block's `targetRole` property) and `random` (each player
independently, with a 50 % chance). Any other value — `zone` and `team` included — means
everyone. The player list is every online member of the event, **spectators included**.

### Zones

| Type | Mode | Properties | Behaviour |
|---|---|---|---|
| `ZONE_SHRINK` | duration-tick | `zoneCenterX`/`cx`, `zoneCenterZ`/`cz`, `radiusInitial` (200), `radiusFinal` (50), `curve` (`linear`), `zoneDamage`/`damagePerSec` (1.0), `showBorder`, `orbitEnabled`, `orbitRadius`, `orbitSpeed` | interpolates the radius over the block's duration and damages players outside |
| `ZONE_HEAL` | duration-tick | `amount` (1.0), `target` | heals each tick |
| `ZONE_DAMAGE` | duration-tick | `amount` (1.0), `target` | damages each tick |
| `ZONE_POWERUP` | duration start/tick/end | `powerX`, `powerY`, `powerZ`, `powerWorld`, `powerRadius` (3.0), `powerType` (`SPEED`), `powerAmplifier` (1), `powerDurationTicks` (200), `singleUse`, `cooldownSeconds`, `powerLabel`, `visualBlock` (`minecraft:end_rod`) | a pickup pad: places a visual block, grants the power on contact, removes the block at the end |
| `ZONE_LOOT` | — | — | **not implemented** |
| `ZONE_MOVE` | — | — | **not implemented** |
| `FLAG_OVERRIDE` | instant / duration start+end | `flagId`, `targetZone`, `flagValue` | forces a zone flag for the block's duration and restores it at the end |

`ZONE_SHRINK` reads two spellings of its centre (`zoneCenterX`, and the older `cx`) for
backward compatibility with blocks saved under the old keys. Border particles are drawn from a
separate 3-tick loop in `EventManager`, not from the block's own tick, which is why the
border looks smooth while damage is applied once per second.

### Spawns

| Type | Mode | Properties | Behaviour |
|---|---|---|---|
| `ENTITY_SPAWN` | instant | `command` | runs a spawn command from the console |
| `POKEMON_SPAWN` | instant | `species`, `level` (50), `x`, `y` (64), `z`, `world` | runs Cobblemon's `pokespawn <species> level=<n> pos=<x> <y> <z>` in that world; does nothing without Cobblemon |
| `SPAWN_ITEM_AT_POS` | instant | `itemId` (`minecraft:diamond`), `x`, `y` (64), `z` | drops an item entity |
| `SPAWN_STRUCTURE` | duration-start | `structureName`, `x`, `y` (64), `z` | places a structure |
| `SET_BLOCK_AT_POS` | instant | `blockId` (`minecraft:air`), `bx`, `by` (64), `bz`, `world` | one `setblock` |
| `COBBLEMON_FORCE_ENCOUNTER` | — | — | **not implemented** |
| `RESPAWN_ENTITY_AT_POS` | — | — | **not implemented** |
| `RANDOM_LOOT_AT_POSITIONS` | — | — | **not implemented** |
| `REDSTONE_ACTIVATE` | — | — | **not implemented** |

### Players

| Type | Mode | Properties | Behaviour |
|---|---|---|---|
| `FREEZE_PLAYERS` | instant / duration start+end | — | participants cannot move; enforced every tick and released at the end |
| `UNFREEZE_PLAYERS` | instant | — | releases everyone |
| `GIVE_ITEM_TO_PLAYER` | instant | `itemId` (`minecraft:diamond`), `count` (1), `target` | |
| `TAKE_ITEM_FROM_PLAYER` | instant | `itemId`, `target` | runs `clear <player> <itemId>`: removes every matching item, there is no count |
| `TELEPORT_PLAYER_TO_PLAYER` | instant | `targetRole` | teleports players onto the holder of a role |
| `ITEM_DROP` | — | — | **not implemented** in the timeline (legacy phase path only) |
| `SET_PLAYER_HEALTH` | — | — | **not implemented** |
| `SET_PLAYER_HUNGER` | — | — | **not implemented** |
| `APPLY_KNOCKBACK` | — | — | **not implemented** |
| `LOCK_HOTBAR` / `UNLOCK_HOTBAR` | — | — | **not implemented** |
| `DIMENSION_TELEPORT_ALL` | — | — | **not implemented** |
| `INVENTORY_DISTRIBUTE` | — | — | **not implemented** |
| `SET_PLAYER_VISIBILITY` | — | — | **not implemented** |

Freeze is held by the executor, not by the block: the frozen set lives per event and is
cleared in `end()`. A `FREEZE_PLAYERS` instant block with no matching `UNFREEZE_PLAYERS`
keeps players still until the event ends.

### Roles, votes and state flags

| Type | Mode | Properties | Behaviour |
|---|---|---|---|
| `ASSIGN_ROLE_PRIVATE` | instant | `roleName`, `strategy` (`random`/`first`/`all`), `count` (1), `revealMessage`, `revealColor`, `otherRoleName`, `otherRevealMessage`, `otherRevealColor` | assigns a hidden role to N players and optionally a second role to everybody else |
| `TRANSFER_ROLE` | instant | `fromRole`, `toRole` | renames a role on whoever holds it |
| `START_VOTE` | instant | `question`, `voteType` (`OPTION_VOTE`), `duration` (30), `opt0`, `opt1`, … | opens a vote through `VoteManager` |
| `PLAYER_FLAG_SET` | instant | `flagKey`, `flagValue`, `target` | writes a per-player state flag |
| `CHANGE_TEAM_COLOR` | — | — | **not implemented** |

Roles and state flags both go through `PlayerStateFlags`, scoped per event and wiped when
the event ends. The hidden role is stored under the flag key `role`, which is also what the
`target: "role"` filter reads — that is how a block can address "only the impostors".

Vote options, like weighted commands, are read as `opt0`, `opt1`, … until a key is missing.

### Score and death

| Type | Mode | Properties | Behaviour |
|---|---|---|---|
| `MULTIPLIER` | duration start+end | `multType` (`score`), `multValue` (2.0) | stores a multiplier on the event while active and clears it at the end — but nothing reads it, so scores are unchanged (see [Events](events.md#scores)) |
| `DEATH_BEHAVIOR_OVERRIDE` | instant | `behavior` (`RESPAWN_EVENT`) | replaces the event's death behaviour for the rest of the run |

### Cross-mod effects

Nine effect types reach into other mods. All of them go through reflection hooks in
`logic/`, so CheeseGuard compiles and runs with none of those mods installed. Each hook
resolves its target class once at server start and logs the outcome:

```text
[CG] CobbleShift detected — transform effects enabled
[CG] CheeseKart not installed — kart effects disabled
[CG] CobblemonDungeons detected — dungeon effects enabled
```

| Type | Target mod | Properties | If the mod is missing |
|---|---|---|---|
| `TRANSFORM_ALL` | CobbleShift | `transformSpecies` (`pikachu`) | `[CG-TL] TRANSFORM_ALL: CobbleShift not available` at **warn**, block does nothing |
| `TRANSFORM_RANDOM` | CobbleShift | `transformSpeciesList` (`pikachu,charizard,mewtwo`), `allowDuplicates` (true) | same, with the type name in the message |
| `TRANSFORM_BY_TEAM` | CobbleShift | `transformTeamMap` (a JSON object as a string) | same |
| `TRANSFORM_REVERT_ALL` | CobbleShift | — | `CobbleShiftHook.revertAll()` returns silently; nothing happens |
| `KART_START_ALL` | CheeseKart | `kartPresetId` (`rally`), `kartSpeciesOverride` (empty) | `[CG] Cannot start karts — CheeseKart not available` at warn |
| `KART_STOP_ALL` | CheeseKart | — | returns silently |
| `DUNGEON_OPEN` | CobblemonDungeons | `dungeonId`, `dungeonTier` (1) | `[CG-TL] DUNGEON_OPEN: CobblemonDungeons not available` at warn |
| `DUNGEON_TICKET_GIVE` | CobblemonDungeons | `dungeonId`, `dungeonTier` (1), `ticketCount` (1) | same |
| `DUNGEON_MODIFIER` | CobblemonDungeons | `dungeonId`, `modifierType` (`DOUBLE_LOOT`), `modifierValue` (2.0) | runs anyway — see below |

The type is spelled **`TRANSFORM_REVERT_ALL`**, not `REVERT_ALL`. Some older notes use the
short name; the enum constant and the stored `effectType` value are the long one.

`TRANSFORM_BY_TEAM` does not read teams from any team system. It splits the participant
list into blocks of `event.teamSize` by their position in the collection, and looks up block
index 0, 1, 2 … as the **string keys** `"0"`, `"1"`, `"2"` … of `transformTeamMap`
(`{"0":"charizard","1":"blastoise"}`); a block with no matching key is not transformed. Participant order comes from a
`HashMap`'s values, so it is not stable between runs. Treat team assignment here as
arbitrary-but-balanced, not as "the red team becomes Charizard".

`DUNGEON_OPEN` breaks out of its loop after the first participant: it starts one dungeon
instance with the first player as leader, whoever that happens to be. It also checks
`dungeonExists()` first and logs `dungeon '<id>' not found` rather than throwing.

`DUNGEON_MODIFIER` is **not wired into CobblemonDungeons at all**. It does not check
`isAvailable()`, it does not call the hook, and it does not modify anything. It logs
`(broadcast only)` and sends every online player a translated chat line. The comment in the
source says the integration is future work. If you place this block expecting double loot,
you get a message and nothing else.

## Interaction with mini-games

A `MINIGAME`-mode event runs its timeline the same way, but `MiniGameManager` also drives
its own per-type logic on faster ticks (every 4 ticks for Thimble landings, every tick for
race checkpoints). The two do not coordinate: a timeline block that teleports players during
a race will fight the race logic. Keep timelines on mini-game events to messaging and
cosmetics unless you have read the mini-game's code.

## Legacy phases

Before acts, an event had `phases`, each with a flat list of `EventEffect` objects. What
decides which structure runs is whether `acts` is empty: an event with at least one act runs
the timeline, an event with none runs its phases on the legacy path (`useActSystem` is
stored but not consulted for that). Nothing converts one into the other.
`ServerEvent.migrateToActSystem()` and `EventAct.fromLegacyPhase()` — one act per phase,
each effect turned into a block with `start: 0` and `duration` equal to the phase length —
exist in the code but have no caller.

`PARTICLE` and `ITEM_DROP` are the two effects that work on the legacy phase path but have no
handler in `TimelineExecutor`. Rebuilding a phase-based event as acts loses them.

---

← [Events](events.md) · [Players](players.md) →
