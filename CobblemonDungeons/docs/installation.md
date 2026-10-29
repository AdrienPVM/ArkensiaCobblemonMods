[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

CobblemonDungeons is a **server and client** mod. `fabric.mod.json` declares
`"environment": "*"` and the client entrypoint does real work: the objective HUD, the
dungeon screens, the poison water and gas rendering, the gas mask model, and the
`DungeonJoinPayload` the client holds across a server switch. A player without the mod
cannot see the HUD, cannot open the selection GUI, and cannot be transferred between
servers.

---

## Prerequisites

| | |
|---|---|
| **Minecraft** | 1.21.1 |
| **Fabric Loader** | ≥ 0.16.10 |
| **Fabric API** | any version for 1.21.1 |
| **Java** | 21 |
| **Cobblemon** | ≥ 1.6.1 — built against [1.7.1 Fabric](https://modrinth.com/mod/cobblemon) |
| **RCTApi** | ≥ 0.13.7-beta — [Radical Cobblemon Trainers API](https://www.curseforge.com/projects/1152792) |
| **CheeseDash** | [sibling mod](../../CheeseDash/), required |

All four hard dependencies are declared in `depends`, so Fabric refuses to start without
any of them. `suggests` lists `flan` and `radicaltrainers`; neither is needed.

Radical Trainers itself is worth having if you want to author trainers with its tools, but
this mod only needs RCT**Api**: it registers its own trainers under its own namespace. See
[trainers and RCTApi](trainers.md).

## Build order

CobblemonDungeons compiles against CheeseDash's jar, resolved by a glob over
`../CheeseDash/build/libs/`. CheeseDash must be built first or the dungeon build fails at
the dependency resolution step.

```bash
cd CheeseDash && ./gradlew build
cd ../CobblemonDungeons && ./gradlew build
```

`tools/build-all.sh` at the repository root walks every mod in the right order if you want
all of them.

Two things set this mod apart from its siblings in the repository:

- its build script is **Kotlin DSL** — `build.gradle.kts`, not `build.gradle`. Gradle
  commands are identical; only the file you edit differs.
- its version is **not** `1.0.0`. `build.gradle.kts` declares
  `version = "1.1.147+1.21.1"` and `base.archivesName.set("cobblemon-dungeons")`, so the
  artefact is `build/libs/cobblemon-dungeons-1.1.147+1.21.1.jar`. There is no
  `mod_version` in `gradle.properties` for this mod — the version lives in the build
  script.

Cobblemon comes from Modrinth Maven, pinned by version id rather than number because
`1.7.1` names both the Fabric and the NeoForge build; RCTApi comes from CurseMaven. A
clean clone builds with no manual downloads, and nothing third-party is redistributed by
this repository.

## Install on the server

Drop two jars in the server's `mods/`:

```text
mods/
├── cobblemon-dungeons-1.1.147+1.21.1.jar
└── cheesedash-1.0.0+1.21.1.jar
```

plus Cobblemon, RCTApi and Fabric API, which you were going to have anyway.

## Install on the client

The same `cobblemon-dungeons-1.1.147+1.21.1.jar` goes in the client's `mods/`, alongside
Cobblemon and Fabric API. CheeseDash is server-side and is not needed on a client.

Keep the client jar at the same version as the server. The network payloads carry no
version negotiation, so a mismatched codec is a disconnect rather than a graceful warning.

## ⚠️ The Velocity plugin is not a mod

`velocity-party-forwarder/` is a **separate Velocity proxy plugin**, and it does not go in
`mods/`. It goes in your Velocity proxy's `plugins/` directory. Nothing in the Minecraft
server will load it, and nothing in the proxy will load the mod jar.

You need it only for `CROSS_SERVER` dungeons. A single-server install can ignore this
section entirely.

What it does: Velocity does not forward BungeeCord `Forward` plugin messages between
backend servers, and this mod relies on them to carry party state from the main server to
the dungeon server. The plugin registers the `bungeecord:main` and
`cobblemon_dungeons:proxy_forward` channels, parses the forwarded payload, extracts the
target player's UUID from its first 16 bytes, and delivers the message to whichever
backend that player is on — queueing it if the player is still in transit and flushing the
queue on `ServerPostConnectEvent`. Without it, a cross-server party arrives empty.

It is **not part of the main build**: the root `settings.gradle.kts` does not include it,
so `./gradlew build` neither builds it nor breaks on it. Building it is a separate,
manual step and needs a Velocity API jar you supply yourself:

```bash
cd CobblemonDungeons/velocity-party-forwarder
mkdir -p libs
# put velocity-proxy.jar (the Velocity API) in libs/
./gradlew build          # produces build/libs/velocity-party-forwarder-1.0.5.jar
```

Then copy that jar into the proxy's `plugins/` and restart the proxy. On startup it logs:

```text
[CobblemonPartyForwarder] Registered channel bungeecord:main
[CobblemonPartyForwarder] Registered channel cobblemon_dungeons:proxy_forward
[CobblemonPartyForwarder] Plugin jar path=/path/to/plugins/velocity-party-forwarder-1.0.5.jar
```

The plugin declares version `1.0.5`, independently of the mod's version. It targets Java 17
bytecode, so it runs on any Velocity build on Java 17 or later. Its logging is verbose by
design — every forwarded message is logged at INFO — which is useful while you are wiring
a network up and noisy once it works.

## Cross-server setup, in order

For a `CROSS_SERVER` dungeon you need all of this:

1. The mod on the main server, with no `serverRole` set.
2. The mod on the dungeon server, started with
   `-Dcobblemon.dungeons.serverRole=dungeon`.
3. The Velocity plugin on the proxy.
4. The dungeon's `networking.targetServer` matching the proxy's name for the dungeon
   server, and `networking.mainServer` matching its name for the main one.
5. Optionally `database.useSql: true` on both Minecraft servers, pointing at the same
   schema, if your players have large PCs.

Get it wrong and the usual symptom is a leader stuck on *Checking dungeon server
status...*. [Troubleshooting](troubleshooting.md) lists the variants.

## First launch

Starting the server creates `config/cobblemon_dungeons/` and logs the following, in this
order:

```text
Initializing Cobblemon Dungeons...
Creating default main config at …/config/cobblemon_dungeons/config.json
Creating default dungeon tickets at …/config/cobblemon_dungeons/dungeon_tickets.json
Registered dungeon from file: example_dungeon.json
Cobblemon Dungeons server role: main
Registered custom trainer from file: example_trainer.json
```

What you get:

- `config.json` with the database block, SQL off, and the placeholder password —
  see [configuration](configuration.md)
- `dungeon_tickets.json` with one Tier 1 template, `§6Copper Dungeon Ticket`
- `dungeons/example_dungeon.json`, a full worked example: two Alpha species, five tier
  levels, two Guardian pool entries, two loot pools, three completion rewards, a
  Charizard boss with its own reward, and a second location slot
- `trainers/example_trainer.json`, a one-Pokémon Guardian wearing Steve's skin

The example dungeon is registered and will show up in the GUI and the dashboard. Its
coordinates point at `100, 64, 100` in the overworld and it is in `CROSS_SERVER` mode, so
it is a template to copy rather than something to run as-is.

## Verify it loaded

Three checks, in increasing strength.

**The log.** `Initializing Cobblemon Dungeons...` near the start, then one
`Registered dungeon from file:` line per file in `dungeons/`.

**In game.** `/dungeons` with at least one Pokémon in your party opens the selection
screen. If the command does not exist, the mod is not on the server; if the screen does not
open but you get a message, read the message — it is one of the two documented refusals in
[commands](commands.md).

**The dashboard.** `GET /api/v1/dungeons/configs` returns an array containing
`example_dungeon`, and the module appears in CheeseDash's sidebar with the castle icon
under the name `CobblemonDungeons`. A `503` with `CobblemonDungeons not ready` means the
module registered but the server had not started yet — retry.

## Uninstall

Remove the jar from the server's `mods/` and from every client's, and the Velocity plugin
from `plugins/` if you installed it.

What stays behind:

- `config/cobblemon_dungeons/` in full. Delete it to drop the dungeon definitions, the
  ticket templates, the trainers, the run history, the leaderboard and the cooldowns.
- Any block the mod registered that you placed in the world — spawners, portals, chests,
  stakes, poison water. Without the mod those become unknown blocks, which vanilla will
  render as air and drop on the next chunk save; back the world up first if you care.
- Tickets, gems, gas masks and gas detectors in player inventories, which disappear the
  same way.
- The `cobblemon_dungeons_player_sync` table, if you used SQL. Dropping it is safe once
  no server runs the mod.

There is nothing in the world save that is specific to the mod beyond blocks and items —
instances are memory-only and die with the server.

---

← [CobblemonDungeons](../README.md) · [Getting started](getting-started.md) →
