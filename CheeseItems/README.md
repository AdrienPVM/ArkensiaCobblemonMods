# CheeseItems

> Custom quest items with NBT, effects and hot reload — authored entirely from the
> dashboard.

Define items in CheeseDash: name, lore, model, NBT payload, status effects, and
behaviour. They are registered at runtime and can be reloaded without restarting the
server, which makes building a quest chain a lot less painful.

**Minecraft 1.21.1 · Fabric · Java 21 · 28 files, ~4 300 lines**

---

## Requirements

- **CheeseDash** — required
- Fabric Loader ≥ 0.16.0, Fabric API, Java 21

Build CheeseDash first, then drop both in `mods/`.

## Commands

| Command | Purpose |
|---------|---------|
| `/cheeseitem give <id> [player]` | give a custom item |
| `/cheeseitem grant <id> <player>` | grant it as a quest reward |
| `/cheeseitem list` | list registered items |
| `/cheeseitem morpho` | open the Morpho Codex |
| `/cheeseitem reload` | hot-reload definitions from disk |

## Dashboard

Registers under the `items` prefix: full CRUD, give, reload, and the enum lists the
editor needs. An `item_update` event is pushed over the WebSocket on every change.

## Notes for 1.21.1

The item API changed in 1.21, and this mod uses the current form:

- custom NBT goes through `DataComponentTypes.CUSTOM_DATA` with `NbtComponent.of(nbt)`
  — there is no `getOrCreateNbt()` any more
- display names go through
  `stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(...))`
- `StatusEffects.FROST_RESISTANCE` does not exist; the ICE type uses `RESISTANCE`

Pre-filled signs are built with `DataComponentTypes.BLOCK_ENTITY_DATA` carrying an
`NbtCompound` with `front_text.messages` (a JSON Text array) and `is_waxed: true`.

## Arkensia specifics

The Morpho Codex screen and the bundled resource pack of item icons were designed for
Arkensia's quest line. They are cosmetic, and the pack is served to clients
automatically.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
