package com.cheeseitems.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads catalog morpho images from the CheeseDash HTTP server and caches the
 * resulting GPU textures. Lookups never block the render thread — the first call
 * for a given morphoId schedules an async download and returns null; once the PNG
 * arrives, it's registered as a runtime texture and subsequent lookups return its
 * Identifier.
 */
public final class MorphoImageCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");

    private static final Map<String, Identifier> ready = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> failed = new ConcurrentHashMap<>();

    private MorphoImageCache() {}

    public static Identifier getOrLoad(String morphoId) {
        if (morphoId == null || morphoId.isEmpty()) return null;
        Identifier id = ready.get(morphoId);
        if (id != null) return id;
        if (Boolean.TRUE.equals(failed.get(morphoId))) return null;
        if (Boolean.TRUE.equals(inFlight.get(morphoId))) return null;

        String host = resolveHost();
        int port = CheeseItemsClient.getCheesedashPort();
        if (host == null || port <= 0) {
            failed.put(morphoId, true);
            return null;
        }

        // The in-game Codex GUI never renders these textures larger than ~64px. The
        // ≤480px thumbnail variant covers that comfortably and saves the client from
        // downloading the full 1-2 MB original PNG over the network on first open.
        String url = "http://" + host + ":" + port + "/morphos/api/thumb/" + morphoId;
        inFlight.put(morphoId, true);

        Thread t = new Thread(() -> {
            try {
                byte[] bytes = download(url);
                if (bytes == null || bytes.length == 0) throw new RuntimeException("empty");
                NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
                MinecraftClient.getInstance().execute(() -> {
                    try {
                        NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
                        Identifier texId = Identifier.of("cheeseitems",
                                "morpho/" + morphoId.toLowerCase().replaceAll("[^a-z0-9_]", ""));
                        MinecraftClient.getInstance().getTextureManager().registerTexture(texId, tex);
                        ready.put(morphoId, texId);
                    } catch (Exception e) {
                        LOGGER.warn("[CheeseItems] Failed to register morpho texture {}: {}", morphoId, e.getMessage());
                        failed.put(morphoId, true);
                    } finally {
                        inFlight.remove(morphoId);
                    }
                });
            } catch (Exception e) {
                LOGGER.debug("[CheeseItems] Could not load morpho image {}: {}", morphoId, e.getMessage());
                failed.put(morphoId, true);
                inFlight.remove(morphoId);
            }
        }, "MorphoImageDownload-" + morphoId);
        t.setDaemon(true);
        t.start();

        return null;
    }

    /** Drop everything on disconnect/sync update so renamed/replaced images reload. */
    public static void invalidateAll() {
        ready.clear();
        inFlight.clear();
        failed.clear();
    }

    private static byte[] download(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(3_000);
            conn.setReadTimeout(5_000);
            conn.setRequestProperty("User-Agent", "CheeseItems-Client");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            try (var in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolveHost() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.isInSingleplayer()) return "localhost";
            var entry = mc.getCurrentServerEntry();
            if (entry == null || entry.address == null) return null;
            String addr = entry.address;
            int colon = addr.indexOf(':');
            if (colon >= 0) addr = addr.substring(0, colon);
            return addr;
        } catch (Exception e) {
            return null;
        }
    }
}
