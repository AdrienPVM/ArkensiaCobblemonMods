[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](websocket.fr.md)

# WebSocket

The REST API answers questions. The WebSocket pushes events: TPS once a second, player
joins, restart countdowns, every audit entry, and whatever the installed modules decide
to broadcast. It is implemented in `WebSocketServer.java`, 193 lines, on top of
`org.java-websocket:Java-WebSocket:1.5.7`, which is bundled inside the jar through
Jar-in-Jar (`include implementation(...)` in `build.gradle`) so there is nothing to
install alongside it.

```text
ws://<server>:44312/
```

The port is the HTTP port plus one, computed in `CheeseDashMod` as
`config.getPort() + 1`. It has no setting of its own, and `GET /api/v1/status` returns
it as `wsPort` so a client never has to assume. There is no TLS: the server is a plain
`ws://` listener, so put it behind the same tunnel or VPN as the HTTP port.

---

## The handshake

Three steps, in this order, and the order is enforced.

**1. Connect.** The server records the connection as *pending* with a timestamp. A
cleanup thread runs every five seconds and closes any connection that has been pending
for more than five seconds with close code `4001` and the reason `Auth timeout`.

**2. Authenticate.** The first message must be:

```json
{"type":"auth","token":"<master token>"}
```

On success the server answers and the connection moves to the authenticated set:

```json
{"type":"auth_ok"}
```

On failure the connection is closed immediately with code `4001` and the reason
`Invalid token`. There is no error message and no retry on the same socket — reconnect
and send a correct token.

Any message other than `auth` sent before authenticating closes the connection with
`4001 Not authenticated`. A message that is not a JSON object, or a JSON object with no
`type`, is ignored silently.

**3. Subscribe.** Authentication alone delivers nothing. A connection receives only the
channels it asked for:

```json
{"type":"subscribe","channels":["tps","players","audit","schedule"]}
```

There is no acknowledgement. `unsubscribe` takes the same shape and removes the channels
from the set. Channel names are not validated, so subscribing to `tpz` is accepted and
simply never delivers anything. Subscribing twice is harmless; the set is a set.

`{"type":"ping"}` answers `{"type":"pong"}`, which is useful for keeping an idle
connection alive through a proxy. Nothing else is accepted.

### Only the master token is accepted

⚠️ The check is `WebDashboard.constantTimeEquals(token, config.getToken())` — a
constant-time comparison against the **master token only**. `SessionManager` is never
consulted, so none of the following work:

- a session id from `POST /api/v1/auth/login` (`ses_…`),
- an additional account's token from `config.accounts[]`, whatever its role.

Two consequences. First, live data is in practice a `SUPER_ADMIN` feature: an
`OPERATOR` or `READ_ONLY` account has no credential that the WebSocket will accept.
Second, and worse: **the shipped dashboard itself does not get past the handshake.**
`app.js` sends `API.getToken()`, and in `api.js` that function is
`function getToken() { return getSessionId(); }` — the session id created at login. The
server compares it to the master token, the comparison fails, and the socket closes with
`4001`. `app.js` then retries every three seconds while the user stays logged in, and
falls back to polling `GET /api/v1/status` every ten seconds whenever the socket is
down, which is why the overview page still shows a TPS figure that refreshes slowly.
Anything genuinely live — the audit stream, join/leave toasts, the restart countdown —
does not arrive.

A client you write yourself works fine if it sends the master token. That is the
asymmetry to keep in mind while reading the rest of this page: everything below is what
the server broadcasts, verified in the code, not what the bundled frontend currently
manages to display.

A channel also carries no role filter of its own. Once a connection is
authenticated with the master token it can subscribe to anything, including `audit`,
which replays who did what from which IP.

---

## Channels that are actually broadcast

A channel exists because something calls
`DashboardRegistry.broadcast(channel, jsonMessage)` — the static entry point any mod can
call safely, since it does nothing when CheeseDash's WebSocket is absent. CheeseDash's
own code sometimes calls `webSocketServer.broadcast(...)` directly, which skips the
Discord routing described in [Discord webhooks](discord-webhooks.md).

| Channel | Published by | Message types |
|---|---|---|
| `tps` | CheeseDash core | `tps` |
| `players` | CheeseDash core | `player_count`, `player_join`, `player_leave`, `player_list` |
| `audit` | CheeseDash core | `audit` |
| `schedule` | CheeseDash core | `schedule_created`, `schedule_tick`, `schedule_cancelled`, `schedule_fired` |
| `market` | CheeseMarket | `market_purchase` |
| `eggs` | EggAndCheese | `egg_discovery` |
| `items` | CheeseItems | `item_update` |
| `morphos` | CheeseItems (Morphos module) | `morpho_update` |
| `prison` | CheeseGuard | `prison_added`, `prison_released`, `prison_updated` |
| `dungeons` | CobblemonDungeons | `dungeon_start`, `dungeon_complete`, `dungeon_fail`, `dungeon_boss_spawn`, `dungeon_player_death` |
| `store` | CheeseStore | `store_update`, `store_purchase`, `store_gallery_update`, `store_cosmetic_update`, `store_cosmetic_purchase`, `store_playcosmetic_update`, `store_playcosmetic_purchase` |
| `raids` | CheeseRaids | `raid_update` |

