# EggAndCheese

> A collectible egg hunt where every egg is procedurally generated from an immutable
> DNA record.

Admins hide collectibles (eggs or cheese wedges) around the world, each with a unique
procedurally generated texture. Players find them by right-clicking, which records the
discovery, hands out a matching decorative item, and can complete an FTB Quests task.

**Minecraft 1.21.1 · Fabric · Java 21 · 49 files, ~6 400 lines**

---

## Requirements

- **CheeseDash** — required. Collectibles are authored in the web dashboard.
- Fabric Loader ≥ 0.16.0, Fabric API, Java 21

Optional: **FTB Quests** (task completion), **JourneyMap** (automatic admin
waypoints), **LuckPerms** (permission node `eggandcheese.admin`, falling back to OP
level 2).

Build CheeseDash first, then drop both in `mods/`.

---

## Admin workflow

Everything happens in CheeseDash, under the **Eggs** section. (Earlier versions had an
in-game admin GUI opened with `/eac`; it was removed when administration moved to the
dashboard.)

1. **New collectible.** Pick a type (egg or cheese), then cycle through:
   - **Palette** — Warm, Cool, Nature, Dark, Pastel, Fire, Ice, Mystic, Gold, Candy
   - **Pattern** — Spots, Stripes, Marble, Geometric, Veins, Constellation, Crystals,
     Bubbles, Waves, Mosaic
   - **Pattern scale** — 1 to 4
   - **Emissive** type and density (see table below)

   A live 3D rotating preview and a 2D icon preview update as you go, and
   **Regenerate** reshuffles the random seed while keeping your parameters. Give it a
   unique id (`cave_nord`, `spawn_secret`…) and confirm.

2. **Place it.** You receive an admin placement item; right-click to place. The block
   is indestructible for non-admins.

3. **Adjust.** The detail page has a scale slider (0.1× to 4.0×), teleport, give admin
   item, give reward item, and delete (which removes the world block too).

4. **Check integrity.** The dashboard has an **Integrity** button that scans for
   duplicate or mislabelled eggs. It exists because both did happen in production.

## Player experience

A player finds the collectible, right-clicks it, and:

1. receives a unique decorative item carrying the same procedural texture,
2. completes the matching FTB Quest task, if one is configured,
3. is recorded as having discovered it — each egg can only be found once per player.

The decorative item can be placed as a block, put in an item frame, or shown on a
display pedestal.

## Emissive patterns

| Type | Appearance |
|------|------------|
| None | no emissive layer |
| Spots | glowing dots scattered across the surface |
| Lines | bright glowing line segments |
| Runes | angular rune-like glyphs |
| Stars | multi-pointed star bursts |
| Aurora | a flowing band of light |
| Veins | glowing crack-like veins |
| Sparks | small sparks with trailing light |

## FTB Quests integration

Right-clicking an admin egg fires `eggandcheese:egg_found` with the egg's id. In your
quest book, add a task of type **Advancement**:

```
Advancement ID: eggandcheese:egg_found
```

For a task tied to one specific egg, use the criterion trigger directly:

```json
{
  "trigger": "eggandcheese:egg_found",
  "conditions": {
    "egg_id": "your_egg_id_here"
  }
}
```

## Commands

| Command | Purpose |
|---------|---------|
| `/eac give <id>` | give yourself the admin placement item for an existing egg |
| `/eac pedestal` · `/eac pillar` | placement helpers |
| `/eac scale` · `/eac height` · `/eac spin` | display tuning |
| `/eac reload` | reload definitions and print the registry count |

Permission node `eggandcheese.admin`, falling back to OP level 2.

## Dashboard

Registers under the `eggs` prefix: CRUD for collectibles, DNA merge-update,
discoveries, configuration, enums, statistics, and the integrity endpoint. An
`egg_discovery` event is pushed over the CheeseDash WebSocket on each find.

## For contributors

**`CollectibleDNA` is an immutable Java record.** To change one field you rebuild the
whole record — `new CollectibleDNA(...)`, merging the modified fields over the old
ones. A `PUT` that forgets a field will blank it.

**Never force-load a chunk from a `BLOCK_ENTITY_LOAD` or `CHUNK_LOAD` handler.** This
mod learned that the hard way: a reconciliation pass calling `getBlockEntity` during
block-entity load deadlocked the server watchdog under c2me and took production down
twice in one night. The fix was to defer the work with `server.execute(...)` and read
chunks non-blockingly with `getChunk(..., FULL, false)`. That pattern is still in the
code — please keep it.

The duplicate protection (a runtime `LiveAdminEggIndex`, reconciliation on load, and
removal of the old block when one is re-placed) exists for the same reason.

## Building

```bash
cd ../CheeseDash && ./gradlew build   # required first
cd ../EggAndCheese && ./gradlew build # -> build/libs/eggandcheese-1.0.0.jar
```

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
