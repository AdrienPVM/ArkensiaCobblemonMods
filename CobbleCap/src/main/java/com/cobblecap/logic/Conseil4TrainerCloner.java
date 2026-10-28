package com.cobblecap.logic;

import com.cobblecap.data.LegendEntry;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-dimension trainer ID isolation for the Conseil 4 (Elite Four + Régis).
 * <p>
 * The bug we work around: rctmod identifies a trainer globally by its config ID
 * (e.g. {@code elite_four_olga_challenge}). Even when two players sit in
 * separate per-player dimension copies ({@code cheeseguard:conseil4_<uuid8>}),
 * {@code RCTApi.BattleManager} sees a single shared {@code TrainerNPC} singleton
 * and refuses {@code makeBattle()} with {@code AlreadyInBattleError} while any
 * other battle holds the same trainer. Result: player B waits for player A's
 * Olga fight to finish before they can start their own.
 * <p>
 * Workaround (Option A discussed 2026-05-17): clone the trainer datapack
 * entries to per-dim variants on demand. When player {@code abcd1234} enters
 * their {@code conseil4_abcd1234} dim, we materialize:
 * <ul>
 *   <li>{@code data/rctmod/trainers/elite_four_olga_challenge_abcd1234.json}</li>
 *   <li>{@code data/rctmod/mobs/trainers/single/elite_four_olga_challenge_abcd1234.json}</li>
 *   <li>… same for aldo, agatha, peter, and champion_regis_challenge</li>
 * </ul>
 * trigger a {@code rctmod reload trainer}, then retag the spawned
 * {@code TrainerMob} entities in that dim via {@code setTrainerId(...)}.
 * Each per-dim trainer now has its own {@code TrainerNPC} singleton — battles
 * no longer collide.
 * <p>
 * For Régis ranked mode the source JSON is {@code RegisModeManager}'s
 * {@code MORPHED.json} snapshot (if it exists), so the in-dim Régis reflects
 * the current Maître de la Ligue. Classic mode uses the pristine backup.
 * <p>
 * Cleanup: scan + delete orphan clones at server start (UUIDs whose per-player
 * dim no longer exists). Clones persist across restarts otherwise — the entity
 * NBT carries the per-dim ID, so the trainer keeps working after a restart
 * without needing the runtime retag pass.
 */
public class Conseil4TrainerCloner {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    /** Canonical Conseil 4 trainer IDs. Order is not significant. */
    public static final List<String> CONSEIL4_BASE_IDS = List.of(
            "elite_four_olga_challenge",
            "elite_four_aldo_challenge",
            "elite_four_agatha_challenge",
            "elite_four_peter_challenge",
            "champion_regis_challenge"
    );

    /** Matches the per-player conseil4 dim path: {@code conseil4_<8hex>}. */
    private static final Pattern PERPLAYER_DIM_PATH = Pattern.compile("^conseil4_([0-9a-f]{8})$");

    /** Matches a per-dim cloned trainer filename: {@code <baseId>_<8hex>.json}. */
    private static final Pattern CLONE_FILENAME = Pattern.compile(
            "^(" + String.join("|", CONSEIL4_BASE_IDS) + ")_([0-9a-f]{8})\\.json$");

    private static final String REGIS_ID = "champion_regis_challenge";

    private static final Path REGIS_BACKUP_DIR = FabricLoader.getInstance()
            .getConfigDir().resolve("cobblecap_regis_backup");
    private static final Path REGIS_CLASSIC_BACKUP = REGIS_BACKUP_DIR.resolve("champion_regis_challenge.json");
    private static final Path REGIS_RANKED_BACKUP  = REGIS_BACKUP_DIR.resolve("champion_regis_challenge.MORPHED.json");

    /** Set of (dimSuffix) we've already provisioned this session — short-circuits repeat entries. */
    private final Set<String> provisioned = java.util.Collections.synchronizedSet(new HashSet<>());

