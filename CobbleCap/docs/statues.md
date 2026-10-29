[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](statues.fr.md)

# Statues and dioramas

A diorama is a frozen tableau: the League Master standing in front of the six Pokémon they
won with. It is built out of vanilla entities and Cobblemon entities, with no custom
entity type and no custom model, which is what makes it work on a vanilla client.

All commands live under `/cobblecap statue …` and are documented in
[statue, chrono and champion commands](commands-statues.md). This page explains what those
commands build.

## What a diorama is made of

| Figure | Entity | How it is made visible |
|---|---|---|
| the trainer | an **invisible marker `ArmorStandEntity`** | a separate fake-player "doppelgänger" overlaid on it |
| each Pokémon | a real Cobblemon `PokemonEntity` | spawned by dispatching `/spawnpokemonat`, then frozen |

The armour stand is not the statue — it is the tracker. It is spawned with
`setInvisible(true)` and `Marker: true` in NBT, no gravity, invulnerable, silent, arms
shown, base plate hidden. It exists so the move/rotate/scale/style commands have something
to address, and so `ENTITY_LOAD` has something to fire on.

The reason for that split is stated in the source: equipping a player head on a visible
armour stand gives you a head bolted onto an armour-stand body. The requirement was a full
player body with arms, legs and real proportions, and the only way to get that on a vanilla
client is to send it an actual player entity.

### The doppelgänger

`ChampionDoppelganger` is a fake player — a server-side player-shaped entity with a real
`GameProfile` — spawned on top of the tracker and shown to viewers individually through
packets. `MojangProfileFetcher.resolve` builds that profile through a four-tier ladder:
the online player, then the server's user cache, then a session cache, then a direct Mojang
HTTP lookup. That last tier is what lets an **offline** champion's skin render, which is the
normal case for a statue.

No profile with textures, and the statue simply does not appear:

```text
[CobbleCap] No profile with textures for Steve (f84c6a79-…) — doppel will not render
```

Keeping a fake player visible takes real work, and `StatueManager.init` registers five
listeners for it:

| Event | Why |
|---|---|
| `ServerPlayConnectionEvents.JOIN` | fast path, so a player spawning in range sees it at once |
| `ServerPlayConnectionEvents.DISCONNECT` | forget the viewer so a re-login re-sends the spawn |
| `ServerEntityEvents.ENTITY_LOAD` | re-attach the doppelgänger when a long-unloaded chunk comes back |
| `ServerEntityEvents.ENTITY_UNLOAD` | drop it |
| `EntityTrackingEvents.START_TRACKING` / `STOP_TRACKING` | the real safety net — Minecraft drops a fake player when the viewer leaves **entity** tracking range, which is smaller than chunk range, and never re-sends it |

On top of those, `tick()` runs an auto-heal every **60 ticks** (3 seconds) calling
`ensureVisibleToNearby(128.0)` on every doppelgänger, with 128 blocks chosen as a margin
over vanilla's 160-block tracking radius. The source is candid about why: JOIN and
`START_TRACKING` do not always deliver, world reference equality has drifted across
reloads, and marker armour stands do not reliably propagate `START_TRACKING`. The auto-heal
is what actually keeps statues visible; the events are optimisations.

There is also an explicit **de-duplication** step. Two `ServerPlayerEntity` instances
sharing a UUID confuse the client's skin texture cache and you get the Steve fallback, so
before registering a new doppelgänger any other one with the same synthetic UUID is hidden
and dropped:

```text
[CobbleCap][Doppel] DEDUP: removing stale doppel for syntheticUuid=… (old anchor=…, new anchor=…)
```

On boot, `rebindDoppelsOnBoot` scans every world for player-slot trackers and re-attaches
an overlay to each. Armour stands persist across a restart; their overlays do not, so
without that pass every statue would come back headless after a reboot.

### The Pokémon

`spawnPokemonStatue` does not construct an entity. It builds a Cobblemon properties string
from the stored `TeamMember` and dispatches a command:

```text
spawnpokemonat 128 71 -344 garchomp level=78 shiny mega
```

Species first, then `level=` when non-zero, then `shiny`, then the `form` aspect, then every
other aspect except duplicates of the form and `shiny`. Coordinates are **integers** —
`/spawnpokemonat` takes a `BlockPosArgument` and would truncate decimals anyway.

Two sharp edges, both handled:

