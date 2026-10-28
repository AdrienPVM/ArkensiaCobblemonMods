package com.roicheese.eggandcheese.util;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Checks admin permissions.
 * Falls back to OP level 2 if no permission mod is present.
 */
public class PermissionUtil {
    public static final String ADMIN_NODE = "eggandcheese.admin";

    public static boolean isAdmin(PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return false;
        // Try fabric-permissions-api (if available) via reflection
        try {
            Class<?> perms = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            java.lang.reflect.Method check = perms.getMethod("check", net.minecraft.entity.Entity.class, String.class, int.class);
            return (boolean) check.invoke(null, serverPlayer, ADMIN_NODE, 2);
        } catch (Exception ignored) {
            // Fallback: OP level 2
            return serverPlayer.hasPermissionLevel(2);
        }
    }

    public static boolean isAdmin(ServerPlayerEntity player) {
        return isAdmin((PlayerEntity) player);
    }
}
