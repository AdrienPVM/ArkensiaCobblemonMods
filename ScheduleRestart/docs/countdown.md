[← ScheduleRestart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](countdown.fr.md)

# The countdown

Everything a player sees comes from this one subsystem. It is a `java.util.Timer` with
`minutes + 10` tasks queued on it, all armed in a single pass when the command runs, and
all of them doing nothing but broadcasting a line of chat. Nothing here reads the clock
again, nothing re-checks the player list, and nothing can be stopped except by cancelling
the whole timer.

This page gives the exact schedule, because an admin who is wrong about it announces a
restart at the wrong time to a server full of people.

## There are no wall-clock times

`/schedulerestart` takes a **delay in minutes from the moment you press Enter**. It does
not take an hour, a time of day, a cron expression, or a date. There is no `03:00`
format to get wrong, no server timezone to misread, no `TimeZone` or `ZoneId` anywhere in
the source, and no daily or recurring schedule.

The upper bound is 60, so the furthest ahead you can arm anything is one hour. The
schedule lives in memory only: if the JVM exits for any other reason, the pending
shutdown is gone.

If you want a restart at 04:00 local time, something outside this mod has to decide that
04:00 has arrived and then send `/schedulerestart 5` over RCON or the console.

## The clock the timer actually uses

`Timer.schedule(task, delay)` converts the delay to an absolute instant using
`System.currentTimeMillis()`, so the countdown follows the **system wall clock**, not the
server tick count. Two consequences follow, and they cut in opposite directions:

- **Server lag does not stretch the countdown.** A server running at 8 TPS still shuts
  down exactly ten minutes after the command. The warnings keep their spacing, because
  they are scheduled on the timer thread; each one only waits for the server thread to
  pick up its broadcast, which on a lagging server is a fraction of a second.
- **A clock step moves the countdown.** If NTP corrects the host clock forward by two
  minutes during the schedule, every remaining task becomes overdue and `Timer` fires
  them back to back — players get a burst of warnings and then the shutdown. Backwards
  steps delay everything by the same amount. This is a property of `java.util.Timer`
  rather than a decision, and the mod does nothing to guard against it.

## The full timeline

Taking `/schedulerestart 10` as the worked example, with `T` as the moment the final
task runs, ten minutes after the command:

| Fires at | Message | Colour |
|---|---|---|
| immediately | `⚠ Redémarrage du serveur dans 10 minutes !` | bold aqua |
| T − 9 min | `⚠ Redémarrage dans 9 minutes !` | bold aqua |
| T − 8 min | `⚠ Redémarrage dans 8 minutes !` | bold aqua |
| … | one per minute, same wording | bold aqua |
| T − 1 min | `⚠ Redémarrage dans 1 minute !` | bold aqua |
| T − 10 s | `⏱ Redémarrage dans 10 secondes...` | bold yellow |
| T − 9 s … T − 6 s | `⏱ Redémarrage dans 9 secondes...` and so on | bold yellow |
| T − 5 s | `⏱ Redémarrage dans 5 secondes...` | **bold red** |
| T − 4 s … T − 2 s | same wording, counting down | bold red |
| T − 1 s | `⏱ Redémarrage dans 1 seconde...` | bold red |
| T | `🔄 Redémarrage en cours...` | bold red |
| T + 1 s | nothing is broadcast; the server begins shutting down | — |

Two details in that table are easy to miss. The **opening message is the only one that
says `du serveur`** — every later minute reminder drops it, which is a cosmetic
inconsistency in the source rather than a signal of anything. And the **colour changes at
five seconds**, from yellow to red, which is the last visual cue a player gets.

For a shutdown (`/schedulerestart 10 stop`) the table is identical except that
`Redémarrage` becomes `Arrêt` and the bold aqua of the minute warnings becomes **bold
gold**. The ten-second countdown keeps its yellow and red in both modes, so the colour
alone does not distinguish a restart from a shutdown in the final seconds.

## The arithmetic behind it

Three loops produce the table, and reading them is faster than trusting a summary.

The opening message is unconditional, sent before anything is scheduled:

```java
broadcastColored(server, "⚠ " + actionCap + " du serveur dans " + minutes
    + " minute" + (minutes > 1 ? "s" : "") + " !", actionColor, true);
```

The minute reminders:

```java
for (int i = 1; i < minutes; i++) {
    final int remaining = minutes - i;
    currentTimer.schedule(task, i * 60 * 1000L);
}
```

The loop stops at `minutes - 1`, so the last reminder is `remaining = 1` at `T − 60 s`.
There is never a reminder saying "0 minutes", and **with `minutes = 1` the loop body
never runs at all** — a one-minute schedule has an opening message and then fifty seconds
of nothing.

The second-by-second countdown:

```java
for (int i = 10; i > 0; i--) {
    final int seconds = i;
    long delay = (minutes * 60 - seconds) * 1000L;
    ...
    Formatting countColor = seconds <= 5 ? Formatting.RED : Formatting.YELLOW;
}
```

Ten tasks, at `T − 10 s` through `T − 1 s`. The delay is computed from the *total*
duration, so these are always anchored to the end of the schedule, never to the start.
The colour threshold is `seconds <= 5`, which puts the switch between the 6-second and
5-second message.

Pluralisation everywhere is `(n > 1 ? "s" : "")`, applied to both minutes and seconds.
That is correct French: `1 minute`, `2 minutes`. It also means `0` would print as
`0 minute`, but no code path can produce 0.

## Who receives the messages

```java
server.execute(() -> server.getPlayerManager().broadcast(finalText, false));
```

Every broadcast is wrapped in `server.execute(...)`, which queues it onto the main server
thread. The timer thread never touches the player manager directly — this is the one
piece of thread discipline in the mod and it is correct.

The recipients are whoever is **online at the instant the task runs**, resolved then and
not cached. A player who logs in at `T − 30 s` sees only the remaining second-by-second
countdown and has no idea a restart was announced nine minutes earlier. A player who logs
out at `T − 5 min` never learns the server went down. There is no join message, no boss
bar, no action bar, and no tab-list indicator: if a player has chat hidden, they get no
warning at all.

⚠️ Because the broadcasts are queued onto the server thread, a server whose main thread
is wedged — a deadlock, a long chunk-save stall, a watchdog freeze — stops showing
warnings while the timer thread keeps counting. The same wedged thread will also never
run the `server.stop(false)` hand-off, so the shutdown silently does not happen. In
shutdown mode that fails closed, which is the safe direction, but it looks to an admin
like the schedule simply vanished. In **restart mode it does not fail closed**:
`restartServer()` runs on the timer thread, not the server thread, so at zero the
replacement JVM is spawned anyway, next to a frozen server that still holds the port and
`session.lock`.

## What you cannot change

Every number on this page is a literal in `ScheduledRestart.java`. The one-minute
interval, the ten-second countdown, the five-second colour switch, the emoji, the
wording, the bold — all hardcoded, with no config file and no reload. Which line to edit
for each of them is listed in [configuration](configuration.md).

---

← [Commands](commands.md) · [What happens at zero](restart.md) →
