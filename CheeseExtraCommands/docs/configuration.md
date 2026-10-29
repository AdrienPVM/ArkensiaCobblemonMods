[← CheeseExtraCommands](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

**This mod has no configuration file.** No `config/cheeseextra.json`, no properties
file, no datapack-driven settings. `FabricLoader.getInstance().getConfigDir()` is never
called anywhere in the source, and nothing is written to disk by the mod itself.

That is a deliberate consequence of its design: every parameter a quest author would want
to tune is passed as a command argument instead. The threshold, the count, the objective
to write and the value to write are all chosen per call. A config file would only
duplicate them.

What follows is what stands in a config file's place: the scoreboard objectives that hold
the state, and the handful of constants compiled into the jar.

---

## Configuration by command argument

The arguments that a config file would otherwise hold:

| What you would configure | Where it lives instead |
|---|---|
| EV threshold | `minEV` on `/cheesecheck ev`, `1`–`252` |
| how many stats must clear it | `statCount`, `1`–`6`, default `1` |
| how many Pokémon must qualify | `teamCount`, `1`–`6`, default `1` |
| IV threshold | `minIV` on `/cheesecheck iv`, `1`–`31` |
| how many IVs must clear it | `count`, `1`–`6` |
| which species | `species` on `/cheesecheck pokemon` |
| how many ball types | `count` on `/cheesecheck ballvariety`, `1`–`20` |
| which objective receives the result | `scoreboard`, on all four |
| what value is written | `value`, on all four |

All of them take effect on the next call. There is nothing to reload and nothing to
restart.

## State lives in the vanilla scoreboard

The mod's only persistent storage is the world's scoreboard, saved by Minecraft in
`world/data/scoreboard.dat`. Two groups of objectives matter.

### Objectives `/cheesecheck` writes

Any objective you name in a `scoreboard` argument. The mod does not reserve names, does
not prefix them, and does not keep a list.

| Property | Value |
|---|---|
| Criterion | `dummy` |
| Display name | the objective's own name, as plain text |
| Render type | `INTEGER` |
| Score holder | the player's **name**, via `ScoreHolder.fromName(...)` |

If the objective does not exist when a check passes, it is created with exactly those
properties and a warning is logged. Pre-creating your objectives with
`/scoreboard objectives add <name> dummy "<display>"` gets you a readable display name
and turns that warning into a real error signal. [Why that matters](commands.md#the-auto-creation-trap).

The write is unconditional: it sets the score, it does not add to it, and it does not
check whether the new value is higher than the old one. A check that passes twice writes
the same value twice, which is harmless. A check pointed at an objective some other
system also writes will fight with it.

### Objectives the island-tour tracker reads

The tracker reads three objective shapes every second and **never creates any of them**.
A missing objective reads as `0`.

| Objective | Read for | Meaning |
|---|---|---|
| `jenny_intro` | every online player | `0` quest not taken, `1` tour in progress, `2` finished and rewarded |
| `spawn_tour` | every online player | how many residents the player has met, `0`–`26` |
| `<key>_met` | each of the 26 residents | `0` not met, `1` or more met |

Create them with `/scoreboard objectives add <name> dummy` before expecting the HUD to
appear. The full list of the 26 keys, and the exact condition under which the HUD shows,
are in [the island tour tracker](island-tour.md).

## Constants compiled into the jar

These are the values you would put in a config file if there were one. Changing any of
them means editing the source and rebuilding.

| Constant | Value | File | What it controls |
|---|---|---|---|
| permission level | `2` | `CheeseCheckCommand.register` | who may run `/cheesecheck` |
| `TICK_INTERVAL` | `20` | `IslandTourTracker` | ticks between HUD state evaluations, so one second |
| `TOTAL` | `26` | `IslandTourTracker` | derived from the size of the `NPCS` map |
| `NPCS` | 26 entries | `IslandTourTracker` | key → display name for the residents |
| HUD title | `Habitants à rencontrer` | `IslandTourHud` | the HUD's heading, hardcoded in French |
| `COLUMNS` | `2` | `IslandTourHud` | how many name columns the HUD draws |
| `COL_WIDTH` | `70` | `IslandTourHud` | column width in scaled pixels; a longer name overlaps the next column |
| `MARGIN_RIGHT` / `MARGIN_BOTTOM` | `5` / `5` | `IslandTourHud` | HUD offset from the bottom-right corner |
| palette | `BG`, `BORDER`, `TITLE`, `COUNTER`, `NAME` | `IslandTourHud` | ARGB values matching the CheeseDash colour scheme |
| Cobblemon class name | `com.cobblemon.mod.common.Cobblemon` | `CobblemonReflect` | the availability probe |
| `Stats` enum class name | `com.cobblemon.mod.common.api.pokemon.stats.Stats` | `CobblemonReflect` | where the six battle stats come from |

Two of those are placeholders in the sense that they will not suit another server:
`NPCS` and the HUD title. Replacing the 26 entries in `IslandTourTracker`'s static
initialiser with your own keys is the only supported way to retarget the HUD, and
`TOTAL` follows automatically because it is `NPCS.size()`.

## No language file

There is no `assets/cheeseextra/lang/` directory, so none of this is translatable. Every
player-facing string is a `Text.literal` in French, written inline in
`CheeseCheckCommand` and `IslandTourHud`. The log lines, by contrast, are in English.

If you need English messages, the strings to change are the seventeen `sendMessage` calls in
`CheeseCheckCommand` and the `title` local in `IslandTourHud.render`. Converting them to
`Text.translatable` with proper lang files would be a welcome contribution; the mod does
not do it today.

## An annotated example, for completeness

Since there is no config file to show, the closest equivalent is the scoreboard setup a
quest-giving NPC needs. Put this in a datapack function and run it once:

```mcfunction
# The objective the NPC branches on. "dummy" because nothing in vanilla should
# ever touch it; only /cheesecheck and the reward function write to it.
scoreboard objectives add mira_progress dummy "Mira"

# Island tour tracker, server-wide. The tracker reads these and never creates
# them, so they have to exist before the HUD can appear.
scoreboard objectives add jenny_intro dummy "Jenny"
scoreboard objectives add spawn_tour dummy "Tour"

# One per resident. 26 of them; see island-tour.md for the full list.
scoreboard objectives add mira_met dummy
scoreboard objectives add bjorn_met dummy
# ...
```

Nothing here is specific to this mod beyond the names. They are ordinary vanilla
objectives, and `/scoreboard objectives remove` disposes of them.

---

← [Commands](commands.md) · [Island tour tracker](island-tour.md) →
