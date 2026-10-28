package com.cheeseraids.hooks;

import com.cheeseraids.CheeseRaidsMod;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Spawns Cobblemon Pokémon programmatically via reflection
 * (PokemonProperties.parse → createEntity(World) → ServerWorld.spawnEntity), returning the live
 * entity. Mirrors the server-proven approach in CheeseCompatModsFix's BossSpawner.
 *
 * <p>Why not a command? {@code pokespawn} from a silent console source swallows its failures, and
 * the failure mode we hit (ServerWorld.addEntity returning false / build-limit) produced no spawn
 * and no error. Here we get a <b>checked boolean</b>, a real log line on failure, and a <b>direct
 * entity handle</b> — so no snapshot-diff capture window is needed.</p>
 */
public final class CobblemonSpawnHook {
    private CobblemonSpawnHook() {}

    private static volatile boolean init = false;
    private static Object companion;
    private static Method parseMethod;
    private static Method createEntityMethod;

    private static synchronized void initApi() {
        if (init) return;
        try {
            Class<?> propsClass = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonProperties");
            Field companionField = propsClass.getField("Companion");
            companion = companionField.get(null);
            parseMethod = companion.getClass().getMethod("parse", String.class);
            for (Method m : propsClass.getDeclaredMethods()) {
                if (m.getName().equals("createEntity") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(World.class)) {
                    createEntityMethod = m;
                    break;
                }
            }
            if (createEntityMethod == null) {
                throw new NoSuchMethodException("createEntity(World) not found on PokemonProperties");
            }
        } catch (Throwable t) {
            CheeseRaidsMod.LOGGER.error("[CheeseRaids] Cobblemon API init failed: {}", t.getMessage(), t);
        }
        init = true;
    }

    public static boolean available() {
        initApi();
        return companion != null && parseMethod != null && createEntityMethod != null;
    }

    /**
     * Spawn a Pokémon from a Cobblemon properties string at the given world coordinates.
     *
     * @param scale      render+hitbox scale (1.0 = normal); applied BEFORE spawn so the spawn packet
     *                   carries the giant size to all clients.
     * @param persistent if true, mark the mob vanilla-persistent so Cobblemon's despawner never ages
     *                   it out (PokemonEntity.checkDespawn respects isPersistent()).
     * @return the spawned entity, or {@code null} if Cobblemon is unavailable or the spawn was
     *         refused (e.g. chunk not loaded — logged so we never fail silently again).
     */
    public static Entity spawn(ServerWorld world, double x, double y, double z,
                               String propsString, String commandTag, float scale, boolean persistent) {
        if (!available()) {
            CheeseRaidsMod.LOGGER.error("[CheeseRaids] Cobblemon API unavailable; cannot spawn '{}'", propsString);
            return null;
        }
        try {
            Object props = parseMethod.invoke(companion, propsString);
            Object entityObj = createEntityMethod.invoke(props, world);
            if (!(entityObj instanceof Entity entity)) {
                CheeseRaidsMod.LOGGER.error("[CheeseRaids] createEntity returned non-Entity for '{}'", propsString);
                return null;
            }
            entity.refreshPositionAndAngles(x, y, z, world.random.nextFloat() * 360f, 0f);
            if (commandTag != null && !commandTag.isBlank()) {
                try { entity.addCommandTag(commandTag); } catch (Throwable ignored) {}
            }
            if (scale > 1.0f && !applyScale(entity, scale)) // before spawnEntity so the size is in the spawn packet
                CheeseRaidsMod.LOGGER.warn("[CheeseRaids] scale {} unsupported for '{}' (normal size, still fightable)", scale, propsString);
            if (persistent && entity instanceof net.minecraft.entity.mob.MobEntity mob) {
                mob.setPersistent(); // PokemonEntity respects vanilla persistence → no natural despawn
            }
            // Force the target chunk to load (FULL) on the server thread BEFORE spawning. A closed
            // CheeseGuard rift (enabled=false) does not keep its centre ticking even with a forceload
            // ticket, so spawnEntity was refused ("chunk not loaded"). getChunk(x,z) loads/generates the
            // void chunk synchronously — this is the spawn path on the server thread (NOT a load handler),
            // so the "no force-load in load handlers" rule does not apply.
            try { world.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4); } catch (Throwable ignored) {}
            boolean ok = world.spawnEntity(entity);
            if (!ok) {
                CheeseRaidsMod.LOGGER.warn("[CheeseRaids] spawnEntity refused '{}' at {},{},{} (chunk not loaded / build limit?)",
                    propsString, x, y, z);
                return null;
            }
            return entity;
        } catch (Throwable t) {
            CheeseRaidsMod.LOGGER.error("[CheeseRaids] spawn '{}' failed: {}", propsString, t.getMessage(), t);
            return null;
        }
    }

    /**
     * Enlarge a Cobblemon entity: PokemonEntity.getPokemon().setScaleModifier(scale). Confirmed
     * present in Cobblemon 1.6.1 (drives both the rendered model and the collision hitbox). Scanned
     * defensively by name + single-float param. Returns false if the running build lacks it.
     */
    private static boolean applyScale(net.minecraft.entity.Entity entity, float scale) {
        try {
            Method getPokemon = null;
            for (Method m : entity.getClass().getMethods()) {
                if (m.getName().equals("getPokemon") && m.getParameterCount() == 0) { getPokemon = m; break; }
            }
            if (getPokemon == null) return false;
            Object pokemon = getPokemon.invoke(entity);
            if (pokemon == null) return false;
            for (Method m : pokemon.getClass().getMethods()) {
                if (m.getName().startsWith("set") && m.getName().toLowerCase().contains("scalemodifier")
                        && m.getParameterCount() == 1
                        && (m.getParameterTypes()[0] == float.class || m.getParameterTypes()[0] == Float.class)) {
                    m.invoke(pokemon, scale);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Build a Cobblemon properties string from the config fields. {@code aspects} is a comma list of
     * bare aspect names (e.g. {@code "unbound"}) → emitted as {@code aspect=unbound}.
     */
    public static String buildProps(String species, int level, String aspects, boolean uncatchable) {
        StringBuilder sb = new StringBuilder(species == null ? "" : species.trim());
        if (level > 0) sb.append(" level=").append(level);
        if (aspects != null && !aspects.isBlank()) {
            for (String a : aspects.split(",")) {
                String t = a.trim();
                if (!t.isEmpty()) sb.append(" aspect=").append(t);
            }
        }
        if (uncatchable) sb.append(" uncatchable=true");
        return sb.toString().trim();
    }
}
