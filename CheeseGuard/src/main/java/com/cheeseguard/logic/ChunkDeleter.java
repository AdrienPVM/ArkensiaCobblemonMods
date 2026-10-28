package com.cheeseguard.logic;

import com.cheeseguard.data.ChunkSet;
import com.cheeseguard.logic.ChunkCalculator.ChunkCoord;
import com.cheeseguard.logic.ChunkCalculator.RegionCoord;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Handles deletion of region files with mandatory backup and integrity verification.
 * <p>
 * Safety guarantees:
 * <ul>
 *   <li>Backup is MANDATORY — deletion aborts if backup fails or integrity check fails</li>
 *   <li>Each backup includes a manifest.json with metadata for manual recovery</li>
 *   <li>File size verification after each copy ensures backup integrity</li>
 *   <li>Keeps last 5 backups with rotation</li>
 *   <li>Partial failures are tracked — successful deletes are not rolled back</li>
 * </ul>
 */
public class ChunkDeleter {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    private static final int MAX_BACKUPS = 5;
    private static final Path BACKUP_DIR = Paths.get("config", "cheeseguard", "backups");
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Path worldRegionDir;

    public ChunkDeleter(Path worldPath) {
        this.worldRegionDir = worldPath.resolve("region");
    }

    /**
     * Create a ChunkDeleter targeting a specific dimension's region folder.
     * <ul>
     *   <li>{@code minecraft:overworld} → {@code <world>/region/}</li>
     *   <li>{@code minecraft:the_nether} → {@code <world>/DIM-1/region/}</li>
     *   <li>{@code minecraft:the_end} → {@code <world>/DIM1/region/}</li>
     *   <li>{@code namespace:path} → {@code <world>/dimensions/namespace/path/region/}</li>
     * </ul>
     */
    public static ChunkDeleter forDimension(Path worldPath, String dimensionId) {
        Path dimPath = resolveDimensionPath(worldPath, dimensionId);
        ChunkDeleter d = new ChunkDeleter(worldPath);
        // Override the region dir with the correct dimension-specific path
        return new ChunkDeleter(dimPath);
    }

