package com.cheeseguard.data;

import net.minecraft.util.math.BlockPos;

import java.util.*;

public class Region {

    private String id;
    private String name;
    private String world;           // e.g. "minecraft:overworld"

    // Bounding box (min always <= max after normalization)
    private int minX, minY, minZ;
    private int maxX, maxY, maxZ;

    private String groupId;         // null = no group
    private String parentId;        // null = no parent
    private int priority;           // higher = wins overlaps

    private Member owner;
    private List<Member> members;

    // Flags: null = inherit from group / default
    private Map<Flag, Boolean> flags;

    // Entry / Exit messages
    private boolean entryMessageEnabled;
    private String entryMessage;
    private boolean exitMessageEnabled;
    private String exitMessage;

    // Default protection message
    private String protectionMessage;

    // Particle color for visualization (red, green, blue, yellow, purple, cyan, white, orange)
    private String particleColor = "green";

    // Advancement requirements for entry/exit
    private List<String> requiredAdvancementsEntry = new ArrayList<>();
    private List<String> requiredAdvancementsExit = new ArrayList<>();
    private String advancementDeniedMessage = "⛔ Vous n'avez pas les advancements requis !";

    // FTB Quest requirements for entry/exit
    private List<String> requiredQuestsEntry = new ArrayList<>();
    private List<String> requiredQuestsExit = new ArrayList<>();

    // Command triggers: executed when player enters / exits the zone
    // Each command is a raw string (no leading "/"), supports {player} placeholder
    private List<String> commandsOnEnter = new ArrayList<>();
    private List<String> commandsOnExit  = new ArrayList<>();

    public Region(String id, String name, String world,
                  int x1, int y1, int z1, int x2, int y2, int z2,
                  Member owner) {
        this.id = id;
        this.name = name;
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
        this.owner = owner;
        this.members = new ArrayList<>();
        this.flags = new EnumMap<>(Flag.class);
        this.priority = 0;
        this.protectionMessage = "⛔ Cette zone est protégée !";
        this.entryMessageEnabled = false;
        this.entryMessage = "Vous entrez dans : " + name;
        this.exitMessageEnabled = false;
        this.exitMessage = "Vous quittez : " + name;
    }

    // ── Containment ─────────────────────────────────────────────────────────

    public boolean contains(String world, int x, int y, int z) {
        return this.world.equals(world)
            && x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }

    public boolean contains(String world, BlockPos pos) {
        return contains(world, pos.getX(), pos.getY(), pos.getZ());
    }

    // ── Members ──────────────────────────────────────────────────────────────

    public boolean isMember(UUID uuid) {
        if (owner != null && owner.getUuid().equals(uuid)) return true;
        return members.stream().anyMatch(m -> m.getUuid().equals(uuid));
    }

    public void addMember(Member m) {
        if (!isMember(m.getUuid())) members.add(m);
    }

    public void removeMember(UUID uuid) {
        members.removeIf(m -> m.getUuid().equals(uuid));
    }

    // ── Flags ────────────────────────────────────────────────────────────────

    /**
     * Returns the flag value set directly on this region, or null if not set (= inherit).
     */
    public Boolean getFlag(Flag flag) {
        return flags.get(flag);
    }

    public void setFlag(Flag flag, Boolean value) {
        if (value == null) flags.remove(flag);
        else flags.put(flag, value);
    }

    public Map<Flag, Boolean> getFlags() { return flags; }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getWorld() { return world; }

    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    public int getCenterX() { return (minX + maxX) / 2; }
    public int getCenterY() { return (minY + maxY) / 2; }
    public int getCenterZ() { return (minZ + maxZ) / 2; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public Member getOwner() { return owner; }
    public void setOwner(Member owner) { this.owner = owner; }

    public List<Member> getMembers() { return members; }

    public boolean isEntryMessageEnabled() { return entryMessageEnabled; }
    public void setEntryMessageEnabled(boolean v) { this.entryMessageEnabled = v; }
    public String getEntryMessage() { return entryMessage; }
    public void setEntryMessage(String m) { this.entryMessage = m; }

    public boolean isExitMessageEnabled() { return exitMessageEnabled; }
    public void setExitMessageEnabled(boolean v) { this.exitMessageEnabled = v; }
    public String getExitMessage() { return exitMessage; }
    public void setExitMessage(String m) { this.exitMessage = m; }

    public String getProtectionMessage() { return protectionMessage; }
    public void setProtectionMessage(String m) { this.protectionMessage = m; }

    public String getParticleColor() { return particleColor; }
    public void setParticleColor(String c) { this.particleColor = c; }

    public List<String> getRequiredAdvancementsEntry() { return requiredAdvancementsEntry; }
    public void setRequiredAdvancementsEntry(List<String> list) { this.requiredAdvancementsEntry = list != null ? list : new ArrayList<>(); }
    public void toggleAdvancementEntry(String advId) {
        if (requiredAdvancementsEntry.contains(advId)) requiredAdvancementsEntry.remove(advId);
        else requiredAdvancementsEntry.add(advId);
    }

    public List<String> getRequiredAdvancementsExit() { return requiredAdvancementsExit; }
    public void setRequiredAdvancementsExit(List<String> list) { this.requiredAdvancementsExit = list != null ? list : new ArrayList<>(); }
    public void toggleAdvancementExit(String advId) {
        if (requiredAdvancementsExit.contains(advId)) requiredAdvancementsExit.remove(advId);
        else requiredAdvancementsExit.add(advId);
    }

    public String getAdvancementDeniedMessage() { return advancementDeniedMessage; }
    public void setAdvancementDeniedMessage(String m) { this.advancementDeniedMessage = m; }

    public List<String> getRequiredQuestsEntry() { return requiredQuestsEntry; }
    public void setRequiredQuestsEntry(List<String> list) { this.requiredQuestsEntry = list != null ? list : new ArrayList<>(); }
    public void toggleQuestEntry(String qId) {
        if (requiredQuestsEntry.contains(qId)) requiredQuestsEntry.remove(qId);
        else requiredQuestsEntry.add(qId);
    }

    public List<String> getRequiredQuestsExit() { return requiredQuestsExit; }
    public void setRequiredQuestsExit(List<String> list) { this.requiredQuestsExit = list != null ? list : new ArrayList<>(); }
    public void toggleQuestExit(String qId) {
        if (requiredQuestsExit.contains(qId)) requiredQuestsExit.remove(qId);
        else requiredQuestsExit.add(qId);
    }

    // ── Command triggers ─────────────────────────────────────────────────────

    public List<String> getCommandsOnEnter() { return commandsOnEnter != null ? commandsOnEnter : (commandsOnEnter = new ArrayList<>()); }
    public void setCommandsOnEnter(List<String> list) { this.commandsOnEnter = list != null ? list : new ArrayList<>(); }
    public void addCommandOnEnter(String cmd) { getCommandsOnEnter().add(cmd); }
    public void removeCommandOnEnter(int index) { if(index>=0&&index<getCommandsOnEnter().size()) getCommandsOnEnter().remove(index); }

    public List<String> getCommandsOnExit() { return commandsOnExit != null ? commandsOnExit : (commandsOnExit = new ArrayList<>()); }
    public void setCommandsOnExit(List<String> list) { this.commandsOnExit = list != null ? list : new ArrayList<>(); }
    public void addCommandOnExit(String cmd) { getCommandsOnExit().add(cmd); }
    public void removeCommandOnExit(int index) { if(index>=0&&index<getCommandsOnExit().size()) getCommandsOnExit().remove(index); }
}
