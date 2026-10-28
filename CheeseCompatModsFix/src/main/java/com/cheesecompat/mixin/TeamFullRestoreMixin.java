package com.cheesecompat.mixin;

import battle.tower.battle.BattleTowerManager;
import battle.tower.data.BattleSettings;
import battle.tower.data.PlayerTowerData;
import battle.tower.data.TeamSnapshot;
import battle.tower.data.TowerDataManager;
import com.cheesecompat.BattleTowerDisconnectFix;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * Two fixes on Battle Tower team management:
 *
 * 1) BACKUP on lock-in: serialize TeamSnapshot to disk (binary NbtIo, atomic write via
 *    BattleTowerDisconnectFix.writeBackupFromSnapshot) so the full team state survives
 *    a mid-battle server crash or disconnect. The backup file is ONLY deleted after a proven
 *    successful restore — otherwise BattleTowerDisconnectFix reuses it on next login.
 *
 * 2) FULL RESTORE before level restoration: BattleTowerManager.restoreForcedLevelsSnapshot()
 *    only touches levels + heal(). Everything else (items, PP, friendship, moves, held item,
 *    status) was being lost. We inject at HEAD and rebuild each party Pokemon in place via
 *    Pokemon.loadFromNBT — the SAME code path Cobblemon uses when loading from disk.
 *    TeamSnapshotMixin protects captureTeam so the snapshot's IVs/EVs are always intact at
 *    backup time, which is what keeps the restore safe despite the Cobblemon 1.6.1 copyFrom
 *    IV/EV lambda bug.
 *
 * Offline players are skipped here intentionally — BattleTowerDisconnectFix owns the
 * reconnect path and reads the untouched backup file to rebuild the party.
 */
@Mixin(value = BattleTowerManager.class, remap = false)
public class TeamFullRestoreMixin {

    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompat");

    // ── Reflection cache for Cobblemon APIs ──

    @Unique private static Class<?> cheesecompat$pokemonClass;
    @Unique private static Method cheesecompat$loadFromNbtMethod;
    @Unique private static Method cheesecompat$pokemonGetUuidMethod;
    @Unique private static Class<?> cheesecompat$cobblemonClass;

    @Unique
    private static void cheesecompat$ensurePokemonReflection() throws Exception {
        if (cheesecompat$pokemonClass == null) {
            cheesecompat$pokemonClass = Class.forName("com.cobblemon.mod.common.pokemon.Pokemon");
            cheesecompat$loadFromNbtMethod = cheesecompat$pokemonClass.getMethod("loadFromNBT",
                DynamicRegistryManager.class, NbtCompound.class);
            cheesecompat$pokemonGetUuidMethod = cheesecompat$pokemonClass.getMethod("getUuid");
        }
    }

