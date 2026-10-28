# ScheduleRestart

> Schedule a server restart or shutdown with a player-facing countdown.

The smallest mod here, and one of the most likely to be useful to you unchanged.
Announce a restart, and players get reminders every minute plus a countdown over the
last ten seconds.

**Minecraft 1.21.1 · Fabric · Java 21 · 1 file, ~150 lines**

---

## Requirements

- **CheeseDash** — required (it registers as a dashboard-aware mod)
- Fabric Loader ≥ 0.16.0, Java 21

Build CheeseDash first, then drop both in `mods/`.

## Commands

Requires OP level 2.

| Command | Effect |
|---------|--------|
| `/schedulerestart <minutes>` | restart in N minutes |
| `/schedulerestart <minutes> stop` | shut down in N minutes, without restarting |

Example: `/schedulerestart 5` announces a restart in five minutes.

Only one schedule can be active at a time — issuing a new one cancels the previous.

## ⚠️ How the "restart" actually works

**This mod runs the vanilla `stop` command.** It does not restart anything by itself —
it cannot. Something outside Minecraft has to bring the server back up: a hosting
panel with auto-restart enabled, a `systemd` unit with `Restart=always`, a
`while true` wrapper script, Docker with `--restart`, whatever you use.

If nothing is watching, `/schedulerestart 5` will stop your server in five minutes and
leave it stopped. Use the `stop` argument when that is what you actually want, and
test your wrapper before relying on this in production.

## Troubleshooting

- Check `logs/latest.log` — the mod logs what it scheduled and when.
- Confirm you have OP level 2.
- If the server stops and never comes back, the problem is your wrapper, not this mod.

## Building

```bash
cd ../CheeseDash && ./gradlew build        # required first
cd ../ScheduleRestart && ./gradlew build   # -> build/libs/
```

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
