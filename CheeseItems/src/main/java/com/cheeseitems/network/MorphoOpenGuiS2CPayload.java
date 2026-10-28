package com.cheeseitems.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server → client: instruct the client to open the Morpho Codex GUI.
 * Used by the {@code /morpho} command so players can open the codex without binding a key.
 */
public record MorphoOpenGuiS2CPayload() implements CustomPayload {

    public static final CustomPayload.Id<MorphoOpenGuiS2CPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cheeseitems", "morpho_open_gui"));

    public static final PacketCodec<RegistryByteBuf, MorphoOpenGuiS2CPayload> CODEC =
        PacketCodec.unit(new MorphoOpenGuiS2CPayload());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
