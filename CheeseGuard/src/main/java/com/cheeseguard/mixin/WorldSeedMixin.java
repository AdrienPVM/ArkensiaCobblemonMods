package com.cheeseguard.mixin;

import com.cheeseguard.logic.SeedRegistry;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerWorld.class)
public class WorldSeedMixin {

    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void cheeseguard$customSeed(CallbackInfoReturnable<Long> cir) {
        ServerWorld self = (ServerWorld) (Object) this;
        Long override = SeedRegistry.get(self.getRegistryKey());
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
