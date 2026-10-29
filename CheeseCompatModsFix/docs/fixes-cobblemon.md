[← CheeseCompatModsFix](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](fixes-cobblemon.fr.md)

# Fix catalogue — Cobblemon, battles and mega evolution

> ⚠️ Every fix on this page targets a specific version of a third-party mod. Two of
> them are not active: one was disabled after it broke production, one was never
> written because the attempt broke production. Both are documented here on purpose.

**Target mods on this page**

| Mod | Mod id | Version compiled against |
|---|---|---|
| Cobblemon | `cobblemon` | 1.6.1 (Fabric), Modrinth id `v77SHSXW` |
| MegaShowdown | — (reflection only) | not on the compile classpath |
| Cobblemon Trials Edition | `cobblemontrialsedition` | 1.2.2 (Fabric), id `XdMo4HiB` |
| Cobblemon Raid Dens | `cobblemonraiddens` | 0.4.7 (Fabric), id `d8mGjw4X` |
| Cobblemon Fight or Flight Reborn | `fightorflight` | 0.9.0 (Fabric), id `6McAEN1y` |
| CobblemonAlphas | — (reflection only) | not on the compile classpath |
| Rad Gyms | `rad-gyms` | not on the compile classpath |

---

## ⚠️ 1. The battle watchdog that froze every battle (disabled)

**Status: DISABLED. The class is still in the source tree, and its only call site is
commented out.**

This is the most useful warning this mod can give anyone, so it goes first.

### What it was meant to do

`RctForcedSwitchFix`, added in **1.2.51**, targeted a real and reproducible bug. When
several Pokémon faint on the same tick — Perish Song, a Requiem effect in a Rad Gyms
fight, a double KO in a 2v2 coop Battle Tower match — Cobblemon's engine emits a choice
request (`|request|`, in practice a force-switch) for an actor, but the routing of that
request to a non-player AI actor never triggers `AIBattleActor.onChoiceRequested()`.
The NPC actor is left with a non-null `request`, an empty `responses` list, and
`PokemonBattle.checkForInputDispatch()` waiting forever for an answer. The battle is
frozen.

The watchdog ran on `ServerTickEvents.END_SERVER_TICK`, walked
`BattleRegistry.battleMap` by reflection (there is no public getter), looked for a
non-player actor with a pending request and no responses, and after a debounce replayed
`onChoiceRequested()` on it. It was written fail-open: any exception logged a warning
and did nothing. It was idempotent per request object. It ran only on the server thread.

### What actually happened

The detection was wrong, and wrong in the most expensive possible way.
`isStuckNpcActor()` flagged as "stuck" **any** non-player AI actor with an active
request and an empty `responses` list — which is the **normal** state of every single
turn while the human player is still deciding what to do. The debounce was 60 ticks,
three seconds. Any player who thought for three seconds about their move had the
watchdog replay `onChoiceRequested()` underneath them, in the middle of a perfectly
healthy battle, wild and trainer alike. That desynchronised Showdown and **froze every
battle on the server**.

Production forensics added a second finding: it did not even unblock the real
force-switch cases it was built for. Those came back as a rejected `Can't pass`. So the
measured result was no benefit at all and server-wide damage.

It was disabled in **1.2.52** by commenting out the registration in
`CheeseCompatMod.onInitialize()`. The class file remains, with the full post-mortem as a
comment at the call site. The Perish Song / double-KO case is still open, and the note
in the code says it would need a strict force-switch filter before anyone tries again.

### The lesson, stated plainly

A "detect a stuck battle" heuristic has to distinguish *waiting* from *stuck*, and in a
turn-based engine those two states look identical from the outside. Pending request plus
empty responses is not evidence of a stall; it is the definition of a turn in progress.
Any watchdog of this kind needs a signal that only appears in the broken case — for
force-switch specifically, that means reading `request.getForceSwitch()` and nothing
else — and it needs to be tested on a server with real traffic, because the healthy
path is the one you will misfire on.

If you are tempted to re-enable this class: don't, not as written.

## ⚠️ 2. The held-item mixin that was refused (never re-attempted)

**Status: deliberately not implemented. There is no such mixin in the source tree, and
there should not be one.**

In **1.2.37** an attempt was made to mix into MegaShowdown's held-item handling, to make
custom mega stones resolve in the Showdown simulator. It broke **every Pokémon
held-item swap on the server** — not just mega stones, all held items, for everyone. It
was reverted, and the decision taken was not to try that approach again.

The `rusted_sword` consume behaviour that the attempt was originally chasing is
knowingly left unfixed. It is a WONTFIX, not an open task.

