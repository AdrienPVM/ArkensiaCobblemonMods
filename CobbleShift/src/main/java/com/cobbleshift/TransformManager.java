package com.cobbleshift;

import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all active player → Pokémon transformations.
 *
 * Mechanism (true model swap — no server-side entity):
 * 1. Player becomes invisible (Invisibility effect, max duration, no particles)
 * 2. Attribute modifiers are applied based on species stats (speed, health)
 * 3. S2C packet is broadcast — clients render the Pokémon model via ghost entity
 * 4. Attack cooldowns are ticked server-side
 */
public class TransformManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobbleshift");
    private static final Identifier SPEED_MOD_ID = Identifier.of("cobbleshift", "transform_speed");
    private static final Identifier HEALTH_MOD_ID = Identifier.of("cobbleshift", "transform_health");
    private static final Identifier SCALE_MOD_ID = Identifier.of("cobbleshift", "transform_scale");

    private final Map<UUID, TransformData> activeTransforms = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRevertTime = new ConcurrentHashMap<>();
    // Animation sync: track last broadcast state per player to avoid redundant packets
    private final Map<UUID, Integer> lastBroadcastEnvState = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> lastBroadcastMoving = new ConcurrentHashMap<>();
    private int animSyncTickCounter = 0;
    private MinecraftServer server;

    public void setServer(MinecraftServer server) { this.server = server; }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Transform a player into a Pokémon species (no shiny/form).
     */
    public boolean transform(ServerPlayerEntity player, String speciesName) {
        return transform(player, speciesName, false, null);
    }

    /**
     * Transform with attacks control.
     * @param attacksEnabled if false, the player cannot fire projectiles while transformed
     */
    public boolean transform(ServerPlayerEntity player, String speciesName, boolean attacksEnabled) {
        boolean result = transform(player, speciesName, false, null);
        if (result && !attacksEnabled) {
            TransformData data = activeTransforms.get(player.getUuid());
            if (data != null) data.setAttacksEnabled(false);
        }
        return result;
    }

    /**
     * Transform a player into a Pokémon species with optional shiny + form.
     * @return true if successful, false if species not found or Cobblemon unavailable
     */
    public boolean transform(ServerPlayerEntity player, String speciesName, boolean shiny, String form) {
        if (player == null || speciesName == null || speciesName.isBlank()) return false;
        UUID uuid = player.getUuid();

        // Cooldown check — only applies when player is NOT already transformed (switching = no cooldown)
        CobbleShiftConfig cfgCheck = CobbleShiftMod.INSTANCE.getConfig();
        if (cfgCheck.isTransformCooldownEnabled() && !activeTransforms.containsKey(uuid)) {
            Long lastRevert = lastRevertTime.get(uuid);
            if (lastRevert != null) {
                long elapsed = System.currentTimeMillis() - lastRevert;
                long cooldownMs = cfgCheck.getTransformCooldownSeconds() * 1000L;
                if (elapsed < cooldownMs) {
                    int remaining = (int) ((cooldownMs - elapsed) / 1000) + 1;
                    player.sendMessage(Text.translatable("cobbleshift.player.cooldown", remaining).formatted(Formatting.RED), true);
                    return false;
                }
            }
        }

        // Strip namespace prefix if present (e.g., "cobblemon:pikachu" → "pikachu")
        if (speciesName.contains(":")) {
            speciesName = speciesName.substring(speciesName.indexOf(':') + 1);
        }

        // Validate species exists in Cobblemon
        SpeciesMapper.SpeciesStats stats = SpeciesMapper.resolve(speciesName);
        if (stats == null) {
            LOGGER.warn("[CobbleShift] Species '{}' not found in Cobblemon registry", speciesName);
            return false;
        }

        // Revert existing transform first (no revert sound when switching pokemon)
        if (activeTransforms.containsKey(uuid)) {
            revert(player, false);
        }

        String type1 = stats.type1();
        String type2 = stats.type2();
        int bst = SpeciesMapper.computeBST(stats.hp(), stats.atk(), stats.def(), stats.spAtk(), stats.spDef(), stats.speed());

        TransformData data = new TransformData(uuid, player.getName().getString(), speciesName, type1, type2, bst, shiny, form, stats.hitboxHeight());

        // Save original attributes
        EntityAttributeInstance speedAttr = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        EntityAttributeInstance healthAttr = player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (speedAttr != null) data.setOriginalSpeed(speedAttr.getBaseValue());
        if (healthAttr != null) data.setOriginalMaxHealth(healthAttr.getBaseValue());

        // Apply invisibility (max duration, ambient, no particles)
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.INVISIBILITY, Integer.MAX_VALUE, 0, true, false, false
        ));

        // Apply attribute modifiers based on species stats
        CobbleShiftConfig cfg = CobbleShiftMod.INSTANCE.getConfig();
        if (cfg.isTransformSpeedEnabled() && speedAttr != null) {
            double speedMod = SpeciesMapper.mapSpeed(stats.speed()) - 0.1;
            speedAttr.removeModifier(SPEED_MOD_ID);
            speedAttr.addPersistentModifier(new EntityAttributeModifier(
                SPEED_MOD_ID, speedMod, EntityAttributeModifier.Operation.ADD_VALUE
            ));
        }
        if (cfg.isTransformHealthEnabled() && healthAttr != null) {
            double healthTarget = SpeciesMapper.mapMaxHealth(stats.hp());
            double healthMod = healthTarget - 20.0;
            healthAttr.removeModifier(HEALTH_MOD_ID);
            healthAttr.addPersistentModifier(new EntityAttributeModifier(
                HEALTH_MOD_ID, healthMod, EntityAttributeModifier.Operation.ADD_VALUE
            ));
            player.setHealth(player.getMaxHealth());
        }
        // Apply scale based on Pokémon actual entity height (matches F3+B gray hitbox)
        if (cfg.isTransformScaleEnabled()) {
            EntityAttributeInstance scaleAttr = player.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
            if (scaleAttr != null) {
                // Try to create a temporary PokemonEntity to get the ACTUAL entity height
                // This is the most accurate approach — matches the gray F3+B hitbox exactly
                float actualHeight = SpeciesMapper.resolveActualHeight(speciesName, player.getWorld());
                float scaleHeight = actualHeight > 0 ? actualHeight : stats.hitboxHeight();
                float scale = SpeciesMapper.mapScale(scaleHeight);
                double scaleMod = scale - 1.0; // base scale is 1.0
                scaleAttr.removeModifier(SCALE_MOD_ID);
                scaleAttr.addPersistentModifier(new EntityAttributeModifier(
                    SCALE_MOD_ID, scaleMod, EntityAttributeModifier.Operation.ADD_VALUE
                ));
                LOGGER.info("[CobbleShift] Applied GENERIC_SCALE: actualH={}, statsH={}, scale={}, scaleMod={}, finalScale={}",
                    actualHeight, stats.hitboxHeight(), scale, scaleMod, scaleAttr.getValue());
            } else {
                LOGGER.warn("[CobbleShift] GENERIC_SCALE attribute is NULL for player {}", player.getName().getString());
            }
        } else {
            LOGGER.info("[CobbleShift] Transform scale is DISABLED in config");
        }

        activeTransforms.put(uuid, data);

        // Broadcast sync packet — encode shiny/form in species string
        broadcastTransformSync(uuid, data.getEncodedSpecies(), type1, type2, data.getCustomScale());

        // Spawn server-side particles for visual transform effect
        spawnTransformParticles(player);

        LOGGER.info("[CobbleShift] Transformed {} → {} (shiny={}, form={}, BST={})",
            player.getName().getString(), speciesName, shiny, form, bst);
        String displayName = speciesName + (shiny ? " shiny" : "") + (form != null ? " " + form : "");
        player.sendMessage(Text.translatable("cobbleshift.player.transformed", displayName).formatted(Formatting.GREEN), false);
        return true;
    }

    /**
     * Revert a player's transformation.
     */
    public boolean revert(ServerPlayerEntity player) {
        return revert(player, true);
    }

    /**
     * Revert a player's transformation.
     * @param playSound if true, play villager celebrate sound (false when switching pokemon)
     */
    public boolean revert(ServerPlayerEntity player, boolean playSound) {
        if (player == null) return false;
        UUID uuid = player.getUuid();
        TransformData data = activeTransforms.remove(uuid);
        if (data == null) return false;

        // Record revert time for cooldown
        lastRevertTime.put(uuid, System.currentTimeMillis());
        // Clean up animation sync state
        lastBroadcastEnvState.remove(uuid);
        lastBroadcastMoving.remove(uuid);

        // Remove invisibility
        player.removeStatusEffect(StatusEffects.INVISIBILITY);

        // Remove attribute modifiers
        EntityAttributeInstance speedAttr = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        EntityAttributeInstance healthAttr = player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        EntityAttributeInstance scaleAttr = player.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
        if (speedAttr != null) speedAttr.removeModifier(SPEED_MOD_ID);
        if (healthAttr != null) {
            healthAttr.removeModifier(HEALTH_MOD_ID);
            if (player.getHealth() > player.getMaxHealth()) {
                player.setHealth(player.getMaxHealth());
            }
        }
        if (scaleAttr != null) scaleAttr.removeModifier(SCALE_MOD_ID);

        // Broadcast revert sync
        broadcastTransformSync(uuid, "", null, null, 1.0f);

        // Spawn revert particles
        spawnTransformParticles(player);

        // Play villager sound only on actual revert to human (not when switching pokemon)
        if (playSound) {
            try {
                ServerWorld world = (ServerWorld) player.getWorld();
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_VILLAGER_CELEBRATE, SoundCategory.PLAYERS, 1.0f, 1.0f);
            } catch (Exception e) {
                LOGGER.debug("[CobbleShift] Failed to play revert sound: {}", e.getMessage());
            }
        }

        LOGGER.info("[CobbleShift] Reverted {}", data.getPlayerName());
        player.sendMessage(Text.translatable("cobbleshift.player.reverted").formatted(Formatting.GRAY), false);
        return true;
    }

    /**
     * Revert all active transformations (server shutdown, admin command).
     */
    public void revertAll() {
        if (server == null) return;
        for (UUID uuid : new ArrayList<>(activeTransforms.keySet())) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                revert(player);
            } else {
                activeTransforms.remove(uuid);
            }
        }
    }

    public boolean isTransformed(UUID uuid) { return activeTransforms.containsKey(uuid); }
    public TransformData getTransform(UUID uuid) { return activeTransforms.get(uuid); }
    public Collection<TransformData> getAllTransforms() { return Collections.unmodifiableCollection(activeTransforms.values()); }

    /**
     * Set the visual scale of a transformed player's Pokémon model.
     * Also re-applies GENERIC_SCALE attribute modifier with the custom multiplier.
     * @return true if the player was transformed and scale updated
     */
    public boolean setPlayerScale(UUID uuid, float scale) {
        TransformData data = activeTransforms.get(uuid);
        if (data == null) return false;

        scale = Math.max(0.1f, Math.min(10.0f, scale));
        data.setCustomScale(scale);

        // Re-apply GENERIC_SCALE = mapScale(height) * customScale
        if (server != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                CobbleShiftConfig cfg = CobbleShiftMod.INSTANCE.getConfig();
                if (cfg.isTransformScaleEnabled()) {
                    EntityAttributeInstance scaleAttr = player.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
                    if (scaleAttr != null) {
                        float actualHeight = SpeciesMapper.resolveActualHeight(data.getSpeciesName(), player.getWorld());
                        float scaleHeight = actualHeight > 0 ? actualHeight : data.getPokemonHeight();
                        float baseScale = SpeciesMapper.mapScale(scaleHeight);
                        float finalScale = baseScale * scale;
                        double scaleMod = finalScale - 1.0;
                        scaleAttr.removeModifier(SCALE_MOD_ID);
                        scaleAttr.addPersistentModifier(new EntityAttributeModifier(
                            SCALE_MOD_ID, scaleMod, EntityAttributeModifier.Operation.ADD_VALUE
                        ));
                    }
                }
            }
        }

        // Re-broadcast with updated scale
        broadcastTransformSync(uuid, data.getEncodedSpecies(), data.getType1(), data.getType2(), scale);

        LOGGER.info("[CobbleShift] Player {} scale set to {}", data.getPlayerName(), scale);
        return true;
    }

    /**
     * Set a custom movement speed multiplier for a transformed player.
     * Overrides the species-based speed. Use 0 to reset to species default.
     * @return true if the player was transformed and speed updated
     */
    public boolean setPlayerSpeed(UUID uuid, float speed) {
        TransformData data = activeTransforms.get(uuid);
        if (data == null) return false;

        data.setCustomSpeed(speed);

        if (server != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                CobbleShiftConfig cfg = CobbleShiftMod.INSTANCE.getConfig();
                if (cfg.isTransformSpeedEnabled()) {
                    EntityAttributeInstance speedAttr = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                    if (speedAttr != null) {
                        speedAttr.removeModifier(SPEED_MOD_ID);
                        double speedMod;
                        if (speed > 0) {
                            // Custom speed: multiplier on base 0.1
                            speedMod = (0.1 * speed) - 0.1;
                        } else {
                            // Reset to species default
                            SpeciesMapper.SpeciesStats stats = SpeciesMapper.resolve(data.getSpeciesName());
                            speedMod = stats != null ? SpeciesMapper.mapSpeed(stats.speed()) - 0.1 : 0;
                        }
                        speedAttr.addPersistentModifier(new EntityAttributeModifier(
                            SPEED_MOD_ID, speedMod, EntityAttributeModifier.Operation.ADD_VALUE
                        ));
                    }
                }
            }
        }

        LOGGER.info("[CobbleShift] Player {} speed set to {}", data.getPlayerName(), speed > 0 ? speed + "x" : "default");
        return true;
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(MinecraftServer server) {
        if (activeTransforms.isEmpty()) return;

        CobbleShiftConfig cfg = CobbleShiftMod.INSTANCE.getConfig();
        boolean durationEnabled = cfg.isTransformMaxDurationEnabled();
        long maxMs = durationEnabled ? cfg.getTransformMaxDurationSeconds() * 1000L : 0;

        for (var entry : new ArrayList<>(activeTransforms.entrySet())) {
            TransformData data = entry.getValue();
            // Tick attack cooldowns
            data.tickCooldown();

            // Auto-revert after max duration
            if (durationEnabled) {
                long elapsed = System.currentTimeMillis() - data.getTransformedAt();
                if (elapsed >= maxMs) {
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                    if (player != null) {
                        revert(player);
                        player.sendMessage(Text.translatable("cobbleshift.player.duration_expired").formatted(Formatting.YELLOW), false);
                    } else {
                        activeTransforms.remove(entry.getKey());
                    }
                }
            }
        }

        // Broadcast animation state sync every 5 ticks (250ms)
        if (++animSyncTickCounter >= 5) {
            animSyncTickCounter = 0;
            broadcastAnimationStates(server);
        }
    }

    /**
     * Broadcast animation state (envState + isMoving) to nearby players for each transformed player.
     * Only sends if state changed since last broadcast.
     */
    private void broadcastAnimationStates(MinecraftServer server) {
        for (var entry : activeTransforms.entrySet()) {
            UUID uuid = entry.getKey();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) continue;

            // Compute environment state server-side (same logic as client)
            int envState;
            if (player.isSleeping()) {
                envState = 8; // sleep
            } else if (player.isSubmergedInWater()) {
                envState = 2; // submerged
            } else if (player.isTouchingWater()) {
                envState = 1; // surface water
            } else if (!player.isOnGround()) {
                envState = 3; // air
            } else {
                envState = 0; // ground
            }

            // Compute isMoving from velocity
            double vx = player.getVelocity().x;
            double vz = player.getVelocity().z;
            boolean isMoving = (vx * vx + vz * vz) > 0.0001; // threshold 0.01^2

            // Only broadcast if state changed
            Integer prevEnv = lastBroadcastEnvState.get(uuid);
            Boolean prevMoving = lastBroadcastMoving.get(uuid);
            if (prevEnv != null && prevEnv == envState && prevMoving != null && prevMoving == isMoving) {
                continue; // no change
            }
            lastBroadcastEnvState.put(uuid, envState);
            lastBroadcastMoving.put(uuid, isMoving);

            // Broadcast to players within 64 blocks
            var payload = new com.cobbleshift.network.AnimationStateSyncPayload(uuid, envState, isMoving);
            double px = player.getX(), pz = player.getZ();
            for (ServerPlayerEntity target : server.getPlayerManager().getPlayerList()) {
                if (target.getUuid().equals(uuid)) continue; // skip self — client computes locally
                if (target.getWorld() != player.getWorld()) continue; // different dimension
                double dx = target.getX() - px;
                double dz = target.getZ() - pz;
                if (dx * dx + dz * dz > 4096) continue; // > 64 blocks away
                if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(target, com.cobbleshift.network.AnimationStateSyncPayload.ID)) {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(target, payload);
                }
            }
        }
    }

    // ── Player Events ────────────────────────────────────────────────────────

    public void onPlayerJoin(ServerPlayerEntity player) {
        TransformData data = activeTransforms.get(player.getUuid());

        if (data == null) {
            // No active transform — clean up any stale modifiers from previous sessions
            cleanupStaleTransform(player);
            return;
        }

        // Re-apply invisibility
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.INVISIBILITY, Integer.MAX_VALUE, 0, true, false, false
        ));

        // Re-broadcast to all clients so the joining player sees existing transforms
        // and other players see this player's transform (silent=true: no cry/particles on rejoin)
        for (var entry : activeTransforms.entrySet()) {
            TransformData td = entry.getValue();
            broadcastTransformSync(entry.getKey(), td.getEncodedSpecies(), td.getType1(), td.getType2(), td.getCustomScale(), true);
        }
    }

    /**
     * Clean up leftover transform modifiers for a player with no active transform.
     * This handles cases where the server was restarted mid-transform (in-memory data lost)
     * but the persistent attribute modifiers and effects remained on the player.
     */
    private void cleanupStaleTransform(ServerPlayerEntity player) {
        boolean cleaned = false;

        // Remove stale invisibility that was applied by us (infinite duration, ambient, no particles)
        if (player.hasStatusEffect(StatusEffects.INVISIBILITY)) {
            StatusEffectInstance effect = player.getStatusEffect(StatusEffects.INVISIBILITY);
            if (effect != null && effect.isAmbient() && !effect.shouldShowParticles()) {
                player.removeStatusEffect(StatusEffects.INVISIBILITY);
                cleaned = true;
            }
        }

        // Remove stale attribute modifiers
        EntityAttributeInstance speedAttr = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        EntityAttributeInstance healthAttr = player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        EntityAttributeInstance scaleAttr = player.getAttributeInstance(EntityAttributes.GENERIC_SCALE);

        if (speedAttr != null && speedAttr.getModifier(SPEED_MOD_ID) != null) {
            speedAttr.removeModifier(SPEED_MOD_ID);
            cleaned = true;
        }
        if (healthAttr != null && healthAttr.getModifier(HEALTH_MOD_ID) != null) {
            healthAttr.removeModifier(HEALTH_MOD_ID);
            if (player.getHealth() > player.getMaxHealth()) {
                player.setHealth(player.getMaxHealth());
            }
            cleaned = true;
        }
        if (scaleAttr != null && scaleAttr.getModifier(SCALE_MOD_ID) != null) {
            scaleAttr.removeModifier(SCALE_MOD_ID);
            cleaned = true;
        }

        if (cleaned) {
            LOGGER.info("[CobbleShift] Cleaned up stale transform modifiers for {}", player.getName().getString());
        }
    }

    public void onPlayerDisconnect(UUID uuid) {
        // Keep the transform data — re-applied on rejoin
    }

    public void onPlayerDeath(ServerPlayerEntity player) {
        if (CobbleShiftMod.INSTANCE.getConfig().isAutoRevertOnDeath()) {
            revert(player);
        }
    }

    // ── Network ──────────────────────────────────────────────────────────────

    /**
     * Spawn particles at the player's position for a visual transform/revert effect.
     */
    private void spawnTransformParticles(ServerPlayerEntity player) {
        try {
            ServerWorld world = (ServerWorld) player.getWorld();
            // End rod particles (sparkle effect)
            world.spawnParticles(ParticleTypes.END_ROD,
                player.getX(), player.getY() + 1.0, player.getZ(),
                30, 0.5, 1.0, 0.5, 0.15);
            // Happy villager particles (green sparkles)
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                player.getX(), player.getY() + 0.5, player.getZ(),
                15, 0.5, 0.8, 0.5, 0.1);
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Failed to spawn transform particles: {}", e.getMessage());
        }
    }

    /**
     * Broadcast transform state to all connected players.
     */
    private void broadcastTransformSync(UUID playerUuid, String speciesName, String type1, String type2, float scale) {
        broadcastTransformSync(playerUuid, speciesName, type1, type2, scale, false);
    }

    /** Max distance (in blocks) at which the transform cry/particles are audible/visible to other players. */
    private static final int TRANSFORM_FX_RADIUS = 32;
    private static final double TRANSFORM_FX_RADIUS_SQ = (double) TRANSFORM_FX_RADIUS * TRANSFORM_FX_RADIUS;

    private void broadcastTransformSync(UUID playerUuid, String speciesName, String type1, String type2, float scale, boolean silent) {
        if (server == null) return;

        ServerPlayerEntity transformedPlayer = server.getPlayerManager().getPlayer(playerUuid);

        try {
            // Visual transform must reach every player (so distant players see the Pokémon model
            // when they later look at / approach the transformed player). The cry/particles, however,
            // must only play for players in the same dimension and within TRANSFORM_FX_RADIUS blocks.
            // We achieve that by sending an audible (silent=false) payload to nearby same-dim players
            // and a silent (silent=true) payload to everyone else. fromNpc is always false here.
            var audiblePayload = new com.cobbleshift.network.TransformSyncPayload(playerUuid, speciesName, type1, type2, scale, silent, false);
            var silentPayload  = silent
                ? audiblePayload
                : new com.cobbleshift.network.TransformSyncPayload(playerUuid, speciesName, type1, type2, scale, true, false);

            double px = transformedPlayer != null ? transformedPlayer.getX() : 0;
            double py = transformedPlayer != null ? transformedPlayer.getY() : 0;
            double pz = transformedPlayer != null ? transformedPlayer.getZ() : 0;
            net.minecraft.world.World originWorld = transformedPlayer != null ? transformedPlayer.getWorld() : null;

            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                boolean nearby = !silent
                    && transformedPlayer != null
                    && p.getWorld() == originWorld;
                if (nearby) {
                    double dx = p.getX() - px;
                    double dy = p.getY() - py;
                    double dz = p.getZ() - pz;
                    if (dx * dx + dy * dy + dz * dz > TRANSFORM_FX_RADIUS_SQ) nearby = false;
                }

                var toSend = nearby ? audiblePayload : silentPayload;
                if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, com.cobbleshift.network.TransformSyncPayload.ID)) {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, toSend);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Failed to broadcast transform sync: {}", e.getMessage());
        }
    }
}
