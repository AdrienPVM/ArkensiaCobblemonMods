package com.cobblecap.logic;

import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.ChampionNpcState;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

/**
 * Reflects the current Pokémon League Master onto a designated Cobblemon NPC
 * entity: applies the player's Mojang skin (server-side fetch via Cobblemon's
 * built-in helper) and copies the player's party so the NPC battles with the
 * same team.
 *
 * Strategy (all via reflection — no compile-time Cobblemon dependency):
 *   1. resolve the bound NPC entity by UUID and verify it is a Cobblemon NPC
 *   2. invoke a public NPC API method named like "loadTextureFromGameProfileName"
 *      with the player's name — Cobblemon handles Mojang download internally
 *   3. retrieve the player's PartyStore, clone its Pokémon, and assign a copy
 *      to the NPC via a method named like "setParty"
 *
 * Method names use Cobblemon's public Kotlin API which compiles to stable
 * named getters/setters; scanning by name keyword keeps us robust if Cobblemon
 * renames internals (we look for "texture"+"profile" / "party" tokens).
 */
public class ChampionNpcManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    public enum MorphResult { OK, NO_BINDING, NPC_NOT_FOUND, NOT_COBBLEMON_NPC, SKIN_FAILED, PARTY_FAILED, EXCEPTION }

    public record MorphOutcome(MorphResult result, String detail) {}

    /**
     * Morph the bound NPC into the given player (skin + team).
     * Must be called from the server thread.
     */
    public MorphOutcome morphTo(MinecraftServer server, ServerPlayerEntity player) {
        ChampionNpcState state = CobbleCapMod.INSTANCE.getChampionNpcState();
        if (!state.isBound()) return new MorphOutcome(MorphResult.NO_BINDING, "no champion NPC bound");

        Entity npc = findEntity(server, state.getNpcEntityUuid(), state.getDimensionId());
        if (npc == null) return new MorphOutcome(MorphResult.NPC_NOT_FOUND,
                "entity uuid=" + state.getNpcEntityUuid() + " in " + state.getDimensionId());
        if (!isCobblemonNpc(npc)) return new MorphOutcome(MorphResult.NOT_COBBLEMON_NPC,
                "entity type: " + net.minecraft.entity.EntityType.getId(npc.getType()));

        // 1. Apply player skin via Cobblemon's helper
        boolean skinOk = applySkinFromPlayerName(npc, player.getName().getString());
        if (!skinOk) {
            LOGGER.warn("[CobbleCap] Could not apply skin to champion NPC for player {}", player.getName().getString());
        }

        // 2. Clone player party and assign to NPC
        boolean partyOk = applyPlayerPartyToNpc(npc, player);
        if (!partyOk) {
            LOGGER.warn("[CobbleCap] Could not apply party to champion NPC for player {}", player.getName().getString());
        }

        if (!skinOk && !partyOk) {
            return new MorphOutcome(MorphResult.EXCEPTION, "both skin and party failed");
        }
        if (!skinOk) return new MorphOutcome(MorphResult.SKIN_FAILED, "party applied, skin failed");
        if (!partyOk) return new MorphOutcome(MorphResult.PARTY_FAILED, "skin applied, party failed");

        state.recordMorph(player.getUuid(), player.getName().getString());
        LOGGER.info("[CobbleCap] Champion NPC morphed -> {} ({})",
                player.getName().getString(), player.getUuid());
        return new MorphOutcome(MorphResult.OK, "morphed to " + player.getName().getString());
    }

    /** Bind an entity (by UUID across all loaded worlds) as the championship NPC. */
    public MorphOutcome bindByUuid(MinecraftServer server, UUID entityUuid) {
        for (ServerWorld w : server.getWorlds()) {
            Entity e = w.getEntity(entityUuid);
            if (e != null) {
                if (!isCobblemonNpc(e)) return new MorphOutcome(MorphResult.NOT_COBBLEMON_NPC,
                        "entity is " + net.minecraft.entity.EntityType.getId(e.getType()));
                CobbleCapMod.INSTANCE.getChampionNpcState().bind(entityUuid,
                        w.getRegistryKey().getValue().toString());
                return new MorphOutcome(MorphResult.OK, "bound to " + entityUuid + " in "
                        + w.getRegistryKey().getValue());
            }
        }
        return new MorphOutcome(MorphResult.NPC_NOT_FOUND, "uuid=" + entityUuid);
    }

    /** Convenience: bind the closest Cobblemon NPC within 10 blocks of the caster. */
    public MorphOutcome bindClosest(ServerPlayerEntity caster) {
        ServerWorld world = caster.getServerWorld();
        Entity best = null; double bestDist = Double.MAX_VALUE;
        for (Entity e : world.iterateEntities()) {
            if (!isCobblemonNpc(e)) continue;
            double d2 = caster.squaredDistanceTo(e);
            if (d2 < bestDist && d2 < 100) { bestDist = d2; best = e; }
        }
        if (best == null) return new MorphOutcome(MorphResult.NPC_NOT_FOUND,
                "no Cobblemon NPC within 10 blocks");
        CobbleCapMod.INSTANCE.getChampionNpcState().bind(best.getUuid(),
                world.getRegistryKey().getValue().toString());
        return new MorphOutcome(MorphResult.OK, "bound nearest NPC "
                + best.getUuid() + " (dist=" + Math.sqrt(bestDist) + ")");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static Entity findEntity(MinecraftServer server, UUID uuid, String preferredWorldId) {
        if (uuid == null) return null;
        if (preferredWorldId != null) {
            for (ServerWorld w : server.getWorlds()) {
                if (w.getRegistryKey().getValue().toString().equals(preferredWorldId)) {
                    Entity e = w.getEntity(uuid);
                    if (e != null) return e;
                }
            }
        }
        // Fallback: scan every loaded world
        for (ServerWorld w : server.getWorlds()) {
            Entity e = w.getEntity(uuid);
            if (e != null) return e;
        }
        return null;
    }

    private static boolean isCobblemonNpc(Entity e) {
        if (e == null) return false;
        String typeId = net.minecraft.entity.EntityType.getId(e.getType()).toString();
        // Cobblemon registers NPCs under cobblemon:npc (and possibly cobblemon:pokemon for trainer-style)
        return typeId.startsWith("cobblemon:") &&
                (typeId.contains("npc") || e.getClass().getSimpleName().equalsIgnoreCase("NPCEntity"));
    }

    /**
     * Apply a Mojang player skin to the NPC by invoking the public Cobblemon
     * NPC helper whose name contains both "texture" and "profile" tokens.
     */
    private static boolean applySkinFromPlayerName(Entity npc, String playerName) {
        try {
            for (Method m : npc.getClass().getMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (!n.contains("texture")) continue;
                if (!(n.contains("profile") || n.contains("playername") || n.contains("username"))) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                try {
                    m.invoke(npc, playerName);
                    LOGGER.info("[CobbleCap] Applied skin via {} -> {}", m.getName(), playerName);
                    return true;
                } catch (Exception ex) {
                    LOGGER.debug("[CobbleCap] {} threw {}", m.getName(), ex.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] applySkin reflection error: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Copy the player's current Cobblemon party and assign a clone to the NPC.
     * Looks for the NPC's public setParty(...) method, accepts whichever
     * Pokémon-collection type matches.
     */
    private static boolean applyPlayerPartyToNpc(Entity npc, ServerPlayerEntity player) {
        try {
            Object playerParty = getPlayerPartyStore(player);
            if (playerParty == null) {
                LOGGER.warn("[CobbleCap] Player {} has no party — NPC team unchanged", player.getName().getString());
                return false;
            }

            // Try direct setParty(Iterable/List/PartyStore) overloads
            for (Method m : npc.getClass().getMethods()) {
                if (!m.getName().equals("setParty")) continue;
                if (m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (p.isInstance(playerParty)) {
                    try {
                        m.invoke(npc, playerParty);
                        LOGGER.info("[CobbleCap] Applied party via {} (direct)", m.getName());
                        return true;
                    } catch (Exception ignored) {}
                }
            }

            // Alternative: iterate player's Pokémon, clone each, push into existing NPC party
            Iterable<?> playerIter = iterableOf(playerParty);
            if (playerIter == null) return false;

            Object npcParty = getNpcPartyStore(npc);
            if (npcParty == null) return false;

            // Clear existing NPC team
            invokeNoArg(npcParty, "clear");

            // For each player Pokémon: clone + add to npcParty.
            // FAIL-CLOSED: if clone is unavailable, skip the slot rather than
            // share a live reference (mutations on the player's Pokémon would
            // otherwise propagate to the NPC team and vice versa).
            int copied = 0;
            int skipped = 0;
            for (Object pokemon : playerIter) {
                if (pokemon == null) continue;
                Object cloned = invokeNoArg(pokemon, "clone", "copy", "cloneWithoutID", "createDeepCopy");
                if (cloned == null) { skipped++; continue; }
                if (addToPartyStore(npcParty, cloned)) copied++;
            }
            if (skipped > 0) {
                LOGGER.warn("[CobbleCap] Skipped {} Pokémon (no clone method) — NPC team will be incomplete", skipped);
            }
            LOGGER.info("[CobbleCap] Copied {} Pokémon into NPC party", copied);
            return copied > 0;
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] applyPlayerPartyToNpc error: {}", e.getMessage());
            return false;
        }
    }

    private static Object getPlayerPartyStore(ServerPlayerEntity player) {
        try {
            Class<?> cobblemon = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object instance = cobblemon.getField("INSTANCE").get(null);
            Object storage = invokeNoArg(instance, "getStorage");
            if (storage == null) storage = invokeNoArg(instance, "storage");
            if (storage == null) return null;
            for (Method m : storage.getClass().getMethods()) {
                String mn = m.getName();
                if (!(mn.equals("getParty") || mn.equals("getPlayerParty"))) continue;
                if (m.getParameterCount() != 1) continue;
                try { return m.invoke(storage, player); } catch (Exception ignored) {}
                try { return m.invoke(storage, player.getUuid()); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Object getNpcPartyStore(Entity npc) {
        for (Method m : npc.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (!m.getName().equalsIgnoreCase("getParty")) continue;
            try { return m.invoke(npc); } catch (Exception ignored) {}
        }
        return null;
    }

    private static Iterable<?> iterableOf(Object partyStore) {
        Object iter = invokeNoArg(partyStore, "iterator", "getPokemon", "toGappyList", "getAll");
        if (iter instanceof Iterable<?> it) return it;
        if (partyStore instanceof Iterable<?> it) return it;
        return null;
    }

    private static boolean addToPartyStore(Object partyStore, Object pokemon) {
        for (Method m : partyStore.getClass().getMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (!(m.getName().equals("add") || m.getName().equals("set"))) continue;
            if (!m.getParameterTypes()[0].isInstance(pokemon)) continue;
            try { m.invoke(partyStore, pokemon); return true; } catch (Exception ignored) {}
        }
        return false;
    }

    private static Object invokeNoArg(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            try {
                Method m = target.getClass().getMethod(name);
                if (m.getParameterCount() == 0) return m.invoke(target);
            } catch (Exception ignored) {}
        }
        return null;
    }
}
