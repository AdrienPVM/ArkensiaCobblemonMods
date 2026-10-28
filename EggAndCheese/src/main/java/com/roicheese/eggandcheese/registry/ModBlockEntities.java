package com.roicheese.eggandcheese.registry;

import com.roicheese.eggandcheese.blockentity.AdminCollectibleBlockEntity;
import com.roicheese.eggandcheese.blockentity.CollectibleDecoBlockEntity;
import com.roicheese.eggandcheese.blockentity.PedestalBlockEntity;
import com.roicheese.eggandcheese.dna.CollectibleType;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlockEntities {
    public static BlockEntityType<AdminCollectibleBlockEntity> ADMIN_COLLECTIBLE;
    public static BlockEntityType<CollectibleDecoBlockEntity>  COLLECTIBLE_DECO;
    public static BlockEntityType<PedestalBlockEntity>         PEDESTAL;

    public static void init() {
        ADMIN_COLLECTIBLE = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of("eggandcheese", "admin_collectible"),
            BlockEntityType.Builder.create(
                (pos, state) -> new AdminCollectibleBlockEntity(pos, state, CollectibleType.EGG),
                ModBlocks.ADMIN_EGG
            ).build(null)
        );
        COLLECTIBLE_DECO = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of("eggandcheese", "collectible_deco"),
            BlockEntityType.Builder.create(
                (pos, state) -> new CollectibleDecoBlockEntity(pos, state, CollectibleType.EGG),
                ModBlocks.EGG_DECO
            ).build(null)
        );
        // One block entity type for all pedestal variants
        PEDESTAL = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of("eggandcheese", "pedestal"),
            BlockEntityType.Builder.create(
                PedestalBlockEntity::new,
                ModBlocks.PEDESTAL_STONE,
                ModBlocks.PEDESTAL_OAK,
                ModBlocks.PEDESTAL_QUARTZ,
                ModBlocks.PEDESTAL_IRON,
                ModBlocks.PEDESTAL_GOLD,
                ModBlocks.PEDESTAL_DIAMOND,
                ModBlocks.PEDESTAL_NETHERITE,
                ModBlocks.PEDESTAL_COPPER,
                ModBlocks.PEDESTAL_AMETHYST,
                ModBlocks.PEDESTAL_EMERALD,
                ModBlocks.PEDESTAL_DARK_OAK,
                ModBlocks.PEDESTAL_CHERRY,
                ModBlocks.PEDESTAL_BIRCH,
                ModBlocks.PEDESTAL_DEEPSLATE,
                ModBlocks.PEDESTAL_BLACKSTONE,
                ModBlocks.PEDESTAL_PRISMARINE,
                ModBlocks.PEDESTAL_SPRUCE,
                ModBlocks.PEDESTAL_JUNGLE,
                ModBlocks.PEDESTAL_ACACIA,
                ModBlocks.PEDESTAL_MANGROVE,
                ModBlocks.PEDESTAL_BAMBOO,
                ModBlocks.PEDESTAL_CRIMSON,
                ModBlocks.PEDESTAL_WARPED
            ).build(null)
        );
    }
}
