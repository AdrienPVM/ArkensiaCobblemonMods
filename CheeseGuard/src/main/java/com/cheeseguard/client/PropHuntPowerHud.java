package com.cheeseguard.client;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Client-side HUD overlay for PropHunt power activation.
 * Shows a small box with "F" key indicator + power name + description + cooldown progress.
 * Rendered above the hotbar.
 *
 * State synced from server via GuiSyncPayload "PROPHUNT_POWER".
 */
public class PropHuntPowerHud {

    private static boolean visible = false;
    private static String powerNameKey = "";
    private static String powerIcon = "";
    private static String powerDescKey = "";
    private static int cooldownTotal = 30;      // total cooldown in seconds
    private static int cooldownRemaining = 0;   // remaining cooldown in seconds
    private static boolean ready = true;

    /**
     * Called from CheeseGuardClient when a PROPHUNT_POWER payload arrives.
     */
    public static void update(JsonObject data) {
        if (data.has("clear") && data.get("clear").getAsBoolean()) {
            visible = false;
            return;
        }
        visible = true;
        if (data.has("nameKey")) powerNameKey = data.get("nameKey").getAsString();
        if (data.has("icon")) powerIcon = data.get("icon").getAsString();
        if (data.has("descKey")) powerDescKey = data.get("descKey").getAsString();
        if (data.has("cooldownTotal")) cooldownTotal = data.get("cooldownTotal").getAsInt();
        if (data.has("cooldownRemaining")) cooldownRemaining = data.get("cooldownRemaining").getAsInt();
        if (data.has("ready")) ready = data.get("ready").getAsBoolean();
    }

    public static void clear() {
        visible = false;
    }

    /**
     * Main render — called every frame from HudRenderCallback.
     */
    public static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.options.hudHidden) return;
        if (!visible) return;

        TextRenderer tr = mc.textRenderer;
        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();

        // Resolve power name from translation key
        String powerName = powerIcon + " " + (powerNameKey.isEmpty() ? "?" : Text.translatable(powerNameKey).getString());

        // Resolve description from translation key
        String desc = powerDescKey.isEmpty() ? "" : Text.translatable(powerDescKey).getString();
        boolean hasDesc = !desc.isEmpty();

        // Dimensions
        int keyBoxSize = 18;
        int pad = 4;
        int textWidth = tr.getWidth(powerName);
        int descWidth = hasDesc ? tr.getWidth(desc) : 0;

        // If on cooldown, reserve space for the "Xs" suffix
        int cdTextWidth = 0;
        String cdText = "";
        if (!ready && cooldownRemaining > 0) {
            cdText = cooldownRemaining + "s";
            cdTextWidth = tr.getWidth(cdText) + pad;
        }

        // Width: max of (name row) and (desc row), both starting after the key box
        int nameRowW = pad + keyBoxSize + pad + textWidth + cdTextWidth + pad;
        int descRowW = hasDesc ? pad + keyBoxSize + pad + descWidth + pad : 0;
        int totalW = Math.max(nameRowW, descRowW);
        int totalH = keyBoxSize + pad * 2 + (hasDesc ? tr.fontHeight + 2 : 0);

        // Position: centered horizontally, above the actionbar text
        // Hotbar: screenH-22, actionbar text: ~screenH-36, XP bar: ~screenH-30
        // Place power HUD above all of that with comfortable gap
        int x = (screenW - totalW) / 2;
        int y = screenH - 80 - totalH;

        // Background
        ctx.fill(x, y, x + totalW, y + totalH, 0xCC1A1D28);
        // Border
        drawBoxBorder(ctx, x, y, totalW, totalH, 0xFF252836);

        // F key box
        int kx = x + pad;
        int ky = y + pad;

        // Cooldown fill (from bottom to top)
        if (!ready && cooldownTotal > 0) {
            float progress = 1.0f - ((float) cooldownRemaining / cooldownTotal);
            int fillH = (int) (keyBoxSize * progress);
            // Background of key box
            ctx.fill(kx, ky, kx + keyBoxSize, ky + keyBoxSize, 0xFF333844);
            // Fill from bottom
            if (fillH > 0) {
                ctx.fill(kx, ky + keyBoxSize - fillH, kx + keyBoxSize, ky + keyBoxSize, 0xAAF5A623);
            }
            // Border: dim
            drawBoxBorder(ctx, kx, ky, keyBoxSize, keyBoxSize, 0xFF555555);
        } else {
            // Ready — dark amber background + golden border
            ctx.fill(kx, ky, kx + keyBoxSize, ky + keyBoxSize, 0xFF2A2520);
            drawBoxBorder(ctx, kx, ky, keyBoxSize, keyBoxSize, 0xFFF5A623);
        }

        // Bound key letter centered in key box (reads actual keybind, not hardcoded)
        String boundKey = "?";
        net.minecraft.client.option.KeyBinding activateKey = CheeseGuardClient.getActivatePowerKey();
        if (activateKey != null) {
            boundKey = activateKey.getBoundKeyLocalizedText().getString();
            if (boundKey.length() > 3) boundKey = boundKey.substring(0, 3); // truncate long names
        }
        int fW = tr.getWidth(boundKey);
        int fX = kx + (keyBoxSize - fW) / 2;
        int fY = ky + (keyBoxSize - tr.fontHeight) / 2;
        int fColor = ready ? 0xFFFFFFFF : 0xFFAAAAAA;
        ctx.drawText(tr, boundKey, fX, fY, fColor, true);

        // Power name text
        int tx = kx + keyBoxSize + pad;
        int ty = y + pad + (keyBoxSize - tr.fontHeight) / 2;
        int nameColor = ready ? 0xFFF5A623 : 0xFF999999;
        ctx.drawText(tr, powerName, tx, ty, nameColor, false);

        // Cooldown seconds (when not ready)
        if (!ready && cooldownRemaining > 0) {
            int cdW = tr.getWidth(cdText);
            ctx.drawText(tr, cdText, x + totalW - cdW - pad, ty, 0xFFFF6666, false);
        }

        // Description text (smaller muted line below power name)
        if (hasDesc) {
            ctx.drawText(tr, desc, tx, ty + tr.fontHeight + 2, 0xFF777777, false);
        }
    }

    private static void drawBoxBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);         // top
        ctx.fill(x, y + h - 1, x + w, y + h, color); // bottom
        ctx.fill(x, y, x + 1, y + h, color);         // left
        ctx.fill(x + w - 1, y, x + w, y + h, color); // right
    }
}
