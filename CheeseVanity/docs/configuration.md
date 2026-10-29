[← CheeseVanity](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

```text
config/cheesevanity.json
```

The path is built in `VanityConfig` as `Path.of("config", "cheesevanity.json")`, a
**relative** path. It resolves against the process working directory, which for a normal
server launch is the server directory, next to `world/` and `mods/`. That is the same
place `FabricLoader.getConfigDir()` points to in practice, but it is not the same code:
start the server with a working directory other than the server root and the file will be
created somewhere else. Launching a client the mod also writes it, under
`.minecraft/config/`. That copy has no effect on a dedicated server, whose own file is the
one that counts; it **is** the live config in singleplayer and on a LAN world you host,
because the integrated server runs inside the client process and its toggle handler reads
the `VanityConfig` that this client loaded.

The file is written with a default-valued object the first time the mod starts without
it, so you never have to create it by hand.

---

## Keys

Three keys, all at the top level. There are no sections.

| Key | Type | Default | What it does |
|---|---|---|---|
| `enableVanillaArmor` | boolean | `false` | allows the four vanilla armor slots (`helmet`, `chestplate`, `leggings`, `boots`) to be toggled |
| `enableTrinkets` | boolean | `true` | allows every Trinkets slot to be toggled |
| `disabledSlots` | array of strings | `[]` | slot keys that may never be toggled, whatever the two booleans say |

All three are read by one method, `VanityConfig.isSlotToggleable`, in this order:

```java
if (disabledSlots.contains(slotKey)) return false;
if (isVanillaArmorSlot(slotKey)) return enableVanillaArmor;
return enableTrinkets;
```

So `disabledSlots` wins over everything, the armor booleans split on whether the key is
one of the four vanilla words, and `enableTrinkets` is the fallback for every other key.

### `enableVanillaArmor`

**It defaults to `false`, and that default surprises people.** Out of the box, pressing
`=` on a helmet does nothing and says nothing. The comment in `VanityConfig.java`
explains why: on the Arkensia server another mod already owns armor visibility, so
CheeseVanity deliberately stays out of it. On any other server that is not true, and this
is the first key you want to flip:

```json
"enableVanillaArmor": true
```

Turning it on does not retroactively change anything. It only changes what the server
accepts from that point on. A player who hid a helmet while it was `true` keeps that
helmet hidden after you set it back to `false` — the stored set is never filtered on
load, only writes are checked. To actually un-hide those slots you have to remove the keys
from `config/cheesevanity_data.json` by hand.

### `enableTrinkets`

Covers every Trinkets slot at once. There is no per-group switch; if you want to allow
`chest/*` but not `head/*`, you do it by listing the head keys in `disabledSlots`.

Setting it to `false` while Trinkets is installed, and leaving `enableVanillaArmor` at
`false`, makes the mod a no-op: nothing anywhere is toggleable. Nothing warns you about
that combination.

### `disabledSlots`

A plain list of slot keys, matched by exact string equality — no wildcards, no prefixes,
no `chest/*`. Get the keys out of the client log as described in
[the controls page](commands.md#reading-the-slot-key-of-a-slot).

The use case this exists for is functional slots that should not be invisible. A backpack
in `chest/back` is the stock example: a hidden backpack is a player who looks unarmed and
is carrying forty stacks, and on a PvP server that is information other players are
entitled to. Same reasoning for an elytra-equivalent or a visible-tier trinket.

```json
"disabledSlots": ["chest/back", "head/hat"]
```

Keys in this list stay toggleable-looking in the GUI — there is no greyed-out state and
no message. A player pressing `=` on a disabled slot gets silence. Tell your players
which slots you blocked, because the mod will not.

---

## Reload behaviour

**None. The config is read exactly once**, in `CheeseVanityMod.onInitialize`, into a
static field. No command reloads it, no file watcher picks it up, and `/reload` does not
touch it. Editing the file on a running server changes nothing until the next restart.

The same applies to `cheesevanity_data.json`: loaded once at startup, written on every
toggle. Editing it while the server runs is worse than useless, because the next toggle
by any player rewrites the whole file from memory and discards your edit. Stop the server
first.

---

## Complete annotated example

JSON has no comments, so the annotations below have to come out before you use the file.
Copy the block under it instead.

```json
{
  "enableVanillaArmor": true,      // let players hide helmet/chestplate/leggings/boots
  "enableTrinkets": true,          // let players hide every Trinkets slot
  "disabledSlots": [               // except these, which stay visible for everyone
    "chest/back",                  // backpacks — hiding storage is unfair in PvP
    "offhand/shield"               // example: a slot your pack declares for shields
  ]
}
```

The usable version:

```json
{
  "enableVanillaArmor": true,
  "enableTrinkets": true,
  "disabledSlots": [
    "chest/back"
  ]
}
```

And the file the mod writes for itself on a first launch, which is also the
"Arkensia default" where another mod handles armor:

```json
{
  "enableVanillaArmor": false,
  "enableTrinkets": true,
  "disabledSlots": []
}
```

Gson deserialises the file straight onto the `VanityConfig` fields. A key you omit keeps
its Java default, a key you misspell is ignored silently, and a malformed file is caught,
logged as `[CheeseVanity] Failed to load config`, and replaced by the defaults — the
broken file is then **overwritten**, so keep a copy before you experiment.

---

← [Controls](commands.md) · [Who sees what](visibility.md) →
