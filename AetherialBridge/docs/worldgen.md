[← AetherialBridge](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](worldgen.fr.md)

# Worldgen

Two things happen in this mod, and they are worth separating. The first is a short
registration: one class tells CheeseGuard "here is another generation type, and here is
how to build its chunk generator". The second is a repair pipeline: four mixins that
make a world with its ocean at y=-30 survive features designed for a world with its
ocean at y=63.

Everything on this page is read out of `FloatingIslandsProvider` and the five classes in
`com.aetherialbridge.mixin`. Where a number comes from the datapack rather than from
this mod's code, it is marked as such, because you can change it and the mod will follow.

---

## The gen-type provider

`FloatingIslandsProvider` implements CheeseGuard's `GenTypeProvider` interface. The
interface has six methods, five of which return a constant:

| Method | Returns | Why that value |
|---|---|---|
| `id()` | `"aetherial-islands:floating"` | the key the dashboard sends back as `customGenTypeId`. Namespaced, as the interface requires. |
| `label()` | `"Aetherial Islands"` | the text on the dashboard card. |
| `icon()` | `"☁"` (`☁`) | one glyph, shown above the label. |
| `defaultSpawnY()` | `100.0` | applied to the dimension config at creation. Above the island band, far above the sea at y=-30. |
| `dimensionType()` | `DimensionTypes.OVERWORLD` | gives the dimension overworld light, height range (-64 to 320), day cycle and the vanilla overworld's `effects` for sky rendering. |
| `createChunkGenerator(server, config)` | a `NoiseChunkGenerator` | the actual work, below. |

The generator is built in six lines of substance:

```java
var cgsRegistry = server.getRegistryManager().get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
RegistryEntry<ChunkGeneratorSettings> settingsEntry = cgsRegistry.getEntry(SETTINGS_KEY)
    .orElseThrow(() -> new IllegalStateException("ChunkGeneratorSettings '" + SETTINGS_ID + "' not loaded — ..."));
BiomeSource biomeSource = CheeseGuardWorldgenApi.freshOverworldBiomeSource(server);
return new NoiseChunkGenerator(biomeSource, settingsEntry);
```

Three decisions are packed into that.

