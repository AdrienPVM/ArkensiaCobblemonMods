package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.EventParticipant;
import com.cheeseguard.data.ServerEvent;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * Centralized hook for automatic event scoring.
 * Called by mixins when scorable game events occur.
 * Checks all active events for matching ScoreTrigger entries and awards points.
 */
public class EventScoreHook {

    /**
     * Check all active events for matching triggers and award score.
     *
     * @param player   The player performing the action
     * @param type     The trigger type (e.g., KILL_MOB, BLOCK_BREAK)
     * @param filterId The specific id to match against the trigger filter (e.g., "minecraft:zombie", "minecraft:diamond_ore")
     *                 Empty string matches triggers with empty filter (= any).
     */
    public static void check(ServerPlayerEntity player, ServerEvent.ScoreTrigger.TriggerType type, String filterId) {
        if (player == null) return;
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        if (em == null) return;
        UUID uuid = player.getUuid();

        for (ServerEvent event : em.getEvents()) {
            if (!event.isActive()) continue;
            EventParticipant p = event.getParticipant(uuid);
            if (p == null || p.getRole() != EventParticipant.Role.PARTICIPANT) continue;

            for (ServerEvent.ScoreTrigger trigger : event.getScoreTriggers()) {
                if (trigger.getType() != type) continue;
                // Filter match: empty filter = any, otherwise must match
                if (!trigger.getFilter().isBlank() && !trigger.getFilter().equalsIgnoreCase(filterId)) continue;
                em.addScore(event, uuid, trigger.getPoints());
            }
        }
    }

    /**
     * Overload for actions without a specific filter id (e.g., PLAYER_KILL where any kill counts).
     */
    public static void check(ServerPlayerEntity player, ServerEvent.ScoreTrigger.TriggerType type) {
        check(player, type, "");
    }
}
