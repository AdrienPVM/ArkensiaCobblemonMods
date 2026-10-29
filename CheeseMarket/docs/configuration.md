[← CheeseMarket](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

**CheeseMarket has no configuration file.** Nothing is written to `config/`, there is no
`cheesemarket.json`, and no class in the mod reads `FabricLoader.getConfigDir()` for its
own settings. Every tunable the mod has lives on a shop, and shops live in the world
save.

That is a deliberate consequence of where shops are stored: a shop is world data, not
server configuration, so it travels with the world and not with the server
installation. Copying a world copies its shops, its stock counters and its sales
history.

This page documents the four things you can edit on disk, where they are, and what is
safe to touch while the server runs.

---

## The files

```text
<world>/data/cheesemarket/
├── shops/<shopId>.json              one shop definition per file
├── cheesemarket_stock.json          live stock and per-player counters
├── cheesemarket_transactions.json   the last 500 sales
├── assets/<shopId>/<name>.png       uploaded layout images
└── templates/<name>.json            saved layouts
```

`<world>` is `server.getSavePath(WorldSavePath.ROOT)` — your `level-name`, `world` by
default. The directory tree is created at `SERVER_STARTED` if it is missing.

⚠️ With one exception. `AssetManager` never has `init(worldDataDir)` called on it, so it
falls back to `<config dir>/../world/data` — a hardcoded `world`. If your `level-name`
is not `world`, uploaded images are written and read under a `world/` directory that has
nothing else in it, while shop definitions go to the real save. Keep `level-name=world`
unless you are patching the mod. Full note in
[the layout editor](layout-editor.md#uploaded-images).

| File | Written when | Read when | Safe to hand-edit live? |
|---|---|---|---|
| `shops/*.json` | every shop, set or item mutation | server start, `/shop reload` | no — the next mutation rewrites the file from memory |
| `cheesemarket_stock.json` | every purchase, and at `SERVER_STOPPING` | server start, `/shop reload` | no — same reason |
| `cheesemarket_transactions.json` | every purchase | server start, `/shop reload` | copy it, do not edit it |
| `assets/*/*.png` | dashboard upload | on each `SHOP_VIEW` build | yes, it is read from disk each time |
| `templates/*.json` | dashboard template save | when a template is applied | yes |

The pattern: anything held in memory is rewritten from memory and your edit is lost.
To change a stock counter, stop the server, edit, start — or edit and then
`/shop reload` **before** anyone buys anything.

---

## A shop file, annotated

Everything below is a real field read by `ShopData.fromJson`. Every key is optional; a
missing key takes the default from the `ShopData` constructor.

```json
{
  "id": "general",
  "name": "Magasin général",
  "description": "Consommables et outils",

  // Parsed, stored, served over the API — and ignored by the client.
  // TABS | FLAT | FEATURED. An unknown value falls back to TABS.
  "displayMode": "TABS",

  // Both carried to the client and never drawn.
  "showWelcomeMessage": false,
  "welcomeMessage": "Bienvenue dans le shop !",

  // A vanilla colour name or a hex triplet.
  "titleColor": "gold",
  // Hex RRGGBB, no leading '#'.
  "bgColor": "1A1A2E",
  "borderColor": "2A5A8A",
  "accentColor": "4AC8EA",
  // Confirmation dialog buttons.
  "btnBuyColor": "1A5C2A",
  "btnBuyTextColor": "FFFFFF",
  "btnCloseColor": "1E3A5F",
  "btnCloseTextColor": "CCCCCC",

  // Omit the key entirely for the built-in arrangement.
  // Written by the dashboard editor; see docs/layout-editor.md.
  "layout": {
    "elements": [
      {
        "id": "a1b2c3d4",
        "type": "BACKGROUND",
        "x": 0, "y": 0, "width": 350, "height": 250,
        "zOrder": 0,
        "props": { "src": "default" }
      },
      {
        "id": "e5f6a7b8",
        "type": "TEXT",
        "x": 50, "y": 38, "width": 250, "height": 20,
        "zOrder": 10,
        // The client finds the title by this exact placeholder string.
        "props": { "text": "{shopName}", "color": "{titleColor}",
                   "align": "center", "fontSize": 9, "bold": false }
      },
      {
        "id": "c9d0e1f2",
        "type": "ITEM_GRID",
        "x": 32, "y": 95, "width": 286, "height": 121,
        "zOrder": 5,
        "props": { "cols": 6, "rows": 3, "padX": 4, "padY": 3 }
      },
      {
        "id": "b3a4c5d6",
        "type": "BUTTON",
        "x": 312, "y": 38, "width": 20, "height": 20,
        "zOrder": 20,
        // "close" is the only action the client understands.
        "props": { "action": "close", "src": "default" }
      }
    ],
    "itemCard": {
      "cardWidth": 41, "cardHeight": 36,
      "iconOffsetX": 13, "iconOffsetY": 7, "iconSize": 15,
      "costOffsetX": 0, "costOffsetY": 26,
      "costTextScale": 0.85, "costIconSize": 9,
      "costGap": 2, "costTextIconGap": -1, "costIconOffsetY": 0,
      "costTextColor": "FFCC44"
    }
  },

  // true replaces the sets below with the CheeseItems morpho catalogue at view time.
  "morphoCatalog": false,

  "sets": [
    {
      "id": "d7e8f9a0",
      "name": "Consommables",
      // Drawn before higher values. Only the first ACTIVE set is visible in game.
      "sortOrder": 0,
      "rotation": {
        // ALWAYS | SCHEDULE | MANUAL. Unknown values fall back to ALWAYS.
        "mode": "SCHEDULE",
        // Server wall-clock time, HH:mm. End before start means it crosses midnight.
        // An unparseable time makes the set permanently ACTIVE, not closed.
        "scheduleStart": "08:00",
        "scheduleEnd": "20:00",
        // Only consulted in MANUAL mode.
        "manualActive": true
      },
      "items": [
        {
          "id": "b1c2d3e4",
          // The icon. minecraft:paper is read as "unset" and replaced by
          // the first output's item id — pick something else if you mean paper.
          "displayItem": "minecraft:potion",
          // Empty means the client shows the icon's translated vanilla name.
          "displayName": "Potion de soin",

          // What the player receives. count is forced to at least 1.
          "outputs": [
            { "itemId": "minecraft:potion", "count": 1,
              // Optional MC 1.21.1 component block. Delivered through /give.
              "components": "[potion_contents={potion:\"minecraft:healing\"}]" }
          ],

          // What the player pays, per unit, multiplied by the purchase quantity.
          // The currency IS the item id. components are ignored on costs.
          "costs": [
            { "itemId": "numismatic-overhaul:silver_coin", "count": 5 }
          ],

          // Run on purchase, count × quantity times, from the console command source.
          // {player} becomes the buyer's name. A leading / is stripped.
          "commands": [
            { "command": "title {player} actionbar {\"text\":\"Merci !\"}", "count": 1 }
          ],

          "stock": {
            // UNLIMITED | GLOBAL | PER_PLAYER | PER_PLAYER_RESET
            "mode": "PER_PLAYER_RESET",
            // GLOBAL only. Forced to at least 1.
            "globalStock": 100,
            // The two PER_PLAYER modes. Forced to at least 1.
            "perPlayerLimit": 3,
            // PER_PLAYER_RESET only. DAILY | WEEKLY | MONTHLY | CUSTOM.
            // These are fixed intervals from the last reset, NOT calendar dates.
            "resetType": "DAILY",
            // CUSTOM only. Forced to at least 60.
            "resetIntervalSeconds": 86400
          },

          // false hides the item from players and keeps it in the dashboard.
          "enabled": true,
          "sortOrder": 0
        }
      ]
    }
  ]
}
```

Field-by-field meaning, with the reasoning behind the odd ones, is in
[the shop model](shop-model.md). The JSON comments above are for reading only — Gson's
default parser rejects them, so strip them before using this as a real file.

---

## The stock file

```json
{
  "global": {
    "general:d7e8f9a0:b1c2d3e4": 87
  },
  "player": {
    "general:d7e8f9a0:b1c2d3e4:9d4f3c2b-1a0e-4f5d-8c7b-6a5e4d3c2b1a": 2
  },
  "lastReset": {
    "general:d7e8f9a0:b1c2d3e4": 1761739200
  }
}
```

Three flat maps, string keys, integer or epoch-second values.

- `global` holds **remaining** units, not units sold. A key absent from the map means
  "nobody has bought any yet", and the item's own `globalStock` is used as the starting
  figure.
- `player` holds units **bought** by that player, counted up from zero. Delete a key to
  give that one player their quota back.
- `lastReset` is the epoch second at which a `PER_PLAYER_RESET` item last had its
  per-player keys wiped. Zero or absent means the next access resets.

Keys are `shopId:setId:itemId` and `shopId:setId:itemId:playerUuid`. Nothing prunes keys
for shops, sets or items that no longer exist, so the file grows slowly and forever.
Deleting an orphaned key is harmless.

---

## The transaction file

A JSON array, newest entry first, capped at 500. Entry shape and the caveat about
per-unit costs are in
[purchases](purchases.md#what-a-transaction-entry-contains). Entry 501 pushes the oldest
out of the file for good, so schedule a copy if sales history matters to you.

---

## Reloading

| Change | Picked up by |
|---|---|
| anything edited through the dashboard | immediately, in memory and on disk |
| anything edited in the in-game admin screen | immediately |
| a hand-edited shop file | `/shop reload`, or a restart |
| a hand-edited stock file | `/shop reload`, or a restart |
| an uploaded PNG | the next time a player opens that shop |
| a template | when you apply it to a shop |

`/shop reload` needs permission level 4 and re-reads everything from disk. A player with
a shop panel already open is not refreshed.

---

← [Commands](commands.md) · [The shop model](shop-model.md) →
