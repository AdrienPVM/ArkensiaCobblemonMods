[← CheeseCompatModsFix](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

64 source files, about 7 456 lines. There is no domain model here and no subsystem in
the usual sense: the mod is a flat collection of independent patches sharing one
initialiser, one config file and one mixin config. Read this page for the shape of the
container; read the [catalogue pages](../README.md#the-fix-catalogue) for what is in it.

---

## Package layout

```text
com.cheesecompat
├── CheeseCompatMod.java              the main entrypoint; wires every fix, owns the config
├── CheeseCompatConfig.java           three booleans, config/cheesecompatmodsfix.json
├── CheeseCompatMixinPlugin.java      decides which mixins apply, from isModLoaded checks
├── CheeseCompatClient.java           client entrypoint: activates the Arkensia resource packs
│
├── BattleTowerDisconnectFix.java     party backup/restore across a disconnect (512 lines)
├── RadGymsFix.java                   four Rad Gyms fixes, all by reflection (413 lines)
├── RaidDenFix.java                   raid crystals that never close (377 lines)
├── RctForcedSwitchFix.java           battle watchdog — DISABLED, see below (276 lines)
├── CacademyMegaStoneRemapPatch.java  42 custom mega stones → Showdown ids (241 lines)
├── SafariSpawnInfluenceFix.java      1.6.1 spawn influences, via java.lang.reflect.Proxy
├── SafariBanFix.java                 re-registers three Cobblemon events for 1.6.1
├── SafariBallFix.java                safari balls in CobbleSafari dimensions
├── SlingshotBlockFix.java            blocks academy:slingshot in CobbleSafari dimensions
├── DungeonCaptureFix.java            blocks Poké Balls in the safari dungeon
├── EnhancedCelestialsWarmup.java     primes EC's lazy per-level lunar data
├── MegaShowdownAspectPatch.java      appends "mega_z" to MegaShowdown's aspect set
│
├── block                             the CobbleSafari portal blocks this mod adds
│   ├── CobbleSafariBlocks.java
│   ├── CobbleSafariPortalBlock.java
│   └── CobbleSafariPortalHelper.java
├── cobbleboss                        behaviour around Cobblewarden bosses
│   ├── ShriekerBossState.java        constants + cooldown persistence
│   ├── ShriekerBossAggroKeeper.java  re-targets bosses every 2 s
│   └── BossDarknessAura.java         DARKNESS within 50 blocks of a boss
├── daycare
│   └── DaycareSimpleModeStore.java   one-shot breeding state, keyed by dim + BlockPos
└── mixin                             35 server mixins + 4 client mixins

com.ace.cobbleboss
└── BossSpawner.java                  another mod's package; compiled here, injected there
```

`com.ace.cobbleboss` is not a typo. That is Cobblewarden Boss's own package, and
`BossSpawner` is written here so it can be extracted from this jar and injected into
theirs. Nothing in this mod calls it. See
[the bosses catalogue](fixes-safari-and-bosses.md#13-bossspawner--in-this-source-tree-not-in-this-jar).

## Who owns what

**`CheeseCompatMod`** is the whole of the wiring. `onInitialize()` runs, in order: load
the config, register the two missing Cobblemon blocks, register `/stopbattle`, schedule
the `/stopbattle <player>` restriction, then seven `isModLoaded` blocks, then the
Enhanced Celestials warmup and the two MegaShowdown patches unconditionally, then one
log line. There is no registry, no manager and no dispatcher — each fix registers its
own event handlers and is on its own from then on.

**`CheeseCompatConfig`** holds three booleans and nothing else. It loads from
`config/cheesecompatmodsfix.json` on init and **re-saves immediately after loading**, so
a field added in a new version appears in the file on first start with that version. It
is read through a static `CheeseCompatMod.getConfig()`, never reloaded, so edits to the
file on a running server do nothing. Details in [configuration](configuration.md).

**`CheeseCompatMixinPlugin`** is the one piece of shared machinery worth understanding,
and it is covered in its own section below.

**Everything else is a leaf.** No fix depends on another, with three exceptions, all in
the Battle Tower family: `BattleTowerManagerMixin` asks
`BattleTowerDisconnectFix.isRestorePending(...)`, `TeamFullRestoreMixin` writes its
backup through `BattleTowerDisconnectFix.writeBackupFromSnapshot(...)`, and
`SculkShriekerBossSpawnMixin` reads and writes `ShriekerBossState`. If you delete a fix,
grep for its class name before assuming nothing else used it.

## How mixins are turned off

`cheesecompatmodsfix.mixins.json` lists 35 server mixins and 4 client mixins, and names
`com.cheesecompat.CheeseCompatMixinPlugin` as its plugin. That plugin is the mechanism
that makes a mod full of third-party mixins safe to install on a server that has none of
those mods.

It evaluates fourteen `FabricLoader.isModLoaded(...)` checks **once**, into static final
booleans, then `shouldApplyMixin(targetClassName, mixinClassName)` matches on
**substrings of the mixin class name** and returns the corresponding boolean:

| Name contains | Gated on |
|---|---|
| `BattleTower`, `TeamSnapshot`, `NetworkHelper`, `TeamFullRestore`, `BattleArenaPosition`, `BattleTowerChunkPreload` | `cobblemon_battle_tower` |
| `ArcPhone`, `Sanctuary`, `EntrepreneurProfessionLock` | `legendarymonuments` |
| `OrePile` | `minerally` |
| `CobbleSafari`, `SafariTeleporter`, `DungeonPortalSpawn`, `DungeonDimensions` | `cobblesafari` |
| `TrialsNature` | `cobblemontrialsedition` |
| `RaidTera`, `RaidBoss`, `RaidFire`, `RaidEntity` | `cobblemonraiddens` |
| `Daycare`, `Fertility` | `daycareplus` |
| `AcademySlingshot` | `academy` |
| `SculkShriekerBossSpawn` | `cobblewarden_boss` |
| `EnhancedCelestialsFactoryFallback` | `enhancedcelestials` |
| `FoFOwnedGiratinaNoAggro` | `fightorflight` |
| `ExtraQuestsTimerResetGuard` | `extraquests` |
| `JourneyMapSeavlianIcon`, `IconDataAccessor` | `journeymap` |
| `NumismaticShopCull` | `numismatic-overhaul` |

The default branch returns `true`. That matters: a mixin whose name matches none of
those patterns is **always applied**. Exactly one mixin falls through —
`AlphaScaleHitboxFixMixin` — and it relies on `require = 0` and a string target to be
harmless when Cobblemon is absent. Three others target vanilla classes on purpose
(`RaidFireSpreadMixin` on `FireBlock`, `EntrepreneurProfessionLockMixin` on
`VillagerEntity`, `SculkShriekerBossSpawnMixin` on `SculkShriekerBlockEntity`) and are
gated by the table above anyway.

⚠️ **Substring matching is the weak point.** Name a new mixin `BattleTowerFooFix` and it
is silently gated on Battle Tower; name it `FooBattleTowerFix` and so is it. Name it
something that matches no pattern and it applies unconditionally. There is no error for
either mistake. When you add a mixin, add its gate to `shouldApplyMixin` in the same
commit, and pick a name that contains the family keyword.

The config also sets `"injectors": { "defaultRequire": 0 }`, so **a failed injection is
a skipped mixin, not a crash**, for every mixin that does not override it. That is the
right default for a mod like this — a mixin that no longer matches should degrade to "the
fix stopped working", not to "the server will not boot". The cost is that a broken fix
is silent. `JourneyMapSeavlianIconMixin` is the single exception, with `require = 1`.

## State and disk

Nothing in this mod is a managed save; every file is written by whichever fix owns it.

| Path | Owner | Written when | Format |
|---|---|---|---|
| `config/cheesecompatmodsfix.json` | `CheeseCompatConfig` | on load at init, and on first creation | pretty-printed Gson |
| `config/cheesecompat_bt_backups/<uuid>.dat` | `BattleTowerDisconnectFix` | on Battle Tower team lock-in | compressed NBT, atomic rename from `.dat.tmp` |
| `config/cheesecompat_daycare.json` | `DaycareSimpleModeStore` | on `SERVER_STOPPING` and as state changes | JSON |
| `config/cheesecompatmodsfix_shrieker_cooldowns.json` | `ShriekerBossState` | as cooldowns are set | JSON |

Two details worth knowing. `CheeseCompatConfig` resolves its path as
`Path.of("config", "cheesecompatmodsfix.json")` — relative to the working directory,
**not** through `FabricLoader.getConfigDir()`, which the other three use. On a normal
server launch those are the same directory. On a launcher that starts the server with a
different working directory, they are not, and the config lands somewhere unexpected
while the other three files land in the right place.

And the Battle Tower backups are the only files here that hold data you cannot
regenerate — they are a player's party. The 30-day expiry sweep on
`SERVER_STARTED` is the only thing that ever deletes them other than a successful
restore.

## Threading

| Thread | What runs there |
|---|---|
| Server (main) | every mixin injection, every event handler, every tick loop, `/stopbattle` |
| Client render | the four client mixins |
| Whatever sgui uses | the Daycare+ GUI click handler, which writes `DaycareSimpleModeStore` |

There is no HTTP thread and no worker pool: this mod registers no CheeseDash module and
starts no threads of its own. Mixins run on whatever thread called the method they are
injected into, which in every case here is the server thread.

The concurrency that does exist is defensive rather than architectural. Several fixes use
a `ConcurrentHashMap` where a plain map would do — `BattleTowerDisconnectFix`'s pending
and attempt maps, `BattleTowerManagerMixin`'s floor and battle counters,
`DaycareSimpleModeStore`'s table, `ShriekerBossState.COOLDOWN_UNTIL`. One of those is
load-bearing: `RadGymsFix` **replaces** Rad Gyms' own `PLAYER_GYMS` `LinkedHashMap` with
a `ConcurrentHashMap` by reflection, because unsynchronised `HashMap` access was hanging
the server inside `get()`.

The one real cross-thread hazard is the Daycare+ GUI writing the simple-mode store while
the pasture tick reads it. The map is concurrent and the values are two booleans, so a
torn read is not possible, but there is no ordering guarantee between a click and the
next tick. In practice a click that lands mid-tick takes effect on the following one.

## Reflection: the dominant pattern

Most of this mod talks to other mods by reflection, not by imports. There are two
distinct reasons, and conflating them leads to the wrong fix.

**Reason one: the mod is not on the compile classpath at all.** MegaShowdown,
CobblemonAlphas and Rad Gyms are never declared in `build.gradle`. Everything about them
is `Class.forName` on a hard-coded name.

**Reason two: the mod *is* on the classpath, but as `compileOnly` rather than
`modCompileOnly`.** `compileOnly` does not remap, so a Cobblemon class whose signature
mentions a Minecraft type arrives with that type in **intermediary** form (`class_3218`,
`class_2338`, `class_1297`), which javac cannot resolve against Yarn mappings. Touching
such a class directly fails to compile. The code works around this in three ways,
depending on the shape of the problem:

- **Reflection on the method**, when a call is needed — `SafariBallFix` scanning for
  `throwPokeBall` by name and parameter count, `BossSpawner` calling
  `PokemonProperties.Companion.parse`.
- **`java.lang.reflect.Proxy`**, when an interface has to be *implemented* —
  `SafariSpawnInfluenceFix` building `SpawningInfluence` instances, `SafariBanFix`
  building Kotlin `Function1` handlers.
- **`Object` in a mixin handler signature**, when a parameter type is the problem —
  `DaycareGUIToggleMixin` and `DaycareSimpleModeTickMixin` both do this.

`build.gradle` carries a comment on almost every dependency explaining which of the two
it is and why. Those comments are the most useful documentation in the build file; keep
them if you touch it.

### Never look up a Yarn name by reflection

This is the rule most easily broken. Method names in a compiled Fabric mod are
**intermediary** at runtime, not Yarn. `getDeclaredMethod("tick")` on a Minecraft class
fails; the name is `method_5773`. You will see that intermediary name written out
literally in several mixins — `AlphaScaleHitboxFixMixin` targets `method_5773`
(`Entity.tick`), `FoFOwnedGiratinaNoAggroMixin` targets `method_6264` (`Goal.canUse`),
`AcademySlingshotProtectionMixin` targets `method_7454`
(`ProjectileEntity.onEntityHit`), and `DungeonPortalSpawnMixin` writes a full descriptor
`spawnPortalNearPlayer(Lnet/minecraft/class_3222;Z)Z`. All four run with
`remap = false`, which is why Mixin does not translate the Yarn name for them.

Where a method's name genuinely cannot be assumed, the code scans instead of guessing —
`SafariBanFix.findSubscribeMethod` looks for a two-parameter method called `subscribe`
rather than matching a Kotlin-generated signature.

## Mixin inventory

Grouped by target mod, with the detail on each in the catalogue:

| Target | Mixins | Catalogue |
|---|---|---|
| Battle Tower | `BattleTowerManagerMixin`, `TeamSnapshotMixin`, `TeamFullRestoreMixin`, `BattleTowerShutdownMixin`, `BattleArenaPositionMixin`, `BattleTowerChunkPreloadMixin`, `NetworkHelperImplMixin`, `client.BattleTowerScreenAspectMixin` | [Battle Tower](fixes-battle-tower.md) |
| Cobblemon | `AlphaScaleHitboxFixMixin`, `RaidTeraBlockMixin` | [Cobblemon](fixes-cobblemon.md) |
| Trials Edition | `TrialsNatureMixin` | [Cobblemon](fixes-cobblemon.md) |
| Raid Dens | `RaidBossFacingMixin`, `RaidEntityClearMixin`, `RaidFireSpreadMixin` | [Cobblemon](fixes-cobblemon.md) |
| Fight or Flight | `FoFOwnedGiratinaNoAggroMixin` | [Cobblemon](fixes-cobblemon.md) |
| CobbleSafari | `CobbleSafariInitMixin`, `SafariTeleporterMixin`, `DungeonPortalSpawnMixin`, `DungeonDimensionsMixin` | [Safari](fixes-safari-and-bosses.md) |
| StarAcademy | `AcademySlingshotProtectionMixin` | [Safari](fixes-safari-and-bosses.md) |
| Cobblewarden Boss | `SculkShriekerBossSpawnMixin` | [Safari](fixes-safari-and-bosses.md) |
| Daycare+ | `DaycareIVFixMixin`, `DaycareIVAverageMixin`, `DaycareFromParentsMixin`, `DaycareEggFertilityAvgMixin`, `DaycareStrictFertilityMixin`, `DaycareSimpleModeTickMixin`, `DaycareGUIToggleMixin`, `FertilityClampMixin` | [Daycare+](fixes-daycare.md) |
| Legendary Monuments | `ArcPhoneHealMixin`, `ArcPhonePCMixin`, `SanctuaryDistanceMixin`, `EntrepreneurProfessionLockMixin` | [Everything else](fixes-misc.md) |
| Minerally | `OrePileMixin` | [Everything else](fixes-misc.md) |
| Enhanced Celestials | `EnhancedCelestialsFactoryFallbackMixin` | [Everything else](fixes-misc.md) |
| ExtraQuests | `ExtraQuestsTimerResetGuardMixin` | [Everything else](fixes-misc.md) |
| JourneyMap | `client.JourneyMapSeavlianIconMixin`, `client.IconDataAccessor` | [Everything else](fixes-misc.md) |
| Numismatic Overhaul | `client.NumismaticShopCullMixin` | [Everything else](fixes-misc.md) |

One mixin class exists in the source tree and is **not** in `mixins.json`:
`DaycareDestinyKnotConsumeMixin`. Mixin only applies what the config names, so it never
loads. See [the Daycare+ catalogue](fixes-daycare.md#4-destiny-knot-consumption--written-never-enabled).

## ⚠️ The watchdog: the scar worth reading

`RctForcedSwitchFix` is the one piece of this mod that did measurable harm in
production, and it is kept in the tree on purpose so the next person does not rewrite
it.

It was a battle watchdog, added in **1.2.51**, built to unstick a real bug: when several
Pokémon faint on the same tick, Cobblemon's engine emits a choice request for an AI
actor and never delivers it, so `checkForInputDispatch()` waits forever. The watchdog ran
on `END_SERVER_TICK`, walked `BattleRegistry.battleMap` by reflection, and replayed
`AIBattleActor.onChoiceRequested()` on any actor it judged stuck.

The judgement was wrong. `isStuckNpcActor()` flagged **any** non-player AI actor with a
pending request and an empty `responses` list, after a 60-tick debounce — and that is the
normal state of every turn while the human player is deciding. Three seconds of thought
was enough to trigger it. It replayed `onChoiceRequested()` into healthy battles, wild
and trainer alike, desynchronised Showdown, and **froze every battle on the server**.
Forensics afterwards showed it did not even unstick the force-switch cases it was built
for; those came back as a rejected `Can't pass`. No benefit, server-wide damage.

It was disabled in **1.2.52** by commenting out the one call that registered it, in
`CheeseCompatMod.onInitialize()`. The post-mortem is in that comment. The class still
compiles and is reachable from nowhere.

What makes this worth a section in an architecture document rather than a line in a
changelog: the class was *well engineered* and still catastrophic. It was fail-open,
idempotent per request object, confined to the server thread, and its own javadoc
honestly flagged the one thing it was not sure about ("the real effectiveness depends on
the hypothesis… to be validated in play"). None of that helped, because the error was in
the **detection predicate**, and a wrong predicate on a path that runs every turn for
every player does not degrade gracefully. Safety engineering around a hot loop does not
compensate for not knowing what "broken" looks like.

If you write a watchdog of this kind, find a signal that appears **only** in the broken
state — for force-switch, that means reading `request.getForceSwitch()` and nothing else
— and test it against real traffic, because the healthy path is the one you will misfire
on.

Its sibling scar is in [the Cobblemon catalogue](fixes-cobblemon.md#-2-the-held-item-mixin-that-was-refused-never-re-attempted):
a mixin on MegaShowdown's held-item handling, tried in **1.2.37**, which broke every
held-item swap server-wide and is deliberately not being retried.

## Extension points

- **Adding a fix for a mod already on the list.** Write the mixin, add it to
  `cheesecompatmodsfix.mixins.json`, and confirm the class name matches an existing
  pattern in `shouldApplyMixin`. Nothing else is needed.
- **Adding a fix for a new mod.** Add the `isModLoaded` constant and a branch to
  `shouldApplyMixin` **first**, then the mixin. Add the dependency to `build.gradle`
  pinned to a Modrinth **version id**, not a version number — the reasoning is in
  [`localdeps/README.md`](../../localdeps/README.md), and resolving by number can
  silently hand you a NeoForge jar.
- **A non-mixin fix.** Follow `SafariBallFix`: a static `register()`, called from an
  `isModLoaded` block in `onInitialize`, registering a Fabric event callback. An event
  that fires before the other mod's hook is often a cleaner lever than a mixin that
  fights it.
- **A new config flag.** Add the field to `CheeseCompatConfig` with its default. The
  load-then-save behaviour makes it appear in existing files automatically. Read it via
  `CheeseCompatMod.getConfig()`.

## Known weaknesses

Stated plainly, because finding these by surprise is worse.

- **There are two `fabric.mod.json` files, and one of them is a trap.** The real one is
  `src/main/resources/fabric.mod.json`: it declares `"version": "${version}"`, which
  `processResources` expands, so the packaged metadata correctly reads
  `1.2.53+1.21.1`. The other sits at the mod root, `CheeseCompatModsFix/fabric.mod.json`,
  is 27 lines long, is read by nothing and packaged nowhere, and still declares
  `"version": "1.1.0"` plus a description that stops at "Battle Tower, Legendary
  Monuments, Arc Phone toggles" — a periphery the mod outgrew a dozen target mods ago. It
  is a leftover from an older project layout. Anyone opening it first will draw wrong
  conclusions about both the version and the scope of the mod; it should be deleted.
- **`CheeseCompatMod` logs its version as a hard-coded string**, `"(v1.2.53)"`, in the
  startup line. Unlike the metadata, this one really is manual and has to be edited by
  hand on every release.
- **`DaycareDestinyKnotConsumeMixin` is dead code.** Not listed in `mixins.json`.
- **`DungeonDimensionsMixin` is an empty class** that exists only to carry a comment,
  and is still loaded and applied.
- **`CheeseCompatConfig` uses a relative path** where every other file in the mod uses
  `FabricLoader.getConfigDir()`.
- **No config reload.** The config is read once at init. Editing the file on a running
  server does nothing, and the next restart will rewrite it.
- **`defaultRequire: 0` makes every broken fix silent.** A mod update that moves a
  target method does not produce an error; it produces a fix that stopped working. The
  only way to notice is the symptom coming back.
- **Lambda-named injection targets.** `ArcPhoneHealMixin` and `ArcPhonePCMixin` inject
  into `lambda$registerServerReceiver$1`. Lambda names are compiler-assigned by source
  order and are not an API.
- **Hard-coded map geometry.** `BattleArenaPositionMixin`'s `18`-block NPC offset is the
  platform spacing of one specific arena.
- **One `@Overwrite` on a third-party initialiser.** `CobbleSafariInitMixin` replaces
  `CobbleSafari.initLogic()` wholesale, and silently discards anything CobbleSafari adds
  to it in a future version.
- **Blocks registered into another mod's namespace.** `cobblemon:tatami_mat` and
  `cobblemon:tatami_block` are registered by this mod, for CobbleSafari's worldgen.
- **An orphaned loot table** logs a parse error at startup when CobbleSafari is absent.
  Harmless, but it is noise, and it looks like a real failure to anyone reading the log
  for the first time.
- **No tests, of any kind.** Every claim in this documentation about what a fix does
  comes from reading the code and from one server's production experience.

---

← [Fix catalogue — everything else](fixes-misc.md) · [Troubleshooting](troubleshooting.md) →
