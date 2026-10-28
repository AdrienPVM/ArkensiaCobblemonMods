package com.cheesevanity.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> Server: request to toggle visibility of a specific slot.
 */
public record ToggleSlotC2SPayload(String slotKey) implements CustomPayload {

    public static final Id<ToggleSlotC2SPayload> ID =
            new Id<>(Identifier.of("cheesevanity", "toggle_slot"));

    public static final PacketCodec<RegistryByteBuf, ToggleSlotC2SPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.STRING, ToggleSlotC2SPayload::slotKey,
                    ToggleSlotC2SPayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
