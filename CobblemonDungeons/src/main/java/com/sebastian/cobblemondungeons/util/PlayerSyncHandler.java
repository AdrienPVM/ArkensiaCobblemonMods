package com.sebastian.cobblemondungeons.util;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.StoreCoordinates;
import com.cobblemon.mod.common.api.storage.party.PartyPosition;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCBox;
import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.storage.player.GeneralPlayerData;
import com.cobblemon.mod.common.api.storage.player.adapter.PlayerDataJsonBackend;
import com.cobblemon.mod.common.api.pokedex.PokedexManager;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.lang.reflect.Method;

public class PlayerSyncHandler {
    private static boolean pokedexCodecWarned = false;
    private static boolean generalJsonWarned = false;
    private static PlayerDataJsonBackend generalJsonBackend;

    // Reuse Cobblemon's JSON backend for GeneralPlayerData to match upstream format.
    private static PlayerDataJsonBackend getGeneralJsonBackend() {
        if (generalJsonBackend == null) {
            generalJsonBackend = new PlayerDataJsonBackend();
        }
        return generalJsonBackend;
    }

    public static void ensureCobblemonObservers(ServerPlayerEntity player, boolean sendInit) {
        if (player == null) return;
        try {
            PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
            ensurePartyObserver(party, player);
            if (sendInit && party != null) {
                party.sendTo(player);
            }
        } catch (Exception ignored) {
        }

        try {
            PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
            ensurePcObserver(pc, player);
            if (sendInit && pc != null) {
                pc.sendTo(player);
            }
        } catch (Exception ignored) {
        }
    }

    public static NbtCompound serialize(ServerPlayerEntity player, boolean includePc) {
        NbtCompound nbt = new NbtCompound();
        RegistryWrapper.WrapperLookup registries = player.getRegistryManager();
        net.minecraft.registry.DynamicRegistryManager registryManager = player.getServer().getRegistryManager();
        
        // Minecraft Inventory
        NbtList inventoryNbt = new NbtList();
        player.getInventory().writeNbt(inventoryNbt);
        nbt.put("Inventory", inventoryNbt);
        
        // XP
        nbt.putInt("XpLevel", player.experienceLevel);
        nbt.putFloat("XpP", player.experienceProgress);
        nbt.putInt("XpTotal", player.totalExperience);
        
        // Ender Chest
        nbt.put("EnderItems", player.getEnderChestInventory().toNbtList(registries));
        
        // Health & Hunger
        nbt.putFloat("Health", player.getHealth());
        nbt.putInt("FoodLevel", player.getHungerManager().getFoodLevel());
        nbt.putFloat("FoodSaturation", player.getHungerManager().getSaturationLevel());

        // Status Effects
        NbtList effectsNbt = new NbtList();
        for (StatusEffectInstance effect : player.getStatusEffects()) {
            effectsNbt.add(effect.writeNbt());
        }
        nbt.put("ActiveEffects", effectsNbt);

        // Cobblemon
        // Party
        try {
            NbtCompound partyNbt = PartySyncHandler.serializeParty(player);
            if (partyNbt != null && !partyNbt.isEmpty()) {
                nbt.put("CobblemonParty", partyNbt);
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Serialized Cobblemon Party for " + player.getName().getString() + " (Keys: " + partyNbt.getKeys().size() + ")");
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to serialize Cobblemon Party for " + player.getName().getString(), e);
        }
        
        // PC
        if (includePc) {
            try {
                PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
                NbtCompound pcNbt = new NbtCompound();
                pc.saveToNBT(pcNbt, registryManager);
                nbt.put("CobblemonPC", pcNbt);
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Serialized Cobblemon PC for " + player.getName().getString() + " (Keys: " + pcNbt.getKeys().size() + ")");
            } catch (Exception e) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to serialize Cobblemon PC for " + player.getName().getString(), e);
            }
        }

        // General Player Data (Starter state, etc.)
        try {
            GeneralPlayerData generalData = Cobblemon.INSTANCE.getPlayerDataManager().getGenericData(player);
            if (generalData != null) {
                String generalJson = serializeGeneralPlayerData(generalData);
                if (generalJson != null && !generalJson.isBlank()) {
                    nbt.putString("CobblemonGeneral", generalJson);
                    com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Serialized Cobblemon General Data for " + player.getName().getString() + " (Length: " + generalJson.length() + ")");
                }
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to serialize Cobblemon General Data for " + player.getName().getString(), e);
        }

        // Pokedex
        try {
            PokedexManager pokedexData = Cobblemon.INSTANCE.getPlayerDataManager().getPokedexData(player);
            if (pokedexData != null) {
                NbtCompound pokedexNbt = new NbtCompound();
                if (serializePokedex(pokedexData, pokedexNbt)) {
                    nbt.put("CobblemonPokedex", pokedexNbt);
                    com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Serialized Cobblemon Pokedex for " + player.getName().getString() + " (Keys: " + pokedexNbt.getKeys().size() + ")");
                }
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to serialize Cobblemon Pokedex for " + player.getName().getString(), e);
        }

        return nbt;
    }

    private static String serializeGeneralPlayerData(GeneralPlayerData data) {
        try {
            PlayerDataJsonBackend backend = getGeneralJsonBackend();
            return backend.getGson().toJson(data, backend.getClassToken().getType());
        } catch (Exception e) {
            if (!generalJsonWarned) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to serialize GeneralPlayerData using Cobblemon JSON backend", e);
                generalJsonWarned = true;
            }
            return null;
        }
    }

    // Uses Cobblemon's PokedexManager CODEC (same format as the built-in NBT backend).
    private static boolean serializePokedex(PokedexManager pokedexData, NbtCompound nbt) {
        Codec<PokedexManager> codec = getPokedexCodec();
        if (codec == null) {
            if (!pokedexCodecWarned) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Pokedex CODEC not available for serialization");
                pokedexCodecWarned = true;
            }
            return false;
        }
        return encodeWithCodec(codec, pokedexData, nbt);
    }

