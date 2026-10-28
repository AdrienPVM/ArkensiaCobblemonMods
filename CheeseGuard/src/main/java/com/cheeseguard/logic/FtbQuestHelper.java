package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper for FTB Quests integration.
 * Parses SNBT files from config/ftbquests/quests/ to list all quests.
 * Reads lang/en_us.snbt for proper display names.
 */
public class FtbQuestHelper {

    public record QuestInfo(String id, String title, String chapterTitle) {}

    private static List<QuestInfo> cachedQuests = null;
    private static long lastCacheTime = 0;
    private static final long CACHE_TTL = 60_000; // 1 minute

    public static List<QuestInfo> getAllQuests() {
        if (cachedQuests != null && System.currentTimeMillis() - lastCacheTime < CACHE_TTL) {
            return cachedQuests;
        }
        cachedQuests = parseAllQuests();
        lastCacheTime = System.currentTimeMillis();
        return cachedQuests;
    }

    public static void invalidateCache() { cachedQuests = null; }

    /**
     * Build a paginated, filtered JSON array of quests for the zone/group quest selector.
     */
    public static JsonObject buildQuestPage(String filter, int page, Set<String> selected) {
        List<QuestInfo> all = getAllQuests();
        String fl = filter.toLowerCase();
        List<QuestInfo> filtered = new ArrayList<>();
        for (QuestInfo q : all) {
            if (!fl.isEmpty() && !q.title.toLowerCase().contains(fl)
                && !q.id.toLowerCase().contains(fl)
                && !q.chapterTitle.toLowerCase().contains(fl)) continue;
            filtered.add(q);
        }
        // Sort: selected first, then chapter + title
        filtered.sort((a, b) -> {
            boolean sa = selected.contains(a.id), sb = selected.contains(b.id);
            if (sa != sb) return sa ? -1 : 1;
            int c = a.chapterTitle.compareToIgnoreCase(b.chapterTitle);
            return c != 0 ? c : a.title.compareToIgnoreCase(b.title);
        });
        return paginate(filtered, filter, page, selected);
    }

