package com.cobbleshift;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Maps Cobblemon species data → Minecraft attribute modifiers.
 * Uses reflection to access Cobblemon API without compile-time dependency.
 *
 * Stats: HP, Atk, Def, SpAtk, SpDef, Speed (base values 1-255)
 */
public class SpeciesMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobbleshift");
    private static boolean cobblemonAvailable = false;
    private static Method getByNameMethod;
    private static boolean initialized = false;

    /**
     * Attempt to initialize Cobblemon reflection hooks.
     */
    public static void tryInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> speciesClass = Class.forName("com.cobblemon.mod.common.pokemon.Species");
            Class<?> registryClass = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
            // Try INSTANCE field (Kotlin object pattern)
            Object instance = registryClass.getField("INSTANCE").get(null);
            getByNameMethod = instance.getClass().getMethod("getByName", String.class);
            cobblemonAvailable = true;
            LOGGER.info("[CobbleShift] Cobblemon API detected — species mapping enabled");
        } catch (Exception e) {
            LOGGER.info("[CobbleShift] Cobblemon not found — using fallback stats");
        }
    }

    public static boolean isCobblemonAvailable() { return cobblemonAvailable; }

    /** Cache of all species names (lowercase), populated on first call. */
    private static List<String> cachedSpeciesNames;

    /**
     * Get all species names from the Cobblemon registry.
     * Caches the result for fast autocomplete.
     */
    public static Collection<String> getAllSpeciesNames() {
        if (cachedSpeciesNames != null) return cachedSpeciesNames;
        tryInit();
        if (!cobblemonAvailable) return Collections.emptyList();

        List<String> names = new ArrayList<>();
        try {
            Class<?> registryClass = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
            Object instance = registryClass.getField("INSTANCE").get(null);

            // Try getSpecies() → Collection<Species>
            Collection<?> speciesList = null;
            for (String methodName : new String[]{"getSpecies", "getAll", "species"}) {
                try {
                    Object result = instance.getClass().getMethod(methodName).invoke(instance);
                    if (result instanceof Collection<?> c) {
                        speciesList = c;
                        break;
                    } else if (result instanceof Iterable<?> it) {
                        List<Object> list = new ArrayList<>();
                        it.forEach(list::add);
                        speciesList = list;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {}
            }

            if (speciesList == null) {
                LOGGER.warn("[CobbleShift] Could not find species listing method on PokemonSpecies");
                return Collections.emptyList();
            }

            for (Object species : speciesList) {
                try {
                    // Try getName() → ResourceIdentifier → getPath()
                    Object nameObj = species.getClass().getMethod("getName").invoke(species);
                    if (nameObj != null) {
                        // ResourceIdentifier has getPath(), Identifier has getPath()
                        try {
                            String path = (String) nameObj.getClass().getMethod("getPath").invoke(nameObj);
                            names.add(path.toLowerCase(Locale.ROOT));
                        } catch (NoSuchMethodException e2) {
                            // Fallback: use toString and strip namespace
                            String full = nameObj.toString();
                            int colon = full.indexOf(':');
                            names.add((colon >= 0 ? full.substring(colon + 1) : full).toLowerCase(Locale.ROOT));
                        }
                    }
                } catch (Exception e) {
                    // Try showdownId() as fallback
                    try {
                        String showdownId = (String) species.getClass().getMethod("showdownId").invoke(species);
                        if (showdownId != null) names.add(showdownId.toLowerCase(Locale.ROOT));
                    } catch (Exception ignored) {}
                }
            }

            LOGGER.info("[CobbleShift] Loaded {} species names for autocomplete", names.size());
        } catch (Exception e) {
            LOGGER.warn("[CobbleShift] Failed to load species names: {}", e.getMessage());
        }

        Collections.sort(names);
        cachedSpeciesNames = names;
        return cachedSpeciesNames;
    }

    /**
     * Resolve species data from Cobblemon registry by name.
     * Returns null if species not found.
     */
    public static SpeciesStats resolve(String speciesName) {
        tryInit();
        if (!cobblemonAvailable || speciesName == null) return null;

        try {
            Class<?> registryClass = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
            Object instance = registryClass.getField("INSTANCE").get(null);

            // Try multiple name formats: original, no-hyphens, underscores, lowercase
            String baseName = speciesName.toLowerCase(Locale.ROOT);
            Object species = getByNameMethod.invoke(instance, baseName);
            if (species == null) {
                // Try without hyphens (e.g., "mr-mime" → "mrmime")
                species = getByNameMethod.invoke(instance, baseName.replace("-", ""));
            }
            if (species == null) {
                // Try with underscores (e.g., "mr-mime" → "mr_mime")
                species = getByNameMethod.invoke(instance, baseName.replace("-", "_"));
            }
            if (species == null) return null;

            // Get base stats
            Object baseStats = species.getClass().getMethod("getBaseStats").invoke(species);
            int hp = getStatValue(baseStats, "HP");
            int atk = getStatValue(baseStats, "ATTACK");
            int def = getStatValue(baseStats, "DEFENCE");
            int spAtk = getStatValue(baseStats, "SPECIAL_ATTACK");
            int spDef = getStatValue(baseStats, "SPECIAL_DEFENCE");
            int speed = getStatValue(baseStats, "SPEED");

            // Get types — ElementalType has getName() returning the string name
            Object primaryType = species.getClass().getMethod("getPrimaryType").invoke(species);
            String type1 = extractTypeName(primaryType);

            String type2 = null;
            try {
                Object secondaryType = species.getClass().getMethod("getSecondaryType").invoke(species);
                if (secondaryType != null) {
                    type2 = extractTypeName(secondaryType);
                }
            } catch (Exception ignored) {}

            // Get Pokedex height (decimeters, for reference)
            float height = 1.0f;
            try {
                Object heightObj = species.getClass().getMethod("getHeight").invoke(species);
                if (heightObj instanceof Number n) height = n.floatValue();
            } catch (Exception ignored) {}

            // Get actual hitbox dimensions (in blocks) — the "gray hitbox" visible with F3+B.
            // This is the entity AABB used for collision, Pokeball targeting, etc.
            // EntityDimensions: try record accessor height()/width(), then getHeight()/getWidth(), then fields.
            float hitboxH = 0.6f; // Cobblemon default
            float hitboxW = 0.7f;
            try {
                Object hitbox = species.getClass().getMethod("getHitbox").invoke(species);
                if (hitbox != null) {
                    // Try record accessor height() first (MC 1.21.1 EntityDimensions is a record)
                    float h = readDimensionValue(hitbox, "height");
                    float w = readDimensionValue(hitbox, "width");
                    if (h > 0) hitboxH = h;
                    if (w > 0) hitboxW = w;
                    LOGGER.info("[CobbleShift] Species '{}' hitbox class={}, raw h={}, w={}",
                        speciesName, hitbox.getClass().getSimpleName(), h, w);
                }
            } catch (Exception e) {
                LOGGER.debug("[CobbleShift] getHitbox() failed for '{}': {}", speciesName, e.getMessage());
            }

            // Get baseScale — Cobblemon's visual+dimension scale factor per species
            float baseScale = 1.0f;
            try {
                Object scaleObj = species.getClass().getMethod("getBaseScale").invoke(species);
                if (scaleObj instanceof Number n && n.floatValue() > 0) baseScale = n.floatValue();
            } catch (Exception ignored) {}

            // Effective hitbox height = hitbox * baseScale (matches the gray F3+B hitbox in-game)
            float effectiveHitboxHeight = hitboxH * baseScale;

            LOGGER.info("[CobbleShift] Species '{}': hitbox={}x{}, baseScale={}, effectiveH={}, scale={}",
                speciesName, hitboxW, hitboxH, baseScale, effectiveHitboxHeight,
                mapScale(effectiveHitboxHeight));

            return new SpeciesStats(speciesName, hp, atk, def, spAtk, spDef, speed, type1, type2, height, effectiveHitboxHeight);
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Failed to resolve species '{}': {}", speciesName, e.getMessage());
            return null;
        }
    }

    /**
     * Extract the type name from a Cobblemon ElementalType object.
     * Tries getName(), getDisplayName(), name field, then falls back to toString().
     */
    private static String extractTypeName(Object typeObj) {
        if (typeObj == null) return "unknown";
        // Try getName()
        for (String getter : new String[]{"getName", "getDisplayName", "name"}) {
            try {
                Object result = typeObj.getClass().getMethod(getter).invoke(typeObj);
                if (result instanceof String s && !s.isEmpty()) {
                    return s.toLowerCase(Locale.ROOT);
                }
            } catch (NoSuchMethodException ignored) {}
            catch (Exception ignored) {}
        }
        // Fallback: try to get the simple class-level name
        String s = typeObj.toString();
        // If it looks like a class reference, try to extract the name after the last '.'
        if (s.contains("@") || s.contains("elementaltype")) {
            // Last resort: check for 'name' field directly
            try {
                java.lang.reflect.Field nameField = typeObj.getClass().getDeclaredField("name");
                nameField.setAccessible(true);
                Object val = nameField.get(typeObj);
                if (val instanceof String str && !str.isEmpty()) return str.toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {}
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * Read a dimension value (height or width) from an EntityDimensions object.
     * Tries: record accessor (height()), getter (getHeight()), then field reflection.
     */
    private static float readDimensionValue(Object dims, String name) {
        // Try record-style accessor: height() / width()
        try {
            Object val = dims.getClass().getMethod(name).invoke(dims);
            if (val instanceof Number n && n.floatValue() > 0) return n.floatValue();
        } catch (Exception ignored) {}

        // Try getter: getHeight() / getWidth()
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            Object val = dims.getClass().getMethod(getter).invoke(dims);
            if (val instanceof Number n && n.floatValue() > 0) return n.floatValue();
        } catch (Exception ignored) {}

        // Try field access as last resort
        try {
            Field f = dims.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object val = f.get(dims);
            if (val instanceof Number n && n.floatValue() > 0) return n.floatValue();
        } catch (Exception ignored) {}

        // Scan all float fields for a name match
        for (Field f : dims.getClass().getDeclaredFields()) {
            if ((f.getType() == float.class || f.getType() == Float.class)
                && f.getName().toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                try {
                    f.setAccessible(true);
                    float val = f.getFloat(dims);
                    if (val > 0) return val;
                } catch (Exception ignored) {}
            }
        }
        return -1f;
    }

    @SuppressWarnings("unchecked")
    private static int getStatValue(Object baseStatsMap, String statName) {
        try {
            // baseStats is a Map<Stat, Integer> — iterate to find by name
            if (baseStatsMap instanceof java.util.Map<?, ?> map) {
                for (var entry : map.entrySet()) {
                    if (entry.getKey().toString().toUpperCase().contains(statName)) {
                        return ((Number) entry.getValue()).intValue();
                    }
                }
            }
        } catch (Exception ignored) {}
        return 80; // fallback default
    }

    // ── Mapping formulas ─────────────────────────────────────────────────────

    /**
     * Speed (base ~30-180) → movement_speed modifier.
     * MC default speed = 0.1. Result range: ~0.04 to 0.22
     */
    public static double mapSpeed(int baseSpeed) {
        return 0.1 + (baseSpeed - 80) / 800.0;
    }

    /**
     * HP (base ~1-255) → max_health value.
     * MC default = 20 (10 hearts). Result range: ~10 to 37
     */
    public static double mapMaxHealth(int baseHP) {
        return 10.0 + (baseHP / 15.0);
    }

    /**
     * Pokémon effective hitbox height (blocks) → player GENERIC_SCALE factor.
     * Scales the player so their collision height matches the Pokémon's actual hitbox.
     * Player base height = 1.8 blocks, so scale = effectiveHitboxHeight / 1.8.
     * Capped at [0.15, 2.5] — allows big Pokémon (Wailord, Steelix, etc.) to be properly large.
     */
    public static float mapScale(float effectiveHitboxHeight) {
        float scale = effectiveHitboxHeight / 1.8f;
        return Math.max(0.15f, Math.min(2.5f, scale));
    }

    /**
     * Compute Base Stat Total from all 6 stats.
     */
    public static int computeBST(int hp, int atk, int def, int spAtk, int spDef, int speed) {
        return hp + atk + def + spAtk + spDef + speed;
    }

    /**
     * Species stats → attack damage.
     * Formula: 2.0 + max(atk, spAtk) / 25. Range ~2-12.
     */
    public static float mapAttackDamage(int atk, int spAtk) {
        return Math.min(12.0f, 2.0f + Math.max(atk, spAtk) / 25.0f);
    }

    /**
     * Speed stat → attack cooldown in ticks.
     * Formula: max(25, 40 - speed/12). Range 25-40 ticks.
     */
    public static int mapAttackCooldown(int speed) {
        return Math.max(25, 40 - speed / 12);
    }

    /**
     * BST → number of projectiles per attack.
     * <350: 1, 350-449: 1 (boosted), >=450: 3 (cone).
     */
    public static int mapProjectileCount(int bst) {
        return bst >= 450 ? 3 : 1;
    }

    // ── Accurate hitbox via temporary PokemonEntity ─────────────────────────

    /**
     * Create a temporary PokemonEntity on the server to read its actual getHeight().
     * This matches the "gray hitbox" visible with F3+B — the ACTUAL entity AABB.
     * Returns -1 if creation fails (Cobblemon not available, etc.).
     */
    public static float resolveActualHeight(String speciesName, World world) {
        tryInit();
        if (!cobblemonAvailable || world == null) return -1;

        try {
            String baseName = speciesName.toLowerCase(Locale.ROOT);
            Class<?> registryClass = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
            Object instance = registryClass.getField("INSTANCE").get(null);
            Object species = getByNameMethod.invoke(instance, baseName);
            if (species == null) species = getByNameMethod.invoke(instance, baseName.replace("-", ""));
            if (species == null) species = getByNameMethod.invoke(instance, baseName.replace("-", "_"));
            if (species == null) return -1;

            // Create Pokemon object
            Class<?> pokemonClass = Class.forName("com.cobblemon.mod.common.pokemon.Pokemon");
            Object pokemon = pokemonClass.getConstructor().newInstance();
            boolean speciesSet = false;
            for (Method m : pokemonClass.getMethods()) {
                if (m.getName().equals("setSpecies") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(species)) {
                    m.invoke(pokemon, species);
                    speciesSet = true;
                    break;
                }
            }
            if (!speciesSet) return -1;

            // Find entity type
            Object entityType = findCobblemonEntityType();
            if (entityType == null) return -1;

            // Create temporary PokemonEntity (never added to world)
            Class<?> entityClass = Class.forName("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
            Entity entity = null;
            for (Constructor<?> ctor : entityClass.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                boolean hasSynthetic = false;
                for (Class<?> p : params) {
                    if (p.getName().contains("DefaultConstructorMarker")) { hasSynthetic = true; break; }
                }
                if (hasSynthetic) continue;

                if (params.length == 3 && params[0].isAssignableFrom(world.getClass())
                    && params[1].isInstance(pokemon) && params[2].isInstance(entityType)) {
                    try { entity = (Entity) ctor.newInstance(world, pokemon, entityType); break; }
                    catch (Exception ignored) {}
                }
                if (params.length == 2 && params[0].isAssignableFrom(world.getClass())
                    && params[1].isInstance(pokemon)) {
                    try { entity = (Entity) ctor.newInstance(world, pokemon); break; }
                    catch (Exception ignored) {}
                }
            }

            if (entity != null) {
                float height = entity.getHeight();
                float width = entity.getWidth();
                entity.discard();
                LOGGER.info("[CobbleShift] Temp PokemonEntity '{}': actualHeight={}, actualWidth={}", speciesName, height, width);
                return height;
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] resolveActualHeight failed for '{}': {}", speciesName, e.getMessage());
        }
        return -1;
    }

    private static Object findCobblemonEntityType() {
        try {
            Class<?> c = Class.forName("com.cobblemon.mod.common.CobblemonEntities");
            try { Object r = c.getField("POKEMON").get(null); if (r != null) return r; }
            catch (NoSuchFieldException ignored) {}
            try {
                Object inst = c.getField("INSTANCE").get(null);
                for (Method m : inst.getClass().getMethods()) {
                    if (m.getName().equals("getPOKEMON") && m.getParameterCount() == 0) {
                        Object r = m.invoke(inst); if (r != null) return r;
                    }
                }
            } catch (NoSuchFieldException ignored) {}
        } catch (Exception ignored) {}
        return null;
    }

    // ── Species Stats Record ─────────────────────────────────────────────────

    public record SpeciesStats(
        String name,
        int hp, int atk, int def, int spAtk, int spDef, int speed,
        String type1, String type2,
        float height, // Pokedex height in decimeters
        float hitboxHeight // effective hitbox height in blocks (hitbox.height * baseScale)
    ) {}
}
