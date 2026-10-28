package com.roicheese.eggandcheese.blockentity;

import com.roicheese.eggandcheese.dna.CollectibleDNA;
import com.roicheese.eggandcheese.dna.CollectibleType;
import com.roicheese.eggandcheese.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class CollectibleDecoBlockEntity extends BlockEntity {
    private CollectibleDNA dna = CollectibleDNA.generate();
    private CollectibleType collectibleType;
    public float yRotation = 0f;

    public CollectibleDecoBlockEntity(BlockPos pos, BlockState state, CollectibleType type) {
        super(ModBlockEntities.COLLECTIBLE_DECO, pos, state);
        this.collectibleType = type;
    }

    public static void tick(World world, BlockPos pos, BlockState state, CollectibleDecoBlockEntity be) {
        be.yRotation = (be.yRotation + 0.3f) % 360f;
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);
        dna = CollectibleDNA.fromNbt(nbt.getCompound("dna"));
        collectibleType = CollectibleType.fromId(nbt.getString("collectibleType"));
    }

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt, registries);
        nbt.put("dna", dna.toNbt());
        nbt.putString("collectibleType", collectibleType.getId());
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registries) {
        return createNbt(registries);
    }

    @Override
    public BlockEntityUpdateS2CPacket toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }

    public CollectibleDNA getDna()             { return dna; }
    public void setDna(CollectibleDNA dna)      { this.dna = dna; markDirty(); }
    public CollectibleType getCollectibleType() { return collectibleType; }
}
