# CheeseExtraCommands

> Verification commands that let an NPC dialogue tree check a player's Pokémon.

A quest system needs to ask questions a command block cannot answer: does this player
have a Pokémon with max Speed EVs? Five perfect IVs? A Gyarados? Pokémon caught in six
different ball types? These commands answer yes or no, so a dialogue tree or a quest
task can branch on it.

**Minecraft 1.21.1 · Fabric · Java 21 · 7 files, ~700 lines**

---

## Requirements

- Fabric Loader ≥ 0.15.0, Fabric API
- **Cobblemon** — optional at build time, required in practice. Read through
  reflection, so the mod loads without it and the checks simply return false.

No CheeseDash dependency — this one is standalone.

## Commands

All under `/cheesecheck`. Each returns a result a command block or quest task can test.

| Command | Checks |
|---------|--------|
| `/cheesecheck ev <player> <stat> <value>` | EV threshold on a given stat |
| `/cheesecheck iv <player> <count>` | number of perfect IVs |
| `/cheesecheck pokemon <player> <species>` | owns a given species |
| `/cheesecheck ballvariety <player> <count>` | number of distinct Poké Ball types across the party or box |

## Arkensia specifics

The mod also carries an **Island Tour** tracker (`IslandTourTracker`, `IslandTourHud`,
`IslandTourPayload`) — a progression counter for Arkensia's NPC quest island, with a
client HUD. It is tightly coupled to that island's 26-step tour and will not mean
anything on another server, but it is self-contained: ignore it and the verification
commands work fine on their own.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
