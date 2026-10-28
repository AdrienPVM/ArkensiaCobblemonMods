package com.cheesemarket.manager;

import com.cheesemarket.data.*;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central shop manager.
 * - Loads / saves shop definitions from JSON files
 * - Tracks runtime stock (global remaining, per-player counts)
 * - Validates purchase eligibility
 * - Resets quotas on schedule
 */
public class ShopManager {

    private static ShopManager INSTANCE;

    private final Path shopsDir;
    private final Map<String, ShopData> shops = new LinkedHashMap<>();

    // Transaction log (newest first, capped at 500)
    private final List<JsonObject> transactions = new CopyOnWriteArrayList<>();
    private static final int MAX_TRANSACTIONS = 500;

    // Runtime stock tracking (not persisted to main shop JSON — separate file)
    // key: "shopId:setId:itemId" → global remaining (for GLOBAL mode)
    private final Map<String, Integer> globalStock = new ConcurrentHashMap<>();
    // key: "shopId:setId:itemId:playerUUID" → purchases made
    private final Map<String, Integer> playerStock = new ConcurrentHashMap<>();
    // key: "shopId:setId:itemId" → timestamp of last reset (epoch seconds)
    private final Map<String, Long> lastReset = new ConcurrentHashMap<>();

    private ShopManager(Path dataDir) {
        this.shopsDir = dataDir.resolve("cheesemarket").resolve("shops");
        try { Files.createDirectories(shopsDir); } catch (IOException ignored) {}
    }

