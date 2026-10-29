[← CheeseCompatModsFix](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](fixes-safari-and-bosses.fr.md)

# Fix catalogue — Safari, Academy and bosses

> ⚠️ The CobbleSafari family exists because CobbleSafari 0.1.5 was built for Cobblemon
> 1.7.1 and the server runs 1.6.1. These are not bug fixes; they are a back-port, held
> together by reflection and one `@Overwrite`. Nothing here survives a CobbleSafari
> update.

**Target mods on this page**

| Mod | Mod id | Version compiled against |
|---|---|---|
| CobbleSafari | `cobblesafari` | 0.1.5 (Fabric), Modrinth id `XANyMPaV` |
| StarAcademy | `academy` | `academy-mc1.21.1-v1.4.0+build.467-fabric.jar`, from `localdeps/` |
| Cobblewarden Boss | `cobblewarden_boss` | `cobblewarden_boss-fabric-1.0.0.jar`, patched separately |

---

## CobbleSafari on the wrong Cobblemon

CobbleSafari 0.1.5 targets Cobblemon 1.7.1. Two of its APIs do not exist in 1.6.1:

- `SpawnablePosition`, used by `RepelInfluence` and `BucketBoostInfluence`, is a 1.7
  class.
- `BattleStartedEvent.Pre`, used by `DimensionalBanEventHandler`, was
  `BattleStartedPreEvent` in 1.6.1.

Left alone, `CobbleSafari.initLogic()` throws on the way through those registrations and
the whole mod fails to initialise — no safari zones, no catch mechanics, no incubator,
no dungeons. The three fixes below cut out the two broken registrations and put
1.6.1-shaped replacements back.

### 1. Skip the broken registrations

**How.** `CobbleSafariInitMixin` — `@Overwrite` on `CobbleSafari.initLogic()`. It
reproduces the original method minus the `SpawningInfluence` registration and minus the
dimensional-ban event registration. Everything else CobbleSafari does in `initLogic`
still happens.

**Inert when absent.** Yes — gated on `cobblesafari` in `CheeseCompatMixinPlugin`.

**Status.** Active.

⚠️ An `@Overwrite` on a third-party initialiser is the single most fragile thing in this
repository. Any change CobbleSafari makes to `initLogic` — a new registration, a
reordering, a renamed call — is silently discarded by this mixin, and the symptom will
be "a CobbleSafari feature stopped existing", with nothing in the log.

### 2. Spawn influences, rebuilt for 1.6.1

**How.** `SafariSpawnInfluenceFix` registers two replacement influences through
`PlayerSpawnerFactory`, using `java.lang.reflect.Proxy` to implement Cobblemon's
`SpawningInfluence` interface. The Proxy is there to avoid compiling against
intermediary Minecraft class names (`class_3218`, `class_2338`, `class_1297`) that the
`compileOnly` Cobblemon setup cannot resolve.

| Influence | Behaviour |
|---|---|
| BucketBoost | multiplies the uncommon / rare / ultra-rare spawn bucket weights while a CobbleSafari PC effect is active |
| Repel | blocks Pokémon spawns within 80 blocks while the `REPEL` effect is active |

Effect ids come from `maxigregrze.cobblesafari.init.ModEffects` and the multipliers from
`maxigregrze.cobblesafari.config.SpawnBoostConfig`, both resolved by name.

**Status.** Active.

### 3. Dimensional bans, rebuilt for 1.6.1

**Symptom without it.** Inside a safari dimension, players can start battles, send out
Pokémon and fish, all of which the safari rules forbid.

**How.** `SafariBanFix` subscribes to three Cobblemon events at `HIGHEST` priority,
entirely by reflection:

- `BATTLE_STARTED_PRE`
- `POKEMON_SENT_PRE`
- `POKEROD_CAST_PRE`

Each handler asks `BannedItemsManager.isBattleAllowed(RegistryKey)` — CobbleSafari's own
rule table — and cancels if the answer is no. Handlers are built as Kotlin `Function1`
proxies returning `kotlin.Unit.INSTANCE`, and the `subscribe(Priority, Function1)`
method is located by scanning for a two-parameter `subscribe` rather than by name
lookup, because the signature is Kotlin-generated.

**Status.** Active. Failure here is loud: the whole `register()` is wrapped in a
try/catch that logs at `ERROR`. Bans then simply do not apply, which is a gameplay
regression and not a crash.

### 4. Teleporter only worked from the Overworld

**Symptom.** Stepping on a `cobblesafari:safari_teleporter` outside the Overworld — in
`cheeseguard:arkensia`, for instance — does nothing.

