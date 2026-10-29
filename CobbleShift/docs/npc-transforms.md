[← CobbleShift](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](npc-transforms.fr.md)

# NPC transforms

An NPC transform makes an EasyNPC look like a Pokémon. The NPC keeps everything that makes
it an NPC: its dialogue, its actions, its position, its hitbox, its UUID. Clients that have
CobbleShift draw a Pokémon model where the NPC stands, and that is all.

Player transforms and NPC transforms share a name, a packet and a renderer, and differ on
almost everything else. A player transform is session state with attributes, attacks and a
cooldown, and it ends on a restart. An NPC transform is purely visual, carries at most one
attribute, and is **saved to disk**, so a Snorlax guarding a bridge is still a Snorlax after
the server restarts. The whole subsystem lives in `NpcTransformManager` on the server and
`NpcTransformModelMixin` on the client.

---

## The model

One map, `Map<UUID, NpcTransformData>`, keyed by the NPC's entity UUID. Each entry is an
immutable record:

| Field | Type | Meaning |
|---|---|---|
| `species` | string | the species name, namespace stripped (`cobblemon:snorlax` becomes `snorlax`) |
| `shiny` | boolean | render the shiny texture |
| `form` | string or `null` | the aspect string, same grammar as `/cshift transform` |
| `type1` | string | primary type, read from Cobblemon at transform time |
| `type2` | string or `null` | secondary type |
| `scale` | float | visual size multiplier, `1.0` by default |
| `speed` | float | movement speed multiplier, `1.0` by default |

`type1` and `type2` are stored but unused for NPCs: they travel in the sync packet because
the packet is shared with player transforms, where the client uses them for water-type
animation. Changing `scale` or `speed` builds a new record with `withScale()` or
`withSpeed()` and puts it back in the map.

Because the key is the entity UUID, the transform follows the NPC wherever it goes:
teleport it, move it to another dimension, and it is still transformed. `findEntity()`
searches every loaded world. A copy of the NPC is a new entity with a new UUID, and is
not transformed.

## What changes on the NPC, and what does not

