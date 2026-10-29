[← CheeseItems](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](morphos.fr.md)

# Morphos

A morpho is a Pokémon appearance a player can unlock and then wear. The subsystem shares
the jar with the custom items and almost nothing else: different config files, different
dashboard module, different code path. The only link is the item `useActions`, which is
how a quest item or a shop purchase ends up granting a morpho.

Three objects, three storage locations:

| Object | Owns | File |
|---|---|---|
| Catalog entry (`MorphoEntry`) | what a morpho *is*: species, form, shiny, title, price, availability, commands | `config/cheeseitems_morphos.json` |
| Codex entry (`MorphoCodexEntry`) | the fact that one player owns one morpho, and when | `config/cheeseitems_morpho.json` |
| Endurance pool (`MorphoEnduranceTracker.State`) | how much transformation time a player has left | memory only |

The catalog is the single source of truth. A codex entry stores a copy of the species,
form and shiny flag at grant time, but the GUI, the transformation and the endurance all
read the catalog, so editing an entry in the dashboard changes it for everyone who owns it
without a restart.

## <a id="the-catalog-entry"></a>The catalog entry

| Key | Type | Default | What it does |
|---|---|---|---|
| `id` | string | 8 hex characters, generated | identity. Referenced by codex entries and by `/cheeseitem morpho grant` |
| `title` | string | `""` | the name shown in the GUI and the store. Empty falls back to `species` |
| `description` | string | `""` | shown by the store website |
| `species` | string | `""` | passed to `cshift transform`. **Empty makes the morpho unusable** |
| `form` | string | `""` | optional form argument for `cshift` |
| `shiny` | boolean | `false` | appends `shiny` to the `cshift` command |
| `hasImage` | boolean | `false` | maintained by the mod when an image is uploaded or deleted. Do not set it by hand |
| `priceCredits` | integer | `0` | price on the website, in CheeseStore credits. Clamped to ≥ 0 |
| `priceGold` | integer | `0` | price in the NPC shop. Clamped to ≥ 0 |
| `availableOnWebsite` | boolean | `false` | includes the entry in `getSortedForWebsite()` |
| `availableInNpcShop` | boolean | `false` | includes the entry in `getSortedForNpcShop()` |
| `isNew` | boolean | `false` | pins the entry to the top of every sorted listing |
| `sortOrder` | integer | `0` → appended | position among non-new entries, ascending |
| `createdAt` | epoch millis | now | tie-break, and the sort key among `isNew` entries |
| `terrestrial`, `aquatic`, `aerial` | boolean | `false` | habitat badges. Purely cosmetic |
| `legendary` | boolean | `false` | a star badge. Purely cosmetic |
| `commandsOnTransform` | array of strings | `[]` | extra commands run after the transform |
| `commandsOnRevert` | array of strings | `[]` | extra commands run after the revert |
| `useCustomEndurance` | boolean | `false` | when true, the three `custom*` keys replace the global values |
| `customCapacity` | integer | `100` | clamped to ≥ 1 |
| `customDrainPerSecond` | float | `2.0` | clamped to ≥ 0 |
| `customRegenPerSecond` | float | `1.0` | clamped to ≥ 0 |

`availableOnWebsite` and `availableInNpcShop` are filters the mod computes but never
consumes itself. The two sorted getters exist for the store website and for the Métamorph
NPC, which are outside this mod. Setting them changes nothing in the in-game Codex: a
player sees every morpho they own, available or not.

Sorting, wherever a sorted list is produced: `isNew` entries first, newest `createdAt`
first among them; then the rest by `sortOrder` ascending, `createdAt` ascending as a
tie-break. `POST /morphos/reorder` renumbers `sortOrder` from 1 in the order you send the
ids — ids it does not recognise are skipped without error.

The file on disk is written in a different order (by `sortOrder`, then `createdAt`) purely
so it stays readable in a text editor.

### Images

