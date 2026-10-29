[← CheeseVanity](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this page you will have hidden one trinket on your own character, seen it
disappear in third person, confirmed that another player sees the same thing, and brought
it back. The whole thing takes about three minutes.

This page is written for **players**. The rest of this repository documents admin tools;
this mod is the exception, and the sections that need a server operator are marked as
such. If you are the admin and you are setting the mod up for the first time, do step 0
and then hand the rest to anyone who wants to test it.

---

## 0. For the admin: make something toggleable

Skip this if you are a player on a server that already runs the mod.

The stock config allows Trinkets slots and forbids vanilla armor. If your pack has
Trinkets, you are already done. If it does not, nothing is toggleable at all and the mod
will look broken, so edit `config/cheesevanity.json` on the server:

```json
{
  "enableVanillaArmor": true,
  "enableTrinkets": true,
  "disabledSlots": []
}
```

Restart the server. The config is read once at startup and never reloaded. Confirm from
the log:

```text
[CheeseVanity] Loaded — vanilla armor: true, trinkets: true
```

If the line still says `false`, the file you edited is not the one the server read — see
[the config path](configuration.md).

---

## 1. Check the mod is on your client

Join the server and open your inventory. Nothing about the screen looks different yet;
the mod adds no button and no tab.

The mod announces itself in the log rather than in the interface. Open your client log
(`.minecraft/logs/latest.log`) and look for:

```text
[CheeseVanity] Client initialized
```

If that line is missing, the jar is not in your client's `mods/`. Hiding happens entirely
on your client, so without it nothing on this page will work, however well the server is
configured.

---

## 2. Equip something you want to hide

Put a trinket in a slot — a cloak in `chest/back`, a hat in `head/hat`, a ring,
whatever your pack provides. If you are testing armor instead, put on a helmet.

Press `F5` twice to get an over-the-shoulder view of your own character, and look at it.
This is your reference image; you want to be able to tell the difference in a moment.

---

## 3. Find out what the slot is called

Go back to your inventory, hover the slot with the item in it, and press `=`.

Open your client log. Two lines appeared:

```text
[CheeseVanity] Key '=' on slot: class=TrinketSlot, index=0, id=46, inv=TrinketInventory, hasStack=true
[CheeseVanity] Resolved slotKey: chest/back
```

That second value, `chest/back`, is the **slot key**. It is the only name the mod ever
uses for a slot, and you will need it if you ever want to ask an admin to block or unblock
a specific slot.

If `Resolved slotKey` says `null`, that slot is not one the mod understands. Try another
one. If no lines appeared at all, go to
[troubleshooting.md](troubleshooting.md#pressing--does-nothing-and-the-client-log-is-silent).

---

## 4. Read the confirmation

The same key press already did the work. Look at your action bar, just above the hotbar:

```text
[CheeseVanity] chest/back hidden
```

in red. The server accepted the toggle, saved it, and told every other player about it.

The slot in your inventory screen now has a translucent red square and a red cross drawn
over the item. The item is still there and still equipped. The cross means "this is
hidden on my model", not "this is disabled".

If you got no message at all here while step 3 printed a real slot key, the server refused
the toggle — almost always because the config does not allow that kind of slot. Step 0
covers it.

---

## 5. Look at yourself

Close the inventory and press `F5` twice again.

The cloak is gone. Your character looks like it is wearing nothing in that slot. Check
your armor points on the HUD if you hid a chestplate: they have not moved. The equipment
is working, it is only invisible.

This is the part people get wrong, so to be explicit: the change is visible **in third
person for you, and on your model for every other player who has the mod installed**. It
is not a private preview. A player connecting without CheeseVanity in their `mods/` still
sees your cloak, and nothing can be done about that from the server.
[Who sees what](visibility.md) explains the boundary in full.

---

## 6. Have someone else confirm it

Ask another player who has the mod to look at you. They see the same thing you do: no
cloak.

Then have them disconnect and reconnect. They still see no cloak, because the server
sends every player's hidden slots to anyone who joins. This is the step that proves the
preference is server-side rather than a local trick.

---

## 7. Log out and come back

Disconnect. Reconnect. Press `F5`.

Still hidden. Your preference was written to `config/cheesevanity_data.json` on the
server the moment you pressed `=`, and it is read back at every server start. It survives
your disconnect, a server restart, and a change of items in that slot — put a different
cloak in `chest/back` and it is hidden too, because the preference belongs to the slot.

It does **not** follow you to a different server. Each server keeps its own file.

---

## 8. Bring it back

Hover the same slot and press `=` once more:

```text
[CheeseVanity] chest/back visible
```

in green. The red cross is gone, the cloak is back on your model, and the key has been
removed from your stored set. A player with no hidden slots at all is dropped from the
data file entirely, so you leave nothing behind.

---

## Where to go next

- [Who sees what](visibility.md) — the sync model, and the exact limits of the hiding
- [Controls](commands.md) — the key press documented in full, with every silent failure
- [Configuration](configuration.md) — for the admin: the three keys, and how to protect a
  slot like a backpack from being hidden

---

← [Installation](installation.md) · [Controls](commands.md) →
