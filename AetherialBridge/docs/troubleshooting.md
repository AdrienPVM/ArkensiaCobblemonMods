[← AetherialBridge](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

Most problems with this mod are one of two things: the datapack namespace is wrong, or
the sea level is above -25. The symptoms below are sorted roughly by how often they come
up.

---

## The server will not start

**Cause.** A `depends` entry is missing. `fabric.mod.json` declares five, and two of them
are other mods:

```json
"depends": {
  "fabricloader": ">=0.15.0",
  "fabric-api": "*",
  "minecraft": "~1.21",
  "cheeseguard": "*",
  "mr_aetherial_islands": "*"
}
```

Fabric Loader resolves dependencies before any mod initialises, so the launch aborts with
a dependency-resolution error naming what it could not satisfy. The log ends there, with
no `[AetherialBridge]` line at all.

**Fix.** Put the named jar in `mods/`. For `cheeseguard`, remember that CheeseGuard
itself depends on CheeseDash, so you need both. For `mr_aetherial_islands`, that is the
mod id of the Aetherial Islands datapack packaged as a mod; the jar this mod was written
against is `aetherial-islands-2.0.2-dimscope.jar`.

Removing this mod's jar is also a legitimate fix if you do not want floating dimensions:
nothing else in the repository depends on it.

## The gen-type does not appear in the dashboard

**Cause.** The `cheeseguard-gentype` entrypoint was not discovered. CheeseGuard scans
once, during its own `onInitialize`, and freezes the registry afterwards. The log tells
you which side failed:

```text
[CG] GenTypeRegistry discovered 0 provider(s): []
```

That means CheeseGuard ran its scan and found nothing. Either this mod did not load at
all (no `[AetherialBridge] Initialized` line earlier in the log), or an older build of
the jar is installed whose `fabric.mod.json` lacks the entrypoint.

A duplicate id produces a different line:

```text
[CG] Duplicate GenTypeProvider id 'aetherial-islands:floating' — ignoring
```

Two jars with the same provider id are installed, most likely two versions of this mod.

**Fix.** Check `mods/` for exactly one `AetherialBridge-*.jar`. Then restart: the
registry is built once at init, so no amount of refreshing the dashboard will populate
it. Confirm with the API rather than the browser, since the dropdown caches:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/cheeseguard/dimensions/gen-types
```

## Dimension creation fails with "Unknown customGenTypeId"

**Cause.** The dashboard sent `customGenTypeId: "aetherial-islands:floating"` and
`GenTypeRegistry.get` returned null, so `DimensionsApi` answered 400. This is the same
root cause as the previous symptom, reached from the other direction: a dashboard page
loaded while the provider was registered, then the server restarted without the jar.

**Fix.** Reload the dashboard page, which refetches `gen-types`. If the entry is really
gone, see above.

## The dimension generated as empty void

**Cause.** The provider threw, and CheeseGuard fell back to a flat void generator rather
than failing the creation. There are two messages, and you need the first one to know
which:

```text
[CG] Plugin gen-type 'aetherial-islands:floating' failed: ChunkGeneratorSettings
'aetherial_islands:floating' not loaded — is the aetherial-islands-2.0.2-dimscope.jar
present in mods/? — falling back to VOID
```

That is the namespace problem: `data/aetherial_islands/worldgen/noise_settings/floating.json`
is not in any loaded datapack. The stock Modrinth download of Aetherial Islands does not
contain it, because upstream the datapack overrides `minecraft:overworld` instead. See
[the datapack namespace](installation.md#the-datapack-namespace).

```text
[CG] Unknown customGenTypeId 'aetherial-islands:floating' — falling back to VOID
```

That is the registry problem: the dimension was saved by a server that had this mod, and
the current server does not.

**Fix.** Install the correctly namespaced datapack, or put the mod jar back, then
**delete and recreate the dimension**. Chunks already generated as void stay void: the
generator only decides what new chunks look like. If the dimension is only a few minutes
old, deleting it from the dashboard and making a new one is the fastest route. If it
already holds builds, CheeseGuard's snapshot and reset tools are the alternative, at the
cost of losing the generated terrain anyway.

## At the bottom of the world there is a dry shaft, or lava, instead of an ocean

**Cause.** The sea level in your noise settings is at or above -25, so the four mixins
never fired. Each one begins with the same test, and the DimScope datapack is at -30
specifically to sit below it.

Without the mixins you get vanilla behaviour in terrain that was not built for it: no
forced sea floor, and vanilla's fluid level sampler putting lava everywhere below y=-54.

**Fix.** Check `sea_level` in `data/aetherial_islands/worldgen/noise_settings/floating.json`.
Put it below -25. Changing the threshold instead means editing the literal in four mixin
files and rebuilding, which is described in
[worldgen](worldgen.md#the-sea-level-threshold).

## Lava tunnels run through the water

**Cause.** `SpringFeatureMixin` did not cancel the lava springs. Either the sea level is
above -25, which is the previous symptom, or the mixin failed to apply, which would have
crashed the game at launch instead. In practice this is always the sea level.

A third possibility is a mod that adds its own lava-placing feature to overworld-tagged
biomes through a class other than `SpringFeature` or `LakeFeature`. Those two are the only
ones suppressed, so anything else gets through.

**Fix.** Confirm the sea level first. If it is below -25 and you still have lava, find out
which feature placed it: `/locate` will not help, but checking what the mod list adds to
`#minecraft:is_overworld` biomes will. Suppressing a third one needs a new mixin, and
`MinerallyFeatureMixin` is the pattern to copy.

## The server freezes or the watchdog kills it during chunk generation

**Cause.** Most likely Minerally's placement scan. Its features are attached to every
biome tagged `#minecraft:is_overworld`, the floating dimension uses the vanilla overworld
biome source, so they come along, and on this geometry
`PositionChecker.isValidPositionDown` never converges. The tell is a log full of:

```text
Empty height range: [-55 absolute--60 absolute]
```

followed by a watchdog message about a single tick taking too long.

`MinerallyFeatureMixin` exists to prevent exactly this. If it is happening anyway, the
mixin did not bind, and that failure is **silent**: it is declared `@Pseudo` with
`require = 0`, so a renamed or moved Minerally class produces no error at all. The four
class names it targets are in `MinerallyFeatureMixin.java:26-31`.

**Fix.** Compare those four names against the Minerally jar you have installed. If they
differ, update the `targets` list and rebuild. Removing Minerally also works, and is
faster to test: if the freeze stops, the diagnosis is confirmed.

Do not confuse this with the ordinary slowness of a first visit. Generating a floating
dimension is genuinely slower than generating overworld chunks, because the forced sea
floor writes 35 blocks per column on top of the normal noise pass. A few
seconds of stutter on first entry is expected; a watchdog kill is not.

## Raid dens stopped spawning in the overworld

**Cause.** Someone unchecked the 🐲 **Raid dens** box on `minecraft:overworld` in the
CheeseDash dimension view. `RaidDenFeatureMixin` reads
`CheeseGuardWorldgenApi.isRaidDensEnabled(worldId)` for **every** dimension, not only for
the floating ones, and CheeseGuard keeps a config entry for the vanilla worlds too. Its
javadoc claims it applies to custom dimensions; the code does not restrict it.

**Fix.** Re-check the box. New chunks generate dens again; chunks already generated
without them stay as they are, since feature placement happens once per chunk.

The reverse case has the same explanation: if dens appear in a dimension where you did
not want them, the flag defaults to enabled, and nothing cancels placement until you turn
it off.

## Editing a config file changes nothing

**Cause.** There is no AetherialBridge configuration file. The mod reads none and writes
none. Looking for `config/aetherialbridge.json` will not find it, because it does not
exist.

**Fix.** Find the real owner of the setting:

| Setting | Lives in |
|---|---|
| sea level, world height, terrain shape | the Aetherial Islands datapack |
| spawn point, PvP, difficulty, keep-inventory, time and weather locks, raid dens, world border | CheeseGuard, in `config/cheeseguard_dimensions.json` or from the dashboard |
| dashboard port and token | CheeseDash, in `config/cheesedash.json` |
| sea floor materials, decoration rates, the -25 threshold | this mod's source. Recompile |

## A command is rejected, or not found

**Cause.** AetherialBridge registers no command. `/aetherialbridge` and any variation of
it do not exist, and never did.

**Fix.** Dimensions are created and managed from the CheeseDash **Dimensions** view.
Moving around is vanilla:

```mcfunction
/execute in cheeseguard:islands run tp YourName 0.5 100 0.5
```

CheeseGuard's own `/cg` command tree covers zones, events and player worlds, and requires
permission level 2 for its administrative branches.

## The game crashes at launch with a mixin error

**Cause.** One of the three vanilla-targeting mixins failed to apply. `aetherialbridge.mixins.json`
sets `"required": true` and `defaultRequire: 1`, so a vanilla method whose signature
changed is a hard failure rather than a silent skip. The crash report names the mixin
class and the method it could not find.

This happens when the mod is run on a Minecraft version other than 1.21.1. The
`depends` entry says `~1.21`, which is looser than the mod actually is.

**Fix.** Run it on 1.21.1. Porting means updating the descriptors at
`NoiseChunkGeneratorMixin.java:45` and `:59`, `SpringFeatureMixin.java:23` and
`LakeFeatureMixin.java:20`, plus the `@Shadow` field at
`NoiseChunkGeneratorMixin.java:41`, and rebuilding against the new Yarn mappings.

## Players arrive inside rock, or fall into the void

**Cause.** The spawn Y is not where the terrain is. New dimensions of this type get
`spawnY = 100` from the provider's `defaultSpawnY()`, which is above the island band, so
a player arrives in open air and falls a short way. If someone edited the spawn point to a
coordinate inside an island, or to one over open air with nothing below, you get the other
two outcomes.

**Fix.** Set the spawn point from the dimension detail page. The **Use my position**
button writes your current coordinates, which is the quickest way to put spawn on an
island you have actually looked at.

---

## Filing a useful bug report

Open an issue at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) with:

