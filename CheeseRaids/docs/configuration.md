[← CheeseRaids](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

CheeseRaids has no global config file. All configuration lives in **one file per raid**:

```text
config/cheeseraids/raids/<id>.json
```

On first server start the mod creates the directory and, if it is empty, writes
`hoopa.json` from the Java defaults in `RaidDefinition.defaultHoopa()`. Two sibling paths
belong to the mod's runtime state rather than to configuration, and you should not edit
them by hand:

```text
config/cheeseraids/instances/<raidId>.json   state of a raid currently running
config/cheeseraids/state.json               raidId → last scheduled run date
```

A definition is reloaded by `/cheeseraids reload` or `POST /api/v1/raids/reload`. The
reload re-reads every `*.json` in `raids/` and replaces the in-memory map. A raid that is
**already running** keeps the definition it started with only insofar as the manager reads
`definitions.get(id)` every tick — in practice a reload mid-raid changes behaviour under
the running instance. Reload between raids.

> ⚠️ Several keys in the file are **not read by any code**. They are listed below as
> *unused*, with what they look like they were meant to do. They are not a configuration
> surface; setting them changes nothing.

---

## Top level

| Key | Type | Default | What it does |
|---|---|---|---|
| `id` | string | `"hoopa"` | the raid's identifier. The file name is ignored — this field is the key the mod stores it under, and the argument every command takes |
| `displayName` | string | `"Invasion Hoopa"` | used to name the rift dimension (`<displayName> — Faille`) and shown in the dashboard |
| `icon` | string | `"👻"` | emoji shown in the dashboard listing. No in-game effect |
| `enabled` | boolean | `true` | `false` makes `start` refuse and the scheduler skip the raid |
| `exitPos` | position | overworld `0.5 / 65 / 0.5` | where players are teleported when the raid ends. **A placeholder — see [calibration](calibration.md)** |

A position object is `{ "world": "<namespace:id>", "x": 0.0, "y": 0.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0 }`.
`world` is a full world id; `yaw` and `pitch` only matter where the player is teleported.

The `id` field, not the file name, is authoritative. Naming a file `invasion.json` with
`"id": "hoopa"` gives you a raid called `hoopa`. The mod never writes a definition back
(only the first-run seed is saved), but two files carrying the same `id` are both read and
the one read last silently replaces the other, in directory order. Keep them aligned.

## `schedule`

| Key | Type | Default | What it does |
|---|---|---|---|
| `autoTrigger` | boolean | `false` | enables the time-of-day trigger |
| `hour` | int | `20` | hour of the server's local time, 0–23 |
| `minute` | int | `0` | minute, 0–59 |
| `everyNDays` | int | `1` | minimum days between automatic runs; `1` is daily |

The scheduler runs once per second and fires when the server's local hour **and** minute
both match. It is not a cron: there is no day-of-week, and a server that is down for that
whole minute misses the run entirely. Anti-replay is a date stamp per raid id in
`state.json`, compared with `ChronoUnit.DAYS.between`, so restarting the server during
the trigger minute does not fire the raid twice.

`everyNDays` is clamped to at least 1. A raid already active is skipped, as is a raid with
`enabled: false`.

## `announce`

| Key | Type | Default | What it does |
|---|---|---|---|
| `title` | string | `"Invasion Hoopa"` | big screen title shown to every player |
| `subtitle` | string | `"Une faille dimensionnelle s'ouvre..."` | subtitle under it; skipped if blank |
| `chat` | string | `"Une invasion Hoopa commence ! …"` | chat line, prefixed with `§d`; skipped if blank |
| `discord` | boolean | `true` | see below |
| `discordMessage` | string | `"👻 Une **Invasion Hoopa** vient de commencer !"` | the text that would be sent |

`discord: true` does **not** send anything to Discord. It writes one line to the server
log:

```text
[CheeseRaids][Discord] 👻 Une **Invasion Hoopa** vient de commencer !
```

There is no webhook code in this mod. Treat the pair as a placeholder for an integration
that was never wired up.

Title and subtitle are injected into a vanilla `title @a` command as JSON, with `"` and
`\` escaped. The colours are fixed in code (`light_purple` for the title, `gray` for the
subtitle); the config cannot change them.

## `wave`

The overworld phase: Pokémon spawn, players kill them.

| Key | Type | Default | What it does |
|---|---|---|---|
| `arena` | position | overworld `0.5 / 65 / 0.5` | spawn centre. **A placeholder** |
| `spread` | double | `6.0` | each mob is placed at a random offset within ±`spread` on X and Z |
| `spawns` | list | leader + minions, below | what to spawn |
| `graceSecondsBeforeWave` | int | `5` | delay between the announcement and the spawn |
| `overworldStorm` | boolean | `true` | forces `weather thunder 1200` and `time set midnight` in the wave's world, re-applied every 600 ticks, and `weather clear` at cleanup |
| `checkIntervalTicks` | int | `20` | how often the wave-cleared check runs |
| `timeoutSeconds` | int | `180` | force-advance to the next phase if the wave never clears. Clamped to at least 5 |
| `spawnCommand` | string | a `pokespawn` template | **unused** |
| `entityType` | string | `"cobblemon:pokemon"` | **unused** |
| `detectRadius` | double | `18.0` | **unused** |
| `captureWindowMs` | int | `3000` | **unused** |

The four unused keys are the remains of an earlier design where mobs were spawned by
running a console command and then found by diffing the entities near the arena. The
current code calls Cobblemon directly through reflection and keeps the entity handle, so
there is no command, no detection radius and no capture window. The field
`RaidInstance.waveCaptureUntil` is still set, to 0, and `isCapturing()` is therefore
always false.

`overworldStorm` is worth understanding before you enable it on a live map: it changes the
weather and the time of day for the **entire world** named in `wave.arena.world`, not for
an area, and cleanup restores `weather clear` but never restores the time.

### `wave.spawns[]`

| Key | Type | Default | What it does |
|---|---|---|---|
| `species` | string | `"hoopa"` | Cobblemon species name |
| `aspects` | string | `""` | comma-separated aspect names, emitted as `aspect=<name>` each |
| `level` | int | `70` | Pokémon level; omitted from the properties string if ≤ 0 |
| `count` | int | `1` | how many to spawn. Clamped to at least 1 |
| `leader` | boolean | `false` | marks the alpha |

Every wave mob is spawned with `uncatchable=true` and marked vanilla-persistent so
Cobblemon's despawner leaves it alone. The **first** entry with `leader: true` becomes the
alpha: it is spawned at scale 1.8, given a glow outline, and tracked separately. Killing
it ends the wave immediately, whatever is left alive. Every other mob spawned from a
`leader` entry is also spawned at scale 1.8, but gets no glow and is not tracked.

The stock `hoopa.json` ships two entries:

```json
[
  { "species": "hoopa",   "aspects": "unbound", "level": 80, "count": 1, "leader": true  },
  { "species": "sableye", "aspects": "",        "level": 60, "count": 4, "leader": false }
]
```

## `npc`

The Ho-Oh herald that players talk to in order to enter.

| Key | Type | Default | What it does |
|---|---|---|---|
| `easyNpcName` | string | `"hooh-raid"` | a label. Substituted into the command templates as `{npc}`; otherwise unused |
| `entityType` | string | `"easy_npc:humanoid_slim"` | substituted as `{type}`. **Not** used to find the NPC |
| `pos` | position | overworld `0.5 / 65 / 0.5` | where the herald stands. **A placeholder** |
| `showCommand` | string | `""` | optional console command run in addition to the Java reveal |
| `hideCommand` | string | `""` | same, for hiding |
| `despawnAfterSeconds` | int | `600` | **unused** |

The reveal itself is Java, not a command: the hook scans for entities whose registry id
starts with `easy_npc` within a 10-block box around `pos` and flips their `VISIBLE`
display attribute. The command templates exist for anything that needs doing on top —
`{world} {type} {x} {y} {z} {npc}` are substituted, and the result runs as a level-4
console source.

`despawnAfterSeconds` suggests the herald should hide itself if the raid never starts.
No code reads it. The herald is hidden at `ANNOUNCE`, again at the start of `NPC_REVEAL`
(the count taken before the crescendo also hides it), and at cleanup — nowhere else.

## `arena`

The rift dimension and the procedural star parkour.

| Key | Type | Default | What it does |
|---|---|---|---|
| `riftDimId` | string | `"hoopa_rift"` | the CheeseGuard dimension id; the world id is `cheeseguard:` + this |
| `center` | position | `cheeseguard:hoopa_rift` `0.5 / 100 / 0.5` | centre of the boss disk. Only `x`/`y`/`z` are read: `world` is ignored, the rift is always `cheeseguard:` + `riftDimId` |
| `starRadius` | double | `75.0` | distance from the centre to a branch's spawn platform. Longer means more jumps |
| `maxSlots` | int | `12` | simultaneous branches, and therefore the maximum number of players in one raid |
| `checkpointEvery` | int | `6` | a checkpoint every N platforms. Lower is easier. Clamped to at least 1 |
| `revealBlocksPerTick` | int | `8` | how fast the path animates in. Clamped to at least 1 |
| `revealDelayTicks` | int | `60` | ticks of build-up at the spawn platform before the path starts appearing |
| `revealParticle` | string | `"ENCHANT"` | particle used by the reveal; one of the names in `Mc.particle`, anything else falls back to `ENCHANT` |
| `revealSound` | string | `"minecraft:block.amethyst_block.chime"` | sound played when the placed-block count at the end of a tick is a multiple of 6 — every 24 blocks (3 ticks) at the default speed of 8 |
| `platformBlock` | string | `"minecraft:purpur_block"` | the plates. An unknown but well-formed id resolves to **air** (the block registry's default), so the plates vanish; only a malformed id falls back to purpur |
| `checkpointBlock` | string | `"minecraft:sea_lantern"` | checkpoint plates |
| `centerBlock` | string | `"minecraft:crying_obsidian"` | the boss disk's base block |
| `centerRadius` | int | `15` | radius of the boss disk. Clamped to at least 6 |
| `fallBelow` | double | `12.0` | blocks below `center.y` before a player is respawned at their last checkpoint |
| `openTimeoutSeconds` | int | `180` | how long the rift stays open. Clamped to at least 5 |
| `keepInventory` | boolean | `true` | passed to the dimension's CheeseGuard config at creation |
| `model` | string | `"SHARED"` | **unused** |
| `parkourLength` | int | `24` | **unused** — the platform count is derived from the geometry |
| `gapMin` / `gapMax` | double | `2.8` / `3.6` | **unused** — jump distances come from the hardcoded style table |
| `riseMax` | double | `1.0` | **unused** |

`keepInventory` only takes effect when the dimension is **created**. The generator
early-returns if the world already exists, so changing this key after the first raid does
nothing — change it in CheeseGuard instead.

`centerRadius` is load-bearing in two places you might not expect. It defines where
arrival at the centre is detected (standing within `centerRadius - 0.5` horizontally and
within 1.5 blocks vertically of `center.y + 1`), and it sets how large a volume cleanup
clears: a cube of radius `centerRadius + 17` and `9 + max(5, centerRadius/3) + 13` blocks
tall. A large radius makes cleanup expensive.

`fallBelow` is relative to the centre's Y, not to the player's platform. A branch dips
below the centre in the *plunge gorge* zone, so a value much smaller than the default
will teleport players who are still on the path.

The parkour geometry itself is not configurable. Thirteen jump styles across five
mechanics (flat, ice, tightrope, cobweb, honey) and five themed zones are hardcoded in
`StarArenaGenerator`; `starRadius`, `maxSlots` and `checkpointEvery` are the only levers
the config gives you. [The raid model](raid-model.md#the-star-arena) explains the layout.

## `boss`

| Key | Type | Default | What it does |
|---|---|---|---|
| `species` | string | `"hoopa"` | Cobblemon species |
| `aspects` | string | `"unbound"` | comma list, emitted as `aspect=<name>` each |
| `baseScale` | double | `1.5` | size multiplier applied before the spawn packet, and used to size the air pocket cleared above the disk |
| `baseLevel` | int | `80` | level for a single participant |
| `levelPerExtraPlayer` | int | `3` | added per participant beyond the first |
| `graceSeconds` | double | `30` | after the first player reaches the centre, how long the others have |
| `combatTimeoutSeconds` | int | `900` | gives up and cleans up without reward. Clamped to at least 10 |
| `provider` | string | `"CUSTOM_GIANT"` | **unused** — `BossProvider.forDefinition` always returns the giant provider |
| `spawnCommand` | string | a `pokespawn` template | **unused** |
| `scaleCommand` | string | `""` | **unused** |
| `entityType` | string | `"cobblemon:pokemon"` | **unused** |
| `detectRadius` | double | `8.0` | **unused** |
| `captureWindowMs` | int | `3000` | **unused** |

The final level is `baseLevel + (participants - 1) × levelPerExtraPlayer`, computed once
when the boss spawns. The participant count is the number of players who entered, not the
number still alive or still in the rift.

`provider` names a strategy interface that has exactly one implementation. The `RAID_DEN`
value in the field comment refers to a CobblemonRaidDens-backed provider that does not
exist; whatever you write here, you get `CUSTOM_GIANT`.

`baseScale` is applied through Cobblemon's `setScaleModifier`, found reflectively. If the
running Cobblemon build does not have it, the boss spawns at normal size and a warning is
logged — the fight still works.

## `reward`

| Key | Type | Default | What it does |
|---|---|---|---|
| `portalDivisor` | int | `3` | `floor(participants / divisor)` portals. Clamped to at least 1 |
| `minPortals` | int | `1` | floor on the above |
| `portalAnchor` | position | overworld `0.5 / 65 / 0.5` | centre of the portal circle. **A placeholder** |
| `portalSpacing` | double | `4.0` | radius of that circle |
| `portalCommand` | string | `""` | fallback command template, run once per portal if CobbleSafari opened none |
| `fromage` | long | `0` | currency added to each winner's CheeseStore wallet. `0` skips it |
| `commands` | list of strings | empty | console commands run once per winner, `{player}` substituted |

Portals come from CobbleSafari, through reflection on its
private `PortalSpawnManager`. The call needs an **online participant** as owner, so if
everyone disconnected before the reward, no portal opens and the command fallback is used
instead. With neither available you get a warning and no portal, but the raid still ends
cleanly.

`portalCommand` placeholders are `{dim} {x} {y} {z} {index}`. The fallback lays
portals out in a line (`x + index × spacing`), not on the circle the primary path uses.

`commands` is the general-purpose reward hook. One entry per command, `{player}` replaced
by the winner's name, each run as a level-4 console source:

```json
"commands": [
  "give {player} minecraft:diamond 3",
  "cheeseitems give {player} hoopa_token 1"
]
```

---

## A complete annotated example

JSON has no comments, so the annotations below are prose, not something to paste. This is
the stock `hoopa.json` with the four placeholder positions filled in.

```json
{
  "id": "hoopa",
  "displayName": "Invasion Hoopa",
  "icon": "👻",
  "enabled": true,

  "schedule": {
    "autoTrigger": true,
    "hour": 21,
    "minute": 30,
    "everyNDays": 2
  },

  "announce": {
    "title": "Invasion Hoopa",
    "subtitle": "Une faille dimensionnelle s'ouvre...",
    "chat": "Une invasion Hoopa commence ! Rejoignez l'arene pour repousser l'attaque.",
    "discord": true,
    "discordMessage": "👻 Une **Invasion Hoopa** vient de commencer !"
  },

  "wave": {
    "arena": { "world": "minecraft:overworld", "x": 112.0, "y": 71.0, "z": -2043.0, "yaw": 0.0, "pitch": 0.0 },
    "spread": 8.0,
    "spawns": [
      { "species": "hoopa",   "aspects": "unbound", "level": 80, "count": 1, "leader": true  },
      { "species": "sableye", "aspects": "",        "level": 60, "count": 6, "leader": false }
    ],
    "graceSecondsBeforeWave": 10,
    "overworldStorm": true,
    "checkIntervalTicks": 20,
    "timeoutSeconds": 240
  },

  "npc": {
    "easyNpcName": "hooh-raid",
    "entityType": "easy_npc:humanoid_slim",
    "pos": { "world": "minecraft:overworld", "x": 118.0, "y": 71.0, "z": -2050.0, "yaw": 180.0, "pitch": 0.0 },
    "showCommand": "",
    "hideCommand": ""
  },

  "arena": {
    "riftDimId": "hoopa_rift",
    "center": { "world": "cheeseguard:hoopa_rift", "x": 0.5, "y": 100.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0 },
    "starRadius": 75.0,
    "maxSlots": 12,
    "checkpointEvery": 5,
    "revealBlocksPerTick": 8,
    "revealDelayTicks": 60,
    "revealParticle": "ENCHANT",
    "revealSound": "minecraft:block.amethyst_block.chime",
    "platformBlock": "minecraft:purpur_block",
    "checkpointBlock": "minecraft:sea_lantern",
    "centerBlock": "minecraft:crying_obsidian",
    "centerRadius": 15,
    "fallBelow": 12.0,
    "openTimeoutSeconds": 240,
    "keepInventory": true
  },

  "boss": {
    "species": "hoopa",
    "aspects": "unbound",
    "baseScale": 1.5,
    "baseLevel": 80,
    "levelPerExtraPlayer": 3,
    "graceSeconds": 45.0,
    "combatTimeoutSeconds": 900
  },

  "reward": {
    "portalDivisor": 3,
    "minPortals": 1,
    "portalAnchor": { "world": "minecraft:overworld", "x": 100.0, "y": 71.0, "z": -2030.0, "yaw": 0.0, "pitch": 0.0 },
    "portalSpacing": 4.0,
    "portalCommand": "",
    "fromage": 500,
    "commands": [
      "give {player} minecraft:diamond 3"
    ]
  },

  "exitPos": { "world": "minecraft:overworld", "x": 120.5, "y": 71.0, "z": -2060.5, "yaw": 90.0, "pitch": 0.0 }
}
```

Choices worth explaining in that file. `schedule` is on, at 21:30 every other day — a
server that is restarting at 21:30 will skip the run rather than catch up. `spread` is 8
instead of 6 and the minion count 6 instead of 4, which needs a 17×17 clear arena.
`checkpointEvery` is 5 rather than 6, making the parkour slightly more forgiving.
`graceSeconds` is 45 rather than 30, because the parkour is long and stragglers are the
norm. `fromage` is non-zero, which requires CheeseStore — without it the grant is a
silent no-op.

The unused keys are omitted from the example on purpose. They are written by the mod on
first run, and leaving them in the file is harmless.

---

← [Commands](commands.md) · [Calibration](calibration.md) →
