package com.cheeseguard.logic;

import com.cheeseguard.data.Member;
import com.cheeseguard.data.Region;
import com.cheeseguard.data.RegionStorage;
import com.cheeseguard.data.SaleConfig;
import com.cheeseguard.data.TagConfigStorage;

import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

/**
 * Core business logic for zone sales: payment checking, purchase flow,
 * hologram lifecycle, sale sign creation, and chat prompts.
 */
public class SaleManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");

    // Cached reflection handles for TextDisplayEntity / DisplayEntity private methods
    private static Method setTextMethod;
    private static Method setBillboardModeMethod;
    private static Method setBackgroundMethod;
    private static boolean reflectionInit;

    private static void initReflection() {
        if (reflectionInit) return;
        reflectionInit = true;
        try {
            // Scan by parameter type to avoid yarn→intermediary name mismatch at runtime
            for (Method m : DisplayEntity.TextDisplayEntity.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getReturnType() == void.class) {
                    if (m.getParameterTypes()[0] == Text.class && setTextMethod == null) {
                        m.setAccessible(true);
                        setTextMethod = m;
                    }
                }
            }
            for (Method m : DisplayEntity.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getReturnType() == void.class
                        && m.getParameterTypes()[0] == DisplayEntity.BillboardMode.class) {
                    m.setAccessible(true);
                    setBillboardModeMethod = m;
                    break;
                }
            }
            // setBackground(int) — skip, not critical for hologram display
        } catch (Exception e) {
            LOGGER.error("[CheeseGuard] Failed to init TextDisplayEntity reflection", e);
        }
    }

    // ── Sign-click → purchase tracking ──────────────────────────────────────
    // Records the location of the sign a player clicked, keyed by player UUID.
    // Consumed by processPurchase() to break the sign on successful buy.
    // Key: player UUID. Value: (worldId, blockPos, zoneId, timestamp).

    public record ClickedSign(String worldId, BlockPos pos, String zoneId, long ts) {}

    private static final Map<UUID, ClickedSign> recentSignClicks = new ConcurrentHashMap<>();
    private static final long SIGN_CLICK_TTL_MS = 60_000L; // 60s window between click and confirm

    /** Called from ProtectionListener when a player right-clicks a sale sign. */
    public static void recordSignClick(UUID playerUuid, String worldId, BlockPos pos, String zoneId) {
        if (playerUuid == null || worldId == null || pos == null || zoneId == null) return;
        // Drop stale entries whenever we insert — bounded cleanup so the map can't grow
        // unboundedly when players click signs but never confirm a purchase.
        long cutoff = System.currentTimeMillis() - SIGN_CLICK_TTL_MS;
        recentSignClicks.entrySet().removeIf(e -> e.getValue().ts() < cutoff);
        recentSignClicks.put(playerUuid, new ClickedSign(worldId, pos.toImmutable(), zoneId, System.currentTimeMillis()));
    }

    /**
     * Forget any cached sign-click for this player. Called from non-sign sale paths
     * (hologram right-click) so a stale sign-click record can't be consumed by a
     * subsequent buy that the player triggered through a different surface.
     */
    public static void clearSignClick(UUID playerUuid) {
        if (playerUuid == null) return;
        recentSignClicks.remove(playerUuid);
    }

    /** Consume + return the most recent click for this player+zone, if still fresh. */
    private static ClickedSign consumeSignClick(UUID playerUuid, String zoneId) {
        ClickedSign c = recentSignClicks.remove(playerUuid);
        if (c == null) return null;
        if (!zoneId.equals(c.zoneId)) return null;
        if (System.currentTimeMillis() - c.ts > SIGN_CLICK_TTL_MS) return null;
        return c;
    }

    // ── Payment checks ──────────────────────────────────────────────────────

    /**
     * Check if a player can afford the price defined in the sale config.
     *
     * @param player the buyer
     * @param sale   the sale configuration
     * @return true if the player has sufficient funds/items
     */
    public static boolean canAfford(ServerPlayerEntity player, SaleConfig sale) {
        if (player == null || sale == null) return false;

        switch (sale.getCurrency().toUpperCase()) {
            case "NUMISMATIC":
                return NumismaticHook.canAfford(player, sale.getPrice());

            case "FROMAGE":
                return CheeseStoreHook.canAfford(player.getUuid(), sale.getPrice());

            case "ITEMS":
                return countItems(player, sale.getItemId()) >= sale.getItemCount();

            default:
                LOGGER.warn("[CheeseGuard] Unknown sale currency: {}", sale.getCurrency());
                return false;
        }
    }

    /**
     * Format the price for display in chat messages.
     *
     * @param sale the sale configuration
     * @return human-readable price string
     */
    public static String formatPrice(SaleConfig sale) {
        if (sale == null) return "???";

        switch (sale.getCurrency().toUpperCase()) {
            case "NUMISMATIC": {
                long total = sale.getPrice();
                long gold = total / 10000;
                long silver = (total % 10000) / 100;
                long bronze = total % 100;
                StringBuilder sb = new StringBuilder();
                if (gold > 0) sb.append(gold).append(" gold");
                if (silver > 0) { if (!sb.isEmpty()) sb.append(" "); sb.append(silver).append(" silver"); }
                if (bronze > 0) { if (!sb.isEmpty()) sb.append(" "); sb.append(bronze).append(" bronze"); }
                if (sb.isEmpty()) sb.append("0 bronze");
                return sb.toString();
            }

            case "FROMAGE":
                return sale.getPrice() + " fromage";

            case "ITEMS":
                return sale.getItemCount() + "x " +
                    (sale.getItemId() != null ? sale.getItemId() : "unknown");

            default:
                return sale.getPrice() + " " + sale.getCurrency();
        }
    }

    // ── Purchase flow ───────────────────────────────────────────────────────

    /**
     * Process a zone purchase. Must be called on the server thread.
     *
     * @param player     the buying player
     * @param zone       the zone being purchased
     * @param storage    region storage for persistence
     * @param tagStorage tag config storage for ownership limit checks
     * @return null on success, or an error message string on failure
     */
    public static String processPurchase(ServerPlayerEntity player, Region zone,
                                          RegionStorage storage, TagConfigStorage tagStorage) {
        if (player == null || zone == null || storage == null) {
            return "Invalid parameters";
        }

        // 1. Validate sale config
        SaleConfig sale = zone.getSaleConfig();
        if (sale == null || !sale.isEnabled()) {
            return "This zone is not for sale";
        }
        if (sale.isSold()) {
            return "This zone has already been sold";
        }

        // 2. Check tag ownership limits
        if (tagStorage != null) {
            String limitTag = tagStorage.checkOwnershipLimits(zone, player.getUuid(), storage);
            if (limitTag != null) {
                return "You have reached the ownership limit for tag: " + limitTag;
            }
        }

        // 3. Check if player can afford
        if (!canAfford(player, sale)) {
            return "You cannot afford this zone (" + formatPrice(sale) + ")";
        }

        // 4. Deduct payment
        switch (sale.getCurrency().toUpperCase()) {
            case "NUMISMATIC":
                NumismaticHook.deduct(player, sale.getPrice());
                break;

            case "FROMAGE":
                boolean deducted = CheeseStoreHook.deduct(
                    player.getUuid(), player.getName().getString(), sale.getPrice());
                if (!deducted) {
                    return "Failed to deduct fromage payment";
                }
                break;

            case "ITEMS":
                if (!deductItems(player, sale.getItemId(), sale.getItemCount())) {
                    return "Failed to deduct items from inventory";
                }
                break;

            default:
                return "Unknown currency: " + sale.getCurrency();
        }

        // 5. Transfer ownership
        Member newOwner = new Member(player.getUuid(), player.getName().getString(), Member.Role.OWNER);
        zone.transferOwnership(newOwner);

        // 6. Mark as sold
        sale.setSold(true);
        sale.setBuyerUuid(player.getUuidAsString());
        sale.setBuyerName(player.getName().getString());

        // 7. Save
        storage.save();

        // 7b. Break the sale sign the player clicked, if we recorded it within the TTL.
        // We're already on the server thread (command executor), so block-break is safe here.
        ClickedSign click = consumeSignClick(player.getUuid(), zone.getId());
        if (click != null) {
            try {
                MinecraftServer srv = player.getServer();
                if (srv != null) {
                    Identifier wid = Identifier.of(click.worldId);
                    ServerWorld world = srv.getWorld(RegistryKey.of(RegistryKeys.WORLD, wid));
                    if (world != null) {
                        BlockState state = world.getBlockState(click.pos);
                        if (state.getBlock() instanceof net.minecraft.block.AbstractSignBlock) {
                            // breakBlock(pos, dropItem) — pass false so the sign isn't dropped
                            // (it's no longer for sale and would carry stale NBT).
                            world.breakBlock(click.pos, false);
                            LOGGER.info("[CheeseGuard] Broke sale sign at {} after purchase by {}",
                                click.pos.toShortString(), player.getName().getString());
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[CheeseGuard] Failed to break sale sign: {}", e.getMessage());
            }
        }

        // 8. Update hologram if exists
        if (sale.getHologramUuid() != null) {
            updateHologramText(zone, player.getServer());
        }

        LOGGER.info("[CheeseGuard] Zone '{}' purchased by {} for {}",
            zone.getName(), player.getName().getString(), formatPrice(sale));

        return null; // success
    }

    // ── Hologram lifecycle ──────────────────────────────────────────────────

    /**
     * Spawn a TextDisplayEntity hologram at the zone's center.
     * Stores the entity UUID in the zone's sale config.
     *
     * @param zone   the zone to create a hologram for
     * @param server the Minecraft server
     */
    public static void spawnHologram(Region zone, MinecraftServer server) {
        if (zone == null || server == null) return;

        SaleConfig sale = zone.getSaleConfig();
        if (sale == null) return;

        // Remove existing hologram first
        if (sale.getHologramUuid() != null) {
            removeHologram(zone, server);
        }

        // Get the zone's world
        ServerWorld world = getWorldForZone(zone, server);
        if (world == null) {
            LOGGER.warn("[CheeseGuard] Cannot spawn hologram: world '{}' not found", zone.getWorld());
            return;
        }

        // Calculate center position (above zone)
        double x = (zone.getMinX() + zone.getMaxX()) / 2.0 + 0.5;
        double y = zone.getMaxY() + 1.5;
        double z = (zone.getMinZ() + zone.getMaxZ()) / 2.0 + 0.5;

        // Create the TextDisplayEntity
        DisplayEntity.TextDisplayEntity hologram =
            new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, world);
        hologram.refreshPositionAndAngles(x, y, z, 0f, 0f);

        // Configure text, billboard, and background via reflection
        // (these methods are private in the yarn mappings)
        Text text = buildHologramText(zone);
        applyTextDisplaySettings(hologram, text);

        // Spawn into world
        world.spawnEntity(hologram);

        // Store UUID
        sale.setHologramUuid(hologram.getUuidAsString());

        LOGGER.info("[CheeseGuard] Spawned sale hologram for zone '{}' at {},{},{}",
            zone.getName(), (int) x, (int) y, (int) z);
    }

    /**
     * Remove the hologram entity for a zone.
     *
     * @param zone   the zone whose hologram should be removed
     * @param server the Minecraft server
     */
    public static void removeHologram(Region zone, MinecraftServer server) {
        if (zone == null || server == null) return;

        SaleConfig sale = zone.getSaleConfig();
        if (sale == null || sale.getHologramUuid() == null) return;

        UUID entityUuid;
        try {
            entityUuid = UUID.fromString(sale.getHologramUuid());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[CheeseGuard] Invalid hologram UUID: {}", sale.getHologramUuid());
            sale.setHologramUuid(null);
            return;
        }

        // Try to find the entity in the zone's world
        ServerWorld world = getWorldForZone(zone, server);
        if (world != null) {
            Entity entity = world.getEntity(entityUuid);
            if (entity != null) {
                entity.discard();
                LOGGER.info("[CheeseGuard] Removed sale hologram for zone '{}'", zone.getName());
            } else {
                LOGGER.debug("[CheeseGuard] Hologram entity not found for zone '{}' (may have been unloaded or removed externally)", zone.getName());
            }
        }

        sale.setHologramUuid(null);
    }

    /**
     * Update the text on an existing hologram entity.
     *
     * @param zone   the zone whose hologram text should be updated
     * @param server the Minecraft server
     */
    public static void updateHologramText(Region zone, MinecraftServer server) {
        if (zone == null || server == null) return;

        SaleConfig sale = zone.getSaleConfig();
        if (sale == null || sale.getHologramUuid() == null) return;

        UUID entityUuid;
        try {
            entityUuid = UUID.fromString(sale.getHologramUuid());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[CheeseGuard] Invalid hologram UUID for text update: {}", sale.getHologramUuid());
            sale.setHologramUuid(null);
            return;
        }

        ServerWorld world = getWorldForZone(zone, server);
        if (world == null) return;

        Entity entity = world.getEntity(entityUuid);
        if (entity instanceof DisplayEntity.TextDisplayEntity textDisplay) {
            Text text = buildHologramText(zone);
            setHologramText(textDisplay, text);
            LOGGER.debug("[CheeseGuard] Updated hologram text for zone '{}'", zone.getName());
        } else {
            LOGGER.warn("[CheeseGuard] Hologram entity not found for zone '{}', clearing UUID", zone.getName());
            sale.setHologramUuid(null);
        }
    }

    // ── Sale sign ───────────────────────────────────────────────────────────

    /**
     * Create an oak sign ItemStack with custom NBT marking it as a sale sign for the given zone.
     *
     * @param zone the zone this sale sign is for
     * @return the configured ItemStack
     */
    public static ItemStack createSaleSign(Region zone, MinecraftServer server) {
        SaleConfig sale = zone.getSaleConfig();
        Item signItem = getSignItem(sale != null ? sale.getSignType() : "oak");
        ItemStack sign = new ItemStack(signItem);

        // Set custom name
        sign.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal("Sale Sign: " + zone.getName()).formatted(Formatting.GOLD));

        // Set custom NBT data with zone ID
        NbtCompound customData = new NbtCompound();
        customData.putString("CheeseGuardSale", zone.getId());
        sign.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(customData));

        // Pre-fill sign text via BLOCK_ENTITY_DATA so it appears when placed
        try {
            NbtCompound beData = new NbtCompound();
            beData.putString("id", "minecraft:sign");

            var reg = server.getRegistryManager();

            // Front text with sale info
            NbtCompound frontText = new NbtCompound();
            NbtList messages = new NbtList();
            // Line 0: translated header
            messages.add(NbtString.of(Text.Serialization.toJsonString(
                Text.translatable("cheeseguard.sale.sign.header").formatted(Formatting.DARK_GREEN), reg)));
            // Line 1: custom text (signLine1 or zone name)
            String line1 = (sale != null && sale.getSignLine1() != null && !sale.getSignLine1().isBlank())
                ? sale.getSignLine1() : zone.getName();
            messages.add(NbtString.of(Text.Serialization.toJsonString(
                Text.literal(line1).formatted(Formatting.DARK_AQUA), reg)));
            // Line 2: custom text (signLine2 or empty)
            String line2 = (sale != null && sale.getSignLine2() != null) ? sale.getSignLine2() : "";
            messages.add(NbtString.of(Text.Serialization.toJsonString(
                Text.literal(line2).formatted(Formatting.DARK_AQUA), reg)));
            // Line 3: price
            messages.add(NbtString.of(Text.Serialization.toJsonString(
                Text.literal(sale != null ? formatPrice(sale) : "").formatted(Formatting.GOLD), reg)));
            frontText.put("messages", messages);
            frontText.putString("color", "black");
            frontText.putBoolean("has_glowing_text", false);
            beData.put("front_text", frontText);

            // Back text — line 0 stores zone ID for detection
            NbtCompound backText = new NbtCompound();
            NbtList backMsgs = new NbtList();
            backMsgs.add(NbtString.of(Text.Serialization.toJsonString(
                Text.literal(zone.getId()), reg)));
            String emptyJson = Text.Serialization.toJsonString(Text.empty(), reg);
            for (int i = 1; i < 4; i++) backMsgs.add(NbtString.of(emptyJson));
            backText.put("messages", backMsgs);
            backText.putString("color", "black");
            backText.putBoolean("has_glowing_text", false);
            beData.put("back_text", backText);

            // Wax the sign so text can't be edited
            beData.putBoolean("is_waxed", true);

            sign.set(DataComponentTypes.BLOCK_ENTITY_DATA, NbtComponent.of(beData));
        } catch (Exception e) {
            LOGGER.warn("[CheeseGuard] Failed to set sign text data: {}", e.getMessage());
        }

        return sign;
    }

    /**
     * Get the sign Item for a given wood type string.
     */
    private static Item getSignItem(String signType) {
        if (signType == null) return Items.OAK_SIGN;
        return switch (signType.toLowerCase()) {
            case "spruce" -> Items.SPRUCE_SIGN;
            case "birch" -> Items.BIRCH_SIGN;
            case "jungle" -> Items.JUNGLE_SIGN;
            case "acacia" -> Items.ACACIA_SIGN;
            case "dark_oak" -> Items.DARK_OAK_SIGN;
            case "mangrove" -> Items.MANGROVE_SIGN;
            case "cherry" -> Items.CHERRY_SIGN;
            case "bamboo" -> Items.BAMBOO_SIGN;
            case "crimson" -> Items.CRIMSON_SIGN;
            case "warped" -> Items.WARPED_SIGN;
            default -> Items.OAK_SIGN;
        };
    }

    // ── Chat prompt ─────────────────────────────────────────────────────────

    /**
     * Send a purchase prompt to a player with a clickable Buy button.
     *
     * @param player the player to send the prompt to
     * @param zone   the zone for sale
     */
    public static void sendPurchasePrompt(ServerPlayerEntity player, Region zone) {
        if (player == null || zone == null) return;

        SaleConfig sale = zone.getSaleConfig();
        if (sale == null || !sale.isEnabled()) return;

        if (sale.isSold()) {
            player.sendMessage(Text.translatable("cheeseguard.sale.already_sold", zone.getName())
                .formatted(Formatting.RED), false);
            return;
        }

        // Info line: zone name + price
        player.sendMessage(Text.translatable("cheeseguard.sale.prompt",
            zone.getName(), formatPrice(sale)).formatted(Formatting.GOLD), false);

        // Clickable buy button
        Text buyButton = Text.translatable("cheeseguard.sale.clickBuy")
            .styled(s -> s
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/cg buy " + zone.getId() + " confirm"))
                .withColor(Formatting.GREEN)
                .withBold(true));

        player.sendMessage(Text.literal(" ").append(buyButton), false);
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Count how many of the specified item the player has in their inventory.
     */
    private static int countItems(ServerPlayerEntity player, String itemId) {
        if (player == null || itemId == null || itemId.isEmpty()) return 0;

        Item item = Registries.ITEM.get(Identifier.of(itemId));
        if (item == Items.AIR) return 0;

        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Remove items from a player's inventory.
     *
     * @return true if enough items were found and removed
     */
    private static boolean deductItems(ServerPlayerEntity player, String itemId, int count) {
        if (player == null || itemId == null || itemId.isEmpty() || count <= 0) return false;

        Item item = Registries.ITEM.get(Identifier.of(itemId));
        if (item == Items.AIR) return false;

        // Verify we have enough first
        if (countItems(player, itemId) < count) return false;

        int remaining = count;
        for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.decrement(take);
                remaining -= take;
            }
        }
        return remaining <= 0;
    }

    /**
     * Apply text, billboard mode, and background to a TextDisplayEntity via reflection.
     */
    private static void applyTextDisplaySettings(DisplayEntity.TextDisplayEntity entity, Text text) {
        initReflection();
        try {
            if (setTextMethod != null) setTextMethod.invoke(entity, text);
            if (setBillboardModeMethod != null) setBillboardModeMethod.invoke(entity, DisplayEntity.BillboardMode.CENTER);
            if (setBackgroundMethod != null) setBackgroundMethod.invoke(entity, 0x40000000);
        } catch (Exception e) {
            LOGGER.warn("[CheeseGuard] Failed to configure TextDisplayEntity via reflection", e);
        }
    }

    /**
     * Set the text on a TextDisplayEntity via reflection.
     */
    private static void setHologramText(DisplayEntity.TextDisplayEntity entity, Text text) {
        initReflection();
        try {
            if (setTextMethod != null) setTextMethod.invoke(entity, text);
        } catch (Exception e) {
            LOGGER.warn("[CheeseGuard] Failed to set hologram text via reflection", e);
        }
    }

    /**
     * Get the ServerWorld for a zone based on its world ID string.
     */
    private static ServerWorld getWorldForZone(Region zone, MinecraftServer server) {
        if (zone.getWorld() == null) return null;
        Identifier worldId = Identifier.of(zone.getWorld());
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, worldId);
        return server.getWorld(worldKey);
    }

    /**
     * Build the Text displayed on a hologram for a zone sale.
     */
    private static Text buildHologramText(Region zone) {
        SaleConfig sale = zone.getSaleConfig();
        List<String> signText = sale.getSignText();

        // If custom sign text is provided, use it
        if (signText != null && !signText.isEmpty()) {
            Text result = Text.empty();
            for (int i = 0; i < signText.size(); i++) {
                if (i > 0) result = result.copy().append(Text.literal("\n"));
                result = result.copy().append(Text.literal(signText.get(i)));
            }
            // If sold, append SOLD status
            if (sale.isSold()) {
                result = result.copy()
                    .append(Text.literal("\n"))
                    .append(Text.literal("SOLD").formatted(Formatting.RED, Formatting.BOLD));
            }
            return result;
        }

        // Default hologram text
        if (sale.isSold()) {
            return Text.literal(zone.getName()).formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("\n"))
                .append(Text.literal("SOLD").formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal("\n"))
                .append(Text.literal("Owner: " + (sale.getBuyerName() != null ? sale.getBuyerName() : "???"))
                    .formatted(Formatting.GRAY));
        }

        return Text.literal("FOR SALE").formatted(Formatting.GREEN, Formatting.BOLD)
            .append(Text.literal("\n"))
            .append(Text.literal(zone.getName()).formatted(Formatting.GOLD))
            .append(Text.literal("\n"))
            .append(Text.literal(formatPrice(sale)).formatted(Formatting.YELLOW))
            .append(Text.literal("\n"))
            .append(Text.literal("[RIGHT-CLICK TO BUY]").formatted(Formatting.AQUA));
    }
}
