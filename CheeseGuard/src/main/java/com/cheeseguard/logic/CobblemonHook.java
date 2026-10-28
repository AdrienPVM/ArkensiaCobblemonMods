package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.EventParticipant;
import com.cheeseguard.data.ServerEvent;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Soft-dependency hook for Cobblemon events.
 * Uses reflection to subscribe to Cobblemon's event bus without compile-time dependency.
 * Handles: CATCH_POKEMON, KILL_POKEMON, WIN_BATTLE, FISH_POKEMON score triggers.
 */
public class CobblemonHook {

    private static boolean initialized = false;
    private static boolean available = false;

    // Track fishing encounters: playerUUID -> (timestamp, species)
    // When a pokemon spawns from fishing, we record it here
    // When POKEMON_CAPTURED fires within 60s, we score FISH_POKEMON (REALISTIC mode)
    private static final Map<UUID, FishEncounter> fishEncounters = new ConcurrentHashMap<>();

    private record FishEncounter(long timestamp, String species) {}

    public static void tryInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> cobblemonEvents = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            available = true;
            CheeseGuardMod.LOGGER.info("[CG] Cobblemon detected, hooking events...");

            // Hook POKEMON_CAPTURED
            hookEvent(cobblemonEvents, "POKEMON_CAPTURED", event -> {
                try {
                    Method getPlayer = event.getClass().getMethod("getPlayer");
                    Object player = getPlayer.invoke(event);
                    if (player instanceof ServerPlayerEntity sp) {
                        Method getPokemon = event.getClass().getMethod("getPokemon");
                        Object pokemon = getPokemon.invoke(event);
                        String species = getPokemonSpecies(pokemon);

                        // Standard CATCH_POKEMON scoring
                        EventScoreHook.check(sp, ServerEvent.ScoreTrigger.TriggerType.CATCH_POKEMON, species);

                        // REALISTIC fishing mode: check if this was a fished pokemon
                        FishEncounter enc = fishEncounters.remove(sp.getUuid());
                        if (enc != null && System.currentTimeMillis() - enc.timestamp < 120_000) {
                            // This capture came from a fishing encounter — score FISH_POKEMON
                            scoreFishPokemonRealistic(sp, species);
                        }
                    }
                } catch (Exception e) {
                    CheeseGuardMod.LOGGER.debug("[CG] Cobblemon capture hook err: {}", e.getMessage());
                }
            });

            // Hook POKEMON_FAINTED
            hookEvent(cobblemonEvents, "POKEMON_FAINTED", event -> {
                try {
                    Method getKiller = findMethod(event.getClass(), "getKiller", "getActor");
                    if (getKiller != null) {
                        Object killer = getKiller.invoke(event);
                        if (killer instanceof ServerPlayerEntity sp) {
                            Method getPokemon = event.getClass().getMethod("getPokemon");
                            Object pokemon = getPokemon.invoke(event);
                            String species = getPokemonSpecies(pokemon);
                            EventScoreHook.check(sp, ServerEvent.ScoreTrigger.TriggerType.KILL_POKEMON, species);
                        }
                    }
                } catch (Exception e) {
                    CheeseGuardMod.LOGGER.debug("[CG] Cobblemon faint hook err: {}", e.getMessage());
                }
            });

            // Hook BATTLE_VICTORY (winners + losers)
            hookEvent(cobblemonEvents, "BATTLE_VICTORY", event -> {
                try {
                    // Score winners
                    Method getWinners = event.getClass().getMethod("getWinners");
                    Object winners = getWinners.invoke(event);
                    if (winners instanceof Iterable<?> iter) {
                        for (Object actor : iter) {
                            UUID uuid = getActorPlayerUuid(actor);
                            if (uuid != null) {
                                ServerPlayerEntity sp = CheeseGuardMod.getInstance().getServer().getPlayerManager().getPlayer(uuid);
                                if (sp != null) {
                                    EventScoreHook.check(sp, ServerEvent.ScoreTrigger.TriggerType.WIN_BATTLE);
                                }
                            }
                        }
                    }
                    // Execute battle loss commands for losers (zone + dimension)
                    try {
                        Method getLosers = event.getClass().getMethod("getLosers");
                        Object losers = getLosers.invoke(event);
                        if (losers instanceof Iterable<?> loserIter) {
                            for (Object actor : loserIter) {
                                UUID uuid = getActorPlayerUuid(actor);
                                if (uuid != null) {
                                    ServerPlayerEntity sp = CheeseGuardMod.getInstance().getServer().getPlayerManager().getPlayer(uuid);
                                    if (sp != null) {
                                        executeBattleLossCommands(sp);
                                    }
                                }
                            }
                        }
                    } catch (NoSuchMethodException ignored) {
                        // getLosers not available in this Cobblemon version — try getBattle().getPlayers() minus winners
                        CheeseGuardMod.LOGGER.debug("[CG] BattleVictoryEvent.getLosers() not available, skipping battle loss commands");
                    }
                } catch (Exception e) {
                    CheeseGuardMod.LOGGER.debug("[CG] Cobblemon battle victory hook err: {}", e.getMessage());
                }
            });

