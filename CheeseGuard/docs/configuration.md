[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

CheeseGuard has no single config file. It has **fourteen** places it reads and writes
under the server's `config/` directory, one per subsystem, and almost all of them are
state rather than settings: zones, events, prisoners and dimensions are created from the
dashboard or from commands, and the files are where the mod keeps them between restarts.

That shapes how you should treat them. Only four files are worth editing by hand
(`cheeseguard_webhook.json`, `cheeseguard_event_launch.json`, `cheeseguard_pastures.json`,
`cheeseguard_fish_pokemon.json`). The rest are owned by the running server, which
overwrites them on the next save; edit those only with the server stopped, and keep a copy.

## Every file at a glance

| Path | Written by | What it holds | Hand-editable |
|---|---|---|---|
| `config/cheeseguard/regions.json` | `RegionStorage` | every zone and every group, with flags, members, priorities, sale configs | stopped server only |
| `config/cheeseguard/chunks.json` | `ChunkSetStorage` | chunk sets and their reset schedules | stopped server only |
| `config/cheeseguard/chunk_suggestions.json` | `ChunkSuggestionStorage` | player suggestions from `/chunksuggest` | stopped server only |
| `config/cheeseguard/tag_configs.json` | `TagConfigStorage` | per-tag zone ownership caps used by `/cg buy` | stopped server only |
| `config/cheeseguard/prison.json` | `PrisonManager` | prison zone id, release point, welcome message, active prisoners, history | stopped server only |
| `config/cheeseguard_dimensions.json` | `DimensionManager` | dimension configs, including per-player ones | stopped server only |
| `config/cheeseguard_events.json` | `EventManager` | every event definition, with acts, effects and timelines | stopped server only |
| `config/cheeseguard_scores.json` | `ScoreManager` | event results, minigame bests, per-player stats | read-only, by design |
| `config/cheeseguard_event_launch.json` | `EventLaunchConfig` | the rate limits and time window for player-launched events | **yes** |
| `config/cheeseguard_pastures.json` | `PastureLimitsManager` | the two pasture-per-chunk caps | **yes** |
| `config/cheeseguard_webhook.json` | you | the Discord webhook URL | **yes** |
| `config/cheeseguard_fish_pokemon.json` | `PokemonFishingTable` | the weighted species table for the fishing minigame | **yes** |
| `config/cheeseguard_prophunt_icons.json` | `PropHuntIconStorage` | prop icon key → `custom_model_data` mapping | no |
| `config/cheeseguard_pending_reverts.json` | `CobbleShiftHook` | CobbleShift transforms still owed a revert after a crash | no |

And nine directories:

| Path | What it holds |
|---|---|
| `config/cheeseguard_templates/` | event templates, one JSON per template. **Not** `config/templates/` |
| `config/cheeseguard_states/<zoneId>/` | saved zone states, with an `index.json` per zone |
| `config/cheeseguard_snapshots/` | `/cg event snapshot` captures, one `.nbt` per snapshot id |
| `config/snapshots/<dimId>/` | dimension snapshots, used by the dimension reset. Note the name: no `cheeseguard_` prefix |
| `config/cheeseguard_racetracks/` | race track definitions, one JSON per event id |
| `config/cheeseguard_inv_archive/` | inventory archives, the source for `/cg restoreinv` |
| `config/cheeseguard_pending_inv/` | inventory snapshots of players currently in an event — the crash-recovery path |
| `config/cheeseguard_clearinv_snapshots/` | inventory snapshots taken by dimension entry clearing |
| `config/cheeseguard_assets/prophunt/` | uploaded prop icons as `.png`, keyed by name |

⚠️ One inconsistency to know about. The five files under `config/cheeseguard/` resolve
their path with `Paths.get("config", "cheeseguard")` — **relative to the process working
directory**, not to the Fabric config directory. Everything else resolves from
`server.getRunDirectory()` or `FabricLoader.getConfigDir()`. On a normal launch, where the
working directory is the server root, the two agree. Launch the server with the working
directory set somewhere else and CheeseGuard will write your zones into a `config/`
directory you did not expect, and start with none of them. If zones vanish after a hosting
change, this is why.

---

## `cheeseguard_webhook.json`

The only file you have to create yourself. The mod reads it once, at `SERVER_STARTED`.

| Key | Type | Default | What it does |
|---|---|---|---|
| `webhook_url` | string | `""` | Discord webhook the mod posts to. Empty or missing disables all Discord posting |

```json
{
  "webhook_url": "https://discord.com/api/webhooks/000000000000000000/EXAMPLE-WEBHOOK-do-not-use"
}
```

Read at startup only. Changing it needs a restart. A missing file is not an error and not
a warning: Discord posting is simply off, which makes a typo in the filename hard to
notice. Grep the log for `Failed to load webhook config` if you expected posts and got
none — and a *missing* file produces no line at all.

The URL is a credential. Anyone holding it can post to your channel as the server.

---

## `cheeseguard_event_launch.json`

The policy for `/cg event launch`, the command that lets players start events themselves.
Loaded when `EventManager.init` runs at server start, and rewritten whenever the dashboard
changes it. A missing file means every value below stays at its default, and the file is
not created until something saves it.

| Key | Type | Default | What it does |
|---|---|---|---|
| `playerLimitCount` | int | `0` | launches one player may make per period. `0` means unlimited |
| `playerLimitPeriod` | string | `"DAY"` | `HOUR`, `DAY` or `WEEK`. Anything else is coerced to `DAY` |
| `globalLimitCount` | int | `0` | launches the whole server may make per period. `0` means unlimited |
| `globalLimitPeriod` | string | `"DAY"` | same three values |
| `timeWindowEnabled` | bool | `false` | when true, launches are refused outside the window |
| `timeWindowStartHour` | int | `8` | first hour of the window, 0–23, clamped |
| `timeWindowEndHour` | int | `22` | first hour **outside** the window, 0–23, clamped |

The window is half-open: `8`–`22` allows a launch at 21:59 and refuses one at 22:00. A
start greater than the end wraps around midnight, so `22`–`6` is a night window. Setting
both to the same hour does **not** close the window: equal hours take the wrap-around
branch (`hour >= start || hour < end`), which is true at every hour, so every launch is
allowed. There is no way to close the window completely short of refusing player launches
on the events themselves (`playerLaunchable`).

Both limits are sliding windows over recorded launch timestamps, not calendar buckets:
with `playerLimitCount: 2` and period `DAY`, a player who launched twice at 20:00 can
launch again at 20:01 the next day, not at midnight. Timestamps are held in memory and
pruned at one week, so **a restart clears every recorded launch** and the limits start
over. That is a real gap if you rely on them to pace an event.

Operators at permission level 2 bypass the window and both limits. The per-event
`cooldownMinutes` is not in this file — it lives on the event, in
`cheeseguard_events.json`, and it applies to operators too.

```json
{
  "playerLimitCount": 2,
  "playerLimitPeriod": "DAY",
  "globalLimitCount": 10,
  "globalLimitPeriod": "DAY",
  "timeWindowEnabled": true,
  "timeWindowStartHour": 14,
  "timeWindowEndHour": 23
}
```

---

## `cheeseguard_pastures.json`

Caps on Cobblemon pasture blocks, enforced at placement time.

| Key | Type | Default | What it does |
|---|---|---|---|
| `maxPerChunk` | int | `1` | pastures allowed in one chunk, all owners together |
| `maxPerChunkPerPlayer` | int | `1` | pastures one player may own in one chunk |

Both are clamped at 0 and have no upper bound. **`0` disables that cap** — it means
"unlimited", not "forbidden" (`PastureLimitsManager` only checks a cap that is above 0).
With both at `0`, CheeseGuard does not limit pastures at all.

The two are checked independently, so the effective cap is the smaller of the two for a
single player. `maxPerChunk: 4` with `maxPerChunkPerPlayer: 1` means four players may each
place one.

Changes made with `/cg pasture chunk` or `/cg pasture player` rewrite the file at once and
take effect on the next placement. Hand edits need a restart, since the file is read only
at server start. A missing file is created with the defaults on that first load.

```json
{
  "maxPerChunk": 4,
  "maxPerChunkPerPlayer": 2
}
```

---

## `cheeseguard_fish_pokemon.json`

The loot table for the fishing minigame, in both its REALISTIC and ARCADE modes. Unlike
the other files, this one **is created with sensible defaults** the first time the mod
loads it and finds it absent, so the shipped table is in the file rather than only in the
code.

The shape is a list of tiers, each with a point value, a weight and a list of species:

```json
{
  "tiers": [
    {
      "name": "common",
      "points": 1,
      "weight": 100,
      "pokemon": [
        { "id": "magikarp", "name": "Magikarp" },
        { "id": "tentacool", "name": "Tentacool" }
      ]
    },
    {
      "name": "legendary",
      "points": 50,
      "weight": 1,
      "pokemon": [
        { "id": "lugia", "name": "Lugia" }
      ]
    }
  ]
}
```

| Key | Type | What it does |
|---|---|---|
| `tiers[].name` | string | the tier label shown to players |
| `tiers[].points` | int | points awarded for any species in this tier |
| `tiers[].weight` | int | relative weight **per species**, not per tier |
| `tiers[].pokemon[].id` | string | the Cobblemon species id, lowercase |
| `tiers[].pokemon[].name` | string | display name; falls back to `id` when absent |

The weight subtlety matters. `weight` is stored on every entry in the tier, and the total
is the sum over *entries*, so a tier with weight 100 and ten species is ten times more
likely to be rolled than a tier with weight 100 and one species. To make a tier rarer, cut
its weight or its species list.

The mod only writes this file when it is absent (the built-in defaults are saved on that
first load); it never rewrites a file you have edited.

The table is loaded by `PokemonFishingTable.reload`, which only `EventManager.init` calls,
at server start. Nothing in the dashboard reloads it, so a hand edit needs a restart. A
malformed file logs `Failed to load fish pokemon table` and falls back to the built-in
defaults, so a syntax error silently gives you the stock table.

---

## The state files

These are written by the server and documented where their model is explained. Editing
them by hand is a last resort.

### `config/cheeseguard/regions.json`

Two top-level objects, `regions` and `groups`, each keyed by id:

```json
{
  "regions": {
    "market_square": {
      "name": "Market Square",
      "world": "minecraft:overworld",
      "minX": 118, "minY": 64, "minZ": -302,
      "maxX": 142, "maxY": 90, "maxZ": -276,
      "group": "public_areas",
      "priority": 0,
      "owner": { "uuid": "…", "name": "RoiCheese", "role": "OWNER" },
      "members": [ { "uuid": "…", "name": "Steve", "role": "MEMBER" } ],
      "flags": { "block-break": false, "pvp": false }
    }
  },
  "groups": {
    "public_areas": { "name": "Public Areas", "flags": {}, "regions": ["market_square"] }
  }
}
```

The sample is trimmed: each zone also carries its entry/exit/protection messages,
particle colour and, when set, `parent`, tags, advancement and quest requirements, enter/exit
commands and a `saleConfig`. The `group` key is omitted when the zone has no group, and the
owner is stored apart from `members`.

A flag absent from the `flags` object is *inherited*, which is not the same as `false`.
The full semantics, priority resolution and the sale fields are in
[zones and flags](zones-and-flags.md) and [groups](groups.md).

Every mutation — one flag, one member — rewrites the whole file synchronously on the
server thread. With a few thousand zones that is a measurable hitch on a busy server.

### `config/cheeseguard_dimensions.json`

One entry per managed dimension, including the per-player copies whose ids have the form
`<masterDimId>_<uuid8>`. Generation type, border, spawn point (what `/cg setspawn`
writes), entry rules, keep-inventory and snapshot trigger all live here. See
[dimensions](dimensions.md).

### `config/cheeseguard_events.json`

Every event: state, trigger, team mode, phases, acts, effects, participants, launch
flags and cooldown. See [events](events.md) and [timeline](timeline.md).

### `config/cheeseguard/chunks.json`

Chunk sets. Each set names a dimension and a reset schedule:

| Key | Type | Default | What it does |
|---|---|---|---|
| `dimension` | string | `"minecraft:overworld"` | the dimension the set belongs to |
| `resetTrigger` | string | `"MANUAL"` | `MANUAL` or `SCHEDULED` |
| `resetHour` | int | `6` | hour of the scheduled reset, 0–23, server local time |
| `resetMinute` | int | `0` | minute, 0–59 |
| `resetDays` | string list | `[]` | day names, `MONDAY`…`SUNDAY`. Empty means every day |
| `lastAutoReset` | long | `0` | epoch milliseconds of the last automatic reset |

The scheduler checks every 600 ticks, matches the hour exactly and the minute within one,
and refuses to fire twice inside 90 seconds. `lastAutoReset` is how it remembers; clearing
it by hand can cause an immediate second reset. Resets delete region files after a backup
— see [chunks](chunks.md).

### `config/cheeseguard/prison.json`

Prison zone id, release point, welcome message, the active prisoners and up to 500 history
entries. The default welcome message is a **French** string hardcoded in `PrisonManager`,
with `§` colour codes and the placeholders `{reason}` and `{time}`:

```text
§c⛓ Vous êtes en prison.
§7Raison: §f{reason}
§7Temps restant: §f{time}
```

It is not translated, because it is config data rather than a language key. Override it
from the dashboard if your players are not French-speaking. Setting it to an empty string
does not clear it — the setter keeps the previous value.

### `config/cheeseguard/tag_configs.json`

Per-tag ownership caps consulted by `/cg buy`: how many zones carrying a given tag one
player may own. Written when a tag is created, edited or deleted from the dashboard; a
purchase only reads it.

### `config/cheeseguard_scores.json`

Event results, minigame bests and per-player aggregates. The comment in `ScoreManager`
says the file exists so that external scoreboard mods can read it, which is what
CobbleCheeseScore does. Treat it as output.

### `config/cheeseguard_prophunt_icons.json`

A `nextCmd` counter and a `mapping` of prop key to `custom_model_data` value, paired with
the `.png` files in `config/cheeseguard_assets/prophunt/`. The counter is monotonic and
never reused, so deleting a prop leaves a gap. Managed entirely from the dashboard.

### `config/cheeseguard_pending_reverts.json`

CobbleShift transforms applied by an event that have not been reverted yet, so a crash
mid-event does not leave a player permanently transformed. Written by `CobbleShiftHook`,
read at server start. Delete it only if you are certain no player is still transformed.

---

## What is not configurable

- **Permission levels.** They are compiled into the Brigadier tree. You cannot change the
  level on `/cg flag` from a config file; see
  [the command gate table](commands.md#permission-levels).
- **Flag defaults.** A flag that no zone, group or dimension sets is always *allowed* —
  there is no per-flag default value. Whether members are exempt from a flag is the
  `memberExempt` field on the `Flag` enum. Changing either means recompiling. (The closest
  thing to a configurable default is a dimension's `deniedFlags`, in
  `cheeseguard_dimensions.json`.)
- **Ports.** CheeseGuard serves no HTTP of its own. Its dashboard routes run inside
  CheeseDash, on CheeseDash's port — configured in `config/cheesedash.json`.
- **The hot potato toggle.** `HotPotatoHandler` holds it in a static field. It is off at
  every start.

---

← [Events, prison and operations](commands-operations.md) · [Zones and flags](zones-and-flags.md) →
