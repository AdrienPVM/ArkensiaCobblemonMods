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
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CapStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path STORAGE_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("cobblecap/players.json");

    private final Map<UUID, PlayerCapData> players = new ConcurrentHashMap<>();
    private final CapConfig capConfig;
    private final AtomicBoolean saveDirty = new AtomicBoolean(false);
    private final ScheduledExecutorService saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CobbleCap-Save");
        t.setDaemon(true);
        return t;
    });

    public CapStorage(CapConfig capConfig) {
        this.capConfig = capConfig;
    }

    public void load() {
        try {
            Files.createDirectories(STORAGE_PATH.getParent());

            if (!Files.exists(STORAGE_PATH)) {
                LOGGER.info("[CobbleCap] No players.json found — starting with empty storage");
                return;
            }

            try (Reader reader = Files.newBufferedReader(STORAGE_PATH)) {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                if (root != null) {
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                        try {
                            UUID uuid = UUID.fromString(entry.getKey());
                            PlayerCapData data = PlayerCapData.fromJson(uuid, entry.getValue().getAsJsonObject());
                            players.put(uuid, data);
                        } catch (Exception e) {
                            LOGGER.error("[CobbleCap] Failed to parse player entry {}: {}", entry.getKey(), e.getMessage());
                        }
                    }
                }
            }

            LOGGER.info("[CobbleCap] Loaded {} player entries", players.size());
        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to load players.json: {}", e.getMessage(), e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(STORAGE_PATH.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, PlayerCapData> entry : players.entrySet()) {
                root.add(entry.getKey().toString(), entry.getValue().toJson());
            }
            Path tmp = STORAGE_PATH.resolveSibling("players.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(tmp, STORAGE_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, STORAGE_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.info("[CobbleCap] Saved {} player entries", players.size());
        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to save players.json: {}", e.getMessage(), e);
        }
    }

    public void saveAsync() {
        if (saveDirty.compareAndSet(false, true)) {
            saveExecutor.schedule(() -> {
                saveDirty.set(false);
                save();
            }, 500, TimeUnit.MILLISECONDS);
        }
    }

    public void ensureExists(UUID uuid, String playerName) {
        players.computeIfAbsent(uuid,
                k -> new PlayerCapData(uuid, playerName, capConfig.getStartingCap()));
        saveAsync();
    }

    public Optional<PlayerCapData> getCapData(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    public void setCapLevel(UUID uuid, int level) {
        PlayerCapData data = players.get(uuid);
        if (data != null) {
            data.setCapLevel(level);
            saveAsync();
        }
    }

    /**
     * Raises a player's cap to newCap, but ONLY if newCap is higher than the current cap.
     * Returns true if the cap was actually raised, false if it was already >= newCap.
     */
    public boolean raiseCapLevel(UUID uuid, int newCap) {
        PlayerCapData data = players.get(uuid);
        if (data == null) return false;
        if (data.getCapLevel() >= newCap) return false;
        data.setCapLevel(newCap);
        saveAsync();
        return true;
    }

    public void setHudVisible(UUID uuid, boolean visible) {
        PlayerCapData data = players.get(uuid);
        if (data != null) {
            data.setHudVisible(visible);
            saveAsync();
        }
    }

    public void setHudPosition(UUID uuid, int position) {
        PlayerCapData data = players.get(uuid);
        if (data != null) {
            data.setHudPosition(position);
            saveAsync();
        }
    }

    public Collection<PlayerCapData> getAllPlayers() {
        return Collections.unmodifiableCollection(players.values());
    }
}
