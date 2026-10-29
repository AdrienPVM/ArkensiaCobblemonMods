[← CheesePurse](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

**CheesePurse has no configuration file.** Nothing is written to `config/`, nothing is
read from it, and `FabricLoader.getInstance().getConfigDir()` does not appear anywhere
in the source. Looking for `config/cheesepurse.json` or `config/numismatic-cheesepurse.json`
is wasted effort; neither has ever existed.

Every number that governs the mod's behaviour is a compile-time constant. This page
lists them, says which file and line to edit, and then covers the settings that do
affect what you see — which belong to Numismatic Overhaul, not to this mod.

---

## The hard-coded values

| Value | Where | Default | What it does |
|---|---|---|---|
| `SCAN_INTERVAL_TICKS` | `NumismaticCheesePurse.java` | `10` | Server ticks between sweeps. 10 ticks is 0.5 s at 20 TPS. |
| `AutoCollectButton.SIZE` | `AutoCollectButton.java` | `12` | Button width and height in pixels, and also the source-rectangle size used to draw the texture. |
| Button X | `InventoryScreenMixin.java` | `guiLeft + 27` | Computed as `guiLeft + 32 - SIZE / 2 + 1`. |
| Button Y | `InventoryScreenMixin.java` | `guiTop + 9` | Computed as `guiTop + 76 - SIZE - 55 - 27 + 27`. |
| Initial client state | `NumismaticCheesePurseClient.java` | `false` | `autoCollectEnabled`, the boolean the button renders from. |

### Changing the sweep interval

```java
// NumismaticCheesePurse.java
private static final int SCAN_INTERVAL_TICKS = 10;
```

Raising it makes deposits lazier and the loop cheaper; lowering it to `1` sweeps every
tick, which is twenty inventory walks per second per opted-in player and twenty possible
action-bar messages. `1` is a legitimate setting on a small server if you want the
deposit to feel instant, and a bad idea with fifty players.

Setting it to `0` or a negative number makes the guard `if (tickCounter < SCAN_INTERVAL_TICKS) return;`
never fire early, so the sweep runs on every tick — the same as `1`, by accident rather
than by design. There is no validation; the field is `private static final` and whatever
you compile in is what runs.

The counter is a single instance field shared by the whole server, so the interval is
global. There is no way to give one player a faster sweep than another.

### Changing the button position

```java
// InventoryScreenMixin.java
int btnX = guiLeft + 32 - s / 2 + 1;
int btnY = guiTop  + 76 - s - 55 - 27 + 27;
```

The Y expression is arithmetic left over from several rounds of nudging the button
around: `76 - 12 - 55 - 27 + 27` evaluates to `9`. It is not wrong, it just records a
history nobody needs. If you move the button, replacing the whole expression with a
plain offset is clearer than adding another term to the chain.

The coordinates are relative to the inventory GUI's top-left corner, which the mixin
recomputes from the hard-coded vanilla GUI size of 176×166. A resource pack that changes
the inventory texture's dimensions will put the button in the wrong place, and there is
no hook to tell the mod about it.

### Changing the textures

Two 12×12 PNGs, at paths the code hard-codes:

```text
src/main/resources/assets/numismatic-cheesepurse/textures/gui/
├── auto_collect_on.png     drawn while the toggle is on
└── auto_collect_off.png    drawn while it is off
```

`AutoCollectButton.renderWidget` draws them with
`context.drawTexture(tex, getX(), getY(), 0, 0, SIZE, SIZE, SIZE, SIZE)`, where the last
two arguments are the texture file's full width and height. Replacing the PNGs with
images of a different size without also changing `SIZE` produces a stretched or clipped
button, because the source rectangle, the destination rectangle and the declared file
dimensions are all the same constant.

There is no background, no border and no hover state drawn — `renderWidget` is overridden
to draw only the texture, so the vanilla button frame never appears. A texture with
transparent edges reads as a floating icon, which is what the stock ones do.

## The language file

Two keys, in `src/main/resources/assets/numismatic-cheesepurse/lang/`:

| Key | `en_us.json` | `fr_fr.json` |
|---|---|---|
| `numismaticheesepurse.button.tooltip.enabled` | `Cheesepurse: ON\nCoins picked up are automatically deposited into your purse.` | `Cheesepurse : ACTIVÉE\nLes pièces ramassées sont automatiquement déposées dans votre bourse.` |
| `numismaticheesepurse.button.tooltip.disabled` | `Cheesepurse: OFF\nClick to automatically deposit picked-up coins into your purse.` | `Cheesepurse : DÉSACTIVÉE\nCliquez pour déposer automatiquement les pièces dans votre bourse.` |

Those two strings are the entire player-facing text of the mod. Everything else a player
reads comes from Numismatic Overhaul.

Only `en_us` and `fr_fr` ship. Any other locale falls back to `en_us`, which is vanilla
behaviour for a missing translation. Adding a language means adding a `<locale>.json`
next to these two with the same two keys; no code change is needed.

⚠️ Both strings describe the behaviour as applying to coins "picked up". As
[auto-collect](auto-collect.md#the-purse-extraction-trap) explains, the sweep takes every
coin in the inventory regardless of origin. The tooltips understate what the feature
does, and a server that has been bitten by the purse-extraction trap may want to reword
them.

---

## Settings that do change what you see

These belong to Numismatic Overhaul and are configured in its own mod options, per
client, through Mod Menu or `config/numismatic-overhaul.json5`.

| Numismatic key | Default | Effect on CheesePurse |
|---|---|---|
| `moneyMessageLocation` | `ACTIONBAR` | Where the `+ [3 Silver]` notification for each sweep appears: `ACTIONBAR`, `CHAT`, or `DISABLED` for none. Set per client and synced to the server. Numismatic's own comment says it requires rejoining to apply. |
| `generateCurrencyInChests` | `true` | Whether loot chests contain coins at all, which is most of what there is to auto-collect on a survival server. Restart required. |
| `enableVillagerTrading` | `true` | Whether villagers trade in Numismatic currency, the other main coin source. Restart required. |
| `lootOptions.*` | varies by structure | How much money each structure type yields. Changes how often a sweep has anything to do. |

Nothing in CheesePurse reads any of these. They are listed because when a server owner
asks "how do I stop the spam", the answer is `moneyMessageLocation`, and it is not in
this mod.

## An annotated example, for the config file that does not exist

If you want to add one, the shape below is the minimum that would be worth having. It is
**not** a file the mod reads — writing it changes nothing — and it is shown only to make
the point that every value below is currently a constant in Java.

```json5
{
  // Server ticks between inventory sweeps. Currently SCAN_INTERVAL_TICKS in
  // NumismaticCheesePurse.java. 20 would make the deposit feel like once a second.
  "scanIntervalTicks": 10,

  // Whether players keep their toggle across a restart. Currently impossible:
  // AUTO_COLLECT_PLAYERS is a plain in-memory HashSet with no save path.
  "persistToggle": false,

  // Whether money bags are swept along with loose coins. Currently always true,
  // because MoneyBagItem implements CurrencyItem and the scan tests the interface.
  "collectMoneyBags": true
}
```

Implementing the first key is a two-line change. The second is the real work — see
[known weaknesses](architecture.md#known-weaknesses) for the two approaches that would
do it properly.

---

← [Auto-collect](auto-collect.md) · [Architecture](architecture.md) →