    /** Subset of {@link #provisioned} whose Régis clone was sourced from the
     *  ranked snapshot (MORPHED.json). Used by {@link #refreshAllRankedRegis}
     *  to re-clone after a morph so other players in their dim see the new
     *  Maître de la Ligue without having to leave and re-enter. */
    private final Set<String> rankedSuffixes = java.util.Collections.synchronizedSet(new HashSet<>());

    /** Coalesce reload commands: when set, the next END_SERVER_TICK fires one reload. */
    private volatile boolean reloadPending = false;
    private int retagTickCounter = 0;

    public void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(this::onPlayerChangeWorld);
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
    }

    /**
     * Disabled-mode registration: only does the boot-time cleanup pass that
     * reverts stale per-dim trainer IDs (left by the failed 2026-05-17 deploy)
     * and removes orphan clone JSONs from the datapack. No live clone/retag
     * hooks are registered — the per-dim isolation feature is deferred until
     * we have a working approach (rctmod reload doesn't pick up new files
     * from disk).
     */
    public void registerCleanupOnly() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStartedCleanupOnly);
        // Also catch entities loaded later (when player enters a per-dim that
        // wasn't loaded at boot). Without this, stale per-dim IDs persist in
        // worlds that load on demand.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD
                .register((entity, world) -> revertIfStale(entity, world));
    }

    /** Per-entity stale ID revert — fires on ENTITY_LOAD for late-loading dims. */
    private void revertIfStale(Entity entity, ServerWorld world) {
        try {
            String path = world.getRegistryKey().getValue().getPath();
            if (!PERPLAYER_DIM_PATH.matcher(path).matches()) return;
            if (!"rctmod".equals(EntityType.getId(entity.getType()).getNamespace())) return;
            String currentId = ChampionManager.extractRctmodTrainerId(entity);
            if (currentId == null) return;
            String canonical = ChampionManager.stripPerDimSuffix(currentId);
            if (canonical.equals(currentId)) return;
            if (applySetTrainerId(entity, canonical)) {
                LOGGER.info("[CobbleCap] Conseil4 late-revert: {} -> {} (entity {} in {})",
                        currentId, canonical, entity.getUuid(), path);
            }
        } catch (Exception ignored) {}
    }

    private void onServerStartedCleanupOnly(MinecraftServer server) {
        try {
            // 1. Revert any TrainerMob entities with stale per-dim suffix IDs
            //    so rctmod stops yelling "Invalid trainer id ..." at every tick.
            int reverted = revertStaleTrainerIds(server);
            // 2. Delete all per-dim clone JSON files from the datapack so future
            //    /reload (vanilla, on next boot via Minestrator restart cycle)
            //    doesn't reload them as orphan trainers.
            int deleted = deleteAllPerDimClones(server);
            if (reverted > 0 || deleted > 0) {
                LOGGER.info("[CobbleCap] Conseil4 cleanup: reverted {} stale entity ID(s), deleted {} orphan clone file(s)",
                        reverted, deleted);
            }
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Conseil4 cleanup-only pass failed: {}", e.getMessage());
        }
    }

    /**
     * Scan all loaded {@code conseil4_<uuid8>} worlds for TrainerMob entities
     * whose {@code TrainerId} NBT has the per-dim suffix and revert them to
     * the canonical base ID via reflection on {@code setTrainerId}.
     */
    private int revertStaleTrainerIds(MinecraftServer server) {
        int total = 0;
        for (ServerWorld world : server.getWorlds()) {
            String path = world.getRegistryKey().getValue().getPath();
            Matcher m = PERPLAYER_DIM_PATH.matcher(path);
            if (!m.matches()) continue;
            for (Entity entity : world.iterateEntities()) {
                if (!"rctmod".equals(EntityType.getId(entity.getType()).getNamespace())) continue;
                String currentId = ChampionManager.extractRctmodTrainerId(entity);
                if (currentId == null) continue;
                String canonical = ChampionManager.stripPerDimSuffix(currentId);
                if (canonical.equals(currentId)) continue; // no suffix, nothing to revert
                if (applySetTrainerId(entity, canonical)) {
                    total++;
                    LOGGER.info("[CobbleCap] Conseil4 cleanup: reverted {} -> {} on entity {}",
                            currentId, canonical, entity.getUuid());
                }
            }
        }
        return total;
    }

    /**
     * Remove every {@code <baseId>_<uuid8>.json} clone file from the datapack —
     * both the trainer config and the mob spec sibling — so no orphan trainers
     * remain after the failed deploy. Idempotent: missing files are skipped.
     */
    private int deleteAllPerDimClones(MinecraftServer server) {
        Path dpRoot = RctTrainerMorpher.locateDatapackRoot(server);
        if (dpRoot == null) return 0;
        int deleted = 0;
        Path[] dirs = new Path[]{
                dpRoot.resolve("data/rctmod/trainers"),
                dpRoot.resolve("data/rctmod/mobs/trainers/single"),
        };
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    Matcher mm = CLONE_FILENAME.matcher(p.getFileName().toString());
                    if (!mm.matches()) continue;
                    try { Files.delete(p); deleted++; }
                    catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return deleted;
    }

    // ── Server start: clean up orphans + force a reload to register surviving clones ──

    private void onServerStarted(MinecraftServer server) {
        try {
            Set<String> liveSuffixes = collectLiveDimSuffixes(server);
            int removed = cleanupOrphans(server, liveSuffixes);
            // DO NOT mark liveSuffixes as provisioned here. Pre-existing dim
            // folders from prior sessions may not yet have clone JSONs on disk
            // (e.g. this is the first deploy of the cloner). The first
            // onPlayerChangeWorld for each suffix will run ensureClones, and
            // cloneTrainer's "if (target exists && !regisRanked) skip" check
            // makes that pass idempotent for clones already on disk.
            if (removed > 0 || !liveSuffixes.isEmpty()) {
                scheduleReload();
                LOGGER.info("[CobbleCap] Conseil4Cloner boot: {} active dims, {} orphan clone(s) removed",
                        liveSuffixes.size(), removed);
            }
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Conseil4Cloner onServerStarted failed: {}", e.getMessage());
        }
    }

    /** Walk the CheeseGuard dim folder to find existing {@code conseil4_<uuid8>} per-player dims. */
    private Set<String> collectLiveDimSuffixes(MinecraftServer server) {
        Set<String> out = new HashSet<>();
        try {
            // Look in <world>/dimensions/cheeseguard/conseil4_<uuid8>/
            String levelName = server.getSaveProperties().getLevelName();
            Path runDir = server.getRunDirectory();
            Path[] candidates = new Path[]{
                    runDir.resolve(levelName).resolve("dimensions").resolve("cheeseguard"),
                    runDir.resolve("world").resolve("dimensions").resolve("cheeseguard"),
            };
            for (Path dimRoot : candidates) {
                if (!Files.isDirectory(dimRoot)) continue;
                try (var stream = Files.list(dimRoot)) {
                    stream.filter(Files::isDirectory).forEach(p -> {
                        Matcher m = PERPLAYER_DIM_PATH.matcher(p.getFileName().toString());
                        if (m.matches()) out.add(m.group(1));
                    });
                }
                break; // first match wins
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] collectLiveDimSuffixes: {}", e.getMessage());
        }
        return out;
    }

    private int cleanupOrphans(MinecraftServer server, Set<String> liveSuffixes) {
        int removed = 0;
        Path dpRoot = RctTrainerMorpher.locateDatapackRoot(server);
        if (dpRoot == null) return 0;
        Path[] dirs = new Path[]{
                dpRoot.resolve("data/rctmod/trainers"),
                dpRoot.resolve("data/rctmod/mobs/trainers/single"),
        };
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    Matcher m = CLONE_FILENAME.matcher(p.getFileName().toString());
                    if (!m.matches()) continue;
                    String suffix = m.group(2);
                    if (!liveSuffixes.contains(suffix)) {
                        try {
                            Files.delete(p);
                            removed++;
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
        return removed;
    }

    // ── Player enters a per-player conseil4 dim: ensure clones + retag ──

    private void onPlayerChangeWorld(ServerPlayerEntity player, ServerWorld origin, ServerWorld destination) {
        try {
            String path = destination.getRegistryKey().getValue().getPath();
            Matcher m = PERPLAYER_DIM_PATH.matcher(path);
            if (!m.matches()) return;
            String suffix = m.group(1);
            boolean ranked = isPlayerInRankedMode(player);
            LOGGER.info("[CobbleCap] Conseil4 entry: player={} suffix={} mode={}",
                    player.getName().getString(), suffix, ranked ? "RANKED" : "CLASSIC");
            MinecraftServer server = destination.getServer();
            boolean changed = ensureClones(server, suffix, ranked);
            // CRITICAL: reload synchronously BEFORE retagging. Without this,
            // rctmod's TrainerRegistry doesn't know about the per-dim trainer IDs
            // yet → setTrainerId throws "Invalid trainer id" → reflection swallows
            // it → entity is left in a partially-mutated state where it has the
            // new ID stored but the registry can't resolve it → battle uses
            // whatever fallback rctmod picks (often the global trainer or default).
            if (changed) triggerRctmodReloadNow(server);
            retagWorld(destination, suffix);
            // Régis skin overlay: spawn doppels for ranked players, tear down
            // for classic so the original rctmod render is restored.
            try {
                com.cobblecap.CobbleCapMod.INSTANCE.regisDoppelMgr.onDimEntered(destination, ranked);
            } catch (Throwable t) {
                LOGGER.debug("[CobbleCap] Conseil4 Régis overlay sync failed: {}", t.getMessage());
            }
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Conseil4Cloner onPlayerChangeWorld failed: {}", e.getMessage());
        }
    }

    /**
     * Ensure all 5 Conseil 4 trainer clones exist on disk for the given dim suffix.
     * For Régis: source is the ranked snapshot when {@code ranked=true} and it
     * exists, otherwise the classic backup, otherwise the live JSON. Other
     * trainers always source from the live datapack JSON.
     * <p>
     * Returns {@code true} if any clone file was written, so the caller can
     * decide whether a reload is needed.
     */
    private boolean ensureClones(MinecraftServer server, String suffix, boolean ranked) {
        if (server == null) return false;
        Path dpRoot = RctTrainerMorpher.locateDatapackRoot(server);
        if (dpRoot == null) {
            LOGGER.warn("[CobbleCap] Datapack root not found — Conseil4 clone skipped for {}", suffix);
            return false;
        }
        boolean changed = false;
        for (String baseId : CONSEIL4_BASE_IDS) {
            changed |= cloneTrainer(dpRoot, baseId, suffix, ranked);
            changed |= cloneMobSpec(dpRoot, baseId, suffix);
        }
        provisioned.add(suffix);
        if (ranked) rankedSuffixes.add(suffix);
        else rankedSuffixes.remove(suffix); // mode flip → drop stale ranked tracking
        return changed;
    }

    /**
     * Re-clone Régis for every dim currently tracked as ranked, sourcing from
     * the latest MORPHED.json snapshot. Called by {@link RctTrainerMorpher}
     * right after a fresh ranked-champion snapshot is saved — without this,
     * other players sitting in their conseil4_xxx dim would keep facing the
     * previous champion until they left and re-entered.
     * <p>
     * Ongoing battles are unaffected: rctmod caches the team at battle start,
     * so the new file only changes what is loaded for the NEXT battle.
     */
    public void refreshAllRankedRegis(MinecraftServer server) {
        if (server == null) return;
        if (rankedSuffixes.isEmpty()) return;
        Path dpRoot = RctTrainerMorpher.locateDatapackRoot(server);
        if (dpRoot == null) return;
        int refreshed = 0;
        // Snapshot the set since cloneTrainer may incidentally observe rankedSuffixes.
        for (String suffix : new java.util.ArrayList<>(rankedSuffixes)) {
            if (cloneTrainer(dpRoot, REGIS_ID, suffix, /*ranked=*/true)) refreshed++;
        }
        if (refreshed > 0) {
            triggerRctmodReloadNow(server);
            LOGGER.info("[CobbleCap] Refreshed {} per-dim Régis clone(s) from latest MORPHED snapshot",
                    refreshed);
        }
    }

    /**
     * Clone {@code data/rctmod/trainers/<baseId>.json} -> {@code <baseId>_<suffix>.json}.
     * Returns {@code true} if the file was (re)written. Idempotent except for the
     * Régis ranked case, where the morphed snapshot may be newer than an existing
     * clone — in that case we overwrite so the in-dim Régis reflects the current
     * Maître de la Ligue at the moment the player enters.
     */
    private boolean cloneTrainer(Path dpRoot, String baseId, String suffix, boolean ranked) {
        Path target = dpRoot.resolve("data/rctmod/trainers/" + baseId + "_" + suffix + ".json");
        Path source = resolveTrainerSource(dpRoot, baseId, ranked);
        if (source == null || !Files.isRegularFile(source)) {
            LOGGER.warn("[CobbleCap] Source trainer file missing for {} (ranked={}) — tried {}",
                    baseId, ranked, source);
            return false;
        }
        // ALWAYS overwrite Régis (regardless of mode) so a player's per-dim
        // copy reflects the live state — MORPHED.json (ranked) or pristine
        // backup (classic). Other trainers are static and only written on
        // first encounter to avoid pointless I/O.
        boolean isRegis = REGIS_ID.equals(baseId);
        if (Files.isRegularFile(target) && !isRegis) return false;
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[CobbleCap] Conseil4 clone: {} -> {} (ranked={})", baseId, target.getFileName(), ranked);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Conseil4 clone failed for {}: {}", baseId, e.getMessage());
            return false;
        }
    }

    private Path resolveTrainerSource(Path dpRoot, String baseId, boolean ranked) {
        if (REGIS_ID.equals(baseId)) {
            if (ranked && Files.isRegularFile(REGIS_RANKED_BACKUP)) return REGIS_RANKED_BACKUP;
            if (Files.isRegularFile(REGIS_CLASSIC_BACKUP)) return REGIS_CLASSIC_BACKUP;
        }
        return dpRoot.resolve("data/rctmod/trainers/" + baseId + ".json");
    }

    /**
     * Clone the mob spec file too. rctmod's TrainerSpawner / DataPackManager
     * looks up {@code mobs/trainers/single/<id>.json} by the trainer ID, so the
     * per-dim variant needs its own file. Without this the per-dim trainer may
     * miss spawn metadata (battle cooldown, type, etc.).
     */
    private boolean cloneMobSpec(Path dpRoot, String baseId, String suffix) {
        Path target = dpRoot.resolve("data/rctmod/mobs/trainers/single/" + baseId + "_" + suffix + ".json");
        Path source = dpRoot.resolve("data/rctmod/mobs/trainers/single/" + baseId + ".json");
        if (!Files.isRegularFile(source)) return false;
        if (Files.isRegularFile(target)) return false; // idempotent
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Conseil4 mob spec clone failed for {}: {}", baseId, e.getMessage());
            return false;
        }
    }

    // ── Entity retagging ──

    private static volatile Method cachedSetTrainerId;

    /**
     * Scan all loaded TrainerMobs in the given per-player conseil4 world and
     * retag any whose current ID is one of the canonical Conseil 4 base IDs to
     * the per-dim suffixed variant. Idempotent — entities already retagged are
     * skipped.
     */
    private void retagWorld(ServerWorld world, String suffix) {
        try {
            int retagged = 0;
            for (Entity entity : world.iterateEntities()) {
                if (!"rctmod".equals(EntityType.getId(entity.getType()).getNamespace())) continue;
                String currentId = ChampionManager.extractRctmodTrainerId(entity);
                if (currentId == null) continue;
                if (!CONSEIL4_BASE_IDS.contains(currentId)) continue; // already suffixed or unrelated
                String targetId = currentId + "_" + suffix;
                if (applySetTrainerId(entity, targetId)) {
                    retagged++;
                }
            }
            if (retagged > 0) {
                LOGGER.info("[CobbleCap] Conseil4 retag in {}: {} entities -> *_{}",
                        world.getRegistryKey().getValue(), retagged, suffix);
            }
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Conseil4 retag failed: {}", e.getMessage());
        }
    }

    private boolean applySetTrainerId(Entity entity, String newId) {
        try {
            Method m = cachedSetTrainerId;
            if (m == null) {
                // Scan once: pick the public 1-arg String method named setTrainerId
                for (Method candidate : entity.getClass().getMethods()) {
                    if ("setTrainerId".equals(candidate.getName())
                            && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0] == String.class) {
                        candidate.setAccessible(true);
                        cachedSetTrainerId = candidate;
                        m = candidate;
                        break;
                    }
                }
            }
            if (m == null) return false;
            m.invoke(entity, newId);
            return true;
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] applySetTrainerId({}) failed: {}", newId, e.getMessage());
            return false;
        }
    }

    // ── Periodic retag for newly-loaded chunks ──

    /**
     * Once per second, scan loaded per-player conseil4 worlds for TrainerMobs
     * still carrying canonical IDs and retag them. Catches the case where a
     * trainer's chunk wasn't loaded at the moment the player entered the dim
     * — e.g. Régis is on the far side of the building and his chunk only loads
     * when the player approaches.
     */
    private void onTick(MinecraftServer server) {
        // Single coalesced reload: fire ASAP after files changed.
        if (reloadPending) {
            reloadPending = false;
            triggerRctmodReload(server);
        }
        if (++retagTickCounter % 20 != 0) return;
        try {
            for (ServerWorld world : server.getWorlds()) {
                String path = world.getRegistryKey().getValue().getPath();
                Matcher m = PERPLAYER_DIM_PATH.matcher(path);
                if (!m.matches()) continue;
                retagWorld(world, m.group(1));
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] Conseil4 periodic retag: {}", e.getMessage());
        }
    }

    private void scheduleReload() { reloadPending = true; }

    private void triggerRctmodReload(MinecraftServer server) { triggerRctmodReloadNow(server); }

    /**
     * Synchronous rctmod reload — call ON the server thread before retagging
     * entities so rctmod's {@code TrainerRegistry} actually knows about the
     * per-dim IDs we're about to assign. {@code rctmod reload trainer} returns
     * fast (in-memory map refresh, no datapack-wide rescan), so this is safe to
     * run inline from event handlers.
     */
    private void triggerRctmodReloadNow(MinecraftServer server) {
        // rctmod has no reload command — call its real reload entry by reflection
        // (TrainerManager.loadTrainers(): in-memory map refresh from disk, no vanilla
        // /reload datapack-wide rescan). The old dispatcher loop always threw silently,
        // so the per-dim IDs were never visible to rctmod's TrainerRegistry.
        boolean ok = RctReloadHook.reloadTrainers(server);
        reloadPending = false; // anything queued is now fulfilled
        LOGGER.info("[CobbleCap] Conseil4Cloner: rctmod reload ok={} (sync)", ok);
    }

    // ── Helpers ──

    /** Delegate to ChampionManager's mode reader so we stay in sync with its scoreboard convention. */
    private static boolean isPlayerInRankedMode(ServerPlayerEntity player) {
        try { return ChampionManager.readPlayerMode(player) == LegendEntry.Mode.RANKED; }
        catch (Exception e) { return false; }
    }
}
