package com.cheeseitems;

import com.cheeseitems.CustomItemDefinition.PotionEffectEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.*;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Core manager: CRUD operations, ItemStack building, hot-reload of player inventories.
 */
public class ItemManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");

    private final CheeseItemsConfig config;
    private MinecraftServer server;

    public ItemManager(CheeseItemsConfig config) {
        this.config = config;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public List<CustomItemDefinition> getAllItems() {
        return config.getItems();
    }

    public CustomItemDefinition getItem(String id) {
        return config.getItems().stream()
                .filter(d -> d.getId().equals(id))
                .findFirst().orElse(null);
    }

    public CustomItemDefinition createItem(CustomItemDefinition def) {
        config.getItemsMutable().add(def);
        config.save();
        return def;
    }

    public void updateItem(CustomItemDefinition def) {
        config.save();
        refreshAllPlayers();
    }

    public boolean deleteItem(String id) {
        boolean removed = config.getItemsMutable().removeIf(d -> d.getId().equals(id));
        if (removed) config.save();
        return removed;
    }

    public void reload() {
        config.load();
        refreshAllPlayers();
    }

    // ── Build ItemStack ──────────────────────────────────────────────────────

    public ItemStack buildItemStack(CustomItemDefinition def, int count) {
        Identifier itemId = Identifier.of(def.getBaseItem());
        Item baseItem = Registries.ITEM.get(itemId);
        ItemStack stack = new ItemStack(baseItem, count);

        // Custom NBT tag for identification
        NbtCompound nbt = new NbtCompound();
        nbt.putString("cheeseitems", def.getId());
        if (def.getMaxUses() > 0) {
            nbt.putInt("usesLeft", def.getMaxUses());
        }
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));

        applyVisuals(stack, def);
        return stack;
    }

    /**
     * Apply all visual/behavioral components to an existing stack (for hot-reload).
     */
    private void applyVisuals(ItemStack stack, CustomItemDefinition def) {
        // Display name with color
        int nameRgb = CustomItemDefinition.parseHexColor(def.getNameColor());
        Text name = Text.literal(def.getDisplayName())
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(nameRgb)).withItalic(false));
        stack.set(DataComponentTypes.CUSTOM_NAME, name);

        // Lore lines
        int loreRgb = CustomItemDefinition.parseHexColor(def.getLoreColor());
        List<Text> loreLines = new ArrayList<>();
        for (String line : def.getLore()) {
            loreLines.add(Text.literal(line)
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(loreRgb)).withItalic(false)));
        }
        stack.set(DataComponentTypes.LORE, new LoreComponent(loreLines));

        // Max stack size
        stack.set(DataComponentTypes.MAX_STACK_SIZE, Math.max(1, Math.min(64, def.getMaxStackSize())));

        // Enchant glint
        if (def.isGlowing()) {
            stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        } else {
            stack.remove(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
        }


        // Unbreakable
        if (def.isUnbreakable()) {
            stack.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(true));
        } else {
            stack.remove(DataComponentTypes.UNBREAKABLE);
        }

        // Dye color (leather/potion items)
        if (def.getDyeColor() >= 0) {
            stack.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(def.getDyeColor(), false));
        } else {
            stack.remove(DataComponentTypes.DYED_COLOR);
        }

        // Custom model data (for server resource pack custom icons)
        Integer cmd = config.getCustomModelData(def.getId());
        if (cmd != null && config.hasItemImage(def.getId())) {
            stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(cmd));
        } else {
            stack.remove(DataComponentTypes.CUSTOM_MODEL_DATA);
        }
    }

    // ── Identification ───────────────────────────────────────────────────────

    /**
     * Get the item definition from an ItemStack by reading its cheeseitems NBT tag.
     */
    public CustomItemDefinition getDefinitionFromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return null;
        NbtCompound nbt = comp.copyNbt();
        if (!nbt.contains("cheeseitems")) return null;
        String itemId = nbt.getString("cheeseitems");
        return getItem(itemId);
    }

    /**
     * Get the raw cheeseitems ID from a stack (even if definition is deleted).
     */
    public static String getCheeseItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return null;
        NbtCompound nbt = comp.copyNbt();
        if (!nbt.contains("cheeseitems")) return null;
        return nbt.getString("cheeseitems");
    }

    // ── Hot-reload ───────────────────────────────────────────────────────────

    public void refreshAllPlayers() {
        if (server == null) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            refreshPlayerInventory(player);
        }
    }

    private void refreshPlayerInventory(ServerPlayerEntity player) {
        boolean changed = false;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            CustomItemDefinition def = getDefinitionFromStack(stack);
            if (def != null) {
                // Check if the base item type has changed (e.g. paper → totem_of_undying)
                Identifier expectedId = Identifier.of(def.getBaseItem());
                Item expectedItem = Registries.ITEM.get(expectedId);
                if (!stack.isOf(expectedItem)) {
                    // Base item changed — rebuild the entire stack, preserve count + usesLeft
                    int count = stack.getCount();
                    int usesLeft = getUsesLeft(stack);
                    ItemStack newStack = buildItemStack(def, count);
                    if (usesLeft >= 0) {
                        NbtComponent comp = newStack.get(DataComponentTypes.CUSTOM_DATA);
                        if (comp != null) {
                            NbtCompound nbt = comp.copyNbt();
                            nbt.putInt("usesLeft", usesLeft);
                            newStack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
                        }
                    }
                    player.getInventory().setStack(i, newStack);
                } else {
                    applyVisuals(stack, def);
                }
                changed = true;
            }
        }
        if (changed) {
            player.currentScreenHandler.sendContentUpdates();
        }
    }

    // ── Uses tracking ────────────────────────────────────────────────────────

    /**
     * Get remaining uses from an item stack. Returns -1 if infinite.
     */
    public static int getUsesLeft(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return -1;
        NbtCompound nbt = comp.copyNbt();
        if (!nbt.contains("usesLeft")) return -1;
        return nbt.getInt("usesLeft");
    }

    /**
     * Decrement uses and update the stack. Returns remaining uses (-1 if infinite).
     */
    public static int decrementUses(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return -1;
        NbtCompound nbt = comp.copyNbt();
        if (!nbt.contains("usesLeft")) return -1;
        int left = nbt.getInt("usesLeft");
        if (left <= 0) return -1; // infinite
        left--;
        nbt.putInt("usesLeft", left);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return left;
    }
}
