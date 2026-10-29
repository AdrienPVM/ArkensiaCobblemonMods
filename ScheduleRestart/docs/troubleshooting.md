[← ScheduleRestart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

## The jar is tiny and the mod does not load

`build/libs/ScheduleRestart-1.0.0+1.21.1.jar` is a few hundred bytes — 261 is the classic
figure — the build reported success, and the server starts without ever mentioning
`scheduledrestart`.

**Cause.** Fabric Loom 1.8 does not work under Gradle 9. Its `remapJar` task completes
without an error and writes a jar containing no classes and no `fabric.mod.json`. Until
29 October 2026 this project's `gradlew` was a stub that delegated to whatever `gradle`
was on your `PATH` instead of downloading the pinned distribution, so anyone with Gradle 9
installed system-wide built an empty jar and got no warning. A clone from before that date
still has the stub.

**Fix.** Check which Gradle actually ran:

```bash
cd ScheduleRestart
./gradlew --version
```

It must print `Gradle 8.10`, the version pinned in
`gradle/wrapper/gradle-wrapper.properties`. If it prints 9.x or anything else, your
wrapper is the old stub. Pull the current repository, or restore a real wrapper:

```bash
gradle wrapper --gradle-version 8.10
./gradlew --version          # confirm 8.10
./gradlew clean build
ls -l build/libs/
```

A correct jar is a few kilobytes and contains `fabric.mod.json` plus
`com/schedulerestart/ScheduledRestart.class`:

```bash
unzip -l build/libs/ScheduleRestart-1.0.0+1.21.1.jar
```

If that listing shows two entries and a manifest, you have a real jar.

---

## The server refuses to start: `requires any version of mod 'cheesedash'`

```text
Mod 'Scheduled Restart' (scheduledrestart) 1.0.0+1.21.1 requires any version of mod
'cheesedash', which is missing!
```

**Cause.** `fabric.mod.json` lists `cheesedash` under `depends`. The dependency is real to
the loader even though the mod never uses a single CheeseDash class.

**Fix.** Install [CheeseDash](../../CheeseDash/) alongside it, or remove this mod. There is
no third option short of editing `fabric.mod.json` and rebuilding — which is a legitimate
thing to do if you want the countdown without the dashboard, and is discussed in
[the dependency that does nothing](architecture.md#the-dependency-that-does-nothing).

---

## The server crashes at startup with `NoClassDefFoundError: …CommandRegistrationCallback`

```text
java.lang.NoClassDefFoundError: net/fabricmc/fabric/api/command/v2/CommandRegistrationCallback
	at com.schedulerestart.ScheduledRestart.onInitialize(ScheduledRestart.java:27)
```

**Cause.** Fabric API is missing. The mod uses it and does not declare it in
`fabric.mod.json`, so the loader lets the mod start and the class is only missing once
`onInitialize` runs.

**Fix.** Drop a `fabric-api-*.jar` built for 1.21.1 into `mods/`. The mod was compiled
against `0.107.0+1.21.1`; any 1.21.1 build works.

---

## `/schedulerestart` is not in tab completion

**Cause.** Almost always permission, not installation. The whole command tree is gated by
`hasPermissionLevel(2)`, and Brigadier hides nodes a source cannot use, so a non-operator
sees nothing at all — no command, no error about permissions.

**Fix.** Check in this order:

1. Run the same command from the **server console**, which always has level 4. If it works
   there, the mod is fine and your account is the problem.
2. Confirm you are opped: `/op <name>` from the console, then rejoin or run
   `/reload`-equivalent — op changes take effect on the next command tree sync, which
   happens automatically.
3. Only if the console also does not know the command, check the mod loaded:
   `grep scheduledrestart logs/latest.log`.

A permissions mod that grants level-2 equivalence through its own node system does **not**
automatically satisfy `hasPermissionLevel(2)` unless it patches the command source. If
your permissions mod advertises vanilla level emulation, enable it.

---

## `Unknown or incomplete command` when I type `/schedulerestart` alone

**Cause.** Not an error in the mod. The root literal has no `executes` branch, so
`minutes` is mandatory and a bare invocation cannot run.

**Fix.** Give it a delay: `/schedulerestart 10`. Accepted values are 1 through 60
inclusive; `0` and `61` are rejected by Brigadier with
`Integer must not be less than 1` and `Integer must not be more than 60`.

---

## I asked for a shutdown and the server came back up

**Cause.** The second argument was not literally the word `stop`. The test is
`mode.equalsIgnoreCase("stop")` with no validation of the alternative, so
`/schedulerestart 5 shutdown`, `… halt`, `… off` and every other word are accepted and
mean *restart*. Neither argument offers tab completion, so nothing warns you.

**Fix.** Type `stop` and nothing else. Read the green confirmation before you walk away —
it says `Arrêt planifié` for a shutdown and `Redémarrage planifié` for a restart, and it
is the only feedback that distinguishes the two. The full table of what each word does is
in [the `stop` keyword trap](commands.md#the-stop-keyword-trap).

---

## The server stops and never comes back

The countdown ran, `🔄 Redémarrage en cours...` appeared, the server shut down, and
nothing started.

**Cause.** One of four, and the log tells you which.

Search for the mod's only log line:

```bash
grep -n "ScheduledRestart" logs/latest.log
```

**No `Relancement :` line, and a stack trace ending in
`Impossible de trouver le jar du serveur pour le redémarrage !`** — the relaunch could not
identify a server jar. The server was not launched with `-jar`, and the working-directory
fallback found no `fabric-server-launch*.jar` and no `server*.jar`. The exception is
caught and discarded, so the shutdown proceeded anyway.

**A `Relancement :` line naming the wrong jar** — the fallback guessed. If it names
`server.jar`, that is the vanilla bundle and the replacement process is a vanilla server.

**A `Relancement :` line that looks right, but the child died** — most likely the new JVM
lost the race against the old one and failed on *Failed to bind to port* or on the world's
`session.lock`. The child is spawned *before* the old server stops, so for a moment both
exist. The child's own output went to the parent's stdout via `inheritIO()`, so on a panel
it may have gone nowhere and left you no trace.

**You are on a hosting panel** — the panel reaped the process group when its managed
process exited, or it marks the server offline and refuses to adopt a process it did not
start.

**Fix.** Stop using the built-in relaunch and put a supervisor in front of it. Use
`/schedulerestart <minutes> stop` so the mod only ever performs the clean shutdown, and
let systemd (`Restart=always`), a `while true` wrapper script, or your panel's
auto-restart option bring the server back. The exact units and scripts are in
[getting started, step 6](getting-started.md#6-make-a-restart-actually-restart), and the
mechanics of each failure are in
[why a hosting panel breaks this](restart.md#why-a-hosting-panel-breaks-this).

---

## The server came back with no mods

**Cause.** The relaunch fallback matched `server.jar` — the vanilla server bundle Fabric
keeps next to its launcher — instead of `fabric-server-launch.jar`, and started a plain
vanilla server. The two directory-scan branches are not symmetric: the
`fabric-server-launch*` branch breaks out of the loop, the `server*` branch does not, so a
directory with several `server*.jar` files keeps whichever one comes last in
`listFiles()` order.

**Fix.** Launch the server as `java … -jar fabric-server-launch.jar nogui` so the `-jar`
scan finds the right jar and the directory fallback is never reached. If you cannot change
the launch command, hardcode the jar name — the patch is in
[making the relaunch find your jar](configuration.md#making-the-relaunch-find-your-jar).

⚠️ Before restarting again, check the world. A vanilla server loading a modded world
strips unknown blocks and items. Restore from a backup rather than letting it save.

---

## I changed a value and nothing happened

**Cause.** There is no configuration file. Nothing in `config/` belongs to this mod, so
there is nothing to edit, nothing to reload, and no `/reload` path that would pick a
change up. Every timing, colour and message is a literal in `ScheduledRestart.java`.

**Fix.** Edit the source, rebuild, replace the jar, restart the server.
[Configuration](configuration.md) maps each behaviour to its line number. The command tree
and all messages are fixed at class-load time, so a running server keeps the old behaviour
no matter what you do to the files on disk.

---

## The dashboard has no ScheduleRestart page

**Cause.** The mod registers no CheeseDash module. It declares no `"cheesedash"` entrypoint
in `fabric.mod.json`, so `FabricLoader.getEntrypoints("cheesedash", …)` never finds it,
and CheeseDash builds its sidebar from registered modules only. There is no REST route
under `/api/v1/` and no WebSocket channel for it either.

**Fix.** None. This is not a misconfiguration; the page does not exist. The `cheesedash`
entry under `depends` is the reason people expect one — see
[the dependency that does nothing](architecture.md#the-dependency-that-does-nothing). Drive
the mod with the command, or use CheeseDash's own scheduler instead.

---

## Players say they never saw a warning

**Cause.** Three possibilities, all behavioural rather than broken.

Recipients are resolved **when each task fires**, not when the command runs. A player who
joined at `T − 30 s` only ever saw the last few seconds of countdown. A player who left
before the end saw whatever had been sent up to that point and nothing after.

Everything is sent to **chat** — `broadcast(text, false)`. There is no boss bar, no action
bar and no title. A player with chat hidden gets no warning whatsoever.

With `/schedulerestart 1` there are **no minute reminders at all**: the loop runs from 1 to
`minutes - 1`, which is empty, so there is the opening message, then fifty seconds of
silence, then the ten-second countdown.

**Fix.** Use a longer delay so the per-minute reminders have time to repeat, and tell
players out of band for anything disruptive. The exact message schedule is in
[the countdown](countdown.md#the-full-timeline).

---

## The countdown stopped partway and the server stayed up

The chat went quiet at, say, `⏱ Redémarrage dans 4 secondes...` and the shutdown never
happened.

**Cause.** The main server thread wedged. Every broadcast and the final
`server.stop(false)` are queued with `server.execute(...)`, so they only run when the
server thread drains its task queue. A deadlock, a watchdog freeze or a very long
chunk-save stall means the timer keeps counting on its own thread while nothing it queues
ever executes.

**Fix.** This is a symptom of a server-thread problem, not of this mod. Look in
`logs/latest.log` for the Minecraft watchdog's *Can't keep up* or
*A single server tick took* lines, or for a watchdog thread dump, and chase that. Once the
server thread recovers it will drain the whole queue at once, so expect a burst of
backlogged countdown messages followed immediately by the shutdown.

---

## Two different countdowns are running

Players report seeing both a 10-minute and a 30-minute warning.

**Cause.** They are not both running. The mod holds one `static Timer`, and each command
cancels it before replacing it, so only the most recent schedule is live. What players saw
is the old announcement still in their chat history plus the new one, with **no retraction
of the old** — the cancel is completely silent.

**Fix.** Announce the correction yourself with `/say`. The behaviour is documented in
[re-issuing cancels the previous schedule](commands.md#re-issuing-cancels-the-previous-schedule);
the missing cancel notice is in
[known weaknesses](architecture.md#known-weaknesses).

One real edge case: `Timer.cancel()` does not interrupt a task that has already started.
If you re-issue the command during the final task's one-second sleep, the old shutdown
still completes.

---

## My players read French and I do not want them to

**Cause.** Every player-facing string is a hardcoded French literal. There is no
`assets/scheduledrestart/lang/` directory, no `Text.translatable` call, and therefore no
resource pack or client language setting that can change the output.

**Fix.** Edit the literals and rebuild. The six lines involved and an English replacement
are in [translating the messages](configuration.md#translating-the-messages).

⚠️ The `cheesedash.scheduler.*` keys in the repository belong to CheeseDash's own
scheduler, not to this mod. Translating them changes nothing here.

---

## Filing a useful bug report

Open it at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues), and include:

1. **The exact command you typed**, including the second argument if there was one. The
   difference between `stop` and `shutdown` is the difference between two bugs.
2. **How the server is launched**, verbatim — the full `java` command line, or the panel's
   startup command, or the systemd `ExecStart`. Almost every relaunch report comes down to
   this line.
3. **`logs/latest.log`**, and specifically:
   - the Fabric mod list near the top, to prove the version that was loaded
   - every line matching `ScheduledRestart`, which is the `Relancement :` line and any
     stack trace from the relaunch
   - the last 200 lines before the shutdown, which contain the vanilla stop sequence
4. **Whether the schedule was a restart or a shutdown**, and whether the server came back.
5. **The jar's size**, if the complaint is that the mod did not load. A jar under 1 kB is
   the empty-`remapJar` bug and not a mod bug.
6. **Your Gradle version** (`./gradlew --version`) if you built it yourself.

Questions that are not bugs go to the [Arkensia Discord](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.md) · [ScheduleRestart](../README.md) →
