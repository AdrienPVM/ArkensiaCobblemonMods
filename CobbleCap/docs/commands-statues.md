[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands-statues.fr.md)

# Statue, chrono and champion commands

Three subtrees of `/cobblecap`, all attached by `StatueCommand.attach` and all gated at
**permission level 2** at the subtree root. A non-op sees none of them in tab completion.

| Subtree | What it drives |
|---|---|
| `/cobblecap statue …` | [the statue and diorama subsystem](statues.md) |
| `/cobblecap chrono …` | the Conseil 4 stopwatch, and the League Master promotion that a `stop` can trigger |
| `/cobblecap champion …` | [champion morphing](champion-morphing.md) — the RCT trainer rewrite and the NPC fallback |

Most of these commands return a **count** rather than a boolean, which makes them usable
under `/execute store result`.

---

## The `slot` argument

Every statue command that edits an existing diorama takes a `slot`, and it is normalised
by `StatueCommand.normalizeSlot` before use:

| You type | Means |
|---|---|
| `player` or `p` | the trainer figure |
| `1`–`6` | that party slot — **converted to 0-based internally** |
| `all` | every entity of that diorama (`move`, `rotate`, `scale`, `style` only) |
| anything else | passed through lower-cased, and will match nothing |

Tab completion offers `player 1 2 3 4 5 6` for `remove_slot`, and the same plus `all` for
`move`, `rotate`, `scale` and `style`. The suggestions are hints: an unmatched value is
accepted and affects zero entities, so a command that reports `Moved 0 entit(y/ies)` is
usually a typo in `slot`, not a missing diorama.

## The `style` argument

Thirteen styles, from the `StatueStyle` enum. Tab-completed everywhere, and
**`StatueStyle.fromKey` falls back to `natural` for anything it does not recognise** rather
than failing — a misspelled style silently gives you an untinted statue.

| Key | Glow colour | Particle aura |
|---|---|---|
| `natural` | — | none (original textures, no glow) |
| `stone` | `0xAAAAAA` | `white_ash` |
| `gold` | `0xFFD700` | `end_rod` |
| `emerald` | `0x50C878` | `happy_villager` |
| `diamond` | `0x5DECF5` | `enchanted_hit` |
| `amethyst` | `0xB180EB` | `dragon_breath` |
| `copper` | `0xC87533` | `wax_on` |
| `netherite` | `0x4D4544` | `sculk_soul` |
| `obsidian` | `0x42325B` | `falling_obsidian_tear` |
| `crystal` | `0xF8E7FF` | `glow` |
| `glass` | `0xCCEEFF` | `bubble_pop` |
| `frost` | `0x88CCFF` | `snowflake` |
| `soul` | `0x29D3DB` | `soul_fire_flame` |

`/cobblecap statue styles` prints the live list, which is the authoritative answer if this
table ever drifts from the enum.

---

## `/cobblecap statue place <playerName> [style] [scale]`

```mcfunction
/cobblecap statue place <playerName> [style] [scale]
```

Places a **full diorama** — the trainer plus their recorded six Pokémon — at the caster's
position. Must be run by a player: the caster's position and facing are the anchor.

| Argument | Type | Required | Accepted | Default |
|---|---|---|---|---|
| `playerName` | single word | yes | a name present in `cobblecap_legends.json` | — |
| `style` | single word | no | a style key, tab-completed | `natural` |
| `scale` | double | no | 0.2–5.0 | `1.0` |

The lookup is `findLatestForPlayerName(playerName, includeTests = false)`, so a player who
only has test entries is treated as having none. The team posed is the team **snapshotted
when they became League Master**, not their current party.

```mcfunction
/cobblecap statue place Steve gold 1.5
```

The reply is `StatueManager.PlaceResult.message()`, green on success and red on failure,
and the command's numeric result is the number of entities spawned.

| Message | Cause |
|---|---|
| `Must be run by a player` | console or command block |
| `No legend found for player: <name>` | no non-test legend row under that exact name; names are matched case-insensitively by `findLatestForPlayerName` but must otherwise be exact |

## `/cobblecap statue place_player <playerName> [style] [scale]`

Same arguments and same failures, but places **only the trainer figure**. Returns `1` on
success.

## `/cobblecap statue place_pokemon <playerName> <slot> [style] [scale]`

```mcfunction
/cobblecap statue place_pokemon <playerName> <slot> [style] [scale]
```

Places **one** Pokémon from the recorded team.

| Argument | Type | Required | Accepted |
|---|---|---|---|
| `playerName` | single word | yes | a non-test legend name |
| `slot` | integer | yes | **1–6**, converted to 0-based before the call |
| `style` | single word | no | style key |
| `scale` | double | no | 0.2–5.0 |

