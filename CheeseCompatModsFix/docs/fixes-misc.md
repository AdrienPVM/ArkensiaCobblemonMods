[← CheeseCompatModsFix](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](fixes-misc.fr.md)

# Fix catalogue — everything else

> ⚠️ Same warning as the other catalogue pages: each fix is pinned to one version of one
> third-party mod, and nothing here is tested against any other.

Eight fixes across six mods, plus the one client-side feature that is not a mixin.

**Target mods on this page**

| Mod | Mod id | Version compiled against |
|---|---|---|
| Legendary Monuments | `legendarymonuments` | 7.2, Modrinth id `oaBxuxgH` |
| Minerally | `minerally` | 1.21, id `wvK26f7w` |
| Enhanced Celestials | `enhancedcelestials` | 6.0.2.5, id `WU6cJeLI` (+ Data Anchor 2.0.0.12, `CpSbR8Mk`) |
| ExtraQuests | `extraquests` | 1.5.3, id `bbzqP178` |
| FTB Quests | `ftbquests` | `ftb-quests-fabric-2101.1.13.jar`, from `localdeps/` |
| JourneyMap | `journeymap` | 1.21.1-6.0.0-beta.47, id `oBV1MaLz` |
| Numismatic Overhaul | `numismatic-overhaul` | 0.3.4, id `DPXG6kTJ` |

---

## Legendary Monuments

All four are gated on `legendarymonuments` in `CheeseCompatMixinPlugin` — the plugin
matches on the mixin class names containing `ArcPhone`, `Sanctuary` or
`EntrepreneurProfessionLock`.

### 1. Sanctuary protection covering the whole world

**Symptom.** A sanctuary block's protection radius appears to extend infinitely — block
breaking is denied in another dimension, or tens of thousands of blocks away.

**Cause.** `SanctuaryBlock.isWithinRadius()` computes `dx * dx + dz * dz` in `int`
arithmetic. Past about 46 000 blocks, `dx * dx` overflows the signed int range and
becomes negative, which is always `<= radiusSq`. Every distant position reads as inside.

**How.** `SanctuaryDistanceMixin` — `@Inject` at `HEAD` of `isWithinRadius`,
`cancellable`, `require = 0`, redoing the comparison in `long` arithmetic and returning
the correct answer.

**Status.** Active.

A textbook integer overflow, and a good illustration of why it went unnoticed: the bug
only appears at a distance nobody tests at, and when it appears the symptom
("protection is on where it shouldn't be") does not look like an arithmetic problem.

### 2. Arc Phone healing, switchable off

**Symptom intended.** The Arc Phone lets any player heal their whole party anywhere,
which removes the point of Pokémon Centers.

**How.** `ArcPhoneHealMixin` on `HealPacket` — `@Inject` at `HEAD` of
`lambda$registerServerReceiver$1`, `cancellable`, `require = 0`. When
`arcPhoneHealEnabled` is false in the config, the handler cancels and sends
`cheesecompat.arcphone.heal_disabled`:

```text
Arc Phone healing is disabled on this server.
```

**Status.** Active, and **on** by default (`arcPhoneHealEnabled = true`). See
[configuration](configuration.md).

### 3. Arc Phone PC access, switchable off

**How.** `ArcPhonePCMixin` on `PCOpenPacket`, structurally identical to fix 2:
`@Inject` at `HEAD` of `lambda$registerServerReceiver$1`, `cancellable`,
`require = 0`, gated on `arcPhonePCEnabled`. Message
`cheesecompat.arcphone.pc_disabled`:

```text
Arc Phone PC access is disabled on this server.
```

**Status.** Active, on by default.

Both of these inject into a **lambda**, named `lambda$registerServerReceiver$1`. Lambda
names are assigned by the compiler in source order and are not part of any API. A
reordering of lambdas in that method — not even a behaviour change — renames the target
and the injection stops matching. `require = 0` means that failure is silent: the Arc
Phone toggle quietly stops working. If you set a toggle to `false` and nothing changes,
this is the first thing to suspect.

### 4. Entrepreneur villagers changing profession

