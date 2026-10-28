package com.sebastian.cobblemondungeons.util;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.CobblemonNetwork;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.net.messages.client.storage.party.SetPartyReferencePacket;
import com.sebastian.cobblemondungeons.networking.BungeePayload;
import com.sebastian.cobblemondungeons.networking.ProxyForwardPayload;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PartySyncHandler {
    // Proxy plugin messaging channel used with Velocity / FabricProxy-Lite Forward messages.
    // This avoids SQL desync by keeping party data in-memory while the player is online.
    public static final String PARTY_SYNC_CHANNEL = "cobblemon_dungeons:party_sync";
    public static final String PARTY_DEBUG_CHANNEL = "cobblemon_dungeons:party_debug";

    private static final Map<UUID, NbtCompound> pendingPartySyncs = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> pendingPartyDelays = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> pendingPartyResends = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> pendingPartyLateResends = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastSendAt = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastReceiveAt = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastApplyAt = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> lastSendBytes = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> lastReceiveBytes = new ConcurrentHashMap<>();
    private static final int APPLY_DELAY_TICKS = 3;
    private static final int RESEND_DELAY_TICKS = 6;
    private static final int LATE_RESEND_DELAY_TICKS = 40;
    private static volatile boolean debugEnabled = false;

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static void dumpParty(ServerPlayerEntity player, String reason) {
        dumpParty(player, reason, false);
    }

    public static void dumpPartyToChat(ServerPlayerEntity player, String reason) {
        dumpParty(player, reason, true);
    }

    public static void dumpStatusToChat(ServerPlayerEntity player, String reason) {
        if (!debugEnabled || player == null) return;
        UUID uuid = player.getUuid();
        long now = System.currentTimeMillis();
        emitDebugLine(player, "Status (" + reason + ") for " + player.getName().getString(), true);
        emitDebugLine(player, "  pending=" + pendingPartySyncs.containsKey(uuid) + " delay=" + pendingPartyDelays.get(uuid), true);
        emitDebugLine(player, "  lastSend=" + formatAge(now, lastSendAt.get(uuid)) + " bytes=" + lastSendBytes.get(uuid), true);
        emitDebugLine(player, "  lastReceive=" + formatAge(now, lastReceiveAt.get(uuid)) + " bytes=" + lastReceiveBytes.get(uuid), true);
        emitDebugLine(player, "  lastApply=" + formatAge(now, lastApplyAt.get(uuid)), true);
    }

    private static void dumpParty(ServerPlayerEntity player, String reason, boolean toChat) {
        if (!debugEnabled || player == null) return;
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        int size = party.size();
        int occupied = party.occupied();
        Integer delay = pendingPartyDelays.get(player.getUuid());
        boolean hasPending = pendingPartySyncs.containsKey(player.getUuid());
        emitDebugLine(player, "Dump party (" + reason + ") for " + player.getName().getString() + " size=" + size + " occupied=" + occupied + " pending=" + hasPending + " delay=" + delay, toChat);
        for (int i = 0; i < size; i++) {
            com.cobblemon.mod.common.pokemon.Pokemon pokemon = party.get(i);
            if (pokemon == null) {
                emitDebugLine(player, "  slot " + i + ": empty", toChat);
                continue;
            }
            com.cobblemon.mod.common.api.storage.StoreCoordinates<?> coords = pokemon.getStoreCoordinates().get();
            String coordText = coords != null ? coords.toString() : "null";
            emitDebugLine(player, "  slot " + i + ": " + pokemon.getSpecies().getName() + " uuid=" + pokemon.getUuid() + " coords=" + coordText, toChat);
        }
    }

    private static void debugLog(String message) {
        if (!debugEnabled) return;
        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("[PartySyncDebug] " + message);
    }

    private static void emitDebugLine(ServerPlayerEntity player, String message, boolean toChat) {
        debugLog(message);
        if (toChat && player != null) {
            player.sendMessage(Text.literal("[PartySyncDebug] " + message));
        }
    }

    private static String formatAge(long now, Long timestamp) {
        if (timestamp == null) return "never";
        long diff = Math.max(0L, now - timestamp);
        return diff + "ms";
    }

    // Serialize the player's party to an NbtCompound (CompoundTag).
    public static NbtCompound serializeParty(ServerPlayerEntity player) {
        if (player == null) return null;
        try {
            PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
            NbtCompound partyNbt = new NbtCompound();
            party.saveToNBT(partyNbt, player.getServer().getRegistryManager());
            debugLog("Serialized party for " + player.getName().getString() + " keys=" + partyNbt.getKeys().size() + " occupied=" + party.occupied());
            return partyNbt;
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to serialize party for " + player.getName().getString(), e);
            return null;
        }
    }

    // Deserialize party NBT into the existing party store (do not replace the store).
    public static void deserializeParty(ServerPlayerEntity player, NbtCompound partyNbt) {
        if (player == null || partyNbt == null) return;
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        NbtCompound backup = new NbtCompound();
        try {
            party.saveToNBT(backup, player.getServer().getRegistryManager());
        } catch (Exception ignored) {
        }

        try {
        // Clear before loading so the same store instance is mutated cleanly.
        party.clearParty();
        party.loadFromNBT(partyNbt, player.getServer().getRegistryManager());
        party.initialize();
        PlayerSyncHandler.ensurePartyStoreCoordinates(party);
        PlayerSyncHandler.ensurePartyObserver(party, player);
        party.sendTo(player); // Force client UI resync immediately after load.
        sendPartyReference(player, party);
        debugLog("Applied party to " + player.getName().getString() + " occupied=" + party.occupied());
    } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to deserialize party for " + player.getName().getString(), e);
            if (!backup.isEmpty()) {
                try {
                    party.clearParty();
                    party.loadFromNBT(backup, player.getServer().getRegistryManager());
                    party.initialize();
                    PlayerSyncHandler.ensurePartyStoreCoordinates(party);
                    PlayerSyncHandler.ensurePartyObserver(party, player);
                    party.sendTo(player);
                    sendPartyReference(player, party);
                    debugLog("Restored party after failure for " + player.getName().getString() + " occupied=" + party.occupied());
                } catch (Exception restore) {
                    com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to restore party after deserialization error for " + player.getName().getString(), restore);
                }
            }
        }
    }

    // Send party NBT to the target server via proxy plugin messaging before switching servers.
    public static void sendPartyToServer(ServerPlayerEntity player, String targetServer) {
        if (player == null || targetServer == null || targetServer.isBlank()) return;
        NbtCompound partyNbt = serializeParty(player);
        if (partyNbt == null) return;

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(player.getUuid());
        buf.writeNbt(partyNbt);
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);

        ServerPlayNetworking.send(player, new ProxyForwardPayload(targetServer, PARTY_SYNC_CHANNEL, data));
        lastSendAt.put(player.getUuid(), System.currentTimeMillis());
        lastSendBytes.put(player.getUuid(), data.length);
        debugLog("Sent proxy party forward for " + player.getName().getString() + " to " + targetServer + " bytes=" + data.length);
    }

    public static void sendDebugPing(ServerPlayerEntity player, String targetServer) {
        if (!debugEnabled || player == null || targetServer == null || targetServer.isBlank()) return;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(player.getUuid());
        buf.writeLong(System.currentTimeMillis());
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);

        ServerPlayNetworking.send(player, new ProxyForwardPayload(targetServer, PARTY_DEBUG_CHANNEL, data));
        debugLog("Sent proxy debug ping for " + player.getName().getString() + " to " + targetServer + " bytes=" + data.length);
    }

    // Handle Bungee/Velocity Forward plugin messages containing party NBT.
    public static void handleBungeePayload(BungeePayload payload, MinecraftServer server) {
        String subChannel = payload.subChannel();
        if (debugEnabled) {
            debugLog("Incoming payload subChannel=" + subChannel + " bytes=" + payload.data().length);
        }

        // Some proxy setups deliver the forwarded channel directly as the subChannel.
        if (PARTY_DEBUG_CHANNEL.equals(subChannel)) {
            handleDebugPayload(server, payload.data());
            return;
        }
        if (PARTY_SYNC_CHANNEL.equals(subChannel)) {
            handleDirectPartyPayload(server, payload.data());
            return;
        }
        if ("PlayerCount".equalsIgnoreCase(subChannel)) {
            handlePlayerCountResponse(server, payload.data());
            return;
        }
        if (!"Forward".equalsIgnoreCase(subChannel)) {
            debugLog("Ignored subChannel=" + subChannel);
            return;
        }

        PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(payload.data()));
        String target = BungeePayload.readBungeeString(buf); // Unused (already routed by proxy).
        String channel = BungeePayload.readBungeeString(buf);
        int length = buf.readUnsignedShort();
        if (length > buf.readableBytes()) {
            debugLog("Invalid party payload length=" + length + " readable=" + buf.readableBytes());
            return;
        }

        byte[] data = new byte[length];
        buf.readBytes(data);

        if (PARTY_DEBUG_CHANNEL.equals(channel)) {
            handleDebugPayload(server, data);
            return;
        }
        if (!PARTY_SYNC_CHANNEL.equals(channel)) return;

        PacketByteBuf dataBuf = new PacketByteBuf(Unpooled.wrappedBuffer(data));
        UUID playerId = dataBuf.readUuid();
        NbtCompound partyNbt = dataBuf.readNbt();
        if (partyNbt == null) return;

        pendingPartySyncs.put(playerId, partyNbt);
        lastReceiveAt.put(playerId, System.currentTimeMillis());
        lastReceiveBytes.put(playerId, data.length);
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerId);
        if (debugEnabled) {
            emitDebugLine(online, "Received party payload for " + playerId + " keys=" + partyNbt.getKeys().size(), true);
        }
        if (online != null) {
            applyPendingPartyDelayed(online, server);
        }
    }

    public static void handleDirectPartyPayload(MinecraftServer server, byte[] data) {
        PacketByteBuf dataBuf = new PacketByteBuf(Unpooled.wrappedBuffer(data));
        UUID playerId = dataBuf.readUuid();
        NbtCompound partyNbt = dataBuf.readNbt();
        if (partyNbt == null) return;
        pendingPartySyncs.put(playerId, partyNbt);
        lastReceiveAt.put(playerId, System.currentTimeMillis());
        lastReceiveBytes.put(playerId, data.length);
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerId);
        if (debugEnabled) {
            emitDebugLine(online, "Received direct party payload for " + playerId + " keys=" + partyNbt.getKeys().size(), true);
        }
        if (online != null) {
            applyPendingPartyDelayed(online, server);
        }
    }

    public static void handleDebugPayload(MinecraftServer server, byte[] data) {
        PacketByteBuf dataBuf = new PacketByteBuf(Unpooled.wrappedBuffer(data));
        UUID playerId = dataBuf.readUuid();
        long sentAt = dataBuf.readLong();
        long now = System.currentTimeMillis();
        debugLog("Received debug ping for " + playerId + " age=" + Math.max(0L, now - sentAt) + "ms");
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player != null) {
            emitDebugLine(player, "Received debug ping age=" + Math.max(0L, now - sentAt) + "ms", true);
        }
    }

    private static void handlePlayerCountResponse(MinecraftServer server, byte[] data) {
        PacketByteBuf dataBuf = new PacketByteBuf(Unpooled.wrappedBuffer(data));
        try {
            String serverName = BungeePayload.readBungeeString(dataBuf);
            int count = dataBuf.readInt();
            com.sebastian.cobblemondungeons.dungeon.DungeonManager.handleServerStatusResponse(server, serverName, count);
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to read PlayerCount response", e);
        }
    }

    // Delay apply so Cobblemon finishes initializing the party store after join.
    public static void applyPendingPartyDelayed(ServerPlayerEntity player, MinecraftServer server) {
        if (player == null || server == null) return;
        pendingPartyDelays.put(player.getUuid(), APPLY_DELAY_TICKS);
        debugLog("Scheduled party apply for " + player.getName().getString() + " in " + APPLY_DELAY_TICKS + " ticks");
    }

    public static void queuePendingParty(ServerPlayerEntity player, NbtCompound partyNbt, MinecraftServer server) {
        if (player == null || partyNbt == null || server == null) return;
        pendingPartySyncs.put(player.getUuid(), partyNbt.copy());
        if (debugEnabled) {
            emitDebugLine(player, "Queued party payload (syncData) keys=" + partyNbt.getKeys().size(), true);
        }
        applyPendingPartyDelayed(player, server);
    }

    // Tick pending party applies on the server thread to ensure post-join init is done.
    public static void tickPending(MinecraftServer server) {
        if (server == null) return;
        for (Map.Entry<UUID, Integer> entry : pendingPartyDelays.entrySet()) {
            UUID playerId = entry.getKey();
            Integer ticksLeft = entry.getValue();
            if (ticksLeft == null) {
                pendingPartyDelays.remove(playerId);
                continue;
            }
            int next = ticksLeft - 1;
            if (next > 0) {
                pendingPartyDelays.put(playerId, next);
                continue;
            }
            pendingPartyDelays.remove(playerId);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                applyPendingParty(player);
            }
        }

        for (Map.Entry<UUID, Integer> entry : pendingPartyResends.entrySet()) {
            UUID playerId = entry.getKey();
            Integer ticksLeft = entry.getValue();
            if (ticksLeft == null) {
                pendingPartyResends.remove(playerId);
                continue;
            }
            int next = ticksLeft - 1;
            if (next > 0) {
                pendingPartyResends.put(playerId, next);
                continue;
            }
            pendingPartyResends.remove(playerId);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                resendPartyToClient(player);
            }
        }

        for (Map.Entry<UUID, Integer> entry : pendingPartyLateResends.entrySet()) {
            UUID playerId = entry.getKey();
            Integer ticksLeft = entry.getValue();
            if (ticksLeft == null) {
                pendingPartyLateResends.remove(playerId);
                continue;
            }
            int next = ticksLeft - 1;
            if (next > 0) {
                pendingPartyLateResends.put(playerId, next);
                continue;
            }
            pendingPartyLateResends.remove(playerId);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                resendPartyToClient(player);
            }
        }
    }

    // Apply pending party data after login (server thread only).
    public static void applyPendingParty(ServerPlayerEntity player) {
        if (player == null) return;
        NbtCompound partyNbt = pendingPartySyncs.remove(player.getUuid());
        if (partyNbt != null) {
            debugLog("Applying pending party for " + player.getName().getString());
            deserializeParty(player, partyNbt);
            lastApplyAt.put(player.getUuid(), System.currentTimeMillis());
            dumpParty(player, "after-apply");
            pendingPartyResends.put(player.getUuid(), RESEND_DELAY_TICKS);
            pendingPartyLateResends.put(player.getUuid(), LATE_RESEND_DELAY_TICKS);
        } else {
            debugLog("No pending party for " + player.getName().getString());
        }
    }

    private static void resendPartyToClient(ServerPlayerEntity player) {
        if (player == null) return;
        try {
            PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
            party.sendTo(player);
            sendPartyReference(player, party);
            debugLog("Resent party to client for " + player.getName().getString() + " occupied=" + party.occupied());
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to resend party to " + player.getName().getString(), e);
        }
    }

    private static void sendPartyReference(ServerPlayerEntity player, PlayerPartyStore party) {
        if (player == null || party == null) return;
        try {
            CobblemonNetwork.INSTANCE.sendPacket(player, new SetPartyReferencePacket(party.getUuid()));
            debugLog("Sent party reference for " + player.getName().getString() + " store=" + party.getUuid());
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to send party reference for " + player.getName().getString(), e);
        }
    }
}
