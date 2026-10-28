package com.cheesecompat.cobbleboss;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every 2s, for each active shrieker cooldown center, locate spawned boss
 * Pokemon (uncatchable) in range and force-retarget the nearest living player.
 * This keeps shrieker-summoned bosses permanently aggressive even after the
 * original target dies or respawns.
 */
public final class ShriekerBossAggroKeeper {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");

    private static volatile boolean registered = false;
    private static volatile boolean cobblemonChecked = false;
    private static Class<?> pokemonEntityClass;
    private static final ConcurrentHashMap<UUID, Long> LAST_TELEPORT_MS = new ConcurrentHashMap<>();

    private ShriekerBossAggroKeeper() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 != 0) return; // every 20 ticks (~1s)
            if (ShriekerBossState.COOLDOWN_UNTIL.isEmpty()) return;
            long now = System.currentTimeMillis();
            initCobblemon();

            for (Map.Entry<Long, Long> e : ShriekerBossState.COOLDOWN_UNTIL.entrySet()) {
                if (e.getValue() <= now) continue;
                int cx = ShriekerBossState.unpackX(e.getKey());
                int cz = ShriekerBossState.unpackZ(e.getKey());
                for (ServerWorld world : server.getWorlds()) {
                    Box area = new Box(
                        cx - ShriekerBossState.AGGRO_SCAN_RADIUS, world.getBottomY(),
                        cz - ShriekerBossState.AGGRO_SCAN_RADIUS,
                        cx + ShriekerBossState.AGGRO_SCAN_RADIUS, world.getTopY(),
                        cz + ShriekerBossState.AGGRO_SCAN_RADIUS
                    );
                    for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, area, m -> isShriekerBoss(m))) {
                        LivingEntity cur = mob.getTarget();
                        boolean targetInvalid = cur == null || !cur.isAlive() || cur.isRemoved() || cur.isSpectator();

                        if (targetInvalid) {
                            PlayerEntity p = world.getClosestPlayer(
                                mob.getX(), mob.getY(), mob.getZ(),
                                ShriekerBossState.AGGRO_TARGET_RADIUS, true
                            );
                            if (p != null && !p.isSpectator() && !p.isCreative()) {
                                mob.setTarget(p);
                                cur = p;
                            } else {
                                continue;
                            }
                        }

                        double dsq = mob.squaredDistanceTo(cur);
                        double minSq = (double) ShriekerBossState.TELEPORT_DIST_THRESHOLD * ShriekerBossState.TELEPORT_DIST_THRESHOLD;
                        double maxSq = (double) ShriekerBossState.TELEPORT_MAX_DIST * ShriekerBossState.TELEPORT_MAX_DIST;
                        if (dsq > minSq && dsq < maxSq) {
                            UUID id = mob.getUuid();
                            Long last = LAST_TELEPORT_MS.get(id);
                            if (last == null || now - last >= ShriekerBossState.TELEPORT_COOLDOWN_MS) {
                                try {
                                    double ang = mob.getRandom().nextDouble() * Math.PI * 2.0;
                                    double ox = Math.cos(ang) * 6.0;
                                    double oz = Math.sin(ang) * 6.0;
                                    double tx = cur.getX() + ox;
                                    double ty = cur.getY() + 1.0;
                                    double tz = cur.getZ() + oz;
                                    mob.requestTeleport(tx, ty, tz);
                                    LAST_TELEPORT_MS.put(id, now);
                                } catch (Throwable t) {
                                    LOGGER.debug("[CheeseCompat] Boss teleport failed: {}", t.getMessage());
                                }
                            }
                        }
                    }
                }
            }
        });
        LOGGER.info("[CheeseCompat] ShriekerBossAggroKeeper registered");
    }

    private static synchronized void initCobblemon() {
        if (cobblemonChecked) return;
        cobblemonChecked = true;
        try {
            pokemonEntityClass = Class.forName("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
        } catch (Throwable t) {
            LOGGER.info("[CheeseCompat] PokemonEntity class unavailable for aggro keeper");
        }
    }

    private static boolean isShriekerBoss(Entity e) {
        if (pokemonEntityClass == null) return false;
        if (!pokemonEntityClass.isInstance(e)) return false;
        // Our shrieker-spawned bosses are uncatchable; wild pokemon in deep dark are not.
        // Use scoreboard tag as a cheap filter where possible.
        return e.getCommandTags().contains("cheesecompat_boss") || isUncatchable(e);
    }

    private static volatile boolean uncatchableInit = false;
    private static java.lang.reflect.Method getPokemonMethod;
    private static java.lang.reflect.Method isUncatchableMethod;

    private static boolean isUncatchable(Entity e) {
        try {
            if (!uncatchableInit) {
                synchronized (ShriekerBossAggroKeeper.class) {
                    if (!uncatchableInit) {
                        uncatchableInit = true;
                        if (pokemonEntityClass != null) {
                            for (java.lang.reflect.Method m : pokemonEntityClass.getMethods()) {
                                if (m.getName().equals("getPokemon") && m.getParameterCount() == 0) {
                                    getPokemonMethod = m;
                                    break;
                                }
                            }
                            if (getPokemonMethod != null) {
                                Class<?> pokemonCls = getPokemonMethod.getReturnType();
                                for (java.lang.reflect.Method m : pokemonCls.getMethods()) {
                                    if (m.getParameterCount() == 0
                                        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)
                                        && m.getName().toLowerCase().contains("uncatchable")) {
                                        isUncatchableMethod = m;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (getPokemonMethod == null || isUncatchableMethod == null) return false;
            Object pokemon = getPokemonMethod.invoke(e);
            if (pokemon == null) return false;
            Object r = isUncatchableMethod.invoke(pokemon);
            return r instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }
}
