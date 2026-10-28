package com.roicheese.eggandcheese.blockentity;

import com.roicheese.eggandcheese.block.PedestalExtensionBlock;
import com.roicheese.eggandcheese.dna.CollectibleDNA;
import com.roicheese.eggandcheese.registry.ModBlockEntities;
import com.roicheese.eggandcheese.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class PedestalBlockEntity extends BlockEntity {
    private ItemStack displayStack = ItemStack.EMPTY;
    private float displayScale = 1.0f;
    private float displayHeight = 0.0f;
    private float pillarHeight = 1.0f;
    private boolean spinning = true;
    public float yRotation = 0f;
    public float bobOffset = 0f;
    private float bobTick = 0f;

    // Shape cache — one VoxelShape per block offset (0 = base, 1..4 = extensions)
    private VoxelShape[] cachedShapes = null;
    private float cachedShapesPh = -1f;

    public PedestalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PEDESTAL, pos, state);
    }

    public static void tick(World world, BlockPos pos, BlockState state, PedestalBlockEntity be) {
        if (be.spinning) be.yRotation = (be.yRotation + 0.8f) % 360f;
        be.bobTick += 0.05f;
        be.bobOffset = (float) Math.sin(be.bobTick) * 0.05f;
    }

    public boolean hasCollectible()           { return !displayStack.isEmpty(); }
    public boolean canAccept(ItemStack stack)  { return !stack.isEmpty(); }
    public ItemStack getDisplayStack()         { return displayStack; }
    public float getDisplayScale()             { return displayScale; }
    public float getDisplayHeight()            { return displayHeight; }
    public float getPillarHeight()             { return pillarHeight; }

    public void setDisplayScale(float scale) {
        this.displayScale = Math.max(0.5f, Math.min(3.0f, scale));
        markDirty();
        sync();
    }

    public void setDisplayHeight(float height) {
        this.displayHeight = Math.max(-0.5f, Math.min(0.5f, height));
        markDirty();
        sync();
    }

    public void setPillarHeight(float height) {
        this.pillarHeight = Math.max(0.0f, Math.min(5.0f, height));
        cachedShapes = null;
        markDirty();
        sync();
        updateExtensions();
    }

    // ── Shape computation (per-offset) ───────────────────────────────────────

    private void rebuildShapeCache() {
        if (cachedShapes != null && cachedShapesPh == pillarHeight) return;
        cachedShapesPh = pillarHeight;
        float colTopPx = 4f + 7f * pillarHeight;
        float platTopPx = colTopPx + 2f;
        int maxOffset = Math.max(0, (int) Math.ceil(platTopPx / 16f) - 1);
        maxOffset = Math.min(maxOffset, 4);

        cachedShapes = new VoxelShape[maxOffset + 1];
        for (int i = 0; i <= maxOffset; i++) {
            cachedShapes[i] = computeShapeForOffset(i, colTopPx, platTopPx);
        }
    }

    /**
     * Returns the collision/outline shape for a given block offset.
     * offset=0 is the base pedestal block, 1..4 are extension blocks above.
     */
    public VoxelShape getShapeForOffset(int offset) {
        rebuildShapeCache();
        if (offset < 0 || offset >= cachedShapes.length) return VoxelShapes.empty();
        return cachedShapes[offset];
    }

    /** Shape for the base block (offset 0) — used by PedestalBlock. */
    public VoxelShape getShape() {
        return getShapeForOffset(0);
    }

    private static VoxelShape computeShapeForOffset(int offset, float colTopPx, float platTopPx) {
        float minPx = offset * 16f;
        float maxPx = (offset + 1) * 16f;

        List<VoxelShape> shapes = new ArrayList<>();

        // Base slab: y=[0, 4]px — [3,0,3] to [13,4,13]
        if (0 < maxPx && 4 > minPx) {
            shapes.add(Block.createCuboidShape(3, Math.max(0, 0 - minPx), 3,
                                               13, Math.min(16, 4 - minPx), 13));
        }
        // Column: y=[4, colTopPx] — [6,4,6] to [10,colTopPx,10]
        if (4 < maxPx && colTopPx > minPx) {
            shapes.add(Block.createCuboidShape(6, Math.max(0, 4 - minPx), 6,
                                               10, Math.min(16, colTopPx - minPx), 10));
        }
        // Platform: y=[colTopPx, platTopPx] — [2,colTopPx,2] to [14,platTopPx,14]
        if (colTopPx < maxPx && platTopPx > minPx) {
            shapes.add(Block.createCuboidShape(2, Math.max(0, colTopPx - minPx), 2,
                                               14, Math.min(16, platTopPx - minPx), 14));
        }

        if (shapes.isEmpty()) return VoxelShapes.empty();
        VoxelShape result = shapes.get(0);
        for (int i = 1; i < shapes.size(); i++) result = VoxelShapes.union(result, shapes.get(i));
        return result;
    }

    // ── Extension block management ───────────────────────────────────────────

    /** Places/removes invisible extension blocks above when pillar height changes. */
    public void updateExtensions() {
        if (world == null || world.isClient) return;

        float platTopPx = 4f + 7f * pillarHeight + 2f;
        int needed = Math.max(0, (int) Math.ceil(platTopPx / 16f) - 1);
        needed = Math.min(needed, 4);

        // Remove excess extensions
        for (int i = needed + 1; i <= 4; i++) {
            BlockPos extPos = pos.up(i);
            if (world.getBlockState(extPos).getBlock() instanceof PedestalExtensionBlock) {
                world.removeBlock(extPos, false);
            }
        }
        // Place needed extensions
        for (int i = 1; i <= needed; i++) {
            BlockPos extPos = pos.up(i);
            BlockState existing = world.getBlockState(extPos);
            BlockState desired = ModBlocks.PEDESTAL_EXTENSION.getDefaultState()
                .with(PedestalExtensionBlock.OFFSET, i);

            if (existing.getBlock() instanceof PedestalExtensionBlock) {
                if (!existing.equals(desired)) {
                    world.setBlockState(extPos, desired);
                }
            } else if (existing.isAir() || existing.isReplaceable()) {
                world.setBlockState(extPos, desired);
            }
        }
    }

    /** Removes all extension blocks above (called when pedestal is broken). */
    public void removeAllExtensions() {
        if (world == null) return;
        for (int i = 1; i <= 4; i++) {
            BlockPos extPos = pos.up(i);
            if (world.getBlockState(extPos).getBlock() instanceof PedestalExtensionBlock) {
                world.removeBlock(extPos, false);
            }
        }
    }

    // ── Display ──────────────────────────────────────────────────────────────

    public boolean isSpinning()                { return spinning; }

    public void setSpinning(boolean spinning) {
        this.spinning = spinning;
        markDirty();
        sync();
    }

    public CollectibleDNA getDisplayDNA() {
        if (displayStack.isEmpty()) return null;
        NbtComponent comp = displayStack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return null;
        NbtCompound nbt = comp.copyNbt();
        if (!nbt.contains("dna")) return null;
        return CollectibleDNA.fromNbt(nbt.getCompound("dna"));
    }

    public void setCollectible(ItemStack stack) {
        displayStack = stack.copy();
        markDirty();
        sync();
    }

    public ItemStack removeCollectible() {
        ItemStack removed = displayStack.copy();
        displayStack = ItemStack.EMPTY;
        markDirty();
        sync();
        return removed;
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    private void sync() {
        if (world == null) return;
        world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        if (world instanceof ServerWorld) {
            BlockEntityUpdateS2CPacket packet = toUpdatePacket();
            net.fabricmc.fabric.api.networking.v1.PlayerLookup
                .tracking(this)
                .forEach(p -> p.networkHandler.sendPacket(packet));
        }
    }

    // ── NBT ──────────────────────────────────────────────────────────────────

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);
        if (nbt.contains("hasDisplay") && !nbt.getBoolean("hasDisplay")) {
            displayStack = ItemStack.EMPTY;
        } else if (nbt.contains("displayStack")) {
            displayStack = ItemStack.fromNbtOrEmpty(registries, nbt.getCompound("displayStack"));
        } else {
            displayStack = ItemStack.EMPTY;
        }
        displayScale = nbt.contains("displayScale") ? nbt.getFloat("displayScale") : 1.0f;
        displayHeight = nbt.contains("displayHeight") ? nbt.getFloat("displayHeight") : 0.0f;
        pillarHeight = nbt.contains("pillarHeight") ? nbt.getFloat("pillarHeight") : 1.0f;
        spinning = !nbt.contains("spinning") || nbt.getBoolean("spinning");
        cachedShapes = null; // invalidate cache on load
    }

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt, registries);
        nbt.putBoolean("hasDisplay", !displayStack.isEmpty());
        if (!displayStack.isEmpty()) nbt.put("displayStack", displayStack.encode(registries));
        nbt.putFloat("displayScale", displayScale);
        nbt.putFloat("displayHeight", displayHeight);
        nbt.putFloat("pillarHeight", pillarHeight);
        nbt.putBoolean("spinning", spinning);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registries) { return createNbt(registries); }

    @Override
    public BlockEntityUpdateS2CPacket toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }
}
