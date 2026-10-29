# CheeseDash

> 🇫🇷 [Lire en français](README.fr.md)

> Self-hosted web dashboard for administering a Fabric server — and the hub the other
> mods in this repository plug into.

CheeseDash starts an HTTP server and a WebSocket server inside your Minecraft server
process and serves a single-page admin interface from them. Other mods register
**modules** with it through a Fabric custom entrypoint; each module gets its own API
namespace under `/api/v1/<prefix>/` and, for the modules the bundled frontend knows, its
own section in the sidebar. A section only appears when its module is actually
installed, so any combination works — CheeseDash alone, with one module, or with every
module in this repository.

An admin logs in with a token, lands on an overview page with the TPS and the player
count, and from there edits zones, runs a server command, schedules a restart or reads
the audit trail. A `READ_ONLY` account sees the same pages, minus the *Administration*
section, and cannot change anything.

The design rule behind all of it: **admins use the dashboard, players use in-game
GUIs.** CheeseDash registers **no commands at all** — there is nothing to type in chat,
and there are deliberately no admin GUIs in game. The one client-side piece is an
optional quick menu bound to a key, which exists to fire a handful of shortcut commands
without opening a browser.

**Minecraft 1.21.1 · Fabric · Java 21 · 16 Java files, ~4 650 lines** (plus roughly
36 000 lines of HTML, JavaScript and CSS for the interface)

---

## Requirements

| | Version | Why |
|---|---|---|
| Minecraft | **1.21.1** | declared as `~1.21.1` |
| Fabric Loader | ≥ 0.15.11 | |
| [Fabric API](https://modrinth.com/mod/fabric-api) | any 1.21.1 build | networking and lifecycle events |
| Java | 21 or newer | the JDK HTTP server, and `release = 21` |

No other mod is required. CheeseDash is useful on its own and depends on neither
Cobblemon nor anything else in this repository. The WebSocket library
(`org.java-websocket:Java-WebSocket:1.5.7`) is bundled inside the jar.

## Install

```bash
cd CheeseDash && ./gradlew build     # gradlew.bat on Windows
```

1. Drop `build/libs/cheesedash-1.0.0+1.21.1.jar` into `mods/`, next to Fabric API.
2. Start the server. It writes `config/cheesedash.json` with a freshly generated
   master token and prints that token in a boxed banner in the log.
3. Open `http://<server>:44311` and log in with the token.

| Port | Protocol | Purpose |
|------|----------|---------|
| 44311 | HTTP | dashboard and REST API |
| 44312 | WebSocket | live TPS, player events, restart countdown, audit stream — master token only, see [WebSocket](docs/websocket.md) |

The WebSocket port is always the HTTP port plus one. It has no setting of its own.

> ⚠️ **Do not expose these ports to the internet.** There is no TLS, so the token and
> every session id travel in clear text, and the `SUPER_ADMIN` role can run arbitrary
> server commands. Restrict the ports with a firewall, reach them over an SSH tunnel or
> a VPN, or put a TLS-terminating reverse proxy in front. The reasoning is spelled out
> in [authentication and roles](docs/auth-and-roles.md#network-exposure), and the
> repository policy is in [SECURITY.md](../SECURITY.md).

## Try the interface without a server

`preview.html` is an early standalone mock of the dashboard, in French. Open it straight
from disk — no build, no Minecraft; the browser only needs the CDNs — and it opens
directly on the overview, the login form being skipped. Only that overview has mock
data; the other entries show a `Phase 2 — implémentation en cours` placeholder. It
gives a feel for the look, not for the real views, which need a running server. Details
in [web UI](docs/web-ui.md#previewhtml--an-early-standalone-mock).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Exact prerequisites, build order, what the first launch creates, the log lines that prove it loaded, clean uninstall |
| [Getting started](docs/getting-started.md) | A walkthrough from a blank server to a logged-in dashboard, a second account, and a command run from the browser |
| [Configuration](docs/configuration.md) | Every key in `config/cheesedash.json`, and the other files the mod writes |
| [Authentication and roles](docs/auth-and-roles.md) | The login flow, 24-hour sliding sessions, what each of the three roles can do, rate limiting, security headers, network exposure |
| [REST API](docs/rest-api.md) | Every core route: method, path, parameters, body, response, status codes, required role — and how module routes are dispatched |
| [WebSocket](docs/websocket.md) | The handshake, the channels, every message type and its payload |
| [Writing a module](docs/writing-a-module.md) | How another mod plugs in: the entrypoint, the `DashboardModule` interface, the helpers, the threading rule, and a complete compilable example |
| [Web UI](docs/web-ui.md) | How the frontend is built and where to edit it |
| [Scheduler](docs/scheduler.md) | One-shot and recurring restarts, shutdowns and maintenance windows |
| [Discord webhooks](docs/discord-webhooks.md) | Routing server and module events to Discord channels |
| [Audit log](docs/audit.md) | What is recorded, where it is stored, how to read it back |
| [Quick menu](docs/quick-menu.md) | The optional client-side shortcut screen |
| [Architecture](docs/architecture.md) | The 16 classes, who owns what, the thread model, persistence, and the known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Port already in use, token refused, module missing, 503, expired session, build errors |

## Commands

None. CheeseDash registers no Brigadier command, no alias and no in-game admin screen.
Administration happens in the browser, and a server command you want to run from there
goes through `POST /api/v1/server/command`.

The client half of the mod registers one keybind, **Open CheeseDash Quick Menu**,
unbound by default and usable only by operators (permission level 2). See
[quick menu](docs/quick-menu.md).

## Core API at a glance

Everything lives under `/api/v1/`. `POST /auth/login` is the only route that does not
require `Authorization: Bearer <token>`.

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/ping` | health check, touches no game state |
| GET | `/status` | TPS, player count, MOTD, version, module count |
| GET | `/modules` | which modules are loaded — this is what builds the sidebar |
| GET | `/server/stats` · `/server/logs?n=` · `/server/mods` | server introspection |
| POST | `/server/command` | run a Minecraft command, audited, on the server thread |
| POST | `/auth/login` · `/auth/logout` · GET `/auth/session` | sessions |
| GET/POST/PUT/DELETE | `/admin/accounts` | account management, `SUPER_ADMIN` only |
| GET | `/admin/audit` · `/admin/sessions` | audit log, active sessions |
| GET/POST/DELETE | `/server/schedule` | restarts and maintenance windows |
| GET/POST/PUT/DELETE | `/webhooks` | Discord webhook routing |

Full reference in [REST API](docs/rest-api.md).

## Arkensia specifics

A few labels and the default Discord message templates mention Arkensia, the server
this was built for. They are cosmetic. Change them in `DiscordWebhookManager.java` and
in the language files under `src/main/resources/assets/cheesedash/lang/`.

The `/dashboard/zone-folders` routes exist to group CheeseGuard zones visually in the
sidebar. They are stored in CheeseDash's own config and have no effect on CheeseGuard's
behaviour; without CheeseGuard installed they are harmless dead weight.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators. You may run this on a monetised server; you may not sell the software.

## Support

- **Bugs and feature requests** → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- **Questions and discussion** → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
- **Security issues** → [SECURITY.md](../SECURITY.md). Please do not open a public issue
  for a vulnerability.
