package com.cheesedash.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> Server: player requests to execute a command from the Quick Menu.
 * The server validates permission level (op 2+) before executing.
 */
public record QuickMenuPayload(String command) implements CustomPayload {

    public static final CustomPayload.Id<QuickMenuPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cheesedash", "quick_menu_cmd"));

    public static final PacketCodec<RegistryByteBuf, QuickMenuPayload> CODEC =
        PacketCodec.tuple(
            PacketCodecs.STRING, QuickMenuPayload::command,
            QuickMenuPayload::new
        );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
