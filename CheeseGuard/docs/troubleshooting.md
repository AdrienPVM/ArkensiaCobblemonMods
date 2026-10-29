[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

Symptom, cause, fix. The failures specific to this mod come at the end, and they are the
ones you are most likely to hit.

---

## The server will not start, and the log mentions a mixin

**Cause.** CheeseGuard applies 44 mixins, 40 of them server-side, and
`cheeseguard.mixins.json` sets `"required": true` with `defaultRequire: 1`. A mixin that
cannot find its target method stops the game instead of loading half-protected. Two
triggers: a Minecraft version other than 1.21.1, or a third-party mod that renamed the
class a cross-mod mixin targets.

**Fix.** Find the first `MixinApplyError` or `InvalidInjectionException` in
`logs/latest.log` and read the mixin class name out of it. If it is one of
`JadeTooltipHideMixin`, `JourneyMapMinimapHideMixin`, `CobblemonNametagHideMixin` or
`CSAFuzzyMatchFixMixin`, the third-party mod it targets has changed — roll that mod back
until CheeseGuard is updated. If it is a vanilla target, you are not on 1.21.1.

The grouped list of what each mixin touches, and which third-party mod each group depends
on, is in [architecture](architecture.md#the-44-mixins).

---

## The server stops with "Mod cheeseguard requires cheesedash"

**Cause.** CheeseDash is a **hard** dependency in `fabric.mod.json`, not an optional one.
CheeseGuard will not load without it, even if you never open the dashboard.

**Fix.** Put the CheeseDash jar in `mods/` alongside it. Full prerequisite list in
[installation](installation.md).

---

## CheeseGuard loaded but no command exists

**Cause.** Usually the mod is in the client's `mods/` only, or the jar you copied is the
`-sources` or `-dev` variant, which contains no usable code.

**Fix.** Confirm `[CheeseGuard] CheeseGuard loaded!` is in the **server** log, and that the
file you copied is `cheeseguard-1.0.0+1.21.1.jar` with no suffix. Then remember the root
command is **`/cg`**, not `/cheeseguard` — there is no long alias.

---

## A command does nothing at all, with no message

**Cause.** Almost every `/cg` handler starts with `ctx.getSource().getPlayerOrThrow()` and
returns `0` when there is no player, without sending an error. Run from the console, from
a command block or from RCON, those subcommands are silent no-ops.

**Fix.** Run them as a player, or wrap them in
`/execute as <player> at @s run cg …`. The subcommands that do work from the console are
the `state` subtree, `playerworld`, `event announce`, `event score`, `event snapshot`,
`event template`, `pasture`, `restoreinv`, `hotpotato` and the whole `/prison` tree.

---

## A player changed a zone's flags

**Cause.** On a jar built before 29 October 2026, `/cg flag`, `/cg addmember`,
`/cg removemember` and the `/cg group` subtree carried **no permission gate**: any
player could run them.

**Fix.** Update the jar — those four nodes are at level 2 now. The levels are compiled
into the Brigadier tree, so there is no config switch: on an older jar, block the nodes
with a permissions mod or keep the server whitelisted. Table of current gates in
[commands](commands.md#permission-levels).

---

## `/cg flag` accepted my value but the flag behaves backwards

**Cause.** The value goes through `Boolean.parseBoolean`. Only the exact string `true`
(case-insensitive) is true. `yes`, `1`, `allow`, `on`, and every typo become **`false`**,
with a cheerful confirmation message.

**Fix.** Use exactly `true`, `false`, or `inherit`. Check with `/cg info` — which does not
list flags, so really check from the dashboard. `inherit` is also distinct from `false`:
it drops the flag so the zone falls back to its group, then to the dimension's denied
flags, and otherwise the action is allowed.

---

## All my zones disappeared after moving the server

**Cause.** `regions.json`, `chunks.json`, `chunk_suggestions.json`, `tag_configs.json` and
`prison.json` resolve their path as `Paths.get("config", "cheeseguard")` — relative to the
**process working directory**, not to the Fabric config directory. Launch the server from
a different directory than the server root and CheeseGuard writes a fresh, empty
`config/cheeseguard/` there and starts with no zones.

**Fix.** Launch with the working directory set to the server root. Then look for a stray
`config/cheeseguard/` next to wherever your start script actually runs, and move the files
back. Detailed in [configuration](configuration.md).

---

## Zones disappeared after a crash

**Cause.** Saves are not atomic. Every manager writes straight onto the live path with
`Files.writeString`, no temporary file and no rename. A crash during a save truncates the
file, and `RegionStorage.load()` swallows the parse error and starts with zero zones
rather than refusing to boot.

**Fix.** Restore `config/cheeseguard/regions.json` from a backup. If there is none, the
zones are gone. Add `config/cheeseguard/` and `config/cheeseguard_*.json` to whatever
backs up your world — they are not inside the world directory and most backup scripts
miss them.

---

## An edit to a config file had no effect

**Cause.** Three different reasons depending on the file.

- **State files** (`regions.json`, `cheeseguard_events.json`,
  `cheeseguard_dimensions.json`, `prison.json`) are owned by the running server, which
  holds them in memory and rewrites them on the next mutation. Your edit was overwritten.
- **`cheeseguard_webhook.json`**, **`cheeseguard_pastures.json`** and
  **`cheeseguard_fish_pokemon.json`** are read once at server start — nothing reloads
  them at runtime. A **malformed** fishing table also logs
  `Failed to load fish pokemon table` and silently falls back to the built-in defaults.

**Fix.** Stop the server before editing a state file. Restart after editing a settings
file. Validate your JSON — a trailing comma is enough to make the mod ignore the file
without telling you.

---

## Discord posts nothing

**Cause.** `config/cheeseguard_webhook.json` is missing, or its `webhook_url` is empty.
A missing file produces **no log line at all** — not even a warning — so a typo in the
filename is invisible.

**Fix.** Create the file with the exact name, restart, and check for
`Failed to load webhook config` in the log, which would mean the file exists but is
malformed. The format is in [configuration](configuration.md#cheeseguard_webhookjson).

---

## The CheeseGuard section is missing from the dashboard

**Cause.** One of three things. CheeseDash did not find the entrypoint; CheeseGuard has
not finished starting; or you are looking at a cached page.

**Fix.** The entrypoint is declared as `"cheesedash": ["com.cheeseguard.dashboard.CheeseGuardDashboardModule"]`
in `fabric.mod.json`, so a jar built from this source always has it — if it is absent,
CheeseDash loaded before CheeseGuard registered, which should not happen and is worth a
bug report. A request that arrives before `onServerStart` is answered
`503 {"error":"CheeseGuard not ready"}`: wait for the server to finish booting and
reload. Confirm the module is live with
`GET /api/v1/cheeseguard/status`.

---

## An OPERATOR deleted a zone, and I wanted that to need SUPER_ADMIN

**Cause.** No route in CheeseGuard can require `SUPER_ADMIN`. `ZonesApi`, `DimensionsApi`,
`EventsApi`, `GroupsApi`, `PlayersApi` and `ChunksApi` never call `WebDashboard.requireWrite`
or `requireAdmin`; what guards them is CheeseDash's module dispatcher, which applies
`requireWrite` — `OPERATOR` or above — to every non-`GET` request to any module. The boundary
does hold, and a `READ_ONLY` session can only read, but `OPERATOR` is the ceiling as well as
the floor: deleting a zone, resetting a dimension from a snapshot and editing a flag are all
at the same level.

**Fix.** Treat `OPERATOR` on this mod as full control and hand it out accordingly; `READ_ONLY`
is the role for someone who should only look. If one specific route needs a higher bar, the
check has to be added inside the module — `if (!WebDashboard.requireAdmin(exchange)) return;`
at the top of that handler, the way `PrisonApi` does it for `requireWrite`. See
[architecture](architecture.md#known-weaknesses) and
[dashboard](dashboard.md#what-the-core-actually-enforces).

---

## `/prison set` says the duration is invalid

**Cause.** `parseDuration` accepts digits followed by `s`, `m`, `h` or `d`, concatenated:
`90s`, `30m`, `2h`, `7d`, `1h30m`. A bare number means **minutes**. Anything else returns
`-1` — a week as `2w`, a decimal as `1.5h`, a space as `2 h`, a negative sign.

**Fix.** Write `14d` rather than `2w`, `90m` rather than `1.5h`, and no spaces.

---

## A prisoner is not teleported into the prison

**Cause.** `/prison set` does **not** teleport an online target. It records the sentence,
sends the message, and leaves the tick-based confinement in `PrisonListener` to pull the
player in on a later tick.

**Fix.** Wait a few seconds. If nothing happens, the prison zone id in `prison.json` no
longer matches a zone in `regions.json` — `/prison setzone <zoneId>` again. If it still
does not, check that the zone is in a dimension the player can be teleported into.

---

## A released prisoner lands somewhere wrong

**Cause.** No release point is configured, so releases fall back to the world spawn.

**Fix.** Stand where you want them, run `/prison setout`. `/prison clearout` goes back to
the world spawn.

---

## `/cg playerworld delete` reports "Deletion failed."

**Cause.** Almost always a file lock on Windows. The dimension's world directory cannot be
removed because the handles from the just-unloaded world have not been released yet.

**Fix.** Wait and run it again. If it keeps failing, stop the server and delete the
directory by hand, then remove the matching entry from
`config/cheeseguard_dimensions.json` with the server still stopped. The per-player
directory is named after the dimension id, which has the form `<masterDimId>_<uuid8>`.

A second, rarer cause: `delete` resolves an **offline** player by looking for a dimension
whose display name *contains* the name you typed, then taking the first UUID from its
whitelist. A loose match can target the wrong world, or none. Delete while the player is
online, or do it from the dashboard.

---

## A player lost their inventory to an event

**Cause.** An event cleared it, and the restore did not run — usually because the server
crashed or the player disconnected between the clear and the restore.

**Fix.** In order:

1. Have the player run `/cg event leave`. Its second fallback applies a pending restore
   from `config/cheeseguard_pending_inv/` and is the intended recovery path.
2. If that reports *You are not participating in any event*, use
   `/cg restoreinv list <player>` to see the archives and
   `/cg restoreinv apply <player> <filename>` to put one back. The lines printed by `list`
   are clickable and fill the `apply` command for you.
3. An offline player works too — the command swaps their `playerdata` file and writes a
   safety copy first, naming it in the feedback.

Every apply is logged with the actor, the target and the archive, so the restore itself is
auditable. Full documentation in
[commands](commands-operations.md#cg-restoreinv).

---

## A zone state capture froze the server for a second

**Cause.** `/cg state capture` and `/cg event snapshot save` walk every block in the box on
the server thread. A 100×100×100 zone is a million block reads in one tick.

**Fix.** Capture small zones, or capture with nobody online. There is no asynchronous
path and no progress indication.

---

## `/cg list` shows a custom dimension as "End"

**Cause.** The world column is produced by a helper that substring-matches `overworld`,
`nether` and `end` and prints anything else raw. A dimension id containing `end` anywhere
— `arkensia:endless_cave` — is labelled *End*.

**Fix.** Cosmetic only; the zone is stored against the right world id. Read the real id
from the dashboard or from `regions.json`.

---

## `/cg removemember` or `/cg group delete` said it worked, and nothing changed

**Cause.** Both report success unconditionally. `removemember` matches members by stored
display name, case-insensitively; a player who has since renamed their account cannot be
matched. `group delete` confirms even for a group that never existed.

**Fix.** Verify with `/cg info <zoneId>` — compare the member count before and after — or
do it from the dashboard, which works on UUIDs.

---

## A `/home` command is refused for one player

**Cause.** `HomeCommandMixin` cancels any command whose text starts with `home` for a
non-operator standing in a dimension whose config has `allowHome` off, and tells them
*[CheeseGuard] The /home command is not allowed in this dimension.* The match is on the
raw string, so `/homeland` and `/homes` are caught too.

**Fix.** Enable `allowHome` on that dimension's config, or accept it. If the player is
in prison instead, `PrisonCommandMixin` is cancelling everything outside a small
whitelist, and the message is *You are in prison. This command is forbidden.*

---

## Cobblemon spawn alerts fire for the wrong Pokémon

**Cause.** That is the bug `CSAFuzzyMatchFixMixin` exists to fix, and if you are seeing it
again the mixin is no longer applying — Cobblemon Spawn Alerts changed
`AlertHandler.getConfigForPokemon` and the `@Redirect` no longer matches.

**Fix.** Search the log for that mixin name around startup. A failed `@Redirect` logs; a
matched-but-wrong one does not, so compare against version 1.9.2 of that mod, which is
what the fix was written against.

---

## Reporting a bug

The information that actually helps:

1. **`logs/latest.log`**, not a screenshot of it. The first 200 lines carry the mod list
   and every mixin apply failure; the last 200 carry the crash. Both matter.
2. **The versions**: Minecraft, Fabric Loader, Fabric API, CheeseDash, CheeseGuard, and
   the version of any third-party mod named in the log — Jade, JourneyMap, Cobblemon,
   Cobblemon Spawn Alerts in particular.
3. **The exact command or request**, copied, not paraphrased. Argument order and
   capitalisation both matter here.
4. **The relevant config file**, with the Discord webhook URL removed before you post it.
   It is a credential.
5. **Whether it reproduces on a server with only CheeseDash and CheeseGuard installed.**
   Given how much of this mod hooks other mods, that single answer saves the most time.

Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) ·
Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)

---

← [Architecture](architecture.md) · [CheeseGuard](../README.md) →
