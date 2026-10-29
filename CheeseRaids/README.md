# CheeseRaids

> 🇫🇷 [Lire en français](README.fr.md)

> ## ⚠️ Experimental — never run with players
>
> This framework compiles and was deployed to test instances. It has **never run on a
> production server, and no raid has ever been played.** Four coordinates in its config
> are placeholders that must be surveyed in-world before anything works, five designed
> features are incomplete, and four of its seven dependencies are reached by reflection
> and undeclared. Install it expecting to find problems nobody has found yet.

A framework for timed, scheduled, server-wide raids. A raid is one JSON file describing an
announcement, a wave of Pokémon in the overworld, a herald NPC, a parkour arena in its own
dimension, a boss and a reward table. The mod runs the lifecycle; the file decides what
happens.

The first and only raid it ships is the **Hoopa Invasion**. At the scheduled hour the sky
over a chosen spot turns to a midnight thunderstorm and a wave of uncatchable Pokémon pours
out, led by an oversized, glowing alpha. Kill the alpha and a Ho-Oh herald materialises
through five seconds of gathering lightning. Through him, players enter a void rift where
each one gets their own parkour branch, starting 75 blocks out, generated from a seed derived from their
UUID and animated into existence block by block ahead of them. The branches converge on a
disk at the centre, where a giant Hoopa Unbound is pulled through a portal once everyone
has arrived. Beat it and the rift collapses, dropping everyone back in the overworld with
portals and fireworks.

That is the design. [What the code actually does](docs/hoopa-invasion.md) is close to it,
with named gaps — the herald has no dialogue until you bind it yourself, and the boss fight
is a plain Cobblemon battle rather than a co-op raid mechanic.

**Minecraft 1.21.1 · Fabric · Java 21 · 21 source files, ~3 340 lines**

---

## Requirements

| | |
|---|---|
| **Required** | [CheeseDash](../CheeseDash/) · [CheeseGuard](../CheeseGuard/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Required in practice** | [Cobblemon](https://modrinth.com/mod/cobblemon) — undeclared, but the raid spawns nothing without it |
| **For the herald** | [EasyNPC](https://modrinth.com/mod/easy-npc) |
| **For the victory portals** | CobbleSafari |
| **Optional** | [CheeseStore](../CheeseStore/) — the `reward.fromage` currency grant |

Server-side only: no client entrypoint, no client-side code, no mixins. Every effect is a
particle, sound or title packet the server sends.

CheeseDash and CheeseGuard are hard dependencies and Fabric will not start without them.
The other four are reached by reflection and declared nowhere, so nothing warns you when
one is missing — [what breaks how](docs/integrations.md).

## Install

```bash
cd CheeseDash    && ./gradlew build     # CheeseRaids compiles against both jars
cd ../CheeseGuard && ./gradlew build
cd ../CheeseRaids && ./gradlew build
```

Drop `build/libs/cheeseraids-1.0.0+1.21.1.jar` and both sibling jars in the server's
`mods/`. On first start the mod writes `config/cheeseraids/raids/hoopa.json` and logs:

```text
[CheeseRaids] initialized — modular raid framework ready.
[CheeseRaids] Loaded 1 raid definition(s), 0 active instance(s)
```

That file is **not ready to use.** Four of its positions default to the world origin.
[Calibration](docs/calibration.md) is the step you cannot skip.

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, build order, what the first launch creates, how to verify it loaded, clean removal and what is left behind |
| [Getting started](docs/getting-started.md) | A tutorial: from a fresh install to one full Hoopa Invasion, every phase seen |
| [Commands](docs/commands.md) | All seven subcommands, argument by argument, with permission levels, output and every failure message |
| [Configuration](docs/configuration.md) | `hoopa.json` in full: every key, default and interaction, including those that do nothing, plus an annotated example |
| [Calibration](docs/calibration.md) | How to survey the four placeholder coordinates in-world and verify each one. The most useful page in the set |
| [The raid model](docs/raid-model.md) | What a raid is, the eight phases, the star arena, and an honest split of what is generic versus Hoopa-specific |
| [The Hoopa Invasion](docs/hoopa-invasion.md) | The shipped raid's real sequence, tick by tick, with its gaps named |
| [External integrations](docs/integrations.md) | The four reflection hooks, why commands were abandoned for direct API calls, and what a dependency update breaks |
| [Dashboard API](docs/dashboard.md) | The six REST routes under `/api/v1/raids`, required roles, the single WebSocket message type |
| [Architecture](docs/architecture.md) | Package layout, class responsibilities, state and disk, threading, extension points, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — including the failures that look like success from in-game |

## Commands at a glance

| Command | What it does | Permission |
|---|---|---|
| `/cheeseraids start <raid>` | start a raid now | level 2 |
| `/cheeseraids stop <raid>` | stop it and clean up the arena | level 2 |
| `/cheeseraids status` | every loaded raid, its phase and schedule | level 2 |
| `/cheeseraids reload` | re-read the definition files | level 2 |
| `/cheeseraids herald <raid> info \| show \| hide` | diagnose and toggle the herald NPC | level 2 |
| `/cheeseraids testreward <raid>` | open the portals at the anchor, without a raid | level 2 |
| `/cheeseraids enter <raid>` | enter the open rift | none |

The raid id comes first on every one of them, including `herald`, where the action follows
it. There is no tab completion for the id; `/cheeseraids status` lists them. Full detail in
[commands](docs/commands.md).

## Dashboard

Registers under the `raids` prefix: `/api/v1/raids/*`, with a read-only overview of
definitions and live instances, and start, stop and reload controls. Definitions themselves
are read-only over REST — a raid is edited as a file on disk and reloaded.

One WebSocket channel, `raids`, one message type, emitted on phase transitions only.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
