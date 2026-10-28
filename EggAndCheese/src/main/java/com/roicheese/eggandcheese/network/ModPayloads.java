package com.roicheese.eggandcheese.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public class ModPayloads {

    // ── S2C — render config sync ─────────────────────────────────────────────
    public record SyncRegistryPayload(byte[] data) implements CustomPayload {
        public static final Id<SyncRegistryPayload> ID = new Id<>(Identifier.of("eggandcheese","sync_registry"));
        public static final PacketCodec<PacketByteBuf, SyncRegistryPayload> CODEC = PacketCodec.of(
            (p, b) -> b.writeByteArray(p.data()), b -> new SyncRegistryPayload(b.readByteArray()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void registerAll() {
        PayloadTypeRegistry.playS2C().register(SyncRegistryPayload.ID, SyncRegistryPayload.CODEC);
    }

    public static byte[] toBytes(PacketByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    public static PacketByteBuf fromBytes(byte[] bytes) {
        PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        buf.writeBytes(bytes);
        return buf;
    }
}