### Channels that are subscribed to but never published

`app.js` subscribes to `logs`, `events` and `dimensions` as well, and
`handleWsMessage` has branches for `log`, `event_update`, `dim_update` and
`snapshot_done`. Nothing in this repository broadcasts any of them — a repository-wide
search for `broadcast("logs"`, `broadcast("events"` and `broadcast("dimensions"` finds
nothing. The server console view therefore fills itself by polling
`GET /api/v1/server/logs`, not from a stream, and the events and dimensions views
refresh when you ask them to. The Discord mapper has the same dangling entries; see
[the mapping table](discord-webhooks.md#channel-to-event-mapping).

---

## Message payloads

Every message is a JSON object with a `type`. Nothing is wrapped, nothing is batched,
and the channel name is not repeated in the payload — a client that subscribes to
several channels distinguishes messages by `type` alone.

### `tps` · channel `tps`

Emitted every 20 ticks, and only when at least one client is connected
(`getConnectionCount() > 0`), so an idle server does no work for nobody.

```json
{"type":"tps","value":19.8}
```

`value` is the measured tick rate, clamped to 20.0 and rounded to one decimal.

### `player_count` · channel `players`

Sent in the same 20-tick block as `tps`, which makes it a cheap heartbeat.

```json
{"type":"player_count","count":7,"max":60}
```

### `player_join` · channel `players`

```json
{"type":"player_join","name":"RoiCheese","uuid":"ca9e…","world":"minecraft:overworld"}
```

### `player_leave` · channel `players`

No `world` on this one.

```json
{"type":"player_leave","name":"RoiCheese","uuid":"ca9e…"}
```

### `player_list` · channel `players`

Sent right after every join and, for a disconnect, on the next server tick — the leaving
player is still in the player manager while `DISCONNECT` runs, so the broadcast is
deferred through `server.execute(...)` to get an accurate list.

```json
{
  "type": "player_list",
  "players": [
    {"name":"RoiCheese","uuid":"ca9e…","world":"minecraft:overworld"}
  ]
}
```

### `audit` · channel `audit`

One message per audit entry, emitted from `AuditLogger.log` before the entry is written
to disk. It carries every field of the stored entry **except `ip`**, which stays in
`config/cheesedash_audit.json` and in `GET /api/v1/admin/audit`.

```json
{
  "type": "audit",
  "timestamp": "2026-03-09T18:42:11.004Z",
  "username": "admin",
  "role": "SUPER_ADMIN",
  "action": "server.command",
  "detail": "say hello"
}
```

### `schedule_created` · channel `schedule`

The nested `action` object is `ScheduledAction.toJson()`, the same shape
`GET /api/v1/server/schedule` returns under `active`.

```json
{
  "type": "schedule_created",
  "action": {
    "id":"sa_3f8a1c","type":"RESTART","message":"Weekly maintenance",
    "scheduledAt":1772131931004,"createdAt":1772131331004,
    "createdBy":"admin","remainingSeconds":600
  }
}
```

### `schedule_tick` · channel `schedule`

Emitted by the countdown ticker **every five seconds**, and every second over the last
ten (`remaining <= 10 || remaining % 5 == 0`).

```json
{"type":"schedule_tick","remaining":582,"actionType":"RESTART","message":"Weekly maintenance"}
```

### `schedule_cancelled` · channel `schedule`

Deliberately bare — no id, no type, so a client watching two things at once cannot tell
*what* was cancelled without re-reading `GET /api/v1/server/schedule`.

```json
{"type":"schedule_cancelled"}
```

### `schedule_fired` · channel `schedule`

Sent as the action fires, before the world save and the stop. On a `RESTART` it is the
last thing the connection receives.

```json
{"type":"schedule_fired","actionType":"RESTART"}
```

---

## Module payloads

These come from the satellite mods, not from CheeseDash. They are documented here
because the channel is CheeseDash's, but each mod owns its own shape and may change it;
treat what follows as read from the code at the time of writing.

### `market_purchase` · channel `market` · CheeseMarket

Fired from `ShopGuiHandler` after a successful purchase, and only when `cheesedash` is
loaded.

```json
{
  "type":"market_purchase","playerName":"RoiCheese","shopId":"spawn_general",
  "itemName":"Iron Pickaxe","quantity":2,"timestamp":1772131331004
}
```

### `egg_discovery` · channel `eggs` · EggAndCheese

From `AdminCollectibleBlockEntity` when a player finds a collectible. `eggId` is the
registry id, not a display name.

```json
{"type":"egg_discovery","playerName":"RoiCheese","eggId":"egg_026","timestamp":1772131331004}
```

### `item_update` · channel `items` · CheeseItems

`action` is `created`, `updated` or `deleted`. The delete message carries no `itemName`.

```json
{"type":"item_update","itemId":"quest_key","itemName":"Rusty Key","action":"updated"}
```

### `morpho_update` · channel `morphos` · CheeseItems

`action` is `created`, `updated`, `deleted`, `reordered`, `image_updated` or
`image_deleted`. On `reordered` there is no `morphoId` at all, because the whole list
moved.

```json
{"type":"morpho_update","morphoId":"morpho_01","action":"image_updated"}
```

### `prison_added` · `prison_released` · `prison_updated` · channel `prison` · CheeseGuard

The nested `entry` is the full `PrisonEntry` JSON. The top-level fields are duplicated
out of it on purpose, so CheeseDash's Discord mapper can read them without parsing the
nested object. Fields that are null in the entry are simply absent.

```json
{
  "type":"prison_added",
  "entry":{ "...": "the full PrisonEntry" },
  "playerName":"Griefer","reason":"griefing spawn",
  "durationMs":3600000,"duration":"1h","byName":"admin"
}
```

`prison_released` adds `releaseReason` (`EXPIRED` when the sentence ran out, otherwise
a pardon) and `releasedByName`.

### `dungeon_*` · channel `dungeons` · CobblemonDungeons

Five types, one shape. `players` holds display names, falling back to the first eight
characters of the UUID for a player who is offline. `bossSpecies` appears only once a
boss has spawned.

```json
{
  "type":"dungeon_boss_spawn","dungeonId":"frost_keep","dungeonName":"Frost Keep",
  "tier":3,"players":["RoiCheese"],"timeLeft":412,"lives":2,"bossSpecies":"glaceon"
}
```

### `store_*` · channel `store` · CheeseStore

Seven types. The update messages embed the changed object as `product`, `item` or
`cosmetic`, serialised by the store's own `toJson()`; the purchase messages describe a
completed order. CheeseStore is the chattiest module on the bus, which is worth knowing
before you subscribe to `store` on a busy server.

### `raid_update` · channel `raids` · CheeseRaids

Emitted on every raid phase change and progress update.

```json
{"type":"raid_update","raidId":"hoopa","phase":"BOSS","participants":12}
```

---

## Lifecycle, threading and failure modes

The WebSocket server is created in `onInitialize` but started in `SERVER_STARTED`, right
after the HTTP server. It is a daemon thread with `setReuseAddr(true)`, so a quick
restart does not trip over a lingering socket in `TIME_WAIT`.

**⚠️ `enabled: false` does not stop it.** `WebDashboard.start()` checks
`config.isEnabled()` and logs `Disabled in config, skipping.` — but
`CheeseDashMod` calls `webSocketServer.start()` unconditionally, outside any such
check. Turning the dashboard off in `config/cheesedash.json` therefore closes port
44311 and leaves **port 44312 listening**. It accepts connections, demands the master
token, and broadcasts TPS and audit entries to anyone who has it. If you disable the
dashboard for security reasons, block both ports at the firewall; do not assume the
config did it.

`broadcast` iterates the authenticated connections on whatever thread called it — the
server tick thread for `tps` and the player events, an HTTP thread for an audit entry, a
scheduler thread for a countdown tick — and `conn.send` failures are swallowed per
connection. One dead socket cannot break a broadcast to the others, and a broadcast
cannot throw into the server tick loop.

Both connection maps are `ConcurrentHashMap`, and `onClose` and `onError` remove the
socket from both. `IOException` is not logged, because a client that closes its laptop
lid produces one and the log does not need it.

On `SERVER_STOPPING`, `shutdown()` calls `stop(1000)`: one second to drain, then the
sockets die. Clients see a normal close, not `4001`, which is how a well-behaved client
tells "the server is going down" from "my token is wrong".

---

## Broadcasting from your own module

```java
import com.cheesedash.DashboardRegistry;
import com.google.gson.JsonObject;

JsonObject msg = new JsonObject();
msg.addProperty("type", "kart_race_finished");
msg.addProperty("preset", preset.getId());
msg.addProperty("winner", player.getName().getString());
DashboardRegistry.broadcast("kart", msg.toString());
```

Four rules, all of them learned from the mods already on the bus:

1. **Call `DashboardRegistry.broadcast`, not the server directly.** It no-ops when the
   WebSocket is not running, and it also feeds the Discord manager.
2. **Name the channel after your module prefix.** Nothing enforces it, and a collision
   just means two modules' messages arrive on the same subscription.
3. **Always set `type`,** and prefix it with your module name. Clients switch on `type`
   across every channel they subscribe to, so `update` is a bad type name and
   `kart_update` is a good one.
4. **Keep a `playerName` field at the top level** if the event concerns a player.
   CheeseDash's Discord mapper only reads top-level fields; that is why CheeseGuard's
   prison payload duplicates them out of its nested `entry`.

The registration side of this, and the rest of what a module gets for free, is in
[writing a module](writing-a-module.md).

---

← [REST API](rest-api.md) · [Writing a module](writing-a-module.md) →
