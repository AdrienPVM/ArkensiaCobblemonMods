# ScheduleRestart

> 🇫🇷 [Lire en français](README.fr.md)

> Schedule a server restart or shutdown with a player-facing countdown.

One command, one timer, one source file. You type `/schedulerestart 10` and everyone on
the server gets a bold warning immediately, another one every minute until the last
minute, then a per-second countdown over the final ten seconds. At zero the server saves
and shuts down, and the mod tries to relaunch itself as a fresh JVM process.

The word "tries" is doing real work in that sentence. The relaunch builds a new
`java -jar … nogui` command line out of the running process and starts it with
`ProcessBuilder`. That works on a plain `java -jar fabric-server-launch.jar nogui` setup
and it does not work on most hosting panels. Read
[what happens at zero](docs/restart.md) before you point this at a production server,
because the failure mode is "the server stops and never comes back".

Everything players see is hardcoded French, in the source, with no language file. That is
a limitation rather than a preference: on an English-speaking server your players read
« ⚠ Redémarrage du serveur dans 10 minutes ! ». Changing it means editing
`ScheduledRestart.java` and rebuilding.

**Minecraft 1.21.1 · Fabric · Java 21 · 1 source file, 171 lines**

---

## Requirements

| | |
|---|---|
| **Required** | Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 · [CheeseDash](../CheeseDash/) |
| **Side** | Server only (`"environment": "server"`) — clients need nothing |

⚠️ The CheeseDash requirement is real but pointless. `fabric.mod.json` lists
`cheesedash` under `depends`, so Fabric refuses to start without it, yet the mod
registers no CheeseDash module and never touches a single CheeseDash class. It is a
dependency you have to satisfy and get nothing for. See
[the dependency that does nothing](docs/architecture.md#the-dependency-that-does-nothing).

Fabric API *is* used (`CommandRegistrationCallback`) but **not declared** in
`fabric.mod.json`. Without it the mod dies with a `NoClassDefFoundError` instead of a
readable missing-dependency message.

## Install

```bash
cd ScheduleRestart && ./gradlew build      # gradlew.bat on Windows
```

Drop `build/libs/ScheduleRestart-1.0.0+1.21.1.jar` into the server's `mods/`, alongside
the CheeseDash jar and Fabric API. Nothing goes on the client. No config file is created
on first launch, and nothing is written to disk at any point.

The jar is named after the folder, not after the mod id — details in
[installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, the jar-name trap, what the first launch does and does not create, how to verify it loaded, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: schedule a two-minute shutdown, watch every message, then set up a supervisor so a restart actually restarts |
| [Commands](docs/commands.md) | `/schedulerestart` argument by argument, the permission level, the `stop` keyword trap, every message it produces |
| [The countdown](docs/countdown.md) | The exact timeline: when each warning fires, its wording, its colour, and the arithmetic behind it |
| [What happens at zero](docs/restart.md) | `server.stop(false)`, the self-relaunch, how the jar is located, and why a hosting panel breaks it |
| [Configuration](docs/configuration.md) | There is no config file. What is hardcoded instead, and which line to edit for each behaviour |
| [Architecture](docs/architecture.md) | The single class, the `java.util.Timer` model, threading, the unused dependency, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — including the 261-byte jar and the server that never comes back |

## Commands at a glance

Requires permission level 2.

| Command | What it does |
|---|---|
| `/schedulerestart <minutes>` | announce, count down, then save, stop and try to relaunch |
| `/schedulerestart <minutes> stop` | announce, count down, then save and stop, with no relaunch |

`minutes` accepts **1 to 60** inclusive. The second argument means "shut down" only when
it is literally the word `stop`, in any capitalisation. Every other word is accepted and
silently means restart, so `/schedulerestart 5 shutdown` relaunches the server.

Only one schedule exists at a time, and a new command cancels the previous one. **There
is no cancel command.** The only way to stand down a schedule you regret is to issue a
longer one and then stop the server by hand before it fires.

## Honest status

The mod has been used on the Arkensia server and does what it says there, under one
specific launch setup: `java -jar fabric-server-launch.jar nogui`, no panel. The relaunch
path is the fragile part, and it has never been tested across hosting providers. The
known defects are listed in [known weaknesses](docs/architecture.md#known-weaknesses)
rather than hidden.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
