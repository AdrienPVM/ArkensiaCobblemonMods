package com.sebastian.cobblemondungeons.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
    id = "cobblemon-party-forwarder",
    name = "Cobblemon Party Forwarder",
    version = "1.0.5"
)
public class PartyForwarder {
    private static final ChannelIdentifier BUNGEE_CHANNEL = MinecraftChannelIdentifier.create("bungeecord", "main");
    private static final ChannelIdentifier PROXY_FORWARD_CHANNEL = MinecraftChannelIdentifier.create("cobblemon_dungeons", "proxy_forward");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Map<UUID, List<PendingMessage>> pending = new ConcurrentHashMap<>();
    private final Map<String, ChannelIdentifier> channelCache = new ConcurrentHashMap<>();

    @Inject
    public PartyForwarder(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(BUNGEE_CHANNEL, PROXY_FORWARD_CHANNEL);
        logger.info("[CobblemonPartyForwarder] Registered channel {}", BUNGEE_CHANNEL.getId());
        logger.info("[CobblemonPartyForwarder] Registered channel {}", PROXY_FORWARD_CHANNEL.getId());
        logger.info("[CobblemonPartyForwarder] Plugin jar path={}", resolveJarPath());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        ChannelIdentifier identifier = event.getIdentifier();
        if (!identifier.equals(BUNGEE_CHANNEL) && !identifier.equals(PROXY_FORWARD_CHANNEL)) {
            return;
        }
        logger.info("[CobblemonPartyForwarder] PluginMessage channel={} source={} target={} bytes={}",
            identifier.getId(), describeEndpoint(event.getSource()), describeEndpoint(event.getTarget()), event.getData().length);
        if (!(event.getSource() instanceof ServerConnection sourceConnection)) {
            logger.info("[CobblemonPartyForwarder] Ignored message: source is not a server connection");
            return;
        }

        if (identifier.equals(BUNGEE_CHANNEL)) {
            handleBungeeForward(event, sourceConnection);
            return;
        }
        handleProxyForward(event, sourceConnection);
    }

