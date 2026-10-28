package com.cheeseextra.client;

import com.cheeseextra.network.IslandTourPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Bottom-right HUD listing the Arkensia residents the player still has to meet.
 * Driven by {@link IslandTourPayload} sent from the server.
 */
public final class IslandTourHud {

    // CheeseDash palette
    private static final int BG       = 0xCC1A1D28;
    private static final int BORDER   = 0x80F5A623;
    private static final int TITLE    = 0xFFF5A623; // amber
    private static final int COUNTER  = 0xFF22C55E; // green
    private static final int NAME     = 0xFFCCCCCC;

    private static final int PADDING       = 6;
    private static final int TITLE_GAP     = 4;
    private static final int LINE_HEIGHT   = 10;
    private static final int COL_WIDTH     = 70;
    private static final int COL_GAP       = 4;
    private static final int MARGIN_RIGHT  = 5;
    private static final int MARGIN_BOTTOM = 5;
    private static final int COLUMNS       = 2;

    private static volatile boolean visible = false;
    private static volatile int met = 0;
    private static volatile int total = 0;
    private static volatile List<String> remaining = List.of();

    private IslandTourHud() {}

    public static void update(IslandTourPayload payload) {
        visible = payload.show();
        met = payload.met();
        total = payload.total();
        remaining = payload.remaining();
    }

    public static void render(DrawContext ctx) {
        if (!visible || remaining.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;

        TextRenderer tr = mc.textRenderer;

        String title = "Habitants à rencontrer";
        String counter = "(" + met + "/" + total + ")";

        int titleW = tr.getWidth(title) + 4 + tr.getWidth(counter);
        int contentW = Math.max(titleW, COLUMNS * COL_WIDTH + (COLUMNS - 1) * COL_GAP);
        int boxW = contentW + 2 * PADDING;

        int rows = (remaining.size() + COLUMNS - 1) / COLUMNS;
        int boxH = PADDING + tr.fontHeight + TITLE_GAP + rows * LINE_HEIGHT + PADDING;

        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();
        int x = screenW - boxW - MARGIN_RIGHT;
        int y = screenH - boxH - MARGIN_BOTTOM;

        // Background + border
        ctx.fill(x, y, x + boxW, y + boxH, BG);
        ctx.fill(x, y, x + boxW, y + 1, BORDER);
        ctx.fill(x, y + boxH - 1, x + boxW, y + boxH, BORDER);
        ctx.fill(x, y, x + 1, y + boxH, BORDER);
        ctx.fill(x + boxW - 1, y, x + boxW, y + boxH, BORDER);

        // Title + counter (counter right-aligned within content area)
        int textX = x + PADDING;
        int textY = y + PADDING;
        ctx.drawText(tr, title, textX, textY, TITLE, false);
        int counterX = x + boxW - PADDING - tr.getWidth(counter);
        ctx.drawText(tr, counter, counterX, textY, COUNTER, false);

        // Names: column-major (col 0 fills first half, col 1 fills second half)
        int listY = textY + tr.fontHeight + TITLE_GAP;
        for (int i = 0; i < remaining.size(); i++) {
            int col = i / rows;
            int row = i % rows;
            int nx = textX + col * (COL_WIDTH + COL_GAP);
            int ny = listY + row * LINE_HEIGHT;
            ctx.drawText(tr, remaining.get(i), nx, ny, NAME, false);
        }
    }
}
