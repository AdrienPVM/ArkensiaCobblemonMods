package com.cheesestore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class DeliveryManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_DELIVERIES = 5000;

    private final Path deliveriesFile;
    private final List<PendingDelivery> deliveries = new CopyOnWriteArrayList<>();

    public DeliveryManager(Path configDir) {
        this.deliveriesFile = configDir.resolve("cheesestore_deliveries.json");
        load();
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(deliveriesFile)) return;
        try {
            String json = Files.readString(deliveriesFile);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            deliveries.clear();
            for (JsonElement el : arr) {
                deliveries.add(PendingDelivery.fromJson(el.getAsJsonObject()));
            }
            LOGGER.info("[CheeseStore] Loaded {} deliveries", deliveries.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseStore] Failed to load deliveries: {}", e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            // Trim to max size (keep newest)
            while (deliveries.size() > MAX_DELIVERIES) {
                deliveries.remove(0);
            }
            JsonArray arr = new JsonArray();
            for (PendingDelivery d : deliveries) arr.add(d.toJson());
            Files.createDirectories(deliveriesFile.getParent());
            Files.writeString(deliveriesFile, GSON.toJson(arr));
        } catch (IOException e) {
            LOGGER.error("[CheeseStore] Failed to save deliveries: {}", e.getMessage());
        }
    }

    // ── Core Operations ──────────────────────────────────────────────────────

    /**
     * Create a delivery. Idempotent — if orderId already exists, returns false.
     */
    public boolean createDelivery(String orderId, String playerUUID, String playerName,
                                   StoreProduct product) {
        // Anti-duplicate check
        if (findByOrderId(orderId) != null) {
            LOGGER.warn("[CheeseStore] Duplicate delivery attempt for orderId={}", orderId);
            return false;
        }

        PendingDelivery d = new PendingDelivery();
        d.setOrderId(orderId);
        d.setPlayerUUID(playerUUID);
        d.setPlayerName(playerName);
        d.setProductId(product.getId());
        d.setProductName(product.getName());
        d.setPriceCents(product.getPriceCents());
        // Resolve commands with player name
        List<String> resolved = new ArrayList<>();
        for (String cmd : product.getCommands()) {
            resolved.add(cmd.replace("{player}", playerName));
        }
        d.setCommands(resolved);
        d.setStatus(PendingDelivery.Status.PENDING);
        d.setCreatedAt(System.currentTimeMillis());

        deliveries.add(d);
        save();
        LOGGER.info("[CheeseStore] Delivery created: orderId={}, player={}, product={}",
            orderId, playerName, product.getName());
        return true;
    }

    /**
     * Attempt to deliver all PENDING deliveries for a player.
     * Called on player join and after successful payment verification.
     * Returns number of deliveries executed.
     */
    public int deliverPending(MinecraftServer server, String playerUUID) {
        List<PendingDelivery> pending = deliveries.stream()
            .filter(d -> d.getStatus() == PendingDelivery.Status.PENDING)
            .filter(d -> playerUUID.equals(d.getPlayerUUID()))
            .collect(Collectors.toList());

        if (pending.isEmpty()) return 0;

        // Check if player is online
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(UUID.fromString(playerUUID));
        if (player == null) return 0;

        int delivered = 0;
        for (PendingDelivery d : pending) {
            try {
                for (String cmd : d.getCommands()) {
                    server.getCommandManager().executeWithPrefix(
                        server.getCommandSource(), cmd);
                }
                d.setStatus(PendingDelivery.Status.DELIVERED);
                d.setDeliveredAt(System.currentTimeMillis());
                delivered++;
                LOGGER.info("[CheeseStore] Delivered orderId={} to player={}",
                    d.getOrderId(), d.getPlayerName());
            } catch (Exception e) {
                d.setStatus(PendingDelivery.Status.FAILED);
                d.setFailReason(e.getMessage());
                LOGGER.error("[CheeseStore] Failed to deliver orderId={}: {}",
                    d.getOrderId(), e.getMessage());
            }
        }

        if (delivered > 0) save();
        return delivered;
    }

    /**
     * Manual delivery trigger from admin dashboard.
     */
    public boolean manualDeliver(MinecraftServer server, String orderId) {
        PendingDelivery d = findByOrderId(orderId);
        if (d == null) return false;
        if (d.getStatus() == PendingDelivery.Status.DELIVERED) return false;

        try {
            for (String cmd : d.getCommands()) {
                server.getCommandManager().executeWithPrefix(
                    server.getCommandSource(), cmd);
            }
            d.setStatus(PendingDelivery.Status.DELIVERED);
            d.setDeliveredAt(System.currentTimeMillis());
            save();
            LOGGER.info("[CheeseStore] Manual delivery: orderId={}", orderId);
            return true;
        } catch (Exception e) {
            d.setStatus(PendingDelivery.Status.FAILED);
            d.setFailReason(e.getMessage());
            save();
            return false;
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public PendingDelivery findByOrderId(String orderId) {
        return deliveries.stream()
            .filter(d -> orderId.equals(d.getOrderId()))
            .findFirst().orElse(null);
    }

    public List<PendingDelivery> getAll() {
        return Collections.unmodifiableList(deliveries);
    }

    public List<PendingDelivery> getPendingForPlayer(String playerUUID) {
        return deliveries.stream()
            .filter(d -> d.getStatus() == PendingDelivery.Status.PENDING)
            .filter(d -> playerUUID.equals(d.getPlayerUUID()))
            .collect(Collectors.toList());
    }

    public long countByStatus(PendingDelivery.Status status) {
        return deliveries.stream().filter(d -> d.getStatus() == status).count();
    }

    public long totalRevenueCents() {
        return deliveries.stream()
            .filter(d -> d.getStatus() == PendingDelivery.Status.DELIVERED)
            .mapToLong(PendingDelivery::getPriceCents)
            .sum();
    }
}
