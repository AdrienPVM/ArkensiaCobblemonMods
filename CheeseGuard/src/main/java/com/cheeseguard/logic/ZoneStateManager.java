package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Region;
import com.cheeseguard.data.ZoneState;
import com.google.gson.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Manages named states (block snapshots) for protection zones.
 * Each state captures all blocks, block entities, and entities within a zone's bounding box.
 * States can be applied instantly or with layer-by-layer animation.
 *
 * Storage: config/cheeseguard_states/{regionId}/
 *   - index.json  — metadata (id, name, createdAt, blockCount, entityCount)
 *   - {stateId}.nbt — block data (BlockState + block entity NBT + entity NBT)
 */
public class ZoneStateManager {

    private static final org.slf4j.Logger LOGGER = CheeseGuardMod.LOGGER;

    // ── Enums ────────────────────────────────────────────────────────────

    public enum Direction {
        BOTTOM_UP, TOP_DOWN,
        SLIDE_FROM_BOTTOM, SLIDE_FROM_TOP,
        SLIDE_FROM_NORTH, SLIDE_FROM_SOUTH,
        SLIDE_FROM_EAST, SLIDE_FROM_WEST,
        CASCADE_FROM_BOTTOM, CASCADE_FROM_TOP,
        CASCADE_FROM_NORTH, CASCADE_FROM_SOUTH,
        CASCADE_FROM_EAST, CASCADE_FROM_WEST;

        /** True for any displacement animation (slide or cascade) */
        public boolean isSlide() { return name().startsWith("SLIDE_") || name().startsWith("CASCADE_"); }

        /** Cascade: closest-first activation (blocks reorder during transit) */
        public boolean isCascade() { return name().startsWith("CASCADE_"); }

        /** Axis index: 0=X, 1=Y, 2=Z */
        public int slideAxis() {
            return switch (this) {
                case SLIDE_FROM_EAST, SLIDE_FROM_WEST, CASCADE_FROM_EAST, CASCADE_FROM_WEST -> 0;
                case SLIDE_FROM_BOTTOM, SLIDE_FROM_TOP, CASCADE_FROM_BOTTOM, CASCADE_FROM_TOP -> 1;
                case SLIDE_FROM_NORTH, SLIDE_FROM_SOUTH, CASCADE_FROM_NORTH, CASCADE_FROM_SOUTH -> 2;
                default -> 1;
            };
        }

        /** Movement direction sign along the axis (+1 or -1) */
        public int slideSign() {
            return switch (this) {
                case SLIDE_FROM_BOTTOM, SLIDE_FROM_NORTH, SLIDE_FROM_WEST,
                     CASCADE_FROM_BOTTOM, CASCADE_FROM_NORTH, CASCADE_FROM_WEST -> 1;
                case SLIDE_FROM_TOP, SLIDE_FROM_SOUTH, SLIDE_FROM_EAST,
                     CASCADE_FROM_TOP, CASCADE_FROM_SOUTH, CASCADE_FROM_EAST -> -1;
                default -> 1;
            };
        }

        /** Coordinate of the entry face */
        public int entryCoord(int minCoord, int maxCoord) {
            return switch (this) {
                case SLIDE_FROM_BOTTOM, SLIDE_FROM_NORTH, SLIDE_FROM_WEST,
                     CASCADE_FROM_BOTTOM, CASCADE_FROM_NORTH, CASCADE_FROM_WEST -> minCoord;
                case SLIDE_FROM_TOP, SLIDE_FROM_SOUTH, SLIDE_FROM_EAST,
                     CASCADE_FROM_TOP, CASCADE_FROM_SOUTH, CASCADE_FROM_EAST -> maxCoord;
                default -> minCoord;
            };
        }

        public static Direction fromString(String s) {
            if (s == null) return BOTTOM_UP;
            return switch (s.toLowerCase()) {
                case "top_down", "topdown", "td" -> TOP_DOWN;
                case "slide_from_bottom", "sfb" -> SLIDE_FROM_BOTTOM;
                case "slide_from_top", "sft" -> SLIDE_FROM_TOP;
                case "slide_from_north", "sfn" -> SLIDE_FROM_NORTH;
                case "slide_from_south", "sfs" -> SLIDE_FROM_SOUTH;
                case "slide_from_east", "sfe" -> SLIDE_FROM_EAST;
                case "slide_from_west", "sfw" -> SLIDE_FROM_WEST;
                case "cascade_from_bottom", "cfb" -> CASCADE_FROM_BOTTOM;
                case "cascade_from_top", "cft" -> CASCADE_FROM_TOP;
                case "cascade_from_north", "cfn" -> CASCADE_FROM_NORTH;
                case "cascade_from_south", "cfs" -> CASCADE_FROM_SOUTH;
                case "cascade_from_east", "cfe" -> CASCADE_FROM_EAST;
                case "cascade_from_west", "cfw" -> CASCADE_FROM_WEST;
                default -> BOTTOM_UP;
            };
        }
    }

