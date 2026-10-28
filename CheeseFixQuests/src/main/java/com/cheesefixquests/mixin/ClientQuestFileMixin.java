package com.cheesefixquests.mixin;

import com.cheesefixquests.CheeseFixQuestsConfig;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side fix for isPlayerOnTeam() in individual progress mode.
 *
 * On the client, isPlayerOnTeam() checks KnownClientPlayer.teamId() which
 * returns the party team UUID, but selfTeamData has the PlayerTeam UUID
 * (because ServerQuestFileMixin redirected the login sync to PlayerTeam).
 * This UUID mismatch causes getOrCreatePlayerData() to return empty,
 * which breaks setCanEdit/getCanEdit — making editing mode permanently disabled.
 *
 * Fix: when forceIndividualProgress is enabled and the TeamData is the player's
 * own selfTeamData, return true (the player is always "on" their own team).
 */
@Mixin(value = ClientQuestFile.class, remap = false)
public class ClientQuestFileMixin {

    @Shadow public TeamData selfTeamData;

    @Inject(method = "isPlayerOnTeam", at = @At("HEAD"), cancellable = true)
    private void forcePlayerTeamCheck(PlayerEntity player, TeamData teamData, CallbackInfoReturnable<Boolean> cir) {
        if (CheeseFixQuestsConfig.forceIndividualProgress && teamData == selfTeamData) {
            cir.setReturnValue(true);
        }
    }
}
