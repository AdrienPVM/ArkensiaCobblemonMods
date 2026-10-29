[← CheeseItems](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this walkthrough a player on your server will be holding a glowing
`Lantern of Arkensia` with its own PNG icon, which plays a sound and teleports them three
seconds after a right-click, and will own a morpho they can wear with a keypress. Nothing
below requires restarting the server after the first launch.

You need a 1.21.1 Fabric server with CheeseDash and CheeseItems in `mods/`
([installation](installation.md)), operator rights in game, and the CheeseDash token so
you can log into the dashboard at `http://<host>:44311`.

## 1. Confirm the mod answers

```mcfunction
/cheeseitem list
```

```text
No items defined.
```

If you get *Unknown or incomplete command* instead, stop here and check
[troubleshooting](troubleshooting.md#the-cheeseitem-command-does-not-exist).

## 2. Set the resource pack host

Open `config/cheeseitems.json` and set the one key that is real configuration:

```json
"resourcePackHost": "play.example.net:44311"
```

Use the address and CheeseDash port your **players** reach, not `localhost`. Then:

```mcfunction
/cheeseitem reload
```

```text
CheeseItems config reloaded.
```

Skipping this step does not break anything you do today if you are testing from the same
machine. It breaks custom icons for everyone else. [Why](configuration.md#resourcepackhost).

## 3. Create the item from the dashboard

Log into the dashboard, open **CheeseItems** 🎁 in the sidebar, and create an item. The
editor writes the JSON for you, but it is worth knowing what it produces, so here is the
same thing through the API:

```bash
curl -s -X POST http://localhost:44311/api/v1/items \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "lantern_of_arkensia",
    "displayName": "Lantern of Arkensia",
    "nameColor": "#F5A623",
    "baseItem": "minecraft:soul_lantern",
    "lore": ["A quest item. It remembers the way."],
    "glowing": true,
    "maxStackSize": 1,
    "cooldownTicks": 200
  }'
```

```json
{"id":"lantern_of_arkensia","displayName":"Lantern of Arkensia", … }
```

The response is the full definition, with every field you did not send filled in from the
defaults. The file on disk is already updated — item writes are synchronous.

Check it from in game:

```mcfunction
/cheeseitem list
```

```text
--- CheeseItems (1) ---
 [ON] lantern_of_arkensia — Lantern of Arkensia (minecraft:soul_lantern)
```

⚠️ Get `baseItem` right. An identifier that does not exist resolves to `minecraft:air` and
`give` will cheerfully report success while handing the player nothing.

## 4. Give it to yourself and look at it

```mcfunction
/cheeseitem give Steve lantern_of_arkensia
```

```text
Gave 1x Lantern of Arkensia to Steve
```

You are holding a soul lantern named *Lantern of Arkensia* in amber, with one grey lore
line and an enchantment glint. Right-clicking does nothing yet: there are no actions on it.

## 5. Attach an action

Back in the editor, add a **use action**. Through the API, a `PUT` merges into the existing
definition — only the keys you send are touched:

```bash
curl -s -X PUT http://localhost:44311/api/v1/items/lantern_of_arkensia \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "useActions": [{
      "command": "playsound minecraft:block.beacon.activate master {player}",
      "count": 1,
      "feedback": "The lantern answers.",
      "delayCommand": "tp {player} 148 64 -512",
      "delaySeconds": 3,
      "showTimer": true,
      "timerLabel": "Shrine"
    }]
  }'
```

Right-click the lantern you are already holding. No reload, no re-give: `useActions` are
not stored on the stack, so the change applies to every copy in the world at once.

What you see, in order: the beacon sound, *The lantern answers.* in the actionbar, then
`Shrine: 3s`, `Shrine: 2s`, `Shrine: 1s`, and then you are at 148 64 -512.

⚠️ `{player}` is the only placeholder, and the commands run **as the console at
permission level 4**. Anyone who can right-click this item can run that command. Read
[useActions](items.md#useactions) before putting anything destructive in there.

Right-click again straight away and you get `Cooldown: 9.8s`, because step 3 set
`cooldownTicks: 200`.

## 6. Give it an icon

In the item editor, upload a 16×16 (or any square) PNG. Or:

```bash
curl -s -X POST http://localhost:44311/api/v1/items/lantern_of_arkensia/image \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"imageBase64\":\"$(base64 -w0 lantern.png)\"}"
```

```json
{"uploaded":true}
```

Three things happen on the server:

```text
[CheeseItems] Resource pack generated: 1 items, 1204 bytes, SHA1=…
[CheeseItems] Pack changed — pushing to 1 online players
[CheeseItems] Image uploaded for item: lantern_of_arkensia
```

Your client is prompted to download *Custom item icons for Cacademy*. Accept it. The
lantern in your hand turns into your PNG — the inventory refresh runs with the upload, so
you do not need to re-give the item.

If the icon does not appear, the usual cause is `resourcePackHost` pointing somewhere your
client cannot reach. [Checklist](troubleshooting.md#custom-icons-do-not-show-up).

## 7. Create a morpho

The second half of the mod. Open **Morphos** 🧬 in the sidebar, or:

```bash
curl -s -X POST http://localhost:44311/api/v1/morphos \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Ditto","species":"ditto","isNew":true,"terrestrial":true}'
```

```json
{"id":"7c1f90ab","title":"Ditto","species":"ditto", … }
```

Note the generated `id` — you need it for the next step. `species` is what gets passed to
CobbleShift, so it has to be a species `cshift` accepts.

Upload an image for it the same way as the item icon
(`POST /api/v1/morphos/7c1f90ab/image`) if you want it to show in the Codex with artwork
rather than a placeholder.

## 8. Grant it and wear it

```mcfunction
/cheeseitem morpho grant Steve 7c1f90ab
```

```text
Morpho Ditto granted to Steve.
```

and the player sees `🧬 Ditto added to your Morpho Codex!`.

Press `J`. The Codex opens, showing one card. Since it is the first morpho this player
owns, it is already the active one. Press `H`:

```text
Transformation: Ditto
```

The player is a Ditto, an endurance bar appears in the actionbar, and after 50 seconds at
the default drain rate it empties and reverts them automatically. Press `H` again before
that to revert manually.

If `H` says *Transformation: Ditto* but nothing visibly changes, CobbleShift is not
installed or `cshift` rejected the species. The log has the detail:

```text
[CheeseItems] cshift command failed: cshift transform Steve ditto (…)
```

See [morphos](morphos.md#wearing-a-morpho) — the mod cannot tell whether the transform
actually worked.

## 9. Hand the item out properly

For a quest, you rarely want to type `give` yourself. Two patterns:

- An NPC or a function runs `cheeseitem give <player> <id>`, which needs permission level
  2 — run it from a command block or a function, both of which execute at level 2 or
  higher.
- The dashboard's give button, which calls `POST /api/v1/items/:id/give` with a player
  name.

And to make an item grant a morpho when used, put the grant in its `useActions`:

```json
{"command": "cheeseitem morpho grant {player} 7c1f90ab", "count": 1, "feedback": "A new form."}
```

That is the whole loop: a dashboard-authored item that, on right-click, gives a
dashboard-authored morpho. Neither needed a restart, and neither required writing Java.

## Where to go next

- Every item field, and the six that do nothing: [custom items](items.md)
- How the pack is built and what it overwrites: [resource pack](resource-pack.md)
- The catalog, the Codex and the endurance pool: [morphos](morphos.md)
- ⚠️ Before you hand dashboard accounts to anyone else: read
  [the missing role guards](dashboard.md#authentication-and-role-guards)

---

← [Installation](installation.md) · [Commands](commands.md) →
