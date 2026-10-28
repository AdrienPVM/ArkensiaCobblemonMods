# CobbleCap

> Badge-based Pokémon level cap, champion detection, a Hall of Fame, run timers and
> statue dioramas.

A progression system for Cobblemon servers built around a hard level cap: Pokémon stop
gaining experience past the cap, and the cap rises as players beat gym leaders. On top
of that sits a champion system — the last player to clear the league becomes the
server champion, gets recorded in a persistent Hall of Fame, and can be immortalised
as a block statue.

**Minecraft 1.21.1 · Fabric · Java 21 · 52 files, ~10 600 lines**

---

## Requirements

- Fabric Loader ≥ 0.15.11, Fabric API, Java 21
- **Cobblemon** — optional at build time, required in practice. Accessed through
  reflection, so the mod loads without it and simply does nothing.
- **CheeseDash** — optional, enables the admin panel

## What it does

**Level cap.** `CapManager` plus mixins on Cobblemon's experience and set-level paths
enforce the cap. Beating a gym raises it; each champion defeated adds a further +10.

**Champions.** `ChampionManager` detects victories and records the winner. The RCT
trainer `champion_regis_challenge` is then *morphed* into the last winner — their skin
and their team — by rewriting the trainer's datapack JSON and skin PNG, with a
fail-closed battle check so it never rewrites mid-fight.

**Hall of Fame.** Persisted in `config/cobblecap_legends.json` with each run's best
time and an `isTest` flag that keeps test entries out of public listings.

**Run timers.** `/cobblecap chrono start|stop|cancel|status`, stored in
`config/cobblecap_chronos.json`.

**Statues and dioramas.** Block-built statues of players and Pokémon in thirteen
material styles (natural, stone, gold, emerald, diamond, amethyst, copper, netherite,
obsidian, crystal, glass, frost, soul). A dynamic diorama can track the current
champion and rebuild itself when the champion changes.

**Leaderboards.** Vanilla scoreboards plus a JSON export for CobbleCheeseScore.

**Client HUD.** Shows the current cap. There is also a client-only
`/pasturehide on|off|toggle|status` that hides Pokémon tethered to pasture blocks, for
framerate; the preference lives in `config/cobblecap_client.properties`.

## Commands

All under `/cobblecap`:

| Subcommand | Purpose |
|------------|---------|
| `enable` · `disable` · `mode classic\|ranked` | master switches |
| `get` · `set` · `reset` | read and write the cap |
| `addbeaten` · `removebeaten` | gym progress |
| `champion bind_npc \| morph_now \| refresh_dioramas \| status` | champion NPC |
| `chrono start \| stop \| cancel \| status` | run timer |
| `legends list` · `backup_now` | Hall of Fame |
| `statue place \| place_player \| place_pokemon \| place_current \| remove \| move \| rotate \| scale \| style \| list \| tp \| stats \| styles` | dioramas |
| `statue test \| test_clear` | test mode, isolated from public listings |
| `hud` · `explock` · `reload` | misc |

Plus the client-side `/pasturehide`.

## Configuration

| File | Contents |
|------|----------|
| `config/cobblecap_legends.json` | Hall of Fame |
| `config/cobblecap_chronos.json` | run timers |
| `config/cobblecap_dioramas.json` | placed statues |
| `config/cobblecap_client.properties` | client pasture-hide preference |

## Dashboard

Registers under the `cobblecap` prefix. Also posts Discord webhook notifications on
champion events.

## ⚠️ This is the most server-specific mod here

CobbleCap was written around one particular league format, and it shows:

- **`RctTrainerMorpher.datapackName` is hardcoded to `"CacademyMons"`.** If your
  Cobblemon datapack is named anything else, champion morphing will not find it.
  Change that constant — it is the one edit you almost certainly need to make.
- The champion system assumes an RCT trainer named `champion_regis_challenge`.
- Leaderboard objective names (`conseil4_first_finish`, `conseil4_fastest_time`) come
  from Arkensia's "Conseil 4" league and are not configurable.
- Some player-facing messages name Arkensia. They live in
  `src/main/resources/assets/cobblecap/lang/`.

None of this is hard to change, but none of it is configurable yet either. Treat the
mod as a strong starting point rather than a drop-in.

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) with an Additional Permission for server
operators.
