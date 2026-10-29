[← CheeseRaids](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

CheeseRaids exposes one command, `/cheeseraids`, with seven subcommands. Six of them
require **permission level 2** — the level a vanilla server grants to an operator. The
seventh, `enter`, has no permission requirement at all and is the only player-facing
branch.

Every reply is sent to the caller only, formatted light purple, and **in French** — the
mod's language files contain a single key (`cheeseraids.title`) and every message is a
hardcoded `Text.literal`. There is nothing to translate by swapping a resource pack.

Every subcommand except `reload` and `status` takes the raid id as its first argument, as
a single word.
There is **no tab completion** for it: the argument is a plain `StringArgumentType.word()`
with no suggestion provider, so you have to know the id. `/cheeseraids status` lists them.

---

## `/cheeseraids start`

```mcfunction
/cheeseraids start <raid>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `raid` | single word | yes | a loaded raid id, as shown by `status`. No completion | — |

Permission level 2. A player below it does not see the subcommand in tab completion and
gets the vanilla *Unknown or incomplete command* error.

### What it does

1. Looks the definition up by id. Refuses if it does not exist or has `enabled: false`.
2. Refuses if an instance of that raid is already active. One instance per raid id, ever
   — different raid ids can run at the same time.
3. Creates a `RaidInstance`, stamps `startedAt`, and transitions it to `ANNOUNCE`, which
   broadcasts the title and chat line, hides the herald, and forces a thunderstorm over
   the wave arena.
4. Writes `config/cheeseraids/instances/<raid>.json` so a crash mid-raid recovers.
5. Broadcasts a `raid_update` message on the dashboard's `raids` WebSocket channel.

From there the raid runs itself. Everything after `ANNOUNCE` is driven by the server tick
— see [the raid model](raid-model.md#phases).

### Example

```mcfunction
/cheeseraids start hoopa
```

```text
Raid 'hoopa' démarré.
```

The console, at the same moment:

```text
[CheeseRaids] Wave for hoopa: spawned 5/5 mob(s) directly.
```

(that line arrives `wave.graceSecondsBeforeWave` seconds later, five by default).

### Failures

| Message | Cause |
|---|---|
| `Impossible de démarrer '<id>' (inconnu/désactivé/déjà actif).` | one of three things: no definition with that id, `enabled: false`, or an instance is already running |

The single message covers all three cases, which is unhelpful when you are debugging.
`/cheeseraids status` disambiguates: an id absent from the list is unknown, an id listed
as `[idle]` with the command still refusing is disabled, and anything else is already
active.

The command's numeric result is 1 on success and 0 on refusal, so it is usable in
`/execute store`.

---

## `/cheeseraids stop`

```mcfunction
/cheeseraids stop <raid>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `raid` | single word | yes | the id of a **running** raid |

Permission level 2.

Runs the cleanup path immediately, whatever phase the raid is in. That means: clear
weather restored over the wave arena, every player's parkour branch air-filled, the boss
arena volume cleared, wave mobs and the boss discarded, the herald hidden, reveal
animations dropped, the instance removed from memory and its JSON file deleted.

**Players are not teleported out.** `stop` does not call the eject path — only the victory
sequence and a combat timeout do. A player inside the rift when you stop a raid is left
standing in a dimension whose floor has just been deleted. Teleport them yourself first,
or accept that they will fall into the void of a dimension with `enabled: false`.

```mcfunction
/cheeseraids stop hoopa
```

```text
Raid 'hoopa' stoppé.
```

```text
[CheeseRaids] Raid 'hoopa' ended and cleaned up.
```

| Message | Cause |
|---|---|
| `Aucune instance active pour '<id>'.` | nothing is running under that id. A definition that exists but is idle gives this too |

---

## `/cheeseraids reload`

```mcfunction
/cheeseraids reload
```

No arguments. Permission level 2.

Re-reads every `*.json` in `config/cheeseraids/raids/` and replaces the in-memory
definition map. Instances are untouched — this is a config reload, not a restart.

```text
Définitions de raids rechargées.
```

The reply is unconditional: it says the same thing whether the files parsed or not. A
malformed file is **left on disk untouched** (it is never overwritten with defaults), so a
broken edit looks exactly like a
successful one from in-game. Worse, the previously loaded definition does **not** survive:
the reload empties the map before parsing, so a raid whose file fails to parse vanishes
from memory — `status` no longer lists it, `start` refuses it, and an instance of it that
is running stops advancing (the tick skips instances with no definition) until the file is
fixed and reloaded. The console is the only place the truth appears:

```text
[CheeseRaids] FAILED to parse raid def .../hoopa.json — fix the JSON syntax and
/cheeseraids reload. Left untouched. (Expected ':' at line 12 column 5)
```

One more quirk: the default `hoopa.json` is seeded only when the directory contains **no**
`.json` files at all. If every file in it is malformed, you end up with zero definitions
loaded and no default written.

---

## `/cheeseraids status`

```mcfunction
/cheeseraids status
```

No arguments. Permission level 2. This is the only inventory of loaded raid ids the mod
offers in-game.

```text
Raids:
• hoopa [ANNOUNCE, 0 j] (auto 21h30)
• wailord_tide [idle]
```

| Field | Meaning |
|---|---|
| the id | the definition's `id`, the word every other subcommand takes |
| `[<PHASE>, N j]` | green, when an instance is running: the current phase and the participant count (`j` for *joueurs*) |
| `[idle]` | grey, when no instance is running. Says nothing about `enabled` |
| `(auto HHhMM)` | shown only when `schedule.autoTrigger` is true |

The phase names are the `RaidPhase` enum verbatim: `ANNOUNCE`, `OVERWORLD_WAVE`,
`NPC_REVEAL`, `OPEN`, `COMBAT`, `REWARD`, `CLEANUP`. `IDLE` never appears here because an
idle instance is not kept in memory.

The participant count is everyone who entered, including players who have since
disconnected — the per-player state survives a relog on purpose.

---

## `/cheeseraids enter`

```mcfunction
/cheeseraids enter <raid>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `raid` | single word | yes | the id of a raid currently in the `OPEN` phase |

**No permission requirement.** `enter` is the player-facing subcommand; every other one
needs permission level 2. Like
`testreward`, it must be run by a player rather than by the console or a command block.

It is also the fallback entry path. Normally players enter by talking to the Ho-Oh
herald; when the herald cannot be found, the mod broadcasts this command in chat instead:

```text
La faille de Hoopa s'ouvre ! Tapez /cheeseraids enter hoopa pour entrer.
```

### What it does, first time

1. Checks the raid is active and in `OPEN`.
2. Ensures the rift dimension exists, creating it through CheeseGuard if needed.
3. Claims the lowest free slot, up to `arena.maxSlots`.
4. Derives a per-player seed from the player's UUID, the raid id and the slot, so the
   branch layout is deterministic and identical across relogs.
5. Generates the branch: the spawn platform and its gateway appear at once, the rest of
   the path is cleared to air and then animated in, `arena.revealBlocksPerTick` blocks per
   tick after `arena.revealDelayTicks` ticks of build-up.
6. Teleports the player to the spawn platform, facing down the path.

### What it does on re-entry

Nothing is regenerated. The player is teleported to their last checkpoint, or to their
spawn platform if they never reached one. This is deliberate and load-bearing: a relog
must never rebuild a branch or reset progress.

### Failures

| Message | Cause |
|---|---|
| `Aucune invasion active.` | no instance under that id, or no definition |
| `La faille n'est pas ouverte.` | the raid is running but not in `OPEN` — too early (wave still up) or too late (boss fight started) |
| `La faille n'a pas pu s'ouvrir.` | the rift dimension could not be created or resolved. Check the console for `Failed to create rift dimension` |
| `La faille est pleine.` | all `arena.maxSlots` branches are taken |
| `Réservé aux joueurs.` | run from the console or a command block |

A failure returns 0 and changes nothing — no slot is consumed and no branch is generated.

---

## `/cheeseraids herald`

```mcfunction
/cheeseraids herald <raid> info
/cheeseraids herald <raid> show
/cheeseraids herald <raid> hide
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `raid` | single word | yes | a loaded raid id |
| the action | literal | yes | `info`, `show` or `hide` |

Permission level 2. Note the order: **the raid id comes before the action.** The mod's
earlier README had it the other way round.

These are calibration tools, not part of running a raid. They work whether or not a raid
is active.

### `info`

Prints where the herald is expected and every EasyNPC the server can see near it. This is
the single most useful command in the mod during setup.

```mcfunction
/cheeseraids herald hoopa info
```

```text
Héraut attendu: 118, 71, -2050 dans minecraft:overworld
EasyNPC dans 10 blocs: 1
  • easy_npc:humanoid_slim @ 118, 71, -2050 (dist 0.5, invisible=true)
```

Three things it can tell you instead. `Monde '<id>' non chargé.` means
`npc.pos.world` does not name a loaded world. `Autres EasyNPC dans 128 blocs (mauvaise
position ?)` lists NPCs that exist but sit outside the 10-block search box — a coordinate
typo. `⚠ AUCUN EasyNPC chargé à proximité` means nothing was found within 128 blocks
either, which is usually an unloaded chunk rather than a missing NPC.

### `show` and `hide`

Flip the `VISIBLE` display attribute of every EasyNPC within 10 blocks of `npc.pos`, and
the vanilla glow outline with it.

```mcfunction
/cheeseraids herald hoopa show
```

```text
1 EasyNPC rendu(s) visible(s).
```

```mcfunction
/cheeseraids herald hoopa hide
```

```text
1 EasyNPC masqué(s).
```

| Message | Cause |
|---|---|
| `Raid inconnu.` | no definition with that id |
| `0 EasyNPC rendu(s) visible(s).` | nothing found in the box. Run `info` |

A subtlety in the count: it reports how many EasyNPC entities were **found**, not how many
were successfully toggled. If EasyNPC's display API has changed, you can get
`1 EasyNPC rendu(s) visible(s).` with no visible change in-world. The console separates
the two:

```text
[CheeseRaids] herald setVisible(true) — 1 EasyNPC(s) near 118, 71, -2050, 1 toggled via display-attr
```

`1 toggled` is the number that matters. `0 toggled` with `1 EasyNPC(s) near` means the
reflection failed; see [integrations](integrations.md#easynpc).

The numeric result is 1 when at least one NPC was found, 0 otherwise, and `-1` is
converted into the `Raid inconnu.` message.

---

## `/cheeseraids testreward`

```mcfunction
/cheeseraids testreward <raid>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `raid` | single word | yes | a loaded raid id |

Permission level 2. Must be run by a player: the caller becomes the owner the portal spawn
is attributed to.

Opens `max(1, reward.minPortals)` CobbleSafari portals around `reward.portalAnchor` and
fires three fireworks at each. It does **not** grant `reward.fromage`, does not run
`reward.commands`, and does not touch any raid state. It is a position and dependency
check, not a reward dry run — the name oversells it.

```mcfunction
/cheeseraids testreward hoopa
```

```text
Test récompense: 1/1 portail(s) Hoopa à 100, 71, -2030 (minecraft:overworld)
```

| Message | Cause |
|---|---|
| `Monde d'ancrage introuvable: <id>` | `reward.portalAnchor.world` is not a loaded world |
| `Test récompense: 0/N …` | CobbleSafari is absent, or its portal methods could not be resolved, or it refused the placement |
| `Réservé aux joueurs.` | run from the console or a command block |
| nothing at all, result 0 | `raid` is not a known id |

The last row is a gap: an unknown raid id produces no feedback whatsoever. Check the id
with `status` first.

---

## Driving the mod from somewhere else

Four of these subcommands have a dashboard equivalent, and the scheduler is a second way
to start a raid:

- **Dashboard** — the `raids` module exposes start, stop and reload, and its overview
  route is a read-only `status`. See [dashboard API](dashboard.md).
- **Scheduler** — `schedule.autoTrigger` starts a raid at a time of day without anyone
  typing anything. See [configuration](configuration.md#schedule).

`enter`, `herald` and `testreward` have no remote equivalent.

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
