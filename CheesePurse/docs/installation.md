[← CheesePurse](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

CheesePurse declares `"environment": "*"`, so Fabric loads it on the server and on the
client. Both sides do real work and neither side is optional: the server runs the
deposit loop, the client draws the button that turns the loop on. A server with the mod
and clients without it has a feature nobody can reach.

---

## The three names of this mod

This trips up every first install, so it comes before anything else.

| Where | Name |
|---|---|
| Repository folder | `CheesePurse` |
| Mod id (`fabric.mod.json`) | `numismatic-cheesepurse` |
| Display name | `Numismatic Cheesepurse` |
| Java package | `com.roicheese.numismaticheesepurse` |
| Mixin config file | `numismaticheesepurse.mixins.json` |
| Language keys | `numismaticheesepurse.button.tooltip.*` |
| Built jar | `numismatic-cheesepurse-1.0.0+1.21.1.jar` |

The jar name comes from `archives_base_name=numismatic-cheesepurse` in
`gradle.properties`, not from the folder. There is no `cheesepurse-*.jar` and never
was. If you are scripting a deployment with a glob, match
`numismatic-cheesepurse-*+1.21.1.jar` and exclude `-sources.jar`.

The package spelling is also irregular: `numismaticheesepurse`, with a single `c`
shared between "numismatic" and "cheesepurse". It is spelled that way consistently
across the Java package, the mixin config and the translation keys, so it is not a typo
you should fix — fixing it in one place only would break the mixin load or the tooltips.
Only the asset namespace uses the hyphenated form `numismatic-cheesepurse`, because an
asset namespace has to match the mod id.

---

## What you need

### Server

| | Version | Why |
|---|---|---|
| Minecraft | **1.21.1** exactly | declared as `~1.21.1` |
| Fabric Loader | ≥ 0.17.2 | declared in `depends` |
| Fabric API | any 1.21.1 build | networking registry and `ServerTickEvents` |
| Java | 21 or newer | `compatibilityLevel: JAVA_21` |
| [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) | any (built against 0.3.4) | **hard dependency** — the server refuses to start without it |

Numismatic Overhaul in turn pulls in its own dependencies, notably Cardinal Components
and owo-lib. Install it the way its own page tells you to; CheesePurse adds no
requirements of its own beyond Numismatic being present.

### Client, for each player

| | Required? | Without it |
|---|---|---|
| CheesePurse | **yes** | no button in the inventory screen, no way to turn the feature on, and the server never hears from that player |
| Numismatic Overhaul | **yes** | the client refuses to launch — the `depends` entry applies on both sides |
| Fabric API | yes | the toggle packet is sent through `ClientPlayNetworking` |

The button comes from a client-only mixin on `InventoryScreen`, listed under `"client"`
in `numismaticheesepurse.mixins.json`. A vanilla client, or a Fabric client without this
jar, sees a stock inventory screen and can never enable the feature for itself.

There is no admin override. Nothing on the server can turn auto-collect on for a player
who cannot click the button.

---

## Build

CheesePurse depends on no sibling mod in this repository, so it builds on its own with
no ordering constraint:

```bash
cd CheesePurse
./gradlew build          # gradlew.bat on Windows
```

Numismatic Overhaul is resolved at build time from the Modrinth Maven repository, which
`build.gradle` declares:

```groovy
modCompileOnly "maven.modrinth:numismatic-overhaul:DPXG6kTJ"  // 0.3.4
```

`DPXG6kTJ` is a Modrinth version id, not a version number. It pins exactly one file. The
dependency is `modCompileOnly`, so Numismatic is on the compile classpath only and is
never bundled into the jar — players install it themselves.

The first build needs network access to `api.modrinth.com`. An offline machine fails
with an unresolved `maven.modrinth:numismatic-overhaul` dependency.

The jar you want:

```text
CheesePurse/build/libs/numismatic-cheesepurse-1.0.0+1.21.1.jar
```

Ignore `numismatic-cheesepurse-1.0.0+1.21.1-sources.jar`, and ignore everything under
`build/devlibs/` — those are development artifacts with unmapped names.

From the repository root, `tools/build-all.sh` builds every mod in dependency order and
includes this one.

## Install

1. Stop the server.
2. Copy `numismatic-cheesepurse-1.0.0+1.21.1.jar` into `mods/`.
3. Confirm Fabric API and Numismatic Overhaul are in the same folder.
4. Start the server.
5. Ship the same jar to every player, in their client `mods/`.

---

## What the first launch creates

Nothing.

No config file, no data folder, no world data, no player data of its own. The only
state the mod owns is a `Set<UUID>` in memory, and the only state it writes is Numismatic
Overhaul's own currency component, which Numismatic persists in player data.

That is worth repeating because it sets expectations: there is no file you can edit to
change the mod's behaviour, and no file you can delete to reset it. See
[configuration](configuration.md).

## Verifying it loaded

Two lines in the server log, from `NumismaticCheesePurse.onInitialize`:

```text
[NumismaticCheesePurse] Initializing…
[NumismaticCheesePurse] Ready.
```

They are logged by a logger named after the mod id, so most log formats render them with
a `numismatic-cheesepurse` prefix as well as the bracketed one in the message itself.
The two lines are adjacent: everything between them is packet registration and event
registration, which produces no output of its own. If you see the first without the
second, something threw during init — the stack trace is right after it.

Anything the mod does afterwards is logged at `DEBUG`:

```text
[NumismaticCheesePurse] Auto-collect ENABLED for Steve
[NumismaticCheesePurse] Collected 1 240 bronze units for Steve
```

A default server log level hides both. Raise the level for
`com.roicheese.numismaticheesepurse` or for the `numismatic-cheesepurse` logger if you
want to watch the loop work.

Then confirm in game:

1. Join the server and press E.
2. A small coin icon sits near the top-left of the inventory window, just above the
   player model.
3. Hover it. The tooltip reads `Cheesepurse: OFF` with a second line explaining the
   click.

No icon means the client jar is missing or the mixin did not apply — see
[troubleshooting](troubleshooting.md#there-is-no-button-in-my-inventory-screen).

## Uninstalling

1. Ask players to turn the toggle off, or do nothing and accept that the in-flight
   sweeps stop when the jar does. Nothing is left half-done: each sweep is a single
   synchronous call, so there is no partial state to drain.
2. Stop the server.
3. Remove the jar from `mods/`, on the server and on every client.

What stays behind:

- **Money already deposited stays in the purse.** That is Numismatic's component, not
  this mod's, and it is unaffected by removing CheesePurse.
- Nothing in `config/`, because nothing was written there.
- Nothing in world data or player data belonging to this mod.

Removing the jar from the server but leaving it on clients leaves the button visible and
clickable. Clicking it sends a payload on the `numismatic-cheesepurse:toggle_auto_collect`
channel that the server no longer has a receiver for; Fabric drops unknown play payloads
and logs a warning rather than disconnecting the client, so the symptom is a button that
changes colour and does nothing. Pull it from both sides at once.

---

← [CheesePurse](../README.md) · [Getting started](getting-started.md) →