    public enum StateParticle {
        NONE, CLOUD, POOF, SMOKE, FLAME, SOUL, SPARKLE, ENCHANT,
        TOTEM, HEART, DRIP, CHERRY, SCULK, WITCH, SNOWFLAKE, NOTE, ELECTRIC, ASH;

        public ParticleEffect toParticle() {
            return switch (this) {
                case CLOUD     -> ParticleTypes.CLOUD;
                case POOF      -> ParticleTypes.POOF;
                case SMOKE     -> ParticleTypes.SMOKE;
                case FLAME     -> ParticleTypes.FLAME;
                case SOUL      -> ParticleTypes.SOUL_FIRE_FLAME;
                case SPARKLE   -> ParticleTypes.END_ROD;
                case ENCHANT   -> ParticleTypes.ENCHANTED_HIT;
                case TOTEM     -> ParticleTypes.TOTEM_OF_UNDYING;
                case HEART     -> ParticleTypes.HEART;
                case DRIP      -> ParticleTypes.DRIPPING_HONEY;
                case CHERRY    -> ParticleTypes.CHERRY_LEAVES;
                case SCULK     -> ParticleTypes.SCULK_SOUL;
                case WITCH     -> ParticleTypes.WITCH;
                case SNOWFLAKE -> ParticleTypes.SNOWFLAKE;
                case NOTE      -> ParticleTypes.NOTE;
                case ELECTRIC  -> ParticleTypes.ELECTRIC_SPARK;
                case ASH       -> ParticleTypes.WHITE_ASH;
                default        -> null;
            };
        }

        public static StateParticle fromString(String s) {
            if (s == null) return CLOUD;
            try { return valueOf(s.toUpperCase()); }
            catch (Exception e) { return CLOUD; }
        }
    }

    // ── Fields ───────────────────────────────────────────────────────────

    private Path statesDir;
    private MinecraftServer server;
    private final List<AnimationTask> runningAnimations = new ArrayList<>();
    private final List<SlideAnimationTask> runningSlideAnimations = new ArrayList<>();

    // ── Init ─────────────────────────────────────────────────────────────

    public void init(MinecraftServer server) {
        this.server = server;
        this.statesDir = server.getRunDirectory().resolve("config").resolve("cheeseguard_states");
        try { Files.createDirectories(statesDir); }
        catch (Exception e) { LOGGER.warn("[CG] Failed to create states dir: {}", e.getMessage()); }
    }

    // ── Capture ──────────────────────────────────────────────────────────

