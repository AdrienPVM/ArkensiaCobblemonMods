[← CheeseExtraCommands](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](architecture.fr.md)

# Architecture

Seven source files, 840 lines, two unrelated features sharing a jar. The verification
commands and the island-tour HUD have no code in common beyond the logger name, and
either could be deleted without touching the other.

---

## Package layout

```text
com.cheeseextra
├── CheeseExtraMod.java            main entrypoint: registers everything, owns the logger
├── CheeseCheckCommand.java        the whole /cheesecheck Brigadier tree and its four handlers
├── CobblemonReflect.java          reflection bridge to Cobblemon: party, EVs, IVs, species, ball
├── arkensia
│   └── IslandTourTracker.java     reads the tour scoreboards each second, pushes deltas
├── network
│   └── IslandTourPayload.java     S2C record: show / met / total / remaining
└── client
    ├── CheeseExtraClient.java     client entrypoint: payload receiver and HUD hook
    └── IslandTourHud.java         draws the bottom-right panel
```

`com.cheeseextra.arkensia` holds the island-specific code, which makes the coupling
visible from the package name: anything in that package is not reusable elsewhere. The
line count by file:

| File | Lines |
|---|---|
| `CheeseCheckCommand.java` | 263 |
| `CobblemonReflect.java` | 243 |
| `IslandTourTracker.java` | 143 |
| `IslandTourHud.java` | 92 |
| `IslandTourPayload.java` | 49 |
| `CheeseExtraMod.java` | 33 |
| `CheeseExtraClient.java` | 17 |

`resources/` contains `fabric.mod.json` and nothing else. No mixin config, no lang file,
no datapack, no textures.

## Who owns what

**`CheeseExtraMod`** is a plain `ModInitializer` with no state beyond a public static
`LOGGER`, named `cheeseextra` rather than after a class. It registers five things:

| Registration | Why |
|---|---|
| `CommandRegistrationCallback` → `CheeseCheckCommand.register` | the command tree, built when the server builds its dispatcher |
| `IslandTourPayload.register()` | the S2C codec, which must be registered during mod init on both sides |
| `ServerTickEvents.END_SERVER_TICK` → `IslandTourTracker::onServerTick` | the per-second HUD evaluation |
| `ServerPlayConnectionEvents.JOIN` | forces an HUD state resend to a reconnecting player |
| `ServerPlayConnectionEvents.DISCONNECT` | drops that player's cached snapshot |

There is no singleton `INSTANCE`, no manager object, no config to load. Every other class
is static.

**`CheeseCheckCommand`** is entirely static: the Brigadier tree in `register`, four
handlers, one `setScore` helper. The tree is built by nesting `.then(...)` calls and
attaching `.executes(...)` at several depths, which is how the optional trailing arguments
work. `/cheesecheck ev` has three `executes` nodes (at `value`, at `statCount`, at
`teamCount`), each passing different literal defaults into the same `checkEv` method.
That is why the defaults are `1` and `1` and are not stated anywhere as constants: they
are arguments at the call site.

