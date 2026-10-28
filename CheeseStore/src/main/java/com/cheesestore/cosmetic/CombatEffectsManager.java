package com.cheesestore.cosmetic;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Plays a player's equipped {@code KILL_EFFECT} / {@code DEATH_EFFECT} cosmetic.
 *
 * <p>Push-driven from {@link CosmeticCombatBridge} (FactionWar calls
 * {@code onKill}/{@code onDeath} reflectively when it resolves PvP kills). As a fallback for
 * vanilla deaths that FactionWar does <em>not</em> intercept, {@link #init()} also registers
 * {@link ServerLivingEntityEvents#AFTER_DEATH} so a player's DEATH_EFFECT still fires.
 *
 * <p>Contract (do not change signatures — referenced by {@link CosmeticCombatBridge} and
 * {@link CosmeticsModule}):
 * <pre>
 *   public static void init()
 *   public static void handleKill(ServerPlayerEntity killer, ServerPlayerEntity victim)
 *   public static void handleDeath(ServerPlayerEntity victim)
 * </pre>
 *
 * <p>Everything here is null-safe and never throws into the caller: a missing/unknown/disabled
 * cosmetic is a silent no-op, and any unexpected error is caught and logged at debug level so a
 * cosmetic flourish can never break a kill, a death, or the FactionWar event loop.
 */
public final class CombatEffectsManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");

    private static final String CAT_KILL = "KILL_EFFECT";
    private static final String CAT_DEATH = "DEATH_EFFECT";

    /**
     * De-dupe window (ms): if {@link #handleDeath} already fired for a victim (e.g. via the
     * FactionWar bridge), the AFTER_DEATH fallback for the same death is suppressed.
     */
    private static final long DEATH_DEDUPE_MS = 500L;

    /** entityId -> last time we played that player's DEATH_EFFECT. Cleaned opportunistically. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> RECENT_DEATHS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile boolean initialized = false;

    private CombatEffectsManager() {}

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Idempotent. Registers the AFTER_DEATH fallback for vanilla (non-FactionWar) deaths. */
    public static void init() {
        if (initialized) return;
        initialized = true;
        try {
            ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
                if (entity instanceof ServerPlayerEntity victim) {
                    // Fallback only: if the FactionWar bridge already played the effect for this
                    // exact death, the de-dupe guard inside handleDeath suppresses a second one.
                    handleDeath(victim);
                }
            });
            LOGGER.info("[CheeseStore] CombatEffectsManager ready (KILL/DEATH effects, AFTER_DEATH fallback)");
        } catch (Throwable t) {
            LOGGER.warn("[CheeseStore] CombatEffectsManager.init() failed to register AFTER_DEATH: {}", t.toString());
        }
    }

    // ── Public API (called via CosmeticCombatBridge) ──────────────────────────

    /** Play the killer's equipped KILL_EFFECT at the victim's position. */
    public static void handleKill(ServerPlayerEntity killer, ServerPlayerEntity victim) {
        try {
            if (killer == null || victim == null) return;
            CosmeticEntry entry = resolveEquipped(killer, CAT_KILL);
            if (entry == null) return;
            ServerWorld world = victim.getServerWorld();
            if (world == null) return;
            playEffect(world, victim.getX(), victim.getY(), victim.getZ(), entry);
        } catch (Throwable t) {
            LOGGER.debug("[CheeseStore] handleKill error: {}", t.toString());
        }
    }

    /** Play the victim's equipped DEATH_EFFECT at their death position. */
    public static void handleDeath(ServerPlayerEntity victim) {
        try {
            if (victim == null) return;
            if (!markDeathHandled(victim)) return; // de-duped: already played for this death
            CosmeticEntry entry = resolveEquipped(victim, CAT_DEATH);
            if (entry == null) return;
            ServerWorld world = victim.getServerWorld();
            if (world == null) return;
            playEffect(world, victim.getX(), victim.getY(), victim.getZ(), entry);
        } catch (Throwable t) {
            LOGGER.debug("[CheeseStore] handleDeath error: {}", t.toString());
        }
    }

    // ── Equipped-cosmetic resolution ──────────────────────────────────────────

    /** The player's equipped, enabled cosmetic in {@code category}, or {@code null}. */
    private static CosmeticEntry resolveEquipped(ServerPlayerEntity player, String category) {
        EquipManager equip = EquipManager.getInstance();
        CosmeticManager catalog = CosmeticManager.getInstance();
        if (equip == null || catalog == null) return null;
        String id = equip.getEquipped(player.getUuid(), category);
        if (id == null || id.isEmpty()) return null;
        CosmeticEntry entry = catalog.get(id);
        if (entry == null || !entry.isEnabled()) return null;
        // Defensive: only honour an entry that actually belongs to this category.
        if (!category.equalsIgnoreCase(entry.getCategory())) return null;
        return entry;
    }

    /**
     * Returns {@code true} if this is the first time we've handled {@code victim}'s death within
     * the de-dupe window (so the caller should play the effect); {@code false} if a duplicate.
     */
    private static boolean markDeathHandled(ServerPlayerEntity victim) {
        long now = System.currentTimeMillis();
        int id = victim.getId();
        Long prev = RECENT_DEATHS.put(id, now);
        // Opportunistic cleanup so the map can't grow unbounded over a long session.
        if (RECENT_DEATHS.size() > 256) {
            RECENT_DEATHS.entrySet().removeIf(e -> now - e.getValue() > DEATH_DEDUPE_MS);
        }
        return prev == null || (now - prev) > DEATH_DEDUPE_MS;
    }

    // ── Effect playback ───────────────────────────────────────────────────────

    /**
     * Plays the cosmetic at (x,y,z): the named visual effect (firework/lightning/...) plus,
     * always, any optional {@code params.sound} and {@code params.particle} on top.
     */
    private static void playEffect(ServerWorld world, double x, double y, double z, CosmeticEntry entry) {
        String effect = entry.param("effect", "").trim().toLowerCase(Locale.ROOT);
        switch (effect) {
            case "firework" -> playFirework(world, x, y, z);
            case "lightning" -> playLightning(world, x, y, z);
            case "explosion" -> playExplosion(world, x, y, z);
            case "souls" -> spawnParticleBurst(world, ParticleTypes.SOUL, x, y, z, 30, 0.4, 0.6, 0.4, 0.05);
            case "totem" -> spawnParticleBurst(world, ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 40, 0.4, 0.6, 0.4, 0.3);
            case "blood" -> spawnParticleBurst(world, ParticleTypes.DAMAGE_INDICATOR, x, y, z, 25, 0.4, 0.5, 0.4, 0.1);
            default -> { /* unknown/absent named effect: optional sound/particle below still play */ }
        }

        // Always-on extras from params (independent of the named effect).
        String particleId = entry.param("particle", null);
        if (particleId != null && !particleId.isBlank()) {
            ParticleEffect extra = resolveParticle(particleId);
            if (extra != null) {
                spawnParticleBurst(world, extra, x, y, z, 30, 0.4, 0.6, 0.4, 0.05);
            }
        }

        String soundId = entry.param("sound", null);
        if (soundId != null && !soundId.isBlank()) {
            playSound(world, x, y, z, soundId, 1.0f, 1.0f);
        }
    }

    /** Colourful firework that explodes near-instantly at the position. */
    private static void playFirework(ServerWorld world, double x, double y, double z) {
        try {
            ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
            it.unimi.dsi.fastutil.ints.IntArrayList colors = new it.unimi.dsi.fastutil.ints.IntArrayList();
            colors.add(0xFF5530); // ember orange-red
            colors.add(0xF5C842); // gold
            it.unimi.dsi.fastutil.ints.IntArrayList fade = new it.unimi.dsi.fastutil.ints.IntArrayList();
            fade.add(0xB02E26); // deep red fade
            FireworkExplosionComponent burst = new FireworkExplosionComponent(
                    FireworkExplosionComponent.Type.LARGE_BALL, colors, fade,
                    /*trail=*/true, /*twinkle=*/true);
            // flight=0 → vanilla detonates within a few ticks of spawn.
            stack.set(DataComponentTypes.FIREWORKS,
                    new FireworksComponent((byte) 0, java.util.List.of(burst)));

            FireworkRocketEntity rocket = new FireworkRocketEntity(world, x, y + 0.5, z, stack);
            world.spawnEntity(rocket);
            // Force a fast explosion so it pops at the kill spot rather than flying off.
            rocket.setVelocity(0, 0, 0);
        } catch (Throwable t) {
            LOGGER.debug("[CheeseStore] firework effect failed: {}", t.toString());
            // Fallback so the kill is still visibly marked.
            spawnParticleBurst(world, ParticleTypes.FIREWORK, x, y, z, 30, 0.5, 0.5, 0.5, 0.1);
        }
    }

    /** Purely-cosmetic lightning bolt (no damage, no fire) at the position. */
    private static void playLightning(ServerWorld world, double x, double y, double z) {
        try {
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
            if (bolt == null) {
                spawnParticleBurst(world, ParticleTypes.ELECTRIC_SPARK, x, y, z, 40, 0.4, 1.0, 0.4, 0.2);
                return;
            }
            bolt.refreshPositionAfterTeleport(new Vec3d(x, y, z));
            bolt.setCosmetic(true); // no damage, no block fire — purely visual
            world.spawnEntity(bolt);
        } catch (Throwable t) {
            LOGGER.debug("[CheeseStore] lightning effect failed: {}", t.toString());
            spawnParticleBurst(world, ParticleTypes.ELECTRIC_SPARK, x, y, z, 40, 0.4, 1.0, 0.4, 0.2);
        }
    }

    /** Cosmetic explosion: emitter particles + the generic explosion sound (no block/entity damage). */
    private static void playExplosion(ServerWorld world, double x, double y, double z) {
        spawnParticleBurst(world, ParticleTypes.EXPLOSION_EMITTER, x, y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0);
        spawnParticleBurst(world, ParticleTypes.EXPLOSION, x, y + 0.2, z, 8, 0.5, 0.5, 0.5, 0.05);
        playSound(world, x, y, z, "minecraft:entity.generic.explode", 1.0f, 1.0f);
    }

    // ── Low-level helpers ─────────────────────────────────────────────────────

    /**
     * Broadcast a particle burst to all nearby players. Uses the no-target
     * {@link ServerWorld#spawnParticles(ParticleEffect, double, double, double, int, double, double, double, double)}
     * overload so everyone in view sees the effect (not just one player).
     */
    private static void spawnParticleBurst(ServerWorld world, ParticleEffect particle,
                                           double x, double y, double z, int count,
                                           double dx, double dy, double dz, double speed) {
        try {
            world.spawnParticles(particle, x, y + 1.0, z, count, dx, dy, dz, speed);
        } catch (Throwable t) {
            LOGGER.debug("[CheeseStore] spawnParticles failed: {}", t.toString());
        }
    }

    /** Resolve a vanilla particle id (e.g. {@code "minecraft:soul"}) to a {@link ParticleEffect}, or null. */
    private static ParticleEffect resolveParticle(String id) {
        try {
            Identifier ident = Identifier.tryParse(id.trim());
            if (ident == null) return null;
            ParticleType<?> type = Registries.PARTICLE_TYPE.get(ident);
            // Only simple (parameterless) particle types are safe to use directly as an effect.
            if (type instanceof ParticleEffect effect) {
                return effect;
            }
            return null;
        } catch (Throwable t) {
            LOGGER.debug("[CheeseStore] resolveParticle('{}') failed: {}", id, t.toString());
            return null;
        }
    }

    /** Play a vanilla sound id (e.g. {@code "minecraft:entity.lightning_bolt.thunder"}) at the position. */
    private static void playSound(ServerWorld world, double x, double y, double z,
                                  String soundId, float volume, float pitch) {
        try {
            Identifier ident = Identifier.tryParse(soundId.trim());
            if (ident == null) return;
            SoundEvent sound = SoundEvent.of(ident);
            if (sound == null) return;
            world.playSound(null, x, y, z, sound, SoundCategory.PLAYERS, volume, pitch);
        } catch (Throwable t) {
            LOGGER.debug("[CheeseStore] playSound('{}') failed: {}", soundId, t.toString());
        }
    }
}
