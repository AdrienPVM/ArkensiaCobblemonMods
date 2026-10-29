[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](web-ui.fr.md)

# Web UI

The dashboard is one HTML file, one stylesheet and about forty JavaScript files served
straight out of the jar. No build step, no bundler, no npm, no framework beyond Alpine.js
from a CDN. That is a deliberate choice: the thing has one user per server, it has to be
editable from an SSH session, and a toolchain that rots in two years would outlive its
usefulness.

**~36 000 lines** of HTML, JavaScript and CSS, against ~4 650 lines of Java.

---

## Where the files live

```text
CheeseDash/
├── index.html                  app shell, login screen, script load order
├── preview.html                standalone demo with mock data
└── assets/
    ├── app.js                  Alpine store, router, shared helpers
    ├── api.js                  the REST client — every network call goes through it
    ├── style.css               the design system, 2 170 lines
    ├── i18n.js                 translation engine
    ├── lang-fr.js              French strings
    ├── lang-en.js              English strings
    ├── guides.js              in-app documentation for each module
    ├── view-*.js               26 files, one per view
    ├── egg-preview.js          Three.js voxel renderer for EggAndCheese
    ├── playcosmetics-viewer.js model viewer for CheeseStore cosmetics
    ├── three.min.js            vendored
    └── OrbitControls.js        vendored
```

### ⚠️ Edit the copies at the mod root, never the ones under `src/main/resources`

`processResources` in `build.gradle` copies them in at build time:

```groovy
from("${projectDir}/index.html")   { into "dashboard" }
from("${projectDir}/preview.html") { into "dashboard" }
from("${projectDir}/assets")       { into "dashboard/assets" }
```

So `src/main/resources/dashboard/` is generated output. Anything you write there is
overwritten by the next `./gradlew build`, silently, with no merge and no warning. If
your change vanished, that is why.

At runtime `WebDashboard.StaticHandler` reads from the classpath at
`/dashboard<path>`. `/` maps to `/index.html`; a path containing `..` is a `403`; an
unknown path falls back to `index.html` so client-side routing survives a page reload on
a deep link. Content types come from the file extension in
`WebDashboard.detectContentType`, HTML is served with no `Cache-Control` header at all,
and **everything else
gets `Cache-Control: public, max-age=3600`**. That hour of caching is the second reason
an edit appears to do nothing: hard-reload the page, or restart the server, after
touching a `.js` or `.css` file.

Compressible responses are gzipped transparently when the client advertises it; already
compressed formats such as PNG and woff2 are passed through untouched.

---

## Script load order

Order matters, because nothing here is a module and everything is a global. `index.html`
ends with this sequence:

```text
three.min.js → OrbitControls.js → egg-preview.js
i18n.js → lang-fr.js → lang-en.js
api.js → guides.js → app.js
view-overview.js … view-wizard.js   (26 files)
```

Alpine.js itself is loaded `defer` from a CDN at the top of the document, so it
initialises after every script above has run. That is what lets `NAV_SECTIONS` in
`app.js` call `t()` at the top level: the language packs have already registered
themselves by then.

Three things follow from this arrangement:

- **A new `view-*.js` must be added to `index.html`,** between `app.js` and the end of
  the list. A view file that is not referenced there simply never loads, and the
  router's call to its builder throws a `ReferenceError` (`buildKart is not defined`),
  which is shown in place of the view.
- **`app.js` must come before the view files,** since they rely on its shared helpers.
- **No `import`, no `export`.** Functions are global by declaration. `const` and `let`
  only — never `var`.

The CDN dependencies are Tailwind's play CDN, DaisyUI 4.10.1, Alpine.js 3.14.1 and
Google Fonts (Geist, Geist Mono). The content security policy set by
`WebDashboard.addSecurityHeaders` allows those origins (and `https://unpkg.com` for
scripts, which nothing currently loads from) plus
`https://mc-heads.net`, `https://minecraft.wiki` and `https://img.pokemondb.net` for
images, and `ws:`/`wss:` for the WebSocket. **The dashboard therefore needs outbound
internet access from the browser,** not from the server. On an air-gapped network the
layout collapses to unstyled HTML; vendoring Tailwind and Alpine next to `three.min.js`
is the fix, and nobody has done it yet.

---

## `app.js` — the store and the router

One Alpine component, `cgApp()`, holds the whole application state: `authenticated`,
`currentView`, `viewHtml`, the toast queue, the WebSocket, the live player list, the
cached server status. `loginForm()` is a second, smaller component for the login screen.

A view is a function that returns a string of HTML:

```javascript
// view-zones.js
async function buildZones(app) {
    let data;
    try { data = await API.zones.list(); }
    catch (e) { app.toast(e.message, 'error'); return `<div class="empty-state">…</div>`; }
    return `<div class="max-w-6xl mx-auto slide-in">…</div>`;
}
```

