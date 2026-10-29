[← CobbleCap](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](client.fr.md)

# The client side

CobbleCap's `fabric.mod.json` declares `"environment": "*"`, so the same jar loads on both
sides. The client half does three things: it draws the cap badge, it hides pasture Pokémon
on request, and it makes player statues look like statues instead of idling players.

The mod is **playable without it**. A vanilla client on a CobbleCap server gets no badge, no
`/pasturehide`, and sees statues as players breathing and swaying, but every cap rule is
enforced server-side and nothing desynchronises.

## The HUD badge

`CapHudRenderer` registers on `HudRenderCallback` and draws a 24×20 texture,
`cobblecap:textures/gui/level_cap.png`, with the cap number centred on it in white with a
shadow.

| Setting | Source |
|---|---|
| visible | `hudVisible` on `PlayerCapData`, set by `/cobblecap hud on|off` |
| corner | `hudPosition`: `0` bottom-right, `1` bottom-left, `2` top-right, `3` top-left |
| margin | 6 pixels, hard-coded |

It draws nothing at all when the player is null, when F1 has hidden the HUD
(`client.options.hudHidden`), before any data has arrived, when the cap system is disabled
server-side, or when the player turned it off.

### What the packet carries

`CapSyncPayload` is the only S2C packet the HUD uses, under the id `cobblecap:cap_sync`:

| Field | Type |
|---|---|
| `capLevel` | int |
| `nextChampion` | string, written as `""` for null and read back as null |
| `hudVisible` | boolean |
| `hudPosition` | int |
| `systemEnabled` | boolean |

`CobbleCapMod.syncToClient` sends it on join, on every cap change, on a HUD toggle, on
`/cobblecap reload`, and on `enable`/`disable`. It returns early when the player has no
`networkHandler` and when they have no stored row, so a player whose data failed to load
quietly gets no HUD rather than a wrong one.

⚠️ **`nextChampion` is sent but never drawn.** The renderer holds it in a static field and
does not use it. The lang files carry `cobblecap.hud.next` → `Next: %s`, and the badge shows
the number only. Either the next-champion line was dropped or it was never written; the data
is there for whoever wants to add it.

## `/pasturehide`

A client-only toggle that stops rendering Cobblemon Pokémon tethered to a pasture block.
The entities still exist and still tick; they are simply not drawn, for this one player.

