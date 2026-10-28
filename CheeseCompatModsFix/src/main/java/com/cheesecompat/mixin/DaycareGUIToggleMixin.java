package com.cheesecompat.mixin;

import com.cheesecompat.daycare.DaycareSimpleModeStore;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.elements.GuiElementInterface;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds a clock toggle in slot 5 of the DaycareGUI (breeding pasture).
 *
 * Click cycle:
 *  AUTO            → click → SIMPLE_ARMED (next tick produces one egg, then disarms)
 *  SIMPLE_ARMED    → click → AUTO         (breeds continuously until fertility depleted)
 *  SIMPLE_DISARMED → click → SIMPLE_ARMED (re-arm for another single egg)
 *
 * State lives in DaycareSimpleModeStore (keyed by dim+BlockPos), not on the BE.
 * Uses Object for Cobblemon/DaycarePlus-typed params to dodge intermediary
 * supertype resolution at javac time (e.g. IMixinPastureBlockEntity → Inventory).
 */
@Mixin(targets = "com.provismet.cobblemon.daycareplus.gui.DaycareGUI", remap = false)
public interface DaycareGUIToggleMixin {

    @Inject(method = "create", at = @At("TAIL"), remap = false)
    private static void cheesecompat$addBreedModeToggle(
            @Coerce Object pastureBlockEntity,
            @Coerce Object mixinPasture,
            ServerPlayerEntity player,
            BlockState state,
            BlockHitResult hit,
            CallbackInfoReturnable<SimpleGui> cir) {
        SimpleGui gui = cir.getReturnValue();
        if (gui == null) return;
        if (!(pastureBlockEntity instanceof BlockEntity be)) return;
        if (be.getWorld() == null) return;
        ServerWorld world = player.getServerWorld();
        String dimId = world.getRegistryKey().getValue().toString();
        long posLong = be.getPos().asLong();
        gui.setSlot(5, DaycareGUIToggleMixin.cheesecompat$buildToggleButton(be, dimId, posLong));
    }

    private static GuiElementInterface cheesecompat$buildToggleButton(
            BlockEntity be, String dimId, long posLong) {
        boolean simple = DaycareSimpleModeStore.isSimpleMode(dimId, posLong);
        boolean armed = DaycareSimpleModeStore.isArmed(dimId, posLong);
        String nameKey;
        String tooltipKey;
        Formatting nameColor;
        if (!simple) {
            nameKey = "gui.button.cheesecompat.breed_mode.auto";
            tooltipKey = "gui.button.cheesecompat.breed_mode.auto.tooltip";
            nameColor = Formatting.GREEN;
        } else if (armed) {
            nameKey = "gui.button.cheesecompat.breed_mode.simple_armed";
            tooltipKey = "gui.button.cheesecompat.breed_mode.simple_armed.tooltip";
            nameColor = Formatting.YELLOW;
        } else {
            nameKey = "gui.button.cheesecompat.breed_mode.simple_disarmed";
            tooltipKey = "gui.button.cheesecompat.breed_mode.simple_disarmed.tooltip";
            nameColor = Formatting.RED;
        }
        return new GuiElementBuilder(Items.CLOCK)
                .hideDefaultTooltip()
                .setName(Text.translatable(nameKey).formatted(nameColor, Formatting.ITALIC).styled(s -> s.withItalic(false)))
                .addLoreLine(Text.translatable(tooltipKey).formatted(Formatting.GRAY).styled(s -> s.withItalic(false)))
                .setCallback((index, type, action, gui) -> {
                    boolean wasSimple = DaycareSimpleModeStore.isSimpleMode(dimId, posLong);
                    boolean wasArmed = DaycareSimpleModeStore.isArmed(dimId, posLong);
                    if (!wasSimple) {
                        DaycareSimpleModeStore.setState(dimId, posLong, true, true);
                    } else if (wasArmed) {
                        DaycareSimpleModeStore.setState(dimId, posLong, false, true);
                    } else {
                        DaycareSimpleModeStore.setState(dimId, posLong, true, true);
                    }
                    be.markDirty();
                    gui.setSlot(5, DaycareGUIToggleMixin.cheesecompat$buildToggleButton(be, dimId, posLong));
                })
                .build();
    }
}
