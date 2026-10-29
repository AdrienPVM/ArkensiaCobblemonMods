[← CheesePurse](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](auto-collect.fr.md)

# Auto-collect

The whole mod is one loop and one set. This page covers what the loop actually does,
which is narrower and broader than the name suggests in different places: narrower
because it is not hooked to item pickup at all, broader because it does not restrict
itself to what was picked up.

---

## The model

Three pieces of state, and it helps to keep them apart because two of them can disagree.

| State | Lives where | Persisted? |
|---|---|---|
| `AUTO_COLLECT_PLAYERS`, a `Set<UUID>` | server memory, `NumismaticCheesePurse` | no |
| `autoCollectEnabled`, a `boolean` | client memory, `NumismaticCheesePurseClient` | no |
| The purse balance | Numismatic's `CurrencyComponent` | yes, by Cardinal Components |

The client boolean decides which texture the button shows. The server set decides
whether coins actually move. They are synchronised in one direction only — client to
server, on click. Nothing ever tells a client what the server believes. When they drift
apart, the player sees one thing and gets another; that is the single most common
confusing behaviour in this mod and it has [its own troubleshooting entry](troubleshooting.md#the-button-shows-grey-but-my-coins-still-disappear).

## The loop

`NumismaticCheesePurse.onInitialize` registers a handler on
`ServerTickEvents.END_SERVER_TICK`. The handler increments a counter and returns early
until the counter reaches `SCAN_INTERVAL_TICKS`, which is `10`:

```java
private static final int SCAN_INTERVAL_TICKS = 10;
```

Ten ticks is half a second at a healthy 20 TPS. On a lagging server it is longer in wall
time, because the loop counts ticks, not milliseconds. On a server running at 10 TPS the
sweep happens once a second.

When the counter fires it resets to zero and walks
`server.getPlayerManager().getPlayerList()` — every online player on the server, in one
pass. Players not in `AUTO_COLLECT_PLAYERS` are skipped with a `Set.contains` on their
UUID. For the rest, `collectCoinsForPlayer` runs.

There is one counter for the whole server, not one per player. Every opted-in player is
swept on the same tick.

### What `collectCoinsForPlayer` does

Two calls, both into Numismatic Overhaul:

```java
long totalValue = CurrencyHelper.getMoneyInInventory(player, true);
if (totalValue <= 0) return;
ModComponents.CURRENCY.get(player).modify(totalValue);
```

The code comment says this replicates what Numismatic's own "store all" button does, and
that is accurate — `RequestPurseActionC2SPacket` handles its `STORE_ALL` action with the
same two calls in the same order. CheesePurse is that button on a timer.

The early return on `totalValue <= 0` is a small saving rather than a visible one.
Without it, every opted-in player with no coins would get a `modify(0)` call twice a
second; `modify(0)` still rewrites the component value, but it sends no message —
Numismatic returns before the notification when the delta converts to no coins. The
guard spares that write, not the player's screen.

---

## Answers to the five questions everyone asks

### Is the deposit immediate or delayed?

**Delayed, by up to ten server ticks.** There is no pickup hook anywhere in this mod.
Nothing observes `ItemEntity`, nothing mixes into `PlayerEntity.sendPickup`, and the
word "pickup" appears only in the tooltip text. The loop is a timer that scans
inventories, so a coin sits in the player's inventory for somewhere between one tick and
ten before it moves, depending on where in the cycle it landed.

A player with their inventory open can see this. With a fast enough eye, or a screen
recording, the stack is visibly there and then gone.

### What happens when the inventory is full?

**The mod never sees the coins, because they never reach the inventory.**

Pickup is vanilla. A player whose 36 main slots are all full and have no room to merge
cannot pick a coin stack up at all — it stays on the ground and despawns on vanilla's
schedule. CheesePurse only reads what is already in the inventory, so a coin that failed
to be picked up is not part of the problem it solves.

This is worth saying plainly because the feature's name invites the opposite assumption.
CheesePurse does **not** rescue coins from a full inventory. If anything it makes a full
inventory less likely, by freeing the slots coins would otherwise occupy — but only
after the coins got in.

The deposit direction never fails for space reasons. A purse is a `long` in a component,
not a container, so there is no capacity to run out of. The theoretical ceiling is
`Long.MAX_VALUE` bronze units, and nothing in either mod guards against overflowing it;
reaching that requires about 9.2 × 10¹⁸ bronze, which no legitimate economy produces.

### Are all denominations affected?

**Yes, and more than you may expect.**

`getMoneyInInventory` tests each stack with `stack.getItem() instanceof CurrencyItem`.
In Numismatic Overhaul 0.3.4 the classes implementing `CurrencyItem` are `CoinItem` and
`MoneyBagItem`, and the registered instances are:

| Item | Class | Value |
|---|---|---|
| `numismatic-overhaul:bronze_coin` | `CoinItem` | 1 bronze unit each |
| `numismatic-overhaul:silver_coin` | `CoinItem` | 100 bronze units each |
| `numismatic-overhaul:gold_coin` | `CoinItem` | 10 000 bronze units each |
| `numismatic-overhaul:money_bag` | `MoneyBagItem` | whatever its component holds |

So all three coins, and money bags too. A money bag a player is carrying as a single
compact store of value gets dissolved into their purse on the next sweep like anything
else. If your server uses money bags as a deliberate item — a quest reward meant to be
handed to someone else, say — tell players to turn the toggle off before carrying one.

The scan covers `player.getInventory().size()`, which in 1.21.1 is the 36 main slots
plus the 4 armour slots plus the offhand. A coin in the offhand is taken. Ender chests,
shulker boxes and any other container are not scanned, so coins stored in them are safe.

⚠️ The list above is read from Numismatic Overhaul 0.3.4's source, not from a published
specification. Another mod that implements `CurrencyItem` on its own item would also be
swept, and a future Numismatic version could add or change items. Treat it as an
observation of one version.

### Is this per-player or global?

**Per-player, keyed by UUID, and set by the player.**

There is exactly one way into `AUTO_COLLECT_PLAYERS`: a `ToggleAutoCollectPayload`
arriving from a client, whose handler reads `context.player()` and uses that player's
UUID. A player can only ever toggle themselves. There is no command, no permission
check, no API, and no admin path — the server cannot enable or disable the feature for
somebody else, and it cannot enable it for everybody.

The set is not consulted for anything but the loop, and it is never written from
anywhere but the packet handler.

### How do you turn it off?

Four ways, in descending order of how much you probably want them:

1. **Click the button again.** The client flips its boolean and sends
   `ToggleAutoCollectPayload(false)`; the server removes the UUID.
2. **Restart the server.** `AUTO_COLLECT_PLAYERS` is a field initialiser on a static
   `HashSet` and is rebuilt empty on every start. Every player is off after a restart,
   whatever their client is drawing.
3. **Remove the jar** from the server. Nothing persists, so there is nothing to clean up.
4. There is no fourth way. No config key, no command, no gamerule, no dashboard switch.

**Logging out does not turn it off.** The handler never removes a UUID on
disconnect, so a player who toggles on, leaves, and comes back is still enabled
server-side while their client, if it was restarted in between, shows grey. That
asymmetry is the source of the desync described below.

---

## The purse-extraction trap

The loop does not know where a coin came from. It reads the inventory, and anything in
it that is a `CurrencyItem` goes into the purse.

That includes coins the player has just taken **out** of the purse. Numismatic's purse
screen has withdraw controls; using them while auto-collect is on produces this:

```text
player clicks "extract 500"
 → Numismatic credits 5 silver coins to the inventory, debits 500 from the purse
 → up to 0.5 s passes
 → CheesePurse sweeps the 5 silver coins, credits 500 back to the purse
 → the player sees  - [5 Silver]  then  + [5 Silver]  in the action bar
```

Net effect: nothing, twice. The money is not lost and nothing is duplicated — both
operations are value-preserving — but a player trying to get physical coins to hand to
another player, or to put in a chest, cannot do it without turning the toggle off first.

The same applies to any other source of coins: a chest, a villager trade, a `/give`, a
loot drop, another mod paying out in Numismatic currency. The sweep treats them all
identically.

This is not a bug in the sense of a mistake in the code; it is the direct consequence of
scanning inventories on a timer instead of hooking pickup. A pickup hook would be the
fix, and it is the first thing to change if this behaviour is unacceptable on your
server. See [extension points](architecture.md#extension-points).

## What the player sees, and who decides

CheesePurse itself sends the player no message. Every notification comes from
Numismatic's `CurrencyComponent.modify`, which formats the delta as a list of coin names
and respects the **client's** `moneyMessageLocation` option, synced to the server by
owo-lib:

| `moneyMessageLocation` | Result of each sweep |
|---|---|
| `ACTIONBAR` (default) | `+ [3 Silver, 12 Bronze]` above the hotbar |
| `CHAT` | `numismatic > + [3 Silver, 12 Bronze]` in chat |
| `DISABLED` | nothing |

A player who finds the notification noisy sets it to `DISABLED` in Numismatic's own mod
options; the Numismatic config comments say that change requires rejoining to apply.
Nothing in CheesePurse can change it for them, and CheesePurse deliberately calls
`modify` rather than `silentModify` — the feedback is the only confirmation a player
gets that the deposit happened.

With the `CHAT` setting and a steady coin income, this means a chat line every half
second. That is a real consideration if your server's economy drip-feeds currency.

## Performance

Each sweep is one `Set.contains` per online player, plus, for each opted-in player, a
walk of 41 inventory slots with an `instanceof` test on each. That runs on the server
thread, synchronously, twice a second.

With fifty opted-in players that is about 4 100 slot checks per second. It is not
nothing, but it is well inside the noise of a normal tick, and it does no I/O: the purse
write is a `long` assignment plus a component sync packet, and there is no file write
anywhere in the path.

The expensive part, if any, is `modify` building and sending a text message per deposit
per player. On a server where many players run the toggle and earn coins constantly,
that is one chat packet each per sweep.

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
