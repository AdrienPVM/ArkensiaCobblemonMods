[← AetherialBridge](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this page you will be standing on a floating island, in a dimension named
`cheeseguard:islands`, with the vanilla overworld exactly as you left it. The whole
thing takes about five minutes on a server that already runs CheeseDash and CheeseGuard,
and no part of it touches a configuration file by hand.

Nothing here is an AetherialBridge command, because the mod has none. What
AetherialBridge contributes is one extra card in a dashboard screen that CheeseGuard
already owns. The work is CheeseGuard's; the terrain is the datapack's.

---

## 1. Start the server and read three log lines

Install the four jars first ([installation](installation.md#install)), then start the
server and look for these, in this order:

```text
[AetherialBridge] Initialized — registers 'aetherial-islands:floating' GenTypeProvider with CheeseGuard.
[CG] GenTypeRegistry discovered 1 provider(s): [aetherial-islands:floating]
[CheeseDash] ✅ Started on port 44311 — http://localhost:44311/
```

If the second line says `discovered 0 provider(s)`, stop here: the dropdown in step 3
will not contain the entry you need. Go to
[troubleshooting](troubleshooting.md#the-gen-type-does-not-appear-in-the-dashboard).

If the server never finishes starting and the log ends on a dependency error naming
`cheeseguard` or `mr_aetherial_islands`, a required jar is missing. Both are declared
in `depends`, so Fabric aborts rather than starting without them.

## 2. Open the dashboard and pick your admin player

Browse to `http://<server>:44311`, log in with your token, and select your in-game name
under **Admin Player** in the header. The dashboard uses that name for anything
player-targeted, including the **TP** button you will press in step 6. Skip it and that
button shows an error toast instead of teleporting anyone.

Then open **Dimensions** in the sidebar. You should see your existing worlds listed
(`minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`), each one picked up
automatically by CheeseGuard as a built-in entry.

## 3. Create the dimension

Click **New dimension**. The wizard has four steps.

**Step 1, type.** Type `islands` in the name field. Below it sits a grid of generation
type cards: five built-ins (`VOID`, `FLAT`, `NORMAL`, `NETHER`, `END`) and then
**Aetherial Islands**, with a ☁ glyph and `aetherial-islands:floating` printed
underneath as its id. `VOID` is selected by default, so click the Aetherial Islands card
and check that its border turns amber.

The plugin cards always come after the built-ins, in the order CheeseGuard discovered
them. If the card is not there, the entrypoint handshake failed, and refreshing the page
will not help.

**Step 2, world.** Leave the seed empty unless you want a reproducible world. CheeseGuard
generates a random non-zero seed when you leave it blank, which matters more than it
looks: a seed of `0` would silently make the dimension reuse the overworld's terrain
seed. The *flat layers* field stays hidden, since it only applies to `FLAT`.

**Step 3, options.** The defaults are reasonable for a first look: mob spawning on, raid
dens on, PvP off, keep-inventory off, structures off. Two are worth a thought here:

- **Generate structures.** Off is the safer first run. Vanilla structures are placed
  against overworld-shaped terrain, and a village trying to settle on a floating island
  produces results that range from amusing to unusable.
- **Raid dens** (🐲). Leave it checked unless CobblemonRaidDens is installed and you do
  not want dens in this dimension. Unchecking it is what makes
  [`RaidDenFeatureMixin`](worldgen.md#cobblemon-raid-dens) cancel den placement for this
  world id, and it is the only one of the five mixins you control from the dashboard.

**Step 4, create.** Review the summary and confirm.

## 4. Confirm the generator was built from the datapack

The server log answers within a second:

```text
[AetherialBridge] Built NoiseChunkGenerator for dim 'islands' using aetherial_islands:floating (min_y=-64, height=384)
[CG] Runtime dimension created: cheeseguard:islands
[CG] Created custom dimension: islands (NORMAL)
```

Three things to read out of that:

- The first line is the proof the datapack namespace resolved. Without it, you got a
  void dimension instead, and the log carries a `falling back to VOID` line in its
  place. [Troubleshooting](troubleshooting.md#the-dimension-generated-as-empty-void) covers
  the recovery.
- `min_y=-64, height=384` are read live out of the loaded noise settings, so they are
  your datapack's real values, not constants from this mod.
- `(NORMAL)` is not a mistake. A plugin gen-type stores `NORMAL` in the `genType`
  field and the real selection in `customGenTypeId`; `genType` survives only to pick
  the dimension type and a fallback spawn Y.

The dashboard navigates to the new dimension's detail page by itself. Its spawn Y reads
**100**, which is the provider's `defaultSpawnY()` rather than CheeseGuard's usual 80 for
a `NORMAL` dimension. 100 is above the island band and well above the sea at y=-30, so
you arrive in open air and fall a short distance rather than materialising inside rock.

## 5. Teleport in

Press the **TP** button on the dimension row or in its detail header. The dashboard
sends your selected admin player to the dimension's spawn point.

The equivalent from the server console, if you would rather not use the dashboard:

```mcfunction
/execute in cheeseguard:islands run tp YourName 0.5 100 0.5
```

Expect a short freeze on the first entry. Chunks around spawn are being generated for
the first time, and the forced sea-floor pass runs per chunk on top of the normal noise
pass.

## 6. Look around, and check the two things that are easy to miss

Fly up. You should see islands at several heights, stacked in four noise layers, with
open air between them and the vanilla biome palette draped across the tops: forest on
one island, taiga on the next.

Then look down and check these two, because they are what the mixins are for:

- **At y=-30 there is an ocean surface**, and under it a floor of sand, gravel, clay,
  dirt and prismarine with coral clusters, kelp, seagrass and the occasional sponge.
  Bedrock sits at y=-64. If instead you find an empty shaft down to bedrock, the sea
  level in your datapack is above `-25` and `NoiseChunkGeneratorMixin` never fired.
- **No lava.** No surface lava lakes, no lava springs cutting through the water. If you
  find either, the same threshold explains it.

```mcfunction
/execute in cheeseguard:islands run tp YourName 0.5 -32 0.5
```

That puts you two blocks under the sea surface — the top water block is y=-30, and below
it the mixin overwrites every column, so y=-32 is water everywhere — which is the fastest way to see the floor.

## 7. Where to go next

The dimension is a normal CheeseGuard dimension from here on. Its detail page gives you
the world border, snapshots, per-player mode, time and weather locks, and the commands
run on entry and exit. None of that is AetherialBridge's doing, and all of it is
documented with [CheeseGuard](../../CheeseGuard/).

Two cases where you come back to this mod's documentation:

- You want to understand what the terrain is actually made of, or you need to change the
  sea floor. [Worldgen](worldgen.md).
- Something broke after a mod update. [Architecture](architecture.md#where-the-coupling-lives)
  names the exact lines that bind to third-party code, in the order you should check
  them.

---

← [Installation](installation.md) · [Worldgen](worldgen.md) →
