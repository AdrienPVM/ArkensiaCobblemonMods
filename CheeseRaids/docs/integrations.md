[← CheeseRaids](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](integrations.fr.md)

# External integrations

CheeseRaids talks to six other mods. Two are hard dependencies compiled against; four are
reached by **reflection** and degrade when absent. That split decides what a dependency
update can break, and how loudly.

> ⚠️ Everything on this page is read out of CheeseRaids' own source. The behaviour of the
> third-party mods themselves is inferred from the class and method names the hooks look
> for, not from their source — treat those details as observations.

---

## The dependency map

| Mod | Declared in `fabric.mod.json` | How it is reached | If absent |
|---|---|---|---|
| Fabric API | `depends` | normal API | the mod does not load |
| [CheeseDash](../../CheeseDash/) | `depends` | compile-time, `modCompileOnly` | the mod does not load |
| [CheeseGuard](../../CheeseGuard/) | `depends` | compile-time, `modCompileOnly` | the mod does not load |
| Cobblemon | not declared | reflection | no wave, no boss — the raid runs empty |
| EasyNPC | not declared | reflection | no herald; the command fallback is announced |
| CobbleSafari | not declared | reflection | no victory portals |
| [CheeseStore](../../CheeseStore/) | not declared | reflection | `reward.fromage` is a silent no-op |

Four of those seven are not declared anywhere, so Fabric cannot warn you about a missing
one and a modpack build cannot catch it. Cobblemon in particular is **not optional in
practice**: without it the raid announces itself, spawns nothing, times out, and cleans up.
The only reason it is not a declared dependency is that every access goes through
reflection.

## The reflection rule this mod follows

Across these mods there is a standing rule: never resolve a Minecraft method by its Yarn
name, because at runtime those names are intermediary. CheeseRaids applies the same
discipline to third-party mods, which is why the hooks look odd. Instead of
`getDeclaredMethod("setDisplayAttribute", …)` they scan `getMethods()` and match on
**shape** — parameter types, parameter count, return type.

The payoff is that a third-party method rename does not break the hook. The cost is that a
*signature* change breaks it silently, and that these matches are loose enough to be
fragile in a different way. `CobblemonBattleHook.getBattleId` accepts any zero-argument
method returning a `UUID` whose name contains `battle` — if a Cobblemon update adds a
second such method, the hook may pick the wrong one.

## Commands versus APIs

The task of documenting this mod started from an assumption worth correcting: that the
raid spawns its entities by running text commands. **It does not, for the things that
matter.** The config file still carries the command templates from an earlier design, but
they are dead:

| Config key | Looks like | Actually |
|---|---|---|
| `wave.spawnCommand` | `execute in {dim} positioned {x} {y} {z} run pokespawn {species} {aspects} level={level}` | unused. The wave spawns through `CobblemonSpawnHook` |
| `boss.spawnCommand` | the same template | unused. The boss spawns through the same hook |
| `boss.scaleCommand` | a template taking `{uuid} {scale}` | unused. Scaling goes through Cobblemon's `setScaleModifier` |

The code comment explains why the commands were abandoned, and it is the most useful
sentence in the mod: `pokespawn` run from a silent console source **swallowed its own
failures**, and the failure actually encountered — `ServerWorld.addEntity` returning false
because the chunk was not loaded — produced no spawn and no error. The direct API call
returns a checked boolean and an entity handle, so a refusal is logged and there is no
guesswork about which entity was spawned.

Three command templates **are** live:

| Key | When it runs | Placeholders |
|---|---|---|
| `npc.showCommand` | at the herald's reveal, in addition to the Java toggle | `{world} {type} {x} {y} {z} {npc}` |
| `npc.hideCommand` | when the herald is hidden | the same |
| `reward.portalCommand` | only if CobbleSafari opened zero portals | `{dim} {x} {y} {z} {index}` |

All three default to empty, so out of the box the mod runs no templated command at all.
When one does run, it goes through a level-4 console source whose output is **captured and
logged** rather than discarded, and a `CommandSyntaxException` is logged with the command
text. That is the opposite of the `pokespawn` problem and worth copying if you add your
own template.

