[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](players.fr.md)

# Players

The players subsystem is the dashboard's window onto who is connected and a small set of
moderation actions: kick, teleport, change game mode, send a message. It owns no state and
no config file — `PlayersApi` (`dashboard/PlayersApi.java`, ~300 lines) reads the live
`PlayerManager` on each request and writes nothing to disk.

Everything here is reachable only through the dashboard. There is no `/cg player` command.

## The online list

`GET /api/v1/cheeseguard/players` returns one object per connected player, built straight
from the entity:

```json
[
  {
    "uuid": "f84c6a79-0a4e-45e0-879b-cd49ebd4c4e2",
    "name": "Steve",
    "world": "minecraft:overworld",
    "x": 142,
    "y": 71,
    "z": -308,
    "health": 18.5,
    "maxHealth": 20.0,
    "gameMode": "survival",
    "ping": 42,
    "online": true
  }
]
```

Coordinates are truncated to integers; health is rounded to one decimal. `ping` is the
latency the network handler reports, which is a rolling average and not a fresh measurement.
`gameMode` is the lowercase vanilla name — `survival`, `creative`, `adventure`,
`spectator` — whereas the game-mode *write* route expects uppercase. Do not round-trip the
value you read into the value you send without upper-casing it.

The list is rebuilt per request, so it is always current but costs a walk of the player list.
The dashboard polls it; there is no players channel on CheeseGuard's own WebSocket (CheeseDash
publishes its own `player_join` / `player_leave` / `player_list` messages on the core
`players` channel, which is a different mechanism).

## The offline list

`GET /api/v1/cheeseguard/players/offline?n=20` returns recently-seen players who are **not**
currently connected.

The source is vanilla's own `usercache.json` in the server directory, read and parsed on each
request. The file is ordered oldest-first, so the handler walks it backwards and stops after
`n` entries, skipping anyone whose name matches an online player case-insensitively. `n` is
clamped to 1–100 and defaults to 20; an unparseable `n` falls back to the default rather
than failing.

```json
[ { "name": "Alex", "uuid": "...", "online": false } ]
```

Three limits follow from the data source:

- A player who has never joined is not in the cache and will never appear.
- The cache is vanilla's, with its own eviction rules, so "recently seen" is approximate and
  entries do eventually disappear.
- A missing, empty or malformed `usercache.json` is swallowed silently and the route returns
  an empty array. An empty list therefore means either "nobody" or "the file could not be
  read", and the two cases are indistinguishable from the response.

There is no pagination and no search: `n` only caps the head of the list.

## Player detail

`GET /api/v1/cheeseguard/players/:name` takes a **name**, not a UUID, URL-decoded before
lookup.

For an online player it returns the summary plus:

| Field | Source |
|---|---|
| `food` | hunger level, 0–20 |
| `xpLevel` | experience level |
| `fly` | whether flight is allowed |
| `advDone` / `advTotal` | advancement progress |

The advancement count walks every loaded advancement, skipping ids containing `recipes/`, and
asks the player's tracker whether each one is done. On a heavily modded server that is
thousands of advancements per request, on the HTTP thread. It is the most expensive read in
this API by a wide margin — do not poll this route in a loop.

For an offline player the response is minimal and still `200`:

```json
{ "name": "Alex", "online": false, "uuid": "..." }
```

`uuid` is present only if the user cache knows the name. A name nobody has ever used returns
`{"name":"Whoever","online":false}` — this route has **no 404**. Check the `online` field and
the presence of `uuid` rather than relying on the status code.

## Actions

All four action routes require the player to be **online** and answer
`404 {"error":"Player not online"}` otherwise. All four run their mutation through
`WebDashboard.executeOnServerThread`, so a `200` means "the action was scheduled and the task
completed without throwing", and any failure inside it surfaces as a `500` carrying the
exception message.

### Kick

`POST /players/:name/kick`, body `{"message": "..."}`. The message defaults to
`Kicked by admin` and is sent with `Text.literal`, so `§` colour codes work and nothing is
translated — the player sees exactly the string you typed, whatever their language.