What replaced it is fix 4 below: Cobblemon exposes a public registration API for
exactly this, and using it does not touch anyone's held-item code path. If you find
yourself reaching for a mixin on held items, that API is the thing to reach for
instead.

## 3. Mega-Z forms never devolve

**Symptom.** A Pokémon that mega-evolves into a Mega-Z form stays in that form forever
— after the battle, after a reload, permanently. It also does not count against the
one-mega-per-player-per-battle cap, so a player can mega twice.

**Cause.** MegaShowdown hard-codes
`mega_aspects = HashSet(Set.of("mega", "mega_x", "mega_y"))` in the static initialiser
of `com.cobblemon.yajatkaul.mega_showdown.formChangeLogic.FormChangeHelper`. A form
whose aspect is `mega_z` is invisible to `hasMega()` (hence the cap bypass) and to the
post-battle `Devolve()` (hence the permanence).

**How.** `MegaShowdownAspectPatch.apply()`, called from `onInitialize`. It resolves the
class by name, reads the `mega_aspects` field reflectively and appends `"mega_z"` to the
live `HashSet`. The decompiled bytecode confirms it is a plain `HashSet`, so mutating it
is safe.

**Inert when absent.** Yes — `Class.forName` fails, the patch logs and returns. There is
no mixin and no compile-time dependency on MegaShowdown at all.

**Status.** Active.

## 4. Custom mega stones not recognised by the simulator

**Symptom.** A player holds one of the 42 CacademyMons mega stones, and the Mega button
is greyed out with no message and nothing in the log.

**Cause.** The CacademyMons datapack ships its mega stones as plain
`minecraft:brick` items (plus a handful of other vanilla items) distinguished only by
`custom_model_data`. Cobblemon's `CobblemonHeldItemManager` builds the held-item id it
sends to the Showdown simulator from the item's registry path — `"brick"` — which
Showdown cannot resolve to a stone id like `lucarionitez`. So `pokemon.heldItem` arrives
empty in the simulator, `canMegaEvo()` returns null, and the button is disabled
silently.

**How.** `CacademyMegaStoneRemapPatch.apply()` registers a `stackRemap` function through
Cobblemon's **public API**, `CobblemonHeldItemManager.registerStackRemap` (resolved by
reflection, because this mod compiles against Cobblemon with `compileOnly`). The
function maps the `(item registry id, custom_model_data)` pair to the right Showdown
stone id. Cobblemon then consults it on every held-item lookup during battle setup, and
the existing `items.js` / `held_items.js` entries that declare `megaStone` and
`megaEvolves` take effect on their own.

Scope: only the 42 stones that have a unique `(item_id, cmd)` pair. Entries where
`cmd == 0` on `minecraft:brick` collide with each other and are skipped — they were
placeholders in the datapack.

**Inert when absent.** Yes, twice over: without Cobblemon the reflective lookup fails
and the patch returns; without the CacademyMons datapack the remap table never matches
anything.

**Status.** Active. ⚠️ This fix is specific to one datapack. The item ids and
`custom_model_data` numbers are hard-coded in `REMAP`. On any other server it does
nothing — which is the intended failure mode, but worth knowing before you read the 241
lines of table.

## 5. Trials Edition crashes on Cobblemon 1.6.1

**Symptom.** `IncompatibleClassChangeError` whenever Cobblemon Trials Edition tries to
resolve a nature.

**Cause.** In Cobblemon 1.7.x the methods on `Natures` are `@JvmStatic`; in 1.6.1 they
are instance methods on the Kotlin singleton `Natures.INSTANCE`. Trials Edition was
compiled against 1.7.x, so it emits `invokestatic` against a method that is not static
on this server.

**How.** `TrialsNatureMixin` — an `@Overwrite` on
`SpawnablePokemonProperties`, replacing the call with one routed through
`Natures.INSTANCE`.

**Inert when absent.** Yes — gated on `cobblemontrialsedition` in the mixin plugin.

**Status.** Active. An `@Overwrite` is the heaviest hammer Mixin offers: it replaces the
method body wholesale and conflicts with any other mod touching the same method. It is
used here because the fix is a different bytecode instruction, not an added behaviour.

## 6. Alphas are silently uncatchable

**Symptom.** A CobblemonAlphas Pokémon cannot be caught. Poké Balls bounce off, no
capture animation, no chat message, nothing in the log. The player has no way to know
whether they are doing something wrong.

