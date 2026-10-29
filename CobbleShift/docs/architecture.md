[← CobbleShift](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

This page is for whoever maintains CobbleShift next. It describes how the code is laid
out, who owns which state, which thread touches what, and where the design is weak. Line
counts come from `wc -l` on `src/main/` at the time of writing.

---

## Package layout

```text
com.cobbleshift                 server side: entrypoint, config, command, managers, species mapping, projectile
com.cobbleshift.client          client entrypoint, the ghost-model renderer, the HUD
com.cobbleshift.client.mixin    the ten client mixins
com.cobbleshift.network         the four custom payloads
com.cobbleshift.dashboard       the CheeseDash module and its REST handler
```

There is no server-side mixin. Every mixin in `cobbleshift.mixins.json` is declared under
`"client"`, so a dedicated server applies none of them.

## Key classes

| Class | Lines | Owns |
|---|---|---|
| `CobbleShiftMod` | 123 | the `main` entrypoint: payload registration, config load, the three managers, every server event registration, the tick fan-out |
| `CobbleShiftConfig` | 155 | the sixteen config fields, `load()`, `save()`, the clamping setters |
| `CobbleShiftCommand` | 559 | the `/cshift` Brigadier tree, the aspect-token parser, the 5-block entity raycast |
| `TransformManager` | 585 | `activeTransforms`, the revert timestamps for the cooldown, the attribute modifiers, the transform sync broadcast, the animation-state broadcast, stale-modifier cleanup on join |
| `TransformData` | 114 | one player transform: species, types, BST, shiny, form, custom scale and speed, attack cooldown, timestamps |
| `AbilityManager` | 347 | `activeAbilities`, `disabledPlayers`, the refresh loop, the reflective read of the party lead |
| `AbilityType` | 99 | the eighteen type entries, their French display names and their status effects |
| `AttackHandler` | 181 | the server side of left-click attacks: damage, cooldown, the delayed-shot queue, projectile spread |
| `TypeProjectileEntity` | 159 | the `cobbleshift:type_projectile` entity and its on-hit effects |
| `SpeciesMapper` | 462 | every reflective read of Cobblemon's species registry, and the five stat-mapping formulas |
| `NpcTransformManager` | 399 | the NPC transform map, its save file, the NPC speed modifier |
| `client.CobbleShiftClient` | 193 | the `client` entrypoint: keybinds, payload receivers, the third-person lock, the crosshair, the projectile renderer |
| `client.TransformRenderer` | 1595 | all client transform state, ghost `PokemonEntity` creation by reflection, pose, water and animation syncing, cries |
| `client.AbilityHudRenderer` | 219 | the ability HUD and the client-side attack cooldown bar |
| `dashboard.CobbleShiftDashboardModule` | 42 | the `shift` prefix, the 503 guard while the server is not started |
| `dashboard.ShiftApi` | 618 | the fifteen REST routes |

`TransformRenderer` is a quarter of the codebase on its own, and most of it is reflection
into Cobblemon's client classes. It is the file most likely to break on a Cobblemon update.

## State and disk

| State | Where | Persisted | Written when |
|---|---|---|---|
| config | `CobbleShiftConfig` fields | `config/cobbleshift.json` | first start if absent, `/cshift ability global`, `POST /ability/global`, `PUT /config` |
| player transforms | `TransformManager.activeTransforms` | **no** | — |
| transform attribute modifiers | the player's attribute data | yes, by Minecraft, as part of the player file | on transform, removed on revert |
| revert timestamps (cooldown) | `TransformManager.lastRevertTime` | no | — |
| active abilities | `AbilityManager.activeAbilities` | no | — |
| per-player ability opt-out | `AbilityManager.disabledPlayers` | no | — |
| NPC transforms | `NpcTransformManager.npcTransforms` | `config/cobbleshift_npc_transforms.json` | every NPC change, and unconditionally on `SERVER_STOPPING` |
| NPC speed modifier | the NPC's attribute data | yes, by Minecraft, with the entity | on `speednpc`, removed on revert |
| pending delayed shots | `AttackHandler.pendingAttacks` | no | — |
| everything on the client | static maps in `TransformRenderer` | no | — |

The asymmetry between players and NPCs is the central design decision, and it is
deliberate. Player transforms are meant to last an event, so they live in memory and a
clean stop reverts them. NPC transforms are world decoration, so they are saved and a
clean stop keeps them.

The weak point is the half-persisted player transform: the map is lost on a crash while the
modifiers and the infinite Invisibility survive in the player file.
`TransformManager.cleanupStaleTransform()` repairs that on the next join. The full story is
in [the transform model](transforms.md#disconnects-restarts-and-crashes).

The two files are resolved differently. `CobbleShiftConfig` goes through
`FabricLoader.getConfigDir()`. `NpcTransformManager` uses the literal relative path
`config/cobbleshift_npc_transforms.json`, resolved against the working directory. On a
host that launches the server from another directory, the two files end up in different
places. Fixing it means resolving `CONFIG_FILE` against `getConfigDir()` like the config
does.

## Threading

### Server thread

Everything that mutates the game runs here when it comes from the game itself:

- the four tick handlers, fanned out from one `END_SERVER_TICK` registration:
  `TransformManager.tick()`, `AbilityManager.tick()`, `AttackHandler.tick()`,
  `NpcTransformManager.tick()`
- every `/cshift` subcommand
- the `JOIN` handler (re-sync of player and NPC transforms, stale cleanup) and the
  `DISCONNECT` handler, which does nothing
- `SERVER_STARTED` and `SERVER_STOPPING`

The C2S attack receiver reads the payload's single boolean and hands the work to the
server thread with `context.server().execute(...)`, whatever thread Fabric invokes the
receiver on. `AttackHandler.pendingAttacks` is a plain `ArrayList`, which is safe only
because every access goes through that hand-off and the tick.

### HTTP thread

`ShiftApi` runs on CheeseDash's HTTP thread. The hand-off is
`WebDashboard.executeOnServerThread(server, task)`, which queues the task on the server
and **blocks the HTTP thread for up to five seconds** waiting for it. On a timeout the
handler answers with an error, but the task stays queued and still runs later.

| Route | Runs on |
|---|---|
| `POST /transform`, `/revert`, `/revertall` | server thread, through `executeOnServerThread` |
| `POST /ability` (force and clear) | server thread, through `executeOnServerThread` |
| `POST /scale` for a **player** | server thread, through `executeOnServerThread` |
| `POST /ability/global` | config write on the HTTP thread; the clean-up is queued with `server.execute` and not awaited |
| `POST /ability/toggle` | **HTTP thread**: `togglePlayer()` mutates the set and sends a packet from there |
| `PUT /config` | **HTTP thread**: setters and `save()` |
| `POST /scale` for an **NPC** | **HTTP thread**: `setNpcScale()` broadcasts and saves from there |
| `POST /speed` | **HTTP thread**: `setNpcSpeed()` looks the entity up, edits its attribute and saves |
| all `GET` routes | HTTP thread, reading `ConcurrentHashMap`s and calling Cobblemon by reflection |

The rows in bold break CheeseDash's own rule that every Minecraft mutation goes through
`executeOnServerThread`. The collections involved are concurrent, so nothing crashes on a
quiet server, but `setNpcSpeed()` touching an entity's attributes from a foreign thread
and two threads rewriting the NPC save file at once are real races. Wrapping those four
handlers in `executeOnServerThread` is the fix.

Config fields are plain, non-volatile fields written by the HTTP thread and read every
tick by the server thread. In practice the new values show up; the Java memory model does
not promise when.

### Client thread

The three S2C receivers in `CobbleShiftClient` hop to the client thread with
`context.client().execute(...)` before touching `TransformRenderer`. The renderer's maps
are `ConcurrentHashMap`s anyway, and every mixin reads them from the render thread.

## Network payloads

All four payloads travel on the Minecraft game connection, registered with Fabric's
`PayloadTypeRegistry`. None of them uses CheeseDash's WebSocket, and the mod publishes
nothing on that WebSocket.

| Payload | Id | Direction | Fields | Sent when |
|---|---|---|---|---|
| `TransformSyncPayload` | `cobbleshift:transform_sync` | S2C | `playerUuid`, `species`, `type1`, `type2`, `scale`, `silent`, `fromNpc` | transform, revert, rescale, every join; for players and NPCs alike |
| `AnimationStateSyncPayload` | `cobbleshift:anim_state_sync` | S2C | `playerUuid`, `envState` (byte), `isMoving` | every 5 ticks, only on change, to players within 64 blocks in the same dimension, never to the player themselves |
| `AbilitySyncPayload` | `cobbleshift:ability_sync` | S2C | `species`, `type1`, `type2` | every ability change for that one player |
| `AttackAbilityPayload` | `cobbleshift:attack_ability` | C2S | `hasAnimation` | left click while transformed |

Conventions shared by the S2C payloads:

- An **empty `species`** means "nothing": revert for a transform, hide the HUD for an
  ability.
- `species` on `TransformSyncPayload` is **encoded**: `snorlax`, `snorlax|shiny`,
  `lycanroc|shiny|midnight`. The client splits on `|`.
- Null type strings are written as `""` and read back as `null`.
- `envState` codes are `0` ground, `1` surface water, `2` submerged, `3` air, `8` sleeping.

For a transform, the server sends two variants of the same payload: `silent = false` to
players within 32 blocks in the same dimension, who hear the cry and see particles, and
`silent = true` to everybody else, who only get the model. `fromNpc = true` suppresses the
cry and particles unconditionally.

Every send is guarded by `ServerPlayNetworking.canSend()`. A vanilla client never receives
these packets and is never disconnected for lacking them; it just sees an invisible player.

`AttackAbilityPayload.hasAnimation` is trusted as sent. A modified client that always sends
`false` skips the 1.3-second wind-up and fires at once. The cooldown is enforced on the
server, so the gain is timing, not damage per second.

## Mixins

Ten client mixins, all with `require = 0` and `"defaultRequire": 0`. That setting means a
mixin whose target is missing is **skipped silently** instead of crashing the game. It
makes the mod tolerant of other mods that touch the same methods, and it means a broken
injection shows up only as a missing feature, with no error in the log.

| Mixin | Target | Injection | Why it exists |
|---|---|---|---|
| `TransformModelMixin` | `PlayerEntityRenderer.render` | `HEAD`, cancellable | draws the ghost Pokémon instead of a transformed player; hides the local model at zoom 0 |
| `NpcTransformModelMixin` | `EntityRenderDispatcher.render` | `HEAD`, cancellable | same for transformed NPCs; see [NPC transforms](npc-transforms.md#how-the-client-draws-it) |
| `PlayerNametagMixin` | `EntityRenderer.renderLabelIfPresent` | `HEAD`, cancellable | hides the nametag of any transformed UUID, player or NPC |
| `TransformArmMixin` | `PlayerEntityRenderer.renderRightArm` / `renderLeftArm` | `HEAD`, cancellable | no first-person arms on a Pokémon |
| `TransformCameraMixin` | `Camera.update` | `TAIL` | re-centres the third-person orbit on the Pokémon's body (55 % of its height plus 0.55) and applies the scroll zoom |
| `TransformScrollMixin` | `Mouse.onMouseScroll` | `HEAD`, cancellable | turns the scroll wheel into camera zoom while transformed |
| `AttackInputMixin` | `MinecraftClient.doAttack` | `HEAD`, cancellable | turns left click into an `AttackAbilityPayload` |
| `TransformStepSoundMixin` | `Entity.playStepSound` | `HEAD`, cancellable | no player footsteps under a Pokémon |
| `TransformFovMixin` | `AbstractClientPlayerEntity.getFovMultiplier` | `HEAD`, cancellable | stops the sprint FOV change |
| `ShinyChimeMuteMixin` | `SoundManager.play(SoundInstance)` | `HEAD`, cancellable | mutes Cobblemon's shiny chime within 4 blocks of a shiny morph player |

All targets are vanilla Minecraft methods, named in Yarn and remapped by Loom at build
time. None of them targets a Cobblemon class, which keeps the mixins independent of
Cobblemon's version. The Cobblemon coupling lives in reflection instead.

`ShinyChimeMuteMixin` matches by sound id (`cobblemon` namespace, path containing `shiny`
and `chime`) and by distance. A real shiny Pokémon standing within 4 blocks of a shiny
morph is muted too; the class comment accepts that trade-off.

## Reflection and why the method names matter

CobbleShift never imports a Cobblemon class. `build.gradle` has no Cobblemon dependency at
all, and `fabric.mod.json` only `suggests` it. Every access goes through
`Class.forName(...)` and `getMethod(...)`, in `SpeciesMapper`, `AbilityManager` and
`TransformRenderer`. That is why the jar loads on a server without Cobblemon, and why every
species lookup then fails instead of crashing.

There are two kinds of names in those lookups, and they behave very differently in a
production jar.

**Cobblemon's own names are safe to hardcode.** `PokemonSpecies.INSTANCE`, `getByName`,
`getBaseStats`, `getPrimaryType`, `getHitbox`, `getBaseScale`, `PokemonEntity.getDelegate`,
`PokemonProperties.Companion`: these are Cobblemon's Kotlin source names. Cobblemon ships
them unobfuscated and Fabric does not remap another mod's own classes, so the string in
the code matches the method at runtime. What breaks them is a Cobblemon update that renames
or removes a method. The code expects that: most lookups try several candidates in a row
(`getSpecies`, then `getAll`, then `species`; `getParty(ServerPlayerEntity)`, then
`getParty(UUID)`), and each failure falls through to the next.

**Minecraft's names are not.** At runtime on a Fabric server, Minecraft's members carry
intermediary names (`field_5957`, `method_5674`), not Yarn names. A Yarn string passed to
`getDeclaredField` works in the development environment and fails in production.
`TransformRenderer` handles this in two places, with two levels of care:

- `notifyTrackedDataSet()` tries `onTrackedDataSet`, `method_5674` and `m_7350_` by name,
  then falls back to a **signature scan**: the first declared method of `Entity` with one
  `TrackedData` parameter and a `void` return. That fallback is the robust pattern.
- `syncWaterState()` tries Yarn and intermediary names for `touchingWater`,
  `submergedInWater` and `submergedFluidTag`, then falls back to **declaration order**:
  the first two protected, non-static `boolean` fields of `Entity` are assumed to be the
  two water flags, and the first private, non-static `Set` is assumed to be the fluid tag
  set. The code reads the player's value of each field and then ignores it, so nothing
  checks the guess. A Minecraft update that adds a protected boolean before those fields
  makes the ghost read the wrong flag, and water animations go wrong silently. The chosen
  fields are logged once, as `[CobbleShift] Water sync: touchingWater=…`, which is the
  line to check after a Minecraft update.

The rule for a contributor: a Cobblemon name may be written as a string; a Minecraft name
never should be. Use the Yarn symbol directly in normal code, which Loom remaps, and when
reflection is unavoidable, match by parameter and return types.

One cache needs care. `SpeciesMapper.getAllSpeciesNames()` fills `cachedSpeciesNames` on its
first successful call and never refreshes it. If that first call happens before Cobblemon
has loaded its species, the autocompletion list stays empty, or short, until a restart.
Species added by a datapack reload are also missing from completion, though they still
resolve when typed.

## The de facto external API

Two sibling mods drive CobbleShift by reflection, without a compile-time dependency:

| Caller | Reflected members |
|---|---|
| CheeseGuard `logic/CobbleShiftHook` | `CobbleShiftMod.INSTANCE`, `getTransformManager()`, `transform(ServerPlayerEntity, String)`, `transform(ServerPlayerEntity, String, boolean)`, `revert(ServerPlayerEntity)`, `revertAll()` |
| CheeseKart `CobbleShiftProxy` | `CobbleShiftMod.INSTANCE`, `getTransformManager()`, `transform(ServerPlayerEntity, String)`, `revert(ServerPlayerEntity)` |

Those six members are a public API whether or not they were designed as one. Renaming
`getTransformManager`, changing a parameter type or turning `INSTANCE` into a getter
compiles cleanly here and silently disables CheeseGuard's four transform event effects,
its `cobbleshift-transform` zone flag, and CheeseKart's transformed racers. Both callers
log a `not installed` line at info level when the lookup fails, so the only trace is in
their startup logs.

The boolean overload is how CheeseGuard passes `noattacks`. The shiny-and-form overload,
`transform(ServerPlayerEntity, String, boolean, String)`, is not reflected by anyone; its
`boolean` is the shiny flag, not the attacks flag, which is an easy mix-up for a caller.

`CobbleShiftHook` also contains `getActiveTransformSpecies()`, which looks up
`getTransformData(UUID)` on the manager. No such method exists (the getter is
`getTransform(UUID)`), so that lookup would always fail; nothing calls the method, so it
has no effect today.

CheeseGuard's flag handler comments that a transform leaves behind "(for flying species)
granted flight", and clears effects and flight after reverting. CobbleShift grants no flight anywhere; that clean-up
is defensive code in CheeseGuard.

The dashboard side is the opposite: CobbleShift compiles against CheeseDash
(`modImplementation` on the CheeseDash jar) and implements its `DashboardModule`
interface. That code only loads through the `cheesedash` entrypoint, so the jar still runs
without CheeseDash.

## Extension points

- **A new transform source.** Call `TransformManager.transform(...)` and `revert(...)` on
  the server thread. Every overload ends in the four-argument one.
- **A new type effect.** Add or edit an entry in `AbilityType`, and a case in
  `TypeProjectileEntity` for the on-hit effect. Both key on the lowercase type id.
- **A new config key.** Add the field, a line in `load()`, a line in `toJson()`, a setter
  with its clamp, and a branch in `ShiftApi.handleUpdateConfig`. Forgetting the last one
  is how the Hot Potato keys ended up readable and not writable.
- **A new REST route.** Add a branch in `ShiftApi.handle()`. Guard writes with
  `requireWrite` or `requireAdmin` in the guarded-return form, and wrap game mutations in
  `executeOnServerThread`.
- **Wiring `autoRevertOnDeath`.** Register `ServerLivingEntityEvents.AFTER_DEATH` in
  `onInitialize()` and call `transformManager.onPlayerDeath(player)` for players.

## Known weaknesses

Listed candidly, roughly by how much they cost in play.

- **Death does not revert.** `autoRevertOnDeath` defaults to `true` and has no caller. See
  [configuration](configuration.md#keys-that-do-nothing).
- **Vanilla clients see nothing.** A transformed player is invisible to anyone without the
  mod. There is no fallback rendering.
- **The client attack bar is a fixed 30 ticks**, unrelated to the real server cooldown, so
  the cooldown multiplier has little effect below 1.0 and causes wasted clicks above it.
  See [configuration](configuration.md#attacks).
- **Config file values bypass every clamp**, and `abilityTickInterval: 0` crashes the
  server on its first tick. See [ranges and clamps](configuration.md#ranges-and-clamps).
- **Off-thread mutations** in four REST handlers, listed under [threading](#threading).
- **The NPC save file can be truncated** by a single malformed entry, and keeps the
  entries of deleted NPCs forever. See [NPC transforms](npc-transforms.md#the-save-file).
- **The client never clears its state.** `TransformRenderer.clear()` exists and has no
  caller; nothing is registered on client disconnect. A player who leaves while
  transformed keeps their own UUID in the client map, and if they join another server or
  a singleplayer world in the same game session, the camera is forced to third person and
  their model replaced, until they restart the game. Observed from the code, not
  reproduced.
- **Misleading log line.** With Cobblemon absent, `SpeciesMapper` logs `Cobblemon not
  found — using fallback stats`. There are no fallback stats: every lookup returns
  nothing.
- **Dead fields.** `TransformData.locked` is never set. `hotPotatoEnabled` and
  `hotPotatoPassRange` are never read. `NpcTransformManager.setServer()` is never called.
- **`mapProjectileCount()` disagrees with `spawnProjectiles()`** about the 350-449 BST
  tier, so `/cshift species` and the API understate that tier. See
  [the transform model](transforms.md#attacks).
- **Hardcoded French** in two places that are not translated: the `AbilityType` display
  names and the dashboard sidebar description `Transformations et mini-jeux`.
- **A missing icon.** `fabric.mod.json` points to `assets/cobbleshift/icon.png`, which
  does not exist; mod menus show a placeholder.
- **A stray data folder.** `CobbleShift/data/cobblemon/species/` holds two Cobblemon
  species files (Charizard, Lycanroc) outside `src/main/`. They are not packaged into the
  jar and nothing references them. They look like reference copies left from development.

The mod has been run on the Arkensia server; none of the points above comes from an
automated test, because the project has none.

---

← [Dashboard API](dashboard.md) · [Troubleshooting](troubleshooting.md) →
