[← CobbleShift](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](transforms.fr.md)

# Transforms

A transform turns a player into a Pokémon without replacing them with an entity. The
player keeps their own body, their own inventory and their own position on the server; they
are made invisible, given a few attribute modifiers, and every client is told to draw a
Pokémon model at their coordinates instead. The server never spawns a Pokémon.

That choice is what makes the subsystem cheap and what makes it fragile. Cheap, because
nothing has to be mounted, synchronised or cleaned up. Fragile, because a vanilla client
receives the invisibility and not the model, and sees nothing at all.

---

## What the player gains

Everything below is applied by `TransformManager.transform()`, in this order.

| Effect | Source | Config gate |
|---|---|---|
| Invisibility, duration `Integer.MAX_VALUE`, ambient, no particles | always | — |
| `GENERIC_MOVEMENT_SPEED` modifier `cobbleshift:transform_speed` | `mapSpeed(baseSpeed) − 0.1` | `transformSpeedEnabled` |
| `GENERIC_MAX_HEALTH` modifier `cobbleshift:transform_health` | `mapMaxHealth(baseHP) − 20` | `transformHealthEnabled` |
| `GENERIC_SCALE` modifier `cobbleshift:transform_scale` | `mapScale(actualHeight) − 1.0` | `transformScaleEnabled` |
| the Pokémon model, on every client | `TransformSyncPayload` | — |
| end-rod and happy-villager particles, server side | always | — |
| the species cry, within 32 blocks, same dimension | client, from the payload | — |
| a type projectile attack bound to left click | `AttackInputMixin` | — |
| forced third-person camera, scroll zoom, sleep pose, cry keybind | client mixins | — |

