package com.cheeseguard.mixin;

import com.cheeseguard.logic.PortalOriginTracker;
import net.minecraft.block.EndPortalBlock;
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
 * Symmetric to {@link NetherPortalReturnMixin}: when a player exits the End,
 * teleport them back to where they entered (any dimension), instead of the
 * vanilla destination (bed/respawn anchor or world spawn).
 *
 * <p>EndPortalBlock is shared by the entry portal (overworld stronghold frame)
 * and the exit portal that spawns at the End centre after the dragon dies.</p>
 */
@Mixin(EndPortalBlock.class)
public class EndPortalReturnMixin {

    @Inject(method = "createTeleportTarget", at = @At("HEAD"), cancellable = true)
    private void cheeseguard$fixEndReturn(ServerWorld world, Entity entity, BlockPos pos,
                                           CallbackInfoReturnable<TeleportTarget> cir) {
        if (!(entity instanceof ServerPlayerEntity player)) return;

        RegistryKey<World> currentDim = world.getRegistryKey();
        UUID playerId = player.getUuid();

        // Entering the End from any dimension: remember exact origin.
        if (!currentDim.equals(World.END)) {
            PortalOriginTracker.storeEnd(playerId, new PortalOriginTracker.PortalOrigin(
                currentDim, entity.getPos(), entity.getYaw(), entity.getPitch()));
            return; // Let vanilla send the player to the End spawn platform.
        }

        // Exiting the End: redirect to stored origin if any.
        PortalOriginTracker.PortalOrigin origin = PortalOriginTracker.getEnd(playerId);
        if (origin == null) return; // No origin → vanilla behaviour (bed/spawn).
        ServerWorld destWorld = world.getServer().getWorld(origin.dimension());
        if (destWorld == null) return;
        PortalOriginTracker.removeEnd(playerId);
        cir.setReturnValue(new TeleportTarget(
            destWorld,
            origin.pos(),
            Vec3d.ZERO,
            origin.yaw(),
            origin.pitch(),
            TeleportTarget.SEND_TRAVEL_THROUGH_PORTAL_PACKET
        ));
    }
}