    /**
     * Capture the current blocks, block entities, and entities in a zone. Must be called from server thread.
     * @return ZoneState metadata on success, null on failure
     */
    public ZoneState capture(String regionId, String name) {
        Region region = getRegion(regionId);
        if (region == null) return null;

        ServerWorld world = getWorld(region.getWorld());
        if (world == null) return null;

        // Load or create index
        IndexData idx = loadIndex(regionId);
        String stateId = String.valueOf(idx.nextId);
        idx.nextId++;

        // Build NBT root
        NbtCompound root = new NbtCompound();
        root.putString("worldId", region.getWorld());
        root.putInt("minX", region.getMinX()); root.putInt("minY", region.getMinY()); root.putInt("minZ", region.getMinZ());
        root.putInt("maxX", region.getMaxX()); root.putInt("maxY", region.getMaxY()); root.putInt("maxZ", region.getMaxZ());

        // Capture blocks + block entities
        NbtList blockList = new NbtList();
        int blockCount = 0;
        for (int x = region.getMinX(); x <= region.getMaxX(); x++) {
            for (int y = region.getMinY(); y <= region.getMaxY(); y++) {
                for (int z = region.getMinZ(); z <= region.getMaxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;

                    NbtCompound blockTag = new NbtCompound();
                    blockTag.putInt("x", x);
                    blockTag.putInt("y", y);
                    blockTag.putInt("z", z);
                    blockTag.put("state", NbtHelper.fromBlockState(state));

                    // Save block entity data (chests, signs, etc.)
                    BlockEntity be = world.getBlockEntity(pos);
                    if (be != null) {
                        try {
                            NbtCompound beNbt = be.createNbtWithId(world.getRegistryManager());
                            blockTag.put("be", beNbt);
                        } catch (Exception e) {
                            LOGGER.warn("[CG] Failed to save block entity at {}: {}", pos, e.getMessage());
                        }
                    }

                    blockList.add(blockTag);
                    blockCount++;
                }
            }
        }
        root.put("blocks", blockList);

        // Capture entities (exclude players)
        Box box = new Box(region.getMinX(), region.getMinY(), region.getMinZ(),
                          region.getMaxX() + 1, region.getMaxY() + 1, region.getMaxZ() + 1);
        List<Entity> entities = world.getOtherEntities(null, box, e -> !(e instanceof PlayerEntity));
        NbtList entityList = new NbtList();
        int entityCount = 0;
        for (Entity entity : entities) {
            try {
                NbtCompound entityNbt = new NbtCompound();
                if (entity.saveSelfNbt(entityNbt)) {
                    entityList.add(entityNbt);
                    entityCount++;
                }
            } catch (Exception e) {
                LOGGER.warn("[CG] Failed to save entity {}: {}", entity.getType().getUntranslatedName(), e.getMessage());
            }
        }
        root.put("entities", entityList);

        // Write NBT to disk
        try {
            Path regionDir = statesDir.resolve(regionId);
            Files.createDirectories(regionDir);
            NbtIo.writeCompressed(root, regionDir.resolve(stateId + ".nbt"));
        } catch (Exception e) {
            LOGGER.error("[CG] Failed to save state NBT for {}/{}: {}", regionId, stateId, e.getMessage());
            return null;
        }

        // Update index
        ZoneState zoneState = new ZoneState(stateId, name, System.currentTimeMillis(), blockCount, entityCount);
        idx.states.add(zoneState);
        saveIndex(regionId, idx);

        LOGGER.info("[CG] Zone state captured: {}/{} '{}' ({} blocks, {} entities)",
            regionId, stateId, name, blockCount, entityCount);
        return zoneState;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────

    public List<ZoneState> listStates(String regionId) {
        return loadIndex(regionId).states;
    }

    public ZoneState getStateInfo(String regionId, String stateId) {
        return loadIndex(regionId).states.stream()
            .filter(s -> s.getId().equals(stateId))
            .findFirst().orElse(null);
    }

    public boolean renameState(String regionId, String stateId, String newName) {
        IndexData idx = loadIndex(regionId);
        for (ZoneState s : idx.states) {
            if (s.getId().equals(stateId)) {
                s.setName(newName);
                saveIndex(regionId, idx);
                return true;
            }
        }
        return false;
    }

    public boolean deleteState(String regionId, String stateId) {
        IndexData idx = loadIndex(regionId);
        boolean removed = idx.states.removeIf(s -> s.getId().equals(stateId));
        if (!removed) {
            LOGGER.warn("[CG] deleteState: state {}/{} not found in index", regionId, stateId);
            return false;
        }
        saveIndex(regionId, idx);
        Path nbtFile = statesDir.resolve(regionId).resolve(stateId + ".nbt");
        try {
            boolean deleted = Files.deleteIfExists(nbtFile);
            LOGGER.info("[CG] deleteState: {}/{} — index updated, nbt file {} (existed={})",
                regionId, stateId, deleted ? "deleted" : "not found", deleted);
        } catch (Exception e) {
            LOGGER.warn("[CG] deleteState: failed to delete {}: {}", nbtFile, e.getMessage());
        }
        return true;
    }

    public boolean isAnimating(String regionId) {
        return runningAnimations.stream().anyMatch(t -> t.regionId.equals(regionId))
            || runningSlideAnimations.stream().anyMatch(t -> t.regionId.equals(regionId));
    }

    public boolean cancelAnimation(String regionId) {
        boolean found = runningAnimations.removeIf(t -> t.regionId.equals(regionId));
        Iterator<SlideAnimationTask> sit = runningSlideAnimations.iterator();
        while (sit.hasNext()) {
            SlideAnimationTask task = sit.next();
            if (task.regionId.equals(regionId)) {
                task.cleanup();
                sit.remove();
                found = true;
            }
        }
        return found;
    }

    // ── Apply State ──────────────────────────────────────────────────────

    /**
     * Apply a saved state to a zone. Must be called from server thread.
     * @return null on success, error message on failure
     */
    public String applyState(String regionId, String stateId, boolean animated,
                             Direction direction, int ticksPerLayer, StateParticle particle,
                             boolean blockByBlock, String sourceWorldId) {
        if (isAnimating(regionId)) return "Animation already running on this zone";

        Region region = getRegion(regionId);
        if (region == null) return "Zone not found";

        // Resolve target world: if source is a per-player instance of the zone's world, use source world
        String targetWorldId = resolveTargetWorld(region.getWorld(), sourceWorldId);
        ServerWorld world = getWorld(targetWorldId);
        if (world == null) return "World not loaded";

        // Load target state NBT
        NbtCompound stateNbt;
        try {
            Path nbtFile = statesDir.resolve(regionId).resolve(stateId + ".nbt");
            if (!Files.exists(nbtFile)) return "State file not found";
            stateNbt = NbtIo.readCompressed(nbtFile, NbtSizeTracker.ofUnlimitedBytes());
        } catch (Exception e) {
            return "Failed to load state: " + e.getMessage();
        }

        // Parse target blocks + block entities
        Map<BlockPos, BlockState> targetBlocks = new HashMap<>();
        Map<BlockPos, NbtCompound> targetBlockEntities = new HashMap<>();
        NbtList blockList = stateNbt.getList("blocks", 10);
        for (int i = 0; i < blockList.size(); i++) {
            NbtCompound tag = blockList.getCompound(i);
            BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            BlockState state = NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), tag.getCompound("state"));
            targetBlocks.put(pos, state);
            if (tag.contains("be")) {
                targetBlockEntities.put(pos, tag.getCompound("be"));
            }
        }

