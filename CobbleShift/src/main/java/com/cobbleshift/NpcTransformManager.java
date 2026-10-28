package com.cobbleshift;

import com.cobbleshift.network.TransformSyncPayload;
import com.google.gson.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages NPC (EasyNPC) → Pokémon visual transformations.
 *
 * Unlike player transforms (session-only, with attributes/abilities/cooldowns),
 * NPC transforms are purely visual and persist across server restarts via JSON.
 */
public class NpcTransformManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobbleshift");
    private static final String CONFIG_FILE = "config/cobbleshift_npc_transforms.json";
    private static final Identifier NPC_SPEED_MOD_ID = Identifier.of("cobbleshift", "npc_speed");

    private final Map<UUID, NpcTransformData> npcTransforms = new ConcurrentHashMap<>();
    private MinecraftServer server;
    private boolean needsReapply = false;

    // ── Data ──────────────────────────────────────────────────────────────────

    public record NpcTransformData(String species, boolean shiny, String form,
                                   String type1, String type2, float scale, float speed) {
        /** Constructor with default scale (1.0) and speed (1.0). */
        public NpcTransformData(String species, boolean shiny, String form, String type1, String type2) {
            this(species, shiny, form, type1, type2, 1.0f, 1.0f);
        }

        /** Constructor with scale, default speed (1.0). */
        public NpcTransformData(String species, boolean shiny, String form, String type1, String type2, float scale) {
            this(species, shiny, form, type1, type2, scale, 1.0f);
        }

        /** Encode species with shiny/form info (same format as player transforms). */
        public String getEncodedSpecies() {
            StringBuilder sb = new StringBuilder(species);
            if (shiny) sb.append("|shiny");
            if (form != null && !form.isEmpty()) sb.append("|").append(form);
            return sb.toString();
        }

        /** Return a copy with a new scale value. */
        public NpcTransformData withScale(float newScale) {
            return new NpcTransformData(species, shiny, form, type1, type2, newScale, speed);
        }

        /** Return a copy with a new speed value. */
        public NpcTransformData withSpeed(float newSpeed) {
            return new NpcTransformData(species, shiny, form, type1, type2, scale, newSpeed);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void setServer(MinecraftServer server) { this.server = server; }

    public void onServerStart(MinecraftServer srv) {
        this.server = srv;
        load();
        if (!npcTransforms.isEmpty()) {
            needsReapply = true;
            LOGGER.info("[CobbleShift] Loaded {} NPC transform(s), will re-apply on first tick", npcTransforms.size());
        }
    }

    public void onServerStop() {
        save();
    }

    /**
     * Called every server tick. On first tick after start, broadcasts all NPC
     * transforms so clients know to render Pokémon ghosts.
     */
    private int speedReapplyTick = 0;

    public void tick(MinecraftServer srv) {
        if (needsReapply) {
            needsReapply = false;
            LOGGER.info("[CobbleShift] {} NPC transform(s) ready for client sync", npcTransforms.size());
        }

        // Every 100 ticks (~5s), re-apply speed modifiers to loaded NPC entities
        // that have a custom speed but don't yet have the modifier (e.g. after chunk load)
        if (++speedReapplyTick >= 100) {
            speedReapplyTick = 0;
            for (var entry : npcTransforms.entrySet()) {
                NpcTransformData data = entry.getValue();
                if (data.speed() == 1.0f) continue; // no custom speed
                Entity entity = findEntity(entry.getKey());
                if (entity instanceof LivingEntity living) {
                    EntityAttributeInstance speedAttr = living.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                    if (speedAttr != null && speedAttr.getModifier(NPC_SPEED_MOD_ID) == null) {
                        applySpeedModifier(living, data.speed());
                        LOGGER.debug("[CobbleShift] Re-applied speed {} to NPC {}", data.speed(), entry.getKey().toString().substring(0, 8));
                    }
                }
            }
        }
    }

    // ── Core API ──────────────────────────────────────────────────────────────

    /**
     * Transform an NPC into a Pokémon visually.
     * @return true if successful, false if species not found
     */
    public boolean transformNpc(Entity npc, String speciesName, boolean shiny, String form) {
        if (npc == null || speciesName == null || speciesName.isBlank()) return false;

        // Strip namespace if present
        if (speciesName.contains(":")) {
            speciesName = speciesName.substring(speciesName.indexOf(':') + 1);
        }

        // Validate species
        SpeciesMapper.SpeciesStats stats = SpeciesMapper.resolve(speciesName);
        if (stats == null) {
            LOGGER.warn("[CobbleShift] NPC transform: species '{}' not found", speciesName);
            return false;
        }

        UUID npcUuid = npc.getUuid();

        // Revert existing transform first
        if (npcTransforms.containsKey(npcUuid)) {
            revertNpc(npc);
        }

        NpcTransformData data = new NpcTransformData(speciesName, shiny, form, stats.type1(), stats.type2());
        npcTransforms.put(npcUuid, data);

        // No invisibility — the client-side mixin cancels the NPC render
        // and renders the Pokémon ghost instead. Invisibility would cause
        // WorldRenderer to skip the entity entirely, preventing our mixin from firing.

        // Broadcast sync to all clients
        broadcastNpcTransformSync(npcUuid, data.getEncodedSpecies(), stats.type1(), stats.type2(), data.scale());

        save();
        LOGGER.info("[CobbleShift] NPC {} transformed → {} (shiny={}, form={})",
            npcUuid.toString().substring(0, 8), speciesName, shiny, form);
        return true;
    }

    /**
     * Revert an NPC's transformation.
     * @return true if the NPC was transformed, false otherwise
     */
    public boolean revertNpc(Entity npc) {
        if (npc == null) return false;
        UUID npcUuid = npc.getUuid();
        NpcTransformData removed = npcTransforms.remove(npcUuid);
        if (removed == null) return false;

        // Remove speed modifier
        if (npc instanceof LivingEntity living) {
            removeSpeedModifier(living);
        }

        // Broadcast empty species = revert
        broadcastNpcTransformSync(npcUuid, "", null, null, 1.0f);

        save();
        LOGGER.info("[CobbleShift] NPC {} reverted from {}", npcUuid.toString().substring(0, 8), removed.species());
        return true;
    }

    /**
     * Revert all NPC transforms (admin command).
     */
    public int revertAll() {
        if (server == null) return 0;
        int count = 0;
        for (UUID npcUuid : new ArrayList<>(npcTransforms.keySet())) {
            Entity found = findEntity(npcUuid);
            if (found != null) {
                revertNpc(found);
            } else {
                // Entity not loaded — just remove from map and broadcast
                npcTransforms.remove(npcUuid);
                broadcastNpcTransformSync(npcUuid, "", null, null, 1.0f);
            }
            count++;
        }
        save();
        return count;
    }

    /**
     * Set the visual scale of a transformed NPC's Pokémon model.
     * @return true if the NPC was transformed and scale updated
     */
    public boolean setNpcScale(UUID npcUuid, float scale) {
        NpcTransformData data = npcTransforms.get(npcUuid);
        if (data == null) return false;

        scale = Math.max(0.1f, Math.min(10.0f, scale));
        NpcTransformData updated = data.withScale(scale);
        npcTransforms.put(npcUuid, updated);

        broadcastNpcTransformSync(npcUuid, updated.getEncodedSpecies(), updated.type1(), updated.type2(), updated.scale());

        save();
        LOGGER.info("[CobbleShift] NPC {} scale set to {}", npcUuid.toString().substring(0, 8), scale);
        return true;
    }

    /**
     * Set the movement speed multiplier of a transformed NPC.
     * 1.0 = normal speed, 2.0 = double, 0.5 = half.
     * @return true if the NPC was transformed and speed updated
     */
    public boolean setNpcSpeed(UUID npcUuid, float speed) {
        NpcTransformData data = npcTransforms.get(npcUuid);
        if (data == null) return false;

        speed = Math.max(0.1f, Math.min(10.0f, speed));
        NpcTransformData updated = data.withSpeed(speed);
        npcTransforms.put(npcUuid, updated);

        // Apply the speed modifier to the entity if it's loaded
        Entity entity = findEntity(npcUuid);
        if (entity instanceof LivingEntity living) {
            applySpeedModifier(living, speed);
        }

        save();
        LOGGER.info("[CobbleShift] NPC {} speed set to {}", npcUuid.toString().substring(0, 8), speed);
        return true;
    }

    /**
     * Apply or update GENERIC_MOVEMENT_SPEED modifier on a living entity.
     * Uses MULTIPLY_BASE: speed=1.0 → no change, speed=2.0 → double speed.
     */
    private void applySpeedModifier(LivingEntity entity, float speed) {
        EntityAttributeInstance speedAttr = entity.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speedAttr == null) return;
        speedAttr.removeModifier(NPC_SPEED_MOD_ID);
        if (speed != 1.0f) {
            speedAttr.addPersistentModifier(new EntityAttributeModifier(
                NPC_SPEED_MOD_ID, speed - 1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE
            ));
        }
    }

    /**
     * Remove our speed modifier from the entity.
     */
    private void removeSpeedModifier(LivingEntity entity) {
        EntityAttributeInstance speedAttr = entity.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speedAttr != null) speedAttr.removeModifier(NPC_SPEED_MOD_ID);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public boolean isNpcTransformed(UUID uuid) { return npcTransforms.containsKey(uuid); }
    public NpcTransformData getNpcTransform(UUID uuid) { return npcTransforms.get(uuid); }
    public Map<UUID, NpcTransformData> getAllTransforms() { return Collections.unmodifiableMap(npcTransforms); }

    /** Find an entity by UUID across all loaded worlds. */
    private Entity findEntity(UUID uuid) {
        if (server == null) return null;
        for (ServerWorld world : server.getWorlds()) {
            Entity e = world.getEntity(uuid);
            if (e != null) return e;
        }
        return null;
    }

    // ── Player join sync ──────────────────────────────────────────────────────

    /**
     * Send all NPC transforms to a joining player so they see existing transforms.
     */
    public void broadcastAllNpcTransforms(ServerPlayerEntity joiningPlayer) {
        if (npcTransforms.isEmpty()) return;
        for (var entry : npcTransforms.entrySet()) {
            UUID npcUuid = entry.getKey();
            NpcTransformData data = entry.getValue();
            try {
                // silent=true + fromNpc=true: sync existing NPC transforms without cry/particles
                var payload = new TransformSyncPayload(npcUuid, data.getEncodedSpecies(), data.type1(), data.type2(), data.scale(), true, true);
                if (ServerPlayNetworking.canSend(joiningPlayer, TransformSyncPayload.ID)) {
                    ServerPlayNetworking.send(joiningPlayer, payload);
                }
            } catch (Exception e) {
                LOGGER.debug("[CobbleShift] Failed to send NPC transform to {}: {}",
                    joiningPlayer.getName().getString(), e.getMessage());
            }
        }
    }

    // ── Detection helper ──────────────────────────────────────────────────────

    /**
     * Check if an entity is an EasyNPC entity (namespace check).
     */
    public static boolean isEasyNpc(Entity entity) {
        if (entity == null) return false;
        Identifier entityId = Registries.ENTITY_TYPE.getId(entity.getType());
        return entityId != null && entityId.getNamespace().equals("easy_npc");
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private void broadcastNpcTransformSync(UUID npcUuid, String species, String type1, String type2, float scale) {
        if (server == null) return;
        try {
            // fromNpc=true: clients must NEVER play a cry or spawn transform particles for NPCs.
            // silent is left false so this flag drives the suppression unambiguously.
            var payload = new TransformSyncPayload(npcUuid, species, type1, type2, scale, false, true);
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (ServerPlayNetworking.canSend(p, TransformSyncPayload.ID)) {
                    ServerPlayNetworking.send(p, payload);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Failed to broadcast NPC transform sync: {}", e.getMessage());
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        Path path = Path.of(CONFIG_FILE);
        if (!Files.exists(path)) return;

        try (Reader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            JsonArray arr = JsonParser.parseReader(reader).getAsJsonArray();
            npcTransforms.clear();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                UUID uuid = UUID.fromString(obj.get("npcUuid").getAsString());
                String species = obj.get("species").getAsString();
                boolean shiny = obj.has("shiny") && obj.get("shiny").getAsBoolean();
                String form = obj.has("form") && !obj.get("form").isJsonNull() ? obj.get("form").getAsString() : null;
                String type1 = obj.has("type1") && !obj.get("type1").isJsonNull() ? obj.get("type1").getAsString() : null;
                String type2 = obj.has("type2") && !obj.get("type2").isJsonNull() ? obj.get("type2").getAsString() : null;
                float scale = obj.has("scale") ? obj.get("scale").getAsFloat() : 1.0f;
                float speed = obj.has("speed") ? obj.get("speed").getAsFloat() : 1.0f;
                npcTransforms.put(uuid, new NpcTransformData(species, shiny, form, type1, type2, scale, speed));
            }
            LOGGER.info("[CobbleShift] Loaded {} NPC transform(s) from {}", npcTransforms.size(), CONFIG_FILE);
        } catch (Exception e) {
            LOGGER.error("[CobbleShift] Failed to load NPC transforms: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            Path path = Path.of(CONFIG_FILE);
            Files.createDirectories(path.getParent());

            JsonArray arr = new JsonArray();
            for (var entry : npcTransforms.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("npcUuid", entry.getKey().toString());
                NpcTransformData data = entry.getValue();
                obj.addProperty("species", data.species());
                obj.addProperty("shiny", data.shiny());
                obj.addProperty("form", data.form());
                obj.addProperty("type1", data.type1());
                obj.addProperty("type2", data.type2());
                if (data.scale() != 1.0f) obj.addProperty("scale", data.scale());
                if (data.speed() != 1.0f) obj.addProperty("speed", data.speed());
                arr.add(obj);
            }

            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(arr, writer);
            }
        } catch (Exception e) {
            LOGGER.error("[CobbleShift] Failed to save NPC transforms: {}", e.getMessage());
        }
    }
}
