package com.cheesecompat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.lang.reflect.Method;

/**
 * EnhancedCelestials matérialise sa donnée lunaire par-niveau de façon
 * <em>lazy</em> : la factory n'est appelée qu'au premier accès via
 * {@code EnhancedCelestials.lunarForecastWorldData(level)}, lui-même invoqué
 * uniquement par {@code LivingEntity#tick}, le natural mob spawner et quelques
 * mixins de bloc/slime.
 * <p>
 * Conséquence : tant qu'aucun joueur n'a visité une dimension <em>et</em>
 * qu'aucun mob n'y a tické, le bloc {@code LunarForecastHologram} de StarAcademy
 * affiche du vide ({@code Optional.empty()}), et aucun évènement lunaire ne se
 * déclenche. Sur les dimensions CheeseGuard créées dynamiquement et rarement
 * visitées (ex {@code cheeseguard:bases}, {@code cheeseguard:terres_sauvages}),
 * la dim "n'existe pas" pour EC tant qu'un humain ne s'y est pas téléporté.
 * <p>
 * Ce hook amorce la factory pour <em>chaque</em> level chargé par le serveur,
 * dès qu'il est disponible. Soft-dep : si EnhancedCelestials est absent, le
 * listener est juste no-op.
 */
public final class EnhancedCelestialsWarmup {

    private EnhancedCelestialsWarmup() {}

    public static void register() {
        if (!FabricLoader.getInstance().isModLoaded("enhancedcelestials")) return;

        Method method;
        try {
            Class<?> ecClass = Class.forName("dev.corgitaco.enhancedcelestials.EnhancedCelestials");
            method = ecClass.getMethod("lunarForecastWorldData", World.class);
        } catch (Throwable t) {
            CheeseCompatMod.LOGGER.warn("[CheeseCompat] EC warmup disabled (signature mismatch): {}", t.getMessage());
            return;
        }

        ServerWorldEvents.LOAD.register((server, world) -> warmup(method, world));
        CheeseCompatMod.LOGGER.info("[CheeseCompat] EnhancedCelestials lunar warmup registered");
    }

    private static void warmup(Method method, ServerWorld world) {
        try {
            // L'appel matérialise la donnée DataAnchor pour ce level. La valeur retournée
            // n'est pas utilisée — on s'en sert juste pour amorcer la factory.
            method.invoke(null, world);
            CheeseCompatMod.LOGGER.info("[CheeseCompat] Warmed lunar data for {}", world.getRegistryKey().getValue());
        } catch (Throwable t) {
            // Silencieux par dimension : si une dim particulière n'a pas de
            // dimension_settings (c.-à-d. n'est pas censée recevoir d'évènements
            // lunaires), la factory peut renvoyer null sans erreur — c'est OK.
            CheeseCompatMod.LOGGER.debug("[CheeseCompat] Lunar warmup skipped for {}: {}",
                    world.getRegistryKey().getValue(), t.getMessage());
        }
    }
}
