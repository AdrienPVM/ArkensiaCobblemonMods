[← CheeseCompatModsFix](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](getting-started.fr.md)

# Getting started

By the end of this walkthrough you will have the mod built and running on a fresh
server, you will know **which** of its fix families actually activated on your mod
stack, and you will have switched one fix off and watched the effect land in game.

That last part matters more than it sounds. Almost everything this mod does is
invisible when it works — a crash that does not happen, a party that comes back
correct. The Arc Phone toggle is one of the very few fixes with a visible, immediate,
reversible result, which makes it the right thing to test first: if you can make that
message appear and disappear, you know the mixin layer is live.

Allow about twenty minutes, most of it server startup.

---

## 1. Check your Java

```bash
java -version
```

```text
openjdk version "21.0.5" 2024-10-15
```

Anything below 21 will not compile this mod and will not run it.

## 2. Put the two CurseForge jars in place

The build refuses to start without them. From CurseForge, download the **Fabric
1.21.1** file of FTB Quests and of StarAcademy, and drop both into `localdeps/` at the
repository root, with exactly these names:

```text
localdeps/ftb-quests-fabric-2101.1.13.jar
localdeps/academy-mc1.21.1-v1.4.0+build.467-fabric.jar
```

Verify:

```bash
ls localdeps/
```

```text
README.md
academy-mc1.21.1-v1.4.0+build.467-fabric.jar
ftb-quests-fabric-2101.1.13.jar
```

