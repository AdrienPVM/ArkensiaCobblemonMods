[← CheeseRaids](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

The goal of this walkthrough: from a server with the jar freshly installed to **one Hoopa
Invasion run end to end** — a wave fought in the overworld, a herald revealed, a rift
entered, a parkour crossed, a boss killed and portals opened. By the last step you will
have seen every phase the mod has.

> ⚠️ Nobody has done this yet. The mod has only been deployed to test instances and no
> raid has been played, so expect to find problems this page does not cover. Do it on a
> test world, with one account you can afford to lose the inventory of, before you do it
> with players.

Allow an hour. Most of it is surveying coordinates and placing an NPC; the raid itself
takes about ten minutes.

---

## Before you start

You need a server with CheeseDash, CheeseGuard, CheeseRaids, Cobblemon and EasyNPC
installed, and the startup log showing:

```text
[CheeseRaids] initialized — modular raid framework ready.
[CheeseRaids] Loaded 1 raid definition(s), 0 active instance(s)
```

If either line is missing, stop here and go through
[installation](installation.md#verifying-it-loaded). You also need operator rights
(permission level 2) and, for step 8, a Pokémon team capable of beating a level 80 Hoopa
Unbound.

## 1. Pick your two overworld locations

Fly somewhere open. You need two spots, 10 to 30 blocks apart:

- **the wave arena** — where the invasion Pokémon spawn and are fought. The default
  spawns need a clear area of at least 13×13 with headroom; the alpha is 1.8× normal size;
- **the herald's stand** — where the Ho-Oh NPC appears once the wave is down.

Pick them away from anything you care about. The raid forces a thunderstorm and sets the
whole world to midnight for its duration, and never restores the time.

Read the coordinates at each spot:

```mcfunction
/data get entity @s Pos
```

```text
RoiCheese has the following entity data: [112.4d, 71.0d, -2043.7d]
```

Write both down, plus two more while you are out there: a **portal anchor** with 10 blocks
of clear radius, and an **exit point** on solid ground where players will be dropped at the
end. Four positions in total.

## 2. Place the herald NPC

Spawn an EasyNPC where you chose, using EasyNPC's own tooling, and give it a Ho-Oh look if
you want the theme. CheeseRaids does not create it — it only reveals and hides whatever
EasyNPC it finds nearby, so the NPC is yours to place and yours to keep.

Stand on it and read your position again. That exact coordinate goes in the config; the
search radius is 10 blocks, so being close is enough, but being exact removes a variable.

## 3. Write the four positions into the config

Open `config/cheeseraids/raids/hoopa.json`. Find the five position objects and fill in
four of them — leave `arena.center` alone, it is inside the generated rift and its default
is correct.

```json
{
  "wave":   { "arena":        { "world": "minecraft:overworld", "x": 112.0, "y": 71.0, "z": -2043.0, "yaw": 0.0, "pitch": 0.0 } },
  "npc":    { "pos":          { "world": "minecraft:overworld", "x": 118.0, "y": 71.0, "z": -2050.0, "yaw": 180.0, "pitch": 0.0 } },
  "reward": { "portalAnchor": { "world": "minecraft:overworld", "x": 100.0, "y": 71.0, "z": -2030.0, "yaw": 0.0, "pitch": 0.0 } },
  "exitPos":                  { "world": "minecraft:overworld", "x": 120.5, "y": 71.0, "z": -2060.5, "yaw": 90.0, "pitch": 0.0 }
}
```

Keep the rest of the file as it was — those are only the keys you are touching, not the
whole file. [Configuration](configuration.md) documents every other key, and
[calibration](calibration.md) goes deeper on what each position has to satisfy.

Then reload:

```mcfunction
/cheeseraids reload
```

```text
Définitions de raids rechargées.
```

**Read the console now.** That reply is unconditional — it says the same thing whether your
JSON parsed or not. A `FAILED to parse raid def` line means your edit was rejected and the
old definition is still loaded.

## 4. Verify the herald

```mcfunction
/cheeseraids herald hoopa info
```

```text
Héraut attendu: 118, 71, -2050 dans minecraft:overworld
EasyNPC dans 10 blocs: 1
  • easy_npc:humanoid_slim @ 118, 71, -2050 (dist 0.5, invisible=true)
```

`EasyNPC dans 10 blocs: 1` or more is what you want. Zero means the reveal will find
nothing — [troubleshooting](troubleshooting.md#the-herald-never-appears) separates the four
reasons.

Confirm the toggle works:

```mcfunction
/cheeseraids herald hoopa show
```

```text
1 EasyNPC rendu(s) visible(s).
```

The NPC should appear with a glow outline. Then hide it again, so the raid can reveal it
dramatically:

```mcfunction
/cheeseraids herald hoopa hide
```

## 5. Bind the herald's dialogue

This is the step that is easy to miss and breaks the raid for players.

CheeseRaids gives the NPC **no dialogue at all.** When the raid tells players *parlez-lui*,
nothing will happen unless you have configured, in EasyNPC, an action on that NPC that runs:

```mcfunction
/cheeseraids enter hoopa
```

Do that now, through EasyNPC's dialogue and action editor. Nothing in CheeseRaids does it
for you, and nothing checks that you did.

If you would rather skip it for this first run, you can: `enter` has no permission
requirement, so players can type the command themselves. Just know that the raid will tell
them to talk to an NPC who does not answer.

## 6. Verify the portal anchor

```mcfunction
/cheeseraids testreward hoopa
```

```text
Test récompense: 1/1 portail(s) Hoopa à 100, 71, -2030 (minecraft:overworld)
```

One portal and fireworks at the anchor. `0/1` means CobbleSafari is absent or refused — the
raid still finishes, just without portals. `Monde d'ancrage introuvable` means a typo in
the world id.

Calibration is done. Everything from here is the raid itself.

## 7. Start the raid and fight the wave

Stand near the wave arena — the chunk has to be loaded for the spawn to succeed — and:

```mcfunction
/cheeseraids start hoopa
```

```text
Raid 'hoopa' démarré.
```

Within a second: a light purple chat line, a big purple title, and the sky turning to a
midnight thunderstorm. Five seconds later the wave appears, and the console confirms it:

```text
[CheeseRaids] Wave for hoopa: spawned 5/5 mob(s) directly.
```

`spawned 0/5` means the spawn was refused; the preceding log line says why, and
[troubleshooting](troubleshooting.md#the-wave-never-spawns) covers all three causes.

You should see one glowing, oversized Hoopa Unbound ringed by swirling portal particles,
and four Sableye marked with witch particles. They are uncatchable, so throwing a ball does
nothing.

Kill the alpha — the glowing one. That is the shortcut: the moment it dies, the whole wave
ends and the remaining Sableye are removed.

```text
L'Alpha Hoopa s'enfuit !
Il s'enfouit dans sa dimension... la faille s'ouvre !
```

Killing everything works too, and the wave force-advances after `wave.timeoutSeconds` (180
by default) if you simply walk away.

## 8. Enter the rift

Over the next five seconds the herald's reveal builds: cave ambience, gathering particles,
two lightning strikes, then a flash as the NPC appears.

```text
Le héraut Ho-Oh surgit dans un éclair ! Parlez-lui pour entrer dans la faille.
```

Talk to him if you bound his dialogue in step 5. Otherwise:

```mcfunction
/cheeseraids enter hoopa
```

You arrive 75 blocks from the rift's centre, on a 3×3 purpur platform under a floating
gold-and-magenta ring, facing inward into the dark. For about a second a portal column
tightens around you. Then the parkour materialises ahead of you, eight blocks per tick,
with a rising musical pitch, until a flourish at the far end connects it to the boss disk.

Jump inward. Five zones in sequence: amethyst caverns, an ice bridge, moss and cobwebs, a
drop gorge, crystal tightropes. Fall and you are put back on your last checkpoint — those
are the sea lantern plates, every six platforms, and each announces itself on the action
bar:

```text
✔ Checkpoint atteint !
```

Reaching the disk:

```text
Vous avez atteint le cœur de la faille !
```

If you get genuinely stuck, `/cheeseraids enter hoopa` again teleports you to your last
checkpoint without regenerating anything.

## 9. Fight the boss

Thirty seconds after the first arrival — `boss.graceSeconds` — or as soon as everyone has
arrived:

```text
Quelque chose d'immense s'éveille au cœur de la faille...
```

Then a 44-tick crescendo: an expanding portal ring, a dragon growl climbing in pitch, two
lightning strikes, and at tick 36 the boss is pulled through.

```text
⚡ Le gardien de la faille SURGIT ! ⚡
```

A 1.5× Hoopa Unbound at level 80, plus three levels per additional participant. It is an
ordinary Cobblemon battle: engage it and fight. There is no raid mechanic and no shared
health bar — with several players on the disk, the first to engage gets the battle.

Win and the rest is automatic. If you lose or the fight drags past
`boss.combatTimeoutSeconds` (15 minutes), the raid ejects everyone and cleans up with no
reward.

## 10. The reward

```text
🏆 VICTOIRE !
La faille s'effondre — l'invasion est repoussée !
```

Glowing, regeneration and slow falling, totem particles, 4.5 seconds of celebration. Then
you are teleported to `exitPos`, portals open around `reward.portalAnchor` with fireworks
at each, and three more fireworks go off over your head.

The console closes the loop:

```text
[CheeseRaids] Rewards for hoopa: 1/1 portal(s) opened, 1 winner(s)
[CheeseRaids] Raid 'hoopa' ended and cleaned up.
```

```mcfunction
/cheeseraids status
```

```text
Raids:
• hoopa [idle]
```

That is one full raid. The rift dimension stays behind, empty and disabled, ready for the
next run.

---

## What to do next

**Schedule it.** Set `schedule.autoTrigger` to true with an hour and minute and the raid
starts itself. It is not a cron: a server down for that whole minute misses the run.
[Details](configuration.md#schedule).

**Tune the difficulty.** `arena.starRadius` lengthens the parkour, `arena.checkpointEvery`
makes falls more or less punishing, `boss.baseLevel` and `levelPerExtraPlayer` set the
fight, `wave.spawns` sets the opening. [Every key](configuration.md).

**Watch it from the dashboard.** The `raids` module shows the phase and a live participant
table, and can start, stop and reload. [Dashboard API](dashboard.md).

**Read the gaps before you run it for players.** Five of them matter: the herald needs
dialogue you bind yourself, the boss fight has no raid mechanic, rewards are not gated on
participation, `stop` does not teleport players out, and the Discord announcement only
writes to the log. [All of them, in one place](hoopa-invasion.md#what-is-incomplete).

---

← [Installation](installation.md) · [Commands](commands.md) →
