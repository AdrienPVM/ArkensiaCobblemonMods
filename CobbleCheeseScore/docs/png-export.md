[← CobbleCheeseScore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](png-export.fr.md)

# PNG export

Every enabled leaderboard is rendered to a `BufferedImage` in memory and served over
HTTP as a PNG. This is the part of the mod that makes it worth installing: a leaderboard
you can hang on a wall in your spawn town and leave there, refreshing on its own.

Nothing is written to disk. The image lives in `CacheManager`'s `imageCache` map and is
encoded to PNG bytes on each request by `ImageIO.write(image, "PNG", baos)`. Restart the
server and every image is gone until the first refresh repopulates the cache, which
happens during `SERVER_STARTED`.

---

## Who renders what, and when

`ScoreboardManager.updateAll()` is the only entry point. For each enabled board it
computes the ranking, calls `ImageGenerator.generateScoreboard(boardConfig, scores,
dynamicTitle)`, and stores the result under the board's id. Three things trigger it:

| Trigger | Thread |
|---|---|
| `SERVER_STARTED` initial update | the server-start thread |
| the scheduler, every `updateIntervalMinutes` | a dedicated single-thread `ScheduledExecutorService` |
| `POST /api/v1/scores/refresh`, or any `PUT /api/v1/scores/scoreboards/:id` | a CheeseDash HTTP thread |

All three run the full pass over every board, not just one. A `PUT` on a single board
regenerates all thirty-odd images, which takes a second or two.

## Geometry

The image has no fixed size. It grows with the number of ranked players, from constants
at the top of `ImageGenerator`:

| Constant | Value |
|---|---|
| `COLUMN_WIDTH` | 580 |
| `ROW_HEIGHT` | 60 |
| `HEADER_HEIGHT` | 140 |
| `FOOTER_HEIGHT` | 60 |
| `PADDING` | 40 |
| `COLUMN_GAP` | 30 |

The column count comes from `ceil(players / rowsPerColumn)`, clamped to the range 1–3.
Once the count is fixed, the rows are redistributed evenly: `actualRowsPerColumn =
ceil(players / columns)`. So `rowsPerColumn` decides **how many columns you get**, not
how many rows each column ends up with.

```text
width  = 580 × columns + 30 × (columns − 1) + 80
height = 280 + actualRowsPerColumn × 60
```

Four worked cases, all with `rowsPerColumn: 10`:

| Ranked players | Columns | Rows per column | Image size |
|---|---|---|---|
| 10 | 1 | 10 | 660 × 880 |
| 25 | 3 | 9 | 1 880 × 820 |
| 30 | 3 | 10 | 1 880 × 880 |
| 45 (`maxPlayers: 45`) | 3 | 15 | 1 880 × 1 180 |

⚠️ Past 3 × `rowsPerColumn` players the clamp bites and columns get longer instead of
wider, so the image becomes a tall strip. If you set `maxPlayers: 100` with
`rowsPerColumn: 10` you get three columns of 34 rows — an image 2 320 pixels tall. Raise
`rowsPerColumn` in step with `maxPlayers` if you want a squarer picture.

The pixel format is `TYPE_INT_RGB` — no alpha channel. The translucent row backgrounds
still work, because they are blended against the gradient already painted underneath.

## What the renderer draws

In order:

1. A diagonal `GradientPaint` over the whole canvas, from `background` to the same
   colour at 90 % brightness.
2. A rounded header rectangle, 140 px tall, filled with a gradient from
   `titleBackground` to 85 % of it.
3. The title, centred, **Inter Bold 48**. The dynamic title wins over
   `boardConfig.title` when one is set — only the Conseil 4 failures board sets one.
4. The description, centred, **Inter Plain 20**, in the same colour as the title.
5. One row per player: a rounded rectangle, a 4 px left bar for the top three, the rank
   number (**Inter Bold 32** for the podium, 28 below), the player name (**Inter Bold
   22** for rank 1, 18 below), and the formatted score right-aligned in **Inter Bold
   22**, coloured with `titleBackground`.
6. The footer, centred, **Inter Plain 16**: `lastUpdateText` followed by the current time
   formatted `dd/MM/yyyy HH:mm:ss` in the configured `timeZone`. Skipped entirely when
   `showLastUpdate` is `false`.

Antialiasing is on for both shapes and text, and `KEY_RENDERING` is set to
`VALUE_RENDER_QUALITY`.

### ⚠️ Inter is almost certainly not installed

Every `new Font(...)` call asks for the family `"Inter"`. A headless Linux game server
rarely has it. When AWT cannot resolve a family name it falls back to a default logical
font, so the images render — they just render in whatever the JVM picked, and the metrics
differ enough that centring looks slightly off and long names truncate at a different
point.

There is no config key for the font. If the typography matters to you, install Inter
system-wide on the host (`fonts-inter` on Debian-family distributions) and restart the
server, or edit the six `new Font("Inter", …)` calls in
`src/main/java/com/cobblecheesescore/image/ImageGenerator.java`.

### Title text colour is computed, not configured

If a board sets `colors.titleText`, that colour is used. Otherwise the renderer picks
black (`#141414`) or white from the perceived luminance of `titleBackground`:

```text
luminance = (0.299·R + 0.587·G + 0.114·B) / 255
luminance > 0.5  →  near-black text
otherwise        →  white text
```

That is why the yellow default header (`#ffcb05`) gets dark text and a purple one gets
white, with no configuration.

### Colour keys the renderer ignores

Four keys appear in the shipped default config and in older documentation but no code
reads them. The podium colours are hard-coded gold / silver / bronze, and rank 1's name
is always `#FFD700`:

| Key | Status |
|---|---|
| `firstPlaceBackground` | ignored — rank 1 row is always gold at 40/255 alpha |
| `secondPlaceBackground` | ignored — silver at 30/255 |
| `thirdPlaceBackground` | ignored — bronze at 30/255 |
| `topPlayerText` | ignored — rank 1's name is always `#FFD700` |

`getRowBackgroundColor(int rank, ScoreboardConfig config)` takes the board config and
never looks at it. Changing those four keys does nothing.

### Long names

A name wider than `COLUMN_WIDTH − 260` (320 px) is shortened by chopping four characters
and appending `...`, repeatedly, until it fits or drops to ten characters. A very long
name on a narrow logical font can therefore still overflow its 320 px budget.

---

## Fetching the image

Two routes serve the exact same bytes. The difference is authentication.

### Authenticated

```text
GET /api/v1/scores/scoreboards/<id>/image
GET /api/v1/scores/scoreboards/<id>/image/<anything>.png
```

Needs a CheeseDash `Authorization: Bearer <token>` header like every other `/api/`
route. The second form exists only so a browser's *Save as* gives the file a sensible
name — the trailing segment is matched by the dispatcher's regex and then thrown away.

### Public, no authentication

```text
GET /scores/api/images/<name>/<id>.png
```

Served from CheeseDash's public context for the `scores` prefix, which is registered
**before** `/api/` and sits outside the auth middleware. `<name>` is free-form filler for
the filename a client will save; `<id>` is the real board id, URL-decoded. Both come from
`CobbleCheeseScoreDashboardModule.handlePublicRequest`.

This is the route to use for anything in-game, because nothing inside Minecraft can send
a Bearer token.

```bash
curl -o pokedex.png \
  http://your-server:44311/scores/api/images/pokedex/pokedex_completed.png
```

| Response | Meaning |
|---|---|
| `200` with `Content-Type: image/png` | the cached image, plus `Cache-Control: public, max-age=60` |
| `404` with a JSON body | no image cached under that id — the id is wrong, no refresh has run yet, or the board has been disabled since the server started (a board disabled later keeps serving its last image) |
| `503` with a JSON body | `CacheManager` is null, meaning the mod has not finished initialising |

⚠️ A `404` and a `503` come back as **JSON**, not as an image. A client that blindly
trusts the content type will try to decode `{"error":"…"}` as a PNG and show nothing.

⚠️ The public route exposes player names and statistics to anyone who can reach port
44311. That is deliberate — it is what makes in-game display possible — but it means you
cannot treat the whole dashboard port as private if you use it. Put it behind a reverse
proxy if you need to restrict it.

`Cache-Control: public, max-age=60` is the other thing to know: a client may legitimately
show a minute-old image after you force a refresh.

---

## Getting it onto a map in-game

Vanilla Minecraft cannot load an image from a URL, and this mod ships no client code —
`fabric.mod.json` declares `"environment": "server"`. Displaying the leaderboard in the
world needs a third-party image-frame mod on the clients. The comment on the public route
in `CobbleCheeseScoreDashboardModule` names WaterFrames, which is what Arkensia used.

> The steps below describe how that integration works from the mod's side. The specifics
> of any image-frame mod are an observation of how it was used, not a specification of
> this mod — check that mod's own documentation for its current command syntax.

1. **Have CheeseDash reachable from the clients.** The URL you place in the world is
   resolved by each player's game, not by the server, so `localhost` will not do. Use the
   host's public address or DNS name and make sure port 44311 is open to players.

   ```bash
   # from a machine on the players' side of the network
   curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://your-server:44311/scores/api/images/test/pokedex_completed.png
   ```

   `200 image/png` means you are done with the server side. Do not test with `curl -I`:
   the route answers only `GET`, and a `HEAD` request gets a `404`. A
   connection refused means the port is closed; a `404` means no image is cached yet —
   force one with `POST /api/v1/scores/refresh`.

2. **Pick your board id.** `GET /api/v1/scores/scoreboards` lists them all with a
   `hasImage` flag. The id is the `id` field, not the `filename` field — `filename` is
   dead weight in the config and plays no part in any URL.

3. **Note the aspect ratio.** Work out the image size from the geometry table above, or
   read the `Content-Length`/pixel size off the fetched file. A 1 880 × 880 image wants a
   frame roughly 2:1. Getting this wrong is the single most common complaint: the text
   looks stretched.

4. **Install the image-frame mod** on the clients (and the server, if it needs a
   server-side half). WaterFrames is a client-and-server mod; AutoModPack or your
   launcher pack is the usual way to get it onto every client.

5. **Place the frame and point it at the public URL.** With WaterFrames you place the
   frame block, then set its URL in the frame's own screen or with its command. The URL
   is the public route:

   ```text
   http://your-server:44311/scores/api/images/pokedex/pokedex_completed.png
   ```

6. **Size the frame** to the aspect ratio from step 3 and set its refresh interval to
   something a little longer than `updateIntervalMinutes`. Refreshing faster than the
   server regenerates only costs bandwidth, and `max-age=60` means a client will not see
   a change sooner than a minute anyway.

7. **Verify in-game.** Stand in front of the frame and read the footer line: it carries
   the render timestamp in the server's configured `timeZone`. If that timestamp advances
   on its own every `updateIntervalMinutes`, the whole chain works.

A board whose image is missing shows whatever the frame mod does with a failed load,
usually a blank or a placeholder. See
[troubleshooting](troubleshooting.md#a-frame-in-game-shows-nothing-or-a-placeholder).

---

← [Leaderboards](leaderboards.md) · [Mini-games](minigames.md) →
