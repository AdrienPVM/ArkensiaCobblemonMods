package com.cheesefixquests.mixin;

import com.cheesefixquests.CheeseFixQuestsConfig;
import com.cheesefixquests.CheeseFixQuestsMod;
import dev.ftb.mods.ftbquests.quest.TeamData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 2: Prevents quest progress from being merged when a player joins a party.
 *
 * Without this, FTB Quests calls mergeData() on party join, which copies
 * taskProgress — contaminating team data with individual progress.
 * With individual mode enabled, each player keeps their own data on their PlayerTeam,
 * so the merge is both unnecessary and harmful.
 *
 * Our migration (QuestMigration) accesses fields directly via TeamDataAccessor,
 * so this block does NOT affect migration.
 */
@Mixin(value = TeamData.class, remap = false)
public abstract class TeamDataMixin {

    @Inject(method = "mergeData", at = @At("HEAD"), cancellable = true)
    private void preventProgressMerge(TeamData other, CallbackInfo ci) {
        if (CheeseFixQuestsConfig.forceIndividualProgress) {
            if (CheeseFixQuestsConfig.debugLogging) {
                CheeseFixQuestsMod.LOGGER.info("[CheeseFixQuests] Blocked mergeData — individual progress mode active");
            }
            ci.cancel();
        }
    }

    /**
     * Also block {@code mergeClaimedRewards} called on party-leave.
     * Symmetric to {@link #preventProgressMerge} above — pollutes the leaving
     * player's PlayerTeam claimedRewards map with entries keyed under OTHER
     * party members' UUIDs (cosmetic, but ugly and could re-trigger reward
     * checks for the wrong player).
     */
    @Inject(method = "mergeClaimedRewards", at = @At("HEAD"), cancellable = true)
    private void preventClaimedRewardsMerge(TeamData other, CallbackInfo ci) {
        if (CheeseFixQuestsConfig.forceIndividualProgress) {
            if (CheeseFixQuestsConfig.debugLogging) {
                CheeseFixQuestsMod.LOGGER.info("[CheeseFixQuests] Blocked mergeClaimedRewards — individual progress mode active");
            }
            ci.cancel();
        }
    }
}
