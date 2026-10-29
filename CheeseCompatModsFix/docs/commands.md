[← CheeseCompatModsFix](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](commands.fr.md)

# Commands

This mod adds exactly one command, and modifies one that belongs to another mod. Both
are in `CheeseCompatMod` — there is no `commands` package.

---

## `/stopbattle`

Force-ends the caller's own Cobblemon battle.

```mcfunction
/stopbattle
```

### Arguments

None. The no-argument form is the only one this mod registers.

| Argument | Type | Required | Accepted values | Default |
|---|---|---|---|---|
| — | — | — | — | — |

### Permission

**None required.** The command is registered with no `requires(...)` clause, so any
player can run it. That is deliberate: a player with a frozen battle needs to be able to
free themselves without an admin.

It does require a **player** source. `context.getSource().getPlayerOrThrow()` is the
first thing the handler calls, so running it from the server console or from a command
block fails with Brigadier's standard error:

```text
No player was found
```

### What it does

The handler reads the caller's name and then runs, as the **server** command source:

```mcfunction
stopbattle <caller's name>
```

That targeted form belongs to Battle Tower, not to this mod. In other words,
`/stopbattle` with no arguments is a self-service wrapper that calls the mod's own
`/stopbattle <player>` on your behalf, with server-level permission, so the permission
restriction below does not block you from stopping your own battle.

The caller then sees, in green:

```text
Battle stop requested.
```

That string is `Text.literal`, not a translation key, so it is in English regardless of
the client's language.

### Side effects

- One line in the server log, at `INFO`:

  ```text
  [CheeseCompat] <name> used /stopbattle on themselves
  ```

- Whatever Battle Tower's `/stopbattle <player>` does, which this mod does not control.
- Nothing is written to disk, and no event is broadcast.

### Example

A player whose battle has frozen types:

```mcfunction
/stopbattle
```

and sees:

```text
Battle stop requested.
```

The battle ends a tick or two later, when the targeted command has run. Note the
wording: *requested*. The message is sent immediately and unconditionally, **before**
the result of the inner command is known. If Battle Tower's handler finds no battle to
stop, the player still gets "Battle stop requested." and nothing happens. There is no
feedback path for that case.

### Failure messages

| Message | Cause |
|---|---|
| `No player was found` | run from the console or a command block instead of by a player |
| `Unknown command...` | Battle Tower is not installed, so there is no `stopbattle` tree for this mod to hang its no-argument form on in a useful way — the literal registers, but the inner targeted call has nothing to execute |
| *(silence, battle continues)* | the inner `/stopbattle <name>` found no battle for that player |

### Completions

None. There are no arguments to complete.

---

## `/stopbattle <player>` — restricted, not added

This mod does **not** register the targeted form. Battle Tower does. What this mod does
is take it away from non-operators.

### Why

Without the restriction, any player could end any other player's battle by name. On a
server where a Battle Tower run locks a party at forced levels, that is not a prank —
it is a way to corrupt someone else's party state from across the map.

### How

`restrictStopBattleArgs()` runs on `ServerLifecycleEvents.SERVER_STARTED`, after every
mod has registered its commands. It cannot use the normal Brigadier builder, because the
node already exists and belongs to another mod, so it edits the live command tree by
reflection:

1. Find the `stopbattle` node under the dispatcher root. If it is absent — Battle Tower
   not installed — return and do nothing.
2. Reach the private `children` and `arguments` maps on `CommandNode` by reflection.
3. For every child that is an `ArgumentCommandNode`, build a replacement with
   `RequiredArgumentBuilder`, carrying over the name, the argument type, the `executes`
   handler and the custom suggestion provider, and adding
   `.requires(src -> src.hasPermissionLevel(2))`. Re-attach the original node's
   children to the replacement, then put it back into both maps.

Only argument children are replaced. The literal `stopbattle` node itself, and the
no-argument form registered above, are untouched.

### Permission after the change

**Level 2** (the usual "op" level for commands) for `/stopbattle <player>`. A player
below level 2 does not see the argument form in their command tree at all, so to them
the targeted syntax simply does not exist. Brigadier reports it the way it reports any
unknown input:

```text
Incorrect argument for command
```

They can still run the bare `/stopbattle`.

### If it fails

The whole method is wrapped in a try/catch. A failure — a Brigadier version where those
field names changed, a security manager, anything — produces one warning and leaves the
tree as it was:

```text
[CheeseCompat] Could not restrict /stopbattle <player>: <message>
```

Success produces:

```text
[CheeseCompat] Restricted /stopbattle <player> to operators
```

⚠️ If you see neither line, nothing ran: either the `stopbattle` node was absent (Battle
Tower not installed, which is fine) or the server never reached `SERVER_STARTED`. The
dangerous case is the warning, because it means **the targeted command is still open to
every player** while the rest of the mod works normally.

### A note on reflecting into Brigadier

This is the only place in the mod that mutates another library's private data structures
rather than another *mod's* code, and it is worth being explicit about why it was
acceptable: there is no API for retro-fitting a permission check onto a command node
registered by someone else. The alternatives were a mixin on Battle Tower's command
registration (fragile in a different way, and version-pinned) or asking Battle Tower
upstream to add the check. The reflection is contained to one method, fails soft, and
logs.

---

← [Getting started](getting-started.md) · [Configuration](configuration.md) →
