[← CheeseKart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

CheeseKart exposes one command, `/cheesekart`, with five subcommands. The whole tree
requires **permission level 2** — the level a vanilla server grants to an operator. A
player below that level does not see the command in tab completion and gets the vanilla
*Unknown or incomplete command* error if they type it anyway.

There is no player-facing command. Racers are put into karts by an admin or by a
CheeseGuard event; they never type anything themselves.

---

## `/cheesekart start`

```mcfunction
/cheesekart start <player> <preset> [species]
```

**The player comes first, the preset second.** This trips people up, because the mod's
own earlier documentation had it backwards.

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `player` | single word | yes | an **online** player's name; tab-completes from the player list | — |
| `preset` | single word | yes | a preset id from `config/cheesekart.json`; tab-completes from the loaded presets | — |
| `species` | greedy string | no | a Cobblemon species name, passed through to CobbleShift untouched | the preset's own `species`, else none |

`species` is a *greedy* argument: it swallows the rest of the line, so a multi-word
value needs no quoting. It is only meaningful when [CobbleShift](../../CobbleShift/) is
installed; without it the argument is accepted and silently ignored.

### What it does

1. If the player already has a kart, that session is stopped first — so `start` doubles
   as a way to switch someone's preset mid-race.
2. Spawns a boat at the player's exact position, flagged invisible, invulnerable and
   silent.
3. Force-mounts the player onto it. If mounting fails, the boat is discarded and the
   command reports failure.
4. Pushes the preset's twelve physics values to the client as OpenBoatUtils packets.
5. Resolves the species (explicit argument first, then the preset's field) and asks
   CobbleShift to transform the player, if it is available.
6. Records the session and tells every client that this player is now in a kart.

### Examples

```mcfunction
/cheesekart start Steve drift
```

```text
Kart started for Steve (preset=drift)
```

```mcfunction
/cheesekart start Steve rally pikachu
```

Same thing, and Steve is also transformed into a Pikachu for the duration if CobbleShift
is installed.

### Failures

| Message | Cause |
|---|---|
| `Player not found: <name>` | the name does not match an online player — offline players cannot be put in a kart |
| `Preset not found: <id>` | no such id in `config/cheesekart.json`; check `/cheesekart presets` |
| `Failed to start kart for <name>` | the force-mount was refused. Rare: an existing vehicle is *not* a cause, because a forced mount takes the player off it first — see [troubleshooting](troubleshooting.md#a-start-command-reports-failed-to-start-kart) |

The first two are rejections: nothing happened. The third means a boat was spawned and
then cleaned up, so no entity is left behind.

---

## `/cheesekart stop`

```mcfunction
/cheesekart stop <player>
```

| Argument | Type | Required | Accepted values |
|---|---|---|---|
| `player` | single word | **yes** | an online player's name; tab-completes from the player list |

The player argument is mandatory. There is no form of `stop` that targets the command's
sender.

Teardown runs in a deliberate order — clients are told the kart is over *before* the
dismount, the physics are reset *after* it, and the player is finally re-teleported to
their own position to flush any leftover velocity. [Why that order](physics.md#stopping-a-kart-is-an-ordered-teardown)
matters if you ever touch this code.

```mcfunction
/cheesekart stop Steve
```

```text
Kart stopped for Steve
```

| Message | Cause |
|---|---|
| `Player not found: <name>` | not online |
| `<name> is not in kart` | the player is online but has no session. Harmless |

---

## `/cheesekart stopall`

```mcfunction
/cheesekart stopall
```

No arguments. Stops every active session, each one going through the same ordered
teardown as a single `stop`. Sessions whose player has since gone offline are dropped
from the map rather than torn down.

```text
All karts stopped (4)
```

The command's numeric result is the number of sessions that were active, which makes it
usable in a command block chain or a `/execute store`.

This also runs automatically when the server stops, so a restart never leaves a stray
invisible boat behind.

---

## `/cheesekart list`

```mcfunction
/cheesekart list
```

Active sessions, one line each. Output is sent only to the caller.

```text
--- Active karts (2) ---
  Steve | preset=drift | species=pikachu | 94s [locked]
  Alex | preset=rally | 12s
```

| Field | Meaning |
|---|---|
| name | the player |
| `preset=` | the preset the session was started with |
| `species=` | omitted entirely when no transform is active |
| `…s` | seconds since the session started |
| `[locked]` | the racer cannot dismount — see [locked mode](physics.md#locked-mode) |

With nothing running you get `No active karts`.

---

## `/cheesekart presets`

```mcfunction
/cheesekart presets
```

Lists configured presets with the three values you most often tune, so you can compare
them without opening the config file.

```text
--- Presets (3) ---
  parkour — Parkour | slip=0.98 step=2.0 fwd=0.04 [locked]
  rally — Rally | slip=0.98 step=1.25 fwd=0.04 [locked]
  drift — Drift | slip=1.1 step=1.25 fwd=0.06 [locked]
```

`slip` is slipperiness, `step` is step height, `fwd` is forward acceleration. A preset
with a `species` field shows it too. Every field is documented in
[configuration](configuration.md).

The order is the one the backing `ConcurrentHashMap` iterates in, not creation order —
which is why the three stock presets come out as `parkour`, `rally`, `drift`. The same goes
for `/cheesekart list` and for the order of presets in the saved file.

This reads the live in-memory presets, so a preset created from the dashboard a second
ago shows up immediately.

---

## Driving the mod from somewhere else

Everything above is also reachable without a command:

- **Dashboard** — the `kart` module, or the REST endpoints directly. See
  [dashboard API](dashboard.md).
- **CheeseGuard events** — the `KART_START_ALL` and `KART_STOP_ALL` effect types put
  every participant of a timed event into a kart at once, which is how a race event
  starts cleanly for everyone on the same tick.

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
