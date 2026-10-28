package com.cheeseextra.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C packet that drives the island-tour HUD on Arkensia.
 * Sent only when a player's tour state changes (new NPC met, quest started/finished),
 * not on every tick.
 */
public record IslandTourPayload(boolean show, int met, int total, List<String> remaining) implements CustomPayload {

    public static final CustomPayload.Id<IslandTourPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cheeseextra", "island_tour"));

    public static final PacketCodec<PacketByteBuf, IslandTourPayload> CODEC =
        PacketCodec.of(IslandTourPayload::write, IslandTourPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeBoolean(show);
        buf.writeVarInt(met);
        buf.writeVarInt(total);
        buf.writeVarInt(remaining.size());
        for (String n : remaining) buf.writeString(n, 64);
    }

    private static IslandTourPayload read(PacketByteBuf buf) {
        boolean show = buf.readBoolean();
        int met = buf.readVarInt();
        int total = buf.readVarInt();
        int n = buf.readVarInt();
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readString(64));
        return new IslandTourPayload(show, met, total, list);
    }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }
}
