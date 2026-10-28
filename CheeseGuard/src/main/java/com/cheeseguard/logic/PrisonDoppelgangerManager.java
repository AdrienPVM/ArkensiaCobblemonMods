package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.PrisonEntry;
import com.cheeseguard.data.Region;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks one PrisonDoppelganger per offline imprisoned player, and syncs
 * visibility when viewers enter / leave the prison world.
 */
public class PrisonDoppelgangerManager {

    /** realPlayerUuid -> doppel */
    private final Map<UUID, PrisonDoppelganger> doppels = new HashMap<>();

    public boolean hasDoppel(UUID realPlayerUuid) {
        return doppels.containsKey(realPlayerUuid);
    }

    /**
     * Spawn a doppel for the player who just disconnected. No-op if not imprisoned,
     * if no prison zone is configured, or if a doppel already exists for them.
     */
    public void spawnFor(MinecraftServer server, GameProfile profile) {
        if (server == null || profile == null) return;
        UUID uuid = profile.getId();
        if (doppels.containsKey(uuid)) return;

        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null || !pm.isImprisoned(uuid.toString())) return;

        String zoneId = pm.getPrisonZoneId();
        if (zoneId == null) return;
        Region zone = CheeseGuardMod.getInstance().getStorage().getRegion(zoneId);
        if (zone == null) return;

        ServerWorld world = null;
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(zone.getWorld())) {
                world = w; break;
            }
        }
        if (world == null) return;

        // Place at zone center, on a safe-ish Y (top air-with-ground search inside the column).
        double x = zone.getCenterX() + 0.5;
        double z = zone.getCenterZ() + 0.5;
        double y = findGroundY(world, zone);
        // Texture diagnostic: an empty properties map means the doppel will render as Steve.
        int texProps = profile.getProperties().get("textures").size();
        try {
            PrisonDoppelganger d = new PrisonDoppelganger(server, world, profile, x, y, z, 0f, 0f);
            doppels.put(uuid, d);
            int viewers = d.showToWorld();
            CheeseGuardMod.LOGGER.info(
                "[CG/Prison] Spawned doppelganger for offline prisoner {} in {} at ({},{},{}) — textures={}, viewers shown={}",
                profile.getName(), zone.getWorld(), (int) x, (int) y, (int) z, texProps, viewers);
        } catch (Throwable t) {
            CheeseGuardMod.LOGGER.error("[CG/Prison] Failed to spawn doppelganger for {}: {}",
                profile.getName(), t.getMessage(), t);
        }
    }

    /**
     * Remove the doppel for a player who just reconnected. No-op if no doppel exists.
     */
    public void removeFor(UUID realPlayerUuid) {
        PrisonDoppelganger d = doppels.remove(realPlayerUuid);
        if (d != null) d.hideFromAll();
    }

    /** Drop everything (server stop, etc.). */
    public void clear() {
        for (PrisonDoppelganger d : doppels.values()) d.hideFromAll();
        doppels.clear();
    }

    /** Sync visibility when a viewer changed worlds (or just joined). */
    public void onViewerWorldChange(ServerPlayerEntity viewer) {
        if (viewer == null) return;
        ServerWorld viewerWorld = viewer.getServerWorld();
        for (PrisonDoppelganger d : doppels.values()) {
            if (d.getWorld() == viewerWorld) d.showTo(viewer);
            else d.hideFrom(viewer);
        }
    }

    /** Re-establish doppels at boot for currently-offline prisoners (lazy on viewer entry). */
    public void onServerStarted(MinecraftServer server) {
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null) return;
        for (PrisonEntry e : pm.getActive()) {
            UUID uuid;
            try { uuid = UUID.fromString(e.getPlayerUuid()); }
            catch (Exception ex) { continue; }
            // Skip if currently online — they're confined the normal way.
            if (server.getPlayerManager().getPlayer(uuid) != null) continue;
            // Resolve a profile from the user cache (with textures missing — client renders Steve).
            // Better: skip; the profile we cached on disconnect is what we want. At cold boot
            // textures are only available after at least one Mojang fetch via UserCache.
            if (server.getUserCache() == null) continue;
            var profOpt = server.getUserCache().getByUuid(uuid);
            if (profOpt.isEmpty()) continue;
            spawnFor(server, profOpt.get());
        }
    }

    private static double findGroundY(ServerWorld world, Region zone) {
        int top = Math.min(zone.getMaxY(), world.getTopY() - 1);
        int cx = zone.getCenterX();
        int cz = zone.getCenterZ();
        for (int yy = top; yy >= zone.getMinY(); yy--) {
            net.minecraft.util.math.BlockPos bp = new net.minecraft.util.math.BlockPos(cx, yy, cz);
            if (!world.getBlockState(bp).isAir()
                    && world.getBlockState(bp.up()).isAir()
                    && world.getBlockState(bp.up(2)).isAir()) {
                return yy + 1;
            }
        }
        return zone.getCenterY();
    }
}
