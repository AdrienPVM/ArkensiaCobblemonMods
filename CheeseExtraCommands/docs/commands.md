[← CheeseExtraCommands](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

One command, `/cheesecheck`, with four subcommands: `ev`, `iv`, `pokemon` and
`ballvariety`. The whole tree is registered in `CheeseCheckCommand.register` and guarded
by a single `requires(src -> src.hasPermissionLevel(2))` on the root literal.

Everything on this page was read out of `CheeseCheckCommand.java` and
`CobblemonReflect.java`. The older README described `/cheesecheck ev <player> <stat>
<value>`, which never existed in this code; see [what the old README got
wrong](#what-the-old-readme-got-wrong).

---

## The shape they all share

Every subcommand follows the same contract:

```text
/cheesecheck <type> <player> <criteria...> <scoreboard> <value>
```

| Step | Behaviour |
|---|---|
| 1 | Resolve the target player from the `player` argument |
| 2 | If Cobblemon is absent, message the player and return `0`. Nothing else happens |
| 3 | Read the target's **party** through reflection |
| 4 | Count how many party members satisfy the criteria |
| 5 | **Pass** → write `value` into the `scoreboard` objective, message the player in green, return `1` |
| 6 | **Fail** → message the player in red, write nothing, return `0` |

Three consequences follow from that, and they are the three things people get wrong.

**The party only.** `CobblemonReflect.getPartyPokemon` resolves Cobblemon's
`PokemonStoreManager` and asks it for a party store. PC boxes are never consulted. A
player with a perfect Pokémon in a box fails every check.

**The message goes to the target, not the caller.** Every `sendMessage` call in
`CheeseCheckCommand` targets the resolved `player`. A command block, the server console,
or an admin checking someone else gets no chat output at all. The caller's only feedback
is the numeric result and the server log.

**Failure is not an error.** A failed check returns `0` without throwing, so the command
is "complete but unsuccessful". That is exactly what makes it usable as a quest gate:
nothing is written, so quest state stays put.

## Permission level

The entire tree requires **permission level 2**, the level a vanilla server grants an
operator.

| Caller | Level | Works |
|---|---|---|
| Server console | 4 | yes |
| Operator (`/op`) with default `op-permission-level=4` | 4 | yes |
| Command block | 2 | yes |
| `mcfunction` run from a command block or `/execute` | inherits 2 | yes |
| An EasyNPC or quest-system button that runs as the server | 4 | yes |
| Ordinary player | 0 | no |

A player below level 2 does not see `cheesecheck` in tab completion, and typing it
produces the vanilla *Unknown or incomplete command* error rather than a permission
message. That is Brigadier's behaviour, not something this mod chooses.

These commands are **designed to be executed by the server**, not typed by players. The
player argument exists precisely so that a dialogue button, a command block or a quest
task can check a third party.

## Tab completion

| Argument | Completion |
|---|---|
| `player` | yes. `EntityArgumentType.player()` suggests online player names and accepts a single-target selector such as `@p` or `@s` |
| `scoreboard` | **no**. It is a plain `StringArgumentType.word()`, so existing objective names are not suggested and typos are not caught |
| `species` | **no**. Also a `word()`; no species list is offered |
| every integer argument | no, but the range is enforced and shown in the error |

The lack of completion on `scoreboard` is the single most common source of silent
breakage. See [the auto-creation trap](#the-auto-creation-trap).

---

## `/cheesecheck ev`

```mcfunction
/cheesecheck ev <player> <minEV> <scoreboard> <value> [statCount] [teamCount]
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `player` | player selector | yes | an online player's name, or a single-target selector | — |
| `minEV` | integer | yes | `1`–`252` | — |
| `scoreboard` | single word | yes | any objective name; created if missing | — |
| `value` | integer | yes | any `int`, negatives included | — |
| `statCount` | integer | no | `1`–`6` | `1` |
| `teamCount` | integer | no | `1`–`6` | `1` |

**There is no stat selector.** `minEV` is a threshold applied to all six stats at once
and `statCount` is *how many of them* must clear it. You can require "three stats at 100
EVs or more", you cannot require "Speed at 252". If you need a specific stat, this
command cannot express it.

The stats read are the six Cobblemon battle stats in enum order: HP, ATTACK, DEFENCE,
SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED. `EVASION` and `ACCURACY` exist in Cobblemon's
`Stats` enum but the reflection layer deliberately takes only the first six.

### How the counting works

For each Pokémon in the party, the command counts stats whose EV is `>= minEV`. If that
count is `>= statCount`, the Pokémon matches. If the number of matching Pokémon is
`>= teamCount`, the check passes.

With both optional arguments omitted that reduces to: at least one Pokémon in the party
has at least one stat at `minEV` EVs or more.

### Examples

A single Pokémon trained in one stat:

```mcfunction
/cheesecheck ev Steve 252 quest_ev 2
```

Steve sees, in green:

```text
Vérification réussie ! Un de tes Pokémon a 1 stats à 252+ EVs.
```

The grammar there is wrong in the mod itself: the singular case still prints
`1 stats`. It is cosmetic and it is in the source, not in this page.

A fully EV-trained team, four Pokémon with two maxed stats each:

```mcfunction
/cheesecheck ev Steve 252 quest_ev_team 1 2 4
```

```text
Vérification réussie ! 4/4 Pokémon dans ton équipe remplissent le critère (2 stats à 252+ EVs).
```

### Failure messages

| Message | Condition |
|---|---|
| `[CheeseCheck] Cobblemon non disponible.` | `Cobblemon` class not on the classpath. Returns `0` before reading anything |
| `Aucun Pokémon dans ton équipe n'a <minEV> EVs dans une stat. Continue l'entraînement !` | `teamCount` and `statCount` both `1`, and nothing matched |
| `Aucun Pokémon dans ton équipe n'a <statCount> stats à <minEV>+ EVs. Continue l'entraînement !` | `teamCount` is `1`, `statCount` above `1`, nothing matched |
| `<matching>/<teamCount> Pokémon dans ton équipe remplissent le critère (<statCount> stats à <minEV>+ EVs). Continue l'entraînement !` | `teamCount` above `1` and not enough Pokémon matched. Shows partial progress |

All of them return `0`.

An out-of-range number is rejected by Brigadier before the handler runs, with the
vanilla message *Integer must not be less than 1, found 0* or *Integer must not be more
than 252, found 300*. That is a parse error: the whole line is refused before anything
runs, so `/execute store result` leaves its target untouched. An offline or unknown
player name produces the vanilla *No player was found*, but only when the handler
resolves the argument, at execution time: the command fails outright and an
`/execute store result` or `store success` target receives `0`.

---

## `/cheesecheck iv`

```mcfunction
/cheesecheck iv <player> <count> <minIV> <scoreboard> <value> [teamCount]
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `player` | player selector | yes | an online player's name, or a single-target selector | — |
| `count` | integer | yes | `1`–`6` | — |
| `minIV` | integer | yes | `1`–`31` | — |
| `scoreboard` | single word | yes | any objective name; created if missing | — |
| `value` | integer | yes | any `int` | — |
| `teamCount` | integer | no | `1`–`6` | `1` |

Note the argument order against `ev`: here the **count comes before the threshold**
(`count` then `minIV`), while `ev` takes the threshold first and the count as a trailing
optional. The two subcommands are not symmetrical. This is the ordering mistake to watch
for when writing dialogue files.

`count` is the number of IVs that must reach `minIV` on the *same* Pokémon. "Five
perfect IVs" is `count=5 minIV=31`.

### Examples

```mcfunction
/cheesecheck iv Steve 5 31 quest_iv 4
```

```text
Vérification réussie ! Un de tes Pokémon a 5 IVs à 31+.
```

Requiring it of three Pokémon at once:

```mcfunction
/cheesecheck iv Steve 4 25 quest_iv_team 4 3
```

```text
Vérification réussie ! 3/3 Pokémon dans ton équipe remplissent le critère (4 IVs à 25+).
```

### Failure messages

| Message | Condition |
|---|---|
| `[CheeseCheck] Cobblemon non disponible.` | Cobblemon absent |
| `Aucun Pokémon dans ton équipe n'a <count> IVs à <minIV>+. Continue l'élevage !` | `teamCount` is `1` and nothing matched |
| `<matching>/<teamCount> Pokémon dans ton équipe remplissent le critère (<count> IVs à <minIV>+). Continue l'élevage !` | `teamCount` above `1`, not enough matched |

Unlike `ev`, `iv` has no separate single-criterion wording. Both return `0`.

---

## `/cheesecheck pokemon`

```mcfunction
/cheesecheck pokemon <player> <species> <scoreboard> <value>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `player` | player selector | yes | an online player's name, or a single-target selector | — |
| `species` | single word | yes | a Cobblemon Showdown id, lowercased by the command | — |
| `scoreboard` | single word | yes | any objective name; created if missing | — |
| `value` | integer | yes | any `int` | — |

No optional arguments, and no count: a single matching Pokémon anywhere in the party
passes.

### How matching works

The command lowercases `species`, then for each party member reads
`getSpecies().getShowdownId()` through reflection (falling back to `showdownId`,
`getName` then `name` if the first is unavailable) and lowercases that too. A match is
either an exact equality or the stored id *ending with* `:<species>`, which tolerates a
namespaced id such as `cobblemon:gyarados`.

Because it is the Showdown id, write it the way Showdown does: no space, no hyphen, no
accent. `mr.mime` is `mrmime`, `Nidoran♀` is `nidoranf`, `Tapu Koko` is `tapukoko`.
`species` being a `word()` argument, a value containing a space cannot be entered at all.

⚠️ This is an **observation about Cobblemon 1.6.1**, not a specification. The exact set
of Showdown ids, and whether a given form or regional variant has its own id, is
Cobblemon's business and may change between its versions. The reflection call will keep
working; the ids might not.

### Example

```mcfunction
/cheesecheck pokemon Steve gyarados quest_dante 6
```

```text
Vérification réussie ! Tu as bien un gyarados dans ton équipe.
```

The species name is echoed back exactly as typed, lowercased. The message will say
`gyarados`, never `Gyarados`.

### Failure messages

| Message | Condition |
|---|---|
| `[CheeseCheck] Cobblemon non disponible.` | Cobblemon absent |
| `Tu n'as pas de <species> dans ton équipe. Capture-en un et reviens !` | no party member matched |

A species id that does not exist at all is indistinguishable from an absent Pokémon:
both produce the second message and return `0`. Nothing validates the id, so a typo
makes a quest step permanently uncompletable with no clue in the log.

---

## `/cheesecheck ballvariety`

```mcfunction
/cheesecheck ballvariety <player> <count> <scoreboard> <value>
```

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `player` | player selector | yes | an online player's name, or a single-target selector | — |
| `count` | integer | yes | `1`–`20` | — |
| `scoreboard` | single word | yes | any objective name; created if missing | — |
| `value` | integer | yes | any `int` | — |

Reads `getCaughtBall` (falling back to `getPokeball`) on each party member, then the
ball's own name, lowercased, into a `HashSet`. Empty strings are discarded. The check
passes when the set's size is `>= count`.

`count` tops out at `20` while a party holds at most six Pokémon, so any value above `6`
can never pass. Brigadier accepts it; the check cannot. The range is wider than the
mechanic allows.

A Pokémon hatched from an egg or given by a command may have no caught ball, or a ball
whose name resolves to the empty string through reflection. Those are skipped rather
than counted as a distinct type.

### Example

```mcfunction
/cheesecheck ballvariety Steve 6 quest_suzy 8
```

```text
Vérification réussie ! 6 types de Balls différentes dans ton équipe.
```

### Failure messages

| Message | Condition |
|---|---|
| `[CheeseCheck] Cobblemon non disponible.` | Cobblemon absent |
| `Tu as <n> types de Balls différentes, il en faut <count>. Capture avec des Balls variées !` | the set is smaller than `count`. `<n>` is the real count, so the player sees how far they are |

---

## Return values, in one table

This is the part a quest system actually consumes.

| Situation | Result | Throws | What a caller observes |
|---|---|---|---|
| Check passes | `1` | no | score written, `store result` → `1`, `store success` → `1`, conditional command block chains |
| Check fails | `0` | no | nothing written, `store result` → `0`, but `store success` → **`1`** and a conditional command block **still chains**: the command did not throw |
| Cobblemon absent | `0` | no | identical to a failed check. **There is no way to tell them apart from the result alone** |
| Player not online | — | yes | execution error; `store result` and `store success` targets both receive `0` |
| Integer out of range | — | yes | parse error; nothing runs, `store` targets are left as they were |
| Unknown subcommand or missing argument | — | yes | vanilla *Unknown or incomplete command* |

`0` being returned for both a legitimate failure and a missing Cobblemon is the weakest
point of the interface. Distinguishing them from a command block is impossible; check
the player's chat or the server log instead.

Since Minecraft 1.20.3, "success" means "did not throw", not "returned non-zero"
(Brigadier's `ContextChain.runExecutable` reports success `true` with whatever the
command returned). A failed check is therefore a *successful* command that returned
`0`. Branch on `store result`, or on the objective the check writes, never on
`store success`.

## Integration patterns

### Reading the result directly

```mcfunction
/scoreboard objectives add check_ok dummy
/execute store result score Steve check_ok run cheesecheck iv Steve 5 31 quest_iv 4
/execute if score Steve check_ok matches 1 run say Steve passed
```

`store result` captures the `1`/`0` return. Use this when you want the outcome without
caring about the objective the command itself writes, for instance while testing a
threshold.

### The intended pattern: let the command write the progress

The command already writes a scoreboard on success, so a quest step does not need
`store result` at all. Point `scoreboard` at the objective your NPC branches on, and
`value` at the progress number for the *next* dialogue state:

```mcfunction
/cheesecheck iv @s 5 31 alfred_progress 12
```

The dialogue then reads `alfred_progress`:

```mcfunction
/execute if score @s alfred_progress matches 11 run function arkensia:alfred/still_breeding
/execute if score @s alfred_progress matches 12 run function arkensia:alfred/reward
```

On a failed check `alfred_progress` stays at `11`, so the player gets the
"still breeding" line again. This is why the command sets nothing on failure.

### FTB Quests

An FTB Quests task cannot run a command, but it can observe a score. Give the quest an
**Observation** task on the objective the check writes, and trigger the check from the
NPC dialogue button that the player clicks. The quest completes the moment the check
passes, because that is the moment the score changes.

### A command block chain

Do **not** put the check in an impulse block and the reward in a **Chain,
Conditional** block behind it. On 1.21.1 a command block's `SuccessCount` goes up
whenever its command completes without throwing (`CommandBlockExecutor` counts the
`successful` flag, not the return value), so the conditional block fires on a failed
check too. Make the chain block test the score instead:

```mcfunction
execute if score @p quest_iv matches 4 run give @p cobblemon:master_ball 1
```

## The auto-creation trap

`setScore` looks the objective up with `getNullableObjective`. If it does not exist, the
objective is **created** rather than reported as an error:

```java
objective = scoreboard.addObjective(objectiveName, ScoreboardCriterion.DUMMY,
        Text.literal(objectiveName), RenderType.INTEGER, true, null);
```

A `dummy` objective, display name equal to its own id, integer render type. The server
logs one warning:

```text
[CheeseExtra] Scoreboard objective 'alfred_progres' not found — creating it
```

then a confirmation of the write:

```text
[CheeseExtra] Set alfred_progres = 12 for Steve
```

A typo therefore produces a working command, a green success message to the player, and
an objective nobody reads. Grep the log for `not found — creating it` after authoring a
new quest step; on a server whose objectives are all pre-created, that line should never
appear again.

One more detail from the same method: the score is written against
`ScoreHolder.fromName(player.getName().getString())`, the player's **name**. A player who
changes their Minecraft name starts from a blank score and loses their quest progress.

---

## What the old README got wrong

For the record, since anything copied from it will not work:

| Old README claimed | The code actually has |
|---|---|
| `/cheesecheck ev <player> <stat> <value>` | `ev <player> <minEV> <scoreboard> <value> [statCount] [teamCount]`. No stat selector exists, and the mandatory `scoreboard` argument was missing from the documented syntax |
| `/cheesecheck iv <player> <count>` | `iv <player> <count> <minIV> <scoreboard> <value> [teamCount]` |
| `/cheesecheck pokemon <player> <species>` | `pokemon <player> <species> <scoreboard> <value>` |
| ball variety counted "across the party or box" | the party only. `getPartyPokemon` never touches the PC |

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
