[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands — zones, flags, groups and states

CheeseGuard registers three root commands: `/cg`, `/prison` and `/chunksuggest`. There is
no `/cheeseguard` alias — the root literal in `CheeseGuardCommand` is `cg`, and nothing
registers a longer name.

This page covers the zone side of the `/cg` tree: selection, zone CRUD, flags, members,
groups, saved zone states and the player purchase command. The rest of the tree — events,
prison, per-player worlds, minigames, inventory restore and the other operational
subcommands — is on [the operations page](commands-operations.md).

## Permission levels

Brigadier gates a node with `requires(src -> src.hasPermissionLevel(2))` — the level a
vanilla server grants to an operator. Every mutating subcommand on this page carries that
gate; a few read-only and player-facing ones deliberately do not.

| Subcommand | Gate in code | Effective access |
|---|---|---|
| `/cg` (bare) | root is ungated; `openGui` checks level 2 itself | everyone — non-ops get a help list, ops get a notice |
| `gui` | level 2 | operators |
| `wand`, `pos1`, `pos2`, `create`, `delete`, `list`, `info` | level 2 | operators |
| `flag`, `addmember`, `removemember` | level 2 | operators |
| `group create`, `group delete`, `group add` | level 2 | operators |
| `state capture/list/delete/apply` | level 2 | operators |
| `tp`, `toggle` | none | everyone |
| `buy` | none (deliberate — it is the player-facing purchase) | everyone |
| `/chunksuggest` | none (deliberate) | everyone |

⚠️ `flag`, `addmember`, `removemember` and the whole `group` subtree were **ungated until
29 October 2026**: any player could disarm the protection on any zone whose id they
guessed, and zone ids are derived from zone names, so they are guessable. They are gated
now. If you are running an older jar, gate those nodes with a permissions mod or keep the
server whitelisted.

`tp` and `toggle` staying open is deliberate and much smaller, but worth knowing: any
player can teleport to a zone's centre coordinates, which leaks the location of whatever
you built behind the protection.

A player below the required level does not see a gated subcommand in tab completion and
gets the vanilla *Unknown or incomplete command* error if they type it.

Every subcommand on this page except `state`, `playerworld` and the `/prison` tree needs
to be run **by a player**, not from the console: the handler calls
`ctx.getSource().getPlayerOrThrow()` and silently returns `0` when there is no player.
A console invocation therefore looks like it did nothing, with no error message. That is
a defect, not a design choice.

---

## `/cg`

```mcfunction
/cg
/cg gui
```

Both forms run the same handler. For an operator it prints one line:

```text
[CheeseGuard] Administration available on the CheeseDash web dashboard.
```

There is no in-game admin GUI any more. It was deleted (`CheeseGuardScreen`,
`EventEditorScreen`) when administration moved to CheeseDash, and the `gui` literal is
what remains of it.

For a player below level 2, bare `/cg` prints the player-facing help instead (`/cg gui`
itself is gated at level 2, so a non-op gets *Unknown or incomplete command*):

```text
═══ CheeseGuard ═══
/cg event join — Join an event
/cg event leave — Leave an event
/cg event spectate — Spectate an event
/cg event list — View running events
```

That list is the honest summary of what a normal player is *meant* to be able to do.

---

## Selecting a volume

A zone is a box. You define it by marking two opposite corners, then naming it.

### `/cg wand`

```mcfunction
/cg wand
```

Level 2. Inserts a golden axe named *Zone Selection Wand* into your inventory and
confirms above the hotbar:

```text
Zone wand received!
```

Left-click a block with it to set corner 1, right-click to set corner 2 (handled by
`WandListener`). The wand is an ordinary golden axe with a custom name — it is not a
registered item, so it survives nothing special and anybody can hold it.

### `/cg pos1` / `/cg pos2`

```mcfunction
/cg pos1
/cg pos2
```

Level 2. Marks the corner at **your own block position**, in the world you are standing
in, without needing the wand. Useful from a command block chain or when you are flying.

```text
Position 1 set: 118, 64, -302
```

```text
Position 2 set: 142, 90, -276
```

The two corners are stored per player in `SelectionManager`, keyed by UUID, and they are
in-memory only: a server restart clears every pending selection.

A selection is only usable once **both** corners are set. `pos1` and `pos2` record the
world id along with the position, but a selection holds a single world id, overwritten by
whichever corner was set last; nothing checks that both corners were marked in the same
world, and the zone is created in the world of the last corner.

### `/cg create`

```mcfunction
/cg create <name>
```

Level 2.

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `name` | greedy string | yes | any text; swallows the rest of the line, so spaces need no quoting | — |

The zone id is derived from the name: lowercased, then every character outside
`[a-z0-9_-]` replaced with `_`. `My Arena #2` becomes `my_arena__2`. Two different names
can collide into the same id, and nothing checks for that — the second `create` overwrites
the first entry in `regions.json`.

The box is normalised (min/max per axis), so the order you marked the corners in does not
matter. The creating player becomes the zone's **owner** (not an entry in its member
list, which starts empty — hence `Members: 0` in `/cg info`).

