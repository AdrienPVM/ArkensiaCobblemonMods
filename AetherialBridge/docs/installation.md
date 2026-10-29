[← AetherialBridge](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

AetherialBridge is a server-side mod. `fabric.mod.json` declares
`"environment": "*"`, so Fabric will happily load the jar on a client too, but the only
entrypoints it registers are a `main` initialiser and a `cheeseguard-gentype` provider,
both of which run on the server. Players connecting to a server that uses it need
nothing installed.

---

## What you need

### Server

| | Version | Why |
|---|---|---|
| Minecraft | **1.21.1** | declared as `~1.21` |
| Fabric Loader | ≥ 0.15.0 | declared in `depends` |
| Fabric API | any 1.21.1 build | declared in `depends` |
| Java | 21 or newer | `options.release = 21` |
| [CheeseGuard](../../CheeseGuard/) | any | **hard dependency**, declared as `cheeseguard: "*"` |
| [CheeseDash](../../CheeseDash/) | any | pulled in by CheeseGuard, which depends on it |
| Aetherial Islands | see below | **hard dependency**, declared as `mr_aetherial_islands: "*"` |

Both `cheeseguard` and `mr_aetherial_islands` sit in `depends`, not in `suggests`. That
is a hard requirement in Fabric's sense: if either jar is absent, **Fabric Loader
aborts the launch** before any mod initialises, with a dependency-resolution error
naming what it could not find. There is no degraded mode, and the mod does nothing
useful without both.

The version range on Aetherial Islands is `"*"`, so Loader accepts any version it
finds. That is deliberately loose and it is not a compatibility statement: what
actually has to match is the **namespace** of the worldgen data, described in the next
section. A version that satisfies `"*"` can still fail at dimension-creation time.

### Client

Nothing. No client mod, no resource pack. Terrain is generated server-side and sent as
ordinary chunk data, and the biomes used are vanilla overworld biomes, so an unmodded
client renders the floating islands without complaint.

### Not required, but relevant

- **TerraBlender**, if other mods on your server use it to inject biomes. CheeseGuard
  initialises TerraBlender for every runtime-created dimension, including this one, so
  modded biomes render with their own features rather than as bare stone. Nothing to
  configure.
- **CobblemonRaidDens** and **Minerally**. Neither is required. Two of this mod's
  mixins exist only to suppress their features in low-sea-level dimensions, and both
  are declared `@Pseudo` with `require = 0`, so they bind if the class is present and
  are silently skipped if it is not. [Worldgen](worldgen.md#the-features-that-get-suppressed)
  explains what each one prevents.

---

## The datapack namespace

This is the part that catches people out, so it comes before the build instructions.

`FloatingIslandsProvider` looks up exactly one registry entry when CheeseGuard asks it
for a chunk generator:

```java
private static final Identifier SETTINGS_ID = Identifier.of("aetherial_islands", "floating");
```

That is a `ChunkGeneratorSettings` object which must exist at
`data/aetherial_islands/worldgen/noise_settings/floating.json` in some loaded datapack.
If the lookup fails, the provider throws, and the message is explicit:

```text
ChunkGeneratorSettings 'aetherial_islands:floating' not loaded — is the
aetherial-islands-2.0.2-dimscope.jar present in mods/?
```

**The published Aetherial Islands download does not contain that file.** Upstream, the
datapack works by *overriding* `data/minecraft/worldgen/noise_settings/overworld.json`,
which is how a worldgen datapack normally replaces the terrain of a whole world. That
approach is the opposite of what this mod is for: it would turn your existing overworld
into floating islands for every player.

So AetherialBridge expects a **repackaged** build, where:

- the noise settings have been moved from `data/minecraft/worldgen/noise_settings/overworld.json`
  to `data/aetherial_islands/worldgen/noise_settings/floating.json`, along with every
  density function and noise it references, which also moves into the
  `aetherial_islands` namespace;
- the datapack's overrides of vanilla carvers, placed features and structure sets are
  removed, since those are global by nature and cannot be scoped to one dimension;
- nothing at all is left under `data/minecraft/`.

The build this mod was written against is named `aetherial-islands-2.0.2-dimscope.jar`
and identifies itself as `mr_aetherial_islands` version `2.0.2-dimscope`. Its noise
settings read:

| Field | Value |
|---|---|
| `sea_level` | `-30` |
| `noise.min_y` | `-64` |
| `noise.height` | `384` |
| `default_block` | `minecraft:stone` |
| `default_fluid` | `minecraft:water` |
| `aquifers_enabled` | `false` |
| `ore_veins_enabled` | `false` |

`sea_level: -30` is the number everything else in this mod hinges on: it is below the
`-25` threshold the four mixins test for, which is what switches on the forced sea
floor and the feature suppression. [Worldgen](worldgen.md#the-sea-level-threshold) goes
into why that threshold was chosen and what breaks if your datapack sits above it.

> ⚠️ If you repackage the datapack yourself, check what else it carries. The DimScope
> build still ships four Lithostitched `worldgen_modifier` files, and they are **not**
> dimension-scoped: one adds a surface rule to `minecraft:overworld`, and two rewrite
> the climate and foliage colours of `minecraft:taiga` and `minecraft:stony_shore`
> globally. They are inert as long as Lithostitched is not installed. Install
> Lithostitched and they apply server-wide, which breaks the overworld-untouched
> property that AetherialBridge's own code upholds.

---

## Build

AetherialBridge compiles against CheeseGuard's **built jar**, through a `fileTree` glob
on `../CheeseGuard/build/libs`, not against its source. CheeseGuard in turn compiles
against CheeseDash. Three builds, in this order:

```bash
cd CheeseDash         && ./gradlew build     # gradlew.bat on Windows
cd ../CheeseGuard     && ./gradlew build
cd ../AetherialBridge && ./gradlew build
```

From the repository root, `tools/build-all.sh` walks every mod in the right order.

The jar you want:

```text
AetherialBridge/build/libs/aetherialbridge-1.0.0+1.21.1.jar
```

Ignore `aetherialbridge-1.0.0+1.21.1-sources.jar`. The version comes from
`mod_version` in `gradle.properties`.

A compile error naming missing `com.cheeseguard.api` or `com.cheeseguard.data` classes
means CheeseGuard was not built first. The dependency is declared `modCompileOnly`, so
nothing from CheeseGuard is bundled into this jar; the classes have to be present at
runtime, which they are, because CheeseGuard is a hard dependency.

## Install

1. Stop the server.
2. Copy into `mods/`:
   - `cheesedash-1.0.0+1.21.1.jar`
   - `cheeseguard-1.0.0+1.21.1.jar`
   - `aetherialbridge-1.0.0+1.21.1.jar`
   - your Aetherial Islands jar, repackaged as described above
3. Confirm Fabric API is in `mods/` as well.
4. Start the server.

Order inside the folder does not matter; Fabric resolves dependencies itself.

## What the first launch creates

Nothing. AetherialBridge writes no file, creates no directory and reads no
configuration. It has no state of its own: the provider is a stateless object with five
constant-returning methods and one factory method.

Files do appear, but they belong to CheeseGuard:

```text
config/cheeseguard_dimensions.json     one entry per dimension, created on first save
```

The first time you create an Aetherial Islands dimension, CheeseGuard adds an entry
there with `"customGenTypeId": "aetherial-islands:floating"`, and Minecraft starts
writing region files under:

```text
<level-name>/dimensions/cheeseguard/<dimension-id>/
```

## Verifying it loaded

One line from this mod, during mod init:

```text
[AetherialBridge] Initialized — registers 'aetherial-islands:floating' GenTypeProvider with CheeseGuard.
```

That line only proves the jar loaded. The line that proves the handshake worked comes
from CheeseGuard, which scans the `cheeseguard-gentype` entrypoint once at init:

```text
[CG] GenTypeRegistry discovered 1 provider(s): [aetherial-islands:floating]
```

If you see `discovered 0 provider(s): []`, CheeseGuard loaded and AetherialBridge did
not register. See [troubleshooting](troubleshooting.md#the-gen-type-does-not-appear-in-the-dashboard).

Then check the API, which is what the dashboard dropdown reads:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/cheeseguard/dimensions/gen-types
```

The plugin entry sits after the five built-ins:

```json
{
  "id": "aetherial-islands:floating",
  "label": "Aetherial Islands",
  "icon": "☁",
  "defaultSpawnY": 100.0,
  "kind": "plugin"
}
```

`kind` tells the dashboard which field to send back when creating a dimension:
`builtin` goes out as `genType`, `plugin` as `customGenTypeId`.

The last confirmation only arrives when you actually create a dimension, because that
is when the chunk generator gets built:

```text
[AetherialBridge] Built NoiseChunkGenerator for dim 'islands' using aetherial_islands:floating (min_y=-64, height=384)
```

Seeing that line means the datapack namespace is right. [Getting
started](getting-started.md) takes it from there.

---

## Uninstalling

The dimension outlives the mod, which is the one thing to be careful about.

1. **Delete every Aetherial Islands dimension first**, from the CheeseDash
   **Dimensions** view, while the mod is still installed. Deleting a dimension unloads
   its world, removes the entry from `config/cheeseguard_dimensions.json`, and deletes
   `<level-name>/dimensions/cheeseguard/<id>/` from disk.
2. Stop the server.
3. Remove `aetherialbridge-1.0.0+1.21.1.jar` from `mods/`. Remove the Aetherial Islands
   jar too, unless something else uses it.
4. Start the server.

Skip step 1 and the dimension entry stays in `cheeseguard_dimensions.json` with a
`customGenTypeId` nothing can resolve. CheeseGuard recreates saved dimensions at every
start, finds no provider for that id, logs it, and falls back to a void generator:

```text
[CG] Unknown customGenTypeId 'aetherial-islands:floating' — falling back to VOID
```

The world then loads with its existing region files intact, so terrain you already
generated is still there; chunks generated from then on are empty. That is recoverable
(put the jar back), but a player standing in a freshly generated area will fall through
nothing until they hit the void.

Nothing else is left behind. No config file of this mod's own exists to delete, and
removing the jar also removes its five mixins, so vanilla worldgen and the two
third-party features return to their stock behaviour immediately.

---

← [AetherialBridge](../README.md) · [Getting started](getting-started.md) →
