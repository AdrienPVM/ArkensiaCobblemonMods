[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](dna.fr.md)

# Collectible DNA

Every collectible in EggAndCheese has exactly one piece of state that decides what it
looks like: a `CollectibleDNA`. It is a Java `record` — fourteen components, no setters,
no mutable fields. Two collectibles with the same DNA are visually identical on every
client, because the texture is computed from the DNA rather than stored as an image.

That design has one consequence you have to internalise before you touch the API: you
never modify a DNA. You build a new one and replace the old reference.

## The fourteen components

Declared in `src/main/java/com/roicheese/eggandcheese/dna/CollectibleDNA.java`:

| Component | Type | Range / values | Effect on the egg |
|---|---|---|---|
| `seed` | `long` | any 64-bit value | Drives every random decision in texture generation. Same seed plus same other fields produce a byte-identical texture. |
| `palette` | `PaletteType` | 34 named palettes | Supplies the six colours the whole texture is drawn with. |
| `pattern` | `PatternType` | 26 named patterns | The primary pattern layer. |
| `patternScale` | `int` | 1–4 (clamped) | Feature size of the primary pattern. 1 is fine and busy, 4 is coarse and blocky. |
| `secondaryPattern` | `PatternType` | 26 named patterns | A second pattern layer drawn under or over the first. |
| `patternBlend` | `int` | 0–100 (clamped) | How much of the secondary pattern shows through. 0 hides it entirely. |
| `emissive` | `EmissiveType` | 16 values, `NONE` included | The glow layer. `NONE` means the emissive map is left blank and never drawn. |
| `emissiveDensity` | `int` | 0–100 (clamped) | How much of the surface the glow covers. 0 suppresses the glow even when `emissive` is set. |
| `rarity` | `RarityType` | 6 tiers | None on the texture. A label whose name and colour the API reports — see below. |
| `stamp` | `StampType` | 10 values, `NONE` included | A recognisable icon (star, moon, crown…) drawn as a third layer. |
| `microTexture` | `MicroTextureType` | 8 values, `NONE` included | High-frequency surface grain layered over everything else. |
| `visualFlags` | `int` | 5-bit mask | Five independent post-processing effects — see below. |
| `varnishLevel` | `int` | 0–100 (clamped) | Strength of the varnish sheen. Only read when the varnish flag is set. |
| `customPalette` | `String` | `""` or six `RRGGBB` values, comma-separated | Overrides `palette` when non-empty. |

### Palettes

A `PaletteType` is six ARGB colours in a fixed order: primary, secondary, highlight,
shadow, accent, glow. Every pattern renderer refers to them by role, which is why
swapping the palette re-skins an egg without changing its shape at all.

The 34 values: `WARM`, `COOL`, `NATURE`, `DARK`, `PASTEL`, `FIRE`, `ICE`, `MYSTIC`,
`GOLD`, `CANDY`, `NEBULA`, `LAVA`, `FOREST`, `ARCTIC`, `CORRUPTED`, `CELESTIAL`,
`NEON`, `RAINBOW`, `OCEAN`, `SUNSET`, `AUTUMN`, `SPRING`, `CYBERPUNK`, `STEAMPUNK`,
`DESERT`, `VOLCANIC`, `AURORA`, `AMETHYST`, `JADE`, `RUST`, `BLUEBERRY`, `PEACH`,
`TOXIC`, `CUSTOM`.

`CUSTOM` carries the same colours as `WARM`. It exists as a marker for the dashboard's
colour pickers; what actually overrides the colours is a non-empty `customPalette`
string, and that works with any palette value. `getActiveColors()` parses the string,
requires exactly six comma-separated hex values, forces alpha to `FF`, and silently
falls back to the enum's own colours when the string is malformed. A typo in a colour
therefore shows up as "my custom colours were ignored", never as an error.

### Patterns

26 values: `SPOTS`, `STRIPES`, `MARBLE`, `GEOMETRIC`, `VEINS`, `CONSTELLATION`,
`CRYSTALS`, `BUBBLES`, `WAVES`, `MOSAIC`, `SCALES`, `CRACKS`, `GALAXY`, `RUNES`,
`SPLATTER`, `CIRCUITS`, `TIEDYE`, `GRADIENT`, `HONEYCOMB`, `FEATHERS`, `FLORA`,
`ZIGZAG`, `CROSSHATCH`, `SMOKE`, `TRIBAL`, `FRACTAL`.

`pattern` and `secondaryPattern` draw from the same enum, so nothing stops you from
setting both to `SPOTS`. The result is a denser spot field, not an error.

### Rarity

`RarityType` carries four properties per tier besides its display name, read straight from
the enum's constructor arguments:

