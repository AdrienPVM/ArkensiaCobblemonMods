[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](auth-and-roles.fr.md)

# Authentication and roles

Everything in this page comes out of three classes: `WebDashboard` (the middleware),
`SessionManager` (the sessions) and `AdminAccount` (the roles). Read it before you give
anyone a token, and read the last section before you open a port.

---

## The login flow

```text
POST /api/v1/auth/login   {"token": "<master or account token>"}
   │
   ├─ token not recognised ──→ 401 {"error":"Invalid token"}  + audit "auth.login_failed"
   │
   └─ recognised ──→ session created ──→ 200 {sessionId, username, role, expiresIn}
                                              │
                      every later request:  Authorization: Bearer <sessionId>
```

`POST /api/v1/auth/login` is the only route under `/api/` that the middleware lets
through without a credential. Everything else needs the header:

```http
Authorization: Bearer ses_9mJ…
```

The response fields are exactly these:

| Field | Meaning |
|---|---|
| `sessionId` | `ses_` plus 43 URL-safe Base64 characters. This is the credential from now on |
| `username` | `admin` for the master token, otherwise the account's username |
| `role` | `READ_ONLY`, `OPERATOR` or `SUPER_ADMIN` |
| `expiresIn` | Milliseconds until expiry — `86400000` on a fresh session |

### A raw token also works as a Bearer credential

The middleware tries the Bearer value as a session id first; if that fails, it tries it
as a master or account token, and on success **creates a session on the fly** and
proceeds. So this works:

```bash
curl -H "Authorization: Bearer EXAMPLE-TOKEN-do-not-use-mWqZ4rKxv2NbSvQ1pX" \
  http://localhost:44311/api/v1/status
```

Convenient for a script, and the reason you can poke the API without logging in first.
It has a real cost, though: **each such request creates a brand new session**, because
the raw token never matches an existing session id. A script polling once a second
grows the session map by one entry per second and writes a
`Session created for 'admin'` line to the log each time. Those sessions expire 24 hours
after their last use, so the map does drain, but a long-running poller will push
`GET /admin/sessions` into the thousands. For anything repeated, log in once and reuse
the session id.

---

## Sessions

| Property | Value |
|---|---|
| Lifetime | 24 hours |
| Expiry | **Sliding** — every successful validation resets the clock |
| Storage | In memory, `ConcurrentHashMap`. Never written to disk |
| Survives a restart | No, by design. Logging in again is cheap |
| Cleanup | A daemon thread sweeps expired sessions every 5 minutes; an expired session is also dropped the moment it is used |

Three routes act on them:

- `GET /api/v1/auth/session` — the current session, plus `activeSessions`, the
  server-wide count.
- `POST /api/v1/auth/logout` — revokes the current session. Any role.
- `GET /api/v1/admin/sessions` and `DELETE /api/v1/admin/sessions/{id}` — list and
  revoke anyone's, `SUPER_ADMIN` only.

Sessions are also revoked in bulk when an account's token is regenerated or the account
is deleted. Regenerating the **master** token does not revoke anything, which is worth
knowing if you are regenerating it because you think it leaked: restart the server to
be sure.

---

## What each role can do

Three roles, compared by ordinal: `READ_ONLY` (0) < `OPERATOR` (1) < `SUPER_ADMIN` (2).
A check is "at least this role", so `SUPER_ADMIN` passes every gate.

| | READ_ONLY | OPERATOR | SUPER_ADMIN |
|---|---|---|---|
| Read any core route (`/status`, `/modules`, `/server/stats`, `/server/logs`, `/server/mods`) | yes | yes | yes |
| Read any module route (`GET /api/v1/<prefix>/…`) | yes | yes | yes |
| Read the audit log | yes | yes | yes |
| Read the webhook list and the scheduler | yes | yes | yes |
| Write to any module route (`POST`/`PUT`/`DELETE`) | **no** | yes | yes |
| Set `adminPlayerName`, manage zone folders | **no** | yes | yes |
| `POST /server/command` | **no** | **no** | yes |
| Create, update, delete accounts; regenerate their tokens | **no** | **no** | yes |
| List and revoke other people's sessions | **no** | **no** | yes |
| Create, update, delete, test webhooks | **no** | **no** | yes |
| Schedule or cancel a restart, shutdown or maintenance | **no** | **no** | yes |
| Regenerate the master token | **no** | **no** | yes |

