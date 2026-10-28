package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Region;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Saves and restores block states in a region.
 * Used for mini-games like Spleef (restore floor), Speed Build (clear area), etc.
 */
public class ZoneSnapshot {

    private final String snapshotId;
    private final String regionId;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private String worldId;

    public ZoneSnapshot(String snapshotId, String regionId) {
        this.snapshotId = snapshotId;
        this.regionId = regionId;
    }

    /**
     * Capture all blocks in the given region.
     * @return number of blocks saved
     */
    public int capture(MinecraftServer server) {
        Region region = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(regionId);
        if (region == null) return 0;

        this.worldId = region.getWorld();
        this.minX = region.getMinX(); this.minY = region.getMinY(); this.minZ = region.getMinZ();
        this.maxX = region.getMaxX(); this.maxY = region.getMaxY(); this.maxZ = region.getMaxZ();

        ServerWorld world = getWorld(server);
        if (world == null) return 0;

        blocks.clear();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!state.isAir()) {
                        blocks.put(pos, state);
                    }
                }
            }
        }
        CheeseGuardMod.LOGGER.info("[CG] Snapshot '{}': captured {} blocks from region '{}'", snapshotId, blocks.size(), regionId);
        return blocks.size();
    }

    /**
     * Restore all saved blocks to the world.
     * @return number of blocks restored
     */
    public int restore(MinecraftServer server) {
        ServerWorld world = getWorld(server);
        if (world == null || blocks.isEmpty()) return 0;

        int count = 0;
        // First pass: clear everything in the region to air
        for (int x = minX; x <= maxX; x++) {
            for (int y = maxY; y >= minY; y--) { // Top to bottom for proper physics
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState current = world.getBlockState(pos);
                    if (!current.isAir()) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                    }
                }
            }
        }
        // Second pass: place saved blocks
        for (var entry : blocks.entrySet()) {
            world.setBlockState(entry.getKey(), entry.getValue(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            count++;
        }
        CheeseGuardMod.LOGGER.info("[CG] Snapshot '{}': restored {} blocks", snapshotId, count);
        return count;
    }

    /**
     * Fill the region with a specific block (for arena setup).
     */
    public int fill(MinecraftServer server, BlockState fillState) {
        ServerWorld world = getWorld(server);
        if (world == null) return 0;
        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    world.setBlockState(new BlockPos(x, y, z), fillState, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Clear all blocks in the region to air.
     */
    public int clear(MinecraftServer server) {
        return fill(server, Blocks.AIR.getDefaultState());
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public void saveToDisk(MinecraftServer server) {
        try {
            NbtCompound root = new NbtCompound();
            root.putString("regionId", regionId);
            root.putString("worldId", worldId != null ? worldId : "");
            root.putInt("minX", minX); root.putInt("minY", minY); root.putInt("minZ", minZ);
            root.putInt("maxX", maxX); root.putInt("maxY", maxY); root.putInt("maxZ", maxZ);

            NbtList blockList = new NbtList();
            for (var entry : blocks.entrySet()) {
                NbtCompound tag = new NbtCompound();
                tag.putInt("x", entry.getKey().getX());
                tag.putInt("y", entry.getKey().getY());
                tag.putInt("z", entry.getKey().getZ());
                tag.putString("block", Registries.BLOCK.getId(entry.getValue().getBlock()).toString());
                blockList.add(tag);
            }
            root.put("blocks", blockList);

            Path dir = server.getRunDirectory().resolve("config").resolve("cheeseguard_snapshots");
            Files.createDirectories(dir);
            NbtIo.writeCompressed(root, dir.resolve(snapshotId + ".nbt"));
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to save snapshot '{}': {}", snapshotId, e.getMessage());
        }
    }

    public static ZoneSnapshot loadFromDisk(MinecraftServer server, String snapshotId) {
        try {
            Path file = server.getRunDirectory().resolve("config").resolve("cheeseguard_snapshots").resolve(snapshotId + ".nbt");
            if (!Files.exists(file)) return null;
            NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            if (root == null) return null;

            ZoneSnapshot snap = new ZoneSnapshot(snapshotId, root.getString("regionId"));
            snap.worldId = root.getString("worldId");
            snap.minX = root.getInt("minX"); snap.minY = root.getInt("minY"); snap.minZ = root.getInt("minZ");
            snap.maxX = root.getInt("maxX"); snap.maxY = root.getInt("maxY"); snap.maxZ = root.getInt("maxZ");

            NbtList blockList = root.getList("blocks", 10);
            for (int i = 0; i < blockList.size(); i++) {
                NbtCompound tag = blockList.getCompound(i);
                BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                Identifier blockId = Identifier.tryParse(tag.getString("block"));
                if (blockId != null) {
                    Block block = Registries.BLOCK.get(blockId);
                    snap.blocks.put(pos, block.getDefaultState());
                }
            }
            return snap;
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to load snapshot '{}': {}", snapshotId, e.getMessage());
            return null;
        }
    }

    private ServerWorld getWorld(MinecraftServer server) {
        if (worldId == null || worldId.isBlank()) return server.getOverworld();
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldId)) return w;
        }
        return server.getOverworld();
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getSnapshotId() { return snapshotId; }
    public String getRegionId() { return regionId; }
    public int getBlockCount() { return blocks.size(); }
    public boolean isEmpty() { return blocks.isEmpty(); }
}
