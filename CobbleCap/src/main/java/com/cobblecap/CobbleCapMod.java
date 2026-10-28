package com.cobblecap;

import com.cobblecap.commands.CobbleCapCommand;
import com.cobblecap.commands.ExpLockCommand;
import com.cobblecap.data.CapConfig;
import com.cobblecap.data.CapStorage;
import com.cobblecap.data.ChampionConfig;
import com.cobblecap.data.ChampionEntry;
import com.cobblecap.data.ChampionNpcState;
import com.cobblecap.data.LegendsStorage;
import com.cobblecap.data.PlayerCapData;
import com.cobblecap.data.TenureStorage;
import com.cobblecap.logic.CapListener;
import com.cobblecap.logic.ChampionNpcManager;
import com.cobblecap.logic.ChronoManager;
import com.cobblecap.logic.CobbleCapWebhook;
import com.cobblecap.logic.Conseil4TrainerCloner;
import com.cobblecap.logic.RctTrainerMorpher;
import com.cobblecap.logic.StatueManager;
import com.cobblecap.network.CapSyncPayload;
import com.cobblecap.logic.PastureTracker;
import com.cobblecap.network.PastureSyncPayload;
import com.cobblecap.network.StatueStyleSyncPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class CobbleCapMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    public static CobbleCapMod INSTANCE;

    /**
     * When true on the current thread, PokemonSetLevelMixin and BATTLE_PRE party-cap
     * checks are bypassed. Used by external mods (e.g. Battle Tower via CheeseCompat)
     * to temporarily force Pokemon levels above the cap.
     */
    public static final ThreadLocal<Boolean> LEVEL_FORCE_ACTIVE = ThreadLocal.withInitial(() -> false);

    public final CapConfig capConfig = new CapConfig();
    public final CapStorage capStorage;
    public final ChampionConfig championConfig = new ChampionConfig();
    public final LegendsStorage legendsStorage = new LegendsStorage();
    public final TenureStorage tenureStorage = new TenureStorage();
    public final ChronoManager chronoManager = new ChronoManager();
    public final StatueManager statueManager = new StatueManager();
    public final ChampionNpcState championNpcState = new ChampionNpcState();
    public final ChampionNpcManager championNpcManager = new ChampionNpcManager();
    public final RctTrainerMorpher rctMorpher = new RctTrainerMorpher();
    public final Conseil4TrainerCloner conseil4Cloner = new Conseil4TrainerCloner();
    public final com.cobblecap.logic.RegisDoppelgangerManager regisDoppelMgr = new com.cobblecap.logic.RegisDoppelgangerManager();

    public CobbleCapMod() {
        this.capStorage = new CapStorage(capConfig);
    }

    @Override
    public void onInitialize() {
        INSTANCE = this;

        CapSyncPayload.register();
        // Register the pasture-sync payload codec (S2C — server pushes pastured
        // UUIDs to clients because Cobblemon doesn't sync tetheringId itself).
        PayloadTypeRegistry.playS2C().register(PastureSyncPayload.ID, PastureSyncPayload.CODEC);
        // Statue style sync (S2C — server tells clients which doppelganger UUID
        // uses which StatueStyle for skin retexturing).
        PayloadTypeRegistry.playS2C().register(StatueStyleSyncPayload.ID, StatueStyleSyncPayload.CODEC);

        capConfig.load();
        capStorage.load();
        championConfig.load();
        legendsStorage.load();
        tenureStorage.load();
        chronoManager.load();
        championNpcState.load();
        CobbleCapWebhook.setUrl(capConfig.getDiscordWebhookUrl());

        CapListener.register(null, capStorage);

        // Initialize statue subsystem when the server starts (scoreboard teams need the server)
        ServerLifecycleEvents.SERVER_STARTED.register(statueManager::init);
        // Ensure Conseil 4 scoreboards exist at boot so CCS sees them immediately.
        ServerLifecycleEvents.SERVER_STARTED.register(com.cobblecap.logic.Conseil4Boards::initObjectives);
        // Per-dim Conseil 4 trainer cloner — DISABLED 2026-05-17 after prod deploy
        // showed "Invalid trainer id" errors on all 5 trainers in conseil4_<uuid8>
        // dims. Root cause: rctmod's `rctmod reload trainer` command does NOT
        // rescan datapack files from disk, so per-dim clone JSONs we wrote
        // were never registered. Re-enabling requires either a vanilla /reload
        // (banned) or reflection-injection into TrainerRegistry. Tracked for
        // follow-up — see RegisDoppelgangerManager / Conseil4TrainerCloner for
        // the dormant implementation. The cloner also installs a one-shot
        // cleanup that reverts any stale per-dim trainer IDs left over from
        // the failed deploy and removes orphan clone JSONs on disk.
        conseil4Cloner.registerCleanupOnly();
        // In-arena Régis skin overlay (champion's player skin via fake-player doppel).
        regisDoppelMgr.register();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            statueManager.tick();
            rctMorpher.tick(server);
            PastureTracker.tick(server);
        });

        // Send full pasture snapshot to a player on join.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            try { PastureTracker.sendSnapshotTo(handler.player); }
            catch (Exception ignored) {}
        });

        // Auto-cancel chrono on dim leave: when a player exits any
        // cheeseguard:conseil4* dimension to a non-conseil4 dimension. The
        // master→per-player transition no longer triggers this because we
        // explicitly only cancel when the destination is NOT conseil4-related.
        net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents
                .AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            try {
                String fromId = origin.getRegistryKey().getValue().toString();
                String toId = destination.getRegistryKey().getValue().toString();
                boolean leavingConseil4 = fromId.contains(":conseil4");
                boolean enteringConseil4 = toId.contains(":conseil4");
                if (leavingConseil4 && !enteringConseil4
                        && chronoManager.isActive(player.getUuid())) {
                    long elapsed = chronoManager.elapsedFor(player.getUuid());
                    chronoManager.cancel(player.getUuid());
                    LOGGER.info("[CobbleCap] Auto-cancelled chrono — {} left {} to {} (was at {})",
                            player.getName().getString(), fromId, toId,
                            ChronoManager.formatDuration(elapsed));
                    player.sendMessage(Text.literal(
                            "⏱ Chrono Conseil 4 annulé — tu as quitté la dimension")
                            .formatted(Formatting.YELLOW), false);
                }
            } catch (Exception ignored) {}
        });

        // Auto-cancel a player's chrono on disconnect. The "League Master run"
        // is meant to be a single uninterrupted session; if the player drops
        // we discard the partial run so they can start fresh next time.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            try {
                java.util.UUID uuid = handler.player.getUuid();
                if (chronoManager.isActive(uuid)) {
                    long elapsed = chronoManager.elapsedFor(uuid);
                    chronoManager.cancel(uuid);
                    LOGGER.info("[CobbleCap] Auto-cancelled chrono on disconnect: {} (was at {})",
                            handler.player.getName().getString(),
                            ChronoManager.formatDuration(elapsed));
                }
            } catch (Exception ignored) {}
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, reg, env) -> {
                CobbleCapCommand.register(dispatcher);
                ExpLockCommand.register(dispatcher);
        });

        LOGGER.info("[CobbleCap] Initialized — startingCap={}, enabled={}, champions={}",
                capConfig.getStartingCap(), capConfig.isEnabled(), championConfig.getChampions().size());
    }

    public CapConfig getCapConfig() { return capConfig; }
    public CapStorage getCapStorage() { return capStorage; }
    public ChampionConfig getChampionConfig() { return championConfig; }
    public LegendsStorage getLegendsStorage() { return legendsStorage; }
    public TenureStorage getTenureStorage() { return tenureStorage; }
    public ChronoManager getChronoManager() { return chronoManager; }
    public StatueManager getStatueManager() { return statueManager; }
    public ChampionNpcState getChampionNpcState() { return championNpcState; }
    public ChampionNpcManager getChampionNpcManager() { return championNpcManager; }
    public RctTrainerMorpher getRctMorpher() { return rctMorpher; }

    /**
     * Build and send a CapSyncPayload to the given player.
     * Safe to call from any thread — checks player connection state.
     */
    public static void syncToClient(ServerPlayerEntity player) {
        if (player.networkHandler == null) return;
        CapStorage storage = INSTANCE.getCapStorage();
        Optional<PlayerCapData> dataOpt = storage.getCapData(player.getUuid());
        if (dataOpt.isEmpty()) return;

        PlayerCapData data = dataOpt.get();
        String nextChampion = null;
        Optional<ChampionEntry> next = INSTANCE.getChampionConfig().nextUnbeaten(data.getChampionsBeaten());
        if (next.isPresent()) nextChampion = next.get().displayName();

        ServerPlayNetworking.send(player, new CapSyncPayload(
                data.getCapLevel(), nextChampion, data.isHudVisible(),
                data.getHudPosition(), INSTANCE.getCapConfig().isEnabled()
        ));
    }
}
