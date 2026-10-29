[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](troubleshooting.fr.md)

# Troubleshooting

## The server refuses to start after adding the jar

**Cause.** CheeseDash is a **hard** dependency. `fabric.mod.json` lists
`"cheesedash": "*"` under `depends`, so Fabric Loader aborts startup when it is absent,
with a message naming `eggandcheese` and the missing `cheesedash`.

**Fix.** Put the CheeseDash jar in the same `mods/` folder. Building EggAndCheese from
source needs CheeseDash built first too, because the Gradle dependency is a `fileTree`
glob over `../CheeseDash/build/libs`.

The other three `depends` entries — `fabricloader >=0.16.0`, `fabric-api`,
`minecraft ~1.21.1` — fail in the same obvious way.

## The mod is in `mods/` but nothing happens

**Cause.** It did not initialise.

**Fix.** Search the log for the two lines the mod always prints:

```text
[EggAndCheese] Initializing...
[EggAndCheese] Ready!
```

Both present means every registry, the criterion, the payloads, the reconciliation
handler and both commands are in place. `Initializing...` with no `Ready!` means an
exception during init — the stack trace is directly below it.

Neither line means the jar was not loaded at all: wrong folder, a `-sources.jar` or
`-dev.jar` by mistake, or a jar built for another Minecraft version.

## `/eac` is not recognised

**Cause.** One of three things.

- You are the **server console**. The whole `/eac` tree requires
  `isExecutedByPlayer()`, so to the console the command does not exist.
- You are not an admin. `PermissionUtil.isAdmin` checks `eggandcheese.admin` through
  fabric-permissions-api, falling back to op level 2. Brigadier hides a command whose
  `requires` fails rather than refusing it, which is why you get Minecraft's
  `Unknown or incomplete command` instead of a permission error.
- The mod did not load — see above.

**Fix.** Run it as a player with op level 2, or grant `eggandcheese.admin` in LuckPerms.
`/pedestal` is unaffected: it has no permission requirement at all, so if
`/pedestal` works and `/eac` does not, your problem is permissions, not loading.

## `/eac give <id>` says `Unknown ID`

**Cause.** The id does not exist in the registry, exactly as typed. Lookup is an exact,
case-sensitive map lookup, and the argument has **no tab completion**, which makes typos
the normal failure.

**Fix.** Read the id from the dashboard's Eggs list and copy it. Check case.
`/eac reload` prints how many entries the server has, which at least tells you whether the
registry is populated at all.

## A config change has no effect

**Cause.** Almost always one of these.

- You created `config/eggandcheese.json`. **There is no config file.** The mod never calls
  `getConfigDir()`. A file you create there is ignored.
- You edited `world/data/eggandcheese_registry.dat` by hand. Minecraft holds that state
  in memory and overwrites the file on its next save, so your edit is discarded.
- You changed `ftbAutoSync`. It is a dead setting — stored, returned by the API, read by
  nothing.
- You changed `textureRenderDistance` and expected it on a client that was already
  connected before the change. It is pushed at the moment of the `PUT`, to players online
  at that moment.

**Fix.** Change settings through `PUT /api/v1/eggs/config` or the dashboard, and read the
response back — it returns the stored state.

## Eggs degrade to plain boxes too close to the player

**Cause.** `textureRenderDistance`, and the fact that it may never have reached the
client. `sendRegistrySync` is called from exactly one place — `PUT /config` — so a client
that joined after the last config change is still using its compiled-in default of 20.

**Fix.** Save the config once from the dashboard after each restart, or set a per-egg
`renderDistanceOverride` of `0` on the eggs that must always show full detail. `0` means
"never degrade"; `-1` means "follow the global value".

## The dashboard has no Eggs section

**Cause.** CheeseDash discovers modules through the `cheesedash` entrypoint at startup.
No section means the entrypoint was never loaded.

**Fix.** In order:

1. Confirm both jars are in `mods/` and the server started cleanly.
2. Confirm `[EggAndCheese] Ready!` is in the log.
3. Call `GET /api/v1/modules` and look for the `eggs` prefix. Present there but absent
   from the sidebar is a dashboard-side problem; absent from both is a loading problem.
4. Check for a CheeseDash version mismatch. EggAndCheese compiles against CheeseDash's
   `DashboardModule`, `WebDashboard` and `DashboardRegistry`; if those changed
   incompatibly, the entrypoint construction throws and CheeseDash logs it.

## Every eggs route returns 503

