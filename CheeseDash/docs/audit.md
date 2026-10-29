[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](audit.fr.md)

# Audit log

`AuditLogger.java`, 179 lines, records who did what, when, and from which IP. It exists
because a dashboard with a `SUPER_ADMIN` role can run arbitrary server commands, and a
server with several admins needs a way to answer "who deleted that zone". The log is
deliberately readable by every role, including `READ_ONLY`: an audit trail that only the
person being audited can read is not an audit trail.

---

## An entry

```json
{
  "timestamp": "2026-03-09T18:42:11.004Z",
  "username": "admin",
  "role": "SUPER_ADMIN",
  "action": "server.command",
  "detail": "say hello",
  "ip": "127.0.0.1"
}
```

| Field | Notes |
|---|---|
| `timestamp` | `Instant.now().toString()` — ISO-8601, UTC, millisecond precision |
| `username` | the session's username, or `unknown` for a failed login |
| `role` | the role **captured in the session**, not the account's current role. Demote someone and their running session keeps logging the old role |
| `action` | a dotted category, listed below |
| `detail` | free-form human-readable text, built at the call site |
| `ip` | the session's IP, resolved once at login through `WebDashboard.getClientIp` |

There is no entry id and no sequence number. Entries are ordered by position in the
file; two actions in the same millisecond are distinguishable only by that order.

---

## What is recorded

Every one of these comes from a call in `DashboardApi`.

| Action | `detail` | Fired by |
|---|---|---|
| `auth.login` | `Logged in as admin (SUPER_ADMIN)` | `POST /auth/login`, on success |
| `auth.login_failed` | `Failed login attempt` | `POST /auth/login`, on an unknown token |
| `auth.logout` | `Logged out` | `POST /auth/logout` |
| `admin.account_create` | `Created account 'moderator' with role OPERATOR` | `POST /admin/accounts` |
| `admin.account_update` | `Changed role of 'moderator' to READ_ONLY` | `PUT /admin/accounts/{username}` |
| `admin.account_regen_token` | `Regenerated token for 'moderator'` | `POST /admin/accounts/{username}/regenerate` |
| `admin.account_delete` | `Deleted account 'moderator'` | `DELETE /admin/accounts/{username}` |
| `admin.token_regenerate` | `Master token regenerated` | `POST /dashboard/token/regenerate` |
| `server.command` | the full command string | `POST /server/command` |
| `schedule.create` | `RESTART in 600s: Weekly maintenance` | `POST /server/schedule` |
| `schedule.cancel` | `Cancelled active schedule` | `DELETE /server/schedule/active` |
| `schedule.recurring_add` | `RESTART at 05:00` | `POST /server/schedule/recurring` |
| `schedule.recurring_update` | `Updated rec_9b21ef` | `PUT /server/schedule/recurring/{id}` |
| `schedule.recurring_delete` | `Deleted rec_9b21ef` | `DELETE /server/schedule/recurring/{id}` |
| `webhook.create` | `Created webhook 'Staff alerts'` | `POST /webhooks` |
| `webhook.update` | `Updated webhook wh_3f8a1c` | `PUT /webhooks/{id}` |
| `webhook.delete` | `Deleted webhook wh_3f8a1c` | `DELETE /webhooks/{id}` |
| `<prefix>.<method>` | `POST /api/v1/kart/start` | **every** non-`GET` request to a module route |

That last row is the one that carries the volume. The dispatcher audits any module
request that is not a `GET`, automatically, with the lower-cased HTTP method as the
action suffix and the method plus full path as the detail. So editing a CheeseGuard zone
produces `cheeseguard.put` with `PUT /api/v1/cheeseguard/zones/spawn`, and a module
author gets a baseline audit trail for free. A module that wants something more
descriptive writes its own entry **in addition** — the automatic one is not suppressed,
so you will see both.

Note the ordering for `server.command`: the audit entry is written **before** the command
runs. A command that throws is still in the log. That is the right way round for an
audit trail, but it means an entry is not proof the command succeeded.

### What is not recorded

Honest list, because a gap you do not know about is worse than a gap:

- **Every `GET`.** Reading the player list, exporting a scoreboard image, listing
  accounts, and `GET /webhooks/{id}` — which discloses a Discord credential — leave no
  trace at all.
- **`DELETE /admin/sessions/{id}`.** Revoking somebody's session is unaudited.
- **`PUT /dashboard/admin-player`** and all four `/dashboard/zone-folders` routes.
  They are cosmetic, but they are writes that leave nothing behind.
- **`POST /webhooks/{id}/test`.** Create, update and delete are audited; the test post
  is not.
- **In-game actions.** The quick menu logs to the server log
  (`[CheeseDash] QuickMenu: RoiCheese executing '/tp …'`) and not to the audit log. So do
  console commands and commands typed by an operator in chat. The audit log covers the
  dashboard, not the server.
- **The old value of anything.** `admin.account_update` records the new role, never the
  previous one. For a module write, the detail is the path — not the body — so you know
  that a zone was modified and never what changed. Reconstructing a before-and-after from
  this log is not possible by design.

---

## Storage

One file: `config/cheesedash_audit.json`, a flat JSON array of entry objects, pretty
printed, oldest first. It is created on the first audited action, which on a fresh
install is your first login.

