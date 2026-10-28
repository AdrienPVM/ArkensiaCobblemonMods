package com.cheesemusic.logic;

import com.cheesemusic.CheeseMusicMod;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Scans the CheeseMusic resource pack directory for available .ogg tracks.
 * Auto-generates sounds.json so Minecraft clients can resolve the sound IDs.
 *
 * Minecraft Identifier rules: only [a-z0-9/._-] allowed.
 * Files are auto-renamed to valid identifiers on scan.
 */
public class SoundScanner {

    private final Path serverDir;
    private final List<String> availableTracks = new ArrayList<>();

    public SoundScanner(Path serverDir) {
        this.serverDir = serverDir;
    }

    public Path getSoundsDir() {
        return serverDir.resolve("resourcepacks/CheeseMusic/assets/cheesemusic/sounds");
    }

    public Path getResourcePackRoot() {
        return serverDir.resolve("resourcepacks/CheeseMusic");
    }

    /**
     * Sanitize a filename to a valid Minecraft identifier.
     * Lowercase, replace spaces/special chars with underscores, strip invalid chars.
     */
    public static String sanitize(String name) {
        String s = name.toLowerCase(Locale.ROOT)
            .replace(' ', '_')
            .replace('—', '-')
            .replace('–', '-')
            .replace(',', '_');
        // Keep only [a-z0-9/._-]
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        // Collapse multiple underscores
        String result = sb.toString().replaceAll("_+", "_");
        // Strip leading/trailing underscores
        result = result.replaceAll("^_+|_+$", "");
        return result.isEmpty() ? "track" : result;
    }

    public void scan() {
        availableTracks.clear();

        Path soundsDir = getSoundsDir();
        if (!Files.isDirectory(soundsDir)) {
            try {
                Files.createDirectories(soundsDir);
                Path rpRoot = getResourcePackRoot();
                Path mcmeta = rpRoot.resolve("pack.mcmeta");
                if (!Files.exists(mcmeta)) {
                    Files.writeString(mcmeta, "{\"pack\":{\"pack_format\":34,\"description\":\"CheeseMusic — Custom zone music\"}}");
                }
                CheeseMusicMod.LOGGER.info("[CheeseMusic] Created resource pack at: {}", rpRoot);
                CheeseMusicMod.LOGGER.info("[CheeseMusic] Place .ogg files in: {}", soundsDir);
            } catch (IOException e) {
                CheeseMusicMod.LOGGER.error("[CheeseMusic] Failed to create resource pack directory: {}", e.getMessage());
            }
            regenerateSoundsJson();
            return;
        }

        // Scan and auto-rename files with invalid names
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(soundsDir, "*.ogg")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String rawName = fileName.substring(0, fileName.length() - 4);
                String sanitized = sanitize(rawName);

                // Rename file if needed
                if (!sanitized.equals(rawName)) {
                    Path newPath = soundsDir.resolve(sanitized + ".ogg");
                    // Avoid overwriting
                    if (Files.exists(newPath) && !newPath.equals(file)) {
                        int suffix = 2;
                        while (Files.exists(soundsDir.resolve(sanitized + "_" + suffix + ".ogg"))) suffix++;
                        sanitized = sanitized + "_" + suffix;
                        newPath = soundsDir.resolve(sanitized + ".ogg");
                    }
                    Files.move(file, newPath, StandardCopyOption.REPLACE_EXISTING);
                    CheeseMusicMod.LOGGER.info("[CheeseMusic] Renamed '{}' -> '{}.ogg'", fileName, sanitized);
                }

                availableTracks.add(sanitized);
            }
        } catch (IOException e) {
            CheeseMusicMod.LOGGER.error("[CheeseMusic] Failed to scan sounds directory: {}", e.getMessage());
        }

        Collections.sort(availableTracks);
        regenerateSoundsJson();
        CheeseMusicMod.LOGGER.info("[CheeseMusic] Found {} tracks in resource pack", availableTracks.size());
    }

    /**
     * Add a track file — sanitizes the filename, saves, and rescans.
     * @return the sanitized track name
     */
    public String addTrack(String originalFileName, byte[] data) throws IOException {
        String rawName = originalFileName.endsWith(".ogg")
            ? originalFileName.substring(0, originalFileName.length() - 4) : originalFileName;
        String sanitized = sanitize(rawName);

        Path soundsDir = getSoundsDir();
        Files.createDirectories(soundsDir);
        Path target = soundsDir.resolve(sanitized + ".ogg");
        Files.write(target, data);
        scan();
        return sanitized;
    }

    /**
     * Delete a track file and rescan.
     */
    public void deleteTrack(String trackName) throws IOException {
        Path file = getSoundsDir().resolve(trackName + ".ogg");
        if (Files.exists(file)) {
            Files.delete(file);
        }
        scan();
    }

    /**
     * Auto-generate sounds.json from available tracks.
     */
    private void regenerateSoundsJson() {
        try {
            Path soundsJson = getResourcePackRoot().resolve("assets/cheesemusic/sounds.json");
            Files.createDirectories(soundsJson.getParent());

            StringBuilder sb = new StringBuilder("{\n");
            for (int i = 0; i < availableTracks.size(); i++) {
                String track = availableTracks.get(i);
                sb.append("  \"").append(track).append("\": {\n");
                sb.append("    \"sounds\": [{\n");
                sb.append("      \"name\": \"cheesemusic:").append(track).append("\",\n");
                sb.append("      \"stream\": true\n");
                sb.append("    }]\n");
                sb.append("  }");
                if (i < availableTracks.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("}\n");

            Files.writeString(soundsJson, sb.toString());
        } catch (IOException e) {
            CheeseMusicMod.LOGGER.error("[CheeseMusic] Failed to regenerate sounds.json: {}", e.getMessage());
        }
    }

    public List<String> getAvailableTracks() {
        return Collections.unmodifiableList(availableTracks);
    }
}
