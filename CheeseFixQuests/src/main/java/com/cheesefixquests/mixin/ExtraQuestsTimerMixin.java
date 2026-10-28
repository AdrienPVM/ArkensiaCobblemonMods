package com.cheesefixquests.mixin;

import com.cheesefixquests.CheeseFixQuestsConfig;
import com.cheesefixquests.CheeseFixQuestsMod;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.UUID;

/**
 * Mixin 4: Fixes ExtraQuests timer resets targeting the wrong TeamData.
 *
 * ExtraQuests' Utils.questReset() calls getTeamForPlayerID(UUID) which returns
 * the party team (effective team) when the player is in a party. The reset then
 * operates on the party's TeamData which is EMPTY — CheeseFixQuests stores all
 * progress on the individual PlayerTeam.
 *
 * This redirect ensures the timer reset finds the PlayerTeam's TeamData where
 * the actual quest progress lives, so daily quests reset correctly.
 */
@Mixin(value = com.vecoo.extraquests.util.Utils.class, remap = false)
public abstract class ExtraQuestsTimerMixin {

    @Redirect(
            method = "questReset",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftbteams/api/TeamManager;getTeamForPlayerID(Ljava/util/UUID;)Ljava/util/Optional;"
            )
    )
    private static Optional<Team> forcePlayerTeamOnReset(TeamManager manager, UUID playerId) {
        if (CheeseFixQuestsConfig.forceIndividualProgress) {
            Optional<Team> playerTeam = manager.getPlayerTeamForPlayerID(playerId);
            if (playerTeam.isPresent()) {
                if (CheeseFixQuestsConfig.debugLogging) {
                    CheeseFixQuestsMod.LOGGER.info(
                            "[CheeseFixQuests] ExtraQuests timer reset: forced PlayerTeam for {}", playerId);
                }
                return playerTeam;
            }
        }
        return manager.getTeamForPlayerID(playerId);
    }
}
