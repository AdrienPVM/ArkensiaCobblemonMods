package com.cheeseguard.logic;

import com.cheeseguard.data.Region;
import com.cheeseguard.data.RegionStorage;
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
    private final Set<UUID> visualizing = new HashSet<>();
    private final RegionStorage storage;

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

    public void tick(ServerPlayerEntity player) {
        if (!isVisualizing(player.getUuid())) return;

        ServerWorld world = player.getServerWorld();
        String worldId = world.getRegistryKey().getValue().toString();

        for (Region region : storage.getRegions()) {
            if (!region.getWorld().equals(worldId)) continue;

            double dx = player.getX() - region.getCenterX();
            double dz = player.getZ() - region.getCenterZ();
            if (dx * dx + dz * dz > 64 * 64) continue;

            drawRegion(world, player, region);
        }
    }

    private void drawRegion(ServerWorld world, ServerPlayerEntity player, Region region) {
        double x1 = region.getMinX(), y1 = region.getMinY(), z1 = region.getMinZ();
        double x2 = region.getMaxX() + 1, y2 = region.getMaxY() + 1, z2 = region.getMaxZ() + 1;

        Vector3f color = COLORS.getOrDefault(region.getParticleColor(), COLORS.get("green"));
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
}
