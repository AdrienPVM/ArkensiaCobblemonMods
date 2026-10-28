package com.roicheese.eggandcheese.data;

import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Runtime index of currently-LOADED admin collectible blocks, keyed by registryId.
 *
 * <p>Invariant: contains exactly the positions of every loaded {@code AdminCollectibleBlockEntity}
 * with a non-empty registryId. Maintained by:
 * <ul>
 *   <li>{@code ServerBlockEntityEvents.BLOCK_ENTITY_LOAD} → {@link #add}</li>
 *   <li>{@code ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD} → {@link #remove}</li>
 *   <li>{@code AdminCollectibleBlockEntity.setRegistryId} (runtime, server-side) → add/remove</li>
 * </ul>
 *
 * <p>Because the index only ever holds loaded blocks, any position it contains is guaranteed to be
 * in a loaded chunk — so callers may safely {@code getBlockEntity()} on it without risking a chunk
 * load. This is the backbone of the duplicate-ID reconciliation (one physical block per ID).
 *
 * <p>All access happens on the server thread (BE load/unload events + dashboard via
 * {@code executeOnServerThread}); methods are still {@code synchronized} as a belt-and-suspenders
 * guard against any stray off-thread read.
 */
public final class LiveAdminEggIndex {

    /** A loaded admin-egg block location: dimension id + position. */
    public record EggLoc(String dim, BlockPos pos) {}

    // registryId -> set of loaded positions claiming it
    private static final Map<String, Set<EggLoc>> INDEX = new HashMap<>();

    private LiveAdminEggIndex() {}

    public static synchronized void add(String registryId, String dim, BlockPos pos) {
        if (registryId == null || registryId.isEmpty() || dim == null || pos == null) return;
        INDEX.computeIfAbsent(registryId, k -> new LinkedHashSet<>()).add(new EggLoc(dim, pos.toImmutable()));
    }

    public static synchronized void remove(String registryId, String dim, BlockPos pos) {
        if (registryId == null || registryId.isEmpty() || dim == null || pos == null) return;
        Set<EggLoc> set = INDEX.get(registryId);
        if (set == null) return;
        set.remove(new EggLoc(dim, pos.toImmutable()));
        if (set.isEmpty()) INDEX.remove(registryId);
    }

    /** Snapshot of the loaded positions for one registryId (never null, may be empty). */
    public static synchronized Set<EggLoc> get(String registryId) {
        Set<EggLoc> set = INDEX.get(registryId);
        return set == null ? Collections.emptySet() : new LinkedHashSet<>(set);
    }

    /** Snapshot of the full index (registryId -> loaded positions). */
    public static synchronized Map<String, Set<EggLoc>> snapshot() {
        Map<String, Set<EggLoc>> copy = new LinkedHashMap<>();
        for (var e : INDEX.entrySet()) copy.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        return copy;
    }

    /** All registryIds with more than one loaded physical block (true duplicates among loaded chunks). */
    public static synchronized Set<String> duplicateIds() {
        Set<String> dups = new LinkedHashSet<>();
        for (var e : INDEX.entrySet()) if (e.getValue().size() > 1) dups.add(e.getKey());
        return dups;
    }
}