- Cobblemon 1.6.1's spawn command calls `source.getPlayer().getUuid()` and throws an NPE if
  the source has no player. So the command runs on `caster.getCommandSource()` — player
  bound — and the method **fails fast**, returning `false` without dispatching, when there
  is no valid caster. That is why a refresh looks for any online operator
  (`findOnlineOp`) to borrow as a proxy, and why `place_current` dioramas do not refresh
  while no operator is online.
- The command returns no entity. The new Pokémon is found by a **heuristic**: snapshot every
  Cobblemon entity UUID in the world before the dispatch, then look for an untagged
  Cobblemon Pokémon within 4 blocks of the target position afterwards, nearest first. On a
  busy server with Pokémon spawning nearby this can in principle pick up the wrong entity.
  In practice the untagged-and-within-4-blocks filter makes that rare, but it is a heuristic
  and not a guarantee.

A team member with an empty `species` is skipped, and `placeFullDiorama` reports which ones
failed:

```text
spawned 5 entit(y/ies) — failed: zacian
```

## Layout

`placeFullDiorama` uses the caster's exact position and yaw:

- the trainer stands where the caster stood, facing the same way
- the Pokémon form a row **3.5 blocks behind** the trainer, 2 blocks apart, centred on the
  trainer's axis

With fewer than six Pokémon the row stays centred. `scale` applies to everything placed in
that call.

There is no collision check and no ground snap. The trainer appears at the caster's feet
position, so standing on a slab or mid-jump puts the statue there too. Place from a standing
position on flat ground, then adjust with `move`.

## The tags

Every entity in a diorama carries command tags, and those tags are the only index — there
is no registry of placed statues on disk. `/cobblecap statue list` works by scanning worlds
for tags.

| Tag | Meaning |
|---|---|
| `cobblecap_statue` | the master tag; everything in the namespace carries it |
| `cobblecap_statue_test` | spawned by `/cobblecap statue test` |
| `cobblecap_statue_current` | part of a dynamic current-champion diorama |
| `cobblecap_statue_legend.<name>` | the legend this figure belongs to, **lower-cased** |
| `cobblecap_statue_slot.<slot>` | `player`, or `0`–`5` for the party slots |
| `cobblecap_statue_style.<key>` | the current style |

Two consequences of using the lower-cased player **name** as the key: renaming a player
orphans their diorama, and two legends whose names differ only in case collide.

## Styles

A style is three things: a glow colour, a particle aura, and a cosmetic armour preset.

`ensureTeams` creates one vanilla scoreboard team per style, named `cgcap_<key>`, at server
start. The glow outline colour comes from the team, because vanilla glow uses a named
`Formatting` colour rather than raw RGB — `closestFormatting` picks the named colour nearest
the style's hex value in RGB space. `natural` has no glow.

`tick()` runs every **10 ticks** and, for every entity carrying the master tag:

1. re-applies a 30-tick `GLOWING` status effect when the style glows, so the outline never
   lapses
2. spawns 3 particles at 60% of the entity's height, spread by its width and height
3. moves the entity onto the right team if it drifted off

That is a full iteration of every entity in every loaded world, once per 10 ticks. It is
cheap in the common case because the tag check is first, but it is still a scan.

The armour presets in `equipStyleArmor` are applied to the **armour stand**, which is
invisible, so in practice they are not visible on a player statue:

| Style | Armour |
|---|---|
| `stone` | iron |
| `gold` | gold |
| `emerald`, `diamond`, `amethyst`, `crystal` | diamond |
| `copper` | chainmail |
| `netherite`, `obsidian` | netherite |
| `frost` | leather, no boots |
| `soul` | netherite, no boots |
| `glass` | none, and `setInvisible(true)` |
| `natural` | none |

⚠️ `equipStyleArmor` has **no caller** in `StatueManager`. The method is dead code, left
from the earlier visible-armour-stand design that the doppelgänger approach replaced. The
visible effect of a style today is the glow colour and the particles, nothing else.

## The dynamic champion diorama

`place_current` and `place_current_player` build a diorama bound to a role rather than a
person. The position is stored as a `DioramaAnchor` in `config/cobblecap_dioramas.json`:

```json
{
  "anchors": [
    {
      "worldId": "minecraft:overworld",
      "x": 128.5,
      "y": 71.0,
      "z": -340.5,
      "yaw": 180.0,
      "styleKey": "gold",
      "scale": 1.0
    }
  ]
}
```

`playerOnly` is written only when true, and `placeholderLegend` holds a full serialised
`LegendEntry` when one is in use. Written atomically through a `.tmp` sibling.

