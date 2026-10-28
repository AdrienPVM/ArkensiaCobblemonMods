package com.sebastian.cobblemondungeons;

import com.sebastian.cobblemondungeons.dungeon.BattleHandler;
import com.sebastian.cobblemondungeons.party.DungeonsPartyManager;
import com.sebastian.cobblemondungeons.party.DungeonsPartyCommands;
import com.sebastian.cobblemondungeons.util.InteractionHandler;
import com.sebastian.cobblemondungeons.block.AlphaSpawnerBlock;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.Entity;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.item.BucketItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import com.sebastian.cobblemondungeons.fluid.PoisonWaterFluid;
import com.sebastian.cobblemondungeons.networking.BungeePayload;
import com.sebastian.cobblemondungeons.networking.DungeonJoinPayload;
import com.sebastian.cobblemondungeons.networking.DungeonHudPayload;
import com.sebastian.cobblemondungeons.networking.DungeonTicketSyncPayload;
import com.sebastian.cobblemondungeons.networking.PartyDebugPayload;
import com.sebastian.cobblemondungeons.networking.PartySyncPayload;
import com.sebastian.cobblemondungeons.networking.ProxyForwardPayload;
import com.sebastian.cobblemondungeons.config.ConfigLoader;
import com.sebastian.cobblemondungeons.util.PartySyncHandler;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;

import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.recipe.Ingredient;
import net.minecraft.sound.SoundEvents;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.entity.damage.DamageSource;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

public class CobblemonDungeons implements ModInitializer {
    public static final String MOD_ID = "cobblemon_dungeons";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String SERVER_ROLE_PROP = "cobblemon.dungeons.serverRole";
    private static final String SERVER_ROLE_ENV = "COBBLEMON_DUNGEONS_SERVER_ROLE";
    private static volatile Boolean isDungeonServer;
    private static int dungeonsPartyTickCounter = 0;
    private static int hazardTickCounter = 0;
    private static final long SLOW_TICK_THRESHOLD_NANOS = 25_000_000L;
    private static final double ALPHA_AGGRO_RANGE = 32.0;
    private static final double ALPHA_MELEE_RANGE = 2.4;
    private static final Map<UUID, Integer> alphaAggroCooldowns = new HashMap<>();
    private static final Map<UUID, Integer> alphaNavCooldowns = new HashMap<>();
    private static final Map<UUID, UUID> alphaTargetIds = new HashMap<>();
    private static final Map<UUID, Integer> alphaTargetSearchCooldowns = new HashMap<>();
    private static final Map<UUID, AlphaArchetype> alphaArchetypes = new HashMap<>();
    private static final Set<UUID> alphaStatsApplied = new HashSet<>();
    private static final Map<UUID, ServerBossBar> alphaBossBars = new HashMap<>();
    private static final Set<UUID> alphaBonusDamageGuard = new HashSet<>();
    private static final Set<UUID> alphaReductionGuard = new HashSet<>();
    private static final Map<UUID, Integer> alphaAoeCooldowns = new HashMap<>();
    private static final Map<UUID, Integer> alphaAoeChargeTicks = new HashMap<>();
    private static final double ALPHA_BOSSBAR_RANGE = 32.0;

    private enum AlphaArchetype {
        STALKER,   // Direct approach
        BOLTER,    // Fast and aggressive
        FLANKER,   // Tries to get around the side
        TACTICAL   // Circles the player
    }

    private static ElementalType POISON_TYPE;
    private static Map<ElementalType, Set<ElementalType>> typeStrongAgainst;
    private static Map<ElementalType, Set<ElementalType>> typeResistances;
    private static final Map<RegistryKey<net.minecraft.world.World>, Long2IntOpenHashMap> alphaSpawnerRegistry = new HashMap<>();
    private static final Map<RegistryKey<net.minecraft.world.World>, Long2IntOpenHashMap> dungeonChestRegistry = new HashMap<>();
    private static final Map<RegistryKey<net.minecraft.world.World>, Long2IntOpenHashMap> stakeSpawnerRegistry = new HashMap<>();
    private static final Map<RegistryKey<net.minecraft.world.World>, Long2IntOpenHashMap> portalRegistry = new HashMap<>();
    private static final Map<RegistryKey<net.minecraft.world.World>, Long2IntOpenHashMap> guardianSpawnerRegistry = new HashMap<>();
    private static final Map<RegistryKey<net.minecraft.world.World>, Long2ObjectOpenHashMap<LongArrayList>> alphaSpawnerChunkIndex = new HashMap<>();
    private static final Map<RegistryKey<net.minecraft.world.World>, Long2ObjectOpenHashMap<LongArrayList>> dungeonChestChunkIndex = new HashMap<>();
    private static final Map<RegistryKey<net.minecraft.world.World>, Long2ObjectOpenHashMap<LongArrayList>> stakeSpawnerChunkIndex = new HashMap<>();
    private static final Map<RegistryKey<net.minecraft.world.World>, Long2ObjectOpenHashMap<LongArrayList>> portalChunkIndex = new HashMap<>();
    private static final Map<RegistryKey<net.minecraft.world.World>, Long2ObjectOpenHashMap<LongArrayList>> guardianSpawnerChunkIndex = new HashMap<>();

    // Custom Armor Material for Gas Mask
    public static final RegistryEntry<ArmorMaterial> GAS_MASK_MATERIAL = Registry.registerReference(Registries.ARMOR_MATERIAL, Identifier.of(MOD_ID, "gas_mask"), new ArmorMaterial(
        Map.of(ArmorItem.Type.HELMET, 2),
        15,
        SoundEvents.ITEM_ARMOR_EQUIP_IRON,
        () -> Ingredient.ofItems(Items.IRON_INGOT),
        List.of(new ArmorMaterial.Layer(Identifier.of(MOD_ID, "gas_mask"))),
        0.0F,
        0.0F
    ));

    // Poison Water Fluid
    public static FlowableFluid STILL_POISON_WATER;
    public static FlowableFluid FLOWING_POISON_WATER;
    public static Block POISON_WATER_BLOCK;
    public static Item POISON_WATER_BUCKET;

    // Gas System
    public static Block GAS_BLOCK;
    public static Item GAS_ITEM;
    public static Item GAS_MASK;

    // Alpha Spawner
    public static Block ALPHA_SPAWNER_BLOCK;
    public static Item ALPHA_SPAWNER_ITEM;
    public static final int ALPHA_SPAWNER_ID_MIN = 1;
    public static final int ALPHA_SPAWNER_ID_MAX = 20;
    public static final Map<Integer, Block> ALPHA_SPAWNER_BLOCKS = new HashMap<>();
    public static final Map<Block, Integer> ALPHA_SPAWNER_BLOCK_IDS = new HashMap<>();
    public static final Map<Integer, Item> ALPHA_SPAWNER_ITEMS = new HashMap<>();

    // Guardian Spawner
    public static Block GUARDIAN_SPAWNER_BLOCK;
    public static Item GUARDIAN_SPAWNER_ITEM;
    public static final int GUARDIAN_SPAWNER_ID_MIN = 1;
    public static final int GUARDIAN_SPAWNER_ID_MAX = 10;
    public static final Map<Integer, Block> GUARDIAN_SPAWNER_BLOCKS = new HashMap<>();
    public static final Map<Block, Integer> GUARDIAN_SPAWNER_BLOCK_IDS = new HashMap<>();
    public static final Map<Integer, Item> GUARDIAN_SPAWNER_ITEMS = new HashMap<>();

    // Stake
    public static Block STAKE_BLOCK;
    public static Item STAKE_ITEM;
    public static Block STAKE_SPAWNER_BLOCK;
    public static Item STAKE_SPAWNER_ITEM;
    public static final int STAKE_SPAWNER_ID_MIN = 1;
    public static final int STAKE_SPAWNER_ID_MAX = 10;
    public static final Map<Integer, Block> STAKE_SPAWNER_BLOCKS = new HashMap<>();
    public static final Map<Block, Integer> STAKE_SPAWNER_BLOCK_IDS = new HashMap<>();
    public static final Map<Integer, Item> STAKE_SPAWNER_ITEMS = new HashMap<>();
    
    // Portal
    public static net.minecraft.block.Block PORTAL_BLOCK;
    public static net.minecraft.item.Item PORTAL_ITEM;
    public static final int PORTAL_ID_MIN = 1;
    public static final int PORTAL_ID_MAX = 10;
    public static final Map<Integer, Block> PORTAL_BLOCKS = new HashMap<>();
    public static final Map<Block, Integer> PORTAL_BLOCK_IDS = new HashMap<>();
    public static final Map<Integer, Item> PORTAL_ITEMS = new HashMap<>();
    
    // BlockEntity
    public static net.minecraft.block.entity.BlockEntityType<com.sebastian.cobblemondungeons.block.DungeonChestBlockEntity> DUNGEON_CHEST_BLOCK_ENTITY;

    // Dungeon Chest
    public static Block DUNGEON_CHEST_BLOCK;
    public static Item DUNGEON_CHEST_ITEM;
    public static final int DUNGEON_CHEST_ID_MIN = 1;
    public static final int DUNGEON_CHEST_ID_MAX = 10;
    public static final Map<Integer, Block> DUNGEON_CHEST_BLOCKS = new HashMap<>();
    public static final Map<Block, Integer> DUNGEON_CHEST_BLOCK_IDS = new HashMap<>();
    public static final Map<Integer, Item> DUNGEON_CHEST_ITEMS = new HashMap<>();

    // Gem Item
    public static Item GEM;
    public static Item DUNGEON_TICKET;

    // Register custom ScreenHandlerTypes for each mode to ensure correct slot layout on the client
    public static final ScreenHandlerType<com.sebastian.cobblemondungeons.gui.DungeonScreenHandler> DUNGEONS_SCREEN_HANDLER = 
        Registry.register(Registries.SCREEN_HANDLER, Identifier.of(MOD_ID, "dungeons"),
            new ScreenHandlerType<>((syncId, inv) -> new com.sebastian.cobblemondungeons.gui.DungeonScreenHandler(syncId, inv, com.sebastian.cobblemondungeons.gui.DungeonScreenHandler.ViewMode.DUNGEONS), net.minecraft.resource.featuretoggle.FeatureSet.empty()));
    
    public static final ScreenHandlerType<com.sebastian.cobblemondungeons.gui.DungeonScreenHandler> DUNGEONS_PARTY_SCREEN_HANDLER =
        Registry.register(Registries.SCREEN_HANDLER, Identifier.of(MOD_ID, "dungeons_party"),
            new ScreenHandlerType<>((syncId, inv) -> new com.sebastian.cobblemondungeons.gui.DungeonScreenHandler(syncId, inv, com.sebastian.cobblemondungeons.gui.DungeonScreenHandler.ViewMode.DUNGEONS_PARTY), net.minecraft.resource.featuretoggle.FeatureSet.empty()));

    public static final ScreenHandlerType<com.sebastian.cobblemondungeons.gui.DungeonScreenHandler> SEARCHING_SCREEN_HANDLER = 
        Registry.register(Registries.SCREEN_HANDLER, Identifier.of(MOD_ID, "searching"),
            new ScreenHandlerType<>((syncId, inv) -> new com.sebastian.cobblemondungeons.gui.DungeonScreenHandler(syncId, inv, com.sebastian.cobblemondungeons.gui.DungeonScreenHandler.ViewMode.SEARCHING), net.minecraft.resource.featuretoggle.FeatureSet.empty()));

    public static final ScreenHandlerType<com.sebastian.cobblemondungeons.gui.DungeonScreenHandler> INVITES_SCREEN_HANDLER = 
        Registry.register(Registries.SCREEN_HANDLER, Identifier.of(MOD_ID, "invites"),
            new ScreenHandlerType<>((syncId, inv) -> new com.sebastian.cobblemondungeons.gui.DungeonScreenHandler(syncId, inv, com.sebastian.cobblemondungeons.gui.DungeonScreenHandler.ViewMode.INVITES), net.minecraft.resource.featuretoggle.FeatureSet.empty()));

    public static final ScreenHandlerType<com.sebastian.cobblemondungeons.gui.DungeonScreenHandler> INVITE_PLAYERS_SCREEN_HANDLER = 
        Registry.register(Registries.SCREEN_HANDLER, Identifier.of(MOD_ID, "invite_players"),
            new ScreenHandlerType<>((syncId, inv) -> new com.sebastian.cobblemondungeons.gui.DungeonScreenHandler(syncId, inv, com.sebastian.cobblemondungeons.gui.DungeonScreenHandler.ViewMode.INVITE_PLAYERS), net.minecraft.resource.featuretoggle.FeatureSet.empty()));

    public static final ScreenHandlerType<com.sebastian.cobblemondungeons.gui.DungeonScreenHandler> BOSS_PROMPT_SCREEN_HANDLER = 
        Registry.register(Registries.SCREEN_HANDLER, Identifier.of(MOD_ID, "boss_prompt"),
            new ScreenHandlerType<>((syncId, inv) -> new com.sebastian.cobblemondungeons.gui.DungeonScreenHandler(syncId, inv, com.sebastian.cobblemondungeons.gui.DungeonScreenHandler.ViewMode.BOSS_PROMPT), net.minecraft.resource.featuretoggle.FeatureSet.empty()));
    
    public static final ScreenHandlerType<com.sebastian.cobblemondungeons.gui.DungeonScreenHandler> DUNGEON_GO_SCREEN_HANDLER = 
        Registry.register(Registries.SCREEN_HANDLER, Identifier.of(MOD_ID, "dungeon_go"),
            new ScreenHandlerType<>((syncId, inv) -> new com.sebastian.cobblemondungeons.gui.DungeonScreenHandler(syncId, inv, com.sebastian.cobblemondungeons.gui.DungeonScreenHandler.ViewMode.DUNGEON_GO), net.minecraft.resource.featuretoggle.FeatureSet.empty()));
    
    public static final ScreenHandlerType<com.sebastian.cobblemondungeons.gui.DungeonScreenHandler> DUNGEON_INFO_SCREEN_HANDLER = 
        Registry.register(Registries.SCREEN_HANDLER, Identifier.of(MOD_ID, "dungeon_info"),
            new ScreenHandlerType<>((syncId, inv) -> new com.sebastian.cobblemondungeons.gui.DungeonScreenHandler(syncId, inv, com.sebastian.cobblemondungeons.gui.DungeonScreenHandler.ViewMode.DUNGEON_INFO), net.minecraft.resource.featuretoggle.FeatureSet.empty()));
    
    public static final ScreenHandlerType<com.sebastian.cobblemondungeons.gui.DungeonScreenHandler> TICKET_EXCHANGE_SCREEN_HANDLER = 
        Registry.register(Registries.SCREEN_HANDLER, Identifier.of(MOD_ID, "ticket_exchange"),
            new ScreenHandlerType<>((syncId, inv) -> new com.sebastian.cobblemondungeons.gui.DungeonScreenHandler(syncId, inv, com.sebastian.cobblemondungeons.gui.DungeonScreenHandler.ViewMode.TICKET_EXCHANGE), net.minecraft.resource.featuretoggle.FeatureSet.empty()));
    
