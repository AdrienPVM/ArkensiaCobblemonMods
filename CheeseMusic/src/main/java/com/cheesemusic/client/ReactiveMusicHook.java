package com.cheesemusic.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Soft-dependency hook for ReactiveMusic (client-side).
 * Controls RM volume via gainPercentage + additionalSilence.
 * Supports timed fade-out and fade-in with completion detection.
 */
public class ReactiveMusicHook {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheesemusic");

    private static boolean initialized = false;
    private static boolean available = false;

    private static Object playerThread;
    private static Field gainPercentageField;
    private static Method resetPlayerMethod;
    private static Field additionalSilenceField;

    // Fade state
    private static float currentGain = 1.0f;
    private static float savedGain = 1.0f;
    private static int savedSilence = 0;
    private static float fadeTarget = 1.0f;
    private static float fadeStep = 0f;
    private static boolean fading = false;
    private static boolean suppressed = false;

    public static void tryInit() {
        if (initialized) return;
        initialized = true;
        doInit();
    }

    private static void doInit() {
        try {
            Class<?> rmClass = Class.forName("circuitlord.reactivemusic.ReactiveMusic");
            Field threadField = rmClass.getField("thread");
            playerThread = threadField.get(null);
            if (playerThread == null) return;

            Class<?> ptClass = playerThread.getClass();
            gainPercentageField = ptClass.getField("gainPercentage");
            resetPlayerMethod = ptClass.getMethod("resetPlayer");
            additionalSilenceField = rmClass.getField("additionalSilence");

            available = true;
            LOGGER.info("[CheeseMusic] ReactiveMusic detected — cooperative control enabled");
        } catch (ClassNotFoundException e) {
            LOGGER.info("[CheeseMusic] ReactiveMusic not installed");
        } catch (Exception e) {
            LOGGER.warn("[CheeseMusic] ReactiveMusic hook failed: {}", e.getMessage());
        }
    }

    private static void ensureThread() {
        if (available) return;
        doInit();
    }

    public static boolean isAvailable() {
        if (!available) ensureThread();
        return available;
    }

    /**
     * Start fading RM out (entering zone). Call once.
     * @param ticks duration of fade (from assignment fadeInTicks)
     */
    public static void startFadeOut(int ticks) {
        if (!isAvailable()) return;
        try {
            savedGain = gainPercentageField.getFloat(playerThread);
            if (savedGain <= 0f) savedGain = 1.0f;
            savedSilence = additionalSilenceField.getInt(null);

            // Block RM from picking new songs
            additionalSilenceField.setInt(null, 999999);

            currentGain = savedGain;
            fadeTarget = 0f;
            fadeStep = savedGain / Math.max(ticks, 1);
            fading = true;
            suppressed = true;
        } catch (Exception e) {
            LOGGER.warn("[CheeseMusic] Failed to start RM fadeOut: {}", e.getMessage());
        }
    }

    /**
     * Start fading RM back in (leaving zone). Call once.
     * @param ticks duration of fade (from assignment fadeOutTicks)
     */
    public static void startFadeIn(int ticks) {
        if (!isAvailable()) return;
        try {
            // Restore silence so RM can pick songs again
            additionalSilenceField.setInt(null, savedSilence);
            // Reset player so RM picks a fresh song
            resetPlayerMethod.invoke(playerThread);
        } catch (Exception ignored) {}

        currentGain = 0f;
        fadeTarget = savedGain;
        fadeStep = Math.max(savedGain, 0.1f) / Math.max(ticks, 1);
        fading = true;
        // suppressed stays true until fade-in completes
    }

    /**
     * Tick the fade. Call every client tick.
     * Returns true when suppression is active (RM should be silent).
     */
    public static void tick() {
        if (!available) return;

        try {
            if (fading) {
                // Progress fade
                if (fadeTarget < currentGain) {
                    // Fading out
                    currentGain = Math.max(currentGain - fadeStep, 0f);
                } else {
                    // Fading in
                    currentGain = Math.min(currentGain + fadeStep, fadeTarget);
                }
                gainPercentageField.setFloat(playerThread, currentGain);

                // Check completion
                if (Math.abs(currentGain - fadeTarget) < 0.001f) {
                    fading = false;
                    currentGain = fadeTarget;
                    gainPercentageField.setFloat(playerThread, currentGain);

                    if (fadeTarget <= 0f) {
                        // Fade-out complete — stop RM's current song
                        try { resetPlayerMethod.invoke(playerThread); } catch (Exception ignored) {}
                    } else {
                        // Fade-in complete — RM fully restored
                        suppressed = false;
                    }
                }
            } else if (suppressed) {
                // Keep enforcing silence (RM can reset gain when picking songs)
                additionalSilenceField.setInt(null, 999999);
                gainPercentageField.setFloat(playerThread, 0.0f);
            }
        } catch (Exception ignored) {}
    }

    /** True when RM fade-out is complete (gain=0, ready for zone music). */
    public static boolean isFadeOutDone() {
        return suppressed && !fading && currentGain <= 0.001f;
    }

    /** True when RM fade-in is complete (fully restored). */
    public static boolean isFadeInDone() {
        return !suppressed && !fading;
    }

    /** True when actively controlling RM. */
    public static boolean isSuppressed() { return suppressed; }

    /** Instant reset (disconnect). */
    public static void forceReset() {
        if (!available) return;
        try {
            additionalSilenceField.setInt(null, savedSilence);
            gainPercentageField.setFloat(playerThread, savedGain);
        } catch (Exception ignored) {}
        currentGain = savedGain;
        fading = false;
        suppressed = false;
    }
}