Two vanilla commands are also issued directly, unconditionally, built in code:
`weather thunder 1200` and `time set midnight` (and `weather clear` at cleanup) wrapped in
`execute in <world> run`, plus `title @a …` for the announcement. Those are stable vanilla
syntax; they are not a third-party risk.

---

## CheeseGuard

The only hard dependency the mod actually uses at runtime, and it uses it directly — real
imports, no reflection:

```java
com.cheeseguard.CheeseGuardMod.INSTANCE.getDimensionManager()
```

Three calls:

- **`createDimension(id, name, GenType.VOID, cfg -> …)`** — creates the rift the first
  time a player enters, as a void world named `<displayName> — Faille`, with spawn at
  `arena.center` + 1, `keepInventory` from the config, PvP off, mob spawning off, raid dens
  off, time locked to 18000, weather locked, and `enabled = false` so nobody can enter
  outside a raid. `createDimension` early-returns if the dimension already exists, which
  is why changing `arena.keepInventory` after the first raid has no effect;
- **`getConfig(id)` + `applyTimeLock(cfg)` + `save()`** — re-pins midnight. A rift created
  by an older build of the mod keeps its old config, so the lock is forced here. The
  blocking `save()` runs at most once per rift per server session; a player entering
  re-pins the display only, with no disk write;
- nothing else. CheeseGuard's zones, events and chunk tooling are not used.

The old README claimed raids "hook its events". They do not: there is no CheeseGuard
`EventEffect` type for raids in this mod, in either direction. Compare
[CheeseKart](../../CheeseKart/), which does register `KART_START_ALL` and `KART_STOP_ALL`.

Because the dependency is compile-time and declared in `depends`, a CheeseGuard API change
breaks the **build**, loudly, which is the good failure mode. There is no graceful
fallback: if `CheeseGuardMod.INSTANCE` is null the rift creation throws, is caught, logged
as `Failed to create rift dimension`, and every `enter` replies *« La faille n'a pas pu
s'ouvrir. »*

## Cobblemon

Two hooks, both reflection, both essential.

### Spawning — `CobblemonSpawnHook`

Resolved once, lazily:

```text
com.cobblemon.mod.common.api.pokemon.PokemonProperties
  → public static field Companion
  → Companion.parse(String)
  → PokemonProperties.createEntity(World)   [matched by name + single World-assignable param]
```

Then `refreshPositionAndAngles`, an optional command tag, the scale modifier, vanilla
persistence, a synchronous `getChunk` to force the target chunk, and `world.spawnEntity`.

The properties string is assembled by the mod, not configured:

```text
<species> level=<n> aspect=<a> aspect=<b> … uncatchable=true
```

`aspects` is a comma list in the config and becomes one `aspect=` token each. Everything
the mod spawns is `uncatchable=true` — wave mobs and boss alike. There is no option to
make a raid Pokémon catchable.

Failures are logged, each with a distinct line: `Cobblemon API init failed`,
`Cobblemon API unavailable; cannot spawn '<props>'`, `createEntity returned non-Entity`,
`spawnEntity refused '<props>' at x,y,z (chunk not loaded / build limit?)`. If you are
debugging an empty raid, those four strings are what to grep for.

The scale path is separate and softer: it scans the entity for a zero-argument
`getPokemon()`, then the returned object for a one-float setter whose name contains
`scalemodifier`. A miss logs `scale <n> unsupported` and the mob spawns at normal size.
The fight still works; the boss is just not giant.

### Battles — `CobblemonBattleHook`

```text
com.cobblemon.mod.common.battles.BattleRegistry
  → public static field INSTANCE
  → getBattle(UUID)
```

Used only to answer one question: has the boss's battle finished? The entity's battle id
is read by shape (zero params, returns `UUID`, name contains `battle`) every combat tick
and remembered; victory then waits for `getBattle(id)` to return null.

If this hook fails to resolve, the mod falls back to an age check: entity gone and more
than five seconds since the spawn counts as a kill. That fallback is why a hook failure
here is survivable, and also why players may be teleported out a couple of seconds early.

### What breaks on a Cobblemon update

A rename of `PokemonProperties`, `Companion`, `parse`, `BattleRegistry` or `INSTANCE`
breaks the hook — these are matched by exact name. A change to `createEntity`'s parameter
types, or to the `getBattle(UUID)` signature, breaks it too. The mod was written against
Cobblemon 1.6.1, which is where the `setScaleModifier` comment points.

