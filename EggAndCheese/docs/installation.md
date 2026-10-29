[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

## Which side

**Both.** `fabric.mod.json` declares `"environment": "*"`, and the mod means it:

- the server owns the registry, the placement logic, the discovery logic and the
  dashboard API;
- the client owns every renderer and the procedural texture generator — there is no egg
  texture in the jar, so a client that does not run the mod has nothing to draw.

The mod also registers 25 blocks and 25 items. A client joining without them has an
incomplete registry for the blocks the server sends, so treat a server-only install as
unsupported rather than degraded.

## Prerequisites

| | Version | Where |
|---|---|---|
| Minecraft | 1.21.1 exactly (`~1.21.1`) | |
| Fabric Loader | ≥ 0.16.0 | [fabricmc.net](https://fabricmc.net/use/installer/) |
| Fabric API | any 1.21.1 build; developed against `0.102.0+1.21.1` | [Modrinth](https://modrinth.com/mod/fabric-api) |
| Java | 21 | |
| **CheeseDash** | matching build from this repository | [CheeseDash](../../CheeseDash/) |

CheeseDash is a **hard dependency** — `"cheesedash": "*"` sits in `depends`, and Fabric
Loader refuses to start the server without it.

### Optional

| Mod | What you gain | Without it |
|---|---|---|
| [FTB Quests](https://modrinth.com/mod/ftb-quests-fabric) | One quest per egg, auto-created and auto-completed | The integration disables itself and logs that it did. Use the `eggandcheese:egg_found` advancement instead |
| fabric-permissions-api, e.g. via [LuckPerms](https://luckperms.net/) | The `eggandcheese.admin` permission node | Falls back to op level 2 |
| [JourneyMap](https://modrinth.com/mod/journeymap) | Nothing, today | `JourneyMapIntegration` exists but is wired to nothing — see [architecture](architecture.md#dead-code) |

Cobblemon is not required. One compatibility fix in `CollectibleItem` targets Cobblemon's
display case, and it is inert when Cobblemon is absent.

## Build order

A sibling-mod dependency resolves through a `fileTree` glob over
`../CheeseDash/build/libs`, not a pinned filename. CheeseDash must therefore be **built**
before EggAndCheese compiles:

```bash
cd CheeseDash   && ./gradlew build
cd ../EggAndCheese && ./gradlew build
```

The result is `EggAndCheese/build/libs/eggandcheese-1.0.0+1.21.1.jar`. The version comes
from `mod_version` in `gradle.properties`.

`tools/build-all.sh` at the repository root walks every mod in dependency order, which is
simpler when you are building more than one.

Two build details worth knowing:

- `loom.splitEnvironmentSourceSets()` is on, so client code lives in `src/client/java`
  and server code in `src/main/java`, and both are declared as the same mod. A build that
  silently drops the client source set produces a jar with no renderers.
- `withSourcesJar()` is enabled, so `build/libs/` also contains
  `eggandcheese-1.0.0+1.21.1-sources.jar`. **Do not deploy that one.**

## Installing

1. Stop the server.
2. Copy into the server's `mods/`:
   - `eggandcheese-1.0.0+1.21.1.jar`
   - the CheeseDash jar
   - Fabric API
3. Copy the same EggAndCheese jar into each client's `mods/`, alongside Fabric API.
4. Start the server.

Delete the old jar before copying a new one. Two versions of the same mod in `mods/` is a
startup failure, not a merge.

## What the first launch creates

**No configuration file.** EggAndCheese never calls `getConfigDir()`; nothing appears in
`config/`. If you expected `config/eggandcheese.json`, see
[configuration](configuration.md).

What it does create, lazily, is a persistent state entry inside the world:

```text
world/data/eggandcheese_registry.dat
```

It appears the first time anything reads the registry — a dashboard call, a command, an
egg placement — and holds both the collectibles and the four settings. On a brand-new
world it starts empty, with `ftbQuestsEnabled` false, `ftbQuestsChapter` `"oeufs"`,
`ftbAutoSync` false and `textureRenderDistance` 20.

Nothing is written to `config/`, nothing to a database, and no files outside the world
folder.

## Verifying it loaded

### In the log

```text
[EggAndCheese] Initializing...
[EggAndCheese] Ready!
```

Both lines mean registration completed. On the client you also get:

```text
[EggAndCheese] Client initializing...
[EggAndCheese] Client ready!
```

With FTB Quests installed, the hook adds its own diagnosis at first use:

```text
[EAC-FTB] CustomTask available — egg quests will not be clickable by players
[EAC-FTB] setRawIcon available — quest icons enabled
[EAC-FTB] TranslationManager available — title sync enabled
[EAC-FTB] Network sync available — live client updates enabled (sendToPlayers=true, create=true, editCtor=true)
[EAC-FTB] FTB Quests hook initialized — Java API ready
```

Without it:

```text
[EAC-FTB] FTB Quests not present — hook disabled
```

### In the dashboard

Log in at `http://<server>:44311`. An **Eggs** section with a 🥚 icon in the sidebar means
the `cheesedash` entrypoint was discovered. For a check that does not involve the
frontend:

```bash
curl -H "Authorization: Bearer <token>" http://localhost:44311/api/v1/modules
```

The `eggs` prefix should be in the list. `GET /api/v1/eggs/stats` on a fresh install
returns zeros, which is still a successful round trip through the module.

### In game

```mcfunction
/eac reload
```

```text
Egg registry loaded: 0 entries.
```

An op player getting that line has confirmed the command tree, the permission check and
the registry in one go. Then place an egg:

```mcfunction
/give @s eggandcheese:admin_egg
```

Right-click a block face with it. You should get a slowly rotating procedurally textured
egg and, in chat:

```text
Egg created with automatic ID 'egg_001'. Editable from the CheeseDash dashboard.
```

If the block appears as a missing-model cube or as nothing at all, the client half is not
loaded. If the chat line is absent, the server half is not.

## Uninstalling

1. Stop the server.
2. Remove the jar from the server's `mods/` and from every client's `mods/`.
3. Start the server.

### What breaks

Every `eggandcheese:` block and item becomes an unknown registry entry. Minecraft removes
unknown blocks from chunks as they load, so **every placed egg, every decorative egg and
every pedestal disappears**, along with whatever a pedestal was displaying. Items in
inventories are dropped from the stack on load. This is vanilla behaviour for a removed
mod, and it is not recoverable without putting the mod back.

Back up the world before removing the mod if any of that matters.

### What is left behind

| Left | Where | Remove it with |
|---|---|---|
| The collectible registry and the four settings | `world/data/eggandcheese_registry.dat` | delete the file |
| FTB quests the mod created | your pack's FTB Quests data | delete them in the quest editor — the mod will not clean up after itself once removed |
| Nothing in `config/` | — | — |

Putting the mod back before deleting `eggandcheese_registry.dat` restores the registry
with every entry and discovery intact. The blocks, however, are gone from the world, so
every entry will be pointing at coordinates where nothing stands. The dashboard's
[integrity report](integrity.md) will list them all under `unloadedEntries`, and you
re-place each egg from its admin item.

### Removing only the FTB integration

You rarely need to uninstall the mod for that. Set `ftbQuestsEnabled` to `false` in
`PUT /api/v1/eggs/config` and the hook goes quiet immediately, leaving the existing quests
in place. Delete them in the quest editor if you want them gone.

---

← [Troubleshooting](troubleshooting.md) · [Getting started](getting-started.md) →
