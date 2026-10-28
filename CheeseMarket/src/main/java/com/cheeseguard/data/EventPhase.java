package com.cheeseguard.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A phase within a ServerEvent. Each phase has its own duration, effects, and message.
 */
public class EventPhase {

    private String id;
    private String name = "Phase";
    private int durationSeconds = 300; // 5 min default

    // Entry message (title/subtitle displayed when phase starts)
    private String titleMessage = "";
    private String subtitleMessage = "";
    private String titleColor = "gold";

    // Sound played on phase start
    private String startSound = ""; // e.g. "minecraft:entity.ender_dragon.growl"

    // Effects active during this phase
    private final List<EventEffect> effects = new ArrayList<>();

    public EventPhase() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
    }

    public EventPhase(String id) {
        this.id = id;
    }

    // ── Getters / Setters ────────────────────────────────────

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int d) { this.durationSeconds = d; }

    public String getTitleMessage() { return titleMessage; }
    public void setTitleMessage(String m) { this.titleMessage = m; }
    public String getSubtitleMessage() { return subtitleMessage; }
    public void setSubtitleMessage(String m) { this.subtitleMessage = m; }
    public String getTitleColor() { return titleColor; }
    public void setTitleColor(String c) { this.titleColor = c; }

    public String getStartSound() { return startSound; }
    public void setStartSound(String s) { this.startSound = s; }

    public List<EventEffect> getEffects() { return effects; }

    public EventEffect getEffect(String effectId) {
        for (EventEffect e : effects) if (e.getId().equals(effectId)) return e;
        return null;
    }

    public void addEffect(EventEffect e) { effects.add(e); }
    public void removeEffect(String effectId) { effects.removeIf(e -> e.getId().equals(effectId)); }

    // ── JSON ─────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("duration", durationSeconds);
        o.addProperty("titleMessage", titleMessage);
        o.addProperty("subtitleMessage", subtitleMessage);
        o.addProperty("titleColor", titleColor);
        if (!startSound.isBlank()) o.addProperty("startSound", startSound);

        JsonArray arr = new JsonArray();
        for (EventEffect e : effects) arr.add(e.toJson());
        o.add("effects", arr);
        return o;
    }

    public static EventPhase fromJson(JsonObject o) {
        EventPhase p = new EventPhase(o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString().substring(0, 8));
        if (o.has("name")) p.name = o.get("name").getAsString();
        if (o.has("duration")) p.durationSeconds = o.get("duration").getAsInt();
        if (o.has("titleMessage")) p.titleMessage = o.get("titleMessage").getAsString();
        if (o.has("subtitleMessage")) p.subtitleMessage = o.get("subtitleMessage").getAsString();
        if (o.has("titleColor")) p.titleColor = o.get("titleColor").getAsString();
        if (o.has("startSound")) p.startSound = o.get("startSound").getAsString();

        if (o.has("effects")) {
            for (JsonElement el : o.getAsJsonArray("effects")) {
                try { p.effects.add(EventEffect.fromJson(el.getAsJsonObject())); } catch (Exception ignored) {}
            }
        }
        return p;
    }
}
