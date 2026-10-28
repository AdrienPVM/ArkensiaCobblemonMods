package com.cheesefixquests.logic;

import com.cheesefixquests.CheeseFixQuestsMod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Forensic audit log for quest finalization and reward claiming. Writes to
 * {@code config/cheesefixquests_audit.log}, append-only, thread-safe.
 *
 * <p>Disabled by default. Enable via {@code auditQuestEvents: true} in
 * {@code config/cheesefixquests.json} — flip on when investigating a duplicate
 * report, flip off in steady-state to avoid log churn.</p>
 *
 * <h3>Implementation note</h3>
 *
 * <p>A single {@link BufferedWriter} is opened lazily on first write and held
 * open for the lifetime of the JVM. Each line is flushed immediately so a
 * crash does not lose audit trail. This is cheaper than re-opening the file
 * per-line and avoids the filesystem-stall risk during reward bursts (e.g. an
 * event with 50 players claiming concurrently). All writes are serialised
 * through {@link #LOCK} so the underlying writer never sees concurrent calls.</p>
 *
 * <p>Each line begins with a ms-precision timestamp + thread name so a true
 * cross-thread race can be reconstructed from the file alone.</p>
 */
public final class QuestAuditLogger {

    private static final Path LOG_PATH = Path.of("config", "cheesefixquests_audit.log");
    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private static final Object LOCK = new Object();
    private static volatile BufferedWriter writer;
    private static volatile boolean initFailed = false;

    static {
        // Best-effort: register a JVM shutdown hook so the buffered tail flushes.
        Runtime.getRuntime().addShutdownHook(new Thread(QuestAuditLogger::shutdown,
                "CheeseFixQuests-AuditFlush"));
    }

    private QuestAuditLogger() {}

    /**
     * Per-player Phase-C sweep summary. Always emitted (when audit is on),
     * even with all-zero counts — proves the sweep is actually running.
     */
    public static void logSweep(String threadName, UUID playerUuid, String playerName,
                                 int visited, int itemScans, int itemRepairs, int itemSets,
                                 int orphanRepairs, int finalizes) {
        write("PHASE_C_SWEEP",
                "thread=" + threadName,
                "player=" + playerName + "/" + playerUuid,
                "visited=" + visited,
                "itemScans=" + itemScans,
                "itemRepairs=" + itemRepairs,
                "itemSets=" + itemSets,
                "orphanRepairs=" + orphanRepairs,
                "finalizes=" + finalizes);
    }

    /**
     * Defensive ItemTask scanner repaired a task by directly counting
     * inventory matches and calling markTaskCompleted. Bypasses FTBQ's
     * listener-based path entirely.
     */
    public static void logItemRepair(String threadName, long questId, long taskId,
                                      UUID teamId, String playerName, long matches, long max) {
        write("ITEM_REPAIR",
                "thread=" + threadName,
                "quest=" + Long.toHexString(questId).toUpperCase(),
                "task=" + Long.toHexString(taskId).toUpperCase(),
                "team=" + teamId,
                "player=" + playerName,
                "matches=" + matches,
                "max=" + max);
    }

    /**
     * Defensive ItemTask scanner detected partial progress and pushed it via
     * setProgress (matches > current but matches < max).
     */
    public static void logItemSet(String threadName, long questId, long taskId,
                                   UUID teamId, String playerName,
                                   long oldProgress, long newProgress) {
        write("ITEM_SET",
                "thread=" + threadName,
                "quest=" + Long.toHexString(questId).toUpperCase(),
                "task=" + Long.toHexString(taskId).toUpperCase(),
                "team=" + teamId,
                "player=" + playerName,
                "old=" + oldProgress,
                "new=" + newProgress);
    }

    public static void logFinalize(String threadName, long questId, UUID teamId, String playerName) {
        write("FINALIZE",
                "thread=" + threadName,
                "quest=" + Long.toHexString(questId).toUpperCase(),
                "team=" + teamId,
                "player=" + playerName);
    }

    /**
     * Repaired an orphaned task: progress was at max but the {@code completed}
     * map entry was missing because of {@code TeamData.setProgress}'s
     * {@code count == prev} short-circuit. We forced {@code markTaskCompleted}.
     */
    public static void logTaskRepair(String threadName, long questId, long taskId,
                                      UUID teamId, String playerName) {
        write("TASK_REPAIR",
                "thread=" + threadName,
                "quest=" + Long.toHexString(questId).toUpperCase(),
                "task=" + Long.toHexString(taskId).toUpperCase(),
                "team=" + teamId,
                "player=" + playerName);
    }

    public static void logFinalizeSkipped(String threadName, long questId, UUID teamId, String reason) {
        write("FINALIZE_SKIP",
                "thread=" + threadName,
                "quest=" + Long.toHexString(questId).toUpperCase(),
                "team=" + teamId,
                "reason=" + reason);
    }

    public static void logClaimReserved(String threadName, Object key) {
        write("CLAIM_RESERVED",
                "thread=" + threadName,
                "key=" + key);
    }

    public static void logClaimBlocked(String threadName, Object key) {
        write("CLAIM_BLOCKED",
                "thread=" + threadName,
                "key=" + key);
    }

    public static void logPokedexResync(String threadName, UUID playerId) {
        write("POKEDEX_RESYNC",
                "thread=" + threadName,
                "player=" + playerId);
    }

    private static void write(String event, String... fields) {
        if (initFailed) return;
        StringBuilder sb = new StringBuilder(160);
        sb.append(TS.format(Instant.now()))
          .append(' ').append(event);
        for (String f : fields) sb.append(' ').append(f);
        sb.append(System.lineSeparator());
        String line = sb.toString();
        synchronized (LOCK) {
            try {
                BufferedWriter w = writer;
                if (w == null) {
                    Files.createDirectories(LOG_PATH.getParent());
                    w = Files.newBufferedWriter(LOG_PATH,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                    writer = w;
                }
                w.write(line);
                w.flush(); // flush each line so a crash preserves the trail
            } catch (IOException ioe) {
                if (!initFailed) {
                    initFailed = true;
                    CheeseFixQuestsMod.LOGGER.warn(
                            "[CheeseFixQuests] Audit log write failed, audit disabled: {}",
                            ioe.getMessage());
                    closeQuietly();
                }
            }
        }
    }

    /** Called from the JVM shutdown hook — best-effort flush + close. */
    public static void shutdown() {
        synchronized (LOCK) {
            closeQuietly();
        }
    }

    private static void closeQuietly() {
        BufferedWriter w = writer;
        writer = null;
        if (w != null) {
            try { w.flush(); } catch (IOException ignored) {}
            try { w.close(); } catch (IOException ignored) {}
        }
    }
}
