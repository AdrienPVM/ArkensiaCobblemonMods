package com.cheesemarket.data;

import com.google.gson.JsonObject;

import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * Defines when a ShopSet is active / visible.
 *
 * Modes:
 *  ALWAYS   — Always active.
 *  SCHEDULE — Active during a daily time window (IRL time). e.g. 08:00–12:00.
 *             Repeats every day. Multiple sets can be active simultaneously.
 *  MANUAL   — Activated/deactivated manually from the admin dashboard.
 */
public class RotationConfig {

    public enum Mode {
        ALWAYS, SCHEDULE, MANUAL
    }

    private Mode   mode;
    private String scheduleStart;  // "HH:mm"
    private String scheduleEnd;    // "HH:mm"
    private boolean manualActive;  // for MANUAL mode

    public RotationConfig() {
        this.mode = Mode.ALWAYS;
        this.scheduleStart = "08:00";
        this.scheduleEnd = "20:00";
        this.manualActive = true;
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode.name());
        o.addProperty("scheduleStart", scheduleStart);
        o.addProperty("scheduleEnd", scheduleEnd);
        o.addProperty("manualActive", manualActive);
        return o;
    }

    public static RotationConfig fromJson(JsonObject o) {
        RotationConfig r = new RotationConfig();
        if (o == null) return r;
        if (o.has("mode"))           r.mode = safeMode(o.get("mode").getAsString());
        if (o.has("scheduleStart"))  r.scheduleStart = o.get("scheduleStart").getAsString();
        if (o.has("scheduleEnd"))    r.scheduleEnd = o.get("scheduleEnd").getAsString();
        if (o.has("manualActive"))   r.manualActive = o.get("manualActive").getAsBoolean();
        return r;
    }

    private static Mode safeMode(String s) {
        try { return Mode.valueOf(s); } catch (Exception e) { return Mode.ALWAYS; }
    }

    // ── Runtime check ──────────────────────────────────────────────────────────

    /**
     * Returns true if this rotation config is currently active.
     * Called by ShopManager when building player-facing shop data.
     */
    public boolean isCurrentlyActive() {
        return switch (mode) {
            case ALWAYS   -> true;
            case MANUAL   -> manualActive;
            case SCHEDULE -> isInScheduleWindow();
        };
    }

    private boolean isInScheduleWindow() {
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
            LocalTime now   = LocalTime.now();
            LocalTime start = LocalTime.parse(scheduleStart, fmt);
            LocalTime end   = LocalTime.parse(scheduleEnd, fmt);
            if (start.isBefore(end)) {
                return !now.isBefore(start) && now.isBefore(end);
            } else {
                // crosses midnight: active from start until end next day
                return !now.isBefore(start) || now.isBefore(end);
            }
        } catch (Exception e) {
            return true;
        }
    }

    // ── Display helpers ────────────────────────────────────────────────────────

    public String modeLabel() {
        return switch (mode) {
            case ALWAYS   -> "Toujours actif";
            case SCHEDULE -> "Horaire (" + scheduleStart + "→" + scheduleEnd + ")";
            case MANUAL   -> "Manuel (" + (manualActive ? "ON" : "OFF") + ")";
        };
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public Mode    getMode()                       { return mode; }
    public void    setMode(Mode mode)              { this.mode = mode; }
    public String  getScheduleStart()              { return scheduleStart; }
    public void    setScheduleStart(String v)      { this.scheduleStart = v; }
    public String  getScheduleEnd()                { return scheduleEnd; }
    public void    setScheduleEnd(String v)        { this.scheduleEnd = v; }
    public boolean isManualActive()                { return manualActive; }
    public void    setManualActive(boolean v)      { this.manualActive = v; }
}
