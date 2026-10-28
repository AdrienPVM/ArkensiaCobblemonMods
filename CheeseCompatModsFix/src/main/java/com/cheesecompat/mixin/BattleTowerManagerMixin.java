package com.cheesecompat.mixin;

import battle.tower.battle.BattleTowerManager;
import battle.tower.data.PlayerTowerData;
import battle.tower.data.TeamSnapshot;
import battle.tower.data.TowerDataManager;
import com.cheesecompat.BattleTowerDisconnectFix;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Three fixes on BattleTowerManager:
 * 1) isAcademyPresent -> always true (unlocks NPC level forcing)
 * 2) startBattle -> block if player is already in a Cobblemon battle
 * 3) startBattle -> auto-clear stale team lock after forfeit (prevents "team changed" ghost state)
 */
@Mixin(value = BattleTowerManager.class, remap = false)
public class BattleTowerManagerMixin {

    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompat");

    /**
     * Quick team validation: checks if the current party UUIDs match the snapshot UUIDs.
     * Returns true if teams match, false if stale/mismatched.
     */
    @Unique
    private static boolean cheesecompat$validateTeam(ServerPlayerEntity player, PlayerTowerData data) {
        try {
            TeamSnapshot snapshot = data.getTeamSnapshot();
            if (snapshot == null || !snapshot.hasSnapshot()) return false;

            // Get party via reflection to avoid obfuscated class resolution
            Class<?> cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object instance = cobblemonClass.getField("INSTANCE").get(null);
            Object storage = cobblemonClass.getMethod("getStorage").invoke(instance);
            Object party = null;
            for (Method m : storage.getClass().getMethods()) {
                if (m.getName().equals("getParty") && m.getParameterCount() == 1) {
                    party = m.invoke(storage, player);
                    break;
                }
            }
            if (party == null) return true; // Fail open

            // Get party size and pokemon UUIDs via reflection
            Method sizeMethod = party.getClass().getMethod("size");
            int partySize = (int) sizeMethod.invoke(party);
            Method getMethod = party.getClass().getMethod("get", int.class);

            ArrayList<UUID> currentUUIDs = new ArrayList<>();
            for (int i = 0; i < partySize; i++) {
                Object p = getMethod.invoke(party, i);
                if (p instanceof Pokemon poke) currentUUIDs.add(poke.getUuid());
            }

            List<Pokemon> snapshotTeam = snapshot.getOriginalTeam();
            if (currentUUIDs.size() != snapshotTeam.size()) return false;

            HashSet<UUID> snapshotUUIDs = new HashSet<>();
            for (Pokemon p : snapshotTeam) snapshotUUIDs.add(p.getUuid());

            return snapshotUUIDs.equals(new HashSet<>(currentUUIDs));
        } catch (Exception e) {
            return true; // Fail open — let BT's own validation handle it
        }
    }

    /**
     * Force re-capture the team snapshot using the player's current party.
     * Bypasses copyFrom corruption by directly copying UUIDs/species instead of cloning.
     */
    @Unique
    private static void cheesecompat$reLockTeam(ServerPlayerEntity player, PlayerTowerData data) {
        try {
            // Get current party via reflection
            Class<?> cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object instance = cobblemonClass.getField("INSTANCE").get(null);
            Object storage = cobblemonClass.getMethod("getStorage").invoke(instance);
            Object party = null;
            for (Method m : storage.getClass().getMethods()) {
                if (m.getName().equals("getParty") && m.getParameterCount() == 1) {
                    party = m.invoke(storage, player);
                    break;
                }
            }
            if (party == null) return;

            Method sizeMethod = party.getClass().getMethod("size");
            int partySize = (int) sizeMethod.invoke(party);
            Method getMethod = party.getClass().getMethod("get", int.class);

            ArrayList<Pokemon> validPokemon = new ArrayList<>();
            for (int i = 0; i < partySize; i++) {
                Object p = getMethod.invoke(party, i);
                if (p instanceof Pokemon poke && !poke.isFainted()) {
                    validPokemon.add(poke);
                }
            }

            if (!validPokemon.isEmpty()) {
                // Re-capture using the TeamSnapshot's captureTeam
                // The TeamSnapshotMixin will protect IVs/EVs during copyFrom
                data.getTeamSnapshot().captureTeam(validPokemon, player.getUuid());
                TowerDataManager.savePlayerData(player.getUuid());

                // Rewrite the disk backup to match the freshly re-captured snapshot.
                // Otherwise the stale backup would still be used by BattleTowerDisconnectFix
                // if the player disconnects before completing the next battle.
                BattleTowerDisconnectFix.writeBackupFromSnapshot(
                    player.getUuid(), data.getTeamSnapshot(), player.getServer());
            }
        } catch (Exception e) {
            LoggerFactory.getLogger("CheeseCompat").warn(
                "[CheeseCompat] reLockTeam failed: {}", e.getMessage());
        }
    }

