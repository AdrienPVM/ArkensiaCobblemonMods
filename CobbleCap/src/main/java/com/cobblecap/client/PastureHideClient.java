package com.cobblecap.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-only toggle: when active, Cobblemon Pokémon attached to a pasture block
 * are not rendered for this player. The setting is persisted in
 * config/cobblecap_client.properties and applied via the EntityRenderDispatcherMixin.
 *
 * Decision is reflective: a Pokémon is considered "pastured" when its underlying
 * Pokemon object exposes a non-null tethering field/getter. Method names are
 * resolved by scanning, never by hardcoded yarn names, so the mixin is robust
 * against mapping rename across Cobblemon versions.
 */
public final class PastureHideClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static final Path PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("cobblecap_client.properties");

    private static volatile boolean hidden = false;

    public static void load() {
        try {
            if (!Files.exists(PATH)) return;
            java.util.Properties p = new java.util.Properties();
            try (var r = Files.newBufferedReader(PATH)) { p.load(r); }
            hidden = Boolean.parseBoolean(p.getProperty("pasture_pokemon_hidden", "false"));
            LOGGER.info("[CobbleCap-Client] Loaded pasture hide = {}", hidden);
        } catch (IOException e) {
            LOGGER.warn("[CobbleCap-Client] Failed to load client config: {}", e.getMessage());
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(PATH.getParent());
            java.util.Properties p = new java.util.Properties();
            p.setProperty("pasture_pokemon_hidden", Boolean.toString(hidden));
            try (var w = Files.newBufferedWriter(PATH)) { p.store(w, "CobbleCap client preferences"); }
        } catch (IOException e) {
            LOGGER.warn("[CobbleCap-Client] Failed to save client config: {}", e.getMessage());
        }
    }

    public static boolean isHidden() { return hidden; }
    public static void setHidden(boolean v) { hidden = v; save(); }
    public static boolean toggle() { hidden = !hidden; save(); return hidden; }

    /** Throttle for the debug log: don't spam more than ~1 line per second per entity. */
    private static volatile long lastDebugLog = 0L;

    /** Client-side cache of pastured Pokémon UUIDs, fed by the server's
     *  {@code PastureSyncPayload}. Cobblemon does NOT sync tetheringId via
     *  DataTracker, so client-side reflection cannot detect pasture — the
     *  server has to tell us. */
    private static final java.util.Set<java.util.UUID> PASTURED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void applySnapshot(java.util.Collection<java.util.UUID> uuids) {
        PASTURED.clear();
        PASTURED.addAll(uuids);
        LOGGER.info("[CobbleCap-Client] Pasture snapshot received: {} tethered Pokémon", PASTURED.size());
    }
    public static void addPastured(java.util.UUID uuid) {
        if (uuid != null) PASTURED.add(uuid);
    }
    public static void removePastured(java.util.UUID uuid) {
        if (uuid != null) PASTURED.remove(uuid);
    }
    public static int cachedCount() { return PASTURED.size(); }

    /**
     * True if the entity is a Cobblemon Pokémon currently attached to a pasture.
     * Looks up the client-side cache populated by {@code PastureSyncPayload}.
     */
    public static boolean isPastured(Entity entity) {
        if (entity == null) return false;
        if (!entity.getClass().getName().endsWith(".PokemonEntity")) return false;
        return PASTURED.contains(entity.getUuid());
    }

    private static void debugLog(Entity entity, String reason) {
        long now = System.currentTimeMillis();
        if (now - lastDebugLog < 1000) return;
        lastDebugLog = now;
        LOGGER.debug("[CobbleCap-Client] Pasture-hide: cancelling render for {} (reason: {})",
                entity.getName().getString(), reason);
    }

    private PastureHideClient() {}
}
