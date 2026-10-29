# CheeseCompatModsFix

> 🇫🇷 [Lire en français](README.fr.md)

> Forty patches into seventeen other people's mods, written for one modpack.

> ## ⚠️ These are patches, not a mod
>
> Every fix here was written for **one specific modpack**, against **specific versions**
> of a dozen third-party mods, to solve problems that occurred on one server. The mixins
> target other people's internals, by name, by lambda index, by bytecode constant.
> **Any update to any of those mods can break this silently** — the mixin config sets
> `defaultRequire: 0`, so a fix whose target has moved is skipped with no error at all.
>
> It is **provided as is**, and **no support is guaranteed**. If you install it and
> something breaks, the answer will be "remove it".

This is not a feature. It is a collection of compatibility mixins and runtime patches —
crash fixes, cross-mod behaviour corrections, performance culls, and a few outright
hacks — kept together because they share one initialiser, one config file and one
mixin-gating plugin.

It is published because the individual fixes are instructive: an integer overflow that
made a protection radius cover the world, a `copyFrom` that wrote IVs in the wrong
direction, a cache key coarse enough to freeze an event for the lifetime of a world, a
`goto` that will not verify where a conditional branch will. A few are genuinely
reusable. Reading it is the point; installing it is not.

It is also **not throwaway code**. Version `1.2.53+1.21.1` runs in production on its
author's server and has done so through fifty-odd releases. Each fix is pinned to a
precise version of a third-party mod, which is exactly what makes it both load-bearing
and fragile.

**Minecraft 1.21.1 · Fabric · Java 21 · 64 files, ~7 456 lines**

---

## Requirements

| | |
|---|---|
| **Required** | Fabric Loader ≥ 0.16.0 · Fabric API (`fabric-command-api-v2`) · Java 21 |
| **Optional** | all seventeen mods it patches — each enables a group of fixes and is ignored if absent |

Everything it patches is optional at runtime. `CheeseCompatMixinPlugin` checks
`FabricLoader.isModLoaded(...)` for each target and switches the relevant mixins off
when the mod is missing, so installing this does not require installing the whole stack
it knows about.

Runs on **server and client**. The server gets all 35 server mixins and every
event-driven fix; the client gets four cosmetic or performance mixins plus a `client`
entrypoint that enables the Arkensia resource packs on first launch.

### ⚠️ Building needs two jars you must supply

**FTB Quests** and **StarAcademy** are CurseForge-exclusive and cannot be resolved by
Gradle. Download the **Fabric 1.21.1** file of each into
[`localdeps/`](../localdeps/README.md) at the repository root, with exactly these names:

```text
ftb-quests-fabric-2101.1.13.jar
academy-mc1.21.1-v1.4.0+build.467-fabric.jar
```

The build stops at configuration time with a message naming what is missing, rather than
a wall of `cannot find symbol`. A mixin that names its target as a string still needs the
jar: the Mixin annotation processor validates every target at compile time.

Everything else — Cobblemon, Battle Tower, CobbleSafari, Trials Edition, Raid Dens,
Legendary Monuments, Minerally, Enhanced Celestials, Data Anchor, Daycare+, Fight or
Flight, ExtraQuests, Numismatic Overhaul, JourneyMap, sgui — is fetched automatically
from Maven.

## Install

```bash
cd CheeseCompatModsFix && ./gradlew build
```

Drop `build/libs/cheesecompatmodsfix-1.2.53+1.21.1.jar` in the server's `mods/`. No
sibling mod is required. On first start it writes `config/cheesecompatmodsfix.json` and
logs:

```text
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=enabled, PC=enabled, Sanctuary fix=ON
```

