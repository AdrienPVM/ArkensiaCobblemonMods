[← CheeseExtraCommands](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this page an NPC on your server will ask a player for a Pokémon with five
perfect IVs, refuse to advance while that Pokémon does not exist, and hand over a reward
the moment it does. The whole chain is three scoreboard objectives, two datapack
functions and one `/cheesecheck` call.

The quest giver here is called Mira, the objective holding her progress is
`mira_progress`, and the reward is a Master Ball. Rename all three freely; the structure
is what matters.

Everything below uses vanilla commands and a vanilla datapack, so you can follow it on a
bare server with nothing but this mod and Cobblemon. [Step 8](#8-drive-it-from-a-real-npc)
replaces the test command block with a real NPC.

---

## 1. Confirm the mod is live

Start the server and look for the three startup lines:

```text
[CheeseExtra] Initializing CheeseExtraCommands...
[CheeseExtra] Commands + Arkensia island-tour tracker registered.
[CheeseExtra] /cheesecheck command registered (ev, iv, pokemon, ballvariety)
```

The third line is the one that matters: it means the command tree exists. If it is
missing, stop here and read [installation](installation.md#proving-it-loaded).

## 2. Create the progress objective by hand

```mcfunction
/scoreboard objectives add mira_progress dummy "Mira"
```

Do this even though `/cheesecheck` would create the objective for you. Creating it
yourself means that if you ever misspell the name in a later command, the mod's warning
line stands out in the log instead of blending into a legitimate first write. See
[the auto-creation trap](commands.md#the-auto-creation-trap).

Confirm it:

```mcfunction
/scoreboard objectives list
```

```text
There are 1 objective(s): [Mira]
```

## 3. Give yourself a starting state

The convention this quest system uses is **odd numbers mean waiting, even numbers mean
done**. Mira's states:

| Score | Meaning |
|---|---|
| `0` | never spoken to Mira |
| `1` | quest accepted, player is breeding |
| `2` | check passed, reward owed |
| `3` | reward handed over, quest closed |

Put yourself at state `1`, as if you had already accepted the quest:

```mcfunction
/scoreboard players set @s mira_progress 1
```

```text
Set [Mira] for Steve to 1
```

## 4. Write the two dialogue branches

Create a datapack at `world/datapacks/arkensia/` with
`pack.mcmeta`:

```json
{ "pack": { "pack_format": 48, "description": "Arkensia quests" } }
```

Then `data/arkensia/function/mira/still_breeding.mcfunction`:

```mcfunction
tellraw @s {"text":"Mira: reviens me voir quand tu auras un Pokémon avec cinq IVs parfaits.","color":"gray"}
```

And `data/arkensia/function/mira/reward.mcfunction`:

```mcfunction
tellraw @s {"text":"Mira: impressionnant. Tiens, tu l'as méritée.","color":"gold"}
give @s cobblemon:master_ball 1
scoreboard players set @s mira_progress 3
```

The reward function advances the score to `3` itself. Nothing else does, which is what
stops a player from collecting twice.

Load the pack:

```mcfunction
/reload
```

```text
Reloading!
```

## 5. Write the branch selector

`data/arkensia/function/mira/talk.mcfunction` is what the NPC will call. It does two
things: run the check when the player is waiting, then branch on the resulting score.

```mcfunction
execute if score @s mira_progress matches 1 run cheesecheck iv @s 5 31 mira_progress 2
execute if score @s mira_progress matches 1 run function arkensia:mira/still_breeding
execute if score @s mira_progress matches 2 run function arkensia:mira/reward
execute if score @s mira_progress matches 3 run tellraw @s {"text":"Mira: merci encore.","color":"gray"}
```

Read the order carefully, because it is the whole trick.

Line 1 runs the check. If the player's party qualifies, the command writes `2` into
`mira_progress` on the spot. Line 2 then tests for `1` again, finds `2`, and skips the
"still breeding" line. Line 3 tests for `2`, matches, and hands over the reward.

If the check fails, it writes nothing. `mira_progress` is still `1`, line 2 fires, and
the player gets the nudge. One function, both outcomes, no conditional nesting.

`@s` works here because the function runs with the player as its executor. Mira's
dialogue button has to be set up that way; see [step 8](#8-drive-it-from-a-real-npc).

## 6. Watch it fail

Empty your party, or fill it with Pokémon that have no perfect IVs, then run:

```mcfunction
/function arkensia:mira/talk
```

Two messages, in this order:

```text
Aucun Pokémon dans ton équipe n'a 5 IVs à 31+. Continue l'élevage !
Mira: reviens me voir quand tu auras un Pokémon avec cinq IVs parfaits.
```

The first is `/cheesecheck`, in red. The second is your dialogue. Verify that nothing
moved:

```mcfunction
/scoreboard players get @s mira_progress
```

```text
Steve has 1 [Mira]
```

That `1` is the point of the whole design. A failed check leaves no trace to undo.

## 7. Watch it pass

Put a qualifying Pokémon in the party. On a test server the fastest route is Cobblemon's
own command:

```mcfunction
/pokegive @s magikarp ivs=hp_iv:31,attack_iv:31,defence_iv:31,special_attack_iv:31,speed_iv:31
```

⚠️ `pokegive` and its argument syntax belong to Cobblemon, not to this mod. The form
above matches Cobblemon 1.6.1; check `/pokegive` in game if your version differs.

Then talk to Mira again:

```mcfunction
/function arkensia:mira/talk
```

```text
Vérification réussie ! Un de tes Pokémon a 5 IVs à 31+.
Mira: impressionnant. Tiens, tu l'as méritée.
```

A Master Ball lands in your inventory, and the score has moved twice in one call:

```mcfunction
/scoreboard players get @s mira_progress
```

```text
Steve has 3 [Mira]
```

`1` → `2` written by `/cheesecheck`, then `2` → `3` written by the reward function. Run
the function once more and all you get is `Mira: merci encore.` The quest is closed.

The server log records the write:

```text
[CheeseExtra] IVs values: HP=31 ATK=31 DEF=31 SPA=31 SPD=0 SPE=31
[CheeseExtra] Set mira_progress = 2 for Steve
```

Those `IVs values` lines are printed at INFO level for every Pokémon on every check. On a
busy server that is noisy; see [known weaknesses](architecture.md#known-weaknesses).

## 8. Drive it from a real NPC

Replace `/function arkensia:mira/talk` with whatever your NPC mod calls on a dialogue
button. Two requirements, and they apply to any NPC mod:

- the command must run at **permission level 2 or above**, which an NPC acting as the
  server satisfies
- the **executor must be the clicking player**, so that `@s` resolves to them

If your NPC mod runs commands as the server with no executor, `@s` has nothing to bind
to. Pass the player explicitly instead and give up on `@s`:

```mcfunction
execute as <player> run function arkensia:mira/talk
```

Most dialogue mods substitute the clicking player's name into a placeholder for exactly
this reason. On Arkensia this was done with EasyNPC dialogue buttons, which run their
command with the clicking player as executor. That is an **observation about EasyNPC**,
not a guarantee this mod makes: verify it with your own NPC mod before building twenty
quests on top of it.

## 9. Add a second criterion

The other three subcommands drop into the same slot. A step that asks for a specific
species:

```mcfunction
execute if score @s dante_progress matches 1 run cheesecheck pokemon @s gyarados dante_progress 2
```

A step that asks for ball variety:

```mcfunction
execute if score @s suzy_progress matches 1 run cheesecheck ballvariety @s 6 suzy_progress 2
```

A step that asks for an EV-trained team, three Pokémon with two maxed stats each:

```mcfunction
execute if score @s brick_progress matches 1 run cheesecheck ev @s 252 brick_progress 2 2 3
```

That last one is the argument order to double-check: `ev` takes the threshold first, then
`scoreboard`, then `value`, and only then `statCount` and `teamCount`. `iv` takes the
count first and the threshold second. Full tables in [commands](commands.md).

## If something did not work

| What you saw | Go to |
|---|---|
| no third startup line | [installation](installation.md#proving-it-loaded) |
| `[CheeseCheck] Cobblemon non disponible.` | [troubleshooting](troubleshooting.md#every-check-says-cobblemon-non-disponible) |
| a check that should pass returns `0` | [troubleshooting](troubleshooting.md#a-check-that-should-pass-returns-zero) |
| the score moved but the NPC did not react | [troubleshooting](troubleshooting.md#the-score-is-written-but-the-quest-does-not-advance) |
| no message in chat at all | [troubleshooting](troubleshooting.md#nothing-appears-in-chat-when-i-run-a-check) |

---

← [Installation](installation.md) · [Commands](commands.md) →