**Cause.** Unknown, and said so in the code. Alphas spawn with some corrupt internal
entity state that stops `EmptyPokeBallEntity.onEntityHit` from firing. Every path in
Cobblemon's `onEntityHit` that *blocks* a capture sends a chat message, and this symptom
is silent — so the throw is not being blocked, it is not arriving. The diagnostic that
pinned it down: running
`/data merge entity … {Pokemon:{ScaleModifier:2.5f}}` with the **same** scale value,
changing nothing, makes the alpha catchable. So the trigger is state that `readNbt()`
resets, not a scale or hitbox mismatch.

**How.** `AlphaScaleHitboxFixMixin`, `@Pseudo`-style target by string
(`com.cobblemon.mod.common.entity.pokemon.PokemonEntity`, `remap = false`), `@Inject`
at `HEAD` of `method_5773` — the intermediary name for `Entity.tick()`, written out
because Yarn names are not translated when `remap = false`. On the first tick of an
entity flagged `IS_ALPHA: true` in its `Pokemon.PersistentData`, it performs an NBT
round-trip (`writeNbt` then `readNbt`), which is what `/data merge` does internally.
Scale, AABB, the `IS_ALPHA` flag, IVs, abilities and loot drops all survive unchanged.

For a non-alpha Pokémon it is one boolean check and one `PersistentData` read per
entity, once.

**Inert when absent.** The mixin has no entry in `CheeseCompatMixinPlugin`, so it
returns `true` from the default branch and *is* applied whenever the target class
exists. With Cobblemon absent the target class does not exist and `require = 0` means
the injection is skipped silently rather than crashing. This is the one fix in the mod
whose inertness rests on `require = 0` rather than on an explicit mod check.

**Status.** Active. A fix for a cause nobody identified, which is stated as such in its
own javadoc. It works; nobody claims to know why the state is corrupt in the first
place.

## 7. Captured Giratina attacks its owner

**Symptom.** A player who catches a Giratina gets a Pokémon that behaves like a Warden
and attacks.

**Cause.** The aggressive AI comes from Fight or Flight's `PokemonAttackGoal`, not from
the boss mod people assumed. The goal does not check ownership.

**How.** `FoFOwnedGiratinaNoAggroMixin` — `@Inject` at `HEAD` of `method_6264`
(`Goal.canUse()`), `cancellable`, `require = 0`. A boss spawned by this mod carries the
command tag `cheesecompat_boss` and is allowed through; a Giratina with a Cobblemon
owner has `canUse()` forced to false.

Worth reading as a mixin lesson: **1.2.39 used
`@Shadow protected Entity pokemonEntity;` and crashed the server at boot** with
`InvalidMixinException: @Shadow field pokemonEntity was not located in the target
class`. The real field type is `PokemonEntity`, and `@Shadow` requires an exact type
match. 1.2.40 dropped `@Shadow` entirely and reads the field by reflection, caching the
`Field` on first call — which also removes any compile-time dependency on Cobblemon's
entity hierarchy.

**Inert when absent.** Yes — gated on `fightorflight`.

**Status.** Active.

## 8. Raid dens: four fixes

All four are gated on `cobblemonraiddens` in the mixin plugin.

### 8a. Terastallisation during a raid

**Symptom.** Players Terastallise inside a raid, which the raid design does not allow
for.

**How.** `RaidTeraBlockMixin` — `@Inject` at `TAIL` of
`ShowdownActionRequest.sanitize()`, stripping the tera option when the battle is in a
dimension in the `cobblemonraiddens` namespace. Note the target class belongs to
**Cobblemon**, not to Raid Dens; the gate is on Raid Dens because that is the only
reason to apply it.

### 8b. Boss faces the wrong way

**Symptom.** The raid boss spawns with its back to the crystal and to the players.

**How.** `RaidBossFacingMixin` — `@Inject` at `TAIL` of
`RaidCrystalBlockEntity.spawnRaidBoss`, turning the boss toward the crystal. The
crystal is always at `(0, 0, 0)` in a raid dimension; the boss position comes from
`raid_pois.boss_x/y/z` in the structure NBT.

### 8c. Crash when the raid dimension is cleared

**Symptom.** `ArrayIndexOutOfBoundsException` with index -1 while a raid starts, on
structures with many interactive blocks (redstone, copper bulbs, hoppers).

**Cause.** `spawnRaidBoss` calls
`getDimension().iterateEntities().forEach(entity -> entity.discard())`. That discards
entities from the dimension's `Int2ObjectLinkedOpenHashMap` *during* iteration,
corrupting the linked-list pointers.

**How.** `RaidEntityClearMixin` — `@Redirect` on the `Iterable.forEach` call, taking a
snapshot into an `ArrayList` first and applying the discard consumer to the copy.