All three modifiers are **persistent**, meaning they are written to the player's saved
attribute data. That is deliberate — it is how a transform survives a disconnect — and it
is also why a crash leaves them behind. See
[disconnects, restarts and crashes](#disconnects-restarts-and-crashes).

Health is topped up to the new maximum immediately after the modifier is applied, so a
transform into a high-HP species is a full heal. Reverting clamps health back down if the
player is now above their real maximum, so it is not an exploit in the other direction.

## What the player loses

| Lost | Why |
|---|---|
| first-person view | the client forces `THIRD_PERSON_BACK` on transform and re-forces it every tick, so F5 does nothing |
| the vanilla crosshair | third person hides it; the client draws its own cross instead |
| their held item and arms on screen | `TransformArmMixin` cancels `renderRightArm` and `renderLeftArm` |
| their nametag | `PlayerNametagMixin` cancels `renderLabelIfPresent` for transformed players and NPCs |
| footstep sounds | `TransformStepSoundMixin` cancels `playStepSound` |
| sprint FOV change | `TransformFovMixin` pins the FOV multiplier |
| normal left-click attacks | `AttackInputMixin` intercepts `doAttack` and fires a projectile instead |
| their own skin, to everyone | the model is swapped on every client that has the mod |

Nothing about blocks, inventory or interaction changes. A transformed player can still
mine, place, open chests and type in chat. The transform is cosmetic plus three attributes
plus one new attack.

## How base stats become Minecraft values

Five formulas in `SpeciesMapper`, all linear and all reading Cobblemon base stats.

| Formula | Code | Range |
|---|---|---|
| movement speed | `0.1 + (baseSpeed − 80) / 800` | roughly 0.04 to 0.22 |
| max health | `10 + baseHP / 15` | roughly 10 to 27 |
| scale | `clamp(effectiveHitboxHeight / 1.8, 0.15, 2.5)` | 0.15 to 2.5 |
| attack damage | `min(12, 2 + max(atk, spAtk) / 25)` | 2 to 12 |
| attack cooldown | `max(25, 40 − speed / 12)` ticks | 25 to 40 |

`mapSpeed` is subtracted by 0.1 before becoming a modifier because the vanilla base is
0.1 and the modifier is `ADD_VALUE`; the same trick applies to health against 20 and scale
against 1.0. A species with base speed 80 therefore gets a modifier of exactly zero and
moves at a normal player's pace.

Scale is the one with real work behind it. The mod wants the player's collision box to
match the gray F3+B hitbox a real Pokémon of that species would have, so `resolveActualHeight`
builds a throwaway `PokemonEntity` by reflection, reads `getHeight()`, and discards it
without ever adding it to the world. When that fails it falls back to
`hitbox.height × baseScale` read off the species object. Both paths then divide by 1.8, the
vanilla player height. The clamp at 2.5 means Wailord and Steelix are large but not
absurd — and it also means the dashboard's `mappedScale` field, which passes the Pokédex
height in decimetres instead, reports 2.5 for nearly everything. That is a bug in the API,
not in the transform.

Run `/cshift species <name>` to see every derived value for a species before you use it.

## Attacks

Left click while transformed sends `AttackAbilityPayload` instead of swinging. The client
includes one bit: whether its ghost model has an attack animation ready.

`AttackHandler.handleAttack()` then:

1. Returns silently if the player is not transformed, if `attacksEnabled` is false, or if
   the per-transform cooldown is still running.
2. Computes damage and cooldown from the species, scaled by `attackDamageMultiplier` and
   `attackCooldownMultiplier`. Cooldown has a floor of 5 ticks regardless of multiplier.
3. Sets the cooldown **at click time**, before any projectile exists, so animation delay
   never changes damage per second.
4. With an animation, queues the shot for 26 ticks — 1.3 seconds — and reads the aim
   direction at spawn time, so the player can re-aim during the wind-up. Without an
   animation, it fires at once.

Projectile count comes from base stat total:

| BST | Shots | Damage per shot |
|---|---|---|
| under 350 | 1 | full |
| 350 to 449 | 1 | ×1.3 |
| 450 and over | 3, in a 15° cone | ×0.7 centre, ×0.5 each flank |

⚠️ `mapProjectileCount()` returns only 1 or 3 and is used for display in `/cshift species`
and in the API, while `spawnProjectiles()` has the three-tier logic above. The 350-449
boosted tier is therefore invisible in both readouts: a BST-400 species reports
`1 projectile(s)` without mentioning the 1.3 multiplier.

`TypeProjectileEntity` is a `ThrownEntity` with gravity 0.01, launched at speed 1.5, so it
flies nearly flat. It is registered as `cobbleshift:type_projectile` and rendered as a
flying item chosen per type: a fire charge for Fire, a feather for Flying, a cobblestone
for Rock, and so on. It ignores its owner, applies its damage as thrown damage, applies one
type-specific status effect, and discards itself on any collision.

| Type | On-hit effect |
|---|---|
| `fire` | 3 seconds on fire |
| `ice` | Slowness II, 3 s |
| `ground` | Slowness II, 3 s |
| `grass`, `water` | Slowness I, 2 s |
| `poison` | Poison I, 5 s |
| `electric`, `fairy` | Glowing, 5 s / 3 s |
| `psychic` | Levitation, 1.5 s |
| `flying` | Levitation, 1 s |
| `ghost` | Blindness, 3 s |
| `dark` | Darkness, 3 s |
| `dragon` | Weakness, 5 s |
| `fighting` | Weakness, 3 s |
| `rock` | Mining Fatigue, 3 s |
| `steel` | Mining Fatigue, 2 s |
| `bug` | Hunger, 4 s |
| `normal` | none |

Only the **primary** type is used, both for the projectile and for its effect. A
Charizard's Flying half never appears in its attacks.

Attacks are disabled per transform with the `noattacks` token on `/cshift transform`.
There is no way to re-enable them on a live transform short of transforming again.

## What the player can do while transformed

Two client keybinds, both **unbound by default** — they appear in the Controls screen under
the `CobbleShift` category and do nothing until the player assigns a key.

| Keybind | Translation key | Effect |
|---|---|---|
| Toggle Sleep Pose | `key.cobbleshift.sleep` | puts the ghost model in its sleeping animation; shows `Zzz...` on the action bar |
| Play Cry | `key.cobbleshift.cry` | plays the species cry |

Mouse scroll adjusts camera distance through `TransformScrollMixin`. At zoom 0 the local
player's model is hidden, which is the closest thing to a first-person view available.

A player cannot revert themselves. `TransformData` carries a `locked` flag with a getter, a
setter and a JSON field, and **nothing in the mod ever sets it** — there is no self-revert
to lock. The field is dead weight left over from a design that was not built.

## Duration, cooldown, and what ends a transform

| Ends a transform | Condition |
|---|---|
| `/cshift revert <player>` | any time |
| `/cshift revertall` | any time |
| `POST /api/v1/shift/revert` or `/revertall` | OPERATOR role |
| CheeseGuard's `REVERT_ALL` effect | during an event |
| CheeseKart session end | if the racer was transformed by the kart |
| max duration expiry | `transformMaxDurationEnabled` is on and `transformMaxDurationSeconds` elapsed |
| server stop | `SERVER_STOPPING` calls `revertAll()` |
| transforming again | the old transform is reverted first, without the revert sound |

Max duration is **off** by default. When on, `TransformManager.tick()` checks elapsed time
against `transformMaxDurationSeconds × 1000` every server tick and reverts the player with
`⏰ Max transformation duration reached!`. The check runs on wall-clock milliseconds, not on
ticks, so a laggy server still expires transforms on schedule.

The cooldown is also off by default. When on, a player who was reverted cannot be
transformed again for `transformCooldownSeconds`. Two things about it are easy to miss:

- It is measured from the **revert**, not from the transform, and is stored per UUID in
  memory only.
- Switching species while already transformed bypasses it entirely — the check is skipped
  when the player has an active transform. So the cooldown limits how often a player can
  *return* to Pokémon form, not how often they can change species.

When the cooldown blocks a transform, the refusal message goes to the **target player** on
their action bar. The admin who typed the command sees nothing, and an API caller gets the
generic "species not found" error.

⚠️ `autoRevertOnDeath` defaults to `true` and **does nothing**. `TransformManager.onPlayerDeath()`
implements it correctly, but no caller exists anywhere in the mod: no death mixin, no
`ServerLivingEntityEvents.AFTER_DEATH` registration, nothing. A transformed player who dies
stays transformed through the respawn. Wiring it up means registering that event in
`CobbleShiftMod.onInitialize()`.

## Disconnects, restarts and crashes

A disconnect is the easy case. `onPlayerDisconnect` deliberately does nothing: the entry
stays in `activeTransforms`, and `onPlayerJoin` re-applies the Invisibility and re-broadcasts
every active transform to every client, with `silent=true` so nobody hears a cry on a rejoin.
The attribute modifiers were persistent and were never removed, so they are already in
place. A player logs back in still transformed.

A clean restart is also handled: `SERVER_STOPPING` reverts everyone, which strips the
modifiers before they are saved.

A **crash** is the problem case. `activeTransforms` is in-memory only and is never written
to disk, so the server comes back with no record of who was transformed — but the three
persistent modifiers and the infinite Invisibility are in the player files. The player
would log in invisible, scaled, and without a model.

`cleanupStaleTransform()` catches exactly that. On any join with no active transform, it
removes any `cobbleshift:transform_*` modifier it finds and removes Invisibility **only if
that instance is ambient with particles off**, which is the mod's own signature. A beacon
or a potion-given invisibility does not match that signature closely enough to be a real
concern in practice, though a beacon-granted ambient effect is the one case where the test
could misfire. The method logs `Cleaned up stale transform modifiers for <name>` when it
does anything, which is the line to grep for after a crash.

The practical consequence: a crash silently ends every transform. Player transforms are not
persistent by design. NPC transforms are — that difference is the whole point of
[NPC transforms](npc-transforms.md).

## Who else transforms players

Three callers outside the command tree, all documented in
[architecture](architecture.md#the-de-facto-external-api):

- **CheeseDash** — `POST /api/v1/shift/transform`, species only, no shiny and no form.
- **CheeseGuard** — the `TRANSFORM_ALL`, `TRANSFORM_RANDOM`, `TRANSFORM_BY_TEAM` and
  `REVERT_ALL` event effects.
- **CheeseKart** — transforms a racer on session start, reverts on session end.

The last two reach in by reflection on `getTransformManager().transform(player, species)`
and `revert(player)`. Those two signatures are a public API whether or not they were meant
to be.

---

← [Configuration](configuration.md) · [Passive abilities](abilities.md) →