**How.** `SafariTeleporterMixin` — `@Inject` at `HEAD` of
`SafariTeleporterBlock.onEntityCollision`, `cancellable`, allowing the teleport from any
dimension that is not itself a safari dimension.

**Status.** Active.

### 5. Dungeon portals only spawned in the Overworld

**How.** `DungeonPortalSpawnMixin` on `PortalSpawnManager`, two injections at `HEAD`,
both `cancellable` with `require = 0`:

- `trySpawnRandomPortal` — picks a random player from all online players rather than
  only Overworld ones.
- `spawnPortalNearPlayer(Lnet/minecraft/class_3222;Z)Z` — the signature is written in
  intermediary form because the mixin runs with `remap = false`.

Portals are allowed in every dimension **except** `cobblesafari:*` ones, so a dungeon
portal cannot spawn inside a dungeon.

**Status.** Active.

### 6. A second dungeon dimension — not registered

`DungeonDimensionsMixin` is an **empty mixin class**. It is declared in
`cheesecompatmodsfix.mixins.json`, it targets `DungeonDimensions`, and it contains
nothing but a comment: `dungeon_jump` is deliberately not registered, because
CobbleSafari 0.1.5 has the dimension files but no worldgen structure for it — no
template pools, no start piece. Registering it would create an empty void dimension.

**Status.** Inert by construction. It is dead code that documents a decision. Loading an
empty mixin costs a class transformation and nothing else, but it is the kind of thing
worth deleting on the next pass.

### 7. Missing Cobblemon 1.7 blocks

**Symptom.** CobbleSafari's underground dimension fails to generate, because its
worldgen references `cobblemon:tatami_mat` and `cobblemon:tatami_block` — blocks added
in Cobblemon 1.7.x and absent from 1.6.1.

**How.** `CheeseCompatMod.registerCobblemonMissingBlocks()` registers both under the
`cobblemon` namespace: `tatami_mat` as a `CarpetBlock` with no collision, `tatami_block`
as a full block, both `MapColor.PALE_YELLOW`, strength `0.1f`, `BlockSoundGroup.WOOL`,
each with a matching `BlockItem`. Models, blockstates, textures and loot tables for both
ship in `src/main/resources/assets/cobblemon/` and
`src/main/resources/data/cobblemon/`.

Two guards: the whole method returns early if CobbleSafari is absent, and again if
`cobblemon:tatami_mat` is already in the block registry — so running on Cobblemon 1.7,
where the blocks exist upstream, is a no-op.

**Status.** Active. Registering blocks into **another mod's namespace** is not something
to copy casually. It works because Minecraft's registries do not care who registers an
id, and it will conflict the day Cobblemon registers its own under this server's load
order.

### 8. A portal block for the safari

Not a fix — an addition, grouped here because it only loads with CobbleSafari.

`CobbleSafariBlocks` registers two blocks in the mod's own `cheesecompat` namespace:
`cheesecompat:cobblesafari_portal_frame` (with an item) and
`cheesecompat:cobblesafari_portal`. A `UseBlockCallback` lights a frame. The frame
detection in `CobbleSafariPortalHelper` follows the vanilla Nether portal pattern:
width `MIN_WIDTH` 2 to `MAX_WIDTH` 21, height `MIN_HEIGHT` 3 to `MAX_HEIGHT` 21. Walking
into the portal runs the same teleport logic as stepping on
`cobblesafari:safari_teleporter`, with a per-player message cooldown so a player
standing in the portal is not spammed.

Block names come from the lang files:

```text
block.cheesecompat.cobblesafari_portal_frame  →  CobbleSafari Portal Frame
block.cheesecompat.cobblesafari_portal        →  CobbleSafari Portal
```

**Status.** Active.

⚠️ **Known harmless log noise.** The blocks are registered conditionally but their loot
table ships unconditionally, so on a server without CobbleSafari the server log carries:

```text
Couldn't parse element minecraft:loot_table/cheesecompat:blocks/cobblesafari_portal_frame
  - Unknown registry key ...: cheesecompat:cobblesafari_portal_frame
```

The server starts normally. It is an orphaned loot table, not a failure.

### 9. No capturing inside the dungeon

**Symptom intended.** Players were catching the spawner Pokémon in the underground
dungeon instead of battling them.

**How.** `DungeonCaptureFix` registers a `UseItemCallback` that, in
`cobblesafari:dungeon_underground` only, fails any item whose id starts with
`cobblemon:` and contains `ball`, with `cheesecompat.dungeon.capture_blocked`:

