[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

CheeseGuard is the largest mod in this repository: 140 source files and about 35 000
lines. It started as a region protection mod and accumulated an event engine, a dimension
manager, a minigame framework, a prison, a race timer and a land sale system. Nothing was
ever split out. Read this page before changing anything in it.

**Minecraft 1.21.1 · Fabric · Java 21 · 140 source files, ~35 100 lines**

## Package layout

```text
com.cheeseguard
├── CheeseGuardMod.java        442 lines — the ModInitializer; owns every manager
├── api/                         3 files,    225 lines — the one public extension point
├── client/                      4 files,    739 lines — client entrypoint, HUDs, zone-owner screen
├── commands/                    2 files,  1 441 lines — the /cg and /prison Brigadier trees
├── dashboard/                  10 files,  4 227 lines — the CheeseDash module and eight REST APIs
├── data/                       25 files,  4 965 lines — the model: zones, flags, events, configs, storage
├── logic/                      43 files, 21 093 lines — every manager, listener and third-party hook
├── mixin/                      46 files,  1 810 lines — 44 registered mixins, 2 dead files
└── network/                     6 files,    178 lines — custom payloads for the client HUDs and menu
```

`logic/` holds 60% of the code and is where the complexity lives. Its four largest files
are worth knowing by name:

| File | Lines | What it owns |
|---|---|---|
| `MiniGameManager` | 5 244 | every minigame mode — prop hunt, spleef, fishing, hot potato scoring, team handling |
| `EventManager` | 2 892 | the event lifecycle, participants, scores, scheduled tasks, launch rate limits |
| `DimensionManager` | 2 570 | runtime dimension creation, per-player worlds, snapshots, borders, entry rules |
| `TimelineExecutor` | 1 312 | the act/effect timeline language events are written in |

A 5 000-line class with a dozen responsibilities is not a design, it is an accumulation.
Say so out loud when you touch it.

## Key classes

### `CheeseGuardMod`

The single entrypoint and the service locator. `CheeseGuardMod.INSTANCE` is a public
static field, and every other class reaches its managers through
`CheeseGuardMod.getInstance().getXxx()`. There is no dependency injection and no
interface between a manager and its callers.

It constructs, in `onInitialize`: `RegionStorage`, `RegionManager`, `SelectionManager`,
`VisualizationManager`, `ChunkSetStorage`, `ChunkSuggestionStorage`, `TagConfigStorage`,
`ServerManager`, `EventManager`, `DimensionManager`, `ZoneStateManager`,
`PropHuntIconStorage`, `PastureLimitsManager`, `PrisonManager` and
`PrisonDoppelgangerManager`. It registers the two command trees, three listeners
(`ProtectionListener`, `WandListener`, `PrisonListener`), two more handlers
(`HotPotatoHandler`, `EventLeaveHandler`), six network payload types, and nine Fabric
lifecycle callbacks.

Managers created as fields are available immediately; the ones that need a server
(`EventManager.init`, `DimensionManager.init`, `ZoneStateManager.init`) only come up at
`SERVER_STARTED`. That gap is why several handlers start with a null check on their
manager and return quietly — a request that arrives during startup is dropped rather than
queued.

### `RegionStorage` and `Region`

`Region` is the zone: a box, a world id, a priority, a group id, a flag map, a member
list, and an optional `SaleConfig`. A flag absent from the map means *inherit*, which is
distinct from `false`; `Flag` carries the per-flag default and a `memberExempt` bit
deciding whether owners bypass it.

`RegionStorage` holds every zone and group in two maps and persists both to
`config/cheeseguard/regions.json`. Every mutation calls `save()` synchronously: adding one
member rewrites the whole file on the server thread.

### `ProtectionListener`

The hot path. Every mixin that enforces a flag asks this class whether an action is
allowed at a position, and it resolves zone → group → flag default by priority. At 795
lines it is the one piece of `logic/` that runs thousands of times per tick, which is why
its lookups are kept simple.

### `EventManager` and `TimelineExecutor`

An event is a `ServerEvent` with a state out of `IDLE`, `ANNOUNCED`, `QUEUE`, `COUNTDOWN`,
`ACTIVE`, `ENDING`, `CLEANUP`. There is **no `PAUSED` state**, and no pause or resume
anywhere in the mod. Do not add one without reading how `EventManager.tick` computes
remaining time from wall-clock timestamps — a pause would need every one of those to be
rebased, and several of them are also persisted.

`TimelineExecutor` runs the acts: each act has a time offset and a list of `EventEffect`s,
some of which call into other mods (CobbleShift transforms, CheeseKart starts, dungeon
opens).

### `DimensionManager`

Creates and deletes dimensions at runtime, which is the single most dangerous thing this
mod does. Per-player worlds get the id `<masterDimId>_<uuid8>` — the master's config id,
an underscore, and the first eight hex characters of the owner's UUID. Deleting one
unloads the world and removes its directory; on Windows the file handles are not released
immediately, so the code waits before deleting and can still fail.

It is also the only consumer of the access widener: vanilla chains the Overworld's world
border to the Nether and the End through a `WorldBorderSyncer` listener, which makes
independent per-dimension borders impossible. The widener opens
`WorldBorder.getListeners()` so that chain can be stripped at boot.

## Threading

Three threads touch this mod's state, and the hand-offs are explicit.

**The server thread** runs everything in `logic/`: the tick handlers, the listeners, the
command executors, every block and entity mutation. Commands are already on it, so command
handlers mutate directly.

**CheeseDash's HTTP threads** run `dashboard/*Api.java`. Minecraft state must not be
touched from there, so each API hands work over with
`WebDashboard.executeOnServerThread(server, () -> …)`. The current counts are 18 hand-offs
in `ZonesApi`, 13 each in `DimensionsApi` and `EventsApi`, 7 in `ChunksApi`, 5 in
`GroupsApi`, 4 in `PlayersApi`, 2 in `PrisonApi` and **none in `RacesApi`** — that last
one reads and writes only its own JSON files, which is defensible, but it means a race
config write races with the race scanner on the server thread.

Reads of immutable or concurrent structures happen straight from the HTTP thread.
`PrisonManager` uses a `ConcurrentHashMap` and a synchronized list for exactly this
reason; `RegionStorage` uses plain maps and does not, so a dashboard read of the zone list
while a player creates a zone is technically a data race. It has not bitten in practice
because zone creation is rare.

**Netty threads** deliver the custom payloads. Every receiver in `CheeseGuardMod`
immediately wraps its body in `context.server().execute(...)`. The disconnect handler goes
further: it snapshots the `GameProfile` on the netty thread and defers doppelgänger
creation to the server thread, because constructing a `ServerPlayerEntity` and
broadcasting packets off-thread proved unreliable.

## Persistence

Fourteen files and eight directories, all under `config/`, each written by its own
manager. [Configuration](configuration.md) lists every one with its keys and its owner.

Three patterns, and the differences matter:

- **Write-on-mutation.** `RegionStorage`, `PrisonManager`, `PastureLimitsManager`,
  `ChunkSetStorage` rewrite their whole file inside the setter. Durable, and a hitch on
  large data.
- **Write-on-demand.** `EventManager.save()` and `DimensionManager.save()` are called by
  their callers. Miss a call and the change survives until the next restart, then
  vanishes.
- **Write-on-event.** The inventory archives and pending-inventory snapshots are written
  when an event clears an inventory, which is what makes
  [`/cg restoreinv`](commands-operations.md#cg-restoreinv) possible.

Nothing is written atomically. Every save is a `Files.writeString` straight onto the live
path, with no temporary file and no rename. A crash during a save of `regions.json`
truncates it, and the next start loads zero zones — `load()` returns quietly when parsing
fails. Back up `config/cheeseguard/` and `config/cheeseguard_*.json` with your world.

## The 44 mixins

`cheeseguard.mixins.json` registers 40 server-side and 4 client-side mixins. Two further
files exist in `mixin/` and are **not** registered: `StructureGenerationMixin`, now an
empty class whose comment says the logic moved, and `StructurePieceMixin`, which is live
code that nothing loads. Delete the first; decide about the second.

Grouped by what they hook, with the risk each group carries:

### Vanilla block and entity behaviour — 23 mixins

`BlockPlaceMixin` (`BlockItem.place`), `ItemDropMixin`, `ItemPickupMixin`,
`EntitySpawnMixin` (`ServerWorld.spawnEntity`), `MobSpawnMixin` (`MobEntity.canSpawn`),
`ExplosionMixin`, `FallDamageMixin`, `HungerMixin`, `FarmlandTrampleMixin`,
`FireSpreadMixin`, `LeavesDecayMixin`, `IceMeltMixin`, `SnowFormMixin`, `VineGrowMixin`,
`CoralDeathMixin` (four coral block classes at once), `RedstoneMixin`,
`EndermanGriefMixin`, `EatGrassGoalMixin`, `CreativeInventoryMixin`,
`CobblemonBlockInteractMixin` and `AbstractFireBlockCustomDimMixin`.

Each one asks `ProtectionListener` whether a flag allows the action and cancels at `HEAD`
if not. This is the flag enforcement layer, and it is the group most likely to conflict
with another protection or world-management mod hooking the same methods — two mods both
cancelling `BlockItem.place` is a load-order lottery. `FireSpreadMixin` and
`EventProjectileHitMixin` carry `require = 0`, so they fail silently if their target
method is not found; the rest will crash the game on a mapping change instead, which is
the better behaviour for a protection mod.

### Event scoring — 6 mixins

`EventBlockBreakMixin`, `EventBlockPlaceScoreMixin`, `EventCraftItemMixin`,
`EventFishCatchMixin`, `EventItemPickupScoreMixin`, `EventMobKillMixin`,
`EventPlayerDeathMixin`, `EventProjectileHitMixin`. These inject at `RETURN` rather than
`HEAD` and award event points. Two of them (`EventBlockPlaceScoreMixin`,
`EventItemPickupScoreMixin`) set `priority = 1100` so they run after the protection mixin
on the same method — the point is scored only for an action that was actually allowed.
That ordering is load-bearing and undocumented in the code itself.

### Dimension and world plumbing — 7 mixins

`NetherPortalReturnMixin` and `EndPortalReturnMixin` (`createTeleportTarget`) send a
player back to the dimension they came from instead of the Overworld, which is what makes
a Nether inside a custom dimension work. `WorldSeedMixin` (`ServerWorld.getSeed`) returns
a per-dimension seed. `WorldBorderNoSyncerMixin` (`WorldBorder.addListener`) refuses the
vanilla syncer so borders stay independent. `StructureStartMixin` and the unregistered
`StructurePieceMixin` filter structure generation. `ChunkRegionAccessor` and
`MinecraftServerAccessor` are plain accessors — `MinecraftServerAccessor` exposes the
mutable `worlds` map, which is how dimensions are registered at runtime.

Anything in this group breaks on a Minecraft version bump before anything else does. The
two portal mixins and the `worlds` accessor in particular touch internals Mojang has
changed repeatedly.

### Command interception — 2 mixins

Both target `CommandManager.execute` at `HEAD`. `HomeCommandMixin` cancels any command
starting with `home` for a non-operator standing in a dimension whose config has
`allowHome` off. `PrisonCommandMixin` cancels everything a prisoner types except a small
whitelist.

Matching on the raw command string is crude: `/homeland` also starts with `home`. And two
mixins on the same method, both cancelling, is a conflict waiting to be found. If a
command mysteriously does nothing for one player and works for another, look here first.

### Third-party mods — 6 mixins

This is the fragile group. Each one targets a class in another mod by its fully-qualified
name with `remap = false`, and `@Pseudo` where the mod may be absent.

| Mixin | Target mod | What it does | What breaks on their update |
|---|---|---|---|
| `JadeTooltipHideMixin` | Jade — `snownee.jade.overlay.OverlayRenderer` | hides the block tooltip inside a `minimap-hide` zone | a renamed or repackaged renderer class |
| `JourneyMapMinimapHideMixin` | JourneyMap — `journeymap.client.ui.minimap.MiniMap` | hides the minimap inside a `minimap-hide` zone | note this one has **no** `remap = false`, unlike its siblings |
| `CobblemonNametagHideMixin` | Cobblemon — `…client.render.pokemon.PokemonRenderer` | hides Pokémon nametags in a `nametag-hide` zone | Cobblemon reorganises its client renderers often |
| `NametagHideMixin` | vanilla `EntityRenderer` | the same for non-Pokémon entities | low risk |
| `CSAFuzzyMatchFixMixin` | Cobblemon Spawn Alerts — `io.github.stainlessstasis.alert.AlertHandler` | redirects a `String.contains` to `equalsIgnoreCase` in `getConfigForPokemon` | an `@Redirect` on a specific call site: any edit to that method silently stops matching, or crashes |
| `PlayerListEntryNullSafeMixin` | vanilla `PlayerListS2CPacket.Entry` | null-safety for a crash seen with modded player list entries | low risk |

`CSAFuzzyMatchFixMixin` deserves a word, because it is the clearest example of what this
group costs. It is not a CheeseGuard feature at all: it fixes a bug in someone else's mod,
where a substring match made a Pokémon named *Tic* in French fire the alert configured for
*Articuno*. The fix is correct and the comment documenting it is excellent. It is still a
`@Redirect` into a third-party method, inspected at version 1.9.2, that nobody will think
to re-check when that mod updates.

Waystones, by contrast, is reached through **reflection** in `WaystonesHook` rather than a
mixin — the right call for an optional integration, and the pattern to copy if you need
another one. `CobblemonHook`, `CobbleShiftHook`, `CheeseKartHook`, `CheeseStoreHook`,
`CobblemonDungeonsHook`, `NumismaticHook` and `FtbQuestHelper` all do the same.

## Dashboard integration

`CheeseGuardDashboardModule` is declared as a `cheesedash` entrypoint in
`fabric.mod.json`, registers the prefix `cheeseguard`, and forwards everything to
`CheeseGuardApiRouter`. The router dispatches on a path prefix to eight APIs: `/zones`,
`/groups`, `/dimensions`, `/events`, `/players`, `/chunks`, `/races`, `/prison`. An
unmatched path gets a 404; a request arriving before `onServerStart` gets a 503.

Only one WebSocket channel is published from this mod: `PrisonListener` calls
`DashboardRegistry.broadcast("prison", …)`. Everything else the dashboard shows for
CheeseGuard is polled over REST. The routes are documented in [dashboard](dashboard.md).

## Extension points

- **`api/GenTypeRegistry` and `GenTypeProvider`** — the one designed extension point.
  Another mod can register a world generation type that CheeseGuard's dimension creator
  will offer. `GenTypeRegistry.discover()` runs in `onInitialize`.
- **`Flag`** — adding a flag means adding an enum constant, a mixin or listener hook that
  consults it, and a row in the dashboard's flag grid. The enum's `displayName` strings
  are **French, hardcoded**, and are not language keys.
- **`EventEffect`** — new effect types are the intended way to make an event do something
  new, including driving another mod. The existing cross-mod effects (CobbleShift,
  CheeseKart, dungeons) are the models.
- **A third-party integration** — write a `*Hook` in `logic/` using reflection and
  `FabricLoader.isModLoaded`. Do not add a mixin unless reflection genuinely cannot do it.

## Known weaknesses

Stated plainly, because they are the things that will cost you an afternoon.

**The dashboard APIs delegate all of their authorisation to the core.** CheeseDash provides
`WebDashboard.requireWrite(exchange)` and `requireAdmin(exchange)`, and `ZonesApi`,
`DimensionsApi`, `EventsApi`, `GroupsApi`, `PlayersApi` and `ChunksApi` **never call either
one** — 65 mutating routes with no role check inside the module. Only `PrisonApi` and
`RacesApi` guard themselves:

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

The boundary nonetheless holds, because CheeseDash's module dispatcher in `DashboardApi`
applies `requireWrite` to every non-`GET` request before any module sees it. A `READ_ONLY`
session can read and nothing else, on every route of this mod. What does not hold is the
reason it holds — the protection is a property of the core's dispatch, keyed purely on the
HTTP method, and the module contributes nothing. Three consequences:

- **No CheeseGuard route can require `SUPER_ADMIN`.** `requireWrite` is the only bar the
  dispatcher knows, so resetting a dimension from a snapshot, deleting region files from the
  world save and editing a flag all sit at `OPERATOR`. A route that deserves a higher bar has
  to call `requireAdmin` itself.
- **A mutating route exposed as a `GET` would be unguarded entirely.** None exists today in
  these six APIs, but nothing in the module would stop one from being added, and nothing
  would flag it.
- **One change to that dispatcher drops all 65 checks at once**, and no module would notice.

A local `requireWrite` call, as in `PrisonApi`, is therefore defence in depth: useful, but
redundant — it is not the boundary itself. The guard both answers the request with a 403 and
returns `false`; ignoring its return value — which `RacesApi` did until 29 October 2026 —
sends the 403 and then performs the action anyway. Per-route detail, and the role column that
marks which guarantees come from the core, belong in [dashboard](dashboard.md).

**The command gates were wrong for a long time.** `/cg flag`, `/cg addmember`,
`/cg removemember` and the whole `/cg group` subtree had no `requires` clause at all until
29 October 2026, so any player could call them. They are at level 2 now, like the other
nineteen gated subcommands. The lesson is structural rather than historical: a `requires`
clause is one line that is easy to forget and that nothing tests, and four were missing
for months without anyone noticing. The full table is in
[commands](commands.md#permission-levels).

**Saves are not atomic and are not backed up.** A crash mid-save of `regions.json` loses
every zone, silently, because the loader swallows parse errors.

**`MiniGameManager` is 5 244 lines.** Prop hunt, spleef, fishing and the team logic share
one class and one set of fields. There is no test for any of it.

**Two commands lie about success.** `/cg removemember` and `/cg group delete` report
success unconditionally.

**The console cannot run most of `/cg`.** Handlers call `getPlayerOrThrow()` and return
`0` on failure with no message, so a console invocation looks like a no-op.

**Hardcoded French in English-facing places.** The `Flag` enum's display names, the
dashboard module's `getNavDescription()`, the prison welcome message default, and the
literal `aucun` printed by `/cg info` when a zone has no group.

**`logic/` has no boundaries.** Everything reaches everything through the static
`CheeseGuardMod.INSTANCE`. There is no seam to test against, which is why there are no
tests.

**It targets six third-party mods by class name.** Jade, JourneyMap, Cobblemon, Cobblemon
Spawn Alerts. Each of those updating can break a CheeseGuard feature or crash the game at
load. If the server will not start after a mod update, suspect this list first and check
the mixin apply failures in the log.

---

← [Dashboard API](dashboard.md) · [Troubleshooting](troubleshooting.md) →
