[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](instances.fr.md)

# Instances and their lifecycle

A `DungeonConfig` is a template. A `DungeonInstance` is one party's run through it: its
own timer, its own shared lives, its own objective counters, its own boss, its own chest
inventories. Nothing about an instance is written to disk while it runs — it lives in
`DungeonManager`'s maps and dies with the server.

Three maps hold the state:

- `activeInstances`, keyed by **player** UUID. Every member of a party points at the same
  instance object.
- `partyInstances`, keyed by the party leader's UUID, used to let a reconnecting member
  find their party's run again.
- `bossToInstance`, keyed by the boss entity's UUID, so a battle victory can be traced
  back to the run that spawned the boss.

---

## Starting a run

`DungeonManager.startDungeon(leader, dungeonId[, tier])` is the single entry point. The
GUI calls it, `/dungeons` leads to it through the selection screen, and CheeseGuard's
`DUNGEON_OPEN` effect calls it by reflection. There is no command that starts a dungeon
directly.

In `CROSS_SERVER` mode the first thing that happens is a liveness check. The leader is
sent a `BungeePayload.playerCount(targetServer)`, their client relays it to the proxy, and
the answer comes back as a plugin message. Meanwhile a `PendingStart` sits in a map with
a 60-tick budget. The leader sees *Checking dungeon server status...*. A second attempt
while one is pending re-prints that message and is otherwise ignored. If no answer arrives
within the 60 ticks, `tickPendingStarts` drops the attempt and tells the leader *Dungeon
server is offline. Please try again later.* — the same message as an explicit "offline"
answer.

Once the target server has answered (or immediately, in `SAME_SERVER` mode), the checks
run in this order, and the first failure aborts with a message:

1. **Leadership** — if the leader is in a party but is not its leader: *Only the Party
   leader can start a dungeon!*
2. **Team present** — every online member must have at least one Pokémon: *You need one or
   more pokemon on your team to enter dungeons*
3. **Party size** — more members than `requirements.dungeonsPartySizeLimit`: *Your party
   is too large for this dungeon (Max: N)*
4. **Cooldown** — any member still cooling down: *<player> is still on cooldown for this
   dungeon (Xm Ys remaining)*
5. **Free slot**, `SAME_SERVER` only — no unused location slot: *Dungeons are full! Please
   try again later.*
6. **Ticket** — the leader must hold a matching ticket, and it is consumed here: *You need
   a valid Dungeon Ticket for <name> (Tier N) to enter.*

Only the leader pays a ticket. Party members ride along for free.

A `CountdownData` is then put in `activeCountdowns` for every member, at 5 seconds, and
everyone sees *Dungeon will start in 5 seconds... Prepare your team!* The countdown ticks
once per `tickDungeons` call — every 20 server ticks — and shows *Starting in N...* in the
action bar.

When it reaches zero the `DungeonInstance` is constructed. This is the moment party size
is frozen: lives come from the member count captured in the countdown, not from who is
still online later.

## Preparation, and why there are two teleports

`SAME_SERVER` players are teleported to `hubPos` immediately and told *Preparing dungeon
area... Please wait.* Preparation then runs on the fast-scan tick:

- the per-world block registries are consulted for Alpha spawners, Stake spawners,
  Guardian spawners and Portals within the configured radii
- chunks are scanned on a shared budget of 32 scans per tick across every live instance
- Stakes and Guardian trainers are placed
- `isReadyForPlayers()` becomes true once Stakes and Guardians are spawned and no trainer
  spawn task is still queued

Only then does `tickDungeons` teleport everyone to `spawnPos` with *The dungeon is ready!
Prepare yourself.* Alphas are deliberately excluded from that gate: they spawn when a
player comes within `alphaTriggerRadius` of a spawner, so waiting for them would mean
waiting forever.

The HUD is pushed as a `DungeonHudPayload` on every `tickDungeons` pass, carrying the four
objective counters with their targets, the remaining time and the remaining lives.

