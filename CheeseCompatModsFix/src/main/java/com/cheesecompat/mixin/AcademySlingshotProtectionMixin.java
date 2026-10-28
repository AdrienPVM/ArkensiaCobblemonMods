package com.cheesecompat.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Cancels the slingshot's onEntityHit when the target is an uncatchable
 * Cobblemon Pokemon (e.g., Giratina boss spawned by cobblewarden_boss).
 *
 * Approach: @Inject at HEAD with cancellable, all types are standard Minecraft
 * (EntityHitResult, Entity) — no PokemonEntity in the handler signature. The
 * uncatchable check is performed via reflection so javac never has to resolve
 * PokemonEntity's intermediary Minecraft supertypes (class_1471, class_5147).
 */
@Mixin(targets = "abeshutt.staracademy.entity.SlingshotEntity", remap = false)
public class AcademySlingshotProtectionMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");
    private static volatile Class<?> pokemonEntityClass;
    private static volatile Method getPokemonMethod;
    private static volatile Method isUncatchableMethod;
    private static volatile boolean reflectionReady = false;

    private static synchronized void initReflection() {
        if (reflectionReady) return;
        try {
            pokemonEntityClass = Class.forName("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
            Class<?> pokemonClass = Class.forName("com.cobblemon.mod.common.pokemon.Pokemon");
            getPokemonMethod = pokemonEntityClass.getMethod("getPokemon");
            isUncatchableMethod = pokemonClass.getMethod("isUncatchable");
        } catch (Throwable t) {
            LOGGER.error("[CheeseCompat] Failed to init slingshot reflection", t);
        }
        reflectionReady = true;
    }

    @Inject(method = "method_7454", at = @At("HEAD"), cancellable = true, remap = false)
    private void cheesecompat$blockOnUncatchable(EntityHitResult hitResult, CallbackInfo ci) {
        initReflection();
        if (pokemonEntityClass == null || getPokemonMethod == null || isUncatchableMethod == null) return;
        try {
            Entity entity = hitResult.getEntity();
            if (entity == null || !pokemonEntityClass.isInstance(entity)) return;
            Object pk = getPokemonMethod.invoke(entity);
            if (pk != null && Boolean.TRUE.equals(isUncatchableMethod.invoke(pk))) {
                LOGGER.info("[CheeseCompat] Slingshot hit cancelled for uncatchable Pokemon");
                ci.cancel();
            }
        } catch (Throwable t) {
            LOGGER.warn("[CheeseCompat] Slingshot uncatchable check failed", t);
        }
    }
}
