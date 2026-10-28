package com.sebastian.cobblemondungeons.gui;

import com.sebastian.cobblemondungeons.networking.DungeonHudPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class DungeonHud {
    private static volatile DungeonHudPayload currentData = null;
    private static volatile long lastUpdateTime = 0;

    public static void update(DungeonHudPayload data) {
        if (data == null || !data.isVisible()) {
            currentData = null;
        } else {
            currentData = data;
            lastUpdateTime = System.currentTimeMillis();
        }
    }

    public static void clear() {
        currentData = null;
    }

    public static void init() {
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            try {
                DungeonHudPayload data = currentData;
                if (data == null) return;

                // Hide HUD if data is older than 2 seconds (player likely left dungeon or server stopped sending)
                if (System.currentTimeMillis() - lastUpdateTime > 2000) {
                    currentData = null;
                    return;
                }

                render(drawContext, data);
            } catch (Exception e) {
                // Silently ignore HUD rendering errors to prevent crashes
            }
        });
    }

    private static void render(DrawContext context, DungeonHudPayload data) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        TextRenderer renderer = client.textRenderer;
        if (renderer == null || client.getWindow() == null) return;
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();

        List<Text> lines = new ArrayList<>();
        String dungeonName = data.dungeonName();
        lines.add(Text.literal(dungeonName != null ? dungeonName : Text.translatable("cobblemondungeons.hud.dungeon_name").getString()).formatted(Formatting.GOLD, Formatting.BOLD));

        if (data.requiredAlphas() > 0) {
            lines.add(buildProgressLine(Text.translatable("cobblemondungeons.hud.alphas").getString(), data.alphasDefeated(), data.requiredAlphas()));
        }
        if (data.requiredGems() > 0) {
            lines.add(buildProgressLine(Text.translatable("cobblemondungeons.hud.gems").getString(), data.gemsFound(), data.requiredGems()));
        }
        if (data.requiredGuardians() > 0) {
            lines.add(buildProgressLine(Text.translatable("cobblemondungeons.hud.guardians").getString(), data.guardiansDefeated(), data.requiredGuardians()));
        }
        if (data.requiredStakes() > 0) {
            lines.add(buildProgressLine(Text.translatable("cobblemondungeons.hud.stakes").getString(), data.stakesFound(), data.requiredStakes()));
        }

        lines.add(Text.translatable("cobblemondungeons.hud.lives", data.lives()).formatted(Formatting.GRAY));

        int timeLeft = data.timeLeftSeconds();
        int mins = timeLeft / 60;
        int secs = timeLeft % 60;
        lines.add(Text.translatable("cobblemondungeons.hud.time_left", String.format("%02d:%02d", mins, secs)).formatted(Formatting.GRAY));

        int y = height / 2;
        for (Text line : lines) {
            int lineWidth = renderer.getWidth(line);
            context.drawTextWithShadow(renderer, line, width - lineWidth - 10, y, 0xFFFFFF);
            y += 12;
        }
    }

    private static Text buildProgressLine(String label, int current, int required) {
        boolean complete = current >= required;
        String color = complete ? "\u00a7a" : "\u00a7f";
        return Text.literal("\u00a77" + label + ": " + color + current + "/" + required);
    }

}
