package com.cobblecheesescore.config;

import java.util.HashMap;
import java.util.Map;

public class ScoreboardConfig {
    private String id;
    private boolean enabled = true;
    private String filename;
    private String title;
    private String description;
    private String sortOrder = "desc"; // "desc" or "asc"
    private int maxPlayers = 30;
    private int rowsPerColumn = 10;
    private Map<String, String> colors = new HashMap<>();
    
    // Getters
    public String getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public String getFilename() { return filename; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getSortOrder() { return sortOrder; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getRowsPerColumn() { return rowsPerColumn; }
    public Map<String, String> getColors() { return colors; }
    
    public String getColorOrDefault(String key, Map<String, String> defaults) {
        return colors.getOrDefault(key, defaults.getOrDefault(key, "#FFFFFF"));
    }

    // Setters for dashboard editing + programmatic config seeding
    public void setId(String id) { this.id = id; }
    public void setFilename(String filename) { this.filename = filename; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
    public void setRowsPerColumn(int rowsPerColumn) { this.rowsPerColumn = rowsPerColumn; }
    public void setSortOrder(String sortOrder) { this.sortOrder = sortOrder; }
    public void setColors(Map<String, String> colors) { this.colors = colors; }
}
