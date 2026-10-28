package com.cheeseguard.data;

/**
 * Metadata for a saved zone state. Block data is stored separately in NBT files.
 * Used for JSON index serialization via Gson.
 */
public class ZoneState {
    private final String id;
    private String name;
    private final long createdAt;
    private final int blockCount;
    private final int entityCount;

    public ZoneState(String id, String name, long createdAt, int blockCount, int entityCount) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.blockCount = blockCount;
        this.entityCount = entityCount;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public long getCreatedAt() { return createdAt; }
    public int getBlockCount() { return blockCount; }
    public int getEntityCount() { return entityCount; }

    public void setName(String name) { this.name = name; }
}