        // Parse target entities
        List<NbtCompound> targetEntities = new ArrayList<>();
        if (stateNbt.contains("entities")) {
            NbtList entityList = stateNbt.getList("entities", 10);
            for (int i = 0; i < entityList.size(); i++) {
                targetEntities.add(entityList.getCompound(i));
            }
        }

        // Compute diff grouped by Y level — only include blocks that actually changed
        Map<Integer, List<BlockChange>> layerChanges = new TreeMap<>();
        for (int y = region.getMinY(); y <= region.getMaxY(); y++) {
            List<BlockChange> changes = new ArrayList<>();
            for (int x = region.getMinX(); x <= region.getMaxX(); x++) {
                for (int z = region.getMinZ(); z <= region.getMaxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState currentState = world.getBlockState(pos);
                    BlockState targetState = targetBlocks.getOrDefault(pos, Blocks.AIR.getDefaultState());

                    if (currentState.equals(targetState)) {
                        // Block type is same — check block entity data too
                        NbtCompound targetBe = targetBlockEntities.get(pos);
                        BlockEntity currentBe = world.getBlockEntity(pos);
                        if (currentBe == null && targetBe == null) continue; // Both null — identical
                        if (currentBe != null && targetBe != null) {
                            NbtCompound currentBeNbt = currentBe.createNbtWithId(world.getRegistryManager());
                            if (currentBeNbt.equals(targetBe)) continue; // Same block entity data — identical
                        }
                        // Block entity differs — need to update
                    }

                    changes.add(new BlockChange(pos, targetState, targetBlockEntities.get(pos)));
                }
            }
            if (!changes.isEmpty()) {
                layerChanges.put(y, changes);
            }
        }

        // Remove existing non-player entities in the zone
        Box box = new Box(region.getMinX(), region.getMinY(), region.getMinZ(),
                          region.getMaxX() + 1, region.getMaxY() + 1, region.getMaxZ() + 1);
        List<Entity> existingEntities = world.getOtherEntities(null, box, e -> !(e instanceof PlayerEntity));
        for (Entity e : existingEntities) {
            e.discard();
        }

        // Instant apply
        if (!animated || layerChanges.isEmpty()) {
            for (var entry : layerChanges.values()) {
                for (BlockChange change : entry) {
                    applyBlockChange(world, change);
                }
            }
            spawnEntities(world, targetEntities);
            LOGGER.info("[CG] Zone state applied instantly: {}/{}", regionId, stateId);
            return null;
        }