That line appears whether or not any fix activated. To find out which ones did, grep the
log for `CheeseCompat` — a missing activation line means a missing fix. Full walkthrough:
[installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, the two CurseForge jars, build, what the first launch creates, the log lines that prove it loaded, clean removal and what the world keeps |
| [Getting started](docs/getting-started.md) | A tutorial: build it, find out which fixes activated on your stack, then switch one off and watch it land in game |
| [Commands](docs/commands.md) | `/stopbattle`, and how `/stopbattle <player>` is taken away from non-operators by reflecting into Brigadier |
| [Configuration](docs/configuration.md) | The three booleans, plus every number that looks configurable and is hard-coded |
| [Architecture](docs/architecture.md) | Package layout, how mixins are gated, reflection patterns, threading, and a candid list of known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — built around the fact that the normal failure mode here is silence |

### The fix catalogue

The important part. One entry per fix: the mod and version it targets, the symptom from
a player's or an admin's point of view, how it hooks in, what happens when the target
mod is absent, and whether it is still active.

| Page | What it covers |
|---|---|
| [Battle Tower](docs/fixes-battle-tower.md) | Twelve fixes: party backup and restore across a disconnect, IV/EV corruption, arena geometry, invisible NPC Pokémon, a BP shop overflow exploit |
| [Cobblemon and battles](docs/fixes-cobblemon.md) | Mega-Z forms, custom mega stones, Trials Edition on 1.6.1, uncatchable alphas, raid dens, Rad Gyms — and the two patches that were **disabled or refused** after breaking production |
| [Safari, Academy and bosses](docs/fixes-safari-and-bosses.md) | CobbleSafari 0.1.5 back-ported onto Cobblemon 1.6.1, the safari portal blocks, and the bytecode patches for Cobblewarden Boss |
| [Daycare+](docs/fixes-daycare.md) | Nine mixins that rewrite the breeding rules, including one that is written and never enabled |
| [Everything else](docs/fixes-misc.md) | Legendary Monuments, Minerally, Enhanced Celestials, ExtraQuests, JourneyMap, Numismatic Overhaul |

## Commands at a glance

| Command | Permission | What it does |
|---|---|---|
| `/stopbattle` | any player | force-ends the caller's own Cobblemon battle |
| `/stopbattle <player>` | level 2 | Battle Tower's command, restricted to operators by this mod |

The targeted form is not added here, only locked down. Details in
[commands](docs/commands.md).

## What it patches

| Target mod | Fixes |
|---|---|
| **Battle Tower** | forced NPC levels, double-battle guard, stale team locks, IV/EV corruption, full team restore, disconnect backup, arena offsets, chunk preload, party-screen aspects, shutdown restore, BP shop overflow |
| **Cobblemon** | silently uncatchable alphas, Tera blocked in raids |
| **Trials Edition** | `IncompatibleClassChangeError` on Cobblemon 1.6.1 when resolving a nature |
| **MegaShowdown** | `mega_z` added to the aspect set so Mega-Z forms devolve; 70 custom mega stones mapped to Showdown ids |
| **CobbleSafari** | back-port to Cobblemon 1.6.1: spawn influences, dimensional bans, teleporter and dungeon portals outside the Overworld, two missing Cobblemon blocks, a portal block |
| **StarAcademy** | safari balls unblocked in safari dimensions, slingshot blocked there, uncatchable bosses protected from slingshot hits |
| **Daycare+** | IV inheritance rewritten, fertility clamped and decaying, one-shot breeding with a GUI toggle |
| **Raid Dens** | boss facing, an entity-clear crash, fire spread, crystals that never close |
| **Rad Gyms** | players stranded after a crash, a concurrency hang, wrong badge type, an NPE on trainer interaction, orphan trainers purged from an empty gym dimension |
| **Legendary Monuments** | sanctuary radius integer overflow, Arc Phone heal and PC toggles, entrepreneur profession lock |
| **Minerally** | NPE crash when JustHammers area-breaks an `OrePile` |
| **Enhanced Celestials** | `cheeseguard:*` dimensions inherit lunar settings; lazy per-level data forced to materialise |
| **ExtraQuests** | a startup race that deleted daily-quest timers without resetting them |
| **Fight or Flight** | a captured Giratina no longer attacks its owner |
| **JourneyMap** | minimap icon path for fakemon outside the `cobblemon` namespace |
| **Numismatic Overhaul** | distance cull on shop block rendering (client FPS) |
| **Cobblewarden Boss** | instant boss spawn on a sculk shriek, permanent aggression, a darkness aura — plus four bytecode patches applied to that mod's own jar |

## Two scars worth reading before you touch this

**A battle watchdog once froze every battle on the server.** Version 1.2.51 shipped
`RctForcedSwitchFix` to unstick battles where several Pokémon faint on the same tick. Its
predicate flagged any AI actor with a pending request and no response — which is the
normal state of every turn while the human player is deciding. Three seconds of thought
triggered it. It replayed the choice handler into healthy battles, desynchronised
Showdown, and froze everything; forensics then showed it did not even unstick the cases
it was built for. **Disabled in 1.2.52**, and the class is kept in the tree with the
post-mortem at its commented-out call site. It was well engineered — fail-open,
idempotent, server-thread only — and still catastrophic, because the error was in the
detection predicate, and a wrong predicate on a path that runs every turn does not
degrade gracefully. The full account is in
[architecture](docs/architecture.md#-the-watchdog-the-scar-worth-reading).

**A held-item mixin broke every Pokémon held-item swap, server-wide.** Tried in 1.2.37
against MegaShowdown, reverted, and **deliberately not retried**. The `rusted_sword`
consume behaviour it was chasing is a knowing WONTFIX. The working approach is
Cobblemon's public `registerStackRemap` API, which is what
`CacademyMegaStoneRemapPatch` uses instead.

## Known non-fatal error on startup

If CobbleSafari is not installed, the server log will carry:

```text
Couldn't parse element minecraft:loot_table/cheesecompat:blocks/cobblesafari_portal_frame
  - Unknown registry key ...: cheesecompat:cobblesafari_portal_frame
```

The portal frame block is registered conditionally, but its loot table ships
unconditionally, so the table is orphaned when the block is absent. The server starts
normally. Verified on a 475-mod test server. It is noise in the log and nothing more.

## Other honest warnings

- **There is an orphaned `fabric.mod.json` at the mod root.** The one that is packaged is
  `src/main/resources/fabric.mod.json`, and it is correct. The 27-line copy at
  `CheeseCompatModsFix/fabric.mod.json` is read by nothing, is not packaged, declares a
  stale `"version": "1.1.0"` and a description that stops at "Battle Tower, Legendary
  Monuments, Arc Phone toggles". It is a leftover from an older project layout and should
  be deleted. Read the one under `src/main/resources/`.
- **Two classes are dead code**: `DaycareDestinyKnotConsumeMixin` (not listed in the
  mixin config) and `DungeonDimensionsMixin` (an empty class carrying a comment).
- **A new Daycare+ pasture produces one egg and stops.** That is this mod's default and
  the opposite of Daycare+'s own.
- **`BattleArenaPositionMixin` hard-codes an 18-block arena.** On any other arena
  geometry the NPC lands in a wall.
- **`CobbleSafariInitMixin` is an `@Overwrite` on a third-party initialiser** and will
  silently discard anything CobbleSafari adds to that method in a future version.
- **There are no tests.** Everything documented here comes from reading the code and
  from one server's production experience.

## Arkensia specifics

`CacademyMegaStoneRemapPatch` maps 70 custom mega stones — 56 from the `CacademyMons`
datapack, the rest from Z-A Megas Server-Side and AllTheMons x Mega Showdown, all shipped
as `minecraft:brick` and a handful of other vanilla items, distinguished by
`custom_model_data` — onto MegaShowdown's Showdown simulator ids. It is entirely specific
to those packs and does nothing without them.

`EnhancedCelestialsFactoryFallbackMixin` is specific to
[CheeseGuard](../CheeseGuard/)'s runtime-created dimensions: any `cheeseguard:*`
dimension with no lunar settings of its own inherits `cheeseguard:arkensia`'s.

## ⚠️ `patches/cobblewarden/` is someone else's code

`patches/cobblewarden/` contains compatibility patches for **Cobblewarden Boss by
LiquidSilverAce**, used under its **CC0-1.0** licence. It is the only part of this
repository that is not RoiCheese's work, and it keeps its own attribution: the patched
`fabric.mod.json` there names LiquidSilverAce as the author, keeps
`"license": "CC0-1.0"`, and keeps the upstream project links.

Four patches are documented in `patches/cobblewarden/README.md`, in French, down to the
constant pool indices and a Python snippet to regenerate each one: a relaxed `depends`
block, two bytecode edits to `WardenSpawnInterceptor` (a coarse cache key was freezing
every future Warden in a 128×64×128 region), and a full replacement of `BossSpawner`.
Note that the patched `.class` files themselves are **not in this repository** — only the
README and the patched `fabric.mod.json`.

Summary and the general lessons from those patches:
[Safari, Academy and bosses](docs/fixes-safari-and-bosses.md#cobblewarden-boss).

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may read it, modify it, fork it, redistribute it with your changes, and
run it on a server that takes donations or sells ranks; you may not sell the mod itself.

Every mod patched here belongs to its own authors and none is redistributed. The
contents of `patches/cobblewarden/` are LiquidSilverAce's, under CC0-1.0.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)

⚠️ For this mod specifically: **no support is guaranteed**. A fix breaking after its
target mod updated is expected, not a bug. Reports that a fix is wrong *against the
version it claims to target* are worth filing.
