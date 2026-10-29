[← CheeseRaids](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

Symptom, cause, fix. The mod has never been run with players, so this page is derived
from the failure paths the code logs rather than from support history — but those paths
were written in response to real failures, and the log lines quoted below are verbatim.

Start every investigation in `logs/latest.log`, filtering on the prefix:

```bash
grep '\[CheeseRaids\]' logs/latest.log
```

---

## The mod does not load

**Cause.** Three possibilities, in order of likelihood. A missing hard dependency —
`fabric.mod.json` declares `cheesedash` and `cheeseguard` as required, so Fabric refuses to
start without either. A Fabric Loader older than 0.16.0. A Java older than 21.

**Fix.** Check the crash report or the startup log for Fabric's own dependency message,
which names the missing mod. Build and install both siblings:

```bash
cd CheeseDash   && ./gradlew build
cd ../CheeseGuard && ./gradlew build
cd ../CheeseRaids && ./gradlew build
```

The successful startup line is:

```text
[CheeseRaids] initialized — modular raid framework ready.
```

followed, once the world is up, by:

```text
[CheeseRaids] Loaded 1 raid definition(s), 0 active instance(s)
```

## The mod loads but no raid is listed

**Cause.** `/cheeseraids status` prints an empty list when `definitions` is empty. That
happens in exactly one situation the mod cannot recover from: `config/cheeseraids/raids/`
contains `.json` files and **all of them** failed to parse. The default `hoopa.json` is
only seeded when the directory holds no `.json` file at all, so a single malformed file
blocks the seed.

**Fix.** Look for the parse error:

```text
[CheeseRaids] FAILED to parse raid def .../hoopa.json — fix the JSON syntax and
/cheeseraids reload. Left untouched. (…)
```

Fix the syntax, or move the file out of the directory and restart to get a fresh default.
The mod never overwrites a malformed file, so your edits are still there.

## A config edit has no effect

**Cause.** Four candidates, and the first two are by far the most common.

1. The file did not parse. `/cheeseraids reload` replies *Définitions de raids
   rechargées.* unconditionally — it says the same thing whether the reload worked or not.
   The old definition does not stay in memory either: the reload drops it, so the raid
   disappears from `status` until the file parses.
2. The key does nothing. Sixteen keys in the file are read by no code; they are listed in
   [configuration](configuration.md). `boss.spawnCommand`, `wave.spawnCommand`,
   `boss.provider` and `arena.gapMin`/`gapMax` are the ones people reach for first.
3. You edited `arena.keepInventory` after the rift already existed. The dimension's config
   is only applied at creation; change it in CheeseGuard instead.
4. The raid is running. A reload mid-raid changes behaviour under the live instance in
   confusing ways. Stop it first.

**Fix.** After every reload, read the console. Then confirm what the server actually holds:

```bash
curl -s http://localhost:44311/api/v1/raids/definitions -H "Authorization: Bearer $TOKEN"
```

That route serialises the in-memory objects, so it is the only place your edit can be
verified rather than assumed.

## A command is refused or invisible

**Cause.** Six of the seven subcommands require permission level 2. Below that level the
subcommand does not appear in tab completion and typing it gives the vanilla *Unknown or
incomplete command*. The exception is `/cheeseraids enter`, which has no permission
requirement.

Two other refusals look like permission problems but are not. *Réservé aux joueurs.* means
`enter` or `testreward` was run from the console or a command block — both need a player.
And nothing tab-completes the raid id: the argument is a plain word with no suggestion
provider, so a typo is indistinguishable from an unknown raid.

**Fix.** `/op <player>`, or run it from the console for the admin branches other than `testreward`. Get the
exact id from `/cheeseraids status`.

## `start` refuses with no explanation

**Cause.** `Impossible de démarrer '<id>' (inconnu/désactivé/déjà actif).` covers three
different conditions with one message.

**Fix.** `/cheeseraids status` disambiguates:

- the id is **absent from the list** → unknown. Check the `id` field inside the file, not
  the file name — the field wins;
- the id is listed as `[idle]` and `start` still refuses → `enabled` is false;
- the id shows a phase → already running. `/cheeseraids stop <id>` first.

## The wave never spawns

**Cause.** The log tells you which of three it is.

```text
[CheeseRaids] wave arena world 'minecraft:overwrld' NOT FOUND — fix wave.arena.world.
Available: minecraft:overworld minecraft:the_nether …
```

A typo in the world id. The message lists every world that does exist.

```text
[CheeseRaids] Cobblemon API unavailable; cannot spawn 'hoopa level=80 aspect=unbound uncatchable=true'
```

Cobblemon is absent or its `PokemonProperties` API moved. Cobblemon is **not**
declared as a dependency, so nothing warned you at startup.

```text
[CheeseRaids] spawnEntity refused 'sableye level=60 uncatchable=true' at 112.3,72.0,-2043.1
(chunk not loaded / build limit?)
```

The chunk is not loaded — nobody is near the wave arena — or the position is outside the
build limit.

**Fix.** Respectively: fix `wave.arena.world`; install Cobblemon; be in the area, or pick
an arena inside a permanently loaded region. A raid with zero spawned mobs does not hang —
it logs `spawned 0 wave mob(s) — proceeding.` and advances.

## The herald never appears

**Cause.** The reveal looks for entities whose registry id starts with `easy_npc`, within
10 blocks of `npc.pos`, in the world `npc.pos.world` names. Four things break it: wrong
coordinates, wrong world, an unloaded chunk, or an EasyNPC display API change.

**Fix.** The diagnostic separates all four:

```mcfunction
/cheeseraids herald hoopa info
```

- `Monde '<id>' non chargé.` → wrong world id;
- `EasyNPC dans 10 blocs: 0` plus `Autres EasyNPC dans 128 blocs (mauvaise position ?)` →
  coordinate typo, and the listing tells you where the NPC actually is;
- `⚠ AUCUN EasyNPC chargé à proximité` → nothing within 128 blocks. Usually an unloaded
  chunk: stand next to the NPC and run it again;
- a correct listing but `0 toggled via display-attr` in the console → EasyNPC is found and
  the visibility API changed. Also logs `setDisplayAttribute(VISIBLE,...) not found`.

This failure does not stop the raid. It announces `/cheeseraids enter <id>` in chat
instead, which is why a broken herald can go unnoticed for a whole event.

## Players cannot enter even though the herald is visible

**Cause.** This is the one most likely to catch you out. **CheeseRaids does not give the
herald any dialogue.** It makes the NPC visible and glowing; it contains no interaction
handler and no EasyNPC action binding. Chat tells players *parlez-lui* — talk to him — and
nothing happens when they do, because nothing was ever bound.

**Fix.** Configure the NPC's dialogue in EasyNPC yourself, with an action that runs
`/cheeseraids enter <raid>`. Until then, tell players to type the command. Nothing in this
repository does this for you and nothing checks that you did.

## `enter` is refused

| Message | Cause | Fix |
|---|---|---|
| `Aucune invasion active.` | no instance under that id | `/cheeseraids status`; start the raid |
| `La faille n'est pas ouverte.` | the raid is running but not in `OPEN` — the wave is still up, or the boss fight already started | wait, or accept that entry has closed |
| `La faille n'a pas pu s'ouvrir.` | the rift dimension could not be created or resolved | console: `Failed to create rift dimension cheeseguard:<id>`. Check CheeseGuard is loaded and `arena.riftDimId` is a legal identifier |
| `La faille est pleine.` | all `arena.maxSlots` slots taken | raise `maxSlots`, or wait for the next raid |
| `Réservé aux joueurs.` | run from the console | run it as a player |

## The parkour path does not appear

**Cause.** The branch is cleared to air and then animated in at `arena.revealBlocksPerTick`
blocks per tick, after `arena.revealDelayTicks` ticks of build-up — three full seconds of
particles before anything is placed, by default (60 ticks). A long path at eight blocks per tick takes
a while. If nothing appears at all after that, the reveal queue was cleared: `clearReveals`
runs at cleanup, and `/cheeseraids stop` triggers it.

**Fix.** Wait out the delay. Raise `revealBlocksPerTick` if the pace feels wrong. If a
player is standing on an empty branch after a stop, that raid is over — teleport them out.

## The boss does not spawn

**Cause.** Three, each with its own log line.

```text
[CheeseRaids] Boss rift world cheeseguard:hoopa_rift not loaded
```

The rift `cheeseguard:` + `arena.riftDimId` does not exist or is not loaded
(`arena.center.world` plays no part: it is never read).

```text
[CheeseRaids] Boss spawn FAILED for hoopa — see preceding log.
```

The Cobblemon spawn was refused; the preceding line says why. The two causes the code
already defends against are suffocation (an air pocket is carved first, sized from
`boss.baseScale`) and the centre chunk unloading while players are out on the parkour (the
chunk is force-loaded).

No log line at all, with the raid stuck in `COMBAT`: an effects-task exception dropped the
apparition before it reached tick 36, where the spawn happens. Look for
`FX task error` just before.

**Fix.** Look for `Failed to create rift dimension` earlier in the log and check that
CheeseGuard is loaded. Install Cobblemon. For the third case, the raid self-resolves after
`boss.combatTimeoutSeconds` — 15 minutes by default, so stop it manually.

## The boss vanished and nobody won

**Cause.** The provider tries to distinguish a kill from a vanish. A boss gone within 200
ticks (ten seconds) of spawning with no battle recorded is treated as a spawn failure, not
a victory, and is respawned up to twice:

```text
[CheeseRaids] Boss for hoopa vanished pre-battle — respawning (1/2).
```

Past two respawns, or for a vanish more than ten seconds after the spawn, nothing is
respawned — and the raid does **not** wait for the combat timeout. With no battle recorded,
a boss that has been gone once five seconds have passed since its last spawn counts as a
kill, so the raid moves to `REWARD` and everyone online is rewarded although nobody won.

**Fix.** A repeating pre-battle vanish is suffocation or a void fall. Raise
`arena.center.y`, or check that nothing else is writing blocks into the rift.

## No victory portals opened

**Cause.** Four, in the order the code tries them.

```text
[CheeseRaids] No Hoopa portal opened (2 earned) — CobbleSafari absent, or it requires the
overworld (anchor is in <world>).
```

CobbleSafari is not installed, or its `PortalSpawnManager` methods could not be resolved
(`CobbleSafari portal methods not found` at init), or the anchor is outside the overworld
and only the overworld-only fallback was available, or every participant disconnected
before the reward — the portal call needs an online player as owner.

**Fix.** Test the position and the dependency without running a raid:

```mcfunction
/cheeseraids testreward hoopa
```

`0/1` confirms the dependency side; `Monde d'ancrage introuvable: <id>` confirms the world
id. As a last resort, set `reward.portalCommand` to something that works on your server —
it runs only when CobbleSafari opened nothing, and lays portals out in a line rather than a
circle.

## Rewards went to someone who did nothing

**Cause.** Not a bug, a design gap. Every player in the instance who is **online** when the
boss dies gets the fireworks, the fromage and every `reward.commands` entry. The `arrived`
flag is tracked and the reward path does not read it.

**Fix.** None available in configuration. It needs a code change in `RewardManager`.

## Players are stuck in the rift after a stop

**Cause.** Neither `/cheeseraids stop` nor `POST /api/v1/raids/stop` teleports anyone out.
Cleanup air-fills every branch and clears the arena, so a player who was mid-parkour is
left standing in a void dimension whose floor has just been deleted — and that dimension
has `enabled: false`, so they cannot leave through a portal.

**Fix.** Teleport them yourself before stopping, or afterwards:

```mcfunction
/execute in minecraft:overworld run tp <player> <x> <y> <z>
```

Then let the victory path handle ends when you can: it ejects everyone properly.

## The dashboard module is missing

**Cause.** The module is loaded through the Fabric custom entrypoint `cheesedash`. If the
sidebar has no **CheeseRaids** entry, either the CheeseRaids jar is not in `mods/` at all,
or CheeseDash started without discovering it.

**Fix.** Confirm both jars are present and that the startup log shows CheeseDash loading
its modules. Then check the prefix directly:

```bash
curl -s http://localhost:44311/api/v1/raids/ -H "Authorization: Bearer $TOKEN"
```

`503 {"error":"CheeseRaids not ready"}` means the module loaded but the server has not
finished starting — wait. A `404` from `/api/v1/modules` not listing `raids` means the
entrypoint was not discovered.

## The dashboard shows stale data

**Cause.** The `raids` WebSocket channel carries exactly one message type, `raid_update`,
and it is emitted on **phase transitions only**. Nothing broadcasts when a player enters,
crosses a checkpoint, or when the boss spawns — and the shipped view does not even listen
to that channel (see [the dashboard page](dashboard.md#websocket)). The participant table is
redrawn only when you press *Rafraîchir* or use one of the view's buttons.

**Fix.** Press *Rafraîchir*. This is a known limitation, not a connection problem.

## A scheduled raid did not fire

**Cause.** The scheduler is not a cron. It polls once a second and fires only when the
server's local hour **and** minute both match, so a server that is down or restarting for
that whole minute misses the run with no catch-up. It also skips a raid that is already
active, one with `enabled: false`, and one whose `state.json` stamp is less than
`everyNDays` old.

**Fix.** Confirm `autoTrigger` is true via `/cheeseraids status`, which shows
`(auto HHhMM)`. Check the server's own timezone, not yours. The firing line is:

```text
[CheeseRaids] Scheduler triggers raid 'hoopa'
```

If `state.json` holds a stamp from a test run, delete the entry to allow an immediate run.

---

## Reporting a bug

Open an issue at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) with:

1. **Every `[CheeseRaids]` line from the run**, not just the error:
   ```bash
   grep '\[CheeseRaids\]' logs/latest.log > cheeseraids.log
   ```
   Phase transitions themselves are not logged, but the steps around them are (wave
   spawned, wave done, boss spawned, rewards, cleanup), as is every hook failure, so the
   whole sequence is far more useful than the last line.
2. **The raid definition**, from `GET /api/v1/raids/definitions` rather than the file —
   that is what the server actually has in memory. Redact nothing; there are no secrets in
   it.
3. **The instance file** if a raid is stuck: `config/cheeseraids/instances/<raidId>.json`.
   It holds every deadline, the boss's entity and battle ids, and each player's checkpoint
   — none of which the REST API exposes.
4. **Which optional mods are installed**, with versions: Cobblemon, EasyNPC, CobbleSafari,
   CheeseStore. Four of the mod's dependencies are undeclared and reached by reflection, so
   a version mismatch is a likely cause and invisible from the logs alone.
5. **What phase it was in**, from `/cheeseraids status` or the dashboard card.

For a crash, attach `crash-reports/` as well as the log.

Questions rather than bugs → [Arkensia Discord](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.md) · [CheeseRaids](../README.md) →
