[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dungeons.fr.md)

# The dungeon definition

A dungeon is one JSON file in `config/cobblemon_dungeons/dungeons/`. The file name does
not matter for identification — the `general.id` field inside it does — but the dashboard
writes a dungeon back to `<id>.json` (and `DELETE` only removes `<id>.json`), so keeping the
file named after its id avoids duplicate files.

Every field below is a field of `DungeonConfig` (or one of its nested classes) in
`src/main/java/com/sebastian/cobblemondungeons/config/DungeonConfig.java`. The default
given is the value a freshly constructed `DungeonConfig` carries, which is also what you
get for any key you leave out of the file: Gson fills absent keys with the field
initialiser, so a partial file is valid and the rest is defaulted.

---

## Top level

| Key | Type | Default | What it does |
|---|---|---|---|
| `general` | object | see below | Identity and GUI presentation |
| `networking` | object | see below | Which server runs the dungeon, and how players get there |
| `locations` | object | see below | The first location slot |
| `additionalLocations` | array of location objects | `[]` | Extra slots, for running the dungeon several times in parallel |
| `requirements` | object | see below | Entry gates, objectives, time limit, lives, cooldown |
| `scaling` | object | see below | Default tier and the tier → level table |
| `spawning` | object | see below | Alpha, Guardian, Stake, Portal and chest placement |
| `boss` | object | see below | Boss pool and boss spawn position |
| `rewardCap` | int | `0` | Maximum completion rewards a single player can receive. `0` means no cap |
| `lootPools` | array | `[]` | Chest contents, keyed by chest id and tier |
| `completionRewards` | array | `[]` | Items rolled for every player when the boss dies |

`rewardCap` counts only rewards actually handed over. A roll whose item id is unknown (and
matches no ticket name) produces an empty stack: it is logged as
`Unknown loot item ID or ticket name: <item>` and does not consume a slot, rather than
silently eating someone's cap.

## `general`

| Key | Type | Default | What it does |
|---|---|---|---|
| `name` | string | `"Generic Dungeon"` | Display name, used in chat messages, the HUD and the selection GUI |
| `id` | string | `"generic_dungeon"` | Registry key. Tickets, the dashboard and `DUNGEON_OPEN` all reference this |
| `guiIcon` | string | `"minecraft:paper"` | Item id drawn for this dungeon in the selection GUI |

`id` is the only identity that matters. Two files declaring the same `id` end up with
the last one loaded winning, because `DungeonManager.registerDungeon` puts into a map
keyed by id and the load order of `File.listFiles` is not specified.

## `networking`

| Key | Type | Default | What it does |
|---|---|---|---|
| `targetServer` | string | `"dungeonsraids"` | Proxy server name the player is sent to, in `CROSS_SERVER` mode |
| `mainServer` | string | `"main"` | Proxy server name the player is sent back to on leave |
| `teleportMode` | string | `"CROSS_SERVER"` | `"SAME_SERVER"` or `"CROSS_SERVER"` (compared case-insensitively) |
| `syncPlayerData` | boolean | `true` | Whether inventory, party and PC are serialised and carried across |

The two modes are genuinely different code paths, and the mode decides which half of the
mod you are using:

- **`SAME_SERVER`** — everything happens on this server. Entering means a 5-second
  countdown, then a teleport to `hubPos`, then a teleport to `spawnPos` once the world is
  prepared. No proxy, no player-data serialisation, no `targetServer`.
- **`CROSS_SERVER`** — the default. The leader's client is first asked for the player
  count of `targetServer` over the BungeeCord plugin channel; if the answer never comes
  within 60 ticks, or if the server reports as offline, the attempt is dropped and the
  leader is told *Dungeon server is offline. Please try again later.* Only then does the
  countdown start. At the end of it each member receives a `DungeonJoinPayload` and is
  transferred to `targetServer` 40 ticks later.

A dungeon created from the dashboard is forced to `SAME_SERVER`, because the dashboard has
no way to know your proxy topology.

