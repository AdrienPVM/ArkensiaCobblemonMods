[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](ftb-quests.fr.md)

# FTB Quests integration

An egg hunt wants a checklist, and FTB Quests is the checklist most Cobblemon packs
already ship. `ftb/FtbQuestsHook.java` — 733 lines, the third-largest file in the mod after
`ProceduralTextureGenerator` and `EggsApi` —
creates one quest per egg, completes it when a player finds that egg, and keeps the two in
step through edits and deletions.

⚠️ **The whole file is reflection against FTB Quests internals.** There is no compile-time
dependency and no API contract. Every class name, method name and field name is looked up
by string at runtime against `dev.ftb.mods.ftbquests.*`. This works, and it is also the
most fragile part of the mod: **it will break when FTB Quests changes its internals**, and
the breakage will be a silent degradation rather than a crash. If quests stop appearing
after a pack update, this is the first place to look.

Everything on this page is an observation about how FTB Quests behaved when the hook was
written, not a specification of FTB Quests.

## Gating

Two conditions must both hold before anything happens:

1. `ftbQuestsEnabled` is `true` in [the config](configuration.md#ftbquestsenabled).
2. `FtbQuestsHook.isAvailable()` returns `true`.

`isAvailable()` runs a one-time `init()` behind an `initialized` flag. It starts with
`FabricLoader.getInstance().isModLoaded("ftbquests")` and logs, once:

```text
[EAC-FTB] FTB Quests not present — hook disabled
```

Then it resolves every class, method, field and constructor it needs. Any failure in the
required set leaves `available` false and the hook inert for the rest of the session; the
optional pieces degrade individually and log what they found. A healthy startup on a pack
with FTB Quests logs something like:

```text
[EAC-FTB] CustomTask available — egg quests will not be clickable by players
[EAC-FTB] setRawIcon available — quest icons enabled
[EAC-FTB] TranslationManager available — title sync enabled
[EAC-FTB] Network sync available — live client updates enabled (sendToPlayers=true, create=true, editCtor=true)
[EAC-FTB] FTB Quests hook initialized — Java API ready
```

Those five lines are the best diagnostic the subsystem has. Read them before guessing.

## What it resolves, and what it does without

| Piece | Required | If missing |
|---|---|---|
| `ServerQuestFile`, `BaseQuestFile`, `Chapter`, `ChapterGroup`, `Quest`, `Task`, `CheckmarkTask`, `TeamData`, `QuestObjectBase` | yes | the hook disables itself entirely |
| `TeamData.get(PlayerEntity)` | yes | same — found by scanning for a static one-argument `get` |
| `CustomTask` | no | falls back to `CheckmarkTask`, which **players can click to complete themselves** |
| `QuestObjectBase.setRawIcon` | no | quests appear without an egg icon |
| `TranslationManager` | no | quest titles may not reach clients |
| architectury `NetworkManager.sendToPlayers` and the create/edit response messages | no | changes apply, but clients need to relog to see them |

The `CustomTask` fallback is the one with gameplay consequences. A `CheckmarkTask` is a
manual tick box: a player who opens the quest book can mark the egg found without
finding it. If your startup log says `CustomTask not found`, your hunt is on the honour
system.

## Creating a quest

`createQuest(chapterName, entry, server)` is called from two places: `POST /collectibles`
and `AdminCollectibleItem.postPlacement` for a brand-new egg. It returns the quest's
`long` id, or `0` on any failure, and the caller stores that id on the entry as
`ftbQuestId`.

What it builds:

- The chapter is resolved by `findOrCreateChapter`. Lookup is **by file name**, derived
  from the setting (lowercased, anything outside `a-z0-9_` turned into `_`) and compared
  case-insensitively — not by displayed title; see
  [configuration](configuration.md#ftbquestschapter). A chapter is created when none
  matches, and several matching chapters are merged into one, logged as
  `Merged N duplicate chapters into one 'oeufs'`.
- The quest's X position is `existingQuests.size() * 2.5`, Y is `0.0`. Every egg quest
  lands on a single horizontal row, 2.5 units apart. With fifty eggs that row is 125 units
  wide; a quest book chapter is not designed for that and you will be scrolling. There is
  no wrapping and no layout option.
- The title is the entry's `title`, or `Trouver : <id>` when the title is blank — note the
  non-breaking space before the colon, which is correct French typography and is baked
  into the Java source. The subtitle is the entry's `subtitle`.
- The icon, when `setRawIcon` resolved, is the actual decorative egg item built from the
  entry's DNA. The quest book therefore shows the real procedural egg.
- `applyQuestAppearance` sets a 2× scale and shape `none`.
- One task is created — `CustomTask` when available, `CheckmarkTask` otherwise — and given
  the same title as the quest.
- `onCreated()` is called on both objects, the id map is refreshed, cached data cleared,
  the file marked dirty, both objects broadcast to clients, and the file saved.

```text
[EAC-FTB] Created quest 'Trouver : cave_nord' for egg 'cave_nord' (questId=1234567890)
```

Every failure path returns `0` and logs at error level with the stack trace. A quest that
fails to create does not block the egg: the entry exists, `ftbQuestId` stays `0`, and the
discovery handler will try again.

### `ensureQuestExists`

The retry path. `AdminCollectibleBlockEntity.handlePlayerDiscovery` calls it when the
entry's `ftbQuestId` is still `0`, which happens when the egg was placed before FTB
integration was switched on. The quest is created at discovery time, the id is written
back to the entry, and the task is completed in the same breath — so the first player to
find a pre-existing egg does not lose their tick.

## Completing a quest

`completeForPlayer(questId, player)`:

1. Fetch the quest from the file by id. A missing quest logs
   `Quest <id> not found — cannot complete for <player>` and returns.
2. Take the quest's task list. An empty list logs `Quest <id> has no tasks`.
3. Resolve the player's `TeamData`. A null one logs `No TeamData for player <name>`.
4. Call `markTaskCompleted` on **the first task only**, then save the quest file.

```text
[EAC-FTB] Completed quest 1234567890 for player 'Lia'
```

Only the first task is completed. If you add a second task to an egg quest by hand, it
will not be ticked. Design your egg quests as one task each.

Going through `TeamData` means completion is **per team**, with FTB Quests' own semantics:
on a pack where players share a team, one member finding the egg completes it for all of
them. The egg's own `discoveredBy` set is still per player, so the mod's statistics and
the quest book can legitimately disagree. That is not a bug in either.

Saving the quest file on every discovery is worth noting for a busy server: a hunt event
with fifty players finding eggs produces fifty `saveNow` calls.

## Updating and deleting

`updateQuest(questId, title, subtitle, server)` is called from `PUT /collectibles/:id` and
from `POST /collectibles/:id/rename`, when integration is on and the entry has a quest.
It rewrites the quest's title and subtitle, and the first task's title, then broadcasts an
edit so open quest books refresh.

```text
[EAC-FTB] Updated quest 1234567890 title='La grotte du nord'
```

Note what rename does **not** do: the quest title follows the entry's `title`, not its id.
And when that title is blank, `updateQuest` does not rebuild `Trouver : <id>` — it writes
the fixed text `Trouver un oeuf` into the quest and its task. So renaming `egg_014` to
`cave_nord` without a title leaves the quest reading `Trouver un oeuf`, and so does
**any** `PUT` on an untitled egg — a scale change is enough. Set a title, or run a full
sync.

`deleteQuest(questId)` goes through `ServerQuestFile.deleteObject(long)`, which FTB Quests
itself broadcasts to clients as a delete message. That is why a deleted egg's quest
disappears from an open quest book without a relog.

```text
[EAC-FTB] Deleted quest 1234567890
```

## Full sync

`POST /api/v1/eggs/config/sync-ftb` calls `syncAll(server, registry)`. It is a rebuild,
not a repair:

1. Return immediately if the hook is unavailable or `ftbQuestsEnabled` is false.
2. Clear the chapter cache.
3. For every entry with a non-zero `ftbQuestId`, delete that quest and zero the id.
4. Refresh the id map, mark the file dirty.
5. Walk `getEntriesSorted()` and create a fresh quest and task for each, at
   `x = index * 2.5`, writing the new id back onto the entry.
6. Save the file and mark the registry dirty.

```text
[EAC-FTB] Full sync starting for chapter 'oeufs'...
[EAC-FTB] Full sync complete — 56 quests created
```

⚠️ **A full sync destroys player progress on those quests.** The old quests are deleted
and new ones created with new ids, so every tick a player had earned on an egg quest is
gone. The mod's own `discoveredBy` records survive — they live in the egg registry, not in
FTB Quests — but the quest book resets. Use `syncAll` when you are setting the hunt up or
when the quests have drifted badly, not as routine maintenance.

The whole method is wrapped in one try/catch. An exception part-way through leaves you
with some quests deleted and some recreated:

```text
[EAC-FTB] Full sync failed
```

If you see that line, read the stack trace and expect to run the sync again once the cause
is fixed.

## Using the advancement instead

If you would rather not let this mod write into your quest file, the hook is entirely
optional. Leave `ftbQuestsEnabled` at `false` and drive your quests from the
`eggandcheese:egg_found` criterion trigger, which is plain datapack data and has no
reflection anywhere near it.

The advancement the mod ships under that name is never loaded in 1.21.1 — it sits in the
old plural `advancements/` folder — so you need your own, in your pack's datapack:

```text
Advancement ID: mypack:egg_found_any
```

An FTB Quests task of type **Advancement** pointing at it completes on any egg discovery
when its criterion is `"egg_id": "*"`. For one specific egg, use
`"conditions": {"egg_id": "cave_nord"}` — the files are in
[decorative eggs and pedestals](display.md#the-advancement).

This route is more work to set up, survives FTB Quests updates, and is what to fall back
to when the hook stops resolving.

---

← [Decorative eggs and pedestals](display.md) · [Integrity: duplicates and orphans](integrity.md) →
