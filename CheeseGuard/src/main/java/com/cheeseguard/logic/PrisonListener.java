package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.PrisonEntry;
import com.cheeseguard.data.Region;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.TypedActionResult;

import java.util.Set;

/**
 * Enforces prison confinement: TPs prisoners back into the prison zone,
 * blocks block/entity/item interactions, and handles login/expiry transitions.
 *
 * Action blocks rely on Fabric callbacks. Item drops + commands are handled
 * by dedicated mixins (PrisonItemDropMixin, PrisonCommandMixin).
 */
public class PrisonListener {

    private int tickCounter = 0;

    public void register() {

        // ── Block break ────────────────────────────────────────────────────
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> {
            if (!(player instanceof ServerPlayerEntity sp)) return true;
            if (isPrisoner(sp)) {
                notifyDenied(sp);
                return false;
            }
            return true;
        });

        // ── Block / item interact ──────────────────────────────────────────
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayerEntity sp && isPrisoner(sp)) {
                notifyDenied(sp);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayerEntity sp && isPrisoner(sp)) {
                notifyDenied(sp);
                return TypedActionResult.fail(player.getStackInHand(hand));
            }
            return TypedActionResult.pass(player.getStackInHand(hand));
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity sp && isPrisoner(sp)) {
                notifyDenied(sp);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayerEntity sp && isPrisoner(sp)) {
                notifyDenied(sp);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // ── Player join: re-imprison or release-on-expiry ──────────────────
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
            if (pm == null) return;
            String uuid = p.getUuid().toString();
            PrisonEntry e = pm.get(uuid);
            if (e == null) return;

            long now = System.currentTimeMillis();
            if (e.hasExpired(now)) {
                releaseAndTpToSpawn(server, p, PrisonEntry.RELEASE_EXPIRED, null, null);
                return;
            }

            // Update the stored name in case it changed.
            if (!p.getName().getString().equals(e.getPlayerName())) {
                e.setPlayerName(p.getName().getString());
                pm.save();
            }

            // TP into prison zone + send welcome message after 1 tick (let join finish).
            server.execute(() -> {
                tpToPrisonCenter(server, p);
                sendWelcomeMessage(p, e, pm.getWelcomeMessage());
            });
        });

        // ── Tick: enforce confinement + auto-release expired ───────────────
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
    }

    private void onTick(MinecraftServer server) {
        tickCounter++;
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null) return;

        // Every 1s: process expired sentences (release any whose time is up).
        if (tickCounter % 20 == 0) {
            for (PrisonEntry expired : pm.tickExpiry()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(
                    java.util.UUID.fromString(expired.getPlayerUuid()));
                if (p != null) {
                    tpToReleasePoint(server, p);
                    p.sendMessage(Text.translatable("cheeseguard.prison.released_expired")
                        .formatted(Formatting.GREEN), false);
                }
                broadcastPrisonEvent("prison_released", expired);
            }
        }

        // Every 0.5s: containment check.
        if (tickCounter % 10 == 0) {
            String zoneId = pm.getPrisonZoneId();
            if (zoneId == null) return;
            Region zone = CheeseGuardMod.getInstance().getStorage().getRegion(zoneId);
            if (zone == null) {
                if (!pm.getActive().isEmpty() && tickCounter % 600 == 0) {
                    CheeseGuardMod.LOGGER.warn(
                        "[CG/Prison] Configured prison zone '{}' no longer exists; {} prisoner(s) are unconstrained.",
                        zoneId, pm.getActive().size());
                }
                return;
            }

            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (!pm.isImprisoned(p.getUuid().toString())) continue;
                String worldId = p.getServerWorld().getRegistryKey().getValue().toString();
                boolean inside = worldId.equals(zone.getWorld())
                    && zone.contains(worldId, p.getBlockPos());
                if (!inside) {
                    tpToZone(server, p, zone);
                    p.sendMessage(Text.translatable("cheeseguard.prison.confined")
                        .formatted(Formatting.RED), true);
                }
            }
        }
    }

    // ── TP helpers ───────────────────────────────────────────────────────────

    private static void tpToPrisonCenter(MinecraftServer server, ServerPlayerEntity p) {
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null) return;
        String zoneId = pm.getPrisonZoneId();
        if (zoneId == null) return;
        Region zone = CheeseGuardMod.getInstance().getStorage().getRegion(zoneId);
        if (zone == null) return;
        tpToZone(server, p, zone);
    }

    private static void tpToZone(MinecraftServer server, ServerPlayerEntity p, Region zone) {
        ServerWorld targetWorld = null;
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(zone.getWorld())) {
                targetWorld = w; break;
            }
        }
        if (targetWorld == null) return;
        double x = zone.getCenterX() + 0.5;
        double y = zone.getCenterY();
        double z = zone.getCenterZ() + 0.5;
        // Pick the highest non-air spot inside the zone column for a safer landing.
        int top = targetWorld.getTopY();
        for (int yy = Math.min(zone.getMaxY(), top - 1); yy >= zone.getMinY(); yy--) {
            net.minecraft.util.math.BlockPos bp = new net.minecraft.util.math.BlockPos(zone.getCenterX(), yy, zone.getCenterZ());
            if (!targetWorld.getBlockState(bp).isAir()
                    && targetWorld.getBlockState(bp.up()).isAir()
                    && targetWorld.getBlockState(bp.up(2)).isAir()) {
                y = yy + 1;
                break;
            }
        }
        p.teleport(targetWorld, x, y, z, Set.of(), p.getYaw(), p.getPitch());
        p.fallDistance = 0;
    }

    /**
     * TP the released player to the configured release point, or to the overworld
     * spawn as a fallback. The release point captures world + coords + facing.
     */
    private static void tpToReleasePoint(MinecraftServer server, ServerPlayerEntity p) {
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm != null && pm.hasReleasePoint()) {
            ServerWorld target = null;
            for (ServerWorld w : server.getWorlds()) {
                if (w.getRegistryKey().getValue().toString().equals(pm.getReleaseWorld())) {
                    target = w; break;
                }
            }
            if (target != null) {
                float yaw   = pm.getReleaseYaw()   != null ? pm.getReleaseYaw()   : p.getYaw();
                float pitch = pm.getReleasePitch() != null ? pm.getReleasePitch() : p.getPitch();
                p.teleport(target, pm.getReleaseX(), pm.getReleaseY(), pm.getReleaseZ(),
                    Set.of(), yaw, pitch);
                p.fallDistance = 0;
                return;
            }
            CheeseGuardMod.LOGGER.warn(
                "[CG/Prison] Configured release world '{}' not loaded; falling back to overworld spawn.",
                pm.getReleaseWorld());
        }
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) return;
        var spawn = overworld.getSpawnPos();
        p.teleport(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
            Set.of(), p.getYaw(), p.getPitch());
        p.fallDistance = 0;
    }

    /** Release the entry in the manager, TP to spawn, and broadcast. */
    public static void releaseAndTpToSpawn(MinecraftServer server, ServerPlayerEntity p,
                                            String reason, String byUuid, String byName) {
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null) return;
        PrisonEntry released = pm.release(p.getUuid().toString(), reason, byUuid, byName);
        if (released != null) {
            tpToReleasePoint(server, p);
            String key = PrisonEntry.RELEASE_EXPIRED.equals(reason)
                ? "cheeseguard.prison.released_expired"
                : "cheeseguard.prison.released_pardoned";
            p.sendMessage(Text.translatable(key).formatted(Formatting.GREEN), false);
            broadcastPrisonEvent("prison_released", released);
        }
    }

    private static void sendWelcomeMessage(ServerPlayerEntity p, PrisonEntry e, String template) {
        long remaining = e.remainingMs(System.currentTimeMillis());
        String msg = template
            .replace("{reason}", e.getReason() == null ? "" : e.getReason())
            .replace("{time}",   PrisonManager.formatDuration(remaining));
        for (String line : msg.split("\n")) {
            p.sendMessage(Text.literal(line), false);
        }
    }

    private static boolean isPrisoner(ServerPlayerEntity sp) {
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        return pm != null && pm.isImprisoned(sp.getUuid().toString());
    }

    private static void notifyDenied(ServerPlayerEntity sp) {
        sp.sendMessage(Text.translatable("cheeseguard.prison.action_denied")
            .formatted(Formatting.RED), true);
    }

    /**
     * Broadcast a prison_* WS event to dashboards AND publish an in-game chat
     * announcement to every online player. The WS payload is enriched with
     * top-level fields so the Discord webhook layer can extract them without
     * parsing the nested {@code entry} object.
     */
    public static void broadcastPrisonEvent(String type, PrisonEntry e) {
        // 1) WS broadcast (dashboard + Discord auto-route)
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("type", type);
            o.add("entry", e.toJson());
            // Top-level convenience fields for DiscordWebhookManager.extractBroadcastData.
            if (e.getPlayerName() != null)   o.addProperty("playerName",  e.getPlayerName());
            if (e.getReason() != null)       o.addProperty("reason",      e.getReason());
            o.addProperty("durationMs", e.getDurationMs());
            o.addProperty("duration",   PrisonManager.formatDuration(e.getDurationMs()));
            if (e.getIssuedByName() != null) o.addProperty("byName",      e.getIssuedByName());
            if (e.getReleaseReason() != null)o.addProperty("releaseReason", e.getReleaseReason());
            if (e.getReleasedByName() != null)o.addProperty("releasedByName", e.getReleasedByName());
            com.cheesedash.DashboardRegistry.broadcast("prison", o.toString());
        } catch (Throwable ignored) {}

        // 2) In-game public chat broadcast.
        // We skip prison_updated (technical event from dashboard duration adjustments —
        // not interesting for players in chat).
        try {
            MinecraftServer server = CheeseGuardMod.getInstance() != null
                ? CheeseGuardMod.getInstance().getServer() : null;
            if (server == null) return;
            Text msg;
            switch (type) {
                case "prison_added" -> msg = Text.translatable(
                        "cheeseguard.prison.broadcast.imprisoned",
                        e.getPlayerName(),
                        e.getIssuedByName() != null ? e.getIssuedByName() : "Console",
                        PrisonManager.formatDuration(e.getDurationMs()),
                        e.getReason() != null ? e.getReason() : "—"
                    ).formatted(Formatting.RED);
                case "prison_released" -> {
                    String releaseKey = PrisonEntry.RELEASE_EXPIRED.equals(e.getReleaseReason())
                        ? "cheeseguard.prison.broadcast.released_expired"
                        : "cheeseguard.prison.broadcast.released_pardoned";
                    msg = Text.translatable(releaseKey, e.getPlayerName())
                        .formatted(Formatting.GREEN);
                }
                default -> { return; }
            }
            final Text finalMsg = msg;
            server.execute(() -> server.getPlayerManager().broadcast(finalMsg, false));
        } catch (Throwable ignored) {}
    }
}
