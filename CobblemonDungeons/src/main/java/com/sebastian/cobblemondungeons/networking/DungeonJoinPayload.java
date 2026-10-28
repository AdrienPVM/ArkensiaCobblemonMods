package com.sebastian.cobblemondungeons.networking;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.Identifier;
import com.sebastian.cobblemondungeons.CobblemonDungeons;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

public record DungeonJoinPayload(String dungeonId, UUID partyId, int partySize, NbtCompound syncData, int tier) implements CustomPayload {
    public static final Id<DungeonJoinPayload> ID = new Id<>(Identifier.of(CobblemonDungeons.MOD_ID, "dungeon_join"));
    
    public static final PacketCodec<PacketByteBuf, DungeonJoinPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            buf.writeString(value.dungeonId());
            buf.writeBoolean(value.partyId() != null);
            if (value.partyId() != null) buf.writeUuid(value.partyId());
            buf.writeInt(value.partySize());
            buf.writeInt(value.tier());
            buf.writeBoolean(value.syncData() != null);
            if (value.syncData() != null) {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    NbtIo.writeCompressed(value.syncData(), baos);
                    buf.writeByteArray(baos.toByteArray());
                } catch (Exception e) {
                    com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to write compressed sync data", e);
                    buf.writeByteArray(new byte[0]);
                }
            }
        },
        buf -> {
            String dId = buf.readString();
            UUID pId = buf.readBoolean() ? buf.readUuid() : null;
            int pSize = buf.readInt();
            int tier = buf.readInt();
            boolean hasSData = buf.readBoolean();
            NbtCompound sData = null;
            if (hasSData) {
                byte[] bytes = buf.readByteArray(2097152); // 2MB limit
                if (bytes.length > 0) {
                    try {
                        sData = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtSizeTracker.ofUnlimitedBytes());
                    } catch (Exception e) {
                        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to read compressed sync data", e);
                    }
                }
            }
            return new DungeonJoinPayload(dId, pId, pSize, sData, tier);
        }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
