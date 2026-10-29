# CobbleShift

> 🇫🇷 [Lire en français](README.fr.md)

> Turn players and NPCs into Pokémon, and give players type-based passive buffs from their party.

An admin types `/cshift transform Steve charizard`, and every client with the mod stops
drawing Steve and draws a Charizard in his place. Steve keeps his inventory and his
position. His speed, maximum health and hitbox follow Charizard's base stats, his camera
moves to third person behind the Pokémon, and a left click throws a fire charge instead
of swinging an arm. The server never spawns a Pokémon: the player is made invisible and
each client renders a ghost model at his coordinates.

The same renderer turns EasyNPC characters into Pokémon. An NPC transform is purely
visual and is saved to disk, so the Snorlax blocking a road is still there after a
restart, while player transforms are meant to last an event and end on a clean stop.

The third piece is unrelated to transforming. With passive abilities switched on, the
Pokémon in the first slot of a player's party grants a themed status effect: a Fire type
gives Fire Resistance, a Water type gives Water Breathing and Dolphin's Grace, and the
buff follows the party as it changes. CheeseGuard events and CheeseKart races drive
transforms too, and a CheeseDash module exposes the whole thing over REST.

**Minecraft 1.21.1 · Fabric · Java 21 · 34 files, ~6 780 lines**

---

## Requirements

| | |
|---|---|
| **Required** | Fabric Loader ≥ 0.16.0 · [Fabric API](https://modrinth.com/mod/fabric-api) · Java 21 · the mod on **both** server and clients |
| **Required in practice** | [Cobblemon](https://modrinth.com/mod/cobblemon). Declared optional, but without it every species lookup fails |
| **Optional** | [CheeseDash](../CheeseDash/) for the admin panel · [EasyNPC](https://modrinth.com/mod/easy-npc) for NPC transforms |

CobbleShift reaches Cobblemon only by reflection and never imports it, which is why the
jar loads without Cobblemon. It also means a Cobblemon update can break species lookups
or the ghost model silently; see
[architecture](docs/architecture.md#reflection-and-why-the-method-names-matter).

## Install

```bash
cd CheeseDash && ./gradlew build     # CobbleShift compiles against its jar
cd ../CobbleShift && ./gradlew build
```

Drop `build/libs/cobbleshift-1.0.0+1.21.1.jar` into `mods/` on the server **and** on every
client. A client without it sees a transformed player as nobody at all. On first start
the server writes `config/cobbleshift.json` and logs:

```text
[CobbleShift] Initializing...
[CobbleShift] Initialized successfully!
```

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, build order, the files a first start creates, how to prove it loaded, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: from a fresh server to a transformed Charizard, a passive buff and an NPC that survives a restart |
| [Commands](docs/commands.md) | All thirteen `/cshift` subcommands, argument by argument, with output and every failure message |
| [Configuration](docs/configuration.md) | The sixteen keys of `cobbleshift.json`, their clamps, the three that do nothing, an annotated file |
| [Transforms](docs/transforms.md) | What a player transform applies and removes, the stat formulas, attacks, duration, crashes |
| [Passive abilities](docs/abilities.md) | The refresh loop, the effect of each type, how the party lead is read |
| [NPC transforms](docs/npc-transforms.md) | EasyNPC targeting, the visual-only model, the speed modifier, the save file and its traps |
| [Dashboard API](docs/dashboard.md) | The fifteen REST routes under `/api/v1/shift`, request and response shapes, required roles |
| [Architecture](docs/architecture.md) | Packages, state and disk, threading, payloads, the ten mixins, reflection, the de facto external API, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix, and what to put in a bug report |

## Commands at a glance

All of them need permission level 2.

| Command | What it does |
|---|---|
| `/cshift transform <player> <pokemon [shiny] [noattacks] [form…]>` | transforms a player |
| `/cshift revert <player>` · `/cshift revertall` | reverts one player, or all |
| `/cshift scale <player> <value>` · `/cshift speed <player> <value>` | resizes or respeeds a transformed player |
| `/cshift ability <player> [pokemon]` | forces a passive ability, or returns to automatic |
| `/cshift ability global enable\|disable` | the master switch for passive abilities, saved to disk |
| `/cshift ability toggle <player>` | opts one player out of passive abilities, until a restart |
| `/cshift transformnpc <pokemon…>` · `revertnpc` · `revertallnpc` | the same for the EasyNPC you are looking at |
| `/cshift scalenpc <value>` · `/cshift speednpc <value>` | tunes the NPC you are looking at |
| `/cshift list` | active player transforms and abilities |
| `/cshift species <name>` | a species' stats and what CobbleShift derives from them |

The player comes first in `transform`. There is no command for a player to transform or
revert themselves. Details in [commands](docs/commands.md).

## Honest warnings

- ⚠️ **Death does not revert a transform**, although the config key `autoRevertOnDeath`
  defaults to `true`. Nothing calls the code behind it.
- **Vanilla clients see transformed players as invisible.** Install the mod on every
  client.
- **Older documentation describes a CobbleHunt minigame and a Hot Potato game.** Neither
  exists in this source. Two `hotPotato*` config keys remain and are never read.
- **Player transforms do not survive a crash.** The next join cleans up the leftovers; see
  [the transform model](docs/transforms.md#disconnects-restarts-and-crashes).
- The mod ran on the Arkensia server. It has no automated tests.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may
not sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
