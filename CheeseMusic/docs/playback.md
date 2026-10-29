[← CheeseMusic](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](playback.fr.md)

# Playback

Everything on this page runs on the client. The server's job ends when the packet leaves
([zone music](zone-music.md#the-packet)); from there a single `ZoneMusicPlayer` instance
per client decides what is audible.

The design constraint that shapes all of it: **three music sources must never overlap**.
Vanilla's `MusicTracker`, ReactiveMusic, and CheeseMusic's own track all want the same
channel. Rather than mixing them, the player runs a state machine that silences the other
two, waits for confirmation that they are silent, and only then starts its own track.

---

## The state machine

Four states, in `ZoneMusicPlayer`:

```text
IDLE ──play──> FADING_RM_OUT ──RM silent──> PLAYING
  ^                  │                        │
  │                  └──────stop──────┐       │stop
  │                                   v       v
  └────RM restored──── FADING_RM_IN <─────────┘
```

| State | What is audible | What it is waiting for |
|---|---|---|
| `IDLE` | vanilla music, or ReactiveMusic | a `play` packet |
| `FADING_RM_OUT` | ReactiveMusic, fading to zero | `ReactiveMusicHook.isFadeOutDone()` |
| `PLAYING` | one zone track | the track to end, or a packet |
| `FADING_RM_IN` | ReactiveMusic, rising from zero | `ReactiveMusicHook.isFadeInDone()` |

`isActive()` is `state != IDLE`, and that single boolean is what the
[mixin](#the-vanilla-music-mixin) reads. So vanilla music is suppressed through the whole
transition, not only while a track plays.

### Entering a region

On a `play` in `IDLE`:

1. Parse the playlist. An empty list aborts the whole thing and leaves the state `IDLE`.
2. Store the volume.
3. `ReactiveMusicHook.startFadeOut(fadeTicks)`.
4. `MinecraftClient.getInstance().getMusicTracker().stop()`, inside a `try` that swallows
   everything.
5. Go to `FADING_RM_OUT`.

Then on each client tick, once `isFadeOutDone()` is true, pick a track, play it, arm a
cooldown and go to `PLAYING`. If the playlist turned out to have no playable index, fade
ReactiveMusic back in instead and go to `FADING_RM_IN`.

A `play` arriving while already in `FADING_RM_OUT` or `PLAYING` does not restart the
transition. It replaces the playlist and volume, so the **next** track obeys the new
assignment while the current one finishes. In practice the server never takes this path:
crossing from one assigned region straight into another sends a `stop` for the old key
and then a `play` for the new one, in the same tick. The client cuts the track, starts
fading ReactiveMusic back in, and the `play` lands in `FADING_RM_IN` (next paragraph).
Editing an assignment from the dashboard sends no packet at all: players already inside
keep the old playlist until they next enter the region.

A `play` arriving in `FADING_RM_IN` — a player who left a region and walked straight back
in — is stashed as `pendingTracksJson` and replayed once the fade-in finishes. The
transition completes in full before the new one starts. Audibly, that means a brief
return of ReactiveMusic. Not pretty, but it keeps the state machine single-threaded in its
reasoning.

### Leaving a region

On a `stop`:

- from `PLAYING`: cut the zone track, `startFadeIn(max(fadeTicks, 20))`, go to
  `FADING_RM_IN`;
- from `FADING_RM_OUT`: the zone track never started, so just reverse — fade back in and
  go to `FADING_RM_IN`;
- from `FADING_RM_IN` or `IDLE`: nothing.

The zone track is **cut, not faded**. `stopZoneMusic()` calls `SoundManager.stop` on the
instance and the audio ends at that instant.

### The fades are ReactiveMusic's fades, not yours

This is the single most misread part of the mod, so it is worth being blunt.

`fadeInTicks` and `fadeOutTicks` never fade a CheeseMusic track. They set how long
ReactiveMusic takes to get out of the way, and how long it takes to come back. The zone
track itself starts abruptly at full `volume` and stops abruptly.

Three further details follow from the code:

- Both are floored at 20 ticks: `Math.max(fadeTicks, 20)`. A value of `5` behaves as `20`.
- `play` stores its own `fadeTicks` into the field called `fadeOutTicks`, with the comment
  *"We'll use the same value for both directions as approximation"*. The field is then
  used only on one failure path. The real fade-out duration is the one the server sends on
  the `stop`, which does come from the assignment's `fadeOutTicks`. The naming inside the
  class is misleading; the end-to-end behaviour is right.
- `ZoneMusicSoundInstance` has a `setMusicVolume` method built for smooth gain changes.
  Nothing calls it. The volume is fixed for the life of a track.

So if you want a track to fade in, fade it in inside the `.ogg` file. The mod will not do
it for you.

---

## Playlist advance

While `PLAYING`, the player watches `SoundManager.isPlaying(currentSound)` and advances
when it goes false. `pickNextIndex` implements the three modes, as described in
[configuration](configuration.md#mode). `SHUFFLE` additionally reshuffles the list when it
reaches the end, so a bag is never replayed in the same order.

### The 100-tick cooldown

A freshly started streaming sound is not reported as playing immediately, and a resource
pack reload can kill it outright. `trackStartCooldown = COOLDOWN_TICKS` (100 ticks, five
seconds) covers that window:

- during the cooldown, `isPlaying` is not consulted at all;
- on the tick the cooldown hits zero, if the sound is **not** playing, the same track is
  restarted and the cooldown is re-armed.

That guard has a consequence. A track shorter than five seconds has already finished by
the time the cooldown expires, so it is restarted — forever. Keep zone tracks well over
five seconds. A short stinger belongs in a `/playsound`, not in an assignment.

No cooldown is applied between playlist tracks, only on the first start after a
transition, which means a dead sound mid-playlist is not recovered.

---

## ReactiveMusic cooperation

`ReactiveMusicHook` is reflection against ReactiveMusic's internals:

| Member | Class | Use |
|---|---|---|
| `thread` (static field) | `circuitlord.reactivemusic.ReactiveMusic` | the player thread object |
| `gainPercentage` (field) | the thread's class | the volume CheeseMusic ramps |
| `resetPlayer()` (method) | the thread's class | drop ReactiveMusic's current song |
| `additionalSilence` (static field) | `ReactiveMusic` | set to `999999` to stop it picking a new song |

Fade-out saves the current gain and the current `additionalSilence`, sets
`additionalSilence` to `999999`, then ramps the gain down by `savedGain / ticks` per tick.
When it reaches zero it calls `resetPlayer()` to kill the song outright.

Fade-in restores `additionalSilence`, calls `resetPlayer()` so a fresh song is chosen,
then ramps the gain back up to the saved value.

Between fades, while `suppressed` is still true, every tick re-asserts
`additionalSilence = 999999` and `gainPercentage = 0`. ReactiveMusic resets its own gain
when it picks a song, so without that re-assertion it would claw its volume back.
`forceReset()` on disconnect restores both fields in one step, with no ramp.

Every reflective access is wrapped so a failure logs at most a warning. `isAvailable()`
re-attempts initialisation on each call until it succeeds, because ReactiveMusic's
`thread` field is null until its own player starts — a one-shot init at client start
would miss it.

### What happens without ReactiveMusic

The honest answer: **nothing plays, and vanilla music stays off.**

Trace it. `ReactiveMusicHook.startFadeOut` begins with `if (!isAvailable()) return;`, so
with ReactiveMusic absent it does nothing, and in particular never sets `suppressed`.
`ZoneMusicPlayer` has already moved to `FADING_RM_OUT` and now waits for:

```java
public static boolean isFadeOutDone() {
    return suppressed && !fading && currentGain <= 0.001f;
}
```

`suppressed` is false and nothing else can set it. The wait never ends. The player stays
in `FADING_RM_OUT`, which means `isActive()` stays true, which means the mixin keeps
cancelling `MusicTracker.tick`. The outcome for a player:

- walking into an assigned region plays no track at all;
- vanilla music never comes back, because the mixin suppresses it for as long as the
  player is stuck;
- a `stop` packet (walking back out) does rescue it — `stop` in `FADING_RM_OUT` goes to
  `FADING_RM_IN`, and `isFadeInDone()` is `!suppressed && !fading`, which is immediately
  true, so the state returns to `IDLE` and vanilla music resumes.

So the symptom is not permanent silence in every case — it is silence in every assigned
region, plus silence for as long as you stay in one. A disconnect also clears it, via
`reset()`.

⚠️ Install ReactiveMusic on every client, or expect this. This has not been verified in
play; it is read off the two classes named above. But there is no branch that avoids it.
Making the mod work without ReactiveMusic means making `isFadeOutDone()` return true when
the hook is unavailable — a one-line change that nobody has made, and which should be
tested rather than assumed.

---

## The vanilla music mixin

`MusicTrackerMixin` is listed under `client` in `cheesemusic.mixins.json`, so it is never
applied on a dedicated server.

```java
@Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
```

At the head of `MusicTracker.tick`, if `ZoneMusicPlayer.isActive()`, it stops whatever
sound the tracker currently holds and cancels the tick. Vanilla therefore neither
continues its current music nor starts new music while zone music is active or
transitioning.

`require = 0` — repeated in the mixin config as `"defaultRequire": 0` — means the game
does not crash if the injection point cannot be found. On a Minecraft version where
`MusicTracker.tick` has changed shape, the mixin silently does nothing and vanilla music
plays on top of zone music. Quiet failure over a crash is the right call for a cosmetic
feature, and it is also why you should check for double music after any version bump.

---

← [Zone music](zone-music.md) · [Configuration](configuration.md) →
