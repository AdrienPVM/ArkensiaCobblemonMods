package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Soft-dependency hook for CobbleShift mod.
 * Uses reflection to call CobbleShift's TransformManager without compile-time dependency.
 * Handles: TRANSFORM_ALL, TRANSFORM_RANDOM, TRANSFORM_BY_TEAM, TRANSFORM_REVERT_ALL
 */
public class CobbleShiftHook {

    private static boolean initialized = false;
    private static boolean available = false;

    // Cached reflection targets
    private static Object transformManagerInstance;
    private static Method transformMethod;      // transform(ServerPlayerEntity, String) -> boolean
    private static Method transformAttksMethod; // transform(ServerPlayerEntity, String, boolean) -> boolean
    private static Method revertMethod;         // revert(ServerPlayerEntity) -> boolean
    private static Method revertAllMethod;      // revertAll() -> void

    /**
     * Attempt to initialize CobbleShift reflection hooks.
     * Call once during EventManager.init().
     */
    public static void tryInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> modClass = Class.forName("com.cobbleshift.CobbleShiftMod");
            Object modInstance = modClass.getField("INSTANCE").get(null);
            if (modInstance == null) {
                CheeseGuardMod.LOGGER.info("[CG] CobbleShift detected but not yet initialized");
                return;
            }

            Method getTransformManager = modClass.getMethod("getTransformManager");
            transformManagerInstance = getTransformManager.invoke(modInstance);

            Class<?> tmClass = transformManagerInstance.getClass();
            transformMethod = tmClass.getMethod("transform", ServerPlayerEntity.class, String.class);
            transformAttksMethod = tmClass.getMethod("transform", ServerPlayerEntity.class, String.class, boolean.class);
            revertMethod = tmClass.getMethod("revert", ServerPlayerEntity.class);
            revertAllMethod = tmClass.getMethod("revertAll");

