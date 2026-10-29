[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this walkthrough you will have a working single-server dungeon: a small
arena with two Alpha Pokémon and one Guardian trainer, entered with a ticket you gave
yourself, finished by beating a Charizard boss, with your time on the leaderboard and the
run visible in the dashboard. Allow half an hour, most of it spent placing blocks.

The dungeon we build runs in `SAME_SERVER` mode. Cross-server dungeons need a proxy and a
second server, and they are a separate exercise — [installation](installation.md) covers
the setup.

Prerequisite: a 1.21.1 Fabric server with Cobblemon, RCTApi, CheeseDash and this mod
installed and started once, plus a client with the mod. If `/dungeons` does not exist yet,
go back to [installation](installation.md).

---

## 1. Confirm the mod is live

Join the server and run:

```mcfunction
/dungeons
```

You should get the dungeon selection screen, listing **Generic Dungeon** — the example
dungeon written on first launch. Close it.

If instead you see *You need one or more pokemon on your team to enter dungeons*, catch
something first. Every entry point checks for a non-empty Cobblemon party.

## 2. Create your own dungeon

Rather than edit the example, create a fresh one from the dashboard so you start from
clean defaults. Log in to CheeseDash, then:

```bash
curl -X POST http://localhost:44311/api/v1/dungeons/configs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"id":"tutorial","name":"Tutorial Dungeon"}'
```

The response is the full config of the new dungeon. A dungeon created this way is forced
to `SAME_SERVER`, which is what we want. You can equally create
`config/cobblemon_dungeons/dungeons/tutorial.json` by hand and reload — the result is the
same file.

Run `/dungeons` again: **Tutorial Dungeon** is now in the list. It is unplayable at the
moment, because its coordinates are all `0, 100, 0` and it has no content.

## 3. Pick your coordinates

Stand where you want players to start and note the position. For this walkthrough you need
four places, which can be within sight of each other:

- **spawn** — where players land and the centre of every block scan
- **hub** — a small sealed room where players wait during preparation
- **boss room** — where the portal sends the party
- **return** — where players are teleported when they leave

Write them down. `/tp` or F3 will give you the numbers.

## 4. Fill in the config

Edit `config/cobblemon_dungeons/dungeons/tutorial.json`. The minimum that makes a playable
dungeon, with the fields we care about and defaults everywhere else:

```json
{
  "general": { "name": "Tutorial Dungeon", "id": "tutorial", "guiIcon": "minecraft:iron_sword" },
  "networking": { "teleportMode": "SAME_SERVER", "syncPlayerData": true },
  "locations": {
    "spawnPos":    { "x": 100, "y": 64, "z": 100, "dimension": "minecraft:overworld" },
    "hubPos":      { "x": 100, "y": 80, "z": 100, "dimension": "minecraft:overworld" },
    "bossRoomPos": { "x": 160, "y": 64, "z": 100, "dimension": "minecraft:overworld" },
    "returnPos":   { "x": 0,   "y": 70, "z": 0,   "dimension": "minecraft:overworld" }
  },
  "requirements": {
    "dungeonsPartySizeLimit": 5,
    "timeLimitSeconds": 900,
    "requiredAlphas": 2,
    "requiredGuardians": 1,
    "requiredGems": 0,
    "requiredStakes": 0,
    "soloLives": 3,
    "cooldownSeconds": 0
  },
  "scaling": { "tier": 1, "tierLevels": [ { "tier": 1, "level": 20 } ] },
  "spawning": {
    "alphaPool": [ { "species": "gastly", "tier": 0, "minLevel": 18, "maxLevel": 22, "weight": 10 } ],
    "alphaSpawnerIds": [ 1 ],
    "guardianSpawnerIds": [ 1 ],
    "portalIds": [ 1 ],
    "chestIds": [ 1 ],
    "alphaSpawnRadius": 64,
    "guardianSpawnRadius": 64,
    "portalSpawnRadius": 64,
    "chestSpawnRadius": 64,
    "guardianPool": [ { "trainerId": "example_trainer", "tier": 0, "weight": 10, "level": 20 } ]
  },
  "boss": {
    "bossSpawnPos": { "x": 160, "y": 64, "z": 100, "dimension": "minecraft:overworld" },
    "pool": [
      { "species": "charizard", "tier": 0, "level": 30, "customName": "Tutorial Boss",
        "isShiny": false, "weight": 10, "rewardChance": 100.0, "scale": 3.0 }
    ],
    "customName": "Tutorial Boss"
  },
  "rewardCap": 1,
  "lootPools": [
    { "id": 1, "tier": 0, "rolls": 2,
      "items": [ { "item": "cobblemon:ultra_ball", "min": 2, "max": 5, "weight": 20 } ] }
  ],
  "completionRewards": [
    { "item": "minecraft:diamond", "min": 1, "max": 3, "chance": 100.0, "tier": 0 }
  ]
}
```

Two details that are easy to get wrong:

- `boss.customName` is set to the same string as the pool entry's `customName`. Keep them
  aligned — the legacy field is still consulted in one code path, as
  [trainers](trainers.md#counting-a-victory) explains.
- `requiredAlphas: 2` and `requiredGuardians: 1` are both objectives **and** spawn
  targets. Two Alphas will spawn because you require two.

Reload:

```bash
curl -X POST http://localhost:44311/api/v1/dungeons/configs/reload \
  -H "Authorization: Bearer $TOKEN"
```

```json
{"reloaded":true}
```

The log shows `Registered dungeon from file: tutorial.json`.

## 5. Build the arena

Now place the blocks the dungeon will find. Everything is positional — the config never
stores a block coordinate, it stores a search radius and a list of variant numbers.

Give yourself the blocks and place them within 64 blocks of your spawn position:

```mcfunction
/give @s cobblemon_dungeons:alpha_spawner 2
/give @s cobblemon_dungeons:guardian_spawner 1
/give @s cobblemon_dungeons:portal 1
/give @s cobblemon_dungeons:dungeon_chest 2
```

- **Two Alpha spawners**, spread apart. Each fires when a player comes within
  `alphaTriggerRadius` (5 blocks by default).
- **One Guardian spawner**, somewhere a player has to walk to.
- **One Portal**, near the spawn. It stays invisible until every objective is complete.
- **Two Dungeon Chests**, anywhere. Their variant number — `1` here — is also the loot
  pool `id` that fills them.

Build a sealed box at your hub coordinates. Players spend the preparation phase in there
and should not be able to wander off.

If you want gems as an objective, put a positive `requiredGems` in the config and the mod
will seed gems into chests for you — the Gem item consumes itself and counts the moment it
lands in the inventory of a player who is in a run. We left it at zero to keep the
walkthrough short.

## 6. Give yourself a ticket

Entry costs a ticket matching the dungeon id and tier, and the default
`dungeon_tickets.json` only has a template for `generic_dungeon`. Add one for the
tutorial dungeon:

```bash
curl -X PUT http://localhost:44311/api/v1/dungeons/tickets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tickets":[
        {"dungeonId":"generic_dungeon","itemName":"§6Copper Dungeon Ticket","tier":1},
        {"dungeonId":"tutorial","itemName":"§bTutorial Ticket","tier":1}
      ]}'
```

Reconnect — the client only receives the ticket list on join — then give yourself the item
with its custom data:

```mcfunction
/give @s cobblemon_dungeons:dungeon_ticket[custom_data={DungeonId:"tutorial",Tier:1},custom_name='{"text":"Tutorial Ticket","color":"aqua","italic":false}'] 1
```

Hover it. The tooltip should read:

```text
Tutorial Ticket
Dungeon: tutorial
Tier: 1
```

If the two tooltip lines are missing, the `custom_data` did not land and the ticket will
not be accepted at the gate.

## 7. Enter the dungeon

```mcfunction
/dungeons
```

Pick **Tutorial Dungeon**, then **Tier 1**. In order you should see — the `Starting in`
lines in the action bar above the hotbar, one per second, the others in chat:

```text
Dungeon will start in 5 seconds... Prepare your team!
Starting in 4...
Starting in 3...
Starting in 2...
Starting in 1...
Dungeon started: Tutorial Dungeon
Preparing dungeon area... Please wait.
```

Your ticket is gone and you are standing in the hub. The HUD appears at the edge of the
screen with the objective counters, the remaining lives and the timer.

A second or two later:

```text
The dungeon is ready! Prepare yourself.
```

and you are teleported to the spawn position. The Guardian has been spawned by now —
preparation does not finish until it has, which is exactly why there are two teleports.

If you are still in the hub after ten seconds, the Guardian could not be spawned. Check
the log and see [troubleshooting](troubleshooting.md).

## 8. Clear the objectives

**The Alphas.** Walk towards a spawner. A large, aggressive Gastly appears with a boss bar
and comes for you. Do not throw a Poké Ball and do not start a Cobblemon battle — neither
works on an Alpha: the ball is silently cancelled, and the battle is refused with *You can
only engage in Minecraft PVP with Alphas!* Hit it with a weapon. On its death:

```text
<you> defeated an Alpha! (1/2)
```

Do the same with the second.

**The Guardian.** Right-click the NPC at the Guardian spawner. A normal Cobblemon trainer
battle starts against the team in `trainers/example_trainer.json` — one Pikachu, at the
level your pool entry specified. Win it:

```text
<you> defeated a Guardian! (1/1)
```

**The chests.** Not an objective, but open one: it generates two rolls of Ultra Balls,
once, and remembers its contents for the rest of the run.

When the last objective lands:

```text
The portal has been activated! All objectives completed.
```

and the portal block you placed becomes visible.

## 9. Beat the boss

Right-click the portal. The boss-fight prompt appears; accept it. You are teleported to the
boss room and:

```text
The Boss Tutorial Boss has appeared!
<you> has started the boss fight! Teleporting party...
```

A Charizard three times normal size — the `scale: 3.0` of the pool entry. Again: Minecraft combat, not a Pokémon battle. Kill
it and the run completes:

```text
Tutorial Boss has been transferred to your PC!
Congratulations! The dungeon boss has been defeated!
The party wins! You will be teleported back in 10 seconds.
You received a reward: Diamond
```

The boss itself — a Charizard nicknamed *Tutorial Boss* — lands in your PC first, because
`rewardChance` was `100.0`, and you get one
completion reward because `rewardCap` was `1`. Ten seconds later you are back at your
return position.

## 10. Check the records

The run is now in three places.

```bash
curl -s http://localhost:44311/api/v1/dungeons/stats -H "Authorization: Bearer $TOKEN"
```

```json
{"totalRuns":1,"successCount":1,"successRate":100,"avgDurationSeconds":214,
 "configCount":2,"activeInstances":0,
 "runsByDungeon":{"tutorial":{"total":1,"success":1}},
 "runsByTier":{"1":{"total":1,"success":1}}}
```

```bash
curl -s http://localhost:44311/api/v1/dungeons/leaderboard/tutorial -H "Authorization: Bearer $TOKEN"
```

```json
{"tutorial":{"1":[{"playerName":"RoiCheese","playerUuid":"…","durationSeconds":214,"date":1761000000}]}}
```

And on disk: `run_history.json`, `leaderboard.json` and `cooldowns.json` now exist in
`config/cobblemon_dungeons/`.

## Where to go next

- Add a second location slot in `additionalLocations` so two parties can run the dungeon at
  once — see [the dungeon definition](dungeons.md#locations-and-additionallocations).
- Set `cooldownSeconds` and a tier table, then build Tier 2 content alongside Tier 1 by
  tagging loot pools, boss entries and Guardian entries with `tier: 2`.
- Write your own Guardians in `config/cobblemon_dungeons/trainers/` — remembering that
  trainers load at server start only, per [trainers and RCTApi](trainers.md).
- Hand out tickets through a CheeseGuard event with `DUNGEON_TICKET_GIVE`, documented in
  [the dashboard page](dashboard.md#cheeseguard-event-effects).

---

← [Installation](installation.md) · [Commands](commands.md) →
