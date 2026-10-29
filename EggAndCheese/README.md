# EggAndCheese

> 🇫🇷 [Lire en français](README.fr.md)

> A collectible egg hunt where every egg's appearance is computed from an immutable DNA
> record rather than stored as a texture.

An admin designs an egg in the CheeseDash dashboard — palette, two pattern layers, a glow
layer, a stamp, a surface finish, five post-processing flags — watches it turn in a 3D
preview, then hides the block somewhere in the world. A player who finds it right-clicks
it once: a pickup sound, a line in the action bar, and a decorative egg in their inventory
carrying that exact texture. They can place it, frame it, or put it on a spinning
pedestal.

Nothing about that texture ships in the jar. The only images in the mod are three item
icons and one particle. A 64×64 colour map and two emissive frames are generated on the
client the first time each distinct DNA appears on screen, from a 13-step pipeline that
exists twice: once in Java for the game, once in JavaScript for the dashboard preview.

The eggs cannot be mined by non-admins in survival (creative mode and explosions still
remove them), are pass-through for movement, waterloggable, and
recorded once per player. With FTB Quests installed, each egg can carry its own quest,
created automatically with the egg itself as the quest icon.

**Minecraft 1.21.1 · Fabric · Java 21 · 49 source files, ~7 100 lines**

---

## Requirements