            // Hook FISHING_POKEMON_CAUGHT or LURE_POKEMON — Cobblemon fishing encounter
            // Try multiple possible event names since API varies by version
            boolean fishedHooked = false;
            for (String eventName : new String[]{
                "POKEMON_FISHED", "FISHING_POKEMON_CAUGHT", "LURE_POKEMON_CREATED",
                "POKEMON_SENT_POST", "BATTLE_STARTED_POST"
            }) {
                try {
                    hookEvent(cobblemonEvents, eventName, evt -> handleFishingEncounter(evt));
                    fishedHooked = true;
                    CheeseGuardMod.LOGGER.info("[CG] Hooked Cobblemon fishing event: {}", eventName);
                    break;
                } catch (Exception ignored) {}
            }

            // Fallback: hook BATTLE_STARTED to detect fishing-originated battles
            if (!fishedHooked) {
                try {
                    hookEvent(cobblemonEvents, "BATTLE_STARTED_POST", evt -> {
                        try {
                            // Check if battle cause is fishing
                            Method getCause = findMethod(evt.getClass(), "getCause", "getReason");
                            if (getCause != null) {
                                Object cause = getCause.invoke(evt);
                                String causeStr = cause != null ? cause.toString().toLowerCase() : "";
                                if (causeStr.contains("fish") || causeStr.contains("lure")) {
                                    handleFishingEncounter(evt);
                                }
                            }
                        } catch (Exception ignored) {}
                    });
                    CheeseGuardMod.LOGGER.info("[CG] Hooked Cobblemon battle start for fishing detection");
                } catch (Exception ignored) {
                    CheeseGuardMod.LOGGER.info("[CG] Cobblemon fishing hooks not available — REALISTIC mode will use capture-only tracking");
                }
            }

