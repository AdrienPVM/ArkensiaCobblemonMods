[← CobbleShift](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this walkthrough you will have turned a player into a Charizard that moves
at the right speed, hits for the right damage and throws fire charges on left click; you
will have given a second player a passive buff driven by their party; and you will have
transformed an NPC that survives a restart. Count on twenty minutes on a server you can
restart.

The starting point is a Fabric 1.21.1 server with Cobblemon, the CobbleShift jar in
`mods/` on both sides, and an account at permission level 2. If you are not there yet,
[installation](installation.md) gets you there.

---

## 1. Check that Cobblemon is reachable

Everything in this mod resolves a species through reflection into Cobblemon. Confirm it
works before anything else.

```mcfunction
/cshift species charizard
```

```text
═══ charizard ═══
Types: fire / flying
Stats: HP=78 Atk=84 Def=78 SpAtk=109 SpDef=85 Spd=100
BST: 534
Mapped Speed: 0.125
Mapped Health: 15.2 HP
Attack: 6.4 dmg, 32 tick cd, 3 projectile(s)
Ability: Flamme Intérieure (fire)
```

Real stats mean the reflection found Cobblemon's species registry. `Species 'charizard'
not found` means it did not, and nothing below will work — go to
[troubleshooting](troubleshooting.md) first.

Those derived numbers are what a transform will feel like. `Mapped Speed: 0.125` against
a player's normal `0.1` is a quarter faster; `15.2 HP` is slightly below a player's
twenty. Read this output for any species before you hand it to a player.

## 2. Transform someone

```mcfunction
/cshift transform Steve charizard
```

You see:

```text
✦ Steve transformed into charizard
```

Steve sees `✦ You are transformed into charizard!` and, on his screen:

- the camera snaps to third person and F5 no longer does anything
- a Charizard model where his player model was
- no held item, no arms, no nametag, and a plain white cross drawn by the mod instead of
  the vanilla crosshair
- a Charizard cry, if he is within 32 blocks of himself, which he is

Everyone else sees a Charizard too, as long as they have the mod. A vanilla client sees
nothing at all — Steve is invisible to them, because invisibility is the mechanism and
the model is the mod's own layer on top.

## 3. Watch the attack

Steve left-clicks. The vanilla swing never happens; `AttackInputMixin` cancels it and the
client sends an `AttackAbilityPayload` instead. About 1.3 seconds later, three fire
charges leave the model in a 15° cone — three, because Charizard's base stat total of 534
is over the 450 threshold — and anything they hit burns for three seconds.

The wind-up is deliberate: the shot is queued for 26 ticks so the model's attack
animation has time to play, and the aim direction is read **at spawn**, so Steve can
re-aim mid-animation. The cooldown, on the other hand, was stamped at click time, so the
animation delay never costs him damage per second.

If you do not want this, pass `noattacks` at transform time:

```mcfunction
/cshift transform Steve charizard noattacks
```

```text
✦ Steve transformed into charizard [no attacks]
```

There is no way to flip that on a live transform. Transform again to change it.

## 4. Resize and respeed by hand

The species-derived scale is often not what a minigame wants.

```mcfunction
/cshift scale Steve 1.5
```

```text
✦ Scale of Steve → 1.5
```

That multiplies the automatic species scale, so `1.0` is Charizard's own size. The value
goes to the clients as well, so the drawn model and the collision box stay in agreement.

```mcfunction
/cshift speed Steve 2.0
```

```text
✦ Speed of Steve → 2.0x
```

Speed **replaces** the species-derived value with `0.1 × value` as a flat base rather
than multiplying it. Neither value survives a revert.

## 5. Add shiny and a regional form

The species argument is greedy and the mod parses the rest of the line itself. The
species comes first; everything after it is a flag or a Cobblemon aspect, in any order.

```mcfunction
/cshift transform Steve lycanroc shiny midnight
```

```text
✦ Steve transformed into lycanroc shiny midnight
```

The echo is the point: it shows what the mod actually parsed, which is the cheapest way
to find out that your token produced the aspect you meant. Tab completion suggests
`shiny`, `noattacks` and six form names, but any aspect string Cobblemon accepts can be
typed.

Then put Steve back:

```mcfunction
/cshift revert Steve
```

```text
✦ Steve — transformation reverted
```

He hears a villager celebration, sees particles, and gets his own body back. The three
attribute modifiers come off and his health is clamped down if the transform had raised
his maximum.

## 6. Turn on passive abilities

This is the other half of the mod, and it is off in a fresh config.

```mcfunction
/cshift ability global enable
```

```text
✦ Global abilities enabled
```

This is the one subcommand that writes the config file, so the switch survives a restart.

Now have Alex put a Gengar in her first party slot and wait. Within five seconds —
`abilityTickInterval` is 100 ticks by default — she goes invisible and the ability HUD
appears. Swap her lead to a Charizard and, on the next refresh, she gets Fire Resistance;
the invisibility is no longer refreshed and runs out within `abilityEffectDuration`
(eight seconds by default).

⚠️ Ghost-type leads grant real, indefinitely refreshed Invisibility to anyone who catches
a Gastly. On a PvP server, decide about that before you leave the subsystem on. The full
type table is in [passive abilities](abilities.md).

Pin a player to a type regardless of their party:

```mcfunction
/cshift ability Alex charizard
```

```text
✦ Ability forced for Alex → charizard
```

And release the pin:

```mcfunction
/cshift ability Alex
```

```text
✦ Forced ability removed for Alex — back to auto mode
```

## 7. Audit what is running

```mcfunction
/cshift list
```

```text
═══ CobbleShift Status ═══
Active transforms: 0
Active abilities: 1
  • charizard (fire)
Global abilities: ON
```

No transform is listed, since you reverted Steve in step 5, and Alex's ability is no longer
marked `[FORCED]` since you released the pin. The ability lines carry no player name — the
record holds a UUID and the command never
resolves it. If you need the name, read `GET /api/v1/shift/abilities` instead, which
joins it for you.

## 8. Transform an NPC, then restart

NPC transforms are purely visual and they persist, which is the whole reason they are a
separate subsystem.

Stand within 5 blocks of an EasyNPC, look at it, and type:

```mcfunction
/cshift transformnpc snorlax
```

```text
✦ NPC transformed into snorlax
```

There is no entity argument — you aim, then type. The command raycasts your eye line
against entity bounding boxes expanded by 0.3 and takes the first hit.

Now stop and restart the server. The log shows:

```text
[CobbleShift] Loaded 1 NPC transform(s), will re-apply on first tick
```

The Snorlax is still a Snorlax. A player still transformed at that point would, by
contrast, be himself again: `SERVER_STOPPING` reverts every player transform. Player transforms are session state; NPC transforms are saved. The
difference and its consequences are in [NPC transforms](npc-transforms.md).

## 9. Tune it from the dashboard

With CheeseDash running, log in at port **44311** and open the **CobbleShift** entry in
the sidebar. You get the live transform list, the ability list with player names resolved,
the species probe and the config form.

Two things to know before you rely on it. The config `PUT` needs a `SUPER_ADMIN` session
rather than `OPERATOR`, because it writes to disk. And the `mappedScale` field on
`GET /species/:name` is wrong — it feeds the Pokédex height in decimetres into a formula
that expects blocks, so nearly every species reports the `2.5` cap. Use `/cshift species`
in game for that number. Both are detailed in [the dashboard API](dashboard.md).

## Where to go next

| You want to | Read |
|---|---|
| every subcommand, argument and failure message | [Commands](commands.md) |
| what each config key actually changes | [Configuration](configuration.md) |
| what a transform applies, and what it costs | [Transforms](transforms.md) |
| the passive type effects, one by one | [Passive abilities](abilities.md) |
| NPC transforms and their persistence | [NPC transforms](npc-transforms.md) |
| the REST routes and their roles | [Dashboard API](dashboard.md) |
| the reflection, the threading, the known weak spots | [Architecture](architecture.md) |

---

← [Installation](installation.md) · [Commands](commands.md) →
