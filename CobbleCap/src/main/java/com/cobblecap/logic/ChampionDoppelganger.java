package com.cobblecap.logic;

import com.cobblecap.data.StatueStyle;
import com.cobblecap.network.StatueStyleSyncPayload;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.network.message.ChatVisibility;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Arm;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Full-body player-skin "statue" rendered to viewers via raw packets.
 * Synthetic UUID prevents tablist collision with the real (possibly online)
 * champion. The textures Property is copied from the real profile so the
 * client downloads & renders the correct skin without authentication.
 *
 * After EntitySpawnS2CPacket we send EntityTrackerUpdateS2CPacket with all
 * tracker entries — without this, viewers see only the base skin layer
 * because PLAYER_MODEL_PARTS (hat/jacket/sleeve/pants/cape bitmask) is not
 * carried by the spawn packet itself.
 */
public class ChampionDoppelganger {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    /** XOR masks used to derive the synthetic UUID from a real player UUID. */
    private static final long SYNTH_MASK_HIGH = 0xC0BB1ECA7575A7E5L;
    private static final long SYNTH_MASK_LOW  = 0xC0B1B1ED065747AEL;

    private final MinecraftServer server;
    private final ServerWorld world;
    private final ServerPlayerEntity fake;
    private final UUID syntheticUuid;
    /** True when {@link #syntheticUuid} is actually the real champion UUID (offline path).
     *  Skip SyntheticUuidRegistry add/remove in that case — otherwise the real player would
     *  be flagged as a doppel by the client mixin and their model animations would freeze. */
    private final boolean usesRealUuid;
    private final int entityId;
    private final double x, y, z;
    private final float yaw, pitch;
    private volatile StatueStyle style = StatueStyle.NATURAL;
    private volatile double scale = 1.0;
    private final Set<UUID> shownTo = new HashSet<>();