            // Hook FOSSIL_REVIVED — broadcast shiny, perfect IVs, hidden ability
            hookEvent(cobblemonEvents, "FOSSIL_REVIVED", event -> {
                try {
                    Method getPlayer = event.getClass().getMethod("getPlayer");
                    Object player = getPlayer.invoke(event);
                    if (player instanceof ServerPlayerEntity sp) {
                        Method getPokemon = event.getClass().getMethod("getPokemon");
                        Object pokemon = getPokemon.invoke(event);

                        // Check if shiny
                        Method getShiny = pokemon.getClass().getMethod("getShiny");
                        boolean shiny = (boolean) getShiny.invoke(pokemon);

                        // Check IVs
                        int perfectIvs = 0;
                        try {
                            Object ivs = pokemon.getClass().getMethod("getIVs").invoke(pokemon);
                            // Cobblemon IVStore: iterate over stat keys
                            String[] statKeys = {"hp", "attack", "defence", "special_attack", "special_defence", "speed"};
                            for (String stat : statKeys) {
                                try {
                                    // Try getOrDefault(Stat, int) or get individual stat methods
                                    Method getMethod = findMethod(ivs.getClass(), "get");
                                    if (getMethod != null && getMethod.getParameterCount() == 1) {
                                        // Cobblemon Stats enum
                                        Class<?> statsClass = Class.forName("com.cobblemon.mod.common.api.pokemon.stats.Stats");
                                        Object statEnum = null;
                                        for (var field : statsClass.getFields()) {
                                            if (field.getName().equalsIgnoreCase(stat)) {
                                                statEnum = field.get(null);
                                                break;
                                            }
                                        }
                                        if (statEnum != null) {
                                            Object val = getMethod.invoke(ivs, statEnum);
                                            if (val instanceof Integer iv && iv == 31) perfectIvs++;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}

                        // Check hidden ability
                        boolean hiddenAbility = false;
                        try {
                            Object ability = pokemon.getClass().getMethod("getAbility").invoke(pokemon);
                            if (ability != null) {
                                // Check if the ability is forced (index 2 = hidden in Cobblemon)
                                Method getIndex = findMethod(ability.getClass(), "getIndex", "getPriority");
                                if (getIndex != null) {
                                    Object idx = getIndex.invoke(ability);
                                    if (idx instanceof Integer i && i == 2) hiddenAbility = true;
                                }
                                // Alternative: check isHidden method if available
                                if (!hiddenAbility) {
                                    Method isHidden = findMethod(ability.getClass(), "isHidden", "getForced");
                                    if (isHidden != null) {
                                        Object result = isHidden.invoke(ability);
                                        if (result instanceof Boolean b && b) hiddenAbility = true;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}

                        // Only broadcast if there's something notable
                        if (!shiny && perfectIvs < 6 && !hiddenAbility) return;

                        // Get species name
                        Method getSpecies = pokemon.getClass().getMethod("getSpecies");
                        Object species = getSpecies.invoke(pokemon);
                        Method getNameMethod = findMethod(species.getClass(), "getName", "showdownId");
                        String speciesName = getNameMethod != null ? getNameMethod.invoke(species).toString().toLowerCase() : "unknown";

                        // Build broadcast message
                        String playerName = sp.getName().getString();
                        var speciesText = Text.translatable("cobblemon.species." + speciesName + ".name");
                        var msg = Text.empty()
                            .append(Text.translatable("cheeseguard.cobblemon.fossil_revived_prefix").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal(playerName).formatted(Formatting.GOLD))
                            .append(Text.translatable("cheeseguard.cobblemon.fossil_revived_action").formatted(Formatting.YELLOW))
                            .append(speciesText.copy().formatted(Formatting.AQUA));
                        if (shiny) msg.append(Text.translatable("cheeseguard.cobblemon.fossil_shiny").formatted(Formatting.YELLOW));
                        if (perfectIvs == 6) msg.append(Text.translatable("cheeseguard.cobblemon.fossil_6iv").formatted(Formatting.AQUA));
                        if (hiddenAbility) msg.append(Text.translatable("cheeseguard.cobblemon.fossil_ha").formatted(Formatting.LIGHT_PURPLE));

                        var server = sp.getServer();
                        if (server != null) {
                            server.getPlayerManager().broadcast(msg, false);
                        }
                    }
                } catch (Exception e) {
                    CheeseGuardMod.LOGGER.debug("[CG] Cobblemon fossil revive hook err: {}", e.getMessage());
                }
            });

            CheeseGuardMod.LOGGER.info("[CG] Cobblemon event hooks registered successfully");
        } catch (ClassNotFoundException e) {
            CheeseGuardMod.LOGGER.debug("[CG] Cobblemon not found, skipping hooks");
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to hook Cobblemon events: {}", e.getMessage());
        }
    }

    // ── Fishing encounter handling ───────────────────────────────────────────

    private static void handleFishingEncounter(Object event) {
        try {
            // Try to get the player from the event
            Method getPlayer = findMethod(event.getClass(), "getPlayer", "getPlayerEntity");
            if (getPlayer != null) {
                Object player = getPlayer.invoke(event);
                if (player instanceof ServerPlayerEntity sp) {
                    String species = "unknown";
                    Method getPokemon = findMethod(event.getClass(), "getPokemon", "getEntity");
                    if (getPokemon != null) {
                        Object pokemon = getPokemon.invoke(event);
                        species = getPokemonSpecies(pokemon);
                    }
                    fishEncounters.put(sp.getUuid(), new FishEncounter(System.currentTimeMillis(), species));
                    CheeseGuardMod.LOGGER.debug("[CG] Fishing encounter tracked: {} -> {}", sp.getName().getString(), species);
                }
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.debug("[CG] Could not track fishing encounter: {}", e.getMessage());
        }
    }

    /**
     * Called from EventFishCatchMixin when a vanilla fish is caught.
     * If player is in an event with ARCADE fishing mode, roll a pokemon and award points.
     */
    public static void onVanillaFishCatch(ServerPlayerEntity player) {
        if (player == null) return;
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        if (em == null) return;

        for (ServerEvent event : em.getEvents()) {
            if (!event.isActive()) continue;
            if (!"ARCADE".equals(event.getFishingMode())) continue;
            EventParticipant p = event.getParticipant(player.getUuid());
            if (p == null || p.getRole() != EventParticipant.Role.PARTICIPANT) continue;

            // Roll a random pokemon from the table
            PokemonFishingTable.FishEntry roll = PokemonFishingTable.get().rollRandom();
            if (roll == null) continue;

            // Award points
            em.addScore(event, player.getUuid(), roll.points);

            // Send fancy message
            String color = PokemonFishingTable.tierColor(roll.tier);
            String icon = PokemonFishingTable.tierIcon(roll.tier);
            player.sendMessage(Text.literal(
                icon + " Tu as peche un " + color + roll.display + "§r ! §a(+" + roll.points + " pts) §8[" + roll.tier + "]"
            ), false);
        }
    }

    /**
     * Score FISH_POKEMON in REALISTIC mode: player captured a pokemon that was fished.
     */
    private static void scoreFishPokemonRealistic(ServerPlayerEntity player, String species) {
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        if (em == null) return;

        for (ServerEvent event : em.getEvents()) {
            if (!event.isActive()) continue;
            if (!"REALISTIC".equals(event.getFishingMode())) continue;
            EventParticipant p = event.getParticipant(player.getUuid());
            if (p == null || p.getRole() != EventParticipant.Role.PARTICIPANT) continue;

            // Get points from rarity table
            PokemonFishingTable table = PokemonFishingTable.get();
            PokemonFishingTable.FishEntry entry = table.findBySpecies(species);
            int pts = entry != null ? entry.points : 1;
            String display = entry != null ? entry.display : species;
            String tier = entry != null ? entry.tier : "Inconnu";

            em.addScore(event, player.getUuid(), pts);

            String color = PokemonFishingTable.tierColor(tier);
            String icon = PokemonFishingTable.tierIcon(tier);
            player.sendMessage(Text.literal(
                icon + " Pokemon peche capture : " + color + display + "§r ! §a(+" + pts + " pts) §8[" + tier + "]"
            ), false);
        }
    }

    /**
     * Manually register a fishing encounter for a player.
     * Used as fallback when we can't hook Cobblemon's fishing events directly.
     * Called from EventFishCatchMixin when a fish catch happens — if Cobblemon might
     * have replaced the catch with a pokemon encounter.
     */
    public static void trackPotentialFishEncounter(ServerPlayerEntity player) {
        if (player == null || !available) return;
        // Only track if player is in a REALISTIC fishing event
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        if (em == null) return;
        for (ServerEvent event : em.getEvents()) {
            if (!event.isActive()) continue;
            if (!"REALISTIC".equals(event.getFishingMode())) continue;
            if (event.getParticipant(player.getUuid()) != null) {
                fishEncounters.put(player.getUuid(), new FishEncounter(System.currentTimeMillis(), "unknown"));
                break;
            }
        }
    }

    // ── Cleanup stale entries ────────────────────────────────────────────────

    public static void cleanupStale() {
        long now = System.currentTimeMillis();
        fishEncounters.entrySet().removeIf(e -> now - e.getValue().timestamp > 120_000);
    }

    // ── Utility methods ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void hookEvent(Class<?> eventsClass, String fieldName, Consumer<Object> handler) {
        try {
            var field = eventsClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object observable = field.get(null);

            // Cobblemon uses Kotlin Function1<T, Unit>, not Java Consumer<T>
            // Wrap the Consumer into a Function1 proxy
            Class<?> function1Class = Class.forName("kotlin.jvm.functions.Function1");
            Class<?> unitClass = Class.forName("kotlin.Unit");
            Object unitInstance = unitClass.getField("INSTANCE").get(null);

            Object kotlinHandler = Proxy.newProxyInstance(
                observable.getClass().getClassLoader(),
                new Class[]{function1Class},
                (proxy, method, args) -> {
                    if (method.getName().equals("invoke") && args != null && args.length == 1) {
                        handler.accept(args[0]);
                        return unitInstance;
                    }
                    // Default Object methods (toString, hashCode, equals)
                    if (method.getName().equals("toString")) return "CheeseguardHook[" + fieldName + "]";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    return null;
                }
            );

            // Try subscribe(Priority, Function1) first
            try {
                Class<?> priorityClass = Class.forName("com.cobblemon.mod.common.api.Priority");
                Object normalPriority = priorityClass.getField("NORMAL").get(null);
                Method sub = observable.getClass().getMethod("subscribe", priorityClass, function1Class);
                sub.invoke(observable, normalPriority, kotlinHandler);
            } catch (Exception e1) {
                // Fallback: try subscribe(Function1) without priority
                try {
                    Method sub = observable.getClass().getMethod("subscribe", function1Class);
                    sub.invoke(observable, kotlinHandler);
                } catch (Exception e2) {
                    CheeseGuardMod.LOGGER.warn("[CG] Could not subscribe to {}: {}", fieldName, e2.getMessage());
                }
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Could not find Cobblemon event {}: {}", fieldName, e.getMessage());
        }
    }

    static String getPokemonSpecies(Object pokemon) {
        try {
            Method getSpecies = pokemon.getClass().getMethod("getSpecies");
            Object species = getSpecies.invoke(pokemon);
            Method getName = findMethod(species.getClass(), "getName", "getResourceIdentifier", "showdownId");
            if (getName != null) {
                getName.setAccessible(true);
                return "cobblemon:" + getName.invoke(species).toString().toLowerCase();
            }
        } catch (Exception ignored) {}
        return "cobblemon:unknown";
    }

    /**
     * Execute battle loss commands from the player's current zone(s) and dimension.
     * Commands support {player} placeholder.
     */
    private static void executeBattleLossCommands(net.minecraft.server.network.ServerPlayerEntity player) {
        var server = CheeseGuardMod.getInstance().getServer();
        if (server == null) return;
        String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
        String playerName = player.getName().getString();
        var dispatcher = server.getCommandManager().getDispatcher();
        var source = server.getCommandSource().withWorld(player.getServerWorld()).withPosition(player.getPos());

        // Zone-level: check all zones at player's position
        var regions = CheeseGuardMod.getInstance().getRegionManager().getRegionsAt(
            worldId, (int) player.getX(), (int) player.getY(), (int) player.getZ());
        for (var region : regions) {
            for (String raw : region.getCommandsOnBattleLoss()) {
                if (raw == null || raw.isBlank()) continue;
                String cmd = raw.replace("{player}", playerName).trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                try { dispatcher.execute(cmd, source); }
                catch (Exception e) { CheeseGuardMod.LOGGER.warn("[CG] Battle loss zone cmd failed: '{}' — {}", cmd, e.getMessage()); }
            }
        }

        // Dimension-level: check dimension config
        var dimMgr = CheeseGuardMod.getInstance().getDimensionManager();
        var dimCfg = dimMgr.getConfigByWorldId(worldId);
        if (dimCfg != null) {
            for (String raw : dimCfg.getCommandsOnBattleLoss()) {
                if (raw == null || raw.isBlank()) continue;
                String cmd = raw.replace("{player}", playerName).trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                try { dispatcher.execute(cmd, source); }
                catch (Exception e) { CheeseGuardMod.LOGGER.warn("[CG] Battle loss dim cmd failed: '{}' — {}", cmd, e.getMessage()); }
            }
        }
    }

    private static UUID getActorPlayerUuid(Object actor) {
        try {
            Method getUuids = findMethod(actor.getClass(), "getPlayerUUIDs", "getUuid", "uuid");
            if (getUuids != null) {
                getUuids.setAccessible(true);
                Object result = getUuids.invoke(actor);
                if (result instanceof UUID u) return u;
                if (result instanceof Iterable<?> iter) {
                    for (Object o : iter) { if (o instanceof UUID u) return u; }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Method findMethod(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(name)) return m;
                }
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(name)) return m;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static boolean isAvailable() { return available; }


    /**
     * Recall all cobblemon entities owned by a player.
     * Tries multiple known Cobblemon recall commands.
     */
    public static void recallPlayerPokemon(ServerPlayerEntity player) {
        if (!available) return;

        // Try multiple known Cobblemon recall commands
        var server = player.getServer();
        if (server == null) return;
        var src = server.getCommandSource().withSilent();
        String name = player.getName().getString();

        for (String cmd : new String[]{
            "pokemonrecall " + name,
            "recall " + name,
            "cobblemon recall " + name
        }) {
            try {
                server.getCommandManager().getDispatcher().execute(cmd, src);
                return; // Success
            } catch (Exception ignored) {}
        }

        CheeseGuardMod.LOGGER.debug("[CG] No recall command found for Cobblemon");
    }
}
