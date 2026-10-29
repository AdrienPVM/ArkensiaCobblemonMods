[← CheeseMarket](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](layout-editor.fr.md)

# The layout editor

A shop's visual arrangement is data, not code. The dashboard carries a drag-and-drop
editor on a 350 × 250 canvas — the exact pixel size of the shop texture — and what you
drag there is stored on the shop as a `layout` object and replayed by the client when a
player opens the shop.

A shop with no `layout` falls back to a hardcoded arrangement. That default is also what
`ShopLayout.buildDefault()` produces, so "reset" and "never touched" give the same
result.

---

## The canvas

The editor lives behind the **Layout** tab of a shop's detail page in the dashboard
(`market` module). It gives you:

- a 350 × 250 canvas at 1× to 4× zoom, with the shop's background drawn underneath;
- a snap grid of 1, 2, 5, 10, 15 or 20 pixels (5 by default);
- a layers panel with per-element z-order arrows;
- a properties panel for whatever is selected;
- undo and redo, 30 steps deep, bound to Ctrl+Z and Ctrl+Y;
- Delete to remove the selection, Ctrl+D to duplicate it, arrow keys to nudge by one
  pixel and Shift+arrows to nudge by the snap step;
- an autosave on a 2-second debounce, so you edit and it persists without a save button.

Coordinates are in texture pixels with the origin at the top-left of the 350 × 250
panel. The client adds the panel's on-screen origin, so your coordinates are resolution
independent.

---

## What you can place

Five element types, from `ShopLayout.ElementType`:

| Type | What it is | Rendered by the client? |
|---|---|---|
| `BACKGROUND` | the panel background, either the built-in texture or an uploaded PNG | yes |
| `TEXT` | a text label, with placeholders | yes |
| `BUTTON` | a filled rectangle with a label and an action | yes |
| `ITEM_GRID` | the card grid — columns, rows and padding | yes |
| `IMAGE` | a free-standing image | **no** |

⚠️ `IMAGE` is a dead end. You can add one, position it and save it, and
`ShopViewScreen` skips it explicitly along with `BACKGROUND` and `ITEM_GRID` in its
custom-element loop — but unlike those two it has no dedicated renderer anywhere. It will
never appear in game. Use the `BACKGROUND` element's uploaded PNG for decoration
instead.

Every element shares the same frame: `id`, `type`, `x`, `y`, `width`, `height`,
`zOrder`, and a free-form `props` object whose keys depend on the type. Elements are
drawn in ascending `zOrder`.

### TEXT

| Prop | Meaning |
|---|---|
| `text` | the string; `{shopName}` and `{shopDesc}` are substituted at render time |
| `color` | hex `RRGGBB`, a vanilla colour name, or the literal `{titleColor}` to inherit the shop's title colour |
| `align` | `left`, `center` (default) or `right`, within the element's `width` |
| `fontSize` | carried in the default layout; the renderer derives its scale from `scale` |
| `scale` | text scale factor |
| `bold` | boolean |

The default layout uses two TEXT elements with `text` set to `{shopName}` and
`{shopDesc}`, and the client finds them *by that exact placeholder string* to apply the
title and description styling. Change `{shopName}` to something else and the title stops
being treated as the title.

### BUTTON

| Prop | Meaning |
|---|---|
| `action` | what clicking does |
| `text` | label, centred |
| `color` | background fill, hex `RRGGBB` |
| `textColor` | label colour |
| `src` | used by the default close button to pick the built-in texture |

**Only one action works: `close`.** The client builds a clickable widget for any
non-empty action and routes it through `_custom:<action>`, where exactly one branch
exists, for `close`. Any other action string gives you a button that swallows the click
and does nothing. There is no "switch set", no "next page", no "run command".

The default layout's close button is found by matching `action: "close"`, and gets the
built-in 20 × 20 close texture rather than a colour fill.

### ITEM_GRID

| Prop | Default | Meaning |
|---|---|---|
| `cols` | 6 | cards per row |
| `rows` | 3 | rows in the default layout; the client derives visible rows from the element's `height` |
| `padX` | 4 | horizontal gap in pixels |
| `padY` | 3 | vertical gap in pixels |

The grid centres itself horizontally inside the element. When everything fits without
scrolling it also centres vertically, and an incomplete last row is centred too — so a
shop with four items in a six-column grid shows them as a tidy centred row, not
left-aligned with two gaps. Past one page a scrollbar appears on the right and the
centring stops.

### BACKGROUND

One prop, `src`: either `default` for the built-in `shop/custom/shop.png`, or the name of
an uploaded asset. The client receives the PNG inline as base64 on the first
`SHOP_VIEW` payload and registers it as a dynamic texture, then skips re-sending it on
the stock refresh that follows a purchase.

---

## The item card template

Separate from the elements, `layout.itemCard` describes one card. Edit it in the card
sub-editor, which zooms from 2× to 8× because you are positioning things at a scale of
a few pixels.

