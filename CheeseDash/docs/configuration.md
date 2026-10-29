[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

CheeseDash's own settings live in one file:

```text
config/cheesedash.json
```

It is created on first start, with a generated master token and sane defaults. It is
read **once**, during mod initialization, and rewritten whenever something changes an
account, the admin player name, the zone folders or the master token.

> ⚠️ **This file contains a secret.** The `token` field is the master password to your
> server: a `SUPER_ADMIN` session, and therefore arbitrary command execution. Every
> entry in `accounts[]` contains another one. Never commit this file, never paste it in
> a bug report, never put it in a modpack, and keep its filesystem permissions tight.
> If you suspect it leaked, `POST /api/v1/dashboard/token/regenerate` or delete the file
> and restart.

> ⚠️ Hand edits to a **running** server are ignored, and worse, they are overwritten:
> the next account change rewrites the whole file from memory. Stop the server before
> editing by hand, or make the change through the API.

---

## Every key

| Key | Type | Default | What it does |
|---|---|---|---|
| `port` | int | `44311` | TCP port the HTTP server binds. The WebSocket server binds `port + 1`, always, with no separate setting |
| `token` | string | 32 random bytes, URL-safe Base64 | The master token. Resolves to the username `admin` with role `SUPER_ADMIN` |
| `enabled` | boolean | `true` | When `false`, the HTTP server is not started at all. The WebSocket server still starts |
| `adminPlayerName` | string | `""` | The in-game name the dashboard treats as "you". Drives player-targeting actions and the position readout. Omitted from the file while empty |
| `accounts` | array | `[]` | Additional admin accounts. One object per account; see [below](#the-accounts-array) |
| `zoneFolders` | array | `[]` | Purely visual grouping of CheeseGuard zones in the sidebar. No effect on protection |
| `_note` | string | written by the mod | A reminder string the mod writes on every save and ignores on load. Editing or deleting it changes nothing |

### `port`

Changing it needs a restart; the server binds at startup and never rebinds. Two things
to keep in mind:

- The WebSocket port follows automatically. Setting `port` to `8080` moves the
  WebSocket to `8081`, and the frontend learns the new value from `GET /status`, which
  reports `wsPort`.
- Both ports must be free. If the HTTP bind fails the log says so and the dashboard is
  simply absent; if the WebSocket bind fails, HTTP keeps working and live updates do
  not. See
  [troubleshooting](troubleshooting.md#the-dashboard-never-starts-and-the-log-says-address-already-in-use).

### `token`

Generated with `SecureRandom`, 32 bytes, Base64 URL-safe without padding — 43
characters. It is printed in a boxed banner the first time it is generated, and never
printed again.

Three ways it gets replaced:

- `POST /api/v1/dashboard/token/regenerate` with `{"confirm":true}` in the body,
  `SUPER_ADMIN` only. The new token comes back in the response. **Existing sessions keep
  working** — regenerating the master token does not revoke sessions, so an attacker
  with a live session id is not locked out by this alone. Restart the server if you need
  to be certain.
- Setting it to `""` or removing it from the file: the next start notices, generates a
  new one, and logs `TOKEN MISSING — new master token generated`.
- Deleting the whole file, which also deletes every account.

### `enabled`

The one global off switch. With `enabled: false`:

```text
[CheeseDash] Disabled in config, skipping.
```

No HTTP server, no static files, no API — so no module route works either, even though
the modules themselves loaded normally. The WebSocket server is started regardless,
which is arguably a bug; it is harmless, because without the HTTP side nothing tells a
client to connect, but it does mean port `port + 1` stays occupied.

### `adminPlayerName`

Free text, matched against an online player's name. The dashboard uses it for actions
that need a target player without asking every time, and
`GET /api/v1/dashboard/admin-player/position` reports that player's coordinates and
world, or `{"online":false}` when the name is empty or the player is offline. No
validation: a misspelled name produces `online: false` rather than an error.

Writable at runtime through `PUT /api/v1/dashboard/admin-player` (`OPERATOR` or above),
which saves the file immediately.

---

## The `accounts` array

Each entry is an object with four fields:

| Field | Type | Required | What it does |
|---|---|---|---|
| `username` | string | yes | Unique handle. The API enforces 1–32 characters of `[a-zA-Z0-9_-]` and refuses `admin`, which is reserved for the master token. A hand-written file is **not** checked against those rules |
| `token` | string | yes | This account's secret. Generated the same way as the master token |
| `role` | string | no, defaults to `READ_ONLY` | One of `READ_ONLY`, `OPERATOR`, `SUPER_ADMIN` |
| `createdAt` | long | no | Epoch milliseconds, for display only |

What each role may do is the subject of
[authentication and roles](auth-and-roles.md#what-each-role-can-do). The short version:
`READ_ONLY` can read, `OPERATOR` can change game state, `SUPER_ADMIN` can also run
commands and manage accounts.

Prefer creating accounts through `POST /api/v1/admin/accounts` rather than by hand. The
route validates the username, refuses duplicates, generates the token for you, and
returns it exactly once.

> ⚠️ **One malformed account loses everything.** Account parsing happens inside the same
> `try` block as the rest of the load, and an unknown `role` string throws. The whole
> read is then abandoned with `Failed to read config, regenerating`, and the mod falls
> through to its first-run path: a **new master token is generated and the file is
> overwritten**, taking every account with it. The same applies to a trailing comma or
> any other JSON syntax error. Keep a backup before editing by hand, and if you must,
> validate the JSON first.

---

## The `zoneFolders` array

A CheeseDash-only convenience for servers with many CheeseGuard zones. Each folder is:

```json
{ "id": "f_18f3c2a1b40", "name": "Spawn area", "zoneIds": [], "order": 0 }
```

`id` is generated as `f_` plus the creation timestamp in hex. Nothing validates
`zoneIds` against zones that actually exist, and nothing in CheeseGuard reads this —
deleting a folder never deletes a zone. Managed through the
`/api/v1/dashboard/zone-folders` routes documented in
[the REST API](rest-api.md#zone-folders).

---

## The other files CheeseDash writes

Three siblings in the same directory, each owned by a different subsystem and each
created only when something first writes to it. They are not part of
`cheesedash.json` and are not reloaded from disk at runtime.

| File | Owner | Contents | Documented in |
|---|---|---|---|
| `config/cheesedash_audit.json` | `AuditLogger` | A flat JSON array of audit entries, newest last, capped at 10 000 | [audit log](audit.md) |
| `config/cheesedash_schedules.json` | `ServerScheduler` | `{"recurring":[…]}` — the recurring restart definitions. One-shot actions are never persisted | [scheduler](scheduler.md) |
| `config/cheesedash_webhooks.json` | `DiscordWebhookManager` | `{"webhooks":[…]}` — Discord targets and their event subscriptions | [Discord webhooks](discord-webhooks.md) |

Discord configuration is **not** in `cheesedash.json`. A webhook entry looks like this,
and the `url` field is a bearer secret in its own right — anyone holding it can post to
your channel:

```json
{
  "webhooks": [
    {
      "id": "wh_3f8a1c",
      "name": "Staff alerts",
      "url": "https://discord.com/api/webhooks/123456789/AbCdEf…",
      "avatarUrl": "",
      "enabled": true,
      "events": ["SERVER_START", "SERVER_STOP", "ADMIN_COMMAND"],
      "scoreboardFilter": [],
      "createdAt": 1772131331004
    }
  ]
}
```

An unknown string in `events` is skipped silently, and an entry that fails to parse is
skipped with a warning rather than losing the file — the opposite of how
`cheesedash.json` behaves. The API masks the middle of the URL on list responses and
returns it in full on a single-webhook `GET`.

On a **client** that has the mod installed, one more file appears:

```text
config/cheesedash_shortcuts.json
```

It holds the quick menu's shortcut list and never reaches the server. See
[quick menu](quick-menu.md).

---

## A complete annotated file

The comments are **not** valid JSON. Strip them before use; they are here to be read,
not parsed.

```json
{
  "port": 44311,                       // HTTP; WebSocket is implicitly 44312
  "token": "EXAMPLE-TOKEN-do-not-use-mWqZ4rKxv2NbSvQ1pX",
                                       // master token → user "admin", SUPER_ADMIN.
                                       // SECRET. Never commit this file.
  "enabled": true,                     // false disables the HTTP server entirely
  "adminPlayerName": "RoiCheese",      // the in-game player the dashboard targets

  "zoneFolders": [                     // cosmetic sidebar grouping, CheeseGuard zones
    {
      "id": "f_18f3c2a1b40",           // generated, do not hand-pick
      "name": "Spawn area",            // free text
      "zoneIds": ["spawn", "market"],  // not validated against real zones
      "order": 0                       // display order
    }
  ],

  "accounts": [
    {
      "username": "moderator",         // 1-32 chars, [a-zA-Z0-9_-], never "admin"
      "token": "Jq7x…-WkR",            // SECRET, specific to this account
      "role": "OPERATOR",              // READ_ONLY | OPERATOR | SUPER_ADMIN
      "createdAt": 1772131331004       // display only
    },
    {
      "username": "builder",
      "token": "Zp2v…-9tQ",
      "role": "READ_ONLY",             // can see everything, change nothing
      "createdAt": 1772131400912
    }
  ],

  "_note": "Token is your dashboard password. Delete this file to regenerate."
                                       // written by the mod, ignored on load
}
```

---

← [Getting started](getting-started.md) · [Authentication and roles](auth-and-roles.md) →
