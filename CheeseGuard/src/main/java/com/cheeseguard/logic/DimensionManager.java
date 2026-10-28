package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.DimensionConfig;
import com.cheeseguard.mixin.MinecraftServerAccessor;
import com.google.gson.*;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.random.RandomSequencesState;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.gen.chunk.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class DimensionManager {

    private final List<DimensionConfig> customDimensions = new ArrayList<>();
    private MinecraftServer server;
    private Path configPath;
    private String spawnRedirect = null; // dimension worldId to redirect overworld spawns to

    // Clear inventory mode: save full player NBT, restore on exit
    private final Map<UUID, net.minecraft.nbt.NbtCompound> clearInvSnapshots = new HashMap<>();
    // Items the player held at ENTRY (captured live, before the wipe). Used at exit to
    // re-grant ONLY genuinely-gained items (current minus original) instead of the whole
    // current inventory — preventing duplication when current overlaps the snapshot.
    // Absent for crash-recovered snapshots (loaded from disk) → exit does restore-only.
    private final Map<UUID, java.util.List<net.minecraft.item.ItemStack>> clearInvOriginalItems = new HashMap<>();
    // Construction mode: gamemode tracking only (inventory handled by clearInventory)
    private final Map<UUID, String> constructionSavedGameMode = new HashMap<>(); // gamemode before entering
    private final Map<UUID, String> lastKnownWorld = new HashMap<>();

    // World border auto-persistence: any change to a managed dim's border (vanilla
    // /worldborder command, dashboard, mod) is captured by a listener and saved
    // back into our config so it survives restarts. Mute is set during our own
    // applyWorldBorder calls to avoid feedback loops.
    private static final double BORDER_NO_BORDER_THRESHOLD = 5.99e7;
    private volatile boolean muteBorderListener = false;
    private final Set<String> borderListenerAttached = new HashSet<>();
    private long borderSavePendingTick = -1L; // -1 = no pending save

    public void init(MinecraftServer server) {
        this.server = server;
        this.configPath = server.getRunDirectory().resolve("config").resolve("cheeseguard_dimensions.json");
        load();
        loadClearInvSnapshots(); // Restore crash-survived clearInv snapshots

        // Auto-register built-in dimensions that exist on server but don't have a config yet.
        // Snapshot the current vanilla WorldBorder state so a later applyWorldBorder
        // doesn't clobber a pre-existing border the admin set up via /worldborder
        // before installing CheeseGuard.
        for (ServerWorld world : server.getWorlds()) {
            String worldId = world.getRegistryKey().getValue().toString();
            if (getConfigByWorldId(worldId) == null) {
                DimensionConfig c = new DimensionConfig(worldId, friendlyName(worldId));
                c.setBuiltIn(true);
                c.setGenType(guessGenType(worldId));
                snapshotBorderInto(c, world);
                customDimensions.add(c);
            }
        }

        // Recreate custom (non-built-in) dimensions that were saved
        for (DimensionConfig config : customDimensions) {
            if (!config.isBuiltIn()) {
                try { createWorldRuntime(config); }
                catch (Exception e) {
                    CheeseGuardMod.LOGGER.error("[CG] Failed to recreate dimension {}: {}", config.getId(), e.getMessage());
                }
            }
        }
        save(); // persist auto-registered built-in dims

        // Break vanilla's overworld→nether/end WorldBorderSyncer chain.
        // Vanilla attaches a WorldBorderListener.WorldBorderSyncer to overworld for every
        // other ServerWorld so changes cascade. That cascade overwrites any per-dim border
        // we configure: setting a border on overworld immediately rewrites nether and end's
        // borders to match. Detaching the syncers makes each ServerWorld's WorldBorder fully
        // independent — exactly what an admin expects when "this dim has its own border".
        breakBorderSyncerChain(server);

        // Apply time locks immediately so dimensions have the correct time from the first tick.
        // World borders: always re-apply at boot. For built-in dims (overworld notably),
        // vanilla level.dat may have stored an old border size from before a /toggle, so
        // disabled-in-config must reset the runtime border to "no-border" — otherwise
        // the dashboard says off but the world still enforces the saved size.
        // Auto-registered dims captured the vanilla state above, so the first apply is a no-op.
        for (DimensionConfig config : customDimensions) {
            applyTimeLock(config);
            applyWorldBorder(config);
        }

        // Attach auto-persist listener to every existing world's border so any
        // change (vanilla /worldborder command, mod, dashboard) is saved into
        // our config and survives a restart.
        for (ServerWorld w : server.getWorlds()) attachBorderListener(w);

        // Re-send WorldBorder packet when a player teleports between dimensions.
        // Vanilla only auto-syncs the Overworld border; custom dims need this.
        net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
            (player, origin, destination) -> sendBorderToPlayer(player));

        CheeseGuardMod.LOGGER.info("[CG] DimensionManager loaded, {} dimensions ({} custom)",
            customDimensions.size(), customDimensions.stream().filter(c -> !c.isBuiltIn()).count());
    }

    // ── List all dimensions ──────────────────────────────────────────────────

    /**
     * Returns all dimensions as DimensionConfig objects (built-in + custom).
     * Also picks up any new modded dimensions that appeared since init.
     */
    public List<DimensionConfig> getAllDimensions() {
        List<DimensionConfig> result = new ArrayList<>();
        if (server == null) return result;

        // Check for any new server worlds not yet tracked
        for (ServerWorld world : server.getWorlds()) {
            String worldId = world.getRegistryKey().getValue().toString();
            if (getConfigByWorldId(worldId) == null) {
                DimensionConfig c = new DimensionConfig(worldId, friendlyName(worldId));
                c.setBuiltIn(true);
                c.setGenType(guessGenType(worldId));
                customDimensions.add(c);
            }
            attachBorderListener(world); // idempotent — captures /worldborder changes
        }

        result.addAll(customDimensions);
        return result;
    }

    private String friendlyName(String worldId) {
        return switch (worldId) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "The End";
            default -> {
                // Extract last part: "cobblemon:safari" → "safari"
                String name = worldId.contains(":") ? worldId.substring(worldId.indexOf(':') + 1) : worldId;
                name = name.replace('_', ' ');
                // Capitalize first letter
                yield name.substring(0, 1).toUpperCase() + name.substring(1);
            }
        };
    }

    private DimensionConfig.GenType guessGenType(String worldId) {
        if (worldId.contains("nether")) return DimensionConfig.GenType.NETHER;
        if (worldId.contains("end")) return DimensionConfig.GenType.END;
        return DimensionConfig.GenType.NORMAL;
    }

    // ── Create dimension ─────────────────────────────────────────────────────

    public DimensionConfig createDimension(String id, String displayName, DimensionConfig.GenType genType) {
        return createDimension(id, displayName, genType, null);
    }

    /**
     * Create a dimension with optional pre-creation configurator.
     * The configurator runs BEFORE createWorldRuntime so fields like seed,
     * flatLayers, generateStructures are used during world generation.
     */
    public DimensionConfig createDimension(String id, String displayName, DimensionConfig.GenType genType,
                                           java.util.function.Consumer<DimensionConfig> preCreateConfig) {
        // Sanitize id
        id = id.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (id.isBlank()) id = "dim_" + System.currentTimeMillis() % 10000;

        // Check duplicate
        String fullId = "cheeseguard:" + id;
        for (DimensionConfig c : customDimensions) {
            if (c.getWorldId().equals(fullId)) return null; // already exists
        }

        DimensionConfig config = new DimensionConfig(id, displayName);
        config.setGenType(genType);
        config.setBuiltIn(false);

        // Set default spawn Y based on type
        switch (genType) {
            case VOID -> { config.setSpawnY(65); config.setFlatLayers(""); }
            case FLAT -> config.setSpawnY(5);
            case NORMAL -> config.setSpawnY(80);
            case NETHER -> config.setSpawnY(64);
            case END -> config.setSpawnY(64);
        }

        // Apply pre-creation config (seed, flatLayers, generateStructures, etc.)
        // BEFORE world generation so they take effect during chunk generator creation
        if (preCreateConfig != null) {
            preCreateConfig.accept(config);
        }

        // If the user did not specify a seed, generate a random one for this dim.
        // Without this, getSeed()==0 falls back to the overworld seed in createWorldRuntime,
        // so two "random" dims would silently share the overworld's terrain seed.
        if (config.getSeed() == 0L) {
            long random;
            do { random = new java.util.Random().nextLong(); } while (random == 0L);
            config.setSeed(random);
        }

        try {
            createWorldRuntime(config);
            customDimensions.add(config);
            save();
            CheeseGuardMod.LOGGER.info("[CG] Created custom dimension: {} ({})", id, genType);
            return config;
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.error("[CG] Failed to create dimension {}: {}", id, e.getMessage(), e);
            return null;
        }
    }

    private void createWorldRuntime(DimensionConfig config) {
        Identifier dimId = Identifier.of("cheeseguard", config.getId());
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);

        // Already loaded?
        if (server.getWorld(worldKey) != null) return;

        // Build DimensionOptions
        RegistryEntry<DimensionType> dimType = getDimensionTypeEntry(config);
        ChunkGenerator generator = createChunkGenerator(config);
        DimensionOptions dimOptions = new DimensionOptions(dimType, generator);

        // Determine seed (early — needed for TB init too)
        long tbSeed = config.getSeed() != 0 ? config.getSeed() : server.getOverworld().getSeed();

        // Register the per-world seed override BEFORE the ServerWorld is constructed.
        // ServerChunkLoadingManager (built inside the ServerWorld constructor) calls
        // world.getSeed() to build the NoiseConfig — WorldSeedMixin reads from
        // SeedRegistry to return our seed instead of the overworld's.
        SeedRegistry.set(worldKey, tbSeed);

        // TerraBlender integration: its per-dim biome routing + surface rules are only
        // set up at server start for datapack dims. Runtime-created dims must be
        // registered explicitly, otherwise modded biomes render as empty shells
        // (no BWG/M&L/maple features, plain stone surfaces).
        initializeTerraBlenderForDim(worldKey, dimType, generator, tbSeed);

        // Create world properties (derived from overworld)
        var properties = new net.minecraft.world.level.UnmodifiableLevelProperties(
            server.getSaveProperties(),
            (net.minecraft.world.level.ServerWorldProperties) server.getOverworld().getLevelProperties()
        );

        // Get session from server
        var session = ((MinecraftServerAccessor) server).cg_getSession();

        long seed = tbSeed;

        // No-op progress listener
        var listener = new net.minecraft.server.WorldGenerationProgressListener() {
            @Override public void start(net.minecraft.util.math.ChunkPos spawnPos) {}
            @Override public void setChunkStatus(net.minecraft.util.math.ChunkPos pos, net.minecraft.world.chunk.ChunkStatus status) {}
            @Override public void start() {}
            @Override public void stop() {}
        };

        // Create the ServerWorld
        ServerWorld world = new ServerWorld(
            server,
            Util.getMainWorkerExecutor(),
            session,
            properties,
            worldKey,
            dimOptions,
            listener,
            false, // debugWorld
            seed,
            List.of(), // spawners (no phantoms/patrols)
            false, // shouldTickTime
            null  // randomSequencesState
        );

        // Register in server's world map
        ((MinecraftServerAccessor) server).cg_getWorlds().put(worldKey, world);

        // Notify other mods that a new world was loaded — Fabric's ServerWorldEvents.LOAD
        // is normally fired by Mojang's MinecraftServer.createWorlds, but our runtime
        // creation bypasses that path. Without this, mods that rely on the event miss
        // the dim entirely (e.g. EnhancedCelestials never materialises its lunar data,
        // so the lunar forecast hologram stays empty in CheeseGuard dims).
        try {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.LOAD
                .invoker().onWorldLoad(server, world);
        } catch (Throwable t) {
            CheeseGuardMod.LOGGER.warn("[CG] ServerWorldEvents.LOAD listener failed for {}: {}",
                    dimId, t.getMessage());
        }

        // Auto-persist any future /worldborder changes for this dim
        attachBorderListener(world);

        CheeseGuardMod.LOGGER.info("[CG] Runtime dimension created: {}", dimId);
    }

    private static Boolean tbPresent = null;
    private static java.lang.reflect.Method tbInitMethod = null;

    private void initializeTerraBlenderForDim(RegistryKey<World> worldKey,
                                              RegistryEntry<DimensionType> dimType,
                                              ChunkGenerator generator,
                                              long seed) {
        if (tbPresent != null && !tbPresent) return;
        try {
            if (tbInitMethod == null) {
                Class<?> cls = Class.forName("terrablender.util.LevelUtils");
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (m.getParameterCount() == 5 && m.getReturnType() == void.class
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        Class<?>[] pt = m.getParameterTypes();
                        if (pt[0] == net.minecraft.registry.DynamicRegistryManager.class
                            && pt[1] == RegistryEntry.class
                            && pt[2] == RegistryKey.class
                            && pt[3] == ChunkGenerator.class
                            && pt[4] == long.class) {
                            tbInitMethod = m;
                            break;
                        }
                    }
                }
                if (tbInitMethod == null) {
                    tbPresent = false;
                    return;
                }
                tbPresent = true;
            }
            RegistryKey<DimensionOptions> dimOptKey = RegistryKey.of(RegistryKeys.DIMENSION, worldKey.getValue());
            tbInitMethod.invoke(null, server.getRegistryManager(), dimType, dimOptKey, generator, seed);
            CheeseGuardMod.LOGGER.info("[CG] TerraBlender initialized for dim {}", worldKey.getValue());
        } catch (ClassNotFoundException e) {
            tbPresent = false;
        } catch (Throwable e) {
            CheeseGuardMod.LOGGER.warn("[CG] TerraBlender init skipped for {}: {}", worldKey.getValue(), e.getMessage());
        }
    }

    private RegistryEntry<DimensionType> getDimensionTypeEntry(DimensionConfig config) {
        RegistryKey<DimensionType> typeKey;
        String customId = config.getCustomGenTypeId();
        if (customId != null) {
            var provider = com.cheeseguard.api.GenTypeRegistry.get(customId);
            typeKey = provider != null ? provider.dimensionType() : DimensionTypes.OVERWORLD;
        } else {
            typeKey = switch (config.getGenType()) {
                case NETHER -> DimensionTypes.THE_NETHER;
                case END -> DimensionTypes.THE_END;
                default -> DimensionTypes.OVERWORLD;
            };
        }
        return server.getRegistryManager().get(RegistryKeys.DIMENSION_TYPE)
            .getEntry(typeKey).orElseThrow();
    }

    private ChunkGenerator createChunkGenerator(DimensionConfig config) {
        // Plugin gen-types take priority (registered via Fabric entrypoint "cheeseguard-gentype").
        String customId = config.getCustomGenTypeId();
        if (customId != null) {
            var provider = com.cheeseguard.api.GenTypeRegistry.get(customId);
            if (provider != null) {
                try {
                    return provider.createChunkGenerator(server, config);
                } catch (Throwable t) {
                    CheeseGuardMod.LOGGER.error("[CG] Plugin gen-type '{}' failed: {} — falling back to VOID",
                        customId, t.getMessage(), t);
                }
            } else {
                CheeseGuardMod.LOGGER.error("[CG] Unknown customGenTypeId '{}' — falling back to VOID", customId);
            }
            return createFlatGenerator(List.of(), BiomeKeys.THE_VOID, false);
        }
        return switch (config.getGenType()) {
            case VOID -> createFlatGenerator(List.of(), BiomeKeys.THE_VOID, false);
            case FLAT -> createFlatGenerator(parseLayers(config.getFlatLayers()), BiomeKeys.PLAINS, config.isGenerateStructures());
            case NORMAL -> copyGeneratorFrom(World.OVERWORLD);
            case NETHER -> copyGeneratorFrom(World.NETHER);
            case END -> copyGeneratorFrom(World.END);
        };
    }

    private ChunkGenerator createFlatGenerator(List<FlatChunkGeneratorLayer> layers, RegistryKey<Biome> biomeKey, boolean structures) {
        RegistryEntry<Biome> biome = server.getRegistryManager().get(RegistryKeys.BIOME)
            .getEntry(biomeKey).orElseThrow();
        // Optional.empty() = no structure override (no structures generated)
        // Passing a registry entry would enable specific structure sets.
        // For simplicity: structures==false means Optional.empty(); true means the same
        // (enabling per-structure-set for FLAT requires explicit set registration, not trivial at runtime).
        // The important case is VOID (always no structures) and FLAT default (no structures).
        FlatChunkGeneratorConfig flatConfig = new FlatChunkGeneratorConfig(
            Optional.empty(), biome, List.of()
        );
        flatConfig.getLayers().addAll(layers);
        flatConfig.updateLayerBlocks();
        return new FlatChunkGenerator(flatConfig);
    }

    private ChunkGenerator copyGeneratorFrom(RegistryKey<World> sourceKey) {
        ServerWorld source = server.getWorld(sourceKey);
        if (source != null) {
            return source.getChunkManager().getChunkGenerator();
        }
        // Fallback to void
        return createFlatGenerator(List.of(), BiomeKeys.THE_VOID, false);
    }

    /**
     * Parse layer string like "bedrock,3*stone,grass_block" into FlatChunkGeneratorLayer list.
     */
    private List<FlatChunkGeneratorLayer> parseLayers(String spec) {
        if (spec == null || spec.isBlank()) return List.of();
        List<FlatChunkGeneratorLayer> layers = new ArrayList<>();
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isBlank()) continue;
            int count = 1;
            String blockId = part;
            if (part.contains("*")) {
                String[] sp = part.split("\\*", 2);
                try { count = Integer.parseInt(sp[0].trim()); } catch (Exception ignored) {}
                blockId = sp[1].trim();
            }
            Block block = resolveBlock(blockId);
            if (block != null) {
                layers.add(new FlatChunkGeneratorLayer(count, block));
            }
        }
        return layers;
    }

    private Block resolveBlock(String id) {
        if (!id.contains(":")) id = "minecraft:" + id;
        Identifier bid = Identifier.tryParse(id);
        if (bid == null) return Blocks.STONE;
        return net.minecraft.registry.Registries.BLOCK.getOrEmpty(bid).orElse(Blocks.STONE);
    }

    // ── Delete dimension ─────────────────────────────────────────────────────

    public boolean deleteDimension(String configId) {
        DimensionConfig config = getConfig(configId);
        if (config == null || config.isBuiltIn()) return false;

        // Can't delete vanilla
        String worldId = config.getWorldId();
        if (worldId.equals("minecraft:overworld") || worldId.equals("minecraft:the_nether")) return false;

        // ── Cascade: delete all per-player child dims of this master first ──
        java.util.List<DimensionConfig> children = new java.util.ArrayList<>();
        for (DimensionConfig c : customDimensions) {
            String masterId = getMasterDimId(c.getId());
            if (configId.equals(masterId)) children.add(c);
        }
        for (DimensionConfig child : children) {
            String personalId = child.getId();
            java.util.UUID ownerUuid = null;
            if (playerDimOwners.containsKey(configId)) {
                for (java.util.UUID u : playerDimOwners.get(configId)) {
                    if (getPlayerDimId(configId, u).equals(personalId)) { ownerUuid = u; break; }
                }
            }
            if (ownerUuid != null) {
                deletePlayerDimension(configId, ownerUuid);
            } else {
                // Fallback: inline cleanup for orphaned child (no owner tracked)
                Identifier cid = Identifier.of("cheeseguard", personalId);
                WaystonesHook.removeWaystonesInDimension(server, cid);
                RegistryKey<World> cwk = RegistryKey.of(RegistryKeys.WORLD, cid);
                ServerWorld cw = server.getWorld(cwk);
                if (cw != null) {
                    for (ServerPlayerEntity pl : new ArrayList<>(cw.getPlayers())) {
                        var ow = server.getOverworld();
                        var sp = ow.getSpawnPos();
                        pl.teleport(ow, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5,
                            Set.of(), pl.getYaw(), pl.getPitch());
                    }
                    try { cw.save(null, true, false); } catch (Exception ignored) {}
                    ((MinecraftServerAccessor) server).cg_getWorlds().remove(cwk);
                }
                Path cdir = getDimWorldFolder(child);
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                    try { if (Files.exists(cdir)) deleteRecursively(cdir); }
                    catch (IOException e) { CheeseGuardMod.LOGGER.warn("[CG] Failed to delete child dim folder '{}': {}", personalId, e.getMessage()); }
                });
                Path csnap = getSnapshotFolder(child);
                if (Files.exists(csnap)) { try { deleteRecursively(csnap); } catch (IOException ignored) {} }
                customDimensions.remove(child);
            }
        }

        // Clean up waystones registered in this dimension (Waystones mod, soft dep).
        // Done before unload so the API can resolve registry state cleanly; the
        // remove call also syncs to all connected clients (incl. JourneyMap/Xaero pins).
        Identifier dimId = Identifier.of("cheeseguard", config.getId());
        int removedWaystones = WaystonesHook.removeWaystonesInDimension(server, dimId);
        if (removedWaystones > 0) {
            CheeseGuardMod.LOGGER.info("[CG] Cleaned up {} waystone(s) from dim {}", removedWaystones, dimId);
        }

        // Unload the world
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
        ServerWorld world = server.getWorld(worldKey);
        if (world != null) {
            // TP all players in this dimension to overworld
            for (ServerPlayerEntity pl : new ArrayList<>(world.getPlayers())) {
                var ow = server.getOverworld();
                var sp = ow.getSpawnPos();
                pl.teleport(ow, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5,
                    Set.of(), pl.getYaw(), pl.getPitch());
                pl.sendMessage(net.minecraft.text.Text.translatable(
                    "cheeseguard.dimension.deleted", config.getDisplayName())
                    .formatted(net.minecraft.util.Formatting.RED), false);
            }

            // Save and close the world
            try { world.save(null, true, false); } catch (Exception ignored) {}

            // Remove from server map
            ((MinecraftServerAccessor) server).cg_getWorlds().remove(worldKey);
        }

        // Delete the world folder from disk
        // MC saves custom dimensions at: <worldSaveDir>/dimensions/<namespace>/<id>/
        try {
            // Derive world root from level name: <runDir>/<levelName>/dimensions/cheeseguard/<id>
            String levelName = server.getSaveProperties().getLevelName();
            java.nio.file.Path worldRoot = server.getRunDirectory().resolve(levelName);
            java.nio.file.Path dimFolder = worldRoot.resolve("dimensions")
                .resolve("cheeseguard")
                .resolve(config.getId());
            if (java.nio.file.Files.exists(dimFolder)) {
                deleteRecursively(dimFolder);
                CheeseGuardMod.LOGGER.info("[CG] Deleted world folder: {}", dimFolder);
            } else {
                CheeseGuardMod.LOGGER.info("[CG] No world folder found at {} (never generated chunks?)", dimFolder);
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to delete world folder for {}: {}", configId, e.getMessage());
        }

        // Delete the snapshot folder if present
        try {
            Path snapDir = getSnapshotFolder(config);
            if (Files.exists(snapDir)) {
                deleteRecursively(snapDir);
                CheeseGuardMod.LOGGER.info("[CG] Deleted snapshot folder for {}", configId);
            }
        } catch (IOException e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to delete snapshot folder for {}: {}", configId, e.getMessage());
        }

        // Clean up in-memory state keyed by this configId
        playerDimOwners.remove(configId);
        pendingResets.remove(configId);
        onEmptyOccupied.remove(configId);
        scheduledResetLastDay.remove(configId);

        customDimensions.remove(config);

        // Also purge any leftover shadow entry pointing to the same world — e.g. an
        // auto-discovered builtIn stub ("cheeseguard:<id>") that shadows this custom
        // dim. Both resolve to the same getWorldId(); without this, the stub would
        // linger in cheeseguard_dimensions.json after the real config is deleted.
        String deletedWorldId = config.getWorldId();
        boolean removedShadow = customDimensions.removeIf(other -> other.getWorldId().equals(deletedWorldId));
        if (removedShadow) {
            CheeseGuardMod.LOGGER.info("[CG] Removed shadow stub entry for world {}", deletedWorldId);
        }

        save();
        CheeseGuardMod.LOGGER.info("[CG] Deleted dimension: {}", configId);
        return true;
    }

    /** Recursively delete a directory and all its contents. */
    private void deleteRecursively(java.nio.file.Path dir) throws java.io.IOException {
        if (!java.nio.file.Files.exists(dir)) return;
        java.nio.file.Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                java.nio.file.Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path d,
                    java.io.IOException exc) throws java.io.IOException {
                java.nio.file.Files.delete(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    // ── TP ───────────────────────────────────────────────────────────────────

    public boolean tpToDimension(ServerPlayerEntity player, String worldId) {
        ServerWorld target = findWorld(worldId);
        if (target == null) return false;

        // Check if we have a custom config with spawn point
        DimensionConfig config = customDimensions.stream()
            .filter(c -> c.getWorldId().equals(worldId)).findFirst().orElse(null);

        double x, y, z;
        if (config != null) {
            x = config.getSpawnX(); y = config.getSpawnY(); z = config.getSpawnZ();
        } else {
            // Use world spawn or 0,80,0
            var sp = target.getSpawnPos();
            if (sp != null) { x = sp.getX() + 0.5; y = sp.getY(); z = sp.getZ() + 0.5; }
            else { x = 0.5; y = 80; z = 0.5; }
        }

        player.teleport(target, x, y, z, Set.of(), player.getYaw(), player.getPitch());
        return true;
    }

    /**
     * Immediately applies the time lock for a given config to its world.
     * Called both from the UI handler (instant feedback) and from the tick loop.
     */
    public void applyTimeLock(DimensionConfig config) {
        if (!config.isTimeLocked()) return;
        ServerWorld world = findWorld(config.getWorldId());
        if (world == null) return;
        long lockedTime = config.getLockedTime();
        for (ServerPlayerEntity p : world.getPlayers()) {
            p.networkHandler.sendPacket(
                new net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket(
                    world.getTime(), -lockedTime, false
                )
            );
        }
        CheeseGuardMod.LOGGER.info("[CG] applyTimeLock: sent time {} to {} players (dim={})",
                lockedTime, world.getPlayers().size(), config.getId());
    }

    /**
     * Apply this config's WorldBorder settings to its ServerWorld and resync to all players in it.
     * If borderEnabled is false, the border is reset to a "no-border" state (size = 6e7).
     */
    public void applyWorldBorder(DimensionConfig config) {
        ServerWorld world = findWorld(config.getWorldId());
        if (world == null) return;
        net.minecraft.world.border.WorldBorder border = world.getWorldBorder();
        boolean prevMute = muteBorderListener;
        muteBorderListener = true;
        try {
            // Always set the center: keeps the saved coords coherent across enable/disable
            // toggles and prevents stale vanilla center from leaking through.
            border.setCenter(config.getBorderCenterX(), config.getBorderCenterZ());
            border.setSafeZone(0.0);
            if (config.isBorderEnabled()) {
                border.setSize(config.getBorderSize());
                border.setDamagePerBlock(config.getBorderDamage());
                border.setWarningBlocks(config.getBorderWarningBlocks());
                border.setWarningTime(config.getBorderWarningTime());
            } else {
                // No-border state — deliberately overrides whatever vanilla level.dat had.
                border.setSize(6.0E7);
                border.setDamagePerBlock(0.2);
                border.setWarningBlocks(5);
                border.setWarningTime(15);
            }
        } finally {
            muteBorderListener = prevMute;
        }
        // Re-send to clients in this dim
        var pkt = new net.minecraft.network.packet.s2c.play.WorldBorderInitializeS2CPacket(border);
        for (ServerPlayerEntity p : world.getPlayers()) {
            p.networkHandler.sendPacket(pkt);
        }
        CheeseGuardMod.LOGGER.info("[CG] applyWorldBorder: dim={} enabled={} size={} center=({},{})",
            config.getId(), config.isBorderEnabled(), config.getBorderSize(),
            config.getBorderCenterX(), config.getBorderCenterZ());
    }

    /**
     * Called from {@code ServerWorldEvents.LOAD} for EVERY ServerWorld that
     * appears on the server — including dims loaded LATE (cobblemonraiddens,
     * Fabric runtime dims, our own per-player dims). This closes the gap left
     * by {@link #init(MinecraftServer)} which only handles dims that exist at
     * boot time.
     *
     * <p>Safe to call before {@code init()} has run — in that case we no-op
     * because {@code init()} will sweep all existing worlds at SERVER_STARTED.
     * After init, this is the canonical entry point for new worlds: we
     * auto-register, attach the persistence listener, and apply the stored
     * border config so the runtime state matches what the dashboard shows.</p>
     */
    public void onServerWorldLoaded(ServerWorld world) {
        if (server == null) return; // init() will sweep all worlds at SERVER_STARTED
        if (world == null) return;
        try {
            String worldId = world.getRegistryKey().getValue().toString();
            // Auto-register if not tracked yet
            if (getConfigByWorldId(worldId) == null) {
                DimensionConfig c = new DimensionConfig(worldId, friendlyName(worldId));
                c.setBuiltIn(true);
                c.setGenType(guessGenType(worldId));
                snapshotBorderInto(c, world);
                customDimensions.add(c);
                CheeseGuardMod.LOGGER.info("[CG] Auto-registered late-loaded dim: {}", worldId);
            }
            attachBorderListener(world);
            DimensionConfig cfg = getConfigByWorldId(worldId);
            if (cfg != null) applyWorldBorder(cfg);
        } catch (Throwable t) {
            CheeseGuardMod.LOGGER.warn("[CG] onServerWorldLoaded failed for {}: {}",
                world.getRegistryKey().getValue(), t.getMessage());
        }
    }

    /**
     * Watchdog — runs every {@value #BORDER_DRIFT_CHECK_INTERVAL_TICKS} ticks
     * to verify that each managed dimension's runtime WorldBorder matches its
     * stored config. If another mod or some path we don't control mutates the
     * runtime border, we re-apply the config so the divergence is corrected
     * within seconds rather than persisting until the next dashboard action.
     *
     * <p>Self-induced applies are guarded by {@code muteBorderListener} inside
     * {@link #applyWorldBorder} so they don't trigger save loops.</p>
     */
    private static final int BORDER_DRIFT_CHECK_INTERVAL_TICKS = 200; // 10s
    private long lastBorderDriftCheck;

    private void verifyBorderDriftFree() {
        if (server == null) return;
        for (DimensionConfig c : new ArrayList<>(customDimensions)) {
            try {
                ServerWorld w = findWorld(c.getWorldId());
                if (w == null) continue;
                net.minecraft.world.border.WorldBorder b = w.getWorldBorder();
                boolean drift = false;
                if (c.isBorderEnabled()) {
                    if (Math.abs(b.getSize() - c.getBorderSize()) > 0.5
                        || b.getCenterX() != c.getBorderCenterX()
                        || b.getCenterZ() != c.getBorderCenterZ()) {
                        drift = true;
                    }
                } else {
                    // Disabled = no-border state. If runtime border is finite
                    // (< threshold), some external path enabled one — undo it.
                    if (b.getSize() < BORDER_NO_BORDER_THRESHOLD) drift = true;
                }
                if (drift) {
                    CheeseGuardMod.LOGGER.warn(
                        "[CG] Border drift detected on {}: runtime size={} center=({},{}) — re-applying config",
                        c.getWorldId(), b.getSize(), b.getCenterX(), b.getCenterZ());
                    applyWorldBorder(c);
                }
            } catch (Throwable t) {
                CheeseGuardMod.LOGGER.debug("[CG] border drift check failed for {}: {}",
                    c.getWorldId(), t.getMessage());
            }
        }
    }

    /**
     * Ensure a managed dimension's border auto-persists. Whenever the border state
     * changes (e.g., player runs /worldborder set 100 in this dim), we mirror the
     * change into the matching DimensionConfig and schedule a debounced save so
     * the values survive a restart. Self-induced changes (from applyWorldBorder)
     * are skipped via the mute flag to avoid feedback loops.
     */
    public void attachBorderListener(ServerWorld world) {
        if (world == null) return;
        final String worldId = world.getRegistryKey().getValue().toString();
        if (!borderListenerAttached.add(worldId)) return; // already attached
        // Auto-register the dim if we don't track it yet (vanilla/modded built-in).
        // Snapshot the current vanilla border state so the first applyWorldBorder
        // (which we now run unconditionally at boot) is a no-op rather than a clobber.
        if (getConfigByWorldId(worldId) == null) {
            DimensionConfig c = new DimensionConfig(worldId, friendlyName(worldId));
            c.setBuiltIn(true);
            c.setGenType(guessGenType(worldId));
            snapshotBorderInto(c, world);
            customDimensions.add(c);
        }
        net.minecraft.world.border.WorldBorder border = world.getWorldBorder();
        border.addListener(new net.minecraft.world.border.WorldBorderListener() {
            @Override public void onSizeChange(net.minecraft.world.border.WorldBorder b, double size) {
                if (muteBorderListener) return;
                DimensionConfig c = getConfigByWorldId(worldId);
                if (c == null) return;
                // Honor the dashboard's explicit "disabled" preference: do NOT
                // auto-flip enabled back to true from a runtime size change
                // (other mods, /worldborder commands, vanilla level.dat replay).
                // Only update size if the user has explicitly enabled this dim
                // — then runtime changes are real edits to track.
                if (!c.isBorderEnabled()) {
                    CheeseGuardMod.LOGGER.debug("[CG] Border listener: ignoring size change ({}) on disabled dim {}",
                            size, worldId);
                    return;
                }
                if (c.getBorderSize() != size) {
                    c.setBorderSize(size);
                    scheduleBorderSave();
                }
            }
            @Override public void onInterpolateSize(net.minecraft.world.border.WorldBorder b, double from, double to, long time) {
                if (muteBorderListener) return;
                DimensionConfig c = getConfigByWorldId(worldId);
                if (c == null) return;
                // Same rule as onSizeChange: don't auto-enable disabled dims.
                if (!c.isBorderEnabled()) return;
                if (c.getBorderSize() != to) {
                    c.setBorderSize(to);
                    scheduleBorderSave();
                }
            }
            @Override public void onCenterChanged(net.minecraft.world.border.WorldBorder b, double cx, double cz) {
                if (muteBorderListener) return;
                DimensionConfig c = getConfigByWorldId(worldId);
                if (c == null) return;
                if (c.getBorderCenterX() != cx || c.getBorderCenterZ() != cz) {
                    c.setBorderCenterX(cx);
                    c.setBorderCenterZ(cz);
                    scheduleBorderSave();
                }
            }
            @Override public void onWarningTimeChanged(net.minecraft.world.border.WorldBorder b, int t) {
                if (muteBorderListener) return;
                DimensionConfig c = getConfigByWorldId(worldId);
                if (c == null || c.getBorderWarningTime() == t) return;
                c.setBorderWarningTime(t);
                scheduleBorderSave();
            }
            @Override public void onWarningBlocksChanged(net.minecraft.world.border.WorldBorder b, int blocks) {
                if (muteBorderListener) return;
                DimensionConfig c = getConfigByWorldId(worldId);
                if (c == null || c.getBorderWarningBlocks() == blocks) return;
                c.setBorderWarningBlocks(blocks);
                scheduleBorderSave();
            }
            @Override public void onDamagePerBlockChanged(net.minecraft.world.border.WorldBorder b, double dmg) {
                if (muteBorderListener) return;
                DimensionConfig c = getConfigByWorldId(worldId);
                if (c == null || c.getBorderDamage() == dmg) return;
                c.setBorderDamage(dmg);
                scheduleBorderSave();
            }
            @Override public void onSafeZoneChanged(net.minecraft.world.border.WorldBorder b, double sz) {}
        });
    }

    /**
     * Detach all {@link net.minecraft.world.border.WorldBorderListener.WorldBorderSyncer}
     * listeners from the overworld's WorldBorder. Vanilla wires one per non-overworld dim
     * so any change to overworld replicates to nether and end; that prevents true per-dim
     * borders. After this, each ServerWorld's WorldBorder is fully independent.
     */
    private void breakBorderSyncerChain(MinecraftServer server) {
        net.minecraft.server.world.ServerWorld overworld = server.getOverworld();
        if (overworld == null) return;
        net.minecraft.world.border.WorldBorder border = overworld.getWorldBorder();
        java.util.List<net.minecraft.world.border.WorldBorderListener> listeners = border.getListeners();
        java.util.List<net.minecraft.world.border.WorldBorderListener> toRemove = new java.util.ArrayList<>();
        for (net.minecraft.world.border.WorldBorderListener l : listeners) {
            if (l instanceof net.minecraft.world.border.WorldBorderListener.WorldBorderSyncer) {
                toRemove.add(l);
            }
        }
        for (net.minecraft.world.border.WorldBorderListener l : toRemove) {
            border.removeListener(l);
        }
        if (!toRemove.isEmpty()) {
            CheeseGuardMod.LOGGER.info(
                "[CG] Detached {} vanilla WorldBorderSyncer listener(s) from overworld — per-dim borders are now independent.",
                toRemove.size());
        }
    }

    /** Debounce: save 1s (20 ticks) after the last border change to coalesce bursts. */
    private void scheduleBorderSave() {
        borderSavePendingTick = tickCounter + 20L;
    }

    /**
     * Read the live WorldBorder of a world into a config — no side effect on the world.
     * Only flags the config as enabled when the size is below the "no-border" threshold,
     * matching how the runtime listener interprets size changes.
     */
    private static void snapshotBorderInto(DimensionConfig c, ServerWorld world) {
        if (world == null) return;
        net.minecraft.world.border.WorldBorder b = world.getWorldBorder();
        c.setBorderCenterX(b.getCenterX());
        c.setBorderCenterZ(b.getCenterZ());
        double size = b.getSize();
        boolean enabled = size < BORDER_NO_BORDER_THRESHOLD;
        c.setBorderEnabled(enabled);
        if (enabled) c.setBorderSize(size);
        c.setBorderDamage(b.getDamagePerBlock());
        c.setBorderWarningBlocks(b.getWarningBlocks());
        c.setBorderWarningTime(b.getWarningTime());
    }

    /**
     * Send the WorldBorder state of the player's current dimension to that player.
     * Vanilla only syncs the Overworld border to clients automatically; custom dims
     * need explicit packet on join + on world change.
     */
    public void sendBorderToPlayer(ServerPlayerEntity player) {
        if (player == null || player.getServerWorld() == null) return;
        var world = player.getServerWorld();
        var border = world.getWorldBorder();
        var pkt = new net.minecraft.network.packet.s2c.play.WorldBorderInitializeS2CPacket(border);
        player.networkHandler.sendPacket(pkt);
    }

    /**
     * True if the given flag (by ID) is denied at the dimension level for the world.
     * Returns false for built-in dims unless explicitly configured.
     */
    public boolean isFlagDeniedDim(String worldId, String flagId) {
        DimensionConfig c = getConfigByWorldId(worldId);
        return c != null && c.isFlagDenied(flagId);
    }

    public void applyWeatherLock(DimensionConfig config) {
        if (!config.isWeatherLocked()) return;
        switch (config.getLockedWeather()) {
            case "clear" -> executeCommand("weather clear 1000000");
            case "rain" -> executeCommand("weather rain 1000000");
            case "thunder" -> executeCommand("weather thunder 1000000");
            default -> CheeseGuardMod.LOGGER.warn("[CG] applyWeatherLock: unknown weather value '{}'", config.getLockedWeather());
        }
        CheeseGuardMod.LOGGER.info("[CG] applyWeatherLock: /weather {} (dim={})", config.getLockedWeather(), config.getId());
    }

    private void executeCommand(String command) {
        if (server == null) return;
        var src = server.getCommandSource().withSilent();
        server.getCommandManager().executeWithPrefix(src, command);
    }

    private ServerWorld findWorld(String worldId) {
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldId)) return w;
        }
        return null;
    }

    // ── Tick (enforce time/weather locks) ────────────────────────────────────

    private int tickCounter = 0;

    // Delayed tasks: (firesAtTick, runnable). Fires when tickCounter reaches firesAtTick.
    private final java.util.List<long[]> delayedTaskKeys = new java.util.ArrayList<>();
    private final java.util.List<Runnable> delayedTaskRuns = new java.util.ArrayList<>();
    /** Schedule a runnable to fire after `delayTicks` server ticks. Run on server thread. */
    public void scheduleAfterTicks(int delayTicks, Runnable r) {
        synchronized (delayedTaskKeys) {
            delayedTaskKeys.add(new long[]{ tickCounter + Math.max(1, delayTicks) });
            delayedTaskRuns.add(r);
        }
    }

    // Keep inventory: UUID → saved inventory NBT for dimension keepInventory
    private final Map<UUID, net.minecraft.nbt.NbtCompound> keepInvSnapshots = new HashMap<>();

    /**
     * Called from death mixin when player dies in a keepInventory dimension.
     * Saves inventory and clears it so items don't drop on the ground.
     */
    public void onPlayerDeathKeepInv(ServerPlayerEntity player) {
        // Don't double-save if event already handled it
        var mod = CheeseGuardMod.getInstance();
        if (mod != null && mod.getEventManager() != null) {
            var evt = mod.getEventManager().getActiveEventForPlayer(player.getUuid());
            if (evt != null) return; // Event death handler takes priority
        }

        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        player.writeNbt(nbt);
        keepInvSnapshots.put(player.getUuid(), nbt);

        // Clear inventory so nothing drops
        com.cheeseguard.data.EventParticipant.clearInventoryForEvent(player);
        CheeseGuardMod.LOGGER.info("[CG] KeepInventory saved for {} (dimension rule)", player.getName().getString());
    }

    /**
     * Called from AFTER_RESPAWN. Restores inventory if dimension keepInventory was active.
     */
    public void onPlayerRespawnKeepInv(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer) {
        net.minecraft.nbt.NbtCompound nbt = keepInvSnapshots.remove(newPlayer.getUuid());
        if (nbt == null) return;

        // Preserve current post-respawn state
        double cx = newPlayer.getX(), cy = newPlayer.getY(), cz = newPlayer.getZ();
        float cYaw = newPlayer.getYaw(), cPitch = newPlayer.getPitch();
        var cGm = newPlayer.interactionManager.getGameMode();

        // Nuclear restore
        newPlayer.readNbt(nbt);

        // Re-apply post-respawn state
        newPlayer.refreshPositionAndAngles(cx, cy, cz, cYaw, cPitch);
        newPlayer.interactionManager.changeGameMode(cGm);
        newPlayer.setHealth(newPlayer.getMaxHealth());
        newPlayer.getHungerManager().setFoodLevel(20);
        newPlayer.getHungerManager().setSaturationLevel(5.0f);
        newPlayer.getHungerManager().setExhaustion(0f);
        newPlayer.setFireTicks(0);

        // Sync
        newPlayer.getInventory().markDirty();
        newPlayer.currentScreenHandler.sendContentUpdates();
        newPlayer.playerScreenHandler.sendContentUpdates();

        newPlayer.sendMessage(net.minecraft.text.Text.translatable(
            "cheeseguard.dimension.keep_inventory_restored")
            .formatted(net.minecraft.util.Formatting.GREEN), false);
        CheeseGuardMod.LOGGER.info("[CG] KeepInventory restored for {} (dimension rule)", newPlayer.getName().getString());
    }

    /** Called from AFTER_RESPAWN to redirect overworld respawns to spawnRedirect dimension. */
    public void onPlayerRespawnRedirect(ServerPlayerEntity player) {
        redirectToSpawnDimension(player);
    }

    public void tick() {
        tickCounter++;
        if (server == null) return;

        // Flush pending world-border save (debounced 1s after last change)
        if (borderSavePendingTick >= 0 && tickCounter >= borderSavePendingTick) {
            borderSavePendingTick = -1L;
            try { save(); } catch (Exception e) {
                CheeseGuardMod.LOGGER.error("[CG] border auto-save failed: {}", e.getMessage(), e);
            }
        }

        // Border drift watchdog — verifies each managed dim's runtime border
        // still matches its stored config. Catches mutations from external
        // paths (other mods, late dim loads that bypassed our event hook).
        if (tickCounter - lastBorderDriftCheck >= BORDER_DRIFT_CHECK_INTERVAL_TICKS) {
            lastBorderDriftCheck = tickCounter;
            verifyBorderDriftFree();
        }

        // Process delayed tasks
        synchronized (delayedTaskKeys) {
            for (int i = delayedTaskKeys.size() - 1; i >= 0; i--) {
                if (tickCounter >= delayedTaskKeys.get(i)[0]) {
                    Runnable r = delayedTaskRuns.remove(i);
                    delayedTaskKeys.remove(i);
                    try { r.run(); } catch (Exception e) {
                        CheeseGuardMod.LOGGER.error("[CG] delayed task failed: {}", e.getMessage(), e);
                    }
                }
            }
        }

        // ══ Per-tick: instant creative safety net ══════════════════════════════
        // Runs EVERY tick — lightweight, just checks GameMode for non-ops.
        // This eliminates the ~1s flottement when a builder leaves a construction dim.
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.hasPermissionLevel(2)) continue; // ops manage themselves
            if (player.interactionManager.getGameMode() != net.minecraft.world.GameMode.CREATIVE) continue;

            String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
            DimensionConfig cfg = getConfigByWorldId(worldId);
            boolean legitimateCreative = cfg != null
                && cfg.isConstructionMode()
                && cfg.isConstructionBuilder(player.getUuid().toString())
                && clearInvSnapshots.containsKey(player.getUuid());

            if (!legitimateCreative) {
                // Player is creative but shouldn't be — force survival NOW
                CheeseGuardMod.LOGGER.warn(
                    "[CG] ROGUE CREATIVE detected for {} in '{}' — forcing survival instantly",
                    player.getName().getString(), worldId);
                // If they have a snapshot (left construction dim), do a full exit restore
                if (clearInvSnapshots.containsKey(player.getUuid())) {
                    exitClearInventory(player);
                    exitConstructionMode(player);
                } else {
                    // No snapshot: just strip creative, no inventory to restore
                    player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                    player.sendMessage(
                        net.minecraft.text.Text.translatable(
                            "cheeseguard.dimension.creative_removed")
                            .formatted(net.minecraft.util.Formatting.RED),
                        false);
                }
            }
        }

        // Construction mode tracking — every 1s (20 ticks)
        if (tickCounter % 20 == 0) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                // Skip ops — they can go anywhere
                if (player.hasPermissionLevel(2)) {
                    String currentWorld = player.getServerWorld().getRegistryKey().getValue().toString();
                    String lastWorld = lastKnownWorld.get(player.getUuid());

                    if (lastWorld != null && !lastWorld.equals(currentWorld)) {
                        DimensionConfig fromCfg = getConfigByWorldId(lastWorld);
                        DimensionConfig toCfg = getConfigByWorldId(currentWorld);
                        // Leaving dimension → execute leave commands
                        if (fromCfg != null) executeLeaveCommands(player, fromCfg);
                        // Leaving clearInventory dim → restore inventory
                        if (fromCfg != null && fromCfg.isClearInventory() && clearInvSnapshots.containsKey(player.getUuid())) {
                            exitClearInventory(player);
                        }
                        // Leaving construction dim → restore gamemode
                        if (fromCfg != null && fromCfg.isConstructionMode()) {
                            exitConstructionMode(player);
                        }
                        // Entering clearInventory dim → save & clear inventory
                        if (toCfg != null && toCfg.isClearInventory() && !clearInvSnapshots.containsKey(player.getUuid())) {
                            enterClearInventory(player);
                        }
                        // Entering construction dim → set gamemode
                        if (toCfg != null && toCfg.isConstructionMode()) {
                            enterConstructionMode(player, toCfg);
                        }
                    } else if (lastWorld == null) {
                        DimensionConfig cfg = getConfigByWorldId(currentWorld);
                        if (cfg != null && cfg.isClearInventory() && !clearInvSnapshots.containsKey(player.getUuid())) {
                            enterClearInventory(player);
                        }
                        if (cfg != null && cfg.isConstructionMode()) {
                            enterConstructionMode(player, cfg);
                        }
                    }
                    // Safety net for ops too
                    DimensionConfig opCfg = getConfigByWorldId(currentWorld);
                    if (clearInvSnapshots.containsKey(player.getUuid())
                            && (opCfg == null || !opCfg.isClearInventory())) {
                        exitClearInventory(player);
                    }
                    lastKnownWorld.put(player.getUuid(), currentWorld);
                    continue;
                }

                String currentWorld = player.getServerWorld().getRegistryKey().getValue().toString();
                String lastWorld = lastKnownWorld.get(player.getUuid());
                DimensionConfig currentCfg = getConfigByWorldId(currentWorld);

                // ── Closed dimension enforcement ──
                // If player is in a closed dimension and not whitelisted, kick them out
                if (currentCfg != null && !currentCfg.isEnabled()
                        && !currentCfg.isWhitelisted(player.getUuid().toString())) {
                    // TP to overworld spawn
                    ServerWorld ow = server.getOverworld();
                    var sp = ow.getSpawnPos();
                    player.teleport(ow, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5,
                        java.util.Set.of(), player.getYaw(), player.getPitch());
                    player.sendMessage(net.minecraft.text.Text.literal(
                        "\u26D4 " + currentCfg.getEnabledMessage())
                        .formatted(net.minecraft.util.Formatting.RED), false);
                    lastKnownWorld.put(player.getUuid(), ow.getRegistryKey().getValue().toString());
                    continue;
                }

                // ── Clear inventory + construction mode tracking ──
                if (lastWorld != null && !lastWorld.equals(currentWorld)) {
                    DimensionConfig fromCfg = getConfigByWorldId(lastWorld);
                    DimensionConfig toCfg = currentCfg;

                    // Leaving dimension → execute leave commands
                    if (fromCfg != null) executeLeaveCommands(player, fromCfg);
                    // Leaving clearInventory dimension → restore inventory
                    if (fromCfg != null && fromCfg.isClearInventory() && clearInvSnapshots.containsKey(player.getUuid())) {
                        exitClearInventory(player);
                    }
                    // Leaving construction dimension → restore gamemode
                    if (fromCfg != null && fromCfg.isConstructionMode()) {
                        exitConstructionMode(player);
                    }

                    // Entering clearInventory dimension → save + clear inventory
                    if (toCfg != null && toCfg.isClearInventory() && !clearInvSnapshots.containsKey(player.getUuid())) {
                        enterClearInventory(player);
                    }
                    // Entering construction dimension → set gamemode
                    if (toCfg != null && toCfg.isConstructionMode()) {
                        enterConstructionMode(player, toCfg);
                    }
                }
                // First time tracking
                else if (lastWorld == null) {
                    if (currentCfg != null && currentCfg.isClearInventory() && !clearInvSnapshots.containsKey(player.getUuid())) {
                        enterClearInventory(player);
                    }
                    if (currentCfg != null && currentCfg.isConstructionMode()) {
                        enterConstructionMode(player, currentCfg);
                    }
                }

                // ── Safety net: snapshot present but NOT in clearInventory dim ──
                // Can happen if player was teleported out externally or world was toggled
                if (clearInvSnapshots.containsKey(player.getUuid())
                        && (currentCfg == null || !currentCfg.isClearInventory())) {
                    CheeseGuardMod.LOGGER.warn("[CG] Safety net: {} has clearInv snapshot but is in non-clearInventory dim '{}' — forcing restore",
                        player.getName().getString(), currentWorld);
                    exitClearInventory(player);
                }

                lastKnownWorld.put(player.getUuid(), currentWorld);
            }
        }

        // Snapshot auto-reset check — every 1s (20 ticks)
        if (tickCounter % 20 == 0) {
            checkAutoResets();
            checkPerPlayerRedirects();
            checkCustomDimNightSkip();
        }

        // Time/weather lock — every 30s (600 ticks)
        if (tickCounter % 600 != 0) return;

        // ── Periodic cobblemon purge: belt-and-suspenders ─────────────────────
        // Removes any cobblemon that slipped into a cobblemon-blocked dimension
        // (e.g. spawned before toggle, or via an edge-case API path).
        for (net.minecraft.server.world.ServerWorld w : server.getWorlds()) {
            String wId = w.getRegistryKey().getValue().toString();
            if (!isCobblemonBlocked(wId)) continue;
            boolean found = false;
            java.util.List<net.minecraft.entity.Entity> stray = new java.util.ArrayList<>();
            for (net.minecraft.entity.Entity e : w.iterateEntities()) {
                if ("cobblemon".equals(net.minecraft.entity.EntityType.getId(e.getType()).getNamespace())) {
                    stray.add(e);
                    found = true;
                }
            }
            if (found) {
                for (net.minecraft.entity.Entity e : stray) e.discard();
                CheeseGuardMod.LOGGER.warn("[CG] Periodic purge removed {} stray cobblemon from '{}'", stray.size(), wId);
            }
        }

        for (DimensionConfig config : customDimensions) {
            ServerWorld world = findWorld(config.getWorldId());
            if (world == null) continue;

            // Time lock — send per-player time packet (dimension-local, not global)
            if (config.isTimeLocked()) {
                long lockedTime = config.getLockedTime();
                for (ServerPlayerEntity p : world.getPlayers()) {
                    p.networkHandler.sendPacket(
                        new net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket(
                            world.getTime(), -lockedTime, false
                        )
                    );
                }
            }

            // Weather lock — use /weather command (global but reliable)
            if (config.isWeatherLocked()) {
                switch (config.getLockedWeather()) {
                    case "clear" -> executeCommand("weather clear 1000000");
                    case "rain" -> executeCommand("weather rain 1000000");
                    case "thunder" -> executeCommand("weather thunder 1000000");
                }
            }

            // Peaceful simulation: clear hostile mob targets every 5s
            // MC difficulty is server-global so we can't set it per-world.
            // Instead, we force all hostile mobs in the dimension to forget their target.
            if (config.isPeaceful()) {
                world.getEntitiesByClass(net.minecraft.entity.mob.HostileEntity.class,
                    new net.minecraft.util.math.Box(
                        -30000000, world.getBottomY(), -30000000,
                         30000000, world.getTopY(), 30000000),
                    e -> e.isAlive())
                .forEach(mob -> {
                    mob.setTarget(null);
                    mob.setAttacking(false);
                });
            }
        }
    }

    /**
     * Extends vanilla "sleeping skips the night" to CheeseGuard custom dimensions.
     *
     * Vanilla only runs the night-skip on the real {@code minecraft:overworld} and only
     * counts players physically in it. Our custom dims are separate ServerWorlds built
     * mirroring the overworld's (immutable) level properties with {@code shouldTickTime=false}
     * (see createDimension), so players living in e.g. arkensia can sleep but the night
     * never passes — vanilla never advances time for that world and ignores its sleepers.
     *
     * Fix: count sleepers across eligible overworld-mirror custom dims (genType NORMAL, where
     * beds work), and when the playersSleepingPercentage threshold is met, advance the REAL
     * overworld's time to morning (the mirror dims read time through it) and reset weather,
     * exactly like vanilla. Because a non-time-ticking dim never auto-broadcasts its time to
     * clients, we also push a WorldTimeUpdateS2CPacket to those players (same approach as the
     * timeLocked feature) so the morning shows up immediately client-side.
     *
     * Runs on the server thread (called from tick(), every 20 ticks).
     */
    private void checkCustomDimNightSkip() {
        if (server == null) return;
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) return;

        net.minecraft.world.GameRules rules = overworld.getGameRules();
        if (!rules.getBoolean(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE)) return;
        int pct = rules.getInt(net.minecraft.world.GameRules.PLAYERS_SLEEPING_PERCENTAGE);

        // Aggregate non-spectator players and "slept long enough" sleepers across all
        // eligible custom overworld-mirror dimensions. Cheap filters run before findWorld()
        // so the 100+ builtIn/per-player/nether/end stub entries are skipped without cost.
        int total = 0;
        int sleeping = 0;
        java.util.List<ServerWorld> participating = new java.util.ArrayList<>();
        for (DimensionConfig config : customDimensions) {
            if (config.isBuiltIn() || config.isPerPlayerMode() || config.isTimeLocked()) continue;
            if (!config.isEnabled()) continue;
            if (config.getCustomGenTypeId() != null) continue;            // exotic plugin gen-type → skip
            // NORMAL/FLAT/VOID all map to the OVERWORLD dimension type (beds work);
            // NETHER/END map to their own types where beds explode → never sleepable.
            DimensionConfig.GenType gt = config.getGenType();
            if (gt == DimensionConfig.GenType.NETHER || gt == DimensionConfig.GenType.END) continue;

            ServerWorld world = findWorld(config.getWorldId());
            if (world == null) continue;
            java.util.List<ServerPlayerEntity> players = world.getPlayers();
            if (players.isEmpty()) continue;

            boolean hasPlayers = false;
            for (ServerPlayerEntity p : players) {
                if (p.isSpectator()) continue;
                total++;
                hasPlayers = true;
                if (p.canResetTimeBySleeping()) sleeping++; // isSleeping() && sleepTimer >= 100
            }
            if (hasPlayers) participating.add(world);
        }

        if (total == 0 || sleeping == 0) return;
        int required = Math.max(1, (int) Math.ceil((double) total * pct / 100.0));
        if (sleeping < required) return;

        // Threshold met — advance the real overworld to the next morning (vanilla formula).
        long l = overworld.getTimeOfDay() + 24000L;
        overworld.setTimeOfDay(l - l % 24000L);

        // Reset weather like vanilla does on a sleep night-skip.
        if (rules.getBoolean(net.minecraft.world.GameRules.DO_WEATHER_CYCLE) && overworld.isRaining()) {
            overworld.resetWeather();
        }

        // Wake the sleepers and push the new time to every client in the mirror dims —
        // those worlds never auto-broadcast time (shouldTickTime=false), so without this
        // the server advances time but players keep seeing night.
        long newTime = overworld.getTime();
        long newTimeOfDay = overworld.getTimeOfDay();
        for (ServerWorld world : participating) {
            for (ServerPlayerEntity p : world.getPlayers()) {
                if (p.isSleeping()) p.wakeUp();
                p.networkHandler.sendPacket(
                    new net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket(
                        newTime, newTimeOfDay, true
                    )
                );
            }
        }

        CheeseGuardMod.LOGGER.info("[CG] Sleep skipped the night across overworld-mirror dims ({}/{} sleeping)", sleeping, total);
    }

    private void enterClearInventory(ServerPlayerEntity player) {
        if (clearInvSnapshots.containsKey(player.getUuid())) return; // already saved

        // 1. Full snapshot: captures Trinkets, backpack, all modded slots
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        player.writeNbt(nbt);
        clearInvSnapshots.put(player.getUuid(), nbt);

        // 2. Persist to disk immediately (crash safety)
        saveClearInvSnapshot(player.getUuid(), player.getName().getString(), nbt);

        // 2b. Capture the items held at entry (live copies, BEFORE wipe) so exit can
        //     re-grant ONLY genuinely-gained items (current − original), never duplicating
        //     items that survived into the dim (crash, keepInventory death, missed wipe slot).
        java.util.List<net.minecraft.item.ItemStack> originalItems = new java.util.ArrayList<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            net.minecraft.item.ItemStack s = player.getInventory().getStack(i);
            if (!s.isEmpty()) originalItems.add(s.copy());
        }
        clearInvOriginalItems.put(player.getUuid(), originalItems);

        // 3. Wipe EVERYTHING: vanilla + Trinkets
        com.cheeseguard.data.EventParticipant.clearFullInventoryForConstruction(player);

        player.sendMessage(net.minecraft.text.Text.translatable(
            "cheeseguard.dimension.clearinv_enter")
            .formatted(net.minecraft.util.Formatting.YELLOW), false);
        CheeseGuardMod.LOGGER.info("[CG] Clear inventory mode entered for {}", player.getName().getString());
    }

    private void enterConstructionMode(ServerPlayerEntity player, DimensionConfig cfg) {
        // Save gamemode so we can restore it on exit
        constructionSavedGameMode.put(player.getUuid(),
            player.interactionManager.getGameMode().getName());

        // Set gamemode
        if (cfg.isConstructionBuilder(player.getUuid().toString())) {
            player.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
            player.sendMessage(net.minecraft.text.Text.translatable(
                "cheeseguard.dimension.construction_enter_builder")
                .formatted(net.minecraft.util.Formatting.GOLD), false);
        } else {
            player.sendMessage(net.minecraft.text.Text.translatable(
                "cheeseguard.dimension.construction_enter")
                .formatted(net.minecraft.util.Formatting.YELLOW), false);
        }
        CheeseGuardMod.LOGGER.info("[CG] Construction mode entered for {}", player.getName().getString());
    }

    /**
     * Execute leave commands configured on a dimension when a player leaves it.
     * Commands support {player} placeholder.
     */
    private void executeLeaveCommands(ServerPlayerEntity player, DimensionConfig fromCfg) {
        java.util.List<String> cmds = fromCfg.getCommandsOnLeave();
        if (cmds.isEmpty()) return;
        String playerName = player.getName().getString();
        var dispatcher = server.getCommandManager().getDispatcher();
        var source = server.getCommandSource();
        for (String raw : cmds) {
            if (raw == null || raw.isBlank()) continue;
            String cmd = raw.replace("{player}", playerName).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            try {
                dispatcher.execute(cmd, source);
            } catch (Exception e) {
                CheeseGuardMod.LOGGER.warn("[CG] Leave dim cmd failed: '{}' — {}", cmd, e.getMessage());
            }
        }
    }

    private void exitClearInventory(ServerPlayerEntity player) {
        if (!clearInvSnapshots.containsKey(player.getUuid())) return;

        // Anti-duplication: remove from memory AND disk FIRST, before any restore
        net.minecraft.nbt.NbtCompound nbt = clearInvSnapshots.remove(player.getUuid());
        deleteClearInvSnapshot(player.getUuid());

        // Items held at ENTRY. null ⇒ this snapshot was crash-recovered from disk (the entry
        // record didn't survive the crash) ⇒ the current inventory is UNTRUSTED (it may be the
        // un-wiped original) ⇒ we do a restore-only exit (re-grant nothing).
        java.util.List<net.minecraft.item.ItemStack> originalItems = clearInvOriginalItems.remove(player.getUuid());
        boolean trusted = originalItems != null;

        // Snapshot the current inventory BEFORE wiping (used to compute genuinely-gained items)
        java.util.List<net.minecraft.item.ItemStack> dimItems = new java.util.ArrayList<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            net.minecraft.item.ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                dimItems.add(stack.copy());
            }
        }

        // Wipe current items before restore
        com.cheeseguard.data.EventParticipant.clearFullInventoryForConstruction(player);

        // Nuclear restore from full snapshot
        if (nbt != null) {
            double cx = player.getX(), cy = player.getY(), cz = player.getZ();
            float cyaw = player.getYaw(), cpitch = player.getPitch();
            float ch = player.getHealth();
            int cf = player.getHungerManager().getFoodLevel();
            float csat = player.getHungerManager().getSaturationLevel();
            int cxl = player.experienceLevel;
            float cxp = player.experienceProgress;
            int ctx = player.totalExperience;
            var currentGm = player.interactionManager.getGameMode();

            player.readNbt(nbt);

            player.refreshPositionAndAngles(cx, cy, cz, cyaw, cpitch);
            player.setHealth(Math.max(1f, ch));
            player.getHungerManager().setFoodLevel(cf);
            player.getHungerManager().setSaturationLevel(csat);
            player.experienceLevel = cxl;
            player.experienceProgress = cxp;
            player.totalExperience = ctx;
            player.interactionManager.changeGameMode(currentGm);

            // Compute genuinely-gained items = current (dimItems) MINUS original (held at entry).
            // Items present in both sets are already restored by readNbt(snapshot) above, so they
            // must NOT be re-added (that was the duplication bug). On an untrusted exit, gained
            // stays empty ⇒ restore-only.
            java.util.List<net.minecraft.item.ItemStack> gained = new java.util.ArrayList<>();
            if (trusted) {
                java.util.List<net.minecraft.item.ItemStack> remainingOriginal = new java.util.ArrayList<>();
                for (net.minecraft.item.ItemStack o : originalItems) remainingOriginal.add(o.copy());
                for (net.minecraft.item.ItemStack cur : dimItems) {
                    net.minecraft.item.ItemStack rem = cur.copy();
                    for (net.minecraft.item.ItemStack o : remainingOriginal) {
                        if (rem.isEmpty()) break;
                        if (o.getCount() > 0 && net.minecraft.item.ItemStack.areItemsAndComponentsEqual(rem, o)) {
                            int c = Math.min(rem.getCount(), o.getCount());
                            o.decrement(c);
                            rem.decrement(c);
                        }
                    }
                    if (!rem.isEmpty()) gained.add(rem);
                }
            }

            // Give back ONLY the genuinely-gained items, on top of the restored inventory.
            int givenBack = 0;
            for (net.minecraft.item.ItemStack g : gained) {
                if (!player.getInventory().insertStack(g)) {
                    // Inventory full — drop remaining items at player's feet
                    player.dropItem(g, false);
                }
                givenBack++;
            }

            player.getInventory().markDirty();
            player.currentScreenHandler.sendContentUpdates();
            player.playerScreenHandler.sendContentUpdates();
            player.playerScreenHandler.onContentChanged(player.getInventory());

            player.sendMessage(net.minecraft.text.Text.translatable(
                "cheeseguard.dimension.clearinv_exit")
                .formatted(net.minecraft.util.Formatting.GREEN), false);
            if (!trusted) {
                CheeseGuardMod.LOGGER.warn("[CG] Clear inventory mode exited for {} (crash-recovered snapshot) — restore-only, current inventory discarded to prevent duplication",
                    player.getName().getString());
            } else if (givenBack > 0) {
                CheeseGuardMod.LOGGER.info("[CG] Clear inventory mode exited for {}, inventory restored + {} gained item stack(s) kept",
                    player.getName().getString(), givenBack);
            } else {
                CheeseGuardMod.LOGGER.info("[CG] Clear inventory mode exited for {}, inventory restored", player.getName().getString());
            }
        } else {
            CheeseGuardMod.LOGGER.warn("[CG] exitClearInventory: no snapshot for {}", player.getName().getString());
            player.sendMessage(net.minecraft.text.Text.translatable(
                "cheeseguard.dimension.construction_no_snapshot")
                .formatted(net.minecraft.util.Formatting.RED), false);
        }
    }

    private void exitConstructionMode(ServerPlayerEntity player) {
        String savedGm = constructionSavedGameMode.remove(player.getUuid());

        // Restore gamemode
        net.minecraft.world.GameMode gmToRestore = net.minecraft.world.GameMode.SURVIVAL;
        if (savedGm != null) {
            try { gmToRestore = net.minecraft.world.GameMode.byName(savedGm, net.minecraft.world.GameMode.SURVIVAL); }
            catch (Exception ignored) {}
        }
        if (player.interactionManager.getGameMode() != gmToRestore) {
            player.changeGameMode(gmToRestore);
        }

        player.sendMessage(net.minecraft.text.Text.translatable(
            "cheeseguard.dimension.construction_exit")
            .formatted(net.minecraft.util.Formatting.GREEN), false);
        CheeseGuardMod.LOGGER.info("[CG] Construction mode exited for {}", player.getName().getString());
    }

    // ── Clear inventory snapshot persistence (crash safety) ────────────────

    private Path getClearInvSnapshotDir() {
        return server.getRunDirectory().resolve("config").resolve("cheeseguard_clearinv_snapshots");
    }

    private void saveClearInvSnapshot(UUID uuid, String playerName, net.minecraft.nbt.NbtCompound nbt) {
        try {
            Path dir = getClearInvSnapshotDir();
            java.nio.file.Files.createDirectories(dir);
            net.minecraft.nbt.NbtCompound root = new net.minecraft.nbt.NbtCompound();
            root.putString("playerName", playerName);
            root.put("playerData", nbt);
            net.minecraft.nbt.NbtIo.writeCompressed(root, dir.resolve(uuid + ".nbt"));
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to persist clearInv snapshot for {}: {}", playerName, e.getMessage());
        }
    }

    private void deleteClearInvSnapshot(UUID uuid) {
        try {
            java.nio.file.Files.deleteIfExists(getClearInvSnapshotDir().resolve(uuid + ".nbt"));
        } catch (Exception ignored) {}
    }

    /** On server start: load any clearInv snapshots that survived a crash. Migrates from old dir if needed. */
    private void loadClearInvSnapshots() {
        Path dir = getClearInvSnapshotDir();

        // ── Migration: move files from old construction_snapshots dir to new clearinv_snapshots dir ──
        Path oldDir = server.getRunDirectory().resolve("config").resolve("cheeseguard_construction_snapshots");
        if (java.nio.file.Files.exists(oldDir)) {
            try {
                java.nio.file.Files.createDirectories(dir);
                try (java.nio.file.DirectoryStream<Path> oldStream = java.nio.file.Files.newDirectoryStream(oldDir, "*.nbt")) {
                    for (Path oldFile : oldStream) {
                        Path target = dir.resolve(oldFile.getFileName());
                        java.nio.file.Files.copy(oldFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        java.nio.file.Files.deleteIfExists(oldFile);
                        CheeseGuardMod.LOGGER.info("[CG] Migrated clearInv snapshot {} from old dir", oldFile.getFileName());
                    }
                }
                // Remove old directory if empty
                try (java.nio.file.DirectoryStream<Path> remaining = java.nio.file.Files.newDirectoryStream(oldDir)) {
                    if (!remaining.iterator().hasNext()) {
                        java.nio.file.Files.deleteIfExists(oldDir);
                        CheeseGuardMod.LOGGER.info("[CG] Removed old cheeseguard_construction_snapshots directory");
                    }
                }
            } catch (Exception e) {
                CheeseGuardMod.LOGGER.warn("[CG] Failed to migrate old construction snapshots: {}", e.getMessage());
            }
        }

        if (!java.nio.file.Files.exists(dir)) return;
        try (java.nio.file.DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path file : stream) {
                try {
                    net.minecraft.nbt.NbtCompound root = net.minecraft.nbt.NbtIo.readCompressed(
                        file, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
                    String filename = file.getFileName().toString().replace(".nbt", "");
                    UUID uuid = UUID.fromString(filename);
                    if (root.contains("playerData")) {
                        clearInvSnapshots.put(uuid, root.getCompound("playerData"));
                        CheeseGuardMod.LOGGER.info("[CG] Loaded crash clearInv snapshot for {}",
                            root.contains("playerName") ? root.getString("playerName") : uuid);
                    }
                } catch (Exception e) {
                    CheeseGuardMod.LOGGER.warn("[CG] Failed to load clearInv snapshot {}: {}", file, e.getMessage());
                }
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to scan clearInv snapshots: {}", e.getMessage());
        }
    }

    /** Called when player disconnects to clean up tracking. */
    public void onPlayerDisconnect(UUID uuid) {
        lastKnownWorld.remove(uuid);
        // Keep clearInvSnapshots — already persisted to disk, restored on reconnect
        // Do NOT remove constructionSavedGameMode either
    }

    /**
     * If spawnRedirect is set and the player is in minecraft:overworld,
     * teleport them to the redirect dimension's spawn point (deferred by 1 tick).
     * Returns true if a redirect was scheduled.
     */
    private boolean redirectToSpawnDimension(ServerPlayerEntity player) {
        if (spawnRedirect == null || server == null) return false;
        String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
        if (!"minecraft:overworld".equals(worldId)) return false;

        DimensionConfig targetCfg = getConfigByWorldId(spawnRedirect);
        if (targetCfg == null) return false;

        ServerWorld targetWorld = null;
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(spawnRedirect)) {
                targetWorld = w;
                break;
            }
        }
        if (targetWorld == null) return false;

        final ServerWorld tw = targetWorld;
        final double sx = targetCfg.getSpawnX(), sy = targetCfg.getSpawnY(), sz = targetCfg.getSpawnZ();
        final float sYaw = targetCfg.getSpawnYaw(), sPitch = targetCfg.getSpawnPitch();
        // Defer by 1 tick — player entity may not be fully ready yet
        server.execute(() -> {
            player.teleport(tw, sx, sy, sz, java.util.Set.of(), sYaw, sPitch);
        });
        return true;
    }

    /** Called on player join: handle crash recovery and state consistency. */
    public void onPlayerJoin(ServerPlayerEntity player) {
        redirectToSpawnDimension(player);
        // Send the border at multiple intervals to outlast vanilla's overworld-border init packet
        // (vanilla always sends overworld border on join — we must override it after).
        // Also re-apply the border server-side in case state drifted since SERVER_STARTED.
        // Note: we now call applyWorldBorder UNCONDITIONALLY when a config exists —
        // previously the disabled branch only sent the runtime border to the player,
        // which leaked vanilla worldborder.dat state when the runtime hadn't yet been
        // reset to no-border (rare boot races + late dim loads).
        UUID joinUuid = player.getUuid();
        Runnable resync = () -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(joinUuid);
            if (p == null) return;
            String wid = p.getServerWorld().getRegistryKey().getValue().toString();
            DimensionConfig dc = getConfigByWorldId(wid);
            if (dc != null) applyWorldBorder(dc);
            else sendBorderToPlayer(p);
        };
        server.execute(resync);
        scheduleAfterTicks(20, resync);   // +1s
        scheduleAfterTicks(60, resync);   // +3s
        String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
        DimensionConfig cfg = getConfigByWorldId(worldId);

        boolean hasSnapshot      = clearInvSnapshots.containsKey(player.getUuid());
        boolean inClearInvDim    = cfg != null && cfg.isClearInventory();
        boolean inConstructionDim = cfg != null && cfg.isConstructionMode();
        boolean isOp             = player.hasPermissionLevel(2);

        if (hasSnapshot && inClearInvDim) {
            // Snapshot present + in clearInventory dim → OK, also check construction mode for gamemode
            if (inConstructionDim && cfg.isConstructionBuilder(player.getUuid().toString())
                    && player.interactionManager.getGameMode() != net.minecraft.world.GameMode.CREATIVE) {
                player.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
            }
            CheeseGuardMod.LOGGER.info("[CG] {} reconnected inside clearInventory dim, snapshot active", player.getName().getString());

        } else if (hasSnapshot && !inClearInvDim) {
            // Snapshot present but outside clearInventory dim (crash/external TP) → restore
            CheeseGuardMod.LOGGER.warn("[CG] {} has clearInv snapshot but is outside clearInventory dim — restoring now",
                player.getName().getString());
            exitClearInventory(player);

        } else if (!hasSnapshot && inClearInvDim) {
            // No snapshot + in clearInventory dim → fresh entry
            enterClearInventory(player);
            if (inConstructionDim) {
                enterConstructionMode(player, cfg);
            }

        } else if (!isOp
                && !hasSnapshot
                && player.interactionManager.getGameMode() == net.minecraft.world.GameMode.CREATIVE) {
            // ── Rogue creative: non-op in creative with no legitimate construction snapshot
            // Could happen after a crash, a bug, or manual /gamemode command ──
            CheeseGuardMod.LOGGER.warn(
                "[CG] ROGUE CREATIVE on join: {} is creative without snapshot in '{}' — forcing survival",
                player.getName().getString(), worldId);
            player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
            player.sendMessage(
                net.minecraft.text.Text.translatable(
                    "cheeseguard.dimension.creative_removed_login")
                    .formatted(net.minecraft.util.Formatting.RED),
                false);
        }

        lastKnownWorld.put(player.getUuid(), worldId);
    }

    // ── Config access ────────────────────────────────────────────────────────

    public DimensionConfig getConfig(String configId) {
        for (DimensionConfig c : customDimensions) {
            if (c.getId().equals(configId)) return c;
        }
        return null;
    }

    public DimensionConfig getConfigByWorldId(String worldId) {
        // Two configs can map to the same worldId: a builtIn=true stub (auto-detected
        // from server worlds with no real settings) and a builtIn=false owner config
        // that holds the real flags (clearInventory, snapshots, ...). Prefer the owner
        // so per-player dims resolve to the config that knows about clearInventory.
        DimensionConfig builtInMatch = null;
        for (DimensionConfig c : customDimensions) {
            if (!c.getWorldId().equals(worldId)) continue;
            if (!c.isBuiltIn()) return c;
            if (builtInMatch == null) builtInMatch = c;
        }
        return builtInMatch;
    }

    /** Check if PvP is allowed in the given dimension. Returns true if no custom rule or PvP enabled. */
    public boolean isPvpAllowed(String worldId) {
        DimensionConfig c = getConfigByWorldId(worldId);
        return c == null || c.isPvp();
    }

    /** Check if mob spawning is allowed in the given dimension. */
    public boolean isMobSpawningAllowed(String worldId) {
        DimensionConfig c = getConfigByWorldId(worldId);
        return c == null || c.isMobSpawning();
    }

    /** Check if ALL cobblemon are blocked in this dimension. */
    public boolean isCobblemonBlocked(String worldId) {
        DimensionConfig c = getConfigByWorldId(worldId);
        return c != null && c.isCobblemonBlocked();
    }

    /**
     * Remove all cobblemon (and their passengers) already present in the given world.
     * Called when cobblemonBlocked is toggled ON for a runtime dimension.
     */
    public void purgeCobblemonFromWorld(String worldId) {
        if (server == null) return;
        for (net.minecraft.server.world.ServerWorld w : server.getWorlds()) {
            if (!w.getRegistryKey().getValue().toString().equals(worldId)) continue;
            java.util.List<net.minecraft.entity.Entity> toRemove = new java.util.ArrayList<>();
            for (net.minecraft.entity.Entity e : w.iterateEntities()) {
                String ns = net.minecraft.entity.EntityType.getId(e.getType()).getNamespace();
                if ("cobblemon".equals(ns)) toRemove.add(e);
            }
            for (net.minecraft.entity.Entity e : toRemove) e.discard();
            CheeseGuardMod.LOGGER.info("[CG] Purged {} cobblemon from '{}'", toRemove.size(), worldId);
            break;
        }
    }

    /**
     * RCT trainer spawning follows the mobSpawning flag.
     * Used by EntitySpawnMixin to block trainer NPCs in dimensions with spawning disabled.
     */
    public boolean isTrainerSpawningAllowed(String worldId) {
        return isMobSpawningAllowed(worldId);
    }

    /**
     * Returns true if the given item ID is forbidden for builders in construction mode.
     * Forbidden items: coins (rctmod), ender chests, command blocks, and any item
     * whose ID suggests it is a CheeseGuard or economy token.
     */
    public boolean isConstructionForbiddenItem(String itemId) {
        if (itemId == null) return false;
        // Block ender chests and command blocks unconditionally in construction mode
        if (itemId.equals("minecraft:ender_chest")) return true;
        if (itemId.equals("minecraft:command_block")) return true;
        if (itemId.equals("minecraft:chain_command_block")) return true;
        if (itemId.equals("minecraft:repeating_command_block")) return true;
        // Block RCT economy coins/tokens
        if (itemId.startsWith("rctmod:coin") || itemId.startsWith("rctmod:token")) return true;
        // Block Numismatic Overhaul coins
        if (itemId.startsWith("numismatic-overhaul:") && (
                itemId.contains("coin") || itemId.contains("gold") || itemId.contains("silver") || itemId.contains("bronze")))
            return true;
        if (itemId.equals("numismatic-overhaul:gold_coin") ||
            itemId.equals("numismatic-overhaul:silver_coin") ||
            itemId.equals("numismatic-overhaul:bronze_coin")) return true;
        return false;
    }

    /** Check if command blocks are allowed in the given dimension. */
    public boolean isCommandBlocksAllowed(String worldId) {
        DimensionConfig c = getConfigByWorldId(worldId);
        return c == null || c.isCommandBlocks();
    }

    /** Check if ender chests are allowed in the given dimension. */
    public boolean isEnderChestAllowed(String worldId) {
        DimensionConfig c = getConfigByWorldId(worldId);
        return c == null || c.isEnderChest();
    }

    /** Check if keep inventory is enabled in the given dimension. */
    public boolean isKeepInventory(String worldId) {
        DimensionConfig c = getConfigByWorldId(worldId);
        return c != null && c.isKeepInventory();
    }

    public List<DimensionConfig> getCustomDimensions() { return customDimensions; }

    // ── Persistence ──────────────────────────────────────────────────────────

    // ── Snapshot / Auto-Reset system ────────────────────────────────────────────

    /** Set of configIds that have a reset pending (waiting for 0 players). */
    private final java.util.Set<String> pendingResets = new java.util.HashSet<>();
    private final java.util.Set<String> onEmptyOccupied = new java.util.HashSet<>(); // tracks dims that had players (for ON_EMPTY transition detection)
    /** Track last scheduled-reset day per configId to avoid firing twice the same day. */
    private final java.util.Map<String, Integer> scheduledResetLastDay = new java.util.HashMap<>();

    /** Returns path to live dimension folder. */
    private Path getDimWorldFolder(DimensionConfig config) {
        String levelName = server.getSaveProperties().getLevelName();
        return server.getRunDirectory().resolve(levelName)
            .resolve("dimensions").resolve("cheeseguard").resolve(config.getId());
    }

    /** Returns path to snapshot folder for this dimension. */
    private Path getSnapshotFolder(DimensionConfig config) {
        return configPath.getParent().resolve("snapshots").resolve(config.getId());
    }

    /**
     * Extract master dim ID from a personal dim ID.
     * Personal dims follow pattern: {@code <masterId>_<8 hex chars>}
     * e.g., "conseil4_abc12345" → "conseil4"
     * @return master ID or null if not a personal dim
     */
    private String getMasterDimId(String dimId) {
        int lastUnderscore = dimId.lastIndexOf('_');
        if (lastUnderscore > 0 && dimId.length() - lastUnderscore - 1 == 8) {
            String suffix = dimId.substring(lastUnderscore + 1);
            if (suffix.matches("[0-9a-f]{8}")) {
                return dimId.substring(0, lastUnderscore);
            }
        }
        return null;
    }

    /**
     * Returns the effective snapshot folder, falling back to master's snapshot for personal dims.
     */
    private Path getEffectiveSnapshotFolder(DimensionConfig config) {
        Path own = getSnapshotFolder(config);
        if (Files.exists(own)) return own;
        String masterId = getMasterDimId(config.getId());
        if (masterId != null) {
            DimensionConfig master = getConfig(masterId);
            if (master != null) {
                Path masterSnap = getSnapshotFolder(master);
                if (Files.exists(masterSnap)) return masterSnap;
            }
        }
        return own; // return own path even if missing (caller checks existence)
    }

    /**
     * Create (or overwrite) a snapshot of the given dimension's region files.
     * @return true on success
     */
    public boolean createSnapshot(String configId) {
        DimensionConfig config = getConfig(configId);
        if (config == null || config.isBuiltIn()) return false;

        // First save the world so files are up-to-date
        ServerWorld world = findWorld(config.getWorldId());
        if (world != null) {
            try { world.save(null, false, false); } catch (Exception ignored) {}
        }

        Path srcDir  = getDimWorldFolder(config);
        Path destDir = getSnapshotFolder(config);

        // If dimension folder doesn't exist yet (never generated chunks), create empty snapshot
        if (!Files.exists(srcDir)) {
            try { Files.createDirectories(destDir); } catch (IOException ignored) {}
            config.setSnapshotEnabled(true);
            save();
            CheeseGuardMod.LOGGER.info("[CG] Snapshot created (empty) for dim '{}'", configId);
            return true;
        }

        try {
            // Clear old snapshot
            if (Files.exists(destDir)) deleteRecursively(destDir);
            Files.createDirectories(destDir);

            // Copy all files recursively
            Files.walkFileTree(srcDir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Path rel  = srcDir.relativize(dir);
                    Path dest = destDir.resolve(rel);
                    Files.createDirectories(dest);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Path rel  = srcDir.relativize(file);
                    Path dest = destDir.resolve(rel);
                    Files.copy(file, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });

            config.setSnapshotEnabled(true);
            save();
            long sizeMb = getSnapshotSizeKb(configId) / 1024;
            CheeseGuardMod.LOGGER.info("[CG] Snapshot created for dim '{}' (~{}MB)", configId, sizeMb);
            return true;
        } catch (IOException e) {
            CheeseGuardMod.LOGGER.error("[CG] Failed to create snapshot for '{}': {}", configId, e.getMessage());
            return false;
        }
    }

    /**
     * Schedule or immediately execute a dimension reset from snapshot.
     * If players are present → marks as pending (resets when they leave).
     * If empty → resets immediately.
     * @return "ok" | "pending" | "no_snapshot" | "error"
     */
    public String restoreSnapshot(String configId) {
        DimensionConfig config = getConfig(configId);
        if (config == null || config.isBuiltIn()) return "error";
        // Check snapshot availability (personal dims fall back to master's snapshot)
        boolean hasSnapshot = config.isSnapshotEnabled() && Files.exists(getSnapshotFolder(config));
        if (!hasSnapshot) {
            // For personal dims, check if master has a snapshot
            String masterId = getMasterDimId(config.getId());
            if (masterId != null) {
                DimensionConfig master = getConfig(masterId);
                hasSnapshot = master != null && master.isSnapshotEnabled()
                    && Files.exists(getSnapshotFolder(master));
            }
        }
        if (!hasSnapshot) return "no_snapshot";

        ServerWorld world = findWorld(config.getWorldId());
        if (world != null && !world.getPlayers().isEmpty()) {
            // Kick all players first, then mark pending
            for (ServerPlayerEntity pl : new java.util.ArrayList<>(world.getPlayers())) {
                var ow = server.getOverworld();
                var sp = ow.getSpawnPos();
                pl.teleport(ow, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5,
                    Set.of(), pl.getYaw(), pl.getPitch());
                pl.sendMessage(net.minecraft.text.Text.translatable(
                    "cheeseguard.dimension.resetting")
                    .formatted(net.minecraft.util.Formatting.YELLOW), false);
            }
            pendingResets.add(configId);
            return "pending";
        } else {
            return executeReset(configId) ? "ok" : "error";
        }
    }

    /** Actually perform the file copy. Call only when dimension is empty / safe.
     *  Unloads the live ServerWorld, then asynchronously replaces files on disk,
     *  then reloads so the reset is visible without a server restart. */
    private boolean executeReset(String configId) {
        DimensionConfig config = getConfig(configId);
        if (config == null) return false;
        Path snapshotDir = getEffectiveSnapshotFolder(config);
        if (!Files.exists(snapshotDir)) {
            CheeseGuardMod.LOGGER.warn("[CG] Snapshot dir not found for \'{}\', skipping reset", configId);
            return false;
        }

        Identifier dimId = Identifier.of("cheeseguard", config.getId());
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);

        // 1. Save and unload the live world
        ServerWorld liveWorld = findWorld(config.getWorldId());
        if (liveWorld != null) {
            try { liveWorld.save(null, true, false); } catch (Exception e) {
                CheeseGuardMod.LOGGER.warn("[CG] World save before reset failed for \'{}\': {}", configId, e.getMessage());
            }
            ((com.cheeseguard.mixin.MinecraftServerAccessor) server).cg_getWorlds().remove(worldKey);
            CheeseGuardMod.LOGGER.info("[CG] Unloaded world \'{}\' for reset", config.getWorldId());
        }

        Path destDir = getDimWorldFolder(config);

        // 2. Async file copy — avoids Windows file-handle locks held after world unload
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            try {
                if (Files.exists(destDir)) deleteRecursively(destDir);
                Files.createDirectories(destDir);
                Files.walkFileTree(snapshotDir, new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                            java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        Files.createDirectories(destDir.resolve(snapshotDir.relativize(dir)));
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFile(Path file,
                            java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        Files.copy(file, destDir.resolve(snapshotDir.relativize(file)),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
                CheeseGuardMod.LOGGER.info("[CG] File copy complete for dim \'{}\' reset", configId);
            } catch (IOException e) {
                CheeseGuardMod.LOGGER.error("[CG] Failed to copy snapshot files for \'{}\': {}", configId, e.getMessage());
                server.execute(() -> { try { createWorldRuntime(config); } catch (Exception ex) {} });
                return;
            }

            // 3. Reload on server tick thread
            server.execute(() -> {
                try {
                    createWorldRuntime(config);
                    config.setLastResetAt(System.currentTimeMillis());
                    save();
                    CheeseGuardMod.LOGGER.info("[CG] Dimension \'{}\' reloaded after reset", config.getWorldId());
                    for (var p : server.getPlayerManager().getPlayerList()) {
                        if (p.hasPermissionLevel(2))
                            p.sendMessage(net.minecraft.text.Text.translatable(
                                "cheeseguard.dimension.reset_done", config.getDisplayName()), false);
                    }
                } catch (Exception e) {
                    CheeseGuardMod.LOGGER.error("[CG] Failed to reload world after reset for \'{}\': {}", configId, e.getMessage());
                }
            });
        });

        CheeseGuardMod.LOGGER.info("[CG] Reset started (async) for dim \'{\'}", configId);
        return true;
    }
    /** Returns snapshot size in KB, or 0 if no snapshot. */
    public long getSnapshotSizeKb(String configId) {
        DimensionConfig config = getConfig(configId);
        if (config == null) return 0;
        Path dir = getSnapshotFolder(config);
        if (!Files.exists(dir)) return 0;
        try {
            long[] total = {0};
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            return total[0] / 1024;
        } catch (IOException e) { return 0; }
    }

    /** Check auto-reset triggers (ON_EMPTY + SCHEDULED). Call from tick every 20t. */
    public void checkAutoResets() {
        if (server == null) return;

        for (DimensionConfig config : customDimensions) {
            if (!config.isSnapshotEnabled()) continue;
            String configId = config.getId();
            ServerWorld world = findWorld(config.getWorldId());

            // ── Pending reset: execute when dimension is now empty ──
            if (pendingResets.contains(configId)) {
                boolean empty = world == null || world.getPlayers().isEmpty();
                if (empty) {
                    pendingResets.remove(configId);
                    executeReset(configId);
                }
                continue; // don't layer another trigger on top
            }

            // ── ON_EMPTY: reset when last player leaves (transition occupied → empty) ──
            if (config.getSnapshotResetTrigger() == DimensionConfig.ResetTrigger.ON_EMPTY) {
                boolean empty = world == null || world.getPlayers().isEmpty();
                if (!empty) {
                    // Dimension has player(s) → mark as occupied
                    onEmptyOccupied.add(configId);
                } else if (onEmptyOccupied.remove(configId)) {
                    // Transition from occupied → empty: fire reset ONCE
                    pendingResets.add(configId);
                }
                // If empty and was never occupied (or already reset) → do nothing
            }

            // ── SCHEDULED: reset at configured hour (real time, once per day) ──
            if (config.getSnapshotResetTrigger() == DimensionConfig.ResetTrigger.SCHEDULED) {
                java.time.LocalTime now = java.time.LocalTime.now();
                java.time.LocalDate today = java.time.LocalDate.now();
                int todayNum = today.getDayOfYear() * 10000 + today.getYear();
                int lastDay  = scheduledResetLastDay.getOrDefault(configId, -1);

                if (now.getHour() == config.getSnapshotResetHour() && lastDay != todayNum) {
                    scheduledResetLastDay.put(configId, todayNum);
                    String result = restoreSnapshot(configId);
                    CheeseGuardMod.LOGGER.info("[CG] Scheduled auto-reset for '{}': {}", configId, result);
                }
            }
        }
    }

    public boolean hasPendingReset(String configId) { return pendingResets.contains(configId); }

    // ── Per-Player Dimension System ───────────────────────────────────────────
    // masterDimId → set of playerUUIDs who have a personal copy already created
    private final java.util.Map<String, java.util.Set<java.util.UUID>> playerDimOwners = new java.util.HashMap<>();

    /** Returns the config-id of a player's personal copy for the given master, or null if none. */
    private String getPlayerDimId(String masterConfigId, java.util.UUID uuid) {
        return masterConfigId + "_" + uuid.toString().replace("-","").substring(0,8);
    }

    /** Returns the display name for a player's personal dim. */
    private String getPlayerDimDisplayName(String masterDisplayName, String playerName) {
        return masterDisplayName + " — " + playerName;
    }

    /**
     * Called every tick (via onTick). Detects when a non-op player has entered a
     * per-player master dimension and silently redirects them to their personal copy,
     * creating it on the fly if needed.
     */
    private void checkPerPlayerRedirects() {
        for (net.minecraft.server.network.ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.hasPermissionLevel(2)) continue; // ops enter master freely
            String currentWorld = player.getServerWorld().getRegistryKey().getValue().toString();
            DimensionConfig cfg = getConfigByWorldId(currentWorld);
            if (cfg == null || !cfg.isPerPlayerMode()) continue;

            // Player is in the master dim — redirect to personal copy
            java.util.UUID uuid = player.getUuid();
            String personalId = getPlayerDimId(cfg.getId(), uuid);

            // Ensure personal dim exists (create if needed, or recreate if world folder is missing)
            DimensionConfig personalCfg = getConfig(personalId);
            boolean needsCreate = personalCfg == null;
            if (!needsCreate) {
                // Config exists but world folder may have been deleted — check disk
                Path worldFolder = getDimWorldFolder(personalCfg);
                if (!Files.exists(worldFolder) || !Files.exists(worldFolder.resolve("region"))) {
                    CheeseGuardMod.LOGGER.info("[CG] Personal dim '{}' config exists but world folder missing — recreating from snapshot", personalId);
                    customDimensions.remove(personalCfg);
                    needsCreate = true;
                }
            }
            if (needsCreate) {
                boolean created = createPlayerDimension(cfg, player);
                if (!created) {
                    player.sendMessage(net.minecraft.text.Text.translatable(
                        "cheeseguard.dimension.personal_create_error").formatted(net.minecraft.util.Formatting.RED), false);
                    var ow = server.getOverworld();
                    var sp = ow.getSpawnPos();
                    player.teleport(ow, sp.getX()+0.5, sp.getY(), sp.getZ()+0.5, java.util.Set.of(), player.getYaw(), player.getPitch());
                    continue;
                }
                personalCfg = getConfig(personalId);
            }

            // TP player into their personal copy
            if (personalCfg == null) continue;
            ServerWorld personalWorld = findWorld(personalCfg.getWorldId());
            if (personalWorld == null) {
                // World exists in config but not loaded yet — load it
                try { createWorldRuntime(personalCfg); personalWorld = findWorld(personalCfg.getWorldId()); } catch (Exception e) {
                    CheeseGuardMod.LOGGER.error("[CG] Failed to load personal world for {}: {}", player.getName().getString(), e.getMessage());
                    continue;
                }
            }
            // Preserve the player's current position (where they landed in the master)
            player.teleport(personalWorld, player.getX(), player.getY(), player.getZ(),
                java.util.Set.of(), player.getYaw(), player.getPitch());
            player.sendMessage(net.minecraft.text.Text.translatable(
                "cheeseguard.dimension.personal_welcome").formatted(net.minecraft.util.Formatting.GREEN), true);
        }
    }

    /**
     * Creates a personal dimension for a player based on the master.
     * Copies either the master snapshot (if it exists) or the live master world folder.
     */
    private boolean createPlayerDimension(DimensionConfig master, net.minecraft.server.network.ServerPlayerEntity player) {
        String uuid8 = player.getUuid().toString().replace("-","").substring(0,8);
        String personalId   = master.getId() + "_" + uuid8;
        String personalName = getPlayerDimDisplayName(master.getDisplayName(), player.getName().getString());

        CheeseGuardMod.LOGGER.info("[CG] Creating personal dim '{}' for player '{}'", personalId, player.getName().getString());

        // Build config (clone master settings)
        DimensionConfig cfg = new DimensionConfig(personalId, personalName);
        cfg.setGenType(master.getGenType());
        cfg.setSeed(master.getSeed());
        cfg.setFlatLayers(master.getFlatLayers());
        cfg.setSpawnX(master.getSpawnX()); cfg.setSpawnY(master.getSpawnY()); cfg.setSpawnZ(master.getSpawnZ());
        cfg.setTimeLocked(master.isTimeLocked()); cfg.setLockedTime(master.getLockedTime());
        cfg.setWeatherLocked(master.isWeatherLocked()); cfg.setLockedWeather(master.getLockedWeather());
        cfg.setPvp(master.isPvp());
        cfg.setMobSpawning(master.isMobSpawning());
        cfg.setKeepInventory(master.isKeepInventory());
        cfg.setDifficulty(master.getDifficulty());
        cfg.setCommandBlocks(master.isCommandBlocks());
        cfg.setEnderChest(master.isEnderChest());
        cfg.setGenerateStructures(master.isGenerateStructures());
        cfg.setCobblemonBlocked(master.isCobblemonBlocked());
        cfg.setClearInventory(master.isClearInventory());
        cfg.setAllowHome(master.isAllowHome());
        cfg.setSnapshotEnabled(master.isSnapshotEnabled());
        cfg.setSnapshotResetTrigger(master.getSnapshotResetTrigger());
        cfg.setCommandsOnBattleLoss(new java.util.ArrayList<>(master.getCommandsOnBattleLoss()));
        // Always OFF for child dims
        cfg.setConstructionMode(false);
        cfg.setPerPlayerMode(false);
        cfg.setEnabled(true);
        cfg.setBuiltIn(false);

        // Determine source folder: prefer master snapshot, fall back to live master world
        Path srcDir = getSnapshotFolder(master);
        if (!java.nio.file.Files.exists(srcDir)) {
            srcDir = getDimWorldFolder(master); // live master
        }

        // Destination: the new personal dim's world folder
        Path destDir = getDimWorldFolder(cfg);

        // Copy source → dest synchronously (player is being redirected, no rush on UX)
        try {
            if (java.nio.file.Files.exists(destDir)) deleteRecursively(destDir);
            java.nio.file.Files.createDirectories(destDir);
            if (java.nio.file.Files.exists(srcDir)) {
                final Path finalSrcDir = srcDir;
                java.nio.file.Files.walkFileTree(finalSrcDir, new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                            java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                        java.nio.file.Files.createDirectories(destDir.resolve(finalSrcDir.relativize(dir)));
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFile(Path file,
                            java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                        java.nio.file.Files.copy(file, destDir.resolve(finalSrcDir.relativize(file)),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            }
            // If source didn't exist, an empty VOID world will be generated automatically
        } catch (java.io.IOException e) {
            CheeseGuardMod.LOGGER.error("[CG] Failed to copy world for personal dim '{}': {}", personalId, e.getMessage());
            return false;
        }

        // Register and load
        try {
            createWorldRuntime(cfg);
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.error("[CG] Failed to load personal dim world '{}': {}", personalId, e.getMessage());
            return false;
        }

        // Store owner UUID in config whitelist so only they can enter
        cfg.addWhitelist(player.getUuid().toString());

        customDimensions.add(cfg);

        // Track owner
        playerDimOwners.computeIfAbsent(master.getId(), k -> new java.util.HashSet<>()).add(player.getUuid());

        save();
        CheeseGuardMod.LOGGER.info("[CG] Personal dim '{}' created for '{}'", personalId, player.getName().getString());
        return true;
    }

    /**
     * Delete a player's personal dimension for a given master.
     * TPs the player to overworld if they're in it. Removes world folder and config.
     * @param masterConfigId  ID of the master dimension
     * @param targetUuid      UUID of the player whose dim to delete
     * @return true on success, false if no personal dim exists
     */
    public boolean deletePlayerDimension(String masterConfigId, java.util.UUID targetUuid) {
        String personalId = getPlayerDimId(masterConfigId, targetUuid);
        DimensionConfig cfg = getConfig(personalId);
        if (cfg == null) return false;

        // Clean up waystones in this personal dim before unloading.
        Identifier personalDimId = Identifier.of("cheeseguard", personalId);
        WaystonesHook.removeWaystonesInDimension(server, personalDimId);

        // TP player out if in their personal dim
        String worldId = cfg.getWorldId();
        ServerWorld world = findWorld(worldId);
        if (world != null) {
            for (var pl : new java.util.ArrayList<>(world.getPlayers())) {
                var ow = server.getOverworld();
                var sp = ow.getSpawnPos();
                pl.teleport(ow, sp.getX()+0.5, sp.getY(), sp.getZ()+0.5, java.util.Set.of(), pl.getYaw(), pl.getPitch());
                pl.sendMessage(net.minecraft.text.Text.translatable(
                    "cheeseguard.dimension.personal_deleted_admin").formatted(net.minecraft.util.Formatting.YELLOW), false);
            }
            try { world.save(null, true, false); } catch (Exception ignored) {}
            net.minecraft.util.Identifier dimId = net.minecraft.util.Identifier.of("cheeseguard", personalId);
            net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey =
                net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, dimId);
            ((com.cheeseguard.mixin.MinecraftServerAccessor) server).cg_getWorlds().remove(worldKey);
        }

        // Delete world folder async (avoid file locks)
        Path dirToDelete = getDimWorldFolder(cfg);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            try {
                if (java.nio.file.Files.exists(dirToDelete)) deleteRecursively(dirToDelete);
                CheeseGuardMod.LOGGER.info("[CG] Deleted personal dim folder for '{}'", personalId);
            } catch (java.io.IOException e) {
                CheeseGuardMod.LOGGER.warn("[CG] Failed to delete personal dim folder '{}': {}", personalId, e.getMessage());
            }
        });

        // Also delete any snapshot of the personal dim
        Path snapDir = getSnapshotFolder(cfg);
        if (java.nio.file.Files.exists(snapDir)) {
            try { deleteRecursively(snapDir); } catch (java.io.IOException ignored) {}
        }

        customDimensions.remove(cfg);
        if (playerDimOwners.containsKey(masterConfigId))
            playerDimOwners.get(masterConfigId).remove(targetUuid);

        save();
        CheeseGuardMod.LOGGER.info("[CG] Deleted personal dim '{}' (owner: {})", personalId, targetUuid);
        return true;
    }

    /**
     * Reset a player's personal dimension to the current master snapshot.
     * Equivalent to deletePlayerDimension + re-creating it fresh.
     */
    public boolean resetPlayerDimension(String masterConfigId, java.util.UUID targetUuid) {
        DimensionConfig master = getConfig(masterConfigId);
        if (master == null) return false;

        String personalId = getPlayerDimId(masterConfigId, targetUuid);
        DimensionConfig cfg = getConfig(personalId);

        // TP player out if needed
        if (cfg != null) {
            ServerWorld world = findWorld(cfg.getWorldId());
            if (world != null) {
                for (var pl : new java.util.ArrayList<>(world.getPlayers())) {
                    var ow = server.getOverworld();
                    var sp = ow.getSpawnPos();
                    pl.teleport(ow, sp.getX()+0.5, sp.getY(), sp.getZ()+0.5, java.util.Set.of(), pl.getYaw(), pl.getPitch());
                    pl.sendMessage(net.minecraft.text.Text.translatable(
                        "cheeseguard.dimension.personal_reset_snapshot").formatted(net.minecraft.util.Formatting.YELLOW), false);
                }
                try { world.save(null, true, false); } catch (Exception ignored) {}
                net.minecraft.util.Identifier dimId = net.minecraft.util.Identifier.of("cheeseguard", personalId);
                net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey =
                    net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, dimId);
                ((com.cheeseguard.mixin.MinecraftServerAccessor) server).cg_getWorlds().remove(worldKey);
            }

            // Delete the old world folder
            Path oldDir = getDimWorldFolder(cfg);
            try { if (java.nio.file.Files.exists(oldDir)) deleteRecursively(oldDir); } catch (java.io.IOException ignored) {}
            customDimensions.remove(cfg);
        }

        // Source: master snapshot or live master
        Path srcDir = getSnapshotFolder(master);
        if (!java.nio.file.Files.exists(srcDir)) srcDir = getDimWorldFolder(master);

        // Rebuild config
        String uuid8 = targetUuid.toString().replace("-","").substring(0,8);
        String newPersonalName = cfg != null ? cfg.getDisplayName() : getPlayerDimDisplayName(master.getDisplayName(), targetUuid.toString().substring(0,8));
        DimensionConfig newCfg = new DimensionConfig(personalId, newPersonalName);
        newCfg.setGenType(master.getGenType()); newCfg.setSeed(master.getSeed());
        newCfg.setFlatLayers(master.getFlatLayers());
        newCfg.setSpawnX(master.getSpawnX()); newCfg.setSpawnY(master.getSpawnY()); newCfg.setSpawnZ(master.getSpawnZ());
        newCfg.setTimeLocked(master.isTimeLocked()); newCfg.setLockedTime(master.getLockedTime());
        newCfg.setWeatherLocked(master.isWeatherLocked()); newCfg.setLockedWeather(master.getLockedWeather());
        newCfg.setPvp(master.isPvp());
        newCfg.setMobSpawning(master.isMobSpawning());
        newCfg.setKeepInventory(master.isKeepInventory());
        newCfg.setDifficulty(master.getDifficulty());
        newCfg.setCommandBlocks(master.isCommandBlocks());
        newCfg.setEnderChest(master.isEnderChest());
        newCfg.setGenerateStructures(master.isGenerateStructures());
        newCfg.setCobblemonBlocked(master.isCobblemonBlocked());
        newCfg.setClearInventory(master.isClearInventory());
        newCfg.setAllowHome(master.isAllowHome());
        newCfg.setSnapshotEnabled(master.isSnapshotEnabled());
        newCfg.setSnapshotResetTrigger(master.getSnapshotResetTrigger());
        newCfg.setCommandsOnBattleLoss(new java.util.ArrayList<>(master.getCommandsOnBattleLoss()));
        // Always OFF for child dims
        newCfg.setConstructionMode(false);
        newCfg.setPerPlayerMode(false);
        newCfg.setEnabled(true);
        newCfg.setBuiltIn(false);

        Path newDestDir = getDimWorldFolder(newCfg);
        try {
            java.nio.file.Files.createDirectories(newDestDir);
            final Path finalSrc = srcDir;
            if (java.nio.file.Files.exists(finalSrc)) {
                java.nio.file.Files.walkFileTree(finalSrc, new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                            java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                        java.nio.file.Files.createDirectories(newDestDir.resolve(finalSrc.relativize(dir)));
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFile(Path file,
                            java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                        java.nio.file.Files.copy(file, newDestDir.resolve(finalSrc.relativize(file)),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (java.io.IOException e) {
            CheeseGuardMod.LOGGER.error("[CG] Reset copy failed for personal dim '{}': {}", personalId, e.getMessage());
            return false;
        }

        try { createWorldRuntime(newCfg); } catch (Exception e) {
            CheeseGuardMod.LOGGER.error("[CG] Failed to reload personal dim '{}' after reset: {}", personalId, e.getMessage());
            return false;
        }

        customDimensions.add(newCfg);
        playerDimOwners.computeIfAbsent(masterConfigId, k -> new java.util.HashSet<>()).add(targetUuid);
        save();
        CheeseGuardMod.LOGGER.info("[CG] Reset personal dim '{}' from master snapshot", personalId);
        return true;
    }

    /**
     * Returns all personal dimensions for a given master as a list of objects with
     * playerUuid, configId, displayName, worldId.
     */
    public java.util.List<java.util.Map<String,String>> getPlayerDimsForMaster(String masterConfigId) {
        java.util.List<java.util.Map<String,String>> result = new java.util.ArrayList<>();
        String prefix = masterConfigId + "_";
        for (DimensionConfig c : customDimensions) {
            if (c.getId().startsWith(prefix) && !c.isPerPlayerMode()) {
                // Determine uuid8 suffix
                String suffix = c.getId().substring(prefix.length());
                // Only 8-char hex suffixes are personal dims
                if (suffix.length() == 8 && suffix.matches("[0-9a-f]+")) {
                    java.util.Map<String,String> m = new java.util.LinkedHashMap<>();
                    m.put("configId", c.getId());
                    m.put("displayName", c.getDisplayName());
                    m.put("worldId", c.getWorldId());
                    m.put("uuid8", suffix);
                    result.add(m);
                }
            }
        }
        return result;
    }

    // ── Save / Load ───────────────────────────────────────────────────────────


    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            JsonArray arr = new JsonArray();
            for (DimensionConfig c : customDimensions) arr.add(c.toJson());
            JsonObject root = new JsonObject();
            if (spawnRedirect != null) root.addProperty("spawnRedirect", spawnRedirect);
            root.add("dimensions", arr);
            Files.writeString(configPath, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (IOException e) {
            CheeseGuardMod.LOGGER.error("[CG] Failed to save dimensions: {}", e.getMessage());
        }
    }

    private void load() {
        customDimensions.clear();
        if (!Files.exists(configPath)) return;
        try {
            String json = Files.readString(configPath);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("spawnRedirect")) spawnRedirect = root.get("spawnRedirect").getAsString();
            if (root.has("dimensions")) {
                for (JsonElement el : root.getAsJsonArray("dimensions")) {
                    try { customDimensions.add(DimensionConfig.fromJson(el.getAsJsonObject())); }
                    catch (Exception e) { CheeseGuardMod.LOGGER.warn("[CG] Skipping invalid dim config: {}", e.getMessage()); }
                }
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.error("[CG] Failed to load dimensions: {}", e.getMessage());
        }
    }
}
