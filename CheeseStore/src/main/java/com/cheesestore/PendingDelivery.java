package com.cheesestore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class PendingDelivery {

    public enum Status { PENDING, DELIVERED, FAILED }

    private String orderId; // Stripe checkout session ID — unique key
    private String playerUUID;
    private String playerName;
    private String productId;
    private String productName;
    private int priceCents;
    private List<String> commands = new ArrayList<>();
    private Status status = Status.PENDING;
    private long createdAt;
    private long deliveredAt;
    private String failReason;

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getPlayerUUID() { return playerUUID; }
    public void setPlayerUUID(String playerUUID) { this.playerUUID = playerUUID; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public int getPriceCents() { return priceCents; }
    public void setPriceCents(int priceCents) { this.priceCents = priceCents; }

    public List<String> getCommands() { return commands; }
    public void setCommands(List<String> commands) { this.commands = commands; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(long deliveredAt) { this.deliveredAt = deliveredAt; }

    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }

    // ── JSON ─────────────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("orderId", orderId);
        obj.addProperty("playerUUID", playerUUID);
        obj.addProperty("playerName", playerName);
        obj.addProperty("productId", productId);
        obj.addProperty("productName", productName);
        obj.addProperty("priceCents", priceCents);
        obj.addProperty("status", status.name());
        obj.addProperty("createdAt", createdAt);
        obj.addProperty("deliveredAt", deliveredAt);
        if (failReason != null) obj.addProperty("failReason", failReason);
        JsonArray cmds = new JsonArray();
        for (String cmd : commands) cmds.add(cmd);
        obj.add("commands", cmds);
        return obj;
    }

    public static PendingDelivery fromJson(JsonObject obj) {
        PendingDelivery d = new PendingDelivery();
        d.orderId = obj.has("orderId") ? obj.get("orderId").getAsString() : null;
        d.playerUUID = obj.has("playerUUID") ? obj.get("playerUUID").getAsString() : null;
        d.playerName = obj.has("playerName") ? obj.get("playerName").getAsString() : null;
        d.productId = obj.has("productId") ? obj.get("productId").getAsString() : null;
        d.productName = obj.has("productName") ? obj.get("productName").getAsString() : null;
        d.priceCents = obj.has("priceCents") ? obj.get("priceCents").getAsInt() : 0;
        d.status = obj.has("status") ? Status.valueOf(obj.get("status").getAsString()) : Status.PENDING;
        d.createdAt = obj.has("createdAt") ? obj.get("createdAt").getAsLong() : 0;
        d.deliveredAt = obj.has("deliveredAt") ? obj.get("deliveredAt").getAsLong() : 0;
        d.failReason = obj.has("failReason") ? obj.get("failReason").getAsString() : null;
        if (obj.has("commands")) {
            for (JsonElement el : obj.getAsJsonArray("commands")) {
                d.commands.add(el.getAsString());
            }
        }
        return d;
    }
}
