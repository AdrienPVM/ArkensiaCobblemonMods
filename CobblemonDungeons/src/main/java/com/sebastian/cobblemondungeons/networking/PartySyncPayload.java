package com.sebastian.cobblemondungeons.networking;

import com.sebastian.cobblemondungeons.CobblemonDungeons;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PartySyncPayload(byte[] data) implements CustomPayload {
    public static final Id<PartySyncPayload> ID = new Id<>(Identifier.of(CobblemonDungeons.MOD_ID, "party_sync"));

    public static final PacketCodec<PacketByteBuf, PartySyncPayload> CODEC = PacketCodec.of(
        (value, buf) -> buf.writeBytes(value.data()),
        buf -> {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return new PartySyncPayload(data);
        }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
