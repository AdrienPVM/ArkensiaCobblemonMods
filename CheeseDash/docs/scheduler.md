[← CheeseDash](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](scheduler.fr.md)

# Scheduler

`ServerScheduler.java`, 561 lines, schedules three things: a restart, a shutdown, or a
maintenance stop. It warns players in chat on a fixed countdown, saves the worlds, and
then stops the server. Recurring schedules survive a restart; one-shot actions do not.

The routes that drive it are in
[REST API → Scheduler](rest-api.md#scheduler). This page is about what happens after the
route returns `200`.

> ⚠️ Read [the section on `RESTART`](#restart-is-not-safe-on-every-host) before you
> schedule your first restart on a production server. On some hosting setups a `RESTART`
> stops the server and nothing brings it back.

---

## The three action types

| Type | What it does | Difference |
|---|---|---|
| `RESTART` | saves, spawns a new server process, stops this one | the only type that tries to come back |
| `STOP` | saves, stops | the server stays down |
| `MAINTENANCE` | saves, stops | identical to `STOP`, plus a `MAINTENANCE` Discord event |

`MAINTENANCE` is worth stating plainly, because the name suggests otherwise: it does
**not** put the server into a maintenance mode, does not block logins, does not restart
afterwards. `fireAction` treats it exactly like `STOP` — the save runs, the players get
the same stop message, `server.stop(false)` is called — and the only extra behaviour is
the Discord notification sent with the action's `message` attached. If you want players
to see a reason for the downtime, that message is where it goes.

---

## One-shot actions

A one-shot action is created through `POST /api/v1/server/schedule` with a type and a
delay between 5 and 86 400 seconds. Its id is `sa_` plus six hex characters.

**Only one can be pending.** `scheduleAction` begins by calling `cancelAction()`, so
creating a second one silently discards the first — including its chat announcement,
which players already saw. Cancel explicitly and create again if you mean to reschedule;
the dashboard will not warn you.

What the server does the moment the action is created:

1. Starts a countdown ticker, one tick per second, on the scheduler's thread pool.
2. Broadcasts `schedule_created` on the `schedule` WebSocket channel, which in turn
   fires the `RESTART_SCHEDULED` Discord event for any webhook subscribed to it.
3. Logs the line `[CheeseDash] Restart scheduled in 600s by admin: Weekly maintenance`.
4. Sends an immediate chat warning to every online player, whatever the delay. So a
   restart scheduled an hour out announces itself at once, then again at each threshold
   below.

**One-shot actions are never written to disk.** The config file holds recurring
schedules only. A server that goes down during a countdown comes back with nothing
pending, and neither the dashboard nor the log mentions the action that was lost.

### The countdown

Chat warnings fire at ten thresholds, in seconds:

```text
1800  900  600  300  180  120  60  30  10  5
```

That is 30 min, 15, 10, 5, 3, 2, 1 min, then 30 s, 10 s and 5 s. Thresholds above the
delay are skipped — a 60-second shutdown announces itself at creation, then at 30, 10 and
5 seconds.

The message is built from the language files, so players see their own language:

```text
[CheeseDash] Restart in 5 minutes... (Weekly maintenance)
```

```text
[CheeseDash] Redémarrage dans 5 minutes... (Weekly maintenance)
```

The trailing parenthesis is the action's `message`, free text you supplied, omitted when
empty. It is not translated — it is your string, shown as you typed it.

The duration itself is **not** translated either. `ServerScheduler.formatTime` builds it
in Java with hardcoded words, and those words are French-leaning: under a minute it
produces `30 secondes`, so an English client reads
`[CheeseDash] Restart in 30 secondes...`. Above a minute it happens to read correctly in
both (`5 minutes`), and above an hour it is language-neutral (`1h30m`). Fixing it means
moving the unit words into the language files; it has not been done.

> The match is `remaining == threshold`, on a once-a-second tick computed from the wall
> clock. A tick that arrives late — a long GC pause, a frozen main thread, a laptop
> resuming from sleep — skips the threshold entirely and no warning is sent for it. The
> action still fires on time. In practice you lose the occasional intermediate warning,
> never the restart.

Over the WebSocket, `schedule_tick` goes out every five seconds and every second over
the last ten. Payloads are in [WebSocket](websocket.md#schedule_tick--channel-schedule).

### What happens when it fires

In order, as written in `fireAction`:

1. The ticker stops and `activeAction` is cleared.
2. `schedule_fired` goes out on the `schedule` channel, which maps to the
   `SCHEDULED_TASK_FIRED` Discord event.
3. On the server thread: players are told `Saving worlds...`, then
   `server.save(false, true, true)` runs, then `Save complete.`. The scheduler thread
   waits on a latch for up to **30 seconds** for that to finish. A save that takes
   longer does not abort — the scheduler stops waiting and carries on, which on a very
   large world means the stop can begin while the save is still running.
4. For `MAINTENANCE`, the Discord event is sent.
5. For `RESTART`, `restartProcess()` runs — before the server stops, so the new process
   starts while the old one is still shutting down.
6. On the server thread: `Server stopping...` in chat, then `server.stop(false)`.

A save failure is logged as `Save failed before Restart: …` and does not stop the
sequence. The server stops either way.

---

## `RESTART` is not safe on every host

`restartProcess()` guesses how to relaunch the server. It takes the Java binary from
`ProcessHandle.current()`, the JVM arguments from `ManagementFactory`, and then looks
for the jar:

```java
for (int i = 0; i < processArgs.size() - 1; i++) {
    if (processArgs.get(i).equals("-jar")) { jarPath = processArgs.get(i + 1); break; }
}
if (jarPath == null) {
    // scan the working directory for fabric-server-launch*.jar, else server*.jar
}
if (jarPath == null) throw new Exception("Cannot find server jar for restart");
```

It then runs `<java> <jvm args> -jar <jar> nogui` from the current working directory
with `inheritIO()`.

That works when the server was launched as `java -Xmx8G -jar fabric-server-launch.jar
nogui`. It fails in several common setups:

- **A wrapper script or process manager.** Pterodactyl, systemd with a shell wrapper,
  Docker entrypoints, any panel that launches the JVM with `-cp` and a main class rather
  than `-jar`, or from a different working directory. The argument scan finds no `-jar`,
  the directory scan finds no matching file, and the method throws.
- **A jar with a name the scan does not recognise.** The fallback only matches
  `fabric-server-launch*.jar` or `server*.jar`.
- **A launcher that passes JVM options through a file or an environment variable.** The
  new process inherits only what `getInputArguments()` reports, so a restart can come
  back with a different heap size than the one you configured.

> ⚠️ **The failure happens after the shutdown has already been committed.** Look at the
> order in `fireAction`: the exception from `restartProcess()` is caught, logged as
> `Failed to spawn restart process: …`, and execution continues straight into
> `server.stop(false)`. The server shuts down and nothing starts it again. Your
> players see a scheduled restart and the server never comes back.
>
> If your host restarts the process automatically when it exits, you get the opposite
> hazard: two servers briefly sharing one world directory, because CheeseDash's child
> process starts before the parent has released its file locks.

**Test it safely, once, before you trust it.** On a server nobody is playing on:

1. `POST /api/v1/server/schedule` with `{"type":"RESTART","delaySeconds":30}`.
2. Watch the log. A working setup prints
   `[CheeseDash] Restart process spawned: /usr/bin/java -Xmx8G -jar fabric-server-launch.jar nogui`.
   Read that line: it tells you the exact command, so you can see whether the heap size
   and the jar are right.
3. If instead you see `Failed to spawn restart process:` or
   `Cannot find server jar for restart`, `RESTART` is not usable on this host.

When it is not usable, use `STOP` with your host's auto-restart, or put the restart in
your host's own scheduler and use CheeseDash only for the countdown announcement — a
`MAINTENANCE` action timed a minute before the external restart gives players the
warning without CheeseDash touching the process.

---

## Recurring schedules

A recurring schedule is a time of day plus a set of weekdays, persisted to
`config/cheesedash_schedules.json`. Its id is `rec_` plus six hex characters.

```json
{
  "recurring": [
    {
      "id": "rec_9b21ef",
      "type": "RESTART",
      "hour": 5,
      "minute": 0,
      "daysOfWeek": [1, 2, 3, 4, 5, 6, 7],
      "message": "Nightly restart",
      "enabled": true
    }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | string | generated; regenerated if absent from the file |
| `type` | string | `RESTART`, `STOP` or `MAINTENANCE`. An unknown value makes the whole entry unloadable |
| `hour` | int | 0–23, server local time |
| `minute` | int | 0–59 |
| `daysOfWeek` | array of int | ISO: 1 = Monday … 7 = Sunday. All seven days when the schedule is created without it |
| `message` | string | shown to players in the countdown, `""` by default |
| `enabled` | bool | `true` when the key is absent |

The file is written on every create, update, delete and toggle, on a throwaway daemon
thread. An entry that fails to parse is skipped with
`[CheeseDash] Skipping invalid recurring schedule: …` and the others still load, so one
hand-edited typo does not cost you the rest of the file.

### How a recurring schedule fires

A checker runs **every 30 seconds**, starting 30 seconds after `SERVER_STARTED`, and it
is the only clock involved. For each enabled schedule whose day set contains today, it
compares the current hour and minute to the schedule's:

```java
if (currentHour == schedule.hour && currentMinute == schedule.minute) {
    scheduleAction(schedule.type, 300, schedule.message, "scheduler");
    schedule.enabled = false;
    executor.schedule(() -> { schedule.enabled = true; }, 90, TimeUnit.SECONDS);
    break;
}
```

Four consequences worth understanding:

**A recurring schedule does not fire at its time — it starts a 5-minute countdown at its
time.** A `RESTART` set for 05:00 announces itself at 05:00 and the server actually goes
down at 05:05. Set the time five minutes before you want the downtime.

**`createdBy` on the resulting action is `scheduler`,** which is how you tell an
automatic restart from one an admin triggered when reading the audit log or the Discord
channel.

**The schedule disables itself for 90 seconds after firing,** so the twice-a-minute
checker cannot trigger the same minute twice. If the server stops inside that window —
which for a 5-minute restart it does not, but for a short-delay setup it might — the
schedule is persisted while disabled only if something else saves the file in the
meantime; the re-enable happens in memory, on a timer. Worth knowing if you ever find a
schedule sitting at `enabled: false` with nobody having touched it.

**Nothing fires while a one-shot action is pending.** `checkRecurring` returns
immediately if `activeAction != null`, so a manual restart countdown that spans 05:00
makes the 05:00 schedule miss its window entirely. It does not catch up: it waits for
tomorrow.

And since the comparison is on hour and minute only, a server that is down at 05:00 never
runs that day's restart.

### Day numbers are not validated

`POST /api/v1/server/schedule/recurring` accepts any integers in `daysOfWeek`.
`[0]` and `[9]` are stored and never match `DayOfWeek.getValue()`, so the schedule exists
in the dashboard, shows as enabled, and never fires. The same goes for `hour` and
`minute` on the `PUT` route, which — unlike the create route — range-checks nothing: an
`hour` of `99` is persisted and silently dead. If a recurring schedule never fires,
read the raw JSON before anything else.

---

## Threading and shutdown

Everything the scheduler does on its own runs on a two-thread
`ScheduledExecutorService` whose threads are daemons named `CheeseDash-Scheduler`. Every
touch on the game — chat broadcasts, the world save, the stop — is handed to the server
thread with `server.execute(...)`. The 30-second save latch is the one place the
scheduler blocks, and it blocks its own thread, never the game's.

Disk writes go on short-lived daemon threads named `CheeseDash-ScheduleSave`. A JVM that
dies in the same millisecond as a save can lose that one write; with recurring
schedules changing a few times a year, that is an accepted trade.

On `SERVER_STOPPING`, `shutdown()` cancels the countdown and the recurring checker and
calls `executor.shutdownNow()`. Nothing is flushed on the way out, which is the other
reason a pending one-shot action does not survive: there is no code to save it and no
code to restore it.

---

← [Web UI](web-ui.md) · [Discord webhooks](discord-webhooks.md) →
