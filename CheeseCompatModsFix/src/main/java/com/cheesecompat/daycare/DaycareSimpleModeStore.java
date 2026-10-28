package com.cheesecompat.daycare;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Side-table storage for DaycarePlus Auto/Simple breeding toggle state.
 *
 * State is keyed by (dimension id, BlockPos long) and persisted to
 * config/cheesecompat_daycare.json. Defaults to simpleMode=true, simpleArmed=true
 * so new pastures produce one egg and then stop.
 *
 * Takes primitive key parts (String dimId, long posLong) to avoid forcing
 * callers to import net.minecraft.* types that may not resolve under the
 * compileOnly Cobblemon setup.
 */
public final class DaycareSimpleModeStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix/Daycare");
    private static final Map<String, boolean[]> DATA = new ConcurrentHashMap<>();
    private static Path storePath;
    private static volatile boolean loaded = false;

    private DaycareSimpleModeStore() {}

    public static void init(MinecraftServer server) {
        storePath = FabricLoader.getInstance().getConfigDir().resolve("cheesecompat_daycare.json");
        load();
        loaded = true;
    }

    public static void shutdown() {
        if (!loaded) return;
        save();
        DATA.clear();
        loaded = false;
    }

    private static String key(String dimId, long posLong) {
        return dimId + "|" + posLong;
    }

    public static boolean isSimpleMode(String dimId, long posLong) {
        boolean[] v = DATA.get(key(dimId, posLong));
        return v == null || v[0];
    }

    public static boolean isArmed(String dimId, long posLong) {
        boolean[] v = DATA.get(key(dimId, posLong));
        return v == null || v[1];
    }

    public static void setState(String dimId, long posLong, boolean simple, boolean armed) {
        DATA.put(key(dimId, posLong), new boolean[]{simple, armed});
        save();
    }

    public static void setArmed(String dimId, long posLong, boolean armed) {
        String k = key(dimId, posLong);
        boolean[] cur = DATA.get(k);
        boolean simple = cur == null || cur[0];
        DATA.put(k, new boolean[]{simple, armed});
        save();
    }

    private static synchronized void load() {
        DATA.clear();
        if (storePath == null || !Files.exists(storePath)) return;
        try {
            String raw = Files.readString(storePath);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            for (var entry : root.entrySet()) {
                JsonObject obj = entry.getValue().getAsJsonObject();
                boolean simple = !obj.has("simple") || obj.get("simple").getAsBoolean();
                boolean armed = !obj.has("armed") || obj.get("armed").getAsBoolean();
                DATA.put(entry.getKey(), new boolean[]{simple, armed});
            }
            LOGGER.info("[CheeseCompat/Daycare] Loaded {} pasture toggle entries", DATA.size());
        } catch (Exception e) {
            LOGGER.warn("[CheeseCompat/Daycare] Failed to load daycare toggle store: {}", e.getMessage());
        }
    }

    private static synchronized void save() {
        if (storePath == null) return;
        try {
            JsonObject root = new JsonObject();
            for (var e : DATA.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("simple", e.getValue()[0]);
                obj.addProperty("armed", e.getValue()[1]);
                root.add(e.getKey(), obj);
            }
            Files.writeString(storePath, root.toString());
        } catch (IOException e) {
            LOGGER.warn("[CheeseCompat/Daycare] Failed to save daycare toggle store: {}", e.getMessage());
        }
    }
}