If either is missing, step 3 stops with a message naming it. That is the intended
behaviour, not a bug — see
[why those two cannot be downloaded for you](installation.md#-building-needs-two-jars-you-have-to-supply).

## 3. Build

```bash
cd CheeseCompatModsFix
./gradlew build
```

First run downloads Minecraft, the Yarn mappings and sixteen mod jars, so expect a few
minutes. You are looking for:

```text
BUILD SUCCESSFUL in 2m 14s
```

and then:

```bash
ls build/libs/
```

```text
cheesecompatmodsfix-1.2.53+1.21.1.jar
cheesecompatmodsfix-1.2.53+1.21.1-sources.jar
```

Take the first one. Ignore the sources jar.

If the build failed, it almost certainly failed at configuration time on step 2, and the
message says which file it wanted.

## 4. Drop it in a server

This mod alone needs nothing more than Fabric API. Step 8, though, needs Legendary
Monuments, and Legendary Monuments 7.2 will not load on its own: its `fabric.mod.json`
requires Fabric Loader 0.16.10 or later, Cobblemon, MegaShowdown, Trinkets, Chipped and
CobbleFurnies. So the smallest stack that runs this tutorial is:

```text
mods/
├── fabric-api-<version>+1.21.1.jar
├── cobblemon-<version>.jar
├── mega_showdown-<version>.jar
├── trinkets-<version>.jar
├── chipped-<version>.jar          (and whatever Chipped itself requires)
├── cobblefurnies-<version>.jar
├── LegendaryMonuments-7.2.jar
└── cheesecompatmodsfix-1.2.53+1.21.1.jar
```

Fabric refuses to start and names the missing one if you leave any of them out.

Start it:

```bash
java -Xmx4G -jar fabric-server-launch.jar nogui
```

## 5. Find the line that proves it loaded

In the console, or in `logs/latest.log`:

```text
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=enabled, PC=enabled, Sanctuary fix=ON
```

That is `onInitialize()` reaching its last statement. If it is absent, the mod did not
load at all — jump to
[the mod does not load](troubleshooting.md#symptom-the-mod-does-not-load).

Note what that line does **not** tell you: whether any fix applied. It prints
unconditionally.

## 6. Find out which fixes actually activated

This is the step people skip, and then spend an hour wondering why a fix is not working.

```bash
grep CheeseCompat logs/latest.log
```

On the stack from step 4, the lines that matter are these three, in this order:

```text
[CheeseCompat][MegaPatch] Added 1 aspect(s) to mega_aspects (now 4 entries: [...])
[CheeseCompat][MegaStoneRemap] Registered 70 CacademyMons stone remaps (includes lucarionitez=261023, ...)
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=enabled, PC=enabled, Sanctuary fix=ON
```

The first two are the MegaShowdown and Cobblemon patches, which run just before the
`Loaded` line. There is no `Restricted /stopbattle <player> to operators` line: that one
only appears when Battle Tower has registered a `stopbattle` command with argument nodes
to restrict. (`grep` may return a few more lines, because some classes log under a logger
named `CheeseCompatModsFix`.)

Add CobbleSafari and restart, and lines like these appear:

```text
[CheeseCompat] Registered cobblemon:tatami_mat and tatami_block for CobbleSafari compatibility
[CheeseCompat] Safari dimensional ban events registered (Cobblemon 1.6.x compat)
[CheeseCompat] Safari ball fix registered for CobbleSafari dimensions
[CheeseCompat] Slingshot block registered for CobbleSafari dimensions
[CheeseCompat] Dungeon capture block registered for dungeon_underground
[CheeseCompat] Registered CobbleSafari portal frame and portal blocks
[CheeseCompat] Registered CobbleSafari spawn influences (1.6.1 compat)
```

**The rule to carry away: a missing line means a missing fix.** The mixin plugin gates
everything on `FabricLoader.isModLoaded(<id>)`, and a mod whose id does not match the
string the code expects is, from this mod's point of view, not installed. The ids it
checks are listed in [installation](installation.md#optional--the-mods-it-patches).

## 7. Confirm the command tree registered

Join the server and run:

```mcfunction
/stopbattle
```

```text
Battle stop requested.
```

Green text, immediately. You are not in a battle, so nothing happens beyond the
message — the command reports *requested*, not *stopped*, and does not check. Seeing it
means `onInitialize()` registered its command and Fabric's command API module is
present. Details in [commands](commands.md#stopbattle).

## 8. Switch a fix off and watch it land

Now the part with a visible result. The Arc Phone, from Legendary Monuments, heals a
player's whole party anywhere, for free. Many servers do not want that.

First confirm the current behaviour. In game, open the Arc Phone and heal your party.
It works, silently, as the mod intends.

Stop the server. Open the config:

```text
config/cheesecompatmodsfix.json
```

```json
{
  "arcPhoneHealEnabled": true,
  "arcPhonePCEnabled": true,
  "sanctuaryOverflowFix": true
}
```

Change the first value to `false`:

```json
{
  "arcPhoneHealEnabled": false,
  "arcPhonePCEnabled": true,
  "sanctuaryOverflowFix": true
}
```

Start the server. The startup line now reads:

```text
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=DISABLED, PC=enabled, Sanctuary fix=ON
```

Join, open the Arc Phone, press heal. Instead of healing you get, in red:

```text
Arc Phone healing is disabled on this server.
```

```text
Le soin via l'Arc Phone est désactivé sur ce serveur.
```

(The second is what a French client sees — the message is a translation key, so each
player gets it in their own language.)

The heal button is still there and still pressable: only the **server-side** packet
handler is blocked. That asymmetry is normal for a server-side patch and worth
internalising — this mod never changes a client UI it does not have a client mixin for.

**The server has to be restarted.** There is no reload for this config; it is read once
at init. If you edited the file while the server was running, nothing happened yet: the
edit stays in the file and takes effect at the next start. Editing it while the server is
stopped and then starting it is the clean way.

If the message does not appear after a restart, see
[a config key has no effect](troubleshooting.md#symptom-a-config-key-has-no-effect) —
the likely cause is that the lambda-named injection point no longer matches your
Legendary Monuments build, and `defaultRequire: 0` made that failure silent.

## 9. Put the toggle back

```json
{
  "arcPhoneHealEnabled": true,
  "arcPhonePCEnabled": true,
  "sanctuaryOverflowFix": true
}
```

Restart. The startup line reads `heal=enabled` again and the Arc Phone heals.

You have now exercised the whole chain: build, load, gating, config, mixin, player-facing
message.

---

## Where to go next

You have seen the only part of this mod that behaves like a feature. Everything else is
a patch, and the useful reading is the catalogue — one entry per fix, with the mod and
version it targets, the symptom it addresses, how it hooks in, and whether it is still
active:

- [Battle Tower](fixes-battle-tower.md) — twelve fixes around party backup, restore and
  arena geometry. The largest family.
- [Cobblemon and battles](fixes-cobblemon.md) — mega evolution, raids, alphas, Rad Gyms,
  and the two patches that were **disabled or refused** after breaking production. Read
  those two before writing anything similar.
- [Safari, Academy and bosses](fixes-safari-and-bosses.md) — CobbleSafari 0.1.5
  back-ported onto Cobblemon 1.6.1, and the bytecode patches for Cobblewarden Boss.
- [Daycare+](fixes-daycare.md) — nine mixins that rewrite the breeding rules. ⚠️ A new
  pasture produces one egg and stops, which is the opposite of Daycare+'s own default.
- [Everything else](fixes-misc.md) — six more mods, ten fixes between them.

Then [architecture](architecture.md) for how the gating works and what is known to be
weak, and [troubleshooting](troubleshooting.md) when something is silently not
happening — which, given `defaultRequire: 0`, is the normal failure mode here.

---

← [Installation](installation.md) · [Commands](commands.md) →