        // Slide animated apply — displacement animation
        if (direction.isSlide()) {
            int axis = direction.slideAxis();
            Map<Integer, List<BlockChange>> sliceChanges;
            if (axis == 1) {
                sliceChanges = layerChanges; // already grouped by Y
            } else {
                sliceChanges = new TreeMap<>();
                for (var entry : layerChanges.values()) {
                    for (BlockChange change : entry) {
                        int key = coordAlongAxis(change.pos, axis);
                        sliceChanges.computeIfAbsent(key, k -> new ArrayList<>()).add(change);
                    }
                }
            }
            SlideAnimationTask slideTask = new SlideAnimationTask(
                regionId, world, region, sliceChanges, direction,
                Math.max(1, ticksPerLayer), particle, targetEntities
            );
            runningSlideAnimations.add(slideTask);
            LOGGER.info("[CG] Slide animation started: {}/{} (dir={}, {} waves, {} ticks/step)",
                regionId, stateId, direction, slideTask.waves.size(), ticksPerLayer);
            return null;
        }

        // Regular animated apply — sort layers by direction
        List<Integer> layerOrder = new ArrayList<>(layerChanges.keySet());
        if (direction == Direction.TOP_DOWN) {
            Collections.reverse(layerOrder);
        }

        AnimationTask task = new AnimationTask(
            regionId, world, layerChanges, layerOrder,
            Math.max(1, ticksPerLayer), particle, targetEntities, blockByBlock
        );
        runningAnimations.add(task);
        LOGGER.info("[CG] Zone state animation started: {}/{} ({} layers, {} ticks/{}, {})",
            regionId, stateId, layerOrder.size(), ticksPerLayer,
            blockByBlock ? "block" : "layer", particle);
        return null;
    }

    // ── Tick (server thread) ─────────────────────────────────────────────

    public void tick() {
        if (!runningAnimations.isEmpty()) {
            Iterator<AnimationTask> it = runningAnimations.iterator();
            while (it.hasNext()) {
                AnimationTask task = it.next();
                if (task.tick()) {
                    it.remove();
                    LOGGER.debug("[CG] Zone state animation completed: {}", task.regionId);
                }
            }
        }
        if (!runningSlideAnimations.isEmpty()) {
            Iterator<SlideAnimationTask> sit = runningSlideAnimations.iterator();
            while (sit.hasNext()) {
                SlideAnimationTask task = sit.next();
                if (task.tick()) {
                    sit.remove();
                    LOGGER.debug("[CG] Slide animation completed: {}", task.regionId);
                }
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private void applyBlockChange(ServerWorld world, BlockChange change) {
        world.setBlockState(change.pos, change.targetState, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
        if (change.blockEntityNbt != null) {
            // Create block entity from saved NBT and inject into chunk
            NbtCompound beNbt = change.blockEntityNbt.copy();
            beNbt.putInt("x", change.pos.getX());
            beNbt.putInt("y", change.pos.getY());
            beNbt.putInt("z", change.pos.getZ());
            BlockEntity be = BlockEntity.createFromNbt(change.pos, change.targetState, beNbt, world.getRegistryManager());
            if (be != null) {
                world.getWorldChunk(change.pos).setBlockEntity(be);
            }
        }
    }

    /**
     * Resolves the target world for state application.
     * If sourceWorld is a per-player instance of the zone's world, use the per-player instance.
     */
    private String resolveTargetWorld(String zoneWorld, String sourceWorld) {
        if (sourceWorld == null || sourceWorld.equals(zoneWorld)) return zoneWorld;
        // Check if sourceWorld is a per-player instance of zoneWorld
        String masterWorld = RegionManager.getMasterWorldForPerPlayer(sourceWorld);
        if (masterWorld != null && masterWorld.equals(zoneWorld)) {
            return sourceWorld; // Apply in per-player instance
        }
        return zoneWorld;
    }

    private void spawnEntities(ServerWorld world, List<NbtCompound> entityNbts) {
        for (NbtCompound nbt : entityNbts) {
            try {
                EntityType.loadEntityWithPassengers(nbt, world, entity -> {
                    world.spawnEntity(entity);
                    return entity;
                });
            } catch (Exception e) {
                LOGGER.warn("[CG] Failed to spawn entity: {}", e.getMessage());
            }
        }
    }

    private Region getRegion(String regionId) {
        var storage = CheeseGuardMod.getInstance().getRegionManager().getStorage();
        return storage != null ? storage.getRegion(regionId) : null;
    }

    private ServerWorld getWorld(String worldId) {
        if (server == null) return null;
        if (worldId == null || worldId.isBlank()) return server.getOverworld();
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldId)) return w;
        }
        return server.getOverworld();
    }

    // ── Index I/O ────────────────────────────────────────────────────────

    private static class IndexData {
        int nextId = 1;
        List<ZoneState> states = new ArrayList<>();
    }

    private IndexData loadIndex(String regionId) {
        IndexData data = new IndexData();
        Path indexFile = statesDir.resolve(regionId).resolve("index.json");
        if (!Files.exists(indexFile)) return data;
        try {
            String json = Files.readString(indexFile, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            data.nextId = obj.has("nextId") ? obj.get("nextId").getAsInt() : 1;
            if (obj.has("states")) {
                for (JsonElement el : obj.getAsJsonArray("states")) {
                    JsonObject so = el.getAsJsonObject();
                    data.states.add(new ZoneState(
                        so.get("id").getAsString(),
                        so.get("name").getAsString(),
                        so.has("createdAt") ? so.get("createdAt").getAsLong() : 0,
                        so.has("blockCount") ? so.get("blockCount").getAsInt() : 0,
                        so.has("entityCount") ? so.get("entityCount").getAsInt() : 0
                    ));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[CG] Failed to load state index for {}: {}", regionId, e.getMessage());
        }
        return data;
    }

    private void saveIndex(String regionId, IndexData data) {
        try {
            Path regionDir = statesDir.resolve(regionId);
            Files.createDirectories(regionDir);
            JsonObject obj = new JsonObject();
            obj.addProperty("nextId", data.nextId);
            JsonArray arr = new JsonArray();
            for (ZoneState s : data.states) {
                JsonObject so = new JsonObject();
                so.addProperty("id", s.getId());
                so.addProperty("name", s.getName());
                so.addProperty("createdAt", s.getCreatedAt());
                so.addProperty("blockCount", s.getBlockCount());
                so.addProperty("entityCount", s.getEntityCount());
                arr.add(so);
            }
            obj.add("states", arr);
            Files.writeString(regionDir.resolve("index.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[CG] Failed to save state index for {}: {}", regionId, e.getMessage());
        }
    }

    // ── Internal records & helpers ──────────────────────────────────────

    private record BlockChange(BlockPos pos, BlockState targetState, NbtCompound blockEntityNbt) {}

    private record SavedBlock(BlockState state, NbtCompound blockEntityNbt) {}

    private static int coordAlongAxis(BlockPos pos, int axis) {
        return switch (axis) {
            case 0 -> pos.getX();
            case 2 -> pos.getZ();
            default -> pos.getY();
        };
    }

    private static BlockPos displacePos(BlockPos original, int axis, int newCoord) {
        return switch (axis) {
            case 0 -> new BlockPos(newCoord, original.getY(), original.getZ());
            case 2 -> new BlockPos(original.getX(), original.getY(), newCoord);
            default -> new BlockPos(original.getX(), newCoord, original.getZ());
        };
    }

    // ── Animation Task ───────────────────────────────────────────────────

    private class AnimationTask {
        final String regionId;
        final ServerWorld world;
        final Map<Integer, List<BlockChange>> layerChanges;
        final List<Integer> layerOrder;
        final int ticksPerStep;
        final StateParticle particle;
        final List<NbtCompound> targetEntities;
        final boolean blockByBlock;
        final List<BlockChange> flatChanges; // used in block-by-block mode
        int currentLayerIdx = 0;
        int currentBlockIdx = 0;
        int tickCounter;

        AnimationTask(String regionId, ServerWorld world, Map<Integer, List<BlockChange>> layerChanges,
                      List<Integer> layerOrder, int ticksPerStep, StateParticle particle,
                      List<NbtCompound> targetEntities, boolean blockByBlock) {
            this.regionId = regionId;
            this.world = world;
            this.layerChanges = layerChanges;
            this.layerOrder = layerOrder;
            this.ticksPerStep = ticksPerStep;
            this.particle = particle;
            this.targetEntities = targetEntities;
            this.blockByBlock = blockByBlock;
            this.tickCounter = ticksPerStep - 1; // Fire first step on next tick

            // Pre-build flat list for block-by-block mode (ordered by direction then X/Z)
            if (blockByBlock) {
                flatChanges = new ArrayList<>();
                for (int y : layerOrder) {
                    List<BlockChange> layer = layerChanges.get(y);
                    if (layer != null) flatChanges.addAll(layer);
                }
            } else {
                flatChanges = null;
            }
        }

        /** @return true when animation is complete */
        boolean tick() {
            return blockByBlock ? tickBlock() : tickLayer();
        }

        private boolean tickBlock() {
            if (currentBlockIdx >= flatChanges.size()) {
                spawnEntities(world, targetEntities);
                return true;
            }

            tickCounter++;
            if (tickCounter < ticksPerStep) return false;
            tickCounter = 0;

            BlockChange change = flatChanges.get(currentBlockIdx);
            applyBlockChange(world, change);
            ParticleEffect particleEffect = particle.toParticle();
            if (particleEffect != null) {
                world.spawnParticles(particleEffect,
                    change.pos.getX() + 0.5, change.pos.getY() + 0.5, change.pos.getZ() + 0.5,
                    3, 0.25, 0.25, 0.25, 0.02);
            }

            currentBlockIdx++;
            if (currentBlockIdx >= flatChanges.size()) {
                spawnEntities(world, targetEntities);
                return true;
            }
            return false;
        }

        private boolean tickLayer() {
            if (currentLayerIdx >= layerOrder.size()) {
                spawnEntities(world, targetEntities);
                return true;
            }

            tickCounter++;
            if (tickCounter < ticksPerStep) return false;
            tickCounter = 0;

            int y = layerOrder.get(currentLayerIdx);
            List<BlockChange> changes = layerChanges.get(y);
            if (changes != null) {
                ParticleEffect particleEffect = particle.toParticle();
                for (BlockChange change : changes) {
                    applyBlockChange(world, change);
                    if (particleEffect != null) {
                        world.spawnParticles(particleEffect,
                            change.pos.getX() + 0.5, change.pos.getY() + 0.5, change.pos.getZ() + 0.5,
                            3, 0.25, 0.25, 0.25, 0.02);
                    }
                }
            }

            currentLayerIdx++;
            if (currentLayerIdx >= layerOrder.size()) {
                spawnEntities(world, targetEntities);
                return true;
            }
            return false;
        }
    }

    // ── Slide Animation Task ────────────────────────────────────────────

    private class SlideAnimationTask {
        final String regionId;
        final ServerWorld world;
        final int ticksPerStep;
        final StateParticle particle;
        final List<NbtCompound> targetEntities;
        final int axis;       // 0=X, 1=Y, 2=Z
        final int sign;       // +1 or -1
        final int entryCoord;
        final int minBound, maxBound;

        final List<SliceWave> waves;
        final List<SliceWave> activeWaves = new ArrayList<>();
        int nextWaveIdx = 0;
        int tickCounter;

        final Map<BlockPos, SavedBlock> savedBlocks = new HashMap<>();

        class SliceWave {
            final int finalCoord;
            final List<BlockChange> solidChanges;
            final List<BlockChange> airChanges;
            int currentCoord;

            SliceWave(int finalCoord, List<BlockChange> solid, List<BlockChange> air) {
                this.finalCoord = finalCoord;
                this.solidChanges = solid;
                this.airChanges = air;
                this.currentCoord = entryCoord;
            }
        }

        SlideAnimationTask(String regionId, ServerWorld world, Region region,
                           Map<Integer, List<BlockChange>> sliceChanges,
                           Direction direction, int ticksPerStep, StateParticle particle,
                           List<NbtCompound> targetEntities) {
            this.regionId = regionId;
            this.world = world;
            this.ticksPerStep = Math.max(1, ticksPerStep);
            this.particle = particle;
            this.targetEntities = targetEntities;
            this.axis = direction.slideAxis();
            this.sign = direction.slideSign();

            BlockPos minCorner = new BlockPos(region.getMinX(), region.getMinY(), region.getMinZ());
            BlockPos maxCorner = new BlockPos(region.getMaxX(), region.getMaxY(), region.getMaxZ());
            this.minBound = coordAlongAxis(minCorner, axis);
            this.maxBound = coordAlongAxis(maxCorner, axis);
            this.entryCoord = direction.entryCoord(minBound, maxBound);

            // Build waves ordered from entry face inward
            List<Integer> coords = new ArrayList<>(sliceChanges.keySet());
            // Slide: farthest first → structure stays intact during transit
            // Cascade: closest first → blocks reorder/reassemble during transit
            boolean sortDescending = direction.isCascade() ? (sign < 0) : (sign > 0);
            if (sortDescending) {
                coords.sort(Collections.reverseOrder());
            }

            this.waves = new ArrayList<>();
            for (int coord : coords) {
                List<BlockChange> changes = sliceChanges.get(coord);
                List<BlockChange> solid = new ArrayList<>();
                List<BlockChange> air = new ArrayList<>();
                for (BlockChange c : changes) {
                    if (c.targetState.isAir()) air.add(c);
                    else solid.add(c);
                }
                if (solid.isEmpty() && !air.isEmpty()) {
                    for (BlockChange c : air) applyBlockChange(world, c);
                } else if (!solid.isEmpty()) {
                    waves.add(new SliceWave(coord, solid, air));
                }
            }

            this.tickCounter = ticksPerStep - 1;
        }

        /** @return true when animation is complete */
        boolean tick() {
            tickCounter++;
            if (tickCounter < ticksPerStep) return false;
            tickCounter = 0;

            // 1. Move all active waves one step
            Iterator<SliceWave> it = activeWaves.iterator();
            while (it.hasNext()) {
                SliceWave wave = it.next();

                // Remove from current displaced positions
                for (BlockChange block : wave.solidChanges) {
                    restoreBlock(displacePos(block.pos, axis, wave.currentCoord));
                }

                // Advance one step
                wave.currentCoord += sign;

                // Reached final position → place permanently
                if (wave.currentCoord == wave.finalCoord) {
                    ParticleEffect pe = particle.toParticle();
                    for (BlockChange block : wave.solidChanges) {
                        applyBlockChange(world, block);
                        if (pe != null) {
                            world.spawnParticles(pe,
                                block.pos.getX() + 0.5, block.pos.getY() + 0.5, block.pos.getZ() + 0.5,
                                3, 0.25, 0.25, 0.25, 0.02);
                        }
                    }
                    for (BlockChange air : wave.airChanges) applyBlockChange(world, air);
                    it.remove();
                    continue;
                }

                // Place at new displaced positions
                for (BlockChange block : wave.solidChanges) {
                    BlockPos newPos = displacePos(block.pos, axis, wave.currentCoord);
                    int c = coordAlongAxis(newPos, axis);
                    if (c >= minBound && c <= maxBound) {
                        saveBlock(newPos);
                        world.setBlockState(newPos, block.targetState, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                    }
                }
            }

            // 2. Activate next wave
            if (nextWaveIdx < waves.size()) {
                SliceWave wave = waves.get(nextWaveIdx);
                nextWaveIdx++;

                if (wave.currentCoord == wave.finalCoord) {
                    // Entry face IS final position — place permanently
                    ParticleEffect pe = particle.toParticle();
                    for (BlockChange block : wave.solidChanges) {
                        applyBlockChange(world, block);
                        if (pe != null) {
                            world.spawnParticles(pe,
                                block.pos.getX() + 0.5, block.pos.getY() + 0.5, block.pos.getZ() + 0.5,
                                3, 0.25, 0.25, 0.25, 0.02);
                        }
                    }
                    for (BlockChange air : wave.airChanges) applyBlockChange(world, air);
                } else {
                    // Place at entry position (displaced)
                    for (BlockChange block : wave.solidChanges) {
                        BlockPos entryPos = displacePos(block.pos, axis, entryCoord);
                        saveBlock(entryPos);
                        world.setBlockState(entryPos, block.targetState, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                    }
                    activeWaves.add(wave);
                }
            }

            // 3. Check completion
            if (activeWaves.isEmpty() && nextWaveIdx >= waves.size()) {
                spawnEntities(world, targetEntities);
                return true;
            }
            return false;
        }

        void cleanup() {
            for (SliceWave wave : activeWaves) {
                for (BlockChange block : wave.solidChanges) {
                    restoreBlock(displacePos(block.pos, axis, wave.currentCoord));
                }
            }
            for (BlockPos pos : new ArrayList<>(savedBlocks.keySet())) {
                restoreBlock(pos);
            }
            activeWaves.clear();
        }

        private void saveBlock(BlockPos pos) {
            if (savedBlocks.containsKey(pos)) return;
            BlockState state = world.getBlockState(pos);
            NbtCompound beNbt = null;
            BlockEntity be = world.getBlockEntity(pos);
            if (be != null) {
                try { beNbt = be.createNbtWithId(world.getRegistryManager()); }
                catch (Exception e) { /* ignore */ }
            }
            savedBlocks.put(pos, new SavedBlock(state, beNbt));
        }

        private void restoreBlock(BlockPos pos) {
            SavedBlock saved = savedBlocks.remove(pos);
            if (saved == null) return;
            world.setBlockState(pos, saved.state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            if (saved.blockEntityNbt != null) {
                NbtCompound beNbt = saved.blockEntityNbt.copy();
                beNbt.putInt("x", pos.getX());
                beNbt.putInt("y", pos.getY());
                beNbt.putInt("z", pos.getZ());
                BlockEntity be = BlockEntity.createFromNbt(pos, saved.state, beNbt, world.getRegistryManager());
                if (be != null) {
                    world.getWorldChunk(pos).setBlockEntity(be);
                }
            }
        }
    }
}
