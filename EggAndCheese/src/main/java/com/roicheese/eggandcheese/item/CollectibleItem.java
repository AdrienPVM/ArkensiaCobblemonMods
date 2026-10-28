package com.roicheese.eggandcheese.item;

import com.roicheese.eggandcheese.blockentity.CollectibleDecoBlockEntity;
import com.roicheese.eggandcheese.dna.CollectibleDNA;
import com.roicheese.eggandcheese.dna.CollectibleType;
import com.roicheese.eggandcheese.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.item.tooltip.TooltipType;

import java.util.List;

public class CollectibleItem extends BlockItem {
    private final CollectibleType collectibleType;

    public CollectibleItem(CollectibleType type, Settings settings) {
        super(getBlock(type), settings.maxCount(1));
        this.collectibleType = type;
    }

    private static Block getBlock(CollectibleType type) {
        return ModBlocks.EGG_DECO;
    }

    @Override
    public void appendTooltip(ItemStack stack, Item.TooltipContext context, List<Text> tooltip, TooltipType type) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp != null) {
            NbtCompound nbt = comp.copyNbt();
            if (nbt.contains("dna")) {
                CollectibleDNA dna = CollectibleDNA.fromNbt(nbt.getCompound("dna"));
                tooltip.add(Text.translatable("eggandcheese.tooltip.pattern", dna.pattern().getDisplayName())
                    .formatted(net.minecraft.util.Formatting.GRAY));
                tooltip.add(Text.translatable("eggandcheese.tooltip.palette", dna.palette().getDisplayName())
                    .formatted(net.minecraft.util.Formatting.GRAY));
            }
            if (nbt.contains("registryId") && !nbt.getString("registryId").isEmpty()) {
                tooltip.add(Text.translatable("eggandcheese.tooltip.id", nbt.getString("registryId"))
                    .formatted(net.minecraft.util.Formatting.DARK_GRAY));
            }
        }
        super.appendTooltip(stack, context, tooltip, type);
    }

    @Override
    protected boolean postPlacement(BlockPos pos, World world, PlayerEntity player, ItemStack stack, BlockState state) {
        boolean result = super.postPlacement(pos, world, player, stack, state);
        if (world.getBlockEntity(pos) instanceof CollectibleDecoBlockEntity be) {
            NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
            if (comp != null) {
                NbtCompound nbt = comp.copyNbt();
                if (nbt.contains("dna")) be.setDna(CollectibleDNA.fromNbt(nbt.getCompound("dna")));
            }
        }
        return result;
    }

    /**
     * Cobblemon display_case fix:
     * CollectibleItem extends BlockItem, so right-clicking ANY block normally tries
     * to PLACE the deco block. For display_case (and similar containers), we must
     * allow the block's own handler to run by returning PASS.
     *
     * Returning PASS here makes Fabric pass the interaction to the target block,
     * which can then insert our item normally.
     */
    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (context.getWorld().isClient) return ActionResult.PASS;
        var block = context.getWorld().getBlockState(context.getBlockPos()).getBlock();
        String blockId = net.minecraft.registry.Registries.BLOCK
            .getId(block).toString();
        // Let display cases and similar storage blocks handle the interaction
        if (blockId.contains("display_case") || blockId.contains("display_block")
            || blockId.contains("showcase") || blockId.contains("pedestal")
            || blockId.contains("shelf")) {
            return ActionResult.PASS;
        }
        return super.useOnBlock(context);
    }

    public CollectibleType getCollectibleType() { return collectibleType; }

    public static ItemStack create(CollectibleType type, CollectibleDNA dna) {
        Item item = com.roicheese.eggandcheese.registry.ModItems.COLLECTIBLE_EGG;
        ItemStack stack = new ItemStack(item);
        NbtCompound nbt = new NbtCompound();
        nbt.put("dna", dna.toNbt());
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return stack;
    }
}
