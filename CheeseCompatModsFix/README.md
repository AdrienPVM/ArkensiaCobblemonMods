# CheeseCompatModsFix

> ## ⚠️ These are patches, not a mod
>
> Every fix here was written for **one specific modpack**, against specific versions
> of a dozen third-party mods, to solve problems that occurred on one server. The
> mixins target other people's internals. **Any update to any of those mods can break
> this silently.**
>
> It is published because the individual fixes are instructive and a few are
> genuinely reusable — not because it is meant to be installed as-is. **No support is
> offered.** If you install it and something breaks, the answer will be "remove it".

A collection of compatibility mixins and runtime patches: crash fixes, cross-mod
behaviour corrections, performance culls, and a few outright hacks.

**Minecraft 1.21.1 · Fabric · Java 21 · 64 files, ~6 700 lines**

---

## Requirements

- Fabric Loader ≥ 0.16.0, Fabric Command API v2, Java 21

Everything it patches is optional at runtime. `CheeseCompatMixinPlugin` checks
`FabricLoader.isModLoaded(...)` for each target and switches the relevant mixins off
when the mod is absent, so installing this does not require installing all thirteen
mods it knows about.

### ⚠️ Building needs two jars you must supply

**FTB Quests** and **StarAcademy** are CurseForge-exclusive and cannot be resolved by
Gradle. Download the **Fabric 1.21.1** files into [`localdeps/`](../localdeps/README.md):

```
ftb-quests-fabric-2101.1.13.jar
academy-mc1.21.1-v1.4.0+build.467-fabric.jar
```

The build stops immediately with a message naming what is missing. Everything else —
Cobblemon, Battle Tower, CobbleSafari, Trials Edition, Raid Dens, Legendary Monuments,
Minerally, Enhanced Celestials, Data Anchor, Daycare+, Fight or Flight, ExtraQuests,
Numismatic Overhaul, JourneyMap, sgui — is fetched automatically from Maven.

## What it patches

| Area | Fix |
|------|-----|
| **Battle Tower** | forces `isAcademyPresent` true to unlock NPC level forcing |
| **Minerally** | fixes an NPE crash when JustHammers AoE-breaks an `OrePile` |
| **StarAcademy** | cancels slingshot hits on uncatchable boss Pokémon; blocks `academy:slingshot` and safari balls inside CobbleSafari dimensions |
| **Enhanced Celestials** | lunar forecast fallback so `cheeseguard:*` dimensions inherit settings instead of crashing |
| **Daycare+** | IV inheritance correction for competitive mode |
| **Raid Dens** | boss facing, entity clearing, Tera block handling |
| **Numismatic Overhaul** | distance cull on shop block rendering (client FPS) |
| **JourneyMap** | icon fix for custom fakemon |
| **Cobblemon** | mega-stone remapping for a custom datapack, plus a `/stopbattle` command |

## Commands

`/stopbattle` — force-ends a stuck Cobblemon battle.

## Two scars worth reading before you touch this

**A battle watchdog once froze every battle on the server.** Version 1.2.51 shipped
`RctForcedSwitchFix`, which was supposed to detect stuck battles. It misidentified
every normal turn as stuck. It was disabled in 1.2.53. If you are tempted to write a
"detect stuck battle" heuristic, assume you will get it wrong on the first try and
test it on a server with real traffic.

**A held-item mixin broke every Pokémon held-item swap, server-wide.** Also reverted.
The `rusted_sword` consume behaviour it was trying to fix is deliberately unfixed.

## Arkensia specifics

`CacademyMegaStoneRemapPatch` maps 42 custom mega stones from the `CacademyMons`
datapack (shipped as `minecraft:brick` with custom model data) onto MegaShowdown's
Showdown simulator ids. It is entirely specific to that datapack and does nothing
without it.

`patches/cobblewarden/` contains a compatibility patch for **Cobblewarden Boss** by
LiquidSilverAce, used under its CC0 licence. It is not our work and keeps its own
attribution.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators. Every mod patched here belongs to its own authors and none is
redistributed.
