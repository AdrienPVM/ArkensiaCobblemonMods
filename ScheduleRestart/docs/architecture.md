[← ScheduleRestart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

One source file, 171 lines, one class, no mixins, no packets, no persistence. This page
exists less to explain the structure than to be honest about what it does not have.

## Layout

```text
ScheduleRestart/
├── build.gradle                  Loom 1.8, Java 21, no sibling-mod dependency
├── gradle.properties             mod_version=1.0.0+1.21.1 · archives_base_name (unused)
├── settings.gradle               Fabric plugin repository only; no rootProject.name
├── gradle/wrapper/               Gradle 8.10 wrapper
└── src/main/
    ├── java/com/schedulerestart/
    │   └── ScheduledRestart.java  everything
    └── resources/
        └── fabric.mod.json        id scheduledrestart · environment server
```

There is no `assets/` directory, no `data/`, no `*.mixins.json`, no `client/` package and
no `dashboard/` package. Nothing is registered with any Minecraft registry: no item, no
block, no entity, no sound, no particle, no scoreboard criterion.

The project builds standalone. `build.gradle` has no `fileTree` glob pointing at another
mod's `build/libs/`, so the build order of the repository does not matter for this one —
even though `fabric.mod.json` requires CheeseDash at runtime.

## The one class

`com.schedulerestart.ScheduledRestart implements ModInitializer`.

| Member | What it owns |
|---|---|
| `static Timer currentTimer` | the single armed schedule. `null` until the first command |
| `onInitialize()` | registers the Brigadier tree and returns. Nothing else happens at startup |
| `executeRestart(ctx, isStop)` | reads the arguments, cancels any previous timer, queues every task, confirms to the caller |
| `broadcastColored(server, msg, color, bold)` | builds a `MutableText`, formats it, and hands the broadcast to the server thread |
| `restartServer()` | builds a `java … -jar … nogui` command line from the running process and starts it with `ProcessBuilder` |

`currentTimer` being `static` on a `ModInitializer` is redundant — Fabric instantiates the
initializer exactly once — but harmless, and it does make the field reachable from the
anonymous `TimerTask` bodies without a capture.

## State and disk

The mod has one piece of state, the `Timer` in `currentTimer`, and it lives only in
memory. **Nothing is ever written to disk.** No config, no schedule file, no marker, no
lock. The single consequence is that a schedule does not survive the JVM: if the server
crashes or is restarted by anything else while a schedule is armed, the schedule is gone
and nobody is told.

That is a defensible choice for a tool whose maximum horizon is sixty minutes. It stops
being defensible the moment someone widens the `integer(1, 60)` bound.

## Threading

Three threads matter, and the hand-offs between them are the only interesting mechanical
detail in the mod.

| Thread | What runs there |
|---|---|
| Server main thread | `onInitialize`, the command registration, `executeRestart` (it runs inside the command dispatch), every broadcast, and `server.stop(false)` |
| `java.util.Timer` thread | all `TimerTask` bodies: each announcement's *trigger*, the one-second sleep, and `restartServer()` |
| The child process | a brand-new JVM, not a thread, spawned by `ProcessBuilder.start()` |

Two hand-offs, both correct:

```java
server.execute(() -> server.getPlayerManager().broadcast(finalText, false));
```

and

```java
server.execute(() -> server.stop(false));
```

Every touch of Minecraft state goes through `server.execute`, which queues onto the main
thread. The timer thread itself only ever builds `Text` objects — immutable — and calls
`ProcessBuilder`. There is no synchronisation on `currentTimer`, and none is needed in
practice because it is only assigned from the command dispatch, which is always the
server thread.

`Thread.sleep(1000)` runs on the timer thread, so the one-second grace period before
shutdown does **not** stall the tick loop. The server keeps ticking at full speed until
`stop` is actually dequeued.

### The failure mode of that design

A `Timer` runs independently of the tick loop, which is why the countdown stays accurate
on a lagging server. It is also why both the countdown and the shutdown go silent if the
server thread wedges: `server.execute` queues work that a frozen thread never drains. The
schedule appears to vanish. In shutdown mode nothing is lost and nothing is corrupted,
but an admin watching chat sees a countdown stop at `dans 4 secondes...` and a server
that stays up. In restart mode the timer thread still reaches `restartServer()` at zero
and spawns the replacement JVM beside the frozen one, which then fails on the port or the
world lock.

## Mixins

None. There is no `scheduledrestart.mixins.json` and no `mixins` key in
`fabric.mod.json`. The mod touches no vanilla internals and will not break on a
Minecraft patch for that reason — only the Yarn-mapped API calls (`MinecraftServer.stop`,
`PlayerManager.broadcast`, `ServerCommandSource.hasPermissionLevel`) could change, and
they are stable public API.

## The dependency that does nothing

`fabric.mod.json`:

```json
"depends": {
  "fabricloader": ">=0.16.0",
  "minecraft": "1.21.1",
  "java": ">=21",
  "cheesedash": "*"
}
```

`cheesedash` is a hard dependency. Fabric Loader refuses to start a server without the
CheeseDash jar present. And yet:

- `ScheduledRestart.java` imports nothing from `com.cheesedash`.
- There is no `"cheesedash"` entrypoint, so `FabricLoader.getEntrypoints("cheesedash", …)`
  never finds this mod and CheeseDash's registry never learns it exists.
- No `DashboardModule`, no `*Api` class, no REST route under `/api/v1/`, no WebSocket
  channel, no `broadcast(...)` call into `DashboardRegistry`.
- The mod does not even appear in the dashboard sidebar, because the sidebar is built
  from registered modules.

So the declaration buys nothing and costs a jar. The likely history is that the mod was
written alongside CheeseDash and a dashboard page was intended; the dependency line went
in first and the page never followed. **The intent is not recoverable from the code** —
there is no commented-out module, no stub class, no `TODO`.

Either direction would be an improvement: drop `cheesedash` from `depends` and the mod
becomes genuinely standalone, or write the module and the dependency becomes true. Until
then, treat it as a packaging bug and keep the CheeseDash jar installed.

The mirror-image mistake sits in the same block. The mod's only Fabric API call is

```java
CommandRegistrationCallback.EVENT.register(...)
```

from `net.fabricmc.fabric.api.command.v2`, and `fabric-api` is **not** in `depends`.
Without Fabric API the mod loads and then dies on a `NoClassDefFoundError` during
initialisation, instead of being refused with a readable *requires fabric-api* message.
The `build.gradle` does declare `modImplementation` on Fabric API, so this is purely a
runtime metadata gap.

## Extension points

Honestly: there are none. No events are fired, no interface is published, no static
method is callable from another mod, and `restartServer()` is private. A mod that wanted
to trigger a countdown would have to run the command through the dispatcher.

If you want to extend this, the two changes that would open it up are:

1. Give the command a `cancel` subcommand and expose the pending schedule as a small
   immutable record. That alone makes the mod usable from a dashboard.
2. Replace the three ad-hoc `Timer` loops with a list of
   `(offsetMillis, messageKey)` pairs built once. The timeline then becomes data, which
   makes it configurable and testable, and makes a `cancel` trivially correct.

## Known weaknesses

Listed in rough order of how likely they are to hurt you.

**The relaunch spawns the child before the parent dies.** `restartServer()` runs, then the
stop is queued. Both JVMs exist at once, competing for port 25565 and for the world's
`session.lock`. It works because the new server's startup is slower than the old one's
shutdown. That is a race, not a design, and there is no retry if the child loses it.
Full detail in [what happens at zero](restart.md#why-a-hosting-panel-breaks-this).

**A failed relaunch still shuts the server down.** The `catch (Exception e)` around
`restartServer()` prints the stack trace and falls through to `server.stop(false)`. There
is no "I could not find a jar, so I will not stop" branch. A misconfigured relaunch takes
the server offline indefinitely.

**The jar-finding fallback can start a vanilla server.** If the directory scan matches
`server.jar` — the vanilla bundle Fabric keeps next to the launcher — the replacement JVM
runs vanilla Minecraft with no mods. The server comes back and nothing works, which is
harder to diagnose than a server that stays down.

**There is no cancel command.** One static timer, replaced silently on each new command,
with no way to disarm it. The workaround is in
[commands](commands.md#re-issuing-cancels-the-previous-schedule) and it is a workaround.

**Cancelling tells nobody.** Players who were warned about a shutdown get a new opening
warning with different numbers and no retraction of the old one.

**Any word that is not `stop` means restart.** `/schedulerestart 5 shutdown` relaunches
the server. `equalsIgnoreCase("stop")` with no validation of the alternative is a
single-character typo away from the opposite of your intent, and the argument offers no
tab completion to protect you.

**All player text is hardcoded French.** No lang files, no `Text.translatable`. An
English server shows French warnings and the only fix is a source edit.

**The `Timer` is not a daemon and is held by a static field.** `new Timer()` creates a
non-daemon thread, and `currentTimer` keeps a strong reference to it forever, so the
thread is never collected and never exits on its own. On a dedicated server this has not
been observed to prevent the process from exiting, and the shutdown path does not depend
on it — but a lingering non-daemon thread is exactly the shape of a JVM that refuses to
die, and `new Timer(true)` would remove the risk at no cost.

**No persistence.** An armed schedule disappears if the JVM goes down for any other
reason.

**No tests.** There is no test source set and no way to exercise the timeline without
running a server and waiting a minute.

## What is deliberate

Two things that look like omissions and are not.

The mod does **not** save the world itself before stopping. It calls
`server.stop(false)`, and the vanilla shutdown saves everything. Adding an explicit
`saveAll` before the stop would only save twice.

The countdown is **not** driven by server ticks. A tick-driven countdown drifts on a
lagging server, and lag is the normal condition of the server this was written for. Using
wall-clock time means the restart happens when it was announced, which is what players
were promised.

---

← [Configuration](configuration.md) · [Troubleshooting](troubleshooting.md) →
