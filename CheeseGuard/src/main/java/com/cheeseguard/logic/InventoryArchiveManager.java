package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-and-restore helper for the permanent inventory archives stored in
 * {@code config/cheeseguard_inv_archive/}. These archives are written by
 * {@code EventManager.archiveInventoryBackup} every time a player's inventory
 * is saved (event start, prison admit, etc.) and are NEVER cleaned up
 * automatically — they are the admin's last-resort recovery channel.
 *
 * <h3>Archive file format (writer at {@code EventManager.savePendingRestore})</h3>
 *
 * <pre>
 * filename: {uuid8}_{yyyy-MM-dd_HH-mm-ss}_{playerName}.nbt   (gzip-compressed NBT)
 * root:
 *   playerName: string
 *   savedX/Y/Z: double                — position at archive time
 *   savedWorld: string
 *   format: int                       — 3 = full player.writeNbt() snapshot
 *   originalGameMode: string?         — optional, set if player was a spectator etc.
 *   playerData: NbtCompound           — the raw player.writeNbt() snapshot;
 *                                        same shape as world/playerdata/{uuid}.nbt
 * </pre>
 *
 * <h3>Threading invariant</h3>
 *
 * <p>All public methods MUST be called on the Minecraft server thread.
 * Brigadier dispatches our commands there, and {@code EventManager.archiveInventoryBackup}
 * (the writer) also runs on the server thread, so reader and writer cannot
 * concurrently touch the same file. If anyone ever moves either side
 * off-thread, NBT corruption becomes possible.</p>
 *
 * <h3>Restore paths</h3>
 *
 * <ul>
 *   <li><b>Online player</b>: {@link #restoreOnline} — closes any open
 *       container screen, applies via {@code player.readNbt()}, then re-applies
 *       current health/hunger/XP/position/gamemode (only the inventory and
 *       inventory-adjacent state are restored).</li>
 *   <li><b>Offline player</b>: {@link #restoreOffline} — overwrites
 *       {@code world/playerdata/{full-uuid}.nbt} after taking a safety copy
 *       and sanitising the archived dimension/position so the player spawns
 *       safely in the overworld at their archived position.</li>
 * </ul>
 *
 * <p>Both paths log to the CheeseGuard logger with the actor + archive file +
 * target UUID so any restore is traceable in {@code logs/latest.log}.</p>
 */
public final class InventoryArchiveManager {

    /** Format version we support reading. Writer is at {@code EventManager.savePendingRestore}. */
    public static final int SUPPORTED_FORMAT = 3;

    private static final Pattern FILENAME = Pattern.compile(
            "^([0-9a-f]{8})_(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})_(.+)\\.nbt$");

    private InventoryArchiveManager() {}

    public static Path getArchiveDir(MinecraftServer server) {
        return server.getRunDirectory().resolve("config").resolve("cheeseguard_inv_archive");
    }

    /**
     * Resolve a player's UUID. Tries (1) online player, (2) the server's user
     * cache, (3) scanning archive filenames for a matching playerName and
     * reading the full UUID from the archived {@code playerData}.
     *
     * <p>Step (3) is critical for cross-server scenarios: a player who has
     * archives on server A but never logged into server B can still be
     * restored on B because the archive itself contains their full UUID.</p>
     */
    public static Optional<UUID> resolvePlayerUuid(MinecraftServer server, String playerName) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerName);
        if (online != null) return Optional.of(online.getUuid());

        if (server.getUserCache() != null) {
            var profile = server.getUserCache().findByName(playerName);
            if (profile.isPresent()) return Optional.of(profile.get().getId());
        }

        // Fallback: scan archives whose filename playerName matches and pull the UUID
        // from the archived playerData itself. Most authoritative source.
        Path dir = getArchiveDir(server);
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path candidate : stream.toList()) {
                ArchiveFile a = ArchiveFile.parse(candidate);
                if (a == null) continue;
                if (!a.playerName.equalsIgnoreCase(playerName)) continue;
                try {
                    UUID id = readArchiveOwnerUuid(a.path);
                    if (id != null) return Optional.of(id);
                } catch (IOException ignored) {
                    // Try next candidate.
                }
            }
        } catch (IOException e) {
            CheeseGuardMod.LOGGER.warn("[CG] Archive scan for {} failed: {}", playerName, e.getMessage());
        }
        return Optional.empty();
    }

    /** Find archives whose filename UUID-prefix matches the given player UUID. Sorted timestamp desc. */
    public static List<ArchiveFile> findFor(MinecraftServer server, UUID playerUuid) {
        Path dir = getArchiveDir(server);
        if (!Files.isDirectory(dir)) return List.of();
        String prefix = playerUuid.toString().substring(0, 8);
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(prefix + "_"))
                    .map(ArchiveFile::parse)
                    .filter(Objects::nonNull)
                    // 8-char prefix can collide across different UUIDs — verify the
                    // full UUID stored inside playerData matches before listing.
                    .filter(a -> uuidMatches(a.path, playerUuid))
                    .sorted(Comparator.comparing(ArchiveFile::timestamp).reversed())
                    .toList();
        } catch (IOException e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to list archive dir {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /** Find a specific archive by exact filename. */
    public static Optional<ArchiveFile> findByFilename(MinecraftServer server, String filename) {
        Path dir = getArchiveDir(server);
        Path file = dir.resolve(filename);
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.ofNullable(ArchiveFile.parse(file));
    }

    /**
     * Read the {@code playerData} sub-compound from an archive file.
     *
     * @return the playerData compound on success, or {@code null} if the file
     *         is missing the required {@code playerData}/{@code Inventory}
     *         keys or is in an unsupported format. Never returns a structurally
     *         empty compound — the caller can trust a non-null return to be
     *         safe to feed into {@code player.readNbt}.
     */
    public static NbtCompound readPlayerData(Path archive) throws IOException {
        NbtCompound root = NbtIo.readCompressed(archive, NbtSizeTracker.ofUnlimitedBytes());
        if (root == null) return null;
        if (root.contains("format", NbtElement.INT_TYPE)) {
            int fmt = root.getInt("format");
            if (fmt != SUPPORTED_FORMAT) {
                CheeseGuardMod.LOGGER.warn(
                        "[CG] Archive {} has unsupported format={}, expected {}",
                        archive.getFileName(), fmt, SUPPORTED_FORMAT);
                return null;
            }
        }
        if (!root.contains("playerData", NbtElement.COMPOUND_TYPE)) return null;
        NbtCompound pd = root.getCompound("playerData");
        // Sanity: an archived snapshot MUST contain an Inventory list. Without
        // it, applying readNbt would silently no-op while we'd report success.
        if (!pd.contains("Inventory", NbtElement.LIST_TYPE)) {
            CheeseGuardMod.LOGGER.warn(
                    "[CG] Archive {} playerData missing Inventory key — refusing to apply",
                    archive.getFileName());
            return null;
        }
        return pd;
    }

    /**
     * Read just the player UUID from an archive's {@code playerData}. Used to
     * verify filename-prefix matches and to recover the full UUID for a player
     * who isn't in the user cache.
     *
     * <p>1.21.1 player NBT stores the UUID as a 4-int array under {@code UUID}.</p>
     */
    public static UUID readArchiveOwnerUuid(Path archive) throws IOException {
        NbtCompound root = NbtIo.readCompressed(archive, NbtSizeTracker.ofUnlimitedBytes());
        if (root == null) return null;
        if (!root.contains("playerData", NbtElement.COMPOUND_TYPE)) return null;
        NbtCompound pd = root.getCompound("playerData");
        if (pd.contains("UUID", NbtElement.INT_ARRAY_TYPE)) {
            int[] arr = pd.getIntArray("UUID");
            if (arr.length == 4) {
                long msb = ((long) arr[0] << 32) | ((long) arr[1] & 0xFFFFFFFFL);
                long lsb = ((long) arr[2] << 32) | ((long) arr[3] & 0xFFFFFFFFL);
                return new UUID(msb, lsb);
            }
        }
        return null;
    }

    private static boolean uuidMatches(Path archive, UUID expected) {
        try {
            UUID actual = readArchiveOwnerUuid(archive);
            return actual != null && actual.equals(expected);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Restore the inventory of an ONLINE player by re-running
     * {@code player.readNbt()} over the archived snapshot, then re-applying
     * the player's current health / hunger / XP / position / gamemode so only
     * the inventory portion of the snapshot wins.
     *
     * <p>Closes any open container screen first to avoid ghost-syncing items
     * between the open menu and the restored inventory.</p>
     */
    public static boolean restoreOnline(ServerPlayerEntity player, NbtCompound playerData) {
        if (playerData == null) return false;

        // Close any open container/anvil/etc. so currentScreenHandler == playerScreenHandler
        // when we send sync packets at the end.
        try { player.closeHandledScreen(); } catch (Exception ignored) {}

        // Capture state we want to preserve (everything except inventory).
        double curX = player.getX(), curY = player.getY(), curZ = player.getZ();
        float curYaw = player.getYaw(), curPitch = player.getPitch();
        float curHealth = player.getHealth();
        int curFood = player.getHungerManager().getFoodLevel();
        float curSat = player.getHungerManager().getSaturationLevel();
        float curExhaustion = player.getHungerManager().getExhaustion();
        int curXpLevel = player.experienceLevel;
        float curXpProgress = player.experienceProgress;
        int curTotalXp = player.totalExperience;
        var curGameMode = player.interactionManager.getGameMode();
        int curFireTicks = player.getFireTicks();
        int curAir = player.getAir();

        try {
            player.readNbt(playerData);
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.error(
                    "[CG] Inventory archive restore failed for {}: {}",
                    player.getName().getString(), e.getMessage(), e);
            return false;
        }

        // Re-apply preserved state.
        player.refreshPositionAndAngles(curX, curY, curZ, curYaw, curPitch);
        player.setHealth(curHealth);
        player.getHungerManager().setFoodLevel(curFood);
        player.getHungerManager().setSaturationLevel(curSat);
        player.getHungerManager().setExhaustion(curExhaustion);
        player.experienceLevel = curXpLevel;
        player.experienceProgress = curXpProgress;
        player.totalExperience = curTotalXp;
        player.interactionManager.changeGameMode(curGameMode);
        player.setFireTicks(curFireTicks);
        player.setAir(curAir);

        // Force sync to client.
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
        player.playerScreenHandler.sendContentUpdates();
        player.playerScreenHandler.onContentChanged(player.getInventory());

        return true;
    }

    /**
     * Restore the inventory of an OFFLINE player by overwriting the vanilla
     * {@code world/playerdata/{full-uuid}.nbt} file. Always takes a safety
     * copy of the existing playerdata file first so the operation is reversible.
     *
     * <p>The archived {@code Dimension} key is sanitised: if the archived
     * dimension isn't currently loaded (typical case for ad-hoc per-player
     * event dimensions which get deleted), it's rewritten to
     * {@code minecraft:overworld} so the player can actually spawn at next
     * login. The archived Pos is preserved as-is — the operator can always
     * teleport them after.</p>
     *
     * @return absolute path of the safety copy if one was made, else null.
     * @throws IOException if I/O fails. Caller should NOT proceed if this throws.
     */
    public static Path restoreOffline(MinecraftServer server, UUID playerUuid, NbtCompound playerData)
            throws IOException {
        // Sanitise dimension if it isn't currently loaded.
        if (playerData.contains("Dimension", NbtElement.STRING_TYPE)) {
            String archivedDim = playerData.getString("Dimension");
            boolean dimLoaded = false;
            for (var key : server.getWorldRegistryKeys()) {
                if (key.getValue().toString().equals(archivedDim)) { dimLoaded = true; break; }
            }
            if (!dimLoaded) {
                CheeseGuardMod.LOGGER.info(
                        "[CG] Offline restore: archived Dimension {} no longer loaded — rewriting to overworld",
                        archivedDim);
                playerData.putString("Dimension", "minecraft:overworld");
            }
        }

        Path playerdataDir = server.getSavePath(WorldSavePath.PLAYERDATA);
        Files.createDirectories(playerdataDir);
        Path file = playerdataDir.resolve(playerUuid + ".nbt");
        Path safetyCopy = null;
        if (Files.exists(file)) {
            safetyCopy = playerdataDir.resolve(
                    playerUuid + ".nbt.cgrestore-" + System.currentTimeMillis() + ".bak");
            Files.copy(file, safetyCopy);
        }
        NbtIo.writeCompressed(playerData, file);
        return safetyCopy;
    }

    /** Parsed view of an archive filename. */
    public record ArchiveFile(Path path, String uuidPrefix, String timestamp, String playerName) {
        public static ArchiveFile parse(Path p) {
            Matcher m = FILENAME.matcher(p.getFileName().toString());
            if (!m.matches()) return null;
            return new ArchiveFile(p, m.group(1), m.group(2), m.group(3));
        }
    }
}