    @Unique private static Method cheesecompat$getBattleMethod;
    @Unique private static Object cheesecompat$battleRegistry;
    @Unique private static boolean cheesecompat$reflectionFailed = false;
    @Unique private static boolean cheesecompat$cobbleCapBypassFailed = false;

    /** Tracks the floor number at which each player's last BT battle was started. */
    @Unique private static final Map<UUID, Integer> cheesecompat$btFloorTracker = new ConcurrentHashMap<>();

    /** Tracks total battles fought per player across BT runs (resets on server restart). */
    @Unique private static final Map<UUID, Integer> cheesecompat$btTotalBattles = new ConcurrentHashMap<>();

    /**
     * Adaptive cooldown: scales with floor number to give showdown more cleanup time
     * as cumulative state grows. Floor 2-5: 1500ms, 6-10: 2000ms, 11-15: 2500ms, 16+: 3000ms.
     */
    @Unique
    private static long cheesecompat$getCooldownMs(int floor) {
        if (floor <= 5) return 1500;
        if (floor <= 10) return 2000;
        if (floor <= 15) return 2500;
        return 3000;
    }

    /**
     * Set or clear CobbleCap's LEVEL_FORCE_ACTIVE ThreadLocal via reflection.
     * This bypasses PokemonSetLevelMixin and BATTLE_PRE party-cap checks
     * so Battle Tower can force levels above the player's cap.
     */
    @Unique
    @SuppressWarnings("unchecked")
    private static void cheesecompat$setCobbleCapBypass(boolean active) {
        if (cheesecompat$cobbleCapBypassFailed) return;
        try {
            Class<?> cobbleCapClass = Class.forName("com.cobblecap.CobbleCapMod");
            Field field = cobbleCapClass.getField("LEVEL_FORCE_ACTIVE");
            ThreadLocal<Boolean> threadLocal = (ThreadLocal<Boolean>) field.get(null);
            threadLocal.set(active);
            if (active) {
                LOGGER.debug("[CheeseCompat] CobbleCap level force bypass ENABLED");
            }
        } catch (ClassNotFoundException ignored) {
            // CobbleCap not present — no bypass needed
            cheesecompat$cobbleCapBypassFailed = true;
        } catch (Exception e) {
            LOGGER.warn("[CheeseCompat] Failed to set CobbleCap bypass: {}", e.getMessage());
            cheesecompat$cobbleCapBypassFailed = true;
        }
    }