```mcfunction
/cg create Market Square
```

```text
Region 'Market Square' created!
```

| Message | Cause |
|---|---|
| `No selection! Use the wand.` | no selection at all, or only one corner set |
| `Selection incomplete!` | same condition, reported from `createRegion` |

### `/cg delete`

```mcfunction
/cg delete <id>
```

Level 2. `id` is a single word — the zone **id**, not its display name. `/cg list` shows
both.

```mcfunction
/cg delete market_square
```

```text
Zone deleted: market_square
```

| Message | Cause |
|---|---|
| `Zone not found: <id>` | no zone with that id |

Deletion writes `regions.json` immediately, and also removes the zone id from every
group's zone list (`RegionStorage.removeRegion`) — see [groups](groups.md).

---

## Inspecting zones

### `/cg list`

```mcfunction
/cg list
```

Level 2. Every zone in every world, with its id and a shortened world name.

```text
Zones (3)
• Market Square [market_square] – Overworld
• Prison [prison] – Overworld
• Boss Arena [boss_arena] – Nether
```

The world column is run through a helper that recognises only the three vanilla worlds by
substring (`overworld`, `nether`, `end`); any other dimension id is printed raw. A custom
dimension whose id happens to contain `end` — `arkensia:endless_cave`, say — is mislabelled
as *End*. Cosmetic, but confusing when you are hunting for a zone.

There is no filter and no paging. With a few hundred zones this floods the chat; read them
from the dashboard instead.

### `/cg info`

```mcfunction
/cg info <id>
```

Level 2.

```mcfunction
/cg info market_square
```

```text
Market Square
ID: market_square | Overworld
Min: 118,64,-302
Max: 142,90,-276
Group: aucun
Priority: 0 | Members: 0
```

The `Group:` line prints the literal string `aucun` when the zone belongs to no group,
in both languages — the fallback is hardcoded in Java instead of coming from the language
file. Flags are not shown; `info` only covers geometry, group, priority and the member
count.

| Message | Cause |
|---|---|
| `Zone not found.` | no zone with that id |

### `/cg tp`

```mcfunction
/cg tp <id>
```

**No permission gate.** Teleports you to the zone's horizontal centre, in the world you
are currently in — not in the zone's own world. Teleporting to a Nether zone from the
Overworld drops you at those coordinates in the Overworld.

The Y coordinate is chosen by scanning from the zone's `maxY` downwards for the first
level with a non-air block below and two non-solid blocks above, and landing on top of it.
If no such level exists the zone's centre Y is used as-is, which can put you inside stone.

```text
TP to: Market Square
```

| Message | Cause |
|---|---|
| `Zone not found.` | no zone with that id |

### `/cg toggle`

```mcfunction
/cg toggle
```

**No permission gate.** Toggles the particle outline of nearby zones for you alone,
drawn by `VisualizationManager`.

```text
Visualization: Enabled
```

```text
Visualization: Disabled
```

The state is per player and in memory only.

---

## Flags

### `/cg flag`

```mcfunction
/cg flag <region> <flag> <value>
```

Level 2.

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `region` | single word | yes | a zone id | — |
| `flag` | single word | yes | a flag id from the table below | — |
| `value` | single word | yes | `true`, `false`, `inherit`, `null` | — |

`inherit` and `null` (case-insensitive) clear the flag, so the zone falls back to its
group, then to the dimension's denied flags, and otherwise the action is allowed. Anything else goes through
`Boolean.parseBoolean`, which means every unrecognised value — `yes`, `1`, `allow`,
a typo — is read as `false`. There is no validation and no warning. This is the single
easiest way to accidentally forbid something.

No tab completion is wired on the `flag` or `value` arguments: they are plain word
arguments, so you have to know the spelling.

