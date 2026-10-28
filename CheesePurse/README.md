# CheesePurse

> A toggle button in your inventory that sends every coin you pick up straight into
> your Numismatic Overhaul purse.

A small quality-of-life addon. Open your inventory and a round button appears in the
top-left corner:

| State | Appearance | Behaviour |
|-------|------------|-----------|
| **OFF** | grey circle | normal — coins stay in your inventory |
| **ON** | gold circle with a green dot | every coin picked up is deposited into your global purse |

**Minecraft 1.21.1 · Fabric · Java 21 · 5 files, ~250 lines**

---

## Requirements

- **[Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul)** — required.
  Resolved automatically from Modrinth Maven at build time; players need it installed.
- Fabric Loader ≥ 0.17.2, Fabric API, Java 21

No CheeseDash dependency — this one is standalone.

## Installation

Drop the jar in `mods/`, server and client. There is nothing to configure.

## How it works

```
NumismaticCheesePurse (server)
├── registers the C2S toggle packet
├── on receive → updates AUTO_COLLECT_PLAYERS (Set<UUID>)
└── ServerTickEvents.END_SERVER_TICK, every 10 ticks
    └── for each player with the toggle on:
        ├── CurrencyHelper.getMoneyInInventory(player, true)   ← removes the coins
        └── ModComponents.CURRENCY.get(player).modify(value)   ← adds to the purse

NumismaticCheesePurseClient (client)
└── InventoryScreenMixin.init() → injects AutoCollectButton

AutoCollectButton
├── renders the gold or grey circle
├── onClick toggles the local state
└── sends ToggleAutoCollectPayload to the server
```

## Customising the button

Two 20×20 textures, swap them for your own at the same paths:

```
src/main/resources/assets/numismatic-cheesepurse/textures/gui/
├── auto_collect_on.png
└── auto_collect_off.png
```

## Known limitation

**The toggle lives in server memory and resets on restart.** The client remembers its
own state locally and re-syncs on reconnect, so in practice players rarely notice, but
it is not real persistence.

If you want to fix that properly, the two reasonable routes are Fabric
`AttachmentTypes` (`EntityDataAttachment`) to store it in the player's data, or
Cardinal Components — which is what Numismatic Overhaul itself uses for
`ModComponents.CURRENCY`.

## Note on history

This mod started life as `autocoinpurse` before being renamed. The old
`com.example.autocoinpurse` package and its duplicated assets were still in the source
tree, dead but compiled; they were removed when the mod was published. If you find
references to `autocoinpurse` in an old branch, that is why.

## Building

```bash
./gradlew build    # -> build/libs/numismatic-cheesepurse-1.0.0.jar
```

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators. Numismatic Overhaul itself is MIT, by glisco, Pois1x and Noaaan.