⚠️ `CROSS_SERVER` mode needs the Velocity plugin from `velocity-party-forwarder/` on the
proxy, and the mod on both the main and the dungeon server with
`cobblemon.dungeons.serverRole` set appropriately. See
[the instance lifecycle](instances.md) and [installation](installation.md). Its
client→server payloads are not validated by the server: don't use this mode on a public
server until this is fixed.

## `locations` and `additionalLocations`

Each location object holds five positions. A position is `{x, y, z, dimension}` with
`dimension` as a full dimension id.

| Key | Type | Default | What it does |
|---|---|---|---|
| `spawnPos` | position | `0, 100, 0` in `minecraft:overworld` | Where players land once the dungeon is prepared; also the centre of every spawn-radius scan |
| `hubPos` | position | same | Holding area players see during preparation |
| `bossRoomPos` | position | same | Where the portal sends the party, and where a wiped player respawns once the boss room is unlocked |
| `returnPos` | position | same | Where `SAME_SERVER` players are teleported on leave |
| `bossSpawnPos` | position | `null` | Overrides `boss.bossSpawnPos` for this slot |

`additionalLocations` is how one dungeon definition serves several concurrent parties:
`getAllLocations()` returns `locations` followed by `additionalLocations`, and in
`SAME_SERVER` mode `findAvailableSlot` hands out the first index no live instance is
sitting on. With no additional locations there is exactly one slot, and a second party
gets *Dungeons are full! Please try again later.*

In `CROSS_SERVER` mode the main server allocates nothing (its countdown carries
`locationIndex` `-1`); the slot is allocated on the dungeon server, by `findAvailableSlot`
in `handleCrossServerJoin`, when the first member of a party arrives. A party that finds
every slot busy is told *Dungeons are full!* and sent back to `mainServer`.

⚠️ Three teleports ignore the allocated slot and always use the **first** `locations`
block: the move from the hub to `spawnPos` once preparation is done, the boss-prompt
teleport to `bossRoomPos`, and the team-wipe teleport. Scans, the boss spawn, rejoin and
respawn do use the active slot. With `additionalLocations`, a party on slot 2 is therefore
prepared around its own `spawnPos` but delivered to slot 1's.

## `requirements`

| Key | Type | Default | What it does |
|---|---|---|---|
| `minLevel` | int | `1` | Declared but never read by any code path |
| `dungeonsPartySizeLimit` | int | `5` | Hard cap on party size at entry |
| `timeLimitSeconds` | int | `1800` | Countdown for the whole run, in seconds |
| `requiredKeyItems` | array of strings | `[]` | Key names that must all be collected before the boss unlocks |
| `requiredAlphas` | int | `0` | Alpha Pokémon to defeat |
| `requiredGems` | int | `0` | Gems to find in chests |
| `requiredGuardians` | int | `0` | Guardian trainers to beat |
| `requiredStakes` | int | `0` | Stakes to find |
| `soloLives` | int | `3` | Shared lives for a party of 1 |
| `smallPartyLives` | int | `6` | Shared lives for a party of 2 or 3 |
| `largePartyLives` | int | `10` | Shared lives for a party of 4 or more |
| `cooldownSeconds` | int | `0` | Per-player cooldown after a success. `0` disables it |

`minLevel` exists in the data class and in no logic — no handler reads it. Treat it as
documentation for your own notes, not as an enforced gate.

The four `required*` counters are both objectives and spawn targets. The instance sets
`alphaSpawnTarget`, `guardianSpawnTarget` and `stakeSpawnTarget` from them at
construction, so `requiredStakes: 4` means "spawn four Stakes and require four". Setting
a counter to `0` marks that objective complete before the run starts: the constructor
sets `stakesSpawned` and `areGuardiansSpawned` to `true` when their requirement is zero,
which is also what makes a dungeon with no Guardians ready for players immediately.

