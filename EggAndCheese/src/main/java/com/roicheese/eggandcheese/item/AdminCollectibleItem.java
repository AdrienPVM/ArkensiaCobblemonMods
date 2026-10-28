package com.roicheese.eggandcheese.item;

import com.roicheese.eggandcheese.blockentity.AdminCollectibleBlockEntity;
import com.roicheese.eggandcheese.data.CollectibleEntry;
import com.roicheese.eggandcheese.data.EggRegistryData;
import com.roicheese.eggandcheese.data.LiveAdminEggIndex;
import com.roicheese.eggandcheese.dna.CollectibleDNA;
import com.roicheese.eggandcheese.dna.CollectibleType;
import com.roicheese.eggandcheese.ftb.FtbQuestsHook;
import com.roicheese.eggandcheese.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class AdminCollectibleItem extends BlockItem {
    private final CollectibleType collectibleType;

    public AdminCollectibleItem(CollectibleType type, Settings settings) {
        super(ModBlocks.ADMIN_EGG, settings.maxCount(64));
        this.collectibleType = type;
    }

    @Override
    protected boolean postPlacement(BlockPos pos, World world, PlayerEntity player, ItemStack stack, BlockState state) {
        boolean result = super.postPlacement(pos, world, player, stack, state);
        if (world.isClient || !(world instanceof ServerWorld sw)) return result;
        if (!(world.getBlockEntity(pos) instanceof AdminCollectibleBlockEntity be)) return result;

        // Read data from item NBT
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        String existingId = "";
        CollectibleDNA dna = CollectibleDNA.generate();
        float scale = 1.0f;

        if (comp != null) {
            NbtCompound nbt = comp.copyNbt();
            if (nbt.contains("dna"))        dna = CollectibleDNA.fromNbt(nbt.getCompound("dna"));
            if (nbt.contains("registryId")) existingId = nbt.getString("registryId");
            if (nbt.contains("scale"))      scale = nbt.getFloat("scale");
        }

        EggRegistryData registry = EggRegistryData.get(sw.getServer());
        String dim = world.getRegistryKey().getValue().toString();

        if (!existingId.isEmpty() && registry.hasId(existingId)) {
            // Re-placing an egg that had lost its position (or moving it).
            CollectibleEntry entry = registry.getEntry(existingId).get();
            // B: if this egg already has a LOADED physical block elsewhere, remove it first so
            // we never leave a stale duplicate behind (mirrors the dashboard PUT relocation).
            BlockPos oldPos = entry.getPos();
            String oldDim = entry.getDimension();
            if (!entry.isPositionLost() && !(oldPos.equals(pos) && oldDim.equals(dim))
                    && LiveAdminEggIndex.get(existingId).contains(new LiveAdminEggIndex.EggLoc(oldDim, oldPos))) {
                for (ServerWorld w : sw.getServer().getWorlds()) {
                    if (w.getRegistryKey().getValue().toString().equals(oldDim)) {
                        if (w.getBlockEntity(oldPos) instanceof AdminCollectibleBlockEntity oldBe
                                && existingId.equals(oldBe.getRegistryId())) {
                            oldBe.setRegistryId(""); // unbind (clears index) before removing
                            w.removeBlock(oldPos, false);
                        }
                        break;
                    }
                }
            }
            entry.setPos(pos);
            entry.setDimension(dim);
            entry.setPositionLost(false);
            entry.setDna(dna);
            entry.setScale(scale);
            registry.markDirty();
            be.setDna(dna);
            be.setRegistryId(existingId);
            be.setScale(scale);
            be.setRenderDistanceOverride(entry.getRenderDistanceOverride());
            player.sendMessage(Text.translatable("eggandcheese.egg_replaced", existingId)
                .formatted(Formatting.GREEN), false);
        } else {
            // New placement — auto-generate unique ID
            String newId = registry.generateUniqueId();
            CollectibleEntry entry = new CollectibleEntry(newId, CollectibleType.EGG, dna, pos, dim);
            entry.setScale(scale);
            registry.addEntry(entry);
            be.setDna(dna);
            be.setRegistryId(newId);
            be.setScale(scale);

            // Auto-create FTB quest if enabled
            if (registry.isFtbQuestsEnabled()) {
                long qid = FtbQuestsHook.createQuest(registry.getFtbQuestsChapter(), entry, sw.getServer());
                if (qid != 0) {
                    entry.setFtbQuestId(qid);
                    registry.markDirty();
                }
            }

            player.sendMessage(Text.translatable("eggandcheese.egg_created_auto", newId)
                .formatted(Formatting.YELLOW), false);
        }
        return result;
    }

    public CollectibleType getCollectibleType() { return collectibleType; }
}
