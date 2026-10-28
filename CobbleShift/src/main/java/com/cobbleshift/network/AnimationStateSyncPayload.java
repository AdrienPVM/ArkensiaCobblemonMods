package com.cobbleshift.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * S2C packet: sync animation state for transformed players.
 * Sent every 5 ticks to nearby players so they see correct pose/movement.
 *
 * envState: 0=ground, 1=surface_water, 2=submerged, 3=air, 8=sleep
 * isMoving: whether the player is moving (velocity > threshold)
 */
public record AnimationStateSyncPayload(UUID playerUuid, int envState, boolean isMoving) implements CustomPayload {

    public static final CustomPayload.Id<AnimationStateSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cobbleshift", "anim_state_sync"));

    public static final PacketCodec<PacketByteBuf, AnimationStateSyncPayload> CODEC =
        PacketCodec.of(AnimationStateSyncPayload::write, AnimationStateSyncPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeUuid(playerUuid);
        buf.writeByte(envState);
        buf.writeBoolean(isMoving);
    }

    private static AnimationStateSyncPayload read(PacketByteBuf buf) {
        UUID uuid = buf.readUuid();
        int env = buf.readByte();
        boolean moving = buf.readBoolean();
        return new AnimationStateSyncPayload(uuid, env, moving);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }
}
