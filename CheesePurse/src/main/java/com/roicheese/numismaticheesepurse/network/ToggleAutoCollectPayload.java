package com.roicheese.numismaticheesepurse.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Packet sent from client to server when the player toggles auto-collect.
 * Contains the new desired state (true = enabled, false = disabled).
 */
public record ToggleAutoCollectPayload(boolean enabled) implements CustomPayload {

    public static final Id<ToggleAutoCollectPayload> ID =
            new Id<>(Identifier.of("numismatic-cheesepurse", "toggle_auto_collect"));

    public static final PacketCodec<PacketByteBuf, ToggleAutoCollectPayload> CODEC =
            PacketCodec.of(
                    (payload, buf) -> buf.writeBoolean(payload.enabled()),
                    buf -> new ToggleAutoCollectPayload(buf.readBoolean())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
