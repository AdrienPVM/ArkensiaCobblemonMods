[← CheesePurse](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

---

## The server refuses to start, and the log names `numismatic-overhaul`

**Cause.** `fabric.mod.json` declares `"numismatic-overhaul": "*"` under `depends`.
Fabric Loader resolves dependencies before any mod initialises, and an unsatisfied hard
dependency aborts the launch. The message looks roughly like:

```text
Mod 'Numismatic Cheesepurse' (numismatic-cheesepurse) 1.0.0+1.21.1 requires
any version of mod numismatic-overhaul, which is missing!
```

**Fix.** Install [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) for
1.21.1, along with its own dependencies — Cardinal Components and owo-lib at the time of
writing. Numismatic's missing dependencies produce the same class of message naming
those instead.

The same applies on the client. CheesePurse is `"environment": "*"`, so a client without
Numismatic will not launch either.

---

## There is no button in my inventory screen

**Cause, most often.** The jar is on the server but not on the client. The button comes
from a client-only mixin; a server-side install alone produces a working deposit loop
that nobody can switch on.

**Fix.** Put `numismatic-cheesepurse-1.0.0+1.21.1.jar` in the client's `mods/` as well,
and confirm it appears in the Mods list as **Numismatic Cheesepurse**. If you are looking
for it as "CheesePurse", you will not find it — see
[the three names](installation.md#the-three-names-of-this-mod).

**Second cause.** The mixin failed to apply. This does not fail silently: the mixin
config sets `"required": true` with `defaultRequire: 1`, so a failed injection crashes
the client at startup with a `MixinApplyError` naming `InventoryScreenMixin`. If your
client is running at all, the mixin applied.

**Third cause.** You are looking at the wrong screen. The mixin targets `InventoryScreen`
— the survival inventory opened with E. The creative inventory is a different class and
has no button.

**Fourth cause.** A resource pack that changes the inventory GUI's dimensions. The mixin
computes the button position from a hard-coded 176×166, so with an unusually sized
inventory texture the button can land outside the visible panel. Disable the pack to
confirm.

---

## The button shows grey but my coins still disappear

This is the one that generates support tickets. Read
[the model](auto-collect.md#the-model) once and it stops being mysterious.

**Cause.** The client boolean and the server set are two separate pieces of state, synced
only client-to-server, only on click. The server never tells a client what it believes.
The common sequence is:

1. The player turns the toggle on. Server adds their UUID; client boolean is `true`.
2. The player quits. **The server does not remove the UUID** — nothing handles disconnect.
3. The player restarts their Minecraft client. The static boolean is rebuilt as `false`.
4. They rejoin. The button draws grey; the server still sweeps their inventory.

**Fix.** Click the button twice — on, then off. The first click sends
`ToggleAutoCollectPayload(true)`, the second sends `false`, and the server ends up in the
state the button shows. A server restart also clears every UUID.

**Why it is not fixed in code.** There is no S2C packet in this mod at all, so the server
has no way to correct a client. Adding one is the first item in
[extension points](architecture.md#extension-points).

---

## The button shows gold but nothing is collected

**Cause, most likely.** The mirror image of the previous symptom: the client thinks it is
on, the server does not have the UUID. This happens after a **server** restart, since
`AUTO_COLLECT_PLAYERS` is rebuilt empty, while the client keeps its boolean for as long
as the game is running.

**Fix.** Click twice. The same remedy, for the same reason.

**Second cause.** There is nothing to collect. The sweep returns early on
`totalValue <= 0`, so a player with no `CurrencyItem` in their inventory produces no log
line and no notification. Confirm with:

```mcfunction
/give @s numismatic-overhaul:bronze_coin 16
```

Sixteen bronze should be gone within half a second.

**Third cause.** The notification is off, so the deposit is invisible. If
Numismatic's `moneyMessageLocation` is `DISABLED` on that client, coins leave the
inventory with no feedback at all. Check the purse readout at the bottom-right of the
inventory screen instead — it is the authoritative number.

---

## Coins I take out of my purse go straight back in

**Cause.** Working as written. The sweep scans the inventory and deposits every
`CurrencyItem` it finds, without any notion of where a coin came from. Withdrawing from
the purse puts coins in the inventory; the next sweep puts them back.

**Fix.** Turn the toggle off before withdrawing. Full explanation in
[the purse-extraction trap](auto-collect.md#the-purse-extraction-trap).

Nothing is lost in the round trip — both operations preserve value — so this is annoying
rather than dangerous.

---

## My money bags vanished into my purse

**Cause.** `MoneyBagItem` implements `CurrencyItem`, and the scan tests the interface
rather than listing items. A money bag in the inventory is swept like loose coins and
dissolved into the balance.

**Fix.** Turn the toggle off before picking one up, or store money bags in a container —
ender chests, shulker boxes and ordinary chests are not scanned. The value is not lost;
it is in the purse, and it can be withdrawn as coins.

If your server hands out money bags as quest rewards meant to be traded as objects, this
is worth warning players about in advance.

---

## Changing the config file does nothing

**Cause.** There is no config file. Nothing in the source reads `getConfigDir()`, and
`config/cheesepurse.json` is a file you created, not one the mod wrote.

**Fix.** The behaviour is set by compile-time constants. See
[configuration](configuration.md) for which value sits on which line, and rebuild after
editing.

If what you actually want is to stop the `+ [3 Silver]` spam, that setting belongs to
Numismatic Overhaul: `moneyMessageLocation`, per client, in its own mod options.

---

## A command is rejected, or does not exist

**Cause.** CheesePurse registers no commands. There is no `/cheesepurse`, no
`/autocollect` and no operator control of any kind. A command that looks like it should
belong to this mod belongs to another one.

**Fix.** None available. If you need an admin to toggle the feature for a player, that
has to be written — see [extension points](architecture.md#extension-points).

---

## The module is missing from the CheeseDash dashboard

**Cause.** CheesePurse has no CheeseDash integration. It declares no `cheesedash`
entrypoint in `fabric.mod.json`, has no `dashboard` package, and registers no module. It
will never appear in `GET /api/v1/modules` or in the dashboard sidebar.

**Fix.** None needed; nothing is broken. This mod is standalone by design.

---

## The build fails on `maven.modrinth:numismatic-overhaul`

**Cause.** `build.gradle` pulls Numismatic from the Modrinth Maven repository at
`https://api.modrinth.com/maven`, pinned to the version id `DPXG6kTJ`. A failure here is
usually no network access, a proxy blocking `api.modrinth.com`, or Gradle running
`--offline` without a populated cache.

**Fix.** Build once with network access to populate the Gradle cache; subsequent builds
work offline. In a locked-down environment, mirror the artifact into a local repository
and point the `maven { }` block at it.

The dependency is `modCompileOnly`, so it affects compilation only. It is never bundled
into the output jar.

---

## Deposits are slower than half a second

**Cause.** `SCAN_INTERVAL_TICKS` counts ticks, not milliseconds. At 20 TPS ten ticks is
0.5 s; at 10 TPS it is a full second; on a server stalling at 5 TPS it is two seconds.

**Fix.** Fix the TPS. The sweep itself costs almost nothing — one `Set.contains` per
online player plus a 41-slot walk per opted-in player — so if the deposit feels slow, the
server is slow, and this mod is reporting the symptom rather than causing it.

---

## Reporting a bug

Include these, in this order:

1. **Which side the problem is on.** Server-only, client-only, or both. Most symptoms on
   this page are one-sided and the answer changes completely between them.
2. **The relevant log.** `logs/latest.log` on the server for anything about loading or
   collection; `.minecraft/logs/latest.log` on the client for the button and the mixin.
3. **The two init lines**, so it is clear the mod loaded at all:

   ```text
   [NumismaticCheesePurse] Initializing…
   [NumismaticCheesePurse] Ready.
   ```

4. **A `DEBUG`-level excerpt of the sweep**, if the problem is about coins moving or not
   moving. Raise the level for `com.roicheese.numismaticheesepurse` and reproduce; the
   lines you want are:

   ```text
   [NumismaticCheesePurse] Auto-collect ENABLED for <player>
   [NumismaticCheesePurse] Collected <n> bronze units for <player>
   ```

   The absence of a `Collected` line while the button shows gold is itself the diagnosis
   — it means the server does not have that player's UUID.

5. **Your Numismatic Overhaul version.** This mod compiles against 0.3.4 and accepts any
   version at runtime, so a mismatch is plausible and worth ruling out early.
6. **The mod list**, or at least whether any other mod touches Numismatic currency or the
   inventory screen.

Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues).
Questions → [Arkensia Discord](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.md) · [CheesePurse](../README.md) →
