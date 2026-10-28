package com.cheeseguard.data;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class RegionStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = Paths.get("config", "cheeseguard");
    private static final Path FILE = CONFIG_DIR.resolve("regions.json");

    private final Map<String, Region> regions = new LinkedHashMap<>();
    private final Map<String, RegionGroup> groups = new LinkedHashMap<>();

    public RegionStorage() {
        try { Files.createDirectories(CONFIG_DIR); } catch (IOException ignored) {}
        load();
    }

    // ── Regions ──────────────────────────────────────────────────────────────

    public Collection<Region> getRegions() { return regions.values(); }
    public Region getRegion(String id) { return regions.get(id); }
    public boolean hasRegion(String id) { return regions.containsKey(id); }

    public void addRegion(Region r) { regions.put(r.getId(), r); save(); }
    public void removeRegion(String id) {
        regions.remove(id);
        groups.values().forEach(g -> g.removeRegion(id));
        save();
    }

    // ── Groups ───────────────────────────────────────────────────────────────

    public Collection<RegionGroup> getGroups() { return groups.values(); }
    public RegionGroup getGroup(String id) { return groups.get(id); }
    public boolean hasGroup(String id) { return groups.containsKey(id); }

    public void addGroup(RegionGroup g) { groups.put(g.getId(), g); save(); }
    public void removeGroup(String id) {
        groups.remove(id);
        regions.values().stream()
            .filter(r -> id.equals(r.getGroupId()))
            .forEach(r -> r.setGroupId(null));
        save();
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public void save() {
        try {
            JsonObject root = new JsonObject();

            // Regions
            JsonObject regionsObj = new JsonObject();
            for (Region r : regions.values()) {
                regionsObj.add(r.getId(), serializeRegion(r));
            }
            root.add("regions", regionsObj);

            // Groups
            JsonObject groupsObj = new JsonObject();
            for (RegionGroup g : groups.values()) {
                groupsObj.add(g.getId(), serializeGroup(g));
            }
            root.add("groups", groupsObj);

            Files.writeString(FILE, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("Failed to save CheeseGuard data", e);
        }
    }

    private void load() {
        if (!Files.exists(FILE)) return;
        try {
            String json = Files.readString(FILE);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("groups")) {
                JsonObject obj = root.getAsJsonObject("groups");
                for (var entry : obj.entrySet()) {
                    groups.put(entry.getKey(), deserializeGroup(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }

            if (root.has("regions")) {
                JsonObject obj = root.getAsJsonObject("regions");
                for (var entry : obj.entrySet()) {
                    regions.put(entry.getKey(), deserializeRegion(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }

            LOGGER.info("CheeseGuard: loaded {} regions, {} groups", regions.size(), groups.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load CheeseGuard data", e);
        }
    }

    // ── Serialization ────────────────────────────────────────────────────────

    private JsonObject serializeRegion(Region r) {
        JsonObject o = new JsonObject();
        o.addProperty("name", r.getName());
        o.addProperty("world", r.getWorld());
        o.addProperty("minX", r.getMinX()); o.addProperty("minY", r.getMinY()); o.addProperty("minZ", r.getMinZ());
        o.addProperty("maxX", r.getMaxX()); o.addProperty("maxY", r.getMaxY()); o.addProperty("maxZ", r.getMaxZ());
        if (r.getGroupId() != null) o.addProperty("group", r.getGroupId());
        if (r.getParentId() != null) o.addProperty("parent", r.getParentId());
        o.addProperty("priority", r.getPriority());

        if (r.getOwner() != null) {
            JsonObject owner = new JsonObject();
            owner.addProperty("uuid", r.getOwner().getUuid().toString());
            owner.addProperty("name", r.getOwner().getName());
            o.add("owner", owner);
        }

        JsonArray members = new JsonArray();
        for (Member m : r.getMembers()) {
            JsonObject mo = new JsonObject();
            mo.addProperty("uuid", m.getUuid().toString());
            mo.addProperty("name", m.getName());
            members.add(mo);
        }
        o.add("members", members);

        JsonObject flags = new JsonObject();
        for (var entry : r.getFlags().entrySet()) {
            flags.addProperty(entry.getKey().getId(), entry.getValue());
        }
        o.add("flags", flags);

        o.addProperty("entryMessageEnabled", r.isEntryMessageEnabled());
        o.addProperty("entryMessage", r.getEntryMessage());
        o.addProperty("exitMessageEnabled", r.isExitMessageEnabled());
        o.addProperty("exitMessage", r.getExitMessage());
        o.addProperty("protectionMessage", r.getProtectionMessage());
        o.addProperty("particleColor", r.getParticleColor());

        // Advancement requirements
        if (!r.getRequiredAdvancementsEntry().isEmpty()) {
            JsonArray advEntry = new JsonArray();
            r.getRequiredAdvancementsEntry().forEach(advEntry::add);
            o.add("requiredAdvancementsEntry", advEntry);
        }
        if (!r.getRequiredAdvancementsExit().isEmpty()) {
            JsonArray advExit = new JsonArray();
            r.getRequiredAdvancementsExit().forEach(advExit::add);
            o.add("requiredAdvancementsExit", advExit);
        }
        if (!r.getAdvancementDeniedMessage().equals("⛔ Vous n'avez pas les advancements requis !")) {
            o.addProperty("advancementDeniedMessage", r.getAdvancementDeniedMessage());
        }
        if (!r.getRequiredQuestsEntry().isEmpty()) {
            JsonArray qEntry = new JsonArray();
            r.getRequiredQuestsEntry().forEach(qEntry::add);
            o.add("requiredQuestsEntry", qEntry);
        }
        if (!r.getRequiredQuestsExit().isEmpty()) {
            JsonArray qExit = new JsonArray();
            r.getRequiredQuestsExit().forEach(qExit::add);
            o.add("requiredQuestsExit", qExit);
        }

        return o;
    }

    private Region deserializeRegion(String id, JsonObject o) {
        String name = o.get("name").getAsString();
        String world = o.get("world").getAsString();
        int minX = o.get("minX").getAsInt(), minY = o.get("minY").getAsInt(), minZ = o.get("minZ").getAsInt();
        int maxX = o.get("maxX").getAsInt(), maxY = o.get("maxY").getAsInt(), maxZ = o.get("maxZ").getAsInt();

        Member owner = null;
        if (o.has("owner")) {
            JsonObject oo = o.getAsJsonObject("owner");
            owner = new Member(UUID.fromString(oo.get("uuid").getAsString()), oo.get("name").getAsString());
        }

        Region r = new Region(id, name, world, minX, minY, minZ, maxX, maxY, maxZ, owner);

        if (o.has("group")) r.setGroupId(o.get("group").getAsString());
        if (o.has("parent")) r.setParentId(o.get("parent").getAsString());
        if (o.has("priority")) r.setPriority(o.get("priority").getAsInt());

        if (o.has("members")) {
            for (JsonElement e : o.getAsJsonArray("members")) {
                JsonObject mo = e.getAsJsonObject();
                r.addMember(new Member(UUID.fromString(mo.get("uuid").getAsString()), mo.get("name").getAsString()));
            }
        }

        if (o.has("flags")) {
            JsonObject flags = o.getAsJsonObject("flags");
            for (var entry : flags.entrySet()) {
                Flag f = Flag.fromId(entry.getKey());
                if (f != null) r.setFlag(f, entry.getValue().getAsBoolean());
            }
        }

        if (o.has("entryMessageEnabled")) r.setEntryMessageEnabled(o.get("entryMessageEnabled").getAsBoolean());
        if (o.has("entryMessage")) r.setEntryMessage(o.get("entryMessage").getAsString());
        if (o.has("exitMessageEnabled")) r.setExitMessageEnabled(o.get("exitMessageEnabled").getAsBoolean());
        if (o.has("exitMessage")) r.setExitMessage(o.get("exitMessage").getAsString());
        if (o.has("protectionMessage")) r.setProtectionMessage(o.get("protectionMessage").getAsString());
        if (o.has("particleColor")) r.setParticleColor(o.get("particleColor").getAsString());

        // Advancement requirements
        if (o.has("requiredAdvancementsEntry")) {
            List<String> list = new java.util.ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("requiredAdvancementsEntry")) list.add(e.getAsString());
            r.setRequiredAdvancementsEntry(list);
        }
        if (o.has("requiredAdvancementsExit")) {
            List<String> list = new java.util.ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("requiredAdvancementsExit")) list.add(e.getAsString());
            r.setRequiredAdvancementsExit(list);
        }
        if (o.has("advancementDeniedMessage")) r.setAdvancementDeniedMessage(o.get("advancementDeniedMessage").getAsString());

        // Quest requirements
        if (o.has("requiredQuestsEntry")) {
            List<String> list = new java.util.ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("requiredQuestsEntry")) list.add(e.getAsString());
            r.setRequiredQuestsEntry(list);
        }
        if (o.has("requiredQuestsExit")) {
            List<String> list = new java.util.ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("requiredQuestsExit")) list.add(e.getAsString());
            r.setRequiredQuestsExit(list);
        }

        return r;
    }

    private JsonObject serializeGroup(RegionGroup g) {
        JsonObject o = new JsonObject();
        o.addProperty("name", g.getName());
        JsonArray arr = new JsonArray();
        g.getRegionIds().forEach(arr::add);
        o.add("regions", arr);
        JsonObject flags = new JsonObject();
        for (var entry : g.getFlags().entrySet()) flags.addProperty(entry.getKey().getId(), entry.getValue());
        o.add("flags", flags);

        // Advancement requirements
        if (!g.getRequiredAdvancementsEntry().isEmpty()) {
            JsonArray advEntry = new JsonArray();
            g.getRequiredAdvancementsEntry().forEach(advEntry::add);
            o.add("requiredAdvancementsEntry", advEntry);
        }
        if (!g.getRequiredAdvancementsExit().isEmpty()) {
            JsonArray advExit = new JsonArray();
            g.getRequiredAdvancementsExit().forEach(advExit::add);
            o.add("requiredAdvancementsExit", advExit);
        }
        if (!g.getRequiredQuestsEntry().isEmpty()) {
            JsonArray qEntry = new JsonArray();
            g.getRequiredQuestsEntry().forEach(qEntry::add);
            o.add("requiredQuestsEntry", qEntry);
        }
        if (!g.getRequiredQuestsExit().isEmpty()) {
            JsonArray qExit = new JsonArray();
            g.getRequiredQuestsExit().forEach(qExit::add);
            o.add("requiredQuestsExit", qExit);
        }

        return o;
    }

    private RegionGroup deserializeGroup(String id, JsonObject o) {
        RegionGroup g = new RegionGroup(id, o.get("name").getAsString());
        if (o.has("regions")) for (JsonElement e : o.getAsJsonArray("regions")) g.addRegion(e.getAsString());
        if (o.has("flags")) {
            JsonObject flags = o.getAsJsonObject("flags");
            for (var entry : flags.entrySet()) {
                Flag f = Flag.fromId(entry.getKey());
                if (f != null) g.setFlag(f, entry.getValue().getAsBoolean());
            }
        }
        if (o.has("requiredAdvancementsEntry")) {
            java.util.List<String> list = new java.util.ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("requiredAdvancementsEntry")) list.add(e.getAsString());
            g.setRequiredAdvancementsEntry(list);
        }
        if (o.has("requiredAdvancementsExit")) {
            java.util.List<String> list = new java.util.ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("requiredAdvancementsExit")) list.add(e.getAsString());
            g.setRequiredAdvancementsExit(list);
        }
        if (o.has("requiredQuestsEntry")) {
            java.util.List<String> list = new java.util.ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("requiredQuestsEntry")) list.add(e.getAsString());
            g.setRequiredQuestsEntry(list);
        }
        if (o.has("requiredQuestsExit")) {
            java.util.List<String> list = new java.util.ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("requiredQuestsExit")) list.add(e.getAsString());
            g.setRequiredQuestsExit(list);
        }
        return g;
    }
}
