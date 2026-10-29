[← CheesePatrol](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this page an EasyNPC will be walking a closed four-point circuit around a
village square, pausing five seconds at one corner, trailing cyan dust, and stopping to
turn and face you whenever you come within six blocks. It takes about ten minutes, and
every step says what you should see before you move on.

You need a server with CheesePatrol, CheeseDash and EasyNPC installed (see
[installation](installation.md)), and permission level 2 — operator on a vanilla server.
Every `/npcpatrol` subcommand is gated on that level.

---

## 1. Put an NPC in the world

CheesePatrol does not create NPCs. It drives one that already exists, so spawn one with
EasyNPC's own tools first, wherever you want the circuit to start.

Then pick your spot. The single thing that decides whether this works is **line of sight
between consecutive waypoints**. Movement is a velocity override, not pathfinding: the
NPC walks straight at its next waypoint and does not route around a fence, a flower pot
or a one-block step up. A flat open square is the right place for a first circuit; a
staircase is not.

You should see: an NPC standing still, doing whatever its EasyNPC configuration makes it
do.

## 2. Get the wand

```mcfunction
/npcpatrol wand
```

```text
[CheesePatrol] Patrol Wand given. Right-click an EasyNPC to select it, then use /npcpatrol add.
```

A gold, glinting **Patrol Wand** lands in your inventory. It is a Blaze Rod carrying a
`cheesepatrol_wand` custom-data tag, and the tag is the whole point — the wand is not just
a hint, it is where your NPC selection is stored. Keep it in your main hand for the rest
of this walkthrough. If you drop it, swap it to the off hand, or put it in a chest, every
subcommand below answers `No NPC selected`.

## 3. Select the NPC

Hold the wand and right-click the NPC.

```text
[CheesePatrol] Selected: Village Guard (a1b2c3d4...)
```

That message arrives in chat. The NPC's UUID is now written into the wand, a patrol
record is created for it, and the patrol's dimension is pinned to the world you are
standing in.

If you get a red message on your action bar instead:

```text
[CheesePatrol] Not a valid NPC (not PathAwareEntity).
```

the entity you clicked does not extend `PathAwareEntity`, so it has no navigation to
drive. Armor stands, item frames, boats and villagers-that-are-not-mobs all fail here.
EasyNPC entities pass.

Right-clicking also works as a status check later on: click an NPC that already has a
circuit and the message gains a suffix, `[6 waypoints, inactive]` or a red
`[PATROL ACTIVE — /npcpatrol clear before deleting!]`.

## 4. Mark the circuit

There are two ways to place a waypoint and you will want both.

**With the wand**, right-click the block you want the NPC to stand on. The waypoint is
recorded **one block above** the block you clicked — click the ground, not the air above
it:

```text
[CheesePatrol] Waypoint #1 added at 112 65 -340
```

That one is on your action bar, not in chat. Walk to each corner of the square and click
the ground. While you hold the wand, right-clicking is consumed by CheesePatrol, so you
will not open a door or flip a lever by accident.

**With a command**, standing where you want the waypoint:

```mcfunction
/npcpatrol add
```

```text
[CheesePatrol] Added waypoint #2 at 128 65 -340
```

This uses the block your feet occupy, which for a player standing on the ground is the
air block they are in — exactly where an NPC's feet belong. There is also
`/npcpatrol add <x> <y> <z>` if you prefer typing coordinates, and both forms accept a
trailing wait in seconds.

Place **four** waypoints, one per corner. Two is the minimum a patrol will start with;
three or more make the loop read as a route rather than a pacing animation.

## 5. Check what you built

```mcfunction
/npcpatrol info
```

```text
=== Patrol Info ===
NPC: a1b2c3d4-e5f6-7890-abcd-ef1234567890
Dim: minecraft:overworld
Status: INACTIVE
Speed: 0.5
Radius: 5.0
Waypoints (4):
  #1: 112 65 -340 <-- current
  #2: 128 65 -340
  #3: 128 65 -324
  #4: 112 65 -324
```

Read the list as the running order. The NPC goes #1 → #2 → #3 → #4 and then **back to
#1** — the circuit is a closed loop, not a there-and-back. If your numbers zig-zag across
the square instead of going round it, the order is wrong; `/npcpatrol remove <index>` and
re-add in the right sequence. Removing a waypoint renumbers everything after it.

## 6. See the route before you run it

```mcfunction
/npcpatrol show
```

```text
[CheesePatrol] Particles ON. Run again to hide.
```

The whole circuit is drawn for you alone, refreshed every half second: a green cloud at
waypoint #1, a red cloud at the last one, blue at the ones in between, and a gold line
along every segment — including the closing segment from the last waypoint back to the
first, which is the one people forget exists.

Look along each line. If one of them goes through a building, that segment will not work.
Fix it now, before the NPC spends a minute walking into a wall.

Run the command again to turn the display off.

## 7. Tune the walk

Three settings, each on the selected NPC:

```mcfunction
/npcpatrol speed 1.0
```

```text
[CheesePatrol] Speed set to 1.0
```

`0.5` is the default and is a slow stroll. `1.0` is a brisk walk. The value is clamped to
`0.1`–`5.0` and is not a block-per-second figure — see
[how speed converts to velocity](patrol-circuits.md#speed-is-not-blocks-per-second).

```mcfunction
/npcpatrol radius 6
```

```text
[CheesePatrol] Player detection radius set to 6.0
```

Six blocks, so the NPC notices you as you walk past rather than only when you bump into
it.

```mcfunction
/npcpatrol wait 3 5
```

```text
[CheesePatrol] Waypoint #3 wait set to 5s.
```

The NPC now stands at corner #3 for five seconds each lap. The index is 1-based and
matches what `/npcpatrol info` prints. `0` seconds puts it back to a smooth corner.

## 8. Start it

```mcfunction
/npcpatrol start
```

```text
[CheesePatrol] Patrol started at waypoint #1/4 (NPC kick-started)
```

`(NPC kick-started)` is the part that matters. It means the mod found the entity, wiped
its EasyNPC AI goals, stopped whatever navigation it had, and pushed it at waypoint #1
immediately. If you see `(NPC not found — will start when loaded)` instead, the patrol is
marked active but nothing is moving — the NPC is in an unloaded chunk or a different
dimension than the one the patrol recorded. See
[troubleshooting](troubleshooting.md#start-says-the-npc-was-not-found).

You should see: the NPC turn and set off toward the first corner within a tick or two.

If the NPC was already standing on corner #1 — within 1.5 blocks of its centre — the
kick-start skips it and the message reads `Patrol started at waypoint #2/4`: `start`
never begins by waiting at the point the NPC is standing on.

## 9. Watch it work

Stand back and let it do a full lap. Three behaviours to look for:

- **It rounds the corners.** At a waypoint with no wait, it switches target without
  stopping. At corner #3 it halts for five seconds, then goes again.
- **It closes the loop.** After #4 it heads back to #1 rather than reversing.
- **It notices you.** Walk to within six blocks. It should stop mid-stride and turn its
  head and body toward you, and stay stopped until you step back out. There is no timer
  on this and no line-of-sight check: as long as you are inside the radius, the patrol is
  paused, wall or no wall. Wait timers do not run down while it is looking at you either.

## 10. Give it a trail

```mcfunction
/npcpatrol particles dust_cyan
```

```text
[CheesePatrol] Particle trail set to: dust_cyan
```

Cyan dust now follows the NPC while it moves, three puffs trailing behind it, and settles
to a single puff when it stops. Tab completion lists all 46 effects. If it sits at the
wrong height or clips through the model, nudge it:

```mcfunction
/npcpatrol particleheight 0.4
/npcpatrol particledepth 0.6
```

Positive depth puts the trail behind the NPC, negative puts it ahead. Full reference in
[visuals](visuals.md).

## 11. Optional: plant markers you can see later

```mcfunction
/npcpatrol markers
```

```text
[CheesePatrol] Armor stand markers placed at 4 waypoint(s). Run again to refresh.
```

An invisible, invulnerable armor stand labelled `WP #1` … `WP #4` now floats at each
waypoint, visible to everyone, which makes editing a long route far easier than
re-running `show` each time. Run the command again after changing the circuit to rebuild
them. They are cleaned up automatically when the server stops, and by `/npcpatrol clear`.

⚠️ Markers are permanent entities with name tags visible to players. Take them down
before anyone else logs in — there is no `markers off`, so clear the circuit or kill them
by tag:

```mcfunction
/kill @e[type=armor_stand,tag=cheesepatrol]
```

## 12. Stopping, and what persists

```mcfunction
/npcpatrol stop
```

```text
[CheesePatrol] Patrol stopped.
```

The circuit stays on disk, the NPC stops where it is, and the chunks this patrol was
holding loaded are released. `/npcpatrol start` picks it up again from the waypoint it
was heading for.

Everything you did above was written to `config/cheesepatrol_data.json` as you went —
every `add`, every `speed`, every `wait` saves the whole file immediately, so a crash
loses nothing. Restart the server and the patrol resumes on its own, five seconds after
`SERVER_STARTED`.

What `stop` does **not** do is lift the NPC's protection. It is still unkillable and
still backed up once a minute. To undo the patrol entirely, including that protection,
use `/npcpatrol clear`.

## Where to go next

- [Patrol circuits](patrol-circuits.md) — arrival thresholds, flying segments, and why a
  70-block straight gets teleported
- [Commands](commands.md) — the remaining subcommands, with every failure message
- [Keeping NPCs alive](resilience.md) — what the mod does when the NPC disappears anyway

---

← [Installation](installation.md) · [Commands](commands.md) →
