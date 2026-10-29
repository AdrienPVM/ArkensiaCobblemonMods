[← CobbleShift](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

CobbleShift exposes one command, `/cshift`, with thirteen subcommands. The whole tree is
gated by `requires(src -> src.hasPermissionLevel(2))` on the root literal, so **every**
subcommand needs permission level 2 — operator level on a vanilla server. A player below
that level does not see `/cshift` in tab completion and gets the vanilla *Unknown or
incomplete command* error if they type it anyway.

There is no player-facing command. Players are transformed by an admin, by a CheeseGuard
event effect, or by CheeseKart when they are put into a kart. The only player-side input
is the two client keybinds (sleep pose, cry), covered in
[the transform model](transforms.md#what-the-player-can-do-while-transformed).

The subcommands split into four families:

| Family | Subcommands |
|---|---|
| Player transforms | `transform`, `revert`, `revertall` |
| Passive abilities | `ability <player> [pokemon]`, `ability global enable\|disable`, `ability toggle <player>` |
| NPC transforms | `transformnpc`, `revertnpc`, `revertallnpc` |
| Tuning and inspection | `scale`, `speed`, `scalenpc`, `speednpc`, `list`, `species` |

---

## `/cshift transform`

```mcfunction
/cshift transform <player> <pokemon [shiny] [noattacks] [form…]>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `player` | player selector | yes | an **online** player; `EntityArgumentType.player()`, so selectors like `@p` work | — |
| `pokemon` | greedy string | yes | a species name, then any number of flag or aspect tokens | — |

The second argument is greedy: it swallows the rest of the line and the mod parses it
itself. The grammar is positional only in that the **species must come first**; the
tokens after it may appear in any order.

| Token | Effect |
|---|---|
| `shiny` | renders the shiny texture |
| `noattacks` | the player cannot fire type projectiles while transformed |
| anything else | treated as a Cobblemon aspect, `_` rewritten to `-` |

Aspect handling is worth reading before you use it. A plain token like `alolan` becomes
the aspect `alolan`. A token containing `=` is a species-feature pair: `magikarp_jump=black-mask`
becomes `magikarp-jump-black-mask`. Several tokens are joined with a **space**, because
Cobblemon's `PokemonProperties.parse()` separates aspects with spaces and reserves the
hyphen for the inside of a single aspect name. So `mega midnight` is two aspects, while
`mega-x` is one.

Tab completion is context-aware: on the first word it suggests every species name the
mod resolved from Cobblemon; afterwards it suggests `shiny`, `noattacks` and the six form
names hardcoded in `KNOWN_FORMS` — `alolan`, `galarian`, `hisuian`, `paldean`, `mega`,
`gmax`. That list is a convenience, not a validation list: any aspect string Cobblemon
accepts can be typed even though it is not suggested.

A `cobblemon:` namespace prefix on the species is stripped before lookup, so
`cobblemon:pikachu` and `pikachu` are the same request.

### What it does

1. Checks the transform cooldown, but only if the player is **not** already transformed.
   Switching species while transformed bypasses the cooldown by design.
2. Resolves the species through `SpeciesMapper`. If Cobblemon is absent or the name is
   unknown, the command fails here and nothing is applied.
3. Reverts any existing transform first, without the revert sound.
4. Applies permanent Invisibility, then the speed, max-health and scale attribute
   modifiers derived from the species base stats.
5. Broadcasts the model swap to every connected client, with the cry and particles only
   audible within 32 blocks in the same dimension.
6. Sends the player `✦ You are transformed into <name>!`.

The details of every applied modifier are in [the transform model](transforms.md).

### Examples

```mcfunction
/cshift transform Steve charizard
```

```text
✦ Steve transformed into charizard
```

Steve also sees `✦ You are transformed into charizard!`.

```mcfunction
/cshift transform Steve lycanroc shiny midnight noattacks
```

```text
✦ Steve transformed into lycanroc shiny midnight [no attacks]
```

The feedback line echoes the parsed result, which makes it a cheap way to check that your
token order produced the aspects you meant.

### Failures

| Message | Cause |
|---|---|
| `Failed: species '<name>' not found or Cobblemon not available` | `SpeciesMapper.resolve()` returned nothing. Either the species name is wrong, or Cobblemon is not installed, in which case **every** transform fails this way |
| `⛔ Cooldown: <n>s before transforming again.` | `transformCooldownEnabled` is on and the player reverted less than `transformCooldownSeconds` ago. Sent to the **target player** as an action-bar message. The caller gets the generic `Failed: species … not found` line above instead, because `transform()` only returns `false` |
| vanilla *No player was found* | the `player` argument matched nobody online |

The cooldown case is the one that confuses admins: from the caller's side it looks like a
species lookup failure, even for a species that exists.

---

## `/cshift revert`

```mcfunction
/cshift revert <player>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `player` | player selector | yes | an online player |

Removes the Invisibility, strips the three attribute modifiers, clamps health back down
if the player is now over their real maximum, broadcasts an empty-species sync so clients
drop the model, spawns the revert particles and plays `ENTITY_VILLAGER_CELEBRATE` at the
player's position. It also stamps the revert time, which is what the transform cooldown
measures from.

```mcfunction
/cshift revert Steve
```

```text
✦ Steve — transformation reverted
```

| Message | Cause |
|---|---|
| `<name> is not transformed` | no active transform for that player. Nothing happened |

---

## `/cshift revertall`

```mcfunction
/cshift revertall
```

No arguments. Reverts every active transform. A player whose entry exists but who is
offline is dropped from the map instead of reverted, since there is nothing to apply the
cleanup to; the leftover modifiers on that player are swept on their next join by
`cleanupStaleTransform`.

```text
✦ 4 transformation(s) reverted
```

The numeric command result is the count of transforms that were active **before** the
sweep, so `/execute store` can capture it.

The same `revertAll()` runs on `SERVER_STOPPING`, which is why a clean shutdown never
leaves a transformed player behind. A crash does — see
[the transform model](transforms.md#disconnects-restarts-and-crashes).

---

## `/cshift ability <player> [pokemon]`

```mcfunction
/cshift ability <player>
/cshift ability <player> <pokemon>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `player` | player selector | yes | an online player | — |
| `pokemon` | single word | no | a species name; tab-completes from every resolved species | — |

With a species, the player's passive ability is **forced** to that species' primary type
and stops following their Cobblemon party. Without it, the force is cleared and the
player goes back to automatic mode.

```mcfunction
/cshift ability Steve gengar
```

```text
✦ Ability forced for Steve → gengar
```

```mcfunction
/cshift ability Steve
```

```text
✦ Forced ability removed for Steve — back to auto mode
```

Neither form validates the species name: `forceAbility` resolves it on the spot and, when
the species is unknown, stores the forced entry with type `normal` instead of failing. A
typo therefore produces a forced entry on the `NORMAL` fallback, which grants no effect. Check with `/cshift list`, where forced
entries are marked `[FORCED]`.

⚠️ This subcommand shares its position with the `global` and `toggle` literals. Brigadier
tries literals before arguments, so a player actually named `global` or `toggle` cannot be
targeted by this form.

---

## `/cshift ability global enable|disable`

```mcfunction
/cshift ability global enable
/cshift ability global disable
```

No arguments beyond the literal. Writes `abilityGlobalEnabled` to `config/cobbleshift.json`
immediately — this is the one subcommand that writes the config file (the NPC subcommands
write the NPC save file). Passive abilities are **off**
by default, so this is the switch that turns the whole subsystem on.

Disabling also calls `deactivateAllAndSync()`, which empties the active-ability map and
pushes an empty HUD sync to every player, so no client is left showing an ability it no
longer has. It does not remove the status effects already granted: they stop being
refreshed and run out within `abilityEffectDuration` ticks.

```text
✦ Global abilities enabled
```

---

## `/cshift ability toggle <player>`

```mcfunction
/cshift ability toggle <player>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `player` | player selector | yes | an online player |

Flips one player's opt-out. The toggle is a membership test in an in-memory set of UUIDs,
so it is **not** persisted and resets on restart.

```text
✦ Abilities disabled for Steve
```

The state word in the message reflects the new state: `disabled` when the player was just
opted out, `enabled` when they were put back in.

---

## `/cshift list`

```mcfunction
/cshift list
```

Active transforms and active abilities, sent only to the caller.

```text
═══ CobbleShift Status ═══
Active transforms: 2
  • Steve → charizard (BST=534, 7min)
  • Alex → pikachu (BST=320, 0min)
Active abilities: 1
  • gengar (ghost) [FORCED]
Global abilities: ON
```

| Field | Meaning |
|---|---|
| `BST=` | the species base stat total, which drives projectile count |
| `…min` | whole minutes since the transform started |
| `(type)` | the primary type the ability resolved to |
| `[FORCED]` | set by `/cshift ability <player> <pokemon>` rather than read from the party |
| `Global abilities:` | the current value of `abilityGlobalEnabled` |

The ability lines show the species and type but not the player name — the record carries
the UUID, and the command does not resolve it. That is a gap, not a design choice.

---

## `/cshift species <name>`

```mcfunction
/cshift species <name>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `name` | single word | yes | a species name; tab-completes from every resolved species |

Prints the raw Cobblemon stats and every value CobbleShift derives from them. Use it
before a transform to see what a species will actually feel like.

```mcfunction
/cshift species charizard
```

```text
═══ charizard ═══
Types: fire / flying
Stats: HP=78 Atk=84 Def=78 SpAtk=109 SpDef=85 Spd=100
BST: 534
Mapped Speed: 0.125
Mapped Health: 15.2 HP
Attack: 6.4 dmg, 32 tick cd, 3 projectile(s)
Ability: Flamme Intérieure (fire)
```

The mapping formulas behind those numbers are in
[the transform model](transforms.md#how-base-stats-become-minecraft-values). This output
is built with `Text.literal` and is **not** translated: the labels stay English in both
languages, and the ability display name stays in French because the enum stores it that
way.

| Message | Cause |
|---|---|
| `Species '<name>' not found` | not in the resolved species list. With Cobblemon absent, every name fails |

---

## `/cshift transformnpc`

```mcfunction
/cshift transformnpc <pokemon [shiny] [form…]>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `pokemon` | greedy string | yes | same grammar as `/cshift transform` |

Targets **the entity you are looking at**, within 5 blocks, found by raycasting the
caller's eye line against entity bounding boxes expanded by 0.3. There is no entity
argument: you aim, then type. The caller must be a player, so this cannot run from a
command block or the console.

The target must be an EasyNPC. `noattacks` is parsed but has no meaning here — NPCs never
fire projectiles.

```mcfunction
/cshift transformnpc snorlax
```

```text
✦ NPC transformed into snorlax
```

| Message | Cause |
|---|---|
| `Look at an NPC to transform it` | the raycast found nothing within 5 blocks |
| `This entity is not an EasyNPC` | you hit an entity, but `NpcTransformManager.isEasyNpc()` rejected it |
| `Failed: species '<name>' not found` | species lookup failed |

NPC transforms are persisted, unlike player transforms. See
[NPC transforms](npc-transforms.md).

---

## `/cshift revertnpc`

```mcfunction
/cshift revertnpc
```

No arguments. Same 5-block raycast. Reverts the NPC you are looking at.

```text
✦ NPC transformation reverted
```

| Message | Cause |
|---|---|
| `Look at an NPC to revert its transformation` | nothing in the raycast |
| `This entity is not an EasyNPC` | wrong kind of entity |
| `This NPC is not transformed` | the NPC has no stored transform |

---

## `/cshift revertallnpc`

```mcfunction
/cshift revertallnpc
```

No arguments, no raycast. Reverts every stored NPC transform and returns the count.

```text
✦ 3 NPC transformation(s) reverted
```

---

## `/cshift scale` and `/cshift speed`

```mcfunction
/cshift scale <player> <value>
/cshift speed <player> <value>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `player` | player selector | yes | an online player, **already transformed** |
| `value` | float | yes | `0.1` to `10.0`, enforced by Brigadier |

`scale` multiplies the automatic species scale: the final `GENERIC_SCALE` modifier is
`mapScale(height) × value`, so `1.0` is the species' own size and `2.0` is twice that.
The value is also pushed to clients so the rendered model matches the hitbox.

`speed` replaces the species-derived movement speed with `0.1 × value` as a flat base.
Passing `0` is the documented reset path in the code and restores the species default,
but Brigadier's lower bound is `0.1`, so **the reset is unreachable from the command** —
only another mod calling `setPlayerSpeed(uuid, 0)` can trigger it. The dashboard's
`/speed` route does not help: it targets NPCs only and clamps to the same `[0.1, 10]`.
Reverting and re-transforming is the practical workaround.

```mcfunction
/cshift scale Steve 1.5
```

```text
✦ Scale of Steve → 1.5
```

```mcfunction
/cshift speed Steve 2.0
```

```text
✦ Speed of Steve → 2.0x
```

| Message | Cause |
|---|---|
| `<name> is not transformed` | both subcommands require an active transform. `speed` reuses the `scale` message key, so the wording says nothing about speed |

Neither value survives a revert: both live in the `TransformData` record, which is
discarded.

---

## `/cshift scalenpc` and `/cshift speednpc`

```mcfunction
/cshift scalenpc <value>
/cshift speednpc <value>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `value` | float | yes | `0.1` to `10.0` |

The NPC equivalents, aimed by the same 5-block raycast. `speednpc` multiplies the NPC's
movement speed attribute.

```text
✦ NPC scale → 1.5
✦ NPC speed → 2.0x
```

| Message | Cause |
|---|---|
| `Look at an NPC to change its scale` / `…its speed` | nothing in the raycast |
| `This NPC is not transformed` | the target has no stored transform. Unlike `transformnpc`, these two check only that, not that the entity is an EasyNPC |

---

## Driving the mod from somewhere else

Every transform above is reachable without a command:

- **Dashboard** — the `shift` module, or the REST endpoints directly. See
  [the dashboard API](dashboard.md).
- **CheeseGuard events** — the `TRANSFORM_ALL`, `TRANSFORM_RANDOM`, `TRANSFORM_BY_TEAM`
  and `TRANSFORM_REVERT_ALL` effect types transform every participant of a timed event at once.
- **CheeseKart** — transforms a racer when they are put into a kart, and reverts them when
  the session ends.

Both of those reach in through `getTransformManager().transform(...)` and `revert(...)` by
reflection. [Architecture](architecture.md#the-de-facto-external-api) explains why that
makes those two method signatures load-bearing.

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
