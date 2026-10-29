[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

The mod registers two separate top-level commands, from two classes:

| Command | Class | Who can run it |
|---|---|---|
| `/eac` | `util/AdminCommand.java` | players who pass `PermissionUtil.isAdmin` |
| `/pedestal` | `util/PedestalCommand.java` | **everyone**, including a fresh non-op player |

Administration of the collectibles themselves happens in the CheeseDash dashboard. The
command surface is deliberately thin: one way to get an admin item back, one way to read
the registry size, and four pedestal display knobs.

There are no aliases. Nothing is registered in `/eggandcheese`, `/egg`, or any other
spelling.

## `/eac`

```mcfunction
/eac
/eac give <id>
/eac reload
```

### Permission

The whole `/eac` tree carries one requirement:

```java
.requires(src -> src.isExecutedByPlayer() && PermissionUtil.isAdmin(src.getPlayer()))
```

Two consequences.

**It must be a player.** `isExecutedByPlayer()` is false for the server console, for a
command block, and for `/execute as`. Running `eac give cave_nord` from the console gives
Minecraft's standard unknown-command error, because to the console the command does not
exist at all.

**Admin is `eggandcheese.admin`, falling back to op level 2.** `PermissionUtil.isAdmin`
tries `me.lucko.fabric.api.permissions.v0.Permissions.check(player,
"eggandcheese.admin", 2)` by reflection; when fabric-permissions-api is not on the
classpath, it falls back to `hasPermissionLevel(2)`.

A player without the permission does not get a refusal message. Brigadier hides a command
whose `requires` fails, so `/eac` is simply not in their completion list and typing it
produces:

```text
Unknown or incomplete command, see below for error
```

That is Minecraft's own text, not this mod's.

### `/eac`

With no subcommand, it prints a pointer and nothing else:

```text
Use the CheeseDash dashboard to manage eggs.
```

In French:

```text
Utilisez le dashboard CheeseDash pour gérer les oeufs.
```

Shown in yellow, as command feedback, so it also appears in the server log when
`sendCommandFeedback` is on. No state changes.

### `/eac give <id>`

Puts the admin placement item for an existing registry entry into your inventory.

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `id` | `StringArgumentType.string()` | yes | a single word, or a quoted string for ids containing spaces | — |

⚠️ **No completions.** The argument has no `SuggestionProvider`, so pressing Tab offers
nothing. You have to know the id, or read it from the dashboard's Eggs list. This is the
single most common source of `Unknown ID` errors.

The item it builds is an `eggandcheese:admin_egg` whose `CUSTOM_DATA` carries three
values copied from the registry entry: `dna`, `registryId` and `scale`. That is what
makes a later placement a *replacement* rather than a new egg — see
[placement and discovery](placement-and-discovery.md#branch-1--the-stack-names-an-existing-registry-id).

The stack is inserted into your inventory; if the inventory is full, it is dropped at
your feet rather than lost.

Worked example:

```mcfunction
/eac give cave_nord
```

```text
Given admin egg for: cave_nord
```

French:

```text
Oeuf admin donné pour : cave_nord
```

Failure, in red:

```text
Unknown ID: cave_nrod
```

```text
ID inconnu : cave_nrod
```

The id is looked up with `EggRegistryData.getEntry`, which is an exact match — ids are
case-sensitive and not fuzzy-matched. `cave_nord` and `Cave_Nord` are different ids.

The command returns `1` in both the success and the unknown-id case, so a command block
chain cannot distinguish them by result. It returns `0` only when
`ctx.getSource().getPlayer()` is somehow null, which `requires` should already have
prevented.

### `/eac reload`

```mcfunction
/eac reload
```

```text
Egg registry loaded: 56 entries.
```

```text
Registre d'oeufs chargé : 56 entrées.
```

⚠️ **This command does not reload anything.** Despite the name, the handler does exactly
one thing: it reads `EggRegistryData.get(server).getAllEntries().size()` and prints it.
There is nothing to reload — the registry is a `PersistentState` held in memory and
written by Minecraft's save cycle, and there is no config file to re-read.

Treat it as "how many eggs does the server think it has". It is useful for exactly one
thing: comparing that number against what the dashboard shows, to confirm you are looking
at the server you think you are.

## `/pedestal`

```mcfunction
/pedestal scale <value>
/pedestal height <value>
/pedestal pillar <value>
/pedestal spin
```

Adjusts the pedestal you are looking at. Pedestals are display furniture — see
[decorative eggs and pedestals](display.md).

### Permission

⚠️ **None.** `PedestalCommand` registers no `.requires(...)` at all, so every subcommand
is available at permission level 0. Any player on the server can retune any pedestal they
can stand in front of, including pedestals in a protected build. Each subcommand does
check that the caller is a player, and sends

```text
This command must be executed by a player.
```

```text
Cette commande doit être exécutée par un joueur.
```

through `sendError` when it is not — so the console cannot drive it, but every player
can. If that matters on your server, gate `/pedestal` in your permissions mod, or protect
the region with [CheeseGuard](../../CheeseGuard/).

### Targeting

Every subcommand resolves its target the same way, in `lookingAtPedestal`:

```java
player.raycast(5.0, 0, false)
```

A 5-block raycast from the player's eyes, ignoring fluids. The hit block must have a
`PedestalBlockEntity`. Both a miss and a hit on something else produce the same red
message:

```text
You must be looking at a pedestal.
```

```text
Vous devez regarder un piédestal.
```

and return `0`. So "nothing happened" nearly always means you were a little too far away,
or aiming at the pillar extension rather than the pedestal block itself.

### Arguments

| Subcommand | Argument | Type | Range | Effect |
|---|---|---|---|---|
| `scale` | `value` | float | 0.5 – 3.0 | Size of the displayed item |
| `height` | `value` | float | −0.5 – 0.5 | Vertical offset of the displayed item, in blocks |
| `pillar` | `value` | float | 0.0 – 5.0 | Height of the pillar under the platform, in blocks |
| `spin` | — | — | — | Toggles rotation of the displayed item |

The ranges are enforced by Brigadier itself (`FloatArgumentType.floatArg(min, max)`), so
an out-of-range value is rejected before the handler runs, with Minecraft's standard
message:

```text
Float must not be more than 3.0, found 5.0
```

Brigadier does suggest the valid range in the usage hint while you type, which is the only
completion help any of these commands offer.

### Examples

```mcfunction
/pedestal scale 2.0
```

```text
Display scale set to 2.0.
```

```text
Taille d'affichage définie à 2,0.
```

```mcfunction
/pedestal pillar 3.0
```

```text
Pillar height set to 3.0.
```

```text
Hauteur du pilier définie à 3,0.
```

Changing the pillar height has a side effect worth knowing: `setPillarHeight` rebuilds
the pedestal's collision shape cache and calls `updateExtensions()`, which places or
removes `eggandcheese:pedestal_extension` blocks above the pedestal. Those extension
blocks are what you actually collide with on a tall pedestal, and they are set to
`strength(-1.0f, 3600000.0f)` — unbreakable and blast-proof, like bedrock. If a pedestal
is removed by means other than breaking it normally, its extensions can be left behind;
`removeAllExtensions()` exists for the normal path.

```mcfunction
/pedestal spin
```

```text
Spinning enabled.
```

```text
Rotation activée.
```

Run it again:

```text
Spinning disabled.
```

```text
Rotation désactivée.
```

`spin` reads the current state and inverts it, so there is no way to set it explicitly to
on or off — it is a toggle only. A pedestal starts with `spinning = true`.

All four write through a setter that calls `markDirty()` and syncs the block entity to
nearby clients, so the change is visible immediately and survives a restart. Nothing is
logged.

## What has no command

For completeness, since the shape of the command set surprises people:

- Creating, editing, renaming or deleting a collectible — dashboard only.
- Editing DNA — dashboard only.
- Listing collectibles — dashboard only. `/eac reload` gives a count, not a list.
- Teleporting to an egg — dashboard only.
- Giving the decorative item — dashboard only (`POST /collectibles/:id/give-deco`), or
  by right-clicking your own egg as an admin.
- Running the integrity check — dashboard only.

There used to be an in-game admin GUI opened by `/eac`. It was removed when
administration moved to CheeseDash, and the `/eac` root now prints the pointer message
quoted above. `network/PacketExtras.java` is the leftover stub of that flow: its
`register()` is a documented no-op kept only so `AdminCommand`'s old reference still
compiles.

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
