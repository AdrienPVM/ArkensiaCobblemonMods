[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

## Prerequisites

| | Version | Required on |
|---|---|---|
| Minecraft | 1.21.1 exactly — `~1.21.1` in `fabric.mod.json` | both |
| Fabric Loader | ≥ 0.15.11 | both |
| [Fabric API](https://modrinth.com/mod/fabric-api) | any — built against 0.100.4+1.21 | both |
| `fabric-resource-loader-v0` | any — part of Fabric API | both |
| Java | 21 or later | both |
| [CheeseDash](../../CheeseDash/) | matching build | server |

CheeseGuard declares `"environment": "*"` and registers a `client` entrypoint, so **the
jar goes on the server and should also go on the client**. The split:

- **Server side** — all of it. Zones, flags, events, dimensions, prison, commands, the
  dashboard API. The mod is fully functional with the jar on the server only.
- **Client side** — four things, and nothing else. The `nametag-hide` and `minimap-hide`
  flags (they are enforced by client-side render mixins and cannot work otherwise), the
  vote and prop-hunt HUDs, and the zone-owner menu bound to a keybind. A player without
  the mod sees nametags and their minimap inside a zone that hides them, and has no
  keybind menu. Nothing breaks; the features are simply absent for them.

⚠️ **CheeseDash is a hard dependency.** It is in `depends`, not `suggests`. CheeseGuard
refuses to load without it, even if you never open the dashboard.

Optional, each activated only when present:

| Mod | What it adds |
|---|---|
| [Waystones](https://modrinth.com/mod/waystones) | deleting a dimension also removes its waystones (reflection, `WaystonesHook`) |
| [Cobblemon](https://modrinth.com/mod/cobblemon) | the four `cobblemon-*` flags, pasture limits, the fishing minigame |
| [CobbleShift](../../CobbleShift/) | four event effect types that transform participants |
| [CheeseKart](../../CheeseKart/) | two event effect types that put participants in karts |
| [CobblemonDungeons](../../CobblemonDungeons/) | three event effect types for dungeon control |
| [CheeseStore](../../CheeseStore/) | a wallet backend for zone purchases |
| Numismatic Overhaul | a currency backend for zone purchases |
| Jade, JourneyMap | the `minimap-hide` flag hides their overlays (client mixins) |
| FTB Quests | detected at load; quest integration helpers |

The Jade, JourneyMap, Cobblemon and Cobblemon Spawn Alerts integrations are **mixins into
those mods' classes by name**, not reflection. They can break when those mods update. The
risk per group is in [architecture](architecture.md#the-44-mixins).

## Build

CheeseGuard compiles against CheeseDash's jar, resolved through a glob on
`../CheeseDash/build/libs/`. **CheeseDash has to be built first** or the compile fails on
missing symbols.

```bash
cd CheeseDash && ./gradlew build
cd ../CheeseGuard && ./gradlew build
```

`tools/build-all.sh` walks the whole repository in dependency order and is the easier
route if you are building more than one mod.

The output is `build/libs/cheeseguard-1.0.0+1.21.1.jar`. The version comes from
`mod_version` in `gradle.properties`. Two other jars appear next to it:
`cheeseguard-1.0.0+1.21.1-sources.jar` and the dev jar. **Copy neither** — a sources jar
in `mods/` loads as a mod with no code and produces the confusing failure where the mod
"is installed" but has no commands.

## Install

Drop the CheeseDash jar and `cheeseguard-1.0.0+1.21.1.jar` into the server's `mods/`.
Delete any older CheeseGuard jar first: two versions in `mods/` is a load error, not a
silent upgrade.

For the client features, put the same jar in the client's `mods/` alongside Fabric API.
CheeseDash is not needed on the client.

## What the first launch creates

CheeseGuard creates `config/cheeseguard/` immediately — its storage classes do that in
their constructors — but writes nothing into it until something is saved. A first launch
with no zones therefore leaves you with:

```text
config/
└── cheeseguard/          (empty)
```

Files appear as you use the mod: `regions.json` on the first zone,
`cheeseguard_dimensions.json` on the first dimension, `cheeseguard_events.json` on the
first event, and so on. The one file that writes itself with useful defaults is
`config/cheeseguard_fish_pokemon.json`, created the first time the fishing table is
loaded.

**`config/cheeseguard_webhook.json` is never created for you.** If you want Discord
notifications you write it yourself, with the shape in
[configuration](configuration.md#cheeseguard_webhookjson).

The full inventory of files and directories, and which ones are safe to hand-edit, is in
[configuration](configuration.md).

## Verify it loaded

One line in the server log, from the `CheeseGuard` logger, at the end of
`onInitialize`:

```text
[CheeseGuard] CheeseGuard loaded!
```

With FTB Quests also installed it reads:

```text
[CheeseGuard] CheeseGuard loaded! (FTB Quests detected)
```

That line means the mod initialised. It does **not** mean the server-dependent managers
are up — `EventManager`, `DimensionManager` and `ZoneStateManager` only initialise at
`SERVER_STARTED`, later in the boot. Other lines from the same logger carry a `[CG]`
prefix inside the message, which is how you spot runtime activity:

```text
[CheeseGuard] [CG] Event launch config loaded
[CheeseGuard] [CG] Fish Pokemon table loaded: 42 entries
```

In game, as an operator:

```mcfunction
/cg
```

```text
[CheeseGuard] Administration available on the CheeseDash web dashboard.
```

If you get *Unknown or incomplete command*, the mod is not on the server. Remember the
root command is `/cg` — there is no `/cheeseguard`.

Then check the dashboard side:

```bash
curl -H "Authorization: Bearer EXAMPLE-TOKEN-do-not-use" \
     http://localhost:44311/api/v1/cheeseguard/status
```

A `503 {"error":"CheeseGuard not ready"}` means the module is registered but the server
has not finished starting — wait and retry. A 404 means CheeseDash did not pick up the
entrypoint, which is covered in
[troubleshooting](troubleshooting.md#the-cheeseguard-section-is-missing-from-the-dashboard).

## Uninstall

Remove the jar from `mods/` on the server and on every client, and restart.

What stops working immediately: every flag stops being enforced, so a protected zone
becomes an ordinary piece of world. Prisoners are released in the sense that nothing
confines them any more, but their sentences stay on disk. Events in progress are
abandoned mid-state.

⚠️ **Do not uninstall with players mid-event.** An event that has cleared an inventory
restores it when the player leaves. Removing the mod removes the code that would do the
restoring, and the inventories stay in `config/cheeseguard_pending_inv/` as NBT nobody
reads. Stop every event first, or reinstall the mod and run `/cg event leave` for each
affected player before removing it for good.

What is left behind, none of it harmful:

- `config/cheeseguard/` — zones, groups, chunk sets, suggestions, tags, prison
- `config/cheeseguard_*.json` — dimensions, events, scores, launch policy, pastures,
  webhook, fishing table, prop icons, pending reverts
- `config/cheeseguard_templates/`, `config/cheeseguard_states/`,
  `config/cheeseguard_snapshots/`, `config/snapshots/`,
  `config/cheeseguard_racetracks/`, `config/cheeseguard_inv_archive/`,
  `config/cheeseguard_pending_inv/`, `config/cheeseguard_clearinv_snapshots/`,
  `config/cheeseguard_assets/`

**Dimensions created by the mod are not in `config/`.** They are real world directories
inside your save, and they stay there after uninstalling. Minecraft will not load them
without the mod that registered them, which is harmless but takes disk space. Delete
them by hand if you want the space back, and keep a backup of the save first.

Keep all of it if you might reinstall: the data format is stable and the mod picks up
where it left off.

---

← [CheeseGuard](../README.md) · [Getting started](getting-started.md) →
