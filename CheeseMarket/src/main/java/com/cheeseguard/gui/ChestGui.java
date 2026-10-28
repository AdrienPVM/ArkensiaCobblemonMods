package com.cheeseguard.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.Consumer;

/**
 * Base class for all CheeseGuard chest-based GUIs.
 * Subclasses fill the inventory and handle slot clicks.
 */
public abstract class ChestGui extends SimpleInventory {

    protected final ServerPlayerEntity player;
    protected final int rows;
    private ScreenHandler handler;

    public ChestGui(ServerPlayerEntity player, int rows) {
        super(rows * 9);
        this.player = player;
        this.rows = rows;
    }

    /** Override to fill items into the GUI. Called on open and refresh. */
    protected abstract void build();

    /** Override to handle a slot click. */
    protected abstract void onClick(int slot, int button, SlotActionType action);

    /** Open this GUI for the player. */
    public void open() {
        build();

        ScreenHandlerFactory factory = (syncId, inv, p) -> {
            ScreenHandlerType<?> type = switch (rows) {
                case 1 -> ScreenHandlerType.GENERIC_9X1;
                case 2 -> ScreenHandlerType.GENERIC_9X2;
                case 3 -> ScreenHandlerType.GENERIC_9X3;
                case 4 -> ScreenHandlerType.GENERIC_9X4;
                case 5 -> ScreenHandlerType.GENERIC_9X5;
                default -> ScreenHandlerType.GENERIC_9X6;
            };

            GenericContainerScreenHandler handler = new GenericContainerScreenHandler(type, syncId, inv, this, rows) {
                @Override
                public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
                    if (slotIndex >= 0 && slotIndex < ChestGui.this.size()) {
                        ChestGui.this.onClick(slotIndex, button, actionType);
                    }
                }

                @Override
                public boolean canUse(PlayerEntity player) { return true; }
            };

            ChestGui.this.handler = handler;
            return handler;
        };

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(factory, getTitle()));
    }

    protected abstract Text getTitle();

    /** Re-populate items and sync. */
    public void refresh() {
        build();
        if (handler != null) handler.sendContentUpdates();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    protected void fill(int slot, ItemStack stack) {
        setStack(slot, stack);
    }

    protected void fillBorder(ItemStack stack) {
        for (int i = 0; i < rows * 9; i++) {
            int row = i / 9, col = i % 9;
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                setStack(i, stack);
            }
        }
    }

    protected void fillAll(ItemStack stack) {
        for (int i = 0; i < rows * 9; i++) setStack(i, stack);
    }

    protected ItemStack filler() {
        return GuiHelper.filler();
    }

    protected void close() {
        player.closeHandledScreen();
    }
}
