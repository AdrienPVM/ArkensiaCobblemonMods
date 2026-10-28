package com.cheesecompat.mixin;

import com.provismet.cobblemon.daycareplus.breeding.PastureExtension;
import com.provismet.cobblemon.daycareplus.breeding.PotentialPokemonProperties;
import com.provismet.cobblemon.daycareplus.config.DaycarePlusOptions;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Consumes Destiny Knot from each parent after egg production in competitive mode.
 *
 * DaycarePlus tags Destiny Knot only in `noncompetitive_breeding`, so the built-in
 * consume loop in produceEgg (which checks `competitive_breeding`) leaves it intact.
 * DaycareIVFixMixin restores vanilla IV inheritance in competitive mode — Destiny
 * Knot then transmits 5 IVs from both parents and should be consumed like in the
 * main-series games.
 *
 * Reflection is used for Cobblemon's Pokemon/CobblemonItems to avoid pulling
 * intermediary MC supertypes onto the javac classpath.
 */
@Mixin(value = PastureExtension.class, remap = false)
public abstract class DaycareDestinyKnotConsumeMixin {

    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompat");

    @Unique private static boolean cheesecompat$reflectionInited;
    @Unique private static Method cheesecompat$heldItemMethod;
    @Unique private static Method cheesecompat$swapHeldItemMethod;
    @Unique private static Object cheesecompat$destinyKnotItem;

    @Inject(method = "produceEgg", at = @At("TAIL"))
    private void cheesecompat$consumeDestinyKnot(PotentialPokemonProperties potential, CallbackInfo ci) {
        try {
            if (!DaycarePlusOptions.doCompetitiveBreeding()) return;
            if (!DaycarePlusOptions.shouldConsumeHeldItems()) return;
            cheesecompat$ensureReflection();
            if (cheesecompat$destinyKnotItem == null) return;
            cheesecompat$consumeIfKnot(potential.getPrimary());
            cheesecompat$consumeIfKnot(potential.getSecondary());
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] Destiny Knot consume skipped: {}", e.getMessage());
        }
    }

    @Unique
    private static void cheesecompat$ensureReflection() throws Exception {
        if (cheesecompat$reflectionInited) return;
        cheesecompat$reflectionInited = true;
        Class<?> pokemonClass = Class.forName("com.cobblemon.mod.common.pokemon.Pokemon");
        cheesecompat$heldItemMethod = pokemonClass.getMethod("heldItem");
        cheesecompat$swapHeldItemMethod = pokemonClass.getMethod("swapHeldItem", ItemStack.class, boolean.class);
        Class<?> cobblemonItems = Class.forName("com.cobblemon.mod.common.CobblemonItems");
        Field f = cobblemonItems.getField("DESTINY_KNOT");
        cheesecompat$destinyKnotItem = f.get(null);
    }

    @Unique
    private static void cheesecompat$consumeIfKnot(Object pokemon) throws Exception {
        if (pokemon == null) return;
        Object stack = cheesecompat$heldItemMethod.invoke(pokemon);
        if (!(stack instanceof ItemStack itemStack)) return;
        if (itemStack.isOf((Item) cheesecompat$destinyKnotItem)) {
            cheesecompat$swapHeldItemMethod.invoke(pokemon, ItemStack.EMPTY, true);
        }
    }
}
