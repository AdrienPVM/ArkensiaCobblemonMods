[← CheeseKart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

Fourteen source files, about 1 380 lines. Small enough that this page can cover all of
it, which is the point of writing it down.

---

## Package layout

```text
com.cheesekart
├── CheeseKartMod.java          entrypoint: wires every event, owns the two singletons
├── CheeseKartConfig.java       loads/saves config/cheesekart.json, holds the presets
├── CheeseKartCommand.java      the /cheesekart Brigadier tree
├── KartPreset.java             a preset: 16 fields, JSON in/out, merge, stock factories
├── KartSession.java            immutable record describing one running kart
├── KartManager.java            all the behaviour — start, stop, per-tick enforcement
├── CobbleShiftProxy.java       soft dependency on CobbleShift, by reflection
├── network
│   ├── KartSyncPayload.java    S2C: "this player is/is not in a kart"
│   └── OpenBoatUtilsProxy.java builds and sends the OBU physics packets
├── client
│   ├── CheeseKartClient.java   client entrypoint: tracks kart players and boat ids
│   └── mixin
│       ├── BoatRenderMixin.java  cancels rendering of kart boats
│       └── DismountMixin.java    blocks Shift-dismount in a locked kart
└── dashboard
    ├── CheeseKartDashboardModule.java  registers the "kart" prefix with CheeseDash
    └── KartApi.java                    the nine REST routes
```

## Who owns what

**`CheeseKartMod`** is the only singleton, exposed as a public static `INSTANCE`.
Everything else reaches the config and the manager through it, including the dashboard
layer and CheeseGuard's reflection hook. It registers seven things at init: the config
load, the manager, both payload types, the command tree, and the lifecycle, tick,
disconnect and join handlers.

**`CheeseKartConfig`** is a `ConcurrentHashMap` of presets plus JSON load and save. It
is concurrent because the dashboard mutates presets from HTTP threads while the server
thread reads them. It saves eagerly: `putPreset` and `removePreset` write the whole file
immediately, so there is no "unsaved changes" state to lose.

**`KartManager`** holds the behaviour and the session map, also a `ConcurrentHashMap`,
keyed by player UUID. One session per player, enforced by `startKart` stopping an
existing one first.

**`KartPreset`** is a mutable bean with three JSON methods worth distinguishing:
`fromJson` builds a new preset and leaves absent fields at their defaults; `mergeFrom`
applies only the present fields onto an existing instance, which is what makes `PUT`
a merge; `toJson` always writes all sixteen fields. `id` is deliberately absent from
`mergeFrom`, so an update cannot rename a preset.

**`KartSession`** is a record, so it cannot drift. It snapshots `locked` at start time —
editing a preset's `locked` field does not change a session already running.

## Threading

Three kinds of thread touch this mod, and the boundaries are where bugs live.

| Thread | What runs there |
|---|---|
| **Server (main)** | the command tree, `KartManager.tick()`, every spawn, mount, dismount and discard |
| **HTTP** (CheeseDash) | `KartApi` route handling, reading and writing presets |
| **Netty IO** | the player-disconnect event |

Rules the code follows:

- Anything that touches the world or an entity goes through
  `WebDashboard.executeOnServerThread(server, …)`. Every mutating API route does this —
  `/start`, `/stop`, `/stopall`, and the live re-apply inside `PUT /presets/{id}`.
- Preset reads and writes happen straight on the HTTP thread, which is safe because the
  preset map is concurrent and a preset is only ever fully replaced, never partly
  mutated while being read.
- The disconnect handler arrives on a network thread, so it removes the session inline
  (the map is concurrent) but schedules the boat cleanup with `server.execute(…)`.

The consequence for API callers is that a mutating route answers before its effect has
necessarily happened. `{"started":true}` means *scheduled*, not *succeeded* — see
[the note on POST /start](dashboard.md#post-start).

## The per-tick loop

`KartManager.tick()` runs on every server tick and is the whole of the mod's
enforcement. For each session it decides between four cases:

| Situation | Action |
|---|---|
| player is offline | drop the session, discard the boat, tell clients |
| player is not riding, session is `locked` | find the boat and re-mount; if the boat is dead, stop the session |
| player is not riding, session is not `locked` | stop the session — dismounting is how an unlocked kart ends |
| player is riding, but the boat is gone or dead | stop the session |

It collects what needs doing into two lists and acts after the iteration, rather than
mutating the session map while walking it.

Boats are looked up by **entity id first, UUID second**. Entity id is faster and
reliable within a session; the UUID fallback covers the case where the entity was
reloaded and got a new id. Cleanup tries every loaded world, because a racer can cross
a dimension boundary mid-session.

## The two client mixins

Both are client-only, declared in `cheesekart.mixins.json` under `"client"`, and both
are **non-fatal**: `require = 0` and `defaultRequire: 0` mean a failed injection is a
silently skipped mixin, not a crash. That is deliberate — neither mixin is essential,
and crashing a client over a cosmetic hook would be the worse outcome.

**`BoatRenderMixin`** cancels `BoatEntityRenderer.render` at HEAD when the entity id is
a known kart boat. This is a safety net: the boat is already flagged invisible
server-side. It exists for the window between a client learning about the boat entity
and learning about the kart, and for resource packs or mods that render boats through
another path.

**`DismountMixin`** cancels `ClientPlayerEntity.dismountVehicle` at HEAD when the local
player is in a kart. This is what makes Shift feel like it does nothing rather than
like a dismount that gets undone a tick later. Note it checks only *in a kart*, not
*locked* — the client is not told whether a session is locked, so an unlocked kart also
cannot be exited by Shift on a client that has CheeseKart installed. A player on an
unlocked kart has to be stopped by an admin, or dismount some other way. Worth knowing
if you rely on `locked: false`.

**`CheeseKartClient`** keeps two collections — the set of players in karts and a map of
player to boat entity id — both concurrent, because the payload handler and the render
thread both read them. They are never cleared on world change; the `clear()` method
exists but nothing calls it, so stale entries can survive a server switch until the
next sync packet corrects them. Harmless in practice (a stale entry hides a boat that no
longer exists) but it is a loose end.

## Networking

Two channels, in opposite directions and for different audiences.

**`cheesekart:sync`** (S2C) carries `(playerUuid, active, boatEntityId)` and is
broadcast to **every** client, not just the racer — other players need to know not to
render that boat. It is also replayed to any player who joins while karts are running,
so a late arrival sees the grid correctly.

**`openboatutils:settings`** (S2C) carries raw OBU physics packets to the racer only.
Full protocol in [physics](physics.md#the-openboatutils-protocol). The channel is owned
by OBU when OBU is installed server-side, and by this mod when it is not — which is why
`OpenBoatUtilsProxy` has two send paths.

Both are guarded by `canSend`, so a client that has not registered a channel is never
sent a packet it cannot decode.

## Extension points

- **A new physics setting.** Add the field to `KartPreset` (field, getter/setter, and
  all three JSON methods — `toJson`, `fromJson`, `mergeFrom`; forgetting `mergeFrom` is
  the easy mistake, and makes the field silently un-editable through `PUT`), then add
  its OBU packet id and a send call in `OpenBoatUtilsProxy.applyPreset`.
- **A new route.** Add a branch in `KartApi.handle` and a handler method. Wrap anything
  touching the world in `executeOnServerThread`, and call `WebDashboard.requireWrite`
  first if it mutates.
- **Another soft dependency.** `CobbleShiftProxy` is the pattern to copy: look the class
  up by name, cache the methods, expose an `isAvailable()`, and retry the lookup if the
  other mod's singleton is not ready yet.
- **Reaching this mod from another mod.** Do what CheeseGuard's `CheeseKartHook` does:
  reflect on `com.cheesekart.CheeseKartMod.INSTANCE`, then `getKartManager()` and
  `getConfig()`. Both are public and stable.

## Known weaknesses

Stated plainly, because finding these by surprise is worse.

- **The OBU reflection is the single point of failure.** OBU's `BytePayload` class name
  is hard-coded. If OBU renames or moves it, the lookup fails at startup, the error is
  logged once, and from then on karts handle like boats with no further warning.
- **The packet ids are reverse-engineered**, not from a published spec. A protocol
  change on OBU's side degrades silently rather than erroring.
- **Presets are read from disk once, at startup.** There is no reload command. Hand
  edits to `cheesekart.json` on a running server are ignored and then overwritten.
- **`DismountMixin` ignores `locked`**, as described above.
- **`CheeseKartClient.clear()` is dead code** — client state is never explicitly reset.
- **No rate limiting on preset writes.** Every `POST`, `PUT` and `DELETE` rewrites the
  whole config file synchronously. Fine at human speed; a script hammering `PUT` for
  live tuning is doing a file write per request.
- **Sessions are not persisted**, by design. A crash with karts running leaves invisible
  boats in the world, because the shutdown teardown never ran.

---

← [Dashboard API](dashboard.md) · [Troubleshooting](troubleshooting.md) →