1. **The whole `logs/latest.log`**, or at minimum every line matching
   `AetherialBridge`, `[CG]`, `GenTypeRegistry`, `Mixin` and `Empty height range`. The
   two lines that identify almost every problem are
   `[CG] GenTypeRegistry discovered N provider(s)` from startup and
   `[AetherialBridge] Built NoiseChunkGenerator for dim ...` from dimension creation.
2. **The exact Aetherial Islands jar you are using**, filename and the `version` field
   from its `fabric.mod.json`. "The Modrinth one" is the answer in most broken cases, and
   it is the one configuration that cannot work.
3. **The `sea_level`, `noise.min_y` and `noise.height`** values from your
   `floating.json`.
4. **Whether Minerally, CobblemonRaidDens, Lithostitched or TerraBlender are installed**,
   with versions. Two of the five mixins exist only because of the first two.
5. **The dimension's entry** from `config/cheeseguard_dimensions.json`, which shows
   `customGenTypeId`, `genType`, `seed`, `spawnY` and `raidDensEnabled` in one place.

A screenshot of the terrain is worth including for anything visual, taken with F3 open so
the coordinates and the dimension id are in frame.

Bear in mind what this mod is: a patch against three mods it does not own, built for one
modpack. A report that says "it broke after Minerally updated" is useful information, but
the answer is a code change in the `targets` list, not a setting.

---

← [Architecture](architecture.md) · [AetherialBridge](../README.md) →
