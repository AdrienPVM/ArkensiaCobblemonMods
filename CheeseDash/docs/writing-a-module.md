[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](writing-a-module.fr.md)

# Writing a module

A CheeseDash module is a Fabric mod that implements one interface and declares it as an
entrypoint. CheeseDash finds it at startup, gives it a namespace under
`/api/v1/<prefix>/`, puts it in the sidebar, and routes every request below that prefix
to it. Authentication, session handling, role gating on writes, audit logging, security
headers and the CORS policy are already done by the time your code runs.

Fifteen mods in this repository do exactly this.
[`CheeseKart/src/main/java/com/cheesekart/dashboard/`](../../CheeseKart/src/main/java/com/cheesekart/dashboard/)
is the shortest real one — a 38-line module class and nine routes — and worth opening
next to this page.

---

## The three pieces

```text
build.gradle          compile against CheeseDash's built jar
fabric.mod.json       declare the "cheesedash" entrypoint and the hard dependency
YourDashboardModule   implement DashboardModule
```

### 1. Depend on CheeseDash in `build.gradle`

CheeseDash is not published to a Maven repository. Sibling mods in this repository
compile against its **built jar**, resolved with a glob so the version number is not
baked in:

```groovy
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

    // CheeseDash — build it first
    modImplementation files(fileTree(
        dir: "../CheeseDash/build/libs",
        includes: ["cheesedash-*.jar"],
        excludes: ["*-sources.jar", "*-dev.jar"]))

    implementation 'com.google.code.gson:gson:2.10.1'
}
```

The consequence to remember: **CheeseDash has to be built before your mod**, or the
glob matches nothing and you get a compile error about missing `com.cheesedash` classes.
From the repository root, `tools/build-all.sh` walks the mods in dependency order.