An uploaded PNG lands at `config/cheeseitems_assets/morphos/<id>.png` and sets
`hasImage` on the entry. A ≤480 px thumbnail is generated on first request and cached;
uploading or deleting the original invalidates it. Both are served without
authentication — [the two public routes](dashboard.md#public-morpho-routes).

## The per-player Codex

`config/cheeseitems_morpho.json` maps a player UUID to the list of morphos they own plus
the id of the active one:

```json
{
  "players": {
    "f8a2b1c4-0000-4000-8000-000000000001": {
      "entries": [
        { "itemId": "a3f91c2e", "species": "ditto", "form": "", "shiny": false, "acquiredAt": 1761700000000 }
      ],
      "active": "a3f91c2e"
    }
  }
}
```

The field is called `itemId` rather than `morphoId` for backward compatibility with the
first version of the subsystem, where a morpho was a custom item. Nothing reads it as an
item id any more.

The first morpho a player receives becomes their active one automatically
(`putIfAbsent`). After that, the active morpho only changes when the player clicks one in
the GUI. Setting an active id the player does not own is ignored silently.

Writes are debounced: a mutation marks the codex dirty and saves immediately if the last
save was more than five seconds ago, otherwise the save waits for the next mutation.
`SERVER_STOPPING` flushes. ⚠️ A crash within five seconds of a grant can lose that grant,
because nothing re-saves on a timer — the debounce only fires on the *next* write.

### Pruning

Every sync prunes codex entries whose catalog id no longer exists, and logs one line per
removal:

```text
[CheeseItems] Pruned orphan morpho 'a3f91c2e' from Steve's codex (no catalog entry)
```

Deleting a catalog entry from the dashboard therefore takes it away from every player who
owned it, immediately for online players and at next login for the rest. There is no undo:
re-creating the entry produces a new id.

## Granting a morpho

Four ways, all ending in `MorphoCodex.addToCodex`:

| Route | Who uses it |
|---|---|
| `/cheeseitem morpho grant <player> <morpho_id>` | an operator, in game. Online players only |
| `POST /api/v1/morphos/grant` | the dashboard, and the store's delivery hook. Works offline through the user cache |
| An item's `useActions` running the command above | a quest reward or a redeemable token |
| `MorphoManager.addToCodex(player, entry)` | any sibling mod holding the instance |

A grant that duplicates an existing one is refused: the command answers
`<name> already owns this morpho.` and the API answers `409`.

## Wearing a morpho

The transformation is not implemented in this mod. `MorphoManager` builds a command string
and hands it to the server's own dispatcher:

```text
cshift transform <player> <species> [form] [shiny]
```

then runs every entry in `commandsOnTransform`, with `{player}` replaced by the player's
name, then starts the endurance pool. Reverting runs `cshift revert <player>`, then
`commandsOnRevert`, then stops the drain.

⚠️ This is an observation of how the two mods currently fit together, not a contract.
[CobbleShift](../../CobbleShift/) is not declared as a dependency and is not called
through an API — if `cshift` changes its argument order or its name, every morpho stops
transforming and the only trace is a warning:

```text
[CheeseItems] cshift command failed: cshift transform Steve ditto (…)
```

A failed `cshift` does not stop the rest: the extra commands still run, the endurance pool
still drains, and the player is still told *Transformation: Ditto*. The mod has no way to
know whether the transform actually happened.

### The toggle

`MorphoManager` keeps a map of who is currently transformed, which is what makes `H` a
toggle: first press transforms, second press reverts. That map is **memory only**. A
restart while a player is transformed leaves them transformed as far as CobbleShift is
concerned, while CheeseItems believes they are not — their next `H` press transforms them
again instead of reverting. `cshift revert` by hand is the way out.

Pressing `H` with no active morpho sends *No active morpho selected. Open the Codex with
[J].* An active morpho whose catalog entry has an empty `species` sends *Active morpho is
invalid (item removed?).*

## The endurance pool

One pool per player, not per morpho. Capacity and rates are resolved at transform time
from the entry: its `custom*` values when `useCustomEndurance` is true, the catalog's
`globalEndurance` block otherwise.

```json
"globalEndurance": { "capacity": 100, "drainPerSecond": 2.0, "regenPerSecond": 1.0 }
```

With those defaults a player gets 50 seconds of transformation and needs 100 seconds of
human form to refill. The pool ticks every server tick: `drainPerSecond / 20` removed per
tick while transformed, `regenPerSecond / 20` added per tick while not.

At zero, the pool stops draining and `forceRevert` runs the full revert path, including
`commandsOnRevert`. Pressing `H` again before the pool has regenerated is refused with
*Endurance depleted!* — and note what the code does in that case: the `cshift transform`
command **has already been executed** by the time the tracker refuses, because the refusal
happens inside `start()`, after `doTransform` ran the command. The player ends up
transformed with `draining = false`, so nothing will revert them. This is a real bug; the
workaround is `/cshift revert <player>`.

Lowering `capacity` while someone is transformed clamps their current value down on the
next transform, not immediately. Raising it takes effect on their next transform too.

The pool is drawn in the actionbar four times a second while it is draining or refilling,
and stops being drawn once it is full. The state is dropped from memory at that point, and
also on disconnect. Nothing about endurance is persisted: a reconnect gives the player a
full pool.

## The in-game Codex GUI

The client half of the jar adds the screen and two keybinds, both in the `CheeseItems`
category and both rebindable:

| Key | Default | Action |
|---|---|---|
| Open Morpho Codex | `J` | opens the screen |
| Toggle Morpho | `H` | sends the toggle packet to the server |

`/morpho` opens the same screen by asking the client to do it, which is useful for NPC
dialogue. The screen only opens when no other screen is open.

The screen draws what the last sync sent — it never queries the server itself. Clicking an
entry sends `MorphoSetActiveC2SPayload`, which sets the active morpho and triggers a fresh
sync. Images are fetched over HTTP from `/morphos/api/thumb/<id>` on the host the player
connected to, using the CheeseDash port the sync told the client about, and cached as
textures until the next sync invalidates them.

A player whose client does not have the jar sees none of this and cannot toggle anything,
because the toggle is a client-sent packet. They can still own morphos, and an operator
can still transform them with `cshift` directly.

### What a sync contains

Sent on join (one tick after the connection event), after every grant, and after every
`setActive`:

| Field | Source |
|---|---|
| `active` | the codex, `""` when none |
| `cheesedashPort` | CheeseDash's config, so the client knows where to fetch images |
| `balance` | CheeseStore's wallet through reflection, `-1` when CheeseStore is absent |
| `currencyName` | CheeseStore's config, omitted when unknown |
| `entries[]` | one object per owned morpho: `itemId`, `species`, `form`, `shiny`, `displayName`, `hasImage`, `legendary`, `terrestrial`, `aquatic`, `aerial` |

`displayName` comes from the **catalog** title, falling back to the species. That is why a
rename in the dashboard shows up in every player's GUI without a restart.

CheeseStore is reached with `Class.forName("com.cheesestore.WalletManager")` and friends.
It is not declared in `fabric.mod.json` at all — not even as a `suggests`, despite the
hook's own comment claiming it is. When it is missing the balance line is hidden and a
single line appears in the log:

```text
[CheeseItems] CheeseStore not available: com.cheesestore.WalletManager
```

---

← [Resource pack](resource-pack.md) · [Dashboard API](dashboard.md) →
