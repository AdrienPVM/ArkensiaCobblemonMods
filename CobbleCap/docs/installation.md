[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](installation.fr.md)

# Installation

## Which side

**Server-side mandatory, client-side optional.** `fabric.mod.json` declares
`"environment": "*"`, so the same jar works on both, and the jar contains both the server
logic and the client HUD, mixins and `/pasturehide` command.

Install it on the server and the cap is enforced for everyone, including vanilla clients.
Install it on a client as well and that player additionally gets the cap badge,
`/pasturehide`, frozen statue poses and styled statue textures. Nothing desynchronises
either way — see [the client side](client.md).

## Prerequisites

| | Version | Needed |
|---|---|---|
| Minecraft | 1.21.1 | required |
| Java | 21 | required |
| [Fabric Loader](https://fabricmc.net/use/) | ≥ 0.15.11 | required — `depends` |
| [Fabric API](https://modrinth.com/mod/fabric-api) | any | required — `depends` |
| [Cobblemon](https://modrinth.com/mod/cobblemon) | 1.6.1 | `suggests`, but the mod does nothing without it |
| [CheeseDash](../../CheeseDash/) | 1.0.0 | `suggests` — needed for the dashboard module |
| [Radical Cobblemon Trainers](https://modrinth.com/mod/rctmod) | 0.16.11-beta | not declared at all, required for champions |
| FTB Quests | any | not declared, optional |
| [CheeseGuard](../../CheeseGuard/) | 1.0.0 | not declared, optional — only for the fireworks zone |
| [CobbleCheeseScore](../../CobbleCheeseScore/) | 1.0.0 | not declared, optional — reads the leaderboard exports |

Only three of those are real Fabric dependencies. Everything else is reached by
`Class.forName` and reflection, which is why the mod loads cleanly on a server that has
none of them — and does correspondingly less.

⚠️ **rctmod is the honest requirement nobody declares.** The champion system identifies
champions as rctmod trainer NPCs. Without rctmod there is nothing to beat, so the cap never
rises on its own and only `/cobblecap set` moves it. It is absent from both `depends` and
`suggests` in `fabric.mod.json`, which is an oversight rather than a decision.

The dashboard module is different: `CheeseDash` is a **compile-time** dependency resolved
from `../CheeseDash/build/libs` by a glob, so CheeseDash must be *built* before CobbleCap
even though it is only `suggests` at runtime.

## Build

```bash
cd CheeseDash   && ./gradlew build     # CobbleCap compiles against this jar
cd ../CobbleCap && ./gradlew build
```

The dependency is a `fileTree` glob on `cheesedash-*.jar`, not a fixed filename, so any
CheeseDash build works. `tools/build-all.sh` at the repository root walks every mod in
dependency order if you would rather not think about it.

The result is `CobbleCap/build/libs/cobblecap-1.0.0+1.21.1.jar` — the version comes from
`mod_version` in `gradle.properties`. Ignore `cobblecap-1.0.0+1.21.1-sources.jar`.

Cobblemon is pulled from the Modrinth Maven as `compileOnly`, so a clean clone builds with
no manual downloads and nothing third-party is redistributed.

## Install

Drop the jar in the server's `mods/`, alongside Fabric API, Cobblemon, rctmod and — if you
want the dashboard — CheeseDash. Delete any older `cobblecap-*.jar` first; two versions of
the same mod in `mods/` is a startup failure, not a warning.

For the client half, drop the same jar in the client's `mods/`.

## What the first start creates

```text
config/
├── cobblecap/
│   ├── config.json        ← written with defaults
│   ├── champions.json     ← written as {"champions": []}
│   └── players.json       ← NOT created until the first player joins
├── cobblecap_tenures.json ← written empty
├── cobblecheesescore_external_conseil4_first.json
├── cobblecheesescore_external_conseil4_fastest.json
├── cobblecheesescore_external_conseil4_ranked_champions.json
└── cobblecheesescore_external_conseil4_ranked_failures.json
```

`cobblecap_legends.json`, `cobblecap_chronos.json` and `cobblecap_dioramas.json` are only
created when there is something to put in them. On the client,
`config/cobblecap_client.properties` appears the first time `/pasturehide` is used.

Every field of `config.json` and `champions.json` is documented in
[configuration](configuration.md).

## Proving it loaded

Server log, during startup:

```text
[CobbleCap] Config loaded (startingCap=20, enabled=true)
[CobbleCap] Created empty champions.json
[CobbleCap] No players.json found — starting with empty storage
[CobbleCap] No legends file — starting fresh hall of fame
[CobbleCap] Tenures loaded: 0 (0 current)
[CobbleCap] Initialized — startingCap=20, enabled=true, champions=0
```

That last line is the one to grep for. Then, once the world is up:

```text
[CobbleCap] Conseil 4 scoreboards ready: 4 boards (classic ×2 + ranked ×2)
[CobbleCap] StatueManager initialized (0 current-champion anchor(s), 0 doppel(s) reattached)
[CobbleCap] Cobblemon XP hook registered on field: EXPERIENCE_GAINED_EVENT_PRE
[CobbleCap] Cobblemon candy XP hook registered on field: EXPERIENCE_CANDY_USE_PRE
[CobbleCap] Battle pre-check hook registered on field: BATTLE_STARTED_PRE
[CobbleCap] Battle victory hook registered on field: BATTLE_VICTORY
```

Those four hook lines are the real health check, because they prove the reflective
subscription into Cobblemon's event bus worked. Without Cobblemon you get one line instead:

```text
[CobbleCap] Cobblemon not found — XP cap enforcement disabled
```

And if Cobblemon is present but renamed one of its event fields, a matching warning
(`Could not find XP event field in CobblemonEvents …`). That is the line to look for after
any Cobblemon update.

On the client:

```text
[CobbleCap] Client initializing...
[CobbleCap] Client initialized — HUD registered, /pasturehide enabled (hidden=false)
```

## Verifying in game

1. Join the server. The log should say
   `[CobbleCap] Player joined: <name> (cap=20)`.
2. Type `/cobblecap`. You should see `Your level cap: 20` and, with an empty champion list,
   `All champions defeated!`.
3. With the mod on your client, a small badge showing `20` should sit in the bottom-right
   corner. `/cobblecap hud position top-left` moves it.
4. Type `/cobblecap statue styles`. Thirteen keys should come back. That proves the
   statue subsystem initialised.
5. With CheeseDash running, `curl` the module list — CobbleCap should appear under the
   `cobblecap` prefix. See [the dashboard API](dashboard.md).

That is a *loaded* mod, not a *configured* one. Nothing gates anything until you author
`champions.json`; [getting started](getting-started.md) walks through that.

## Before the first champion morph

⚠️ Two things to do on a new server, in this order, before anyone can win:

1. **Change `datapackName`.** `src/main/java/com/cobblecap/logic/RctTrainerMorpher.java`
   line 57 reads `public static String datapackName = "CacademyMons";`. It is not a config
   key — edit the source and rebuild. Until you do, every morph aborts with
   `[CobbleCap] Datapack 'CacademyMons' not found in any of: [...]`. Details in
   [champion morphing](champion-morphing.md#what-you-must-change).
2. **Run `/cobblecap champion backup_now`.** It copies the pristine trainer JSON and skin
   into `config/cobblecap_regis_backup/`. Without that copy there is no way back from a
   morph, and `ensureBackup` refuses to back up an already-morphed file.

## Uninstalling

Stop the server, remove the jar, restart. Nothing in the world needs the mod to load, and
there are no registered items, blocks or entity types to leave behind.

What survives:

| Left behind | Remove it with |
|---|---|
| `config/cobblecap/` | delete the directory |
| `config/cobblecap_*.json` and `config/cobblecap_regis_backup/` | delete |
| `config/cobblecheesescore_external_conseil4_*.json` | delete |
| `config/cobblecap_client.properties` on each client | delete |
| 5 scoreboard objectives | `/scoreboard objectives remove conseil4_first_finish` and the same for `conseil4_fastest_time`, `conseil4_ranked_champions`, `conseil4_ranked_failures`, `cobblecap_mode` |
| 13 scoreboard teams `cgcap_<style>` | `/team remove cgcap_natural` and so on |
| statue entities | ⚠️ **run `/cobblecap statue wipe_all` before you remove the jar** |
| a morphed rctmod trainer JSON | ⚠️ **run `/cobblecap champion restore_original` before you remove the jar** |
| `cobblecap:` advancements granted to players | they become unknown ids and are harmless |

Those last two matter. Statue trackers are **invisible marker armour stands**: once the mod
is gone nothing renders them and nothing can find them, and they will sit in your world
indefinitely. And a morphed trainer JSON stays morphed — the league's final boss keeps the
last winner's team forever, with no CobbleCap left to restore it.

Capped Pokémon are not affected: their level and XP are real values, already clamped. They
simply resume levelling normally.

---

← [CobbleCap](../README.md) · [Getting started](getting-started.md) →
