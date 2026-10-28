package com.roicheese.eggandcheese.ftb;

import com.roicheese.eggandcheese.data.CollectibleEntry;
import com.roicheese.eggandcheese.data.EggRegistryData;
import com.roicheese.eggandcheese.item.CollectibleItem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.NbtCompound;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reflection-based hook into FTB Quests Java API.
 * All access to FTB Quests classes is via reflection to avoid compile-time
 * dependency and intermediary/yarn mapping issues.
 */
public final class FtbQuestsHook {

    private static final Logger LOGGER = LoggerFactory.getLogger("EggAndCheese/FTB");
    private static boolean available;
    private static boolean initialized;

    // Cached chapter ref to avoid duplicate creation from rapid successive calls
    private static Object cachedChapter;
    private static String cachedChapterFilename;

    // ── Cached reflection handles ────────────────────────────────────────────

    // ServerQuestFile
    private static Field sqfInstanceField;
    private static Method sqfSaveNow;
    private static Method sqfDeleteObject; // deleteObject(long id) — broadcasts to clients

    // BaseQuestFile
    private static Method bqfGetAllChapters;
    private static Method bqfNewId;
    private static Method bqfMarkDirty;
    private static Method bqfRefreshGui;
    private static Method bqfRefreshIDMap;
    private static Method bqfGetDefaultChapterGroup;
    private static Method bqfGetQuest;
    private static Method bqfClearCachedData;

    // QuestObjectBase
    private static Method qobSetRawTitle;
    private static Method qobGetRawTitle;
    private static Method qobOnCreated;
    private static Method qobDeleteSelf;
    private static Method qobGetId;
    private static Method qobSetRawIcon; // optional — may not exist in all FTB versions
    private static Method qobWriteData;  // writeData(CompoundTag, HolderLookup.Provider)

    // Chapter
    private static Constructor<?> chapterCtor4; // (long, BaseQuestFile, ChapterGroup, String)
    private static Method chapterGetQuests;
    private static Method chapterAddQuest;
    private static Method chapterGetFilename;

    // ChapterGroup
    private static Method chapterGroupAddChapter;

    // Quest
    private static Constructor<?> questCtor;
    private static Method questSetRawSubtitle;
    private static Method questSetX;
    private static Method questSetY;
    private static Method questAddTask;
    private static Method questGetTasksAsList;
    private static Field questIconScaleField;  // optional — private double iconScale
    private static Field questShapeField;      // optional — private String shape

    // Task constructors — prefer CustomTask (not clickable) over CheckmarkTask (clickable)
    private static Constructor<?> customTaskCtor;  // preferred: players cannot click
    private static Constructor<?> checkmarkCtor;   // fallback only

    // TeamData
    private static Method teamDataGet; // static get(PlayerEntity)
    private static Method teamDataMarkTaskCompleted;

    // Translation
    private static Method bqfGetTranslationManager;  // BaseQuestFile.getTranslationManager()
    private static Method tmProcessInitialTranslation; // TranslationManager.processInitialTranslation(CompoundTag, QuestObjectBase)
    private static Method tmSendTranslationsToPlayer;  // TranslationManager.sendTranslationsToPlayer(ServerPlayer)

    // Network sync — for broadcasting to clients
    private static Method networkHelperSendToAll; // NetworkHelper.sendToAll(MinecraftServer, CustomPacketPayload)
    private static Method createObjRespCreate;    // CreateObjectResponseMessage.create(QuestObjectBase, CompoundTag, UUID)
    private static Constructor<?> editObjRespCtor;  // EditObjectResponseMessage(QuestObjectBase)

    private FtbQuestsHook() {}

    // ── Availability ─────────────────────────────────────────────────────────

    public static boolean isAvailable() {
        if (!initialized) init();
        return available;
    }

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;

        if (!FabricLoader.getInstance().isModLoaded("ftbquests")) {
            LOGGER.info("[EAC-FTB] FTB Quests not present — hook disabled");
            return;
        }

