package com.cheesefixquests.mixin;

import com.cheesefixquests.CheeseFixQuestsConfig;
import com.cheesefixquests.CheeseFixQuestsMod;
import com.cheesefixquests.QuestMigration;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.event.PlayerLoggedInAfterTeamEvent;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;

/**
 * Mixin 4: Fixes ServerQuestFile.playerLoggedIn() to use the PlayerTeam
 * instead of the party team. Without this, the initial data sync on login
 * sends the party team's data to the client, causing:
 * - Edit mode not working (canEdit flag set on wrong TeamData)
 * - Client showing stale party data instead of individual progress
 *
 * Also triggers auto-migration on first player login.
 */
@Mixin(value = ServerQuestFile.class, remap = false)
public abstract class ServerQuestFileMixin {

    /**
     * Auto-migrate party quest data to individual PlayerTeams on first login.
     * Runs once — sets migrationDone flag after completion.
     */
    @Inject(method = "playerLoggedIn", at = @At("HEAD"))
    private void autoMigrateOnFirstLogin(PlayerLoggedInAfterTeamEvent event, CallbackInfo ci) {
        if (!CheeseFixQuestsConfig.migrationDone && CheeseFixQuestsConfig.forceIndividualProgress) {
            int count = QuestMigration.migrateAll();
            CheeseFixQuestsConfig.migrationDone = true;
            CheeseFixQuestsConfig.save();
            CheeseFixQuestsMod.LOGGER.info("[CheeseFixQuests] Auto-migration complete: {} player(s) migrated", count);
        }
    }

    /**
     * Redirect event.getTeam() to return the player's permanent PlayerTeam
     * instead of their party team. This ensures the correct TeamData is
     * loaded and synced to the client on login.
     */
    @Redirect(
            method = "playerLoggedIn",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftbteams/api/event/PlayerLoggedInAfterTeamEvent;getTeam()Ldev/ftb/mods/ftbteams/api/Team;"
            )
    )
    private Team forcePlayerTeamOnLogin(PlayerLoggedInAfterTeamEvent event) {
        if (CheeseFixQuestsConfig.forceIndividualProgress) {
            UUID playerId = event.getPlayer().getUuid();
            Optional<Team> playerTeam = FTBTeamsAPI.api().getManager()
                    .getPlayerTeamForPlayerID(playerId);
            if (playerTeam.isPresent()) {
                if (CheeseFixQuestsConfig.debugLogging) {
                    CheeseFixQuestsMod.LOGGER.info("[CheeseFixQuests] playerLoggedIn: forced PlayerTeam for {}",
                            event.getPlayer().getName().getString());
                }
                return playerTeam.get();
            }
        }
        return event.getTeam();
    }

    /**
     * Fix isPlayerOnTeam() — intercept at HEAD to bypass the party team check.
     * Uses @Inject instead of @Redirect for reliability (avoids invokeinterface
     * matching issues). Resolves the PlayerTeam and compares its UUID with the
     * TeamData's UUID. Without this, setCanEdit/getCanEdit fails.
     */
    @Inject(method = "isPlayerOnTeam", at = @At("HEAD"), cancellable = true)
    private void forcePlayerTeamCheck(PlayerEntity player, TeamData teamData, CallbackInfoReturnable<Boolean> cir) {
        if (CheeseFixQuestsConfig.forceIndividualProgress) {
            UUID playerId = player.getUuid();
            Optional<Team> playerTeam = FTBTeamsAPI.api().getManager()
                    .getPlayerTeamForPlayerID(playerId);
            if (playerTeam.isPresent()) {
                cir.setReturnValue(playerTeam.get().getTeamId().equals(teamData.getTeamId()));
            }
        }
    }
}
