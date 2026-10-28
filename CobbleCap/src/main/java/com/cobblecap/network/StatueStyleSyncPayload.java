package com.cobblecap.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → client: tells the client which {@link com.cobblecap.data.StatueStyle}
 * ordinal applies to each doppelganger synthetic UUID. The client mixin
 * {@code PlayerEntityRendererMixin} then swaps the player skin texture for the
 * matching {@code assets/cobblecap/textures/statue/<style>.png}.
 *
 * Wire format:
 *   byte mode (SNAPSHOT=0, UPSERT=1, REMOVE=2)
 *   varInt count
 *     repeat count times:
 *       UUID uuid
 *       byte styleOrdinal
 *
 * MODE_REMOVE entries still carry a style byte (ignored) so the wire shape
 * is uniform — keeps the codec dead simple.
 */
public record StatueStyleSyncPayload(byte mode, List<Entry> entries) implements CustomPayload {

    public static final byte MODE_SNAPSHOT = 0;
    public static final byte MODE_UPSERT   = 1;
    public static final byte MODE_REMOVE   = 2;

    public record Entry(UUID uuid, byte styleOrdinal) {}

    public static final CustomPayload.Id<StatueStyleSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of("cobblecap", "statue_style_sync"));

    public static final PacketCodec<PacketByteBuf, StatueStyleSyncPayload> CODEC =
            PacketCodec.of(StatueStyleSyncPayload::write, StatueStyleSyncPayload::new);

    public StatueStyleSyncPayload(PacketByteBuf buf) {
        this(buf.readByte(), readEntries(buf));
    }

    private static List<Entry> readEntries(PacketByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID u = buf.readUuid();
            byte s = buf.readByte();
            out.add(new Entry(u, s));
        }
        return out;
    }

    private void write(PacketByteBuf buf) {
        buf.writeByte(mode);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUuid(e.uuid());
            buf.writeByte(e.styleOrdinal());
        }
    }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }

    public static StatueStyleSyncPayload upsert(UUID uuid, int ordinal) {
        return new StatueStyleSyncPayload(MODE_UPSERT, List.of(new Entry(uuid, (byte) ordinal)));
    }
    public static StatueStyleSyncPayload remove(UUID uuid) {
        return new StatueStyleSyncPayload(MODE_REMOVE, List.of(new Entry(uuid, (byte) 0)));
    }
    public static StatueStyleSyncPayload snapshot(List<Entry> entries) {
        return new StatueStyleSyncPayload(MODE_SNAPSHOT, entries);
    }
}
