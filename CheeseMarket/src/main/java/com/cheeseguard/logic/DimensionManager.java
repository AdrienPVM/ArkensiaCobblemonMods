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

    // Construction mode: track player inventories (full player NBT) and world origin
    private final Map<UUID, net.minecraft.nbt.NbtCompound> constructionInvSnapshots = new HashMap<>();
    private final Map<UUID, String> constructionSavedGameMode = new HashMap<>(); // gamemode before entering
    private final Map<UUID, String> lastKnownWorld = new HashMap<>();

    public void init(MinecraftServer server) {
        this.server = server;
        this.configPath = server.getRunDirectory().resolve("config").resolve("cheeseguard_dimensions.json");
        load();
        loadConstructionSnapshots(); // Restore crash-survived construction snapshots

        // Auto-register built-in dimensions that exist on server but don't have a config yet
        for (ServerWorld world : server.getWorlds()) {
            String worldId = world.getRegistryKey().getValue().toString();
            if (getConfigByWorldId(worldId) == null) {
                DimensionConfig c = new DimensionConfig(worldId, friendlyName(worldId));
                c.setBuiltIn(true);
                c.setGenType(guessGenType(worldId));
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

        // Apply time locks immediately so dimensions have the correct time from the first tick
        for (DimensionConfig config : customDimensions) {
            applyTimeLock(config);
        }

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
        RegistryEntry<DimensionType> dimType = getDimensionTypeEntry(config.getGenType());
        ChunkGenerator generator = createChunkGenerator(config);
        DimensionOptions dimOptions = new DimensionOptions(dimType, generator);

        // Create world properties (derived from overworld)
        var properties = new net.minecraft.world.level.UnmodifiableLevelProperties(
            server.getSaveProperties(),
            (net.minecraft.world.level.ServerWorldProperties) server.getOverworld().getLevelProperties()
        );

        // Get session from server
        var session = ((MinecraftServerAccessor) server).cg_getSession();

        // Determine seed
        long seed = config.getSeed() != 0 ? config.getSeed() : server.getOverworld().getSeed();

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

        CheeseGuardMod.LOGGER.info("[CG] Runtime dimension created: {}", dimId);
    }

    private RegistryEntry<DimensionType> getDimensionTypeEntry(DimensionConfig.GenType genType) {
        RegistryKey<DimensionType> typeKey = switch (genType) {
            case NETHER -> DimensionTypes.THE_NETHER;
            case END -> DimensionTypes.THE_END;
            default -> DimensionTypes.OVERWORLD;
        };
        return server.getRegistryManager().get(RegistryKeys.DIMENSION_TYPE)
            .getEntry(typeKey).orElseThrow();
    }

    private ChunkGenerator createChunkGenerator(DimensionConfig config) {
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

        // Unload the world
        Identifier dimId = Identifier.of("cheeseguard", config.getId());
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
        ServerWorld world = server.getWorld(worldKey);
        if (world != null) {
            // TP all players in this dimension to overworld
            for (ServerPlayerEntity pl : new ArrayList<>(world.getPlayers())) {
                var ow = server.getOverworld();
                var sp = ow.getSpawnPos();
                pl.teleport(ow, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5,
                    Set.of(), pl.getYaw(), pl.getPitch());
                pl.sendMessage(net.minecraft.text.Text.literal(
                    "\u26A0 La dimension " + config.getDisplayName() + " a ete supprimee.")
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

        customDimensions.remove(config);
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
        world.setTimeOfDay(config.getLockedTime());
    }

    private ServerWorld findWorld(String worldId) {
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldId)) return w;
        }
        return null;
    }

    // ── Tick (enforce time/weather locks) ────────────────────────────────────

    private int tickCounter = 0;

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

        newPlayer.sendMessage(net.minecraft.text.Text.literal(
            "\u2714 Inventaire restaure (keepInventory dimension)")
            .formatted(net.minecraft.util.Formatting.GREEN), false);
        CheeseGuardMod.LOGGER.info("[CG] KeepInventory restored for {} (dimension rule)", newPlayer.getName().getString());
    }

    public void tick() {
        tickCounter++;
        if (server == null) return;

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
                && constructionInvSnapshots.containsKey(player.getUuid());

            if (!legitimateCreative) {
                // Player is creative but shouldn't be — force survival NOW
                CheeseGuardMod.LOGGER.warn(
                    "[CG] ROGUE CREATIVE detected for {} in '{}' — forcing survival instantly",
                    player.getName().getString(), worldId);
                // If they have a snapshot (left construction dim), do a full exit restore
                if (constructionInvSnapshots.containsKey(player.getUuid())) {
                    exitConstructionMode(player);
                } else {
                    // No snapshot: just strip creative, no inventory to restore
                    player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                    player.sendMessage(
                        net.minecraft.text.Text.literal(
                            "⚠ Votre mode Créatif a été retiré automatiquement.")
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
                        if (fromCfg != null && fromCfg.isConstructionMode() && constructionInvSnapshots.containsKey(player.getUuid())) {
                            exitConstructionMode(player);
                        }
                        if (toCfg != null && toCfg.isConstructionMode() && !constructionInvSnapshots.containsKey(player.getUuid())) {
                            enterConstructionMode(player, toCfg);
                        }
                    } else if (lastWorld == null) {
                        DimensionConfig cfg = getConfigByWorldId(currentWorld);
                        if (cfg != null && cfg.isConstructionMode() && !constructionInvSnapshots.containsKey(player.getUuid())) {
                            enterConstructionMode(player, cfg);
                        }
                    }
                    // Safety net for ops too
                    DimensionConfig opCfg = getConfigByWorldId(currentWorld);
                    if (constructionInvSnapshots.containsKey(player.getUuid())
                            && (opCfg == null || !opCfg.isConstructionMode())) {
                        exitConstructionMode(player);
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

                // ── Construction mode tracking ──
                if (lastWorld != null && !lastWorld.equals(currentWorld)) {
                    DimensionConfig fromCfg = getConfigByWorldId(lastWorld);
                    DimensionConfig toCfg = currentCfg;

                    // Leaving construction dimension → restore
                    if (fromCfg != null && fromCfg.isConstructionMode() && constructionInvSnapshots.containsKey(player.getUuid())) {
                        exitConstructionMode(player);
                    }

                    // Entering construction dimension → save + clear
                    if (toCfg != null && toCfg.isConstructionMode() && !constructionInvSnapshots.containsKey(player.getUuid())) {
                        enterConstructionMode(player, toCfg);
                    }
                }
                // First time tracking
                else if (lastWorld == null) {
                    if (currentCfg != null && currentCfg.isConstructionMode() && !constructionInvSnapshots.containsKey(player.getUuid())) {
                        enterConstructionMode(player, currentCfg);
                    }
                }

                // ── Safety net: snapshot present but NOT in construction dim ──
                // Can happen if player was teleported out externally or world was toggled
                if (constructionInvSnapshots.containsKey(player.getUuid())
                        && (currentCfg == null || !currentCfg.isConstructionMode())) {
                    CheeseGuardMod.LOGGER.warn("[CG] Safety net: {} has construction snapshot but is in non-construction dim '{}' — forcing restore",
                        player.getName().getString(), currentWorld);
                    exitConstructionMode(player);
                }

                lastKnownWorld.put(player.getUuid(), currentWorld);
            }
        }

        // Snapshot auto-reset check — every 1s (20 ticks)
        if (tickCounter % 20 == 0) {
            checkAutoResets();
        }

        // Time/weather lock — every 5s (100 ticks)
        if (tickCounter % 100 != 0) return;

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

            // Time lock
            if (config.isTimeLocked()) {
                world.setTimeOfDay(config.getLockedTime());
            }

            // Weather lock
            if (config.isWeatherLocked()) {
                switch (config.getLockedWeather()) {
                    case "clear" -> { world.setWeather(6000, 0, false, false); }
                    case "rain" -> { world.setWeather(0, 6000, true, false); }
                    case "thunder" -> { world.setWeather(0, 6000, true, true); }
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

    private void enterConstructionMode(ServerPlayerEntity player, DimensionConfig cfg) {
        if (constructionInvSnapshots.containsKey(player.getUuid())) return; // already in

        // ── 1. Full snapshot: captures Trinkets, backpack, all modded slots ──
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        player.writeNbt(nbt);
        constructionInvSnapshots.put(player.getUuid(), nbt);

        // Save gamemode so we can restore it on exit
        constructionSavedGameMode.put(player.getUuid(),
            player.interactionManager.getGameMode().getName());

        // ── 2. Persist to disk immediately (crash safety) ──
        saveConstructionSnapshot(player.getUuid(), player.getName().getString(), nbt);

        // ── 3. Wipe EVERYTHING: vanilla + Trinkets ──
        com.cheeseguard.data.EventParticipant.clearFullInventoryForConstruction(player);

        // ── 4. Set gamemode ──
        if (cfg.isConstructionBuilder(player.getUuid().toString())) {
            player.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
            player.sendMessage(net.minecraft.text.Text.literal(
                "🚧 Mode construction activé (Creative). Votre inventaire sera restauré en sortant.")
                .formatted(net.minecraft.util.Formatting.GOLD), false);
        } else {
            player.sendMessage(net.minecraft.text.Text.literal(
                "🚧 Mode construction. Votre inventaire sera restauré en sortant.")
                .formatted(net.minecraft.util.Formatting.YELLOW), false);
        }
        CheeseGuardMod.LOGGER.info("[CG] Construction mode entered for {}", player.getName().getString());
    }

    private void exitConstructionMode(ServerPlayerEntity player) {
        // Idempotency guard: if no snapshot in memory, already exited (e.g. per-tick fired before 20-tick)
        if (!constructionInvSnapshots.containsKey(player.getUuid())
                && !constructionSavedGameMode.containsKey(player.getUuid())) {
            return;
        }

        // ── Anti-duplication: remove from memory AND disk FIRST, before any restore ──
        // If the server crashes mid-restore, the snapshot is already gone → no double-restore.
        // Losing items on a mid-restore crash is far preferable to item duplication.
        net.minecraft.nbt.NbtCompound nbt = constructionInvSnapshots.remove(player.getUuid());
        String savedGm = constructionSavedGameMode.remove(player.getUuid());
        deleteConstructionSnapshot(player.getUuid()); // disk gone immediately — anti-dupe

        // ── 1. Restore gamemode first ──
        net.minecraft.world.GameMode gmToRestore = net.minecraft.world.GameMode.SURVIVAL;
        if (savedGm != null) {
            try { gmToRestore = net.minecraft.world.GameMode.byName(savedGm, net.minecraft.world.GameMode.SURVIVAL); }
            catch (Exception ignored) {}
        }
        // Always force out of CREATIVE for safety (don't let construction items be carried)
        if (player.interactionManager.getGameMode() != gmToRestore) {
            player.changeGameMode(gmToRestore);
        }

        // ── 2. WIPE construction items: vanilla + Trinkets ──
        // Do this BEFORE readNbt so nothing from the construction dim survives
        com.cheeseguard.data.EventParticipant.clearFullInventoryForConstruction(player);

        // ── 3. Nuclear restore from full snapshot ──
        if (nbt != null) {
            // Capture current state we want to KEEP (position, health, hunger, XP)
            double cx = player.getX(), cy = player.getY(), cz = player.getZ();
            float cyaw = player.getYaw(), cpitch = player.getPitch();
            float ch = player.getHealth();
            int cf = player.getHungerManager().getFoodLevel();
            float csat = player.getHungerManager().getSaturationLevel();
            int cxl = player.experienceLevel;
            float cxp = player.experienceProgress;
            int ctx = player.totalExperience;
            var currentGm = player.interactionManager.getGameMode(); // already restored above

            // Nuclear restore — fires all mod hooks (Trinkets, Backpacks, etc.)
            player.readNbt(nbt);

            // Re-apply current state (position, health, XP — NOT inventory)
            player.refreshPositionAndAngles(cx, cy, cz, cyaw, cpitch);
            player.setHealth(Math.max(1f, ch));
            player.getHungerManager().setFoodLevel(cf);
            player.getHungerManager().setSaturationLevel(csat);
            player.experienceLevel = cxl;
            player.experienceProgress = cxp;
            player.totalExperience = ctx;
            player.interactionManager.changeGameMode(currentGm);

            // Force sync to client
            player.getInventory().markDirty();
            player.currentScreenHandler.sendContentUpdates();
            player.playerScreenHandler.sendContentUpdates();
            player.playerScreenHandler.onContentChanged(player.getInventory());

            player.sendMessage(net.minecraft.text.Text.literal(
                "✅ Inventaire restauré. Bienvenue !")
                .formatted(net.minecraft.util.Formatting.GREEN), false);
            CheeseGuardMod.LOGGER.info("[CG] Construction mode exited for {}, inventory restored", player.getName().getString());
        } else {
            // No snapshot — just make sure they're clean (shouldn't happen, log it)
            CheeseGuardMod.LOGGER.warn("[CG] exitConstructionMode: no snapshot for {}, inventory NOT restored",
                player.getName().getString());
            player.sendMessage(net.minecraft.text.Text.literal(
                "⚠ Aucun snapshot de votre inventaire — contactez un admin.")
                .formatted(net.minecraft.util.Formatting.RED), false);
        }
    }

    // ── Construction snapshot persistence (crash safety) ─────────────────────

    private Path getConstructionSnapshotDir() {
        return server.getRunDirectory().resolve("config").resolve("cheeseguard_construction_snapshots");
    }

    private void saveConstructionSnapshot(UUID uuid, String playerName, net.minecraft.nbt.NbtCompound nbt) {
        try {
            Path dir = getConstructionSnapshotDir();
            java.nio.file.Files.createDirectories(dir);
            net.minecraft.nbt.NbtCompound root = new net.minecraft.nbt.NbtCompound();
            root.putString("playerName", playerName);
            root.put("playerData", nbt);
            net.minecraft.nbt.NbtIo.writeCompressed(root, dir.resolve(uuid + ".nbt"));
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to persist construction snapshot for {}: {}", playerName, e.getMessage());
        }
    }

    private void deleteConstructionSnapshot(UUID uuid) {
        try {
            java.nio.file.Files.deleteIfExists(getConstructionSnapshotDir().resolve(uuid + ".nbt"));
        } catch (Exception ignored) {}
    }

    /** On server start: load any construction snapshots that survived a crash. */
    private void loadConstructionSnapshots() {
        Path dir = getConstructionSnapshotDir();
        if (!java.nio.file.Files.exists(dir)) return;
        try (java.nio.file.DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path file : stream) {
                try {
                    net.minecraft.nbt.NbtCompound root = net.minecraft.nbt.NbtIo.readCompressed(
                        file, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
                    String filename = file.getFileName().toString().replace(".nbt", "");
                    UUID uuid = UUID.fromString(filename);
                    if (root.contains("playerData")) {
                        constructionInvSnapshots.put(uuid, root.getCompound("playerData"));
                        CheeseGuardMod.LOGGER.info("[CG] Loaded crash construction snapshot for {}",
                            root.contains("playerName") ? root.getString("playerName") : uuid);
                    }
                } catch (Exception e) {
                    CheeseGuardMod.LOGGER.warn("[CG] Failed to load construction snapshot {}: {}", file, e.getMessage());
                }
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to scan construction snapshots: {}", e.getMessage());
        }
    }

    /** Called when player disconnects to clean up tracking. */
    public void onPlayerDisconnect(UUID uuid) {
        lastKnownWorld.remove(uuid);
        // Keep constructionInvSnapshots — already persisted to disk, restored on reconnect
        // Do NOT remove constructionSavedGameMode either
    }

    /** Called on player join: handle crash recovery and state consistency. */
    public void onPlayerJoin(ServerPlayerEntity player) {
        String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
        DimensionConfig cfg = getConfigByWorldId(worldId);

        boolean hasSnapshot      = constructionInvSnapshots.containsKey(player.getUuid());
        boolean inConstructionDim = cfg != null && cfg.isConstructionMode();
        boolean isOp             = player.hasPermissionLevel(2);

        if (hasSnapshot && inConstructionDim) {
            // Snapshot present + in construction dim → re-apply builder rules only
            if (cfg.isConstructionBuilder(player.getUuid().toString())
                    && player.interactionManager.getGameMode() != net.minecraft.world.GameMode.CREATIVE) {
                player.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
            }
            CheeseGuardMod.LOGGER.info("[CG] {} reconnected inside construction dim, snapshot active", player.getName().getString());

        } else if (hasSnapshot && !inConstructionDim) {
            // Snapshot present but outside construction dim (crash/external TP) → restore
            CheeseGuardMod.LOGGER.warn("[CG] {} has construction snapshot but is outside construction dim — restoring now",
                player.getName().getString());
            exitConstructionMode(player);

        } else if (!hasSnapshot && inConstructionDim) {
            // No snapshot + in construction dim → fresh entry
            enterConstructionMode(player, cfg);

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
                net.minecraft.text.Text.literal(
                    "⚠ Votre mode Créatif a été retiré automatiquement au login (aucun contexte de construction actif).")
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
        for (DimensionConfig c : customDimensions) {
            if (c.getWorldId().equals(worldId)) return c;
        }
        return null;
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
        if (!config.isSnapshotEnabled() || !Files.exists(getSnapshotFolder(config))) return "no_snapshot";

        ServerWorld world = findWorld(config.getWorldId());
        if (world != null && !world.getPlayers().isEmpty()) {
            // Kick all players first, then mark pending
            for (ServerPlayerEntity pl : new java.util.ArrayList<>(world.getPlayers())) {
                var ow = server.getOverworld();
                var sp = ow.getSpawnPos();
                pl.teleport(ow, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5,
                    Set.of(), pl.getYaw(), pl.getPitch());
                pl.sendMessage(net.minecraft.text.Text.literal(
                    "\u26A0 Cette dimension est en cours de réinitialisation.")
                    .formatted(net.minecraft.util.Formatting.YELLOW), false);
            }
            pendingResets.add(configId);
            return "pending";
        } else {
            return executeReset(configId) ? "ok" : "error";
        }
    }

    /** Actually perform the file copy. Call only when dimension is empty / safe. */
    private boolean executeReset(String configId) {
        DimensionConfig config = getConfig(configId);
        if (config == null) return false;
        Path snapshotDir = getSnapshotFolder(config);
        if (!Files.exists(snapshotDir)) {
            CheeseGuardMod.LOGGER.warn("[CG] Snapshot dir not found for '{}', skipping reset", configId);
            return false;
        }

        // Save & flush the world before overwriting
        ServerWorld world = findWorld(config.getWorldId());
        if (world != null) {
            try { world.save(null, true, false); } catch (Exception ignored) {}
        }

        Path destDir = getDimWorldFolder(config);
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

            config.setLastResetAt(System.currentTimeMillis());
            save();
            CheeseGuardMod.LOGGER.info("[CG] Reset executed for dim '{}'", configId);
            return true;
        } catch (IOException e) {
            CheeseGuardMod.LOGGER.error("[CG] Failed to reset dim '{}': {}", configId, e.getMessage());
            return false;
        }
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

            // ── ON_EMPTY: reset when last player leaves ──
            if (config.getSnapshotResetTrigger() == DimensionConfig.ResetTrigger.ON_EMPTY) {
                boolean empty = world == null || world.getPlayers().isEmpty();
                // We only want to fire ONCE when it transitions to empty.
                // Use a simple heuristic: if world exists and is empty, schedule reset.
                if (empty && world != null) {
                    // Only if it was used recently (players were in it at some point today)
                    // Just always queue it - executeReset is idempotent
                    pendingResets.add(configId);
                }
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

    // ── Save / Load ───────────────────────────────────────────────────────────

    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            JsonArray arr = new JsonArray();
            for (DimensionConfig c : customDimensions) arr.add(c.toJson());
            JsonObject root = new JsonObject();
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
