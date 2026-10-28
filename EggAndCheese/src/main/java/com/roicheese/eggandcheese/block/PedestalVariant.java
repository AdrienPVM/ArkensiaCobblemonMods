package com.roicheese.eggandcheese.block;

import net.minecraft.util.Identifier;

public enum PedestalVariant {
    STONE      ("stone",      "stone", "minecraft:block/stone_bricks"),
    OAK        ("oak",        "wood",  "minecraft:block/oak_planks"),
    QUARTZ     ("quartz",     "stone", "minecraft:block/quartz_block_side"),
    IRON       ("iron",       "metal", "minecraft:block/iron_block"),
    GOLD       ("gold",       "metal", "minecraft:block/gold_block"),
    DIAMOND    ("diamond",    "metal", "minecraft:block/diamond_block"),
    NETHERITE  ("netherite",  "metal", "minecraft:block/netherite_block"),
    COPPER     ("copper",     "metal", "minecraft:block/copper_block"),
    AMETHYST   ("amethyst",   "amethyst", "minecraft:block/amethyst_block"),
    EMERALD    ("emerald",    "metal", "minecraft:block/emerald_block"),
    DARK_OAK   ("dark_oak",   "wood",  "minecraft:block/dark_oak_planks"),
    CHERRY     ("cherry",     "wood",  "minecraft:block/cherry_planks"),
    BIRCH      ("birch",      "wood",  "minecraft:block/birch_planks"),
    DEEPSLATE  ("deepslate",  "stone", "minecraft:block/deepslate_bricks"),
    BLACKSTONE ("blackstone", "stone", "minecraft:block/polished_blackstone_bricks"),
    PRISMARINE ("prismarine", "stone", "minecraft:block/prismarine_bricks"),
    SPRUCE     ("spruce",     "wood",  "minecraft:block/spruce_planks"),
    JUNGLE     ("jungle",     "wood",  "minecraft:block/jungle_planks"),
    ACACIA     ("acacia",     "wood",  "minecraft:block/acacia_planks"),
    MANGROVE   ("mangrove",   "wood",  "minecraft:block/mangrove_planks"),
    BAMBOO     ("bamboo",     "wood",  "minecraft:block/bamboo_planks"),
    CRIMSON    ("crimson",    "wood",  "minecraft:block/crimson_planks"),
    WARPED     ("warped",     "wood",  "minecraft:block/warped_planks");

    private final String id;
    private final String soundGroup;
    private final Identifier textureId;

    PedestalVariant(String id, String soundGroup, String texture) {
        this.id = id;
        this.soundGroup = soundGroup;
        this.textureId = Identifier.of(texture.split(":")[0], texture.split(":")[1]);
    }

    public String getId() { return id; }
    public Identifier getTextureId() { return textureId; }
}
