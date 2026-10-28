package com.cheeseguard.logic;

import com.cheeseguard.data.ChunkEntry;
import com.cheeseguard.data.ChunkSet;
import com.cheeseguard.logic.ChunkCalculator.ChunkCoord;
import com.cheeseguard.logic.ChunkCalculator.RegionCoord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Handles deletion of region files with backup support
 */
public class ChunkDeleter {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    private static final int MAX_BACKUPS = 3;
    private static final Path BACKUP_DIR = Paths.get("config", "cheeseguard", "backups");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    
    private final Path worldRegionDir;
    
    public ChunkDeleter(Path worldPath) {
        this.worldRegionDir = worldPath.resolve("region");
    }
    
    /**
     * Delete all region files affected by the chunk set
     * Creates backup before deletion
     */
    public DeletionResult deleteChunks(ChunkSet chunkSet) {
        DeletionResult result = new DeletionResult();
        
        // Calculate all affected regions
        Set<ChunkCoord> affectedChunks = new HashSet<>();
        for (ChunkEntry entry : chunkSet.getChunks()) {
            affectedChunks.addAll(ChunkCalculator.getAffectedChunks(entry));
        }
        
        Set<RegionCoord> affectedRegions = ChunkCalculator.getAffectedRegions(affectedChunks);
        LOGGER.info("Deleting {} region files from set '{}'", affectedRegions.size(), chunkSet.getName());
        
        // Create backup
        Path backupPath = createBackup(affectedRegions);
        if (backupPath != null) {
            result.backupPath = backupPath;
            LOGGER.info("Created backup at {}", backupPath);
        }
        
        // Delete region files
        for (RegionCoord region : affectedRegions) {
            Path regionFile = worldRegionDir.resolve(region.getFileName());
            
            if (Files.exists(regionFile)) {
                try {
                    Files.delete(regionFile);
                    result.deletedFiles.add(region.getFileName());
                    LOGGER.info("Deleted region file: {}", region.getFileName());
                } catch (IOException e) {
                    result.failedFiles.add(region.getFileName());
                    LOGGER.error("Failed to delete region file: {}", region.getFileName(), e);
                }
            } else {
                result.notFoundFiles.add(region.getFileName());
                LOGGER.warn("Region file not found: {}", region.getFileName());
            }
        }
        
        // Rotate old backups
        rotateBackups();
        
        return result;
    }
    
    /**
     * Create backup of region files
     */
    private Path createBackup(Set<RegionCoord> regions) {
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            Path backupPath = BACKUP_DIR.resolve("backup-" + timestamp);
            Files.createDirectories(backupPath);
            
            int backedUp = 0;
            for (RegionCoord region : regions) {
                Path sourceFile = worldRegionDir.resolve(region.getFileName());
                if (Files.exists(sourceFile)) {
                    Path targetFile = backupPath.resolve(region.getFileName());
                    Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    backedUp++;
                }
            }
            
            LOGGER.info("Backed up {} region files to {}", backedUp, backupPath);
            return backupPath;
        } catch (IOException e) {
            LOGGER.error("Failed to create backup", e);
            return null;
        }
    }
    
    /**
     * Keep only the 3 most recent backups
     */
    private void rotateBackups() {
        try {
            if (!Files.exists(BACKUP_DIR)) {
                return;
            }
            
            List<Path> backups = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(BACKUP_DIR, "backup-*")) {
                for (Path backup : stream) {
                    if (Files.isDirectory(backup)) {
                        backups.add(backup);
                    }
                }
            }
            
            // Sort by modification time (newest first)
            backups.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) {
                    return 0;
                }
            });
            
            // Delete old backups
            if (backups.size() > MAX_BACKUPS) {
                for (int i = MAX_BACKUPS; i < backups.size(); i++) {
                    Path oldBackup = backups.get(i);
                    deleteDirectory(oldBackup);
                    LOGGER.info("Deleted old backup: {}", oldBackup.getFileName());
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to rotate backups", e);
        }
    }
    
    /**
     * Recursively delete a directory
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (Files.isDirectory(file)) {
                    deleteDirectory(file);
                } else {
                    Files.delete(file);
                }
            }
        }
        
        Files.delete(dir);
    }
    
    /**
     * Result of a deletion operation
     */
    public static class DeletionResult {
        public Path backupPath;
        public List<String> deletedFiles = new ArrayList<>();
        public List<String> notFoundFiles = new ArrayList<>();
        public List<String> failedFiles = new ArrayList<>();
        
        public int getTotalAttempted() {
            return deletedFiles.size() + notFoundFiles.size() + failedFiles.size();
        }
        
        public boolean hasWarnings() {
            return !notFoundFiles.isEmpty();
        }
        
        public boolean hasFailed() {
            return !failedFiles.isEmpty();
        }
    }
}
