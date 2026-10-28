package com.cheeseitems;

import com.cheeseitems.CustomItemDefinition.CommandAction;
import com.cheeseitems.CustomItemDefinition.EnduranceConfig;
import com.cheeseitems.CustomItemDefinition.PotionEffectEntry;
import com.cheeseitems.CustomItemDefinition.UseConditions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Handles all item interactions: right-click use, drop prevention,
 * death handling (soulbound/disappear), pickup effects, passive effects, cooldowns.
 */
public class UseHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");

    private final ItemManager itemManager;

    // itemId → (playerUUID → lastUseTick)
    private final Map<String, Map<UUID, Long>> cooldowns = new HashMap<>();
    // itemId → lastGlobalUseTick
    private final Map<String, Long> globalCooldowns = new HashMap<>();

    // Soulbound items saved on death, keyed by player UUID
    private final Map<UUID, List<ItemStack>> soulboundStash = new HashMap<>();

    // Delayed tasks (commands + optional actionbar countdown)
    private final List<DelayedTask> delayedTasks = new ArrayList<>();

    // Endurance pools: tag → (playerUUID → state)
    private final Map<String, Map<UUID, EnduranceState>> endurancePools = new HashMap<>();

    private MinecraftServer server;
    private long currentTick = 0;

    public UseHandler(ItemManager itemManager) {
        this.itemManager = itemManager;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    // ── Register all events ──────────────────────────────────────────────────

    public void registerEvents() {
        // Right-click use
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return TypedActionResult.pass(player.getStackInHand(hand));
            if (hand != Hand.MAIN_HAND) return TypedActionResult.pass(player.getStackInHand(hand));
            if (!(player instanceof ServerPlayerEntity sp))
                return TypedActionResult.pass(player.getStackInHand(hand));

            ItemStack held = sp.getStackInHand(Hand.MAIN_HAND);
            CustomItemDefinition def = itemManager.getDefinitionFromStack(held);
            if (def == null) return TypedActionResult.pass(held);
            if (!def.isEnabled()) return TypedActionResult.pass(held);

            TypedActionResult<ItemStack> result = handleUse(sp, held, def);
            // Force inventory sync next tick to override client-side prediction
            // (prevents base item native behavior like Poke Ball consumption)
            final ServerPlayerEntity target = sp;
            server.execute(() -> target.playerScreenHandler.updateToClient());
            return result;
        });

        // Server tick — passive effects + cooldown cleanup + delayed commands
        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            currentTick++;
            if (currentTick % 40 == 0) { // every 2 seconds
                applyPassiveEffects(srv);
            }
            if (currentTick % 1200 == 0) { // every minute, cleanup old cooldowns
                cleanupCooldowns();
            }
            processDelayedTasks(srv);
            processEndurance(srv);
        });
    }

    // ── Right-click handler ──────────────────────────────────────────────────

    private TypedActionResult<ItemStack> handleUse(ServerPlayerEntity player, ItemStack held, CustomItemDefinition def) {
        // Check conditions
        if (!checkConditions(player, def)) {
            player.sendMessage(Text.translatable("cheeseitems.conditions_not_met").formatted(Formatting.RED), true);
            return TypedActionResult.fail(held);
        }

        // Check cooldown
        if (!checkCooldown(player, def)) {
            long remaining = getCooldownRemaining(player, def);
            float seconds = remaining / 20f;
            player.sendMessage(Text.translatable("cheeseitems.cooldown", String.format("%.1f", seconds)).formatted(Formatting.YELLOW), true);
            return TypedActionResult.fail(held);
        }

        // Check & toggle endurance
        EnduranceConfig ec = def.getEndurance();
        if (ec != null && ec.getTag() != null && !ec.getTag().isEmpty()) {
            Map<UUID, EnduranceState> pool = endurancePools
                    .computeIfAbsent(ec.getTag(), k -> new HashMap<>());
            EnduranceState state = pool.get(player.getUuid());

            // Already draining → toggle OFF: deactivate + run depletion commands
            if (state != null && state.draining) {
                state.draining = false;
                executeDepletionCommands(player, state);
                applyCooldown(player, def);
                return TypedActionResult.success(held);
            }

            // Pool empty?
            if (state != null && state.currentValue <= 0) {
                player.sendMessage(Text.translatable("cheeseitems.endurance_depleted").formatted(Formatting.RED), true);
                return TypedActionResult.fail(held);
            }

            // Activate drain
            if (state == null) {
                state = new EnduranceState();
                state.currentValue = ec.getCapacity();
                state.maxCapacity = ec.getCapacity();
                pool.put(player.getUuid(), state);
            }
            state.maxCapacity = ec.getCapacity();
            state.draining = true;
            state.drainPerSecond = ec.getDrainPerSecond();
            state.regenPerSecond = ec.getRegenPerSecond();
            state.displayName = (ec.getDisplayName() != null && !ec.getDisplayName().isEmpty())
                    ? ec.getDisplayName() : "Endurance";
            state.activeItemId = def.getId();
            state.playerUUID = player.getUuid();
            state.onDepletedCommands = ec.getOnDepletedCommands();
        }

        // Apply cooldown
        applyCooldown(player, def);

        // Execute command actions
        for (CommandAction action : def.getUseActions()) {
            executeAction(player, action);
        }

        // Apply on-use effects
        applyEffects(player, def.getOnUseEffects());

        // Decrement uses
        if (def.getMaxUses() > 0) {
            int left = ItemManager.decrementUses(held);
            if (left == 0) {
                // Item consumed
                held.decrement(held.getCount());
                player.sendMessage(Text.translatable("cheeseitems.item_consumed").formatted(Formatting.GRAY), true);
                return TypedActionResult.success(held);
            }
        }

        // Consumable — remove after single use regardless
        if (def.isConsumable()) {
            held.decrement(1);
        }

        return TypedActionResult.success(held);
    }

    // ── Drop prevention ──────────────────────────────────────────────────────

    /**
     * Called from CheeseItemsMod tick or event to check if a drop should be cancelled.
     * Returns true if the drop should be blocked.
     */
    public boolean shouldBlockDrop(ServerPlayerEntity player, ItemStack stack) {
        CustomItemDefinition def = itemManager.getDefinitionFromStack(stack);
        if (def == null) return false;
        if (!def.isDroppable()) {
            player.sendMessage(Text.translatable("cheeseitems.cannot_drop").formatted(Formatting.RED), true);
            return true;
        }
        return false;
    }

    // ── Death handling ───────────────────────────────────────────────────────

    /**
     * Called before death: stash soulbound items and remove disappearOnDeath items.
     */
    public void onPlayerDeath(ServerPlayerEntity player) {
        List<ItemStack> saved = new ArrayList<>();

        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            CustomItemDefinition def = itemManager.getDefinitionFromStack(stack);
            if (def == null) continue;

            if (def.isSoulbound()) {
                saved.add(stack.copy());
                player.getInventory().setStack(i, ItemStack.EMPTY);
            } else if (def.isDisappearOnDeath()) {
                player.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }

        if (!saved.isEmpty()) {
            soulboundStash.put(player.getUuid(), saved);
        }
    }

    /**
     * Called after respawn: restore soulbound items.
     */
    public void onPlayerRespawn(ServerPlayerEntity player) {
        List<ItemStack> saved = soulboundStash.remove(player.getUuid());
        if (saved == null) return;
        for (ItemStack stack : saved) {
            if (!player.getInventory().insertStack(stack)) {
                player.dropItem(stack, false);
            }
        }
    }

    // ── Pickup effects ───────────────────────────────────────────────────────

    /**
     * Called when a player picks up an item — apply onPickupEffects.
     */
    public void onItemPickup(ServerPlayerEntity player, ItemStack stack) {
        CustomItemDefinition def = itemManager.getDefinitionFromStack(stack);
        if (def == null || !def.isEnabled()) return;
        applyEffects(player, def.getOnPickupEffects());
    }

    // ── Passive effects ──────────────────────────────────────────────────────

    private void applyPassiveEffects(MinecraftServer srv) {
        for (ServerPlayerEntity player : srv.getPlayerManager().getPlayerList()) {
            // Check main hand
            checkPassive(player, player.getStackInHand(Hand.MAIN_HAND));
            // Check off hand
            checkPassive(player, player.getStackInHand(Hand.OFF_HAND));
        }
    }

    private void checkPassive(ServerPlayerEntity player, ItemStack stack) {
        CustomItemDefinition def = itemManager.getDefinitionFromStack(stack);
        if (def == null || !def.isEnabled()) return;
        if (def.getPassiveEffects().isEmpty()) return;
        applyEffects(player, def.getPassiveEffects());
    }

    // ── Conditions ───────────────────────────────────────────────────────────

    private boolean checkConditions(ServerPlayerEntity player, CustomItemDefinition def) {
        UseConditions c = def.getUseConditions();
        if (c == null) return true;

        // Dimension check
        if (c.getDimension() != null && !c.getDimension().isEmpty()) {
            String current = player.getWorld().getRegistryKey().getValue().toString();
            if (!current.equals(c.getDimension())) return false;
        }

        // Level check
        if (c.getMinLevel() > 0 && player.experienceLevel < c.getMinLevel()) return false;

        // Required held item (offhand)
        if (c.getRequiredHeldItem() != null && !c.getRequiredHeldItem().isEmpty()) {
            ItemStack offhand = player.getStackInHand(Hand.OFF_HAND);
            String offhandId = Registries.ITEM.getId(offhand.getItem()).toString();
            if (!offhandId.equals(c.getRequiredHeldItem())) return false;
        }

        // Permission check — op level 2 bypass, or LuckPerms if available
        if (c.getPermission() != null && !c.getPermission().isEmpty()) {
            if (!player.hasPermissionLevel(2)) {
                // Try LuckPerms reflection check
                if (!checkLuckPermsPermission(player, c.getPermission())) return false;
            }
        }

        return true;
    }

    private boolean checkLuckPermsPermission(ServerPlayerEntity player, String permission) {
        try {
            Class<?> lpClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object lp = lpClass.getMethod("get").invoke(null);
            Object userManager = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player.getUuid());
            if (user == null) return false;
            Object data = user.getClass().getMethod("getCachedData").invoke(user);
            Object permData = data.getClass().getMethod("getPermissionData").invoke(data);
            Object result = permData.getClass().getMethod("checkPermission", String.class).invoke(permData, permission);
            return result.toString().equals("TRUE");
        } catch (Exception e) {
            return false;
        }
    }

    // ── Cooldowns ────────────────────────────────────────────────────────────

    private boolean checkCooldown(ServerPlayerEntity player, CustomItemDefinition def) {
        // Per-player cooldown
        if (def.getCooldownTicks() > 0) {
            Map<UUID, Long> map = cooldowns.get(def.getId());
            if (map != null) {
                Long last = map.get(player.getUuid());
                if (last != null && (currentTick - last) < def.getCooldownTicks()) return false;
            }
        }
        // Global cooldown
        UseConditions c = def.getUseConditions();
        if (c != null && c.getGlobalCooldownTicks() > 0) {
            Long last = globalCooldowns.get(def.getId());
            if (last != null && (currentTick - last) < c.getGlobalCooldownTicks()) return false;
        }
        return true;
    }

    private long getCooldownRemaining(ServerPlayerEntity player, CustomItemDefinition def) {
        long remaining = 0;
        if (def.getCooldownTicks() > 0) {
            Map<UUID, Long> map = cooldowns.get(def.getId());
            if (map != null) {
                Long last = map.get(player.getUuid());
                if (last != null) remaining = Math.max(remaining, def.getCooldownTicks() - (currentTick - last));
            }
        }
        UseConditions c = def.getUseConditions();
        if (c != null && c.getGlobalCooldownTicks() > 0) {
            Long last = globalCooldowns.get(def.getId());
            if (last != null) remaining = Math.max(remaining, c.getGlobalCooldownTicks() - (currentTick - last));
        }
        return remaining;
    }

    private void applyCooldown(ServerPlayerEntity player, CustomItemDefinition def) {
        if (def.getCooldownTicks() > 0) {
            cooldowns.computeIfAbsent(def.getId(), k -> new HashMap<>())
                    .put(player.getUuid(), currentTick);
        }
        UseConditions c = def.getUseConditions();
        if (c != null && c.getGlobalCooldownTicks() > 0) {
            globalCooldowns.put(def.getId(), currentTick);
        }
    }

    private void cleanupCooldowns() {
        cooldowns.values().forEach(map ->
            map.entrySet().removeIf(e -> (currentTick - e.getValue()) > 72000) // 1 hour max
        );
        cooldowns.entrySet().removeIf(e -> e.getValue().isEmpty());
        globalCooldowns.entrySet().removeIf(e -> (currentTick - e.getValue()) > 72000);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void applyEffects(ServerPlayerEntity player, List<PotionEffectEntry> effects) {
        for (PotionEffectEntry effect : effects) {
            Identifier id = Identifier.of(effect.getEffectId());
            Optional<RegistryEntry.Reference<StatusEffect>> entry = Registries.STATUS_EFFECT.getEntry(id);
            if (entry.isEmpty()) continue;
            player.addStatusEffect(new StatusEffectInstance(
                    entry.get(),
                    effect.getDurationTicks(),
                    effect.getAmplifier(),
                    false, true, true
            ));
        }
    }

    private void executeAction(ServerPlayerEntity player, CommandAction action) {
        if (server == null) return;
        String playerName = player.getName().getString();
        String cmd = action.getCommand().replace("{player}", playerName);
        ServerCommandSource source = server.getCommandSource();
        for (int i = 0; i < Math.max(1, action.getCount()); i++) {
            server.getCommandManager().executeWithPrefix(source, cmd);
        }
        if (action.getFeedback() != null && !action.getFeedback().isEmpty()) {
            String msg = action.getFeedback().replace("{player}", playerName);
            player.sendMessage(Text.literal(msg).formatted(Formatting.GREEN), true);
        }
        // Delayed follow-up command
        if (action.getDelayCommand() != null && !action.getDelayCommand().isEmpty() && action.getDelaySeconds() > 0) {
            String delayCmd = action.getDelayCommand().replace("{player}", playerName);
            int delaySec = action.getDelaySeconds();
            DelayedTask task = new DelayedTask(delayCmd, currentTick + (long) delaySec * 20);
            if (action.isShowTimer()) {
                String label = (action.getTimerLabel() != null && !action.getTimerLabel().isEmpty())
                        ? action.getTimerLabel() : "Timer";
                task.timerPlayerUUID = player.getUuid();
                task.timerLabel = label;
                task.timerTotalSeconds = delaySec;
                task.timerStartTick = currentTick;
            }
            synchronized (delayedTasks) {
                delayedTasks.add(task);
            }
        }
    }

    private void processDelayedTasks(MinecraftServer srv) {
        synchronized (delayedTasks) {
            Iterator<DelayedTask> it = delayedTasks.iterator();
            while (it.hasNext()) {
                DelayedTask task = it.next();

                // Actionbar countdown (every 20 ticks = 1 second)
                if (task.timerPlayerUUID != null && currentTick % 20 == 0 && currentTick < task.executionTick) {
                    ServerPlayerEntity p = srv.getPlayerManager().getPlayer(task.timerPlayerUUID);
                    if (p != null) {
                        int remaining = (int) ((task.executionTick - currentTick) / 20);
                        String bar = task.timerLabel + ": " + formatCountdown(remaining);
                        p.sendMessage(Text.literal(bar).formatted(Formatting.GOLD), true);
                    }
                }

                // Execute command when time is up
                if (currentTick >= task.executionTick) {
                    srv.getCommandManager().executeWithPrefix(srv.getCommandSource(), task.command);
                    it.remove();
                }
            }
        }
    }

    private static String formatCountdown(int seconds) {
        if (seconds >= 60) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }

    // ── Endurance system ─────────────────────────────────────────────────

    private void processEndurance(MinecraftServer srv) {
        for (Map.Entry<String, Map<UUID, EnduranceState>> tagEntry : endurancePools.entrySet()) {
            Iterator<Map.Entry<UUID, EnduranceState>> it = tagEntry.getValue().entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, EnduranceState> entry = it.next();
                EnduranceState state = entry.getValue();
                ServerPlayerEntity player = srv.getPlayerManager().getPlayer(state.playerUUID);
                if (player == null) continue; // player offline, will be cleaned up on disconnect

                if (state.draining) {
                    // Drain
                    state.currentValue -= state.drainPerSecond / 20.0f;
                    if (state.currentValue <= 0) {
                        state.currentValue = 0;
                        state.draining = false;
                        // Execute depletion commands
                        executeDepletionCommands(player, state);
                    }
                } else {
                    // Regen
                    if (state.currentValue < state.maxCapacity) {
                        state.currentValue += state.regenPerSecond / 20.0f;
                        if (state.currentValue >= state.maxCapacity) {
                            state.currentValue = state.maxCapacity;
                        }
                    }
                }

                // Actionbar display (every 5 ticks = 4x/sec)
                if (currentTick % 5 == 0) {
                    if (state.draining || state.currentValue < state.maxCapacity) {
                        sendEnduranceBar(player, state);
                    }
                }

                // Cleanup fully regenerated states
                if (!state.draining && state.currentValue >= state.maxCapacity) {
                    it.remove();
                }
            }
        }
        // Remove empty tag maps
        endurancePools.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private void executeDepletionCommands(ServerPlayerEntity player, EnduranceState state) {
        if (server == null || state.onDepletedCommands == null) return;
        String playerName = player.getName().getString();
        for (CommandAction action : state.onDepletedCommands) {
            String cmd = action.getCommand().replace("{player}", playerName);
            ServerCommandSource source = server.getCommandSource();
            for (int i = 0; i < Math.max(1, action.getCount()); i++) {
                server.getCommandManager().executeWithPrefix(source, cmd);
            }
            if (action.getFeedback() != null && !action.getFeedback().isEmpty()) {
                String msg = action.getFeedback().replace("{player}", playerName);
                player.sendMessage(Text.literal(msg).formatted(Formatting.RED), true);
            }
        }
    }

    private void sendEnduranceBar(ServerPlayerEntity player, EnduranceState state) {
        float pct = state.currentValue / state.maxCapacity;

        // Custom font bar (PNG images 0-10 mapped to \uE000-\uE00A in default font)
        if (CheeseItemsMod.INSTANCE.getConfig().hasBarImages()) {
            int level = Math.round(pct * 10);
            level = Math.max(0, Math.min(10, level));
            char barChar = (char) (0xE000 + level);
            player.sendMessage(Text.literal(String.valueOf(barChar)), true);
            return;
        }

        // Fallback: text-based bar
        int total = 10;
        int filled = Math.round(pct * total);
        int empty = total - filled;

        Formatting color;
        if (pct > 0.5f) color = Formatting.GREEN;
        else if (pct > 0.25f) color = Formatting.YELLOW;
        else color = Formatting.RED;

        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < filled; i++) bar.append('\u25C6'); // ◆
        for (int i = 0; i < empty; i++) bar.append('\u25C7');  // ◇

        player.sendMessage(Text.literal(bar.toString()).formatted(color), true);
    }


    /**
     * Called when a player disconnects — execute depletion commands if draining, then cleanup.
     */
    public void onPlayerDisconnect(UUID playerUUID) {
        for (Map<UUID, EnduranceState> pool : endurancePools.values()) {
            EnduranceState state = pool.remove(playerUUID);
            if (state != null && state.draining && server != null) {
                // Execute depletion commands for active drains
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUUID);
                if (player != null) {
                    executeDepletionCommands(player, state);
                }
            }
        }
        endurancePools.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private static class EnduranceState {
        float currentValue;
        float maxCapacity;
        float drainPerSecond;
        float regenPerSecond;
        boolean draining;
        String displayName;
        String activeItemId;
        UUID playerUUID;
        List<CommandAction> onDepletedCommands;
    }

    private static class DelayedTask {
        final String command;
        final long executionTick;
        UUID timerPlayerUUID;
        String timerLabel;
        int timerTotalSeconds;
        long timerStartTick;

        DelayedTask(String command, long executionTick) {
            this.command = command;
            this.executionTick = executionTick;
        }
    }
}
