package com.roicheese.eggandcheese.data;

import com.roicheese.eggandcheese.dna.CollectibleDNA;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;

import java.util.*;

public class EggRegistryData extends PersistentState {
    private static final String DATA_KEY = "eggandcheese_registry";
    private final Map<String, CollectibleEntry> entries = new LinkedHashMap<>();

    // FTB Quests integration settings
    private boolean ftbQuestsEnabled = false;
    private String  ftbQuestsChapter = "oeufs";
    private boolean ftbAutoSync      = false;

    // Render distance: blocks at which full textures are shown (0 = always)
    private int textureRenderDistance = 20;

    public EggRegistryData() {}

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        NbtList list = new NbtList();
        for (CollectibleEntry entry : entries.values()) list.add(entry.toNbt());
        nbt.put("entries", list);
        // Config fields
        nbt.putBoolean("ftbQuestsEnabled", ftbQuestsEnabled);
        nbt.putString("ftbQuestsChapter", ftbQuestsChapter);
        nbt.putBoolean("ftbAutoSync", ftbAutoSync);
        nbt.putInt("textureRenderDistance", textureRenderDistance);
        return nbt;
    }

    public static EggRegistryData readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        EggRegistryData data = new EggRegistryData();
        NbtList list = nbt.getList("entries", 10);
        for (int i = 0; i < list.size(); i++) {
            CollectibleEntry entry = CollectibleEntry.fromNbt(list.getCompound(i));
            data.entries.put(entry.getId(), entry);
        }
        // Config fields
        if (nbt.contains("ftbQuestsEnabled")) data.ftbQuestsEnabled = nbt.getBoolean("ftbQuestsEnabled");
        if (nbt.contains("ftbQuestsChapter")) data.ftbQuestsChapter = nbt.getString("ftbQuestsChapter");
        if (nbt.contains("ftbAutoSync"))      data.ftbAutoSync      = nbt.getBoolean("ftbAutoSync");
        if (nbt.contains("textureRenderDistance")) data.textureRenderDistance = nbt.getInt("textureRenderDistance");
        return data;
    }

    private static final Type<EggRegistryData> TYPE = new Type<>(
        EggRegistryData::new, EggRegistryData::readNbt, null
    );

    public static EggRegistryData get(MinecraftServer server) {
        PersistentStateManager manager = server.getWorld(World.OVERWORLD).getPersistentStateManager();
        return manager.getOrCreate(TYPE, DATA_KEY);
    }

    // ── ID management ─────────────────────────────────────────────────────────

    /** Generate egg_001, egg_002... ensuring uniqueness. */
    public String generateUniqueId() {
        int n = 1;
        String id;
        do { id = String.format("egg_%03d", n++); }
        while (entries.containsKey(id));
        return id;
    }

    /** Rename an entry. Returns false if newId is already taken by a different entry. */
    public boolean renameEntry(String oldId, String newId) {
        if (!entries.containsKey(oldId)) return false;
        if (!newId.equals(oldId) && entries.containsKey(newId)) return false;
        if (newId.equals(oldId)) return true; // no-op

        CollectibleEntry old = entries.remove(oldId);
        old.setId(newId);
        entries.put(newId, old);
        markDirty();
        return true;
    }

    public boolean hasId(String id)                         { return entries.containsKey(id); }
    public void addEntry(CollectibleEntry entry)             { entries.put(entry.getId(), entry); markDirty(); }
    public void removeEntry(String id)                       { entries.remove(id); markDirty(); }
    public Optional<CollectibleEntry> getEntry(String id)    { return Optional.ofNullable(entries.get(id)); }
    public Collection<CollectibleEntry> getAllEntries()       { return Collections.unmodifiableCollection(entries.values()); }

    public List<CollectibleEntry> getEntriesSorted() {
        List<CollectibleEntry> list = new ArrayList<>(entries.values());
        list.sort(Comparator.comparing(CollectibleEntry::getId));
        return list;
    }


    public boolean isFtbQuestsEnabled()              { return ftbQuestsEnabled; }
    public void    setFtbQuestsEnabled(boolean v)    { this.ftbQuestsEnabled = v; }
    public String  getFtbQuestsChapter()             { return ftbQuestsChapter; }
    public void    setFtbQuestsChapter(String v)     { this.ftbQuestsChapter = v == null ? "oeufs" : v; }
    public boolean isFtbAutoSync()                   { return ftbAutoSync; }
    public void    setFtbAutoSync(boolean v)         { this.ftbAutoSync = v; }
    public int     getTextureRenderDistance()         { return textureRenderDistance; }
    public void    setTextureRenderDistance(int v)    { this.textureRenderDistance = Math.max(0, v); }

    public int getTotalDiscoveries() {
        return entries.values().stream().mapToInt(e -> e.getDiscoveredBy().size()).sum();
    }
}
