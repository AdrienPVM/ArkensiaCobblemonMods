package com.cheeseguard.mixin;

import com.cheeseguard.logic.PortalOriginTracker;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Fixes nether portal return when entering from a custom dimension (e.g., cheeseguard:arkensia).
 * Vanilla hardcodes Nether ↔ Overworld. This saves the player's origin position
 * and teleports them back there instead of to the Overworld.
 */
@Mixin(NetherPortalBlock.class)
public class NetherPortalReturnMixin {

    @Inject(method = "createTeleportTarget", at = @At("HEAD"), cancellable = true)
    private void cheeseguard$fixPortalReturn(ServerWorld world, Entity entity, BlockPos pos,
                                              CallbackInfoReturnable<TeleportTarget> cir) {
        if (!(entity instanceof ServerPlayerEntity player)) return;

        RegistryKey<World> currentDim = world.getRegistryKey();
        UUID playerId = player.getUuid();

        // ── Entering portal from a custom dimension ──────────────────────
        if (!currentDim.equals(World.OVERWORLD) && !currentDim.equals(World.NETHER) && !currentDim.equals(World.END)) {
            PortalOriginTracker.store(playerId, new PortalOriginTracker.PortalOrigin(
                currentDim, entity.getPos(), entity.getYaw(), entity.getPitch()));
            return; // Let vanilla handle teleport to Nether
        }

        // ── Returning from Nether ────────────────────────────────────────
        if (currentDim.equals(World.NETHER)) {
            PortalOriginTracker.PortalOrigin origin = PortalOriginTracker.get(playerId);
            if (origin != null && !origin.dimension().equals(World.OVERWORLD)) {
                ServerWorld destWorld = world.getServer().getWorld(origin.dimension());
                if (destWorld != null) {
                    PortalOriginTracker.remove(playerId);
                    cir.setReturnValue(new TeleportTarget(
                        destWorld,
                        origin.pos(),
                        Vec3d.ZERO,
                        origin.yaw(),
                        origin.pitch(),
                        TeleportTarget.SEND_TRAVEL_THROUGH_PORTAL_PACKET
                    ));
                    return;
                }
            }
        }

        // ── Entering from Overworld → clear stored origin ────────────────
        if (currentDim.equals(World.OVERWORLD)) {
            PortalOriginTracker.remove(playerId);
        }
    }
}
