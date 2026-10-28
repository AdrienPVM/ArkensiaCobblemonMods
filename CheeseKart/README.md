# CheeseKart

> Kart racing built on invisible boats and OpenBoatUtils physics.

Players sit in an invisible boat whose handling is retuned into something kart-like —
grip, acceleration, jump, air control, gravity. Presets are authored in the dashboard,
so you can have a drift track and an ice track behave completely differently.

**Minecraft 1.21.1 · Fabric · Java 21 · 14 files, ~1 200 lines**

---

## Requirements

- **CheeseDash** — required
- Fabric Loader ≥ 0.16.0, Fabric API, Java 21

Optional but strongly recommended:

- **[OpenBoatUtils](https://modrinth.com/mod/openboatutils)** — this is what actually
  changes the physics. Without it, karts are just invisible boats with vanilla
  handling.
- **CobbleShift** — lets racers be transformed Pokémon.

Build CheeseDash first, then drop both in `mods/`.

## Commands

| Command | Purpose |
|---------|---------|
| `/cheesekart start <preset> [player]` | put a player in a kart |
| `/cheesekart stop [player]` · `/cheesekart stopall` | end sessions |
| `/cheesekart presets` · `/cheesekart list` | list presets and active sessions |

## Dashboard

Registers under the `kart` prefix: preset CRUD, active sessions, and start/stop/stopall
controls.

CheeseGuard can drive it during events through two effect types (`KART_START_ALL`,
`KART_STOP_ALL`).

## The OpenBoatUtils protocol

Physics settings are pushed to the client over the `openboatutils:settings` channel as
a binary `CustomPayload`. The packet ids this mod uses:

| Id | Setting | | Id | Setting |
|----|---------|-|----|---------|
| 0 | reset | | 11 | forward acceleration |
| 1 | step height | | 12 | backward acceleration |
| 2 | slipperiness | | 13 | turn-forward acceleration |
| 4 | fall damage | | 14 | acceleration stacking |
| 6 | air control | | 28 | air stepping |
| 7 | jump force | | | |
| 9 | gravity | | | |
| 10 | yaw acceleration | | | |

They are not documented upstream — this list was derived by reading OpenBoatUtils.
If it changes its protocol, this mod breaks silently (karts handle like boats) rather
than crashing.

## Client-side pieces

Two mixins: `BoatRenderMixin` cancels rendering of kart boats so they stay invisible,
and `DismountMixin` blocks Shift-to-dismount mid-race.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
