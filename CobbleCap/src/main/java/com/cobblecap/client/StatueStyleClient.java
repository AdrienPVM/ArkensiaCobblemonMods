package com.cobblecap.client;

import com.cobblecap.data.StatueStyle;
import net.minecraft.util.Identifier;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side map: synthetic doppelganger UUID → StatueStyle.
 * Populated by the {@code StatueStyleSyncPayload} receiver on join (SNAPSHOT)
 * and on doppel spawn/restyle (UPSERT). Consulted by PlayerEntityRendererMixin
 * to pick the right statue PNG texture for the doppel.
 */
public final class StatueStyleClient {

    private static final ConcurrentHashMap<UUID, StatueStyle> STYLES = new ConcurrentHashMap<>();

    private StatueStyleClient() {}

    public static void put(UUID uuid, StatueStyle style) {
        if (uuid == null) return;
        if (style == null || style == StatueStyle.NATURAL) {
            STYLES.remove(uuid);
        } else {
            STYLES.put(uuid, style);
        }
    }

    public static void remove(UUID uuid) {
        if (uuid != null) STYLES.remove(uuid);
    }

    public static void clear() { STYLES.clear(); }

    public static StatueStyle get(UUID uuid) {
        return uuid == null ? null : STYLES.get(uuid);
    }

    /** Resolve to the texture Identifier for a given UUID, or null if NATURAL/unknown. */
    public static Identifier textureFor(UUID uuid) {
        StatueStyle s = get(uuid);
        if (s == null || s == StatueStyle.NATURAL) return null;
        return Identifier.of("cobblecap", "textures/statue/" + s.key() + ".png");
    }
}
