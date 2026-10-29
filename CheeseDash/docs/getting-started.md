[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this page you will have a running dashboard on a fresh server, logged in
as `admin`, a second account with a weaker role for someone else, and a Minecraft
command executed from the browser and visible in the audit trail. Twenty minutes,
assuming the server starts.

If you only want to look at the interface, skip to [step 0](#step-0-optional-look-at-it-first)
and stop there — it needs no server at all.

---

## Step 0, optional: look at it first

Open `CheeseDash/preview.html` in a browser, straight from disk. No build, no server.
Type `demo` in the token field and press **Access dashboard**.

Every view works, backed by mock data: invented zones, invented players, invented
orders. Nothing you click reaches a server, and nothing is saved. It exists so you can
judge the mod before installing it, and so frontend work does not need a Minecraft
server running.

---

## Step 1: get the jar into the server

Build it:

```bash
cd CheeseDash && ./gradlew build     # gradlew.bat on Windows
```

Copy `build/libs/cheesedash-1.0.0+1.21.1.jar` into your server's `mods/`, alongside
Fabric API. Full prerequisites and the common build failures are in
[installation](installation.md).

## Step 2: start the server and capture the token

Start the server and watch the log. Among the startup noise, a boxed banner appears
exactly once:

```text
[CheeseDash] ╔═══════════════════════════════════════════════════╗
[CheeseDash] ║       FIRST RUN — Dashboard token generated       ║
[CheeseDash] ║  Save this token, you'll need it to log in:       ║
[CheeseDash] ║   EXAMPLE-TOKEN-do-not-use-mWqZ4rKxv2NbSvQ1pX     ║
[CheeseDash] ║  Port: 44311                                      ║
[CheeseDash] ╚═══════════════════════════════════════════════════╝
```

Copy that string. It is the **master token** and it grants `SUPER_ADMIN`, which includes
running arbitrary server commands — treat it exactly as you would a root password. If
you miss the banner, the same value is in `config/cheesedash.json` under `token`.

A little later, two lines confirm the servers are up:

```text
[CheeseDash] ✅ Started on port 44311 — http://localhost:44311/
[CheeseDash] WebSocket started on port 44312
```

If the second group never appears, stop here and read
[troubleshooting](troubleshooting.md) — nothing below will work.

## Step 3: reach the dashboard

On the machine running the server:

```text
http://localhost:44311
```

From your own machine, do **not** open port 44311 on the firewall yet. Tunnel it over
SSH instead, which takes one command and gives you TLS for free:

```bash
ssh -L 44311:localhost:44311 -L 44312:localhost:44312 user@your-server
```

Then open `http://localhost:44311` locally. Forward 44312 as well or the live TPS graph
and the player list will never update — that data arrives over the WebSocket, not over
HTTP. Why this matters rather than just opening the ports:
[network exposure](auth-and-roles.md#network-exposure).

You should see a dark login card with a single field, `ACCESS TOKEN`, and a footer
reading `Port 44311 · TLS recommended in production`.

## Step 4: log in

Paste the master token and press **Access dashboard**.

Behind that button, the page sends:

```bash
curl -X POST http://localhost:44311/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"token":"EXAMPLE-TOKEN-do-not-use-mWqZ4rKxv2NbSvQ1pX"}'
```

```json
{
  "sessionId": "ses_9mJ...",
  "username": "admin",
  "role": "SUPER_ADMIN",
  "expiresIn": 86400000
}
```

From here on, every request carries `Authorization: Bearer ses_9mJ...` instead of the
token itself. The session lasts 24 hours and the clock restarts on every request, so an
admin who keeps the tab open is never logged out.

You land on **Overview**: TPS, player count, heap usage, uptime, and a sidebar whose
contents depend entirely on which modules are installed. With CheeseDash alone you get
*General* and *Administration* and nothing else — that is correct, not a bug.

If the token is refused, see
[troubleshooting](troubleshooting.md#login-returns-401-invalid-token).

## Step 5: run a command from the browser

Go to **Server** in the sidebar, find the command box, and run something harmless:

```mcfunction
say CheeseDash is up
```

The message appears in game and in the server console. Two things happened that are
worth understanding:

- The HTTP handler did not touch the game directly. It wrapped the call in
  `WebDashboard.executeOnServerThread(...)`, which hands the work to the server tick
  thread and waits up to five seconds for it. Executing a command from an HTTP thread
  would be a race against the whole game loop.
- The action was audited before it ran, with your username, your role, your IP and the
  exact command string.

Only `SUPER_ADMIN` can use this route. An `OPERATOR` gets `403` — see
[the role table](auth-and-roles.md#what-each-role-can-do).

## Step 6: check the audit trail

Still in the dashboard, open the audit view, or ask the API directly:

```bash
curl -H "Authorization: Bearer ses_9mJ..." \
  "http://localhost:44311/api/v1/admin/audit?n=5"
```

```json
{
  "entries": [
    {
      "timestamp": "2026-03-09T18:42:11.004Z",
      "username": "admin",
      "role": "SUPER_ADMIN",
      "action": "server.command",
      "detail": "say CheeseDash is up",
      "ip": "127.0.0.1"
    },
    {
      "timestamp": "2026-03-09T18:40:02.551Z",
      "username": "admin",
      "role": "SUPER_ADMIN",
      "action": "auth.login",
      "detail": "Logged in as admin (SUPER_ADMIN)",
      "ip": "127.0.0.1"
    }
  ],
  "total": 2,
  "offset": 0,
  "limit": 5
}
```

Most recent first. The file behind it is `config/cheesedash_audit.json`, which was
created by your login in step 4. Details in [audit log](audit.md).

## Step 7: give someone else an account

Never hand out the master token. Create an account with the lowest role that does the
job instead. For a moderator who should change zones and kick players but not run
commands, that is `OPERATOR`:

```bash
curl -X POST http://localhost:44311/api/v1/admin/accounts \
  -H "Authorization: Bearer ses_9mJ..." \
  -H "Content-Type: application/json" \
  -d '{"username":"moderator","role":"OPERATOR"}'
```

```json
{
  "username": "moderator",
  "role": "OPERATOR",
  "createdAt": 1772131331004,
  "token": "Jq7x...-WkR"
}
```

⚠️ **That `token` field is returned exactly once.** Every later `GET` of the account
omits it. Copy it now and send it to its owner over something private. If it is lost,
`POST /admin/accounts/moderator/regenerate` issues a new one and kills the old one's
sessions.

Usernames are validated: 1 to 32 characters, `[a-zA-Z0-9_-]` only, and `admin` is
reserved for the master token. The account is written straight into
`config/cheesedash.json` under `accounts[]`, so it survives a restart.

Your moderator logs in the same way at step 4, with their own token. They will see the
same pages; every mutating request they make that needs `SUPER_ADMIN` answers `403` with
`{"error":"Insufficient permissions — requires SUPER_ADMIN"}`.

## Step 8: schedule a restart, then cancel it

A last one to see the scheduler and the WebSocket working together:

```bash
curl -X POST http://localhost:44311/api/v1/server/schedule \
  -H "Authorization: Bearer ses_9mJ..." \
  -H "Content-Type: application/json" \
  -d '{"type":"RESTART","delaySeconds":600,"message":"Weekly maintenance"}'
```

A countdown starts, players are warned in game at decreasing intervals, and the
dashboard shows the pending action live. Cancel it before it fires:

```bash
curl -X DELETE http://localhost:44311/api/v1/server/schedule/active \
  -H "Authorization: Bearer ses_9mJ..."
```

```json
{"cancelled":true}
```

The full behaviour, including recurring schedules and what `RESTART` actually does to
the process, is in [scheduler](scheduler.md).

---

## Where to go next

- Installing CheeseGuard, CheeseMarket or any other module mod adds whole sections to
  the sidebar with no configuration on CheeseDash's side. Each of those mods documents
  its own pages.
- Writing your own module is the subject of [writing a module](writing-a-module.md),
  which is the page to read if you are here as a developer rather than an admin.
- Before you expose any of this beyond `localhost`, read
  [authentication and roles](auth-and-roles.md).

---

← [Installation](installation.md) · [Configuration](configuration.md) →
