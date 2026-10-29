[← CheeseRaids](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](raid-model.fr.md)

# The raid model

CheeseRaids calls itself a framework, which raises a fair question: how much of it is
actually generic, and how much is the Hoopa Invasion wearing a framework's clothes? This
page answers that honestly, because it determines whether writing a second raid is a
config exercise or a code change.

> ⚠️ No raid has ever been run with players. The model below is what the code
> implements, not a validated design.

---

## What a raid is

A raid is two objects.

A **definition** (`RaidDefinition`) is static configuration: one JSON file in
`config/cheeseraids/raids/`, one object in memory, keyed by its `id`. It describes the
announcement, the wave, the herald, the arena, the boss, the rewards and the schedule.
It is pure data with Java defaults; Gson deserialises it straight into public fields.

An **instance** (`RaidInstance`) is the runtime state of one run: the current phase, when
it started, which players are in it, which slots they hold, the wave's entity UUIDs, the
boss's entity and battle ids, the deadlines. At most one instance exists per raid id. It
is persisted to `config/cheeseraids/instances/<raidId>.json` on every phase change and
every 100 ticks, and reloaded at server start, so a crash mid-raid resumes rather than
orphaning a dimension full of players.

`RaidManager` owns both maps and a single `tick()` that the server calls at the end of
every tick. There is no per-raid thread and no scheduler executor: everything happens on
the server thread.

## Phases

```text
IDLE → ANNOUNCE → OVERWORLD_WAVE → NPC_REVEAL → OPEN → COMBAT → REWARD → CLEANUP → IDLE
```

The progression is strictly linear. There is no `PAUSED` state and no way back.

| Phase | Entered when | Left when |
|---|---|---|
| `ANNOUNCE` | `start` is called, or the scheduler fires | `wave.graceSecondsBeforeWave` has elapsed |
| `OVERWORLD_WAVE` | the wave spawns | the alpha dies, the whole wave is cleared, zero mobs spawned, or `wave.timeoutSeconds` expires |
| `NPC_REVEAL` | immediately after the wave | the same tick — this phase does not wait |
| `OPEN` | the rift is open | someone has entered **and** (everyone has arrived, or `boss.graceSeconds` has passed since the first arrival, or `arena.openTimeoutSeconds` expired) |
| `COMBAT` | the boss apparition starts | the boss is defeated, or `boss.combatTimeoutSeconds` expires |
| `REWARD` | the boss is down | 4.5 seconds later |
| `CLEANUP` | always | the same tick |

Two of those transitions are worth reading twice.

`NPC_REVEAL` transitions to `OPEN` in the same tick it is entered. It exists to run one
piece of logic — count the heralds, and either start the reveal animation or announce the
command fallback — and then gets out of the way. The herald's five-second crescendo plays
out during `OPEN`, driven by a separate effects queue, not by the phase machine.

`OPEN` can time out two different ways. If `openDeadline` passes and **nobody** entered,
the raid aborts straight to `CLEANUP` with no boss and no reward. If somebody entered, the
same deadline forces the boss to spawn whether or not anyone reached the centre.

`COMBAT` is entered *before* the boss exists. The transition queues a 44-tick apparition
sequence and the boss is actually spawned at tick 36 of it. `inst.bossSpawned` stays false
until then, and the victory check returns false while it is false, so the build-up cannot
be mistaken for an instant win.

### Who drives what

Three things tick in parallel during a raid, and keeping them separate is what stops a
cosmetic bug from stalling the state machine:

- **the phase machine** — `advance()`, one `switch` over the phase, every tick;
- **the effects queue** — a list of `FxTask`, each a multi-tick cinematic with its own
  age counter. An exception in one is caught, logged and the task dropped. This is where
  the apparitions live, and it is why the boss spawn is inside a cosmetic sequence;
- **the reveal queue** — `StarArenaGenerator.tick()`, placing a branch's blocks a few per
  tick with particles and a rising pitch.

## What is generic and what is Hoopa

The division is not where the class names suggest.

**Genuinely generic**, driven entirely by the definition file:

- the phase machine and its timeouts;
- the scheduler, the anti-replay date stamp, instance persistence and crash recovery;
- the wave: species, aspects, levels, counts, which entry is the alpha, spread, the
  storm;
- the star arena's dimensions: radius, slot count, checkpoint cadence, block palette,
  reveal speed, fall threshold;
- the boss's species, aspects, level scaling, size and timeouts;
- the reward counts, the per-winner command list and the currency amount;
- all five positions.

**Hardcoded, and Hoopa-flavoured whatever you put in the file:**