| Key | Type | Default | What it does |
|---|---|---|---|
| `cardWidth` | int | 41 | card width, matching the stock `item.png` |
| `cardHeight` | int | 36 | card height |
| `iconOffsetX` | int | 13 | icon position inside the card |
| `iconOffsetY` | int | 7 | |
| `iconSize` | int | 15 | icon render size |
| `costOffsetX` | int | 0 | horizontal offset of the price block from centre |
| `costOffsetY` | int | 26 | vertical offset of the price block |
| `costTextScale` | float | 0.85 | price text scale |
| `costIconSize` | int | 9 | size of the small coin or item icon next to a price |
| `costGap` | int | 2 | gap between two price pairs |
| `costTextIconGap` | int | -1 | gap between a price number and its icon |
| `costIconOffsetY` | int | 0 | vertical nudge of the price icon relative to its text |
| `costTextColor` | string | `FFCC44` | price text colour |

Negative gaps are intentional and legal: `costTextIconGap` defaults to `-1` to pull the
icon tight against the number.

---

## The confirmation panel

`layout.confirmLayout` styles the buy dialog. It is absent until you edit it, and
absent means the client's own constants apply. It carries one geometry key and fifteen
colours:

| Key | Default | What it colours |
|---|---|---|
| `width` | 340 | panel width |
| `bgImage` | `""` | asset name for a background image; empty uses the built-in `confirm.png` |
| `bgColor` | `1A1A2E` | panel background |
| `borderColor` | `2A5A8A` | border |
| `accentColor` | `4AC8EA` | accents |
| `headerBgColor` | `0D2137` | header strip |
| `titleColor` | `4AC8EA` | item name |
| `labelColor` | `7A8A9A` | column labels |
| `costColor` | `FFCC44` | prices |
| `arrowColor` | `4AC8EA` | the arrow between the two columns |
| `sliderBgColor` | `1A2A4A` | slider track |
| `sliderFillColor` | `357EA3` | filled part of the track |
| `sliderThumbColor` | `4AC8EA` | slider handle |
| `sepColor` | `1E3050` | separators |
| `btnConfirmTextColor` | `55FF55` | `Confirmer` label |
| `btnCancelTextColor` | `FF6666` | `Fermer` label |
| `columnBgColor` | `18FFFFFF` | column backgrounds, `AARRGGBB` |

`columnBgColor` takes eight hex digits because it needs an alpha channel; the others are
six.

The two button labels themselves, `Confirmer` and `Fermer`, are hardcoded French string
literals in `ShopViewScreen`. You can recolour them, not rename or translate them.

---

## Uploaded images

Images are per shop, uploaded as base64 through the dashboard and stored as real files:

```text
<world>/data/cheesemarket/assets/<shopId>/<name>.png
```

`AssetManager` enforces:

- the bytes must start with the eight-byte PNG signature — the extension is not trusted;
- 2 MB maximum per file;
- 10 assets per shop, counted only when creating a new name, so overwriting an existing
  asset always works;
- names matching `[a-zA-Z0-9_-]+`, shorter than 128 characters, and with no `..`.

Deleting a shop through the dashboard also deletes its asset directory. Deleting it with
`/shop delete` does **not** — that path only removes the JSON file, so the PNGs stay
behind as orphans.

⚠️ `AssetManager` resolves its own storage root and nothing ever calls
`AssetManager.init(worldDataDir)`. It falls back to
`<config dir>/../world/data`, which is correct only when your world directory is
literally named `world`. `ShopManager`, by contrast, gets the real path from
`server.getSavePath(…)`. On a server with `level-name=anything-else`, shop definitions and
uploaded images end up in two different places, and the images are read from a directory
nobody writes to. Set `level-name=world`, or call `AssetManager.init` from
`CheeseMarket.onInitialize` if you are patching the mod.

---

## Templates

A layout can be saved under a name and applied to another shop:

```text
<world>/data/cheesemarket/templates/<name>.json
```

Fifty templates maximum, same name rules as assets, and saving over an existing name is
always allowed. A template stores the `elements` array, the `itemCard` block and the
`confirmLayout` block — the layout and nothing else. It carries no shop colours, no
sets and no items, and no reference to the assets its `BACKGROUND` element names. Apply
a template that points at `shop-bg` to a shop with no asset by that name and the shop
falls back to the built-in texture.

Template files sit outside any shop, so they survive deleting every shop.

---

## What validation exists

Not much, and it is worth knowing where the edges are:

- `PUT /shops/:id/layout` rejects more than 20 elements with a 500 and the message
  `Maximum 20 elements`. That is the only check on the layout as a whole.
- Coordinates are **not** bounded. Nothing stops an element at `x = 9000`; it is saved
  and then drawn off-screen.
- Element types that fail to parse become `TEXT`.
- A `PUT` whose body has neither `elements` nor `itemCard` is read as "reset to
  default" and sets the layout to null.
- `props` is passed through as raw JSON with no schema. An unknown prop is stored and
  ignored; a misspelled one silently loses its effect.

---

← [Player interface](player-interface.md) · [Purchases](purchases.md) →
