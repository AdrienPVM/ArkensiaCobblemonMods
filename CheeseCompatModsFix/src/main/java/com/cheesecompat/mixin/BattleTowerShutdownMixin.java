package com.cheesecompat.mixin;

import battle.tower.BattleTower;
import battle.tower.battle.BattleTowerManager;
import net.minecraft.server.MinecraftServer;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * Fixes: server shutdown doesn't restore forced Pokemon levels.
 * BattleTower.onServerStopping clears maps but never calls restoreForcedLevels.
 * We inject BEFORE the cleanup to restore all forced levels while players are still online.
 */
@Mixin(value = BattleTower.class, remap = false)
public class BattleTowerShutdownMixin {

    @Inject(method = "onServerStopping", at = @At("HEAD"))
    private static void cheesecompat$restoreLevelsOnShutdown(MinecraftServer server, CallbackInfo ci) {
        try {
            Field field = BattleTowerManager.class.getDeclaredField("forcedLevelSnapshots");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, ?> snapshots = (Map<UUID, ?>) field.get(null);
            if (snapshots == null || snapshots.isEmpty()) return;

            // Copy keys to avoid ConcurrentModificationException (restoreForcedLevels removes entries)
            for (UUID playerUuid : new ArrayList<>(snapshots.keySet())) {
                try {
                    BattleTowerManager.restoreForcedLevels(playerUuid, server);
                    LoggerFactory.getLogger("CheeseCompat")
                        .info("[CheeseCompat] Restored forced levels for {} on shutdown", playerUuid);
                } catch (Exception e) {
                    LoggerFactory.getLogger("CheeseCompat")
                        .error("[CheeseCompat] Failed to restore levels for {}: {}", playerUuid, e.getMessage());
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LoggerFactory.getLogger("CheeseCompat")
                .warn("[CheeseCompat] Could not access forcedLevelSnapshots: {}", e.getMessage());
        }
    }
}