            available = true;
            CheeseGuardMod.LOGGER.info("[CG] CobbleShift detected — transform effects enabled");
        } catch (ClassNotFoundException e) {
            CheeseGuardMod.LOGGER.info("[CG] CobbleShift not installed — transform effects disabled");
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] CobbleShift hook failed: {}", e.getMessage());
        }
    }

    public static boolean isAvailable() { return available; }

    /**
     * Transform a player into a Pokémon species (attacks enabled by default).
     * @return true if successful
     */
    public static boolean transformPlayer(ServerPlayerEntity player, String species) {
        if (!available) return false;
        try {
            return (boolean) transformMethod.invoke(transformManagerInstance, player, species);
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.debug("[CG] CobbleShift transform failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Transform a player into a Pokémon species with attack control.
     * @param attacksEnabled if false, projectile attacks are disabled for this transform
     * @return true if successful
     */
    public static boolean transformPlayer(ServerPlayerEntity player, String species, boolean attacksEnabled) {
        if (!available) return false;
        try {
            return (boolean) transformAttksMethod.invoke(transformManagerInstance, player, species, attacksEnabled);
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.debug("[CG] CobbleShift transform (attacks={}) failed: {}", attacksEnabled, e.getMessage());
            return false;
        }
    }

    /**
     * Revert a player's transformation.
     */
    public static boolean revertPlayer(ServerPlayerEntity player) {
        if (!available) return false;
        try {
            return (boolean) revertMethod.invoke(transformManagerInstance, player);
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.debug("[CG] CobbleShift revert failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Revert all active transformations.
     */
    public static void revertAll() {
        if (!available) return;
        try {
            revertAllMethod.invoke(transformManagerInstance);
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.debug("[CG] CobbleShift revertAll failed: {}", e.getMessage());
        }
    }

    /**
     * Transform all participants into the same species.
     */
    public static void transformAll(Collection<ServerPlayerEntity> participants, String species) {
        if (!available) return;
        for (ServerPlayerEntity player : participants) {
            transformPlayer(player, species);
        }
    }

    /**
     * Transform each participant into a random species from the list.
     * @param speciesCsv comma-separated list of species names
     * @param allowDuplicates if false, each species is used at most once
     */
    public static void transformRandom(Collection<ServerPlayerEntity> participants, String speciesCsv, boolean allowDuplicates) {
        if (!available || speciesCsv == null || speciesCsv.isBlank()) return;

        List<String> speciesList = new ArrayList<>(Arrays.asList(speciesCsv.split(",")));
        speciesList.replaceAll(String::trim);
        speciesList.removeIf(String::isEmpty);

        if (speciesList.isEmpty()) return;

        Random random = new Random();
        List<String> pool = new ArrayList<>(speciesList);

        for (ServerPlayerEntity player : participants) {
            if (pool.isEmpty()) {
                if (allowDuplicates) {
                    pool = new ArrayList<>(speciesList); // refill
                } else {
                    break; // no more species available
                }
            }
            int idx = random.nextInt(pool.size());
            String species = pool.get(idx);
            if (!allowDuplicates) pool.remove(idx);
            transformPlayer(player, species);
        }
    }

    /**
     * Transform participants based on their team assignment.
     * @param teamMapJson JSON string: {"teamName":"speciesName", ...}
     * @param participants all event participants
     * @param teamResolver function that returns the team name for a player (from EventManager)
     */
    public static void transformByTeam(Collection<ServerPlayerEntity> participants,
                                        String teamMapJson,
                                        java.util.function.Function<ServerPlayerEntity, String> teamResolver) {
        if (!available || teamMapJson == null || teamMapJson.isBlank()) return;

        try {
            com.google.gson.JsonObject map = com.google.gson.JsonParser.parseString(teamMapJson).getAsJsonObject();
            for (ServerPlayerEntity player : participants) {
                String team = teamResolver.apply(player);
                if (team != null && map.has(team)) {
                    String species = map.get(team).getAsString();
                    transformPlayer(player, species);
                }
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] CobbleShift transformByTeam parse error: {}", e.getMessage());
        }
    }

    // ── Query active transforms ─────────────────────────────────────────────

    private static Method getTransformDataMethod;
    private static Method getSpeciesNameMethod;

    /**
     * Get the species name of all currently-transformed players from a set of UUIDs.
     * @return map of UUID -> species name for players that are currently transformed
     */
    public static Map<UUID, String> getActiveTransformSpecies(Collection<UUID> uuids) {
        if (!available || transformManagerInstance == null) return Collections.emptyMap();
        try {
            // Lazy-init reflection for getTransformData(UUID)
            if (getTransformDataMethod == null) {
                getTransformDataMethod = transformManagerInstance.getClass().getMethod("getTransformData", UUID.class);
            }
            Map<UUID, String> result = new HashMap<>();
            for (UUID uuid : uuids) {
                Object data = getTransformDataMethod.invoke(transformManagerInstance, uuid);
                if (data != null) {
                    if (getSpeciesNameMethod == null) {
                        getSpeciesNameMethod = data.getClass().getMethod("getSpeciesName");
                    }
                    String species = (String) getSpeciesNameMethod.invoke(data);
                    if (species != null && !species.isBlank()) {
                        result.put(uuid, species);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.debug("[CG] CobbleShift getActiveTransformSpecies failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ── Pending reverts for offline players ────────────────────────────────────

    private static final Set<UUID> pendingReverts = Collections.synchronizedSet(new HashSet<>());
    private static Path pendingRevertsPath;

    /** Load pending reverts from disk. Call once during server init. */
    public static void loadPendingReverts(Path configDir) {
        pendingRevertsPath = configDir.resolve("cheeseguard_pending_reverts.json");
        if (Files.exists(pendingRevertsPath)) {
            try {
                String json = Files.readString(pendingRevertsPath);
                List<String> uuids = new Gson().fromJson(json, new TypeToken<List<String>>(){}.getType());
                if (uuids != null) {
                    uuids.forEach(s -> pendingReverts.add(UUID.fromString(s)));
                }
                CheeseGuardMod.LOGGER.info("[CG] Loaded {} pending CobbleShift reverts", pendingReverts.size());
            } catch (Exception e) {
                CheeseGuardMod.LOGGER.warn("[CG] Failed to load pending reverts: {}", e.getMessage());
            }
        }
    }

    private static void savePendingReverts() {
        if (pendingRevertsPath == null) return;
        try {
            List<String> uuids = pendingReverts.stream().map(UUID::toString).toList();
            Files.writeString(pendingRevertsPath, new Gson().toJson(uuids));
        } catch (IOException e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to save pending reverts: {}", e.getMessage());
        }
    }

    /** Mark a player for revert on next join (when they're offline during event cleanup). */
    public static void addPendingRevert(UUID playerUuid) {
        pendingReverts.add(playerUuid);
        savePendingReverts();
    }

    /** Check and apply pending revert when a player joins. Call from player join event. */
    public static void onPlayerJoin(ServerPlayerEntity player) {
        if (pendingReverts.remove(player.getUuid())) {
            if (available) {
                revertPlayer(player);
                CheeseGuardMod.LOGGER.info("[CG] Applied pending CobbleShift revert for {}", player.getName().getString());
            }
            savePendingReverts();
        }
    }

    /** Clear all pending reverts. */
    public static void clearPendingReverts() {
        pendingReverts.clear();
        savePendingReverts();
    }
}
