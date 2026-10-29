[← CheeseVanity](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](visibility.fr.md)

# Who sees what

This is the first question anyone asks about a cosmetic visibility mod, so it gets its
own page. The short answer: hiding a slot hides it for **everyone who has the mod
installed on their client**, including you, in first person and in third person. It is
not a private client-side illusion.

The long answer is below, because the exact boundary matters when half your players run
the modpack and half connect with a vanilla Fabric client.

---

## The data model

The whole mod is one piece of state: a set of **slot keys** per player UUID.

```text
3f2a... (player UUID)  ->  { "chest/back", "head/hat", "boots" }
```

A slot key is a short string. Two kinds exist, and they are produced by
`HandledScreenMixin.resolveSlotKey`:

| Kind | Key format | Examples | Produced from |
|---|---|---|---|
| Trinkets slot | `<group>/<name>` | `chest/back`, `head/hat`, `hand/ring` | the slot's `SlotType`, read by reflection |
| Vanilla armor slot | a fixed word | `helmet`, `chestplate`, `leggings`, `boots` | the slot index in `PlayerInventory` (39, 38, 37, 36) |

A slot key being present in a player's set means "do not render what is in this slot".
Absent means "render it normally". There is no third state, no per-viewer state and no
per-world state.

The groups and names on the Trinkets side come from whatever mods are installed, since
Trinkets slots are declared by datapack. `chest/back` is the slot the common backpack
mods use, `head/hat` the one cosmetic hat mods use. Read the keys off your own server
rather than trusting a list: the log line described in
[commands.md](commands.md#reading-the-slot-key-of-a-slot) prints the resolved key every
time you press the toggle key.

---

## The round trip, step by step

1. You press `=` with the cursor over an eligible slot in any container screen.
   `HandledScreenMixin.onKeyPressed` resolves the slot to a key and sends a
   `cheesevanity:toggle_slot` payload containing that key. Nothing is decided on the
   client.
2. The server receives it in `CheeseVanityMod.onInitialize`. It rejects the key if it is
   null, empty, longer than 64 characters, or not toggleable under the current config
   (see [configuration.md](configuration.md)). A rejected key produces no feedback to the
   player at all, only a server log line.
3. On the server thread, `VanityData.toggle` flips membership of that key in your set,
   and `VanityData.save` rewrites `config/cheesevanity_data.json` immediately.
4. You get an action-bar message: `[CheeseVanity] chest/back hidden` in red, or
   `[CheeseVanity] chest/back visible` in green.
5. The server broadcasts `cheesevanity:sync_hidden` — your UUID plus your complete set of
   hidden keys — to **every online player**, including you.
6. Each receiving client stores the set in `VanityClientState`. From the next frame on,
   the two render mixins consult that cache and skip the hidden pieces.

Because step 5 goes to everyone and step 6 happens on every client that has the mod, the
result is consistent: you and every other modded player see the same model.

---

## The exact boundary

**You, first person.** Vanilla does not draw armor or trinkets on the first-person arms,
so there is nothing to hide and nothing changes.

**You, third person (F5) and in the inventory preview of other mods.** Your model is
rendered through the same `ArmorFeatureRenderer` and `TrinketFeatureRenderer` as anyone
else's, keyed on your own UUID. Hidden pieces are gone.

**Other players, with CheeseVanity installed.** They received the sync packet and their
copy of `VanityClientState` has your set. They see your model exactly as you do.

**Other players, without CheeseVanity installed.** ⚠️ They see everything. The hiding is
done entirely by two client-side render mixins; a client that does not have the mod has
no mixins and no cache, and renders your armor and trinkets normally. The server never
removes or re-equips anything, so there is no server-side way to make an unmodded client
agree. If visibility consistency matters on your server, CheeseVanity has to be a
required client mod, not an optional one.

**Mobs, armour stands, other entities.** `ArmorFeatureRendererMixin` keys on
`entity.getUuid()` and the cache only ever contains player UUIDs, so an armour stand
wearing the same helmet is unaffected.

**Your own inventory screen.** Hidden slots keep showing the item, with a translucent red
fill and a red cross drawn on top by `HandledScreenMixin.afterDrawSlot`. That overlay is
drawn only for slots belonging to you — it reads `client.player.getUuid()` — so it marks
your preference, not anyone else's.

---

## What the server knows and what it does not

The server is the owner of the preference. It stores it, validates it, persists it and
broadcasts it. What it does **not** do:

- it does not change your equipment. A hidden chestplate still gives armour points, a
  hidden trinket still grants whatever effect it grants. Nothing is unequipped, moved, or
  disabled.
- it does not render anything, so a server running CheeseVanity without any modded client
  produces no visible effect whatsoever.
- it does not tell a client to forget a player. There is no "clear" packet;
  `VanityClientState.clear()` exists in the source but is never called. See the stale
  cache symptom in [troubleshooting.md](troubleshooting.md) for what that costs you.

---

## Persistence

Your preference lives in `config/cheesevanity_data.json`, written on every single toggle:

```json
{
  "players": {
    "3f2a6b40-0000-4000-8000-000000000001": [
      "chest/back",
      "head/hat"
    ]
  }
}
```

Players whose set is empty are dropped from the file entirely rather than written as `[]`.

**It survives a disconnect and a server restart.** The file is read once in
`VanityData.load()` during mod initialisation, and the whole map is held in memory from
then on. Log out, log back in two weeks later, your cloak is still hidden.

**It does not follow you to another server.** The file is per server installation, keyed
by player UUID. A proxy network with separate backend servers gets separate preferences
unless the servers share a `config/` directory.

**It is not tied to the item.** The set stores slot keys, never item identities. Hide
`chest/back`, then swap the backpack in that slot for a different one, and the new one is
hidden too. The preference is about the slot, not its contents.

---

## Interaction with the Trinkets dependency

When Trinkets is absent, `resolveSlotKey` catches `ClassNotFoundException` and falls
straight through to the vanilla armor branch, and
`cheesevanity-trinkets.mixins.json` — declared `"required": false` — does not
apply. Nothing breaks. What you lose is every `<group>/<name>` key, which is to say all
of the mod's default functionality, since `enableTrinkets` is `true` and
`enableVanillaArmor` is `false` out of the box. Without Trinkets and without a config
edit, pressing `=` does nothing anywhere.

The trinket-hiding implementation is worth knowing about if you maintain this. Rather
than filtering the render, `TrinketFeatureRendererMixin` empties the hidden
`TrinketInventory` slots just before `TrinketFeatureRenderer.render` runs and puts the
stacks back at `TAIL`. It mutates live inventory objects for the duration of one render
call. The weaknesses that follow from that choice are listed in
[architecture.md](architecture.md#known-weaknesses).

---

← [Configuration](configuration.md) · [Architecture](architecture.md) →
