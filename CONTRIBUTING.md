# Contributing

Thanks for looking. These mods were written for one server, and they are published so
other people can keep them alive. Forks are welcome, and so are pull requests.

A note on honesty before you start: this code was written under real deadlines for a
live server. It is not uniformly pretty. If something looks odd, it is usually because
it had to ship on a Friday night. Ask rather than assume.

---

## Before you open a pull request

**Small fixes** — a bug, a crash, a wrong tooltip, a translation: just send it. No
need to ask first.

**Anything larger** — a new feature, a refactor, a new dashboard view: open an issue
first and describe what you have in mind. It saves you from writing something that
does not fit the architecture, and it saves everyone the awkward conversation
afterwards.

**Please do not** send pull requests that reformat whole files, rename things across a
whole mod, or reorganise packages. They are impossible to review, they break every
other open branch, and the diff hides whatever real change is inside.

---

## Building

You need **JDK 21** and nothing else — Gradle comes with the wrapper.

Each mod is an independent Gradle project:

```bash
cd CheeseGuard
./gradlew build          # jar in build/libs/
```

On Windows, use `gradlew.bat`.

To test against a real server, drop the built jar into your server's `mods/` folder
along with CheeseDash and whatever the mod declares in its `depends`. Delete the old
jar first — two versions of the same mod in `mods/` will fail to load in confusing
ways.

### A compilation error you will probably hit

```
package com.sun.net.httpserver is not visible
```

The mod needs the JDK's internal HTTP server exported. Its `build.gradle` should
already contain:

```gradle
options.compilerArgs += ['--add-exports', 'jdk.httpserver/sun.net.httpserver=ALL-UNNAMED']
```

---

## Architecture in one page

**CheeseDash is the hub.** It runs an HTTP server on port 44311 and a WebSocket server
on 44312. Other mods do not open their own ports; they register a module with
CheeseDash through a Fabric custom entrypoint:

```json
"entrypoints": { "cheesedash": ["com.example.dashboard.MyDashboardModule"] }
```

CheeseDash discovers them at startup and routes `/api/v1/<prefix>/*` to each module.
Any combination of mods works: CheeseDash alone, with one module, or with all of them.

**Admins use the dashboard. Players use in-game GUIs.** There are no admin GUIs
in-game, deliberately. Please do not add any.

---

## Conventions

These are the rules the existing code follows. A pull request that follows them is
much faster to merge.

### Java

- **Thread safety is the one rule you cannot bend.** HTTP handlers run on their own
  threads. Any mutation of Minecraft state must go through
  `WebDashboard.executeOnServerThread(server, () -> { ... })`. Reading immutable or
  thread-safe objects directly from the HTTP thread is fine. Getting this wrong
  produces crashes that reproduce only under load.
- Validate parameters first, before doing anything else in a handler. Reject IDs
  containing `..`, `/`, `\`, `%` or spaces with a 400.
- Build JSON with Gson's `JsonObject` / `JsonArray`. Never concatenate JSON strings.
- Error responses are always JSON with a meaningful HTTP status:
  `WebDashboard.respond(exchange, 404, "{\"error\":\"Zone not found\"}")`.
- Player-facing messages use `Text.translatable("modid.key", args)` with language
  files in `src/main/resources/assets/<modid>/lang/`. Keep `Text.literal()` only for
  runtime data, user-configured content, decorative characters, and mod prefixes.
- Do not put `§` colour codes in language files. Use `.formatted(Formatting.X)` in
  Java instead.
- Log with the mod's prefix: `LOGGER.info("[Dashboard] ...")`.

### Reflection into Cobblemon and other mods

Several mods talk to Cobblemon without a compile-time dependency, so they keep working
when it is absent. If you touch that code:

**Never look a method up by its Yarn name.** Method names are intermediary at runtime
and `getDeclaredMethod("someYarnName", ...)` will fail in production while working
fine in dev. Scan by signature instead:

```java
for (Method m : clazz.getDeclaredMethods()) {
    if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == Text.class) { ... }
}
```

### Frontend (CheeseDash dashboard)

Plain Alpine.js, Tailwind and DaisyUI from a CDN. No React, no npm, no build step —
and please keep it that way.

- All network calls go through `API.*` in `assets/api.js`. Never call `fetch()`
  directly.
- `x-text` for API data, `x-html` only for HTML you hardcoded in JS. Run any user data
  that lands in an HTML attribute through `escHtml()`, single quotes included.
- No hardcoded user-facing strings. Use `t('key')`, and add every new key to **both**
  `lang-fr.js` and `lang-en.js`.
- Use the existing design-system classes (`.btn-cg`, `.cg-table`, `.section-card`,
  `.field-label`…) and the CSS variables. No raw hex colours, no inline styles.
- `const` / `let`, never `var`. Debounce search inputs at 300 ms.
- Edit the files at the root of `CheeseDash/`, not the copies under
  `src/main/resources/dashboard/` — `processResources` overwrites those on every build.
- A helper used by two or more views belongs in `app.js`; one used by a single view
  belongs in that view's `view-*.js`.

### Commits

Conventional-style prefixes, present tense, one logical change per commit:

```
fix(cheeseguard): stop zone flags leaking across dimensions
feat(cobblecap): add configurable champion cap bonus
docs(cheesedash): document the module entrypoint
```

---

## What not to put in a commit

- **Never commit a `config/` directory.** Those files hold the CheeseDash master token
  and Stripe secret keys. The root `.gitignore` excludes them; do not override it.
- No built jars, no `build/` output, no IDE folders.
- No world saves, logs or player data.
- Nothing containing a real IP address, hostname, API key or token — including in
  comments and test fixtures.

If you ever commit a secret by accident, say so immediately in an issue or on Discord.
Rotating a leaked token takes a minute; discovering it was leaked six months later is
a different kind of problem.

---

## Licence of your contribution

By opening a pull request you agree that your contribution is licensed under the
[PolyForm Noncommercial License 1.0.0](LICENSE), the same terms as the rest of the
project, and that you have the right to submit it.
