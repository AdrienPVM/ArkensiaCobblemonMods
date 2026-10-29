[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](tickets.fr.md)

# Tickets

A dungeon ticket is the entry fee. One item, `cobblemon_dungeons:dungeon_ticket`, carries
two pieces of custom data that decide what it opens:

| NBT key | Type | Meaning |
|---|---|---|
| `DungeonId` | string | The `general.id` of the dungeon this ticket is for |
| `Tier` | int | The tier it is worth |

Both live under `DataComponentTypes.CUSTOM_DATA`. The display name comes from
`DataComponentTypes.CUSTOM_NAME` and the tier is also written to
`CUSTOM_MODEL_DATA`, which is what gives tiers 1 to 5 their own textures
(`dungeon_ticket_1.json` through `dungeon_ticket_5.json`).

The tooltip shows both values, read back from the NBT:

```text
§6Copper Dungeon Ticket
Dungeon: generic_dungeon
Tier: 1
```

---

## Where ticket templates are defined

`config/cobblemon_dungeons/dungeon_tickets.json`. One array, `tickets`, of templates:

```json
{
  "tickets": [
    {
      "dungeonId": "generic_dungeon",
      "itemName": "§6Copper Dungeon Ticket",
      "tier": 1
    }
  ]
}
```

| Key | Type | Default | What it does |
|---|---|---|---|
| `dungeonId` | string | `"generic_dungeon"` | Written to the ticket's `DungeonId` |
| `itemName` | string | `"§6Copper Dungeon Ticket"` | Display name, and the handle loot tables use to refer to this ticket |
| `tier` | int | `1` | Written to `Tier` and to `CUSTOM_MODEL_DATA` |

The file is created on first launch with exactly that one entry, and the same fallback
runs whenever the list would be saved empty.

`itemName` is not cosmetic. It is the identifier a loot pool uses to hand out a ticket, so
changing it breaks every loot entry that referenced the old name.

## How the gate works

`consumeDungeonTicket` walks the leader's whole inventory looking for a
`dungeon_ticket` stack whose `DungeonId` equals the dungeon's id **and** whose `Tier` is
greater than or equal to the requested tier. The first match is decremented by one.

Two things follow from `tier >= requiredTier`:

- A Tier 5 ticket opens a Tier 1 run. Tiers are a floor, not an exact match.
- The first matching stack in inventory order is spent, which may be your Tier 5 ticket
  when a Tier 1 would have done. There is no "cheapest first" logic.

No match means no entry: *You need a valid Dungeon Ticket for <name> (Tier N) to enter.*

Only the party **leader** is charged. Everyone else enters on the leader's ticket.

## Getting tickets

Three routes exist in the code.

### 1. Dungeon chest loot

A loot pool entry can name a ticket in its `item` field. The resolver tries the string as
an item id first; when that resolves to `minecraft:air` it falls back to matching the
string against every configured `itemName`, comparing both the raw form and the form with
`§` replaced by `&`. A hit builds a real ticket from that template.

```json
{ "item": "§6Copper Dungeon Ticket", "min": 1, "max": 1, "weight": 5 }
```

Either `§6Copper Dungeon Ticket` or `&6Copper Dungeon Ticket` works. A string that matches
neither an item id nor a ticket name logs `Unknown loot item ID or ticket name: …` and
produces nothing.

### 2. The ticket exchange GUI

The dungeon screen has a **Ticket Exchange** view with four fixed conversions:

| Exchange | Cost |
|---|---|
| Tier 1 → Tier 2 | 5 × Tier 1 |
| Tier 2 → Tier 3 | 3 × Tier 2 |
| Tier 3 → Tier 4 | 3 × Tier 3 |
| Tier 4 → Tier 5 | 3 × Tier 4 |

The rates are hard-coded in `DungeonScreenHandler` and are not configurable.

An exchange counts tickets **by tier only**, ignoring `DungeonId`: five Tier 1 tickets for
five different dungeons are a valid payment. The resulting ticket is built from the first
template in `dungeon_tickets.json` whose `tier` matches the target, so the dungeon the new
ticket points at is whichever template happens to come first in the file.

Failures:

| Message | Cause |
|---|---|
| *Not enough Tier N tickets! Need M.* | Fewer than the required count of the source tier in inventory |
| *No Tier N ticket template found in config!* | No template in `dungeon_tickets.json` carries the target tier |

On success: *Exchanged Nx Tier A tickets for 1x Tier B ticket!*

### 3. CheeseGuard's `DUNGEON_TICKET_GIVE` effect

During a CheeseGuard event, the `DUNGEON_TICKET_GIVE` timeline effect hands tickets to
every participant. It does not read `dungeon_tickets.json` — it builds a template on the
fly with the `dungeonId` and `tier` from the effect and the name
`§6Dungeon Ticket [<dungeonId> T<tier>]`. Tickets created that way therefore look
different from your configured ones even when they open the same dungeon. Details in
[the dashboard page](dashboard.md#cheeseguard-event-effects).

There is no `/give`-style command for tickets. An admin who needs one either runs a
CheeseGuard event effect or hands out the item with vanilla `/give` plus the right custom
data.

## Client-side sync

The ticket config is pushed to each client on join as a `DungeonTicketSyncPayload`
containing the whole JSON, and the client overwrites its local copy. The client needs it
because the exchange GUI renders names and tiers locally.

That sync happens **only on join**. Editing tickets through the dashboard while players
are online updates the server and the file, but connected clients keep the old list until
they reconnect.

---

← [Instances and their lifecycle](instances.md) · [Trainers and RCTApi](trainers.md) →
