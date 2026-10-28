package com.cheesemusic.logic;

import com.cheesemusic.CheeseMusicConfig;
import com.cheesemusic.CheeseMusicMod;
import com.cheesemusic.MusicAssignment;
import com.cheesemusic.network.MusicPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class ZoneMusicTracker {

    private final CheeseMusicConfig config;
    private final Map<UUID, PlayerMusicState> states = new HashMap<>();
    private int tickCounter = 0;

    public ZoneMusicTracker(CheeseMusicConfig config) {
        this.config = config;
    }

    public void tick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % config.getCheckIntervalTicks() != 0) return;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            try {
                tickPlayer(player);
            } catch (Exception e) {
                CheeseMusicMod.LOGGER.debug("[CheeseMusic] tickPlayer error for {}: {}",
                    player.getName().getString(), e.getMessage());
            }
        }
    }

    private void tickPlayer(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        PlayerMusicState state = states.computeIfAbsent(uuid, k -> new PlayerMusicState());

        // Check Cobblemon battle state (wrapped in try-catch for reflection safety)
        boolean inBattle = false;
        try { inBattle = CobblemonBattleHook.isInBattle(player); }
        catch (Exception ignored) {}

        if (inBattle) {
            if (state.currentZoneId != null && !state.inCobblemonBattle) {
                state.inCobblemonBattle = true;
                sendStop(player, 40);
            }
            return;
        }

        // Was in battle, now out -> force re-evaluation
        if (state.inCobblemonBattle) {
            state.inCobblemonBattle = false;
            state.currentZoneId = null;
        }

        String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
        BlockPos pos = player.getBlockPos();
        String resolvedZoneId = resolveZoneMusic(worldId, pos.getX(), pos.getY(), pos.getZ());

        if (!Objects.equals(resolvedZoneId, state.currentZoneId)) {
            // Zone changed
            if (state.currentZoneId != null) {
                MusicAssignment oldAssignment = config.getAssignment(state.currentZoneId);
                int fadeOut = oldAssignment != null ? oldAssignment.getFadeOutTicks() : config.getDefaultFadeOutTicks();
                sendStop(player, fadeOut);
            }
            if (resolvedZoneId != null) {
                MusicAssignment assignment = config.getAssignment(resolvedZoneId);
                if (assignment != null) {
                    sendPlay(player, assignment);
                }
            }
            state.currentZoneId = resolvedZoneId;
            state.lastSendTime = System.currentTimeMillis();
        }

        state.lastPos = pos;
    }

    private String resolveZoneMusic(String worldId, int x, int y, int z) {
        if (!CheeseGuardHook.isAvailable()) return null;

        List<CheeseGuardHook.ZoneInfo> zones = CheeseGuardHook.getRegionsAt(worldId, x, y, z);
        for (CheeseGuardHook.ZoneInfo zone : zones) {
            if (config.getAssignment(zone.id()) != null) {
                return zone.id();
            }
            for (String tag : zone.tags()) {
                if (config.getAssignment("tag:" + tag) != null) {
                    return "tag:" + tag;
                }
            }
        }
        return null;
    }

    private void sendPlay(ServerPlayerEntity player, MusicAssignment assignment) {
        JsonObject tracksData = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String track : assignment.getTracks()) {
            arr.add("cheesemusic:" + track);
        }
        tracksData.add("tracks", arr);
        tracksData.addProperty("mode", assignment.getMode().name());

        MusicPayload payload = new MusicPayload("play", tracksData.toString(),
            assignment.getVolume(), assignment.getFadeInTicks());
        ServerPlayNetworking.send(player, payload);
    }

    private void sendStop(ServerPlayerEntity player, int fadeTicks) {
        MusicPayload payload = new MusicPayload("stop", null, 0f, fadeTicks);
        ServerPlayNetworking.send(player, payload);
    }

    public void onPlayerJoin(ServerPlayerEntity player) {
        states.remove(player.getUuid());
    }

    public void onPlayerDisconnect(UUID uuid) {
        states.remove(uuid);
    }

    public void clear() {
        states.clear();
    }

    public Map<UUID, String> getActiveZoneMusic() {
        Map<UUID, String> result = new HashMap<>();
        for (var entry : states.entrySet()) {
            if (entry.getValue().currentZoneId != null && !entry.getValue().inCobblemonBattle) {
                result.put(entry.getKey(), entry.getValue().currentZoneId);
            }
        }
        return result;
    }

    private static class PlayerMusicState {
        String currentZoneId;
        boolean inCobblemonBattle;
        BlockPos lastPos;
        long lastSendTime;
    }
}
