package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.RaceTrackConfig;
import com.cheeseguard.data.RaceTrackConfig.MarkerRegion;
import com.cheeseguard.data.Region;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Scans a CheeseGuard zone for block markers (colored wool, etc.),
 * groups adjacent blocks into checkpoint/powerup/start regions
 * using flood-fill (BFS with 6-directional 3D adjacency).
 */
public class RaceTrackScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");

    /**
     * Result of a scan operation.
     */
    public static class ScanResult {
        public final List<MarkerRegion> checkpoints;
        public final List<MarkerRegion> powerups;
        public final MarkerRegion startLine;
        public final int totalBlocksScanned;
        public final int markerBlocksFound;
        public final long durationMs;
        public final JsonObject zoneBounds;

        public ScanResult(List<MarkerRegion> checkpoints, List<MarkerRegion> powerups,
                          MarkerRegion startLine, int totalBlocksScanned, int markerBlocksFound,
                          long durationMs, JsonObject zoneBounds) {
            this.checkpoints = checkpoints;
            this.powerups = powerups;
            this.startLine = startLine;
            this.totalBlocksScanned = totalBlocksScanned;
            this.markerBlocksFound = markerBlocksFound;
            this.durationMs = durationMs;
            this.zoneBounds = zoneBounds;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            JsonArray cpArr = new JsonArray();
            for (MarkerRegion cp : checkpoints) cpArr.add(cp.toJson());
            obj.add("checkpoints", cpArr);

            JsonArray puArr = new JsonArray();
            for (MarkerRegion pu : powerups) puArr.add(pu.toJson());
            obj.add("powerups", puArr);

            if (startLine != null) obj.add("startLine", startLine.toJson());

            obj.addProperty("totalBlocksScanned", totalBlocksScanned);
            obj.addProperty("markerBlocksFound", markerBlocksFound);
            obj.addProperty("durationMs", durationMs);
            obj.add("zoneBounds", zoneBounds);

            return obj;
        }
    }

    /**
     * Scan a zone for marker blocks and group them into regions.
     * MUST be called on the server thread (accesses world.getBlockState).
     *
     * @param server        the Minecraft server
     * @param zoneId        the CheeseGuard zone ID to scan
     * @param blockMappings block ID → marker type ("checkpoint", "start", or powerup key)
     * @return scan result with grouped marker regions
     */
    public static ScanResult scan(MinecraftServer server, String zoneId, Map<String, String> blockMappings) {
        long startTime = System.currentTimeMillis();

        // 1. Resolve zone
        Region zone = CheeseGuardMod.INSTANCE.getRegionManager().getStorage().getRegion(zoneId);
        if (zone == null) {
            throw new IllegalArgumentException("Zone not found: " + zoneId);
        }

        // 2. Resolve world
        ServerWorld world = resolveWorld(server, zone.getWorld());
        if (world == null) {
            throw new IllegalArgumentException("World not loaded: " + zone.getWorld());
        }

        // 3. Pre-resolve block mappings to Block objects for fast reference comparison
        Map<Block, String> blockToMarker = new HashMap<>();
        for (Map.Entry<String, String> entry : blockMappings.entrySet()) {
            Identifier blockId = Identifier.of(entry.getKey());
            Block block = Registries.BLOCK.get(blockId);
            if (block != null && block != Registries.BLOCK.get(Identifier.of("minecraft", "air"))) {
                blockToMarker.put(block, entry.getValue());
            } else {
                LOGGER.warn("[CG] Race scan: unknown block in mappings: {}", entry.getKey());
            }
        }

        // 4. Scan zone bounds for matching blocks
        int minX = zone.getMinX(), minY = zone.getMinY(), minZ = zone.getMinZ();
        int maxX = zone.getMaxX(), maxY = zone.getMaxY(), maxZ = zone.getMaxZ();

        // Safety: limit scan volume (50M blocks max)
        long volume = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > 50_000_000L) {
            throw new IllegalArgumentException("Zone too large for scanning: " + volume +
                " blocks (max 50M). Reduce zone bounds.");
        }

        Map<BlockPos, String> foundBlocks = new LinkedHashMap<>();
        int totalScanned = 0;
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    totalScanned++;
                    mutable.set(x, y, z);
                    BlockState state = world.getBlockState(mutable);

                    // Skip air quickly
                    if (state.isAir()) continue;

                    String markerType = blockToMarker.get(state.getBlock());
                    if (markerType != null) {
                        foundBlocks.put(new BlockPos(x, y, z), markerType);
                    }
                }
            }
        }

        // 5. Group adjacent blocks of same marker type using BFS flood fill
        Set<BlockPos> visited = new HashSet<>();
        List<MarkerRegion> allGroups = new ArrayList<>();

        for (Map.Entry<BlockPos, String> entry : foundBlocks.entrySet()) {
            BlockPos pos = entry.getKey();
            if (visited.contains(pos)) continue;

            String markerType = entry.getValue();
            List<int[]> groupBlocks = new ArrayList<>();
            Queue<BlockPos> queue = new LinkedList<>();
            queue.add(pos);
            visited.add(pos);

            int gMinX = pos.getX(), gMinY = pos.getY(), gMinZ = pos.getZ();
            int gMaxX = gMinX, gMaxY = gMinY, gMaxZ = gMinZ;

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                int cx = current.getX(), cy = current.getY(), cz = current.getZ();
                groupBlocks.add(new int[]{cx, cy, cz});

                // Update bounding box
                gMinX = Math.min(gMinX, cx);
                gMinY = Math.min(gMinY, cy);
                gMinZ = Math.min(gMinZ, cz);
                gMaxX = Math.max(gMaxX, cx);
                gMaxY = Math.max(gMaxY, cy);
                gMaxZ = Math.max(gMaxZ, cz);

                // Check 6 neighbors (3D adjacency)
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.offset(dir);
                    if (!visited.contains(neighbor)
                            && foundBlocks.containsKey(neighbor)
                            && foundBlocks.get(neighbor).equals(markerType)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }

            // Calculate center
            double centerX = (gMinX + gMaxX) / 2.0 + 0.5;
            double centerY = (gMinY + gMaxY) / 2.0 + 0.5;
            double centerZ = (gMinZ + gMaxZ) / 2.0 + 0.5;

            MarkerRegion region = new MarkerRegion(
                markerType,
                groupBlocks,
                new int[]{gMinX, gMinY, gMinZ},
                new int[]{gMaxX, gMaxY, gMaxZ},
                new double[]{centerX, centerY, centerZ}
            );

            allGroups.add(region);
        }

        // 6. Separate into categories
        List<MarkerRegion> checkpoints = new ArrayList<>();
        List<MarkerRegion> powerups = new ArrayList<>();
        MarkerRegion startLine = null;

        for (MarkerRegion group : allGroups) {
            switch (group.getMarkerType()) {
                case "checkpoint" -> checkpoints.add(group);
                case "start" -> {
                    if (startLine == null) {
                        startLine = group;
                    } else {
                        // Multiple start markers — merge (shouldn't happen but handle gracefully)
                        LOGGER.warn("[CG] Race scan: multiple start line groups found, using first one");
                    }
                }
                default -> powerups.add(group);  // Any non-checkpoint, non-start is a powerup type
            }
        }

        // Sort checkpoints by X then Z position (gives a reasonable default order)
        checkpoints.sort((a, b) -> {
            int cmp = Double.compare(a.getCenter()[0], b.getCenter()[0]);
            if (cmp != 0) return cmp;
            return Double.compare(a.getCenter()[2], b.getCenter()[2]);
        });

        long duration = System.currentTimeMillis() - startTime;
        LOGGER.info("[CG] Race scan complete: zone='{}' — {} checkpoints, {} powerups, startLine={}, " +
                "scanned {} blocks, found {} markers in {}ms",
            zoneId, checkpoints.size(), powerups.size(), startLine != null,
            totalScanned, foundBlocks.size(), duration);

        // Zone bounds for 2D map rendering
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", minX);
        bounds.addProperty("minY", minY);
        bounds.addProperty("minZ", minZ);
        bounds.addProperty("maxX", maxX);
        bounds.addProperty("maxY", maxY);
        bounds.addProperty("maxZ", maxZ);
        bounds.addProperty("world", zone.getWorld());

        return new ScanResult(checkpoints, powerups, startLine,
            totalScanned, foundBlocks.size(), duration, bounds);
    }

    /**
     * Apply scan results to a RaceTrackConfig.
     * Preserves existing checkpoint order for checkpoints that match by proximity.
     */
    public static void applyToConfig(RaceTrackConfig config, ScanResult result) {
        // Preserve order of existing checkpoints that match new scan results by position
        List<RaceTrackConfig.MarkerRegion> oldCps = config.getCheckpoints();
        if (oldCps != null && !oldCps.isEmpty() && result.checkpoints != null) {
            for (RaceTrackConfig.MarkerRegion newCp : result.checkpoints) {
                double[] nc = newCp.getCenter();
                if (nc == null) continue;
                for (RaceTrackConfig.MarkerRegion oldCp : oldCps) {
                    if (oldCp.getOrder() < 0) continue;
                    double[] oc = oldCp.getCenter();
                    if (oc == null) continue;
                    double dx = nc[0] - oc[0], dz = nc[2] - oc[2];
                    if (dx * dx + dz * dz < 25) { // within 5 blocks
                        newCp.setOrder(oldCp.getOrder());
                        break;
                    }
                }
            }
        }

        config.setCheckpoints(result.checkpoints);
        config.setPowerups(result.powerups);
        config.setStartLine(result.startLine);
        config.setLastScanTimestamp(System.currentTimeMillis());

        // Store zone bounds for 2D map rendering
        JsonObject zb = result.zoneBounds;
        if (zb != null) {
            config.setZoneBoundsMin(new int[]{
                zb.get("minX").getAsInt(), zb.get("minY").getAsInt(), zb.get("minZ").getAsInt()
            });
            config.setZoneBoundsMax(new int[]{
                zb.get("maxX").getAsInt(), zb.get("maxY").getAsInt(), zb.get("maxZ").getAsInt()
            });
            config.setZoneWorld(zb.get("world").getAsString());
        }
    }

    /**
     * Resolve a world ID string to a ServerWorld instance.
     */
    private static ServerWorld resolveWorld(MinecraftServer server, String worldId) {
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldId)) {
                return w;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Heightmap — top-down block color view for 2D map background
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Result of a heightmap scan — a grid of RGB colors for the zone viewed from above.
     */
    public static class HeightmapResult {
        public final int width;
        public final int depth;
        public final int minX;
        public final int minZ;
        private final int[] colors;

        public HeightmapResult(int width, int depth, int minX, int minZ, int[] colors) {
            this.width = width;
            this.depth = depth;
            this.minX = minX;
            this.minZ = minZ;
            this.colors = colors;
        }

        public String toPngBase64() throws IOException {
            BufferedImage img = new BufferedImage(width, depth, BufferedImage.TYPE_INT_RGB);
            for (int zi = 0; zi < depth; zi++) {
                for (int xi = 0; xi < width; xi++) {
                    img.setRGB(xi, zi, colors[zi * width + xi]);
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }

        public JsonObject toJson() throws IOException {
            JsonObject obj = new JsonObject();
            obj.addProperty("width", width);
            obj.addProperty("height", depth);
            obj.addProperty("minX", minX);
            obj.addProperty("minZ", minZ);
            obj.addProperty("image", "data:image/png;base64," + toPngBase64());
            return obj;
        }
    }

    /**
     * Scan a zone from above and return the top-down block color grid.
     * Uses Minecraft's MapColor for accurate block colors + neighbor-based shading.
     * MUST be called on the server thread.
     *
     * @param server the Minecraft server
     * @param zoneId the CheeseGuard zone ID to scan
     * @return heightmap result with PNG-ready color data
     */
    public static HeightmapResult scanHeightmap(MinecraftServer server, String zoneId) {
        long startTime = System.currentTimeMillis();

        Region zone = CheeseGuardMod.INSTANCE.getRegionManager().getStorage().getRegion(zoneId);
        if (zone == null) throw new IllegalArgumentException("Zone not found: " + zoneId);

        ServerWorld world = resolveWorld(server, zone.getWorld());
        if (world == null) throw new IllegalArgumentException("World not loaded: " + zone.getWorld());

        int minX = zone.getMinX(), maxX = zone.getMaxX();
        int minY = zone.getMinY(), maxY = zone.getMaxY();
        int minZ = zone.getMinZ(), maxZ = zone.getMaxZ();

        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        long area = (long) width * depth;
        if (area > 500_000L) {
            throw new IllegalArgumentException("Zone area too large for heightmap: " + area + " (max 500K)");
        }

        int bgColor = 0x0c0e14; // match dashboard background
        int[] heights = new int[width * depth];
        int[] colors = new int[width * depth];
        Arrays.fill(colors, bgColor);
        Arrays.fill(heights, minY);

        BlockPos.Mutable mutable = new BlockPos.Mutable();

        // Pass 1: find top visible block for each (x,z) column
        for (int zi = 0; zi < depth; zi++) {
            int z = minZ + zi;
            for (int xi = 0; xi < width; xi++) {
                int x = minX + xi;
                int idx = zi * width + xi;

                // Use world heightmap as optimization — skip air above surface
                int startY = Math.min(maxY, world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1);
                if (startY < minY) continue;

                for (int y = startY; y >= minY; y--) {
                    mutable.set(x, y, z);
                    BlockState state = world.getBlockState(mutable);
                    if (state.isAir()) continue;

                    MapColor mc = state.getMapColor(world, mutable);
                    if (mc == null || mc == MapColor.CLEAR) continue;

                    heights[idx] = y;
                    colors[idx] = mc.color;
                    break;
                }
            }
        }

        // Pass 2: neighbor-based shading (compare height to northern neighbor for 3D relief)
        for (int zi = 0; zi < depth; zi++) {
            for (int xi = 0; xi < width; xi++) {
                int idx = zi * width + xi;
                int rgb = colors[idx];
                if (rgb == bgColor) continue;

                float shade = 1.0f;
                if (zi > 0) {
                    int northHeight = heights[(zi - 1) * width + xi];
                    int diff = heights[idx] - northHeight;
                    if (diff > 0) shade = 1.12f;       // higher → brighter
                    else if (diff < 0) shade = 0.85f;  // lower → darker
                }

                int r = Math.min(255, (int) ((rgb >> 16 & 0xFF) * shade));
                int g = Math.min(255, (int) ((rgb >> 8 & 0xFF) * shade));
                int b = Math.min(255, (int) ((rgb & 0xFF) * shade));
                colors[idx] = (r << 16) | (g << 8) | b;
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        LOGGER.info("[CG] Heightmap scan: zone='{}' — {}x{} pixels in {}ms", zoneId, width, depth, duration);

        return new HeightmapResult(width, depth, minX, minZ, colors);
    }
}
