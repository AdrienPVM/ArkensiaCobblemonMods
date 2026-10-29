[← CheeseItems](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

## The server will not start

**Cause.** Most likely a missing CheeseDash. `cheesedash` is a hard `depends`, and the
loader stops with a dependency error naming it before any Minecraft code runs. Second most
likely: two copies of `cheeseitems-*.jar` in `mods/`, left over from an upgrade.

**Fix.** Put the CheeseDash jar in `mods/`, built from the same repository checkout. Keep
one CheeseItems jar. Both jars must be the non-`-sources` ones.

## <a id="the-cheeseitem-command-does-not-exist"></a>The `/cheeseitem` command does not exist

**Cause.** Either the mod is not loaded, or you are below permission level 2. Brigadier
hides commands you cannot run, so an unpermitted player gets exactly the same *Unknown or
incomplete command* as a player on a server without the mod.

**Fix.** Check the log for `[CheeseItems] Initialized successfully!`. If it is there, the
mod is loaded and the problem is permissions — `/op <name>`, or run the command from the
console or a command block. `/morpho` is the control test: it needs no permission, so if
`/morpho` works and `/cheeseitem` does not, it is permissions.

## `/cheeseitem list` says no items but the file has items

**Cause.** The config failed to parse. A single trailing comma or a missing bracket is
enough. (`//` comments are not the culprit: `JsonParser.parseString` reads leniently and
accepts them.)

**Fix.** Look for the line just above the startup banner:

```text
[CheeseItems] Failed to load config: …
```

⚠️ Act before touching the dashboard. The mod keeps an empty item list after a failed
parse, and the next write — any create, update or delete — saves that empty list **over
your file**. Copy `config/cheeseitems.json` somewhere safe, fix the JSON, then
`/cheeseitem reload`.

## `give` reports success but the player gets nothing

**Cause.** `baseItem` is not a registered item id. `Registries.ITEM.get` returns
`minecraft:air` for an unknown identifier, the mod builds a stack of air, and the command
reports success because nothing threw.

**Fix.** Check the id in `/cheeseitem list` — the base item is printed in parentheses.
Typos to look for: a missing `minecraft:` namespace, a plural (`minecraft:papers`), or an
item from a mod that is not installed. The mod never validates this field.

A second, rarer cause: the player's inventory was full, in which case the stack was dropped
at their feet rather than lost.

## Right-clicking a custom item does nothing

Four causes, in the order worth checking:

| Cause | How to tell |
|---|---|
| `enabled: false` | `/cheeseitem list` shows `[OFF]` |
| The item has no `useActions` and no `onUseEffects` | nothing is configured to happen |
| A condition failed | the player sees `Conditions not met.` in the actionbar |
| A cooldown is running | the player sees `Cooldown: 7.3s` |

**Fix for the condition case.** The message never says *which* condition failed, which is
deliberate for quest items and unhelpful for you. Work through them: the player must be in
`dimension`, at or above `minLevel` **experience levels**, holding `requiredHeldItem` in the
**off hand**, and pass `permission`.

⚠️ The permission check is the one that surprises people. Operators at level 2 pass without
the node being read. Everyone else goes through a reflective LuckPerms lookup, and **any
failure of that lookup, including LuckPerms not being installed, counts as denied**. On a
server without LuckPerms, a `permission` condition makes the item operator-only.
[Details](items.md#useconditions).

## The item is used in the off hand and nothing happens

**Cause.** By design. `UseItemCallback` is filtered to `Hand.MAIN_HAND`; a use from the off
hand passes straight through.

**Fix.** None needed — put the item in the main hand. `passiveEffects` *do* work
from either hand.

## A whole stack vanished after one use

**Cause.** `maxUses` with a stack larger than one. When the counter reaches zero the handler
empties the entire stack, not one item.

**Fix.** Give `maxUses` items with `maxStackSize: 1` so they cannot stack in the first
place. [Why](items.md#uses-and-cooldowns).

## Changing an item did not update the copies players hold

**Cause.** Depends on what you changed. Behaviour — actions, conditions, effects, flags —
is read live and needs no refresh. Appearance is baked onto the stack and only updated by a
refresh pass, which walks **online players' inventories only**.

**Fix.** `/cheeseitem reload`, with the player online. Stacks in chests, ender chests,
shulker boxes, item frames and on the ground are never refreshed. If you renamed the `id`
or deleted the definition, those stacks are orphaned permanently; recreating the definition
under the exact same `id` brings them back.
[The full table](items.md#hot-reload-and-items-already-in-inventories).

## A soulbound item disappeared on death

**Cause.** This is a bug in the mod, not a configuration mistake. Soulbound stacks are
removed from the inventory before death and stashed in memory, and the method that restores
them after respawn is never called.

**Fix.** Do not use `soulbound: true`. There is no recovery for an item already lost — the
stash is memory-only and gone at the next restart. Re-issue the item with
`/cheeseitem give`. [Details](items.md#flags-that-do-nothing).

## <a id="custom-icons-do-not-show-up"></a>Custom icons do not show up

Work down this list:

1. **Is `resourcePackHost` set to an address the client can reach?** The default resolves to
   `localhost`, which every remote client resolves to itself. The log says
   `resourcePackHost auto-resolved to localhost:44311` when you have not set it.
2. **Did the player accept the pack?** It is sent with `required = false`, so declining is
   silent.
3. **Is the pack non-empty?** `[CheeseItems] Resource pack cleared (no custom icons, no bar
   images)` means there is nothing to send.
4. **Can you download it yourself?**
   `curl -I http://<resourcePackHost>/cheeseitems/api/pack.zip` should answer `200`.
5. **Is another resource pack overriding the base item's model?** The generated pack
   replaces `assets/<ns>/models/item/<base item>.json` wholesale, and a pack loaded above
   it wins. [Why](resource-pack.md#how-an-icon-is-selected).
6. **Was the icon uploaded after the item existed?** The upload route answers `404` if the
   definition is not there yet.

A client-side symptom worth knowing: if the pack's SHA-1 changes while a client holds an
older copy under the same UUID, the client can corrupt its `options.txt` pack list. The mod
sends a removal packet before every send to avoid it, so if you see it, check that the
removal is happening — it is logged only at `DEBUG`.

## The endurance bar is a row of boxes

**Cause.** The bar font is in the pack but the glyphs are not reaching the client, usually
because another pack ships a full `assets/minecraft/font/default.json` and loads later.

**Fix.** Reorder the packs on the client, or remove the bar PNGs from
`config/cheeseitems_assets/bar/` to fall back to the `◆`/`◇` text bar. The fallback is
chosen by whether the eleven PNGs exist **on the server**, not by what the client can
render. [Details](resource-pack.md#the-endurance-bar-font).

Half-installed artwork gives you the text bar with no warning: `hasBarImages()` requires all
eleven files, `0.png` through `10.png`.

## Pressing H says *No active morpho selected*

**Cause.** The player owns morphos but none is marked active, which happens after the
active one was deleted from the catalog.

**Fix.** Open the Codex with `J` and click a card. The first morpho a player ever receives
becomes active automatically; after a deletion, nothing re-picks one.

## Pressing H transforms nothing

**Cause.** CobbleShift is absent, or `cshift` rejected the species. The mod runs
`cshift transform <player> <species> [form] [shiny]` through the command dispatcher and has
no way to check the result, so it tells the player *Transformation: Ditto* either way.

**Fix.** Look in the server log, around the moment of the press, for the error the
dispatcher sent back to the console — `Unknown or incomplete command, see below for error`
if `cshift` does not exist. (The mod's own `[CheeseItems] cshift command failed: …` warning
only fires on an exception, which the dispatcher never lets through.)

Then run the same command by hand as an operator to see the real error. The usual causes
are a species name CobbleShift does not know, or a `form` value that is not valid for that
species. [Why this is fragile](morphos.md#wearing-a-morpho).

## A player is stuck transformed

**Cause.** One of two things. Either the server restarted while they were transformed — the
transformed map is memory-only, so CheeseItems no longer knows and their next `H` press
transforms them again instead of reverting. Or their `H` press landed in the one tick where
the endurance pool sat at exactly zero, which executes the transform and *then* refuses to
start the drain: nothing reverts them automatically any more.

**Fix.** In the second case, one more `H` press reverts them. In both, `/cshift revert <name>`. Both are known bugs, listed in
[architecture](architecture.md#known-weaknesses).

## A morpho vanished from a player's Codex

**Cause.** Its catalog entry was deleted. Every codex sync prunes entries whose catalog id
no longer exists, and logs one line per removal:

```text
[CheeseItems] Pruned orphan morpho 'a3f91c2e' from Steve's codex (no catalog entry)
```

**Fix.** There is none that gives it back automatically: a re-created entry gets a new id.
Grant the new entry to the affected players, finding them in
`config/cheeseitems_morpho.json` — grep the old id to get the list of UUIDs.

## A hand edit to the morpho catalog was overwritten

**Cause.** `MorphoCatalog.load()` runs once, at startup. The running server holds the
catalog in memory and rewrites the whole file on its next debounced save, discarding your
edit. There is no reload for it, unlike the item config.

**Fix.** Edit the catalog from the dashboard, or stop the server, edit, and start again.

## The dashboard modules are missing from the sidebar

**Cause.** Either the mod did not load, or CheeseDash started before it. The modules are
discovered through the `cheesedash` entrypoint at CheeseDash's startup; nothing registers
them later.

**Fix.** Confirm `[CheeseItems] Initialized successfully!` is in the log, then restart the
server so CheeseDash re-runs discovery. If the module shows but every request answers
`503 {"error":"CheeseItems not ready"}`, the server reference is still null — that is
normal during startup and means something is holding `SERVER_STARTED` up.

## My route cannot require `SUPER_ADMIN`

**Cause.** Role enforcement for module routes lives in CheeseDash's dispatcher, and it
only knows `requireWrite` — `OPERATOR` or above for every non-`GET` request, any role for
`GET`. `OPERATOR` is therefore the highest bar a module route reaches by default, however
sensitive the operation.

**Fix.** Call `WebDashboard.requireAdmin(exchange)` at the top of that handler and act on
its return value, the same way the existing guards act on `requireWrite`:

```java
if (!WebDashboard.requireAdmin(exchange)) return;
```

No route in CheeseItems does this today. Note also that a mutating route exposed as `GET`
would be guarded by neither layer, since the central check keys purely on the HTTP method.

Separately, an `OPERATOR` is equivalent to console access on this module, by design: item
`useActions` run as the console at permission level 4, so anyone who can write an item
definition can run anything. Grant the role accordingly.
[Details](dashboard.md#authentication-and-role-guards).

## Filing a useful bug report

Report at [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) with:

1. **The mod version** from `gradle.properties` (`mod_version`), and the CheeseDash version
   alongside it.
2. **Every `[CheeseItems]` line** from `logs/latest.log`, from startup to the failure. The
   startup block alone answers half the questions — it tells us how many definitions loaded
   and whether the parse succeeded.
3. **The definition or catalog entry involved**, as JSON. `GET /api/v1/items/<id>` gives it
   to you verbatim, and it is more reliable than a description of what you think you set.
4. **A stack trace** if there is one. Search the log for `[CheeseItems API]` for HTTP-side
   failures and for `cheeseitems` for mixin and loader failures.
5. **For a resource pack problem**, the output of
   `curl -I http://<resourcePackHost>/cheeseitems/api/pack.zip` run from a player's network,
   not from the server.
6. **For a morpho problem**, whether CobbleShift is installed and what `cshift transform`
   does when you run it by hand.

Questions rather than bugs go to the [Arkensia Discord](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.md) · [CheeseItems](../README.md) →
