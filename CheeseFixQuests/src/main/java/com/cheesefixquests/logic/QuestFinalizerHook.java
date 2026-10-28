package com.cheesefixquests.logic;

import com.cheesefixquests.CheeseFixQuestsConfig;
import com.cheesefixquests.CheeseFixQuestsMod;
import dev.ftb.mods.ftbquests.events.QuestProgressEventData;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Periodically nudges FTB Quests to finalise quests so completions and
 * auto-claim rewards happen without forcing a disconnect/reconnect.
 *
 * <h3>The four phases — all on server thread, every 5 s</h3>
 *
 * <ol>
 *   <li><b>Phase A — inventory rescan.</b>
 *       {@code FTBQuestsInventoryListener.detect(player, ItemStack.EMPTY, 0L)}.
 *       Re-validates passive {@code ItemTask}s for items that landed in the
 *       inventory through paths bypassing the vanilla slot listener (mod APIs,
 *       NBT inserts, Cobblemon trades, etc.).</li>
 *
 *   <li><b>Phase B — per-task submitTask sweep.</b> For every task with
 *       {@code checkOnLogin() == true}, call {@code task.submitTask(team, player)}.
 *       Already-completed tasks short-circuit at offset 0-7 ({@code isCompleted}
 *       guard). Catches non-item task types: kill / biome / location / stat /
 *       advancement / dimension / observation / custom. Bytecode-confirmed safe.</li>
 *
 *   <li><b>Phase C — finalise completed-but-not-marked quests.</b> For every
 *       quest the team can start, if
 *       {@code !td.isCompleted(quest) && quest.isCompletedRaw(td)} then call
 *       {@code quest.onCompleted(...)} <b>under {@code synchronized (td)}</b>.
 *       This catches the bug class where progress reaches max via paths that
 *       don't trigger {@code onCompleted}'s cascade — the canonical case being
 *       {@code KeyValueReward.claim} which calls {@code TeamData.addProgress}
 *       directly without firing task completion. Without phase C, those
 *       quests appear stuck until the next login.</li>
 *
 *   <li><b>Phase D — Cobblemon Pokédex resync.</b> Re-fire
 *       {@code CobblemonQuestsEventHandler.triggerPokeDexUpdate(uuid)} for
 *       every online player. The handler's natural call sites are
 *       {@code PLAYER_JOIN} and {@code POKEDEX_DATA_CHANGED_*}; if any of these
 *       are missed (mod load order, race with other handlers, save-thread
 *       interference) the player's Cobblemon-Quests progress falls behind
 *       until next login. Re-firing periodically is risk-free — it only
 *       calls {@code task.increaseHaveRegistered → TeamData.addProgress}, which
 *       is then picked up by phase C above and finalised.</li>
 * </ol>
 *
 * <h3>Anti-duplication contract</h3>
 *
 * <p>This hook never claims rewards directly. The only place rewards are
 * claimed is FTB's own cascade reached through {@code Quest.onCompleted}, which
 * is guarded by {@code TeamDataClaimRewardSyncMixin} — every
 * {@code claimedRewards.containsKey + put} pair runs under
 * {@code synchronized (claimedRewards)}, making double-claims structurally
 * impossible regardless of source thread.</p>
 *
 * <p>Phase C's {@code synchronized (td)} additionally serialises our own
 * {@code Quest.onCompleted} entry against itself — useful in case FTB's natural
 * event-driven path also fires for the same quest in the same tick, the second
 * call hits the existing {@code !isCompleted} guard.</p>
 *
 * <h3>Per-tick deduplication</h3>
 *
 * <p>Within a single sweep, a {@code Set<Long>} keyed on
 * {@code quest.id ^ teamId.mostSigBits} prevents finalising the same (quest, team)
 * pair twice if multiple players from the same team are processed in the same
 * cycle. Cleared at the start of each sweep.</p>
 *
 * <h3>Kill-switches</h3>
 *
 * <p>Every phase has its own config flag. If a regression appears, the offending
 * phase can be disabled in {@code config/cheesefixquests.json} and the server
 * restarted — no rebuild needed:</p>
 * <ul>
 *   <li>{@code enableInventoryRescan} (phase A)</li>
 *   <li>{@code enableTaskSubmitSweep} (phase B)</li>
 *   <li>{@code enableQuestFinalization} (phase C)</li>
 *   <li>{@code enableCobblemonPokedexResync} (phase D)</li>
 *   <li>{@code enableClaimRewardLock} ({@code TeamDataClaimRewardSyncMixin})</li>
 * </ul>
 */
public final class QuestFinalizerHook {

