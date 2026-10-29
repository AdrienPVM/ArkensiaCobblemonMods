[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](zones-and-flags.fr.md)

# Zones and flags

A zone is an axis-aligned box in one world, with a name, an owner, a list of members, and
a set of flags that say what may happen inside it. Everything else CheeseGuard does —
groups, events, the prison, zone sales — is built on that one object.

The class is `com.cheeseguard.data.Region`. It is called a *region* in the code and a
*zone* everywhere a player or an admin sees it; the two words mean the same thing in this
documentation.

---

## The model

### Bounds

A zone stores six integers and a world identifier:

| Field | Meaning |
|---|---|
| `world` | The dimension identifier the box lives in, e.g. `minecraft:overworld` |
| `minX` `minY` `minZ` | The low corner, inclusive |
| `maxX` `maxY` `maxZ` | The high corner, inclusive |

The constructor and `setBounds()` both normalize, so you cannot create an inverted box:
whatever order you pass the corners in, the smaller value becomes the minimum. Both
corners are inclusive, which means a single-block zone has `min == max` and a volume of
one, and the selection wand reports `dx×dy×dz` with each axis computed as
`abs(pos2 - pos1) + 1`.

There is no cylinder, no polygon and no Y-less "whole column" mode. A zone that should
cover a plot from bedrock to sky is a box with `minY = -64` and `maxY = 320`; you set
those numbers yourself.

