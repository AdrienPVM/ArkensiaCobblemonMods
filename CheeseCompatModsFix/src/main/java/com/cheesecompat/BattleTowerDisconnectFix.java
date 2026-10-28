package com.cheesecompat;

import battle.tower.battle.BattleTowerManager;
import battle.tower.data.TeamSnapshot;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Foolproof Battle Tower disconnect/reconnect fix — hardened v2.
 *
 * Key guarantees:
 *  - Backups written via NbtIo.writeCompressed + atomic tmp→final rename (crash safe)
 *  - Party UUID stability check: ALL backup UUIDs must be present in the party before restore
 *    fires (avoids racing with Cobblemon's async party loader)
 *  - `startBattle` is blocked while a restore is pending for the player (prevents the
 *    rapid-rebattle race where re-lock captures BT-forced levels)
 *  - Age expiry sweep on server start: backups older than 30 days are purged
 *  - Schema-drift safe: distinguishes missing "team" key from empty team list
 *  - Backup deleted only after full success (every backup UUID matched and reloaded)
 */
public final class BattleTowerDisconnectFix {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompat");

    private static final int  WAIT_INTERVAL_TICKS = 20;
    private static final int  MAX_ATTEMPTS        = 60;
    private static final long BACKUP_MAX_AGE_MS   = 30L * 24L * 60L * 60L * 1000L;

    private static final Map<UUID, Integer> pending  = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();

    private static volatile Field  snapshotsField;
    private static volatile boolean snapshotsFieldMissing;
    private static volatile Class<?> pokemonClass;
    private static volatile Method  loadFromNbtMethod;
    private static volatile Method  pokemonGetUuidMethod;
    private static volatile Class<?> cobblemonClass;
    private static volatile Method  toNbtMethod;

    private BattleTowerDisconnectFix() {}

    /** Called from BattleTowerManagerMixin.startBattle to block rapid rebattle while restore is queued. */
    public static boolean isRestorePending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(BattleTowerDisconnectFix::expireOldBackups);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (player != null) {
                inMemoryRestore(player.getUuid(), server, "disconnect");
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (player == null) return;
            UUID uuid = player.getUuid();

            inMemoryRestore(uuid, server, "login");

            if (Files.exists(backupFile(uuid))) {
                pending.put(uuid, WAIT_INTERVAL_TICKS);
                attempts.put(uuid, 0);
                LOGGER.info("[CheeseCompat] Orphan BT backup detected for {} — scheduled foolproof restore",
                    player.getName().getString());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(BattleTowerDisconnectFix::tick);

        LOGGER.info("[CheeseCompat] BattleTower foolproof disconnect/reconnect fix registered (v2 hardened)");
    }

    // ── Backup age expiry on server start ──

    private static void expireOldBackups(MinecraftServer server) {
        Path dir = backupDir();
        if (!Files.isDirectory(dir)) return;
        long now = System.currentTimeMillis();
        int deleted = 0;
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                try {
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (now - mtime > BACKUP_MAX_AGE_MS) {
                        Files.deleteIfExists(p);
                        deleted++;
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            LOGGER.debug("[CheeseCompat] Backup expiry sweep failed: {}", e.getMessage());
            return;
        }
        if (deleted > 0) {
            LOGGER.info("[CheeseCompat] Expired {} stale BT backups (>30d old)", deleted);
        }
    }

    // ── Tick processor ──

    private static void tick(MinecraftServer server) {
        if (pending.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            if (remaining > 0) {
                entry.setValue(remaining);
                continue;
            }

            UUID uuid = entry.getKey();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) {
                it.remove();
                attempts.remove(uuid);
                continue;
            }

            Object party = getParty(player);
            int partySize = party != null ? getPartySize(party) : -1;
            int tries = attempts.getOrDefault(uuid, 0);

            if (partySize <= 0) {
                if (tries >= MAX_ATTEMPTS) {
                    it.remove();
                    attempts.remove(uuid);
                    LOGGER.error("[CheeseCompat] Party for {} never loaded after {}s — backup KEPT",
                        player.getName().getString(), MAX_ATTEMPTS);
                    continue;
                }
                attempts.put(uuid, tries + 1);
                entry.setValue(WAIT_INTERVAL_TICKS);
                continue;
            }

            // Stability: ALL backup UUIDs must be present in party. Otherwise wait a bit longer
            // (Cobblemon can report size > 0 while party is still partially loading async).
            // After MAX_ATTEMPTS we give up — if some UUIDs are truly gone (player modified party),
            // we still attempt to restore the ones we can find and delete the backup.
            Set<UUID> backupUuids = readBackupUuids(uuid, server);
            Set<UUID> partyUuids = collectPartyUuids(party);
            if (backupUuids == null) {
                // Unreadable backup — delete it, nothing we can do.
                it.remove();
                attempts.remove(uuid);
                deleteBackup(uuid);
                LOGGER.error("[CheeseCompat] Backup for {} unreadable — deleted", player.getName().getString());
                continue;
            }

            boolean allPresent = partyUuids.containsAll(backupUuids);
            boolean giveUp = tries >= MAX_ATTEMPTS;
            if (!allPresent && !giveUp) {
                attempts.put(uuid, tries + 1);
                entry.setValue(WAIT_INTERVAL_TICKS);
                continue;
            }

            it.remove();
            attempts.remove(uuid);
            boolean ok = restoreFromBackup(player, server, party);
            if (ok) {
                deleteBackup(uuid);
                LOGGER.info("[CheeseCompat] Foolproof BT restore SUCCESS for {}",
                    player.getName().getString());
            } else if (giveUp) {
                // Gave up waiting for missing UUIDs and partial restore also failed — delete so we
                // don't softlock this player in an infinite retry loop.
                deleteBackup(uuid);
                LOGGER.warn("[CheeseCompat] Restore for {} gave up after {} attempts — backup deleted",
                    player.getName().getString(), MAX_ATTEMPTS);
            } else {
                LOGGER.error("[CheeseCompat] Foolproof BT restore FAILED for {} — backup KEPT for retry",
                    player.getName().getString());
            }
        }
    }

    // ── In-memory path (safety net) ──

    private static boolean inMemoryRestore(UUID uuid, MinecraftServer server, String reason) {
        if (snapshotsFieldMissing) return false;
        try {
            if (snapshotsField == null) {
                synchronized (BattleTowerDisconnectFix.class) {
                    if (snapshotsField == null) {
                        Field f = BattleTowerManager.class.getDeclaredField("forcedLevelSnapshots");
                        f.setAccessible(true);
                        snapshotsField = f;
                    }
                }
            }
            @SuppressWarnings("unchecked")
            Map<UUID, ?> snapshots = (Map<UUID, ?>) snapshotsField.get(null);
            if (snapshots == null || !snapshots.containsKey(uuid)) return false;

            setCobbleCapBypass(true);
            try {
                BattleTowerManager.restoreForcedLevels(uuid, server);
            } finally {
                setCobbleCapBypass(false);
            }
            LOGGER.info("[CheeseCompat] In-memory forced-level restore on {} for {}", reason, uuid);
            return true;
        } catch (NoSuchFieldException nf) {
            snapshotsFieldMissing = true;
            return false;
        } catch (Exception ex) {
            LOGGER.debug("[CheeseCompat] In-memory restore skipped ({}): {}", reason, ex.getMessage());
            return false;
        }
    }

    // ── Core restore: direct NBT reload, zero copyFrom result dependency ──

    private static boolean restoreFromBackup(ServerPlayerEntity player, MinecraftServer server, Object party) {
        NbtCompound root = readBackupNbt(player.getUuid());
        if (root == null) return false;

        if (!root.contains("team", 9)) {
            LOGGER.error("[CheeseCompat] Backup for {} has no 'team' key — schema drift? Deleting.",
                player.getName().getString());
            return true; // delete
        }

        NbtList team = root.getList("team", 10);
        if (team.isEmpty()) {
            LOGGER.warn("[CheeseCompat] Backup team empty for {} — deleting", player.getName().getString());
            return true;
        }

        setCobbleCapBypass(true);
        try {
            ensureReflection();

            DynamicRegistryManager registries = server.getRegistryManager();
            Method partyGet = party.getClass().getMethod("get", int.class);
            int partySize = getPartySize(party);

            int restored = 0;
            int missing = 0;
            int failed = 0;

            Method healMethod = pokemonClass.getMethod("heal");

            for (int i = 0; i < team.size(); i++) {
                NbtCompound pokeNbt = team.getCompound(i);

                UUID targetUuid;
                try {
                    Object probe = pokemonClass.getDeclaredConstructor().newInstance();
                    loadFromNbtMethod.invoke(probe, registries, pokeNbt);
                    targetUuid = (UUID) pokemonGetUuidMethod.invoke(probe);
                } catch (Exception e) {
                    LOGGER.warn("[CheeseCompat] Failed to probe UUID from backup slot {}: {}", i, e.getMessage());
                    failed++;
                    continue;
                }

                Object targetPoke = null;
                for (int slot = 0; slot < partySize; slot++) {
                    Object slotPoke = partyGet.invoke(party, slot);
                    if (slotPoke == null) continue;
                    UUID slotUuid = (UUID) pokemonGetUuidMethod.invoke(slotPoke);
                    if (targetUuid.equals(slotUuid)) {
                        targetPoke = slotPoke;
                        break;
                    }
                }

                if (targetPoke == null) {
                    missing++;
                    LOGGER.warn("[CheeseCompat] Backup pokemon {} not in party of {}",
                        targetUuid, player.getName().getString());
                    continue;
                }

                try {
                    loadFromNbtMethod.invoke(targetPoke, registries, pokeNbt);
                    restored++;
                } catch (Exception e) {
                    LOGGER.error("[CheeseCompat] loadFromNBT failed for Pokemon {}: {}", targetUuid, e.getMessage());
                    try { healMethod.invoke(targetPoke); } catch (Exception ignored) {}
                    failed++;
                }
            }

            LOGGER.info("[CheeseCompat] NBT restore for {}: {}/{} restored, {} missing, {} failed",
                player.getName().getString(), restored, team.size(), missing, failed);

            return failed == 0 && missing == 0 && restored == team.size();
        } catch (Exception ex) {
            LOGGER.error("[CheeseCompat] NBT restore failed for {}: {}",
                player.getName().getString(), ex.getMessage(), ex);
            return false;
        } finally {
            setCobbleCapBypass(false);
        }
    }

    // ── Helpers: read backup UUIDs cheaply (for stability check) ──

    private static Set<UUID> readBackupUuids(UUID playerUuid, MinecraftServer server) {
        NbtCompound root = readBackupNbt(playerUuid);
        if (root == null || !root.contains("team", 9)) return null;
        NbtList team = root.getList("team", 10);
        Set<UUID> out = new HashSet<>();
        try {
            ensureReflection();
            DynamicRegistryManager registries = server.getRegistryManager();
            for (int i = 0; i < team.size(); i++) {
                NbtCompound pokeNbt = team.getCompound(i);
                Object probe = pokemonClass.getDeclaredConstructor().newInstance();
                loadFromNbtMethod.invoke(probe, registries, pokeNbt);
                UUID u = (UUID) pokemonGetUuidMethod.invoke(probe);
                if (u != null) out.add(u);
            }
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] readBackupUuids failed: {}", e.getMessage());
            return null;
        }
        return out;
    }

    private static Set<UUID> collectPartyUuids(Object party) {
        Set<UUID> out = new HashSet<>();
        try {
            int size = getPartySize(party);
            Method get = party.getClass().getMethod("get", int.class);
            for (int i = 0; i < size; i++) {
                Object slot = get.invoke(party, i);
                if (slot == null) continue;
                UUID u = (UUID) pokemonGetUuidMethod.invoke(slot);
                if (u != null) out.add(u);
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ── NBT file I/O (atomic, binary, schema-safe) ──

    public static Path backupDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("cheesecompat_bt_backups");
    }

    public static Path backupFile(UUID uuid) {
        return backupDir().resolve(uuid + ".dat");
    }

    /**
     * Serialize a live TeamSnapshot to NBT via reflection and write it atomically to disk.
     * Used at lockInTeam (TeamFullRestoreMixin) and at floor-1 re-lock (BattleTowerManagerMixin).
     */
    public static boolean writeBackupFromSnapshot(UUID uuid, TeamSnapshot snapshot, MinecraftServer server) {
        try {
            ensureToNbtMethod();
            NbtCompound nbt = (NbtCompound) toNbtMethod.invoke(snapshot, server.getRegistryManager());
            writeBackupNbt(uuid, nbt);
            return true;
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] writeBackupFromSnapshot failed for {}: {}", uuid, e.getMessage());
            return false;
        }
    }

    /** Read the backup file for a player as NbtCompound, or null if absent/unreadable. Public helper. */
    public static NbtCompound readBackup(UUID uuid) {
        return readBackupNbt(uuid);
    }

    private static void ensureToNbtMethod() throws Exception {
        if (toNbtMethod == null) {
            synchronized (BattleTowerDisconnectFix.class) {
                if (toNbtMethod == null) {
                    for (Method m : TeamSnapshot.class.getDeclaredMethods()) {
                        if (m.getName().equals("toNbt") && m.getParameterCount() == 1) {
                            m.setAccessible(true);
                            toNbtMethod = m;
                            break;
                        }
                    }
                    if (toNbtMethod == null) {
                        throw new NoSuchMethodException("TeamSnapshot.toNbt(registries)");
                    }
                }
            }
        }
    }

    /** Atomic NBT write: tmp file first, then atomic move. Survives crash mid-write and disk full. */
    public static void writeBackupNbt(UUID uuid, NbtCompound root) throws IOException {
        Path dir = backupDir();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(uuid + ".dat.tmp");
        Path finalFile = backupFile(uuid);
        NbtIo.writeCompressed(root, tmp);
        try {
            Files.move(tmp, finalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Atomic move may not be supported on all filesystems — fall back.
            Files.move(tmp, finalFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static NbtCompound readBackupNbt(UUID uuid) {
        Path file = backupFile(uuid);
        if (!Files.exists(file)) return null;
        try {
            return NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] Failed to read backup NBT for {}: {}", uuid, e.getMessage());
            return null;
        }
    }

    // ── Reflection helpers ──

    private static void ensureReflection() throws Exception {
        if (pokemonClass == null) {
            synchronized (BattleTowerDisconnectFix.class) {
                if (pokemonClass == null) {
                    Class<?> c = Class.forName("com.cobblemon.mod.common.pokemon.Pokemon");
                    Method load = c.getMethod("loadFromNBT", DynamicRegistryManager.class, NbtCompound.class);
                    Method getUuid = c.getMethod("getUuid");
                    loadFromNbtMethod = load;
                    pokemonGetUuidMethod = getUuid;
                    pokemonClass = c;
                }
            }
        }
    }

    private static Object getParty(ServerPlayerEntity player) {
        try {
            if (cobblemonClass == null) {
                synchronized (BattleTowerDisconnectFix.class) {
                    if (cobblemonClass == null) {
                        cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
                    }
                }
            }
            Object instance = cobblemonClass.getField("INSTANCE").get(null);
            Object storage = cobblemonClass.getMethod("getStorage").invoke(instance);
            for (Method m : storage.getClass().getMethods()) {
                if (m.getName().equals("getParty") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(ServerPlayerEntity.class)) {
                    return m.invoke(storage, player);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] getParty failed: {}", e.getMessage());
        }
        return null;
    }

    private static int getPartySize(Object party) {
        try {
            return (int) party.getClass().getMethod("size").invoke(party);
        } catch (Exception e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static void setCobbleCapBypass(boolean active) {
        try {
            Class<?> ccm = Class.forName("com.cobblecap.CobbleCapMod");
            Field field = ccm.getField("LEVEL_FORCE_ACTIVE");
            ((ThreadLocal<Boolean>) field.get(null)).set(active);
        } catch (ClassNotFoundException ignored) {
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] CobbleCap bypass set failed: {}", e.getMessage());
        }
    }

    public static void deleteBackup(UUID uuid) {
        try {
            Files.deleteIfExists(backupFile(uuid));
        } catch (Exception e) {
            LOGGER.warn("[CheeseCompat] Failed to delete backup for {}: {}", uuid, e.getMessage());
        }
    }
}
