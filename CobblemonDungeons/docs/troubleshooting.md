[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

Symptom first, then why, then what to do. The server log is `logs/latest.log`; almost
everything below is diagnosable from it.

---

## The server refuses to start, naming a missing mod

**Cause.** All four of Cobblemon, RCTApi, Fabric API and CheeseDash are in `depends`, so
Fabric aborts rather than loading the mod without them. The message names the id it wanted:
`cobblemon`, `rctapi`, `fabric-api` or `cheesedash`.

**Fix.** Install the named mod at a version satisfying `fabric.mod.json`: Cobblemon
`>=1.6.1`, RCTApi `>=0.13.7-beta`. For `cheesedash`, build the sibling mod first and put
its jar in `mods/` — see [installation](installation.md).

## The build fails before compiling anything

**Cause.** CobblemonDungeons resolves CheeseDash through a glob over
`../CheeseDash/build/libs/`. An unbuilt CheeseDash means an empty file tree and an
unresolvable dependency.

**Fix.**

```bash
cd CheeseDash && ./gradlew build
cd ../CobblemonDungeons && ./gradlew build
```

This mod's build script is `build.gradle.kts`, not `build.gradle`, and its
version is in that file rather than in `gradle.properties`. The artefact is
`build/libs/cobblemon-dungeons-1.1.147+1.21.1.jar`.

## `./gradlew build` cannot find a Velocity class

**Cause.** You are building the wrong project. `velocity-party-forwarder/` is a separate
Velocity plugin and is not part of the main build — the root `settings.gradle.kts` does not
include it. If you ran Gradle inside that directory, it needs a Velocity API jar you supply
yourself.

**Fix.** For the mod, build from `CobblemonDungeons/`. For the plugin, put
`velocity-proxy.jar` in `velocity-party-forwarder/libs/` first. The plugin jar goes in your
proxy's `plugins/`, never in `mods/`.

## `/dungeons` does not exist

**Cause.** The mod is not on the server, or it failed to load.

**Fix.** Look for `Initializing Cobblemon Dungeons...` in the log. Absent, the jar is not
in `mods/` or Fabric rejected it. Present but followed by a mixin error, the mod crashed
during init — all three mixins are `required: true`, so a failed injection is fatal and
usually means a Minecraft version other than 1.21.1.

## *This command is only available on the main server.*

**Cause.** This server's role is `dungeon`. `/dungeons`, `/dungeon` and the whole
`/DungeonsParty` tree are refused there on purpose; only `/dungeons leave` works.

**Fix.** If this is meant to be the main server, remove
`-Dcobblemon.dungeons.serverRole=dungeon` from the launch line and unset
`COBBLEMON_DUNGEONS_SERVER_ROLE`, then restart. The role is read once and cached, so a
restart is required. The log line to confirm it is
`Cobblemon Dungeons server role: main`.

## *You need one or more pokemon on your team to enter dungeons*

**Cause.** `DungeonsPartyManager.hasPokemon` found an empty Cobblemon party. Every entry
point checks it — the command, party creation, invites and joins.

**Fix.** Have at least one Pokémon in the party, not in the PC. If a player insists theirs
is not empty, the same method returns `false` on any exception from Cobblemon's storage, so
check the log for a Cobblemon error at that moment.

## My config change has no effect

**Cause.** Different files reload differently, and two of them never reload.

**Fix.** Match your file against this:

| Changed | Needed |
|---|---|
| `dungeons/*.json` | `POST /api/v1/dungeons/configs/reload` |
| `dungeon_tickets.json` | The same reload, plus a client reconnect for the GUI |
| `trainers/*.json` | **A server restart.** `TrainerLoader.load` only runs on `SERVER_STARTED` |
| `config.json` | **A server restart** for the database pool |
| Server role | **A server restart** |

And one more: a party already inside a dungeon keeps the config object their instance was
built with. A reload never changes the rules of a run in progress.

## A reload reports success but the dungeon is unchanged

**Cause.** Usually one of three. The file is not in `dungeons/` or does not end in `.json`
— the loader filters on both. Or its `general.id` differs from what you expect, since
registration is keyed by the id inside the file and not by the file name. Or two files
declare the same `general.id`, in which case the last one loaded wins and the order is
undefined.

**Fix.** Keep one file per dungeon named `<id>.json`, matching its `general.id`. Check the
`Registered dungeon from file: …` lines in the log — there is one per file actually loaded.

## *Dungeons are full! Please try again later.*

**Cause.** `SAME_SERVER` mode allocates one location slot per concurrent run, and all of
them are busy. A dungeon with no `additionalLocations` has exactly one slot, so a second
party cannot enter.

**Fix.** Add location slots in `additionalLocations`, each with its own coordinates, and
reload. Alternatively force-fail the stale run from the dashboard with
`POST /api/v1/dungeons/active/fail`. A run left behind by a crash does not exist after a
restart, so if the message persists across one, the slots really are in use.

## *You need a valid Dungeon Ticket for <name> (Tier N) to enter.*

**Cause.** The party leader has no ticket whose `DungeonId` matches the dungeon id and
whose `Tier` is at least the requested tier. Only the leader is charged.

**Fix.** Hover the ticket: the tooltip must show both a `Dungeon:` and a `Tier:` line. If
it does not, the `custom_data` component is missing and the item is a lookalike. Check that
`DungeonId` is the dungeon's `general.id` and not its display name — those are different
fields. Tiers are a floor, so a Tier 5 ticket opens a Tier 1 run but not the reverse. See
[tickets](tickets.md).

## A ticket in a loot pool never appears

**Cause.** A ticket is referenced in loot by its **`itemName`**, not by an item id. The
resolver tries the string as an item id first, and only falls back to matching ticket
names when that yields `minecraft:air`.

**Fix.** Use the exact `itemName` from `dungeon_tickets.json`, either with `§` or with `&`
in its place. Both forms are compared case-insensitively. The log says
`Unknown loot item ID or ticket name: <string>` when nothing matched — that line is the
confirmation you have a typo.

## The party waits in the hub and is never teleported in

**Cause.** `isReadyForPlayers()` requires every Stake and every Guardian to have spawned
and no trainer spawn still queued. One of those cannot complete.

**Fix.** Check, in order:

- the number of Guardian spawner blocks within `guardianSpawnRadius` of `spawnPos` is at
  least `requiredGuardians`, and their variant numbers are listed in
  `guardianSpawnerIds`
- the same for Stake spawners and `requiredStakes`
- `spawnerScanVerticalRange` is large enough to reach them — the default 64 is a half-range
  around the spawn's Y
- the Guardian pool resolves: an entry whose `tier` matches the run's tier, or one with
  `tier: 0`
- every `trainerId` in that pool was actually registered, which means a
  `Registered custom trainer from file: …` line in the log for it

A Guardian pool that resolves to nothing is the usual answer.

## Alphas never spawn

**Cause.** Alphas are not part of the readiness gate; they spawn when a player comes within
`alphaTriggerRadius` — 5 blocks by default — of an Alpha spawner block that this dungeon
is allowed to use.

**Fix.** Confirm the spawner's variant number is in `alphaSpawnerIds`, that it is inside
`alphaSpawnRadius` of `spawnPos`, and walk right up to it. Then check `alphaPool`: an
unknown species is logged once and skipped, and the stock default `"ghastly"` is a
misspelling of `gastly` that produces no Alpha at all.

## A Guardian cannot be fought, or has no team

**Cause.** Either the entity was not registered with RCTApi, or its team is at zero HP from
a previous fight, or the trainer JSON failed conversion at startup.

**Fix.** *This trainer has already been defeated.* means the team is exhausted — a
Guardian is a one-shot. Nothing happening at all means the entity resolves to no trainer
id: look for `Failed to convert trainer model from file: …` or
`Failed to load custom trainer file: …` in the startup log. Remember that trainer files
load **only** at server start, so a trainer you added or fixed through the dashboard does
nothing until you restart.

## Beating the boss does not finish the run

**Cause.** The reliable completion path is `DeathHandler` matching the `DungeonBoss`
command tag on the boss entity, which is set when the mod spawns it. A Pokémon you spawned
yourself, however it is named, carries no such tag.

**Fix.** Let the mod spawn the boss — through the portal, or by interacting with an entity
named "boss altar" once every objective is complete. If the boss never spawned at all,
check the log for `Boss species not found: <species>`: an unknown species means no boss and
an unfinishable run. Keep `boss.customName` aligned with your pool entries' `customName`
as well; a second, dead code path compares against it.

## A player who disconnected mid-run cannot get back in

**Cause.** By design, within limits. `handleDisconnect` removes the player and, if they
were the last member, tears the instance down. Rejoining works only through
`partyInstances`, which needs the player to be in a party whose leader still has a live
instance.

**Fix.** A solo player cannot rejoin — there is no party entry to find them by. Nor can a
member of a party whose other members have all left. In both cases the run is gone and
nothing was logged. Give them their ticket back by hand if that is your policy; the mod has
no compensation path.

## A player lost everything they picked up in a dungeon

**Cause.** This is the documented behaviour of a failed `CROSS_SERVER` run. `leaveDungeon`
only serialises the full player state when `instance.isBossDefeated()` is true. On a
failure or a voluntary exit, only the return position travels back, and the player is told
*You left or failed the dungeon. Your items and progress were NOT saved.*

**Fix.** Nothing to fix — it is the design. Announce it on your server before someone
discovers it. `SAME_SERVER` dungeons do not behave this way: there the player is simply
teleported, so their inventory is whatever it was.

## The leader is stuck on *Checking dungeon server status...*

**Cause.** `CROSS_SERVER` mode asks the leader's **client** to relay a player-count query
through the proxy. No answer within 60 ticks and the attempt is dropped silently. The usual
reasons are a client without the mod, a missing Velocity plugin, or a `targetServer` name
that does not match the proxy's configuration.

**Fix.** Confirm the client has the mod at the same version. Confirm
`velocity-party-forwarder` is in the proxy's `plugins/` and logged its two
`Registered channel …` lines at proxy startup. Confirm `networking.targetServer` is exactly
the proxy's name for the dungeon server. For a single-server install, set
`teleportMode` to `SAME_SERVER` and the whole question disappears.

## Parties arrive empty on the dungeon server

**Cause.** Party state crosses servers over the BungeeCord `Forward` mechanism and the
custom `cobblemon_dungeons:proxy_forward` channel, and Velocity does not forward either on
its own.

**Fix.** Install the Velocity plugin. Then use the diagnostics:
`/dungeons partydebug on`, `/dungeons partydebug status`, `/dungeons partydebug dump`, and
`/dungeons partydebug ping <server>` to test the round trip. The plugin logs every
forwarded message at INFO, so the proxy log tells you whether anything arrived.

## *SQL is enabled but the connection failed*

**Cause.** `useSql: true` and HikariCP could not build a pool. The stored error is whatever
the driver reported — bad host, bad credentials, missing schema, firewall.

**Fix.** `/dungeons sql test` prints the error. Correct `config.json` and restart; nothing
re-reads that section at runtime. The mod shipped the literal password
`"password"` as a placeholder, so a fresh install with `useSql: true` and an untouched
config will fail on authentication. SQL is only needed for cross-server transfers of large
PCs — turning it off is a valid answer.

## The dungeons module is missing from the dashboard

**Cause.** Either CheeseDash did not discover the entrypoint, or the module has no server
yet.

**Fix.** A `503` with `{"error":"CobblemonDungeons not ready"}` means the module registered
but `onServerStart` had not run — retry after the server finishes starting. No module at
all means CheeseDash never found the `cheesedash` entrypoint: check that both jars are in
`mods/` and that the one you deployed is this mod's jar and not a stale copy with a
different version in its name.

## A READ_ONLY dashboard account is changing things

**Cause.** Not a bug you can configure away. `DungeonsApi` never calls `requireWrite` or
`requireAdmin`, so every one of its mutating routes accepts any authenticated session. The
detail, and the one-line fix for whoever takes it on, is in
[the dashboard page](dashboard.md).

**Fix.** Until the code changes, do not issue `READ_ONLY` sessions to anyone who should not
be able to delete a dungeon config.

## Alphas or the boss are the wrong size

**Cause.** Scale is applied through the `scale:<n>` aspect and a `CustomScale` float in the
Pokémon's persistent data, and enforced by `PokemonEntityMixin` on every dimension
recalculation. It also pins `GENERIC_SCALE` to 1.0 so the two do not multiply.

**Fix.** Normal-sized bosses almost always mean the mixin is not applying, which on a
Cobblemon update means the API moved — look for
`BattleHandler failed to init (Cobblemon API version mismatch?)` in the log as a sign that
Cobblemon's API has shifted under the mod. The client needs the mod too: a vanilla client
sees vanilla sizes.

## Writing a useful bug report

Attach:

1. **`logs/latest.log`** in full, from the run that reproduced it. Not an excerpt — the
   startup lines matter as much as the failure.
2. The **version lines**: the mod jar's file name, the Fabric Loader version, and the
   Cobblemon and RCTApi versions, all of which are in the loader's mod list at the top of
   the log.
3. The **`Cobblemon Dungeons server role:`** line, and for a cross-server problem, the
   proxy's log too.
4. The **dungeon config** you were running, as a file. Redact nothing — there are no
   secrets in it.
5. For a dashboard problem: the request, the response body and the HTTP status, plus the
   **role** of the account you used.
6. What you expected and what happened, in that order. "The party was never teleported out
   of the hub" is actionable; "dungeons are broken" is not.

Specific log lines worth grepping for before you file anything:

```text
Registered dungeon from file:
Registered custom trainer from file:
Cobblemon Dungeons server role:
Failed to load dungeon file:
Failed to convert trainer model from file:
Boss species not found:
Unknown loot item ID or ticket name:
BattleHandler failed to init
```

Bugs go to [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues),
questions to the [Arkensia Discord](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.md) · [CobblemonDungeons](../README.md) →
