package com.cheesefixquests;

import com.cheesefixquests.mixin.TeamDataAccessor;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.util.QuestKey;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.*;

/**
 * Migrates quest progress to individual PlayerTeams.
 *
 * v6 — Fixed critical getId() vs getTeamId() bug.
 *
 * ROOT CAUSE (v5 bug): PlayerTeam.getTeamId() returns effectiveTeam.getId()
 * (= the party UUID), NOT the permanent player UUID. This caused ALL party
 * members to be skipped as "self-references" during migration.
 * FIX: Use Team.getId() (permanent UUID) instead of getTeamId() (effective team).
 *
 * For each TeamData source:
 * - ACTIVE PARTY: full copy to all current members (shared progress is correct)
 * - SINGLE-UUID source (PlayerTeam/defunct with 1 UUID): full copy (it's that player's data)
 * - MULTI-UUID source (PlayerTeam with leaked UUIDs, or defunct multi-player team):
 *   reward-guided filtering — only copy completed/started/taskProgress for quests
 *   where the player personally claimed a reward. Prevents UUID leakage false positives.
 *
 * Safe to run multiple times (idempotent).
 */
public class QuestMigration {

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    /**
     * Migrate quest data from ALL TeamData to each player's own PlayerTeam.
     *
     * @return number of player migrations performed
     */
    public static int migrateAll() {
        ServerQuestFile questFile = ServerQuestFile.INSTANCE;
        if (questFile == null) {
            CheeseFixQuestsMod.LOGGER.warn("[CheeseFixQuests] ServerQuestFile not loaded, cannot migrate");
            return 0;
        }

        TeamManager tm;
        try {
            tm = FTBTeamsAPI.api().getManager();
        } catch (Exception e) {
            CheeseFixQuestsMod.LOGGER.warn("[CheeseFixQuests] FTB Teams manager not available: {}", e.getMessage());
            return 0;
        }

        int migrated = 0;

        try {
            for (TeamData teamData : new ArrayList<>(questFile.getAllTeamData())) {
                UUID teamId = teamData.getTeamId();
                TeamDataAccessor sourceAcc = (TeamDataAccessor) teamData;

                // --- Step 1: Discover player UUIDs from claimedRewards ---
                Set<UUID> claimedUUIDs = new HashSet<>();
                for (QuestKey key : sourceAcc.cheesefixquests$getClaimedRewards().keySet()) {
                    UUID keyUUID = key.uuid();
                    if (!keyUUID.equals(NIL_UUID)) {
                        claimedUUIDs.add(keyUUID);
                    }
                }

                Set<UUID> playerUUIDs = new HashSet<>(claimedUUIDs);

                // --- Step 2: Determine team type ---
                boolean isActiveParty = false;
                Set<UUID> partyMembers = new HashSet<>();
                boolean isPlayerTeam = false;

                try {
                    Optional<Team> teamOpt = tm.getTeamByID(teamId);
                    if (teamOpt.isPresent()) {
                        Team team = teamOpt.get();
                        if (team.isPartyTeam()) {
                            isActiveParty = true;
                            partyMembers.addAll(team.getMembers());
                            playerUUIDs.addAll(partyMembers);
                        } else if (team.isPlayerTeam()) {
                            isPlayerTeam = true;
                        }
                    }
                } catch (Exception ignored) {}

                if (playerUUIDs.isEmpty()) continue;

                // --- Step 3: Determine if filtering is needed ---
                // Active party: full copy (members share progress — by design)
                // ALL OTHER sources (PlayerTeam, defunct, single-UUID, multi-UUID):
                //   ALWAYS filter. task_progress/started/completed are per-TEAM, not
                //   per-player. A "single-UUID" defunct team may still contain progress
                //   from other players who left before claiming rewards.
                //   Only claimed_rewards has per-player attribution (QuestKey.uuid).
                boolean needsFiltering = !isActiveParty;

                // --- Step 4: Build reward→quest mapping (always, used for all non-party-member migrations) ---
                Map<UUID, Set<Long>> playerQuestIds = new HashMap<>();
                {
                    for (QuestKey key : sourceAcc.cheesefixquests$getClaimedRewards().keySet()) {
                        UUID keyUUID = key.uuid();
                        if (keyUUID.equals(NIL_UUID)) continue;
                        try {
                            QuestObjectBase obj = questFile.getBase(key.id());
                            if (obj instanceof Reward reward) {
                                var quest = reward.getQuest();
                                if (quest != null) {
                                    Set<Long> ids = playerQuestIds.computeIfAbsent(keyUUID, k -> new HashSet<>());
                                    ids.add(quest.id);
                                    // Add chapter so chapter completion is preserved
                                    var chapter = quest.getQuestChapter();
                                    if (chapter != null) {
                                        ids.add(chapter.id);
                                    }
                                    // Add task IDs for taskProgress filtering
                                    for (Task task : quest.getTasks()) {
                                        ids.add(task.id);
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }

                String teamType = isActiveParty ? "PARTY" : (isPlayerTeam ? "PLAYER" : "DEFUNCT");
                CheeseFixQuestsMod.LOGGER.info(
                        "[CheeseFixQuests] Processing team {} [{}] — {} reward UUID(s), {} total player(s), filter={}",
                        teamId, teamType, claimedUUIDs.size(), playerUUIDs.size(), needsFiltering);

                // --- Step 5: Migrate each player ---
                for (UUID playerUUID : playerUUIDs) {
                    Optional<Team> playerTeamOpt = tm.getPlayerTeamForPlayerID(playerUUID);

                    // Fallback: if getPlayerTeamForPlayerID fails, try getTeamByID with the player UUID
                    // In FTB Teams, PlayerTeam UUID == player Minecraft UUID
                    if (playerTeamOpt.isEmpty()) {
                        CheeseFixQuestsMod.LOGGER.warn(
                                "[CheeseFixQuests]   {} — getPlayerTeamForPlayerID returned EMPTY, trying getTeamByID fallback...",
                                playerUUID);
                        try {
                            playerTeamOpt = tm.getTeamByID(playerUUID);
                            if (playerTeamOpt.isPresent()) {
                                CheeseFixQuestsMod.LOGGER.info(
                                        "[CheeseFixQuests]   {} — fallback getTeamByID found team id={} (type={})",
                                        playerUUID, playerTeamOpt.get().getId(),
                                        playerTeamOpt.get().isPlayerTeam() ? "PLAYER" : "OTHER");
                            }
                        } catch (Exception ignored) {}
                    }

                    if (playerTeamOpt.isEmpty()) {
                        CheeseFixQuestsMod.LOGGER.warn(
                                "[CheeseFixQuests]   {} — PlayerTeam NOT FOUND (both methods), skipping", playerUUID);
                        continue;
                    }

                    Team playerTeam = playerTeamOpt.get();
                    // CRITICAL: Use getId() (permanent UUID), NOT getTeamId() (effective/party UUID).
                    // PlayerTeam.getTeamId() returns effectiveTeam.getId() = party UUID when in party,
                    // which falsely equals the source teamId and skips the migration entirely.
                    UUID personalId = playerTeam.getId();
                    if (personalId.equals(teamId)) {
                        CheeseFixQuestsMod.LOGGER.info(
                                "[CheeseFixQuests]   {} — self-reference (personalId {} == source {}), skipping",
                                playerUUID, personalId, teamId);
                        continue;
                    }

                    // getOrCreateTeamData(Team) uses team.getId() internally — correct for personal UUID
                    TeamData playerData = questFile.getOrCreateTeamData(playerTeam);
                    TeamDataAccessor playerAcc = (TeamDataAccessor) playerData;

                    // Decide migration mode:
                    // FULL = only for active party members (shared progress by design)
                    // FILTERED = everything else (only quests where player claimed a reward)
                    Set<Long> questIds = null; // null = full copy
                    String mode;

                    if (isActiveParty && partyMembers.contains(playerUUID)) {
                        mode = "FULL (party member)";
                    } else {
                        questIds = playerQuestIds.getOrDefault(playerUUID, Collections.emptySet());
                        if (questIds.isEmpty()) {
                            mode = "FILTERED (0 quest IDs — only copying claimedRewards)";
                        } else {
                            mode = "FILTERED (" + questIds.size() + " quest/task/chapter IDs)";
                        }
                    }

                    int[] counts = migratePlayerData(sourceAcc, playerAcc, playerUUID, questIds);
                    int total = counts[0] + counts[1] + counts[2] + counts[3];

                    if (total > 0) {
                        playerData.markDirty();
                        migrated++;
                        CheeseFixQuestsMod.LOGGER.info(
                                "[CheeseFixQuests]   {} → PersonalTeam {} [{}]: tasks={}, started={}, completed={}, rewards={}",
                                teamId, personalId, mode,
                                counts[0], counts[1], counts[2], counts[3]);
                    } else {
                        CheeseFixQuestsMod.LOGGER.info(
                                "[CheeseFixQuests]   {} — 0 new entries [{}]", playerUUID, mode);
                    }
                }
            }

            if (migrated > 0) {
                questFile.markDirty();
                questFile.saveNow();
                CheeseFixQuestsMod.LOGGER.info("[CheeseFixQuests] Migration saved: {} player(s) updated", migrated);
            } else {
                CheeseFixQuestsMod.LOGGER.info("[CheeseFixQuests] No new data to migrate");
            }

        } catch (Exception e) {
            CheeseFixQuestsMod.LOGGER.error("[CheeseFixQuests] Migration failed: {}", e.getMessage(), e);
        }

        return migrated;
    }

    /**
     * Migrate data from a source TeamData to a player's TeamData.
     *
     * @param questIds if null → full copy (all entries).
     *                 if non-null → only copy entries whose ID is in this set.
     *                 Empty set → only copy claimedRewards (steps 1-3 skipped).
     * @return counts: [taskProgress, started, completed, claimedRewards]
     */
    private static int[] migratePlayerData(TeamDataAccessor sourceAcc, TeamDataAccessor playerAcc,
                                           UUID playerUUID, Set<Long> questIds) {
        int[] counts = new int[4];
        boolean filter = questIds != null;

        // 1. taskProgress — MAX(existing, source)
        Long2LongMap sourceTasks = sourceAcc.cheesefixquests$getTaskProgress();
        Long2LongMap playerTasks = playerAcc.cheesefixquests$getTaskProgress();
        for (Long2LongMap.Entry entry : sourceTasks.long2LongEntrySet()) {
            long taskId = entry.getLongKey();
            if (filter && !questIds.contains(taskId)) continue;
            long sourceProgress = entry.getLongValue();
            long currentProgress = playerTasks.getOrDefault(taskId, 0L);
            if (sourceProgress > currentProgress) {
                playerTasks.put(taskId, sourceProgress);
                counts[0]++;
            }
        }

        // 2. started — putIfAbsent
        Long2LongMap sourceStarted = sourceAcc.cheesefixquests$getStarted();
        Long2LongMap playerStarted = playerAcc.cheesefixquests$getStarted();
        for (Long2LongMap.Entry entry : sourceStarted.long2LongEntrySet()) {
            long id = entry.getLongKey();
            if (filter && !questIds.contains(id)) continue;
            if (!playerStarted.containsKey(id)) {
                playerStarted.put(id, entry.getLongValue());
                counts[1]++;
            }
        }

        // 3. completed — putIfAbsent
        Long2LongMap sourceCompleted = sourceAcc.cheesefixquests$getCompleted();
        Long2LongMap playerCompleted = playerAcc.cheesefixquests$getCompleted();
        for (Long2LongMap.Entry entry : sourceCompleted.long2LongEntrySet()) {
            long id = entry.getLongKey();
            if (filter && !questIds.contains(id)) continue;
            if (!playerCompleted.containsKey(id)) {
                playerCompleted.put(id, entry.getLongValue());
                counts[2]++;
            }
        }

        // 4. claimedRewards — always filter by player UUID or NIL_UUID (team rewards)
        Object2LongMap<QuestKey> sourceRewards = sourceAcc.cheesefixquests$getClaimedRewards();
        Object2LongMap<QuestKey> playerRewards = playerAcc.cheesefixquests$getClaimedRewards();
        for (Object2LongMap.Entry<QuestKey> entry : sourceRewards.object2LongEntrySet()) {
            QuestKey key = entry.getKey();
            if (key.uuid().equals(playerUUID) || key.uuid().equals(NIL_UUID)) {
                if (!playerRewards.containsKey(key)) {
                    playerRewards.put(key, entry.getLongValue());
                    counts[3]++;
                }
            }
        }

        return counts;
    }
}
