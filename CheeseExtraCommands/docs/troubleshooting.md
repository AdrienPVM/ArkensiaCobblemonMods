[← CheeseExtraCommands](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

Symptom, cause, fix. The mod fails quietly by design, so most of this page is about
telling three similar-looking silences apart.

---

## The mod does not load at all

**Cause.** One of the hard dependencies is missing or the wrong version. The declared
requirements are Fabric Loader ≥ 0.15.0, Fabric API, and Minecraft `~1.21`. A second copy
of the jar in `mods/` produces the same result: Fabric refuses to start when two files
declare the mod id `cheeseextra`.

**Fix.** Read the first screen of `logs/latest.log`. Fabric names the offending
dependency outright, with the version it wanted and the version it found. For a duplicate,
`ls mods/ | grep cheeseextra` should return exactly one line.

The mod needs no sibling mod from this repository, so a missing CheeseDash or CheeseGuard
is never the cause here.

## Fabric warns about a missing icon

**Cause.** `fabric.mod.json` declares `"icon": "assets/cheeseextra/icon.png"` and that
file does not exist in the jar. There is no `assets/` directory in `src/main/resources/`
at all.

**Fix.** Cosmetic only. The mod loads and runs; Mod Menu shows a placeholder icon. Add a
PNG at `src/main/resources/assets/cheeseextra/icon.png` and rebuild, or delete the `icon`
line from `fabric.mod.json`.

## The command `/cheesecheck` does not exist

**Cause.** Either the mod did not load, or you are below permission level 2. Brigadier
hides commands whose `requires` predicate fails, so an unprivileged player gets the
vanilla *Unknown or incomplete command* error rather than a permission message.

**Fix.** Check for the third startup line:

```text
[CheeseExtra] /cheesecheck command registered (ev, iv, pokemon, ballvariety)
```

If it is present, the tree exists and the problem is your permission level. `/op <name>`,
or run the command from the console or a command block, which carry level 4 and level 2
respectively. [More on who can call it](commands.md#permission-level).

If only the first two lines appear, the server never reached command registration. The
cause is elsewhere in the startup sequence; look for an exception between the two.

## Every check says Cobblemon non disponible

**Cause.** `Class.forName("com.cobblemon.mod.common.Cobblemon")` failed, so Cobblemon is
not on the classpath. It is a `suggests` dependency, so Fabric never complains about its
absence; the mod loads happily without it.

**Fix.** Install Cobblemon for Fabric 1.21.1. The mod was built against 1.6.1. The check
is purely a class-name probe: if a future Cobblemon moved or renamed that class, the probe
would fail even with Cobblemon installed, and the symptom would be identical. If
Cobblemon is clearly present in `mods/` and loaded in the log, that renaming is the thing
to suspect, and `CobblemonReflect.isAvailable` is the single line to change.

## A check that should pass returns zero

Three distinct causes produce the same red message and the same `0`.

**Cause 1: the Pokémon is in a box.** `getPartyPokemon` reads the party store only. A
Pokémon in the PC is invisible to every subcommand.

**Fix.** Have the player move it into the party.

**Cause 2: the criteria are not what you think.** `ev` has no stat selector, so
`minEV 252 statCount 1` passes on *any* single maxed stat, not on Speed. `iv` takes its
count before its threshold, the reverse of `ev`. For `pokemon`, the species must be
written as a Showdown id, lowercase and unpunctuated, and an id that does not exist fails
exactly like an absent Pokémon. Re-read [the argument
tables](commands.md#cheesecheck-ev).

**Fix.** Run the check by hand with `/execute store result` and the real player, then
adjust the numbers until it behaves:

```mcfunction
/scoreboard objectives add check_dbg dummy
/execute store result score Steve check_dbg run cheesecheck iv Steve 5 31 quest_iv 4
/scoreboard players get Steve check_dbg
```

**Cause 3: the reflection bridge broke.** A Cobblemon update renamed something
`CobblemonReflect` scans for. The commands degrade to "no match" rather than throwing, so
this looks like a legitimate failure.

**Fix.** This is the one case the log settles. A working check prints the party size and
the stat values:

```text
[CheeseExtra] Got party object: PlayerPartyStore
[CheeseExtra] Party for Steve: 6 pokemon found
[CheeseExtra] IVs values: HP=31 ATK=31 DEF=31 SPA=31 SPD=12 SPE=31
```

A broken bridge prints a warning instead, and which warning tells you where it broke:

| Log line | What failed |
|---|---|
| `Could not get PokemonStoreManager from Cobblemon.INSTANCE` | the storage lookup. Cobblemon's own API surface changed |
| `Could not get party for player <name>` | storage was found, the party accessor was not |
| `Party for <name>: 0 pokemon found` while the party is not empty | the party object was found but could not be iterated |
| `Could not get EVs from pokemon object (class: ...)` | the stat store accessor changed |
| `Stats enum has N constants, expected >= 6` | the `Stats` enum moved or shrank |

Each maps to one scan in [the reflection layer](architecture.md#the-reflection-layer).
Fixing it means adjusting that scan's discriminator and rebuilding.

## Nothing appears in chat when I run a check

**Cause.** Expected behaviour. Every message goes to the **player named in the command**,
not to the caller. An admin checking someone else, a command block, or the console sees
nothing.

**Fix.** Target yourself to test, or read the server log, which records every successful
write:

```text
[CheeseExtra] Set mira_progress = 2 for Steve
```

## The score is written but the quest does not advance

**Cause.** The check wrote to a different objective than the one your NPC reads. Because
`setScore` creates a missing objective instead of refusing, a single typo produces a
working command, a green success message, and an objective nobody reads. The `scoreboard`
argument has no tab completion to catch the typo.

**Fix.** Grep the log:

```bash
grep "not found — creating it" logs/latest.log
```

Every line names an objective that did not exist when a check wrote to it. On a server
whose objectives are all pre-created, that line should never appear. Compare each name
against `/scoreboard objectives list`.

A second possibility: the objective is right but the **value** is not what the dialogue
branches on. `/cheesecheck` sets the score, it does not increment it, so a dialogue
expecting `progress + 1` will not see it. Check the `value` argument against the number
the next dialogue state tests for.

## A player lost all their quest progress

**Cause.** They changed their Minecraft name. Scores are written against
`ScoreHolder.fromName(player.getName().getString())`, so a rename starts from a blank
score on every objective this mod wrote.

**Fix.** Restore it manually:

```mcfunction
/scoreboard players set NewName mira_progress 3
```

There is no migration path. [Why](architecture.md#known-weaknesses).

## A quest step is impossible to complete

**Cause.** Most often a species id that does not exist. `/cheesecheck pokemon` validates
nothing: an unknown id never matches, and the player is told they do not own the
Pokémon. Nothing is logged.

Second most often, a `ballvariety` count above `6`. The range accepts up to `20`, a party
holds six Pokémon, so `7` and above can never pass.

**Fix.** Test the step yourself with a party you know qualifies. If `pokemon` still fails,
the id is wrong: check the Showdown spelling (`mrmime`, `nidoranf`, `tapukoko`) rather
than the display name.

## The island-tour HUD never appears

**Cause.** One of five, in order of likelihood.

1. The client does not have the mod. The HUD is drawn client-side; the server cannot
   force it.
2. `jenny_intro` is not exactly `1`. The condition is `>= 1 && < 2`, so `0` and `2` both
   hide the panel.
3. `spawn_tour` is `26` or more. The gate closes at `TOTAL`, and a double-incremented
   counter overshoots.
4. All 26 `<key>_met` objectives read `1`, so the remaining list is empty and the renderer
   returns early even with `show = true`.
5. The objectives do not exist. The tracker never creates them, and a missing objective
   reads as `0`.

**Fix.** Check the state directly:

```mcfunction
/scoreboard players get Steve jenny_intro
/scoreboard players get Steve spawn_tour
```

Then put the player into the visible state by hand:

```mcfunction
/scoreboard players set Steve jenny_intro 1
/scoreboard players set Steve spawn_tour 0
```

The panel should appear within a second, since the tracker evaluates every 20 ticks. If it
does not and the client has the jar, enable DEBUG logging on the server and look for
`Failed to send IslandTourPayload`. Full model in [the island tour
tracker](island-tour.md#the-scoreboard-state-machine).

## The HUD shows names that overlap each other

**Cause.** `COL_WIDTH` is a flat `70` scaled pixels and the renderer does not measure
names. A display name wider than that runs into the next column.

**Fix.** Shorten the display names in `IslandTourTracker.NPCS`, or raise `COL_WIDTH` in
`IslandTourHud`, and rebuild. Both are compile-time constants;
[the constants table](configuration.md#constants-compiled-into-the-jar) lists them.

## The log is drowning in CheeseExtra lines

**Cause.** `CobblemonReflect` logs at INFO, not DEBUG. One `/cheesecheck iv` on a full
party writes two party lines, six stat lines and one score line.

**Fix.** There is no runtime switch. Either silence the logger in your log4j
configuration:

```xml
<Logger name="cheeseextra" level="WARN"/>
```

or change the `LOGGER.info` calls in `CobblemonReflect.getPartyPokemon` and
`getStatStore` to `LOGGER.debug` and rebuild. The second is the better fix and the mod
should ship that way.

Silencing the logger also hides the diagnostics you need for
[a broken bridge](#a-check-that-should-pass-returns-zero). `WARN` keeps the warnings and
drops the noise, which is the right compromise.

## The module does not appear in the CheeseDash dashboard

**Cause.** It never will. This mod registers no `cheesedash` entrypoint, no
`DashboardModule`, and no REST routes. `fabric.mod.json` declares only the `main` and
`client` entrypoints.

**Fix.** Nothing to fix. There is no dashboard page for this mod, by design, and no
`dashboard.md` in this documentation set for the same reason.

## Writing a useful bug report

Open an issue at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) with:

1. **The three startup lines**, or whichever of them appeared. They establish that the mod
   loaded and that the command tree was built.
2. **The exact command**, copied verbatim, with the real player name and the real
   objective name. Argument order is the single most common fault and only the verbatim
   line reveals it.
3. **Every `[CheeseExtra]` line from the attempt.** Grep for it:

   ```bash
   grep "CheeseExtra" logs/latest.log | tail -40
   ```

   The `Got party object`, `Party for`, `values:` and `Set` lines together show exactly
   how far the check got.
4. **The message the targeted player saw**, in French, copied as-is. It identifies which
   branch of which handler ran.
5. **Your Cobblemon version.** The reflection layer is pinned to nothing, but it was
   written against 1.6.1 and every scan is an assumption about that version's class names.
6. **Whether the client has the mod**, for any HUD report.

A stack trace, if there is one, comes from a `LOGGER.error` in `CobblemonReflect` and
names the reflective call that failed. Include all of it.

---

← [Architecture](architecture.md) · [CheeseExtraCommands](../README.md) →
