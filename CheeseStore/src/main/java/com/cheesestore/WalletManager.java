package com.cheesestore;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages "fromage" (premium cheese currency) balances per player.
 * Thread-safe via ConcurrentHashMap, async persistence.
 */
public class WalletManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static WalletManager INSTANCE;

    private final Path walletsFile;
    private final Map<UUID, WalletEntry> wallets = new ConcurrentHashMap<>();

    private WalletManager(Path configDir) {
        this.walletsFile = configDir.resolve("cheesestore_wallets.json");
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static void init(Path configDir) {
        INSTANCE = new WalletManager(configDir);
        INSTANCE.load();
    }

    public static WalletManager getInstance() {
        return INSTANCE;
    }

    // ── Balance Operations ────────────────────────────────────────────────────

    /**
     * Returns the player's balance, or 0 if not found.
     */
    public long getBalance(UUID uuid) {
        WalletEntry entry = wallets.get(uuid);
        return entry != null ? entry.getBalance() : 0;
    }

    /**
     * Sets a player's balance to an exact amount (clamped to >= 0).
     */
    public void setBalance(UUID uuid, String playerName, long amount) {
        long clamped = Math.max(0, amount);
        WalletEntry entry = wallets.computeIfAbsent(uuid, k -> new WalletEntry(uuid, playerName));
        entry.setPlayerName(playerName);
        entry.setBalance(clamped);
        entry.setLastUpdated(System.currentTimeMillis());
        saveAsync();
    }

    /**
     * Deducts an amount from a player's balance.
     * Returns false if the player has insufficient funds (balance unchanged).
     */
    public boolean deduct(UUID uuid, String playerName, long amount) {
        if (amount <= 0) return true;
        WalletEntry entry = wallets.get(uuid);
        if (entry == null || entry.getBalance() < amount) {
            return false;
        }
        entry.setPlayerName(playerName);
        entry.setBalance(entry.getBalance() - amount);
        entry.setLastUpdated(System.currentTimeMillis());
        saveAsync();
        return true;
    }

    /**
     * Adds an amount to a player's balance.
     */
    public void add(UUID uuid, String playerName, long amount) {
        if (amount <= 0) return;
        WalletEntry entry = wallets.computeIfAbsent(uuid, k -> new WalletEntry(uuid, playerName));
        entry.setPlayerName(playerName);
        entry.setBalance(entry.getBalance() + amount);
        entry.setLastUpdated(System.currentTimeMillis());
        saveAsync();
    }

    /**
     * Returns an unmodifiable copy of all wallet entries.
     */
    public Map<UUID, WalletEntry> getAll() {
        return Collections.unmodifiableMap(new HashMap<>(wallets));
    }

    /**
     * Returns the top N players sorted by balance descending.
     */
    public List<WalletEntry> getTop(int limit) {
        return wallets.values().stream()
                .sorted(Comparator.comparingLong(WalletEntry::getBalance).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void load() {
        if (!Files.exists(walletsFile)) {
            LOGGER.info("[CheeseStore] No wallets file found, starting fresh");
            return;
        }
        try {
            String json = Files.readString(walletsFile);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            wallets.clear();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(e.getKey());
                    WalletEntry entry = WalletEntry.fromJson(uuid, e.getValue().getAsJsonObject());
                    wallets.put(uuid, entry);
                } catch (Exception ex) {
                    LOGGER.warn("[CheeseStore] Skipping invalid wallet entry {}: {}", e.getKey(), ex.getMessage());
                }
            }
            LOGGER.info("[CheeseStore] Loaded {} wallet(s)", wallets.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseStore] Failed to load wallets: {}", e.getMessage());
        }
    }

    public void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, WalletEntry> e : wallets.entrySet()) {
                root.add(e.getKey().toString(), e.getValue().toJson());
            }
            Files.createDirectories(walletsFile.getParent());
            Files.writeString(walletsFile, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("[CheeseStore] Failed to save wallets: {}", e.getMessage());
        }
    }

    private void saveAsync() {
        CompletableFuture.runAsync(this::save).exceptionally(ex -> {
            LOGGER.error("[CheeseStore] Async wallet save failed: {}", ex.getMessage());
            return null;
        });
    }

    // ── WalletEntry ───────────────────────────────────────────────────────────

    public static class WalletEntry {

        private UUID uuid;
        private String playerName;
        private long balance;
        private long lastUpdated;

        public WalletEntry(UUID uuid, String playerName) {
            this.uuid = uuid;
            this.playerName = playerName;
            this.balance = 0;
            this.lastUpdated = System.currentTimeMillis();
        }

        // Getters
        public UUID getUuid() { return uuid; }
        public String getPlayerName() { return playerName; }
        public long getBalance() { return balance; }
        public long getLastUpdated() { return lastUpdated; }

        // Setters
        public void setPlayerName(String playerName) { this.playerName = playerName; }
        public void setBalance(long balance) { this.balance = balance; }
        public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }

        // JSON
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("playerName", playerName);
            obj.addProperty("balance", balance);
            obj.addProperty("lastUpdated", lastUpdated);
            return obj;
        }

        public static WalletEntry fromJson(UUID uuid, JsonObject obj) {
            WalletEntry entry = new WalletEntry(uuid, "");
            if (obj.has("playerName")) entry.playerName = obj.get("playerName").getAsString();
            if (obj.has("balance")) entry.balance = obj.get("balance").getAsLong();
            if (obj.has("lastUpdated")) entry.lastUpdated = obj.get("lastUpdated").getAsLong();
            return entry;
        }
    }
}