Underneath, three helpers do all the work, and they are the ones a module author calls:

| Helper | Minimum role | On failure |
|---|---|---|
| `WebDashboard.requireWrite(exchange)` | `OPERATOR` | `403 {"error":"Insufficient permissions — requires OPERATOR"}` |
| `WebDashboard.requireAdmin(exchange)` | `SUPER_ADMIN` | `403 {"error":"Insufficient permissions — requires SUPER_ADMIN"}` |
| `WebDashboard.requireRole(exchange, role)` | whatever you pass | same shape, naming that role |

Each returns a boolean and has already written the response when it returns `false`, so
the calling pattern is `if (!requireWrite(exchange)) return;`.

**Module writes are gated centrally.** Before dispatching a non-`GET` request to a
module, `DashboardApi` calls `requireWrite` itself. A module therefore cannot
accidentally expose a write to a `READ_ONLY` account — but it still should call
`requireAdmin` on its own dangerous routes, because `OPERATOR` passes the central gate.

### Two gaps worth knowing

- **`GET /webhooks/{id}` returns the Discord URL in full, to any authenticated role.**
  The list route masks the middle of the URL; the single-webhook route does not. A
  `READ_ONLY` account can therefore read a secret that lets it post to your Discord
  channel. Treat `READ_ONLY` as "trusted observer", not "harmless".
- **`READ_ONLY` can read the server log.** `GET /server/logs` has no role check, and a
  Minecraft log routinely contains player IPs and command arguments.

---

## Token handling

- **Comparison is constant-time.** `WebDashboard.constantTimeEquals` XORs every byte
  and accumulates the difference rather than returning early on a mismatch. It does
  return early when the two lengths differ, so the length of a token is not protected —
  which is fine, since every token this mod generates is 43 characters.
- **Tokens are generated with `SecureRandom`**, 32 bytes, Base64 URL-safe without
  padding. The master token and account tokens use the same generator, and session ids
  the same again with a `ses_` prefix.
- **An account token is shown exactly once**, in the response to
  `POST /admin/accounts` or `POST /admin/accounts/{username}/regenerate`. Every other
  representation of an account omits it. It is still stored in cleartext in
  `config/cheesedash.json`; the file is the secret, not a hash of it.

---

## Rate limiting

The middleware keeps a per-IP counter: **20 failed authentications per 60-second
window**, after which that IP gets `429 {"error":"Too many requests"}` until the window
rolls over. The window is not sliding — it restarts from the first failure after a gap.

> ⚠️ **It does not cover the login route.** `/auth/login` is matched and dispatched
> *before* the rate-limit check, and a failed login never increments the counter. What
> is actually rate-limited is sending a bad `Authorization` header to some other route.
> A brute-force attempt against `POST /auth/login` is therefore unthrottled; failures
> are audited as `auth.login_failed` with the IP, so you will see it in the audit log,
> but nothing stops it. The token is 256 bits of entropy, which is the real defence —
> and it is why none of this belongs on a public port.

---

## Security headers

Every response from `/api/` and from the static file handler carries:

| Header | Value |
|---|---|
| `X-Frame-Options` | `DENY` |
| `X-Content-Type-Options` | `nosniff` |
| `X-XSS-Protection` | `1; mode=block` |
| `Referrer-Policy` | `no-referrer` |
| `Access-Control-Allow-Origin` | `""` (empty) |
| `Content-Security-Policy` | `default-src 'self'`, with script and style allowances for the Tailwind and jsDelivr CDNs, fonts from `fonts.gstatic.com`, images from `data:`, `blob:`, `mc-heads.net`, `minecraft.wiki` and `img.pokemondb.net`, and `connect-src 'self' ws: wss:` for the WebSocket |

