package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Allows Nether portals to form in CheeseGuard custom dimensions.
 *
 * Why: vanilla AbstractFireBlock.isOverworldOrNether(World) compares the world RegistryKey
 * directly against World.OVERWORLD / World.NETHER. CheeseGuard custom dims have their own
 * RegistryKey (cheeseguard:xxx) even when their DimensionType is OVERWORLD, so the gate
 * fails and:
 *   - shouldLightPortalAt() rejects flint-and-steel placement on obsidian frames
 *   - onBlockAdded() never calls NetherPortal.getNewPortal(), so even if fire is placed
 *     manually, no portal blocks ever appear.
 *
 * We force this method to return true for our managed dims so both call sites work.
 */
@Mixin(AbstractFireBlock.class)
public class AbstractFireBlockCustomDimMixin {

    @Inject(method = "isOverworldOrNether", at = @At("HEAD"), cancellable = true)
    private static void cheeseguard$allowPortalInCustomDim(World world, CallbackInfoReturnable<Boolean> cir) {
        if (world.isClient()) return;
        var mod = CheeseGuardMod.getInstance();
        if (mod == null || mod.getDimensionManager() == null) return;
        String worldId = world.getRegistryKey().getValue().toString();
        var config = mod.getDimensionManager().getConfigByWorldId(worldId);
        if (config == null || config.isBuiltIn()) return;
        cir.setReturnValue(true);
    }
}