```text
You cannot capture Pokémon in the dungeon!
```

**Status.** Active. The item match is a substring test, so a future Cobblemon item with
`ball` in its name and no throwing behaviour would be blocked too.

---

## StarAcademy

### 10. Safari balls blocked in the wrong dimensions

**Symptom.** A safari ball does nothing in a CobbleSafari dimension — which is exactly
where it should work.

**Cause.** StarAcademy restricts safari balls to `academy:safari` through a mixin on
`Item.use()`.

**How.** `SafariBallFix` registers a `UseItemCallback`, which fires **before**
`Item.use()` and therefore bypasses Academy's mixin entirely. In a dimension whose id
starts with `cobblesafari:`, holding an item whose id contains `safari_ball`, it invokes
`PokeBallItem.throwPokeBall(World, ServerPlayerEntity)` by reflection (the method is
found by scanning declared methods for the name and a parameter count of 2), decrements
the stack unless the player is in creative, and returns success.

**Status.** Active. Bypassing another mod's restriction by getting in front of it with an
earlier event is a pattern worth naming: it does not fight the other mixin, it makes it
unreachable. It also means Academy's rule is still there and still applies everywhere
else.

### 11. Slingshot inside the safari

**How.** `SlingshotBlockFix`, also a `UseItemCallback`: in any `cobblesafari:` dimension,
an item whose id is exactly `academy:slingshot` is failed, with an action-bar message
`cheesecompat.slingshot.blocked`:

```text
The slingshot cannot be used here!
```

**Status.** Active.

### 12. Slingshot killing uncatchable bosses

**Symptom.** A boss Pokémon spawned as uncatchable is removed by a slingshot hit.

**How.** `AcademySlingshotProtectionMixin` —
`@Mixin(targets = "abeshutt.staracademy.entity.SlingshotEntity", remap = false)`,
`@Inject` at `HEAD` of `method_7454` (`ProjectileEntity.onEntityHit`), `cancellable`.
Every type in the handler signature is standard Minecraft (`EntityHitResult`, `Entity`),
and the uncatchable check is done by reflection, so javac never has to resolve
`PokemonEntity`'s intermediary supertypes (`class_1471`, `class_5147`).

**Inert when absent.** Yes — gated on `academy` in the mixin plugin. This is also the
mixin that makes the StarAcademy jar a build requirement: the Mixin annotation processor
validates a string target at compile time, so the class has to be on the classpath even
though it is named rather than imported.

**Status.** Active.

---

## Cobblewarden Boss

This boss mod is the one case where the fixes live in two places: mixins in this jar,
and **bytecode patches applied to the other mod's jar**, kept in `patches/cobblewarden/`.

### ⚠️ `patches/cobblewarden/` is not RoiCheese's code

`patches/cobblewarden/` carries compatibility patches for **Cobblewarden Boss by
LiquidSilverAce**, licensed **CC0-1.0**. This is the only part of the repository that is
someone else's work. The patched `fabric.mod.json` in that folder keeps LiquidSilverAce
as the author, keeps `"license": "CC0-1.0"`, and keeps the upstream project links
(`github.com/LiquidSilverAce/CobblewardenBoss`). Attribution stays with the author; the
patches are published under the same CC0 terms as the code they modify, not under this
repository's PolyForm licence.

`patches/cobblewarden/README.md` documents four patches, written in French, in full
forensic detail — constant pool indices, the exact byte sequences, and a Python snippet
to regenerate each one. In outline:

| Patch | What it does |
|---|---|
| 1 | relaxes the `depends` block: `fabricloader >=0.17.3→>=0.16.0`, `cobblemon >=1.7.1→>=1.6.1`, `fightorflight >=0.10.2→>=0.9.0`, after a bytecode audit confirmed only two Cobblemon methods are actually called |
| 2 | turns `EventResult.interruptFalse()` into `EventResult.pass()` in `WardenSpawnInterceptor`, so a second Warden in a region is no longer cancelled with nothing to replace it |
| 3 | replaces `BossSpawner` entirely, calling the Cobblemon API directly instead of running `/pokespawnat` — the command route crashed with an NPE inside `cobblemon_spawn_alerts` |
| 4 | NOPs out the `AncientCityTracker.hasSpawned` check completely, so **every** Warden triggers a boss regardless of the regional cache |

Two notes from that README are worth lifting out, because they are general lessons:

