package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
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
     * For per-player dimensions (cheeseguard:masterId_uuid8), also matches zones from the master dimension.
     */
    public List<Region> getRegionsAt(String world, int x, int y, int z) {
        String masterWorld = getMasterWorldForPerPlayer(world);
        return storage.getRegions().stream()
            .filter(r -> {
                boolean worldMatch = r.getWorld().equals(world)
                    || (masterWorld != null && r.getWorld().equals(masterWorld));
                return worldMatch
                    && x >= r.getMinX() && x <= r.getMaxX()
                    && y >= r.getMinY() && y <= r.getMaxY()
                    && z >= r.getMinZ() && z <= r.getMaxZ();
            })
            .sorted(Comparator.comparingInt(Region::getPriority).reversed())
            .collect(Collectors.toList());
    }

    /**
     * For per-player dimensions (format: cheeseguard:masterConfigId_[8 hex chars]),
     * returns the master world ID. Returns null if not a per-player dimension.
     */
    public static String getMasterWorldForPerPlayer(String worldId) {
        if (worldId == null || !worldId.startsWith("cheeseguard:")) return null;
        String configId = worldId.substring("cheeseguard:".length());
        if (configId.length() <= 9) return null;
        String suffix = configId.substring(configId.length() - 9);
        if (suffix.charAt(0) == '_' && suffix.substring(1).matches("[0-9a-f]{8}")) {
            return "cheeseguard:" + configId.substring(0, configId.length() - 9);
        }
        return null;
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

        UUID uuid = player.getUuid();

        // ─── OP / admin bypass ───────────────────────────────────────────────
        // Players with permission level 2+ bypass protection checks unless in test mode.
        // Exception: non-memberExempt flags (behavioural, e.g. NAMETAG_HIDE) apply to EVERYONE,
        // including OPs, because they control environment behaviour rather than protection.
        if (!testModePlayers.contains(uuid) && player.hasPermissionLevel(2) && flag.isMemberExempt()) {
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

        if (regions.isEmpty()) {
            // No region covers this location — fall back to dimension-level flag set
            return !isDimensionFlagDenied(world, flag);
        }

        // Sort: child regions before their parents (parentId relationship),
        // then by priority descending. This ensures a child zone's explicit flags
        // always beat the parent zone's flags.
        regions.sort((a, b) -> {
            if (a.getId().equals(b.getParentId())) return 1;  // b is child of a → b wins
            if (b.getId().equals(a.getParentId())) return -1; // a is child of b → a wins
            return Integer.compare(b.getPriority(), a.getPriority());
        });

        // PASS 0 — Event runtime flag overrides (PropHunt, timeline effects)
        // These take highest priority: active events can force-deny flags on zones
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        if (em != null) {
            for (Region region : regions) {
                for (ServerEvent event : em.getEvents()) {
                    if (event.getState() == ServerEvent.State.IDLE) continue;
                    // Runtime overrides (PropHunt sets these dynamically)
                    Boolean rtOverride = event.getRuntimeFlagOverride(region.getId() + ":" + flag.getId());
                    if (rtOverride != null) return rtOverride;
                    // Persistent flag overrides (timeline/event config)
                    Map<String, Boolean> zoneFlagMap = event.getFlagOverrides().get(region.getId());
                    if (zoneFlagMap != null) {
                        Boolean persistOverride = zoneFlagMap.get(flag.getId());
                        if (persistOverride != null) return persistOverride;
                    }
                }
            }
        }

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

        // PASS 3 — Dimension-level fallback: deniedFlags from DimensionConfig.
        // Only kicks in when no region/group has an explicit value for this flag.
        return !isDimensionFlagDenied(world, flag);
    }

    /**
     * Simplified check without a player (for environmental events).
     */
    public boolean isFlagAllowed(String world, int x, int y, int z, Flag flag) {
        List<Region> regions = getRegionsAt(world, x, y, z);

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
        // Pass 3: dimension-level fallback
        return !isDimensionFlagDenied(world, flag);
    }

    /**
     * True if the dimension's config has this flag in its deniedFlags set.
     * Per-player dimensions inherit their master dim's denied flags.
     */
    private boolean isDimensionFlagDenied(String world, Flag flag) {
        var mod = CheeseGuardMod.getInstance();
        if (mod == null || mod.getDimensionManager() == null) return false;
        var dm = mod.getDimensionManager();
        // 1. Config of the exact world the player is in.
        var cfg = dm.getConfigByWorldId(world);
        if (cfg != null && cfg.isFlagDenied(flag.getId())) return true;
        // 2. Per-player dimension instances (cheeseguard:master_<8hex>) ALSO inherit the
        //    master dim's denied flags — even when an instance config already exists.
        //    The instance config is usually an empty builtIn stub, so the old "only fall
        //    back when cfg == null" logic never consulted the master where flags are set.
        String master = getMasterWorldForPerPlayer(world);
        if (master != null && !master.equals(world)) {
            var masterCfg = dm.getConfigByWorldId(master);
            if (masterCfg != null && masterCfg.isFlagDenied(flag.getId())) return true;
        }
        return false;
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
