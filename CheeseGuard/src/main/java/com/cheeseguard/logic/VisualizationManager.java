package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Region;
import com.cheeseguard.data.RegionStorage;
import com.cheeseguard.data.TagConfig;
import com.cheeseguard.data.TagConfigStorage;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.joml.Vector3f;

import java.util.*;

/**
 * Draws region borders using colored dust particles.
 * Each region can have its own particle color.
 */
public class VisualizationManager {

    private static final double PARTICLE_SPACING = 1.0;
    private static final double FACE_SPACING = 2.0;
    private static final int MAX_ZONES = 5;

    private final Set<UUID> visualizing = new HashSet<>();
    private final RegionStorage storage;
    private boolean faceMode = true;

    // Predefined color palette
    private static final Map<String, Vector3f> COLORS = Map.ofEntries(
        Map.entry("red",    new Vector3f(1.0f, 0.2f, 0.2f)),
        Map.entry("green",  new Vector3f(0.2f, 1.0f, 0.2f)),
        Map.entry("blue",   new Vector3f(0.3f, 0.3f, 1.0f)),
        Map.entry("yellow", new Vector3f(1.0f, 1.0f, 0.2f)),
        Map.entry("purple", new Vector3f(0.8f, 0.2f, 1.0f)),
        Map.entry("cyan",   new Vector3f(0.2f, 1.0f, 1.0f)),
        Map.entry("white",  new Vector3f(1.0f, 1.0f, 1.0f)),
        Map.entry("orange", new Vector3f(1.0f, 0.6f, 0.1f)),
        Map.entry("pink",   new Vector3f(1.0f, 0.4f, 0.7f))
    );

    public static List<String> getAvailableColors() {
        return List.of("green", "red", "blue", "yellow", "purple", "cyan", "white", "orange", "pink");
    }

    public VisualizationManager(RegionStorage storage) {
        this.storage = storage;
    }

    public void toggle(UUID playerId) {
        if (!visualizing.remove(playerId)) visualizing.add(playerId);
    }

    public boolean isVisualizing(UUID playerId) {
        return visualizing.contains(playerId);
    }

    public boolean isFaceMode() { return faceMode; }
    public void setFaceMode(boolean faceMode) { this.faceMode = faceMode; }

    public void tick(ServerPlayerEntity player) {
        if (!isVisualizing(player.getUuid())) return;

        ServerWorld world = player.getServerWorld();
        String worldId = world.getRegistryKey().getValue().toString();
        double px = player.getX();
        double pz = player.getZ();

        // Collect nearby regions with their distances
        List<Map.Entry<Region, Double>> nearby = new ArrayList<>();
        for (Region region : storage.getRegions()) {
            if (!region.getWorld().equals(worldId)) continue;

            double dx = px - region.getCenterX();
            double dz = pz - region.getCenterZ();
            double distSq = dx * dx + dz * dz;
            if (distSq > 64 * 64) continue;

            nearby.add(Map.entry(region, distSq));
        }

        // Sort by distance (ascending) and limit
        nearby.sort(Comparator.comparingDouble(Map.Entry::getValue));
        int limit = Math.min(nearby.size(), MAX_ZONES);

        for (int i = 0; i < limit; i++) {
            Region r = nearby.get(i).getKey();
            if (faceMode) {
                drawFaces(player, r, world);
            } else {
                drawRegion(world, player, r);
            }
        }
    }

    private void drawRegion(ServerWorld world, ServerPlayerEntity player, Region region) {
        double x1 = region.getMinX(), y1 = region.getMinY(), z1 = region.getMinZ();
        double x2 = region.getMaxX() + 1, y2 = region.getMaxY() + 1, z2 = region.getMaxZ() + 1;

        Vector3f color = resolveColor(region);
        DustParticleEffect particle = new DustParticleEffect(color, 1.0f);

        // Bottom face edges
        drawEdge(world, player, x1, y1, z1, x2, y1, z1, particle);
        drawEdge(world, player, x1, y1, z2, x2, y1, z2, particle);
        drawEdge(world, player, x1, y1, z1, x1, y1, z2, particle);
        drawEdge(world, player, x2, y1, z1, x2, y1, z2, particle);

        // Top face edges
        drawEdge(world, player, x1, y2, z1, x2, y2, z1, particle);
        drawEdge(world, player, x1, y2, z2, x2, y2, z2, particle);
        drawEdge(world, player, x1, y2, z1, x1, y2, z2, particle);
        drawEdge(world, player, x2, y2, z1, x2, y2, z2, particle);

        // Vertical edges
        drawEdge(world, player, x1, y1, z1, x1, y2, z1, particle);
        drawEdge(world, player, x2, y1, z1, x2, y2, z1, particle);
        drawEdge(world, player, x1, y1, z2, x1, y2, z2, particle);
        drawEdge(world, player, x2, y1, z2, x2, y2, z2, particle);
    }