```bash
curl -X POST http://localhost:44311/api/v1/cheeseguard/players/Steve/kick \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Server restarting in 10s"}'
```

There is no ban here, and no kick history. A kicked player can reconnect immediately. For
something durable, use [Prison](prison.md) or vanilla's own ban list via
`POST /api/v1/server/command`.

### Teleport

`POST /players/:name/tp`. The body shape decides which of three behaviours you get:

| Body | Behaviour |
|---|---|
| `{"x":..,"y":..,"z":..}` | teleport within the player's current world |
| `{"dimension":"..","x":..,"y":..,"z":..}` | teleport to those coordinates in that dimension |
| `{"dimension":".."}` | teleport to that dimension's spawn point, through `DimensionManager` |

With coordinates and no dimension, any axis you omit keeps the player's current value — so
`{"y": 200}` lifts them straight up. With a dimension **and** coordinates, omitted axes
default to `0`, `64`, `0` instead, because there is no sensible "current" value in a world
the player is not in yet. That asymmetry catches people out: the same partial body means
different things depending on whether `dimension` is present.

Yaw and pitch are always preserved. An unknown dimension id gives
`500 {"error":"Dimension not found: <id>"}`.

The dimension-spawn form delegates to `DimensionManager.tpToDimension()`, which knows about
CheeseGuard's custom dimensions and their configured spawn points — see
[Dimensions](dimensions.md). If no `DimensionManager` is available the call is skipped
silently and the route still answers `200 {"teleported":true}`.

### Game mode

`POST /players/:name/gamemode`, body `{"mode": "SURVIVAL"}`. Accepted values, uppercase:
`SURVIVAL`, `CREATIVE`, `ADVENTURE`, `SPECTATOR`. The input is upper-cased before matching,
so `survival` works; anything else gives `500 {"error":"Invalid gamemode: <value>"}`. A
missing `mode` gives `400 {"error":"mode is required"}`.

This is a direct `changeGameMode` call. It does not coordinate with anything else that
manages game modes — an event that put a player in spectator mode, for instance, keeps its own
record of the mode it intends to restore. Changing a participant's game mode from here while
an event is running will be overwritten at event cleanup, or will overwrite what cleanup
expected. Prefer the event's own participant roles ([Events](events.md)).

### Message

`POST /players/:name/message`, body `{"text": "...", "type": "chat"}`. Four types:

| `type` | Delivery |
|---|---|
| `chat` (default, and any unrecognised value) | a chat line |
| `actionbar` | above the hotbar |
| `title` | the large title text |
| `subtitle` | the title's second line |

A missing `text` gives `400 {"error":"text is required"}`.

`chat` and `actionbar` use `Text.literal`, so `§` codes work. `title` and `subtitle` are
implemented differently: they build a vanilla `/title` command string with the text embedded
in a JSON component and execute it from the server command source. Double quotes in the text
are escaped, but nothing else is — a backslash, a newline, or a lone `{` in the text produces
malformed JSON, and the command fails. The failure is a command error, not an exception, so
the route still answers `200 {"sent":true}` and nothing appears on screen.

Keep titles to plain text. If you need markup, send `chat` with `§` codes instead.

## Authorisation

`PlayersApi` contains **no** calls to `WebDashboard.requireWrite` or `requireAdmin`. Nothing
in this file checks a role.

What protects the four action routes is CheeseDash's own module dispatcher, which guards
every non-`GET` request to any module with `if (!method.equals("GET") && !requireWrite(...))`.
Because all four actions are `POST`, they are effectively `OPERATOR`-or-above, and the two
read routes accept any role. That is the right outcome here — but it is the core's doing, not
this module's, and the distinction matters the moment someone adds a mutating `GET`. The full
picture is in [Dashboard API](dashboard.md#what-the-core-actually-enforces).

Every non-`GET` module request is also written to CheeseDash's audit log, as
`cheeseguard.post` with the full path, so kicks and teleports are traceable to a session.

---

← [Timeline](timeline.md) · [Chunks](chunks.md) →
