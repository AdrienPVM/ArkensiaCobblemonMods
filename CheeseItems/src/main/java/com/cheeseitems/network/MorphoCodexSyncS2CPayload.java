package com.cheeseitems.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server → client: full snapshot of the player's Morpho Codex.
 * Encoded as a JSON string to keep the codec simple — payload is only sent on join/mutation,
 * so the size overhead is negligible.
 *
 * JSON shape:
 * {
 *   "active": "itemId" | "",
 *   "entries": [{"itemId":"...","species":"...","form":"...","shiny":false,"displayName":"..."}]
 * }
 */
public record MorphoCodexSyncS2CPayload(String json) implements CustomPayload {

    public static final CustomPayload.Id<MorphoCodexSyncS2CPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cheeseitems", "morpho_codex_sync"));

    public static final PacketCodec<RegistryByteBuf, MorphoCodexSyncS2CPayload> CODEC =
        PacketCodec.tuple(
            PacketCodecs.STRING, MorphoCodexSyncS2CPayload::json,
            MorphoCodexSyncS2CPayload::new
        );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
