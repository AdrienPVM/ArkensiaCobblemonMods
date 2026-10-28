package com.cheeseguard.mixin;

import com.cheeseguard.data.ServerEvent;
import com.cheeseguard.logic.EventScoreHook;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerInteractionManager.class)
public class EventBlockBreakMixin {

    @Shadow protected ServerPlayerEntity player;

    @Unique private static final ThreadLocal<String> breakingBlockId = new ThreadLocal<>();

    @Inject(method = "tryBreakBlock", at = @At("HEAD"))
    private void captureBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (player != null) {
            BlockState state = player.getServerWorld().getBlockState(pos);
            breakingBlockId.set(Registries.BLOCK.getId(state.getBlock()).toString());
        }
    }

    @Inject(method = "tryBreakBlock", at = @At("RETURN"))
    private void onBlockBroken(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() || player == null) return;
        String blockId = breakingBlockId.get();
        if (blockId != null) {
            EventScoreHook.check(player, ServerEvent.ScoreTrigger.TriggerType.BLOCK_BREAK, blockId);
            breakingBlockId.remove();
        }
    }
}
