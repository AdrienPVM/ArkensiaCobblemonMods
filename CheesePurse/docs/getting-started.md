[← CheesePurse](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this page you will have a server running CheesePurse, a client that draws
the button, and a player whose bronze coins leave the hotbar on their own and land in
the Numismatic Overhaul purse with an action-bar notification. It takes about ten
minutes, and most of that is Gradle.

The walkthrough assumes a blank Fabric 1.21.1 server and a Fabric 1.21.1 client. No
other mod in this repository is involved.

---

## 1. Get Numismatic Overhaul in place first

Download [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) for 1.21.1
along with whatever it lists as its own dependencies — at the time of writing, Cardinal
Components and owo-lib. Put them in the server's `mods/` and in your client's `mods/`,
next to Fabric API.

Start the server and join it once, before touching CheesePurse. Press E and look at the
bottom-right of the inventory screen: Numismatic draws a purse readout there. That
confirms the dependency works on its own.

Doing this first matters. If something is wrong with the Numismatic install, you want to
find out now rather than while blaming a 260-line addon.

## 2. Build CheesePurse

```bash
cd CheesePurse
./gradlew build
```

The build resolves Numismatic from the Modrinth Maven repository, so the first run needs
a network connection. When it finishes:

```text
CheesePurse/build/libs/numismatic-cheesepurse-1.0.0+1.21.1.jar
```

That is the file. The `-sources.jar` next to it is not a mod.

If Gradle fails on an unresolved `maven.modrinth:numismatic-overhaul`, see
[troubleshooting](troubleshooting.md#the-build-fails-on-mavenmodrinthnumismatic-overhaul).

## 3. Install it on both sides

Stop the server. Copy the jar into:

- the server's `mods/`
- your client's `mods/`

Both. The server half runs the deposit loop and the client half draws the button, and
neither is any use alone.

Start the server. The log shows:

```text
[NumismaticCheesePurse] Initializing…
[NumismaticCheesePurse] Ready.
```

Two lines, adjacent. If the server stops instead with a message about a missing
dependency `numismatic-overhaul`, step 1 did not actually happen on the server side.

## 4. Find the button

Join the server and press E.

A 12×12 coin icon appears near the top-left of the inventory window, just above the
player model, at `guiLeft + 27, guiTop + 9`. It is grey.

Hover it:

```text
Cheesepurse: OFF
Click to automatically deposit picked-up coins into your purse.
```

In a French client the same tooltip reads:

```text
Cheesepurse : DÉSACTIVÉE
Cliquez pour déposer automatiquement les pièces dans votre bourse.
```

No icon at all means the client does not have the jar, or the mixin failed to apply.
[What to check](troubleshooting.md#there-is-no-button-in-my-inventory-screen).

## 5. Get some coins, with the toggle still off

Give yourself something to work with:

```mcfunction
/give @s numismatic-overhaul:bronze_coin 64
```

64 bronze coins land in your inventory and stay there. The toggle is off, so nothing
touches them. Leave your inventory open and watch for a few seconds to be sure: this is
your control case.

## 6. Turn it on

Click the coin icon.

Three things happen, in this order:

1. The texture changes to the gold coin.
2. The tooltip becomes `Cheesepurse: ON` / `Cheesepurse : ACTIVÉE`.
3. The client sends a `ToggleAutoCollectPayload(true)` on the
   `numismatic-cheesepurse:toggle_auto_collect` channel, and the server adds your UUID
   to its set.

Within half a second — ten server ticks at most — the 64 bronze coins vanish from your
inventory and Numismatic puts a notification in your action bar:

```text
+ [64 Bronze]
```

Your purse readout, bottom-right of the inventory screen, goes up by 64.

The delay is the whole point to understand here: the sweep is not triggered by picking
anything up. It is a timer. See [the collection loop](auto-collect.md#the-loop).

## 7. Prove it works on a real pickup

With the toggle on, a `/give` followed by a throw does not work: the given coins are swept
into the purse within half a second, before you can drop them. Put the coins on the ground
directly instead, a few blocks away:

```mcfunction
/summon item ~ ~ ~3 {Item:{id:"numismatic-overhaul:silver_coin",count:10}}
```

Walk into the stack. The coins enter your inventory normally — vanilla pickup, which
CheesePurse does not touch — and disappear again on the next sweep, with `+ [10 Silver]`
in the action bar.

If you are quick enough with your inventory open, you can watch the stack sit in a slot
for a tick or two before it goes. That window is real and it is up to half a second
long.

## 8. Watch the server see it

Raise the level of the `numismatic-cheesepurse` logger — the mod creates it with
`LoggerFactory.getLogger(MOD_ID)`, so it is named after the mod id, not the package — to
`DEBUG` and the server narrates every step:

```text
[NumismaticCheesePurse] Auto-collect ENABLED for Steve
[NumismaticCheesePurse] Collected 64 bronze units for Steve
[NumismaticCheesePurse] Collected 1000 bronze units for Steve
```

The numbers are in bronze units, Numismatic's base denomination: 1 bronze, 100 per
silver, 10 000 per gold. Ten silver coins log as `1000`, not `10`.

## 9. Turn it off, and understand what "off" means

Click the icon again. The texture goes back to grey, the tooltip back to `OFF`, and the
client sends `ToggleAutoCollectPayload(false)`. The server removes your UUID and the
loop skips you from the next tick onward.

Coins already in the purse stay there. Nothing is refunded and nothing is reversible by
toggling — `/give` yourself coins again if you want your control case back.

One thing to try before you move on, because it is the single behaviour that catches
players out. Turn the toggle **on**, open the Numismatic purse screen, and withdraw
coins from it. They appear in your inventory and are gone again within half a second,
straight back into the purse. The loop does not know or care where a coin came from.
That is [the purse-extraction trap](auto-collect.md#the-purse-extraction-trap), and the
only fix is to turn the toggle off before withdrawing.

---

## What to read next

- [Auto-collect](auto-collect.md) explains what the loop counts as a coin, what happens
  when an inventory is full, and why the toggle resets on restart.
- [Configuration](configuration.md) covers the fact that there is nothing to configure
  and what you would have to edit instead.

---

← [Installation](installation.md) · [Auto-collect](auto-collect.md) →
