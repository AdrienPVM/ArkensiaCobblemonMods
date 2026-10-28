package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts entity spawning in ServerWorld to enforce dimension-level rules:
 *
 * RCT trainers (rctmod):
 *   - Blocked when trainerSpawning = false for the dimension.
 *
 * Cobblemon entities:
 *   - Proximity heuristic: entity within 6 blocks of a player → send-out.
 *   - Flag check uses the PLAYER's position (not entity position) because
 *     Cobblemon can spawn the entity a few blocks outside a region boundary
 *     when the throw arc crosses the edge.
 *   - COBBLEMON_SUMMON has memberExempt=false in Flag, so isAllowed() will
 *     enforce the flag even for region members/owners.
 */
@Mixin(ServerWorld.class)
public class EntitySpawnMixin {

    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void onSpawnEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        String namespace = EntityType.getId(entity.getType()).getNamespace();
        ServerWorld world  = (ServerWorld)(Object)this;
        String worldId     = world.getRegistryKey().getValue().toString();
        var    dimMgr      = CheeseGuardMod.getInstance().getDimensionManager();

        // ── RCT trainers ──────────────────────────────────────────────────────
        if ("rctmod".equals(namespace)) {
            if (dimMgr != null && !dimMgr.isTrainerSpawningAllowed(worldId)) {
                cir.setReturnValue(false);
            }
            return;
        }

        // ── Cobblemon only below ──────────────────────────────────────────────
        if (!"cobblemon".equals(namespace)) return;

        // ── Battle Pokemon: never block ──────────────────────────────────────
        // Pokemon sent out in PvP/trainer battles have a battleId set.
        // These must always be allowed regardless of MOB_SPAWNING or COBBLEMON_SUMMON flags,
        // because the opponent's Pokemon spawns on the far side of the battlefield
        // (often >6 blocks from any player), which would incorrectly trigger the
        // "wild spawn" heuristic below and get blocked by MOB_SPAWNING=deny.
        try {
            java.lang.reflect.Method getBattleId = entity.getClass().getMethod("getBattleId");
            Object battleId = getBattleId.invoke(entity);
            if (battleId != null) return; // battle Pokemon — always allow
        } catch (Exception ignored) {}

        // ── Hard block: dim has cobblemon entirely disabled ───────────────────
        // This catches ALL cobblemon: wild spawns, player send-outs, legendary
        // monument activations, /pokespawn commands, and any mod that spawns them.
        if (dimMgr != null && dimMgr.isCobblemonBlocked(worldId)) {
            cir.setReturnValue(false);
            // Notify nearby player if this looks like a manual action
            ServerPlayerEntity nearby = null;
            double distSq = 10.0 * 10.0;
            for (ServerPlayerEntity player : world.getPlayers()) {
                double d = player.squaredDistanceTo(entity.getX(), entity.getY(), entity.getZ());
                if (d < distSq) { distSq = d; nearby = player; }
            }
            if (nearby != null) {
                nearby.sendMessage(
                    net.minecraft.text.Text.translatable("cheeseguard.protection.cobblemon_disabled")
                        .formatted(Formatting.RED), true);
            }
            return;
        }

        var rm = CheeseGuardMod.getInstance().getRegionManager();

        // Proximity heuristic: entity within 6 blocks → this is a player send-out
        ServerPlayerEntity nearestPlayer = null;
        double nearestDistSq = 6.0 * 6.0;
        for (ServerPlayerEntity player : world.getPlayers()) {
            double d = player.squaredDistanceTo(entity.getX(), entity.getY(), entity.getZ());
            if (d < nearestDistSq) { nearestDistSq = d; nearestPlayer = player; }
        }

        if (nearestPlayer != null) {
            // Check flag at PLAYER position — entity position is unreliable at spawn time
            // (may be at 0,0,0 or outside region boundary due to throw arc)
            int px = nearestPlayer.getBlockX();
            int py = nearestPlayer.getBlockY();
            int pz = nearestPlayer.getBlockZ();
            if (!rm.isAllowed(nearestPlayer, worldId, px, py, pz, Flag.COBBLEMON_SUMMON)) {
                cir.setReturnValue(false);
                nearestPlayer.sendMessage(
                    net.minecraft.text.Text.translatable("cheeseguard.protection.cobblemon_summon_zone_denied")
                        .formatted(Formatting.RED), true);
                CheeseGuardMod.LOGGER.debug("[CG] Blocked pokemon send-out for {} (COBBLEMON_SUMMON denied)",
                    nearestPlayer.getName().getString());
            }
        } else {
            // No nearby player → wild spawn
            if (dimMgr != null && !dimMgr.isMobSpawningAllowed(worldId)) {
                cir.setReturnValue(false);
                return;
            }
            BlockPos pos = entity.getBlockPos();
            if (!rm.isFlagAllowed(worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.MOB_SPAWNING)) {
                cir.setReturnValue(false);
            }
        }
    }


    // Note: spawnEntityAndPassengers does NOT exist in ServerWorld in MC 1.21.1.
    // Coverage for legendary monument / mod API spawns is handled by:
    //   1. The spawnEntity injection above (catches all spawns including passengers)
    //   2. The periodic purge in DimensionManager (every 5s, removes stray cobblemon)

}
