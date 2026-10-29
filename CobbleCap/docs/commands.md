[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

CobbleCap registers three command roots:

| Root | Side | Registered in |
|---|---|---|
| `/cobblecap` | server | `CobbleCapCommand.register` |
| `/explock` | server | `ExpLockCommand.register` |
| `/pasturehide` | **client** | `PastureHideCommand.register` |

`/pasturehide` is a Fabric *client* command. It never reaches the server, so it works on
any server as long as the player has CobbleCap installed, and typing it in a console does
nothing because the console has no client.

The `/cobblecap` tree is mostly level 2 (operator), with four exceptions that any player
can run: the bare root, `status` without a target, `hud …`, `legends …` and
`mode classic|ranked`. Brigadier hides a subcommand a player cannot use, so a non-op who
types `/cobblecap ` sees only those; typing a gated one anyway gives the vanilla *Unknown
or incomplete command* error.

This page covers the cap, player and config subcommands. The statue, chrono and champion
subtrees are large enough to have their own page:
[statue, chrono and champion commands](commands-statues.md).

There are no aliases. Nothing is abbreviated.

---

## `/cobblecap`

```mcfunction
/cobblecap
```

No arguments, no permission, **must be run by a player**. Prints the caller's own cap, the
next champion, and any unmet conditions for that champion.

```text
Your level cap: 45
Next champion: Erika
  - Pokédex: 61/80 caught
```

Only two conditions are listed here, and only when they fail: the Pokédex count
(`requiredPokedex`) and the FTB quest (`requiredQuestId`). The scoreboard gate
(`requiredScoreboard`) is enforced at the NPC but **is not shown** by this command — a
player blocked by a scoreboard requirement sees no reason why. Use
[`/cobblecap status <player>`](#cobblecap-status-player) as an admin to see it.

| Message | Cause |
|---|---|
| `This command must be run by a player.` | run from console or a command block |
| `Player %s not found in CobbleCap data.` | no row in `players.json`; normally impossible, since joining creates one |

With every champion beaten, the second line becomes `All champions defeated!`.

## `/cobblecap status`

```mcfunction
/cobblecap status
/cobblecap status <player>
```

| Argument | Type | Required | Permission |
|---|---|---|---|
| *(none)* | — | — | any player — identical to the bare `/cobblecap` |
| `player` | entity selector resolving to one player | yes for this form | **level 2** |

The targeted form is a diagnostic dump, and it is the first thing to run when someone says
they cannot fight a champion. It prints, in order: UUID, system enabled, game mode,
permission level, dimension, position; cap level, XP lock, HUD state, last cap change;
every champion with `✓`/`✗` and `✦` for a beaten challenge; the next champion's full gate
analysis with `OK`/`FAIL` per condition; last challenge timestamps; **every scoreboard
objective** this player has a score on; command tags; scoreboard team; and every granted
`cobblecap:`/`cobblemon:` advancement.

```text
═════ CobbleCap status — Steve ═════
UUID: f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1
System enabled: true
…
─── Champions (4/13 beaten) ───
  ✓ gym_leader_pierre_0395 (Pierre, cap=30)
  ✗ gym_leader_erika_0399 (Erika, cap=50)
─── Next champion gating ───
Next: gym_leader_erika_0399 (Erika)
  cap granted: 50 | allowRefight: true
  scoreboard arkensia_tour >= 12 (current=9) FAIL
  ftb quest: (none)
  pokedex: 61/80 FAIL
═════ end status ═════
```

It is long — expect sixty to a hundred chat lines on a developed server. Run it from the
console or the dashboard terminal rather than in chat if you can.

`Player %s not found in CobbleCap data.` as an error means the target has no stored row.

## `/cobblecap hud`

```mcfunction
/cobblecap hud on
/cobblecap hud off
/cobblecap hud position <corner>
```

No permission, must be a player. The preference is stored per-player in `players.json`
and pushed to the client immediately through `CapSyncPayload`, so the change is visible
without a relog.

| Argument | Type | Accepted values | Stored as |
|---|---|---|---|
| `corner` | single word, tab-completed | `bottom-right`, `bottom-left`, `top-right`, `top-left` | `0`, `1`, `2`, `3` |

```mcfunction
/cobblecap hud position top-left
```

```text
HUD moved to top-left.
```

| Message | Cause |
|---|---|
| `HUD is already visible.` / `HUD is already hidden.` | the toggle was already in that state; nothing written |
| `Unknown position: <x>. Use: bottom-right, bottom-left, top-right, top-left` | the word did not match; tab completion suggests the four but does not enforce them |
| `This command must be run by a player.` | not a player source |

The HUD itself is described in [the client side](client.md).

## `/cobblecap set <player> <level>`

```mcfunction
/cobblecap set <player> <level>
```

**Level 2.**

| Argument | Type | Required | Range |
|---|---|---|---|
| `player` | entity selector resolving to one player | yes | must be **online** |
| `level` | integer | yes | 1–100, enforced by Brigadier |

Creates the player's row if missing, writes the cap, logs
`[CobbleCap] <source> set cap for <name> to <level>`, and syncs the client.

```mcfunction
/cobblecap set Steve 60
```

```text
Set Steve's cap to 60.
```

The feedback is broadcast to other operators (`sendFeedback(…, true)`), as it is for every
`/cobblecap` subcommand that uses the internal `reply` helper.

⚠️ This sets only the number. `championsBeaten` is untouched, so the champion-ordering gate
still thinks the player is at their old progress point: raising a cap by hand lets their
Pokémon level up but does **not** let them fight the next champion. That mismatch is exactly
the desync `addbeaten` exists to repair, and `CobblemonHook` logs a warning naming the fix
when it sees it.

Out-of-range values are rejected by Brigadier before the handler runs, with the vanilla
*Integer must be between 1 and 100* error.

## `/cobblecap get <player>`

```mcfunction
/cobblecap get <player>
```

**Level 2.** Reads one player's cap.

```text
Steve's current cap: 60.
```

`Player Steve not found in CobbleCap data.` if the row does not exist.

## `/cobblecap reset <player>`

```mcfunction
/cobblecap reset <player>
```

**Level 2.** A full progression wipe for one player, not only a cap reset. In order:

1. cap set back to `startingCap` from `config.json`
2. `championsBeaten` cleared
3. `challengeBeaten` cleared
4. `lastChallengeTimes` cleared
5. `expLocked` set to `false`
6. for **every** champion in `champions.json`, four vanilla commands dispatched silently
   through the server command source:
   - `advancement revoke <player> only cobblecap:champion/<key>`
   - `advancement revoke <player> only cobblecap:challenge/<key>`
   - `ftbquests change_progress <player> reset <victoryQuestId>` when set
   - `ftbquests change_progress <player> reset <challengeQuestId>` when set

```mcfunction
/cobblecap reset Steve
```

```text
Reset Steve's cap to 20 (starting cap).
```

The advancement `<key>` is derived from the champion's **display name**, lower-cased with
spaces turned into underscores — not from its `id`. So `displayName: "Major Bob"` resolves
to `cobblecap:champion/major_bob`. If you rename a champion in `champions.json`, the revoke
silently stops matching and stale advancements survive the reset, which keeps rctmod NPC
dialogue in post-victory mode. The dispatches are each wrapped in a bare `catch (Exception
ignored)`, so a missing advancement or an absent FTB Quests install fails quietly and the
command still reports success.

There is no confirmation step and no undo.

## `/cobblecap addbeaten <player> <championId>`

```mcfunction
/cobblecap addbeaten <player> <championId>
```

**Level 2.** Repair tool. Marks a champion as beaten and raises the cap to that champion's
`capGranted` if the cap is currently lower.

| Argument | Type | Required | Notes |
|---|---|---|---|
| `player` | entity selector resolving to one player | yes | online |
| `championId` | **quoted** string | yes | tab-completes from the loaded champion ids; must exist |

The argument is `StringArgumentType.string()`, the quoted variant. Tab completion inserts
the id for you; typing it by hand, quote it if it contains anything Brigadier would not
accept bare.

```mcfunction
/cobblecap addbeaten Steve "gym_leader_pierre_0395"
```

```text
Added 'gym_leader_pierre_0395' to Steve's beaten list — cap raised to 30.
```

The trailing clause varies: ` (already present)` when the id was already in the list, ` —
cap raised to N` when `raiseCapLevel` returned true, and nothing at all when the id was new
but the cap was already at or above `capGranted`.

| Message | Cause |
|---|---|
| `Unknown champion id: <id>` | not in `champions.json` — returns 0, nothing written |

Use this after a `reset` + `set` pair, or when a `BATTLE_VICTORY` did not persist. The
warning that tells you to run it looks like this in the server log:

```text
[CobbleCap] Order-check rejection may be a desync: player=Steve cap=50 tried=gym_leader_erika_0399 expectedNext=gym_leader_ondine_0396 championsBeaten=[…]. If the player legitimately beat prior champions, repair with: /cobblecap addbeaten Steve gym_leader_ondine_0396
```

## `/cobblecap removebeaten <player> <championId>`

```mcfunction
/cobblecap removebeaten <player> <championId>
```

**Level 2.** The inverse, with one asymmetry worth knowing: it removes the id from
**both** `championsBeaten` and `challengeBeaten`, and it does **not** validate the id
against `champions.json`. An id that exists in neither list is accepted and does nothing.
The cap is not lowered.

```text
Removed 'gym_leader_pierre_0395' from Steve's beaten list.
```

Advancements and FTB quests are not revoked by this command — only `reset` does that. So
after a `removebeaten` the NPC may still greet the player in post-victory mode.

## `/cobblecap reload`

```mcfunction
/cobblecap reload
```

**Level 2.** Re-reads `config/cobblecap/config.json` and `config/cobblecap/champions.json`
from disk, pushes the webhook URL into `CobbleCapWebhook`, then syncs every online client
so the HUD's *Next* line reflects a changed champion list.

```text
Champion config reloaded (13 champions loaded).
```

What `reload` does **not** pick up: `startingCap` is read fresh into `CapConfig`, so new
joiners get the new value, but existing rows keep theirs. Player data (`players.json`) is
not re-read — it is the authority in memory, and reloading would lose unsaved changes.

A malformed JSON file logs `[CobbleCap] Failed to load champions.json: …` and leaves the
in-memory list as it was. Check the server log after every edit; the chat line reports a
count, and a count of `0` after editing a file with thirteen champions in it means the
parse failed.

## `/cobblecap enable` · `/cobblecap disable`

```mcfunction
/cobblecap enable
/cobblecap disable
```

**Level 2.** Flip `CapConfig.enabled` and save `config.json` immediately. Every online
client is re-synced so the HUD can grey out.

```text
Level cap system enabled.
Level cap system disabled.
```

Already in that state, you get `Level cap system is already enabled.` /
`… already disabled.` and nothing is written.

What turning it off actually stops is listed in
[the level cap](level-cap.md#what-enabled-false-turns-off) — notably it also disables
banned-species checks and champion victory detection, not only the XP cap.

## `/cobblecap legends`

```mcfunction
/cobblecap legends
/cobblecap legends by_time
```

No permission. Lists the Hall of Fame from `config/cobblecap_legends.json`. Test entries
are excluded.

`legends` lists them in the order they were crowned, with a `dd/MM/yyyy` date:

```text
─── Pokémon League Masters (3) ───
🥇 Steve — 14/05/2026 (first ever Master)
2. Alex — 02/06/2026
3. Herobrine — 19/06/2026
```

`legends by_time` instead drops anyone with no recorded time (`bestTimeMs <= 0`) and sorts
ascending by their best Conseil 4 run:

```text
─── Pokémon League Masters by time (2) ───
1. §6Alex§r — 18m42s
2. §6Steve§r — 24m05s
```

With nobody crowned: `No Pokémon League Master crowned yet. Be the first!`

Both the Hall of Fame and the chronos are described in
[the champion system](champions.md#the-hall-of-fame).

## `/cobblecap mode`

```mcfunction
/cobblecap mode classic
/cobblecap mode ranked
/cobblecap mode set <player> classic
/cobblecap mode set <player> ranked
```

The first two forms are self-targeted and need **no permission** — they exist to be wired
to Alfred's dialogue buttons in the Conseil 4 lobby. The `set` form takes a target and
needs **level 2**.

Each invocation does two things:

1. Writes `1` (classic) or `2` (ranked) to the player's score on the `cobblecap_mode`
   vanilla scoreboard objective, creating the objective with
   `scoreboard objectives add cobblecap_mode dummy` first if it is somehow missing.
2. Calls `RegisModeManager.applyMode`, which swaps the **live Régis trainer JSON** to the
   matching variant and dispatches an rctmod reload.

```text
Conseil 4 mode → ranked for Steve (JSON swapped + reload dispatched)
```

or, when the requested variant was already the live one:

```text
Conseil 4 mode → classic for Steve (already live)
```

⚠️ The JSON swap is **server-global**, while the scoreboard score is per-player. Two
players picking different modes at the same time fight whichever variant was written last.
This is a real limitation of the design, not a bug to be worked around from the command
side; see [champion morphing](champion-morphing.md#the-régis-mode-swap-is-global).

`This command must target a player` as an error means the self-targeted form was run from
a non-player source.

The scoreboard write failing is non-fatal: it logs
`[CobbleCap] cobblecap_mode score set failed: …` and the JSON swap still happens.

---

## `/explock`

```mcfunction
/explock
/explock <player>
```

| Form | Permission | Effect |
|---|---|---|
| `/explock` | none, must be a player | toggles the caller's own XP lock |
| `/explock <player>` | **level 2** | toggles that player's XP lock |

It is a toggle, not a setter — there is no `on`/`off` form, so the only way to know the
resulting state is the message, or `/cobblecap status <player>`.

The player whose lock changed always gets the message, even when an admin flipped it for
them:

```text
🔒 XP lock enabled — your Pokémon can no longer gain experience.
🔓 XP lock disabled — your Pokémon can gain experience again.
```

And when an admin targeted someone else, the admin additionally sees, broadcast to ops:

```text
XP lock for Steve: ON
```

| Message | Cause |
|---|---|
| `This command must be run by a player.` | self-form from a non-player source |
| `Player data not found.` | `ensureExists` ran but the row is still missing — an internal fault, report it |

An XP lock is honoured regardless of `enabled`, and takes priority over the cap in all
three enforcement layers. See
[the XP lock](level-cap.md#the-xp-lock-is-a-separate-switch).

---

## `/pasturehide`

```mcfunction
/pasturehide
/pasturehide on
/pasturehide off
/pasturehide toggle
/pasturehide status
```

**Client command.** No permission of any kind — it is the player's own renderer. The bare
form and `toggle` are identical.

The setting hides Cobblemon Pokémon that are tethered to a pasture block, for this client
only, and is persisted in `config/cobblecap_client.properties`:

```properties
#CobbleCap client preferences
pasture_pokemon_hidden=true
```

```text
🙈 Pasture Pokémon hidden (client-only)
👁 Pasture Pokémon visible again
Pasture Pokémon hidden: ON
```

No failure messages — nothing it does can fail at the command level. If the toggle appears
to do nothing, the cause is upstream of the command: the client has no pasture snapshot
from the server. [The client side](client.md#pasturehide) explains why the server has to
send that list and what to check.

---

## Driving the mod without commands

The cap, the champion list, the config and the fireworks test are all reachable over REST
under `/api/v1/cobblecap`. See [the dashboard API](dashboard.md). The statue, chrono,
morph and scoreboard subsystems are **command-only** — the dashboard does not expose them.

---

← [The champion system](champions.md) · [Statue, chrono and champion commands](commands-statues.md) →
