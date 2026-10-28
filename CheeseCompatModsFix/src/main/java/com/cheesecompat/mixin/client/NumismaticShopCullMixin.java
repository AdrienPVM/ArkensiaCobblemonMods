package com.cheesecompat.mixin.client;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * numismatic-overhaul shop FPS optimization — distance cutoff.
 *
 * <p>{@code ShopBlockEntityRender.render()} issues a full {@code ItemRenderer.renderItem}
 * (rotating 3D model) for every visible shop, every frame, with no custom distance cull
 * (vanilla default is 64 blocks). In a dense market this is O(N) renderItem calls per frame.</p>
 *
 * <p>This mixin cancels the render for shops beyond a fixed distance from the camera.
 * Beyond ~24 blocks the rotating item is barely visible anyway, so culling it is a pure
 * performance win with negligible visual impact. The shop block itself (static model) is
 * still rendered by the chunk mesh — only the BER's floating item is skipped.</p>
 *
 * <p>Pure-visual, client-only, fail-safe: any exception falls through to the original
 * render (no cull), so worst case is the current behavior.</p>
 */
@Pseudo
@Mixin(targets = "com.glisco.numismaticoverhaul.client.ShopBlockEntityRender", remap = false)
public abstract class NumismaticShopCullMixin {

    /** Squared cutoff distance. 10 blocks → 100 (inclusive: distance == 10 still renders).
     *  Beyond this the floating item is not rendered. Tightened from 24 → 10 (15 juin 2026)
     *  to kill FPS drops in dense markets. */
    private static final double CCMF_CULL_DIST_SQ = 10.0D * 10.0D;

    // @Coerce on the first param — target method signature is
    //   render(ShopBlockEntity, float, MatrixStack, VertexConsumerProvider, int, int)
    // but we cannot import ShopBlockEntity at compile time (no modCompile dep on
    // numismatic-overhaul). Declaring `BlockEntity` (the superclass) here causes
    // Mixin descriptor strict-match to fail with InvalidInjectionException at
    // load time. @Coerce relaxes the type check and lets us treat it as Object,
    // then cast to BlockEntity via instanceof to access getPos().
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cheesecompat$distanceCull(@Coerce Object entity, float tickDelta, MatrixStack matrices,
                                            VertexConsumerProvider vertexConsumers, int light, int overlay,
                                            CallbackInfo ci) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || !(entity instanceof BlockEntity be)) return;
            Camera camera = mc.gameRenderer.getCamera();
            if (camera == null) return;
            Vec3d cam = camera.getPos();
            BlockPos pos = be.getPos();
            double dx = cam.x - (pos.getX() + 0.5D);
            double dy = cam.y - (pos.getY() + 0.5D);
            double dz = cam.z - (pos.getZ() + 0.5D);
            if (dx * dx + dy * dy + dz * dz > CCMF_CULL_DIST_SQ) {
                ci.cancel();
            }
        } catch (Throwable t) {
            // Fail-safe: never break shop rendering — fall through to original behavior.
        }
    }
}