    /**
     * Resolve the base directory for a dimension (without /region/ suffix).
     */
    public static Path resolveDimensionPath(Path worldPath, String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty() || dimensionId.equals("minecraft:overworld")) {
            return worldPath;
        }
        if (dimensionId.equals("minecraft:the_nether")) {
            return worldPath.resolve("DIM-1");
        }
        if (dimensionId.equals("minecraft:the_end")) {
            return worldPath.resolve("DIM1");
        }
        // Custom dimensions: namespace:path → dimensions/namespace/path/
        int colonIdx = dimensionId.indexOf(':');
        if (colonIdx > 0) {
            String namespace = dimensionId.substring(0, colonIdx);
            String path = dimensionId.substring(colonIdx + 1);
            return worldPath.resolve("dimensions").resolve(namespace).resolve(path);
        }
        // Fallback: treat as overworld
        return worldPath;
    }

    /**
     * Delete all region files affected by the chunk set.
     * Creates a verified backup before deletion. Aborts entirely if backup fails.
     */
    public DeletionResult deleteChunks(ChunkSet chunkSet) {
        DeletionResult result = new DeletionResult();
        result.setName = chunkSet.getName();

        // 1. Calculate affected regions
        Set<ChunkCoord> affectedChunks = ChunkCalculator.getAffectedChunks(chunkSet);
        Set<RegionCoord> affectedRegions = ChunkCalculator.getAffectedRegions(affectedChunks);
        result.totalChunks = affectedChunks.size();
        result.totalRegions = affectedRegions.size();

        LOGGER.info("[CG] Chunk reset '{}': {} regions, {} chunks, regionDir={}",
                chunkSet.getName(), affectedRegions.size(), affectedChunks.size(), worldRegionDir);

        if (affectedRegions.isEmpty()) {
            result.status = "NO_REGIONS";
            return result;
        }

        // 2. Identify existing region files
        List<RegionCoord> existingRegions = new ArrayList<>();
        for (RegionCoord region : affectedRegions) {
            Path regionFile = worldRegionDir.resolve(region.getFileName());
            if (Files.exists(regionFile)) {
                existingRegions.add(region);
            } else {
                result.notFoundFiles.add(region.getFileName());
            }
        }

        if (existingRegions.isEmpty()) {
            LOGGER.warn("[CG] Chunk reset '{}': no region files found on disk", chunkSet.getName());
            result.status = "NO_FILES_FOUND";
            return result;
        }

        // 3. Create verified backup (MANDATORY — abort if fails)
        Path backupPath;
        try {
            backupPath = createVerifiedBackup(chunkSet.getName(), existingRegions);
        } catch (IOException e) {
            LOGGER.error("[CG] ABORTING chunk reset '{}': backup failed — {}", chunkSet.getName(), e.getMessage());
            result.status = "BACKUP_FAILED";
            result.error = "Backup failed: " + e.getMessage();
            return result;
        }
        result.backupPath = backupPath;

        // 4. Delete region files
        for (RegionCoord region : existingRegions) {
            Path regionFile = worldRegionDir.resolve(region.getFileName());
            try {
                Files.delete(regionFile);
                result.deletedFiles.add(region.getFileName());
                LOGGER.info("[CG] Deleted region file: {}", region.getFileName());
            } catch (IOException e) {
                result.failedFiles.add(region.getFileName());
                LOGGER.error("[CG] Failed to delete {}: {}", region.getFileName(), e.getMessage());
            }
        }

        // 5. Rotate old backups (keep MAX_BACKUPS most recent)
        rotateBackups();

        result.status = result.failedFiles.isEmpty() ? "SUCCESS" : "PARTIAL";
        LOGGER.info("[CG] Chunk reset '{}' complete: {} deleted, {} failed, {} not found. Backup: {}",
                chunkSet.getName(), result.deletedFiles.size(), result.failedFiles.size(),
                result.notFoundFiles.size(), backupPath.getFileName());

        return result;
    }

    /**
     * Create backup with integrity verification and manifest.
     * Throws IOException if backup cannot be completed — caller MUST abort deletion.
     */
    private Path createVerifiedBackup(String setName, List<RegionCoord> regions) throws IOException {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String safeName = setName.replaceAll("[^a-zA-Z0-9_-]", "_");
        Path backupPath = BACKUP_DIR.resolve(safeName + "-" + timestamp);
        Files.createDirectories(backupPath);

        List<JsonObject> fileEntries = new ArrayList<>();
        int backedUp = 0;

        for (RegionCoord region : regions) {
            Path sourceFile = worldRegionDir.resolve(region.getFileName());
            Path targetFile = backupPath.resolve(region.getFileName());

            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);

            // Integrity check: file sizes must match
            long sourceSize = Files.size(sourceFile);
            long targetSize = Files.size(targetFile);
            if (sourceSize != targetSize) {
                deleteDirectory(backupPath);
                throw new IOException("Integrity check failed for " + region.getFileName()
                        + ": source=" + sourceSize + "B, backup=" + targetSize + "B");
            }

            JsonObject entry = new JsonObject();
            entry.addProperty("file", region.getFileName());
            entry.addProperty("sizeBytes", sourceSize);
            entry.addProperty("regionX", region.x);
            entry.addProperty("regionZ", region.z);
            fileEntries.add(entry);
            backedUp++;
        }

        // Write manifest for manual recovery
        JsonObject manifest = new JsonObject();
        manifest.addProperty("setName", setName);
        manifest.addProperty("timestamp", timestamp);
        manifest.addProperty("regionDir", worldRegionDir.toString());
        manifest.addProperty("fileCount", backedUp);
        manifest.addProperty("recoveryInstructions",
                "To restore: copy all .mca files from this directory back into " + worldRegionDir);
        JsonArray filesArr = new JsonArray();
        fileEntries.forEach(filesArr::add);
        manifest.add("files", filesArr);

        Files.writeString(backupPath.resolve("manifest.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(manifest));

        LOGGER.info("[CG] Backup verified: {} files ({}) -> {}",
                backedUp, formatSize(fileEntries), backupPath);
        return backupPath;
    }

    /** Format total size from manifest entries for logging */
    private String formatSize(List<JsonObject> entries) {
        long total = entries.stream().mapToLong(e -> e.get("sizeBytes").getAsLong()).sum();
        if (total < 1024) return total + "B";
        if (total < 1024 * 1024) return String.format("%.1fKB", total / 1024.0);
        return String.format("%.1fMB", total / (1024.0 * 1024.0));
    }

    /** Keep only the MAX_BACKUPS most recent backup directories */
    private void rotateBackups() {
        try {
            if (!Files.exists(BACKUP_DIR)) return;

            List<Path> backups = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(BACKUP_DIR)) {
                for (Path p : stream) {
                    if (Files.isDirectory(p) && Files.exists(p.resolve("manifest.json"))) {
                        backups.add(p);
                    }
                }
            }

            // Sort newest first
            backups.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) { return 0; }
            });

            // Delete oldest beyond limit
            for (int i = MAX_BACKUPS; i < backups.size(); i++) {
                Path old = backups.get(i);
                deleteDirectory(old);
                LOGGER.info("[CG] Rotated old backup: {}", old.getFileName());
            }
        } catch (IOException e) {
            LOGGER.error("[CG] Backup rotation failed: {}", e.getMessage());
        }
    }

    /** Recursively delete a directory */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
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

    // ── Result ──────────────────────────────────────────────────────────────

    /** Result of a chunk deletion operation */
    public static class DeletionResult {
        public String setName;
        /** SUCCESS | PARTIAL | BACKUP_FAILED | NO_REGIONS | NO_FILES_FOUND */
        public String status;
        public String error;
        public Path backupPath;
        public int totalChunks;
        public int totalRegions;
        public final List<String> deletedFiles = new ArrayList<>();
        public final List<String> notFoundFiles = new ArrayList<>();
        public final List<String> failedFiles = new ArrayList<>();

        public boolean isSuccess() { return "SUCCESS".equals(status); }
        public boolean isPartial() { return "PARTIAL".equals(status); }
        public boolean isFailed() { return "BACKUP_FAILED".equals(status); }

        /** Serialize to JSON for API responses */
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("setName", setName);
            o.addProperty("status", status);
            if (error != null) o.addProperty("error", error);
            o.addProperty("backupDir", backupPath != null ? backupPath.getFileName().toString() : null);
            o.addProperty("totalChunks", totalChunks);
            o.addProperty("totalRegions", totalRegions);
            o.addProperty("deleted", deletedFiles.size());
            o.addProperty("notFound", notFoundFiles.size());
            o.addProperty("failed", failedFiles.size());

            JsonArray del = new JsonArray();
            deletedFiles.forEach(del::add);
            o.add("deletedFiles", del);

            JsonArray nf = new JsonArray();
            notFoundFiles.forEach(nf::add);
            o.add("notFoundFiles", nf);

            JsonArray fl = new JsonArray();
            failedFiles.forEach(fl::add);
            o.add("failedFiles", fl);

            o.addProperty("needsRestart", !deletedFiles.isEmpty());
            return o;
        }
    }
}
