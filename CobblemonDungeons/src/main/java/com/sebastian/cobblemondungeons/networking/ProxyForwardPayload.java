package com.sebastian.cobblemondungeons.networking;

import com.sebastian.cobblemondungeons.CobblemonDungeons;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ProxyForwardPayload(String targetServer, String channel, byte[] data) implements CustomPayload {
    public static final Id<ProxyForwardPayload> ID = new Id<>(Identifier.of(CobblemonDungeons.MOD_ID, "proxy_forward"));

    public static final PacketCodec<PacketByteBuf, ProxyForwardPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            BungeePayload.writeBungeeString(buf, value.targetServer());
            BungeePayload.writeBungeeString(buf, value.channel());
            buf.writeInt(value.data().length);
            buf.writeBytes(value.data());
        },
        buf -> {
            String target = BungeePayload.readBungeeString(buf);
            String channel = BungeePayload.readBungeeString(buf);
            int length = buf.readInt();
            if (length < 0 || length > buf.readableBytes()) {
                length = Math.max(0, Math.min(buf.readableBytes(), length));
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new ProxyForwardPayload(target, channel, data);
        }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