## Objectives and unlocking the boss

`canAccessBoss()` is a conjunction: every key in `requirements.requiredKeyItems` collected,
and all four counters at or above their requirement. Nothing partial counts.

When it first becomes true, `checkPortalActivation` flips every Portal block in the
instance to its visible state and announces *The portal has been activated! All objectives
completed.* Before that the portal is invisible and right-clicking it does nothing. A
visible portal right-clicked by someone who is not in a run answers *You must be in an
active dungeon run to use this portal.*, and by someone whose own run has not unlocked the
boss, *You must complete all dungeon objectives before you can fight the boss!* Walking
into a portal does nothing at all.

Right-clicking a visible portal opens the boss prompt. Accepting it spawns the boss at
`bossSpawnPos` — or the active slot's override — through `spawnBoss`, then teleports every
online member to `bossRoomPos` of the **first** location slot and marks each as a boss-room
visitor, which matters for respawns.

## Dying, wiping, and running out of time

Three things cost a life, and all three share one counter for the whole party:

| Event | Handler | Message |
|---|---|---|
| A player dies | `handlePlayerDeath` | *A player has died! N lives remaining for the party.* |
| A player's whole Pokémon team faints | `handlePokemonTeamWipe` | *Your whole team has fainted! N lives remaining for the party.* plus *<player>'s team has fainted! N lives remaining.* to the others |
| Lives reach zero | `failDungeon` | *Party lives depleted! The dungeon has failed.* |
| The timer reaches zero | `failDungeon` | *Time is up! The dungeon has failed.* |

A team wipe with lives to spare heals the player's party and — on a dungeon-role server
only — teleports them back: to `bossRoomPos` if the boss is unlocked **and** they have been
in the boss room before, otherwise to `spawnPos` (both taken from the first location slot).
On a main-role server, `SAME_SERVER` mode included, the player stays where they are. A
respawn after death follows the same rule on any server, using the active slot. The
"has visited the boss room" condition is what stops a late joiner from being dropped into
a boss fight they never walked into.

Both failure paths log the run as unsuccessful, broadcast `dungeon_fail` to the dashboard,
clear the dungeon's entities, and then call `leaveDungeon` for every member still online.
Players who are offline at that moment are simply dropped from `activeInstances`.

## Completing a run

`handleBossDefeated` runs once per instance, guarded by the `bossDefeated` flag:

1. the boss is unmapped from `bossToInstance` and the instance is marked complete
2. the run is appended to the history and `dungeon_complete` goes out over the WebSocket
3. a cooldown is recorded for **every** member, and the leaderboard is updated
4. each member rolls `rewardChance` for the boss Pokémon itself, delivered to their PC
   (*<boss customName> has been transferred to your PC!* — the Pokémon carries that
   nickname), and has their party healed
5. *Congratulations! The dungeon boss has been defeated!* and *The party wins! You will be
   teleported back in 10 seconds.*
6. completion rewards are distributed
7. `finishDelayTicks` is set to 10

That delay is counted in `tickDungeons` passes, so ten of them — ten seconds at normal
tick rate, which is what the chat message promises. When it expires every member is run
through `leaveDungeon`.

## Leaving

`leaveDungeon` is called by `/dungeons leave`, by the finish delay, by `failDungeon`, and
by the dashboard's force-leave. It always hides the HUD, then branches on `teleportMode`:

- `SAME_SERVER` — teleport to the active slot's `returnPos`.
- `CROSS_SERVER` — send the return transfer (`DungeonJoinPayload`), then transfer them to
  `mainServer` after 40 ticks.

What gets carried back depends on whether the boss died. On a success and with
`syncPlayerData` on, the full player state — inventory, Cobblemon party, PC — is
serialised and, when SQL is enabled, written to the database. On a failure only the return
position travels, and the player is told plainly: *You left or failed the dungeon. Your
items and progress were NOT saved.*

