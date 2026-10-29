[← CheeseCompatModsFix](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

One thing to know before anything else: `cheesecompatmodsfix.mixins.json` sets
`"injectors": { "defaultRequire": 0 }`, which means **a mixin that no longer matches its
target is skipped silently**. No error, no warning, no stack trace. The fix simply stops
existing.

So the characteristic failure of this mod is not a crash. It is a bug you thought was
fixed coming back, with nothing in the log. Almost every symptom below is a variation on
that.

---

## Symptom: the build fails before it compiles

```text
* What went wrong:
A problem occurred evaluating project ':CheeseCompatModsFix'.
>
  CheeseCompatModsFix cannot be built without these third-party jars.

  Missing from localdeps/:
    - ftb-quests-fabric-2101.1.13.jar
```

**Cause.** FTB Quests and StarAcademy are CurseForge-exclusive. Gradle cannot fetch
them, and `build.gradle` checks for both files at configuration time rather than letting
you hit a hundred `cannot find symbol` errors later.

**Fix.** Download the **Fabric 1.21.1** file of whatever it names from CurseForge and put
it in `localdeps/` at the **repository root** — not in `CheeseCompatModsFix/` — with
exactly the filename in the message. See
[the build prerequisites](installation.md#-building-needs-two-jars-you-have-to-supply).

A newer version of either mod will probably compile, but you then have to change the
filename in `CheeseCompatModsFix/build.gradle` as well, because the reference is a
literal name and not a glob.

## Symptom: the build fails with `Mixin target ... could not be found`

**Cause.** A mixin that names its target as a string still needs that class on the
compile classpath. The Mixin annotation processor resolves and validates every target at
compile time.

**Fix.** Add the mod to `build.gradle` as `modCompileOnly` (or `compileOnly`, if the
class's signatures do not pull in remapped Minecraft types). Pin it to a Modrinth
**version id**, not a version number — numbers are not unique on Modrinth and you can
silently get the NeoForge jar. The reasoning is in
[`localdeps/README.md`](../../localdeps/README.md).

## Symptom: the mod does not load

No `[CheeseCompat] Loaded (v1.2.53)` line anywhere in the log.

**Cause and fix**, in the order worth checking:

| Cause | Fix |
|---|---|
| Java below 21 | `java -version`; the mod declares `java >=21` |
| Fabric Loader below 0.16.0 | update the loader |
| Fabric API absent | `fabric-command-api-v2` is a hard dependency; install the full Fabric API |
| Minecraft is not 1.21.1 | `minecraft ~1.21.1` — a 1.21.4 server will refuse the jar |
| two copies of the jar in `mods/` | delete the old `cheesecompatmodsfix-*.jar`; Fabric fails on duplicate mod ids rather than picking one |

If Fabric refused it, the reason is on the very first screen of the log, before any mod
initialises, as a `ModResolutionException` naming the unsatisfied dependency.

## Symptom: a fix for a mod I have installed is not working

The `Loaded (v1.2.53)` line is there, but nothing this mod is supposed to do for a
particular mod is happening.

**Cause, nearly always: the mod id did not match.** `CheeseCompatMixinPlugin` evaluates
`FabricLoader.isModLoaded("<id>")` for fourteen hard-coded id strings. A mod whose id is
not exactly one of those is, from this mod's point of view, not installed — and the
mixins for it are disabled with no message.

**Fix.** Check the ids:

```bash
grep -o 'isModLoaded("[^"]*")' src/main/java/com/cheesecompat/CheeseCompatMixinPlugin.java
```

against the `id` field in your installed mod's own `fabric.mod.json`. A fork, a rename,
or a mod that changed its id between versions all produce this symptom. The full list is
in [installation](installation.md#optional--the-mods-it-patches).

**Second cause: the family logs nothing on activation.** Only some fixes log. Use this
table:

| If you expect | Look for |
|---|---|
| the CobbleSafari fixes | six or seven `[CheeseCompat]` lines mentioning `Safari`, `CobbleSafari`, `Dungeon` or `tatami` (the `tatami` one only on Cobblemon 1.6.1) |
| the Enhanced Celestials fixes | `EnhancedCelestials lunar warmup registered` plus a `Warmed lunar data for …` line per dimension |
| the `/stopbattle <player>` restriction | `Restricted /stopbattle <player> to operators` |
| the Battle Tower fixes | `BattleTower foolproof disconnect/reconnect fix registered (v2 hardened)` at startup; the mixins themselves log per battle, on `startBattle` and `applyForcedLevelFallback` |
| the Daycare+ fixes | nothing. Test behaviour instead (see below) |
| the Legendary Monuments fixes | nothing. Set a config key to `false` and test |
| the client fixes | nothing. Test visually |

**Third cause: the injection point moved.** The target mod updated and the method, the
constant or the lambda the mixin was aimed at is no longer where it was.
`defaultRequire: 0` means this is silent. There is no way to detect it from the log; you
detect it from the symptom. The version each fix was written against is at the top of
each [catalogue page](../README.md#the-fix-catalogue).

## Symptom: a config key has no effect

Specifically: `arcPhoneHealEnabled` or `arcPhonePCEnabled` set to `false`, and the Arc
Phone still works.

**Cause 1 — you edited the file while the server was running.** The config is read once,
as the first statement of `onInitialize()`. There is no reload command and no file
watcher. The edit is not lost — nothing writes the file while the server runs — but it
only takes effect at the next start.

**Fix.** Stop the server, edit, start. Confirm with the startup line, which echoes the
values:

```text
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=DISABLED, PC=enabled, Sanctuary fix=ON
```

`DISABLED` in capitals means the config was read as `false`. If the line says `DISABLED`
and the Arc Phone still heals, the config is fine and the problem is the mixin.

**Cause 2 — the lambda injection no longer matches.** `ArcPhoneHealMixin` and
`ArcPhonePCMixin` inject into `lambda$registerServerReceiver$1`. Lambda names are
assigned by the compiler in source order and are not an API: a Legendary Monuments
release that merely reorders lambdas in that method renames the target. With
`require = 0`, the injection is skipped and the toggle silently stops working.

**Fix.** Decompile your Legendary Monuments jar, find `HealPacket`, and check what the
server receiver lambda is called in that build. There is no workaround from the config.

**Cause 3 — `sanctuaryOverflowFix` is `false`.** That key does work:
`SanctuaryDistanceMixin` checks it on every call and, at `false`, lets Legendary
Monuments' original overflowing arithmetic run, so a sanctuary can again appear to cover
the whole world. The log line shows `Sanctuary fix=OFF`. Set it back to `true`. See
[configuration](configuration.md#sanctuaryoverflowfix).

## Symptom: `/stopbattle` is refused or missing

| What you see | Cause | Fix |
|---|---|---|
| `No player was found` | you ran it from the console or a command block; the handler calls `getPlayerOrThrow()` first | run it as a player |
| `Unknown command` | Fabric's command API module is missing, so `CommandRegistrationCallback` never fired — but then the mod would not have loaded at all | install the full Fabric API |
| `Incorrect argument for command` on `/stopbattle <player>` | you are below permission level 2; the argument node is hidden from your command tree | that is the intended behaviour, see [commands](commands.md#stopbattle-player--restricted-not-added) |
| `Battle stop requested.` but the battle continues | the inner `/stopbattle <name>` found no battle, or Battle Tower is not installed | the message is sent after the inner command runs but ignores its result |

⚠️ One case to watch for: the log line
`[CheeseCompat] Could not restrict /stopbattle <player>: <message>` means the reflection
into Brigadier's private `children` and `arguments` maps failed. The rest of the mod is
fine, but **`/stopbattle <player>` is open to every player**, who can then end anyone's
battle by name.

## Symptom: `Couldn't parse element ... cobblesafari_portal_frame` at startup

```text
Couldn't parse element minecraft:loot_table/cheesecompat:blocks/cobblesafari_portal_frame
  - Unknown registry key ...: cheesecompat:cobblesafari_portal_frame
```

**Cause.** The portal frame block is registered only when CobbleSafari is present, but
its loot table ships unconditionally. Without CobbleSafari the table references a block
that does not exist.

**Fix.** None needed. The server starts normally; it is an orphaned loot table. Verified
on a 475-mod test server. It is the single most-reported line about this mod and it has
never meant anything.

## Symptom: Daycare+ pastures produce one egg and stop

**Cause.** That is this mod's default, and it is the opposite of Daycare+'s. A pasture
with no stored state defaults to `simpleMode = true`, `simpleArmed = true` in
`DaycareSimpleModeStore` — one egg, then disarm.

**Fix.** Click the clock in **slot 5** of the pasture GUI to switch it to "Auto
breeding". Per pasture; the setting is keyed by dimension and block position and stored in
`config/cheesecompat_daycare.json`. If you want every pasture to default to auto, the
default is in `DaycareSimpleModeStore` and needs a rebuild. Full behaviour in
[the Daycare+ catalogue](fixes-daycare.md#one-shot-breeding).

## Symptom: Daycare+ IVs are not what Daycare+ documents

**Cause.** Four mixins rewrite the IV rules on purpose. With no held items, all six IVs
are the integer average of the parents', not random. With a Destiny Knot, five are
inherited and one averaged. Competitive mode no longer overrides the item logic.

**Fix.** Nothing to fix — it is intentional. The full table is in
[the Daycare+ catalogue](fixes-daycare.md#3-random-fallback-replaced-by-the-parents-average).
If you want Daycare+'s own behaviour, this mod is the wrong mod to install.

Also note fertility: an egg inherits `(parents' average) - 1`, so a lineage decays by one
point per generation, and a zero-fertility Pokémon can never breed in competitive mode
regardless of `allow_breeding_without_fertility`.

## Symptom: Battle Tower NPC spawns in a wall, or players spawn in the wrong place

**Cause.** `BattleArenaPositionMixin` rewrites the arena offsets to `0` for the player
and `18` for the NPC. `18` is the platform spacing of **one specific arena build** on
one server. On any other arena geometry, the NPC lands wherever 18 blocks from your
anchor happens to be.

**Fix.** There is no config for it. Either build your arena with 18 blocks between
platforms, or change the `@ModifyConstant` return value in
`BattleArenaPositionMixin` and rebuild. See
[arena teleport offsets](fixes-battle-tower.md#8-arena-teleport-offsets).

## Symptom: Battle Tower NPC has a health bar but no visible Pokémon

**Cause.** The NPC ended up in a different chunk from the arena anchor, and Battle
Tower only force-loads the anchor's chunk, so the spawn packet was dropped by the entity
tracker.

**Fix.** `BattleTowerChunkPreloadMixin` already handles this by force-loading a 3×3 chunk
square around the midpoint. If the symptom persists, look for its `ERROR` line — the
preload is wrapped in a catch so that a failure does not break the battle:

```bash
grep -i "preload" logs/latest.log
```

## Symptom: a player's party came back wrong from the Battle Tower

The worst case, and the one to act on quickly.

**Cause.** One of the restore chain's three cooperating parts did not complete: the
backup on lock-in (`TeamFullRestoreMixin`), the restore on reconnect
(`BattleTowerDisconnectFix`), or the guard that blocks a new battle while a restore is
pending (`BattleTowerManagerMixin`).

**Fix.** Before anything else, check whether the backup still exists:

```bash
ls config/cheesecompat_bt_backups/
```

A `<uuid>.dat` present means the restore never completed and the player's real party is
**still in that file**. Do not delete it. The restore retries for 60 attempts at one per
second from the moment they join, and waits for every UUID in the backup to be present in
the live party. If the party never loads within that window, the file is kept and the
next login tries again; if it loads but some Pokémon are still missing at the 60th
attempt, the mod restores the ones it finds and then **deletes the backup** anyway.

Have the player reconnect and watch the log. If they come back and immediately run to
the tower, the `cheesecompat.bt.restore_pending` guard should stop them:

```text
Battle Tower team restore still in progress — please wait a few seconds before starting a new battle.
```

If a party came back with the tower's **forced levels** as its real levels, the restore
ran against a party that was still being rebuilt. Details in
[the Battle Tower catalogue](fixes-battle-tower.md#7-disconnect-and-reconnect-mid-battle).

## Symptom: every battle on the server freezes

**Cause, if you re-enabled `RctForcedSwitchFix`:** that is what it does. It was disabled
in 1.2.52 after doing exactly this in production. Its stuck-battle predicate flags the
normal state of every turn while a player is deciding, and three seconds of thought is
enough to trigger it.

**Fix.** Make sure the registration in `CheeseCompatMod.onInitialize()` is still
commented out:

```bash
grep -n "RctForcedSwitchFix" src/main/java/com/cheesecompat/CheeseCompatMod.java
```

```text
71:        //     RctForcedSwitchFix.register();
```

The `//` is the fix. The full post-mortem, and what a correct version of that watchdog
would have to do differently, is in
[the architecture page](architecture.md#-the-watchdog-the-scar-worth-reading).

**If you did not re-enable it**, this mod is not your cause — look at whatever else
touches Cobblemon's battle loop.

## Symptom: Pokémon held items stop swapping, server-wide

**Cause.** A mixin on MegaShowdown's held-item handling. This mod does not contain one —
it was tried in 1.2.37, broke every held-item swap for everyone, was reverted, and is
deliberately not being retried. If you are seeing this, something else on your server is
doing it, or someone added that mixin back.

**Fix.** Use Cobblemon's public `CobblemonHeldItemManager.registerStackRemap` API
instead, the way `CacademyMegaStoneRemapPatch` does. See
[the refused approach](fixes-cobblemon.md#-2-the-held-item-mixin-that-was-refused-never-re-attempted).

## Symptom: an alpha Pokémon cannot be caught

**Cause.** If `AlphaScaleHitboxFixMixin` is not applying, the underlying CobblemonAlphas
bug is back: balls bounce off with no animation, no message and nothing in the log.

**Fix.** The mixin fires on an entity's first tick after it is spawned or loaded, and
only for wild entities (no owner) flagged `IS_ALPHA: true` in `Pokemon.PersistentData`.
The "already processed" flag lives on the entity object and is not saved, so an alpha
is processed again every time it is loaded — but only once per load: if it is still
uncatchable after that first tick, the mixin will not retry until the chunk reloads. The
manual equivalent, which is what the fix automates, is an NBT round-trip:

```mcfunction
/data merge entity <target> {Pokemon:{ScaleModifier:2.5f}}
```

with the **same** value it already has, changing nothing. That alone makes the alpha
catchable. Nobody has identified what state is actually corrupt. See
[the Cobblemon catalogue](fixes-cobblemon.md#6-alphas-are-silently-uncatchable).

## Symptom: the client resource packs are not enabled automatically

**Cause.** `CheeseCompatClient` only acts when `file/CacademyMons` is **absent** from the
client's enabled pack list. If that entry is present — even from a stale `options.txt` —
the activator returns immediately and changes nothing else.

**Fix.** Check the client log for the line it prints when it does run:

```text
[CheeseCompat] Arkensia resource packs not configured — activating...
```

If that line is missing, the guard short-circuited; remove `file/CacademyMons` from
`options.txt` and restart the client. If it is present but packs are still off, look for
the warning that names which ones it could not find:

```text
[CheeseCompat] N packs not found: [...]
```

A pack that is not in `resourcepacks/` cannot be enabled. The list itself comes from
`config/resourcepackoverrides.json`, synced by AutoModpack, with a hard-coded fallback —
see
[the misc catalogue](fixes-misc.md#the-client-resource-pack-activator).

## Symptom: `CheeseCompatModsFix/fabric.mod.json` says version 1.1.0

**Cause.** You are reading the wrong file. There are two: the packaged one is
`src/main/resources/fabric.mod.json`, which declares `"version": "${version}"` and comes
out as `1.2.53+1.21.1` after `processResources`. The 27-line copy at the **mod root** is
read by nothing, packaged nowhere, and still carries `"version": "1.1.0"` and a
description limited to "Battle Tower, Legendary Monuments, Arc Phone toggles".

**Fix.** Nothing is broken — Fabric and the in-game mod list report the correct version.
The root file is a leftover from an older project layout and should be deleted so nobody
else reads it first. Its stale description is the more misleading half: it understates the
mod's scope by a dozen target mods.

---

## Producing a useful bug report

Because the normal failure mode is silence, a report that just says "fix X is not
working" cannot be acted on. Four things make one that can.

**1. The exact versions.** Not "latest" — the version string of this mod, of Minecraft,
of Fabric Loader, and of the **target mod the fix is for**. That last one is the single
most useful field, because the most likely explanation for any fix that stopped working
is that the target mod updated.

**2. The startup lines.** From the server log:

```bash
grep CheeseCompat logs/latest.log
```

Include the whole output, even if it is one line. The *absence* of the family's
activation line is itself the answer in many cases.

**3. The resolution block, if the mod did not load.** Fabric prints why on the first
screen of the log, before any mod initialises. Copy from the first `Exception` through
the end of the stack trace.

**4. A crash report, if there was one.** `crash-reports/crash-<timestamp>-server.txt`,
whole file. Mixin failures appear there with the mixin class name in the stack, which
points straight at the fix.

Then: [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues).

⚠️ Be realistic about what to expect. This mod patches other people's internals against
specific versions, for one modpack, and **no support is offered for it**. A report that
a fix broke after a target mod updated is expected behaviour, not a bug. The honest
answer will often be "remove the jar". The reports that are worth filing are the ones
where a fix is wrong *against the version it claims to target* — those are real, and
fixable.

---

← [Architecture](architecture.md) · [CheeseCompatModsFix](../README.md) →
