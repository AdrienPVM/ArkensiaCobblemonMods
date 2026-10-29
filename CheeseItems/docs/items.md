[← CheeseItems](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](items.fr.md)

# Custom items

A custom item is not a registered item. It is a vanilla `ItemStack` whose `CUSTOM_DATA`
component carries one string: `cheeseitems: "<id>"`. Everything else — the name, the
lore, the colour, the effects, the commands, the conditions — is looked up from
`config/cheeseitems.json` at the moment the item is used, or re-applied to the stack when
the definition changes.

That design decides most of the mod's behaviour. The item registry is never touched, so
definitions can appear and disappear while the server runs. A stack found in a chest six
months from now will behave according to whatever the definition says *then*, not what it
said when the stack was created.

## The definition object

One entry in the `items` array of `config/cheeseitems.json`. Every field is optional: a
field the loader does not find keeps the constructor's default, which is what the table
below lists. There is **no validation** beyond those defaults — an unknown `baseItem`, a
malformed `nameColor` or a negative `maxUses` are all accepted and produce the behaviour
described in the prose.

### Identity

| Key | Type | Default | What it does |
|---|---|---|---|
| `id` | string | a random 8-character UUID fragment | the id used by `give`, the API and the `cheeseitems` NBT tag |
| `displayName` | string | `"Custom Item"` | the stack's `CUSTOM_NAME`, italics removed |
| `nameColor` | string | `"#FFFFFF"` | hex colour applied to the display name |
| `baseItem` | string | `"minecraft:paper"` | the vanilla item the stack is made of |

`id` is the only field that matters to a stack already in the world, because it is the
only one stored on the stack. Renaming an id orphans every existing copy. There is no
rename endpoint for that reason — create a new definition and give it out again.

`nameColor` and `loreColor` go through `parseHexColor`, which strips a leading `#` and
parses the rest as hexadecimal. **Anything it cannot parse silently becomes white**
(`0xFFFFFF`). A three-digit shorthand like `#f5a` parses as the number `0xF5A`, a dark
blue, not as the colour you meant.

### <a id="enabled"></a>Appearance and flags

