[← CheeseKart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

Most CheeseKart problems are one of three things wearing different clothes: the mod did
not load, OpenBoatUtils is not where you think it is, or a preset is not what you think
it is.

---

## The server will not start, and the log mentions `cheesedash`

**Cause.** CheeseKart declares `cheesedash` as a hard dependency. Fabric refuses to
start without it rather than loading a mod that would fail on its first request.

**Fix.** Put the CheeseDash jar in `mods/` as well. Both jars, always.

## `/cheesekart` does not exist

**Cause, in order of likelihood.** The jar is not in `mods/`; the jar is a
`-sources.jar`; the server is not 1.21.1; Fabric API is missing.

**Fix.** Check the log for `[CheeseKart] Initialized`. If it is absent the mod never
loaded, and the reason is higher up in the log — Fabric reports rejected mods by name
during startup. If it is present, the command exists and you are hitting the next
problem instead.

## `/cheesekart` exists but says *Unknown or incomplete command*

**Cause.** The whole command tree requires permission level 2. Brigadier hides
subcommands the caller cannot run, so an under-privileged player sees the vanilla
unknown-command error rather than a permission error.

**Fix.** `/op <player>`, or level 2 through your permissions mod. There is no way to
lower the requirement without editing `CheeseKartCommand`.

## `/cheesekart presets` says *No presets configured*

**Cause.** The in-memory preset map is empty, which should be impossible after a normal
startup — the mod seeds three defaults whenever it loads nothing. So this means the
presets were deleted *after* startup, through the API or by a `DELETE` on each.

**Fix.** Create one, through the dashboard or
[`POST /presets`](dashboard.md#post-presets). Or stop the server, delete
`config/cheesekart.json`, and start it again to get the three stock presets back.

If it happened at startup instead, look for:

```text
[CheeseKart] Failed to load config: <reason>
```

That means the JSON is malformed. The mod logs it and carries on with whatever it
parsed — and since a failed parse leaves the map empty, it then seeds the defaults.
A file whose `presets` entries all lack an `id` loads "successfully" with zero presets,
which is the one case that produces this message without a parse error.

---

## The kart is a visible boat and handles like a boat

**Cause.** OpenBoatUtils is missing **on that player's client**. The server checks
whether the client registered the channel and skips sending physics entirely if not.
This is not logged, by design — otherwise every non-OBU player would spam the log on
every start.

**Fix.** Install OpenBoatUtils client-side. If you cannot control clients, treat kart
racing as opt-in and tell players what they need.

**How to confirm.** Start a kart for a client you know has OBU. If that one handles
correctly, the mod is fine and the problem is per-client.

## Nobody's kart has physics, even with OpenBoatUtils installed

**Cause.** The server-side reflection into OBU's payload class failed. This happens when
OBU is installed on the server and has changed the shape of
`dev.o7moon.openboatutils.OpenBoatUtils$BytePayload`.

**Fix.** Check the startup log for:

```text
[CheeseKart] Failed to init OBU reflection, OBU packets will not work
```

There is no configuration fix — the class name is hard-coded in
`OpenBoatUtilsProxy.register()`. Either downgrade OBU to a version whose class matches,
or **remove OBU from the server** and keep it only on clients: with OBU absent
server-side, CheeseKart registers its own payload on the channel and sends the identical
bytes, bypassing the reflection entirely. That is often the quicker fix.

## Physics are wrong in one specific way

**Cause.** Almost always the preset, not the mod.

**Fix.** `/cheesekart presets` shows the three values most often at fault. For the rest,
read the preset back with `GET /presets/{id}` and compare against
[the field reference](configuration.md#preset-fields). The common surprises:

| Symptom | Likely field |
|---|---|
| kart accelerates on its own, slides everywhere | `slipperiness` above 1 |
| no top speed at all, keeps gaining | `accelStacking` is `true` |
| cannot jump | `jumpForce` is `0.0`, which is its default |
| cannot steer mid-air | `airControl` is `false` |
| racers dying on landings | `fallDamage` is `true` |
| kart will not climb a step it should | `stepHeight` below the block height |
| kart rises continuously | `gravity` is positive — negative is down |

## A preset edit changed nothing

**Cause.** One of two things. Either you edited `config/cheesekart.json` by hand on a
running server — the file is read once at startup and your edit will be overwritten by
the next save — or you edited a preset while sessions were running and expected the
*running* sessions to change without a re-apply.

**Fix.** Edit presets through the dashboard or the API, which is the only route that
writes to the live map. A `PUT` re-applies automatically; check the log:

```text
[CheeseKart] Re-applied preset 'drift' to 3 active session(s)
```

If that line is missing, no session was using the preset — including when you edited a
preset whose name you guessed wrong and got a `404`.

`locked` on a running session never changes, because a session snapshots it at
start. Stop and restart that session.

---

## A `start` command reports *Failed to start kart*

**Cause.** The force-mount was refused. The overwhelmingly common reason is that the
player is already riding something — a horse, a real boat, a minecart. A player already
in a *kart* is not this case: that session is stopped first and replaced.

**Fix.** Get them off the other vehicle and try again. No cleanup is needed: the boat
that was spawned is discarded before the failure is reported.

## A racer is stuck after their kart stopped

**Cause.** This is what the final teleport in the teardown exists to prevent — stepping
off an invisible boat running custom gravity can leave the player with stale velocity.
Seeing it means the teardown did not finish, usually because the player disconnected
mid-teardown.

**Fix.** Have them reconnect; the position resyncs. If they are stuck on reconnect,
`/tp` them one block up.

## Shift does not let a player out

**Cause.** Working as designed, if the preset is `locked` — the default on all three
stock presets.

But also by accident when it is *not* locked: the client-side dismount mixin checks only
whether the player is in a kart, not whether the session is locked. So a client with
CheeseKart installed cannot Shift out of an unlocked kart either. See
[the mixin note](architecture.md#the-two-client-mixins).

**Fix.** `/cheesekart stop <player>`. If you need genuinely free-roam karts, be aware
the current build does not deliver them to clients that have the mod.

## An invisible boat is left in the world

**Cause.** The server stopped without running its teardown — a crash, a `kill -9`, a
host pulling the plug. A clean shutdown stops every session; an unclean one cannot.

**Fix.** The leftover is an ordinary boat entity with the invisible flag. Stand near it
and `/kill @e[type=boat,distance=..10]`, which will also remove legitimate boats in
range, so pick your spot. Nothing else is left behind: sessions are memory-only and do
not come back after a restart.

**Prevention.** Run `/cheesekart stopall` before any planned maintenance.

---

## Dashboard says *CheeseKart not ready*

**Cause.** A route was hit before the server-started event fired, or after it stopped.
The module answers `503` rather than throwing.

**Fix.** Wait for `[CheeseKart] Server started — N presets loaded`. If the server is up
and this persists, CheeseKart's `INSTANCE` is null, which means mod init threw — look
for a stack trace between `Initializing...` and the end of startup.

## The `kart` module is missing from the dashboard

**Cause.** CheeseDash discovers modules through the `cheesedash` entrypoint at startup.
A missing module means CheeseKart loaded after discovery or did not load at all.

**Fix.** Check `GET /api/v1/modules` for `kart`. Absent there too, confirm CheeseKart is
in `mods/` and look for its init lines in the log.

## `GET /active` is empty while people are clearly driving

**Cause.** You are talking to a different server than the one they are on — a test
instance on the same machine, most often.

**Fix.** Check the port and host. `/cheesekart list` in game is the authoritative answer.

---

## Reporting a bug

Include:

1. **The startup block** — every `[CheeseKart]` line from `Initializing...` onwards. The
   OBU and CobbleShift lines in particular tell us which of the two send paths you are
   on, and that determines half the possible causes.
2. **The preset**, from `GET /presets/{id}` or the file. Not a description of it.
3. **Whether OpenBoatUtils is on the server, on the client, or both.** These three
   configurations behave differently and the answer is never obvious from a description
   of the symptom.
4. **What you expected and what happened**, separately.

Logs live in `logs/latest.log`. The relevant lines are tagged `[CheeseKart]`:

```bash
grep CheeseKart logs/latest.log
```

Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues).
Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.md) · [CheeseKart](../README.md) →
