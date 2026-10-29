[← CheeseMusic](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

For whoever maintains this next. Sixteen source files, about 1 710 lines, no persistence
layer beyond one JSON file, and three reflective hooks into mods that are not compile-time
dependencies.

---

## Package layout

```text
com.cheesemusic
├── CheeseMusicMod.java         ModInitializer. Lifecycle wiring, holds the singletons
├── CheeseMusicConfig.java      the config model, its load and its save
├── MusicAssignment.java        one zone's playlist, with JSON in and out
├── PlayMode.java               RANDOM | SEQUENTIAL | SHUFFLE
├── network
│   └── MusicPayload.java       the single S2C packet, action + JSON + volume + ticks
├── logic                       server side
│   ├── ZoneMusicTracker.java   per-player state, the tick loop, packet dispatch
│   ├── SoundScanner.java       the server's resource pack: scan, sanitise, sounds.json
│   ├── CheeseGuardHook.java    reflection into CheeseGuard's RegionManager
│   └── CobblemonBattleHook.java reflection into Cobblemon's BattleRegistry
├── client                      client side
│   ├── CheeseMusicClient.java  ClientModInitializer, packet receiver, tick hook
│   ├── ZoneMusicPlayer.java    the four-state playback machine (the largest class)
│   ├── ZoneMusicSoundInstance.java  an AbstractSoundInstance on the MUSIC channel
│   └── ReactiveMusicHook.java  reflection into ReactiveMusic, gain ramping
├── mixin
│   └── MusicTrackerMixin.java  client-only, suppresses vanilla music
└── dashboard
    ├── CheeseMusicDashboardModule.java  the CheeseDash entrypoint, prefix "music"
    └── MusicApi.java           the REST dispatch
```

The split is strictly by side. Nothing under `logic` is touched by the client, nothing
under `client` by the server, and `network`, plus the four top-level model classes, are
the only shared code. `dashboard` is server-only too, and is the only package that imports
`com.cheesedash`.

## What each class owns

**`CheeseMusicMod`** holds the three singletons — config, tracker, scanner — plus the
`MinecraftServer` reference, and is itself a static singleton through `INSTANCE`.
Registration of the payload type happens in `onInitialize`, because a payload must be
registered before any connection exists. Everything else is deferred to `SERVER_STARTED`,
where the run directory is known.

**`CheeseMusicConfig`** is both the model and its own serialiser. `save()` hand-builds the
`JsonObject` rather than reflecting over the fields, which is why the `transient
configPath` never leaks into the file. The setters clamp; `load()` does not. That
asymmetry is deliberate for the dashboard and a hazard for hand-editing — see
[configuration](configuration.md#checkintervalticks).

**`MusicAssignment`** is a mutable bean with setters that nothing calls — the API replaces
whole objects instead of mutating them. It would be a better `record`, and its
`pickTrackIndex` is dead code duplicating the client's own picker.

**`ZoneMusicTracker`** owns `Map<UUID, PlayerMusicState>` and is the only class that sends
packets. Its state is intentionally not persisted.

**`SoundScanner`** owns the directory `resourcepacks/CheeseMusic/` and the generated
`sounds.json`. It is the only class that renames the user's files.

**`ZoneMusicPlayer`** is a static singleton on the client, holding the state machine, the
playlist, the current `SoundInstance` and a pending play request. One instance per game
process, which is correct — a client is in one world at a time.

**`ReactiveMusicHook`** is entirely static and holds the saved ReactiveMusic gain and
silence values across a fade cycle. That static state is the reason a failed
`startFadeIn` can leave ReactiveMusic muted until the next disconnect.

---

## State and when it hits disk

| State | Where it lives | Written when |
|---|---|---|
| Global config and all assignments | `config/cheesemusic.json` | on every dashboard write, and once at first start if the file is absent. Never on shutdown |
| Track files | `resourcepacks/CheeseMusic/assets/cheesemusic/sounds/*.ogg` | on upload; renamed on every scan |
| `sounds.json` | the same pack | regenerated on every scan, which means every start and every upload or delete |
| Per-player current zone | memory, `ZoneMusicTracker.states` | never |
| Client playback state | memory, `ZoneMusicPlayer` | never |

Config writes are whole-file rewrites through `Files.writeString`, with no temp file and
no atomic move. A crash mid-write truncates the config. Assignments are cheap to rebuild,
so this has not been worth fixing, but it is worth knowing before you point it at anything
you care about.

There is no reload path at all. `load` is called once, from `SERVER_STARTED`.

---

## Threading

Four contexts, and the hand-offs between them are the part to get right.

**Server tick thread.** `ZoneMusicTracker.tick`, every `checkIntervalTicks` ticks. Reads
player positions, calls into CheeseGuard and Cobblemon reflectively, and sends packets
through `ServerPlayNetworking.send`. All of this is correct on this thread and only on
this thread.

**CheeseDash HTTP thread.** `MusicApi.handle`, one request at a time per connection, on
`com.sun.net.httpserver`'s executor. It mutates `CheeseMusicConfig` and calls
`SoundScanner`, both of which the tick thread reads concurrently. **There is no
synchronisation and no hand-off to the server thread.** No
`WebDashboard.executeOnServerThread` call exists in this mod.

That is a real data race. In practice it is mild: `assignments` is a
`LinkedHashMap` read by the tick thread and written by the HTTP thread, so a write during
a read can in principle throw or return a stale value. The tick loop's per-player
`try`/`catch` swallows whatever comes of it, and the next check half a second later sees a
consistent map. `getAllAssignments()` returns an unmodifiable *view*, not a copy, so an
iteration on the HTTP thread during a write on the same thread is the only case that is
structurally safe.

Any mutation of Minecraft state from the HTTP thread would be a genuine bug — and there is
none, because the API only touches the config and the filesystem. If you add an endpoint
that teleports a player or plays a sound, route it through
`WebDashboard.executeOnServerThread`.

**Client network thread.** The payload receiver, which immediately does
`context.client().execute(...)` to hop onto the render thread. Correct: `ZoneMusicPlayer`
and `SoundManager` are render-thread-only.

**Client render thread.** `ZoneMusicPlayer.tick` from `END_CLIENT_TICK`, the mixin from
`MusicTracker.tick`, and the deferred packet handling. Single-threaded, no locking needed.

A fifth, half-visible context: `ReactiveMusicHook` writes `gainPercentage` on a field
belonging to ReactiveMusic's **own player thread**, from the render thread, non-volatile.
A write might be seen a few milliseconds late. For a volume ramp that does not matter, and
the tick loop re-asserts the value anyway.

---

## The mixin

One mixin, client-only, declared under `"client"` in `cheesemusic.mixins.json` with
`"defaultRequire": 0`.

| Mixin | Target | Injection | Why |
|---|---|---|---|
| `MusicTrackerMixin` | `net.minecraft.client.sound.MusicTracker` | `@Inject` at `HEAD` of `tick`, `cancellable`, `require = 0` | Vanilla would otherwise start its own background music over a zone track. The mixin stops the tracked sound and cancels the tick while `ZoneMusicPlayer.isActive()` |

It `@Shadow`s the private field `current` and the public method `stop()`. Both are stable
across 1.21.x, and `require = 0` means a rename degrades to "the mixin does nothing"
rather than a crash. The visible symptom of that degradation is two pieces of music at
once, which is also the first thing to check after a Minecraft version bump.

Suppressing vanilla through a mixin while suppressing ReactiveMusic through reflection is
inconsistent. The reason is that ReactiveMusic needs a *ramp*, which a cancel cannot
express, and vanilla needs only an on/off.

---

## Extension points

**A new resolution rule.** `ZoneMusicTracker.resolveZoneMusic` is the whole of the
position-to-playlist logic, in fifteen lines. Time of day, weather or a player's team
would go here, as another key form alongside `tag:`.

**A new packet action.** `MusicPayload.action` is a free-form string and the client
dispatches on it with a `switch`. `fade_to` already exists on the client and is never
sent; wiring it up is a two-line change in the tracker.

**A dashboard route.** Add a branch to `MusicApi.handle`. Follow the existing shape:
guard writes with `if (!WebDashboard.requireWrite(exchange)) return;` as the first
statement, validate before mutating, and call `config.save()` after. That guard is
defence in depth rather than the boundary — CheeseDash's module dispatch already demands
`OPERATOR` for any non-`GET` request before `MusicApi` is reached. Two things the dispatch
will not do for you: it keys only on the HTTP method, so a mutating `GET` would arrive
unguarded; and it never raises the bar above `OPERATOR`, so a route that deserves
`SUPER_ADMIN` must call `WebDashboard.requireAdmin` itself, in the same `if (!…) return;`
form.

**A WebSocket channel.** Nothing exists today. `ZoneMusicTracker.tickPlayer` is where a
zone-change broadcast belongs, since it is already the single choke point for every
transition.

**A second music source.** `ReactiveMusicHook` is the template: a static class, a
`tryInit` that catches `ClassNotFoundException` separately from everything else, an
`isAvailable()` that retries, and every member access wrapped. Copy that shape rather than
inventing a new one.

---

## Known weaknesses

Candidly, in rough order of how likely each is to bite you.

**ReactiveMusic is effectively required.** The client state machine waits on a flag only
the ReactiveMusic hook can set. Without that mod, an assigned region plays nothing and
vanilla music stays suppressed until the player leaves or disconnects. Full trace in
[playback](playback.md#what-happens-without-reactivemusic). This is the most serious
defect in the mod, and the fix is roughly one line.

**Nothing here has been exercised in play**, as far as the repository shows. Several of the
items on this list are the sort a single evening of real use would surface.

**Three reflective hooks against mod internals.** ReactiveMusic's `thread`,
`gainPercentage`, `additionalSilence` and `resetPlayer`; Cobblemon's `BattleRegistry`;
CheeseGuard's `RegionManager`, `getStorage` and `Region` accessors. Each will break when
that mod changes shape, and each fails quietly — logged at `info`, `warn` or `debug`, never
thrown. Quiet failure is right for a cosmetic feature and also means a breakage can go
unnoticed for months. The start-up log lines are the only early warning; read them after
every dependency update.

**Cobblemon's battle method is found by guesswork.** The first pass filters for a name
containing both `battle` and `player`; the fallback accepts *any* single-`UUID` method
returning a reference type. If the preferred name disappears, the fallback can bind to an
unrelated method and battle detection becomes nonsense without any error. The chosen
method name is logged, which is the only way to check.

**CheeseGuard priority is ignored.** `resolveZoneMusic` takes the first assigned region in
CheeseGuard's iteration order and never consults the `priority` it already has in hand.
Overlapping assigned regions resolve unpredictably from this mod's point of view.

**No synchronisation between the HTTP thread and the tick thread**, as described above.

**Config writes are not atomic**, and a hand-edit is silently discarded by the next
dashboard write.

**`checkIntervalTicks` can be set to `0` by hand**, which makes `tickCounter % 0` throw an
`ArithmeticException` on the first tick after start-up. The clamp lives only in the setter.

**A track shorter than five seconds restarts forever**, because the 100-tick start cooldown
expires after the sound has already finished and the recovery path treats that as a dead
sound.

**No validation that a track name exists** when an assignment is written, and no warning
when a track still referenced by an assignment is deleted.

**Dead code.** `MusicAssignment.pickTrackIndex` (the client picks), its setters,
`ZoneMusicSoundInstance.setMusicVolume` / `isDone` / `markDone`,
`ReactiveMusicHook.isSuppressed`, `CobblemonBattleHook.isAvailable`, the `fade_to` action,
and `PlayerMusicState.lastPos` / `lastSendTime`. The last pair looks like an abandoned
movement debounce. None of it is harmful; all of it misleads a reader, which is why it is
listed rather than left to be discovered.

**Two config keys that do nothing.** `defaultFadeInTicks` and `defaultVolume` are loaded,
saved and served, and read by no playback code.

**The sidebar description is hard-coded French.** `getNavDescription()` returns
`"Musique de zones"` as a literal, while the rest of the dashboard goes through i18n keys.

---

← [Dashboard API](dashboard.md) · [Troubleshooting](troubleshooting.md) →
