[← CheeseMusic](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

CheeseMusic keeps everything in one file on the server:

```text
<server root>/config/cheesemusic.json
```

The path comes from `srv.getRunDirectory().resolve("config")` in `CheeseMusicMod.onServerStarted`,
so it follows the server's run directory, not the Fabric config directory helper. On a
client-only install (single-player), the run directory is the `.minecraft` folder, and
the file lands in `.minecraft/config/cheesemusic.json` for the integrated server.

If the file does not exist when the server starts, `CheeseMusicConfig.load` writes a
fresh one with the defaults below and an empty `assignments` object. The file is read
once, at `SERVER_STARTED`. Editing it by hand while the server runs has no effect, and
the next dashboard write will overwrite your edits — see
[Reloading](#reloading-and-write-back).

---

## Top-level keys

| Key | Type | Default | What it does |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. When `false`, `CheeseMusicMod.onTick` skips the tracker entirely, so no zone is evaluated and no packet is sent. Clients keep playing whatever they were last told to play until they leave the world. |
| `defaultFadeInTicks` | integer | `60` | Stored and exposed over the dashboard, but **not read by the playback path**. See [Keys that do nothing yet](#keys-that-do-nothing-yet). |
| `defaultFadeOutTicks` | integer | `60` | Fade-out length, in ticks, used when the assignment of the zone a player was hearing has been deleted before they leave it, so there is nothing left to read a value from. 60 ticks = 3 seconds. |
| `checkIntervalTicks` | integer | `10` | How often the server re-evaluates which zone every online player stands in. 10 ticks = twice per second. |
| `defaultVolume` | float | `0.7` | Stored and exposed over the dashboard, but **not read by the playback path**. See [Keys that do nothing yet](#keys-that-do-nothing-yet). |
| `assignments` | object | `{}` | Map of lookup key to assignment object. A key is either a CheeseGuard zone id, or `tag:<tag>` to match any zone carrying that tag. See [zone music](zone-music.md#resolution-order). |

### `checkIntervalTicks`

`ZoneMusicTracker.tick` increments a counter every server tick and returns early unless
`tickCounter % checkIntervalTicks == 0`. The value therefore sets the worst-case delay
between a player crossing a zone border and the music changing: at the default of `10`,
up to half a second.

Lower it to `1` and the zone lookup runs for every online player on every tick. Each
lookup is a reflective call into CheeseGuard (`CheeseGuardHook`), so the cost scales with
player count. Raise it past `40` and border crossings start to feel detached from the
music, because a player can walk several blocks into a new zone before the track swaps.

`CheeseMusicConfig.setCheckIntervalTicks` clamps the value to the range 1–100. That clamp
runs on the dashboard write path only. `load()` assigns the field directly, so a
hand-edited file can hold `0` — which makes `tickCounter % 0` throw an
`ArithmeticException` on the first tick. Do not hand-edit this key to `0`.

### `defaultFadeOutTicks`

Used in exactly one place, `ZoneMusicTracker.tick`, when a player's zone changes and the
key they were hearing no longer has an assignment to read a fade-out from:

```java
int fadeOut = oldAssignment != null ? oldAssignment.getFadeOutTicks() : config.getDefaultFadeOutTicks();
```

The tracker only ever remembers keys that had an assignment when it resolved them, so a
player standing in an unassigned region is hearing nothing and gets no `stop` when they
leave it. The fallback fires in one case: the assignment was deleted from the dashboard
while the player was inside. Every other `stop` reads `fadeOutTicks` from the assignment
the player is leaving. What the client does with a fade duration is
[playback](playback.md#the-fades-are-reactivemusics-fades-not-yours), and it is not what
the key names suggest.

### Keys that do nothing yet

`defaultFadeInTicks` and `defaultVolume` are loaded, saved, served by
`GET /api/v1/music/config` and accepted by `PUT /api/v1/music/config`, and then never
read again. Nothing in `ZoneMusicTracker`, `MusicAssignment` or the client package
consults them. A new assignment created through the dashboard carries its own
`fadeInTicks` and `volume`, supplied by the caller, and `MusicAssignment.fromJson` falls
back to the hard-coded literals `60` and `0.7f` rather than to these config keys.

Changing them is harmless and changes nothing audible. They are documented here because
they appear in the file and in the API, and a reader who sets `defaultVolume` to `0.2`
and hears no difference deserves to know why.

---

## Assignment objects

Each value under `assignments` is serialised by `MusicAssignment.toJson` and read back by
`MusicAssignment.fromJson`.

| Field | Type | Default when absent | What it does |
|---|---|---|---|
| `zoneId` | string | **required** | The CheeseGuard zone id this assignment binds to. `fromJson` calls `obj.get("zoneId").getAsString()` with no presence check, so an entry missing this field throws and stops the load there: the keys and assignments read before it are kept, the ones after it are dropped, and the next dashboard write saves that truncated map over the file. |
| `zoneName` | string | falls back to `zoneId` | Display label only. Shown in the dashboard; never used for matching. |
| `tracks` | array of string | `[]` | Bare track names — the `.ogg` filename without its extension, after sanitising. **No namespace prefix.** `ZoneMusicTracker.sendPlay` prepends `cheesemusic:` itself. An empty list still makes the zone resolve and still sends a `play`, but the client drops it and stays `IDLE`: whatever music was playing carries on. |
| `mode` | string | `RANDOM` | One of `RANDOM`, `SEQUENTIAL`, `SHUFFLE`. An unrecognised value is swallowed by the `catch (IllegalArgumentException ignored)` block and leaves the mode at `RANDOM`. |
| `volume` | float | `0.7` | Passed through to the client and into the `SoundInstance` volume. The vanilla music slider still applies on top. |
| `fadeInTicks` | integer | `60` | How long the client spends fading ReactiveMusic out **before** this zone's track starts. The track itself then starts at full `volume`, with no fade of its own. Floored at 20 ticks client-side. |
| `fadeOutTicks` | integer | `60` | How long the client spends fading ReactiveMusic back in **after** this zone's track is cut. Also floored at 20. |

The map key and the inner `zoneId` are written identically by the dashboard
(`config.putAssignment(zoneId, assignment)` with the same string), but nothing enforces
that they agree. Lookups go through the map key (`config.getAssignment(zoneId)`), so if
you hand-edit the file and desynchronise the two, the map key wins and `zoneId` becomes a
cosmetic field.

### `mode`

The mode string travels to the client inside the packet, and **the client decides the
order**. `ZoneMusicPlayer.pickNextIndex` is the code that matters:

- One track in the list, or zero: the mode is irrelevant. Zero tracks plays nothing.
- `SEQUENTIAL` — the list in config order, looping.
- `SHUFFLE` — the list is shuffled once when the packet arrives, then played in that
  order, and reshuffled each time it reaches the end. A proper shuffled bag.
- `RANDOM` — a uniform draw that rejects the index just played, so the same track never
  repeats back to back. With exactly two tracks it alternates, because rejecting the
  previous index leaves only one choice.

`MusicAssignment.pickTrackIndex` on the server implements the same three modes and is
never called by anything. Its `SHUFFLE` branch is a copy of `SEQUENTIAL`, which is
harmless only because the method is dead. Do not reason from it.

### Track names are not validated on write

`MusicApi` accepts whatever strings you put in `tracks`. Nothing checks that a matching
`.ogg` exists. `SoundScanner.getAvailableTracks()` holds the list of names it found on
disk and `GET /api/v1/music/tracks` serves it so the dashboard can offer a picker, but
the assignment write path never consults it. A name that does not exist produces silence
on the client, a one-line `warn` in the client log, and nothing at all on the server.

Writing a namespaced id here is a specific trap. `sendPlay` builds
`"cheesemusic:" + track`, so `arkensia:volcano` becomes `cheesemusic:arkensia:volcano`,
which `Identifier.of` rejects on the client. [Sound packs](sound-packs.md) covers how to
confirm a name is real.

---

## Reloading and write-back

| Action | Effect |
|---|---|
| Server start | File read once, in `SERVER_STARTED`. |
| Hand-edit while running | Ignored. The in-memory copy is authoritative. |
| Dashboard write (`POST`/`PUT`/`DELETE` on assignments, `PUT` on config) | Mutates the in-memory copy, then calls `config.save()`, which rewrites the whole file from memory. **Any hand-edit you made since start-up is lost at this point.** |
| Server stop | No save. `onServerStopping` only clears the tracker. |

There is no reload command and no `/reload` hook. To apply a hand-edit, restart the
server. If you intend to hand-edit a live server's file anyway, stop using the dashboard
first, because the next write there wipes it.

---

## A complete annotated file

```json
{
  "enabled": true,
  "defaultFadeInTicks": 60,
  "defaultFadeOutTicks": 90,
  "checkIntervalTicks": 10,
  "defaultVolume": 0.7,
  "assignments": {
    "spawn_plaza": {
      "zoneId": "spawn_plaza",
      "zoneName": "Spawn Plaza",
      "tracks": [
        "spawn_theme",
        "spawn_theme_night"
      ],
      "mode": "RANDOM",
      "volume": 0.7,
      "fadeInTicks": 60,
      "fadeOutTicks": 60
    },
    "volcano_cave": {
      "zoneId": "volcano_cave",
      "zoneName": "Volcano Cave",
      "tracks": [
        "volcano"
      ],
      "mode": "SEQUENTIAL",
      "volume": 0.45,
      "fadeInTicks": 20,
      "fadeOutTicks": 120
    }
  }
}
```

Reading that file top to bottom:

- `defaultFadeOutTicks` is `90`, which only matters if one of these assignments is
  deleted while a player is inside it: ReactiveMusic then gets 4.5 seconds to come back
  up. Leaving `volcano_cave` normally gives it 6 seconds, from that region's own
  `fadeOutTicks` of `120`.
- `spawn_plaza` has two tracks in `RANDOM` mode, which with two tracks alternates: an A/B
  swap each time a track ends. Every entry into the plaza starts with `spawn_theme`,
  because the client resets its index to `-1` on each new playlist and the two-track rule
  then picks index `0`.
- `volcano_cave` gives ReactiveMusic one second (`20` ticks) to fade out, then starts its
  track at once at a low volume. One track in
  `SEQUENTIAL` mode loops it indefinitely while the player stays inside.
- The three names refer to `spawn_theme.ogg`, `spawn_theme_night.ogg` and `volcano.ogg`
  sitting in `resourcepacks/CheeseMusic/assets/cheesemusic/sounds/` on the server, and to
  the same pack installed on every client. CheeseMusic ships no audio of its own.

Both `defaultFadeInTicks` and `defaultVolume` are present because `save()` always writes
them. They have no effect.

---

← [Playback](playback.md) · [Dashboard API](dashboard.md) →
