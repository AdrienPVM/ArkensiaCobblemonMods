[← CheeseRaids](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

> ⚠️ **This mod has only ever been deployed to test instances.** It has never run on a
> production server, no raid has ever been played, and no player feedback exists. Install
> it on a server you are willing to break.

---

## Prerequisites

CheeseRaids is **server-side only**. There is no client entrypoint and no client-side
code; every visual effect is a particle, sound or title packet the server sends. Players
join with a vanilla client, or with whatever modpack your server already requires for
Cobblemon.

| | |
|---|---|
| **Required** | Minecraft 1.21.1 · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Required siblings** | [CheeseDash](../../CheeseDash/) · [CheeseGuard](../../CheeseGuard/) |
| **Required in practice** | [Cobblemon](https://modrinth.com/mod/cobblemon) — not declared, but the raid spawns nothing without it |
| **Needed for the herald** | [EasyNPC](https://modrinth.com/mod/easy-npc) |
| **Needed for victory portals** | CobbleSafari |
| **Optional** | [CheeseStore](../../CheeseStore/) — for the `reward.fromage` currency grant |

The two siblings are declared in `fabric.mod.json` as hard dependencies, so Fabric refuses
to start without either. CheeseGuard is used directly, through real imports, to create the
rift dimension; CheeseDash hosts the dashboard module.

The other four are reached by reflection and are **not declared anywhere**. Fabric cannot
warn you about a missing one. Cobblemon especially: without it the raid announces itself,
spawns no wave, no boss, times out and cleans up — a sequence of empty phases with
`Cobblemon API unavailable` in the log. Treat it as mandatory in spite of the manifest.
[Integrations](integrations.md) has the full matrix of what breaks how.

## Build order

CheeseRaids compiles against CheeseDash and CheeseGuard through a `fileTree` glob on
`../<Mod>/build/libs/`, so both must be **built** before it, in this order:

```bash
cd CheeseDash    && ./gradlew build
cd ../CheeseGuard && ./gradlew build
cd ../CheeseRaids && ./gradlew build
```

`tools/build-all.sh` at the repository root walks every mod in dependency order and is the
easier route if you are building more than this one.

The glob matches `cheesedash-*.jar` and `cheeseguard-*.jar`, excluding `-sources` and
`-dev`, so the exact sibling version does not matter. If either `build/libs/` is empty the
compile fails on a missing `com.cheeseguard.CheeseGuardMod` or `com.cheesedash.WebDashboard`
import — that is the symptom of a skipped build step, not of a broken dependency.

The jar lands at:

```text
CheeseRaids/build/libs/cheeseraids-1.0.0+1.21.1.jar
```

The version comes from `mod_version` in `gradle.properties`, which already carries the
`+1.21.1` suffix.

## Install

Drop three jars in the server's `mods/`, alongside Fabric API:

```text
mods/
├── fabric-api-0.103.0+1.21.1.jar
├── cheesedash-<version>.jar
├── cheeseguard-<version>.jar
└── cheeseraids-1.0.0+1.21.1.jar
```

Skip the `-sources.jar` and `-dev.jar` artifacts that also appear in `build/libs/`. Delete
any older `cheeseraids-*.jar` before copying a new one — two versions of the same mod in
`mods/` is a startup failure.

Cobblemon, EasyNPC, CobbleSafari and CheeseStore go in the same directory if you want
them, in whatever version your pack already uses.

## What the first launch creates

Two directories and, on an empty install, one file:

```text
config/cheeseraids/
├── raids/
│   └── hoopa.json        the default Hoopa Invasion definition
├── instances/            empty; one file per running raid
└── state.json            written at the first server stop, then on every scheduled run
```

`raids/` and `instances/` are created unconditionally. `hoopa.json` is written **only when
`raids/` contains no `.json` file at all** — which matters later: a single malformed file
blocks the seeding, so you can end up with a directory holding one broken file and no
default.

`state.json` maps a raid id to the ISO date of its last scheduled run, and is the
scheduler's anti-replay record. It is written at every server stop (as `{}` while nothing
has been scheduled) and each time a scheduled raid fires, so it appears after the first
shutdown.

The default `hoopa.json` is **not ready to use.** Four of its positions default to
`minecraft:overworld` at `0.5 / 65 / 0.5` — the world origin, under the ground. Nothing
works until you replace them with real coordinates from your map. That is the subject of
[calibration](calibration.md), and it is the one step you cannot skip.

## Verifying it loaded

Two log lines, in this order. The first comes from the mod's initialiser, the second once
the world is up:

```text
[CheeseRaids] initialized — modular raid framework ready.
[CheeseRaids] Loaded 1 raid definition(s), 0 active instance(s)
```

If the second line says `Loaded 0 raid definition(s)`, the definition directory held files
that all failed to parse — see [troubleshooting](troubleshooting.md#the-mod-loads-but-no-raid-is-listed).

In-game, as an operator:

```mcfunction
/cheeseraids status
```

```text
Raids:
• hoopa [idle]
```

And on the dashboard, a **CheeseRaids** entry in the sidebar, described as *Raids serveur
(invasions)*. Or over HTTP:

```bash
TOKEN=$(curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"token":"<master token>"}' | jq -r .sessionId)

curl -s http://localhost:44311/api/v1/raids/ -H "Authorization: Bearer $TOKEN"
```

A `503 {"error":"CheeseRaids not ready"}` means the module registered but the server has
not finished starting. Wait and retry.

## Uninstalling

Remove `cheeseraids-*.jar` from `mods/` and restart. CheeseDash drops the module, the
`/cheeseraids` command disappears, and nothing else in the pack is affected — the mod has
no mixins, registers no blocks, items or entities, and patches nothing.

What is left behind, and what to do about it:

| Left behind | Notes |
|---|---|
| `config/cheeseraids/` | the definitions, instance files and `state.json`. Safe to delete; keep `raids/` if you plan to reinstall, since your calibrated coordinates live there |
| the rift dimension | a CheeseGuard dimension, usually `cheeseguard:hoopa_rift`, with its region files under the world save. CheeseRaids never deletes it — it reuses it between raids. Remove it through CheeseGuard's own dimension management |
| the EasyNPC herald | an ordinary EasyNPC entity you placed yourself. It will be left in whatever visibility state it was in last. Delete it with EasyNPC's tooling |
| wave mobs and the boss | discarded at cleanup. A server killed mid-raid can leave persistent, uncatchable Pokémon at the wave arena, since persistence is exactly what stops them despawning. Remove them with `/kill @e[type=cobblemon:pokemon,distance=..20]` from the arena, or with the `cheeseraids_wave_<id>` / `cheeseraids_boss_<id>` command tag each one carries |
| the time of day | `wave.overworldStorm` sets the wave world to midnight and never restores it. Cleanup only clears the weather |

Uninstall while a raid is active and the players inside the rift stay there, in a dimension
with `enabled: false`. Stop the raid and let the victory path or a manual teleport get them
out first.

---

← [CheeseRaids](../README.md) · [Getting started](getting-started.md) →
