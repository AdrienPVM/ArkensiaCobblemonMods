package com.cobblecap.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlayerCapData {

    private UUID uuid;
    private String playerName;
    private int capLevel;
    private List<String> championsBeaten;
    private List<String> challengeBeaten;
    private boolean hudVisible;
    private int hudPosition;
    private boolean expLocked;
    private long lastCapChange;
    private Map<String, Long> lastChallengeTimes;

    public PlayerCapData(UUID uuid, String playerName, int startingCap) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.capLevel = startingCap;
        this.championsBeaten = new ArrayList<>();
        this.challengeBeaten = new ArrayList<>();
        this.hudVisible = true;
        this.hudPosition = 0;
        this.expLocked = false;
        this.lastCapChange = System.currentTimeMillis();
        this.lastChallengeTimes = new HashMap<>();
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", uuid.toString());
        obj.addProperty("playerName", playerName);
        obj.addProperty("capLevel", capLevel);

        JsonArray championsArr = new JsonArray();
        for (String s : championsBeaten) championsArr.add(s);
        obj.add("championsBeaten", championsArr);

        JsonArray challengeArr = new JsonArray();
        for (String s : challengeBeaten) challengeArr.add(s);
        obj.add("challengeBeaten", challengeArr);

        obj.addProperty("hudVisible", hudVisible);
        obj.addProperty("hudPosition", hudPosition);
        obj.addProperty("expLocked", expLocked);
        obj.addProperty("lastCapChange", lastCapChange);

        JsonObject timesObj = new JsonObject();
        for (Map.Entry<String, Long> entry : lastChallengeTimes.entrySet()) {
            timesObj.addProperty(entry.getKey(), entry.getValue());
        }
        obj.add("lastChallengeTimes", timesObj);

        return obj;
    }

    public static PlayerCapData fromJson(UUID uuid, JsonObject obj) {
        String playerName = obj.has("playerName") ? obj.get("playerName").getAsString() : "Unknown";
        int capLevel = obj.has("capLevel") ? obj.get("capLevel").getAsInt() : 20;

        PlayerCapData data = new PlayerCapData(uuid, playerName, capLevel);
        // capLevel was set via constructor with startingCap, set it correctly from file
        data.capLevel = capLevel;

        if (obj.has("championsBeaten") && obj.get("championsBeaten").isJsonArray()) {
            List<String> champions = new ArrayList<>();
            for (JsonElement el : obj.get("championsBeaten").getAsJsonArray()) {
                champions.add(el.getAsString());
            }
            data.championsBeaten = champions;
        }

        if (obj.has("challengeBeaten") && obj.get("challengeBeaten").isJsonArray()) {
            List<String> challenges = new ArrayList<>();
            for (JsonElement el : obj.get("challengeBeaten").getAsJsonArray()) {
                challenges.add(el.getAsString());
            }
            data.challengeBeaten = challenges;
        }

        data.hudVisible = obj.has("hudVisible") ? obj.get("hudVisible").getAsBoolean() : true;
        data.hudPosition = obj.has("hudPosition") ? obj.get("hudPosition").getAsInt() : 0;
        data.expLocked = obj.has("expLocked") && obj.get("expLocked").getAsBoolean();
        data.lastCapChange = obj.has("lastCapChange") ? obj.get("lastCapChange").getAsLong() : System.currentTimeMillis();

        if (obj.has("lastChallengeTimes") && obj.get("lastChallengeTimes").isJsonObject()) {
            Map<String, Long> times = new HashMap<>();
            JsonObject timesObj = obj.get("lastChallengeTimes").getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : timesObj.entrySet()) {
                times.put(entry.getKey(), entry.getValue().getAsLong());
            }
            data.lastChallengeTimes = times;
        }

        return data;
    }

    public void addChampionBeaten(String id) {
        if (!championsBeaten.contains(id)) {
            championsBeaten.add(id);
        }
    }

    // Getters
    public UUID getUuid() { return uuid; }
    public String getPlayerName() { return playerName; }
    public int getCapLevel() { return capLevel; }
    public List<String> getChampionsBeaten() { return championsBeaten; }
    public List<String> getChallengeBeaten() { return challengeBeaten; }
    public boolean isHudVisible() { return hudVisible; }
    public int getHudPosition() { return hudPosition; }
    public long getLastCapChange() { return lastCapChange; }
    public Map<String, Long> getLastChallengeTimes() { return lastChallengeTimes; }

    // Setters
    public void setPlayerName(String playerName) { this.playerName = playerName; }
    public void setCapLevel(int capLevel) {
        this.capLevel = capLevel;
        this.lastCapChange = System.currentTimeMillis();
    }
    public void setHudVisible(boolean hudVisible) { this.hudVisible = hudVisible; }
    public void setHudPosition(int hudPosition) { this.hudPosition = hudPosition; }
    public boolean isExpLocked() { return expLocked; }
    public void setExpLocked(boolean expLocked) { this.expLocked = expLocked; }

    public void addChallengeBeaten(String id) {
        if (!challengeBeaten.contains(id)) {
            challengeBeaten.add(id);
        }
    }

    public void recordChallengeTime(String championId) {
        lastChallengeTimes.put(championId, System.currentTimeMillis());
    }

    /**
     * Check if all "normal" champions have been beaten.
     * Normal = champions with capGranted <= 100 that are NOT post-game (conseil 4, final champion).
     * In practice: only checks champions whose capGranted < 100, plus Giovanni (capGranted=100 with challengeId).
     * Champions without challengeId AND capGranted=100 are post-game (conseil 4) and excluded.
     */
    public boolean hasAllNormalChampionsBeaten(java.util.List<ChampionEntry> allChampions) {
        for (ChampionEntry c : allChampions) {
            // Skip post-game champions (conseil 4 + final Regis):
            // they have capGranted=100 but NO challengeId (or are the final boss)
            boolean hasChallenge = c.challengeId() != null && !c.challengeId().isEmpty();
            if (c.capGranted() >= 100 && !hasChallenge) continue;
            if (!championsBeaten.contains(c.id())) return false;
        }
        return !allChampions.isEmpty();
    }

    public boolean isChallengeOnCooldown(String championId, int cooldownHours) {
        if (cooldownHours <= 0) return false;
        Long lastTime = lastChallengeTimes.get(championId);
        if (lastTime == null) return false;
        long cooldownMs = cooldownHours * 3600_000L;
        return (System.currentTimeMillis() - lastTime) < cooldownMs;
    }

    public long getChallengeRemainingMs(String championId, int cooldownHours) {
        if (cooldownHours <= 0) return 0;
        Long lastTime = lastChallengeTimes.get(championId);
        if (lastTime == null) return 0;
        long cooldownMs = cooldownHours * 3600_000L;
        long remaining = cooldownMs - (System.currentTimeMillis() - lastTime);
        return Math.max(0, remaining);
    }
}
