package com.cheesevanity.mixin.client;

import com.cheesevanity.VanityClientState;
import dev.emi.trinkets.TrinketFeatureRenderer;
import dev.emi.trinkets.api.TrinketComponent;
import dev.emi.trinkets.api.TrinketInventory;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

/**
 * Temporarily empties hidden trinket slots before rendering, restores them after.
 * This prevents TrinketFeatureRenderer from rendering hidden trinkets without
 * modifying any rendering logic directly.
 */
@Mixin(value = TrinketFeatureRenderer.class, remap = false)
public class TrinketFeatureRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {

    @Unique
    private static final ThreadLocal<Map<String, ItemStack>> cheesevanity$savedItems =
            ThreadLocal.withInitial(HashMap::new);

    @Inject(method = "render", at = @At("HEAD"), require = 0, remap = true)
    private void cheesevanity$beforeRender(MatrixStack matrices, VertexConsumerProvider vcp, int light,
                                           T entity, float f1, float f2, float f3, float f4, float f5, float f6,
                                           CallbackInfo ci) {
        Set<String> hidden = VanityClientState.getHiddenSlots(entity.getUuid());
        if (hidden == null || hidden.isEmpty()) return;

        Optional<TrinketComponent> opt = TrinketsApi.getTrinketComponent(entity);
        if (opt.isEmpty()) return;

        Map<String, ItemStack> saved = cheesevanity$savedItems.get();
        saved.clear();

        Map<String, Map<String, TrinketInventory>> inv = opt.get().getInventory();
        for (var groupEntry : inv.entrySet()) {
            String group = groupEntry.getKey();
            for (var slotEntry : groupEntry.getValue().entrySet()) {
                String slotName = slotEntry.getKey();
                String slotKey = group + "/" + slotName;
                if (!hidden.contains(slotKey)) continue;

                TrinketInventory trinketInv = slotEntry.getValue();
                for (int i = 0; i < trinketInv.size(); i++) {
                    ItemStack stack = trinketInv.getStack(i);
                    if (!stack.isEmpty()) {
                        saved.put(slotKey + "#" + i, stack);
                        trinketInv.setStack(i, ItemStack.EMPTY);
                    }
                }
            }
        }
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0, remap = true)
    private void cheesevanity$afterRender(MatrixStack matrices, VertexConsumerProvider vcp, int light,
                                          T entity, float f1, float f2, float f3, float f4, float f5, float f6,
                                          CallbackInfo ci) {
        Map<String, ItemStack> saved = cheesevanity$savedItems.get();
        if (saved.isEmpty()) return;

        Optional<TrinketComponent> opt = TrinketsApi.getTrinketComponent(entity);
        if (opt.isEmpty()) {
            saved.clear();
            return;
        }

        Map<String, Map<String, TrinketInventory>> inv = opt.get().getInventory();
        for (var entry : saved.entrySet()) {
            // Key format: "group/name#index"
            int hashIdx = entry.getKey().lastIndexOf('#');
            if (hashIdx < 0) continue;
            String slotKey = entry.getKey().substring(0, hashIdx);
            int idx = Integer.parseInt(entry.getKey().substring(hashIdx + 1));

            int slashIdx = slotKey.indexOf('/');
            if (slashIdx < 0) continue;
            String group = slotKey.substring(0, slashIdx);
            String name = slotKey.substring(slashIdx + 1);

            Map<String, TrinketInventory> groupMap = inv.get(group);
            if (groupMap == null) continue;
            TrinketInventory trinketInv = groupMap.get(name);
            if (trinketInv == null) continue;
            trinketInv.setStack(idx, entry.getValue());
        }
        saved.clear();
    }
}
