package com.sebastian.cobblemondungeons.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class PokemonEntityMixin {

    @Inject(method = "calculateDimensions", at = @At("TAIL"))
    private void onRefreshDimensions(CallbackInfo ci) {
        if (!((Object)this instanceof PokemonEntity pokemonEntity)) return;
        if (pokemonEntity.getPokemon() == null) return;

        float customScale = 0;
        NbtCompound persistentData = pokemonEntity.getPokemon().getPersistentData();
        if (persistentData.contains("CustomScale")) {
            customScale = persistentData.getFloat("CustomScale");
        }
        
        // Fallback to aspects if NBT is missing
        if (customScale <= 0) {
            for (String aspect : pokemonEntity.getPokemon().getAspects()) {
                if (aspect.startsWith("scale:")) {
                    try {
                        customScale = Float.parseFloat(aspect.substring(6));
                        break;
                    } catch (Exception ignored) {}
                }
            }
        }

        if (customScale > 0) {
            // Force Cobblemon visual scale modifier to customScale
            try {
                if (Math.abs(pokemonEntity.getDataTracker().get(com.cobblemon.mod.common.entity.pokemon.PokemonEntity.getSCALE_MODIFIER()) - customScale) > 0.001f) {
                    pokemonEntity.getDataTracker().set(com.cobblemon.mod.common.entity.pokemon.PokemonEntity.getSCALE_MODIFIER(), customScale);
                }
            } catch (Exception ignored) {}

            // Ensure GENERIC_SCALE is 1.0 to avoid double scaling
            var scaleAttr = pokemonEntity.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_SCALE);
            if (scaleAttr != null && Math.abs(scaleAttr.getBaseValue() - 1.0f) > 0.001) {
                scaleAttr.setBaseValue(1.0f);
            }
        }
    }
}
