package com.cheesefixquests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

public class CheeseFixQuestsConfig {
    private static final Path CONFIG_PATH = Path.of("config", "cheesefixquests.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Existing flags ─────────────────────────────────────────────
    public static boolean forceIndividualProgress = true;
    public static boolean debugLogging = false;
    public static boolean migrationDone = false;

    // ── QuestFinalizerHook kill-switches ───────────────────────────
    /** Phase A — passive item-task scan via FTBQuestsInventoryListener.detect */
    public static boolean enableInventoryRescan = true;
    /** Phase B — per-task submitTask sweep for tasks with checkOnLogin == true */
    public static boolean enableTaskSubmitSweep = true;
    /** Phase C — finalise quests whose tasks are complete but onCompleted never fired */
    public static boolean enableQuestFinalization = true;
    /** Phase D — periodic re-fire of Cobblemon Quests' triggerPokeDexUpdate */
    public static boolean enableCobblemonPokedexResync = true;

    // ── Anti-duplication safety net ────────────────────────────────
    /**
     * Master kill-switch for {@code TeamDataClaimRewardSyncMixin}. Keep TRUE.
     * Set to false ONLY if the mixin causes a regression — disabling it
     * re-exposes the cross-thread race on {@code claimedRewards} that produced
     * a rock_badge double-claim seen in production.
     */
    public static boolean enableClaimRewardLock = true;

    // ── Forensic audit ─────────────────────────────────────────────
    /**
     * Append every quest finalisation, claim reservation, claim block and
     * Pokédex resync to {@code config/cheesefixquests_audit.log}. Off by
     * default (steady-state log churn). Turn on to investigate a duplicate
     * report — the log gives thread name + timestamp ms-precision.
     */
    public static boolean auditQuestEvents = false;

    public static void load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                ConfigData data = GSON.fromJson(Files.readString(CONFIG_PATH), ConfigData.class);
                if (data != null) {
                    forceIndividualProgress = data.forceIndividualProgress;
                    debugLogging = data.debugLogging;
                    migrationDone = data.migrationDone;
                    enableInventoryRescan = data.enableInventoryRescan;
                    enableTaskSubmitSweep = data.enableTaskSubmitSweep;
                    enableQuestFinalization = data.enableQuestFinalization;
                    enableCobblemonPokedexResync = data.enableCobblemonPokedexResync;
                    enableClaimRewardLock = data.enableClaimRewardLock;
                    auditQuestEvents = data.auditQuestEvents;
                }
            } else {
                save();
            }
        } catch (Exception e) {
            CheeseFixQuestsMod.LOGGER.error("[CheeseFixQuests] Failed to load config, using defaults", e);
            save();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            ConfigData data = new ConfigData();
            data.forceIndividualProgress = forceIndividualProgress;
            data.debugLogging = debugLogging;
            data.migrationDone = migrationDone;
            data.enableInventoryRescan = enableInventoryRescan;
            data.enableTaskSubmitSweep = enableTaskSubmitSweep;
            data.enableQuestFinalization = enableQuestFinalization;
            data.enableCobblemonPokedexResync = enableCobblemonPokedexResync;
            data.enableClaimRewardLock = enableClaimRewardLock;
            data.auditQuestEvents = auditQuestEvents;
            Files.writeString(CONFIG_PATH, GSON.toJson(data));
        } catch (Exception e) {
            CheeseFixQuestsMod.LOGGER.error("[CheeseFixQuests] Failed to save config", e);
        }
    }

    private static class ConfigData {
        boolean forceIndividualProgress = true;
        boolean debugLogging = false;
        boolean migrationDone = false;
        boolean enableInventoryRescan = true;
        boolean enableTaskSubmitSweep = true;
        boolean enableQuestFinalization = true;
        boolean enableCobblemonPokedexResync = true;
        boolean enableClaimRewardLock = true;
        boolean auditQuestEvents = false;
    }
}
