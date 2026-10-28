package com.cobbleshift.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C packet: sync ability state to the affected player.
 * Client uses this to display the HUD overlay (type icon + species name).
 * species="" means no active ability.
 */
public record AbilitySyncPayload(String species, String type1, String type2) implements CustomPayload {

    public static final CustomPayload.Id<AbilitySyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cobbleshift", "ability_sync"));

    public static final PacketCodec<PacketByteBuf, AbilitySyncPayload> CODEC =
        PacketCodec.of(AbilitySyncPayload::write, AbilitySyncPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeString(species);
        buf.writeString(type1);
        buf.writeString(type2 != null ? type2 : "");
    }

    private static AbilitySyncPayload read(PacketByteBuf buf) {
        String sp = buf.readString();
        String t1 = buf.readString();
        String t2 = buf.readString();
        return new AbilitySyncPayload(sp, t1, t2.isEmpty() ? null : t2);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * Register this payload type for S2C networking.
     */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }
}
