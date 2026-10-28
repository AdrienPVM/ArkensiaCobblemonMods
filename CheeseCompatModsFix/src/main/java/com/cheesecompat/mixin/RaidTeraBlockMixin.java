package com.cheesecompat.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Blocks Terastallization during Cobblemon Raid Den battles.
 * Injects at the tail of ShowdownActionRequest.sanitize() to strip the tera option
 * when the battle takes place in a raid dimension.
 */
@Mixin(targets = "com.cobblemon.mod.common.battles.ShowdownActionRequest", remap = false)
public class RaidTeraBlockMixin {

    @Shadow
    private List<ShowdownMoveset> active;

    private static Method cheesecompat$getPlayers;

    @Inject(method = "sanitize", at = @At("TAIL"))
    private void cheesecompat$blockTeraInRaid(PokemonBattle battle, BattleActor battleActor, CallbackInfo ci) {
        if (this.active == null) return;

        try {
            if (cheesecompat$getPlayers == null) {
                for (Method m : battle.getClass().getMethods()) {
                    if (m.getName().equals("getPlayers") && m.getParameterCount() == 0) {
                        cheesecompat$getPlayers = m;
                        break;
                    }
                }
                if (cheesecompat$getPlayers == null) return;
            }

            List<?> players = (List<?>) cheesecompat$getPlayers.invoke(battle);
            boolean isRaid = false;
            for (Object obj : players) {
                ServerPlayerEntity player = (ServerPlayerEntity) obj;
                String dimId = player.getServerWorld().getRegistryKey().getValue().toString();
                if (dimId.contains("cobblemonraiddens")) {
                    isRaid = true;
                    break;
                }
            }

            if (isRaid) {
                for (ShowdownMoveset moveset : this.active) {
                    moveset.setCanTerastallize(null);
                }
            }
        } catch (Exception ignored) {
            // Silently fail — don't break the battle
        }
    }
}
