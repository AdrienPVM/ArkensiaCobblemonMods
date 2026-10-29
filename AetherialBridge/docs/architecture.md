[← AetherialBridge](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

Seven Java files, 485 lines, and more than half of that is one mixin. Small enough that
this page can name every line that matters, which is the point: when the mods this one
patches change, you need to know what to open first.

---

## Package layout

```text
com.aetherialbridge
├── AetherialBridgeMod.java        ModInitializer. Logs one line. No state.
├── FloatingIslandsProvider.java   the GenTypeProvider CheeseGuard discovers
└── mixin
    ├── NoiseChunkGeneratorMixin.java  264 lines: forced water picker + forced sea floor
    ├── SpringFeatureMixin.java        cancels lava springs below sea level -25
    ├── LakeFeatureMixin.java          cancels all lakes below sea level -25
    ├── MinerallyFeatureMixin.java     @Pseudo: cancels Minerally features below -25
    └── RaidDenFeatureMixin.java       @Pseudo: cancels raid dens where CheeseGuard says so
```

Two resources:

```text
src/main/resources/fabric.mod.json              2 entrypoints, 5 depends entries
src/main/resources/aetherialbridge.mixins.json  5 mixins, required: true, defaultRequire: 1
```

## Who owns what

**`AetherialBridgeMod`** is the `main` entrypoint and holds nothing but a `MOD_ID`
constant and a public static `LOGGER`. Its `onInitialize` writes one line and returns.
Other mods in this repository keep an `INSTANCE` singleton for cross-mod reflection;
this one has nothing worth reaching, so it does not.

**`FloatingIslandsProvider`** is the whole public surface. It is `final`, stateless, and
every method but one returns a constant. Fabric instantiates it when CheeseGuard asks
for the `cheeseguard-gentype` entrypoint, and CheeseGuard keeps that instance in
`GenTypeRegistry` for the life of the process. `createChunkGenerator` is the only method
that does work, and it allocates a new `NoiseChunkGenerator` on every call: once per
dimension creation, once per saved dimension at each server start.

**The five mixins** own no state either. Four of them are pure guards that return early
or cancel a call. `NoiseChunkGeneratorMixin` is the exception: it shadows vanilla's
private `settings` field to read the sea level, and its two helper methods
(`aetherialbridge$decorate`, `aetherialbridge$placeCoralCluster`) take everything they
need as parameters, including the `BlockPos.Mutable` scratch object, so nothing is
retained between chunks.

## State and disk

There is none. No configuration file, no persisted data, no caches, no static mutable
field anywhere in the mod. Restarting the server resets nothing because there was
nothing to reset.

The state that behaviour depends on lives in two other places. The noise settings come
from a datapack and are read through the server's registry manager on every
`createChunkGenerator` call, so a `/reload` that reloads datapacks is picked up at the
next dimension creation rather than being cached. The `raidDensEnabled` flag lives in
CheeseGuard's `config/cheeseguard_dimensions.json` and is read on every raid-den
placement attempt, which makes the dashboard toggle take effect on newly generated
chunks without a restart.

## Threading

Everything in this mod runs on a worldgen path, and none of it is this mod's choice.

- `createChunkGenerator` is called from CheeseGuard's `DimensionManager`, which the
  dashboard reaches through `WebDashboard.executeOnServerThread`. It is therefore always
  on the **server thread**, and the `GenTypeProvider` javadoc states that as a contract.
- The two `NoiseChunkGenerator` injections run wherever Minecraft runs chunk generation:
  on the **worldgen worker pool**, many chunks in parallel. They touch only their own
  `Chunk` object and local variables, which is what makes that safe. Adding any shared
  mutable field to that mixin would be a data race.
- `createFluidLevelSampler` is `static` in vanilla and the injection is static too, so
  it has no access to instance state and cannot develop one.
- `RaidDenFeatureMixin` reads CheeseGuard's live config list from a worldgen thread.
  `CheeseGuardWorldgenApi.isRaidDensEnabled` documents itself as safe to call from any
  thread, being a read over the config list, and the whole body is wrapped in a
  `catch (Throwable)` so a concurrent modification degrades into "dens allowed" rather
  than into a failed chunk.

No HTTP thread ever enters this mod. It registers no dashboard module and serves no
route.

## Mixins

| Mixin | Target | Injection | Remap | Why |
|---|---|---|---|---|
| `NoiseChunkGeneratorMixin` | `net.minecraft.world.gen.chunk.NoiseChunkGenerator` | `createFluidLevelSampler` at `HEAD`, cancellable | yes | force water instead of vanilla's lava band below y=-54 |
| | | `carve` at `RETURN` | yes | write bedrock, sea floor and water column, then decorate |
| `SpringFeatureMixin` | `net.minecraft.world.gen.feature.SpringFeature` | `generate` at `HEAD`, cancellable | yes | lava springs would tunnel through the ocean |
| `LakeFeatureMixin` | `net.minecraft.world.gen.feature.LakeFeature` | `generate` at `HEAD`, cancellable | yes | lava lakes, same reason |
| `MinerallyFeatureMixin` | 4 classes in `theone_ss.minerally.generation.feature` | `generate` at `HEAD`, cancellable, `require = 0` | no | its placement scan never converges and the watchdog kills the server |
| `RaidDenFeatureMixin` | `com.necro.raid.dens.common.worldgen.RaidDenFeature` | `generate` at `HEAD`, cancellable, `require = 0` | no | CobblemonRaidDens has no per-dimension blacklist |

What each one does in detail, and the terrain it produces, is in
[worldgen](worldgen.md).

The two third-party mixins are `@Pseudo` with `remap = false`, and each lists two method
names so the injector binds either way:

```java
@Inject(method = {
    "generate(Lnet/minecraft/world/gen/feature/util/FeatureContext;)Z",
    "method_13151(Lnet/minecraft/class_5821;)Z"
}, at = @At("HEAD"), cancellable = true, require = 0)
```

The first is the Yarn name, which only matches in a Yarn-mapped development environment;
the second is the intermediary name, which is what every mod jar — Minerally and
CobblemonRaidDens included — carries at runtime in production, and the one that actually
binds on a server. Listing both avoids needing a refmap for a class this mod cannot
compile against. `require = 0` means a mixin that binds to nothing is not an error, which
is how the mod stays loadable when Minerally or CobblemonRaidDens is absent. It is also a
real hazard, covered under [known weaknesses](#known-weaknesses).

## Why the overworld is safe

The README and the root README both claim the vanilla overworld is untouched. It holds,
and it holds for three separate reasons, each of which you can check yourself.

**1. No worldgen data targets `minecraft:`.** This mod ships no `data/` directory at all,
and the repackaged datapack it expects has had its `data/minecraft/` tree removed; the
noise settings live at `data/aetherial_islands/worldgen/noise_settings/floating.json`
instead. Nothing overrides `minecraft:overworld`'s noise settings, carvers, features or
structure sets. This is the part that would otherwise rewrite your whole server, and it
is the reason the repackaging exists.

**2. Four of the five mixins are gated on a sea level the overworld cannot reach.**
`NoiseChunkGeneratorMixin`, `SpringFeatureMixin`, `LakeFeatureMixin` and
`MinerallyFeatureMixin` all return immediately when the sea level is at or above -25. The vanilla overworld's is 63.
The injections are present in the overworld's code path, and the cost of that is a single
`int` comparison per call.

**3. The raid-den mixin defaults to allowing.** `isRaidDensEnabled` returns `true` for
any world it has no config for, and for any config whose flag is set, which is the
default. So it cancels nothing anywhere until an admin unchecks a box.

Three honest qualifications on that:

- The guard is a **sea-level test, not a dimension-id test**. Install another datapack
  that puts its sea below -25 and it gets the same treatment, including a forced sea
  floor it never asked for.
- `RaidDenFeatureMixin` is **not limited to custom dimensions**, despite what its javadoc
  says. CheeseGuard holds configs for the vanilla worlds too, so unchecking **Raid dens**
  on `minecraft:overworld` does suppress natural dens in the overworld. That is an admin
  action, not something this mod does on its own, but it is a global effect reached
  through a mod whose README says it leaves the overworld alone.
- The datapack may still carry things that are global. The DimScope build ships four
  Lithostitched `worldgen_modifier` files, one of which adds a surface rule to
  `minecraft:overworld` while the other three rewrite the climate and colours of four
  vanilla biomes server-wide. They do nothing without Lithostitched installed. With it, they apply, and
  that is the datapack's doing rather than this mod's.

## Where the coupling lives

Four distinct bets on code this mod does not own. In the order to check them when
something breaks after an update:

**1. The datapack identifier.** `FloatingIslandsProvider.java:31`

```java
private static final Identifier SETTINGS_ID = Identifier.of("aetherial_islands", "floating");
```

A rename on the datapack side breaks dimension creation, loudly: the `orElseThrow` at
line 45 produces a message naming the id it wanted, and CheeseGuard falls back to a void
generator. This is the failure mode you want, and it is the only one of the four that
announces itself.

**2. Vanilla method descriptors.** `NoiseChunkGeneratorMixin.java:45` and `:59`,
`SpringFeatureMixin.java:23`, `LakeFeatureMixin.java:20`. Full descriptors, remapped
through the refmap, with `defaultRequire: 1`. On a Minecraft version where any of those
signatures changed, the mixin fails to apply and the game **crashes at launch** with a
mixin apply error naming the method. Loud, and the right behaviour: this mod has no
sensible degraded mode for a missing sea floor.

**3. The `settings` shadow.** `NoiseChunkGeneratorMixin.java:41`

```java
@Shadow @Final
private RegistryEntry<ChunkGeneratorSettings> settings;
```

A private vanilla field, read to get the sea level in the `carve` injection. Same failure
class as the descriptors, same loudness.

**4. The two third-party class names.** `RaidDenFeatureMixin.java:22` and
`MinerallyFeatureMixin.java:26-31`. Five fully-qualified class names belonging to two
mods, with `require = 0`. **These fail silently.** If Minerally renames
`CrystalFeature` or moves its package, nothing is logged, nothing crashes, and the only
symptom is that the thing the mixin prevented comes back: in Minerally's case, watchdog
timeouts during chunk generation in a floating dimension. Check these last, but check
them with a profiler or a log full of `Empty height range` warnings rather than by
looking for an error.

## Extension points

**Writing your own gen-type.** This mod exists partly as the reference implementation,
and the recipe is short. Implement `com.cheeseguard.api.GenTypeProvider`, declare it
under the `cheeseguard-gentype` entrypoint in your `fabric.mod.json`, and depend on
`cheeseguard`. Return a namespaced `id()`, since CheeseGuard's dashboard distinguishes
plugin types from built-ins by looking for a `:` in the value. Use
`CheeseGuardWorldgenApi` for the hard parts: `freshOverworldBiomeSource` if you want
overworld biomes with your own seed, `withOverworldSurfaceRule` if you want modded
surface rules to apply, `ensureTerraBlenderReady` if you register a world yourself
rather than letting CheeseGuard do it. Declare the dependency `modCompileOnly` against a
`fileTree` glob on `../CheeseGuard/build/libs`, as this mod's `build.gradle` does, so
nothing of CheeseGuard is shaded into your jar.

**Changing the sea floor.** Everything is in `aetherialbridge$forceSeafloor` and
`aetherialbridge$decorate`. Both take their inputs as parameters and write only through
`chunk.setBlockState`, so a rewrite is self-contained. Keep the sea-level guard at the
top, keep writes inside the chunk's own 16×16 footprint, and do not introduce a field.

**Suppressing another misbehaving feature.** `MinerallyFeatureMixin` is the pattern:
`@Pseudo`, `remap = false`, both method names listed, `require = 0`, the whole body in a
`try/catch (Throwable)`, and the decision taken from
`ctx.getGenerator().getSeaLevel()`. Copy it, change the targets, and add the class to
`aetherialbridge.mixins.json`.

## Known weaknesses

Stated plainly, because finding these by surprise is worse.

- **The two pseudo-mixins fail silently.** `require = 0` is what makes the mod loadable
  without Minerally and CobblemonRaidDens, and it is also what makes a class rename
  undetectable. There is no startup log line saying whether either one bound.
- **The -25 threshold is a literal, repeated in four files.** Nothing shares it, nothing
  logs it, and nothing validates that the datapack's sea level is actually below it. A
  datapack edit that raises the sea level silently disables the entire repair pipeline.
- **The guard is on sea level, not on dimension.** Any other low-sea-level dimension on
  the server inherits the forced sea floor, with no way to opt out short of recompiling.
- **Two javadoc comments carry wrong numbers.** `SpringFeatureMixin` and
  `LakeFeatureMixin` say the threshold is -50, and `SpringFeatureMixin` adds that the
  dimension sits at -55. The code says -25 and the datapack says -30 —
  `MinerallyFeatureMixin`'s javadoc ("< -25", "sits at -30") is the one that is right.
  Believe the code.
- **A failed settings lookup degrades into a void world over real region files.**
  CheeseGuard catches the provider's exception and falls back to a flat void generator.
  Chunks already on disk still load with their terrain; new chunks come out empty. The
  only trace is two log lines, and nothing in the dashboard says the dimension is
  generating wrongly.
- **The sea floor ignores biome.** The same sand, gravel, clay and prismarine mix appears
  under a desert island and under a snowy taiga. No per-biome variation, by choice, but
  it does read as odd in a cold biome.
- **The decoration pass is not profiled.** It runs 256 columns with exactly 35
  `setBlockState` calls each for bedrock, floor and water (every y from `min_y` to the sea
  level, with this datapack), plus decoration, on every chunk of the
  dimension. It is cheap arithmetic and bounded, but nobody has measured the per-chunk
  cost, and chunk generation in a floating dimension is noticeably slower than in the
  overworld.
- **The dimension outlives the mod.** Remove the jar and CheeseGuard keeps the saved
  dimension, resolving it to a void generator every start. See
  [uninstalling](installation.md#uninstalling).
- **Tested in exactly one modpack.** It was written for one server's mod list, with one
  specific repackaged datapack, against one version each of Minerally and
  CobblemonRaidDens. No part of it has been tested against the stock Modrinth datapack,
  because that configuration cannot work by design.

---

← [Worldgen](worldgen.md) · [Troubleshooting](troubleshooting.md) →
