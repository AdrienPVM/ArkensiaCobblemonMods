[← CheesePurse](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

Five source files, 257 lines. The mod is small enough that this page can cover every
class and still have room for the parts that are wrong.

---

## Package layout

```text
com.roicheese.numismaticheesepurse
├── NumismaticCheesePurse.java        main entrypoint: packet receiver + the tick loop
├── NumismaticCheesePurseClient.java  client entrypoint: holds one boolean, does nothing else
├── network
│   └── ToggleAutoCollectPayload.java C2S record carrying a single boolean
├── client
│   └── AutoCollectButton.java        the 12×12 ButtonWidget and its tooltip
└── mixin
    └── InventoryScreenMixin.java     adds the button to InventoryScreen.init
```

Both entrypoints are declared in `fabric.mod.json`, `main` and `client`. There is no
`server` entrypoint and no dashboard package — CheesePurse registers nothing with
CheeseDash and publishes no `DashboardModule`.

## Who owns what

**`NumismaticCheesePurse`** is the whole server side. It owns the one piece of
server state, `public static final Set<UUID> AUTO_COLLECT_PLAYERS`, a plain `HashSet`.
`onInitialize` does three things: registers `ToggleAutoCollectPayload` on
`PayloadTypeRegistry.playC2S()`, registers the global receiver that mutates the set, and
registers the `END_SERVER_TICK` handler that drives the sweep. It also holds
`tickCounter`, an instance field, and the `SCAN_INTERVAL_TICKS` constant.

`collectCoinsForPlayer` is `public static`, so another mod could call it to force a
deposit for a player. That is the only usable extension surface the mod exposes, and it
is incidental rather than designed.

**`NumismaticCheesePurseClient`** holds `public static boolean autoCollectEnabled` and
nothing else. Its `onInitializeClient` is empty, with a comment explaining why: the C2S
payload type is registered by the `main` entrypoint, which runs on both physical sides,
and registering it twice crashes the client at startup. That comment is load-bearing —
somebody will eventually look at the empty method and want to fill it.

**`ToggleAutoCollectPayload`** is a record of one boolean, with a hand-written
`PacketCodec` that writes and reads a single byte. Its id is
`numismatic-cheesepurse:toggle_auto_collect`. There is no S2C counterpart, which is the
root of the desync described below.

**`AutoCollectButton`** extends `ButtonWidget`. The press action flips
`NumismaticCheesePurseClient.autoCollectEnabled`, refreshes the tooltip, then runs the
`Runnable` passed by the mixin — which is what sends the packet. Note the ordering: the
local state changes first and the packet goes second, so the button's appearance is
never waiting on the server and never corrected by it.

The class passes a tooltip supplier to `super(...)` **and** calls `setTooltip` in
`refreshTooltipOn`. The supplier form is evaluated by vanilla's narration path, the
explicit tooltip is what the player sees on hover; having both is redundant but harmless.

**`InventoryScreenMixin`** injects at `TAIL` of `InventoryScreen.init`, computes the
button position from the vanilla GUI size, and calls `addDrawableChild`. Because it runs
in `init`, the button is rebuilt on every screen open and every window resize, and the
state it renders from is the static boolean, so nothing is lost across a rebuild.

## State, and what is written to disk

| State | Written to disk? |
|---|---|
| `AUTO_COLLECT_PLAYERS` | **no** |
| `autoCollectEnabled` | **no** |
| Purse balance | yes, by Numismatic's Cardinal Components `CurrencyComponent` |

The mod writes no file of its own, ever. It has no config, no data folder and no
persistence layer. The only durable effect it has on a world is the money it moves into
Numismatic's component, which Numismatic saves in player data with
`RespawnCopyStrategy.ALWAYS_COPY` — so a deposited balance survives death, dimension
changes and restarts.

⚠️ A comment in `NumismaticCheesePurse` claims "The client stores its own state in a
config file so it always knows what to display without asking the server." **It does
not.** `NumismaticCheesePurseClient.autoCollectEnabled` is an in-memory static field with
no save and no load anywhere in the source. The comment describes an intention that was
never implemented, and the old README repeated it as fact. Believe the field, not the
comment.

## Threading

Two threads touch this mod, and the boundary is simpler than in most mods here because
there is no HTTP layer at all.

| Thread | What runs there |
|---|---|
| **Server (main)** | the `END_SERVER_TICK` handler, `collectCoinsForPlayer`, and the payload receiver |
| **Client render** | `AutoCollectButton.renderWidget` and the mixin's `init` injection |

The payload receiver deserves a note. Fabric's `ServerPlayNetworking.registerGlobalReceiver`
for play payloads runs the handler on the **server thread**, not on the Netty IO thread —
Fabric schedules it. That is what makes a plain `HashSet` safe here: both the only writer
(the receiver) and the only reader (the tick handler) are on the same thread, and they
never run concurrently.

That safety is accidental rather than enforced. Nothing in the class documents the
requirement, and `collectCoinsForPlayer` being `public static` means an outside caller
on another thread could break it. A `ConcurrentHashMap.newKeySet()` would cost nothing
and remove the question.

There is no explicit hand-off anywhere, because there is nothing to hand off: the client
side never touches the world, and the server side never touches a GUI.

## The mixin

One mixin, declared under `"client"` in `numismaticheesepurse.mixins.json`. The
`"mixins"` array — the common-side one — is empty.

| | |
|---|---|
| Class | `InventoryScreenMixin` |
| Target | `net.minecraft.client.gui.screen.ingame.InventoryScreen` |
| Method | `init`, injected at `@At("TAIL")` |
| Why | `InventoryScreen` offers no API for adding a widget, so the only way to put a button in the survival inventory is to extend its initialisation. |

The mixin extends `AbstractInventoryScreen<PlayerScreenHandler>` so that `this.width`,
`this.height` and `addDrawableChild` resolve at compile time. That is the usual pattern
for mixing into a screen.

`defaultRequire` is `1` and the config is `"required": true`, so a failed injection is a
**hard crash on the client**, not a silently missing button. That is a deliberate
difference from the other mods in this repository, several of which use `require = 0` on
cosmetic mixins. Here the mixin is the only way to reach the feature, so failing loudly
is defensible — but it does mean a Minecraft update that moves `InventoryScreen.init`
takes the client down rather than degrading.

The injected method is named `autocoinpurse$injectToggleButton`. The `autocoinpurse$`
prefix is a remnant of the mod's former name and no longer matches anything; it is only
a uniqueness prefix so it causes no functional problem, but it is the kind of thing that
makes a reader doubt they are in the right file.

## How Numismatic Overhaul is reached

**By direct import, not reflection.** `NumismaticCheesePurse` imports
`com.glisco.numismaticoverhaul.ModComponents` and
`com.glisco.numismaticoverhaul.currency.CurrencyHelper` and calls them like any other
class. `build.gradle` has it as `modCompileOnly`, so it is on the compile classpath only.

That choice has consequences worth being clear about, because the other mods in this
repository mostly do the opposite:

- **No graceful degradation.** `fabric.mod.json` lists `numismatic-overhaul` in
  `depends`, so Fabric refuses to launch without it rather than letting the mod load and
  fail later. That is the correct pairing: a hard import needs a hard dependency.
- **Breakage is at class-load time.** If a future Numismatic renames `CurrencyHelper`,
  moves `ModComponents`, or changes the signature of `getMoneyInInventory` or
  `modify`, the result is a `NoClassDefFoundError` or `NoSuchMethodError` the first time
  `NumismaticCheesePurse` is touched — at server init, in practice. Loud and early, which
  is better than silent, but it is a crash.
- **The version is pinned to one Modrinth file.** `maven.modrinth:numismatic-overhaul:DPXG6kTJ`
  is version 0.3.4. The runtime `depends` is `"*"`, so a server can run any Numismatic
  version it likes against a jar compiled for 0.3.4. That mismatch is only safe for as
  long as the two methods above keep their shapes.

Compare with `CobbleShiftProxy` in [CheeseKart](../../CheeseKart/docs/architecture.md),
which reaches an optional mod by reflection and can report `isAvailable()`. That pattern
is the right one for a soft dependency, and the wrong one here — the mod is useless
without Numismatic, so there is nothing to degrade to.

## Extension points

- **Forcing a deposit from another mod.** Call
  `NumismaticCheesePurse.collectCoinsForPlayer(serverPlayer)` on the server thread. It is
  public, static, and independent of the toggle — it deposits whether or not the player
  opted in.
- **Reading or changing who is opted in.** `AUTO_COLLECT_PLAYERS` is public and mutable.
  Adding a UUID from outside works and takes effect on the next sweep, with the caveat
  that the player's client will still show grey, because nothing tells it otherwise.
- **Adding a command.** There is no command class and no Brigadier registration. A
  `/cheesepurse toggle <player>` would be about thirty lines, and would need an S2C
  packet to keep the button honest.
- **Hooking pickup instead of scanning.** The real fix for
  [the purse-extraction trap](auto-collect.md#the-purse-extraction-trap). Mixing into
  `ItemEntity.onPlayerCollision`, or into `PlayerInventory.insertStack` and filtering on
  `CurrencyItem`, would make the deposit genuinely pickup-driven and remove the timer.
- **Persisting the toggle.** See below.

## Known weaknesses

Stated plainly, because finding these by surprise is worse.

- **The toggle is not persisted, and the two sides can disagree.** `AUTO_COLLECT_PLAYERS`
  is rebuilt empty on every server start. `autoCollectEnabled` is rebuilt `false` on
  every client start. Because there is no S2C sync and the client never re-sends its
  state on join, any restart of one side without the other produces a mismatch. The
  worst case is a client restart while the server kept its state: the button shows grey
  and the coins keep disappearing. The old README claimed the client "re-syncs on
  reconnect"; no code does that.
- **Players are never removed from the set on disconnect.** `AUTO_COLLECT_PLAYERS` only
  grows over a server's uptime, bounded by the number of distinct players who toggled on.
  It is a few tens of bytes per player and it is wiped by the restart that this mod
  needs anyway, so it is a leak in principle rather than in practice — but the sweep also
  skips offline players by iterating only the online list, so the entries do no work.
- **The set is a non-concurrent `HashSet`.** Safe today only because Fabric runs the
  payload receiver on the server thread. Nothing enforces or documents that.
- **A comment and the old README both describe persistence that does not exist.** See the
  callout in [state](#state-and-what-is-written-to-disk).
- **No S2C packet at all.** The server cannot tell a client anything, which blocks the
  obvious fixes for every point above.
- **The mixin is `required: true`.** A Minecraft update that changes
  `InventoryScreen.init` crashes the client rather than hiding the button.
- **Residual naming from the old project.** The injected method is
  `autocoinpurse$injectToggleButton`, and the comment in `NumismaticCheesePurseClient`
  refers to "AutoCoinPurse (entrypoint main)", a class that no longer exists. The dead
  `com.example.autocoinpurse` package that the old README mentions is **gone** — it is
  not in the source tree and nothing compiles it. Only the two naming traces remain.
- **An unused import.** `NumismaticCheesePurse` imports `net.minecraft.util.Identifier`
  and never uses it; the identifier is built inside `ToggleAutoCollectPayload`. Harmless,
  and a sign that nobody has run a cleanup pass.
- **French comments in an otherwise English codebase**, in three files:
  `NumismaticCheesePurseClient`, `AutoCollectButton` and `InventoryScreenMixin`. Worth
  normalising if you touch those files.
- **The button position assumes a vanilla-sized inventory GUI**, 176×166, hard-coded in
  the mixin. A resource pack that resizes it misplaces the button.
- **There is no way to turn the feature off server-wide** short of removing the jar. On a
  server that decides mid-season that auto-collect breaks its economy, that means a
  restart with a modified `mods/` folder.

---

← [Configuration](configuration.md) · [Troubleshooting](troubleshooting.md) →
