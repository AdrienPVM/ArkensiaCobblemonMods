[← CheeseCompatModsFix](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](fixes-daycare.fr.md)

# Fix catalogue — Daycare+

> ⚠️ This family does not fix bugs. It rewrites Daycare+'s competitive breeding rules
> into different rules, because the server wanted different rules. If you install this
> jar expecting Daycare+ to behave as its author documented, it will not.

**Target mod:** Daycare+ (`daycareplus`).
**Version compiled against:** 1.1.0 (Fabric), Modrinth version id `HqAFvLN2`.
**Also needed at compile time:** sgui 1.6.1+1.21.1 (Patbox), for the GUI mixin. It is
shipped inside Daycare+ as a jar-in-jar; the build pulls it from the Nucleoid Maven
instead.
**Inert when absent:** yes. `CheeseCompatMixinPlugin` returns `DAYCAREPLUS_LOADED` for
any mixin whose class name contains `Daycare` or `Fertility`, and
`DaycareSimpleModeStore` is only wired to the lifecycle events behind an
`isModLoaded("daycareplus")` check in `CheeseCompatMod`.

Read this page as a whole before changing any one mixin. Eight of them touch two
methods — `PotentialPokemonProperties.setIVs` and `PastureExtension.produceEgg` — and
the result is the product of all of them, not the sum.

---

## The IV rules, rewritten

Daycare+'s "competitive" mode replaces the main-series IV inheritance with an averaging
system of its own, in which held items stop mattering. The server wanted the opposite:
main-series item behaviour **plus** averaging as the fallback instead of random. Four
mixins get there.

### 1. Competitive mode stops overriding inheritance

**How.** `DaycareIVFixMixin` on
`com.provismet.cobblemon.daycareplus.breeding.PotentialPokemonProperties`, two
`@Redirect` on calls to `DaycarePlusOptions.doCompetitiveBreeding()` — one inside
`setIVs`, one inside `setNature` — both forced to return `false`.

The effect: competitive mode still tracks fertility and still consumes held items, but
Destiny Knot, Power items and Everstone behave exactly as in standard breeding.

**Status.** Active.

### 2. The same, for `fromParents`

**How.** `DaycareFromParentsMixin` on
`PotentialPokemonProperties$PotentialIV`, one `@Redirect` on
`doCompetitiveBreeding()` inside `fromParents`, forced to `false`.

`fromParents` had its own copy of the averaging logic, so fix 1 alone left it in place.
With both, the standard inheritance path is preserved: Power items force their stat,
`WILDCARD` for the rest.

**Status.** Active.

### 3. Random fallback replaced by the parents' average

**How.** `DaycareIVAverageMixin` on `PotentialPokemonProperties`, two changes to
`setIVs`:

- `@ModifyConstant` on the constant `3`, returning `0`. That constant is the default
  `forcedIVs` count. Left at 3, the second loop always picks three random stats and
  copies one parent's exact value — the "min/max" result the server did not want. The
  Destiny Knot branch sets `forcedIVs` to 5 with `iconst_5`, a different constant, so
  wearing a Destiny Knot still inherits five stats.
- `@Redirect` on the third `IVs.set(Stat, int)` call (`ordinal = 2`), which is the random
  fallback, writing `(primary + secondary) / 2` for that stat instead. The two parents
  are read through `@Shadow @Final private Pokemon primary` / `secondary`.

The combined table:

| Items held | Result |
|---|---|
| none | 0 forced, all 6 IVs averaged |
| Destiny Knot | 5 inherited from a parent at random, 1 averaged |
| Power item | that stat forced from the holder, the others averaged |
| Power item + Destiny Knot | forced stat + 4 inherited + 1 averaged |

**Status.** Active.

⚠️ `@ModifyConstant` matching the literal `3` is only unambiguous as long as `setIVs`
contains exactly one `3`. A refactor upstream that introduces a second one will silently
rewrite the wrong constant — and the symptom will be strange IV distributions, not an
error.

### 4. Destiny Knot consumption — written, never enabled

**Status: present in the source tree and NOT registered.**

`DaycareDestinyKnotConsumeMixin` exists, is complete, and targets
`PastureExtension.produceEgg` at `TAIL` to swap each parent's Destiny Knot for
`ItemStack.EMPTY` after an egg is produced. Its reasoning is sound: Daycare+ tags the
Destiny Knot only in `noncompetitive_breeding`, so the built-in consume loop — which
checks `competitive_breeding` — leaves it intact, while fix 1 above has restored the
Destiny Knot's five-IV transfer in competitive mode. In the main-series games it would be
consumed.

**It is not listed in `cheesecompatmodsfix.mixins.json`.** Mixin only applies what the
config file names, so this class is never loaded and never runs. There is nothing in the
code or in the commit trail to say whether that is deliberate or an oversight; the
honest answer is that the intent is unclear.

What *is* shipped is the matching datapack tag,
`src/main/resources/data/daycareplus/tags/item/competitive_breeding.json`:

```json
{
  "replace": false,
  "values": [
    "cobblemon:destiny_knot"
  ]
}
```

That adds the Destiny Knot to Daycare+'s `competitive_breeding` item tag, which makes
the mod's **own** consume loop pick it up. So the behaviour the orphaned mixin was
written for is achieved by the tag instead — which would explain why the mixin was left
out. Treat that as the most likely reading, not as a documented decision.

---

## Fertility

### 5. Fertility can go negative

