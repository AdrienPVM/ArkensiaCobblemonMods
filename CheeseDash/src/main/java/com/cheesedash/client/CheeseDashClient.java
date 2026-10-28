package com.cheesedash.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * CheeseDash client entrypoint.
 * Registers the Quick Menu keybind and opens the screen on press.
 */
@Environment(EnvType.CLIENT)
public class CheeseDashClient implements ClientModInitializer {

    static KeyBinding QUICK_MENU_KEY;
    private final QuickMenuConfig config = new QuickMenuConfig();

    @Override
    public void onInitializeClient() {
        // Load shortcut config
        config.load();

        // Register keybind (unbound by default)
        QUICK_MENU_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.cheesedash.quick_menu",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "category.cheesedash"
        ));

        // Tick handler: open screen when keybind is pressed (OP only)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (QUICK_MENU_KEY.wasPressed()) {
                if (client.player == null) continue;
                // Only allow operators to open the quick menu
                if (!client.player.hasPermissionLevel(2)) continue;
                if (client.currentScreen == null) {
                    config.load(); // Reload in case file was edited externally
                    client.setScreen(new QuickMenuScreen(config));
                } else if (client.currentScreen instanceof QuickMenuScreen) {
                    client.setScreen(null);
                }
            }
        });
    }
}
