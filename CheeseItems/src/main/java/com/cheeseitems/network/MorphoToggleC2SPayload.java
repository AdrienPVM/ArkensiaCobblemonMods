package com.cheeseitems.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → server: "Toggle my active morpho on/off". The server uses whichever morpho is
 * currently flagged active in the player's MorphoCodex.
 */
public record MorphoToggleC2SPayload() implements CustomPayload {

    public static final CustomPayload.Id<MorphoToggleC2SPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cheeseitems", "morpho_toggle"));

    public static final PacketCodec<RegistryByteBuf, MorphoToggleC2SPayload> CODEC =
        PacketCodec.unit(new MorphoToggleC2SPayload());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
