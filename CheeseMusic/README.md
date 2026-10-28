# CheeseMusic

> Ambient music that changes as players cross region borders, with crossfades.

Assign a track to a CheeseGuard zone and it plays while players are inside, fading in
and out at the boundary instead of cutting. Battle music can take over when a Cobblemon
fight starts.

**Minecraft 1.21.1 · Fabric · Java 21 · 16 files, ~1 500 lines**

---

## Requirements

- Fabric Loader ≥ 0.16.0, Fabric API, Java 21

Both optional, but it does little without the first:

- **CheeseGuard** — supplies the zones music is attached to
- **CheeseDash** — admin panel for assignments

## Installation

Drop the jar in `mods/`, server and client. **Clients need it too** — the playback is
client-side.

## Sound files

**No audio ships with this mod.** You supply the tracks yourself, in a resource pack
served to your players, and reference their sound events by id in the dashboard. The
`SoundScanner` enumerates whatever sound events are available so you can pick from a
list rather than typing ids blind.

That also means there is no licensing question attached to this repository — whatever
music you use is between you and its rights holder. Be careful here: most game and
film soundtracks are not licensed for redistribution in a modpack.

## Dashboard

Registers under the `music` prefix: zone-to-track assignments, play modes, fade
durations, and the scanned sound list.

## Playing nicely with ReactiveMusic

If **[ReactiveMusic](https://modrinth.com/mod/reactive-music)** by CircuitLord is
installed, `ReactiveMusicHook` coordinates with it so the two do not fight over the
music channel — CheeseMusic fades ReactiveMusic out while a zone track plays, and hands
control back afterwards.

The hook is pure reflection (`Class.forName("circuitlord.reactivemusic.ReactiveMusic")`),
so there is no dependency and no shared code. If ReactiveMusic is absent the hook logs
once and does nothing.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
