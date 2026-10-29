[← CheeseMusic](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

## Which side needs the jar

**Both.** This is not a choice, and it is the first thing to get right.

`fabric.mod.json` declares `"environment": "*"` and three entrypoints:

| Entrypoint | Class | Runs on |
|---|---|---|
| `main` | `com.cheesemusic.CheeseMusicMod` | server, and the integrated server in single-player |
| `client` | `com.cheesemusic.client.CheeseMusicClient` | client only |
| `cheesedash` | `com.cheesemusic.dashboard.CheeseMusicDashboardModule` | server, and only when CheeseDash is installed |

The server decides *what* should play and sends a packet. The client is what actually
plays it. A server with the jar and a client without it: the client has no receiver
registered for `cheesemusic:zone_music`, drops the packet, and hears vanilla music as
normal. A client with the jar on a server without it: nothing ever sends a packet, so the
mod is inert.

So the jar goes in the server's `mods/` **and** in every player's `mods/`. A mixed server
works — players without it are not broken, they just hear nothing special.

## Prerequisites

| | Version | Needed for |
|---|---|---|
| Minecraft | 1.21.1 exactly (`~1.21.1`) | — |
| Fabric Loader | ≥ 0.16.0 | — |
| Fabric API | any | lifecycle events, networking, client tick events |
| Java | ≥ 21 | compiled with `options.release = 21` |

Those four are `depends` — the loader refuses to start without them.

| Optional, by `fabric.mod.json` | Reality |
|---|---|
| [CheeseGuard](../../CheeseGuard/) | `suggests`. Without it there are no regions, `resolveZoneMusic` returns `null` for every position, and no player is ever sent a track. The mod loads and does nothing |
| [CheeseDash](../../CheeseDash/) | `suggests`. Without it there is no editor. Assignments already in `config/cheesemusic.json` still play; you cannot change them without editing the file and restarting |
| [ReactiveMusic](https://modrinth.com/mod/reactive-music) | not declared at all, and **required in practice on every client**. See the warning below |
| [Cobblemon](https://modrinth.com/mod/cobblemon) | not declared. Only enables stopping zone music during a battle |

### ReactiveMusic

The client-side player waits for ReactiveMusic to confirm it has faded itself out before
starting a zone track, and that confirmation can only ever come from the ReactiveMusic
hook. With the mod absent, walking into an assigned region plays nothing and leaves
vanilla music suppressed until the player walks out again or reconnects. The exact trace
is in [playback](playback.md#what-happens-without-reactivemusic).

Install ReactiveMusic on every client, or expect silence in assigned regions.

## Build order

CheeseMusic compiles against CheeseDash's jar, resolved through a `fileTree` glob in
`build.gradle`:

```groovy
modImplementation files(fileTree(dir: "../CheeseDash/build/libs",
    includes: ["cheesedash-*.jar"], excludes: ["*-sources.jar", "*-dev.jar"]))
```

An empty glob is not an error in Gradle — it silently resolves to nothing, and the build
then fails on `import com.cheesedash.WebDashboard` with *package com.cheesedash does not
exist*. So CheeseDash must be built first:

```bash
cd CheeseDash   && ./gradlew build
cd ../CheeseMusic && ./gradlew build
```

`tools/build-all.sh` at the repository root walks every mod in dependency order and is the
safer option when building more than one.

CheeseGuard is **not** a compile-time dependency. `CheeseGuardHook` reaches it entirely
through reflection, so you can build CheeseMusic without CheeseGuard's jar anywhere on
disk. The same is true of Cobblemon and ReactiveMusic.

The output is:

```text
CheeseMusic/build/libs/cheesemusic-1.0.0+1.21.1.jar
```

The version comes from `mod_version=1.0.0+1.21.1` in `gradle.properties`. Ignore
`cheesemusic-1.0.0+1.21.1-sources.jar`, which is produced by `withSourcesJar()` and has no
place in a `mods/` folder.

## Install

```bash
cp CheeseMusic/build/libs/cheesemusic-1.0.0+1.21.1.jar /srv/mc/mods/
```

Then the same jar into each client's `mods/`, alongside Fabric API and ReactiveMusic.
Remove any older `cheesemusic-*.jar` first — two versions in one folder is a load error,
not an upgrade.

## What the first start creates

Two things, both under the server's run directory.

**The config file**, written by `CheeseMusicConfig.load` when it finds nothing:

```text
config/cheesemusic.json
```

```json
{
  "enabled": true,
  "defaultFadeInTicks": 60,
  "defaultFadeOutTicks": 60,
  "checkIntervalTicks": 10,
  "defaultVolume": 0.7,
  "assignments": {}
}
```

Every key is documented in [configuration](configuration.md).

**The resource pack skeleton**, created by `SoundScanner.scan` when the sounds directory is
absent:

```text
resourcepacks/CheeseMusic/pack.mcmeta
resourcepacks/CheeseMusic/assets/cheesemusic/sounds.json      (empty object)
resourcepacks/CheeseMusic/assets/cheesemusic/sounds/          (empty directory)
```

That pack is a staging area. The server does not serve it to anyone; you have to
distribute it. [Sound packs](sound-packs.md) is the page for that.

## Proving it loaded

On the **server**, at `SERVER_STARTED`, in this order:

```text
[CheeseMusic] Initializing...
[CheeseMusic] Initialized successfully!
[CheeseMusic] Loaded config with 0 assignments
[CheeseMusic] Created resource pack at: /srv/mc/resourcepacks/CheeseMusic
[CheeseMusic] Place .ogg files in: /srv/mc/resourcepacks/CheeseMusic/assets/cheesemusic/sounds
[CheeseMusic] CheeseGuard detected — zone music enabled
[CheeseMusic] Cobblemon detected — battle detection via getBattleByParticipatingPlayerId()
[CheeseMusic] Server started — 0 assignments loaded
```

The `Loaded config with N assignments` line is absent on a first start, because a freshly
written file takes the early return in `load`. The two resource-pack lines appear only
when the directory had to be created; that branch returns without counting tracks. On
every later start they are replaced by `[CheeseMusic] Found <n> tracks in resource pack`.

Three of those lines are diagnostics worth reading every time:

| Line | Meaning |
|---|---|
| `CheeseGuard not installed — zone music disabled` | reflection found no `com.cheeseguard.CheeseGuardMod`. Nothing will ever play |
| `CheeseGuard hook failed: <message>` | CheeseGuard is present but its shape changed. Nothing will play, and this is a code-level break |
| `Cobblemon BattleRegistry found but no suitable method` | battle suppression is off; zone music will play over battle music |

On the **client**:

```text
[CheeseMusic] Client initializing...
[CheeseMusic] ReactiveMusic detected — cooperative control enabled
[CheeseMusic] Client initialized
```

`ReactiveMusic not installed` in place of the middle line is the warning above, and it is
the single most useful line in the client log. It can also legitimately be absent at
start-up: `ReactiveMusicHook.isAvailable()` retries on every call, because ReactiveMusic's
`thread` field is null until its own player starts. The line then appears later, on the
first fade. The retry has a side effect when ReactiveMusic really is absent:
`ReactiveMusic not installed` is logged again on every `play` and every `stop` the client
receives, so a log full of it means the player crossed many region borders.

## Verifying in game

With CheeseDash running, the fastest check needs no audio at all:

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/zones
```

A non-empty array proves the jar is loaded, the dashboard module registered, and the
CheeseGuard hook works. An empty array with `CheeseGuard detected` in the log means you
have no regions yet.

For the full path from nothing to audible music, work through
[getting started](getting-started.md).

## Uninstalling

Delete the jar from the server's `mods/` and from every client's. Then decide what to do
with the leftovers, none of which the mod removes:

| Left behind | Remove? |
|---|---|
| `config/cheesemusic.json` | safe to delete. Keep it if you might reinstall — it is the only copy of your assignments |
| `resourcepacks/CheeseMusic/` | your `.ogg` files live here. Back it up before deleting |
| The pack on each client | a plain resource pack. Disable it in **Options → Resource Packs**, or delete the zip |
| `resource-pack=` in `server.properties` | if you used that delivery route, clear the line, or clients keep downloading a pack for sounds nothing requests |

Nothing is written into the world save, no entities are spawned and no blocks are placed,
so a world that once ran CheeseMusic loads cleanly without it. A client that keeps the
pack enabled after the mod is gone is harmless: the sound events exist and nothing ever
triggers them.

One oddity worth knowing on the way out: `src/main/resources/assets/cheesemusic/lang/`
holds `en_us.json` and `fr_fr.json` with two keys, `cheesemusic.zone.enter` and
`cheesemusic.zone.exit`. No code references either. The mod sends no chat message at any
point, so there is nothing to translate and nothing to configure there.

---

← [CheeseMusic](../README.md) · [Getting started](getting-started.md) →