- **every player-facing string.** The phase announcements, titles and subtitles are
  French literals in `RaidManager`: *« L'Alpha Hoopa s'enfuit ! »*, *« Le héraut Ho-Oh
  surgit dans un éclair ! »*, *« La faille de Hoopa s'ouvre ! »*, *« Le gardien de la
  faille SURGIT ! »*, *« VICTOIRE ! »*. The definition's `announce` block controls the
  opening announcement only. A second raid would announce itself as a Hoopa rift;
- **the visual identity.** Portal and reverse-portal particles, end-rod crowns, the
  enderman-teleport and end-portal sounds, the gold-and-magenta firework palette, the
  floating Hoopa ring built above every spawn platform;
- **the parkour design.** Thirteen jump styles, five mechanics and five named zones
  (`amethyst_caverns`, `frostbridge`, `mosscaverns`, `plunge_gorge`, `crystal_spires`),
  each with its own block palette and style weighting, are static tables in
  `StarArenaGenerator`;
- **the boss strategy.** `BossProvider` is an interface with one implementation, and
  `forDefinition()` ignores the `boss.provider` field entirely;
- **the arena shape.** `arena.model` offers `SHARED` and `PER_PLAYER`; nothing reads it.
  Every raid is one shared rift with one branch per player.

So: a second raid with different Pokémon, levels, positions, parkour length and rewards is
a config file. A second raid that does not look and sound like a Hoopa invasion is a code
change. The framework abstraction is real at the lifecycle level and cosmetic at the
presentation level.

## The star arena

The rift is a CheeseGuard void dimension, created on demand the first time a raid reaches
`NPC_REVEAL` (or a player enters, whichever comes first), with `enabled = false` so nobody can wander in outside a raid. At its centre sits
a flat disk of radius `arena.centerRadius` — the boss arena, built once per raid.

Each player gets a **branch**: a parkour path running from a spawn platform at
`arena.starRadius` blocks out, inward to the disk. Branches are assigned angular slots,
`2π / arena.maxSlots` apart, so twelve players make a twelve-pointed star.

The geometry is a **pure function of (definition, slot, seed)**. The seed comes from the
player's UUID mixed with the raid id and the slot. Nothing about the layout is stored:
cleanup, checkpoint tracking and respawn all recompute it. That is what makes a relog
safe — recomputing gives the identical path, so the player is put back on the same blocks
they left.

Platforms come in two kinds. The spawn platform, the checkpoints and the final platform
are 3×3 plates with a skirt below and a beacon marker. Everything between them is a
single 1×1 landing cap on a themed base column below the landing plane, plus whatever its
mechanic needs: a blue-ice runway on the take-off platform for an ice jump, a column of
cobwebs above a cobweb landing, scaffolding for the final climb. Those mechanic blocks are
the only ones placed in a jump's path.

Jumps are chosen from a weighted style table, biased by which of the five zones the step
falls in, and then clamped: each mechanic declares a reach ceiling for the vertical
change it achieved, and a candidate that exceeds it is pulled back. Upward steps are
limited to +1 block, except for one scaffolding climb inserted at the very end when the
path ends more than two blocks below the disk. The code's comments say the result was verified by an external
simulation (`temp/sim_parkour_v3.py`, not in this repository) reporting zero impossible
jumps. **No player has jumped it.**

Progress is supervised every tick by `CheckpointTracker`, for each player who has entered
but not yet arrived:

- below `center.y - arena.fallBelow` → teleported to the last checkpoint, velocity and
  fall distance zeroed;
- within 2.2 blocks horizontally and 2 vertically of a checkpoint → that checkpoint is
  recorded, and an action-bar message fires once per new one;
- within `centerRadius - 0.5` horizontally and 1.5 vertically of `center.y + 1` →
  `arrived`. The check is deliberately strict: standing on the last platform is not
  arriving, you have to be on the disk.

## Writing a second raid

Copy `hoopa.json`, change the `id`, change the file name to match, and edit. There is no
registration step: `loadDefinitions()` reads every `*.json` in the directory. `start` and
the scheduler then treat it exactly like the first one.

What you can change freely: species and levels, positions, the schedule, parkour radius
and slot count, the block palette, the boss's size and scaling, the rewards.

What you will hit: the strings and the particle identity, listed above. If you need a raid
that is not a dimensional rift torn open by Hoopa, `RaidManager`'s literals are where you
start.

One extension point exists and is unused. `BossProvider` is a clean five-method interface
— spawn, is-capturing, capture-tick, is-defeated, cleanup, plus two defaulted tick hooks.
Writing a second provider is straightforward; wiring it in means editing
`BossProvider.forDefinition()` to actually read `def.boss.provider`, which it currently
does not.

---

← [Calibration](calibration.md) · [The Hoopa Invasion](hoopa-invasion.md) →
