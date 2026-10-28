package com.roicheese.eggandcheese.renderer;

import com.roicheese.eggandcheese.block.PedestalBlock;
import com.roicheese.eggandcheese.block.PedestalVariant;
import com.roicheese.eggandcheese.blockentity.PedestalBlockEntity;
import com.roicheese.eggandcheese.dna.CollectibleDNA;
import com.roicheese.eggandcheese.item.CollectibleItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

public class PedestalBESR implements BlockEntityRenderer<PedestalBlockEntity> {
    public PedestalBESR(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(PedestalBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vcp, int light, int overlay) {
        renderPedestalGeometry(entity, matrices, vcp, light, overlay);

        if (!entity.hasCollectible()) return;
        ItemStack stack = entity.getDisplayStack();
        if (stack.isEmpty()) return;

        float scale = entity.getDisplayScale();
        float height = entity.getDisplayHeight();
        float ph = entity.getPillarHeight();
        // Platform top = base(4) + column(7*ph) + cap(2), all /16
        float platformTop = (6f + 7f * ph) / 16f;

        if (stack.getItem() instanceof CollectibleItem ci) {
            CollectibleDNA dna = entity.getDisplayDNA();
            if (dna == null) return;

            matrices.push();
            matrices.translate(0.5, platformTop + 0.04 + height + entity.bobOffset, 0.5);
            matrices.scale(scale, scale, scale);
            matrices.translate(-0.5, 0.0, -0.5);

            int maxDist = EggRenderConfig.textureRenderDistance;
            if (maxDist > 0) {
                double distSq = MinecraftClient.getInstance().gameRenderer.getCamera().getPos()
                    .squaredDistanceTo(
                        entity.getPos().getX() + 0.5,
                        entity.getPos().getY() + 0.5,
                        entity.getPos().getZ() + 0.5);
                if (distSq > (double) maxDist * maxDist) {
                    CollectibleModelRenderer.renderSimple(ci.getCollectibleType(), dna,
                        1.0f, matrices, vcp, light, overlay, entity.yRotation);
                    matrices.pop();
                    return;
                }
            }

            CollectibleModelRenderer.render(ci.getCollectibleType(), dna, 1.0f, matrices, vcp, light, overlay, entity.yRotation);
            matrices.pop();
            return;
        }

        // Standard item rendering
        matrices.push();
        matrices.translate(0.5, platformTop + 0.0875 + height + entity.bobOffset, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(entity.yRotation));
        matrices.scale(0.5f * scale, 0.5f * scale, 0.5f * scale);
        MinecraftClient.getInstance().getItemRenderer().renderItem(
            stack, ModelTransformationMode.GROUND, light, OverlayTexture.DEFAULT_UV,
            matrices, vcp, entity.getWorld(), 0
        );
        matrices.pop();
    }

    private void renderPedestalGeometry(PedestalBlockEntity entity, MatrixStack matrices,
                                         VertexConsumerProvider vcp, int light, int overlay) {
        PedestalVariant variant = PedestalVariant.STONE;
        if (entity.getCachedState().getBlock() instanceof PedestalBlock pb) {
            variant = pb.getVariant();
        }

        Sprite sprite = MinecraftClient.getInstance()
            .getSpriteAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE)
            .apply(variant.getTextureId());

        VertexConsumer buffer = vcp.getBuffer(
            RenderLayer.getEntitySolid(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE));
        Matrix4f pos = matrices.peek().getPositionMatrix();
        float ph = entity.getPillarHeight();

        float baseH = 4f / 16f;
        float colH = (7f / 16f) * ph;
        float colTop = baseH + colH;
        float capH = 2f / 16f;

        // Base slab: [3,0,3] to [13,4,13] — all 6 faces
        box(buffer, pos, sprite, light, overlay,
            3f/16f, 0f, 3f/16f, 13f/16f, baseH, 13f/16f, true, true);

        // Column: [6,baseH,6] to [10,colTop,10] — skip down/up (hidden by base/platform)
        if (colH > 0.001f) {
            box(buffer, pos, sprite, light, overlay,
                6f/16f, baseH, 6f/16f, 10f/16f, colTop, 10f/16f, false, false);
        }

        // Platform: [2,colTop,2] to [14,colTop+capH,14] — all 6 faces
        box(buffer, pos, sprite, light, overlay,
            2f/16f, colTop, 2f/16f, 14f/16f, colTop + capH, 14f/16f, true, true);
    }

