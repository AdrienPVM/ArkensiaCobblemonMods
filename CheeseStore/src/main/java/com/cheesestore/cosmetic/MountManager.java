package com.cheesestore.cosmetic;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Saddleable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.HorseColor;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns and maintains AT MOST ONE cosmetic {@code MOUNT} entity per player, RIDDEN on demand.
 *
 * <p>Unlike {@link PetManager} (which auto-spawns a follower when a PET is equipped), a MOUNT is
 * purely an on-demand summon: equipping a MOUNT cosmetic only records the <em>selection</em> in
 * {@link EquipManager}; the rideable mob is spawned only by an explicit {@link #summon} (via the
 * {@code /mount} command or the cosmetics GUI). It is recalled automatically the instant its owner
 * enters combat, and on death / disconnect / world-change / un-equip.
 *
 * <p>The mount:
 * <ul>
 *   <li>is the rideable vanilla mob named by the entry param {@code mob}
 *       (horse/donkey/mule/pig/strider/camel/skeleton_horse, optionally a {@code variant} colour
 *       for horses and {@code saddle=true} for pig/strider);</li>
 *   <li>is INVULNERABLE, SILENT, persistent (won't natural-despawn), AI-disabled (steered by the
 *       rider via vanilla riding controls), has NO nametag, and is tamed/owned by the player;</li>
 *   <li>carries the scoreboard tags {@link #TAG_MOUNT} and {@code cos_mount_owner.<uuid>} so it can
 *       always be found / swept even after a server reload.</li>
 * </ul>
 *
 * <p><b>Cosmetic only.</b> The mount uses vanilla movement speed and grants no stat advantage; it
 * vanishes in combat so it cannot be used for an edge in a fight.
 *
 * <p>Combat detection is fully self-contained inside CheeseStore (no new cross-mod call): a Fabric
 * {@link ServerLivingEntityEvents#AFTER_DAMAGE} hook (owner took or dealt damage) plus a
 * belt-and-braces per-tick {@code owner.hurtTime} poll. The combat hooks NEVER throw into the
 * Fabric event (mirrors {@link CombatEffectsManager}'s never-throw discipline).
 *
 * <p>Contract: {@code public static void init()} — idempotent, registers its own Fabric events.
 * {@link #refresh(ServerPlayerEntity)} lets the equip layer re-sync one player (despawns the live
 * mount if it is no longer equipped/owned; it never auto-summons). {@link #summon} / {@link #dismiss}
 * drive the on-demand spawn from commands / GUI.
 */
public final class MountManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");

    /** Master tag every cosmetic mount carries (own namespace, separate from pets). */
    public static final String TAG_MOUNT = "cos_mount";
    /** Per-owner tag suffix, e.g. {@code cos_mount_owner.<uuid>}. */
    public static final String TAG_OWNER_PREFIX = "cos_mount_owner.";

    private static final String MOUNT_CATEGORY = "MOUNT";

    /** Re-summon lock-out after a combat recall (ticks). 200 ticks = ~10 s. */
    private static final int COOLDOWN_TICKS = 200;
    /** Only re-evaluate equip/orphan state every N ticks (cheap); combat poll runs every tick. */
    private static final int RESYNC_INTERVAL = 20;

    private static boolean initialised = false;

    /** owner uuid -> live mount entity uuid (the spawned ridden mob). */
    private static final Map<UUID, UUID> MOUNT_BY_OWNER = new ConcurrentHashMap<>();
    /** owner uuid -> the MOUNT cosmetic id currently materialised. */
    private static final Map<UUID, String> SUMMONED_FOR = new ConcurrentHashMap<>();
    /** owner uuid -> server-tick timestamp until which re-summon is locked out (combat cooldown). */
    private static final Map<UUID, Long> COMBAT_UNTIL = new ConcurrentHashMap<>();
    /** Mob ids we already warned about being invalid (avoid log spam). */
    private static final java.util.Set<String> WARNED_BAD_MOBS = ConcurrentHashMap.newKeySet();

    private static int tickCounter = 0;
    /** Monotonic server-tick counter for cooldown bookkeeping (server has no global tick getter we rely on). */
    private static long serverTicks = 0L;

    private MountManager() {}

    public static void init() {
        if (initialised) return; // idempotent
        initialised = true;

        ServerTickEvents.END_SERVER_TICK.register(MountManager::onServerTick);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.player != null) despawnFor(handler.player.getUuid(), server);
        });

        // (A) Combat signal: owner TOOK damage, or owner DEALT damage → recall their mount.
        // Never throws into the Fabric event (mirrors CombatEffectsManager).
        try {
            ServerLivingEntityEvents.AFTER_DAMAGE.register(
                    (LivingEntity entity, net.minecraft.entity.damage.DamageSource source,
                     float baseDamageTaken, float damageTaken, boolean blocked) -> {
                        try {
                            // Fast path: no mounts live at all → nothing to do.
                            if (MOUNT_BY_OWNER.isEmpty()) return;

                            // Victim took damage.
                            if (entity instanceof ServerPlayerEntity victim
                                    && MOUNT_BY_OWNER.containsKey(victim.getUuid())) {
                                combatDespawn(victim);
                            }
                            // Attacker dealt damage.
                            if (source != null && source.getAttacker() instanceof ServerPlayerEntity attacker
                                    && MOUNT_BY_OWNER.containsKey(attacker.getUuid())) {
                                combatDespawn(attacker);
                            }
                        } catch (Throwable t) {
                            LOGGER.debug("[CheeseStore] MountManager AFTER_DAMAGE error: {}", t.toString());
                        }
                    });
            LOGGER.info("[CheeseStore] MountManager initialised (combat recall via AFTER_DAMAGE + hurtTime poll)");
        } catch (Throwable t) {
            LOGGER.warn("[CheeseStore] MountManager.init() failed to register AFTER_DAMAGE: {}", t.toString());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Re-sync one player's mount: despawn it if the player no longer has a MOUNT equipped/owned, or
     * has changed which MOUNT is selected. Does NOT auto-summon — equipping only selects a mount.
     * Call after a MOUNT equip/unequip change.
     */
    public static void refresh(ServerPlayerEntity player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        try {
            UUID ownerId = player.getUuid();
            if (!MOUNT_BY_OWNER.containsKey(ownerId)) return; // no live mount → nothing to recall

            EquipManager equip = EquipManager.getInstance();
            CosmeticManager catalog = CosmeticManager.getInstance();
            if (equip == null || catalog == null) return;

            String equippedId = equip.getEquipped(ownerId, MOUNT_CATEGORY);
            CosmeticEntry entry = equippedId == null ? null : catalog.get(equippedId);
            boolean stillValid = entry != null && entry.isEnabled()
                    && MOUNT_CATEGORY.equalsIgnoreCase(entry.getCategory())
                    && ownsEquipped(ownerId, equippedId)
                    && equippedId.equals(SUMMONED_FOR.get(ownerId));

            if (!stillValid) {
                // Voluntary-style recall (no cooldown): the selection changed or was removed.
                dismiss(player);
            }
        } catch (Throwable t) {
            LOGGER.warn("[CheeseStore] MountManager.refresh failed for {}: {}",
                    player.getName().getString(), t.toString());
        }
    }

    /** True if the player currently has a live, tracked mount. */
    public static boolean hasMount(ServerPlayerEntity player) {
        return player != null && MOUNT_BY_OWNER.containsKey(player.getUuid());
    }

    /**
     * Spawn and mount the player's equipped MOUNT. Guards: must own + have a MOUNT equipped, must be
     * alive/non-spectator, and must not be in the post-combat cooldown. Returns {@code true} if a
     * mount was summoned. Sends the player feedback on failure.
     */
    public static boolean summon(ServerPlayerEntity player) {
        if (player == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;

        EquipManager equip = EquipManager.getInstance();
        CosmeticManager catalog = CosmeticManager.getInstance();
        OwnershipManager ownership = OwnershipManager.getInstance();
        if (equip == null || catalog == null || ownership == null) {
            tell(player, "Système de montures indisponible.", Formatting.RED);
            return false;
        }

        UUID ownerId = player.getUuid();

        if (isOwnerDead(player)) {
            tell(player, "Impossible d'invoquer une monture maintenant.", Formatting.RED);
            return false;
        }

        // Post-combat cooldown.
        long until = COMBAT_UNTIL.getOrDefault(ownerId, 0L);
        if (serverTicks < until) {
            long remainingSec = Math.max(1, (until - serverTicks) / 20);
            tell(player, "Monture en rappel (combat). Réessaie dans " + remainingSec + " s.", Formatting.RED);
            return false;
        }

        String equippedId = equip.getEquipped(ownerId, MOUNT_CATEGORY);
        if (equippedId == null) {
            tell(player, "Aucune monture équipée. /cosmetics pour en équiper une.", Formatting.YELLOW);
            return false;
        }
        CosmeticEntry entry = catalog.get(equippedId);
        if (entry == null || !entry.isEnabled() || !MOUNT_CATEGORY.equalsIgnoreCase(entry.getCategory())) {
            tell(player, "Monture équipée introuvable ou désactivée.", Formatting.RED);
            return false;
        }
        if (!ownership.owns(ownerId, equippedId)) {
            tell(player, "Tu ne possèdes pas cette monture.", Formatting.RED);
            return false;
        }

        // Replace any existing mount first (idempotent re-summon).
        despawnFor(ownerId, server);

        try {
            boolean ok = spawnMount(player, entry, server);
            if (!ok) {
                tell(player, "Impossible d'invoquer cette monture.", Formatting.RED);
            } else {
                tell(player, "Monture invoquée : " + entry.getTitle(), Formatting.GREEN);
            }
            return ok;
        } catch (Throwable t) {
            LOGGER.warn("[CheeseStore] MountManager.summon failed for {}: {}",
                    player.getName().getString(), t.toString());
            tell(player, "Impossible d'invoquer cette monture.", Formatting.RED);
            return false;
        }
    }

    /** Voluntarily dismiss the player's mount (no cooldown). */
    public static void dismiss(ServerPlayerEntity player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        boolean had = MOUNT_BY_OWNER.containsKey(player.getUuid());
        despawnFor(player.getUuid(), server);
        if (had) tell(player, "Monture renvoyée.", Formatting.YELLOW);
    }

    /** Toggle: summon if no mount is live, otherwise dismiss. Returns true if a mount is now live. */
    public static boolean toggle(ServerPlayerEntity player) {
        if (player == null) return false;
        if (hasMount(player)) {
            dismiss(player);
            return false;
        }
        return summon(player);
    }

    // ── Combat recall ───────────────────────────────────────────────────────────

    /** Recall the mount because the owner entered combat; sets the re-summon cooldown. Idempotent. */
    private static void combatDespawn(ServerPlayerEntity owner) {
        if (owner == null) return;
        // Idempotent: remove-then-act, so concurrent AFTER_DAMAGE / hurtTime-poll triggers are safe.
        if (!MOUNT_BY_OWNER.containsKey(owner.getUuid())) return;
        MinecraftServer server = owner.getServer();
        boolean had = MOUNT_BY_OWNER.containsKey(owner.getUuid());
        despawnFor(owner.getUuid(), server);
        COMBAT_UNTIL.put(owner.getUuid(), serverTicks + COOLDOWN_TICKS);
        if (had) tell(owner, "Monture rappelée (combat).", Formatting.GOLD);
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    private static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        serverTicks++;
        boolean resync = (++tickCounter % RESYNC_INTERVAL == 0);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            try {
                UUID ownerId = player.getUuid();
                if (!MOUNT_BY_OWNER.containsKey(ownerId)) continue;

                // Lifecycle: owner dead / awaiting respawn / spectator → recall (voluntary, no cooldown).
                if (isOwnerDead(player)) {
                    despawnFor(ownerId, server);
                    continue;
                }

                Entity mount = findEntity(server, MOUNT_BY_OWNER.get(ownerId));
                if (mount == null) {
                    // Entity vanished (killed/chunk-unload) → drop tracking; no auto-respawn.
                    MOUNT_BY_OWNER.remove(ownerId);
                    SUMMONED_FOR.remove(ownerId);
                    continue;
                }

                // Dimension mismatch (owner changed worlds) → recall.
                if (mount.getEntityWorld() != player.getEntityWorld()) {
                    despawnFor(ownerId, server);
                    continue;
                }

                // (B) Belt-and-braces combat poll: any damage path AFTER_DAMAGE may have missed.
                if (player.hurtTime > 0) {
                    combatDespawn(player);
                    continue;
                }

                // Safety: if the owner is no longer actually riding their mount (e.g. they pressed
                // shift to dismount), recall it so an invulnerable persistent mob isn't left behind.
                if (player.getVehicle() != mount) {
                    despawnFor(ownerId, server);
                    continue;
                }
            } catch (Throwable t) {
                LOGGER.debug("[CheeseStore] mount tick failed for {}: {}",
                        player.getName().getString(), t.toString());
            }
        }

        if (resync) {
            // GC tracking for owners no longer online, then sweep orphaned tagged mounts.
            try {
                for (Iterator<Map.Entry<UUID, UUID>> it = MOUNT_BY_OWNER.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<UUID, UUID> e = it.next();
                    UUID ownerId = e.getKey();
                    if (server.getPlayerManager().getPlayer(ownerId) == null) {
                        discardMountEntity(e.getValue(), server);
                        SUMMONED_FOR.remove(ownerId);
                        it.remove();
                    }
                }
                sweepOrphans(server);
                // Opportunistic cleanup of stale cooldowns so the map can't grow unbounded.
                COMBAT_UNTIL.entrySet().removeIf(e -> serverTicks > e.getValue() + COOLDOWN_TICKS);
            } catch (Throwable t) {
                LOGGER.debug("[CheeseStore] mount resync sweep failed: {}", t.toString());
            }
        }
    }

    // ── Spawning ──────────────────────────────────────────────────────────────

    private static boolean spawnMount(ServerPlayerEntity owner, CosmeticEntry entry, MinecraftServer server) {
        String mobId = entry.param("mob", "");
        if (mobId == null || mobId.isBlank()) return false;

        EntityType<?> type = resolveType(mobId);
        if (type == null) {
            if (WARNED_BAD_MOBS.add(mobId)) {
                LOGGER.warn("[CheeseStore] MOUNT '{}' has invalid mob id '{}' — skipping", entry.getId(), mobId);
            }
            return false;
        }

        ServerWorld world = owner.getServerWorld();
        Entity entity;
        try {
            entity = type.create(world);
        } catch (Exception e) {
            if (WARNED_BAD_MOBS.add(mobId)) {
                LOGGER.warn("[CheeseStore] MOUNT '{}' failed to create mob '{}': {}", entry.getId(), mobId, e.getMessage());
            }
            return false;
        }
        if (entity == null) {
            if (WARNED_BAD_MOBS.add(mobId)) {
                LOGGER.warn("[CheeseStore] MOUNT '{}' could not instantiate mob '{}' — skipping", entry.getId(), mobId);
            }
            return false;
        }

        // Spawn at the owner's feet, facing the same way.
        entity.refreshPositionAndAngles(owner.getX(), owner.getY(), owner.getZ(), owner.getYaw(), 0f);

        // Harmless, decorative-but-rideable mount.
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.setFireTicks(0);
        entity.extinguish();
        entity.setCustomNameVisible(false); // NEVER show a nametag
        entity.addCommandTag(TAG_MOUNT);
        entity.addCommandTag(TAG_OWNER_PREFIX + owner.getUuid());

        if (entity instanceof MobEntity mob) {
            // Disable the vanilla brain so it never wanders/targets. Player STEERING still works:
            // horses/camels/donkeys/mules are driven by rider input; pig/strider need the rider to
            // hold their control item (carrot/warped-fungus on a stick) — vanilla, not AI-driven.
            mob.setAiDisabled(true);
            mob.setPersistent(); // never natural-despawn while alive
            if (entry.paramBool("baby", false)) {
                try { mob.setBaby(true); } catch (Exception ignored) {}
            }
        }

        // Tame / own so the player is recognised as the rider's owner where applicable.
        if (entity instanceof AbstractHorseEntity horse) {
            try {
                horse.setTame(true);
                horse.setOwnerUuid(owner.getUuid());
            } catch (Throwable ignored) {}
        } else if (entity instanceof TameableEntity tame) {
            try {
                tame.setOwnerUuid(owner.getUuid());
                tame.setTamed(true, false);
            } catch (Throwable ignored) {}
        }

        // Variant (horse colour).
        applyVariant(entity, entry.param("variant", null));

        // Saddle, where requested / required. Wrapped so an unsaddled mount still spawns.
        if (entry.paramBool("saddle", false) && entity instanceof Saddleable saddleable) {
            try {
                if (saddleable.canBeSaddled() && !saddleable.isSaddled()) {
                    saddleable.saddle(new ItemStack(Items.SADDLE), SoundCategory.NEUTRAL);
                }
            } catch (Throwable t) {
                LOGGER.debug("[CheeseStore] MOUNT '{}' saddle failed: {}", entry.getId(), t.toString());
            }
        }

        if (entity instanceof LivingEntity living) {
            living.setHealth(living.getMaxHealth());
        }

        if (!world.spawnEntity(entity)) {
            entity.discard();
            return false;
        }

        // Make the mount NON-SOLID (no-collision team) so it never pushes the player on dismount.
        applyNoCollision(entity, world.getServer());

        // Mount up. force=true so vanilla doesn't refuse the ride.
        boolean riding;
        try {
            riding = owner.startRiding(entity, true);
        } catch (Throwable t) {
            riding = false;
        }
        if (!riding) {
            entity.discard();
            return false;
        }

        // Pig & strider only steer if the rider HOLDS a control item — give a marked one so we can clean it up.
        String ctrlMob = entry.param("mob", "");
        if ("minecraft:pig".equalsIgnoreCase(ctrlMob)) giveControlItem(owner, Items.CARROT_ON_A_STICK, "Rênes du cochon");
        else if ("minecraft:strider".equalsIgnoreCase(ctrlMob)) giveControlItem(owner, Items.WARPED_FUNGUS_ON_A_STICK, "Rênes du strider");

        MOUNT_BY_OWNER.put(owner.getUuid(), entity.getUuid());
        SUMMONED_FOR.put(owner.getUuid(), entry.getId());
        return true;
    }

    // ── Mount control items (pig/strider) + no-collision team ─────────────────────
    private static final String CTRL_TAG = "fw_mount_ctrl";

    private static void giveControlItem(ServerPlayerEntity owner, net.minecraft.item.Item item, String name) {
        try {
            ItemStack stack = new ItemStack(item);
            NbtCompound nbt = new NbtCompound();
            nbt.putBoolean(CTRL_TAG, true);
            stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).formatted(Formatting.GOLD));
            if (owner.getOffHandStack().isEmpty()) {
                owner.setStackInHand(Hand.OFF_HAND, stack);
            } else if (owner.getMainHandStack().isEmpty()) {
                owner.setStackInHand(Hand.MAIN_HAND, stack);
            } else if (owner.getInventory().getEmptySlot() >= 0) {
                owner.getInventory().insertStack(stack);
                owner.sendMessage(Text.literal("§7Tiens les rênes en main pour diriger ta monture.").formatted(Formatting.GRAY), true);
            } else {
                owner.sendMessage(Text.literal("§eLibère une main pour diriger cette monture (rênes non données).").formatted(Formatting.YELLOW), true);
            }
        } catch (Throwable ignored) {}
    }

    /** Remove any reins we handed out (called on despawn). */
    private static void removeControlItems(ServerPlayerEntity owner) {
        if (owner == null) return;
        try {
            var inv = owner.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty()) continue;
                NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
                if (c != null && c.copyNbt().getBoolean(CTRL_TAG)) inv.setStack(i, ItemStack.EMPTY);
            }
        } catch (Throwable ignored) {}
    }

    /** Add an entity to a shared no-collision scoreboard team so it never pushes players. */
    static void applyNoCollision(Entity entity, MinecraftServer server) {
        if (entity == null || server == null) return;
        try {
            Scoreboard sb = server.getScoreboard();
            Team team = sb.getTeam("fw_nocollide");
            if (team == null) {
                team = sb.addTeam("fw_nocollide");
                team.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
                team.setShowFriendlyInvisibles(false);
            }
            sb.addScoreHolderToTeam(entity.getNameForScoreboard(), team);
        } catch (Throwable ignored) {}
    }

    private static void applyVariant(Entity entity, String variant) {
        if (variant == null || variant.isBlank()) return;
        try {
            if (entity instanceof HorseEntity horse) {
                HorseColor color = horseColor(variant);
                if (color != null) horse.setVariant(color);
            }
            // Other mob variants (e.g. camel has none) intentionally ignored.
        } catch (Throwable t) {
            LOGGER.debug("[CheeseStore] applyVariant('{}') failed: {}", variant, t.toString());
        }
    }

    private static HorseColor horseColor(String variant) {
        try {
            return HorseColor.valueOf(variant.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return null;
        }
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

    // ── Despawn / cleanup ───────────────────────────────────────────────────────

    /** Remove the player's mount (dismounting first) and forget all tracking for them. Idempotent. */
    public static void despawnFor(UUID ownerId, MinecraftServer server) {
        if (ownerId == null) return;
        UUID mountId = MOUNT_BY_OWNER.remove(ownerId);
        SUMMONED_FOR.remove(ownerId);
        // Reclaim any reins we handed out for a pig/strider.
        if (server != null) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerId);
            if (owner != null) removeControlItems(owner);
        }
        if (mountId != null) {
            Entity e = server == null ? null : findEntity(server, mountId);
            if (e != null) {
                // Dismount any rider, then discard so an invulnerable persistent mob isn't left behind.
                try { for (Entity p : e.getPassengerList()) p.stopRiding(); } catch (Throwable ignored) {}
                e.discard();
            }
        }
        // Also discard any stray entity tagged for this owner (defensive).
        if (server != null) {
            String ownerTag = TAG_OWNER_PREFIX + ownerId;
            for (ServerWorld w : server.getWorlds()) {
                for (Entity e : w.iterateEntities()) {
                    if (e.getCommandTags().contains(TAG_MOUNT) && e.getCommandTags().contains(ownerTag)) {
                        try { for (Entity p : e.getPassengerList()) p.stopRiding(); } catch (Throwable ignored) {}
                        e.discard();
                    }
                }
            }
        }
    }

    private static void discardMountEntity(UUID mountId, MinecraftServer server) {
        if (mountId == null || server == null) return;
        Entity e = findEntity(server, mountId);
        if (e != null) {
            try { for (Entity p : e.getPassengerList()) p.stopRiding(); } catch (Throwable ignored) {}
            e.discard();
        }
    }

    private static Entity findEntity(MinecraftServer server, UUID entityId) {
        if (server == null || entityId == null) return null;
        for (ServerWorld w : server.getWorlds()) {
            Entity e = w.getEntity(entityId);
            if (e != null && e.isAlive()) return e;
        }
        return null;
    }

    /** Discard any tagged cosmetic mount whose owner is offline or not tracked to it. */
    private static void sweepOrphans(MinecraftServer server) {
        for (ServerWorld w : server.getWorlds()) {
            for (Entity e : w.iterateEntities()) {
                if (!e.getCommandTags().contains(TAG_MOUNT)) continue;
                UUID ownerId = readOwner(e);
                if (ownerId == null) { e.discard(); continue; }
                boolean online = server.getPlayerManager().getPlayer(ownerId) != null;
                UUID tracked = MOUNT_BY_OWNER.get(ownerId);
                boolean isTracked = tracked != null && tracked.equals(e.getUuid());
                if (!online || !isTracked) {
                    try { for (Entity p : e.getPassengerList()) p.stopRiding(); } catch (Throwable ignored) {}
                    e.discard();
                }
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

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static boolean ownsEquipped(UUID ownerId, String cosmeticId) {
        OwnershipManager om = OwnershipManager.getInstance();
        return om != null && cosmeticId != null && om.owns(ownerId, cosmeticId);
    }

    /** True when the owner has no living, in-world pawn (dead, awaiting respawn, or spectating). */
    private static boolean isOwnerDead(ServerPlayerEntity owner) {
        return owner.isDead() || !owner.isAlive() || owner.getHealth() <= 0f || owner.isSpectator();
    }

    private static void tell(ServerPlayerEntity player, String msg, Formatting color) {
        try {
            player.sendMessage(Text.literal(msg).formatted(color), true);
        } catch (Throwable ignored) {}
    }
}
