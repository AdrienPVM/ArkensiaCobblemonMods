package com.cheeseitems.morpho;

import com.cheeseitems.CheeseItemsMod;
import com.cheeseitems.CheeseItemsConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drains a per-player endurance pool while a morpho is active and regenerates it
 * once the player reverts. When the pool reaches zero the morpho is auto-reverted.
 *
 * The lifecycle is driven by {@link MorphoManager} (start/stop) and the per-tick
 * update is wired in {@link CheeseItemsMod#onInitialize()}.
 */
public final class MorphoEnduranceTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");
    private static final MorphoEnduranceTracker INSTANCE = new MorphoEnduranceTracker();

    public static MorphoEnduranceTracker getInstance() { return INSTANCE; }

    public static final class State {
        public float currentValue;
        public float maxCapacity;
        public float drainPerSecond;
        public float regenPerSecond;
        public boolean draining;
        public String displayName;
        public String activeMorphoId;
        public UUID playerUuid;
    }

    private final Map<UUID, State> pools = new ConcurrentHashMap<>();
    private long currentTick = 0;

    private MorphoEnduranceTracker() {}

    /** Read-only view used by handlers / debug. */
    public State get(UUID playerUuid) { return pools.get(playerUuid); }

    /** Start (or restart) draining for a player who just transformed. */
    public void start(ServerPlayerEntity player, MorphoEntry entry) {
        MorphoCatalog cat = MorphoCatalog.getInstance();
        State state = pools.get(player.getUuid());
        if (state == null) {
            state = new State();
            state.currentValue = cat.capacityFor(entry);
            pools.put(player.getUuid(), state);
        }
        state.maxCapacity    = cat.capacityFor(entry);
        state.drainPerSecond = cat.drainFor(entry);
        state.regenPerSecond = cat.regenFor(entry);
        // Cap the current value so reducing the global capacity doesn't leave a stale higher reading.
        if (state.currentValue > state.maxCapacity) state.currentValue = state.maxCapacity;
        // If the pool was depleted, the player must wait for regen — refuse to start drain.
        if (state.currentValue <= 0) {
            player.sendMessage(Text.translatable("cheeseitems.endurance_depleted").formatted(Formatting.RED), true);
            // Schedule an auto-revert next tick so the cshift transform we just executed is reversed.
            state.draining = false;
            return;
        }
        state.draining       = true;
        state.displayName    = (entry.getTitle() == null || entry.getTitle().isEmpty()) ? entry.getSpecies() : entry.getTitle();
        state.activeMorphoId = entry.getId();
        state.playerUuid     = player.getUuid();
    }

    /** Stop draining (transition to regen phase). Called when the player reverts. */
    public void stop(UUID playerUuid) {
        State state = pools.get(playerUuid);
        if (state != null) state.draining = false;
    }

    /** Drop all state for a player (disconnect). */
    public void clear(UUID playerUuid) {
        pools.remove(playerUuid);
    }

    /**
     * Advance every active pool. Triggered every server tick by
     * {@code ServerTickEvents.END_SERVER_TICK} (registered in {@link CheeseItemsMod}).
     */
    public void tick(MinecraftServer server) {
        currentTick++;
        if (pools.isEmpty()) return;
        Iterator<Map.Entry<UUID, State>> it = pools.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> e = it.next();
            State state = e.getValue();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(state.playerUuid);
            if (player == null) continue; // offline — keep state for now, will be cleared on disconnect

            if (state.draining) {
                state.currentValue -= state.drainPerSecond / 20.0f;
                if (state.currentValue <= 0) {
                    state.currentValue = 0;
                    state.draining = false;
                    // Auto-revert when depleted.
                    MorphoManager mm = CheeseItemsMod.INSTANCE.getMorphoManager();
                    if (mm != null) mm.forceRevert(player, "endurance_depleted");
                }
            } else if (state.currentValue < state.maxCapacity) {
                state.currentValue += state.regenPerSecond / 20.0f;
                if (state.currentValue >= state.maxCapacity) state.currentValue = state.maxCapacity;
            }

            if (currentTick % 5 == 0) {
                if (state.draining || state.currentValue < state.maxCapacity) {
                    sendBar(player, state);
                }
            }

            // Drop fully-regenerated states to keep the map small.
            if (!state.draining && state.currentValue >= state.maxCapacity) {
                it.remove();
            }
        }
    }

    private void sendBar(ServerPlayerEntity player, State state) {
        float pct = state.maxCapacity > 0 ? state.currentValue / state.maxCapacity : 0f;

        // Custom font bar (PNG images 0-10 mapped to U+E000-U+E00A in default font),
        // matches the existing CheeseItems endurance system used by other items.
        try {
            CheeseItemsConfig cfg = CheeseItemsMod.INSTANCE.getConfig();
            if (cfg != null && cfg.hasBarImages()) {
                int level = Math.round(pct * 10);
                level = Math.max(0, Math.min(10, level));
                char barChar = (char) (0xE000 + level);
                player.sendMessage(Text.literal(String.valueOf(barChar)), true);
                return;
            }
        } catch (Exception ignored) {}

        // Fallback: text-based bar with ◆/◇.
        int total = 10;
        int filled = Math.round(pct * total);
        Formatting color;
        if (pct > 0.5f) color = Formatting.GREEN;
        else if (pct > 0.25f) color = Formatting.YELLOW;
        else color = Formatting.RED;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < filled; i++)        bar.append('◆');
        for (int i = 0; i < total - filled; i++) bar.append('◇');
        player.sendMessage(Text.literal(bar.toString()).formatted(color), true);
    }
}
