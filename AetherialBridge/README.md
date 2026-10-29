# AetherialBridge

> 🇫🇷 [Lire en français](README.fr.md)

> Exposes the Aetherial Islands floating-island worldgen as one more generation type
> in CheeseGuard's dimension creator.

CheeseGuard can create dimensions while the server is running: you pick a generation
type in the dashboard, give the dimension a name, and a new `ServerWorld` appears a
moment later. Out of the box that list holds five built-in entries (`VOID`, `FLAT`,
`NORMAL`, `NETHER`, `END`). AetherialBridge adds a sixth, labelled **Aetherial
Islands**, which builds its chunk generator from the Aetherial Islands noise settings
instead of a vanilla preset. Everything else about the dimension (spawn point, PvP,
keep-inventory, snapshots, resets, per-player mode) stays CheeseGuard's job.

What it is not: it is not a worldgen mod, and it ships no worldgen data of its own. The
terrain comes from the Aetherial Islands datapack, which you install separately. This
mod is the adapter that lets CheeseGuard reach that data for one dimension at a time,
instead of having the datapack rewrite `minecraft:overworld` for the whole server.

The second half of the mod is less obvious from the name. Floating-island terrain sits
far below y=0 with an ocean floor near the bottom of the world, and several features
that vanilla and other mods attach to every overworld-tagged biome behave badly down
there: lava springs cut tunnels through the water column, and one ore mod's placement
scan never converges and trips the server watchdog. Four mixins suppress those features
in any dimension whose chunk generator reports a sea level below -25, and one of them
writes the sea floor itself, with sand, gravel, clay, prismarine, coral, kelp and
seagrass. [The worldgen page](docs/worldgen.md) covers all of it.

**Minecraft 1.21.1 · Fabric · Java 21 · 7 files, ~485 lines**

---

## ⚠️ Read this before installing

**This is a third-party patch, published as a worked example rather than as a finished
product.**

- Three of its five mixins inject into classes it does not own: one into vanilla's
  `NoiseChunkGenerator`, and two into classes belonging to other mods
  (`cobblemonraiddens` and `minerally`). It was written against one specific modpack,
  and any update to those mods can break it without warning.
- It expects a **repackaged** build of Aetherial Islands whose worldgen data lives in
  the `aetherial_islands` namespace. The stock Modrinth download does not provide that
  namespace, so dimension creation fails with a clear error until you supply a datapack
  that does. [Installation](docs/installation.md#the-datapack-namespace) explains what
  is expected and why.
- Provided as is. No support is offered, and no fix is promised when the mods it
  targets change.

The claim that **the vanilla overworld is left untouched** does hold, and
[architecture](docs/architecture.md#why-the-overworld-is-safe) shows the guard that
makes it hold. That guard is a sea-level test, not a dimension-id test, so read the
section before installing another low-sea-level datapack alongside it.

---

## Requirements

Both mods below sit in `depends`, which means Fabric Loader refuses to start the server
if either one is missing.

| | Required | Notes |
|---|---|---|
| [CheeseGuard](../CheeseGuard/) | yes | provides the `cheeseguard-gentype` extension point and the dimension manager. Pulls in [CheeseDash](../CheeseDash/) in turn. |
| [Aetherial Islands](https://modrinth.com/datapack/aetherial-islands) | yes | declared as `mr_aetherial_islands`. The worldgen data itself. |
| Fabric Loader | ≥ 0.15.0 | |
| Fabric API | any 1.21.1 build | |

Server-side where it matters. `fabric.mod.json` declares `"environment": "*"`, so the
jar also loads on a client, but nothing in it runs there.

## Install

CheeseGuard has to be **built** before this mod compiles, because the dependency
resolves against its jar rather than its source:

```bash
cd CheeseDash         && ./gradlew build     # gradlew.bat on Windows
cd ../CheeseGuard     && ./gradlew build
cd ../AetherialBridge && ./gradlew build
```

Then drop four jars in `mods/`: CheeseDash, CheeseGuard,
`aetherialbridge-1.0.0+1.21.1.jar`, and your Aetherial Islands jar. Full detail, the
log lines to check and the namespace caveat are in
[installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Exact prerequisites, build order, the datapack namespace the provider needs, log lines that prove it loaded, clean uninstall. |
| [Getting started](docs/getting-started.md) | Numbered walkthrough from a blank server to standing on a floating island. |
| [Worldgen](docs/worldgen.md) | The gen-type provider, the sea-level threshold, the forced sea floor and its decorations, and the three features that get suppressed. |
| [Architecture](docs/architecture.md) | Class by class, the exact lines where the coupling to third-party code lives, threading, and the known weaknesses. |
| [Troubleshooting](docs/troubleshooting.md) | A dimension that generated as void, lava tunnels, watchdog timeouts, the mod refusing to load, and how to file a useful bug report. |

There is no `commands.md` and no `configuration.md`: the mod registers no command and
reads no configuration file of its own. Everything tunable about an Aetherial Islands
dimension is a CheeseGuard dimension setting, stored in
`config/cheeseguard_dimensions.json` and edited from the CheeseDash **Dimensions**
view. There is no `dashboard.md` either, because the mod registers no CheeseDash
module; the one dashboard surface it affects is the gen-type dropdown CheeseGuard
already serves. [Getting started](docs/getting-started.md) walks through that screen.

## Commands at a glance

None. The mod adds no command and no in-game GUI. Dimensions are created from the
dashboard, and you move into one with vanilla teleportation or the dashboard's **TP**
button.

## License

Released under [PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional
Permission for server operators: you may run this on a server that takes donations,
sells ranks or runs a store. You may not sell the mod itself, bundle it in a paid
modpack, or sell a service built around hosting it.

Aetherial Islands belongs to its own authors. It is neither redistributed here nor
covered by this license.

## Support

- **Bugs** → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- **Questions** → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)

Keep the warning above in mind when reporting: a break caused by an update to Aetherial
Islands, CobblemonRaidDens or Minerally is expected behaviour for a patch mod, and the
fix is a code change rather than a setting.
