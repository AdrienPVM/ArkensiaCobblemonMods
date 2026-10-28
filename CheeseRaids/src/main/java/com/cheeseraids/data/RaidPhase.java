package com.cheeseraids.data;

/**
 * Lifecycle states of a raid instance. Linear progression (no PAUSED).
 * IDLE → ANNOUNCE → OVERWORLD_WAVE → NPC_REVEAL → OPEN → COMBAT → REWARD → CLEANUP → IDLE
 */
public enum RaidPhase {
    IDLE,
    ANNOUNCE,
    OVERWORLD_WAVE,
    NPC_REVEAL,
    OPEN,
    COMBAT,
    REWARD,
    CLEANUP
}
