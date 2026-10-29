[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](display.fr.md)

# Decorative eggs and pedestals

A discovery is only worth something if the player can show it. Half of this mod is the
hunt; the other half is furniture.

## The decorative egg

`eggandcheese:collectible_egg` is what a player receives when they find an egg. It is a
`BlockItem` with `maxCount(1)` — every copy is unique because every copy carries its own
DNA — and `CollectibleItem.create(type, dna)` is what builds one.

Its `CUSTOM_DATA` holds the `dna` compound, and, when the item was handed out by an admin
right-click or by `POST /collectibles/:id/give-deco`, a `registryId` string. The reward a
player gets from a discovery carries no id. The id is only used for the tooltip; nothing about the
decorative item is bound to the registry.

### Tooltip

`CollectibleItem.appendTooltip` adds up to three lines:

```text
Pattern: Cristaux
Palette: Glace
ID: cave_nord
```

In French:

```text
Motif : Cristaux
Palette : Glace
ID : cave_nord
```

The pattern and palette values come from the enums' `getDisplayName()`, which are
**French strings in the Java source** regardless of the client's language — so an English
client reads `Pattern: Cristaux`. The ID line is dark grey and only appears when a
`registryId` is present.

### Placing it

Right-clicking a block face places an `eggandcheese:egg_deco` block. `postPlacement`
copies the DNA from the item onto the new `CollectibleDecoBlockEntity`, so the placed egg
looks exactly like the item.

The block:

- has `noCollision()` and `nonOpaque()` — players walk through it,
- carries only a `FACING` property, set to the opposite of the placing player's facing,
- has `strength(0.5f)`, so it breaks about as fast as dirt or sand,
- is **not** waterloggable, unlike the admin block,
- renders as `ENTITYBLOCK_ANIMATED` with a client-only ticker, same as the admin egg.

Breaking it gives the egg back. `onBreak` rebuilds the item from the block entity's DNA
and drops it on the ground with `dropStack`, so a decorative egg survives being moved
around a build. It is dropped as a world item rather than handed to the player,
which is the opposite of what the admin block does.

### In other containers

`CollectibleItem` extends `BlockItem`, and that caused a real bug worth knowing about: a
right-click on *any* block tries to place the deco block, which made it impossible to put
an egg into Cobblemon's display case. The class overrides the item-use path to return
`PASS` for that case, letting the target block's own handler run and insert the item
normally. The comment in the source names `display_case` explicitly.

This is an observation about third-party behaviour, not a specification: it was written
against the Cobblemon of the day, and a future Cobblemon version could interact
differently.

## Pedestals

A pedestal is a block that displays one item, scaled, lifted, and optionally spinning, on
top of an adjustable pillar. They are ordinary craftable blocks, not admin tools.

### The 23 variants

Each variant is its own block and its own item, all sharing one
`eggandcheese:pedestal` block entity type:

`pedestal_stone`, `pedestal_oak`, `pedestal_quartz`, `pedestal_iron`, `pedestal_gold`,
`pedestal_diamond`, `pedestal_netherite`, `pedestal_copper`, `pedestal_amethyst`,
`pedestal_emerald`, `pedestal_dark_oak`, `pedestal_cherry`, `pedestal_birch`,
`pedestal_deepslate`, `pedestal_blackstone`, `pedestal_prismarine`, `pedestal_spruce`,
`pedestal_jungle`, `pedestal_acacia`, `pedestal_mangrove`, `pedestal_bamboo`,
`pedestal_crimson`, `pedestal_warped`.

Each one's block settings are copied from the material it is named after —
`Settings.copy(Blocks.STONE_BRICKS)` for the stone pedestal, `Blocks.GOLD_BLOCK` for the
gold one — so hardness, tool requirement and sound all match that material. The only
change is `nonOpaque()`.

### Crafting

All 23 share one shaped recipe: a column of two, on a base of three, of the obvious
material.

```text
 X
 X
XXX
```

| Pedestal | `X` |
|---|---|
| `pedestal_stone` | `minecraft:stone_bricks` |
| `pedestal_gold` | `minecraft:gold_ingot` |
| … | the matching planks, bricks, block, ingot, gem or shard for each variant |

Output is one pedestal. The recipes are plain datapack JSON in
`data/eggandcheese/recipe/`, one file per variant, so a pack can override or remove any of
them without touching the mod.

### Using one

| Action | Result |
|---|---|
| Right-click with an item, pedestal empty | One item is taken from your stack and displayed |
| Right-click empty-handed, pedestal occupied | The displayed item is returned to you |
| Right-click with an item, pedestal occupied | The displayed item is returned to you — the empty-hand action runs |
| Right-click with an **admin egg**, pedestal occupied | The displayed item is returned to you |
| Right-click with an **admin egg**, pedestal empty | Not displayed — the item's own use runs, so the admin egg is **placed** against the face you clicked, as on any block |

