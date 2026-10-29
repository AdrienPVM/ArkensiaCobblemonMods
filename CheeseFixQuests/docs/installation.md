[← CheeseFixQuests](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

---

## Prerequisites

| | |
|---|---|
| **Required** | Minecraft 1.21.1 · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Required mods** | [FTB Quests](https://www.curseforge.com/minecraft/mc-mods/ftb-quests-fabric) · [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-fabric) (and FTB Library, which both pull in) |
| **Optional mods** | [Cobblemon Quests](https://modrinth.com/mod/cobblemon-quests) · [ExtraQuests](https://modrinth.com/mod/extraquests) |

`fabric.mod.json` declares `ftbquests` and `ftbteams` in `depends` with no version bound,
and `cobblemon_quests` and `extraquests` in `suggests`. Without FTB Quests or FTB Teams the
jar will not load at all. The optional two each have their own compatibility mixin, which
simply does not apply when the mod is absent.

The versions this mod is verified against are the ones pinned in `build.gradle`:
**FTB Quests 2101.1.13**, FTB Teams 2101.1.2, FTB Library 2101.1.18, Cobblemon Quests
1.1.14, ExtraQuests 1.5.3. Other versions may work; see the warning further down.

### Which side

`environment` is `"*"` — **install it on the server and on clients.**

Most of the mod is server-side: the storage redirects, the migration, the finalizer sweep.
A server-only install works and gives every player individual progress correctly. But
`ClientQuestFileMixin` is a client-side fix, and without it the quest book's edit mode is
permanently disabled for operators, because the client compares the party id it was told
about against the player-team id its quest data actually carries. If nobody edits quests
from in-game, server-only is a defensible choice. If anyone does, ship the jar to clients
too.

The mod has no config GUI and no client-side settings. A client install needs no setup.

---

## Getting the jar

### Pre-built

Take `cheesefixquests-1.0.1+1.21.1.jar` from the release you are installing. Nothing else
from this repository is needed — CheeseFixQuests does not depend on CheeseDash or on any
sibling mod, which makes it the one mod here you can drop into an unrelated modpack.

### Building from source

> ⚠️ **This mod cannot be built from a clean clone.** It needs three jars you have to
> download by hand. They are the only reason the build is not fully automatic.

FTB Quests, FTB Teams and FTB Library are **CurseForge-exclusive**: they are published on
neither Modrinth nor any public Maven repository, so Gradle has no way to fetch them. This
mod mixes directly into their classes, and the Mixin annotation processor resolves every
target at compile time, so the jars have to be on the compile classpath.

Download the **Fabric 1.21.1** file of each from CurseForge and drop them in the
repository's [`localdeps/`](../../localdeps/README.md) folder — at the repository root, not
inside the mod folder — under exactly these names:

```text
localdeps/ftb-quests-fabric-2101.1.13.jar
localdeps/ftb-teams-fabric-2101.1.2.jar
localdeps/ftb-library-fabric-2101.1.18.jar
```

The names must match exactly. `build.gradle` lists them literally in its `ftbJars`
definition and checks for them before anything else happens. A newer FTB version will
probably compile, but you have to edit those three filenames in
`CheeseFixQuests/build.gradle` as well as renaming the files.

`localdeps/` also contains `academy-mc1.21.1-v1.4.0+build.467-fabric.jar`. That one is for
CheeseCompatModsFix and **is not needed here** — CheeseFixQuests requires exactly the three
FTB jars.

`.gitignore` excludes `*.jar` inside `localdeps/`, so nothing you put there can end up in a
commit. These mods belong to their authors and are not redistributed by this repository.

If a jar is missing, the build stops immediately and names it:

```text
FAILURE: Build failed with an exception.

* Where: Build file 'CheeseFixQuests/build.gradle'

* What went wrong:
A problem occurred evaluating project ':'.
>
  CheeseFixQuests cannot be built without the FTB jars.

  Missing from localdeps/:
    - ftb-teams-fabric-2101.1.2.jar

  These three mods are CurseForge-exclusive — they are on neither Modrinth nor any
  public Maven repository, so Gradle cannot download them for you. CheeseFixQuests
  mixes directly into their classes, so they are required to compile.

  Grab them from CurseForge (search the mod name, pick the Fabric 1.21.1 file) and
  drop them in localdeps/ with exactly those filenames. Details: localdeps/README.md
```

That check exists so you get this message instead of several hundred lines of
`cannot find symbol`.

Everything else resolves on its own, from Modrinth's Maven: Architectury API 13.0.8,
Cobblemon Quests 1.1.14 and ExtraQuests 1.5.3. Then:

```bash
cd CheeseFixQuests && ./gradlew build
```

The jar lands in `build/libs/cheesefixquests-1.0.1+1.21.1.jar`. There is **no build order
to respect** — no sibling mod in this repository is involved.

---

## Installing

1. Stop the server.
2. **Back up `world/ftbquests/` and `config/ftbquests/`.** This is not boilerplate: the
   mod runs a one-shot migration that rewrites quest progress the first time a player logs
   in. [The migration](migration.md) explains exactly what it does, and the backup is your
   only way back if the result is not what you wanted.
3. Drop `cheesefixquests-1.0.1+1.21.1.jar` in the server's `mods/`, next to FTB Quests and
   FTB Teams.
4. Ship the same jar to clients if you want in-game quest editing to keep working.
5. Start the server.

---

## What the first start creates

One file, `config/cheesefixquests.json`, with all nine defaults. Full reference in
[configuration](configuration.md).

Nothing else, yet. `config/cheesefixquests_audit.log` only appears if you turn
`auditQuestEvents` on.

The migration does **not** run at startup. It runs when the first player logs in.

---

## Verifying it loaded

Three or four lines, in the server log, during startup:

```text
[CheeseFixQuests] Individual quest progress mode ENABLED
[CheeseFixQuests] Migration pending — will run on first player login
[CheeseFixQuests] Boot complete — finalizer=true claim-lock=true audit=false
```

The second line only appears while `migrationDone` is `false`. If you see
`Individual quest progress mode DISABLED`, `forceIndividualProgress` is `false` in the
config and the mod is doing nothing.

A fourth line comes from `TeamDataClaimRewardSyncMixin`'s static initialiser, so it appears
whenever FTB Quests' `TeamData` class is first loaded rather than at a fixed point in boot:

```text
[CheeseFixQuests] TeamDataClaimRewardSyncMixin loaded — claimReward is now thread-atomic.
```

Then, about ten seconds after the server starts ticking — the first sweep runs, and arms
its phases, whether or not anyone is online:

```text
[CheeseFixQuests] Phase A (detect) armed — sweep every 200 ticks.
[CheeseFixQuests] Phase D (Cobblemon Pokédex resync) armed.
```

Phase D's line only appears if Cobblemon Quests is installed.

### Verifying in game

The migration line proves the data moved. To prove the redirect works, you need two
accounts:

1. Have two players join an FTB Teams party: `/ftbteams party create`, then invite and
   accept.
2. Have one of them complete any quest — picking up a required item is the quickest.
3. Open the quest book on the other account. **The quest must still be incomplete.** On
   stock FTB Quests it would be complete for both.

If it completed for both, start at
[quest progress is still shared](troubleshooting.md#quest-progress-is-still-shared-inside-a-party).

---

## Uninstalling

Stop the server, delete the jar from `mods/` (and from clients). FTB Quests goes straight
back to party-keyed progress.

What stays behind:

- `config/cheesefixquests.json`, and `config/cheesefixquests_audit.log` if you ever enabled
  it. Both are inert; delete them at will.
- **The per-player progress records created by the migration.** They stay inside FTB
  Quests' own data, keyed by player-team UUID. FTB Quests will ignore them for anyone in a
  party and read the old party record instead — which has not been updated since the day
  you installed this mod. Players in parties will appear to lose everything they did in the
  meantime.
- The party records this mod stopped writing to. Untouched, and stale.

So uninstalling is not symmetric with installing. Reinstalling picks the per-player records
straight back up, but any play that happened without the mod installed stays in the party
record, and a later `remigrate` applies the filtering rules in
[the migration](migration.md) to it. Restore the backup from step 2 if you want a clean
rollback.

---

← [CheeseFixQuests](../README.md) · [Getting started](getting-started.md) →
