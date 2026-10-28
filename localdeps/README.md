# localdeps — third-party jars you must supply yourself

Seven of the mods in this repository compile against third-party mods. Those jars
belong to their respective authors and **are not redistributed here**, so this folder
ships empty.

To build those mods, download the jars yourself and drop them in this folder using
**exactly the filenames listed below** — the build scripts reference them by name.

`.gitignore` excludes `*.jar` here, so anything you put in this folder stays out of
git.

## Do I need any of this?

Only for these seven mods:

| Mod | Needs jars from here |
|-----|----------------------|
| CheeseCompatModsFix | 15 — it patches a lot of mods |
| CheeseFixQuests | 6 |
| CheeseExtraCommands | 1 |
| CheesePatrol | 1 |
| CobbleCap | 1 |
| CheeseStore, CheesePurse, CobblemonDungeons | see *Per-mod `libs/`* below |

**The other 14 mods build with no extra downloads.** If you only want CheeseDash,
CheeseGuard, CheeseMarket, EggAndCheese, CobbleShift, CobbleCheeseScore, CheeseItems,
CheeseKart, CheeseMusic, CheeseVanity, CheeseRaids, ScheduleRestart or
AetherialBridge, you can ignore this folder entirely.

---

## Required files

All of these are `compileOnly` / `modCompileOnly` dependencies. They are needed to
compile against another mod's API — they are never bundled into the built jar, and the
built mod only needs them at runtime if you actually use the feature that targets them.

### Cobblemon ecosystem

| Filename | Needed by |
|----------|-----------|
| `Cobblemon-fabric-1.6.1+1.21.1.jar` | CheeseCompatModsFix, CheeseExtraCommands, CobbleCap |
| `cobblemon_battle_tower-fabric-1.7.11+1.6.1.jar` | CheeseCompatModsFix |
| `cobblesafari-fabric-1.21.1-0.1.5.jar` | CheeseCompatModsFix |
| `CobblemonTrialsEdition-fabric-1.2.2.jar` | CheeseCompatModsFix |
| `cobblemonraiddens-fabric-0.4.7+1.21.1.jar` | CheeseCompatModsFix |
| `daycareplus-1.1.0+1.21.1.jar` | CheeseCompatModsFix |
| `fightorflight-fabric-0.9.0.jar` | CheeseCompatModsFix |
| `cobblemon_quests-[1.21.1]-fabric-1.1.14.jar` | CheeseFixQuests |

### FTB stack

| Filename | Needed by |
|----------|-----------|
| `ftb-quests-fabric-2101.1.13.jar` | CheeseCompatModsFix, CheeseFixQuests |
| `ftb-teams-fabric-2101.1.2.jar` | CheeseFixQuests |
| `ftb-library-fabric-2101.1.18.jar` | CheeseFixQuests |
| `architectury-13.0.8-fabric.jar` | CheeseFixQuests |
| `ExtraQuests-1.5.3-1.21.1-Fabric.jar` | CheeseCompatModsFix, CheeseFixQuests |

### Other

| Filename | Needed by |
|----------|-----------|
| `easy_npc_bundle-6.2.0-fabric-1.21.1.jar` | CheesePatrol |
| `numismatic-overhaul-0.3.4+1.21.jar` | CheeseCompatModsFix |
| `Enhanced-Celestials-Fabric-1.21.1-6.0.2.5.jar` | CheeseCompatModsFix |
| `Data_Anchor-fabric-1.21.1-2.0.0.12.jar` | CheeseCompatModsFix |
| `LegendaryMonuments-7.2.jar` | CheeseCompatModsFix |
| `minerally-1.21-1.21.jar` | CheeseCompatModsFix |
| `academy-mc1.21.1-v1.4.0+build.467-fabric.jar` | CheeseCompatModsFix |

All of these are published on CurseForge and/or Modrinth. Search the mod name; the
version in the filename is the one the code was written against. A different version
may well work, but you will have to update the filename in that mod's `build.gradle`.

---

## Per-mod `libs/`

Four mods resolve jars from their own `libs/` folder rather than from here, because
Gradle needs them declared as a `flatDir` repository or a `fileTree`. Those folders are
also empty and also gitignored.

| Put the jar in | Exact filename | Why |
|----------------|----------------|-----|
| `CheeseCompatModsFix/libs/` | `sgui-1.6.1+1.21.1.jar` | referenced by name |
| `CheeseCompatModsFix/libs/` | `journeymap-fabric-1.21.1-6.0.0-beta.47.jar` | referenced by name |
| `CheeseStore/libs/` | `sgui-1.6.1.jar` | resolved as the coordinate `:sgui:1.6.1` through `flatDir`, and bundled into the built jar with Jar-in-Jar — **the filename must match exactly** |
| `CheesePurse/libs/` | any `*.jar` | picked up by `fileTree`, so the name does not matter. Use Numismatic Overhaul for 1.21.1 |
| `CobblemonDungeons/libs/` | `cobblemon-fabric-1.7.1+1.21.1.jar` | referenced by name. Note this is Cobblemon **1.7.1**, a different version from the one above |
| `CobblemonDungeons/velocity-party-forwarder/libs/` | `velocity-proxy.jar` | only needed for the optional Velocity proxy subproject |

---

## Build order

Mods that depend on another mod in this repository read its **built jar**, not its
source. So build in dependency order:

```bash
cd CheeseDash   && ./gradlew build     # first — almost everything needs it
cd ../CheeseGuard && ./gradlew build   # needed by CheeseRaids and AetherialBridge
cd ../CheeseItems && ./gradlew build   # needed by CheeseStore
# then any other mod, in any order
```

If a build fails with a missing `cheesedash-1.0.0.jar`, you skipped the first step.

Those references pin version `1.0.0` in the filename. If you bump a mod's version,
update the paths in the mods that depend on it.
