package com.cobblecap.logic;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/** Reflection bridge to rctmod's in-memory trainer reload.
 *  There is NO `rctmod reload` command (verified against rctmod-fabric
 *  0.16.11-beta bytecode — only `rctmod player ...` and `rctmod trainer ...`).
 *  The real entry point is
 *  {@code RCTMod.getInstance().getTrainerManager().loadTrainers()}, which re-reads
 *  the trainer JSONs from disk via the cached ResourceManager and rebuilds the
 *  registry (then resyncs clients) — WITHOUT the vanilla /reload datapack-wide rescan.
 *
 *  <p>Must be called ON the server thread (loadTrainers touches PlayerList sync).
 *  Fail-open: any reflection failure logs the real cause and returns false.</p>
 */
public final class RctReloadHook {
    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static volatile Method getInstance, getTrainerManager, loadTrainers;
    private static volatile boolean initFailed;

    private RctReloadHook() {}

    /** Reload rctmod's trainers from disk. Must run on the server thread. Returns true on success. */
    public static boolean reloadTrainers(MinecraftServer server) {
        try {
            if (loadTrainers == null && !initFailed) initReflection();
            if (initFailed) return false;
            Object rct = getInstance.invoke(null);
            Object tm = getTrainerManager.invoke(rct);
            loadTrainers.invoke(tm);
            LOGGER.info("[CobbleCap] rctmod TrainerManager.loadTrainers() invoked (ok=true)");
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[CobbleCap] rctmod reload via reflection FAILED: {}", t.toString());
            return false;
        }
    }

    private static synchronized void initReflection() {
        if (loadTrainers != null || initFailed) return;
        try {
            Class<?> rctMod = Class.forName("com.gitlab.srcmc.rctmod.api.RCTMod");
            getInstance = rctMod.getMethod("getInstance");
            getTrainerManager = rctMod.getMethod("getTrainerManager");
            Class<?> tm = Class.forName("com.gitlab.srcmc.rctmod.api.service.TrainerManager");
            loadTrainers = tm.getMethod("loadTrainers");
        } catch (Throwable t) {
            initFailed = true;
            LOGGER.warn("[CobbleCap] rctmod reload reflection unavailable: {}", t.toString());
        }
    }
}
