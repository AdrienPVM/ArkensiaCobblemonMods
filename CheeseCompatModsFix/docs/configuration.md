[← CheeseCompatModsFix](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

```text
config/cheesecompatmodsfix.json
```

Three booleans. That is the entire configuration surface of a mod with forty fixes — a
deliberate choice, and an honest limitation: **almost nothing here is switchable**. If a
fix on your server is unwanted, there is no flag for it; the only lever is removing the
jar.

The path is resolved as `Path.of("config", "cheesecompatmodsfix.json")`, relative to the
server's working directory, and so is the shrieker cooldown file
(`Path.of("config", "cheesecompatmodsfix_shrieker_cooldowns.json")`). The two other files
this mod writes — the Battle Tower backups and the daycare store — go through
`FabricLoader.getConfigDir()` instead. On a normal launch these are the same folder; on a
launcher that sets a different working directory they are not, and the files will be
split between two places.

---

## Keys

| Key | Type | Default | What it does |
|---|---|---|---|
| `arcPhoneHealEnabled` | boolean | `true` | when `false`, the Legendary Monuments Arc Phone cannot heal a party |
| `arcPhonePCEnabled` | boolean | `true` | when `false`, the Arc Phone cannot open the Pokémon PC |
| `sanctuaryOverflowFix` | boolean | `true` | when `false`, the Legendary Monuments sanctuary radius check runs unpatched, with its integer overflow |

### `arcPhoneHealEnabled`

Set to `false` on a server that wants Pokémon Centers to matter. The Arc Phone's healing
is free, instant and available anywhere, so leaving it on removes most of the reason to
walk back to a town.

Enforced by `ArcPhoneHealMixin`, which cancels the server-side packet handler and sends
the player `cheesecompat.arcphone.heal_disabled`:

```text
Arc Phone healing is disabled on this server.
```

```text
Le soin via l'Arc Phone est désactivé sur ce serveur.
```

Only the server-side handler is blocked. The Arc Phone's heal button still exists on the
client and can still be pressed; the player gets the message instead of a heal.

### `arcPhonePCEnabled`

Same shape, for PC access. Enforced by `ArcPhonePCMixin`, message
`cheesecompat.arcphone.pc_disabled`:

```text
Arc Phone PC access is disabled on this server.
```

```text
L'accès au PC via l'Arc Phone est désactivé sur ce serveur.
```

### `sanctuaryOverflowFix`

Switches the integer overflow fix in `SanctuaryBlock.isWithinRadius()` on or off.
`SanctuaryDistanceMixin` reads it at the head of every call: at `true` it computes the
distance in `long` and returns the result itself; at `false` it returns without
cancelling, and Legendary Monuments' original `int` arithmetic runs — the version that
overflows for a far-away player and makes the sanctuary appear to cover the world. Its
value is also printed in the startup log line (`Sanctuary fix=ON` / `OFF`).

There is no good reason to turn it off. Leave it at `true`.

---

## Reload behaviour

**None.** `CheeseCompatConfig.load()` runs once, as the first statement of
`onInitialize()`, and the result is held in a static field for the life of the process.
There is no reload command and no file watcher. Editing the file on a running server has
no effect, and the next restart loads your edit normally.

## What happens on first start

The file does not exist, so `load()` constructs a default instance and calls `save()` on
it. You get:

```json
{
  "arcPhoneHealEnabled": true,
  "arcPhonePCEnabled": true,
  "sanctuaryOverflowFix": true
}
```

written with Gson pretty-printing.

## What happens when a version adds a key

`load()` re-saves the config **immediately after a successful parse**. Gson leaves a
field absent from the JSON at its Java default, and the re-save then writes every field
back out. So a key added in a new version appears in your existing file on the first
start with that version, already set to its default. You do not have to delete the file
to pick up new keys.

The same mechanism rewrites the file every single start, which means **comments and
formatting you add by hand are lost**. Gson parses this file leniently, so a `//`
comment does not break the load — but the re-save that follows erases it, so there is
nowhere to keep a note.

## What happens when the file is malformed

A parse failure is caught, logged, and the mod falls back to defaults:

```text
[CheeseCompat] Failed to load config
```

followed by the exception. The broken file is then **overwritten** with the defaults,
because the fallback path calls `save()`. There is no backup. If you hand-edit this file
and get the JSON wrong, your previous values are gone on the next start — which, with
three booleans, is survivable, but worth knowing.

A file containing literal `null` parses to a null object and takes the same path.

## Annotated example

A server that wants Pokémon Centers to be the only way to heal, and the PC to be the
only way to reach boxes:

```json
{
  "arcPhoneHealEnabled": false,
  "arcPhonePCEnabled": false,
  "sanctuaryOverflowFix": true
}
```

Reading that back, line by line:

- `arcPhoneHealEnabled: false` — the Arc Phone heal packet is refused server-side.
  Players get a red message. Pokémon Centers and healers are unaffected.
- `arcPhonePCEnabled: false` — the Arc Phone PC packet is refused the same way. Physical
  PC blocks are unaffected.
- `sanctuaryOverflowFix: true` — the sanctuary radius check keeps its overflow fix.
  Left at the default, which is the only sensible value.

Both Arc Phone keys are no-ops on a server without Legendary Monuments: the mixins are
never applied, so the packets they guard do not exist.

---

## Everything else that behaves like configuration

Several fixes have numbers that an admin would reasonably expect to be configurable and
that are **hard-coded**. They are listed here so you know where to look, and in which
file to edit them if you are willing to rebuild.

| Constant | Value | Where | What it controls |
|---|---|---|---|
| NPC arena offset | `18` | `BattleArenaPositionMixin` | distance from anchor to the NPC platform — [arena geometry](fixes-battle-tower.md#8-arena-teleport-offsets) |
| `WAIT_INTERVAL_TICKS` / `MAX_ATTEMPTS` | `20` / `60` | `BattleTowerDisconnectFix` | party-restore retry cadence and give-up point |
| `BACKUP_MAX_AGE_MS` | 30 days | `BattleTowerDisconnectFix` | when an unused party backup is swept |
| battle cooldown ladder | 1500–3000 ms | `BattleTowerManagerMixin` | per-floor delay between tower battles |
| `GIVE_UP_THRESHOLD` | `1200` ticks | `RaidDenFix` | when the mod stops trying to close a crystal |
| `COOLDOWN_MILLIS` / `COOLDOWN_RADIUS` | 10 min / 200 blocks | `ShriekerBossState` | shrieker boss spawn cooldown |
| `RADIUS` | `50` | `BossDarknessAura` | range of the boss darkness aura |
| Repel range | `80` blocks | `SafariSpawnInfluenceFix` | safari Repel effect radius |
| shop cull distance | `10` blocks | `client.NumismaticShopCullMixin` | when a Numismatic shop stops rendering its item |
| `FAKEMON_NAMESPACES` | `seavlbizarremon` | `client.JourneyMapSeavlianIconMixin` | which namespaces get their minimap icon path rewritten |
| `REMAP` | 70 entries | `CacademyMegaStoneRemapPatch` | custom mega stone → Showdown stone id table |

## Other files this mod writes

None of these is a configuration file — do not hand-edit them — but they live in
`config/` and will appear next to the one above.

| Path | Owner | Contents |
|---|---|---|
| `config/cheesecompat_bt_backups/<uuid>.dat` | `BattleTowerDisconnectFix` | compressed NBT snapshot of a player's Battle Tower party |
| `config/cheesecompat_daycare.json` | `DaycareSimpleModeStore` | one-shot breeding state per pasture |
| `config/cheesecompatmodsfix_shrieker_cooldowns.json` | `ShriekerBossState` | sculk shrieker boss cooldowns by position |

The `.dat` files are the only data here you cannot regenerate: each one is a player's
party. Deleting one while a restore is still pending for that player loses whatever the
tower had not yet restored.

A fourth file is **read** and never written, and only on a client:
`config/resourcepackoverrides.json`, from which `CheeseCompatClient` takes the ordered
resource-pack list. It is synced from the server by AutoModpack, so the server stays the
single source of truth; if it is absent, a hard-coded fallback list is used. See
[the misc catalogue](fixes-misc.md#the-client-resource-pack-activator).

---

← [Commands](commands.md) · [Fix catalogue — Battle Tower](fixes-battle-tower.md) →
