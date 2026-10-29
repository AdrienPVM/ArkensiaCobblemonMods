[← CobbleShift](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](abilities.fr.md)

# Passive abilities

Passive abilities are the half of CobbleShift that is not about transforming. A player
keeps their own body and their own skin; what changes is that the Pokémon in their party's
first slot grants them a permanent, themed status effect. Carry a Charizard and you stop
burning. Carry a Gengar and you go invisible. Swap your lead and the buff follows within a
few seconds.

The subsystem is **off by default** — `abilityGlobalEnabled` is `false` in a fresh config.
Turn it on with `/cshift ability global enable`, which also writes the flag to disk.

---

## The model

Three pieces of state, all held by `AbilityManager`:

| State | Holds | Persisted |
|---|---|---|
| `activeAbilities` | one `AbilityData` per player: species, both types, a `forced` flag, the activation timestamp | no |
| `disabledPlayers` | UUIDs that opted out, set by `/cshift ability toggle` | no |
| `abilityGlobalEnabled` | the master switch | **yes**, in `config/cobbleshift.json` |

Only the master switch survives a restart. Per-player opt-outs are lost, and forced
abilities with them; automatic abilities are rebuilt from the party on the next refresh
tick, which is usually what you want: nothing to clean up after a crash.

An ability can be in one of two modes:

- **auto** — read from the player's lead Pokémon every refresh. The default.
- **forced** — pinned to a species by an admin with `/cshift ability <player> <pokemon>`.
  A forced entry skips the party read entirely, so it survives any party change until it
  is cleared.

## The refresh loop

`AbilityManager.tick()` runs on every server tick but returns immediately unless
`tickCounter % abilityTickInterval == 0`. With the default `abilityTickInterval` of 100
that is once every five seconds. On a refresh tick:

1. If `abilityGlobalEnabled` is false, return. Nothing is cleared — the effects already on
   players expire on their own.
2. For each online player: skip if their UUID is in `disabledPlayers`.
3. If their entry is forced, re-apply its effects and move on.
4. Otherwise, in auto mode, read the lead Pokémon and update the entry if the species
   changed. If the party read returns nothing, drop the entry and tell the client to clear
   the HUD.
5. Re-apply the effects of whatever entry now exists.

Effects are applied with a duration of `abilityEffectDuration` ticks, 160 by default — 8
seconds, deliberately longer than the 5-second refresh interval so the buff never flickers
between two refreshes. If you raise `abilityTickInterval` you must raise
`abilityEffectDuration` with it, or players will watch their buff drop and come back. The
config setters enforce floors of 20 and 40 ticks respectively but do **not** enforce that
relationship.

Every applied effect is marked ambient, with particles off and the HUD icon on. Ambient is
what makes the effect render as the faint beacon-style icon rather than a player-applied
one, and it is also how `cleanupStaleTransform` recognises the mod's own Invisibility.

## The effect table

Read straight out of `AbilityType`. The display names are stored in French in the enum and
are **not** translated at runtime, so they read the same in both languages.

| Type | Display name | Effects granted |
|---|---|---|
| `fire` | Flamme Intérieure | Fire Resistance I |
| `water` | Nage Rapide | Water Breathing I + Dolphin's Grace I |
| `grass` | Photosynthèse | Regeneration I |
| `electric` | Charge Statique | Speed I |
| `ice` | Coeur de Glace | Resistance I |
| `flying` | Plume Légère | Slow Falling I |
| `ground` | Ancrage | Resistance I |
| `rock` | Peau de Pierre | Resistance I |
| `steel` | Blindage | Resistance I + Fire Resistance I |
| `poison` | Immunité Toxique | **nothing usable** — see below |
| `psychic` | Perception Extra | Glowing I |
| `ghost` | Ombre Furtive | Invisibility I |
| `dark` | Vision Nocturne | Night Vision I |
| `dragon` | Fureur Draconique | Strength I |
| `fairy` | Grâce Féerique | Regeneration I + Luck I |
| `fighting` | Esprit Combatif | Strength I + Speed I |
| `bug` | Agilité Insecte | Jump Boost I |
| `normal` | Endurance | none — the enum's `NORMAL` branch is empty |

Every effect is amplifier 0, so always level I. There is no scaling by base stats, by BST
or by level: a Caterpie's Bug buff is identical to a Scizor's.

### Dual types stack

