# CheeseDash

> Self-hosted web dashboard for administering a Fabric server — and the hub the other
> mods in this repository plug into.

CheeseDash runs an HTTP server and a WebSocket server inside your Minecraft server,
and serves a single-page admin interface. Other mods register **modules** with it
through a Fabric custom entrypoint; each module gets its own API namespace and its own
page in the sidebar. The sidebar is built from whatever is actually installed, so any
combination works — CheeseDash alone, with one module, or with all twenty.

The design rule behind it: **admins use the dashboard, players use in-game GUIs.**
There are deliberately no admin GUIs in-game.

**Minecraft 1.21.1 · Fabric · Java 21 · ~4 000 lines**

---

## Requirements

- Fabric Loader ≥ 0.15.11
- Fabric API
- Java 21

No other mod is required. CheeseDash is useful on its own and does not depend on
Cobblemon or anything else.

## Installation

Drop the jar in `mods/` and start the server. On first boot it writes
`config/cheesedash.json` with a freshly generated master token.

Then open `http://<server>:44311` and log in with that token.

| Port | Protocol | Purpose |
|------|----------|---------|
| 44311 | HTTP | dashboard and REST API |
| 44312 | WebSocket | live TPS, player events, logs, audit stream |

> ⚠️ **Do not expose these ports to the internet.** There is no TLS, so your session
> token would travel in clear text. Restrict them with a firewall, reach them over an
> SSH tunnel or VPN, or put a TLS-terminating reverse proxy in front. See
> [SECURITY.md](../SECURITY.md).

## Authentication

- The **master token** in `config/cheesedash.json` grants the `SUPER_ADMIN` role,
  which includes arbitrary server command execution. Treat it like a root password.
- Additional accounts live in `config.accounts[]` with one of three roles:
  `READ_ONLY`, `OPERATOR`, `SUPER_ADMIN`. Give each account the lowest role that does
  its job.
- Logging in exchanges a token for a session valid 24 hours with a sliding expiry.
- Token comparison is constant-time, and failed authentication is rate-limited to
  20 attempts per 60 seconds per IP.

Administrative actions are written to `config/cheesedash_audit.json` with the IP,
timestamp and payload.

## Writing a module

Declare the entrypoint in your `fabric.mod.json`:

```json
"entrypoints": {
  "cheesedash": ["com.example.dashboard.MyDashboardModule"]
}
```

Implement `DashboardModule`, and CheeseDash will discover it at startup and route
`/api/v1/<your-prefix>/*` to it.

Two rules matter when you do:

- **Any mutation of Minecraft state must go through
  `WebDashboard.executeOnServerThread(server, () -> { ... })`.** HTTP handlers run on
  their own threads. Reading immutable or thread-safe objects directly is fine.
  Getting this wrong produces crashes that only reproduce under load.
- **Validate parameters first.** Reject ids containing `..`, `/`, `\`, `%` or spaces
  with a 400.

Helpers you will want: `WebDashboard.requireWrite(exchange)`,
`requireAdmin(exchange)`, `getSession(exchange)`, `WebDashboard.respond(exchange,
code, json)`.

## Core API

Everything lives under `/api/v1/`. `POST /auth/login` is the only route that does not
require `Authorization: Bearer <token>`.

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/ping` | health check |
| GET | `/status` | TPS, player count, heap, uptime |
| GET | `/modules` | which modules are loaded (drives the sidebar) |
| GET | `/server/stats` · `/server/logs?n=` · `/server/mods` | server introspection |
| POST | `/server/command` | run a Minecraft command (audited, executed on the server thread) |
| POST | `/auth/login` · `/auth/logout` · GET `/auth/session` | sessions |
| GET/POST/DELETE | `/admin/accounts` | account management |
| GET | `/admin/audit` · `/admin/sessions` | audit log, active sessions |

WebSocket clients authenticate with `{"type":"auth","token":"..."}` and then
subscribe: `{"type":"subscribe","channels":["tps","players","logs","audit"]}`.

## Frontend

Plain Alpine.js, Tailwind and DaisyUI from a CDN. No React, no npm, no build step.
The files at the root of this folder (`index.html`, `preview.html`, `assets/`) are the
source; `processResources` copies them into `src/main/resources/dashboard/` at build
time. **Edit the root files, not the copies.**

`preview.html` runs the whole interface against mock data with the token `demo`, which
is handy for working on the UI without a server.

## Arkensia specifics

The default Discord webhook messages and a few labels mention Arkensia, the server
this was built for. They are cosmetic — change them in `DiscordWebhookManager.java`
and the language files under `src/main/resources/assets/cheesedash/lang/`.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators. You may run this on a monetised server; you may not sell the software.
