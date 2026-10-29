[← CheeseItems](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dashboard.fr.md)

# Dashboard API

CheeseItems declares **two** CheeseDash modules in the `cheesedash` entrypoint of its
`fabric.mod.json`:

| Class | Prefix | Authenticated base | Public base | Nav label |
|---|---|---|---|---|
| `CheeseItemsDashboardModule` | `items` | `/api/v1/items` | `/cheeseitems/api` | CheeseItems 🎁 |
| `MorphoCatalogDashboardModule` | `morphos` | `/api/v1/morphos` | `/morphos/api` | Morphos 🧬 |

Both appear in the dashboard sidebar as soon as CheeseDash discovers them at startup.
CheeseDash serves HTTP on port **44311** and WebSocket on **44312**. Authenticated routes
need a session: `POST /api/v1/auth/login` returns a token you pass as
`Authorization: Bearer <token>`.

## <a id="authentication-and-role-guards"></a>Authentication and role guards

Both dispatchers, `ItemsApi` and `MorphoCatalogApi`, open with a single guard:

```java
if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) return;
```

So **every route that changes anything requires `OPERATOR` or above**, and the read
routes accept any role, `READ_ONLY` included. The guard sits on the HTTP method rather
than on each branch, which is deliberate: a new mutating route cannot be added without
inheriting it, whereas a per-branch guard is one `return` away from being forgotten.

That call is also the correct form — `requireWrite` returns a boolean and answers the
exchange itself when it denies, so its result **must** be acted on; calling it and
discarding the result answers `403` and then does the work anyway.

### What the core actually enforces

The same boundary is applied a second time, before CheeseItems sees the request at all,
by CheeseDash's module dispatcher in `DashboardApi`:

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

Every non-`GET` request to any module — not just this one — is held to `OPERATOR` there.
The module's own guard is therefore defence in depth: useful, and redundant. Three
consequences follow from where the real boundary sits:

- **No route in CheeseItems can require `SUPER_ADMIN`.** The central guard only knows
  `requireWrite`, so `OPERATOR` is the highest bar reachable through it. A route that
  deserved `SUPER_ADMIN` would have to call `requireAdmin` itself; none does.
- **A mutating route exposed as `GET` would not be guarded at all**, by either layer —
  the central guard keys purely on the HTTP method. None exists here today.
- **A single change to that dispatcher would drop the central check everywhere at once**,
  with no module noticing. On this module the local guard would still hold; on modules
  without one, nothing would.

The role column in the tables below states the role actually required in this build.