The command and its output are in [commands](commands.md#pasturehide). The preference lives
in `config/cobblecap_client.properties`:

```properties
#CobbleCap client preferences
pasture_pokemon_hidden=true
```

Loaded by `PastureHideClient.load()` during client init and rewritten on every toggle. It is
a plain `java.util.Properties` file with one key, separate from the server-side JSON configs
because it belongs to the player, not the world.

### Why the server has to help

`Pokemon.tetheringId` is **never DataTracker-synced**. The client has no way to know a
Pokémon is pastured, so no amount of client-side reflection can answer the question. The
first implementation tried exactly that and could not work.

`PastureTracker`, server-side, closes the gap:

1. Every **40 ticks** (2 seconds) it iterates every entity in every world whose class name
   ends in `.PokemonEntity` and asks, reflectively, whether `getTetheringId()` or
   `getTethering()` is non-null — on the entity, then on its `Pokemon` object.
2. It diffs that set against the previous one and broadcasts only the changes.
3. On join it sends the whole set.

`PastureSyncPayload` therefore has three modes — `SNAPSHOT`, `ADD`, `REMOVE` — and the
client keeps a `ConcurrentHashMap`-backed set. `PastureHideClient.isPastured` is then a
cheap two-step check: the entity's class name ends in `.PokemonEntity`, and its UUID is in
that set.

The deltas are sent one packet per UUID per player, which is wasteful in a burst but
harmless at the rate pastures actually change.

### The render cancel

`EntityRenderDispatcherMixin` injects at the head of
`EntityRenderDispatcher.render(Entity, DDD, F, F, MatrixStack, VertexConsumerProvider, I)`
and cancels when the toggle is on and the entity is pastured. It returns immediately when
the toggle is off, so the cost is one boolean read per entity per frame.

It also keeps telemetry and logs once a second while the toggle is on:

```text
[CobbleCap-Client] /pasturehide last 1s: checked 42 Pokémon entities, hid 7
```

That line is the diagnostic. The mixin only prints it when it checked at least one Pokémon
in the past second, so you never see `checked 0`: **no line at all** while the toggle is on
and Pokémon are in view means the mixin is not seeing Pokémon entities. `checked 42, hid 0`
means the mixin works and the client has no pasture data — the server is not sending it,
which usually means the server does not have CobbleCap, or `PastureTracker` has not run its
first scan yet. Note the log is at `info` level and fires once a second for as long as the
toggle is on and a Pokémon is being rendered, which will fill a client log over a long
session.

`PastureHideClient` also contains an unused `debugLog` method and a `lastDebugLog` field —
dead code from the earlier reflective approach.

## Statue rendering

Four client mixins exist for one purpose: make the fake players that
[the statue subsystem](statues.md) spawns look carved rather than alive.

They all key off `SyntheticUuidRegistry`, a set of UUIDs that belong to doppelgängers. The
registry lives in the **common** source set so a client mixin can read it without a separate
client singleton. The server populates it when it spawns or removes a doppelgänger; the
client mirrors it from `StatueStyleSyncPayload`, on the logic that any UUID mentioned in
that payload is by definition a doppelgänger. The `REMOVE` mode is the authoritative
deregister — a `SNAPSHOT` deliberately does **not** clear the registry, since a doppelgänger
whose style never changed is still synthetic.

| Mixin | Target | Why |
|---|---|---|
| `BipedAnimateModelMixin` | `BipedEntityModel.animateModel(LivingEntity,FFF)` | cancels the pre-pose pass — leaning, swim and crawl angles, rider and sneak crouch. Without it a statue near a swimming player mirrors that pose |
| `BipedEntityModelMixin` | `BipedEntityModel.setAngles(LivingEntity,FFFFF)` | re-invokes `setAngles` with every motion argument zeroed, then cancels the original — no idle breathing, no limb sway |
| `PlayerEntityModelMixin` | `PlayerEntityModel.setAngles(LivingEntity,FFFFF)` | the player override adds sleeve sway and ear tracking of its own, applied before it calls `super`. A mixin on the parent alone would be too late |
| `PlayerEntityRendererMixin` | `PlayerEntityRenderer.getTexture(Entity)` | returns `cobblecap:textures/statue/<style>.png` instead of the player's skin, for any non-`natural` style |

`BipedEntityModelMixin` and `PlayerEntityModelMixin` would recurse into each other —
`PlayerEntityModel extends BipedEntityModel`, so the re-entrant call from one lands in the
other's injection. `StatueFreezeGuard`, a shared `ThreadLocal<Boolean>`, breaks the cycle:
both check `active()` first and set it around their own re-invocation.

### Styled textures

`StatueStyleClient` maps a doppelgänger UUID to a `StatueStyle`, fed by
`StatueStyleSyncPayload` in `SNAPSHOT`, `UPSERT` and `REMOVE` modes. `natural` is stored as
*absent* — `put(uuid, NATURAL)` removes the entry — so a `natural` statue falls through to
vanilla skin resolution and shows the champion's real skin.

Every other style resolves to a pre-baked PNG shipped in the jar:

```text
assets/cobblecap/textures/statue/{stone,gold,emerald,diamond,amethyst,copper,
                                  netherite,obsidian,crystal,glass,frost,soul}.png
```

Twelve files, one per non-`natural` style. These are what give a gold statue a gold body
rather than a gold outline; the scoreboard-team glow colour and the particle aura are
server-side and visible to a vanilla client, the body texture is not.

⚠️ So a vanilla client sees a styled statue as the champion's ordinary skin with a coloured
glow. That is a cosmetic difference only, but it means screenshots will not match between
players with and without the mod.

## Client init order

`CobbleCapClient.onInitializeClient`:

1. attach the `CapSyncPayload` receiver — all three codecs are registered by the **common**
   initializer, which runs on both sides, so registering again here would throw *already
   registered*
2. attach the `PastureSyncPayload` receiver
3. attach the `StatueStyleSyncPayload` receiver
4. `CapHudRenderer.register()`
5. `PastureHideClient.load()`
6. `PastureHideCommand.register()`

```text
[CobbleCap] Client initializing...
[CobbleCap] Client initialized — HUD registered, /pasturehide enabled (hidden=false)
```

Every receiver body is wrapped in `context.client().execute(...)`, so packet handling moves
off the netty thread onto the client thread before touching any state.

---

← [Statues and dioramas](statues.md) · [Configuration](configuration.md) →
