[← CheeseMusic](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

Zone music fails silently almost everywhere. There is no error in chat, no red text, no
crash — a region simply plays nothing, and six different causes produce that same symptom.
The fastest way through is to find which half of the chain is broken, so start here:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/status
```

If your name appears with the expected `zoneId`, the server did its job and the fault is on
the client. If it does not, the fault is on the server. Everything below is sorted by that
split.

---

## The mod does not load at all

**Cause.** A dependency in `depends` is unsatisfied — Fabric Loader below 0.16.0, no Fabric
API, Java below 21, or a Minecraft version that is not 1.21.1. Or two copies of
`cheesemusic-*.jar` in `mods/`.

**Fix.** Read the loader's own error, which names the unsatisfied constraint. Then check
`mods/` holds exactly one `cheesemusic-*.jar` and no `-sources.jar`. The mod's own first
log line is `[CheeseMusic] Initializing...`; its absence means the loader never reached the
entrypoint.

## `[CheeseMusic] CheeseGuard not installed — zone music disabled`

**Cause.** `Class.forName("com.cheeseguard.CheeseGuardMod")` threw
`ClassNotFoundException`. CheeseGuard's jar is not loaded.

**Fix.** Add it to the server's `mods/`. CheeseGuard is in `suggests`, not `depends`, so
the loader will not warn you — the mod starts happily and resolves every position to
"no music".

## `[CheeseMusic] CheeseGuard detected but not yet initialized`

**Cause.** `CheeseGuardMod.INSTANCE` or its `RegionManager` was still null when CheeseMusic
ran its `SERVER_STARTED` handler. Both mods initialise on the same event and the order is
whatever the loader chose.

**Fix.** Restart the server; the order is usually stable once it works. Unlike the
ReactiveMusic hook, `CheeseGuardHook` **does not retry** — `initialized` is set to `true`
before the attempt, so a failed init stays failed for the whole server session. If it
recurs on every start, that retry is the code change needed.

## `[CheeseMusic] CheeseGuard hook failed: <message>`

**Cause.** CheeseGuard is present but no longer has the shape the hook expects:
`getRegionManager()`, `getStorage()`, `getRegions()`, or `getRegionsAt(String,int,int,int)`
has been renamed or re-signed.

**Fix.** This is a code-level break, not a configuration problem. Align
`CheeseGuardHook` with CheeseGuard's current API. The exception message names the missing
member.

## `GET /api/v1/music/zones` returns an empty array but `/cg list` shows regions

**Cause.** Either the hook is unavailable (the three log lines above) or
`getAllZones()` threw. That second case logs at `debug` level, so you see nothing at
default log settings.

**Fix.** Set the logger `cheesemusic` to `DEBUG` and retry the request. The
`[CheeseMusic] getAllZones failed: ...` line then tells you which reflective call broke.
A `200` with `[]` never distinguishes "no regions" from "no CheeseGuard" on its own — only
the start-up log does.

## I walk into an assigned region and hear nothing at all

Six causes, in the order worth checking.

**1. ReactiveMusic is not installed on the client.** By far the most common. The client
state machine waits for a confirmation only the ReactiveMusic hook can give, so it stalls
mid-transition and plays nothing, while also keeping vanilla music suppressed. Look for
`[CheeseMusic] ReactiveMusic not installed` in the **client** log.
*Fix:* install ReactiveMusic. Full trace:
[playback](playback.md#what-happens-without-reactivemusic).

**2. The client does not have the resource pack.** Test it directly:
`/playsound cheesemusic:<track> music @s`. *No sound with that name* means the pack is
missing or not enabled. *Fix:* [sound packs](sound-packs.md#delivery-to-clients).

**3. The track name in the assignment does not exist.** Compare
`GET /api/v1/music/assignments` against `GET /api/v1/music/tracks`. Nothing validates the
names on write. *Fix:* correct the assignment, using the sanitised name exactly.

**4. The name was written with a namespace.** `arkensia:volcano` becomes
`cheesemusic:arkensia:volcano`, which the client rejects as an invalid identifier. *Fix:*
store the bare name.

**5. The client does not have CheeseMusic installed.** It drops the packet with no message
at all. *Fix:* install the jar client-side too.

**6. `enabled` is `false` in the config.** The tick loop is skipped entirely, so nobody is
ever evaluated. *Fix:* `PUT /api/v1/music/config` with `{"enabled":true}`.

Causes 1, 2, 3 and 4 all show you as happily listening in
`GET /api/v1/music/status`, because the server did send the packet. Cause 5 shows the same
thing. Only cause 6 leaves the list empty.

## The track restarts every five seconds

**Cause.** The track is shorter than the 100-tick start cooldown. When the cooldown
expires, `SoundManager.isPlaying` is false because the sound has already finished, which
the recovery path reads as "the sound died" and restarts it.

**Fix.** Use tracks longer than five seconds. Pad a short loop, or concatenate it into a
longer file.

## Two pieces of music at once

**Cause.** `MusicTrackerMixin` did not apply. It is declared with `require = 0`, so a
changed injection target degrades to doing nothing instead of crashing.

**Fix.** Search the client log for a mixin warning naming `MusicTrackerMixin`. If the
Minecraft version is not 1.21.1, that is the reason. The mixin targets
`MusicTracker.tick`.

If the second source is ReactiveMusic rather than vanilla, the cause is different: the
reflective hook failed, and the client log says
`[CheeseMusic] ReactiveMusic hook failed: ...`. ReactiveMusic changed its `thread`,
`gainPercentage` or `additionalSilence` members.

## ReactiveMusic stays silent after I leave a region

**Cause.** `ReactiveMusicHook` keeps its saved gain and silence values in static fields
across a fade cycle. If `startFadeIn` threw part-way, `additionalSilence` can be left at
`999999` and the gain at zero.

**Fix.** Disconnect and reconnect. `ZoneMusicPlayer.reset()` runs on disconnect and calls
`forceReset()`, which restores both fields immediately. A client restart works too.

## Music keeps playing during a Cobblemon battle

**Cause.** Battle detection is off, or bound to the wrong method. Check the server
start-up line:

```text
[CheeseMusic] Cobblemon detected — battle detection via <methodName>()
```

`Cobblemon not installed` means the hook is disabled, which is correct if you have no
Cobblemon. `Cobblemon BattleRegistry found but no suitable method` means detection is off
despite Cobblemon being present. A method name that has nothing to do with battles means
the fallback scan bound to the wrong one.

**Fix.** The first two need no fix if you do not care about battle music. The third needs
`CobblemonBattleHook`'s name filter tightened against Cobblemon's current `BattleRegistry`.

## A command is refused

CheeseMusic registers **no commands**. There is no Brigadier tree anywhere in the source,
so `/cheesemusic` and anything like it produce the vanilla *Unknown or incomplete command*
error regardless of your permission level. Region commands (`/cg …`) belong to CheeseGuard
and need permission level 2. Everything CheeseMusic-specific happens through
[the dashboard](dashboard.md).

## The dashboard has no CheeseMusic section

**Cause.** One of three. CheeseDash is not installed, so the `cheesedash` entrypoint is
never invoked. Or CheeseMusic's jar is absent server-side. Or CheeseMusic's start-up threw
before the config was built, in which case the module loads but every request answers
`503 {"error":"CheeseMusic not ready"}`.

**Fix.** Check for `[CheeseMusic] Server started — N assignments loaded` in the server log.
Present means the mod is healthy and the problem is CheeseDash's discovery; absent means
start-up failed and the stack trace above it is the answer.

## I want a music route to require `SUPER_ADMIN` and it only ever asks for `OPERATOR`

**Cause.** CheeseDash's module dispatch raises the bar to `OPERATOR` for every non-`GET`
request and stops there — `requireWrite` is the only check it knows. A module route can
therefore never be more restrictive than `OPERATOR` by inheritance alone, and nothing in
`MusicApi` calls `requireAdmin`.

**Fix.** Add `if (!WebDashboard.requireAdmin(exchange)) return;` as the first statement of
the handler you want to restrict. Note the `if (!…) return;` form: `requireAdmin` sends its
own `403` and returns a boolean, so a bare call would send the `403` and then let the
handler run on. Builds of this mod from before 29 October 2026 contained exactly that
mistake on the six write routes — a real defect, though not an exploitable one, since the
core dispatch had already turned away any session below `OPERATOR`.

## My hand-edit to `config/cheesemusic.json` disappeared

**Cause.** The file is read once, at `SERVER_STARTED`, and the in-memory copy is
authoritative from then on. Any dashboard write rewrites the whole file from memory,
discarding your edit.

**Fix.** Edit while the server is stopped, or make the change through the API instead.
There is no reload command.

## The server threw `ArithmeticException` right after start-up

**Cause.** `checkIntervalTicks` is `0` in the config file. `ZoneMusicTracker.tick`
computes `tickCounter % checkIntervalTicks`. The 1–100 clamp exists only in the setter used
by the dashboard; `load()` assigns the field directly.

**Fix.** Set it to `10` by hand, or anything from 1 to 100.

## My `.ogg` filenames changed on their own

**Cause.** Expected behaviour. `SoundScanner.scan()` renames any file whose basename is not
a valid Minecraft identifier path, every time it scans. Each rename is logged:

```text
[CheeseMusic] Renamed 'Volcano Theme (Final Mix).ogg' -> 'volcano_theme_final_mix.ogg'
```

**Fix.** None needed, but keep your masters outside `sounds/` if the original names matter.
Accents are dropped rather than transliterated, so `forêt.ogg` becomes `fort.ogg` — rename
such files yourself first. Rules: [sound packs](sound-packs.md#filename-sanitising-exactly).

## Overlapping regions play the wrong music

**Cause.** `resolveZoneMusic` returns the first region in CheeseGuard's iteration order
that has an assignment. CheeseGuard's `priority` is read into `ZoneInfo` and never used for
resolution.

**Fix.** Do not give overlapping regions conflicting assignments. Assign the outer region
only, or make the inner one a separate non-overlapping region. The gap is recorded in
[architecture](architecture.md#known-weaknesses).

---

## Reporting a bug

Open an issue at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) with:

1. **Which half.** The output of `GET /api/v1/music/status` while the problem is happening.
   That one line decides whether anyone needs to look at client code at all.
2. **The server log**, `logs/latest.log`, filtered to the mod:
   ```bash
   grep CheeseMusic logs/latest.log
   ```
   Include every `[CheeseMusic]` line from start-up onwards, not only the error. The
   detection lines for CheeseGuard and Cobblemon are usually the answer.
3. **The client log**, `.minecraft/logs/latest.log`, filtered the same way. The
   ReactiveMusic detection line is the one that matters most.
4. **The assignment**, from `GET /api/v1/music/assignments`, and the track list from
   `GET /api/v1/music/tracks`. Most "no music" reports are a mismatch between those two.
5. **Versions**: Minecraft, Fabric Loader, and the exact jar name
   (`cheesemusic-1.0.0+1.21.1.jar`). For anything involving fades or suppression, the
   ReactiveMusic version too.

If the log holds nothing from `[CheeseMusic]` at all, say so explicitly. That is itself the
most useful finding, and it points at the loader rather than the mod.

---

← [Architecture](architecture.md) · [CheeseMusic](../README.md) →
