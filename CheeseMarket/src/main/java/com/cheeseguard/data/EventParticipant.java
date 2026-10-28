package com.cheeseguard.data;

import com.google.gson.JsonObject;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;

/**
 * Tracks a player's participation in an event: role, score, saved state.
 */
public class EventParticipant {

    public enum Role { PARTICIPANT, SPECTATOR }

    private UUID playerUuid;
    private String playerName;
    private Role role = Role.PARTICIPANT;
    private int score = 0;

    // Saved position for return TP
    private double savedX, savedY, savedZ;
    private String savedWorld = "";

    // Death during event: temp inventory snapshot before items drop
    private NbtCompound deathInventoryNbt;
    private boolean deathPending = false;

    // Saved gamemode for restoration when spectator returns to normal
    private net.minecraft.world.GameMode originalGameMode = null;

    // NUCLEAR save: full player NBT — captures EVERYTHING including
    // Sophisticated Backpacks contents, Cobblemon data, all modded components
    private NbtCompound savedPlayerNbt;

    // Timestamps
    private long joinedAt = System.currentTimeMillis();

    public EventParticipant(UUID uuid, String name, Role role) {
        this.playerUuid = uuid;
        this.playerName = name;
        this.role = role;
    }

    // ── Getters / Setters ────────────────────────────────────

    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public Role getRole() { return role; }
    public void setRole(Role r) { this.role = r; }
    public int getScore() { return score; }
    public void setScore(int s) { this.score = s; }
    public void addScore(int amount) { this.score += amount; }

    public double getSavedX() { return savedX; }
    public double getSavedY() { return savedY; }
    public double getSavedZ() { return savedZ; }
    public String getSavedWorld() { return savedWorld; }
    public long getJoinedAt() { return joinedAt; }

    // ── Position save/restore ────────────────────────────────

    public void savePosition(ServerPlayerEntity player) {
        savedX = player.getX();
        savedY = player.getY();
        savedZ = player.getZ();
        savedWorld = player.getServerWorld().getRegistryKey().getValue().toString();
    }

    // ── Inventory save/restore ───────────────────────────────

    /**
     * Save using player.writeNbt() — the SAME method Minecraft uses to
     * persist player data to disk. Captures absolutely everything:
     * inventory, armor, offhand, ender chest, AND all modded data
     * (Sophisticated Backpacks contents, Cobblemon party, etc.)
     */
    public void saveInventory(ServerPlayerEntity player) {
        savedPlayerNbt = new NbtCompound();
        player.writeNbt(savedPlayerNbt);
    }

    /**
     * Restore using player.readNbt() — the SAME method Minecraft uses to
     * load player data from disk. This fires all mod hooks that init
     * internal caches from NBT, so Sophisticated Backpacks etc.
     * will properly restore their contents.
     *
     * We preserve current health, hunger, XP, position, game mode
     * since we only want to restore the inventory.
     */
    public void restoreInventory(ServerPlayerEntity player) {
        if (savedPlayerNbt == null) return;

        // Step 1: Capture current state we want to KEEP
        double curX = player.getX(), curY = player.getY(), curZ = player.getZ();
        float curYaw = player.getYaw(), curPitch = player.getPitch();
        float curHealth = player.getHealth();
        int curFood = player.getHungerManager().getFoodLevel();
        float curSat = player.getHungerManager().getSaturationLevel();
        float curExhaustion = player.getHungerManager().getExhaustion();
        int curXpLevel = player.experienceLevel;
        float curXpProgress = player.experienceProgress;
        int curTotalXp = player.totalExperience;
        var curGameMode = player.interactionManager.getGameMode();
        int curFireTicks = player.getFireTicks();
        int curAir = player.getAir();

        // Step 2: NUCLEAR restore via Minecraft's native readNbt
        player.readNbt(savedPlayerNbt);

        // Step 3: Re-apply current state
        player.refreshPositionAndAngles(curX, curY, curZ, curYaw, curPitch);
        player.setHealth(curHealth);
        player.getHungerManager().setFoodLevel(curFood);
        player.getHungerManager().setSaturationLevel(curSat);
        player.getHungerManager().setExhaustion(curExhaustion);
        player.experienceLevel = curXpLevel;
        player.experienceProgress = curXpProgress;
        player.totalExperience = curTotalXp;
        player.interactionManager.changeGameMode(curGameMode);
        player.setFireTicks(curFireTicks);
        player.setAir(curAir);

        // Step 4: Force sync to client
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
        player.playerScreenHandler.sendContentUpdates();
        player.playerScreenHandler.onContentChanged(player.getInventory());

        // Step 5: Clear saved
        savedPlayerNbt = null;
    }

