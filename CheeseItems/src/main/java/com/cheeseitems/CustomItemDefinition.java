package com.cheeseitems;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Full definition of a custom item: identity, appearance, flags, effects, actions, conditions.
 * Serialized to/from JSON for persistence in config/cheeseitems.json.
 */
public class CustomItemDefinition {

    // ── Identity ─────────────────────────────────────────────────────────────
    private String id;
    private String displayName;
    private String nameColor;      // hex "#FF5555"
    private String baseItem;       // "minecraft:paper"

    // ── Appearance ───────────────────────────────────────────────────────────
    private List<String> lore;
    private String loreColor;      // hex "#AAAAAA"
    private boolean glowing;
    private int dyeColor;          // -1 = none, else RGB int

    // ── Flags ────────────────────────────────────────────────────────────────
    private boolean enabled;
    private int maxStackSize;
    private boolean droppable;
    private boolean tradeable;
    private boolean placeable;
    private boolean consumable;
    private boolean soulbound;
    private boolean disappearOnDeath;
    private boolean unbreakable;

    // ── Durability / Cooldown ────────────────────────────────────────────────
    private int maxUses;           // 0 = infinite
    private int cooldownTicks;     // 0 = no cooldown

    // ── Effects ──────────────────────────────────────────────────────────────
    private List<PotionEffectEntry> onUseEffects;
    private List<PotionEffectEntry> passiveEffects;
    private List<PotionEffectEntry> onPickupEffects;

    // ── Actions ──────────────────────────────────────────────────────────────
    private List<CommandAction> useActions;

    // ── Conditions ───────────────────────────────────────────────────────────
    private UseConditions useConditions;

    // ── Endurance ──────────────────────────────────────────────────────────
    private EnduranceConfig endurance;  // null = no endurance system

    // ── Constructor ──────────────────────────────────────────────────────────

    public CustomItemDefinition(String id) {
        this.id = id;
        this.displayName = "Custom Item";
        this.nameColor = "#FFFFFF";
        this.baseItem = "minecraft:paper";
        this.lore = new ArrayList<>();
        this.loreColor = "#AAAAAA";
        this.glowing = false;
        this.dyeColor = -1;
        this.enabled = true;
        this.maxStackSize = 64;
        this.droppable = true;
        this.tradeable = true;
        this.placeable = false;
        this.consumable = false;
        this.soulbound = false;
        this.disappearOnDeath = false;
        this.unbreakable = false;
        this.maxUses = 0;
        this.cooldownTicks = 0;
        this.onUseEffects = new ArrayList<>();
        this.passiveEffects = new ArrayList<>();
        this.onPickupEffects = new ArrayList<>();
        this.useActions = new ArrayList<>();
        this.useConditions = new UseConditions();
        this.endurance = null;
    }

    // ── Serialization ────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("displayName", displayName);
        o.addProperty("nameColor", nameColor);
        o.addProperty("baseItem", baseItem);

        JsonArray loreArr = new JsonArray();
        for (String line : lore) loreArr.add(line);
        o.add("lore", loreArr);
        o.addProperty("loreColor", loreColor);
        o.addProperty("glowing", glowing);
        o.addProperty("dyeColor", dyeColor);

        o.addProperty("enabled", enabled);
        o.addProperty("maxStackSize", maxStackSize);
        o.addProperty("droppable", droppable);
        o.addProperty("tradeable", tradeable);
        o.addProperty("placeable", placeable);
        o.addProperty("consumable", consumable);
        o.addProperty("soulbound", soulbound);
        o.addProperty("disappearOnDeath", disappearOnDeath);
        o.addProperty("unbreakable", unbreakable);

        o.addProperty("maxUses", maxUses);
        o.addProperty("cooldownTicks", cooldownTicks);

        o.add("onUseEffects", serializeEffects(onUseEffects));
        o.add("passiveEffects", serializeEffects(passiveEffects));
        o.add("onPickupEffects", serializeEffects(onPickupEffects));

