[← CobbleShift](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

One section per symptom, each with its cause and its fix. Most CobbleShift problems come
from three places: a client without the mod, a reflective lookup into Cobblemon that
stopped matching, and a config key that does not do what its name says. When a symptom is
not listed, the server log with the lines described in
[writing a useful bug report](#writing-a-useful-bug-report) is the place to start.

---

## The mod does not load

**Cause.** Fabric refuses the jar before CobbleShift runs a line of code. The usual
reasons are a Minecraft version other than `1.21.1`, a Fabric Loader older than `0.16.0`,
Java older than 21, or Fabric API missing. The crash screen or `logs/latest.log` names the
unmet requirement.

**Fix.** Match the [requirements](installation.md#requirements). If the jar loaded, the
server log contains, in this order:

```text
[CobbleShift] Initializing...
[CobbleShift] TypeProjectileEntity registered
[CobbleShift] Initialized successfully!
```

No `Initializing...` line means Fabric never got to the entrypoint. A
`Failed to load TypeProjectileEntity!` line instead of the second one means the class is
missing from the jar, usually a broken build.

## Every transform fails with "species not found"

```text
Failed: species 'pikachu' not found or Cobblemon not available
```

**Cause.** `SpeciesMapper.resolve()` returned nothing. Three cases look identical from the
command:

1. **Cobblemon is not installed.** It is only `suggests` in `fabric.mod.json`, so the mod
   loads without it and refuses every species. The startup log says
   `Cobblemon not found — using fallback stats`; ignore the second half, there is no
   fallback.
2. **The name is wrong.** The lookup tries the name lowercased, without hyphens, then
   with underscores. Anything else, such as a display name with a space, fails.
3. **Cobblemon changed its API.** `PokemonSpecies.INSTANCE`, `getByName`, `getBaseStats`
   or `getPrimaryType` no longer match. Failures inside `resolve()` are logged at
   **debug** level only, as `Failed to resolve species '<name>': <message>`.

**Fix.** Run `/cshift species pikachu`. If Cobblemon is installed and even Pikachu fails,
enable debug logging for the `cobbleshift` logger and look for that debug line: its
message names the method that was not found. That is case 3, and it needs a code fix in
`SpeciesMapper`, see [reflection](architecture.md#reflection-and-why-the-method-names-matter).

## The command seems to do nothing

`/cshift transform Steve charizard` returns without a success line and without an error.

**Cause.** `transformCooldownEnabled` is on and Steve was reverted less than
`transformCooldownSeconds` ago. The refusal goes to **Steve**, on his action bar:

```text
⛔ Cooldown: 42s before transforming again.
```

The caller sees nothing. A dashboard caller gets the generic "species not found" error
instead.

**Fix.** Wait, or transform Steve while he is still transformed: switching species skips
the cooldown. To remove the cooldown altogether, set `transformCooldownEnabled` to `false`
through `PUT /api/v1/shift/config`.

## A transformed player is invisible

Other players see nobody where Steve stands.

**Cause.** The viewer's client does not have CobbleShift. The server makes a transformed
player invisible and relies on each client's renderer to draw the Pokémon. A vanilla
client gets the Invisibility and never gets the model.

**Fix.** Install the same jar on every client. On a server that syncs mods to its clients
(AutoModPack or similar), make sure CobbleShift is in the synced set.

If the viewer **does** have the mod and still sees nothing, the ghost could not be built.
The client log should then lack the line `[CobbleShift] Ghost ready: '<species>' …` for
that species. A Cobblemon update that changed `PokemonEntity` or `PokemonProperties` is the
likely cause.

## A player is invisible and the wrong size after a crash

**Cause.** The server crashed while they were transformed. The transform list is
memory-only, so it is gone, but the three persistent attribute modifiers and the infinite
Invisibility are still in the player file.

**Fix.** Have the player reconnect with CobbleShift installed. On a join with no active
transform, `cleanupStaleTransform()` removes the modifiers and the Invisibility, and logs:

```text
[CobbleShift] Cleaned up stale transform modifiers for Steve
```

If the mod was removed in the meantime, the commands to clear it by hand are in
[uninstalling](installation.md#uninstalling).

## A transformed player who dies stays transformed

**Cause.** `autoRevertOnDeath` is `true` by default and does nothing: no death event calls
`TransformManager.onPlayerDeath()`.

**Fix.** Revert dead players yourself with `/cshift revert <player>`, or from whatever
killed them (a CheeseGuard event's `REVERT_ALL` effect, for instance). A code fix is
described in [architecture](architecture.md#extension-points).

## A config change has no effect

**Cause.** One of four things:

1. **You edited the file while the server ran.** The file is read once, at startup.
   Worse, the next `/cshift ability global …` or dashboard save writes the startup values
   back over your edit.
2. **The key does nothing.** `autoRevertOnDeath`, `hotPotatoEnabled` and
   `hotPotatoPassRange` are never read. See
   [keys that do nothing](configuration.md#keys-that-do-nothing).
3. **The key was clamped.** Through the dashboard, `abilityTickInterval: 5` becomes `20`
   without a word. See [ranges and clamps](configuration.md#ranges-and-clamps).
4. **A typo broke the load.** A wrong type throws, and the log shows
   `Failed to load config, using defaults: <message>`. Keys after the bad one were left
   at their defaults.

**Fix.** Edit the file with the server stopped, or use `PUT /api/v1/shift/config` and read
the result back with `GET /config`.

## Passive abilities do nothing

**Cause.** In order of likelihood:

1. `abilityGlobalEnabled` is `false`, which is the default.
2. The player is opted out with `/cshift ability toggle`. The opt-out lasts until a
   restart.
3. The player's lead is `poison` or `normal`, which grant no effect.
4. Cobblemon changed its party API. `getLeadPokemon()` then returns nothing, or every
   type falls back to `normal`, and abilities silently grant nothing to anyone.

**Fix.** `/cshift ability global enable`, then `/cshift list`. If `Active abilities: 0`
stays at zero with players holding Pokémon, it is case 4; the full read path is in
[passive abilities](abilities.md#reading-the-lead-pokémon).

## The Dark-type Night Vision flickers

**Cause.** The client makes Night Vision flicker during its last 200 ticks, and the default
`abilityEffectDuration` is 160. The effect never leaves that window. This is vanilla
rendering, observed rather than specified by CobbleShift.

**Fix.** Raise `abilityEffectDuration` above 200, for example `260`, keeping it above
`abilityTickInterval`.

## The server crashes on its first tick after a config edit

**Cause.** `abilityTickInterval` is `0` in `cobbleshift.json`. `AbilityManager.tick()` runs
`tickCounter % 0`, which throws an `ArithmeticException`, before even checking whether
abilities are enabled. The file value bypasses the setter's minimum of 20.

**Fix.** Put a positive value back, `100` by default, and restart.

## Attacks feel slower than the config says

**Cause.** The client refuses clicks for a fixed 30 ticks after each attack, whatever the
server cooldown is. An `attackCooldownMultiplier` below `1.0` cannot make attacks faster
than once every 1.5 seconds. With long server cooldowns, the HUD bar shows "ready" before
the server is, and the click is wasted. See
[configuration](configuration.md#attacks).

**Fix.** None from the config. Keep the multiplier at `1.0` or slightly above. A real fix
means sending the server cooldown to the client.

## `/cshift` is rejected

**Cause.** The whole tree requires permission level 2. Below that, `/cshift` is absent from
tab completion and typing it gives the vanilla *Unknown or incomplete command* error.
Some subcommands fail for other reasons:

- `transformnpc`, `revertnpc`, `scalenpc` and `speednpc` need a **player** caller, so the
  console and command blocks get the vanilla "a player is required" error.
- `/cshift ability <player> …` cannot target a player literally named `global` or
  `toggle`, because Brigadier matches the literals first.

**Fix.** `/op` the account, or give it level 2 through your permissions mod. Run the NPC
subcommands in game.

## "This entity is not an EasyNPC" while looking at an NPC

**Cause.** The 5-block raycast takes the **closest entity box** on your line of sight and
ignores blocks. Anything between you and the NPC, a Pokémon or an armor stand, is the
target. The other cause is EasyNPC missing or renamed: the check is a test on the
`easy_npc` registry namespace.

**Fix.** Step closer, with nothing in between. Check that the entity's id starts with
`easy_npc:` (F3 with the crosshair on it).

## An NPC transform disappeared after a restart

**Cause.** One of:

1. The server crashed before any save. NPC transforms are saved on every change, so this
   only loses the last change.
2. The save file sits elsewhere. Its path is relative to the working directory, not to
   the config directory.
3. One entry in the file was malformed, the load stopped there, and the next save wrote
   back only the entries read before it. The log shows
   `Failed to load NPC transforms: <message>`.

**Fix.** Check the startup log for `Loaded <n> NPC transform(s) from
config/cobbleshift_npc_transforms.json`. Keep a backup of that file before editing it.
Details in [NPC transforms](npc-transforms.md#the-save-file).

## An NPC walks fast after being reverted

**Cause.** It was reverted with `/cshift revertallnpc` while its chunk was unloaded. The
entry was dropped, but the persistent `cobbleshift:npc_speed` modifier stayed on the
entity.

**Fix.** Load the chunk and remove it with
`/attribute <npc> minecraft:generic.movement_speed modifier remove cobbleshift:npc_speed`.

## The CobbleShift module is missing from the dashboard

**Cause.** CheeseDash is not installed, failed to start, or loaded without discovering the
`cheesedash` entrypoint. While the server is starting, the module answers `503
{"error":"CobbleShift not ready"}`.

**Fix.** Check that both jars are in `mods/` and that CheeseDash's own startup lines are
present. The module registers under the `shift` prefix, so a quick test is
`GET /api/v1/shift/config` with a valid session token. The routes are in
[the dashboard API](dashboard.md).

## Dashboard changes are refused with 403

**Cause.** The session's role is too low. Most writes need `OPERATOR`. Two need
`SUPER_ADMIN`: `PUT /config` and `POST /ability/global`.

**Fix.** Log in with an account of the right role.

## Writing a useful bug report

Open an issue at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues), or ask first on
the [Arkensia Discord](https://discord.gg/fYmPwMBjdB). Include:

- the CobbleShift version (`mod_version`, for example `1.0.0+1.21.1`), the Cobblemon
  version, and whether EasyNPC and CheeseDash are installed
- whether the problem is on the server, on a client, or both, and whether every client
  has the mod
- the exact command or REST call, and what you saw
- from the **server** `logs/latest.log`, every line containing `[CobbleShift]`, starting at
  `Initializing...`. For a species problem, add the lines from a run with the
  `cobbleshift` logger at debug level
- from the **client** `logs/latest.log`, the `[CobbleShift]` lines, in particular
  `Ghost ready: …` and `Water sync: …`, for anything visual
- `config/cobbleshift.json`, and `config/cobbleshift_npc_transforms.json` for an NPC issue

Remove any webhook URL, token or address from what you paste. Those files and logs come
from a live server.

---

← [Architecture](architecture.md) · [CobbleShift](../README.md)
