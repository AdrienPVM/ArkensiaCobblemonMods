[← CobbleCheeseScore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this walkthrough you will have a shiny-hunter leaderboard rendered as a
PNG, recoloured the way you want it, served on a URL that needs no authentication, and
hanging on a wall in your spawn town where it refreshes itself every ten minutes.

It assumes a dedicated Fabric 1.21.1 server with Cobblemon installed and at least one
player who has caught something. A world with no Cobblemon data at all will walk through
every step and render empty boards, which is a useful dry run but not a visible result.

---

## 1. Build and install both jars

The mod compiles against CheeseDash's jar, so CheeseDash goes first.

```bash
cd CheeseDash && ./gradlew build
cd ../CobbleCheeseScore && ./gradlew build
cp ../CheeseDash/build/libs/cheesedash-*.jar /path/to/server/mods/
cp build/libs/cobblecheesescore-1.0.0+1.21.1.jar /path/to/server/mods/
```

Full detail, including what to do when the CheeseDash jar is not found, in
[installation](installation.md).

## 2. Start the server and read three log lines

```bash
cd /path/to/server && java -Xmx8G -jar fabric-server-launch.jar nogui
```

Three lines prove the chain works. Expect them in this order:

```text
[cobblecheesescore] CobbleCheeseScore initialized successfully!
[CheeseDash] Public context registered: /scores/
[cobblecheesescore] Scoreboard update scheduler started (interval: 10 minutes)
```

The middle one is the important one: it means CheeseDash found the module through the
`cheesedash` entrypoint. Without it, nothing in the rest of this page works — go to
[troubleshooting](troubleshooting.md#the-dashboard-has-no-cobblecheesescore-section).

Just above the scheduler line you should also see how many players were found:

```text
[CobbleCheeseScore] Collected data for 30 players
```

`Pokedex folder not found` instead means the mod is looking in the wrong place or your
world genuinely has no Cobblemon data yet. See
[troubleshooting](troubleshooting.md#every-board-is-empty-and-the-log-says-pokedex-folder-not-found).

## 3. Get a dashboard token

Everything under `/api/` needs a session token. Log in with the master token from
`config/cheesedash.json`:

```bash
curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"token":"<master token from config/cheesedash.json>"}'
```

```json
{"token":"ses_8f2a…","role":"SUPER_ADMIN","username":"admin"}
```

Keep that `ses_…` value; the rest of this page calls it `$TOKEN`.

```bash
export TOKEN=ses_8f2a…
```

## 4. See what boards you have

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/scoreboards
```

Thirty-four objects come back. Find `shiny_hunter` and look at two fields:

```json
{
  "id": "shiny_hunter",
  "title": "Chasseur de Shiny",
  "playerCount": 12,
  "topPlayer": { "name": "RoiCheese", "score": "69" },
  "hasImage": true
}
```

`hasImage: true` means an image is already cached and waiting. `playerCount` is the
number of players with a non-zero score, not the number on the server — players with no
shinies are dropped before ranking.

If `playerCount` is `0` on every board but `Collected data for N players` said otherwise,
your Cobblemon data has a shape the parser does not recognise; check one board at a time
with `GET /scoreboards/shiny_hunter` to see the empty `rankings` array for yourself.

## 5. Look at the rendered image

The authenticated route gives you the PNG:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  -o shiny.png \
  http://localhost:44311/api/v1/scores/scoreboards/shiny_hunter/image
file shiny.png
```

```text
shiny.png: PNG image data, 660 x 880, 8-bit/color RGB, non-interlaced
```

Open it. You should see a gold header reading *Chasseur de Shiny*, one row per ranked
player with gold, silver and bronze accents on the podium, and a footer with the render
timestamp.

The size is not fixed — 660 × 880 is what twelve players at `rowsPerColumn: 10` produces.
The arithmetic is in [PNG export](png-export.md#geometry).

If `file` reports JSON rather than PNG, you got a `404` or a `503` body: the error
responses on this route are JSON while the success response is an image. Read the file.

## 6. Recolour it, and watch the image change

The dashboard route applies, saves and re-renders in one call. Give the board an English
title and a cyan header:

```bash
curl -s -X PUT http://localhost:44311/api/v1/scores/scoreboards/shiny_hunter \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Shiny Hunters","description":"Shinies caught",
       "colors":{"titleBackground":"#22d3ee"}}'
```

```json
{"updated":true}
```

That call takes a second or two, because it re-renders every board, not just this one.
Fetch the image again and the header is cyan with **dark** text — you did not choose the
text colour, the renderer computed it from the header's luminance. Override it with
`"titleText":"#FFFFFF"` in the same `colors` object if you disagree.

Check the file on disk too:

```bash
grep -A2 '"id": "shiny_hunter"' /path/to/server/config/cobblecheesescore.json
```

The change is already persisted. This is the one path where an edit survives a restart
without you touching the file — and the reason you should **not** hand-edit
`config/cobblecheesescore.json` while the server is running, since the next `PUT` writes
the in-memory config back over your edit.

## 7. Confirm the public URL works

Nothing inside Minecraft can send a Bearer token, so the in-game display uses the
unauthenticated route. No header this time:

```bash
curl -sI http://localhost:44311/scores/api/images/shiny/shiny_hunter.png | head -3
```

```text
HTTP/1.1 200 OK
Content-Type: image/png
Cache-Control: public, max-age=60
```

The `shiny` segment is free-form — it only decides the filename a browser would save.
`shiny_hunter` is the real board id.

Now test it **from a player's network**, not from the server:

```bash
curl -sI http://your-server-address:44311/scores/api/images/shiny/shiny_hunter.png
```

The URL is resolved by each player's game, so `localhost` will fail for everyone but you.
A connection refused here means port 44311 is not reachable from outside, and no amount
of in-game configuration will fix it.

⚠️ That route is open to anyone who can reach port 44311. It exposes player names and
statistics with no authentication. That is the deliberate trade-off that makes in-game
display possible; if it is not acceptable to you, stop here and embed the authenticated
route through a proxy of your own instead.

## 8. Hang it on a wall

Vanilla Minecraft cannot load an image from a URL. This step needs an image-frame mod on
the clients — WaterFrames is what this was built against.

1. Note the image's aspect ratio from step 5. 660 × 880 is taller than it is wide; a
   three-column board at 1 880 × 880 is roughly 2:1. Getting this wrong is why
   leaderboards end up looking stretched.
2. Install the image-frame mod on the clients, and on the server if it needs a
   server-side half.
3. Place a frame, open its screen, and set its URL to the public route from step 7, using
   your server's real address.
4. Size the frame to the ratio from step 1.
5. Set the frame's refresh interval a little longer than `updateIntervalMinutes` —
   twelve minutes against a ten-minute render. Refreshing faster gains nothing: the
   server sends `max-age=60` and does not re-render between scheduled passes anyway.

> The frame mod's own behaviour is an observation of how it was used here, not something
> this mod specifies. Check its documentation for the current way to set a URL.

Stand in front of the frame and read the footer timestamp. Full procedure and the failure
modes in [PNG export](png-export.md#getting-it-onto-a-map-in-game).

## 9. Force a refresh and watch the timestamp move

Rather than waiting ten minutes:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/refresh
```

```json
{"refreshed":true,"lastUpdate":1761700120000}
```

The log brackets the pass:

```text
[CobbleCheeseScore] Collecting player data...
[CobbleCheeseScore] Collected data for 30 players
[CobbleCheeseScore] Updated scoreboard: shiny_hunter (12 players)
[CobbleCheeseScore] All scoreboards updated successfully
```

Time the gap between the first and last line. That is what happens every
`updateIntervalMinutes`, and it is the number to look at before you raise `maxPlayers` or
shorten the interval.

Wait a minute for the frame's cache to expire, look at it again, and the footer timestamp
has advanced. That is the whole loop working: disk → ranking → PNG → HTTP → the wall.

## 10. Turn off what you do not want

A fresh install ships 34 boards, including four Conseil 4 boards that only mean anything
with [CobbleCap](../../CobbleCap/) installed. Without it they render as empty headers.

```bash
for id in conseil4_first_finish conseil4_fastest_time \
          conseil4_ranked_champions conseil4_ranked_failures; do
  curl -s -X PUT "http://localhost:44311/api/v1/scores/scoreboards/$id" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"enabled":false}'
done
```

Disabled boards are skipped entirely: no ranking, no image, and a `404` on their image
route. The seeder only re-adds a Conseil 4 board when its id is **missing** from the
config, so a disabled entry stays disabled across restarts.

Use `{"enabled": false}` on `PUT /scoreboards/:id` for this, not `PUT /config` — that
second route logs the request and does not apply it. The reason is in
[dashboard](dashboard.md#put-apiv1scoresconfig).

---

## Where to go next

- [Leaderboards](leaderboards.md) — every board field, what each of the 30 ids measures,
  and how to add a measurement of your own
- [Configuration](configuration.md) — the full config file, including the nine keys that
  do nothing
- [Mini-games](minigames.md) — the separate ledger for event results you type in by hand
- [Dashboard API](dashboard.md) — every route, with the roles it really enforces

---

← [Installation](installation.md) · [Configuration](configuration.md) →