`loadView` is a `switch` over the view id that calls the right builder, and the result
is injected with Alpine's `x-html`. Detail routes are the same id with a second segment
after a colon (`zones:spawn`), dispatched through a second `switch`. The URL hash carries the current
view, so a refresh lands where you were.

Shared helpers live in `app.js` and specific ones in the view that uses them. The rule
is mechanical: **used by two or more views → `app.js`; used by one → that view's file.**
The helpers worth knowing before writing a view are `escHtml`, `_nav`, `_toast`,
`confirmAction`, `detailBackBtn`, `detailTabBar`, `switchDetailTab`,
`speciesPickerHtml`, `buildFlagsGrid`, `openPlayerPicker`, `skeletonCard` and
`skeletonPage`. Per-view mutable state goes in a namespaced global — `window._mk` for
market, `window._eg` for eggs, `window._ed` for events — not in the Alpine store.

Two rules that are not stylistic:

- **Never call `fetch()` directly.** Everything goes through `API.*` in `api.js`, which
  attaches the session credential, parses errors into exceptions and centralises the base
  URL.
- **`escHtml()` on every piece of server data you interpolate into HTML**, single quotes
  included. The views build HTML as strings, so this is the only thing standing between a
  player-chosen name and script injection into your own admin page.

---

## The sidebar adapts to what is installed

`NAV_SECTIONS` in `app.js` declares every section the frontend knows how to render. Most
sections carry a `prefix` naming the backend module they belong to. On login:

```javascript
const mods = await API.get('/modules');
if (Array.isArray(mods)) installed = new Set(mods.map(m => m.prefix));

this.navSections = NAV_SECTIONS.filter(s =>
  (s.key !== 'admin' || API.canAdmin()) &&
  (!s.prefix || installed === null || installed.has(s.prefix))
);
```

So a section appears only when `GET /api/v1/modules` reports its prefix, which is what
makes any combination of mods work with no configuration. Sections with no `prefix` —
*General* and *Administration* — are infrastructure and always shown, and the
*Administration* section is additionally hidden from anyone who is not `SUPER_ADMIN`.
If the `/modules` probe fails, `installed` stays `null` and **every** section is kept:
better a dead menu entry than a dashboard that hides half of itself because one request
timed out.

### Views for mods that are not in this repository

`assets/` ships `view-factionwar.js` and `view-wizard.js`, which belong to a
`factionwar` module that does not exist in this repository. The same is true of pieces
of other sections: `view-morphos.js` needs the `morphos` module (shipped inside
CheeseItems), `view-prison.js` needs CheeseGuard, `view-playcosmetics.js` needs
CheeseStore, `view-patrol.js` needs CheesePatrol, `view-music.js` needs CheeseMusic.

For a server that does not have the mod, the filter above does its job: **the section is
simply absent from the sidebar.** The JavaScript still loads — it is a few dozen
kilobytes of dead code served on every page load — but there is no way to reach it
through the interface. Typing the hash by hand (`#factionwar`) does reach the builder,
which then calls an endpoint under a prefix nobody registered and gets
`404 {"error":"Route not found: …"}`; the view shows an error toast and an empty state.
Nothing breaks, nothing is exposed, and the only cost is the download.

`view-raids.js` is the opposite oddity: the builder exists and is wired into the router,
CheeseRaids registers the `raids` prefix, but `NAV_SECTIONS` has no section for it. The
view is reachable only through `#raids`.

---

## Internationalisation

`i18n.js` is about a hundred lines. `I18N.register(lang, data)` takes a language pack,
`I18N.t(key, params)` resolves a key, and two globals wrap it:

```javascript
t('zones.created', {name: 'Arena'})   // interpolates {name}
tip('tip.zones.priority')             // returns a tooltip <span>, or '' if the key is missing
```

`lang-fr.js` and `lang-en.js` each hold about 2 900 keys. French is the default and the
fallback: an unknown key is looked up in French, and failing that the key itself is
returned, which makes a missing translation visible rather than blank. The choice lives
in `localStorage` under `cg-lang`, and switching language reloads the page because
`NAV_SECTIONS` and the guide definitions are evaluated once at load time.

Conventions, if you add a string:

- `module.subkey` for module strings, `common.*` for shared ones, `error.*` for
  failures, `tip.module.field` for field tooltips, `guide.module.section.*` for guide
  content.
- **Every new key goes into both `lang-fr.js` and `lang-en.js`.** A key present in only
  one file silently falls back to French for English readers.
- Dates and durations through `I18N.fmtDate(iso)` and `I18N.fmtUptime(ms)`, never a
  hardcoded locale.
- No user-visible string hardcoded in a view. A few do remain — the
  `factionwar` and `playcosmetics` nav entries carry French labels inline — and they are
  bugs, not a pattern to copy.

