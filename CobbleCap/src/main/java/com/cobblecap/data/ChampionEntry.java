package com.cobblecap.data;

import com.google.gson.JsonObject;

/**
 * Immutable record describing a single champion (gym leader / elite / champion)
 * that gates a player's level cap progression.
 */
public record ChampionEntry(
        String id,              // rctmod trainer ID: "gym_leader_pierre_0395"
        String displayName,     // Human-readable: "Pierre"
        int capGranted,         // Level cap unlocked on victory: 30
        int requiredPokedex,    // Pokémon caught required: 0 = no requirement
        String requiredQuestId, // FTB Quest hex ID required BEFORE fight: "" = none
        String victoryQuestId,  // FTB Quest hex ID to COMPLETE on victory: "" = none
        String challengeId,     // Challenge variant trainer ID, "" = none
        String challengeQuestId, // FTB Quest hex ID completed on challenge victory: "" = none
        int challengeCooldownHours, // Hours between challenge attempts: 0 = no cooldown
        String requiredScoreboard, // Scoreboard objective required BEFORE fight: "" = none
        int requiredScoreMin,      // Minimum score required on the scoreboard (normal fight)
        int challengeRequiredScoreMin, // Minimum score for challenge fight (0 = no gate)
        boolean allowRefight,      // Allow re-fighting after first victory (false = one-time only)
        boolean challengeRequiresAllBeaten, // Challenge needs all normal champions beaten (default true)
        int challengeCapGranted    // Cap granted on challenge victory (0 = no cap change)
) {

    /**
     * Deserialize from JSON. Missing optional fields fall back to safe defaults.
     */
    public static ChampionEntry fromJson(JsonObject obj) {
        String id = obj.has("id") ? obj.get("id").getAsString() : "";
        String displayName = obj.has("displayName") ? obj.get("displayName").getAsString() : "";
        int capGranted = obj.has("capGranted") ? obj.get("capGranted").getAsInt() : 20;
        int requiredPokedex = obj.has("requiredPokedex") ? obj.get("requiredPokedex").getAsInt() : 0;
        String requiredQuestId = obj.has("requiredQuestId") ? obj.get("requiredQuestId").getAsString() : "";
        String victoryQuestId = obj.has("victoryQuestId") ? obj.get("victoryQuestId").getAsString() : "";
        String challengeId = obj.has("challengeId") ? obj.get("challengeId").getAsString() : "";
        String challengeQuestId = obj.has("challengeQuestId") ? obj.get("challengeQuestId").getAsString() : "";
        int challengeCooldownHours = obj.has("challengeCooldownHours") ? obj.get("challengeCooldownHours").getAsInt() : 0;
        String requiredScoreboard = obj.has("requiredScoreboard") ? obj.get("requiredScoreboard").getAsString() : "";
        int requiredScoreMin = obj.has("requiredScoreMin") ? obj.get("requiredScoreMin").getAsInt() : 0;
        int challengeRequiredScoreMin = obj.has("challengeRequiredScoreMin") ? obj.get("challengeRequiredScoreMin").getAsInt() : 0;
        boolean allowRefight = obj.has("allowRefight") ? obj.get("allowRefight").getAsBoolean() : true;
        boolean challengeRequiresAllBeaten = obj.has("challengeRequiresAllBeaten") ? obj.get("challengeRequiresAllBeaten").getAsBoolean() : true;
        int challengeCapGranted = obj.has("challengeCapGranted") ? obj.get("challengeCapGranted").getAsInt() : 0;
        return new ChampionEntry(id, displayName, capGranted, requiredPokedex,
                requiredQuestId, victoryQuestId, challengeId, challengeQuestId, challengeCooldownHours,
                requiredScoreboard, requiredScoreMin, challengeRequiredScoreMin, allowRefight,
                challengeRequiresAllBeaten, challengeCapGranted);
    }

    /**
     * Serialize to JSON for persistence.
     */
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("displayName", displayName);
        obj.addProperty("capGranted", capGranted);
        obj.addProperty("requiredPokedex", requiredPokedex);
        obj.addProperty("requiredQuestId", requiredQuestId);
        obj.addProperty("victoryQuestId", victoryQuestId);
        obj.addProperty("challengeId", challengeId);
        obj.addProperty("challengeQuestId", challengeQuestId);
        obj.addProperty("challengeCooldownHours", challengeCooldownHours);
        if (requiredScoreboard != null && !requiredScoreboard.isEmpty()) {
            obj.addProperty("requiredScoreboard", requiredScoreboard);
            obj.addProperty("requiredScoreMin", requiredScoreMin);
            if (challengeRequiredScoreMin > 0) obj.addProperty("challengeRequiredScoreMin", challengeRequiredScoreMin);
        }
        obj.addProperty("allowRefight", allowRefight);
        if (!challengeRequiresAllBeaten) obj.addProperty("challengeRequiresAllBeaten", false);
        if (challengeCapGranted > 0) obj.addProperty("challengeCapGranted", challengeCapGranted);
        return obj;
    }
}