`contains()` is a plain six-way comparison plus a world-identifier equality check. A zone
never matches a position in another dimension — with one deliberate exception covered in
[per-player dimensions](#per-player-dimensions-see-the-masters-zones).

### Identity

The id is derived from the name, not chosen:

```java
String id = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
```

So `Arène du Nord` becomes `ar_ne_du_nord`. Accented letters and spaces are each replaced
by one underscore. The id is the key everywhere: in `regions.json`, in commands, in the
REST API, on sale signs.

> ⚠️ `/cg create` does **not** check whether that id already exists. `RegionStorage.addRegion()`
> is a `Map.put`, so creating a second zone whose name slugifies to an existing id
> **silently replaces the first zone**, with its members, flags and sale config. The
> dashboard route refuses the same case with HTTP 409. Until that is fixed, treat
> `/cg create` as destructive and check `/cg list` first.

### Priority and parenting

Two integers-worth of ordering:

- `priority` — an `int`, default `0`, higher wins.
- `parentId` — the id of another zone, default `null`.

Both only matter when zones overlap. See [overlaps](#overlapping-zones).

### Everything else a zone carries

| Field | Default | Role |
|---|---|---|
| `groupId` | `null` | The group this zone inherits flags from. See [groups](groups.md) |
| `owner` | the creator | A single `Member`, or `null` for an ownerless zone |
| `members` | empty | Additional `Member` entries |
| `flags` | empty | Three-state map, see [flags](#the-flags) |
| `priority` | `0` | Overlap tie-break |
| `entryMessageEnabled` | `false` | Whether to chat a line on entry |
| `entryMessage` | `"Vous entrez dans : <name>"` | That line |
| `exitMessageEnabled` | `false` | Whether to chat a line on exit |
| `exitMessage` | `"Vous quittez : <name>"` | That line |
| `protectionMessage` | `"⛔ Cette zone est protégée !"` | Action-bar text when a flag blocks something |
| `particleColor` | `"green"` | Outline colour for `/cg toggle` visualization. One of red, green, blue, yellow, purple, cyan, white, orange, pink; an unknown value draws green. A tag with a configured `color` takes precedence, see [tags](#tags-and-ownership-limits) |
| `requiredAdvancementsEntry` / `Exit` | empty | Advancement gates, see [entry and exit gates](#entry-and-exit-gates) |
| `advancementDeniedMessage` | `"⛔ Vous n'avez pas les advancements requis !"` | Shown when a gate refuses |
| `requiredQuestsEntry` / `Exit` | empty | FTB Quests gates, hexadecimal quest ids |
| `commandsOnEnter` / `commandsOnExit` / `commandsOnBattleLoss` | empty | Command triggers, see [command triggers](#command-triggers) |
| `tags` | empty | Free text labels, see [tags](#tags-and-ownership-limits) |
| `saleConfig` | `null` | Sale listing, see [selling zones](#selling-zones) |

Those three message defaults are French string literals hard-coded in `Region.java` and
`RegionManager.java`, not translation keys. A server running in English will show French
text until you override the message per zone. It is a known rough edge, not a setting.

---

## Creating a zone

The selection wand is a **golden axe** named `Zone Selection Wand`. `/cg wand` hands you
one; it is not craftable and nothing else in the mod uses that item.

1. `/cg wand` — permission level 2.
2. **Left-click** a block: position 1. The click is consumed, so the block is not broken.
3. **Right-click** a block: position 2. The chat then prints the selection size and the
   total block count.
4. `/cg create <name>` — creates the zone in the world of the selection (the world of
   your last wand click), with you as owner.

Both wand clicks require permission level 2. The left click checks the main hand; the
right click checks the hand that clicked. The wand is recognised as any golden axe whose
custom name contains `Zone Selection Wand`. `/cg pos1` and `/cg pos2` (level 2) set the
same corners from the block you are standing on, without the wand. A selection lives in
memory per player (`SelectionManager`) and is lost on restart.

The dashboard route `POST /zones` consumes the *same* selection: it looks up the stored
selection of the player you name, which means an admin selects in game and an admin
creates from the browser. With no `playerName` in the body it falls back to the first
online player with permission level 2.

> The owner created by `/cg create` and by the dashboard is a `Member` built with the
> two-argument constructor, which assigns role `MEMBER` rather than `OWNER`. It is still
> stored in the `owner` slot and still treated as the owner everywhere, so the behaviour
> is right, but the role string written to `regions.json` is misleading. Ownership
> transferred later through `transferOwnership()` — which the purchase flow and the zone
> menu both use — correctly writes `OWNER`.

---

## Overlapping zones

Zones may overlap freely. `RegionManager.getRegionsAt()` returns **every** zone containing
the position, and resolution walks that list in order. The order is built in two steps.

First, sort by `priority` descending. Then re-sort with a comparator that puts a child
before its parent:

```java
regions.sort((a, b) -> {
    if (a.getId().equals(b.getParentId())) return 1;  // b is a's child → b first
    if (b.getId().equals(a.getParentId())) return -1; // a is b's child → a first
    return Integer.compare(b.getPriority(), a.getPriority());
});
```

So a direct parent/child pair always resolves in the child's favour, whatever the
priorities say, and everything else resolves by priority. Two zones with the same priority
and no parent link keep the order `RegionStorage` loaded them in, which is insertion
order from `regions.json` — stable, but not something to design around. Give any pair of
overlapping zones different priorities, or a parent link, if you care which wins.

`parentId` is a one-level hint used by that comparator. It is not a hierarchy: a
grandchild does not beat its grandparent through the chain, only through priority.

### The full resolution order

`RegionManager.isAllowed(player, world, x, y, z, flag)` answers every protection question
in the mod. In order:

1. **OP bypass.** Permission level 2 or higher returns *allowed* immediately — but only
   for member-exempt flags (see below), and only when the player is not in test mode.
2. **Member exemption.** For a member-exempt flag, being the owner or a member of *any*
   zone covering the position returns *allowed*.
3. **No zone here** → fall through to the dimension's denied-flag set.
4. **Event overrides.** For every non-`IDLE` event, a runtime override keyed
   `<zoneId>:<flagId>` wins, then a persisted override from the event's config. These beat
   everything below. Prop Hunt and timeline effects set them.
5. **Pass 1 — explicit zone flags.** The first zone in the sorted list with an explicit
   `true`/`false` for this flag decides.
6. **Pass 2 — group flags.** No zone had an explicit value; walk the same list again and
   take the first group flag that is set.
7. **Pass 3 — dimension fallback.** Still nothing; the action is denied only if the
   dimension config lists this flag in its `deniedFlags`.
8. Otherwise **allowed**.

Passes 1 and 2 being separate is the whole point. If zone A explicitly allows `pvp` and
overlapping zone B has no `pvp` flag of its own but belongs to a group that denies `pvp`,
PvP is **allowed** — an explicit flag on any zone beats a group flag on any zone, even a
higher-priority one. If you want B's group rule to win, set the flag explicitly on B.

`isFlagAllowed(world, x, y, z, flag)` is the player-less variant used for environmental
checks (fire spread, mob griefing). It skips steps 1, 2 and 4 and runs the three passes
directly.

> `RegionManager` also carries a test mode (`toggleTestMode`, `isTestMode`) that suspends
> the OP bypass and the member exemption so an admin can feel the zone as a player would.
> Nothing calls it — no command, no API route. The set is always empty at runtime. It is
> dead code with a useful shape; wire it up before relying on it.

### Per-player dimensions see the master's zones

A per-player dimension id looks like `cheeseguard:<masterId>_<8 hex>`. When resolving a
position inside one, `getRegionsAt()` also matches zones whose `world` is the **master**
dimension `cheeseguard:<masterId>`. One set of zones therefore covers every player's copy
of an instanced world, which is what you want: you draw the arena once.

The suffix test is strict — an underscore followed by exactly eight lowercase hex
characters — so a master dimension whose own id happens to end that way would be
misread. Avoid naming a master dimension `something_deadbeef`.

---

## The flags

Thirty-one flags, defined once in `com.cheeseguard.data.Flag`. Each one is three-state on
a zone and on a group:

- **allow** — the action is permitted; stops resolution here.
- **deny** — the action is refused; stops resolution here.
- **inherit** — no value stored on this object; resolution continues to the group, then
  to the dimension. This is what every flag is until you set it.

The REST API spells those three states `ALLOW`, `DENY` and `INHERIT`. `/cg flag` takes
`true`, `false`, or `inherit` / `null`; any other word is parsed by `Boolean.parseBoolean`
and therefore stored as `false` (deny). A flag id that `Flag.fromId()` does not recognise
is ignored on load and refused by the command.

> There is **no per-flag default value**. The fourth argument in each enum constant is
> `memberExempt`, not a default — a mistake that is easy to make reading the file. With
> nothing set anywhere, every action is allowed; a flag only ever restricts.

### Member-exempt flags versus behavioural flags

`memberExempt` splits the flags into two families, and it changes who the flag applies to:

- **Member-exempt (protection) flags** — owners, members and OPs bypass them. These are
  the classic "don't let strangers break my stuff" flags.
- **Behavioural flags** — they apply to **everyone**, members and OPs included, because
  they describe how the world behaves rather than who owns it. Denying `fall-damage` in an
  arena has to apply to the admin standing in it too, or the arena does not work.

### Protection flags — members and OPs bypass

| Flag id | What it gates |
|---|---|
| `block-break` | Breaking a block. Checked in `PlayerBlockBreakEvents.BEFORE` |
| `block-place` | Placing a block |
| `interact` | Right-clicking a block or an entity: chests, doors, NPCs |
| `entity-damage` | Attacking any non-player entity |
| `pvp` | Attacking a player |
| `use-items` | Using a bucket, flint and steel, a fire charge, bone meal or TNT. Both the clicked block and the block the click lands on are checked |
| `explosions` | TNT and creeper blasts |
| `item-drop` | Dropping items |
| `item-pickup` | Picking items up |
| `ender-pearl` | Throwing an ender pearl |
| `entry` | Walking into the zone. Denied entry teleports the player back to the block they came from |
| `exit` | Walking out of the zone. Same push-back |

### Behavioural flags — nobody is exempt

| Flag id | What it gates |
|---|---|
| `fire-spread` | Fire spreading from block to block |
| `mob-spawning` | Natural mob spawns |
| `mob-grief` | Mob-caused damage: endermen lifting blocks, mobs trampling grass |
| `trample` | Crops being trampled |
| `fly` | Elytra flight and creative flight. On deny, fall-flying is stopped and the flying ability is revoked, every 10 ticks |
| `cobblemon-fly` | Riding a Cobblemon mount while airborne. On deny, the player is dismounted when the mount is off the ground |
| `cobblemon-mount` | Riding any Cobblemon mount at all |
| `cobblemon-summon` | Having Pokémon out. Sending out is blocked by the Cobblemon hook; walking in with one already out recalls your party |
| `fall-damage` | Damage on landing |
| `hunger` | Hunger drain |
| `leaf-decay` | Leaves decaying when the log goes |
| `ice-melt` | Ice melting |
| `snow-form` | Snow layers forming |
| `vine-grow` | Vines and similar plants spreading |
| `redstone` | Redstone mechanisms |
| `nametag-hide` | **Denying this flag hides nametags.** See the inversion note below |
| `minimap-hide` | **Denying this flag hides the minimap.** Same inversion |
| `coral-death` | Coral dying out of water |
| `cobbleshift-transform` | Being CobbleShift-transformed. On deny, the player is reverted, their status effects are cleared and granted flight is removed |

> ⚠️ `nametag-hide` and `minimap-hide` read backwards. The code computes
> `shouldHide = !isAllowed(..., flag)`, so **deny** means *hide* and the default
> (inherit, nothing set) means *show*. The flag names describe the effect of denying them,
> not of allowing them. Both are pushed to the client over the `GuiSyncPayload` channel
> as `NAMETAG_ZONE` / `MINIMAP_ZONE` with a `{"hide":true}` body, and only when the state
> changes, so a player without the client mod sees nothing happen.

Flags that depend on Cobblemon (`cobblemon-fly`, `cobblemon-mount`, `cobblemon-summon`)
are skipped entirely when Cobblemon is not loaded; the check returns early.
`cobbleshift-transform` does the same when CobbleShift is absent. The quest gates are
skipped when FTB Quests is absent.

### What a player sees when a flag blocks them

Most refusals print the zone's `protectionMessage` on the **action bar** — the line just
above the hotbar, not chat. The default is `⛔ Cette zone est protégée !`, taken from the
highest-priority zone at the position; with no zone there, `RegionManager` falls back to
the same literal.

A handful of checks have their own translated message instead, from
`assets/cheeseguard/lang/`:

| Situation | English | French |
|---|---|---|
| `fly` denied | `Flying is not allowed in this zone!` | `Vol interdit dans cette zone !` |
| `cobblemon-fly` denied | `Flying mounts are not allowed in this zone!` | `Monture volante interdite dans cette zone !` |
| `cobblemon-mount` denied | `Mounts are not allowed in this zone!` | `Monture interdite dans cette zone !` |
| `cobblemon-summon` denied | `Pokemon are not allowed in this zone! Auto-recall.` | `Pokémon interdits dans cette zone ! Rappel automatique.` |
| `cobbleshift-transform` denied | `CobbleShift transformations are not allowed here! Reverted.` | `Transformations CobbleShift interdites ici ! Annulée.` |
| `entry` denied | `You cannot enter this zone!` | `Vous ne pouvez pas entrer dans cette zone !` |
| `exit` denied | `You cannot leave this zone!` | `Vous ne pouvez pas quitter cette zone !` |

The five periodic checks — fly, Cobblemon mounts, transform, nametag, minimap — run on a
tick handler every 10 ticks, so roughly twice a second. A player who walks into a no-fly
zone keeps flying for up to half a second before being dropped. That is intentional:
checking 31 flags for every player every tick would cost more than it is worth.

---

## Members and roles

`Member` is a UUID, a cached display name, and a role from `Member.Role`: `OWNER` or
`MEMBER`. There are only those two.

A zone holds one `owner` slot and a `members` list. `isMember(uuid)` returns true for
either, so the owner never needs to also appear in the list.

| Operation | Effect |
|---|---|
| `addMember` | Appends, unless `isMember` is already true |
| `removeMember` | Removes by UUID from the list. Does not touch the owner |
| `transferOwnership` | Removes the new owner from the members list, demotes the old owner to `MEMBER` and appends them to the list, then installs the new owner with role `OWNER` |
| `removeOwner` | Clears the owner slot. The zone becomes ownerless; its members keep their exemption |

The cached name is what you see in listings and what `/cg removemember` matches against,
case-insensitively. Nothing ever refreshes it (`Member.setName()` has no caller), so a
renamed account keeps its old name until it is removed and added again.

### In game

A zone's owner standing inside it gets an action-bar reminder every two seconds —
`You own <zone> — press <key> to manage` — with the real keybind for
`key.cheeseguard.zone_menu`, so a rebound key shows correctly. The key opens a
client-side menu that can add a member, remove one, transfer ownership, rename the zone
(1 to 64 characters), cycle the zone's `pvp` and `mob-spawning` flags through
inherit → allow → deny → inherit, and abandon it. The menu needs the CheeseGuard client mod; without it the server answers
`This menu requires the CheeseGuard client mod.`

`/cg addmember <zone> <player>` and `/cg removemember <zone> <player>` do the same from
the console or chat. The target must be **online** for `addmember`, because the UUID comes
from `PlayerManager.getPlayer(name)`.

`/cg flag`, `/cg addmember`, `/cg removemember` and `/cg group …` all require
permission level 2, like `/cg create`, `/cg delete` and `/cg wand`. `/cg buy` is open to
every player, since it is how players purchase zones.

---

## Entry and exit gates

Entry and exit are detected by comparing the block position a player occupied on the last
check with the one they occupy now, every 10 ticks. For each zone they just entered, in
order:

1. The `entry` flag. Denied → teleport back to the previous position and show
   `You cannot enter this zone!`
2. Required advancements, unless the player has permission level 2. Any missing one →
   teleport back, show the zone's `advancementDeniedMessage` on the action bar, and list
   the missing advancements in chat by their display title.
3. Required FTB quests, same conditions, when FTB Quests is loaded.
4. The entry message, if enabled.
5. The `commandsOnEnter` list.

Exit runs the mirror of that with `exit`, the exit requirement lists, `exitMessage` and
`commandsOnExit`. Both requirement lists are the **union** of the zone's own list and its
group's list, so a group can gate a dozen zones at once.

Quest ids are hexadecimal strings — FTB's own id format, parsed with
`Long.parseUnsignedLong(id, 16)`. A quest that cannot be resolved counts as missing, which
fails closed: a typo locks the zone rather than opening it. The lookup goes through
reflection against `dev.ftb.mods.ftbquests.quest.ServerQuestFile`, looking methods up by
both name (`getData`, `getBase`, `isCompleted`…) and parameter type. That is an observation of how
FTB Quests behaves today, not a contract — an FTB update can break it, and when it does
every required quest reads as missing.

The push-back teleport uses `teleport()` rather than `requestTeleport()` so the player
cannot be pushed into the wrong world, and it rewrites the remembered position so the next
check does not fire again immediately.

---

## Command triggers

Three lists of raw server commands:

| List | Fires when |
|---|---|
| `commandsOnEnter` | The player enters the zone |
| `commandsOnExit` | The player leaves the zone |
| `commandsOnBattleLoss` | The player loses a Cobblemon battle inside the zone |

Each entry is a command string. A leading `/` is stripped if present, so both forms work.
`{player}` is replaced by the player's name. Empty and blank lines are skipped.

The command runs with the **server console's** source — operator level — but with the
player's world and position injected, which matters for per-player dimensions: a relative
`tp ~ ~ ~` resolves in the player's own copy of the world, not in the master. A command
that throws is caught and reported to the server log as
`[CheeseGuard] Erreur commande zone "<cmd>": <message>`; the remaining commands still run.

```json
"commandsOnEnter": [
  "effect give {player} minecraft:night_vision 20 0 true",
  "title {player} actionbar {\"text\":\"Mines du Nord\"}"
]
```

Giving a player an operator-level command trigger is as powerful as it sounds. A zone
anybody can walk into with `op {player}` in its enter list hands out your server. Review
these lists the way you would review a datapack function.

---

## Tags and ownership limits

`tags` is a free-text list on a zone. Nothing enforces a vocabulary; `GET /zones/tags`
collects every distinct tag in use for autocompletion.

A tag becomes meaningful when it has a configuration in
`config/cheeseguard/tag_configs.json`:

```json
{
  "plot": {
    "label": "Residential plot",
    "color": "#22d3ee",
    "ownershipLimit": 2
  }
}
```

| Key | Type | Default | Effect |
|---|---|---|---|
| `label` | string | the tag id | Display name. Omitted from the file when it equals the id |
| `color` | string | absent | Display colour (`#rrggbb`). Shown by the dashboard, and used for the `/cg toggle` outline of every zone carrying the tag, ahead of the zone's own `particleColor` |
| `ownershipLimit` | int | `0` | Maximum zones carrying this tag that one player may own. `0` means unlimited |

A tag id must match `^[a-z0-9-]+$` and be at most 64 characters.

The limit is checked when a player **buys** a zone, by counting the zones where they are
the owner and whose tags contain that tag. A tag with no config, or a limit of zero, never
blocks. Nothing re-checks the limit when an admin assigns a zone by hand or when ownership
is transferred through the zone menu, so an admin can push a player over their own limit.

---

## Persistence

Zones and groups share one file:

```text
config/cheeseguard/regions.json
```

It is read once in the `RegionStorage` constructor, at mod init, and written in full every
time a zone or group is created, deleted or modified — `addRegion`, `removeRegion`,
`addGroup`, `removeGroup` and every API write call `save()`. Groups are deserialized
before zones, so a zone's `group` reference always resolves.

Pretty-printed, so it stays readable. Editing it by hand while the server runs
accomplishes nothing: the in-memory copy is authoritative and the next write overwrites
your edits. Stop the server first.

### Shape

```json
{
  "regions": {
    "mines_du_nord": {
      "name": "Mines du Nord",
      "world": "minecraft:overworld",
      "minX": 120, "minY": -20, "minZ": -340,
      "maxX": 190, "maxY": 60,  "maxZ": -260,
      "group": "pve",
      "parent": "continent_nord",
      "priority": 10,
      "owner":   { "uuid": "…", "name": "RoiCheese", "role": "OWNER" },
      "members": [ { "uuid": "…", "name": "Alex", "role": "MEMBER" } ],
      "flags": { "block-break": false, "pvp": false, "fall-damage": false },
      "entryMessageEnabled": true,
      "entryMessage": "Vous entrez dans : Mines du Nord",
      "exitMessageEnabled": false,
      "exitMessage": "Vous quittez : Mines du Nord",
      "protectionMessage": "⛔ Cette zone est protégée !",
      "particleColor": "green",
      "tags": [ "plot" ],
      "commandsOnEnter": [ "effect give {player} minecraft:night_vision 20 0 true" ]
    }
  },
  "groups": { }
}
```

The zone id is the **key**, not a field. Only flags that are explicitly set appear in
`flags`; an absent flag is inherit. Several blocks are written only when non-empty —
`tags`, the three command lists, the four requirement lists, `saleConfig`,
`advancementDeniedMessage` when it differs from the default — which keeps the file short
for simple zones.

`name`, `world` and the six bounds are read without a `has()` guard. A hand-edited zone
missing any one of them throws, and the load aborts in the catch block: the zones read
before it stay loaded, but the broken one and every zone after it in the file are lost,
with one `Failed to load CheeseGuard data` line in the log. The next save then rewrites
the file without them. Back the file up before editing it.

### What does not survive a round-trip

`SaleConfig` holds `signLine1`, `signLine2` and `signType`, and the serializer writes
none of them. Set a sign's custom lines or its wood type from the dashboard and they work
until the next restart, after which signs fall back to the zone name, an empty second
line, and oak. Verified by reading `RegionStorage.serializeRegion()` against
`SaleConfig` — the fields exist on both the API and the object, only the persistence step
is missing.

---

## Selling zones

A zone can be put up for sale to players, paid for in one of three currencies, and claimed
by right-clicking a sign or a hologram. The whole feature hangs off the zone's
`saleConfig`; a zone with `saleConfig == null` is not for sale and nothing about it
changes.

### The sale config

| Key | Type | Default | Effect |
|---|---|---|---|
| `enabled` | boolean | `false` | Master switch. A disabled config answers `This zone is not for sale.` |
| `price` | long | `0` | Clamped to `>= 0` by the setter |
| `currency` | string | `"NUMISMATIC"` | One of `NUMISMATIC`, `FROMAGE`, `ITEMS`. Compared case-insensitively |
| `itemId` | string | `null` | For `ITEMS`: the item identifier, e.g. `minecraft:diamond` |
| `itemCount` | int | `0` | For `ITEMS`: how many. Clamped to `>= 1` by the setter |
| `signText` | string list | empty | Overrides the whole hologram text, one entry per line. Once sold, a red `SOLD` line is still appended |
| `signLine1` | string | zone name | Line 2 of the physical sign. **Not persisted** |
| `signLine2` | string | empty | Line 3 of the physical sign. **Not persisted** |
| `signType` | string | `"oak"` | Wood type of the sign item. **Not persisted** |
| `sold` | boolean | `false` | Set by a successful purchase |
| `buyerUuid` / `buyerName` | string | `null` | Written by a successful purchase |
| `hologramUuid` | string | `null` | UUID of the spawned text display, managed by the mod |

`signType` accepts `oak`, `spruce`, `birch`, `jungle`, `acacia`, `dark_oak`, `mangrove`,
`cherry`, `bamboo`, `crimson` and `warped`. Anything else falls back to oak.

### The three currencies

**`NUMISMATIC`** goes through `NumismaticHook` to the Numismatic Overhaul mod. The price
is a single number of bronze, and it is displayed decomposed: 10 000 bronze to a gold,
100 to a silver. A price of `10250` prints as `1 gold 2 silver 50 bronze`. A zero price
prints `0 bronze`.

**`FROMAGE`** goes through `CheeseStoreHook` to the CheeseStore wallet, keyed by player
UUID. Displayed as `<price> fromage`.

**`ITEMS`** counts matching stacks across the buyer's whole inventory and takes them from
the first slots found. Displayed as `<count>x <itemId>`.

An unknown currency string logs `Unknown sale currency: <value>` and makes `canAfford`
return false, so the zone simply cannot be bought. Both hooks are optional-mod bridges: if
the backing mod is absent, the affordability check is whatever the hook decides, which is
an observation about those two hooks rather than a guarantee of this page.

### Putting a zone on the market

From the dashboard, `PUT /zones/:id/sale` with the fields above, then one or both of:

- `POST /zones/:id/sale/hologram` — spawns a `TextDisplayEntity` at the horizontal centre
  of the zone, 1.5 blocks above `maxY`, billboarded to always face the viewer. The entity
  UUID is stored in `hologramUuid`. Both endpoints refuse with 400 unless the sale config
  exists and is enabled.
- `POST /zones/:id/sale/sign?player=<name>` — builds a waxed sign item and puts it in that
  **online** player's inventory, or drops it at their feet. Place it wherever the plot
  should be advertised.

The text display is configured through reflection, because the setters are private in the
Yarn mappings; the methods are located by parameter type rather than by name, so remapping
does not break them. If the reflection fails the hologram still spawns — blank. The log
line is `Failed to configure TextDisplayEntity via reflection`.

The sign carries the zone id in **back text line 0**, and that is how a click is
recognised. Front text is four lines: a translated `[FOR SALE]` / `[À VENDRE]` header,
`signLine1` or the zone name, `signLine2` or nothing, and the formatted price. The sign is
waxed so players cannot edit it, and it also carries `CheeseGuardSale: <zoneId>` in custom
data.

Default hologram text, when `signText` is empty:

```text
FOR SALE
<zone name>
<formatted price>
[RIGHT-CLICK TO BUY]
```

and once sold:

```text
<zone name>
SOLD
Owner: <buyer name>
```

Those strings are literals in `SaleManager.buildHologramText()` — they are not
translated, and they stay English whatever the server language. Use `signText` if you need
them in another language.

### Buying

Right-clicking the sign or the hologram, or typing `/cg buy <zoneId>`, sends a prompt:

```text
Zone Mines du Nord is for sale for 1 gold 2 silver 50 bronze.
 [Click to Buy]
```

The button runs `/cg buy <zoneId> confirm`. A sign click is remembered for **60 seconds**,
keyed by player UUID, so the confirm knows which sign to break. Clicking a hologram
explicitly clears any remembered sign click, so a stale click from another plot cannot
bleed into this purchase.

`SaleManager.processPurchase()` then runs, on the server thread, in this order:

1. Sale config exists and is enabled, else `This zone is not for sale`.
2. Not already sold, else `This zone has already been sold`.
3. Tag ownership limits, else `You have reached the ownership limit for tag: <tag>`.
4. Affordability, else `You cannot afford this zone (<price>)`.
5. **Deduct the payment.** A failed item deduction returns
   `Failed to deduct items from inventory`, a failed fromage deduction
   `Failed to deduct fromage payment`. The Numismatic deduction's result is not checked.
6. `transferOwnership()` — the buyer becomes `OWNER`, the previous owner is demoted to
   `MEMBER` and kept in the members list.
7. `sold = true`, `buyerUuid` and `buyerName` recorded.
8. `storage.save()`.
9. Break the clicked sign, if one was recorded within the TTL, without dropping it.
10. Refresh the hologram text if there is a hologram.

On success the buyer gets `You are now the owner of %s!` / `Vous êtes maintenant
propriétaire de %s !` and the server logs
`Zone '<name>' purchased by <player> for <price>`.

The step-5 failure messages are **not** translated — they are returned as plain English
strings by `processPurchase` and printed in red as-is. Steps 1 through 4 are re-checked
here even though the command already checked some of them, because the dashboard and the
sign paths both land in this one method.

> ⚠️ The previous owner keeps member status, and member status grants exemption from every
> protection flag. Selling a plot therefore does **not** evict its old owner's build
> rights — they remain a member of the zone they just sold. If a sale should be a clean
> handover, clear the members list after it.

> `SaleManager.sendPurchasePrompt()` uses the translation key
> `cheeseguard.sale.already_sold` when the zone is already sold. That key does not exist
> in either lang file — only `cheeseguard.sale.alreadySold` does. In practice the branch
> is never reached: the sign, hologram and `/cg buy` paths all check `isSold()` first and
> answer with the correct `alreadySold` key before calling the prompt.

### Re-listing and taking a zone off the market

`POST /zones/:id/sale/relist` clears `sold`, `buyerUuid` and `buyerName`, forces
`enabled` back to `true`, and refreshes the hologram. It does **not** restore the previous
owner or remove the buyer — ownership stays with whoever bought it, so re-listing a sold
plot means selling it again out from under its owner. Change the owner first if that is
not what you want.

`DELETE /zones/:id/sale` removes the hologram and clears the sale config entirely. The
physical sign, if one was placed, stays in the world; a click on it then finds a zone with
no sale config and does nothing at all, because the sign handler only intercepts the click
when `saleConfig != null`.

---

← [Configuration](configuration.md) · [Groups](groups.md) →
