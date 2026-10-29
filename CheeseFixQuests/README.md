# CheeseFixQuests

> 🇫🇷 [Lire en français](README.fr.md)

> Makes FTB Quests progress individual, even when players share an FTB Teams party.

**This is the mod in this repository most likely to be useful to you, whatever server you
run.** It has nothing to do with Cobblemon or with the Arkensia server — it fixes one
well-known friction point between two very widely used mods.

FTB Quests stores progress per *team*. FTB Teams gives every player a permanent team of
their own and lets them additionally join a party; when a player is in a party, FTB Quests
reads and writes the *party's* progress. For a co-op modpack where a party is a questing
group, that is the right design. For a server where parties exist for land claims, chunk
loading or a guild chat, it is not: those players never asked to share a quest book, and
the first time one of them kills a boss the quest vanishes from everyone else's.
CheeseFixQuests decouples the two. Quests become per-player, and teams go on doing
everything else they do.

The mechanism is nine mixins into FTB's internals, most of which turn FTB Teams'
"which team is this player effectively on?" lookup into "which team is this player's own?"
There is no per-task-type logic: the redirect sits at the storage-resolution layer, so
every quest and every task type is covered. A one-shot migration copies existing team
progress into per-player progress on the first login, and a 10-second repair sweep fixes a
separate family of FTB bugs where a task reaches its target and the quest never notices.

**Minecraft 1.21.1 · Fabric · Java 21 · 15 source files, ~1 920 lines**

---

## ⚠️ Honest warnings

**The mixins target another mod's internals — private fields, method descriptors,
individual bytecode instructions. None of it is a public API.** An FTB Quests, FTB Teams,
Cobblemon Quests or ExtraQuests update can break this mod, and because the mixin config
sets `required: true`, the failure mode is a crash on startup rather than a silent
degradation. That is the better of the two outcomes, but it means you pin your FTB
versions and test before updating them.

Verified against **FTB Quests 2101.1.13**, FTB Teams 2101.1.2, FTB Library 2101.1.18,
and optionally Cobblemon Quests 1.1.14 and ExtraQuests 1.5.3. Those are the versions in
`build.gradle`. Anything else is untested.

**The migration rewrites quest progress on the first player login, automatically, with no
prompt.** Back up `world/ftbquests/` first. For teams that are no longer active parties the
copy is deliberately lossy — only reward claims record which player did what — so a quest
completed without claiming its reward does not survive. [The migration](docs/migration.md)
explains exactly what is copied and why.

This mod runs in production on a live server. It has not been tested against any FTB
version other than the ones listed above.

---

## Requirements

| | |
|---|---|
| **Required** | [FTB Quests](https://www.curseforge.com/minecraft/mc-mods/ftb-quests-fabric) · [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-fabric) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Optional** | [Cobblemon Quests](https://modrinth.com/mod/cobblemon-quests) — keeps its own event handler per-player too |
| **Optional** | [ExtraQuests](https://modrinth.com/mod/extraquests) — makes its daily timer reset the right data |

No dependency on CheeseDash or on any other mod in this repository. It drops into an
unrelated modpack as-is.

Install it on the **server and on clients**: the server side is what matters, but one
client-side mixin is what keeps the quest book's edit mode working for operators.

## Install

Drop `cheesefixquests-1.0.1+1.21.1.jar` in `mods/`, next to FTB Quests and FTB Teams.
Back up `world/ftbquests/` before the first start. There is nothing to configure.

```text
[CheeseFixQuests] Individual quest progress mode ENABLED
[CheeseFixQuests] Migration pending — will run on first player login
[CheeseFixQuests] Boot complete — finalizer=true claim-lock=true audit=false
```

### ⚠️ Building it needs three jars you must supply

FTB Quests, FTB Teams and FTB Library are **CurseForge-exclusive** — published on neither
Modrinth nor any public Maven repository, so Gradle cannot fetch them. This mod mixes
directly into their classes, so they are mandatory to compile.

Download the **Fabric 1.21.1** file of each into [`localdeps/`](../localdeps/README.md), at
the repository root, under exactly these names:

```text
ftb-quests-fabric-2101.1.13.jar
ftb-teams-fabric-2101.1.2.jar
ftb-library-fabric-2101.1.18.jar
```

The build checks for them before anything else and stops with a message naming what is
missing, so you are not left reading a wall of `cannot find symbol`. Then
`./gradlew build`. No sibling mod has to be built first.

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites and versions, which side to install on, the three jars the build needs, what the first start creates, the log lines that prove it loaded, clean removal and what it leaves behind |
| [Getting started](docs/getting-started.md) | A tutorial with two accounts: see progress shared, install, then see the same quest complete for one player and not the other |
| [Commands](docs/commands.md) | All four subcommands, their arguments, permission level, side effects and every failure message |
| [Configuration](docs/configuration.md) | The nine flags in `cheesefixquests.json`, what each one turns off, and a complete annotated file |
| [The migration](docs/migration.md) | What happens to progress that already exists: when the copy runs, the two modes, what it refuses to copy and why, and how to read its log |
| [The finalizer sweep](docs/finalizer.md) | The four-phase repair pass, why FTB leaves quests stuck at a full bar, the anti-duplication lock, and the forensic audit log |
| [Architecture](docs/architecture.md) | What FTB does by default, exactly what is and is not covered, all nine mixins with their targets, threading, extension points, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — mixin crashes, progress still shared, rewards handed out twice, lost progress after migrating |

## Commands at a glance

All four need **permission level 4**, not the usual operator level 2. Every message they
print is hardcoded French; the mod has no language file.

| Command | What it does |
|---|---|
| `/cheesefixquests status` | prints the three flags that decide whether the mod is doing anything |
| `/cheesefixquests migrate` | runs the one-shot progress migration, unless it already ran |
| `/cheesefixquests remigrate` | runs it again regardless |
| `/cheesefixquests resetchapter <chapterHexId>` | un-completes every finished quest of one chapter, for every player |

The migration normally runs on its own at the first login, so `migrate` is there for the
case where you would rather run it from the console before letting players back in.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself. FTB Quests, FTB Teams and FTB Library belong to the FTB Team and are
not redistributed here.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
