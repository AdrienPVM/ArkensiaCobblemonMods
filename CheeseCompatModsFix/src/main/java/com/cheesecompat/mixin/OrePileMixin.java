package com.cheesecompat.mixin;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes crash when JustHammers AoE breaks Minerally OrePile blocks.
 * JustHammers calls onBroken() without calling onBreak() first for AoE blocks,
 * so OrePile._player is null → NPE.
 */
@Mixin(targets = "theone_ss.minerally.block.OrePile")
public abstract class OrePileMixin extends Block {

    private OrePileMixin(AbstractBlock.Settings settings) {
        super(settings);
    }

    @Shadow
    private PlayerEntity _player;

    @Inject(method = "onBroken", at = @At("HEAD"), cancellable = true)
    private void cheesecompat$skipIfNoPlayer(WorldAccess world, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (this._player == null) {
            ci.cancel();
        }
    }
}
