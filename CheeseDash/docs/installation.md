[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

CheeseDash declares `"environment": "*"`, so Fabric loads it on both sides — but the
two sides do completely different things. The server half is the dashboard: the HTTP
server, the WebSocket server, the module registry, the scheduler, the webhooks. The
client half is one keybind and one screen, and nothing else. **Installing it only on
the server is a perfectly normal setup.**

---

## What you need

### Server

| | Version | Why |
|---|---|---|
| Minecraft | **1.21.1** exactly | declared as `~1.21.1` |
| Fabric Loader | ≥ 0.15.11 | declared in `fabric.mod.json` |
| [Fabric API](https://modrinth.com/mod/fabric-api) | any 1.21.1 build | lifecycle events, server tick events, networking |
| Java | **21 or newer** | the mod compiles with `release = 21` and uses the JDK's `com.sun.net.httpserver` |

Nothing else. CheeseDash has no `suggests` and no soft dependency: it is the thing other
mods depend on, not the other way round.

### Client, optional

| | Required? | Without it |
|---|---|---|
| CheeseDash | no | nothing breaks. The dashboard is a web page; any browser reaches it. You lose the in-game quick menu, which is the only client feature |

A player who connects to a CheeseDash server without having the mod installed sees no
difference and is told nothing. There is no client-side enforcement and no handshake.

### Ports

| Port | Protocol | Configurable |
|------|----------|---|
| 44311 | HTTP | yes, `port` in `config/cheesedash.json` |
| 44312 | WebSocket | no — it is always `port + 1`, computed at startup |

Both must be free on the host, and reachable from wherever you open the browser. On a
rented host, a port in this range is often not open by default; see
[network exposure](auth-and-roles.md#network-exposure) before you ask your provider to
open it to the world.

---

## Build

CheeseDash has no sibling-mod dependency, so it builds on its own and it builds
**first** — every other mod in this repository that integrates with the dashboard
compiles against the jar produced here.

```bash
cd CheeseDash && ./gradlew build     # gradlew.bat on Windows
```

The jar you want:

```text
CheeseDash/build/libs/cheesedash-1.0.0+1.21.1.jar
```

Ignore `cheesedash-1.0.0+1.21.1-sources.jar` — that is the source bundle, not a mod.
The exact version comes from `mod_version` in `gradle.properties`.

From the repository root, `tools/build-all.sh` builds everything in dependency order,
which means CheeseDash goes first there too.

### What the build does with the frontend

The dashboard's HTML, JavaScript and CSS live at the **root of the mod folder**, not in
`src/main/resources`. The `processResources` task copies them in:

| Source | Lands in the jar at |
|---|---|
| `index.html` | `dashboard/index.html` |
| `preview.html` | `dashboard/preview.html` |
| `assets/` | `dashboard/assets/` |

Edit the files at the root. Anything you write into `src/main/resources/dashboard/`
gets overwritten by the next build. More detail in [web UI](web-ui.md).

---

## Install

1. Stop the server.
2. Copy `cheesedash-1.0.0+1.21.1.jar` into `mods/`.
3. Make sure Fabric API is in `mods/` too.
4. Start the server.
5. Copy the same jar into your client's `mods/` only if you want the quick menu.

Mods that register a dashboard module (CheeseGuard, CheeseMarket, CheeseKart and the
rest) go in the same `mods/` folder. Load order does not matter: CheeseDash collects
modules through Fabric's entrypoint system, which runs after every mod's jar has been
discovered.

---

## What the first launch creates

One file:

```text
config/cheesedash.json
```

It is written with a port, a freshly generated 32-byte master token, `enabled: true`,
an empty `accounts` array and an empty `zoneFolders` array. Full reference in
[configuration](configuration.md).

Three more files appear later, each only once something writes to it:

| File | Created when |
|---|---|
| `config/cheesedash_audit.json` | the first audited action — including the first login |
| `config/cheesedash_schedules.json` | the first recurring schedule is saved |
| `config/cheesedash_webhooks.json` | the first Discord webhook is created |

On the client, `config/cheesedash_shortcuts.json` appears the first time you save a
quick-menu shortcut.

Nothing is stored in world data or player data. Sessions live in memory only and are
gone after a restart, which is deliberate — logging in again is cheap.

---

## Verifying it loaded

The server log, in order:

```text
[CheeseDash] Initializing...
[CheeseDash] 0 module(s) discovered.
[CheeseDash] ServerScheduler initialized — 0 recurring schedule(s)
[CheeseDash] DiscordWebhookManager loaded — 0 webhook(s)
[CheeseDash] Initialization complete.
```

Then, once the world has finished loading:

```text
[CheeseDash] Server started — notifying 0 module(s)...
[CheeseDash] ✅ Started on port 44311 — http://localhost:44311/
[CheeseDash] WebSocket started on port 44312
```

The `✅ Started on port` line is the one that matters. If initialization succeeded but
that line never appears, either `enabled` is `false` in the config — in which case you
get `Disabled in config, skipping.` — or the bind failed, which logs:

```text
[CheeseDash] ❌ Failed to start: Address already in use
```

See [troubleshooting](troubleshooting.md#the-dashboard-never-starts-and-the-log-says-address-already-in-use).

On a true first run, the token banner is printed too, and it is the only time the token
is shown in the log:

```text
[CheeseDash] ╔═══════════════════════════════════════════════════╗
[CheeseDash] ║       FIRST RUN — Dashboard token generated       ║
[CheeseDash] ║  Save this token, you'll need it to log in:       ║
[CheeseDash] ║   T3Kq...                                         ║
[CheeseDash] ║  Port: 44311                                      ║
[CheeseDash] ╚═══════════════════════════════════════════════════╝
```

If you miss it, the token is in `config/cheesedash.json` under `token`.

### With modules installed

Each module announces itself twice — once from the registry, once from the loader:

```text
[CheeseDash] Module registered: CheeseKart (prefix: /api/v1/kart)
[CheeseDash] Module loaded: CheeseKart (kart)
[CheeseDash] 1 module(s) discovered.
```

A module whose prefix collides with one already registered is skipped with a warning
and will not appear in the sidebar:

```text
[CheeseDash] Duplicate module prefix 'kart' — skipping CheeseKart
```

### From outside the game

The cheapest check needs no browser and no token:

```bash
curl http://localhost:44311/api/v1/ping
```

```json
{"status":"ok","mod":"cheesedash"}
```

`/ping` is handled before the authentication middleware would have anything to say
about a missing header, so a `401` here means something else is answering on that port.
Then confirm the module list, which does need the token:

```bash
curl -H "Authorization: Bearer <token>" http://localhost:44311/api/v1/modules
```

```json
[{"prefix":"kart","displayName":"CheeseKart","icon":"🏎","description":"Mode kart et courses"}]
```

An empty array means CheeseDash loaded and found no modules — correct if it is the only
mod you installed, a problem otherwise. See
[troubleshooting](troubleshooting.md#a-module-is-installed-but-missing-from-the-sidebar).

---

## Uninstalling

1. Stop the server. There is no teardown to run first: the HTTP and WebSocket servers
   stop on `SERVER_STOPPING`, and nothing CheeseDash owns lives in the world.
2. Remove the jar from `mods/`.
3. Remove every mod that declares `cheesedash` in its `depends`. CheeseGuard,
   CheeseMarket, CheeseKart, CheeseItems, CobbleCheeseScore, CobblemonDungeons and
   others will refuse to load without it, and Fabric will stop the server with a
   dependency error rather than start degraded.

What is left behind, all of it inert and safe to keep or delete:

```text
config/cheesedash.json            ⚠️ contains the master token — delete it or keep it secret
config/cheesedash_audit.json      the audit trail, up to 10 000 entries
config/cheesedash_schedules.json  recurring restart definitions
config/cheesedash_webhooks.json   ⚠️ contains Discord webhook URLs, which are secrets
```

Deleting `cheesedash.json` is how you forget a compromised master token: the next start
generates a new one. Deleting it also discards every additional account, since they are
stored in the same file.

---

← [CheeseDash](../README.md) · [Getting started](getting-started.md) →