**The noise settings are looked up by id, not constructed.** `aetherial_islands:floating`
has to exist in the server's `ChunkGeneratorSettings` registry, which means it has to be
present in a loaded datapack. This mod ships no worldgen data at all; see
[the datapack namespace](installation.md#the-datapack-namespace) for what the file has to
be and where. When the lookup fails, the provider throws, CheeseGuard catches it and
falls back to a void generator.

**The biome source is a fresh overworld one, not the live overworld's.**
`CheeseGuardWorldgenApi.freshOverworldBiomeSource` builds a new `MultiNoiseBiomeSource`
from the `OVERWORLD` parameter list. CheeseGuard's own comment explains the reason:
reusing the running overworld's biome source would lock biome placement to the
overworld's coordinates and ignore the new dimension's seed, so two "random" dimensions
would get identical biome layouts. The visible consequence here is that the islands wear
the vanilla biome palette, including biomes other mods inject through TerraBlender, which
CheeseGuard initialises for runtime dimensions.

**No surface rule merge.** `CheeseGuardWorldgenApi` also offers
`withOverworldSurfaceRule`, which grafts the live overworld's surface rule onto a
settings object. This provider does not call it, so the surface comes from the
datapack's own `surface_rule`. That is the right call for terrain whose shape is nothing
like the overworld's, but it is worth knowing if you are debugging a modded biome whose
surface blocks look wrong in this dimension and correct in the overworld.

### How CheeseGuard reaches it

The handshake is one Fabric custom entrypoint and no API call from this side:

```json
"entrypoints": {
  "main": ["com.aetherialbridge.AetherialBridgeMod"],
  "cheeseguard-gentype": ["com.aetherialbridge.FloatingIslandsProvider"]
}
```

`GenTypeRegistry.discover()` runs once during `CheeseGuardMod.onInitialize()`, collects
every `cheeseguard-gentype` entrypoint, rejects a provider whose `id()` is null or
already taken, and freezes the map. Nothing can register later, and nothing unregisters.
`AetherialBridgeMod.onInitialize()` itself does one thing: log a line. The provider is
instantiated by Fabric, not by this mod.

From then on, CheeseGuard calls `createChunkGenerator` once per dimension creation and
once more per server start for every saved dimension it recreates. Both calls happen on
the server thread.

---

## The sea-level threshold

Four mixins change behaviour, and all four decide whether to act by the same test:

```java
if (settings.seaLevel() >= -25) return;
```

or its equivalent, `ctx.getGenerator().getSeaLevel() >= -25`. Above -25, every mixin
returns immediately and vanilla runs untouched. Below it, the repair pipeline engages.

The DimScope datapack sets `sea_level: -30`, so the dimension sits 5 below the threshold.
The vanilla overworld sits at 63, the nether at 32, the end at 0, so none of them come
close. That is the whole mechanism by which this mod leaves other dimensions alone, and
it is worth being clear about its shape: **the test is on the sea level of the chunk
generator, not on the dimension id.** Any dimension, from any mod or datapack, whose
noise settings put the sea below -25 gets the forced sea floor and the feature
suppression. There is no allowlist.

Two consequences:

- If you edit the datapack and raise `sea_level` above -25, the mixins stop firing and
  you get vanilla behaviour in a world shaped for something else: a dry shaft down to
  bedrock where the ocean should be, lava from -64 to -54, lava springs in the island
  band, and Minerally's placement scan looping.
- If you install a second low-sea-level datapack, it inherits all of this whether it
  wants to or not.

The javadoc on `SpringFeatureMixin` and `LakeFeatureMixin` says "sea_level &lt; -50" and
`SpringFeatureMixin` mentions -55; `MinerallyFeatureMixin`'s javadoc already says -25
(and that the dimension sits at -30). The code in all four is -25. The first two comments
are stale; the number that matters is -25.

---

## The forced sea floor

`NoiseChunkGeneratorMixin` holds two injections. Both target vanilla's
`NoiseChunkGenerator`, which every overworld-like dimension on the server uses, and both
are gated on the sea-level test above.

### Forcing water instead of lava

```java
@Inject(method = "createFluidLevelSampler(...)", at = @At("HEAD"), cancellable = true)
```

Vanilla's fluid level sampler answers "what fluid belongs at this position" with two
bands: lava below y=-54, and the configured fluid up to sea level. In a world whose
`min_y` is -64 and whose sea is at -30, that lava band is ten layers of lava across the
entire bottom of the world. The mixin replaces the sampler with a constant one: the
default fluid from the settings (water, in this datapack), at `seaLevel + 1`.

The datapack disables aquifers, which limits what the sampler is consulted for, but it is
still the source of the default fluid level, so the override matters.

### Writing the floor

```java
@Inject(method = "carve(...)", at = @At("RETURN"))
```

The second injection runs at the end of `carve`, and only for
`GenerationStep.Carver.AIR`. Chunk status order is what makes that placement work:
Minecraft runs `NOISE`, then `SURFACE`, then `CARVERS`, then `FEATURES`. Injecting at the
end of carving means the floor is written after the datapack's surface rules have already
painted, so it wins; features still run afterwards, which is exactly why the lava springs
needed a separate fix.

For every one of the 256 columns in the chunk, the mixin computes a floor height from
four sine terms, writes bedrock at `min_y`, fills the floor material up to that height,
and fills water from there to sea level:

| Quantity | Formula | With this datapack |
|---|---|---|
| bedrock | `min_y` | y=-64 |
| floor base | `min_y + 1` | y=-63 |
| floor ceiling | `seaLevel - 3` | y=-33 |
| floor top | `clamp(base + round(n + 14))` | between -63 and -33 |
| water column | `floorTop + 1` to `seaLevel` | up to and including y=-30 |

`n` is the sum of four sine/cosine terms at wavelengths roughly 140, 30, 10 and 90
blocks, amplitudes 8, 4, 1.5 and 3. Three phase offsets are derived from three different
16-bit slices of the world seed, so two dimensions with different seeds get different
floors. It is a hand-rolled noise function rather than a Minecraft `DensityFunction`:
cheap, periodic, and good enough for a surface a player sees from close up.

The floor material comes from a second, independent patch function evaluated per column:

| Patch value | Block |
|---|---|
| > 1.1 | `minecraft:prismarine` |
| > 0.55 | `minecraft:gravel` |
| < -1.05 | `minecraft:clay` |
| < -0.6 | `minecraft:dirt` |
| otherwise | `minecraft:sand` |

Sand is the common case, and the others appear as slow patches, on the order of 80 blocks
across.

### Decoration

A private `aetherialbridge$decorate` pass then walks the same 256 columns with a
`java.util.Random` seeded from the world seed and the chunk coordinates, so the result is
reproducible per chunk and independent of vanilla's feature randomness. Columns with less
than 2 blocks of water above the floor are skipped entirely.

One roll of `nextDouble()` per column decides everything, read in this order:

| Condition | Outcome |
|---|---|
| floor is prismarine and roll < 0.04 | coral cluster |
| roll < 0.005 | coral cluster, on any floor |
| floor is sand or gravel and roll < 0.06 | kelp column, 3 to 14 tall, capped at `depth - 1` |
| roll < 0.10 and depth ≥ 3 | tall seagrass, both halves |
| roll < 0.22 | seagrass |
| roll < 0.235 | sea pickle, 1 to 4 pickles, waterlogged |
| roll < 0.238 | sponge or wet sponge, 50/50 |
| otherwise | bare floor |

Roughly a quarter of the floor carries something, prismarine patches are the coral reefs,
and sand flats are the kelp forests. A coral cluster picks one of the five coral families
and places 4 to 9 blocks in a 3×3 footprint, each 1 or 2 tall, with a one-in-three chance
each of a coral fan or a coral plant on top, both waterlogged, never above `seaLevel - 1`.

One quirk, since it is visible if you look for it: the kelp branch bails out with
`continue` when the water is too shallow for a 3-block column (less than 4 deep), and
since the branches form one `else if` chain, a roll that landed on kelp never falls
through to seagrass: the column stays bare. Shallow sandy ground is therefore slightly
barer than the table suggests.

---

## The features that get suppressed

Three features come from elsewhere and misbehave in this terrain. Each gets its own
mixin, and none of them is configurable.

### Lava springs

`SpringFeatureMixin` injects into vanilla `SpringFeature.generate` and cancels the
placement when the configured fluid is lava and the generator's sea level is below -25.
Water springs are left alone, so the check is on `ctx.getConfig().state.getFluid()`
rather than on the feature id.

Vanilla overworld biomes schedule roughly twenty lava spring attempts per chunk, biased
towards the bottom of the world. In a world whose bottom is a filled ocean, each
successful one becomes a lava tunnel through the water, visible from the surface and
lethal to anyone swimming into it.

### Lakes

`LakeFeatureMixin` cancels every `LakeFeature.generate` call below the threshold,
without inspecting the config. The reasoning in its javadoc is that vanilla overworld
biome generation only schedules `lake_lava_surface` and `lake_lava_underground`, so there
are no water lakes worth preserving. That holds for vanilla. A mod or datapack that adds
a water lake feature to an overworld-tagged biome would lose it in this dimension, and the
loss would be silent.

### Minerally ores

`MinerallyFeatureMixin` is a `@Pseudo` mixin targeting four classes in
`theone_ss.minerally.generation.feature`: `OverWorldMineralFeature`,
`DeepslateMineralFeature`, `MineralRockFeature` and `CrystalFeature`. It cancels them all
below the threshold.

Minerally attaches its features through
`BiomeModifications.addFeatureInOverWorldBiome`, which means every biome tagged
`#minecraft:is_overworld` carries them, and the floating dimension uses the vanilla
overworld biome source, so it inherits the lot. On this geometry Minerally's
`PositionChecker.isValidPositionDown` never converges: the server logs
`Empty height range: [-55 absolute--60 absolute]` warnings and then the watchdog fires.
The mixin exists to stop a crash, not to tidy up ore distribution.

### Cobblemon raid dens

`RaidDenFeatureMixin` is the odd one out, and the only one whose behaviour an admin
controls. It is a `@Pseudo` mixin on
`com.necro.raid.dens.common.worldgen.RaidDenFeature`, and instead of testing the sea
level it asks CheeseGuard:

```java
var world = ctx.getWorld().toServerWorld();
String worldId = world.getRegistryKey().getValue().toString();
if (!CheeseGuardWorldgenApi.isRaidDensEnabled(worldId)) {
    cir.setReturnValue(false);
}
```

`isRaidDensEnabled` looks the world id up in CheeseGuard's dimension configs and returns
the `raidDensEnabled` flag, defaulting to `true` for any dimension it does not know. So
den placement is cancelled only where an admin unchecked the 🐲 **Raid dens** box, which
is offered both in the creation wizard and on the dimension detail page.

Two things follow that the class javadoc does not say. First, this has **nothing to do
with floating islands**: it applies to every dimension CheeseGuard has a config for, and
CheeseGuard registers configs for the vanilla worlds too, so unchecking the box on
`minecraft:overworld` disables natural raid dens in your main world. That is a feature if
you wanted it and a surprise if you did not. Second, the whole body is wrapped in
`try { ... } catch (Throwable ignored) {}`, deliberately, so a failed lookup lets worldgen
proceed rather than blocking a chunk.

The reason it lives in this mod rather than in CheeseGuard is mechanical: CobblemonRaidDens
has no per-dimension blacklist of its own, and CheeseGuard does not mix into third-party
mods. AetherialBridge is already a patch mod, so the hook was put here.

---

## What you can change, and where

| You want to change | Where |
|---|---|
| island shape, height, layers, biome climate | the Aetherial Islands datapack |
| sea level, world height, default block and fluid | `noise_settings/floating.json` in the datapack |
| the sea floor's materials or decoration | `NoiseChunkGeneratorMixin`, recompile |
| the -25 threshold | the four mixins, recompile. It is a literal in each one |
| spawn Y for new dimensions of this type | `defaultSpawnY()` in `FloatingIslandsProvider`, or per dimension from the dashboard |
| raid dens on or off, per dimension | the dashboard, no rebuild |
| anything else about the dimension | CheeseGuard's dimension settings |

---

← [Getting started](getting-started.md) · [Architecture](architecture.md) →
