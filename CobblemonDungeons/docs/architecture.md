[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

45 Java files under `src/main/java`, about 12 000 lines, plus one more file in the
`velocity-party-forwarder/` subproject. Two files hold more than 45% of that total, which
tells you most of what you need to know about where the complexity sits.

---

## The package name

The root package is `com.sebastian.cobblemondungeons`, the Gradle group is
`com.sebastian`, and the mod id is `cobblemon_dungeons`. That naming is inherited and it
has **deliberately not been renamed**. Three things would break at once:

- **Mixins.** `cobblemon_dungeons.mixins.json` declares
  `"package": "com.sebastian.cobblemondungeons.mixin"`, and every mixin class is resolved
  relative to it.
- **Registry and resource identifiers.** `MOD_ID` is `cobblemon_dungeons`, and every
  block, item, fluid and screen handler is registered under that namespace. Resource and
  datapack paths — `assets/cobblemon_dungeons/…` and the blockstate, model and item files
  they contain — are keyed by it, as are the block ids already placed in live worlds.
- **The CheeseDash entrypoint.** `fabric.mod.json` names
  `com.sebastian.cobblemondungeons.dashboard.CobblemonDungeonsDashboardModule` as a
  fully-qualified class, discovered reflectively at runtime. So does CheeseGuard's
  `CobblemonDungeonsHook`, which looks up
  `com.sebastian.cobblemondungeons.dungeon.DungeonManager`,
  `…item.DungeonTicketItem` and `…config.DungeonTicketConfig$TicketConfig` by
  `Class.forName`.

A rename gains nothing functional and costs a world migration plus a coordinated change in
another mod. Leave it.

One real inconsistency does exist: the language files live in
`assets/cobblemondungeons/lang/` — no underscore — while every other resource is under
`assets/cobblemon_dungeons/`. It works, because Minecraft merges language files from every
namespace and the keys themselves are `cobblemondungeons.*`, but it is a trap when you go
looking for them.

## Package layout

```text
com.sebastian.cobblemondungeons
├── CobblemonDungeons.java          main entrypoint — registries, ticks, events, commands (2439 L)
├── CobblemonDungeonsClient.java    client entrypoint — screens, HUD, rendering, payload replay
├── block/                          7 classes: spawners, stakes, portal, dungeon chest + its block entity
├── config/                         4 classes: ConfigLoader, DungeonConfig, MainConfig, DungeonTicketConfig
├── dashboard/                      CheeseDash module + DungeonsApi
├── dungeon/                        DungeonManager, DungeonInstance, and the three Cobblemon event handlers
├── fluid/                          PoisonWaterFluid (still + flowing)
├── gui/                            screen handler, screen, HUD, and the two GUI openers
├── item/                           DungeonTicketItem
├── mixin/                          3 mixins
├── networking/                     7 CustomPayload records
├── party/                          DungeonsParty, its manager, its commands
└── util/                           DatabaseManager, InteractionHandler, TrainerLoader, the two sync handlers
```

## Key classes

| Class | Lines | What it owns |
|---|---|---|
| `DungeonManager` | 3012 | Everything about a run: the instance maps, countdowns, start and leave, spawning, objectives, rewards, run history, cooldowns, the leaderboard, WebSocket broadcasts |
| `CobblemonDungeons` | 2439 | Registration of every block, item, fluid and screen handler; the per-tick loop; Alpha AI; hazards; the Brigadier trees |
| `DungeonScreenHandler` | 656 | Server-side state of every dungeon screen, including the ticket exchange |
| `DungeonScreen` | 646 | Client rendering of those screens |
| `DungeonInstance` | 589 | One run's mutable state: counters, lives, timer, chest inventories, scan queues |
| `DungeonsApi` | 565 | The 16 REST routes under `/api/v1/dungeons` |
| `PlayerSyncHandler` | 529 | Serialising and restoring player state across servers |
| `PartySyncHandler` | 401 | Cobblemon party state across servers, plus the debug tooling |
| `InteractionHandler` | 297 | Entity and block interactions: chests, loot generation, stakes, keys, gems, the boss altar |
| `ConfigLoader` | 250 | Reading and writing every JSON file |
| `TrainerLoader` | 135 | Registering trainers with RCTApi at server start |

`DungeonManager` is entirely static — static maps, static methods, no instances. That is
why a `MinecraftServer` has to be threaded through almost every call, and why there is no
natural seam for tests.

## State and when it is written

| State | Where it lives | Persisted |
|---|---|---|
| Dungeon configs | `DungeonManager.dungeons`, a `ConcurrentHashMap` keyed by id | `dungeons/<id>.json`, written on dashboard create and update |
| Live instances | `activeInstances` (per player), `partyInstances` (per party), `bossToInstance` (per boss) | Never. They die with the server |
| Countdowns, pending syncs, transfers and starts | Four more static maps | Never |
| Block positions | Per-world `Long2IntOpenHashMap` registries plus a per-chunk index, populated at chunk load | Never — rebuilt from the world on every load |
| Ticket templates | `ConfigLoader.ticketConfig` | `dungeon_tickets.json`, on dashboard update |
| Run history | `CopyOnWriteArrayList`, capped at 1000 | `run_history.json`, rewritten in full at the end of every run |
| Cooldowns | Nested `ConcurrentHashMap` | `cooldowns.json`, rewritten in full on every successful run |
| Leaderboard | Nested `ConcurrentHashMap` | `leaderboard.json`, rewritten in full on every successful run |
| Player transfer blobs | The `<prefix>player_sync` table | Only when `database.useSql` is on |

Every JSON write is a full rewrite of the file, not an append, and happens on the thread
that triggered it. For the records files that is the server thread at the end of a run,
which is a blocking write of up to a few hundred kilobytes. At human rates this is
invisible; it is worth knowing before anyone adds a code path that logs a run in a loop.

There is no `world/data` state and no custom `PersistentState`. Nothing the mod stores is
tied to a world save except the blocks and items players hold.

## Threading

**Server thread.** Everything that touches the world. `tickFastScans`,
`tickPendingSyncs`, `tickPendingTransfers` and `tickPendingStarts` run on every
`START_SERVER_TICK`; `tickDungeons` — countdowns, the timer, the HUD push — runs once every
20 of them. The one-second granularity of the dungeon timer is a consequence of that
divisor, not of a wall clock.

**HTTP threads.** `DungeonsApi` handlers run on CheeseDash's `HttpServer` executor. Reads
go straight at the concurrent maps; every mutation is wrapped in
`WebDashboard.executeOnServerThread(server, …)`. That is consistent across the file: the
create, update, delete, reload, force-fail and force-leave handlers all hand off. The
ticket and trainer writes do not, because they touch only files and the config object,
never the world.

One consequence of the hand-off worth knowing when reading the code: `handleCreateConfig`
schedules the registration and then immediately reads the dungeon back out of the registry
to build its response, so the response depends on the scheduled task having completed.
`executeOnServerThread` is synchronous, which makes it correct — and fragile if that ever
changes.

**Cobblemon event threads.** `BattleHandler`, `DeathHandler` and `CaptureHandler`
subscribe to Cobblemon's Kotlin event bus and run wherever it dispatches, in practice the
server thread. They reach for the server through
`FabricLoader.getInstance().getGameInstance()` rather than being handed one, which is a
smell but works on a dedicated server.

**Netty threads.** Payload receivers are registered with `ServerPlayNetworking`, which
dispatches on the server thread, so nothing special is needed there.

## Mixins

Three, all in `cobblemon_dungeons.mixins.json` with `"required": true` and
`defaultRequire: 1` — a failed injection is a crash, not a warning.

| Mixin | Target | Why |
|---|---|---|
| `PokemonEntityMixin` | `net.minecraft.entity.Entity#calculateDimensions`, at `TAIL` | Forces Cobblemon's scale modifier to the `CustomScale` persistent value — or the `scale:` aspect as a fallback — and pins `GENERIC_SCALE` to 1.0 so the two do not multiply. This is what makes an Alpha or a boss visibly larger without its hitbox drifting |
| `EntityAccessor` | `net.minecraft.entity.Entity` | Accessor and invoker for the private `dimensions` field and `calculateDimensions()`, used by the scale code |
| `HandledScreenMixin` | `HandledScreen#render`, wrapping `drawSlotHighlight` | Client only. Suppresses the vanilla slot highlight on a `DungeonScreen`, which is a custom-drawn screen where the highlight looks wrong |

Note the class name: `PokemonEntityMixin` targets `Entity`, not `PokemonEntity`, and
checks the type at runtime. It was presumably renamed in intent and not in name.

All three target vanilla classes, which is deliberate — no mixin in this mod touches
Cobblemon or RCTApi bytecode. The coupling to those mods is through their public APIs and,
in `BattleHandler`, through aspect strings and command tags.

## Networking payloads

Seven `CustomPayload` records under `networking/`, all in the `cobblemon_dungeons`
namespace:

| Payload | Direction | Carries |
|---|---|---|
| `DungeonJoinPayload` | both ways | Dungeon id, party id, party size, an NBT blob of player state, tier. The client holds it across a server switch and replays it on join |
| `DungeonHudPayload` | S2C | The four objective counters with their targets, time left, lives, a visibility flag |
| `DungeonTicketSyncPayload` | S2C | The whole ticket config as JSON, sent on join |
| `BungeePayload` | both ways | BungeeCord-format plugin messages, including the player-count query |
| `ProxyForwardPayload` | S2C | The custom forward format the Velocity plugin understands |
| `PartySyncPayload` | both ways | Serialised Cobblemon party state |
| `PartyDebugPayload` | both ways | The `partydebug ping` round trip |

There is no protocol version field anywhere. A client and server on different mod versions
will produce codec errors rather than a readable warning.

## Extension points

If you are adding to this mod, these are the seams that exist:

- **A new config field.** Add it to `DungeonConfig` with an initialiser. Gson fills absent
  keys from the initialiser, so old files keep working and the dashboard picks the field up
  for free — `buildConfigDetail` just serialises the object.
- **A new objective.** The pattern is: a block variant registered in a numbered family, a
  per-world registry, a counter and a target on `DungeonInstance`, a `handleXFound` on
  `DungeonManager` that broadcasts and calls `checkPortalActivation`, and an extra term in
  `DungeonInstance.canAccessBoss()`. Follow Stakes — they are the smallest of the four.
- **A new REST route.** One more `if (route.equals(…) && method.equals(…))` at the top of
  `DungeonsApi.handle`. Put it before the `/configs/` prefix branch, which is greedy.
- **A new WebSocket message.** `broadcastDungeonEvent(type, instance, server)` with a new
  type string. The payload shape is fixed for every type.
- **A new block variant family.** Follow `registerAlphaSpawner(int, Block, Item)` and its
  `*_ID_MIN` / `*_ID_MAX` constants, and remember the blockstate and model JSON per
  variant. The counts today are 20 Alpha spawners and 10 each of Guardian spawner, Stake
  spawner, Portal and chest.
- **Driving the mod from another mod.** Copy CheeseGuard's `CobblemonDungeonsHook`: look up
  `DungeonManager` by name and call its static methods reflectively. The useful entry
  points are `startDungeon`, `getDungeonConfig` and `DungeonTicketItem.createStack`.

## Known weaknesses and deliberate trade-offs

**Two files carry everything.** `DungeonManager` at 3012 lines and
`CobblemonDungeons` at 2439 are the whole mod in practice. Alpha AI, type charts, hazard
ticks, registration and the command tree all live in the entrypoint class, and the
instance lifecycle, spawning, rewards and three persistence subsystems all live in the
manager. Any change to either means reading a lot of unrelated code first.

**Instances are memory-only, so a restart loses every run.** There is no serialisation of
`DungeonInstance`. A server stop or crash mid-run leaves the party wherever they were
standing, with no instance, no HUD and no exit beyond `/dungeons leave`, which will tell
them they are not in a dungeon. On a cross-server setup they may also be on the wrong
server with unsaved inventories. This is the single largest gap.

**Disconnecting is an unlogged failure.** `handleDisconnect` drops the player silently:
nothing is logged, no cooldown is recorded, the leaderboard is untouched, and a solo player
cannot rejoin because the instance is torn down with them.

**Guardian detection is partly name-based.** `BattleHandler` treats any NPC whose name
contains "guardian" as a dungeon Guardian. Entity cleanup on a main-role server is the
same kind of heuristic, matching `key`, `gem`, `stake` and `boss` in entity names with a
"dungeon master" exception. Both will mis-fire on an unrelated entity that happens to be
named unluckily.

**Interactions are driven by entity names.** Keys, gems, stakes, the boss altar and the
Dungeon Master NPC are all identified by substring matches on a custom name in
`InteractionHandler`. It is why the system is so easy to author with a datapack and a few
named armour stands, and why it is so easy to break by renaming something.

**Dead code in the boss-victory path.** `BattleHandler` compares a battle loser's nickname
against `boss.customName` to detect a boss kill, but a boss carries the `alpha` aspect and
`BattleHandler` cancels any battle containing an Alpha, so that branch cannot run. The live
path is `DeathHandler`'s `DungeonBoss` command tag.

**`minLevel` is not implemented.** `requirements.minLevel` exists in the data class and is
read by nothing.

**`DUNGEON_MODIFIER` is not implemented.** CheeseGuard's third dungeon effect logs and
announces, and its own comment says integration with `DungeonManager` is future work.

**The dashboard enforces no roles.** `DungeonsApi` never calls `requireWrite` or
`requireAdmin`, so eight mutating routes are open to any authenticated session including
`READ_ONLY`. Documented in full in [the dashboard page](dashboard.md).

**`deathCount` in run history is derived and wrong for parties that lost members**, because
the starting life total is recomputed from the player count at logging time rather than the
one captured at construction.

**Error handling swallows.** `consumeDungeonTicket` and `hasDungeonTicket` wrap their whole
body in `catch (Exception) {}`; `broadcastDungeonEvent` does the same; a malformed ticket
JSON `PUT` is logged and answered `200`. A failure in any of these is invisible from the
outside.

**Coupling to third-party internals.** `BattleHandler` keys off the aspect string `alpha`
and Cobblemon's actor and battle-Pokémon APIs; `TrainerLoader` drives RCTApi's model
converters and Cobblemon's `NPCClasses`, `NPCEntity` and `NPCPlayerModelType`;
`PokemonEntityMixin` reaches for `PokemonEntity.getSCALE_MODIFIER()`. All of that is
public API, and all of it is version-sensitive: these subsystems will break on a Cobblemon
or RCT major update, and the breakage will look like Guardians that do not count or bosses
that spawn at normal size rather than like a crash. `BattleHandler.init` is already wrapped
in a `catch (NoClassDefFoundError | Exception)` that logs
`BattleHandler failed to init (Cobblemon API version mismatch?)` and lets the mod continue
without battle integration, which is a reasonable degradation and a warning you should
never ignore.

**The Velocity plugin logs at INFO, loudly.** Every forwarded message produces several log
lines. That is deliberate for commissioning a network and wants turning down afterwards,
which currently means editing the source.

---

← [Dashboard integration](dashboard.md) · [Troubleshooting](troubleshooting.md) →
