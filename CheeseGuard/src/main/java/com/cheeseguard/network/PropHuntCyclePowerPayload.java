package com.cheeseguard.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PropHuntCyclePowerPayload() implements CustomPayload {
    public static final Id<PropHuntCyclePowerPayload> ID =
        new Id<>(Identifier.of("cheeseguard", "prophunt_cycle"));
    public static final PacketCodec<RegistryByteBuf, PropHuntCyclePowerPayload> CODEC =
        PacketCodec.unit(new PropHuntCyclePowerPayload());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
