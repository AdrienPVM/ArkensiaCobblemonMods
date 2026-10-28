package com.cheeseguard.client;

import com.cheeseguard.network.GuiSyncPayload;
import com.cheeseguard.network.OpenZoneMenuC2S;
import com.cheeseguard.network.PropHuntActivatePowerPayload;
import com.cheeseguard.network.PropHuntCyclePowerPayload;
import com.cheeseguard.network.ZoneMenuDataS2C;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Client entry point for CheeseGuard.
 * Registers PropHunt power keybinds, HUD overlays, and network handlers.
 */
@Environment(EnvType.CLIENT)
public class CheeseGuardClient implements ClientModInitializer {

    /** When true, ALL nametags (players, mobs, Cobblemon) are hidden. Set by NAMETAG_ZONE packet. */
    public static boolean hideNametags = false;

    /** When true, the JourneyMap minimap is hidden. Set by MINIMAP_ZONE packet. */
    public static boolean hideMinimap = false;

    private static KeyBinding activatePowerKey;
    private static KeyBinding cyclePowerKey;
    private static KeyBinding zoneMenuKey;

    @Override
    public void onInitializeClient() {
        // Register keybind for power activation (default: R)
        activatePowerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.cheeseguard.activate_power",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "category.cheeseguard"
        ));

        // Register keybind for power cycling (default: middle mouse)
        cyclePowerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.cheeseguard.cycle_power",
            InputUtil.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
            "category.cheeseguard"
        ));

        // Register keybind for the zone-owner edit menu (default: K).
        // Players can rebind from Options → Controls → CheeseGuard.
        zoneMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.cheeseguard.zone_menu",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "category.cheeseguard"
        ));

        // Handle keybind presses
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (activatePowerKey.wasPressed()) {
                if (client.getNetworkHandler() != null) {
                    ClientPlayNetworking.send(new PropHuntActivatePowerPayload());
                }
            }
            while (cyclePowerKey.wasPressed()) {
                if (client.getNetworkHandler() != null) {
                    ClientPlayNetworking.send(new PropHuntCyclePowerPayload());
                }
            }
            while (zoneMenuKey.wasPressed()) {
                if (client.getNetworkHandler() != null && client.currentScreen == null) {
                    ClientPlayNetworking.send(new OpenZoneMenuC2S());
                }
            }
        });

        // HUD overlays — rendered every frame over the game world
        HudRenderCallback.EVENT.register((drawContext, tickDeltaManager) -> {
            VoteHudRenderer.render(drawContext);
            PropHuntPowerHud.render(drawContext);
        });

        // Zone-owner menu data from server: open / refresh / close ZoneOwnerMenuScreen.
        ClientPlayNetworking.registerGlobalReceiver(ZoneMenuDataS2C.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (payload.isClose()) {
                    if (context.client().currentScreen instanceof ZoneOwnerMenuScreen) {
                        context.client().setScreen(null);
                    }
                    return;
                }
                if (context.client().currentScreen instanceof ZoneOwnerMenuScreen existing) {
                    existing.updateData(payload.data());
                } else {
                    context.client().setScreen(new ZoneOwnerMenuScreen(payload.data()));
                }
            });
        });

        // Handle server sync payloads
        ClientPlayNetworking.registerGlobalReceiver(GuiSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                switch (payload.viewMode()) {
                    case "CLOSE" -> context.client().setScreen(null);
                    case "VOTE_OVERLAY" -> VoteHudRenderer.update(payload.data());
                    case "VOTE_CLEAR" -> VoteHudRenderer.clear();
                    case "PROPHUNT_POWER" -> PropHuntPowerHud.update(payload.data());
                    case "NAMETAG_ZONE" -> {
                        com.google.gson.JsonObject obj = payload.data();
                        hideNametags = obj.has("hide") && obj.get("hide").getAsBoolean();
                    }
                    case "MINIMAP_ZONE" -> {
                        com.google.gson.JsonObject obj2 = payload.data();
                        hideMinimap = obj2.has("hide") && obj2.get("hide").getAsBoolean();
                    }
                }
            });
        });
    }

    /** Get the power activation keybind — used by PropHuntPowerHud to display the bound key. */
    public static KeyBinding getActivatePowerKey() { return activatePowerKey; }

    /** Get the power cycle keybind. */
    public static KeyBinding getCyclePowerKey() { return cyclePowerKey; }
}
