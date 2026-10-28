package com.cheesecompat.mixin;

import com.cheesecompat.daycare.DaycareSimpleModeStore;
import com.provismet.cobblemon.daycareplus.breeding.PastureExtension;
import com.provismet.cobblemon.daycareplus.breeding.PotentialPokemonProperties;
import net.minecraft.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Gates egg production by the simple-mode toggle stored in DaycareSimpleModeStore.
 *
 *  - In tick(), redirects the predictEgg() call so that when simpleMode is on
 *    and simpleArmed is false, it returns Optional.empty().
 *  - On produceEgg TAIL, if simpleMode is on, disarms the pasture.
 *
 * Uses reflection to read the private blockEntity field — importing
 * PokemonPastureBlockEntity would force javac to resolve intermediary MC
 * supertypes (class_2586) that aren't on the compile classpath.
 */
@Mixin(value = PastureExtension.class, remap = false)
public abstract class DaycareSimpleModeTickMixin {

    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE",
            target = "Lcom/provismet/cobblemon/daycareplus/breeding/PastureExtension;predictEgg()Ljava/util/Optional;")
    )
    private Optional<PotentialPokemonProperties> cheesecompat$gatePredictEgg(PastureExtension self) {
        Object be = cheesecompat$getBlockEntity(self);
        if (be instanceof BlockEntity blockEntity && blockEntity.getWorld() != null) {
            String dimId = blockEntity.getWorld().getRegistryKey().getValue().toString();
            long posLong = blockEntity.getPos().asLong();
            if (DaycareSimpleModeStore.isSimpleMode(dimId, posLong)
                    && !DaycareSimpleModeStore.isArmed(dimId, posLong)) {
                return Optional.empty();
            }
        }
        return self.predictEgg();
    }

    @Inject(method = "produceEgg", at = @At("TAIL"))
    private void cheesecompat$disarmAfterEgg(PotentialPokemonProperties potentialEgg, CallbackInfo ci) {
        PastureExtension self = (PastureExtension) (Object) this;
        Object be = cheesecompat$getBlockEntity(self);
        if (be instanceof BlockEntity blockEntity && blockEntity.getWorld() != null) {
            String dimId = blockEntity.getWorld().getRegistryKey().getValue().toString();
            long posLong = blockEntity.getPos().asLong();
            if (DaycareSimpleModeStore.isSimpleMode(dimId, posLong)) {
                DaycareSimpleModeStore.setArmed(dimId, posLong, false);
                blockEntity.markDirty();
            }
        }
    }

    private static Object cheesecompat$getBlockEntity(PastureExtension ext) {
        try {
            Field f = ext.getClass().getDeclaredField("blockEntity");
            f.setAccessible(true);
            return f.get(ext);
        } catch (Exception e) {
            return null;
        }
    }
}
