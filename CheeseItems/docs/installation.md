[← CheeseItems](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

## Prerequisites

| | Version | Where |
|---|---|---|
| Minecraft | 1.21.1 exactly (`~1.21.1`) | — |
| Fabric Loader | ≥ 0.16.0 | [fabricmc.net](https://fabricmc.net/use/server/) |
| Fabric API | any 1.21.1 build; developed against `0.103.0+1.21.1` | [Modrinth](https://modrinth.com/mod/fabric-api) |
| Java | 21 or newer | — |
| CheeseDash | built from this repository | [CheeseDash](../../CheeseDash/) |

`cheesedash` is a hard `depends` in `fabric.mod.json`. Without it the loader refuses to
start the server, and that is the correct behaviour: the mod calls CheeseDash classes
directly and has no degraded mode.

Two optional neighbours:

| Mod | What it adds | What happens without it |
|---|---|---|
| [CobbleShift](../../CobbleShift/) | the actual transformation behind every morpho | morphos can be collected and selected, and transforming logs a warning and does nothing |
| [CheeseStore](../../CheeseStore/) | a currency balance line in the Codex GUI | the line is hidden |

Neither is declared in the manifest. CobbleShift is reached by running a `cshift` command,
CheeseStore by reflection, so both are optional in fact rather than by declaration.

## Which side needs the jar

**Both, with different consequences.** The jar is `"environment": "*"` and has a `client`
entrypoint.

- **Server: required.** Everything lives here — definitions, commands, the dashboard API,
  the resource pack, the morpho logic.
- **Client: optional but recommended.** The client half adds the Morpho Codex screen and
  the `J` / `H` keybinds. A player without it still receives custom items that look and
  behave correctly, still gets the resource pack, and still has morphos recorded in their
  codex. They cannot open the Codex or toggle a transformation.

If you distribute mods to players through a launcher or a sync mod, ship CheeseItems to
them.

## Build order

CheeseItems compiles against CheeseDash's jar, resolved by a glob over
`../CheeseDash/build/libs/cheesedash-*.jar`. That directory has to contain a jar before
CheeseItems will compile, so CheeseDash is built first:

```bash
cd CheeseDash && ./gradlew build
cd ../CheeseItems && ./gradlew build
```

`tools/build-all.sh` at the repository root walks every mod in dependency order if you
would rather not think about it.

The output is `CheeseItems/build/libs/cheeseitems-1.0.0+1.21.1.jar`. The version comes from
`mod_version` in `gradle.properties`. Ignore `cheeseitems-1.0.0+1.21.1-sources.jar` — it is
produced by `withSourcesJar()` and must not go into `mods/`.

## Install

```bash
cp CheeseDash/build/libs/cheesedash-*.jar      /path/to/server/mods/
cp CheeseItems/build/libs/cheeseitems-1.0.0+1.21.1.jar /path/to/server/mods/
```

Delete the previous jar before copying a new one. Two versions of the same mod in `mods/`
is a startup crash, not a warning.

## First launch

The mod creates three files under `config/`, all of them empty of content:

```text
config/cheeseitems.json           {"items":[],"customModelData":{},"resourcePackHost":""}
config/cheeseitems_morphos.json   {"entries":[],"globalEndurance":{…}}
config/cheeseitems_morpho.json    created on the first grant, not at startup
```

`cheeseitems_morpho.json` is the exception: `MorphoCodex.load()` returns early when the
file is missing and only writes once a player actually owns something.

No asset folder is created until the first upload.

### Log lines that prove it loaded

```text
[CheeseItems] Initializing...
[CheeseItems] Default config created
[CheeseItems] MorphoCatalog loaded (0 entries)
[CheeseItems] Initialized successfully!
[CheeseItems] Server started — 0 items loaded
```

On a client, one more line appears:

```text
[CheeseItems] Client initialized — keybinds H (toggle) / J (codex)
```

On the second and later launches, `Default config created` is replaced by:

```text
[CheeseItems] Loaded 3 item definitions, 2 custom model data mappings
```

The number of items in that line and in `Server started — N items loaded` should match. If
the second says `0` and your file is not empty, the parse failed — look for
`[CheeseItems] Failed to load config:` just above it, and read
[configuration](configuration.md#a-complete-annotated-example) on why that is dangerous.

### Verify in game

```mcfunction
/cheeseitem list
```

```text
No items defined.
```

That response is success: the command tree is registered and you have permission. A
*Unknown or incomplete command* means either the mod did not load, or you are below
permission level 2.

Then check the dashboard. Log in at `http://<host>:44311`, and the sidebar should show two
new entries, **CheeseItems** 🎁 and **Morphos** 🧬. Or ask the API directly:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/items
```

```json
[]
```

## Configure the resource pack host

One step that is easy to skip and silently breaks custom icons for every remote player.
Open `config/cheeseitems.json` and set:

```json
"resourcePackHost": "play.example.net:44311"
```

A host and port a **player's machine** can reach, no scheme, no path. Left empty, the mod
falls back to `localhost:44311` and logs that it did. Full explanation in
[configuration](configuration.md#resourcepackhost).

Then `/cheeseitem reload` or a restart.

## Uninstall

1. Stop the server.
2. Remove `cheeseitems-*.jar` from `mods/`.
3. Start the server.

What breaks and what survives:

| Thing | After removal |
|---|---|
| Custom item stacks in inventories and chests | still there, still the base item, still named and lored — the components stay on the stack. Their `cheeseitems` tag becomes inert |
| `maxUses` items | keep their `usesLeft` tag. It does nothing |
| Items built on `minecraft:totem_of_undying` | **start resurrecting their holder again**, because the mixin is gone |
| Transformed players | stay transformed. Revert them with `cshift revert <name>` before removing the mod |
| The resource pack | clients keep the last pack they downloaded until the server tells them otherwise, which it never will. They can remove it from the server resource-pack list in the options |
| `/cheeseitem`, `/morpho` | gone |
| Dashboard modules | gone from the sidebar on the next CheeseDash start |

Nothing is deleted from disk. Left behind:

```text
config/cheeseitems.json
config/cheeseitems_morphos.json
config/cheeseitems_morpho.json
config/cheeseitems_assets/
```

Delete those by hand if you are done with the mod. Keep them if you plan to reinstall:
dropping the jar back in restores every definition and every player's codex, because the
only binding between a stack and its definition is the id string.

---

← [CheeseItems](../README.md) · [Getting started](getting-started.md) →
