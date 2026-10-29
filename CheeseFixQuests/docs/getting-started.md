[← CheeseFixQuests](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this walkthrough two players will be in the same FTB Teams party, one of them
will complete a quest, and the other one's quest book will still show that quest as
incomplete. That is the whole point of the mod, and it is a thing you can watch happen in
about ten minutes.

You need a Fabric 1.21.1 server with FTB Quests and FTB Teams, and two accounts — the
effect is invisible with one player, because a single player is always alone on their own
team. A second Minecraft instance or a friend both work.

---

## 1. Confirm the stock behaviour first

Do this before installing anything. It takes two minutes and it is what makes the rest
convincing.

Start the server without CheeseFixQuests. Join with both accounts and put them in a party:

```mcfunction
/ftbteams party create TestParty
/ftbteams party invite <SecondPlayer>
```

and on the second account:

```mcfunction
/ftbteams party join TestParty
```

Now have the first player complete any quest with an item task — pick up the item it asks
for. Open the quest book on the **second** account.

The quest is complete there too, and its reward is waiting to be claimed. Nobody on the
second account did anything. That is FTB Quests working exactly as designed, and it is the
behaviour you are about to change.

Leave the party on both accounts (`/ftbteams party leave`) and stop the server.

---

## 2. Back up the quest data

```text
world/ftbquests/
config/ftbquests/
```

Copy both somewhere safe. The mod runs a one-shot migration that rewrites quest progress
the first time a player logs in, and this backup is the only way back from it. On a brand
new world there is nothing to lose, but get into the habit here rather than on your live
server.

---

## 3. Install the jar

Drop `cheesefixquests-1.0.1+1.21.1.jar` in the server's `mods/`, next to FTB Quests and FTB
Teams. Copy the same jar to both clients' `mods/` as well — the client-side mixin is what
keeps the quest book's edit mode working for operators, and the
[installation page](installation.md#which-side) explains why.

Start the server.

---

## 4. Check the log

Within the first few seconds of startup:

```text
[CheeseFixQuests] Individual quest progress mode ENABLED
[CheeseFixQuests] Migration pending — will run on first player login
[CheeseFixQuests] Boot complete — finalizer=true claim-lock=true audit=false
```

`ENABLED` is the line that matters. If it says `DISABLED`, stop here: something has written
`"forceIndividualProgress": false` into `config/cheesefixquests.json` and the rest of this
walkthrough will do nothing. See
[configuration](configuration.md#forceindividualprogress).

If you see no `[CheeseFixQuests]` line at all, go to
[the mod does not appear in the log at all](troubleshooting.md#the-mod-does-not-appear-in-the-log-at-all).

---

## 5. Log in, and watch the migration run

Join with the first account. The migration fires inside that login, before the quest book
data is sent:

```text
[CheeseFixQuests] Processing team 8f3c1d20-… [PLAYER] — 1 reward UUID(s), 1 total player(s), filter=true
[CheeseFixQuests]   8f3c1d20-… — 0 new entries [FILTERED (0 quest IDs — only copying claimedRewards)]
[CheeseFixQuests] No new data to migrate
[CheeseFixQuests] Auto-migration complete: 0 player(s) migrated
```

On the test world you just made, zero is the right answer — there was nothing to move. On a
server with history you will see real counters, and you should read
[the migration](migration.md) to understand what it decided to copy and what it left
behind.

The flag is now set, so this will not happen again:

```mcfunction
/cheesefixquests status
```

```text
§e[CheeseFixQuests] forceIndividual=true migrationDone=true debug=false
```

That command needs **permission level 4**, not the usual operator level 2. From the server
console it always works.

---

## 6. Re-form the party

Both accounts, same as step 1:

```mcfunction
/ftbteams party create TestParty
/ftbteams party invite <SecondPlayer>
```

```mcfunction
/ftbteams party join TestParty
```

Nothing visible changes in the quest book. Under the hood, something did: joining a party
normally makes FTB Quests merge the joiner's progress into the party's, and
`TeamDataMixin` has just cancelled that merge. With `debugLogging` on you would see:

```text
[CheeseFixQuests] Blocked mergeData — individual progress mode active
```

---

## 7. Complete a quest on one account

Pick the simplest item task you have and have the **first** player satisfy it.

Within ten seconds, the finalizer sweep will have noticed — and if the item arrived through
a path FTB's own listener does not watch, such as a shift-click out of a chest, that sweep
is the only thing that will notice at all. Either way the first player's quest completes
and its reward becomes claimable.

The first time this mixin fires for a player, the log says so once:

```text
[CheeseFixQuests] InventoryListener.detect: redirected to PlayerTeam for <FirstPlayer> (first redirect for this player; total redirects since startup: 1)
```

That line is the proof that item-task progress is being written to the player's own
storage rather than the party's.

---

## 8. Open the book on the other account

**The quest is still incomplete, with an empty progress bar.** No reward waiting.

That is the result. The two accounts are in one party, sharing claims and chunk loading and
whatever else you use parties for, and their quest books have nothing to do with each
other.

Have the second player complete the same quest to confirm it works for them independently:
their bar fills, their reward appears, and the first player's book is unaffected.

---

## 9. Leave the party, and check nothing was lost

```mcfunction
/ftbteams party leave
```

Both books stay exactly as they were. FTB normally merges claimed rewards back out on
party leave, and `TeamDataMixin` cancels that too, so neither player inherits the other's
claims.

---

## Where to go next

- A player reports a quest stuck with a full bar, or a reward handed out twice:
  [the finalizer sweep](finalizer.md) is the subsystem that deals with both, and
  `auditQuestEvents` is how you get evidence.
- A quest chapter on a daily timer that stopped resetting before you installed the mod
  needs [`/cheesefixquests resetchapter`](commands.md#cheesefixquests-resetchapter).
- Installing on a server with years of party history: read
  [the migration](migration.md) in full before the first login, not after.
- Something behaves differently from the above: [troubleshooting](troubleshooting.md).

---

← [Installation](installation.md) · [Commands](commands.md) →