### Who "the current champion" is

`currentChampionEntry()` resolves the **ranked** champion — the single open tenure in
`TenureStorage`. Classic League Masters deliberately get no dynamic diorama; they get a Hall
of Fame entry and a chrono record.

From that tenure's `championUuid` it walks a three-step ladder, because `LegendsStorage` and
`TenureStorage` can disagree:

1. the most recent non-test entry for that UUID with `mode == RANKED`
2. failing that, the most recent non-test entry for that UUID whatever its mode — right
   identity, right team
3. failing that, an entry synthesised from the `Tenure` alone — right UUID and name, so the
   skin is correct, but **no team**, so no Pokémon row

### The placeholder

With no ranked champion yet, `place_current` does not refuse. It snapshots the **caster's**
party and uses the caster as a placeholder, and that placeholder is serialised into the
anchor so a visible body survives restarts. The reply says so:

```text
[CURRENT] PLACEHOLDER (no champion yet — using your profile; auto-replaces when first champion is crowned) — spawned 7 entit(y/ies)
```

The first real champion replaces it, and the placeholder is then dropped from the anchor so
stale snapshots do not accumulate in the file.

### When it refreshes

`refreshCurrentChampionDioramas` wipes every `cobblecap_statue_current` entity in every
world and respawns one diorama per anchor. It is called from:

- `RctTrainerMorpher.dispatchReload`, at the end of every morph flush
- `RctTrainerMorpher.restoreOriginal`
- `StatueManager.init`, right after boot
- `/cobblecap champion refresh_dioramas`

The wipe-and-respawn happens even in placeholder mode, because the boot rebind pass cannot
resolve a placeholder caster who has no `LegendsStorage` entry.

⚠️ The refresh is coupled to the morph, and the morph aborts when the datapack is not found
(see [champion morphing](champion-morphing.md#what-you-must-change)). On a server where
`datapackName` is still `CacademyMons`, dynamic dioramas never refresh on a win — only the
boot refresh and the manual command work.

## Test mode

`/cobblecap statue test` writes a `LegendEntry` with `isTest: true` and stages a diorama
from the caster's current party. Test entries are invisible to the public: excluded from
`getAll()`, `hasLegend()`, `isEmpty()`, `/cobblecap legends`, every broadcast, the Discord
webhook and the Conseil 4 boards. Only `getAllIncludingTests()` — used by the statue
subsystem — and `findLatestForPlayerName(name, includeTests = true)` see them.

Note the asymmetry in which lookups include tests:

| Caller | Includes tests |
|---|---|
| `statue place` / `place_player` / `place_pokemon` | **no** — `includeTests = false` |
| the doppelgänger re-attach paths (`ENTITY_LOAD`, `START_TRACKING`, boot rebind) | **yes** |
| `/cobblecap statue test` | creates one |

So a test diorama renders and survives restarts, but you cannot re-place it by name with
`statue place` — only `statue test` creates it. Clean up with `/cobblecap statue test_clear`,
which removes only the caster's own test statues and test legend rows.

## Cleanup

| Command | Scope |
|---|---|
| `statue remove_slot` | one figure |
| `statue remove` | one legend's whole diorama |
| `statue test_clear` | the caster's test statues and test legend rows |
| `statue wipe_all` | ⚠️ every statue entity in every world, plus the anchor list |

`wipe_all` is tag-based rather than anchor-based, which is the point: it catches orphan
invisible armour stands whose doppelgänger never re-attached, and those are invisible and
therefore impossible to find by hand. It clears `currentChampionAnchors` too, so
`place_current` starts from nothing afterwards.

Every removal goes through `despawnWithDoppel`, which hides the overlay before discarding
the tracker. Discarding a tracker without that would leave a fake player visible to
everyone who had already been sent it, with nothing to remove it.

## Known weaknesses

- **Tags are the only index.** A statue whose tags are stripped — by `/tag remove`, or by a
  world edit that copies entities — becomes an orphan that only `wipe_all` can find.
- **Keyed by player name.** Renames orphan dioramas.
- **The Pokémon spawn is a heuristic.** No API returns the spawned entity, so it is located
  spatially.
- **The refresh needs an online operator.** `findOnlineOp` returns `null` when nobody with
  level 2 is connected, and the Pokémon row is then skipped for that refresh.
- **Dead code.** `equipStyleArmor` is never called.
- **The style tick scans every entity in every world** every 10 ticks.

---

← [Champion morphing](champion-morphing.md) · [The client side](client.md) →
