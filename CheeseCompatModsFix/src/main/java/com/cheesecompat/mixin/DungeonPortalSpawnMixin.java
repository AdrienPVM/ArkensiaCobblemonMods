package com.cheesecompat.mixin;

import maxigregrze.cobblesafari.dungeon.PortalSpawnManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Patches PortalSpawnManager to allow dungeon portals in ALL dimensions
 * (except cobblesafari:* dimensions), not just the Overworld.
 * Enables portals in cheeseguard:arkensia, etc.
 */
@Mixin(value = PortalSpawnManager.class)
public class DungeonPortalSpawnMixin {

    private static final Random CHEESECOMPAT_RANDOM = new Random();

    /**
     * Override trySpawnRandomPortal to pick players from ANY dimension
     * except cobblesafari dimensions. Original only considers overworld players.
     */
    @Inject(method = "trySpawnRandomPortal", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void cheesecompat$trySpawnAllDimensions(MinecraftServer server, CallbackInfo ci) {
        ci.cancel();
        List<ServerPlayerEntity> allPlayers = new ArrayList<>(server.getPlayerManager().getPlayerList());
        // Exclude players already in CobbleSafari dimensions
        allPlayers.removeIf(p -> p.getServerWorld().getRegistryKey().getValue().toString().startsWith("cobblesafari:"));
        if (allPlayers.isEmpty()) return;

        try {
            Method findPos = PortalSpawnManager.class.getDeclaredMethod(
                "findValidSpawnPosition", ServerWorld.class, BlockPos.class);
            findPos.setAccessible(true);

            Method spawnPortal = PortalSpawnManager.class.getDeclaredMethod(
                "spawnPortal", ServerWorld.class, BlockPos.class, ServerPlayerEntity.class);
            spawnPortal.setAccessible(true);

            for (int attempt = 0; attempt < 3; attempt++) {
                ServerPlayerEntity target = allPlayers.get(CHEESECOMPAT_RANDOM.nextInt(allPlayers.size()));
                ServerWorld level = target.getServerWorld();
                BlockPos portalPos = (BlockPos) findPos.invoke(null, level, target.getBlockPos());
                if (portalPos != null) {
                    spawnPortal.invoke(null, level, portalPos, target);
                    return;
                }
            }
        } catch (Exception e) {
            com.cheesecompat.CheeseCompatMod.LOGGER.debug("[CheeseCompat] Portal spawn attempt: {}", e.getMessage());
        }
    }

    /**
     * Override spawnPortalNearPlayer to allow ANY dimension except cobblesafari:*
     * (command-based spawning). Original blocks anything that isn't the Overworld.
     *
     * Note: method descriptor omitted — remap=false with yarn param types won't match
     * intermediary at runtime. Using name-only matching since there's only one overload.
     */
    @Inject(method = "spawnPortalNearPlayer(Lnet/minecraft/class_3222;Z)Z", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void cheesecompat$allowAnyDimensionPortalSpawn(ServerPlayerEntity player, boolean force,
                                                                   CallbackInfoReturnable<Boolean> ci) {
        String dimId = player.getServerWorld().getRegistryKey().getValue().toString();

        // Block in CobbleSafari dimensions — no portals inside the safari
        if (dimId.startsWith("cobblesafari:")) {
            ci.setReturnValue(false);
            return;
        }

        // Overworld — let original handle it
        if (dimId.equals("minecraft:overworld")) return;

        // Any other dimension — handle ourselves
        try {
            ServerWorld level = player.getServerWorld();
            BlockPos portalPos;

            if (force) {
                portalPos = player.getBlockPos().up();
            } else {
                Method findPos = PortalSpawnManager.class.getDeclaredMethod(
                    "findValidSpawnPosition", ServerWorld.class, BlockPos.class);
                findPos.setAccessible(true);
                portalPos = (BlockPos) findPos.invoke(null, level, player.getBlockPos());
                if (portalPos == null) {
                    ci.setReturnValue(false);
                    return;
                }
            }

            Method spawnPortal = PortalSpawnManager.class.getDeclaredMethod(
                "spawnPortal", ServerWorld.class, BlockPos.class, ServerPlayerEntity.class);
            spawnPortal.setAccessible(true);
            spawnPortal.invoke(null, level, portalPos, player);
            ci.setReturnValue(true);
        } catch (Exception e) {
            com.cheesecompat.CheeseCompatMod.LOGGER.warn("[CheeseCompat] Portal spawn failed in {}: {}", dimId, e.getMessage());
            ci.setReturnValue(false);
        }
    }
}
