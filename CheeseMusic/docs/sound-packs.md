[← CheeseMusic](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](sound-packs.fr.md)

# Sound packs

**CheeseMusic contains no audio.** Search the jar and you will find sixteen class files,
two tiny language files, a mixin config and `fabric.mod.json` — no `.ogg`, no
`sounds.json`, no `assets/cheesemusic/sounds/` directory. Every track you hear in a zone
is a file you supplied.

That makes this page the one you cannot skip. The mod gives you half of the delivery
chain: it manages a resource pack **on the server**, keeps its `sounds.json` honest, and
sanitises your filenames. The other half — getting that pack onto every player's client
— it does not do, and cannot do, because resource packs are a vanilla mechanism the mod
never touches.

---

## Why a resource pack, and not a datapack

Sound files are client assets. The client is the machine with the speakers, and it will
only play a sound event it can resolve from its own loaded resource packs. A datapack
lives on the server and holds no audio. So:

- **Resource pack** — yes. This is the only route.
- **Datapack** — no. CheeseMusic reads nothing from `data/` and registers no datapack
  loader.
- **Vanilla sound events** — no, and this is worth stating plainly.
  `ZoneMusicTracker.sendPlay` builds the sound id as `"cheesemusic:" + track`, so every
  id the client receives lives in the `cheesemusic` namespace. There is no way to point
  an assignment at `minecraft:music.nether.basalt_deltas`, or at any other mod's sound
  event. If you want vanilla music in a zone, copy the `.ogg` out of the vanilla assets
  into your own pack under a name of your choosing.

---

## The pack the server builds for you

`SoundScanner` runs once, from `CheeseMusicMod.onServerStarted`, immediately after the
config loads. It works on two paths, both relative to the server's run directory:

```text
<server root>/resourcepacks/CheeseMusic/                              pack root
<server root>/resourcepacks/CheeseMusic/pack.mcmeta                    written if absent
<server root>/resourcepacks/CheeseMusic/assets/cheesemusic/sounds.json regenerated every scan
<server root>/resourcepacks/CheeseMusic/assets/cheesemusic/sounds/     your .ogg files
```

If the `sounds/` directory does not exist, the scanner creates it, writes `pack.mcmeta`
if there is none, and logs where it put everything:

```text
[CheeseMusic] Created resource pack at: .../resourcepacks/CheeseMusic
[CheeseMusic] Place .ogg files in: .../resourcepacks/CheeseMusic/assets/cheesemusic/sounds
```

The generated `pack.mcmeta` is exactly this:

```json
{"pack":{"pack_format":34,"description":"CheeseMusic — Custom zone music"}}
```

`34` is the resource pack format for 1.21.1. The file is written **only when it does not
already exist**, so you can replace it with your own description, add a `pack.png`, or
widen the `supported_formats` range, and the mod will leave your version alone.

⚠️ The directory is named `resourcepacks/`, which on a dedicated server is not a
directory vanilla reads for anything. Nothing on the server loads this pack. It is a
staging area whose only purpose is to be handed to clients.

### What a scan does

On every scan — at server start, and again after each track upload or delete through the
dashboard — `SoundScanner.scan()`:

1. Clears its in-memory track list.
2. Walks `sounds/` for `*.ogg` files only. Subdirectories are not visited; the glob is
   flat.
3. Sanitises each basename (next section). If the sanitised name differs from the real
   one, **the file is renamed on disk**, and the rename is logged:
   ```text
   [CheeseMusic] Renamed 'Volcano Theme (Final Mix).ogg' -> 'volcano_theme_final_mix.ogg'
   ```
4. Sorts the names alphabetically.
5. Rewrites `sounds.json` from scratch.
6. Logs the total: `[CheeseMusic] Found 7 tracks in resource pack`.

Step 3 mutates your files. If you need the original filenames preserved, keep your
masters somewhere else and copy sanitised duplicates into `sounds/`.

