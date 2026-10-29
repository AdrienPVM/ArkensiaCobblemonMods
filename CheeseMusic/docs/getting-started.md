[← CheeseMusic](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this page you will walk across an invisible line in your world and hear a
track you chose start playing, while a player standing two blocks behind you hears
something else. Roughly thirty minutes, most of it spent converting audio and getting a
resource pack onto a client.

What you need before starting: a Fabric 1.21.1 server you can restart and copy files to,
operator rights on it, one music file you are allowed to use, and a client you can install
mods into.

---

## 1. Build and install four mods

CheeseMusic on its own does nothing visible. The working set is CheeseMusic, CheeseGuard
(regions), CheeseDash (the editor) and ReactiveMusic (client-side, see
[installation](installation.md#reactivemusic)).

```bash
cd CheeseDash   && ./gradlew build
cd ../CheeseGuard && ./gradlew build
cd ../CheeseMusic && ./gradlew build
```

CheeseDash first — CheeseMusic compiles against its jar. Copy the three jars to the
server:

```bash
cp CheeseDash/build/libs/cheesedash-*+1.21.1.jar   /srv/mc/mods/
cp CheeseGuard/build/libs/cheeseguard-*+1.21.1.jar /srv/mc/mods/
cp CheeseMusic/build/libs/cheesemusic-*+1.21.1.jar /srv/mc/mods/
```

Skip any `-sources.jar`. On your client, install Fabric API, ReactiveMusic and the same
`cheesemusic-1.0.0+1.21.1.jar`.

**Expect to see:** three jars in `mods/`, no `-sources` among them.

## 2. Start the server and read the log

```bash
cd /srv/mc && java -Xmx4G -jar fabric-server-launch.jar nogui
```

**Expect to see**, among the start-up lines:

```text
[CheeseMusic] Initialized successfully!
[CheeseMusic] Created resource pack at: /srv/mc/resourcepacks/CheeseMusic
[CheeseMusic] Place .ogg files in: /srv/mc/resourcepacks/CheeseMusic/assets/cheesemusic/sounds
[CheeseMusic] Found 0 tracks in resource pack
[CheeseMusic] CheeseGuard detected — zone music enabled
[CheeseMusic] Server started — 0 assignments loaded
```

`CheeseGuard not installed — zone music disabled` instead of that second-to-last line
means CheeseGuard's jar is missing or failed to load. Fix that before going on; nothing
downstream can work without it. Other variants are listed in
[installation](installation.md#proving-it-loaded).

The server has now written `config/cheesemusic.json` and the empty resource pack.

## 3. Get a dashboard session

Find your master token in `config/cheesedash.json`, then:

```bash
TOKEN=$(curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"token":"<your master token>"}' | jq -r .sessionId)
echo $TOKEN
```

**Expect to see:** a string starting with `ses_`. Every command below reuses `$TOKEN`.
You can do all of this from the CheeseDash web UI at `http://localhost:44311` instead; the
`curl` form is used here because it shows exactly what is happening.

## 4. Create a region

In game, as an operator, mark two opposite corners of a small area — a room, a courtyard,
anything you can walk in and out of in a few seconds:

```mcfunction
/cg wand
```

Left-click one corner, right-click the other. Or skip the wand and stand in each corner:

```mcfunction
/cg pos1
/cg pos2
/cg create Volcano Cave
```

`create` takes a greedy name, so spaces need no quoting. CheeseGuard derives the region id
from the name.

```mcfunction
/cg list
```

**Expect to see:** your region listed, with its id in brackets. Write that id down — the rest of
this page assumes `volcano_cave`.

Cross-check from the dashboard, which is what CheeseMusic itself sees:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/zones
```

```json
[{"id":"volcano_cave","name":"Volcano Cave","priority":0,"tags":[]}]
```

An empty `[]` here with the region visible in `/cg list` means the reflective
CheeseGuard hook broke — see
[troubleshooting](troubleshooting.md#get-apiv1musiczones-returns-an-empty-array-but-cg-list-shows-regions).

## 5. Convert your audio to Ogg Vorbis

Minecraft plays Ogg Vorbis and nothing else.

```bash
ffmpeg -i "Volcano Theme.wav" -c:a libvorbis -q:a 5 -ar 44100 "Volcano Theme.ogg"
```

**Expect:** a file well under 50 MB, and longer than five seconds — a shorter track hits
the restart loop described in [playback](playback.md#the-100-tick-cooldown).

## 6. Upload the track

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  --data-binary "@Volcano Theme.ogg" \
  "http://localhost:44311/api/v1/music/tracks/upload?name=Volcano%20Theme.ogg"
```

```json
{"ok":true,"track":"volcano_theme"}
```

**Expect to see:** the sanitised name in the `track` field. That is the only name that
works from here on — `Volcano Theme` does not exist as far as the game is concerned. The
upload also rewrote `sounds.json`, with no restart needed.

Confirm:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/tracks
```

```json
["volcano_theme"]
```

## 7. Put the resource pack on your client

The step that trips everyone, because the mod cannot do it for you. The server's pack is
at `/srv/mc/resourcepacks/CheeseMusic/`. Zip its **contents**, so that `pack.mcmeta` is at
the root of the zip and not inside a folder:

```bash
cd /srv/mc/resourcepacks/CheeseMusic && zip -r ~/cheesemusic-pack.zip .
```

Copy that zip into your client's `.minecraft/resourcepacks/`, then enable it in
**Options → Resource Packs**.

Now verify, in game:

```mcfunction
/playsound cheesemusic:volcano_theme music @s
```

**Expect to hear:** your track. This is the checkpoint of the whole tutorial — if it fails
here, no assignment will ever work. *No sound with that name* means the client cannot
resolve the event: the pack is not enabled, or `pack.mcmeta` is nested one directory too
deep inside the zip. [Sound packs](sound-packs.md#delivery-to-clients) covers the three
real distribution routes for when you move past testing on your own client.

Stop the sound before continuing:

```mcfunction
/stopsound @s music cheesemusic:volcano_theme
```

## 8. Assign the track to the region

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"zoneId":"volcano_cave","zoneName":"Volcano Cave","tracks":["volcano_theme"],"mode":"SEQUENTIAL","volume":0.6,"fadeInTicks":40,"fadeOutTicks":80}' \
  http://localhost:44311/api/v1/music/assignments
```

**Expect to see:** the assignment echoed back. `config/cheesemusic.json` now holds it, and
the change is live — no restart, no reload command.

The `tracks` entry is `volcano_theme`, with no `cheesemusic:` prefix. The server adds the
prefix when it sends the packet; adding it yourself produces an invalid id and silence.

## 9. Walk in

Stand outside the region. Walk in.

**Expect to hear:** ReactiveMusic fade out over two seconds (40 ticks), then your track
start. The server re-checks positions every ten ticks, so the transition begins within
half a second of crossing the border.

Confirm the server agrees:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/status
```

```json
[{"playerUuid":"069a79f4-...","zoneId":"volcano_cave","playerName":"Steve"}]
```

Appearing in that list means the server sent the `play` packet. Silence while listed means
the problem is client-side, and almost always ReactiveMusic missing — start at
[troubleshooting](troubleshooting.md#i-walk-into-an-assigned-region-and-hear-nothing-at-all).

Walk out. The track cuts and ReactiveMusic rises again over four seconds (80 ticks). The
cut is abrupt by design: fade durations move ReactiveMusic's volume, never
CheeseMusic's own track. If you want the track to fade, fade it inside the `.ogg`. The
reasoning is in [playback](playback.md#the-fades-are-reactivemusics-fades-not-yours).

## 10. Add a second track and a play mode

```bash
curl -s -X PUT -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"zoneId":"volcano_cave","zoneName":"Volcano Cave","tracks":["volcano_theme","volcano_calm"],"mode":"SHUFFLE","volume":0.6,"fadeInTicks":40,"fadeOutTicks":80}' \
  http://localhost:44311/api/v1/music/assignments/volcano_cave
```

Upload `volcano_calm` first, the same way as step 6. `PUT` is a full replace, not a patch —
every field you omit falls back to its default rather than keeping its old value.

**Expect:** if you were already inside the region, nothing audible changes. The current
track finishes, and the **next** one comes from the new list. That is deliberate: editing
an assignment never interrupts a player.

## Where to go next

- One track across many regions at once: give the regions a CheeseGuard tag and assign
  `tag:<tag>` instead of an id — [zone music](zone-music.md#resolution-order).
- Tuning the check interval and the global fades — [configuration](configuration.md).
- Getting the pack onto thirty players instead of one —
  [sound packs](sound-packs.md#delivery-to-clients).

---

← [Installation](installation.md) · [Sound packs](sound-packs.md) →
