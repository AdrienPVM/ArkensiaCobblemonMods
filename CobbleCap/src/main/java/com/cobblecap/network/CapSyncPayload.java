package com.cobblecap.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C packet: sync cap state to the player's client.
 * Client uses this to display the HUD overlay (cap level + next champion).
 * Sent on player join, cap change, HUD toggle, and system enable/disable.
 */
public record CapSyncPayload(int capLevel, String nextChampion, boolean hudVisible, int hudPosition, boolean systemEnabled)
        implements CustomPayload {

    public static final CustomPayload.Id<CapSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of("cobblecap", "cap_sync"));

    public static final PacketCodec<PacketByteBuf, CapSyncPayload> CODEC =
            PacketCodec.of(CapSyncPayload::write, CapSyncPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeInt(capLevel);
        buf.writeString(nextChampion != null ? nextChampion : "");
        buf.writeBoolean(hudVisible);
        buf.writeInt(hudPosition);
        buf.writeBoolean(systemEnabled);
    }

    private static CapSyncPayload read(PacketByteBuf buf) {
        int cap = buf.readInt();
        String next = buf.readString();
        boolean hud = buf.readBoolean();
        int pos = buf.readInt();
        boolean enabled = buf.readBoolean();
        return new CapSyncPayload(cap, next.isEmpty() ? null : next, hud, pos, enabled);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * Register this payload type for S2C networking.
     */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }
}
