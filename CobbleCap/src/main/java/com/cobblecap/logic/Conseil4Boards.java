package com.cobblecap.logic;

import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.LegendEntry;
import com.cobblecap.data.Tenure;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.scoreboard.number.StyledNumberFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Conseil 4 leaderboards — split by mode (Classique / Compétitif) as of 2026-05-17.
 *
 * Boards:
 *  - {@code conseil4_first_finish}    — CLASSIC: order of first finishers (oldest first)
 *  - {@code conseil4_fastest_time}    — CLASSIC: best chrono in seconds (faster first)
 *  - {@code conseil4_ranked_champions} — RANKED: champion tenures sorted by start date DESC
 *                                        (current champion always #1)
 *  - {@code conseil4_ranked_failures} — RANKED: players who LOST against the current
 *                                        champion's Régis NPC, sorted by fail count DESC.
 *                                        Resets every time a new ranked champion arrives.
 *
 * Each board writes:
 *  1. A vanilla MC scoreboard (in-game sidebar display)
 *  2. A JSON export at {@code config/cobblecheesescore_external_conseil4_*.json} consumed
 *     by CobbleCheeseScore to render the dashboard tile.
 */
public final class Conseil4Boards {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Existing (kept names for back-compat with CCS config) — now CLASSIC-only.
    public static final String OBJ_FIRST   = "conseil4_first_finish";
    public static final String OBJ_FASTEST = "conseil4_fastest_time";
    // New ranked boards.
    public static final String OBJ_RANKED_CHAMPIONS = "conseil4_ranked_champions";
    public static final String OBJ_RANKED_FAILURES  = "conseil4_ranked_failures";

    private static final Path EXPORT_FIRST    = export("conseil4_first.json");
    private static final Path EXPORT_FASTEST  = export("conseil4_fastest.json");
    private static final Path EXPORT_CHAMPS   = export("conseil4_ranked_champions.json");
    private static final Path EXPORT_FAILURES = export("conseil4_ranked_failures.json");

    private static Path export(String name) {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("cobblecheesescore_external_" + name);
    }

    private Conseil4Boards() {}

    public static void initObjectives(MinecraftServer server) {
        if (server == null) return;
        try {
            Scoreboard sb = server.getScoreboard();
            ensureObjective(sb, OBJ_FIRST,             "§6Conseil 4 — Premiers vainqueurs");
            ensureObjective(sb, OBJ_FASTEST,           "§6Conseil 4 — Temps les plus rapides");
            ensureObjective(sb, OBJ_RANKED_CHAMPIONS,  "§bConseil 4 — Champions en titre");
            ensureObjective(sb, OBJ_RANKED_FAILURES,   "§bConseil 4 — Échecs face au Maître");
            // Player mode flag used by Alfred dialogs + ChampionManager — pre-create so
            // /cobblecap mode set never has to bootstrap the objective at command-time.
            ensureObjective(sb, "cobblecap_mode",      "§eMode Conseil 4");
            LOGGER.info("[CobbleCap] Conseil 4 scoreboards ready: 4 boards (classic ×2 + ranked ×2)");

            // Populate from actual data (legends + tenures). Previous code wrote
            // empty arrays here which wiped the CCS exports at every boot — fixed
            // by replacing the empty writes with a full rebuild from persistent
            // storage so the dashboard never shows blank scoreboards after a
            // restart.
            rebuild(server);
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Conseil4Boards init failed: {}", e.getMessage());
        }
    }

    public static void rebuild(MinecraftServer server) {
        if (server == null) return;
        try {
            List<LegendEntry> allLegends = CobbleCapMod.INSTANCE.getLegendsStorage().getAll();
            // Filter CLASSIC-only for the classic boards (legacy entries without
            // an explicit mode default to CLASSIC via LegendEntry.fromJson).
            List<LegendEntry> classic = new ArrayList<>();
            for (LegendEntry e : allLegends) {
                if (e.mode() == null || e.mode() == LegendEntry.Mode.CLASSIC) classic.add(e);
            }
            rebuildClassicFirst(server, classic);
            rebuildClassicFastest(server, classic);

            List<Tenure> tenures = CobbleCapMod.INSTANCE.getTenureStorage().allReverseChronological();
            rebuildRankedChampions(server, tenures);

            Tenure current = CobbleCapMod.INSTANCE.getTenureStorage().current();
            rebuildRankedFailures(server, current);
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Conseil4Boards rebuild failed: {}", e.getMessage());
        }
    }

    // ── CLASSIC boards ───────────────────────────────────────────────────────

    private static void rebuildClassicFirst(MinecraftServer server, List<LegendEntry> classic) {
        Scoreboard sb = server.getScoreboard();
        List<LegendEntry> sorted = new ArrayList<>(classic);
        sorted.sort(Comparator.comparingLong(LegendEntry::completedAt));
        replaceObjective(sb, OBJ_FIRST, "§6Conseil 4 — Premiers vainqueurs");
        ScoreboardObjective obj = ensureObjective(sb, OBJ_FIRST, "§6Conseil 4 — Premiers vainqueurs");
        for (int i = 0; i < sorted.size(); i++) {
            LegendEntry e = sorted.get(i);
            // Inverted score (higher = older finisher) so #1 shows at top.
            sb.getOrCreateScore(ScoreHolder.fromName(e.playerName()), obj).setScore(sorted.size() - i);
        }
        exportClassicFirst(sorted);
    }

    private static void rebuildClassicFastest(MinecraftServer server, List<LegendEntry> classic) {
        Scoreboard sb = server.getScoreboard();
        List<LegendEntry> sorted = new ArrayList<>();
        for (LegendEntry e : classic) if (e.bestTimeMs() > 0) sorted.add(e);
        sorted.sort(Comparator.comparingLong(LegendEntry::bestTimeMs));
        replaceObjective(sb, OBJ_FASTEST, "§6Conseil 4 — Temps les plus rapides");
        ScoreboardObjective obj = ensureObjective(sb, OBJ_FASTEST, "§6Conseil 4 — Temps les plus rapides");
        for (LegendEntry e : sorted) {
            int seconds = (int) (e.bestTimeMs() / 1000L);
            // Negative = faster shows at top.
            sb.getOrCreateScore(ScoreHolder.fromName(e.playerName()), obj).setScore(-seconds);
        }
        exportClassicFastest(sorted);
    }

    private static void exportClassicFirst(List<LegendEntry> sorted) {
        writeExport(EXPORT_FIRST, root -> {
            root.addProperty("mode", "classic");
            root.addProperty("kind", "first_finish");
            root.addProperty("count", sorted.size());
            JsonArray entries = new JsonArray();
            for (int i = 0; i < sorted.size(); i++) {
                LegendEntry e = sorted.get(i);
                JsonObject o = new JsonObject();
                o.addProperty("rank", i + 1);
                o.addProperty("playerUuid", e.uuid().toString());
                o.addProperty("playerName", e.playerName());
                o.addProperty("completedAt", e.completedAt());
                entries.add(o);
            }
            root.add("ranking", entries);
        });
    }

    private static void exportClassicFastest(List<LegendEntry> sorted) {
        writeExport(EXPORT_FASTEST, root -> {
            root.addProperty("mode", "classic");
            root.addProperty("kind", "fastest_time");
            root.addProperty("count", sorted.size());
            JsonArray entries = new JsonArray();
            for (int i = 0; i < sorted.size(); i++) {
                LegendEntry e = sorted.get(i);
                JsonObject o = new JsonObject();
                o.addProperty("rank", i + 1);
                o.addProperty("playerUuid", e.uuid().toString());
                o.addProperty("playerName", e.playerName());
                o.addProperty("completedAt", e.completedAt());
                o.addProperty("bestTimeMs", e.bestTimeMs());
                o.addProperty("bestTimeFormatted", ChronoManager.formatDuration(e.bestTimeMs()));
                entries.add(o);
            }
            root.add("ranking", entries);
        });
    }

    // ── RANKED boards ────────────────────────────────────────────────────────

    private static void rebuildRankedChampions(MinecraftServer server, List<Tenure> reverseChrono) {
        Scoreboard sb = server.getScoreboard();
        replaceObjective(sb, OBJ_RANKED_CHAMPIONS, "§bConseil 4 — Champions en titre");
        ScoreboardObjective obj = ensureObjective(sb, OBJ_RANKED_CHAMPIONS, "§bConseil 4 — Champions en titre");
        // Score = startedAt epoch seconds — higher = more recent (top of sidebar).
        for (Tenure t : reverseChrono) {
            int sec = (int) (t.startedAt / 1000L);
            sb.getOrCreateScore(ScoreHolder.fromName(t.championName), obj).setScore(sec);
        }
        exportRankedChampions(reverseChrono);
    }

    private static void exportRankedChampions(List<Tenure> reverseChrono) {
        writeExport(EXPORT_CHAMPS, root -> {
            root.addProperty("mode", "ranked");
            root.addProperty("kind", "champions_history");
            root.addProperty("count", reverseChrono.size());
            JsonArray entries = new JsonArray();
            for (int i = 0; i < reverseChrono.size(); i++) {
                Tenure t = reverseChrono.get(i);
                JsonObject o = new JsonObject();
                o.addProperty("rank", i + 1);
                o.addProperty("playerUuid", t.championUuid.toString());
                o.addProperty("playerName", t.championName);
                o.addProperty("completedAt", t.startedAt);       // align with classic schema
                o.addProperty("startedAt", t.startedAt);
                if (t.endedAt > 0) o.addProperty("endedAt", t.endedAt);
                o.addProperty("isCurrent", t.isCurrent());
                if (t.bestTimeMs > 0) {
                    o.addProperty("bestTimeMs", t.bestTimeMs);
                    o.addProperty("bestTimeFormatted", ChronoManager.formatDuration(t.bestTimeMs));
                }
                entries.add(o);
            }
            root.add("ranking", entries);
        });
    }

    private static void rebuildRankedFailures(MinecraftServer server, Tenure current) {
        Scoreboard sb = server.getScoreboard();
        // Title includes the current champion's name when there is one.
        String title = current != null
                ? "§bConseil 4 — Échecs face à " + current.championName
                : "§bConseil 4 — Échecs face au Maître";
        replaceObjective(sb, OBJ_RANKED_FAILURES, title);
        ScoreboardObjective obj = ensureObjective(sb, OBJ_RANKED_FAILURES, title);
        if (current != null) {
            List<java.util.Map.Entry<String, Tenure.FailRecord>> sorted = new ArrayList<>(current.failedAttempts.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue().count, a.getValue().count));
            for (var e : sorted) {
                sb.getOrCreateScore(ScoreHolder.fromName(e.getValue().playerName), obj).setScore(e.getValue().count);
            }
        }
        exportRankedFailures(current);
    }

    private static void exportRankedFailures(Tenure current) {
        writeExport(EXPORT_FAILURES, root -> {
            root.addProperty("mode", "ranked");
            root.addProperty("kind", "failures_against_current");
            if (current == null) {
                root.addProperty("count", 0);
                root.addProperty("championName", "(personne)");
                root.add("ranking", new JsonArray());
                root.addProperty("dynamicTitle", "Conseil 4 — Aucun champion en titre");
                return;
            }
            root.addProperty("championUuid", current.championUuid.toString());
            root.addProperty("championName", current.championName);
            root.addProperty("tenureStartedAt", current.startedAt);
            // Dynamic title CCS reads to override the configured board title.
            root.addProperty("dynamicTitle", "Échecs face à " + current.championName);
            List<java.util.Map.Entry<String, Tenure.FailRecord>> sorted =
                    new ArrayList<>(current.failedAttempts.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue().count, a.getValue().count));
            root.addProperty("count", sorted.size());
            JsonArray entries = new JsonArray();
            for (int i = 0; i < sorted.size(); i++) {
                var e = sorted.get(i);
                Tenure.FailRecord rec = e.getValue();
                JsonObject o = new JsonObject();
                o.addProperty("rank", i + 1);
                o.addProperty("playerUuid", e.getKey());
                o.addProperty("playerName", rec.playerName);
                o.addProperty("failures", rec.count);
                o.addProperty("lastFailAt", rec.lastFailAt);
                entries.add(o);
            }
            root.add("ranking", entries);
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ScoreboardObjective ensureObjective(Scoreboard sb, String name, String displayName) {
        ScoreboardObjective obj = sb.getNullableObjective(name);
        if (obj != null) return obj;
        return sb.addObjective(name, ScoreboardCriterion.DUMMY,
                Text.literal(displayName).formatted(Formatting.GOLD),
                ScoreboardCriterion.RenderType.INTEGER,
                true, (NumberFormat) StyledNumberFormat.YELLOW);
    }

    /** Drop + recreate so rank reshuffles cleanly (no stale entries). */
    private static void replaceObjective(Scoreboard sb, String name, String displayName) {
        ScoreboardObjective existing = sb.getNullableObjective(name);
        if (existing != null) sb.removeObjective(existing);
        ensureObjective(sb, name, displayName);
    }

    @FunctionalInterface
    private interface ExportBuilder { void build(JsonObject root); }

    private static void writeExport(Path path, ExportBuilder b) {
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("generatedAt", System.currentTimeMillis());
            root.addProperty("source", "cobblecap");
            b.build(root);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) { GSON.toJson(root, w); }
            try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException atomic) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            LOGGER.warn("[CobbleCap] export {} failed: {}", path.getFileName(), e.getMessage());
        }
    }
}
