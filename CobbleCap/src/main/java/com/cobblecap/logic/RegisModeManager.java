package com.cobblecap.logic;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages the live state of the Régis trainer JSON between two persisted
 * variants — "classic" (the pristine original, frozen) and "ranked" (the
 * latest morphed version, reflecting the current Maître de la Ligue).
 *
 * Option B of the Classique/Compétitif dual-mode design (chosen by user
 * 2026-05-17): one in-arena NPC, but the live JSON is swapped at the moment
 * Alfred teleports the player into the Conseil 4 dimension. Race conditions
 * between two simultaneous entries with opposite modes are rare in practice
 * (~5-10 entries/week) and mitigated by a coalescing no-op check.
 *
 * Persistence layout:
 *   config/cobblecap_regis_backup/champion_regis_challenge.json          ← classic (pristine)
 *   config/cobblecap_regis_backup/champion_regis_challenge.MORPHED.json  ← ranked (last morph)
 * Both files are written by their respective producers ({@link RctTrainerMorpher}
 * ensureBackup + flushMorph). This class only reads them and copies to the
 * live datapack location, then dispatches a reload.
 */
public class RegisModeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static final ReentrantLock SWAP_LOCK = new ReentrantLock();

    public enum Mode {
        CLASSIC("classic"),
        RANKED("ranked");

        private final String key;
        Mode(String key) { this.key = key; }
        public String key() { return key; }
        public static Mode fromKey(String key) {
            if (key == null) return CLASSIC;
            String k = key.trim().toLowerCase();
            return k.equals("ranked") || k.equals("competitive") || k.equals("competitif") || k.equals("2")
                    ? RANKED : CLASSIC;
        }
    }

    /** Last applied live mode — used to short-circuit no-op swaps. */
    private static volatile Mode currentLive = null;

    private static final Path BACKUP_DIR = FabricLoader.getInstance()
            .getConfigDir().resolve("cobblecap_regis_backup");
    private static final Path CLASSIC_JSON = BACKUP_DIR.resolve("champion_regis_challenge.json");
    private static final Path RANKED_JSON  = BACKUP_DIR.resolve("champion_regis_challenge.MORPHED.json");

    /**
     * Swap the live trainer JSON to the requested mode and trigger /reload.
     * Returns true if a swap was actually performed; false if the requested
     * mode is already live (no-op) or if no source file exists.
     */
    public static boolean applyMode(MinecraftServer server, Mode mode) {
        SWAP_LOCK.lock();
        try {
            if (mode == currentLive) {
                LOGGER.debug("[CobbleCap] Régis already in {} mode — swap skipped", mode.key());
                return false;
            }
            // First-time call: snapshot the original via RctTrainerMorpher's logic.
            RctTrainerMorpher.ensureBackup(server);

            Path source = (mode == Mode.RANKED) ? RANKED_JSON : CLASSIC_JSON;
            if (!Files.isRegularFile(source)) {
                if (mode == Mode.RANKED) {
                    LOGGER.info("[CobbleCap] No ranked snapshot yet — falling back to classic for {}", mode.key());
                    source = CLASSIC_JSON;
                    mode = Mode.CLASSIC;
                    if (!Files.isRegularFile(source)) {
                        LOGGER.warn("[CobbleCap] Neither ranked nor classic snapshot exists — swap aborted");
                        return false;
                    }
                } else {
                    LOGGER.warn("[CobbleCap] Classic snapshot missing at {} — swap aborted", source);
                    return false;
                }
            }

            Path dpRoot = RctTrainerMorpher.locateDatapackRoot(server);
            if (dpRoot == null) return false;
            Path live = dpRoot.resolve(RctTrainerMorpher.trainerJsonRelPath);
            try {
                Files.createDirectories(live.getParent());
                Files.copy(source, live, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOGGER.warn("[CobbleCap] Régis JSON copy failed: {}", e.getMessage());
                return false;
            }
            currentLive = mode;
            final Mode appliedMode = mode;
            server.execute(() -> {
                // The `rctmod reload [trainer]` command does NOT exist (verified against
                // rctmod 0.16.11-beta bytecode) — the old dispatcher loop always threw and
                // was swallowed, so the in-memory trainer cache was NEVER refreshed and the
                // engine kept serving the boot-time (default) team. We now call rctmod's real
                // reload entry point (TrainerManager.loadTrainers()) by reflection, which
                // re-reads the trainer JSONs from disk WITHOUT the vanilla /reload datapack
                // rescan that would freeze the server.
                boolean rctOk = RctReloadHook.reloadTrainers(server);
                LOGGER.info("[CobbleCap] Régis mode applied: {} (rctmod reload ok={}; vanilla /reload NEVER triggered)",
                        appliedMode.key(), rctOk);
            });
            return true;
        } finally {
            SWAP_LOCK.unlock();
        }
    }

    /** Persist the freshly-morphed ranked JSON so it can be re-applied later. */
    public static void saveRankedSnapshot(MinecraftServer server) {
        try {
            Path dpRoot = RctTrainerMorpher.locateDatapackRoot(server);
            if (dpRoot == null) return;
            Path live = dpRoot.resolve(RctTrainerMorpher.trainerJsonRelPath);
            if (!Files.isRegularFile(live)) return;
            Files.createDirectories(BACKUP_DIR);
            Files.copy(live, RANKED_JSON, StandardCopyOption.REPLACE_EXISTING);
            currentLive = Mode.RANKED;
            LOGGER.info("[CobbleCap] Ranked Régis snapshot saved -> {}", RANKED_JSON);
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] saveRankedSnapshot failed: {}", e.getMessage());
        }
    }

    public static Mode getCurrentLive() { return currentLive; }
}
