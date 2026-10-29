[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this page you will have a server where a player's Pokémon stop at level 20,
an rctmod gym leader who raises that ceiling to 30 when beaten, and a working cap badge on
screen. One champion, one cap step, proven in game.

The walkthrough assumes a Fabric 1.21.1 server with Cobblemon, Radical Cobblemon Trainers
and CobbleCap installed, as described in [installation](installation.md). It does **not**
assume CheeseDash — everything here is done with commands.

---

## 1. Confirm the mod is awake

Start the server and look for:

```text
[CobbleCap] Initialized — startingCap=20, enabled=true, champions=0
[CobbleCap] Cobblemon XP hook registered on field: EXPERIENCE_GAINED_EVENT_PRE
[CobbleCap] Battle victory hook registered on field: BATTLE_VICTORY
```

`champions=0` is expected: the mod ships an empty champion list. The two hook lines are the
important ones — they prove the reflective subscription into Cobblemon's event bus landed.
If instead you see `Cobblemon not found` or `Could not find XP event field`, stop here and
read [troubleshooting](troubleshooting.md#the-xp-cap-does-nothing).

Join the server. The log should say:

```text
[CobbleCap] Player joined: Steve (cap=20)
```

That line means a row was created in `config/cobblecap/players.json` with the default
`startingCap`.

## 2. See the cap from the player's side

```mcfunction
/cobblecap
```

```text
Your level cap: 20
All champions defeated!
```

The second line is not a bug. `nextUnbeaten` over an empty champion list returns nothing,
and the mod reports that as "nothing left to beat". It will become a real champion name in
step 5.

If CobbleCap is on your client too, a small badge reading `20` sits in the bottom-right
corner. Move it if it is in the way:

```mcfunction
/cobblecap hud position top-left
```

```text
HUD moved to top-left.
```

## 3. Watch the cap actually bite

Get a Pokémon to level 20. The quickest way is a handful of exp candies, or:

```mcfunction
/pokegive Steve pikachu level=19
```

Then battle something, or feed it candy. Once it reaches the cap, further experience is
refused and the player gets an action-bar line:

```text
Pikachu cannot gain XP — level cap is 20. No more champions to beat!
```

Two details worth noticing, because they are the whole design:

- The Pokémon sits at **level 19** with a nearly full XP bar, not at level 20. The trimming
  layer deliberately leaves it one experience point short of the cap level so Cobblemon's
  level-up path never fires. See [the level cap](level-cap.md#2-pokemonaddexperiencemixin--the-shaping-layer).
- The surplus experience is **discarded**, not banked. Raising the cap later does not make
  the Pokémon jump several levels.

The message is rate-limited to one per 30 seconds per Pokémon, so do not expect it on every
candy.

## 4. Pick the trainer that will be your first champion

CobbleCap identifies a champion by its **rctmod trainer id**, and you need the exact string.
Place or find an rctmod trainer NPC, then right-click it once and read the server log:

```text
[CobbleCap] BATTLE_PRE: entity=TrainerMob trainerId=gym_leader_pierre_0395 (raw=gym_leader_pierre_0395)
```

That line only appears once a champion battle is being evaluated, so on a fresh install you
may see nothing. The reliable alternative is to read the id out of your rctmod datapack —
the filename under `data/rctmod/trainers/` *is* the id.

Write it down. Everything in the next step hangs off getting it byte-exact.

## 5. Author one champion

Stop the server, or be ready to run `/cobblecap reload`. Open
`config/cobblecap/champions.json` and replace the empty array:

```json
{
  "champions": [
    {
      "id": "gym_leader_pierre_0395",
      "displayName": "Pierre",
      "capGranted": 30,
      "allowRefight": false
    }
  ]
}
```

Four fields. Every other field falls back to a default — no Pokédex gate, no quest gate, no
scoreboard gate, no challenge variant. Add those later; get one working champion first.

Reload:

```mcfunction
/cobblecap reload
```

```text
Champion config reloaded (1 champions loaded).
```

⚠️ A count of `0` means the JSON failed to parse and the old list is still in memory. Check
the server log for `[CobbleCap] Failed to load champions.json`.

Now ask again as the player:

```mcfunction
/cobblecap
```

```text
Your level cap: 20
Next champion: Pierre
```

The HUD badge still shows `20`, and `reload` has re-synced every client so the next-champion
name is current.

## 6. Verify the gate from the admin side

Before fighting, check what the server thinks:

```mcfunction
/cobblecap status Steve
```

The interesting block is near the middle:

```text
─── Champions (0/1 beaten) ───
  ✗ gym_leader_pierre_0395 (Pierre, cap=30)
─── Next champion gating ───
Next: gym_leader_pierre_0395 (Pierre)
  cap granted: 30 | allowRefight: false
  victoryQuestId:
  challengeId: ''
  scoreboard: (none)
  ftb quest: (none)
  pokedex: (none)
```

Three `(none)` lines mean no gates, so the fight is allowed. If the id you typed does not
match the trainer, this screen is where you will notice: the champion is listed but the NPC
never triggers any of it.

## 7. Beat the champion

Right-click the trainer and win the battle. The whole server sees:

```text
Steve defeated champion Pierre! Their cap is now 30.
```

and the player also gets:

```text
You defeated Pierre! Your level cap is now 30.
```

The HUD badge flips to `30` without a relog — `handleVictory` calls `syncToClient` at the
end. `/cobblecap` now says `All champions defeated!` again, because there is only one
champion in the list.

Confirm it stuck:

```mcfunction
/cobblecap get Steve
```

```text
Steve's current cap: 30.
```

That Pikachu from step 3 will now resume levelling from 19 — from where it stopped, not
from a backlog.

If the victory produced no message at all, the trainer id does not match. Add it by hand to
unblock yourself and then fix the id:

```mcfunction
/cobblecap addbeaten Steve "gym_leader_pierre_0395"
```

```text
Added 'gym_leader_pierre_0395' to Steve's beaten list — cap raised to 30.
```

## 8. Add a second champion and a gate

Append to the array — remember the order **is** the progression:

```json
{
  "champions": [
    {
      "id": "gym_leader_pierre_0395",
      "displayName": "Pierre",
      "capGranted": 30,
      "allowRefight": false
    },
    {
      "id": "gym_leader_ondine_0396",
      "displayName": "Ondine",
      "capGranted": 40,
      "requiredPokedex": 15,
      "allowRefight": false
    }
  ]
}
```

`/cobblecap reload`, then try to fight Ondine with fewer than 15 Pokémon caught:

```text
Cannot challenge Ondine — conditions not met:
  - Pokédex: 8/15 caught
```

And try to fight Ondine *before* Pierre, from a fresh player, to see the ordering gate:

```text
You cannot challenge Ondine yet — you must defeat Pierre first.
```

Those two refusals are the backbone of the progression: conditions, then order.

## 9. Reset and start over

```mcfunction
/cobblecap reset Steve
```

```text
Reset Steve's cap to 20 (starting cap).
```

This is a full wipe, not just the number: beaten champions, beaten challenges, cooldowns
and the XP lock all clear, and for every champion in the list the mod revokes the
`cobblecap:champion/<key>` and `cobblecap:challenge/<key>` advancements and resets the FTB
quests. There is no confirmation and no undo. Use it on a test account.

---

## Where to go next

| You want | Read |
|---|---|
| challenge variants, quests, scoreboard gates, the Hall of Fame | [the champion system](champions.md) |
| why the cap behaves the way it does, and what `enabled: false` really turns off | [the level cap](level-cap.md) |
| statues and dioramas of your champions | [statues and dioramas](statues.md) |
| the final champion taking the last winner's team | [champion morphing](champion-morphing.md) — read the warnings first |
| every subcommand in full | [commands](commands.md) and [statue, chrono and champion commands](commands-statues.md) |
| the admin panel | [the dashboard API](dashboard.md) |

⚠️ One thing to settle before you go further: on any server that is not Arkensia, the
champion morph cannot work until you change `datapackName` in
`RctTrainerMorpher.java` line 57 and rebuild. Everything on this page works without that
edit; champion morphing and dynamic dioramas do not.

---

← [Installation](installation.md) · [The level cap](level-cap.md) →
