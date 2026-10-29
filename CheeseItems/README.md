# CheeseItems

> 🇫🇷 [Lire en français](README.fr.md)

> Quest items authored from a web dashboard, plus the Morpho transformation catalog.

An item in CheeseItems is a vanilla item carrying a `cheeseitems` tag in its
`CUSTOM_DATA` component. That tag is the whole trick: the mod never registers anything
into the item registry, so a definition can be created, renamed, recoloured, given new
status effects and a new icon while the server runs, and every copy already in a
player's inventory updates on the next tick. A quest chain is built by typing in the
dashboard, not by restarting.

Right-clicking a custom item runs the commands you attached to it, applies the status
effects you listed, consumes a use, starts a countdown in the actionbar — whatever the
definition says. Conditions gate the use by dimension, experience level, off-hand item
or permission node. Uploaded PNG icons are compiled into a server resource pack that is
pushed to clients automatically.

The second half of the mod shares only the codebase: the **Morpho catalog**, a list of
Pokémon forms a player can unlock and then wear. A morpho is stored per player in a
Codex, picked in an in-game GUI (`J`), toggled with `H`, and applied by driving
[CobbleShift](../CobbleShift/)'s `cshift` command. Each transformation drains an
endurance pool and reverts on its own when the pool empties.

**Minecraft 1.21.1 · Fabric · Java 21 · 28 source files, ~5 000 lines**

---

## Requirements

| | |
|---|---|
| **Required** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Needed for morphos** | [CobbleShift](../CobbleShift/) — the transformations are `cshift` commands |
| **Optional** | [CheeseStore](../CheeseStore/) — shows a currency balance in the Codex GUI |

`cheesedash` is a hard `depends`, so the server refuses to start without it. CobbleShift
is not declared at all: the morpho system calls `cshift transform` through the command
dispatcher, so without CobbleShift the command fails and only a warning reaches the log.

`"environment": "*"` — the jar loads on both sides. The client half adds the Codex GUI
and the two keybinds. A player without the jar can still be given items and still
receives the resource pack.

## Install

```bash
cd CheeseDash && ./gradlew build     # CheeseItems compiles against its jar
cd ../CheeseItems && ./gradlew build
```

Drop `build/libs/cheeseitems-1.0.0+1.21.1.jar` and the CheeseDash jar in the server's
`mods/`. First start writes `config/cheeseitems.json` and
`config/cheeseitems_morphos.json`, and logs:

```text
[CheeseItems] Initializing...
[CheeseItems] Default config created
[CheeseItems] Initialized successfully!
[CheeseItems] Server started — 0 items loaded
```

Then set `resourcePackHost` in `config/cheeseitems.json` to an address your players can
actually reach. Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, build order, what the first launch creates, how to verify it loaded, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: from a blank server to a custom icon in a player's hand and a morpho they can wear |
| [Commands](docs/commands.md) | `/cheeseitem` and `/morpho`, argument by argument, with permission levels, tab completion, output and every failure message |
| [Configuration](docs/configuration.md) | The three JSON files and the asset folders, key by key, with a complete annotated example |
| [Custom items](docs/items.md) | The item model: identity, flags, uses, cooldowns, effects, command actions, conditions, per-item endurance, and which flags do nothing |
| [Resource pack](docs/resource-pack.md) | How uploaded PNGs become a pack, the CustomModelData assignment, the endurance bar font, what the pack overwrites |
| [Morphos](docs/morphos.md) | The catalog, the per-player Codex, the GUI, the toggle, the endurance pool, how CobbleShift is driven |
| [Dashboard API](docs/dashboard.md) | Both modules, every REST route, request and response shapes, the two WebSocket channels, and what the role guards do and do not cover |
| [Architecture](docs/architecture.md) | Package layout, class responsibilities, persistence timing, threading, the totem mixin, extension points, known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — including the three ways an item silently does nothing |

## Commands at a glance

| Command | Permission | What it does |
|---|---|---|
| `/cheeseitem give <player> <item_id> [quantity]` | level 2 | builds a custom item and puts it in a player's inventory |
| `/cheeseitem list` | level 2 | every definition, with its on/off state and base item |
| `/cheeseitem reload` | level 2 | re-reads `cheeseitems.json` and refreshes online inventories |
| `/cheeseitem morpho grant <player> <morpho_id>` | level 2 | adds a catalog morpho to a player's Codex |
| `/morpho` | none | opens the Codex GUI on the caller's client |

The command literal is `cheeseitem`, singular. In `give` the player comes first and the
item id second. The older documentation had that order backwards and listed a
`/cheeseitem grant` subcommand that does not exist.

## Dashboard

Two modules, both declared in the `cheesedash` entrypoint:

- **`items`** → `/api/v1/items/*` — item CRUD, give, reload, icon upload, the potion
  effect list the editor needs. Publishes `item_update` on the `items` channel.
- **`morphos`** → `/api/v1/morphos/*` — catalog CRUD, image upload, drag-drop reorder,
  the global endurance defaults, and granting a morpho to a player. Publishes
  `morpho_update` on the `morphos` channel.

Each also serves unauthenticated public routes: `/cheeseitems/api/pack.zip` for the
resource pack, and `/morphos/api/image/<id>` plus `/morphos/api/thumb/<id>` for catalog
images, which is how the in-game GUI and the public store fetch them.

> ⚠️ **An `OPERATOR` on this module is equivalent to console access.** Items can carry
> `useActions` whose commands run at permission level 4, so anyone who can author an item
> can run anything. Write routes require `OPERATOR` or above — enforced both by the module
> and by CheeseDash's own dispatcher — and `OPERATOR` is also the highest bar any module
> route can reach. Grant that role accordingly. Details in
> [dashboard](docs/dashboard.md#authentication-and-role-guards).

## Honest warnings

- **`soulbound` deletes the item.** Items are pulled out of the inventory before death
  and stashed, but the method that puts them back is never called. See
  [items](docs/items.md#flags-that-do-nothing).
- `droppable`, `tradeable`, `placeable`, `zoneId` and `onPickupEffects` are stored,
  served over the API and shown in the editor, and change nothing in play.
- The morpho system drives CobbleShift by executing a command string. It breaks quietly
  the day `cshift` changes its syntax.
- The generated resource pack replaces `assets/minecraft/font/default.json` and the item
  model of every base item in use. Another pack touching the same files will clash.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may
not sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
