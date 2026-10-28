package com.roicheese.eggandcheese.registry;

import com.roicheese.eggandcheese.block.AdminCollectibleBlock;
import com.roicheese.eggandcheese.block.CollectibleDecoBlock;
import com.roicheese.eggandcheese.block.PedestalBlock;
import com.roicheese.eggandcheese.block.PedestalExtensionBlock;
import com.roicheese.eggandcheese.block.PedestalVariant;
import com.roicheese.eggandcheese.dna.CollectibleType;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlocks {
    // ── Collectible blocks ──
    public static final AdminCollectibleBlock ADMIN_EGG = register("admin_egg",
        new AdminCollectibleBlock(AbstractBlock.Settings.create().noCollision().nonOpaque(), CollectibleType.EGG));
    public static final CollectibleDecoBlock EGG_DECO = register("egg_deco",
        new CollectibleDecoBlock(AbstractBlock.Settings.create().noCollision().nonOpaque().strength(0.5f), CollectibleType.EGG));

    // ── Pedestal extension (invisible collision proxy for tall pillars) ──
    public static final PedestalExtensionBlock PEDESTAL_EXTENSION = register("pedestal_extension",
        new PedestalExtensionBlock(AbstractBlock.Settings.create()
            .strength(-1.0f, 3600000.0f).nonOpaque()));

    // ── Pedestal variants ──
    public static final PedestalBlock PEDESTAL_STONE = register("pedestal_stone",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.STONE_BRICKS).nonOpaque(), PedestalVariant.STONE));
    public static final PedestalBlock PEDESTAL_OAK = register("pedestal_oak",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.OAK_PLANKS).nonOpaque(), PedestalVariant.OAK));
    public static final PedestalBlock PEDESTAL_QUARTZ = register("pedestal_quartz",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.QUARTZ_BLOCK).nonOpaque(), PedestalVariant.QUARTZ));
    public static final PedestalBlock PEDESTAL_IRON = register("pedestal_iron",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.IRON_BLOCK).nonOpaque(), PedestalVariant.IRON));
    public static final PedestalBlock PEDESTAL_GOLD = register("pedestal_gold",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.GOLD_BLOCK).nonOpaque(), PedestalVariant.GOLD));
    public static final PedestalBlock PEDESTAL_DIAMOND = register("pedestal_diamond",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.DIAMOND_BLOCK).nonOpaque(), PedestalVariant.DIAMOND));
    public static final PedestalBlock PEDESTAL_NETHERITE = register("pedestal_netherite",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.NETHERITE_BLOCK).nonOpaque(), PedestalVariant.NETHERITE));
    public static final PedestalBlock PEDESTAL_COPPER = register("pedestal_copper",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.COPPER_BLOCK).nonOpaque(), PedestalVariant.COPPER));
    public static final PedestalBlock PEDESTAL_AMETHYST = register("pedestal_amethyst",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.AMETHYST_BLOCK).nonOpaque(), PedestalVariant.AMETHYST));
    public static final PedestalBlock PEDESTAL_EMERALD = register("pedestal_emerald",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.EMERALD_BLOCK).nonOpaque(), PedestalVariant.EMERALD));
    public static final PedestalBlock PEDESTAL_DARK_OAK = register("pedestal_dark_oak",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.DARK_OAK_PLANKS).nonOpaque(), PedestalVariant.DARK_OAK));
    public static final PedestalBlock PEDESTAL_CHERRY = register("pedestal_cherry",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.CHERRY_PLANKS).nonOpaque(), PedestalVariant.CHERRY));
    public static final PedestalBlock PEDESTAL_BIRCH = register("pedestal_birch",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.BIRCH_PLANKS).nonOpaque(), PedestalVariant.BIRCH));
    public static final PedestalBlock PEDESTAL_DEEPSLATE = register("pedestal_deepslate",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.DEEPSLATE_BRICKS).nonOpaque(), PedestalVariant.DEEPSLATE));
    public static final PedestalBlock PEDESTAL_BLACKSTONE = register("pedestal_blackstone",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.POLISHED_BLACKSTONE_BRICKS).nonOpaque(), PedestalVariant.BLACKSTONE));
    public static final PedestalBlock PEDESTAL_PRISMARINE = register("pedestal_prismarine",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.PRISMARINE_BRICKS).nonOpaque(), PedestalVariant.PRISMARINE));
    public static final PedestalBlock PEDESTAL_SPRUCE = register("pedestal_spruce",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.SPRUCE_PLANKS).nonOpaque(), PedestalVariant.SPRUCE));
    public static final PedestalBlock PEDESTAL_JUNGLE = register("pedestal_jungle",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.JUNGLE_PLANKS).nonOpaque(), PedestalVariant.JUNGLE));
    public static final PedestalBlock PEDESTAL_ACACIA = register("pedestal_acacia",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.ACACIA_PLANKS).nonOpaque(), PedestalVariant.ACACIA));
    public static final PedestalBlock PEDESTAL_MANGROVE = register("pedestal_mangrove",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.MANGROVE_PLANKS).nonOpaque(), PedestalVariant.MANGROVE));
    public static final PedestalBlock PEDESTAL_BAMBOO = register("pedestal_bamboo",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.BAMBOO_PLANKS).nonOpaque(), PedestalVariant.BAMBOO));
    public static final PedestalBlock PEDESTAL_CRIMSON = register("pedestal_crimson",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.CRIMSON_PLANKS).nonOpaque(), PedestalVariant.CRIMSON));
    public static final PedestalBlock PEDESTAL_WARPED = register("pedestal_warped",
        new PedestalBlock(AbstractBlock.Settings.copy(Blocks.WARPED_PLANKS).nonOpaque(), PedestalVariant.WARPED));

    private static <T extends Block> T register(String name, T block) {
        return Registry.register(Registries.BLOCK, Identifier.of("eggandcheese", name), block);
    }
    public static void init() {}
}
