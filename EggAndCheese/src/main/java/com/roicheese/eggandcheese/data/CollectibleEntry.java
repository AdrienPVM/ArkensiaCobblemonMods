package com.roicheese.eggandcheese.data;

import com.roicheese.eggandcheese.dna.CollectibleDNA;
import com.roicheese.eggandcheese.dna.CollectibleType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class CollectibleEntry {
    private String id;
    private final CollectibleType type;
    private CollectibleDNA dna;
    private BlockPos pos;
    private String dimension;
    private float scale = 1.0f;
    private boolean positionLost = false;
    private String title    = "";   // used as FTB quest title
    private String subtitle = "";   // used as FTB quest description
    private int renderDistanceOverride = -1;  // -1 = use global, >= 0 = custom distance
    private long ftbQuestId = 0L;  // FTB Quests quest ID (0 = no linked quest)
    private final Set<UUID> discoveredBy   = new HashSet<>();
    private final Set<UUID> decoReceivedBy = new HashSet<>();  // one deco per player

    public CollectibleEntry(String id, CollectibleType type, CollectibleDNA dna, BlockPos pos, String dimension) {
        this.id = id; this.type = type; this.dna = dna; this.pos = pos; this.dimension = dimension;
    }

    // ── Serialization ────────────────────────────────────────────────────────
    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", id);
        nbt.putString("type", type.getId());
        nbt.put("dna", dna.toNbt());
        nbt.putLong("pos", pos.asLong());
        nbt.putString("dimension", dimension);
        nbt.putFloat("scale", scale);
        nbt.putBoolean("positionLost", positionLost);
        nbt.putString("title", title);
        nbt.putString("subtitle", subtitle);
        nbt.putInt("renderDistanceOverride", renderDistanceOverride);
        nbt.putLong("ftbQuestId", ftbQuestId);
        NbtList disc = new NbtList();
        for (UUID u : discoveredBy) disc.add(NbtString.of(u.toString()));
        nbt.put("discoveredBy", disc);
        NbtList deco = new NbtList();
        for (UUID u : decoReceivedBy) deco.add(NbtString.of(u.toString()));
        nbt.put("decoReceivedBy", deco);
        return nbt;
    }

    public static CollectibleEntry fromNbt(NbtCompound nbt) {
        String id   = nbt.getString("id");
        CollectibleType type = CollectibleType.fromId(nbt.getString("type"));
        CollectibleDNA dna   = CollectibleDNA.fromNbt(nbt.getCompound("dna"));
        BlockPos pos  = BlockPos.fromLong(nbt.getLong("pos"));
        String dim   = nbt.getString("dimension");
        CollectibleEntry e = new CollectibleEntry(id, type, dna, pos, dim);
        e.scale        = nbt.getFloat("scale");
        e.positionLost = nbt.contains("positionLost") && nbt.getBoolean("positionLost");
        e.title        = nbt.contains("title")    ? nbt.getString("title")    : "";
        e.subtitle     = nbt.contains("subtitle") ? nbt.getString("subtitle") : "";
        e.renderDistanceOverride = nbt.contains("renderDistanceOverride") ? nbt.getInt("renderDistanceOverride") : -1;
        e.ftbQuestId = nbt.contains("ftbQuestId") ? nbt.getLong("ftbQuestId") : 0L;
        for (var el : nbt.getList("discoveredBy", 8)) {
            try { e.discoveredBy.add(UUID.fromString(el.asString())); } catch (Exception ignored) {}
        }
        for (var el : nbt.getList("decoReceivedBy", 8)) {
            try { e.decoReceivedBy.add(UUID.fromString(el.asString())); } catch (Exception ignored) {}
        }
        return e;
    }

    // ── Getters / setters ────────────────────────────────────────────────────
    public String          getId()              { return id; }
    public void            setId(String id)     { this.id = id; }
    public CollectibleType getType()            { return type; }
    public CollectibleDNA  getDna()             { return dna; }
    public void            setDna(CollectibleDNA d) { this.dna = d; }
    public BlockPos        getPos()             { return pos; }
    public void            setPos(BlockPos pos) { this.pos = pos; }
    public String          getDimension()       { return dimension; }
    public void            setDimension(String d){ this.dimension = d; }
    public float           getScale()           { return scale; }
    public void            setScale(float s)    { this.scale = Math.max(0.1f, Math.min(4.0f, s)); }
    public boolean         isPositionLost()     { return positionLost; }
    public void            setPositionLost(boolean l){ this.positionLost = l; }
    public String          getTitle()           { return title; }
    public void            setTitle(String t)   { this.title = t == null ? "" : t; }
    public String          getSubtitle()        { return subtitle; }
    public void            setSubtitle(String s){ this.subtitle = s == null ? "" : s; }
    public int             getRenderDistanceOverride() { return renderDistanceOverride; }
    public void            setRenderDistanceOverride(int v) { this.renderDistanceOverride = v; }
    public long            getFtbQuestId()           { return ftbQuestId; }
    public void            setFtbQuestId(long id)    { this.ftbQuestId = id; }
    public Set<UUID>       getDiscoveredBy()    { return discoveredBy; }
    public boolean         hasDiscovered(UUID u){ return discoveredBy.contains(u); }
    public void            addDiscovery(UUID u) { discoveredBy.add(u); }
    public void            clearDiscoveries()   { discoveredBy.clear(); }
    public Set<UUID>       getDecoReceivedBy()  { return decoReceivedBy; }
    public boolean         hasReceivedDeco(UUID u){ return decoReceivedBy.contains(u); }
    public void            markDecoReceived(UUID u){ decoReceivedBy.add(u); }
}
