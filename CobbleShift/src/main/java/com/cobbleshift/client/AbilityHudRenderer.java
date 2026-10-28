package com.cobbleshift.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * Renders a small HUD overlay in the bottom-left corner showing:
 * - Line 1: type emoji + ability display name (e.g., "🔥 Flamme Intérieure")
 * - Line 2: secondary ability name (if dual-type)
 * - Line 3: attack cooldown bar (when transformed, visible during cooldown)
 *
 * Shows ability names (AbilityType.displayName) instead of species/type.
 */
public class AbilityHudRenderer {

    private static String activeSpecies = null;
    private static String activeType1 = null;
    private static String activeType2 = null;

    // Attack cooldown bar state (set by AttackInputMixin)
    private static int attackCooldownMax = 0;
    private static int attackCooldownTicks = 0;
    private static long lastCooldownTrigger = 0;

    public static void register() {
        HudRenderCallback.EVENT.register(AbilityHudRenderer::render);
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;

        boolean hasAbility = activeSpecies != null && !activeSpecies.isEmpty();
        boolean isTransformed = TransformRenderer.isTransformed(client.player.getUuid());
        if (!hasAbility && !isTransformed) return;

        TextRenderer textRenderer = client.textRenderer;
        int screenHeight = client.getWindow().getScaledHeight();

        int x = 6;
        int y = screenHeight - 36;
        int bgRight = x + 140;

        // Tick cooldown (client-side visual only)
        if (attackCooldownTicks > 0) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastCooldownTrigger;
            // Approximate: 50ms per tick
            int ticksElapsed = (int) (elapsed / 50);
            attackCooldownTicks = Math.max(0, attackCooldownMax - ticksElapsed);
        }

        // Determine how many lines we need
        int lines = 0;
        if (hasAbility) lines += 1; // ability 1
        if (hasAbility && activeType2 != null && !activeType2.isEmpty()) lines += 1; // ability 2
        if (isTransformed) lines += 1; // cooldown bar

        int bgHeight = lines * 10 + 6;
        int bgY = y + (30 - bgHeight);

        // Background
        context.fill(x - 2, bgY, bgRight, bgY + bgHeight, 0x80000000);

        int lineY = bgY + 3;

        // Line 1: primary ability name
        if (hasAbility) {
            String typeIcon = getTypeIcon(activeType1);
            String abilityName = getAbilityDisplayName(activeType1);
            int color1 = getTypeColorHex(activeType1);
            context.drawText(textRenderer, typeIcon + " " + abilityName, x, lineY, color1, true);
            lineY += 10;
        }

        // Line 2: secondary ability name (if dual-type)
        if (hasAbility && activeType2 != null && !activeType2.isEmpty()) {
            String typeIcon2 = getTypeIcon(activeType2);
            String abilityName2 = getAbilityDisplayName(activeType2);
            int color2 = getTypeColorHex(activeType2);
            context.drawText(textRenderer, typeIcon2 + " " + abilityName2, x, lineY, color2, false);
            lineY += 10;
        }

        // Line 3: attack cooldown bar (only when transformed)
        if (isTransformed) {
            int barWidth = 80;
            int barHeight = 4;
            int barX = x;
            int barY = lineY + 2;

            // Background bar
            context.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF333333);