    private void handleBungeeForward(PluginMessageEvent event, ServerConnection sourceConnection) {
        byte[] data = event.getData();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String subChannel = readBungeeString(in);
            logger.info("[CobblemonPartyForwarder] Incoming subChannel={}", subChannel);
            if (!"Forward".equalsIgnoreCase(subChannel)) {
                logger.info("[CobblemonPartyForwarder] Ignored non-Forward subChannel={}", subChannel);
                return;
            }

            String targetServer = readBungeeString(in);
            String channel = readBungeeString(in);
            int length = in.readUnsignedShort();
            int remaining = in.available();
            if (length < 0 || length > remaining) {
                logger.warn("[CobblemonPartyForwarder] Invalid Forward length={} remaining={}", length, remaining);
                return;
            }
            byte[] forwarded = new byte[length];
            in.readFully(forwarded);
            logger.info("[CobblemonPartyForwarder] Forward target={} channel={} bytes={}", targetServer, channel, length);
            queueForwarded(sourceConnection, targetServer, channel, forwarded);
            event.setResult(PluginMessageEvent.ForwardResult.handled());
        } catch (IOException e) {
            logger.warn("[CobblemonPartyForwarder] Failed to parse bungeecord forward", e);
        }
    }

    private void handleProxyForward(PluginMessageEvent event, ServerConnection sourceConnection) {
        byte[] data = event.getData();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String targetServer = readBungeeString(in);
            String channel = readBungeeString(in);
            int length = in.readInt();
            int remaining = in.available();
            if (length < 0 || length > remaining) {
                logger.warn("[CobblemonPartyForwarder] Invalid proxy forward length={} remaining={}", length, remaining);
                return;
            }
            byte[] forwarded = new byte[length];
            in.readFully(forwarded);
            logger.info("[CobblemonPartyForwarder] ProxyForward target={} channel={} bytes={}", targetServer, channel, length);
            queueForwarded(sourceConnection, targetServer, channel, forwarded);
            event.setResult(PluginMessageEvent.ForwardResult.handled());
        } catch (IOException e) {
            logger.warn("[CobblemonPartyForwarder] Failed to parse proxy forward", e);
        }
    }

    private void queueForwarded(ServerConnection sourceConnection, String targetServer, String channel, byte[] forwarded) {
        UUID playerId = extractUuid(forwarded);
        if (playerId == null) {
            logger.warn("[CobblemonPartyForwarder] Could not read player UUID from forwarded payload (channel={})", channel);
            return;
        }

        PendingMessage pendingMessage = new PendingMessage(targetServer, channel, forwarded);
        pending.computeIfAbsent(playerId, key -> new ArrayList<>()).add(pendingMessage);
        logger.info("[CobblemonPartyForwarder] Queued pending for {} target={} sourceServer={}",
            playerId, targetServer, sourceConnection.getServerInfo().getName());

        Optional<Player> player = proxy.getPlayer(playerId);
        if (player.isPresent()) {
            if (trySendToServer(player.get(), pendingMessage)) {
                removePending(playerId, pendingMessage);
            }
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        List<PendingMessage> pendingMessages = pending.get(playerId);
        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return;
        }

        List<PendingMessage> sent = new ArrayList<>();
        for (PendingMessage message : pendingMessages) {
            if (trySendToServer(player, message)) {
                sent.add(message);
            }
        }
        for (PendingMessage message : sent) {
            removePending(playerId, message);
        }
    }

    private boolean trySendToServer(Player player, PendingMessage message) {
        Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isEmpty()) {
            return false;
        }
        ServerConnection connection = current.get();
        String serverName = connection.getServerInfo().getName();
        if (!targetMatches(connection, message.targetServer)) {
            return false;
        }

        ChannelIdentifier targetChannel = resolveChannel(message.channel);
        if (targetChannel == null) {
            logger.warn("[CobblemonPartyForwarder] Invalid target channel={}, cannot forward", message.channel);
            return false;
        }
        connection.sendPluginMessage(targetChannel, message.data);
        logger.info("[CobblemonPartyForwarder] Forwarded {} bytes to {} on {} for {}", message.data.length, serverName, message.channel, player.getUsername());
        return true;
    }

    private void removePending(UUID playerId, PendingMessage message) {
        List<PendingMessage> messages = pending.get(playerId);
        if (messages == null) return;
        messages.remove(message);
        if (messages.isEmpty()) {
            pending.remove(playerId);
        }
    }

    private boolean targetMatches(ServerConnection connection, String target) {
        if (target == null) return false;
        if ("ALL".equalsIgnoreCase(target)) return true;
        if ("ONLINE".equalsIgnoreCase(target)) return true;
        String serverName = connection.getServerInfo().getName();
        if (serverName.equalsIgnoreCase(target)) return true;
        String host = connection.getServerInfo().getAddress().getHostString();
        int port = connection.getServerInfo().getAddress().getPort();
        String hostPort = host + ":" + port;
        return host.equalsIgnoreCase(target) || hostPort.equalsIgnoreCase(target);
    }

    private static String readBungeeString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        if (len <= 0) return "";
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static UUID extractUuid(byte[] data) {
        if (data == null || data.length < 16) return null;
        ByteBuffer buffer = ByteBuffer.wrap(data);
        long most = buffer.getLong();
        long least = buffer.getLong();
        return new UUID(most, least);
    }

    private static String resolveJarPath() {
        try {
            java.security.CodeSource source = PartyForwarder.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return "unknown";
            }
            return new java.io.File(source.getLocation().toURI()).getAbsolutePath();
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private ChannelIdentifier resolveChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        ChannelIdentifier cached = channelCache.get(channel);
        if (cached != null) {
            return cached;
        }
        String[] parts = channel.split(":", 2);
        if (parts.length != 2) {
            return null;
        }
        ChannelIdentifier identifier = MinecraftChannelIdentifier.create(parts[0], parts[1]);
        proxy.getChannelRegistrar().register(identifier);
        channelCache.put(channel, identifier);
        return identifier;
    }

    private static String describeEndpoint(Object endpoint) {
        if (endpoint instanceof Player player) {
            return "player:" + player.getUsername();
        }
        if (endpoint instanceof ServerConnection connection) {
            return "server:" + connection.getServerInfo().getName();
        }
        if (endpoint == null) {
            return "null";
        }
        return endpoint.getClass().getSimpleName();
    }

    private static class PendingMessage {
        private final String targetServer;
        private final String channel;
        private final byte[] data;

        private PendingMessage(String targetServer, String channel, byte[] data) {
            this.targetServer = targetServer;
            this.channel = channel;
            this.data = data;
        }
    }
}
