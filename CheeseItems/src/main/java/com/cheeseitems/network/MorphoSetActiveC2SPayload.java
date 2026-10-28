package com.cheeseitems.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → server: "Make this morpho item the active one". Server validates the id
 * is in the player's codex before storing the choice.
 */
public record MorphoSetActiveC2SPayload(String itemId) implements CustomPayload {

    public static final CustomPayload.Id<MorphoSetActiveC2SPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cheeseitems", "morpho_set_active"));

    public static final PacketCodec<RegistryByteBuf, MorphoSetActiveC2SPayload> CODEC =
        PacketCodec.tuple(
            PacketCodecs.STRING, MorphoSetActiveC2SPayload::itemId,
            MorphoSetActiveC2SPayload::new
        );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