    /**
     * Render a textured cuboid. Top/bottom faces use position-based XZ UVs (always 0-16).
     * Side faces are split at integer Y boundaries so UVs tile properly for tall pillars.
     */
    private static void box(VertexConsumer buf, Matrix4f pos, Sprite sp,
                            int light, int overlay,
                            float x1, float y1, float z1,
                            float x2, float y2, float z2,
                            boolean drawDown, boolean drawUp) {
        float px1 = x1 * 16f, pz1 = z1 * 16f;
        float px2 = x2 * 16f, pz2 = z2 * 16f;

        // Top/bottom faces: position-based UV in X/Z (always within 0-16)
        if (drawDown)
            quad(buf, pos, sp, light, overlay,
                x1,y1,z2, x1,y1,z1, x2,y1,z1, x2,y1,z2, 0,-1,0,
                px1, pz1, px2, pz2);
        if (drawUp)
            quad(buf, pos, sp, light, overlay,
                x1,y2,z1, x1,y2,z2, x2,y2,z2, x2,y2,z1, 0,1,0,
                px1, pz1, px2, pz2);

        // Side faces: split at integer Y (block) boundaries for UV tiling
        float segBot = y1;
        while (segBot < y2 - 0.0001f) {
            float nextBound = (float)(Math.floor(segBot) + 1.0);
            if (nextBound - segBot < 0.0001f) nextBound += 1.0f;
            float segTop = Math.min(y2, nextBound);

            // V coords: map within-block Y position to texture V (16-y convention)
            float blockBase = (float) Math.floor(segBot);
            float vTop = 16f - (segTop - blockBase) * 16f;
            float vBot = 16f - (segBot - blockBase) * 16f;

            // North (z1)
            quad(buf, pos, sp, light, overlay,
                x2,segTop,z1, x2,segBot,z1, x1,segBot,z1, x1,segTop,z1,
                0,0,-1, px1, vTop, px2, vBot);
            // South (z2)
            quad(buf, pos, sp, light, overlay,
                x1,segTop,z2, x1,segBot,z2, x2,segBot,z2, x2,segTop,z2,
                0,0,1, px1, vTop, px2, vBot);
            // West (x1)
            quad(buf, pos, sp, light, overlay,
                x1,segTop,z1, x1,segBot,z1, x1,segBot,z2, x1,segTop,z2,
                -1,0,0, pz1, vTop, pz2, vBot);
            // East (x2)
            quad(buf, pos, sp, light, overlay,
                x2,segTop,z2, x2,segBot,z2, x2,segBot,z1, x2,segTop,z1,
                1,0,0, pz1, vTop, pz2, vBot);

            segBot = segTop;
        }
    }

    /** Emit a single quad with UV in pixel space [0..16]. */
    private static void quad(VertexConsumer buf, Matrix4f pos, Sprite sp,
                             int light, int overlay,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz,
                             float nx, float ny, float nz,
                             float u1p, float v1p, float u2p, float v2p) {
        float uMin = u(sp, u1p), vMin = v(sp, v1p);
        float uMax = u(sp, u2p), vMax = v(sp, v2p);

        buf.vertex(pos, ax,ay,az).color(255,255,255,255).texture(uMin,vMin).overlay(overlay).light(light).normal(nx,ny,nz);
        buf.vertex(pos, bx,by,bz).color(255,255,255,255).texture(uMin,vMax).overlay(overlay).light(light).normal(nx,ny,nz);
        buf.vertex(pos, cx,cy,cz).color(255,255,255,255).texture(uMax,vMax).overlay(overlay).light(light).normal(nx,ny,nz);
        buf.vertex(pos, dx,dy,dz).color(255,255,255,255).texture(uMax,vMin).overlay(overlay).light(light).normal(nx,ny,nz);
    }

    private static float u(Sprite sp, float pixel) {
        return sp.getMinU() + (pixel / 16f) * (sp.getMaxU() - sp.getMinU());
    }

    private static float v(Sprite sp, float pixel) {
        return sp.getMinV() + (pixel / 16f) * (sp.getMaxV() - sp.getMinV());
    }
}
