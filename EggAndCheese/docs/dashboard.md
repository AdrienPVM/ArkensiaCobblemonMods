[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

EggAndCheese registers a CheeseDash module through the `cheesedash` custom entrypoint in
`fabric.mod.json`:

```json
"entrypoints": {
  "cheesedash": ["com.roicheese.eggandcheese.dashboard.EggAndCheeseDashboardModule"]
}
```

| Module property | Value |
|---|---|
| prefix | `eggs` |
| display name | `EggAndCheese` |
| nav icon | 🥚 |
| nav description | `Collectibles et œufs` |

Every route below therefore sits under `/api/v1/eggs/`. CheeseDash serves HTTP on port
**44311** and the WebSocket on **44312**.

`EggAndCheeseDashboardModule` keeps a reference to the `MinecraftServer` between
`onServerStart` and `onServerStop`, and answers `503 {"error":"Server not available"}`
to any request that arrives while that reference is null — during startup, or after
shutdown. Otherwise it hands the request straight to `EggsApi.handle`.

## Authentication and roles

Authentication is CheeseDash's, not this mod's. `POST /api/v1/auth/login` returns a
session token, which you pass as `Authorization: Bearer <token>` on every other request.

### What the core actually enforces

`EggsApi.java` contains no call to `WebDashboard.requireWrite` or
`WebDashboard.requireAdmin` — verified by grepping the whole source tree. The role
boundary is nonetheless enforced, by CheeseDash's module dispatcher in `DashboardApi`
rather than by this mod:

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
    ...
}
```

That is the correct form — it tests the return value and returns — so in this build every
non-`GET` request under `/api/v1/eggs/` does require `OPERATOR` or above, and `GET`
requests accept any role including `READ_ONLY`. A `READ_ONLY` account cannot delete a
collectible, rename one, clear discoveries, hand out items, rewrite the config or run the
integrity repair. The boundary holds.

What does **not** hold is the reason it holds. The protection is a property of the core's
dispatch, keyed purely on the HTTP method, and the module contributes nothing. Three
consequences:

- **No route in EggAndCheese can require `SUPER_ADMIN`.** Deleting a collectible along
  with its world block and FTB quest, and rewriting the mod config for every online
  player, are both `OPERATOR`-level operations, because `OPERATOR` is the only bar the
  dispatcher knows. A route that wanted `SUPER_ADMIN` would have to call `requireAdmin`
  itself.
- **A mutating `GET` would be completely unguarded.** None exists today — every handler
  that changes state is matched on `POST`, `PUT` or `DELETE` — but nothing in the module
  would stop one from being added, and nothing would flag it.
- **A change to the dispatcher removes every check at once.** `EggsApi` has no second
  line of defence.

Adding `if (!WebDashboard.requireWrite(exchange)) return;` at the top of each mutating
handler would be defence in depth: useful, but redundant today, and not the boundary
itself. In this repository `WebDashboard.requireWrite(exchange)` **returns a
boolean** and blocks nothing on its own — ignoring its result is a no-op, so that is the
only shape worth writing.

The role column in the tables below states the role **actually** required in this build,
and marks with (core) the cases where the guarantee comes from CheeseDash's dispatch
rather than from the module.

## Conventions

- Request and response bodies are JSON. Responses are built with Gson.
- `:id` is URL-decoded, then validated by `isValidId`: non-empty, no `..`, no `/`, no
  `\`, no `%`, shorter than 128 characters. A failure is `400
  {"error":"Invalid collectible ID"}`.
- Every handler that touches game state wraps its work in
  `WebDashboard.executeOnServerThread`, so a mutation is applied on the Minecraft thread
  even though the HTTP thread is what returned the response.
- A missing collectible is `404 {"error":"Collectible not found"}`. Internal failures are
  `500` with the exception message in `error`.
- An unmatched path gives `404 {"error":"Unknown eggs route: <route>"}`, or
  `404 {"error":"Unknown collectible route"}` under `/collectibles/:id`.
- A malformed JSON body does **not** error: `parseJson` returns an empty object, so the
  request behaves as if you had sent `{}`. A `PUT` with broken JSON is a successful no-op.

## Collectibles

| Method | Path | Role enforced | What it does |
|---|---|---|---|
| `GET` | `/collectibles` | any role | Every entry, sorted by id, as summaries |
| `POST` | `/collectibles` | `OPERATOR`+ (core) | Create an entry |
| `GET` | `/collectibles/:id` | any role | One entry, full detail including DNA |
| `PUT` | `/collectibles/:id` | `OPERATOR`+ (core) | Update fields and/or DNA, relocate the block |
| `DELETE` | `/collectibles/:id` | `OPERATOR`+ (core) | Delete the entry, its world block and its FTB quest |
| `POST` | `/collectibles/:id/rename` | `OPERATOR`+ (core) | Change the id |
| `POST` | `/collectibles/:id/randomize-dna` | `OPERATOR`+ (core) | Replace the DNA with a new random one |
| `GET` | `/collectibles/:id/discoveries` | any role | Who found it, with names |
| `POST` | `/collectibles/:id/discoveries` | `OPERATOR`+ (core) | Record a discovery by player name |
| `DELETE` | `/collectibles/:id/discoveries` | `OPERATOR`+ (core) | Clear every discovery |
| `DELETE` | `/collectibles/:id/discoveries/:uuid` | `OPERATOR`+ (core) | Remove one discovery |
| `POST` | `/collectibles/:id/give-admin` | `OPERATOR`+ (core) | Give the admin placement item to an online player |
| `POST` | `/collectibles/:id/give-deco` | `OPERATOR`+ (core) | Give the decorative item to an online player |

### Summary shape

Returned by `GET /collectibles` for each entry, and nested in every detail response:

```json
{
  "id": "cave_nord",
  "type": "egg",
  "title": "The northern cave",
  "subtitle": "Behind the waterfall",
  "dimension": "minecraft:overworld",
  "x": 812, "y": 71, "z": -304,
  "scale": 1.4,
  "renderDistanceOverride": -1,
  "positionLost": false,
  "discoveryCount": 7,
  "decoCount": 7,
  "rarity": "EPIC",
  "rarityDisplay": "Épique",
  "rarityColor": "#AA00FF",
  "palette": "ICE",
  "paletteDisplay": "Glace"
}
```

`rarityDisplay` and `paletteDisplay` come from the enums' `getDisplayName()`, which is
**French in the source**. They are display strings from the Java enum, not translated by
the dashboard's i18n layer. Prefer `rarity` and `palette` if you want to translate them
yourself.

### Detail shape

`GET /collectibles/:id` is the summary plus two fields:

```json
{
  "...": "all summary fields",
  "dna": {
    "seed": 6857411294431479272,
    "palette": "ICE",
    "pattern": "CRYSTALS",
    "patternScale": 2,
    "secondaryPattern": "MARBLE",
    "patternBlend": 35,
    "emissive": "STARS",
    "emissiveDensity": 60,
    "rarity": "EPIC",
    "stamp": "MOON",
    "microTexture": "ICY",
    "visualFlags": 15,
    "varnishLevel": 45,
    "customPalette": "",
    "dnaCode": "EAC-5F2A91C30B4D77E8-ICE.CRYSTALS.2.MARBLE.35.STARS.60.EPIC.MOON.ICY.0F.45",
    "flagLighting": true,
    "flagSpecular": true,
    "flagEmboss": true,
    "flagVarnish": true,
    "flagRimGlow": false
  },
  "discoveredByUuids": ["c3f1…", "9a02…"]
}
```

The five `flag*` booleans are decoded from `visualFlags` for convenience. They are
read-only: a write must send the integer.

### `POST /collectibles`

All body fields are optional.

| Field | Type | Default | Notes |
|---|---|---|---|
| `id` | string | auto `egg_NNN` | Blank or absent means `generateUniqueId()` |
| `type` | string | `egg` | `CollectibleType.fromId`, so anything unknown becomes `egg` |
| `dimension` | string | `minecraft:overworld` | Not validated against loaded worlds |
| `x`, `y`, `z` | int | `0`, `64`, `0` | |
| `seed` | long | random | When present, the DNA is `randomDNA(seed)`; otherwise fully random |
| `scale` | float | `1.0` | Clamped to 0.1–4.0 by the setter |
| `title`, `subtitle` | string | `""` | Used as the FTB quest title and description |

Responses: `200` with the detail shape, `409 {"error":"ID already exists: <id>"}`, or
`500`.

⚠️ `POST` creates a **registry entry only**. No block is placed in the world. The entry
is not flagged `positionLost` either, so it claims coordinates where nothing stands until
an admin places the item there. Create from the dashboard, then place in game.

There is no way to supply a full DNA on creation: you get a random one, optionally from
your seed. To author an exact appearance, create the entry and then `PUT` the DNA.

### `PUT /collectibles/:id`

Accepts any subset of `title`, `subtitle`, `scale`, `positionLost`,
`renderDistanceOverride`, `dimension`, `x`, `y`, `z`, `dna`. DNA merge semantics are in
[Collectible DNA](dna.md#immutability-and-what-a-put-has-to-do) — omitted DNA fields keep
their current value.

Two side effects beyond the registry write:

- **Relocation.** If the body contains any of `x`, `y`, `z`, `dimension` and the entry is
  not `positionLost`, the old block is removed and a new `admin_egg` block is placed at
  the new position, configured with the entry's id, DNA, scale and render-distance
  override. Both worlds must be loaded for this to run; if either lookup fails, the
  registry moves and the block does not.
- **In-place sync.** If the position did *not* change but the body contains
  `renderDistanceOverride`, `scale` or `dna`, the existing block entity is updated in
  place, which pushes the change to clients immediately.

The linked FTB quest's title and description are updated when FTB integration is enabled
and the entry has a quest id.

### `DELETE /collectibles/:id`

Deletes the FTB quest if there is one, removes the world block unless the entry is
`positionLost`, then removes the registry entry. `200 {"deleted":true}`.

### `POST /collectibles/:id/rename`

Body `{"newId":"cave_nord"}`. The new id goes through `isValidId`, then
`EggRegistryData.renameEntry`, which refuses if the target id belongs to a different
entry. Renaming to the same id is a successful no-op. Response is the detail shape, or
`400` — either `{"error":"Invalid new ID"}` or
`{"error":"Rename failed (ID conflict or not found)"}`.

⚠️ Rename does **not** touch the world block. The block keeps the old `registryId`,
which makes it an orphan the moment the entry is renamed, while the renamed entry has no
block. Run [the integrity tool](integrity.md) afterwards, or re-place the egg. This is a
real bug, not a caveat.

### `POST /collectibles/:id/randomize-dna`

Body `{"seed": 1234}` or empty. Builds a complete new random DNA — your hand-tuned
palette and pattern are discarded — writes it to the entry, and syncs the block entity
when the entry is not `positionLost`. Returns the detail shape.

### Discoveries

`GET` returns an array of `{"uuid": "...", "name": "..."}`. The name comes from the
server's user cache; when the UUID is not cached, `name` is the first eight characters of
the UUID.

`POST` takes `{"playerName":"Lia"}` and resolves it through
`server.getUserCache().findByName`, so the player need not be online but must have joined
before. `400 {"error":"playerName required"}` when the field is blank,
`400 {"error":"Player not found: Lia"}` when the cache has never seen them. Success is
`200 {"added":true}`.

`DELETE` on the collection clears every discovery: `200 {"cleared":true}`. `DELETE` with
a UUID removes one: `200 {"removed":true}`, or `400 {"error":"Invalid UUID"}` on an
unparseable one. Neither touches `decoReceivedBy`.

### Give

Both give routes take `{"playerName":"Lia"}` and require the player to be **online** —
they go through `server.getPlayerManager().getPlayer(name)` and answer
`400 {"error":"Player not online: Lia"}` otherwise. The item is inserted into the
inventory, or dropped at the player's feet if it is full.

| Route | Item | Response |
|---|---|---|
| `give-admin` | `eggandcheese:admin_egg` with `dna`, `registryId` and `scale` in `CUSTOM_DATA` | `{"given":true,"type":"admin"}` |
| `give-deco` | `eggandcheese:collectible_egg` with the same DNA and the `registryId` | `{"given":true,"type":"deco"}` |

`give-deco` does not record anything in `decoReceivedBy`, so it can be used repeatedly on
the same player.

## Configuration

| Method | Path | Role enforced | What it does |
|---|---|---|---|
| `GET` | `/config` | any role | Read the four settings plus two counters |
| `PUT` | `/config` | `OPERATOR`+ (core) | Write any subset, then push a sync packet to every online player |
| `POST` | `/config/sync-ftb` | `OPERATOR`+ (core) | Run a full FTB Quests sync |

```json
{
  "ftbQuestsEnabled": false,
  "ftbQuestsChapter": "oeufs",
  "ftbAutoSync": false,
  "textureRenderDistance": 20,
  "totalEntries": 56,
  "totalDiscoveries": 318
}
```

`totalEntries` and `totalDiscoveries` are read-only; `PUT` ignores them. The `PUT`
re-reads and returns the same shape, so the response is the new state.

`PUT` is also the only code path that ever calls `ModPackets.sendRegistrySync` — see
[render distance](rendering.md#render-distance-and-level-of-detail) for why that matters.

`POST /config/sync-ftb` answers `200 {"synced":true}` whether or not FTB Quests is
installed; `FtbQuestsHook.syncAll` returns immediately when the hook is unavailable.

## Statistics

`GET /stats`:

```json
{
  "totalEntries": 56,
  "totalDiscoveries": 318,
  "eggCount": 56,
  "cheeseCount": 0,
  "byRarity": { "COMMON": 21, "UNCOMMON": 14, "RARE": 10, "EPIC": 7, "LEGENDARY": 3, "MYTHIC": 1 }
}
```

`cheeseCount` counts entries whose type is `CHEESE`. No cheese block or item is
registered in this version, so on a normal install it is `0` — see
[architecture](architecture.md#known-weaknesses).

## Enums

`GET /enums` returns every dropdown the dashboard's editor needs, in one response. No
server state is touched, so this is the one handler that does not go through the server
thread.

| Key | Entries | Fields per entry |
|---|---|---|
| `palettes` | 34 | `id`, `displayName`, `colors` (six `RRGGBB` strings) |
| `patterns` | 26 | `id`, `displayName` |
| `emissives` | 16 | `id`, `displayName` |
| `rarities` | 6 | `id`, `displayName`, `color` (`#RRGGBB`) |
| `stamps` | 10 | `id`, `displayName` |
| `microTextures` | 8 | `id`, `displayName` |
| `types` | 2 | `id` (`egg` / `cheese`), `name` (`EGG` / `CHEESE`) |

