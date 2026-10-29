[← CheeseMarket](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

CheeseMarket registers a CheeseDash module through the `cheesedash` entrypoint
declared in `fabric.mod.json`:

```json
"entrypoints": { "cheesedash": ["com.cheesemarket.dashboard.CheeseMarketDashboardModule"] }
```

The module's prefix is **`market`**, so every route below is reachable at
`/api/v1/market/...` on CheeseDash's HTTP port, **44311**. The module also supplies a
display name (`CheeseMarket`), a nav icon (`🛒`) and a nav description
(`Gestion des boutiques`) for the dashboard sidebar.

Authentication is CheeseDash's, not this mod's: `POST /api/v1/auth/login` returns a
session token, and every call below needs `Authorization: Bearer <token>`.

---

## Required roles

`MarketApi` contains **no call to `requireWrite` and no call to `requireAdmin`**. Grep
it yourself — there is not one. The guard that protects these routes is CheeseDash's
central module dispatch in `DashboardApi`:

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    // Enforce write permissions for non-GET requests on modules
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
```

That matters because in this repository `WebDashboard.requireWrite(exchange)` **returns
a boolean and blocks nothing on its own** — ignoring its result lets the request through.
The dispatch above uses the correct form, `if (!…) return;`, so the guard is real.

The rule that follows from it, and it is a blanket rule rather than a per-route one:

| Method | Role required |
|---|---|
| `GET` | any authenticated session — `READ_ONLY` is enough |
| `POST`, `PUT`, `DELETE` | `OPERATOR` or higher |

**No market route requires `SUPER_ADMIN`.** Deleting a shop, with its items, its sales
configuration and its uploaded images, is an `OPERATOR` action.

Two consequences of the guard living upstream rather than in `MarketApi`:

- a `READ_ONLY` session can read every shop definition, every price, the full
  transaction log with player names and UUIDs, and every uploaded image;
- if this module were ever mounted anywhere other than CheeseDash's module dispatch, it
  would have no authorization at all. Nothing in the mod enforces a role by itself.

Every non-GET module request is also written to CheeseDash's audit log by the same
dispatch, as `market.post`, `market.put` or `market.delete` with the full path.

---

## Shops

### `GET /api/v1/market/shops`

Every loaded shop, as summaries. No parameters.

```json
[
  { "id": "general", "name": "Magasin général", "description": "Consommables",
    "displayMode": "TABS", "setCount": 3, "itemCount": 41 }
]
```

`itemCount` counts all items, enabled or not.

### `POST /api/v1/market/shops`

Creates a shop. Role: `OPERATOR`.

```json
{ "id": "farmers", "name": "Marché fermier" }
```

Both fields are optional. An empty or absent `id` makes the mod generate an 8-character
one. `name` is applied and saved only if present. The creation runs on the server thread
through `WebDashboard.executeOnServerThread`.

Returns the full shop detail object. `409` with
`{"error":"Shop ID already exists: <id>"}` if the id is taken, `500` with the exception
message if the server-thread task throws.

### `GET /api/v1/market/shops/:shopId`

The full definition, with runtime enrichment. Sets gain `active` (the live rotation
verdict) and `rotationLabel` (a French label such as `Horaire (08:00→20:00)`); items are
sorted by `sortOrder` and gain `outputsSummary`, `costsSummary`, `commandsSummary` and
`effectiveName`.

`400` `Invalid shop ID` if the id contains `..`, `/`, `\`, `%` or is 128 characters or
longer. `404` `Shop not found` otherwise.

### `PUT /api/v1/market/shops/:shopId`

Partial update. Role: `OPERATOR`. Only the keys present in the body are applied:
`name`, `description`, `displayMode`, `showWelcomeMessage`, `welcomeMessage`,
`titleColor`, `bgColor`, `borderColor`, `accentColor`, `btnBuyColor`,
`btnBuyTextColor`, `btnCloseColor`, `btnCloseTextColor`, `morphoCatalog`, `layout`.

An unparseable `displayMode` is ignored rather than rejected. A `layout` of `null`
resets to the default layout; a `layout` object replaces it wholesale.

Returns the new shop detail. `404` if the shop is gone, `500` on a throwing task.

### `DELETE /api/v1/market/shops/:shopId`

Role: `OPERATOR`. Deletes the shop from memory and its JSON file, **and** its asset
directory — unlike `/shop delete` in game, which leaves the images behind.

```json
{ "deleted": true }
```

---

## Layout

### `GET /api/v1/market/shops/:shopId/layout`

The shop's layout. A shop with no layout gets `ShopLayout.buildDefault()` rather than
an empty object, so the editor always has something to open.

### `PUT /api/v1/market/shops/:shopId/layout`

Role: `OPERATOR`. Body is a layout object: `elements`, `itemCard`, `confirmLayout`.

- A body carrying `elements` or `itemCard` is parsed and stored.
- A body carrying neither resets the layout to null — the default.
- More than 20 elements gives `500` `{"error":"Maximum 20 elements"}`, because the
  check throws inside the server-thread task and the catch turns it into a 500 rather
  than a 400.

Returns the stored layout, or the default if it was reset.

---

## Assets

### `GET /api/v1/market/shops/:shopId/assets`

An array of asset names, without the `.png`.

### `POST /api/v1/market/shops/:shopId/assets`

Role: `OPERATOR`. Base64 upload.

```json
{ "name": "shop-bg", "data": "data:image/png;base64,iVBORw0KGgo…" }
```

A `data:` URL prefix is stripped at the first comma, so either form works.

```json
{ "uploaded": true, "name": "shop-bg" }
```

`400` for a missing name, missing data, undecodable base64, or any `AssetManager`
rejection: `Invalid asset name`, `Empty data`, `File too large (max 2 MB)`,
`Not a valid PNG file`, `Maximum 10 assets per shop`, `Cannot create directory`,
`Failed to write: <message>`.

### `DELETE /api/v1/market/shops/:shopId/assets/:name`

Role: `OPERATOR`. `{"deleted":true}`, or `404` `Asset not found`.

### `GET /api/v1/market/assets/:shopId/:name`

Serves the PNG bytes. `Content-Type: image/png` and
`Cache-Control: public, max-age=3600` — so a replaced image can take an hour to appear
in a browser that cached it; the dashboard editor appends a cache-busting query string
for that reason. `400` `Invalid path` if there is no slash after the shop id, `404`
`Asset not found` otherwise.

Note the shape: this route is `/market/assets/...`, not
`/market/shops/:id/assets/...`. It still needs a valid session like everything else —
images are not public.

---

## Sets

### `POST /api/v1/market/shops/:shopId/sets`

Role: `OPERATOR`. Body `{"name": "Consommables"}`, optional. The set gets a random
8-character id and a `sortOrder` equal to the current set count. Returns the new set's
JSON.

### `PUT /api/v1/market/shops/:shopId/sets/:setId`

Role: `OPERATOR`. Applies `name`, `sortOrder` and any subset of `rotation`:

```json
{ "name": "Soirée", "sortOrder": 1,
  "rotation": { "mode": "SCHEDULE", "scheduleStart": "20:00", "scheduleEnd": "02:00" } }
```

Flipping a `MANUAL` set is a `PUT` with `rotation.manualActive`. Returns the set JSON.
`404` `Set not found`, `400` `Invalid set ID`.

### `DELETE /api/v1/market/shops/:shopId/sets/:setId`

Role: `OPERATOR`. `{"deleted":true}`. The set's items go with it. Their stock counters
stay in `cheesemarket_stock.json`.

---

## Items

### `POST /api/v1/market/shops/:shopId/sets/:setId/items`

Role: `OPERATOR`. Creates an item and immediately applies the same body the `PUT`
accepts, so an item can be created fully formed in one call. Returns the item JSON.

### `PUT /api/v1/market/shops/:shopId/sets/:setId/items/:itemId`

Role: `OPERATOR`. Applies any subset of:

| Key | Shape |
|---|---|
| `displayItem` | string |
| `displayName` | string |
| `enabled` | boolean |
| `sortOrder` | int |
| `outputs` | array of `{itemId, count, components?}` — **replaces** the list |
| `costs` | array of the same — **replaces** the list |
| `commands` | array of `{command, count}` — **replaces** the list |
| `stock` | object; `mode`, `globalStock`, `perPlayerLimit`, `resetType`, `resetIntervalSeconds`, each applied individually |

The three arrays are cleared and rebuilt, so a `PUT` carrying `costs` must carry the
whole price. `stock` is merged key by key onto the existing config. Unparseable enum
values are ignored silently.

### `DELETE /api/v1/market/shops/:shopId/sets/:setId/items/:itemId`

Role: `OPERATOR`. `{"deleted":true}`.

### `POST /api/v1/market/shops/:shopId/sets/:setId/items/:itemId/toggle`

Role: `OPERATOR`. Flips `enabled` and saves. Returns `{"enabled": false}`.

### `POST /api/v1/market/shops/:shopId/sets/:setId/items/:itemId/reset-stock`

Role: `OPERATOR`. ⚠️ **Does nothing.** The handler body is a single
`respond(200, "{\"reset\":true}")` with no logic behind it. Counters are not cleared, no
file is written, and the response is indistinguishable from success. To actually reset a
counter, edit `cheesemarket_stock.json` and `/shop reload`, as described in
[configuration](configuration.md#the-stock-file).

---

## Transactions and statistics

### `GET /api/v1/market/transactions`

| Query parameter | Type | Default | Effect |
|---|---|---|---|
| `shopId` | string | none | returns only entries for that shop |
| `n` | int | 50 | how many to return; clamped to 1–500 |

```json
{ "transactions": [ … ], "total": 312, "returned": 50 }
```

`total` is the size of the whole in-memory log, before filtering; `returned` is what
came back. The filter is applied while counting toward `n`, so filtering by shop with
`n=50` gives up to 50 entries *for that shop*.

The log holds at most 500 entries overall, newest first. Morpho-catalog purchases are
not recorded at all and never appear here.

### `GET /api/v1/market/stats`

```json
{ "shopCount": 4, "totalSets": 11, "totalItems": 143,
  "enabledItems": 139, "transactionCount": 312 }
```

Counted live by walking the shops. `transactionCount` is capped by the 500-entry log and
is not a lifetime total.

---

## Templates

### `GET /api/v1/market/templates`

An array of template names, without the `.json`.

### `POST /api/v1/market/templates`

Role: `OPERATOR`.

```json
{ "name": "wide-grid", "layout": { "elements": [ … ], "itemCard": { … } } }
```

`{"saved":true}`. `400` `Name required`, `400` `Layout required` if `layout` is missing
or not an object, `400` `Invalid template name` or `Maximum 50 templates` from
`AssetManager`.

### `GET /api/v1/market/templates/:name`

The layout object, or `404` `Template not found`.

### `DELETE /api/v1/market/templates/:name`

Role: `OPERATOR`. `{"deleted":true}` or `404` `Template not found`.

---

## Error shapes

| Status | When |
|---|---|
| `200` | success; the body is the resource, or a small acknowledgement object |
| `400` | an id failed `isValidId`, or an asset/template was rejected |
| `404` | shop, set, item, asset or template not found; or an unrecognised route, with the route echoed back |
| `409` | shop id already in use, on create |
| `500` | a server-thread task threw; the body carries the exception message |
| `503` | `{"error":"Server not available"}` before the server has started, or `{"error":"ShopManager unavailable"}` |

Validation runs before anything else in each handler, and every mutation goes through
`WebDashboard.executeOnServerThread`, so an HTTP thread never touches shop state
directly.

---

## WebSocket

One message type, on the **`market`** channel. Subscribe to it the usual CheeseDash
way:

```json
{ "type": "subscribe", "channels": ["market"] }
```

### `market_purchase`

Broadcast by `ShopGuiHandler` after a successful purchase through
`DashboardRegistry.broadcast("market", …)`:

```json
{
  "type": "market_purchase",
  "playerName": "Steve",
  "shopId": "general",
  "itemName": "Potion de soin",
  "quantity": 3,
  "timestamp": 1761739391284
}
```

`timestamp` is `System.currentTimeMillis()`, not the ISO string the transaction log
uses. `itemName` is the item's effective display name, and there is no `setId` or
`itemId` — the message is for a live feed, not for reconciliation. Use
`GET /transactions` for that.

CheeseDash's own `DiscordWebhookManager` recognises this type and maps it to its
`MARKET_PURCHASE` Discord event, so a purchase can reach a Discord channel without any
code in this mod.

The broadcast is skipped when CheeseDash is not loaded, guarded both by
`isModLoaded("cheesedash")` and a `NoClassDefFoundError` catch.

**No other WebSocket message is published by this mod.** Shop, set and item edits, asset
uploads, template changes and stock resets are not broadcast — a second dashboard
session does not see them until it refetches.

---

← [Purchases](purchases.md) · [Architecture](architecture.md) →
