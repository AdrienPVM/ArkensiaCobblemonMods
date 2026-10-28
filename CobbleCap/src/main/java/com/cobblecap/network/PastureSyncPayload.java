package com.cobblecap.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → client: pastured Pokémon UUID delta or snapshot.
 * <p>
 * The {@code tetheringId} field on a Cobblemon {@code Pokemon} is server-only —
 * it's never synced via DataTracker. We mirror it manually so /pasturehide
 * can decide which entities to skip rendering.
 *
 * <ul>
 *   <li>{@link Mode#SNAPSHOT} — full set, sent on player join.</li>
 *   <li>{@link Mode#ADD}, {@link Mode#REMOVE} — delta updates, broadcast as
 *       pasture state changes.</li>
 * </ul>
 */
public record PastureSyncPayload(byte mode, List<UUID> uuids) implements CustomPayload {

    public static final byte MODE_SNAPSHOT = 0;
    public static final byte MODE_ADD      = 1;
    public static final byte MODE_REMOVE   = 2;

    public static final CustomPayload.Id<PastureSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of("cobblecap", "pasture_sync"));

    public static final PacketCodec<PacketByteBuf, PastureSyncPayload> CODEC =
            PacketCodec.of(PastureSyncPayload::write, PastureSyncPayload::new);

    public PastureSyncPayload(PacketByteBuf buf) {
        this(buf.readByte(), readUuids(buf));
    }

    private static List<UUID> readUuids(PacketByteBuf buf) {
        int n = buf.readVarInt();
        List<UUID> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(buf.readUuid());
        return out;
    }

    private void write(PacketByteBuf buf) {
        buf.writeByte(mode);
        buf.writeVarInt(uuids.size());
        for (UUID u : uuids) buf.writeUuid(u);
    }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }

    /** Helpers for callers — they don't need to know the byte codes. */
    public static PastureSyncPayload snapshot(List<UUID> uuids) {
        return new PastureSyncPayload(MODE_SNAPSHOT, uuids);
    }
    public static PastureSyncPayload add(UUID uuid) {
        return new PastureSyncPayload(MODE_ADD, List.of(uuid));
    }
    public static PastureSyncPayload remove(UUID uuid) {
        return new PastureSyncPayload(MODE_REMOVE, List.of(uuid));
    }
}
