package com.cheeseguard.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PropHuntActivatePowerPayload() implements CustomPayload {
    public static final Id<PropHuntActivatePowerPayload> ID =
        new Id<>(Identifier.of("cheeseguard", "prophunt_activate"));
    public static final PacketCodec<RegistryByteBuf, PropHuntActivatePowerPayload> CODEC =
        PacketCodec.unit(new PropHuntActivatePowerPayload());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
