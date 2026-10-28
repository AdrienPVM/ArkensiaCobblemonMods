package com.cheesekart.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * S2C payload to inform clients about kart state changes.
 * Empty species + active=false = kart stopped.
 */
public record KartSyncPayload(UUID playerUuid, boolean active, int boatEntityId) implements CustomPayload {

    public static final Id<KartSyncPayload> ID = new CustomPayload.Id<>(Identifier.of("cheesekart", "sync"));
    public static final PacketCodec<PacketByteBuf, KartSyncPayload> CODEC =
            PacketCodec.of(KartSyncPayload::write, KartSyncPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeUuid(playerUuid);
        buf.writeBoolean(active);
        buf.writeVarInt(boatEntityId);
    }

    private static KartSyncPayload read(PacketByteBuf buf) {
        return new KartSyncPayload(buf.readUuid(), buf.readBoolean(), buf.readVarInt());
    }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }
}
