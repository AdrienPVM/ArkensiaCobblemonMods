package com.cheeseguard.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → Server: an owner clicked an action in the zone-owner menu.
 *
 * Action values:
 *   "add"      — target = player name to add as member
 *   "remove"   — target = UUID string of member to remove
 *   "transfer" — target = player name to promote (with confirm flag handled server-side)
 *   "leave"    — target = "" — owner abandons the zone (zone becomes ownerless)
 *
 * The server validates that the calling player still owns the zone they had open.
 * zoneId is included so the server doesn't have to remember per-player state.
 */
public record ZoneMenuActionC2S(String zoneId, String action, String target) implements CustomPayload {

    public static final Id<ZoneMenuActionC2S> ID =
        new Id<>(Identifier.of("cheeseguard", "zone_menu_action"));

    public static final PacketCodec<RegistryByteBuf, ZoneMenuActionC2S> CODEC =
        PacketCodec.tuple(
            PacketCodecs.STRING, ZoneMenuActionC2S::zoneId,
            PacketCodecs.STRING, ZoneMenuActionC2S::action,
            PacketCodecs.STRING, ZoneMenuActionC2S::target,
            ZoneMenuActionC2S::new
        );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