**`CobblemonReflect`** is the only interesting file. See
[the reflection layer](#the-reflection-layer).

**`IslandTourTracker`** owns the one piece of mutable state in the mod: a
`ConcurrentHashMap<UUID, Snapshot>` of what was last sent to each player, plus a static
`int tickCounter`. `Snapshot` is a record, so a stored snapshot cannot drift.

**`IslandTourPayload`** is a record implementing `CustomPayload`, with a hand-written
`PacketCodec.of(write, read)` rather than a composed tuple codec. Hand-writing it is what
allows the list length to be a VarInt followed by capped strings.

**`IslandTourHud`** keeps four `volatile` static fields and draws from them. `volatile`
because the network thread hands the payload to the client executor while the render
thread reads the fields; see [threading](#threading).

## The reflection layer

`CobblemonReflect` reaches Cobblemon without a compile-time or load-time link. Cobblemon
is `compileOnly` in `build.gradle` and sits in `suggests`, not `depends`, in
`fabric.mod.json`. `isAvailable()` is a single `Class.forName` on
`com.cobblemon.mod.common.Cobblemon`, and every command calls it before doing anything
else.

The important discipline here: **nothing is looked up by method name where a name could
be remapped or renamed.** Instead the code scans for a method by its *shape*.

```java
for (Method m : cobblemonClass.getMethods()) {
    if (m.getParameterCount() == 0
            && m.getReturnType().getSimpleName().equals("PokemonStoreManager")) {
        storage = m.invoke(instance);
        break;
    }
}
```

That pattern repeats four times, each with a different discriminator:

| Target | Discriminator |
|---|---|
| `PokemonStoreManager` | zero-arg method whose return type is named `PokemonStoreManager`, on `Cobblemon.INSTANCE` |
| the party store | one-arg method whose return type name *contains* `Party` and whose parameter accepts a `ServerPlayerEntity`, falling back to a `UUID` overload |
| the `EVs` / `IVs` store | zero-arg method whose return type is named exactly `EVs` or `IVs`, on the Pokémon object |
| `getOrDefault(Stat)` | one-arg method returning `int` whose parameter implements `com.cobblemon.mod.common.api.pokemon.stats.Stat` |

Only the class names are hardcoded: `Cobblemon`, `Stats`, `Stat`, and the `INSTANCE`
field that Kotlin generates for an `object`. Those are Cobblemon's own API surface and the
ones least likely to move.

Where a name *is* used, it is used as one candidate among several. `invokeGetter` takes a
varargs list and tries each against both `getMethod` and `getDeclaredMethod`:
`getSpeciesId` tries `getShowdownId`, `showdownId`, `getName`, `name` in that order;
`getCaughtBall` tries `getCaughtBall` then `getPokeball`, and then the ball's own
`getName`, `name`, `toString`.

Two fallbacks are worth knowing about because they change the meaning of a result rather
than only the route to it:

- **The party store.** If the party cannot be obtained as an `Iterable`, the code tries
  `toGappyList`, `getAll`, `getPokemon` in turn and iterates whichever returns an
  `Iterable`. A gappy list may contain nulls for empty party slots; nulls are filtered
  out at insertion.
- **The stat store.** If `getOrDefault(Stat)` is not found, the code iterates the store
  as an `Iterable<Map.Entry<Stat, Integer>>` and places each value at the stat's **enum
  ordinal**. That silently assumes `Stats`' declaration order is HP, ATTACK, DEFENCE,
  SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED. If Cobblemon ever reorders that enum, the
  fallback path reads the wrong stats and nothing warns you.

The `getCaughtBall` candidate list ending in `toString` is a sharper edge than it looks:
`getMethod("toString")` always succeeds on any object, so if `getName` and `name` are both
absent the method returns the ball's `toString`, lowercased, whatever that happens to be.
For counting *distinct* ball types that still works, since the value only needs to be
stable per ball type. It would not work if the string were ever compared against a known
id.

Every reflective failure is caught. The commands degrade to "no match" rather than
throwing, which means a Cobblemon update that breaks the bridge looks exactly like a
player whose party does not qualify. That is the main maintenance hazard of this design,
and the log lines at INFO level are the only way to tell the two apart.

## State and persistence

The mod writes **no files**. Its entire persistent footprint is the vanilla scoreboard:
objectives written by `setScore`, which Minecraft saves in `world/data/scoreboard.dat` on
its own schedule. There is no save hook, no flush, no shutdown handler.

The only in-memory state is `IslandTourTracker.lastSent`, a cache that can be dropped at
any time without consequence beyond one redundant packet.

## Threading

| Code | Thread | Notes |
|---|---|---|
| `CheeseCheckCommand` handlers | server thread | Brigadier dispatches on the thread that read the command, which for a player, a command block or a function is the server thread |
| `CobblemonReflect` | server thread | called only from the command handlers |
| `setScore` | server thread | mutates the scoreboard directly, which is safe there |
| `IslandTourTracker.onServerTick` | server thread | `END_SERVER_TICK` |
| `onPlayerJoin` / `onPlayerDisconnect` | server thread | Fabric's connection events |
| `ServerPlayNetworking.send` | server thread | hands the packet to the netty pipeline |
| payload decode | client network thread | the Fabric receiver |
| `IslandTourHud.update` | client main thread | the receiver wraps it in `context.client().execute(...)` |
| `IslandTourHud.render` | client render thread | `HudRenderCallback` |

There is **no HTTP thread**, because there is no dashboard module. Nothing in this mod
needs `executeOnServerThread`.

Two hand-offs exist and both are explicit. On the client, the payload receiver does not
touch the HUD fields itself; it posts to the client executor. The HUD's fields are
`volatile` on top of that, which is belt and braces: `update` and `render` both run on
client threads that are not guaranteed to be the same one in every Minecraft version.

`lastSent` is a `ConcurrentHashMap` although every access happens on the server thread.
Harmless, and cheap insurance if a future caller arrives from elsewhere.

## Mixins

**None.** `fabric.mod.json` declares `"mixins": []` and there is no mixin config file in
`resources/`. The mod achieves everything through public Fabric API events, which is why
it survives a Minecraft patch release without recompilation.

## Extension points

There are no interfaces, no registries and no events published by this mod. A contributor
extends it by editing it. The three natural seams:

**Adding a subcommand.** Add a `.then(CommandManager.literal("..."))` branch in
`CheeseCheckCommand.register` and a static handler beside the existing four. Return `1`
on a pass and `0` on a failure, and call `setScore` only on the pass path: the entire
quest-integration contract is those two rules. Follow the existing argument order
convention (`player` first, criteria next, `scoreboard` and `value` last) rather than
`iv`'s, which puts its count before its threshold and reads inconsistently against `ev`.

**Reading something new from a Pokémon.** Add a static method to `CobblemonReflect`.
Prefer scanning `getMethods()` by return type or parameter type over naming a method.
Where a name is unavoidable, pass several candidates to `invokeGetter` and make sure the
method returns a neutral value rather than throwing.

**Retargeting the HUD.** Covered in [the island tour tracker](island-tour.md#retargeting-it-to-another-server).

## Known weaknesses

Written down because they are all real and all cheap to trip over.

**A failed check and a broken bridge are indistinguishable.** Both return `0`, both send
the player a red message. If a Cobblemon update changes `PokemonStoreManager`'s name,
every quest on the server silently becomes uncompletable and the only symptom is
`Could not get PokemonStoreManager from Cobblemon.INSTANCE` buried in the log at WARN.
A health-check subcommand that reported the bridge's state would fix this and does not
exist.

**The logging is far too loud.** `getPartyPokemon` logs two INFO lines per call and
`getStatStore` logs one per Pokémon. A single `/cheesecheck iv` on a full party writes
eight INFO lines, and `setScore` adds a ninth. On a server where twenty NPC dialogues
call checks, the log is unusable. Those should be DEBUG; they are INFO because they were
added while the reflection was being made to work and never turned down.

**Scores are keyed by player name.** `ScoreHolder.fromName(player.getName().getString())`
loses a player's progress when they rename. Using the UUID would not help either, since
the NPC dialogues read the same objectives by name; fixing it properly means changing
both sides at once.

**A misspelled objective name is accepted.** `setScore` creates the objective instead of
refusing. The warning it logs is the only signal, and the player still sees a success
message. Validating against the existing objectives, or at least making the auto-creation
opt-in, would turn a silent failure into a loud one.

**The `ev` subcommand cannot target a stat.** "Max Speed EVs" is not expressible. Adding
a stat argument would mean a fifth `executes` depth or a separate subcommand; the current
shape cannot be extended cleanly because `statCount` and a hypothetical `stat` would
occupy the same position.

**`ballvariety` accepts a count up to 20** for a container that holds six. Anything above
`6` is guaranteed to fail. The range should be `1`–`6`.

**The `ev` success message says `1 stats`** in the singular case. Cosmetic, visible to
every player on the first quest step they complete.

**The stat-store fallback trusts enum ordinals.** Described under
[the reflection layer](#the-reflection-layer). It is the one path that can return wrong
numbers rather than no numbers.

**The island-tour half is dead weight off Arkensia.** It is 284 lines across three files
plus a client entrypoint, and on any other server it does nothing. Splitting it into its
own mod would make both halves honest; it was not worth doing while a single server ran
both.

**Nothing is tested.** There is no test source set, no fixture, and no way to exercise the
reflection layer without a running server and Cobblemon installed. Every change to
`CobblemonReflect` has to be verified in game.

---

← [Island tour tracker](island-tour.md) · [Troubleshooting](troubleshooting.md) →
