package com.cheesecompat;

import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Replaces CobbleSafari's DimensionalBanEventHandler.registerCobblemonEvents()
 * which can't load on Cobblemon 1.6.1 (references BattleStartedEvent.Pre from 1.7.x).
 *
 * Uses reflection + method scanning by return type to avoid intermediary name issues.
 */
public class SafariBanFix {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");

    // Cached reflection lookups
    private static Method getWorldMethod;
    private static Method isBattleAllowedMethod;

    public static void register() {
        try {
            Class<?> eventsClass = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            Class<?> priorityClass = Class.forName("com.cobblemon.mod.common.api.Priority");
            Object priorityHighest = Enum.valueOf((Class) priorityClass, "HIGHEST");

            Class<?> bannedItemsClass = Class.forName("maxigregrze.cobblesafari.manager.BannedItemsManager");
            isBattleAllowedMethod = bannedItemsClass.getMethod("isBattleAllowed", RegistryKey.class);

            Class<?> function1Class = Class.forName("kotlin.jvm.functions.Function1");
            Class<?> unitClass = Class.forName("kotlin.Unit");
            Object unitInstance = unitClass.getField("INSTANCE").get(null);

            // --- BATTLE_STARTED_PRE ---
            registerBattleBan(eventsClass, function1Class, unitInstance, priorityHighest);

            // --- POKEMON_SENT_PRE ---
            registerSentBan(eventsClass, function1Class, unitInstance, priorityHighest);

            // --- POKEROD_CAST_PRE ---
            registerFishingBan(eventsClass, function1Class, unitInstance, priorityHighest);

            LOGGER.info("[CheeseCompat] Safari dimensional ban events registered (Cobblemon 1.6.x compat)");
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] Failed to register Safari ban events: {}", e.getMessage(), e);
        }
    }

    private static void registerBattleBan(Class<?> eventsClass, Class<?> function1Class,
                                           Object unitInstance, Object priority) throws Exception {
        Object battlePre = eventsClass.getField("BATTLE_STARTED_PRE").get(null);
        Method subscribeMethod = findSubscribeMethod(battlePre.getClass());

        Object handler = Proxy.newProxyInstance(
            function1Class.getClassLoader(),
            new Class[]{function1Class},
            (proxy, method, args) -> {
                if (!"invoke".equals(method.getName())) return null;
                Object event = args[0];
                try {
                    Object battle = callMethod(event, "getBattle");
                    Iterable<?> actors = (Iterable<?>) callMethod(battle, "getActors");
                    for (Object actor : actors) {
                        Iterable<?> pokemonList = (Iterable<?>) callMethod(actor, "getPokemonList");
                        for (Object bp : pokemonList) {
                            Object entity = callMethod(bp, "getEntity");
                            if (entity == null) continue;
                            World level = getEntityWorld(entity);
                            if (level != null && !isBattleAllowed(level.getRegistryKey())) {
                                callMethod(event, "cancel");
                                return unitInstance;
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[CheeseCompat] SafariBan battle check error: {}", e.getMessage());
                }
                return unitInstance;
            }
        );
        subscribeMethod.invoke(battlePre, priority, handler);
    }

    private static void registerSentBan(Class<?> eventsClass, Class<?> function1Class,
                                         Object unitInstance, Object priority) throws Exception {
        Object sentPre = eventsClass.getField("POKEMON_SENT_PRE").get(null);
        Method subscribeMethod = findSubscribeMethod(sentPre.getClass());

        Object handler = Proxy.newProxyInstance(
            function1Class.getClassLoader(),
            new Class[]{function1Class},
            (proxy, method, args) -> {
                if (!"invoke".equals(method.getName())) return null;
                Object event = args[0];
                try {
                    // PokemonSentPreEvent has getLevel() — Cobblemon method, stable name
                    Object level = callMethod(event, "getLevel");
                    if (level instanceof ServerWorld sw) {
                        if (!isBattleAllowed(sw.getRegistryKey())) {
                            callMethod(event, "cancel");
                            return unitInstance;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[CheeseCompat] SafariBan pokemon sent check error: {}", e.getMessage());
                }
                return unitInstance;
            }
        );
        subscribeMethod.invoke(sentPre, priority, handler);
    }

    private static void registerFishingBan(Class<?> eventsClass, Class<?> function1Class,
                                            Object unitInstance, Object priority) throws Exception {
        Object rodPre = eventsClass.getField("POKEROD_CAST_PRE").get(null);
        Method subscribeMethod = findSubscribeMethod(rodPre.getClass());

        Object handler = Proxy.newProxyInstance(
            function1Class.getClassLoader(),
            new Class[]{function1Class},
            (proxy, method, args) -> {
                if (!"invoke".equals(method.getName())) return null;
                Object event = args[0];
                try {
                    Object bobber = callMethod(event, "getBobber");
                    if (bobber != null) {
                        // Bobber.getOwner() is Entity — scan for it
                        Object owner = callMethodByReturnType(bobber, Entity.class);
                        if (owner instanceof ServerPlayerEntity sp) {
                            if (!isBattleAllowed(sp.getServerWorld().getRegistryKey())) {
                                sp.sendMessage(Text.translatable("cobblesafari.ban.fishing_banned"), false);
                                callMethod(event, "cancel");
                                return unitInstance;
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[CheeseCompat] SafariBan fishing check error: {}", e.getMessage());
                }
                return unitInstance;
            }
        );
        subscribeMethod.invoke(rodPre, priority, handler);
    }

    // --- Utility methods ---

    /** Call a no-arg method by name (works for Cobblemon API methods which keep their names) */
    private static Object callMethod(Object obj, String methodName) throws Exception {
        for (Method m : obj.getClass().getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 0) {
                return m.invoke(obj);
            }
        }
        throw new NoSuchMethodException(methodName + " on " + obj.getClass().getSimpleName());
    }

    /** Call a no-arg method by return type (for Minecraft methods with intermediary names) */
    private static Object callMethodByReturnType(Object obj, Class<?> returnType) throws Exception {
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && returnType.isAssignableFrom(m.getReturnType())) {
                return m.invoke(obj);
            }
        }
        return null;
    }

    /** Get Entity.getWorld() by scanning for method returning World */
    private static World getEntityWorld(Object entity) {
        try {
            if (getWorldMethod == null) {
                for (Method m : entity.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == World.class) {
                        getWorldMethod = m;
                        break;
                    }
                }
            }
            if (getWorldMethod != null) {
                return (World) getWorldMethod.invoke(entity);
            }
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] getEntityWorld failed: {}", e.getMessage());
        }
        return null;
    }

    private static boolean isBattleAllowed(RegistryKey<World> dimKey) {
        try {
            return (boolean) isBattleAllowedMethod.invoke(null, dimKey);
        } catch (Exception e) {
            return true; // fail-open
        }
    }

    private static Method findSubscribeMethod(Class<?> observableClass) throws NoSuchMethodException {
        for (Method m : observableClass.getMethods()) {
            if ("subscribe".equals(m.getName()) && m.getParameterCount() == 2) {
                return m;
            }
        }
        throw new NoSuchMethodException("subscribe(Priority, Function1) not found on " + observableClass.getName());
    }
}