In-game text is a separate mechanism: `Text.translatable` with
`src/main/resources/assets/cheesedash/lang/{en_us,fr_fr}.json`.

---

## Guides

`guides.js` holds `GUIDE_DEFS`, a definition per view — 22 of them, covering the module
views and the three administration ones — and renders them from i18n keys shaped
`guide.<module>.<section>.{title,body,list,note}`. Two integration patterns, depending
on whether the view already has a tab bar:

```javascript
// a view with tabs: add a Guide tab
const tabs = detailTabBar('zv', ['info','flags','members'], guideTabEntry());
// …and its panel in the body
${guideTabPanel('zones', 'zv')}

// a view without tabs: a button that opens the guide in a modal
${guideButton('shift')}
```

When you change what a module does, update its `guide.*` keys in both language files.
A guide that describes last month's behaviour is worse than no guide.

---

## The design system

`style.css` defines the palette as CSS variables on `[data-theme="cg-dark"]` and
`[data-theme="cg-light"]`, and the theme attribute sits on `<html>`. The preference is
stored in `localStorage` under `cg-theme`, applied by an inline script in `index.html`
before the stylesheet paints, and toggled from the header. Dark is the default and the
one that gets the attention.

| Variable | Dark | Role |
|---|---|---|
| `--bg` | `#0c0e14` | page background |
| `--bg-card` | `#13161e` | cards and panels |
| `--bg-elevated` | `#1a1d28` | inputs, raised elements |
| `--bg-border` | `#252836` | every border |
| `--amber` | `#f5a623` | primary accent, the branding colour |
| `--cyan` | `#22d3ee` | secondary accent |
| `--green` | `#22c55e` | success, online |
| `--red` | `#ef4444` | danger, errors |
| `--muted` | `#6b7280` | secondary text |

Use the variables, never a literal hex, or the light theme breaks.

The components are custom classes, because DaisyUI's defaults do not match this palette:
`.btn-cg` with `.btn-cg-primary` / `-danger` / `-ghost` / `-success` / `-sm` for
buttons, `.cg-table` for tables, `.section-card` and its header/title/body for panels,
`.stat-card` for figures, `.id-badge` for identifiers, `.status-dot` with `.s-online` /
`.s-warn` / `.s-offline` / `.s-idle`, `.detail-tabs` and `.detail-tab` for tabs,
`.empty-state` for emptiness, `.skeleton-line` for loading, `.field-label` and
`.field-tip` for forms. DaisyUI is kept only where the theme overrides it cleanly:
`.input`, `.select`, `.toggle`, `.checkbox`, `.radio`, `.modal` and `.modal-box`.

---

## `preview.html` — an early standalone mock

`preview.html` is a separate, self-contained page with its own inline Alpine component
and mock data, hardcoded in French. It loads none of the `assets/*.js` files. Open it
from disk: no build, no Minecraft, but the browser still needs the Tailwind and Alpine
CDNs.

It opens straight on the dashboard: `init()` sets `auth = true`, so the login form is
skipped. (That form, if you re-enable it, accepts `demo` or any string over four
characters, and anything shorter shows `Token invalide — essayez "demo" pour la démo`.)
Only the overview has mock data — TPS, player count, counters for zones, dimensions
and events, a list of recent activity. The sidebar has seven more entries (Zones,
Groupes, Chunks, Dimensions, Événements, Joueurs, Serveur), and each of them shows a
`Phase 2 — implémentation en cours` placeholder.

So it shows the look and the palette, not the real interface, and it has drifted far
from `index.html`: none of the 26 views is in it. To work on a real view you need a
running server.

---

## Adding a view

Start to finish, for a module with prefix `kart`:

1. Add the endpoints to `api.js`, under an `endpoints.kart` object. No `fetch` anywhere
   else.
2. Write `assets/view-kart.js` with `async function buildKart(app)` returning HTML.
   Wrap every API call in `try/catch` and report failures with `app.toast(e.message,
   'error')`.
3. Add `case 'kart': return await buildKart(this);` to the list routes in `loadView`
   in `app.js`, and a detail case too if the view has one.
4. Add the section to `NAV_SECTIONS` with `prefix: 'kart'`, so it hides itself when the
   mod is absent.
5. Add `<script src="assets/view-kart.js"></script>` to `index.html`, after `app.js`.
6. Add every string to `lang-fr.js` and `lang-en.js`. Debounce search inputs by 300 ms.
7. Add a `GUIDE_DEFS.kart` entry and its `guide.kart.*` keys, and place
   `guideButton('kart')` in the view header.

Then `./gradlew build`, restart, and hard-reload the browser to get past that one-hour
`Cache-Control`.

---

← [Writing a module](writing-a-module.md) · [Scheduler](scheduler.md) →
