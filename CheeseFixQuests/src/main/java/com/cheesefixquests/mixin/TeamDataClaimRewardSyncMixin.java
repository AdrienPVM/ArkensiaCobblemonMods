package com.cheesefixquests.mixin;

import com.cheesefixquests.CheeseFixQuestsConfig;
import com.cheesefixquests.CheeseFixQuestsMod;
import com.cheesefixquests.logic.QuestAuditLogger;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.util.QuestKey;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * THE anti-duplication safety net.
 *
 * <h3>The bug this kills</h3>
 *
 * <p>{@code TeamData.claimedRewards} is a fastutil {@code Object2LongMap} which
 * is <b>NOT thread-safe</b>. The bytecode of {@code TeamData.claimReward(UUID,
 * Reward, long)} reads:</p>
 *
 * <pre>
 * 24-35  if (claimedRewards.containsKey(key)) return false   ← guard READ
 * 38-50  claimedRewards.put(key, time)                       ← guard WRITE
 * </pre>
 *
 * <p>Between the read and the write, any other thread (CobbleCap-Save thread,
 * Cobblemon battle thread, async event listener…) that also lands on the same
 * key will pass its own {@code containsKey} check, race past, and call
 * {@code Reward.claim(player, notify)} — which actually gives the item or runs
 * the command. The first thread's {@code put} then overwrites the second's,
 * leaving the persisted snbt looking clean even though the side-effect ran
 * twice.</p>
 *
 * <p>This is exactly what happened to one player: rock_badge given twice,
 * {@code /scoreboard players set ... alfred_progress 1} run twice 2 seconds
 * apart, but his TeamData snbt only contains a single
 * {@code claimed_rewards} entry per reward.</p>
 *
 * <h3>The fix</h3>
 *
 * <p>Redirect the {@code claimedRewards.containsKey(key)} call inside
 * {@code claimReward(UUID, Reward, long)} to a synchronized check-and-reserve:</p>
 *
 * <pre>
 * synchronized (claimedRewards) {
 *   if (claimedRewards.containsKey(key)) return true;   // tell FTB "already claimed"
 *   claimedRewards.put(key, currentTimeMillis());       // reserve our slot atomically
 *   return false;                                       // tell FTB "claim it"
 * }
 * </pre>
 *
 * <p>FTB's original code path then proceeds to call {@code Reward.claim} (give
 * step) once — and only once, because the SECOND thread, on entering this
 * redirect, sees {@code containsKey == true} (we put it there) and short-circuits.
 * FTB's own {@code claimedRewards.put} that follows is now an idempotent overwrite
 * of an existing key with the same timestamp.</p>
 *
 * <p>The two synchronization windows on the SAME monitor (the map object) make
 * the (read, write) pair atomic regardless of how many threads concurrently
 * call into {@code claimReward}. <b>Bytecode-airtight zero-duplication
 * guarantee.</b></p>
 *
 * <h3>Why this is safe (no false positives)</h3>
 *
 * <ul>
 *   <li>If the original {@code containsKey} would have returned {@code true}:
 *       we also return {@code true} — same behavior.</li>
 *   <li>If it would have returned {@code false}: we put then return {@code false}
 *       — same behavior, plus the put is idempotent (FTB will overwrite us with
 *       the same key on offset 38-50 with the {@code time} param, off by ≤ a few
 *       ms from our {@code System.currentTimeMillis()}).</li>
 *   <li>The redirect is scoped to {@code claimReward(UUID, Reward, long)} only —
 *       no other {@code containsKey} call site is affected.</li>
 *   <li>{@code remap = false} because all targets are in the FTB Quests package,
 *       outside the Yarn-mapped namespace.</li>
 * </ul>
 *
 * <h3>Kill-switch</h3>
 *
 * <p>If anything ever goes wrong, set {@code enableClaimRewardLock: false} in
 * {@code config/cheesefixquests.json} and restart. The redirect falls back to
 * the unguarded original behavior.</p>
 */
@Mixin(value = TeamData.class, remap = false)
public abstract class TeamDataClaimRewardSyncMixin {

    @Redirect(
            method = "claimReward(Ljava/util/UUID;Ldev/ftb/mods/ftbquests/quest/reward/Reward;J)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/objects/Object2LongMap;containsKey(Ljava/lang/Object;)Z"
            ),
            require = 1
    )
    private boolean cheesefixquests$atomicCheckAndReserve(Object2LongMap<QuestKey> map, Object key) {
        if (!CheeseFixQuestsConfig.enableClaimRewardLock) {
            // Kill-switch: behave exactly like the original.
            return map.containsKey(key);
        }
        synchronized (map) {
            if (map.containsKey(key)) {
                if (CheeseFixQuestsConfig.auditQuestEvents) {
                    QuestAuditLogger.logClaimBlocked(Thread.currentThread().getName(), key);
                }
                return true; // FTB will return false from claimReward (already claimed)
            }
            // Reserve the slot under the same lock the next thread will see.
            map.put((QuestKey) key, System.currentTimeMillis());
            if (CheeseFixQuestsConfig.auditQuestEvents) {
                QuestAuditLogger.logClaimReserved(Thread.currentThread().getName(), key);
            }
            return false; // FTB will call Reward.claim(player, notify) — the actual give
        }
    }

    static {
        CheeseFixQuestsMod.LOGGER.info(
                "[CheeseFixQuests] TeamDataClaimRewardSyncMixin loaded — claimReward is now thread-atomic.");
    }
}
