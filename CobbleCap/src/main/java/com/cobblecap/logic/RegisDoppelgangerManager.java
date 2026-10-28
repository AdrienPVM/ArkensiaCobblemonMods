package com.cobblecap.logic;

import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.Tenure;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-arena Régis skin overlay using the same {@link ChampionDoppelganger}
 * machinery as the statue system. When a player enters their per-player
 * conseil4 dim in <strong>ranked</strong> mode, we attach a fake-player
 * doppelganger to every {@code champion_regis_challenge[_<uuid8>]}
 * {@code TrainerMob} in that world so the trainer renders with the current
 * Maître de la Ligue Compétitif's skin instead of the rctmod default.
 * <p>
 * Why this exists: rctmod loads trainer textures from
 * {@code assets/rctmod/textures/trainers/single/<id>.png} — there is no path
 * by which we can change that asset at runtime without a resource-pack
 * regeneration + AutoModpack push (disruptive). The doppel overlay is a
 * server-side packet trick: we spawn an invisible fake-player with the
 * champion's GameProfile (Mojang textures property copied), positioned on
 * top of the TrainerMob. The real TrainerMob is set to invisible so only the
 * doppel is rendered. Battle interaction remains intact — the TrainerMob's
 * hitbox is unchanged, right-click still triggers the rctmod battle dialog.
 * <p>
 * Lifecycle:
 * <ul>
 *   <li>{@link Conseil4TrainerCloner} calls {@link #onDimEntered(ServerWorld, boolean)}
 *       after retagging. Ranked → spawn doppels. Classic → tear them down.</li>
 *   <li>{@link ServerEntityEvents#ENTITY_UNLOAD} clears the per-entity doppel
 *       when the TrainerMob's chunk unloads (player walks away, dim resets).</li>
 *   <li>{@link EntityTrackingEvents#START_TRACKING} re-shows the doppel to a
 *       player who has just come into range of the tracker.</li>
 *   <li>{@link #refreshAll(MinecraftServer)} is called from
 *       {@link RctTrainerMorpher#flushMorph} after a new champion is crowned;
 *       it rebuilds every active doppel with the new champion's profile.</li>
 * </ul>
 */
public final class RegisDoppelgangerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    /** Canonical Régis trainer ID prefix — matches both bare and per-dim variants. */
    private static final String REGIS_BASE_ID = "champion_regis_challenge";

    /** TrainerMob entity UUID -> overlay doppel. */
    private final Map<UUID, ChampionDoppelganger> doppels = new ConcurrentHashMap<>();

    /** World registry-key strings (e.g. "cheeseguard:conseil4_a1b2c3d4") whose
     *  owner is currently in ranked mode. Drives lazy doppel spawning for
     *  Régis TrainerMobs loaded after the player entered (e.g. when their
     *  chunk loads as the player walks across the arena). */
    private final java.util.Set<String> rankedDims =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private MinecraftServer server;

    public void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> hideAllAndClear());
        ServerEntityEvents.ENTITY_LOAD.register(this::onEntityLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register(this::onEntityUnload);
        EntityTrackingEvents.START_TRACKING.register(this::onStartTracking);
        // Direct hook: react to dim entry ourselves instead of being called by
        // Conseil4TrainerCloner (which is now in cleanup-only mode).
        net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents
                .AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            try {
                String path = destination.getRegistryKey().getValue().getPath();
                if (!path.startsWith("conseil4_") || path.length() != "conseil4_".length() + 8) return;
                boolean ranked = ChampionManager.readPlayerMode(player)
                        == com.cobblecap.data.LegendEntry.Mode.RANKED;
                onDimEntered(destination, ranked);
            } catch (Throwable t) {
                LOGGER.debug("[CobbleCap] Régis doppel dim-entry hook failed: {}", t.getMessage());
            }
        });
    }

    /**
     * Reset every Régis {@code TrainerMob} in any per-player conseil4 world to
     * VISIBLE at boot. We can't rely on prior-session doppel state — the fake
     * players are packet-only and don't survive a restart, but the {@code
     * setInvisible(true)} flag IS persisted in entity NBT. Without this pass,
     * a Régis whose owner is offline (or who hasn't re-entered yet) would
     * remain invisible until {@code onDimEntered} re-applies the overlay.
     * {@code onDimEntered} itself sets invisible=true again on ranked re-entry,
     * so this reset is harmless when followed by a doppel spawn.
     */
    private void onServerStarted(MinecraftServer s) {
        this.server = s;
        try {
            int reset = 0;
            for (ServerWorld w : s.getWorlds()) {
                String path = w.getRegistryKey().getValue().getPath();
                if (!path.startsWith("conseil4_") || path.length() != "conseil4_".length() + 8) continue;
                for (Entity e : w.iterateEntities()) {
                    if (isRegisTrainerMob(e) && e.isInvisible()) {
                        e.setInvisible(false);
                        reset++;
                    }
                }
            }
            if (reset > 0) {
                LOGGER.info("[CobbleCap] Régis boot reset: cleared invisibility on {} stale TrainerMob(s)", reset);
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] Régis boot reset failed: {}", e.getMessage());
        }
    }

    // ── Called from Conseil4TrainerCloner ────────────────────────────────────

    /**
     * Update Régis doppels for the given world based on the player's mode.
     * Ranked → ensure every Régis TrainerMob has a doppel showing the current
     * champion's skin. Classic → tear down any existing doppels in this world
     * (restores original rctmod render).
     */
    public void onDimEntered(ServerWorld world, boolean ranked) {
        if (server == null || world == null) return;
        String worldKey = world.getRegistryKey().getValue().toString();
        if (ranked) {
            rankedDims.add(worldKey);
            spawnAllInDim(world);
        } else {
            rankedDims.remove(worldKey);
            hideAllInDim(world);
        }
    }

    private void spawnAllInDim(ServerWorld world) {
        // ALWAYS clear stale invisibility first — the prior session may have
        // set invisible=true (NBT-persisted) but failed to attach a doppel, so
        // an "invisible without overlay" Régis would render as nothing. This
        // pass guarantees visibility unless we successfully overlay a doppel.
        for (Entity e : world.iterateEntities()) {
            if (isRegisTrainerMob(e) && e.isInvisible()) e.setInvisible(false);
        }
        Tenure current = CobbleCapMod.INSTANCE.getTenureStorage().current();
        if (current == null) {
            // No ranked champion yet — leave the original rctmod skin alone.
            LOGGER.debug("[CobbleCap] No ranked champion yet — Régis skin overlay skipped for {}",
                    world.getRegistryKey().getValue());
            return;
        }
        GameProfile profile = fetchChampionProfile(current.championUuid, current.championName);
        if (profile == null) {
            LOGGER.warn("[CobbleCap] Could not fetch Mojang profile for champion {} — Régis skin not overlaid (visible default skin instead)",
                    current.championName);
            return;
        }
        int spawned = 0;
        for (Entity e : world.iterateEntities()) {
            if (!isRegisTrainerMob(e)) continue;
            if (doppels.containsKey(e.getUuid())) continue;
            spawnDoppel(world, e, profile);
            spawned++;
        }
        if (spawned > 0) {
            LOGGER.info("[CobbleCap] Régis doppel: spawned {} overlay(s) in {} (champion={})",
                    spawned, world.getRegistryKey().getValue(), current.championName);
        }
    }

    private void hideAllInDim(ServerWorld world) {
        int hidden = 0;
        int unhid = 0;
        for (Entity e : world.iterateEntities()) {
            if (!isRegisTrainerMob(e)) continue;
            ChampionDoppelganger d = doppels.remove(e.getUuid());
            if (d != null) { d.hideFromAll(); hidden++; }
            // ALWAYS restore visibility (whether or not we had a doppel for it).
            // Without this, a Régis left invisible by a prior session would
            // remain invisible in classic mode.
            if (e.isInvisible()) { e.setInvisible(false); unhid++; }
        }
        if (hidden > 0 || unhid > 0) {
            LOGGER.info("[CobbleCap] Régis doppel: classic-mode in {} — hid {} overlay(s), restored {} visible TrainerMob(s)",
                    world.getRegistryKey().getValue(), hidden, unhid);
        }
    }

    /**
     * Rebuild every active doppel using the latest ranked champion's profile.
     * Called by {@link RctTrainerMorpher#flushMorph} so players currently
     * inside their conseil4 dim see the new Maître's skin without having to
     * leave and re-enter.
     */
    public void refreshAll(MinecraftServer server) {
        if (server == null || doppels.isEmpty()) return;
        Tenure current = CobbleCapMod.INSTANCE.getTenureStorage().current();
        if (current == null) return;
        GameProfile profile = fetchChampionProfile(current.championUuid, current.championName);
        if (profile == null) return;
        int refreshed = 0;
        for (Map.Entry<UUID, ChampionDoppelganger> entry
                : new ArrayList<>(doppels.entrySet())) {
            UUID tmUuid = entry.getKey();
            ChampionDoppelganger old = entry.getValue();
            // Locate the TrainerMob (it could be in any loaded world).
            Entity ent = null;
            ServerWorld w = null;
            for (ServerWorld sw : server.getWorlds()) {
                Entity candidate = sw.getEntity(tmUuid);
                if (candidate != null) { ent = candidate; w = sw; break; }
            }
            if (ent == null || w == null) continue;
            old.hideFromAll();
            doppels.remove(tmUuid);
            spawnDoppel(w, ent, profile);
            refreshed++;
        }
        if (refreshed > 0) {
            LOGGER.info("[CobbleCap] Régis doppel refresh: {} overlay(s) -> {}",
                    refreshed, current.championName);
        }
    }

    // ── Fabric event hooks ───────────────────────────────────────────────────

    /**
     * Late-load handler: when Régis's chunk loads after the player has already
     * entered the dim (e.g. they walked across the arena), we need to spawn
     * the doppel for him then. Without this hook, Régis would render with
     * default rctmod skin until the player leaves and re-enters.
     * <p>
     * Also unconditionally clears stale invisibility from a prior session so
     * the trainer is never stuck transparent — visibility is restored, then
     * the doppel re-applies invisible only if it successfully attaches.
     */
    private void onEntityLoad(Entity entity, ServerWorld world) {
        if (server == null) return;
        if (!isRegisTrainerMob(entity)) return;
        // Always clear stale invisibility (NBT-persisted from a prior bad session)
        if (entity.isInvisible()) entity.setInvisible(false);
        String worldKey = world.getRegistryKey().getValue().toString();
        if (!rankedDims.contains(worldKey)) return;
        if (doppels.containsKey(entity.getUuid())) return;
        Tenure current = CobbleCapMod.INSTANCE.getTenureStorage().current();
        if (current == null) return;
        GameProfile profile = fetchChampionProfile(current.championUuid, current.championName);
        if (profile == null) return;
        spawnDoppel(world, entity, profile);
        LOGGER.info("[CobbleCap] Régis doppel: late-spawn for newly-loaded {} (champion={})",
                entity.getUuid(), current.championName);
    }

    private void onEntityUnload(Entity entity, ServerWorld world) {
        ChampionDoppelganger d = doppels.remove(entity.getUuid());
        if (d != null) d.hideFromAll();
    }

    private void onStartTracking(Entity entity, ServerPlayerEntity viewer) {
        ChampionDoppelganger d = doppels.get(entity.getUuid());
        if (d != null) d.showTo(viewer);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private void spawnDoppel(ServerWorld world, Entity trainerMob, GameProfile profile) {
        try {
            ChampionDoppelganger d = new ChampionDoppelganger(server, world, profile,
                    trainerMob.getX(), trainerMob.getY(), trainerMob.getZ(),
                    trainerMob.getYaw(), trainerMob.getPitch());
            doppels.put(trainerMob.getUuid(), d);
            d.showToWorld();
            // Hide the underlying TrainerMob render so the doppel is the only
            // visible body. The hitbox stays intact for right-click → battle.
            trainerMob.setInvisible(true);
        } catch (Throwable t) {
            LOGGER.warn("[CobbleCap] Régis doppel spawn failed: {}", t.getMessage());
        }
    }

    private static boolean isRegisTrainerMob(Entity e) {
        try {
            if (!"rctmod".equals(EntityType.getId(e.getType()).getNamespace())) return false;
            String id = ChampionManager.extractRctmodTrainerId(e);
            if (id == null) return false;
            // Match canonical OR any legacy per-dim suffix (left over from the
            // disabled cloner experiment). The doppel doesn't care about the
            // suffix — it overlays the player skin regardless.
            return REGIS_BASE_ID.equals(ChampionManager.stripPerDimSuffix(id));
        } catch (Exception ignored) {
            return false;
        }
    }

    private GameProfile fetchChampionProfile(UUID uuid, String fallbackName) {
        GameProfile p = MojangProfileFetcher.resolve(server, uuid, fallbackName);
        if (p == null) {
            LOGGER.warn("[CobbleCap] Champion {} ({}) has no cached textures — Régis skin will fall back to default rctmod render",
                    fallbackName, uuid);
        }
        return p;
    }

    private void hideAllAndClear() {
        for (ChampionDoppelganger d : doppels.values()) d.hideFromAll();
        doppels.clear();
    }
}
