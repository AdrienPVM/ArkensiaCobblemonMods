package com.cheesefixquests;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.*;

public class CheeseFixQuestsCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("cheesefixquests")
                .requires(src -> src.hasPermissionLevel(4))

                .then(CommandManager.literal("migrate")
                        .executes(CheeseFixQuestsCommand::migrate))

                .then(CommandManager.literal("status")
                        .executes(CheeseFixQuestsCommand::status))

                .then(CommandManager.literal("remigrate")
                        .executes(CheeseFixQuestsCommand::remigrate))

                .then(CommandManager.literal("resetchapter")
                        .then(CommandManager.argument("chapterHexId", StringArgumentType.word())
                                .executes(CheeseFixQuestsCommand::resetChapter)))
        );
    }

    /**
     * /cheesefixquests migrate — Run migration (skips if already done).
     */
    private static int migrate(CommandContext<ServerCommandSource> ctx) {
        if (CheeseFixQuestsConfig.migrationDone) {
            ctx.getSource().sendFeedback(() -> Text.literal(
                    "§e[CheeseFixQuests] Migration déjà effectuée. Utilisez /cheesefixquests remigrate pour forcer."), false);
            return 0;
        }
        int count = QuestMigration.migrateAll();
        CheeseFixQuestsConfig.migrationDone = true;
        CheeseFixQuestsConfig.save();
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§a[CheeseFixQuests] Migration terminée: " + count + " joueur(s) migrés."), true);
        return count;
    }

    /**
     * /cheesefixquests remigrate — Force re-migration (even if already done).
     * Safe to run multiple times — mergeData uses Long.max().
     */
    private static int remigrate(CommandContext<ServerCommandSource> ctx) {
        int count = QuestMigration.migrateAll();
        CheeseFixQuestsConfig.migrationDone = true;
        CheeseFixQuestsConfig.save();
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§a[CheeseFixQuests] Re-migration terminée: " + count + " joueur(s) migrés."), true);
        return count;
    }

    /**
     * /cheesefixquests status — Show current config status.
     */
    private static int status(CommandContext<ServerCommandSource> ctx) {
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§e[CheeseFixQuests] forceIndividual=" + CheeseFixQuestsConfig.forceIndividualProgress +
                        " migrationDone=" + CheeseFixQuestsConfig.migrationDone +
                        " debug=" + CheeseFixQuestsConfig.debugLogging), false);
        return 1;
    }

    /**
     * /cheesefixquests resetchapter <hexId> — Reset all quests in a chapter for ALL players.
     *
     * This is needed when ExtraQuests timers expired before the mixin fix was deployed,
     * causing daily quest resets to silently fail (reset hit empty party TeamData).
     * After running this, quests become available again and future timers work correctly.
     */
    private static int resetChapter(CommandContext<ServerCommandSource> ctx) {
        String hexId = StringArgumentType.getString(ctx, "chapterHexId");
        ServerQuestFile questFile = ServerQuestFile.INSTANCE;
        if (questFile == null) {
            ctx.getSource().sendFeedback(() -> Text.literal("§c[CheeseFixQuests] ServerQuestFile not loaded"), false);
            return 0;
        }

        long chapterId;
        try {
            chapterId = questFile.getID(hexId);
        } catch (Exception e) {
            ctx.getSource().sendFeedback(() -> Text.literal("§c[CheeseFixQuests] Invalid hex ID: " + hexId), false);
            return 0;
        }

        QuestObjectBase obj = questFile.getBase(chapterId);
        if (!(obj instanceof Chapter chapter)) {
            ctx.getSource().sendFeedback(() -> Text.literal("§c[CheeseFixQuests] ID " + hexId + " is not a chapter"), false);
            return 0;
        }

        List<Quest> quests = new ArrayList<>(chapter.getQuests());
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§e[CheeseFixQuests] Resetting " + quests.size() + " quests in chapter '" + hexId + "' for all players..."), true);

        int totalResets = 0;

        for (TeamData teamData : new ArrayList<>(questFile.getAllTeamData())) {
            UUID teamId = teamData.getTeamId();

            // Find a player UUID for this TeamData (needed for reward reset)
            UUID playerUUID = null;
            try {
                Optional<Team> teamOpt = FTBTeamsAPI.api().getManager().getTeamByID(teamId);
                if (teamOpt.isPresent() && teamOpt.get().isPlayerTeam()) {
                    // PlayerTeam ID == player UUID
                    playerUUID = teamOpt.get().getId();
                }
            } catch (Exception ignored) {}

            if (playerUUID == null) continue; // Skip party teams / defunct teams

            int resetCount = 0;
            for (Quest quest : quests) {
                if (teamData.isCompleted(quest)) {
                    // Reset task progress
                    for (Task task : quest.getTasks()) {
                        teamData.setProgress(task, 0L);
                    }
                    // Reset started/completed
                    teamData.setStarted(quest.id, null);
                    teamData.setCompleted(quest.id, null);
                    // Reset claimed rewards
                    for (Reward reward : quest.getRewards()) {
                        teamData.resetReward(playerUUID, reward);
                    }
                    resetCount++;
                }
            }

            if (resetCount > 0) {
                teamData.markDirty();
                totalResets += resetCount;
                CheeseFixQuestsMod.LOGGER.info("[CheeseFixQuests] Reset {} quests for PlayerTeam {}",
                        resetCount, teamId);
            }
        }

        questFile.markDirty();
        questFile.saveNow();

        int finalTotal = totalResets;
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§a[CheeseFixQuests] Reset terminé: " + finalTotal + " quest(s) réinitialisées au total."), true);
        return totalResets;
    }
}