    public static ShopManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ShopManager(FabricLoader.getInstance().getConfigDir().getParent().resolve("world").resolve("data"));
        }
        return INSTANCE;
    }

    public static void init(Path worldDataDir) {
        INSTANCE = new ShopManager(worldDataDir);
        INSTANCE.loadAll();
    }

    // ── Load / Save ────────────────────────────────────────────────────────────

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("CheeseMarket");

    public void loadAll() {
        shops.clear();
        LOGGER.info("[Market] Loading shops from {}", shopsDir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(shopsDir, "*.json")) {
            for (Path file : stream) {
                try {
                    String json = Files.readString(file);
                    JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                    boolean hasLayout = o.has("layout") && o.get("layout").isJsonObject();
                    ShopData shop = ShopData.fromJson(o);
                    shops.put(shop.getId(), shop);
                    LOGGER.info("[Market]   Loaded shop '{}': jsonHasLayout={}, parsedLayout={}",
                        shop.getId(), hasLayout, shop.getLayout() != null ? shop.getLayout().getElements().size() + " elements" : "null");
                } catch (Exception e) {
                    System.err.println("[CheeseMarket] Failed to load shop: " + file + " → " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[CheeseMarket] Could not read shops directory: " + e.getMessage());
        }
        loadStockData();
        loadTransactions();
    }

    public void saveShop(ShopData shop) {
        shops.put(shop.getId(), shop);
        Path file = shopsDir.resolve(shop.getId() + ".json");
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(file, gson.toJson(shop.toJson()));
        } catch (IOException e) {
            System.err.println("[CheeseMarket] Failed to save shop " + shop.getId() + ": " + e.getMessage());
        }
    }

    public void deleteShop(String shopId) {
        shops.remove(shopId);
        Path file = shopsDir.resolve(shopId + ".json");
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }

    // ── Stock persistence ──────────────────────────────────────────────────────

    private Path stockFile() { return shopsDir.getParent().resolve("cheesemarket_stock.json"); }

    private void loadStockData() {
        Path f = stockFile();
        if (!Files.exists(f)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (root.has("global")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("global").entrySet())
                    globalStock.put(e.getKey(), e.getValue().getAsInt());
            }
            if (root.has("player")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("player").entrySet())
                    playerStock.put(e.getKey(), e.getValue().getAsInt());
            }
            if (root.has("lastReset")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("lastReset").entrySet())
                    lastReset.put(e.getKey(), e.getValue().getAsLong());
            }
        } catch (Exception e) {
            System.err.println("[CheeseMarket] Failed to load stock data: " + e.getMessage());
        }
    }

    public void saveStockData() {
        JsonObject root = new JsonObject();
        JsonObject gObj = new JsonObject();
        globalStock.forEach(gObj::addProperty);
        root.add("global", gObj);
        JsonObject pObj = new JsonObject();
        playerStock.forEach(pObj::addProperty);
        root.add("player", pObj);
        JsonObject rObj = new JsonObject();
        lastReset.forEach(rObj::addProperty);
        root.add("lastReset", rObj);
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(stockFile(), gson.toJson(root));
        } catch (IOException e) {
            System.err.println("[CheeseMarket] Failed to save stock data: " + e.getMessage());
        }
    }

    // ── CRUD helpers ───────────────────────────────────────────────────────────

    public Collection<ShopData> getAllShops() { return Collections.unmodifiableCollection(shops.values()); }
    public ShopData getShop(String id)        { return shops.get(id); }
    public boolean  shopExists(String id)     { return shops.containsKey(id); }

    public ShopData createShop(String id) {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString().substring(0, 8);
        ShopData shop = new ShopData(id);
        saveShop(shop);
        return shop;
    }

    // ── Purchase validation ────────────────────────────────────────────────────

    public enum PurchaseResult {
        OK, OUT_OF_GLOBAL_STOCK, PLAYER_LIMIT_REACHED, INSUFFICIENT_FUNDS, ITEM_DISABLED, SET_INACTIVE, SHOP_NOT_FOUND
    }

    /**
     * Check if a player can purchase a given item.
     * Does NOT deduct stock — call recordPurchase() after confirming payment.
     */
    public PurchaseResult canPurchase(String shopId, String setId, String itemId, UUID playerUUID) {
        ShopData shop = shops.get(shopId);
        if (shop == null) return PurchaseResult.SHOP_NOT_FOUND;

        ShopSet set = shop.findSet(setId);
        if (set == null || !set.isCurrentlyActive()) return PurchaseResult.SET_INACTIVE;

        ShopItem item = set.findItem(itemId);
        if (item == null || !item.isEnabled()) return PurchaseResult.ITEM_DISABLED;

        processResets(shopId, setId, itemId, item.getStock());

        String gKey = stockKey(shopId, setId, itemId);
        String pKey = playerKey(shopId, setId, itemId, playerUUID);
        StockConfig stock = item.getStock();

        switch (stock.getMode()) {
            case GLOBAL -> {
                int remaining = globalStock.getOrDefault(gKey, stock.getGlobalStock());
                if (remaining <= 0) return PurchaseResult.OUT_OF_GLOBAL_STOCK;
            }
            case PER_PLAYER -> {
                int purchased = playerStock.getOrDefault(pKey, 0);
                if (purchased >= stock.getPerPlayerLimit()) return PurchaseResult.PLAYER_LIMIT_REACHED;
            }
            case PER_PLAYER_RESET -> {
                int purchased = playerStock.getOrDefault(pKey, 0);
                if (purchased >= stock.getPerPlayerLimit()) return PurchaseResult.PLAYER_LIMIT_REACHED;
            }
            case UNLIMITED -> { /* always ok */ }
        }
        return PurchaseResult.OK;
    }

    /**
     * Record a completed purchase (deducts stock).
     * Overload without playerName for backward compat.
     */
    public void recordPurchase(String shopId, String setId, String itemId, UUID playerUUID) {
        recordPurchase(shopId, setId, itemId, playerUUID, null, 1);
    }

    /**
     * Record a completed purchase (deducts stock + logs transaction).
     * Saves stock & transaction to disk ONCE regardless of quantity.
     */
    public void recordPurchase(String shopId, String setId, String itemId, UUID playerUUID, String playerName, int quantity) {
        ShopData shop = shops.get(shopId);
        if (shop == null) return;
        ShopSet set = shop.findSet(setId);
        if (set == null) return;
        ShopItem item = set.findItem(itemId);
        if (item == null) return;

        String gKey = stockKey(shopId, setId, itemId);
        String pKey = playerKey(shopId, setId, itemId, playerUUID);
        StockConfig stock = item.getStock();

        switch (stock.getMode()) {
            case GLOBAL -> globalStock.put(gKey, Math.max(0, globalStock.getOrDefault(gKey, stock.getGlobalStock()) - quantity));
            case PER_PLAYER, PER_PLAYER_RESET -> playerStock.put(pKey, playerStock.getOrDefault(pKey, 0) + quantity);
            default -> {}
        }
        saveStockData();

        // Log transaction (single entry with quantity)
        logTransaction(shopId, shop.getName(), setId, set.getName(), itemId,
                item.getEffectiveName(), playerUUID, playerName, item.getCosts(), item.getOutputs(), quantity);
    }

    /** Get remaining stock info string for a given item (for GUI display). */
    public String stockDisplay(String shopId, String setId, String itemId, UUID playerUUID) {
        ShopData shop = shops.get(shopId);
        if (shop == null) return "—";
        ShopSet set = shop.findSet(setId);
        if (set == null) return "—";
        ShopItem item = set.findItem(itemId);
        if (item == null) return "—";

        processResets(shopId, setId, itemId, item.getStock());
        String gKey = stockKey(shopId, setId, itemId);
        String pKey = playerKey(shopId, setId, itemId, playerUUID);
        StockConfig stock = item.getStock();

        return switch (stock.getMode()) {
            case UNLIMITED -> "∞";
            case GLOBAL    -> globalStock.getOrDefault(gKey, stock.getGlobalStock()) + "/" + stock.getGlobalStock();
            case PER_PLAYER -> {
                int bought = playerStock.getOrDefault(pKey, 0);
                int remaining = stock.getPerPlayerLimit() - bought;
                yield remaining + "/" + stock.getPerPlayerLimit() + " (vous)";
            }
            case PER_PLAYER_RESET -> {
                int bought = playerStock.getOrDefault(pKey, 0);
                int remaining = stock.getPerPlayerLimit() - bought;
                yield remaining + "/" + stock.getPerPlayerLimit() + " (" + stock.resetLabel() + ")";
            }
        };
    }

    // ── Reset logic ────────────────────────────────────────────────────────────

    private void processResets(String shopId, String setId, String itemId, StockConfig stock) {
        if (stock.getMode() != StockConfig.Mode.PER_PLAYER_RESET) return;
        String key = stockKey(shopId, setId, itemId);
        long now = Instant.now().getEpochSecond();
        long last = lastReset.getOrDefault(key, 0L);
        long interval = getResetInterval(stock);
        if (now - last >= interval) {
            // Remove all per-player keys for this item
            String prefix = shopId + ":" + setId + ":" + itemId + ":";
            playerStock.keySet().removeIf(k -> k.startsWith(prefix));
            lastReset.put(key, now);
        }
    }

    private long getResetInterval(StockConfig stock) {
        return switch (stock.getResetType()) {
            case DAILY   -> 86400L;
            case WEEKLY  -> 604800L;
            case MONTHLY -> 2592000L;
            case CUSTOM  -> stock.getResetIntervalSeconds();
        };
    }

    // ── Key helpers ───────────────────────────────────────────────────────────

    private static String stockKey(String shopId, String setId, String itemId) {
        return shopId + ":" + setId + ":" + itemId;
    }
    private static String playerKey(String shopId, String setId, String itemId, UUID uuid) {
        return shopId + ":" + setId + ":" + itemId + ":" + uuid.toString();
    }

    // ── Transaction logging ─────────────────────────────────────────────────

    public List<JsonObject> getTransactions() {
        return Collections.unmodifiableList(transactions);
    }

    /**
     * Public log method for sell/buyback transactions from ShopGuiHandler.
     * type: "BUY" or "SELL"
     */
    public void logTransaction(String shopId, String setId, String itemId,
                               UUID playerUUID, String playerName, String type,
                               String itemSold, int quantity) {
        JsonObject tx = new JsonObject();
        tx.addProperty("timestamp", Instant.now().toString());
        tx.addProperty("playerUUID", playerUUID.toString());
        tx.addProperty("playerName", playerName != null ? playerName : "Unknown");
        tx.addProperty("shopId", shopId);
        tx.addProperty("type", type);
        tx.addProperty("itemId", itemSold);
        tx.addProperty("quantity", quantity);
        transactions.add(0, tx);
        while (transactions.size() > MAX_TRANSACTIONS) transactions.remove(transactions.size() - 1);
        saveTransactions();
    }

    private void logTransaction(String shopId, String shopName, String setId, String setName,
                                String itemId, String itemName, UUID playerUUID, String playerName,
                                java.util.List<ItemEntry> costs, java.util.List<ItemEntry> outputs, int quantity) {
        JsonObject tx = new JsonObject();
        tx.addProperty("timestamp", Instant.now().toString());
        tx.addProperty("playerUUID", playerUUID.toString());
        tx.addProperty("playerName", playerName != null ? playerName : "Unknown");
        tx.addProperty("shopId", shopId);
        tx.addProperty("shopName", shopName != null ? shopName : shopId);
        tx.addProperty("setId", setId);
        tx.addProperty("setName", setName != null ? setName : setId);
        tx.addProperty("itemId", itemId);
        tx.addProperty("itemName", itemName != null ? itemName : itemId);
        tx.addProperty("quantity", quantity);
        JsonArray costsArr = new JsonArray();
        if (costs != null) costs.forEach(c -> costsArr.add(c.toJson()));
        tx.add("costs", costsArr);
        JsonArray outputsArr = new JsonArray();
        if (outputs != null) outputs.forEach(o -> outputsArr.add(o.toJson()));
        tx.add("outputs", outputsArr);

        transactions.add(0, tx);
        while (transactions.size() > MAX_TRANSACTIONS) transactions.remove(transactions.size() - 1);
        saveTransactions();
    }

    private Path transactionsFile() { return shopsDir.getParent().resolve("cheesemarket_transactions.json"); }

    private void loadTransactions() {
        Path f = transactionsFile();
        if (!Files.exists(f)) return;
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(f)).getAsJsonArray();
            transactions.clear();
            for (JsonElement el : arr) transactions.add(el.getAsJsonObject());
        } catch (Exception e) {
            System.err.println("[CheeseMarket] Failed to load transactions: " + e.getMessage());
        }
    }

    private void saveTransactions() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonArray arr = new JsonArray();
            transactions.forEach(arr::add);
            Files.writeString(transactionsFile(), gson.toJson(arr));
        } catch (IOException e) {
            System.err.println("[CheeseMarket] Failed to save transactions: " + e.getMessage());
        }
    }
}
