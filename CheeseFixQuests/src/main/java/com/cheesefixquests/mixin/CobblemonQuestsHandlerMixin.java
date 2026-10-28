package com.cheesefixquests.mixin;

import com.cheesefixquests.CheeseFixQuestsConfig;
import com.cheesefixquests.CheeseFixQuestsMod;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

/**
 * Mixin 3: Forces cobblemon_quests event handlers to use the player's permanent
 * PlayerTeam instead of their party team.
 *
 * cobblemon_quests has its own getTeamData(ServerPlayerEntity) that calls
 * TeamManagerImpl.getTeamForPlayer() directly, bypassing BaseQuestFile.getTeamData().
 * This mixin ensures event-based actions (catch, defeat, evolve, trade, etc.)
 * also write progress to the individual PlayerTeam.
 *
 * The have_registered path (getTeamData(UUID) → getTeamByID(playerUUID))
 * already returns the PlayerTeam by coincidence, so no fix needed there.
 */
@Mixin(value = winterwolfsv.cobblemon_quests.events.CobblemonQuestsEventHandler.class, remap = false)
public abstract class CobblemonQuestsHandlerMixin {

    @Redirect(
            method = "getTeamData(Lnet/minecraft/class_3222;)Ldev/ftb/mods/ftbquests/quest/TeamData;",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftbteams/data/TeamManagerImpl;getTeamForPlayer(Lnet/minecraft/class_3222;)Ljava/util/Optional;"
            )
    )
    private Optional<Team> forcePlayerTeam(TeamManagerImpl manager, ServerPlayerEntity player) {
        if (CheeseFixQuestsConfig.forceIndividualProgress) {
            Optional<Team> playerTeam = manager.getPlayerTeamForPlayerID(player.getUuid());
            if (playerTeam.isPresent()) {
                if (CheeseFixQuestsConfig.debugLogging) {
                    CheeseFixQuestsMod.LOGGER.info("[CheeseFixQuests] cobblemon_quests: forced PlayerTeam for {}",
                            player.getName().getString());
                }
                return playerTeam;
            }
        }
        return manager.getTeamForPlayer(player);
    }
}