The 31 flag ids:

| Flag id | What it controls | Members exempt |
|---|---|---|
| `block-break` | breaking blocks | yes |
| `block-place` | placing blocks | yes |
| `interact` | chests, doors, NPC interaction | yes |
| `entity-damage` | attacking entities and mobs | yes |
| `pvp` | attacking players | yes |
| `use-items` | item use (bucket, flint and steel…) | yes |
| `explosions` | TNT, creepers | yes |
| `fire-spread` | fire spreading | no |
| `mob-spawning` | natural mob spawns | no |
| `mob-grief` | mob griefing (enderman, grass eating) | no |
| `trample` | farmland trampling | no |
| `item-drop` | dropping items | yes |
| `item-pickup` | picking items up | yes |
| `ender-pearl` | ender pearl teleports | yes |
| `fly` | flight (elytra, creative) | no |
| `cobblemon-fly` | Cobblemon flying mounts | no |
| `cobblemon-mount` | Cobblemon ground mounts | no |
| `cobblemon-summon` | sending out Pokémon | no |
| `fall-damage` | fall damage | no |
| `hunger` | hunger drain | no |
| `entry` | entering the zone | yes |
| `exit` | leaving the zone | yes |
| `leaf-decay` | leaf decay | no |
| `ice-melt` | ice melting | no |
| `snow-form` | snow forming | no |
| `vine-grow` | vine and creeper growth | no |
| `redstone` | redstone mechanisms | no |
| `nametag-hide` | hides nametags (client mixin) | no |
| `minimap-hide` | hides the minimap (Jade / JourneyMap mixins) | no |
| `coral-death` | coral dying out of water | no |
| `cobbleshift-transform` | CobbleShift transformation | no |

*Members exempt* is the `memberExempt` field on the enum. For a `yes` flag, the zone's
members and owner bypass the restriction — the classic protection behaviour, where you can
break your own build. For a `no` flag the restriction applies to everyone including the
owner, because it describes the environment rather than a permission.

The semantics of each flag, inheritance and priority are in
[zones and flags](zones-and-flags.md).

```mcfunction
/cg flag market_square block-break false
```

```text
Flag block-break = false
```

```mcfunction
/cg flag market_square pvp inherit
```

```text
Flag pvp = inherit
```

| Message | Cause |
|---|---|
| `Zone not found.` | no zone with that id |
| `Unknown flag: <id>` | the flag id is not in the table above — check the hyphens |

Both a set and a clear save `regions.json` synchronously, on the server thread.

---

## Members

### `/cg addmember`

```mcfunction
/cg addmember <region> <player>
```

Level 2.

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `region` | single word | yes | a zone id |
| `player` | single word | yes | the name of an **online** player |

The target has to be online, because the handler resolves them through
`PlayerManager.getPlayer(name)` to capture their UUID. There is no offline path and no
user-cache fallback — unlike `/prison`, which does have one.

```mcfunction
/cg addmember market_square Steve
```

```text
Steve added.
```

| Message | Cause |
|---|---|
| `Zone not found.` | no zone with that id |
| `Player not found.` | the player is not connected |

Adding a member who is already a member is not rejected.

### `/cg removemember`

```mcfunction
/cg removemember <region> <player>
```

Level 2. Matches members by display name, case-insensitively, and removes the first
match.

```mcfunction
/cg removemember market_square steve
```

```text
steve removed.
```

The name in the message is the one you typed, not the stored one. The success message is sent **whether or not anything was removed**: the handler reports
success unconditionally after the lookup. Run `/cg info <id>` and compare the member count
if you need to be sure. Only `Zone not found.` is a real failure here.

Members are stored by UUID *and* name. A player who changes their Minecraft name keeps
their membership (UUID match in protection checks) but can no longer be removed by their
new name, because this command matches on the stored name.

---

## Groups

A group is a named bundle of zones that share flag defaults. Zones resolve a flag they do
not set themselves from their group. The model is described in [groups](groups.md); this
is the command surface.

The whole `group` subtree requires **level 2**.

### `/cg group create`

