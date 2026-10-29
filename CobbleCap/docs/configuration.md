[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

CobbleCap keeps eight files. Two are configuration you edit, one is a champion list you
author, and five are state the mod owns and you should only touch with the server stopped.

| Path | Kind | Written by |
|---|---|---|
| `config/cobblecap/config.json` | **configuration** | `CapConfig` — on first start, and on every setter |
| `config/cobblecap/champions.json` | **authored data** | `ChampionConfig` — created empty, rewritten by the dashboard `PUT` |
| `config/cobblecap/players.json` | state | `CapStorage` — debounced, and on shutdown |
| `config/cobblecap_legends.json` | state | `LegendsStorage` — on every change |
| `config/cobblecap_tenures.json` | state | `TenureStorage` — on every change |
| `config/cobblecap_chronos.json` | state | `ChronoManager` — on every change |
| `config/cobblecap_dioramas.json` | state | `StatueManager` — on anchor change |
| `config/cobblecap_client.properties` | **client** configuration | `PastureHideClient` |

Note the inconsistency in layout: `config.json`, `champions.json` and `players.json` live in
a `cobblecap/` subdirectory; everything else sits directly in `config/` with a
`cobblecap_` prefix. That is historical and the mod does not care, but it means a backup
script has to cover both shapes.

A ninth and tenth set of files are **outputs**, not configuration:
`config/cobblecheesescore_external_conseil4_*.json` and
`config/cobblecap_regis_backup/`. See
[the Conseil 4 leaderboards](champions.md#json-export-for-cobblecheesescore) and
[champion morphing](champion-morphing.md#the-backup).

---

## `config/cobblecap/config.json`

Written with defaults on first start if it does not exist. Every key is read only when
present, so deleting a key restores its default rather than breaking the parse.

| Key | Type | Default | What it does |
|---|---|---|---|
| `startingCap` | int | `20` | the cap given to a player the first time they join |
| `enabled` | bool | `true` | master switch for XP enforcement, champions, banned species and the party check |
| `discordWebhookUrl` | string | `""` | Discord webhook for champion and League Master events; empty disables |
| `bannedSpecies` | array of strings | `[]` | species that cancel a battle if present in the party |
| `fireworksEnabled` | bool | `false` | fire a rocket show when a ranked champion is crowned |
| `fireworksZoneId` | string | `""` | the CheeseGuard zone the show happens in |
| `fireworksCount` | int | `12` | rockets to launch |
| `fireworksIntervalMs` | int | `800` | delay between rockets |
| `fireworksDurationSec` | int | `12` | hard cap on the show's length |

### `startingCap`

Read at every join by `CapStorage.ensureExists`, so changing it affects **new** players
only. Existing rows keep their stored `capLevel` — use `/cobblecap reset <player>` to pull
one player onto the new value.

It is **not settable over the API**: `GET /config` returns it, `PUT /config` ignores it.
`/cobblecap reload` does re-read it into memory, so a restart is not required, but nothing
retroactively adjusts anyone.

### `enabled`

This is more than an XP switch. With `false`:

- all three XP enforcement layers return early
- the `BATTLE_STARTED_PRE` handler returns at its first line, so **banned species and the
  party cap check stop too**
- `UseEntityCallback` stops intercepting rctmod NPCs, so champions behave as ordinary
  trainers with no gates and no refusals
- `BATTLE_VICTORY` handling stops, so caps stop rising and nobody is promoted

Still working: XP locks, statues, chronos, the Conseil 4 boards and the whole dashboard.

`/cobblecap enable` and `/cobblecap disable` flip it and save immediately; so does
`PUT /api/v1/cobblecap/config`. Every online client is re-synced so the HUD can hide itself.

### `discordWebhookUrl`

Pushed into `CobbleCapWebhook` at startup and again on `/cobblecap reload` and on a
dashboard `PUT`. An empty string disables posting. The mod posts on a champion victory and
on a League Master promotion; there is no event filter and no rate limit beyond those
triggers.

### `bannedSpecies`

Lower-cased on both read and write, and compared against Cobblemon's **showdown id** — the
lower-cased species name, so `mewtwo`, `tapukoko`, `mrmime`. A party containing any of them
cancels the battle before it starts with:

```text
Battle cancelled — banned Pokémon in your party: Mewtwo
```

The message lists display names; the config holds showdown ids. The check runs on every
battle, not only champion battles, and it is **not** skipped while
`CobbleCapMod.LEVEL_FORCE_ACTIVE` is set — unlike the party cap check, which is.

`findBannedSpeciesInParty` fails open: if the party cannot be read reflectively it returns
an empty list and the battle proceeds. So a Cobblemon update that breaks party reflection
silently disables the ban list.

### The fireworks keys

All five feed `FireworksManager`, which launches rockets inside a CheeseGuard zone when a
**ranked** champion is crowned. CheeseGuard is reached by reflection, so with CheeseGuard
absent the show is skipped with a debug line and nothing else happens.

The setters clamp, and they clamp silently — a dashboard `PUT` reports success with the
value you sent while storing the clamped one:

| Key | Clamp |
|---|---|
| `fireworksCount` | `max(1, v)` |
| `fireworksIntervalMs` | `max(50, v)` |
| `fireworksDurationSec` | `max(1, v)` |

Nothing clamps the **upper** bound. `fireworksCount: 10000` with
`fireworksIntervalMs: 50` will try to fire ten thousand rockets, bounded only by
`fireworksDurationSec` — the show checks the deadline before each rocket and stops:

```text
[CobbleCap] Fireworks show hit duration cap at rocket 240/10000
```

So `fireworksDurationSec` is the real safety valve, and the effective rocket count is
`min(fireworksCount, durationSec × 1000 / intervalMs)`. Keep the duration low.

The show runs on **its own thread**, sleeping between rockets and hopping back onto the
server thread with `server.execute` for each spawn. A re-entry guard coalesces a second
request while one is running:

```text
[CobbleCap] Fireworks show already running — new request coalesced
```

An unresolvable zone id aborts with a warning:

```text
[CobbleCap] Fireworks zone 'league_arena' not found via CheeseGuard — show aborted
```

Aim it with `POST /api/v1/cobblecap/fireworks/test`, which ignores `fireworksEnabled` but
still requires a zone id.

### Hot reload

`/cobblecap reload` re-reads this file and `champions.json`. A dashboard `PUT` writes it. A
hand edit while the server is running will be **overwritten** by the next setter call, since
`CapConfig` holds the authoritative copy in memory and `save()` writes the whole object.
Edit, then `/cobblecap reload`, in that order.

### Complete annotated example

```json
{
  "startingCap": 20,

  "enabled": true,

  "discordWebhookUrl": "https://discord.com/api/webhooks/0000/xxxx",

  "bannedSpecies": [
    "mewtwo",
    "arceus",
    "rayquaza"
  ],

  "fireworksEnabled": true,
  "fireworksZoneId": "league_arena",
  "fireworksCount": 12,
  "fireworksIntervalMs": 800,
  "fireworksDurationSec": 12
}
```

JSON has no comments. The annotations for each key are in the table above; copy the block
as it stands.

---

## `config/cobblecap/champions.json`

**Created empty on first start.** This is the file that makes the mod do anything, and the
mod ships no content for it:

```json
{
  "champions": []
}
```

With an empty list no champion is ever detected, no cap ever rises on its own, and
`/cobblecap` tells every player `All champions defeated!` — because `nextUnbeaten` over an
empty list returns nothing.

The array is **ordered**, and the order is the progression. Every field and its default is
documented in [the champion system](champions.md#the-model); `id` must match the rctmod
trainer id exactly.

### Complete annotated example

Three entries: a first gym with no gates, a later gym gated on the Pokédex and a scoreboard,
and the final champion.

```json
{
  "champions": [
    {
      "id": "gym_leader_pierre_0395",
      "displayName": "Pierre",
      "capGranted": 30,
      "requiredPokedex": 0,
      "requiredQuestId": "",
      "victoryQuestId": "1A2B3C4D5E6F7081",
      "challengeId": "gym_leader_pierre_challenge",
      "challengeQuestId": "",
      "challengeCooldownHours": 24,
      "allowRefight": false
    },
    {
      "id": "gym_leader_erika_0399",
      "displayName": "Erika",
      "capGranted": 50,
      "requiredPokedex": 80,
      "requiredScoreboard": "alfred_main_progress",
      "requiredScoreMin": 12,
      "challengeId": "gym_leader_erika_challenge",
      "challengeRequiredScoreMin": 20,
      "challengeCooldownHours": 24,
      "challengeCapGranted": 0,
      "allowRefight": false
    },
    {
      "id": "champion_regis_challenge",
      "displayName": "Régis",
      "capGranted": 100,
      "challengeId": "",
      "allowRefight": true
    }
  ]
}
```

Things worth knowing about that example:

- `displayName` is what players see **and** what the advancement key is derived from:
  lower-cased, spaces to underscores. `"Major Bob"` becomes `cobblecap:champion/major_bob`,
  which must exist as a datapack advancement or the grant does nothing.
- `capGranted: 100` with an empty `challengeId` marks an entry **post-game**: it is skipped
  by `hasAllNormalChampionsBeaten`, which is what lets the final champion sit in the same
  list without blocking challenge unlocks.
- `requiredScoreMin` and `challengeRequiredScoreMin` are only read when `requiredScoreboard`
  is non-empty. Both gate against the **same** objective.
- `allowRefight: false` makes the fight one-time; `true` lets a beaten champion be fought
  again, though `CapListener` still refuses the interaction once the champion is beaten and
  no challenge is unlocked, so the practical effect of `true` is narrow.
- the id `champion_regis_challenge` is hard-coded in four places as "the final champion".
  See [what you must change](champions.md#what-you-must-change-for-your-own-server).

### How it is written

`/cobblecap reload` re-reads it. `PUT /api/v1/cobblecap/champions` **replaces the whole
list** and rewrites the file — there is no merge and no undo, so always `PUT` the result of
a `GET` with your edits applied.

A parse failure leaves the in-memory list untouched and logs:

```text
[CobbleCap] Failed to load champions.json: <message>
```

The chat reply to `/cobblecap reload` reports a count. A count of `0` right after editing a
populated file means the parse failed — read the log.

Note that `ChampionEntry.toJson` omits conditional fields, so a `GET` followed by a `PUT`
is lossless but not byte-identical to a hand-written file: `requiredScoreboard` and friends
disappear when empty, `challengeRequiresAllBeaten` only appears when `false`, and
`challengeCapGranted` only when above zero.

---

## State files

Do not hand-edit these while the server is running. Each manager holds the authoritative
copy in memory and will overwrite you.

### `config/cobblecap/players.json`

A flat object keyed by UUID:

```json
{
  "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1": {
    "uuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1",
    "playerName": "Steve",
    "capLevel": 45,
    "championsBeaten": ["gym_leader_pierre_0395"],
    "challengeBeaten": [],
    "hudVisible": true,
    "hudPosition": 0,
    "expLocked": false,
    "lastCapChange": 1778000000000,
    "lastChallengeTimes": {}
  }
}
```

Writes are **debounced**: `saveAsync()` sets a dirty flag and schedules a full save 500 ms
later on a daemon thread named `CobbleCap-Save`, collapsing a burst of changes into one
write. The write itself is atomic through `players.json.tmp` and a `Files.move`, preferring
`ATOMIC_MOVE` and falling back to a plain replace. A synchronous `save()` runs on
`SERVER_STOPPING`.

⚠️ The 500 ms debounce means a hard crash can lose up to half a second of changes — one
cap raise, realistically. A clean shutdown loses nothing. An entry that fails to parse is
skipped with an error and the rest of the file still loads.

### `config/cobblecap_legends.json`

The Hall of Fame. Shape and semantics in
[the champion system](champions.md#the-hall-of-fame). Append-only in normal use; the only
things that remove rows are `/cobblecap statue test_clear` and `/cobblecap chrono wipe`.

### `config/cobblecap_tenures.json`

Ranked champion reigns. At most one entry has no `endedAt`, and that is the current
champion.

```json
{
  "tenures": [
    {
      "id": "7c1e...",
      "championUuid": "f84c6a79-...",
      "championName": "Steve",
      "startedAt": 1778000000000,
      "bestTimeMs": 1122000,
      "failedAttempts": [
        {"uuid": "9b2d...", "playerName": "Alex", "count": 3, "lastFailAt": 1778100000000}
      ]
    }
  ]
}
```

Unlike the other state files this one is **not** written atomically — `TenureStorage.save`
writes straight into the target path. A crash mid-write can truncate it. `saveAsync` also
spawns a bare `new Thread` per call rather than using a pool.

### `config/cobblecap_chronos.json`

Running timers, UUID to start timestamp:

```json
{
  "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1": 1778000000000
}
```

Persisted so a run survives a crash, and it keeps ticking against the original start — the
clock is **not** paused while the server is down. If you restart during a race, cancel the
affected chronos.

### `config/cobblecap_dioramas.json`

Dynamic champion diorama anchors. Shape in
[statues and dioramas](statues.md#the-dynamic-champion-diorama). Cleared by
`/cobblecap statue wipe_all`.

---

## `config/cobblecap_client.properties`

Client-side, one key, written by the `/pasturehide` command:

```properties
#CobbleCap client preferences
pasture_pokemon_hidden=false
```

Any value that is not `true` reads as `false` (`Boolean.parseBoolean`). A missing file is
not an error — the default is `false`. Details in
[the client side](client.md#pasturehide).

---

## What is not configurable

Worth stating plainly, because several of these look like they should be:

| Thing | Where it is fixed |
|---|---|
| the datapack name `CacademyMons` | `RctTrainerMorpher.java` line 57 — **the one edit you almost certainly need** |
| the morphed trainer's paths and id | `RctTrainerMorpher.java` lines 52, 55, 56 |
| the final champion id `champion_regis_challenge` | hard-coded in four methods |
| the Conseil 4 objective names | `Conseil4Boards` constants |
| the CobbleCheeseScore export filenames | `Conseil4Boards.export(...)` |
| the `cobblecap_mode` objective name | `Conseil4Boards`, `ChampionManager`, `CobbleCapCommand` |
| the 30-second XP message cooldown | `CapManager.XP_MSG_COOLDOWN_MS` |
| the 2-second pre-battle message cooldown | `ChampionManager.shouldSendMessage` |
| the HUD badge size, margin and texture | `CapHudRenderer` |
| the diorama layout (3.5 blocks back, 2 apart) | `StatueManager.placeFullDiorama` |

The three `RctTrainerMorpher` path fields are `public static` and non-final, and a comment
above them claims they are "Configurable from CapConfig". **That comment is wrong** — no
config key reads or writes them. Changing them means editing the source and rebuilding.

---

← [The client side](client.md) · [Architecture](architecture.md) →
