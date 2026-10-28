package com.cobblecap.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a Minecraft {@link GameProfile} that contains a usable {@code textures}
 * property (skin URL + signature) for a given UUID.
 *
 * Order: online player ▸ server user cache ▸ in-memory session cache ▸ Mojang HTTP.
 *
 * The Mojang user-cache file on disk does NOT persist the {@code textures} property
 * — only name/uuid/expiresOn. After a server restart, {@code userCache.getByUuid}
 * returns a profile WITHOUT textures, so the HTTP fallback is required for any
 * doppelganger that overlays the skin of a currently-offline player.
 */
public final class MojangProfileFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    /** Session cache — survives until JVM exit. Mojang rate-limits, don't hammer. */
    private static final ConcurrentHashMap<UUID, GameProfile> CACHE = new ConcurrentHashMap<>();

    private MojangProfileFetcher() {}

    /**
     * Try every available source. Returns null only if no source could provide a
     * profile with textures (e.g. invalid UUID, Mojang outage).
     */
    public static GameProfile resolve(MinecraftServer server, UUID uuid, String fallbackName) {
        if (server == null || uuid == null) return null;
        // 1. Online player's profile carries fresh textures.
        try {
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(uuid);
            if (online != null && online.getGameProfile().getProperties().containsKey("textures")) {
                return online.getGameProfile();
            }
        } catch (Exception ignored) {}
        // 2. Server user cache — populated when a player logs in, but textures
        //    are NOT persisted to disk so they vanish after a restart.
        try {
            var cache = server.getUserCache();
            if (cache != null) {
                var opt = cache.getByUuid(uuid);
                if (opt.isPresent() && opt.get().getProperties().containsKey("textures")) {
                    return opt.get();
                }
            }
        } catch (Exception ignored) {}
        // 3. Already fetched this session?
        GameProfile cached = CACHE.get(uuid);
        if (cached != null) return cached;
        // 4. Mojang session API fetch — synchronous HTTP, ~100-300ms.
        try {
            GameProfile fetched = fetchFromMojang(uuid, fallbackName);
            if (fetched != null && fetched.getProperties().containsKey("textures")) {
                CACHE.put(uuid, fetched);
                LOGGER.info("[CobbleCap] Fetched profile from Mojang: {} (no local cache had textures)",
                        fallbackName);
                return fetched;
            }
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] Mojang profile fetch failed for {}: {}",
                    fallbackName, e.getMessage());
        }
        return null;
    }

    /**
     * Fetch a {@link GameProfile} (UUID + textures property, signature included)
     * directly from Mojang's session server. Returns null on any non-200 or parse
     * failure. Public for callers that need to bypass cache (e.g. periodic refresh).
     */
    public static GameProfile fetchFromMojang(UUID uuid, String name) throws Exception {
        String stripped = uuid.toString().replace("-", "");
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .build();
        // IMPORTANT : `?unsigned=false`. Sans ce paramètre, le sessionserver Mojang
        // renvoie la property `textures` SANS signature → on crée une Property non
        // signée → un client en online-mode REJETTE le skin non signé et affiche le
        // skin par défaut (Steve/Alex). C'était la cause du "doppelganger en skin par
        // défaut". Avec unsigned=false, Mojang inclut le champ `signature` et le client
        // accepte et rend le vrai skin du champion.
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://sessionserver.mojang.com/session/minecraft/profile/"
                                + stripped + "?unsigned=false"))
                .timeout(Duration.ofSeconds(8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!root.has("properties") || !root.get("properties").isJsonArray()) return null;
        GameProfile profile = new GameProfile(uuid,
                root.has("name") ? root.get("name").getAsString() : name);
        for (var el : root.getAsJsonArray("properties")) {
            JsonObject prop = el.getAsJsonObject();
            String pname = prop.get("name").getAsString();
            String value = prop.get("value").getAsString();
            String sig = prop.has("signature") ? prop.get("signature").getAsString() : null;
            profile.getProperties().put(pname, new Property(pname, value, sig));
        }
        return profile;
    }
}
