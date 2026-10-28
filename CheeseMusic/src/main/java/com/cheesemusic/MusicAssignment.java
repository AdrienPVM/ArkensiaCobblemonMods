package com.cheesemusic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class MusicAssignment {

    private String zoneId;
    private String zoneName;
    private List<String> tracks;
    private PlayMode mode;
    private float volume;
    private int fadeInTicks;
    private int fadeOutTicks;

    private static final Random RANDOM = new Random();

    public MusicAssignment(String zoneId, String zoneName, List<String> tracks,
                           PlayMode mode, float volume, int fadeInTicks, int fadeOutTicks) {
        this.zoneId = zoneId;
        this.zoneName = zoneName;
        this.tracks = new ArrayList<>(tracks);
        this.mode = mode;
        this.volume = volume;
        this.fadeInTicks = fadeInTicks;
        this.fadeOutTicks = fadeOutTicks;
    }

    public String getZoneId() { return zoneId; }
    public String getZoneName() { return zoneName; }
    public List<String> getTracks() { return Collections.unmodifiableList(tracks); }
    public PlayMode getMode() { return mode; }
    public float getVolume() { return volume; }
    public int getFadeInTicks() { return fadeInTicks; }
    public int getFadeOutTicks() { return fadeOutTicks; }

    public void setZoneName(String zoneName) { this.zoneName = zoneName; }
    public void setTracks(List<String> tracks) { this.tracks = new ArrayList<>(tracks); }
    public void setMode(PlayMode mode) { this.mode = mode; }
    public void setVolume(float volume) { this.volume = volume; }
    public void setFadeInTicks(int fadeInTicks) { this.fadeInTicks = fadeInTicks; }
    public void setFadeOutTicks(int fadeOutTicks) { this.fadeOutTicks = fadeOutTicks; }

    /**
     * Pick the next track index based on mode.
     * @param lastIndex the last played index (-1 if none)
     * @return the index in tracks list to play next
     */
    public int pickTrackIndex(int lastIndex) {
        if (tracks.isEmpty()) return -1;
        if (tracks.size() == 1) return 0;

        switch (mode) {
            case SEQUENTIAL:
                return (lastIndex + 1) % tracks.size();
            case RANDOM:
                if (tracks.size() == 2) return lastIndex == 0 ? 1 : 0;
                int next;
                do { next = RANDOM.nextInt(tracks.size()); } while (next == lastIndex);
                return next;
            case SHUFFLE:
                return (lastIndex + 1) % tracks.size();
            default:
                return 0;
        }
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("zoneId", zoneId);
        obj.addProperty("zoneName", zoneName);
        JsonArray arr = new JsonArray();
        tracks.forEach(arr::add);
        obj.add("tracks", arr);
        obj.addProperty("mode", mode.name());
        obj.addProperty("volume", volume);
        obj.addProperty("fadeInTicks", fadeInTicks);
        obj.addProperty("fadeOutTicks", fadeOutTicks);
        return obj;
    }

    public static MusicAssignment fromJson(JsonObject obj) {
        String zoneId = obj.get("zoneId").getAsString();
        String zoneName = obj.has("zoneName") ? obj.get("zoneName").getAsString() : zoneId;
        List<String> tracks = new ArrayList<>();
        if (obj.has("tracks")) {
            obj.getAsJsonArray("tracks").forEach(e -> tracks.add(e.getAsString()));
        }
        PlayMode mode = PlayMode.RANDOM;
        if (obj.has("mode")) {
            try { mode = PlayMode.valueOf(obj.get("mode").getAsString()); }
            catch (IllegalArgumentException ignored) {}
        }
        float volume = obj.has("volume") ? obj.get("volume").getAsFloat() : 0.7f;
        int fadeIn = obj.has("fadeInTicks") ? obj.get("fadeInTicks").getAsInt() : 60;
        int fadeOut = obj.has("fadeOutTicks") ? obj.get("fadeOutTicks").getAsInt() : 60;
        return new MusicAssignment(zoneId, zoneName, tracks, mode, volume, fadeIn, fadeOut);
    }
}
