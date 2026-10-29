[← CheeseRaids](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](calibration.fr.md)

# Calibrating a raid

> ⚠️ Nothing in this page has been validated by an actual raid run with players. The
> procedure below is derived from the code, not from experience.

A fresh `config/cheeseraids/raids/hoopa.json` contains four positions that all default
to `minecraft:overworld` at `0.5 / 65 / 0.5`. That is the world origin, almost certainly
under the ground, and nothing about the raid works until you replace them with real
coordinates from your own map. This is the one setup step the mod cannot do for you.

The four positions, and what each one is for:

| JSON path | What sits there | Consequence if wrong |
|---|---|---|
| `wave.arena` | the overworld spot where the invasion wave of Pokémon spawns | the wave spawns at origin, or not at all |
| `npc.pos` | where the Ho-Oh herald EasyNPC stands | the herald is never found, and players are told to use a command instead |
| `reward.portalAnchor` | where the victory portals open | portals open at origin, or are skipped |
| `exitPos` | where players are teleported when the raid ends | players are ejected to origin, possibly inside blocks |

`arena.center` is a fifth position, but it lives inside the generated rift dimension and
its default (`cheeseguard:hoopa_rift` at `0.5 / 100 / 0.5`) is already correct. Leave it
alone. Only its `x`/`y`/`z` are read: the code always builds the world id as
`cheeseguard:` + `riftDimId` and ignores `arena.center.world`.

---

## Step 1 — survey the coordinates in-world

Fly to each location and read your position. Vanilla gives you three ways; use whichever
you prefer.

```mcfunction
/data get entity @s Pos
```

```text
RoiCheese has the following entity data: [112.4d, 71.0d, -2043.7d]
```

F3 also shows **XYZ** and, under **Block**, the integer block coordinates. The mod floors
your coordinates to a block (`BlockPos.ofFloored`), so the decimals only matter for
`exitPos`, where the player is teleported to the exact double.

You also need the dimension id, which F3 shows on the right as the world name. For a
CheeseGuard dimension it looks like `cheeseguard:<id>`, for vanilla
`minecraft:overworld`.

### What to look for at each spot

**`wave.arena`** — pick open, flat ground with at least `wave.spread` blocks of clearance
in every horizontal direction (default 6, so a 13×13 area), and headroom. Mobs spawn at
`arena.y + 1`, scattered randomly inside `±spread` on X and Z. The alpha leader is spawned
at scale 1.8, so it needs vertical room. Players fight the wave here, so it should be
somewhere they can reach and somewhere a thunderstorm is not going to ruin a build —
`wave.overworldStorm` forces `weather thunder` and `time set midnight` in this world for
the whole raid.

**`npc.pos`** — stand exactly where the herald should appear. The reveal only finds
EasyNPC entities **within 10 blocks** of this position, measured as an axis-aligned box
expanded by 10 in every direction, and only in the world named by `npc.pos.world`. Put
the NPC down first, then read your own coordinates while standing on it.

**`reward.portalAnchor`** — portals are placed on a circle of radius `portalSpacing`
(default 4.0) around this point, one per earned portal, evenly spaced. Allow a 10-block
clear circle. CobbleSafari's fallback path only works in the overworld, so an anchor in
another dimension may silently open nothing.

**`exitPos`** — where everyone lands after the victory. Somewhere safe and on the ground,
ideally near spawn. Set `yaw` and `pitch` too if you care which way players face.

## Step 2 — place the herald NPC