    /**
     * Build a paginated quest list for player quest management.
     */
    public static JsonObject buildPlayerQuestPage(String filter, int page) {
        List<QuestInfo> all = getAllQuests();
        String fl = filter.toLowerCase();
        List<QuestInfo> filtered = new ArrayList<>();
        for (QuestInfo q : all) {
            if (!fl.isEmpty() && !q.title.toLowerCase().contains(fl)
                && !q.id.toLowerCase().contains(fl)
                && !q.chapterTitle.toLowerCase().contains(fl)) continue;
            filtered.add(q);
        }
        filtered.sort(Comparator.comparing((QuestInfo q) -> q.chapterTitle, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(q -> q.title, String.CASE_INSENSITIVE_ORDER));
        return paginate(filtered, filter, page, null);
    }

    private static JsonObject paginate(List<QuestInfo> filtered, String filter, int page, Set<String> selected) {
        int pageSize = 50;
        int total = filtered.size();
        int maxPage = Math.max(0, (total - 1) / pageSize);
        page = Math.max(0, Math.min(page, maxPage));
        int from = page * pageSize, to = Math.min(from + pageSize, total);

        JsonArray arr = new JsonArray();
        for (int i = from; i < to; i++) {
            QuestInfo q = filtered.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", q.id);
            o.addProperty("title", q.title);
            o.addProperty("chapter", q.chapterTitle);
            if (selected != null) {
                o.addProperty("selected", selected.contains(q.id));
            }
            arr.add(o);
        }

        JsonObject result = new JsonObject();
        result.add("quests", arr);
        result.addProperty("filter", filter);
        result.addProperty("page", page);
        result.addProperty("maxPage", maxPage);
        result.addProperty("totalFiltered", total);
        if (selected != null) result.addProperty("selectedCount", selected.size());
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SNBT PARSING
    // ═════════════════════════════════════════════════════════════════════════

    /** Pattern for lang entries: quest.HEXID.title: "Value" */
    private static final Pattern LANG_TITLE = Pattern.compile(
        "\\t(quest|chapter)\\.([0-9A-Fa-f]+)\\.title:\\s*\"(.*)\"");
    /** Pattern for quest_desc with &eName&r for fallback title */
    private static final Pattern LANG_DESC_NAME = Pattern.compile(
        "\\tquest\\.([0-9A-Fa-f]+)\\.quest_desc:\\s*\\[\"(.*)\"\\]");
    private static final Pattern COLOR_NAME = Pattern.compile("&e([^&]+)&r");
    /** Quest ID at 3-tab indent inside quests array */
    private static final Pattern QUEST_ID = Pattern.compile(
        "^\\t\\t\\tid: \"([0-9A-F]+)\"$");
    /** Chapter ID at 1-tab indent */
    private static final Pattern CHAPTER_ID = Pattern.compile(
        "^\\tid: \"([0-9A-F]+)\"$");
    /** Item ID pattern: mod:item_name */
    private static final Pattern ITEM_ID = Pattern.compile(
        "id:\\s*\"([a-z][a-z0-9_.-]*:[a-z][a-z0-9_.-]*)\"");
    /** &X color code stripping */
    private static final Pattern COLOR_CODE = Pattern.compile("&[0-9a-fk-or]");

    private static String stripColors(String s) {
        return COLOR_CODE.matcher(s).replaceAll("");
    }

    private static String itemToTitle(String itemId) {
        // "pokeblocks:pokedoll_shiny_beartic" -> "Pokedoll Shiny Beartic"
        String name = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(p.charAt(0)));
                if (p.length() > 1) sb.append(p.substring(1));
            }
        }
        return sb.toString();
    }

    private static List<QuestInfo> parseAllQuests() {
        Path questRoot = findQuestRoot();
        if (questRoot == null) return List.of();

        // 1. Parse lang file for translations
        Map<String, String> langTitles = new HashMap<>();    // "quest.HEX" -> title
        Map<String, String> langDescNames = new HashMap<>();  // "HEX" -> name from desc
        Path langFile = questRoot.resolve("lang").resolve("en_us.snbt");
        if (Files.exists(langFile)) {
            try {
                for (String line : Files.readAllLines(langFile)) {
                    Matcher m = LANG_TITLE.matcher(line);
                    if (m.find()) {
                        String key = m.group(1) + "." + m.group(2);
                        langTitles.put(key, stripColors(m.group(3)));
                    }
                    Matcher md = LANG_DESC_NAME.matcher(line);
                    if (md.find()) {
                        Matcher cn = COLOR_NAME.matcher(md.group(2));
                        if (cn.find()) {
                            langDescNames.put(md.group(1), cn.group(1).trim());
                        }
                    }
                }
            } catch (IOException e) {
                CheeseGuardMod.LOGGER.warn("[CG] Failed to read lang file: {}", e.getMessage());
            }
        }

        CheeseGuardMod.LOGGER.info("[CG] FTB lang loaded: {} titles, {} desc fallbacks",
            langTitles.size(), langDescNames.size());

        // 2. Parse each chapter file
        List<QuestInfo> quests = new ArrayList<>();
        Path chapDir = questRoot.resolve("chapters");
        if (!Files.exists(chapDir)) return quests;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(chapDir, "*.snbt")) {
            for (Path chapterFile : stream) {
                quests.addAll(parseChapterFile(chapterFile, langTitles, langDescNames));
            }
        } catch (IOException e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to read chapters: {}", e.getMessage());
        }

        CheeseGuardMod.LOGGER.info("[CG] Parsed {} FTB quests from {} chapter files", quests.size(),
            countFiles(chapDir));
        return quests;
    }

    private static List<QuestInfo> parseChapterFile(Path file,
            Map<String, String> langTitles, Map<String, String> langDescNames) {
        List<QuestInfo> quests = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file);

            // Find chapter ID and title
            String chapterId = "";
            for (String line : lines) {
                Matcher cm = CHAPTER_ID.matcher(line);
                if (cm.find()) { chapterId = cm.group(1); break; }
            }
            String chapterTitle = langTitles.getOrDefault("chapter." + chapterId,
                file.getFileName().toString().replace(".snbt", "").replace("_", " "));
            // Capitalize first letter of filename fallback
            if (!langTitles.containsKey("chapter." + chapterId) && !chapterTitle.isEmpty()) {
                chapterTitle = Character.toUpperCase(chapterTitle.charAt(0)) + chapterTitle.substring(1);
            }

            // Parse quests
            String currentQuestId = null;
            String currentTaskItem = null;
            boolean inTasks = false;

            for (String line : lines) {
                String s = line.stripTrailing();

                // Detect quest ID (3-tab indent, hex-only value)
                Matcher qm = QUEST_ID.matcher(s);
                if (qm.find()) {
                    // Save previous quest
                    if (currentQuestId != null) {
                        quests.add(makeQuestInfo(currentQuestId, currentTaskItem,
                            chapterTitle, langTitles, langDescNames));
                    }
                    currentQuestId = qm.group(1);
                    currentTaskItem = null;
                    inTasks = false;
                    continue;
                }

                if (currentQuestId == null) continue;

                // Track tasks section
                if (s.contains("tasks:")) {
                    inTasks = true;
                    continue;
                }
                // End of tasks section (next section or end of quest block)
                if (inTasks && (s.matches("^\\t\\t\\t(x|y|rewards|dependencies|depend|shape|size|icon|subtitle):.*")
                    || s.matches("^\\t\\t\\}"))) {
                    inTasks = false;
                    continue;
                }

                // Extract item ID from tasks only (ignore ftbquests:custom_icon etc.)
                if (inTasks && currentTaskItem == null) {
                    Matcher im = ITEM_ID.matcher(s);
                    if (im.find()) {
                        String itemId = im.group(1);
                        // Skip FTB internal items and icon references
                        if (!itemId.startsWith("ftbquests:")) {
                            currentTaskItem = itemId;
                        }
                    }
                }
            }

            // Don't forget last quest
            if (currentQuestId != null) {
                quests.add(makeQuestInfo(currentQuestId, currentTaskItem,
                    chapterTitle, langTitles, langDescNames));
            }

        } catch (IOException e) {
            CheeseGuardMod.LOGGER.debug("[CG] Failed to parse {}: {}", file, e.getMessage());
        }
        return quests;
    }

    private static QuestInfo makeQuestInfo(String questId, String taskItem,
            String chapterTitle, Map<String, String> langTitles, Map<String, String> langDescNames) {
        // Priority: 1) lang title  2) lang desc name  3) task item name  4) hex ID
        String title = langTitles.get("quest." + questId);
        if (title == null || title.isBlank()) {
            title = langDescNames.get(questId);
        }
        if (title == null || title.isBlank()) {
            if (taskItem != null && !taskItem.isBlank()) {
                title = itemToTitle(taskItem);
            }
        }
        if (title == null || title.isBlank()) {
            title = questId;
        }
        return new QuestInfo(questId, title, chapterTitle);
    }

    private static Path findQuestRoot() {
        // Try common locations
        Path[] candidates = {
            Paths.get("config", "ftbquests", "quests"),
            Paths.get(".", "config", "ftbquests", "quests"),
        };
        for (Path p : candidates) {
            if (Files.exists(p) && Files.isDirectory(p)) return p;
        }
        return null;
    }

    private static int countFiles(Path dir) {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.snbt")) {
            int c = 0; for (Path ignored : s) c++;
            return c;
        } catch (IOException e) { return 0; }
    }
}
