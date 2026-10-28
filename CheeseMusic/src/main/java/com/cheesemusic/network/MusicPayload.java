package com.cheesemusic.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C packet: control zone music on the client.
 * action: "play" (tracksJson = JSON array of sound IDs + mode), "stop", "fade_to"
 * tracksJson: for "play" action, JSON like {"tracks":["cheesemusic:arena","cheesemusic:battle"],"mode":"RANDOM"}
 * volume: target volume (0.0-1.0)
 * fadeTicks: duration of fade transition in ticks (20 ticks = 1 second)
 */
public record MusicPayload(String action, String tracksJson, float volume, int fadeTicks) implements CustomPayload {

    public static final CustomPayload.Id<MusicPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cheesemusic", "zone_music"));

    public static final PacketCodec<PacketByteBuf, MusicPayload> CODEC =
        PacketCodec.of(MusicPayload::write, MusicPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeString(action);
        buf.writeString(tracksJson != null ? tracksJson : "");
        buf.writeFloat(volume);
        buf.writeInt(fadeTicks);
    }

    private static MusicPayload read(PacketByteBuf buf) {
        String action = buf.readString();
        String tracksJson = buf.readString();
        float volume = buf.readFloat();
        int fadeTicks = buf.readInt();
        return new MusicPayload(action, tracksJson.isEmpty() ? null : tracksJson, volume, fadeTicks);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }
}
