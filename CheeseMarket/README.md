# CheeseMarket

> Player-facing shops with a custom full-screen GUI, and a layout editor in the
> dashboard.

Admins design shops in CheeseDash — sets, items, prices, layout — and players browse
them in-game through a full-screen interface rather than the usual chest-inventory
trick. Transactions and statistics are recorded and visible from the dashboard.

**Minecraft 1.21.1 · Fabric · Java 21 · 109 files, ~23 000 lines**

---

## Requirements

- **CheeseDash** — required
- Fabric Loader ≥ 0.15.11, Fabric API, Java 21

Optional: **CheeseStore** (premium currency) and **CheeseItems** (selling custom quest
items).

## Installation

Build CheeseDash first, then drop both in `mods/`.

## Usage

Players open a shop through the shop block or the command exposed by the mod; the
full-screen GUI handles browsing and buying. Shop definitions, item sets, prices and
the visual layout are all authored in the dashboard — there is no in-game admin
editor, by design.

The in-game command tree is in `commands/`, and the GUI lives in `gui/`.

## Dashboard

Registers under the `market` prefix: CRUD for shops, sets and items, plus transaction
history and statistics.

A `market_purchase` event is pushed over the CheeseDash WebSocket on every purchase.

## Known rough edges

**This mod contains a duplicated copy of the `com.cheeseguard` package** —
specifically `logic/TimelineExecutor.java` and `gui/MainGui.java`. It is a leftover
from when the two mods were split, and it has not been untangled because it sits on
the event execution path shared with CheeseGuard. If you refactor it, test events in
game, not just the build.

The in-game admin GUIs that used to live here (`CheeseGuardScreen`, `EventEditorScreen`
— some 4 700 lines) were deleted when administration moved to the dashboard. If you
find dangling references in old branches, that is why.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
