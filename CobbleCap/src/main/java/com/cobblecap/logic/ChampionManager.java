package com.cobblecap.logic;

import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.ChampionEntry;
import com.cobblecap.data.LegendEntry;
import com.cobblecap.data.PlayerCapData;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates champion pre-battle conditions and party cap compliance.
 * All Cobblemon access is via reflection — no direct Cobblemon imports.
 */
public class ChampionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    /** Rate-limit pre-battle messages to avoid duplicates (event fires twice). */
    private static final java.util.Map<UUID, Long> lastPreBattleMsg = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean shouldSendMessage(UUID playerUuid) {
        long now = System.currentTimeMillis();
        Long last = lastPreBattleMsg.get(playerUuid);
        if (last != null && now - last < 2000) return false;
        lastPreBattleMsg.put(playerUuid, now);
        return true;
    }

    // ── Pre-battle condition gate ─────────────────────────────────────────────

    /**
     * Validate pre-battle conditions for a champion fight.
     * Returns PASS if the player may proceed, FAIL if conditions are unmet (with messages sent).
     * Fails open: if player data is unavailable, returns PASS rather than blocking.
     */
    public static ActionResult handlePreBattle(ServerPlayerEntity player, ChampionEntry champ) {
        Optional<PlayerCapData> dataOpt = CobbleCapMod.INSTANCE.getCapStorage().getCapData(player.getUuid());

        // Fail open: no data yet — let the fight proceed
        if (dataOpt.isEmpty()) return ActionResult.PASS;

        PlayerCapData data = dataOpt.get();

        // Already beaten — block if one-time only, allow if has challenge form
        if (data.getChampionsBeaten().contains(champ.id())) {
            if (!champ.allowRefight()) {
                if (shouldSendMessage(player.getUuid())) {
                    player.sendMessage(
                        Text.translatable("cobblecap.msg.already_beaten", champ.displayName())
                            .formatted(Formatting.YELLOW), false);
                }
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        }

        // Build list of unmet conditions
        List<Text> unmetConditions = new ArrayList<>();

        // Check Pokédex count requirement
        if (champ.requiredPokedex() > 0) {
            int caught = getPokedexCount(player);
            if (caught < champ.requiredPokedex()) {
                unmetConditions.add(
                    Text.translatable("cobblecap.msg.condition_pokedex", caught, champ.requiredPokedex())
                );
            }
        }

        // Check FTB Quest requirement
        if (champ.requiredQuestId() != null && !champ.requiredQuestId().isEmpty()) {
            if (!isFtbQuestCompleted(player, champ.requiredQuestId())) {
                unmetConditions.add(
                    Text.translatable("cobblecap.msg.condition_quest")
                );
            }
        }

        // Check scoreboard requirement (e.g., alfred_main_progress gate)
        if (champ.requiredScoreboard() != null && !champ.requiredScoreboard().isEmpty()) {
            int score = getPlayerScore(player, champ.requiredScoreboard());
            if (score < champ.requiredScoreMin()) {
                unmetConditions.add(
                    Text.translatable("cobblecap.msg.condition_progress")
                );
            }
        }

        // All conditions met — allow fight
        if (unmetConditions.isEmpty()) return ActionResult.PASS;

        // Send multi-line failure message (dedup: skip if same player got a message <2s ago)
        if (shouldSendMessage(player.getUuid())) {
            player.sendMessage(
                Text.translatable("cobblecap.msg.conditions_unmet_header", champ.displayName())
                    .formatted(Formatting.RED),
                false
            );
            for (Text condition : unmetConditions) {
                player.sendMessage(
                    Text.literal("  - ").append(condition).formatted(Formatting.YELLOW),
                    false
                );
            }
        }

        return ActionResult.FAIL;
    }

    // ── Challenge mode pre-battle gate (CHAMP-06) ──────────────────────────────

    /**
     * Validate pre-battle conditions for a challenge fight.
     * Requires: all normal champions beaten + cooldown not active.
     * Does NOT check party cap — challenge battles bypass the cap system.
     */
    public static ActionResult handleChallengePreBattle(ServerPlayerEntity player, ChampionEntry champ) {
        Optional<PlayerCapData> dataOpt = CobbleCapMod.INSTANCE.getCapStorage().getCapData(player.getUuid());
        if (dataOpt.isEmpty()) return ActionResult.PASS;

        PlayerCapData data = dataOpt.get();

        // Must have beaten all normal champions (unless this champion doesn't require it)
        if (champ.challengeRequiresAllBeaten()
                && !data.hasAllNormalChampionsBeaten(CobbleCapMod.INSTANCE.getChampionConfig().getChampions())) {
            player.sendMessage(
                Text.translatable("cobblecap.msg.challenge_locked")
                    .formatted(Formatting.RED),
                false
            );
            return ActionResult.FAIL;
        }

        // Check scoreboard gate for challenge (e.g., alfred_main_progress)
        if (champ.challengeRequiredScoreMin() > 0
                && champ.requiredScoreboard() != null && !champ.requiredScoreboard().isEmpty()) {
            int score = getPlayerScore(player, champ.requiredScoreboard());
            if (score < champ.challengeRequiredScoreMin()) {
                if (shouldSendMessage(player.getUuid())) {
                    player.sendMessage(
                        Text.translatable("cobblecap.msg.condition_progress")
                            .formatted(Formatting.RED), false);
                }
                return ActionResult.FAIL;
            }
        }

        // Check cooldown
        if (data.isChallengeOnCooldown(champ.id(), champ.challengeCooldownHours())) {
            long remainingMs = data.getChallengeRemainingMs(champ.id(), champ.challengeCooldownHours());
            int remainingMin = (int) Math.ceil(remainingMs / 60_000.0);
            if (shouldSendMessage(player.getUuid())) {
                player.sendMessage(
                    Text.translatable("cobblecap.msg.challenge_cooldown", champ.displayName(), remainingMin)
                        .formatted(Formatting.YELLOW), false);
            }
            return ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    // ── rctmod trainer ID extraction ─────────────────────────────────────────

    /**
     * Strip the per-dim suffix ({@code _<8hex>}) from a trainer ID when the
     * prefix matches one of the Conseil 4 base IDs. Used to normalize IDs from
     * entities that live in per-player conseil4 dimensions (where
     * {@link Conseil4TrainerCloner} clones each trainer into a dim-specific
     * variant so battles don't collide on rctmod's shared TrainerNPC singleton).
     * Returns the input unchanged when no per-dim suffix is present.
     */
    public static String stripPerDimSuffix(String trainerId) {
        if (trainerId == null) return null;
        int us = trainerId.lastIndexOf('_');
        if (us <= 0 || trainerId.length() - us != 9) return trainerId; // must be _<8 chars>
        String suffix = trainerId.substring(us + 1);
        if (suffix.length() != 8) return trainerId;
        for (int i = 0; i < 8; i++) {
            char c = suffix.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return trainerId;
        }
        String base = trainerId.substring(0, us);
        return Conseil4TrainerCloner.CONSEIL4_BASE_IDS.contains(base) ? base : trainerId;
    }

    /**
     * Extract the rctmod trainer configuration ID from an entity via NBT.
     * Logs all top-level NBT keys on first call (diagnostic for identifying the correct key).
     * Returns null if no trainer ID can be found — caller should PASS in that case.
     */
    public static String extractRctmodTrainerId(Entity entity) {
        try {
            NbtCompound nbt = new NbtCompound();
            entity.writeNbt(nbt);

            // Try known key names in order (most likely first based on rctmod patterns)
            for (String key : new String[]{"TrainerData", "trainer", "trainerId", "configId", "id", "Trainer"}) {
                if (nbt.contains(key)) {
                    // Could be a compound (TrainerData) or a string directly
                    if (nbt.contains(key, 10)) { // 10 = NbtCompound type
                        NbtCompound sub = nbt.getCompound(key);
                        // Try sub-keys within the compound
                        for (String subKey : new String[]{"id", "configId", "trainerId", "trainer"}) {
                            if (sub.contains(subKey, 8)) { // 8 = String type
                                String val = sub.getString(subKey);
                                if (val != null && !val.isEmpty()) return val;
                            }
                        }
                    } else if (nbt.contains(key, 8)) { // 8 = String type
                        String val = nbt.getString(key);
                        if (val != null && !val.isEmpty()) return val;
                    }
                }
            }

            // Reflection fallback: scan entity methods for String-returning ones that look like trainer IDs
            for (Method m : entity.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                    try {
                        String result = (String) m.invoke(entity);
                        if (result != null && result.contains("_") && result.length() > 3) {
                            return result;
                        }
                    } catch (Exception ignored) {}
                }
            }

        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] extractRctmodTrainerId error: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Check if all champions that have a challengeId have been beaten in challenge mode.
     */
    private static boolean allChallengesBeaten(PlayerCapData data) {
        for (ChampionEntry c : CobbleCapMod.INSTANCE.getChampionConfig().getChampions()) {
            if (c.challengeId() != null && !c.challengeId().isEmpty()) {
                if (!data.getChallengeBeaten().contains(c.id())) return false;
            }
        }
        return true;
    }

    // ── Scoreboard check ──────────────────────────────────────────────────────

    /**
     * Get a player's score for a given scoreboard objective.
     * Returns 0 if objective or score doesn't exist.
     */
    public static int getPlayerScore(ServerPlayerEntity player, String objectiveName) {
        try {
            var scoreboard = player.getServer().getScoreboard();
            var objective = scoreboard.getNullableObjective(objectiveName);
            if (objective == null) return 0;
            var scoreAccess = scoreboard.getScore(player, objective);
            return scoreAccess != null ? scoreAccess.getScore() : 0;
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] getPlayerScore error for {}: {}", objectiveName, e.getMessage());
            return 0;
        }
    }

    // ── Cobblemon Pokédex check ───────────────────────────────────────────────

    /**
     * Get the number of Pokémon caught in the player's Pokédex via reflection.
     * Returns 0 on any failure (fail-safe: condition appears unmet if check fails,
     * which is the correct behavior — don't grant access if we can't verify).
     */
    public static int getPokedexCount(ServerPlayerEntity player) {
        try {
            // com.cobblemon.mod.common.Cobblemon.INSTANCE.getPlayerData().get(uuid).getPokedex().getCaught()
            Class<?> cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object cobblemonInstance = cobblemonClass.getField("INSTANCE").get(null);

            // Get player data storage
            Object playerData = CapManager.invokeGetter(cobblemonInstance, "getPlayerData", "playerData");
            if (playerData == null) return 0;

            // Get this player's data
            Method getByUuid = CapManager.findMethod(playerData.getClass(), "get", "getForPlayer");
            if (getByUuid == null) return 0;
            Object pd;
            try {
                pd = getByUuid.invoke(playerData, player.getUuid());
            } catch (Exception e1) {
                try {
                    pd = getByUuid.invoke(playerData, player);
                } catch (Exception e2) {
                    return 0;
                }
            }
            if (pd == null) return 0;

            // Get pokedex from player data
            Object pokedex = CapManager.invokeGetter(pd, "getPokedex", "pokedex");
            if (pokedex == null) return 0;

            // Get caught count
            Object caught = CapManager.invokeGetter(pokedex, "getCaught", "caught", "getCount", "size");
            if (caught == null) return 0;

            if (caught instanceof java.util.Collection<?> col) return col.size();
            if (caught instanceof java.util.Map<?, ?> map) return map.size();
            if (caught instanceof Number n) return n.intValue();

        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] getPokedexCount error: {}", e.getMessage());
        }
        return 0;
    }

    // ── FTB Quests check ─────────────────────────────────────────────────────

    /**
     * Check if a player has completed a specific FTB Quest (by hex ID).
     * Returns true (fail open) on any error — don't block fights if FTB is absent.
     */
    public static boolean isFtbQuestCompleted(ServerPlayerEntity player, String questHexId) {
        try {
            Class<?> sqf = Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile");
            Object instance = sqf.getField("INSTANCE").get(null);
            if (instance == null) return true;

            // Get player data: getData(player) or getPlayerData(player)
            Method getDataMethod = null;
            for (Method m : sqf.getMethods()) {
                String mn = m.getName();
                if ((mn.equals("getData") || mn.equals("getPlayerData")) && m.getParameterCount() == 1) {
                    getDataMethod = m;
                    break;
                }
            }
            if (getDataMethod == null) return true;

            Object playerQuestData;
            try {
                playerQuestData = getDataMethod.invoke(instance, player);
            } catch (Exception e) {
                try {
                    playerQuestData = getDataMethod.invoke(instance, player.getUuid());
                } catch (Exception e2) {
                    return true; // fail open
                }
            }
            if (playerQuestData == null) return true;

            // Parse quest hex ID to long
            long questId;
            try {
                questId = Long.parseUnsignedLong(questHexId, 16);
            } catch (NumberFormatException e) {
                LOGGER.warn("[CobbleCap] Invalid quest hex ID: {}", questHexId);
                return true; // fail open
            }

            // Check completion: isComplete(questId) or isQuestComplete(questId)
            for (Method m : playerQuestData.getClass().getMethods()) {
                String mn = m.getName();
                if ((mn.equals("isComplete") || mn.equals("isQuestComplete")) && m.getParameterCount() == 1) {
                    try {
                        Object result = m.invoke(playerQuestData, questId);
                        if (result instanceof Boolean b) return b;
                    } catch (Exception ignored) {}
                    // Try with int/long variants
                    try {
                        Object result = m.invoke(playerQuestData, (int) questId);
                        if (result instanceof Boolean b) return b;
                    } catch (Exception ignored) {}
                }
            }

        } catch (ClassNotFoundException e) {
            // FTB Quests not installed — fail open
            LOGGER.debug("[CobbleCap] FTB Quests not found — quest condition skipped");
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] isFtbQuestCompleted error: {}", e.getMessage());
        }
        return true; // fail open
    }

    // ── Victory handling (CHAMP-04) ───────────────────────────────────────────

    /**
     * Called from CobblemonHook BATTLE_VICTORY event.
     * Identifies the winning player and losing champion NPC, then delegates to handleVictory.
     */
    public static void handleBattleVictoryEvent(Object event, MinecraftServer server) {
        try {
            // Get winners
            Object winners = CapManager.invokeGetter(event, "getWinners", "winners");
            UUID winnerUuid = null;
            if (winners instanceof Iterable<?> iter) {
                for (Object actor : iter) {
                    UUID uuid = getActorPlayerUuid(actor);
                    if (uuid != null) { winnerUuid = uuid; break; }
                }
            }

            // ── LOSS DETECTION (Régis in ranked mode) ─────────────────────────
            // If NO player won (a trainer NPC won), see if it was Régis and if
            // the losing player was in ranked mode. If so, tally a failure.
            if (winnerUuid == null) {
                handleRegisRankedLoss(event, server);
                return; // wild/NPC battle — nothing else to do
            }

            // Get losers
            Object losers = CapManager.invokeGetter(event, "getLosers", "losers");
            String trainerId = null;
            Entity loserEntity = null;

            if (losers instanceof Iterable<?> iter) {
                for (Object actor : iter) {
                    Object ent = CapManager.invokeGetter(actor, "getEntity", "getNPC", "entity");
                    if (ent instanceof Entity e) { loserEntity = e; break; }
                }
            }

            // Fallback: get battle actors and find non-winner
            if (loserEntity == null) {
                Object battle = CapManager.invokeGetter(event, "getBattle");
                if (battle != null) {
                    Object actors = CapManager.invokeGetter(battle, "getActors", "getPlayers");
                    if (actors instanceof Iterable<?> iter) {
                        for (Object actor : iter) {
                            UUID uuid = getActorPlayerUuid(actor);
                            if (uuid != null && uuid.equals(winnerUuid)) continue; // skip winner
                            Object ent = CapManager.invokeGetter(actor, "getEntity", "getNPC", "entity");
                            if (ent instanceof Entity e) { loserEntity = e; break; }
                        }
                    }
                }
            }

            if (loserEntity != null) {
                trainerId = stripPerDimSuffix(extractRctmodTrainerId(loserEntity));
            }
            if (trainerId == null) return; // Not a champion NPC battle

            // Check if this is a challenge variant first
            Optional<ChampionEntry> challengeOpt = CobbleCapMod.INSTANCE.getChampionConfig().findByChallengeId(trainerId);
            if (challengeOpt.isPresent()) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(winnerUuid);
                if (player != null) handleChallengeVictory(player, challengeOpt.get(), server);
                return;
            }

            Optional<ChampionEntry> champOpt = CobbleCapMod.INSTANCE.getChampionConfig().findById(trainerId);
            if (champOpt.isEmpty()) return; // Trainer not in champion config

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(winnerUuid);
            if (player == null) return;

            handleVictory(player, champOpt.get(), server);

        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] handleBattleVictoryEvent error: {}", e.getMessage());
        }
    }

    /**
     * Process a confirmed champion victory: raise cap, broadcast, trigger FTB quest and webhook.
     */
    public static void handleVictory(ServerPlayerEntity player, ChampionEntry champ, MinecraftServer server) {
        Optional<PlayerCapData> dataOpt = CobbleCapMod.INSTANCE.getCapStorage().getCapData(player.getUuid());
        if (dataOpt.isEmpty()) return;

        PlayerCapData data = dataOpt.get();
        boolean firstTime = !data.getChampionsBeaten().contains(champ.id());

        // Always record the champion as beaten (idempotent)
        data.addChampionBeaten(champ.id());

        boolean raised = CobbleCapMod.INSTANCE.getCapStorage().raiseCapLevel(player.getUuid(), champ.capGranted());

        // Defensive save: raiseCapLevel only saves on actual raise, so ensure the
        // championsBeaten mutation above is always persisted regardless of cap state.
        if (!raised) CobbleCapMod.INSTANCE.getCapStorage().saveAsync();

        if (raised) {
            // Server-wide broadcast
            server.getPlayerManager().broadcast(
                Text.translatable("cobblecap.msg.cap_raised_broadcast",
                    player.getName().getString(), champ.displayName(), champ.capGranted())
                    .formatted(Formatting.GOLD),
                false
            );
            // Personal confirmation
            player.sendMessage(
                Text.translatable("cobblecap.msg.champion_beaten", champ.displayName(), champ.capGranted())
                    .formatted(Formatting.GREEN),
                false
            );
        } else if (firstTime) {
            // Cap was already higher — still a first-time recording
            player.sendMessage(
                Text.translatable("cobblecap.msg.already_beaten", champ.displayName())
                    .formatted(Formatting.YELLOW),
                false
            );
            CobbleCapMod.INSTANCE.getCapStorage().saveAsync();
        }

        if (firstTime) {
            // Path 1 only: AdvancementTask.autoSubmitOnPlayerTick polls and detects this
            // advancement reliably, completing the quest and auto-claiming rewards once.
            // We previously also force-completed the quest here as a safety net, but that
            // path runs Quest.forceProgress -> Reward.forceProgress -> claimReward(uuid),
            // which on rare occasions duplicated the badge reward despite the
            // TeamDataClaimRewardSyncMixin guard. Removing the redundant call makes the
            // dupe structurally impossible.
            grantAdvancement(player, champ.displayName(), "champion", server);
            CobbleCapWebhook.sendChampionVictory(
                player.getName().getString(), champ.displayName(), champ.capGranted());

            // Check if all normal champions are now beaten → grant badges milestone
            if (data.hasAllNormalChampionsBeaten(CobbleCapMod.INSTANCE.getChampionConfig().getChampions())) {
                grantAdvancement(player, "badges_complete", "milestone", server);
                LOGGER.info("[CobbleCap] {} completed all normal champions — badges milestone granted", player.getName().getString());
            }
        }

        // ── Pokémon League Master gate (shared with challenge path) ──────────
        // Régis lives in the config as a NORMAL champion entry (id="champion_regis_challenge",
        // challengeId=""), so handleBattleVictoryEvent routes us here — not to
        // handleChallengeVictory. We must run the League Master promotion here too,
        // gated on the champion's id matching the Régis RCT trainer ID. Bug fixed
        // 2026-05-17 — players who beat Régis were getting the chrono "not beaten"
        // refusal because the gate only existed in handleChallengeVictory.
        if (firstTime && "champion_regis_challenge".equals(champ.id())) {
            tryGrantLeagueMaster(player, server);
        }

        // Sync updated cap state to client HUD
        CobbleCapMod.syncToClient(player);
    }

    /**
     * Process a confirmed challenge victory: record, trigger FTB quest, cooldown.
     * No cap raise — challenge mode is post-game repeatable content.
     */
    public static void handleChallengeVictory(ServerPlayerEntity player, ChampionEntry champ, MinecraftServer server) {
        Optional<PlayerCapData> dataOpt = CobbleCapMod.INSTANCE.getCapStorage().getCapData(player.getUuid());
        if (dataOpt.isEmpty()) return;

        PlayerCapData data = dataOpt.get();
        data.addChallengeBeaten(champ.id());
        data.recordChallengeTime(champ.id());

        // Raise cap if this challenge grants one (e.g., rival late form)
        if (champ.challengeCapGranted() > 0) {
            boolean raised = CobbleCapMod.INSTANCE.getCapStorage().raiseCapLevel(player.getUuid(), champ.challengeCapGranted());
            if (raised) {
                server.getPlayerManager().broadcast(
                    Text.translatable("cobblecap.msg.cap_raised_broadcast",
                        player.getName().getString(), champ.displayName(), champ.challengeCapGranted())
                        .formatted(Formatting.GOLD),
                    false
                );
            }
        }

        CobbleCapMod.INSTANCE.getCapStorage().saveAsync();

        player.sendMessage(
            Text.translatable("cobblecap.msg.challenge_victory", champ.displayName())
                .formatted(Formatting.GREEN),
            false
        );

        // Grant challenge advancement only — AdvancementTask polls and completes the
        // quest naturally. Force-completion via /ftbquests complete_quest was removed
        // to eliminate the dupe-reward path (see handleVictory comment above).
        grantAdvancement(player, champ.displayName(), "challenge", server);

        // Check if all challenges are now beaten → grant challengers milestone
        // (advancement for completing every gym leader challenge — independent
        // of the Conseil 4 / League Master flow below).
        if (allChallengesBeaten(data)) {
            grantAdvancement(player, "challengers_complete", "milestone", server);
            LOGGER.info("[CobbleCap] {} completed all challenges — challengers milestone granted", player.getName().getString());
        }

        // Same Régis gate as in handleVictory — fires here if Régis is ever
        // configured as a "challenge" variant in the future. For now the
        // normal-victory path is the active one.
        if ("champion_regis_challenge".equals(champ.challengeId())) {
            tryGrantLeagueMaster(player, server);
        }

        CobbleCapMod.syncToClient(player);
    }

    /**
     * Promote the player to Maître de la Ligue (Pokémon League Master).
     * Idempotent — re-runs just update bestTime if the chrono is faster.
     * Shared between handleVictory (normal path) and handleChallengeVictory
     * (legacy/challenge path) so the gate runs from whichever route the Régis
     * trainer happens to be routed through given the champion config shape.
     * <p>
     * Stops the active chrono if any (beating Régis IS the natural end of
     * the run). Snapshots the team, adds a LegendEntry, broadcasts, fires the
     * Discord webhook, rebuilds the Conseil 4 scoreboards, and queues the RCT
     * trainer morph.
     */
    private static void tryGrantLeagueMaster(ServerPlayerEntity player, MinecraftServer server) {
        long elapsed = CobbleCapMod.INSTANCE.getChronoManager().stop(player.getUuid());
        boolean chronoCaptured = elapsed >= 0;
        LegendEntry.Mode mode = readPlayerMode(player);

        if (mode == LegendEntry.Mode.RANKED) {
            handleRankedWin(player, server, chronoCaptured ? elapsed : 0L);
        } else {
            handleClassicWin(player, server, chronoCaptured, elapsed);
        }
    }

    /**
     * Handle a CLASSIC-mode Conseil 4 victory: creates a first-time legend entry
     * (if none exists) or updates the player's bestTime (if faster). Drives the
     * classic-only leaderboards (first finishers + fastest chronos).
     */
    private static void handleClassicWin(ServerPlayerEntity player, MinecraftServer server,
                                          boolean chronoCaptured, long elapsed) {
        var legends = CobbleCapMod.INSTANCE.getLegendsStorage();
        if (!legends.hasLegend(player.getUuid())) {
            boolean firstEver = legends.isEmpty();
            List<LegendEntry.TeamMember> team = snapshotPlayerTeam(player);
            LegendEntry entry = new LegendEntry(
                    player.getUuid(), player.getName().getString(),
                    System.currentTimeMillis(), firstEver, false,
                    chronoCaptured ? elapsed : 0L, team, LegendEntry.Mode.CLASSIC);
            legends.add(entry);
            broadcastClassicWin(player, server, firstEver, chronoCaptured, elapsed);
            CobbleCapWebhook.sendLeagueMaster(entry);
            try { com.cobblecap.logic.Conseil4Boards.rebuild(server); } catch (Exception ignored) {}
            LOGGER.info("[CobbleCap] {} became a CLASSIC Maître de la Ligue (firstEver={}, chrono={}ms)",
                    player.getName().getString(), firstEver, chronoCaptured ? elapsed : -1L);
            return;
        }
        if (!chronoCaptured) return;

        LegendEntry existing = legends.findLatestForPlayerName(player.getName().getString());
        if (existing == null) return;
        if (existing.bestTimeMs() == 0L || elapsed < existing.bestTimeMs()) {
            LegendEntry updated = existing.withBestTime(elapsed);
            var all = legends.getAllIncludingTests();
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).uuid().equals(existing.uuid())
                        && all.get(i).completedAt() == existing.completedAt()) {
                    legends.replaceEntry(i, updated); break;
                }
            }
            String timeStr = com.cobblecap.logic.ChronoManager.formatDuration(elapsed);
            server.getPlayerManager().broadcast(
                    Text.literal("🏆 " + player.getName().getString() + " bat son record : " + timeStr + " !")
                            .formatted(Formatting.GOLD), false);
            try { com.cobblecap.logic.Conseil4Boards.rebuild(server); } catch (Exception ignored) {}
        } else {
            String timeStr = com.cobblecap.logic.ChronoManager.formatDuration(elapsed);
            server.getPlayerManager().broadcast(
                    Text.literal("⏱ " + player.getName().getString() + " a fini en " + timeStr
                            + " (record perso : " + com.cobblecap.logic.ChronoManager.formatDuration(existing.bestTimeMs()) + ")")
                            .formatted(Formatting.GRAY), false);
        }
    }

    /**
     * Handle a RANKED-mode Conseil 4 victory: ALWAYS starts a new champion
     * tenure (per user spec 2026-05-17 — even the same player re-claiming gets
     * a fresh failedAttempts slate). Triggers the RctTrainerMorpher so future
     * ranked challengers face this player. Does NOT touch LegendsStorage —
     * ranked history is its own storage (TenureStorage). Always broadcasts +
     * webhook since a champion change is always newsworthy.
     */
    private static void handleRankedWin(ServerPlayerEntity player, MinecraftServer server, long elapsedOr0) {
        var tenures = CobbleCapMod.INSTANCE.getTenureStorage();
        var previous = tenures.current();
        com.cobblecap.data.Tenure created = tenures.startNewTenure(
                player.getUuid(), player.getName().getString(), elapsedOr0);

        // Broadcast — first-ever ranked = bold/everyone-pingy; subsequent = "new champion".
        boolean firstEver = previous == null;
        String timeStr = elapsedOr0 > 0 ? com.cobblecap.logic.ChronoManager.formatDuration(elapsedOr0) : null;
        Text msg;
        if (firstEver) {
            msg = Text.literal("👑 " + player.getName().getString()
                    + " entre dans l'histoire comme PREMIER Maître de la Ligue Compétitif"
                    + (timeStr != null ? " (en " + timeStr + ") !" : " !"))
                    .formatted(Formatting.GOLD, Formatting.BOLD);
        } else {
            msg = Text.literal("👑 " + player.getName().getString()
                    + " détrône " + previous.championName + " et devient le nouveau Maître de la Ligue"
                    + (timeStr != null ? " (en " + timeStr + ") !" : " !"))
                    .formatted(Formatting.GOLD, Formatting.BOLD);
        }
        server.getPlayerManager().broadcast(msg, false);

        // For the webhook, build a synthetic LegendEntry from the tenure data —
        // keeps the existing webhook shape without inflating LegendsStorage.
        List<LegendEntry.TeamMember> team = snapshotPlayerTeam(player);
        LegendEntry webhookEntry = new LegendEntry(
                player.getUuid(), player.getName().getString(),
                created.startedAt, firstEver, false, elapsedOr0, team, LegendEntry.Mode.RANKED);
        CobbleCapWebhook.sendLeagueMaster(webhookEntry);

        // Refresh boards (ranked champions + failures) + queue the RCT morph so
        // the live Régis JSON's "ranked" snapshot reflects this new champion.
        try { com.cobblecap.logic.Conseil4Boards.rebuild(server); } catch (Exception ignored) {}
        CobbleCapMod.INSTANCE.getRctMorpher().request(player, elapsedOr0);

        // 🎆 Celebratory fireworks show in the configured CheeseGuard zone.
        try { com.cobblecap.logic.FireworksManager.triggerShow(server); }
        catch (Exception e) { LOGGER.debug("[CobbleCap] fireworks show skipped: {}", e.getMessage()); }

        LOGGER.info("[CobbleCap] {} became RANKED Maître de la Ligue (tenureId={}, firstEver={}, chrono={}ms, previous={})",
                player.getName().getString(), created.id, firstEver, elapsedOr0,
                previous != null ? previous.championName : "(none)");
    }

    /** Broadcast the classic-mode win using the existing translation keys. */
    private static void broadcastClassicWin(ServerPlayerEntity player, MinecraftServer server,
                                             boolean firstEver, boolean chronoCaptured, long elapsed) {
        String key;
        Text msg;
        if (chronoCaptured) {
            String timeStr = com.cobblecap.logic.ChronoManager.formatDuration(elapsed);
            key = firstEver ? "cobblecap.msg.league_master_first_chrono"
                            : "cobblecap.msg.league_master_chrono";
            msg = Text.translatable(key, player.getName().getString(), timeStr)
                    .formatted(Formatting.GOLD, Formatting.BOLD);
        } else {
            key = firstEver ? "cobblecap.msg.league_master_first"
                            : "cobblecap.msg.league_master_broadcast";
            msg = Text.translatable(key, player.getName().getString())
                    .formatted(Formatting.GOLD, Formatting.BOLD);
        }
        server.getPlayerManager().broadcast(msg, false);
    }

    /** Read the {@code cobblecap_mode} scoreboard score (1=classic, 2=ranked). */
    public static LegendEntry.Mode readPlayerMode(ServerPlayerEntity player) {
        try {
            var sb = player.getServer().getScoreboard();
            var obj = sb.getNullableObjective("cobblecap_mode");
            if (obj == null) return LegendEntry.Mode.CLASSIC;
            var score = sb.getScore(player, obj);
            if (score == null) return LegendEntry.Mode.CLASSIC;
            return score.getScore() == 2 ? LegendEntry.Mode.RANKED : LegendEntry.Mode.CLASSIC;
        } catch (Exception e) {
            return LegendEntry.Mode.CLASSIC;
        }
    }

    /**
     * Loss detection: called from handleBattleVictoryEvent when no player won
     * (i.e. a trainer NPC won the battle). If the winner was Régis and the
     * loser was a player in ranked mode, tally the failure against the current
     * champion tenure. Safe to call with no current tenure (silently no-ops).
     */
    private static void handleRegisRankedLoss(Object event, MinecraftServer server) {
        try {
            // Identify the NPC winner — must be the Régis trainer.
            Object winners = CapManager.invokeGetter(event, "getWinners", "winners");
            Entity winnerEntity = null;
            if (winners instanceof Iterable<?> iter) {
                for (Object actor : iter) {
                    Object ent = CapManager.invokeGetter(actor, "getEntity", "getNPC", "entity");
                    if (ent instanceof Entity e) { winnerEntity = e; break; }
                }
            }
            if (winnerEntity == null) return;
            String winnerTrainerId = stripPerDimSuffix(extractRctmodTrainerId(winnerEntity));
            if (!"champion_regis_challenge".equals(winnerTrainerId)) return;

            // Identify the player loser.
            Object losers = CapManager.invokeGetter(event, "getLosers", "losers");
            UUID loserUuid = null;
            if (losers instanceof Iterable<?> iter) {
                for (Object actor : iter) {
                    UUID uuid = getActorPlayerUuid(actor);
                    if (uuid != null) { loserUuid = uuid; break; }
                }
            }
            if (loserUuid == null) return;
            ServerPlayerEntity loser = server.getPlayerManager().getPlayer(loserUuid);
            if (loser == null) return;
            if (readPlayerMode(loser) != LegendEntry.Mode.RANKED) return; // classic loss — not counted

            CobbleCapMod.INSTANCE.getTenureStorage().recordFailure(loserUuid, loser.getName().getString());
            try { com.cobblecap.logic.Conseil4Boards.rebuild(server); } catch (Exception ignored) {}
            LOGGER.info("[CobbleCap] {} lost to Régis (ranked mode) — failure tallied", loser.getName().getString());
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] handleRegisRankedLoss error: {}", e.getMessage());
        }
    }

    /**
     * Capture an immutable snapshot of the player's current Cobblemon party for
     * the hall of fame. Returns an empty list on any reflection failure — never throws.
     * Must be called from the server thread (Cobblemon party storage isn't
     * thread-safe for reads).
     */
    public static List<LegendEntry.TeamMember> snapshotPlayerTeam(ServerPlayerEntity player) {
        List<LegendEntry.TeamMember> result = new ArrayList<>();
        try {
            Iterable<?> party = getPartyIterable(player);
            if (party == null) return result;
            for (Object poke : party) {
                if (poke == null) continue;
                String species = safeLower(getSpeciesName(poke));
                String displayName = getSpeciesDisplayName(poke);
                String nickname = safeString(invokeText(poke, "getNickname"));
                int level = 0;
                Object lvlObj = CapManager.invokeGetter(poke, "getLevel", "level");
                if (lvlObj instanceof Number n) level = n.intValue();
                boolean shiny = false;
                Object shinyObj = CapManager.invokeGetter(poke, "getShiny", "isShiny", "shiny");
                if (shinyObj instanceof Boolean b) shiny = b;
                String ball = safeString(invokeEnumName(poke, "getCaughtBall"));
                if (ball.isEmpty()) ball = safeLower(safeString(CapManager.invokeGetter(poke, "getCaughtBall")));
                String nature = safeLower(safeString(invokeEnumName(poke, "getNature")));
                if (nature.isEmpty()) nature = safeLower(safeString(CapManager.invokeGetter(poke, "getNature")));
                String gender = safeString(invokeEnumName(poke, "getGender"));

                // Capture Cobblemon "aspects" (form/regional/cosmetic variants) so a
                // statue can later be respawned with the exact same look.
                java.util.List<String> aspects = readAspects(poke);
                String form = pickPrimaryForm(aspects);

                result.add(new LegendEntry.TeamMember(
                        species == null ? "" : species,
                        displayName == null ? "" : displayName,
                        nickname == null ? "" : nickname,
                        level,
                        shiny,
                        ball == null ? "" : ball.toLowerCase(Locale.ROOT),
                        nature == null ? "" : nature,
                        gender == null ? "" : gender.toUpperCase(Locale.ROOT),
                        form == null ? "" : form,
                        aspects
                ));
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] snapshotPlayerTeam error: {}", e.getMessage());
        }
        return result;
    }

    private static String safeLower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String safeString(Object o) {
        return o == null ? "" : o.toString();
    }

    /** Read a Text-returning getter and resolve to a plain string. */
    private static String invokeText(Object instance, String getterName) {
        Object value = CapManager.invokeGetter(instance, getterName);
        if (value == null) return "";
        try {
            Method getString = CapManager.findMethod(value.getClass(), "getString");
            if (getString != null) {
                Object s = getString.invoke(value);
                return s == null ? "" : s.toString();
            }
        } catch (Exception ignored) {}
        return value.toString();
    }

    /**
     * Best-effort: read the Cobblemon Pokémon's "aspects" set via reflection.
     * Returns a sorted list of distinct aspect strings; empty on any failure.
     * Cobblemon stores aspects as Set&lt;String&gt; accessible via getAspects().
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> readAspects(Object pokemon) {
        java.util.List<String> out = new ArrayList<>();
        try {
            Object aspectsObj = CapManager.invokeGetter(pokemon, "getAspects", "aspects");
            if (aspectsObj instanceof java.util.Collection<?> col) {
                for (Object o : col) if (o != null) out.add(o.toString());
            }
            java.util.Collections.sort(out);
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Heuristic to extract the primary "form" aspect from an aspect set —
     * filters out cosmetic flags (shiny/gender/etc.) and keeps the first form-like token.
     */
    private static String pickPrimaryForm(java.util.List<String> aspects) {
        if (aspects == null || aspects.isEmpty()) return "";
        java.util.Set<String> cosmetic = java.util.Set.of(
                "shiny", "male", "female", "genderless", "shadow"
        );
        for (String a : aspects) {
            String lower = a.toLowerCase(Locale.ROOT);
            if (cosmetic.contains(lower)) continue;
            return lower;
        }
        return "";
    }

    /** Read an enum-returning getter and return its name(). Returns empty if not an enum. */
    private static String invokeEnumName(Object instance, String getterName) {
        Object value = CapManager.invokeGetter(instance, getterName);
        if (value == null) return "";
        if (value.getClass().isEnum()) return ((Enum<?>) value).name();
        // Many Cobblemon types have a name()/getName() method even when not enums
        try {
            Method m = CapManager.findMethod(value.getClass(), "getName");
            if (m != null) {
                Object n = m.invoke(value);
                if (n != null) return n.toString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Dispatch an FTB Quests command to mark the victory quest as complete.
     */
    static void triggerFtbQuest(ServerPlayerEntity player, ChampionEntry champ, MinecraftServer server) {
        if (champ.victoryQuestId() == null || champ.victoryQuestId().isEmpty()) return;
        triggerFtbQuestById(player, champ.victoryQuestId(), server);
    }

    /**
     * Grant a hidden advancement used as FTB Quests task trigger.
     * Advancement ID: cobblecap:{category}/{key} where key is derived from displayName.
     */
    static void grantAdvancement(ServerPlayerEntity player, String displayName, String category, MinecraftServer server) {
        String key = displayName.toLowerCase(Locale.ROOT).replace(" ", "_");
        String advId = "cobblecap:" + category + "/" + key;
        String cmd = "advancement grant " + player.getName().getString() + " only " + advId;
        LOGGER.info("[CobbleCap] Granting advancement: {}", cmd);
        try {
            var dispatcher = server.getCommandManager().getDispatcher();
            var src = server.getCommandSource().withSilent();
            int result = dispatcher.execute(cmd, src);
            LOGGER.info("[CobbleCap] Advancement grant result: {} (1=success, 0=already had)", result);
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Advancement grant failed for {}: {}", player.getName().getString(), e.getMessage());
        }
    }

    static void triggerFtbQuestById(ServerPlayerEntity player, String questId, MinecraftServer server) {
        if (questId == null || questId.isEmpty()) return;
        // Delay 3 ticks to let FTB Quests detect the advancement naturally first,
        // then force-complete as a safety net. This avoids client desync.
        final String playerName = player.getName().getString();
        final String cmd = "ftbquests complete_quest " + playerName + " " + questId;
        LOGGER.info("[CobbleCap] Scheduling FTB quest trigger (3 tick delay): {}", cmd);
        scheduleDelayed(server, 3, () -> {
            try {
                var dispatcher = server.getCommandManager().getDispatcher();
                var src = server.getCommandSource().withSilent();
                int result = dispatcher.execute(cmd, src);
                LOGGER.info("[CobbleCap] FTB quest trigger result: {}", result);
            } catch (Exception e) {
                LOGGER.warn("[CobbleCap] FTB quest trigger failed for {}: {}", playerName, e.getMessage());
            }
        });
    }

    /** Run a task on the server thread after a few ticks (chained server.execute). */
    private static void scheduleDelayed(MinecraftServer server, int ticks, Runnable task) {
        Runnable chain = task;
        for (int i = 0; i < ticks; i++) {
            final Runnable next = chain;
            chain = () -> server.execute(next);
        }
        server.execute(chain);
    }

    /**
     * Extract a player UUID from a Cobblemon battle actor via reflection.
     * Returns null if not a player actor or UUID cannot be resolved.
     */
    static UUID getActorPlayerUuid(Object actor) {
        try {
            Object uuids = CapManager.invokeGetter(actor, "getPlayerUUIDs", "getUUIDs");
            if (uuids instanceof Iterable<?> iter) {
                for (Object o : iter) {
                    if (o instanceof UUID u) return u;
                }
            }
            Object uuid = CapManager.invokeGetter(actor, "getUuid", "uuid", "getPlayerId");
            if (uuid instanceof UUID u) return u;
        } catch (Exception ignored) {}
        return null;
    }

    // ── Banned species check ─────────────────────────────────────────────────

    /**
     * Scan the player's party for any species on the banned list.
     * Returns a list of banned species names found (empty = OK).
     * Fails open on any error (returns empty list).
     */
    public static List<String> findBannedSpeciesInParty(ServerPlayerEntity player) {
        List<String> found = new ArrayList<>();
        try {
            List<String> banned = CobbleCapMod.INSTANCE.getCapConfig().getBannedSpecies();
            if (banned.isEmpty()) return found;

            Iterable<?> partyIter = getPartyIterable(player);
            if (partyIter == null) return found;

            for (Object poke : partyIter) {
                if (poke == null) continue;
                String speciesName = getSpeciesName(poke);
                if (speciesName != null && CobbleCapMod.INSTANCE.getCapConfig().isSpeciesBanned(speciesName)) {
                    // Get display name for the message
                    String displayName = getSpeciesDisplayName(poke);
                    if (displayName != null && !found.contains(displayName)) {
                        found.add(displayName);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] findBannedSpeciesInParty error: {}", e.getMessage());
        }
        return found;
    }

    /**
     * Get Cobblemon species showdown ID (lowercase) from a Pokemon object via reflection.
     */
    private static String getSpeciesName(Object pokemon) {
        try {
            Object species = CapManager.invokeGetter(pokemon, "getSpecies");
            if (species != null) {
                // showdownId() returns lowercase species name
                Object showdownId = CapManager.invokeGetter(species, "showdownId", "getShowdownId", "getName");
                if (showdownId != null) return showdownId.toString().toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Get a human-readable display name for a Pokémon.
     */
    private static String getSpeciesDisplayName(Object pokemon) {
        try {
            Object displayName = CapManager.invokeGetter(pokemon, "getDisplayName");
            if (displayName != null) {
                Method getString = CapManager.findMethod(displayName.getClass(), "getString");
                if (getString != null) return (String) getString.invoke(displayName);
                return displayName.toString();
            }
            Object species = CapManager.invokeGetter(pokemon, "getSpecies");
            if (species != null) {
                Object name = CapManager.invokeGetter(species, "getName", "showdownId");
                if (name != null) {
                    String s = name.toString();
                    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    /**
     * Get the player's party as an Iterable via reflection.
     * Shared between banned species check and party cap check.
     */
    private static Iterable<?> getPartyIterable(ServerPlayerEntity player) {
        try {
            Class<?> cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object cobblemonInstance = cobblemonClass.getField("INSTANCE").get(null);
            Object storage = CapManager.invokeGetter(cobblemonInstance, "getStorage", "storage");
            if (storage == null) return null;

            Object party = null;
            for (Method m : storage.getClass().getMethods()) {
                String mn = m.getName();
                if ((mn.equals("getParty") || mn.equals("getPlayerParty")) && m.getParameterCount() == 1) {
                    try { party = m.invoke(storage, player); break; }
                    catch (Exception ignored) {}
                    try { party = m.invoke(storage, player.getUuid()); break; }
                    catch (Exception ignored) {}
                }
            }
            if (party == null) return null;

            Object pokemon = CapManager.invokeGetter(party, "iterator", "getPokemon", "toGappyList", "getAll");
            if (pokemon instanceof Iterable<?> it) return it;
            if (party instanceof Iterable<?> it) return it;
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] getPartyIterable error: {}", e.getMessage());
        }
        return null;
    }

    // ── Party cap violation check ─────────────────────────────────────────────

    /**
     * Check if any Pokémon in the player's party is at or above their cap level.
     * Returns false on any error (fail open — don't cancel battle if check fails).
     */
    public static boolean playerPartyViolatesCap(ServerPlayerEntity player) {
        try {
            Optional<PlayerCapData> dataOpt = CobbleCapMod.INSTANCE.getCapStorage().getCapData(player.getUuid());
            if (dataOpt.isEmpty()) return false;

            int cap = dataOpt.get().getCapLevel();

            Iterable<?> iter = getPartyIterable(player);
            if (iter == null) return false;

            for (Object poke : iter) {
                if (poke == null) continue;
                Object levelObj = CapManager.invokeGetter(poke, "getLevel", "level");
                if (levelObj instanceof Number n) {
                    if (n.intValue() > cap) return true;
                }
            }

        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] playerPartyViolatesCap error: {}", e.getMessage());
        }
        return false; // fail open
    }
}
