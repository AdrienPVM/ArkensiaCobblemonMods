package com.cobblecap.logic;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which UUIDs belong to {@link ChampionDoppelganger} packet entities.
 * Lives in the common source set so the client mixin can resolve it without
 * a separate client-side singleton; the server populates the set when it
 * spawns / removes a doppel, and the client mixin reads it to know which
 * player models to render in a frozen pose.
 */
public final class SyntheticUuidRegistry {

    private static final Set<UUID> SYNTH = ConcurrentHashMap.newKeySet();

    private SyntheticUuidRegistry() {}

    public static void add(UUID uuid)    { if (uuid != null) SYNTH.add(uuid); }
    public static void remove(UUID uuid) { if (uuid != null) SYNTH.remove(uuid); }
    public static boolean contains(UUID uuid) { return uuid != null && SYNTH.contains(uuid); }
    public static void clear() { SYNTH.clear(); }
}
