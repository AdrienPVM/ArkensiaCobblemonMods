package com.cheesefixquests.mixin;

import com.cheesefixquests.CheeseFixQuestsConfig;
import com.cheesefixquests.CheeseFixQuestsMod;
import dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mixin 8: Forces FTB Quests' inventory listener to resolve the player's
 * permanent PlayerTeam instead of their party team when writing item-task
 * and crafting-task progress.
 *
 * <h3>The bug this kills</h3>
 *
 * {@code FTBQuestsInventoryListener.detect(ServerPlayer, ItemStack, long)} is
 * attached to every player's screen handler in
 * {@code ServerQuestFile.playerLoggedIn}. It fires on every inventory mutation
 * (pickup, craft, shift-click, drop, container insert/extract). Internally it
 * calls {@code TeamManager.getTeamForPlayer(ServerPlayer)} — the
 * <b>player-keyed</b> variant of the lookup. {@link BaseQuestFileMixin} only
 * intercepts the <b>UUID-keyed</b> variant ({@code getTeamForPlayerID(UUID)}),
 * so {@code detect()} still returns the party team for any player in a party.
 * Progress from item pickups thus writes to {@code PartyTeam.TeamData}
 * regardless of {@code forceIndividualProgress}.
 *
 * <h3>The fix</h3>
 *
 * {@code @Redirect} on the {@code getTeamForPlayer} INVOKE inside {@code detect}.
 * When {@code forceIndividualProgress} is on, resolve the player's PlayerTeam
 * directly via {@code getPlayerTeamForPlayerID(UUID)} and return that. Falls
 * back to the original method if the PlayerTeam isn't found (defensive).
 *
 * <h3>Why this is the most important mixin in the set</h3>
 *
 * The inventory listener is the highest-frequency write path FTB Quests has.
 * Without this redirect, every item a player touches in a party leaks
 * task progress into the shared party TeamData. Audit of prod confirms
 * party TeamData files were accumulating fresh {@code completed} entries
 * every few minutes despite all other CheeseFixQuests redirects being active.
 *
 * <h3>Logging</h3>
 *
 * <p>Logs one INFO per UNIQUE player when first redirected (rate-limited per
 * UUID), to prove the mixin is firing without spamming. Total redirects
 * counter exposed for diagnostic purposes.</p>
 *
 * <p>Set {@code debugLogging: true} in {@code cheesefixquests.json} to see
 * every redirect (verbose).</p>
 */
@Mixin(value = FTBQuestsInventoryListener.class, remap = false)
public abstract class FTBQuestsInventoryListenerMixin {

    /** Total redirects since startup — for forensics if user reports persisting team-sharing. */
    private static final AtomicLong cheesefixquests$redirectCount = new AtomicLong(0);
    /** Set of player UUIDs already logged at INFO so we don't spam. */
    private static final java.util.concurrent.ConcurrentHashMap.KeySetView<java.util.UUID, Boolean> cheesefixquests$loggedPlayers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Redirect(
        method = "detect",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ftb/mods/ftbteams/api/TeamManager;getTeamForPlayer(Lnet/minecraft/class_3222;)Ljava/util/Optional;"
        )
    )
    private static Optional<Team> cheesefixquests$forcePlayerTeamOnDetect(TeamManager mgr, ServerPlayerEntity player) {
        if (CheeseFixQuestsConfig.forceIndividualProgress) {
            try {
                Optional<Team> pt = mgr.getPlayerTeamForPlayerID(player.getUuid());
                if (pt.isPresent()) {
                    long n = cheesefixquests$redirectCount.incrementAndGet();
                    // Log first time per player (INFO) — proves the mixin is reaching this player
                    if (cheesefixquests$loggedPlayers.add(player.getUuid())) {
                        CheeseFixQuestsMod.LOGGER.info(
                            "[CheeseFixQuests] InventoryListener.detect: redirected to PlayerTeam for {} (first redirect for this player; total redirects since startup: {})",
                            player.getName().getString(), n);
                    } else if (CheeseFixQuestsConfig.debugLogging) {
                        CheeseFixQuestsMod.LOGGER.info(
                            "[CheeseFixQuests] InventoryListener.detect: PlayerTeam for {} (count {})",
                            player.getName().getString(), n);
                    }
                    return pt;
                }
                // PlayerTeam missing — fall through to original behavior + log warn (rare)
                CheeseFixQuestsMod.LOGGER.warn(
                    "[CheeseFixQuests] InventoryListener.detect: PlayerTeam NOT found for {} ({}). Falling back to getTeamForPlayer — progress may leak to party. Investigate.",
                    player.getName().getString(), player.getUuid());
            } catch (Throwable t) {
                CheeseFixQuestsMod.LOGGER.error(
                    "[CheeseFixQuests] InventoryListener.detect: redirect threw for {}: {}. Falling back to original method.",
                    player.getName().getString(), t.toString());
            }
        }
        return mgr.getTeamForPlayer(player);
    }
}
