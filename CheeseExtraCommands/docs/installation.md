[← CheeseExtraCommands](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

CheeseExtraCommands has no sibling-mod dependency, so it builds and installs on its own.
It is the shortest install in the repository.

---

## Prerequisites

| | Version | Why |
|---|---|---|
| Minecraft | 1.21.x | `fabric.mod.json` declares `"minecraft": "~1.21"`; built and tested against 1.21.1 |
| Fabric Loader | ≥ 0.15.0 | declared in `depends`; the build uses 0.15.11 |
| Fabric API | any | declared as `"fabric-api": "*"`; the mod uses the command, lifecycle, networking and HUD-render modules |
| Java | 21 | `options.release = 21` in `build.gradle` |
| [Cobblemon](https://modrinth.com/mod/cobblemon) | 1.6.1 | optional at load, required for the commands to do anything |

Cobblemon sits in `suggests`, not `depends`. The build pulls it from the Modrinth Maven
as `compileOnly "maven.modrinth:cobblemon:v77SHSXW"`, which is the Fabric build of
1.6.1, and never links against it at runtime. Everything goes through
`CobblemonReflect`, which resolves classes by name and methods by signature. See
[the reflection layer](architecture.md#the-reflection-layer).

## Which side does it go on

**Both, with different consequences.** `fabric.mod.json` sets `"environment": "*"` and
declares two entrypoints: `main` (`CheeseExtraMod`) and `client`
(`CheeseExtraClient`).

| Install location | What you get |
|---|---|
| **Server only** | The four `/cheesecheck` subcommands work. The island-tour HUD never appears, because no client can decode the packet |
| **Server and client** | The commands work, and a player whose client also has the jar sees the island-tour HUD |
| **Client only** | Nothing. No command, no HUD, since the HUD is driven entirely by a server packet |

The server tolerates a client without the mod: `IslandTourTracker.update` wraps its
`ServerPlayNetworking.send` in a `try/catch` and logs a single DEBUG line if the send
fails. Nobody is disconnected, nothing is spammed.

## Build

```bash
cd CheeseExtraCommands
./gradlew build
```

The jar lands in `build/libs/`:

```text
build/libs/cheeseextra-1.0.0+1.21.1.jar
build/libs/cheeseextra-1.0.0+1.21.1-sources.jar
```

The version comes from `mod_version=1.0.0+1.21.1` in `gradle.properties` and the base
name from `archives_base_name=cheeseextra`. Ship the first file, not the `-sources` one.

The first build needs network access: Gradle resolves Cobblemon from
`https://api.modrinth.com/maven` and Kotlin's standard library from Maven Central. No
jar from this repository's `localdeps/` is involved, and `tools/build-all.sh` places
this mod in tier 3 only because that is where everything without a sibling dependency
goes.

## Install

1. Stop the server.
2. Copy `cheeseextra-1.0.0+1.21.1.jar` into `mods/`.
3. Delete any older `cheeseextra-*.jar` from `mods/`. Two versions of the same mod id
   stop Fabric from starting at all.
4. Start the server.

Distribute the same jar to clients only if you want the island-tour HUD. It is not
required for the commands.

## What the first launch creates

Nothing. There is no config file, no data directory, no registered item, block or
entity. The mod's whole persistent footprint lives in the world's vanilla scoreboard,
and only once a `/cheesecheck` call has written to it. See
[configuration](configuration.md).

## Proving it loaded

Three lines, in this order:

```text
[CheeseExtra] Initializing CheeseExtraCommands...
[CheeseExtra] Commands + Arkensia island-tour tracker registered.
[CheeseExtra] /cheesecheck command registered (ev, iv, pokemon, ballvariety)
```

The order is worth knowing. The first two come from `onInitialize`, which only
*registers a callback* for the command tree. The third is printed by
`CheeseCheckCommand.register`, which Fabric calls later, when the server builds its
command dispatcher. If you see the first two and never the third, the server never got
far enough to register commands, and the problem is elsewhere in the startup.

All three are logged under the logger name `cheeseextra`, not under a class name, so
grepping the log for `CheeseExtra` catches everything this mod ever prints.

## Verifying in game

With the server running, as an operator:

```mcfunction
/cheesecheck pokemon YourName pikachu quest_test 1
```

Three outcomes, each diagnostic:

| What the targeted player sees | Meaning |
|---|---|
| `Vérification réussie ! Tu as bien un pikachu dans ton équipe.` | the mod and Cobblemon are both working |
| `Tu n'as pas de pikachu dans ton équipe. Capture-en un et reviens !` | also working; the party genuinely has no Pikachu |
| `[CheeseCheck] Cobblemon non disponible.` | the mod loaded, Cobblemon did not |

If tab completion does not offer `cheesecheck` at all, you are below permission level 2.

Remember that the message goes to the **player named in the command**, so target
yourself for this test.

## Uninstalling

Remove the jar from `mods/` and restart. Nothing in the mod's own storage is left
behind, because it has none.

What does survive is everything it wrote into the world:

- every scoreboard objective a `/cheesecheck` call created or updated, with its scores
- the objectives the island-tour tracker *reads* (`jenny_intro`, `spawn_tour`,
  `<key>_met`), which it never creates and never deletes

Those are ordinary vanilla objectives. Clean them up with
`/scoreboard objectives remove <name>` if you want them gone. Any NPC dialogue or quest
task still branching on them keeps working, it never advances again.

---

← [CheeseExtraCommands](../README.md) · [Getting started](getting-started.md) →
