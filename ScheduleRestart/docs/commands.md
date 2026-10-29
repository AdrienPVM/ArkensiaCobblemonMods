[← ScheduleRestart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

ScheduleRestart registers exactly one command, `/schedulerestart`. There are no
subcommands, no aliases, and no cancel form. The whole tree requires **permission
level 2** — the level a vanilla server grants an operator — declared on the root literal:

```java
.requires(source -> source.hasPermissionLevel(2))
```

A player below level 2 does not see `/schedulerestart` in tab completion, and typing it
anyway produces the vanilla *Unknown or incomplete command, see below for error* message
with the red caret under the first character. The command never prints a permission
message of its own, because Brigadier prunes the node before the mod is ever consulted.

The command works from any source that has level 2: a player, the server console, a
command block with `/execute`, or an RCON session. It needs no particular server state.

---

## `/schedulerestart`

```mcfunction
/schedulerestart <minutes> [mode]
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `minutes` | integer | yes | **1 to 60** inclusive | — |
| `mode` | single word | no | the literal word `stop`, any capitalisation; any other word is accepted and means *restart* | absent, which means *restart* |

Neither argument offers tab completion. `minutes` is a plain
`IntegerArgumentType.integer(1, 60)` and `mode` is a plain `StringArgumentType.word()`,
so the client suggests nothing at all. The word `stop` has to be typed from memory —
that is the single most common mistake with this command, and it fails silently in the
dangerous direction. See [the `stop` keyword trap](#the-stop-keyword-trap) below.

The root literal has no `executes` of its own, so `/schedulerestart` with no argument is
a syntax error rather than a help message.

### What it does

The command does not restart anything. It arms a `java.util.Timer` and returns
immediately.

1. If a schedule is already armed, it is cancelled — see
   [re-issuing cancels the previous schedule](#re-issuing-cancels-the-previous-schedule).
2. A fresh `Timer` is created. From this point the countdown lives on its own thread,
   independent of the server tick loop.
3. One warning goes out to every online player **right away**.
4. One warning is queued for each remaining whole minute, then one for each of the last
   ten seconds.
5. A final task is queued at `minutes × 60 000` ms. That task announces the shutdown,
   waits one second, spawns a replacement JVM (restart mode only), then asks the server
   to stop.
6. The caller gets a green confirmation line.

Every message is listed in [the countdown](countdown.md), with its exact wording, colour
and firing time. What the final task actually does to your server — including the ways
the relaunch fails — is in [what happens at zero](restart.md).

Nothing is written to disk. No file is created, no state is persisted. A schedule exists
only in the memory of the running JVM, so a crash or an unrelated restart forgets it.

### Permission level and side effects

The confirmation is sent with `sendFeedback(..., true)`. The `true` is
`broadcastToOps`, so the line is mirrored to every other operator in the vanilla
`[Caller: message]` form, subject to the `sendCommandFeedback` gamerule. If you schedule
a shutdown from the console, the other operators see who did it.

The player warnings go through `server.getPlayerManager().broadcast(text, false)` — the
same chat path `/say` uses. The `false` means *chat*, not action bar, so the messages
stay in the chat history and a player who alt-tabbed can scroll back to them.

### Examples

A ten-minute restart, the normal case:

```mcfunction
/schedulerestart 10
```

The caller sees, in green:

```text
✅ Redémarrage planifié dans 10 minutes
```

and every player immediately sees, in bold aqua:

```text
⚠ Redémarrage du serveur dans 10 minutes !
```

A two-minute shutdown with no relaunch:

```mcfunction
/schedulerestart 2 stop
```

```text
✅ Arrêt planifié dans 2 minutes
```

```text
⚠ Arrêt du serveur dans 2 minutes !
```

The warnings turn gold instead of aqua for a shutdown, so an admin who glances at chat
can tell the two apart without rereading the text.

The shortest possible schedule:

```mcfunction
/schedulerestart 1
```

With `minutes` at 1 there is no per-minute reminder at all — the per-minute loop runs
from 1 to `minutes - 1`, which is empty. Players get the opening warning, then silence
for fifty seconds, then the ten-second countdown. One minute is enough time for the
message to be seen and not enough for anyone to act on it.

⚠️ All player-facing text is hardcoded French string literals in
`ScheduledRestart.java`. There is no `assets/scheduledrestart/lang/` directory, no
`Text.translatable`, and therefore no way to translate the output without editing the
source. An English-speaking server shows its players
`⚠ Redémarrage du serveur dans 10 minutes !` regardless of client language.

### Failure messages

The mod itself produces no error message. Every rejection comes from Brigadier or from
the vanilla command dispatcher, so the wording depends on your Minecraft version rather
than on this mod.

| What you typed | What you get | Cause |
|---|---|---|
| `/schedulerestart` | `Unknown or incomplete command, see below for error` | `minutes` is mandatory; the root literal is not executable |
| `/schedulerestart 0` | `Integer must not be less than 1, found 0` | below the `integer(1, 60)` lower bound |
| `/schedulerestart 61` | `Integer must not be more than 60, found 61` | above the upper bound. There is no way to schedule more than an hour ahead |
| `/schedulerestart abc` | `Expected integer` | `minutes` is parsed before anything else |
| `/schedulerestart 10 stop now` | `Incorrect argument for command` | `mode` is a single word and has no child node, so a third token is trailing data |
| `/schedulerestart 10` as a non-op | `Unknown or incomplete command, see below for error` | permission level below 2; the node is invisible, not refused |

One failure happens *after* the command succeeds, and so cannot be reported to the
caller: if the relaunch cannot find a server jar, the final task throws

```text
Impossible de trouver le jar du serveur pour le redémarrage !
```

That exception is caught, printed as a stack trace in the server log, and then **ignored
— the server still stops**. The command returned `1` ten minutes earlier and there is
nobody left to tell. This is the failure mode behind
[the server stops and never comes back](troubleshooting.md#the-server-stops-and-never-comes-back).

---

## The `stop` keyword trap

The second argument is tested exactly once:

```java
String mode = StringArgumentType.getString(context, "mode");
return executeRestart(context, mode.equalsIgnoreCase("stop"));
```

`equalsIgnoreCase` means `stop`, `STOP` and `Stop` all work. Everything else is a valid
`mode` value that falls through to restart mode. There is no validation and no warning:

| Command | Actual behaviour |
|---|---|
| `/schedulerestart 5 stop` | shutdown, no relaunch |
| `/schedulerestart 5 STOP` | shutdown, no relaunch |
| `/schedulerestart 5 shutdown` | **restart**, relaunch attempted |
| `/schedulerestart 5 halt` | **restart**, relaunch attempted |
| `/schedulerestart 5 restart` | restart, relaunch attempted |
| `/schedulerestart 5 x` | restart, relaunch attempted |

An admin who wanted the server to stay down and typed `shutdown` gets a server that
comes back up — or, more often on a hosted setup, a server that stops and whose panel
then reports a process it did not start. Read the confirmation line before you walk away:
it says `Arrêt planifié` for a shutdown and `Redémarrage planifié` for a restart, and it
is the only feedback that tells you which branch you actually took.

---

## Re-issuing cancels the previous schedule

The timer is held in a single static field:

```java
private static Timer currentTimer = null;
```

Each call cancels that timer before replacing it. The consequences are worth spelling
out:

- **Only one schedule can exist.** You cannot stack a 30-minute warning and a 5-minute
  warning.
- **Cancelling is silent.** Players who were told `⚠ Arrêt du serveur dans 10 minutes !`
  get no retraction. They just receive a new opening warning with a different number,
  and possibly a different word, and have to work out which one counts.
- **`Timer.cancel()` does not interrupt a task that is already running.** If you re-issue
  the command during the final task's one-second sleep, the old shutdown proceeds anyway.
- **There is no cancel command.** `/schedulerestart 60` is the closest thing: it replaces
  whatever was pending with a schedule an hour out, which buys you time to stop the
  server by hand, or to restart it for real and let the forgotten timer die with the
  old JVM.

That last point is the practical workaround, and it is a workaround rather than a
feature. The missing cancel subcommand is listed under
[known weaknesses](architecture.md#known-weaknesses).

---

## No other entry point

The mod registers one command and nothing else. It has no dashboard module, no REST
route, no WebSocket channel, no config file to drive it from, and no hook another mod
could call. Despite `fabric.mod.json` listing `cheesedash` under `depends`, there is no
`cheesedash` entrypoint and no CheeseDash class is referenced anywhere in the source —
see [the dependency that does nothing](architecture.md#the-dependency-that-does-nothing).

If you need an unattended nightly restart, this mod will not give you one: it schedules
a delay from now, never a wall-clock time, and it forgets everything when the JVM exits.
Use your host's cron or your panel's scheduler to issue the command over RCON, or use the
scheduler built into [CheeseDash](../../CheeseDash/) itself.

---

← [Getting started](getting-started.md) · [The countdown](countdown.md) →
