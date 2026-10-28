package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.EventParticipant;
import com.cheeseguard.data.ServerEvent;
import com.cheeseguard.logic.CobblemonHook;
import com.cheeseguard.logic.EventScoreHook;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FishingBobberEntity.class)
public abstract class EventFishCatchMixin {

    @Shadow public abstract net.minecraft.entity.player.PlayerEntity getPlayerOwner();
    @Shadow public abstract net.minecraft.entity.Entity getHookedEntity();

    @Inject(method = "use", at = @At("RETURN"), require = 0)
    private void cg_onFishCatch(ItemStack usedItem, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValue() <= 0) return;
        if (!(getPlayerOwner() instanceof ServerPlayerEntity player)) return;

        // ── Guard: only genuine water-fishing catches ─────────────────────
        // Skip if an entity (player, mob) is on the hook.
        if (getHookedEntity() != null) return;
        // The bobber must be touching water.
        // A bobber resting on a lily pad block is NOT in water (returns false),
        // preventing fake Pokémon spawns when clicking a nénuphar.
        if (!((FishingBobberEntity)(Object)this).isTouchingWater()) return;

        // Check if player is in an ARCADE fishing event
        boolean inArcade = false;
        var em = CheeseGuardMod.getInstance().getEventManager();
        if (em != null) {
            for (ServerEvent event : em.getEvents()) {
                if (!event.isActive()) continue;
                if (!"ARCADE".equals(event.getFishingMode())) continue;
                EventParticipant p = event.getParticipant(player.getUuid());
                if (p != null && p.getRole() == EventParticipant.Role.PARTICIPANT) {
                    inArcade = true;
                    break;
                }
            }
        }

        // Standard FISH_CATCH scoring — SKIP if in ARCADE mode
        if (!inArcade) {
            EventScoreHook.check(player, ServerEvent.ScoreTrigger.TriggerType.FISH_CATCH);
        }

        // ARCADE mode: roll a random pokemon and give points
        CobblemonHook.onVanillaFishCatch(player);

        // REALISTIC mode fallback
        CobblemonHook.trackPotentialFishEncounter(player);
    }
}