`onUseWithItem` only requires a non-empty stack, so a pedestal will display anything: a
collectible egg, a diamond, a cake. The one explicit exclusion is `AdminCollectibleItem`:
handing the admin placement item to a pedestal returns
`PASS_TO_DEFAULT_BLOCK_INTERACTION` instead of consuming it, so the placement item is never
swallowed by the furniture. In 1.21.1 that result hands the click on, first to the
pedestal's empty-hand action, then to the item itself — which is why an item aimed at an
occupied pedestal takes the displayed one back, and why an admin egg aimed at an empty one
is placed next to it as a real hunt egg.

The item is taken with `stack.copyWithCount(1)` and the source stack decremented by one,
so a stack of 64 loses exactly one.

Retrieval inserts into your inventory, dropping at your feet when full.

### Display properties

Four values live on `PedestalBlockEntity`, all adjustable with
[`/pedestal`](commands.md#pedestal):

| Property | Default | Range via command | Effect |
|---|---|---|---|
| `displayScale` | `1.0` | 0.5 – 3.0 | Size of the displayed item |
| `displayHeight` | `0.0` | −0.5 – 0.5 | Vertical offset, in blocks |
| `pillarHeight` | `1.0` | 0.0 – 5.0 | Height of the pillar under the platform |
| `spinning` | `true` | toggle | Whether the item rotates |

The renderer also bobs the item gently; `bobOffset` and `bobTick` are client-side
animation state, not configuration.

`getDisplayDNA()` reads the DNA out of whatever is on the pedestal, which is how an egg
on a pedestal gets its procedural texture rather than a flat icon.

### Pillars and the extension block

`pillarHeight` is not cosmetic. `setPillarHeight` rebuilds a cached array of `VoxelShape`s
and calls `updateExtensions()`, which places `eggandcheese:pedestal_extension` blocks
above the pedestal to carry the collision of the raised platform. `removeAllExtensions()`
cleans them up on the normal removal path.

`PedestalExtensionBlock` is registered with `strength(-1.0f, 3600000.0f)` — the same
settings as bedrock. It cannot be mined and cannot be blown up. That is deliberate: an
extension is structural, and letting a player break the middle of a pillar would leave
the platform floating with no collision.

⚠️ If a pedestal disappears by a route that skips block updates — a WorldEdit operation, a
chunk rollback — `removeAllExtensions` never runs (a `/setblock` or an explosion does go
through `onStateReplaced` and cleans up). The extension blocks stay, and they are
unbreakable. Remove them with `/setblock <x> <y> <z> air`. There is no in-game way to
clear them otherwise.

The shape cache is keyed on the current `pillarHeight` (`cachedShapesPh`), rebuilt only
when the height actually changes, so collision lookups on an unchanged pedestal cost
nothing.

## The advancement

`data/eggandcheese/advancements/egg_found.json` ships a single advancement with one
criterion:

```json
{
  "criteria": {
    "find_egg": {
      "trigger": "eggandcheese:egg_found",
      "conditions": { "egg_id": "*" }
    }
  },
  "rewards": {}
}
```

⚠️ **Minecraft 1.21.1 never loads that file.** Since 1.21 the datapack folder is
`advancement/`, singular — the mod's own recipes do sit in `recipe/` — so a file under
`advancements/` is ignored, and no advancement with the id `eggandcheese:egg_found`
exists in game. What does work is the **criterion trigger** of the same name: any
advancement that names it fires.

`"*"` is handled by `EggFoundCriterion.Conditions.matches`, which accepts an absent
`egg_id`, the literal `*`, or an exact id. To react to **any** discovery, copy the JSON
above into your own datapack, for example as `data/mypack/advancement/egg_found_any.json`.
Without a display block it stays invisible in the advancement screen and serves purely as
a hook — FTB Quests can watch `mypack:egg_found_any`, and a datapack can depend on it.

To build a task tied to one specific egg, put the id in the conditions:

```json
{
  "criteria": {
    "find_it": {
      "trigger": "eggandcheese:egg_found",
      "conditions": { "egg_id": "cave_nord" }
    }
  }
}
```

The criterion is registered in `EggAndCheese.onInitialize` under
`Registries.CRITERION`, so the trigger id `eggandcheese:egg_found` is available to any
datapack without extra setup.

---

← [Placement and discovery](placement-and-discovery.md) · [FTB Quests integration](ftb-quests.md) →
