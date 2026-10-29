[← CheeseKart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](physics.fr.md)

# Physics and sessions

A kart is not an entity this mod adds. It is a vanilla boat, hidden, that a player is
locked onto, with its handling overridden by packets sent to that player's client. Three
separate mechanisms have to line up for it to feel right, and each one fails differently
when it does not.

---

## What a kart is made of

`/cheesekart start` does six things, in this order:

1. **Spawns a boat** at the player's exact position, with three vanilla flags set:
   invisible, invulnerable, silent. Invisible is the real mechanism that hides it — the
   [render mixin](architecture.md#the-two-client-mixins) is only a safety net.
2. **Force-mounts** the player. Force here means the vanilla "ignore whether this is
   normally allowed" path. If it still fails, the boat is discarded immediately so no
   orphan entity is left in the world, and the command reports failure.
3. **Pushes the physics** — twelve OpenBoatUtils packets, one per setting.
4. **Resolves the species**, explicit argument first, then the preset's `species` field,
   then nothing.
5. **Transforms the player** through CobbleShift, if a species was resolved and
   CobbleShift is present.
6. **Records the session** and broadcasts the new kart state to every client.

Starting a kart for someone who already has one stops the old session first, which makes
`start` the way to switch a racer's preset mid-race.

---

## The OpenBoatUtils protocol

OpenBoatUtils listens on the channel `openboatutils:settings`. Its payload is raw
binary, not JSON:

```text
[short packetId][value]
```

Both parts are big-endian, which is Netty's default and therefore what
`PacketByteBuf.writeShort` and friends produce. The value's width depends on the
setting: a float for most, a double for gravity, a single byte for a boolean, nothing at
all for a reset. These packet ids are **not documented upstream** — the list below was
derived by reading OpenBoatUtils.

| Id | Setting | Payload | Preset field |
|---|---|---|---|
| 0 | reset to vanilla | — | sent on stop |
| 1 | step height | float | `stepHeight` |
| 2 | slipperiness | float | `slipperiness` |
| 4 | fall damage | boolean | `fallDamage` |
| 6 | air control | boolean | `airControl` |
| 7 | jump force | float | `jumpForce` |
| 9 | gravity | **double** | `gravity` |
| 10 | yaw acceleration | float | `yawAccel` |
| 11 | forward acceleration | float | `forwardAccel` |
| 12 | backward acceleration | float | `backwardAccel` |
| 13 | turn-forward acceleration | float | `turnForwardAccel` |
| 14 | acceleration stacking | boolean | `accelStacking` |
| 28 | air stepping | boolean | `airStepping` |

The gaps are real: ids 3, 5, 8 and everything between 15 and 27 are OBU settings this
mod does not use.

### Why the payload is sent two different ways

OBU may or may not be installed on the **server**, and that changes how the packet has
to be built. Both cases are handled at mod init:

- **OBU present server-side.** Registering a second payload type on a channel OBU
  already owns would be a conflict, so CheeseKart borrows OBU's own payload class —
  `dev.o7moon.openboatutils.OpenBoatUtils$BytePayload` — by reflection, caching its
  constructor and its static `ID`. The log says `OBU detected — using OBU BytePayload
  via reflection`.
- **OBU absent server-side.** Nothing owns the channel, so CheeseKart registers its own
  fallback payload on it. The log says `OBU not loaded — registered fallback payload
  channel`. The bytes on the wire are identical either way.

Reflection is the fragile part. If OBU renames or restructures that class, the lookup
fails at startup and the error is logged once — after which no physics packets are sent
at all and karts handle like boats. The mod keeps running.

### Why it has to be on the client

The server never simulates kart physics. It sends numbers; **the racer's own client**
applies them. Before sending anything, the mod checks whether that client has
registered the channel, and skips the send entirely if not. So a racer without OBU
installed gets a working invisible boat with vanilla handling, no error and no log spam.

The practical consequence is that every racer needs OBU client-side, and a server
without it in its required-mods list will have racers who are quietly slower than the
rest.

---

## Locked mode

`locked` decides whether the racer can leave. It defaults to `true`, and it is enforced
twice.

**Client side**, a mixin cancels `dismountVehicle` while the local player is flagged as
being in a kart. Pressing Shift does nothing at all. This is the part the racer feels.

**Server side**, every tick, each session is checked: if the player is no longer riding
anything, the mod looks for their boat and re-mounts them. If the boat is gone, the
session is stopped instead. This is what makes locked mode hold even for a client
without the mod installed, or a client that found another way out.

With `locked: false` the inverse applies: a player who dismounts has their session
stopped cleanly on the next tick, boat removed and physics reset.

The same per-tick check also catches a third case — the player is riding *something*,
but their boat has died or been removed. That session is stopped rather than left
pointing at a dead entity.

---

## Stopping a kart is an ordered teardown

The order in `stopKart` is not arbitrary, and two of its steps exist because of bugs
that were fixed by moving them.

1. **Broadcast that the kart is over, first.** Clients clear their kart state before the
   dismount packet arrives. Done the other way round, the dismount mixin is still armed
   when the dismount reaches the client, and it cancels the very dismount the server
   just asked for.
2. **Dismount** the player.
3. **Reset the physics** — packet id 0. After the dismount, so the client resets the
   settings it is no longer using rather than the ones it is.
4. **Discard the boat**, looked up by entity id first and by UUID as a fallback, across
   every loaded world.
5. **Revert the CobbleShift transform**, if there was one.
6. **Zero the player's velocity and re-teleport them to their own position.** Without
   this, a player stepping off an invisible boat that was running custom gravity can be
   left stuck or drifting. Teleporting someone to where they already are is a cheap way
   to force a full position and velocity resync.

---

## Session lifecycle

A session is an immutable record: player UUID and name, preset id, resolved species,
the boat's entity id and UUID, a start timestamp, and the `locked` flag copied from the
preset at start time. Changing a preset's `locked` field does not change a session
already running.

| Event | What happens |
|---|---|
| **Admin starts a kart** | session created, physics pushed, all clients told |
| **Every server tick** | missing riders re-mounted or stopped, dead boats cleaned up, vanished players dropped |
| **A player joins** | every active kart is sent to the new client, so a late arrival sees other racers' karts correctly |
| **A player disconnects** | session dropped immediately; the boat is discarded on the server thread, because the disconnect event arrives on a network thread |
| **A preset is updated via `PUT`** | physics are re-pushed to every session using that preset — [live tuning](dashboard.md#put-presetsid) |
| **The server stops** | every session torn down, so no invisible boat survives a restart |

Sessions live in memory only. They are not written to disk and do not survive a restart
— which is intentional: a kart that outlived the server would be an invisible boat
nobody could get out of.

---

## CobbleShift transforms

Entirely optional, and reached only by reflection — CheeseKart compiles and runs with no
knowledge of CobbleShift. At server start it tries to find
`com.cobbleshift.CobbleShiftMod`, read its `INSTANCE`, get the transform manager, and
cache the `transform` and `revert` methods.

The lookup is retried later if CobbleShift's instance is not ready yet, rather than
being written off permanently, because mod init order is not guaranteed. Three outcomes
end up in the log:

```text
[CheeseKart] CobbleShift integration active
[CheeseKart] CobbleShift not installed — transform features disabled
[CheeseKart] Failed to hook CobbleShift: <reason>
```

The third is the one to care about: CobbleShift is installed but its shape changed. A
failed transform never fails the kart — the racer gets their kart without the costume.

---

← [Configuration](configuration.md) · [Dashboard API](dashboard.md) →
