package com.sebastian.cobblemondungeons.util;

import com.sebastian.cobblemondungeons.dungeon.DungeonManager;
import com.sebastian.cobblemondungeons.gui.DungeonSelectionGui;
import com.sebastian.cobblemondungeons.config.DungeonConfig;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.util.Hand;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import com.sebastian.cobblemondungeons.dungeon.DungeonInstance;
import com.sebastian.cobblemondungeons.CobblemonDungeons;
import com.sebastian.cobblemondungeons.config.ConfigLoader;
import com.sebastian.cobblemondungeons.config.DungeonTicketConfig;
import com.sebastian.cobblemondungeons.block.DungeonChestBlock;
import com.sebastian.cobblemondungeons.block.PortalBlock;
import com.sebastian.cobblemondungeons.block.StakeBlock;
import com.sebastian.cobblemondungeons.block.StakeSpawnerBlock;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;

public class InteractionHandler {
    public static void init() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient && entity.getCommandTags().contains("DungeonTrainer")) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {
                String name = "Unnamed";
                if (entity.getCustomName() != null) {
                    name = entity.getCustomName().getString();
                } else if (entity.hasCustomName()) {
                    name = entity.getDisplayName().getString();
                }
                
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("[DEBUG] Interacted with entity: " + name + " (Type: " + entity.getType().toString() + ")");
                
                if (entity.getCommandTags().contains("DungeonTrainer") && entity instanceof net.minecraft.entity.LivingEntity living) {
                    if (DungeonManager.handleTrainerInteract(serverPlayer, living)) {
                        return ActionResult.SUCCESS;
                    }
                }

                String lowerName = name.toLowerCase();
                if (lowerName.contains("dungeon master")) {
                    com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("[DEBUG] Dungeon Master detected! Opening GUI for " + serverPlayer.getName().getString());
                    DungeonSelectionGui.open(serverPlayer);
                    return ActionResult.SUCCESS;
                }
                
                // Key item check
                if (lowerName.contains("key")) {
                    var instance = DungeonManager.getInstance(serverPlayer.getUuid());
                    if (instance != null) {
                        DungeonManager.handleKeyCollected(serverPlayer, name);
                        entity.discard();
                    }
                    return ActionResult.SUCCESS;
                }

                // Gem check
                if (lowerName.contains("gem")) {
                    var instance = DungeonManager.getInstance(serverPlayer.getUuid());
                    if (instance != null) {
                        DungeonManager.handleGemFound(serverPlayer);
                        entity.discard();
                    }
                    return ActionResult.SUCCESS;
                }

                // Stake check
                if (lowerName.contains("stake")) {
                    var instance = DungeonManager.getInstance(serverPlayer.getUuid());
                    if (instance != null) {
                        DungeonManager.handleStakeFound(serverPlayer);
                        entity.discard();
                    }
                    return ActionResult.SUCCESS;
                }

                // Boss Altar check
                if (lowerName.contains("boss altar")) {
                    var instance = DungeonManager.getInstance(serverPlayer.getUuid());
                    if (instance != null) {
                        if (instance.canAccessBoss()) {
                            if (!instance.isBossDefeated()) {
                                if (!instance.isBossSpawned()) {
                                    DungeonManager.spawnBoss(instance, serverPlayer.getServer());
                                } else {
                                    serverPlayer.sendMessage(Text.translatable("cobblemondungeons.interaction.boss_already_appeared"));
                                }
                            } else {
                                serverPlayer.sendMessage(Text.translatable("cobblemondungeons.interaction.boss_already_defeated"));
                            }
                        } else {
                            serverPlayer.sendMessage(Text.translatable("cobblemondungeons.interaction.objectives_needed"));
                        }
                    }
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient && hand == net.minecraft.util.Hand.MAIN_HAND && player instanceof ServerPlayerEntity serverPlayer) {
                BlockPos pos = hitResult.getBlockPos();
                net.minecraft.block.BlockState state = world.getBlockState(pos);
                net.minecraft.block.Block block = state.getBlock();

                if (block instanceof DungeonChestBlock) {
                    return handleDungeonChestInteract(serverPlayer, world, pos, block);
                } else if (block instanceof PortalBlock portalBlock) {
                    return portalBlock.onUse(state, world, pos, serverPlayer, hitResult);
                } else if (block instanceof StakeBlock stakeBlock) {
                    return stakeBlock.onUse(state, world, pos, serverPlayer, hitResult);
                } else if (block instanceof StakeSpawnerBlock stakeSpawnerBlock) {
                    return stakeSpawnerBlock.onUse(state, world, pos, serverPlayer, hitResult);
                }
            }
            return ActionResult.PASS;
        });
    }

    public static ActionResult handleDungeonChestInteract(PlayerEntity player, World world, BlockPos pos, Block block) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.SUCCESS;
        
        var instance = DungeonManager.getInstance(serverPlayer.getUuid());
        if (instance == null) {
            serverPlayer.sendMessage(Text.translatable("cobblemondungeons.interaction.chest_requires_dungeon").formatted(Formatting.RED));
            return ActionResult.SUCCESS;
        }

        Integer lootPoolId = com.sebastian.cobblemondungeons.CobblemonDungeons.DUNGEON_CHEST_BLOCK_IDS.get(block);
        if (lootPoolId == null) return ActionResult.PASS;

        SimpleInventory chestInv = instance.getChestInventory(pos);
        if (chestInv == null) {
            // Ensure gems are assigned before creating inventory
            if (!instance.areGemsAssigned()) {
                DungeonManager.discoverChests(serverPlayer.getServerWorld(), instance.getConfig(), instance);
                instance.assignGems();
            }

            chestInv = new SimpleInventory(27);
            
            int dungeonTier = instance.getTier();
            DungeonConfig.LootPoolConfig pool = null;
            
            // First try exact tier match
            for (DungeonConfig.LootPoolConfig p : instance.getConfig().lootPools) {
                if (p.id == lootPoolId && p.tier == dungeonTier) {
                    pool = p;
                    break;
                }
            }
            
            // Fallback to default (tier 0) if no exact match
            if (pool == null) {
                for (DungeonConfig.LootPoolConfig p : instance.getConfig().lootPools) {
                    if (p.id == lootPoolId && p.tier == 0) {
                        pool = p;
                        break;
                    }
                }
            }

            if (pool != null && !pool.items.isEmpty()) {
                generateLootIntoInventory(chestInv, pool);
            }
            
            // Add Gem if assigned to this chest
            if (instance.hasGem(pos)) {
                ItemStack gemStack = new ItemStack(com.sebastian.cobblemondungeons.CobblemonDungeons.GEM);
                // Try to find a random empty slot for the gem
                java.util.Random random = new java.util.Random();
                int slot = random.nextInt(chestInv.size());
                if (chestInv.getStack(slot).isEmpty()) {
                    chestInv.setStack(slot, gemStack);
                } else {
                    chestInv.addStack(gemStack);
                }
            }

            instance.setChestInventory(pos, chestInv);
        }

        final SimpleInventory finalInv = chestInv;
        serverPlayer.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, playerInv, p) -> {
            return GenericContainerScreenHandler.createGeneric9x3(syncId, playerInv, finalInv);
        }, Text.translatable("cobblemondungeons.interaction.dungeon_chest")));
        
        world.playSound(null, pos, net.minecraft.sound.SoundEvents.BLOCK_CHEST_OPEN, net.minecraft.sound.SoundCategory.BLOCKS, 0.5f, 1.0f);
        
        return ActionResult.SUCCESS;
    }

    private static void generateLootIntoInventory(SimpleInventory inventory, DungeonConfig.LootPoolConfig pool) {
        java.util.Random random = new java.util.Random();
        int totalWeight = pool.items.stream().mapToInt(i -> i.weight).sum();
        
        if (totalWeight <= 0) return;

        for (int r = 0; r < pool.rolls; r++) {
            int target = random.nextInt(totalWeight);
            for (DungeonConfig.LootItemConfig itemConfig : pool.items) {
                target -= itemConfig.weight;
                if (target < 0) {
                    ItemStack stack = createLootItemStack(itemConfig, random);
                    if (!stack.isEmpty()) {
                        // Try to find a random slot
                        int slot = random.nextInt(inventory.size());
                        if (inventory.getStack(slot).isEmpty()) {
                            inventory.setStack(slot, stack);
                        } else {
                            inventory.addStack(stack);
                        }
                    }
                    break;
                }
            }
        }
    }

    public static ItemStack createLootItemStack(DungeonConfig.LootItemConfig config, java.util.Random random) {
        return createItemStack(config.item, config.customName, config.min, config.max, config.nbt, random);
    }

    public static ItemStack createCompletionRewardStack(DungeonConfig.CompletionRewardConfig config, java.util.Random random) {
        return createItemStack(config.item, config.customName, config.min, config.max, config.nbt, random);
    }

    private static ItemStack createItemStack(String itemIdStr, String customName, int min, int max, String nbtStr, java.util.Random random) {
        if (itemIdStr == null || itemIdStr.isEmpty()) return ItemStack.EMPTY;
        
        Identifier itemId = Identifier.tryParse(itemIdStr);
        Item item = (itemId != null) ? Registries.ITEM.get(itemId) : Items.AIR;

        int count = min;
        if (max > min) {
            count += random.nextInt(max - min + 1);
        }

        if (item == Items.AIR) {
            // Try to match against ticket names from config
            for (DungeonTicketConfig.TicketConfig ticketCfg : ConfigLoader.getTicketConfig().tickets) {
                if (ticketCfg.itemName.equalsIgnoreCase(itemIdStr) ||
                        ticketCfg.itemName.replace("\u00A7", "&").equalsIgnoreCase(itemIdStr)) {

                    return com.sebastian.cobblemondungeons.item.DungeonTicketItem.createStack(ticketCfg, count);
                }
            }
            CobblemonDungeons.LOGGER.warn("Unknown loot item ID or ticket name: " + itemIdStr);
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(item, count);

        // Apply custom name if provided
        if (customName != null && !customName.isEmpty()) {
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(customName).styled(s -> s.withItalic(false)));
        }

        if (nbtStr != null && !nbtStr.isEmpty()) {
            try {
                NbtCompound nbt = StringNbtReader.parse(nbtStr);
                stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
            } catch (Exception e) {
                CobblemonDungeons.LOGGER.error("Failed to parse NBT for loot item " + itemIdStr, e);
            }
        }
        return stack;
    }
}
