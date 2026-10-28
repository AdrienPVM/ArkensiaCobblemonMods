package com.cheesestore.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server -> client cape ownership/equip broadcast (the SERVER SEND side of the dedicated
 * {@code cheesestore:capes} contract).
 *
 * <p><b>Wire contract (must stay byte-identical to the client copy
 * {@code com.factionwarhud.net.CapePayload} in the factionwarhud companion mod):</b>
 * <pre>
 *   Identifier : cheesestore:capes
 *   read order :
 *     varInt   count
 *     repeat count times:
 *       UUID    playerUuid   (writeUuid / readUuid)
 *       string  capeId       (e.g. "cape_phenix")
 * </pre>
 *
 * <p>The payload carries the FULL map of every online player who currently has a cape equipped
 * (players with no cape are simply omitted). The client overwrites its whole holder from each
 * broadcast — there is no incremental/delta format. This is cosmetic-only: the client maps each
 * {@code capeId} to {@code factionwarhud:textures/entity/cape/<capeId>.png} for rendering.
 *
 * <p>This is a NEW, dedicated payload — it does NOT touch the existing {@code factionwar:state}
 * {@code FactionWarStatePayload}.
 */
public record CapePayload(List<Entry> entries) implements CustomPayload {

    /** One (player → capeId) row on the wire. */
    public record Entry(UUID playerUuid, String capeId) {}

    /** Namespace {@code cheesestore}, path {@code capes}. */
    public static final CustomPayload.Id<CapePayload> ID =
            new CustomPayload.Id<>(Identifier.of("cheesestore", "capes"));

    /**
     * Manual codec. {@code PacketCodec.of(ValueFirstEncoder, PacketDecoder)} — the encoder receives
     * the <b>value first via the lambda but the buffer is the first method parameter</b> per
     * Fabric's ValueFirstEncoder ({@code accept(value, buf)}); the decoder reads the buffer.
     */
    public static final PacketCodec<RegistryByteBuf, CapePayload> CODEC =
            PacketCodec.of(CapePayload::write, CapePayload::read);

    /** Encoder: value first, buffer second (matches {@code ValueFirstEncoder#encode}). */
    private static void write(CapePayload value, RegistryByteBuf buf) {
        List<Entry> list = value.entries == null ? List.of() : value.entries;
        buf.writeVarInt(list.size());
        for (Entry e : list) {
            buf.writeUuid(e.playerUuid());
            buf.writeString(e.capeId());
        }
    }

    /** Decoder: read in the exact same order the server wrote. */
    private static CapePayload read(RegistryByteBuf buf) {
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            UUID uuid = buf.readUuid();
            String capeId = buf.readString();
            entries.add(new Entry(uuid, capeId));
        }
        return new CapePayload(entries);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
