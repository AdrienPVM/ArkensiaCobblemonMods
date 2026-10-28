package com.cheesestore.cosmetic;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns and maintains ONE cosmetic companion ("pet") entity per player that has a
 * {@code PET} cosmetic equipped (category resolved by {@link EquipManager}).
 *
 * <p>The pet:
 * <ul>
 *   <li>is the entity type named by the entry param {@code mob} (e.g. "minecraft:allay"),
 *       optionally a baby ({@code baby=true});</li>
 *   <li>is INVULNERABLE, SILENT, has no vanilla AI/targeting (cannot attack or wander),
 *       is non-persistent (won't be saved to disk), and won't despawn naturally;</li>
 *   <li>carries the scoreboard tags {@link #TAG_PET} and {@code cos_pet_owner.<uuid>} so it
 *       can always be found / cleaned up even after a server reload;</li>
 *   <li>smoothly follows its owner each tick (teleporting if it falls too far behind, e.g.
 *       after a dimension change).</li>
 * </ul>
 *
 * <p>Despawned when the owner disconnects, changes pet, or un-equips. Exactly one pet per
 * player is enforced. Invalid / unknown mob ids are handled gracefully (logged once, skipped).
 *
 * <p>Contract (do not change): {@code public static void init()} — idempotent, registers its
 * own Fabric tick + disconnect events. Also exposes {@link #refresh(ServerPlayerEntity)} so the
 * equip layer can re-sync a single player immediately when their equip changes.
 */
public final class PetManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");

    /** Master tag every cosmetic pet carries. */
    public static final String TAG_PET = "cos_pet";
    /** Per-owner tag suffix, e.g. {@code cos_pet_owner.<uuid>}. */
    public static final String TAG_OWNER_PREFIX = "cos_pet_owner.";

    private static final String PET_CATEGORY = "PET";

    /** How close (blocks) the pet tries to hover beside the owner. Fixed lateral offset,
     *  independent of where the owner is looking, so the pet never "flees" the gaze. */
    private static final double FOLLOW_OFFSET_SIDE = 0.8;
    /** Hover height for FLYING pets (blocks above the owner's feet). */
    private static final double FLYER_HOVER_Y = 1.0;
    /** Per-tick interpolation factor for smooth follow (0..1). */
    private static final double LERP = 0.25;
    /** If the pet is further than this from its target, hard-teleport instead of lerping. */
    private static final double TELEPORT_DIST_SQ = 20.0 * 20.0;
    /** Only re-evaluate equip/spawn state every N ticks (cheap); follow runs every tick. */
    private static final int RESYNC_INTERVAL = 20;

    /**
     * Entity types that should hover/fly (kept airborne, NoGravity). Everything else is treated
     * as a GROUND pet: gravity enabled so it stays on the floor at the owner's feet level.
     */
    private static final Set<EntityType<?>> FLYING_TYPES = Set.of(
            EntityType.ALLAY,
            EntityType.BEE,
            EntityType.PARROT,
            EntityType.VEX,
            EntityType.BAT,
            EntityType.PHANTOM,
            EntityType.GHAST,
            EntityType.BLAZE,
            EntityType.WITHER,
            EntityType.ENDER_DRAGON
    );

    private static boolean initialised = false;

    /** owner uuid -> live pet entity uuid (the spawned companion). */
    private static final Map<UUID, UUID> PET_BY_OWNER = new ConcurrentHashMap<>();
    /** owner uuid -> the PET cosmetic id currently materialised (to detect equip changes). */
    private static final Map<UUID, String> SPAWNED_FOR = new ConcurrentHashMap<>();
    /** Mob ids we already warned about being invalid (avoid log spam). */
    private static final Set<String> WARNED_BAD_MOBS = ConcurrentHashMap.newKeySet();

    private static int tickCounter = 0;

    private PetManager() {}

    public static void init() {
        if (initialised) return; // idempotent
        initialised = true;

        ServerTickEvents.END_SERVER_TICK.register(PetManager::onServerTick);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.player != null) despawnFor(handler.player.getUuid(), server);
        });

        LOGGER.info("[CheeseStore] PetManager initialised");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Re-sync a single player's pet immediately (call right after their PET equip changes).
     * Spawns, replaces, or despawns the companion to match {@link EquipManager}.
     */
    public static void refresh(ServerPlayerEntity player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        try {
            syncPlayer(player, server);
        } catch (Exception e) {
            LOGGER.warn("[CheeseStore] PetManager.refresh failed for {}: {}",
                    player.getName().getString(), e.getMessage());
        }
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    private static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        boolean resync = (++tickCounter % RESYNC_INTERVAL == 0);

        Set<UUID> onlineWithPets = resync ? new HashSet<>() : null;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            try {
                if (resync) {
                    syncPlayer(player, server);
                    if (PET_BY_OWNER.containsKey(player.getUuid())) onlineWithPets.add(player.getUuid());
                }
                followTick(player, server);
            } catch (Exception e) {
                LOGGER.debug("[CheeseStore] pet tick failed for {}: {}",
                        player.getName().getString(), e.getMessage());
            }
        }

        // Periodic GC: drop tracking for owners no longer online / pets that vanished,
        // and discard orphaned pet entities (e.g. after a crash-reload).
        if (resync) {
            for (Iterator<Map.Entry<UUID, UUID>> it = PET_BY_OWNER.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, UUID> e = it.next();
                UUID ownerId = e.getKey();
                if (!onlineWithPets.contains(ownerId)) {
                    discardPetEntity(e.getValue(), server);
                    SPAWNED_FOR.remove(ownerId);
                    it.remove();
                }
            }
            // Belt-and-braces: sweep any tagged pet whose owner is offline or untracked.
            sweepOrphans(server);
        }
    }

    /** Reconcile one player's actual pet with what they have equipped. */
    private static void syncPlayer(ServerPlayerEntity player, MinecraftServer server) {
        UUID ownerId = player.getUuid();
        EquipManager equip = EquipManager.getInstance();
        CosmeticManager catalog = CosmeticManager.getInstance();
        if (equip == null || catalog == null) return;

        String equippedId = equip.getEquipped(ownerId, PET_CATEGORY);
        CosmeticEntry entry = equippedId == null ? null : catalog.get(equippedId);
        boolean wantPet = entry != null && entry.isEnabled()
                && PET_CATEGORY.equalsIgnoreCase(entry.getCategory());

        String current = SPAWNED_FOR.get(ownerId);
        Entity live = livePet(ownerId, server);

        // Owner is dead (death screen / awaiting respawn) or spectating → no pet should be visible.
        // We keep SPAWNED_FOR so the periodic resync re-spawns the same pet once they're alive again.
        if (isOwnerDead(player)) {
            if (live != null) discardPetEntity(PET_BY_OWNER.remove(ownerId), server);
            else PET_BY_OWNER.remove(ownerId);
            // Defensive: kill any stray tagged pet for this owner while they're down.
            discardTaggedPetsFor(ownerId, server);
            return;
        }

        // Nothing equipped (or disabled/removed) → make sure no pet remains.
        if (!wantPet) {
            if (live != null || current != null) despawnFor(ownerId, server);
            return;
        }

        // Correct pet already alive → nothing to do.
        if (live != null && equippedId.equals(current)) return;

        // Equip changed, or the entity went missing → (re)spawn.
        despawnFor(ownerId, server);
        spawnPet(player, entry, server);
    }

    // ── Spawning ──────────────────────────────────────────────────────────────

    private static void spawnPet(ServerPlayerEntity owner, CosmeticEntry entry, MinecraftServer server) {
        String mobId = entry.param("mob", "");
        if (mobId == null || mobId.isBlank()) return;

        EntityType<?> type = resolveType(mobId);
        if (type == null) {
            if (WARNED_BAD_MOBS.add(mobId)) {
                LOGGER.warn("[CheeseStore] PET '{}' has invalid mob id '{}' — skipping",
                        entry.getId(), mobId);
            }
            return;
        }

        ServerWorld world = owner.getServerWorld();
        Entity entity;
        try {
            entity = type.create(world);
        } catch (Exception e) {
            if (WARNED_BAD_MOBS.add(mobId)) {
                LOGGER.warn("[CheeseStore] PET '{}' failed to create mob '{}': {}",
                        entry.getId(), mobId, e.getMessage());
            }
            return;
        }
        if (entity == null) {
            if (WARNED_BAD_MOBS.add(mobId)) {
                LOGGER.warn("[CheeseStore] PET '{}' could not instantiate mob '{}' — skipping",
                        entry.getId(), mobId);
            }
            return;
        }

        boolean flyer = isFlyer(type);

        Vec3d target = followTarget(owner, flyer);
        entity.refreshPositionAndAngles(target.x, target.y, target.z, owner.getYaw(), 0f);

        // Make it a harmless, decorative companion.
        entity.setInvulnerable(true);
        entity.setSilent(true);
        // Belt-and-braces against fire/lava ticking damage on invulnerable mobs.
        entity.setFireTicks(0);
        entity.extinguish();
        // Flyers hover (NoGravity); ground pets keep gravity so they stay on the floor.
        entity.setNoGravity(flyer);
        entity.addCommandTag(TAG_PET);
        entity.addCommandTag(TAG_OWNER_PREFIX + owner.getUuid());

        // NEVER show a nametag above the pet. (Catalogue petName is intentionally NOT displayed.)
        entity.setCustomNameVisible(false);

        if (entity instanceof MobEntity mob) {
            mob.setAiDisabled(true);       // freeze vanilla brain (no wander/attack/target)
            mob.setPersistent();           // never natural-despawn while alive
            // Sit tame mobs so they don't play stand/idle path animations awkwardly.
            if (mob instanceof TameableEntity tame) {
                tame.setOwnerUuid(owner.getUuid());
                tame.setTamed(true, false);
            }
            if (entry.paramBool("baby", false)) {
                try { mob.setBaby(true); } catch (Exception ignored) {}
            }
        }
        if (entity instanceof LivingEntity living) {
            living.setHealth(living.getMaxHealth());
        }

        if (!world.spawnEntity(entity)) {
            entity.discard();
            return;
        }

        // Make the familiar NON-SOLID (no-collision team) so it can never push the player.
        MountManager.applyNoCollision(entity, world.getServer());

        PET_BY_OWNER.put(owner.getUuid(), entity.getUuid());
        SPAWNED_FOR.put(owner.getUuid(), entry.getId());
    }

    private static EntityType<?> resolveType(String mobId) {
        try {
            Identifier id = Identifier.tryParse(mobId);
            if (id == null) return null;
            if (!Registries.ENTITY_TYPE.containsId(id)) return null;
            return Registries.ENTITY_TYPE.get(id);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Following ─────────────────────────────────────────────────────────────

    private static void followTick(ServerPlayerEntity owner, MinecraftServer server) {
        UUID petId = PET_BY_OWNER.get(owner.getUuid());
        if (petId == null) return;

        // Owner died/started spectating mid-tick → despawn now (don't wait for the resync).
        // SPAWNED_FOR is kept so the pet reappears at their side on respawn.
        if (isOwnerDead(owner)) {
            discardPetEntity(PET_BY_OWNER.remove(owner.getUuid()), server);
            return;
        }

        Entity pet = findEntity(server, petId);
        if (pet == null) {
            // Entity disappeared (chunk unload across dimension, killed, etc.) — drop tracking;
            // the periodic resync will respawn it if a pet is still equipped.
            PET_BY_OWNER.remove(owner.getUuid());
            SPAWNED_FOR.remove(owner.getUuid());
            return;
        }

        // Dimension mismatch (owner changed worlds): re-anchor by despawn → resync respawns.
        if (pet.getEntityWorld() != owner.getEntityWorld()) {
            despawnFor(owner.getUuid(), server);
            return;
        }

        boolean flyer = isFlyer(pet.getType());
        Vec3d target = followTarget(owner, flyer);
        double distSq = pet.squaredDistanceTo(target.x, target.y, target.z);

        // The pet's facing follows the owner's body yaw (not their look pitch), so it stays
        // beside them naturally without ever fleeing the camera.
        float yaw = owner.getBodyYaw();
        if (distSq > TELEPORT_DIST_SQ) {
            pet.refreshPositionAndAngles(target.x, target.y, target.z, yaw, 0f);
            pet.setVelocity(Vec3d.ZERO);
        } else {
            double nx = MathHelper.lerp(LERP, pet.getX(), target.x);
            // Ground pets snap their Y straight to target (the owner's feet level) so gravity
            // never makes them dip/jitter; flyers lerp smoothly to their hover height.
            double ny = flyer ? MathHelper.lerp(LERP, pet.getY(), target.y) : target.y;
            double nz = MathHelper.lerp(LERP, pet.getZ(), target.z);
            pet.refreshPositionAndAngles(nx, ny, nz, yaw, 0f);
            pet.setVelocity(Vec3d.ZERO);
        }
        pet.setHeadYaw(yaw);
        pet.setBodyYaw(yaw);
        pet.fallDistance = 0f;
    }

    /**
     * Follow point: a FIXED lateral offset to the owner's right, at a constant distance — it does
     * NOT depend on where the owner is looking, so the pet never teleports behind the camera.
     * The lateral direction is derived from the owner's body yaw (movement facing), which is
     * stable. Flyers hover above feet level; ground pets sit at the owner's feet (Y).
     */
    private static Vec3d followTarget(ServerPlayerEntity owner, boolean flyer) {
        float yawRad = owner.getBodyYaw() * ((float) Math.PI / 180f);
        // right unit vector relative to body facing
        double rx = MathHelper.cos(yawRad);
        double rz = MathHelper.sin(yawRad);
        double x = owner.getX() + rx * FOLLOW_OFFSET_SIDE;
        double z = owner.getZ() + rz * FOLLOW_OFFSET_SIDE;
        double y = owner.getY() + (flyer ? FLYER_HOVER_Y : 0.0);
        return new Vec3d(x, y, z);
    }

    /** True if this entity type should hover/fly; false → ground pet kept on the floor. */
    private static boolean isFlyer(EntityType<?> type) {
        return type != null && FLYING_TYPES.contains(type);
    }

    /** True when the owner has no living, in-world pawn (dead, awaiting respawn, or spectating). */
    private static boolean isOwnerDead(ServerPlayerEntity owner) {
        return owner.isDead() || !owner.isAlive() || owner.getHealth() <= 0f || owner.isSpectator();
    }

    /** Discard every tagged cosmetic pet belonging to this owner (no tracking changes). */
    private static void discardTaggedPetsFor(UUID ownerId, MinecraftServer server) {
        if (ownerId == null || server == null) return;
        String ownerTag = TAG_OWNER_PREFIX + ownerId;
        for (ServerWorld w : server.getWorlds()) {
            for (Entity e : w.iterateEntities()) {
                if (e.getCommandTags().contains(TAG_PET) && e.getCommandTags().contains(ownerTag)) {
                    e.discard();
                }
            }
        }
    }

    // ── Despawn / cleanup ───────────────────────────────────────────────────────

    /** Remove the player's pet (if any) and forget all tracking for them. */
    public static void despawnFor(UUID ownerId, MinecraftServer server) {
        if (ownerId == null) return;
        UUID petId = PET_BY_OWNER.remove(ownerId);
        SPAWNED_FOR.remove(ownerId);
        if (petId != null) discardPetEntity(petId, server);
        // Also discard any stray entity tagged for this owner (defensive).
        if (server != null) {
            String ownerTag = TAG_OWNER_PREFIX + ownerId;
            for (ServerWorld w : server.getWorlds()) {
                for (Entity e : w.iterateEntities()) {
                    if (e.getCommandTags().contains(TAG_PET)
                            && e.getCommandTags().contains(ownerTag)) {
                        e.discard();
                    }
                }
            }
        }
    }

    private static void discardPetEntity(UUID petId, MinecraftServer server) {
        if (petId == null || server == null) return;
        Entity e = findEntity(server, petId);
        if (e != null) e.discard();
    }

    /** The live tracked pet entity for an owner, or null if none / gone. */
    private static Entity livePet(UUID ownerId, MinecraftServer server) {
        UUID petId = PET_BY_OWNER.get(ownerId);
        if (petId == null) return null;
        return findEntity(server, petId);
    }

    private static Entity findEntity(MinecraftServer server, UUID entityId) {
        if (server == null || entityId == null) return null;
        for (ServerWorld w : server.getWorlds()) {
            Entity e = w.getEntity(entityId);
            if (e != null && e.isAlive()) return e;
        }
        return null;
    }

    /** Discard any tagged cosmetic pet whose owner is offline or not tracked to it. */
    private static void sweepOrphans(MinecraftServer server) {
        for (ServerWorld w : server.getWorlds()) {
            for (Entity e : w.iterateEntities()) {
                if (!e.getCommandTags().contains(TAG_PET)) continue;
                UUID ownerId = readOwner(e);
                if (ownerId == null) { e.discard(); continue; }
                boolean online = server.getPlayerManager().getPlayer(ownerId) != null;
                UUID tracked = PET_BY_OWNER.get(ownerId);
                boolean isTracked = tracked != null && tracked.equals(e.getUuid());
                if (!online || !isTracked) e.discard();
            }
        }
    }

    private static UUID readOwner(Entity e) {
        for (String tag : e.getCommandTags()) {
            if (tag.startsWith(TAG_OWNER_PREFIX)) {
                try { return UUID.fromString(tag.substring(TAG_OWNER_PREFIX.length())); }
                catch (Exception ignored) { return null; }
            }
        }
        return null;
    }
}
