[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](champion-morphing.fr.md)

# Champion morphing

The idea: the final boss of the league is whoever beat it last. A player wins the Conseil 4
in ranked mode, and the RCT trainer `champion_regis_challenge` is rewritten to fight with
that player's team.

⚠️ **This is the most fragile part of CobbleCap and the part most likely to break.** It
works by writing files into a third-party mod's datapack directory and then calling a
private method of that mod by reflection. It will break when Radical Cobblemon Trainers
changes its file layout or its class names, and nothing in CobbleCap can detect that in
advance. If you are adapting this mod to another server, read
[what you must change](#what-you-must-change) before anything else.

## What it rests on

| Dependency | How it is reached | What breaks if it changes |
|---|---|---|
| **rctmod** datapack file layout | a path built from three mutable static fields in `RctTrainerMorpher` | the datapack root is not found and the morph is skipped with a warning |
| `com.gitlab.srcmc.rctmod.api.RCTMod` | reflection: `getInstance().getTrainerManager().loadTrainers()` | the file is written but the running server keeps the old team until restart |
| `com.gitlab.srcmc.rctmod.world.entities.TrainerMob` | reflection, for the challenge swap | challenge mode stops swapping |
| Mojang session server | plain HTTPS, for the skin — **currently unused** | nothing; see [the skin](#the-skin-is-not-written) |

None of this is a compile-time dependency. `build.gradle` has no rctmod entry at all;
every call is `Class.forName`. The mod loads and runs without rctmod — the morph simply
never finds anything.

## The flow

```text
ranked win / /cobblecap champion morph_now
        │
        ▼
RctTrainerMorpher.request(player, bestTimeMs)
        │  snapshots the party into a PendingMorph (AtomicReference)
        ▼
tick(server)  — every 20 server ticks
        │  compareAndSet(pending, null)   ← one flush per request, no double-apply
        ▼
flushMorph
        1. locateDatapackRoot
        2. ensureBackup                  ← first morph only
        3. rewrite the trainer JSON's "team" array
        4. RegisModeManager.saveRankedSnapshot
        5. refresh per-dim clones, skin doppelgängers
        6. RctReloadHook.reloadTrainers
        7. StatueManager.refreshCurrentChampionDioramas
```

### The queue

`request` does not morph. It snapshots the player's party — through
`ChampionManager.snapshotPlayerTeam`, the same reflection the Hall of Fame uses — and
stores a `PendingMorph` in an `AtomicReference`. A later request simply overwrites a
pending one, so only the most recent champion is ever applied.

`tick` runs on `END_SERVER_TICK` but acts only every **20 ticks**, one second. It takes the
pending value with `compareAndSet(pm, null)`, which guarantees a single flush even if two
ticks were somehow interleaved.

### The battle check is dead code

The queue exists because of a safety concern: do not change a trainer's team while someone
is fighting that trainer. `RctTrainerMorpher` still contains `countActiveBattles()`, which
reflects into Cobblemon's `BattleRegistry`, finds its first `Map`-typed declared field, and
returns that map's size — or **−1 when the registry cannot be introspected**, with the
contract, written in its own Javadoc, that callers must treat −1 as "unknown" and defer the
morph. That is the fail-closed check.

**Nothing calls it any more.** `tick` flushes at the next one-second boundary regardless.
The source comment explains why: on a thirty-player server the active battle count is
virtually never zero, so the deferral deferred forever. And the deferral turned out to be
unnecessary, because rctmod loads a trainer's team snapshot at battle start — rewriting the
JSON mid-fight does not affect a fight in progress, only the next one.

So: the method is live code that is never invoked, and the guarantee it was written for is
instead provided by rctmod's own caching. That is a reasonable trade, but it is an
observation about rctmod's behaviour rather than a contract, and it would stop holding if
rctmod ever started re-reading a trainer mid-battle.

### Locating the datapack

`locateDatapackRoot` tries five candidates in order and takes the first that is a directory
**and** contains the trainer JSON at the expected relative path:

1. `server.getSavePath(WorldSavePath.DATAPACKS)/<datapackName>`
2. `<runDir>/<level-name>/datapacks/<datapackName>`
3. `<runDir>/world/datapacks/<datapackName>`
4. `<runDir>/datapacks/<datapackName>`
5. `<runDir>/resourcepacks/<datapackName>`

The fifth exists because Arkensia's AutoModpack layout mirrors RCT data under
`resourcepacks/`. On success it logs the chosen path; on failure it logs every candidate it
tried, which is the line to read when a morph does nothing:

```text
[CobbleCap] Datapack root resolved: /srv/mc/world/datapacks/CacademyMons
[CobbleCap] Datapack 'CacademyMons' not found in any of: [...]
```

### The backup

`ensureBackup` runs before the first write and copies the live files into
`config/cobblecap_regis_backup/`:

| File | Contents |
|---|---|
| `champion_regis_challenge.json` | the pristine, pre-morph trainer — the **classic** variant |
| `champion_regis_challenge.png` | the original skin |
| `champion_regis_challenge.MORPHED.json` | the latest morph — the **ranked** variant, written by `RegisModeManager.saveRankedSnapshot` |

It never overwrites an existing backup, so the first snapshot is always the pristine state.
It also refuses to back up a JSON that already contains the `__cobblecap_champion` marker,
logging `Live trainer JSON already morphed — backup skipped (no pristine copy available)`.

⚠️ If CobbleCap is installed on a server where the trainer JSON was already morphed by a
previous install, there is no pristine copy and `restore_original` cannot bring it back.
Run `/cobblecap champion backup_now` before the first morph on any new server.

### The rewrite

The existing JSON is parsed and **patched**, not replaced: only the `team` array is
substituted, so `name`, `ai`, `battleRules` and anything else rctmod defines survive. If the
file is missing or unparseable, a minimal template is built with
`name.translatable = trainer.rctmod.champion_regis_challenge`, `ai.type = "rct"` with
`maxSelectMargin: 0.01`, and `battleRules.maxItemUses: 6`.

Each team member becomes:

```json
{
  "species": "garchomp",
  "level": 78,
  "gender": "MALE",
  "nature": "adamant",
  "shiny": true,
  "aspects": ["mega"]
}
```

`species` falls back to `"ditto"` when the snapshot has none, and every other field is
omitted when empty or zero. A provenance block is appended:

```json
"__cobblecap_champion": {
  "playerUuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1",
  "playerName": "Steve",
  "bestTimeMs": 1122000,
  "morphedAt": 1778000000000
}
```

rctmod ignores that key; CobbleCap uses it as the "already morphed" marker, and it tells you
at a glance whose team a trainer file currently holds. The write is atomic through a `.tmp`
sibling.

### The reload

There is no `rctmod reload` command. The source says so explicitly, verified against
rctmod-fabric 0.16.11-beta bytecode: only `rctmod player …` and `rctmod trainer …` exist.
An earlier version dispatched a reload command in a loop, every attempt threw, every
exception was swallowed, and the morphed team therefore only took effect after a full
restart.

`RctReloadHook` replaces that with reflection onto
`RCTMod.getInstance().getTrainerManager().loadTrainers()`, which re-reads the trainer JSONs
from disk and resyncs clients **without** a vanilla `/reload` datapack rescan — a rescan
would freeze a large modded server for seconds. It must run on the server thread, and it
fails open: any reflection failure logs the real cause and returns `false`.

```text
[CobbleCap] rctmod TrainerManager.loadTrainers() invoked (ok=true)
[CobbleCap] rctmod reload failed after morph -> Steve — JSON on disk updated, restart to apply.
```

The second line is benign in the sense that nothing is corrupt: the file is correct and a
restart applies it.

## The skin is not written

The mod does **not** change the trainer's texture.

`downloadMojangSkin` is fully implemented — UUID to session profile, base64 `textures`
property, skin URL, PNG bytes — and `writeAtomicBytes` is there to write it. Neither is
called. `flushMorph` logs:

```text
[CobbleCap] Skin morph skipped (architectural limitation — see RctTrainerMorpher comment)
```

The reason, from the source: the previous implementation wrote to
`datapacks/<name>/assets/…`, and Minecraft never loads assets from a datapack — textures
come from resource packs. Writing to `resourcepacks/` would require regenerating the
AutoModpack bundle and making every client re-download it, which is too disruptive to do on
a win. The intended fix is a runtime "doppelgänger overlay", and parts of it exist
(`RegisDoppelgangerManager`, `ChampionDoppelganger`) and are refreshed on every morph, but
the trainer file's own texture is left as it was.

So: **the team morphs, the face does not**. Two dead methods and the `trainerSkinRelPath`
field remain for whoever finishes the job.

## The Régis mode swap is global

`RegisModeManager` keeps the live trainer file as a copy of one of two persisted variants —
the pristine `champion_regis_challenge.json` for classic, the `…MORPHED.json` for ranked —
and `/cobblecap mode` switches between them. `applyMode` takes a `ReentrantLock`,
short-circuits when the requested mode is already live, copies the source over the live
file, and dispatches the same reflective reload.

Asking for ranked before anyone has ever morphed falls back to classic and logs
`No ranked snapshot yet — falling back to classic`. With neither file present the swap
aborts.

⚠️ There is **one** live file, and it is server-global. The player's chosen mode is stored
per player on the `cobblecap_mode` scoreboard, but the trainer they actually fight is
whichever variant was written last. Two players entering the Conseil 4 a second apart with
opposite modes will both fight the second one's choice. The source acknowledges this: the
design is "Option B", chosen on the grounds that at five to ten entries a week the race is
rare, and the no-op check coalesces repeats. It is a real correctness hole, not an
oversight, and the only robust fix is per-player trainer instances — which is what
`Conseil4TrainerCloner` was meant to do before it was disabled.

## The fallback: a Cobblemon NPC

`ChampionNpcManager` is a second, independent path. Instead of rewriting a datapack, it
binds a **Cobblemon NPC entity** and morphs that entity in memory. Nothing in the normal
flow calls it — it is driven only by the `/cobblecap champion bind_npc*` commands, and
`morphTo` has no caller in the mod at all, so in practice binding an NPC records a binding
and little else happens automatically.

What `morphTo` would do, for whoever wires it up:

1. Resolve the bound entity by UUID, preferring the recorded dimension and then scanning
   every loaded world.
2. Verify it is a Cobblemon NPC — the type id must start with `cobblemon:` and either
   contain `npc` or have the simple class name `NPCEntity`.
3. Apply the skin by scanning the entity's public methods for a one-argument `String`
   method whose lower-cased name contains `texture` plus one of `profile`, `playername` or
   `username`. Cobblemon does the Mojang download itself.
4. Copy the party: try a `setParty` overload that accepts the player's party store
   directly; otherwise clear the NPC's party and, for each of the player's Pokémon, clone it
   (`clone`/`copy`/`cloneWithoutID`/`createDeepCopy`) and add the clone.

The clone step is **fail-closed**: a Pokémon with no usable clone method is skipped rather
than shared by reference, because sharing would make the player's and the NPC's Pokémon
mutate each other. It logs `Skipped N Pokémon (no clone method) — NPC team will be
incomplete`.

The outcome is a `MorphOutcome` with one of `OK`, `NO_BINDING`, `NPC_NOT_FOUND`,
`NOT_COBBLEMON_NPC`, `SKIN_FAILED`, `PARTY_FAILED`, `EXCEPTION`. Skin and party failures are
independent: either one alone still returns a partial success with the other applied.

`ChampionNpcState` persists the binding and the last morphed champion's name, which is what
`/cobblecap champion status` prints.

This path is **less fragile** than the datapack rewrite — it touches no files and survives
an rctmod update — but it morphs a Cobblemon NPC, not an RCT trainer, so the NPC does not
participate in rctmod's progression. Treat it as an escape hatch rather than an equivalent.

## What you must change

| Constant | File and line | What it is |
|---|---|---|
| `datapackName = "CacademyMons"` | `src/main/java/com/cobblecap/logic/RctTrainerMorpher.java` **line 57** | the name of the datapack directory holding the RCT trainer files |
| `trainerJsonRelPath` | same file, line 55 | `data/rctmod/trainers/champion_regis_challenge.json` |
| `trainerSkinRelPath` | same file, line 56 | `assets/rctmod/textures/trainers/single/champion_regis_challenge.png` — unused today |
| `TRAINER_ID = "champion_regis_challenge"` | same file, line 52 | the trainer that gets morphed |

All three paths are `public static` and non-final, so they can be reassigned at runtime from
another mod — but **nothing in CobbleCap reads them from a config file**, despite the
comment above them saying "Configurable from CapConfig". That comment is wrong: no
`CapConfig` key touches them. Changing them means editing the source and rebuilding.

`CacademyMons` is Arkensia's datapack. On any other server, `locateDatapackRoot` will find
no directory by that name, every candidate will miss, and the morph will abort with the
`Datapack 'CacademyMons' not found` warning on every ranked win and every `morph_now`.
Nothing else breaks — no crash, no corruption — but the champion never changes, and the
`place_current` dioramas never refresh either, because their refresh hangs off the same
flush.

---

← [The champion system](champions.md) · [Statues and dioramas](statues.md) →