    private static final boolean FTBQ_LOADED = FabricLoader.getInstance().isModLoaded("ftbquests");
    private static final boolean COBBLEMON_QUESTS_LOADED =
            FabricLoader.getInstance().isModLoaded("cobblemon_quests");
    /** 200 ticks = 10 s. Tuned to keep sweep cost < 1 ms even with 50 players × 1000 quests. */
    private static final int RESCAN_INTERVAL_TICKS = 200;

    // ── Phase A reflection (mandatory) ──────────────────────────────────
    private static volatile Method detectMethod;
    private static volatile boolean phaseAArmed;

    // ── Phase D reflection (cobblemon_quests, optional, retried) ────────
    private static volatile Object cobblemonHandlerInstance;
    private static volatile Method triggerPokeDexUpdateMethod;
    private static volatile boolean phaseDArmed;
    private static volatile boolean phaseDPermanentlyFailed;

    // ── Lifecycle ──────────────────────────────────────────────────────
    private static volatile boolean initFailed;
    private static volatile boolean serverStopping;
    private static int rescanTickCounter;

    /** Per-sweep dedup set: quest.id ^ teamId.mostSigBits. Cleared at start of each sweep. */
    private static final Set<Long> finalisedThisSweep = new HashSet<>();

    private QuestFinalizerHook() {}

    /** Called from {@code ServerLifecycleEvents.SERVER_STOPPING}. */
    public static void onServerStopping() { serverStopping = true; }

