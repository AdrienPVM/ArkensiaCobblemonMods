[← CheeseItems](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](resource-pack.fr.md)

# Resource pack

A custom item is a vanilla item, so by default it looks like one: a definition built on
`minecraft:paper` renders as paper. Giving it its own icon means getting a texture onto
the client, and the mod does that by building a resource pack ZIP in memory and telling
clients to download it from CheeseDash's HTTP port.

Nothing is written to disk. The pack exists as a `byte[]` inside
`ResourcePackGenerator`, rebuilt when its inputs change.

## What triggers a build

| Event | Effect |
|---|---|
| `SERVER_STARTED` | first build, from whatever icons are on disk |
| `POST /api/v1/items/:id/image` | rebuild, then push to online players if it changed |
| `DELETE /api/v1/items/:id/image` | same |
| `DELETE /api/v1/items/:id` | same, after dropping the icon and the `customModelData` entry |

`/cheeseitem reload` does **not** rebuild the pack. Dropping a PNG into
`config/cheeseitems_assets/` by hand therefore does nothing until an icon upload or a
restart happens.

`generate()` returns whether the SHA-1 changed, and only a changed pack is pushed. That
matters more than it sounds: every push makes the client re-download and re-apply the
pack, which resets its resource-pack ordering in `options.txt`. The ZIP is written with a
fixed entry timestamp (`2025-01-01T00:00:00Z`) precisely so that an unchanged set of icons
produces a byte-identical ZIP and the same hash across restarts.

## What goes into the ZIP

```text
pack.mcmeta
assets/<ns>/models/item/<base item>.json      one per distinct baseItem in use
assets/cheeseitems/models/item/<id>.json      one per item with an icon
assets/cheeseitems/textures/item/<id>.png     the uploaded PNG, unmodified
assets/cheeseitems/textures/font/bar_0..10.png   only when all 11 bar frames exist
assets/minecraft/font/default.json            only when all 11 bar frames exist
```

`pack.mcmeta` declares `pack_format: 34`, which is the 1.21.1 value, and the description
*CheeseItems Custom Icons*.

### How an icon is selected

Each item with an icon gets a `customModelData` number, assigned once and stored in
`config/cheeseitems.json`. `ItemManager` writes that number onto the stack as the
`CUSTOM_MODEL_DATA` component — but **only if the item also has an icon file on disk**. If
either is missing the component is removed, so a stack never points at a model the pack
does not contain.

The pack then overrides the *base item's* model with a list of predicates:

```json
{
  "parent": "minecraft:item/generated",
  "textures": { "layer0": "minecraft:item/paper" },
  "overrides": [
    {"predicate": {"custom_model_data": 1}, "model": "cheeseitems:item/quest_key"},
    {"predicate": {"custom_model_data": 4}, "model": "cheeseitems:item/lantern_of_arkensia"}
  ]
}
```

One such file per distinct `baseItem`, with the overrides sorted by number. Items sharing
a base item share that file.

⚠️ **This file replaces the base item's model wholesale.** Every item used as a `baseItem`
loses whatever another resource pack did to it — a texture pack that restyles paper and a
CheeseItems definition built on paper cannot both win, and load order decides. Pick base
items your texture pack does not touch, or accept that CheeseItems' version is the one
players see. The same applies in reverse: a pack loaded above CheeseItems' kills the
custom icons.

### <a id="the-endurance-bar-font"></a>The endurance bar font

The endurance bar can be drawn with your own artwork instead of `◆`/`◇` characters. Put
eleven PNGs at `config/cheeseitems_assets/bar/0.png` through `10.png`, representing 0% to
100% in steps of 10.

All eleven must exist. `hasBarImages()` checks every one of them and returns false on the
first miss, so ten frames give you nothing and no warning.

When they are all there, the pack maps them to the private-use codepoints `U+E000` to
`U+E00A` and the actionbar sends the single character matching the rounded level. The
glyphs are declared with `ascent: -3` and `height: 16`.

⚠️ The font is injected as `assets/minecraft/font/default.json`, which is the **whole
default font definition**. Minecraft merges font providers across packs, so this usually
coexists with other packs' fonts — but a pack that also ships a full `default.json` and
loads later will replace this one, and the bar turns into a missing-glyph box. If that
happens, the `◆`/`◇` fallback is not used: the mod still sends the glyph, because it only
checks that the PNGs exist on the server.

## <a id="the-pack-is-served-over-http"></a>The pack is served over HTTP

`GET http://<resourcePackHost>/cheeseitems/api/pack.zip` — unauthenticated, through
CheeseDash's public route mechanism. `resourcePackHost` is a plain `host:port` and
defaults to `localhost:44311`, which works on a test machine and silently fails for every
remote player. [Set it](configuration.md#resourcepackhost).

Sending happens on join, in two packets:

1. `ResourcePackRemoveS2CPacket` for the pack's fixed UUID, derived from the string
   `cheeseitems-pack`, so a client that holds an older build with a different hash drops it
   first. Without that removal the client can end up with a UUID/SHA-1 mismatch, which is
   what corrupts `options.txt`.
2. `ResourcePackSendS2CPacket` with the URL, the SHA-1, `required = false` and the prompt
   *Custom item icons for Cacademy*.

`required = false` means a player can decline the pack. They then see vanilla icons and
the text bar, and nothing else breaks.

If the pack is empty — no icons and no bar frames — nothing is sent at all, and the log
says so:

```text
[CheeseItems] Resource pack cleared (no custom icons, no bar images)
```

If `resourcePackHost` resolves to nothing usable, the send is skipped per player with a
warning:

```text
[CheeseItems] resourcePackHost not configured in cheeseitems.json — cannot send pack to Steve
```

## Entries from other mods

`ResourcePackGenerator` exposes two methods a sibling mod can call to put its own icons
into the same pack:

```java
generator.addExternalEntry(id, namespace, baseItem, customModelData, pngBytes);
generator.removeExternalEntries(idPrefix);
```

External entries are appended to the item entries on every build and are **not**
persisted — a caller has to re-register them after each restart. They also get no
`customModelData` allocation from `CheeseItemsConfig`, so the caller is responsible for
picking numbers that do not collide with the ones in `config/cheeseitems.json`. Nothing in
this repository currently calls either method; the comment in the source mentions PropHunt
icons as the intended consumer.

## Checking what players actually got

The server log tells you the size and hash of each build:

```text
[CheeseItems] Resource pack generated: 3 items, 18422 bytes, SHA1=4f1c…
[CheeseItems] Endurance bar font added to resource pack (11 levels)
[CheeseItems] Pack changed — pushing to 7 online players
```

and you can download the pack yourself with the same URL the clients use, which is the
quickest way to confirm a texture made it in:

```bash
curl -O http://play.example.net:44311/cheeseitems/api/pack.zip
unzip -l cheeseitems-pack.zip
```

---

← [Custom items](items.md) · [Morphos](morphos.md) →