| Rarity | Label colour | Pattern layers | Border | Animated glow |
|---|---|---|---|---|
| `COMMON` | `#AAAAAA` | 1 | no | no |
| `UNCOMMON` | `#55FF55` | 1 | no | no |
| `RARE` | `#5599FF` | 2 | no | no |
| `EPIC` | `#AA00FF` | 2 | yes | no |
| `LEGENDARY` | `#FFAA00` | 3 | yes | yes |
| `MYTHIC` | `#FF55FF` | 3 | yes | yes |

Only the first column is ever read. The colour reaches the API as `rarityColor` and in
`GET /enums`; `getPatternLayers()`, `hasBorder()` and `isAnimated()` are never called.
Neither the Java texture generator nor the dashboard's JavaScript previewer reads rarity
at all — "Step 1, rarity background" uses only the seed and the palette — so two eggs that
differ only by rarity look identical, and no glow cycles frames. Rarity is a label, and
not a drop rate either: nothing in the code rolls against it to decide whether a player
finds an egg.

### Emissive layer

16 values: `NONE`, `SPOTS`, `LINES`, `RUNES`, `STARS`, `AURORA`, `VEINS`, `SPARKS`,
`CRACKS`, `PULSE`, `CIRCUIT`, `RIPPLE`, `FLAME`, `NEBULA`, `MATRIX`, `DIAMONDS`.

The glow is a separate texture, filled only when `emissive != NONE` **and**
`emissiveDensity > 0`. Both conditions matter: an egg with `emissive = FLAME` and
`emissiveDensity = 0` gets a blank glow map that the renderer never draws. How each of these is actually drawn is
covered in [texture generation and previews](rendering.md).

### Stamps and micro-textures

Stamps are iconography rather than pattern: `NONE`, `STAR`, `MOON`, `HEART`, `SPIRAL`,
`DIAMOND`, `RUNE_TRIPLE`, `ARROW`, `CROWN`, `INFINITY`. They are drawn as a third
layer, which means a stamp survives a busy pattern underneath it.

Micro-textures simulate a material: `NONE`, `GRANULAR`, `METALLIC`, `ICY`, `SILK`,
`STONE`, `SAND`, `CARBON`. They modulate brightness at high frequency over the whole
surface, so they read as a finish rather than a drawing.

### Visual flags

`visualFlags` is a bitmask. Five bits are defined:

| Constant | Bit | Hex | What it adds |
|---|---|---|---|
| `FLAG_LIGHTING` | 0 | `0x01` | Directional lighting — fake sphere shading. |
| `FLAG_SPECULAR` | 1 | `0x02` | A gloss blob in the upper-left of the texture. |
| `FLAG_EMBOSS` | 2 | `0x04` | Sobel-based relief on pattern edges, a chiselled look. |
| `FLAG_VARNISH` | 3 | `0x08` | A glossy diagonal sheen whose strength is `varnishLevel`. |
| `FLAG_RIM_GLOW` | 4 | `0x10` | A band of the palette's glow colour painted into the colour map at the top and bottom of the texture — the egg's tip and base. It is not emissive: it does not glow in the dark. |

Two aliases exist for compatibility with the names these flags used to have:
`FLAG_OUTLINE` is `FLAG_EMBOSS` and `FLAG_EDGE_GLOW` is `FLAG_RIM_GLOW`. They are the
same bits, not extra ones.

`hasFlag(int)` is the only read accessor. The dashboard also receives each bit decoded
as a boolean (`flagLighting`, `flagSpecular`, `flagEmboss`, `flagVarnish`,
`flagRimGlow`) so a form can bind checkboxes without doing bit arithmetic in
JavaScript — but a write still goes back as the integer `visualFlags`.

## Immutability, and what a `PUT` has to do

`CollectibleDNA` is a `record`. There is no `withPalette(...)`, no setter, no builder.
The only way to produce a DNA that differs by one field is to call the canonical
constructor with all fourteen arguments:

```java
CollectibleDNA updated = new CollectibleDNA(
    old.seed(), newPalette, old.pattern(), old.patternScale(),
    old.secondaryPattern(), old.patternBlend(), old.emissive(),
    old.emissiveDensity(), old.rarity(), old.stamp(), old.microTexture(),
    old.visualFlags(), old.varnishLevel(), old.customPalette()
);
entry.setDna(updated);
```

`EggsApi.applyEdits` does exactly this. For `PUT /api/v1/eggs/collectibles/:id` with a
`dna` object in the body, it reads the entry's current DNA into `old`, then for each of
the fourteen components takes the submitted value **if the key is present** and `old`'s
value otherwise, and finally constructs one new record.

### What that means for an API caller

- **A partial `dna` object is safe.** Send `{"dna":{"palette":"ICE"}}` and the other
  thirteen components survive untouched. The server fills them from the stored DNA, not
  from defaults.
- **Omission is "keep", not "clear".** There is no way to reset a component to its
  default by leaving it out. To clear a custom palette you must send
  `"customPalette": ""` explicitly; to remove a stamp, send `"stamp": "NONE"`.
