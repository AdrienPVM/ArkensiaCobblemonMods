package com.cheeseguard.logic;

import com.cheeseguard.data.ChunkEntry;

import java.util.HashSet;
import java.util.Set;

/**
 * Calculates which chunks and region files are affected by a chunk entry
 */
public class ChunkCalculator {
    
    /**
     * Get all chunks affected by a chunk entry (including radius)
     * Returns a square of chunks centered on the entry's chunk
     */
    public static Set<ChunkCoord> getAffectedChunks(ChunkEntry entry) {
        Set<ChunkCoord> chunks = new HashSet<>();
        
        int centerX = entry.getChunkX();
        int centerZ = entry.getChunkZ();
        int radius = entry.getRadius();
        
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                chunks.add(new ChunkCoord(x, z));
            }
        }
        
        return chunks;
    }

    /** Convenience overload: get all affected chunks for an entire ChunkSet */
    public static Set<ChunkCoord> getAffectedChunks(com.cheeseguard.data.ChunkSet set) {
        Set<ChunkCoord> result = new HashSet<>();
        for (com.cheeseguard.data.ChunkEntry e : set.getChunks()) result.addAll(getAffectedChunks(e));
        return result;
    }
    
    /**
     * Get all region files that contain chunks from the given set
     */
    public static Set<RegionCoord> getAffectedRegions(Set<ChunkCoord> chunks) {
        Set<RegionCoord> regions = new HashSet<>();
        
        for (ChunkCoord chunk : chunks) {
            int regionX = chunk.x >> 5;  // Divide by 32
            int regionZ = chunk.z >> 5;
            regions.add(new RegionCoord(regionX, regionZ));
        }
        
        return regions;
    }
    
    /**
     * Parse chunk coordinates from region file name (e.g., "r.-1.-10.mca")
     * Returns the chunk coordinates of the first chunk in that region
     */
    public static ChunkCoord parseRegionFileName(String regionFileName) {
        // Remove "r." prefix and ".mca" suffix
        String coords = regionFileName.substring(2, regionFileName.length() - 4);
        String[] parts = coords.split("\\.");
        
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid region file name: " + regionFileName);
        }
        
        int regionX = Integer.parseInt(parts[0]);
        int regionZ = Integer.parseInt(parts[1]);
        
        // Convert region coords to chunk coords (multiply by 32)
        return new ChunkCoord(regionX * 32, regionZ * 32);
    }
    
    /**
     * Represents chunk coordinates
     */
    public static class ChunkCoord {
        public final int x;
        public final int z;
        
        public ChunkCoord(int x, int z) {
            this.x = x;
            this.z = z;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChunkCoord that = (ChunkCoord) o;
            return x == that.x && z == that.z;
        }
        
        @Override
        public int hashCode() {
            return 31 * x + z;
        }
        
        @Override
        public String toString() {
            return String.format("(%d, %d)", x, z);
        }
    }
    
    /**
     * Represents region file coordinates
     */
    public static class RegionCoord {
        public final int x;
        public final int z;
        
        public RegionCoord(int x, int z) {
            this.x = x;
            this.z = z;
        }
        
        public String getFileName() {
            return String.format("r.%d.%d.mca", x, z);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RegionCoord that = (RegionCoord) o;
            return x == that.x && z == that.z;
        }
        
        @Override
        public int hashCode() {
            return 31 * x + z;
        }
        
        @Override
        public String toString() {
            return getFileName();
        }
    }
}
