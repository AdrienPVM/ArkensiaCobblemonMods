package com.cheeseitems.client;

import com.cheeseitems.client.CheeseItemsClient.ClientCodexEntry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * Morpho Codex GUI — Arkensia premium aesthetic, designed to mirror the
 * cinematic gold/amber/crimson palette of the public web store.
 *
 * Two important details to keep in mind:
 *  - {@link #renderBackground(DrawContext, int, int, float)} is overridden to a no-op
 *    so vanilla's blur post-process never runs on top of our content (this was the
 *    real cause of the "fuzzy text" bug).
 *  - All glyph draws use integer-aligned coordinates and the unmodified text
 *    renderer to keep glyphs pixel-perfect.
 */
public class MorphoCodexScreen extends Screen {

    // Layout
    private static final int CARD_W   = 108;
    private static final int CARD_H   = 138;
    private static final int CARD_GAP = 14;
    private static final int GRID_PAD = 18;
    private static final int HEADER_H = 64;
    private static final int FOOTER_H = 64;

    // Palette — matches website tokens (gold + crimson + legendary purple on deep navy)
    private static final int COLOR_BG          = 0xFF080510;
    private static final int COLOR_BG_TOP      = 0xFF120A18;
    private static final int COLOR_BG_BOT      = 0xFF050308;
    private static final int COLOR_HEADER_BG   = 0xFF120A18;
    private static final int COLOR_GOLD        = 0xFFFFD86B;
    private static final int COLOR_GOLD_BRIGHT = 0xFFFFE89E;
    private static final int COLOR_GOLD_DIM    = 0xFFB87619;
    private static final int COLOR_DIVIDER     = 0xFF3A2030;
    private static final int COLOR_TEXT_LIGHT  = 0xFFF4ECD0;
    private static final int COLOR_TEXT_MUTED  = 0xFF8A7560;
    private static final int COLOR_TEXT_DIM    = 0xFF5A4A40;


    private List<ClientCodexEntry> entries;
    private float scrollY;
    private int hoverIndex = -1;

    public MorphoCodexScreen() {
        super(Text.translatable("cheeseitems.morpho.codex_title"));
    }

    @Override
    protected void init() {
        super.init();
        this.entries = CheeseItemsClient.snapshotCodex();

        int btnW = 200;
        this.addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget.builder(
            Text.translatable("cheeseitems.morpho.toggle_btn"),
            b -> { CheeseItemsClient.requestToggle(); this.close(); })
            .dimensions(this.width / 2 - btnW / 2, this.height - 44, btnW, 22)
            .build());

