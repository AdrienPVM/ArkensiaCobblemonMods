[← ScheduleRestart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](restart.fr.md)

# What happens at zero

The last task on the timer is the only one that does anything irreversible. Read this
page before you point the mod at a server anyone cares about, because the shutdown half
is solid and the relaunch half is not.

## The final task, line by line

```java
currentTimer.schedule(new TimerTask() {
    @Override
    public void run() {
        broadcastColored(server, "🔄 " + actionCap + " en cours...", Formatting.RED, true);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (!isStop) {
            try {
                restartServer();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        server.execute(() -> server.stop(false));
    }
}, minutes * 60 * 1000L);
```

Four steps, in this order:

1. **Announce.** `🔄 Redémarrage en cours...` (or `🔄 Arrêt en cours...`) in bold red,
   queued onto the server thread like every other broadcast.
2. **Sleep one second** on the timer thread. This is what gives the announcement time to
   reach clients before the network shuts down. The server thread is not blocked by it —
   the sleep happens on the timer's own thread — so the server keeps ticking normally for
   that second.
3. **Spawn the replacement JVM**, restart mode only. This happens *before* the shutdown,
   not after. See [the relaunch](#the-relaunch) for why that matters.
4. **Ask the server to stop**, from the server thread.

## The shutdown is the vanilla shutdown

```java
server.execute(() -> server.stop(false));
```

`MinecraftServer.stop(boolean)` is the same method the vanilla `/stop` command calls. The
mod does not kill the process, does not call `System.exit`, does not send `SIGTERM`, and
does not run any command. It calls the ordinary API and lets Minecraft do its own
teardown:

- players are disconnected with the standard *Server closed* message
- every loaded world is saved
- player data is written
- the level storage session is released and the `session.lock` is dropped

**The mod issues no explicit save of its own.** There is no `/save-all`, no
`saveAll(...)` call, and no flush before the stop. The save happens because the vanilla
shutdown sequence performs it, exactly as it would if an operator typed `/stop`. If you
have a mod that corrupts data on a normal `/stop`, this does not help; if `/stop` is safe
on your server, this is safe too.

The `server.execute(...)` wrapper matters: `stop` must run on the main server thread, and
the final task runs on a `java.util.Timer` thread. Calling `stop` directly from the timer
thread would tear the server down from under its own tick loop. The mod gets this right.

In shutdown mode (`stop` as the second argument) that is the whole story. The process
ends and nothing tries to bring it back.

## The relaunch

In restart mode, `restartServer()` runs just before the stop is queued. It builds a new
command line from the current process and starts it with `ProcessBuilder`.

### How the Java binary is found

```java
String javaBin = ProcessHandle.current().info().command()
        .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
```

The executable of the running process, with `$JAVA_HOME/bin/java` as a fallback. On a
normal Linux or Windows host the first branch resolves and the child runs under the same
JVM as the parent.

### How the JVM flags are carried over

```java
List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
```

This returns the **JVM** arguments — `-Xmx8G`, `-XX:+UseG1GC`, every `-D` system
property, every `-javaagent`. It does **not** return the program arguments, and it does
not include `-jar` or the jar path. So the memory settings and GC flags survive a restart,
and anything after the jar name does not.

### How the server jar is found

Two strategies, tried in order.

```java
for (int i = 0; i < processArgs.size() - 1; i++) {
    if (processArgs.get(i).equals("-jar")) {
        jarPath = processArgs.get(i + 1);
        break;
    }
}
```

First, the mod scans its own process arguments for `-jar` and takes the token after it.
This is the reliable path, and it only works if the server was launched as
`java … -jar something.jar`.

If there is no `-jar` — a `-cp`/classpath launch, a launcher script that uses a main
class directly, or most hosting panels — it falls back to scanning the working directory:

```java
for (File f : workDir.listFiles()) {
    String name = f.getName().toLowerCase();
    if (name.startsWith("fabric-server-launch") && name.endsWith(".jar")) {
        jarPath = f.getName();
        break;
    }
    if (name.startsWith("server") && name.endsWith(".jar")) {
        jarPath = f.getName();
    }
}
```

Note the asymmetry: the `fabric-server-launch*.jar` branch **breaks** out of the loop, the
`server*.jar` branch does not. So a directory containing both `server.jar` (the vanilla
bundle Fabric downloads) and `server-1.21.1.jar` keeps whichever `server*.jar` comes last
in `listFiles()` order, which is filesystem-dependent and not alphabetical by
specification. A directory containing a `fabric-server-launch.jar` is fine, as long as
`listFiles()` reaches it — and if some `server*.jar` matched earlier in the iteration, it
is overwritten by the fabric launcher only when the fabric file is reached, which it will
be, because the `break` is unconditional once it matches.

The practical rule: **this works on a `java -jar fabric-server-launch.jar nogui` layout
and is a coin toss on anything else.**

If neither strategy finds a jar:

```java
throw new Exception("Impossible de trouver le jar du serveur pour le redémarrage !");
```

That exception is caught by the `catch (Exception e)` around the call, printed as a stack
trace, and then **discarded. The shutdown proceeds anyway.** The server stops and does
not come back. There is no abort, no retry, and no message to anyone still online.

### The command that gets run

```java
command.add(javaBin);
command.addAll(jvmArgs);
command.add("-jar");
command.add(jarPath);
command.add("nogui");
```

so the child process is

```text
<java> <all JVM flags> -jar <jarPath> nogui
```

and the mod logs it, which is the one line you can grep for afterwards:

```text
[ScheduledRestart] Relancement : /usr/bin/java -Xmx8G -XX:+UseG1GC -jar fabric-server-launch.jar nogui
```

`nogui` is appended unconditionally. Any **other** program argument the server was
started with is lost: `--universe`, `--world`, `--port`, `--bonusChest`, a custom
`--serverId`. If your start script passes anything after the jar name beyond `nogui`, the
restarted server runs without it.

```java
ProcessBuilder pb = new ProcessBuilder(command);
pb.directory(new File(System.getProperty("user.dir")));
pb.inheritIO();
pb.start();
```

The working directory is inherited explicitly, so relative paths resolve the same way.
`inheritIO()` hands the child the parent's own stdin, stdout and stderr.

## Why a hosting panel breaks this

Four independent reasons, any one of which is enough.

**The launch is usually not `-jar`.** Pterodactyl, Multicraft and most panels build a
startup command with a classpath or a wrapper, so the `-jar` scan fails and the directory
fallback has to guess. Often it guesses the vanilla `server.jar`, which starts a
**vanilla** server with no mods — the worst outcome, because the server does come back
and silently loads no mod at all.

**The child is spawned before the parent dies.** `restartServer()` runs, then the stop is
queued. For a moment both JVMs exist. The new one tries to bind port 25565 while the old
one still holds it, and tries to acquire `session.lock` on a world the old one has not
released. Depending on timing it either crashes with *Failed to bind to port* or
*world is already in use*, or it survives because its own startup takes longer than the
old server's shutdown. **The mod relies on that race resolving in its favour.** It has no
wait, no retry, and no handshake.

**Panels kill process groups.** When the parent exits, the panel's supervisor sees its
managed process end and reaps the whole process group, or marks the server as stopped and
refuses to acknowledge a process it did not start. The child either dies with its parent
or runs invisibly, with the panel showing *offline* while port 25565 is answering.

**`inheritIO()` points at handles that are about to close.** The child's console is the
dying parent's console. On a panel, the parent's stdout is a pipe the supervisor closes
when it reaps the process, so the child's log output goes nowhere and its stdin is dead —
meaning you cannot type `stop` into it ever again.

### What to do instead

Use the shutdown mode and let something outside the JVM restart the server. That is the
reliable arrangement, and it is what [getting started](getting-started.md) walks you
through:

```mcfunction
/schedulerestart 5 stop
```

- **systemd**: a unit with `Restart=always`. The mod stops the process, systemd starts it
  again, the port and the world lock are free by then because the units are sequential.
- **A wrapper script**: `while true; do java -jar fabric-server-launch.jar nogui; sleep 5; done`.
- **A hosting panel**: turn on *auto-restart on crash* or *restart on stop*, whichever
  your panel calls it. The panel then owns the restart and the mod only ever has to stop
  cleanly.

In all three cases the countdown, the warnings and the clean save still come from this
mod. Only the relaunch moves somewhere that can actually do it.

---

← [The countdown](countdown.md) · [Configuration](configuration.md) →
