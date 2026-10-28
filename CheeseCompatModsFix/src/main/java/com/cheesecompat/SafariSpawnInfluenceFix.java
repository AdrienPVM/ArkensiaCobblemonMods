package com.cheesecompat;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Registers 1.6.1-compatible spawn influences to replace CobbleSafari's broken ones.
 * Uses java.lang.reflect.Proxy to implement SpawningInfluence without compiling against
 * intermediary MC class names (class_3218, class_2338, class_1297).
 *
 * Two influences:
 * - BucketBoost: multiplies spawn bucket weights (uncommon/rare/ultra-rare) when PC effects active
 * - Repel: blocks Pokemon spawns within 80 blocks when REPEL effect active
 */
public class SafariSpawnInfluenceFix {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");

    // Multipliers — loaded from CobbleSafari SpawnBoostConfig at runtime
    private static float UNCOMMON_MULT = 16.0f;
    private static float RARE_MULT = 16.0f;
    private static float ULTRA_RARE_MULT = 16.0f;
    private static boolean multipliersLoaded = false;
    private static final double REPEL_RANGE_SQ = 80.0 * 80.0;

    // Cached ModEffects holders (resolved once at first use)
    private static RegistryEntry<StatusEffect> UNCOMMON_BOOST_HOLDER;
    private static RegistryEntry<StatusEffect> RARE_BOOST_HOLDER;
    private static RegistryEntry<StatusEffect> ULTRA_RARE_BOOST_HOLDER;
    private static RegistryEntry<StatusEffect> REPEL_HOLDER;
    private static boolean effectsResolved = false;

