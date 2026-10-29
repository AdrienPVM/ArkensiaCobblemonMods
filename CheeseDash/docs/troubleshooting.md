[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

One symptom per section, with the cause and the fix. The server log is the first place
to look for all of them: CheeseDash prefixes every line with `[CheeseDash]`, so
`grep CheeseDash logs/latest.log` gives you the whole story of a boot in about fifteen
lines.

---

## The mod does not appear to load at all

No `[CheeseDash]` line anywhere in the log.

**Cause.** Fabric never loaded the jar: it is in the wrong folder, it is the
`-sources.jar`, Fabric API is missing, or the loader is older than 0.15.11.

**Fix.** Check in order:

- The jar is in `mods/` and is named `cheesedash-<version>+1.21.1.jar`, not
  `…-sources.jar`.
- Fabric API is in `mods/` too. Without it, Fabric refuses to start and names the
  missing dependency in the crash log, so read the top of the log rather than the
  bottom.
- `GET /api/v1/server/mods` — or the Fabric startup banner — lists `cheesedash`.
- Java is 21 or newer: `java -version`. Minecraft 1.21.1 itself needs Java 21, and
  CheeseDash's `fabric.mod.json` declares `"java": ">=21"`, so Fabric refuses to start
  on anything older and names the requirement.

---

## The dashboard never starts and the log says `Address already in use`

```text
[CheeseDash] ❌ Failed to start: Address already in use
```

**Cause.** Something else already holds port 44311. On a shared host, that is often
another server instance of your own, or a panel service. It also happens after a crash,
if the old JVM has not fully released the socket yet.

**Fix.**

```bash
# Linux
ss -tlnp | grep 44311
# Windows
netstat -ano | findstr 44311
```

Either stop the other process, or move CheeseDash: set `"port": 44411` in
`config/cheesedash.json` and restart. Remember the WebSocket follows to `port + 1`, so
check **both** ports are free — 44411 and 44412 in that example.

A failure to bind the HTTP port is not fatal: the server keeps running without a
dashboard. A failure to bind the **WebSocket** port is quieter still — HTTP works, the
interface loads, and the live views simply never update.

---

## The dashboard starts but the browser shows nothing on another machine

Works at `http://localhost:44311` on the server, times out from your desktop.

**Cause.** The port is not reachable. On a rented host it is firewalled by default; on a
home server the router does not forward it.

**Fix.** Do **not** open the port. Tunnel it:

```bash
ssh -L 44311:localhost:44311 -L 44312:localhost:44312 user@your-server
```

then browse `http://localhost:44311` on your own machine. Forward both ports or the TPS
graph and the player list stay empty. The reasons to prefer this over opening a firewall
hole are in [network exposure](auth-and-roles.md#network-exposure).

If you are already tunnelling and still get nothing, confirm the server is listening on
all interfaces rather than only on a container-internal one, and check that
`[CheeseDash] ✅ Started on port 44311` is actually in the log.

---

## The page loads but is blank, or styled wrong

The login card is missing, or the dashboard renders as unstyled HTML.

**Cause.** Either the frontend is not in the jar, or the browser cannot reach the CDNs
the page pulls Tailwind, DaisyUI and Alpine from.

**Fix.**

- Open the browser console. `Refused to load …` messages point at the CSP or at a
  blocked CDN; `404` on `assets/app.js` points at the jar.
- If the assets 404, the jar was built without the frontend. `processResources` copies
  `index.html`, `preview.html` and `assets/` from the **root of the mod folder** — check
  they are there and rebuild with `./gradlew clean build`.
- The page needs outbound internet access *from the browser*, not from the server. A
  fully offline workstation will render the dashboard unstyled.
- A stale service worker or cache can serve an old `app.js` against a new API. Hard
  reload.

---

## Login returns `401 Invalid token`

**Cause.** The token does not match the master token or any account token.

**Fix.**

- Read the token out of `config/cheesedash.json` rather than from scrollback — the
  banner truncates nothing, but terminals wrap, and a wrapped token copied with the
  line break in it is a different string.
- Tokens are case-sensitive and 43 characters long. Leading and trailing whitespace is
  trimmed for you; anything in the middle is not.
- If an account token was regenerated, the old one stops working immediately and every
  session it created was revoked.
- If the config file was malformed at the last start, CheeseDash **silently generated a
  new master token and overwrote the file**, losing some or all of the accounts with it.
  Look for `Failed to read config, regenerating` followed by the
  `FIRST RUN — Dashboard token generated` banner in the log. The token you have is then
  genuinely stale; use the one now in the file. This is the single nastiest failure mode
  in the mod — see [configuration](configuration.md#the-accounts-array).

To be sure the credential itself is the problem, bypass the page:

```bash
curl -i -X POST http://localhost:44311/api/v1/auth/login \
  -H "Content-Type: application/json" -d '{"token":"<your token>"}'
```

`401` means the token is wrong. `400 {"error":"Invalid JSON"}` means your quoting is
wrong, not your token.

---

## Requests suddenly return `429 Too many requests`

**Cause.** More than 20 requests with a bad `Authorization` header arrived from your IP
within 60 seconds. The usual source is a script holding an expired session id and
retrying in a loop.

**Fix.** Stop the loop and wait out the window — it clears 60 seconds after the first
failure. Then log in again and use the new session id.

Note what this does *not* cover: failed logins on `/auth/login` are not counted, so a
`429` always means a bad Bearer header somewhere, never a wrong password.

---

## Everything returns `401` after working for a day

**Cause.** The session expired. Sessions last 24 hours from their **last use**, and they
are also dropped entirely when the server restarts.

**Fix.** Log in again. If you ticked "remember" on the login form, the frontend does it
for you when you reload the page, with the raw token it kept in `localStorage`;
otherwise it shows the login form. A script has to call `/auth/login` again. If a script needs to run unattended for longer than a
day, have it re-login on any `401` rather than caching a session id forever.

---

## A route returns `503 Server not ready`

**Cause.** The core answers `503` only while its Minecraft server reference is null. In
the `SERVER_STARTED` handler that reference is set (`CheeseDashMod.java:180`) before the
HTTP server is started (line 195), so in practice a core route does not answer `503`:
while the game is still starting, the port is simply closed and you get a refused
connection.

**Fix.** Wait and retry. Use `GET /api/v1/ping` as your readiness probe once the port
answers, with the token in the `Authorization` header like every other route; it touches
no game state.

If the port never opens after the world has loaded, something stopped the startup event
early. Look for an exception from another mod after
`[CheeseDash] Server started — notifying …` and before
`[CheeseDash] ✅ Started on port`: a module whose `onServerStart` throws aborts the
handler before the HTTP server is started.

A module's own `503` — `{"error":"CheeseKart not ready"}` and the like — means that
module did not receive its server reference, which is the same cause one level down.

---

## A module is installed but missing from the sidebar

`GET /api/v1/modules` does not list it.

**Cause.** One of four things, in order of likelihood:

1. The mod does not declare the `cheesedash` entrypoint in its `fabric.mod.json`.
2. Its prefix collides with another module's. The log says so:
   `Duplicate module prefix 'x' — skipping …`.
3. Its prefix collides with a **core** route — `ping`, `status`, `modules`, `auth`,
   `admin`, `dashboard`, `server`, `webhooks`. The module registers fine and appears in
   `/modules`, but every path the core handles itself never reaches it (and nothing at
   all under `admin/` does), and nothing warns you.
4. The mod declares CheeseDash under `suggests` and something went wrong resolving the
   entrypoint class, so Fabric skipped it silently.

**Fix.** Check the log for the two lines that prove registration:

```text
[CheeseDash] Module registered: CheeseKart (prefix: /api/v1/kart)
[CheeseDash] Module loaded: CheeseKart (kart)
```

No lines at all means the entrypoint is missing or the mod did not load — check the
other mod's own troubleshooting page. Lines present but the page still absent means the
frontend has no view for that prefix, which is a frontend problem rather than a
registration one.

---

## A change to `config/cheesedash.json` has no effect

**Cause.** The file is read once, at mod initialization, and rewritten from memory
whenever an account or a preference changes. Editing it on a running server is both
ignored and doomed.

**Fix.** Stop the server, edit, start. Or make the change through the API:
`PUT /dashboard/admin-player`, `POST /admin/accounts`, and so on. `port` and `enabled`
have no API and always need a restart.

⚠️ Validate your JSON before restarting. A syntax error, or an unknown `role` value,
makes CheeseDash generate a new master token and rewrite the file, losing accounts on
the way (all of them for a syntax error). Keep a backup.

---

## A request is refused with `403 Insufficient permissions`

```json
{"error":"Insufficient permissions — requires OPERATOR"}
```

**Cause.** The account's role is below what the route needs. The message names the
required role.

**Fix.** Either use an account with that role, or raise this one:

```bash
curl -X PUT http://localhost:44311/api/v1/admin/accounts/moderator \
  -H "Authorization: Bearer <a SUPER_ADMIN session>" \
  -H "Content-Type: application/json" -d '{"role":"SUPER_ADMIN"}'
```

⚠️ The change does **not** apply to sessions that already exist, because a session
captures its role at creation. Revoke the user's session with
`DELETE /admin/sessions/{id}` and have them log in again, or wait 24 hours.

The full matrix of what each role can reach is in
[authentication and roles](auth-and-roles.md#what-each-role-can-do).

---

## Live TPS, the player list and the log stream never update

REST works, everything real-time is frozen.

**Cause.** The WebSocket is not connected. Three reasons, in order:

1. Port 44312 is not reachable — the common case when you tunnelled or proxied only
   44311.
2. The client sends something other than the master token. The WebSocket accepts the
   master token and nothing else — and the bundled dashboard always sends its session
   id, even when you logged in with the master token, so its own socket is refused for
   every account. See
   [the note on this](auth-and-roles.md#the-websocket-is-master-token-only).
3. The WebSocket server failed to bind. HTTP keeps working, so nothing obvious breaks —
   check for `[CheeseDash] WebSocket started on port 44312` in the log, and for its
   absence.

**Fix.** Forward or proxy `port + 1` as well. A proxy must also pass the `Upgrade` and
`Connection` headers through, or the handshake fails with a `200` instead of a `101`.
For the bundled pages, cause 2 has no configuration fix: they fall back to polling
`GET /api/v1/status` every two seconds. Live data needs a client of your own that sends
the master token.

A connection that authenticates but shows nothing is a different problem: the client
subscribed to no channels. The browser console shows the `subscribe` message the page
sent.

---

## A scheduled restart stopped the server instead of restarting it

**Cause.** `ServerScheduler` saves the worlds, spawns a fresh JVM with the same
arguments, then stops the current server. To spawn it, it has to find the server jar:
first by looking for `-jar` in the current process arguments, then by scanning the
working directory for `fabric-server-launch*.jar` or `server*.jar`. On a host that
launches through a wrapper script, a shaded launcher or an unusually named jar, neither
lookup succeeds and it throws `Cannot find server jar for restart`. The exception is
only logged (`Failed to spawn restart process: …`), and the stop goes ahead anyway.

**Fix.** Test a single `RESTART` on any new host before trusting a recurring schedule
with it. If it fails, let your panel's own auto-restart handle the relaunch and use
`STOP` instead of `RESTART`, which is honest about what it does. Look for
`[CheeseDash] Restart process spawned: …` in the log — if that line is there, the
relaunch was attempted.

---

## Discord notifications are not arriving

**Cause.** Most often the webhook has an empty `events` set. A webhook created through
`POST /webhooks` starts enabled and subscribed to **nothing**.

**Fix.**

1. `POST /api/v1/webhooks/{id}/test` first. It reports delivery, not configuration:
   `{"success":true}` proves the URL works.
2. If the test succeeds but real events never arrive, `PUT` an `events` array onto the
   webhook with the ids you want. `GET /webhooks/event-types` lists all 23.
3. Check `enabled` is `true`.
4. Each webhook has its own outbound rate limit: any event that comes less than two
   seconds after the previous send to the same webhook, whatever its type, is dropped
   rather than queued.

Detail in [Discord webhooks](discord-webhooks.md).

---

## The audit log is missing old entries

**Cause.** It is capped at 10 000 entries and trimmed from the oldest end, both in memory
and on disk.

**Fix.** Rotate `config/cheesedash_audit.json` yourself — copy it aside on a schedule —
if you need a longer history. There is no setting for the cap.

---

## Compiling fails with `package com.sun.net.httpserver is not visible`

**Cause.** The JDK's HTTP server lives in the `jdk.httpserver` module. On **JDK 21 it is
exported**, so a straightforward Gradle build needs no flag at all — CheeseDash's own
`build.gradle` sets nothing beyond `options.release = 21`, and compiles. The error means
the compiler is not in that situation:

- a `release`, `sourceCompatibility` or toolchain setting below 11, where
  `com.sun.net.httpserver` was not an exported package;
- an IDE or build configured to compile against a **limited module graph**, where
  `jdk.httpserver` is not in `--add-modules`;
- or code reaching for `sun.net.httpserver` — the internal implementation package,
  spelled without the `com.` — which genuinely is not exported and never will be.

**Fix.** First make sure the compiler is targeting 21:

```groovy
tasks.withType(JavaCompile).configureEach {
    it.options.release = 21
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
```

That is what CheeseDash does, and it is enough. If your build genuinely restricts the
module graph, add the module rather than forcing an export:

```groovy
tasks.withType(JavaCompile).configureEach {
    it.options.compilerArgs += ['--add-modules', 'jdk.httpserver']
}
```

An `--add-exports` of an internal package is the sledgehammer and is only correct for the
`sun.net.httpserver` case:

```groovy
it.options.compilerArgs += ['--add-exports', 'jdk.httpserver/sun.net.httpserver=ALL-UNNAMED']
```

If you reach for that, check first that you did not simply mistype the import.

---

## Compiling a module fails on missing `com.cheesedash` classes

**Cause.** CheeseDash was not built, so the `fileTree` glob in your module's
`build.gradle` matched nothing and the dependency resolved to an empty set.

**Fix.**

```bash
cd CheeseDash && ./gradlew build
cd ../YourMod  && ./gradlew build
```

Or run `tools/build-all.sh` from the repository root, which walks the mods in dependency
order. Confirm `CheeseDash/build/libs/cheesedash-*.jar` exists and is not only the
`-sources` one.

---

## Reporting a bug

What makes a report actionable, in order of usefulness:

1. **`logs/latest.log`**, or at minimum every line matching `CheeseDash` from startup to
   the failure. The boot sequence tells us the version, the module list, which ports
   bound, and whether the config loaded cleanly.
2. **The exact request**: method, path, body, and the response status and body. A `curl`
   command that reproduces it is better than a description of a button.
3. **The browser console and the Network tab** for anything that looks like a frontend
   problem. A `Refused to load` line or a red request is usually the whole answer.
4. **Which mods are installed**, from `GET /api/v1/server/mods`. Many symptoms turn out
   to belong to a module rather than to CheeseDash.
5. **Your `config/cheesedash.json` — with the `token` field and every account token
   removed.** ⚠️ Those are passwords. Replace them with `"REDACTED"` before pasting
   anything.

File it at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues). For anything
that looks like a vulnerability, read [SECURITY.md](../../SECURITY.md) first and do not open
a public issue.

---

← [Architecture](architecture.md) · [CheeseDash](../README.md) →
