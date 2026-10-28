package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.PastureLimitsConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class PastureLimitsManager {

    private static final String PASTURE_BLOCK_ID = "cobblemon:pasture";
    private static final String[] OWNER_NBT_KEYS = { "OwnerId", "ownerId", "owner_id", "Owner", "owner" };

    private final Path configFile;
    private PastureLimitsConfig config = new PastureLimitsConfig();

    public PastureLimitsManager() {
        this.configFile = FabricLoader.getInstance().getConfigDir().resolve("cheeseguard_pastures.json");
    }

    public void load() {
        try {
            if (Files.exists(configFile)) {
                String json = new String(Files.readAllBytes(configFile));
                JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                this.config = PastureLimitsConfig.fromJson(o);
            } else {
                save();
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to load pasture limits config: {}", e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(configFile.getParent());
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(configFile, gson.toJson(config.toJson()));
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to save pasture limits config: {}", e.getMessage());
        }
    }

    public PastureLimitsConfig getConfig() { return config; }

    /** Returns null when placement is allowed; otherwise an enum reason. */
    public DenyReason canPlace(ServerWorld world, BlockPos placePos, UUID placerUuid) {
        int maxChunk = config.getMaxPerChunk();
        int maxPlayer = config.getMaxPerChunkPerPlayer();
        if (maxChunk <= 0 && maxPlayer <= 0) return null; // both disabled = unlimited via this mod

        ChunkPos cp = new ChunkPos(placePos);
        WorldChunk chunk = world.getChunk(cp.x, cp.z);

        int total = 0;
        int byPlayer = 0;
        RegistryWrapper.WrapperLookup lookup = world.getRegistryManager();

        for (var entry : chunk.getBlockEntities().entrySet()) {
            BlockEntity be = entry.getValue();
            if (be == null) continue;
            String typeId = Registries.BLOCK_ENTITY_TYPE.getId(be.getType()).toString();
            if (!PASTURE_BLOCK_ID.equals(typeId)) continue;
            total++;
            if (placerUuid != null && readOwnerUuid(be, lookup) instanceof UUID owner && owner.equals(placerUuid)) {
                byPlayer++;
            }
        }

        if (maxChunk > 0 && total >= maxChunk) return DenyReason.CHUNK_FULL;
        if (maxPlayer > 0 && byPlayer >= maxPlayer) return DenyReason.PLAYER_FULL;
        return null;
    }

    private static UUID readOwnerUuid(BlockEntity be, RegistryWrapper.WrapperLookup lookup) {
        try {
            NbtCompound nbt = be.createNbtWithIdentifyingData(lookup);
            for (String key : OWNER_NBT_KEYS) {
                if (!nbt.contains(key)) continue;
                NbtElement el = nbt.get(key);
                if (el == null) continue;
                if (nbt.containsUuid(key)) return nbt.getUuid(key);
                if (el.getType() == NbtElement.STRING_TYPE) {
                    try { return UUID.fromString(nbt.getString(key)); } catch (Exception ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public enum DenyReason { CHUNK_FULL, PLAYER_FULL }
}