The herald is an [EasyNPC](https://modrinth.com/mod/easy-npc) entity. CheeseRaids does
not create it; it only toggles the visibility of whatever EasyNPC it finds near
`npc.pos`. Spawn one with EasyNPC's own tooling, dress it as Ho-Oh, and leave it there
permanently — the raid hides it between runs rather than deleting it.

The code filters candidates by registry id prefix `easy_npc`, so any EasyNPC entity type
qualifies. `npc.entityType` in the config (default `easy_npc:humanoid_slim`) is **not**
used for the search: it is only substituted into the optional `showCommand` /
`hideCommand` templates as `{type}`.

## Step 3 — write the coordinates into the file

Edit `config/cheeseraids/raids/hoopa.json`. Only the four position objects need
touching; everything else has a working default.

```json
{
  "id": "hoopa",
  "wave": {
    "arena": { "world": "minecraft:overworld", "x": 112.0, "y": 71.0, "z": -2043.0, "yaw": 0.0, "pitch": 0.0 }
  },
  "npc": {
    "pos": { "world": "minecraft:overworld", "x": 118.0, "y": 71.0, "z": -2050.0, "yaw": 180.0, "pitch": 0.0 }
  },
  "reward": {
    "portalAnchor": { "world": "minecraft:overworld", "x": 100.0, "y": 71.0, "z": -2030.0, "yaw": 0.0, "pitch": 0.0 }
  },
  "exitPos": { "world": "minecraft:overworld", "x": 120.5, "y": 71.0, "z": -2060.5, "yaw": 90.0, "pitch": 0.0 }
}
```

Keep the rest of the file as the mod wrote it. Every field in `RaidDefinition` has a
Java-side default, so a key you omit is filled in from the class — but the file the mod
writes on first run is complete, and editing in place is less error-prone than trimming
it.

Then reload:

```mcfunction
/cheeseraids reload
```

```text
Définitions de raids rechargées.
```

A reload re-reads every `*.json` in `config/cheeseraids/raids/`. If your edit broke the
JSON syntax, the file is **left untouched** and the console says so:

```text
[CheeseRaids] FAILED to parse raid def .../hoopa.json — fix the JSON syntax and
/cheeseraids reload. Left untouched. (...)
```

That is deliberate: a malformed file is never overwritten with defaults, so you do not
lose your work. But the old definition is not kept either: the reload drops it, so the raid
silently disappears from memory (`status` stops listing it) while the reply still claims
success. Read the console after every reload.

## Step 4 — verify each position

### The herald

```mcfunction
/cheeseraids herald hoopa info
```

```text
Héraut attendu: 118, 71, -2050 dans minecraft:overworld
EasyNPC dans 10 blocs: 1
  • easy_npc:humanoid_slim @ 118, 71, -2050 (dist 0.5, invisible=true)
```

`EasyNPC dans 10 blocs: 0` means the reveal will not find anything. The diagnostic also
widens its search to 128 blocks and lists what it finds there, labelled *mauvaise
position ?* — that is how you catch a coordinate typo. `⚠ AUCUN EasyNPC chargé à
proximité` means either the NPC is somewhere else entirely or the chunk is not loaded.

Chunk loading is a real trap here. The herald lives in the overworld at a spot no player
may be standing near, and an unloaded chunk has no entities to find. If `info` reports
nothing while you are certain the NPC is there, stand next to it and run the command
again.

Then check the toggle actually works:

```mcfunction
/cheeseraids herald hoopa show
```

```text
1 EasyNPC rendu(s) visible(s).
```

The NPC should appear with a glow outline. The count is the number of NPCs **found**, not
toggled: `1 EasyNPC rendu(s) visible(s).` with no visible change points at the EasyNPC
visibility API rather than at your coordinates — the console line then reads `0 toggled`.
See
[integrations](integrations.md#easynpc).

### The reward portals

```mcfunction
/cheeseraids testreward hoopa
```

This opens `max(1, reward.minPortals)` portals around the anchor and fires fireworks,
without running a raid. The reply names the count and the position:

```text
Test récompense: 1/1 portail(s) Hoopa à 100, 71, -2030 (minecraft:overworld)
```

`0/1` means CobbleSafari refused or is absent. `Monde d'ancrage introuvable: <id>` means
`reward.portalAnchor.world` does not name a loaded world — check for a typo, or that the
CheeseGuard dimension you pointed at exists.

### The wave arena and the exit

Neither has a dry-run command. Fly to both and confirm by eye: clearance at the wave
arena, solid ground at the exit. The first time you run the raid for real, watch the
console — a bad wave world is reported loudly, with the list of worlds that do exist:

```text
[CheeseRaids] wave arena world 'minecraft:overwrld' NOT FOUND — fix wave.arena.world.
Available: minecraft:overworld minecraft:the_nether minecraft:the_end cheeseguard:hoopa_rift
```

## Step 5 — a full rehearsal

Calibration is not finished until one raid has run end to end. With the four positions
set and the herald verified:

```mcfunction
/cheeseraids start hoopa
```

Then follow [the Hoopa sequence](hoopa-invasion.md) and watch for the five things that
can still be wrong after the coordinates are right:

1. **The wave does not spawn.** Cobblemon's spawn call was refused. The console carries
   the reason (`spawnEntity refused`, `Cobblemon API unavailable`).
2. **The herald never appears.** Covered above; the raid does not dead-end, it falls back
   to announcing `/cheeseraids enter hoopa` in chat.
3. **The rift does not open.** CheeseGuard could not create `cheeseguard:hoopa_rift`.
   `Failed to create rift dimension` is logged with the cause.
4. **The parkour is unreachable or impossible.** `arena.starRadius` (default 75) is the
   distance from the centre to a branch's spawn platform; geometry is generated from it.
   The jump feasibility was checked by simulation, never by a player.
5. **The boss does not spawn, or vanishes instantly.** The provider clears an air pocket
   sized from `boss.baseScale` before spawning and will respawn the boss up to twice if
   it disappears before a battle starts. If it still fails, `Boss spawn FAILED` is logged.

## Changing the rift dimension id

`arena.riftDimId` defaults to `hoopa_rift`, giving the world id
`cheeseguard:hoopa_rift`. Changing it is all it takes: every lookup resolves the rift by
concatenation, and `arena.center.world` is never read, so it cannot go out of sync. A
rift created under the old id is not renamed or deleted; it simply stops being used.

The dimension is created on demand, the first time a raid reaches `NPC_REVEAL` (or a player
enters, whichever comes first), with
`enabled = false` so nobody can walk into it outside a raid, `pvp = false`,
`mobSpawning = false`, time locked to 18000 (midnight) and weather locked. Those values
are set in code, not in the raid definition, so the config file gives you no handle on
them. Edit them through CheeseGuard's own dimension configuration afterwards if you need
something different.

---

← [Configuration](configuration.md) · [The raid model](raid-model.md) →
