package com.cheesecompat.mixin;

import com.necro.raid.dens.common.util.RaidDenRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the raid boss Pokémon face toward the raid crystal when it spawns.
 * The crystal is always at (0,0,0) in the raid dimension; the boss spawns at
 * the position defined by raid_pois.boss_x/y/z in the structure NBT.
 */
@Mixin(targets = "com.necro.raid.dens.common.blocks.entity.RaidCrystalBlockEntity", remap = false)
public abstract class RaidBossFacingMixin {

    @Shadow
    public abstract ServerWorld getDimension();

    @Shadow
    private Identifier raidStructure;

    @Inject(method = "spawnRaidBoss", at = @At("TAIL"))
    private void cheesecompat$faceBossToCrystal(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;

        try {
            ServerWorld dim = this.getDimension();
            if (dim == null) return;

            Vec3d bossVec = RaidDenRegistry.getBossPos(this.raidStructure);
            if (bossVec == null) return;

            // Crystal is at origin (0,0,0) in the raid dimension.
            // Direction from boss to crystal: target - source
            double dx = -bossVec.x;
            double dz = -bossVec.z;
            float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;

            // The boss was just spawned and all other entities were killed
            // — find the LivingEntity (PokemonEntity) at the spawn position.
            // Check by class name to avoid hard compile dependency on Cobblemon.
            Box box = new Box(
                    bossVec.x - 1.5, bossVec.y - 1.0, bossVec.z - 1.5,
                    bossVec.x + 1.5, bossVec.y + 2.0, bossVec.z + 1.5
            );
            for (Entity entity : dim.getOtherEntities(null, box)) {
                if (entity instanceof LivingEntity living
                        && entity.getClass().getSimpleName().equals("PokemonEntity")) {
                    living.setYaw(yaw);
                    living.setHeadYaw(yaw);
                    living.setBodyYaw(yaw);
                    break;
                }
            }
        } catch (Exception ignored) {
            // Silently fail — don't break raid spawning
        }
    }
}