That message depends on `syncPlayerData` alone, not on the mode. In `SAME_SERVER` mode
nothing is serialised or rolled back — the player is simply teleported with everything they
carry — yet with `syncPlayerData: true` (the default) they still read that their items were
not saved. With `syncPlayerData: false` the message is never shown.

⚠️ That is the design, and it is harsh: anything a player picked up inside a
`CROSS_SERVER` dungeon is lost if they fail or walk out. Say so on your server before
anyone finds out the hard way.

The player is removed from `activeInstances`. When the last member leaves,
`clearDungeonEntities` runs and the party mapping is dropped.

A player who was never in a run gets *You are not currently in a dungeon instance.*

## Disconnecting mid-run

`handleDisconnect` is wired to `ServerPlayConnectionEvents.DISCONNECT`. It drops any
pending countdown, removes the player from `activeInstances` and from the instance's
player set, and — if that was the last member — clears the dungeon's entities and the
party mapping.

Nothing is saved. The run is not logged, no cooldown is recorded, the leaderboard is
untouched. For the disconnecting player it is a silent failure.

Coming back is handled by `handleRejoin` on join: if the player is in a party whose leader
still has a live instance in `partyInstances`, they are added back to it and teleported —
to the boss room if the boss is unlocked and they had visited it, to `spawnPos` if the
party has already been teleported in, to `hubPos` otherwise — and the HUD is re-sent.

Two consequences worth knowing:

- A **solo** player cannot rejoin. With no party there is no `partyInstances` entry, and
  the instance was already torn down when they were the last member to drop.
- A player in a party who reconnects **after** the last other member has left also cannot
  rejoin, for the same reason.

## Cleanup

`clearDungeonEntities` resets Stake spawner blocks to "no stake present", hides every
Portal block again, and then sweeps entities in a box around `spawnPos` expanded by
`alphaSpawnRadius + 50`. What it deletes depends on the server role:

- On a **dungeon-role** server every non-player entity in the box is discarded.
- On a **main-role** server the sweep is selective: Alpha and ownerless Pokémon, dropped
  items, and entities whose name contains `key`, `gem`, `stake` or `boss` — excluding
  anything named *dungeon master*.

The selective branch is a string-matching heuristic, and it will take a wrong entity if
you have named something unluckily. Running dungeons on a dedicated dungeon-role server
avoids the question entirely.

## Cross-server runs

`CROSS_SERVER` mode is three moving parts:

1. **Server role.** `CobblemonDungeons.isDungeonServer` reads the system property
   `cobblemon.dungeons.serverRole`, falling back to the environment variable
   `COBBLEMON_DUNGEONS_SERVER_ROLE`. The value `dungeon` (case-insensitive) makes it a
   dungeon server; anything else, including absent, makes it a main server. The result is
   cached on first call and logged as `Cobblemon Dungeons server role: dungeon` or
   `… : main`. Changing it needs a restart.
2. **The join payload.** `DungeonJoinPayload` carries the transfer itself. The client
   relays it across the server switch, which is what makes a transfer atomic without a
   database.
3. **The proxy plugin.** Party state is forwarded between servers over the BungeeCord
   `Forward` mechanism and the custom `cobblemon_dungeons:proxy_forward` channel. Velocity
   does not forward those on its own, hence `velocity-party-forwarder/`. See
   [installation](installation.md).

With SQL enabled the PC is read from the database instead of travelling with the transfer,
because a full PC does not belong in a plugin message.

⚠️ The client→server payloads of `CROSS_SERVER` mode are not validated by the server, and
a modified client can abuse them. Don't use this mode on a public server until this is
fixed.

### Known fragility

The liveness check is a BungeeCord `PlayerCount` plugin message sent through the leader's
connection, so it only gets an answer if the proxy handles that channel and reports the
named server. When no answer comes, the symptom is *Checking dungeon server status...*,
then, three seconds later, *Dungeon server is offline. Please try again later.* even if the
server is in fact up.

---

← [The dungeon definition](dungeons.md) · [Tickets](tickets.md) →
