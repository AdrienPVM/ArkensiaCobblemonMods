package com.cheeseraids.logic;

import com.cheeseraids.data.PlayerRaidState;
import com.cheeseraids.data.RaidDefinition;
import com.cheeseraids.data.RaidInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tick parkour supervision for players inside the rift: fall → respawn at last checkpoint,
 * capture crossed checkpoints, detect arrival at the central platform.
 */
public class CheckpointTracker {

    public void tick(MinecraftServer server, RaidDefinition def, RaidInstance inst, StarArenaGenerator gen) {
        String riftId = "cheeseguard:" + def.arena.riftDimId;
        ServerWorld rift = com.cheeseraids.util.Mc.world(server, riftId);
        if (rift == null) return;
        BlockPos center = def.arena.center.blockPos();

        for (PlayerRaidState ps : inst.players.values()) {
            if (!ps.entered || ps.arrived || ps.finished) continue;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(UUID.fromString(ps.uuid));
            if (p == null) continue;
            if (!riftId.equals(p.getServerWorld().getRegistryKey().getValue().toString())) continue;

            // Fall → respawn at last checkpoint (or spawn platform)
            if (p.getY() < center.getY() - def.arena.fallBelow) {
                respawn(p, rift, def, ps, gen);
                continue;
            }

            // Capture crossed checkpoint (+ notify once per new checkpoint)
            List<BlockPos> cps = gen.checkpoints(def, ps.slot, ps.seed);
            for (BlockPos cp : cps) {
                if (near(p, cp, 2.2, 2.0)) {
                    int[] now = {cp.getX(), cp.getY() + 1, cp.getZ()};
                    boolean isNew = ps.lastCheckpoint == null
                        || ps.lastCheckpoint[0] != now[0] || ps.lastCheckpoint[2] != now[2];
                    ps.lastCheckpoint = now;
                    if (isNew) {
                        p.sendMessage(net.minecraft.text.Text.literal("§b✔ Checkpoint atteint !"), true); // actionbar
                    }
                }
            }

            // Arrival = actually standing on the central boss disk (strict — NOT the last platform)
            if (horiz(p, center) <= Math.max(1.0, def.arena.centerRadius - 0.5)
                && Math.abs(p.getY() - (center.getY() + 1)) <= 1.5) {
                ps.arrived = true;
                p.sendMessage(net.minecraft.text.Text.literal("§5Vous avez atteint le cœur de la faille !"), false);
            }
        }
    }

    private void respawn(ServerPlayerEntity p, ServerWorld rift, RaidDefinition def,
                         PlayerRaidState ps, StarArenaGenerator gen) {
        BlockPos target;
        if (ps.lastCheckpoint != null) {
            target = new BlockPos(ps.lastCheckpoint[0], ps.lastCheckpoint[1], ps.lastCheckpoint[2]);
        } else {
            target = gen.spawnPlatform(def, ps.slot, ps.seed).up();
        }
        p.networkHandler.requestTeleport(target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
            p.getYaw(), p.getPitch());
        p.setVelocity(0, 0, 0);
        p.fallDistance = 0;
    }

    private boolean near(ServerPlayerEntity p, BlockPos pos, double h, double v) {
        return horiz(p, pos) <= h && Math.abs(p.getY() - (pos.getY() + 1)) <= v;
    }

    private double horiz(ServerPlayerEntity p, BlockPos pos) {
        double dx = p.getX() - (pos.getX() + 0.5);
        double dz = p.getZ() - (pos.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }
}
