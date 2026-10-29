[← CheeseMarket](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](player-interface.fr.md)

# The player interface

This page is written for the person actually buying things. The shop is not a chest with
items in it — it is a full-screen panel drawn from a 350 × 250 texture, with its own
cards, its own confirmation dialog and a quantity slider. You never drag an item and you
never close a chest.

---

## Opening a shop

You do not type anything. A shop opens when the server decides to open it for you,
which in practice means one of three things:

- an admin runs `/shop open <id>` while you are the one running it, or targets you some
  other way;
- an NPC, a quest script or a command block sends you into a shop;
- a mod or datapack sends the `open_shop_player:<shopId>` action on your behalf.

There is no `/shop` command for players. The whole `/shop` tree needs permission level
2, so if you type it you get the vanilla *Unknown or incomplete command* error. See
[commands](commands.md) if you are the admin.

When it opens, your world is still running behind the panel — the shop does **not** pause
the game (`shouldPause()` returns false). You can be shot while shopping.

---

## What you see

```text
┌──────────────────────────────────────────────┐
│                                         [ X ]│  ← close button, top right
│                  Shop name                   │  ← title, coloured by the shop
│                 description                  │
│                                              │
│   ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐  │
│   │icon│ │icon│ │icon│ │icon│ │icon│ │icon│  │  ← 6 cards per row, 3 rows visible
│   │ 5⛁ │ │ 12⛁│ │ 3🧀│ │ 1⛁ │ │ 40⛁│ │ 2⛁ │  │  ← price under each icon
│   └────┘ └────┘ └────┘ └────┘ └────┘ └────┘  │
│   ┌────┐ ┌────┐                            ▌ │  ← scrollbar when there is more
│   └────┘ └────┘                              │
└──────────────────────────────────────────────┘
```

The exact positions, the card size, the colours, the background image and the price
label are all configurable per shop, so what you see on one server need not match the
sketch. The pieces that are always there: a background, a title, a close button, and a
grid of cards.

Each card shows the item's icon and its price, up to three cost lines (a fourth becomes
`…`). Hovering a card gives you the item's real vanilla tooltip — the server pre-renders
it and ships it with the shop data, so an enchanted book shows its enchantments and a
custom quest item shows its lore exactly as it would in your inventory.

Under the icon, depending on the item's limit, you may also see how many are left:
`∞`, `42/100`, `2/3 (vous)`, `1/1 (Quotidien)`.

**A greyed-out card is one you cannot buy right now** — out of stock, over your personal
limit, or already owned. It is drawn with a dark overlay, does not highlight on hover,
and clicking it does nothing.

---

## Buying

1. **Click a card.** A confirmation panel opens on top of the grid. There is no buy
   button on the cards themselves.
2. The panel shows two columns: on the left what you pay, on the right what you get,
   both with large icons. Either column scrolls with the wheel if the list is long.
   Hovering a line gives you its tooltip plus a `Quantité: n` line.
3. **Set the quantity.** Drag the slider. It runs from 1 to 64 in whole steps, and both
   columns update live — prices multiply as you drag.
4. **Click `Confirmer`.** Or `Fermer`, or press Escape, or click anywhere outside the
   panel: all three cancel and reset the quantity to 1.

Coin prices are re-denominated as you drag, so 150 bronze coins are shown as
1 silver 50 bronze rather than a pile of 150. That is display only — the server charges
the amounts configured on the item.

### What happens next

The confirmation panel closes the instant the server answers, before the items land, so
there is no double-click window.

| Outcome | What you see |
|---|---|
| Bought | green `Achat réussi : <item>` in chat, and the grid refreshes with new stock numbers |
| Not enough money or items | red `Pas assez de ressources pour acheter x<n>.` |
| No room | red `Ton inventaire est plein ! Libère de la place avant d'acheter.` — nothing was charged |
| Out of stock | red `Stock épuisé pour cet item.` |
| Over your limit | red `Vous avez atteint votre limite d'achat pour cet item.` |
| Item switched off | red `Cet item n'est pas disponible.` |
| Set closed while you browsed | red `Ce set n'est pas actif en ce moment.` |
| Anything else | red `Achat impossible.` |

If you paid in credits you also get a gold line: `🧀 -<n> Crédits (solde : <reste>)`.

⚠️ One message means something went wrong on the server's side, not yours:

```text
[Market] Erreur de livraison ! Contactez un admin pour un remboursement.
```

You were charged and the delivery failed. Keep the time and the item name and tell an
admin; the server log holds the matching line and the transaction log holds the price
you paid.

### Items at your feet

If your inventory fills up *while* the items are being handed over — because the
space check looks at your 36 main slots and a purchase can still overflow in odd cases
— the remainder drops on the ground at your feet instead of disappearing. You are not
told, so pick up before you walk away.

---

## Scrolling and the rest of the controls

| Input | Effect |
|---|---|
| mouse wheel over the grid | scrolls the grid |
| drag the scrollbar on the right | same, faster |
| mouse wheel over a confirmation column | scrolls that column |
| left-click a card | opens the confirmation panel |
| drag the slider | sets the quantity, 1–64 |
| Escape | closes the confirmation panel, or the shop if no panel is open |
| click the `X` | closes the shop |
| click outside the confirmation panel | closes the panel only |

Right-clicks and middle-clicks do nothing. The jar ships lang strings for
`Middle-click to buy full stack` and `Left-click to purchase`, left over from an earlier
design; the slider replaced both and those strings are no longer used.

---

## Things the interface does not do

Honest list, so you do not go looking:

- **No tabs.** A shop can hold several sets, and only the first active one is shown.
  Nothing in the interface switches sets.
- **No welcome message.** The shop can carry one, and the client never draws it.
- **No display modes.** `TABS`, `FLAT` and `FEATURED` are configurable and the client
  ignores all three.
- **No selling.** There is no sell screen and no sell action, despite sell-looking
  textures in the jar.
- **No search and no categories.** The grid is the whole shop.
- **No currency readout.** Your purse or credit balance is not shown anywhere in the
  panel; you find out when a purchase fails.

---

## If something looks wrong

A card with a blank white square instead of an icon means a custom
item whose icon image did not arrive — the server logs
`[Market]   <id> → NO image found` for that case. A card with the wrong icon is usually
an item whose `displayItem` was left at `minecraft:paper`, which the mod reads as "unset"
and replaces with the first output's icon.

More in [troubleshooting](troubleshooting.md).

---

← [The shop model](shop-model.md) · [Layout editor](layout-editor.md) →
