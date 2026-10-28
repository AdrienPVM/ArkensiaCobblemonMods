package com.sebastian.cobblemondungeons.dungeon;

import com.sebastian.cobblemondungeons.config.DungeonConfig;
import com.sebastian.cobblemondungeons.party.DungeonsParty;
import com.sebastian.cobblemondungeons.party.DungeonsPartyManager;
import com.sebastian.cobblemondungeons.networking.BungeePayload;
import com.sebastian.cobblemondungeons.networking.DungeonJoinPayload;
import com.sebastian.cobblemondungeons.networking.DungeonHudPayload;
import com.sebastian.cobblemondungeons.util.PartySyncHandler;
import com.sebastian.cobblemondungeons.util.PlayerSyncHandler;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.gitlab.srcmc.rctapi.api.RCTApi;
import com.gitlab.srcmc.rctapi.api.trainer.Trainer;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerPlayer;
import com.gitlab.srcmc.rctapi.api.battle.BattleManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.item.ItemStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class DungeonManager {
    private static final Map<String, DungeonConfig> dungeons = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, DungeonInstance> activeInstances = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, DungeonInstance> partyInstances = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, DungeonInstance> bossToInstance = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, NbtCompound> pendingSyncData = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, PendingSync> delayedSyncs = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, CountdownData> activeCountdowns = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, PendingTransfer> pendingTransfers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int TRANSFER_DELAY_TICKS = 40;
    private static final Map<UUID, PendingStart> pendingStarts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, NbtCompound> returnPositions = new java.util.concurrent.ConcurrentHashMap<>();
    private static int alphaSpawnerTickCounter = 0;
    private static int guardianSpawnerTickCounter = 0;
    private static final int GLOBAL_SCAN_BUDGET = 32;
    private static final int SERVER_STATUS_TIMEOUT_TICKS = 60;
    private static final Set<String> INVALID_ALPHA_SPECIES_LOGGED = new HashSet<>();

    private static class CountdownData {
        String dungeonId;
        int secondsLeft;
        UUID partyId;
        Set<UUID> members;
        int tier;
        int locationIndex = -1;

        CountdownData(String dungeonId, int secondsLeft, UUID partyId, Set<UUID> members, int tier) {
            this(dungeonId, secondsLeft, partyId, members, tier, -1);
        }

        CountdownData(String dungeonId, int secondsLeft, UUID partyId, Set<UUID> members, int tier, int locationIndex) {
            this.dungeonId = dungeonId;
            this.secondsLeft = secondsLeft;
            this.partyId = partyId;
            this.members = members;
            this.tier = tier;
            this.locationIndex = locationIndex;
        }
    }

    private static class PendingSync {
        NbtCompound data;
        int ticksLeft;
        boolean loadFromSql;
        boolean force = false;
        int retries = 0;

        PendingSync(NbtCompound data, int ticksLeft, boolean loadFromSql) {
            this.data = data;
            this.ticksLeft = ticksLeft;
            this.loadFromSql = loadFromSql;
        }

        PendingSync(NbtCompound data, int ticksLeft, boolean loadFromSql, boolean force) {
            this.data = data;
            this.ticksLeft = ticksLeft;
            this.loadFromSql = loadFromSql;
            this.force = force;
        }
    }

    private static class PendingTransfer {
        String targetServer;
        int ticksLeft;

        PendingTransfer(String targetServer, int ticksLeft) {
            this.targetServer = targetServer;
            this.ticksLeft = ticksLeft;
        }
    }

    private static class PendingStart {
        String dungeonId;
        String targetServer;
        int ticksLeft;
        int tier;

        PendingStart(String dungeonId, String targetServer, int ticksLeft, int tier) {
            this.dungeonId = dungeonId;
            this.targetServer = targetServer;
            this.ticksLeft = ticksLeft;
            this.tier = tier;
        }
    }

    private static ServerWorld getDungeonWorld(MinecraftServer server, DungeonInstance instance) {
        if (instance == null || instance.getConfig() == null || instance.getConfig().locations.spawnPos == null) return null;
        String dimension = instance.getConfig().locations.spawnPos.dimension;
        if (dimension == null) return server.getOverworld();
        try {
            Identifier dimId = Identifier.of(dimension);
            RegistryKey<net.minecraft.world.World> key = RegistryKey.of(RegistryKeys.WORLD, dimId);
            return server.getWorld(key);
        } catch (Exception e) {
            return server.getOverworld();
        }
    }

    public static void registerDungeon(DungeonConfig config) {
        dungeons.put(config.general.id, config);
    }

    /** Clear all registered dungeon configs. Used for hot-reload idempotency. */
    public static void clearDungeonConfigs() {
        dungeons.clear();
    }

    public static int findAvailableSlot(String dungeonId) {
        DungeonConfig config = dungeons.get(dungeonId);
        if (config == null) return -1;
        List<DungeonConfig.LocationsConfig> all = config.getAllLocations();
        for (int i = 0; i < all.size(); i++) {
            if (!isSlotBusy(dungeonId, i)) return i;
        }
        return -1;
    }

    public static boolean isSlotBusy(String dungeonId, int slotIndex) {
        Set<DungeonInstance> uniqueInstances = new HashSet<>(activeInstances.values());
        for (DungeonInstance instance : uniqueInstances) {
            if (instance.getConfig().general.id.equals(dungeonId) && instance.getLocationIndex() == slotIndex) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDungeonTicket(ServerPlayerEntity player, String dungeonId, int requiredTier) {
        try {
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (stack == null || stack.isEmpty()) continue;
                if (!stack.isOf(com.sebastian.cobblemondungeons.CobblemonDungeons.DUNGEON_TICKET)) continue;
                net.minecraft.component.type.NbtComponent data = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
                if (data == null) continue;
                net.minecraft.nbt.NbtCompound nbt = data.copyNbt();
                String id = nbt.getString("DungeonId");
                int tier = nbt.getInt("Tier");
                if (id != null && !id.isBlank() && id.equals(dungeonId) && tier >= requiredTier) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static DungeonConfig getDungeonConfig(String id) {
        return dungeons.get(id);
    }

    public static boolean startDungeon(ServerPlayerEntity leader, String dungeonId) {
        DungeonConfig config = dungeons.get(dungeonId);
        if (config == null) return false;
        return startDungeon(leader, dungeonId, config.scaling.tier);
    }

    public static boolean startDungeon(ServerPlayerEntity leader, String dungeonId, int tier) {
        DungeonConfig config = dungeons.get(dungeonId);
        if (config == null) return false;
        if (leader == null || leader.getServer() == null) return false;

        if ("SAME_SERVER".equalsIgnoreCase(config.networking.teleportMode)) {
            return startDungeonAfterStatus(leader, dungeonId, tier);
        }

        UUID leaderId = leader.getUuid();
        if (pendingStarts.containsKey(leaderId)) {
            leader.sendMessage(Text.translatable("cobblemondungeons.checking_server").formatted(Formatting.YELLOW));
            return true;
        }
        pendingStarts.put(leaderId, new PendingStart(dungeonId, config.networking.targetServer, SERVER_STATUS_TIMEOUT_TICKS, tier));
        ServerPlayNetworking.send(leader, BungeePayload.playerCount(config.networking.targetServer));
        leader.sendMessage(Text.translatable("cobblemondungeons.checking_server").formatted(Formatting.YELLOW));
        return true;
    }

    private static boolean startDungeonAfterStatus(ServerPlayerEntity leader, String dungeonId, int tier) {
        DungeonConfig config = dungeons.get(dungeonId);
        if (config == null) return false;

        DungeonsParty party = DungeonsPartyManager.getParty(leader.getUuid());
        Set<UUID> members = new HashSet<>();
        
        if (party != null) {
            if (!party.getLeader().equals(leader.getUuid())) {
                leader.sendMessage(Text.translatable("cobblemondungeons.party_leader_only").formatted(Formatting.RED));
                return false;
            }
            members.addAll(party.getMembers());
        } else {
            members.add(leader.getUuid());
        }

        // Check if all members have pokemon
        for (UUID uuid : members) {
            ServerPlayerEntity player = leader.getServer().getPlayerManager().getPlayer(uuid);
            if (player != null && !DungeonsPartyManager.hasPokemon(player)) {
                leader.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(Formatting.RED));
                return false;
            }
        }

        if (members.size() > config.requirements.dungeonsPartySizeLimit) {
            leader.sendMessage(Text.translatable("cobblemondungeons.party_too_large", config.requirements.dungeonsPartySizeLimit).formatted(Formatting.RED));
            return false;
        }

        // Cooldown check for all members
        if (config.requirements.cooldownSeconds > 0) {
            for (UUID uuid : members) {
                long remaining = getCooldownRemaining(dungeonId, uuid);
                if (remaining > 0) {
                    ServerPlayerEntity p = leader.getServer().getPlayerManager().getPlayer(uuid);
                    String name = p != null ? p.getName().getString() : uuid.toString().substring(0, 8);
                    long mins = remaining / 60;
                    long secs = remaining % 60;
                    leader.sendMessage(Text.translatable("cobblemondungeons.cooldown_remaining", name, mins, secs).formatted(Formatting.RED));
                    return false;
                }
            }
        }

        int slotIndex = -1;
        if ("SAME_SERVER".equalsIgnoreCase(config.networking.teleportMode)) {
            slotIndex = findAvailableSlot(dungeonId);
            if (slotIndex == -1) {
                leader.sendMessage(Text.translatable("cobblemondungeons.dungeons_full").formatted(Formatting.RED));
                return false;
            }

            // Require and consume dungeon ticket for the leader
            if (!consumeDungeonTicket(leader, config.general.id, tier)) {
                leader.sendMessage(Text.translatable("cobblemondungeons.ticket_required", config.general.name, tier).formatted(Formatting.RED));
                return false;
            }
        } else {
            // Require and consume dungeon ticket for the leader
            if (!consumeDungeonTicket(leader, config.general.id, tier)) {
                leader.sendMessage(Text.translatable("cobblemondungeons.ticket_required", config.general.name, tier).formatted(Formatting.RED));
                return false;
            }
        }

        UUID partyId = party != null ? party.getLeader() : null;
        
        // Start Countdown
        CountdownData countdown = new CountdownData(dungeonId, 5, partyId, members, tier, slotIndex);
        for (UUID uuid : members) {
            activeCountdowns.put(uuid, countdown);
            ServerPlayerEntity p = leader.getServer().getPlayerManager().getPlayer(uuid);
            if (p != null) {
                p.sendMessage(Text.translatable("cobblemondungeons.starting_countdown").formatted(Formatting.GOLD, Formatting.BOLD));
            }
        }
        return true;
    }

    private static boolean consumeDungeonTicket(ServerPlayerEntity player, String dungeonId, int requiredTier) {
        try {
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (stack == null || stack.isEmpty()) continue;
                if (!stack.isOf(com.sebastian.cobblemondungeons.CobblemonDungeons.DUNGEON_TICKET)) continue;
                net.minecraft.component.type.NbtComponent data = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
                if (data == null) continue;
                net.minecraft.nbt.NbtCompound nbt = data.copyNbt();
                String id = nbt.getString("DungeonId");
                int tier = nbt.getInt("Tier");
                if (id != null && !id.isBlank() && id.equals(dungeonId) && tier >= requiredTier) {
                    stack.decrement(1);
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static void handleServerStatusResponse(MinecraftServer server, String serverName, int playerCount) {
        if (server == null || serverName == null) return;
        boolean online = playerCount >= 0;
        for (Map.Entry<UUID, PendingStart> entry : new HashMap<>(pendingStarts).entrySet()) {
            PendingStart pending = entry.getValue();
            if (!serverName.equalsIgnoreCase(pending.targetServer)) continue;
            pendingStarts.remove(entry.getKey());
            ServerPlayerEntity leader = server.getPlayerManager().getPlayer(entry.getKey());
            if (leader == null) continue;
            if (!online) {
                leader.sendMessage(Text.translatable("cobblemondungeons.server_offline").formatted(Formatting.RED));
                continue;
            }
            startDungeonAfterStatus(leader, pending.dungeonId, pending.tier);
        }
    }

    private static void executeStart(ServerPlayerEntity player, DungeonConfig config, UUID partyId, int tier, int partySize) {
        // WS broadcast dungeon start
        DungeonInstance startInst = activeInstances.get(player.getUuid());
        if (startInst != null) broadcastDungeonEvent("dungeon_start", startInst, player.getServer());

        if ("SAME_SERVER".equalsIgnoreCase(config.networking.teleportMode)) {
            ensureAliveForDungeonJoin(player);
            player.sendMessage(Text.translatable("cobblemondungeons.dungeon_started", config.general.name));
            player.sendMessage(Text.translatable("cobblemondungeons.preparing").formatted(Formatting.YELLOW));

            DungeonInstance instance = activeInstances.get(player.getUuid());
            if (instance != null) {
                // Teleport to hub immediately
                teleportToDungeon(player, instance.getActiveLocations().hubPos);

                // Send initial HUD update
                ServerPlayNetworking.send(player, new DungeonHudPayload(
                    instance.getConfig().general.name,
                    instance.getAlphasDefeated(), instance.getConfig().requirements.requiredAlphas,
                    instance.getGemsFound(), instance.getConfig().requirements.requiredGems,
                    instance.getGuardiansDefeated(), instance.getConfig().requirements.requiredGuardians,
                    instance.getStakesFound(), instance.getConfig().requirements.requiredStakes,
                    instance.getTimeLeft(),
                    instance.getLives(),
                    !instance.isBossDefeated()
                ));

                if (!instance.areAlphasSpawned()) {
                    spawnAlphas(player.getServerWorld(), config, new BlockPos((int)instance.getActiveLocations().spawnPos.x, (int)instance.getActiveLocations().spawnPos.y, (int)instance.getActiveLocations().spawnPos.z), instance);
                    discoverChests(player.getServerWorld(), config, instance);
                    initializeStakeSpawning(player.getServerWorld(), config, instance);
                    initializeGuardianSpawning(player.getServerWorld(), config, instance);
                    instance.setAlphasSpawned(true);
                }
            }
            return;
        }

        NbtCompound syncData = null;
        if (config.networking.syncPlayerData) {
            syncData = serializePlayerData(player, true);
            if (com.sebastian.cobblemondungeons.util.DatabaseManager.isEnabled() && syncData != null) {
                com.sebastian.cobblemondungeons.util.DatabaseManager.savePlayerData(player.getUuid(), syncData);
            }

            // Exclude PC for payload only when SQL is enabled (payload is the fallback otherwise)
            if (syncData != null && com.sebastian.cobblemondungeons.util.DatabaseManager.isEnabled()) {
                syncData = syncData.copy();
                syncData.remove("CobblemonPC");
            }
        }

        if (syncData == null) {
            syncData = new NbtCompound();
            if (!com.sebastian.cobblemondungeons.CobblemonDungeons.isDungeonServer(player.getServer())) {
                NbtCompound posNbt = new NbtCompound();
                posNbt.putDouble("x", player.getX());
                posNbt.putDouble("y", player.getY());
                posNbt.putDouble("z", player.getZ());
                posNbt.putFloat("yaw", player.getYaw());
                posNbt.putFloat("pitch", player.getPitch());
                posNbt.putString("dim", player.getWorld().getRegistryKey().getValue().toString());
                syncData.put("DungeonReturnPos", posNbt);
            } else {
                NbtCompound returnPos = returnPositions.get(player.getUuid());
                if (returnPos != null) {
                    syncData.put("DungeonReturnPos", returnPos.copy());
                }
            }
        }

        // Clean up from memory map as we move to payload/transfer
        returnPositions.remove(player.getUuid());

        NbtCompound partyNbt = PartySyncHandler.serializeParty(player);
        if (partyNbt != null && !partyNbt.isEmpty()) {
            syncData.put("CobblemonParty", partyNbt);
        }
        player.sendMessage(Text.translatable("cobblemondungeons.dungeon_started", config.general.name));
        player.sendMessage(Text.translatable("cobblemondungeons.preparing").formatted(Formatting.YELLOW));

        if (syncData != null) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Sending DungeonJoinPayload to " + player.getName().getString() + " (SyncData keys: " + syncData.getKeys().size() + ")");
        }
        
        ServerPlayNetworking.send(player, new DungeonJoinPayload(config.general.id, partyId, partySize, syncData, tier));
        // Party sync via proxy is only needed as a fast-path for SQL-enabled setups.
        // For non-SQL, we rely solely on the atomic DungeonJoinPayload to avoid duplication risk.
        if (com.sebastian.cobblemondungeons.util.DatabaseManager.isEnabled()) {
            PartySyncHandler.sendPartyToServer(player, config.networking.targetServer);
        }
        transferPlayerDelayed(player, config.networking.targetServer);
    }

    private static void tickCountdowns(net.minecraft.server.MinecraftServer server) {
        if (activeCountdowns.isEmpty()) return;
        
        Set<CountdownData> uniqueCountdowns = new HashSet<>(activeCountdowns.values());
        for (CountdownData data : uniqueCountdowns) {
            data.secondsLeft--;
            
            if (data.secondsLeft > 0) {
                for (UUID uuid : data.members) {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                    if (p != null) p.sendMessage(Text.translatable("cobblemondungeons.countdown", data.secondsLeft).formatted(Formatting.YELLOW), true);
                }
            } else {
                // Time up! Start for all members
                DungeonConfig config = dungeons.get(data.dungeonId);
                if (config != null) {
                    DungeonInstance instance = new DungeonInstance(config, data.members, data.partyId, data.tier, data.members.size(), data.locationIndex);
                    if (data.partyId != null) {
                        partyInstances.put(data.partyId, instance);
                    }
                    int partySize = data.members.size();
                    for (UUID uuid : data.members) {
                        activeCountdowns.remove(uuid);
                        activeInstances.put(uuid, instance);
                        ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                        if (p != null) {
                            executeStart(p, config, data.partyId, data.tier, partySize);
                        }
                    }
                } else {
                    for (UUID uuid : data.members) activeCountdowns.remove(uuid);
                }
            }
        }
    }

    public static void leaveDungeon(ServerPlayerEntity player) {
        DungeonInstance instance = activeInstances.get(player.getUuid());
        if (instance != null) {
            DungeonConfig config = instance.getConfig();
            
            // Hide HUD
            ServerPlayNetworking.send(player, new DungeonHudPayload(config.general.name, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false));

            NbtCompound syncData = null;
            if (config.networking.syncPlayerData) {
                if (instance.isBossDefeated()) {
                    syncData = serializePlayerData(player, true);
                    if (com.sebastian.cobblemondungeons.util.DatabaseManager.isEnabled() && syncData != null) {
                        com.sebastian.cobblemondungeons.util.DatabaseManager.savePlayerData(player.getUuid(), syncData);
                    }

                    // Exclude PC for payload only when SQL is enabled (payload is the fallback otherwise)
                    if (syncData != null && com.sebastian.cobblemondungeons.util.DatabaseManager.isEnabled()) {
                        syncData = syncData.copy();
                        syncData.remove("CobblemonPC");
                    }
                } else {
                    // If not defeated, only sync return position, don't save inventory/pokemon
                    syncData = new NbtCompound();
                    NbtCompound returnPos = returnPositions.get(player.getUuid());
                    if (returnPos != null) {
                        syncData.put("DungeonReturnPos", returnPos.copy());
                    }
                    player.sendMessage(Text.translatable("cobblemondungeons.failed_message").formatted(Formatting.RED));
                }
            }
            if (syncData == null) {
                syncData = new NbtCompound();
                // Preserve return position if we have one (on dungeon server)
                NbtCompound returnPos = returnPositions.get(player.getUuid());
                if (returnPos != null) {
                    syncData.put("DungeonReturnPos", returnPos.copy());
                }
            }
            // Clean up from memory map as we move to payload/transfer
            returnPositions.remove(player.getUuid());
            NbtCompound partyNbt = PartySyncHandler.serializeParty(player);
            if (partyNbt != null && !partyNbt.isEmpty()) {
                syncData.put("CobblemonParty", partyNbt);
            }
            if ("SAME_SERVER".equalsIgnoreCase(config.networking.teleportMode)) {
                teleportToDungeon(player, instance.getActiveLocations().returnPos);
            } else {
                ServerPlayNetworking.send(player, new DungeonJoinPayload("", null, 1, syncData, 1));
                // Party sync via proxy is only needed as a fast-path for SQL-enabled setups.
                if (com.sebastian.cobblemondungeons.util.DatabaseManager.isEnabled()) {
                    PartySyncHandler.sendPartyToServer(player, config.networking.mainServer);
                }
                transferPlayerDelayed(player, config.networking.mainServer);
            }
            activeInstances.remove(player.getUuid());
            instance.getPlayers().remove(player.getUuid());
            if (instance.getPlayers().isEmpty()) {
                clearDungeonEntities(instance, player.getServer());
                if (instance.getPartyId() != null) {
                    partyInstances.remove(instance.getPartyId());
                }
            }
        } else {
            player.sendMessage(Text.translatable("cobblemondungeons.not_in_dungeon").formatted(Formatting.RED));
        }
    }

    public static void tickDungeons(net.minecraft.server.MinecraftServer server) {
        tickCountdowns(server);
        alphaSpawnerTickCounter++;
        boolean checkAlphaSpawners = alphaSpawnerTickCounter % 5 == 0;
        guardianSpawnerTickCounter++;
        boolean checkGuardianSpawners = guardianSpawnerTickCounter % 10 == 0;

        Set<DungeonInstance> uniqueInstances = new HashSet<>(activeInstances.values());
        for (DungeonInstance instance : uniqueInstances) {
            if (checkAlphaSpawners) {
                tickAlphaSpawners(server, instance);
            }
            if (checkGuardianSpawners) {
                tickGuardianSpawners(server, instance);
            }
            
            // Fast scan for stakes is now handled in tickFastScans
            
            if (instance.getFinishDelayTicks() > 0) {
                instance.setFinishDelayTicks(instance.getFinishDelayTicks() - 1);
                if (instance.getFinishDelayTicks() <= 0) {
                    for (UUID uuid : new ArrayList<>(instance.getPlayers())) {
                        ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                        if (p != null) leaveDungeon(p);
                    }
                    continue;
                }
            }

            instance.tickTime();
            int timeLeft = instance.getTimeLeft();

            if (timeLeft <= 0) {
                failDungeon(instance, server, Text.translatable("cobblemondungeons.time_up").formatted(Formatting.RED, Formatting.BOLD));
                continue;
            }

            // Check if preparation is complete and teleport players
            if (!instance.isTeleported() && instance.isReadyForPlayers()) {
                instance.setTeleported(true);
                for (UUID uuid : instance.getPlayers()) {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                    if (p != null) {
                        teleportToDungeon(p, instance.getConfig().locations.spawnPos);
                        p.sendMessage(Text.translatable("cobblemondungeons.ready").formatted(Formatting.GREEN, Formatting.BOLD));
                    }
                }
            }

            // Sync HUD to all players in the instance
            DungeonHudPayload payload = new DungeonHudPayload(
                instance.getConfig().general.name,
                instance.getAlphasDefeated(), instance.getConfig().requirements.requiredAlphas,
                instance.getGemsFound(), instance.getConfig().requirements.requiredGems,
                instance.getGuardiansDefeated(), instance.getConfig().requirements.requiredGuardians,
                instance.getStakesFound(), instance.getConfig().requirements.requiredStakes,
                timeLeft,
                instance.getLives(),
                !instance.isBossDefeated()
            );

            for (UUID uuid : instance.getPlayers()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null) {
                    ServerPlayNetworking.send(p, payload);
                }
            }
        }
    }

    public static void tickFastScans(net.minecraft.server.MinecraftServer server) {
        if (activeInstances.isEmpty()) return;
        Set<DungeonInstance> uniqueInstances = new HashSet<>(activeInstances.values());
        
        int budgetRemaining = GLOBAL_SCAN_BUDGET;

        for (DungeonInstance instance : uniqueInstances) {
            tickTrainerSpawns(server, instance);
            
            if (budgetRemaining <= 0) continue;

            DungeonConfig config = instance.getConfig();
            ServerWorld world = getDungeonWorld(server, instance);
            if (world == null) continue;

            // 1. Registry Population (One-time or periodic)
            if (!instance.isRegistryPopulated() || server.getTicks() % 100 == 0) {
                tryPopulateAlphaSpawnersFromRegistry(world, instance, config);
                tryPopulateStakeSpawnersFromRegistry(world, instance, config);
                tryPopulateGuardianSpawnersFromRegistry(world, instance, config);
                tryPopulatePortalsFromRegistry(world, instance, config);
                instance.setRegistryPopulated(true);
                checkPortalActivation(instance, world);
            }

            // 2. Alpha Scans
            if (!instance.isAlphaSpawnerScanComplete()) {
                int scanned = scanAlphaSpawnerChunks(server, world, instance, config, budgetRemaining);
                budgetRemaining -= scanned;
            }

            if (budgetRemaining <= 0) continue;

            // 3. Stake Scans
            if (!instance.areStakesSpawned()) {
                int scanned = scanStakeSpawnerChunks(world, instance, config, budgetRemaining);
                budgetRemaining -= scanned;

                // Activation Logic for Stakes
                int stakeTarget = instance.getStakeSpawnTarget();
                List<BlockPos> activatedStakes = instance.getStakeSpawnerPositions();
                Set<BlockPos> discoveredStakes = instance.getDiscoveredStakeSpawners();

                if (stakeTarget > 0) {
                    if (activatedStakes.size() < stakeTarget && !discoveredStakes.isEmpty()) {
                        List<BlockPos> candidates = new ArrayList<>();
                        for (BlockPos discovered : discoveredStakes) {
                            if (!activatedStakes.contains(discovered)) {
                                candidates.add(discovered);
                            }
                        }

                        if (!candidates.isEmpty()) {
                            Collections.shuffle(candidates);
                            int toAdd = Math.min(candidates.size(), stakeTarget - activatedStakes.size());
                            for (int i = 0; i < toAdd; i++) {
                                instance.addStakeSpawnerPosition(candidates.get(i));
                            }
                        }
                    }
                } else if (!discoveredStakes.isEmpty()) {
                    for (BlockPos discovered : discoveredStakes) {
                        instance.addStakeSpawnerPosition(discovered);
                    }
                }
                
                // Spawn if we have enough activated or scan is done
                if (instance.getStakeSpawnerPositions().size() >= config.requirements.requiredStakes || instance.isStakeSpawnerScanComplete()) {
                    instance.setStakesSpawned(true);
                    spawnStakes(world, instance);
                }
            }

            if (budgetRemaining <= 0) continue;

            // 4. Guardian Scans
            if (!instance.areGuardiansSpawned()) {
                int scanned = scanGuardianSpawnerChunks(world, instance, config, budgetRemaining);
                budgetRemaining -= scanned;
                
                // Activation Logic for Guardians (similar to Alphas)
                int targetCount = instance.getGuardianSpawnTarget();
                List<BlockPos> activatedSpawners = instance.getGuardianSpawnerPositions();
                Set<BlockPos> discoveredSpawners = instance.getDiscoveredGuardianSpawners();

                if (targetCount > 0) {
                    if (activatedSpawners.size() < targetCount && !discoveredSpawners.isEmpty()) {
                        List<BlockPos> candidates = new ArrayList<>();
                        for (BlockPos discovered : discoveredSpawners) {
                            if (!activatedSpawners.contains(discovered)) {
                                candidates.add(discovered);
                            }
                        }

                        if (!candidates.isEmpty()) {
                            Collections.shuffle(candidates);
                            int toAdd = Math.min(candidates.size(), targetCount - activatedSpawners.size());
                            for (int i = 0; i < toAdd; i++) {
                                instance.addGuardianSpawnerPosition(candidates.get(i));
                            }
                        }
                    }
                } else if (!discoveredSpawners.isEmpty()) {
                    for (BlockPos discovered : discoveredSpawners) {
                        instance.addGuardianSpawnerPosition(discovered);
                    }
                }

                if (instance.getGuardianSpawnerPositions().size() >= config.requirements.requiredGuardians || instance.isGuardianSpawnerScanComplete()) {
                    instance.setGuardiansSpawned(true);
                    spawnGuardians(world, instance);
                }
            }

            if (budgetRemaining <= 0) continue;

            // 5. Chest Scans
            if (!instance.areGemsAssigned()) {
                int scanned = scanChestChunks(world, instance, config, budgetRemaining);
                budgetRemaining -= scanned;

                if (instance.isChestScanComplete()) {
                    // Final assignment if done
                    instance.assignGems();
                }
            }
        }
    }

    private static void tickTrainerSpawns(MinecraftServer server, DungeonInstance instance) {
        if (instance == null || !instance.hasTrainerSpawnsPending()) return;

        ServerWorld world = getDungeonWorld(server, instance);
        if (world == null) return;

        int spawnsPerTick = 2; // Process 2 trainers per tick to avoid lag
        for (int i = 0; i < spawnsPerTick; i++) {
            DungeonInstance.TrainerSpawnTask task = instance.pollTrainerSpawn();
            if (task == null) break;

            spawnTrainerDirectly(world, task.trainerId(), task.x(), task.y(), task.z(), task.level(), task.invulnerable(), task.isGuardian());
        }
    }

    public static void handlePlayerDeath(ServerPlayerEntity player) {
        DungeonInstance instance = activeInstances.get(player.getUuid());
        if (instance != null) {
            recallPlayerPokemon(player);
            instance.decrementLives();
            broadcastDungeonEvent("dungeon_player_death", instance, player.getServer());
            int remainingLives = instance.getLives();

            if (remainingLives > 0) {
                Text message = Text.translatable("cobblemondungeons.player_death_lives", remainingLives).formatted(Formatting.RED);
                for (UUID uuid : instance.getPlayers()) {
                    ServerPlayerEntity p = player.getServer().getPlayerManager().getPlayer(uuid);
                    if (p != null) {
                        p.sendMessage(message);
                    }
                }
            } else {
                failDungeon(instance, player.getServer(), Text.translatable("cobblemondungeons.lives_depleted").formatted(Formatting.RED, Formatting.BOLD));
            }
        }
    }

    public static void handleDisconnect(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        activeCountdowns.remove(uuid);
        DungeonInstance instance = activeInstances.remove(uuid);
        if (instance != null) {
            instance.getPlayers().remove(uuid);
            if (instance.getPlayers().isEmpty()) {
                clearDungeonEntities(instance, player.getServer());
                if (instance.getPartyId() != null) {
                    partyInstances.remove(instance.getPartyId());
                }
            }
        }
    }

    public static void clearDungeonEntities(DungeonInstance instance, net.minecraft.server.MinecraftServer server) {
        if (instance == null || server == null) return;
        
        if (instance.getBossUuid() != null) {
            bossToInstance.remove(instance.getBossUuid());
        }

        DungeonConfig config = instance.getConfig();
        if (config == null || instance.getActiveLocations().spawnPos == null || instance.getActiveLocations().spawnPos.dimension == null) return;

        ServerWorld world = null;
        for (UUID uuid : instance.getPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                world = player.getServerWorld();
                break;
            }
        }
        if (world == null) {
            try {
                net.minecraft.util.Identifier dimensionId = net.minecraft.util.Identifier.of(instance.getActiveLocations().spawnPos.dimension);
                net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, dimensionId);
                world = server.getWorld(worldKey);
            } catch (Exception ignored) {}
        }
        if (world == null) return;

        // Reset Stake Spawners
        List<BlockPos> stakePositions = instance.getStakeSpawnerPositions();
        for (BlockPos pos : stakePositions) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(com.sebastian.cobblemondungeons.CobblemonDungeons.STAKE_SPAWNER_BLOCK)) {
                world.setBlockState(pos, state.with(com.sebastian.cobblemondungeons.block.StakeSpawnerBlock.STAKE_PRESENT, false));
            }
        }

        // Reset Portals
        for (BlockPos pos : instance.getPortalPositions()) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(com.sebastian.cobblemondungeons.CobblemonDungeons.PORTAL_BLOCK)) {
                world.setBlockState(pos, state.with(com.sebastian.cobblemondungeons.block.PortalBlock.VISIBLE, false));
            }
        }

        double radius = config.spawning.alphaSpawnRadius + 50.0;
        net.minecraft.util.math.Box searchBox = new net.minecraft.util.math.Box(instance.getActiveLocations().spawnPos.x, instance.getActiveLocations().spawnPos.y, instance.getActiveLocations().spawnPos.z, instance.getActiveLocations().spawnPos.x, instance.getActiveLocations().spawnPos.y, instance.getActiveLocations().spawnPos.z).expand(radius);
        boolean isDedicatedServer = com.sebastian.cobblemondungeons.CobblemonDungeons.isDungeonServer(server);

        List<net.minecraft.entity.Entity> entities = world.getEntitiesByClass(net.minecraft.entity.Entity.class, searchBox, e -> !(e instanceof ServerPlayerEntity));
        for (net.minecraft.entity.Entity entity : entities) {
            if (!isDedicatedServer) {
                // On survival server, be selective
                if (entity instanceof PokemonEntity pokemonEntity) {
                    if (pokemonEntity.getPokemon().getAspects().contains("alpha") || pokemonEntity.getPokemon().getOwnerUUID() == null) {
                        com.sebastian.cobblemondungeons.CobblemonDungeons.clearAlphaData(entity.getUuid());
                        entity.discard();
                    }
                    continue;
                }
                
                if (entity instanceof net.minecraft.entity.ItemEntity) {
                    entity.discard();
                    continue;
                }

                String lowerName = entity.getName().getString().toLowerCase();
                if (lowerName.contains("key") || lowerName.contains("gem") || lowerName.contains("stake") || lowerName.contains("boss")) {
                    if (!lowerName.contains("dungeon master")) {
                        com.sebastian.cobblemondungeons.CobblemonDungeons.clearAlphaData(entity.getUuid());
                        entity.discard();
                    }
                }
            } else {
                // On dedicated server, discard everything non-player as per original implementation
                com.sebastian.cobblemondungeons.CobblemonDungeons.clearAlphaData(entity.getUuid());
                entity.discard();
            }
        }
    }

    public static void handlePokemonTeamWipe(ServerPlayerEntity player) {
        DungeonInstance instance = activeInstances.get(player.getUuid());
        if (instance != null) {
            recallPlayerPokemon(player);
            instance.decrementLives();
            int remainingLives = instance.getLives();

            if (remainingLives > 0) {
                // Heal team
                healParty(player);

                // Teleport to spawn/boss
                if (com.sebastian.cobblemondungeons.CobblemonDungeons.isDungeonServer(player.getServer())) {
                    if (instance.canAccessBoss() && instance.hasVisitedBossRoom(player.getUuid())) {
                        teleportToDungeon(player, instance.getConfig().locations.bossRoomPos);
                    } else {
                        teleportToDungeon(player, instance.getConfig().locations.spawnPos);
                    }
                }

                player.sendMessage(Text.translatable("cobblemondungeons.team_fainted_lives", remainingLives).formatted(Formatting.RED));

                // Notify others
                Text notifyMessage = Text.translatable("cobblemondungeons.team_fainted_notify", player.getName().getString(), remainingLives).formatted(Formatting.RED);
                for (UUID uuid : instance.getPlayers()) {
                    if (uuid.equals(player.getUuid())) continue;
                    ServerPlayerEntity p = player.getServer().getPlayerManager().getPlayer(uuid);
                    if (p != null) {
                        p.sendMessage(notifyMessage);
                    }
                }
            } else {
                failDungeon(instance, player.getServer(), Text.translatable("cobblemondungeons.lives_depleted").formatted(Formatting.RED, Formatting.BOLD));
            }
        }
    }

    public static void handleRejoin(ServerPlayerEntity player) {
        DungeonsParty party = DungeonsPartyManager.getParty(player.getUuid());
        if (party != null) {
            UUID partyId = party.getLeader();
            DungeonInstance instance = partyInstances.get(partyId);
            if (instance != null) {
                instance.getPlayers().add(player.getUuid());
                activeInstances.put(player.getUuid(), instance);

                DungeonConfig config = instance.getConfig();
                if (instance.isTeleported()) {
                    if (instance.canAccessBoss() && instance.hasVisitedBossRoom(player.getUuid())) {
                        teleportToDungeon(player, instance.getActiveLocations().bossRoomPos);
                    } else {
                        teleportToDungeon(player, instance.getActiveLocations().spawnPos);
                    }
                } else {
                    teleportToDungeon(player, instance.getActiveLocations().hubPos);
                }

                // Send initial HUD update
                ServerPlayNetworking.send(player, new DungeonHudPayload(
                    instance.getConfig().general.name,
                    instance.getAlphasDefeated(), instance.getConfig().requirements.requiredAlphas,
                    instance.getGemsFound(), instance.getConfig().requirements.requiredGems,
                    instance.getGuardiansDefeated(), instance.getConfig().requirements.requiredGuardians,
                    instance.getStakesFound(), instance.getConfig().requirements.requiredStakes,
                    instance.getTimeLeft(),
                    instance.getLives(),
                    !instance.isBossDefeated()
                ));
            }
        }
    }

    public static void handlePlayerRespawn(ServerPlayerEntity player) {
        DungeonInstance instance = activeInstances.get(player.getUuid());
        if (instance != null) {
            DungeonConfig config = instance.getConfig();
            if (instance.canAccessBoss() && instance.hasVisitedBossRoom(player.getUuid())) {
                teleportToDungeon(player, instance.getActiveLocations().bossRoomPos);
            } else {
                teleportToDungeon(player, instance.getActiveLocations().spawnPos);
            }
        }
    }

    public static void recallPlayerPokemon(ServerPlayerEntity player) {
        if (player == null || player.getServer() == null) return;
        UUID ownerUuid = player.getUuid();
        for (ServerWorld world : player.getServer().getWorlds()) {
            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                if (entity instanceof PokemonEntity pokemonEntity) {
                    if (ownerUuid.equals(pokemonEntity.getPokemon().getOwnerUUID())) {
                        pokemonEntity.discard();
                    }
                }
            }
        }
    }

    private static void failDungeon(DungeonInstance instance, net.minecraft.server.MinecraftServer server, Text failMessage) {
        logRun(instance, server, false);
        broadcastDungeonEvent("dungeon_fail", instance, server);
        clearDungeonEntities(instance, server);
        List<UUID> members = new ArrayList<>(instance.getPlayers());
        for (UUID uuid : members) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) {
                p.sendMessage(failMessage);
                leaveDungeon(p);
            } else {
                activeInstances.remove(uuid);
            }
        }
    }

    private static void healParty(ServerPlayerEntity player) {
        var party = com.cobblemon.mod.common.Cobblemon.INSTANCE.getStorage().getParty(player);
        for (com.cobblemon.mod.common.pokemon.Pokemon pokemon : party) {
            pokemon.heal();
        }
    }

    public static boolean teleportToReturnPos(ServerPlayerEntity player, NbtCompound returnPos) {
        if (player == null || returnPos == null) return false;
        try {
            double x = returnPos.getDouble("x");
            double y = returnPos.getDouble("y");
            double z = returnPos.getDouble("z");
            float yaw = returnPos.getFloat("yaw");
            float pitch = returnPos.getFloat("pitch");
            String dim = returnPos.getString("dim");
            
            ServerWorld world = player.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, net.minecraft.util.Identifier.of(dim)));
            if (world != null) {
                player.teleport(world, x, y, z, yaw, pitch);
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Teleported returning player " + player.getName().getString() + " to " + x + "," + y + "," + z + " in " + dim);
                return true;
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to teleport returning player " + player.getName().getString(), e);
        }
        return false;
    }

    public static NbtCompound serializePlayerData(ServerPlayerEntity player, boolean includePc) {
        try {
            NbtCompound nbt = PlayerSyncHandler.serialize(player, includePc);
            if (nbt != null) {
                if (!com.sebastian.cobblemondungeons.CobblemonDungeons.isDungeonServer(player.getServer())) {
                    NbtCompound posNbt = new NbtCompound();
                    posNbt.putDouble("x", player.getX());
                    posNbt.putDouble("y", player.getY());
                    posNbt.putDouble("z", player.getZ());
                    posNbt.putFloat("yaw", player.getYaw());
                    posNbt.putFloat("pitch", player.getPitch());
                    posNbt.putString("dim", player.getWorld().getRegistryKey().getValue().toString());
                    nbt.put("DungeonReturnPos", posNbt);
                } else {
                    NbtCompound returnPos = returnPositions.get(player.getUuid());
                    if (returnPos != null) {
                        nbt.put("DungeonReturnPos", returnPos.copy());
                    }
                }
            }
            return nbt;
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to serialize player data for " + player.getName().getString(), e);
            return null;
        }
    }

    public static void handleCrossServerSync(net.minecraft.server.MinecraftServer server, UUID playerUuid, NbtCompound data) {
        pendingSyncData.put(playerUuid, data);
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
        if (player != null) {
            applyPendingSync(player);
        }
    }

    public static void applyPendingSync(ServerPlayerEntity player) {
        applyPendingSync(player, false);
    }

    public static void applyPendingSync(ServerPlayerEntity player, boolean force) {
        NbtCompound data = null;
        
        // Try SQL first if enabled (it's the most complete data source)
        if (com.sebastian.cobblemondungeons.util.DatabaseManager.isEnabled()) {
            data = com.sebastian.cobblemondungeons.util.DatabaseManager.loadPlayerData(player.getUuid());
            if (data != null) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Loaded sync data from SQL for " + player.getName().getString());
            }
        }

        // Fallback to pending data from payload if SQL failed or is disabled
        if (data == null) {
            data = pendingSyncData.remove(player.getUuid());
        } else {
            // Clean up pending data even if we used SQL
            pendingSyncData.remove(player.getUuid());
        }

        if (data != null) {
            // Extract return position if present
            if (data.contains("DungeonReturnPos")) {
                NbtCompound pos = data.getCompound("DungeonReturnPos");
                returnPositions.put(player.getUuid(), pos);
                
                // If on main server and NOT in a dungeon instance or countdown, teleport back
                if (!com.sebastian.cobblemondungeons.CobblemonDungeons.isDungeonServer(player.getServer())) {
                    if (!activeInstances.containsKey(player.getUuid()) && !activeCountdowns.containsKey(player.getUuid())) {
                        if (teleportToReturnPos(player, pos)) {
                            returnPositions.remove(player.getUuid());
                        }
                    }
                }
            }
            try {
                PlayerSyncHandler.deserialize(player, data);
                player.sendMessage(Text.translatable("cobblemondungeons.data_synced").formatted(Formatting.GREEN));
            } catch (Exception e) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to apply sync data for " + player.getName().getString(), e);
                player.sendMessage(Text.translatable("cobblemondungeons.sync_failed").formatted(Formatting.RED));
            }
        }
        boolean needsCobblemonResync = false;
        if (com.sebastian.cobblemondungeons.util.DatabaseManager.isEnabled()) {
            needsCobblemonResync = true;
        } else if (data != null) {
            needsCobblemonResync = force || shouldApplyCobblemonResync(player, data);
        }

        if (needsCobblemonResync) {
            if (com.sebastian.cobblemondungeons.util.DatabaseManager.isEnabled()) {
                scheduleDelayedSqlSync(player.getUuid(), 40, force);
            } else if (data != null) {
                scheduleDelayedSync(player.getUuid(), data, 40, force);
            }
        }

    }

    public static void handleCrossServerJoin(ServerPlayerEntity player, String dungeonId, UUID partyId, int partySize, NbtCompound syncData, int tier) {
        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Player " + player.getName().getString() + " joining dungeon " + dungeonId + " (Tier " + tier + ") via cross-server join (Party size: " + partySize + ")");
        long joinStart = System.nanoTime();
        if (syncData != null) {
            pendingSyncData.put(player.getUuid(), syncData);
        }
        applyPendingSync(player, true);
        
        // Always queue a pending party apply to ensure it's synced correctly after join,
        // regardless of whether we are joining a dungeon or returning to main.
        if (syncData != null && syncData.contains("CobblemonParty")) {
            NbtCompound partyNbt = syncData.getCompound("CobblemonParty");
            if (!partyNbt.isEmpty()) {
                PartySyncHandler.queuePendingParty(player, partyNbt, player.getServer());
            }
        }

        if (dungeonId == null || dungeonId.isEmpty()) {
            // Returning to main server - teleport if we have a return position
            NbtCompound returnPos = returnPositions.remove(player.getUuid());
            if (returnPos != null && !com.sebastian.cobblemondungeons.CobblemonDungeons.isDungeonServer(player.getServer())) {
                teleportToReturnPos(player, returnPos);
            }
            return;
        }

        DungeonConfig config = dungeons.get(dungeonId);
        if (config != null) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Dungeon config found for " + dungeonId + ", initializing instance for " + player.getName().getString());
            ensureAliveForDungeonJoin(player);
            player.sendMessage(Text.translatable("cobblemondungeons.dungeon_joined", config.general.name));
            player.sendMessage(Text.translatable("cobblemondungeons.preparing").formatted(Formatting.YELLOW));
            
            DungeonInstance instance;
            if (partyId != null) {
                instance = partyInstances.get(partyId);
                if (instance == null) {
                    int slot = findAvailableSlot(dungeonId);
                    if (slot == -1) {
                        player.sendMessage(Text.translatable("cobblemondungeons.dungeons_full").formatted(Formatting.RED));
                        ServerPlayNetworking.send(player, new DungeonJoinPayload("", null, 1, syncData, 1));
                        transferPlayerDelayed(player, config.networking.mainServer);
                        return;
                    }

                    instance = new DungeonInstance(config, new HashSet<>(List.of(player.getUuid())), partyId, tier, partySize, slot);
                    partyInstances.put(partyId, instance);
                } else {
                    instance.getPlayers().add(player.getUuid());
                }
            } else {
                int slot = findAvailableSlot(dungeonId);
                if (slot == -1) {
                    player.sendMessage(Text.translatable("cobblemondungeons.dungeons_full").formatted(Formatting.RED));
                    ServerPlayNetworking.send(player, new DungeonJoinPayload("", null, 1, syncData, 1));
                    transferPlayerDelayed(player, config.networking.mainServer);
                    return;
                }

                instance = new DungeonInstance(config, new HashSet<>(List.of(player.getUuid())), null, tier, partySize, slot);
            }
            activeInstances.put(player.getUuid(), instance);

            // Teleport to hub if not prepared yet, otherwise to spawn/boss
            if (instance.isTeleported()) {
                if (instance.canAccessBoss() && instance.hasVisitedBossRoom(player.getUuid())) {
                    teleportToDungeon(player, instance.getActiveLocations().bossRoomPos);
                } else {
                    teleportToDungeon(player, instance.getActiveLocations().spawnPos);
                }
            } else {
                teleportToDungeon(player, instance.getActiveLocations().hubPos);
            }

            // Send initial HUD update
            ServerPlayNetworking.send(player, new DungeonHudPayload(
                instance.getConfig().general.name,
                instance.getAlphasDefeated(), instance.getConfig().requirements.requiredAlphas,
                instance.getGemsFound(), instance.getConfig().requirements.requiredGems,
                instance.getGuardiansDefeated(), instance.getConfig().requirements.requiredGuardians,
                instance.getStakesFound(), instance.getConfig().requirements.requiredStakes,
                instance.getTimeLeft(),
                instance.getLives(),
                !instance.isBossDefeated()
            ));

            if (!instance.areAlphasSpawned()) {
                spawnAlphas(player.getServerWorld(), config, new BlockPos((int)instance.getActiveLocations().spawnPos.x, (int)instance.getActiveLocations().spawnPos.y, (int)instance.getActiveLocations().spawnPos.z), instance);
                discoverChests(player.getServerWorld(), config, instance);
                initializeStakeSpawning(player.getServerWorld(), config, instance);
                initializeGuardianSpawning(player.getServerWorld(), config, instance);
                instance.setAlphasSpawned(true);
            }
        }
        long joinNanos = System.nanoTime() - joinStart;
        if (joinNanos >= 50_000_000L) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.warn(
                "Slow dungeon join {}ms for {}",
                joinNanos / 1_000_000.0,
                player.getName().getString()
            );
        }
    }

    public static void discoverChests(ServerWorld world, DungeonConfig config, DungeonInstance instance) {
        if (world == null || config == null || instance == null) return;
        
        BlockPos center = new BlockPos((int) instance.getActiveLocations().spawnPos.x, (int) instance.getActiveLocations().spawnPos.y, (int) instance.getActiveLocations().spawnPos.z);
        int radius = config.spawning.chestSpawnRadius;
        
        List<BlockPos> chests = com.sebastian.cobblemondungeons.CobblemonDungeons.getDungeonChestsInRange(world, center, radius, config.spawning.chestIds);
        for (BlockPos pos : chests) {
            if (!isSpawnerClaimedByAnother(instance, pos)) {
                instance.addDiscoveredChest(pos);
            }
        }

        // Fallback scan if registry is empty - using chunks near players but indexed
        if (instance.getDiscoveredChests().isEmpty()) {
            int chunkRadius = 2;
            boolean filterIds = config.spawning.chestIds != null && !config.spawning.chestIds.isEmpty();
            for (UUID uuid : instance.getPlayers()) {
                ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(uuid);
                if (p == null) continue;
                net.minecraft.util.math.ChunkPos centerChunk = new net.minecraft.util.math.ChunkPos(p.getBlockPos());
                for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                    for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                        long chunkKey = net.minecraft.util.math.ChunkPos.toLong(centerChunk.x + dx, centerChunk.z + dz);
                        it.unimi.dsi.fastutil.longs.LongArrayList indexed = com.sebastian.cobblemondungeons.CobblemonDungeons.getDungeonChestsInChunk(world, chunkKey);
                        if (indexed != null) {
                            for (long posLong : indexed) {
                                BlockPos pos = BlockPos.fromLong(posLong);
                                if (filterIds) {
                                    Block block = world.getBlockState(pos).getBlock();
                                    Integer id = com.sebastian.cobblemondungeons.CobblemonDungeons.DUNGEON_CHEST_BLOCK_IDS.get(block);
                                    if (id == null || !config.spawning.chestIds.contains(id)) continue;
                                }
                                if (!isSpawnerClaimedByAnother(instance, pos)) {
                                    instance.addDiscoveredChest(pos);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public static void spawnAlphas(ServerWorld world, DungeonConfig config, BlockPos center, DungeonInstance instance) {
        if (config.spawning.alphaPool == null || config.spawning.alphaPool.isEmpty()) return;
        if (instance == null) return;

        int targetCount = config.requirements.requiredAlphas > 0 ? config.requirements.requiredAlphas : 0;
        instance.setAlphaSpawnerPositions(java.util.List.of(), targetCount);
        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info(
            "Alpha spawners deferred for {} (cap {})",
            config.general.name,
            targetCount > 0 ? targetCount : "all"
        );
    }

    private static void tickAlphaSpawners(net.minecraft.server.MinecraftServer server, DungeonInstance instance) {
        if (instance == null) return;
        DungeonConfig config = instance.getConfig();
        if (config == null) return;

        int targetCount = instance.getAlphaSpawnTarget();
        List<BlockPos> activatedSpawners = instance.getAlphaSpawnerPositions();
        Set<BlockPos> discoveredSpawners = instance.getDiscoveredAlphaSpawners();

        // Activation Logic: Pick random spawners from discovered ones until we reach targetCount
        if (targetCount > 0) {
            if (activatedSpawners.size() < targetCount && !discoveredSpawners.isEmpty()) {
                List<BlockPos> candidates = new ArrayList<>();
                for (BlockPos discovered : discoveredSpawners) {
                    if (!activatedSpawners.contains(discovered)) {
                        candidates.add(discovered);
                    }
                }

                if (!candidates.isEmpty()) {
                    Collections.shuffle(candidates);
                    int toAdd = Math.min(candidates.size(), targetCount - activatedSpawners.size());
                    for (int i = 0; i < toAdd; i++) {
                        instance.addAlphaSpawnerPosition(candidates.get(i));
                    }

                    com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info(
                        "Activated {} more Alpha spawners for {} (total activated: {})",
                        toAdd, config.general.name, activatedSpawners.size()
                    );
                }
            }
        } else {
            // If targetCount is 0, activate all discovered spawners
            for (BlockPos discovered : discoveredSpawners) {
                instance.addAlphaSpawnerPosition(discovered);
            }
        }

        if (activatedSpawners.isEmpty()) {
            discoverAlphaSpawnersLocal(server, instance, config);
            return;
        }

        if (targetCount > 0 && instance.getAlphaSpawnedCount() >= targetCount) return;

        double maxDistSq = config.spawning.alphaTriggerRadius * config.spawning.alphaTriggerRadius;
        int maxToSpawn = targetCount > 0 ? targetCount : activatedSpawners.size();

        if (!instance.isAlphaSpawnerDebugLogged()) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info(
                "Alpha spawners ready for {}: activated={} target={}",
                config.general.name,
                activatedSpawners.size(),
                maxToSpawn
            );
            instance.setAlphaSpawnerDebugLogged(true);
        }

        for (UUID uuid : instance.getPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) continue;
            ServerWorld world = player.getServerWorld();
            for (BlockPos pos : activatedSpawners) {
                if (instance.isAlphaSpawnerSpawned(pos)) continue;
                double distSq = player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distSq > maxDistSq) continue;
                if (spawnAlphaAt(world, config, pos, instance.getTier())) {
                    instance.markAlphaSpawnerSpawned(pos);
                }
                if (instance.getAlphaSpawnedCount() >= maxToSpawn) return;
            }
        }
    }

    private static void discoverAlphaSpawnersLocal(MinecraftServer server, DungeonInstance instance, DungeonConfig config) {
        int radius = config.spawning.alphaSpawnRadius;
        int chunkRadius = (radius + 15) / 16;
        int added = 0;
        boolean filterIds = config.spawning.alphaSpawnerIds != null && !config.spawning.alphaSpawnerIds.isEmpty();
        for (UUID uuid : instance.getPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) continue;
            ServerWorld world = player.getServerWorld();
            net.minecraft.util.math.ChunkPos centerChunk = new net.minecraft.util.math.ChunkPos(player.getBlockPos());
            
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    long chunkKey = net.minecraft.util.math.ChunkPos.toLong(centerChunk.x + dx, centerChunk.z + dz);
                    it.unimi.dsi.fastutil.longs.LongArrayList indexed = com.sebastian.cobblemondungeons.CobblemonDungeons.getAlphaSpawnersInChunk(world, chunkKey);
                    if (indexed != null) {
                        for (long posLong : indexed) {
                            BlockPos pos = BlockPos.fromLong(posLong);
                            if (filterIds) {
                                Block block = world.getBlockState(pos).getBlock();
                                Integer id = com.sebastian.cobblemondungeons.CobblemonDungeons.ALPHA_SPAWNER_BLOCK_IDS.get(block);
                                if (id == null || !config.spawning.alphaSpawnerIds.contains(id)) continue;
                            }
                            if (!isSpawnerClaimedByAnother(instance, pos) && instance.addDiscoveredAlphaSpawner(pos)) {
                                added++;
                            }
                        }
                    }
                }
            }
        }
        if (added > 0) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info(
                "Discovered {} Alpha spawners near players in {} (local index scan)",
                added,
                config.general.name
            );
        }
    }

    private static int scanAlphaSpawnerChunks(MinecraftServer server, ServerWorld world, DungeonInstance instance, DungeonConfig config, int budget) {
        if (world == null) return 0;

        int radius = Math.max(32, config.spawning.alphaSpawnRadius);
        int chunkRadius = Math.max(1, (radius + 15) / 16);
        if (!instance.hasAlphaSpawnerScanQueue()) {
            net.minecraft.util.math.ChunkPos spawnChunk = new net.minecraft.util.math.ChunkPos(
                new BlockPos((int) instance.getActiveLocations().spawnPos.x, (int) instance.getActiveLocations().spawnPos.y, (int) instance.getActiveLocations().spawnPos.z)
            );
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    long key = net.minecraft.util.math.ChunkPos.toLong(spawnChunk.x + dx, spawnChunk.z + dz);
                    instance.enqueueAlphaSpawnerScanChunk(key);
                }
            }
            for (UUID uuid : instance.getPlayers()) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player == null) continue;
                net.minecraft.util.math.ChunkPos centerChunk = new net.minecraft.util.math.ChunkPos(player.getBlockPos());
                for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                    for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                        long key = net.minecraft.util.math.ChunkPos.toLong(centerChunk.x + dx, centerChunk.z + dz);
                        instance.enqueueAlphaSpawnerScanChunk(key);
                    }
                }
            }
        }

        int verticalRange = Math.max(16, config.spawning.spawnerScanVerticalRange);
        int centerY = (int) instance.getActiveLocations().spawnPos.y;
        int minY = Math.max(world.getBottomY(), centerY - verticalRange);
        int maxY = Math.min(world.getTopY() - 1, centerY + verticalRange);

        int scanned = 0;
        int added = 0;
        boolean filterIds = config.spawning.alphaSpawnerIds != null && !config.spawning.alphaSpawnerIds.isEmpty();
        while (scanned < budget) {
            Long key = instance.pollAlphaSpawnerScanChunk();
            if (key == null) break;
            scanned++;
            
            it.unimi.dsi.fastutil.longs.LongArrayList indexed = com.sebastian.cobblemondungeons.CobblemonDungeons.getAlphaSpawnersInChunk(world, key);
            if (indexed != null) {
                for (long posLong : indexed) {
                    BlockPos pos = BlockPos.fromLong(posLong);
                    if (pos.getY() >= minY && pos.getY() <= maxY) {
                        if (filterIds) {
                            Block block = world.getBlockState(pos).getBlock();
                            Integer id = com.sebastian.cobblemondungeons.CobblemonDungeons.ALPHA_SPAWNER_BLOCK_IDS.get(block);
                            if (id == null || !config.spawning.alphaSpawnerIds.contains(id)) continue;
                        }
                        if (!isSpawnerClaimedByAnother(instance, pos) && instance.addDiscoveredAlphaSpawner(pos)) {
                            added++;
                        }
                    }
                }
            }
        }
        if (added > 0) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info(
                "Discovered {} Alpha spawners near players in {}",
                added,
                config.general.name
            );
        }
        return scanned;
    }

    private static boolean tryPopulateAlphaSpawnersFromRegistry(ServerWorld world, DungeonInstance instance, DungeonConfig config) {
        if (world == null) return false;

        BlockPos center = new BlockPos((int) instance.getActiveLocations().spawnPos.x, (int) instance.getActiveLocations().spawnPos.y, (int) instance.getActiveLocations().spawnPos.z);
        int radius = config.spawning.alphaSpawnRadius;
        List<BlockPos> found = com.sebastian.cobblemondungeons.CobblemonDungeons.getAlphaSpawnersInRange(
            world,
            center,
            radius,
            config.spawning.alphaSpawnerIds
        );
        
        for (BlockPos pos : found) {
            if (!isSpawnerClaimedByAnother(instance, pos)) {
                instance.addDiscoveredAlphaSpawner(pos);
            }
        }
        return !instance.getDiscoveredAlphaSpawners().isEmpty();
    }

    private static int scanStakeSpawnerChunks(ServerWorld world, DungeonInstance instance, DungeonConfig config, int budget) {
        if (world == null) return 0;

        if (!instance.isStakeSpawnerScanStarted()) {
            initializeStakeSpawning(world, config, instance);
        }

        int verticalRange = Math.max(16, config.spawning.spawnerScanVerticalRange);
        int centerY = (int) instance.getActiveLocations().spawnPos.y;
        int minY = Math.max(world.getBottomY(), centerY - verticalRange);
        int maxY = Math.min(world.getTopY() - 1, centerY + verticalRange);

        int scanned = 0;
        int added = 0;
        boolean filterIds = config.spawning.stakeSpawnerIds != null && !config.spawning.stakeSpawnerIds.isEmpty();
        while (scanned < budget) {
            Long key = instance.pollStakeSpawnerScanChunk();
            if (key == null) break;
            scanned++;
            
            it.unimi.dsi.fastutil.longs.LongArrayList indexed = com.sebastian.cobblemondungeons.CobblemonDungeons.getStakeSpawnersInChunk(world, key);
            if (indexed != null) {
                for (long posLong : indexed) {
                    BlockPos pos = BlockPos.fromLong(posLong);
                    if (pos.getY() >= minY && pos.getY() <= maxY) {
                        if (filterIds) {
                            Block block = world.getBlockState(pos).getBlock();
                            Integer id = com.sebastian.cobblemondungeons.CobblemonDungeons.STAKE_SPAWNER_BLOCK_IDS.get(block);
                            if (id == null || !config.spawning.stakeSpawnerIds.contains(id)) continue;
                        }
                        if (!isSpawnerClaimedByAnother(instance, pos) && instance.addDiscoveredStakeSpawner(pos)) {
                            added++;
                        }
                    }
                }
            }
        }
        if (added > 0) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info(
                "Discovered {} Stake spawners in {}",
                added,
                config.general.name
            );
        }
        return scanned;
    }

    private static void initializeStakeSpawning(ServerWorld world, DungeonConfig config, DungeonInstance instance) {
        if (instance.isStakeSpawnerScanStarted()) return;
        instance.setStakeSpawnerScanStarted(true);

        int radius = Math.max(32, config.spawning.stakeSpawnRadius);
        int chunkRadius = Math.max(1, (radius + 15) / 16);
        net.minecraft.util.math.ChunkPos spawnChunk = new net.minecraft.util.math.ChunkPos(
            new BlockPos((int) instance.getActiveLocations().spawnPos.x, (int) instance.getActiveLocations().spawnPos.y, (int) instance.getActiveLocations().spawnPos.z)
        );

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                long key = net.minecraft.util.math.ChunkPos.toLong(spawnChunk.x + dx, spawnChunk.z + dz);
                instance.enqueueStakeSpawnerScanChunk(key);
                // Add ticket to load chunk
                world.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.POST_TELEPORT, new net.minecraft.util.math.ChunkPos(spawnChunk.x + dx, spawnChunk.z + dz), 1, 0);
            }
        }
    }

    private static boolean tryPopulateStakeSpawnersFromRegistry(ServerWorld world, DungeonInstance instance, DungeonConfig config) {
        if (config.requirements.requiredStakes <= 0) return true;
        if (world == null) return false;

        BlockPos center = new BlockPos((int) instance.getActiveLocations().spawnPos.x, (int) instance.getActiveLocations().spawnPos.y, (int) instance.getActiveLocations().spawnPos.z);
        List<BlockPos> spawners = com.sebastian.cobblemondungeons.CobblemonDungeons.getStakeSpawnersInRange(world, center, config.spawning.stakeSpawnRadius, config.spawning.stakeSpawnerIds);
        for (BlockPos pos : spawners) {
            if (!isSpawnerClaimedByAnother(instance, pos)) {
                instance.addDiscoveredStakeSpawner(pos);
            }
        }
        return !instance.getDiscoveredStakeSpawners().isEmpty();
    }

    private static boolean tryPopulateGuardianSpawnersFromRegistry(ServerWorld world, DungeonInstance instance, DungeonConfig config) {
        if (world == null) return false;

        BlockPos center = new BlockPos((int) instance.getActiveLocations().spawnPos.x, (int) instance.getActiveLocations().spawnPos.y, (int) instance.getActiveLocations().spawnPos.z);
        List<BlockPos> spawners = com.sebastian.cobblemondungeons.CobblemonDungeons.getGuardianSpawnersInRange(world, center, config.spawning.guardianSpawnRadius, config.spawning.guardianSpawnerIds);
        for (BlockPos pos : spawners) {
            if (!isSpawnerClaimedByAnother(instance, pos)) {
                instance.addDiscoveredGuardianSpawner(pos);
            }
        }
        return !instance.getDiscoveredGuardianSpawners().isEmpty();
    }

    private static boolean tryPopulatePortalsFromRegistry(ServerWorld world, DungeonInstance instance, DungeonConfig config) {
        if (world == null) return false;

        BlockPos center = new BlockPos((int) instance.getActiveLocations().spawnPos.x, (int) instance.getActiveLocations().spawnPos.y, (int) instance.getActiveLocations().spawnPos.z);
        List<BlockPos> found = com.sebastian.cobblemondungeons.CobblemonDungeons.getPortalsInRange(world, center, config.spawning.portalSpawnRadius, config.spawning.portalIds);
        for (BlockPos pos : found) {
            if (!isSpawnerClaimedByAnother(instance, pos)) {
                instance.addPortalPosition(pos);
            }
        }
        return !instance.getPortalPositions().isEmpty();
    }

    private static void spawnStakes(ServerWorld world, DungeonInstance instance) {
        DungeonConfig config = instance.getConfig();
        if (config.requirements.requiredStakes <= 0 && instance.getStakeSpawnTarget() <= 0) return;

        if (world == null) return;

        List<BlockPos> spawners = instance.getStakeSpawnerPositions();
        if (spawners.isEmpty()) return;

        int spawned = 0;
        for (BlockPos pos : spawners) {
            BlockState spawnerState = world.getBlockState(pos);
            if (spawnerState.isOf(com.sebastian.cobblemondungeons.CobblemonDungeons.STAKE_SPAWNER_BLOCK)) {
                world.setBlockState(pos, spawnerState.with(com.sebastian.cobblemondungeons.block.StakeSpawnerBlock.STAKE_PRESENT, true));
                spawned++;
            }
        }
        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Spawned {} Stakes in {}", spawned, config.general.name);
    }

    private static void tickGuardianSpawners(net.minecraft.server.MinecraftServer server, DungeonInstance instance) {
        if (instance == null) return;
        DungeonConfig config = instance.getConfig();
        if (config == null || config.spawning.guardianPool == null || config.spawning.guardianPool.isEmpty()) return;

        // Discovery is now handled in tickFastScans via scanGuardianSpawners
    }

    private static int scanGuardianSpawnerChunks(ServerWorld world, DungeonInstance instance, DungeonConfig config, int budget) {
        if (world == null) return 0;

        if (!instance.isGuardianSpawnerScanStarted()) {
            initializeGuardianSpawning(world, config, instance);
        }

        int verticalRange = Math.max(16, config.spawning.spawnerScanVerticalRange);
        int centerY = (int) instance.getActiveLocations().spawnPos.y;
        int minY = Math.max(world.getBottomY(), centerY - verticalRange);
        int maxY = Math.min(world.getTopY() - 1, centerY + verticalRange);

        int scanned = 0;
        int added = 0;
        boolean filterIds = config.spawning.guardianSpawnerIds != null && !config.spawning.guardianSpawnerIds.isEmpty();
        while (scanned < budget) {
            Long key = instance.pollGuardianSpawnerScanChunk();
            if (key == null) break;
            scanned++;
            
            it.unimi.dsi.fastutil.longs.LongArrayList indexed = com.sebastian.cobblemondungeons.CobblemonDungeons.getGuardianSpawnersInChunk(world, key);
            if (indexed != null) {
                for (long posLong : indexed) {
                    BlockPos pos = BlockPos.fromLong(posLong);
                    if (pos.getY() >= minY && pos.getY() <= maxY) {
                        if (filterIds) {
                            Block block = world.getBlockState(pos).getBlock();
                            Integer id = com.sebastian.cobblemondungeons.CobblemonDungeons.GUARDIAN_SPAWNER_BLOCK_IDS.get(block);
                            if (id == null || !config.spawning.guardianSpawnerIds.contains(id)) continue;
                        }
                        if (!isSpawnerClaimedByAnother(instance, pos) && instance.addDiscoveredGuardianSpawner(pos)) {
                            added++;
                        }
                    }
                }
            }
        }
        if (added > 0) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info(
                "Discovered {} Guardian spawners in {}",
                added,
                config.general.name
            );
        }
        return scanned;
    }

    private static int scanChestChunks(ServerWorld world, DungeonInstance instance, DungeonConfig config, int budget) {
        if (world == null) return 0;

        // Initialize chest scan queue if empty
        if (!instance.hasChestScanQueue() && !instance.isChestScanComplete()) {
            int radius = Math.max(32, config.spawning.chestSpawnRadius);
            int chunkRadius = Math.max(1, (radius + 15) / 16);
            net.minecraft.util.math.ChunkPos spawnChunk = new net.minecraft.util.math.ChunkPos(
                new BlockPos((int) instance.getActiveLocations().spawnPos.x, (int) instance.getActiveLocations().spawnPos.y, (int) instance.getActiveLocations().spawnPos.z)
            );
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    long key = net.minecraft.util.math.ChunkPos.toLong(spawnChunk.x + dx, spawnChunk.z + dz);
                    instance.enqueueChestScanChunk(key);
                }
            }
        }

        int scanned = 0;
        int added = 0;
        boolean filterIds = config.spawning.chestIds != null && !config.spawning.chestIds.isEmpty();
        while (scanned < budget) {
            Long key = instance.pollChestScanChunk();
            if (key == null) break;
            scanned++;
            
            it.unimi.dsi.fastutil.longs.LongArrayList indexed = com.sebastian.cobblemondungeons.CobblemonDungeons.getDungeonChestsInChunk(world, key);
            if (indexed != null) {
                for (long posLong : indexed) {
                    BlockPos pos = BlockPos.fromLong(posLong);
                    if (filterIds) {
                        Block block = world.getBlockState(pos).getBlock();
                        Integer id = com.sebastian.cobblemondungeons.CobblemonDungeons.DUNGEON_CHEST_BLOCK_IDS.get(block);
                        if (id == null || !config.spawning.chestIds.contains(id)) continue;
                    }
                    if (!isSpawnerClaimedByAnother(instance, pos) && instance.addDiscoveredChest(pos)) {
                        added++;
                    }
                }
            }
        }
        return scanned;
    }

    private static void initializeGuardianSpawning(ServerWorld world, DungeonConfig config, DungeonInstance instance) {
        if (instance.isGuardianSpawnerScanStarted()) return;
        instance.setGuardianSpawnerScanStarted(true);
        instance.setGuardianSpawnTarget(config.requirements.requiredGuardians);

        int radius = Math.max(32, config.spawning.guardianSpawnRadius);
        int chunkRadius = Math.max(1, (radius + 15) / 16);
        net.minecraft.util.math.ChunkPos spawnChunk = new net.minecraft.util.math.ChunkPos(
            new BlockPos((int) instance.getActiveLocations().spawnPos.x, (int) instance.getActiveLocations().spawnPos.y, (int) instance.getActiveLocations().spawnPos.z)
        );

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                long key = net.minecraft.util.math.ChunkPos.toLong(spawnChunk.x + dx, spawnChunk.z + dz);
                instance.enqueueGuardianSpawnerScanChunk(key);
                // Add ticket to load chunk
                world.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.POST_TELEPORT, new net.minecraft.util.math.ChunkPos(spawnChunk.x + dx, spawnChunk.z + dz), 1, 0);
            }
        }
    }

    private static void spawnGuardians(ServerWorld world, DungeonInstance instance) {
        DungeonConfig config = instance.getConfig();
        if (config.spawning.guardianPool == null || config.spawning.guardianPool.isEmpty()) return;

        if (world == null) return;

        List<BlockPos> spawners = instance.getGuardianSpawnerPositions();
        if (spawners.isEmpty()) return;

        int tier = instance.getTier();
        java.util.List<DungeonConfig.GuardianPoolConfig> tierPool = config.spawning.guardianPool.stream()
                .filter(g -> g.tier == tier)
                .toList();

        if (tierPool.isEmpty()) {
            tierPool = config.spawning.guardianPool.stream()
                    .filter(g -> g.tier == 0)
                    .toList();
        }

        if (tierPool.isEmpty()) return;

        int tierLevel = resolveTierLevel(config, tier);
        for (BlockPos pos : spawners) {
            if (instance.isGuardianSpawnerSpawned(pos)) continue;
            
            DungeonConfig.GuardianPoolConfig guardianConfig = pickRandomGuardian(tierPool, world.getRandom());
            if (guardianConfig != null) {
                instance.enqueueTrainerSpawn(new DungeonInstance.TrainerSpawnTask(
                    guardianConfig.trainerId,
                    pos.getX() + 0.5,
                    (double) pos.getY(),
                    pos.getZ() + 0.5,
                    guardianConfig.level > 0 ? guardianConfig.level : tierLevel,
                    true, // Always unkillable
                    true  // Is guardian
                ));
                instance.markGuardianSpawnerSpawned(pos);
            }
        }
        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Enqueued Guardians for spawning in {}", config.general.name);
    }


    private static DungeonConfig.GuardianPoolConfig pickRandomGuardian(List<DungeonConfig.GuardianPoolConfig> pool, net.minecraft.util.math.random.Random random) {
        int totalWeight = pool.stream().mapToInt(g -> g.weight).sum();
        if (totalWeight <= 0) return null;
        int target = random.nextInt(totalWeight);
        for (DungeonConfig.GuardianPoolConfig config : pool) {
            target -= config.weight;
            if (target < 0) return config;
        }
        return pool.get(0);
    }

    private static boolean spawnAlphaAt(ServerWorld world, DungeonConfig config, BlockPos pos, int tier) {
        if (config.spawning.alphaPool == null || config.spawning.alphaPool.isEmpty()) return false;
        
        java.util.List<DungeonConfig.AlphaPokemonConfig> tierPool = config.spawning.alphaPool.stream()
                .filter(a -> a.tier == tier)
                .toList();
        
        if (tierPool.isEmpty()) {
            tierPool = config.spawning.alphaPool.stream()
                    .filter(a -> a.tier == 0)
                    .toList();
        }
        
        if (tierPool.isEmpty()) return false;

        int poolSize = tierPool.size();
        for (int attempt = 0; attempt < poolSize; attempt++) {
            DungeonConfig.AlphaPokemonConfig alphaConfig = pickRandomAlpha(tierPool, world.getRandom());
            if (alphaConfig == null) return false;

            var species = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.INSTANCE.getByName(alphaConfig.species);
            if (species == null) {
                if (INVALID_ALPHA_SPECIES_LOGGED.add(alphaConfig.species)) {
                    com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.warn(
                        "Unknown alpha species '{}' in dungeon '{}'; skipping spawn",
                        alphaConfig.species,
                        config.general.name
                    );
                }
                continue;
            }

            int tierLevel = resolveTierLevel(config, tier);
            int minLevel = alphaConfig.minLevel;
            int maxLevel = alphaConfig.maxLevel;
            if (tierLevel > 0) {
                minLevel = tierLevel;
                maxLevel = tierLevel;
            }

            com.cobblemon.mod.common.pokemon.Pokemon pokemon = species.create(world.random.nextInt(maxLevel - minLevel + 1) + minLevel);
            pokemon.getAspects().add("alpha");
            pokemon.getAspects().add("scale:1.3");
            
            // Set persistent NBT for scale and alpha status
            pokemon.getPersistentData().putBoolean("IsAlpha", true);
            pokemon.getPersistentData().putInt("DungeonTier", tier);
            pokemon.getPersistentData().putFloat("CustomScale", 1.3f);
            
            buffAlphaIvs(pokemon);
            
            var entityType = (net.minecraft.entity.EntityType<com.cobblemon.mod.common.entity.pokemon.PokemonEntity>) net.minecraft.registry.Registries.ENTITY_TYPE.get(net.minecraft.util.Identifier.of("cobblemon", "pokemon"));
            var pokemonEntity = new com.cobblemon.mod.common.entity.pokemon.PokemonEntity(world, pokemon, entityType);
            pokemonEntity.addCommandTag("DungeonAlpha");
            pokemonEntity.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, world.random.nextFloat() * 360, 0);
            if (pokemonEntity instanceof MobEntity mob) {
                mob.setPersistent();
            }
            
            world.spawnEntity(pokemonEntity);
            com.sebastian.cobblemondungeons.CobblemonDungeons.ensureCorrectScale(pokemonEntity);
            return true;
        }
        return false;
    }

    private static void spawnTrainerDirectly(ServerWorld world, String trainerId, double x, double y, double z, int level, boolean invulnerable, boolean isGuardian) {
        // Try radicaltrainers first, then cobblemon_dungeons
        RCTApi api = RCTApi.getInstance("radicaltrainers");
        Trainer template = (api != null) ? api.getTrainerRegistry().getById(trainerId) : null;

        if (template == null) {
            api = RCTApi.getInstance(com.sebastian.cobblemondungeons.CobblemonDungeons.MOD_ID);
            if (api != null) {
                template = api.getTrainerRegistry().getById(trainerId);
            }
        }

        if (template == null) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Trainer template '{}' not found in any Radical Trainers registry!", trainerId);
            return;
        }

        if (!(template instanceof TrainerNPC templateNpc)) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Trainer template '{}' is not an NPC!", trainerId);
            return;
        }

        // Create a new NPC instance from the template
        TrainerNPC newNpc = new TrainerNPC(templateNpc);

        // Adjust levels if specified
        if (level > 0) {
            for (Pokemon p : newNpc.getTeam()) {
                if (p != null) {
                    p.setLevel(level);
                }
            }
        }

        // Create the entity
        LivingEntity templateEntity = templateNpc.getEntity();
        EntityType<?> entityType = templateEntity != null ? templateEntity.getType() : EntityType.VILLAGER;
        LivingEntity newEntity = (LivingEntity) entityType.create(world);

        if (newEntity == null) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to create entity for trainer '{}'", trainerId);
            return;
        }

        // Initialize as standard NPC if it's a Cobblemon NPC to prevent fallback issues
        if (newEntity instanceof com.cobblemon.mod.common.entity.npc.NPCEntity npc) {
            var npcClass = com.cobblemon.mod.common.api.npc.NPCClasses.INSTANCE.getByName("standard");
            if (npcClass != null) {
                npc.setNpc(npcClass);
            }
        }

        // Copy NBT from template to new entity
        if (templateEntity != null) {
            try {
                NbtCompound nbt = templateEntity.writeNbt(new NbtCompound());
                nbt.remove("UUID");
                nbt.remove("Pos");
                nbt.remove("Rotation");
                newEntity.readNbt(nbt);
            } catch (Exception e) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to copy NBT for trainer '{}'", trainerId, e);
            }
        }

        // Explicitly set name and visibility if present
        if (newNpc.getName() != null) {
            newEntity.setCustomName(newNpc.getName().getComponent());
            newEntity.setCustomNameVisible(true);
        }

        newEntity.refreshPositionAndAngles(x, y, z, world.random.nextFloat() * 360, 0);
        newNpc.setEntity(newEntity);

        // Spawn the entity
        if (invulnerable) {
            newEntity.setInvulnerable(true);
            newEntity.addCommandTag("DungeonTrainer");
        }
        if (isGuardian) {
            newEntity.addCommandTag("DungeonGuardian");
        }
        world.spawnEntity(newEntity);

        // Ensure invulnerability is set even after spawning
        if (invulnerable) {
            newEntity.setInvulnerable(true);
        }

        // Re-apply special dungeon appearance data from CommandTags AFTER spawning to ensure syncing
        if (newEntity instanceof com.cobblemon.mod.common.entity.npc.NPCEntity npc) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Applying appearance for NPC trainer '{}' after spawn", trainerId);
            
            for (String tag : newEntity.getCommandTags()) {
                if (tag.startsWith("DungeonSkin:")) {
                    String skinName = tag.substring("DungeonSkin:".length());
                    com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Found skin tag: {}. Triggering load.", skinName);
                    npc.loadTextureFromGameProfileName(skinName);
                } else if (tag.startsWith("DungeonTexture:")) {
                    String url = tag.substring("DungeonTexture:".length());
                    com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Found texture tag: {}. Triggering load.", url);
                    com.cobblemon.mod.common.entity.npc.NPCPlayerModelType mt = com.cobblemon.mod.common.entity.npc.NPCPlayerModelType.DEFAULT;
                    // Search for model type tag
                    for (String mtag : newEntity.getCommandTags()) {
                        if (mtag.startsWith("DungeonModel:")) {
                            String modelType = mtag.substring("DungeonModel:".length());
                            if ("SLIM".equalsIgnoreCase(modelType) || "ALEX".equalsIgnoreCase(modelType)) {
                                mt = com.cobblemon.mod.common.entity.npc.NPCPlayerModelType.SLIM;
                            }
                        }
                    }
                    try {
                        npc.loadTexture(new java.net.URI(url), mt);
                    } catch (Exception ignored) {}
                }
            }
            npc.updateAspects();
        }

        // Register the new instance with a unique ID so it's tracked by RCT
        String uniqueInstanceId = trainerId + "_" + UUID.randomUUID().toString().substring(0, 8);
        api.getTrainerRegistry().registerNPC(uniqueInstanceId, newNpc);
    }

    private static int resolveTierLevel(DungeonConfig config, int tier) {
        if (config == null) return 0;
        if (config.scaling.tierLevels != null) {
            for (DungeonConfig.TierLevelConfig tierLevelConfig : config.scaling.tierLevels) {
                if (tierLevelConfig.tier == tier) {
                    return tierLevelConfig.level;
                }
            }
        }
        return switch (tier) {
            case 1 -> 15;
            case 2 -> 30;
            case 3 -> 60;
            case 4 -> 85;
            case 5 -> 115;
            default -> 0;
        };
    }

    private static void buffAlphaIvs(com.cobblemon.mod.common.pokemon.Pokemon pokemon) {
        if (pokemon == null) return;
        var ivs = pokemon.getIvs();
        if (ivs == null) return;
        int maxIv = com.cobblemon.mod.common.pokemon.IVs.MAX_VALUE;
        int attack = ivs.getOrDefault(com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK);
        int hp = ivs.getOrDefault(com.cobblemon.mod.common.api.pokemon.stats.Stats.HP);
        int newAttack = Math.min(maxIv, (int) Math.round(attack * 1.3));
        int newHp = Math.min(maxIv, (int) Math.round(hp * 1.3));
        ivs.set(com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK, newAttack);
        ivs.set(com.cobblemon.mod.common.api.pokemon.stats.Stats.HP, newHp);
    }

    private static DungeonConfig.AlphaPokemonConfig pickRandomAlpha(List<DungeonConfig.AlphaPokemonConfig> pool, net.minecraft.util.math.random.Random random) {
        int totalWeight = pool.stream().mapToInt(a -> a.weight).sum();
        if (totalWeight <= 0) return null;
        int target = random.nextInt(totalWeight);
        for (DungeonConfig.AlphaPokemonConfig config : pool) {
            target -= config.weight;
            if (target < 0) return config;
        }
        return pool.get(0);
    }

    public static void transferPlayer(ServerPlayerEntity player, String serverName) {
        ServerPlayNetworking.send(player, BungeePayload.connect(serverName));
    }

    private static void transferPlayerDelayed(ServerPlayerEntity player, String serverName) {
        if (player == null || serverName == null || serverName.isBlank()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        pendingTransfers.put(player.getUuid(), new PendingTransfer(serverName, TRANSFER_DELAY_TICKS));
    }

    public static void teleportToDungeon(ServerPlayerEntity player, DungeonConfig.WorldPos pos) {
        if (player == null || pos == null || pos.dimension == null) return;
        try {
            Identifier dimId = Identifier.of(pos.dimension);
            ServerWorld world = player.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, dimId));
            if (world != null) {
                // Force the chunk to load at the destination to reduce lag
                BlockPos destPos = new BlockPos((int)pos.x, (int)pos.y, (int)pos.z);
                world.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.POST_TELEPORT, new net.minecraft.util.math.ChunkPos(destPos), 1, player.getId());
                
                // In 1.21.1, changing dimensions automatically triggers the loading screen.
                // For same-dimension, we use teleport with forced chunk loading.
                player.teleport(world, pos.x, pos.y, pos.z, player.getYaw(), player.getPitch());
            } else {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to teleport " + player.getName().getString() + " - World " + pos.dimension + " not found!");
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Error during teleportation for " + player.getName().getString(), e);
        }
    }

    public static boolean isTeamWiped(ServerPlayerEntity player) {
        var party = com.cobblemon.mod.common.Cobblemon.INSTANCE.getStorage().getParty(player);
        if (party.isEmpty()) return false;
        for (com.cobblemon.mod.common.pokemon.Pokemon pokemon : party) {
            if (pokemon.getCurrentHealth() > 0) return false;
        }
        return true;
    }

    public static DungeonInstance getInstance(UUID player) {
        return activeInstances.get(player);
    }

    private static boolean isSpawnerClaimedByAnother(DungeonInstance currentInstance, BlockPos pos) {
        if (currentInstance == null || currentInstance.getConfig() == null || currentInstance.getConfig().locations.spawnPos == null) return false;
        String currentDim = currentInstance.getConfig().locations.spawnPos.dimension;
        
        for (DungeonInstance instance : new HashSet<>(activeInstances.values())) {
            if (instance == currentInstance) continue;
            if (instance.getConfig() == null || instance.getConfig().locations.spawnPos == null) continue;
            
            String otherDim = instance.getConfig().locations.spawnPos.dimension;
            if (currentDim == null) {
                if (otherDim != null) continue;
            } else if (!currentDim.equals(otherDim)) {
                continue;
            }
            
            if (instance.getDiscoveredAlphaSpawners().contains(pos) ||
                instance.getDiscoveredGuardianSpawners().contains(pos) ||
                instance.getDiscoveredStakeSpawners().contains(pos) ||
                instance.getDiscoveredChests().contains(pos) ||
                instance.getPortalPositions().contains(pos)) {
                return true;
            }
        }
        return false;
    }

    public static void broadcastToDungeon(DungeonInstance instance, Text message, MinecraftServer server) {
        if (instance == null || server == null) return;
        for (UUID uuid : instance.getPlayers()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) {
                p.sendMessage(message);
            }
        }
    }

    public static void handleAlphaDefeated(ServerPlayerEntity player) {
        if (player == null) return;
        DungeonInstance instance = activeInstances.get(player.getUuid());
        if (instance == null) return;
        instance.addAlphaDefeated();
        broadcastToDungeon(instance, Text.translatable("cobblemondungeons.alpha_defeated", player.getName().getString(), instance.getAlphasDefeated(), instance.getConfig().requirements.requiredAlphas).formatted(Formatting.GRAY), player.getServer());
        checkPortalActivation(instance, player.getServerWorld());
    }

    public static void handleGuardianDefeated(ServerPlayerEntity player) {
        handleGuardianDefeated(player, 1);
    }

    public static void handleGuardianDefeated(ServerPlayerEntity player, int count) {
        if (player == null || count <= 0) return;
        DungeonInstance instance = activeInstances.get(player.getUuid());
        if (instance == null) return;
        for (int i = 0; i < count; i++) {
            instance.addGuardianDefeated();
        }
        String guardianLabel = count > 1 ? count + " Guardians" : "a Guardian";
        broadcastToDungeon(instance, Text.translatable("cobblemondungeons.guardian_defeated", player.getName().getString(), guardianLabel, instance.getGuardiansDefeated(), instance.getConfig().requirements.requiredGuardians).formatted(Formatting.GRAY), player.getServer());
        checkPortalActivation(instance, player.getServerWorld());
    }

    public static boolean handleTrainerInteract(ServerPlayerEntity player, LivingEntity entity) {
        if (player == null || entity == null) return false;
        
        // Check if player is already in a battle
        if (com.cobblemon.mod.common.battles.BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(player) != null) {
            player.sendMessage(Text.translatable("cobblemondungeons.already_in_battle").formatted(Formatting.RED), true);
            return true;
        }
        
        // Try our mod's registry first
        RCTApi api = RCTApi.getInstance(com.sebastian.cobblemondungeons.CobblemonDungeons.MOD_ID);
        String trainerId = null;
        if (api != null) {
            trainerId = api.getTrainerRegistry().getId(entity);
        }
        
        // If not found, try the main radicaltrainers registry
        if (trainerId == null) {
            RCTApi mainApi = RCTApi.getInstance("radicaltrainers");
            if (mainApi != null) {
                trainerId = mainApi.getTrainerRegistry().getId(entity);
                if (trainerId != null) {
                    api = mainApi;
                }
            }
        }
        
        if (api != null && trainerId != null) {
            Trainer trainer = api.getTrainerRegistry().getById(trainerId);
            if (trainer instanceof TrainerNPC npcTrainer) {
                // Check if the trainer has any healthy pokemon
                boolean hasHealthy = false;
                for (Pokemon p : npcTrainer.getTeam()) {
                    if (p != null && p.getCurrentHealth() > 0) {
                        hasHealthy = true;
                        break;
                    }
                }
                
                if (!hasHealthy) {
                    player.sendMessage(Text.translatable("cobblemondungeons.trainer_defeated").formatted(Formatting.GRAY), true);
                    return true;
                }

                // Make the NPC look at the player
                entity.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, player.getEyePos());
                if (entity instanceof MobEntity mob) {
                    mob.setBodyYaw(entity.getYaw());
                    mob.setHeadYaw(entity.getYaw());
                }

                TrainerPlayer trainerPlayer = new TrainerPlayer(player);
                return api.getBattleManager().startSingle(trainerPlayer, npcTrainer);
            }
        }
        
        return false;
    }

    public static void handleGemFound(ServerPlayerEntity player) {
        handleGemFound(player, 1);
    }

    public static void handleGemFound(ServerPlayerEntity player, int count) {
        if (player == null || count <= 0) return;
        DungeonInstance instance = activeInstances.get(player.getUuid());
        if (instance == null) return;
        for (int i = 0; i < count; i++) {
            instance.addGemFound();
        }
        Text msg = count > 1
            ? Text.translatable("cobblemondungeons.gem_found_multi", player.getName().getString(), count, instance.getGemsFound(), instance.getConfig().requirements.requiredGems).formatted(Formatting.GRAY)
            : Text.translatable("cobblemondungeons.gem_found_single", player.getName().getString(), instance.getGemsFound(), instance.getConfig().requirements.requiredGems).formatted(Formatting.GRAY);
        broadcastToDungeon(instance, msg, player.getServer());
        checkPortalActivation(instance, player.getServerWorld());
    }

    public static void handleStakeFound(ServerPlayerEntity player) {
        if (player == null) return;
        DungeonInstance instance = activeInstances.get(player.getUuid());
        if (instance == null) return;
        instance.addStakeFound();
        broadcastToDungeon(instance, Text.translatable("cobblemondungeons.stake_found", player.getName().getString(), instance.getStakesFound(), instance.getConfig().requirements.requiredStakes).formatted(Formatting.GRAY), player.getServer());
        checkPortalActivation(instance, player.getServerWorld());
    }

    public static void handleKeyCollected(ServerPlayerEntity player, String keyName) {
        if (player == null || keyName == null) return;
        DungeonInstance instance = activeInstances.get(player.getUuid());
        if (instance == null) return;
        instance.collectKey(keyName);
        broadcastToDungeon(instance, Text.translatable("cobblemondungeons.key_collected", player.getName().getString(), keyName).formatted(Formatting.GRAY), player.getServer());
        checkPortalActivation(instance, player.getServerWorld());
    }

    public static void handleBossDefeated(ServerPlayerEntity player) {
        if (player == null) return;
        DungeonInstance instance = activeInstances.get(player.getUuid());
        if (instance == null) return;
        if (instance.isBossDefeated()) return;

        if (instance.getBossUuid() != null) {
            bossToInstance.remove(instance.getBossUuid());
        }

        instance.setBossDefeated(true);

        // Log completed run + WS broadcast
        logRun(instance, player.getServer(), true);
        broadcastDungeonEvent("dungeon_complete", instance, player.getServer());

        // Record cooldowns and update leaderboard for all participants
        for (UUID uuid : instance.getPlayers()) {
            recordCooldown(instance.getConfig().general.id, uuid);
        }
        updateLeaderboard(instance, player.getServer());

        java.util.Random random = new java.util.Random();

        // Reward Boss to ALL party members
        for (UUID memberUuid : instance.getPlayers()) {
            ServerPlayerEntity member = player.getServer().getPlayerManager().getPlayer(memberUuid);
            if (member != null) {
                if (random.nextDouble() * 100.0 <= instance.getBossRewardChance()) {
                    rewardBossToPC(member, instance);
                }
                healParty(member);
            }
        }
        
        // Notify all party members
        broadcastToDungeon(instance, Text.translatable("cobblemondungeons.boss_defeated").formatted(Formatting.GOLD, Formatting.BOLD), player.getServer());
        broadcastToDungeon(instance, Text.translatable("cobblemondungeons.party_wins").formatted(Formatting.YELLOW), player.getServer());

        distributeCompletionRewards(instance, player.getServer());

        instance.setFinishDelayTicks(10); // 10 seconds (tickDungeons runs every 20 ticks)
    }

    public static void distributeCompletionRewards(DungeonInstance instance, MinecraftServer server) {
        if (instance == null || server == null) return;
        DungeonConfig config = instance.getConfig();
        int tier = instance.getTier();
        int cap = config.rewardCap;
        java.util.Random random = new java.util.Random();

        // Get eligible rewards for this tier
        List<DungeonConfig.CompletionRewardConfig> eligibleRewards = new ArrayList<>();
        for (DungeonConfig.CompletionRewardConfig reward : config.completionRewards) {
            if (reward.tier == 0 || reward.tier == tier) {
                eligibleRewards.add(reward);
            }
        }
        
        // Add boss specific rewards
        eligibleRewards.addAll(instance.getBossRewards());

        if (eligibleRewards.isEmpty()) return;

        for (UUID playerUuid : instance.getPlayers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
            if (player == null) continue;

            // Shuffle rewards for each player to ensure fairness if cap is reached
            List<DungeonConfig.CompletionRewardConfig> playerRewards = new ArrayList<>(eligibleRewards);
            Collections.shuffle(playerRewards, random);

            int rewardsGiven = 0;
            for (DungeonConfig.CompletionRewardConfig reward : playerRewards) {
                if (cap > 0 && rewardsGiven >= cap) break;

                if (random.nextDouble() * 100.0 <= reward.chance) {
                    ItemStack stack = com.sebastian.cobblemondungeons.util.InteractionHandler.createCompletionRewardStack(reward, random);
                    if (!stack.isEmpty()) {
                        if (!player.getInventory().insertStack(stack)) {
                            player.dropItem(stack, false);
                        }
                        
                        Text itemName = stack.getName();
                        if (itemName.getString().equalsIgnoreCase("Air")) {
                            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.warn("Attempted to give 'Air' reward to {}. Item ID in config might be invalid.", player.getName().getString());
                        } else {
                            player.sendMessage(Text.translatable("cobblemondungeons.reward_received").formatted(Formatting.GREEN).append(itemName.copy().formatted(Formatting.GOLD)));
                            rewardsGiven++;
                        }
                    }
                }
            }
        }
    }

    private static void checkPortalActivation(DungeonInstance instance, ServerWorld world) {
        if (instance == null || world == null || instance.arePortalsActivated()) return;

        if (instance.canAccessBoss()) {
            instance.setPortalsActivated(true);
            broadcastToDungeon(instance, Text.translatable("cobblemondungeons.portal_activated").formatted(Formatting.GREEN, Formatting.BOLD), world.getServer());
            
            for (BlockPos pos : instance.getPortalPositions()) {
                BlockState state = world.getBlockState(pos);
                if (state.isOf(com.sebastian.cobblemondungeons.CobblemonDungeons.PORTAL_BLOCK)) {
                    world.setBlockState(pos, state.with(com.sebastian.cobblemondungeons.block.PortalBlock.VISIBLE, true));
                }
            }
        }
    }

    public static void spawnBoss(DungeonInstance instance, MinecraftServer server) {
        if (instance == null || instance.isBossSpawned()) return;
        
        DungeonConfig config = instance.getConfig();
        DungeonConfig.BossConfig bossConfig = config.boss;
        
        ServerWorld world = getDungeonWorld(server, instance);
        if (world == null) return;
        
        int tier = instance.getTier();
        DungeonConfig.BossPokemonConfig selected = null;
        if (bossConfig.pool != null && !bossConfig.pool.isEmpty()) {
            java.util.List<DungeonConfig.BossPokemonConfig> tierPool = bossConfig.pool.stream()
                    .filter(b -> b.tier == tier)
                    .toList();
            
            if (tierPool.isEmpty()) {
                tierPool = bossConfig.pool.stream()
                        .filter(b -> b.tier == 0)
                        .toList();
            }
            
            if (!tierPool.isEmpty()) {
                selected = pickRandomBoss(tierPool, world.getRandom());
            }
        }
        if (selected == null) {
            // Fallback to legacy fields
            selected = new DungeonConfig.BossPokemonConfig(bossConfig.species, 0, bossConfig.level, bossConfig.customName, bossConfig.isShiny, 10);
        }

        if (selected == null) return;

        var species = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.INSTANCE.getByName(selected.species);
        if (species == null) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Boss species not found: " + selected.species);
            return;
        }

        int bossLevel = selected.level;
        if (bossLevel <= 0) {
            bossLevel = resolveTierLevel(config, instance.getTier());
        }

        com.cobblemon.mod.common.pokemon.Pokemon pokemon = species.create(bossLevel);
        pokemon.setNickname(Text.literal(selected.customName));
        pokemon.getAspects().add("alpha");
        pokemon.getAspects().add("scale:" + selected.scale);
        
        // Set persistent NBT for scale and boss status
        pokemon.getPersistentData().putBoolean("IsAlpha", true);
        pokemon.getPersistentData().putBoolean("IsDungeonBoss", true);
        pokemon.getPersistentData().putInt("DungeonTier", instance.getTier());
        pokemon.getPersistentData().putFloat("CustomScale", (float)selected.scale);
        
        if (selected.isShiny) {
            pokemon.setShiny(true);
        }
        
        buffAlphaIvs(pokemon);

        var entityType = (net.minecraft.entity.EntityType<com.cobblemon.mod.common.entity.pokemon.PokemonEntity>) net.minecraft.registry.Registries.ENTITY_TYPE.get(net.minecraft.util.Identifier.of("cobblemon", "pokemon"));
        var pokemonEntity = new com.cobblemon.mod.common.entity.pokemon.PokemonEntity(world, pokemon, entityType);
        pokemonEntity.addCommandTag("DungeonAlpha");
        pokemonEntity.addCommandTag("DungeonBoss");
        pokemonEntity.setCustomName(Text.literal(selected.customName));
        pokemonEntity.setCustomNameVisible(false); // Use Cobblemon label only
        
        DungeonConfig.WorldPos bPos = instance.getBossSpawnPos();
        pokemonEntity.refreshPositionAndAngles(bPos.x + 0.5, bPos.y, bPos.z + 0.5, 0, 0);
        
        if (pokemonEntity instanceof MobEntity mob) {
            mob.setPersistent();
        }
        
        world.spawnEntity(pokemonEntity);
        // Re-apply scale after spawning to ensure it sticks and syncs
        com.sebastian.cobblemondungeons.CobblemonDungeons.ensureCorrectScale(pokemonEntity);
        instance.setBossSpawned(true);
        instance.setBossUuid(pokemonEntity.getUuid());
        instance.setBossPhase(1);
        instance.setSpawnedBossSpecies(selected.species);
        instance.setSpawnedBossLevel(bossLevel);
        instance.setSpawnedBossShiny(selected.isShiny);
        instance.setSpawnedBossName(selected.customName);
        instance.setBossRewardChance(selected.rewardChance);
        instance.setBossRewards(selected.customRewards);
        bossToInstance.put(pokemonEntity.getUuid(), instance);
        broadcastDungeonEvent("dungeon_boss_spawn", instance, server);

        broadcastToDungeon(instance, Text.translatable("cobblemondungeons.boss_appeared", selected.customName).formatted(Formatting.RED), server);
    }

    public static DungeonInstance getBossInstance(UUID bossUuid) {
        return bossToInstance.get(bossUuid);
    }

    private static DungeonConfig.BossPokemonConfig pickRandomBoss(List<DungeonConfig.BossPokemonConfig> pool, net.minecraft.util.math.random.Random random) {
        int totalWeight = pool.stream().mapToInt(b -> b.weight).sum();
        if (totalWeight <= 0) return null;
        int target = random.nextInt(totalWeight);
        for (DungeonConfig.BossPokemonConfig config : pool) {
            target -= config.weight;
            if (target < 0) return config;
        }
        return pool.get(0);
    }

    private static void rewardBossToPC(ServerPlayerEntity player, DungeonInstance instance) {
        String species = instance.getSpawnedBossSpecies();
        int level = instance.getSpawnedBossLevel();
        boolean shiny = instance.isSpawnedBossShiny();
        String customName = instance.getSpawnedBossName();

        if (species == null) return;

        var pokemonSpecies = PokemonSpecies.INSTANCE.getByName(species);
        if (pokemonSpecies == null) return;

        Pokemon pokemon = pokemonSpecies.create(level);
        if (customName != null) {
            pokemon.setNickname(Text.literal(customName));
        }
        if (shiny) {
            pokemon.setShiny(true);
        }

        var pc = Cobblemon.INSTANCE.getStorage().getPC(player);
        pc.add(pokemon);
        player.sendMessage(Text.translatable("cobblemondungeons.transferred_to_pc", customName != null ? customName : species));
    }

    private static boolean shouldUseAlphaSpawnerId(DungeonConfig config, int spawnerId) {
        if (config == null || config.spawning.alphaSpawnerIds == null || config.spawning.alphaSpawnerIds.isEmpty()) {
            return true;
        }
        return config.spawning.alphaSpawnerIds.contains(spawnerId);
    }

    public static Map<String, DungeonConfig> getDungeons() {
        return Collections.unmodifiableMap(dungeons);
    }

    private static boolean isPcEmpty(ServerPlayerEntity player) {
        try {
            var pc = com.cobblemon.mod.common.Cobblemon.INSTANCE.getStorage().getPC(player);
            if (pc == null) return true;
            return !pc.iterator().hasNext();
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isStoreNbtEmpty(NbtCompound nbt) {
        if (nbt == null || nbt.isEmpty()) return true;
        int keyCount = nbt.getKeys().size();
        if (keyCount == 0) return true;
        if (keyCount == 1 && (nbt.contains("uuid") || nbt.contains("UUID"))) return true;
        return false;
    }

    private static boolean shouldApplyCobblemonResync(ServerPlayerEntity player, NbtCompound data) {
        if (data == null) return false;
        NbtCompound pcNbt = data.getCompound("CobblemonPC");
        if (!data.contains("CobblemonPC") || isStoreNbtEmpty(pcNbt)) return false;
        return isPcEmpty(player);
    }

    private static NbtCompound extractCobblemonData(NbtCompound data) {
        NbtCompound cobblemon = new NbtCompound();
        if (data == null) return cobblemon;
        if (data.contains("CobblemonPC")) {
            cobblemon.put("CobblemonPC", data.getCompound("CobblemonPC"));
        }
        if (data.contains("CobblemonParty")) {
            cobblemon.put("CobblemonParty", data.getCompound("CobblemonParty"));
        }
        return cobblemon;
    }

    public static void tickPendingSyncs(MinecraftServer server) {
        if (delayedSyncs.isEmpty()) return;
        Iterator<Map.Entry<UUID, PendingSync>> it = delayedSyncs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingSync> entry = it.next();
            PendingSync pending = entry.getValue();
            pending.ticksLeft--;
            if (pending.ticksLeft > 0) continue;

            UUID playerId = entry.getKey();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                it.remove();
                continue;
            }

            NbtCompound data = pending.data;
            if (pending.loadFromSql) {
                if (!com.sebastian.cobblemondungeons.util.DatabaseManager.isEnabled()) {
                    it.remove();
                    continue;
                }
                data = com.sebastian.cobblemondungeons.util.DatabaseManager.loadPlayerData(playerId);
                if (data == null) {
                    pending.retries++;
                    if (pending.retries > 5) { // 5 retries = 10 seconds total
                        it.remove();
                    } else {
                        pending.ticksLeft = 40;
                    }
                    continue;
                }
            }

            NbtCompound cobblemonData = extractCobblemonData(data);
            if (cobblemonData.isEmpty()) {
                it.remove();
                continue;
            }
            if (!pending.force) {
                if (pending.loadFromSql) {
                    if (!isPcEmpty(player)) {
                        it.remove();
                        continue;
                    }
                } else if (!shouldApplyCobblemonResync(player, cobblemonData)) {
                    it.remove();
                    continue;
                }
            }

            if (!cobblemonData.isEmpty()) {
                try {
                    PlayerSyncHandler.deserialize(player, cobblemonData);
                } catch (Exception e) {
                    com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed delayed sync for " + player.getName().getString(), e);
                }
            }
            it.remove();
        }
    }

    private static void ensureAliveForDungeonJoin(ServerPlayerEntity player) {
        if (player == null) return;
        try {
            if (player.isDead() || player.getHealth() <= 0.0f) {
                player.setHealth(player.getMaxHealth());
            }
            if (player.getHungerManager().getFoodLevel() <= 0) {
                player.getHungerManager().setFoodLevel(20);
                player.getHungerManager().setSaturationLevel(5.0f);
            }
            player.fallDistance = 0.0f;
        } catch (Exception ignored) {
        }
    }

    public static void tickPendingTransfers(MinecraftServer server) {
        if (server == null || pendingTransfers.isEmpty()) return;
        Iterator<Map.Entry<UUID, PendingTransfer>> it = pendingTransfers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingTransfer> entry = it.next();
            PendingTransfer pending = entry.getValue();
            pending.ticksLeft--;
            if (pending.ticksLeft > 0) continue;

            UUID playerId = entry.getKey();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                transferPlayer(player, pending.targetServer);
            }
            it.remove();
        }
    }

    public static void tickPendingStarts(MinecraftServer server) {
        if (server == null || pendingStarts.isEmpty()) return;
        Iterator<Map.Entry<UUID, PendingStart>> it = pendingStarts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingStart> entry = it.next();
            PendingStart pending = entry.getValue();
            pending.ticksLeft--;
            if (pending.ticksLeft > 0) continue;
            ServerPlayerEntity leader = server.getPlayerManager().getPlayer(entry.getKey());
            if (leader != null) {
                leader.sendMessage(Text.translatable("cobblemondungeons.server_offline").formatted(Formatting.RED));
            }
            it.remove();
        }
    }

    public static void scheduleSqlSyncIfEmpty(ServerPlayerEntity player, int ticks) {
        if (player == null) return;
        if (!com.sebastian.cobblemondungeons.util.DatabaseManager.isEnabled()) return;
        scheduleDelayedSqlSync(player.getUuid(), ticks, false);
    }

    private static void scheduleDelayedSync(UUID playerUuid, NbtCompound data, int ticks) {
        scheduleDelayedSync(playerUuid, data, ticks, false);
    }

    private static void scheduleDelayedSync(UUID playerUuid, NbtCompound data, int ticks, boolean force) {
        if (data == null) return;
        PendingSync existing = delayedSyncs.get(playerUuid);
        boolean finalForce = force || (existing != null && existing.force);
        delayedSyncs.put(playerUuid, new PendingSync(data.copy(), ticks, false, finalForce));
    }

    private static void scheduleDelayedSqlSync(UUID playerUuid, int ticks) {
        scheduleDelayedSqlSync(playerUuid, ticks, false);
    }

    private static void scheduleDelayedSqlSync(UUID playerUuid, int ticks, boolean force) {
        PendingSync existing = delayedSyncs.get(playerUuid);
        boolean finalForce = force || (existing != null && existing.force);
        delayedSyncs.put(playerUuid, new PendingSync(null, ticks, true, finalForce));
    }

    // ── Public API for Dashboard (CheeseDash) ─────────────────────────────────

    /** Returns all registered dungeon configs. */
    public static Collection<DungeonConfig> getAllDungeonConfigs() {
        return Collections.unmodifiableCollection(dungeons.values());
    }

    /** Returns all unique active instances (deduplicated from activeInstances.values()). */
    public static Collection<DungeonInstance> getUniqueActiveInstances() {
        return Collections.unmodifiableSet(new HashSet<>(activeInstances.values()));
    }

    /** Returns the dungeon instance a player is currently in, or null. */
    public static DungeonInstance getInstanceForPlayer(UUID uuid) {
        return activeInstances.get(uuid);
    }

    /** Checks if a player is currently in a dungeon. */
    public static boolean isPlayerInDungeon(UUID uuid) {
        return activeInstances.containsKey(uuid);
    }

    /** Returns the number of active countdowns (players waiting to enter). */
    public static int getActiveCountdownCount() {
        return activeCountdowns.size();
    }

    /** Force-fails a dungeon instance. Called from the dashboard. */
    public static void forceFailInstance(DungeonInstance instance, MinecraftServer server) {
        if (instance == null || server == null) return;
        failDungeon(instance, server, Text.translatable("cobblemondungeons.force_terminated").formatted(Formatting.RED, Formatting.BOLD));
    }

    /** Force-leaves a specific player from their dungeon. */
    public static void forceLeavePlayer(UUID playerUuid, MinecraftServer server) {
        if (server == null) return;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
        if (player != null && activeInstances.containsKey(playerUuid)) {
            player.sendMessage(Text.translatable("cobblemondungeons.removed_by_admin").formatted(Formatting.RED));
            leaveDungeon(player);
        }
    }

    // ── Run History Logging ────────────────────────────────────────────────────

    public record DungeonRunLog(String dungeonId, String dungeonName, int tier, List<String> playerNames,
                                 long startTime, long endTime, boolean success, String bossSpecies, int deathCount) {
        public com.google.gson.JsonObject toJson() {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("dungeonId", dungeonId);
            o.addProperty("dungeonName", dungeonName);
            o.addProperty("tier", tier);
            com.google.gson.JsonArray pArr = new com.google.gson.JsonArray();
            if (playerNames != null) playerNames.forEach(pArr::add);
            o.add("players", pArr);
            o.addProperty("startTime", startTime);
            o.addProperty("endTime", endTime);
            o.addProperty("success", success);
            o.addProperty("bossSpecies", bossSpecies != null ? bossSpecies : "");
            o.addProperty("deathCount", deathCount);
            o.addProperty("durationSeconds", endTime - startTime);
            return o;
        }
        public static DungeonRunLog fromJson(com.google.gson.JsonObject o) {
            List<String> players = new ArrayList<>();
            if (o.has("players")) {
                for (com.google.gson.JsonElement el : o.getAsJsonArray("players"))
                    players.add(el.getAsString());
            }
            return new DungeonRunLog(
                o.has("dungeonId") ? o.get("dungeonId").getAsString() : "",
                o.has("dungeonName") ? o.get("dungeonName").getAsString() : "",
                o.has("tier") ? o.get("tier").getAsInt() : 1,
                players,
                o.has("startTime") ? o.get("startTime").getAsLong() : 0,
                o.has("endTime") ? o.get("endTime").getAsLong() : 0,
                o.has("success") && o.get("success").getAsBoolean(),
                o.has("bossSpecies") ? o.get("bossSpecies").getAsString() : "",
                o.has("deathCount") ? o.get("deathCount").getAsInt() : 0
            );
        }
    }

    private static final List<DungeonRunLog> runHistory = new CopyOnWriteArrayList<>();
    private static final int MAX_RUN_HISTORY = 1000;

    public static List<DungeonRunLog> getRunHistory() {
        return Collections.unmodifiableList(runHistory);
    }

    public static void logRun(DungeonInstance instance, MinecraftServer server, boolean success) {
        if (instance == null) return;
        DungeonConfig config = instance.getConfig();
        List<String> playerNames = new ArrayList<>();
        for (UUID uuid : instance.getPlayers()) {
            ServerPlayerEntity p = server != null ? server.getPlayerManager().getPlayer(uuid) : null;
            playerNames.add(p != null ? p.getName().getString() : uuid.toString().substring(0, 8));
        }
        int totalLivesUsed = 0;
        int maxLives;
        int partySize = instance.getPlayers().size();
        if (partySize <= 1) maxLives = config.requirements.soloLives;
        else if (partySize <= 3) maxLives = config.requirements.smallPartyLives;
        else maxLives = config.requirements.largePartyLives;
        totalLivesUsed = maxLives - instance.getLives();

        DungeonRunLog log = new DungeonRunLog(
            config.general.id, config.general.name, instance.getTier(),
            playerNames, instance.getStartTime() / 1000L, Instant.now().getEpochSecond(),
            success, instance.getSpawnedBossSpecies(),
            Math.max(0, totalLivesUsed)
        );
        runHistory.add(0, log);
        while (runHistory.size() > MAX_RUN_HISTORY) runHistory.remove(runHistory.size() - 1);
        saveRunHistory();
    }

    private static Path runHistoryFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
            .resolve("cobblemon_dungeons").resolve("run_history.json");
    }

    public static void loadRunHistory() {
        Path f = runHistoryFile();
        if (!Files.exists(f)) return;
        try {
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonArray();
            runHistory.clear();
            for (com.google.gson.JsonElement el : arr) runHistory.add(DungeonRunLog.fromJson(el.getAsJsonObject()));
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("[Dungeons] Failed to load run history", e);
        }
    }

    private static void saveRunHistory() {
        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            runHistory.forEach(r -> arr.add(r.toJson()));
            Files.writeString(runHistoryFile(), gson.toJson(arr));
        } catch (IOException e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("[Dungeons] Failed to save run history", e);
        }
    }

    // ── WS Broadcast (CheeseDash integration) ─────────────────────────────────

    private static void broadcastDungeonEvent(String type, DungeonInstance instance, MinecraftServer server) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cheesedash")) return;
        try {
            com.google.gson.JsonObject msg = new com.google.gson.JsonObject();
            msg.addProperty("type", type);
            msg.addProperty("dungeonId", instance.getConfig().general.id);
            msg.addProperty("dungeonName", instance.getConfig().general.name);
            msg.addProperty("tier", instance.getTier());
            com.google.gson.JsonArray playersArr = new com.google.gson.JsonArray();
            for (UUID uuid : instance.getPlayers()) {
                ServerPlayerEntity p = server != null ? server.getPlayerManager().getPlayer(uuid) : null;
                playersArr.add(p != null ? p.getName().getString() : uuid.toString().substring(0, 8));
            }
            msg.add("players", playersArr);
            msg.addProperty("timeLeft", instance.getTimeLeft());
            msg.addProperty("lives", instance.getLives());
            if (instance.getSpawnedBossSpecies() != null) {
                msg.addProperty("bossSpecies", instance.getSpawnedBossSpecies());
            }
            com.cheesedash.DashboardRegistry.broadcast("dungeons", msg.toString());
        } catch (Exception ignored) {}
    }

    // ── Cooldowns ─────────────────────────────────────────────────────────────

    // dungeonId -> (playerUuid -> lastCompletionEpochSecond)
    private static final Map<String, Map<UUID, Long>> cooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    /** Check if a player is on cooldown for a dungeon. Returns remaining seconds, or 0 if ready. */
    public static long getCooldownRemaining(String dungeonId, UUID playerUuid) {
        DungeonConfig config = dungeons.get(dungeonId);
        if (config == null) return 0;
        int cooldownSec = config.requirements.cooldownSeconds;
        if (cooldownSec <= 0) return 0;
        Map<UUID, Long> map = cooldowns.get(dungeonId);
        if (map == null) return 0;
        Long lastCompletion = map.get(playerUuid);
        if (lastCompletion == null) return 0;
        long now = Instant.now().getEpochSecond();
        long elapsed = now - lastCompletion;
        return elapsed >= cooldownSec ? 0 : cooldownSec - elapsed;
    }

    /** Record a cooldown after successful dungeon completion. */
    public static void recordCooldown(String dungeonId, UUID playerUuid) {
        cooldowns.computeIfAbsent(dungeonId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                 .put(playerUuid, Instant.now().getEpochSecond());
        saveCooldowns();
    }

    private static Path cooldownsFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
            .resolve("cobblemon_dungeons").resolve("cooldowns.json");
    }

    public static void loadCooldowns() {
        Path f = cooldownsFile();
        if (!Files.exists(f)) return;
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            cooldowns.clear();
            for (var entry : root.entrySet()) {
                Map<UUID, Long> map = new java.util.concurrent.ConcurrentHashMap<>();
                for (var playerEntry : entry.getValue().getAsJsonObject().entrySet()) {
                    try {
                        map.put(UUID.fromString(playerEntry.getKey()), playerEntry.getValue().getAsLong());
                    } catch (Exception ignored) {}
                }
                cooldowns.put(entry.getKey(), map);
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("[Dungeons] Failed to load cooldowns", e);
        }
    }

    private static void saveCooldowns() {
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            for (var entry : cooldowns.entrySet()) {
                com.google.gson.JsonObject map = new com.google.gson.JsonObject();
                for (var pe : entry.getValue().entrySet()) {
                    map.addProperty(pe.getKey().toString(), pe.getValue());
                }
                root.add(entry.getKey(), map);
            }
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            Files.writeString(cooldownsFile(), gson.toJson(root));
        } catch (IOException e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("[Dungeons] Failed to save cooldowns", e);
        }
    }

    /** Get all cooldowns for dashboard display. */
    public static Map<String, Map<UUID, Long>> getAllCooldowns() {
        return Collections.unmodifiableMap(cooldowns);
    }

    // ── Leaderboard ───────────────────────────────────────────────────────────

    // dungeonId -> (tier -> list of entries, sorted by duration ascending, max 10)
    private static final Map<String, Map<Integer, List<LeaderboardEntry>>> leaderboard = new java.util.concurrent.ConcurrentHashMap<>();

    public record LeaderboardEntry(String playerName, UUID playerUuid, long durationSeconds, long date) {
        public com.google.gson.JsonObject toJson() {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("playerName", playerName);
            o.addProperty("playerUuid", playerUuid.toString());
            o.addProperty("durationSeconds", durationSeconds);
            o.addProperty("date", date);
            return o;
        }
        public static LeaderboardEntry fromJson(com.google.gson.JsonObject o) {
            return new LeaderboardEntry(
                o.has("playerName") ? o.get("playerName").getAsString() : "?",
                o.has("playerUuid") ? UUID.fromString(o.get("playerUuid").getAsString()) : UUID.randomUUID(),
                o.has("durationSeconds") ? o.get("durationSeconds").getAsLong() : 0,
                o.has("date") ? o.get("date").getAsLong() : 0
            );
        }
    }

    /** Record a leaderboard entry if the run is a top-10 time. */
    public static void updateLeaderboard(DungeonInstance instance, MinecraftServer server) {
        if (instance == null || server == null) return;
        DungeonConfig config = instance.getConfig();
        String dungeonId = config.general.id;
        int tier = instance.getTier();
        long duration = (Instant.now().getEpochSecond()) - (instance.getStartTime() / 1000L);
        long now = Instant.now().getEpochSecond();

        // Each player gets a leaderboard entry
        for (UUID uuid : instance.getPlayers()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            String name = p != null ? p.getName().getString() : uuid.toString().substring(0, 8);
            LeaderboardEntry entry = new LeaderboardEntry(name, uuid, duration, now);

            Map<Integer, List<LeaderboardEntry>> tierMap = leaderboard.computeIfAbsent(dungeonId, k -> new java.util.concurrent.ConcurrentHashMap<>());
            List<LeaderboardEntry> entries = tierMap.computeIfAbsent(tier, k -> new CopyOnWriteArrayList<>());

            // Check if this player already has a better time
            boolean improved = true;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).playerUuid().equals(uuid)) {
                    if (entries.get(i).durationSeconds() <= duration) {
                        improved = false;
                    } else {
                        entries.remove(i);
                    }
                    break;
                }
            }

            if (improved) {
                entries.add(entry);
                // Sort by duration ascending (fastest first)
                entries.sort((a, b) -> Long.compare(a.durationSeconds(), b.durationSeconds()));
                // Keep only top 10
                while (entries.size() > 10) entries.remove(entries.size() - 1);
            }
        }
        saveLeaderboard();
    }

    /** Get leaderboard for all dungeons. */
    public static Map<String, Map<Integer, List<LeaderboardEntry>>> getFullLeaderboard() {
        return Collections.unmodifiableMap(leaderboard);
    }

    /** Get leaderboard for a specific dungeon. */
    public static Map<Integer, List<LeaderboardEntry>> getDungeonLeaderboard(String dungeonId) {
        Map<Integer, List<LeaderboardEntry>> tierMap = leaderboard.get(dungeonId);
        return tierMap != null ? Collections.unmodifiableMap(tierMap) : Collections.emptyMap();
    }

    private static Path leaderboardFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
            .resolve("cobblemon_dungeons").resolve("leaderboard.json");
    }

    public static void loadLeaderboard() {
        Path f = leaderboardFile();
        if (!Files.exists(f)) return;
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            leaderboard.clear();
            for (var dEntry : root.entrySet()) {
                Map<Integer, List<LeaderboardEntry>> tierMap = new java.util.concurrent.ConcurrentHashMap<>();
                for (var tEntry : dEntry.getValue().getAsJsonObject().entrySet()) {
                    int tier = Integer.parseInt(tEntry.getKey());
                    List<LeaderboardEntry> entries = new CopyOnWriteArrayList<>();
                    for (com.google.gson.JsonElement el : tEntry.getValue().getAsJsonArray()) {
                        entries.add(LeaderboardEntry.fromJson(el.getAsJsonObject()));
                    }
                    tierMap.put(tier, entries);
                }
                leaderboard.put(dEntry.getKey(), tierMap);
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("[Dungeons] Failed to load leaderboard", e);
        }
    }

    private static void saveLeaderboard() {
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            for (var dEntry : leaderboard.entrySet()) {
                com.google.gson.JsonObject tierObj = new com.google.gson.JsonObject();
                for (var tEntry : dEntry.getValue().entrySet()) {
                    com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                    tEntry.getValue().forEach(e -> arr.add(e.toJson()));
                    tierObj.add(String.valueOf(tEntry.getKey()), arr);
                }
                root.add(dEntry.getKey(), tierObj);
            }
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            Files.writeString(leaderboardFile(), gson.toJson(root));
        } catch (IOException e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("[Dungeons] Failed to save leaderboard", e);
        }
    }
}

