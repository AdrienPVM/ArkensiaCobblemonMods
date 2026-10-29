[← ScheduleRestart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this walkthrough you will have watched a full two-minute shutdown from the
inside — every warning, in order, with the right colours — and then set up a supervisor
so that `/schedulerestart` actually brings the server back instead of merely hoping to.
Two minutes is the shortest schedule that exercises every branch of the countdown, which
is why we use it rather than one.

Do this on a test server. Step 5 stops the server on purpose.

## 1. Get the three jars into `mods/`

```bash
cd ScheduleRestart
./gradlew --version          # must print Gradle 8.10
./gradlew build
cp build/libs/ScheduleRestart-1.0.0+1.21.1.jar /path/to/test-server/mods/
```

The `mods/` folder also needs `fabric-api-*.jar` and `cheesedash-*.jar`. The CheeseDash
jar is required by `fabric.mod.json` even though this mod never calls into it, so the
server refuses to start without it.

If the jar you just built is only a few hundred bytes, stop here and read
[the jar is tiny and the mod does not load](troubleshooting.md#the-jar-is-tiny-and-the-mod-does-not-load).

## 2. Start the server and confirm the mod loaded

```bash
java -Xmx4G -jar fabric-server-launch.jar nogui
```

Use that exact shape of command line — `java … -jar <launcher>.jar nogui` — because it is
the one launch layout the relaunch in step 6 can reconstruct. Then:

```bash
grep -n "scheduledrestart" logs/latest.log
```

```text
	- scheduledrestart 1.0.0+1.21.1
```

The mod prints no banner of its own, so this line in Fabric's mod list is the proof.
Nothing appears in `config/` — there is no config file to create.

## 3. Confirm the command is there

From the server console, type:

```mcfunction
schedulerestart
```

```text
Unknown or incomplete command, see below for error
```

That error is the success case: the command exists but `minutes` is mandatory, so a bare
invocation is a syntax error. If the console instead says the command is unknown at the
first character, the mod did not register — go back to step 2.

In game, log in as an operator and type `/schedule`. `schedulerestart` should appear in
tab completion. A player below permission level 2 sees nothing, which is the intended
behaviour and not a bug.

## 4. Join with a second account, or at least be in game

The whole point of the mod is what players see. The console does log each warning as
plain text, but without the colours and bold that make the countdown readable, and the
green confirmation goes only to the caller and the other operators. Be logged in as a
player for the next step, and issue the command from the console or from that same
player.

## 5. Schedule a two-minute shutdown and watch it

```mcfunction
/schedulerestart 2 stop
```

The `stop` keyword matters. It means *shut down and do not relaunch*, which is what you
want while you are still learning the timeline. Type it exactly: any other word silently
means "restart", and `/schedulerestart 2 shutdown` would relaunch the server.

The caller immediately sees, in green:

```text
✅ Arrêt planifié dans 2 minutes
```

and in chat, every player sees, in bold gold:

```text
⚠ Arrêt du serveur dans 2 minutes !
```

Now watch the clock. Here is what should arrive, and when:

| Time | What you see |
|---|---|
| T + 1:00 | `⚠ Arrêt dans 1 minute !` — bold gold |
| T + 1:50 | `⏱ Arrêt dans 10 secondes...` — bold yellow |
| T + 1:51 → 1:54 | `9`, `8`, `7`, `6` secondes — still yellow |
| T + 1:55 | `⏱ Arrêt dans 5 secondes...` — turns **bold red** |
| T + 1:56 → 1:59 | `4`, `3`, `2`, `1` seconde — red |
| T + 2:00 | `🔄 Arrêt en cours...` — bold red |
| T + 2:01 | you are disconnected; the server saves and exits |

Three things to notice while it runs. The opening message is the only one that says
`du serveur`. The colour switches from yellow to red exactly at five seconds. And the
messages are in French, with no language file anywhere in the mod — that is a limitation,
not a setting; [configuration](configuration.md#translating-the-messages) says which lines
to edit.

The full timeline, including the arithmetic and what happens with other delays, is in
[the countdown](countdown.md).

## 6. Make a restart actually restart

The server is now down. Start it again and try the other mode:

```mcfunction
/schedulerestart 2
```

With no second argument this is a **restart**: at zero the mod builds a
`java … -jar … nogui` command line out of the running process and starts it with
`ProcessBuilder` before stopping the old server. Watch the log for

```text
[ScheduledRestart] Relancement : /usr/bin/java -Xmx4G -jar fabric-server-launch.jar nogui
```

If you launched the server exactly as in step 2, this works and the server comes back on
its own. If you launched it any other way — a panel, a classpath, a wrapper that passes
extra arguments — one of several things goes wrong, and
[why a hosting panel breaks this](restart.md#why-a-hosting-panel-breaks-this) lists them.

**On anything other than a bare `java -jar` setup, stop relying on the relaunch.** Use a
supervisor and only ever use `stop` mode. A systemd unit is the straightforward version:

```ini
[Unit]
Description=Minecraft Fabric server
After=network.target

[Service]
WorkingDirectory=/path/to/server
ExecStart=/usr/bin/java -Xmx8G -jar fabric-server-launch.jar nogui
Restart=always
RestartSec=10
User=minecraft

[Install]
WantedBy=multi-user.target
```

A shell wrapper does the same job with less ceremony:

```bash
while true; do
  java -Xmx8G -jar fabric-server-launch.jar nogui
  echo "server exited, restarting in 5s"
  sleep 5
done
```

With either in place, `/schedulerestart 5 stop` gives you the warnings, the countdown and
a clean vanilla save, and the supervisor handles the part the mod is bad at. The port and
the world lock are free by the time the new process starts, which is the thing the mod's
own relaunch cannot guarantee.

## 7. Know how to back out

There is no cancel command. If you schedule something you regret:

```mcfunction
/schedulerestart 60
```

A new schedule replaces the old one, so this pushes the event an hour out — and no
retraction is announced, so your players will see a confusing second warning. Then stop
the server by hand, or restart it for real, and the forgotten timer dies with the JVM.

That is the whole workaround, and it is why the missing `cancel` is at the top of
[known weaknesses](architecture.md#known-weaknesses).

## What to read next

- [Commands](commands.md) for the argument table, the permission level and every failure
  message.
- [What happens at zero](restart.md) before you use restart mode on a production server.
- [Troubleshooting](troubleshooting.md) when a step above did not do what this page
  said it would.

---

← [Installation](installation.md) · [Commands](commands.md) →
