[← CheeseMusic](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](zone-music.fr.md)

# Zone music

The server side of the mod, in one sentence: every few ticks, work out which region each
player stands in, and when that answer changes, tell their client to start or stop a
playlist. This page covers the model, the resolution rules, the lifecycle and the packet.
What the client then does with the packet is [playback](playback.md).

---

## The model

Three objects, no database.

**A zone** is a CheeseGuard region. CheeseMusic does not define, store or edit zones — it
reads them through reflection, as `CheeseGuardHook.ZoneInfo(id, name, priority, tags)`.
Create and shape your regions with CheeseGuard's own tools; CheeseMusic only ever asks
"which regions contain this point".

**An assignment** (`MusicAssignment`) binds a lookup key to a playlist. Its fields are
the zone id, a display name, the track list, the play mode, a volume and two fade
durations. [Configuration](configuration.md#assignment-objects) lists them with types and
defaults. Assignments live in `config/cheesemusic.json` and nowhere else.

**A player state** (`ZoneMusicTracker.PlayerMusicState`) is in-memory only: which key the
player is currently hearing, and whether they are in a Cobblemon battle. It is created on
first tick, dropped on join and on disconnect, and never written to disk. A restart means
every online player gets re-evaluated from scratch, which is harmless — they receive a
fresh `play` packet within half a second of the server coming back.

A lookup key is not always a zone id. It is either:

- a **zone id**, matching one region exactly, or
- `tag:<tag>`, matching any region that carries that CheeseGuard tag.

Both forms sit in the same `assignments` map. `POST /assignments` files an entry under its
own `zoneId`, so a tag assignment is created with `"zoneId": "tag:cave"` and the key and
the field agree. They can only drift apart through `PUT /assignments/:zoneId` with a
different `zoneId` in the body, or a hand edit; the key is what lookups use.

---

## Resolution order

`ZoneMusicTracker.resolveZoneMusic(worldId, x, y, z)` turns a position into a lookup key,
or `null` for silence:

1. If `CheeseGuardHook.isAvailable()` is false, return `null` immediately. No CheeseGuard
   means no music, ever, for anyone.
2. Ask CheeseGuard for every region containing the point:
   `getRegionsAt(worldId, x, y, z)`. `worldId` is the full dimension id,
   `minecraft:overworld` and the like.
3. Walk that list **in the order CheeseGuard returned it**. For each region:
   - if `assignments` has an entry under the region's id, return that id;
   - otherwise, for each of the region's tags in order, if `assignments` has an entry
     under `tag:<tag>`, return `tag:<tag>`.
4. Nothing matched: return `null`.

So a direct zone assignment beats a tag assignment **on the same region**, but a tag
assignment on an earlier region beats a direct assignment on a later one. The order of
regions is whatever CheeseGuard's `getRegionsAt` produces: by priority, highest first.

### Priority comes from CheeseGuard's ordering

`ZoneInfo` carries CheeseGuard's `priority`, and `GET /api/v1/music/zones` reports it.
`resolveZoneMusic` never sorts on it itself, and does not need to: CheeseGuard's
`RegionManager.getRegionsAt` returns the regions sorted by `priority`, highest first. So
among overlapping regions, the highest-priority one that has a matching assignment
(direct or by tag) wins. Regions of equal priority keep CheeseGuard's storage order,
which you do not control; give overlapping regions with different music different
priorities. The dependency is implicit — if CheeseGuard ever stopped sorting, this mod
would follow without notice — and it is listed again in
[architecture](architecture.md#known-weaknesses).

---

## The tick loop

`CheeseMusicMod.onTick` is registered on `ServerTickEvents.END_SERVER_TICK` and does
nothing unless both the tracker exists and `config.isEnabled()`. Then
`ZoneMusicTracker.tick`:

```java
tickCounter++;
if (tickCounter % config.getCheckIntervalTicks() != 0) return;
```

Every `checkIntervalTicks`-th tick, it iterates the whole online player list and calls
`tickPlayer` on each, with a per-player `try`/`catch` that logs at `debug` level. One
player whose lookup throws does not stop the others.

`tickPlayer`, in order:

1. Ask `CobblemonBattleHook.isInBattle(player)`, inside its own `try`/`catch`.
2. If in battle: if the player currently has music and was not already flagged as
   battling, send a `stop` with a **hard-coded fade of 40 ticks**, set the flag, and
   return. `currentZoneId` is deliberately left intact.
3. If the flag was set and the player is no longer in battle, clear the flag and set
   `currentZoneId = null`. That forces the next comparison to see a change, so the zone
   music restarts after the fight.
4. Resolve the key from the player's current dimension and block position.
5. If the key differs from `currentZoneId`:
   - if there was a previous key, send a `stop`, with the fade-out taken from the old
     assignment, or from `defaultFadeOutTicks` when there was no assignment for it;
   - if the new key is non-`null` and has an assignment, send a `play`;
   - store the new key.

Step 5 is the only place a packet is sent during normal movement. A player who stands
still, or who moves inside one region, generates no network traffic at all.

### Join and disconnect

`ServerPlayConnectionEvents.JOIN` removes the player's state, and so does `DISCONNECT`.
Removing it on **join** matters: a reconnecting player's client has reset its own
`ZoneMusicPlayer` to `IDLE`, so the server must forget what it thought the player was
hearing, or it would compare the new position against a stale key and send nothing.

`SERVER_STOPPING` clears every state. No packet is sent, because the clients are about to
be disconnected anyway and will reset themselves.

---

## Battle suppression

With Cobblemon installed, `CobblemonBattleHook` locates `BattleRegistry`'s single-`UUID`
lookup method reflectively and treats a non-`null` return as "in battle". While that
holds, zone music is stopped and no new zone is resolved, which leaves Cobblemon's own
battle music the only thing playing.

Two things to know about this hook. It picks its method by scanning
`BattleRegistry.getDeclaredMethods()` for one taking a single `UUID` and returning a
non-`void`, preferring a name containing both `battle` and `player`, and falling back to
*any* single-`UUID` method returning a reference type. The fallback is a guess; if
Cobblemon ever grows a second such method that is declared earlier, the hook silently
binds to the wrong one. The log line names which method it chose, and it is the only way
to tell:

```text
[CheeseMusic] Cobblemon detected — battle detection via getBattleByParticipatingPlayerId()
```

And the fade on the battle stop is the literal `40` in `sendStop(player, 40)`. It is not
configurable and it ignores the assignment's own `fadeOutTicks`. Two seconds was chosen
to get out of the way of Cobblemon's battle intro; if you want a different value, change
that line.

Without Cobblemon, `isInBattle` returns `false` always and this whole branch is dead
weight.

---

## The packet

One payload type, `cheesemusic:zone_music`, registered server-to-client only
(`PayloadTypeRegistry.playS2C()`). Four fields, written in order:

| Field | Type | Meaning |
|---|---|---|
| `action` | string | `play`, `stop` or `fade_to` |
| `tracksJson` | string | a JSON object for `play`; empty string for the others, which the reader turns back into `null` |
| `volume` | float | the assignment's volume; `0` on a `stop` |
| `fadeTicks` | int | `fadeInTicks` on a `play`, the resolved fade-out on a `stop` |

`tracksJson` for a `play` is built by `sendPlay` and looks like this:

```json
{"tracks":["cheesemusic:spawn_theme","cheesemusic:spawn_theme_night"],"mode":"RANDOM"}
```

The `cheesemusic:` prefix is added here, one entry at a time, which is why an assignment
stores bare names. Sending JSON as a string inside a binary payload is not elegant, but it
keeps the codec to four primitive writes and lets the playlist grow without a protocol
change.

`fade_to` is handled on the client — `ZoneMusicPlayer.fadeTo` sets the target volume and
nothing else, because a streaming sound instance cannot be smoothly re-gained. **No
server code ever sends it.** It is dead on arrival, kept because a future per-zone volume
ramp would use it.

A client without CheeseMusic installed has no receiver registered for the payload id and
drops the packet. The server neither knows nor cares, so a mixed-client server works —
the players without the jar just hear vanilla music.

---

## Edge cases worth knowing

**Two players, one border.** State is strictly per-player. Two players on opposite sides
of a region edge get different packets on the same tick, and hear different tracks. This
is the whole point of doing it per-player rather than through a world-level sound.

**Dimension change.** The dimension id is part of the resolution input, so a teleport to
the Nether re-resolves on the next check. If the destination has no assigned region, the
player gets a `stop`; the fade-out comes from the region they left.

**An assignment deleted while someone is listening.** The next check resolves a different
key (or `null`), and the stop fade falls back to `defaultFadeOutTicks`, because
`config.getAssignment(oldKey)` now returns `null`. The zone track is still cut at once;
the value only sets how long ReactiveMusic takes to come back. This is the one case where
`defaultFadeOutTicks` is read.

**An empty `tracks` list.** The server still sends a `play`. The client parses an empty
playlist and returns before leaving `IDLE`: no fade, no suppression. Vanilla music or
ReactiveMusic simply carries on, as if the region had no assignment. Not an error, and
not silence either.

**`lastPos` and `lastSendTime`.** `lastPos` is written on every check, `lastSendTime` on
every zone change, and neither is read anywhere. They
look like the start of a movement-based debounce that was never finished.

---

← [Sound packs](sound-packs.md) · [Playback](playback.md) →
