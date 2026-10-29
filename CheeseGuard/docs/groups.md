[← CheeseGuard](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](groups.fr.md)

# Groups

A group is a named bundle of flags and entry requirements that a set of zones share. It
exists so that twenty spawn-adjacent plots can all deny `pvp` without you editing twenty
zones, and so that changing your mind means editing one object.

The class is `com.cheeseguard.data.RegionGroup`. It lives in the same file as the zones,
`config/cheeseguard/regions.json`, under a `groups` key.

---

## What a group holds, and what it does not

| Field | Type | Role |
|---|---|---|
| `id` | string | Slug, derived from the name. The key everywhere |
| `name` | string | Display label, free text |
| `regionIds` | string list | The zones that belong to the group |
| `flags` | three-state map | Flag values inherited by member zones |
| `requiredAdvancementsEntry` / `Exit` | string list | Advancement gates, merged with each zone's own |
| `requiredQuestsEntry` / `Exit` | string list | FTB Quests gates, same merge |

That is the whole object. A group has **no** bounds, no members, no owner, no priority, no
messages, no tags, no sale config and no command triggers. It is not a zone and it does not
cover any space: a group only ever contributes values to zones that belong to it.

Three consequences worth stating plainly:

- A group cannot grant membership. Adding somebody to a "staff" group is not possible —
  members belong to zones, one list per zone.
- A group cannot carry an entry or exit message. Those are per-zone strings.
- A group has no priority, so two groups never compete. A zone belongs to **at most one**
  group: `groupId` is a single string, not a list.

---

## Creating a group and filling it

### From commands

```text
/cg group create <name>
/cg group add <zoneId> <groupId>
/cg group delete <groupId>
```

`create` slugifies the name with the same rule zones use —
`name.toLowerCase().replaceAll("[^a-z0-9_-]", "_")` — so `Zone PvE` becomes `zone_pve`.
It refuses an id that already exists. `add` sets the zone's `groupId` and appends the zone
id to the group's list, in one step; it reports `Zone or group not found.` if either side
is missing. It does **not** take the zone out of its previous group's list.

All three require permission level 2: the `requires` clause sits on the `group` node
itself, so it covers every subcommand.

`/cg group delete` calls `removeGroup()` without checking that the group exists, so
deleting a group that is not there reports success and does nothing. Harmless, but do not
read the confirmation as proof the id was right.

### From the dashboard

`POST /groups` with `{"name": "..."}`. The id comes from a slightly different slug than
the command's: `GroupsApi.slugify()` **drops** characters outside `[a-z0-9\s-]` instead of
replacing them, then collapses whitespace runs into single hyphens. `Zone PvE` becomes
`zone-pve` from the dashboard and `zone_pve` from the command.

The two rules disagree, and nothing reconciles them. Pick one creation path per server and
stay with it, or you will end up with `zone-pve` and `zone_pve` side by side.

Assignment is `POST /groups/:id/zones/:zoneId`, removal is the same path with `DELETE`.
Both keep the two sides of the relationship consistent, which matters because the
relationship is stored twice.

---

## The relationship is stored on both sides

A zone knows its group (`Region.groupId`) and a group knows its zones
(`RegionGroup.regionIds`). Nothing in the data model forces those two to agree, so every
write path updates both:

| Path | What it does |
|---|---|
| `POST /groups/:id/zones/:zoneId` | Removes the zone from its old group, appends to this one, sets `groupId` |
| `DELETE /groups/:id/zones/:zoneId` | Removes from the list, and clears `groupId` only if it still points at this group |
| `PUT /zones/:id` with `groupId` | Same two-sided update, from the zone's side. An empty string means `null` |
| `DELETE /zones/:id` | Removes the zone id from its group's list before deleting the zone |
| `DELETE /groups/:id` | Clears `groupId` on every zone that pointed at the group |
| `/cg group add` | Sets `groupId` and appends to this group's list, but leaves the zone id in its previous group's list |

Flag resolution reads only **one** of the two sides: `isAllowed()` goes from the zone to
`region.getGroupId()` and looks the group up by id. A zone listed in a group's
`regionIds` but whose own `groupId` is null or stale inherits **nothing** — it just shows
up in the group's zone listing, which skips ids that no longer resolve to a zone. If a
group's flags seem to be ignored by one zone, check that zone's `groupId` first.

