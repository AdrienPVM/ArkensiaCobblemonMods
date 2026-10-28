package com.cheeseguard.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server → Client: sends the current GUI view mode + its JSON data.
 * The client opens/updates CheeseGuardScreen on receipt.
 *
 * viewMode:
 *   MAIN, REGION_LIST, REGION_EDIT, FLAGS, MEMBERS,
 *   GROUP_LIST, GROUP_EDIT, GROUP_FLAGS, CLOSE
 */
public record GuiSyncPayload(String viewMode, String json) implements CustomPayload {

    public static final CustomPayload.Id<GuiSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cheeseguard", "gui_sync"));

    public static final PacketCodec<RegistryByteBuf, GuiSyncPayload> CODEC =
        PacketCodec.tuple(
            PacketCodecs.STRING, GuiSyncPayload::viewMode,
            PacketCodecs.STRING, GuiSyncPayload::json,
            GuiSyncPayload::new
        );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }

    /** Helper: parse the JSON string as a JsonObject */
    public com.google.gson.JsonObject data() {
        try {
            return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return new com.google.gson.JsonObject();
        }
    }
}
