package com.cheesefixquests;

import com.cheesefixquests.logic.QuestAuditLogger;
import com.cheesefixquests.logic.QuestFinalizerHook;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheeseFixQuestsMod implements ModInitializer {
    public static final String MOD_ID = "cheesefixquests";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CheeseFixQuestsConfig.load();

        if (CheeseFixQuestsConfig.forceIndividualProgress) {
            LOGGER.info("[CheeseFixQuests] Individual quest progress mode ENABLED");
            if (!CheeseFixQuestsConfig.migrationDone) {
                LOGGER.info("[CheeseFixQuests] Migration pending — will run on first player login");
            }
        } else {
            LOGGER.info("[CheeseFixQuests] Individual quest progress mode DISABLED");
        }

        // /cheesefixquests command (migrate, remigrate, status, resetchapter)
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CheeseFixQuestsCommand.register(dispatcher));

        // Quest finalisation sweep — every 200 ticks (10 s) on the server thread.
        // No-ops fast when FTB Quests is absent.
        ServerTickEvents.END_SERVER_TICK.register(QuestFinalizerHook::tick);

        // Stop the sweep before world-save runs at shutdown. Without this, the
        // last sweep could mutate TeamData while the save thread reads it.
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            QuestFinalizerHook.onServerStopping();
            QuestAuditLogger.shutdown();
        });

        LOGGER.info(
                "[CheeseFixQuests] Boot complete — finalizer={} claim-lock={} audit={}",
                CheeseFixQuestsConfig.enableQuestFinalization,
                CheeseFixQuestsConfig.enableClaimRewardLock,
                CheeseFixQuestsConfig.auditQuestEvents);
    }
}