    @SuppressWarnings("unchecked")
    private static <T> boolean encodeWithCodec(Codec<T> codec, Object obj, NbtCompound nbt) {
        try {
            DataResult<NbtElement> result = codec.encodeStart(NbtOps.INSTANCE, (T) obj);
            NbtElement element = result.getOrThrow();
            if (element instanceof NbtCompound compound) {
                nbt.copyFrom(compound);
                return true;
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Codec encoding failed", e);
        }
        return false;
    }

    public static void deserialize(ServerPlayerEntity player, NbtCompound nbt) {
        RegistryWrapper.WrapperLookup registries = player.getRegistryManager();
        net.minecraft.registry.DynamicRegistryManager registryManager = player.getServer().getRegistryManager();
        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Applying synchronization data for player: " + player.getName().getString() + " (keys=" + nbt.getKeys().size() + ")");
        
        if (nbt.contains("Inventory")) {
            player.getInventory().readNbt(nbt.getList("Inventory", 10));
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("  Synced Inventory");
        }
        
        if (nbt.contains("XpLevel")) {
            player.experienceLevel = nbt.getInt("XpLevel");
            player.experienceProgress = nbt.getFloat("XpP");
            player.totalExperience = nbt.getInt("XpTotal");
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("  Synced XP");
        }
        
        if (nbt.contains("EnderItems")) {
            player.getEnderChestInventory().readNbtList(nbt.getList("EnderItems", 10), registries);
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("  Synced Ender Chest");
        }
        
        if (nbt.contains("Health")) {
            player.setHealth(nbt.getFloat("Health"));
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("  Synced Health");
        }
        
        if (nbt.contains("FoodLevel")) {
            player.getHungerManager().setFoodLevel(nbt.getInt("FoodLevel"));
            player.getHungerManager().setSaturationLevel(nbt.getFloat("FoodSaturation"));
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("  Synced Hunger");
        }
        
        if (nbt.contains("ActiveEffects")) {
            player.clearStatusEffects();
            NbtList effectsList = nbt.getList("ActiveEffects", 10);
            for (int i = 0; i < effectsList.size(); i++) {
                StatusEffectInstance effect = StatusEffectInstance.fromNbt(effectsList.getCompound(i));
                if (effect != null) {
                    player.addStatusEffect(effect);
                }
            }
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("  Synced Status Effects");
        }

        // Cobblemon Data
        // Party
        try {
            if (nbt.contains("CobblemonParty")) {
                NbtCompound partyNbt = nbt.getCompound("CobblemonParty");
                PartySyncHandler.deserializeParty(player, partyNbt);
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("  Synced Cobblemon Party for " + player.getName().getString());
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to deserialize Cobblemon Party for " + player.getName().getString(), e);
        }

        // PC
        try {
            if (nbt.contains("CobblemonPC")) {
                PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
                NbtCompound pcNbt = nbt.getCompound("CobblemonPC");
                // Clear existing PC to avoid merging data
                clearPC(pc);
                pc.loadFromNBT(pcNbt, registryManager);
                try { pc.initialize(); } catch (Exception ignored) {}
                ensurePcStoreCoordinates(pc);
                ensurePcObserver(pc, player);
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("  Synced Cobblemon PC for " + player.getName().getString() + " (Keys: " + pcNbt.getKeys().size() + ")");
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to deserialize Cobblemon PC for " + player.getName().getString(), e);
        }

        // General Data
        try {
            if (nbt.contains("CobblemonGeneral", NbtElement.STRING_TYPE)) {
                GeneralPlayerData generalData = Cobblemon.INSTANCE.getPlayerDataManager().getGenericData(player);
                if (generalData != null) {
                    String generalJson = nbt.getString("CobblemonGeneral");
                    if (applyGeneralPlayerData(generalData, generalJson)) {
                        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("  Synced Cobblemon General Data for " + player.getName().getString() + " (Length: " + generalJson.length() + ")");
                    } else {
                        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.warn("Failed to apply Cobblemon General Data for " + player.getName().getString());
                    }
                }
            } else if (nbt.contains("CobblemonGeneral")) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.warn("Cobblemon General Data is in an unsupported format for " + player.getName().getString());
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to deserialize Cobblemon General Data for " + player.getName().getString(), e);
        }

        // Pokedex
        try {
            if (nbt.contains("CobblemonPokedex", NbtElement.COMPOUND_TYPE)) {
                PokedexManager pokedexData = Cobblemon.INSTANCE.getPlayerDataManager().getPokedexData(player);
                if (pokedexData != null) {
                    NbtCompound pokedexNbt = nbt.getCompound("CobblemonPokedex");
                    if (applyPokedex(pokedexData, pokedexNbt)) {
                        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("  Synced Cobblemon Pokedex for " + player.getName().getString() + " (Keys: " + pokedexNbt.getKeys().size() + ")");
                    } else {
                        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.warn("Failed to apply Cobblemon Pokedex for " + player.getName().getString());
                    }
                }
            } else if (nbt.contains("CobblemonPokedex")) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.warn("Cobblemon Pokedex data is in an unsupported format for " + player.getName().getString());
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to deserialize Cobblemon Pokedex for " + player.getName().getString(), e);
        }

        // Sync to client
        syncToClient(player);
    }

    private static void clearPC(PCStore pc) {
        if (pc == null) return;
        try {
            for (PCBox box : pc.getBoxes()) {
                java.util.Set<Integer> slots = new java.util.HashSet<>(box.getNonEmptySlots().keySet());
                for (int slot : slots) {
                    box.set(slot, null);
                }
            }
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Error clearing PC", e);
        }
    }

    private static boolean applyGeneralPlayerData(GeneralPlayerData target, String json) {
        if (json == null || json.isBlank()) return false;
        try {
            PlayerDataJsonBackend backend = getGeneralJsonBackend();
            GeneralPlayerData decoded = backend.getGson().fromJson(json, backend.getClassToken().getType());
            if (decoded == null) return false;

            target.setStarterPrompted(decoded.getStarterPrompted());
            target.setStarterLocked(decoded.getStarterLocked());
            target.setStarterSelected(decoded.getStarterSelected());
            target.setStarterUUID(decoded.getStarterUUID());
            if (decoded.getKeyItems() != null) {
                target.setKeyItems(new java.util.HashSet<>(decoded.getKeyItems()));
            }
            target.setBattleTheme(decoded.getBattleTheme());

            if (target.getExtraData() != null) {
                target.getExtraData().clear();
                if (decoded.getExtraData() != null) {
                    target.getExtraData().putAll(decoded.getExtraData());
                }
            }
            if (decoded.getAdvancementData() != null) {
                target.setAdvancementData(decoded.getAdvancementData());
            }
            target.initialize();
            return true;
        } catch (Exception e) {
            if (!generalJsonWarned) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Failed to deserialize GeneralPlayerData using Cobblemon JSON backend", e);
                generalJsonWarned = true;
            }
            return false;
        }
    }

    private static boolean applyPokedex(PokedexManager pokedexData, NbtCompound nbt) {
        Codec<PokedexManager> codec = getPokedexCodec();
        if (codec == null) {
            if (!pokedexCodecWarned) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Pokedex CODEC not available for deserialization");
                pokedexCodecWarned = true;
            }
            return false;
        }
        PokedexManager decoded = decodeWithCodecHelper(codec, nbt);
        if (decoded == null) return false;
        pokedexData.getSpeciesRecords().clear();
        pokedexData.getSpeciesRecords().putAll(decoded.getSpeciesRecords());
        pokedexData.initialize();
        pokedexData.markDirty();
        return true;
    }

    private static Codec<PokedexManager> getPokedexCodec() {
        try {
            java.lang.reflect.Field field = PokedexManager.class.getDeclaredField("CODEC");
            field.setAccessible(true);
            Object codecObj = field.get(null);
            if (codecObj instanceof Codec<?> codec) {
                @SuppressWarnings("unchecked")
                Codec<PokedexManager> typed = (Codec<PokedexManager>) codec;
                return typed;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> T decodeWithCodecHelper(Codec<T> codec, NbtCompound nbt) {
        try {
            DataResult<T> result = codec.parse(NbtOps.INSTANCE, nbt);
            return result.getOrThrow();
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Codec parse failed", e);
            return null;
        }
    }

    public static void syncToClient(ServerPlayerEntity player) {
        try {
            // Minecraft Inventory
            if (player.currentScreenHandler != null) {
                player.currentScreenHandler.sendContentUpdates();
            }
            if (player.playerScreenHandler != null) {
                player.playerScreenHandler.sendContentUpdates();
            }
            
            // Experience
            player.addExperience(0); 

            // Cobblemon Data
            // Party
            PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
            ensurePartyObserver(party, player);
            party.sendTo(player);

            // PC
            PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
            ensurePcObserver(pc, player);
            pc.sendTo(player);

            // General Data
            Object generalData = Cobblemon.INSTANCE.getPlayerDataManager().getGenericData(player);
            if (generalData instanceof GeneralPlayerData gpd) {
                gpd.sendToPlayer(player);
            } else {
                invokeBestSyncMethod(generalData, player);
            }

            // Pokedex
            Object pokedex = Cobblemon.INSTANCE.getPlayerDataManager().getPokedexData(player);
            invokeBestSyncMethod(pokedex, player);

        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Error during player data client sync", e);
        }
    }

    private static void invokeBestSyncMethod(Object obj, ServerPlayerEntity player) {
        if (obj == null) return;
        try {
            // Try sendTo(ServerPlayerEntity)
            try {
                Method m = obj.getClass().getMethod("sendTo", ServerPlayerEntity.class);
                m.invoke(obj, player);
                return;
            } catch (NoSuchMethodException ignored) {}

            // Try sendToPlayer(ServerPlayerEntity)
            try {
                Method m = obj.getClass().getMethod("sendToPlayer", ServerPlayerEntity.class);
                m.invoke(obj, player);
                return;
            } catch (NoSuchMethodException ignored) {}

            // Try sync()
            try {
                Method m = obj.getClass().getMethod("sync");
                m.invoke(obj);
                return;
            } catch (NoSuchMethodException ignored) {}
            
            // Try syncToPlayer(ServerPlayerEntity)
            try {
                Method m = obj.getClass().getMethod("syncToPlayer", ServerPlayerEntity.class);
                m.invoke(obj, player);
                return;
            } catch (NoSuchMethodException ignored) {}

        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.warn("Could not find sync method for " + obj.getClass().getName());
        }
    }

    static void ensurePartyObserver(PlayerPartyStore party, ServerPlayerEntity player) {
        if (party == null || player == null) return;
        try {
            java.util.List<java.util.UUID> observers = party.getObserverUUIDs();
            java.util.UUID playerId = player.getUuid();
            if (!observers.contains(playerId)) {
                // Create a new mutable list if it's not one, or just add if it is.
                // Cobblemon usually uses ArrayList internally for this.
                try {
                    observers.add(playerId);
                } catch (UnsupportedOperationException e) {
                    java.util.List<java.util.UUID> newObservers = new java.util.ArrayList<>(observers);
                    newObservers.add(playerId);
                    party.setObserverUUIDs(newObservers);
                }
            }
        } catch (Exception ignored) {
        }
    }

    static void ensurePcObserver(PCStore pc, ServerPlayerEntity player) {
        if (pc == null || player == null) return;
        try {
            pc.addObserver(player);
        } catch (Exception ignored) {
        }
    }

    static void ensurePartyStoreCoordinates(PlayerPartyStore party) {
        if (party == null) return;
        try {
            int size = party.size();
            for (int i = 0; i < size; i++) {
                Pokemon pokemon = party.get(i);
                if (pokemon == null) continue;
                pokemon.getStoreCoordinates().set(new StoreCoordinates<>(party, new PartyPosition(i)));
            }
        } catch (Exception ignored) {
        }
    }

    static void ensurePcStoreCoordinates(PCStore pc) {
        if (pc == null) return;
        try {
            for (PCBox box : pc.getBoxes()) {
                int boxNumber = box.getBoxNumber();
                for (java.util.Map.Entry<Integer, Pokemon> entry : box.getNonEmptySlots().entrySet()) {
                    Pokemon pokemon = entry.getValue();
                    if (pokemon == null) continue;
                    pokemon.getStoreCoordinates().set(new StoreCoordinates<>(pc, new PCPosition(boxNumber, entry.getKey())));
                }
            }
        } catch (Exception ignored) {
        }
    }
}