- `AncientCityTracker.hasSpawned()` keys its cache on `(x/128)_(y/64)_(z/128)`, which
  marks a whole 128×64×128 region as "already spawned" for the lifetime of the world. A
  boss killed weeks ago froze every future Warden in that region. Coarse cache keys look
  harmless until the thing you are caching is a one-time event.
- Patch 4 could not be written as a `goto`. Replacing the check with
  `goto 51` crashes the server at class load with
  `VerifyError: Expecting a stack map frame`, because `goto` is an unconditional branch
  and the JVM requires a `StackMapFrame` at the instruction after it — even when that
  instruction is dead code. The working form is `iconst_0` followed by the original
  `ifeq`, which has the same runtime effect (0 == 0, always branches) but stays a
  *conditional* branch, whose fall-through needs no frame.

⚠️ `patches/cobblewarden/README.md` references
`patches/cobblewarden/classes/com/ace/cobbleboss/WardenSpawnInterceptor.class` and a
patched `BossSpawner.class`. **Those `.class` files are not in the repository** — only
the README and the patched `fabric.mod.json` are. You would have to regenerate them from
the Python snippets in that README.

### 13. `BossSpawner` — in this source tree, not in this jar

`src/main/java/com/ace/cobbleboss/BossSpawner.java` is in the `com.ace.cobbleboss`
package, which is **Cobblewarden Boss's own package**, not this mod's. It exists to be
compiled here and then *extracted* and injected into the other mod's jar (patch 3). The
`cheesecompatmodsfix` jar carries the class but never calls it.

It spawns through the Cobblemon API by reflection —
`PokemonProperties.Companion.parse`, `props.createEntity(level)`,
`serverLevel.spawnEntity(entity)` — rather than through `/pokespawnat`, and then looks
for the nearest player within 64 blocks and calls `mob.setTarget(player)`. That last
step is there because `fightorflight.do_pokemon_attack_unprovoked=false` suppresses
spontaneous aggression, but a directed `setTarget` is not "unprovoked", so the AI
switches to combat immediately on spawn.

**Status.** Compiled, unused by this mod, used by the patched jar.

### 14. Instant boss spawn on a sculk shriek

**How.** `SculkShriekerBossSpawnMixin` — `@Inject` at `HEAD` of
`SculkShriekerBlockEntity.shriek`, `cancellable`. The target is a **vanilla** block
entity. It ignores creative and spectator players, checks
`SculkShriekerBlock.CAN_SUMMON` on the cached state, and then starts the boss sequence —
through `com.ace.cobbleboss.PortalAnimator.startPortalCreation` when that class is
present, or a direct spawn as a fallback.

State lives in `ShriekerBossState`, persisted to
`config/cheesecompatmodsfix_shrieker_cooldowns.json`:

| Constant | Value | Meaning |
|---|---|---|
| `COOLDOWN_MILLIS` | 10 minutes | per-centre cooldown between boss spawns |
| `COOLDOWN_RADIUS` | 200 blocks | radius a cooldown centre covers |
| `AGGRO_SCAN_RADIUS` | 96 blocks | how far to look for bosses to retarget |
| `AGGRO_TARGET_RADIUS` | 256 blocks | how far a boss will accept a target |
| `TELEPORT_DIST_THRESHOLD` | 24 blocks | distance past which a boss is teleported to its target |
| `TELEPORT_MAX_DIST` | 256 blocks | beyond this, no teleport |
| `TELEPORT_COOLDOWN_MS` | 5000 ms | minimum gap between teleports of one boss |

**Inert when absent.** Yes — gated on `cobblewarden_boss`.

**Status.** Active.

### 15. Bosses losing aggression

**How.** `ShriekerBossAggroKeeper`, on `END_SERVER_TICK`, every 2 seconds: for each
active cooldown centre, find spawned uncatchable boss Pokémon in range and force-retarget
the nearest living player. This keeps a shrieker-summoned boss hostile after its original
target dies or respawns.

**Status.** Active.

### 16. Darkness aura around a boss

**How.** `BossDarknessAura`, on `END_SERVER_TICK`: every `APPLY_INTERVAL_TICKS` (20, one
second), every non-spectator non-creative player within `RADIUS` 50 blocks of an entity
tagged `cheesecompat_boss` gets `DARKNESS` for `EFFECT_DURATION_TICKS` (60, three
seconds). The duration is longer than the interval so the effect is continuous while in
range and fades on its own a few seconds after leaving — no infinite duration, no
cleanup bookkeeping.

**Status.** Active.

---

← [Fix catalogue — Cobblemon and battles](fixes-cobblemon.md) · [Fix catalogue — Daycare+](fixes-daycare.md) →
