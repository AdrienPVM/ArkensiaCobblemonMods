package com.cobblecap.logic;

import com.cobblecap.CobbleCapMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rewrites the RCT trainer datapack file (team JSON + skin PNG) for the
 * "champion_regis_challenge" so it reflects whichever player is the current
 * Pokémon League Master. Triggers a /reload to apply changes.
 *
 * Safety against mid-battle morph:
 *   - {@link #request(ServerPlayerEntity)} stores a pending morph
 *   - {@link #tick(MinecraftServer)} is called every 20 ticks (1s) — when no
 *     active Cobblemon battle is detected, the pending morph is flushed
 *   - in-progress battles keep using the team they loaded at start, so no
 *     "team switch mid-fight" occurs even if /reload happened to fire
 */
public class RctTrainerMorpher {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    private static final String TRAINER_ID = "champion_regis_challenge";

    /** Configurable from CapConfig — defaults written below. */
    public static String trainerJsonRelPath  = "data/rctmod/trainers/" + TRAINER_ID + ".json";
    public static String trainerSkinRelPath  = "assets/rctmod/textures/trainers/single/" + TRAINER_ID + ".png";
    public static String datapackName        = "CacademyMons";

    private final AtomicReference<PendingMorph> pending = new AtomicReference<>(null);
    private int tickCounter = 0;

    private record PendingMorph(UUID uuid, String playerName, long bestTimeMs,
                                  List<TeamSnapshot> team, long queuedAt) {}

    public record TeamSnapshot(String species, int level, boolean shiny, String form,
                                 List<String> aspects, String ball, String nature,
                                 String gender, String nickname) {}

    /**
     * Capture the player's party + queue a morph. The actual file rewrite +
     * /reload happens later when no Cobblemon battle is active.
     */
    public void request(ServerPlayerEntity player, long bestTimeMs) {
        List<TeamSnapshot> snap = snapshotTeam(player);
        PendingMorph pm = new PendingMorph(player.getUuid(), player.getName().getString(),
                bestTimeMs, snap, System.currentTimeMillis());
        pending.set(pm);
        LOGGER.info("[CobbleCap] Morph queued for {} (team size={}, will flush when no battle is active)",
                pm.playerName, snap.size());
    }

    /**
     * Per-tick check (every 20 ticks).
     * <p>
     * Previous implementation deferred while ANY Cobblemon battle was active —
     * but on a busy server (30+ players) the active count is virtually never
     * zero, so the morph was deferred forever. RCT loads a trainer's team
     * snapshot at battle start; modifying the JSON mid-fight does NOT affect
     * ongoing battles, only new ones. So the deferral was over-cautious.
     * <p>
     * Now: flush immediately at the next 1s tick boundary. The morph affects
     * only future Régis battles, never one in progress.
     */
    public void tick(MinecraftServer server) {
        if (++tickCounter % 20 != 0) return;
        PendingMorph pm = pending.get();
        if (pm == null) return;
        if (pending.compareAndSet(pm, null)) {
            try {
                flushMorph(server, pm);
            } catch (Exception e) {
                LOGGER.error("[CobbleCap] flushMorph failed: {}", e.getMessage(), e);
            }
        }
    }

    /** Backup root: stored next to the mod config so it survives world rebuilds. */
    private static final Path BACKUP_DIR = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("cobblecap_regis_backup");
    private static final Path BACKUP_JSON = BACKUP_DIR.resolve("champion_regis_challenge.json");
    private static final Path BACKUP_SKIN = BACKUP_DIR.resolve("champion_regis_challenge.png");

    /**
     * Snapshot the original trainer JSON + skin on the very first morph (or
     * whenever {@link #BACKUP_JSON} is missing). Safe to call repeatedly —
     * existing backups are left untouched so the FIRST snapshot is always the
     * pristine pre-morph state.
     */
    public static synchronized void ensureBackup(MinecraftServer server) {
        try {
            Path dpRoot = locateDatapackRoot(server);
            if (dpRoot == null) return;
            Files.createDirectories(BACKUP_DIR);
            Path liveJson = dpRoot.resolve(trainerJsonRelPath);
            Path liveSkin = dpRoot.resolve(trainerSkinRelPath);
            if (!Files.exists(BACKUP_JSON) && Files.isRegularFile(liveJson)) {
                // Skip if the current JSON already has the morph marker (we'd
                // backup a contaminated state otherwise — defensive guard).
                String content = Files.readString(liveJson);
                if (!content.contains("__cobblecap_champion")) {
                    Files.copy(liveJson, BACKUP_JSON);
                    LOGGER.info("[CobbleCap] Régis original JSON backed up to {}", BACKUP_JSON);
                } else {
                    LOGGER.warn("[CobbleCap] Live trainer JSON already morphed — backup skipped (no pristine copy available)");
                }
            }
            if (!Files.exists(BACKUP_SKIN) && Files.isRegularFile(liveSkin)) {
                Files.copy(liveSkin, BACKUP_SKIN);
                LOGGER.info("[CobbleCap] Régis original skin backed up to {}", BACKUP_SKIN);
            }
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] ensureBackup failed: {}", e.getMessage());
        }
    }

    /**
     * Restore Régis to his original state from the backup. Returns a short
     * status string for command feedback.
     */
    public static String restoreOriginal(MinecraftServer server) {
        Path dpRoot = locateDatapackRoot(server);
        if (dpRoot == null) return "datapack root not found";
        boolean restoredJson = false;
        boolean restoredSkin = false;
        try {
            if (Files.exists(BACKUP_JSON)) {
                Files.copy(BACKUP_JSON, dpRoot.resolve(trainerJsonRelPath),
                        StandardCopyOption.REPLACE_EXISTING);
                restoredJson = true;
            }
            if (Files.exists(BACKUP_SKIN)) {
                Files.copy(BACKUP_SKIN, dpRoot.resolve(trainerSkinRelPath),
                        StandardCopyOption.REPLACE_EXISTING);
                restoredSkin = true;
            }
        } catch (Exception e) {
            return "restore failed: " + e.getMessage();
        }
        if (!restoredJson && !restoredSkin) return "no backup found (was Régis ever morphed?)";
        final boolean rj = restoredJson;
        final boolean rs = restoredSkin;
        // Trigger reload so RCT picks up the original immediately
        server.execute(() -> {
            try {
                // rctmod has no reload command — call its real reload entry by reflection
                // (re-reads trainer JSONs from disk, no vanilla /reload datapack rescan).
                boolean ok = RctReloadHook.reloadTrainers(server);
                LOGGER.info("[CobbleCap] Régis original restored — JSON={}, skin={}, rctmod reload ok={}", rj, rs, ok);
                CobbleCapMod.INSTANCE.getStatueManager().refreshCurrentChampionDioramas(server);
            } catch (Exception e) {
                LOGGER.warn("[CobbleCap] Restore reload failed: {}", e.getMessage());
            }
        });
        return "restored (json=" + rj + ", skin=" + rs + ")";
    }

    /** Apply the pending morph: write JSON + download skin async + /reload. */
    private void flushMorph(MinecraftServer server, PendingMorph pm) {
        Path dpRoot = locateDatapackRoot(server);
        if (dpRoot == null) {
            LOGGER.warn("[CobbleCap] Datapack '{}' not found — morph aborted", datapackName);
            return;
        }

        // CRITICAL: backup the original BEFORE we overwrite — first morph only
        ensureBackup(server);

        // 1. Rewrite team JSON in-place (preserve other fields by patching)
        Path teamJson = dpRoot.resolve(trainerJsonRelPath);
        try {
            JsonObject root = loadOrTemplate(teamJson);
            JsonArray team = new JsonArray();
            for (TeamSnapshot t : pm.team) team.add(buildPokemonJson(t));
            root.add("team", team);
            // Best-time annotation for future tooling
            JsonObject champ = new JsonObject();
            champ.addProperty("playerUuid", pm.uuid.toString());
            champ.addProperty("playerName", pm.playerName);
            if (pm.bestTimeMs > 0) champ.addProperty("bestTimeMs", pm.bestTimeMs);
            champ.addProperty("morphedAt", System.currentTimeMillis());
            root.add("__cobblecap_champion", champ);
            writeAtomic(teamJson, GSON.toJson(root));
            LOGGER.info("[CobbleCap] Trainer JSON rewritten: {} ({} Pokémon)", teamJson, pm.team.size());
        } catch (Exception e) {
            LOGGER.error("[CobbleCap] Failed to rewrite trainer JSON: {}", e.getMessage(), e);
            return;
        }

        // 2. Skip skin PNG write — the previous implementation wrote to
        // datapacks/CacademyMons/assets/... which Minecraft never loads (assets
        // only come from resourcepacks). Writing to resourcepacks/ would
        // require AutoModpack regeneration + client redownload, which is
        // disruptive. The runtime "doppelganger overlay" approach (Option A
        // in the 2026-05-17 discussion) is the proper fix when implemented.
        // Until then: team-only morph, original Régis skin preserved.
        LOGGER.info("[CobbleCap] Skin morph skipped (architectural limitation — see RctTrainerMorpher comment)");

        // Persist this morphed version as the "ranked" snapshot so RegisModeManager
        // can re-apply it later when a player picks Compétitif mode.
        try { RegisModeManager.saveRankedSnapshot(server); }
        catch (Exception e) { LOGGER.debug("[CobbleCap] saveRankedSnapshot post-flush failed: {}", e.getMessage()); }

        // Refresh every per-dim Régis clone whose owner is in Compétitif mode so
        // other players still in their conseil4_xxx dim see the new champion's
        // team without having to leave and re-enter. Ongoing battles are
        // unaffected (rctmod caches the team at battle start).
        try { CobbleCapMod.INSTANCE.conseil4Cloner.refreshAllRankedRegis(server); }
        catch (Exception e) { LOGGER.debug("[CobbleCap] refreshAllRankedRegis post-flush failed: {}", e.getMessage()); }

        // Refresh every in-arena Régis SKIN overlay so the doppel reflects the
        // new champion's player skin (visual mirror of the team refresh above).
        try { CobbleCapMod.INSTANCE.regisDoppelMgr.refreshAll(server); }
        catch (Exception e) { LOGGER.debug("[CobbleCap] regisDoppelMgr.refreshAll post-flush failed: {}", e.getMessage()); }

        dispatchReload(server, pm);
    }

    private static void dispatchReload(MinecraftServer server, PendingMorph pm) {
        // The `rctmod reload [trainer]` commands do NOT exist (verified against rctmod
        // 0.16.11-beta bytecode — only `rctmod player`/`rctmod trainer` subtrees). The old
        // dispatcher loop always threw + was swallowed, so RCT's in-memory trainer map was
        // never refreshed and the morphed team only took effect after a full server restart.
        // Call rctmod's real reload entry (TrainerManager.loadTrainers()) by reflection — it
        // re-reads the trainer JSONs from disk WITHOUT the vanilla /reload datapack rescan
        // (which would freeze the server for seconds with a large mod stack online).
        boolean ok = RctReloadHook.reloadTrainers(server);
        if (ok) {
            LOGGER.info("[CobbleCap] rctmod reload ok=true after morph -> {}", pm.playerName);
        } else {
            LOGGER.warn("[CobbleCap] rctmod reload failed after morph -> {} — JSON on disk updated, restart to apply.", pm.playerName);
        }
        try { CobbleCapMod.INSTANCE.getStatueManager().refreshCurrentChampionDioramas(server); }
        catch (Exception ignored) {}
    }

    /** Build the RCT JSON for a single Pokémon. */
    private static JsonObject buildPokemonJson(TeamSnapshot t) {
        JsonObject p = new JsonObject();
        p.addProperty("species", t.species == null ? "ditto" : t.species);
        if (t.level > 0) p.addProperty("level", t.level);
        if (t.gender != null && !t.gender.isEmpty()) p.addProperty("gender", t.gender);
        if (t.nature != null && !t.nature.isEmpty()) p.addProperty("nature", t.nature);
        if (t.shiny) p.addProperty("shiny", true);
        if (t.aspects != null && !t.aspects.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String a : t.aspects) if (a != null && !a.isBlank()) arr.add(a);
            if (arr.size() > 0) p.add("aspects", arr);
        }
        return p;
    }

    /** Load existing trainer JSON or return a minimal template. */
    private static JsonObject loadOrTemplate(Path file) {
        if (Files.exists(file)) {
            try {
                String content = Files.readString(file);
                JsonObject obj = GSON.fromJson(content, JsonObject.class);
                if (obj != null) return obj;
            } catch (Exception e) {
                LOGGER.warn("[CobbleCap] Could not parse existing trainer JSON, starting from template: {}",
                        e.getMessage());
            }
        }
        JsonObject root = new JsonObject();
        JsonObject name = new JsonObject();
        name.addProperty("translatable", "trainer.rctmod." + TRAINER_ID);
        root.add("name", name);
        JsonObject ai = new JsonObject();
        ai.addProperty("type", "rct");
        JsonObject aiData = new JsonObject();
        aiData.addProperty("maxSelectMargin", 0.01);
        ai.add("data", aiData);
        root.add("ai", ai);
        JsonObject rules = new JsonObject();
        rules.addProperty("maxItemUses", 6);
        root.add("battleRules", rules);
        return root;
    }

    private static void writeAtomic(Path target, String content) throws Exception {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (Exception ignored) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void writeAtomicBytes(Path target, byte[] bytes) throws Exception {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (Exception ignored) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    /**
     * Locate the world datapack root by name. Tries (in order):
     *   1. server.getSavePath(WorldSavePath.DATAPACKS) (canonical Fabric path)
     *   2. {level-name}/datapacks/...
     *   3. world/datapacks/...
     *   4. datapacks/... (global runDir/datapacks — Cacademy prod layout)
     *   5. resourcepacks/... (AutoModpack hybrid layout — Cacademy mirrors RCT data here)
     * Logs the chosen path so a misconfigured server can be debugged at first
     * morph attempt instead of silently doing nothing.
     */
    /** Exposed (package-public) for RegisModeManager which needs the same resolution. */
    static Path locateDatapackRoot(MinecraftServer server) {
        Path runDir = server.getRunDirectory();
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        try { candidates.add(server.getSavePath(net.minecraft.util.WorldSavePath.DATAPACKS).resolve(datapackName)); }
        catch (Exception ignored) {}
        try {
            String levelName = server.getSaveProperties().getLevelName();
            if (levelName != null && !levelName.isEmpty()) {
                candidates.add(runDir.resolve(levelName).resolve("datapacks").resolve(datapackName));
            }
        } catch (Exception ignored) {}
        candidates.add(runDir.resolve("world").resolve("datapacks").resolve(datapackName));
        candidates.add(runDir.resolve("datapacks").resolve(datapackName));
        candidates.add(runDir.resolve("resourcepacks").resolve(datapackName));

        for (Path c : candidates) {
            if (c != null && Files.isDirectory(c)) {
                Path trainer = c.resolve(trainerJsonRelPath);
                if (Files.isRegularFile(trainer)) {
                    LOGGER.info("[CobbleCap] Datapack root resolved: {}", c);
                    return c;
                }
            }
        }
        LOGGER.warn("[CobbleCap] Datapack '{}' not found in any of: {}", datapackName, candidates);
        return null;
    }

    /**
     * Download the player's Mojang skin PNG (64x64). Returns null on any failure.
     * Process: UUID -> session profile JSON -> base64 "textures" property -> JSON
     * with skin URL -> PNG bytes.
     */
    private static byte[] downloadMojangSkin(UUID uuid) {
        try {
            String stripped = uuid.toString().replace("-", "");
            HttpRequest profileReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + stripped))
                    .timeout(Duration.ofSeconds(8))
                    .build();
            HttpResponse<String> profileResp = HTTP.send(profileReq, HttpResponse.BodyHandlers.ofString());
            if (profileResp.statusCode() != 200) {
                LOGGER.warn("[CobbleCap] Mojang profile lookup {} -> HTTP {}", uuid, profileResp.statusCode());
                return null;
            }
            JsonObject profile = GSON.fromJson(profileResp.body(), JsonObject.class);
            if (profile == null || !profile.has("properties")) return null;
            String texturesB64 = null;
            for (var el : profile.getAsJsonArray("properties")) {
                JsonObject prop = el.getAsJsonObject();
                if (prop.has("name") && "textures".equals(prop.get("name").getAsString())) {
                    texturesB64 = prop.get("value").getAsString();
                    break;
                }
            }
            if (texturesB64 == null) return null;
            String texturesJson = new String(Base64.getDecoder().decode(texturesB64), StandardCharsets.UTF_8);
            JsonObject texturesObj = GSON.fromJson(texturesJson, JsonObject.class);
            if (texturesObj == null) return null;
            JsonObject tex = texturesObj.getAsJsonObject("textures");
            if (tex == null || !tex.has("SKIN")) return null;
            String skinUrl = tex.getAsJsonObject("SKIN").get("url").getAsString();
            HttpRequest pngReq = HttpRequest.newBuilder()
                    .uri(URI.create(skinUrl))
                    .timeout(Duration.ofSeconds(8))
                    .build();
            HttpResponse<InputStream> pngResp = HTTP.send(pngReq, HttpResponse.BodyHandlers.ofInputStream());
            if (pngResp.statusCode() != 200) return null;
            try (InputStream in = pngResp.body()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] downloadMojangSkin error: {}", e.getMessage());
            return null;
        }
    }

    /** Cached reflection handle to BattleRegistry's private map. -1 means "unknown". */
    private static volatile java.lang.reflect.Field battleMapField;
    private static volatile Object battleRegistryInstance;
    private static volatile boolean battleScanInitFailed;

    /**
     * Count active Cobblemon battles by scanning BattleRegistry for its private
     * Map field (BattleRegistry's public surface is method-based and 1-arg only;
     * the `battleMap` field is private static).
     *
     * Returns {@code -1} when the registry is not introspectable — callers MUST
     * treat -1 as "unknown" and defer the morph (fail-closed).
     */
    private static int countActiveBattles() {
        try {
            if (battleRegistryInstance == null && !battleScanInitFailed) {
                try {
                    Class<?> br;
                    try { br = Class.forName("com.cobblemon.mod.common.battles.BattleRegistry"); }
                    catch (ClassNotFoundException e1) {
                        br = Class.forName("com.cobblemon.mod.common.api.battles.BattleRegistry");
                    }
                    Object inst;
                    try { inst = br.getField("INSTANCE").get(null); }
                    catch (NoSuchFieldException nf) { inst = null; }
                    // Find the first Map-typed declared field on this registry
                    java.lang.reflect.Field chosen = null;
                    for (java.lang.reflect.Field f : br.getDeclaredFields()) {
                        if (java.util.Map.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            chosen = f; break;
                        }
                    }
                    if (chosen != null) {
                        battleMapField = chosen;
                        battleRegistryInstance = inst; // may be null for true static fields
                        LOGGER.info("[CobbleCap] Battle scan ready via field '{}'", chosen.getName());
                    } else {
                        battleScanInitFailed = true;
                        LOGGER.warn("[CobbleCap] BattleRegistry has no Map field — countActiveBattles will return -1");
                    }
                } catch (Exception bootErr) {
                    battleScanInitFailed = true;
                    LOGGER.warn("[CobbleCap] BattleRegistry init failed: {}", bootErr.getMessage());
                }
            }
            if (battleMapField == null) return -1;
            Object v = battleMapField.get(battleRegistryInstance);
            if (v instanceof java.util.Map<?, ?> m) return m.size();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Build a TeamSnapshot list from the player's current Cobblemon party. */
    private static List<TeamSnapshot> snapshotTeam(ServerPlayerEntity player) {
        List<TeamSnapshot> out = new ArrayList<>();
        var legendTeam = ChampionManager.snapshotPlayerTeam(player);
        for (var m : legendTeam) {
            out.add(new TeamSnapshot(m.species(), m.level(), m.shiny(), m.form(),
                    m.aspects(), m.ball(), m.nature(),
                    m.gender(), m.nickname()));
        }
        return out;
    }
}
