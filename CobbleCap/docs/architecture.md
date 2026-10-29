[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

52 Java source files, about 11 740 lines. Two of those files are more than 800 lines and
two more are over 1 000, which is the first thing to know: the logic is concentrated in a
handful of large managers rather than spread thin.

## Package layout

```text
com.cobblecap
├── CobbleCapMod.java            entry point: loads storage, registers events, owns every manager
├── client/                      client-only — HUD, /pasturehide, statue helpers
│   ├── CobbleCapClient          ClientModInitializer: payload receivers, HUD, command
│   ├── CapHudRenderer           the cap badge
│   ├── PastureHideClient        the toggle, its .properties file, the pastured-UUID cache
│   ├── PastureHideCommand       the client-side Brigadier tree
│   ├── StatueStyleClient        doppelgänger UUID → StatueStyle → texture Identifier
│   └── StatueFreezeGuard        ThreadLocal breaking recursion between two model mixins
├── commands/                    Brigadier trees
│   ├── CobbleCapCommand         /cobblecap root, cap/player/config subcommands (843 L)
│   ├── StatueCommand            statue, chrono and champion subtrees (822 L)
│   └── ExpLockCommand           /explock
├── dashboard/                   CheeseDash integration
│   ├── CobbleCapDashboardModule prefix, nav metadata, readiness gate
│   └── CobbleCapApi             the REST dispatcher (376 L)
├── data/                        state holders and their persistence
│   ├── CapConfig                config/cobblecap/config.json
│   ├── CapStorage               config/cobblecap/players.json, debounced writer
│   ├── PlayerCapData            one player's row
│   ├── ChampionConfig           config/cobblecap/champions.json, ordered list
│   ├── ChampionEntry            one champion — immutable record
│   ├── LegendsStorage           config/cobblecap_legends.json
│   ├── LegendEntry              one Hall of Fame row + TeamMember + Mode
│   ├── TenureStorage            config/cobblecap_tenures.json
│   ├── Tenure                   one ranked reign + FailRecord
│   ├── ChampionNpcState         the NPC-fallback binding
│   └── StatueStyle              the 13-value style enum
├── logic/                       everything that does something
│   ├── CapManager               XP event handling, the block message, Cobblemon reflection helpers
│   ├── CapListener              join/stop lifecycle, UseEntityCallback on rctmod NPCs
│   ├── CobblemonHook            reflective event subscription + the BATTLE_STARTED_PRE handler
│   ├── ChampionManager          gates, victory, promotion, party snapshots (1 147 L)
│   ├── ChronoManager            the run timers
│   ├── Conseil4Boards           4 scoreboards + 4 JSON exports
│   ├── StatueManager            dioramas, doppelgängers, anchors, style ticking (1 422 L)
│   ├── ChampionDoppelganger     one fake-player overlay
│   ├── RegisDoppelgangerManager in-arena skin overlay for the final trainer
│   ├── MojangProfileFetcher     4-tier GameProfile resolution
│   ├── RctTrainerMorpher        the datapack rewrite + queue
│   ├── RctReloadHook            reflection into rctmod's TrainerManager.loadTrainers()
│   ├── RegisModeManager         classic/ranked live-JSON swap
│   ├── ChampionNpcManager       the Cobblemon-NPC fallback morph
│   ├── Conseil4TrainerCloner    per-dimension trainer clones — DISABLED (548 L)
│   ├── FireworksManager         the celebration, CheeseGuard by reflection
│   ├── CobbleCapWebhook         Discord posts
│   ├── PastureTracker           server-side pasture authority, 2-second scan
│   └── SyntheticUuidRegistry    the set of doppelgänger UUIDs, shared with client mixins
├── mixin/
│   ├── PokemonSetLevelMixin     the safety net
│   ├── PokemonAddExperienceMixin the shaping layer
│   └── client/                  5 client mixins — pasture hide, 3 pose freezes, 1 texture swap
└── network/                     3 S2C payloads: CapSyncPayload, PastureSyncPayload, StatueStyleSyncPayload
```

## Who owns what

`CobbleCapMod.INSTANCE` is a public static singleton set in `onInitialize`, and every
manager hangs off it as a public final field with a getter. There is no dependency
injection and no service locator — `CobbleCapMod.INSTANCE.getCapStorage()` is the idiom
throughout, including from static methods and from mixins.

| Owner | Owns |
|---|---|
| `CapConfig` | the tunables, and saves itself on every setter |
| `CapStorage` | the `UUID → PlayerCapData` map; the only writer of `players.json` |
| `ChampionConfig` | the ordered champion list and `nextUnbeaten` |
| `LegendsStorage` | the Hall of Fame, test rows included |
| `TenureStorage` | ranked reigns; at most one open |
| `ChronoManager` | running timers |
| `StatueManager` | diorama entities, doppelgänger overlays, anchors, style ticking |
| `RctTrainerMorpher` | the pending morph and the datapack files |
| `ChampionNpcState` | the fallback NPC binding |

`CapManager`, `ChampionManager`, `Conseil4Boards`, `FireworksManager`, `PastureTracker`,
`RctReloadHook`, `RegisModeManager` and `SyntheticUuidRegistry` are all **static** —
stateless helpers, or static mutable state with no instance.

## Persistence

| File | Written when | Atomic |
|---|---|---|
| `cobblecap/config.json` | every setter, and first start | no |
| `cobblecap/champions.json` | `setChampions` (dashboard `PUT`) | no |
| `cobblecap/players.json` | 500 ms debounce after any change, plus `SERVER_STOPPING` | **yes** |
| `cobblecap_legends.json` | every add, replace or removal | **yes** |
| `cobblecap_tenures.json` | every tenure change and failure | no |
| `cobblecap_chronos.json` | every start, stop, cancel | **yes** |
| `cobblecap_dioramas.json` | every anchor change | **yes** |
| `cobblecheesescore_external_conseil4_*.json` | every board rebuild | **yes** |
| `cobblecap_client.properties` | every `/pasturehide` change | no |

"Atomic" means a `.tmp` sibling and a `Files.move`, preferring `ATOMIC_MOVE` and falling
back to a plain replace. Four files do not do that, and `cobblecap_tenures.json` is the one
that matters: it holds the current champion and is written straight into place, so a crash
mid-write can truncate it.

`players.json` is the only debounced writer. `CapStorage.saveAsync` flips an
`AtomicBoolean` and schedules a full save 500 ms later on a daemon thread named
`CobbleCap-Save`, collapsing a burst into one write. The window where a hard crash loses
data is therefore half a second — realistically one cap raise.

## Threading

| Thread | What runs there |
|---|---|
| **server thread** | every command, every Cobblemon event handler, the two tick bodies, all entity work, `RctReloadHook` |
| **HTTP thread** (CheeseDash) | `CobbleCapApi` handlers — reads directly, mutations hop to the server thread |
| **netty thread** (client) | payload receipt, immediately deferred with `context.client().execute(...)` |
| **client render thread** | `CapHudRenderer.render`, the four client mixins |
| `CobbleCap-Save` daemon | `CapStorage` debounced writes |
| `cobblecap-tenures-save` | one `new Thread` per `TenureStorage.saveAsync` call |
| the fireworks thread | one `new Thread` per show, sleeping between rockets |

The hand-offs that matter:

- **HTTP → server.** `CobbleCapApi` wraps every mutation in
  `WebDashboard.executeOnServerThread(server, …)`: `handleSetCap`, `handleToggleExpLock`
  and `handleFireworksTest`. Config and champion `PUT`s do **not** hop, because they only
  touch plain Java state and write a file — defensible, though `ChampionConfig.setChampions`
  replaces a list that the server thread reads on every battle, without synchronisation.
- **fireworks thread → server.** Each rocket is a `server.execute(...)`; only the `sleep` is
  off-thread.
- **netty → client.** Every receiver body is inside `context.client().execute(...)`.

Concurrency primitives used: `ConcurrentHashMap` for the player map, the message
rate-limiters, the doppelgänger map and the pastured sets; `CopyOnWriteArrayList` for
legends and tenures; `AtomicReference` for the pending morph; `AtomicBoolean` for the save
flag and the fireworks guard; `ReentrantLock` for the Régis mode swap; `synchronized` on
`save()` in several storages; and three `ThreadLocal<Boolean>` guards —
`LEVEL_FORCE_ACTIVE`, `cobblecap$capping` and `StatueFreezeGuard`.

### The two tick bodies

`END_SERVER_TICK` runs three things, each with its own internal throttle:

| Called | Throttle | Cost |
|---|---|---|
| `StatueManager.tick()` | every 10 ticks | iterates **every entity in every loaded world**; doppelgänger auto-heal every 60 ticks |
| `RctTrainerMorpher.tick(server)` | every 20 ticks | a single `AtomicReference` read, almost always null |
| `PastureTracker.tick(server)` | every 40 ticks | iterates **every entity in every loaded world**, with reflection per Pokémon |

Two full world-entity scans on a shared tick is the mod's main standing cost. The tag check
in `StatueManager` and the class-name check in `PastureTracker` are both first, so the
per-entity cost is small, but the iteration itself is not free on a large world.

## The reflection strategy

Nothing in `logic/` imports Cobblemon. Everything goes through `Class.forName` and method
scanning, for one stated reason: at runtime a Yarn method name is an *intermediary* name, so
`getDeclaredMethod("getOwnerPlayer")` would not match.

Three patterns recur:

1. **Name candidates.** `CapManager.invokeGetter(target, "getLevel", "level")` tries each
   name against both `getMethods()` and `getDeclaredMethods()`.
2. **Scan by signature.** `PokemonSetLevelMixin` finds the owning player by looking for any
   zero-argument method whose return type is assignable to `ServerPlayerEntity`.
   `RctTrainerMorpher.countActiveBattles` finds the battle map by taking the first
   `Map`-typed declared field.
3. **A Kotlin `Function1` proxy.** `CobblemonHook.hookEvent` builds a
   `java.lang.reflect.Proxy` implementing `kotlin.jvm.functions.Function1`, handles
   `invoke`/`toString`/`hashCode`/`equals` itself, and passes it to the observable's
   `subscribe` — trying the `(Priority, Function1)` overload first and falling back to
   `(Function1)`.

The two mixins are the exception: they `@Mixin(Pokemon.class, remap = false)` with a
compile-time `compileOnly` Cobblemon dependency, because a mixin needs a real target class.

### Fail-open and fail-closed

The policy is not uniform, and the asymmetries are deliberate:

| Path | On failure | Consequence |
|---|---|---|
| `CapManager.handleXpEvent` | **open** — XP allowed | a Cobblemon rename degrades the cap rather than crashing |
| both mixins | **open** | same |
| `ChampionManager.handlePreBattle` with no player row | **open** | the fight proceeds |
| `isFtbQuestCompleted` | **open** — returns `true` | FTB Quests absent never blocks a fight |
| `playerPartyViolatesCap` | **open** — returns `false` | the battle is not cancelled |
| `findBannedSpeciesInParty` | **open** — empty list | broken party reflection silently disables the ban list |
| `getPokedexCount` | **closed** — returns `0` | a Pokédex gate becomes impassable |
| `countActiveBattles` | **closed** — returns `−1` | documented, but no longer called |
| the NPC party clone | **closed** — slot skipped | never share a live Pokémon reference |

Of those, `getPokedexCount` is the one to remember: it is the only reflection failure that
*blocks* players rather than letting them through.

## Mixins

```json
"mixins": ["PokemonSetLevelMixin", "PokemonAddExperienceMixin"],
"client": ["client.EntityRenderDispatcherMixin", "client.BipedEntityModelMixin",
           "client.PlayerEntityModelMixin", "client.BipedAnimateModelMixin",
           "client.PlayerEntityRendererMixin"]
```

| Mixin | Target | Why |
|---|---|---|
| `PokemonSetLevelMixin` | `Pokemon.setLevel(int)` HEAD, cancellable | catches level sets that bypass XP entirely — the motivating case was Cobblemon Utility+'s MasterCandyItem |
| `PokemonAddExperienceMixin` | `Pokemon.addExperienceWithPlayer` and `addExperience` HEAD, cancellable | trims XP to the headroom below the cap so no level-up message fires and gets rolled back |
| `EntityRenderDispatcherMixin` | `EntityRenderDispatcher.render(...)` HEAD | `/pasturehide` — cancels the draw for a tethered Pokémon |
| `BipedAnimateModelMixin` | `BipedEntityModel.animateModel(LivingEntity,FFF)` HEAD | cancels the pre-pose pass, so a statue does not mirror a nearby swimmer |
| `BipedEntityModelMixin` | `BipedEntityModel.setAngles(LivingEntity,FFFFF)` HEAD | re-invokes with zeroed motion — no breathing, no sway |
| `PlayerEntityModelMixin` | `PlayerEntityModel.setAngles(...)` HEAD | the player override animates sleeves and ears before calling `super`, so the parent mixin is too late |
| `PlayerEntityRendererMixin` | `PlayerEntityRenderer.getTexture(Entity)` HEAD | returns the pre-baked statue PNG for non-`natural` styles |

`injectors.defaultRequire` is `1`, so a signature that stops matching is a **hard startup
failure**, not a silent skip. That is the right default for the two cap mixins and a
liability for the five cosmetic ones: a client-side Minecraft or Cobblemon change breaks
the game rather than losing a cosmetic effect.

## Extension points

Things a contributor can use without touching the internals:

- **`CobbleCapMod.LEVEL_FORCE_ACTIVE`** — a `public static ThreadLocal<Boolean>`. Set it
  true on your thread and `PokemonSetLevelMixin` plus the `BATTLE_STARTED_PRE` party check
  stand down. This is how CheeseCompat lets Battle Tower force levels above the cap. Set it
  back in a `finally`.
- **`RctTrainerMorpher.trainerJsonRelPath` / `trainerSkinRelPath` / `datapackName`** —
  `public static`, non-final, reassignable at runtime.
- **The CobbleCheeseScore exports** — four JSON files with a stable shape, written on every
  board rebuild. Read them rather than parsing scoreboards.
- **The `cobblecap_mode` scoreboard** — `1` classic, `2` ranked. Any system can write it;
  `/cobblecap mode` is only one producer.
- **The `cobblecap:` advancements** — granted on victory, and FTB Quests is expected to
  notice them by polling. Any quest system that watches advancements can hook the same way.
- **`SyntheticUuidRegistry`** — lives in the common source set precisely so client code can
  read it.

There is **no** public Java API, no events published for other mods, and no WebSocket
channel. The dashboard integration is one-way: CheeseDash calls in, CobbleCap never pushes.

## Disabled and dormant code

Stated plainly, because reading the source without this list is confusing:

- **`Conseil4TrainerCloner` (548 lines) is disabled.** `CobbleCapMod.onInitialize` calls
  `registerCleanupOnly()`, not the full registration. It was meant to clone each Conseil 4
  trainer into a per-dimension variant so simultaneous battles would not collide on rctmod's
  shared `TrainerNPC` singleton. The prod deploy on 2026-05-17 produced *Invalid trainer id*
  on all five trainers, because rctmod's reload did not rescan datapack files from disk.
  What still runs is a one-shot cleanup that reverts stale per-dimension ids and deletes
  orphan clone JSONs. `ChampionManager.stripPerDimSuffix` exists to normalise the ids that
  deploy left behind.
- **`RctTrainerMorpher.countActiveBattles`** — the fail-closed battle check. Never called;
  see [champion morphing](champion-morphing.md#the-battle-check-is-dead-code).
- **`RctTrainerMorpher.downloadMojangSkin` and `writeAtomicBytes`** — the skin morph. Never
  called; a datapack cannot serve assets, and writing to `resourcepacks/` would force every
  client to re-download.
- **`StatueManager.equipStyleArmor`** — never called. A leftover of the visible-armour-stand
  design the doppelgänger replaced, which is why a style's armour preset has no visible
  effect.
- **`ChampionManager.triggerFtbQuest` and `triggerFtbQuestById`** — no longer called from
  the victory path. They forced quest completion, which went
  `Quest.forceProgress → Reward.forceProgress → claimReward` and occasionally duplicated a
  badge reward. Removing the call made the dupe structurally impossible.
- **`ChampionNpcManager.morphTo`** — no caller. Only the `bind_npc*` commands touch that
  class, so the fallback path records a binding and nothing morphs automatically.
- **`PastureHideClient.debugLog` and `lastDebugLog`** — leftovers of the
  client-side-reflection approach that could not work.
- **`ChampionManager.allChallengesBeaten`** is private and *is* used, by
  `handleChallengeVictory`; mentioned only because it looks dead next to the rest.

## Known weaknesses

Beyond the dead code:

- **The Régis mode swap is server-global** while the mode flag is per-player. Two players
  entering with opposite modes both fight whichever was written last. Acknowledged in the
  source as an accepted risk at five to ten entries a week.
  [Details](champion-morphing.md#the-régis-mode-swap-is-global).
- **The final champion id is hard-coded in four places** —
  `ChampionManager` lines 548, 605, 792 and `StatueCommand` line 541.
- **`datapackName = "CacademyMons"`** is hard-coded at `RctTrainerMorpher` line 57, and a
  comment above it falsely claims it is configurable from `CapConfig`.
- **Statues are indexed only by command tags,** keyed by lower-cased player **name**. A
  rename orphans a diorama; two names differing only in case collide.
- **The Pokémon statue spawn is a spatial heuristic** — `/spawnpokemonat` returns no entity,
  so the mod snapshots UUIDs before and looks within 4 blocks after.
- **`refreshCurrentChampionDioramas` needs an online operator** as a command-source proxy,
  because Cobblemon's spawn command NPEs without a player. No operator online, no Pokémon
  row.
- **Four of nine state files are not written atomically,** `cobblecap_tenures.json`
  included.
- **`ChampionConfig.setChampions` replaces an unsynchronised list** from an HTTP thread
  while the server thread reads it per battle.
- **`CapHudRenderer` receives `nextChampion` and never draws it.** The lang key
  `cobblecap.hud.next` exists and is unused.
- **Several player-facing strings are French `Text.literal`s,** not translatable keys: the
  chrono auto-cancel notice, the record-beaten and slower-run broadcasts, the ranked
  crowning broadcasts, the chrono-stop refusal, and every Conseil 4 scoreboard title. An
  English client sees French.
- **`/cobblecap status <player>` prints sixty to a hundred chat lines,** including a full
  scoreboard dump and an advancement scan.
- **`extractRctmodTrainerId` falls back to a loose heuristic** — any zero-argument
  `String` getter whose result contains an underscore and is longer than three characters.
  It can return something unrelated.
- **Two large classes carry most of the risk.** `StatueManager` at 1 422 lines and
  `ChampionManager` at 1 147 mix persistence, reflection, entity work and messaging. They
  are where a change is most likely to have a consequence somewhere else.

## Deliberate trade-offs

- **Reflection everywhere over a hard Cobblemon dependency.** Costs type safety and makes
  every failure a runtime one; buys a mod that loads and does not crash when Cobblemon
  renames something.
- **Fail-open on the XP path.** A broken cap is better than a crashed server, and the cap
  is a game rule rather than a correctness guarantee.
- **The `−1` XP headroom.** Leaves a capped Pokémon one point short of the cap level, which
  looks odd but prevents Cobblemon's level-up path from firing at all.
- **No forced FTB Quests completion.** Slower and dependent on FTB's polling, but it closed
  a reward-duplication hole for good.
- **No WebSocket channel.** The dashboard polls. Simpler, at the cost of live updates.
- **An invisible marker armour stand plus a fake player,** rather than a visible armour
  stand with a head. Much more machinery — five listeners and a 3-second auto-heal — but it
  is the only way to get a full player body on a vanilla client.

---

← [Configuration](configuration.md) · [Troubleshooting](troubleshooting.md) →
