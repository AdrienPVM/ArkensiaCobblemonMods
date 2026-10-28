package com.cheesemusic.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Client-side zone music player with sequential transitions (never overlap).
 *
 * State machine:
 *   IDLE → FADING_RM_OUT → PLAYING → STOPPING → FADING_RM_IN → IDLE
 *
 * Enter zone: RM fades out (fadeInTicks) → zone music starts at full volume
 * Exit zone:  zone music stops → RM fades in (fadeOutTicks)
 */
public class ZoneMusicPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheesemusic");
    private static final ZoneMusicPlayer INSTANCE = new ZoneMusicPlayer();
    private static final Random RANDOM = new Random();
    private static final int COOLDOWN_TICKS = 100;

    private enum State { IDLE, FADING_RM_OUT, PLAYING, FADING_RM_IN }

    private State state = State.IDLE;
    private ZoneMusicSoundInstance currentSound;
    private int trackStartCooldown = 0;

    // Playlist
    private List<String> tracks = new ArrayList<>();
    private String playMode = "RANDOM";
    private int currentTrackIndex = -1;
    private float playlistVolume = 0.7f;
    private int fadeOutTicks = 60;  // saved for exit transition

    // Pending play request (received while in another state)
    private String pendingTracksJson;
    private float pendingVolume;
    private int pendingFadeInTicks;
    private int pendingFadeOutTicks;

    public static ZoneMusicPlayer getInstance() { return INSTANCE; }

    /** Active = zone music is playing OR transitioning (RM should be suppressed). */
    public boolean isActive() { return state != State.IDLE; }

    /**
     * Server says: play zone music.
     * @param fadeTicks used as RM fade-out duration before zone music starts
     */
    public void play(String tracksJson, float volume, int fadeTicks) {
        if (tracksJson == null || tracksJson.isEmpty()) return;

        // Parse fadeOutTicks from the assignment (passed via fadeTicks param from server)
        // The server sends fadeInTicks in the packet; fadeOutTicks is stored in the assignment
        // We'll use the same value for both directions as approximation
        int rmFadeOutDuration = Math.max(fadeTicks, 20);

        switch (state) {
            case IDLE:
                // Start transition: fade RM out, then play
                parsePlaylist(tracksJson);
                if (tracks.isEmpty()) return;
                playlistVolume = volume;
                fadeOutTicks = rmFadeOutDuration;
                ReactiveMusicHook.startFadeOut(rmFadeOutDuration);
                try { MinecraftClient.getInstance().getMusicTracker().stop(); } catch (Exception ignored) {}
                state = State.FADING_RM_OUT;
                break;

            case FADING_RM_OUT:
            case PLAYING:
                // Already in zone music — update playlist for next track
                parsePlaylist(tracksJson);
                playlistVolume = volume;
                fadeOutTicks = rmFadeOutDuration;
                break;

            case FADING_RM_IN:
                // Was leaving, but re-entered — save and restart sequence
                pendingTracksJson = tracksJson;
                pendingVolume = volume;
                pendingFadeInTicks = rmFadeOutDuration;
                pendingFadeOutTicks = rmFadeOutDuration;
                break;
        }
    }

    /**
     * Server says: stop zone music.
     * @param fadeTicks used as RM fade-in duration after zone music stops
     */
    public void stop(int fadeTicks) {
        pendingTracksJson = null;

        switch (state) {
            case PLAYING:
                // Stop zone music, then fade RM back in
                stopZoneMusic();
                ReactiveMusicHook.startFadeIn(Math.max(fadeTicks, 20));
                state = State.FADING_RM_IN;
                break;

            case FADING_RM_OUT:
                // RM was fading out, cancel and go back
                ReactiveMusicHook.startFadeIn(Math.max(fadeTicks, 20));
                state = State.FADING_RM_IN;
                break;

            case FADING_RM_IN:
            case IDLE:
                // Already stopping or idle
                break;
        }
    }

    public void fadeTo(float volume, int fadeTicks) {
        // Not supported with streaming sounds — just update target volume
        playlistVolume = volume;
    }

    public void tick() {
        // Always tick RM hook (handles fading + enforcement)
        ReactiveMusicHook.tick();

        switch (state) {
            case IDLE:
                break;

            case FADING_RM_OUT:
                // Wait for RM to finish fading out
                if (ReactiveMusicHook.isFadeOutDone()) {
                    // RM is silent — start zone music
                    currentTrackIndex = pickNextIndex();
                    if (currentTrackIndex >= 0) {
                        playTrack(tracks.get(currentTrackIndex));
                        trackStartCooldown = COOLDOWN_TICKS;
                        state = State.PLAYING;
                    } else {
                        // No tracks — go back
                        ReactiveMusicHook.startFadeIn(fadeOutTicks);
                        state = State.FADING_RM_IN;
                    }
                }
                break;

            case PLAYING:
                // Cooldown after track start (ResourceManager reload protection)
                if (trackStartCooldown > 0) {
                    trackStartCooldown--;
                    if (trackStartCooldown == 0 && currentSound != null) {
                        SoundManager sm = MinecraftClient.getInstance().getSoundManager();
                        if (!sm.isPlaying(currentSound)) {
                            // Sound died during cooldown — restart
                            playTrack(tracks.get(currentTrackIndex));
                            trackStartCooldown = COOLDOWN_TICKS;
                        }
                    }
                    break;
                }
                // Check if track finished → next track
                if (currentSound != null) {
                    SoundManager sm = MinecraftClient.getInstance().getSoundManager();
                    if (!sm.isPlaying(currentSound)) {
                        advancePlaylist();
                    }
                }
                break;

            case FADING_RM_IN:
                // Wait for RM to finish fading in
                if (ReactiveMusicHook.isFadeInDone()) {
                    state = State.IDLE;
                    // Check if a new play request came in while we were fading
                    if (pendingTracksJson != null) {
                        String json = pendingTracksJson;
                        float vol = pendingVolume;
                        int fade = pendingFadeInTicks;
                        pendingTracksJson = null;
                        play(json, vol, fade);
                    }
                }
                break;
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void parsePlaylist(String tracksJson) {
        try {
            JsonObject obj = JsonParser.parseString(tracksJson).getAsJsonObject();
            tracks.clear();
            JsonArray arr = obj.getAsJsonArray("tracks");
            for (int i = 0; i < arr.size(); i++) {
                tracks.add(arr.get(i).getAsString());
            }
            playMode = obj.has("mode") ? obj.get("mode").getAsString() : "RANDOM";
            if (playMode.equals("SHUFFLE")) {
                Collections.shuffle(tracks, RANDOM);
            }
            currentTrackIndex = -1;
        } catch (Exception e) {
            LOGGER.warn("[CheeseMusic] Failed to parse tracks: {}", e.getMessage());
            tracks.clear();
        }
    }

    private void playTrack(String soundId) {
        try {
            SoundManager sm = MinecraftClient.getInstance().getSoundManager();
            if (currentSound != null) sm.stop(currentSound);

            Identifier id = Identifier.of(soundId);
            currentSound = new ZoneMusicSoundInstance(id, SoundCategory.MUSIC, playlistVolume);
            sm.play(currentSound);
        } catch (Exception e) {
            LOGGER.warn("[CheeseMusic] Failed to play {}: {}", soundId, e.getMessage());
        }
    }

    private void advancePlaylist() {
        if (tracks.isEmpty()) return;

        if (playMode.equals("SHUFFLE") && currentTrackIndex >= tracks.size() - 1) {
            Collections.shuffle(tracks, RANDOM);
            currentTrackIndex = -1;
        }

        currentTrackIndex = pickNextIndex();
        if (currentTrackIndex >= 0) {
            playTrack(tracks.get(currentTrackIndex));
            // No cooldown between playlist tracks
        }
    }

    private int pickNextIndex() {
        if (tracks.isEmpty()) return -1;
        if (tracks.size() == 1) return 0;
        switch (playMode) {
            case "SEQUENTIAL":
            case "SHUFFLE":
                return (currentTrackIndex + 1) % tracks.size();
            case "RANDOM":
            default:
                if (tracks.size() == 2) return currentTrackIndex == 0 ? 1 : 0;
                int next;
                do { next = RANDOM.nextInt(tracks.size()); } while (next == currentTrackIndex);
                return next;
        }
    }

    private void stopZoneMusic() {
        if (currentSound != null) {
            try { MinecraftClient.getInstance().getSoundManager().stop(currentSound); }
            catch (Exception ignored) {}
            currentSound = null;
        }
        tracks.clear();
        currentTrackIndex = -1;
        trackStartCooldown = 0;
    }

    /** Reset all state (disconnect — instant, no fade). */
    public void reset() {
        stopZoneMusic();
        pendingTracksJson = null;
        state = State.IDLE;
        ReactiveMusicHook.forceReset();
    }
}