    @Unique
    private static Object cheesecompat$getParty(ServerPlayerEntity player) {
        try {
            if (cheesecompat$cobblemonClass == null) {
                cheesecompat$cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
            }
            Object instance = cheesecompat$cobblemonClass.getField("INSTANCE").get(null);
            Object storage = cheesecompat$cobblemonClass.getMethod("getStorage").invoke(instance);
            for (Method m : storage.getClass().getMethods()) {
                if (m.getName().equals("getParty") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(ServerPlayerEntity.class)) {
                    return m.invoke(storage, player);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[CheeseCompat] getParty failed for {}: {}", player.getName().getString(), e.getMessage());
        }
        return null;
    }

    // ── CobbleCap level force bypass (lets loadFromNBT restore pre-forced levels above cap) ──

    @Unique
    @SuppressWarnings("unchecked")
    private static void cheesecompat$setCobbleCapBypass(boolean active) {
        try {
            Class<?> ccm = Class.forName("com.cobblecap.CobbleCapMod");
            Field field = ccm.getField("LEVEL_FORCE_ACTIVE");
            ((ThreadLocal<Boolean>) field.get(null)).set(active);
        } catch (ClassNotFoundException ignored) {
            // CobbleCap not present
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] CobbleCap bypass failed: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // FIX 1: Backup team on lock-in (binary NbtIo via BattleTowerDisconnectFix)
    // ═══════════════════════════════════════════════════════

    @Inject(method = "lockInTeam", at = @At("RETURN"))
    private static void cheesecompat$backupOnLock(ServerPlayerEntity player, BattleSettings settings,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;

        try {
            PlayerTowerData data = TowerDataManager.getPlayerData(player.getUuid());
            TeamSnapshot snapshot = data.getTeamSnapshot();
            if (!snapshot.hasSnapshot()) return;

            boolean ok = BattleTowerDisconnectFix.writeBackupFromSnapshot(
                player.getUuid(), snapshot, player.getServer());

            if (ok) {
                LOGGER.info("[CheeseCompat] Team backup saved for {} ({} Pokemon)",
                    player.getName().getString(), snapshot.getTeamSize());
            }
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] Failed to save team backup for {}: {}",
                player.getName().getString(), e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // FIX 2: Full team restore (NBT path, no copyFrom result dependency)
    // ═══════════════════════════════════════════════════════

    @Inject(method = "restoreForcedLevelsSnapshot", at = @At("HEAD"))
    private static void cheesecompat$fullRestoreBeforeLevels(Map<UUID, Map<UUID, Integer>> snapshot,
                                                              MinecraftServer server, CallbackInfo ci) {
        // Enable CobbleCap bypass for BOTH our restore AND BT's original setLevel loop that
        // follows. Previously ci.cancel() blocked BT's loop, which was dangerous: if our
        // restore failed, the player's team was stuck at forced levels with no fallback.
        // Now BT's setLevel+heal always runs as a safety net — it's redundant after a
        // successful loadFromNBT restore, but essential as fallback if ours fails.
        cheesecompat$setCobbleCapBypass(true);

        for (UUID playerUUID : snapshot.keySet()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUUID);
            if (player == null) {
                LOGGER.debug("[CheeseCompat] Player {} offline, deferring to reconnect fix", playerUUID);
                continue;
            }

            boolean restored = cheesecompat$tryRestoreFromSnapshot(player, server);
            if (!restored) {
                LOGGER.warn("[CheeseCompat] In-memory snapshot restore failed for {}, falling back to disk backup",
                    player.getName().getString());
                restored = cheesecompat$tryRestoreFromBackup(player, server);
            }

            if (restored) {
                LOGGER.info("[CheeseCompat] Full team restore complete for {}", player.getName().getString());
                BattleTowerDisconnectFix.deleteBackup(playerUUID);
            } else {
                LOGGER.error("[CheeseCompat] All restore paths failed for {} — BT fallback will handle levels+heal",
                    player.getName().getString());
            }

            cheesecompat$forceClosePlayerBattle(player);
        }
        // DON'T cancel — let BT's original setLevel(originalLevel) + heal() run.
        // With bypass active, CobbleCap won't clamp the levels.
    }

    @Inject(method = "restoreForcedLevelsSnapshot", at = @At("RETURN"))
    private static void cheesecompat$clearBypassAfterRestore(Map<UUID, Map<UUID, Integer>> snapshot,
                                                               MinecraftServer server, CallbackInfo ci) {
        cheesecompat$setCobbleCapBypass(false);
    }

    @Unique
    private static void cheesecompat$forceClosePlayerBattle(ServerPlayerEntity player) {
        try {
            if (cheesecompat$cobblemonClass == null) {
                cheesecompat$cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
            }
            Object instance = cheesecompat$cobblemonClass.getField("INSTANCE").get(null);
            Object registry = cheesecompat$cobblemonClass.getMethod("getBattleRegistry").invoke(instance);

            Method getBattle = null;
            for (Method m : registry.getClass().getMethods()) {
                if (m.getName().equals("getBattleByParticipatingPlayer") && m.getParameterCount() == 1) {
                    getBattle = m;
                    break;
                }
            }
            if (getBattle == null) return;

            Object battle = getBattle.invoke(registry, player);
            if (battle == null) return;

            LOGGER.warn("[CheeseCompat] Post-restore: zombie battle found for {} — force-closing",
                player.getName().getString());

            for (Method m : registry.getClass().getMethods()) {
                if (m.getName().equals("closeBattle") && m.getParameterCount() == 1) {
                    m.invoke(registry, battle);
                    LOGGER.info("[CheeseCompat] Zombie battle closed for {}", player.getName().getString());
                    return;
                }
            }
            for (Method m : battle.getClass().getMethods()) {
                if (m.getName().equals("end") && m.getParameterCount() == 0) {
                    m.invoke(battle);
                    LOGGER.info("[CheeseCompat] Zombie battle ended for {}", player.getName().getString());
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] forceClosePlayerBattle: {}", e.getMessage());
        }
    }

    // ── Restore path A: serialize the in-memory snapshot and reload via NBT ──

    @Unique
    private static boolean cheesecompat$tryRestoreFromSnapshot(ServerPlayerEntity player, MinecraftServer server) {
        try {
            PlayerTowerData data = TowerDataManager.getPlayerData(player.getUuid());
            TeamSnapshot teamSnapshot = data.getTeamSnapshot();
            if (!teamSnapshot.hasSnapshot()) return false;

            // Reuse the reflection helper in BattleTowerDisconnectFix to call TeamSnapshot.toNbt.
            // We do this by writing the backup to disk AND restoring, but only if we trust the
            // current snapshot — which we do here, since this is the online "run end" path.
            //
            // Simpler: serialize via reflection inline.
            NbtCompound nbt = cheesecompat$serializeSnapshot(teamSnapshot, server);
            if (nbt == null) return false;
            return cheesecompat$restoreFromTeamNbt(player, server, nbt, "snapshot");
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] Snapshot restore failed: {}", e.getMessage(), e);
            return false;
        }
    }

    @Unique private static Method cheesecompat$toNbtMethod;

    @Unique
    private static NbtCompound cheesecompat$serializeSnapshot(TeamSnapshot snapshot, MinecraftServer server) {
        try {
            if (cheesecompat$toNbtMethod == null) {
                for (Method m : TeamSnapshot.class.getDeclaredMethods()) {
                    if (m.getName().equals("toNbt") && m.getParameterCount() == 1) {
                        m.setAccessible(true);
                        cheesecompat$toNbtMethod = m;
                        break;
                    }
                }
            }
            if (cheesecompat$toNbtMethod == null) return null;
            return (NbtCompound) cheesecompat$toNbtMethod.invoke(snapshot, server.getRegistryManager());
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] serializeSnapshot failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Restore path B: read disk backup (binary NBT via BattleTowerDisconnectFix) and reload ──

    @Unique
    private static boolean cheesecompat$tryRestoreFromBackup(ServerPlayerEntity player, MinecraftServer server) {
        try {
            NbtCompound nbt = BattleTowerDisconnectFix.readBackup(player.getUuid());
            if (nbt == null) {
                LOGGER.debug("[CheeseCompat] No backup file for {}", player.getUuid());
                return false;
            }
            return cheesecompat$restoreFromTeamNbt(player, server, nbt, "backup");
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] Backup restore failed: {}", e.getMessage(), e);
            return false;
        }
    }

    // ── Shared NBT -> party in-place reload ──

    @Unique
    private static boolean cheesecompat$restoreFromTeamNbt(ServerPlayerEntity player, MinecraftServer server,
                                                             NbtCompound snapshotNbt, String source) {
        cheesecompat$setCobbleCapBypass(true);
        try {
            cheesecompat$ensurePokemonReflection();

            if (!snapshotNbt.contains("team", 9)) {
                LOGGER.error("[CheeseCompat] {} NBT has no 'team' key for {}",
                    source, player.getName().getString());
                return false;
            }
            NbtList team = snapshotNbt.getList("team", 10); // 10 = COMPOUND
            if (team.isEmpty()) {
                LOGGER.warn("[CheeseCompat] Empty {} team NBT for {}", source, player.getName().getString());
                return false;
            }

            Object party = cheesecompat$getParty(player);
            if (party == null) return false;

            Method sizeMethod = party.getClass().getMethod("size");
            Method getMethod = party.getClass().getMethod("get", int.class);
            int partySize = (int) sizeMethod.invoke(party);

            DynamicRegistryManager registries = server.getRegistryManager();

            int restored = 0;
            int missing = 0;
            int failed = 0;

            Method healMethod = cheesecompat$pokemonClass.getMethod("heal");

            for (int i = 0; i < team.size(); i++) {
                NbtCompound pokeNbt = team.getCompound(i);

                // Extract UUID from NBT via a throwaway probe Pokemon
                UUID targetUuid;
                try {
                    Object probe = cheesecompat$pokemonClass.getDeclaredConstructor().newInstance();
                    cheesecompat$loadFromNbtMethod.invoke(probe, registries, pokeNbt);
                    targetUuid = (UUID) cheesecompat$pokemonGetUuidMethod.invoke(probe);
                } catch (Exception e) {
                    LOGGER.warn("[CheeseCompat] Failed to probe UUID from {} slot {}: {}",
                        source, i, e.getMessage());
                    failed++;
                    continue;
                }

                // Locate that Pokemon in the current party by UUID
                Object targetPoke = null;
                for (int slot = 0; slot < partySize; slot++) {
                    Object slotPoke = getMethod.invoke(party, slot);
                    if (slotPoke == null) continue;
                    UUID slotUuid = (UUID) cheesecompat$pokemonGetUuidMethod.invoke(slotPoke);
                    if (targetUuid.equals(slotUuid)) {
                        targetPoke = slotPoke;
                        break;
                    }
                }

                if (targetPoke == null) {
                    missing++;
                    continue;
                }

                // Per-Pokemon try-catch: if loadFromNBT fails for ONE Pokemon, heal it to
                // prevent a half-corrupted state and continue restoring the others.
                try {
                    cheesecompat$loadFromNbtMethod.invoke(targetPoke, registries, pokeNbt);
                    restored++;
                } catch (Exception e) {
                    LOGGER.error("[CheeseCompat] loadFromNBT failed for Pokemon {} in {}: {}",
                        targetUuid, source, e.getMessage());
                    try {
                        healMethod.invoke(targetPoke);
                    } catch (Exception ignored) {}
                    failed++;
                }
            }

            LOGGER.info("[CheeseCompat] NBT {} restore for {}: {}/{} restored, {} missing, {} failed",
                source, player.getName().getString(), restored, team.size(), missing, failed);

            // NO sendTo() here — the next battle starts 1-3 seconds after restore, and sending
            // a full party sync packet this close to battle creation causes a race condition:
            // the client receives restore-sync and battle-init packets near-simultaneously,
            // preventing showdown from initializing (Pokemon appear but no move buttons).
            // The client party will sync naturally when the BT session ends.

            return failed == 0 && missing == 0 && restored == team.size();
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] NBT restore ({}) failed for {}: {}",
                source, player.getName().getString(), e.getMessage(), e);
            return false;
        } finally {
            cheesecompat$setCobbleCapBypass(false);
        }
    }
}
