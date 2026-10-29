[← CheeseRaids](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](hoopa-invasion.fr.md)

# The Hoopa Invasion

The first and so far only raid the mod ships. This page describes the sequence **as the
code runs it**, step by step, with the gaps marked.

> ⚠️ It has never been played. Every timing, message and effect below was read out of
> the source, not observed in-game.

---

## The sequence, as coded

### 1. Announcement (`ANNOUNCE`)

`/cheeseraids start hoopa`, or the scheduler, creates the instance and immediately:

- broadcasts `announce.chat` in chat, prefixed light purple;
- sends `title @a title` with `announce.title` in `light_purple`, and `title @a subtitle`
  with `announce.subtitle` in grey;
- writes the Discord line to the **server log** — there is no webhook (see
  [configuration](configuration.md#announce));
- hides the herald, so a player standing next to the NPC does not see it before the
  reveal;
- forces `weather thunder 1200` and `time set midnight` in the wave's world, if
  `wave.overworldStorm` is on. The thunder is re-applied every 600 ticks for the whole
  raid, because the vanilla command only lasts about 60 seconds.

Then it waits `wave.graceSecondsBeforeWave` seconds, five by default.

### 2. The wave (`OVERWORLD_WAVE`)

Every entry in `wave.spawns` is spawned `count` times, at `wave.arena.y + 1`, scattered
randomly within ±`wave.spread` on X and Z. Each one is created as an **uncatchable**,
vanilla-persistent Cobblemon entity. Persistence matters: without it Cobblemon's despawner
would age the wave out mid-fight.

The stock wave is one Hoopa Unbound at level 80 and four Sableye at level 60.

The first entry flagged `leader: true` becomes the **alpha**. It spawns at scale 1.8, gets
a permanent glow outline, and is tracked by its own UUID. Every five ticks the wave draws
marker particles: a rotating portal ring, soul flames and a crown of end rods around the
alpha, witch particles and end rods around the rest, so players can find them in the
thunderstorm.

The phase ends on whichever comes first:

- **the alpha dies.** This is the intended ending. A server-wide title fires — *« L'Alpha
  Hoopa s'enfuit ! »* / *« Il s'enfouit dans sa dimension... la faille s'ouvre ! »* — and
  the rift-opening effect plays at the arena. Any minions still alive are discarded;
- **everything is dead**, checked every `wave.checkIntervalTicks` ticks;
- **no mob spawned at all.** The raid logs a warning and proceeds rather than hanging;
- **`wave.timeoutSeconds` expires**, 180 by default. The wave is discarded and the raid
  proceeds.

So killing the alpha is a shortcut, not a requirement, and a wave nobody fights still
advances after three minutes. Even a `wave.arena.world` that does not exist does not stall
it: the spawn is skipped with an error naming the worlds that do exist, nothing is
tracked, and the phase moves on at the next tick through the zero-mob branch — no wave at
all, straight to the herald.

### 3. The herald (`NPC_REVEAL` → `OPEN`)

The rift dimension is created if it does not exist. Then the mod counts EasyNPC entities
within 10 blocks of `npc.pos` without revealing them.

**If at least one is found**, a ~112-tick crescendo is queued and the phase moves on
immediately. The crescendo runs during `OPEN`:

| Tick | What happens |
|---|---|
| 0 | the rift-opening portal effect at the wave arena, plus a low goat horn |
| 0–59 | three seconds of build-up: cave ambience every 12 ticks, portal particles gathering |
| 60–97 | totem and end-rod particles spiralling inward around the still-invisible NPC, beacon pitch rising |
| 80, 90 | two cosmetic lightning strikes with thunder |
| 100 | **the climax.** The EasyNPC is flipped visible, a third bolt lands, a flash and 120 totem particles burst, three sounds fire at once, and chat gets *« Le héraut Ho-Oh surgit dans un éclair ! Parlez-lui pour entrer dans la faille. »* |
| 100–112 | flames linger at the NPC |

The three-second lead-in is deliberate: it stops the herald arriving on top of the
rift-opening effect.

**If none is found**, the raid does not dead-end. It logs a warning, plays the
rift-opening effect anyway, and broadcasts the command fallback:

```text
La faille de Hoopa s'ouvre ! Tapez /cheeseraids enter hoopa pour entrer.
```

That graceful degradation is the right call, but note what it implies: a misconfigured
herald does not look broken from a player's seat. Check the console, or run
`/cheeseraids herald hoopa info` before the raid.

**What the herald does not do.** Chat says *parlez-lui* — talk to him. CheeseRaids
contains no dialogue handling, no interaction listener and no EasyNPC action binding. The
mod makes the NPC visible and glow; wiring his dialogue to run `/cheeseraids enter hoopa`
is something you configure **in EasyNPC**, by hand, on that NPC. Nothing in this
repository does it for you, and nothing checks that you did. This is the largest gap
between what the raid promises players and what the code delivers.

### 4. The rift (`OPEN`)

Players enter — through the herald's dialogue if you configured it, otherwise with
`/cheeseraids enter hoopa`. Each one claims a slot and gets their own parkour branch
generated from a seed derived from their UUID. [The raid model](raid-model.md#the-star-arena)
covers the layout; what a player experiences is:

1. they arrive on a 3×3 purpur platform under a floating gold-and-magenta Hoopa ring,
   facing inward, 75 blocks from the centre;
2. for three seconds, a portal column tightens around them with a rising beacon pitch;
3. then the path materialises block by block ahead of them, eight blocks per tick, with
   particles at each one, a beam racing to the leading edge, and a musical pitch climb
   from 0.9 to 1.9 over the length of the path;
4. when the last block lands, a flourish at the far end connects the path to the boss
   disk.

They jump inward through five themed zones — amethyst caverns, an ice bridge, moss and
cobweb caves, a drop gorge, crystal tightropes. Falling below `center.y - arena.fallBelow`
puts them back on their last checkpoint. Checkpoints are every `arena.checkpointEvery`
platforms, six by default, and announce themselves on the action bar: *« ✔ Checkpoint
atteint ! »*

Reaching the disk gives *« Vous avez atteint le cœur de la faille ! »* and marks the
player `arrived`.

The phase ends when at least one player has entered and any of these is true: everyone who
entered has arrived; `boss.graceSeconds` have passed since the **first** arrival; or
`arena.openTimeoutSeconds` have passed since the phase began. If the open timeout passes
and nobody entered at all, the raid aborts to cleanup.

### 5. The boss (`COMBAT`)

Chat gets *« Quelque chose d'immense s'éveille au cœur de la faille... »* and a 44-tick
apparition starts at the disk centre:

| Tick | What happens |
|---|---|
| 0 | end-portal and wither ambience |
| 0–33 | an expanding ring of reverse-portal particles, soul fire rising, a dragon growl climbing in pitch |
| 18, 28 | cosmetic lightning with thunder |
| 36 | **the boss spawns.** A second burst, a bolt, a wither roar, chat: *« ⚡ Le gardien de la faille SURGIT ! ⚡ »*, and a title sent only to players actually inside the rift |

Before spawning, the provider carves an air pocket above the disk sized from
`boss.baseScale` — half-width `ceil(2 + scale × 2)`, height `ceil(6 × scale) + 3` — and
force-loads the centre chunk. Both exist because of real failures: a 1.5× Hoopa Unbound is
around ten blocks tall and suffocated instantly in a fixed 5×5×6 pocket, and the rift's
centre chunk unloads while every player is 75 blocks out on the parkour, which made
`spawnEntity` refuse outright.

The boss is one uncatchable, persistent Pokémon at
`boss.baseLevel + (participants - 1) × boss.levelPerExtraPlayer`, scaled by
`boss.baseScale` applied *before* the spawn packet so every client sees the size
immediately. A swirling soul-fire aura is drawn around it every three ticks.

**It is fought as a normal Cobblemon battle.** There is no raid mechanic, no shared health
bar, no turn coordination between players. "Co-operative" means several players are
standing on the same disk; the first to engage gets the battle. This is the second large
gap between the design sketch and the code.

Victory detection is deliberately careful, because the obvious check is wrong. Winning a
Cobblemon battle removes the wild Pokémon about two seconds **after** the battle ends, and
teleporting players during that window was visibly broken. So the contract is: the entity
is gone, **and** either a battle id was captured and that battle is no longer registered,
or no battle was ever captured but the boss survived more than five seconds. A boss that
vanishes with no battle within 200 ticks of its spawn is treated as a spawn failure, not a
win, and is respawned — up to twice. Once those two respawns are spent, the same vanish
counts as a kill as soon as five seconds have passed since the last spawn.

The phase ends on victory, or on `boss.combatTimeoutSeconds` (900 by default), which
ejects everyone and cleans up **without rewards**.

### 6. Rewards (`REWARD`)

Players still in the rift get glowing for ten seconds, regeneration II for five, slow
falling for ten, a gold *« 🏆 VICTOIRE ! »* title and *« La faille s'effondre — l'invasion
est repoussée ! »*, with totem particles and two sounds. Sparkles continue every four
ticks.

4.5 seconds later:

1. everyone is teleported out, to their own `returnPos` if set, otherwise `exitPos`;
2. `floor(participants / reward.portalDivisor)` portals, at least `reward.minPortals`,
   open on a circle of radius `reward.portalSpacing` around `reward.portalAnchor`, with
   two fireworks at each. These are CobbleSafari "Hoopa Ring" dungeon portals, reached by
   reflection — see [integrations](integrations.md#cobblesafari);
3. three fireworks and a launch sound over each winner, wherever they now are;
4. `reward.fromage` is added to each winner's CheeseStore wallet, if the amount is
   non-zero and CheeseStore is installed;
5. every entry in `reward.commands` runs once per winner with `{player}` substituted.

"Winner" here means every player in the instance who is online at that moment. There is no
check that they ever arrived, let alone fought. A player who entered the rift, fell off the
parkour fifty times and never reached the disk is rewarded identically to the one who beat
the boss.

### 7. Cleanup (`CLEANUP`)

Clear weather over the wave arena; every player's branch recomputed and air-filled; the
boss arena volume cleared and its forced chunks released; wave mobs and the boss discarded;
the herald hidden; effect and reveal queues emptied; the instance dropped from memory and
its JSON file deleted. The rift dimension itself is **not** deleted — it stays, empty and
disabled, and the next raid rebuilds the arena inside it.

The time of day is not restored. `overworldStorm` set it to midnight; cleanup only clears
the weather.

---

## What is incomplete

Collected in one place, because the sequence above reads smoother than the mod plays.

| Gap | Effect |
|---|---|
| The herald has no dialogue | the NPC appears and glows, but nothing makes him an entry point. You must bind his dialogue to `/cheeseraids enter <id>` in EasyNPC yourself |
| The boss fight has no raid mechanic | a plain Cobblemon battle. Several players on one disk, one battle |
| Rewards are not gated on participation | everyone online when the boss dies is rewarded, arrived or not |
| `stop` does not eject players | manual stop leaves players in a dimension whose floor is being deleted |
| Discord announcements are a log line | `announce.discord` sends nothing anywhere |
| Sixteen config keys do nothing | listed in [configuration](configuration.md) |
| Nothing is translated | every player-facing string is a French literal in Java |
| Four reflection hooks | EasyNPC, Cobblemon spawning, Cobblemon battles and CobbleSafari are all reached by reflection. Any of them can break on a dependency update, and three of the four degrade silently |

None of this makes the raid unrunnable on paper. All of it is why the mod is marked
experimental.

---

← [The raid model](raid-model.md) · [External integrations](integrations.md) →
