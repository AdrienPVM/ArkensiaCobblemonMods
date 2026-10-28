package com.cheeseguard.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a named set of chunk entries
 */
public class ChunkSet {
    private final String name;
    private final List<ChunkEntry> chunks;
    
    public ChunkSet(String name) {
        this.name = name;
        this.chunks = new ArrayList<>();
    }
    
    public String getName() {
        return name;
    }
    
    public List<ChunkEntry> getChunks() {
        return chunks;
    }
    
    public void addChunk(ChunkEntry entry) {
        chunks.add(entry);
    }
    
    public boolean removeChunk(ChunkEntry entry) {
        return chunks.remove(entry);
    }
    
    public int size() {
        return chunks.size();
    }
    
    public boolean isEmpty() {
        return chunks.isEmpty();
    }
    
    /**
     * Get total number of affected chunks (including radius expansion)
     */
    public int getTotalAffectedChunks() {
        return chunks.stream()
                .mapToInt(entry -> {
                    int diameter = (entry.getRadius() * 2) + 1;
                    return diameter * diameter;
                })
                .sum();
    }
    
    @Override
    public String toString() {
        return String.format("ChunkSet[%s]: %d entries (%d total chunks)", 
                           name, chunks.size(), getTotalAffectedChunks());
    }
}
