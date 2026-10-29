[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

**There is no configuration file.** EggAndCheese never calls
`FabricLoader.getInstance().getConfigDir()`, and nothing appears in `config/` on first
launch. If you went looking for `config/eggandcheese.json`, stop — it does not exist and
creating it has no effect.

Four server settings exist, and they live inside the same persistent state as the
collectibles themselves:

```text
world/data/eggandcheese_registry.dat
```

That file is an NBT blob written by Minecraft's `PersistentStateManager`, not something
you edit by hand. The supported way to change a setting is the dashboard, or the REST
endpoint behind it.

## The four settings

Declared as fields on `EggRegistryData`, read and written through
`GET`/`PUT /api/v1/eggs/config`:

| Key | Type | Default | What it does |
|---|---|---|---|
| `ftbQuestsEnabled` | boolean | `false` | Master switch for the FTB Quests integration: auto-creating a quest when an egg is created, updating it on edit, deleting it on delete, and completing it on discovery |
| `ftbQuestsChapter` | string | `"oeufs"` | Name of the FTB Quests chapter the egg quests are created in |
| `ftbAutoSync` | boolean | `false` | Stored and exposed, but **never read by any logic** |
| `textureRenderDistance` | int | `20` | Distance in blocks beyond which a client replaces the full voxel egg with a simple box. `0` disables the degradation |

Both counters in the same response — `totalEntries` and `totalDiscoveries` — are derived,
read-only, and ignored by `PUT`.

### `ftbQuestsEnabled`

Everything in [the FTB Quests integration](ftb-quests.md) is gated on this flag *and* on
`FtbQuestsHook.isAvailable()`. With the flag off, the mod never touches the quest file
even if FTB Quests is installed; with the flag on and FTB Quests absent, every call
returns immediately.

Turning it on does not retroactively create quests for eggs that already exist. Run
`POST /api/v1/eggs/config/sync-ftb` for that. There is a second safety net: when a player
discovers an egg whose `ftbQuestId` is still `0`, the discovery handler calls
`ensureQuestExists` and creates the quest on the spot.

### `ftbQuestsChapter`

A chapter **name**, not an id. `FtbQuestsHook` looks the chapter up by name and creates it
when no chapter matches. If more than one chapter carries the name — which has happened,
and the hook logs it — the duplicates are merged into one:

```text
[EAC-FTB] Merged 2 duplicate chapters into one 'oeufs'
```

The setter normalises `null` to `"oeufs"`, so you cannot clear the value; sending
`{"ftbQuestsChapter": null}` resets it to the default. An empty string, on the other
hand, is accepted as-is and will have the hook look for a chapter named `""`.

The default is French because the server it was written for is French. If you rename your
chapter, change this setting to match or the mod will silently create a second chapter
next to yours.

### `ftbAutoSync`

⚠️ **Dead setting.** `isFtbAutoSync()` is called in exactly one place —
`EggsApi.handleGetConfig`, to report it back. No behaviour reads it. It persists, it
round-trips through the API, and it does nothing. Presumably it was meant to run
`syncAll` automatically; today you have to call `POST /config/sync-ftb` yourself. Leaving
it `false` and leaving it `true` are the same thing.

### `textureRenderDistance`

A client-side level-of-detail threshold, decided by the server and pushed to clients. The
mechanics, the per-egg override, and the ⚠️ about it not reaching clients on join are in
[texture generation and previews](rendering.md#render-distance-and-level-of-detail).

The setter clamps to `>= 0`; there is no upper bound. Values beyond your pack's render
distance are equivalent to `0`, since a block further away than that is not rendered at
all.

Changes take effect on connected clients immediately, because `PUT /config` sends a
`SyncRegistryPayload` to every online player as its last step. No restart, no relog.

## Per-collectible settings

Four fields on `CollectibleEntry` behave like configuration rather than content. All four
are written with `PUT /api/v1/eggs/collectibles/:id`.

| Field | Type | Default | Range | Effect |
|---|---|---|---|---|
| `scale` | float | `1.0` | 0.1 – 4.0, clamped in the setter | Visual size of the egg in the world |
| `renderDistanceOverride` | int | `-1` | `-1`, or any `>= 0` | `-1` means "use `textureRenderDistance`"; anything else is this egg's own threshold in blocks |
| `title` | string | `""` | — | The FTB quest's title. **Not** shown in the discovery message |
| `subtitle` | string | `""` | — | The FTB quest's description |

`scale` is clamped silently: `PUT {"scale": 10}` returns `200` with `scale: 4.0`. Always
read the value back.

A `renderDistanceOverride` of `0` is not the same as `-1`. `0` means "never degrade this
egg", which is what you want for a centrepiece egg visible across a plaza. `-1` means
"follow the global setting".

`title` and `subtitle` have no effect at all when FTB Quests integration is off. They are
stored, returned by the API, and otherwise inert. In particular, setting a title does not
change what a player reads when they find the egg — that message uses the egg's id. See
[placement and discovery](placement-and-discovery.md#discovery).

## Client-side

One client value exists, and it is not configurable by the player:

```java
// src/client/java/com/roicheese/eggandcheese/renderer/EggRenderConfig.java
public class EggRenderConfig {
    public static int textureRenderDistance = 20;
}
```

It is overwritten whenever a `SyncRegistryPayload` arrives. There is no client config
file, no mod-menu screen, no keybind. A player who finds eggs expensive to render cannot
turn them down; the server operator has to.

## Worked example

There is no file to copy, so the equivalent of an annotated example config is the
request that writes one. Full state, every key set explicitly:

```bash
# Replace <token> with the session token from POST /api/v1/auth/login
curl -X PUT http://localhost:44311/api/v1/eggs/config \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "ftbQuestsEnabled": true,
    "ftbQuestsChapter": "oeufs",
    "ftbAutoSync": false,
    "textureRenderDistance": 32
  }'
```

Annotated, key by key:

```json
{
  "ftbQuestsEnabled": true,
  "ftbQuestsChapter": "oeufs",
  "ftbAutoSync": false,
  "textureRenderDistance": 32
}
```

- `ftbQuestsEnabled: true` — quests are created, updated, deleted and completed alongside
  the eggs. Requires FTB Quests to be installed; harmless if it is not.
- `ftbQuestsChapter: "oeufs"` — the chapter name as it appears in your quest book. Match
  your existing chapter exactly, or a new one is created.
- `ftbAutoSync: false` — no effect either way. Left in the example because the API
  accepts it and the dashboard shows it.
- `textureRenderDistance: 32` — eggs keep their full voxel model up to 32 blocks, then
  degrade to a box. Raise it for a showcase area, lower it if clients report frame drops
  in a dense egg field.

The response is the config shape plus the two counters, so you can confirm what was
actually stored:

```json
{
  "ftbQuestsEnabled": true,
  "ftbQuestsChapter": "oeufs",
  "ftbAutoSync": false,
  "textureRenderDistance": 32,
  "totalEntries": 56,
  "totalDiscoveries": 318
}
```

## Reload behaviour

No setting needs a restart. No setting needs a reload command either — `/eac reload`
despite its name reloads nothing, as [commands](commands.md#eac-reload) explains.

A `PUT` is applied on the server thread, marks the state dirty, and takes effect on the
next action that reads it. The only value propagated to clients is
`textureRenderDistance`, and that happens in the same request.

The state is written to disk on Minecraft's save cycle. A `PUT` followed immediately by a
hard kill of the process loses the change.

---

← [Commands](commands.md) · [Collectible DNA](dna.md) →
