package com.cobblecap.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Persistent binding for the "championship" Cobblemon NPC: which entity in
 * which world represents the current Pokémon League Master, and which player
 * was last reflected by it. Stored at config/cobblecap_champion_npc.json.
 */
public class ChampionNpcState {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("cobblecap_champion_npc.json");

    private volatile UUID npcEntityUuid;     // the bound Cobblemon NPC entity
    private volatile String dimensionId;     // e.g. "cheeseguard:conseil4" or "minecraft:overworld"
    private volatile UUID lastChampionUuid;  // last player whose skin/team was applied
    private volatile String lastChampionName;
    private volatile long lastMorphAt;

    public synchronized void load() {
        try {
            if (!Files.exists(PATH)) return;
            try (Reader r = Files.newBufferedReader(PATH)) {
                JsonObject root = GSON.fromJson(r, JsonObject.class);
                if (root == null) return;
                if (root.has("npcEntityUuid"))
                    npcEntityUuid = parseUuid(root.get("npcEntityUuid").getAsString());
                if (root.has("dimensionId"))
                    dimensionId = root.get("dimensionId").getAsString();
                if (root.has("lastChampionUuid"))
                    lastChampionUuid = parseUuid(root.get("lastChampionUuid").getAsString());
                if (root.has("lastChampionName"))
                    lastChampionName = root.get("lastChampionName").getAsString();
                if (root.has("lastMorphAt"))
                    lastMorphAt = root.get("lastMorphAt").getAsLong();
            }
            LOGGER.info("[CobbleCap] Loaded champion NPC binding: npc={}, lastChamp={}",
                    npcEntityUuid, lastChampionName);
        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to load champion NPC state: {}", e.getMessage(), e);
        }
    }

    private static UUID parseUuid(String s) {
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(PATH.getParent());
            JsonObject root = new JsonObject();
            if (npcEntityUuid != null) root.addProperty("npcEntityUuid", npcEntityUuid.toString());
            if (dimensionId != null) root.addProperty("dimensionId", dimensionId);
            if (lastChampionUuid != null) root.addProperty("lastChampionUuid", lastChampionUuid.toString());
            if (lastChampionName != null) root.addProperty("lastChampionName", lastChampionName);
            if (lastMorphAt > 0) root.addProperty("lastMorphAt", lastMorphAt);
            Path tmp = PATH.resolveSibling("cobblecap_champion_npc.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) { GSON.toJson(root, w); }
            try { Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException atomic) { Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to save champion NPC state: {}", e.getMessage(), e);
        }
    }

    public UUID getNpcEntityUuid()      { return npcEntityUuid; }
    public String getDimensionId()      { return dimensionId; }
    public UUID getLastChampionUuid()   { return lastChampionUuid; }
    public String getLastChampionName() { return lastChampionName; }
    public long getLastMorphAt()        { return lastMorphAt; }
    public boolean isBound()            { return npcEntityUuid != null; }

    public synchronized void bind(UUID entityUuid, String dimId) {
        this.npcEntityUuid = entityUuid;
        this.dimensionId = dimId;
        save();
    }
    public synchronized void unbind() {
        this.npcEntityUuid = null;
        this.dimensionId = null;
        save();
    }
    public synchronized void recordMorph(UUID playerUuid, String playerName) {
        this.lastChampionUuid = playerUuid;
        this.lastChampionName = playerName;
        this.lastMorphAt = System.currentTimeMillis();
        save();
    }
}
