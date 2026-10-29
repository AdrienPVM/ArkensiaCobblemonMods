[← CheeseVanity](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

Ten Java files, around 630 lines, no dashboard module, no commands, no registry entries.
The whole mod is a set of strings per player UUID, a two-packet protocol to move it
around, and three client mixins that act on it. Reading the source end to end takes
twenty minutes, and this page exists mostly to point out the parts that are not obvious
and the parts that are weak.

---

## Package layout

```text
com.cheesevanity
├── CheeseVanityMod.java              main entrypoint — server side, 94 lines
├── CheeseVanityClient.java           client entrypoint — one packet receiver, 20 lines
├── VanityConfig.java                 the three config keys + the toggleability rule
├── VanityData.java                   server-side store, UUID -> set of slot keys
├── VanityClientState.java            client-side cache of the same shape
├── network
│   ├── ToggleSlotC2SPayload.java     C2S: one string, the slot key
│   └── SyncHiddenSlotsS2CPayload.java S2C: a UUID and its full set of keys
└── mixin.client
    ├── HandledScreenMixin.java       the '=' key, the slot-key resolver, the red overlay
    ├── ArmorFeatureRendererMixin.java cancels armor rendering for hidden vanilla slots
    └── TrinketFeatureRendererMixin.java empties hidden trinket slots around the render
```

Two mixin configs, both listing only `client` mixins:

| File | `required` | `defaultRequire` | Contents |
|---|---|---|---|
| `cheesevanity.mixins.json` | `true` | `1` | `HandledScreenMixin`, `ArmorFeatureRendererMixin` |
| `cheesevanity-trinkets.mixins.json` | `false` | `0` | `TrinketFeatureRendererMixin` |

The split is what makes Trinkets a genuine optional dependency. The second config is not
required and its injections are not required, so when Trinkets is absent the mixin fails
to apply and Loader carries on without a word.

---

## What each class owns

**`CheeseVanityMod`** owns the server. In `onInitialize` it loads the config and the data
file, registers both payload types, registers the C2S receiver, and hooks
`ServerPlayConnectionEvents.JOIN`. It also holds the static config instance and the
`Logger` named `CheeseVanity` that every other class logs through.

**`VanityData`** is the single source of truth. A `ConcurrentHashMap<UUID, Set<String>>`
where the sets are `ConcurrentHashMap.newKeySet()`. `toggle` flips membership and returns
the new state, `getHidden` can return `null` for a player with nothing hidden, `getAll`
hands out an unmodifiable view of the map but **not** of the sets inside it.

**`VanityConfig`** holds the three keys and the decision procedure. `isSlotToggleable`
checks `disabledSlots` first, then branches on `isVanillaArmorSlot`, a static string
comparison against the four hardcoded words.

**`VanityClientState`** mirrors the data shape on the client. `updatePlayer` removes the
entry entirely when the incoming set is empty, which is how an un-hide is expressed: the
server never sends a "clear" instruction, it sends an empty set.

**`HandledScreenMixin`** does three different jobs, which is one more than a class should
have: it reads the key press, it owns `resolveSlotKey` (the only place a `Slot` becomes a
slot key), and it draws the overlay.

**`ArmorFeatureRendererMixin`** and **`TrinketFeatureRendererMixin`** are the render side,
and they take opposite approaches. See below.

---

## State and when it reaches disk

| State | Lives in | Written when |
|---|---|---|
| config | `VanityConfig` static instance | on first launch only, when the file is absent or unparseable |
| hidden slots, server | `VanityData.hiddenSlots` | `VanityData.save()` on **every** toggle |
| hidden slots, client | `VanityClientState.hiddenSlots` | never — memory only |

`save()` serialises the entire map and rewrites `config/cheesevanity_data.json` whole,
with pretty printing, on the server thread, synchronously, inside the toggle handler.
With a handful of players that is a few hundred bytes and nobody notices. With a thousand
stored players and someone spamming `=`, it is a full file rewrite per key press on the
main thread. There is no debounce, no dirty flag, and no save on shutdown — which is
consistent, since every mutation is already persisted the moment it happens.

Players with an empty set are skipped during serialisation, so the file self-cleans.

---

## Threading

Three threads matter.

**The render thread** reads `VanityClientState` from both render mixins and from
`drawSlot`. Reads only, no locking, backed by a `ConcurrentHashMap`.

**The netty client thread** receives the S2C payload and immediately hands off with
`context.client().execute(...)`, so the cache is mutated on the client main thread rather
than under the renderer.

**The server network thread** receives the C2S payload. It validates the slot key inline —
null, empty, longer than 64 characters, not toggleable — and only then hands off with
`context.server().execute(...)`. Everything that touches `VanityData`, the player's
message and the broadcast runs on the server thread. The validation happening off-thread
is safe because it only reads immutable config.

The one threading oddity is in the trinkets mixin, covered next.

---

## The two render strategies

`ArmorFeatureRendererMixin` injects at the head of `ArmorFeatureRenderer.renderArmor`,
maps the `EquipmentSlot` to one of the four armor keys, and calls `ci.cancel()` if that
key is hidden for `entity.getUuid()`. Clean, read-only, cheap: one map lookup per armor
layer per entity per frame.

`TrinketFeatureRendererMixin` cannot do that, because `TrinketFeatureRenderer.render`
iterates the component's inventories itself and there is no per-slot method to cancel.
So it does something else: at `HEAD` it walks the `TrinketComponent`, saves every stack
in a hidden slot into a `ThreadLocal<Map<String, ItemStack>>` keyed `group/name#index`,
replaces it with `ItemStack.EMPTY`, and at `TAIL` puts them all back.

That works, and it is honest about being a trick in its own class comment. The costs:

- it **mutates live inventory objects** during rendering. `TrinketInventory.setStack` is
  not a view operation, and anything that observes the inventory while a render is in
  flight sees empty slots.
- if anything between `HEAD` and `TAIL` throws, `TAIL` never runs and the player's
  trinkets stay gone until the next successful render of the same entity restores them —
  or not, since the saved map is also lost.
- the `ThreadLocal` is `static`, so nested or re-entrant rendering of two entities on the
  same thread would have the second `HEAD` clear the first one's saved map. In practice
  entity rendering is sequential on the render thread, so this has not been observed, but
  nothing in the code prevents it.
- both injections carry `require = 0`. If the target method signature ever stops matching,
  the mixin applies with zero injections and trinket hiding stops working **silently**.
  No log line, no crash. That is a deliberate trade for not crashing on a Trinkets update,
  and it is the single most likely reason for a future "it stopped working" report.

---

## Known weaknesses

Written down because the next person to touch this deserves the list.

**The client cache is never cleared.** `VanityClientState.clear()` exists and is called
from nowhere. There is no disconnect hook. Leave a server and join another in the same
game session and the old entries are still in the map. They only matter if a UUID that was
hidden on server A is also present on server B, in which case B's player appears partly
invisible until they toggle something. A `ClientPlayConnectionEvents.DISCONNECT`
registration calling `clear()` is a two-line fix.

**Debug logging is at `INFO`.** `HandledScreenMixin.onKeyPressed` logs the slot class,
index, id, inventory class and `hasStack` on every single `=` press, then logs the
resolved key, and the server logs every refused toggle. The comment says "INFO level for
troubleshooting", so it was intentional, but it is intentional debug noise that ships to
users.

**The key is hardcoded.** `GLFW_KEY_EQUAL`, compared literally. No `KeyBinding`, so no
entry in Options → Controls and no way for a player to change it or for the mod to avoid
a conflict with another mod that wants `=` in container screens.

**No server-side authority over what is already stored.** `isSlotToggleable` gates writes
only. Keys stored while a setting was permissive stay in effect after you tighten it, and
keys added to `disabledSlots` do not un-hide anything. There is no filter on load and no
admin command to fix it, so the only remedy is editing the data file with the server down.

**`getAll()` leaks mutable sets.** The map is wrapped unmodifiable, the sets are not. The
join handler copies them into a `HashSet` before sending, so no current caller abuses it,
but the API invites it.

**The config path is relative.** `Path.of("config", ...)` rather than
`FabricLoader.getInstance().getConfigDir()`. Correct for a normal launch, wrong for any
launcher that sets a different working directory, and inconsistent with the rest of the
repository.

**The join sync is O(all stored players).** On every join the server sends one packet per
player who has ever hidden anything, including players who are offline and in another
dimension. With a long-lived data file that is a few hundred small packets at login. It is
not a problem yet; it is also not bounded by anything.

**No player-facing localisation.** There is no `assets/cheesevanity/lang/` directory. The
two feedback messages are built with `Text.literal` and string concatenation, so they are
English-only and cannot be translated without a code change — unlike the other mods in
this repository, which were converted to `Text.translatable`.

---

## Extension points

There are no interfaces to implement and no events to subscribe to; this mod was not
built to be extended. The three places a contributor would actually touch:

- **`VanityConfig.isSlotToggleable`** — the whole policy. Per-group rules, permission
  levels, or a per-player allowance all belong here.
- **`HandledScreenMixin.resolveSlotKey`** — the only mapping from a `Slot` to a key. A
  fourth kind of slot (a different accessory mod, a curios-style API) is added here, and
  nothing else changes, because everything downstream is a string.
- **a new render mixin** — the pattern to copy is `ArmorFeatureRendererMixin`, not the
  trinkets one. Cancel at the head of a per-slot render method if you can; only fall back
  to the save-and-restore trick when the target gives you no seam.

If you add a real `KeyBinding`, register it in `CheeseVanityClient` and read it from the
mixin instead of comparing `keyCode` — that is the one change that would improve the mod
for every user at once.

---

← [Who sees what](visibility.md) · [Troubleshooting](troubleshooting.md) →