`com.sun.net.httpserver` needs no compiler flag on JDK 21 — the `jdk.httpserver` module
exports it. If you hit `package com.sun.net.httpserver is not visible`, the cause is
almost always a stale `--release 8`-era toolchain setting rather than a missing export;
[troubleshooting](troubleshooting.md#compiling-fails-with-package-comsunnethttpserver-is-not-visible)
goes through it.

### 2. Declare the entrypoint

```json
{
  "schemaVersion": 1,
  "id": "mymod",
  "entrypoints": {
    "main": ["com.example.mymod.MyMod"],
    "cheesedash": ["com.example.mymod.dashboard.MyDashboardModule"]
  },
  "depends": {
    "fabricloader": ">=0.15.11",
    "minecraft": "~1.21.1",
    "java": ">=21",
    "fabric-api": "*",
    "cheesedash": "*"
  }
}
```

`cheesedash` is a **custom entrypoint**, not one Fabric knows about. CheeseDash collects
it itself:

```java
FabricLoader.getInstance().getEntrypoints("cheesedash", DashboardModule.class)
```

Two deliberate choices available to you here:

- **`depends`** means your mod refuses to load without CheeseDash. Right for a mod whose
  whole point is the dashboard.
- **`suggests`** means it loads either way, and your module class is simply never
  instantiated — Fabric does not resolve an entrypoint for a class whose dependency is
  absent. Right for a mod that works in game on its own and merely gains an admin page.
  Careful: anything else in your mod that imports a `com.cheesedash` class will then
  throw `NoClassDefFoundError`, so keep that import surface inside the
  `dashboard` package.

### 3. Implement `DashboardModule`

Four methods are required, four more have defaults.

| Method | Required | What it is for |
|---|---|---|
| `String getPrefix()` | yes | Your namespace. `"kart"` means `/api/v1/kart/*`. Lowercase, no slashes |
| `String getDisplayName()` | yes | Sidebar label, e.g. `"CheeseKart"` |
| `String getNavIcon()` | yes | A single emoji for the sidebar |
| `String getNavDescription()` | yes | One short line under the label |
| `void handleRequest(HttpExchange, String subRoute)` | yes | Every request to your prefix. `subRoute` is the path after the prefix, starting with `/` |
| `void onServerStart(MinecraftServer)` | default no-op | Where you capture the server reference |
| `void onServerStop()` | default no-op | Drop that reference, stop your own threads |
| `String getPublicPrefix()` | default `null` | Return non-null to also get an unauthenticated context; see [below](#public-unauthenticated-contexts) |
| `void handlePublicRequest(HttpExchange, String path)` | default no-op | Handles `/{publicPrefix}/api/*` |

> ⚠️ Do not pick a prefix that collides with a core route: `ping`, `status`, `modules`,
> `auth`, `admin`, `dashboard`, `server`, `webhooks`. Core checks run first, your module
> is never reached, and nothing warns you. Collisions with another *module* are warned
> about at startup and the second module is skipped.

---

## The helpers CheeseDash gives you

All static on `WebDashboard`.

### Responding

```java
WebDashboard.respond(exchange, 200, "{\"ok\":true}");        // status + raw JSON string
WebDashboard.respondJson(exchange, someJsonObject);          // 200 + Gson-serialised
String body = WebDashboard.readBody(exchange);               // the request body as UTF-8
```

`respond` sets `Content-Type: application/json; charset=utf-8`, writes the
`Content-Length` and closes the stream. Call it **exactly once** per exchange: a second
call throws, and zero calls leaves the connection hanging until the client times out.
That second failure mode is the one to watch for in an early-return branch.

Build JSON with Gson's `JsonObject`/`JsonArray` rather than concatenating strings — a
player name with a quote in it will otherwise produce a malformed response. Where the
core does concatenate, it runs the value through an `escapeJson` helper first.

For a generated image, there is one specialist:

```java
WebDashboard.servePngWithRevalidation(exchange, path);  // ETag + Last-Modified, 304-aware
```

It answers `404` if the file is missing, `304` when the client's `If-None-Match` or
`If-Modified-Since` still matches, and otherwise the bytes with a five-minute cache
window. CobbleCheeseScore uses it for rendered scoreboard images.

### Permissions

```java
if (!WebDashboard.requireWrite(exchange)) return;   // OPERATOR or above, else 403
if (!WebDashboard.requireAdmin(exchange)) return;   // SUPER_ADMIN only, else 403
if (!WebDashboard.requireRole(exchange, AdminAccount.Role.OPERATOR)) return;
boolean ok = WebDashboard.hasRole(exchange, AdminAccount.Role.OPERATOR);  // no response written
```

Each `require*` writes the `403` itself before returning `false`, which is why the
pattern is `if (!…) return;` and not an `if/else`.

Remember that `DashboardApi` already applied `requireWrite` to every non-`GET` request
before calling you. Adding `requireWrite` again is harmless and makes the intent
explicit, but it is not what protects you. What you **do** have to add yourself is
`requireAdmin` on anything an `OPERATOR` should not touch — deleting everything,
rewriting a config file, moving real money.

> A caution from a real bug in this repository: `KartApi` calls
> `WebDashboard.requireWrite(exchange)` and **discards the result**, then continues to
> the handler. Had the central gate not existed, that would have let a `READ_ONLY`
> account write, with the `403` body already sent and a second response attempt
> following it. Always branch on the return value.

### Session context

```java
SessionManager.Session s = WebDashboard.getSession(exchange);
String who  = s.getUsername();      // "admin", or the account name
String role = s.getRole().name();   // READ_ONLY | OPERATOR | SUPER_ADMIN
String ip   = s.getIp();
```

The session is attached to the exchange by the middleware before dispatch. It is
non-null for any request that reached your `handleRequest`; it **is** null inside
`handlePublicRequest`, which runs outside authentication entirely.

### Touching the game: the one rule that matters

```java
WebDashboard.executeOnServerThread(server, () -> {
    manager.deleteZone(id);                 // runs on the server tick thread
});
```

**Every mutation of Minecraft state goes through this.** Your handler runs on one of
four threads in a fixed pool named `CheeseDash-HTTP`, concurrently with the game loop.
Spawning an entity, teleporting a player, changing a block or running a command from
that thread is a data race against the server tick, and the crashes it produces only
appear under load — which is to say, in production and never in testing.

`executeOnServerThread` hands the task to `server.execute(...)` and blocks on a
`CompletableFuture` for **up to 5 seconds**. Three consequences:

- It throws `IllegalStateException("Server not available")` if you pass `null`. Check
  your server reference and answer `503` instead.
- It propagates whatever your task threw, wrapped in an `ExecutionException`.
- It throws `TimeoutException` after 5 seconds. A slow task — loading a region,
  rewriting a large file — will time out while *still running* on the server thread.
  Your handler then answers `500` for an operation that is going to succeed a second
  later. For anything slow, do not wait: call `server.execute(...)` directly and answer
  `202`-style with "scheduled", documenting that the response means *accepted*, not
  *done*.

Reads are the other half of the rule. **Reading an immutable or thread-safe object
directly from the HTTP thread is fine and is what every module does** — a
`ConcurrentHashMap` of presets, an immutable record, a config object that is replaced
wholesale rather than mutated in place. Reading a live `ServerWorld`'s block state or
iterating a player's inventory is not; that is game state and needs the server thread
like any mutation.

### Pushing to the WebSocket

```java
DashboardRegistry.broadcast("kart", msg.toString());
```

Fire-and-forget. It reaches every WebSocket client that subscribed to that channel, and
does nothing at all if the WebSocket server is unavailable — so it is safe to call from
a mod that might be running without CheeseDash's WebSocket up.

The same call also feeds `DiscordWebhookManager.onBroadcast`, which maps certain channel
names and message types onto Discord events. Picking a channel name already known to
that mapping gets you Discord delivery for free; picking a new one does not. Channel
names and payload shapes are in [WebSocket](websocket.md).

### Escape hatch

```java
HttpServer raw = CheeseDashMod.INSTANCE.getWebDashboard().getHttpServer();
raw.createContext("/my-thing", exchange -> { … });
```

Available, and you should not need it. A context registered this way bypasses
authentication, the security headers and the audit log. The supported way to serve
something unauthenticated is the public prefix below.

---

## A complete module

A module that tracks one counter per player. One read route, one write route, enough to
show every rule in practice. It compiles as written, against CheeseDash 1.0.0 and
Minecraft 1.21.1.

### `src/main/resources/fabric.mod.json`

```json
{
  "schemaVersion": 1,
  "id": "cheesecount",
  "version": "${version}",
  "name": "CheeseCount",
  "environment": "*",
  "entrypoints": {
    "main": ["com.example.cheesecount.CheeseCountMod"],
    "cheesedash": ["com.example.cheesecount.dashboard.CountDashboardModule"]
  },
  "depends": {
    "fabricloader": ">=0.15.11",
    "minecraft": "~1.21.1",
    "java": ">=21",
    "fabric-api": "*",
    "cheesedash": "*"
  }
}
```

### The module class

```java
package com.example.cheesecount.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

public class CountDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()        { return "count"; }
    @Override public String getDisplayName()   { return "CheeseCount"; }
    @Override public String getNavIcon()       { return "🔢"; } // 🔢
    @Override public String getNavDescription(){ return "Per-player counters"; }

    @Override
    public void onServerStart(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void onServerStop() {
        this.server = null;
    }

    @Override
    public void handleRequest(HttpExchange exchange, String subRoute) throws IOException {
        // The server reference is only set on SERVER_STARTED, and the HTTP server
        // accepts connections from that same moment. Answer honestly in between.
        if (server == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"CheeseCount not ready\"}");
            return;
        }
        CountApi.handle(exchange, server, subRoute);
    }
}
```

### The API class

```java
package com.example.cheesecount.dashboard;

import com.cheesedash.AdminAccount;
import com.cheesedash.DashboardRegistry;
import com.cheesedash.SessionManager;
import com.cheesedash.WebDashboard;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CountApi {

    // Concurrent, because HTTP threads read it while the server thread writes it.
    private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute)
            throws IOException {
        String method = exchange.getRequestMethod();
        String path = subRoute.startsWith("/") ? subRoute : "/" + subRoute;

        if (path.equals("/counts") && method.equals("GET")) {
            listCounts(exchange);
            return;
        }
        if (path.equals("/counts") && method.equals("POST")) {
            // requireWrite already ran centrally; this route also rewards a player,
            // so keep it at OPERATOR and branch on the result.
            if (!WebDashboard.requireWrite(exchange)) return;
            addCount(exchange, server);
            return;
        }
        if (path.equals("/counts") && method.equals("DELETE")) {
            // Wiping everything is not an OPERATOR's job.
            if (!WebDashboard.requireAdmin(exchange)) return;
            COUNTS.clear();
            WebDashboard.respond(exchange, 200, "{\"cleared\":true}");
            return;
        }
        WebDashboard.respond(exchange, 404,
            "{\"error\":\"Unknown count route: " + escape(path) + "\"}");
    }

    // ── GET /api/v1/count/counts ─────────────────────────────────────────────
    // A read of a thread-safe map. No hand-off needed: this runs straight on the
    // HTTP thread, which is the whole point of keeping the map concurrent.
    private static void listCounts(HttpExchange exchange) throws IOException {
        JsonArray arr = new JsonArray();
        COUNTS.forEach((name, n) -> {
            JsonObject o = new JsonObject();
            o.addProperty("player", name);
            o.addProperty("count", n);
            arr.add(o);
        });
        WebDashboard.respond(exchange, 200, arr.toString());
    }

    // ── POST /api/v1/count/counts ────────────────────────────────────────────
    // Body: {"player":"RoiCheese","amount":1}
    private static void addCount(HttpExchange exchange, MinecraftServer server)
            throws IOException {
        JsonObject body;
        try {
            body = JsonParser.parseString(WebDashboard.readBody(exchange)).getAsJsonObject();
        } catch (Exception e) {
            // Parse defensively. The core has handlers that do not, and they turn a
            // client's typo into a 500.
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }

        // Validate before doing anything. An id or name that reaches a file path or a
        // registry lookup must be checked for traversal first.
        if (!body.has("player")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"player is required\"}");
            return;
        }
        String name = body.get("player").getAsString().trim();
        if (!isValidName(name)) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid player name\"}");
            return;
        }
        int amount = body.has("amount") ? body.get("amount").getAsInt() : 1;
        if (amount < 1 || amount > 1000) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"amount must be 1-1000\"}");
            return;
        }

        int updated = COUNTS.merge(name, amount, Integer::sum);

        // Telling the player is a mutation of game state. It goes on the server thread,
        // always, even though sendMessage looks harmless.
        try {
            WebDashboard.executeOnServerThread(server, () -> {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
                if (p != null) {
                    p.sendMessage(net.minecraft.text.Text.literal("+" + amount), false);
                }
            });
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }

        // Push it to any dashboard tab that subscribed to this channel. Fire and forget:
        // it is a no-op when no WebSocket server is running.
        JsonObject ws = new JsonObject();
        ws.addProperty("type", "count_update");
        ws.addProperty("player", name);
        ws.addProperty("count", updated);
        DashboardRegistry.broadcast("count", ws.toString());

        // Who did this is available if you want a richer audit entry than the automatic
        // "count.post" one the core writes for every non-GET request.
        SessionManager.Session session = WebDashboard.getSession(exchange);
        String by = session != null ? session.getUsername() : "unknown";

        JsonObject resp = new JsonObject();
        resp.addProperty("player", name);
        resp.addProperty("count", updated);
        resp.addProperty("by", by);
        WebDashboard.respondJson(exchange, resp);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isValidName(String s) {
        return s != null && !s.isEmpty() && s.length() < 64
            && !s.contains("..") && !s.contains("/") && !s.contains("\\")
            && !s.contains("%");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
```

Build CheeseDash, then build this, then drop both jars in `mods/`. The log should say:

```text
[CheeseDash] Module registered: CheeseCount (prefix: /api/v1/count)
[CheeseDash] Module loaded: CheeseCount (count)
[CheeseDash] 1 module(s) discovered.
```

And the routes answer:

```bash
curl -H "Authorization: Bearer ses_…" http://localhost:44311/api/v1/count/counts
# []

curl -X POST http://localhost:44311/api/v1/count/counts \
  -H "Authorization: Bearer ses_…" -H "Content-Type: application/json" \
  -d '{"player":"RoiCheese","amount":3}'
# {"player":"RoiCheese","count":3,"by":"admin"}
```

A `READ_ONLY` session on that `POST` gets
`403 {"error":"Insufficient permissions — requires OPERATOR"}` from the central gate,
without your handler running.

---

## Public, unauthenticated contexts

Returning a non-null `getPublicPrefix()` makes CheeseDash register a second context,
outside `/api/` and outside authentication:

```java
@Override public String getPublicPrefix() { return "shop"; }

@Override
public void handlePublicRequest(HttpExchange exchange, String path) throws IOException {
    // path is what followed "/shop/api", e.g. "/products"
}
```

What CheeseDash then does, on the same HTTP port:

| Request | Handling |
|---|---|
| `/shop` | `301` redirect to `/shop/` |
| `/shop/` or `/shop` with an empty remainder | serves `index.html` |
| `/shop/api/<rest>` | calls `handlePublicRequest(exchange, "/<rest>")` |
| anything else under `/shop/` | served from the **classpath** at `/<prefix>/<path>`, with `index.html` as the fallback for unknown paths |

So your static files go in `src/main/resources/shop/` and are served from the jar. Paths
containing `..` are rejected with `403`. Responses are gzipped when the content type is
compressible and the client said it accepts it. The header set is stricter than the
dashboard's: HSTS, `Permissions-Policy`, `frame-ancestors 'none'`, and a CSP that permits
Stripe — this mechanism exists for CheeseStore's storefront and its headers were written
for that case.

> ⚠️ **There is no authentication, no session, no role and no audit entry here**, and
> `WebDashboard.getSession(exchange)` returns `null`. Anyone who can reach the port can
> reach these routes. Every check — rate limiting, signature verification on a payment
> webhook, idempotency — is yours to write. Do not put anything behind a public prefix
> that you would not publish on the open internet.

An exception thrown out of `handlePublicRequest` is caught, logged and answered as
`500 {"error":"Internal server error"}`, with no detail leaked to the caller.

---

## Reaching a module from another mod

Nothing in CheeseDash brokers mod-to-mod calls. The pattern the Arkensia mods use is
reflection on the other mod's public singleton, so that the calling mod still loads when
the other one is absent — CheeseGuard's hooks into CheeseKart, CobbleShift and
CobblemonDungeons all work this way. Look the class up by name, cache the methods you
need, expose an `isAvailable()`, and be ready for the lookup to fail.

Never use `getDeclaredMethod("someYarnName", …)` on a **Minecraft** class for this: at
runtime those names are intermediary, not Yarn. Scan `getDeclaredMethods()` and match on
parameter types instead.

---

## Checklist before you ship a module

- Prefix does not collide with a core route or another module.
- `handleRequest` answers `503` while your server reference is null.
- Every branch calls exactly one `respond*`, including the error branches.
- Every non-`GET` route that an `OPERATOR` should not reach calls `requireAdmin` and
  branches on the result.
- Every id or name that reaches a file path is validated against `..`, `/`, `\` and `%`.
- Every mutation of game state is inside `executeOnServerThread`, and anything that
  could exceed 5 seconds is not.
- Shared state read from HTTP threads is concurrent or immutable.
- JSON is built with Gson, or escaped by hand if concatenated.
- `onServerStop` releases the server reference and stops any thread you started.

---

← [WebSocket](websocket.md) · [Web UI](web-ui.md) →