        try {
            Class<?> sqfClass = Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile");
            Class<?> bqfClass = Class.forName("dev.ftb.mods.ftbquests.quest.BaseQuestFile");
            Class<?> chapterClass = Class.forName("dev.ftb.mods.ftbquests.quest.Chapter");
            Class<?> chapterGroupClass = Class.forName("dev.ftb.mods.ftbquests.quest.ChapterGroup");
            Class<?> questClass = Class.forName("dev.ftb.mods.ftbquests.quest.Quest");
            Class<?> taskClass = Class.forName("dev.ftb.mods.ftbquests.quest.task.Task");
            Class<?> checkmarkClass = Class.forName("dev.ftb.mods.ftbquests.quest.task.CheckmarkTask");
            Class<?> teamDataClass = Class.forName("dev.ftb.mods.ftbquests.quest.TeamData");
            Class<?> qobClass = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObjectBase");

            // ServerQuestFile
            sqfInstanceField = sqfClass.getField("INSTANCE");
            sqfSaveNow = sqfClass.getMethod("saveNow");
            sqfDeleteObject = sqfClass.getMethod("deleteObject", long.class);

            // BaseQuestFile
            bqfGetAllChapters = bqfClass.getMethod("getAllChapters");
            bqfNewId = bqfClass.getMethod("newID");
            bqfMarkDirty = bqfClass.getMethod("markDirty");
            bqfRefreshGui = bqfClass.getMethod("refreshGui");
            bqfRefreshIDMap = bqfClass.getMethod("refreshIDMap");
            bqfGetDefaultChapterGroup = bqfClass.getMethod("getDefaultChapterGroup");
            bqfGetQuest = bqfClass.getMethod("getQuest", long.class);
            bqfClearCachedData = bqfClass.getMethod("clearCachedData");

            // QuestObjectBase
            qobSetRawTitle = qobClass.getMethod("setRawTitle", String.class);
            qobGetRawTitle = qobClass.getMethod("getRawTitle");
            qobOnCreated = qobClass.getMethod("onCreated");
            qobDeleteSelf = qobClass.getMethod("deleteSelf");
            qobGetId = qobClass.getMethod("getId");

            // Chapter (4-arg: long, BaseQuestFile, ChapterGroup, String filename)
            chapterCtor4 = chapterClass.getConstructor(long.class, bqfClass, chapterGroupClass, String.class);
            chapterGetQuests = chapterClass.getMethod("getQuests");
            chapterAddQuest = chapterClass.getMethod("addQuest", questClass);
            chapterGetFilename = chapterClass.getMethod("getFilename");

            // ChapterGroup
            chapterGroupAddChapter = chapterGroupClass.getMethod("addChapter", chapterClass);

            // Quest
            questCtor = questClass.getConstructor(long.class, chapterClass);
            questSetRawSubtitle = questClass.getMethod("setRawSubtitle", String.class);
            questSetX = questClass.getMethod("setX", double.class);
            questSetY = questClass.getMethod("setY", double.class);
            questAddTask = questClass.getMethod("addTask", taskClass);
            questGetTasksAsList = questClass.getMethod("getTasksAsList");

            // Quest appearance fields (private, optional)
            try {
                questIconScaleField = questClass.getDeclaredField("iconScale");
                questIconScaleField.setAccessible(true);
            } catch (NoSuchFieldException ignored) {}
            try {
                questShapeField = questClass.getDeclaredField("shape");
                questShapeField.setAccessible(true);
            } catch (NoSuchFieldException ignored) {}

            // CheckmarkTask (fallback)
            checkmarkCtor = checkmarkClass.getConstructor(long.class, questClass);

            // CustomTask — preferred: cannot be clicked by players, only completed via API
            try {
                Class<?> customTaskClass = Class.forName("dev.ftb.mods.ftbquests.quest.task.CustomTask");
                customTaskCtor = customTaskClass.getConstructor(long.class, questClass);
                LOGGER.info("[EAC-FTB] CustomTask available — egg quests will not be clickable by players");
            } catch (ClassNotFoundException e) {
                LOGGER.info("[EAC-FTB] CustomTask not found — falling back to CheckmarkTask (players can click)");
            }

            // TeamData — find static get(PlayerEntity)
            for (Method m : teamDataClass.getDeclaredMethods()) {
                if ("get".equals(m.getName()) && Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1) {
                    teamDataGet = m;
                    break;
                }
            }
            teamDataMarkTaskCompleted = teamDataClass.getMethod("markTaskCompleted", taskClass);

            if (teamDataGet == null) throw new NoSuchMethodException("TeamData.get(PlayerEntity) not found");

            // Optional: setRawIcon(ItemStack) for quest icons
            try {
                for (Method m : qobClass.getMethods()) {
                    if ("setRawIcon".equals(m.getName()) && m.getParameterCount() == 1) {
                        qobSetRawIcon = m;
                        break;
                    }
                }
                if (qobSetRawIcon != null) LOGGER.info("[EAC-FTB] setRawIcon available — quest icons enabled");
            } catch (Exception ignored) {}

            // Translation manager — needed to sync titles to clients
            try {
                bqfGetTranslationManager = bqfClass.getMethod("getTranslationManager");
                Class<?> tmClass = Class.forName("dev.ftb.mods.ftbquests.quest.translation.TranslationManager");
                // processInitialTranslation(CompoundTag extra, QuestObjectBase object)
                // Use NbtCompound.class directly — Fabric remaps it at runtime
                tmProcessInitialTranslation = tmClass.getMethod("processInitialTranslation",
                    NbtCompound.class, qobClass);
                // sendTranslationsToPlayer(ServerPlayer)
                // Use ServerPlayerEntity.class directly — Fabric remaps it at runtime
                tmSendTranslationsToPlayer = tmClass.getMethod("sendTranslationsToPlayer",
                    ServerPlayerEntity.class);
                LOGGER.info("[EAC-FTB] TranslationManager available — title sync enabled");
            } catch (Exception tmEx) {
                LOGGER.info("[EAC-FTB] TranslationManager not available ({})", tmEx.getMessage());
            }

            // Network sync — for broadcasting changes to connected clients
            try {
                Class<?> networkHelperClass = Class.forName("dev.architectury.networking.NetworkManager");
                // Find sendToPlayers(Iterable, T) — the generic version with 2 params
                // The second param type varies by mappings, so match by name + param count
                for (Method m : networkHelperClass.getMethods()) {
                    if ("sendToPlayers".equals(m.getName()) && m.getParameterCount() == 2
                            && m.getParameterTypes()[0] == Iterable.class) {
                        networkHelperSendToAll = m;
                        break;
                    }
                }

                Class<?> createRespClass = Class.forName("dev.ftb.mods.ftbquests.net.CreateObjectResponseMessage");
                // CreateObjectResponseMessage.create(...) — find best static factory
                for (Method m : createRespClass.getMethods()) {
                    if ("create".equals(m.getName()) && Modifier.isStatic(m.getModifiers())) {
                        if (m.getParameterCount() == 3) {
                            createObjRespCreate = m; // prefer 3-arg version
                            break;
                        }
                        if (m.getParameterCount() == 2 && createObjRespCreate == null) {
                            createObjRespCreate = m;
                        }
                    }
                }

                Class<?> editRespClass = Class.forName("dev.ftb.mods.ftbquests.net.EditObjectResponseMessage");
                // EditObjectResponseMessage is a record with constructor(QuestObjectBase)
                editObjRespCtor = editRespClass.getConstructor(qobClass);

                if (networkHelperSendToAll != null && createObjRespCreate != null) {
                    LOGGER.info("[EAC-FTB] Network sync available — live client updates enabled (sendToPlayers={}, create={}, editCtor={})",
                        networkHelperSendToAll != null, createObjRespCreate != null, editObjRespCtor != null);
                } else {
                    LOGGER.info("[EAC-FTB] Network sync partially available (sendToPlayers={}, createResp={}, editResp={})",
                        networkHelperSendToAll != null, createObjRespCreate != null, editObjRespCtor != null);
                }
            } catch (Exception netEx) {
                LOGGER.info("[EAC-FTB] Network sync not available — clients will need relog to see changes ({})", netEx.getMessage());
            }

            available = true;
            LOGGER.info("[EAC-FTB] FTB Quests hook initialized — Java API ready");

        } catch (Exception e) {
            LOGGER.error("[EAC-FTB] Failed to initialize FTB Quests hook", e);
            available = false;
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /** Create a task for a quest — prefers CustomTask (not clickable) over CheckmarkTask. */
    private static Object createTask(long tid, Object quest) throws Exception {
        Constructor<?> ctor = customTaskCtor != null ? customTaskCtor : checkmarkCtor;
        return ctor.newInstance(tid, quest);
    }

    /** Apply egg appearance to a quest: icon scaling 2x, shape "none". */
    private static void applyQuestAppearance(Object quest) {
        try {
            if (questIconScaleField != null) questIconScaleField.setDouble(quest, 2.0);
            if (questShapeField != null) questShapeField.set(quest, "none");
        } catch (Exception e) {
            LOGGER.warn("[EAC-FTB] Failed to set quest appearance", e);
        }
    }

    private static Object getQuestFile() throws Exception {
        Object sqf = sqfInstanceField.get(null);
        if (sqf == null) throw new IllegalStateException("ServerQuestFile.INSTANCE is null (server not ready?)");
        return sqf;
    }

    private static long newId(Object file) throws Exception {
        return (long) bqfNewId.invoke(file);
    }

    private static void save(Object file) throws Exception {
        bqfMarkDirty.invoke(file);
        sqfSaveNow.invoke(file);
        bqfRefreshGui.invoke(file);
    }

    /**
     * Broadcast a CreateObjectResponseMessage to all clients for a newly created object.
     * This makes the quest/chapter/task appear immediately without relog.
     */
    private static void broadcastCreate(MinecraftServer server, Object questObject, String title, String subtitle) {
        if (networkHelperSendToAll == null || createObjRespCreate == null || server == null) return;
        try {
            // Build extra tag with translations so the client receives the title
            NbtCompound extra = new NbtCompound();
            extra.putString("locale", "en_us");
            NbtCompound translate = new NbtCompound();
            if (title != null && !title.isEmpty()) translate.putString("title", title);
            if (subtitle != null && !subtitle.isEmpty()) translate.putString("subtitle", subtitle);
            extra.put("translate", translate);

            // Register translations server-side
            if (tmProcessInitialTranslation != null && bqfGetTranslationManager != null) {
                try {
                    Object file = getQuestFile();
                    Object tm = bqfGetTranslationManager.invoke(file);
                    tmProcessInitialTranslation.invoke(tm, extra, questObject);
                } catch (Exception ignored) {}
            }

            Object message;
            if (createObjRespCreate.getParameterCount() == 3) {
                message = createObjRespCreate.invoke(null, questObject, extra, null);
            } else {
                message = createObjRespCreate.invoke(null, questObject, extra);
            }
            networkHelperSendToAll.invoke(null, server.getPlayerManager().getPlayerList(), message);

            // Also send full translation table to all players
            sendTranslationsToAllPlayers(server);
        } catch (Exception e) {
            LOGGER.debug("[EAC-FTB] Failed to broadcast create: {}", e.getMessage());
        }
    }

    /** Convenience overload without title/subtitle (for chapters, tasks). */
    private static void broadcastCreate(MinecraftServer server, Object questObject) {
        broadcastCreate(server, questObject, null, null);
    }

    /** Send translation tables to all connected players so they see updated titles. */
    private static void sendTranslationsToAllPlayers(MinecraftServer server) {
        if (tmSendTranslationsToPlayer == null || bqfGetTranslationManager == null || server == null) return;
        try {
            Object file = getQuestFile();
            Object tm = bqfGetTranslationManager.invoke(file);
            for (var player : server.getPlayerManager().getPlayerList()) {
                tmSendTranslationsToPlayer.invoke(tm, player);
            }
        } catch (Exception e) {
            LOGGER.debug("[EAC-FTB] Failed to send translations: {}", e.getMessage());
        }
    }

    /**
     * Broadcast an EditObjectResponseMessage to all clients for an updated object.
     */
    private static void broadcastEdit(MinecraftServer server, Object questObject) {
        if (networkHelperSendToAll == null || editObjRespCtor == null || server == null) return;
        try {
            Object message = editObjRespCtor.newInstance(questObject);
            networkHelperSendToAll.invoke(null, server.getPlayerManager().getPlayerList(), message);
            // Also resend translations so updated titles appear immediately
            sendTranslationsToAllPlayers(server);
        } catch (Exception e) {
            LOGGER.debug("[EAC-FTB] Failed to broadcast edit: {}", e.getMessage());
        }
    }

    /** Find a chapter by its filename, or null. */
    @SuppressWarnings("unchecked")
    private static Object findChapter(Object file, String filename) throws Exception {
        List<Object> chapters = (List<Object>) bqfGetAllChapters.invoke(file);
        for (Object ch : chapters) {
            String fn = (String) chapterGetFilename.invoke(ch);
            if (filename.equalsIgnoreCase(fn)) return ch;
        }
        return null;
    }

    /** Find or create the egg chapter, with caching and duplicate merging. */
    @SuppressWarnings("unchecked")
    private static synchronized Object findOrCreateChapter(Object file, String chapterName, MinecraftServer server) throws Exception {
        String filename = chapterName.toLowerCase().replaceAll("[^a-z0-9_]", "_");

        // Fast path: return cached chapter if filename matches
        if (cachedChapter != null && filename.equals(cachedChapterFilename)) {
            return cachedChapter;
        }

        // Search all chapters — collect ALL matches (to detect duplicates)
        List<Object> chapters = (List<Object>) bqfGetAllChapters.invoke(file);
        List<Object> matches = new ArrayList<>();
        for (Object ch : chapters) {
            String fn = (String) chapterGetFilename.invoke(ch);
            if (filename.equalsIgnoreCase(fn)) matches.add(ch);
        }

        Object chapter;
        if (matches.size() == 1) {
            chapter = matches.get(0);
        } else if (matches.size() > 1) {
            // Deduplicate: keep first, merge quests from others, delete extras
            chapter = matches.get(0);
            for (int i = 1; i < matches.size(); i++) {
                Object dup = matches.get(i);
                long dupId = (long) qobGetId.invoke(dup);
                List<Object> quests = (List<Object>) chapterGetQuests.invoke(dup);
                for (Object q : new ArrayList<>(quests)) {
                    chapterAddQuest.invoke(chapter, q);
                }
                sqfDeleteObject.invoke(file, dupId);
            }
            bqfRefreshIDMap.invoke(file);
            LOGGER.info("[EAC-FTB] Merged {} duplicate chapters into one '{}'", matches.size() - 1, chapterName);
        } else {
            // Create new chapter
            long cid = newId(file);
            Object defaultGroup = bqfGetDefaultChapterGroup.invoke(file);
            chapter = chapterCtor4.newInstance(cid, file, defaultGroup, filename);
            qobSetRawTitle.invoke(chapter, chapterName);
            // NOTE: do NOT call chapterGroupAddChapter here — Chapter.onCreated()
            // internally calls group.addChapter(this), so calling both causes duplicates
            qobOnCreated.invoke(chapter);
            bqfRefreshIDMap.invoke(file);
            bqfClearCachedData.invoke(file);
            bqfMarkDirty.invoke(file);
            broadcastCreate(server, chapter);
            LOGGER.info("[EAC-FTB] Created chapter '{}' (id={})", chapterName, cid);
        }

        // Cache the result
        cachedChapter = chapter;
        cachedChapterFilename = filename;
        return chapter;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Clear cached chapter reference — call when chapter name changes or on cleanup. */
    public static void clearChapterCache() {
        cachedChapter = null;
        cachedChapterFilename = null;
    }

    /**
     * Ensure a quest exists for the given entry. If ftbQuestId is 0 and FTB is enabled,
     * create the quest now. Returns the (possibly new) quest ID.
     */
    public static long ensureQuestExists(String chapterName, CollectibleEntry entry) {
        return ensureQuestExists(chapterName, entry, null);
    }

    public static long ensureQuestExists(String chapterName, CollectibleEntry entry, MinecraftServer server) {
        if (entry.getFtbQuestId() != 0) return entry.getFtbQuestId();
        return createQuest(chapterName, entry, server);
    }

    /**
     * Create a quest for an egg in the FTB Quests chapter.
     * @return the quest's long ID, or 0 if creation failed
     */
    public static long createQuest(String chapterName, CollectibleEntry entry) {
        return createQuest(chapterName, entry, null);
    }

    /**
     * Create a quest for an egg in the FTB Quests chapter.
     * @param server if non-null, broadcasts changes to connected clients in real-time
     * @return the quest's long ID, or 0 if creation failed
     */
    public static long createQuest(String chapterName, CollectibleEntry entry, MinecraftServer server) {
        if (!isAvailable()) return 0;
        try {
            Object file = getQuestFile();
            Object chapter = findOrCreateChapter(file, chapterName, server);

            // Calculate next X position based on existing quest count
            @SuppressWarnings("unchecked")
            List<Object> existingQuests = (List<Object>) chapterGetQuests.invoke(chapter);
            double xPos = existingQuests.size() * 2.5;

            // Create quest
            long qid = newId(file);
            Object quest = questCtor.newInstance(qid, chapter);
            String title = (entry.getTitle() != null && !entry.getTitle().isBlank())
                ? entry.getTitle() : "Trouver\u00a0: " + entry.getId();
            String subtitle = (entry.getSubtitle() != null) ? entry.getSubtitle() : "";
            qobSetRawTitle.invoke(quest, title);
            questSetRawSubtitle.invoke(quest, subtitle);
            questSetX.invoke(quest, xPos);
            questSetY.invoke(quest, 0.0);

            // Set quest icon and appearance (scaling 2x, shape "none")
            if (qobSetRawIcon != null) {
                try {
                    ItemStack icon = CollectibleItem.create(entry.getType(), entry.getDna());
                    qobSetRawIcon.invoke(quest, icon);
                } catch (Exception iconEx) {
                    LOGGER.warn("[EAC-FTB] Failed to set quest icon for '{}'", entry.getId(), iconEx);
                }
            }
            applyQuestAppearance(quest);

            // Create task (CustomTask if available, else CheckmarkTask fallback)
            long tid = newId(file);
            Object task = createTask(tid, quest);
            qobSetRawTitle.invoke(task, title);
            // Let onCreated() handle registration (avoids duplicates)
            qobOnCreated.invoke(quest);
            qobOnCreated.invoke(task);
            bqfRefreshIDMap.invoke(file);
            bqfClearCachedData.invoke(file);
            bqfMarkDirty.invoke(file);

            // Broadcast to clients so they see the new quest immediately
            broadcastCreate(server, quest, title, subtitle);
            broadcastCreate(server, task, title, null);

            save(file);
            LOGGER.info("[EAC-FTB] Created quest '{}' for egg '{}' (questId={})", title, entry.getId(), qid);
            return qid;

        } catch (Exception e) {
            LOGGER.error("[EAC-FTB] Failed to create quest for egg '{}'", entry.getId(), e);
            return 0;
        }
    }

    /**
     * Update the title and subtitle of an existing FTB quest linked to an egg.
     */
    public static void updateQuest(long questId, String title, String subtitle) {
        updateQuest(questId, title, subtitle, null);
    }

    /**
     * Update the title and subtitle of an existing FTB quest linked to an egg.
     * @param server if non-null, broadcasts the change to connected clients in real-time
     */
    public static void updateQuest(long questId, String title, String subtitle, MinecraftServer server) {
        if (!isAvailable() || questId == 0) return;
        try {
            Object file = getQuestFile();
            Object quest = bqfGetQuest.invoke(file, questId);
            if (quest == null) {
                LOGGER.warn("[EAC-FTB] Quest {} not found — cannot update", questId);
                return;
            }
            qobSetRawTitle.invoke(quest, title != null && !title.isBlank() ? title : "Trouver un oeuf");
            questSetRawSubtitle.invoke(quest, subtitle != null ? subtitle : "");

            // Also update the task title
            @SuppressWarnings("unchecked")
            List<Object> tasks = (List<Object>) questGetTasksAsList.invoke(quest);
            if (!tasks.isEmpty()) {
                qobSetRawTitle.invoke(tasks.get(0), title != null && !title.isBlank() ? title : "Trouver un oeuf");
                broadcastEdit(server, tasks.get(0));
            }

            // Broadcast edit to clients
            broadcastEdit(server, quest);

            save(file);
            LOGGER.info("[EAC-FTB] Updated quest {} title='{}'", questId, title);

        } catch (Exception e) {
            LOGGER.error("[EAC-FTB] Failed to update quest {}", questId, e);
        }
    }

    /**
     * Delete a quest from FTB Quests.
     * Uses ServerQuestFile.deleteObject() which broadcasts DeleteObjectResponseMessage to all clients.
     */
    public static void deleteQuest(long questId) {
        if (!isAvailable() || questId == 0) return;
        try {
            Object file = getQuestFile();
            Object quest = bqfGetQuest.invoke(file, questId);
            if (quest == null) {
                LOGGER.debug("[EAC-FTB] Quest {} not found — already deleted?", questId);
                return;
            }
            // Use ServerQuestFile.deleteObject() — it handles:
            // deleteChildren, deleteSelf, refreshIDMap, markDirty, file cleanup,
            // AND broadcasts DeleteObjectResponseMessage to all clients
            sqfDeleteObject.invoke(file, questId);
            sqfSaveNow.invoke(file);
            LOGGER.info("[EAC-FTB] Deleted quest {}", questId);

        } catch (Exception e) {
            LOGGER.error("[EAC-FTB] Failed to delete quest {}", questId, e);
        }
    }

    /**
     * Complete the task for a player when they discover an egg.
     * Works with both CustomTask and CheckmarkTask via TeamData.markTaskCompleted().
     */
    public static void completeForPlayer(long questId, ServerPlayerEntity player) {
        if (!isAvailable() || questId == 0) return;
        try {
            Object file = getQuestFile();
            Object quest = bqfGetQuest.invoke(file, questId);
            if (quest == null) {
                LOGGER.warn("[EAC-FTB] Quest {} not found — cannot complete for {}", questId, player.getName().getString());
                return;
            }

            @SuppressWarnings("unchecked")
            List<Object> tasks = (List<Object>) questGetTasksAsList.invoke(quest);
            if (tasks.isEmpty()) {
                LOGGER.warn("[EAC-FTB] Quest {} has no tasks", questId);
                return;
            }

            Object teamData = teamDataGet.invoke(null, player);
            if (teamData == null) {
                LOGGER.warn("[EAC-FTB] No TeamData for player {}", player.getName().getString());
                return;
            }

            Object task = tasks.get(0);
            teamDataMarkTaskCompleted.invoke(teamData, task);

            save(file);
            LOGGER.info("[EAC-FTB] Completed quest {} for player '{}'", questId, player.getName().getString());

        } catch (Exception e) {
            LOGGER.error("[EAC-FTB] Failed to complete quest {} for {}", questId, player.getName().getString(), e);
        }
    }

    /**
     * Full resync: delete all linked quests, recreate from current registry state.
     * Called when FTB settings change, or manually from the dashboard "Force Sync" button.
     */
    public static void syncAll(MinecraftServer server, EggRegistryData registry) {
        if (!isAvailable()) return;
        if (!registry.isFtbQuestsEnabled()) return;

        String chapterName = registry.getFtbQuestsChapter();
        LOGGER.info("[EAC-FTB] Full sync starting for chapter '{}'...", chapterName);

        // Invalidate cache — chapter will be re-fetched after deleting old quests
        clearChapterCache();

        try {
            Object file = getQuestFile();

            // Delete existing linked quests using deleteObject (broadcasts to clients)
            for (CollectibleEntry entry : registry.getAllEntries()) {
                if (entry.getFtbQuestId() != 0) {
                    try {
                        Object quest = bqfGetQuest.invoke(file, entry.getFtbQuestId());
                        if (quest != null) {
                            sqfDeleteObject.invoke(file, entry.getFtbQuestId());
                        }
                    } catch (Exception ignored) {}
                    entry.setFtbQuestId(0);
                }
            }

            // Save intermediate state
            bqfRefreshIDMap.invoke(file);
            bqfMarkDirty.invoke(file);

            // Recreate all quests
            Object chapter = findOrCreateChapter(file, chapterName, server);
            int idx = 0;
            for (CollectibleEntry entry : registry.getEntriesSorted()) {
                long qid = newId(file);
                Object quest = questCtor.newInstance(qid, chapter);
                String title = (entry.getTitle() != null && !entry.getTitle().isBlank())
                    ? entry.getTitle() : "Trouver\u00a0: " + entry.getId();
                String subtitle = (entry.getSubtitle() != null) ? entry.getSubtitle() : "";
                qobSetRawTitle.invoke(quest, title);
                questSetRawSubtitle.invoke(quest, subtitle);
                questSetX.invoke(quest, idx * 2.5);
                questSetY.invoke(quest, 0.0);

                // Set quest icon and appearance
                if (qobSetRawIcon != null) {
                    try {
                        ItemStack icon = CollectibleItem.create(entry.getType(), entry.getDna());
                        qobSetRawIcon.invoke(quest, icon);
                    } catch (Exception ignored) {}
                }
                applyQuestAppearance(quest);

                long tid = newId(file);
                Object task = createTask(tid, quest);
                qobSetRawTitle.invoke(task, title);
                // Let onCreated() handle registration (avoids duplicates)
                qobOnCreated.invoke(quest);
                qobOnCreated.invoke(task);
                bqfRefreshIDMap.invoke(file);
                bqfClearCachedData.invoke(file);
                bqfMarkDirty.invoke(file);

                // Broadcast to clients
                broadcastCreate(server, quest, title, subtitle);
                broadcastCreate(server, task, title, null);

                entry.setFtbQuestId(qid);
                idx++;
            }

            save(file);
            registry.markDirty();
            LOGGER.info("[EAC-FTB] Full sync complete — {} quests created", idx);

        } catch (Exception e) {
            LOGGER.error("[EAC-FTB] Full sync failed", e);
        }
    }
}