**Cause.** `EggAndCheeseDashboardModule` answers
`503 {"error":"Server not available"}` while its `MinecraftServer` reference is null — that
is, before `onServerStart` or after `onServerStop`.

**Fix.** Wait for the server to finish starting. A persistent 503 after startup means
`onServerStart` was never called, which points at CheeseDash's module lifecycle rather
than at this mod.

## A player right-clicks an egg and nothing happens

**Cause.** Four possibilities, in order of likelihood.

- **The block has no `registryId`.** `handlePlayerDiscovery` returns at its first check.
  This is what an unbound or reconciled-away block looks like — an egg that renders and
  does nothing.
- **The id names no registry entry.** Same silent return. This is an orphan.
- **The player already found it.** They get `You already found this one!` in the action
  bar, which is easy to miss above the hotbar.
- **The player is an admin.** An admin right-click takes a completely different branch:
  they receive a decorative copy, and no discovery is recorded.

**Fix.** Run the **Integrity** button in the dashboard with the area loaded — it reports
orphans by coordinate. For the admin case, test with a non-op account; see
[placement and discovery](placement-and-discovery.md#an-admin-right-click-is-not-a-discovery).

## Two eggs look identical and share an id

**Cause.** A duplicate: one `registryId` claimed by more than one block. Copying a build
with WorldEdit, pasting a schematic twice, or restoring part of a map all produce this.

**Fix.** Load the area, press **Integrity**, read the report, confirm the repair. The
repair *unbinds* the extra blocks — sets their id to empty — it never deletes them. Full
procedure in [integrity](integrity.md#how-to-actually-use-it-on-a-drifted-map).

⚠️ The tool only sees **loaded chunks**. A duplicate in a chunk nobody has visited since
the restart cannot be found. Walk the area first.

## An egg is in the dashboard but there is no block at its coordinates

**Cause.** Three ways to get here.

- The entry was created with `POST /collectibles`, which creates a registry entry and
  **does not place a block**.
- An admin broke the block. That sets `positionLost = true` and the position to
  `BlockPos.ORIGIN`, and tells the admin so in gold text.
- The entry was renamed. `POST /rename` changes the registry key and leaves the block
  carrying the old id, so you get one entry with no block and one orphan block. This is a
  bug, not a configuration issue.

**Fix.** Give yourself the admin item for that id (`/eac give <id>` or the dashboard's
give-admin button) and place it where the egg belongs. Placing an item that names an
existing id re-binds the entry and clears `positionLost`.

## The egg in chat shows `egg_014` instead of its title

**Cause.** By design, though probably not the intended design. The discovery message is
`Text.translatable("eggandcheese.found", registryId)` — it passes the **id**. `title` is
used for the FTB quest only.

**Fix.** Name your ids as player-facing strings: `cave_nord`, `spawn_secret`. Renaming an
existing egg works, but read the rename caveat above first.

## FTB quests are not created

**Cause.** Several gates, all logged.

**Fix.** Check the startup log for the hook's own diagnosis:

```text
[EAC-FTB] FTB Quests not present — hook disabled
```

means FTB Quests is not installed. Otherwise look for

```text
[EAC-FTB] FTB Quests hook initialized — Java API ready
```

Absent, with no "not present" line, means reflection failed to resolve something required
— the log line immediately before it names what. That is the expected failure mode after
an FTB Quests update, and it is why
[the integration page](ftb-quests.md) recommends the advancement route as a fallback.

If the hook initialised and quests still do not appear, check `ftbQuestsEnabled` in
`GET /api/v1/eggs/config`. It defaults to `false`.

## Players can tick egg quests without finding the egg

**Cause.** The `CustomTask` class did not resolve, so the hook fell back to
`CheckmarkTask`, which is a manually clickable tick box.

**Fix.** There is none within this mod — it uses `CustomTask` whenever FTB Quests provides
it. The log says which one you got:

```text
[EAC-FTB] CustomTask not found — falling back to CheckmarkTask (players can click)
```

Either update FTB Quests to a version that has `CustomTask`, or drive your quests from the
`eggandcheese:egg_found` advancement and make the task read-only yourself.

## Quest ticks disappeared after a sync

**Cause.** `POST /config/sync-ftb` deletes every linked quest and recreates it with a new
id. Player progress is attached to the old ids and does not survive.

**Fix.** Nothing to recover on the FTB side. The mod's own `discoveredBy` records are
intact in the egg registry, so you can see who had found what through
`GET /collectibles/:id/discoveries`. Use full sync for setup and for badly drifted quest
files, not as routine maintenance.

## The server froze, then the watchdog killed it

**Cause.** If this happens around chunk loading and the stack trace mentions
`BLOCK_ENTITY_LOAD`, `AdminEggPlacedEvent`, `getBlockEntity` or `getChunkBlocking`, it is
the June 2026 deadlock pattern: work done inside a block-entity-load handler that forces a
chunk to load, re-entering c2me's chunk pipeline from inside it.

**Fix.** Confirm the two defences are still in the code you are running:

1. `AdminEggPlacedEvent.register()` only *enqueues* on `BLOCK_ENTITY_LOAD` and drains the
   queue in `END_SERVER_TICK`. It must not be a `server.execute`, which runs inline on the
   server thread and deadlocks identically.
2. `unbindAt` calls `world.getChunkManager().isChunkLoaded(cx, cz)` before touching
   `getBlockEntity`, and returns `false` when the chunk is not loaded.

If a patch removed either, restore it. The full account is in
[integrity](integrity.md#never-force-load-a-chunk-from-a-block-entity-load-handler).

## A frame hitch when flying into an egg field

**Cause.** Texture generation is synchronous on the render thread the first time each
distinct DNA appears. Fifty distinct eggs are fifty 64×64 textures plus two emissive
frames each, generated on sight.

**Fix.** Expected behaviour, not a bug, and it happens once per DNA per session. Lower
`textureRenderDistance` so distant eggs use the box instead. Eggs sharing a DNA share one
texture, so a decorative wall of identical eggs is nearly free.

## An unbreakable block is left where a pedestal used to be

**Cause.** `eggandcheese:pedestal_extension`. A tall pedestal places these above itself to
carry the platform's collision, and they are registered with bedrock-like strength.
Removing the pedestal through `/setblock`, WorldEdit or a rollback skips
`removeAllExtensions()`.

**Fix.** `/setblock <x> <y> <z> air` on each leftover block. There is no in-game way to
break them.

## English clients see French words in tooltips

**Cause.** `getDisplayName()` on `PatternType`, `PaletteType`, `RarityType` and the rest
returns French strings baked into the Java source. The item tooltip and the dashboard's
`displayName` fields both use them.

**Fix.** None available without a code change. The enum constant names (`CRYSTALS`,
`ICE`, `EPIC`) are language-neutral and are what `GET /enums` returns as `id`, so a
consumer that wants its own translations should key on those.

## A `PUT` returned 200 but nothing changed

**Cause.** `EggsApi.parseJson` swallows malformed JSON and returns an empty object, so a
request with a broken body behaves exactly like `{}` — a successful no-op. Clamping and
enum fallbacks do the same thing more quietly: `patternScale: 99` becomes `4`, and
`pattern: "SPOT"` keeps the old value.

**Fix.** Always read the response body, which is the full new state, and compare it with
what you sent. Check your `Content-Type: application/json` header and your JSON syntax.

## A READ_ONLY dashboard account deleted an egg

**Cause.** Not a misconfiguration. `EggsApi` performs **no role check at all** — no
`requireWrite`, no `requireAdmin`. Every route is open to any authenticated session.

**Fix.** Until the handlers are patched with
`if (!WebDashboard.requireWrite(exchange)) return;`, do not hand out CheeseDash accounts
you would not trust with full control of the eggs. Restricting the dashboard port by
firewall or SSH tunnel is the usual mitigation.

## Reporting a bug

Open an issue at
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) with:

1. **The jar versions.** `eggandcheese-1.0.0+1.21.1.jar` and your CheeseDash jar's exact
   filename.
2. **The mod's startup lines** from `logs/latest.log`:
   - `[EggAndCheese] Initializing...` and `[EggAndCheese] Ready!`
   - every `[EAC-FTB]` line from startup, if the problem involves quests
3. **The stack trace**, if there is one. Grep the log for `eggandcheese` and include the
   whole block, not the first line.
4. **What the dashboard says.** `GET /api/v1/eggs/config`,
   `GET /api/v1/eggs/stats`, and for an egg-specific problem,
   `GET /api/v1/eggs/collectibles/<id>` and `GET /api/v1/eggs/integrity`. Those four
   responses describe the state far better than a screenshot.
5. **The egg's id and coordinates**, and which dimension.
6. **Whether c2me or another chunk-system mod is installed**, for anything involving a
   freeze, a watchdog kill, or chunk loading.

For a client-side rendering problem, the client's own `logs/latest.log` is the relevant
one, and say whether the egg renders as a box (a level-of-detail question) or not at all
(a renderer question).

---

← [Architecture](architecture.md) · [Installation](installation.md) →
