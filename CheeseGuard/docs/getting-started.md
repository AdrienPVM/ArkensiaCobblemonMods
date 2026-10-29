[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this walkthrough you will have a protected zone that nobody but its members
can build in, a second zone grouped with it so they share rules, a saved copy of the first
zone's blocks that you can restore on demand, and a working prison. Everything here is
done in game, with commands, so that you see what each piece does before you touch the
dashboard.

Allow twenty minutes. You need a server with CheeseDash and CheeseGuard installed — see
[installation](installation.md) — and operator level 2.

---

## 1. Confirm the mod is live

```mcfunction
/cg
```

```text
[CheeseGuard] Administration available on the CheeseDash web dashboard.
```

If you get *Unknown or incomplete command*, the jar is not on the server. The root command
is `/cg`; there is no `/cheeseguard`.

## 2. Mark out a zone

Get the selection tool:

```mcfunction
/cg wand
```

```text
Zone wand received!
```

A golden axe named *Zone Selection Wand* lands in your inventory. Left-click one corner of
the area you want to protect, right-click the opposite corner. Pick something small for
now — a 20×10×20 box is plenty.

If you would rather not use the wand, stand at one corner and run `/cg pos1`, then stand
at the other and run `/cg pos2`:

```text
Position 1 set: 118, 64, -302
```

```text
Position 2 set: 142, 90, -276
```

Mark both corners in the same world: nothing checks it, and the zone is created in the
world of whichever corner you set last. The selection lives in memory only — a restart
clears it.

## 3. Create the zone

```mcfunction
/cg create Market Square
```

```text
Region 'Market Square' created!
```

The name can contain spaces; the **id** is derived from it by lowercasing and replacing
everything outside `[a-z0-9_-]` with `_`, so this zone's id is `market_square`. You will
use the id in every other command.

Check it:

```mcfunction
/cg info market_square
```

```text
Market Square
ID: market_square | Overworld
Min: 118,64,-302
Max: 142,90,-276
Group: aucun
Priority: 0 | Members: 0
```

`Members: 0` is expected: you are the zone's **owner**, which is stored apart from the
member list. `Group: aucun` is the hardcoded
fallback for "no group"; you will change that in step 7.

This write created `config/cheeseguard/regions.json`.

## 4. See the zone

```mcfunction
/cg toggle
```

```text
Visualization: Enabled
```

Particles now outline nearby zones, for you alone. Walk the boundary and check it is the
box you meant. Run it again to turn it off. If the box is wrong, delete and redo —
`/cg delete market_square`, then back to step 2.

## 5. Protect it

Nothing is protected yet. A zone with no flags set behaves exactly like unclaimed world,
because an unset flag means *inherit*, and the inherited default for `block-break` is
"allowed".

```mcfunction
/cg flag market_square block-break false
```

```text
Flag block-break = false
```

```mcfunction
/cg flag market_square block-place false
```

```mcfunction
/cg flag market_square pvp false
```

Now log in as a player who is **neither a member nor an operator**, or ask someone to, and
try to break a block inside the box. Nothing happens. You can still build: `block-break`
is one of the flags where members and the owner are exempt — the classic protection
behaviour. Operators (level 2), however, bypass every member-exempt flag everywhere
(`RegionManager.isAllowed`), so testing with your own op account proves nothing.

⚠️ Two things to get right here. The value must be exactly `true`, `false` or `inherit`:
anything else, including `yes` and `1`, is read as `false` with a confirmation message
that looks like success. And `/cg flag` was **ungated until 29 October 2026** — on an
older jar, any player can run it; update the jar. Check
[the gate table](commands.md#permission-levels) against the jar you deployed.

The 31 flags, and whether members are exempt from each, are listed in
[commands](commands.md#flags); what each one actually governs is in
[zones and flags](zones-and-flags.md).

## 6. Add a member

Someone else needs to build here. They have to be online:

```mcfunction
/cg addmember market_square Steve
```

```text
Steve added.
```

Steve can now break and place inside the zone. Verify with `/cg info market_square` —
the member count goes to 1.

To remove them, `/cg removemember market_square Steve`. Be aware that this command
reports success whether or not it removed anything, so always re-check the count.

## 7. Group two zones so they share rules

Make a second zone the same way — select, then:

```mcfunction
/cg create Market Annex
```

Rather than setting the same three flags again, put both zones in a group and set the
flags once on the group. Create it:

```mcfunction
/cg group create Public Areas
```

```text
Group created: Public Areas
```

Assign both zones:

```mcfunction
/cg group add market_square public_areas
```

```text
Market Square assigned to group Public Areas
```

```mcfunction
/cg group add market_annex public_areas
```

A zone resolves a flag it does not set itself from its group, and only then from the
flag's own default. So to make the group's rules the shared ones, clear the per-zone
values you set in step 5:

```mcfunction
/cg flag market_square block-break inherit
```

Group flags themselves are set from the dashboard, not from a command — there is no
`/cg group flag`. Open CheeseDash, go to the CheeseGuard section, open **Public Areas**
and set `block-break` and `block-place` to deny there. Both zones follow.

Check your work with `/cg info market_square`: the `Group:` line now reads
`public_areas` (the group's id, not its display name).

The full inheritance and priority model, including what happens when two zones overlap, is
in [groups](groups.md).

## 8. Save the zone's blocks

A zone state is a snapshot of every block inside the box. This is how arena resets work.

```mcfunction
/cg state capture market_square Pristine
```

```text
State 'Pristine' captured (9620 blocks, 3 entities)
```

⚠️ Capture walks every block on the server thread. A 20×10×20 box is 4 000 reads and
instant; a 100×100×100 box is a million and will stutter visibly. Keep captures small, or
do them with nobody online.

List what you have:

```mcfunction
/cg state list market_square
```

```text
States for zone 'market_square':
  1 — Pristine (9620 blocks, 3 entities)
```

The first column is the state id. Now break a few blocks inside the zone, then put it
back:

```mcfunction
/cg state apply market_square 1
```

```text
State '1' applied to zone 'market_square'
```

The blocks you broke are back. Try it with the animation on:

```mcfunction
/cg state apply market_square 1 true cascade_from_bottom 4 sparkle
```

```text
Animating state '1' on zone 'market_square'...
```

The feedback means "started". The rebuild runs over the following ticks. All fourteen
directions and eighteen particles are listed in
[commands](commands.md#cg-state-apply) — and a typo in either one silently
falls back to the default rather than erroring.

## 9. Set up the prison

The prison is a separate command tree with its own zone. Make a zone somewhere escape-proof
— a sealed room is fine — and call it `prison`. Then:

```mcfunction
/prison setzone prison
```

```text
Prison zone set: prison
```

Stand where released prisoners should reappear, and record it:

```mcfunction
/prison setout
```

```text
Prison release point set: 118.5, 64.0, -302.5 (minecraft:overworld).
```

Now jail someone:

```mcfunction
/prison set Steve 10m testing the prison
```

```text
Steve has been sent to prison for 10m.
```

Steve sees:

```text
⛓ You are in prison for 10m. Reason: testing the prison
```

He is **not** teleported by the command. The tick-based confinement in `PrisonListener`
pulls him in a moment later, and from then on he cannot leave the zone and most commands
are refused. Durations are compound: `90s`, `30m`, `2h`, `7d`, `1h30m` all work, and a
bare number means minutes.

Check and release:

```mcfunction
/prison list
```

```text
Active prisoners (1)
• Steve — 9m — testing the prison
```

```mcfunction
/prison release Steve
```

```text
Steve has been released from prison.
```

The confinement mechanism, the blocked-command whitelist and the doppelgänger that stands
in for an offline prisoner are in [prison](prison.md).

## 10. Open the dashboard

Everything from here is easier in CheeseDash, and some of it is only possible there:
group flags, dimension creation, the event editor and its timeline, chunk sets, race
tracks, Discord webhooks and zone sale configuration have no command at all.

Open `http://<server>:44311`, log in, and pick **CheeseGuard** in the sidebar. The zones
you created in this walkthrough are listed, with their flags as a grid rather than one
command per flag.

⚠️ One thing to know before you create dashboard accounts for other people: a `READ_ONLY`
account can only read here — CheeseDash's own dispatcher requires `OPERATOR` or above for
every non-`GET` request to a module — but an `OPERATOR` can do everything this mod offers.
Six of CheeseGuard's eight APIs check no role themselves, so nothing can sit above
`OPERATOR`: deleting a zone and wiping a dimension are at the same bar as editing a flag.
Hand out `OPERATOR` only to people you would trust with the whole mod. The detail is in
[architecture](architecture.md#known-weaknesses).

## Where to go next

| You want to | Read |
|---|---|
| understand flag inheritance, priority and overlapping zones | [zones and flags](zones-and-flags.md) |
| run a timed event with scoring | [events](events.md), then [timeline](timeline.md) |
| give each player their own copy of a world | [dimensions](dimensions.md) |
| regenerate part of the map on a schedule | [chunks](chunks.md) |
| drive all of it from a script | [dashboard](dashboard.md) |
| recover a player's inventory after an event went wrong | [`/cg restoreinv`](commands-operations.md#cg-restoreinv) |

---

← [Installation](installation.md) · [Commands](commands.md) →
