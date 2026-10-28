package com.cheeseguard.data;

import java.util.Objects;

/**
 * Represents a single chunk entry with its radius and optional label.
 */
public class ChunkEntry {
    private final int chunkX;
    private final int chunkZ;
    private final int radius;
    private String label;
    private long addedAt; // epoch millis

    public ChunkEntry(int chunkX, int chunkZ, int radius) {
        this(chunkX, chunkZ, radius, "");
    }

    public ChunkEntry(int chunkX, int chunkZ, int radius, String label) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.radius = radius;
        this.label = label != null ? label : "";
        this.addedAt = System.currentTimeMillis();
    }

    public long getAddedAt() { return addedAt; }
    public void setAddedAt(long t) { addedAt = t; }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public int getRadius() { return radius; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label != null ? label : ""; }

    public int getRegionX() { return chunkX >> 5; }
    public int getRegionZ() { return chunkZ >> 5; }

    public String getRegionFileName() {
        return String.format("r.%d.%d.mca", getRegionX(), getRegionZ());
    }

    public int getCenterX() { return (chunkX * 16) + 8; }
    public int getCenterZ() { return (chunkZ * 16) + 8; }

    /**
     * Parse a region file name like "r.-2.-2.mca" and return a ChunkEntry
     * centered in that region with the given radius.
     * Returns null if parsing fails.
     */
    public static ChunkEntry fromRegionFileName(String fileName, int radius, String label) {
        try {
            String name = fileName.replace(".mca", "");
            String[] parts = name.split("\\.");
            if (parts.length < 3 || !parts[0].equals("r")) return null;
            int regionX = Integer.parseInt(parts[1]);
            int regionZ = Integer.parseInt(parts[2]);
            // Center chunk of the region (each region = 32x32 chunks)
            int chunkX = regionX * 32 + 16;
            int chunkZ = regionZ * 32 + 16;
            return new ChunkEntry(chunkX, chunkZ, radius, label);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChunkEntry that = (ChunkEntry) o;
        return chunkX == that.chunkX && chunkZ == that.chunkZ && radius == that.radius;
    }

    @Override
    public int hashCode() { return Objects.hash(chunkX, chunkZ, radius); }

    @Override
    public String toString() {
        String l = label.isEmpty() ? "" : " \"" + label + "\"";
        return String.format("Chunk(%d, %d) radius=%d [%s]%s", chunkX, chunkZ, radius, getRegionFileName(), l);
    }
}