**Symptom.** The entrepreneur NPC loses its job and picks up another one — a villager
who was a shopkeeper yesterday is a farmer today.

**Cause.** The entrepreneur is a vanilla `minecraft:villager` carrying the custom
profession `legendarymonuments:entrepreneur`. Its workstation
(`legendarymonuments:suitcase_block`, POI `entrepreneur_poi`) is added to the vanilla
`acquirable_job_site` tag. Vanilla behaviour then applies: a villager that was never
locked in by trading, and no longer sees its claimed workstation in range, drops its
profession and takes any other nearby job site.

**How.** `EntrepreneurProfessionLockMixin` on **vanilla** `VillagerEntity` — `@Inject`
at `HEAD` of `setVillagerData`, `cancellable`, cancelling any call that would move a
villager *out of* the entrepreneur profession once it holds it. Acquiring it still works
(`NONE` → entrepreneur passes), trading and AI are untouched, and non-entrepreneur
villagers are unaffected.

**Status.** Active. It targets a vanilla class, so this mixin is applied on every
villager on the server whenever Legendary Monuments is installed; the profession check is
the first thing it does.

---

## Minerally

### 5. Crash when a hammer breaks an ore pile

**Symptom.** Server crash — NPE — when JustHammers breaks a Minerally `OrePile` as part
of its area-of-effect mining.

**Cause.** JustHammers calls `onBroken()` for the AoE blocks without calling `onBreak()`
first, so `OrePile._player` is still null when `onBroken` dereferences it.

**How.** `OrePileMixin`, `@Mixin(targets = "theone_ss.minerally.block.OrePile")` —
`@Inject` at `HEAD` of `onBroken`, `cancellable`, cancelling when the player field is
null. The pile does not produce its player-dependent drop in that case; it also does not
take the server down.

**Inert when absent.** Yes — gated on `minerally`.

**Status.** Active.

This is the mixin quoted in [`localdeps/README.md`](../../localdeps/README.md) as the
reason a string-targeted mixin still needs its jar at compile time: the Mixin annotation
processor resolves and validates `targets` during compilation and fails with
`Mixin target ... could not be found` if the class is absent. Minerally therefore sits on
the compile classpath even though no line of code imports it.

---

## Enhanced Celestials

Two fixes, same root problem: dimensions created at runtime by
[CheeseGuard](../../CheeseGuard/) never get lunar data.

### 6. Lunar settings inherited by `cheeseguard:*` dimensions

**Symptom.** A CheeseGuard dimension never has a lunar event. The StarAcademy Lunar
Forecast Hologram block standing in it shows nothing.

**Cause.** `EnhancedCelestialsLunarForecastWorldData.factory()` looks the dimension up in
the `enhancedcelestials:lunar/dimension_settings` registry. A dimension created at
runtime has no entry, the lookup returns `Optional.empty()`, the factory returns null.

**How.** `EnhancedCelestialsFactoryFallbackMixin` on
`dev.corgitaco.enhancedcelestials.lunarevent.EnhancedCelestialsLunarForecastWorldData` —
a `@Redirect` on the single `Registry.getEntry(RegistryKey)` call inside `factory()`. If
the dimension's namespace is `cheeseguard` and it has no entry of its own, the redirect
returns `cheeseguard:arkensia`'s settings instead. Every CheeseGuard dimension then
follows the same cosmic event cycle as the main one, which is what the admin wanted.

**Inert when absent.** Yes — gated on `enhancedcelestials`.

**Status.** Active.

Data Anchor is a compile dependency here and nothing else: it provides
`SyncedLevelTrackedData`, the superclass of the mixin's target. It is not patched.

### 7. Forcing the lunar data to materialise

**Symptom.** Even with fix 6, a rarely visited dimension still has no lunar data.

**Cause.** Enhanced Celestials materialises its per-level data **lazily**. The factory
runs on the first call to `EnhancedCelestials.lunarForecastWorldData(level)`, and that
call comes only from `LivingEntity#tick`, the natural mob spawner, and a few block and
slime mixins. Until a player visits the dimension *or* a mob ticks in it, the dimension
effectively does not exist as far as Enhanced Celestials is concerned.