    @SuppressWarnings("unchecked")
    private static void resolveEffects() {
        if (effectsResolved) return;
        try {
            Class<?> modEffectsClass = Class.forName("maxigregrze.cobblesafari.init.ModEffects");
            UNCOMMON_BOOST_HOLDER = getEffectHolder(modEffectsClass, "UNCOMMON_BOOST");
            RARE_BOOST_HOLDER = getEffectHolder(modEffectsClass, "RARE_BOOST");
            ULTRA_RARE_BOOST_HOLDER = getEffectHolder(modEffectsClass, "ULTRA_RARE_BOOST");
            REPEL_HOLDER = getEffectHolder(modEffectsClass, "REPEL");
            effectsResolved = true;
            LOGGER.info("[CheeseCompat] Resolved CobbleSafari ModEffects for spawn influences");
        } catch (Exception e) {
            LOGGER.warn("[CheeseCompat] Could not resolve ModEffects: {}", e.getMessage());
        }
        // Load multipliers from SpawnBoostConfig
        if (!multipliersLoaded) {
            try {
                Class<?> configClass = Class.forName("maxigregrze.cobblesafari.config.SpawnBoostConfig");
                Field dataField = configClass.getDeclaredField("data");
                dataField.setAccessible(true);
                Object configData = dataField.get(null);
                Field settingsField = configData.getClass().getDeclaredField("effectSettings");
                settingsField.setAccessible(true);
                Object settings = settingsField.get(configData);
                Field uncommonField = settings.getClass().getDeclaredField("uncommonBoostMultiplier");
                Field rareField = settings.getClass().getDeclaredField("rareBoostMultiplier");
                Field ultraRareField = settings.getClass().getDeclaredField("ultraRareBoostMultiplier");
                uncommonField.setAccessible(true);
                rareField.setAccessible(true);
                ultraRareField.setAccessible(true);
                UNCOMMON_MULT = uncommonField.getFloat(settings);
                RARE_MULT = rareField.getFloat(settings);
                ULTRA_RARE_MULT = ultraRareField.getFloat(settings);
                multipliersLoaded = true;
                LOGGER.info("[CheeseCompat] Loaded SpawnBoostConfig multipliers: uncommon={} rare={} ultraRare={}",
                    UNCOMMON_MULT, RARE_MULT, ULTRA_RARE_MULT);
            } catch (Exception e) {
                LOGGER.warn("[CheeseCompat] Could not load SpawnBoostConfig multipliers, using defaults: {}", e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static RegistryEntry<StatusEffect> getEffectHolder(Class<?> enumClass, String name) throws Exception {
        Object enumVal = Enum.valueOf((Class<Enum>) enumClass, name);
        Field holderField = enumClass.getField("holder");
        return (RegistryEntry<StatusEffect>) holderField.get(enumVal);
    }

    public static void register() {
        try {
            // Get SpawningInfluence class and PlayerSpawnerFactory
            Class<?> influenceClass = Class.forName("com.cobblemon.mod.common.api.spawning.influence.SpawningInfluence");
            Class<?> factoryClass = Class.forName("com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawnerFactory");
            Object factory = factoryClass.getField("INSTANCE").get(null);
            Method getBuilders = factoryClass.getMethod("getInfluenceBuilders");

            @SuppressWarnings("unchecked")
            List<Object> builders = (List<Object>) getBuilders.invoke(factory);

            // Add BucketBoost influence builder (Kotlin Function1 proxy)
            Object bucketBoostBuilder = createKotlinFunction1(player ->
                createBucketBoostProxy(influenceClass, (ServerPlayerEntity) player));
            builders.add(bucketBoostBuilder);

            // Add Repel influence builder
            Object repelBuilder = createKotlinFunction1(player ->
                createRepelProxy(influenceClass, (ServerPlayerEntity) player));
            builders.add(repelBuilder);

            LOGGER.info("[CheeseCompat] Registered CobbleSafari spawn influences (1.6.1 compat)");
        } catch (Exception e) {
            LOGGER.warn("[CheeseCompat] Could not register spawn influences: {}", e.getMessage());
        }
    }

    /**
     * Creates a Proxy implementing SpawningInfluence for bucket weight boosts.
     */
    private static Object createBucketBoostProxy(Class<?> influenceClass, ServerPlayerEntity player) {
        resolveEffects();
        return Proxy.newProxyInstance(
            influenceClass.getClassLoader(),
            new Class<?>[] { influenceClass },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "affectBucketWeight": {
                        // args[0] = SpawnBucket, args[1] = float weight
                        Object bucket = args[0];
                        float weight = (float) args[1];
                        String bucketName = (String) bucket.getClass().getMethod("getName").invoke(bucket);

                        if ("uncommon".equals(bucketName) && UNCOMMON_BOOST_HOLDER != null
                                && player.hasStatusEffect(UNCOMMON_BOOST_HOLDER)) {
                            return weight * UNCOMMON_MULT;
                        }
                        if ("rare".equals(bucketName) && RARE_BOOST_HOLDER != null
                                && player.hasStatusEffect(RARE_BOOST_HOLDER)) {
                            return weight * RARE_MULT;
                        }
                        if ("ultra-rare".equals(bucketName) && ULTRA_RARE_BOOST_HOLDER != null
                                && player.hasStatusEffect(ULTRA_RARE_BOOST_HOLDER)) {
                            return weight * ULTRA_RARE_MULT;
                        }
                        return weight;
                    }
                    case "isExpired": return false;
                    case "affectSpawnable": return true;
                    case "affectWeight": return args[2]; // pass through weight
                    case "isAllowedPosition": return true;
                    case "affectAction":
                    case "affectSpawn": return null; // void
                    default: return handleDefault(method);
                }
            }
        );
    }

    /**
     * Creates a Proxy implementing SpawningInfluence for repel effect.
     */
    private static Object createRepelProxy(Class<?> influenceClass, ServerPlayerEntity player) {
        resolveEffects();
        return Proxy.newProxyInstance(
            influenceClass.getClassLoader(),
            new Class<?>[] { influenceClass },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "affectSpawnable": {
                        // args[0] = SpawnDetail, args[1] = SpawningContext
                        if (REPEL_HOLDER == null || !player.hasStatusEffect(REPEL_HOLDER)) {
                            return true;
                        }
                        // Check if it's a PokemonSpawnDetail
                        Object detail = args[0];
                        if (!detail.getClass().getSimpleName().contains("PokemonSpawnDetail")) {
                            return true;
                        }
                        // Check distance: ctx.getPosition() returns BlockPos (intermediary: class_2338)
                        Object ctx = args[1];
                        Method getPos = ctx.getClass().getMethod("getPosition");
                        Object spawnPos = getPos.invoke(ctx);
                        // spawnPos is a BlockPos (intermediary)
                        BlockPos playerPos = player.getBlockPos();
                        // Use getSquaredDistance if available, else calculate manually
                        double distSq = playerPos.getSquaredDistance((BlockPos) spawnPos);
                        return distSq > REPEL_RANGE_SQ;
                    }
                    case "isExpired": return false;
                    case "affectWeight": return args[2];
                    case "affectBucketWeight": return args[1];
                    case "isAllowedPosition": return true;
                    case "affectAction":
                    case "affectSpawn": return null;
                    default: return handleDefault(method);
                }
            }
        );
    }

    private static Object handleDefault(Method method) {
        Class<?> rt = method.getReturnType();
        if (rt == boolean.class) return true;
        if (rt == float.class) return 0f;
        if (rt == int.class) return 0;
        return null;
    }

    /**
     * Creates a Kotlin Function1 proxy (same pattern as SafariBanFix).
     */
    private static Object createKotlinFunction1(java.util.function.Function<Object, Object> func) throws Exception {
        Class<?> function1Class = Class.forName("kotlin.jvm.functions.Function1");
        return Proxy.newProxyInstance(
            function1Class.getClassLoader(),
            new Class<?>[] { function1Class },
            (proxy, method, args) -> {
                if ("invoke".equals(method.getName())) {
                    return func.apply(args[0]);
                }
                return null;
            }
        );
    }
}
