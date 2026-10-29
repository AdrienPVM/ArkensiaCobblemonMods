# CheeseMusic

> 🇫🇷 [Lire en français](README.fr.md)

> Ambient music tied to CheeseGuard regions, pushed to each player's client as they
> walk in and out.

The server watches where every player stands. When someone steps into a region that has
a music assignment, the server sends that player a packet naming the playlist, and the
player's own client starts playing it over the Music channel. Walk out, and the server
sends a stop. Nobody types a command, and two players standing on either side of a
border hear different things.

An assignment is a region id, a list of tracks, a play mode (`RANDOM`, `SEQUENTIAL` or
`SHUFFLE`), a volume and two fade durations. You write them from the CheeseDash
dashboard, which also uploads the `.ogg` files and keeps the resource pack's
`sounds.json` in step with whatever is on disk. Cobblemon battle music takes over when
a fight starts: the mod stops zone music for the duration and resumes afterwards.

No audio ships with this mod, and getting your own audio to the players is the part
that takes real work. The server builds a resource pack skeleton for you but cannot
hand it out by itself. [Sound packs](docs/sound-packs.md) walks that through end to end.

**Minecraft 1.21.1 · Fabric · Java 21 · 16 source files, ~1 710 lines**

---

## Requirements

| | |
|---|---|
| **Required** | Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Required in practice, on every client** | [ReactiveMusic](https://modrinth.com/mod/reactive-music) — see the warning below |
| **Required in practice, on the server** | [CheeseGuard](../CheeseGuard/) — supplies the regions |
| **Optional** | [CheeseDash](../CheeseDash/) — the only editor for assignments · [Cobblemon](https://modrinth.com/mod/cobblemon) — battle detection |

`fabric.mod.json` lists CheeseGuard and CheeseDash under `suggests`, not `depends`, and
the mod does load and run without either. It just does nothing useful: with no
CheeseGuard, region lookup always returns nothing and no player is ever sent a track.

## ⚠️ ReactiveMusic is not optional in the state the code is in

The client-side player is a state machine that waits for ReactiveMusic to confirm it has
faded itself out before starting a zone track. When ReactiveMusic is absent that
confirmation never arrives, because the flag it depends on is only ever set inside the
ReactiveMusic hook. The result, read straight off
[`ZoneMusicPlayer`](src/main/java/com/cheesemusic/client/ZoneMusicPlayer.java) and
[`ReactiveMusicHook`](src/main/java/com/cheesemusic/client/ReactiveMusicHook.java):

- entering an assigned region plays nothing at all, and
- the vanilla music tracker stays suppressed by the mixin for as long as the player
  stays there, so the region is simply silent. Walking back out, or reconnecting,
  restores vanilla music.

Install ReactiveMusic on every client, or expect silence. The full reasoning, with the
exact lines, is in [playback](docs/playback.md#what-happens-without-reactivemusic). This
has not been verified in play — it is read out of the code — but the path is
unambiguous.

## Install

```bash
cd CheeseDash  && ./gradlew build     # CheeseMusic compiles against its jar
cd ../CheeseMusic && ./gradlew build
```

Drop `build/libs/cheesemusic-1.0.0+1.21.1.jar` in the server's `mods/` **and** in every
player's `mods/`. Playback is client-side; a client without the jar ignores the packet
and hears nothing.

On first start the mod writes `config/cheesemusic.json` and creates an empty resource
pack under `resourcepacks/CheeseMusic/`:

```text
[CheeseMusic] Created resource pack at: .../resourcepacks/CheeseMusic
[CheeseMusic] Place .ogg files in: .../resourcepacks/CheeseMusic/assets/cheesemusic/sounds
[CheeseMusic] Server started — 0 assignments loaded
```

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, which side needs what, build order, what the first launch creates, the log lines that prove it loaded, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: from a blank server to standing in a region and hearing your own track |
| [Sound packs](docs/sound-packs.md) | Where the `.ogg` files go, how `sounds.json` is generated, filename sanitising, and the three ways to actually get the pack onto clients |
| [Zone music](docs/zone-music.md) | The assignment model, how a position becomes a track, `tag:` assignments, priority, battle suppression, the packet |
| [Playback](docs/playback.md) | The client state machine, what the fade durations really do, ReactiveMusic cooperation, the vanilla music mixin |
| [Configuration](docs/configuration.md) | `config/cheesemusic.json` key by key, which keys are read and which are not, and a complete annotated file |
| [Dashboard API](docs/dashboard.md) | The eleven REST routes under `/api/v1/music`, request and response shapes, required roles, and the status codes each one can produce |
| [Architecture](docs/architecture.md) | Packages, class responsibilities, threading, the mixin, extension points, dead code and known weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — starting with the several distinct reasons a region plays nothing |

## Commands at a glance

CheeseMusic registers **no commands at all**. There is no Brigadier tree in the source.
Everything is driven from the dashboard, or by editing `config/cheesemusic.json` and
restarting the server.

## Dashboard

Registers under the `music` prefix, so the routes sit at `/api/v1/music/*`: assignment
CRUD, track upload and delete, the CheeseGuard region list, global config, and who is
currently hearing what. In the sidebar it appears as **CheeseMusic** 🎵 with the
description *Musique de zones*. It publishes nothing on any WebSocket channel.

Reads are open to any authenticated session; the six write routes require `OPERATOR` or
above. Those guards were repaired on 29 October 2026 — an earlier build sent the `403`
and applied the change anyway, so a `READ_ONLY` account could edit assignments. If you
run an older jar, read [dashboard](docs/dashboard.md#roles) before trusting its roles.

## Honest status

Nothing in the repository suggests this mod has been exercised in play. The
ReactiveMusic dependency above, and the config key that can stop the server tick loop
([`checkIntervalTicks`](docs/configuration.md#checkintervalticks)), are both the kind of
thing one evening of real use would have caught.

Both third-party hooks are reflection against internals — ReactiveMusic's `thread`,
`gainPercentage` and `additionalSilence`, Cobblemon's `BattleRegistry` — so both will
stop working when those mods change shape. They fail quietly rather than crashing, which
is the right trade-off and also why a breakage is easy to miss.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may
not sell the mod itself.

The music you play through it is a separate matter: no audio is distributed with this
repository, and most game and film soundtracks are not licensed for redistribution in a
modpack. That part is between you and the rights holder.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
