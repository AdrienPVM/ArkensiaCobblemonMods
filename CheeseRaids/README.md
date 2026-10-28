# CheeseRaids

> ## ⚠️ Experimental — never tested in a live game
>
> This framework compiles, and it was deployed to a staging server, but **the first
> raid was never actually run with players.** The coordinates in its config are
> placeholders that have to be calibrated in-world. Treat it as a design sketch you
> can build on, not a finished feature.

A modular framework for timed multiplayer raids: an announcement wave, a staging NPC,
a portal into a procedural arena, a co-op boss, and rewards. The first (and so far
only) raid implemented is **Hoopa Invasion**.

**Minecraft 1.21.1 · Fabric · Java 21 · 21 files, ~3 100 lines**

---

## Requirements

- **CheeseDash** — required
- **CheeseGuard** — required (arenas are CheeseGuard dimensions, raids hook its events)
- Fabric Loader ≥ 0.16.0, Fabric API, Java 21

Build CheeseDash and CheeseGuard first, then drop all three in `mods/`.

## The Hoopa raid, as designed

1. a warning wave announces the invasion
2. an EasyNPC herald (Ho-Oh) appears at a staging point
3. a star-shaped rift opens — players reach it through parkour
4. a giant boss fight, co-operative
5. portals return survivors, rewards are handed out

## Commands

| Command | Purpose |
|---------|---------|
| `/cheeseraids start <id>` · `stop` · `status` | lifecycle |
| `/cheeseraids enter` | join the active raid |
| `/cheeseraids herald show \| hide` | the staging NPC |
| `/cheeseraids info` · `reload` | inspection and config reload |
| `/cheeseraids testreward` | dry-run the reward table |

## Configuration

`config/cheeseraids/raids/hoopa.json` holds the raid definition — spawn points, portal
positions, boss parameters, reward table.

**The coordinates in it are placeholders.** They have to be set to real positions in
your world before the raid will work. Build the arena, note the coordinates, put them
in the file, then `/cheeseraids reload`.

## If you want to add a raid

The framework is the point, not Hoopa. A raid is a definition file plus the command
templates it uses (spawning Cobblemon as uncatchable, opening a portal, placing an
EasyNPC). Copy `hoopa.json`, change the stages, and register it.

## Dashboard

Registers under the `raids` prefix.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
