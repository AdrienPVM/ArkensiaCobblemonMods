package com.cheeseguard.data;

import java.util.*;

public class RegionGroup {

    private String id;
    private String name;
    private List<String> regionIds;
    private Map<Flag, Boolean> flags;

    // Advancement requirements (inherited by member regions)
    private List<String> requiredAdvancementsEntry;
    private List<String> requiredAdvancementsExit;

    // FTB Quest requirements (inherited by member regions)
    private List<String> requiredQuestsEntry;
    private List<String> requiredQuestsExit;

    public RegionGroup(String id, String name) {
        this.id = id;
        this.name = name;
        this.regionIds = new ArrayList<>();
        this.flags = new EnumMap<>(Flag.class);
        this.requiredAdvancementsEntry = new ArrayList<>();
        this.requiredAdvancementsExit = new ArrayList<>();
        this.requiredQuestsEntry = new ArrayList<>();
        this.requiredQuestsExit = new ArrayList<>();
    }

    public void addRegion(String regionId) {
        if (!regionIds.contains(regionId)) regionIds.add(regionId);
    }

    public void removeRegion(String regionId) {
        regionIds.remove(regionId);
    }

    public Boolean getFlag(Flag flag) {
        return flags.get(flag);
    }

    public void setFlag(Flag flag, Boolean value) {
        if (value == null) flags.remove(flag);
        else flags.put(flag, value);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getRegionIds() { return regionIds; }
    public Map<Flag, Boolean> getFlags() { return flags; }

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
}