        JsonArray actArr = new JsonArray();
        for (CommandAction a : useActions) actArr.add(a.toJson());
        o.add("useActions", actArr);

        o.add("useConditions", useConditions.toJson());

        if (endurance != null) o.add("endurance", endurance.toJson());

        return o;
    }

    public static CustomItemDefinition fromJson(JsonObject o) {
        String id = o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString().substring(0, 8);
        CustomItemDefinition def = new CustomItemDefinition(id);

        if (o.has("displayName")) def.displayName = o.get("displayName").getAsString();
        if (o.has("nameColor")) def.nameColor = o.get("nameColor").getAsString();
        if (o.has("baseItem")) def.baseItem = o.get("baseItem").getAsString();

        if (o.has("lore")) {
            def.lore.clear();
            for (JsonElement e : o.getAsJsonArray("lore")) def.lore.add(e.getAsString());
        }
        if (o.has("loreColor")) def.loreColor = o.get("loreColor").getAsString();
        if (o.has("glowing")) def.glowing = o.get("glowing").getAsBoolean();
        if (o.has("dyeColor")) def.dyeColor = o.get("dyeColor").getAsInt();

        if (o.has("enabled")) def.enabled = o.get("enabled").getAsBoolean();
        if (o.has("maxStackSize")) def.maxStackSize = o.get("maxStackSize").getAsInt();
        if (o.has("droppable")) def.droppable = o.get("droppable").getAsBoolean();
        if (o.has("tradeable")) def.tradeable = o.get("tradeable").getAsBoolean();
        if (o.has("placeable")) def.placeable = o.get("placeable").getAsBoolean();
        if (o.has("consumable")) def.consumable = o.get("consumable").getAsBoolean();
        if (o.has("soulbound")) def.soulbound = o.get("soulbound").getAsBoolean();
        if (o.has("disappearOnDeath")) def.disappearOnDeath = o.get("disappearOnDeath").getAsBoolean();
        if (o.has("unbreakable")) def.unbreakable = o.get("unbreakable").getAsBoolean();

        if (o.has("maxUses")) def.maxUses = o.get("maxUses").getAsInt();
        if (o.has("cooldownTicks")) def.cooldownTicks = o.get("cooldownTicks").getAsInt();

        if (o.has("onUseEffects")) def.onUseEffects = deserializeEffects(o.getAsJsonArray("onUseEffects"));
        if (o.has("passiveEffects")) def.passiveEffects = deserializeEffects(o.getAsJsonArray("passiveEffects"));
        if (o.has("onPickupEffects")) def.onPickupEffects = deserializeEffects(o.getAsJsonArray("onPickupEffects"));

        if (o.has("useActions")) {
            def.useActions.clear();
            for (JsonElement e : o.getAsJsonArray("useActions"))
                def.useActions.add(CommandAction.fromJson(e.getAsJsonObject()));
        }
        if (o.has("useConditions")) def.useConditions = UseConditions.fromJson(o.getAsJsonObject("useConditions"));

        if (o.has("endurance") && !o.get("endurance").isJsonNull())
            def.endurance = EnduranceConfig.fromJson(o.getAsJsonObject("endurance"));

        return def;
    }

    private static JsonArray serializeEffects(List<PotionEffectEntry> list) {
        JsonArray arr = new JsonArray();
        for (PotionEffectEntry e : list) arr.add(e.toJson());
        return arr;
    }

    private static List<PotionEffectEntry> deserializeEffects(JsonArray arr) {
        List<PotionEffectEntry> list = new ArrayList<>();
        for (JsonElement e : arr) list.add(PotionEffectEntry.fromJson(e.getAsJsonObject()));
        return list;
    }

    // ── Merge update (for PUT partial update) ────────────────────────────────

    public void mergeFrom(JsonObject o) {
        if (o.has("displayName")) this.displayName = o.get("displayName").getAsString();
        if (o.has("nameColor")) this.nameColor = o.get("nameColor").getAsString();
        if (o.has("baseItem")) this.baseItem = o.get("baseItem").getAsString();

        if (o.has("lore")) {
            this.lore.clear();
            for (JsonElement e : o.getAsJsonArray("lore")) this.lore.add(e.getAsString());
        }
        if (o.has("loreColor")) this.loreColor = o.get("loreColor").getAsString();
        if (o.has("glowing")) this.glowing = o.get("glowing").getAsBoolean();
        if (o.has("dyeColor")) this.dyeColor = o.get("dyeColor").getAsInt();

        if (o.has("enabled")) this.enabled = o.get("enabled").getAsBoolean();
        if (o.has("maxStackSize")) this.maxStackSize = o.get("maxStackSize").getAsInt();
        if (o.has("droppable")) this.droppable = o.get("droppable").getAsBoolean();
        if (o.has("tradeable")) this.tradeable = o.get("tradeable").getAsBoolean();
        if (o.has("placeable")) this.placeable = o.get("placeable").getAsBoolean();
        if (o.has("consumable")) this.consumable = o.get("consumable").getAsBoolean();
        if (o.has("soulbound")) this.soulbound = o.get("soulbound").getAsBoolean();
        if (o.has("disappearOnDeath")) this.disappearOnDeath = o.get("disappearOnDeath").getAsBoolean();
        if (o.has("unbreakable")) this.unbreakable = o.get("unbreakable").getAsBoolean();

        if (o.has("maxUses")) this.maxUses = o.get("maxUses").getAsInt();
        if (o.has("cooldownTicks")) this.cooldownTicks = o.get("cooldownTicks").getAsInt();

        if (o.has("onUseEffects")) this.onUseEffects = deserializeEffects(o.getAsJsonArray("onUseEffects"));
        if (o.has("passiveEffects")) this.passiveEffects = deserializeEffects(o.getAsJsonArray("passiveEffects"));
        if (o.has("onPickupEffects")) this.onPickupEffects = deserializeEffects(o.getAsJsonArray("onPickupEffects"));

        if (o.has("useActions")) {
            this.useActions.clear();
            for (JsonElement e : o.getAsJsonArray("useActions"))
                this.useActions.add(CommandAction.fromJson(e.getAsJsonObject()));
        }
        if (o.has("useConditions")) this.useConditions = UseConditions.fromJson(o.getAsJsonObject("useConditions"));

        if (o.has("endurance")) {
            if (o.get("endurance").isJsonNull()) this.endurance = null;
            else this.endurance = EnduranceConfig.fromJson(o.getAsJsonObject("endurance"));
        }
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getNameColor() { return nameColor; }
    public String getBaseItem() { return baseItem; }
    public List<String> getLore() { return lore; }
    public String getLoreColor() { return loreColor; }
    public boolean isGlowing() { return glowing; }
    public int getDyeColor() { return dyeColor; }
    public boolean isEnabled() { return enabled; }
    public int getMaxStackSize() { return maxStackSize; }
    public boolean isDroppable() { return droppable; }
    public boolean isTradeable() { return tradeable; }
    public boolean isPlaceable() { return placeable; }
    public boolean isConsumable() { return consumable; }
    public boolean isSoulbound() { return soulbound; }
    public boolean isDisappearOnDeath() { return disappearOnDeath; }
    public boolean isUnbreakable() { return unbreakable; }
    public int getMaxUses() { return maxUses; }
    public int getCooldownTicks() { return cooldownTicks; }
    public List<PotionEffectEntry> getOnUseEffects() { return onUseEffects; }
    public List<PotionEffectEntry> getPassiveEffects() { return passiveEffects; }
    public List<PotionEffectEntry> getOnPickupEffects() { return onPickupEffects; }
    public List<CommandAction> getUseActions() { return useActions; }
    public UseConditions getUseConditions() { return useConditions; }
    public EnduranceConfig getEndurance() { return endurance; }

    // ── Inner classes ────────────────────────────────────────────────────────

    public static class PotionEffectEntry {
        private String effectId;
        private int amplifier;
        private int durationTicks;

        public PotionEffectEntry() {
            this.effectId = "minecraft:speed";
            this.amplifier = 0;
            this.durationTicks = 600;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("effectId", effectId);
            o.addProperty("amplifier", amplifier);
            o.addProperty("durationTicks", durationTicks);
            return o;
        }

        public static PotionEffectEntry fromJson(JsonObject o) {
            PotionEffectEntry e = new PotionEffectEntry();
            if (o.has("effectId")) e.effectId = o.get("effectId").getAsString();
            if (o.has("amplifier")) e.amplifier = o.get("amplifier").getAsInt();
            if (o.has("durationTicks")) e.durationTicks = o.get("durationTicks").getAsInt();
            return e;
        }

        public String getEffectId() { return effectId; }
        public int getAmplifier() { return amplifier; }
        public int getDurationTicks() { return durationTicks; }
    }

    public static class CommandAction {
        private String command;
        private int count;
        private String feedback;
        private String delayCommand;
        private int delaySeconds;
        private boolean showTimer;
        private String timerLabel;

        public CommandAction() {
            this.command = "";
            this.count = 1;
            this.feedback = "";
            this.delayCommand = "";
            this.delaySeconds = 0;
            this.showTimer = false;
            this.timerLabel = "";
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("command", command);
            o.addProperty("count", count);
            o.addProperty("feedback", feedback);
            if (delayCommand != null && !delayCommand.isEmpty()) {
                o.addProperty("delayCommand", delayCommand);
                o.addProperty("delaySeconds", delaySeconds);
            }
            o.addProperty("showTimer", showTimer);
            if (timerLabel != null && !timerLabel.isEmpty()) {
                o.addProperty("timerLabel", timerLabel);
            }
            return o;
        }

        public static CommandAction fromJson(JsonObject o) {
            CommandAction a = new CommandAction();
            if (o.has("command")) a.command = o.get("command").getAsString();
            if (o.has("count")) a.count = o.get("count").getAsInt();
            if (o.has("feedback")) a.feedback = o.get("feedback").getAsString();
            if (o.has("delayCommand")) a.delayCommand = o.get("delayCommand").getAsString();
            if (o.has("delaySeconds")) a.delaySeconds = o.get("delaySeconds").getAsInt();
            if (o.has("showTimer")) a.showTimer = o.get("showTimer").getAsBoolean();
            if (o.has("timerLabel")) a.timerLabel = o.get("timerLabel").getAsString();
            return a;
        }

        public String getCommand() { return command; }
        public int getCount() { return count; }
        public String getFeedback() { return feedback; }
        public String getDelayCommand() { return delayCommand; }
        public int getDelaySeconds() { return delaySeconds; }
        public boolean isShowTimer() { return showTimer; }
        public String getTimerLabel() { return timerLabel; }
    }

    public static class UseConditions {
        private String dimension;
        private String permission;
        private String zoneId;
        private int minLevel;
        private String requiredHeldItem;
        private int globalCooldownTicks;

        public UseConditions() {
            this.dimension = null;
            this.permission = null;
            this.zoneId = null;
            this.minLevel = 0;
            this.requiredHeldItem = null;
            this.globalCooldownTicks = 0;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            if (dimension != null) o.addProperty("dimension", dimension);
            if (permission != null) o.addProperty("permission", permission);
            if (zoneId != null) o.addProperty("zoneId", zoneId);
            o.addProperty("minLevel", minLevel);
            if (requiredHeldItem != null) o.addProperty("requiredHeldItem", requiredHeldItem);
            o.addProperty("globalCooldownTicks", globalCooldownTicks);
            return o;
        }

        public static UseConditions fromJson(JsonObject o) {
            UseConditions c = new UseConditions();
            if (o.has("dimension") && !o.get("dimension").isJsonNull()) c.dimension = o.get("dimension").getAsString();
            if (o.has("permission") && !o.get("permission").isJsonNull()) c.permission = o.get("permission").getAsString();
            if (o.has("zoneId") && !o.get("zoneId").isJsonNull()) c.zoneId = o.get("zoneId").getAsString();
            if (o.has("minLevel")) c.minLevel = o.get("minLevel").getAsInt();
            if (o.has("requiredHeldItem") && !o.get("requiredHeldItem").isJsonNull()) c.requiredHeldItem = o.get("requiredHeldItem").getAsString();
            if (o.has("globalCooldownTicks")) c.globalCooldownTicks = o.get("globalCooldownTicks").getAsInt();
            return c;
        }

        public String getDimension() { return dimension; }
        public String getPermission() { return permission; }
        public String getZoneId() { return zoneId; }
        public int getMinLevel() { return minLevel; }
        public String getRequiredHeldItem() { return requiredHeldItem; }
        public int getGlobalCooldownTicks() { return globalCooldownTicks; }
    }

    public static class EnduranceConfig {
        private String tag;                    // shared pool identifier (e.g., "pokemon")
        private String displayName;            // label in actionbar (e.g., "Endurance")
        private int capacity;                  // max value
        private float drainPerSecond;          // points drained per second while active
        private float regenPerSecond;          // points regenerated per second while inactive
        private List<CommandAction> onDepletedCommands;  // commands when pool hits 0

        public EnduranceConfig() {
            this.tag = "";
            this.displayName = "Endurance";
            this.capacity = 100;
            this.drainPerSecond = 2.0f;
            this.regenPerSecond = 1.0f;
            this.onDepletedCommands = new ArrayList<>();
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("tag", tag);
            o.addProperty("displayName", displayName);
            o.addProperty("capacity", capacity);
            o.addProperty("drainPerSecond", drainPerSecond);
            o.addProperty("regenPerSecond", regenPerSecond);
            JsonArray cmds = new JsonArray();
            for (CommandAction a : onDepletedCommands) cmds.add(a.toJson());
            o.add("onDepletedCommands", cmds);
            return o;
        }

        public static EnduranceConfig fromJson(JsonObject o) {
            EnduranceConfig c = new EnduranceConfig();
            if (o.has("tag")) c.tag = o.get("tag").getAsString();
            if (o.has("displayName")) c.displayName = o.get("displayName").getAsString();
            if (o.has("capacity")) c.capacity = o.get("capacity").getAsInt();
            if (o.has("drainPerSecond")) c.drainPerSecond = o.get("drainPerSecond").getAsFloat();
            if (o.has("regenPerSecond")) c.regenPerSecond = o.get("regenPerSecond").getAsFloat();
            // Legacy morphoStorable/morphoSpecies/morphoForm/morphoShiny silently ignored.
            if (o.has("onDepletedCommands")) {
                c.onDepletedCommands.clear();
                for (JsonElement e : o.getAsJsonArray("onDepletedCommands"))
                    c.onDepletedCommands.add(CommandAction.fromJson(e.getAsJsonObject()));
            }
            return c;
        }

        public String getTag() { return tag; }
        public String getDisplayName() { return displayName; }
        public int getCapacity() { return capacity; }
        public float getDrainPerSecond() { return drainPerSecond; }
        public float getRegenPerSecond() { return regenPerSecond; }
        public List<CommandAction> getOnDepletedCommands() { return onDepletedCommands; }
    }

    // ── Helper: parse hex color ──────────────────────────────────────────────

    public static int parseHexColor(String hex) {
        if (hex == null || hex.isEmpty()) return 0xFFFFFF;
        try {
            String clean = hex.startsWith("#") ? hex.substring(1) : hex;
            return Integer.parseInt(clean, 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }
}
