package com.cheesefixquests.mixin;

import com.cheesefixquests.CheeseFixQuestsConfig;
import com.cheesefixquests.CheeseFixQuestsMod;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.UUID;

/**
 * Mixin 1: Forces FTB Quests to resolve the player's permanent PlayerTeam
 * instead of their party team. Makes ALL quest progress individual.
 */
@Mixin(value = BaseQuestFile.class)
public abstract class BaseQuestFileMixin {

    @Redirect(
            method = "getTeamData(Lnet/minecraft/entity/player/PlayerEntity;)Ljava/util/Optional;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftbteams/api/TeamManager;getTeamForPlayerID(Ljava/util/UUID;)Ljava/util/Optional;"
            )
    )
    private Optional<Team> forcePlayerTeamInGetTeamData(TeamManager manager, UUID playerId) {
        return resolvePlayerTeam(manager, playerId);
    }

    @Redirect(
            method = "getOrCreateTeamData(Lnet/minecraft/entity/Entity;)Ldev/ftb/mods/ftbquests/quest/TeamData;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftbteams/api/TeamManager;getTeamForPlayerID(Ljava/util/UUID;)Ljava/util/Optional;"
            )
    )
    private Optional<Team> forcePlayerTeamInGetOrCreate(TeamManager manager, UUID playerId) {
        return resolvePlayerTeam(manager, playerId);
    }

    private Optional<Team> resolvePlayerTeam(TeamManager manager, UUID playerId) {
        if (CheeseFixQuestsConfig.forceIndividualProgress) {
            Optional<Team> playerTeam = manager.getPlayerTeamForPlayerID(playerId);
            if (playerTeam.isPresent()) {
                if (CheeseFixQuestsConfig.debugLogging) {
                    CheeseFixQuestsMod.LOGGER.info("[CheeseFixQuests] Resolved PlayerTeam for {}", playerId);
                }
                return playerTeam;
            }
        }
        return manager.getTeamForPlayerID(playerId);
    }
}
