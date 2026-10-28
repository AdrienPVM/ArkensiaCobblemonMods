package com.sebastian.cobblemondungeons.fluid;

import com.sebastian.cobblemondungeons.CobblemonDungeons;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.WaterFluid;
import net.minecraft.item.Item;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldView;

public abstract class PoisonWaterFluid extends WaterFluid {
    @Override
    public boolean matchesType(Fluid fluid) {
        return fluid == getStill() || fluid == getFlowing();
    }

    @Override
    public abstract Fluid getFlowing();

    @Override
    public abstract Fluid getStill();

    @Override
    public Item getBucketItem() {
        return CobblemonDungeons.POISON_WATER_BUCKET;
    }

    @Override
    protected boolean isInfinite(net.minecraft.world.World world) {
        return false;
    }

    @Override
    public BlockState toBlockState(FluidState state) {
        return CobblemonDungeons.POISON_WATER_BLOCK.getDefaultState().with(net.minecraft.block.FluidBlock.LEVEL, getBlockStateLevel(state));
    }

    public static class Flowing extends PoisonWaterFluid {
        @Override
        public Fluid getFlowing() {
            return this;
        }

        @Override
        public Fluid getStill() {
            return CobblemonDungeons.STILL_POISON_WATER;
        }

        @Override
        protected void appendProperties(StateManager.Builder<Fluid, FluidState> builder) {
            super.appendProperties(builder);
            builder.add(LEVEL);
        }

        @Override
        public int getLevel(FluidState state) {
            return state.get(LEVEL);
        }

        @Override
        public boolean isStill(FluidState state) {
            return false;
        }
    }

    public static class Still extends PoisonWaterFluid {
        @Override
        public Fluid getFlowing() {
            return CobblemonDungeons.FLOWING_POISON_WATER;
        }

        @Override
        public Fluid getStill() {
            return this;
        }

        @Override
        public int getLevel(FluidState state) {
            return 8;
        }

        @Override
        public boolean isStill(FluidState state) {
            return true;
        }
    }
}
