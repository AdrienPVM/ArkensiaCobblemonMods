package com.cobblecap.logic;

import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.ChampionEntry;
import com.cobblecap.data.CapStorage;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class CapListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    public static void register(MinecraftServer ignoredServer, CapStorage capStorage) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.player;
            capStorage.ensureExists(player.getUuid(), player.getName().getString());
            capStorage.getCapData(player.getUuid()).ifPresent(data ->
                LOGGER.info("[CobbleCap] Player joined: {} (cap={})", data.getPlayerName(), data.getCapLevel())
            );
            CobbleCapMod.syncToClient(handler.player);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[CobbleCap] Server stopping — saving player data synchronously");
            capStorage.save();
        });

        // Hook Cobblemon XP events once the server has fully started (all mods loaded)
        ServerLifecycleEvents.SERVER_STARTED.register(CobblemonHook::tryInit);

        // ── UseEntityCallback: intercept rctmod champion NPC interactions ──────
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            // Only intercept server-side player interactions
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            // Avoid double-fire on off-hand
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;

            // System must be enabled
            if (!CobbleCapMod.INSTANCE.getCapConfig().isEnabled()) return ActionResult.PASS;

            // Only intercept entities from the rctmod namespace
            String entityNs = Registries.ENTITY_TYPE.getId(entity.getType()).getNamespace();
            if (!"rctmod".equals(entityNs)) return ActionResult.PASS;

            // Extract trainer ID from the entity. Strip the per-dim suffix (added by
            // Conseil4TrainerCloner for per-player conseil4 dims) so config lookups
            // — findByChallengeId / findById — match the canonical entries.
            String rawTrainerId = ChampionManager.extractRctmodTrainerId(entity);
            if (rawTrainerId == null) return ActionResult.PASS;
            String trainerId = ChampionManager.stripPerDimSuffix(rawTrainerId);
            // When restoring the stuck challenge ID below, preserve the per-dim suffix
            // so we don't accidentally undo Conseil4TrainerCloner's per-dim isolation.
            String suffix = rawTrainerId.equals(trainerId) ? null
                    : rawTrainerId.substring(trainerId.length() + 1);

            // Check if this is a challenge variant first
            Optional<ChampionEntry> challengeChamp = CobbleCapMod.INSTANCE.getChampionConfig().findByChallengeId(trainerId);
            if (challengeChamp.isPresent()) {
                ChampionEntry champ = challengeChamp.get();
                // If the player hasn't beaten this champion normally yet, the entity's
                // trainer ID is stuck on the challenge variant (failed restore from swapToChallengeBattle).
                // Restore it to the normal ID and treat as a normal fight.
                var dataOpt = CobbleCapMod.INSTANCE.getCapStorage().getCapData(sp.getUuid());
                if (dataOpt.isPresent() && !dataOpt.get().getChampionsBeaten().contains(champ.id())) {
                    // Preserve the per-dim suffix when restoring so Conseil4TrainerCloner's
                    // isolation isn't broken inside a per-player conseil4 dim.
                    String restoreId = suffix == null ? champ.id() : champ.id() + "_" + suffix;
                    LOGGER.info("[CobbleCap] Entity has stuck challenge ID '{}' but player hasn't beaten '{}' — restoring to '{}'",
                            rawTrainerId, champ.id(), restoreId);
                    try {
                        java.lang.reflect.Method setId = entity.getClass().getMethod("setTrainerId", String.class);
                        setId.invoke(entity, restoreId);
                    } catch (Exception e) {
                        LOGGER.debug("[CobbleCap] Could not restore trainer ID: {}", e.getMessage());
                    }
                    return ChampionManager.handlePreBattle(sp, champ);
                }
                // Block if challenge already beaten (one-time fight)
                if (dataOpt.isPresent() && dataOpt.get().getChallengeBeaten().contains(champ.id())) {
                    if (ChampionManager.shouldSendMessage(sp.getUuid())) {
                        sp.sendMessage(
                            net.minecraft.text.Text.translatable("cobblecap.msg.champion_post_victory",
                                    champ.displayName())
                                .formatted(net.minecraft.util.Formatting.YELLOW),
                            false
                        );
                    }
                    return ActionResult.FAIL;
                }
                return ChampionManager.handleChallengePreBattle(sp, champ);
            }

            // Only intercept champions (regular trainers pass through)
            Optional<ChampionEntry> champ = CobbleCapMod.INSTANCE.getChampionConfig().findById(trainerId);
            if (champ.isEmpty()) return ActionResult.PASS;

            // If already beaten in normal mode — block refight with post-victory message
            // (challenge mode becomes available based on champion config)
            var dataOpt = CobbleCapMod.INSTANCE.getCapStorage().getCapData(sp.getUuid());
            if (dataOpt.isPresent() && dataOpt.get().getChampionsBeaten().contains(champ.get().id())) {
                // Has challenge variant → check if challenge is unlocked
                if (champ.get().challengeId() != null && !champ.get().challengeId().isEmpty()) {
                    boolean challengeUnlocked = champ.get().challengeRequiresAllBeaten()
                            ? dataOpt.get().hasAllNormalChampionsBeaten(
                                CobbleCapMod.INSTANCE.getChampionConfig().getChampions())
                            : true; // No all-beaten gate → challenge available as soon as normal is beaten
                    // Also check scoreboard gate for challenge
                    if (challengeUnlocked && champ.get().challengeRequiredScoreMin() > 0
                            && champ.get().requiredScoreboard() != null && !champ.get().requiredScoreboard().isEmpty()) {
                        int score = ChampionManager.getPlayerScore(sp, champ.get().requiredScoreboard());
                        if (score < champ.get().challengeRequiredScoreMin()) {
                            challengeUnlocked = false;
                        }
                    }
                    // Block if challenge already beaten (one-time fight)
                    if (challengeUnlocked
                            && dataOpt.get().getChallengeBeaten().contains(champ.get().id())) {
                        challengeUnlocked = false;
                    }
                    if (challengeUnlocked) return ActionResult.PASS;
                }
                // Post-victory: block refight with a friendly message
                if (ChampionManager.shouldSendMessage(sp.getUuid())) {
                    sp.sendMessage(
                        net.minecraft.text.Text.translatable("cobblecap.msg.champion_post_victory",
                                champ.get().displayName())
                            .formatted(net.minecraft.util.Formatting.YELLOW),
                        false
                    );
                }
                return ActionResult.FAIL;
            }

            // Run pre-battle validation
            return ChampionManager.handlePreBattle(sp, champ.get());
        });
    }
}
