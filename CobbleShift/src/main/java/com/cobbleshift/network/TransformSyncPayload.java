package com.cobbleshift.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * S2C packet: sync transform state to all clients.
 * Clients use this to swap player model to Pokémon + hide nametags.
 * species="" means revert (player no longer transformed).
 * scale=1.0 means default size; other values are custom multipliers.
 */
public record TransformSyncPayload(UUID playerUuid, String species, String type1, String type2, float scale, boolean silent, boolean fromNpc) implements CustomPayload {

    /** Convenience constructor with default scale and not silent, player source. */
    public TransformSyncPayload(UUID playerUuid, String species, String type1, String type2) {
        this(playerUuid, species, type1, type2, 1.0f, false, false);
    }

    /** Convenience constructor with scale, not silent, player source. */
    public TransformSyncPayload(UUID playerUuid, String species, String type1, String type2, float scale) {
        this(playerUuid, species, type1, type2, scale, false, false);
    }

    /** Convenience constructor with scale + silent flag, player source. */
    public TransformSyncPayload(UUID playerUuid, String species, String type1, String type2, float scale, boolean silent) {
        this(playerUuid, species, type1, type2, scale, silent, false);
    }

    public static final CustomPayload.Id<TransformSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cobbleshift", "transform_sync"));

    public static final PacketCodec<PacketByteBuf, TransformSyncPayload> CODEC =
        PacketCodec.of(TransformSyncPayload::write, TransformSyncPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeUuid(playerUuid);
        buf.writeString(species);
        buf.writeString(type1 != null ? type1 : "");
        buf.writeString(type2 != null ? type2 : "");
        buf.writeFloat(scale);
        buf.writeBoolean(silent);
        buf.writeBoolean(fromNpc);
    }

    private static TransformSyncPayload read(PacketByteBuf buf) {
        UUID uuid = buf.readUuid();
        String sp = buf.readString();
        String t1 = buf.readString();
        String t2 = buf.readString();
        float sc = buf.readFloat();
        boolean si = buf.readBoolean();
        boolean fn = buf.readBoolean();
        return new TransformSyncPayload(uuid, sp, t1.isEmpty() ? null : t1, t2.isEmpty() ? null : t2, sc, si, fn);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * Register this payload type for S2C networking.
     * Call from both server and client mod initializers.
     */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }
}
