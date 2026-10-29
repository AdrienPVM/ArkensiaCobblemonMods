# CheeseKart

> 🇫🇷 [Lire en français](README.fr.md)

> Kart racing built on invisible boats and OpenBoatUtils physics.

A player sits in a boat they cannot see, and that boat handles nothing like a boat.
Grip, acceleration, steering rate, jump force, gravity and air control are all pushed
to the client as physics overrides, so the same track can feel like a rally stage or
like an ice rink depending on which preset you started the racer with.

Presets are authored from the CheeseDash dashboard and applied live: change a preset's
grip while three people are mid-race and their karts change under them on the next
packet. That is the point of the mod — you tune a track by driving it, not by
restarting the server.

Admins put players into karts with a command or a dashboard button. There is no
player-facing command: racers never type anything.

**Minecraft 1.21.1 · Fabric · Java 21 · 14 source files, ~1 380 lines**

---

## Requirements

| | |
|---|---|
| **Required** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Strongly recommended** | [OpenBoatUtils](https://modrinth.com/mod/openboatutils), on the client |
| **Optional** | [CobbleShift](../CobbleShift/) — lets racers be transformed Pokémon |

OpenBoatUtils is what actually changes the physics, and it has to be **on the racer's
client**. Without it a kart is an invisible boat with stock boat handling: it still
works, it just is not a kart. The mod detects that case and stays quiet rather than
failing — see [how the physics are delivered](docs/physics.md).

## Install

```bash
cd CheeseDash && ./gradlew build     # CheeseKart compiles against its jar
cd ../CheeseKart && ./gradlew build
```

Drop `build/libs/cheesekart-1.0.0+1.21.1.jar` and the CheeseDash jar in the server's
`mods/`. On first start the mod writes `config/cheesekart.json` with three presets
(`rally`, `drift`, `parkour`) and logs:

```text
[CheeseKart] Created default presets (rally, drift, parkour)
[CheeseKart] Server started — 3 presets loaded
```

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, build order, what the first launch creates, how to verify it loaded, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: from a fresh server to a racer driving a kart you tuned yourself |
| [Commands](docs/commands.md) | Every subcommand, argument by argument, with permission levels, output and failure messages |
| [Configuration](docs/configuration.md) | `cheesekart.json` in full: all 16 preset fields, their defaults, ranges and interactions, plus the three stock presets |
| [Physics and sessions](docs/physics.md) | How a kart is actually built, the OpenBoatUtils packet protocol, locked mode, CobbleShift transforms, session lifecycle |
| [Dashboard API](docs/dashboard.md) | The nine REST endpoints under `/api/v1/kart`, request and response shapes, required roles |
| [Architecture](docs/architecture.md) | Package layout, class responsibilities, threading, the two client mixins, extension points, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — including the three failure modes that look identical from the outside |

## Commands at a glance

All of these need permission level 2.

| Command | What it does |
|---|---|
| `/cheesekart start <player> <preset> [species]` | puts a player in a kart |
| `/cheesekart stop <player>` | ends that player's session |
| `/cheesekart stopall` | ends every session |
| `/cheesekart list` | active sessions, with preset and elapsed time |
| `/cheesekart presets` | configured presets, with their key physics values |

Argument order is easy to get backwards: the **player comes first**, the preset
second. Details in [commands](docs/commands.md).

## Dashboard

Registers under the `kart` prefix, so everything lives at `/api/v1/kart/*`: preset
CRUD, the list of active sessions, and start/stop/stopall controls. A `PUT` on a preset
re-applies it to every session currently using it.

CheeseGuard can also drive the mod during a timed event through two effect types,
`KART_START_ALL` and `KART_STOP_ALL`.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may
not sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