Here `slot` is a real integer argument, so Brigadier rejects `0`, `7` and `player`. That is
different from the `slot` on `remove_slot`/`move`/`rotate`/`scale`/`style`, which is a word.

## `/cobblecap statue place_current [style] [scale]`

```mcfunction
/cobblecap statue place_current [style] [scale]
```

Places a **dynamic** diorama bound to whoever the current champion is, rather than to a
named player. When the RCT trainer morphs to a new winner, this diorama is torn down and
re-spawned with the new champion's skin and team.

```text
[CURRENT] <message> — will auto-refresh when the champion morphs
```

Takes no `playerName`. The anchor is persisted in `config/cobblecap_dioramas.json` so it
survives a restart. Details in [the statue subsystem](statues.md#the-dynamic-champion-diorama).

## `/cobblecap statue place_current_player [style] [scale]`

The same thing without the Pokémon row — and the omission persists across refreshes, so a
champion change updates the trainer's skin only.

```text
[CURRENT player-only] <message> — auto-refreshes player skin only when champion morphs (no Pokémon)
```

## `/cobblecap statue test [style] [scale]`

```mcfunction
/cobblecap statue test [style] [scale]
```

Snapshots the **caster's own current party**, writes a legend row flagged `isTest = true`,
and stages a full diorama from it. This is how you preview a style or a scale without
touching the real Hall of Fame.

```text
[TEST] Captured team (6 Pokémon) — <message> — clear with /cobblecap statue test_clear
```

A test legend never appears in `/cobblecap legends`, is never broadcast, and is skipped by
every `place*` lookup. It exists only to feed this preview.

## `/cobblecap statue test_clear`

```mcfunction
/cobblecap statue test_clear
```

Must be run by a player, and only ever touches **the caster's own** test data: test-tagged
statue entities, and test legend rows for the caster's UUID.

```text
Cleared 1 test legend(s) and 7 test statue entit(y/ies)
```

Real League Master entries and real statues for the same player are left alone.

## `/cobblecap statue remove <playerName>`

Removes every statue entity belonging to that legend's diorama. Returns the count.

```text
Removed 7 statue entit(y/ies) for Steve
```

A count of `0` means nothing matched — either the name is wrong or the diorama was already
gone. There is no error message for a missing diorama.

## `/cobblecap statue remove_slot <playerName> <slot>`

Removes one figure. `slot` is a word: `player`, `p`, or `1`–`6`. `all` is **not** accepted
here — use `remove` for that.

```text
Removed 1 entit(y/ies)
```

## `/cobblecap statue move <playerName> <slot> <dx> <dy> <dz>`

```mcfunction
/cobblecap statue move <playerName> <slot> <dx> <dy> <dz>
```

| Argument | Type | Range |
|---|---|---|
| `slot` | word | `player`, `1`–`6`, `all` |
| `dx` `dy` `dz` | double | −100 to 100 each |

A **relative** offset in blocks, not an absolute position. `all` passes `null` as the
selector, which `StatueManager` reads as "every entity of this diorama".

```mcfunction
/cobblecap statue move Steve all 0 0.5 0
```

```text
Moved 3 entit(y/ies)
```

## `/cobblecap statue rotate <playerName> <slot> <yaw>`

`yaw` is a float from −360 to 360, and it is an **absolute** yaw, not a delta.

```text
Rotated 7 entit(y/ies)
```

## `/cobblecap statue scale <playerName> <slot> <factor>`

`factor` is a double from **0.1 to 5.0** — note the lower bound differs from the `scale`
argument on the `place*` commands, which starts at 0.2. Absolute, not multiplicative.

```text
Scaled 7 entit(y/ies)
```

## `/cobblecap statue style <playerName> <slot> <style>`

Restyles existing figures in place, glow colour and particle aura together.

```text
Restyled 7 entit(y/ies) -> netherite
```

## `/cobblecap statue list`

```text
─── Placed Dioramas (2) ───
  steve — minecraft:overworld @ (128.5, 71.0, -340.5) [7 entit(y/ies)]
  alex — minecraft:overworld @ (134.5, 71.0, -340.5) [7 entit(y/ies)]
```

`No diorama currently placed.` with nothing staged, and the command returns `0`.

Names are shown **lower-cased**: the list is built from the `cobblecap_statue_legend.<name>`
tag, which `StatueManager` writes with `toLowerCase`. The coordinates are those of the first
entity of the diorama found in the scan, not necessarily the trainer figure.

## `/cobblecap statue tp <playerName>`

Teleports the caster to the position `statue list` shows for that diorama, one block up, keeping their current yaw and
pitch. Matching is case-insensitive against the names in `statue list`.

```text
TP'd to diorama of steve
```

| Message | Cause |
|---|---|
| `Must be run by a player` | non-player source |
| `No diorama for <name>` | no entry in `statue list` under that name |
| `World not loaded: <id>` | defensive only: `statue list` scans loaded worlds only, so the world it reports always resolves and this message should never appear |

## `/cobblecap statue stats`

Counts dioramas, total entities, and entities per style. The per-style tally is built by
iterating **every entity in every loaded world** looking for CobbleCap's master tag, which
is a real scan: avoid it on a busy server during peak.

```text
─── Statue Stats ───
Total dioramas: 2 · total entities: 14
  · gold: 7
  · netherite: 7
```

## `/cobblecap statue styles`

Prints the live enum.

```text
Available styles: natural stone gold emerald diamond amethyst copper netherite obsidian crystal glass frost soul
```

## `/cobblecap statue wipe_all`

```mcfunction
/cobblecap statue wipe_all
```

⚠️ Destructive and not an undo. A tag-based scan that removes **every** CobbleCap statue
entity in every world — including orphan invisible armour stands whose doppelgänger never
re-attached after a restart — and then clears the dynamic-champion anchor list, so
`place_current` and `place_current_player` start from nothing.

```text
Wiped 23 statue entit(y/ies) and cleared all anchors
```

The feedback is broadcast to ops. Use it when the statue state is visibly corrupt; use
`remove` for anything targeted.

---

## `/cobblecap chrono start <player>`

```mcfunction
/cobblecap chrono start <player>
```

Starts the Conseil 4 stopwatch, with **force-restart** semantics: an already-running chrono
is always discarded and replaced. This is deliberate, so that wiring the command to a
dimension-entry trigger makes re-entry restart the run rather than silently continue an
old one.

```text
Chrono started for Steve
```

or, when one was already running:

```text
Chrono RESTARTED for Steve (was at 7m12s)
```

The player themselves gets `⏱ Conseil 4 chronometer started. Good luck!`.

Two events cancel a chrono without any command, both in `CobbleCapMod.onInitialize`:

- leaving a `…:conseil4*` dimension for a dimension that is **not** conseil4-related. The
  player is told `⏱ Chrono Conseil 4 annulé — tu as quitté la dimension`. That string is a
  `Text.literal` in French in the source, so an English client sees French here.
- disconnecting. A partial run is discarded so the next attempt starts clean.

## `/cobblecap chrono start_keep <player>`

The older semantics: start **only** if nothing is running.

```text
Chrono already running for Steve (elapsed: 7m12s)
```

Returns `1` when it started one and `0` when it declined. Use this instead of `start` if
you want dimension re-entry to preserve the timer.

## `/cobblecap chrono stop <player>`

```mcfunction
/cobblecap chrono stop <player>
```

Stops the clock and runs the League Master finalisation. This is the command that can crown
someone, so it has a hard gate.

**If the player has no legend yet**, they are promoted only when
`champion_regis_challenge` is in their `championsBeaten` or `challengeBeaten`. Otherwise:

```text
⚠ Le chrono a été arrêté mais tu n'as pas (encore) battu Régis Challenger. Aucun statut de Maître de la Ligue accordé.
```

The log line is `[CobbleCap] Chrono stop for <name> REFUSED — champion_regis_challenge not
beaten this run; bestTime ignored, no legend created`, the Conseil 4 boards are rebuilt
anyway so an admin can see the timer was processed, and the command returns `0`. That
message is a French `Text.literal` too.

⚠️ `champion_regis_challenge` is **hard-coded** in `StatueCommand.finalizeChrono`. On a
server whose final champion has any other id, `chrono stop` can never promote anybody.

**If the player already has a legend**, the gate does not apply and only the best time is
touched:

- faster than the stored `bestTimeMs`, or no time stored yet: the entry is replaced with
  the new time and the full broadcast fires
- slower: the old record is kept and everyone sees
  `⏱ Steve a fini en 24m05s (record perso : 18m42s)` — again a French literal — and the
  command returns `1` without a webhook or a morph

On a successful promotion or record, in order: the server-wide broadcast
(`cobblecap.msg.league_master_chrono`, or `…_first_chrono` for the very first Master ever),
the Discord webhook through `CobbleCapWebhook.sendLeagueMaster`, a **queued** RCT trainer
morph via `rctMorpher.request(player, elapsed)`, and a `Conseil4Boards.rebuild`.

```text
🏆 Steve completed Conseil 4 in 18m42s and is now the Pokémon League Master!
```

`No active chrono for <name>` as an error when nothing was running.

## `/cobblecap chrono cancel <player>`

Discards the run. No legend update, no broadcast, no webhook.

```text
Chrono cancelled for Steve
No active chrono for Steve
```

## `/cobblecap chrono wipe <player>`

⚠️ Cancels any active chrono **and deletes every legend entry for that player, test and
real alike**. The in-code comment names its purpose: cleaning up after a `chrono stop` that
accidentally crowned someone during testing.

```text
Wiped Steve — cancelled chrono: yes, removed legend entries: 2
```

Broadcast to ops. There is no undo; `cobblecap_legends.json` is rewritten.

## `/cobblecap chrono list`

Active chronos with elapsed times. It iterates **online players only**, so a chrono held by
someone who has since disconnected is not listed — though in practice a disconnect cancels
it anyway.

```text
─── Active chronos (2) ───
  · Steve — 7m12s
  · Alex — 1m48s
```

`No active chronos` when there are none, returning `0`.

## `/cobblecap chrono status [player]`

With no argument, just the count:

```text
2 active chrono(s)
```

With a player:

```text
Steve — 7m12s
No active chrono
```

---

## `/cobblecap champion morph_now <player>`

```mcfunction
/cobblecap champion morph_now <player>
```

Queues a morph of the `champion_regis_challenge` RCT trainer onto that player, with a
recorded time of `0`. It is a **request**, not an immediate action: the player's party is
snapshotted now, and `RctTrainerMorpher.tick` flushes it at the next 20-tick boundary (within
a second). Despite the wording of the reply below, the flush **no longer waits** for
battles to end: rctmod reads a trainer's team at battle start, so a fight already in
progress keeps the old team and only new ones see the morph.

```text
Queued morph of champion_regis_challenge to Steve — applied when no battle is active
```

Broadcast to ops. There is no chat feedback when the morph lands — watch the server log
for `Trainer JSON rewritten`. The mechanism is in
[champion morphing](champion-morphing.md).

## `/cobblecap champion bind_npc <uuid>`

```mcfunction
/cobblecap champion bind_npc <uuid>
```

Binds a Cobblemon NPC entity by UUID as the champion's avatar. This is the **fallback
path**, used when the datapack rewrite is not viable.

```text
[NPC] <detail>
```

Green when `ChampionNpcManager.MorphResult.OK`, red otherwise, and the detail string is
whatever the manager reports. `Invalid UUID` if the word is not a UUID.

## `/cobblecap champion bind_npc_closest`

Binds the nearest Cobblemon NPC to the caster, searching only the caster's own world and
only **within 10 blocks**. Must be run by a player.

```text
[NPC] bound nearest NPC <uuid> (dist=3.2)
[NPC] no Cobblemon NPC within 10 blocks
Must be run by a player
```

## `/cobblecap champion unbind_npc`

Clears the binding in `ChampionNpcState`. Always succeeds.

```text
Champion NPC unbound
```

## `/cobblecap champion status`

```text
Champion NPC: 4f0c…-…-… in minecraft:overworld
Last morphed champion: (none)
```

`(none)` in either position when unset. Do not read the second line as a health check of the
morph: it is written only by `ChampionNpcManager.morphTo`, which nothing in the mod calls
(see [champion morphing](champion-morphing.md#the-fallback-a-cobblemon-npc)), so the RCT
datapack morph never updates it and it normally stays `(none)`. To see whether a morph
landed, look for `Trainer JSON rewritten` in the server log.

## `/cobblecap champion refresh_dioramas`

Re-spawns every `place_current` and `place_current_player` diorama from the current
champion. Normally automatic on a morph; run it by hand when a diorama is visibly stale.

```text
Current-champion dioramas refreshed
```

## `/cobblecap champion backup_now`

Snapshots the Régis trainer JSON and its skin PNG into `config/cobblecap_regis_backup/`.
No-op if a backup is already there, so it never overwrites a good one with a morphed one.

```text
Régis backup ensured at config/cobblecap_regis_backup/ — see log for details
```

Broadcast to ops. **Run this before the first morph on a new server.** Without a backup
there is no way back to the original trainer.

## `/cobblecap champion restore_original`

Restores both the trainer JSON and the skin PNG from that backup, then triggers an rctmod
reload.

```text
Régis restore: <status>
```

The status string comes straight from `RctTrainerMorpher.restoreOriginal` and reports what
it actually managed to do, which may be partial. Read it rather than assuming success.

---

← [Commands](commands.md) · [Dashboard API](dashboard.md) →
