# CheesePatrol

> Waypoint patrols for EasyNPC entities: NPCs walk a loop and pause to look at nearby
> players.

EasyNPC gives you NPCs that stand still. This makes them walk. Place waypoints with a
wand, set a speed and a wait time, and the NPC patrols the circuit — stopping to turn
toward any player who comes close, which does a surprising amount for making a town
feel alive.

**Minecraft 1.21.1 · Fabric · Java 21 · 10 files, ~2 000 lines · server-side**

---

## Requirements

- **CheeseDash** — required
- **[EasyNPC](https://modrinth.com/mod/easy-npc)** — optional at build time, required
  in practice. Accessed by reflection, so the mod loads without it and does nothing.
- Fabric Loader ≥ 0.16.0, Fabric API, Java 21

`environment` is `server`, so clients do not need it.

Build CheeseDash first, then drop both in `mods/`.

## Commands

All under `/npcpatrol`:

| Subcommand | Purpose |
|------------|---------|
| `wand` | get the waypoint placement tool |
| `select` | select the NPC to configure |
| `add` · `remove` · `clear` | manage waypoints |
| `start` · `stop` | run or halt the patrol |
| `speed <value>` · `wait <ticks>` · `radius <blocks>` | tune the walk |
| `list` · `info` · `show` · `markers` | inspection |
| `particles` · `particlerange` · `particleheight` · `particledepth` | debug visuals |

The particle options draw the route in-world, which is the fastest way to see why an
NPC is stuck on a fence.

## Dashboard

Registers under the `patrol` prefix.

## Known limits

- Pathfinding is EasyNPC's, with its quirks. NPCs will occasionally get caught on
  terrain; keep routes simple and reasonably flat.
- The look-at-player behaviour triggers on proximity with no line-of-sight check, so
  an NPC will turn toward a player through a wall.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