        this.addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget.builder(
            Text.translatable("cheeseitems.morpho.close_btn"),
            b -> this.close())
            .dimensions(this.width / 2 + btnW / 2 + 8, this.height - 44, 80, 22)
            .build());
    }

    /**
     * Vanilla applies a full-screen blur post-process to whatever is drawn behind
     * a Screen — the call is wired from {@code Screen#renderBackground}, which we
     * neutralise here so our title and grid glyphs stay crisp.
     */
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Intentional no-op — we paint our own background in render().
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Animated phase 0..1 used by pulsing accents (active card glow, badges).
        float phase = (float) ((System.currentTimeMillis() % 2400L) / 2400.0);
        float wave  = (float) (0.5 + 0.5 * Math.sin(phase * Math.PI * 2.0));

        // ── Background ────────────────────────────────────────────────
        ctx.fill(0, 0, this.width, this.height, COLOR_BG);
        ctx.fillGradient(0, 0, this.width, this.height / 2, COLOR_BG_TOP, COLOR_BG);
        ctx.fillGradient(0, this.height / 2, this.width, this.height, COLOR_BG, COLOR_BG_BOT);

        // ── Header ────────────────────────────────────────────────────
        ctx.fill(0, 0, this.width, HEADER_H, COLOR_HEADER_BG);
        // Soft amber glow under the header
        ctx.fillGradient(0, HEADER_H - 14, this.width, HEADER_H, 0x00FFD86B, 0x33B87619);
        // Gold double liseré + crimson micro-line
        ctx.fill(0, HEADER_H,     this.width, HEADER_H + 1, COLOR_GOLD);
        ctx.fill(0, HEADER_H + 1, this.width, HEADER_H + 2, COLOR_GOLD_DIM);
        ctx.fill(0, HEADER_H + 2, this.width, HEADER_H + 3, 0x55D62828);

        // Brand pre-title (small caps eyebrow)
        Text eyebrow = Text.translatable("cheeseitems.morpho.codex_eyebrow");
        ctx.drawCenteredTextWithShadow(this.textRenderer, eyebrow, this.width / 2, 12, COLOR_GOLD_DIM);
        // Title — drawn twice for a subtle "weight" (offset 1px) to feel like the website logotype
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 26, COLOR_GOLD);

        // Status sub-line
        String count = entries == null ? "0" : String.valueOf(entries.size());
        Text status = entries == null || entries.isEmpty()
            ? Text.translatable("cheeseitems.morpho.codex_empty")
            : Text.translatable("cheeseitems.morpho.codex_count", count);
        ctx.drawCenteredTextWithShadow(this.textRenderer, status, this.width / 2, 44, COLOR_TEXT_MUTED);

        // ── Wallet pill (top-right) — credits balance from CheeseStore via reflection.
        long bal = CheeseItemsClient.getWalletBalance();
        if (bal >= 0) {
            String cname = CheeseItemsClient.getCurrencyName();
            String label = String.format(java.util.Locale.ROOT, "%,d", bal).replace(',', ' ');
            if (cname != null && !cname.isEmpty()) {
                label = label + " " + (bal > 1 ? cname + "s" : cname);
            } else {
                label = label + " " + (bal > 1
                    ? Text.translatable("cheeseitems.morpho.credits_plural").getString()
                    : Text.translatable("cheeseitems.morpho.credits_singular").getString());
            }
            int padX = 10;
            int textW = this.textRenderer.getWidth(label);
            int pillW = textW + padX * 2;
            int pillH = 16;
            int pillX = this.width - pillW - 10;
            int pillY = 14;
            // Subtle dark pill with gold outline
            ctx.fill(pillX, pillY,             pillX + pillW, pillY + pillH,        0xCC15101A);
            ctx.fill(pillX, pillY,             pillX + pillW, pillY + 1,            COLOR_GOLD_DIM);
            ctx.fill(pillX, pillY + pillH - 1, pillX + pillW, pillY + pillH,        COLOR_GOLD_DIM);
            ctx.fill(pillX, pillY,             pillX + 1,     pillY + pillH,        COLOR_GOLD_DIM);
            ctx.fill(pillX + pillW - 1, pillY, pillX + pillW, pillY + pillH,        COLOR_GOLD_DIM);
            ctx.drawText(this.textRenderer, label, pillX + padX, pillY + 4, COLOR_GOLD_BRIGHT, false);
        }

        // ── Grid ──────────────────────────────────────────────────────
        int gridTop = HEADER_H + 12;
        int gridBottom = this.height - FOOTER_H;
        int cols = Math.max(1, (this.width - GRID_PAD * 2) / (CARD_W + CARD_GAP));
        int gridWidth = cols * CARD_W + (cols - 1) * CARD_GAP;
        int gridLeft = (this.width - gridWidth) / 2;

        ctx.enableScissor(0, gridTop - 4, this.width, gridBottom);

        hoverIndex = -1;
        String activeId = CheeseItemsClient.getActiveItemId();
        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                int x = gridLeft + col * (CARD_W + CARD_GAP);
                int y = gridTop + row * (CARD_H + CARD_GAP) - (int) scrollY;
                if (y + CARD_H < gridTop || y > gridBottom) continue;

                ClientCodexEntry e = entries.get(i);
                boolean active = e.itemId().equals(activeId);
                boolean hover  = mouseX >= x && mouseX < x + CARD_W
                              && mouseY >= y && mouseY < y + CARD_H
                              && mouseY >= gridTop && mouseY < gridBottom;
                if (hover) hoverIndex = i;
                drawCard(ctx, x, y, e, active, hover, wave);
            }
        }

        ctx.disableScissor();

        // Empty state hint when codex is empty
        if (entries != null && entries.isEmpty()) {
            int cx = this.width / 2;
            int cy = (gridTop + gridBottom) / 2;
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("cheeseitems.morpho.codex_empty_hint"),
                cx, cy, COLOR_TEXT_DIM);
        }

        // ── Footer ────────────────────────────────────────────────────
        ctx.fill(0, this.height - FOOTER_H,     this.width, this.height - FOOTER_H + 1, COLOR_GOLD_DIM);
        ctx.fill(0, this.height - FOOTER_H + 1, this.width, this.height - FOOTER_H + 2, COLOR_DIVIDER);

        Text hint = Text.translatable("cheeseitems.morpho.codex_hint");
        ctx.drawCenteredTextWithShadow(this.textRenderer, hint, this.width / 2, this.height - 16, COLOR_TEXT_DIM);

        // Buttons (registered via addDrawableChild)
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawCard(DrawContext ctx, int x, int y, ClientCodexEntry entry,
                          boolean active, boolean hover, float wave) {
        // Frame-less by design: only the sprite, the name, and (when active) a clean pill.
        // Hover is a faint amber wash behind the sprite, active is a soft pulsing halo.
        int spriteSize = 80;
        int spriteX = x + (CARD_W - spriteSize) / 2;
        int spriteY = y + 6;

        if (active) {
            int aHi = (int) (0x44 + 0x44 * wave);
            int aMd = (int) (0x18 + 0x22 * wave);
            int gHi = (aHi << 24) | 0x00FFD86B;
            int gMd = (aMd << 24) | 0x00FFD86B;
            // Two concentric haloes around the sprite area (no sharp box border).
            ctx.fill(spriteX - 2, spriteY - 2, spriteX + spriteSize + 2, spriteY + spriteSize + 2, gHi);
            ctx.fill(spriteX - 5, spriteY - 5, spriteX + spriteSize + 5, spriteY + spriteSize + 5, gMd);
        } else if (hover) {
            ctx.fill(spriteX - 2, spriteY - 2, spriteX + spriteSize + 2, spriteY + spriteSize + 2, 0x33B87619);
        }

        // Sprite — drawn flat, no well, no frame.
        Identifier spriteId = entry.hasImage() ? MorphoImageCache.getOrLoad(entry.itemId()) : null;
        if (spriteId != null) {
            try {
                ctx.drawTexture(spriteId, spriteX, spriteY, 0, 0, spriteSize, spriteSize, spriteSize, spriteSize);
            } catch (Exception ignored) {
                drawSpritePlaceholder(ctx, x, spriteY, spriteSize, entry);
            }
        } else {
            drawSpritePlaceholder(ctx, x, spriteY, spriteSize, entry);
        }

        // Title — small, below the sprite, no shadow or frame.
        String name = entry.displayName() == null || entry.displayName().isEmpty()
            ? entry.species() : entry.displayName();
        if (name == null) name = "?";
        if (name.length() > 15) name = name.substring(0, 14) + "…";
        int nameColor = active ? COLOR_GOLD_BRIGHT : (hover ? COLOR_GOLD : COLOR_TEXT_LIGHT);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(name),
            x + CARD_W / 2, spriteY + spriteSize + 6, nameColor);

        if (active) {
            // Clean gold pill — flat fill, no double-shadow, no inner highlight noise.
            int pillW = 60;
            int pillX = x + (CARD_W - pillW) / 2;
            int pillY = spriteY + spriteSize + 22;
            int pillH = 12;
            // Rounded look: slightly inset top/bottom rows to fake corners
            ctx.fill(pillX + 1, pillY,           pillX + pillW - 1, pillY + 1,         COLOR_GOLD);
            ctx.fill(pillX,     pillY + 1,       pillX + pillW,     pillY + pillH - 1, COLOR_GOLD);
            ctx.fill(pillX + 1, pillY + pillH - 1, pillX + pillW - 1, pillY + pillH,   COLOR_GOLD);
            // Centered label, dark ink, NO drop shadow (the shadow was the ugly bit).
            Text label = Text.translatable("cheeseitems.morpho.active");
            int textW = this.textRenderer.getWidth(label);
            ctx.drawText(this.textRenderer, label,
                x + CARD_W / 2 - textW / 2, pillY + 2, 0xFF1A0F08, false);
        }
    }

    private void drawSpritePlaceholder(DrawContext ctx, int x, int y, int h, ClientCodexEntry entry) {
        String letter = entry.species() == null || entry.species().isEmpty() ? "?"
            : entry.species().substring(0, 1).toUpperCase(Locale.ROOT);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal(letter),
            x + CARD_W / 2, y + h / 2 - 4, COLOR_GOLD_DIM);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hoverIndex >= 0 && entries != null && hoverIndex < entries.size()) {
            ClientCodexEntry e = entries.get(hoverIndex);
            CheeseItemsClient.requestSetActive(e.itemId());
            this.close();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        // Only scroll when the cursor is over the grid area.
        int gridTop = HEADER_H + 12;
        int gridBottom = this.height - FOOTER_H;
        if (mouseY < gridTop || mouseY > gridBottom) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        scrollY = Math.max(0, scrollY - (float) vertical * 24f);
        // Clamp to total content height.
        if (entries != null) {
            int cols = Math.max(1, (this.width - GRID_PAD * 2) / (CARD_W + CARD_GAP));
            int rows = (int) Math.ceil(entries.size() / (double) cols);
            float contentH = rows * (CARD_H + CARD_GAP);
            float visibleH = gridBottom - gridTop;
            float maxScroll = Math.max(0, contentH - visibleH);
            if (scrollY > maxScroll) scrollY = maxScroll;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { this.close(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() { return false; }
}
