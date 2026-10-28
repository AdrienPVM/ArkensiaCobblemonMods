package com.cheeseguard.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → Server: the player pressed the "zone menu" keybind.
 * The server resolves which zone the player is currently inside and,
 * if they own it, replies with a {@link ZoneMenuDataS2C}.
 */
public record OpenZoneMenuC2S() implements CustomPayload {
    public static final Id<OpenZoneMenuC2S> ID =
        new Id<>(Identifier.of("cheeseguard", "zone_menu_open"));
    public static final PacketCodec<RegistryByteBuf, OpenZoneMenuC2S> CODEC =
        PacketCodec.unit(new OpenZoneMenuC2S());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