The whole log is held in memory in a `Collections.synchronizedList`, capped at
**10 000 entries**. Past that, `log()` removes the oldest one per new entry, so the cap
is a sliding window and the file is rewritten at the trimmed size. At roughly 200 bytes
per entry the file settles around 2 MB.

**There is no rotation.** Entries beyond 10 000 are discarded, not archived. On a busy
server the window is shorter than it looks: every module write produces an entry, so an
afternoon of zone editing can be a few thousand of them. If you need audit history for
longer than the window, copy the file aside on a cron — it is rewritten in full on every
entry, so a copy is a consistent snapshot of that moment.

The write path is worth knowing, because it is unusual:

```java
private void saveToDiskAsync() {
    Thread writer = new Thread(() -> { … Files.writeString(auditFile, GSON.toJson(arr)); },
        "CheeseDash-AuditWrite");
    writer.setDaemon(true);
    writer.start();
}
```

**Each entry spawns a thread and rewrites the entire file.** Two consequences. First,
cost: ten actions in a second means ten threads each serialising up to 10 000 entries.
It is invisible at human speed and measurable under a script hammering a module route.
Second, and more serious: the writes are not serialised against each other. The snapshot
of the list is taken under the lock, but two writers can reach `Files.writeString` in
any order, so a slow write started earlier can land after a faster one and leave the
file one entry behind. The next entry fixes it. A `writeString` interrupted by a JVM
kill can also leave a truncated file, since there is no write-to-temp-and-rename.

That last case degrades gracefully. On startup `loadFromDisk` parses the file, and on
failure logs
`[CheeseDash] Failed to load audit log, starting fresh: …` and continues with an empty
list — **the corrupt file is then overwritten by the next audited action, and the
history is gone.** If a log matters to you and the server refuses to load it, copy the
file aside before letting the server write again. A successful load logs
`[CheeseDash] Loaded 412 audit entries`, and a file holding more than 10 000 entries is
trimmed at load time.

---

## Reading it back

### From the dashboard

The *Administration → Audit* view in the sidebar, which any role can open. It paginates
over the REST route below.

### From the API

```bash
curl -s "http://localhost:44311/api/v1/admin/audit?n=50" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "entries": [ { "timestamp": "…", "username": "admin", "…": "…" } ],
  "total": 412,
  "offset": 0,
  "limit": 100
}
```

Newest first. `n` defaults to 100 and is clamped to 1–500; `offset` counts back from the
most recent entry, so `offset=100&n=100` is the second page. `total` is the number of
entries currently stored, which is also the number of entries the window holds — not the
number of actions ever taken.

To page through everything, walk `offset` in steps of your `n` until the `entries` array
comes back short. Both parameters are clamped rather than rejected, so a nonsensical
value gives you a valid page instead of a `400`.

### Over the WebSocket

Subscribe to the `audit` channel and you get one message per entry, live, as it is
recorded — pushed before the disk write. The payload carries every field **except `ip`**:

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

The omission looks deliberate — the live feed is for watching activity, the stored log is
for investigating it — but nothing in the code says so. Note also that the WebSocket
only accepts the master token, so the live feed is in practice `SUPER_ADMIN` only, while
the REST route is open to everyone. Details in
[WebSocket](websocket.md#only-the-master-token-is-accepted).

### From the file

`jq` is the quickest tool for a question the API cannot express. Every command a given
admin ran, for instance:

```bash
jq -r '.[] | select(.action == "server.command" and .username == "admin")
       | "\(.timestamp)  \(.detail)"' config/cheesedash_audit.json
```

Or every failed login, with its source address:

```bash
jq -r '.[] | select(.action == "auth.login_failed") | "\(.timestamp)  \(.ip)"' \
  config/cheesedash_audit.json
```

A run of `auth.login_failed` from one IP is what a brute-force attempt looks like from
here, and ⚠️ **nothing slows it down**: the login route is dispatched before the rate
limiter runs, so an attacker gets as many attempts per minute as your server can answer
([the detail](auth-and-roles.md#rate-limiting)). Every one of them is audited, which
means the audit log is the only place a sustained attack is visible at all — and it will
show a continuous run, not the burst-then-gap pattern a working limiter would produce.

If you see one, the response is at the network layer: block the address at the firewall,
and rotate the master token in case it was reached.

---

## Writing entries from a module

```java
AuditLogger audit = CheeseDashMod.INSTANCE.getAuditLogger();
SessionManager.Session session = WebDashboard.getSession(exchange);
if (audit != null && session != null) {
    audit.log(session, "kart.preset_delete", "Deleted preset '" + id + "'");
}
```

The two-argument overload takes username, role and IP from the session, which is what
you want on an HTTP thread. The five-argument form —
`log(username, role, action, detail, ip)` — is for the case with no session, as the login
route uses for a failed attempt.

Both null checks matter. `getAuditLogger()` is null before `onInitialize` completes, and
`getSession` returns null on a public unauthenticated context. Follow the convention of
the core: `<prefix>.<verb>` for the action, and a detail naming the object by its id, so
the log stays greppable.

---

← [Discord webhooks](discord-webhooks.md) · [Quick menu](quick-menu.md) →