            if (attackCooldownTicks > 0 && attackCooldownMax > 0) {
                // Cooldown in progress — amber fill
                float progress = 1.0f - ((float) attackCooldownTicks / attackCooldownMax);
                int fillWidth = Math.round(barWidth * progress);
                context.fill(barX, barY, barX + fillWidth, barY + barHeight, 0xFFF5A623);
            } else {
                // Ready — green fill
                context.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF22C55E);
            }

            // "ATK" label
            context.drawText(textRenderer, "ATK", barX + barWidth + 4, barY - 1, 0xAAAAAA, false);
        }
    }

    public static void setAbility(String species, String type1, String type2) {
        activeSpecies = species;
        activeType1 = type1;
        activeType2 = type2;
    }

    public static void clearAbility() {
        activeSpecies = null;
        activeType1 = null;
        activeType2 = null;
    }

    /**
     * Returns true if the attack cooldown bar is still filling (not ready yet).
     */
    public static boolean isOnCooldown() {
        if (attackCooldownTicks <= 0) return false;
        long elapsed = System.currentTimeMillis() - lastCooldownTrigger;
        int ticksElapsed = (int) (elapsed / 50);
        return (attackCooldownMax - ticksElapsed) > 0;
    }

    /**
     * Called by AttackInputMixin when the player fires an attack.
     * Sets the cooldown bar animation. Default 30 ticks (~1.5s).
     */
    public static void triggerAttackCooldown() {
        attackCooldownMax = 30; // approximate visual, server has real cooldown
        attackCooldownTicks = attackCooldownMax;
        lastCooldownTrigger = System.currentTimeMillis();
    }

    /**
     * Get the French display name for a Pokémon type's ability.
     */
    private static String getAbilityDisplayName(String type) {
        if (type == null) return "Endurance";
        return switch (type.toLowerCase()) {
            case "fire"     -> "Flamme Int\u00e9rieure";
            case "water"    -> "Nage Rapide";
            case "grass"    -> "Photosynth\u00e8se";
            case "electric" -> "Charge Statique";
            case "ice"      -> "Coeur de Glace";
            case "flying"   -> "Plume L\u00e9g\u00e8re";
            case "ground"   -> "Ancrage";
            case "rock"     -> "Peau de Pierre";
            case "steel"    -> "Blindage";
            case "poison"   -> "Immunit\u00e9 Toxique";
            case "psychic"  -> "Perception Extra";
            case "ghost"    -> "Ombre Furtive";
            case "dark"     -> "Vision Nocturne";
            case "dragon"   -> "Fureur Draconique";
            case "fairy"    -> "Gr\u00e2ce F\u00e9erique";
            case "fighting" -> "Esprit Combatif";
            case "bug"      -> "Agilit\u00e9 Insecte";
            default         -> "Endurance";
        };
    }

    private static String getTypeIcon(String type) {
        if (type == null) return "\u2B50";
        return switch (type.toLowerCase()) {
            case "fire"     -> "\uD83D\uDD25";
            case "water"    -> "\uD83D\uDCA7";
            case "grass"    -> "\uD83C\uDF3F";
            case "electric" -> "\u26A1";
            case "ice"      -> "\u2744";
            case "flying"   -> "\uD83D\uDD4A";
            case "ground"   -> "\uD83C\uDF0D";
            case "rock"     -> "\uD83E\uDEA8";
            case "steel"    -> "\u2699";
            case "poison"   -> "\u2620";
            case "psychic"  -> "\uD83D\uDD2E";
            case "ghost"    -> "\uD83D\uDC7B";
            case "dark"     -> "\uD83C\uDF11";
            case "dragon"   -> "\uD83D\uDC32";
            case "fairy"    -> "\u2728";
            case "fighting" -> "\uD83E\uDD4A";
            case "bug"      -> "\uD83D\uDC1B";
            default         -> "\u2B50";
        };
    }

    private static int getTypeColorHex(String type) {
        if (type == null) return 0xFFFFFF;
        return switch (type.toLowerCase()) {
            case "fire"     -> 0xFF5555;
            case "water"    -> 0x5555FF;
            case "grass"    -> 0x55FF55;
            case "electric" -> 0xFFFF55;
            case "ice"      -> 0x55FFFF;
            case "flying"   -> 0xFFFFFF;
            case "ground"   -> 0xFFAA00;
            case "rock"     -> 0xAAAAAA;
            case "steel"    -> 0x888888;
            case "poison"   -> 0xAA00AA;
            case "psychic"  -> 0xFF55FF;
            case "ghost"    -> 0xAA00AA;
            case "dark"     -> 0x555555;
            case "dragon"   -> 0x0000AA;
            case "fairy"    -> 0xFF55FF;
            case "fighting" -> 0xFFAA00;
            case "bug"      -> 0x00AA00;
            default         -> 0xFFFFFF;
        };
    }
}