| Aspect | NPC transform | Player transform, for comparison |
|---|---|---|
| model on clients with the mod | the Pokémon | the Pokémon |
| model on vanilla clients | **the NPC, unchanged** | nothing, the player is invisible |
| Invisibility effect | none | permanent |
| nametag | hidden by `PlayerNametagMixin` | hidden |
| hitbox | the NPC's own, untouched | scaled to the species |
| `scale` | client-side model only | model and `GENERIC_SCALE` attribute |
| movement speed | only if you set it, see [speed](#speed) | derived from base Speed |
| max health | untouched | derived from base HP |
| cry and particles | never | within 32 blocks |
| attacks, passive abilities | none | yes |

The missing Invisibility is deliberate, and the reason is in the comments of both
`NpcTransformManager` and the mixin. Minecraft's `WorldRenderer` skips invisible entities
before it ever calls `EntityRenderDispatcher.render()`. The NPC mixin hooks that method, so
an invisible NPC would never reach it and no Pokémon would be drawn. Players are handled
by a different mixin on `PlayerEntityRenderer`, which is why they can be invisible and NPCs
cannot.

A side effect worth having: a player without CobbleShift sees the plain NPC instead of an
empty spot. An NPC transform degrades better than a player transform.

The hitbox is the other side of the same choice. `scalenpc 3.0` makes a huge Snorlax whose
clickable box is still the size of a villager. Players aim at the NPC, not at the model,
when they want to talk to it.

## Choosing the target

The four aimed subcommands (`transformnpc`, `revertnpc`, `scalenpc`, `speednpc`) take no
entity argument. `getLookedAtEntity()` picks the target:

1. Casts a 5-block segment from the caller's eyes along their look direction.
2. Tests every entity in the caller's bounding box expanded by 5 blocks, against its own
   bounding box expanded by 0.3.
3. Keeps the **closest** hit.

Three consequences follow from that implementation:

- **Blocks do not stop the ray.** Only entity boxes are tested, so you can target an NPC
  through a wall or a window.
- **Any entity in front wins.** A Pokémon, an armor stand or another player between you and
  the NPC is the target, and the command answers `This entity is not an EasyNPC`.
- **The caller must be a player.** `getPlayerOrThrow()` rejects the console and command
  blocks with the vanilla error.

`isEasyNpc()` then checks the entity type's registry namespace, and nothing else: anything
registered under `easy_npc` passes. EasyNPC is not declared in `fabric.mod.json` and no
EasyNPC class is referenced. If EasyNPC renames its namespace, every NPC subcommand will
start refusing with that same message.

`scalenpc` and `speednpc` skip the EasyNPC check and only ask whether the target has a
stored transform. They cannot reach a non-NPC entity in practice, since `transformnpc` is
the only way to create one.

## Lifecycle

### Transform

`/cshift transformnpc <pokemon [shiny] [form…]>`, aimed. The species must resolve through
`SpeciesMapper`, which means Cobblemon must be present.

If the NPC is already transformed, the old transform is **reverted first**, then a new
record is created with `scale` and `speed` back at `1.0`. Changing an NPC's species
therefore resets its size and removes its speed modifier. Re-apply them afterwards.

The manager then broadcasts the sync to every connected client and saves the file.

```text
[CobbleShift] NPC 1a2b3c4d transformed → snorlax (shiny=false, form=null)
```

The UUID in the log is truncated to its first eight characters.

### Revert

`/cshift revertnpc`, aimed. Removes the entry, removes the speed modifier from the entity,
broadcasts an empty species so clients drop the model, and saves.

`/cshift revertallnpc` does the same for every entry, without aiming. For an NPC whose
chunk is not loaded, `findEntity()` returns nothing: the entry is dropped from the map and
the empty sync is broadcast, but the entity itself is out of reach, so **its speed modifier
stays on it**. That modifier is persistent and lives in the entity's saved data. The NPC
keeps its custom speed, with no transform left to explain it. Revert NPCs with a custom
speed while their chunk is loaded, or set their speed back to `1.0` first.

### Scale

`/cshift scalenpc <value>`, or `POST /api/v1/shift/scale` with the NPC's UUID. Clamped to
`[0.1, 10.0]`. The value is stored, broadcast, and saved. On the client,
`NpcTransformModelMixin` multiplies the model by it with `matrices.scale()`. Nothing
changes on the server: no attribute, no hitbox.

### Speed

`/cshift speednpc <value>`, or `POST /api/v1/shift/speed`. Clamped to `[0.1, 10.0]`.

This is the only server-side effect of an NPC transform. `applySpeedModifier()` puts a
`GENERIC_MOVEMENT_SPEED` modifier on the entity:

| Property | Value |
|---|---|
| id | `cobbleshift:npc_speed` |
| operation | `ADD_MULTIPLIED_BASE` |
| amount | `speed − 1.0` |
| persistent | yes |

`2.0` doubles the base speed, `0.5` halves it, and `1.0` removes the modifier entirely.
Whether that changes anything visible depends on the NPC moving at all. A static EasyNPC
stays static; an NPC on a patrol route walks it faster. How EasyNPC uses the attribute is
an observation, not something this mod controls.

Every 100 ticks, `tick()` walks the entries with a custom speed and re-adds the modifier to
any loaded NPC that lacks it. The comment in the code gives chunk loading as the reason.
Since the modifier is persistent, this is mostly a safety net against something else
stripping it.

## Persistence

### When the file is read and written

| Moment | What happens |
|---|---|
| `SERVER_STARTED` | `load()` reads the file, if it exists, into the map |
| first server tick after a non-empty load | logs that the transforms are ready, nothing more |
| each transform, revert, scale or speed change | `save()` rewrites the whole file |
| `revertallnpc` | one `save()` per reverted NPC whose entity is loaded, then one more |
| `SERVER_STOPPING` | `save()`, **unconditionally**, even with nothing to save |

The last row means a clean stop always leaves a file behind, containing `[]` if no NPC was
ever transformed. NPCs are saved on stop and never reverted; that is the opposite of player
transforms, which `SERVER_STOPPING` reverts.

The two log lines after a restart with saved transforms:

```text
[CobbleShift] Loaded 3 NPC transform(s) from config/cobbleshift_npc_transforms.json
[CobbleShift] Loaded 3 NPC transform(s), will re-apply on first tick
```

followed on the first tick by:

```text
[CobbleShift] 3 NPC transform(s) ready for client sync
```

Despite the wording, nothing is "re-applied" at that point. Clients learn about NPC
transforms when they join: the `JOIN` handler calls `broadcastAllNpcTransforms()`, which
sends one silent sync per entry to the joining player. Since nobody is connected when the
server starts, that is enough.

### The save file

```text
config/cobbleshift_npc_transforms.json
```

⚠️ This path is a **literal relative path**, `Path.of("config/cobbleshift_npc_transforms.json")`,
resolved against the server's working directory. It does not go through
`FabricLoader.getConfigDir()` the way `cobbleshift.json` does. On a standard layout both
files end up in the same `config/` folder. If your host launches the server from another
directory, or relocates the config directory, the NPC file lands somewhere else.

A JSON array, one object per NPC:

```json
[
  {
    "npcUuid": "1a2b3c4d-0000-4000-8000-000000000001",
    "species": "snorlax",
    "shiny": false,
    "type1": "normal"
  },
  {
    "npcUuid": "1a2b3c4d-0000-4000-8000-000000000002",
    "species": "lycanroc",
    "shiny": true,
    "form": "midnight",
    "type1": "rock",
    "scale": 1.5,
    "speed": 2.0
  }
]
```

`scale` and `speed` are written only when they differ from `1.0`, and read back as `1.0`
when absent. `form`, `type1` and `type2` are **omitted** when they are `null`: the Gson
instance that writes the file does not serialise nulls, so a key set to `null` is dropped.
`load()` reads a missing key back as `null`.

Editing the file by hand works only with the server stopped: the next change in game
overwrites it from memory. A `species` value is not re-validated on load, so a typo
survives until a client tries to build the model and fails quietly.

⚠️ A malformed entry is dangerous. `load()` clears the map, then adds entries one by one; an
exception partway through (a bad UUID, a missing `species`) stops the loop and logs
`Failed to load NPC transforms: <message>`, keeping only the entries read before it. The
next `save()`, which happens on the next change or on the next stop, writes that partial
map back and **the remaining entries are lost**. Keep a copy of the file before editing
it.

## How the client draws it

The server sends a `TransformSyncPayload` with `fromNpc = true`. The packet's UUID field is
named `playerUuid` for historical reasons; for an NPC it carries the NPC's UUID. The
client receiver checks `fromNpc` and never plays a cry or spawns particles for it, whatever
the `silent` flag says. An empty species means revert.

`TransformRenderer` stores the transform in the same maps as players, keyed by UUID. Then
`NpcTransformModelMixin`, injected at the head of `EntityRenderDispatcher.render()`, runs for
every rendered entity:

1. Returns at once for any `PlayerEntity`, which the player mixin handles.
2. Returns if the entity's UUID has no transform.
3. Gets or builds the ghost: a client-side `PokemonEntity` created by reflection, never
   added to the world.
4. Copies position, rotation, velocity, body and head yaw and limb speed from the NPC onto
   the ghost, and ticks the ghost's Cobblemon animation delegate once per game tick.
5. Renders the ghost at the NPC's position, scaled by the stored `scale`.
6. Cancels the NPC's own render.

Any exception in that path is swallowed. If Cobblemon's renderer throws midway, the mixin
unwinds the matrix stack and the frame continues. The visible symptom of a failure is the
NPC rendering as itself, or not rendering at all, with nothing in the log.

The ghost has no sleep pose, no attack animation and no camera logic: `syncGhostToEntity()`
is the stripped-down version of the player sync.

`ShinyChimeMuteMixin`, which silences Cobblemon's shiny chime around shiny morphs, looks only
at transformed **players** (`getPlayerByUuid`). A shiny NPC is not covered. Whether its
ghost actually emits the chime depends on Cobblemon's client code and has not been
verified.

## Driving it as an admin

| Action | Command | Dashboard |
|---|---|---|
| transform an NPC | `/cshift transformnpc <pokemon…>` | — |
| revert one | `/cshift revertnpc` | — |
| revert all | `/cshift revertallnpc` | — |
| resize the model | `/cshift scalenpc <value>` | `POST /api/v1/shift/scale` |
| change walking speed | `/cshift speednpc <value>` | `POST /api/v1/shift/speed` |
| list them | — | `GET /api/v1/shift/transforms`, in the `npcs` array |

There is no REST route to transform or revert an NPC: the dashboard can only list and tune
what you created in game. `GET /transforms` is also the only place that shows NPC
transforms, with their UUIDs; `/cshift list` shows player transforms only. Route details are
in [the dashboard API](dashboard.md).

⚠️ The two dashboard tuning routes call `setNpcScale()` and `setNpcSpeed()` directly on the
HTTP thread, without `executeOnServerThread`. `setNpcSpeed()` looks the entity up and
modifies its attributes from that thread, and both write the save file concurrently with
the server thread. It works in practice on a quiet server; it is still a data race.
[Architecture](architecture.md#threading) lists it with the other off-thread calls.

## Edge cases

**The NPC is deleted.** Nothing listens for entity removal. The entry stays in the map and
in the file, is sent to every player who joins, and costs a little bandwidth forever. Only
`/cshift revertallnpc` clears it, and that also clears every live NPC.

**The NPC is in an unloaded chunk.** The transform is unaffected. Clients already know the
UUID and draw the model when the entity loads in. Speed changes made from the dashboard
while the NPC is unloaded are stored and applied by the 100-tick check once it loads.

**A client joins after a restart.** It receives every NPC transform on join, silently. No
cry plays for any of them.

**A client switches servers.** The client never clears its transform maps on disconnect
(`TransformRenderer.clear()` has no caller). A UUID transformed on one server stays
registered on the client for the rest of the game session. For NPCs the risk is low,
since entity UUIDs rarely collide across servers.

---

← [Passive abilities](abilities.md) · [Dashboard API](dashboard.md) →
