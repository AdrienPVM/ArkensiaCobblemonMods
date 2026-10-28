package com.cobbleshift;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Server-authoritative attack handler for transformed players.
 *
 * Two modes depending on whether the client has an attack animation:
 * - WITH animation: 1.3s delay. Aim direction is read at SPAWN time (player can re-aim during animation).
 * - WITHOUT animation: instant fire. Aim direction is read at click time (= spawn time).
 *
 * Cooldown starts at click time in BOTH cases, so DPS is identical regardless of delay.
 */
public class AttackHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobbleshift");

    /** Delay in ticks between click (animation start) and projectile spawn. 26 ticks = 1.3s */
    private static final int PROJECTILE_DELAY_TICKS = 26;

    private static final List<PendingAttack> pendingAttacks = new ArrayList<>();

    /**
     * Pending projectile spawn — stores data needed to fire after the animation delay.
     * Aim direction is NOT stored — it is read from the player at spawn time,
     * so the player can re-aim during the animation window.
     */
    private static class PendingAttack {
        final UUID playerUuid;
        final String type;
        final float damage;
        final int bst;
        int ticksRemaining;

        PendingAttack(UUID playerUuid, String type, float damage, int bst, int ticks) {
            this.playerUuid = playerUuid;
            this.type = type;
            this.damage = damage;
            this.bst = bst;
            this.ticksRemaining = ticks;
        }
    }

    /**
     * Handle an attack request from a transformed player.
     * Validates cooldown, sets it, and either queues projectile spawn after animation delay
     * (if client has animation) or spawns immediately (if no animation).
     * @param hasAnimation true if the client has an attack animation ready (delays projectile by 1.3s)
     */
    public static void handleAttack(ServerPlayerEntity player, boolean hasAnimation) {
        TransformManager mgr = CobbleShiftMod.INSTANCE.getTransformManager();
        TransformData data = mgr.getTransform(player.getUuid());
        if (data == null) return;

        // Check if attacks are enabled for this transform
        if (!data.isAttacksEnabled()) return;

        // Check cooldown
        if (data.getAttackCooldown() > 0) return;

        CobbleShiftConfig cfg = CobbleShiftMod.INSTANCE.getConfig();
        SpeciesMapper.SpeciesStats stats = SpeciesMapper.resolve(data.getSpeciesName());
        if (stats == null) return;

        // Calculate damage and cooldown
        float baseDamage = SpeciesMapper.mapAttackDamage(stats.atk(), stats.spAtk());
        int baseCooldown = SpeciesMapper.mapAttackCooldown(stats.speed());
        float damageMult = cfg.getAttackDamageMultiplier();
        float cooldownMult = cfg.getAttackCooldownMultiplier();

        float damage = baseDamage * damageMult;
        int cooldown = Math.max(5, Math.round(baseCooldown * cooldownMult));

        // Set cooldown immediately (prevents spamming during animation)
        data.setAttackCooldown(cooldown);

        if (hasAnimation) {
            // Queue projectile spawn after 1.3s delay.
            // Aim direction will be read at spawn time (player can re-aim during animation).
            pendingAttacks.add(new PendingAttack(
                player.getUuid(), data.getType1(), damage, data.getBst(), PROJECTILE_DELAY_TICKS
            ));
        } else {
            // Spawn immediately — no animation, aim at click time (= spawn time).
            Vec3d aimDir = getAimDirection(player);
            spawnProjectiles(player, data.getType1(), damage, aimDir, data.getBst());
        }
    }

    /**
     * Tick pending attacks — decrement counters and spawn projectiles when ready.
     * Called every server tick from CobbleShiftMod.onTick().
     */
    public static void tick(MinecraftServer server) {
        if (pendingAttacks.isEmpty()) return;

        Iterator<PendingAttack> it = pendingAttacks.iterator();
        while (it.hasNext()) {
            PendingAttack pa = it.next();
            pa.ticksRemaining--;

            if (pa.ticksRemaining <= 0) {
                it.remove();

                // Look up player at spawn time (they may have moved, or disconnected)
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(pa.playerUuid);
                if (player == null) continue;

                // Check player is still transformed
                TransformManager mgr = CobbleShiftMod.INSTANCE.getTransformManager();
                if (!mgr.isTransformed(player.getUuid())) continue;

                // Read aim direction NOW — player may have re-aimed during the animation
                Vec3d aimDir = getAimDirection(player);
                spawnProjectiles(player, pa.type, pa.damage, aimDir, pa.bst);
            }
        }
    }

    /**
     * Get the player's current aim direction with upward bias compensation.
     */
    private static Vec3d getAimDirection(ServerPlayerEntity player) {
        Vec3d lookDir = player.getRotationVector();
        return new Vec3d(lookDir.x, lookDir.y + 0.06, lookDir.z).normalize();
    }

    /**
     * Spawn projectiles based on BST tier.
     */
    private static void spawnProjectiles(ServerPlayerEntity player, String type, float damage, Vec3d aimDir, int bst) {
        ServerWorld world = (ServerWorld) player.getWorld();
        double spawnY = player.getY() + Math.max(0.25, player.getHeight() * 0.45);

        if (bst >= 450) {
            // 3 projectiles in a cone (15deg spread)
            spawnProjectile(world, player, type, damage * 0.7f, aimDir, spawnY);
            spawnProjectile(world, player, type, damage * 0.5f, rotateY(aimDir, Math.toRadians(15)), spawnY);
            spawnProjectile(world, player, type, damage * 0.5f, rotateY(aimDir, Math.toRadians(-15)), spawnY);
        } else if (bst >= 350) {
            // 1 boosted projectile
            spawnProjectile(world, player, type, damage * 1.3f, aimDir, spawnY);
        } else {
            // 1 normal projectile
            spawnProjectile(world, player, type, damage, aimDir, spawnY);
        }
    }

    private static void spawnProjectile(ServerWorld world, ServerPlayerEntity owner,
                                         String pokemonType, float damage, Vec3d direction, double spawnY) {
        TypeProjectileEntity projectile = new TypeProjectileEntity(world, owner, pokemonType, damage);
        projectile.setPosition(owner.getX(), spawnY, owner.getZ());

        double speed = 1.5;
        projectile.setVelocity(direction.x * speed, direction.y * speed, direction.z * speed);
        world.spawnEntity(projectile);
    }

    /**
     * Rotate a vector around the Y axis.
     */
    private static Vec3d rotateY(Vec3d vec, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3d(
            vec.x * cos + vec.z * sin,
            vec.y,
            -vec.x * sin + vec.z * cos
        );
    }
}
