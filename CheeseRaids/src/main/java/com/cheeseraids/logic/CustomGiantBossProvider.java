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

import java.util.UUID;

/**
 * v1 boss: a single uncatchable (optionally enlarged) Pokémon summoned on the central platform,
 * scaled in level by participant count. Spawned directly via {@link CobblemonSpawnHook} (no command,
 * no capture window) so we get the entity handle immediately and a logged failure if it's refused.
 * Defeat = the boss entity dies/disappears (winning the Cobblemon battle removes the wild mon).
 */
public class CustomGiantBossProvider implements BossProvider {

    private String tag(RaidDefinition def) { return "cheeseraids_boss_" + def.id; }
    private String riftId(RaidDefinition def) { return "cheeseguard:" + def.arena.riftDimId; }

    @Override
    public void spawnBoss(MinecraftServer server, RaidDefinition def, RaidInstance inst, int participants) {
        ServerWorld w = Mc.world(server, riftId(def));
        if (w == null) {
            CheeseRaidsMod.LOGGER.error("[CheeseRaids] Boss rift world {} not loaded", riftId(def));
            return;
        }
        BlockPos c = def.arena.center.blockPos();
        // Guarantee a clear air pocket above the disk so the (giant) boss can't spawn inside a block
        // and instantly suffocate ("mort instantanément"). A 1.5× Hoopa-Unbound is ~9-10 blocks tall, so
        // a fixed 5×5×6 box was too small — size the pocket from the actual scale (floor block kept intact).
        int rad = (int) Math.ceil(2 + def.boss.baseScale * 2.0);                   // footprint half-width
        int sh  = (int) Math.ceil(6 * Math.max(1.0, def.boss.baseScale)) + 3;       // vertical headroom
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++)
                for (int dy = 1; dy <= sh; dy++)
                    w.setBlockState(c.add(dx, dy, dz), net.minecraft.block.Blocks.AIR.getDefaultState(),
                        net.minecraft.block.Block.NOTIFY_LISTENERS);

        int level = def.boss.baseLevel + Math.max(0, participants - 1) * def.boss.levelPerExtraPlayer;
        String props = CobblemonSpawnHook.buildProps(def.boss.species, level, def.boss.aspects, true);

        Entity e = CobblemonSpawnHook.spawn(w, c.getX() + 0.5, c.getY() + 2, c.getZ() + 0.5,
            props, tag(def), (float) def.boss.baseScale, true); // giant + persistent, 2 blocks above the floor
        inst.bossEntityUuid = (e != null) ? e.getUuidAsString() : null;
        inst.bossCaptureUntil = 0;
        inst.bossSpawned = true;
        inst.bossSpawnTick = server.getTicks();

        if (e == null) {
            CheeseRaidsMod.LOGGER.error("[CheeseRaids] Boss spawn FAILED for {} — see preceding log.", def.id);
            return;
        }
        CheeseRaidsMod.LOGGER.info("[CheeseRaids] Boss spawned for {} (level {}, scale {}, {} participant(s)): {}",
            def.id, level, def.boss.baseScale, participants, e.getUuidAsString());
    }

    @Override
    public void tickEffects(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        if (inst.bossEntityUuid == null) return;
        ServerWorld w = Mc.world(server, riftId(def));
        if (w == null) return;
        try {
            Entity e = w.getEntity(UUID.fromString(inst.bossEntityUuid));
            if (e == null || !e.isAlive()) return;
            double cx = e.getX(), cy = e.getY(), cz = e.getZ();
            // swirling dark aura + rising soul flames
            double t = (server.getTicks() % 40) / 40.0 * Math.PI * 2;
            for (int i = 0; i < 3; i++) {
                double a = t + i * (Math.PI * 2 / 3), rr = 1.8 + e.getWidth();
                w.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME,
                    cx + Math.cos(a) * rr, cy + 0.5, cz + Math.sin(a) * rr, 1, 0.0, 0.0, 0.0, 0.0);
            }
            w.spawnParticles(net.minecraft.particle.ParticleTypes.WITCH,
                cx, cy + e.getHeight() * 0.6, cz, 4, 0.6, 0.6, 0.6, 0.02);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean isCapturing(RaidInstance inst) {
        return false; // direct spawn — captured immediately
    }

    @Override
    public void captureTick(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        // no-op: the boss entity handle is recorded at spawn time
    }

    @Override
    public void combatTick(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        if (inst.bossEntityUuid == null) return;
        ServerWorld w = Mc.world(server, riftId(def));
        if (w == null) return;
        try {
            Entity e = w.getEntity(UUID.fromString(inst.bossEntityUuid));
            if (e != null) {
                java.util.UUID bid = com.cheeseraids.hooks.CobblemonBattleHook.getBattleId(e);
                if (bid != null) inst.bossBattleId = bid.toString(); // remember the battle so we can wait for it
                return;
            }
            // Boss entity gone but NO battle ever started AND it just spawned → it vanished pre-fight
            // (suffocation/void). Respawn it (bounded) instead of declaring a false victory.
            if (inst.bossBattleId == null && inst.bossRespawns < 2
                    && server.getTicks() - inst.bossSpawnTick < 200) {
                inst.bossRespawns++;
                CheeseRaidsMod.LOGGER.warn("[CheeseRaids] Boss for {} vanished pre-battle — respawning ({}/2).", def.id, inst.bossRespawns);
                spawnBoss(server, def, inst, inst.participantCount());
            }
        } catch (Exception ignored) {}
    }

    @Override
    public boolean isDefeated(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        if (!inst.bossSpawned) return false;
        // Spawn failed → never declare victory; the COMBAT timeout in RaidManager handles it.
        if (inst.bossEntityUuid == null) return false;
        ServerWorld w = Mc.world(server, riftId(def));
        if (w == null) return false;
        boolean entityGone;
        try {
            Entity e = w.getEntity(UUID.fromString(inst.bossEntityUuid));
            entityGone = (e == null || !e.isAlive());
        } catch (Exception ex) {
            entityGone = true;
        }
        if (!entityGone) return false;
        // Instant vanish with no battle = suffocation/void on spawn → NOT a win (combatTick respawns it).
        // Surviving >5s then gone = a legit KO (fallback even if battle-id capture failed).
        int age = Math.max(0, server.getTicks() - inst.bossSpawnTick); // clamp (getTicks resets on restart)
        if (inst.bossBattleId == null && age < 100) return false;
        // If a battle was tracked, wait until it has fully ended (entity is removed ~2s AFTER battle
        // end; teleporting mid-wrap-up is what the user saw before).
        if (inst.bossBattleId != null) {
            try {
                if (com.cheeseraids.hooks.CobblemonBattleHook.battleActive(UUID.fromString(inst.bossBattleId)))
                    return false;
            } catch (Exception ignored) {}
        }
        return true;
    }

    @Override
    public void cleanup(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        ServerWorld w = Mc.world(server, riftId(def));
        if (w != null && inst.bossEntityUuid != null) {
            try {
                Entity e = w.getEntity(UUID.fromString(inst.bossEntityUuid));
                if (e != null) e.discard();
            } catch (Exception ignored) {}
        }
        inst.bossEntityUuid = null;
        inst.bossSpawned = false;
    }
}