A dual-type Pokémon gets both type's effect lists applied. Scizor is `bug/steel`, so Jump
Boost, Resistance and Fire Resistance. Where both types grant the same effect the second
call is a no-op at equal amplifier and duration. There is no cap on how many effects a
dual type can stack, because no type grants more than two.

### `poison` and `normal` grant nothing

`NORMAL` is explicitly empty — the enum branch is `{}`.

`POISON` is a bug rather than a design choice. The branch adds
`new StatusEffectInstance(StatusEffects.POISON, 1, 0, ...)`, a one-tick Poison, with a code
comment claiming it clears an existing poison. It does not: vanilla's `addStatusEffect`
only replaces an existing instance when the new one is stronger or longer, so a one-tick
Poison cannot displace a real one, and on an unpoisoned player it does a single tick of
nothing. A Poison-type lead currently grants no benefit at all. Fixing it means clearing
the effect rather than re-adding it, which is a behaviour change, so it is left as-is and
documented here.

### Ghost is a trap on a public server

`ghost` grants real Invisibility. This effect can give an advantage in PvP; the subsystem
is off by default. On a PvP server, either keep it off, or opt players out with
`/cshift ability toggle`.

It also interacts with transforms: both use Invisibility, and a revert calls
`removeStatusEffect(StatusEffects.INVISIBILITY)` unconditionally. Revert a Ghost-lead
player and their ability invisibility goes with the transform's, until the next refresh
tick restores it. For up to five seconds they are visible.

## Reading the lead Pokémon

`getLeadPokemon()` walks Cobblemon by reflection, with a fallback at every step:

1. `com.cobblemon.mod.common.Cobblemon.INSTANCE` → `getStorage()`.
2. `getParty(ServerPlayerEntity)`, falling back to `getParty(UUID)`. If that returns a
   `CompletableFuture`, the code takes `getNow(null)` — a non-blocking peek, which returns
   `null` when the party has not finished loading. A player who just joined can therefore
   go an extra refresh cycle with no ability.
3. Slot 0 via `get(int)`, then `getSlot(int)`, then the first element of the iterator.
4. `getSpecies()`, then the species name through `getName()` → `getPath()`, falling back to
   `showdownId()`.
5. `getPrimaryType()` and `getSecondaryType()`, each run through `extractTypeName()`.

Any `ClassNotFoundException` returns `null` silently — that is the Cobblemon-absent path.
Any other failure logs at debug level and returns `null`, which looks identical from the
outside. Why these particular method names are safe to hardcode, and which ones are not,
is in [architecture](architecture.md#reflection-and-why-the-method-names-matter).

`extractTypeName()` tries `getName()`, `getDisplayName()` and `name()` in that order, then
a declared `name` field, then `toString()` lowercased. If all of that produces something
that is not a known type id, `AbilityType.fromTypeName()` falls back to `NORMAL` — so a
Cobblemon API change does not crash the loop, it quietly turns every ability into nothing.
That failure mode is worth remembering: silent `NORMAL` everywhere means the type
extraction broke, not that your config is wrong.

## The client HUD

`AbilityHudRenderer` draws the active ability on the client, fed by `AbilitySyncPayload`
(species, `type1`, `type2`). The server sends one on every change: a new auto ability, a
force, a clear, a per-player toggle, and one empty payload per player when
`deactivateAllAndSync()` runs. An empty species string is the signal to hide the HUD.

Sync is guarded by `ServerPlayNetworking.canSend`, so a vanilla client simply never
receives it. The effects still apply — only the display is missing.

## Driving it as an admin

| Action | Command | Dashboard |
|---|---|---|
| turn the subsystem on or off | `/cshift ability global enable\|disable` | `POST /api/v1/shift/ability/global` (or `PUT /api/v1/shift/config`, which does not clear the active abilities) |
| pin a player to a species | `/cshift ability <player> <pokemon>` | `POST /api/v1/shift/ability` with `species` |
| release that pin | `/cshift ability <player>` | `POST /api/v1/shift/ability` without `species` |
| opt one player out | `/cshift ability toggle <player>` | `POST /api/v1/shift/ability/toggle` |
| see what is active | `/cshift list` | `GET /api/v1/shift/abilities` |

`forceAbility` does not validate the species: an unknown name stores `normal` as the type
and the player gets nothing. `/cshift list` marks forced entries `[FORCED]` but prints only
the species and type, never the player name, which makes auditing forces harder than it
should be.

---

← [Transforms](transforms.md) · [NPC transforms](npc-transforms.md) →
