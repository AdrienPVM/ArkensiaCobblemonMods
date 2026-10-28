package com.cobbleshift.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.registry.tag.FluidTags;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side transform state + ghost entity management.
 *
 * Ghost PokemonEntity is created client-side only (never added to world).
 * We MANUALLY set POSE_TYPE tracked data based on the player's movement state,
 * because the ghost's tick() is never called by MC.
 *
 * When the player's state changes (ground→water, ground→air, etc.), we force
 * a POSE_TYPE transition by briefly setting NONE then the target, so Cobblemon's
 * delegate re-resolves the pose with updated MoLang conditions.
 */
public class TransformRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobbleshift");

    private static final Map<UUID, String> transformedPlayers = new ConcurrentHashMap<>();
    private static final Map<UUID, String> playerTypes = new ConcurrentHashMap<>();
    private static final Map<UUID, String> playerTypes2 = new ConcurrentHashMap<>();
    private static final Map<UUID, Entity> ghostEntities = new ConcurrentHashMap<>();
    private static final Map<UUID, String> ghostSpecies = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> attackAnimTicks = new ConcurrentHashMap<>();
    private static final Map<UUID, Object> ghostDelegates = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> lastTickedAge = new ConcurrentHashMap<>();
    private static final Set<UUID> sleepingPlayers = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> modelReady = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> pendingCry = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Float> customScales = new ConcurrentHashMap<>();

    // Track previous movement state per player to detect transitions
    // State codes: 0=ground, 1=surface_water, 2=submerged, 3=air, 8=sleep
    private static final Map<UUID, Integer> prevEnvState = new ConcurrentHashMap<>();

    // Remote animation states received from server (for other players — more accurate than client-side guessing)
    private static final Map<UUID, int[]> remoteAnimStates = new ConcurrentHashMap<>(); // [envState, isMoving (0/1)]

    // Camera zoom factor (scroll wheel controlled, default 1.3 = slightly zoomed out)
    private static float zoomFactor = 1.3f;

    // Cobblemon species registry reflection
    private static boolean reflectionAttempted = false;
    private static boolean cobblemonAvailable = false;
    private static Method getByNameMethod;
    private static Object registryInstance;

    // Animation TrackedData reflection
    private static boolean animReflectionAttempted = false;
    @SuppressWarnings("rawtypes")
    private static TrackedData cobblemonMovingKey;
    @SuppressWarnings("rawtypes")
    private static TrackedData cobblemonPoseTypeKey;
    @SuppressWarnings("rawtypes")
    private static TrackedData cobblemonBeamModeKey; // Cobblemon attack beam visual (byte: 0=none, 1=physical, 2=special)
    private static Object poseTypeWalk, poseTypeStand, poseTypeFly, poseTypeHover,
            poseTypeSwim, poseTypeSleep, poseTypeFloat, poseTypeNone;

    // Delegate reflection
    private static boolean delegateReflectionAttempted = false;
    private static Method getDelegateMethod, delegateInitMethod, delegateTickMethod, delegatePartialTicksMethod;
    private static Method delegateCryMethod;
    private static Method delegateGetCurrentModelMethod;

    // PokemonEntity.cry() — works client-side (internally calls delegate.cry())
    private static Method pokemonEntityCryMethod;
    private static boolean pokemonCryReflectionAttempted = false;

    // onTrackedDataSet reflection
    private static Method onTrackedDataSetMethod;
    private static boolean trackedDataSetReflectionAttempted = false;

    // Cobblemon animation system reflection (for attack animations)
    private static boolean attackAnimReflectionAttempted = false;
    private static Method addFirstAnimationMethod; // PosableState.addFirstAnimation(Set<String>)
    private static final Set<UUID> attackAnimSuccess = ConcurrentHashMap.newKeySet();

    // Cache: does this ghost have a REAL attack animation ("physical"/"special")?
    // Probed when model becomes ready. false = no real animation, only lunge+cry fallback.
    private static final Map<UUID, Boolean> hasRealAttackAnim = new ConcurrentHashMap<>();

    // Entity fields for syncing water state
    private static Field touchingWaterField;
    private static Field submergedInWaterField;
    private static Field submergedFluidTagField;
    private static boolean waterFieldAttempted = false;

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Set transform state. Species can be encoded: "pikachu" or "pikachu|shiny" or "pikachu|shiny|alolan"
     */
    public static void setTransform(UUID playerUuid, String species, String type1, String type2) {
        transformedPlayers.put(playerUuid, species);
        playerTypes.put(playerUuid, type1 != null ? type1 : "normal");
        playerTypes2.put(playerUuid, type2 != null ? type2 : "");
        sleepingPlayers.remove(playerUuid);
        modelReady.remove(playerUuid);
        prevEnvState.remove(playerUuid);
        String prev = ghostSpecies.get(playerUuid);
        // Compare base species name (before |)
        String baseSpecies = species.contains("|") ? species.substring(0, species.indexOf('|')) : species;
        String prevBase = prev != null && prev.contains("|") ? prev.substring(0, prev.indexOf('|')) : prev;
        if (prevBase == null || !prevBase.equalsIgnoreCase(baseSpecies) || !species.equals(prev)) {
            disposeGhost(playerUuid);
        }
    }

    /**
     * Called from the client when an AnimationStateSyncPayload is received from the server.
     * Stores the authoritative animation state for a remote player.
     */
    public static void setRemoteAnimState(UUID playerUuid, int envState, boolean isMoving) {
        remoteAnimStates.put(playerUuid, new int[]{envState, isMoving ? 1 : 0});
    }

    public static void removeTransform(UUID playerUuid) {
        transformedPlayers.remove(playerUuid);
        playerTypes.remove(playerUuid);
        playerTypes2.remove(playerUuid);
        attackAnimTicks.remove(playerUuid);
        sleepingPlayers.remove(playerUuid);
        modelReady.remove(playerUuid);
        pendingCry.remove(playerUuid);
        prevEnvState.remove(playerUuid);
        remoteAnimStates.remove(playerUuid);
        customScales.remove(playerUuid);
        disposeGhost(playerUuid);
    }

    public static boolean isTransformed(UUID playerUuid) {
        return transformedPlayers.containsKey(playerUuid);
    }

    /**
     * Returns true if any transformed player within {@code radius} blocks of (x,y,z) is currently
     * a shiny morph. Used by ShinyChimeMuteMixin to silence the shiny sparkle sound only for
     * CobbleShift ghosts (real wild/captured shiny Pokémon are unaffected because they aren't
     * tracked here).
     */
    public static boolean hasNearbyGhostShiny(double x, double y, double z, double radius) {
        if (transformedPlayers.isEmpty()) return false;
        double r2 = radius * radius;
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        net.minecraft.client.world.ClientWorld world = mc != null ? mc.world : null;
        if (world == null) return false;
        for (Map.Entry<UUID, String> e : transformedPlayers.entrySet()) {
            String encoded = e.getValue();
            if (encoded == null || !encoded.contains("|shiny")) {
                // Fast path: 'shiny' only ever appears as a pipe-delimited token, so a literal
                // contains check on "|shiny" rules out the false positive of "shinypanda" species.
                continue;
            }
            net.minecraft.entity.player.PlayerEntity p = world.getPlayerByUuid(e.getKey());
            if (p == null) continue;
            double dx = p.getX() - x, dy = p.getY() - y, dz = p.getZ() - z;
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }

    public static String getSpecies(UUID playerUuid) {
        String raw = transformedPlayers.get(playerUuid);
        if (raw == null) return null;
        return raw.contains("|") ? raw.substring(0, raw.indexOf('|')) : raw;
    }

    /** Get the full encoded species string (species|shiny|form). */
    public static String getEncodedSpecies(UUID playerUuid) { return transformedPlayers.get(playerUuid); }
    public static String getType1(UUID playerUuid) { return playerTypes.get(playerUuid); }
    public static String getType2(UUID playerUuid) {
        String t = playerTypes2.get(playerUuid);
        return (t != null && !t.isEmpty()) ? t : null;
    }

    public static void setScale(UUID uuid, float scale) { customScales.put(uuid, scale); }
    public static float getScale(UUID uuid) { return customScales.getOrDefault(uuid, 1.0f); }

    public static float getGhostHeight(UUID playerUuid) {
        Entity ghost = ghostEntities.get(playerUuid);
        return ghost != null ? ghost.getHeight() : 1.0f;
    }

    /**
     * Check if the ghost entity has a REAL Cobblemon attack animation ("physical"/"special").
     * Used by the client to tell the server whether to delay the projectile.
     * Returns false for Pokemon that only have the lunge/cry fallback (e.g., Pikachu).
     */
    public static boolean canPlayAttackAnimation(UUID playerUuid) {
        Boolean cached = hasRealAttackAnim.get(playerUuid);
        return cached != null && cached;
    }

    public static void triggerAttackAnimation(UUID playerUuid) {
        attackAnimTicks.put(playerUuid, 10);
        Entity ghost = ghostEntities.get(playerUuid);
        if (ghost instanceof LivingEntity living) {
            living.handSwinging = true;
            living.handSwingTicks = 0;
        }
        // Play cry as attack vocalization
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            triggerCry(playerUuid, client.world);
        }
    }

    public static void toggleSleep(UUID playerUuid) {
        if (sleepingPlayers.contains(playerUuid)) {
            sleepingPlayers.remove(playerUuid);
        } else {
            sleepingPlayers.add(playerUuid);
        }
    }

    public static boolean isSleeping(UUID playerUuid) {
        return sleepingPlayers.contains(playerUuid);
    }

    // ── Zoom ────────────────────────────────────────────────────────────────

    public static void adjustZoom(float delta) {
        zoomFactor = Math.max(0.0f, Math.min(3.0f, zoomFactor + delta));
    }

    public static float getZoomFactor() { return zoomFactor; }

    public static void resetZoom() { zoomFactor = 1.3f; }

    // ── Ghost entity management ─────────────────────────────────────────────

    public static Entity getGhostEntity(UUID playerUuid, ClientWorld world) {
        Entity existing = ghostEntities.get(playerUuid);
        if (existing != null) return existing;

        String encodedSpecies = transformedPlayers.get(playerUuid);
        if (encodedSpecies == null || encodedSpecies.isEmpty() || world == null) return null;

        // Parse encoded species: "species" or "species|shiny" or "species|shiny|form" or "species|form"
        String species = encodedSpecies;
        boolean shiny = false;
        String form = null;
        if (encodedSpecies.contains("|")) {
            String[] parts = encodedSpecies.split("\\|");
            species = parts[0];
            for (int i = 1; i < parts.length; i++) {
                if ("shiny".equalsIgnoreCase(parts[i])) shiny = true;
                else if (form == null) form = parts[i];
            }
        }

        Entity ghost = createGhostViaReflection(species, world, shiny, form);
        if (ghost != null) {
            ghostSpecies.put(playerUuid, encodedSpecies);

            // Initialize delegate for animation
            initDelegateReflection(ghost);
            if (getDelegateMethod != null) {
                try {
                    Object delegate = getDelegateMethod.invoke(ghost);
                    if (delegate != null) {
                        if (delegateInitMethod != null) {
                            delegateInitMethod.invoke(delegate, ghost);
                        }
                        ghostDelegates.put(playerUuid, delegate);
                    }
                } catch (Exception e) {
                    LOGGER.debug("[CobbleShift] Delegate init failed: {}", e.getMessage());
                }
            }

            // Init cry reflection
            initPokemonCryReflection(ghost);

            ghostEntities.put(playerUuid, ghost);
            LOGGER.info("[CobbleShift] Ghost ready: '{}' height={} width={}", species, ghost.getHeight(), ghost.getWidth());
        }
        return ghost;
    }

    // ── Sync + Animation ────────────────────────────────────────────────────

    public static void syncGhostToPlayer(Entity ghost, PlayerEntity player, float tickDelta) {
        if (ghost == null || player == null) return;

        UUID uuid = player.getUuid();

        // Sync position
        ghost.setPosition(player.getX(), player.getY(), player.getZ());
        ghost.prevX = player.prevX;
        ghost.prevY = player.prevY;
        ghost.prevZ = player.prevZ;

        // Sync rotation
        ghost.setYaw(player.getYaw());
        ghost.prevYaw = player.prevYaw;
        ghost.setPitch(player.getPitch());
        ghost.prevPitch = player.prevPitch;

        // Sync velocity
        ghost.setVelocity(player.getVelocity());

        // Sync ground state (water + swimming synced AFTER delegate.tick below in syncWaterState)
        ghost.setOnGround(player.isOnGround());

        if (ghost instanceof LivingEntity livingGhost && player instanceof LivingEntity livingPlayer) {
            livingGhost.bodyYaw = livingPlayer.bodyYaw;
            livingGhost.prevBodyYaw = livingPlayer.prevBodyYaw;
            livingGhost.headYaw = livingPlayer.headYaw;
            livingGhost.prevHeadYaw = livingPlayer.prevHeadYaw;
            livingGhost.limbAnimator.setSpeed(livingPlayer.limbAnimator.getSpeed());
        }

        // Attack animation: try Cobblemon's bedrock animation system first,
        // fall back to head-tilt lunge if no attack animation found
        Integer animTicks = attackAnimTicks.get(uuid);
        if (animTicks != null && animTicks > 0) {
            // On the first tick of attack animation, try to play a real Cobblemon animation
            if (animTicks == 10) {
                initAttackAnimReflection();
                if (tryPlayAttackAnimation(uuid)) {
                    attackAnimSuccess.add(uuid);
                } else {
                    attackAnimSuccess.remove(uuid);
                }
            }
            // Only apply lunge as fallback if real animation didn't play
            if (!attackAnimSuccess.contains(uuid)) {
                float lungeFactor = (float) animTicks / 10.0f;
                ghost.setPitch(ghost.getPitch() + 25.0f * lungeFactor);
            }
            if (ghost instanceof LivingEntity lg) { lg.handSwinging = true; }
            attackAnimTicks.put(uuid, animTicks - 1);
            if (animTicks - 1 <= 0) {
                attackAnimSuccess.remove(uuid);
            }
        }

        // Determine movement
        Vec3d vel = player.getVelocity();
        double horizontalSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        boolean isMoving = horizontalSpeed > 0.01;

        // Tick delegate (once per game tick) — let it update animation internals
        int currentAge = player.age;
        Integer prevAge = lastTickedAge.get(uuid);
        if (prevAge == null || currentAge != prevAge) {
            ghost.age = currentAge;
            lastTickedAge.put(uuid, currentAge);

            Object delegate = ghostDelegates.get(uuid);
            if (delegate != null && delegateTickMethod != null) {
                try {
                    delegateTickMethod.invoke(delegate, ghost);
                } catch (Exception ignored) {}
            }
        }

        // Sync water state AFTER delegate.tick() — the delegate resets water fields,
        // so we must override them after it runs. This is critical for Gyarados swim animation.
        syncWaterState(ghost, player);

        // NOW set POSE_TYPE + MOVING — AFTER delegate.tick() and water sync
        syncCobblemonAnimationState(ghost, player, isMoving);

        // Update partial ticks every frame for smooth animation interpolation
        Object delegate = ghostDelegates.get(uuid);
        if (delegate != null && delegatePartialTicksMethod != null) {
            try {
                delegatePartialTicksMethod.invoke(delegate, tickDelta);
            } catch (Exception ignored) {}
        }

        // Check if model became ready (set during Cobblemon's PokemonRenderer)
        if (!modelReady.contains(uuid) && delegate != null && delegateGetCurrentModelMethod != null) {
            try {
                Object model = delegateGetCurrentModelMethod.invoke(delegate);
                if (model != null) {
                    modelReady.add(uuid);
                    LOGGER.info("[CobbleShift] Model ready for '{}'", ghostSpecies.get(uuid));

                    // Probe: check if this species has real attack animations
                    probeAttackAnimations(uuid, delegate, model);

                    if (pendingCry.remove(uuid)) {
                        triggerCryInternal(uuid);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Sync ghost entity to a generic Entity (NPC, mob, etc.).
     * Simplified version of syncGhostToPlayer — no attack animations, no sleep, no camera.
     */
    public static void syncGhostToEntity(Entity ghost, Entity source, float tickDelta) {
        if (ghost == null || source == null) return;
        UUID uuid = source.getUuid();

        // Sync position
        ghost.setPosition(source.getX(), source.getY(), source.getZ());
        ghost.prevX = source.prevX;
        ghost.prevY = source.prevY;
        ghost.prevZ = source.prevZ;

        // Sync rotation
        ghost.setYaw(source.getYaw());
        ghost.prevYaw = source.prevYaw;
        ghost.setPitch(source.getPitch());
        ghost.prevPitch = source.prevPitch;

        // Sync velocity + ground
        ghost.setVelocity(source.getVelocity());
        ghost.setOnGround(source.isOnGround());

        if (ghost instanceof LivingEntity livingGhost && source instanceof LivingEntity livingSource) {
            livingGhost.bodyYaw = livingSource.bodyYaw;
            livingGhost.prevBodyYaw = livingSource.prevBodyYaw;
            livingGhost.headYaw = livingSource.headYaw;
            livingGhost.prevHeadYaw = livingSource.prevHeadYaw;
            livingGhost.limbAnimator.setSpeed(livingSource.limbAnimator.getSpeed());
        }

        // Movement detection
        Vec3d vel = source.getVelocity();
        double horizontalSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        boolean isMoving = horizontalSpeed > 0.01;

        // Tick delegate (once per game tick)
        int currentAge = source.age;
        Integer prevAge = lastTickedAge.get(uuid);
        if (prevAge == null || currentAge != prevAge) {
            ghost.age = currentAge;
            lastTickedAge.put(uuid, currentAge);
            Object delegate = ghostDelegates.get(uuid);
            if (delegate != null && delegateTickMethod != null) {
                try { delegateTickMethod.invoke(delegate, ghost); } catch (Exception ignored) {}
            }
        }

        // Water state + animation state
        syncWaterState(ghost, source);
        syncCobblemonAnimationState(ghost, source, isMoving);

        // Partial ticks for smooth animation
        Object delegate = ghostDelegates.get(uuid);
        if (delegate != null && delegatePartialTicksMethod != null) {
            try { delegatePartialTicksMethod.invoke(delegate, tickDelta); } catch (Exception ignored) {}
        }

        // Model ready check
        if (!modelReady.contains(uuid) && delegate != null && delegateGetCurrentModelMethod != null) {
            try {
                Object model = delegateGetCurrentModelMethod.invoke(delegate);
                if (model != null) {
                    modelReady.add(uuid);
                    if (pendingCry.remove(uuid)) {
                        triggerCryInternal(uuid);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Sync water state from player to ghost entity.
     * Sets touchingWater, submergedInWater, and submergedFluidTag.
     * All are needed for Cobblemon's MoLang conditions (is_touching_water, is_underwater).
     *
     * For water-type Pokémon in the air, we FORCE water state on the ghost so Pokémon
     * like Gyarados (whose swim animation requires isUnderWater=true MoLang) can animate.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void syncWaterState(Entity ghost, Entity player) {
        if (!waterFieldAttempted) {
            waterFieldAttempted = true;
            // Find touchingWater (boolean)
            // Yarn: touchingWater, Intermediary: field_5957
            for (String name : new String[]{"touchingWater", "field_5957", "f_19811_", "inWater"}) {
                try {
                    touchingWaterField = Entity.class.getDeclaredField(name);
                    touchingWaterField.setAccessible(true);
                    break;
                } catch (Exception ignored) {}
            }
            // Find submergedInWater (boolean)
            // Yarn: submergedInWater, Intermediary: field_6000
            for (String name : new String[]{"submergedInWater", "field_6000"}) {
                try {
                    Field f = Entity.class.getDeclaredField(name);
                    if (f.getType() == boolean.class) {
                        f.setAccessible(true);
                        submergedInWaterField = f;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            // Find submergedFluidTag (Set<TagKey<Fluid>>)
            // Yarn: submergedFluidTag, Intermediary: field_25599
            for (String name : new String[]{"submergedFluidTag", "field_25599"}) {
                try {
                    Field f = Entity.class.getDeclaredField(name);
                    if (Set.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        submergedFluidTagField = f;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            // If named fields failed, scan Entity fields by TYPE ORDER as last resort.
            // At runtime with intermediary names (field_XXXX), we cannot match by name.
            // Entity declares exactly 2 protected boolean fields related to water state,
            // and they appear in declaration order: touchingWater, then submergedInWater.
            if (touchingWaterField == null || submergedInWaterField == null) {
                // Collect ALL boolean fields in declaration order
                java.util.List<Field> booleanFields = new java.util.ArrayList<>();
                for (Field f : Entity.class.getDeclaredFields()) {
                    if (f.getType() == boolean.class
                        && java.lang.reflect.Modifier.isProtected(f.getModifiers())
                        && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        booleanFields.add(f);
                    }
                }
                // In MC 1.21.1 Entity, protected booleans in order are:
                //   touchingWater (field_5957), submergedInWater (field_6000)
                // We verify by reading the player's KNOWN water state
                for (Field f : booleanFields) {
                    try {
                        f.setAccessible(true);
                        boolean val = f.getBoolean(player);
                        // If we haven't found touchingWater yet and player IS touching water,
                        // or this is early in the list, try matching by behavior
                        if (touchingWaterField == null) {
                            touchingWaterField = f;
                        } else if (submergedInWaterField == null) {
                            submergedInWaterField = f;
                            break; // found both
                        }
                    } catch (Exception ignored) {}
                }
            }
            // Similarly for submergedFluidTag (Set) if not found
            if (submergedFluidTagField == null) {
                for (Field f : Entity.class.getDeclaredFields()) {
                    if (Set.class.isAssignableFrom(f.getType())
                        && java.lang.reflect.Modifier.isPrivate(f.getModifiers())
                        && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        // In MC 1.21.1 Entity, the only private non-static Set field is submergedFluidTag
                        f.setAccessible(true);
                        submergedFluidTagField = f;
                        break;
                    }
                }
            }
            LOGGER.info("[CobbleShift] Water sync: touchingWater={} ({}), submergedInWater={} ({}), submergedFluidTag={} ({})",
                touchingWaterField != null, touchingWaterField != null ? touchingWaterField.getName() : "null",
                submergedInWaterField != null, submergedInWaterField != null ? submergedInWaterField.getName() : "null",
                submergedFluidTagField != null, submergedFluidTagField != null ? submergedFluidTagField.getName() : "null");
        }

        UUID uuid = player.getUuid();
        String type1 = playerTypes.getOrDefault(uuid, "normal");
        String type2Val = playerTypes2.get(uuid);
        boolean isWaterType = "water".equalsIgnoreCase(type1) || "water".equalsIgnoreCase(type2Val);

        // For remote players, use server-provided envState to determine water state
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean isLocalPlayer = mc.player != null && mc.player.getUuid().equals(uuid);
        int[] remoteState = isLocalPlayer ? null : remoteAnimStates.get(uuid);

        boolean playerTouchingWater, playerSubmerged, playerOnGround;
        if (remoteState != null) {
            int env = remoteState[0];
            playerTouchingWater = (env == 1 || env == 2);
            playerSubmerged = (env == 2);
            playerOnGround = (env == 0);
        } else {
            playerTouchingWater = player.isTouchingWater();
            playerSubmerged = player.isSubmergedInWater();
            playerOnGround = player.isOnGround();
        }

        // Force water state for water-type Pokémon when NOT on dry ground.
        // "Dry ground" = on ground AND not touching water.
        // In air: force water → Gyarados swims/flies with swim animation
        // In water (surface or submerged): force submerged → Gyarados's "swim" pose needs isUnderWater=true
        // On dry ground: no force → Gyarados walks normally with walk animation
        boolean onDryGround = playerOnGround && !playerTouchingWater;
        boolean forceWater = isWaterType && !onDryGround;

        boolean targetTouchingWater = playerTouchingWater || forceWater;
        boolean targetSubmerged = playerSubmerged || forceWater;

        // Also set the entity swimming flag (distinct from water state)
        ghost.setSwimming((remoteState != null ? playerSubmerged : player.isSwimming()) || forceWater);

        // Set touchingWater
        if (touchingWaterField != null) {
            try { touchingWaterField.setBoolean(ghost, targetTouchingWater); } catch (Exception ignored) {}
        }

        // Set submergedInWater (boolean field)
        if (submergedInWaterField != null) {
            try { submergedInWaterField.setBoolean(ghost, targetSubmerged); } catch (Exception ignored) {}
        }

        // Set submergedFluidTag (Set<TagKey<Fluid>>)
        // Use FluidTags.WATER directly (not Class.forName which bypasses Loom remapping)
        if (submergedFluidTagField != null) {
            try {
                Set ghostTags = (Set) submergedFluidTagField.get(ghost);
                if (ghostTags != null) {
                    ghostTags.clear();
                    if (targetSubmerged) {
                        ghostTags.add(FluidTags.WATER);
                    } else {
                        Set playerTags = (Set) submergedFluidTagField.get(player);
                        if (playerTags != null && !playerTags.isEmpty()) {
                            ghostTags.addAll(playerTags);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Manually set POSE_TYPE and MOVING tracked data on the ghost entity.
     *
     * When the environment state changes (ground↔water↔air↔sleep), we force a
     * POSE_TYPE transition by setting NONE first, then the target. This ensures
     * Cobblemon's delegate re-resolves the pose with updated MoLang conditions.
     */
    @SuppressWarnings("unchecked")
    private static void syncCobblemonAnimationState(Entity ghost, Entity player, boolean isMoving) {
        initAnimReflection(ghost);
        if (cobblemonMovingKey == null || cobblemonPoseTypeKey == null) return;

        UUID uuid = player.getUuid();

        try {
            // For remote players, use server-provided animation state if available
            // (server computes isMoving/envState with authoritative data)
            MinecraftClient mc = MinecraftClient.getInstance();
            boolean isLocalPlayer = mc.player != null && mc.player.getUuid().equals(uuid);
            int[] remoteState = isLocalPlayer ? null : remoteAnimStates.get(uuid);

            int envState;
            if (remoteState != null) {
                // Use server-provided state for remote players
                envState = remoteState[0];
                isMoving = remoteState[1] == 1;
            } else {
                // Compute locally (for local player or if no server data yet)
                if (sleepingPlayers.contains(uuid)) {
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
            }

            // Always set MOVING
            ghost.getDataTracker().set(cobblemonMovingKey, isMoving);

            // Detect state transition
            Integer prevState = prevEnvState.put(uuid, envState);
            boolean stateChanged = (prevState != null && prevState != envState);

            // Determine Pokemon typing for water/air pose selection
            String type1 = playerTypes.getOrDefault(uuid, "normal");
            String type2Val = playerTypes2.get(uuid);
            boolean isWaterType = "water".equalsIgnoreCase(type1) || "water".equalsIgnoreCase(type2Val);
            boolean isFlyingType = "flying".equalsIgnoreCase(type1) || "flying".equalsIgnoreCase(type2Val);

            // Determine correct PoseType based on environment + typing
            Object targetPose;
            switch (envState) {
                case 8 -> // Sleep
                    targetPose = poseTypeSleep;
                case 2 -> { // Submerged underwater
                    if (isMoving) {
                        // Water types prefer SWIM; flying-only types prefer FLY; others try SWIM
                        if (isWaterType) targetPose = poseTypeSwim;
                        else if (isFlyingType) targetPose = poseTypeFly;
                        else targetPose = poseTypeSwim;
                    } else {
                        // Idle underwater
                        if (isWaterType) targetPose = poseTypeFloat;
                        else if (isFlyingType) targetPose = poseTypeHover;
                        else targetPose = poseTypeFloat;
                    }
                }
                case 1 -> { // Surface water (feet in water, not submerged)
                    if (isMoving) {
                        if (isWaterType) targetPose = poseTypeSwim;
                        else if (isFlyingType) targetPose = poseTypeFly;
                        else targetPose = poseTypeSwim;
                    } else {
                        if (isWaterType) targetPose = poseTypeFloat;
                        else if (isFlyingType) targetPose = poseTypeHover;
                        else targetPose = poseTypeFloat;
                    }
                }
                case 3 -> { // In air
                    // Water types (including Water/Flying like Gyarados) → always SWIM in air.
                    // Their swim animation is their primary movement animation.
                    // Non-water flying types → FLY/HOVER as normal.
                    if (isWaterType) {
                        targetPose = isMoving ? poseTypeSwim : poseTypeFloat;
                    } else {
                        targetPose = isMoving ? poseTypeFly : poseTypeHover;
                    }
                }
                default -> // Ground (dry ground — water types walk normally here)
                    targetPose = isMoving ? poseTypeWalk : poseTypeStand;
            }

            if (targetPose == null) targetPose = poseTypeStand;
            if (targetPose == null) return;

            // If state changed, force a transition by setting NONE first
            // This ensures the delegate re-resolves even if the PoseType enum is the same
            if (stateChanged && poseTypeNone != null) {
                ghost.getDataTracker().set(cobblemonPoseTypeKey, poseTypeNone);
                notifyTrackedDataSet(ghost, cobblemonPoseTypeKey);
            }

            ghost.getDataTracker().set(cobblemonPoseTypeKey, targetPose);
            notifyTrackedDataSet(ghost, cobblemonPoseTypeKey);
            notifyTrackedDataSet(ghost, cobblemonMovingKey);
        } catch (Exception e) {
            // Don't spam
        }
    }

    @SuppressWarnings("rawtypes")
    private static void notifyTrackedDataSet(Entity ghost, TrackedData key) {
        if (!trackedDataSetReflectionAttempted) {
            trackedDataSetReflectionAttempted = true;
            for (String name : new String[]{"onTrackedDataSet", "method_5674", "m_7350_"}) {
                try {
                    onTrackedDataSetMethod = Entity.class.getDeclaredMethod(name, TrackedData.class);
                    onTrackedDataSetMethod.setAccessible(true);
                    break;
                } catch (Exception ignored) {}
            }
            if (onTrackedDataSetMethod == null) {
                for (Method m : Entity.class.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == TrackedData.class
                        && m.getReturnType() == void.class) {
                        m.setAccessible(true);
                        onTrackedDataSetMethod = m;
                        break;
                    }
                }
            }
        }
        if (onTrackedDataSetMethod != null) {
            try { onTrackedDataSetMethod.invoke(ghost, key); } catch (Exception ignored) {}
        }
    }

    // ── Delegate reflection ─────────────────────────────────────────────────

    private static void initDelegateReflection(Entity ghost) {
        if (delegateReflectionAttempted) return;
        delegateReflectionAttempted = true;

        try {
            Class<?> peClass = Class.forName("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
            if (!peClass.isInstance(ghost)) return;

            getDelegateMethod = peClass.getMethod("getDelegate");
            Object delegate = getDelegateMethod.invoke(ghost);
            if (delegate == null) return;

            for (Method m : delegate.getClass().getMethods()) {
                String name = m.getName();
                int pc = m.getParameterCount();
                if (name.equals("initialize") && pc == 1 && delegateInitMethod == null) delegateInitMethod = m;
                else if (name.equals("tick") && pc == 1 && delegateTickMethod == null) delegateTickMethod = m;
                else if (name.equals("updatePartialTicks") && pc == 1 && delegatePartialTicksMethod == null) delegatePartialTicksMethod = m;
                else if (name.equals("cry") && pc == 0 && delegateCryMethod == null) delegateCryMethod = m;
                else if (name.equals("getCurrentModel") && pc == 0 && delegateGetCurrentModelMethod == null) delegateGetCurrentModelMethod = m;
            }

            LOGGER.info("[CobbleShift] Delegate reflection: init={}, tick={}, partial={}, cry={}, model={}",
                delegateInitMethod != null, delegateTickMethod != null,
                delegatePartialTicksMethod != null, delegateCryMethod != null,
                delegateGetCurrentModelMethod != null);
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Delegate reflection failed: {}", e.getMessage());
        }
    }

    private static void initPokemonCryReflection(Entity ghost) {
        if (pokemonCryReflectionAttempted) return;
        pokemonCryReflectionAttempted = true;

        try {
            Class<?> peClass = Class.forName("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
            if (!peClass.isInstance(ghost)) return;
            pokemonEntityCryMethod = peClass.getMethod("cry");
            LOGGER.info("[CobbleShift] PokemonEntity.cry() found");
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] PokemonEntity.cry() not found: {}", e.getMessage());
        }
    }

    // ── Attack animation reflection (Cobblemon's bedrock animation system) ──

    private static void initAttackAnimReflection() {
        if (attackAnimReflectionAttempted) return;
        attackAnimReflectionAttempted = true;

        try {
            // PosableState.addFirstAnimation(Set<String>) — the canonical entry point for playing
            // named animations. It resolves animation names through the pose's named animations,
            // the model's animation map, and extractAnimation as fallback.
            Class<?> posableStateClass = Class.forName("com.cobblemon.mod.common.client.render.models.blockbench.PosableState");
            for (Method m : posableStateClass.getMethods()) {
                if (m.getName().equals("addFirstAnimation") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Set.class) {
                    addFirstAnimationMethod = m;
                    break;
                }
            }

            LOGGER.info("[CobbleShift] Attack anim reflection: addFirstAnimation={}",
                addFirstAnimationMethod != null);
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Attack anim reflection failed: {}", e.getMessage());
        }
    }

    /**
     * Try to play a REAL Cobblemon attack animation ("physical"/"special") on the ghost entity.
     * Does NOT include "cry" as fallback — cry is handled separately as sound/visual effect.
     *
     * Returns true only if a real attack animation was found and started playing.
     * addFirstAnimation returns non-null if an animation resolved, null if none matched.
     */
    private static boolean tryPlayAttackAnimation(UUID playerUuid) {
        if (addFirstAnimationMethod == null) return false;
        if (!modelReady.contains(playerUuid)) return false;

        Object delegate = ghostDelegates.get(playerUuid);
        if (delegate == null) return false;

        String species = getSpecies(playerUuid);
        if (species == null) return false;
        String speciesLower = species.toLowerCase(Locale.ROOT);

        try {
            // Only try real attack animation names — NO cry fallback
            Set<String> animNames = new LinkedHashSet<>();
            animNames.add("physical");
            animNames.add("special");
            animNames.add(speciesLower + ":animation." + speciesLower + ".physical");
            animNames.add(speciesLower + ":animation." + speciesLower + ".special");

            Object result = addFirstAnimationMethod.invoke(delegate, animNames);
            boolean found = (result != null);
            LOGGER.debug("[CobbleShift] addFirstAnimation for '{}': found={}", species, found);
            return found;
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] addFirstAnimation failed for '{}': {}", species, e.getMessage());
            return false;
        }
    }

    /**
     * Probe the model's available animations to check if this species has
     * REAL attack animations ("physical" or "special").
     * Called once when model becomes ready. Result cached in hasRealAttackAnim.
     */
    @SuppressWarnings("unchecked")
    private static void probeAttackAnimations(UUID playerUuid, Object delegate, Object model) {
        String species = getSpecies(playerUuid);
        if (species == null) return;
        String speciesLower = species.toLowerCase(Locale.ROOT);

        boolean found = false;

        // Approach 1: Check the model's animations map directly
        try {
            for (Method m : model.getClass().getMethods()) {
                if (m.getName().equals("getAnimations") && m.getParameterCount() == 0
                    && Map.class.isAssignableFrom(m.getReturnType())) {
                    Map<String, ?> animations = (Map<String, ?>) m.invoke(model);
                    if (animations != null) {
                        // Check short names
                        found = animations.containsKey("physical") || animations.containsKey("special");
                        if (!found) {
                            // Check full animation names (species prefix)
                            found = animations.containsKey("animation." + speciesLower + ".physical")
                                 || animations.containsKey("animation." + speciesLower + ".special");
                        }
                        if (!found) {
                            // Scan all keys for partial match
                            for (String key : animations.keySet()) {
                                if (key.contains("physical") || key.contains("special")) {
                                    found = true;
                                    break;
                                }
                            }
                        }
                        LOGGER.info("[CobbleShift] Model animations for '{}': {} keys, physical/special={}",
                            species, animations.size(), found);
                    }
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Model animations probe failed: {}", e.getMessage());
        }

        // Approach 2: Check current pose's named animations
        if (!found) {
            try {
                for (Method m : delegate.getClass().getMethods()) {
                    if (m.getName().equals("getCurrentPose") && m.getParameterCount() == 0) {
                        Object pose = m.invoke(delegate);
                        if (pose != null) {
                            for (Method pm : pose.getClass().getMethods()) {
                                if (pm.getName().equals("getNamedAnimations") && pm.getParameterCount() == 0) {
                                    Map<String, ?> namedAnims = (Map<String, ?>) pm.invoke(pose);
                                    if (namedAnims != null) {
                                        found = namedAnims.containsKey("physical")
                                             || namedAnims.containsKey("special");
                                    }
                                    break;
                                }
                            }
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }

        hasRealAttackAnim.put(playerUuid, found);
        LOGGER.info("[CobbleShift] Attack anim probe for '{}': hasRealAttackAnim={}", species, found);
    }

    // ── Animation TrackedData reflection ─────────────────────────────────────

    private static void initAnimReflection(Entity ghost) {
        if (animReflectionAttempted) return;
        animReflectionAttempted = true;

        try {
            Class<?> peClass = Class.forName("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
            if (!peClass.isInstance(ghost)) return;

            cobblemonMovingKey = getTrackedDataKey(peClass, "getMOVING");
            cobblemonPoseTypeKey = getTrackedDataKey(peClass, "getPOSE_TYPE");
            cobblemonBeamModeKey = getTrackedDataKey(peClass, "getBEAM_MODE");

            try {
                Class<?> ptClass = Class.forName("com.cobblemon.mod.common.entity.PoseType");
                poseTypeWalk = safeEnum(ptClass, "WALK");
                poseTypeStand = safeEnum(ptClass, "STAND");
                poseTypeFly = safeEnum(ptClass, "FLY");
                poseTypeHover = safeEnum(ptClass, "HOVER");
                poseTypeSwim = safeEnum(ptClass, "SWIM");
                poseTypeSleep = safeEnum(ptClass, "SLEEP");
                poseTypeFloat = safeEnum(ptClass, "FLOAT");
                poseTypeNone = safeEnum(ptClass, "NONE");
            } catch (Exception ignored) {}

            LOGGER.info("[CobbleShift] Anim reflection OK: MOVING={}, POSE_TYPE={}, BEAM_MODE={}",
                cobblemonMovingKey != null, cobblemonPoseTypeKey != null, cobblemonBeamModeKey != null);
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Anim reflection failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("rawtypes")
    private static TrackedData getTrackedDataKey(Class<?> peClass, String getterName) {
        try { return (TrackedData) peClass.getMethod(getterName).invoke(null); }
        catch (Exception ignored) {}
        try {
            Object companion = peClass.getField("Companion").get(null);
            for (Method m : companion.getClass().getMethods()) {
                if (m.getName().equals(getterName) && m.getParameterCount() == 0) {
                    return (TrackedData) m.invoke(companion);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Object safeEnum(Class<?> enumClass, String name) {
        try { return Enum.valueOf(enumClass.asSubclass(Enum.class), name); }
        catch (Exception e) { return null; }
    }

    // ── Cry ─────────────────────────────────────────────────────────────────

    /** Max distance (blocks) at which a remote transform cry is audible to the local client. Mirrors the server-side filter. */
    private static final int CRY_AUDIBLE_RADIUS = 32;
    private static final double CRY_AUDIBLE_RADIUS_SQ = (double) CRY_AUDIBLE_RADIUS * CRY_AUDIBLE_RADIUS;

    /**
     * Play the Pokemon cry. Always plays sound directly via SoundEvent.of() (most reliable),
     * then also tries PokemonEntity.cry() for the visual animation.
     * Defensively gated by {@link #isCryAudibleHere} to avoid late/cross-dimension packets.
     */
    public static void triggerCry(UUID playerUuid, ClientWorld world) {
        // Defensive check (server already filters, this catches edge cases like late packets after dim change)
        if (!isCryAudibleHere(playerUuid, world)) {
            return;
        }

        // Always play sound directly — this is the most reliable approach
        String species = getSpecies(playerUuid); // base name without |shiny|form
        if (species != null) {
            playCrySoundDirect(species);
        }

        // Also try entity cry for the cry animation (if model ready)
        if (modelReady.contains(playerUuid)) {
            triggerCryInternal(playerUuid);
        } else {
            pendingCry.add(playerUuid);
        }
    }

    /**
     * Returns true when a transform cry for {@code targetUuid} should be audible to the local player.
     * Local player crying for himself (keybind, attack vocalize) always passes (distance = 0).
     */
    private static boolean isCryAudibleHere(UUID targetUuid, ClientWorld world) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || world == null) return false;
        if (client.player.getUuid().equals(targetUuid)) return true;

        Entity target = world.getPlayerByUuid(targetUuid);
        if (target == null) {
            for (Entity e : world.getEntities()) {
                if (e.getUuid().equals(targetUuid)) { target = e; break; }
            }
        }
        if (target == null) return false; // entity not loaded in this world — different dim or out of range

        double dx = target.getX() - client.player.getX();
        double dy = target.getY() - client.player.getY();
        double dz = target.getZ() - client.player.getZ();
        return (dx * dx + dy * dy + dz * dz) <= CRY_AUDIBLE_RADIUS_SQ;
    }

    /**
     * Try to invoke cry on the ghost entity for animation purposes.
     */
    private static void triggerCryInternal(UUID playerUuid) {
        Entity ghost = ghostEntities.get(playerUuid);
        if (ghost == null) return;

        // Try PokemonEntity.cry()
        if (pokemonEntityCryMethod != null) {
            try {
                pokemonEntityCryMethod.invoke(ghost);
                return;
            } catch (Exception ignored) {}
        }

        // Try delegate.cry()
        Object delegate = ghostDelegates.get(playerUuid);
        if (delegate != null && delegateCryMethod != null) {
            try {
                delegateCryMethod.invoke(delegate);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Play cry sound directly by creating a SoundEvent from Cobblemon's expected ID.
     */
    private static void playCrySoundDirect(String species) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        String speciesLower = species.toLowerCase(Locale.ROOT);
        String[] formats = {
            "pokemon." + speciesLower + ".cry",
            "pokemon." + speciesLower + ".ambient",
            speciesLower + ".cry",
            "cry." + speciesLower
        };

        for (String fmt : formats) {
            try {
                Identifier soundId = Identifier.of("cobblemon", fmt);
                SoundEvent sound = SoundEvent.of(soundId);
                client.getSoundManager().play(PositionedSoundInstance.master(sound, 1.0f));
                LOGGER.debug("[CobbleShift] Played cry: {}", soundId);
                return;
            } catch (Exception ignored) {}
        }
        LOGGER.debug("[CobbleShift] No cry sound found for '{}'", species);
    }

    // ── VFX ─────────────────────────────────────────────────────────────────

    public static void spawnTransformParticles(Entity target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || target == null) return;

        for (int i = 0; i < 40; i++) {
            double x = target.getX() + (Math.random() - 0.5) * 2.0;
            double y = target.getY() + Math.random() * 2.0;
            double z = target.getZ() + (Math.random() - 0.5) * 2.0;
            client.world.addParticle(ParticleTypes.END_ROD, x, y, z,
                (Math.random() - 0.5) * 0.3, Math.random() * 0.3, (Math.random() - 0.5) * 0.3);
        }
        for (int i = 0; i < 15; i++) {
            double x = target.getX() + (Math.random() - 0.5) * 1.5;
            double y = target.getY() + 0.5 + Math.random() * 1.5;
            double z = target.getZ() + (Math.random() - 0.5) * 1.5;
            client.world.addParticle(ParticleTypes.HAPPY_VILLAGER, x, y, z, 0, 0.1, 0);
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    public static void disposeGhost(UUID playerUuid) {
        ghostEntities.remove(playerUuid);
        ghostSpecies.remove(playerUuid);
        ghostDelegates.remove(playerUuid);
        lastTickedAge.remove(playerUuid);
        modelReady.remove(playerUuid);
        pendingCry.remove(playerUuid);
        prevEnvState.remove(playerUuid);
        attackAnimSuccess.remove(playerUuid);
        hasRealAttackAnim.remove(playerUuid);
    }

    public static void clear() {
        transformedPlayers.clear();
        playerTypes.clear();
        playerTypes2.clear();
        ghostEntities.clear();
        ghostSpecies.clear();
        attackAnimTicks.clear();
        ghostDelegates.clear();
        lastTickedAge.clear();
        sleepingPlayers.clear();
        modelReady.clear();
        pendingCry.clear();
        prevEnvState.clear();
        attackAnimSuccess.clear();
        hasRealAttackAnim.clear();
        zoomFactor = 1.3f;
    }

    // ── Ghost creation via reflection ───────────────────────────────────────

    private static void initReflection() {
        if (reflectionAttempted) return;
        reflectionAttempted = true;
        try {
            Class<?> registryClass = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
            registryInstance = registryClass.getField("INSTANCE").get(null);
            getByNameMethod = registryInstance.getClass().getMethod("getByName", String.class);
            cobblemonAvailable = true;
            LOGGER.info("[CobbleShift] Client: Cobblemon reflection OK");
        } catch (Exception e) {
            LOGGER.info("[CobbleShift] Client: Cobblemon not available");
        }
    }

    private static Entity createGhostViaReflection(String speciesName, ClientWorld world, boolean shiny, String form) {
        initReflection();
        if (!cobblemonAvailable) return null;

        try {
            // Try multiple name formats (handle hyphens)
            String baseName = speciesName.toLowerCase(Locale.ROOT);
            Object species = getByNameMethod.invoke(registryInstance, baseName);
            if (species == null) species = getByNameMethod.invoke(registryInstance, baseName.replace("-", ""));
            if (species == null) species = getByNameMethod.invoke(registryInstance, baseName.replace("-", "_"));
            if (species == null) return null;

            Class<?> pokemonClass = Class.forName("com.cobblemon.mod.common.pokemon.Pokemon");
            Object pokemon = pokemonClass.getConstructor().newInstance();

            boolean speciesSet = false;
            for (Method m : pokemonClass.getMethods()) {
                if (m.getName().equals("setSpecies") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(species)) {
                    m.invoke(pokemon, species);
                    speciesSet = true;
                    break;
                }
            }
            if (!speciesSet) return null;

            // Apply shiny/form on the Pokemon object BEFORE creating the entity.
            // This way the PokemonEntity is constructed with the correct visual state from the start,
            // and Cobblemon's renderer resolves the correct model/texture during initialization.
            if (shiny || (form != null && !form.isEmpty())) {
                applyVisualPropertiesToPokemon(pokemon, pokemonClass, species, speciesName, shiny, form);
            }

            Object entityType = findCobblemonEntityType();
            if (entityType == null) return null;

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

            if (entity == null) return null;

            // Configure as non-interactable ghost
            // NOTE: do NOT set setSilent(true) — it blocks cry() sound!
            entity.setInvulnerable(true);
            entity.noClip = true;

            // === DataTracker sync for shiny/form ===
            // The entity constructor reads pokemon.aspects into its DataTracker.
            // But on client, aspects might not have been fully computed at construction time.
            // Force-sync the ASPECTS TrackedData from the Pokemon's current aspects.
            if (shiny || (form != null && !form.isEmpty())) {
                syncEntityDataTrackerAspects(entity, pokemon, pokemonClass, speciesName);
            }

            return entity;

        } catch (ClassNotFoundException e) {
            cobblemonAvailable = false;
            return null;
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Ghost creation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Apply shiny + form/aspects to a Pokemon object BEFORE creating the PokemonEntity.
     * This ensures the entity is initialized with the correct visual state, and
     * Cobblemon's renderer picks up the right model/texture from the start.
     *
     * The form parameter can be:
     * - A simple form name: "alolan", "galarian", "mega"
     * - A form aspect string: "mega_x", "mega_y", "gmax"
     * - A species feature key=value: "magikarp_jump=black-mask" → converted to "magikarp-jump-black-mask"
     * - A direct aspect string: "magikarp-jump-black-mask"
     * - Multiple space-separated tokens: "mega_x shiny"
     *
     * Critical: On client-side, updateAspects() only uses forcedAspects (not AspectProviders).
     * So we MUST add shiny/form aspects to forcedAspects explicitly, then call updateAspects().
     */
    @SuppressWarnings("unchecked")
    private static void applyVisualPropertiesToPokemon(Object pokemon, Class<?> pokemonClass,
                                                        Object speciesObj, String speciesName,
                                                        boolean shiny, String form) {
        // Split form into individual aspect tokens (supports multi-token forms)
        String[] formTokens = (form != null && !form.isEmpty()) ? form.split("\\s+") : new String[0];

        // === Step 1: PokemonProperties.parse().apply() for species + shiny + form ===
        // Cobblemon's formOnlyShowdownId() strips non-alphanumeric chars for matching,
        // so "mega-x" → "megax" correctly matches form "Mega-X" → "megax".
        try {
            Class<?> propsClass = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonProperties");
            Object companion = propsClass.getField("Companion").get(null);

            // Build full properties string including form
            StringBuilder propsStr = new StringBuilder(speciesName.toLowerCase(Locale.ROOT));
            if (shiny) propsStr.append(" shiny");
            if (form != null && !form.isEmpty()) propsStr.append(" ").append(form);

            Object props = null;
            for (Method m : companion.getClass().getMethods()) {
                if (m.getName().equals("parse") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == String.class) {
                    props = m.invoke(companion, propsStr.toString());
                    break;
                }
            }
            if (props == null) {
                for (Method m : companion.getClass().getMethods()) {
                    if (m.getName().equals("parse") && m.getParameterCount() == 3) {
                        props = m.invoke(companion, propsStr.toString(), " ", "=");
                        break;
                    }
                }
            }

            if (props != null) {
                for (Method m : props.getClass().getMethods()) {
                    if (m.getName().equals("apply") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(pokemon)) {
                        m.invoke(props, pokemon);
                        LOGGER.info("[CobbleShift] PokemonProperties '{}' applied for '{}'", propsStr, speciesName);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] PokemonProperties approach failed: {}", e.getMessage());
        }

        // === Step 2: ALWAYS try manual shiny + form matching (not just fallback) ===
        // PokemonProperties.parse() may silently ignore unknown tokens, so we must always try this.
        if (shiny) {
            for (Class<?> paramType : new Class<?>[]{boolean.class, Boolean.class}) {
                try {
                    pokemonClass.getMethod("setShiny", paramType).invoke(pokemon, true);
                    break;
                } catch (Exception ignored) {}
            }
        }

        // Collect canonical aspect strings from matched forms
        Set<String> canonicalFormAspects = new java.util.HashSet<>();

        if (formTokens.length > 0) {
            try {
                Object forms = speciesObj.getClass().getMethod("getForms").invoke(speciesObj);
                if (forms instanceof Iterable<?> iterable) {
                    // Log available forms for debugging
                    for (Object formObj : iterable) {
                        String fName = null;
                        Set<String> fAspects = Set.of();
                        try { fName = (String) formObj.getClass().getMethod("getName").invoke(formObj); }
                        catch (Exception ignored) { try { fName = formObj.toString(); } catch (Exception ignored2) {} }
                        try {
                            Object fa = formObj.getClass().getMethod("getAspects").invoke(formObj);
                            if (fa instanceof Iterable<?> fas) {
                                Set<String> temp = new java.util.HashSet<>();
                                for (Object a : fas) { if (a instanceof String s) temp.add(s); }
                                fAspects = temp;
                            }
                        } catch (Exception ignored) {}
                        LOGGER.debug("[CobbleShift] Available form: name='{}' aspects={}", fName, fAspects);
                    }

                    // Now match — iterate again to find the best form
                    boolean formSet = false;
                    for (Object formObj : iterable) {
                        if (formSet) break;

                        String fName = null;
                        try { fName = (String) formObj.getClass().getMethod("getName").invoke(formObj); }
                        catch (Exception ignored) { try { fName = formObj.toString(); } catch (Exception ignored2) {} }

                        // Get form's declared aspects (FormData.getAspects() returns List<String>)
                        Set<String> fAspects = new java.util.HashSet<>();
                        try {
                            Object fa = formObj.getClass().getMethod("getAspects").invoke(formObj);
                            if (fa instanceof Iterable<?> fas) {
                                for (Object a : fas) { if (a instanceof String s) fAspects.add(s); }
                            }
                        } catch (Exception ignored) {}

                        // Match 1: Token matches form name (case-insensitive, substring)
                        // Normalize hyphens/underscores for comparison
                        boolean nameMatch = false;
                        if (fName != null) {
                            String fNameNorm = fName.toLowerCase(Locale.ROOT).replace('_', '-');
                            for (String token : formTokens) {
                                String tokenNorm = token.toLowerCase(Locale.ROOT).replace('_', '-');
                                // Don't substring-match key=value tokens against form names
                                if (tokenNorm.contains("=")) continue;
                                if (fNameNorm.equals(tokenNorm) || fNameNorm.contains(tokenNorm) || tokenNorm.contains(fNameNorm)) {
                                    nameMatch = true;
                                    break;
                                }
                            }
                        }

                        // Match 2: Token matches one of the form's aspect strings
                        // Normalize hyphens/underscores for comparison
                        boolean aspectMatch = false;
                        for (String aspect : fAspects) {
                            String aspectNorm = aspect.toLowerCase(Locale.ROOT).replace('_', '-');
                            for (String token : formTokens) {
                                String tokenNorm = token.toLowerCase(Locale.ROOT).replace('_', '-');
                                if (aspectNorm.equals(tokenNorm)) {
                                    aspectMatch = true;
                                    break;
                                }
                            }
                            if (aspectMatch) break;
                        }

                        if (nameMatch || aspectMatch) {
                            for (Method m : pokemonClass.getMethods()) {
                                if (m.getName().equals("setForm") && m.getParameterCount() == 1
                                    && m.getParameterTypes()[0].isInstance(formObj)) {
                                    m.invoke(pokemon, formObj);
                                    formSet = true;
                                    canonicalFormAspects.addAll(fAspects);
                                    LOGGER.info("[CobbleShift] Form '{}' set for '{}' (nameMatch={}, aspectMatch={}, canonicalAspects={})",
                                        fName, speciesName, nameMatch, aspectMatch, fAspects);
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("[CobbleShift] Form lookup failed: {}", e.getMessage());
            }
        }

        // === Step 3: Build NEW forcedAspects set and use setter ===
        // CRITICAL: forcedAspects defaults to Kotlin's immutable emptySet().
        // Calling .add() on it throws UnsupportedOperationException (caught silently before).
        // We MUST build a NEW mutable set and use setForcedAspects(newSet).
        // The setter automatically calls updateAspects() internally.
        try {
            Set<String> newForcedAspects = new LinkedHashSet<>();

            if (shiny) newForcedAspects.add("shiny");

            // Add canonical aspects from matched form (e.g., "mega_x" from Mega-X form)
            newForcedAspects.addAll(canonicalFormAspects);

            // Add user-provided tokens + both hyphen and underscore variants
            for (String token : formTokens) {
                String tokenLower = token.toLowerCase(Locale.ROOT);
                newForcedAspects.add(tokenLower);
                newForcedAspects.add(tokenLower.replace('_', '-'));
                newForcedAspects.add(tokenLower.replace('-', '_'));

                // Heuristic: convert species feature key=value to Cobblemon's aspect format
                if (tokenLower.contains("=")) {
                    String[] kv = tokenLower.split("=", 2);
                    String featureAspect = kv[0].replace('_', '-') + "-" + kv[1];
                    newForcedAspects.add(featureAspect);
                }
            }

            // Also include the current form's declared aspects (set by Step 1 or Step 2)
            // FormData.getAspects() returns List<String>, not Set
            try {
                Object currentForm = pokemonClass.getMethod("getForm").invoke(pokemon);
                if (currentForm != null) {
                    Object fa = currentForm.getClass().getMethod("getAspects").invoke(currentForm);
                    if (fa instanceof Iterable<?> fas) {
                        for (Object a : fas) { if (a instanceof String s) newForcedAspects.add(s); }
                    }
                }
            } catch (Exception ignored) {}

            // Use the setter: assigns the set directly + calls updateAspects() automatically
            pokemonClass.getMethod("setForcedAspects", Set.class).invoke(pokemon, newForcedAspects);
            LOGGER.info("[CobbleShift] setForcedAspects({}) for '{}'", newForcedAspects, speciesName);
        } catch (Exception e) {
            LOGGER.warn("[CobbleShift] setForcedAspects failed for '{}': {}", speciesName, e.getMessage());
        }

        // === Step 4: Call updateForm() — ensures form selection matches aspects ===
        try { pokemonClass.getMethod("updateForm").invoke(pokemon); }
        catch (NoSuchMethodException ignored) {}
        catch (Exception e) { LOGGER.debug("[CobbleShift] updateForm failed: {}", e.getMessage()); }

        // === Step 5: Verify final state ===
        try {
            Set<String> aspects = (Set<String>) pokemonClass.getMethod("getAspects").invoke(pokemon);
            Object finalForm = pokemonClass.getMethod("getForm").invoke(pokemon);
            String formName = finalForm != null ? (String) finalForm.getClass().getMethod("getName").invoke(finalForm) : "?";
            LOGGER.info("[CobbleShift] Final state for '{}': form='{}', aspects={}", speciesName, formName, aspects);
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Verification failed: {}", e.getMessage());
        }
    }

    /**
     * Force-sync the ASPECTS TrackedData on the entity from the Pokemon's current aspects.
     * This is needed because on client, the Pokemon's aspects might not have been fully
     * computed when the entity constructor ran.
     *
     * Also tries re-setting the Pokemon on the entity (triggers the setter which syncs all TrackedData).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void syncEntityDataTrackerAspects(Entity entity, Object pokemon, Class<?> pokemonClass, String speciesName) {
        try {
            Set<String> aspects = (Set<String>) pokemonClass.getMethod("getAspects").invoke(pokemon);
            if (aspects == null || aspects.isEmpty()) {
                LOGGER.debug("[CobbleShift] No aspects to sync for '{}'", speciesName);
                return;
            }

            Class<?> peClass = entity.getClass();

            // Approach 1: Find ASPECTS TrackedData key and set it directly
            TrackedData aspectsKey = null;
            // Try companion getter (Kotlin pattern)
            try {
                Object companion = peClass.getField("Companion").get(null);
                for (Method m : companion.getClass().getMethods()) {
                    if (m.getName().equals("getASPECTS") && m.getParameterCount() == 0) {
                        aspectsKey = (TrackedData) m.invoke(companion);
                        break;
                    }
                }
            } catch (Exception ignored) {}
            // Try static getter
            if (aspectsKey == null) {
                try { aspectsKey = (TrackedData) peClass.getMethod("getASPECTS").invoke(null); }
                catch (Exception ignored) {}
            }

            if (aspectsKey != null) {
                entity.getDataTracker().set(aspectsKey, aspects);
                LOGGER.info("[CobbleShift] Synced ASPECTS DataTracker: {} for '{}'", aspects, speciesName);

                // Also notify the entity that tracked data changed (triggers renderer update)
                notifyTrackedDataSet(entity, aspectsKey);
            }

            // Approach 2: Also try re-setting the Pokemon on the entity (triggers full sync)
            for (Method m : peClass.getMethods()) {
                if (m.getName().equals("setPokemon") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(pokemon)) {
                    m.invoke(entity, pokemon);
                    LOGGER.info("[CobbleShift] Re-set Pokemon on entity for '{}'", speciesName);
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] DataTracker sync failed for '{}': {}", speciesName, e.getMessage());
        }
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
}
