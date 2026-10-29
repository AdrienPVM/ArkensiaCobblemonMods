[← CheeseItems](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

CheeseItems registers two command trees. `/cheeseitem` is the admin tree and requires
**permission level 2** — the level a vanilla server grants to an operator. `/morpho` has
no permission requirement at all: any player can run it.

A player below level 2 does not see `/cheeseitem` in tab completion and gets the vanilla
*Unknown or incomplete command* error if they type it anyway. There is no per-subcommand
permission check: the `requires(...)` call sits on the root literal, so the whole tree is
gated at once.

---

## `/cheeseitem give`

```mcfunction
/cheeseitem give <player> <item_id> [quantity]
```

**The player comes first, the item id second.**

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `player` | single word | yes | an **online** player's name; tab-completes from the server's player list | — |
| `item_id` | single word | yes | an id from `config/cheeseitems.json`; tab-completes from the loaded definitions | — |
| `quantity` | integer | no | `1`–`64`, enforced by Brigadier | `1` |

`quantity` is bounded by the argument type itself, so `0` or `65` is rejected by the
command parser before the mod sees it — you get Brigadier's own range error, not one of
the mod's messages.

Both suggestion providers read live state. An item created from the dashboard a second
ago tab-completes immediately, because the provider iterates the in-memory definition
list rather than re-reading the file.

### What it does

1. Looks the definition up by id. A missing definition aborts the command **before** the
   player name is checked, so a typo in the id hides a typo in the name.
2. Looks the player up in the player manager. Offline players cannot be targeted.
3. Builds a fresh `ItemStack` from the definition: base item, `cheeseitems` identity tag
   in `CUSTOM_DATA`, `usesLeft` when the definition has a `maxUses` above zero, then the
   display name, lore, stack size, glint, unbreakable flag, dye colour and custom model
   data. All of it is described in [the item format](items.md).
4. Inserts the stack into the player's inventory. **If the inventory is full the stack is
   dropped on the ground at the player's feet** rather than discarded.
5. Reports success to the caller and to the server log (the feedback is broadcast to
   other operators, because `sendFeedback` is called with `true`).

A disabled definition (`"enabled": false`) is still given out. The flag gates use-time
behaviour, not handing the item over — see [the item format](items.md#enabled).

### Example

```mcfunction
/cheeseitem give Steve quest_key 3
```

```text
Gave 3x Rusty Quest Key to Steve
```

In French (`fr_fr.json`): `Donné 3x Rusty Quest Key à Steve`. The display name itself is
never translated: it comes from the definition.

### Failures

| Message | Cause |
|---|---|
| `Item not found: <id>` | no definition with that id. Check `/cheeseitem list` |
| `Player not found: <name>` | the name does not match an **online** player |

Both are rejections: nothing is given, nothing is written to disk.

⚠️ If the definition's `baseItem` is not a registered item id, the stack is built from
whatever `Registries.ITEM.get(...)` returns for an unknown identifier, which is
`minecraft:air`. The command still reports success and the player receives nothing
visible. This is the single most common authoring mistake — the mod does not validate
`baseItem`.

---

## `/cheeseitem list`

```mcfunction
/cheeseitem list
```

No arguments. Prints every loaded definition to the caller only (`sendFeedback` with
`false`), one line each.

```text
--- CheeseItems (3) ---
 [ON] quest_key — Rusty Quest Key (minecraft:tripwire_hook)
 [ON] morpho_ditto — Ditto Morpho (minecraft:slime_ball)
 [OFF] old_token — Legacy Token (minecraft:paper)
```

| Field | Meaning |
|---|---|
| `[ON]` / `[OFF]` | the definition's `enabled` flag, green or red |
| id | the `item_id` you pass to `give` |
| after the dash | `displayName`, uncoloured |
| in parentheses | `baseItem` |

The `[ON]`/`[OFF]` marker and the parenthesised base item are built with
`Text.literal`, so they stay in English regardless of the client's language. Only the
header line is translatable.

With nothing defined you get `No items defined.` and the command returns `0`. Otherwise
the numeric result is the number of definitions, which makes it usable in an
`/execute store`.

---

## `/cheeseitem reload`

```mcfunction
/cheeseitem reload
```

No arguments. Re-reads `config/cheeseitems.json` from disk and then walks every online
player's inventory to bring already-distributed items back in line with the new
definitions.

```text
CheeseItems config reloaded.
```

What the inventory pass actually does, and what it cannot do, is the part worth reading
before you rely on it: [hot reload and existing items](items.md#hot-reload-and-items-already-in-inventories).
The short version — visual properties and the base item are repaired in place, but a
definition you **delete** leaves every copy already in a player's inventory behind as an
inert stack that no longer matches anything.

Reload is also what you run after editing the JSON by hand. Dashboard writes call the
same code path themselves, so you do not need to reload after a dashboard edit.

---

## `/cheeseitem morpho grant`

```mcfunction
/cheeseitem morpho grant <player> <morpho_id>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `player` | single word | yes | an online player's name; tab-completes from the player list | — |
| `morpho_id` | single word | yes | an entry id from the Morpho catalog; tab-completes from the loaded catalog | — |

Adds a catalog entry to a player's Morpho Codex without them having to use an item. The
entry is recorded with the catalog's species, form and shiny flag plus the current
timestamp, the player's codex is pushed to their client, and both the caller and the
player are told.

```mcfunction
/cheeseitem morpho grant Steve ditto
```

```text
Morpho Ditto granted to Steve.
```

and the player sees `🧬 Ditto added to your Morpho Codex!`.

| Message | Cause |
|---|---|
| `Player not found: <name>` | not online. Checked first |
| `Morpho not found in catalog: <id>` | no such entry in `config/cheeseitems_morphos.json` |
| `<name> already owns this morpho.` | the codex already holds that id. Nothing is changed |

The whole subsystem is described in [the Morpho system](morphos.md).

---

## `/morpho`

```mcfunction
/morpho
```

No arguments, **no permission level**, and it only works for a player — a console or
command-block source gets `This command can only be used by a player.`

The command sends a single S2C packet that tells the caller's own client to open the
Morpho Codex screen. Nothing happens server-side beyond that packet, so running it for a
player whose client does not have CheeseItems installed does nothing at all: the payload
has no handler there and is dropped.

If sending throws, the player sees `Failed to open Morpho Codex: <message>` in red and
the command returns `0`. That message and the console-source one are both
`Text.literal`, so they are English-only even on a French client.

The same screen opens with the `J` key, and the active morpho toggles with `H`, both
rebindable in the client's controls under the `CheeseItems` category. The command exists
for players who cleared their keybinds and for NPC dialogue that runs a command.

---

## Driving the mod from somewhere else

Nothing above is the only way in:

- **Dashboard** — the `items` module for definition CRUD and `give`, the
  `morphos` module for the catalog. See [the dashboard API](dashboard.md).
- **Command actions** — a definition's `useActions` run arbitrary server commands when
  the item is used, which is how an item ends up granting a morpho, teleporting a player
  or starting a quest without any extra Java. See
  [useActions](items.md#useactions).

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
