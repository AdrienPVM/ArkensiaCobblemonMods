[← CheeseVanity](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Controls

**CheeseVanity registers no commands.** There is no `/cheesevanity`, no subcommand, no
admin command, and nothing that takes a permission level. Searching the source for
`CommandRegistrationCallback` or `literal(` finds nothing, because the mod's only
user-facing control is a key press inside an inventory screen.

This page documents that key press the way the other mods in the repository document
their commands, because for a player it plays the same role. Unlike the rest of the
repository, this page is written for **players**, not for admins: every player drives
their own visibility, and no operator privilege is involved anywhere.

---

## The toggle key

```text
=    (equals / plus key, while hovering a slot in an open inventory screen)
```

Open any screen that shows your armor or your Trinkets slots — your own inventory, the
Trinkets side panel, a backpack screen, anything built on vanilla's `HandledScreen` —
put the mouse cursor over a slot, and press `=`.

| Input | Value | Required | Notes |
|---|---|---|---|
| key | `GLFW_KEY_EQUAL` (the `=` / `+` key) | yes | **hardcoded**. It is not a Minecraft key binding and does not appear in Options → Controls, so it cannot be rebound in game |
| hovered slot | any slot the mod can resolve | yes | a Trinkets slot, or one of the four vanilla armor slots in your own inventory |

The key is read in `HandledScreenMixin.onKeyPressed` at the head of
`HandledScreen.keyPressed`. On a slot the mod recognises, the key press is consumed and
never reaches the rest of the screen. On any other slot, or with no slot under the
cursor, the mixin returns immediately and `=` behaves as it normally would.

### Permission

None. Any player, operator or not, can toggle their own slots. A player can only ever
affect their own model: the server uses `context.player()` from the packet as the target
and ignores anything the client might claim, so the payload carries no UUID to forge.

There is no admin override either. Nothing lets an operator hide or unhide another
player's gear, and nothing lets an operator forbid one specific player from using the
mod. The only lever an admin has is the config, which applies server-wide — see
[configuration.md](configuration.md).

### What it does

1. The client resolves the hovered slot to a slot key and sends
   `cheesevanity:toggle_slot` to the server.
2. The server validates the key, flips it in your set, and writes
   `config/cheesevanity_data.json` to disk right away.
3. The server broadcasts your new set to every online player, so everyone running the
   mod re-renders your model.
4. You get an action-bar message confirming the new state.

The full sequence, including who ends up seeing the change, is on the
[who sees what](visibility.md) page.

### Example

Hover the `back` slot of your Trinkets panel, where a cloak sits, and press `=`:

```text
[CheeseVanity] chest/back hidden
```

in red, on the action bar. The slot in the screen now carries a translucent red fill and
a red cross, the cloak disappears from your model in F5, and it disappears for every
other modded player too. The cloak itself is untouched: it is still equipped and still
does whatever it does.

Press `=` on the same slot again:

```text
[CheeseVanity] chest/back visible
```

in green. The overlay and the hiding are both gone.

### Failures

There are no error messages. Every refusal is silent on the client side, which is the
main thing to know when it looks broken.

| What you see | Cause |
|---|---|
| nothing at all, no message | the hovered slot could not be resolved to a slot key — it is neither a Trinkets slot nor one of your four armor slots |
| nothing at all, no message | the key resolved, but the config forbids it: `enableTrinkets` is `false` for a trinket slot, `enableVanillaArmor` is `false` for an armor slot (**this is the default**), or the key is listed in `disabledSlots` |
| nothing at all, no message | the client has the mod and the server does not, so the payload is dropped by the server as an unknown channel |
| the message arrives, the model does not change | the render mixin did not apply. For trinkets this is silent by design, see [troubleshooting.md](troubleshooting.md) |

The second row is the one that catches almost everybody: on a fresh install, pressing `=`
on a helmet does nothing, because `enableVanillaArmor` defaults to `false`.

A refusal does leave a trace, but only in the **server** log:

```text
[CheeseVanity] Slot 'helmet' not toggleable (enableVanillaArmor=false, enableTrinkets=true, isVanillaArmor=true)
```

---

## Reading the slot key of a slot

Slot keys are not documented anywhere in game, and the Trinkets groups and names depend
on which mods your pack installs. The mod prints them, at `INFO` level, in the **client**
log every time you press `=` over a slot:

```text
[CheeseVanity] Key '=' on slot: class=TrinketSlot, index=0, id=46, inv=TrinketInventory, hasStack=true
[CheeseVanity] Resolved slotKey: chest/back
```

That second line is the exact string to put in `disabledSlots`. If `Resolved slotKey` is
`null`, the slot is not one this mod handles and no config change will make it
toggleable.

⚠️ These two lines are debug logging that was left at `INFO` rather than `DEBUG`. On a
busy client they are noise, and on a server that runs a client-side log aggregator they
are a lot of noise. They are harmless, but they are a wart; see
[architecture.md](architecture.md#known-weaknesses).

---

## No other entry point

Unlike the rest of the repository, this mod has no second route to the same action:

- **no commands**, as stated above
- **no CheeseDash module.** CheeseVanity declares no `cheesedash` entrypoint in its
  `fabric.mod.json`, so it never appears in the dashboard sidebar and exposes no REST
  route. There is no `dashboard.md` page for this mod because there is nothing to
  document.
- **no GUI of its own.** The only interface is the red overlay drawn on top of slots that
  are already there.

An admin who needs to reset a player's preferences edits
`config/cheesevanity_data.json` with the server stopped. There is no in-game way to do
it, and no reload: the file is read once at startup.

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
