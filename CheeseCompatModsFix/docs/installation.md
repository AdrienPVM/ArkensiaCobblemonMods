[← CheeseCompatModsFix](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

> ⚠️ Read the [README](../README.md) first. This mod patches other people's mods against
> specific versions, it is published as a reference rather than as something to install,
> and no support is offered for it.

---

## Prerequisites

### Required

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | ≥ 0.16.0 |
| Java | ≥ 21 |
| Fabric API | the `fabric-command-api-v2` module is a hard dependency |

That is the whole of `depends` in `fabric.mod.json`. **Every mod this jar patches is
optional at runtime.** There is no `cobblemon`, no `cobblesafari`, no
`cobblemon_battle_tower` in the dependency block, on purpose: `CheeseCompatMixinPlugin`
checks `FabricLoader.isModLoaded(...)` for each target and switches the relevant mixins
off when the mod is absent, and the non-mixin fixes sit behind the same check in
`CheeseCompatMod.onInitialize()`. Installing this does not oblige you to install any of
the mods it knows about.

### Optional — the mods it patches

Each of these turns on a group of fixes if present and is ignored if not. The version
column is what the code was written against.

| Mod | Mod id | Version |
|---|---|---|
| [Cobblemon](https://modrinth.com/mod/cobblemon) | `cobblemon` | 1.6.1 |
| [Cobblemon Battle Tower](https://modrinth.com/mod/cobblemon-battle-tower-1-6-1) | `cobblemon_battle_tower` | 1.7.11+1.6.1 |
| [CobbleSafari](https://modrinth.com/mod/cobblesafari) | `cobblesafari` | 0.1.5 |
| [Cobblemon Trials Edition](https://modrinth.com/mod/cobblemon-trials-edition) | `cobblemontrialsedition` | 1.2.2 |
| [Cobblemon Raid Dens](https://modrinth.com/mod/cobblemonraiddens) | `cobblemonraiddens` | 0.4.7 |
| [Cobblemon Fight or Flight Reborn](https://modrinth.com/mod/cobblemon-fight-or-flight-reborn) | `fightorflight` | 0.9.0 |
| [Legendary Monuments](https://modrinth.com/mod/legendary-monuments) | `legendarymonuments` | 7.2 |
| [Daycare+](https://modrinth.com/mod/daycareplus) | `daycareplus` | 1.1.0 |
| [Enhanced Celestials](https://modrinth.com/mod/enhanced-celestials) | `enhancedcelestials` | 6.0.2.5 |
| [Minerally](https://modrinth.com/mod/minerally) | `minerally` | 1.21 |
| [ExtraQuests](https://modrinth.com/mod/extraquests) | `extraquests` | 1.5.3 |
| [JourneyMap](https://modrinth.com/mod/journeymap) | `journeymap` | 1.21.1-6.0.0-beta.47 |
| [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) | `numismatic-overhaul` | 0.3.4 |
| FTB Quests | `ftbquests` | 2101.1.13 (CurseForge) |
| StarAcademy | `academy` | v1.4.0+build.467 (CurseForge) |
| Cobblewarden Boss | `cobblewarden_boss` | 1.0.0 |
| MegaShowdown | `mega_showdown` | reached by reflection only, never declared |
| CobblemonAlphas | — | reached by reflection only, never declared |
| Rad Gyms | `rad-gyms` | reached by reflection only, never declared |

Nothing in this repository depends on this mod, and this mod depends on nothing in this
repository, with one soft exception: `BattleTowerDisconnectFix` reaches into
[CobbleCap](../../CobbleCap/) by reflection, if it is installed, to suspend level-cap
enforcement while the Battle Tower forces levels. CobbleCap's absence changes nothing.

### Which side does it go on?

**Both.** `fabric.mod.json` declares `"environment": "*"`.

- On the **server**: everything. All 35 server mixins and all the event-driven fixes.
- On the **client**: four mixins — the Battle Tower party-screen aspect fix, the two
  JourneyMap icon classes, and the Numismatic shop cull, all cosmetic or performance —
  plus the `client` entrypoint, `CheeseCompatClient`, which enables the Arkensia resource
  packs on first launch.

A server-only install is perfectly valid and loses only the client-side pieces. A
client-only install gets the four cosmetic mixins and the resource-pack activator, and
nothing else — ⚠️ the activator rewrites the player's `options.txt` on first
launch, which is the one thing in this jar that changes a file outside `config/`.

---

## ⚠️ Building needs two jars you have to supply

This is the one mod in the repository (together with
[CheeseFixQuests](../../CheeseFixQuests/)) that does **not** build from a clean clone.
`build.gradle` checks for two files before it does anything else:

```text
localdeps/ftb-quests-fabric-2101.1.13.jar
localdeps/academy-mc1.21.1-v1.4.0+build.467-fabric.jar
```

Those filenames are exact. The build references them by name, not by a glob.

FTB Quests and StarAcademy are **CurseForge-exclusive**: neither is on Modrinth nor on
any public Maven repository, so Gradle cannot fetch them. Download the **Fabric 1.21.1**
file of each from CurseForge and drop it in [`localdeps/`](../../localdeps/README.md) at
the **repository root** — not inside `CheeseCompatModsFix/`.

With either file missing, the build stops immediately and names what it is missing:

```text
FAILURE: Build failed with an exception.

* Where:
Build file 'CheeseCompatModsFix/build.gradle'

* What went wrong:
A problem occurred evaluating project ':CheeseCompatModsFix'.
>
  CheeseCompatModsFix cannot be built without these third-party jars.

  Missing from localdeps/:
    - academy-mc1.21.1-v1.4.0+build.467-fabric.jar

  FTB Quests and StarAcademy are CurseForge-exclusive — neither is on Modrinth nor
  on any public Maven repository, so Gradle cannot download them for you. [...]
```

It fails at configuration time, before compilation, so you get that message instead of a
wall of "cannot find symbol".

### Why a jar is needed for a mixin that targets a class by name

You might reasonably expect that a mixin written like this needs no compile dependency:

```java
@Mixin(targets = "theone_ss.minerally.block.OrePile")
```

It does. The Mixin annotation processor resolves and validates **every** target at
compile time and fails with `Mixin target ... could not be found` if the class is absent.
So the jar has to be on the compile classpath whether the target is imported or named.

`.gitignore` excludes `*.jar` under `localdeps/`, so nothing you put there can end up in
a commit. These mods belong to their authors and are not redistributed by this
repository.

### Everything else is automatic

The other sixteen dependencies resolve from Maven with no action from you: Modrinth
Maven for the mods, the Nucleoid Maven for sgui, and Fabric's own Maven. They are pinned
to Modrinth **version ids** rather than version numbers — `maven.modrinth:cobblemon:v77SHSXW`
rather than `:1.6.1` — because version numbers are not unique on Modrinth and resolving
by number can silently hand you the NeoForge jar. The reasoning is spelled out in
[`localdeps/README.md`](../../localdeps/README.md).

---

## Build

No sibling mod has to be built first — this mod is not part of the CheeseDash
dependency chain.

```bash
cd CheeseCompatModsFix
./gradlew build
```

The result is:

```text
build/libs/cheesecompatmodsfix-1.2.53+1.21.1.jar
```

Ignore `-sources.jar` and `-dev.jar` in the same folder; they are not for a server.

The version comes from `mod_version` in `gradle.properties`.
`src/main/resources/fabric.mod.json` declares `"version": "${version}"`, which
`processResources` expands, so the jar's filename and the version Fabric reports both read
`1.2.53+1.21.1`.

⚠️ **Do not read `CheeseCompatModsFix/fabric.mod.json`.** There is a second copy of that
file at the mod root, 27 lines long, read by nothing and packaged nowhere. It still claims
`"version": "1.1.0"` and describes the mod as covering only "Battle Tower, Legendary
Monuments, Arc Phone toggles" — a periphery it outgrew long ago. It is a leftover from an
older project layout and should be deleted. The file that matters is the one under
`src/main/resources/`.

---

## Install

1. Stop the server.
2. Delete any older `cheesecompatmodsfix-*.jar` from `mods/`. Two copies of a Fabric mod
   in one folder is a load failure, not a version choice.
3. Copy `cheesecompatmodsfix-1.2.53+1.21.1.jar` into `mods/`.
4. Start the server.

No sibling jar is needed alongside it.

## What the first start creates

One file:

```text
config/cheesecompatmodsfix.json
```

with the three defaults. Full reference in [configuration](configuration.md).

Three more appear later, as the fixes that own them need them — not at startup:

```text
config/cheesecompat_bt_backups/        on the first Battle Tower team lock-in
config/cheesecompat_daycare.json       when a Daycare+ pasture's mode is first stored
config/cheesecompatmodsfix_shrieker_cooldowns.json   on the first sculk shrieker boss
```

## Proving it loaded

The one line that always appears, from `CheeseCompatMod.onInitialize()`:

```text
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=enabled, PC=enabled, Sanctuary fix=ON
```

The three values echo your config. `heal=DISABLED` or `PC=DISABLED` in capitals means
you set that key to `false`.

Then one line per fix family that actually activated. On a server with the full stack
you should see a selection of:

```text
[CheeseCompat] Registered cobblemon:tatami_mat and tatami_block for CobbleSafari compatibility
[CheeseCompat] Safari dimensional ban events registered (Cobblemon 1.6.x compat)
[CheeseCompat] Safari ball fix registered for CobbleSafari dimensions
[CheeseCompat] Slingshot block registered for CobbleSafari dimensions
[CheeseCompat] Dungeon capture block registered for dungeon_underground
[CheeseCompat] EnhancedCelestials lunar warmup registered
[CheeseCompat] Warmed lunar data for minecraft:overworld
[CheeseCompat] Restricted /stopbattle <player> to operators
```

**The absence of a line is the signal that matters.** If you installed CobbleSafari and
do not see the safari lines, the mod id did not match and none of those fixes are
running. The `Loaded (v1.2.53)` line appears either way, so it does not tell you
anything about whether the fixes applied.

In-game, the quickest end-to-end check is `/stopbattle`:

```mcfunction
/stopbattle
```

```text
Battle stop requested.
```

Seeing that green message means the command tree registered, which means `onInitialize`
ran to completion.

## One harmless error you will see without CobbleSafari

```text
Couldn't parse element minecraft:loot_table/cheesecompat:blocks/cobblesafari_portal_frame
  - Unknown registry key ...: cheesecompat:cobblesafari_portal_frame
```

The portal frame block is registered conditionally; its loot table ships
unconditionally, so the table is orphaned when the block is absent. The server starts
normally. Verified on a 475-mod test server. It is noise, not a failure — but it is the
first thing anyone reading the log reports, so it is documented rather than hidden.

---

## Uninstalling

1. Stop the server.
2. Delete `cheesecompatmodsfix-*.jar` from `mods/`.
3. Start the server.

Nothing else is required. No mixin leaves a trace in a save, and no fix writes into
another mod's save data.

### What stays behind

| Path | Safe to delete | Note |
|---|---|---|
| `config/cheesecompatmodsfix.json` | yes | three booleans |
| `config/cheesecompat_daycare.json` | yes | Daycare+ reverts to its own breeding behaviour |
| `config/cheesecompatmodsfix_shrieker_cooldowns.json` | yes | cooldowns are only meaningful to this mod |
| `config/cheesecompat_bt_backups/*.dat` | ⚠️ **check first** | a party snapshot for a player whose restore never completed |

### What the world keeps

Three things survive in the save and need a decision.

**`cheesecompat:cobblesafari_portal` and `cheesecompat:cobblesafari_portal_frame`
blocks.** Any that are placed become unknown blocks. Break or replace them before you
remove the jar if you care about the world looking right.

**`cobblemon:tatami_mat` and `cobblemon:tatami_block` blocks.** These are registered by
*this* mod into *Cobblemon's* namespace, for CobbleSafari's worldgen. Remove the jar on
Cobblemon 1.6.1 and every tatami block placed in the world — including any CobbleSafari
generated — becomes an unknown block. On Cobblemon 1.7.x, where those blocks exist
upstream, nothing is lost.

**A party that was mid-restore.** If a player disconnected during a Battle Tower battle
and you remove the jar before they come back, the `.dat` backup is the only remaining
copy of their real party and nothing will ever read it. Check
`config/cheesecompat_bt_backups/` is empty before you remove the jar, or keep the
directory.

### Removing the Cobblewarden patches

If you also applied the patches from `patches/cobblewarden/` to your
`cobblewarden_boss-fabric-1.0.0.jar`, that jar is modified and removing this mod does not
undo it. Replace it with the original download. The procedure, and the original/patched
diff, are in `patches/cobblewarden/README.md` — which also notes that those patches are
**LiquidSilverAce's code under CC0-1.0**, not RoiCheese's, and keep their own
attribution.

---

← [CheeseCompatModsFix](../README.md) · [Getting started](getting-started.md) →
