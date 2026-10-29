[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

Sixteen Java files, 4 656 lines, no mixins. The interesting complexity is not in the
Java — it is in the thread boundary between an HTTP request and a Minecraft tick, and in
the frontend, which is seven times larger than the backend.

---

## Package layout

```text
com.cheesedash
├── CheeseDashMod.java            entrypoint: discovers modules, wires events, owns the six singletons
├── DashboardModule.java          the public interface other mods implement           (45 L)
├── DashboardRegistry.java        static map of prefix → module, plus broadcast()     (62 L)
├── DashboardApi.java             dispatcher for /api/v1/*, every core endpoint    (1 242 L)
├── WebDashboard.java             the HTTP server, auth, rate limiting, headers, helpers (554 L)
├── WebSocketServer.java          WS on port+1, auth handshake, channel fan-out      (193 L)
├── DashboardConfig.java          config/cheesedash.json, tokens, accounts           (285 L)
├── SessionManager.java           in-memory sessions, 24 h sliding                   (187 L)
├── AuditLogger.java              persistent audit trail, 10 000-entry ring          (179 L)
├── AdminAccount.java             one account; the Role enum lives here               (59 L)
├── ServerScheduler.java          one-shot and recurring restarts, countdown, respawn (561 L)
├── DiscordWebhookManager.java    23 event types, embed building, async POSTs         (693 L)
├── network
│   └── QuickMenuPayload.java     C2S: "run this command for me"                      (26 L)
└── client
    ├── CheeseDashClient.java     client entrypoint: one keybind                      (50 L)
    ├── QuickMenuConfig.java      config/cheesedash_shortcuts.json                    (79 L)
    └── QuickMenuScreen.java      the shortcut screen                                (183 L)
```

Plus the frontend at the root of the mod folder: `index.html`, `preview.html` and
`assets/` — around 36 000 lines of HTML, JavaScript and CSS, including 26 `view-*.js`
files, one per dashboard page. `processResources` copies all of it into
`src/main/resources/dashboard/` at build time, which means **you edit the files at the
root and never the copies**. Details in [web UI](web-ui.md).

---

## Who owns what

**`CheeseDashMod`** is the only `ModInitializer`, exposed as a public static `INSTANCE`.
It constructs and holds the config, the HTTP server, the WebSocket server, the audit
logger, the scheduler and the Discord manager, and it is how `DashboardApi` reaches the
scheduler and the webhooks. It also owns the TPS measurement — a tick counter that
divides ticks by elapsed nanoseconds every 20 ticks and clamps the result to 20.0.

**`WebDashboard`** owns the `HttpServer` and everything that happens before a route
handler: the four-thread executor, the authentication middleware, the per-IP rate
limiter, the security headers, the static file handler with its gzip and SPA fallback,
and the public module contexts. It also hosts the static helpers that modules call, which
is why the class is both infrastructure and public API.

**`DashboardApi`** is one 1 242-line static dispatcher: a long chain of
`if (route.equals(…) && method.equals(…))` leading to private handlers. No framework, no
annotations, no reflection. It is repetitive and completely transparent, which is the
trade accepted here — the alternative was a routing abstraction nobody would have read.

**`DashboardRegistry`** is static state: a `LinkedHashMap` of prefix → module, so the
sidebar order follows the order Fabric returned the entrypoints. It also holds the
WebSocket server and the Discord manager references, which is what makes
`DashboardRegistry.broadcast(channel, json)` callable from any mod without a CheeseDash
object in hand.

**`DashboardConfig`** is the `cheesedash.json` reader/writer plus token resolution.
`resolveToken` checks the master token first, then every account, in constant time per
comparison. Accounts live in a `ConcurrentHashMap` because HTTP threads mutate them.

**`SessionManager`** holds sessions in a `ConcurrentHashMap` and nothing else. A session
captures `username`, `role`, `ip` and timestamps, and the role is **captured at creation**
— which is why demoting an account does not affect its live sessions.

**`AuditLogger`** keeps a synchronized `ArrayList` of entries, trims it to 10 000, and
writes the whole list to disk after every single entry.

**`ServerScheduler`** owns a two-thread `ScheduledExecutorService`: one future ticking a
countdown every second, one checking recurring schedules every 30 seconds.

**`DiscordWebhookManager`** owns a two-thread pool for outbound HTTP, a per-webhook rate
limiter, and the embed templates for 23 event types.

---

## The two servers

**HTTP, port 44311.** `com.sun.net.httpserver.HttpServer` from the JDK, backlog 64,
four daemon threads named `CheeseDash-HTTP`. Three kinds of context are registered at
startup, and **the order matters**:

1. Each module's public prefix, if it declared one — registered *before* `/api/` so it
   matches first.
2. `/api/` — the authenticated middleware, which then calls `DashboardApi.handle`.
3. `/` — the static handler, which serves `dashboard/*` from the classpath and falls back
   to `index.html` for anything unknown so the client-side router can take over.

Four threads is a deliberate ceiling: a dashboard serves one or two admins, and a bounded
pool means a runaway poller cannot spawn threads without limit. It also means four slow
requests block the fifth, which is the reason a handler must never wait on something
long.

**WebSocket, port 44312**, from `org.java-websocket:Java-WebSocket:1.5.7`, bundled into
the jar with Gradle's `include` so no extra mod is needed. The port is computed as
`config.getPort() + 1` and has no setting of its own. A connection must send
`{"type":"auth","token":…}` within five seconds or a cleanup thread closes it with code
`4001`; after that it subscribes to channels and receives whatever is broadcast to them.
Protocol detail in [WebSocket](websocket.md).

One asymmetry worth knowing: the WebSocket compares the token against the **master token
only**, not against account tokens or session ids. See
[authentication and roles](auth-and-roles.md#the-websocket-is-master-token-only).

---

## Threads

| Thread | Name | What runs there |
|---|---|---|
| Server (main) | `Server thread` | Minecraft itself; the TPS counter; everything inside `executeOnServerThread`; world saves before a scheduled restart |
| HTTP | `CheeseDash-HTTP` ×4 | Every route handler, every module handler, config and audit writes triggered by a request |
| WebSocket | Java-WebSocket's own | The auth handshake, subscription bookkeeping, outbound sends |
| Session cleanup | `CheeseDash-Session-Cleanup` | Sweeps expired sessions every 5 minutes |
| WS cleanup | `CheeseDash-WS-Cleanup` | Closes unauthenticated sockets every 5 seconds |
| Scheduler | `CheeseDash-Scheduler` ×2 | The countdown tick and the recurring check |
| Discord | `CheeseDash-Discord` ×2 | Outbound HTTPS POSTs to Discord |
| Audit write | `CheeseDash-AuditWrite` | One **new thread per audit entry**, writing the whole file |
| Schedule / webhook write | `CheeseDash-ScheduleSave`, `CheeseDash-WebhookSave` | One new thread per save |

All of them are daemon threads, so none of them keeps the JVM alive after Minecraft
stops.

**The hand-off is `WebDashboard.executeOnServerThread(server, task)`**, and it is the
only sanctioned crossing from HTTP to game. It submits to `server.execute(...)` and
blocks on a `CompletableFuture` for at most five seconds, so the HTTP response is only
written after the game has actually done the work — or after it has timed out. The rule
every module follows: **mutations cross, reads of immutable or concurrent structures do
not.**

The reverse direction needs no ceremony: game code calls
`DashboardRegistry.broadcast(...)` straight from the server thread, and the WebSocket
library handles the actual sends elsewhere.

---

## Persistence

Nothing lives in world data or player data. Four JSON files in `config/`, each owned by
one class, each rewritten in full on every change — no incremental writes, no database,
no migrations.

| File | Written by | When |
|---|---|---|
| `cheesedash.json` | `DashboardConfig.save()` | Synchronously, on the calling thread, whenever an account, the admin player name, a zone folder or the master token changes. Also once at first start |
| `cheesedash_audit.json` | `AuditLogger` | After **every** audit entry, on a freshly spawned thread |
| `cheesedash_schedules.json` | `ServerScheduler` | On any change to the recurring list, on a freshly spawned thread |
| `cheesedash_webhooks.json` | `DiscordWebhookManager` | On any webhook change, on a freshly spawned thread |

Read back only at startup. Editing any of them on a running server has no effect and will
be overwritten.

Sessions are the deliberate exception: in memory only, gone on restart.

---

## Module discovery

One call, during `onInitialize`:

```java
FabricLoader.getInstance()
    .getEntrypoints("cheesedash", DashboardModule.class)
    .forEach(module -> { DashboardRegistry.register(module); … });
```

`cheesedash` is a custom entrypoint name, not something Fabric itself knows about. Any
mod declaring it gets instantiated and registered, keyed by its prefix; a duplicate
prefix is refused with a warning and the second module is dropped. Load order is whatever
Fabric returns, and it determines sidebar order.

Modules are then notified twice over the server's life, on `SERVER_STARTED` and
`SERVER_STOPPING`, which is where they pick up and release the `MinecraftServer`
reference. Both notifications run on the server thread before the HTTP server starts and
after it stops respectively — so a module's `onServerStart` cannot receive a request that
races it.

The full contract is in [writing a module](writing-a-module.md).

---

## Known weaknesses

Stated plainly, because finding these by surprise is worse.

**Security**

- **No TLS, and there never will be in-process.** The design assumes a firewall, an SSH
  tunnel or a reverse proxy. Tokens and session ids are cleartext on the wire.
- **The login route is not rate-limited.** The limiter sits behind the `/auth/login`
  short-circuit, so it only throttles bad `Authorization` headers on other routes. A
  brute-force attempt against the login endpoint is unthrottled — only audited.
- **The WebSocket accepts the master token only**, which forces anyone who needs live
  views to hold the highest-value credential. The role system does not reach the
  WebSocket at all.
- **`GET /webhooks/{id}` returns the Discord URL unmasked to any authenticated role**,
  `READ_ONLY` included. So does `GET /server/logs` with whatever is in the log.
- **Regenerating the master token does not revoke sessions.** Compromise recovery needs a
  restart.
- **The CSP carries `'unsafe-inline'` and `'unsafe-eval'`**, because Alpine.js evaluates
  attribute expressions. XSS defence rests entirely on the frontend escaping data.
- **Tokens are stored in cleartext**, not hashed. The config file is the secret.

**Robustness**

- **A malformed `cheesedash.json` is silently replaced.** Account parsing shares the
  outer `try`, so one bad `role` string, or a trailing comma, abandons the whole read and
  falls through to the first-run path: new master token, every account gone. The webhook
  and schedule loaders do the opposite and skip only the bad entry — the config loader
  should have been written that way too.
- **`AuditLogger` spawns a thread and rewrites the entire file per entry.** At 10 000
  entries that is a multi-megabyte write for every single logged action, and concurrent
  writers can interleave. Under a burst of mutations this is the first thing that will
  hurt.
- **`GET /server/logs` reads the whole log file** into memory to return its tail.
- **`executeOnServerThread` has a hard 5-second timeout** and reports a timeout as a
  `500`, even though the task usually completes a moment later. Any genuinely slow
  operation needs a different pattern.
- **Four HTTP threads**, no queue limit beyond the backlog of 64. Four slow handlers
  stall everything.
- **`enabled: false` still starts the WebSocket server**, occupying `port + 1` for no
  reason.

**API consistency**

- **`/status` reports `uptime` as the current epoch time**, not a duration;
  `/server/stats` reports a real duration under the same name.
- **Malformed JSON is a `500` on four routes** and a `400` on the rest, depending on
  whether the handler bothered to catch the parse.
- **`PUT /server/schedule/recurring/{id}` validates nothing** that its `POST`
  counterpart validates — hour, minute and type all go in unchecked.
- **`DELETE /admin/sessions/{id}` answers `200 {"revoked":false}`** for a session that
  does not exist, where everything else in the admin namespace would answer `404`.
- **Core prefixes shadow module prefixes silently.** A module calling itself `server`
  simply never receives a request.

**Scale**

- **Using a raw token as a Bearer credential creates a new session per request.** A
  polling script grows the session map and the log by one entry per poll.
- **The audit trail is capped at 10 000 entries** and trimmed from the front. On a busy
  server that is days, not months. Rotate the file yourself if you need a longer history.
- **`ServerScheduler.restartProcess` guesses the server jar** by scanning `-jar` in the
  current process arguments, then by looking for `fabric-server-launch*.jar` or
  `server*.jar` in the working directory. On a host that launches via a wrapper script or
  a non-standard jar name, the restart fails with `Cannot find server jar for restart`
  after the world has already been saved and the server is on its way down — leaving the
  server stopped rather than restarted. Test a `RESTART` once on a new host before
  trusting it with a recurring schedule.

---

## Extension points

- **A new core route.** Add a branch in `DashboardApi.handle` next to the related ones,
  then a private `handleX` method. Gate it with `requireWrite` or `requireAdmin` first,
  and add an `auditLogger.log(session, "domain.action", detail)` call if it mutates.
- **A new module.** Nothing to change in CheeseDash at all — that is the point. See
  [writing a module](writing-a-module.md).
- **A new WebSocket channel.** Just broadcast to a new name; channels are strings and
  subscription is open. Teach the frontend to subscribe to it.
- **A new Discord event type.** Add an enum constant to
  `DiscordWebhookManager.DiscordEventType` with a label, a description and a colour, then
  handle it in the embed builder. It appears in `GET /webhooks/event-types` for free.
- **A new dashboard page.** Add `assets/view-yours.js` with an `async buildYours(app)`
  function returning HTML, register the script in `index.html`, and add the nav entry.
  Covered in [web UI](web-ui.md).

---

← [Quick menu](quick-menu.md) · [Troubleshooting](troubleshooting.md) →