## Integrity

| Method | Path | Role enforced | What it does |
|---|---|---|---|
| `GET` | `/integrity` | any role | Duplicate and orphan report over loaded chunks |
| `POST` | `/integrity/reconcile` | `OPERATOR`+ (core) | Unbind duplicates and orphans |

Both are documented in full, with their payloads and the reasoning behind them, in
[integrity](integrity.md#the-dashboard-endpoints).

## WebSocket

One message type is published, on the **`eggs`** channel. Subscribe the usual way:

```json
{"type":"subscribe","channels":["eggs"]}
```

### `egg_discovery`

Sent from `AdminCollectibleBlockEntity.handlePlayerDiscovery` when a player's discovery
is recorded — not on an admin right-click, and not when the dashboard adds a discovery by
hand.

```json
{
  "type": "egg_discovery",
  "playerName": "Lia",
  "eggId": "cave_nord",
  "timestamp": 1750300800000
}
```

`timestamp` is `System.currentTimeMillis()`. The broadcast is guarded by
`FabricLoader.isModLoaded("cheesedash")` and wrapped in a `NoClassDefFoundError` catch,
so a build without CheeseDash on the classpath degrades quietly instead of throwing
inside the interaction.

**No other WebSocket message is published by this mod.** Create, update, delete, rename,
config changes and integrity repairs are all silent — a second dashboard session will not
see them until it reloads the view.

---

← [Integrity: duplicates and orphans](integrity.md) · [Architecture](architecture.md) →
