[← CheeseVanity](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

CheeseVanity goes on **both sides**, and it is one of the few mods in this repository
where that is not a formality. The server owns the preference and persists it; the client
is the only thing that can act on it. One side without the other gives you half a mod:

| Where the jar is | What happens |
|---|---|
| server and client | the mod works as documented |
| server only | preferences can be stored but nothing is ever hidden, on any screen, for anyone |
| client only | pressing `=` sends a packet the server does not understand and drops; nothing is stored, nothing hides |

It declares `"environment": "*"` in `fabric.mod.json`, so the same jar covers both.

---

## Prerequisites

| | |
|---|---|
| **Required** | Minecraft 1.21.1 · Fabric Loader ≥ 0.16.0 · [Fabric API](https://modrinth.com/mod/fabric-api) · Java 21 |
| **Optional** | [Trinkets](https://modrinth.com/mod/trinkets) 3.10.0 or compatible |

Those three required entries are the literal contents of `depends` in
`fabric.mod.json`. `suggests` lists `trinkets` with no version bound; the build compiles
against `dev.emi:trinkets:3.10.0`, declared `modCompileOnly`, so Trinkets is **not**
bundled in the jar and must be installed separately if you want trinket slots.

There is no CheeseDash dependency and no sibling-mod dependency of any kind. This mod
builds and runs on its own.

⚠️ Without Trinkets, and with the stock config, the mod does nothing at all:
`enableTrinkets` has nothing to apply to and `enableVanillaArmor` is `false`. See
[configuration.md](configuration.md#enablevanillaarmor) before concluding the install
failed.

---

## Build

No sibling jar has to exist first, so there is no build order to respect:

```bash
cd CheeseVanity
./gradlew build
```

The output lands in `build/libs/`:

```text
cheesevanity-1.0.0+1.21.1.jar
cheesevanity-1.0.0+1.21.1-sources.jar
```

The `+1.21.1` is part of `mod_version` in `gradle.properties`, not something Loom
appends. Deploy the first file; the sources jar is for reading, not for running.

`tools/build-all.sh` at the repository root builds every mod in dependency order and
covers this one too.

---

## Deploy

Copy `cheesevanity-1.0.0+1.21.1.jar` into the server's `mods/`, and the same jar into
each client's `mods/`. Delete any older `cheesevanity-*.jar` first — two versions of the
same mod id in one `mods/` folder stops Fabric Loader from starting.

Nothing else needs doing. There is no datapack, no resource pack, no registry to sync.

---

## What the first launch creates

On the server, `VanityConfig.load()` finds no file and writes one:

```text
config/cheesevanity.json
```

```json
{
  "enableVanillaArmor": false,
  "enableTrinkets": true,
  "disabledSlots": []
}
```

`config/cheesevanity_data.json` is **not** created at startup. It only appears the first
time a player toggles something, because `VanityData.save()` is called from the toggle
handler and nowhere else. An empty `config/` after a first launch with no players is
normal.

A client writes `config/cheesevanity.json` too, under `.minecraft/config/`. Nothing reads
it there; the file is a side effect of the main entrypoint running on an integrated
client. Editing it has no effect on a multiplayer server.

---

## Verify it loaded

Server log, during mod initialisation:

```text
[CheeseVanity] Loaded — vanilla armor: false, trinkets: true
```

The two values are the config as actually parsed, which makes this line the fastest way
to confirm a config edit took effect.

Client log, during client initialisation:

```text
[CheeseVanity] Client initialized
```

Then verify in game, in this order, because each step rules out one failure:

1. Open your inventory and press `=` over a slot. The **client** log must print
   `[CheeseVanity] Key '=' on slot: ...` and `[CheeseVanity] Resolved slotKey: ...`.
   Nothing printed means the mixin did not apply or the key never reached it.
2. A `Resolved slotKey` other than `null` means the slot is understood. `null` means this
   slot is not one the mod handles.
3. An action-bar message means the server received, accepted and stored the toggle.
   Silence here with a non-null key means the config refused it — check the server log for
   `not toggleable`.
4. A red cross on the slot and the piece gone in F5 means the render side works.

[troubleshooting.md](troubleshooting.md) is organised around which of those four steps
stops.

---

## Uninstall

Stop the server, remove the jar from the server and from every client, start again.
Nothing has to be migrated and no world data is touched: the mod registers no items, no
blocks, no entities, no components and no scoreboard objectives, and it writes nothing
into the save.

Left behind:

- `config/cheesevanity.json` — the config, harmless, delete it if you like
- `config/cheesevanity_data.json` — every player's hidden slots, keyed by UUID

Keep the second file if you might reinstall. Players' preferences come back exactly as
they were, because the keys are slot names and UUIDs, not references to anything that
could have gone stale.

Removing the mod restores every piece of equipment to view immediately. Nothing was ever
unequipped, so there is no inventory state to repair.

---

← [CheeseVanity](../README.md) · [Getting started](getting-started.md) →
