package com.roicheese.eggandcheese.network;

import com.roicheese.eggandcheese.data.CollectibleEntry;
import com.roicheese.eggandcheese.data.EggRegistryData;
import com.roicheese.eggandcheese.dna.CollectibleDNA;
import com.roicheese.eggandcheese.dna.CollectibleType;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Network packets for EggAndCheese.
 * Admin GUI packets removed — all admin actions are now via CheeseDash HTTP API.
 * Only the S2C SyncRegistryPayload remains for syncing render config to clients.
 */
public class ModPackets {

    public static void registerServerHandlers() {
        // No C2S handlers — all admin actions are via CheeseDash dashboard
    }

    // ── S2C senders ──────────────────────────────────────────────────────────

    public static void sendRegistrySync(ServerPlayerEntity player, EggRegistryData registry) {
        PacketByteBuf buf = PacketByteBufs.create();
        writeRegistryToBuf(buf, registry);
        ServerPlayNetworking.send(player, new ModPayloads.SyncRegistryPayload(ModPayloads.toBytes(buf)));
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    public static void writeRegistryToBuf(PacketByteBuf buf, EggRegistryData registry) {
        List<CollectibleEntry> entries = registry.getEntriesSorted();
        buf.writeInt(entries.size());
        for (CollectibleEntry e : entries) writeEntryToBuf(buf, e);
        // FTB settings appended
        buf.writeBoolean(registry.isFtbQuestsEnabled());
        buf.writeString(registry.getFtbQuestsChapter(), 128);
        // Render distance config
        buf.writeInt(registry.getTextureRenderDistance());
    }

    public static void writeEntryToBuf(PacketByteBuf buf, CollectibleEntry entry) {
        buf.writeString(entry.getId(), 64);
        buf.writeString(entry.getType().getId(), 16);
        buf.writeNbt(entry.getDna().toNbt());
        buf.writeBlockPos(entry.getPos());
        buf.writeString(entry.getDimension(), 256);
        buf.writeFloat(entry.getScale());
        buf.writeInt(entry.getDiscoveredBy().size());
        buf.writeBoolean(entry.isPositionLost());
        buf.writeString(entry.getTitle(), 256);
        buf.writeString(entry.getSubtitle(), 512);
    }

    /** @return [0]=entries list, [1]=Boolean ftbEnabled, [2]=String ftbChapter, [3]=Integer textureRenderDist */
    public static Object[] readRegistryFromBufFull(PacketByteBuf buf) {
        int count = buf.readInt();
        List<CollectibleEntry> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(readEntryFromBuf(buf));
        boolean ftbEnabled = buf.readBoolean();
        String ftbChapter  = buf.readString(128);
        int textureRenderDist = buf.readableBytes() >= 4 ? buf.readInt() : 10;
        return new Object[]{list, ftbEnabled, ftbChapter, textureRenderDist};
    }

    public static CollectibleEntry readEntryFromBuf(PacketByteBuf buf) {
        String id            = buf.readString(64);
        CollectibleType type = CollectibleType.fromId(buf.readString(16));
        CollectibleDNA dna   = CollectibleDNA.fromNbt(buf.readNbt());
        BlockPos pos         = buf.readBlockPos();
        String dim           = buf.readString(256);
        float scale          = buf.readFloat();
        int discoveries      = buf.readInt();
        boolean posLost      = buf.readBoolean();
        String title         = buf.readString(256);
        String subtitle      = buf.readString(512);
        CollectibleEntry entry = new CollectibleEntry(id, type, dna, pos, dim);
        entry.setScale(scale);
        entry.setPositionLost(posLost);
        entry.setTitle(title);
        entry.setSubtitle(subtitle);
        return entry;
    }
}