### Filename sanitising, exactly

`SoundScanner.sanitize` exists because a Minecraft `Identifier` path accepts only
`[a-z0-9/._-]`, and an invalid id throws on the client rather than failing gracefully.
The transformation, in order:

| Step | Effect |
|---|---|
| Lowercase | `Volcano` → `volcano`, using `Locale.ROOT` so a Turkish-locale server behaves the same as any other |
| Spaces | ` ` → `_` |
| Dashes | `—` (em dash) and `–` (en dash) → `-` |
| Commas | `,` → `_` |
| Filter | every character still outside `[a-z0-9/._-]` is **dropped**, not replaced |
| Collapse | runs of `_` become a single `_` |
| Trim | leading and trailing `_` are stripped |
| Empty result | becomes the literal `track` |

Two consequences to keep in mind. Accented characters are dropped outright, so
`forêt.ogg` becomes `fort.ogg` — rename such files yourself before upload if that
matters to you. And because the filter deletes rather than substitutes,
`Theme #1.ogg` and `Theme 1.ogg` both sanitise to `theme_1`; the second one scanned gets
`_2` appended (`theme_1_2`) to avoid clobbering the first.

### The generated `sounds.json`

One entry per track, written by `regenerateSoundsJson()`:

```json
{
  "spawn_theme": {
    "sounds": [{
      "name": "cheesemusic:spawn_theme",
      "stream": true
    }]
  },
  "volcano": {
    "sounds": [{
      "name": "cheesemusic:volcano",
      "stream": true
    }]
  }
}
```

So the sound **event** id is `cheesemusic:spawn_theme`, and the `name` field points the
client at `assets/cheesemusic/sounds/spawn_theme.ogg`. Event id and file name coincide,
which is why an assignment stores the bare name and nothing else.

`"stream": true` on every entry is deliberate: a streamed sound is decoded as it plays
instead of being loaded whole into memory, which is what you want for a four-minute
track. It also means the sound cannot be used positionally, which does not matter here —
`ZoneMusicSoundInstance` sets `relative = true` and plays at the listener.

The file is overwritten on every scan. Hand-edits to it are lost at the next server
start. If you need a subtitle, a volume curve, or several `.ogg` variants under one
event, you need a second pack of your own — this one is machine-generated.

---

## End to end: one `.ogg` to a zone that plays it

The worked example. A server with CheeseGuard and CheeseDash running, a region called
`volcano_cave`, and a file `Volcano Theme.ogg` on your laptop.

### 1. Convert the audio to Ogg Vorbis

Minecraft plays Ogg Vorbis. An `.ogg` container holding Opus will not play. With
ffmpeg:

```bash
ffmpeg -i "Volcano Theme.wav" -c:a libvorbis -q:a 5 -ar 44100 "Volcano Theme.ogg"
```

`-q:a 5` lands around 160 kbps, which is more than enough for background music and keeps
the pack small. Mono halves the size again if the track is not stereo-dependent
(`-ac 1`). Keep a single file under the dashboard's 50 MB upload ceiling.

### 2. Get the file into the server's pack

Either copy it there yourself:

```bash
scp "Volcano Theme.ogg" user@server:/srv/mc/resourcepacks/CheeseMusic/assets/cheesemusic/sounds/
```

…and restart the server so `scan()` picks it up and renames it. Or upload it through the
dashboard, which scans immediately and needs no restart:

```bash
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  --data-binary "@Volcano Theme.ogg" \
  "http://localhost:44311/api/v1/music/tracks/upload?name=Volcano%20Theme.ogg"
```

```json
{"ok":true,"track":"volcano_theme"}
```

The `track` field in the response is the sanitised name, and it is the only name that
works from here on. Note the filename travels in the `name` **query parameter**, not as
a multipart body; the body is the raw bytes. The endpoint rejects anything whose name
does not end in `.ogg`, or contains `..`, `/` or `\`.

### 3. Confirm the server sees it

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/tracks
```

