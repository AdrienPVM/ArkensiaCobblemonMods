package com.cheesecompat.mixin;

import com.cobblemon.mod.common.pokemon.Nature;
import com.lemenok.cobblemontrialsedition.config.SpawnablePokemonProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Method;

/**
 * Fixes CobblemonTrialsEdition calling Natures.getNature() as a static method.
 *
 * In Cobblemon 1.7.x, Natures methods are @JvmStatic.
 * In Cobblemon 1.6.1, they are instance methods on the Kotlin singleton (Natures.INSTANCE).
 * CTE compiled against 1.7.x uses invokestatic, causing IncompatibleClassChangeError on 1.6.1.
 */
@Mixin(value = SpawnablePokemonProperties.class, remap = false)
public class TrialsNatureMixin {

    @Unique private static Object cheesecompat$naturesInstance;
    @Unique private static Method cheesecompat$getNature;
    @Unique private static Method cheesecompat$getRandomNature;

    /**
     * @author CheeseCompatModsFix
     * @reason Use Natures.INSTANCE via reflection for Cobblemon 1.6.x compatibility
     */
    @Overwrite
    private static Nature parseNature(String nature) {
        try {
            if (cheesecompat$naturesInstance == null) {
                Class<?> naturesClass = Class.forName("com.cobblemon.mod.common.api.pokemon.Natures");
                cheesecompat$naturesInstance = naturesClass.getField("INSTANCE").get(null);
                for (Method m : naturesClass.getDeclaredMethods()) {
                    if (m.getName().equals("getNature") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == String.class) {
                        cheesecompat$getNature = m;
                        cheesecompat$getNature.setAccessible(true);
                    }
                    if (m.getName().equals("getRandomNature") && m.getParameterCount() == 0) {
                        cheesecompat$getRandomNature = m;
                        cheesecompat$getRandomNature.setAccessible(true);
                    }
                }
            }
            if (cheesecompat$getNature != null) {
                Object result = cheesecompat$getNature.invoke(cheesecompat$naturesInstance, nature);
                if (result != null) return (Nature) result;
            }
            if (cheesecompat$getRandomNature != null) {
                return (Nature) cheesecompat$getRandomNature.invoke(cheesecompat$naturesInstance);
            }
        } catch (Exception e) {
            com.cheesecompat.CheeseCompatMod.LOGGER.error("[CheeseCompat] Failed to resolve nature '{}': {}", nature, e.getMessage());
        }
        return null;
    }
}
