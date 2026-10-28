package com.cheesecompat.cobbleboss;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a vanilla-Warden-style {@code DARKNESS} aura to every non-spectator
 * non-creative player within {@value #RADIUS} blocks of any active CobbleWarden
 * boss Pokemon (entities tagged {@code cheesecompat_boss}).
 *
 * <p>Cadence: refreshed every {@value #APPLY_INTERVAL_TICKS} ticks (1s). Each
 * application lasts {@value #EFFECT_DURATION_TICKS} ticks (3s), so it persists
 * continuously while the player stays in range and fades on its own a few
 * seconds after they leave — no infinite duration, no cleanup tracking.</p>
 */
public final class BossDarknessAura {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");

    private static final int RADIUS = 50;
    private static final int APPLY_INTERVAL_TICKS = 20;
    private static final int EFFECT_DURATION_TICKS = 60;

    private static volatile boolean registered = false;

    private BossDarknessAura() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % APPLY_INTERVAL_TICKS != 0) return;
            for (ServerWorld world : server.getWorlds()) {
                java.util.List<ServerPlayerEntity> players = world.getPlayers();
                if (players.isEmpty()) continue;
                double rSq = (double) RADIUS * RADIUS;
                for (ServerPlayerEntity p : players) {
                    if (p.isSpectator() || p.isCreative()) continue;
                    Box area = new Box(
                        p.getX() - RADIUS, p.getY() - RADIUS, p.getZ() - RADIUS,
                        p.getX() + RADIUS, p.getY() + RADIUS, p.getZ() + RADIUS
                    );
                    boolean inRange = false;
                    for (Entity e : world.getOtherEntities(null, area,
                            ent -> ent.getCommandTags().contains("cheesecompat_boss"))) {
                        if (p.squaredDistanceTo(e) <= rSq) { inRange = true; break; }
                    }
                    if (!inRange) continue;
                    p.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.DARKNESS,
                        EFFECT_DURATION_TICKS,
                        0,
                        false,  // ambient
                        false,  // showParticles
                        true    // showIcon
                    ));
                }
            }
        });
        LOGGER.info("[CheeseCompat] BossDarknessAura registered (radius={} blocks, refresh={}t)", RADIUS, APPLY_INTERVAL_TICKS);
    }
}
