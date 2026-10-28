package com.cheeseguard.logic;

import com.cheeseguard.data.*;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central manager: resolves which regions apply at a location and
 * whether a given action (flag) is allowed.
 */
public class RegionManager {

    private final RegionStorage storage;
    // Players in "test mode" — bypasses membership check so they experience flags like a normal player
    private final Set<UUID> testModePlayers = new HashSet<>();

    public RegionManager(RegionStorage storage) {
        this.storage = storage;
    }

    public RegionStorage getStorage() { return storage; }

    public boolean isTestMode(UUID uuid) { return testModePlayers.contains(uuid); }
    public boolean toggleTestMode(UUID uuid) {
        if (testModePlayers.contains(uuid)) { testModePlayers.remove(uuid); return false; }
        else { testModePlayers.add(uuid); return true; }
    }

    // ── Region lookup ────────────────────────────────────────────────────────

    /**
     * Returns all regions that contain the given position, sorted by priority (highest first).
     */
    public List<Region> getRegionsAt(String world, int x, int y, int z) {
        return storage.getRegions().stream()
            .filter(r -> r.contains(world, x, y, z))
            .sorted(Comparator.comparingInt(Region::getPriority).reversed())
            .collect(Collectors.toList());
    }

    // ── Permission check ─────────────────────────────────────────────────────

    /**
     * Returns true if the action is allowed.
     *
     * Resolution order:
     *  1. No region at location → allowed
     *  2. Player is OP → allowed
     *  3. Player is member of ANY region covering this location → allowed
     *  4. Sort regions: child regions beat their parents (parentId), then by priority.
     *  5. PASS 1 – Check ALL explicit region flags first.
     *     This ensures a child zone's "allow" beats a group's "deny" from another
     *     overlapping zone that happens to have no explicit flag of its own.
     *  6. PASS 2 – Fall back to group flags by effective priority.
     *  7. No rule → allowed
     */
    public boolean isAllowed(ServerPlayerEntity player, String world, int x, int y, int z, Flag flag) {
        List<Region> regions = getRegionsAt(world, x, y, z);

        if (regions.isEmpty()) return true;

        UUID uuid = player.getUuid();

        // ─── OP / admin bypass ───────────────────────────────────────────────
        // Players with permission level 2+ bypass ALL checks unless in test mode.
        // This fixes the case where an admin/owner gets denied by ENTRY=deny, etc.
        if (!testModePlayers.contains(uuid) && player.hasPermissionLevel(2)) {
            return true;
        }

        // Members/owners are exempt from PROTECTION flags (block-break, pvp, etc.)
        // but NOT from BEHAVIOURAL flags (cobblemon-summon, fly, fall-damage, etc.)
        // Flag.isMemberExempt() controls which category a flag belongs to.
        if (flag.isMemberExempt() && !testModePlayers.contains(uuid)) {
            for (Region r : regions) {
                if (r.isMember(uuid)) return true;
            }
        }

        // Sort: child regions before their parents (parentId relationship),
        // then by priority descending. This ensures a child zone's explicit flags
        // always beat the parent zone's flags.
        regions.sort((a, b) -> {
            if (a.getId().equals(b.getParentId())) return 1;  // b is child of a → b wins
            if (b.getId().equals(a.getParentId())) return -1; // a is child of b → a wins
            return Integer.compare(b.getPriority(), a.getPriority());
        });

        // PASS 1 — Check all explicit region flags first (highest effective priority).
        // This ensures a zone with an explicit flag always beats a group flag from
        // another overlapping zone that has no explicit flag of its own.
        for (Region region : regions) {
            Boolean regionFlag = region.getFlag(flag);
            if (regionFlag != null) return regionFlag;
        }

        // PASS 2 — No explicit region flag found; fall back to group flags by priority.
        for (Region region : regions) {
            if (region.getGroupId() != null) {
                RegionGroup group = storage.getGroup(region.getGroupId());
                if (group != null) {
                    Boolean groupFlag = group.getFlag(flag);
                    if (groupFlag != null) return groupFlag;
                }
            }
        }

        return true; // No rule = allowed
    }

    /**
     * Simplified check without a player (for environmental events).
     */
    public boolean isFlagAllowed(String world, int x, int y, int z, Flag flag) {
        List<Region> regions = getRegionsAt(world, x, y, z);
        if (regions.isEmpty()) return true;

        // Pass 1: explicit region flags
        for (Region region : regions) {
            Boolean regionFlag = region.getFlag(flag);
            if (regionFlag != null) return regionFlag;
        }

        // Pass 2: group flags
        for (Region region : regions) {
            if (region.getGroupId() != null) {
                RegionGroup group = storage.getGroup(region.getGroupId());
                if (group != null) {
                    Boolean groupFlag = group.getFlag(flag);
                    if (groupFlag != null) return groupFlag;
                }
            }
        }
        return true;
    }

    /**
     * Returns the protection message for the highest-priority region at a location.
     */
    public String getProtectionMessage(String world, int x, int y, int z) {
        List<Region> regions = getRegionsAt(world, x, y, z);
        if (regions.isEmpty()) return "⛔ Cette zone est protégée !";
        return regions.get(0).getProtectionMessage();
    }
}
