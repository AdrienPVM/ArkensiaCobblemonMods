package com.ace.cobbleboss;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class BossSpawner {
    private static final Logger LOGGER = LoggerFactory.getLogger("cobblewarden_boss");

    private static volatile boolean apiInit = false;
    private static Object companion;
    private static Method parseMethod;
    private static Method createEntityMethod;

    private static synchronized void initApi() {
        if (apiInit) return;
        try {
            Class<?> propsClass = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonProperties");
            Field companionField = propsClass.getField("Companion");
            companion = companionField.get(null);
            Class<?> companionClass = companion.getClass();
            parseMethod = companionClass.getMethod("parse", String.class);
            for (Method m : propsClass.getDeclaredMethods()) {
                if (m.getName().equals("createEntity") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(World.class)) {
                    createEntityMethod = m;
                    break;
                }
            }
            if (createEntityMethod == null) {
                throw new NoSuchMethodException("createEntity(World) not found on PokemonProperties");
            }
        } catch (Throwable t) {
            LOGGER.error("[CobblewardenPatch] Failed to init Cobblemon API", t);
        }
        apiInit = true;
    }

    public static void spawnBoss(World level, BlockPos pos) {
        if (!(level instanceof ServerWorld serverLevel)) {
            LOGGER.warn("[CobblewardenPatch] Cannot spawn boss Pokemon on client side");
            return;
        }
        String species = determineSpecies(serverLevel, pos);
        spawnBoss(level, pos, species);
    }

    public static void spawnBoss(World level, BlockPos pos, String species) {
        if (!(level instanceof ServerWorld serverLevel)) {
            LOGGER.warn("[CobblewardenPatch] Cannot spawn boss Pokemon on client side");
            return;
        }
        initApi();
        if (companion == null || parseMethod == null || createEntityMethod == null) {
            LOGGER.error("[CobblewardenPatch] Cobblemon API unavailable, aborting {} spawn", species);
            return;
        }
        try {
            String propsString = buildPropsString(species);
            Object props = parseMethod.invoke(companion, propsString);
            Object entity = createEntityMethod.invoke(props, level);
            if (!(entity instanceof Entity mcEntity)) {
                LOGGER.error("[CobblewardenPatch] createEntity returned non-Entity: {}", entity);
                return;
            }
            mcEntity.refreshPositionAndAngles(
                pos.getX() + 0.5,
                pos.getY() + 1.0,
                pos.getZ() + 0.5,
                serverLevel.random.nextFloat() * 360f,
                0f
            );
            try { mcEntity.addCommandTag("cheesecompat_boss"); } catch (Throwable ignored) {}
            boolean ok = serverLevel.spawnEntity(mcEntity);
            if (ok) {
                LOGGER.info("[CobblewardenPatch] Spawned {} boss at {}", species, pos);
                tryAggroNearestPlayer(serverLevel, mcEntity, pos);
            } else {
                LOGGER.warn("[CobblewardenPatch] spawnEntity returned false for {} at {}", species, pos);
            }
        } catch (Throwable t) {
            LOGGER.error("[CobblewardenPatch] Failed to spawn {} boss at {}", species, pos, t);
        }
    }

    private static void tryAggroNearestPlayer(ServerWorld world, Entity entity, BlockPos pos) {
        try {
            if (!(entity instanceof net.minecraft.entity.mob.MobEntity mob)) {
                LOGGER.info("[CobblewardenPatch] Boss is not a MobEntity ({}), skipping aggro", entity.getClass().getName());
                return;
            }
            net.minecraft.entity.player.PlayerEntity player = world.getClosestPlayer(
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 64.0, false
            );
            if (player == null) {
                LOGGER.info("[CobblewardenPatch] No player within 64 blocks for aggro at {}", pos);
                return;
            }
            mob.setTarget(player);
            LOGGER.info("[CobblewardenPatch] Boss targeting player {} (distance={})",
                player.getName().getString(), Math.sqrt(mob.squaredDistanceTo(player)));
        } catch (Throwable t) {
            LOGGER.warn("[CobblewardenPatch] Aggro attempt failed", t);
        }
    }

    private static String buildPropsString(String species) {
        return switch (species) {
            case "giratina" -> "giratina level=100 nature=modest special_attack_ev=252 hp_ev=252 special_attack_iv=31 hp_iv=31 moves=shadowball,hex,aurasphere,earthpower held_item=cobblemon:leftovers uncatchable=true";
            // Same moveset as Giratina (the boss the user validated as working perfectly).
            // Visual swap only — same level/nature/EVs/IVs/moves/item.
            case "darkrai" -> "darkrai level=100 nature=modest special_attack_ev=252 hp_ev=252 special_attack_iv=31 hp_iv=31 moves=shadowball,hex,aurasphere,earthpower held_item=cobblemon:leftovers uncatchable=true";
            // Kept for backwards-compat / explicit calls; no longer the default overworld pick.
            case "exploud" -> "exploud level=100 nature=modest special_attack_ev=252 hp_ev=252 special_attack_iv=31 hp_iv=31 moves=boomburst,hypervoice,focusblast,icebeam held_item=cobblemon:leftovers uncatchable=true";
            case "guzzlord" -> "guzzlord level=100 nature=adamant attack_ev=252 hp_ev=252 attack_iv=31 hp_iv=31 moves=dragonclaw,knockoff,drainpunch,stoneedge held_item=cobblemon:leftovers uncatchable=true";
            default -> species + " level=100 uncatchable=true";
        };
    }

    /**
     * Boss species selection — Giratina ONLY (per user request 2026-05-04).
     *
     * <p>Earlier iterations branched on biome/Y to choose between Giratina,
     * Darkrai or Exploud. Reverted to a single species: every CobbleWarden
     * spawn yields a Giratina, regardless of biome or altitude. The
     * {@code level} and {@code pos} parameters are kept for signature
     * compatibility with {@link #spawnBoss(World, BlockPos)}.</p>
     */
    @SuppressWarnings("unused")
    private static String determineSpecies(ServerWorld level, BlockPos pos) {
        return "giratina";
    }
}
