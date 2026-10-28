package com.cheeseguard.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a named set of chunk entries
 */
public class ChunkSet {
    private final String name;
    private final List<ChunkEntry> chunks;

    // Dimension this chunk set targets (e.g. "minecraft:overworld", "cheeseguard:arkensia")
    private String dimension = "minecraft:overworld";

    // Scheduling fields
    private String resetTrigger = "MANUAL"; // MANUAL or SCHEDULED
    private int resetHour = 6;    // 0-23
    private int resetMinute = 0;  // 0-59
    private List<String> resetDays = new ArrayList<>(); // empty = every day, or ["MONDAY","FRIDAY",...]
    private long lastAutoReset = 0; // epoch ms, anti-double protection

    public ChunkSet(String name) {
        this.name = name;
        this.chunks = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public List<ChunkEntry> getChunks() {
        return chunks;
    }

    // ── Dimension ──

    public String getDimension() { return dimension; }
    public void setDimension(String dim) { this.dimension = dim != null && !dim.isEmpty() ? dim : "minecraft:overworld"; }

    // ── Scheduling getters/setters ──

    public String getResetTrigger() { return resetTrigger; }
    public void setResetTrigger(String trigger) { this.resetTrigger = trigger; }

    public int getResetHour() { return resetHour; }
    public void setResetHour(int hour) { this.resetHour = hour; }

    public int getResetMinute() { return resetMinute; }
    public void setResetMinute(int minute) { this.resetMinute = minute; }

    public List<String> getResetDays() { return resetDays; }
    public void setResetDays(List<String> days) { this.resetDays = days != null ? days : new ArrayList<>(); }

    public long getLastAutoReset() { return lastAutoReset; }
    public void setLastAutoReset(long ts) { this.lastAutoReset = ts; }

    public boolean isScheduled() { return "SCHEDULED".equals(resetTrigger); }
    
    public void addChunk(ChunkEntry entry) {
        chunks.add(entry);
    }
    
    public boolean removeChunk(ChunkEntry entry) {
        return chunks.remove(entry);
    }
    
    public int size() {
        return chunks.size();
    }
    
    public boolean isEmpty() {
        return chunks.isEmpty();
    }
    
    /**
     * Get total number of affected chunks (including radius expansion)
     */
    public int getTotalAffectedChunks() {
        return chunks.stream()
                .mapToInt(entry -> {
                    int diameter = (entry.getRadius() * 2) + 1;
                    return diameter * diameter;
                })
                .sum();
    }
    
    @Override
    public String toString() {
        return String.format("ChunkSet[%s]: %d entries (%d total chunks)", 
                           name, chunks.size(), getTotalAffectedChunks());
    }
}