    public ChampionDoppelganger(MinecraftServer server, ServerWorld world, GameProfile sourceProfile,
                                  double x, double y, double z, float yaw, float pitch) {
        this.server = server;
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch;

        // Skin resolution strategy: when the real champion is OFFLINE we use
        // their REAL UUID + the signed textures property as-is. The client then
        // resolves the skin via standard Mojang lookup (signature valid against
        // the real UUID, profile exists at sessionserver) — yields the correct
        // skin reliably.
        //
        // When the real champion is ONLINE we fall back to a synthetic UUID
        // (XOR mask) to avoid the rare case of two PlayerListS2CPacket entries
        // for the same UUID confusing the client tab list. Signatures must be
        // stripped in that path because they're signed for the source UUID and
        // would fail validation against the synthetic UUID, forcing a fallback
        // to Steve/Alex. (This path historically rendered as Steve in v4 even
        // with stripped signatures because the client re-queries Mojang via the
        // synthetic UUID and gets 404 — accepted limitation, statue may look
        // wrong briefly while the champion is online.)
        UUID realUuid = sourceProfile.getId();
        boolean championOnline = server.getPlayerManager().getPlayer(realUuid) != null;
        this.usesRealUuid = !championOnline;
        this.syntheticUuid = usesRealUuid ? realUuid : synthFor(realUuid);
        boolean useRealUuid = usesRealUuid;
        String displayName = sourceProfile.getName() != null ? sourceProfile.getName() : "Champion";
        GameProfile profile = new GameProfile(syntheticUuid, displayName);
        for (java.util.Map.Entry<String, com.mojang.authlib.properties.Property> entry
                : sourceProfile.getProperties().entries()) {
            com.mojang.authlib.properties.Property p = entry.getValue();
            // Keep signature when using real UUID (valid for that UUID); strip
            // when using synthetic UUID (signature would mismatch).
            String sig = useRealUuid ? p.signature() : null;
            profile.getProperties().put(entry.getKey(),
                    new com.mojang.authlib.properties.Property(p.name(), p.value(), sig));
        }

        // 0x7F = all 7 skin layer bits enabled (cape, jacket, left/right sleeve, left/right pants, hat)
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

    /** Derive a stable synthetic UUID from a real player UUID. */
    public static UUID synthFor(UUID real) {
        return new UUID(
                real.getMostSignificantBits()  ^ SYNTH_MASK_HIGH,
                real.getLeastSignificantBits() ^ SYNTH_MASK_LOW);
    }

    /**
     * True if the given UUID looks like one of our synthetic doppel UUIDs.
     * Heuristic check used by the client mixin to freeze the model animation.
     * Reverse-XOR: a real UUID xor'd twice with the same mask returns itself,
     * so this checks whether the high/low bits, after one xor, are *not* a
     * trivially-zero pattern (catches our specific mask signature).
     */
    public static boolean isSynthetic(UUID candidate) {
        if (candidate == null) return false;
        // No clean way to "detect" a synthetic UUID without external state.
        // We expose this hook for the client mixin to register synthetic
        // doppel UUIDs as it sees them via a tracking set elsewhere.
        return SyntheticUuidRegistry.contains(candidate);
    }

    public UUID syntheticUuid() { return syntheticUuid; }
    public ServerWorld getWorld() { return world; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public Set<UUID> viewerUuids() { return shownTo; }

    /** Update the cosmetic style for the underlying anchor. Re-broadcasts to all viewers. */
    public void setStyle(StatueStyle style) {
        if (style == null) style = StatueStyle.NATURAL;
        this.style = style;
        broadcastStyleToAllViewers();
    }
    public StatueStyle getStyle() { return style; }

    /**
     * Apply a uniform scale to the doppel via the vanilla
     * {@code minecraft:generic.scale} attribute. Re-broadcasts to all viewers
     * so they see the new size immediately. Scale is also persisted on the fake
     * entity so freshly-joining viewers (showTo) see the correct size.
     */
    public void setScale(double scale) {
        if (scale <= 0) scale = 1.0;
        this.scale = scale;
        applyScaleToFake();
        broadcastAttributesToAllViewers();
    }
    public double getScale() { return scale; }

    private void applyScaleToFake() {
        try {
            Identifier id = Identifier.of("minecraft", "generic.scale");
            var opt = Registries.ATTRIBUTE.getEntry(id);
            if (opt.isEmpty()) return;
            @SuppressWarnings("unchecked")
            RegistryEntry<EntityAttribute> entry = (RegistryEntry<EntityAttribute>) opt.get();
            EntityAttributeInstance inst = fake.getAttributeInstance(entry);
            if (inst != null) inst.setBaseValue(scale);
        } catch (Throwable t) {
            LOGGER.debug("[CobbleCap] doppel applyScaleToFake failed: {}", t.getMessage());
        }
    }

    private void broadcastAttributesToAllViewers() {
        try {
            Identifier id = Identifier.of("minecraft", "generic.scale");
            var opt = Registries.ATTRIBUTE.getEntry(id);
            if (opt.isEmpty()) return;
            @SuppressWarnings("unchecked")
            RegistryEntry<EntityAttribute> entry = (RegistryEntry<EntityAttribute>) opt.get();
            EntityAttributeInstance inst = fake.getAttributeInstance(entry);
            if (inst == null) return;
            EntityAttributesS2CPacket packet = new EntityAttributesS2CPacket(
                    entityId, java.util.List.of(inst));
            for (UUID viewerUuid : shownTo) {
                ServerPlayerEntity v = server.getPlayerManager().getPlayer(viewerUuid);
                if (v != null && v.networkHandler != null) v.networkHandler.sendPacket(packet);
            }
        } catch (Throwable t) {
            LOGGER.warn("[CobbleCap] doppel attr rebroadcast failed: {}", t.getMessage());
        }
    }

    private void sendAttributesTo(ServerPlayerEntity viewer) {
        try {
            Identifier id = Identifier.of("minecraft", "generic.scale");
            var opt = Registries.ATTRIBUTE.getEntry(id);
            if (opt.isEmpty()) return;
            @SuppressWarnings("unchecked")
            RegistryEntry<EntityAttribute> entry = (RegistryEntry<EntityAttribute>) opt.get();
            EntityAttributeInstance inst = fake.getAttributeInstance(entry);
            if (inst == null) return;
            viewer.networkHandler.sendPacket(
                    new EntityAttributesS2CPacket(entityId, java.util.List.of(inst)));
        } catch (Throwable ignored) {}
    }

    public void showTo(ServerPlayerEntity viewer) {
        if (viewer == null || viewer.networkHandler == null) {
            LOGGER.info("[CobbleCap][Doppel] showTo skipped: viewer null or no handler");
            return;
        }
        if (!viewer.getServerWorld().getRegistryKey().equals(world.getRegistryKey())) {
            LOGGER.info("[CobbleCap][Doppel] showTo skipped: dim mismatch viewer={} doppel={}",
                    viewer.getServerWorld().getRegistryKey().getValue(),
                    world.getRegistryKey().getValue());
            return;
        }
        if (!shownTo.add(viewer.getUuid())) {
            // already shown — no-op, no log
            return;
        }
        try {
            viewer.networkHandler.sendPacket(
                    new PlayerListS2CPacket(PlayerListS2CPacket.Action.ADD_PLAYER, fake));
            viewer.networkHandler.sendPacket(new EntitySpawnS2CPacket(
                    entityId, fake.getUuid(), x, y, z, pitch, yaw,
                    net.minecraft.entity.EntityType.PLAYER, 0, Vec3d.ZERO, yaw));
            viewer.networkHandler.sendPacket(new EntitySetHeadYawS2CPacket(
                    fake, (byte) (yaw * 256.0f / 360.0f)));
            // Send ALL tracker entries (not just changed) so the client renders
            // skin layers / glow / pose immediately on spawn.
            List<DataTracker.SerializedEntry<?>> entries = fake.getDataTracker().getChangedEntries();
            if (entries == null || entries.isEmpty()) {
                entries = fake.getDataTracker().getDirtyEntries();
            }
            if (entries != null && !entries.isEmpty()) {
                viewer.networkHandler.sendPacket(
                        new EntityTrackerUpdateS2CPacket(entityId, entries));
            }
            // Tell this viewer's client which retextured skin to use for the doppel.
            sendStyleTo(viewer);
            // Send the scale attribute so the doppel renders at the right size.
            sendAttributesTo(viewer);
            LOGGER.info("[CobbleCap][Doppel] showTo OK viewer={} entityId={} fakeUuid={}",
                    viewer.getName().getString(), entityId, fake.getUuid());
        } catch (Throwable t) {
            LOGGER.warn("[CobbleCap][Doppel] showTo FAILED for {}: {}",
                    viewer.getName().getString(), t.getMessage(), t);
            shownTo.remove(viewer.getUuid());
        }
    }

    /**
     * Auto-heal: ensure every player in the doppel's world who is within {@code range}
     * blocks (squared distance) is in {@link #shownTo}. This is the safety net for
     * cases where Fabric's JOIN/START_TRACKING events somehow don't deliver our
     * spawn packets — e.g., when the viewer's ServerWorld isn't yet set at JOIN
     * time, or the marker armor stand never enters the player's tracker.
     *
     * Returns the number of newly-shown viewers.
     */
    public int ensureVisibleToNearby(double range) {
        if (server == null) return 0;
        double r2 = range * range;
        int shown = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p == null || p.networkHandler == null) continue;
            if (!p.getServerWorld().getRegistryKey().equals(world.getRegistryKey())) continue;
            if (shownTo.contains(p.getUuid())) continue;
            double dx = p.getX() - x, dy = p.getY() - y, dz = p.getZ() - z;
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            int before = shownTo.size();
            showTo(p);
            if (shownTo.size() > before) shown++;
        }
        return shown;
    }

