[← CheeseItems](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

CheeseItems has no settings file in the usual sense. It has three JSON documents that are
*data* — item definitions, the morpho catalog, player-owned morphos — and two keys of
actual configuration buried in the first of them. Everything lives under the server's
`config/` directory.

| Path | Written by | Contains |
|---|---|---|
| `config/cheeseitems.json` | the mod, on every CRUD operation | item definitions, `customModelData` map, `resourcePackHost` |
| `config/cheeseitems_morphos.json` | the mod, debounced | the morpho catalog and the global endurance defaults |
| `config/cheeseitems_morpho.json` | the mod, debounced | per-player morpho ownership. Not meant to be edited |
| `config/cheeseitems_assets/<id>.png` | icon upload | one custom item icon per definition |
| `config/cheeseitems_assets/bar/0.png` … `10.png` | you, by hand | the endurance bar font frames |
| `config/cheeseitems_assets/morphos/<id>.png` | catalog image upload | one morpho image per catalog entry |

The first two JSON files are created on first launch if they are missing — empty, with no
sample content. The third, `cheeseitems_morpho.json`, only appears once a player owns a
morpho. `config/cheeseitems.json` comes out as:

```json
{
  "items": [],
  "customModelData": {},
  "resourcePackHost": ""
}
```

## cheeseitems.json

### Top-level keys

| Key | Type | Default | What it does |
|---|---|---|---|
| `items` | array of definitions | `[]` | the custom items. One page of its own: [custom items](items.md) |
| `customModelData` | object, id → integer | `{}` | which `CustomModelData` number each item's icon uses |
| `resourcePackHost` | string | `""` | `host:port` the client should download the resource pack from |

`customModelData` is bookkeeping, not configuration. A number is assigned the first time
an icon is uploaded for an item and reused forever after, so a client that has the pack
cached does not see every icon shift when a new item appears. The next number to hand out
is derived at load time as `max(existing) + 1`. Editing these numbers by hand breaks the
mapping between a stack already in the world and its icon — leave them alone.

`resourcePackHost` is the one key you must set yourself.

### resourcePackHost

The mod builds the download URL as:

```text
http://<resourcePackHost>/cheeseitems/api/pack.zip
```

So the value is a host and a port, with no scheme and no path: `play.example.net:44311`.
It has to be reachable **from a player's machine**, not from the server.

When the key is empty, the mod resolves a fallback in this order:

1. CheeseDash's configured port, as `localhost:<port>` — and it logs that it did so:

   ```text
   [CheeseItems] resourcePackHost auto-resolved to localhost:44311 (set resourcePackHost in cheeseitems.json for production)
   ```

2. If CheeseDash cannot be read, the literal `localhost:44311`.

⚠️ **`localhost` is a working default for a single-player test and a silent failure in
production.** Every remote client resolves `localhost` to itself, finds nothing on 44311,
and gives up on the pack. The player sees vanilla icons and no error. If custom icons are
part of your plan, set this key on the first day.

Note the scheme is hardcoded to `http`. There is no way to serve the pack over HTTPS
without a reverse proxy that terminates TLS on a different hostname, and the client will
refuse an `http` pack only if the launcher has been configured to — vanilla accepts it.

Changing `resourcePackHost` requires a restart or a `/cheeseitem reload`: the value is read
on every pack send, but the file is only re-read on reload.

## cheeseitems_morphos.json

Two top-level keys:

```json
{
  "entries": [ … ],
  "globalEndurance": {
    "capacity": 100,
    "drainPerSecond": 2.0,
    "regenPerSecond": 1.0
  }
}
```

`entries` is the catalog, documented field by field in
[morphos](morphos.md#the-catalog-entry).

### globalEndurance

| Key | Type | Default | Clamp | What it does |
|---|---|---|---|---|
| `capacity` | integer | `100` | ≥ 1 | pool size for every morpho that does not override it |
| `drainPerSecond` | float | `2.0` | ≥ 0 | points spent per second while transformed |
| `regenPerSecond` | float | `1.0` | ≥ 0 | points recovered per second while not transformed |

The numbers are abstract points; only their ratio matters. With the defaults a
transformation lasts `100 / 2 = 50` seconds and refills in `100 / 1 = 100` seconds.

`drainPerSecond: 0` gives unlimited transformation time — the pool never reaches zero, so
nothing auto-reverts, and the bar stays on the actionbar, full, for as long as the player
stays transformed (it is drawn whenever the pool is draining). That is
the supported way to turn endurance off. `regenPerSecond: 0` is the opposite trap: once a
player empties the pool it never refills while they are connected, and the only reset is a
reconnect, because the state is memory-only.

A catalog entry with `useCustomEndurance: true` ignores all three keys in favour of its own
`customCapacity`, `customDrainPerSecond` and `customRegenPerSecond`.

These values are resolved when a player transforms. Editing them from the dashboard
(`PUT /api/v1/morphos/global`) takes effect on the next transform, not on a transformation
already running.

## cheeseitems_morpho.json

Player ownership. The shape is in [morphos](morphos.md#the-per-player-codex). Editing it by
hand is possible but awkward: the file is keyed by UUID, writes are debounced, and the
running server will overwrite your edit from memory on its next save. Grant morphos with
the command or the API instead, and if you really must edit the file, stop the server
first.

## Reload semantics

| What | Picked up by | Needs a restart? |
|---|---|---|
| `items` in `cheeseitems.json` | `/cheeseitem reload`, `POST /api/v1/items/reload` | no |
| `customModelData` | the same reload | no, but do not edit by hand |
| `resourcePackHost` | the same reload | no |
| `cheeseitems_morphos.json` edited by hand | nothing — the catalog loads once | **yes** |
| `globalEndurance` edited through the API | immediately, for the next transform | no |
| `cheeseitems_morpho.json` edited by hand | nothing | **yes**, and stop the server first |
| Bar font PNGs added or changed | the next pack regeneration, so any icon upload or a restart | effectively yes |

⚠️ There is no reload for the morpho catalog. `MorphoCatalog.load()` runs once, when the
singleton is first touched during mod initialisation, and nothing calls it again.
Hand-editing `cheeseitems_morphos.json` on a running server will be **overwritten** by the
next debounced save. Edit the catalog from the dashboard, or stop the server.

## A complete annotated example

```json
{
  // Item definitions. Each one is described in docs/items.md.
  "items": [
    {
      "id": "quest_key",
      "displayName": "Rusty Quest Key",
      "nameColor": "#F5A623",
      "baseItem": "minecraft:tripwire_hook",
      "lore": ["Opens something, somewhere."],
      "loreColor": "#9CA3AF",
      "glowing": true,
      "dyeColor": -1,
      "enabled": true,
      "maxStackSize": 1,
      "droppable": true,
      "tradeable": true,
      "placeable": false,
      "consumable": true,
      "soulbound": false,
      "disappearOnDeath": false,
      "unbreakable": true,
      "maxUses": 0,
      "cooldownTicks": 100,
      "onUseEffects": [],
      "passiveEffects": [],
      "onPickupEffects": [],
      "useActions": [
        {
          "command": "function arkensia:open_vault",
          "count": 1,
          "feedback": "The lock gives way.",
          "showTimer": false
        }
      ],
      "useConditions": {
        "dimension": "minecraft:overworld",
        "minLevel": 0,
        "globalCooldownTicks": 0
      }
    }
  ],

  // Assigned by the mod when an icon is uploaded. Do not edit.
  "customModelData": {
    "quest_key": 1
  },

  // host:port a player's client can reach. No scheme, no path.
  "resourcePackHost": "play.example.net:44311"
}
```

⚠️ The comments above are for reading only. `JsonParser.parseString` reads leniently, so
the file does load with `//` comments in it — but the mod never writes them back: the next
save (any dashboard edit, an icon upload, or the server start itself, which saves after
assigning `customModelData`) rewrites the file without them. Keep your notes
elsewhere.

A real syntax error is a different story, and deserves a second look: a parse error is
caught, logged as `[CheeseItems] Failed to load config: …` at `ERROR` and then ignored. The item list stays empty and the next save writes that empty list over your
file. Keep a backup of `cheeseitems.json` before editing it by hand, and check the log
after a reload.

---

← [Commands](commands.md) · [Custom items](items.md) →
