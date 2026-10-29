[← CobblemonDungeons](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

Two command trees, registered in `CobblemonDungeons.onInitialize` and
`DungeonsPartyCommands.register`:

- `/dungeons`, with `/dungeon` as a byte-for-byte identical alias
- `/DungeonsParty`

There is deliberately no admin command for creating or editing dungeons. Authoring is done
in the config files or from [the dashboard](dashboard.md); players use the GUI. That is
also why nothing here starts a dungeon directly — starting a run goes through the
selection screen, which calls `DungeonManager.startDungeon` for you.

⚠️ `/DungeonsParty` is registered with that exact capitalisation and Brigadier literals
are case-sensitive. `/dungeonsparty` is not a command. Tab completion from `/D` will
produce the right form.

---

## `/dungeons` · `/dungeon`

Opens the dungeon selection GUI. Permission: none — any player.

```mcfunction
/dungeons
```

The two names are separate registrations with identical subtrees, not an alias in the
Brigadier sense. Everything below applies to both.

**What it does.** Checks the server role, checks the player has at least one Pokémon, then
opens `DungeonSelectionGui`. From there the player picks a dungeon, optionally a tier
(buttons for Tier 1 to Tier 5), and confirms, which is what actually calls
`startDungeon`.

**Required state.** The command is refused on a dungeon-role server — see
[server roles](instances.md#cross-server-runs). It needs a player source: run from console
it fails with Brigadier's standard "requires a player" error.

**Failure messages.**

| Message | Cause |
|---|---|
| *This command is only available on the main server. Use /dungeons leave to exit the dungeon.* | The server's role is `dungeon` |
| *You need one or more pokemon on your team to enter dungeons* | The player's Cobblemon party is empty |
| *An internal error occurred: <message>* | Any exception while opening the GUI; the stack trace goes to the log as `Error executing /dungeons command` |

### `/dungeons leave`

Leaves the dungeon the player is currently in. Permission: none — any player.

```mcfunction
/dungeons leave
```

This is the only subcommand with no server-role restriction, by design: it is what a
player on the dungeon server uses to get back out.

**Side effects.** The full leave path from [the instance lifecycle](instances.md#leaving):
the HUD is hidden, and either a teleport to `returnPos` (`SAME_SERVER`) or a
`DungeonJoinPayload` plus a transfer to `mainServer` after 40 ticks (`CROSS_SERVER`). If
the boss is not dead, progress and items from inside the dungeon are **not** saved, and the
player is told so. When the last member leaves, the dungeon's entities are cleaned up.

**Example.**

```mcfunction
/dungeons leave
```

```text
You left or failed the dungeon. Your items and progress were NOT saved.
```

**Failure messages.**

| Message | Cause |
|---|---|
| *You are not currently in a dungeon instance.* | No instance registered for this player |

Exceptions are caught and logged as `Error executing /dungeons leave command` with nothing
shown to the player.

### `/dungeons sql test`

Reports the state of the MariaDB connection. Permission level **2**. Refused on a
dungeon-role server.

```mcfunction
/dungeons sql test
```

**What it does.** Reads `config.json`, then either reports that SQL is off, or takes a
connection from the HikariCP pool and validates it with a 5-second timeout. Nothing is
written.

**Examples.** With SQL disabled:

```text
SQL is disabled in config.json
```

Working:

```text
SQL Connection is active and healthy! (Table prefix: cobblemon_dungeons_)
```

Enabled but broken — the error is whatever the pool reported at startup:

```text
SQL is enabled but the connection failed. Error: Socket fail to connect to localhost:3306
```

The first three lines are coloured by `§`-codes in the Java source rather than by a lang
file, so they are English on a French client too.

**What a player without permission sees.** Nothing: Brigadier hides nodes whose `requires`
fails, so `/dungeons sql` does not appear in completion and typing it gives the generic
*Unknown or incomplete command* error.

### `/dungeons partydebug <on|off|dump|status|ping>`

Diagnostics for cross-server party synchronisation. Permission level **2** on the whole
subtree. Refused on a dungeon-role server.

```mcfunction
/dungeons partydebug on
/dungeons partydebug off
/dungeons partydebug dump
/dungeons partydebug status
/dungeons partydebug ping <server>
```

| Subcommand | Source | What it does | Feedback |
|---|---|---|---|
| `on` | any | Turns on verbose logging in `PartySyncHandler` | *Party sync debug enabled.* |
| `off` | any | Turns it off | *Party sync debug disabled.* |
| `dump` | player | Prints the player's serialised Cobblemon party to chat and to the log | *Party sync debug dumped to log and chat.* |
| `status` | player | Prints the current sync state to chat | none — the output *is* the response |
| `ping <server>` | player | Sends a `PartyDebugPayload` through the proxy to the named server | *Ping of sync debug sent to <server>.* |

`dump`, `status` and `ping` need a player source and fail from console.

**`ping` argument.**

| Name | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `server` | Brigadier `string` (quotable word) | yes | Any proxy server name | — |

No completions are provided — the mod has no list of proxy servers. A name with a space
must be quoted: `/dungeons partydebug ping "dungeon 1"`.

A ping only produces a reply when the Velocity forwarder is installed and the named server
is reachable. Silence is the normal failure mode, and there is no timeout message.

```mcfunction
/dungeons partydebug ping dungeonsraids
```

```text
Ping of sync debug sent to dungeonsraids.
```

---

## `/DungeonsParty`

Party management for dungeon runs. These parties are the mod's own grouping, unrelated to
a Cobblemon party of Pokémon. Permission: none — any player. The **whole tree** is refused
on a dungeon-role server, including `leave`, so parties are managed on the main server
only.

```mcfunction
/DungeonsParty
```

With no argument: creates a party if the player has none, then opens the party GUI. If one
already exists it opens the GUI.

**Failure messages.**

| Message | Cause |
|---|---|
| *You need one or more pokemon on your team to enter dungeons* | Empty Cobblemon party — checked by every subcommand below as well |

On creation: *Party created!*

### `/DungeonsParty create`

Creates a party without opening the GUI.

```mcfunction
/DungeonsParty create
```

| Message | Cause |
|---|---|
| *Party created!* | Success |
| *You are already in a Party!* | The player is in a party |
| *You need one or more pokemon on your team to enter dungeons* | Empty Cobblemon party |

Creation can also fail silently: if `createParty` returns null, no message is
sent at all.

### `/DungeonsParty invite <player>`

Invites a player to your party.

| Name | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `player` | Brigadier entity selector, player form | yes | A single online player — a name, `@p`, `@r`, or a selector resolving to exactly one player | — |

Completion offers online player names. A selector matching zero or several players is
rejected by Brigadier before the command runs.

```mcfunction
/DungeonsParty invite Alex
```

```text
Invite sent to Alex
```

The invited player receives a clickable chat message: *<inviter> invited you to their
DungeonsParty!* with the hover text *Click to join <inviter>'s DungeonsParty*.

**Side effects.** An invite is stored pending on the target. `invitePlayer` can decline for
reasons of its own — a full party, for instance, which sends *Your Party is full!* — and in
that case no *Invite sent* feedback is printed.

### `/DungeonsParty join [inviter]`

Accepts an invite.

| Name | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| `inviter` | Brigadier entity selector, player form | no | A single online player | — |

With no argument, the player's pending invites are consumed in whatever order the manager
holds them. With an `inviter`, only an invite from that specific player is accepted.

```mcfunction
/DungeonsParty join
```

```text
Joined the Party!
```

| Message | Cause |
|---|---|
| *Joined the Party!* | Success, no argument |
| *Joined <inviter>'s Party!* | Success, with an argument |
| *No pending invites.* | No argument and nothing pending |
| *No invite from <player>.* | With an argument, and no invite from that player |
| *Party is full* / *Failed to join Party.* / *Failed: the Party may have been disbanded.* | Emitted by `DungeonsPartyManager` depending on why the join was refused |

### `/DungeonsParty leave`

Leaves the party.

```mcfunction
/DungeonsParty leave
```

The command itself prints nothing; the manager does. The leaver sees *You have left the
Party.* The remaining members see *<player> has left the Party.*, and if the leaver was the
leader the party is dissolved with *The Party has been disbanded because the leader left.*

There is no confirmation and no failure message for a player who is in no party.

---

## What is in the GUI and not in a command

The selection screen carries actions with no command equivalent:

- picking a dungeon and starting it, at a chosen tier
- the "searching for group" state, which advertises you to other players
- the ticket exchange — all four conversions, documented in [tickets](tickets.md)
- the boss-fight prompt shown when you step into an activated portal

Admin actions with no command equivalent — config CRUD, reload, force-fail, force-leave —
live in [the dashboard](dashboard.md).

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
