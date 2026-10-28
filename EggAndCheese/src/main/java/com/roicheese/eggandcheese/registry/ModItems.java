package com.roicheese.eggandcheese.registry;

import com.roicheese.eggandcheese.dna.CollectibleType;
import com.roicheese.eggandcheese.item.AdminCollectibleItem;
import com.roicheese.eggandcheese.item.CollectibleItem;
import com.roicheese.eggandcheese.item.PedestalItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModItems {
    public static final AdminCollectibleItem ADMIN_EGG = register("admin_egg",
        new AdminCollectibleItem(CollectibleType.EGG, new Item.Settings()));
    public static final CollectibleItem COLLECTIBLE_EGG = register("collectible_egg",
        new CollectibleItem(CollectibleType.EGG, new Item.Settings().maxCount(1)));

    public static final PedestalItem PEDESTAL_STONE     = register("pedestal_stone",     new PedestalItem(ModBlocks.PEDESTAL_STONE,     new Item.Settings()));
    public static final PedestalItem PEDESTAL_OAK       = register("pedestal_oak",       new PedestalItem(ModBlocks.PEDESTAL_OAK,       new Item.Settings()));
    public static final PedestalItem PEDESTAL_QUARTZ    = register("pedestal_quartz",    new PedestalItem(ModBlocks.PEDESTAL_QUARTZ,    new Item.Settings()));
    public static final PedestalItem PEDESTAL_IRON      = register("pedestal_iron",      new PedestalItem(ModBlocks.PEDESTAL_IRON,      new Item.Settings()));
    public static final PedestalItem PEDESTAL_GOLD      = register("pedestal_gold",      new PedestalItem(ModBlocks.PEDESTAL_GOLD,      new Item.Settings()));
    public static final PedestalItem PEDESTAL_DIAMOND   = register("pedestal_diamond",   new PedestalItem(ModBlocks.PEDESTAL_DIAMOND,   new Item.Settings()));
    public static final PedestalItem PEDESTAL_NETHERITE = register("pedestal_netherite", new PedestalItem(ModBlocks.PEDESTAL_NETHERITE, new Item.Settings()));
    public static final PedestalItem PEDESTAL_COPPER    = register("pedestal_copper",    new PedestalItem(ModBlocks.PEDESTAL_COPPER,    new Item.Settings()));
    public static final PedestalItem PEDESTAL_AMETHYST  = register("pedestal_amethyst",  new PedestalItem(ModBlocks.PEDESTAL_AMETHYST,  new Item.Settings()));
    public static final PedestalItem PEDESTAL_EMERALD   = register("pedestal_emerald",   new PedestalItem(ModBlocks.PEDESTAL_EMERALD,   new Item.Settings()));
    public static final PedestalItem PEDESTAL_DARK_OAK  = register("pedestal_dark_oak",  new PedestalItem(ModBlocks.PEDESTAL_DARK_OAK,  new Item.Settings()));
    public static final PedestalItem PEDESTAL_CHERRY    = register("pedestal_cherry",    new PedestalItem(ModBlocks.PEDESTAL_CHERRY,    new Item.Settings()));
    public static final PedestalItem PEDESTAL_BIRCH     = register("pedestal_birch",     new PedestalItem(ModBlocks.PEDESTAL_BIRCH,     new Item.Settings()));
    public static final PedestalItem PEDESTAL_DEEPSLATE = register("pedestal_deepslate", new PedestalItem(ModBlocks.PEDESTAL_DEEPSLATE, new Item.Settings()));
    public static final PedestalItem PEDESTAL_BLACKSTONE= register("pedestal_blackstone",new PedestalItem(ModBlocks.PEDESTAL_BLACKSTONE,new Item.Settings()));
    public static final PedestalItem PEDESTAL_PRISMARINE= register("pedestal_prismarine",new PedestalItem(ModBlocks.PEDESTAL_PRISMARINE,new Item.Settings()));
    public static final PedestalItem PEDESTAL_SPRUCE    = register("pedestal_spruce",    new PedestalItem(ModBlocks.PEDESTAL_SPRUCE,    new Item.Settings()));
    public static final PedestalItem PEDESTAL_JUNGLE    = register("pedestal_jungle",    new PedestalItem(ModBlocks.PEDESTAL_JUNGLE,    new Item.Settings()));
    public static final PedestalItem PEDESTAL_ACACIA    = register("pedestal_acacia",    new PedestalItem(ModBlocks.PEDESTAL_ACACIA,    new Item.Settings()));
    public static final PedestalItem PEDESTAL_MANGROVE  = register("pedestal_mangrove",  new PedestalItem(ModBlocks.PEDESTAL_MANGROVE,  new Item.Settings()));
    public static final PedestalItem PEDESTAL_BAMBOO    = register("pedestal_bamboo",    new PedestalItem(ModBlocks.PEDESTAL_BAMBOO,    new Item.Settings()));
    public static final PedestalItem PEDESTAL_CRIMSON   = register("pedestal_crimson",   new PedestalItem(ModBlocks.PEDESTAL_CRIMSON,   new Item.Settings()));
    public static final PedestalItem PEDESTAL_WARPED    = register("pedestal_warped",    new PedestalItem(ModBlocks.PEDESTAL_WARPED,    new Item.Settings()));

    private static <T extends Item> T register(String name, T item) {
        return Registry.register(Registries.ITEM, Identifier.of("eggandcheese", name), item);
    }
    public static void init() {}
}
