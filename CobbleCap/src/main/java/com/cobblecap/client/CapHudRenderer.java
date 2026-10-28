package com.cobblecap.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

/**
 * Renders the level cap badge HUD overlay: a small texture with the cap number centered.
 * Position is configurable: 0=bottom-right, 1=bottom-left, 2=top-right, 3=top-left.
 * State is updated from CapSyncPayload received from the server.
 */
public class CapHudRenderer {

    private static int capLevel = 0;
    private static String nextChampion = null;
    private static boolean hudVisible = true;
    private static int hudPosition = 0; // 0=bottom-right, 1=bottom-left, 2=top-right, 3=top-left
    private static boolean systemEnabled = true;
    private static boolean hasData = false;

    private static final Identifier BADGE_TEXTURE = Identifier.of("cobblecap", "textures/gui/level_cap.png");
    private static final int BADGE_W = 24;
    private static final int BADGE_H = 20;
    private static final int COLOR_WHITE = 0xFFFFFFFF;

    public static void register() {
        HudRenderCallback.EVENT.register(CapHudRenderer::render);
    }

    /**
     * Called from CobbleCapClient when a CapSyncPayload is received.
     */
    public static void update(int cap, String next, boolean visible, int position, boolean enabled) {
        capLevel = cap;
        nextChampion = next;
        hudVisible = visible;
        hudPosition = position;
        systemEnabled = enabled;
        hasData = true;
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        if (!hasData || !systemEnabled || !hudVisible) return;

        TextRenderer textRenderer = client.textRenderer;
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        // Position the badge
        int x, y;
        int margin = 6;

        switch (hudPosition) {
            case 1: // bottom-left
                x = margin;
                y = screenHeight - margin - BADGE_H;
                break;
            case 2: // top-right
                x = screenWidth - margin - BADGE_W;
                y = margin;
                break;
            case 3: // top-left
                x = margin;
                y = margin;
                break;
            default: // 0 = bottom-right
                x = screenWidth - margin - BADGE_W;
                y = screenHeight - margin - BADGE_H;
                break;
        }

        // Draw badge texture
        context.drawTexture(BADGE_TEXTURE, x, y, 0, 0, BADGE_W, BADGE_H, BADGE_W, BADGE_H);

        // Draw cap number centered on badge
        String capText = String.valueOf(capLevel);
        int textWidth = textRenderer.getWidth(capText);
        int textX = x + (BADGE_W - textWidth) / 2;
        int textY = y + (BADGE_H - textRenderer.fontHeight) / 2 + 1;
        context.drawText(textRenderer, capText, textX, textY, COLOR_WHITE, true);
    }
}