    @Unique
    private static boolean cheesecompat$forceCloseStaleBattle(Object battle) {
        try {
            // Approach 1: BattleRegistry.closeBattle(battle)
            for (Method m : cheesecompat$battleRegistry.getClass().getMethods()) {
                if (m.getName().equals("closeBattle") && m.getParameterCount() == 1) {
                    m.invoke(cheesecompat$battleRegistry, battle);
                    LOGGER.info("[CheeseCompat] closeBattle() succeeded on stale battle");
                    return true;
                }
            }
            // Approach 2: battle.end()
            for (Method m : battle.getClass().getMethods()) {
                if (m.getName().equals("end") && m.getParameterCount() == 0) {
                    m.invoke(battle);
                    LOGGER.info("[CheeseCompat] battle.end() succeeded on stale battle");
                    return true;
                }
            }
            LOGGER.error("[CheeseCompat] No close/end method found on {} / {}",
                cheesecompat$battleRegistry.getClass().getSimpleName(),
                battle.getClass().getSimpleName());
            return false;
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] forceCloseStaleBattle exception: {}", e.getMessage());
            return false;
        }
    }

    @Inject(method = "isAcademyPresent", at = @At("HEAD"), cancellable = true)
    private static void forceAcademyPresent(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    /**
     * Diagnostic: log when forced level is applied to a player's team.
     * Helps debug whether Level 50/100 normalization is working.
     */
    @Inject(method = "applyForcedLevelFallback", at = @At("HEAD"))
    private static void cheesecompat$logForcedLevelApply(ServerPlayerEntity player, int forcedLevel,
                                                          CallbackInfoReturnable<Map> cir) {
        LOGGER.info("[CheeseCompat] Applying forced level {} to {}'s team",
            forcedLevel, player.getName().getString());
    }

    @Inject(method = "applyForcedLevelFallback", at = @At("RETURN"))
    private static void cheesecompat$logForcedLevelResult(ServerPlayerEntity player, int forcedLevel,
                                                           CallbackInfoReturnable<Map> cir) {
        Map<?, ?> result = cir.getReturnValue();
        if (result != null && !result.isEmpty()) {
            LOGGER.info("[CheeseCompat] Forced level applied: {} Pokemon set to Lv{} for {}",
                result.size(), forcedLevel, player.getName().getString());
        } else {
            LOGGER.warn("[CheeseCompat] Forced level FAILED or empty for {} (level {})",
                player.getName().getString(), forcedLevel);
        }
        // sendTo() moved to startBattle RETURN — calling it here (between forced levels
        // and battle creation) sent a full party sync packet that could arrive on the client
        // during Cobblemon's battle initialization, preventing the player's Pokemon from spawning.
    }

    @Unique
    private static void cheesecompat$logPartyState(ServerPlayerEntity player) {
        try {
            Class<?> cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object instance = cobblemonClass.getField("INSTANCE").get(null);
            Object storage = cobblemonClass.getMethod("getStorage").invoke(instance);
            Object party = null;
            for (Method m : storage.getClass().getMethods()) {
                if (m.getName().equals("getParty") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(ServerPlayerEntity.class)) {
                    party = m.invoke(storage, player);
                    break;
                }
            }
            if (party == null) return;

            int size = (int) party.getClass().getMethod("size").invoke(party);
            Method getMethod = party.getClass().getMethod("get", int.class);
            Class<?> pokemonClass = Class.forName("com.cobblemon.mod.common.pokemon.Pokemon");
            Method getMoveSet = pokemonClass.getMethod("getMoveSet");
            Method getSpecies = pokemonClass.getMethod("getSpecies");
            Method getLevel = pokemonClass.getMethod("getLevel");

            for (int i = 0; i < size; i++) {
                Object pokemon = getMethod.invoke(party, i);
                if (pokemon == null) continue;
                try {
                    Object species = getSpecies.invoke(pokemon);
                    int level = (int) getLevel.invoke(pokemon);
                    Object moveSet = getMoveSet.invoke(pokemon);
                    StringBuilder movesDetail = new StringBuilder();
                    Method getMoves = moveSet.getClass().getMethod("getMoves");
                    Object movesList = getMoves.invoke(moveSet);
                    if (movesList instanceof Iterable<?> moves) {
                        for (Object move : moves) {
                            if (movesDetail.length() > 0) movesDetail.append(", ");
                            Method getName = move.getClass().getMethod("getName");
                            Method getCurrentPp = move.getClass().getMethod("getCurrentPp");
                            Method getMaxPp = move.getClass().getMethod("getMaxPp");
                            movesDetail.append(getName.invoke(move))
                                .append("(").append(getCurrentPp.invoke(move))
                                .append("/").append(getMaxPp.invoke(move)).append(")");
                        }
                    }
                    LOGGER.info("[CheeseCompat] Pre-battle slot[{}]: {} Lv{} moves=[{}]",
                        i, species, level, movesDetail);
                } catch (Exception e) {
                    LOGGER.warn("[CheeseCompat] Pre-battle log failed slot {}: {}", i, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] Party log exception: {}", e.getMessage());
        }
    }

    @Unique
    private static void cheesecompat$verifyBattleInRegistry(ServerPlayerEntity player) {
        if (cheesecompat$reflectionFailed || cheesecompat$getBattleMethod == null) return;
        try {
            Object battle = cheesecompat$getBattleMethod.invoke(cheesecompat$battleRegistry, player);
            if (battle != null) {
                LOGGER.info("[CheeseCompat] Battle registry CONFIRMED for {} — battle={}",
                    player.getName().getString(), battle.getClass().getSimpleName());
            } else {
                LOGGER.error("[CheeseCompat] Battle registry EMPTY for {} — battle vanished immediately!",
                    player.getName().getString());
            }
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] Battle registry check failed: {}", e.getMessage());
        }
    }

    @Inject(method = "startBattle", at = @At("HEAD"), cancellable = true)
    private static void preventIfInCobblemonBattle(ServerPlayerEntity player, BlockPos pos,
                                                    CallbackInfoReturnable<?> cir) {
        // Clear any stale bypass from a previous failed startBattle
        cheesecompat$setCobbleCapBypass(false);

        // Fix 0: Block if a disk backup restore is still pending for this player.
        // Without this, a player who reconnects and immediately speed-runs /battletower
        // would race BattleTowerDisconnectFix's tick-based restore loop, potentially
        // locking a team that still has BT's forced levels applied (or the loadFromNBT
        // target Pokemon list mid-rebuild).
        if (BattleTowerDisconnectFix.isRestorePending(player.getUuid())) {
            player.sendMessage(
                Text.translatable("cheesecompat.bt.restore_pending")
                    .formatted(Formatting.YELLOW), false
            );
            LOGGER.info("[CheeseCompat] Blocked startBattle for {} — restore still pending",
                player.getName().getString());
            cir.setReturnValue(null);
            return;
        }

        // Fix 2: Block if in Cobblemon battle
        if (!cheesecompat$reflectionFailed) {
            try {
                if (cheesecompat$battleRegistry == null) {
                    Class<?> cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
                    Object instance = cobblemonClass.getField("INSTANCE").get(null);
                    cheesecompat$battleRegistry = cobblemonClass.getMethod("getBattleRegistry").invoke(instance);

                    for (Method m : cheesecompat$battleRegistry.getClass().getMethods()) {
                        if (m.getName().equals("getBattleByParticipatingPlayer")
                                && m.getParameterCount() == 1) {
                            cheesecompat$getBattleMethod = m;
                            break;
                        }
                    }
                    if (cheesecompat$getBattleMethod == null) {
                        cheesecompat$reflectionFailed = true;
                    }
                }

                if (!cheesecompat$reflectionFailed) {
                    Object battle = cheesecompat$getBattleMethod.invoke(cheesecompat$battleRegistry, player);
                    if (battle != null) {
                        LOGGER.warn("[CheeseCompat] Player {} has stale Cobblemon battle in registry — force-closing",
                            player.getName().getString());
                        boolean closed = cheesecompat$forceCloseStaleBattle(battle);
                        if (!closed) {
                            LOGGER.error("[CheeseCompat] Force-close FAILED — blocking startBattle for {}",
                                player.getName().getString());
                            player.sendMessage(
                                Text.translatable("cheesecompat.bt.already_in_battle")
                                    .formatted(Formatting.RED), false
                            );
                            cir.setReturnValue(null);
                            return;
                        }
                        LOGGER.info("[CheeseCompat] Stale battle force-closed — proceeding with startBattle for {}",
                            player.getName().getString());
                    }
                }
            } catch (Exception e) {
                cheesecompat$reflectionFailed = true;
            }
        }

        // Fix 3: Auto re-lock team when validation fails at floor 1
        // After forfeit, copyFrom bug can corrupt the snapshot, causing validatePartyMatchesSnapshot
        // to always fail even though the player just locked their team. If we're at floor 1 (no
        // battle fought yet), it's safe to force re-capture the snapshot with the current party.
        try {
            PlayerTowerData data = TowerDataManager.getPlayerData(player.getUuid());
            if (data.isPartyLockedIn() && data.getCurrentFloor() <= 1) {
                boolean teamMatches = cheesecompat$validateTeam(player, data);
                if (!teamMatches) {
                    // Re-capture snapshot with current party to fix corrupted state
                    cheesecompat$reLockTeam(player, data);
                    LoggerFactory.getLogger("CheeseCompat").info(
                        "[CheeseCompat] Auto re-locked BattleTower team for player {} (snapshot was stale/corrupted)",
                        player.getName().getString());
                    // Don't return null — let startBattle continue with the fresh snapshot
                }
            }
        } catch (Exception e) {
            LoggerFactory.getLogger("CheeseCompat").debug(
                "[CheeseCompat] BT auto re-lock failed: {}", e.getMessage());
        }

        // ── Floor cooldown: let the showdown engine fully clean up between BT floors ──
        // The Cobblemon showdown engine (GraalJS) runs on a separate thread. After ending
        // a battle, it needs time to fully tear down the previous session before it can
        // reliably initialize a new one. Without this delay, after 6-7 rapid battle cycles,
        // the engine fails to initialize — the PokemonBattle is created server-side but
        // showdown never sends battle data to the client (Pokemon don't appear, no move buttons).
        try {
            PlayerTowerData ptd = TowerDataManager.getPlayerData(player.getUuid());
            int floor = ptd.getCurrentFloor();
            int totalBattles = cheesecompat$btTotalBattles.getOrDefault(player.getUuid(), 0);

            LOGGER.info("[CheeseCompat] Pre-startBattle: floor={}, totalBT={}, player={}",
                floor, totalBattles, player.getName().getString());

            if (floor > 1) {
                long cooldown = cheesecompat$getCooldownMs(floor);
                LOGGER.info("[CheeseCompat] Floor {} cooldown: {}ms for showdown cleanup — {}",
                    floor, cooldown, player.getName().getString());
                Thread.sleep(cooldown);

                // Re-check for stale battle after cooldown
                if (!cheesecompat$reflectionFailed && cheesecompat$getBattleMethod != null) {
                    Object staleBattle = cheesecompat$getBattleMethod.invoke(
                        cheesecompat$battleRegistry, player);
                    if (staleBattle != null) {
                        LOGGER.warn("[CheeseCompat] Post-cooldown: stale battle found for {} — force-closing",
                            player.getName().getString());
                        cheesecompat$forceCloseStaleBattle(staleBattle);
                        Thread.sleep(500);
                    }
                }
            }

            cheesecompat$btFloorTracker.put(player.getUuid(), floor);
            cheesecompat$btTotalBattles.put(player.getUuid(), totalBattles + 1);
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] Floor cooldown check failed: {}", e.getMessage());
        }

        // Enable CobbleCap bypass for the entire startBattle execution
        // This covers applyForcedLevelFallback + RCTApi.startBattle + BATTLE_STARTED_PRE
        cheesecompat$setCobbleCapBypass(true);
    }

    /**
     * Clear CobbleCap level force bypass after startBattle completes.
     */
    @Inject(method = "startBattle", at = @At("RETURN"))
    private static void cheesecompat$clearLevelBypass(ServerPlayerEntity player, BlockPos pos,
                                                       CallbackInfoReturnable<?> cir) {
        cheesecompat$setCobbleCapBypass(false);
        Object result = cir.getReturnValue();
        Integer floor = cheesecompat$btFloorTracker.get(player.getUuid());
        if (result == null) {
            LOGGER.error("[CheeseCompat] startBattle returned NULL for {} (floor {}) — RCT battle creation failed!",
                player.getName().getString(), floor);
        } else {
            LOGGER.info("[CheeseCompat] startBattle returned session for {} (floor {}): {}",
                player.getName().getString(), floor, result.getClass().getSimpleName());
            cheesecompat$logPartyState(player);
            cheesecompat$verifyBattleInRegistry(player);
        }
    }
}
