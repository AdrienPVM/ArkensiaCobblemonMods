package com.cheesepatrol;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import org.joml.Vector3f;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PatrolMarker {

    private static final String TAG_PREFIX = "cheesepatrol";

    public static void spawnMarker(ServerWorld world, BlockPos pos, UUID npcUuid, int waypointIndex) {
        ArmorStandEntity stand = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
        stand.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setCustomName(Text.literal("WP #" + (waypointIndex + 1)));
        stand.setCustomNameVisible(true);
        stand.addCommandTag(TAG_PREFIX);
        stand.addCommandTag("npc:" + npcUuid.toString());
        stand.addCommandTag("wp:" + waypointIndex);
        world.spawnEntity(stand);
    }

    public static void removeMarkers(ServerWorld world, UUID npcUuid) {
        String npcTag = "npc:" + npcUuid.toString();
        List<Entity> toRemove = new ArrayList<>();
        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof ArmorStandEntity
                    && entity.getCommandTags().contains(TAG_PREFIX)
                    && entity.getCommandTags().contains(npcTag)) {
                toRemove.add(entity);
            }
        }
        toRemove.forEach(Entity::discard);
    }

    public static void removeAllMarkers(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            List<Entity> toRemove = new ArrayList<>();
            for (Entity entity : world.iterateEntities()) {
                if (entity instanceof ArmorStandEntity
                        && entity.getCommandTags().contains(TAG_PREFIX)) {
                    toRemove.add(entity);
                }
            }
            toRemove.forEach(Entity::discard);
        }
    }

    public static void refreshMarkers(ServerWorld world, PatrolData patrol) {
        removeMarkers(world, patrol.getNpcUuid());
        for (int i = 0; i < patrol.getWaypoints().size(); i++) {
            spawnMarker(world, patrol.getWaypoints().get(i), patrol.getNpcUuid(), i);
        }
    }

    // ── NPC trail particle effects ────────────────────────────────────────

    private static final DustParticleEffect DUST_GOLD = new DustParticleEffect(new Vector3f(1f, 0.84f, 0f), 1.2f);

    /** Spawn trail particles on an NPC entity. Called every 2 ticks from PatrolManager. */
    public static void spawnTrailParticles(ServerWorld world, Entity npc, String effect, float yOffset, float zOffset, int maxRange) {
        if ("none".equals(effect) || effect == null) return;

        double vx = npc.getVelocity().x;
        double vy = npc.getVelocity().y;
        double vz = npc.getVelocity().z;
        double spd = Math.sqrt(vx * vx + vy * vy + vz * vz);

        // Forward direction (from NPC yaw if stationary, from velocity if moving)
        double fwdX, fwdY, fwdZ;
        if (spd > 0.01) {
            fwdX = vx / spd; fwdY = vy / spd; fwdZ = vz / spd;
        } else {
            double yawRad = Math.toRadians(npc.getYaw());
            fwdX = -Math.sin(yawRad); fwdY = 0; fwdZ = Math.cos(yawRad);
        }

        // Base position with offsets: yOffset = up/down, zOffset = forward/backward (positive = behind)
        double x = npc.getX() - fwdX * zOffset;
        double y = npc.getY() + npc.getHeight() * 0.5 + yOffset - fwdY * zOffset;
        double z = npc.getZ() - fwdZ * zOffset;

        // Trail positions: at offset point + 2 trailing points further behind
        double[][] positions;
        if (spd > 0.01) {
            double bx = -fwdX, by = -fwdY, bz = -fwdZ; // backward direction
            positions = new double[][] {
                {x, y, z},
                {x + bx * 0.5, y + by * 0.5, z + bz * 0.5},
                {x + bx * 1.0, y + by * 1.0, z + bz * 1.0},
            };
        } else {
            positions = new double[][] {{x, y, z}};
        }

        var particle = resolveParticle(effect);
        if (particle == null) return;
        int count = "enchant".equals(effect) ? 3 : "cherry".equals(effect) ? 3 : "end_rod".equals(effect) ? 1 : "smoke".equals(effect) ? 1 : 2;
        double spread = "enchant".equals(effect) ? 0.2 : "cherry".equals(effect) ? 0.3 : "smoke".equals(effect) ? 0.1 : 0.15;
        double pSpeed = "enchant".equals(effect) ? 0.5 : "smoke".equals(effect) ? 0.005 : 0.01;
        double rangeSq = (double) maxRange * maxRange;

        // Send only to players within configured range
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.squaredDistanceTo(x, y, z) > rangeSq) continue;
            for (double[] pos : positions) {
                world.spawnParticles(p, particle, true, pos[0], pos[1], pos[2],
                        count, spread, spread * 1.3, spread, pSpeed);
            }
        }
    }

    private static final DustParticleEffect DUST_CYAN = new DustParticleEffect(new Vector3f(0.13f, 0.83f, 0.93f), 1.2f);
    private static final DustParticleEffect DUST_PURPLE = new DustParticleEffect(new Vector3f(0.6f, 0.2f, 1f), 1.2f);
    private static final DustParticleEffect DUST_WHITE = new DustParticleEffect(new Vector3f(1f, 1f, 1f), 1.0f);

    private static final DustParticleEffect TRAIL_RED = new DustParticleEffect(new Vector3f(1f, 0.15f, 0.15f), 1.2f);
    private static final DustParticleEffect TRAIL_GREEN = new DustParticleEffect(new Vector3f(0.2f, 1f, 0.3f), 1.2f);
    private static final DustParticleEffect DUST_PINK = new DustParticleEffect(new Vector3f(1f, 0.5f, 0.8f), 1.2f);
    private static final DustParticleEffect DUST_ORANGE = new DustParticleEffect(new Vector3f(1f, 0.55f, 0f), 1.2f);

    private static net.minecraft.particle.ParticleEffect resolveParticle(String effect) {
        return switch (effect) {
            case "sparkle", "end_rod" -> ParticleTypes.END_ROD;
            case "flame" -> ParticleTypes.FLAME;
            case "soul" -> ParticleTypes.SOUL_FIRE_FLAME;
            case "enchant" -> ParticleTypes.ENCHANT;
            case "cherry" -> ParticleTypes.CHERRY_LEAVES;
            case "smoke" -> ParticleTypes.CAMPFIRE_COSY_SMOKE;
            case "dust" -> DUST_GOLD;
            case "dust_cyan" -> DUST_CYAN;
            case "dust_purple" -> DUST_PURPLE;
            case "dust_white" -> DUST_WHITE;
            case "dust_red" -> TRAIL_RED;
            case "dust_green" -> TRAIL_GREEN;
            case "dust_pink" -> DUST_PINK;
            case "dust_orange" -> DUST_ORANGE;
            case "heart" -> ParticleTypes.HEART;
            case "note" -> ParticleTypes.NOTE;
            case "portal" -> ParticleTypes.PORTAL;
            case "snowflake" -> ParticleTypes.SNOWFLAKE;
            case "witch" -> ParticleTypes.WITCH;
            case "glow" -> ParticleTypes.GLOW;
            case "electric" -> ParticleTypes.ELECTRIC_SPARK;
            case "scrape" -> ParticleTypes.SCRAPE;
            case "wax" -> ParticleTypes.WAX_ON;
            case "spore" -> ParticleTypes.SPORE_BLOSSOM_AIR;
            case "ash" -> ParticleTypes.WHITE_ASH;
            case "crimson_spore" -> ParticleTypes.CRIMSON_SPORE;
            case "warped_spore" -> ParticleTypes.WARPED_SPORE;
            case "mycelium" -> ParticleTypes.MYCELIUM;
            case "falling_nectar" -> ParticleTypes.FALLING_NECTAR;
            case "falling_spore" -> ParticleTypes.FALLING_SPORE_BLOSSOM;
            case "dripping_honey" -> ParticleTypes.DRIPPING_HONEY;
            case "falling_honey" -> ParticleTypes.FALLING_HONEY;
            case "dripping_obsidian_tear" -> ParticleTypes.DRIPPING_OBSIDIAN_TEAR;
            case "falling_obsidian_tear" -> ParticleTypes.FALLING_OBSIDIAN_TEAR;
            case "cloud" -> ParticleTypes.CLOUD;
            case "crit" -> ParticleTypes.CRIT;
            case "damage" -> ParticleTypes.DAMAGE_INDICATOR;
            case "dragon" -> ParticleTypes.DRAGON_BREATH;
            case "firework" -> ParticleTypes.FIREWORK;
            case "lava" -> ParticleTypes.LAVA;
            case "rain" -> ParticleTypes.RAIN;
            case "smoke_large" -> ParticleTypes.LARGE_SMOKE;
            case "totem" -> ParticleTypes.TOTEM_OF_UNDYING;
            case "trial" -> ParticleTypes.TRIAL_SPAWNER_DETECTION;
            default -> null;
        };
    }

    public static final List<String> PARTICLE_EFFECTS = List.of(
            "none", "sparkle", "flame", "soul", "enchant", "end_rod", "cherry", "smoke",
            "dust", "dust_cyan", "dust_purple", "dust_white", "dust_red", "dust_green", "dust_pink", "dust_orange",
            "heart", "note", "portal", "snowflake", "witch", "glow", "electric", "scrape",
            "wax", "spore", "ash", "crimson_spore", "warped_spore", "mycelium",
            "falling_nectar", "falling_spore", "dripping_honey", "falling_honey",
            "dripping_obsidian_tear", "falling_obsidian_tear",
            "cloud", "crit", "damage", "dragon", "firework",
            "lava", "rain", "smoke_large", "totem", "trial"
    );

    // ── Waypoint visualization ──────────────────────────────────────────

    private static final DustParticleEffect DUST_GREEN = new DustParticleEffect(new Vector3f(0f, 1f, 0f), 1.5f);
    private static final DustParticleEffect DUST_RED = new DustParticleEffect(new Vector3f(1f, 0f, 0f), 1.5f);
    private static final DustParticleEffect DUST_BLUE = new DustParticleEffect(new Vector3f(0.2f, 0.6f, 1f), 1.5f);
    private static final DustParticleEffect DUST_LINE = new DustParticleEffect(new Vector3f(1f, 0.67f, 0f), 0.8f);

    public static void showParticles(ServerPlayerEntity player, PatrolData patrol) {
        if (patrol.getWaypoints().isEmpty()) return;
        // Only show particles if the player is in the same dimension as the patrol
        ServerWorld world = player.getServerWorld();
        if (!world.getRegistryKey().getValue().toString().equals(patrol.getDimension())) return;

        List<BlockPos> wps = patrol.getWaypoints();
        int last = wps.size() - 1;

        for (int i = 0; i < wps.size(); i++) {
            BlockPos from = wps.get(i);

            // Waypoint marker — green=first, red=last, blue=intermediate
            DustParticleEffect dust;
            if (i == 0) dust = DUST_GREEN;
            else if (i == last) dust = DUST_RED;
            else dust = DUST_BLUE;

            world.spawnParticles(player, dust, true,
                    from.getX() + 0.5, from.getY() + 1.5, from.getZ() + 0.5,
                    8, 0.15, 0.15, 0.15, 0.01);

            // Line to next waypoint (loop back to first)
            BlockPos to = wps.get((i + 1) % wps.size());
            double dx = to.getX() - from.getX();
            double dy = to.getY() - from.getY();
            double dz = to.getZ() - from.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int steps = Math.max(1, (int) (dist * 2));

            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                world.spawnParticles(player, DUST_LINE, true,
                        from.getX() + 0.5 + dx * t,
                        from.getY() + 1.0 + dy * t,
                        from.getZ() + 0.5 + dz * t,
                        1, 0, 0, 0, 0);
            }
        }
    }
}
