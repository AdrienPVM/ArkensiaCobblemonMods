# localdeps — the four jars Gradle cannot fetch for you

**19 of the 21 mods build straight from a clean clone.** Every third-party
dependency is resolved from a Maven repository, so `./gradlew build` downloads what
it needs and you do nothing.

Two mods are the exception: **CheeseFixQuests** and **CheeseCompatModsFix**. They mix
into FTB Quests, FTB Teams, FTB Library and StarAcademy — four mods published *only*
on CurseForge, on neither Modrinth nor any public Maven repository. Gradle cannot
reach them, so you have to supply them yourself.

If you try to build either mod without them, the build stops immediately with a
message naming exactly what is missing. No cryptic compile errors.

## What to download

From CurseForge, the **Fabric 1.21.1** file of each:

| Filename expected in this folder | Needed by |
|----------------------------------|-----------|
| `ftb-quests-fabric-2101.1.13.jar` | CheeseFixQuests, CheeseCompatModsFix |
| `ftb-teams-fabric-2101.1.2.jar` | CheeseFixQuests |
| `ftb-library-fabric-2101.1.18.jar` | CheeseFixQuests |
| `academy-mc1.21.1-v1.4.0+build.467-fabric.jar` | CheeseCompatModsFix |

The filenames must match exactly — the build scripts reference them by name. A newer
version will probably work, but you will have to update the filename in
`CheeseFixQuests/build.gradle` and `CheeseCompatModsFix/build.gradle`.

`.gitignore` excludes `*.jar` here, so nothing you drop in this folder can end up in
a commit. These mods belong to their authors and are not redistributed by this
repository.

### Why a string-targeted mixin still needs the jar

Some of these mixins reference their target by name rather than by class:

```java
@Mixin(targets = "theone_ss.minerally.block.OrePile")
```

You might expect that to need no compile dependency. It does: the Mixin annotation
processor resolves and validates every target at compile time, and fails with
`Mixin target ... could not be found` if the class is absent. So the jar has to be on
the compile classpath either way.

---

## Everything else is automatic

For reference, here is what Gradle resolves on its own, and from where.

**Modrinth Maven** (`https://api.modrinth.com/maven`) — 18 dependencies:
Cobblemon (1.6.1 and 1.7.1), Cobblemon Battle Tower, Legendary Monuments,
CobbleSafari, Cobblemon Trials Edition, Cobblemon Raid Dens, Cobblemon Quests,
Cobblemon Fight or Flight Reborn, Daycare+, Enhanced Celestials, Data Anchor,
ExtraQuests, Easy NPC, Minerally, Numismatic Overhaul, Architectury API, JourneyMap.

JourneyMap is deliberately pinned to `6.0.0-beta.47` rather than the `6.0.0`
release: the release was built with Loom 1.14.10, which CheeseCompatModsFix's
Loom 1.10.5 refuses to read.

**Nucleoid Maven** (`https://maven.nucleoid.xyz/`) — sgui, by Patbox.

**CurseMaven** (`https://cursemaven.com`) — Radical Cobblemon Trainers API.

### Why the Modrinth coordinates look like gibberish

You will see dependencies written like this:

```gradle
compileOnly "maven.modrinth:cobblemon:v77SHSXW" // Cobblemon 1.6.1 (fabric)
```

`v77SHSXW` is a Modrinth **version id**, not a version number. This is deliberate.

Modrinth Maven resolves `maven.modrinth:<slug>:<version>` by *version number*, and
those numbers are not unique: Cobblemon publishes its Fabric and NeoForge builds
under the same `1.6.1`, and `easy-npc:6.2.0` matches twenty different releases.
Resolving by number silently hands you whichever one Modrinth returns first — which
may be the NeoForge jar, or a build for another Minecraft version. The build then
either succeeds against the wrong API, or fails with baffling mapping errors.

Version ids are unique, so pinning them makes the dependency unambiguous and the
build reproducible. Each one carries a comment with the human-readable version.

To update a dependency, find the version you want on Modrinth and take its version
id from the URL (`modrinth.com/mod/<slug>/version/<id>`) or from the API:

```
https://api.modrinth.com/v2/project/<slug>/version?loaders=["fabric"]&game_versions=["1.21.1"]
```

---

## Build order

Mods that depend on another mod in this repository read its **built jar**, not its
source. So build in dependency order:

```bash
cd CheeseDash    && ./gradlew build   # first — almost everything needs it
cd ../CheeseGuard && ./gradlew build  # needed by CheeseRaids and AetherialBridge
cd ../CheeseItems && ./gradlew build  # needed by CheeseStore
# then any other mod, in any order
```

If a build fails on a missing `cheesedash-1.0.0.jar`, you skipped the first step.

Those references pin version `1.0.0` in the filename, so if you bump a mod's version
you also need to update the paths in the mods that depend on it.
