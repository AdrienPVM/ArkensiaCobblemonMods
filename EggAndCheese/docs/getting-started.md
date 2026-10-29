[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this walkthrough a second player will right-click a hidden egg you placed,
hear the pickup sound, read their own discovery line, and walk away with a decorative egg
whose texture you designed yourself. The dashboard will show one discovery against one
collectible.

Fifteen minutes on a fresh server. You need a server with
[EggAndCheese installed](installation.md), one op account, and a second account that is
**not** op — an admin right-click is not a discovery, so you cannot test the player
experience with your own op account.

## 1. Log in to the dashboard

Open `http://<server>:44311` and log in. Click **Collectibles** in the sidebar — the 🥚
entry under the **EggAndCheese** section.

An empty list and a toolbar. If there is no EggAndCheese section at all, stop here and work
through [troubleshooting](troubleshooting.md#the-dashboard-has-no-eggs-section): nothing
below will work.

## 2. Create the collectible, with the id you actually want

Do this **before** placing anything. Ids are player-visible — the discovery message shows
the id, not the title — and renaming an egg after it is placed leaves an orphan block
behind, which is a real bug in the current code.

In the Collectibles view, press **+ New**. The form asks for a title, a dimension and
coordinates — not an id — so the entry gets an automatic one (`egg_001` on a fresh server).
Coordinates do not matter yet; the form's defaults are `0, 64, 0` in `minecraft:overworld`.
Press **Create** and the dashboard opens the new entry's detail page.

Now give it the id you want: open the **Configuration** tab, replace the id with
`cave_nord` and press **Rename**. Renaming is harmless at this point, because no block
carries the id yet.

What you have is a registry entry with a random DNA and **no block in the world**.
`POST /collectibles` never places anything. The entry is now listed, showing its palette.

⚠️ Leave the coordinates in the **Location** tab alone until the egg is placed. Changing
them — by hand or with **Use my position** — makes the server delete whatever block stands
at the old coordinates (`0, 64, 0` here, so the terrain) and place the egg at the new ones;
see [the dashboard API](dashboard.md#put-collectiblesid).

## 3. Design its appearance

The detail page, now under `cave_nord`, shows the 3D preview on one side and the DNA
fields on the other. Rotate the preview with the mouse.

Change a few fields and watch the preview update:

- set **palette** to `ICE`
- set **pattern** to `CRYSTALS` and **pattern scale** to `2`
- set **emissive** to `STARS` with a density around `60`
- set **stamp** to `MOON`

The form has no rarity field: rarity comes with the random DNA, and only a regeneration
or an API `PUT` carrying `dna.rarity` changes it.

There is no Save button. The form saves itself 1.5 seconds after your last change, as a
`PUT` whose `dna` object carries every field of the form — twelve of the fourteen
components. The two it does not show, `seed` and `rarity`, are kept by the merge. The full
merge rules, and the handful of traps in them, are in
[collectible DNA](dna.md#immutability-and-what-a-put-has-to-do).

⚠️ Do **not** press the **Refresh** button under the DNA code at this point. Despite its
label, it calls `randomize-dna`: it replaces the whole DNA, seed and rarity included, with
a new random one and discards everything you just set. If you want to shuffle before
committing to a look, shuffle first, then tune.

While you are here, open the **Configuration** tab and set a **title** —
`The northern cave` — and a **subtitle**. They are
used for the FTB quest if you enable that later, and they are inert otherwise. They do
**not** change what a player reads on discovery.

Copy the **DNA code** from the detail page somewhere safe. It looks like this:

```text
EAC-5F2A91C30B4D77E8-ICE.CRYSTALS.2.MARBLE.35.STARS.60.EPIC.MOON.ICY.0F.45
```

That one string records the whole appearance, seed included. Nothing in the dashboard reads
it back — there is no field to paste it into — so it is a record, not an import: to restore
a look after an accidental **Refresh**, re-enter its fields by hand, or send them, `seed`
and `rarity` included, in the `dna` object of a `PUT /collectibles/:id`.

## 4. Get the placement item

In game, as your op account:

```mcfunction
/eac give cave_nord
```

```text
Given admin egg for: cave_nord
```

The item in your inventory is an **Admin Egg** carrying this entry's DNA, id and scale.
Held, it renders as the actual 3D egg with your texture — your first confirmation that the
client agrees with the dashboard preview.

If you get `Unknown ID: cave_nord`, check the spelling: the lookup is exact and
case-sensitive, and the argument has no tab completion.

## 5. Hide it

Walk to where the egg belongs and right-click a block face.

```text
Egg 'cave_nord' replaced successfully — position restored.
```

That is the message you want. "Replaced" is the right branch: the item named an existing
id, so the mod bound this block to your entry and wrote the real coordinates into the
registry. If you instead see

```text
Egg created with automatic ID 'egg_001'. Editable from the CheeseDash dashboard.
```

the item had no id — you probably used `/give eggandcheese:admin_egg` rather than
`/eac give cave_nord`. Break that egg, delete the stray entry (`egg_001`, or whatever
number it got) from the dashboard, and go back to step 4.

The egg is now:

- rotating slowly, with no collision — players walk through it,
- unbreakable for anyone who is not an admin, in survival — creative mode and explosions
  still remove it, see [placement and discovery](placement-and-discovery.md#indestructibility),
- waterloggable, so it can sit in a stream.

Refresh the dashboard. `cave_nord` now shows the coordinates you placed it at, and
`positionLost` is false.

## 6. Size it to its hiding place

Scale is in the **Location** tab. Set it to `1.4`; the tab saves itself.

⚠️ The registry takes the new value, but the egg in the world does **not** change. The
Location tab always sends the coordinates along with the scale, and the API only syncs the
scale onto the block in place when the `PUT` carries no `x`, `y`, `z` or `dimension` — a
bug in the current code. To resize the placed egg, send the scale on its own:

```bash
curl -X PUT -H "Authorization: Bearer <token>" -d '{"scale":1.4}' \
  http://localhost:44311/api/v1/eggs/collectibles/cave_nord
```

or place the egg again from a fresh `/eac give cave_nord`, whose item carries the
registry's scale.

Scale is clamped to 0.1–4.0. A `PUT` with `10` returns `200` with `4.0`, silently, so read
the value back.

If this egg sits on a plaza and must stay sharp from a distance, set **Render distance**
in the **Configuration** tab to `0`, which means "never degrade this one". `-1` means
"follow the server's global distance". That field does reach the placed block immediately.

## 7. Discover it, as a player

Log in with the **non-op** account and right-click the egg.

```text
You discovered: cave_nord!
```

in the action bar, the experience-orb pickup sound, and a **Collectible Egg** in the
inventory with your texture and a tooltip reading its pattern and palette.

Right-click again:

```text
You already found this one!
```

Each egg counts once per player.

Now check the dashboard: `cave_nord` shows one discovery. Open its discoveries list and
you will see the player's name, resolved from the server's user cache.

If nothing happened at all, the likely causes are in order of frequency: you tested with
an op account (admins take a different branch entirely), the block is not bound to the
entry, or you are looking at a different egg. See
[troubleshooting](troubleshooting.md#a-player-right-clicks-an-egg-and-nothing-happens).

## 8. Put the reward on display

As the player, right-click a block face with the decorative egg. It places as a breakable
decorative block carrying the same DNA; breaking it drops the item back.

For something nicer, craft a pedestal — a column of two plus a base of three, in any of 23
materials:

```text
 X
 X
XXX
```

Place it, right-click it with the decorative egg, and the egg sits on top, spinning. Then
tune it while looking at it, from up to 5 blocks away:

```mcfunction
/pedestal scale 2.0
/pedestal pillar 2.0
/pedestal spin
```

```text
Display scale set to 2.0.
Pillar height set to 2.0.
Spinning disabled.
```

⚠️ `/pedestal` has **no permission requirement**. Any player can retune any pedestal they
can stand in front of. If that is a problem on your server, gate the command in your
permissions mod.

## 9. Check integrity before you build out

You have one egg. Press **Integrity** in the Collectibles view anyway, so you know what a clean
report looks like:

```text
Integrity OK — 1 loaded egg blocks, no duplicates or orphans.
```

Come back to this button whenever eggs behave strangely — two identical eggs, a block that
does nothing when clicked, a dashboard entry whose coordinates are empty. It finds
duplicates and orphans in **loaded chunks** and repairs them by unbinding, never by
deleting. [Integrity](integrity.md) has the full procedure, including the walk-the-area
caveat.

## 10. Scale up to a real hunt

The loop for egg two onwards is steps 2 through 5: create the entry, rename it to its final
id, design the DNA, `/eac give <id>`, place it. Fifteen eggs is about an hour.

Two shortcuts once you know what you are doing:

- **Place first, tidy later.** Right-clicking a bare `eggandcheese:admin_egg` creates an
  entry with an auto-generated id (`egg_001`, `egg_002`, …) and a random DNA on the spot.
  Faster for a large hunt, at the cost of ids a player will read as `egg_014`.
- **Reuse a look.** The DNA code from step 3 lists an entire appearance, field by field.
  Copy those values into another egg's DNA form to give a set of eggs a family
  resemblance — by hand, since the dashboard has no import for the code itself.

### Wiring it to FTB Quests

If your pack has FTB Quests, tick **FTB Quests** in the Collectibles view's
**Configuration** tab (that is `ftbQuestsEnabled`) and choose a chapter name. From then on, creating an egg creates a quest with the egg as
its icon, and a discovery completes it.

For eggs you created before enabling it, run a sync once — the **Reload** button in that
panel — but read
[the FTB page](ftb-quests.md#full-sync) first, because a full sync deletes and recreates
every egg quest and players lose their ticks.

If you would rather not let the mod write into your quest file, leave the setting off and
point an Advancement task at an advancement of your own built on the
`eggandcheese:egg_found` trigger — the one the mod ships is never loaded, see
[the advancement](display.md#the-advancement). That route survives FTB
Quests updates; the reflection hook will not.

---

← [Installation](installation.md) · [Commands](commands.md) →