- **An invalid enum name is swallowed.** `safeEnum` falls back to the old value rather
  than erroring, so `{"dna":{"pattern":"SPOT"}}` — note the missing S — returns `200`
  with the pattern unchanged. Always compare the response's `dna` block against what
  you sent.
- **Numbers are clamped, not rejected.** `patternScale: 99` becomes `4`;
  `emissiveDensity: -5` becomes `0`. Again: `200`, with a value you did not ask for.
- **`visualFlags` is all-or-nothing.** It is one integer. Sending it replaces the whole
  mask, so to turn one bit on you must send the full value you want, usually
  `oldFlags | 0x08`.
- **Omitting `dna` entirely leaves the DNA alone.** The surrounding fields (`title`,
  `scale`, coordinates…) are applied independently of it.

The upshot: a client that round-trips `GET` → edit → `PUT` is always correct, and a
client that sends only the fields it changed is also correct. What is *not* correct is
assuming an omitted field resets.

## Serialisation

DNA is written in three different shapes, all in `CollectibleDNA`.

**NBT** — `toNbt()` / `fromNbt(NbtCompound)`. Used for the block entity, for the item's
`CUSTOM_DATA` component, and inside the registry's persistent state. `customPalette` is
only written when non-empty, and `fromNbt` on a compound with no `seed` key returns a
freshly generated random DNA rather than throwing. Enum names that no longer exist fall
back to a documented default (`WARM`, `SPOTS`, `MARBLE`, `NONE`, `COMMON`), so renaming
an enum constant degrades old eggs instead of breaking the save.

**DNA code** — `toDnaCode()` / `fromDnaCode(String)`. A single human-transportable
string:

```text
EAC-{seed as 16 hex digits}-{PALETTE}.{PATTERN}.{SCALE}.{SECONDARY}.{BLEND}.{EMISSIVE}.{DENSITY}.{RARITY}.{STAMP}.{MICRO}.{FLAGS as 2 hex digits}.{VARNISH}[;{customPalette}]
```

A real one:

```text
EAC-5F2A91C30B4D77E8-ICE.CRYSTALS.2.MARBLE.35.STARS.60.EPIC.MOON.ICY.0F.45
```

`fromDnaCode` returns `null` on anything it cannot parse — a missing `EAC-` prefix,
fewer than twelve dot-separated fields, a non-numeric scale. It never throws — and
nothing ever calls it: no route accepts a DNA code and no dashboard field imports one. The
dashboard exposes the code as `dna.dnaCode` on every detail response, so "send me that
egg" is a copy-paste of a record, but applying it still means sending its fields as a
`dna` object in a `PUT`.

**Cache key** — `cacheKey()` concatenates all fourteen components. The client's texture
cache uses it so that two eggs with identical DNA share one generated texture in
memory. If you add a component to the record and forget to add it to `cacheKey()`, two
visually different eggs will collide in the cache and render identically. The field list
is duplicated by hand in several places — `toNbt`, `fromNbt`, the DNA code,
`EggsApi.applyEdits` and `buildDetail` — but `cacheKey()` is the one where forgetting a
field fails silently.

## Random generation

`randomDNA(long seed)` seeds a `java.util.Random` and makes every choice from it, so
the same seed always yields the same DNA. `generate()` is `randomDNA` with a random
seed.

The distribution is deliberately not uniform:

- `palette`, `pattern`, `secondaryPattern` and `emissive` are picked uniformly from
  their full enum, `NONE` included for emissive.
- `stamp` and `microTexture` are `NONE` two times out of three. When they are not, the
  value is drawn uniformly from the non-`NONE` entries.
- `patternScale` is 1–4 uniform. `patternBlend` is 15–69. `emissiveDensity` is 20–79.
  `varnishLevel` is 30–79.
- `FLAG_LIGHTING` and `FLAG_SPECULAR` are always set. `FLAG_EMBOSS` is a coin flip,
  `FLAG_VARNISH` one in three, `FLAG_RIM_GLOW` one in four.
- rarity is weighted: `COMMON` 40 %, `UNCOMMON` 25 %, `RARE` 18 %, `EPIC` 10 %,
  `LEGENDARY` 5 %, `MYTHIC` 2 %.
- `customPalette` is always `""`. Random generation never invents colours.

That is why the dashboard's regenerate actions — the **Refresh** button under the DNA code
on the detail page, and **Randomize selection** in the list's bulk bar — which call
`POST /collectibles/:id/randomize-dna`, discard any hand-tuned parameters: they do not
reshuffle the seed and keep your fields, they build a whole new random DNA from a new
seed. If you have hand-picked a palette, copy the DNA code before you press one, so you
know which values to send back.

⚠️ `randomize-dna` with no `seed` in the body uses `new Random().nextLong()`, which is
not reproducible. Pass `{"seed": 1234}` when you want a result you can get back.

---

← [Configuration](configuration.md) · [Texture generation and previews](rendering.md) →