`/cg group add` and the JSON file are the two ways to get into that inconsistent state.
Moving a zone from one group to another with the command leaves it listed in the old
group too (harmless for flags, since `groupId` points at the new one, but visible in the
old group's zone listing); a hand edit can break either side. The dashboard routes clean
up the old group.

---

## How rights resolve when a zone has both

Flags resolve across all overlapping zones at a position, in passes.
[The full order is in the zones page](zones-and-flags.md#the-full-resolution-order); the
part that concerns groups is this:

1. **Pass 1** — every zone covering the position is checked for an **explicit** value of
   the flag, in sorted order. The first one found decides, and resolution stops.
2. **Pass 2** — only if no zone had an explicit value. The same list is walked again, and
   the first zone whose group has a value for the flag decides.
3. **Pass 3** — the dimension's denied-flag set.

So: **an explicit zone flag always beats a group flag.** Not just its own group's flag —
*any* group flag from *any* overlapping zone, however high that other zone's priority.

### The three cases

**One zone, with a group.** Its own flag wins when set; the group fills in everything left
on inherit. This is the normal case and it behaves the way you expect: the group is the
default, the zone is the override.

**Overlapping zones, one with an explicit flag.** The explicit value wins even if the
other zone's group says the opposite and even if that other zone has the higher priority.
Pass 1 finishes before pass 2 ever starts.

Concretely: `plaza` (priority 0) explicitly allows `pvp`; `downtown` (priority 50)
overlaps it and belongs to group `safe`, which denies `pvp`. Inside the overlap PvP is
**allowed**, because `plaza` has an explicit value and `downtown` does not. Raising
`downtown`'s priority changes nothing. To make `safe` win there, set `pvp` to deny
explicitly on `downtown`.

**Overlapping zones, both on inherit.** Pass 2 decides, walking the zones in the same
sorted order — child before parent, then priority descending — and taking the first group
value it finds. Here priority does what you would expect.

### Members and OPs come first

The member exemption and the OP bypass are checked **before** any of those passes, and
they are checked against the zones, never against the group. For a member-exempt
(protection) flag, being the owner or a member of any zone covering the position allows
the action outright — a group's deny cannot restrict a member of one of its own zones.

Behavioural flags have no exemption, so a group that denies `fall-damage` or
`cobblemon-summon` applies to members, owners and operators alike. That asymmetry is the
single most common source of "my group flag does nothing": protection flags are waived for
the people who were testing them.

Which flags fall in which family is listed in
[the flag tables](zones-and-flags.md#the-flags).

### Entry requirements merge instead of overriding

Advancement and quest gates behave differently from flags. For each zone,
`ProtectionListener` builds the **union** of the zone's list and its group's list into a
`LinkedHashSet` — zone entries first, group entries after, duplicates collapsed:

```java
Set<String> required = new LinkedHashSet<>();
required.addAll(entry ? region.getRequiredAdvancementsEntry() : region.getRequiredAdvancementsExit());
if (region.getGroupId() != null) { /* add the group's list too */ }
```

A zone cannot shorten its group's requirement list — only lengthen it. A group with one
required advancement gates every one of its zones, and a zone that adds two of its own now
requires all three. If you need one zone exempt, take it out of the group.

Operators skip both requirement lists entirely, at permission level 2, and the quest list
is skipped when FTB Quests is not loaded.

---

## Setting group flags

Group flags are the same three states as zone flags: `ALLOW`, `DENY`, `INHERIT`. There is
no `/cg` command for them — `/cg flag` only writes zone flags. Groups are edited from the
dashboard:

```http
PUT /api/v1/cheeseguard/groups/zone-pve
Content-Type: application/json

{
  "name": "PvE zones",
  "flags": { "pvp": "DENY", "explosions": "DENY", "block-break": "INHERIT" }
}
```

Only the keys present in the body are touched; a flag id `Flag.fromId()` does not
recognise is skipped silently, and a value that is not one of the three states is skipped
too. `INHERIT` removes the entry from the map, which is how you un-set a group flag —
there is no delete-flag route.

`GET /groups/:id` always returns all 31 flags with their state, so `INHERIT` entries are
visible even though they are absent from the file. The listing (`GET /groups`) returns a
`flagCount`, which counts only the explicitly set ones.

---

## Persistence

In the same file as the zones, which is read once at startup and rewritten in full after
every change:

```text
config/cheeseguard/regions.json
```

```json
{
  "regions": { },
  "groups": {
    "zone-pve": {
      "name": "PvE zones",
      "regions": [ "mines_du_nord", "plaine_centrale" ],
      "flags": { "pvp": false, "explosions": false },
      "requiredAdvancementsEntry": [ "minecraft:story/mine_diamond" ]
    }
  }
}
```

The group id is the **key**. The zone list is written under `regions`, not `regionIds` —
the JSON name and the Java field name differ. `flags` holds only explicitly set entries,
as booleans. The four requirement lists are written only when non-empty.

Groups are deserialized **before** zones, so a zone's `group` reference always finds its
target during load. Only `name` is read without a `has()` guard, so a hand-edited group
missing its name aborts the load: the groups read before it survive, but that group, every
group after it and every zone are lost. Back the file up before editing.

`removeGroup()` sweeps every zone whose `groupId` matched and sets it to `null`, then
saves. Deleting a group never deletes a zone, and the zones keep whatever explicit flags
they had; they lose only the inherited ones.

---

← [Zones and flags](zones-and-flags.md) · [Dimensions](dimensions.md) →
