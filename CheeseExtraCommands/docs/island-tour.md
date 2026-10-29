[← CheeseExtraCommands](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](island-tour.fr.md)

# Island tour tracker

The second half of this mod has nothing to do with `/cheesecheck`. It is a HUD: a panel
in the bottom-right corner of the screen listing, by name, the Arkensia residents a
player has yet to meet, with a counter. It exists because a 26-NPC scavenger hunt is
unplayable if the player has to remember who they already talked to.

⚠️ It is hardcoded to one island. The 26 names live in a static map in
`IslandTourTracker`, and the HUD's title is a French string literal. On any other server
the HUD will either never appear or will list names that mean nothing. Retargeting it
means editing the source and rebuilding. Nothing breaks if you leave it alone: with the
objectives absent, the tracker evaluates to "hidden" and sends nothing.

---

## The model

Three pieces, each in its own file.

| Piece | File | Runs on |
|---|---|---|
| State reader | `com.cheeseextra.arkensia.IslandTourTracker` | server thread |
| Wire format | `com.cheeseextra.network.IslandTourPayload` | both, S2C |
| Renderer | `com.cheeseextra.client.IslandTourHud` | client render thread |

There is no new persistent state. The tracker reads the vanilla scoreboard, which the
NPC dialogues were already writing, and derives everything from it. That is the design
decision worth understanding: the mod is a *view* over state owned by the quest system,
not a second source of truth. Nothing can desynchronise, because nothing is duplicated.

## The scoreboard state machine

Three objective shapes drive it. The tracker never creates any of them, and a missing
objective reads as `0`.

| Objective | Written by | Values |
|---|---|---|
| `jenny_intro` | Jenny's dialogue, the quest giver | `0` never spoke to her, `1` tour accepted and in progress, `2` tour completed and rewarded |
| `spawn_tour` | each resident's first-meet dialogue, incremented | `0`–`26` |
| `<key>_met` | the matching resident's first-meet dialogue | `0` not met, `1` met |

The HUD is shown when, and only when:

```java
boolean show = introVal >= 1 && introVal < 2 && tourVal < TOTAL;
```

For integers `>= 1 && < 2` means exactly `1`. So the panel is visible while
`jenny_intro == 1` and `spawn_tour < 26`. Taking the quest makes it appear, finishing it
makes it disappear, and a player who never spoke to Jenny never sees it.

`spawn_tour` and the `<key>_met` objectives are redundant on purpose. `spawn_tour` is the
cheap gate, read once per player per second. The 26 `<key>_met` objectives are only read
when the gate has already passed, and they are what produces the actual list of names.
If the two ever disagree (a dialogue that increments `spawn_tour` without setting
`<key>_met`, say) the counter in the HUD follows the `_met` objectives, not `spawn_tour`,
because `met` is computed as `TOTAL - remaining.size()`.

## The 26 residents

The order below is the order in `IslandTourTracker.NPCS`, a `LinkedHashMap`, and it is
the order the HUD lists them in. It is alphabetical by display name, with one exception:
`Sven` comes before `Suzy`.

| Key | Display name | Objective read |
|---|---|---|
| `alfred_main` | Alfred | `alfred_main_met` |
| `bjorn` | Bjorn | `bjorn_met` |
| `brick` | Brick | `brick_met` |
| `chen` | Chen | `chen_met` |
| `dante` | Dante | `dante_met` |
| `dino` | Dino | `dino_met` |
| `ectoplasma` | Ectoplasma | `ectoplasma_met` |
| `finn` | Finn | `finn_met` |
| `flint` | Flint | `flint_met` |
| `joelle` | Joelle | `joelle_met` |
| `kai` | Kai | `kai_met` |
| `lucius` | Lucius | `lucius_met` |
| `max` | Max | `max_met` |
| `metamorph` | Metamorph | `metamorph_met` |
| `mira` | Mira | `mira_met` |
| `nemo` | Nemo | `nemo_met` |
| `nina` | Nina | `nina_met` |
| `primo` | Primo | `primo_met` |
| `rosalia` | Rosalia | `rosalia_met` |
| `selena` | Selena | `selena_met` |
| `stella` | Stella | `stella_met` |
| `sven` | Sven | `sven_met` |
| `suzy` | Suzy | `suzy_met` |
| `violette` | Violette | `violette_met` |
| `woody` | Woody | `woody_met` |
| `zora` | Zora | `zora_met` |

`TOTAL` is `NPCS.size()`, so it is `26` by construction rather than by a separate
constant. Add an entry to the map and the threshold moves with it. The display names
carry no accents, which is why `Joelle` and `Metamorph` appear unaccented in game.

The map is wrapped in `Collections.unmodifiableMap`, so nothing can mutate it at runtime.

## The lifecycle

### Every second

`ServerTickEvents.END_SERVER_TICK` calls `IslandTourTracker.onServerTick`. A static
counter skips 19 ticks out of 20, so the body runs once per second.

For each online player:

1. Read `jenny_intro` and `spawn_tour`.
2. Decide `show`.
3. If `show`, read all 26 `<key>_met` objectives and build the list of names still
   missing. If not, use an empty list and set `met` to `min(spawn_tour, 26)`.
4. Compare the result against the last snapshot sent to that player.
5. If it differs, send an `IslandTourPayload` and store the new snapshot.

Step 4 is why this costs nothing. The snapshot is a record of `(show, met, remaining)`,
and a player standing still generates an identical snapshot every second, so no packet
goes out. A packet is sent only when the player's tour state actually changed, which
happens at most a few dozen times over a whole playthrough.

Both objectives are looked up once per tick for the whole server, outside the player
loop. The 26 `_met` lookups, by contrast, happen per player, but only for players whose
gate passed.

### On join

`ServerPlayConnectionEvents.JOIN` calls `onPlayerJoin`, which removes the player's cached
snapshot and then runs the same `update`. Clearing the cache first is what forces a send:
a reconnecting player has no HUD state client-side, so the server must re-send even
though nothing changed server-side.

### On disconnect

`ServerPlayConnectionEvents.DISCONNECT` removes the snapshot. The map is a
`ConcurrentHashMap` keyed by UUID and this is the only thing that trims it, so a server
that never saw a disconnect event for a player would leak one small record. In practice
Fabric always fires it.

## The payload

`IslandTourPayload` is a record and a Fabric `CustomPayload`, registered S2C only:

```java
PayloadTypeRegistry.playS2C().register(ID, CODEC);
```

| Field | Wire type | Meaning |
|---|---|---|
| `show` | boolean | whether the HUD should be visible at all |
| `met` | VarInt | residents met, for the counter |
| `total` | VarInt | always `26` as the code stands, but sent rather than assumed |
| `remaining` | VarInt length, then that many strings capped at 64 chars | display names still to meet |

Channel id: `cheeseextra:island_tour`.

Sending `total` over the wire instead of hardcoding it client-side means a server whose
`NPCS` map was edited works with an unmodified client jar. That only helps if the names
also come from the server, which they do.

The send is wrapped in a `try/catch` that logs at DEBUG, so a server-side failure to send
never escapes the tick handler: the HUD is optional decoration, not something worth
breaking the tick over. That `catch` only covers the server side; what a client without
the mod does with the unknown channel is decided on the client, out of this mod's reach.

## The renderer

`IslandTourHud` keeps four `volatile` static fields updated from the payload and draws
them from `HudRenderCallback`. It returns early, drawing nothing, when any of these hold:

- `visible` is false
- `remaining` is empty, even if `visible` is true
- the player is null, the HUD is hidden with F1, or a screen is open

That second condition matters: once a player has met all 26 residents, the server may
still be sending `show = true` for a tick or two, and the HUD hides itself anyway rather
than drawing an empty box.

Layout, from `IslandTourHud`'s constants:

- anchored bottom-right, 5 scaled pixels from each edge
- title `Habitants à rencontrer` in amber (`0xFFF5A623`), counter `(met/total)` in green
  (`0xFF22C55E`), right-aligned on the same line
- names in grey (`0xFFCCCCCC`), two columns, column-major: the first column fills to the
  bottom before the second begins
- background `0xCC1A1D28` with a `0x80F5A623` one-pixel border, matching the CheeseDash
  palette

Row count is `ceil(remaining / 2)`, so the box shrinks as the player works through the
list. Column width is a flat `70` scaled pixels. A display name wider than that overlaps
its neighbour, which is why every name in `NPCS` is short.

## Driving it from your own quests

Nothing in the tracker is callable. There is no command, no API, no entrypoint. The only
way to make the HUD appear is to put the right values in the right objectives:

```mcfunction
# once, server-wide
scoreboard objectives add jenny_intro dummy
scoreboard objectives add spawn_tour dummy
scoreboard objectives add mira_met dummy
# ... 25 more

# in Jenny's "accept quest" dialogue
scoreboard players set @s jenny_intro 1

# in each resident's first-meet dialogue, Mira's for example
execute unless score @s mira_met matches 1.. run scoreboard players add @s spawn_tour 1
scoreboard players set @s mira_met 1

# in Jenny's "hand over the reward" dialogue
scoreboard players set @s jenny_intro 2
```

The `execute unless` guard on the increment is not optional. `spawn_tour` is a plain
counter with nothing stopping it from being incremented twice by the same NPC, and if it
overshoots `26` the gate closes and the HUD vanishes while names are still missing.

## Retargeting it to another server

Three edits, then rebuild:

1. Replace the entries in `IslandTourTracker`'s static block with your own
   `key → display name` pairs. `TOTAL` follows.
2. Rename `jenny_intro` and `spawn_tour` in `onServerTick` and `onPlayerJoin` (both look
   them up by name) if your quest giver is not called Jenny, or create objectives with
   those names and leave the code alone.
3. Change the `title` string in `IslandTourHud.render` if French does not suit.

Keep display names under roughly ten characters or widen `COL_WIDTH` to match.

---

← [Configuration](configuration.md) · [Architecture](architecture.md) →