### 8d. Fire spreading in raid dimensions

**How.** `RaidFireSpreadMixin` — `@Inject` at `HEAD` of `FireBlock.scheduledTick`,
`cancellable`, cancelling in any dimension whose registry key is in the
`cobblemonraiddens` namespace. The target is a **vanilla** class, so this mixin applies
to every fire tick on the server; the namespace check is the first thing it does.

### 8e. Crystals that never close

**Symptom.** A raid crystal stays open after everyone has left, died or disconnected,
so nobody can start a new raid there.

**Cause.** Raid Dens tracked state by dimension id, and dimension ids are reused per
player. Two rapid raids by the same player overwrote each other's entry in the map, and
the first crystal was forgotten.

**How.** `RaidDenFix`, a tick-driven handler keyed by **crystal position** instead of
dimension id. When a player exits a raid it schedules a check on that crystal in
`FAST_CHECK_DELAY` (20 ticks, 1 s), force-loading the crystal's chunk if needed, and
otherwise scans every `SCAN_INTERVAL` (100 ticks, 5 s). Escalation ladder:
`GRACE_PERIOD` 100 ticks before any action, `MEDIUM_THRESHOLD` 200 ticks,
`NUCLEAR_THRESHOLD` 300 ticks, and `GIVE_UP_THRESHOLD` 1200 ticks (60 s) after which it
stops tracking that crystal entirely. Giving up is deliberate: a crystal this mod cannot
close is better left alone than retried forever.

**Status.** All five active.

## 9. Rad Gyms: four fixes

**Target:** `rad-gyms`. Not on the compile classpath — every access is by reflection on
`lol.gito.radgyms.*`. Gated on `isModLoaded("rad-gyms")` in `CheeseCompatMod`, so the
whole class is inert without the mod.

### 9a. Players stranded in a gym dimension

**Symptom.** After a crash or a restart, a player is inside `rad-gyms:rad-gyms_dim`
with no way out.

**Cause.** Rad Gyms keeps gym instances in a `PLAYER_GYMS` `LinkedHashMap` in memory
only. A crash loses the map, and with it every exit route.

**How.** On join, players found in the gym dimension with no instance are teleported out
and told what happened, via `cheesecompat.radgyms.rescued`:

```text
You were rescued from a gym dimension after a server restart. Your gym progress was lost.
```

### 9b. Server hang on concurrent map access

**How.** The `PLAYER_GYMS` `LinkedHashMap` is replaced reflectively with a
`ConcurrentHashMap`. An unsynchronised `HashMap` under concurrent access can spin
forever inside `get()`, which presents as a hung server rather than as an exception.

### 9c. Wrong badge and loot for the gym type

**Symptom.** Two players enter different gym types at the same time and one gets the
other's badge.

**Cause.** `GymTemplate` is a singleton with static mutable state. Two concurrent gyms
race on it.

**How.** The original gym type key is tracked per player, and the correct `GymTemplate`
is restored before loot is handed out. The handler subscribes to Cobblemon's
`BATTLE_VICTORY` at `HIGHEST` priority — Rad Gyms subscribes at `NORMAL`, so this runs
first. The subscription is built by reflection: the `Priority` enum value is resolved by
name and the `subscribe(Priority, Function1)` method is found by scanning for a
two-parameter `subscribe`.

### 9d. NPE on interacting with a gym trainer

**Symptom.** Over 60 occurrences in one log of an NPE from
`Trainer.interactMob()`, typically after a disconnect and reconnect inside a gym
dimension.

**Cause.** `interactMob()` has no error handling and dereferences a `GymInstance` that
may not exist.

**How.** A `UseEntityCallback` pre-validates the state and blocks the interaction with a
message instead of letting it throw —
`cheesecompat.radgyms.no_gym` or `cheesecompat.radgyms.wrong_trainer`:

```text
You don't have an active gym session. Leave the gym dimension and try again.
This trainer doesn't belong to your gym session.
```

**Status.** All four active.

---

## Not shipped: the Mewtwo species override

`data/cobblemon/species/generation1/mewtwo.json` sits at the **mod root**, outside
`src/main/resources/`, so Gradle does not put it in the jar. It is a datapack file kept
alongside the code for reference, not something this mod applies. If you were expecting
a shiny-shadow Mewtwo resolver fix from installing the jar, it is not in there.

---

← [Fix catalogue — Battle Tower](fixes-battle-tower.md) · [Fix catalogue — Safari, Academy and bosses](fixes-safari-and-bosses.md) →