| Key | Type | Default | What it does |
|---|---|---|---|
| `lore` | array of strings | `[]` | one tooltip line per entry, italics removed |
| `loreColor` | string | `"#AAAAAA"` | hex colour applied to every lore line |
| `glowing` | boolean | `false` | sets `ENCHANTMENT_GLINT_OVERRIDE`; `false` removes it |
| `dyeColor` | integer | `-1` | packed RGB for `DYED_COLOR`; `-1` removes the component |
| `enabled` | boolean | `true` | `false` makes right-click do nothing at all |
| `maxStackSize` | integer | `64` | clamped to 1–64 before being written to `MAX_STACK_SIZE` |
| `unbreakable` | boolean | `false` | sets the `UNBREAKABLE` component |
| `consumable` | boolean | `false` | removes one from the stack after a successful use |
| `soulbound` | boolean | `false` | ⚠️ see [flags that do nothing](#flags-that-do-nothing) |
| `disappearOnDeath` | boolean | `false` | the stack is wiped from the inventory before death |
| `droppable` | boolean | `true` | ⚠️ not enforced — see below |
| `tradeable` | boolean | `true` | ⚠️ never read by any code |
| `placeable` | boolean | `false` | ⚠️ never read by any code |

`enabled: false` does not stop `give` from handing the item out, and it does not stop
passive effects from being skipped either — the passive pass checks the flag too, so a
disabled item is inert in the hand but still a normal vanilla item. It still stacks, still
drops, still takes an inventory slot. Treat it as "retired", not "removed".

`dyeColor` only shows on items that read the component: leather armour, wolf armour,
shulker boxes. On a paper it changes nothing.

### Uses and cooldowns

| Key | Type | Default | What it does |
|---|---|---|---|
| `maxUses` | integer | `0` | `0` is infinite. Above zero, the stack is stamped with `usesLeft` and the last use destroys the whole stack |
| `cooldownTicks` | integer | `0` | per-player cooldown between uses of this definition, in ticks |

`maxUses` is written onto the stack at build time, so changing it later does not change
the counter on a stack someone already holds — the counter only resets when the base item
changes and the stack is rebuilt. When the counter reaches zero the handler calls
`held.decrement(held.getCount())`, which empties **the entire stack**, not one item. Give
`maxUses` items one at a time, or expect a stack of five to vanish on the fifth use of the
first one.

A cooldown that is still running sends `Cooldown: 12.4s` to the actionbar and the use is
refused. Cooldown bookkeeping lives in memory only: a restart clears every cooldown. Old
entries are pruned once a minute, and anything older than 72 000 ticks (one hour) is
dropped.

### Effects

Three arrays of the same shape:

| Key | When it applies |
|---|---|
| `onUseEffects` | on a successful right-click |
| `passiveEffects` | re-applied every 40 ticks while the item is in either hand |
| `onPickupEffects` | ⚠️ never applied — see [flags that do nothing](#flags-that-do-nothing) |

Each entry:

| Field | Type | Default | What it does |
|---|---|---|---|
| `effectId` | string | `"minecraft:speed"` | status effect identifier; an unknown id is skipped silently |
| `amplifier` | integer | `0` | `0` is level I |
| `durationTicks` | integer | `600` | 20 ticks per second |

Effects are added with `ambient = false`, `showParticles = true`, `showIcon = true`. For a
passive effect, pick a duration longer than the 40-tick refresh — 100 ticks is a good
floor, because a duration under 40 leaves gaps the player will feel.

`GET /api/v1/items/enums` returns the 37 effect ids the dashboard editor offers. The mod
does not restrict you to that list; anything in the `STATUS_EFFECT` registry works,
including effects added by other mods.

### <a id="useactions"></a>useActions

The reason the mod exists. Each entry runs a server command when the item is used.

| Field | Type | Default | What it does |
|---|---|---|---|
| `command` | string | `""` | the command to run, without a leading slash |
| `count` | integer | `1` | how many times to run it; values below 1 are treated as 1 |
| `feedback` | string | `""` | actionbar message to the user, green. Empty means no message |
| `delayCommand` | string | `""` | a second command, run later |
| `delaySeconds` | integer | `0` | the delay. **Both this and `delayCommand` must be set, or nothing is scheduled** |
| `showTimer` | boolean | `false` | shows a countdown in the actionbar until the delayed command fires |
| `timerLabel` | string | `""` | label for that countdown; empty falls back to `Timer` |

`{player}` is replaced with the user's name in `command`, `delayCommand` and `feedback`.
That is the only placeholder.

⚠️ **Commands run as the server console**, through
`server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd)`. That is
permission level 4. A right-click by any player can therefore run `op`, `ban`, `stop` or
anything else — the item definition is the security boundary, and the dashboard routes
that write definitions [check no role](dashboard.md#authentication-and-role-guards).

The delayed command runs from a list the mod ticks itself. It is in-memory: a restart
between the use and the delay drops the follow-up silently. The countdown is pushed once
a second and formats as `3m 20s` above a minute, `45s` below.

A worked example — a teleport scroll that warns, then moves the player:

```json
{
  "command": "effect give {player} minecraft:blindness 60 1",
  "count": 1,
  "feedback": "Channelling…",
  "delayCommand": "tp {player} 120 70 -340",
  "delaySeconds": 3,
  "showTimer": true,
  "timerLabel": "Teleport"
}
```

### useConditions

Checked before anything else happens. A failed check sends `Conditions not met.` to the
actionbar and the use is refused — the player is not told *which* condition failed, which
is deliberate for quest items and annoying for debugging.

| Key | Type | Default | What it does |
|---|---|---|---|
| `dimension` | string or absent | absent | the use only works in this dimension, matched against the full registry key (`minecraft:the_nether`) |
| `minLevel` | integer | `0` | minimum **experience level**, not a Pokémon level. `0` disables the check |
| `requiredHeldItem` | string or absent | absent | item id that must be in the **off hand** |
| `permission` | string or absent | absent | a permission node, checked through LuckPerms |
| `globalCooldownTicks` | integer | `0` | one cooldown shared by every player for this definition |
| `zoneId` | string or absent | absent | ⚠️ stored, served, never checked |

The `permission` check works like this: operators at level 2 or above pass immediately
without the node being consulted. Everyone else goes through a reflective LuckPerms
lookup, and **any failure of that reflection — including LuckPerms not being
installed — is treated as "permission denied"**. So on a server without LuckPerms, a
`permission` condition makes the item usable by operators only. That is a reasonable
default but it is not what the field name suggests.

`globalCooldownTicks` and `cooldownTicks` are both consulted, and the message shows
whichever has more time left.

### endurance

A per-item drain pool, separate from the morpho endurance pool described in
[morphos](morphos.md). Omit the key, or set it to `null`, for no pool.

| Key | Type | Default | What it does |
|---|---|---|---|
| `tag` | string | `""` | pool identifier. **An empty tag disables the whole block** |
| `displayName` | string | `"Endurance"` | kept in the pool state; not currently rendered anywhere |
| `capacity` | integer | `100` | full value of the pool |
| `drainPerSecond` | float | `2.0` | points removed per second while active |
| `regenPerSecond` | float | `1.0` | points restored per second while inactive |
| `onDepletedCommands` | array of actions | `[]` | `useActions`-shaped entries, run when the pool empties or is switched off |

With a pool configured, right-clicking toggles it. First press starts the drain and runs
the normal use path. A second press while draining stops it, runs
`onDepletedCommands`, applies the cooldown and **skips** `useActions` and `onUseEffects`
entirely. A press while the pool is at zero is refused with `Endurance depleted!`.

The pool is drawn in the actionbar four times a second, as ten `◆`/`◇` characters coloured
green above 50%, yellow above 25%, red below — or as a single glyph from the generated
font if `config/cheeseitems_assets/bar/0.png` … `10.png` all exist. See
[the resource pack](resource-pack.md#the-endurance-bar-font).

Players sharing a `tag` do not share a pool: the map is keyed by tag *and* player UUID.
The tag groups items, so two items with `tag: "stamina"` drain the same personal pool.
Pools live in memory and are dropped on disconnect, after running
`onDepletedCommands` if a drain was active.

## A complete annotated example

```json
{
  "items": [
    {
      "id": "lantern_of_arkensia",
      "displayName": "Lantern of Arkensia",
      "nameColor": "#F5A623",
      "baseItem": "minecraft:soul_lantern",

      "lore": [
        "A quest item. It remembers the way.",
        "Right-click near the shrine."
      ],
      "loreColor": "#9CA3AF",
      "glowing": true,
      "dyeColor": -1,

      "enabled": true,
      "maxStackSize": 1,
      "unbreakable": true,
      "consumable": false,
      "soulbound": false,
      "disappearOnDeath": false,
      "droppable": true,
      "tradeable": true,
      "placeable": false,

      "maxUses": 3,
      "cooldownTicks": 200,

      "onUseEffects": [
        { "effectId": "minecraft:night_vision", "amplifier": 0, "durationTicks": 1200 }
      ],
      "passiveEffects": [
        { "effectId": "minecraft:glowing", "amplifier": 0, "durationTicks": 100 }
      ],
      "onPickupEffects": [],

      "useActions": [
        {
          "command": "playsound minecraft:block.beacon.activate master {player}",
          "count": 1,
          "feedback": "The lantern answers.",
          "delayCommand": "tp {player} 148 64 -512",
          "delaySeconds": 5,
          "showTimer": true,
          "timerLabel": "Shrine"
        }
      ],

      "useConditions": {
        "dimension": "minecraft:overworld",
        "minLevel": 5,
        "requiredHeldItem": "minecraft:amethyst_shard",
        "globalCooldownTicks": 0
      }
    }
  ],
  "customModelData": {
    "lantern_of_arkensia": 1
  },
  "resourcePackHost": "play.example.net:44311"
}
```

Reading that definition in play: the item stacks to one, glows, and cannot be used outside
the Overworld, below experience level 5, or without an amethyst shard in the off hand. A
valid use plays a sound, prints *The lantern answers.*, starts a five-second `Shrine`
countdown, grants night vision for a minute and burns one of three charges. Holding it
keeps the player outlined. The third use destroys it.

`customModelData` and `resourcePackHost` are not part of the item — they sit next to the
array and are covered in [configuration](configuration.md).

## <a id="hot-reload-and-items-already-in-inventories"></a>Hot reload and items already in inventories

This is the question that matters on a live server, so here is exactly what happens.

`refreshAllPlayers()` runs after `/cheeseitem reload`, after a `PUT` on an item, after an
icon upload and after an icon delete. It walks every slot of every **online** player's
inventory. For each stack carrying a `cheeseitems` tag whose id still resolves to a
definition:

- if `baseItem` still matches the stack's item, the visual components are re-applied in
  place: name, colour, lore, max stack size, glint, unbreakable, dye, custom model data;
- if `baseItem` has changed, the stack is rebuilt from scratch at the same count, and the
  `usesLeft` counter is copied across.

Then `player.currentScreenHandler.sendContentUpdates()` pushes the changes to the client.

**What is not refreshed:**

| Case | What happens |
|---|---|
| The player is offline | nothing. The stack is updated the next time a refresh runs while they are online |
| The stack is in a chest, ender chest, shulker box, item frame, hopper or on the ground | never touched. Only player inventories are walked |
| The definition was **deleted** | the stack keeps its tag, its old name and its old lore forever. It no longer resolves, so right-clicking it does nothing beyond the base item's own behaviour, and no pass will ever clean it up |
| The definition's `id` was changed | identical to a delete: the old stacks are orphaned |
| `maxUses` was raised or lowered | existing stacks keep their stamped `usesLeft`. Only a `baseItem` change rebuilds the counter |
| `useActions`, `useConditions`, effects, flags | not stored on the stack at all, so they take effect immediately — nothing to refresh |

An orphaned stack is a cosmetic problem, not a dangerous one: its commands are gone with
its definition. Recovering from an accidental delete means recreating the definition under
exactly the same `id` — the old stacks then start working again, because the only thing
binding them is that string.

The reload itself also *replaces* the in-memory list, which means an item created from the
dashboard but not yet saved to disk cannot exist: `createItem`, `updateItem` and
`deleteItem` all call `config.save()` before returning. Reloading never loses a dashboard
edit.

## <a id="flags-that-do-nothing"></a>Flags that do nothing

Honesty section. These fields are accepted by the loader, round-tripped through the API
and shown in the dashboard editor, and change nothing in play.

| Field | Why |
|---|---|
| `droppable` | `UseHandler.shouldBlockDrop` exists and sends `This item cannot be dropped.`, but **no event handler calls it**. Any item can be dropped |
| `onPickupEffects` | `UseHandler.onItemPickup` exists and is never called. Picking up an item applies nothing |
| `tradeable` | no code reads it |
| `placeable` | no code reads it. A `baseItem` that is a block can always be placed |
| `useConditions.zoneId` | no code reads it. `checkConditions` never consults CheeseGuard |
| `endurance.displayName` | stored in the pool state, never rendered |

⚠️ **`soulbound` is worse than inert: it deletes the item.** On death, every soulbound
stack is copied into an in-memory stash and **removed from the inventory**. The method
that gives them back, `onPlayerRespawn`, is never called by anything — the
`AFTER_DEATH` handler in `CheeseItemsMod` is an empty block with a comment saying the
restore should happen elsewhere. The stash is then dropped when the server restarts.

Until that is fixed, do not set `soulbound: true`. Use `disappearOnDeath: false` (the
default) and let the item drop with the rest of the player's inventory, or keep the item
out of combat areas.

---

← [Configuration](configuration.md) · [Resource pack](resource-pack.md) →
