[← CheeseVanity](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

Almost every report about this mod is "pressing `=` does nothing", and that one symptom
has five different causes. The mod gives the player no error message for any of them, so
the diagnosis is always the same: find out how far down the chain the toggle got.

The chain is: key press → slot resolved → packet sent → server accepts → server
broadcasts → client renders. Each symptom below is one of those links breaking.

---

## The mod does not load at all

**Cause.** Fabric Loader refused the jar. The usual reasons: Fabric API missing, loader
older than 0.16.0, Minecraft not 1.21.1, two copies of `cheesevanity-*.jar` in `mods/`,
or Java older than 21.

**Fix.** Read the loader error at the top of `logs/latest.log`; it names the unsatisfied
dependency. The required set is in
[installation.md](installation.md#prerequisites). Delete every older
`cheesevanity-*.jar` before copying a new one — same mod id twice is a hard stop.

---

## Pressing `=` does nothing and the client log is silent

**Cause.** `HandledScreenMixin` never ran. Either the mod is not in the **client's**
`mods/` folder, or `cheesevanity.mixins.json` failed to apply, or another mod consumed the
key press before vanilla's `keyPressed` was reached.

**Fix.** Confirm `[CheeseVanity] Client initialized` is in the client log. If it is
missing, the jar is server-side only — install it on the client too. If it is present and
the slot lines still never appear, search the client log for `Mixin apply failed
cheesevanity.mixins.json`; that is a mod conflict on `HandledScreen` and it needs the
conflicting mod's name from the stack trace to go further.

---

## The log says `Resolved slotKey: null`

**Cause.** The hovered slot is not one of the two kinds the mod understands. Only
`dev.emi.trinkets.TrinketSlot` instances and the four armor indices of a
`PlayerInventory` (36 to 39) resolve to a key. A slot in a chest, a hotbar slot, a
crafting slot, or an accessory slot provided by some other API returns `null`.

**Fix.** There is nothing to configure here — a `null` slot cannot be made toggleable
without a code change to `resolveSlotKey`. Hover a slot the mod handles instead. If the
slot you want is a Trinkets slot and still resolves to `null`, check that Trinkets is
installed on the **client**: without it the reflection branch is skipped entirely and only
armor resolves.

---

## The slot key resolves but no action-bar message appears

**Cause.** The server rejected the toggle, or never got it. Three possibilities, in
descending order of likelihood:

1. The config forbids that kind of slot. `enableVanillaArmor` is `false` by default, so
   every armor slot is refused on a fresh install.
2. The slot key is listed in `disabledSlots`.
3. The server does not have the mod, so the `cheesevanity:toggle_slot` channel is unknown
   and the payload is discarded.

**Fix.** Look in the **server** log for the refusal, which is printed in full:

```text
[CheeseVanity] Slot 'helmet' not toggleable (enableVanillaArmor=false, enableTrinkets=true, isVanillaArmor=true)
```

That line tells you which of the first two cases you are in. No line at all, and no
`[CheeseVanity] Loaded` line either, means the third. Edit
`config/cheesevanity.json` and **restart** — see the next symptom.

---

## I edited the config and nothing changed

**Cause.** The config is read exactly once, in `CheeseVanityMod.onInitialize`. There is no
reload command, no file watcher, and `/reload` does not touch it.

**Fix.** Restart the server, then confirm the values from the startup line:

```text
[CheeseVanity] Loaded — vanilla armor: true, trinkets: true
```

If those values still do not match your file, you edited a different file. The path is
relative to the server's working directory, and a client writes its own useless copy under
`.minecraft/config/`. [configuration.md](configuration.md) has the detail.

---

## I turned a setting off and slots are still hidden

**Cause.** `isSlotToggleable` gates **writes**, not reads. Whatever is already in
`config/cheesevanity_data.json` keeps being broadcast and keeps being rendered, no matter
what the config now says. Adding a key to `disabledSlots` likewise prevents further
toggling of it and un-hides nothing.

**Fix.** Stop the server, edit `config/cheesevanity_data.json`, remove the offending keys
from the players' arrays (or delete a player's entry entirely), start the server. Do not
edit it while the server is running: the next toggle by anyone rewrites the whole file
from memory and your change is gone.

---

## The message says `hidden` but the item still renders

**Cause.** The server side worked and the render side did not. For armor that means
`ArmorFeatureRendererMixin` did not apply, which would also have produced a mixin error in
the client log. For trinkets it is more likely and quieter:
`TrinketFeatureRendererMixin` injects with `require = 0`, so if
`TrinketFeatureRenderer.render` no longer matches the expected signature the mixin applies
with **zero** injections and says nothing.

**Fix.** Check the Trinkets version. The mod is compiled against `dev.emi:trinkets:3.10.0`
and the injection targets that method shape; a Trinkets update can break it without
breaking anything else. The symptom is specific and recognisable: the action-bar message
and the red cross both work, only the model is unchanged. There is no workaround short of
rebuilding the mixin against the new Trinkets.

---

## Other players still see my gear

**Cause.** Those players do not have CheeseVanity in their client `mods/`. The hiding is
performed by client-side render mixins; a client without the mod has nothing to hide with.
The server never unequips or removes anything, so there is no server-side fallback.

**Fix.** Make CheeseVanity a required client mod on your server. If your pack is
distributed through a launcher or an auto-sync system, add it there. There is no
configuration that makes an unmodded client cooperate. This is a design limit, documented
on [the visibility page](visibility.md#the-exact-boundary).

---

## Someone looks partly invisible on a server that does not run the mod

**Cause.** A stale client cache. `VanityClientState` is never cleared — the `clear()`
method exists but nothing calls it, and there is no disconnect hook. Join server A where
your gear is hidden, disconnect, join server B in the same game session, and B's client
still holds A's entries.

**Fix.** Restart the game client. That is the only reliable reset. If you maintain the
mod, the fix is a `ClientPlayConnectionEvents.DISCONNECT` handler calling
`VanityClientState.clear()`; it is listed under
[known weaknesses](architecture.md#known-weaknesses).

---

## My preferences disappeared

**Cause.** Either `config/cheesevanity_data.json` was lost or replaced, or you are on a
different server. The file is per server installation and keyed by player UUID; nothing is
shared between servers, and a proxy network with separate backends keeps separate files
unless the servers share a `config/` directory.

**Fix.** Restore the file from a backup with the server stopped. Check the log for
`[CheeseVanity] Failed to load data`, which means the file existed but could not be
parsed — in that case the in-memory map starts empty, and the first toggle afterwards
overwrites the broken file, so recover it before letting anyone play.

---

## The dashboard has no CheeseVanity module

**Cause.** There is no module. CheeseVanity declares no `cheesedash` entrypoint in its
`fabric.mod.json` and exposes no REST route. Its absence from the sidebar is correct
behaviour, not a failure.

**Fix.** None needed. Administration is the config file and the data file, both edited on
disk with the server stopped.

---

## Reporting a bug

The report that gets fixed quickly contains four things.

1. **The client log**, `.minecraft/logs/latest.log`. Attach the whole file if you can. The
   lines that matter are `[CheeseVanity] Client initialized`, every
   `[CheeseVanity] Key '='` and `Resolved slotKey` line around the failure, and any
   `Mixin apply failed` line anywhere in the file.
2. **The server log**, `logs/latest.log`. The `[CheeseVanity] Loaded — vanilla armor: ...,
   trinkets: ...` line proves which config was in effect, and any `not toggleable` line
   explains a silent refusal.
3. **`config/cheesevanity.json`**, verbatim. Three keys, so paste the whole thing.
4. **Which link in the chain broke** — key press, slot resolved, message received, model
   changed, other players agree. Saying "pressing `=` resolves `chest/back`, I get the red
   message, the model does not change" turns five candidate causes into one.

Also state whether Trinkets is installed and at which version, and whether the player who
cannot see the change has the mod themselves. Those two questions account for most
reports.

Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) ·
Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)

---

← [Architecture](architecture.md) · [CheeseVanity](../README.md) →
