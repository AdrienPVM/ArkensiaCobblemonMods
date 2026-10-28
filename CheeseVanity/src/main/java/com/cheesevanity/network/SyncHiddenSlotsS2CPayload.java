package com.cheesevanity.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Server -> Client: sync hidden slots for a specific player.
 * Sent on join (for all nearby players) and on toggle (broadcast).
 */
public record SyncHiddenSlotsS2CPayload(UUID playerUuid, Set<String> hiddenSlots) implements CustomPayload {

    public static final Id<SyncHiddenSlotsS2CPayload> ID =
            new Id<>(Identifier.of("cheesevanity", "sync_hidden"));

    public static final PacketCodec<RegistryByteBuf, SyncHiddenSlotsS2CPayload> CODEC =
            new PacketCodec<>() {
                @Override
                public SyncHiddenSlotsS2CPayload decode(RegistryByteBuf buf) {
                    UUID uuid = buf.readUuid();
                    int count = buf.readVarInt();
                    Set<String> slots = new HashSet<>();
                    for (int i = 0; i < count; i++) {
                        slots.add(buf.readString());
                    }
                    return new SyncHiddenSlotsS2CPayload(uuid, slots);
                }

                @Override
                public void encode(RegistryByteBuf buf, SyncHiddenSlotsS2CPayload payload) {
                    buf.writeUuid(payload.playerUuid);
                    buf.writeVarInt(payload.hiddenSlots.size());
                    for (String slot : payload.hiddenSlots) {
                        buf.writeString(slot);
                    }
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
