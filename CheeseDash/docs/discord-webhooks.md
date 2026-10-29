[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](discord-webhooks.fr.md)

# Discord webhooks

`DiscordWebhookManager.java`, 693 lines, posts rich embeds to Discord channels when
something happens on the server. You register a webhook URL, tick the events you want,
and it fires from then on. The clever part is that most events do not come from
dedicated code: they are picked up from the WebSocket bus, so a module that broadcasts
an event gets Discord notifications without knowing Discord exists.

The CRUD routes are in [REST API → Discord webhooks](rest-api.md#discord-webhooks).
This page covers the events, the message shapes and the failure modes.

> ⚠️ A Discord webhook URL is a credential: anyone holding it can post in that channel
> as your server. `GET /api/v1/webhooks/{id}` hands it out unmasked to **any**
> authenticated role, `READ_ONLY` included. See
> [the note below](#the-url-is-readable-by-every-role).

---

## Registering a webhook

In Discord: channel settings → Integrations → Webhooks → New Webhook → Copy URL. Then:

```bash
curl -X POST http://localhost:44311/api/v1/webhooks \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Staff alerts","url":"https://discord.com/api/webhooks/123…/abc…"}'
```

`name` must be 1 to 64 characters. The URL must start with
`https://discord.com/api/webhooks/` or `https://discordapp.com/api/webhooks/`; anything
else is `400 {"error":"URL must be a valid Discord webhook URL"}`. That check is a
prefix test, not a reachability test — a typo in the token half of the URL is accepted
here and only shows up as an HTTP 401 from Discord when the first event fires.

**A new webhook subscribes to nothing.** It is created `enabled` with an empty event
set, so it stays silent until you send an `events` array:

```bash
curl -X PUT http://localhost:44311/api/v1/webhooks/wh_3f8a1c \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"events":["SERVER_START","SERVER_STOP","PRISON_IMPRISONED"]}'
```

The `events` key replaces the whole set, so read-modify-write it. Unknown event names
are **dropped silently** — `EnumSet.valueOf` failures are caught and ignored — so a typo
costs you that subscription with no error. Compare what you sent with what
`GET /api/v1/webhooks/{id}` returns back, and check `eventCount`.

`POST /api/v1/webhooks/{id}/test` posts a green `✅ Test CheeseDash` embed naming the
webhook and its event count. It answers `200` either way, with `{"success":true}` or
`{"success":false,"error":"…"}` — read the body, not the status code.

Everything is stored in `config/cheesedash_webhooks.json`, written on every change on a
daemon thread named `CheeseDash-WebhookSave`:

```json
{
  "webhooks": [
    {
      "id": "wh_3f8a1c",
      "name": "Staff alerts",
      "url": "https://discord.com/api/webhooks/123…/abc…",
      "avatarUrl": "",
      "enabled": true,
      "events": ["SERVER_START", "PRISON_IMPRISONED"],
      "scoreboardFilter": [],
      "createdAt": 1772131331004,
      "eventCount": 2
    }
  ]
}
```

The URL is stored in clear — it has to be, to be usable. `eventCount` is written out too
and ignored on load, which makes it a hand-editing hazard: change `events` in the file
and `eventCount` lies until the next write. `avatarUrl`, if set, overrides the avatar
Discord shows for the message; the username is always `CheeseDash`.

### `scoreboardFilter`

Only consulted for `SCORE_LEADER_CHANGE`. When the array is non-empty, the event is
delivered only if its `scoreboardId` is in the list, which keeps a channel dedicated to
one leaderboard. An empty array means every scoreboard. A quirk worth knowing: an event
that arrives with **no** `scoreboardId` at all passes the filter, because the check skips
when the id is null.

---

## The 23 event types

`GET /api/v1/webhooks/event-types` returns this catalogue with each entry's `id`,
`label`, `description`, `color` and `category`. The labels and descriptions are in
French in the enum — they are what the dashboard shows in the webhook editor, not
anything Discord sees.

| Event | Category | Fired by |
|---|---|---|
| `SERVER_START` | Serveur | CheeseDash, on `SERVER_STARTED` |
| `SERVER_STOP` | Serveur | CheeseDash, on `SERVER_STOPPING` |
| `RESTART_SCHEDULED` | Serveur | `POST /server/schedule`, and the `schedule_created` broadcast |
| `RESTART_CANCELLED` | Serveur | `DELETE /server/schedule/active`, and the `schedule_cancelled` broadcast |
| `MAINTENANCE` | Serveur | a `MAINTENANCE` action firing |
| `SCHEDULED_TASK_FIRED` | Serveur | any action firing; also CheeseGuard's chunk auto-reset |
| `PLAYER_JOIN` | Joueurs | CheeseDash, on player join |
| `PLAYER_LEAVE` | Joueurs | CheeseDash, on player disconnect |
| `EVENT_ANNOUNCE` | Evénements | the `events` channel — **nothing broadcasts it** |
| `EVENT_START` | Evénements | the `events` channel — **nothing broadcasts it** |
| `EVENT_END` | Evénements | the `events` channel — **nothing broadcasts it** |
| `SCORE_LEADER_CHANGE` | Scores | nothing in this repository |
| `MARKET_PURCHASE` | Marché | CheeseMarket, via the `market` channel |
| `EGG_DISCOVERY` | Collectibles | EggAndCheese, via the `eggs` channel |
| `ADMIN_COMMAND` | Administration | nothing in this repository |
| `DIMENSION_CREATED` | Dimensions | the `dimensions` channel — **nothing broadcasts it** |
| `DIMENSION_DELETED` | Dimensions | the `dimensions` channel — **nothing broadcasts it** |
| `SHIFT_TRANSFORM` | CobbleShift | the `shift` channel — **nothing broadcasts it** |
| `PRISON_IMPRISONED` | Prison | CheeseGuard, via the `prison` channel |
| `PRISON_RELEASED` | Prison | CheeseGuard, via the `prison` channel |
| `LEAGUE_MASTER` | CobbleCap | CobbleCap, as a pre-built embed |
| `CHAMPION_VICTORY` | CobbleCap | CobbleCap, as a pre-built embed |
| `CUSTOM` | Autre | nothing — a slot for a mod that wants a generic message |

Six of them can be subscribed to and will never fire with the mods in this repository:
`EVENT_ANNOUNCE`, `EVENT_START`, `EVENT_END`, `DIMENSION_CREATED`, `DIMENSION_DELETED`
and `SHIFT_TRANSFORM` all depend on WebSocket channels nothing publishes — see
[WebSocket](websocket.md#channels-that-are-subscribed-to-but-never-published).
`SCORE_LEADER_CHANGE`, `ADMIN_COMMAND` and `CUSTOM` have no sender either. They are
wired end to end on the Discord side and waiting on a broadcast that does not exist yet;
subscribing to them is harmless, and silent.

`ADMIN_COMMAND` is the one whose absence surprises people: a command run from
`POST /api/v1/server/command` is written to the audit log and to the server log, but no
Discord event is sent for it.

---

## Channel to event mapping

`onBroadcast(channel, jsonMessage)` is called by `DashboardRegistry.broadcast` for every
WebSocket message, and `mapBroadcastToEvent` decides whether it becomes a Discord event:

| Channel | Message `type` | Discord event |
|---|---|---|
| `players` | `player_join` | `PLAYER_JOIN` |
| `players` | `player_leave` | `PLAYER_LEAVE` |
| `market` | `market_purchase` | `MARKET_PURCHASE` |
| `eggs` | `egg_discovery` | `EGG_DISCOVERY` |
| `schedule` | `schedule_created` | `RESTART_SCHEDULED` |
| `schedule` | `schedule_cancelled` | `RESTART_CANCELLED` |
| `schedule` | `schedule_fired` | `SCHEDULED_TASK_FIRED` |
| `prison` | `prison_added` | `PRISON_IMPRISONED` |
| `prison` | `prison_released` | `PRISON_RELEASED` |
| `events` | type containing `ANNOUNCED` | `EVENT_ANNOUNCE` |
| `events` | type containing `ACTIVE` or `COUNTDOWN` | `EVENT_START` |
| `events` | type containing `CLEANUP` or `IDLE` | `EVENT_END` |
| `dimensions` | `dim_created` / `dim_deleted` | `DIMENSION_CREATED` / `DIMENSION_DELETED` |
| `shift` | `transform` | `SHIFT_TRANSFORM` |

Anything else returns `null` and is dropped. `schedule_created` maps to
`RESTART_SCHEDULED` whatever the action type, so a scheduled `STOP` arrives in Discord
titled *Redémarrage planifié*. The three `prison_*` types include `prison_updated`,
which maps to nothing: changing a sentence's duration is not announced.

Then `extractBroadcastData` flattens the message into a string map, reading these keys
and only these, **at the top level**: `name`, `playerName`, `eventName`, `itemName`,
`eggName`, `dimName`, `species`, `message`, `scoreboardId`, `scoreboardName`, `reason`,
`duration`, `byName`, `releaseReason`, `releasedByName`. A nested `action` object is
read too, for `message` and `remainingSeconds` (formatted into `delay`). That is the
whole contract between a module and Discord: put those fields at the top of your
payload, or the embed falls back to its generic wording. It is exactly why CheeseGuard's
prison payload duplicates `playerName`, `reason`, `duration` and `byName` out of its
nested `entry`.

Also worth noting: `DashboardRegistry.broadcast` is what triggers this, and CheeseDash's
own `tps` and `players` broadcasts partly bypass it by calling
`webSocketServer.broadcast(...)` directly. The player join and leave events reach
Discord anyway, because `CheeseDashMod` calls `sendEvent` for them explicitly in the same
handler.

---

## What the message looks like

Every message is one embed in a standard envelope:

```json
{
  "username": "CheeseDash",
  "embeds": [{
    "title": "⛓ Joueur emprisonné",
    "description": "**Griefer** a été emprisonné par **admin**\n**Durée:** 1h\n**Raison:** griefing spawn",
    "color": 15680580,
    "timestamp": "2026-03-09T18:42:11.004Z",
    "footer": {"text": "CheeseDash • Arkensia"}
  }]
}
```

`avatar_url` is added when the webhook has one. The colour comes from the event type —
amber `#f5a623` for scheduling, green `#22c55e` for success, red `#ef4444` for stops and
punishments, gold `#ffd700` for leaderboard milestones, matching the dashboard palette.

**Titles and descriptions are hardcoded French**, with emoji, in `buildEmbed`. There is
no language setting for Discord output, and the footer reads `CheeseDash • Arkensia`.
Both are cosmetic and both are in `DiscordWebhookManager.java` if you want them in
another language or without the server name — the README says the same about the other
Arkensia labels.

Two extra fields are appended when the event data carries them: *Joueurs en ligne*
from `playerCount` and *TPS* from `tps`, side by side. In practice only `SERVER_START`
provides them, and its `tps` value is the literal string `20.0` rather than a measurement
— the server has just started, nothing has been measured yet.

A handful of events read their description from the payload. `RESTART_SCHEDULED` uses
`delay` and appends `message` as a quote block; `MAINTENANCE` appends `message` the same
way; `SCHEDULED_TASK_FIRED` uses `message` as its whole description, which is how
CheeseGuard's chunk auto-reset reports how many region files it deleted;
`ADMIN_COMMAND` would wrap `command` in backticks and name the user, if anything fired
it.

### Pre-built embeds

`sendRawEmbed(eventType, embed)` posts an embed built by the caller, bypassing
`buildEmbed` entirely. CobbleCap uses it for `CHAMPION_VICTORY` and `LEAGUE_MASTER`,
where it wants Pokémon sprites and a team breakdown that the template cannot express. It
reaches the method by reflection, so CobbleCap compiles and runs without CheeseDash.

If you are writing a module, prefer broadcasting on the WebSocket and letting the mapper
do the work. Reach for `sendRawEmbed` only when the embed itself is the point.

---

## Delivery, rate limiting and errors

Sending is asynchronous on a two-thread pool named `CheeseDash-Discord`, so a slow
Discord never stalls the game thread. Each POST has a 5-second connect timeout and a
5-second read timeout.

**CheeseDash rate-limits itself to one message per webhook every two seconds**, and the
implementation matters: an event that arrives inside that window is **dropped**, not
queued.

```java
Long lastSend = rateLimitTracker.get(webhook.id);
if (lastSend != null && System.currentTimeMillis() - lastSend < RATE_LIMIT_INTERVAL_MS) {
    continue;
}
```

So a webhook subscribed to `PLAYER_JOIN` on a server where ten people reconnect after a
restart reports perhaps two of them. Nothing is logged when a message is dropped. If you
want a complete record of an event stream, use the audit log or the WebSocket; Discord
here is for notifications, not for bookkeeping. The counter is per webhook, so splitting
chatty events across two webhook URLs in the same channel doubles your throughput.

Failures are logged at `WARN` and never retried:

| Outcome | Logged | Returned by the test route |
|---|---|---|
| HTTP 200 or 204 | nothing | `{"success":true}` |
| HTTP 429 | `Discord rate limited for webhook 'Staff alerts'` | `Rate limited (429)` |
| Any other code | `Discord webhook 'X' returned 401: {…}` with Discord's body | `HTTP 401: …` |
| Timeout, DNS failure, bad URL | `Discord webhook 'X' failed: …` | the exception message |

A 429 from Discord means you tripped *their* limit, which the 2-second self-limit
normally prevents; seeing it points at several CheeseDash instances sharing one webhook
URL, or another bot in the same channel. A 401 or 404 means the webhook was deleted on
the Discord side — the URL is dead and no amount of retrying will revive it.

On `SERVER_STOPPING` the manager is shut down with `shutdownNow()`, which is why the
`SERVER_STOP` event is sent **before** the shutdown sequence in `CheeseDashMod`. A
message still in flight at that point is dropped.

---

## The URL is readable by every role

`GET /api/v1/webhooks` masks the middle of the URL. `GET /api/v1/webhooks/{id}` does
not: it calls `toJson(false)` and returns the complete URL, and the route has no role
check beyond being authenticated.

> ⚠️ **A `READ_ONLY` account can read every Discord webhook URL you have registered.**
> That URL needs no token and no account: anyone with it can post arbitrary messages,
> with arbitrary embeds, into that channel, as your server. Treat handing out a
> `READ_ONLY` account as handing out every webhook URL on the dashboard. If that is not
> acceptable, do not create the account — the masking on the list route is not a control,
> only a convenience.

Rotating a leaked URL means deleting the webhook in Discord, creating a new one, and
sending the new URL with `PUT /api/v1/webhooks/{id}`. The event subscriptions survive.

---

← [Scheduler](scheduler.md) · [Audit log](audit.md) →
