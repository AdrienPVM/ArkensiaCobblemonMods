package com.sebastian.cobblemondungeons.networking;

import com.sebastian.cobblemondungeons.CobblemonDungeons;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record DungeonTicketSyncPayload(String json) implements CustomPayload {
    public static final Id<DungeonTicketSyncPayload> ID = new Id<>(Identifier.of(CobblemonDungeons.MOD_ID, "dungeon_ticket_sync"));

    public static final PacketCodec<PacketByteBuf, DungeonTicketSyncPayload> CODEC = PacketCodec.of(
        (value, buf) -> buf.writeString(value.json),
        buf -> new DungeonTicketSyncPayload(buf.readString())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