```json
["volcano_theme"]
```

If the name is absent, the file is not in `sounds/`, is not `.ogg`, or sits in a
subdirectory.

### 4. Distribute the pack to clients

This is the step the mod does not do. Copy the whole
`resourcepacks/CheeseMusic/` directory out of the server and get it onto every client, by
one of the three routes in [Delivery](#delivery-to-clients) below. Pick one now, because
nothing in the following steps produces a sound without it.

The quickest route for a test: zip the contents of `resourcepacks/CheeseMusic/` so that
`pack.mcmeta` sits at the root of the zip, and drop the zip in your own client's
`.minecraft/resourcepacks/`. Then enable it in **Options → Resource Packs**.

### 5. Verify the client resolves the sound

In-game, with the pack enabled:

```mcfunction
/playsound cheesemusic:volcano_theme music @s
```

Hearing the track means the pack is loaded and the event id is right. The vanilla error
*No sound with that name* means the client cannot resolve it — the pack is not enabled,
or `sounds.json` does not list that name, or the `.ogg` is missing from the zip.

Fix this before touching assignments. Everything downstream assumes the client can
resolve the id.

### 6. Create the assignment

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"zoneId":"volcano_cave","zoneName":"Volcano Cave","tracks":["volcano_theme"],"mode":"SEQUENTIAL","volume":0.5,"fadeInTicks":40,"fadeOutTicks":80}' \
  http://localhost:44311/api/v1/music/assignments
```

The response echoes the stored assignment, and `config/cheesemusic.json` is rewritten on
the spot. Note `tracks` holds `volcano_theme` — the bare name, no `cheesemusic:` prefix.
The server adds the prefix when it sends the packet.

### 7. Walk into the region

Stand outside `volcano_cave`, then walk in. Within `checkIntervalTicks` (half a second by
default) ReactiveMusic fades out, and the track starts. `GET /api/v1/music/status` now
lists you:

```json
[{"playerUuid":"...","zoneId":"volcano_cave","playerName":"Steve"}]
```

Silence at this step with everything above verified is almost always ReactiveMusic
missing from the client — see the warning in the [README](../README.md) and
[playback](playback.md#what-happens-without-reactivemusic).

---

## Delivery to clients

Three routes. None of them is CheeseMusic's code; all three are standard Minecraft or
third-party mechanisms, described here because the mod is useless without one of them.

### Server resource pack (`server.properties`)

Host the zip on any HTTP server and point the game at it:

```properties
resource-pack=https://files.example.com/cheesemusic-pack-1.zip
resource-pack-sha1=<sha1 of the zip>
require-resource-pack=true
```

Clients download it on join and apply it automatically. The SHA-1 must match or clients
re-download every time; change the **filename** whenever the contents change, because
clients cache aggressively by URL.

This is the least work and the most fragile: one HTTP endpoint you must keep alive, and
a vanilla size limit on the download that a long music pack can exceed.

### Modpack overrides

If your players install through a CurseForge or Modrinth pack, put the zip in the pack's
`overrides/resourcepacks/` and the pack ships with the mod. Players get it on install and
on every pack update. This scales best and is how a music-heavy server normally does it.

### A mod-sync tool

Tools that synchronise files from server to client on join (AutoModpack and its like)
can push the resource pack alongside the mod jars. Behaviour depends entirely on that
tool, not on CheeseMusic — treat anything written here about it as an observation about
how such tools are usually set up, not a specification.

---

## Licensing the audio

The repository ships no audio, and the licence on the code says nothing about what you
play through it. Most game and film soundtracks are not licensed for redistribution
inside a resource pack, however private your server feels. Commissioned, public-domain
or explicitly pack-licensed music keeps that question closed. It is between you and the
rights holder, not between you and this mod.

---

← [Getting started](getting-started.md) · [Zone music](zone-music.md) →