**Symptom.** A Pokémon's fertility drifts below zero, which then breaks every comparison
that assumed it could not.

**Cause.** Two routes: a server configured with
`allow_breeding_without_fertility = true`, or a race that lets a zero-fertility parent
produce one more egg.

**How.** `FertilityClampMixin` on
`com.provismet.cobblemon.daycareplus.feature.FertilityProperty` — a `@ModifyArg` on the
second argument of the `new IntSpeciesFeature(KEY, value)` call inside `decrement()`,
replacing `get - 1` with `max(0, get - 1)`.

**Status.** Active.

### 6. Zero fertility can never breed, whatever the config says

**How.** `DaycareStrictFertilityMixin` on `BreedingUtils` — `@Inject` at `HEAD` of
`isAllowedToBreed`, `cancellable`. If competitive breeding is on **and**
`FertilityProperty.get(pokemon) <= 0`, it returns `false`.

Note the condition: it is gated on `doCompetitiveBreeding()` being true. On a
non-competitive server this mixin does nothing, and
`allow_breeding_without_fertility = true` still works as Daycare+ intends.

**Status.** Active.

### 7. The egg inherits one less fertility than the parents' average

**Symptom intended.** A breeding lineage should decay: each generation one point less
fertile than the one before.

**Cause of the original behaviour.** `produceEgg` sets the egg's fertility to
`Math.min(parent1, parent2)`.

**How.** `DaycareEggFertilityAvgMixin` — a `@Redirect` on the `Math.min(II)I` call
inside `produceEgg`, returning `max(0, (a + b) / 2)`.

The arithmetic is worth spelling out, because the formula in the code does not look like
the rule it implements. `Math.min` is called *after* `FertilityProperty.decrement` has
run on both parents, so the values arriving are already `pre1 - 1` and `pre2 - 1`. The
rule wanted is `(pre1 + pre2) / 2 - 1`. Substituting:
`(a + 1 + b + 1) / 2 - 1 = (a + b) / 2`. Hence the simplification. The clamp at 0 can
only fire if a parent already had 0, which `isAllowedToBreed` prevents (fix 6).

**Status.** Active.

⚠️ This fix depends on the *order* of operations in `produceEgg` — decrement first,
`Math.min` second. If Daycare+ ever reorders those two, the formula silently becomes
`(pre1 + pre2) / 2`, one point too generous, with no error anywhere.

---

## One-shot breeding

Daycare+ pastures breed continuously until fertility runs out. The server wanted a
pasture that produces one egg and then stops, so an admin can hand out a pasture without
it churning.

### 8. A toggle in the pasture GUI

**How.** `DaycareGUIToggleMixin` on `com.provismet.cobblemon.daycareplus.gui.DaycareGUI`
— `@Inject` at `TAIL` of `create`, adding a clock item in slot 5. Parameters are typed
as `Object` rather than with their real Daycare+ and Cobblemon types, to avoid making
javac resolve intermediary Minecraft supertypes
(`IMixinPastureBlockEntity` → `Inventory`) that are not on the compile classpath.

Click cycle, with the labels read from the lang files:

| State | Label | Click leads to |
|---|---|---|
| `AUTO` | Auto breeding | `SIMPLE_ARMED` |
| `SIMPLE_ARMED` | One-shot breeding (armed) | `AUTO` |
| `SIMPLE_DISARMED` | One-shot breeding (stopped) | `SIMPLE_ARMED` |

Tooltips:

```text
Breeds continuously until fertility is depleted. Click to switch to one-shot.
Will produce a single egg, then stop. Click to switch to auto.
Egg produced. Click to re-arm for another egg.
```

**Status.** Active.

### 9. Enforcing the toggle

**How.** `DaycareSimpleModeTickMixin` on `PastureExtension`, two injections:

- `@Redirect` on the `predictEgg()` call inside `tick()`. When the pasture is in simple
  mode and not armed, it returns `Optional.empty()` instead of calling through, so no
  egg is predicted and none is produced.
- `@Inject` at `TAIL` of `produceEgg`. If the pasture is in simple mode, it disarms it
  and calls `markDirty()`.

Both read the private `blockEntity` field by reflection, for the same reason as fix 8:
importing `PokemonPastureBlockEntity` would force javac to resolve `class_2586`.

**Status.** Active.

### Where the toggle state lives

`DaycareSimpleModeStore` keeps it in a side table, not on the block entity, keyed by
`(dimension id, BlockPos as a long)` and persisted to
`config/cheesecompat_daycare.json`. It is initialised on
`ServerLifecycleEvents.SERVER_STARTED` and flushed on `SERVER_STOPPING`.

Defaults are `simpleMode = true`, `simpleArmed = true`, so a **new pasture produces one
egg and then stops**. That is the opposite of Daycare+'s own default, and it is the
single most likely reason for a "my pasture stopped breeding" report on a server that
installed this jar without reading this page.

The store's methods take primitive key parts (`String dimId`, `long posLong`) rather
than Minecraft types, deliberately, so a caller does not have to import
`net.minecraft.*` classes that the `compileOnly` Cobblemon setup cannot resolve.

**Threading.** The backing map is a `ConcurrentHashMap`. Reads happen from the server
thread (the pasture tick) and from whatever thread sgui runs the GUI click handler on.

---

← [Fix catalogue — Safari, Academy and bosses](fixes-safari-and-bosses.md) · [Fix catalogue — everything else](fixes-misc.md) →
