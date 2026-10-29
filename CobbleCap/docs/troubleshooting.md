[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

## The mod does not appear at all

**Cause.** Either the jar is not loaded, or two copies are.

**Fix.** Grep the server log for `[CobbleCap] Initialized`. No such line means Fabric never
ran the initializer: check that `cobblecap-1.0.0+1.21.1.jar` is in `mods/`, that Fabric API
is there too, and that the loader is at least 0.15.11. If startup failed outright with a
duplicate-mod error, you have an old `cobblecap-*.jar` next to the new one — delete the old
one, do not rename it.

## Startup crashes with a mixin error

**Cause.** `cobblecap.mixins.json` sets `injectors.defaultRequire: 1`, so every injection
point is mandatory. A target method that no longer matches is a hard failure.

**Fix.** Read which mixin the crash names.

- `PokemonSetLevelMixin` or `PokemonAddExperienceMixin` — Cobblemon changed
  `Pokemon.setLevel`, `addExperience` or `addExperienceWithPlayer`. CobbleCap is built
  against Cobblemon 1.6.1; a newer Cobblemon needs the mixin updated.
- `BipedEntityModelMixin`, `PlayerEntityModelMixin`, `BipedAnimateModelMixin`,
  `PlayerEntityRendererMixin` or `EntityRenderDispatcherMixin` — these are **client** mixins
  and a Minecraft version mismatch will break them. They are cosmetic, but `defaultRequire`
  makes them fatal anyway.

There is no config switch to disable a mixin. The only workarounds are matching the
supported versions or editing `cobblecap.mixins.json` and rebuilding.

## The XP cap does nothing

**Cause.** The Cobblemon event hooks did not bind, or the cap system is off, or there is no
player row.

**Fix.** Work down this list in order.

1. Look for the four hook lines in the log:

   ```text
   [CobbleCap] Cobblemon XP hook registered on field: EXPERIENCE_GAINED_EVENT_PRE
   [CobbleCap] Cobblemon candy XP hook registered on field: EXPERIENCE_CANDY_USE_PRE
   [CobbleCap] Battle pre-check hook registered on field: BATTLE_STARTED_PRE
   [CobbleCap] Battle victory hook registered on field: BATTLE_VICTORY
   ```

   `[CobbleCap] Cobblemon not found — XP cap enforcement disabled` means Cobblemon is not
   installed. `Could not find XP event field in CobblemonEvents` means Cobblemon renamed the
   field and that layer is off — this is the line to check after every Cobblemon update.
2. `/cobblecap status <player>` and read `System enabled`. `false` means someone ran
   `/cobblecap disable` or the dashboard set `enabled: false`. Re-enable with
   `/cobblecap enable`.
3. Read `Cap level` on the same screen. If the player is not in the data at all you get
   `Player <name> not found in CobbleCap data.` — have them rejoin, which calls
   `ensureExists`.
4. Do not judge by the action-bar message. Battle XP on a capped Pokémon is zeroed by
   `PokemonAddExperienceMixin` before Cobblemon fires its XP event, so it is refused
   **silently** even when everything works; the message only appears when an exp candy is
   used on a Pokémon at or above the cap. Test with a candy.

## A Pokémon sits at one level below the cap

**Cause.** This is correct behaviour, not a bug.

**Fix.** Nothing. `PokemonAddExperienceMixin` computes headroom as
`xpForCapLevel − currentXp − 1`, leaving the Pokémon one experience point short of the cap
level so Cobblemon's level-up path never fires. A cap of 30 means Pokémon top out at **29**
with a nearly full bar. [Why](level-cap.md#2-pokemonaddexperiencemixin--the-shaping-layer).

## XP is blocked even though the player is below the cap

**Cause.** An XP lock, which is independent of the cap and of `enabled`.

**Fix.** `/cobblecap status <player>` and read `XP locked`. Clear it with
`/explock <player>` — it is a **toggle**, so run it once and re-check. The player sees
`🔒 %s — XP locked` on the action bar rather than the usual cap message, which is the
tell-apart.

## A champion does nothing when right-clicked

**Cause.** Almost always the trainer id in `champions.json` does not match the entity.

**Fix.**

1. `/cobblecap reload` and read the count. No count at all, only the vanilla *An unexpected
   error occurred trying to execute that command*, means the JSON has a syntax error and the
   old list is still live — the exception is in the server log. A count lower than the
   number of entries in the file means an entry has a wrongly typed value and only the
   entries before it were loaded.
2. Right-click the trainer and look for the diagnostic line:

   ```text
   [CobbleCap] BATTLE_PRE: entity=TrainerMob trainerId=gym_leader_pierre_0395 (raw=gym_leader_pierre_0395)
   ```

   Compare `trainerId` against your `id` byte for byte. If `raw` differs from `trainerId`, a
   per-dimension suffix was stripped — that is expected inside a per-player Conseil 4
   dimension.
3. `trainerId=null` means `extractRctmodTrainerId` found nothing on that entity. No line at
   all means no battle started, or the battle pre-check hook did not bind (see
   [above](#the-xp-cap-does-nothing)). The entity must also be in the `rctmod` namespace
   for `UseEntityCallback` to even look at it.
4. Confirm the entry is loaded with `/cobblecap status <player>` — the champion list is
   printed in full with `✓`/`✗` per entry.

## A champion fight is refused

**Cause.** One of four gates. Each produces a different message.

**Fix.** Match the message:

| Message | Gate | Clear it by |
|---|---|---|
| `You cannot challenge X yet — you must defeat Y first.` | ordering — X is not `nextUnbeaten` | beat Y, or `/cobblecap addbeaten <player> "<Y's id>"` |
| `Cannot challenge X — conditions not met:` then `Pokédex: n/m caught` | `requiredPokedex` | catch more, or lower the field |
| `… Complete a required quest first` | `requiredQuestId` | complete the FTB quest |
| `… You must talk to Alfred first` | `requiredScoreboard` / `requiredScoreMin` | raise the score. The message text is Arkensia-specific — see below |
| `X — "What a great battle! …"` | already beaten, no challenge unlocked | nothing; `allowRefight` does not change this |

`You have already defeated X.` is not a refusal you will meet here. `handlePreBattle` would
print it for a beaten champion with `allowRefight: false`, but every caller checks
`championsBeaten` first and never hands it a beaten champion, so in practice the line only
appears on a **first** victory when the cap was already at or above `capGranted`.

`/cobblecap status <player>` prints every gate with `OK`/`FAIL` and the current value, which
is faster than guessing. The player-facing `/cobblecap` does **not** show the
scoreboard gate — only the admin view does.

The `You must talk to Alfred first` wording is the lang key
`cobblecap.msg.condition_progress`, written for Arkensia's quest NPC. If your scoreboard
gate means something else, edit `src/main/resources/assets/cobblecap/lang/{en_us,fr_fr}.json`.

## A Pokédex gate can never be satisfied

**Cause.** `getPokedexCount` fails **closed** — it returns `0` on any reflection failure,
which makes every Pokédex gate impassable.

**Fix.** `/cobblecap status <player>` shows `pokedex: 0/80 FAIL` for a player who demonstrably
has more. That mismatch is the signature. The server log has
`[CobbleCap] getPokedexCount error: …` at debug level — enable debug logging for the
`cobblecap` logger to see it.

This is the only reflection failure in the mod that blocks players rather than letting them
through. Until it is fixed, work around it by setting `requiredPokedex` to `0` and gating on
a scoreboard instead.

## A victory did not raise the cap

**Cause.** The victory was not detected, or the cap was already higher.

**Fix.**

1. If the cap was already at or above `capGranted`, `raiseCapLevel` returns `false` by
   design and the player gets `You have already defeated X.` instead of the victory line.
   That is correct.
2. Otherwise the `BATTLE_VICTORY` hook did not identify the battle. Check the hook line
   exists (see [above](#the-xp-cap-does-nothing)), then repair by hand:

   ```mcfunction
   /cobblecap addbeaten Steve "gym_leader_pierre_0395"
   ```

## `/cobblecap set` raised the cap but the player still cannot progress

**Cause.** `set` writes only the number. `championsBeaten` is untouched, so the ordering
gate still believes the player is at their old point.

**Fix.** The server log says so explicitly, and names the command:

```text
[CobbleCap] Order-check rejection may be a desync: player=Steve cap=50 tried=gym_leader_erika_0399 expectedNext=gym_leader_ondine_0396 championsBeaten=[…]. If the player legitimately beat prior champions, repair with: /cobblecap addbeaten Steve gym_leader_ondine_0396
```

Run that `addbeaten` for each champion they should have beaten. Prefer `addbeaten` to `set`
in the first place — it raises the cap *and* records the progress.

## A trainer keeps offering the challenge fight

**Cause.** `swapToChallengeBattle` sets the TrainerMob's id to the challenge variant and
restores it two `server.execute` hops later. If the entity unloaded or the server stalled in
between, the id is stuck.

**Fix.** It self-heals. `CapListener` detects a challenge id on an entity whose champion the
player has not beaten normally, restores the normal id — preserving any per-dimension suffix
— and treats the interaction as a normal fight:

```text
[CobbleCap] Entity has stuck challenge ID 'gym_leader_pierre_challenge' but player hasn't beaten 'gym_leader_pierre_0395' — restoring to 'gym_leader_pierre_0395'
```

If it does not clear, set the id by hand with rctmod's own tooling, or kill and re-place
the NPC.

## Nobody is ever crowned League Master

**Cause.** The final champion's id is not `champion_regis_challenge`.

**Fix.** That string is hard-coded in four places — `ChampionManager` lines 548, 605 and
792, and `StatueCommand` line 541. Either rename your final champion's `id` to
`champion_regis_challenge` in both `champions.json` and your rctmod datapack, or edit those
four lines and rebuild.

The symptom on `chrono stop` is explicit:

```text
[CobbleCap] Chrono stop for Steve REFUSED — champion_regis_challenge not beaten this run; bestTime ignored, no legend created
```

and the player gets the French line
`⚠ Le chrono a été arrêté mais tu n'as pas (encore) battu Régis Challenger…`.

## Champion morphing does nothing

**Cause.** The datapack was not found. This is the single most likely problem on any server
that is not Arkensia.

**Fix.** The log names every path it tried:

```text
[CobbleCap] Datapack 'CacademyMons' not found in any of: [/srv/mc/world/datapacks/CacademyMons, …]
```

`datapackName` is hard-coded to `"CacademyMons"` at
`src/main/java/com/cobblecap/logic/RctTrainerMorpher.java` **line 57**. It is **not** a
config key, despite the comment above it claiming otherwise. Edit the source, rebuild,
redeploy. Full detail in
[champion morphing](champion-morphing.md#what-you-must-change).

Once the path resolves you should see:

```text
[CobbleCap] Datapack root resolved: /srv/mc/world/datapacks/YourPack
[CobbleCap] Trainer JSON rewritten: …/champion_regis_challenge.json (6 Pokémon)
[CobbleCap] rctmod TrainerManager.loadTrainers() invoked (ok=true)
```

## The morphed team only appears after a restart

**Cause.** The reflective rctmod reload failed. The file on disk is correct; rctmod's
in-memory registry is stale.

**Fix.**

```text
[CobbleCap] rctmod reload failed after morph -> Steve — JSON on disk updated, restart to apply.
[CobbleCap] rctmod reload via reflection FAILED: <exception>
```

Nothing is corrupt. Either restart, or fix the reflection: `RctReloadHook` targets
`com.gitlab.srcmc.rctmod.api.RCTMod.getInstance().getTrainerManager().loadTrainers()`,
verified against rctmod-fabric 0.16.11-beta. A different rctmod version may have moved it.
Do **not** reach for `/reload` — a vanilla datapack rescan freezes a large modded server for
seconds, which is exactly what this hook exists to avoid.

## The champion's face never changes

**Cause.** Correct behaviour. The skin morph is **not implemented**.

**Fix.** Nothing to fix. `flushMorph` logs
`[CobbleCap] Skin morph skipped (architectural limitation — see RctTrainerMorpher comment)`.
A datapack cannot serve textures — assets come from resource packs — and writing to
`resourcepacks/` would force every client to re-download the modpack. The team morphs, the
face does not. [Why](champion-morphing.md#the-skin-is-not-written).

## Two players fight different Conseil 4 variants than they chose

**Cause.** There is one live trainer JSON and it is server-global, while `cobblecap_mode` is
per-player.

**Fix.** There is no fix from configuration — it is a design limitation, acknowledged in the
source. Overlapping entries can land on a variant they did not choose. [Details](champion-morphing.md#the-régis-mode-swap-is-global). Serialise entries, or
accept it as Arkensia does at five to ten entries a week.

## A statue is invisible, or renders as Steve

**Cause.** Several, in rough order of likelihood.

**Fix.**

1. **No profile with textures.**

   ```text
   [CobbleCap] No profile with textures for Steve (f84c6a79-…) — doppel will not render
   ```

   `MojangProfileFetcher` tried the online player, the user cache, the session cache and a
   direct Mojang lookup, and all four failed. On an offline-mode server or with no outbound
   network, there is nothing to fetch.
2. **The doppelgänger has not been re-sent.** Walk away past 160 blocks and back; the
   3-second auto-heal calls `ensureVisibleToNearby(128.0)` and should reattach it. The log
   says so:

   ```text
   [CobbleCap][Doppel] auto-heal: shown to 1 new viewer(s) across 2 doppel(s)
   ```
3. **Steve fallback specifically** means two player entities shared a UUID and confused the
   client's texture cache. The mod de-duplicates on spawn
   (`[CobbleCap][Doppel] DEDUP: …`), but a client that already cached the wrong skin needs a
   relog.
4. **It is an orphan tracker.** An invisible marker armour stand whose overlay never
   attached is unfindable by hand. `/cobblecap statue wipe_all` is tag-based and will clear
   it, then re-place.

## A statue is styled but looks like a normal skin

**Cause.** The viewer does not have CobbleCap on their client.

**Fix.** The styled body texture comes from `PlayerEntityRendererMixin` and the PNGs in the
jar, so it is client-side. The glow colour and the particle aura are server-side and visible
to everyone. Install the mod on the client, or accept that screenshots will differ between
players.

A `natural` statue intentionally shows the real skin — `StatueStyleClient` stores `natural`
as absent so it falls through to vanilla resolution.

## A `place_current` diorama has no Pokémon row

**Cause.** No operator was online when the refresh ran.

**Fix.** Cobblemon's `/spawnpokemonat` calls `source.getPlayer().getUuid()` and throws
without a player, so `spawnDioramaAtAnchor` borrows any online level-2 player as a command
source. `findOnlineOp` returning `null` means the trainer figure spawns and the Pokémon row
does not. Run `/cobblecap champion refresh_dioramas` while an operator is connected.

The other possibility is a champion resolved through the third rung of the fallback ladder —
synthesised from the `Tenure` alone, with the right identity but **no team**. See
[who "the current champion" is](statues.md#who-the-current-champion-is).

## `statue move` reports `Moved 0 entit(y/ies)`

**Cause.** The `slot` or `playerName` matched nothing.

**Fix.** `slot` on `move`, `rotate`, `scale` and `style` is a **word**, not an integer:
`player`, `p`, `1`–`6`, or `all`. An unrecognised value is accepted and affects nothing.
`remove_slot` additionally does **not** accept `all`. Check the diorama exists at all with
`/cobblecap statue list`, and remember the key is the lower-cased player **name** — a
rename orphans the diorama.

## `/pasturehide` hides nothing

**Cause.** The client has no pasture data from the server.

**Fix.** Turn it on and read the client log:

```text
[CobbleCap-Client] /pasturehide last 1s: checked 42 Pokémon entities, hid 7
```

- no line at all while the toggle is on and Pokémon are in view — the mixin is not seeing
  Pokémon entities. The line is only printed when at least one was checked, so `checked 0`
  never appears. Cobblemon is absent on the client, or the entity class name is not
  `…PokemonEntity`.
- `checked 42, hid 0` — the mixin works and the client set is empty. The server has not sent
  a snapshot: it does not have CobbleCap, or `PastureTracker` has not run its first scan
  (every 40 ticks), or the Pokémon are genuinely not tethered.
- no line and nothing in view either — check the toggle is on: `/pasturehide status`.

On join the client should log
`[CobbleCap-Client] Pasture snapshot received: N tethered Pokémon`. Absent, the server side
is the problem.

## The dashboard module is missing

**Cause.** CheeseDash is not installed, or the entrypoint never ran, or the server was not
ready.

**Fix.**

1. CheeseDash must be in `mods/`. It is only `suggests` at runtime, so its absence is silent
   — the module simply never registers.
2. A `503 {"error":"CobbleCap not ready"}` means CheeseDash is up but either its
   `onServerStart` has not fired or `CobbleCapMod.INSTANCE` is null. Wait for startup to
   finish; if it persists, CobbleCap's initializer did not run — see the first symptom on
   this page.
3. Confirm `fabric.mod.json` carries
   `"cheesedash": ["com.cobblecap.dashboard.CobbleCapDashboardModule"]`. It does in a stock
   build.

## A `READ_ONLY` dashboard account can change caps

**Cause.** You are running a build from before **29 October 2026**.

**Fix.** Update. The four write guards in `CobbleCapApi` were written
`WebDashboard.requireWrite(exchange);` as a bare statement, discarding the return value, so
the 403 was sent and the handler carried on anyway. They are now
`if (!WebDashboard.requireWrite(exchange)) return;`. Until you can update, give nobody a
`READ_ONLY` account — treat every account as OPERATOR.
[Details](dashboard.md#roles).

## The dashboard reports success but the value is different

**Cause.** The fireworks setters clamp silently.

**Fix.** `fireworksCount` is clamped to `max(1, v)`, `fireworksIntervalMs` to `max(50, v)`
and `fireworksDurationSec` to `max(1, v)`. A `PUT` returns `{"updated": true}` with the
value you sent while storing the clamped one. Re-`GET /api/v1/cobblecap/config` to see what
was actually kept.

`startingCap` is a different case: `GET` returns it, `PUT` ignores it entirely. Edit
`config/cobblecap/config.json` and run `/cobblecap reload` (or restart).

## The fireworks never fire

**Cause.** Disabled, no zone, an unresolvable zone, or the duration cap.

**Fix.**

```text
[CobbleCap] Fireworks enabled but no zone configured — skipped        (debug level)
[CobbleCap] Fireworks zone 'league_arena' not found via CheeseGuard — show aborted
[CobbleCap] Fireworks show already running — new request coalesced
[CobbleCap] Fireworks show hit duration cap at rocket 12/200
```

Aim it with `POST /api/v1/cobblecap/fireworks/test`, which ignores `fireworksEnabled` but
still needs a zone id. CheeseGuard is reached by reflection; without it the zone cannot
resolve, so you get the same `Fireworks zone '…' not found via CheeseGuard — show aborted`
warning (plus a debug line saying CheeseGuard is not installed). And remember the show only triggers on a
**ranked** win, never a classic one.

## Conseil 4 scoreboards are blank or wrong

**Cause.** The wrong sign convention, or name-keyed rows.

**Fix.** Scores are deliberately inverted so a vanilla sidebar's descending sort shows the
intended order:

| Objective | Score stored |
|---|---|
| `conseil4_first_finish` | `count − index` — oldest finisher highest |
| `conseil4_fastest_time` | **negative** seconds — fastest highest |
| `conseil4_ranked_champions` | `startedAt / 1000` |
| `conseil4_ranked_failures` | the raw fail count |

An external consumer reading `conseil4_fastest_time` raw must negate it. Prefer the JSON
exports at `config/cobblecheesescore_external_conseil4_*.json`, which carry real values and
a pre-formatted time.

Rows are keyed by player **name**, not UUID, so a rename orphans a row until the next
rebuild. Force one with any event that calls `Conseil4Boards.rebuild` — a `chrono stop`, or
a restart.

If all four are empty after a restart, that was a bug in an older build which wrote empty
arrays at boot. The current `initObjectives` calls `rebuild` from persistent storage.

## A chrono survived a restart and shows an absurd time

**Cause. ** Correct behaviour. `cobblecap_chronos.json` persists the **start timestamp**, and
the clock is not paused while the server is down.

**Fix.** `/cobblecap chrono cancel <player>`, or `/cobblecap chrono start <player>` to
restart it — `start` has force-restart semantics and always discards a running run. If you
restart during a race, cancel the affected chronos before anyone reaches the end.

## Progress was lost after a crash

**Cause.** The 500 ms debounce on `players.json`, or a truncated `cobblecap_tenures.json`.

**Fix.** `CapStorage.saveAsync` schedules a full write 500 ms after a change, so a hard
crash can lose that window — one cap raise, realistically. A clean shutdown loses nothing
(`SERVER_STOPPING` calls a synchronous `save()`).

`cobblecap_tenures.json` is the riskier one: unlike `players.json`, `legends`, `chronos` and
`dioramas`, it is **not** written atomically. A crash mid-write can truncate it, and it holds
the current champion. If it fails to parse you get
`[CobbleCap] Failed to load tenures: …` and an empty tenure list, which means no current
champion and a blank ranked board. Restore it from a backup; there is no automatic recovery.

## A test statue or test legend will not go away

**Cause.** `test_clear` only touches the caster's own data.

**Fix.** `/cobblecap statue test_clear` removes test-tagged statues and test legend rows for
**the player who runs it**. To clear someone else's, use
`/cobblecap chrono wipe <player>` — but one removes **every** legend entry for
that player, test and real alike, and cancels their chrono. There is no per-player
test-only clear.

---

## Filing a useful bug report

Open an issue at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) with:

1. **The jar version**, from `gradle.properties` (`mod_version`) or the filename.
2. **Versions of Minecraft, Fabric Loader, Fabric API, Cobblemon and rctmod.** The last two
   matter more than anything else here, because almost every failure mode in this mod is a
   reflection target that moved.
3. **Which side.** Server, client, or both — and whether the reporting player has the mod
   installed.
4. **The relevant log.** Server: `logs/latest.log`. Client: the instance's `logs/latest.log`.
   Grep for `[CobbleCap]` and include **all** of it from startup onward, not just the lines
   around the failure — the four hook-registration lines at startup are often the real
   answer.
5. **`/cobblecap status <player>`** output for the affected player, as text. It is long;
   paste it anyway, or attach it. It contains the cap, the beaten list, every gate with
   `OK`/`FAIL`, every scoreboard value and every granted advancement.
6. **The configs.** `config/cobblecap/config.json` and `config/cobblecap/champions.json`,
   redacting `discordWebhookUrl`.
7. **For a morph problem**, the `Datapack root resolved` or `not found in any of` line
   verbatim, and your actual datapack directory name.
8. **For a statue problem**, `/cobblecap statue list` and every `[CobbleCap][Doppel]` line.

Questions rather than bugs → [the Arkensia Discord](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.md) · [CobbleCap](../README.md) →