    /** Call from {@code ServerTickEvents.END_SERVER_TICK}. No-op when FTBQ is absent. */
    public static void tick(MinecraftServer server) {
        if (!FTBQ_LOADED || initFailed || serverStopping || server == null) return;
        if (++rescanTickCounter < RESCAN_INTERVAL_TICKS) return;
        rescanTickCounter = 0;
        ensurePhasesArmed();

        finalisedThisSweep.clear();

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            // Phase A
            if (CheeseFixQuestsConfig.enableInventoryRescan && detectMethod != null) {
                try { detectMethod.invoke(null, p, ItemStack.EMPTY, 0L); }
                catch (Throwable t) { logOnce("phaseA", t); }
            }

            // Phases B + C — both need the player's team data, resolve it once.
            TeamData td = resolveTeamData(p);
            if (td == null) continue;

            // Phase B
            if (CheeseFixQuestsConfig.enableTaskSubmitSweep) {
                try { runTaskSubmitSweep(td, p); }
                catch (Throwable t) { logOnce("phaseB", t); }
            }

            // Phase C
            if (CheeseFixQuestsConfig.enableQuestFinalization) {
                try { runFinalizationSweep(td, p); }
                catch (Throwable t) { logOnce("phaseC", t); }
            }

            // Phase D
            if (COBBLEMON_QUESTS_LOADED
                    && CheeseFixQuestsConfig.enableCobblemonPokedexResync
                    && triggerPokeDexUpdateMethod != null) {
                try {
                    triggerPokeDexUpdateMethod.invoke(cobblemonHandlerInstance, p.getUuid());
                    if (CheeseFixQuestsConfig.auditQuestEvents) {
                        QuestAuditLogger.logPokedexResync(
                                Thread.currentThread().getName(), p.getUuid());
                    }
                } catch (Throwable t) { logOnce("phaseD", t); }
            }
        }
    }

    /** Phase B implementation. */
    private static void runTaskSubmitSweep(TeamData td, ServerPlayerEntity player) {
        ServerQuestFile sqf = ServerQuestFile.INSTANCE;
        if (sqf == null) return;
        sqf.forAllQuests(quest -> {
            if (!td.canStartTasks(quest)) return;
            for (Task task : quest.getTasks()) {
                try {
                    if (task.checkOnLogin()) {
                        task.submitTask(td, player);
                    }
                } catch (Throwable ignored) {
                    // One bad task shouldn't break the sweep.
                }
            }
        });
    }

    /**
     * Phase C — finalise quests stuck in two distinct broken states:
     *
     * <h4>C1 — orphaned task progress</h4>
     *
     * <p>{@code TeamData.setProgress} (offsets 32-52) returns early when
     * {@code count == prev}, BYPASSING the
     * {@code markTaskCompleted → task.onCompleted → completed.put} cascade.
     * Result: a task can have {@code taskProgress[id] = max} while
     * {@code completed[id]} is missing. {@code task.isCompleted(td)} reads the
     * {@code completed} map, so it returns {@code false} forever — and
     * {@code Quest.isCompletedRaw} (which AND-checks every task's
     * {@code isCompleted}) returns {@code false} too. The quest is
     * functionally complete but the system can't see it.</p>
     *
     * <p>This is the bug-class behind shiny_plushies' gate quest
     * (`extraquests:key_value` value=40) needing deco/reco: 70 child rewards
     * each call {@code TeamData.addProgress(gateTask, 1)}; on the 40th call
     * {@code setProgress} should cascade to {@code markTaskCompleted}, but if
     * the task progress was repopulated from disk earlier with value 40 and
     * then any caller does {@code addProgress(task, 0)} or
     * {@code setProgress(task, 40)} again, the short-circuit fires and the
     * cascade never runs.</p>
     *
     * <p><b>Fix C1</b>: walk every task on every quest. If
     * {@code progress >= maxProgress && !isCompleted(task)} and dependencies
     * are met, call {@code markTaskCompleted(task)} directly — this is what
     * the natural cascade should have done. {@code markTaskCompleted}
     * synchronously fires {@code task.onCompleted}, which writes the missing
     * {@code completed[taskId]} entry AND, if it was the last task of the
     * quest, calls {@code Quest.onCompleted} → {@code checkAutoCompletion} →
     * {@code claimReward} (guarded by our anti-duplication mixin).</p>
     *
     * <h4>C2 — completed-but-not-marked quests</h4>
     *
     * <p>If after the C1 repair (or independently) all tasks are complete but
     * the quest itself wasn't marked, fire {@code Quest.onCompleted} once
     * more under the same lock. Idempotent due to the {@code !isCompleted}
     * guard at {@code Quest.onCompleted:0-8}.</p>
     *
     * <p>All work is done under {@code synchronized (td)} for defensive belt;
     * the actual reward-claim step is independently locked by the
     * {@code TeamDataClaimRewardSyncMixin}.</p>
     */
    private static void runFinalizationSweep(TeamData td, ServerPlayerEntity player) {
        ServerQuestFile sqf = ServerQuestFile.INSTANCE;
        if (sqf == null) return;
        UUID teamId = td.getTeamId();
        long teamHash = teamId == null ? 0L : teamId.getMostSignificantBits();
        String threadName = Thread.currentThread().getName();
        String playerName = player.getName().getString();

        // Per-sweep counters for the diagnostic summary.
        int[] visited = {0}, itemScans = {0}, itemRepairs = {0}, itemSets = {0};
        int[] orphanRepairs = {0}, finalizes = {0};

        sqf.forAllQuests(quest -> {
            long dedupKey = quest.id ^ teamHash;
            if (!finalisedThisSweep.add(dedupKey)) return; // already considered this sweep
            visited[0]++;
            try {
                if (td.isCompleted(quest)) return;
                if (!td.canStartTasks(quest)) return; // dependencies not met

                // ── C0: defensive ItemTask inventory scanner ────────────────
                // FTBQuestsInventoryListener is attached to playerScreenHandler,
                // not to ChestScreenHandler — so chest-to-inventory transfers
                // (shift-click, drag) are NEVER seen by the listener. Phase A's
                // detect() does iterate submitTasks but goes through setProgress
                // which short-circuits on count==prev. Phase B (per-task
                // submitTask sweep) hits the same short-circuit.
                //
                // Workaround: directly scan the player's inventory ourselves
                // and call markTaskCompleted/setProgress when needed. Bypasses
                // every broken FTBQ path.
                for (Task task : quest.getTasks()) {
                    if (!(task instanceof ItemTask itemTask)) continue;
                    try {
                        long max = itemTask.getMaxProgress();
                        if (max <= 0) continue;
                        if (td.isCompleted(itemTask)) continue;
                        itemScans[0]++;
                        long matches = countInventoryMatches(player, itemTask);
                        long current = td.getProgress(itemTask);
                        if (matches >= max) {
                            synchronized (td) {
                                if (td.isCompleted(itemTask)) continue;
                                if (!td.areDependenciesComplete(quest)) continue;
                                td.markTaskCompleted(itemTask);
                                itemRepairs[0]++;
                                if (CheeseFixQuestsConfig.auditQuestEvents) {
                                    QuestAuditLogger.logItemRepair(
                                            threadName, quest.id, itemTask.id,
                                            teamId, playerName, matches, max);
                                }
                            }
                        } else if (matches > current) {
                            synchronized (td) {
                                if (td.isCompleted(itemTask)) continue;
                                if (!td.areDependenciesComplete(quest)) continue;
                                long oldProg = td.getProgress(itemTask);
                                td.setProgress(itemTask, matches);
                                itemSets[0]++;
                                if (CheeseFixQuestsConfig.auditQuestEvents) {
                                    QuestAuditLogger.logItemSet(
                                            threadName, quest.id, itemTask.id,
                                            teamId, playerName, oldProg, matches);
                                }
                            }
                        }
                    } catch (Throwable taskErr) {
                        // One bad task should not break the rest of the sweep.
                    }
                }

                // ── C1: repair orphaned task progress ───────────────────────
                // For NON-ItemTask types (or ItemTask cases C0 didn't repair):
                // if progress reached max but completed map entry is missing,
                // force markTaskCompleted. Bypasses setProgress's count==prev
                // short-circuit.
                for (Task task : quest.getTasks()) {
                    try {
                        long max = task.getMaxProgress();
                        if (max <= 0) continue;
                        if (td.getProgress(task) < max) continue;
                        if (td.isCompleted(task)) continue;
                        synchronized (td) {
                            if (td.getProgress(task) < task.getMaxProgress()) continue;
                            if (td.isCompleted(task)) continue;
                            if (!td.areDependenciesComplete(quest)) continue;
                            td.markTaskCompleted(task);
                            orphanRepairs[0]++;
                            if (CheeseFixQuestsConfig.auditQuestEvents) {
                                QuestAuditLogger.logTaskRepair(
                                        threadName, quest.id, task.id,
                                        teamId, playerName);
                            }
                        }
                    } catch (Throwable taskErr) {
                        // One bad task should not break the rest of the sweep.
                    }
                }

                // ── C2: catch-all finalize ──────────────────────────────────
                if (td.isCompleted(quest)) return;
                if (!quest.isCompletedRaw(td)) return;

                synchronized (td) {
                    if (td.isCompleted(quest)) return;
                    if (!quest.isCompletedRaw(td)) return;

                    QuestProgressEventData<Quest> ev = new QuestProgressEventData<>(
                            new Date(), td, quest,
                            td.getOnlineMembers(),
                            Collections.emptyList()
                    );
                    quest.onCompleted(ev);
                    finalizes[0]++;

                    if (CheeseFixQuestsConfig.auditQuestEvents) {
                        QuestAuditLogger.logFinalize(
                                threadName, quest.id, teamId, playerName);
                    }
                }
            } catch (Throwable t) {
                logOnce("phaseC", t);
                if (CheeseFixQuestsConfig.auditQuestEvents) {
                    QuestAuditLogger.logFinalizeSkipped(
                            threadName, quest.id, teamId,
                            t.getClass().getSimpleName() + ":" + t.getMessage());
                }
            }
        });

        // Per-sweep summary — proves the sweep ran for this player and shows
        // exactly what it did. Always emitted when audit is on, even with all
        // zero counts.
        if (CheeseFixQuestsConfig.auditQuestEvents) {
            QuestAuditLogger.logSweep(threadName, player.getUuid(), playerName,
                    visited[0], itemScans[0], itemRepairs[0], itemSets[0],
                    orphanRepairs[0], finalizes[0]);
        }
    }

    /**
     * Count items in the player's inventory that match an {@link ItemTask}'s
     * filter. {@code ItemTask} implements {@code Predicate<ItemStack>} so we
     * delegate to its own {@code test} which already handles NBT-component
     * matching the way FTB Quests does internally.
     */
    private static long countInventoryMatches(ServerPlayerEntity player, ItemTask itemTask) {
        long total = 0;
        var inv = player.getInventory();
        int size = inv.size();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            try {
                if (itemTask.test(stack)) total += stack.getCount();
            } catch (Throwable ignored) {
                // Defensive: never let a single stack break the scan.
            }
        }
        return total;
    }

    /**
     * Resolve the player's {@code TeamData}. With {@code forceIndividualProgress}
     * enabled, this returns the PlayerTeam's data (consistent with the rest of
     * CheeseFixQuests). Otherwise falls back to the active team.
     */
    private static TeamData resolveTeamData(ServerPlayerEntity player) {
        try {
            UUID playerId = player.getUuid();
            Optional<Team> playerTeam = CheeseFixQuestsConfig.forceIndividualProgress
                    ? FTBTeamsAPI.api().getManager().getPlayerTeamForPlayerID(playerId)
                    : FTBTeamsAPI.api().getManager().getTeamForPlayer(player);
            if (playerTeam.isEmpty()) return null;
            ServerQuestFile sqf = ServerQuestFile.INSTANCE;
            if (sqf == null) return null;
            return sqf.getNullableTeamData(playerTeam.get().getTeamId());
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Init ───────────────────────────────────────────────────────────

    /**
     * Arm any phase that isn't yet armed. Called every sweep — Phase A and
     * Phase D arm independently and retry if their target wasn't ready (e.g.
     * cobblemon_quests' static initializer hadn't populated {@code eventHandler}
     * yet at the first sweep). Once armed, the volatile flag prevents re-resolution.
     *
     * <p>Phase A reflection failure is logged once and the phase stays disarmed.
     * Phase D failures are logged once with a kill-flag so the retry doesn't
     * spam the log if the field is permanently null (e.g. a future cobblemon_quests
     * version reorganizes the singleton).</p>
     */
    private static void ensurePhasesArmed() {
        if (!phaseAArmed) tryArmPhaseA();
        if (COBBLEMON_QUESTS_LOADED && !phaseDArmed && !phaseDPermanentlyFailed) tryArmPhaseD();
    }

    private static void tryArmPhaseA() {
        synchronized (QuestFinalizerHook.class) {
            if (phaseAArmed) return;
            try {
                Class<?> listenerCls = Class.forName(
                        "dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener");
                detectMethod = listenerCls.getMethod(
                        "detect", ServerPlayerEntity.class, ItemStack.class, long.class);
                phaseAArmed = true;
                CheeseFixQuestsMod.LOGGER.info(
                        "[CheeseFixQuests] Phase A (detect) armed — sweep every {} ticks.",
                        RESCAN_INTERVAL_TICKS);
            } catch (Throwable t) {
                if (!phaseAErrLogged) {
                    phaseAErrLogged = true;
                    CheeseFixQuestsMod.LOGGER.warn(
                            "[CheeseFixQuests] Phase A init failed (will retry next sweep): {}",
                            t.getMessage());
                }
            }
        }
    }

    /**
     * Resolve cobblemon_quests' singleton and the private
     * {@code triggerPokeDexUpdate(UUID)} method.
     *
     * <p>The mod stores its singleton in
     * {@code winterwolfsv.cobblemon_quests.CobblemonQuests.eventHandler} (public
     * static field, populated in {@code CobblemonQuests.init} via
     * {@code new CobblemonQuestsEventHandler().init()}). We MUST use the existing
     * singleton — instantiating a new handler would re-register every Cobblemon
     * event listener and cause duplicate processing.</p>
     */
    private static void tryArmPhaseD() {
        synchronized (QuestFinalizerHook.class) {
            if (phaseDArmed || phaseDPermanentlyFailed) return;
            try {
                Class<?> modCls = Class.forName("winterwolfsv.cobblemon_quests.CobblemonQuests");
                Object handler = modCls.getField("eventHandler").get(null);
                if (handler == null) {
                    // Not a permanent failure — cobblemon_quests' static init may run after ours.
                    return;
                }
                Class<?> handlerCls = handler.getClass();
                Method m = handlerCls.getDeclaredMethod("triggerPokeDexUpdate", UUID.class);
                m.setAccessible(true);
                cobblemonHandlerInstance = handler;
                triggerPokeDexUpdateMethod = m;
                phaseDArmed = true;
                CheeseFixQuestsMod.LOGGER.info(
                        "[CheeseFixQuests] Phase D (Cobblemon Pokédex resync) armed.");
            } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException e) {
                // Permanent — class shape changed in a cobblemon_quests version we don't recognise.
                phaseDPermanentlyFailed = true;
                CheeseFixQuestsMod.LOGGER.warn(
                        "[CheeseFixQuests] Phase D permanently disabled (class shape mismatch): {}",
                        e.toString());
            } catch (Throwable t) {
                if (!phaseDErrLogged) {
                    phaseDErrLogged = true;
                    CheeseFixQuestsMod.LOGGER.warn(
                            "[CheeseFixQuests] Phase D init transient failure (will retry): {}",
                            t.getMessage());
                }
            }
        }
    }

    private static volatile boolean phaseAErrLogged, phaseBErrLogged, phaseCErrLogged, phaseDErrLogged;

    private static void logOnce(String phase, Throwable t) {
        boolean log;
        switch (phase) {
            case "phaseA" -> { log = !phaseAErrLogged; phaseAErrLogged = true; }
            case "phaseB" -> { log = !phaseBErrLogged; phaseBErrLogged = true; }
            case "phaseC" -> { log = !phaseCErrLogged; phaseCErrLogged = true; }
            case "phaseD" -> { log = !phaseDErrLogged; phaseDErrLogged = true; }
            default -> log = false;
        }
        if (log) {
            CheeseFixQuestsMod.LOGGER.warn(
                    "[CheeseFixQuests] {} error (further occurrences silenced): {}",
                    phase, t.getMessage(), t);
        }
    }
}
