package com.cheeseguard.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Client-side HUD overlay for active votes during events.
 * Receives VOTE_OVERLAY payloads from the server and renders a
 * translucent vote panel in the top-right corner of the screen.
 *
 * Updated via {@link #update(JsonObject)} when a VOTE_OVERLAY sync arrives.
 * Cleared via {@link #clear()} when the vote ends (closed=true or payload absent).
 */
public class VoteHudRenderer {

    private static JsonObject currentVote = null;
    private static long receivedAt = 0;

    /** Called from CheeseGuardClient when a VOTE_OVERLAY payload arrives. */
    public static void update(JsonObject data) {
        // If vote is closed, clear after a short grace period to show result
        if (data.has("closed") && data.get("closed").getAsBoolean()) {
            currentVote = data; // keep briefly to show result
            receivedAt  = System.currentTimeMillis();
        } else {
            currentVote = data;
            receivedAt  = System.currentTimeMillis();
        }
    }

    public static void clear() {
        currentVote = null;
    }

    /**
     * Main render method — called every frame from HudRenderCallback.
     * Draws nothing if no active vote.
     */
    public static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || currentVote == null) return;

        // Auto-clear closed votes after 4 seconds
        boolean closed = currentVote.has("closed") && currentVote.get("closed").getAsBoolean();
        if (closed && System.currentTimeMillis() - receivedAt > 4000) {
            currentVote = null;
            return;
        }

        String question  = currentVote.has("question")    ? currentVote.get("question").getAsString()    : "Vote";
        int    remaining = currentVote.has("remainingSecs")? currentVote.get("remainingSecs").getAsInt()  : 0;
        int    total     = currentVote.has("totalVotes")   ? currentVote.get("totalVotes").getAsInt()     : 0;
        String winner    = currentVote.has("winnerId")     ? currentVote.get("winnerId").getAsString()    : "";
        JsonArray opts   = currentVote.has("options")      ? currentVote.getAsJsonArray("options")        : new JsonArray();

        int screenW = mc.getWindow().getScaledWidth();

        // Panel dimensions
        int PAD   = 6;
        int W     = 130;
        int lineH = 12;
        int rows  = 2 + opts.size() + (closed ? 1 : 0);
        int H     = PAD * 2 + rows * lineH + 2;

        int x = screenW - W - 6;
        int y = 6;

        // Background panel
        ctx.fill(x - 1, y - 1, x + W + 1, y + H + 1, 0x88000000);
        ctx.fill(x,     y,     x + W,     y + H,     0xBB1A2D35);

        // Accent bar left
        int barColor = closed ? 0xBB8C3B3B : 0xBB3B5C8C;
        ctx.fill(x, y, x + 2, y + H, barColor);

        int ty = y + PAD;

        // Title / question
        int titleColor = closed ? 0xFFFF6666 : 0xFFFFCC44;
        drawSmallText(ctx, mc, "🗳 " + truncate(question, 18), x + PAD, ty, titleColor);
        ty += lineH;

        // Timer or result
        if (closed) {
            String winLabel = winner.isEmpty() ? "Egalité" : truncate(winner, 16);
            drawSmallText(ctx, mc, "✅ " + winLabel, x + PAD, ty, 0xFF55FF55);
        } else {
            String timeLabel = remaining > 0 ? remaining + "s restantes" : "Fin imminente";
            int    timeColor = remaining <= 5 ? 0xFFFF5555 : 0xFFAAAAAA;
            drawSmallText(ctx, mc, timeLabel + " · " + total + " votes", x + PAD, ty, timeColor);
        }
        ty += lineH + 2;

        // Options with vote bars
        for (int i = 0; i < opts.size(); i++) {
            JsonObject opt = opts.get(i).getAsJsonObject();
            String id    = opt.has("id")    ? opt.get("id").getAsString()    : "?";
            int    count = opt.has("count") ? opt.get("count").getAsInt()    : 0;
            int    pct   = opt.has("pct")   ? opt.get("pct").getAsInt()      : 0;

            boolean isWinner = closed && id.equals(winner);

            // Mini progress bar
            int barW = W - PAD * 2 - 2;
            int barFill = (int)(barW * pct / 100.0);
            ctx.fill(x + PAD, ty + 1, x + PAD + barW, ty + lineH - 2, 0x33FFFFFF);
            int fillColor = isWinner ? 0x8855FF55 : 0x55558CFF;
            if (barFill > 0) ctx.fill(x + PAD, ty + 1, x + PAD + barFill, ty + lineH - 2, fillColor);

            // Label: "[name]  [count] ([pct]%)"
            String optLabel = truncate(id, 10) + "  " + count + " (" + pct + "%)";
            int optColor = isWinner ? 0xFF55FF55 : 0xFFDDDDDD;
            drawSmallText(ctx, mc, optLabel, x + PAD + 2, ty + 2, optColor);
            ty += lineH;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void drawSmallText(DrawContext ctx, MinecraftClient mc,
                                       String text, int x, int y, int color) {
        ctx.drawText(mc.textRenderer, Text.literal(text), x, y, color, false);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