Lives are shared by the party, not per player. A death or a full team wipe costs one
life; at zero the run fails for everyone. The thresholds are evaluated against the party
size captured when the instance was built, so a member who disconnects before the
countdown ends does not give the rest more lives.

`timeLimitSeconds` is decremented once per `tickDungeons`, which runs every 20 server
ticks — one real second on a healthy server, longer on a lagging one. The timer is wall
clock only in the sense that it follows tick rate.

`cooldownSeconds` is recorded on **success only**, for every member of the party, keyed
by dungeon id and player UUID in `config/cobblemon_dungeons/cooldowns.json`. A failed run
costs nothing. The check at entry rejects the whole party if any single member is still
cooling down, and names them: *<player> is still on cooldown for this dungeon (Xm Ys
remaining)*.

## `scaling`

| Key | Type | Default | What it does |
|---|---|---|---|
| `tier` | int | `1` | Tier used when a run starts without an explicit tier |
| `tierLevels` | array of `{tier, level}` | `[]` | Level each tier scales content to |

Tier is the one dial players turn. The GUI offers Tier 1 to Tier 5 as five buttons, and
`startDungeon(player, id)` without a tier falls back to `scaling.tier`. Tier then selects
which loot pools, boss entries, Alpha entries and Guardian entries apply — each of those
carries its own `tier` field where `0` means "any tier".

`tierLevels` maps a tier to the level content spawns at. A tier with no entry in the
table falls back to a built-in table in `resolveTierLevel`: tier 1 → 15, 2 → 30, 3 → 60,
4 → 85, 5 → 115; any other tier gets `0`, meaning no override. Tiers 1 to 5 therefore always
have a level, even with `tierLevels: []`.

## `spawning`

| Key | Type | Default | What it does |
|---|---|---|---|
| `alphaPool` | array | `[]` | Candidate Alpha Pokémon, weighted |
| `alphaSpawnRadius` | int | `150` | Search radius around `spawnPos` for Alpha spawner blocks |
| `alphaTriggerRadius` | double | `5.0` | How close a player must come for an Alpha spawner to fire |
| `guardianSpawnRadius` | int | `150` | Search radius for Guardian spawner blocks |
| `stakeSpawnRadius` | int | `150` | Search radius for Stake spawner blocks |
| `portalSpawnRadius` | int | `150` | Search radius for Portal blocks |
| `chestSpawnRadius` | int | `150` | Search radius for Dungeon Chest blocks |
| `alphaSpawnerIds` | array of int | `[]` | Which numbered Alpha spawner variants this dungeon uses |
| `guardianSpawnerIds` | array of int | `[]` | Which numbered Guardian spawner variants |
| `stakeSpawnerIds` | array of int | `[]` | Which numbered Stake spawner variants |
| `portalIds` | array of int | `[]` | Which numbered Portal variants |
| `chestIds` | array of int | `[]` | Which numbered chest variants — also the loot pool id |
| `spawnerScanVerticalRange` | int | `64` | Vertical half-range of the block scan |
| `guardianPool` | array | `[]` | Candidate Guardian trainers, weighted |
| `guardianTrainerIds` | array of strings | `[]` | Extra trainer **names** that count as a Guardian when a player beats them. Nothing is spawned from this list |
| `spawnProtectionRadius` | double | `100.0` | Declared but never read by any code path |

The numbered id lists are how two dungeons share one world without stealing each other's
furniture. The mod registers 20 Alpha spawner block variants (`alpha_spawner`,
`alpha_spawner_2` … `alpha_spawner_20`) and 10 each of Guardian spawner, Stake spawner,
Portal and Dungeon Chest. A dungeon only reacts to the variants listed in its own
`*Ids` array, so `alphaSpawnerIds: [1]` makes a dungeon blind to `alpha_spawner_2`. An
empty array — the default — means no filter: every variant is used.

