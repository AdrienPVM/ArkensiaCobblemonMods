package com.cheesecompat.mixin;

import maxigregrze.cobblesafari.block.teleporter.SafariTeleporterBlock;
import maxigregrze.cobblesafari.teleporter.TeleporterTickHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Patches SafariTeleporterBlock to allow teleportation from any non-Safari dimension,
 * not just the Overworld. Enables the teleporter from cheeseguard:arkensia etc.
 */
@Mixin(value = SafariTeleporterBlock.class)
public class SafariTeleporterMixin {

    @Unique
    private static final Map<UUID, Long> cheesecompat$messageCooldown = new ConcurrentHashMap<>();

    @Inject(method = "onEntityCollision", at = @At("HEAD"), cancellable = true)
    private void cheesecompat$allowNonOverworldTeleport(BlockState state, World level, BlockPos pos, Entity entity, CallbackInfo ci) {
        if (level.isClient()) {
            ci.cancel();
            return;
        }
        if (entity instanceof ServerPlayerEntity player) {
            String dimId = level.getRegistryKey().getValue().toString();
            // Block teleporter in safari dimensions only
            if (dimId.startsWith("cobblesafari:")) {
                UUID playerId = player.getUuid();
                long currentTick = level.getServer().getTicks();
                Long lastMessage = cheesecompat$messageCooldown.get(playerId);
                if (lastMessage == null || currentTick - lastMessage > 100L) {
                    player.sendMessage(Text.translatable("cobblesafari.teleporter.overworld_only"), false);
                    cheesecompat$messageCooldown.put(playerId, currentTick);
                }
            } else {
                // Allow from any non-safari dimension (overworld, arkensia, etc.)
                TeleporterTickHandler.updatePlayerOnTeleporter(player);
            }
        }
        ci.cancel();
    }
}
