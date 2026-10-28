# AetherialBridge

> Makes Aetherial Islands' floating-island worldgen available as a CheeseGuard
> dimension type.

A deliberately tiny adapter. CheeseGuard can create dimensions at runtime, each with a
generation type; this registers "Aetherial Islands" as one of those types, so you can
spin up a floating-island dimension from the dashboard.

**The vanilla overworld is left completely untouched.** Only dimensions you explicitly
create with that gen-type use the worldgen — which is the whole reason this exists
rather than just installing the datapack globally.

**Minecraft 1.21.1 · Fabric · Java 21 · 7 files, ~440 lines**

---

## Requirements

- **CheeseGuard** — required (and therefore CheeseDash)
- **Aetherial Islands (DimScope)** — required, the mod whose worldgen this exposes.
  Declared as `mr_aetherial_islands`.
- Fabric Loader ≥ 0.15.0, Fabric API

Build CheeseDash and CheeseGuard first, then drop all three in `mods/` along with
Aetherial Islands.

## How it works

It registers a `FloatingIslandsProvider` through CheeseGuard's `cheeseguard-gentype`
entrypoint — the same extension mechanism any other mod could use to contribute a
generation type. If you want to add your own worldgen to CheeseGuard's dimension
creator, **this is the smallest example to copy.**

## ⚠️ This is glue, not a feature

It does nothing without Aetherial Islands installed, and it is coupled to that mod's
internals. If Aetherial Islands changes its chunk generator, this breaks. No support
offered; it is published mainly as a worked example of the gen-type entrypoint.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators. Aetherial Islands belongs to its own authors and is not redistributed here.
