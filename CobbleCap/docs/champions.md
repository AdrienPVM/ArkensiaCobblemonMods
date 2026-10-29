[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](champions.fr.md)

# The champion system

Champions are the only thing that moves a player's cap on its own. A champion is an rctmod
trainer NPC that CobbleCap has an entry for, and beating it both raises the cap and unlocks
the next entry in the list.

⚠️ This subsystem is where CobbleCap stops being generic. It expects **Radical Cobblemon
Trainers** (`rctmod`) NPCs, optionally **FTB Quests**, and — in four places — a champion
whose trainer id is literally `champion_regis_challenge`. Those four hard-codings are listed
at the bottom of this page under [what you must change](#what-you-must-change-for-your-own-server).

## The model

```text
champions.json  ──ordered list──▶  ChampionEntry
                                        │  id = rctmod trainer id
                                        │  capGranted
                                        └─ gates: requiredPokedex, requiredQuestId,
                                                  requiredScoreboard + requiredScoreMin
players.json    ──per player────▶  PlayerCapData
                                        │  championsBeaten   (normal victories)
                                        │  challengeBeaten   (challenge victories)
                                        └─ lastChallengeTimes (cooldowns)
```

`ChampionConfig.nextUnbeaten(championsBeaten)` returns the first entry not in the beaten
list. That single call defines "the player's current champion", and it is used by the HUD,
by `/cobblecap`, and by the ordering gate.

Every `ChampionEntry` field, with its default when absent from the JSON:

| Field | Type | Default | Effect |
|---|---|---|---|
| `id` | string | `""` | the rctmod trainer id this entry matches |
| `displayName` | string | `""` | shown to players; **also derives the advancement key** |
| `capGranted` | int | `20` | the cap set on first victory, via raise-only |
| `requiredPokedex` | int | `0` | Pokémon caught required before the fight; `0` disables |
| `requiredQuestId` | string (hex) | `""` | FTB quest that must be complete before the fight |
| `victoryQuestId` | string (hex) | `""` | FTB quest associated with the victory |
| `challengeId` | string | `""` | the challenge-variant trainer id |
| `challengeQuestId` | string (hex) | `""` | FTB quest for the challenge victory |
| `challengeCooldownHours` | int | `0` | hours between challenge attempts; `0` disables |
| `requiredScoreboard` | string | `""` | vanilla objective gating the fight |
| `requiredScoreMin` | int | `0` | minimum score on that objective |
| `challengeRequiredScoreMin` | int | `0` | minimum score for the challenge fight; `0` disables |
| `allowRefight` | bool | `true` | meant to allow re-fighting a beaten champion; in practice **no effect** (see the gate below) |
| `challengeRequiresAllBeaten` | bool | `true` | challenge needs every normal champion beaten |
| `challengeCapGranted` | int | `0` | cap granted on challenge victory; `0` means none |

`hasAllNormalChampionsBeaten` decides what "every normal champion" means, and the rule is
peculiar enough to spell out: an entry with `capGranted >= 100` **and** no `challengeId` is
treated as post-game and skipped. That is how Conseil 4 members and the final champion sit
in the same list without blocking the challenge unlock.

## Victory detection

`CobblemonHook` subscribes to `BATTLE_VICTORY` (or `BATTLE_WON`) and
`ChampionManager.handleBattleVictoryEvent` does the identification, all reflectively:

1. Read `getWinners()`, walk the actors, take the first that yields a player UUID.
   `getPlayerUUIDs()`/`getUUIDs()` first, then `getUuid()`/`uuid`/`getPlayerId`.
2. **No player winner at all** means an NPC won — that routes to the ranked-loss path
   below, and nothing else happens.
3. Read `getLosers()` and take the first actor with an `Entity`. If that fails, fall back to
   `getBattle().getActors()` and pick the first actor that is not the winner.
4. Extract the trainer id from the loser entity and strip any per-dimension suffix.
5. Match against `findByChallengeId` first, then `findById`. No match means this was not a
   champion battle and the handler returns.

Trainer ids are read out of entity NBT by `extractRctmodTrainerId`, trying the keys
`TrainerData`, `trainer`, `trainerId`, `configId`, `id`, `Trainer` — as a compound with
sub-keys `id`/`configId`/`trainerId`/`trainer`, or as a plain string. If every key misses,
it falls back to scanning every zero-argument `String`-returning method on the entity and
returning the first result that contains an underscore and is longer than three characters.
That heuristic is loose on purpose and it can pick up something unrelated: a mysterious
champion detection on a non-champion trainer has this as its cause.

### The per-dimension suffix

`stripPerDimSuffix` removes a trailing `_<8 lowercase hex>` — but **only** when the
remaining prefix is in `Conseil4TrainerCloner.CONSEIL4_BASE_IDS`. It exists because
CheeseGuard's per-player Conseil 4 dimensions used to clone each trainer into a
dim-specific variant. That cloner is **disabled** (see
[architecture](architecture.md#disabled-and-dormant-code)); the stripping stays so that
leftover cloned ids from the failed deploy still resolve.

## The pre-battle gate

Two entry points, and they overlap:

- **`UseEntityCallback`** in `CapListener`, on right-clicking any entity in the `rctmod`
  namespace. This is what produces the player-facing refusal.
- **`BATTLE_STARTED_PRE`** in `CobblemonHook`, as a safety net for battles that start some
  other way.

`ChampionManager.handlePreBattle` is the condition check:

1. Already in `championsBeaten`? `allowRefight` false gives `FAIL` with
   `cobblecap.msg.already_beaten`; true gives `PASS`. This branch is unreachable: both
   entry points above only call `handlePreBattle` for a champion that is **not** beaten yet, so
   `allowRefight` never decides anything.
2. `requiredPokedex` not met → one unmet line, `Pokédex: %d/%d caught`.
3. `requiredQuestId` not complete → `Complete a required quest first`.
4. `requiredScoreboard` score below `requiredScoreMin` → `You must talk to Alfred first`.
   That message text is written for Arkensia's quest NPC; change it in the lang files if
   your scoreboard gate means something else.

Any unmet condition gives a header line and the bullet list:

```text
Cannot challenge Erika — conditions not met:
  - Pokédex: 61/80 caught
```

Messages are rate-limited to one per player per **2 seconds** (`shouldSendMessage`),
because the event fires twice per interaction.

`handlePreBattle` **fails open** when the player has no stored row, and
`isFtbQuestCompleted` fails open on every error including FTB Quests being absent — a
missing quest mod never blocks a fight. `getPokedexCount`, by contrast, fails **closed**,
returning `0`: if Cobblemon's Pokédex reflection breaks, every Pokédex gate becomes
impassable. That asymmetry is deliberate and documented in the source.

`BATTLE_STARTED_PRE` additionally enforces ordering: if the champion in the battle is not
`nextUnbeaten`, the battle is cancelled with `cobblecap.msg.champion_not_next`. When it
cancels and the player's cap is already at or above that champion's `capGranted`, it logs a
warning naming the repair command, because that combination almost always means
`championsBeaten` and `capLevel` have drifted apart.

## Challenge mode

A champion with a non-empty `challengeId` has a harder variant. Interacting with an
already-beaten champion whose challenge is unlocked lets the interaction `PASS` through to
rctmod; `BATTLE_STARTED_PRE` then performs the **swap**:

1. `handleChallengePreBattle` checks the gates — all normal champions beaten (unless
   `challengeRequiresAllBeaten` is false), the challenge scoreboard minimum, and the
   cooldown. The cooldown refusal reports remaining minutes, rounded up.
2. The normal battle is cancelled.
3. `swapToChallengeBattle` reflects on `com.gitlab.srcmc.rctmod.world.entities.TrainerMob`,
   reads `getTrainerId()`, calls `setTrainerId(challengeId)`, then
   `startBattleWith(player)`.
4. The player's UUID goes into `challengeSwapInProgress` so the re-fired
   `BATTLE_STARTED_PRE` does not swap again.
5. One `server.execute` hop later the UUID is removed from the guard set, and the original
   id is restored on the hop after that.

⚠️ That restore is the fragile part. If the server stalls or the entity unloads between
steps 3 and 5, the TrainerMob keeps the challenge id permanently. `CapListener` has a
repair for exactly that: when a player interacts with an entity carrying a challenge id for
a champion they have **not** beaten normally, it restores the normal id — preserving any
per-dimension suffix — and treats the interaction as a normal fight, logging:

```text
[CobbleCap] Entity has stuck challenge ID 'gym_leader_pierre_challenge' but player hasn't beaten 'gym_leader_pierre_0395' — restoring to 'gym_leader_pierre_0395'
```

A challenge victory records `challengeBeaten`, stamps the cooldown, raises the cap only if
`challengeCapGranted > 0`, and grants `cobblecap:challenge/<key>` — plus
`cobblecap:milestone/challengers_complete` once every entry that has a `challengeId` is in
`challengeBeaten`. Challenge battles skip
the party cap check entirely — they are post-game content and the cap is not meant to apply.
A challenge is **one-time**: once in `challengeBeaten`, interacting again is refused with
the post-victory line.

## What a normal victory does

`handleVictory`, in order:

1. `addChampionBeaten(id)` — idempotent.
2. `raiseCapLevel(capGranted)` — raise-only.
3. If raised: a server-wide broadcast (`%s defeated champion %s! Their cap is now %d.`) and
   a personal `You defeated %s! Your level cap is now %d.`
4. If not raised but it was the first time: the personal line is `You have already defeated
   %s.` instead, which reads oddly for a genuine first victory and is a known wart.
5. First time only: grant `cobblecap:champion/<key>`, fire the Discord webhook, and if every
   normal champion is now beaten, grant `cobblecap:milestone/badges_complete`.
6. First time only, and only when `id` equals `champion_regis_challenge`: run the League
   Master promotion.
7. Sync the client HUD.

The advancement is granted by dispatching `advancement grant <player> only
cobblecap:<category>/<key>`, where `<key>` is the **display name** lower-cased with spaces
replaced by underscores. The mod ships advancement JSONs under
`data/cobblecap/advancement/{champion,challenge,milestone}/` for Arkensia's roster; rename a
champion and the grant silently targets an advancement that does not exist.

FTB Quests completion is **not** forced any more. `ChampionManager.triggerFtbQuest` and
`triggerFtbQuestById` still exist but nothing calls them from the victory path: the source
comment explains that `Quest.forceProgress → Reward.forceProgress → claimReward` duplicated
a badge reward on rare occasions, so the mod now relies on FTB's own
`AdvancementTask.autoSubmitOnPlayerTick` polling the granted advancement. Those two methods
are effectively dead code kept for reference.

## Classic and ranked

Beating the final champion promotes the player to **Maître de la Ligue** (League Master),
and there are two separate flows depending on the player's `cobblecap_mode` scoreboard
score: `2` is **RANKED**, anything else — including no score and no objective — is
**CLASSIC**. `/cobblecap mode` writes that score.

### Classic

Writes to `LegendsStorage` (`config/cobblecap_legends.json`), the permanent Hall of Fame.

- No legend yet: the player's party is snapshotted, a `LegendEntry` is appended with
  `firstEver` true when nobody had ever been crowned, the broadcast fires, the Discord
  webhook fires, and the Conseil 4 boards rebuild.
- Legend exists and a chrono was captured that beats the stored `bestTimeMs` (or no time was
  stored): the entry is replaced with the new time and everyone sees
  `🏆 Steve bat son record : 18m42s !`
- Legend exists and the run was slower: the record stands and everyone sees
  `⏱ Steve a fini en 24m05s (record perso : 18m42s)`

Those last two strings are `Text.literal` in French in the source, so an English client sees
French. The four `league_master*` broadcasts, by contrast, go through the lang files and do
translate.

### Ranked

Writes to `TenureStorage` (`config/cobblecap_tenures.json`) and **never touches the Hall of
Fame**. A ranked win **always** opens a new tenure, even for a player re-claiming their own
title, so the failed-attempts tally starts clean each reign.

`startNewTenure` stamps `endedAt` on the previous current tenure and appends a new one. At
most one tenure has `endedAt == 0` at any time. Then, in order: a broadcast
(`👑 … entre dans l'histoire comme PREMIER Maître de la Ligue Compétitif` for the first ever,
`👑 … détrône <previous> et devient le nouveau Maître de la Ligue` afterwards — French
literals again), a Discord webhook built from a **synthetic** `LegendEntry` that is never
stored, a board rebuild, a queued [RCT trainer morph](champion-morphing.md), and the
fireworks show.

### Ranked losses

When `BATTLE_VICTORY` reports no player winner, `handleRegisRankedLoss` checks whether the
NPC winner's trainer id is `champion_regis_challenge` and whether the losing player is in
ranked mode. Both true, and `Tenure.recordFailure` increments that challenger's count
against the **current** tenure and rebuilds the boards. Classic losses are not counted, and
with no current tenure the call is a silent no-op.

## The chronos

`ChronoManager` is a map of player UUID to start timestamp, persisted to
`config/cobblecap_chronos.json` on every mutation. A run therefore survives a crash or a
restart and keeps ticking against its original start — the mod does not pause the clock
while the server is down.

Durations format as `18m42s`, or `1h02m03s` past the hour, and `—` for a negative value.

Chronos are started and stopped by external triggers in practice: a CheeseGuard
dimension-entry command runs `/cobblecap chrono start`, and an FTB Quests reward command
runs `/cobblecap chrono stop`. They are also stopped implicitly — `tryGrantLeagueMaster`
calls `ChronoManager.stop` itself, so beating the final champion ends the run whether or not
anything dispatched a stop.

Two automatic cancellations, both registered in `CobbleCapMod.onInitialize`: leaving a
`…:conseil4*` dimension for anywhere that is not conseil4-related, and disconnecting.

The chrono commands are documented in
[statue, chrono and champion commands](commands-statues.md#cobblecap-chrono-start-player),
including the hard gate on `chrono stop`.

## The Hall of Fame

`config/cobblecap_legends.json`, append-only in normal use, written atomically through a
`.tmp` file and a `Files.move`:

```json
{
  "legends": [
    {
      "uuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1",
      "playerName": "Steve",
      "completedAt": 1778000000000,
      "firstEver": true,
      "bestTimeMs": 1122000,
      "mode": "classic",
      "team": [
        {
          "species": "garchomp",
          "displayName": "Garchomp",
          "nickname": "",
          "level": 78,
          "shiny": false,
          "ball": "ultra",
          "nature": "adamant",
          "gender": "MALE",
          "form": "",
          "aspects": []
        }
      ]
    }
  ]
}
```

`isTest` is written only when `true`, and `bestTimeMs` only when greater than zero, which
keeps real history readable. `mode` is always written so the board code never has to guess.

The `team` array is a snapshot taken at promotion, read reflectively off the Cobblemon
party: species showdown id, display name, nickname, level, shiny, caught ball, nature,
gender, the primary form aspect and the raw aspect list. The aspects are what let a statue
be re-spawned later with the same regional or cosmetic variant. Every field degrades to `""`
or `0` rather than throwing, so a partial snapshot is possible and a Pokémon with an empty
`species` will not render as a statue.

Test entries from `/cobblecap statue test` carry `isTest: true` and are excluded from
`getAll()`, `hasLegend()`, `isEmpty()`, every broadcast, the webhook and the Conseil 4
boards. Only `getAllIncludingTests()` and the statue subsystem see them.

## Conseil 4 leaderboards

`Conseil4Boards` maintains four vanilla scoreboard objectives, created at
`SERVER_STARTED` and rebuilt after every promotion, record, ranked loss and `chrono stop`.
Each rebuild **drops and recreates** the objective so stale names cannot linger.

| Objective | Mode | Score written | Sidebar order |
|---|---|---|---|
| `conseil4_first_finish` | classic | `count − index` over `completedAt` ascending | oldest finisher on top |
| `conseil4_fastest_time` | classic | `−seconds` | fastest on top |
| `conseil4_ranked_champions` | ranked | `startedAt / 1000` | current champion on top |
| `conseil4_ranked_failures` | ranked | the fail count | most failures on top |

A fifth objective, `cobblecap_mode`, is pre-created here so `/cobblecap mode` never has to
bootstrap it. Its display names are French literals.

The inverted and negated scores are how a vanilla sidebar — which always sorts descending —
ends up showing the intended order. A consequence worth knowing: `conseil4_fastest_time`
holds **negative** scores, so any external consumer reading it raw must negate them back.
Another: scores are keyed by **player name**, not UUID, so a name change orphans a row until
the next rebuild.

The failures board's title is dynamic, `§bConseil 4 — Échecs face à <champion>`, and it is
reset implicitly on every new tenure because it is rebuilt from `current().failedAttempts`
and a new tenure starts empty.

### JSON export for CobbleCheeseScore

Each board also writes a file under `config/`:

| File | Board |
|---|---|
| `cobblecheesescore_external_conseil4_first.json` | first finishers |
| `cobblecheesescore_external_conseil4_fastest.json` | fastest times |
| `cobblecheesescore_external_conseil4_ranked_champions.json` | tenure history |
| `cobblecheesescore_external_conseil4_ranked_failures.json` | failures against the current champion |

All four share a shape: `generatedAt`, `source: "cobblecap"`, `mode`, `kind`, `count`, and a
`ranking` array of objects carrying at least `rank`, `playerUuid` and `playerName`. The
fastest and champion exports add `bestTimeMs` and a pre-formatted `bestTimeFormatted`; the
failures export adds `failures`, `lastFailAt`, the champion's identity and a `dynamicTitle`
that CobbleCheeseScore uses to override its configured board title.

The write is atomic through a `.tmp` sibling. These files are **produced** by CobbleCap and
**read** by [CobbleCheeseScore](../../CobbleCheeseScore/) — CobbleCap has no code dependency
on it, and if CobbleCheeseScore is absent the files are simply written and ignored.

`initObjectives` calls `rebuild` at boot. An earlier version wrote empty arrays
there, which blanked the dashboard tiles after every restart; the rebuild-from-storage is
the fix.

## The fireworks show

A ranked win calls `FireworksManager.triggerShow`, which launches `fireworksCount` rockets
`fireworksIntervalMs` apart inside the CheeseGuard zone named by `fireworksZoneId`, capped
at `fireworksDurationSec`. It is off by default (`fireworksEnabled: false`) and does nothing
without a zone id. CheeseGuard is reached reflectively, so the feature degrades to nothing
when CheeseGuard is absent. Aim it with `POST /api/v1/cobblecap/fireworks/test`, which
bypasses the enabled flag — see [the dashboard API](dashboard.md#post-apiv1cobblecapfireworkstest).

## What you must change for your own server

| What | Where | Why it matters |
|---|---|---|
| `champions.json` | `config/cobblecap/champions.json` — ships **empty** | without entries nothing gates the cap and nothing raises it |
| `champion_regis_challenge` | `ChampionManager.handleVictory` line 548, `handleChallengeVictory` line 605, `handleRegisRankedLoss` line 792, `StatueCommand.finalizeChrono` line 541 | the final-champion id is hard-coded in four places. A server whose last champion has another id never promotes a League Master and never counts a ranked loss |
| `datapackName = "CacademyMons"` | `RctTrainerMorpher.java` line 57 | see [champion morphing](champion-morphing.md#what-you-must-change) — the morph cannot work until this matches your datapack |
| the advancement JSONs | `data/cobblecap/advancement/**` | keyed by Arkensia's champion display names; yours need their own files |
| `cobblecap.msg.condition_progress` | the lang files | reads *You must talk to Alfred first*, which is Arkensia's quest NPC |
| the board titles and several broadcasts | `Conseil4Boards`, `ChampionManager`, `StatueCommand` | French literals, not translatable keys |

---

← [The level cap](level-cap.md) · [Champion morphing](champion-morphing.md) →
