[← ScheduleRestart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

## Prerequisites

| Requirement | Version | Why |
|---|---|---|
| Minecraft | **1.21.1** exactly | `fabric.mod.json` pins `"minecraft": "1.21.1"`, not a range |
| Fabric Loader | **≥ 0.16.0** | declared in `depends`. Built against 0.16.9 |
| Java | **21 or newer** | declared in `depends`; `build.gradle` compiles with `release = 21` |
| Fabric API | any 1.21.1 build | used for `CommandRegistrationCallback`. Built against `0.107.0+1.21.1` |
| [CheeseDash](../../CheeseDash/) | any version | required by `depends`, and used for nothing. See below |

⚠️ Fabric API is **not** declared in `fabric.mod.json`. The mod will load without it and
then throw a `NoClassDefFoundError` on
`net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback` during initialisation,
which usually takes the server down with it. Install Fabric API; the loader will not
remind you.

⚠️ CheeseDash is required and unused. Fabric Loader refuses to start without the
CheeseDash jar, yet this mod registers no dashboard module and references no CheeseDash
class. You have to satisfy the dependency and you get nothing back —
[the full story](architecture.md#the-dependency-that-does-nothing).

## Which side

**Server only.** `fabric.mod.json` sets `"environment": "server"`, and the mod contains no
client code, no packets, no rendering and no key bindings. Players connect with an
unmodified client and need nothing installed. Putting the jar in a client's `mods/` folder
does nothing useful; the loader simply skips it in a client environment.

## Build

The mod has no sibling-mod dependency at build time — `build.gradle` contains no
`fileTree` glob pointing at another mod's `build/libs/` — so you can build it on its own,
in any order relative to the rest of the repository.

```bash
cd ScheduleRestart
./gradlew build          # gradlew.bat on Windows
```

The jar lands in `build/libs/`. `tools/build-all.sh` at the repository root builds every
mod in dependency order and includes this one.

⚠️ **Check your Gradle version before anything else.**

```bash
./gradlew --version
```

It must report `Gradle 8.10`. The wrapper in `gradle/wrapper/gradle-wrapper.properties`
pins that version, and Loom 1.8 does not work on Gradle 9: it produces a `remapJar` that
succeeds and writes a jar containing nothing. If you cloned this repository before
29 October 2026, the `gradlew` script was a stub that delegated to whatever Gradle was on
your `PATH`, which made the failure easy to hit. Symptom and fix are in
[the jar is tiny and the mod does not load](troubleshooting.md#the-jar-is-tiny-and-the-mod-does-not-load).

## The jar is not named after the mod

```text
build/libs/ScheduleRestart-1.0.0+1.21.1.jar
```

The mod id is `scheduledrestart` — one `d` more than the folder — and `gradle.properties`
even declares `archives_base_name=scheduledrestart`. `build.gradle` never applies it:
there is no `base { archivesName = ... }` block, so Gradle falls back to the project
name, and `settings.gradle` sets no `rootProject.name`, so the project name is the
directory name, `ScheduleRestart`.

The result is a jar called `ScheduleRestart-…` holding a mod called `scheduledrestart`.
Nothing breaks, because a jar's file name in `mods/` has no meaning to Fabric. But do not
search your `mods/` directory for `scheduledrestart`, find nothing, and conclude the mod
is not installed. The inconsistency is left alone deliberately so that the jar name
printed in this documentation matches what a build actually produces.

## Deploy

```bash
cp build/libs/ScheduleRestart-1.0.0+1.21.1.jar /path/to/server/mods/
```

Alongside it the server's `mods/` must contain:

- `fabric-api-*.jar`
- `cheesedash-*.jar`

Restart the server. There is nothing to configure.

## What the first launch creates

**Nothing.** No file in `config/`, no directory, no marker, no database. The mod writes
nothing to disk on its first launch or on any later one, and it never reads a file. The
only state it keeps is a `java.util.Timer` in memory, which is why
[configuration](configuration.md) is a page about source lines rather than about a file.

## Verifying it loaded

The mod prints no startup banner — `onInitialize()` registers the command and returns.
There are two reliable checks.

**In the log.** Fabric lists every loaded mod during startup:

```text
[main/INFO] (FabricLoader) Loading 42 mods:
	...
	- scheduledrestart 1.0.0+1.21.1
	...
```

Grep for the id, not the jar name:

```bash
grep -n "scheduledrestart" logs/latest.log
```

**In game.** Log in as an operator and type `/schedule`. If `schedulerestart` appears in
tab completion, the command tree registered. If you are not level 2 it will not appear
even when the mod is loaded, so run this check from the server console or as a full
operator.

A third, slower check is to run `/schedulerestart 1` on a test server and watch the
warnings arrive. Do not do that on a production server to confirm an install: it stops the
server a minute later and tries to relaunch it.

The only log line the mod ever writes itself is on a restart, immediately before the
shutdown:

```text
[ScheduledRestart] Relancement : /usr/bin/java -Xmx8G -jar fabric-server-launch.jar nogui
```

Its absence after a restart-mode countdown means the relaunch never got as far as
building a command line. [What happens at zero](restart.md) explains the branches.

## Uninstalling

Stop the server, delete the jar, start it again.

```bash
rm /path/to/server/mods/ScheduleRestart-1.0.0+1.21.1.jar
```

**Nothing is left behind.** No config file to clean up, no world data, no scoreboard
objective, no persistent NBT, no entity, no registered item that would turn into
`minecraft:air` on the next load. Removing the mod cannot corrupt a world, because it
never wrote to one.

One operational caveat: if you remove the mod while a schedule is armed, the schedule dies
with the JVM you stopped, so nothing fires later. And if you were relying on this mod for
your nightly restart, remember that it was never the thing performing the restart on a
supervised setup — your systemd unit or panel was. Removing the mod removes the countdown
and the clean `stop`, not the supervisor.

If you remove **CheeseDash** and leave this mod installed, the server will not start:

```text
Mod 'Scheduled Restart' (scheduledrestart) 1.0.0+1.21.1 requires any version of mod
'cheesedash', which is missing!
```

Which is the unused dependency biting. Remove both jars or keep both.

---

← [ScheduleRestart](../README.md) · [Getting started](getting-started.md) →
