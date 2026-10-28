# CheeseVanity

> Let players hide their own armor and trinkets without taking them off.

Purely cosmetic, purely client-visible: a player can toggle the visibility of armor
pieces and Trinkets accessories on their model. The equipment still works — it just
stops showing.

**Minecraft 1.21.1 · Fabric · Java 21 · 10 files, ~550 lines**

---

## Requirements

- Fabric Loader ≥ 0.16.0, Fabric API, Java 21
- **[Trinkets](https://modrinth.com/mod/trinkets)** — optional. Without it, only armor
  can be hidden.

No CheeseDash dependency — this one is standalone.

## Installation

Drop the jar in `mods/`. Needed on both sides: the server tracks each player's
preference, the client renders it.

## Why you might want it

Cosmetic mods and armor fight each other constantly — a nice cloak hidden under a
chestplate, a hat clipping through a helmet. This is the small fix for that, without
asking players to choose between looking right and being protected.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
