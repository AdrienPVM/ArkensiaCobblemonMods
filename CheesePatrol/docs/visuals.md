[← CheesePatrol](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](visuals.fr.md)

# Visuals

Three unrelated display systems live in `PatrolMarker`, and it is worth knowing which is
which because they have different audiences and different costs.

| System | Who sees it | Lifetime | Driven by |
|---|---|---|---|
| **Route preview** | one admin | until toggled off | `/npcpatrol show` |
| **Waypoint markers** | everyone | until removed by hand or at shutdown | `/npcpatrol markers` |
| **Trail effect** | everyone in range | permanent, part of the patrol | `/npcpatrol particles` |

---

## The route preview

`/npcpatrol show` is the design tool. It draws the whole circuit for **you alone**,
redrawn every 10 ticks, and it is the fastest way to see that a segment passes through a
wall.

**What is drawn:**

- At each waypoint, 8 dust particles at `x + 0.5`, `y + 1.5`, `z + 0.5` — one and a half
  blocks up, so the cloud sits at head height rather than in the floor. Spread 0.15 on
  each axis, so it reads as a small puff and not a point.
- Along each segment, a line of dust at `y + 1.0`, one particle every 0.5 blocks, in
  amber (`1.0, 0.67, 0.0`) at 0.8 scale. The segments are drawn at a lower height than the
  waypoint clouds, which makes the junctions readable.

**The colours encode position in the list:**

| Colour | Waypoint |
|---|---|
| Green | the first, `#1` |
| Red | the last |
| Blue | everything in between |

On a two-waypoint patrol you get one green and one red, no blue. On a one-waypoint patrol
— which cannot be started, but can be previewed — the single waypoint is green, because
the first-waypoint test is checked before the last-waypoint test.

**The closing segment is drawn.** The line loops from the last waypoint back to the first,
because the circuit does. That segment is the one people forget they created, and seeing
it drawn across the middle of a town square is usually the moment a route gets redesigned.

**Scoping and lifetime:**

- One NPC at a time per player. Calling `show` on a second patrol replaces the first,
  because the mapping is player UUID → NPC UUID.
- The display is suppressed entirely while you are in a different dimension than the
  patrol — not an error, just nothing drawn. Come back to the right world.
- The entry is dropped automatically if you disconnect, or if the patrol is cleared or
  loses all its waypoints.
- Particles are sent with `force = true`, so they render at long range and on a client set
  to minimal particles.

⚠️ **Cost scales with route length, not with waypoint count.** A circuit with 12 waypoints
and 300 blocks of total length is roughly 100 waypoint particles plus 600 line particles,
re-sent to you every 10 ticks. That is fine for one admin designing a route and is not
something to leave on. Turn it off when you are done.

## Waypoint markers

`/npcpatrol markers` plants a real entity at every waypoint: an armor stand, invisible,
weightless, invulnerable, with a visible custom name `WP #1`, `WP #2` and so on, at
`x + 0.5`, `y`, `z + 0.5`.

Each one carries three command tags, which is what makes them manageable:

| Tag | Value |
|---|---|
| `cheesepatrol` | on every marker the mod creates |
| `npc:<uuid>` | the full NPC UUID this marker belongs to |
| `wp:<index>` | the **zero-based** waypoint index — `wp:0` is the stand labelled `WP #1` |

So you can target one patrol's markers precisely:

```mcfunction
/kill @e[type=armor_stand,tag=cheesepatrol,tag=npc:a1b2c3d4-e5f6-7890-abcd-ef1234567890]
```

Markers beat the route preview for editing a long circuit, because they persist while you
walk around and they are visible to a second admin standing with you. They are worse for
checking segments, because they show the points and not the lines between them. Use both.

### What to watch out for

⚠️ **The name tags are visible to players.** There is no permission filter — an invisible
armor stand with `WP #3` floating over a market square is visible to everybody who logs
in. Markers are an editing tool, not scenery.

⚠️ **There is no way to turn them off with the command.** `/npcpatrol markers` deletes and
re-places; it never just deletes. They go away when:

- `/npcpatrol clear` runs — but only in the world you are standing in
- the server shuts down cleanly, which removes every `cheesepatrol`-tagged armor stand in
  a loaded chunk, in every world
- you kill them by tag yourself

A crash therefore leaves them behind, and clearing a patrol from the wrong dimension
leaves that patrol's markers orphaned with no NPC to associate them with.

**They are not obstacles.** An armor stand is not pushable in vanilla and has no movement
collision, so an NPC — like a player — walks straight through a marker standing on its
waypoint. Markers cannot nudge a patrol off its line.

**Placing markers scans every entity in the world.** `refreshMarkers` first calls
`removeMarkers`, which walks `world.iterateEntities()` looking for tagged stands. Running
it on a busy world is a full entity iteration. Not a problem for an admin command run
occasionally; worth knowing before you script it.

## Trail effects

The per-NPC trail is the only one of the three that is part of the patrol's saved state,
and the only one players are meant to see. Set it with `/npcpatrol particles <type>`.

### When and where it spawns

Trail particles are emitted from `tickPatrol`, which means **the patrol has to be active
and its NPC loaded**. A stopped patrol has no trail, however it is configured. They are
emitted every 2 server ticks, 10 times a second.

The anchor point is the NPC's mid-body, `y + height * 0.5`, offset by the two
configurables:

```text
x = npc.x - forward.x * particleZOffset
y = npc.y + height/2 + particleYOffset - forward.y * particleZOffset
z = npc.z - forward.z * particleZOffset
```

`forward` is the normalised velocity vector while the NPC is moving (speed > 0.01) and the
body yaw while it is stopped. That second case matters: when an NPC halts to look at a
player, its forward axis becomes its facing, so a trail configured to sit behind it swings
round as it turns.

Because `particleZOffset` is **subtracted** along forward, a positive value puts the trail
*behind* the NPC and a negative value puts it in front. The command's output says so, and
the field is named `Z` only because it started life as a depth offset.

While moving, three puffs are emitted — at the anchor and at 0.5 and 1.0 blocks further
back along the reverse of forward — which is what makes it read as a trail. While stopped,
one.

### Density and spread, per effect

Four effects are special-cased; everything else uses the defaults.

| Effect | Particles per puff | Spread (X/Z) | Spread (Y) | Particle speed |
|---|---|---|---|---|
| `enchant` | 3 | 0.2 | 0.26 | 0.5 |
| `cherry` | 3 | 0.3 | 0.39 | 0.01 |
| `smoke` | 1 | 0.1 | 0.13 | 0.005 |
| `end_rod` | 1 | 0.15 | 0.195 | 0.01 |
| everything else | 2 | 0.15 | 0.195 | 0.01 |

Y spread is always 1.3× the horizontal spread, which stretches the puff vertically and
hides the discrete positions. `enchant` gets a high particle speed because enchantment
glyphs are supposed to converge; the others are near-static so they hang where they are
spawned.

Note the overlap: **`sparkle` and `end_rod` render the same `END_ROD` particle**, and
differ only in density — `sparkle` emits 2 per puff, `end_rod` emits 1. Pick `sparkle` for
a visible trail and `end_rod` for a faint one.

### The 46 effect names

```text
none  sparkle  flame  soul  enchant  end_rod  cherry  smoke
dust  dust_cyan  dust_purple  dust_white  dust_red  dust_green  dust_pink  dust_orange
heart  note  portal  snowflake  witch  glow  electric  scrape
wax  spore  ash  crimson_spore  warped_spore  mycelium
falling_nectar  falling_spore  dripping_honey  falling_honey
dripping_obsidian_tear  falling_obsidian_tear
cloud  crit  damage  dragon  firework
lava  rain  smoke_large  totem  trial
```

Tab completion prefix-matches this list, so `dust` + Tab gives you the eight dust colours.

The eight `dust_*` entries are the mod's own coloured dust, defined as RGB in the source:

