# CheeseExtraCommands

> 🇫🇷 [Lire en français](README.fr.md)

> Four verification commands that let an NPC dialogue tree inspect a player's Cobblemon
> party and write the answer to a scoreboard.

A quest system built on NPC dialogue has a blind spot: it can hand out items and read
scoreboards, but it cannot look inside a player's party. `/cheesecheck` fills that gap.
It answers four questions (does a Pokémon in this party carry enough EVs, enough high
IVs, is this species present, were these Pokémon caught in enough different ball types)
and on a pass it writes a value into a scoreboard objective of your choosing. The NPC's
next dialogue branch reads that objective and moves on.

The design choice that matters: on a pass the command sets the score and returns `1`; on
a failure it sets nothing and returns `0`. Quest progress therefore stays where it was,
and the NPC shows its "still working on it" line the next time the player talks to it.
No rollback logic, no second command.

The mod also carries the **Arkensia island tour** tracker: a server-side scoreboard
watcher that pushes a bottom-right HUD listing the island residents a player has yet to
meet. That half is hard-wired to one specific island and its 26 NPCs. It is inert
anywhere else and it is documented honestly as such.

**Minecraft 1.21.1 · Fabric · Java 21 · 7 source files, ~840 lines**

⚠️ Every player-facing message in this mod is hardcoded **in French**, with
`Text.literal`. There is no language file, so there is nothing to translate and no way
to switch it to English short of editing `CheeseCheckCommand.java`.

---

## Requirements

| | |
|---|---|
| **Required** | Fabric Loader ≥ 0.15.0 · Fabric API · Minecraft 1.21.x · Java 21 |
| **Optional, but required in practice** | [Cobblemon](https://modrinth.com/mod/cobblemon) 1.6.1 |

Cobblemon is reached entirely through reflection (`CobblemonReflect`), so there is no
compile-time or load-time dependency on it. Without Cobblemon installed the mod loads,
the command registers, and every check tells the player
`[CheeseCheck] Cobblemon non disponible.` and returns `0`.

No CheeseDash dependency. This mod registers no dashboard module and no REST routes.

## Install

```bash
cd CheeseExtraCommands && ./gradlew build
```

Drop `build/libs/cheeseextra-1.0.0+1.21.1.jar` into the server's `mods/`. There is no
config file to create. The server log shows:

```text
[CheeseExtra] Initializing CheeseExtraCommands...
[CheeseExtra] Commands + Arkensia island-tour tracker registered.
[CheeseExtra] /cheesecheck command registered (ev, iv, pokemon, ballvariety)
```

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, which side to install it on, the log lines that prove it loaded, clean removal |
| [Getting started](docs/getting-started.md) | A complete chain: an NPC sets a condition, a command verifies it, a reward depends on it |
| [Commands](docs/commands.md) | All four subcommands argument by argument, every return value, every failure message, integration patterns |
| [Configuration](docs/configuration.md) | There is no config file. What stands in its place: scoreboard objectives, and the constants compiled into the jar |
| [Island tour tracker](docs/island-tour.md) | The Arkensia HUD subsystem: the scoreboard state machine, the S2C payload, the 26 hardcoded NPCs |
| [Architecture](docs/architecture.md) | Package layout, the reflection layer and why it scans by type, threading, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix, including the three reasons a check silently returns zero |

## Commands at a glance

One command, `/cheesecheck`, four subcommands, **permission level 2** on the whole tree.
Every subcommand ends with a `<scoreboard> <value>` pair: the objective to write and the
value to write into it when the check passes.

| Command | Passes when |
|---|---|
| `/cheesecheck ev <player> <minEV> <scoreboard> <value> [statCount] [teamCount]` | at least `teamCount` party Pokémon each have `statCount` stats at `minEV` EVs or more |
| `/cheesecheck iv <player> <count> <minIV> <scoreboard> <value> [teamCount]` | at least `teamCount` party Pokémon each have `count` IVs at `minIV` or more |
| `/cheesecheck pokemon <player> <species> <scoreboard> <value>` | the party contains that species |
| `/cheesecheck ballvariety <player> <count> <scoreboard> <value>` | the party holds at least `count` distinct caught-ball types |

Two things catch people out. The `ev` subcommand has **no stat selector**: it counts how
many stats clear the threshold, it does not let you require Speed specifically. And all
four read the **party only**, never the PC boxes.

## Honest warnings

- Feedback goes to the **target player**, not to the command's caller. An admin running
  a check on someone else sees nothing in their own chat.
- Scores are written against the player's **name**, not their UUID. A player who renames
  loses their quest progress.
- A missing scoreboard objective is created automatically rather than refused, so a typo
  in the objective name produces a silent, permanently-unread objective.
- The island tour tracker is specific to Arkensia and will show nothing on another
  server unless you recreate its scoreboard objectives.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