**How.** `EnhancedCelestialsWarmup` — not a mixin. It resolves
`EnhancedCelestials.lunarForecastWorldData(World)` by reflection once at init, then calls
it on `ServerWorldEvents.LOAD` for **every** level the server loads. The return value is
discarded; the call exists only to prime the factory.

**Inert when absent.** Yes, twice: `register()` returns immediately if
`enhancedcelestials` is not loaded, and a signature mismatch on the reflective lookup
logs a warning and disables the warmup rather than throwing. A per-dimension failure is
logged at `DEBUG` only, because a dimension with no `dimension_settings` legitimately
returns null.

Log lines:

```text
[CheeseCompat] EnhancedCelestials lunar warmup registered
[CheeseCompat] Warmed lunar data for cheeseguard:arkensia
```

**Status.** Active. Note it is registered **unconditionally** in `onInitialize` — the mod
check lives inside `register()`, not around the call.

---

## ExtraQuests and FTB Quests

### 8. Daily quests that never reset

**Symptom.** Some players' 8-hour daily quests stop resetting. The quest stays
claim-locked until an admin intervenes by hand. Other players on the same server are
fine.

**Cause.** A startup race, and a destructive one. `ExtraQuests.loadStorage` and
`ServerQuestFile.INSTANCE` are both initialised on
`ServerLifecycleEvents.SERVER_STARTED`, and Fabric does not guarantee listener order. If
ExtraQuests runs first, `ServerQuestFile.INSTANCE` is still null. `Utils.questReset`
then either dereferences null or gets null for the quest, logs an error — **and removes
the entry from `TimerStorage.json` without having reset the quest**. The timer is gone,
so nothing will ever retry.

**How.** `ExtraQuestsTimerResetGuardMixin` on `Utils` — `@Inject` at `HEAD` of
`questReset`, `cancellable`. If `ServerQuestFile.INSTANCE` is null it returns `false`
**without** touching the storage. On the next ExtraQuests tick, or at worst the next
restart, FTB is ready and the reset succeeds.

One static method, one early return. The whole fix is "fail without destroying
evidence".

Logging, added in 1.2.39, is built for diagnosis: a `WARN` on each block, rate-limited
to the first five and then every fiftieth, plus one `INFO` the first time it blocks and
one the first time FTB is ready again. That tells you whether the race actually happens
on your server, and how often.

**Inert when absent.** Yes — gated on `extraquests`. FTB Quests itself is only a compile
dependency, needed because the mixin reads `ServerQuestFile`.

**Status.** Active.

---

## JourneyMap

### 9. Fakemon minimap icons rendered from the wrong texture

**Symptom.** A fakemon from the Seavl's Bizarremons pack — Vaporipotent, Clockage,
Dragash and the rest — shows up on the JourneyMap minimap as a blurry, wrong-looking
icon. Cobblemon's own species look fine.

**Cause.** `EntityHelper.getIconData(Entity)` resolves a Pokémon's icon from
`RenderFacade.getEntityTexture(...)`, which for Cobblemon returns the species variation
texture — for example
`seavlbizarremon:textures/pokemon/vaporipotent/vaporipotent.png`. JourneyMap then
transforms that path into an icon path, but the transformation is **hard-coded to the
`cobblemon` namespace**: decompiled bytecode of `EntityHelper`, offsets 613-649, shows an
`.equals("cobblemon")` guard before the `.replace("pokemon", "entity_icon")` call. For
any other namespace the full-resolution model texture is used verbatim as a minimap
icon.

**How.** Two client-only mixins:

- `client.IconDataAccessor` — an accessor for the package-private
  `EntityHelper.IconData.icon` field.
- `client.JourneyMapSeavlianIconMixin` — `@Inject` at `RETURN` of `getIconData`,
  `require = 1`. It reads the returned `IconData.icon`; if the namespace is in
  `FAKEMON_NAMESPACES` (currently `seavlbizarremon`) and the path contains `/pokemon/`,
  it rewrites the icon to `<namespace>:textures/entity_icon/<rest>.png`.

