package com.cheesecompat.block;

import com.cheesecompat.CheeseCompatMod;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Registers the CobbleSafari portal frame and portal blocks.
 */
public class CobbleSafariBlocks {

    public static Block COBBLESAFARI_PORTAL_FRAME;
    public static Block COBBLESAFARI_PORTAL;

    public static void register() {
        // Portal frame — full cube block, same properties as academy:safari_portal_frame
        COBBLESAFARI_PORTAL_FRAME = new Block(AbstractBlock.Settings.create()
            .mapColor(MapColor.DARK_GREEN)
            .strength(2.0f, 6.0f)
            .sounds(BlockSoundGroup.STONE)
            .requiresTool());
        Registry.register(Registries.BLOCK, Identifier.of("cheesecompat", "cobblesafari_portal_frame"),
            COBBLESAFARI_PORTAL_FRAME);
        Registry.register(Registries.ITEM, Identifier.of("cheesecompat", "cobblesafari_portal_frame"),
            new BlockItem(COBBLESAFARI_PORTAL_FRAME, new Item.Settings()));

        // Portal block — non-solid, transparent, animated texture
        COBBLESAFARI_PORTAL = new CobbleSafariPortalBlock(AbstractBlock.Settings.create()
            .noCollision()
            .strength(-1.0f) // unbreakable by hand
            .luminance(state -> 11) // slight glow like nether portal
            .sounds(BlockSoundGroup.GLASS));
        Registry.register(Registries.BLOCK, Identifier.of("cheesecompat", "cobblesafari_portal"),
            COBBLESAFARI_PORTAL);
        // No BlockItem for portal — it's not placeable by hand

        // Register flint & steel activation callback
        registerPortalActivation();

        CheeseCompatMod.LOGGER.info("[CheeseCompat] Registered CobbleSafari portal frame and portal blocks");
    }

    /**
     * When a player uses flint & steel on air inside a frame of our portal blocks,
     * check if a valid portal frame exists and create the portal.
     */
    private static void registerPortalActivation() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;

            // Only trigger with flint & steel
            if (!player.getStackInHand(hand).isOf(Items.FLINT_AND_STEEL)) return ActionResult.PASS;

            BlockPos hitPos = hitResult.getBlockPos();
            BlockState hitState = world.getBlockState(hitPos);

            // If clicked on a portal frame block, check adjacent air
            BlockPos checkPos;
            if (hitState.isOf(COBBLESAFARI_PORTAL_FRAME)) {
                checkPos = hitPos.offset(hitResult.getSide());
            } else {
                return ActionResult.PASS;
            }

            // The target position should be air (or replaceable)
            if (!world.getBlockState(checkPos).isAir()) return ActionResult.PASS;

            // Try to find a valid portal frame
            var portal = CobbleSafariPortalHelper.getNewPortal(world, checkPos);
            if (portal.isPresent()) {
                portal.get().createPortal();
                // Play fire ignite sound
                world.playSound(null, checkPos, net.minecraft.sound.SoundEvents.ITEM_FLINTANDSTEEL_USE,
                    net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
                CheeseCompatMod.LOGGER.info("[CheeseCompat] CobbleSafari portal created at {}", checkPos);
                return ActionResult.SUCCESS;
            }

            return ActionResult.PASS;
        });
    }
}
