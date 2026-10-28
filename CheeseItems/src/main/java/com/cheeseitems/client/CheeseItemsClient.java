package com.cheeseitems.client;

import com.cheeseitems.network.MorphoCodexSyncS2CPayload;
import com.cheeseitems.network.MorphoOpenGuiS2CPayload;
import com.cheeseitems.network.MorphoSetActiveC2SPayload;
import com.cheeseitems.network.MorphoToggleC2SPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CheeseItemsClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");

    private static KeyBinding morphoToggleKey;
    private static KeyBinding morphoCodexKey;

    /** Latest snapshot received from server. Read by {@link MorphoCodexScreen}. */
    private static final List<ClientCodexEntry> codex = Collections.synchronizedList(new ArrayList<>());
    private static volatile String activeItemId = "";
    private static volatile int cheesedashPort = 44311;
    private static volatile long walletBalance = -1L;
    private static volatile String currencyName = "";

    @Override
    public void onInitializeClient() {
        morphoToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.cheeseitems.morpho_toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "category.cheeseitems"
        ));
        morphoCodexKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.cheeseitems.morpho_codex",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "category.cheeseitems"
        ));

        // Receive sync from server.
        ClientPlayNetworking.registerGlobalReceiver(MorphoCodexSyncS2CPayload.ID, (payload, context) -> {
            String json = payload.json();
            context.client().execute(() -> applySync(json));
        });

        // /morpho command → server tells the client to open the codex.
        ClientPlayNetworking.registerGlobalReceiver(MorphoOpenGuiS2CPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (context.client().currentScreen == null) {
                    context.client().setScreen(new MorphoCodexScreen());
                }
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            while (morphoToggleKey.wasPressed()) {
                ClientPlayNetworking.send(new MorphoToggleC2SPayload());
            }
            while (morphoCodexKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new MorphoCodexScreen());
                }
            }
        });

        LOGGER.info("[CheeseItems] Client initialized — keybinds H (toggle) / J (codex)");
    }

    private static void applySync(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            activeItemId = root.has("active") ? root.get("active").getAsString() : "";
            if (root.has("cheesedashPort")) {
                int p = root.get("cheesedashPort").getAsInt();
                if (p > 0) cheesedashPort = p;
            }
            walletBalance = root.has("balance") ? root.get("balance").getAsLong() : -1L;
            currencyName = root.has("currencyName") ? root.get("currencyName").getAsString() : "";
            List<ClientCodexEntry> next = new ArrayList<>();
            if (root.has("entries")) {
                JsonArray arr = root.getAsJsonArray("entries");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject o = arr.get(i).getAsJsonObject();
                    next.add(new ClientCodexEntry(
                        o.has("itemId") ? o.get("itemId").getAsString() : "",
                        o.has("species") ? o.get("species").getAsString() : "",
                        o.has("form") ? o.get("form").getAsString() : "",
                        o.has("shiny") && o.get("shiny").getAsBoolean(),
                        o.has("displayName") ? o.get("displayName").getAsString() : "",
                        o.has("hasImage") && o.get("hasImage").getAsBoolean(),
                        o.has("legendary") && o.get("legendary").getAsBoolean(),
                        o.has("terrestrial") && o.get("terrestrial").getAsBoolean(),
                        o.has("aquatic") && o.get("aquatic").getAsBoolean(),
                        o.has("aerial") && o.get("aerial").getAsBoolean()
                    ));
                }
            }
            synchronized (codex) {
                codex.clear();
                codex.addAll(next);
            }
            // Image content may have changed server-side → drop cached textures so they re-fetch.
            MorphoImageCache.invalidateAll();
        } catch (Exception ex) {
            LOGGER.warn("[CheeseItems] Failed to apply morpho codex sync: {}", ex.getMessage());
        }
    }

    public static int getCheesedashPort() { return cheesedashPort; }

    public static List<ClientCodexEntry> snapshotCodex() {
        synchronized (codex) {
            return new ArrayList<>(codex);
        }
    }

    public static String getActiveItemId() { return activeItemId; }
    public static long getWalletBalance() { return walletBalance; }
    public static String getCurrencyName() { return currencyName; }

    public static void requestSetActive(String itemId) {
        ClientPlayNetworking.send(new MorphoSetActiveC2SPayload(itemId == null ? "" : itemId));
    }

    public static void requestToggle() {
        ClientPlayNetworking.send(new MorphoToggleC2SPayload());
    }

    public record ClientCodexEntry(String itemId, String species, String form, boolean shiny,
                                   String displayName, boolean hasImage,
                                   boolean legendary, boolean terrestrial, boolean aquatic, boolean aerial) {}
}