    /** Forget a viewer without sending REMOVE packets (used on player disconnect). */
    public void forgetViewer(UUID viewerUuid) {
        shownTo.remove(viewerUuid);
    }

    private void sendStyleTo(ServerPlayerEntity viewer) {
        try {
            ServerPlayNetworking.send(viewer,
                    StatueStyleSyncPayload.upsert(syntheticUuid, style.ordinal()));
        } catch (Throwable ignored) {}
    }

    private void broadcastStyleToAllViewers() {
        for (UUID viewerUuid : shownTo) {
            ServerPlayerEntity v = server.getPlayerManager().getPlayer(viewerUuid);
            if (v != null) sendStyleTo(v);
        }
    }

    public void hideFrom(ServerPlayerEntity viewer) {
        if (viewer == null || viewer.networkHandler == null) return;
        if (!shownTo.remove(viewer.getUuid())) return;
        try {
            viewer.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(entityId));
            // When usesRealUuid is true, skip PlayerRemoveS2CPacket if the real
            // champion has come online in the meantime — otherwise we'd remove
            // them from this viewer's tab list. The vanilla player-list logic
            // will emit the correct REMOVE_PLAYER when they actually log out.
            boolean safeToRemove = !usesRealUuid
                    || server.getPlayerManager().getPlayer(syntheticUuid) == null;
            if (safeToRemove) {
                viewer.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(syntheticUuid)));
            }
            // Drop the client-side style mapping so the UUID can be reused later.
            try { ServerPlayNetworking.send(viewer, StatueStyleSyncPayload.remove(syntheticUuid)); }
            catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public int showToWorld() {
        // Register the doppel UUID so the client mixin can freeze its animation.
        // Skip when usesRealUuid is true — registering the real player UUID would
        // freeze the actual player's model when they log back in.
        if (!usesRealUuid) SyntheticUuidRegistry.add(syntheticUuid);
        int n = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.getServerWorld().getRegistryKey().equals(world.getRegistryKey())) {
                int before = shownTo.size();
                showTo(p);
                if (shownTo.size() > before) n++;
            }
        }
        return n;
    }

    public void hideFromAll() {
        for (UUID viewerUuid : new HashSet<>(shownTo)) {
            ServerPlayerEntity v = server.getPlayerManager().getPlayer(viewerUuid);
            if (v != null) hideFrom(v);
            else shownTo.remove(viewerUuid);
        }
        if (!usesRealUuid) SyntheticUuidRegistry.remove(syntheticUuid);
    }

    /**
     * Apply a glow status (yes/no) to the fake player and re-broadcast its
     * tracker so viewers see the outline color update. Combine with a
     * scoreboard team assigned to {@link #syntheticUuid} for the glow color.
     */
    public void setGlowingAndRebroadcast(boolean glowing) {
        try {
            fake.setGlowing(glowing);
            List<DataTracker.SerializedEntry<?>> entries = fake.getDataTracker().getChangedEntries();
            if (entries == null || entries.isEmpty()) {
                entries = fake.getDataTracker().getDirtyEntries();
            }
            if (entries == null || entries.isEmpty()) return;
            EntityTrackerUpdateS2CPacket packet = new EntityTrackerUpdateS2CPacket(entityId, entries);
            for (UUID viewerUuid : shownTo) {
                ServerPlayerEntity v = server.getPlayerManager().getPlayer(viewerUuid);
                if (v != null && v.networkHandler != null) {
                    v.networkHandler.sendPacket(packet);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[CobbleCap] setGlowing rebroadcast failed: {}", t.getMessage());
        }
    }
}
