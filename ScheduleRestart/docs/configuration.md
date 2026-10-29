[← ScheduleRestart](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

**There is no configuration file.** Nothing appears in `config/` on first launch, nothing
appears later, and the mod never reads or writes a file at any point in its lifetime.
`FabricLoader.getInstance().getConfigDir()` does not appear in the source. There is no
`*Config` class, no Gson, no reload command and no dashboard page.

Everything that could be configurable is a literal in the one source file,
`src/main/java/com/schedulerestart/ScheduledRestart.java`. Changing any of it means
editing that file and rebuilding the jar. This page is therefore a map of which line
controls what, so you do not have to read the whole class to change a colour.

The only thing an operator can vary at runtime is the command's own arguments: the delay
in minutes, and whether the second argument is the word `stop`. Those are documented in
[commands](commands.md).

## What is hardcoded, and where

Line numbers refer to the file as it stands at version `1.0.0+1.21.1`, 171 lines.

| Behaviour | Current value | Line | Code |
|---|---|---|---|
| Command name | `schedulerestart` | 28 | `CommandManager.literal("schedulerestart")` |
| Permission level | `2` | 29 | `source.hasPermissionLevel(2)` |
| Minimum delay | `1` minute | 30 | `IntegerArgumentType.integer(1, 60)` |
| Maximum delay | `60` minutes | 30 | same call |
| The shutdown keyword | `stop`, case-insensitive | 35 | `mode.equalsIgnoreCase("stop")` |
| Action nouns | `Arrêt` / `Redémarrage` | 52 | `String actionCap = isStop ? "Arrêt" : "Redémarrage";` |
| Warning colours | gold for shutdown, aqua for restart | 54 | `isStop ? Formatting.GOLD : Formatting.AQUA` |
| Opening message | `⚠ … du serveur dans N minute(s) !` | 56 | `broadcastColored(...)` |
| Minute reminder interval | every `60 000` ms | 65 | `i * 60 * 1000L` |
| Minute reminder text | `⚠ … dans N minute(s) !` | 63 | inside the first `TimerTask` |
| Second-countdown length | last `10` seconds | 68 | `for (int i = 10; i > 0; i--)` |
| Red/yellow threshold | `5` seconds | 75 | `seconds <= 5 ? Formatting.RED : Formatting.YELLOW` |
| Second-countdown text | `⏱ … dans N seconde(s)...` | 76 | inside the second `TimerTask` |
| Final announcement | `🔄 … en cours...` in red | 84 | `broadcastColored(..., Formatting.RED, true)` |
| Grace delay before stop | `1000` ms | 86 | `Thread.sleep(1000)` |
| How the server stops | `server.stop(false)` | 99 | the vanilla shutdown call |
| Bold on broadcasts | always on | 112 | `if (bold) text = text.formatted(Formatting.BOLD)` |
| Caller confirmation | `✅ … planifié dans N minute(s)`, green | 104 | `Text.literal(...).formatted(Formatting.GREEN)` |
| Fabric launcher jar pattern | `fabric-server-launch*.jar` | 143 | `name.startsWith("fabric-server-launch")` |
| Fallback jar pattern | `server*.jar` | 147 | `name.startsWith("server")` |
| Argument given to the child JVM | `nogui` | 162 | `command.add("nogui")` |

## The changes people actually want

### Raising the one-hour ceiling

`IntegerArgumentType.integer(1, 60)` on line 30 caps the delay at 60 minutes. Widening it
to `integer(1, 1440)` is safe arithmetic — the longest delay then becomes
`1440 × 60 × 1000 = 86 400 000` ms, far inside a `long`, and the `i * 60 * 1000L` in the
minute loop is already promoted to `long` by the `1000L` literal.

What is *not* safe is the consequence: a 24-hour schedule queues 1 450 timer tasks
(1 439 minute reminders, 10 countdown ticks, the final task) and
expects the JVM to live for a day without being restarted by anything else. The mod has
no persistence, so a crash at hour 20 silently forgets the whole thing.

### Lowering the permission level

Line 29. Dropping to `hasPermissionLevel(0)` would let any player stop the server, which
is why it is 2. If you want a trusted non-op to use it, keep the level and grant the
permission through a permissions mod rather than editing this line.

### Translating the messages

There is no language file to edit. Lines 52, 56, 63, 76, 84 and 104 hold the only
player-facing strings in the mod, as French literals. An English set would be:

```java
String actionCap = isStop ? "Shutdown" : "Restart";
```

and the five message templates rewritten to match, including their pluralisation, which
is currently `(n > 1 ? "s" : "")` — correct for both French and English.

Doing this properly means `Text.translatable` with keys in
`src/main/resources/assets/scheduledrestart/lang/en_us.json` and `fr_fr.json`. That
directory does not exist. Creating it is the right fix and is listed under
[known weaknesses](architecture.md#known-weaknesses).

⚠️ The `cheesedash.scheduler.*` translation keys you may find while grepping the
repository belong to **CheeseDash's own scheduler**, not to this mod. They live in
`CheeseDash/src/main/resources/assets/cheesedash/lang/`. This mod uses none of them and
has no lang files of its own, so searching its jar for translatable text finds nothing.

### Changing the countdown shape

Three independent pieces:

- The **minute reminders** come from the loop on lines 58–66. Changing `i * 60 * 1000L`
  to `i * 300 * 1000L` would not give you five-minute reminders, because the loop also
  computes `remaining = minutes - i` and would then announce the wrong number. If you
  want reminders at 30, 15, 5 and 1 minutes, replace the loop with explicit
  `schedule(...)` calls rather than adjusting the multiplier.
- The **second countdown** comes from the loop on lines 68–79 and is anchored to the end
  of the schedule by `(minutes * 60 - seconds) * 1000L`. Changing `i = 10` to `i = 30`
  gives a thirty-second countdown and works as written, as long as the total duration is
  longer than the countdown — which the `integer(1, 60)` minimum of one minute guarantees
  up to 59 seconds.
- The **colour threshold** is line 75 on its own and can be changed freely.

### Making the relaunch find your jar

Lines 139–151 are the fallback that runs when no `-jar` appears in the process arguments.
If your server is launched from a classpath and the fallback picks the wrong file, the
honest fix is to hardcode yours:

```java
if (jarPath == null) {
    jarPath = "fabric-server-launch.jar";
}
```

Replacing a guess with a known value is an improvement here, not a regression. Why the
guess exists and how it goes wrong is in
[what happens at zero](restart.md#how-the-server-jar-is-found).

## Build-time values

Two files hold values that look like configuration and are not reloadable at all.

`gradle.properties`:

```properties
mod_version=1.0.0+1.21.1
maven_group=com.schedulerestart
archives_base_name=scheduledrestart
minecraft_version=1.21.1
yarn_mappings=1.21.1+build.3
loader_version=0.16.9
fabric_version=0.107.0+1.21.1
```

⚠️ `archives_base_name=scheduledrestart` is **declared and never used**. `build.gradle`
has no `base { archivesName = ... }` block, so Gradle names the jar after the project,
which is the directory name. The jar is `ScheduleRestart-1.0.0+1.21.1.jar` while the mod
id is `scheduledrestart`. Both names are correct in their own world and neither matches
the other. Nothing breaks — the file name of a jar in `mods/` is cosmetic — but do not
grep your `mods/` directory for `scheduledrestart` and conclude the mod is missing. The
inconsistency is documented rather than fixed, so that the jar name in this
documentation keeps matching what a build actually produces.

`fabric.mod.json` declares the dependencies, and gets them wrong in both directions:

```json
"depends": {
  "fabricloader": ">=0.16.0",
  "minecraft": "1.21.1",
  "java": ">=21",
  "cheesedash": "*"
}
```

`cheesedash` is required and unused. `fabric-api` is used — the mod's only Fabric API
call is `CommandRegistrationCallback.EVENT.register` — and not declared. Both are
explained in [architecture](architecture.md#the-dependency-that-does-nothing).

## A complete example configuration

There is none to give, because there is no file. The nearest equivalent is the patch you
would apply to run the mod in English with a thirty-second countdown and a two-hour
ceiling, annotated so you can pick the parts you want:

```java
// line 30 — widen the accepted delay to two hours
CommandManager.argument("minutes", IntegerArgumentType.integer(1, 120))

// line 52 — English nouns; everything downstream reuses this string
String actionCap = isStop ? "Shutdown" : "Restart";

// line 56 — the opening announcement
broadcastColored(server, "⚠ Server " + actionCap.toLowerCase() + " in " + minutes
        + " minute" + (minutes > 1 ? "s" : "") + "!", actionColor, true);

// line 68 — thirty seconds of per-second countdown instead of ten
for (int i = 30; i > 0; i--) {

// line 75 — keep red for the final five seconds
Formatting countColor = seconds <= 5 ? Formatting.RED : Formatting.YELLOW;

// line 140 — stop guessing; name the launcher you actually use
jarPath = "fabric-server-launch.jar";
```

Rebuild with `./gradlew build` and redeploy the jar. There is no reload path: the command
tree and every message are fixed at class-load time, so a running server keeps the old
behaviour until it restarts.

---

← [What happens at zero](restart.md) · [Architecture](architecture.md) →
