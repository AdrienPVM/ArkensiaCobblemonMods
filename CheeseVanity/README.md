# CheeseVanity

> 🇫🇷 [Lire en français](README.fr.md)

> Players hide their own armor and trinkets without taking them off.

A player opens their inventory, hovers the slot holding a cloak, presses `=`, and the
cloak stops being drawn on their character. It is still equipped and still does whatever
it does; it has simply gone invisible. Press `=` again and it comes back. That is the
entire mod.

The preference is stored on the server, per player UUID, and broadcast to every online
player. So the change is not a private client-side illusion: you see it in third person,
and every other player running the mod sees the same model you do. ⚠️ A player connecting
**without** CheeseVanity in their `mods/` folder still sees all of your gear, and nothing
on the server can change that — the hiding is done by client-side render mixins. If
visual consistency matters on your server, make this a required client mod.

Unlike the rest of this repository, CheeseVanity is a **player** tool rather than an admin
one. There are no commands, no permission levels and no dashboard module. An operator's
only lever is a three-key config file that decides which kinds of slot players are allowed
to hide.

**Minecraft 1.21.1 · Fabric · Java 21 · 10 source files, ~630 lines**

---

## Requirements

| | |
|---|---|
| **Required** | Fabric Loader ≥ 0.16.0 · [Fabric API](https://modrinth.com/mod/fabric-api) · Java 21 |
| **Optional** | [Trinkets](https://modrinth.com/mod/trinkets) — compiled against 3.10.0, not bundled |

Needed on **both sides**: the server stores and broadcasts the preference, the client is
the only thing that can act on it.

⚠️ Without Trinkets, only the four vanilla armor slots can be hidden — and
`enableVanillaArmor` is `false` by default, so on a fresh install with no Trinkets the mod
does nothing at all until you edit the config. That default is deliberate: on the Arkensia
server another mod owns armor visibility.

No CheeseDash dependency and no sibling-mod dependency. This one builds and runs on its
own.

## Install

```bash
cd CheeseVanity && ./gradlew build
```

Drop `build/libs/cheesevanity-1.0.0+1.21.1.jar` in the server's `mods/` **and** in each
client's `mods/`. On first start the server writes `config/cheesevanity.json` and logs:

```text
[CheeseVanity] Loaded — vanilla armor: false, trinkets: true
```

Full walkthrough: [installation](docs/installation.md).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, which side needs the jar, what the first launch creates, the four checks that prove it works, clean removal |
| [Getting started](docs/getting-started.md) | A player's tutorial: hide a trinket, see it vanish, have someone else confirm it, bring it back |
| [Controls](docs/commands.md) | The `=` key documented input by input, and every one of its silent failures |
| [Configuration](docs/configuration.md) | `cheesevanity.json`: the three keys, the order they are evaluated in, and a full annotated example |
| [Who sees what](docs/visibility.md) | The slot-key model, the client/server round trip, the exact boundary of the hiding, and where the preference is stored |
| [Architecture](docs/architecture.md) | Package layout, the two opposite render strategies, threading, every mixin, and a candid list of weaknesses |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — the five different reasons `=` appears to do nothing |

There is no `dashboard.md`: the mod registers no CheeseDash module and exposes no REST
route.

## Controls at a glance

No commands exist. One key does everything, and any player can use it.

| Input | What it does |
|---|---|
| `=` over a Trinkets slot | toggles that trinket's visibility on your model |
| `=` over one of your four armor slots | the same, if `enableVanillaArmor` is `true` |

Hidden slots are marked in the inventory screen with a translucent red fill and a red
cross. Details, including why a press can be silently refused, in
[controls](docs/commands.md).

## Honest warnings

- **The `=` key is hardcoded.** It is not a Minecraft key binding, so it does not appear
  in Options → Controls and cannot be rebound in game.
- **Nothing tells a player why a toggle was refused.** Every rejection is silent on the
  client; a config refusal is printed in the server log, the rest leave no trace there.
- **Trinket hiding is a trick.** The mixin empties the hidden trinket slots for the
  duration of one render call and puts the stacks back afterwards, because the Trinkets
  renderer offers no per-slot seam. It will break when Trinkets changes that method, and
  it will break **silently** — the injections are declared `require = 0`.
- **Debug logging ships at `INFO`.** Every `=` press writes two lines to the client log.
- **The config is read once at startup.** No reload; restart the server after editing it.
- **Tightening the config does not un-hide anything** already stored. The data file has to
  be edited by hand with the server stopped.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