> ⚠️ The guard draws the role boundary; it does not change what an `OPERATOR` can do.
> An operator can still author an item whose `useActions` run arbitrary commands as the
> server console at permission level 4 ([why](items.md#useactions)) — for instance an
> item whose use runs `op <name>`, handed to a player. On this module, `OPERATOR` is
> equivalent to console access, so grant it accordingly.

The public, unauthenticated routes are outside all of this, because they are served by
`handlePublicImage`, `handlePublicThumb` and `handlePublicRequest`, never by the `handle`
dispatchers above.

Both modules answer `503 {"error":"CheeseItems not ready"}` (or `"Server not ready"`)
before any handler runs if the Minecraft server reference is null — during startup, or
after `SERVER_STOPPING`.

Every unmatched path or method falls through to `404 {"error":"Not found"}`, and any
exception thrown inside a handler is logged and answered as
`500 {"error":"Internal server error"}`.

## Item routes

Base: `/api/v1/items`. Roles: `GET` accepts any role, including `READ_ONLY`; every other
method requires `OPERATOR` or above (see above).

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| `GET` | `/` | — | `200` array of summaries | — |
| `POST` | `/` | definition object | `201` full definition | `409` id taken |
| `GET` | `/:id` | — | `200` full definition + `hasCustomIcon` | `400` invalid id · `404` not found |
| `PUT` | `/:id` | partial definition | `200` merged definition | `400` · `404` · `500` update failed |
| `DELETE` | `/:id` | — | `200 {"deleted":true}` | `400` · `404` · `500` |
| `POST` | `/:id/give` | `{"playerName":"…","quantity":1}` | `200 {"given":true,"quantity":N}` | `404` item · `400` playerName required / player not found |
| `POST` | `/:id/image` | `{"imageBase64":"…"}` | `200 {"uploaded":true}` | `400` · `404` |
| `GET` | `/:id/image` | — | `200` `image/png` | `400` · `404` image not found |
| `DELETE` | `/:id/image` | — | `200 {"deleted":true}` | `400` |
| `POST` | `/reload` | — | `200 {"reloaded":true}` | `500` reload failed |
| `GET` | `/enums` | — | `200 {"potionEffects":[…]}` | — |

### Shapes

`GET /` returns a summary per item, not the full definition — the dashboard list view does
not need the arrays:

```json
[
  {
    "id": "quest_key",
    "displayName": "Rusty Quest Key",
    "nameColor": "#F5A623",
    "baseItem": "minecraft:tripwire_hook",
    "enabled": true,
    "glowing": false,
    "soulbound": false,
    "consumable": false,
    "droppable": true,
    "unbreakable": false,
    "disappearOnDeath": false,
    "maxUses": 0,
    "cooldownTicks": 0,
    "actionCount": 2,
    "effectCount": 1,
    "hasCustomIcon": true
  }
]
```

`GET /:id` returns the definition exactly as it is written to disk, plus `hasCustomIcon`.
Every field is documented in [custom items](items.md).

`POST /` accepts the same shape. An absent or blank `id` is replaced with
`item_<5 hex chars>`. Any field you omit takes its constructor default, so posting `{}`
creates a white paper named *Custom Item*.

`PUT /:id` is a **merge**: only the keys present in the body are touched. `endurance`
is the one key where `null` is meaningful — passing `"endurance": null` removes the pool,
while omitting the key keeps it. The merge and the inventory refresh run on the server
thread through `WebDashboard.executeOnServerThread`, because they touch player
inventories.

`DELETE /:id` also deletes the uploaded icon, frees the item's `customModelData` slot,
saves the config and regenerates the resource pack. It does **not** remove copies already
in player inventories; see [hot reload](items.md#hot-reload-and-items-already-in-inventories).

`POST /:id/give` targets an online player by name. The body's `quantity` defaults to `1`
and, unlike the command, is **not range-checked** — a quantity above the stack size
produces whatever `insertStack` does with it, and a non-integer value throws and comes
back as a `500`. A player who is offline comes back as `400`, not `404`, because the
failure is raised from inside the server-thread task.

`POST /:id/image` takes a base64 PNG, with or without a `data:image/png;base64,` prefix.
Uploading assigns a `customModelData` number if the item does not have one yet, rebuilds
the resource pack, pushes it to online players if its SHA-1 changed, and refreshes
inventories so the new icon appears without a reconnect. There is no size limit and no
check that the bytes really are a PNG.

`GET /enums` returns only `potionEffects` — 37 vanilla effect ids. The route's own javadoc
claims it also returns `categories` and `rarities`; it does not, and no such fields exist
anywhere in the mod.

### Public item route

`GET /cheeseitems/api/pack.zip` — unauthenticated. Serves the generated resource pack as
`application/zip` with `Cache-Control: no-cache`, or `404 {"error":"No resource pack
available"}` when no pack has been built. This is the URL the mod hands to clients, so it
must be reachable from the outside; see
[the resource pack](resource-pack.md#the-pack-is-served-over-http).

## Morpho catalog routes

Base: `/api/v1/morphos`. Roles: `GET` accepts any role, including `READ_ONLY`; every
other method requires `OPERATOR` or above (see above).

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| `GET` | `/` | — | `200` array of entries, sorted | — |
| `POST` | `/` | entry object (may be empty) | `201` created entry | — |
| `GET` | `/:id` | — | `200` entry | `400` invalid id · `404` |
| `PUT` | `/:id` | partial entry | `200` merged entry | `400` · `404` |
| `DELETE` | `/:id` | — | `200 {"deleted":true}` | `400` · `404` |
| `POST` | `/:id/image` | `{"imageBase64":"…"}` | `200 {"uploaded":true}` | `400` · `404` |
| `GET` | `/:id/image` | — | `200` `image/png`, revalidated | `400` · `404` no image |
| `DELETE` | `/:id/image` | — | `200 {"deleted":true}` | `400` |
| `POST` | `/reorder` | `{"ids":["a","b",…]}` | `200 {"ok":true}` | `400` ids required |
| `POST` | `/grant` | `{"playerUuid"\|"playerName", "morphoId"}` | `200 {"granted":true}` | `400` · `404` · `409` already in codex · `500` |
| `GET` | `/global` | — | `200` global endurance | — |
| `PUT` | `/global` | partial endurance | `200` updated endurance | — |

An entry looks like this; every field and its default is documented in
[morphos](morphos.md#the-catalog-entry):

```json
{
  "id": "a3f91c2e",
  "title": "Ditto",
  "description": "It copies anything.",
  "species": "ditto",
  "form": "",
  "shiny": false,
  "hasImage": true,
  "priceCredits": 250,
  "priceGold": 0,
  "availableOnWebsite": true,
  "availableInNpcShop": true,
  "isNew": true,
  "sortOrder": 4,
  "createdAt": 1761700000000,
  "terrestrial": true,
  "aquatic": false,
  "aerial": false,
  "legendary": false,
  "commandsOnTransform": [],
  "commandsOnRevert": [],
  "useCustomEndurance": false,
  "customCapacity": 100,
  "customDrainPerSecond": 2.0,
  "customRegenPerSecond": 1.0
}
```

`POST /` generates an 8-character hex id, stamps `createdAt`, and appends the entry at the
end of the sort order — unless the body carries a non-zero `sortOrder`, which the merge
does read and which is then kept as is. It runs the body through the same merge code as `PUT`, so a
duplicate created by posting a copy of an existing entry keeps its commands and endurance
overrides. `id`, `hasImage` and `createdAt` are **ignored** in the body: the merge does not
read them.

`PUT /global` patches only the keys you send. Values are clamped: `capacity` to at least
1, the two rates to at least 0.

`POST /grant` resolves the player from `playerUuid` if present, otherwise by name among
online players, otherwise through the server's user cache — so an offline player who has
joined before can be granted a morpho. A name that resolves nowhere is a `404`.

`DELETE /:id` removes the entry, deletes its image and its cached thumbnail, then resyncs
every online player's codex so the orphaned entry disappears from their GUI immediately.
The codex prune is permanent: a player who owned that morpho loses it. Re-creating the
entry under a new id does not give it back.

### Public morpho routes

Both unauthenticated, both `GET` only, both answered with PNG revalidation headers so a
client that already has the file gets a `304`:

| Path | Returns |
|---|---|
| `/morphos/api/image/<id>` or `/morphos/api/image/<id>.png` | the uploaded PNG at full resolution |
| `/morphos/api/thumb/<id>` or `/morphos/api/thumb/<id>.png` | a lossless downscale to at most 480 px, generated and cached on first request |

The in-game Codex GUI fetches `/thumb/`, and so does the admin table; the public store
website uses `/image/` for its lightbox. `MorphoCatalogApi`'s javadoc states the public
path is `/cheeseitems/api/morpho-image/<id>.png` — that is stale and wrong; the path comes
from `MorphoCatalogDashboardModule.getPublicPrefix()`, which returns `morphos`.

## WebSocket

Two channels, both published with `DashboardRegistry.broadcast(...)`. Subscribe the usual
way:

```json
{"type":"subscribe","channels":["items","morphos"]}
```

### Channel `items`

One message type, `item_update`:

| Field | Present | Value |
|---|---|---|
| `type` | always | `"item_update"` |
| `itemId` | always | the definition id |
| `itemName` | on `created` and `updated` only | `displayName` |
| `action` | always | `"created"`, `"updated"` or `"deleted"` |

```json
{"type":"item_update","itemId":"quest_key","itemName":"Rusty Quest Key","action":"updated"}
```

Nothing is broadcast for `give`, `reload` or the image routes.

### Channel `morphos`

One message type, `morpho_update`:

| Field | Present | Value |
|---|---|---|
| `type` | always | `"morpho_update"` |
| `morphoId` | except on `reordered` | the entry id |
| `action` | always | `"created"`, `"updated"`, `"deleted"`, `"image_updated"`, `"image_deleted"` or `"reordered"` |

`reordered` carries no id, because the whole list moved. Nothing is broadcast for `grant`
or for the `/global` routes, so a second dashboard tab will not see an endurance change
until it reloads.

## What the dashboard views let an admin do

The `items` view lists definitions with their flags, opens an editor for every field
described in [custom items](items.md), uploads an icon (sent as is — there is no
cropping), duplicates a definition, triggers a reload, and has a give button that asks for
a player name and a quantity. The `morphos` view lists catalog entries with their
thumbnails, edits them, drag-reorders them (which calls `/reorder`), uploads images, edits
the global endurance defaults and grants an entry to a player.

Both views are part of the CheeseDash frontend, not of this mod — the mod only serves JSON.

---

← [Morphos](morphos.md) · [Architecture](architecture.md) →
