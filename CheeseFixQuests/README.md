# CheeseFixQuests

> Makes FTB Quests progress individual, even when players share an FTB Teams party.

**This is the mod in this repository most likely to be useful to you, whatever server
you run.** It has nothing to do with Cobblemon or Arkensia — it fixes a specific, well
known friction point in FTB Quests.

By design, FTB Quests tracks progress per *team*. If your players are in parties for
any other reason — claims, chunk loading, chat — they end up sharing quest progress
whether they want to or not. One player completes a task and it vanishes from
everyone's book. This mod decouples the two: quests become per-player while teams keep
doing everything else.

**Minecraft 1.21.1 · Fabric · Java 21 · 15 files, ~1 750 lines**

---

## Requirements

- **FTB Quests** and **FTB Teams** — required
- Fabric Loader ≥ 0.16.0, Fabric API, Java 21

Optional, each with its own compatibility mixin:

- **Cobblemon Quests** — keeps its event handler per-player too
- **ExtraQuests** — fixes its timer reset to be per-player

### ⚠️ Building this mod needs three jars you must supply

FTB Quests, FTB Teams and FTB Library are **CurseForge-exclusive** — published on
neither Modrinth nor any public Maven repository, so Gradle cannot fetch them. This
mod mixes directly into their classes, so they are mandatory to compile.

Download the **Fabric 1.21.1** files into [`localdeps/`](../localdeps/README.md):

```
ftb-quests-fabric-2101.1.13.jar
ftb-teams-fabric-2101.1.2.jar
ftb-library-fabric-2101.1.18.jar
```

The build stops immediately with a message naming what is missing, so you will not be
left reading a wall of `cannot find symbol`.

## Installation

Drop the jar in `mods/` alongside FTB Quests and FTB Teams. No configuration.

## Commands

| Command | Purpose |
|---------|---------|
| `/cheesefixquests status` | show migration state |
| `/cheesefixquests migrate` | migrate existing team progress to individual progress |
| `/cheesefixquests remigrate` | run the migration again |
| `/cheesefixquests resetchapter <chapter>` | reset one chapter for everyone |

**Run `migrate` once, on a backed-up world.** It rewrites quest progress data. If you
are starting fresh you do not need it at all.

## How it works

Ten mixins, all with `remap = false` since they target another mod's classes:

- `TeamDataMixin`, `TeamDataAccessor`, `TeamDataClaimRewardSyncMixin` — redirect
  per-team storage to per-player keys
- `BaseQuestFileMixin`, `ServerQuestFileMixin`, `ClientQuestFileMixin` — the quest file
  on both sides
- `FTBQuestsInventoryListenerMixin` — item task detection
- `CobblemonQuestsHandlerMixin`, `ExtraQuestsTimerMixin` — optional-mod compatibility

Plus `QuestMigration` and `QuestFinalizerHook` for the data migration and reward
finalisation.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators. FTB Quests, FTB Teams and FTB Library belong to the FTB Team and are not
redistributed here.
