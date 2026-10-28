package com.cheeseraids.logic;

import com.cheeseraids.CheeseRaidsMod;
import com.cheeseraids.data.RaidDefinition;
import com.cheeseraids.data.RaidInstance;
import com.cheeseraids.hooks.CobblemonSpawnHook;
import com.cheeseraids.util.Mc;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Overworld invasion wave: spawns uncatchable Pokémon directly via {@link CobblemonSpawnHook} and
 * tracks the live entity UUIDs to know when the wave is fully defeated.
 */
public class WaveManager {

    private String tag(RaidDefinition def) { return "cheeseraids_wave_" + def.id; }

    /**
     * Spawn the wave directly (programmatic Cobblemon spawn) and record the live entity UUIDs.
     * No capture window / snapshot-diff needed: {@link CobblemonSpawnHook#spawn} returns the entity
     * handle and a checked boolean, so a refused spawn is logged instead of silently ignored.
     */
    public void fireWave(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        ServerWorld w = Mc.world(server, def.wave.arena.world);
        if (w == null) {
            StringBuilder worlds = new StringBuilder();
            server.getWorldRegistryKeys().forEach(k -> worlds.append(k.getValue()).append(' '));
            CheeseRaidsMod.LOGGER.error("[CheeseRaids] wave arena world '{}' NOT FOUND — fix wave.arena.world. Available: {}",
                def.wave.arena.world, worlds.toString().trim());
            return;
        }
        BlockPos center = def.wave.arena.blockPos();
        Random rnd = new Random();
        inst.waveEntityUuids.clear();
        inst.waveBefore.clear();
        inst.alphaUuid = null;
        inst.alphaAnnounced = false;
        int spawned = 0, attempted = 0;
        for (RaidDefinition.SpawnEntry se : def.wave.spawns) {
            for (int k = 0; k < Math.max(1, se.count); k++) {
                attempted++;
                double dx = (rnd.nextDouble() - 0.5) * 2 * def.wave.spread;
                double dz = (rnd.nextDouble() - 0.5) * 2 * def.wave.spread;
                String props = CobblemonSpawnHook.buildProps(se.species, se.level, se.aspects, true);
                float scale = se.leader ? 1.8f : 1.0f; // the alpha leader is bigger
                Entity e = CobblemonSpawnHook.spawn(w,
                    center.getX() + 0.5 + dx, center.getY() + 1.0, center.getZ() + 0.5 + dz,
                    props, tag(def), scale, true); // persistent → no fast natural despawn
                if (e != null) {
                    inst.waveEntityUuids.add(e.getUuidAsString());
                    if (se.leader && inst.alphaUuid == null) {
                        inst.alphaUuid = e.getUuidAsString();
                        try { e.setGlowing(true); } catch (Throwable ignored) {} // alpha stands out
                    }
                    spawned++;
                }
            }
        }
        inst.waveTotal = inst.waveEntityUuids.size();
        inst.waveCaptureUntil = 0; // entities captured at spawn — no window
        CheeseRaidsMod.LOGGER.info("[CheeseRaids] Wave for {}: spawned {}/{} mob(s) directly.", def.id, spawned, attempted);
    }

    public boolean isCapturing(RaidInstance inst) {
        return System.currentTimeMillis() < inst.waveCaptureUntil; // always false now (captureUntil=0)
    }

    /** No-op: entities are captured at spawn time. Kept for state-machine compatibility. */
    public void captureTick(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        inst.waveTotal = inst.waveEntityUuids.size();
    }

    /** True once every captured wave mob is dead/removed. */
    public boolean isCleared(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        ServerWorld w = Mc.world(server, def.wave.arena.world);
        if (w == null) return true;
        for (String su : inst.waveEntityUuids) {
            try {
                Entity e = w.getEntity(UUID.fromString(su));
                if (e != null && e.isAlive()) return false;
            } catch (Exception ignored) {}
        }
        return true;
    }

    public int aliveCount(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        ServerWorld w = Mc.world(server, def.wave.arena.world);
        if (w == null) return 0;
        int n = 0;
        for (String su : inst.waveEntityUuids) {
            try {
                Entity e = w.getEntity(UUID.fromString(su));
                if (e != null && e.isAlive()) n++;
            } catch (Exception ignored) {}
        }
        return n;
    }

    /** Draw a marker particle aura around each living wave mob — bolder around the alpha leader. */
    public void tickEffects(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        ServerWorld w = Mc.world(server, def.wave.arena.world);
        if (w == null) return;
        for (String su : inst.waveEntityUuids) {
            try {
                Entity e = w.getEntity(UUID.fromString(su));
                if (e == null || !e.isAlive()) continue;
                boolean alpha = su.equals(inst.alphaUuid);
                if (alpha) {
                    // bold dimensional aura: swirling portal ring + soul flames + a crown of end rods
                    double t = (server.getTicks() % 30) / 30.0 * Math.PI * 2;
                    for (int i = 0; i < 4; i++) {
                        double a = t + i * (Math.PI / 2), rr = 1.2 + e.getWidth();
                        w.spawnParticles(net.minecraft.particle.ParticleTypes.PORTAL,
                            e.getX() + Math.cos(a) * rr, e.getY() + 0.4, e.getZ() + Math.sin(a) * rr, 1, 0, 0, 0, 0);
                    }
                    w.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME,
                        e.getX(), e.getY() + e.getHeight() * 0.6, e.getZ(), 4, 0.4, 0.4, 0.4, 0.01);
                    w.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                        e.getX(), e.getY() + e.getHeight() + 0.5, e.getZ(), 5, 0.3, 0.1, 0.3, 0.02);
                } else {
                    w.spawnParticles(net.minecraft.particle.ParticleTypes.WITCH,
                        e.getX(), e.getY() + e.getHeight() + 0.4, e.getZ(), 6, 0.35, 0.3, 0.35, 0.01);
                    w.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                        e.getX(), e.getY() + 0.2, e.getZ(), 3, 0.3, 0.1, 0.3, 0.01);
                }
            } catch (Exception ignored) {}
        }
    }

    /** True exactly once, when the tracked alpha leader has just died/disappeared. */
    public boolean alphaJustDefeated(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        if (inst.alphaUuid == null || inst.alphaAnnounced) return false;
        ServerWorld w = Mc.world(server, def.wave.arena.world);
        if (w == null) return false;
        Entity e;
        try { e = w.getEntity(UUID.fromString(inst.alphaUuid)); } catch (Exception ex) { e = null; }
        if (e == null || !e.isAlive()) {
            inst.alphaAnnounced = true;
            return true;
        }
        return false;
    }

    public void cleanup(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        ServerWorld w = Mc.world(server, def.wave.arena.world);
        if (w != null) {
            for (String su : inst.waveEntityUuids) {
                try {
                    Entity e = w.getEntity(UUID.fromString(su));
                    if (e != null) e.discard();
                } catch (Exception ignored) {}
            }
        }
        inst.waveEntityUuids.clear();
    }
}
