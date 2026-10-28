package com.cobbleshift;

import com.google.gson.JsonObject;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages passive abilities based on the player's lead Pokémon (slot 0).
 * Auto mode: checks lead Pokémon every N ticks, applies type-based effects.
 * Force mode: admin can override a player's ability to a specific species.
 * Supports global enable/disable and per-player disable.
 */
public class AbilityManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobbleshift");

    private final CobbleShiftConfig config;
    private final Map<UUID, AbilityData> activeAbilities = new ConcurrentHashMap<>();
    private final Set<UUID> disabledPlayers = ConcurrentHashMap.newKeySet();
    private MinecraftServer server;
    private int tickCounter = 0;

    public AbilityManager(CobbleShiftConfig config) {
        this.config = config;
    }

    public void setServer(MinecraftServer server) { this.server = server; }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % config.getAbilityTickInterval() != 0) return;

        // Global disable check
        if (!config.isAbilityGlobalEnabled()) return;

        if (!config.isAbilityAutoMode() && activeAbilities.isEmpty()) return;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();

            // Skip per-player disabled
            if (disabledPlayers.contains(uuid)) continue;

            AbilityData existing = activeAbilities.get(uuid);

            // If forced, just refresh effects — don't check lead
            if (existing != null && existing.forced()) {
                applyEffects(player, existing.type1(), existing.type2());
                continue;
            }

            // Auto mode: check lead Pokémon
            if (config.isAbilityAutoMode()) {
                checkAndUpdateAbility(player);
            }

            // Refresh effects for active abilities
            if (activeAbilities.containsKey(uuid)) {
                AbilityData data = activeAbilities.get(uuid);
                applyEffects(player, data.type1(), data.type2());
            }
        }
    }

    // ── Auto Mode ────────────────────────────────────────────────────────────

    private void checkAndUpdateAbility(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        LeadPokemonInfo lead = getLeadPokemon(player);

        if (lead == null) {
            if (activeAbilities.containsKey(uuid)) {
                activeAbilities.remove(uuid);
                sendAbilitySync(player, null);
            }
            return;
        }

        AbilityData existing = activeAbilities.get(uuid);
        if (existing != null && existing.speciesName().equalsIgnoreCase(lead.species)) {
            return;
        }

        AbilityData newAbility = new AbilityData(uuid, lead.species, lead.type1, lead.type2, false, System.currentTimeMillis());
        activeAbilities.put(uuid, newAbility);
        sendAbilitySync(player, newAbility);

        LOGGER.debug("[CobbleShift] Ability updated for {}: {} ({}{})",
            player.getName().getString(), lead.species, lead.type1,
            lead.type2 != null ? "/" + lead.type2 : "");
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void forceAbility(ServerPlayerEntity player, String speciesName) {
        SpeciesMapper.SpeciesStats stats = SpeciesMapper.resolve(speciesName);
        String type1 = stats != null ? stats.type1() : "normal";
        String type2 = stats != null ? stats.type2() : null;

        AbilityData data = new AbilityData(player.getUuid(), speciesName, type1, type2, true, System.currentTimeMillis());
        activeAbilities.put(player.getUuid(), data);
        applyEffects(player, type1, type2);
        sendAbilitySync(player, data);
    }

    public void clearForce(ServerPlayerEntity player) {
        AbilityData existing = activeAbilities.get(player.getUuid());
        if (existing != null && existing.forced()) {
            activeAbilities.remove(player.getUuid());
            sendAbilitySync(player, null);
        }
    }

    public void deactivateAbility(ServerPlayerEntity player) {
        activeAbilities.remove(player.getUuid());
        sendAbilitySync(player, null);
    }

    public void deactivateAll() {
        activeAbilities.clear();
    }

    /**
     * Deactivate all abilities and send sync packets to clear the HUD on all connected clients.
     */
    public void deactivateAllAndSync() {
        activeAbilities.clear();
        if (server != null) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                sendAbilitySync(player, null);
            }
        }
    }

    // ── Per-Player Toggle ─────────────────────────────────────────────────────

    /**
     * Toggle ability for a specific player. Returns true if now disabled.
     */
    public boolean togglePlayer(UUID uuid) {
        if (disabledPlayers.contains(uuid)) {
            disabledPlayers.remove(uuid);
            return false;
        } else {
            disabledPlayers.add(uuid);
            activeAbilities.remove(uuid);
            // Send clear to client
            if (server != null) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) sendAbilitySync(player, null);
            }
            return true;
        }
    }

    public boolean isPlayerDisabled(UUID uuid) { return disabledPlayers.contains(uuid); }
    public Set<UUID> getDisabledPlayers() { return Collections.unmodifiableSet(disabledPlayers); }

    public boolean hasAbility(UUID uuid) { return activeAbilities.containsKey(uuid); }
    public AbilityData getAbility(UUID uuid) { return activeAbilities.get(uuid); }
    public Collection<AbilityData> getAllAbilities() { return Collections.unmodifiableCollection(activeAbilities.values()); }

    // ── Effect Application ───────────────────────────────────────────────────

    private void applyEffects(ServerPlayerEntity player, String type1, String type2) {
        int duration = config.getAbilityEffectDuration();

        AbilityType primary = AbilityType.fromTypeName(type1);
        for (StatusEffectInstance effect : primary.getEffects(duration)) {
            player.addStatusEffect(effect);
        }

        if (type2 != null && !type2.isBlank()) {
            AbilityType secondary = AbilityType.fromTypeName(type2);
            for (StatusEffectInstance effect : secondary.getEffects(duration)) {
                player.addStatusEffect(effect);
            }
        }
    }

    // ── Cobblemon Lead Pokémon Resolution (robust) ────────────────────────────

    private record LeadPokemonInfo(String species, String type1, String type2) {}

    /**
     * Get the player's lead Pokémon (slot 0) via Cobblemon reflection.
     * Robust version: tries multiple method signatures/fallbacks at each step.
     */
    private LeadPokemonInfo getLeadPokemon(ServerPlayerEntity player) {
        try {
            // Step 1: Get party store
            Class<?> cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object cobblemonInstance = cobblemonClass.getField("INSTANCE").get(null);
            Object storage = cobblemonClass.getMethod("getStorage").invoke(cobblemonInstance);

            // Try getParty(ServerPlayerEntity) first, then getParty(UUID)
            Object party = null;
            try {
                Method getPartyPlayer = storage.getClass().getMethod("getParty", net.minecraft.server.network.ServerPlayerEntity.class);
                party = getPartyPlayer.invoke(storage, player);
            } catch (NoSuchMethodException e1) {
                LOGGER.debug("[CobbleShift] getParty(ServerPlayerEntity) not found, trying UUID");
            }
            if (party == null) {
                try {
                    Method getPartyUuid = storage.getClass().getMethod("getParty", UUID.class);
                    Object result = getPartyUuid.invoke(storage, player.getUuid());
                    // May return CompletableFuture
                    if (result instanceof java.util.concurrent.CompletableFuture<?> cf) {
                        party = cf.getNow(null);
                    } else {
                        party = result;
                    }
                } catch (NoSuchMethodException e2) {
                    LOGGER.debug("[CobbleShift] getParty(UUID) not found either");
                }
            }
            if (party == null) return null;

            // Step 2: Get slot 0 — try get(int), then getSlot(int), then iterator
            Object pokemon = null;
            try {
                Method getSlot = party.getClass().getMethod("get", int.class);
                pokemon = getSlot.invoke(party, 0);
            } catch (NoSuchMethodException e) {
                try {
                    Method getSlotAlt = party.getClass().getMethod("getSlot", int.class);
                    pokemon = getSlotAlt.invoke(party, 0);
                } catch (NoSuchMethodException e2) {
                    // Try iterator as last resort
                    if (party instanceof Iterable<?> it) {
                        Iterator<?> iter = it.iterator();
                        if (iter.hasNext()) pokemon = iter.next();
                    }
                }
            }
            if (pokemon == null) return null;

            // Step 3: Get species
            Method getSpecies = pokemon.getClass().getMethod("getSpecies");
            Object species = getSpecies.invoke(pokemon);
            if (species == null) return null;

            // Step 4: Get species name — try getName() then showdownId()
            String speciesName = null;
            try {
                Object nameObj = species.getClass().getMethod("getName").invoke(species);
                if (nameObj != null) {
                    // May be ResourceIdentifier — try getPath()
                    try {
                        speciesName = (String) nameObj.getClass().getMethod("getPath").invoke(nameObj);
                    } catch (NoSuchMethodException e) {
                        String full = nameObj.toString();
                        int colon = full.indexOf(':');
                        speciesName = colon >= 0 ? full.substring(colon + 1) : full;
                    }
                }
            } catch (Exception e) {
                try {
                    speciesName = (String) species.getClass().getMethod("showdownId").invoke(species);
                } catch (Exception ignored) {}
            }
            if (speciesName == null || speciesName.isEmpty()) return null;

            // Step 5: Get types
            Object primaryType = species.getClass().getMethod("getPrimaryType").invoke(species);
            String type1 = extractTypeName(primaryType);
            String type2 = null;
            try {
                Object secondaryType = species.getClass().getMethod("getSecondaryType").invoke(species);
                if (secondaryType != null) type2 = extractTypeName(secondaryType);
            } catch (Exception ignored) {}

            return new LeadPokemonInfo(speciesName, type1, type2);

        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Failed to read lead Pokémon: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract type name string from Cobblemon ElementalType object.
     */
    private String extractTypeName(Object typeObj) {
        if (typeObj == null) return "unknown";
        for (String getter : new String[]{"getName", "getDisplayName", "name"}) {
            try {
                Object result = typeObj.getClass().getMethod(getter).invoke(typeObj);
                if (result instanceof String s && !s.isEmpty()) return s.toLowerCase();
            } catch (Exception ignored) {}
        }
        // Fallback: try field access
        try {
            java.lang.reflect.Field nameField = typeObj.getClass().getDeclaredField("name");
            nameField.setAccessible(true);
            Object val = nameField.get(typeObj);
            if (val instanceof String str && !str.isEmpty()) return str.toLowerCase();
        } catch (Exception ignored) {}
        return typeObj.toString().toLowerCase();
    }

    /**
     * Send ability sync packet to client for HUD display.
     */
    private void sendAbilitySync(ServerPlayerEntity player, AbilityData data) {
        try {
            var payload = new com.cobbleshift.network.AbilitySyncPayload(
                data != null ? data.speciesName() : "",
                data != null ? data.type1() : "",
                data != null ? data.type2() : ""
            );
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, com.cobbleshift.network.AbilitySyncPayload.ID)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Failed to send ability sync: {}", e.getMessage());
        }
    }

    // ── Data Record ──────────────────────────────────────────────────────────

    public record AbilityData(UUID playerUuid, String speciesName, String type1, String type2,
                              boolean forced, long activatedAt) {
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("playerUuid", playerUuid.toString());
            obj.addProperty("species", speciesName);
            obj.addProperty("type1", type1);
            if (type2 != null) obj.addProperty("type2", type2);
            obj.addProperty("forced", forced);
            obj.addProperty("activatedAt", activatedAt);
            return obj;
        }
    }
}
