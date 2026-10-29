[← CobbleShift](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

CobbleShift reads one global config file:

```text
config/cobbleshift.json
```

The path comes from `FabricLoader.getInstance().getConfigDir().resolve("cobbleshift.json")`
in `CobbleShiftConfig`, so it follows a relocated config directory. The file is created
with every key at its default on the first start, if it does not exist yet. The client
never reads the server's file. `CobbleShiftMod` is a `main` entrypoint, though, so it
also runs on every client that has the mod and creates a `cobbleshift.json` in that
client's own `config/` folder; that copy is used only by a single-player world.

A second file, `config/cobbleshift_npc_transforms.json`, is **not** configuration: it is
the saved state of the NPC transforms, rewritten by the mod every time an NPC changes and
on every server stop. Its
format is in [NPC transforms](npc-transforms.md#the-save-file). Do not edit it by hand
while the server runs.

---

## How the file is loaded and saved

The config is read **once**, in `CobbleShiftMod.onInitialize()`, before the server
starts. There is no reload command. A hand edit to the file takes effect on the next
restart and not before.

The file is written back in four situations, and each one serialises the **in-memory**
values, not the file:

| Writer | Keys it changes |
|---|---|
| first start, file absent | all sixteen, at their defaults |
| `/cshift ability global enable\|disable` | `abilityGlobalEnabled` |
| `POST /api/v1/shift/ability/global` | `abilityGlobalEnabled` |
| `PUT /api/v1/shift/config` | any of the fourteen keys it accepts |

⚠️ The consequence is easy to get bitten by: if you edit `cobbleshift.json` while the
server runs and someone then toggles abilities from the command or the dashboard, your
edit is overwritten by the values the server loaded at startup. Edit the file with the
server stopped, or change values through `PUT /config`.

Loading is key by key. A key absent from the file keeps its default, which is how an
old file picks up keys added later. A key present with the wrong type (a string where a
number is expected) throws, and the `catch` logs:

```text
[CobbleShift] Failed to load config, using defaults: <message>
```

That message overstates what happened. Keys read **before** the bad one keep their file
values, the bad key and every key after it keep their defaults. The order is the one in
the table below. The broken file is not rewritten at that point, but the next save of
any kind replaces it with the in-memory values.

The parser is Gson's `JsonParser.parseString`, which runs in lenient mode and tolerates
`//` comments. That is what makes the annotated example at the bottom of this page
loadable as it stands. The comments do not survive the next save.

## The keys

All sixteen keys live at the top level of one JSON object. The table follows the order of
`CobbleShiftConfig.load()`.

### Transforms

| Key | Type | Default | What it does |
|---|---|---|---|
| `autoRevertOnDeath` | boolean | `true` | **nothing**, see [keys that do nothing](#keys-that-do-nothing) |
| `transformScaleEnabled` | boolean | `true` | applies the `GENERIC_SCALE` modifier on transform, so the player's hitbox matches the species |
| `transformSpeedEnabled` | boolean | `true` | applies the `GENERIC_MOVEMENT_SPEED` modifier derived from base Speed |
| `transformHealthEnabled` | boolean | `true` | applies the `GENERIC_MAX_HEALTH` modifier derived from base HP, then heals to the new maximum |
| `transformMaxDurationEnabled` | boolean | `false` | reverts a transform automatically once it reaches `transformMaxDurationSeconds` |
| `transformMaxDurationSeconds` | int | `300` | the duration limit, in seconds of wall-clock time |
| `transformCooldownEnabled` | boolean | `false` | blocks a new transform for `transformCooldownSeconds` after a revert |
| `transformCooldownSeconds` | int | `60` | the cooldown, measured from the revert |

The three `transform*Enabled` switches are read at the moment of a transform. Turning one
off does not strip the modifier from a player who is already transformed; it stops the
next transform from applying it. A revert removes the three modifiers whatever the
switches say.

Two interactions with the tuning commands are worth knowing:

- With `transformScaleEnabled` off, `/cshift scale` still succeeds and still pushes the new
  scale to clients, so the **model** grows, but `setPlayerScale` skips the attribute and
  the **hitbox** does not move.
- With `transformSpeedEnabled` off, `/cshift speed` prints its success line and changes
  nothing: `setPlayerSpeed` stores the value and skips the modifier.

The duration and cooldown are both measured with `System.currentTimeMillis()`, not in
ticks, so a lagging server does not stretch them. Both are documented from the player's
side in [the transform model](transforms.md#duration-cooldown-and-what-ends-a-transform).

### Attacks

| Key | Type | Default | What it does |
|---|---|---|---|
| `attackDamageMultiplier` | float | `1.0` | multiplies the per-species projectile damage |
| `attackCooldownMultiplier` | float | `1.0` | multiplies the per-species attack cooldown, before the 5-tick floor |

`AttackHandler` reads both on every click, so a change through `PUT /config` applies to the
very next attack. A cooldown multiplier below `1.0` makes attacks faster on the server; the
hard floor of 5 ticks (a quarter of a second) applies after the multiplication, whatever
the value.

⚠️ The client does not know the server's cooldown. `AttackInputMixin` refuses any click
while the HUD cooldown bar is filling, and `AbilityHudRenderer.triggerAttackCooldown()`
always fills that bar over a fixed **30 ticks**. Two consequences:

- A real client can never attack faster than once every 1.5 seconds. Lowering
  `attackCooldownMultiplier` until the server cooldown drops under 30 ticks changes
  nothing a player can feel.
- When the server cooldown is longer than 30 ticks (base cooldowns already range from 25
  to 40, and a multiplier above `1.0` stretches them), the bar shows "ready" too early.
  The next click is sent, ignored by the server, and restarts the 30-tick bar. The player
  sees a wasted attack.

There is no config key to disable attacks globally. Attacks are disabled per transform
with the `noattacks` token on `/cshift transform`.

### Passive abilities

| Key | Type | Default | What it does |
|---|---|---|---|
| `abilityAutoMode` | boolean | `true` | the ability follows the first Pokémon of the player's party |
| `abilityGlobalEnabled` | boolean | `false` | master switch for the whole ability subsystem |
| `abilityTickInterval` | int | `100` | how often, in server ticks, abilities are re-evaluated and effects re-applied |
| `abilityEffectDuration` | int | `160` | the duration, in ticks, of each effect granted at a refresh |

`abilityGlobalEnabled` is off by default: a fresh install grants no passive effect to
anyone until an admin turns it on.

With `abilityAutoMode` off, the refresh loop no longer reads parties. Only abilities
forced with `/cshift ability <player> <pokemon>` keep being refreshed, and when no
ability is active at all the loop exits early. An automatic ability that was already
active when you switched the mode off stays in place and keeps being refreshed, until
abilities are switched off globally, the player is toggled out, a force replaces it, or
the server restarts.

`abilityTickInterval` and `abilityEffectDuration` only make sense together. Each refresh
grants effects lasting `abilityEffectDuration` ticks, and the next refresh comes
`abilityTickInterval` ticks later. The duration must stay **above** the interval, or
every buff lapses for `interval − duration` ticks between two refreshes. The defaults keep
a 60-tick margin.

One vanilla behaviour to account for: the client makes Night Vision flicker during its
last 200 ticks. The default duration of 160 ticks sits entirely inside that window, so the
`dark` type's Night Vision flickers constantly. Raise `abilityEffectDuration` above 200,
keeping it above the interval, if you rely on it. This is an observation of vanilla
rendering, not something CobbleShift controls.

Each effect is described in [passive abilities](abilities.md).

### Hot Potato

| Key | Type | Default | What it does |
|---|---|---|---|
| `hotPotatoEnabled` | boolean | `true` | **nothing** |
| `hotPotatoPassRange` | double | `3.0` | **nothing** |

Both are explained in [keys that do nothing](#keys-that-do-nothing).

## Ranges and clamps

Every numeric key has a setter that clamps its value. **The clamps only run through the
setters**, and the only caller of the numeric setters is `PUT /api/v1/shift/config`.
`load()` assigns the raw file value directly, so a hand-edited file bypasses every bound
below.

| Key | Clamp in the setter | What an out-of-range file value does |
|---|---|---|
| `abilityTickInterval` | `>= 20` | `0` throws an `ArithmeticException` (`tickCounter % 0`) in the server tick handler on the first tick; nothing catches it, so the server stops with a crash report. A negative value behaves like its absolute value |
| `abilityEffectDuration` | `>= 40` | `0` or less grants effects that expire at once: abilities appear to do nothing |
| `attackDamageMultiplier` | `[0.1, 5.0]` | `0` makes projectiles harmless; their status effect still applies |
| `attackCooldownMultiplier` | `[0.1, 5.0]` | `0` or less lands on the 5-tick floor |
| `transformMaxDurationSeconds` | `>= 10` | `0` or less reverts every transform on the tick after it starts, when the limit is enabled |
| `transformCooldownSeconds` | `>= 5` | `0` or less disables the cooldown in practice |
| `hotPotatoPassRange` | `>= 1.0` | irrelevant, the key is never read |

⚠️ Never put `0` in `abilityTickInterval`. The loop runs `tickCounter % interval` at the top
of `AbilityManager.tick()`, before the global switch is checked, so the exception fires
even with abilities disabled.

The same bounds apply when the dashboard sends a value: `PUT /config` with
`"abilityTickInterval": 5` stores `20`, and the response does not tell you. Read the
config back with `GET /config` to see what was actually kept.

The `/cshift scale`, `/cshift speed`, `scalenpc` and `speednpc` values are not config keys.
Their own `[0.1, 10.0]` range is enforced by Brigadier and again in the managers; see
[commands](commands.md#cshift-scale-and-cshift-speed).

## Keys that do nothing

Three of the sixteen keys are loaded, saved, and returned by `GET /config`, and change
nothing in the game.

| Key | Why it has no effect |
|---|---|
| `autoRevertOnDeath` | the only reader is `TransformManager.onPlayerDeath()`, and nothing calls that method: no death event is registered, no mixin hooks death. A transformed player who dies stays transformed |
| `hotPotatoEnabled` | no code reads it. There is no Hot Potato game in this mod |
| `hotPotatoPassRange` | same. Its setter exists and is never called |

`autoRevertOnDeath` is the misleading one, because its name and its default (`true`)
promise a behaviour, and the dashboard lets you toggle it. Making it work means
registering a death event in `CobbleShiftMod.onInitialize()` that calls
`onPlayerDeath()`; until then, revert dead players by hand or from the event that killed
them.

The two Hot Potato keys are leftovers from a minigame that is described in older
documentation and is not in the source. `PUT /config` does not even accept them: the
handler has no branch for either key, so sending them is silently ignored. They stay in
the file only because `toJson()` still writes them.

## Example file

The complete file, with every key at its default and a comment on each line. It loads as
is, thanks to the lenient parser, and the server writes it back without comments on its
next save.

```json
{
  // Does nothing: no death hook calls the code that reads it.
  "autoRevertOnDeath": true,
  // Passive ability follows the first slot of the party. Off = only forced abilities refresh.
  "abilityAutoMode": true,
  // Master switch for passive abilities. Off on a fresh install.
  "abilityGlobalEnabled": false,
  // Ticks between two ability refreshes. Never 0. The dashboard clamps to 20 minimum.
  "abilityTickInterval": 100,
  // Ticks each refreshed effect lasts. Keep it above abilityTickInterval, above 200 for Night Vision.
  "abilityEffectDuration": 160,
  // Does nothing: no Hot Potato game exists in the code.
  "hotPotatoEnabled": true,
  // Does nothing, same reason.
  "hotPotatoPassRange": 3.0,
  // Resize the player's hitbox to the species' height (0.15x to 2.5x).
  "transformScaleEnabled": true,
  // Derive movement speed from base Speed.
  "transformSpeedEnabled": true,
  // Derive max health from base HP.
  "transformHealthEnabled": true,
  // Projectile damage multiplier. The dashboard clamps to [0.1, 5.0].
  "attackDamageMultiplier": 1.0,
  // Attack cooldown multiplier. Lower = faster. Floor of 5 ticks after multiplication.
  "attackCooldownMultiplier": 1.0,
  // Revert transforms automatically after transformMaxDurationSeconds.
  "transformMaxDurationEnabled": false,
  // Wall-clock seconds. The dashboard clamps to 10 minimum.
  "transformMaxDurationSeconds": 300,
  // Block re-transforming for a while after a revert. Switching species skips the check.
  "transformCooldownEnabled": false,
  // Seconds, counted from the revert. The dashboard clamps to 5 minimum.
  "transformCooldownSeconds": 60
}
```

A server running timed events, where a transform should end on its own and players
should not bounce straight back into Pokémon form, would change four values:

```json
{
  "transformMaxDurationEnabled": true,
  "transformMaxDurationSeconds": 600,
  "transformCooldownEnabled": true,
  "transformCooldownSeconds": 120
}
```

Send that object as the body of `PUT /api/v1/shift/config` with a `SUPER_ADMIN` session
and it applies at once, clamps included, and is saved to disk. Written into the file by
hand, the omitted keys keep their defaults and the change waits for a restart.

---

← [Commands](commands.md) · [Transforms](transforms.md) →
