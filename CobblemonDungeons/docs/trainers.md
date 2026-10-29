[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](trainers.fr.md)

# Trainers and RCTApi

Guardians are NPC trainers with real Cobblemon teams. The mod does not implement battling
itself — it delegates to **RCTApi**, the API side of Radical Cobblemon Trainers, which is
a hard dependency declared in `fabric.mod.json` as `"rctapi": ">=0.13.7-beta"`.

What this mod owns is: loading trainer definitions from JSON, registering them with
RCTApi under its own namespace, placing them in the world at Guardian spawner blocks, and
counting a victory as a dungeon objective.

---

## Where trainers are defined

`config/cobblemon_dungeons/trainers/`, one JSON file per trainer. The file name minus
`.json` is the trainer id — the string you put in `guardianPool[].trainerId` or
`guardianTrainerIds[]`.

The directory is created on first launch along with `example_trainer.json`:

```json
{
  "name": "Example Guardian",
  "entity": "cobblemon:npc",
  "skin": "Steve",
  "team": [
    {
      "species": "pikachu",
      "level": 20
    }
  ]
}
```

The schema is RCTApi's `TrainerModel` — `name`, `team`, battle rules and everything else
RCT supports — extended by `EnhancedTrainerModel` with four fields this mod adds:

| Key | Type | What it does |
|---|---|---|
| `entity` | string | Entity type id to use as the trainer's body, e.g. `cobblemon:npc` |
| `skin` | string | A Minecraft account name whose skin the NPC wears |
| `texture` | string | A skin texture URL, used when `skin` is absent |
| `modelType` | string | `SLIM` or `ALEX` selects the slim player model; anything else is the default |

Because the rest of the file is parsed by RCTApi's own Gson instance, the authoritative
reference for `team` entries and battle rules is RCT's documentation, not this page. What
is documented here is only what the mod reads itself.

## How loading works

`TrainerLoader.load` runs on `SERVER_STARTED`:

1. the directory is created if missing, and the example file is written if absent
2. `RCTApi.initInstance("cobblemon_dungeons")` gets this mod its own RCT instance, so its
   trainers live in a registry separate from whatever `radicaltrainers` has
3. every `*.json` is deserialised into an `EnhancedTrainerModel` and converted to a
   `TrainerNPC`
4. if `entity` is set, the entity is created, made invulnerable, tagged
   `DungeonTrainer`, and given the model's name as a visible custom name
5. for a Cobblemon `NPCEntity` the NPC class is forced to `standard` — without that,
   Cobblemon falls back to its "kitchen sink" class — and the skin or texture is applied,
   with the source recorded as a command tag (`DungeonSkin:…`, `DungeonTexture:…`,
   `DungeonModel:…`)
6. the trainer is registered under its file-name id, logged as
   `Registered custom trainer from file: <file>.json`

A file that fails to convert logs `Failed to convert trainer model from file: …` and is
skipped; the others still load. A file that fails to parse logs
`Failed to load custom trainer file: …`.

⚠️ Trainers load **once**, at server start. The dashboard can read and write trainer
files, and the dungeon configs reload hot, but a changed trainer file does not take effect
until the server restarts. Nothing in the code re-runs `TrainerLoader.load`.

## Placement in the world

Guardians are not placed by coordinates. You build the dungeon and put **Guardian spawner
blocks** where you want them — `guardian_spawner`, `guardian_spawner_2` … up to
`guardian_spawner_10` — and list the variant numbers you want this dungeon to use in
`spawning.guardianSpawnerIds`.

When a run starts, the instance looks up those blocks in the per-world registry within
`spawning.guardianSpawnRadius` of `spawnPos` (with `spawnerScanVerticalRange` as the
vertical half-range), then spawns up to `requirements.requiredGuardians` trainers at the
positions it found, queued so the work spreads over several ticks.

Which trainer appears at each position is a weighted draw over `spawning.guardianPool`,
filtered the same way as every other tiered list: entries whose `tier` equals the run's
tier, falling back to entries with `tier: 0`. A pool entry's `level`, when greater than
zero, overrides the level the tier table would have given.

`spawning.guardianTrainerIds` is the escape hatch: ids listed there are spawned
unconditionally, outside the weighted draw. The generated example uses it for a named
boss-adjacent trainer (`"Guardian Leader"`).

Preparation does not finish until every queued Guardian has been spawned —
`isReadyForPlayers()` requires it — so the party waits in the hub rather than watching
trainers pop in around them.

## Fighting one

Right-clicking a trainer goes through `DungeonManager.handleTrainerInteract`:

1. if the player is already in a Cobblemon battle: *You are already in a battle!*
2. the entity is resolved to a trainer id, first in this mod's RCT registry, then in the
   `radicaltrainers` registry as a fallback
3. if the trainer's whole team is at zero HP: *This trainer has already been defeated.*
4. otherwise the NPC turns to face the player and `api.getBattleManager().startSingle` is
   called

The fallback to the `radicaltrainers` registry means a trainer placed by Radical Trainers
itself, not by this mod, is still fightable through a dungeon interaction. Whether it
*counts* is a separate question — see below.

## Counting a victory

`BattleHandler` subscribes to Cobblemon's `BATTLE_VICTORY`. For each loser it decides
whether that loser was a Guardian, by the first of these that matches:

1. the losing entity carries the command tag `DungeonGuardian`
2. the NPC's name contains the word `guardian`, case-insensitively
3. the NPC's name appears in the winning player's dungeon config, either in
   `spawning.guardianTrainerIds` or as a `guardianPool[].trainerId`
4. the NPC's name resolves to a trainer in this mod's RCT registry

A match increments the Guardian counter, discards the entity, and broadcasts
*<player> defeated <guardian>! (N/M)*.

Rule 2 is a heuristic and it is worth knowing about: any NPC trainer with "guardian"
anywhere in its name counts as a dungeon Guardian when a player in a run beats it, even
one that has nothing to do with the dungeon. If you run Radical Trainers content
alongside dungeons, avoid that word in unrelated trainer names.

The same handler also counts Alphas — wild losers with the `alpha` aspect — and contains a
boss check that compares a wild Pokémon's nickname against the legacy `boss.customName`.
That branch cannot fire in practice: the boss is spawned with the `alpha` aspect, and
`BattleHandler` cancels every battle containing an Alpha, so a boss is never a battle
loser. The path that actually completes a run is in `DeathHandler`, which keys off the
`DungeonBoss` command tag the boss entity carries, and is reliable regardless of names.

## Alphas are not trainers

Alpha Pokémon are wild entities with the `alpha` aspect, scaled up and given custom combat
stats, and they are deliberately **not** battleable through Cobblemon. `BattleHandler`
cancels any battle that would include an Alpha and tells the player *You can only engage
in Minecraft PVP with Alphas!* — they are fought with a weapon, not with a team.
`CaptureHandler` likewise blocks capture: *Alpha Pokemon cannot be caught!*

---

← [Tickets](tickets.md) · [Leaderboards and run history](records.md) →
