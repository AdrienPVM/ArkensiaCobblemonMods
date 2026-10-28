package com.cobblecheesescore.cache;

import com.cobblecheesescore.data.PlayerData;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

public class CacheManager {
    private Map<String, PlayerData> playerDataCache = new HashMap<>();
    private Map<String, BufferedImage> imageCache = new HashMap<>();
    private long lastUpdate = 0;
    
    public void setPlayerData(Map<String, PlayerData> data) {
        this.playerDataCache = new HashMap<>(data);
    }
    
    public Map<String, PlayerData> getPlayerData() {
        return playerDataCache;
    }
    
    public void cacheImage(String scoreboardId, BufferedImage image) {
        imageCache.put(scoreboardId, image);
    }
    
    public BufferedImage getCachedImage(String scoreboardId) {
        return imageCache.get(scoreboardId);
    }
    
    public boolean hasImage(String scoreboardId) {
        return imageCache.containsKey(scoreboardId);
    }
    
    public void setLastUpdate(long timestamp) {
        this.lastUpdate = timestamp;
    }
    
    public long getLastUpdate() {
        return lastUpdate;
    }
    
    public void clearAll() {
        playerDataCache.clear();
        imageCache.clear();
        lastUpdate = 0;
    }
}