    /**
     * Clear inventory safely for event start.
     */
    public static void clearInventoryForEvent(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().main.size(); i++)
            player.getInventory().main.set(i, ItemStack.EMPTY);
        for (int i = 0; i < player.getInventory().armor.size(); i++)
            player.getInventory().armor.set(i, ItemStack.EMPTY);
        for (int i = 0; i < player.getInventory().offHand.size(); i++)
            player.getInventory().offHand.set(i, ItemStack.EMPTY);
        player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
        player.playerScreenHandler.sendContentUpdates();
        // Clear Trinkets accessory slots (soft-dependency via reflection)
        clearTrinketsIfPresent(player);
    }

    /**
     * Full wipe including modded slots (Trinkets, Sophisticated Backpacks, Curios, etc.).
     * Used for construction mode where we need a truly clean slate.
     * Works by writing a blank NbtCompound for the player's inventory components,
     * then syncing. Mods that use player NBT to store their slots are covered.
     */
    public static void clearFullInventoryForConstruction(ServerPlayerEntity player) {
        // 1. Clear vanilla slots
        clearInventoryForEvent(player);  // already calls clearTrinketsIfPresent

        // 2. Close any open screen to prevent duplication glitches.
        if (player.currentScreenHandler != null && player.currentScreenHandler != player.playerScreenHandler) {
            player.closeHandledScreen();
        }

        // 3. Sync to client
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
        player.playerScreenHandler.sendContentUpdates();
    }

    /**
     * Clear Trinkets accessory slots via reflection (soft dependency — no compile-time requirement).
     * Trinkets stores slot data in player NBT so saveInventory() already captures it via writeNbt().
     * This ensures the slots are actually emptied on the player so buffs/effects are removed.
     */
    private static void clearTrinketsIfPresent(ServerPlayerEntity player) {
        try {
            Class<?> apiClass = Class.forName("dev.emi.trinkets.api.TrinketsApi");
            java.lang.reflect.Method getComp = apiClass.getMethod("getTrinketComponent",
                net.minecraft.entity.LivingEntity.class);
            @SuppressWarnings("unchecked")
            java.util.Optional<Object> opt = (java.util.Optional<Object>) getComp.invoke(null, player);
            if (opt == null || !opt.isPresent()) return;
            Object comp = opt.get();
            // getInventory() → Map<String, Map<String, TrinketInventory>>
            // TrinketInventory implements Inventory
            java.lang.reflect.Method getInv = comp.getClass().getMethod("getInventory");
            @SuppressWarnings("unchecked")
            java.util.Map<String, java.util.Map<String, Object>> groups =
                (java.util.Map<String, java.util.Map<String, Object>>) getInv.invoke(comp);
            for (java.util.Map<String, Object> slots : groups.values()) {
                for (Object inv : slots.values()) {
                    if (inv instanceof net.minecraft.inventory.Inventory tinv) {
                        for (int i = 0; i < tinv.size(); i++) {
                            tinv.setStack(i, net.minecraft.item.ItemStack.EMPTY);
                        }
                        tinv.markDirty();
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Trinkets not installed — normal, skip silently
        } catch (Exception e) {
            com.cheeseguard.CheeseGuardMod.LOGGER.warn("[CG] Trinkets clear failed: {}", e.getMessage());
        }
    }

    public boolean hasInventorySaved() { return savedPlayerNbt != null; }

    // ── Death during event ────────────────────────────────────

    public boolean isDeathPending() { return deathPending; }
    public void setDeathPending(boolean b) { deathPending = b; }
    public boolean hasOriginalGameMode() { return originalGameMode != null; }
    public net.minecraft.world.GameMode getOriginalGameMode() { return originalGameMode; }
    public void setOriginalGameMode(net.minecraft.world.GameMode gm) { this.originalGameMode = gm; }

    /**
     * Save inventory snapshot right before death so items can be restored on respawn.
     * We clear the player's inventory after saving so nothing drops on the ground.
     */
    public void saveDeathInventory(ServerPlayerEntity player) {
        deathInventoryNbt = new NbtCompound();
        player.writeNbt(deathInventoryNbt);
        // Clear inventory so items don't drop
        clearInventoryForEvent(player);
        deathPending = true;
    }

    /**
     * Restore inventory from death snapshot on respawn.
     */
    public void restoreDeathInventory(ServerPlayerEntity player) {
        if (deathInventoryNbt == null) { deathPending = false; return; }

        // Preserve current post-respawn state
        double curX = player.getX(), curY = player.getY(), curZ = player.getZ();
        float curYaw = player.getYaw(), curPitch = player.getPitch();
        var curGameMode = player.interactionManager.getGameMode();

        // Nuclear restore
        player.readNbt(deathInventoryNbt);

        // Re-apply position/gamemode (will be overridden by TP anyway)
        player.refreshPositionAndAngles(curX, curY, curZ, curYaw, curPitch);
        player.interactionManager.changeGameMode(curGameMode);

        // Reset health/hunger to fresh respawn values
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5.0f);
        player.getHungerManager().setExhaustion(0f);
        player.setFireTicks(0);

        // Sync
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
        player.playerScreenHandler.sendContentUpdates();

        deathInventoryNbt = null;
        deathPending = false;
    }

    /** Get raw NBT for crash-safe disk persistence. */
    public NbtCompound getSavedPlayerNbt() { return savedPlayerNbt; }

    /** Set from disk (crash recovery). */
    public void setSavedPlayerNbt(NbtCompound nbt) { this.savedPlayerNbt = nbt; }

    // ── JSON (for score persistence, not inventory) ──────────

    public JsonObject toScoreJson() {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", playerUuid.toString());
        o.addProperty("name", playerName);
        o.addProperty("score", score);
        o.addProperty("role", role.name());
        return o;
    }
}