| Name | RGB | Scale | Reads as |
|---|---|---|---|
| `dust` | `1.00, 0.84, 0.00` | 1.2 | gold |
| `dust_cyan` | `0.13, 0.83, 0.93` | 1.2 | cyan |
| `dust_purple` | `0.60, 0.20, 1.00` | 1.2 | violet |
| `dust_white` | `1.00, 1.00, 1.00` | 1.0 | white, slightly smaller |
| `dust_red` | `1.00, 0.15, 0.15` | 1.2 | red |
| `dust_green` | `0.20, 1.00, 0.30` | 1.2 | green |
| `dust_pink` | `1.00, 0.50, 0.80` | 1.2 | pink |
| `dust_orange` | `1.00, 0.55, 0.00` | 1.2 | orange |

The dusts are the ones worth using for a trail: they are a single consistent colour, they
do not animate, and they do not carry any vanilla meaning. The rest map straight onto
vanilla particle types:

| Name | Vanilla particle | Name | Vanilla particle |
|---|---|---|---|
| `sparkle` | `END_ROD` | `wax` | `WAX_ON` |
| `flame` | `FLAME` | `spore` | `SPORE_BLOSSOM_AIR` |
| `soul` | `SOUL_FIRE_FLAME` | `ash` | `WHITE_ASH` |
| `enchant` | `ENCHANT` | `crimson_spore` | `CRIMSON_SPORE` |
| `end_rod` | `END_ROD` | `warped_spore` | `WARPED_SPORE` |
| `cherry` | `CHERRY_LEAVES` | `mycelium` | `MYCELIUM` |
| `smoke` | `CAMPFIRE_COSY_SMOKE` | `falling_nectar` | `FALLING_NECTAR` |
| `heart` | `HEART` | `falling_spore` | `FALLING_SPORE_BLOSSOM` |
| `note` | `NOTE` | `dripping_honey` | `DRIPPING_HONEY` |
| `portal` | `PORTAL` | `falling_honey` | `FALLING_HONEY` |
| `snowflake` | `SNOWFLAKE` | `dripping_obsidian_tear` | `DRIPPING_OBSIDIAN_TEAR` |
| `witch` | `WITCH` | `falling_obsidian_tear` | `FALLING_OBSIDIAN_TEAR` |
| `glow` | `GLOW` | `cloud` | `CLOUD` |
| `electric` | `ELECTRIC_SPARK` | `crit` | `CRIT` |
| `scrape` | `SCRAPE` | `damage` | `DAMAGE_INDICATOR` |
| `dragon` | `DRAGON_BREATH` | `firework` | `FIREWORK` |
| `lava` | `LAVA` | `rain` | `RAIN` |
| `smoke_large` | `LARGE_SMOKE` | `totem` | `TOTEM_OF_UNDYING` |
| `trial` | `TRIAL_SPAWNER_DETECTION` | | |

Three of these are worth avoiding on a public server, because players read them as
information rather than decoration: `damage` looks like the NPC is being hit, `crit` looks
like a critical hit landing, and `totem` is the full-screen-adjacent totem burst. `heart`
reads as breeding or taming.

### Visibility range

`/npcpatrol particlerange <16–512>` bounds who gets the packets. Each particle position is
sent per-player, to every player inside that spherical distance of the anchor, with
`force = true`.

The cost is real and it multiplies: *(players in range) × (3 puffs) × (particles per puff)
× (10 emissions per second)*. At the default 16 blocks that is a handful of packets to a
couple of people. At 512 blocks on a 40-player server, every player on the map receives the
trail of every patrolling NPC, ten times a second.

Set it to 16 for a town guard and raise it only for something deliberately visible from a
distance, like a flying NPC circling a landmark.

The range applies **only to the trail**. The `show` route preview goes to the one player
who asked for it at any distance up to 512 blocks within the dimension, and the markers are ordinary
entities subject to normal entity view distance.

---

← [Patrol circuits](patrol-circuits.md) · [Keeping NPCs alive](resilience.md) →