Spawning fails loudly: the error log is explicit and the raid visibly spawns nothing. The
battle hook fails quietly, falling back. The scale hook fails quietly, logging a warning.

## EasyNPC

```text
de.markusbordihn.easynpc.data.display.DisplayAttributeType  → enum constant VISIBLE
de.markusbordihn.easynpc.data.type.ValueType                → enum constant BOOLEAN
<the NPC entity>.<some method>(DisplayAttributeType, ValueType, Boolean)
```

The third line is matched purely by parameter shape, so the method's name can change
freely. The two enum classes and constants are matched by name, so **they** cannot.

Why this instead of vanilla invisibility: EasyNPC entities override `isInvisible()` to read
their own `VISIBLE` display attribute, so `setInvisible(true)` is ignored. The display
attribute is the real lever, and it writes auto-syncing tracked data, so clients update on
their own.

Candidate NPCs are found by registry-id prefix `easy_npc`, inside a box expanded by 10
blocks around `npc.pos`, in the world `npc.pos.world` names. `npc.entityType` from the
config is **not** part of the search — it only feeds `{type}` in the command templates.

Three distinct failure modes, which `/cheeseraids herald <id> info` separates:

| Symptom | Meaning |
|---|---|
| `EasyNPC visibility API not found` at init | EasyNPC is absent, or the two enum classes moved |
| `no EasyNPC found within 10 blocks of <pos>` | wrong coordinates, wrong world, or an unloaded chunk |
| `N EasyNPC(s) near <pos>, 0 toggled via display-attr` | EasyNPC is there and found, but the three-parameter setter no longer exists. Also logs `setDisplayAttribute(VISIBLE,...) not found` |

A raid whose herald cannot be revealed does not stall: it announces
`/cheeseraids enter <id>` in chat instead.

**The dialogue is yours to configure.** CheeseRaids toggles visibility and glow. It does
not create the NPC, give it a skin, or bind an action to it. Making the herald an actual
entry point means configuring his dialogue in EasyNPC to run `/cheeseraids enter <id>`.
Nothing here does that, and nothing checks it was done — which is why an uncalibrated raid
can look like it works right up to the point where players cannot get in.

## CobbleSafari

```text
maxigregrze.cobblesafari.dungeon.PortalSpawnManager
  → spawnPortal(ServerWorld, BlockPos, ServerPlayerEntity)   [private; setAccessible]
  → spawnPortalNearPlayer(ServerPlayerEntity, boolean)        [fallback]
```

Both are matched by name **and** shape, and both are invoked statically (first argument
`null`). The primary method is private, which is a deliberate reach into another mod's
internals: it is the only way to place a portal at a chosen position rather than near a
player. The fallback is public but overworld-only, and ignores the position entirely.

The call needs an online participant as owner. The comment says CobbleSafari may throw on
a null owner, so the reward path requires one and otherwise skips straight to the command
fallback.

Three degradation steps, in order: the private method, then the public near-player one,
then `reward.portalCommand` if it is set, then a warning and no portal at all. The raid
ends cleanly in every case.

This is the most fragile hook in the mod, and the one most likely to break without
anybody noticing. A private method is not API; CobbleSafari owes it no stability
whatsoever.

## CheeseStore

```text
com.cheesestore.WalletManager
  → getInstance()
  → add(UUID, String, long)
```

Matched entirely by name. Called once per winner when `reward.fromage > 0`.
`ClassNotFoundException` is caught and ignored — CheeseStore not installed is a normal
condition, not an error. Any other exception logs `fromage reward failed`.

Since `reward.fromage` defaults to 0, the hook is inert unless you enable it.

---

## If you add an integration

The pattern to copy is `CobblemonSpawnHook`: a `private static volatile boolean init`, one
synchronized lazy resolver, an `available()` predicate, shape-based method matching, a
distinct log line for every failure point, and a return value the caller can check. The
pattern to avoid is a hook that returns `void` and logs nothing — `CobbleSafariHook` is
closer to that, and it is the one you will spend an evening on when it stops working.

---

← [The Hoopa Invasion](hoopa-invasion.md) · [Dashboard API](dashboard.md) →
