package com.sebastian.cobblemondungeons.networking;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.nio.charset.StandardCharsets;

public record BungeePayload(String subChannel, byte[] data) implements CustomPayload {
    public static final Id<BungeePayload> ID = new Id<>(Identifier.of("bungeecord", "main"));
    
    public static final PacketCodec<PacketByteBuf, BungeePayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            writeBungeeString(buf, value.subChannel());
            buf.writeBytes(value.data());
        },
        buf -> {
            String subChannel = readBungeeString(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return new BungeePayload(subChannel, data);
        }
    );

    public static BungeePayload connect(String serverName) {
        net.minecraft.network.PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        writeBungeeString(buf, serverName);
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return new BungeePayload("Connect", data);
    }

    public static BungeePayload forward(String targetServer, String channel, byte[] data) {
        net.minecraft.network.PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        writeBungeeString(buf, targetServer);
        writeBungeeString(buf, channel);
        buf.writeShort(data.length);
        buf.writeBytes(data);
        byte[] fullData = new byte[buf.readableBytes()];
        buf.readBytes(fullData);
        return new BungeePayload("Forward", fullData);
    }

    public static BungeePayload playerCount(String serverName) {
        net.minecraft.network.PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        writeBungeeString(buf, serverName);
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return new BungeePayload("PlayerCount", data);
    }

    public static void writeBungeeString(PacketByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    public static String readBungeeString(PacketByteBuf buf) {
        int len = buf.readUnsignedShort();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
