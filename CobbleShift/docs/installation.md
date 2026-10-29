[← CobbleShift](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

CobbleShift is declared `"environment": "*"` in its `fabric.mod.json`, which means the
same jar belongs on **both sides**. That is not a formality: the server owns the
transform state, the attributes and the commands, while all ten mixins are client-side
and every model swap happens in the client renderer. A server with the mod and clients
without it works, but nobody will see a Pokémon — the transformed player is simply
invisible. Install it on both.

---

## Requirements

| Requirement | Version | Why |
|---|---|---|
| Minecraft | `1.21.1` | `"minecraft": "~1.21.1"` in `fabric.mod.json` |
| Fabric Loader | `>= 0.16.0` | hard dependency |
| Fabric API | any | hard dependency — command registration, lifecycle events, networking |
| Java | `>= 21` | `options.release = 21` |
| Cobblemon | any | **optional** (`suggests`). Without it, every species lookup fails |
| CheeseDash | any | **optional** (`suggests`). Without it, the admin panel is absent |
| EasyNPC | any | **optional**, and not declared at all. Needed only for `/cshift transformnpc` |

Cobblemon is optional in the dependency graph and essential in practice. The mod never
imports a Cobblemon class; it reaches the party, the species registry and the base stats
through reflection, so it loads cleanly on a server without Cobblemon. What it does on
such a server is refuse every transform with `Failed: species '<name>' not found or
Cobblemon not available`. Treat Cobblemon as required unless you only want the jar
present for a future install.

EasyNPC is the odd one. `NpcTransformManager.isEasyNpc()` tests the target entity at
runtime, so the mod has no compile-time or load-time dependency on it, and
`fabric.mod.json` does not mention it. The NPC subcommands exist either way and refuse
with `This entity is not an EasyNPC` when the mod is missing.

## Getting the jar

### From a release

Drop `cobbleshift-1.0.0+1.21.1.jar` into `mods/` on the server and into `mods/` on each
client. The version string comes from `mod_version` in `gradle.properties`; read it there
rather than trusting this page after an update.

### From source

```bash
cd ArkensiaCobblemonMods/CheeseDash
./gradlew build
cd ../CobbleShift
./gradlew build
```

The order matters. `CobbleShift/build.gradle` resolves CheeseDash through a file tree
glob:

```groovy
modImplementation files(fileTree(dir: "../CheeseDash/build/libs",
    includes: ["cheesedash-*.jar"], excludes: ["*-sources.jar", "*-dev.jar"]))
```

That glob matches nothing until CheeseDash has been built at least once, and the
compilation of `ShiftApi` and `CobbleShiftDashboardModule` then fails on missing
`com.cheesedash.*` symbols. `tools/build-all.sh` walks the repository in an order that
avoids the problem.

The build writes to `CobbleShift/build/libs/`. Take the plain jar; ignore
`-sources.jar`.

## What the first start creates

Two files, both written lazily.

| Path | Written when | Contents |
|---|---|---|
| `config/cobbleshift.json` | the first start, if absent | the sixteen config keys at their defaults |
| `config/cobbleshift_npc_transforms.json` | on every NPC transform change, and on every `SERVER_STOPPING` — even with none, it then writes `[]` | the persisted NPC transforms |

`CobbleShiftConfig.load()` resolves its path through `FabricLoader.getConfigDir()`, so
it follows a relocated config directory. `NpcTransformManager` does not: its
`CONFIG_FILE` is the literal string `"config/cobbleshift_npc_transforms.json"`, resolved
against the server's working directory. On a normal server layout the two land in the
same place. On an unusual one they do not — see
[architecture](architecture.md#state-and-disk).

The client never creates the NPC file. It does create its own `config/cobbleshift.json`:
`CobbleShiftMod` is a `main` entrypoint, so it also runs on the client at launch and writes
the default config there. That copy only matters in single-player.

## Proving it loaded

These lines appear on the server during initialisation, in this order:

```text
[CobbleShift] Initializing...
[CobbleShift] TypeProjectileEntity registered
[CobbleShift] Default config created
[CobbleShift] Initialized successfully!
```

`Default config created` only appears on the very first start; later starts log
`Config loaded` instead. `TypeProjectileEntity registered` is the line to watch: the mod
force-loads that class with `Class.forName` purely to trigger its static entity-type
registration, and an `ERROR`-level `Failed to load TypeProjectileEntity!` there means the
entity type never registered and every attack will fail later.

Once the world is up:

```text
[CobbleShift] Server started
```

With CheeseDash installed, CheeseDash logs the module discovery itself; the `shift`
prefix then answers on port **44311**.

## Verifying in game

```mcfunction
/cshift list
```

An operator should get:

```text
═══ CobbleShift Status ═══
Active transforms: 0
Active abilities: 0
Global abilities: OFF
```

If `/cshift` is not in tab completion, either the mod did not load or your permission
level is below 2 — the whole command tree is gated at level 2.

Then confirm Cobblemon is actually reachable:

```mcfunction
/cshift species pikachu
```

Real base stats mean the reflection found Cobblemon. `Species 'pikachu' not found` with
Cobblemon installed means the reflective lookup broke, which is a different problem and
is covered in [troubleshooting](troubleshooting.md).

Finally, transform yourself and check that a client with the mod draws the model:

```mcfunction
/cshift transform <yourname> pikachu
/cshift revert <yourname>
```

## Uninstalling

Revert everything **before** removing the jar:

```mcfunction
/cshift revertall
/cshift revertallnpc
```

A clean stop would do the player half for you — `SERVER_STOPPING` calls `revertAll()` —
but it deliberately does **not** revert NPCs, it saves them. Run `revertallnpc` yourself
or the NPCs stay recorded in a file nothing will read any more.

Then stop the server, delete the jar from the server and from every client, and decide
what to do with the leftovers:

| Left behind | Harmless? | How to clear |
|---|---|---|
| `config/cobbleshift.json` | yes | delete it |
| `config/cobbleshift_npc_transforms.json` | yes | delete it |
| `cobbleshift:transform_speed` / `_health` / `_scale` attribute modifiers | **no** | they are persistent and live in the player data |
| infinite ambient Invisibility | **no** | same |
| a type projectile in flight | yes | it is discarded as an unknown entity on load |

The two rows marked **no** are the reason for reverting first. If you removed the jar while a player
was transformed, that player stays invisible and mis-scaled with no mod left to clean it
up; the fix is `/attribute <player> minecraft:generic.scale modifier remove
cobbleshift:transform_scale` for each of the three, plus `/effect clear <player>
minecraft:invisibility`. Putting the jar back is easier: `/cshift revertall` would find
nothing, since the transform list did not survive the restart, but a join with the jar
present triggers `cleanupStaleTransform()`, which does exactly this sweep automatically.

---

← [CobbleShift](../README.md) · [Getting started](getting-started.md) →
