package com.cobblecap.logic;

import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.ChampionEntry;
import com.cobblecap.data.PlayerCapData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the XP cap for players.
 * Called from CobblemonHook when a Cobblemon XP event fires.
 * All Cobblemon access is via reflection — no direct Cobblemon imports.
 */
public class CapManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    /** Per-player, per-Pokémon timestamp of last XP block message sent (ms). */
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Long>> lastXpBlockMsg =
            new ConcurrentHashMap<>();

    /** Minimum ms between XP block messages for the same (player, pokemon) pair. */
    private static final long XP_MSG_COOLDOWN_MS = 30_000L;

    // ── Public entry point ───────────────────────────────────────────────────

    /**
     * Called from the Cobblemon XP event hook. Extracts the relevant data via reflection,
     * checks the cap, and cancels/zeroes XP if needed.
     * Fails open on any reflection error to avoid crashes.
     */
    public static void handleXpEvent(Object event) {
        try {
            // ── Extract Pokémon from event ────────────────────────────────────────
            Object pokemon = invokeGetter(event, "getPokemon", "pokemon");
            if (pokemon == null) return;

            // ── Resolve owning player ─────────────────────────────────────────────
            // Strategy 1: try getting player directly from the event
            ServerPlayerEntity player = null;
            Object eventPlayer = invokeGetter(event, "getPlayer", "getPlayerEntity");
            if (eventPlayer instanceof ServerPlayerEntity sp) {
                player = sp;
            }

            // Strategy 2: try getting player from the Pokemon
            if (player == null) {
                Object owner = invokeGetter(pokemon, "getOwnerPlayer", "getTrainer", "getEntity");
                if (owner instanceof ServerPlayerEntity sp) {
                    player = sp;
                }
            }

            if (player == null) {
                LOGGER.debug("[CobbleCap] XP event: could not resolve player");
                return;
            }

            // ── Get Pokémon level ─────────────────────────────────────────────────
            Object levelObj = invokeGetter(pokemon, "getLevel", "level");
            if (levelObj == null) {
                LOGGER.debug("[CobbleCap] Could not get pokemon level — skipping XP check");
                return;
            }
            int currentLevel;
            if (levelObj instanceof Integer i) {
                currentLevel = i;
            } else if (levelObj instanceof Number n) {
                currentLevel = n.intValue();
            } else {
                // Cobblemon may wrap level in an object — try invoking intValue/getValue
                Object inner = invokeGetter(levelObj, "intValue", "getValue", "get");
                if (inner instanceof Number n) {
                    currentLevel = n.intValue();
                } else {
                    LOGGER.warn("[CobbleCap] Unexpected level type: {} ({})", levelObj, levelObj.getClass().getName());
                    return;
                }
            }

            // ── Get Pokémon UUID (for rate-limiting) ──────────────────────────────
            Object uuidObj = invokeGetter(pokemon, "getUuid", "uuid");
            UUID pokemonUuid;
            if (uuidObj instanceof UUID u) {
                pokemonUuid = u;
            } else {
                // Fallback: derive a stable UUID from species + owner so rate-limit still works
                pokemonUuid = UUID.nameUUIDFromBytes(pokemon.toString().getBytes());
            }

            // ── Check explock (blocks ALL XP regardless of cap) ────────────────
            UUID playerUuid = player.getUuid();
            Optional<PlayerCapData> dataOpt = CobbleCapMod.INSTANCE.getCapStorage().getCapData(playerUuid);
            if (dataOpt.isEmpty()) return; // Edge case: player not yet registered

            if (dataOpt.get().isExpLocked()) {
                cancelXpEvent(event);
                sendExpLockMessage(player, pokemonUuid, pokemon);
                return;
            }

            // Guard: cap system must be enabled for cap enforcement (explock works regardless)
            if (!CobbleCapMod.INSTANCE.getCapConfig().isEnabled()) return;

            // ── Check cap ────────────────────────────────────────────────────────
            int cap = dataOpt.get().getCapLevel();
            if (currentLevel < cap) return; // Under cap — XP allowed (at cap = blocked)

            // ── Cancel / zero the XP ──────────────────────────────────────────────
            cancelXpEvent(event);

            // ── Send rate-limited message ─────────────────────────────────────────
            sendXpBlockMessage(player, pokemonUuid, pokemon, cap);

        } catch (Exception e) {
            // Fail open: log at debug to avoid log spam
            LOGGER.debug("[CobbleCap] handleXpEvent error: {}", e.getMessage());
        }
    }

    // ── XP cancel helper ────────────────────────────────────────────────────

    private static void cancelXpEvent(Object event) {
        boolean cancelled = false;
        // Strategy 1: call cancel() on the event (pre-events in Cobblemon are Cancelable)
        try {
            Method cancelMethod = findMethod(event.getClass(), "cancel", "setCanceled", "setCancelled");
            if (cancelMethod != null) {
                if (cancelMethod.getParameterCount() == 0) {
                    cancelMethod.invoke(event);
                } else if (cancelMethod.getParameterCount() == 1 &&
                           cancelMethod.getParameterTypes()[0] == boolean.class) {
                    cancelMethod.invoke(event, true);
                }
                cancelled = true;
            }
        } catch (Exception ignored) {}

        // Strategy 2: set the experience amount to 0 (post-events)
        if (!cancelled) {
            try {
                Method setExp = findMethod(event.getClass(), "setExperience", "setExpToAdd", "setExp", "setAmount");
                if (setExp != null && setExp.getParameterCount() == 1) {
                    setExp.invoke(event, 0);
                    cancelled = true;
                }
            } catch (Exception ignored) {}
        }

        if (!cancelled) {
            LOGGER.debug("[CobbleCap] Could not cancel XP event — event class: {}", event.getClass().getName());
        }
    }

    // ── Messaging ────────────────────────────────────────────────────────────

    private static void sendXpBlockMessage(ServerPlayerEntity player, UUID pokemonUuid,
                                            Object pokemon, int cap) {
        if (!shouldSendMessage(player.getUuid(), pokemonUuid)) return;

        // Get Pokémon display name
        String pokemonName = getPokemonName(pokemon);

        // Get next champion name (Phase 2 placeholder)
        String nextChampion = getNextChampionName(player.getUuid());

        Text msg;
        if (nextChampion == null) {
            msg = Text.translatable("cobblecap.msg.xp_blocked_none", pokemonName, cap)
                    .formatted(Formatting.YELLOW);
        } else {
            msg = Text.translatable("cobblecap.msg.xp_blocked", pokemonName, cap, nextChampion)
                    .formatted(Formatting.YELLOW);
        }

        // Send as actionbar (true = overlay / action bar)
        player.sendMessage(msg, true);
    }

    // ── Rate limiting ────────────────────────────────────────────────────────

    private static boolean shouldSendMessage(UUID playerUuid, UUID pokemonUuid) {
        long now = System.currentTimeMillis();
        ConcurrentHashMap<UUID, Long> perPlayer =
                lastXpBlockMsg.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());
        Long lastSent = perPlayer.get(pokemonUuid);
        if (lastSent != null && now - lastSent < XP_MSG_COOLDOWN_MS) return false;
        perPlayer.put(pokemonUuid, now);
        return true;
    }

    private static void sendExpLockMessage(ServerPlayerEntity player, UUID pokemonUuid, Object pokemon) {
        if (!shouldSendMessage(player.getUuid(), pokemonUuid)) return;
        String pokemonName = getPokemonName(pokemon);
        Text msg = Text.translatable("cobblecap.msg.xp_locked", pokemonName)
                .formatted(Formatting.GRAY);
        player.sendMessage(msg, true); // actionbar
    }

    // ── Champion lookup ──────────────────────────────────────────────────────

    /**
     * Returns the name of the next unbeaten champion, or null if all are beaten.
     * TODO Phase 2: look up champion config for the first unbeaten champion name.
     */
    private static String getNextChampionName(UUID playerUuid) {
        try {
            PlayerCapData data = CobbleCapMod.INSTANCE.getCapStorage()
                .getCapData(playerUuid).orElse(null);
            if (data == null) return null;

            Optional<ChampionEntry> next = CobbleCapMod.INSTANCE.getChampionConfig()
                .nextUnbeaten(data.getChampionsBeaten());
            return next.map(ChampionEntry::displayName).orElse(null);
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] Could not resolve next champion name: {}", e.getMessage());
            return null;
        }
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    /**
     * Try to get a Pokémon's display name via reflection.
     * Falls back to "your Pokemon" if all approaches fail.
     */
    private static String getPokemonName(Object pokemon) {
        // Try getDisplayName() first (returns Text in Cobblemon)
        try {
            Object displayName = invokeGetter(pokemon, "getDisplayName");
            if (displayName != null) {
                // Text.getString() for Text objects, or toString() for others
                Method getString = findMethod(displayName.getClass(), "getString");
                if (getString != null) return (String) getString.invoke(displayName);
                return displayName.toString();
            }
        } catch (Exception ignored) {}

        // Try species name
        try {
            Object species = invokeGetter(pokemon, "getSpecies");
            if (species != null) {
                Method getName = findMethod(species.getClass(), "getName", "showdownId");
                if (getName != null) {
                    String name = getName.invoke(species).toString();
                    // Capitalise first letter
                    return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
                }
            }
        } catch (Exception ignored) {}

        return "your Pokemon";
    }

    /**
     * Invoke a zero-argument getter method, trying multiple name candidates.
     * Returns null if none resolves.
     * Package-private so ChampionManager (same package) can use it.
     */
    static Object invokeGetter(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = findMethod(target.getClass(), name);
                if (m != null && m.getParameterCount() == 0) {
                    return m.invoke(target);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Scan public and declared methods for a name match.
     * Package-private so ChampionManager (same package) can use it.
     */
    static Method findMethod(Class<?> clazz, String... names) {
        for (String name : names) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name)) return m;
            }
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }
}