| | |
|---|---|
| **Required** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Optional** | [FTB Quests](https://modrinth.com/mod/ftb-quests-fabric) — one quest per egg |
| **Optional** | fabric-permissions-api / [LuckPerms](https://luckperms.net/) — the `eggandcheese.admin` node, otherwise op level 2 |

CheeseDash is a hard dependency: collectibles are authored in the web dashboard and there
is no in-game admin GUI. Install the mod on **both** the server and the clients — it
registers 26 blocks and 25 items, and all rendering is client-side.

## Install

```bash
cd CheeseDash && ./gradlew build      # EggAndCheese compiles against its jar
cd ../EggAndCheese && ./gradlew build
```

Drop `build/libs/eggandcheese-1.0.0+1.21.1.jar` and the CheeseDash jar into the server's
`mods/`, and the EggAndCheese jar into each client's `mods/`. There is **no config file**;
the mod stores its state in `world/data/eggandcheese_registry.dat`. On startup it logs:

```text
[EggAndCheese] Initializing...
[EggAndCheese] Ready!
```

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, which side to install on, build order, what the first launch creates, how to verify it loaded, clean removal and what it destroys |
| [Getting started](docs/getting-started.md) | A tutorial: from an empty dashboard to a second player discovering an egg whose texture you designed |
| [Commands](docs/commands.md) | `/eac` and `/pedestal`, argument by argument, with permissions, every output message and every failure |
| [Configuration](docs/configuration.md) | The four settings, where they live given there is no config file, the per-egg fields, and an annotated worked example |
| [Collectible DNA](docs/dna.md) | The 14-component record, every enum and range, and what immutability means for an API caller doing a partial `PUT` |
| [Texture generation and previews](docs/rendering.md) | The 13-step pipeline, the 15 emissive patterns, the client texture cache, level of detail, and why the 3D preview lives in CheeseDash |
| [Placement and discovery](docs/placement-and-discovery.md) | How an egg is placed, why it cannot be broken, what a discovery records, and what the player sees |
| [Decorative eggs and pedestals](docs/display.md) | The reward item, the 23 pedestal variants and their recipes, display tuning, and the `egg_found` advancement |
| [FTB Quests integration](docs/ftb-quests.md) | The reflection hook, what it resolves, quest creation and completion, full sync, and the fallback when it breaks |
| [Integrity: duplicates and orphans](docs/integrity.md) | The live block index, reconciliation on load, the Integrity button, and the chunk-loading rule learned from two production crashes |
| [Dashboard API](docs/dashboard.md) | Every route under `/api/v1/eggs`, request and response shapes, the `egg_discovery` WebSocket message, and the role situation |
| [Architecture](docs/architecture.md) | Package layout, state and threading, extension points, dead code, and a candid list of known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — including the three ways a right-click silently does nothing |

## Commands at a glance

| Command | Permission | What it does |
|---|---|---|
| `/eac` | `eggandcheese.admin`, else op 2 | prints a pointer to the dashboard |
| `/eac give <id>` | `eggandcheese.admin`, else op 2 | gives you the admin placement item for an existing egg |
| `/eac reload` | `eggandcheese.admin`, else op 2 | prints the number of registry entries — it reloads nothing |
| `/pedestal scale <0.5–3.0>` | **none** | size of the item on the pedestal you are looking at |
| `/pedestal height <−0.5–0.5>` | **none** | vertical offset of that item |
| `/pedestal pillar <0.0–5.0>` | **none** | pillar height under the platform |
| `/pedestal spin` | **none** | toggles rotation |

Everything else — creating, editing, renaming, deleting, teleporting, statistics,
integrity — is dashboard only. Details in [commands](docs/commands.md).

## Dashboard

Registers under the `eggs` prefix, so everything lives at `/api/v1/eggs/*`: collectible
CRUD, DNA merge-update, discoveries, give-item, configuration, enums, statistics, and the
integrity report and repair. An `egg_discovery` message is pushed on the `eggs` WebSocket
channel each time a player finds one.

## Honest warnings

⚠️ **The dashboard role boundary is held by the core, not by this mod.** `EggsApi`
contains no call to `WebDashboard.requireWrite` or `requireAdmin`. Writes are still
`OPERATOR`-only, because CheeseDash's module dispatcher rejects every non-`GET` request
that fails `requireWrite` before the module ever sees it — so a `READ_ONLY` account really
is read-only here. The cost is that no route in this mod can require `SUPER_ADMIN`, that a
mutating `GET` would be unguarded, and that one change to that dispatcher would drop every
check at once. [The detail](docs/dashboard.md#what-the-core-actually-enforces).

⚠️ **`/pedestal` has no permission requirement at all.** Any player on the server can
retune any pedestal they can stand in front of.

⚠️ **Renaming a placed egg orphans its block.** `POST /collectibles/:id/rename` changes
the registry key and leaves the block carrying the old id. Choose ids before placing, or
run the integrity repair afterwards.

⚠️ **The FTB Quests integration is pure reflection** against
`dev.ftb.mods.ftbquests.*` internals — 733 lines of it. It degrades quietly and logs what
it resolved, but it will break when FTB Quests restructures. An advancement of your own on
the `eggandcheese:egg_found` criterion trigger is the stable alternative — the mod's own
`egg_found` advancement sits in the pre-1.21 `advancements/` folder and is never loaded
([details](docs/display.md#the-advancement)).

The `CHEESE` collectible type is half-implemented: the enum, the API and the statistics
know about it, and no cheese block or item is ever registered. Creating a collectible with
`"type":"cheese"` gives you an egg with a cheese label.

## For contributors

`CollectibleDNA` is an immutable Java record. To change one field you rebuild the whole
record, merging modified fields over the old ones — which is exactly what the dashboard
`PUT` does, so a partial `dna` object keeps the fields you omit.

**Never force-load a chunk from a `BLOCK_ENTITY_LOAD` or `CHUNK_LOAD` handler.** A June
2026 fix did, deadlocked the server thread inside c2me's chunk pipeline, and crashed
production twice in one night. The reconciliation is now deferred onto a queue drained at
tick end — not `server.execute`, which runs inline when you are already on the server
thread — and chunk reads check `isChunkLoaded` first. Both defences must stay. The account
is in [integrity](docs/integrity.md#never-force-load-a-chunk-from-a-block-entity-load-handler).

The Java package was renamed from `com.roicheese.eggandcheese` to
`com.roicheese.eggandcheese` when the mod was published. Resource identifiers are
unaffected: the namespace is `eggandcheese`, derived from the mod id, so
`eggandcheese:egg_found` and every recipe id are unchanged.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