```mcfunction
/cg group create <name>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `name` | greedy string | yes | any text |

The id is slugged from the name the same way `/cg create` does it.

```mcfunction
/cg group create Public Areas
```

```text
Group created: Public Areas
```

| Message | Cause |
|---|---|
| `Group already exists.` | a group with that slugged id is already stored |

### `/cg group delete`

```mcfunction
/cg group delete <id>
```

```text
Group deleted: public_areas
```

The confirmation is unconditional — deleting a group that never existed reports success.
Zones that pointed at the group have their `groupId` cleared (`RegionStorage.removeGroup`),
so they fall back to their own flags and the flag defaults.

### `/cg group add`

```mcfunction
/cg group add <region> <group>
```

Assigns a zone to a group, writing both sides of the link: the zone's `groupId` and the
group's zone list.

```mcfunction
/cg group add market_square public_areas
```

```text
Market Square assigned to group Public Areas
```

| Message | Cause |
|---|---|
| `Zone or group not found.` | either id is unknown — the message does not say which |

There is no `group remove`. To detach a zone, reassign it from the dashboard.

---

## Saved zone states

A *state* is a captured copy of every non-air block (with block-entity data) and every
non-player entity inside a zone, saved under `config/cheeseguard_states/<zoneId>/`.
Re-applying one rebuilds the zone, optionally as an animation: the non-player entities
currently in the box are discarded and the captured ones are spawned again. Arena resets and seasonal decoration both use this.

The whole `state` subtree requires **level 2**, and unlike most of this page it works from
the console.

### `/cg state capture`

```mcfunction
/cg state capture <zoneId> <name>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `zoneId` | single word | yes | a zone id |
| `name` | greedy string | yes | a label for the capture |

```mcfunction
/cg state capture boss_arena Pristine
```

```text
State 'Pristine' captured (18432 blocks, 3 entities)
```

Capture walks the whole box, so the cost grows with the volume. A 100×100×100 zone is a
million block reads on the server thread — expect a tick spike. Capture small zones, or
capture when nobody is online.

| Message | Cause |
|---|---|
| `Zone not found: <id>` | no zone with that id |

### `/cg state list`

```mcfunction
/cg state list <zoneId>
```

```text
States for zone 'boss_arena':
  1 — Pristine (18432 blocks, 3 entities)
```

The first column is the state id you pass to `apply` and `delete`. State ids are
sequential integers per zone (`1`, `2`, …), taken from the `nextId` counter in the zone's
`index.json`.

| Message | Cause |
|---|---|
| `No states for zone '<id>'` | the zone has no captures — also what you get for an unknown zone id |

### `/cg state delete`

```mcfunction
/cg state delete <zoneId> <stateId>
```

```text
State '1' deleted from zone 'boss_arena'
```

| Message | Cause |
|---|---|
| `State not found: <id>` | no state with that id under that zone |

### `/cg state apply`

```mcfunction
/cg state apply <zoneId> <stateId>
/cg state apply <zoneId> <stateId> <animated>
/cg state apply <zoneId> <stateId> <animated> <direction>
/cg state apply <zoneId> <stateId> <animated> <direction> <speed>
/cg state apply <zoneId> <stateId> <animated> <direction> <speed> <particle>
/cg state apply <zoneId> <stateId> <animated> <direction> <speed> <particle> <blockbyblock>
```

Arguments are positional and each one defaults when omitted, so the six forms above are
the same handler with progressively more detail.

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `zoneId` | single word | yes | a zone id | — |
| `stateId` | single word | yes | a state id from `state list` | — |
| `animated` | boolean | no | `true`, `false` | `false` |
| `direction` | single word | no | one of the 14 directions below | `bottom_up` |
| `speed` | integer | no | 1–100, enforced by Brigadier | `2` |
| `particle` | single word | no | one of the 18 particles below | `cloud` |
| `blockbyblock` | boolean | no | `true`, `false` | `false` |

Directions: `bottom_up`, `top_down`, `slide_from_bottom`, `slide_from_top`,
`slide_from_north`, `slide_from_south`, `slide_from_east`, `slide_from_west`,
`cascade_from_bottom`, `cascade_from_top`, `cascade_from_north`, `cascade_from_south`,
`cascade_from_east`, `cascade_from_west`.

`bottom_up` and `top_down` reveal the state layer by layer in place. The `slide_*` forms
fly the blocks in from outside the zone along one axis. The `cascade_*` forms do the same
but activate the nearest blocks first, so the build appears to unfurl.

Particles: `none`, `cloud`, `poof`, `smoke`, `flame`, `soul`, `sparkle`, `enchant`,
`totem`, `heart`, `drip`, `cherry`, `sculk`, `witch`, `snowflake`, `note`, `electric`,
`ash`.

