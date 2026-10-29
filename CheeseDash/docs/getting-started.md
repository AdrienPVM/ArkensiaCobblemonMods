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

Open `CheeseDash/preview.html` in a browser, straight from disk. No build, no server
(the browser still needs internet access for the Tailwind and Alpine CDNs). It opens
directly on the dashboard: its `init()` sets `auth = true`, so the login form — which
would accept `demo` or any token longer than four characters — is skipped.

Keep your expectations low. `preview.html` is an early, standalone mock in French, not
the real interface: only the overview has mock data (TPS, player count, counters, a
list of recent activity), and the seven other sidebar entries — Zones, Groupes, Chunks,
Dimensions, Événements, Joueurs, Serveur — show a `Phase 2 — implémentation en cours`
placeholder. Nothing you click reaches a server. To see the real views you need steps 1
to 4.

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
[CheeseDash] ║     EXAMPLE-TOKEN-do-not-use-mWqZ4rKxv2NbSvQ1pX     ║
[CheeseDash] ║  Port: 44311                                         ║
[CheeseDash] ╚═══════════════════════════════════════════════════╝
```

The token and port lines really are wider than the box: the code pads them to a fixed
width that does not match the border. Copy the token string. It is the **master token** and it grants `SUPER_ADMIN`, which includes
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
SSH instead, which takes one command and encrypts the traffic for free:

```bash
ssh -L 44311:localhost:44311 -L 44312:localhost:44312 user@your-server
```

Then open `http://localhost:44311` locally. Forward 44312 as well: it is the WebSocket
port. With the bundled pages it makes no visible difference today — their socket is
refused whatever you do ([why](websocket.md#only-the-master-token-is-accepted)), and the
TPS and player count in the header come from polling `GET /api/v1/status` every two
seconds — but any live client needs it. Why a tunnel rather than just opening the ports:
[network exposure](auth-and-roles.md#network-exposure).

You should see a dark login card with a single token field, a "remember" checkbox and a
footer. **The page opens in French**: the language comes from `localStorage` (`cg-lang`),
defaults to `fr`, and the login card has no language switch. So the field reads
`TOKEN D'ACCÈS`, the checkbox `Mémoriser sur cet appareil` and the footer
`Port 44311 · TLS recommandé en production` (that footer text is fixed, whatever your
port). Once logged in, the FR/EN pills in the header switch to English — `ACCESS TOKEN`,
`Remember on this device`, `Access dashboard` — and the choice sticks for next time.

## Step 4: log in

Paste the master token and press **Accéder au dashboard** (**Access dashboard** once the
interface is in English).

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

You land on the **Overview** (*Vue d'ensemble* in French): TPS and player count in the
header, then stat cards, the MOTD and version, the installed modules and a few quick
actions, next to a sidebar whose contents depend entirely on which modules are
installed. With CheeseDash alone you get *General* and *Administration* and nothing
else — that is correct, not a bug. The cards that belong to modules show `—`.

Two things you will notice on that first visit:

- Half a second after login a dialog asks for your in-game name (*Admin player*),
  because `adminPlayerName` is still empty. Type your Minecraft name, or close it; the
  name is saved with `PUT /api/v1/dashboard/admin-player`.
- The *Uptime* figure is wrong, typically tens of thousands of days. The overview reads
  `uptime` from `GET /api/v1/status`, which holds the current epoch time, not an uptime
  ([details](rest-api.md#get-status)).

If the token is refused, see
[troubleshooting](troubleshooting.md#login-returns-401-invalid-token).

## Step 5: run a command from the browser

With CheeseDash alone there is no **Server** entry in the sidebar: it belongs to the
CheeseGuard section, which only appears when CheeseGuard is installed. The view itself
is core, though. Open it with the **Server console** quick action on the overview (or go
to `#server`), find the command box, and run something harmless:

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

Your moderator logs in the same way at step 4, with their own token. They see the same
pages minus the *Administration* section, which the sidebar shows only to
`SUPER_ADMIN`; every mutating request they make that needs `SUPER_ADMIN` answers `403`
with `{"error":"Insufficient permissions — requires SUPER_ADMIN"}`.

## Step 8: schedule a restart, then cancel it

A last one, to see the scheduler at work:

```bash
curl -X POST http://localhost:44311/api/v1/server/schedule \
  -H "Authorization: Bearer ses_9mJ..." \
  -H "Content-Type: application/json" \
  -d '{"type":"RESTART","delaySeconds":600,"message":"Weekly maintenance"}'
```

A countdown starts and players are warned in game at decreasing intervals. The
**Server** view shows the pending action with a countdown that the browser computes from
`scheduledAt`, read from `GET /api/v1/server/schedule` (no WebSocket push reaches the
bundled page). Cancel it before it fires:

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
