package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.message.ChatVisibility;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Arm;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A "fake" offline-prisoner stand-in. Built on a real ServerPlayerEntity instance
 * but never registered in the PlayerManager — only used as a packet payload source.
 *
 * <p>The entity is shown to viewers in the prison world via raw packets:
 * PlayerList ADD (so the client receives the textures and can render the model),
 * EntitySpawn (synthetic entity id), and EntitySetHeadYaw (since we bypass the
 * server's EntityTrackerEntry).</p>
 *
 * <p>Despawn sends EntitiesDestroy + PlayerRemove to all viewers we tracked.</p>
 */
public class PrisonDoppelganger {

    private final MinecraftServer server;
    private final ServerWorld world;
    private final ServerPlayerEntity fake;
    private final UUID realPlayerUuid;
    private final int entityId;
    private final double x, y, z;
    private final float yaw, pitch;

    /** Viewers (real player UUIDs) currently seeing this doppel. */
    private final Set<UUID> shownTo = new HashSet<>();

    public PrisonDoppelganger(MinecraftServer server, ServerWorld world, GameProfile sourceProfile,
                               double x, double y, double z, float yaw, float pitch) {
        this.server = server;
        this.world = world;
        this.realPlayerUuid = sourceProfile.getId();
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch;

        // Clone the profile (same UUID + name + textures Property) so the client renders the
        // proper skin without re-fetching. We re-use the source UUID; the fake is never added
        // to PlayerManager, so it cannot conflict with a re-connecting player.
        GameProfile profile = new GameProfile(sourceProfile.getId(), sourceProfile.getName());
        profile.getProperties().putAll(sourceProfile.getProperties());

        // All skin layer bits enabled (cape, jacket, sleeves, pants, hat).
        SyncedClientOptions opts = new SyncedClientOptions(
            "en_us", 8, ChatVisibility.FULL, true, 0x7F, Arm.RIGHT, true, true);

        this.fake = new ServerPlayerEntity(server, world, profile, opts);
        this.fake.setPosition(x, y, z);
        this.fake.setYaw(yaw);
        this.fake.setHeadYaw(yaw);
        this.fake.setBodyYaw(yaw);
        this.fake.setPitch(pitch);
        this.entityId = this.fake.getId();
    }

    public UUID getRealPlayerUuid() { return realPlayerUuid; }
    public ServerWorld getWorld()   { return world; }

    /** Send the spawn packets to a single viewer (idempotent per viewer). */
    public void showTo(ServerPlayerEntity viewer) {
        if (viewer == null || viewer.networkHandler == null) return;
        if (!viewer.getServerWorld().getRegistryKey().equals(world.getRegistryKey())) return;
        if (!shownTo.add(viewer.getUuid())) return;
        try {
            viewer.networkHandler.sendPacket(
                new PlayerListS2CPacket(PlayerListS2CPacket.Action.ADD_PLAYER, fake));
            viewer.networkHandler.sendPacket(new EntitySpawnS2CPacket(
                entityId, fake.getUuid(), x, y, z, pitch, yaw,
                net.minecraft.entity.EntityType.PLAYER, 0, Vec3d.ZERO, yaw));
            viewer.networkHandler.sendPacket(new EntitySetHeadYawS2CPacket(
                fake, (byte) (yaw * 256.0f / 360.0f)));
        } catch (Throwable t) {
            CheeseGuardMod.LOGGER.warn("[CG/Prison] doppel showTo failed for {}: {}",
                viewer.getName().getString(), t.getMessage());
            shownTo.remove(viewer.getUuid());
        }
    }

    /** Remove the doppel from a single viewer. */
    public void hideFrom(ServerPlayerEntity viewer) {
        if (viewer == null || viewer.networkHandler == null) return;
        if (!shownTo.remove(viewer.getUuid())) return;
        try {
            viewer.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(entityId));
            // Only remove the tablist entry if the real player is currently OFFLINE.
            // If the real player is online (i.e. the doppel is being hidden because they
            // just reconnected), the tablist entry now belongs to them and was added by
            // PlayerManager.onPlayerConnect — sending PlayerRemove would erase that entry
            // for this viewer and the real player would silently vanish from their tablist.
            if (server.getPlayerManager().getPlayer(realPlayerUuid) == null) {
                viewer.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(fake.getUuid())));
            }
        } catch (Throwable ignored) {}
    }

    /** Show to every player currently in the prison world. Returns the number of viewers shown to. */
    public int showToWorld() {
        int n = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            // Use registry-key equality (== may fail across world reloads though we're on the server thread now).
            if (p.getServerWorld().getRegistryKey().equals(world.getRegistryKey())) {
                int before = shownTo.size();
                showTo(p);
                if (shownTo.size() > before) n++;
            }
        }
        return n;
    }

    /** Hide from every viewer. Called on real player reconnect. */
    public void hideFromAll() {
        // Copy to avoid CME while iterating.
        for (UUID viewerUuid : new HashSet<>(shownTo)) {
            ServerPlayerEntity v = server.getPlayerManager().getPlayer(viewerUuid);
            if (v != null) hideFrom(v);
            else shownTo.remove(viewerUuid);
        }
    }
}