    public static void clearAlphaData(UUID uuid) {
        alphaAggroCooldowns.remove(uuid);
        alphaNavCooldowns.remove(uuid);
        alphaTargetIds.remove(uuid);
        alphaTargetSearchCooldowns.remove(uuid);
        alphaArchetypes.remove(uuid);
        alphaStatsApplied.remove(uuid);
        alphaBonusDamageGuard.remove(uuid);
        alphaReductionGuard.remove(uuid);
        alphaAoeCooldowns.remove(uuid);
        alphaAoeChargeTicks.remove(uuid);
        ServerBossBar bossBar = alphaBossBars.remove(uuid);
        if (bossBar != null) {
            bossBar.clearPlayers();
        }
    }

    public static boolean isProtected(net.minecraft.entity.LivingEntity entity) {
        if (entity.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).isOf(GAS_MASK) || 
            entity.getMainHandStack().isOf(GAS_MASK) || 
            entity.getOffHandStack().isOf(GAS_MASK)) {
            return true;
        }
        
        if (entity instanceof PokemonEntity pokemonEntity) {
            Pokemon pokemon = pokemonEntity.getPokemon();
            if (POISON_TYPE == null) POISON_TYPE = ElementalTypes.INSTANCE.getPOISON();
            
            // Type immunity
            if (POISON_TYPE.equals(pokemon.getPrimaryType()) || 
                POISON_TYPE.equals(pokemon.getSecondaryType())) {
                return true;
            }
            // Check Cobblemon held item
            if (pokemon.heldItem().isOf(GAS_MASK)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDungeonServer(net.minecraft.server.MinecraftServer server) {
        if (isDungeonServer != null) return isDungeonServer;
        String role = System.getProperty(SERVER_ROLE_PROP);
        if (role == null || role.isBlank()) {
            role = System.getenv(SERVER_ROLE_ENV);
        }
        isDungeonServer = role != null && role.equalsIgnoreCase("dungeon");
        LOGGER.info("Cobblemon Dungeons server role: {}", isDungeonServer ? "dungeon" : "main");
        return isDungeonServer;
    }

    public static List<BlockPos> getAlphaSpawnersInRange(ServerWorld world, BlockPos center, int radius, List<Integer> allowedIds) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        var index = alphaSpawnerChunkIndex.get(key);
        if (index == null || index.isEmpty()) return java.util.List.of();

        int radiusSq = radius * radius;
        int chunkRadius = (radius + 15) / 16;
        net.minecraft.util.math.ChunkPos centerChunk = new net.minecraft.util.math.ChunkPos(center);
        List<BlockPos> result = new java.util.ArrayList<>();
        boolean filterIds = allowedIds != null && !allowedIds.isEmpty();
        Long2IntOpenHashMap registry = alphaSpawnerRegistry.get(key);
        
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                long chunkKey = net.minecraft.util.math.ChunkPos.toLong(centerChunk.x + dx, centerChunk.z + dz);
                LongArrayList positions = index.get(chunkKey);
                if (positions != null) {
                    for (long posLong : positions) {
                        BlockPos pos = BlockPos.fromLong(posLong);
                        if (pos.getSquaredDistance(center) <= radiusSq) {
                            if (filterIds && registry != null) {
                                int id = registry.get(posLong);
                                if (!allowedIds.contains(id)) continue;
                            }
                            result.add(pos);
                        }
                    }
                }
            }
        }
        return result;
    }

    public static List<BlockPos> getStakeSpawnersInRange(ServerWorld world, BlockPos center, int radius, List<Integer> allowedIds) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        var index = stakeSpawnerChunkIndex.get(key);
        if (index == null || index.isEmpty()) return java.util.List.of();

        int radiusSq = radius * radius;
        int chunkRadius = (radius + 15) / 16;
        net.minecraft.util.math.ChunkPos centerChunk = new net.minecraft.util.math.ChunkPos(center);
        List<BlockPos> result = new java.util.ArrayList<>();
        boolean filterIds = allowedIds != null && !allowedIds.isEmpty();
        Long2IntOpenHashMap registry = stakeSpawnerRegistry.get(key);
        
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                long chunkKey = net.minecraft.util.math.ChunkPos.toLong(centerChunk.x + dx, centerChunk.z + dz);
                LongArrayList positions = index.get(chunkKey);
                if (positions != null) {
                    for (long posLong : positions) {
                        BlockPos pos = BlockPos.fromLong(posLong);
                        if (pos.getSquaredDistance(center) <= radiusSq) {
                            if (filterIds && registry != null) {
                                int id = registry.get(posLong);
                                if (!allowedIds.contains(id)) continue;
                            }
                            result.add(pos);
                        }
                    }
                }
            }
        }
        return result;
    }

    public static List<BlockPos> getGuardianSpawnersInRange(ServerWorld world, BlockPos center, int radius, List<Integer> allowedIds) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        var index = guardianSpawnerChunkIndex.get(key);
        if (index == null || index.isEmpty()) return java.util.List.of();

        int radiusSq = radius * radius;
        int chunkRadius = (radius + 15) / 16;
        net.minecraft.util.math.ChunkPos centerChunk = new net.minecraft.util.math.ChunkPos(center);
        List<BlockPos> result = new java.util.ArrayList<>();
        boolean filterIds = allowedIds != null && !allowedIds.isEmpty();
        Long2IntOpenHashMap registry = guardianSpawnerRegistry.get(key);
        
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                long chunkKey = net.minecraft.util.math.ChunkPos.toLong(centerChunk.x + dx, centerChunk.z + dz);
                LongArrayList positions = index.get(chunkKey);
                if (positions != null) {
                    for (long posLong : positions) {
                        BlockPos pos = BlockPos.fromLong(posLong);
                        if (pos.getSquaredDistance(center) <= radiusSq) {
                            if (filterIds && registry != null) {
                                int id = registry.get(posLong);
                                if (!allowedIds.contains(id)) continue;
                            }
                            result.add(pos);
                        }
                    }
                }
            }
        }
        return result;
    }

    public static List<BlockPos> getPortalsInRange(ServerWorld world, BlockPos center, int radius, List<Integer> allowedIds) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        var index = portalChunkIndex.get(key);
        if (index == null || index.isEmpty()) return java.util.List.of();

        int radiusSq = radius * radius;
        int chunkRadius = (radius + 15) / 16;
        net.minecraft.util.math.ChunkPos centerChunk = new net.minecraft.util.math.ChunkPos(center);
        List<BlockPos> result = new java.util.ArrayList<>();
        boolean filterIds = allowedIds != null && !allowedIds.isEmpty();
        Long2IntOpenHashMap registry = portalRegistry.get(key);
        
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                long chunkKey = net.minecraft.util.math.ChunkPos.toLong(centerChunk.x + dx, centerChunk.z + dz);
                LongArrayList positions = index.get(chunkKey);
                if (positions != null) {
                    for (long posLong : positions) {
                        BlockPos pos = BlockPos.fromLong(posLong);
                        if (pos.getSquaredDistance(center) <= radiusSq) {
                            if (filterIds && registry != null) {
                                int id = registry.get(posLong);
                                if (!allowedIds.contains(id)) continue;
                            }
                            result.add(pos);
                        }
                    }
                }
            }
        }
        return result;
    }

    public static LongArrayList getAlphaSpawnersInChunk(ServerWorld world, long chunkKey) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        var index = alphaSpawnerChunkIndex.get(key);
        return index != null ? index.get(chunkKey) : null;
    }

    public static LongArrayList getDungeonChestsInChunk(ServerWorld world, long chunkKey) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        var index = dungeonChestChunkIndex.get(key);
        return index != null ? index.get(chunkKey) : null;
    }

    public static LongArrayList getStakeSpawnersInChunk(ServerWorld world, long chunkKey) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        var index = stakeSpawnerChunkIndex.get(key);
        return index != null ? index.get(chunkKey) : null;
    }

    public static LongArrayList getGuardianSpawnersInChunk(ServerWorld world, long chunkKey) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        var index = guardianSpawnerChunkIndex.get(key);
        return index != null ? index.get(chunkKey) : null;
    }

    public static LongArrayList getPortalsInChunk(ServerWorld world, long chunkKey) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        var index = portalChunkIndex.get(key);
        return index != null ? index.get(chunkKey) : null;
    }

    public static List<BlockPos> getDungeonChestsInRange(ServerWorld world, BlockPos center, int radius, List<Integer> allowedIds) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        var index = dungeonChestChunkIndex.get(key);
        if (index == null || index.isEmpty()) return java.util.List.of();

        int radiusSq = radius * radius;
        int chunkRadius = (radius + 15) / 16;
        net.minecraft.util.math.ChunkPos centerChunk = new net.minecraft.util.math.ChunkPos(center);
        List<BlockPos> result = new java.util.ArrayList<>();
        boolean filterIds = allowedIds != null && !allowedIds.isEmpty();
        Long2IntOpenHashMap registry = dungeonChestRegistry.get(key);
        
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                long chunkKey = net.minecraft.util.math.ChunkPos.toLong(centerChunk.x + dx, centerChunk.z + dz);
                LongArrayList positions = index.get(chunkKey);
                if (positions != null) {
                    for (long posLong : positions) {
                        BlockPos pos = BlockPos.fromLong(posLong);
                        if (pos.getSquaredDistance(center) <= radiusSq) {
                            if (filterIds && registry != null) {
                                int id = registry.get(posLong);
                                if (!allowedIds.contains(id)) continue;
                            }
                            result.add(pos);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static void registerAlphaSpawnerChunk(ServerWorld world, net.minecraft.world.chunk.WorldChunk chunk) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        
        Long2IntOpenHashMap alphaPositions = alphaSpawnerRegistry.computeIfAbsent(key, k -> new Long2IntOpenHashMap());
        Long2ObjectOpenHashMap<LongArrayList> alphaChunkIndex = alphaSpawnerChunkIndex.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());
        
        Long2IntOpenHashMap chestPositions = dungeonChestRegistry.computeIfAbsent(key, k -> new Long2IntOpenHashMap());
        Long2ObjectOpenHashMap<LongArrayList> chestChunkIndex = dungeonChestChunkIndex.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());

        Long2IntOpenHashMap stakePositions = stakeSpawnerRegistry.computeIfAbsent(key, k -> new Long2IntOpenHashMap());
        Long2ObjectOpenHashMap<LongArrayList> stakeChunkIndex = stakeSpawnerChunkIndex.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());

        Long2IntOpenHashMap guardianPositions = guardianSpawnerRegistry.computeIfAbsent(key, k -> new Long2IntOpenHashMap());
        Long2ObjectOpenHashMap<LongArrayList> guardianChunkIndex = guardianSpawnerChunkIndex.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());

        Long2IntOpenHashMap portalPositions = portalRegistry.computeIfAbsent(key, k -> new Long2IntOpenHashMap());
        Long2ObjectOpenHashMap<LongArrayList> portalChunkIdx = portalChunkIndex.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());

        long chunkKey = chunk.getPos().toLong();
        LongArrayList chunkAlphaPositions = new LongArrayList();
        LongArrayList chunkChestPositions = new LongArrayList();
        LongArrayList chunkStakePositions = new LongArrayList();
        LongArrayList chunkGuardianPositions = new LongArrayList();
        LongArrayList chunkPortalPositions = new LongArrayList();

        var sections = chunk.getSectionArray();
        int sectionY = chunk.getBottomSectionCoord();
        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();
        for (int i = 0; i < sections.length; i++, sectionY++) {
            var section = sections[i];
            if (section == null || section.isEmpty()) continue;

            // Optimization: Skip section if it doesn't contain any of our specialty blocks
            if (!section.getBlockStateContainer().hasAny(state -> 
                ALPHA_SPAWNER_BLOCK_IDS.containsKey(state.getBlock()) || 
                DUNGEON_CHEST_BLOCK_IDS.containsKey(state.getBlock()) || 
                state.isOf(STAKE_SPAWNER_BLOCK) ||
                state.isOf(GUARDIAN_SPAWNER_BLOCK) ||
                state.isOf(PORTAL_BLOCK)
            )) {
                continue;
            }

            int baseY = sectionY << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block block = section.getBlockState(x, y, z).getBlock();
                        
                        Integer alphaId = ALPHA_SPAWNER_BLOCK_IDS.get(block);
                        if (alphaId != null) {
                            long posLong = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);
                            alphaPositions.put(posLong, alphaId.intValue());
                            chunkAlphaPositions.add(posLong);
                        }

                        Integer chestId = DUNGEON_CHEST_BLOCK_IDS.get(block);
                        if (chestId != null) {
                            long posLong = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);
                            chestPositions.put(posLong, chestId.intValue());
                            chunkChestPositions.add(posLong);
                        }

                        if (STAKE_SPAWNER_BLOCK_IDS.containsKey(block)) {
                            long posLong = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);
                            stakePositions.put(posLong, (int) STAKE_SPAWNER_BLOCK_IDS.get(block));
                            chunkStakePositions.add(posLong);
                        }

                        if (GUARDIAN_SPAWNER_BLOCK_IDS.containsKey(block)) {
                            long posLong = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);
                            guardianPositions.put(posLong, (int) GUARDIAN_SPAWNER_BLOCK_IDS.get(block));
                            chunkGuardianPositions.add(posLong);
                        }

                        if (PORTAL_BLOCK_IDS.containsKey(block)) {
                            long posLong = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);
                            portalPositions.put(posLong, (int) PORTAL_BLOCK_IDS.get(block));
                            chunkPortalPositions.add(posLong);
                        }
                    }
                }
            }
        }
        if (!chunkAlphaPositions.isEmpty()) {
            alphaChunkIndex.put(chunkKey, chunkAlphaPositions);
        }
        if (!chunkChestPositions.isEmpty()) {
            chestChunkIndex.put(chunkKey, chunkChestPositions);
        }
        if (!chunkStakePositions.isEmpty()) {
            stakeChunkIndex.put(chunkKey, chunkStakePositions);
        }
        if (!chunkGuardianPositions.isEmpty()) {
            guardianChunkIndex.put(chunkKey, chunkGuardianPositions);
        }
        if (!chunkPortalPositions.isEmpty()) {
            portalChunkIdx.put(chunkKey, chunkPortalPositions);
        }
    }

    private static void unregisterAlphaSpawnerChunk(ServerWorld world, net.minecraft.world.chunk.WorldChunk chunk) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        long chunkKey = chunk.getPos().toLong();

        Long2ObjectOpenHashMap<LongArrayList> alphaIndex = alphaSpawnerChunkIndex.get(key);
        if (alphaIndex != null) {
            LongArrayList positions = alphaIndex.remove(chunkKey);
            if (positions != null) {
                Long2IntOpenHashMap registry = alphaSpawnerRegistry.get(key);
                if (registry != null) {
                    for (long pos : positions) registry.remove(pos);
                }
            }
        }

        Long2ObjectOpenHashMap<LongArrayList> chestIndex = dungeonChestChunkIndex.get(key);
        if (chestIndex != null) {
            LongArrayList positions = chestIndex.remove(chunkKey);
            if (positions != null) {
                Long2IntOpenHashMap registry = dungeonChestRegistry.get(key);
                if (registry != null) {
                    for (long pos : positions) registry.remove(pos);
                }
            }
        }

        Long2ObjectOpenHashMap<LongArrayList> stakeIndex = stakeSpawnerChunkIndex.get(key);
        if (stakeIndex != null) {
            LongArrayList positions = stakeIndex.remove(chunkKey);
            if (positions != null) {
                Long2IntOpenHashMap registry = stakeSpawnerRegistry.get(key);
                if (registry != null) {
                    for (long pos : positions) registry.remove(pos);
                }
            }
        }

        Long2ObjectOpenHashMap<LongArrayList> guardianIndex = guardianSpawnerChunkIndex.get(key);
        if (guardianIndex != null) {
            LongArrayList positions = guardianIndex.remove(chunkKey);
            if (positions != null) {
                Long2IntOpenHashMap registry = guardianSpawnerRegistry.get(key);
                if (registry != null) {
                    for (long pos : positions) registry.remove(pos);
                }
            }
        }

        Long2ObjectOpenHashMap<LongArrayList> portalIndex = portalChunkIndex.get(key);
        if (portalIndex != null) {
            LongArrayList positions = portalIndex.remove(chunkKey);
            if (positions != null) {
                Long2IntOpenHashMap registry = portalRegistry.get(key);
                if (registry != null) {
                    for (long pos : positions) registry.remove(pos);
                }
            }
        }
    }

    private static void applyHazard(net.minecraft.entity.LivingEntity entity, int duration, int amplifier, boolean includeNausea) {
        if (!isProtected(entity)) {
            entity.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.POISON, duration, amplifier));
            if (includeNausea) {
                entity.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.NAUSEA, 200, 0));
            }
        }
    }

    public static void registerAlphaSpawner(ServerWorld world, BlockPos pos, int id) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        alphaSpawnerRegistry.computeIfAbsent(key, k -> new Long2IntOpenHashMap()).put(pos.asLong(), id);
        long chunkKey = net.minecraft.util.math.ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongArrayList list = alphaSpawnerChunkIndex.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>())
            .computeIfAbsent(chunkKey, k -> new LongArrayList());
        if (!list.contains(pos.asLong())) list.add(pos.asLong());
    }

    public static void unregisterAlphaSpawner(ServerWorld world, BlockPos pos) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        Long2IntOpenHashMap registry = alphaSpawnerRegistry.get(key);
        if (registry != null) registry.remove(pos.asLong());
        long chunkKey = net.minecraft.util.math.ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        var index = alphaSpawnerChunkIndex.get(key);
        if (index != null) {
            var list = index.get(chunkKey);
            if (list != null) list.remove(pos.asLong());
        }
    }

    public static void registerDungeonChest(ServerWorld world, BlockPos pos, int id) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        dungeonChestRegistry.computeIfAbsent(key, k -> new Long2IntOpenHashMap()).put(pos.asLong(), id);
        long chunkKey = net.minecraft.util.math.ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongArrayList list = dungeonChestChunkIndex.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>())
            .computeIfAbsent(chunkKey, k -> new LongArrayList());
        if (!list.contains(pos.asLong())) list.add(pos.asLong());
    }

    public static void unregisterDungeonChest(ServerWorld world, BlockPos pos) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        Long2IntOpenHashMap registry = dungeonChestRegistry.get(key);
        if (registry != null) registry.remove(pos.asLong());
        long chunkKey = net.minecraft.util.math.ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        var index = dungeonChestChunkIndex.get(key);
        if (index != null) {
            var list = index.get(chunkKey);
            if (list != null) list.remove(pos.asLong());
        }
    }

    public static void registerStakeSpawner(ServerWorld world, BlockPos pos, int id) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        stakeSpawnerRegistry.computeIfAbsent(key, k -> new Long2IntOpenHashMap()).put(pos.asLong(), id);
        long chunkKey = net.minecraft.util.math.ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongArrayList list = stakeSpawnerChunkIndex.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>())
            .computeIfAbsent(chunkKey, k -> new LongArrayList());
        if (!list.contains(pos.asLong())) list.add(pos.asLong());
    }

    public static void unregisterStakeSpawner(ServerWorld world, BlockPos pos) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        Long2IntOpenHashMap registry = stakeSpawnerRegistry.get(key);
        if (registry != null) registry.remove(pos.asLong());
        long chunkKey = net.minecraft.util.math.ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        var index = stakeSpawnerChunkIndex.get(key);
        if (index != null) {
            var list = index.get(chunkKey);
            if (list != null) list.remove(pos.asLong());
        }
    }

    public static void registerGuardianSpawner(ServerWorld world, BlockPos pos, int id) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        guardianSpawnerRegistry.computeIfAbsent(key, k -> new Long2IntOpenHashMap()).put(pos.asLong(), id);
        long chunkKey = net.minecraft.util.math.ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongArrayList list = guardianSpawnerChunkIndex.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>())
            .computeIfAbsent(chunkKey, k -> new LongArrayList());
        if (!list.contains(pos.asLong())) list.add(pos.asLong());
    }

    public static void unregisterGuardianSpawner(ServerWorld world, BlockPos pos) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        Long2IntOpenHashMap registry = guardianSpawnerRegistry.get(key);
        if (registry != null) registry.remove(pos.asLong());
        long chunkKey = net.minecraft.util.math.ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        var index = guardianSpawnerChunkIndex.get(key);
        if (index != null) {
            var list = index.get(chunkKey);
            if (list != null) list.remove(pos.asLong());
        }
    }

    public static void registerPortalBlock(ServerWorld world, BlockPos pos, int id) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        portalRegistry.computeIfAbsent(key, k -> new Long2IntOpenHashMap()).put(pos.asLong(), id);
        long chunkKey = net.minecraft.util.math.ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        LongArrayList list = portalChunkIndex.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>())
            .computeIfAbsent(chunkKey, k -> new LongArrayList());
        if (!list.contains(pos.asLong())) list.add(pos.asLong());
    }

    public static void unregisterPortalBlock(ServerWorld world, BlockPos pos) {
        RegistryKey<net.minecraft.world.World> key = world.getRegistryKey();
        Long2IntOpenHashMap registry = portalRegistry.get(key);
        if (registry != null) registry.remove(pos.asLong());
        long chunkKey = net.minecraft.util.math.ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        var index = portalChunkIndex.get(key);
        if (index != null) {
            var list = index.get(chunkKey);
            if (list != null) list.remove(pos.asLong());
        }
    }

    private static void registerAlphaSpawner(int id, Block block, Item item) {
        ALPHA_SPAWNER_BLOCKS.put(id, block);
        ALPHA_SPAWNER_BLOCK_IDS.put(block, id);
        ALPHA_SPAWNER_ITEMS.put(id, item);
    }

    private static void registerGuardianSpawner(int id, Block block, Item item) {
        GUARDIAN_SPAWNER_BLOCKS.put(id, block);
        GUARDIAN_SPAWNER_BLOCK_IDS.put(block, id);
        GUARDIAN_SPAWNER_ITEMS.put(id, item);
    }

    private static void registerStakeSpawner(int id, Block block, Item item) {
        STAKE_SPAWNER_BLOCKS.put(id, block);
        STAKE_SPAWNER_BLOCK_IDS.put(block, id);
        STAKE_SPAWNER_ITEMS.put(id, item);
    }

    private static void registerPortalBlock(int id, Block block, Item item) {
        PORTAL_BLOCKS.put(id, block);
        PORTAL_BLOCK_IDS.put(block, id);
        PORTAL_ITEMS.put(id, item);
    }

    private static void registerDungeonChest(int id, Block block, Item item) {
        DUNGEON_CHEST_BLOCKS.put(id, block);
        DUNGEON_CHEST_BLOCK_IDS.put(block, id);
        DUNGEON_CHEST_ITEMS.put(id, item);
    }

    private static double getMeleeRangeSq(PokemonEntity pokemonEntity) {
        double range = ALPHA_MELEE_RANGE;
        if (pokemonEntity.getCommandTags().contains("DungeonBoss")) {
            range = 4.0; // Decreased by 1.5 blocks from 5.5
        }
        return range * range;
    }

    private static void tickAlphaAggression(net.minecraft.server.world.ServerWorld world, PokemonEntity pokemonEntity) {
        UUID entityId = pokemonEntity.getUuid();
        if (!pokemonEntity.isAlive()) {
            clearAlphaData(entityId);
            return;
        }
        if (pokemonEntity.isBattling()) return;

        // 1. Throttled initialization
        if (!alphaArchetypes.containsKey(entityId)) {
            AlphaArchetype[] values = AlphaArchetype.values();
            alphaArchetypes.put(entityId, values[world.random.nextInt(values.length)]);
            applyAlphaCombatStatsIfNeeded(pokemonEntity);
        }

        long serverTicks = world.getServer().getTicks();

        // 2. Target Management & Looking
        int targetSearchCooldown = alphaTargetSearchCooldowns.getOrDefault(entityId, 0);
        if (targetSearchCooldown > 0) {
            alphaTargetSearchCooldowns.put(entityId, targetSearchCooldown - 1);
        }

        UUID lockedTargetId = alphaTargetIds.get(entityId);
        Entity lockedEntity = (lockedTargetId != null) ? world.getEntity(lockedTargetId) : null;
        net.minecraft.entity.LivingEntity currentTarget = null;
        double aggroRangeSq = ALPHA_AGGRO_RANGE * ALPHA_AGGRO_RANGE;

        if (lockedEntity instanceof net.minecraft.entity.LivingEntity living && living.isAlive() && !living.isRemoved()) {
            double distSq = living.squaredDistanceTo(pokemonEntity);
            if (distSq <= aggroRangeSq) {
                if (!(living instanceof PokemonEntity tp) || (!tp.isBattling() && !tp.getPokemon().getAspects().contains("alpha"))) {
                    currentTarget = living;
                }
            }
        }

        if (currentTarget == null || targetSearchCooldown <= 0) {
            double bestDistSq = (currentTarget != null) ? currentTarget.squaredDistanceTo(pokemonEntity) : Double.MAX_VALUE;
            net.minecraft.entity.LivingEntity bestFound = currentTarget;

            for (net.minecraft.server.network.ServerPlayerEntity player : world.getPlayers()) {
                if (player.isSpectator() || !player.isAlive() || player.isCreative()) continue;
                double distSq = player.squaredDistanceTo(pokemonEntity);
                if (distSq < aggroRangeSq && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestFound = player;
                }
            }

            Box searchBox = pokemonEntity.getBoundingBox().expand(ALPHA_AGGRO_RANGE);
            List<PokemonEntity> nearbyPokemon = world.getEntitiesByClass(
                PokemonEntity.class,
                searchBox,
                candidate -> candidate != pokemonEntity
                    && candidate.isAlive()
                    && !candidate.getPokemon().isWild()
                    && !candidate.isBattling()
                    && !candidate.getPokemon().getAspects().contains("alpha")
            );
            for (PokemonEntity candidate : nearbyPokemon) {
                double distSq = candidate.squaredDistanceTo(pokemonEntity);
                if (distSq < aggroRangeSq && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestFound = candidate;
                }
            }

            if (bestFound != null && (currentTarget == null || !bestFound.getUuid().equals(lockedTargetId))) {
                alphaTargetIds.put(entityId, bestFound.getUuid());
                currentTarget = bestFound;
            }
            // Throttle search more heavily if no target found to save CPU
            alphaTargetSearchCooldowns.put(entityId, currentTarget != null ? (15 + world.random.nextInt(10)) : (40 + world.random.nextInt(20)));
        }

        if (currentTarget == null) return;

        // ALWAYS Look at Target - Increased speed for better fluidity
        pokemonEntity.getLookControl().lookAt(currentTarget, 50.0F, 50.0F);

        // 3. Combat Logic
        int aoeCooldown = alphaAoeCooldowns.getOrDefault(entityId, 0);
        if (aoeCooldown > 0) alphaAoeCooldowns.put(entityId, aoeCooldown - 1);
        
        int chargeTicks = alphaAoeChargeTicks.getOrDefault(entityId, 0);
        if (chargeTicks > 0) {
            alphaAoeChargeTicks.put(entityId, chargeTicks - 1);
            if (chargeTicks > 1) {
                if (world.random.nextInt(2) == 0) {
                    world.spawnParticles(net.minecraft.particle.ParticleTypes.ANGRY_VILLAGER, 
                        pokemonEntity.getX(), pokemonEntity.getY() + pokemonEntity.getHeight() + 0.5, pokemonEntity.getZ(), 1, 0.2, 0.2, 0.2, 0);
                }
                return;
            } else {
                executeAoeAttack(world, pokemonEntity);
                alphaAoeCooldowns.put(entityId, pokemonEntity.getCommandTags().contains("DungeonBoss") ? 700 : 400);
                alphaAggroCooldowns.put(entityId, 0);
                return;
            }
        }

        if (aoeCooldown <= 0 && (serverTicks % 10 == 0)) {
            List<net.minecraft.entity.LivingEntity> nearby = world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class, 
                pokemonEntity.getBoundingBox().expand(5.0), 
                e -> (e != pokemonEntity && e.isAlive() && (e instanceof net.minecraft.server.network.ServerPlayerEntity p ? (!p.isSpectator() && !p.isCreative()) : (e instanceof PokemonEntity pe && !pe.getPokemon().isWild() && pe.getPokemon().getAspects().contains("alpha") == false && pe.getPokemon().getOwnerUUID() != null))));
            
            if (nearby.size() >= 3) {
                alphaAoeChargeTicks.put(entityId, 20);
                return;
            }
        }

        if (pokemonEntity.getCommandTags().contains("DungeonBoss") && (serverTicks % 20 == 0)) {
            com.sebastian.cobblemondungeons.dungeon.DungeonInstance instance = com.sebastian.cobblemondungeons.dungeon.DungeonManager.getBossInstance(entityId);
            if (instance != null && instance.getBossPhase() == 1 && (pokemonEntity.getHealth() / pokemonEntity.getMaxHealth() <= 0.15f)) {
                instance.setBossPhase(2);
                pokemonEntity.setHealth(pokemonEntity.getMaxHealth());
                com.sebastian.cobblemondungeons.dungeon.DungeonManager.broadcastToDungeon(instance, Text.translatable("cobblemondungeons.boss_final_phase", pokemonEntity.getCustomName().getString()).formatted(Formatting.RED, Formatting.BOLD), world.getServer());
                world.spawnParticles(net.minecraft.particle.ParticleTypes.EXPLOSION_EMITTER, pokemonEntity.getX(), pokemonEntity.getY() + 1, pokemonEntity.getZ(), 1, 0, 0, 0, 0);
            }
        }

        if (serverTicks % 20 == 0) updateAlphaBossBar(world, pokemonEntity);
        // ensureCorrectScale removed from here as it is handled in the main loop more efficiently

        int cooldown = alphaAggroCooldowns.getOrDefault(entityId, 0);
        if (cooldown > 0) alphaAggroCooldowns.put(entityId, cooldown - 1);
        int navCooldown = alphaNavCooldowns.getOrDefault(entityId, 0);
        if (navCooldown > 0) alphaNavCooldowns.put(entityId, navCooldown - 1);
        
        double meleeRangeSq = getMeleeRangeSq(pokemonEntity);
        double distSq = currentTarget.squaredDistanceTo(pokemonEntity);

        if (distSq <= meleeRangeSq) {
            pokemonEntity.getNavigation().stop();
        } else if (navCooldown <= 0 || !pokemonEntity.getNavigation().isFollowingPath()) {
            double speed = 1.15;
            AlphaArchetype archetype = alphaArchetypes.getOrDefault(entityId, AlphaArchetype.STALKER);
            if (archetype == AlphaArchetype.BOLTER) speed = 1.35;
            
            // Apply speed boost to all alphas/bosses
            speed *= 1.25;

            if (pokemonEntity.getCommandTags().contains("DungeonBoss")) {
                speed *= 2.2;
                com.sebastian.cobblemondungeons.dungeon.DungeonInstance instance = com.sebastian.cobblemondungeons.dungeon.DungeonManager.getBossInstance(entityId);
                if (instance != null && instance.getBossPhase() == 2) speed *= 1.3;
            }
            
            if (archetype == AlphaArchetype.FLANKER) {
                Vec3d dir = pokemonEntity.getPos().subtract(currentTarget.getPos()).normalize();
                Vec3d side = new Vec3d(-dir.z, 0, dir.x).multiply(3.5);
                if (entityId.getLeastSignificantBits() % 2 == 0) side = side.multiply(-1);
                pokemonEntity.getNavigation().startMovingTo(currentTarget.getX() + side.x, currentTarget.getY() + side.y, currentTarget.getZ() + side.z, 1.0);
            } else if (archetype == AlphaArchetype.TACTICAL) {
                double angle = (serverTicks % 100) / 100.0 * Math.PI * 2;
                if (entityId.getLeastSignificantBits() % 2 == 0) angle = -angle;
                pokemonEntity.getNavigation().startMovingTo(currentTarget.getX() + Math.cos(angle) * 4.5, currentTarget.getY(), currentTarget.getZ() + Math.sin(angle) * 4.5, 0.9);
            } else {
                pokemonEntity.getNavigation().startMovingTo(currentTarget, speed);
            }
            
            // Be more responsive when close to the target
            int nextNavCooldown = archetype == AlphaArchetype.TACTICAL ? 3 : 6;
            if (distSq < meleeRangeSq * 4) {
                nextNavCooldown = Math.max(1, nextNavCooldown / 2);
            }
            alphaNavCooldowns.put(entityId, nextNavCooldown);
        }

        if (cooldown <= 0 && distSq <= meleeRangeSq) {
            Vec3d toTarget = currentTarget.getPos().subtract(pokemonEntity.getPos()).normalize();
            if (pokemonEntity.getRotationVec(1.0F).normalize().dotProduct(toTarget) >= 0.5) {
                double damage = getAttributeValue(pokemonEntity, net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.4);
                if (pokemonEntity.getCommandTags().contains("DungeonBoss")) {
                    com.sebastian.cobblemondungeons.dungeon.DungeonInstance inst = com.sebastian.cobblemondungeons.dungeon.DungeonManager.getBossInstance(entityId);
                    if (inst != null && inst.getBossPhase() == 2) damage *= 1.5;
                }
                if (currentTarget instanceof PokemonEntity targetPkmn) {
                    damage *= isResistant(targetPkmn.getPokemon(), pokemonEntity.getPokemon()) ? 0.625 : 0.8125;
                    if (hasTypeAdvantage(pokemonEntity.getPokemon(), targetPkmn.getPokemon())) damage *= 2.0;
                } else {
                    damage *= 1.25;
                    if (pokemonEntity.getCommandTags().contains("DungeonBoss")) damage *= 1.2;
                }
                pokemonEntity.playAnimation("attack", java.util.List.of());
                if (pokemonEntity.getPokemon().getLevel() >= 30 && isGhostType(pokemonEntity.getPokemon())) {
                    currentTarget.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.BLINDNESS, Math.min(200, 20 + pokemonEntity.getPokemon().getLevel() * 2), 0));
                }
                currentTarget.damage(world.getDamageSources().mobAttack(pokemonEntity), (float) damage);
                currentTarget.takeKnockback(getAttributeValue(pokemonEntity, net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_KNOCKBACK, 0.4), pokemonEntity.getX() - currentTarget.getX(), pokemonEntity.getZ() - currentTarget.getZ());
                alphaAggroCooldowns.put(entityId, getAlphaMeleeCooldownTicks(pokemonEntity));
            }
        }
    }

    private static void executeAoeAttack(net.minecraft.server.world.ServerWorld world, PokemonEntity pokemonEntity) {
        UUID entityId = pokemonEntity.getUuid();
        double baseDamage = getAttributeValue(pokemonEntity, net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.4);
        
        if (pokemonEntity.getCommandTags().contains("DungeonBoss")) {
            com.sebastian.cobblemondungeons.dungeon.DungeonInstance instance = com.sebastian.cobblemondungeons.dungeon.DungeonManager.getBossInstance(entityId);
            if (instance != null && instance.getBossPhase() == 2) {
                baseDamage *= 1.5;
            }
        }
        
        double aoeDamage = baseDamage * 0.75; // 25% less damage
        double knockbackAmount = 1.5;
        
        world.playSound(null, pokemonEntity.getX(), pokemonEntity.getY(), pokemonEntity.getZ(), net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EXPLODE, net.minecraft.sound.SoundCategory.HOSTILE, 1.0F, 1.0F);
        world.playSound(null, pokemonEntity.getX(), pokemonEntity.getY(), pokemonEntity.getZ(), 
            net.minecraft.sound.SoundEvents.ENTITY_WOLF_GROWL, net.minecraft.sound.SoundCategory.HOSTILE, 1.5F, 0.5F + world.random.nextFloat() * 0.2F);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.EXPLOSION, pokemonEntity.getX(), pokemonEntity.getY() + 0.5, pokemonEntity.getZ(), 1, 0, 0, 0, 0);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.ANGRY_VILLAGER, 
            pokemonEntity.getX(), pokemonEntity.getY() + pokemonEntity.getHeight() + 0.5, pokemonEntity.getZ(), 
            10, 0.5, 0.5, 0.5, 0.1);
        pokemonEntity.playAnimation("attack", java.util.List.of());

        List<net.minecraft.entity.LivingEntity> targets = world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class, 
            pokemonEntity.getBoundingBox().expand(5.0), 
            e -> {
                if (e == pokemonEntity) return false;
                if (e instanceof net.minecraft.server.network.ServerPlayerEntity p) return !p.isSpectator() && p.isAlive();
                if (e instanceof PokemonEntity p) {
                    return p.isAlive() && !p.getPokemon().isWild() && !p.getPokemon().getAspects().contains("alpha");
                }
                return false;
            });

        net.minecraft.entity.LivingEntity bestStunTarget = null;
        double minStunDistSq = Double.MAX_VALUE;

        for (net.minecraft.entity.LivingEntity target : targets) {
            double damage = aoeDamage;
            if (target instanceof PokemonEntity targetPokemon) {
                double reduction = 0.8125; // 18.75% less damage to pokemon (originally 35% less, now 25% more than that)
                if (isResistant(targetPokemon.getPokemon(), pokemonEntity.getPokemon())) {
                    reduction = 0.625; // 37.5% less if resistant (originally 50% less, now 25% more than that)
                }
                damage *= reduction;
                if (hasTypeAdvantage(pokemonEntity.getPokemon(), targetPokemon.getPokemon())) {
                    damage *= 2.0;
                }
            } else if (target instanceof net.minecraft.server.network.ServerPlayerEntity) {
                damage *= 1.25;
                if (pokemonEntity.getCommandTags().contains("DungeonBoss")) {
                    damage *= 1.2; // 20% more damage to players for bosses
                }
            }
            
            target.damage(world.getDamageSources().mobAttack(pokemonEntity), (float) damage);
            
            // Knockback
            double dx = target.getX() - pokemonEntity.getX();
            double dz = target.getZ() - pokemonEntity.getZ();
            target.takeKnockback(knockbackAmount, -dx, -dz);
            
            // Stun (Slowness 10 for 3 seconds)
            target.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.SLOWNESS, 60, 9, false, false, true));
            // Also Weakness 10 to simulate stun (preventing them from doing damage if they can attack)
            target.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.WEAKNESS, 60, 9, false, false, true));

            double distSq = target.squaredDistanceTo(pokemonEntity);
            if (distSq < minStunDistSq) {
                minStunDistSq = distSq;
                bestStunTarget = target;
            }
        }

        if (bestStunTarget != null) {
            alphaTargetIds.put(entityId, bestStunTarget.getUuid());
            alphaTargetSearchCooldowns.put(entityId, 40); // Lock target for 2 seconds
        }
    }

    private static void updateAlphaBossBar(net.minecraft.server.world.ServerWorld world, PokemonEntity pokemonEntity) {
        UUID entityId = pokemonEntity.getUuid();
        ServerBossBar bossBar = alphaBossBars.get(entityId);
        BossBar.Color desiredColor = getBossBarColorForPokemon(pokemonEntity.getPokemon());
        if (bossBar == null) {
            bossBar = new ServerBossBar(pokemonEntity.getDisplayName(), desiredColor, BossBar.Style.PROGRESS);
            bossBar.setDarkenSky(false);
            bossBar.setDragonMusic(false);
            bossBar.setThickenFog(false);
            alphaBossBars.put(entityId, bossBar);
        }
        if (bossBar.getColor() != desiredColor) {
            bossBar.setColor(desiredColor);
        }

        bossBar.setName(pokemonEntity.getDisplayName());
        float percent = (float) (pokemonEntity.getHealth() / Math.max(1.0F, pokemonEntity.getMaxHealth()));
        bossBar.setPercent(Math.max(0.0F, Math.min(1.0F, percent)));

        double rangeSq = ALPHA_BOSSBAR_RANGE * ALPHA_BOSSBAR_RANGE;
        Set<net.minecraft.server.network.ServerPlayerEntity> inRange = new HashSet<>();
        for (net.minecraft.server.network.ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator() || !player.isAlive()) continue;
            if (player.squaredDistanceTo(pokemonEntity) > rangeSq) continue;
            inRange.add(player);
        }

        for (net.minecraft.server.network.ServerPlayerEntity player : inRange) {
            bossBar.addPlayer(player);
        }
        for (net.minecraft.server.network.ServerPlayerEntity player : List.copyOf(bossBar.getPlayers())) {
            if (!inRange.contains(player)) {
                bossBar.removePlayer(player);
            }
        }
    }

    private static BossBar.Color getBossBarColorForPokemon(Pokemon pokemon) {
        if (pokemon == null) return BossBar.Color.WHITE;
        var primary = pokemon.getPrimaryType();
        if (primary == null) return BossBar.Color.WHITE;
        if (primary.equals(ElementalTypes.INSTANCE.getFIRE())) return BossBar.Color.RED;
        if (primary.equals(ElementalTypes.INSTANCE.getWATER())) return BossBar.Color.BLUE;
        if (primary.equals(ElementalTypes.INSTANCE.getGRASS())) return BossBar.Color.GREEN;
        if (primary.equals(ElementalTypes.INSTANCE.getELECTRIC())) return BossBar.Color.YELLOW;
        if (primary.equals(ElementalTypes.INSTANCE.getPSYCHIC())) return BossBar.Color.PURPLE;
        if (primary.equals(ElementalTypes.INSTANCE.getFAIRY())) return BossBar.Color.PINK;
        if (primary.equals(ElementalTypes.INSTANCE.getICE())) return BossBar.Color.WHITE;
        if (primary.equals(ElementalTypes.INSTANCE.getDRAGON())) return BossBar.Color.PURPLE;
        if (primary.equals(ElementalTypes.INSTANCE.getGHOST())) return BossBar.Color.PURPLE;
        if (primary.equals(ElementalTypes.INSTANCE.getPOISON())) return BossBar.Color.PURPLE;
        if (primary.equals(ElementalTypes.INSTANCE.getGROUND())) return BossBar.Color.YELLOW;
        if (primary.equals(ElementalTypes.INSTANCE.getROCK())) return BossBar.Color.WHITE;
        if (primary.equals(ElementalTypes.INSTANCE.getSTEEL())) return BossBar.Color.WHITE;
        if (primary.equals(ElementalTypes.INSTANCE.getDARK())) return BossBar.Color.PURPLE;
        if (primary.equals(ElementalTypes.INSTANCE.getBUG())) return BossBar.Color.GREEN;
        if (primary.equals(ElementalTypes.INSTANCE.getFLYING())) return BossBar.Color.BLUE;
        if (primary.equals(ElementalTypes.INSTANCE.getFIGHTING())) return BossBar.Color.RED;
        if (primary.equals(ElementalTypes.INSTANCE.getNORMAL())) return BossBar.Color.WHITE;
        return BossBar.Color.WHITE;
    }

    private static Map<ElementalType, Set<ElementalType>> getTypeResistanceChart() {
        if (typeResistances != null) return typeResistances;
        Map<ElementalType, Set<ElementalType>> chart = new HashMap<>();
        chart.put(ElementalTypes.INSTANCE.getFIRE(), java.util.Set.of(
            ElementalTypes.INSTANCE.getFIRE(), ElementalTypes.INSTANCE.getGRASS(), ElementalTypes.INSTANCE.getICE(),
            ElementalTypes.INSTANCE.getBUG(), ElementalTypes.INSTANCE.getSTEEL(), ElementalTypes.INSTANCE.getFAIRY()
        ));
        chart.put(ElementalTypes.INSTANCE.getWATER(), java.util.Set.of(
            ElementalTypes.INSTANCE.getFIRE(), ElementalTypes.INSTANCE.getWATER(), ElementalTypes.INSTANCE.getICE(), ElementalTypes.INSTANCE.getSTEEL()
        ));
        chart.put(ElementalTypes.INSTANCE.getGRASS(), java.util.Set.of(
            ElementalTypes.INSTANCE.getWATER(), ElementalTypes.INSTANCE.getGRASS(), ElementalTypes.INSTANCE.getELECTRIC(), ElementalTypes.INSTANCE.getGROUND()
        ));
        chart.put(ElementalTypes.INSTANCE.getELECTRIC(), java.util.Set.of(
            ElementalTypes.INSTANCE.getELECTRIC(), ElementalTypes.INSTANCE.getFLYING(), ElementalTypes.INSTANCE.getSTEEL()
        ));
        chart.put(ElementalTypes.INSTANCE.getICE(), java.util.Set.of(
            ElementalTypes.INSTANCE.getICE()
        ));
        chart.put(ElementalTypes.INSTANCE.getFIGHTING(), java.util.Set.of(
            ElementalTypes.INSTANCE.getBUG(), ElementalTypes.INSTANCE.getROCK(), ElementalTypes.INSTANCE.getDARK()
        ));
        chart.put(ElementalTypes.INSTANCE.getPOISON(), java.util.Set.of(
            ElementalTypes.INSTANCE.getGRASS(), ElementalTypes.INSTANCE.getFIGHTING(), ElementalTypes.INSTANCE.getPOISON(),
            ElementalTypes.INSTANCE.getBUG(), ElementalTypes.INSTANCE.getFAIRY()
        ));
        chart.put(ElementalTypes.INSTANCE.getGROUND(), java.util.Set.of(
            ElementalTypes.INSTANCE.getPOISON(), ElementalTypes.INSTANCE.getROCK(), ElementalTypes.INSTANCE.getELECTRIC()
        ));
        chart.put(ElementalTypes.INSTANCE.getFLYING(), java.util.Set.of(
            ElementalTypes.INSTANCE.getGRASS(), ElementalTypes.INSTANCE.getFIGHTING(), ElementalTypes.INSTANCE.getBUG(), ElementalTypes.INSTANCE.getGROUND()
        ));
        chart.put(ElementalTypes.INSTANCE.getPSYCHIC(), java.util.Set.of(
            ElementalTypes.INSTANCE.getFIGHTING(), ElementalTypes.INSTANCE.getPSYCHIC()
        ));
        chart.put(ElementalTypes.INSTANCE.getBUG(), java.util.Set.of(
            ElementalTypes.INSTANCE.getGRASS(), ElementalTypes.INSTANCE.getFIGHTING(), ElementalTypes.INSTANCE.getGROUND()
        ));
        chart.put(ElementalTypes.INSTANCE.getROCK(), java.util.Set.of(
            ElementalTypes.INSTANCE.getNORMAL(), ElementalTypes.INSTANCE.getFIRE(), ElementalTypes.INSTANCE.getPOISON(), ElementalTypes.INSTANCE.getFLYING()
        ));
        chart.put(ElementalTypes.INSTANCE.getGHOST(), java.util.Set.of(
            ElementalTypes.INSTANCE.getPOISON(), ElementalTypes.INSTANCE.getBUG(), ElementalTypes.INSTANCE.getNORMAL(), ElementalTypes.INSTANCE.getFIGHTING()
        ));
        chart.put(ElementalTypes.INSTANCE.getDRAGON(), java.util.Set.of(
            ElementalTypes.INSTANCE.getFIRE(), ElementalTypes.INSTANCE.getWATER(), ElementalTypes.INSTANCE.getGRASS(), ElementalTypes.INSTANCE.getELECTRIC()
        ));
        chart.put(ElementalTypes.INSTANCE.getSTEEL(), java.util.Set.of(
            ElementalTypes.INSTANCE.getNORMAL(), ElementalTypes.INSTANCE.getGRASS(), ElementalTypes.INSTANCE.getICE(),
            ElementalTypes.INSTANCE.getFLYING(), ElementalTypes.INSTANCE.getPSYCHIC(), ElementalTypes.INSTANCE.getBUG(),
            ElementalTypes.INSTANCE.getROCK(), ElementalTypes.INSTANCE.getDRAGON(), ElementalTypes.INSTANCE.getSTEEL(),
            ElementalTypes.INSTANCE.getFAIRY(), ElementalTypes.INSTANCE.getPOISON()
        ));
        chart.put(ElementalTypes.INSTANCE.getDARK(), java.util.Set.of(
            ElementalTypes.INSTANCE.getGHOST(), ElementalTypes.INSTANCE.getDARK(), ElementalTypes.INSTANCE.getPSYCHIC()
        ));
        chart.put(ElementalTypes.INSTANCE.getFAIRY(), java.util.Set.of(
            ElementalTypes.INSTANCE.getFIGHTING(), ElementalTypes.INSTANCE.getBUG(), ElementalTypes.INSTANCE.getDARK(), ElementalTypes.INSTANCE.getDRAGON()
        ));
        chart.put(ElementalTypes.INSTANCE.getNORMAL(), java.util.Set.of(
            ElementalTypes.INSTANCE.getGHOST()
        ));
        typeResistances = chart;
        return chart;
    }

    private static boolean isResistant(Pokemon defender, Pokemon attacker) {
        if (attacker == null || defender == null) return false;
        List<ElementalType> attackerTypes = getPokemonTypes(attacker);
        List<ElementalType> defenderTypes = getPokemonTypes(defender);
        if (attackerTypes.isEmpty() || defenderTypes.isEmpty()) return false;
        Map<ElementalType, Set<ElementalType>> chart = getTypeResistanceChart();
        for (ElementalType defenderType : defenderTypes) {
            Set<ElementalType> resists = chart.get(defenderType);
            if (resists == null || resists.isEmpty()) continue;
            for (ElementalType attackerType : attackerTypes) {
                if (resists.contains(attackerType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTypeAdvantage(Pokemon attacker, Pokemon defender) {
        if (attacker == null || defender == null) return false;
        List<ElementalType> attackerTypes = getPokemonTypes(attacker);
        List<ElementalType> defenderTypes = getPokemonTypes(defender);
        if (attackerTypes.isEmpty() || defenderTypes.isEmpty()) return false;
        Map<ElementalType, Set<ElementalType>> chart = getTypeChart();
        for (ElementalType attackerType : attackerTypes) {
            Set<ElementalType> strongAgainst = chart.get(attackerType);
            if (strongAgainst == null || strongAgainst.isEmpty()) continue;
            for (ElementalType defenderType : defenderTypes) {
                if (strongAgainst.contains(defenderType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isGhostType(Pokemon pokemon) {
        if (pokemon == null) return false;
        ElementalType ghost = ElementalTypes.INSTANCE.getGHOST();
        ElementalType primary = pokemon.getPrimaryType();
        if (primary != null && primary.equals(ghost)) return true;
        ElementalType secondary = pokemon.getSecondaryType();
        return secondary != null && secondary.equals(ghost);
    }

    private static List<ElementalType> getPokemonTypes(Pokemon pokemon) {
        List<ElementalType> types = new java.util.ArrayList<>();
        ElementalType primary = pokemon.getPrimaryType();
        if (primary != null) {
            types.add(primary);
        }
        ElementalType secondary = pokemon.getSecondaryType();
        if (secondary != null) {
            types.add(secondary);
        }
        return types;
    }

    private static Map<ElementalType, Set<ElementalType>> getTypeChart() {
        if (typeStrongAgainst != null) return typeStrongAgainst;
        Map<ElementalType, Set<ElementalType>> chart = new HashMap<>();
        chart.put(ElementalTypes.INSTANCE.getNORMAL(), java.util.Set.of());
        chart.put(ElementalTypes.INSTANCE.getFIRE(), java.util.Set.of(
            ElementalTypes.INSTANCE.getGRASS(),
            ElementalTypes.INSTANCE.getICE(),
            ElementalTypes.INSTANCE.getBUG(),
            ElementalTypes.INSTANCE.getSTEEL()
        ));
        chart.put(ElementalTypes.INSTANCE.getWATER(), java.util.Set.of(
            ElementalTypes.INSTANCE.getFIRE(),
            ElementalTypes.INSTANCE.getGROUND(),
            ElementalTypes.INSTANCE.getROCK()
        ));
        chart.put(ElementalTypes.INSTANCE.getELECTRIC(), java.util.Set.of(
            ElementalTypes.INSTANCE.getWATER(),
            ElementalTypes.INSTANCE.getFLYING()
        ));
        chart.put(ElementalTypes.INSTANCE.getGRASS(), java.util.Set.of(
            ElementalTypes.INSTANCE.getWATER(),
            ElementalTypes.INSTANCE.getGROUND(),
            ElementalTypes.INSTANCE.getROCK()
        ));
        chart.put(ElementalTypes.INSTANCE.getICE(), java.util.Set.of(
            ElementalTypes.INSTANCE.getGRASS(),
            ElementalTypes.INSTANCE.getGROUND(),
            ElementalTypes.INSTANCE.getFLYING(),
            ElementalTypes.INSTANCE.getDRAGON()
        ));
        chart.put(ElementalTypes.INSTANCE.getFIGHTING(), java.util.Set.of(
            ElementalTypes.INSTANCE.getNORMAL(),
            ElementalTypes.INSTANCE.getICE(),
            ElementalTypes.INSTANCE.getROCK(),
            ElementalTypes.INSTANCE.getDARK(),
            ElementalTypes.INSTANCE.getSTEEL()
        ));
        chart.put(ElementalTypes.INSTANCE.getPOISON(), java.util.Set.of(
            ElementalTypes.INSTANCE.getGRASS(),
            ElementalTypes.INSTANCE.getFAIRY()
        ));
        chart.put(ElementalTypes.INSTANCE.getGROUND(), java.util.Set.of(
            ElementalTypes.INSTANCE.getFIRE(),
            ElementalTypes.INSTANCE.getELECTRIC(),
            ElementalTypes.INSTANCE.getPOISON(),
            ElementalTypes.INSTANCE.getROCK(),
            ElementalTypes.INSTANCE.getSTEEL()
        ));
        chart.put(ElementalTypes.INSTANCE.getFLYING(), java.util.Set.of(
            ElementalTypes.INSTANCE.getGRASS(),
            ElementalTypes.INSTANCE.getFIGHTING(),
            ElementalTypes.INSTANCE.getBUG()
        ));
        chart.put(ElementalTypes.INSTANCE.getPSYCHIC(), java.util.Set.of(
            ElementalTypes.INSTANCE.getFIGHTING(),
            ElementalTypes.INSTANCE.getPOISON()
        ));
        chart.put(ElementalTypes.INSTANCE.getBUG(), java.util.Set.of(
            ElementalTypes.INSTANCE.getGRASS(),
            ElementalTypes.INSTANCE.getPSYCHIC(),
            ElementalTypes.INSTANCE.getDARK()
        ));
        chart.put(ElementalTypes.INSTANCE.getROCK(), java.util.Set.of(
            ElementalTypes.INSTANCE.getFIRE(),
            ElementalTypes.INSTANCE.getICE(),
            ElementalTypes.INSTANCE.getFLYING(),
            ElementalTypes.INSTANCE.getBUG()
        ));
        chart.put(ElementalTypes.INSTANCE.getGHOST(), java.util.Set.of(
            ElementalTypes.INSTANCE.getPSYCHIC(),
            ElementalTypes.INSTANCE.getGHOST()
        ));
        chart.put(ElementalTypes.INSTANCE.getDRAGON(), java.util.Set.of(
            ElementalTypes.INSTANCE.getDRAGON()
        ));
        chart.put(ElementalTypes.INSTANCE.getDARK(), java.util.Set.of(
            ElementalTypes.INSTANCE.getPSYCHIC(),
            ElementalTypes.INSTANCE.getGHOST()
        ));
        chart.put(ElementalTypes.INSTANCE.getSTEEL(), java.util.Set.of(
            ElementalTypes.INSTANCE.getICE(),
            ElementalTypes.INSTANCE.getROCK(),
            ElementalTypes.INSTANCE.getFAIRY()
        ));
        chart.put(ElementalTypes.INSTANCE.getFAIRY(), java.util.Set.of(
            ElementalTypes.INSTANCE.getFIGHTING(),
            ElementalTypes.INSTANCE.getDRAGON(),
            ElementalTypes.INSTANCE.getDARK()
        ));
        typeStrongAgainst = chart;
        return chart;
    }

    public static void ensureCorrectScale(PokemonEntity pokemonEntity) {
        if (pokemonEntity == null || pokemonEntity.getPokemon() == null) return;

        com.cobblemon.mod.common.pokemon.Pokemon pokemon = pokemonEntity.getPokemon();
        NbtCompound persistentData = pokemon.getPersistentData();
        
        boolean isBoss = persistentData.getBoolean("IsDungeonBoss") || pokemonEntity.getCommandTags().contains("DungeonBoss");
        boolean isAlpha = persistentData.getBoolean("IsAlpha") || pokemonEntity.getCommandTags().contains("DungeonAlpha");
        
        if (!isBoss && !isAlpha) return;

        float scale = 1.0f;
        if (!pokemonEntity.getWorld().isClient) {
            if (!persistentData.contains("CustomScale")) {
                float targetScale = isBoss ? 4.0f : 1.3f;
                persistentData.putFloat("CustomScale", targetScale);
                persistentData.putBoolean("IsAlpha", isAlpha);
                persistentData.putBoolean("IsDungeonBoss", isBoss);
            }
            scale = persistentData.getFloat("CustomScale");

            // Ensure tags and aspects for other systems
            if (isBoss && !pokemonEntity.getCommandTags().contains("DungeonBoss")) pokemonEntity.addCommandTag("DungeonBoss");
            if (isAlpha && !pokemonEntity.getCommandTags().contains("DungeonAlpha")) pokemonEntity.addCommandTag("DungeonAlpha");
            
            if (!pokemon.getAspects().contains("alpha")) {
                pokemon.getAspects().add("alpha");
            }
        } else {
            if (persistentData.contains("CustomScale")) {
                scale = persistentData.getFloat("CustomScale");
            } else {
                // Fallback to aspects
                for (String aspect : pokemon.getAspects()) {
                    if (aspect.startsWith("scale:")) {
                        try {
                            float val = Float.parseFloat(aspect.substring(6));
                            if (val > 1.001f) {
                                scale = val;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                if (scale <= 1.001f) {
                    scale = isBoss ? 4.0f : 1.3f;
                }
            }
        }

        // Attribute scaling
        // We set GENERIC_SCALE to 1.0 because the user wants to ONLY use Cobblemon's scale modifiers.
        // In 1.21.1, GENERIC_SCALE also affects hitboxes, so keeping it at 1.0 prevents double-scaling.
        var scaleAttr = pokemonEntity.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_SCALE);
        if (scaleAttr != null && Math.abs(scaleAttr.getBaseValue() - 1.0f) > 0.001) {
            scaleAttr.setBaseValue(1.0f);
        }

        // Cobblemon visual scaling (TrackedData)
        // Use SCALE_MODIFIER as the single source of truth for model + hitbox sizing inside Cobblemon.
        try {
            if (Math.abs(pokemonEntity.getDataTracker().get(com.cobblemon.mod.common.entity.pokemon.PokemonEntity.getSCALE_MODIFIER()) - scale) > 0.001f) {
                pokemonEntity.getDataTracker().set(com.cobblemon.mod.common.entity.pokemon.PokemonEntity.getSCALE_MODIFIER(), scale);
            }
        } catch (Exception ignored) {}

        if (!pokemonEntity.getWorld().isClient) {
            // Visual aspects
            // We ensure aspect is scale:target
            java.util.Set<String> aspects = pokemon.getAspects();
            boolean foundCorrect = false;
            java.util.List<String> toRemove = new java.util.ArrayList<>();
            for (String aspect : aspects) {
                if (aspect.startsWith("scale:")) {
                    try {
                        float existingScale = Float.parseFloat(aspect.substring(6));
                        if (Math.abs(existingScale - scale) < 0.001f) {
                            foundCorrect = true;
                        } else {
                            toRemove.add(aspect);
                        }
                    } catch (Exception e) {
                        toRemove.add(aspect);
                    }
                }
            }
            
            if (!foundCorrect || !toRemove.isEmpty()) {
                aspects.removeAll(toRemove);
                if (!foundCorrect) {
                    aspects.add("scale:" + scale);
                }
                syncPokemon(pokemonEntity);
            }
        }
    }

    public static void applyCustomScale(PokemonEntity entity) {
        if (entity == null || entity.getPokemon() == null) return;
        
        // Ensure scale modifier and attributes are set correctly on both sides
        ensureCorrectScale(entity);

        NbtCompound persistentData = entity.getPokemon().getPersistentData();
        
        boolean hasScaleData = persistentData.contains("CustomScale");
        if (!hasScaleData) {
            // Check aspects as fallback
            for (String aspect : entity.getPokemon().getAspects()) {
                if (aspect.startsWith("scale:")) {
                    hasScaleData = true;
                    break;
                }
            }
        }

        if (hasScaleData || persistentData.getBoolean("IsAlpha") || persistentData.getBoolean("IsDungeonBoss")) {
            try {
                // Trigger dimension recalculation which our Mixin will intercept
                ((com.sebastian.cobblemondungeons.mixin.EntityAccessor)entity).callCalculateDimensions();
                syncPokemon(entity);
            } catch (Exception e) {
                LOGGER.error("Failed to apply custom scale to " + entity.getUuid(), e);
            }
        }
    }

    private static void syncPokemon(PokemonEntity entity) {
        try {
            String[] methods = {"updateAspects", "sync", "syncToPlayers", "onAspectsChanged", "onPokemonChanged", "refreshPokemon", "updateModel"};
            for (String methodName : methods) {
                try {
                    java.lang.reflect.Method m = entity.getClass().getMethod(methodName);
                    m.setAccessible(true);
                    m.invoke(entity);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static void applyAlphaCombatStatsIfNeeded(PokemonEntity pokemonEntity) {
        UUID entityId = pokemonEntity.getUuid();
        
        ensureCorrectScale(pokemonEntity);

        if (alphaStatsApplied.contains(entityId)) return;
        alphaStatsApplied.add(entityId);

        AlphaArchetype archetype = alphaArchetypes.getOrDefault(entityId, AlphaArchetype.STALKER);
        int bst = getBaseStatTotal(pokemonEntity);
        int level = pokemonEntity.getPokemon().getLevel();
        double speciesScale = clamp01((bst - 200) / 500.0);
        double levelScale = clamp01(level / 100.0);

        int tier = pokemonEntity.getPokemon().getPersistentData().getInt("DungeonTier");
        double hpScale;
        double damageScale;

        if (tier == 2) {
            hpScale = 1.7;
            damageScale = 1.6;
        } else if (tier == 3) {
            hpScale = 1.9;
            damageScale = 1.8;
        } else if (tier == 4) {
            hpScale = 2.2;
            damageScale = 1.9;
        } else if (tier >= 5) {
            hpScale = 2.5;
            damageScale = 2.2;
        } else {
            // Tier 1 or untiered (fallback) - keep dynamic logic
            hpScale = 0.8 + 1.6 * (0.5 * speciesScale + 0.5 * levelScale);
            damageScale = 0.7 + 1.8 * (0.6 * speciesScale + 0.4 * levelScale);
        }

        double speedScale = 0.85 + 0.35 * (0.5 * speciesScale + 0.5 * levelScale);
        double armorScale = 0.6 + 1.6 * (0.5 * speciesScale + 0.5 * levelScale);
        double knockbackScale = 0.6 + 1.6 * (0.6 * speciesScale + 0.4 * levelScale);

        double maxHealth = 12.0 * hpScale * 10.8;
        if (pokemonEntity.getCommandTags().contains("DungeonBoss")) {
            maxHealth *= 2.65625; // 15% less than 3.125
        }
        double attackDamage = 3.0 * damageScale * 1.518 * 1.1;
        double moveSpeed = 0.25 * speedScale * 1.25; // Apply speed boost to all alphas/bosses
        
        if (pokemonEntity.getCommandTags().contains("DungeonBoss")) {
            moveSpeed *= 1.4; // 40% faster base movement speed for bosses (was 25%)
        }
        double armor = 2.0 * armorScale * 1.25;
        double attackKnockback = 0.4 * knockbackScale;
        double knockbackRes = 0.5;
        if (pokemonEntity.getCommandTags().contains("DungeonBoss")) {
            knockbackRes = 0.9;
        }

        setAttributeBase(pokemonEntity, net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH, maxHealth);
        setAttributeBase(pokemonEntity, net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE, attackDamage);
        setAttributeBase(pokemonEntity, net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED, moveSpeed);
        setAttributeBase(pokemonEntity, net.minecraft.entity.attribute.EntityAttributes.GENERIC_ARMOR, armor);
        setAttributeBase(pokemonEntity, net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_KNOCKBACK, attackKnockback);
        setAttributeBase(pokemonEntity, net.minecraft.entity.attribute.EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, knockbackRes);
        setAttributeBase(pokemonEntity, net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_SPEED, getAttackSpeedFromCooldown(getAlphaMeleeCooldownTicks(pokemonEntity)));

        pokemonEntity.setHealth((float) maxHealth);
    }

    private static int getBaseStatTotal(PokemonEntity pokemonEntity) {
        Pokemon pokemon = pokemonEntity.getPokemon();
        var species = pokemon.getSpecies();
        if (species == null) return 300;
        Map<com.cobblemon.mod.common.api.pokemon.stats.Stat, Integer> baseStats = species.getBaseStats();
        if (baseStats == null || baseStats.isEmpty()) return 300;
        int total = 0;
        total += getBaseStat(baseStats, Stats.HP);
        total += getBaseStat(baseStats, Stats.ATTACK);
        total += getBaseStat(baseStats, Stats.DEFENCE);
        total += getBaseStat(baseStats, Stats.SPECIAL_ATTACK);
        total += getBaseStat(baseStats, Stats.SPECIAL_DEFENCE);
        total += getBaseStat(baseStats, Stats.SPEED);
        return total > 0 ? total : 300;
    }

    private static int getBaseStat(Map<com.cobblemon.mod.common.api.pokemon.stats.Stat, Integer> baseStats, Stats stat) {
        Integer value = baseStats.get(stat);
        return value != null ? value : 0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int getAlphaMeleeCooldownTicks(PokemonEntity pokemonEntity) {
        UUID entityId = pokemonEntity.getUuid();
        AlphaArchetype archetype = alphaArchetypes.getOrDefault(entityId, AlphaArchetype.STALKER);
        int bst = getBaseStatTotal(pokemonEntity);
        int level = pokemonEntity.getPokemon().getLevel();
        double speciesScale = clamp01((bst - 200) / 500.0);
        double levelScale = clamp01(level / 100.0);
        int ticks = getAlphaMeleeCooldownTicks(levelScale, speciesScale, archetype);
        
        if (pokemonEntity.getCommandTags().contains("DungeonBoss")) {
            ticks = (int)(ticks * 1.1); // Bosses attack 10% slower than alphas (was 5% faster)
        }
        
        return Math.max(10, ticks);
    }

    private static int getAlphaMeleeCooldownTicks(double levelScale, double speciesScale, AlphaArchetype archetype) {
        // Significantly increased base cooldown to make attacks much less frequent
        int cooldown = (int) Math.round(50 - 10 * speciesScale - 15 * levelScale);
        if (archetype == AlphaArchetype.BOLTER) cooldown -= 5;
        if (archetype == AlphaArchetype.TACTICAL) cooldown += 5;
        // Increased range: min 20 ticks (1s), max 100 ticks (5s)
        return Math.max(20, Math.min(100, cooldown));
    }

    private static double getAttackSpeedFromCooldown(int cooldownTicks) {
        return 20.0 / Math.max(1, cooldownTicks);
    }

    public static void setAttributeBase(PokemonEntity entity, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute, double value) {
        var instance = entity.getAttributeInstance(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static double getAttributeValue(PokemonEntity entity, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute, double fallback) {
        var instance = entity.getAttributeInstance(attribute);
        return instance != null ? instance.getValue() : fallback;
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Cobblemon Dungeons...");

        // Load config files early so all systems have access to them
        ConfigLoader.load();

        // Block damage to dungeon trainers and reduce damage to alphas from regular pokemon
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity.getCommandTags().contains("DungeonTrainer")) {
                return false;
            }

            // Pokemon deal 15% less damage to alphas and bosses
            if (entity instanceof PokemonEntity pokemonEntity && pokemonEntity.getPokemon().getAspects().contains("alpha")) {
                net.minecraft.entity.Entity attacker = source.getAttacker();
                if (attacker instanceof PokemonEntity attackerPkmn && !attackerPkmn.getPokemon().getAspects().contains("alpha")) {
                    UUID entityId = entity.getUuid();
                    if (!alphaReductionGuard.contains(entityId)) {
                        try {
                            alphaReductionGuard.add(entityId);
                            entity.damage(source, amount * 0.85f);
                        } finally {
                            alphaReductionGuard.remove(entityId);
                        }
                        return false; // cancel original damage
                    }
                }
            }

            return true;
        });

        // Register Networking Payloads
        PayloadTypeRegistry.playS2C().register(BungeePayload.ID, BungeePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(BungeePayload.ID, BungeePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ProxyForwardPayload.ID, ProxyForwardPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PartySyncPayload.ID, PartySyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PartySyncPayload.ID, PartySyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PartyDebugPayload.ID, PartyDebugPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PartyDebugPayload.ID, PartyDebugPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(DungeonJoinPayload.ID, DungeonJoinPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(DungeonJoinPayload.ID, DungeonJoinPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(DungeonHudPayload.ID, DungeonHudPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(DungeonTicketSyncPayload.ID, DungeonTicketSyncPayload.CODEC);

        // Handle BungeeCord plugin messages (Forwarded messages)
        ServerPlayNetworking.registerGlobalReceiver(BungeePayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                // Party sync arrives over proxy messaging to avoid SQL desync.
                PartySyncHandler.handleBungeePayload(payload, context.server());
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(PartySyncPayload.ID, (payload, context) -> {
            context.server().execute(() -> PartySyncHandler.handleDirectPartyPayload(context.server(), payload.data()));
        });
        ServerPlayNetworking.registerGlobalReceiver(PartyDebugPayload.ID, (payload, context) -> {
            context.server().execute(() -> PartySyncHandler.handleDebugPayload(context.server(), payload.data()));
        });

        // Handle Player Join for sync
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            server.execute(() -> {
                com.sebastian.cobblemondungeons.util.PlayerSyncHandler.ensureCobblemonObservers(handler.getPlayer(), false);
                boolean isDungeon = isDungeonServer(server);
                if (isDungeon) {
                    com.sebastian.cobblemondungeons.dungeon.DungeonManager.applyPendingSync(handler.getPlayer(), true);
                } else {
                    com.sebastian.cobblemondungeons.dungeon.DungeonManager.scheduleSqlSyncIfEmpty(handler.getPlayer(), 40);
                }
                // Delay party apply so Cobblemon finishes initializing PlayerPartyStore after join.
                PartySyncHandler.applyPendingPartyDelayed(handler.getPlayer(), server);
                
                // Clear HUD on join to ensure it doesn't persist from other servers
                ServerPlayNetworking.send(handler.getPlayer(), new DungeonHudPayload("", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false));

                // Sync dungeon tickets config from server to client
                ServerPlayNetworking.send(handler.getPlayer(), new DungeonTicketSyncPayload(ConfigLoader.getTicketConfigJson()));

                // Handle re-joining if relogging on the same server
                com.sebastian.cobblemondungeons.dungeon.DungeonManager.handleRejoin(handler.getPlayer());
            });
        });

        // Handle Player Respawn
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            newPlayer.getServer().execute(() -> {
                com.sebastian.cobblemondungeons.dungeon.DungeonManager.handlePlayerRespawn(newPlayer);
            });
        });

        // Handle Player Disconnect to cleanup active instances
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            server.execute(() -> {
                com.sebastian.cobblemondungeons.dungeon.DungeonManager.handleDisconnect(handler.getPlayer());
            });
        });

        // Handle Server Shutdown to close database connections
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            com.sebastian.cobblemondungeons.util.DatabaseManager.close();
        });

        // Register client sending dungeon join info
        ServerPlayNetworking.registerGlobalReceiver(DungeonJoinPayload.ID, (payload, context) -> {
            String dungeonId = payload.dungeonId();
            java.util.UUID partyId = payload.partyId();
            int partySize = payload.partySize();
            NbtCompound syncData = payload.syncData();
            int tier = payload.tier();
            context.server().execute(() -> {
                com.sebastian.cobblemondungeons.dungeon.DungeonManager.handleCrossServerJoin(context.player(), dungeonId, partyId, partySize, syncData, tier);
            });
        });

        // Ensure scale is correct when entity is loaded or tracked
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity pokemonEntity) {
                // Reapply the scale ONE SERVER TICK LATER
                world.getServer().execute(() -> {
                    if (pokemonEntity.isAlive()) {
                        applyCustomScale(pokemonEntity);
                    }
                });
            }
        });

        EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
            if (entity instanceof PokemonEntity pokemonEntity) {
                applyCustomScale(pokemonEntity);
            }
        });

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!(entity instanceof PokemonEntity pokemonEntity)) return;
            if (!pokemonEntity.getPokemon().getAspects().contains("alpha")) return;
            
            Entity attacker = source.getAttacker();
            UUID entityId = pokemonEntity.getUuid();
            
            // Aggressive revenge: Immediately target the attacker
            if (attacker instanceof net.minecraft.entity.LivingEntity livingAttacker && !attacker.isRemoved() && livingAttacker.isAlive()) {
                if (!(livingAttacker instanceof PokemonEntity p) || !p.getPokemon().getAspects().contains("alpha")) {
                    alphaTargetIds.put(entityId, attacker.getUuid());
                    alphaTargetSearchCooldowns.put(entityId, 100); // Lock onto attacker for 5 seconds
                }
            }

            if (!(attacker instanceof PokemonEntity attackingPokemon)) return;
            if (!hasTypeAdvantage(attackingPokemon.getPokemon(), pokemonEntity.getPokemon())) return;
            if (alphaBonusDamageGuard.contains(entityId)) return;

            float extra = damageTaken;
            if (extra <= 0.0F) return;

            try {
                alphaBonusDamageGuard.add(entityId);
                pokemonEntity.damage(source, extra);
            } finally {
                alphaBonusDamageGuard.remove(entityId);
            }
        });

        // Register Poison Water
        STILL_POISON_WATER = Registry.register(Registries.FLUID, Identifier.of(MOD_ID, "poison_water"), new PoisonWaterFluid.Still());
        FLOWING_POISON_WATER = Registry.register(Registries.FLUID, Identifier.of(MOD_ID, "flowing_poison_water"), new PoisonWaterFluid.Flowing());
        
        POISON_WATER_BLOCK = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "poison_water"), 
            new FluidBlock(STILL_POISON_WATER, net.minecraft.block.AbstractBlock.Settings.copy(Blocks.WATER).noCollision().nonOpaque().dropsNothing()) {
                @Override
                public void onEntityCollision(BlockState state, net.minecraft.world.World world, BlockPos pos, net.minecraft.entity.Entity entity) {
                    if (entity instanceof net.minecraft.entity.LivingEntity livingEntity) {
                        applyHazard(livingEntity, 100, 1, false);
                    }
                    super.onEntityCollision(state, world, pos, entity);
                }
            });
        
        POISON_WATER_BUCKET = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "poison_water_bucket"), 
            new BucketItem(STILL_POISON_WATER, new Item.Settings().recipeRemainder(Items.BUCKET).maxCount(1)));

        // Register Gas
        GAS_BLOCK = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "poison_gas"), 
            new net.minecraft.block.Block(net.minecraft.block.AbstractBlock.Settings.create()
                .noCollision()
                .nonOpaque()
                .replaceable()
                .dropsNothing()) {
                @Override
                public void onEntityCollision(BlockState state, net.minecraft.world.World world, BlockPos pos, net.minecraft.entity.Entity entity) {
                    if (!world.isClient && entity instanceof net.minecraft.entity.LivingEntity livingEntity) {
                        applyHazard(livingEntity, 60, 0, false);
                    }
                }

                @Override
                public void randomDisplayTick(BlockState state, net.minecraft.world.World world, BlockPos pos, net.minecraft.util.math.random.Random random) {
                    if (random.nextInt(5) == 0) { // Very low intensity
                        double x = pos.getX() + random.nextDouble();
                        double y = pos.getY() + random.nextDouble();
                        double z = pos.getZ() + random.nextDouble();
                        // Green tinted snowflake-like effect using happy_villager or custom colored particles
                        world.addParticle(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER, x, y, z, 0, 0, 0);
                    }
                }
            });
        
        // Register BlockItem for Gas so it can exist in inventories
        Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "poison_gas"), new net.minecraft.item.BlockItem(GAS_BLOCK, new Item.Settings()));
        
        GAS_ITEM = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "gas_detector"), new Item(new Item.Settings()));
        
        GAS_MASK = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "gas_mask"), 
            new ArmorItem(GAS_MASK_MATERIAL, ArmorItem.Type.HELMET, new Item.Settings().maxCount(1)));

        // Register Alpha Spawner (legacy id = 1)
        ALPHA_SPAWNER_BLOCK = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "alpha_spawner"),
            new AlphaSpawnerBlock(net.minecraft.block.AbstractBlock.Settings.create()
                .noCollision()
                .nonOpaque()
                .dropsNothing()));
        ALPHA_SPAWNER_ITEM = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "alpha_spawner"),
            new net.minecraft.item.BlockItem(ALPHA_SPAWNER_BLOCK, new Item.Settings()));
        registerAlphaSpawner(1, ALPHA_SPAWNER_BLOCK, ALPHA_SPAWNER_ITEM);

        for (int id = 2; id <= ALPHA_SPAWNER_ID_MAX; id++) {
            String name = "alpha_spawner_" + id;
            Block block = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, name),
                new AlphaSpawnerBlock(net.minecraft.block.AbstractBlock.Settings.create()
                    .noCollision()
                    .nonOpaque()
                    .dropsNothing()));
            Item item = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, name),
                new net.minecraft.item.BlockItem(block, new Item.Settings()));
            registerAlphaSpawner(id, block, item);
        }

        // Register Guardian Spawner
        GUARDIAN_SPAWNER_BLOCK = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "guardian_spawner"), 
            new com.sebastian.cobblemondungeons.block.GuardianSpawnerBlock(net.minecraft.block.AbstractBlock.Settings.create()
                .noCollision()
                .nonOpaque()
                .dropsNothing()));
        GUARDIAN_SPAWNER_ITEM = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "guardian_spawner"), 
            new net.minecraft.item.BlockItem(GUARDIAN_SPAWNER_BLOCK, new Item.Settings()));
        registerGuardianSpawner(1, GUARDIAN_SPAWNER_BLOCK, GUARDIAN_SPAWNER_ITEM);

        for (int id = 2; id <= GUARDIAN_SPAWNER_ID_MAX; id++) {
            String name = "guardian_spawner_" + id;
            Block block = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, name), 
                new com.sebastian.cobblemondungeons.block.GuardianSpawnerBlock(net.minecraft.block.AbstractBlock.Settings.create()
                    .noCollision()
                    .nonOpaque()
                    .dropsNothing()));
            Item item = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, name), 
                new net.minecraft.item.BlockItem(block, new Item.Settings()));
            registerGuardianSpawner(id, block, item);
        }

        // Register Stake
        STAKE_BLOCK = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "stake"),
            new com.sebastian.cobblemondungeons.block.StakeBlock(net.minecraft.block.AbstractBlock.Settings.create()
                .noCollision()
                .nonOpaque()
                .dropsNothing()
                .sounds(net.minecraft.sound.BlockSoundGroup.METAL)));
        STAKE_ITEM = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "stake"),
            new net.minecraft.item.BlockItem(STAKE_BLOCK, new Item.Settings()));

        STAKE_SPAWNER_BLOCK = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "stake_spawner"),
            new com.sebastian.cobblemondungeons.block.StakeSpawnerBlock(net.minecraft.block.AbstractBlock.Settings.create()
                .noCollision()
                .nonOpaque()
                .dropsNothing()));
        STAKE_SPAWNER_ITEM = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "stake_spawner"),
            new net.minecraft.item.BlockItem(STAKE_SPAWNER_BLOCK, new Item.Settings()));
        registerStakeSpawner(1, STAKE_SPAWNER_BLOCK, STAKE_SPAWNER_ITEM);

        for (int id = 2; id <= STAKE_SPAWNER_ID_MAX; id++) {
            String name = "stake_spawner_" + id;
            Block block = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, name),
                new com.sebastian.cobblemondungeons.block.StakeSpawnerBlock(net.minecraft.block.AbstractBlock.Settings.create()
                    .noCollision()
                    .nonOpaque()
                    .dropsNothing()));
            Item item = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, name),
                new net.minecraft.item.BlockItem(block, new Item.Settings()));
            registerStakeSpawner(id, block, item);
        }

        // Register Portal
        PORTAL_BLOCK = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "portal"),
            new com.sebastian.cobblemondungeons.block.PortalBlock(net.minecraft.block.AbstractBlock.Settings.create()
                .noCollision()
                .nonOpaque()
                .dropsNothing()
                .luminance(state -> state.get(com.sebastian.cobblemondungeons.block.PortalBlock.VISIBLE) ? 15 : 0)));
        PORTAL_ITEM = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "portal"),
            new net.minecraft.item.BlockItem(PORTAL_BLOCK, new Item.Settings()));
        registerPortalBlock(1, PORTAL_BLOCK, PORTAL_ITEM);

        for (int id = 2; id <= PORTAL_ID_MAX; id++) {
            String name = "portal_" + id;
            Block block = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, name),
                new com.sebastian.cobblemondungeons.block.PortalBlock(net.minecraft.block.AbstractBlock.Settings.create()
                    .noCollision()
                    .nonOpaque()
                    .dropsNothing()
                    .luminance(state -> state.get(com.sebastian.cobblemondungeons.block.PortalBlock.VISIBLE) ? 15 : 0)));
            Item item = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, name),
                new net.minecraft.item.BlockItem(block, new Item.Settings()));
            registerPortalBlock(id, block, item);
        }

        // Register Dungeon Chests
        DUNGEON_CHEST_BLOCK = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "dungeon_chest"),
            new com.sebastian.cobblemondungeons.block.DungeonChestBlock(net.minecraft.block.AbstractBlock.Settings.create()
                .nonOpaque()
                .sounds(net.minecraft.sound.BlockSoundGroup.WOOD)
                .dropsNothing()));
        DUNGEON_CHEST_ITEM = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "dungeon_chest"),
            new net.minecraft.item.BlockItem(DUNGEON_CHEST_BLOCK, new net.minecraft.item.Item.Settings()));
        registerDungeonChest(1, DUNGEON_CHEST_BLOCK, DUNGEON_CHEST_ITEM);

        for (int id = 2; id <= DUNGEON_CHEST_ID_MAX; id++) {
            String name = "dungeon_chest_" + id;
            Block block = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, name),
                new com.sebastian.cobblemondungeons.block.DungeonChestBlock(net.minecraft.block.AbstractBlock.Settings.create()
                    .nonOpaque()
                    .sounds(net.minecraft.sound.BlockSoundGroup.WOOD)
                    .dropsNothing()));
            Item item = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, name),
                new net.minecraft.item.BlockItem(block, new net.minecraft.item.Item.Settings()));
            registerDungeonChest(id, block, item);
        }

        // Register Gem Item
        GEM = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "gem"), 
            new Item(new Item.Settings()) {
                @Override
                public void inventoryTick(ItemStack stack, net.minecraft.world.World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
                    if (!world.isClient && entity instanceof net.minecraft.server.network.ServerPlayerEntity player) {
                        com.sebastian.cobblemondungeons.dungeon.DungeonInstance instance = com.sebastian.cobblemondungeons.dungeon.DungeonManager.getInstance(player.getUuid());
                        if (instance != null) {
                            int count = stack.getCount();
                            com.sebastian.cobblemondungeons.dungeon.DungeonManager.handleGemFound(player, count);
                            stack.setCount(0);
                        }
                    }
                }
            });

        DUNGEON_TICKET = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "dungeon_ticket"), 
            new com.sebastian.cobblemondungeons.item.DungeonTicketItem(new Item.Settings().maxCount(32)));

        // Register Dungeon Chest Block Entity
        DUNGEON_CHEST_BLOCK_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(MOD_ID, "dungeon_chest_be"),
            net.minecraft.block.entity.BlockEntityType.Builder.create(com.sebastian.cobblemondungeons.block.DungeonChestBlockEntity::new,
                DUNGEON_CHEST_BLOCKS.values().toArray(new Block[0])).build(null));

        // Register Creative Tab
        Registry.register(Registries.ITEM_GROUP, Identifier.of(MOD_ID, "dungeons_tab"), FabricItemGroup.builder()
            .icon(() -> new ItemStack(Items.IRON_DOOR))
            .displayName(Text.translatable("cobblemondungeons.item_group"))
            .entries((displayContext, entries) -> {
                entries.add(POISON_WATER_BUCKET);
                entries.add(GAS_BLOCK);
                entries.add(GAS_ITEM);
                entries.add(GAS_MASK);
                entries.add(ALPHA_SPAWNER_ITEM);
                for (int id = 2; id <= ALPHA_SPAWNER_ID_MAX; id++) {
                    Item item = ALPHA_SPAWNER_ITEMS.get(id);
                    if (item != null) {
                        entries.add(item);
                    }
                }
                entries.add(GUARDIAN_SPAWNER_ITEM);
                entries.add(STAKE_ITEM);
                entries.add(STAKE_SPAWNER_ITEM);
                entries.add(PORTAL_ITEM);
                
                entries.add(DUNGEON_CHEST_ITEM);
                for (int id = 2; id <= DUNGEON_CHEST_ID_MAX; id++) {
                    Item item = DUNGEON_CHEST_ITEMS.get(id);
                    if (item != null) {
                        entries.add(item);
                    }
                }
                entries.add(GEM);
                
                // Add Dungeon Tickets from config
                com.sebastian.cobblemondungeons.config.DungeonTicketConfig tCfg = ConfigLoader.getTicketConfig();
                for (com.sebastian.cobblemondungeons.config.DungeonTicketConfig.TicketConfig t : tCfg.tickets) {
                    entries.add(com.sebastian.cobblemondungeons.item.DungeonTicketItem.createStack(t, 1));
                }
            })
            .build());

        // Poison Protection & Hazard Logic (Tick-based)
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            long tickStart = System.nanoTime();

            long pendingStart = System.nanoTime();
            com.sebastian.cobblemondungeons.util.PartySyncHandler.tickPending(server);
            com.sebastian.cobblemondungeons.dungeon.DungeonManager.tickPendingSyncs(server);
            com.sebastian.cobblemondungeons.dungeon.DungeonManager.tickPendingTransfers(server);
            com.sebastian.cobblemondungeons.dungeon.DungeonManager.tickPendingStarts(server);
            com.sebastian.cobblemondungeons.dungeon.DungeonManager.tickFastScans(server);
            long pendingNanos = System.nanoTime() - pendingStart;

            dungeonsPartyTickCounter++;
            boolean checkParties = dungeonsPartyTickCounter % 20 == 0;
            hazardTickCounter++;
            boolean checkPlayerHazards = hazardTickCounter % 5 == 0;
            boolean checkGasDetector = hazardTickCounter % 20 == 0;
            boolean checkPokemonHazards = hazardTickCounter % 10 == 0;

            int playerCount = server.getPlayerManager().getPlayerList().size();
            long playersStart = System.nanoTime();
            for (net.minecraft.server.network.ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                // Remove poison if protected
                if (isProtected(player)) {
                    if (player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.POISON)) {
                        player.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.POISON);
                    }
                }

                BlockPos feetPos = player.getBlockPos();
                BlockPos eyesPos = BlockPos.ofFloored(player.getEyePos());
                
                // Check if standing in Poison Water or Gas
                if (checkPlayerHazards) {
                    BlockState feetBlock = player.getWorld().getBlockState(feetPos);
                    BlockState eyesBlock = player.getWorld().getBlockState(eyesPos);
                    net.minecraft.fluid.FluidState feetFluid = player.getWorld().getFluidState(feetPos);
                    net.minecraft.fluid.FluidState eyesFluid = player.getWorld().getFluidState(eyesPos);
                    
                    if (feetFluid.isOf(STILL_POISON_WATER) || feetFluid.isOf(FLOWING_POISON_WATER) ||
                        eyesFluid.isOf(STILL_POISON_WATER) || eyesFluid.isOf(FLOWING_POISON_WATER) ||
                        feetBlock.isOf(POISON_WATER_BLOCK) || eyesBlock.isOf(POISON_WATER_BLOCK)) {
                        applyHazard(player, 100, 1, false);
                    } else if (feetBlock.isOf(GAS_BLOCK) || eyesBlock.isOf(GAS_BLOCK)) {
                        applyHazard(player, 60, 0, false);
                    }
                }

                // Gas Detector logic
                if (checkGasDetector && (player.getMainHandStack().isOf(GAS_ITEM) || player.getOffHandStack().isOf(GAS_ITEM))) {
                    boolean gasNearby = false;
                    for (BlockPos pos : BlockPos.iterate(feetPos.add(-5, -2, -5), feetPos.add(5, 5, 5))) {
                        if (player.getWorld().getBlockState(pos).isOf(GAS_BLOCK)) {
                            gasNearby = true;
                            break;
                        }
                    }
                    if (gasNearby) {
                        player.sendMessage(Text.translatable("cobblemondungeons.poison_warning").formatted(Formatting.RED), true);
                    }
                }

                // Pokemon DungeonsParty check
                if (checkParties) {
                    java.util.UUID uuid = player.getUuid();
                    
                    // Dungeon Team Wipe Check
                    if (com.sebastian.cobblemondungeons.dungeon.DungeonManager.getInstance(uuid) != null) {
                        if (com.sebastian.cobblemondungeons.dungeon.DungeonManager.isTeamWiped(player)) {
                            com.sebastian.cobblemondungeons.dungeon.DungeonManager.handlePokemonTeamWipe(player);
                        }
                    }

                    if (DungeonsPartyManager.getParty(uuid) != null ||
                        DungeonsPartyManager.isSearchingForGroup(uuid)) {
                        
                        if (!DungeonsPartyManager.hasPokemon(player)) {
                            player.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(Formatting.RED));
                            if (DungeonsPartyManager.getParty(uuid) != null) {
                                DungeonsPartyManager.leaveParty(uuid, server);
                            }
                            if (DungeonsPartyManager.isSearchingForGroup(uuid)) {
                                DungeonsPartyManager.setSearchingForGroup(uuid, false, server);
                            }
                        }
                    }
                }
            }
            long playersNanos = System.nanoTime() - playersStart;
            
        // Update Dungeons (Timer and HUD)
        long dungeonTickNanos = 0L;
        if (checkParties) {
            long dungeonStart = System.nanoTime();
            com.sebastian.cobblemondungeons.dungeon.DungeonManager.tickDungeons(server);
            dungeonTickNanos = System.nanoTime() - dungeonStart;
        }

        // Also check all loaded Pokemon entities
        int pokemonCount = 0;
        long pokemonStart = System.nanoTime();
        boolean isDungeonServer = isDungeonServer(server);
        long serverTicks = server.getTicks();

        for (net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
            // Copy entity list to avoid ConcurrentModificationException on iterateEntities()
            java.util.List<net.minecraft.entity.Entity> entities = new java.util.ArrayList<>();
            world.iterateEntities().forEach(entities::add);
            for (net.minecraft.entity.Entity entity : entities) {
                if (!(entity instanceof PokemonEntity pokemonEntity)) continue;

                // 1. Alpha AI - Every tick for movement/looking, but internal logic is throttled
                // Optimized isAlpha check: check tags first as it's fastest
                boolean isAlpha = false;
                boolean hasAlphaTag = false;
                
                if (!pokemonEntity.getCommandTags().isEmpty()) {
                    hasAlphaTag = pokemonEntity.getCommandTags().contains("DungeonAlpha") || pokemonEntity.getCommandTags().contains("DungeonBoss");
                    if (hasAlphaTag) isAlpha = true;
                }
                
                if (!isAlpha) {
                    com.cobblemon.mod.common.pokemon.Pokemon pokemon = pokemonEntity.getPokemon();
                    if (pokemon != null) {
                        isAlpha = pokemon.getPersistentData().getBoolean("IsAlpha") || 
                                  pokemon.getPersistentData().getBoolean("IsDungeonBoss") ||
                                  pokemon.getAspects().contains("alpha");
                    }
                }
                
                if (isAlpha) {
                    // Throttled scale enforcement to reduce lag (every 10 ticks)
                    if (serverTicks % 10 == 0) {
                        ensureCorrectScale(pokemonEntity);
                    }
                    
                    if (isDungeonServer || hasAlphaTag) {
                        tickAlphaAggression(world, pokemonEntity);
                    }
                }

                // 2. Poison Protection & Hazards - Throttled tasks over 10 ticks
                if ((entity.getId() + serverTicks) % 10 == 0) {
                    pokemonCount++;
                    if (isProtected(pokemonEntity)) {
                        if (pokemonEntity.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.POISON)) {
                            pokemonEntity.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.POISON);
                        }
                    } else {
                        BlockPos fPos = entity.getBlockPos();
                        net.minecraft.fluid.FluidState fFluid = world.getFluidState(fPos);
                        boolean hazard = false;
                        if (fFluid.isOf(STILL_POISON_WATER) || fFluid.isOf(FLOWING_POISON_WATER)) {
                            applyHazard(pokemonEntity, 100, 1, false);
                            hazard = true;
                        } else {
                            BlockState fState = world.getBlockState(fPos);
                            if (fState.isOf(POISON_WATER_BLOCK)) {
                                applyHazard(pokemonEntity, 100, 1, false);
                                hazard = true;
                            } else if (fState.isOf(GAS_BLOCK)) {
                                applyHazard(pokemonEntity, 60, 0, false);
                                hazard = true;
                            }
                        }

                        if (!hazard) {
                            BlockPos ePos = BlockPos.ofFloored(entity.getEyePos());
                            net.minecraft.fluid.FluidState eFluid = world.getFluidState(ePos);
                            if (eFluid.isOf(STILL_POISON_WATER) || eFluid.isOf(FLOWING_POISON_WATER)) {
                                applyHazard(pokemonEntity, 100, 1, false);
                            } else {
                                BlockState eState = world.getBlockState(ePos);
                                if (eState.isOf(POISON_WATER_BLOCK)) {
                                    applyHazard(pokemonEntity, 100, 1, false);
                                } else if (eState.isOf(GAS_BLOCK)) {
                                    applyHazard(pokemonEntity, 60, 0, false);
                                }
                            }
                        }
                    }
                }
            }
        }
        long pokemonNanos = System.nanoTime() - pokemonStart;

            long totalNanos = System.nanoTime() - tickStart;
            if (totalNanos >= SLOW_TICK_THRESHOLD_NANOS) {
                LOGGER.warn(
                    "Slow tick {}ms (pending={}ms players={}ms dungeon={}ms pokemon={}ms players={} pokemon={})",
                    totalNanos / 1_000_000.0,
                    pendingNanos / 1_000_000.0,
                    playersNanos / 1_000_000.0,
                    dungeonTickNanos / 1_000_000.0,
                    pokemonNanos / 1_000_000.0,
                    playerCount,
                    pokemonCount
                );
            }
        });

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            registerAlphaSpawnerChunk(world, chunk);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            unregisterAlphaSpawnerChunk(world, chunk);
        });

        // Initialize SQL Database if enabled
        com.sebastian.cobblemondungeons.util.DatabaseManager.init();

        // Load custom trainers if RCTApi is present
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            com.sebastian.cobblemondungeons.util.TrainerLoader.load(server);
        });

        // Initialize handlers
        InteractionHandler.init();
        try {
            BattleHandler.init();
        } catch (NoClassDefFoundError | Exception e) {
            LOGGER.warn("[CobblemonDungeons] BattleHandler failed to init (Cobblemon API version mismatch?): " + e.getMessage());
        }
        com.sebastian.cobblemondungeons.dungeon.DeathHandler.init();
        com.sebastian.cobblemondungeons.dungeon.CaptureHandler.init();

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            DungeonsPartyCommands.register(dispatcher);
            
            // Register /dungeons command
            dispatcher.register(net.minecraft.server.command.CommandManager.literal("dungeons")
                .executes(context -> {
                    try {
                        if (CobblemonDungeons.isDungeonServer(context.getSource().getServer())) {
                            context.getSource().sendError(Text.translatable("cobblemondungeons.main_server_only").formatted(Formatting.RED));
                            return 0;
                        }
                        net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                        if (!DungeonsPartyManager.hasPokemon(player)) {
                            player.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(Formatting.RED));
                            return 0;
                        }
                        com.sebastian.cobblemondungeons.gui.DungeonSelectionGui.open(player);
                    } catch (Exception e) {
                        LOGGER.error("Error executing /dungeons command", e);
                        context.getSource().sendError(Text.translatable("cobblemondungeons.internal_error", e.getMessage()));
                    }
                    return 1;
                })
                .then(net.minecraft.server.command.CommandManager.literal("sql")
                    .requires(source -> !CobblemonDungeons.isDungeonServer(source.getServer()))
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(net.minecraft.server.command.CommandManager.literal("test")
                        .executes(context -> {
                            context.getSource().sendFeedback(() -> net.minecraft.text.Text.literal(com.sebastian.cobblemondungeons.util.DatabaseManager.testConnection()), false);
                            return 1;
                        })
                    )
                )
                .then(net.minecraft.server.command.CommandManager.literal("partydebug")
                    .requires(source -> !CobblemonDungeons.isDungeonServer(source.getServer()))
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(net.minecraft.server.command.CommandManager.literal("on")
                        .executes(context -> {
                            PartySyncHandler.setDebugEnabled(true);
                            context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.command.debug_enabled"), false);
                            return 1;
                        })
                    )
                    .then(net.minecraft.server.command.CommandManager.literal("off")
                        .executes(context -> {
                            PartySyncHandler.setDebugEnabled(false);
                            context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.command.debug_disabled"), false);
                            return 1;
                        })
                    )
                    .then(net.minecraft.server.command.CommandManager.literal("dump")
                        .executes(context -> {
                            net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            PartySyncHandler.dumpPartyToChat(player, "command");
                            context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.command.debug_dumped"), false);
                            return 1;
                        })
                    )
                    .then(net.minecraft.server.command.CommandManager.literal("status")
                        .executes(context -> {
                            net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            PartySyncHandler.dumpStatusToChat(player, "command");
                            return 1;
                        })
                    )
                    .then(net.minecraft.server.command.CommandManager.literal("ping")
                        .then(net.minecraft.server.command.CommandManager.argument("server", com.mojang.brigadier.arguments.StringArgumentType.string())
                            .executes(context -> {
                                net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                String target = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "server");
                                PartySyncHandler.sendDebugPing(player, target);
                                context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.command.debug_ping_sent", target), false);
                                return 1;
                            })
                        )
                    )
                )
                .then(net.minecraft.server.command.CommandManager.literal("leave")
                    .executes(context -> {
                        try {
                            net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            com.sebastian.cobblemondungeons.dungeon.DungeonManager.leaveDungeon(player);
                        } catch (Exception e) {
                            LOGGER.error("Error executing /dungeons leave command", e);
                        }
                        return 1;
                    })
                )
            );
            
            // Alias /dungeon
            dispatcher.register(net.minecraft.server.command.CommandManager.literal("dungeon")
                .executes(context -> {
                    try {
                        if (CobblemonDungeons.isDungeonServer(context.getSource().getServer())) {
                            context.getSource().sendError(Text.translatable("cobblemondungeons.main_server_only").formatted(Formatting.RED));
                            return 0;
                        }
                        net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                        if (!DungeonsPartyManager.hasPokemon(player)) {
                            player.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(Formatting.RED));
                            return 0;
                        }
                        com.sebastian.cobblemondungeons.gui.DungeonSelectionGui.open(player);
                    } catch (Exception e) {
                        LOGGER.error("Error executing /dungeon command", e);
                        context.getSource().sendError(Text.translatable("cobblemondungeons.internal_error", e.getMessage()));
                    }
                    return 1;
                })
                .then(net.minecraft.server.command.CommandManager.literal("sql")
                    .requires(source -> !CobblemonDungeons.isDungeonServer(source.getServer()))
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(net.minecraft.server.command.CommandManager.literal("test")
                        .executes(context -> {
                            context.getSource().sendFeedback(() -> net.minecraft.text.Text.literal(com.sebastian.cobblemondungeons.util.DatabaseManager.testConnection()), false);
                            return 1;
                        })
                    )
                )
                .then(net.minecraft.server.command.CommandManager.literal("partydebug")
                    .requires(source -> !CobblemonDungeons.isDungeonServer(source.getServer()))
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(net.minecraft.server.command.CommandManager.literal("on")
                        .executes(context -> {
                            PartySyncHandler.setDebugEnabled(true);
                            context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.command.debug_enabled"), false);
                            return 1;
                        })
                    )
                    .then(net.minecraft.server.command.CommandManager.literal("off")
                        .executes(context -> {
                            PartySyncHandler.setDebugEnabled(false);
                            context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.command.debug_disabled"), false);
                            return 1;
                        })
                    )
                    .then(net.minecraft.server.command.CommandManager.literal("dump")
                        .executes(context -> {
                            net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            PartySyncHandler.dumpPartyToChat(player, "command");
                            context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.command.debug_dumped"), false);
                            return 1;
                        })
                    )
                    .then(net.minecraft.server.command.CommandManager.literal("status")
                        .executes(context -> {
                            net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            PartySyncHandler.dumpStatusToChat(player, "command");
                            return 1;
                        })
                    )
                    .then(net.minecraft.server.command.CommandManager.literal("ping")
                        .then(net.minecraft.server.command.CommandManager.argument("server", com.mojang.brigadier.arguments.StringArgumentType.string())
                            .executes(context -> {
                                net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                String target = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "server");
                                PartySyncHandler.sendDebugPing(player, target);
                                context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.command.debug_ping_sent", target), false);
                                return 1;
                            })
                        )
                    )
                )
                .then(net.minecraft.server.command.CommandManager.literal("leave")
                    .executes(context -> {
                        try {
                            net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            com.sebastian.cobblemondungeons.dungeon.DungeonManager.leaveDungeon(player);
                        } catch (Exception e) {
                            LOGGER.error("Error executing /dungeon leave command", e);
                        }
                        return 1;
                    })
                )
            );
        });
    }
}