    private void drawEdge(ServerWorld world, ServerPlayerEntity player,
                           double x1, double y1, double z1,
                           double x2, double y2, double z2,
                           DustParticleEffect particle) {
        double len = Math.sqrt((x2-x1)*(x2-x1) + (y2-y1)*(y2-y1) + (z2-z1)*(z2-z1));
        int steps = Math.max(1, (int)(len / PARTICLE_SPACING));

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = x1 + (x2 - x1) * t;
            double py = y1 + (y2 - y1) * t;
            double pz = z1 + (z2 - z1) * t;

            world.spawnParticles(player, particle, true, px, py, pz, 1, 0, 0, 0, 0);
        }
    }

    // ── Color resolution ─────────────────────────────────────────────────

    private Vector3f resolveColor(Region r) {
        // 1. Check tag config colors (first tag with a configured color wins)
        TagConfigStorage tagStorage = CheeseGuardMod.getInstance().getTagConfigStorage();
        if (tagStorage != null) {
            for (String tag : r.getTags()) {
                TagConfig tc = tagStorage.get(tag);
                if (tc != null && tc.getColor() != null && !tc.getColor().isEmpty()) {
                    return hexToVector3f(tc.getColor());
                }
            }
        }
        // 2. Region particle color
        if (r.getParticleColor() != null && COLORS.containsKey(r.getParticleColor())) {
            return COLORS.get(r.getParticleColor());
        }
        // 3. Default green
        return COLORS.get("green");
    }

    private static Vector3f hexToVector3f(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) return COLORS.get("green");
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            return new Vector3f(r / 255f, g / 255f, b / 255f);
        } catch (NumberFormatException e) {
            return COLORS.get("green");
        }
    }

    // ── Face rendering ───────────────────────────────────────────────────

    private void drawFaces(ServerPlayerEntity player, Region r, ServerWorld world) {
        Vector3f color = resolveColor(r);
        DustParticleEffect particle = new DustParticleEffect(color, 1.0f);

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        // Zone bounds (add 1 for block edge)
        double x0 = r.getMinX();
        double x1 = r.getMaxX() + 1;
        double y0 = r.getMinY();
        double y1 = r.getMaxY() + 1;
        double z0 = r.getMinZ();
        double z1 = r.getMaxZ() + 1;

        // Calculate distance to each of 6 faces
        double[][] faces = {
            {Math.abs(px - x1), 0},  // +X face
            {Math.abs(px - x0), 1},  // -X face
            {Math.abs(py - y1), 2},  // +Y face (top)
            {Math.abs(py - y0), 3},  // -Y face (bottom)
            {Math.abs(pz - z1), 4},  // +Z face
            {Math.abs(pz - z0), 5},  // -Z face
        };

        // Sort by distance, take 2 nearest
        java.util.Arrays.sort(faces, (a, b) -> Double.compare(a[0], b[0]));

        for (int f = 0; f < 2; f++) {
            int faceId = (int) faces[f][1];
            drawFace(player, world, particle, faceId, x0, y0, z0, x1, y1, z1);
        }
    }

    private void drawFace(ServerPlayerEntity player, ServerWorld world, DustParticleEffect particle,
                           int faceId, double x0, double y0, double z0, double x1, double y1, double z1) {
        switch (faceId) {
            case 0 -> drawPlane(player, world, particle, x1, x1, y0, y1, z0, z1);  // +X
            case 1 -> drawPlane(player, world, particle, x0, x0, y0, y1, z0, z1);  // -X
            case 2 -> drawPlane(player, world, particle, x0, x1, y1, y1, z0, z1);  // +Y
            case 3 -> drawPlane(player, world, particle, x0, x1, y0, y0, z0, z1);  // -Y
            case 4 -> drawPlane(player, world, particle, x0, x1, y0, y1, z1, z1);  // +Z
            case 5 -> drawPlane(player, world, particle, x0, x1, y0, y1, z0, z0);  // -Z
        }
    }

    private void drawPlane(ServerPlayerEntity player, ServerWorld world, DustParticleEffect particle,
                            double xa, double xb, double ya, double yb, double za, double zb) {
        boolean varyX = xa != xb;
        boolean varyY = ya != yb;
        boolean varyZ = za != zb;

        if (varyX && varyZ) {
            // Y is fixed (top/bottom face)
            for (double x = xa; x <= xb; x += FACE_SPACING) {
                for (double z = za; z <= zb; z += FACE_SPACING) {
                    world.spawnParticles(player, particle, true, x, ya, z, 1, 0, 0, 0, 0);
                }
            }
        } else if (varyY && varyZ) {
            // X is fixed (+X/-X face)
            for (double y = ya; y <= yb; y += FACE_SPACING) {
                for (double z = za; z <= zb; z += FACE_SPACING) {
                    world.spawnParticles(player, particle, true, xa, y, z, 1, 0, 0, 0, 0);
                }
            }
        } else if (varyX && varyY) {
            // Z is fixed (+Z/-Z face)
            for (double x = xa; x <= xb; x += FACE_SPACING) {
                for (double y = ya; y <= yb; y += FACE_SPACING) {
                    world.spawnParticles(player, particle, true, x, y, za, 1, 0, 0, 0, 0);
                }
            }
        }
    }
}
