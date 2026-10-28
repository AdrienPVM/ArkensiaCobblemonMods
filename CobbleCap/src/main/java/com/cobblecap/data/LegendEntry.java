package com.cobblecap.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of a player who has completed all CobbleCap challenges
 * and earned the "Maître de la Ligue Pokémon" title. Stored permanently in
 * config/cobblecap_legends.json as a chronological record of accomplishments.
 *
 * isTest=true entries are produced by /cobblecap statue test for staging the
 * diorama feature. They are NEVER shown in /cobblecap legends, never broadcast,
 * never sent to Discord, and can be wiped by /cobblecap statue test_clear.
 */
public record LegendEntry(
        UUID uuid,
        String playerName,
        long completedAt,
        boolean firstEver,
        boolean isTest,
        long bestTimeMs,        // 0 = no chrono recorded; otherwise fastest run time
        List<TeamMember> team,
        Mode mode               // CLASSIC vs RANKED — added 2026-05-17 for dual-mode Régis
) {

    /** Conseil 4 mode. Drives which CCS scoreboards this entry feeds + whether
     *  it triggers a champion tenure change. Legacy entries (pre-2026-05-17) read
     *  as CLASSIC by default since no ranked mode existed back then. */
    public enum Mode {
        CLASSIC, RANKED;

        public static Mode fromKey(String k) {
            if (k == null) return CLASSIC;
            return k.trim().equalsIgnoreCase("ranked") ? RANKED : CLASSIC;
        }
    }

    /** Convenience overload — production entries are never tests, no chrono, classic. */
    public LegendEntry(UUID uuid, String playerName, long completedAt, boolean firstEver, List<TeamMember> team) {
        this(uuid, playerName, completedAt, firstEver, false, 0L, team, Mode.CLASSIC);
    }

    /** Convenience overload — explicit isTest, no chrono, classic. */
    public LegendEntry(UUID uuid, String playerName, long completedAt, boolean firstEver,
                       boolean isTest, List<TeamMember> team) {
        this(uuid, playerName, completedAt, firstEver, isTest, 0L, team, Mode.CLASSIC);
    }

    /** Compatibility overload — pre-2026-05-17 callers without a Mode argument default to CLASSIC. */
    public LegendEntry(UUID uuid, String playerName, long completedAt, boolean firstEver,
                       boolean isTest, long bestTimeMs, List<TeamMember> team) {
        this(uuid, playerName, completedAt, firstEver, isTest, bestTimeMs, team, Mode.CLASSIC);
    }

    /** Returns a copy of this entry with a new bestTime (used when a player improves their chrono). */
    public LegendEntry withBestTime(long ms) {
        return new LegendEntry(uuid, playerName, completedAt, firstEver, isTest, ms, team, mode);
    }

    /** Returns a copy of this entry with a new mode (used when the in-flight mode is determined late). */
    public LegendEntry withMode(Mode m) {
        return new LegendEntry(uuid, playerName, completedAt, firstEver, isTest, bestTimeMs, team, m);
    }

    public record TeamMember(
            String species,        // Cobblemon species id, base form (e.g. "garchomp")
            String displayName,    // "Garchomp"
            String nickname,       // "" if no nickname
            int level,
            boolean shiny,
            String ball,           // "poke", "ultra", "" if unknown
            String nature,         // "adamant", "" if unknown
            String gender,         // "MALE" / "FEMALE" / "GENDERLESS" / ""
            String form,           // form aspect e.g. "mega", "alola", "" if base form
            List<String> aspects   // raw Cobblemon aspects list (regional/form/cosmetic)
    ) {
        /** Convenience overload for entries without form/aspects info. */
        public TeamMember(String species, String displayName, String nickname, int level,
                          boolean shiny, String ball, String nature, String gender) {
            this(species, displayName, nickname, level, shiny, ball, nature, gender, "", new ArrayList<>());
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("species", species);
            o.addProperty("displayName", displayName);
            o.addProperty("nickname", nickname);
            o.addProperty("level", level);
            o.addProperty("shiny", shiny);
            o.addProperty("ball", ball);
            o.addProperty("nature", nature);
            o.addProperty("gender", gender);
            o.addProperty("form", form);
            JsonArray asp = new JsonArray();
            for (String a : aspects) asp.add(a);
            o.add("aspects", asp);
            return o;
        }

        public static TeamMember fromJson(JsonObject o) {
            List<String> aspects = new ArrayList<>();
            if (o.has("aspects")) {
                for (var el : o.getAsJsonArray("aspects")) aspects.add(el.getAsString());
            }
            return new TeamMember(
                    o.has("species") ? o.get("species").getAsString() : "",
                    o.has("displayName") ? o.get("displayName").getAsString() : "",
                    o.has("nickname") ? o.get("nickname").getAsString() : "",
                    o.has("level") ? o.get("level").getAsInt() : 0,
                    o.has("shiny") && o.get("shiny").getAsBoolean(),
                    o.has("ball") ? o.get("ball").getAsString() : "",
                    o.has("nature") ? o.get("nature").getAsString() : "",
                    o.has("gender") ? o.get("gender").getAsString() : "",
                    o.has("form") ? o.get("form").getAsString() : "",
                    aspects
            );
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("playerName", playerName);
        o.addProperty("completedAt", completedAt);
        o.addProperty("firstEver", firstEver);
        if (isTest) o.addProperty("isTest", true); // omit when false to keep history clean
        if (bestTimeMs > 0) o.addProperty("bestTimeMs", bestTimeMs);
        // Always serialize mode so downstream board readers don't have to default-guess.
        o.addProperty("mode", mode == null ? "classic" : mode.name().toLowerCase(java.util.Locale.ROOT));
        JsonArray arr = new JsonArray();
        for (TeamMember m : team) arr.add(m.toJson());
        o.add("team", arr);
        return o;
    }

    public static LegendEntry fromJson(JsonObject o) {
        List<TeamMember> team = new ArrayList<>();
        if (o.has("team")) {
            for (var el : o.getAsJsonArray("team")) {
                team.add(TeamMember.fromJson(el.getAsJsonObject()));
            }
        }
        return new LegendEntry(
                UUID.fromString(o.get("uuid").getAsString()),
                o.get("playerName").getAsString(),
                o.get("completedAt").getAsLong(),
                o.has("firstEver") && o.get("firstEver").getAsBoolean(),
                o.has("isTest") && o.get("isTest").getAsBoolean(),
                o.has("bestTimeMs") ? o.get("bestTimeMs").getAsLong() : 0L,
                team,
                Mode.fromKey(o.has("mode") ? o.get("mode").getAsString() : null)
        );
    }
}