The CSP includes `'unsafe-inline'` and `'unsafe-eval'` because Alpine.js evaluates
expressions out of HTML attributes. That is a real weakening of the policy, accepted in
exchange for having no build step. The practical consequence: XSS protection in the
dashboard rests on the frontend escaping data properly, not on the CSP.

A **public module context** — the mechanism CheeseStore uses for its storefront — gets a
different and stricter header set, including HSTS, `Permissions-Policy` and
`frame-ancestors 'none'`, plus Stripe-specific allowances. Those pages are unauthenticated
by design; see [writing a module](writing-a-module.md#public-unauthenticated-contexts).

### CORS

Closed. `Access-Control-Allow-Origin` is set to the empty string on every API response,
which no browser accepts as a match, so a page on another origin cannot read the API
even with a stolen token in JavaScript. The `OPTIONS` preflight answers `204` with
`Allow-Methods` and `Allow-Headers` but still no usable origin, so the real request is
never sent.

Nothing stops a non-browser client, which is the point: `curl` and your scripts work
fine. CORS protects the admin's browser, not the server.

### What is served without any authentication

- `GET /` and every static file under it: `index.html`, `preview.html`, `assets/*`. The
  login page has to be reachable before you have a credential. No server data is in
  those files.
- Any path that does not resolve to a file falls back to `index.html`, so the
  single-page router can handle it.
- Every route under a module's **public prefix**, if it declares one.

---

## Network exposure

> ⚠️ **Port 44311 must not be reachable from the internet.** This is not a hardening
> suggestion; it is the single most important line on this page.

The concrete reasons, in order of how badly they end:

1. **There is no TLS.** The HTTP server is a plain `HttpServer` on a TCP socket. Your
   master token travels in clear text on the login request, and your session id travels
   in clear text on every request after that. Anyone between you and the server — a
   coffee-shop network, a compromised router, your hosting provider's switch — reads
   both and can replay them.
2. **A `SUPER_ADMIN` session is a remote shell on the game.**
   `POST /server/command` runs any Minecraft command with full server permissions. That
   includes `op`, `ban`, `whitelist off`, and whatever administrative commands your
   other mods register.
3. **Brute-forcing the login route is not rate-limited**, as described above. The
   entropy of the token is what saves you, and only as long as the token is a generated
   one — if you ever replace it by hand with something memorable, you have removed the
   only defence.
4. **`READ_ONLY` is not read-only in the sense that matters.** It can read your server
   log and your unmasked Discord webhook URLs.
5. **The WebSocket port accepts only the master token** (see below), so whatever you do
   for 44311, 44312 carries the highest-value secret you have.

Pick one of these, in descending order of preference:

| Approach | What to do |
|---|---|
| **SSH tunnel** | `ssh -L 44311:localhost:44311 -L 44312:localhost:44312 user@server`, then browse `http://localhost:44311`. Nothing is exposed, and the traffic is encrypted by SSH. Forward both ports or the live views stay empty |
| **VPN** | Put the server and your workstation on a WireGuard or Tailscale network and bind the dashboard to that interface only |
| **Reverse proxy with TLS** | nginx or Caddy terminating HTTPS on 443 and proxying to `127.0.0.1:44311`, with the WebSocket upgraded through to 44312. Add HTTP basic auth in front for a second factor |
| **Firewall allow-list** | `iptables`/`ufw` allowing only your own IP. Workable, but your token still crosses the internet unencrypted |

And whatever you choose: do not put the dashboard behind a shared host's "open port"
feature and call it done.

### The WebSocket is master-token only

`WebSocketServer.onMessage` compares the `auth` message's token against
`config.getToken()` and nothing else. Session ids do not work, and neither do account
tokens. A connection that fails or does not authenticate within 5 seconds is closed
with code `4001`.

So an `OPERATOR` or `READ_ONLY` user whose browser wants live TPS has to be handed the
master token, which defeats the role system entirely. The honest guidance: additional
accounts work fine for the REST API and the pages built on it, and live-update views are
effectively a `SUPER_ADMIN` feature. Details of the protocol are in
[WebSocket](websocket.md).

---

← [Configuration](configuration.md) · [REST API](rest-api.md) →