Post-`RETURN` rather than inline on purpose: `getIconData` also sets `IconData.type` and
`.texture` and runs other logic worth keeping, so post-processing the result survives
JourneyMap's internal refactors better than replacing the method would.

The icons have to actually exist at `assets/<namespace>/textures/entity_icon/...`. On
this server they come from the dCIM resourcepack. Adding another fakemon pack means
adding its namespace to `FAKEMON_NAMESPACES` **and** providing the icons.

**Inert when absent.** Yes — both are gated on `journeymap` in the mixin plugin.

**Status.** Active.

This is the one mixin in the mod with `require = 1`, meaning a failed injection is an
error rather than a silent skip. On a client, that is a hard failure at startup rather
than a wrong icon.

⚠️ JourneyMap is pinned to `6.0.0-beta.47` and not the `6.0.0` release, for a reason
that has nothing to do with the fix: the release jar was built with Loom 1.14.10, which
this project's Loom 1.10.5 refuses to read.

---

## Numismatic Overhaul

### 10. Shop block rendering costing frames

**Symptom.** Frame rate drops in a dense market area with many Numismatic shop blocks in
view.

**Cause.** `ShopBlockEntityRender.render()` issues a full `ItemRenderer.renderItem` — a
rotating 3D model — for every visible shop, every frame, with no custom distance cull.
The vanilla default cut-off is 64 blocks, so that is O(N) `renderItem` calls per frame
across a market.

**How.** `client.NumismaticShopCullMixin` — `@Inject` at `HEAD` of `render`,
`cancellable`, cancelling beyond a fixed distance (about 24 blocks) from the camera. The
shop block's static model is still drawn by the chunk mesh; only the block entity
renderer's floating item is skipped. Any exception falls through to the original render,
so the worst case is current behaviour.

**Inert when absent.** Yes — gated on `numismatic-overhaul`.

**Status.** Active. Client-only, purely visual.

---

## The client resource-pack activator

Not a fix for a third-party mod, but the one piece of client-side behaviour in this jar
that is not a mixin, so it belongs here.

**Problem.** CurseForge does not apply `options.txt` from modpack overrides, so a new
player starts with every Arkensia resource pack disabled, and the modpack looks nothing
like it is supposed to.

**How.** `CheeseCompatClient` implements `ClientModInitializer` and is registered as the
`client` entrypoint in `src/main/resources/fabric.mod.json`:

```json
"entrypoints": {
  "main": ["com.cheesecompat.CheeseCompatMod"],
  "client": ["com.cheesecompat.CheeseCompatClient"]
}
```

On `CLIENT_STARTED` it checks whether `file/CacademyMons` is already in the enabled pack
list, and returns immediately if it is — that single check is what makes this a one-time
setup rather than something that fights the player's choices on every launch. Otherwise it
reads the ordered pack list from `config/resourcepackoverrides.json` (synced from the
server by AutoModpack, which keeps the server as the single source of truth), falls back
to a hard-coded list of about 55 packs if that file is absent, rescans the packs folder,
enables them through the `ResourcePackManager`, syncs the manager's state back into the
options, writes them, and reloads resources.

It logs what it did, and — more useful — what it could not do:

```text
[CheeseCompat] Arkensia resource packs not configured — activating...
[CheeseCompat] Loaded 55 packs from resourcepackoverrides.json
[CheeseCompat] N packs not found: [...]
[CheeseCompat] 54 resource packs activated (of 55 requested).
```

**Status.** Active, client-side only.

⚠️ This is the only code in the mod that rewrites a player's own settings file and forces
a resource reload at startup. It is guarded by one sentinel pack name. If that pack is
ever renamed in the modpack, the guard stops matching and every client re-runs the whole
activation on every launch, overwriting any pack choice the player has made since. The
sentinel is `file/CacademyMons`, hard-coded in `CheeseCompatClient`.

The hard-coded fallback list is also Arkensia-specific down to individual zip filenames,
and does nothing useful on any other modpack.

---

← [Fix catalogue — Daycare+](fixes-daycare.md) · [Architecture](architecture.md) →
