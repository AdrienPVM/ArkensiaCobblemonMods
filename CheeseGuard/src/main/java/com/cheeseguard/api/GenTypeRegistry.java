package com.cheeseguard.api;

import com.cheeseguard.CheeseGuardMod;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of {@link GenTypeProvider} instances discovered via the
 * Fabric custom entrypoint {@code "cheeseguard-gentype"}.
 *
 * Discovery happens once at mod init. The registry is read-only after that.
 */
public final class GenTypeRegistry {

    private static final Map<String, GenTypeProvider> PROVIDERS = new LinkedHashMap<>();
    private static boolean discovered = false;

    private GenTypeRegistry() {}

    /** Called once from {@code CheeseGuardMod.onInitialize()}. */
    public static synchronized void discover() {
        if (discovered) return;
        discovered = true;
        try {
            for (GenTypeProvider p : FabricLoader.getInstance()
                    .getEntrypoints("cheeseguard-gentype", GenTypeProvider.class)) {
                if (p == null || p.id() == null) continue;
                if (PROVIDERS.containsKey(p.id())) {
                    CheeseGuardMod.LOGGER.warn("[CG] Duplicate GenTypeProvider id '{}' — ignoring", p.id());
                    continue;
                }
                PROVIDERS.put(p.id(), p);
            }
            CheeseGuardMod.LOGGER.info("[CG] GenTypeRegistry discovered {} provider(s): {}",
                PROVIDERS.size(), PROVIDERS.keySet());
        } catch (Throwable t) {
            CheeseGuardMod.LOGGER.error("[CG] GenTypeRegistry discovery failed: {}", t.getMessage(), t);
        }
    }

    public static GenTypeProvider get(String id) {
        return id == null ? null : PROVIDERS.get(id);
    }

    public static Collection<GenTypeProvider> all() {
        return Collections.unmodifiableCollection(PROVIDERS.values());
    }

    public static boolean has(String id) {
        return id != null && PROVIDERS.containsKey(id);
    }
}
