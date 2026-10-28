package com.roicheese.eggandcheese.renderer;

import com.roicheese.eggandcheese.dna.CollectibleDNA;
import com.roicheese.eggandcheese.dna.CollectibleType;
import com.roicheese.eggandcheese.dna.EmissiveType;
import com.roicheese.eggandcheese.dna.PaletteType;
import com.roicheese.eggandcheese.dna.PatternType;
import com.roicheese.eggandcheese.dna.RarityType;
import com.roicheese.eggandcheese.dna.StampType;
import com.roicheese.eggandcheese.dna.MicroTextureType;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.RotationAxis;

import java.util.WeakHashMap;

/**
 * Renders the 3D egg model for item rendering (hand, GUI, ground).
 *
 * EMI/inventory fix:
 *   Items WITHOUT embedded DNA (e.g. in EMI browser) get a static DEFAULT_DNA —
 *   a neutral white egg that is generated once and never changes, avoiding
 *   per-frame texture regeneration and the resulting lag.
 *
 *   DNA is only assigned randomly when the admin egg is PLACED in the world
 *   (handled in AdminCollectibleItem.postPlacement), NOT when it is in the inventory.
 */
public class EggItemRenderer {

    /**
     * Static neutral egg shown for items without embedded DNA.
     * Plain white/grey, no pattern — clearly a "blank" admin egg.
     */
    private static final CollectibleDNA DEFAULT_DNA = new CollectibleDNA(
        0xDEADBEEFCAFEBABEL,
        PaletteType.ARCTIC,
        PatternType.SPOTS,
        1,
        PatternType.SPOTS,
        0,
        EmissiveType.NONE,
        0,
        RarityType.COMMON,
        StampType.NONE,
        MicroTextureType.NONE,
        CollectibleDNA.FLAG_LIGHTING | CollectibleDNA.FLAG_SPECULAR,
        0,
        ""
    );

    /**
     * Per-stack DNA cache for items that HAVE embedded DNA.
     * WeakHashMap ensures stacks are GC'd normally.
     */
    private static final WeakHashMap<ItemStack, CollectibleDNA> DNA_CACHE = new WeakHashMap<>();

    public static void render(ItemStack stack, ModelTransformationMode mode,
                              MatrixStack matrices, VertexConsumerProvider provider,
                              int light, int overlay) {
        CollectibleDNA dna = getDna(stack);
        matrices.push();

        switch (mode) {
            case GUI -> {
                matrices.translate(0.5, 0.5, 0.5);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-20f));
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-30f));
                matrices.translate(-0.5, -0.25, -0.5); // Y raised by 0.25 to center egg in GUI icon
            }
            case FIRST_PERSON_RIGHT_HAND, FIRST_PERSON_LEFT_HAND,
                 THIRD_PERSON_RIGHT_HAND, THIRD_PERSON_LEFT_HAND -> {
                matrices.translate(0.5, 0.3, 0.5);
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-10f));
                matrices.translate(-0.5, -0.3, -0.5);
            }
            case GROUND -> {
                matrices.translate(0.5, 0.5, 0.5);
                matrices.scale(0.75f, 0.75f, 0.75f);
                matrices.translate(-0.5, -0.5, -0.5);
            }
            default -> {}
        }

        CollectibleModelRenderer.render(CollectibleType.EGG, dna, 1.0f,
            matrices, provider, LightmapTextureManager.MAX_LIGHT_COORDINATE, overlay, 0f);
        matrices.pop();
    }

    public static CollectibleDNA getDna(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp != null) {
            NbtCompound nbt = comp.copyNbt();
            if (nbt.contains("dna")) {
                // Cache by ItemStack identity to avoid deserialising every frame
                return DNA_CACHE.computeIfAbsent(stack,
                    s -> CollectibleDNA.fromNbt(nbt.getCompound("dna")));
            }
        }
        // No DNA → return the static default, never regenerate
        return DEFAULT_DNA;
    }
}