⚠️ Two variant families are discovered but never activated. `checkPortalActivation` only
lights blocks that are the base `portal` block, so `portal_2` … `portal_10` never become
visible and cannot be used; and `spawnStakes` only sets a Stake on the base `stake_spawner`,
so `stake_spawner_2` … `stake_spawner_10` never hold one — a dungeon relying on them can
never reach `requiredStakes`. Use variant 1 of both (`portal`, `stake_spawner`).

`chestIds` does double duty: the chest variant number is also the `id` a loot pool must
carry to be used for that chest.

Positions are not stored in the config. They are discovered: block placements go into a
per-world registry at chunk load, and each live instance scans the chunks within its
radius on a budget of 32 chunk scans per tick across all instances. Large radii therefore
cost preparation time rather than TPS.

### `alphaPool` entries

| Key | Type | Default | What it does |
|---|---|---|---|
| `species` | string | `"ghastly"` | Cobblemon species name |
| `tier` | int | `0` | Tier this entry belongs to. `0` means any tier |
| `minLevel` | int | `10` | Lower bound of the rolled level — only when the tier level is `0` |
| `maxLevel` | int | `20` | Upper bound — same condition |
| `weight` | int | `10` | Relative selection weight |

Whenever the run's tier resolves to a level above zero — always the case for tiers 1 to 5,
see [`scaling`](#scaling) — the Alpha spawns at exactly that level and `minLevel` /
`maxLevel` are ignored.

The stock default `"ghastly"` is a typo for Gastly that has never been corrected in the
data class. An unknown species is logged once and skipped, so a pool of nothing but
typos produces a dungeon with no Alphas and an objective nobody can complete.

### `guardianPool` entries

| Key | Type | Default | What it does |
|---|---|---|---|
| `trainerId` | string | `"generic_trainer"` | Id of a trainer registered with RCTApi |
| `tier` | int | `0` | Tier this entry belongs to. `0` means any tier |
| `weight` | int | `10` | Relative selection weight |
| `level` | int | `0` | Overrides the tier level when greater than zero |

See [trainers and RCTApi](trainers.md) for where `trainerId` comes from.

## `boss`

| Key | Type | Default | What it does |
|---|---|---|---|
| `pool` | array of boss entries | `[]` | Weighted candidates |
| `bossSpawnPos` | position | `10, 100, 10` in `minecraft:overworld` | Where the boss appears, unless the active slot overrides it |
| `species` | string | `"pikachu"` | Legacy single-boss field |
| `level` | int | `50` | Legacy |
| `customName` | string | `"Dungeon Boss"` | Legacy |
| `isShiny` | boolean | `false` | Legacy |

The four legacy fields are the pre-pool format and are still honoured: if `pool` is empty,
or if no entry matches the run's tier and none has `tier: 0`, the mod builds a one-off
boss from `species`, `level`, `customName` and `isShiny`. Use `pool` for anything new.

### Boss pool entries

| Key | Type | Default | What it does |
|---|---|---|---|
| `species` | string | `"pikachu"` | Cobblemon species name |
| `tier` | int | `0` | Tier this entry belongs to. `0` means any tier |
| `level` | int | `50` | Boss level. `0` or less uses the tier level |
| `customName` | string | `"Dungeon Boss"` | Name shown above the entity |
| `isShiny` | boolean | `false` | Spawn it shiny |
| `weight` | int | `10` | Relative selection weight within the matching tier |
| `rewardChance` | double | `100.0` | Percent chance each member receives the boss itself in their PC |
| `scale` | double | `4.0` | Visual scale, applied through the `scale:` aspect and `CustomScale` persistent NBT |
| `customRewards` | array of completion rewards | `[]` | Extra rewards added to the pool for this boss only |

Selection prefers entries whose `tier` equals the run's tier; only if that filtered list
is empty does it fall back to entries with `tier: 0`. A species name that Cobblemon does
not know is an error in the log and no boss at all, which leaves the run unfinishable.

## `lootPools`

| Key | Type | Default | What it does |
|---|---|---|---|
| `id` | int | `1` | Chest variant this pool fills |
| `tier` | int | `0` | Tier this pool serves. `0` is the fallback |
| `rolls` | int | `1` | Number of weighted draws per chest |
| `items` | array of loot items | `[]` | Candidates |

Pool resolution for a chest is: exact match on `id` **and** the run's tier first, then a
match on `id` with `tier: 0`. No match means an empty chest.

Each roll picks one item by weight out of the whole `items` list, independently — the same
item can come up twice in one chest. If the weights sum to zero nothing is generated.

### Loot item entries

| Key | Type | Default | What it does |
|---|---|---|---|
| `item` | string | `"minecraft:diamond"` | Item id, or the exact `itemName` of a configured dungeon ticket |
| `customName` | string | `""` | Overrides the display name |
| `min` | int | `1` | Lower bound of the stack size |
| `max` | int | `1` | Upper bound |
| `weight` | int | `10` | Relative weight |
| `nbt` | string | `""` | SNBT string, parsed and stored as the stack's `custom_data` component |

Putting a ticket in a chest is done by naming it, not by item id: the generated example
file uses `"§6Copper Dungeon Ticket"` as the `item` value, which matches a ticket by its
configured `itemName` (case-insensitively, and `&` may stand in for `§`). See
[tickets](tickets.md).

## `completionRewards`

| Key | Type | Default | What it does |
|---|---|---|---|
| `item` | string | `"minecraft:diamond"` | Item id |
| `customName` | string | `""` | Overrides the display name |
| `min` | int | `1` | Lower bound of the stack size |
| `max` | int | `1` | Upper bound |
| `chance` | double | `100.0` | Percent chance this reward is rolled |
| `tier` | int | `0` | Tier this reward serves. `0` means any tier |
| `nbt` | string | `""` | SNBT string, stored as the stack's `custom_data` component |

Completion rewards are rolled per player when the boss dies. Eligible rewards are those
whose `tier` is `0` or equals the run's tier, plus the boss entry's `customRewards`. The
list is shuffled per player before rolling, so when `rewardCap` bites, which rewards a
player gets is random rather than being the first ones in the file. A reward that does not
fit in the inventory is dropped at the player's feet.

## Outside the file: hazards and named entities

Two parts of a dungeon are built in the world rather than declared in the JSON.

### Hazards

All four items are in the **Cobblemon Dungeons** creative tab. Hazards work anywhere in the
world, inside a run or not.

| Item | Effect |
|---|---|
| `poison_water_bucket` | Places poison water. Standing in it (feet or eyes) gives Poison II for 5 seconds, refreshed while you stay in |
| `poison_gas` | A block with no collision. Standing in it gives Poison I for 3 seconds |
| `gas_mask` | Helmet. Worn, or held in either hand, it makes the wearer immune and clears Poison |
| `gas_detector` | Held in either hand, shows *⚠ Poison Gas Detected Nearby! ⚠* in the action bar, once a second, when a gas block is within 5 blocks horizontally (2 below, 5 above) |

A Pokémon of the Poison type, or one holding a gas mask as its Cobblemon held item, is immune
too.

### Named entities

`InteractionHandler` reacts to a right-click on any entity whose custom name contains one of
these words, case-insensitively, tested in this order:

| Name contains | Effect |
|---|---|
| `dungeon master` | Opens the dungeon selection GUI, for anyone |
| `key` | For a player in a run: collects a key named exactly like the entity — the string `requirements.requiredKeyItems` must list — and removes the entity |
| `gem` | For a player in a run: counts one Gem and removes the entity |
| `stake` | For a player in a run: counts one Stake and removes the entity |
| `boss altar` | For a player in a run: spawns the boss if every objective is complete, otherwise *You need all objectives completed to summon the boss!*; *The boss has already appeared!* / *The boss has already been defeated!* afterwards |

The first word that matches wins, so an entity named "Boss Altar Key" is a key. Named armour
stands are the usual carrier.

---

← [Configuration](configuration.md) · [Instances and their lifecycle](instances.md) →
