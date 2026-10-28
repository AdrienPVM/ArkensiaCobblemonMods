package com.cheeseraids.logic;

import com.cheeseraids.data.RaidDefinition;
import com.cheeseraids.data.RaidInstance;
import net.minecraft.server.MinecraftServer;

/**
 * Pluggable boss strategy (modularity). v1 ships {@link CustomGiantBossProvider}; a
 * CobblemonRaidDens-backed provider is a documented stretch.
 */
public interface BossProvider {

    void spawnBoss(MinecraftServer server, RaidDefinition def, RaidInstance inst, int participants);

    /** Capturing the (async-spawned) boss entity is still in progress. */
    boolean isCapturing(RaidInstance inst);

    /** Resolve the async-spawned boss entity during the capture window. */
    void captureTick(MinecraftServer server, RaidDefinition def, RaidInstance inst);

    /** True once the boss is dead / removed. */
    boolean isDefeated(MinecraftServer server, RaidDefinition def, RaidInstance inst);

    /** Per-tick combat ambience around the boss (particles/aura). Default no-op. */
    default void tickEffects(MinecraftServer server, RaidDefinition def, RaidInstance inst) {}

    /** Per-tick combat bookkeeping (e.g. capture the boss's Cobblemon battle id). Default no-op. */
    default void combatTick(MinecraftServer server, RaidDefinition def, RaidInstance inst) {}

    void cleanup(MinecraftServer server, RaidDefinition def, RaidInstance inst);

    static BossProvider forDefinition(RaidDefinition def) {
        // "RAID_DEN" reserved for a future CobblemonRaidDens-backed provider.
        return new CustomGiantBossProvider();
    }
}