An unrecognised `direction` or `particle` is **not** an error: both are parsed by a
`fromString` that falls back to the default (`bottom_up`, `cloud`) on anything it does not
know. A typo silently gives you the default animation.

The world used for the rebuild is the **command source's** world *when that world is a
per-player copy of the zone's world*; in every other case it is the zone's own world. That
is what makes `state apply` work inside a per-player dimension: the same zone definition
can be applied in whichever copy of the world the caller is standing in.

```mcfunction
/cg state apply boss_arena 1
```

```text
State '1' applied to zone 'boss_arena'
```

```mcfunction
/cg state apply boss_arena 1 true cascade_from_bottom 4 sparkle true
```

```text
Animating state '1' on zone 'boss_arena'...
```

An animated apply returns immediately and runs over subsequent ticks, so the feedback
line means "started", not "finished".

| Message | Cause |
|---|---|
| `Error: <detail>` | the single error channel for this subcommand — `Animation already running on this zone`, `Zone not found`, `World not loaded`, `State file not found`, `Failed to load state: …`. The detail string comes from `ZoneStateManager.applyState` and is always English |

---

## `/cg buy`

```mcfunction
/cg buy <zoneId>
/cg buy <zoneId> confirm
```

The player-facing purchase command, deliberately ungated. Without `confirm` it prints the
price and a clickable prompt; with `confirm` it executes the sale.

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `zoneId` | single word | yes | a zone id with sale enabled |
| `confirm` | literal | no | the word `confirm` |

```mcfunction
/cg buy plot_14
```

```text
Zone Plot 14 is for sale for 2500 fromage.
 [Click to Buy]
```

The prompt shows the zone's display name and the price formatted by
`SaleManager.formatPrice` for the zone's currency (`2500 fromage`; a `NUMISMATIC` price of
2500 prints as `25 silver`).

```mcfunction
/cg buy plot_14 confirm
```

```text
You are now the owner of Plot 14!
```

| Message | Cause |
|---|---|
| `Zone not found.` | no zone with that id |
| `This zone is not for sale.` | the zone has no sale config, or it is disabled |
| `This zone has already been sold.` | the sale config is marked sold |
| `You have reached the ownership limit for tag: <tag>` | the per-tag ownership cap in `config/cheeseguard/tag_configs.json` is reached (checked first) |
| `You cannot afford this zone (<price>)` | the buyer cannot pay the price in the zone's currency |
| `Failed to deduct fromage payment` / `Failed to deduct items from inventory` / `Unknown currency: <c>` | the payment step failed |

A successful purchase makes the buyer the owner (the previous owner is demoted to member),
marks the sale sold, records the buyer, and saves `regions.json`. It also breaks the sale
sign the buyer clicked in the last 60 seconds, if any, and refreshes the sale hologram.
The tag config is only read, not written. The currency is the one set in the zone's sale
config (`NUMISMATIC`, `FROMAGE` or `ITEMS`) — see [zones and flags](zones-and-flags.md)
for the sale model and the sign format.

The failure strings from the confirm step (the last three rows) are returned from
`SaleManager.processPurchase` as hardcoded English strings and printed with
`Text.literal`, so they are **not** translated even though the language files contain
`cheeseguard.sale.cannotAfford` and `cheeseguard.sale.ownershipLimit` keys with different
wording. Every other message on this page goes through `Text.translatable` and respects
the client's language.

---

## `/chunksuggest`

```mcfunction
/chunksuggest
```

Registered as its own root command, not under `/cg`, and available to **everyone**. The
comment in the source gives the reason: nesting it under an operator-gated root would have
clashed with the `chunkstoclear` mod's own permission gate.

No arguments. It records your current position and dimension as a suggestion for the admin
to regenerate that map region.

```text
Suggestion sent to the administrator! (x:1184 y:72 z:-3012)
```

Before recording anything it checks whether your 512×512 map region is already covered by
a configured chunk set in the same dimension, and refuses if so:

```text
This region is already part of the set "wild_east" and will be regenerated by the administrator. No need to suggest!
```

| Message | Cause |
|---|---|
| `Suggestion system not available.` | `ChunkSuggestionStorage` is null — the mod did not finish initialising |

Suggestions land in `config/cheeseguard/chunk_suggestions.json` and are reviewed from the
dashboard.
The set model and the reset scheduler are in [chunks](chunks.md).

---

← [Getting started](getting-started.md) · [Events, prison and operations](commands-operations.md) →
