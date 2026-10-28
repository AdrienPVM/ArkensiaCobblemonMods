package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class EventPlayerDeathMixin {

    @Inject(method = "onDeath", at = @At("HEAD"))
    private void onPlayerDeath(DamageSource source, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity)(Object) this;
        if (self.getWorld().isClient()) return;

        var mod = CheeseGuardMod.getInstance();
        if (mod == null) return;

        // Event death handling: saves inventory + clears so items don't drop
        if (mod.getEventManager() != null) {
            mod.getEventManager().onParticipantDeath(self);
        }

        // Dimension keepInventory: save inventory so nothing drops, restore on respawn
        if (mod.getDimensionManager() != null) {
            String worldId = self.getServerWorld().getRegistryKey().getValue().toString();
            if (mod.getDimensionManager().isKeepInventory(worldId)) {
                mod.getDimensionManager().onPlayerDeathKeepInv(self);
            }
        }
    }
}
