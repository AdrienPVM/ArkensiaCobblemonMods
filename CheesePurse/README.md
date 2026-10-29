# CheesePurse

> 🇫🇷 [Lire en français](README.fr.md)

> A toggle in the inventory screen that sweeps every Numismatic Overhaul coin out of a
> player's inventory and into their purse, twice a second.

Numismatic Overhaul already has a "store all" button inside its purse screen. A player
who mines for an hour ends up with four stacks of bronze coins and has to remember to
open the purse and click it. CheesePurse removes the remembering: a 12×12 coin button
sits near the top-left of the inventory screen, and while it shows the gold coin, a
server-side loop empties that player's coins into their purse every ten ticks.

The deposit path is the one Numismatic uses for its own button —
`CurrencyHelper.getMoneyInInventory(player, true)` to take the coins, then
`ModComponents.CURRENCY.get(player).modify(value)` to credit the purse. The coins land
in the Cardinal Components currency component that Numismatic persists in player data,
and the player gets Numismatic's own `+ [3 Silver, 12 Bronze]` notification in their
action bar. Nothing about the economy changes; only the clicking goes away.

The mod is deliberately tiny, and it shows. There is no config file, no command, no
dashboard module, and the toggle lives in a plain in-memory `HashSet` that empties when
the server restarts. Read [the known weaknesses](docs/architecture.md#known-weaknesses)
before putting it on a server with a serious economy.

**Minecraft 1.21.1 · Fabric · Java 21 · 5 source files, ~260 lines**

---

## Requirements

| | |
|---|---|
| **Required** | [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) (built against 0.3.4) · Fabric Loader ≥ 0.17.2 · Fabric API · Java 21 |
| **Optional** | nothing |

Numismatic Overhaul is a hard dependency, declared `"numismatic-overhaul": "*"` in
`fabric.mod.json`. Neither the server nor the client will launch without it. Its classes
are imported directly rather than reached by reflection, so there is no degraded mode to
fall back on.

CheesePurse does **not** depend on CheeseDash. It registers no dashboard module and
exposes no REST route.

## Install

```bash
cd CheesePurse && ./gradlew build
```

Drop `build/libs/numismatic-cheesepurse-1.0.0+1.21.1.jar` into the server's `mods/` and
into every player's `mods/`, next to Numismatic Overhaul and Fabric API. The server log
then shows:

```text
[NumismaticCheesePurse] Initializing…
[NumismaticCheesePurse] Ready.
```

Nothing is written to `config/`. Full walkthrough: [installation](docs/installation.md).

⚠️ **The jar is not named after the folder.** The folder is `CheesePurse`, the mod id is
`numismatic-cheesepurse`, and the jar is `numismatic-cheesepurse-<version>+1.21.1.jar`.
No file called `cheesepurse-*.jar` exists anywhere in the build output.
[Why](docs/installation.md#the-three-names-of-this-mod).

## Documentation

| Page | What it covers |
|---|---|
| [Installation](docs/installation.md) | Prerequisites, the three names this mod answers to, which side needs what, verifying it loaded, clean removal |
| [Getting started](docs/getting-started.md) | A tutorial: from a blank server to watching coins leave your hotbar for your purse |
| [Auto-collect](docs/auto-collect.md) | The loop in detail: what counts as a coin, the half-second delay, a full inventory, the purse-extraction trap, per-player scope |
| [Configuration](docs/configuration.md) | There is no config file. What is hard-coded, which line to edit, and which Numismatic options change what you see |
| [Architecture](docs/architecture.md) | Five classes, one client mixin, threading, the state that is not persisted, and the residue of the old project name |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix — including the desync that makes coins vanish while the button shows grey |

## Commands at a glance

None. CheesePurse registers no command and offers no operator-facing control. Everything
happens through the inventory button, which each player drives for themselves.

## Honest warnings

- **The toggle does not survive a restart.** The server keeps it in memory. After a
  restart every player is OFF on the server side, while their client may still be
  drawing the gold coin.
  See [the desync symptom](docs/troubleshooting.md#the-button-shows-grey-but-my-coins-still-disappear).
- **The loop sweeps the whole inventory, not only what was picked up.** Coins you pull
  out of your own purse go back in within half a second while the toggle is on. This
  surprises people. [Details](docs/auto-collect.md#the-purse-extraction-trap).
- **It compiles against Numismatic Overhaul 0.3.4 with direct imports.** A rename of
  `CurrencyHelper`, `ModComponents` or `CurrencyComponent.modify` in a future Numismatic
  release breaks the mod at class-load time rather than degrading.
- The happy path ran on the Arkensia server. The failure modes listed in
  [troubleshooting](docs/troubleshooting.md) were read out of the code; not all of them
  were reproduced deliberately.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE), with an Additional Permission for server
operators. You may run this on a server that takes donations or sells ranks; you may not
sell the mod itself.

This mod's `fabric.mod.json` declares `PolyForm-Noncommercial-1.0.0` and there is no
separate `LICENSE` file inside `CheesePurse/`, so it sits under the same terms as the
rest of the repository. Numismatic Overhaul, which it depends on and compiles against,
is a separate work under the MIT license by glisco, Pois1x and Noaaan. Nothing here
relicenses it, and no Numismatic code is copied into this mod.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB)
