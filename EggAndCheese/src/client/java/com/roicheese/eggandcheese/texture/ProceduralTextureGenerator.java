package com.roicheese.eggandcheese.texture;

import com.roicheese.eggandcheese.dna.*;
import net.minecraft.client.texture.NativeImage;

/**
 * Procedural texture generator — v4 (seamless cylindrical UV)
 *
 * Port of CheeseDash/assets/egg-preview.js texture generation.
 * Uses torus-mapped noise for seamless horizontal wrapping on the egg cylinder,
 * 32-bit splitmix hash matching JS, and wrapped drawing primitives.
 *
 * Full pipeline per egg (order matters):
 *   1.  Rarity base background (seamless noise)
 *   2.  Base gradient (vertical + seamless noise)
 *   3.  Primary pattern
 *   4.  Secondary pattern (blended at patternBlend %)
 *   5.  Pattern emboss (FLAG_EMBOSS: x-wrapped Sobel relief)
 *   6.  Shimmer / sparkle effects
 *   7.  Stamps (third layer iconographic shapes, wrapped)
 *   8.  Micro-texture (high-frequency surface material, seamless)
 *   9.  Directional lighting (FLAG_LIGHTING: cylindrical normals)
 *  10.  Specular highlight (FLAG_SPECULAR: cylinder-wrapped Gaussian)
 *  11.  Edge glow (FLAG_RIM_GLOW: top/bottom rims only)
 *  12.  Varnish layer (FLAG_VARNISH: sinusoidal cylinder sheen)
 *  13.  Highlight vignette (vertical gradient, seamless on x)
 *
 * Returns NativeImage[3]: [0]=color, [1]=emissive_f0, [2]=emissive_f1
 */
public class ProceduralTextureGenerator {

    public static final int SIZE = 64;

    public static NativeImage[] generate(CollectibleDNA dna) {
        NativeImage color   = new NativeImage(SIZE, SIZE, false);
        NativeImage emissF0 = new NativeImage(SIZE, SIZE, false);
        NativeImage emissF1 = new NativeImage(SIZE, SIZE, false);
        generateColor(color, dna);
        generateEmissive(emissF0, dna, 0L);
        generateEmissive(emissF1, dna, 999983L);
        return new NativeImage[]{color, emissF0, emissF1};
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  COLOR LAYER — 13-step pipeline
    // ═══════════════════════════════════════════════════════════════════════════

    private static void generateColor(NativeImage img, CollectibleDNA dna) {
        SeededRandom rng = new SeededRandom(dna.seed());
        int[] p = dna.getActiveColors();
        int primary   = p[0], secondary = p[1], highlight = p[2],
            shadow    = p[3], accent    = p[4], glow      = p[5];
        long seed = dna.seed();

        // ── Step 1: Rarity base background (seamless noise) ─────────────────
        applyRarityBackground(img, dna, primary, shadow);

        // ── Step 2: Base gradient (vertical + seamless noise) ───────────────
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            float dy = (y - SIZE * 0.5f) / (SIZE * 0.5f);
            float dist = Math.min(Math.abs(dy), 1f);
            float noise = seamlessValueNoise(x, y, 0.15f, 0.15f, seed) * 0.3f;
            int base = lerpColor(primary, shadow, Math.min(1f, dist + noise) * 0.65f);
            int cur = fromAbgr(img.getColor(x, y));
            img.setColor(x, y, toAbgr(blendColor(cur, base, 0.7f)));
        }

        // ── Step 3: Primary pattern ─────────────────────────────────────────
        applyPatternType(img, dna.pattern(), dna, rng,
            primary, secondary, highlight, shadow, accent, 1.0f);

        // ── Step 4: Secondary pattern (blended) ─────────────────────────────
        if (dna.patternBlend() > 0) {
            float blendAlpha = dna.patternBlend() / 100f * 0.85f;
            SeededRandom rng2 = new SeededRandom(seed + 77777L);
            applyPatternType(img, dna.secondaryPattern(), dna, rng2,
                accent, highlight, glow, shadow, primary, blendAlpha);
        }

        // ── Step 5: Pattern emboss (FLAG_EMBOSS) ────────────────────────────
        if (dna.hasFlag(CollectibleDNA.FLAG_EMBOSS)) {
            applyPatternOutline(img, shadow);
        }

        // ── Step 6: Shimmer / sparkle effects ───────────────────────────────
        applyRarityEffects(img, dna);

        // ── Step 7: Stamps ──────────────────────────────────────────────────
        if (dna.stamp() != StampType.NONE) {
            applyStamp(img, dna, rng, highlight, accent, glow, secondary);
        }

        // ── Step 8: Micro-texture ───────────────────────────────────────────
        if (dna.microTexture() != MicroTextureType.NONE) {
            applyMicroTexture(img, dna);
        }

        // ── Step 9: Directional lighting (cylindrical normals) ──────────────
        if (dna.hasFlag(CollectibleDNA.FLAG_LIGHTING)) {
            float Lx = -0.6f, Ly = -0.7f, Lz = 0.4f;
            float Llen = (float) Math.sqrt(Lx * Lx + Ly * Ly + Lz * Lz);
            Lx /= Llen; Ly /= Llen; Lz /= Llen;
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                float angle = (x / (float) SIZE) * (float) Math.PI * 2f;
                float nyV = (y - SIZE * 0.5f) / (SIZE * 0.5f);
                float nx = (float) Math.cos(angle), nz = (float) Math.sin(angle);
                float nLen = (float) Math.sqrt(nx * nx + nyV * nyV * 0.25f + nz * nz);
                nx /= nLen; float nny = nyV * 0.5f / nLen; nz /= nLen;
                float lambert = Math.max(0f, -(nx * Lx + nny * Ly + nz * Lz));
                float light = 0.45f + lambert * 0.55f;
                int cur = fromAbgr(img.getColor(x, y));
                int rr = clamp((int) (((cur >> 16) & 0xFF) * light), 0, 255);
                int gg = clamp((int) (((cur >> 8) & 0xFF) * light), 0, 255);
                int bb = clamp((int) ((cur & 0xFF) * light), 0, 255);
                img.setColor(x, y, toAbgr(0xFF000000 | (rr << 16) | (gg << 8) | bb));
            }
        }

        // ── Step 10: Specular (cylinder-wrapped distance) ───────────────────
        if (dna.hasFlag(CollectibleDNA.FLAG_SPECULAR)) {
            int hotX = SIZE / 4, hotY = SIZE / 4;
            float sigma = SIZE * 0.18f;
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                float rawDx = Math.abs(x - hotX);
                float dx = Math.min(rawDx, SIZE - rawDx);
                float dy = y - hotY;
                float gauss = (float) Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
                if (gauss < 0.01f) continue;
                int specCol = lerpColor(0xFFFFFFFF, highlight, 0.25f);
                float intensity = gauss * 0.65f;
                int cur = fromAbgr(img.getColor(x, y));
                img.setColor(x, y, toAbgr(blendColor(cur, specCol, intensity)));
            }
        }

        // ── Step 11: Edge glow — top/bottom rims only ───────────────────────
        if (dna.hasFlag(CollectibleDNA.FLAG_RIM_GLOW)) {
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                float v = y / (float) SIZE;
                float rimV_top = Math.max(0f, 1f - v / 0.18f);
                float rimV_bot = Math.max(0f, 1f - (1f - v) / 0.18f);
                float rim = Math.max(rimV_top, rimV_bot);
                rim = rim * rim;
                if (rim > 0.01f) {
                    int cur = fromAbgr(img.getColor(x, y));
                    int glowBright = lerpColor(glow, 0xFFFFFFFF, rim * 0.35f);
                    img.setColor(x, y, toAbgr(blendColor(cur, glowBright, rim * 0.80f)));
                }
            }
        }

        // ── Step 12: Varnish (sinusoidal cylinder sheen) ────────────────────
        if (dna.hasFlag(CollectibleDNA.FLAG_VARNISH)) {
            float strength = dna.varnishLevel() / 100f * 0.35f;
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                float band = (float) Math.sin(x / (float) SIZE * (float) Math.PI * 2f) * 0.5f + 0.5f
                           + (y / (float) SIZE) * 0.3f;
                float sheen = (float) Math.pow(Math.max(0f, (float) Math.sin(band * (float) Math.PI * 1.5f)), 3f);
                if (sheen > 0.05f) {
                    int cur = fromAbgr(img.getColor(x, y));
                    img.setColor(x, y, toAbgr(blendColor(cur,
                        lerpColor(highlight, 0xFFFFFFFF, 0.5f), sheen * strength)));
                }
            }
        }

        // ── Step 13: Highlight vignette (vertical gradient) ─────────────────
        for (int y = 0; y < SIZE; y++) {
            float vy = 1f - y / (float) SIZE;
            float hl = Math.max(0f, vy - 0.25f) / 0.75f;
            if (hl <= 0.04f) continue;
            float hlAlpha = hl * hl * 0.18f;
            for (int x = 0; x < SIZE; x++) {
                int cur = fromAbgr(img.getColor(x, y));
                img.setColor(x, y, toAbgr(blendColor(cur, highlight, hlAlpha)));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  STEP 1 — RARITY BACKGROUND (seamless noise)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void applyRarityBackground(NativeImage img, CollectibleDNA dna,
                                               int primary, int shadow) {
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            float n = seamlessValueNoise(x, y, 0.1f, 0.1f, dna.seed() + 55555L);
            img.setColor(x, y, toAbgr(lerpColor(primary, shadow, n * 0.35f)));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  STEP 5 — EMBOSS (x-wrapped Sobel relief)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void applyPatternOutline(NativeImage img, int shadowColor) {
        int[] snap = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++)
                snap[y * SIZE + x] = fromAbgr(img.getColor(x, y));

        for (int y = 1; y < SIZE - 1; y++) for (int x = 0; x < SIZE; x++) {
            int xR = (x + 1) % SIZE, xL = (x - 1 + SIZE) % SIZE;
            int cR = snap[y * SIZE + xR], cL = snap[y * SIZE + xL];
            int cD = snap[(y + 1) * SIZE + x], cU = snap[(y - 1) * SIZE + x];

            float dx = colorBrightness(cR) - colorBrightness(cL);
            float dy = colorBrightness(cD) - colorBrightness(cU);
            float emboss = (dx - dy) * 0.5f;

            if (Math.abs(emboss) > 0.04f) {
                int base = fromAbgr(img.getColor(x, y));
                int result = emboss > 0
                    ? blendColor(base, 0xFFFFFFFF, Math.max(0f, emboss) * 0.55f)
                    : blendColor(base, shadowColor, Math.max(0f, -emboss) * 0.65f);
                img.setColor(x, y, toAbgr(result));
            }
        }
    }

    private static float colorBrightness(int rgb) {
        return ((rgb >> 16 & 0xFF) * 0.299f + (rgb >> 8 & 0xFF) * 0.587f + (rgb & 0xFF) * 0.114f) / 255f;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  STEP 6 — SHIMMER & SPARKLE (seamless noise + vertical rim)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void applyRarityEffects(NativeImage img, CollectibleDNA dna) {
        int[] p = dna.getActiveColors();
        int highlight = p[2], glow = p[5];
        long seed = dna.seed();

        // Seamless shimmer highlights
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            float n = seamlessValueNoise(x, y, 0.25f, 0.25f, seed + 9999L);
            if (n > 0.84f)
                img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)),
                    highlight, (n - 0.84f) / 0.16f * 0.4f)));
        }

        // Vertical rim (top/bottom edges, seamless on x)
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            float dy = (y - SIZE * 0.5f) / (SIZE * 0.5f);
            float dist = Math.abs(dy);
            if (dist > 0.78f && dist < 1.0f) {
                float rim = (dist - 0.78f) / 0.22f;
                float pulse = ((float) Math.sin(x / (float) SIZE * (float) Math.PI * 2f * 3f
                    + y * 0.7f + seed) + 1f) * 0.5f;
                img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)),
                    lerpColor(glow, highlight, pulse * 0.4f), rim * 0.5f)));
            }
        }

        // Sparkle highlights (SeededRandom for determinism)
        SeededRandom sparkRng = new SeededRandom(seed + 0xABCDEF01L);
        int sparkCount = 6 + sparkRng.nextInt(8);
        for (int i = 0; i < sparkCount; i++) {
            int sx = sparkRng.nextInt(SIZE), sy = sparkRng.nextInt(SIZE);
            setPixelWrapped(img, sx, sy, 0xFFFFFFFF);
            int sxL = (sx - 1 + SIZE) % SIZE, sxR = (sx + 1) % SIZE;
            setPixelWrapped(img, sxL, sy, blendColor(fromAbgr(img.getColor(sxL, sy)), highlight, 0.5f));
            setPixelWrapped(img, sxR, sy, blendColor(fromAbgr(img.getColor(sxR, sy)), highlight, 0.5f));
            if (sy > 0)
                img.setColor(sx, sy - 1, toAbgr(blendColor(fromAbgr(img.getColor(sx, sy - 1)), highlight, 0.5f)));
            if (sy < SIZE - 1)
                img.setColor(sx, sy + 1, toAbgr(blendColor(fromAbgr(img.getColor(sx, sy + 1)), highlight, 0.5f)));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  STEP 7 — STAMPS (horizontally wrapped)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void applyStamp(NativeImage img, CollectibleDNA dna, SeededRandom rng,
                                    int highlight, int accent, int glow, int secondary) {
        if (dna.stamp() == null || dna.stamp() == StampType.NONE) return;
        int r = 6, cols = 4, rows = 4;
        int stepX = SIZE / cols, stepY = SIZE / rows;
        int col1 = highlight, col2 = secondary;
        float alpha = 0.70f;

        for (int row = 0; row < rows; row++) {
            int offsetX = (row % 2 == 0) ? 0 : stepX / 2;
            for (int col = 0; col < cols; col++) {
                int cx = offsetX + col * stepX + stepX / 2;
                int cy = row * stepY + stepY / 2;
                cx = ((cx % SIZE) + SIZE) % SIZE;
                cy = Math.max(r + 2, Math.min(SIZE - r - 3, cy));

                switch (dna.stamp()) {
                    case STAR     -> drawStar(img, cx, cy, r, r / 2, 5, col1, col2, alpha);
                    case MOON     -> drawMoon(img, cx, cy, r, col1, alpha);
                    case HEART    -> drawHeart(img, cx, cy, r, col1, col2, alpha);
                    case SPIRAL   -> drawSpiral(img, cx, cy, r, col1, alpha);
                    case DIAMOND  -> drawDiamond(img, cx, cy, r, col1, col2, alpha);
                    case RUNE_TRIPLE -> drawRuneTriple(img, cx, cy, r, col1, alpha);
                    case ARROW    -> drawArrow(img, cx, cy, r, col1, alpha);
                    case CROWN    -> drawCrown(img, cx, cy, r, col1, col2, alpha);
                    case INFINITY -> drawInfinity(img, cx, cy, r, col1, alpha);
                    default -> {}
                }
            }
        }
    }

    private static void drawStar(NativeImage img, int cx, int cy, int outerR, int innerR, int points,
                                   int col1, int col2, float alpha) {
        for (int y = cy - outerR - 2; y <= cy + outerR + 2; y++) {
            if (y < 0 || y >= SIZE) continue;
            for (int dx = -outerR - 2; dx <= outerR + 2; dx++) {
                int x = ((cx + dx) % SIZE + SIZE) % SIZE;
                double angle = Math.atan2(y - cy, dx) + Math.PI / 2;
                double dist = Math.sqrt(dx * dx + (y - cy) * (y - cy));
                double section = angle / (Math.PI * 2 / points);
                double frac = section - Math.floor(section);
                double starR = innerR + (outerR - innerR) * (1 - Math.abs(frac - 0.5) * 2);
                if (dist <= starR + 0.5) {
                    float t = (float) (dist / starR);
                    int col = t < 0.5f ? col1 : col2;
                    float a = alpha * (1f - Math.max(0f, (float) (dist - starR)));
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, a)));
                }
            }
        }
    }

    private static void drawMoon(NativeImage img, int cx, int cy, int r, int col, float alpha) {
        int off = r / 3;
        for (int y = cy - r; y <= cy + r; y++) {
            if (y < 0 || y >= SIZE) continue;
            for (int ddx = -r; ddx <= r; ddx++) {
                int x = ((cx + ddx) % SIZE + SIZE) % SIZE;
                float d1 = (float) Math.sqrt(ddx * ddx + (y - cy) * (y - cy));
                float d2 = (float) Math.sqrt((ddx - off) * (ddx - off) + (y - cy) * (y - cy));
                if (d1 <= r && d2 > r * 0.75f)
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)),
                        col, alpha * (1f - d1 / r * 0.3f))));
            }
        }
    }

    private static void drawHeart(NativeImage img, int cx, int cy, int r, int col1, int col2, float alpha) {
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
            int x = ((cx + dx) % SIZE + SIZE) % SIZE, y = cy - dy;
            if (y < 0 || y >= SIZE) continue;
            float nx = dx / (float) r, ny = dy / (float) r;
            float v = (nx * nx + ny * ny - 1);
            float heart = v * v * v - nx * nx * ny * ny * ny;
            if (heart <= 0.08f) {
                float t = Math.max(0f, heart / 0.08f);
                img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)),
                    lerpColor(col1, col2, t), alpha * (1f - t * 0.4f))));
            }
        }
    }

    private static void drawSpiral(NativeImage img, int cx, int cy, int r, int col, float alpha) {
        int turns = 3;
        for (float t = 0; t < turns * (float) Math.PI * 2; t += 0.05f) {
            float sr = (t / (turns * (float) Math.PI * 2)) * r;
            int x = ((cx + (int) (Math.cos(t) * sr)) % SIZE + SIZE) % SIZE;
            int y = cy + (int) (Math.sin(t) * sr);
            if (y >= 0 && y < SIZE)
                img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha)));
        }
    }

    private static void drawDiamond(NativeImage img, int cx, int cy, int r, int col1, int col2, float alpha) {
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
            int x = ((cx + dx) % SIZE + SIZE) % SIZE, y = cy + dy;
            if (y < 0 || y >= SIZE) continue;
            if (Math.abs(dx) + Math.abs(dy) <= r) {
                float t = (float) (Math.abs(dx) + Math.abs(dy)) / r;
                img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)),
                    lerpColor(col1, col2, t), alpha)));
            }
        }
    }

    private static void drawRuneTriple(NativeImage img, int cx, int cy, int r, int col, float alpha) {
        for (int i = 0; i < 3; i++) {
            double angle = i * Math.PI * 2 / 3;
            drawLineWrapped(img, cx, cy, cx + (int) (Math.cos(angle) * r), cy + (int) (Math.sin(angle) * r), col, alpha);
            drawLineWrapped(img, cx - (int) (Math.cos(angle) * r / 3), cy - (int) (Math.sin(angle) * r / 3),
                cx + (int) (Math.cos(angle + Math.PI / 2) * r / 2), cy + (int) (Math.sin(angle + Math.PI / 2) * r / 2),
                col, alpha * 0.7f);
        }
        drawEllipseWrapped(img, cx, cy, 2, 2, col, alpha);
    }

    private static void drawArrow(NativeImage img, int cx, int cy, int r, int col, float alpha) {
        drawLineWrapped(img, cx, cy - r, cx, cy + r / 2, col, alpha);
        drawLineWrapped(img, cx, cy - r, cx - r / 2, cy - r / 3, col, alpha);
        drawLineWrapped(img, cx, cy - r, cx + r / 2, cy - r / 3, col, alpha);
    }

    private static void drawCrown(NativeImage img, int cx, int cy, int r, int col1, int col2, float alpha) {
        int by = cy + r / 2;
        if (by >= 0 && by < SIZE)
            for (int dx = -r; dx <= r; dx++) {
                int wx = ((cx + dx) % SIZE + SIZE) % SIZE;
                img.setColor(wx, by, toAbgr(blendColor(fromAbgr(img.getColor(wx, by)), col1, alpha)));
            }
        int[] spikeX = {cx - r, cx - r / 2, cx, cx + r / 2, cx + r};
        int[] spikeH = {r / 2, r, r * 3 / 2, r, r / 2};
        for (int i = 0; i < 5; i++) {
            drawLineWrapped(img, spikeX[i], by, spikeX[i], by - spikeH[i], col1, alpha);
            drawEllipseWrapped(img, spikeX[i], by - spikeH[i], 2, 2, col2, alpha);
        }
        for (int dx = -r; dx <= r; dx++) for (int y = by; y <= by + r / 3; y++) {
            if (y < 0 || y >= SIZE) continue;
            int wx = ((cx + dx) % SIZE + SIZE) % SIZE;
            img.setColor(wx, y, toAbgr(blendColor(fromAbgr(img.getColor(wx, y)), col1, alpha * 0.7f)));
        }
    }

    private static void drawInfinity(NativeImage img, int cx, int cy, int r, int col, float alpha) {
        int off = r / 2;
        for (float t = 0; t < (float) Math.PI * 2; t += 0.04f) {
            int x1 = ((cx - off + (int) (Math.cos(t) * off)) % SIZE + SIZE) % SIZE;
            int y1 = cy + (int) (Math.sin(t) * off / 2);
            int x2 = ((cx + off + (int) (Math.cos(t + Math.PI) * off)) % SIZE + SIZE) % SIZE;
            int y2 = cy + (int) (Math.sin(t + Math.PI) * off / 2);
            if (y1 >= 0 && y1 < SIZE)
                img.setColor(x1, y1, toAbgr(blendColor(fromAbgr(img.getColor(x1, y1)), col, alpha)));
            if (y2 >= 0 && y2 < SIZE)
                img.setColor(x2, y2, toAbgr(blendColor(fromAbgr(img.getColor(x2, y2)), col, alpha)));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PATTERNS (26 types)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void applyPatternType(NativeImage img, PatternType type,
            CollectibleDNA dna, SeededRandom rng,
            int primary, int secondary, int highlight, int shadow, int accent, float alpha) {
        int sc = dna.patternScale();
        long seed = dna.seed();
        switch (type) {

            case SPOTS -> {
                int count = 8 + rng.nextInt(14);
                for (int i = 0; i < count; i++) {
                    int col = rng.nextInt(3) == 0 ? highlight : (rng.nextBoolean() ? secondary : accent);
                    drawEllipseWrapped(img, rng.nextInt(SIZE), rng.nextInt(SIZE),
                        (2 + rng.nextInt(5)) * sc,
                        (int) ((2 + rng.nextInt(5)) * sc * (0.5f + rng.nextFloat() * 0.9f)),
                        col, alpha * (0.4f + rng.nextFloat() * 0.4f));
                }
            }

            case STRIPES -> {
                int freq = 2 + rng.nextInt(3);
                float slope = (0.3f + rng.nextFloat() * 0.6f) * (rng.nextBoolean() ? 1 : -1);
                int sw = (2 + rng.nextInt(3)) * sc;
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float v = (float) Math.sin(x / (float) SIZE * (float) Math.PI * 2f * freq + y * slope) * 0.5f + 0.5f;
                    int band = (int) (v * 3);
                    int col = band == 0 ? secondary : band == 1 ? accent : primary;
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * 0.55f)));
                }
            }

            case MARBLE -> {
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float n = seamlessTurbulence(x, y, 0.08f / sc, 0.08f / sc, seed + 1L, 5);
                    float m = ((float) Math.sin(x / (float) SIZE * (float) Math.PI * 2f + n * 4.8f) + 1f) * 0.5f;
                    float m2 = ((float) Math.sin((y + n * 25f) * 0.18f) + 1f) * 0.5f;
                    int col = m > 0.6f ? lerpColor(secondary, highlight, m2) : lerpColor(primary, accent, m);
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * 0.65f)));
                }
            }

            case GEOMETRIC -> {
                int cells = (3 + rng.nextInt(4)) * sc;
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    int cx2 = ((x * cells / SIZE)) % cells;
                    int cy2 = (y * cells / SIZE);
                    int col = ((cx2 + cy2) % 3 == 0) ? secondary : ((cx2 + cy2) % 3 == 1) ? accent : primary;
                    float hv = seamlessValueNoise(x, y, 0.12f / sc, 0.10f / sc, seed + 55L);
                    if (hv > 0.85f) col = highlight;
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * 0.55f)));
                }
            }

            case VEINS -> {
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float v = seamlessVeinNoise(x, y, 0.10f / sc, 0.10f / sc, seed);
                    if (v > 0.65f) {
                        float s2 = (v - 0.65f) / 0.35f;
                        int col = s2 > 0.7f ? highlight : secondary;
                        img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * s2 * 0.85f)));
                    }
                }
            }

            case CONSTELLATION -> {
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++)
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), shadow, alpha * 0.7f)));
                int stars = 12 + rng.nextInt(10);
                int[][] pts = new int[stars][2];
                for (int i = 0; i < stars; i++) {
                    pts[i][0] = rng.nextInt(SIZE); pts[i][1] = rng.nextInt(SIZE);
                    drawEllipseWrapped(img, pts[i][0], pts[i][1], 1, 1, highlight, alpha * 0.9f);
                }
                for (int i = 0; i < stars - 1; i += 2)
                    drawLineWrapped(img, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], secondary, alpha * 0.3f);
            }

            case CRYSTALS -> {
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float scl = 1f / (4f * sc);
                    float n  = seamlessValueNoise(x, y, scl, scl, seed);
                    float n2 = seamlessValueNoise(x, y, scl, scl, seed + 13L);
                    int col = n > 0.5f ? (n2 > 0.5f ? highlight : secondary) : (n2 > 0.5f ? accent : primary);
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * 0.6f)));
                }
            }

            case BUBBLES -> {
                for (int i = 0; i < 15 + rng.nextInt(10); i++) {
                    int bx = rng.nextInt(SIZE), by = rng.nextInt(SIZE);
                    int br = (int) ((3 + rng.nextInt(6)) * sc / 2f);
                    for (int dy2 = -br; dy2 <= br; dy2++) for (int dx = -br; dx <= br; dx++) {
                        int ny2 = by + dy2;
                        if (ny2 < 0 || ny2 >= SIZE) continue;
                        int wx = ((bx + dx) % SIZE + SIZE) % SIZE;
                        float dd = (float) Math.sqrt(dx * dx + dy2 * dy2);
                        if (dd <= br) {
                            float rim = Math.abs(dd - br * 0.85f) / (br * 0.15f + 1f);
                            int col = dd < br * 0.6f ? blendColor(primary, secondary, 0.3f)
                                    : rim < 0.4f ? highlight : secondary;
                            img.setColor(wx, ny2, toAbgr(blendColor(fromAbgr(img.getColor(wx, ny2)),
                                col, alpha * (1f - dd / br) * 0.7f)));
                        }
                    }
                }
            }

            case WAVES -> {
                int waveCycles = Math.max(1, Math.round(SIZE * 0.28f / sc / ((float) Math.PI * 2f)));
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float n = seamlessValueNoise(x, y, 0.04f, 0.04f, seed);
                    float w1 = ((float) Math.sin(x / (float) SIZE * (float) Math.PI * 2f * waveCycles + n * 6f) + 1f) * 0.5f;
                    float w2 = ((float) Math.cos(y * 0.22f / sc + n * 5f) + 1f) * 0.5f;
                    float c2 = (w1 + w2) * 0.5f;
                    int col = c2 > 0.75f ? highlight : c2 > 0.5f ? secondary : c2 > 0.25f ? accent : primary;
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * 0.6f)));
                }
            }

            case MOSAIC -> {
                int ts = Math.max(2, 4 * sc / 2);
                int maxTiles = (SIZE + ts - 1) / ts;
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    int tileX = (((x / ts) % maxTiles) + maxTiles) % maxTiles;
                    int tileY = y / ts;
                    SeededRandom tr = new SeededRandom(seed + tileX * 1000L + tileY);
                    float tv = tr.nextFloat();
                    int col = tv > 0.7f ? secondary : tv > 0.45f ? accent : tv > 0.2f ? primary : highlight;
                    boolean border = x % ts == 0 || y % ts == 0;
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)),
                        border ? blendColor(col, shadow, 0.6f) : col, alpha * 0.6f)));
                }
            }

            case SCALES -> {
                float scaleSize = 6f * sc;
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    int row = (int) Math.floor(y / scaleSize);
                    float ox = (row % 2 == 0) ? 0 : scaleSize * 0.5f;
                    float lx = (((x + ox) % scaleSize) + scaleSize) % scaleSize - scaleSize * 0.5f;
                    float ly = (y % scaleSize) - scaleSize * 0.5f;
                    float dist = (float) Math.sqrt(lx * lx * 0.6f + ly * ly);
                    float edge = dist / (scaleSize * 0.55f);
                    int col = edge < 0.6f ? secondary : edge < 0.85f ? accent : shadow;
                    if (edge > 0.92f) col = highlight;
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * 0.7f)));
                }
            }

            case CRACKS -> {
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float v = seamlessVeinNoise(x, y, 0.18f / sc, 0.18f / sc, seed + 333L);
                    float v2 = seamlessVeinNoise(x, y, 0.28f / sc, 0.28f / sc, seed + 444L);
                    float thin = Math.min(v, v2);
                    if (thin < 0.10f) {
                        float depth = thin / 0.10f;
                        int crackCol = depth < 0.5f
                            ? lerpColor(shadow, secondary, depth * 0.6f)
                            : lerpColor(shadow, accent, (depth - 0.5f) * 0.8f);
                        img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)),
                            crackCol, alpha * (1f - depth) * 0.85f)));
                    }
                }
            }

            case GALAXY -> {
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++)
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), shadow, alpha * 0.8f)));
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float n = seamlessTurbulence(x, y, 0.06f, 0.06f, seed, 4);
                    if (n > 0.4f) {
                        int col = n > 0.7f ? highlight : n > 0.55f ? secondary : accent;
                        img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * (n - 0.4f) * 1.2f)));
                    }
                }
                // Nebula clouds
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float nb = seamlessValueNoise(x, y, 0.09f, 0.09f, seed + 789L);
                    if (nb > 0.6f && nb < 0.75f)
                        img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)),
                            secondary, alpha * (nb - 0.6f) * 4f)));
                }
                // Stars
                for (int i = 0; i < 40 + rng.nextInt(30); i++) {
                    int sx2 = rng.nextInt(SIZE), sy2 = rng.nextInt(SIZE);
                    img.setColor(sx2, sy2, toAbgr(blendColor(fromAbgr(img.getColor(sx2, sy2)), 0xFFFFFFFF, alpha * 0.9f)));
                }
            }

            case RUNES -> {
                int count2 = 5 + rng.nextInt(8);
                for (int i = 0; i < count2; i++) {
                    int rx = rng.nextInt(SIZE), ry = 4 + rng.nextInt(SIZE - 8);
                    int r2 = (2 + rng.nextInt(3)) * sc;
                    int col = rng.nextInt(3) == 0 ? secondary : (rng.nextBoolean() ? highlight : accent);
                    drawLineWrapped(img, rx - r2, ry, rx + r2, ry, col, alpha * 0.85f);
                    drawLineWrapped(img, rx, ry - r2, rx, ry + r2, col, alpha * 0.85f);
                    if (rng.nextBoolean()) drawLineWrapped(img, rx - r2, ry - r2, rx + r2, ry + r2, col, alpha * 0.6f);
                    if (rng.nextBoolean()) drawLineWrapped(img, rx - r2, ry + r2, rx + r2, ry - r2, col, alpha * 0.6f);
                }
            }

            case SPLATTER -> {
                for (int i = 0; i < 20 + rng.nextInt(20); i++) {
                    int sx2 = rng.nextInt(SIZE), sy2 = rng.nextInt(SIZE);
                    int sr = (int) (1 + rng.nextInt(4) * sc / 2f);
                    int col = rng.nextInt(4) == 0 ? highlight : rng.nextBoolean() ? secondary : accent;
                    drawEllipseWrapped(img, sx2, sy2, sr, (int) (sr * (0.4f + rng.nextFloat() * 0.8f)),
                        col, alpha * (0.5f + rng.nextFloat() * 0.4f));
                    drawLineWrapped(img, sx2, sy2, sx2 + rng.nextInt(11) - 5, sy2 + rng.nextInt(11) - 5,
                        col, alpha * 0.35f);
                }
            }

            case CIRCUITS -> {
                int grid = 8 * sc;
                for (int y = 0; y < SIZE; y += grid) for (int x = 0; x < SIZE; x++)
                    img.setColor(x, Math.min(y, SIZE - 1), toAbgr(blendColor(fromAbgr(
                        img.getColor(x, Math.min(y, SIZE - 1))), secondary, alpha * 0.5f)));
                for (int x = 0; x < SIZE; x += grid) for (int y = 0; y < SIZE; y++)
                    img.setColor(x % SIZE, y, toAbgr(blendColor(fromAbgr(
                        img.getColor(x % SIZE, y)), secondary, alpha * 0.5f)));
                for (int y = 0; y < SIZE; y += grid) for (int x = 0; x < SIZE; x += grid)
                    if (rng.nextBoolean())
                        drawEllipseWrapped(img, x % SIZE, Math.min(y, SIZE - 1), 2, 2, highlight, alpha * 0.9f);
            }

            case TIEDYE -> {
                int cx3 = rng.nextInt(SIZE / 2) + SIZE / 4;
                int cy3 = rng.nextInt(SIZE / 2) + SIZE / 4;
                int[] cols = {primary, secondary, highlight, accent, shadow};
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    int rawDx = x - cx3;
                    int dx3 = Math.abs(rawDx) < SIZE / 2 ? rawDx : (rawDx > 0 ? rawDx - SIZE : rawDx + SIZE);
                    int dy3 = y - cy3;
                    float angle2 = (float) Math.atan2(dy3, dx3);
                    float dist2 = (float) Math.sqrt(dx3 * dx3 + dy3 * dy3);
                    float swirl = angle2 + dist2 * 0.1f / sc;
                    int band2 = (int) (Math.abs(swirl) / 0.4f) % cols.length;
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), cols[band2], alpha * 0.65f)));
                }
            }

            case GRADIENT -> {
                int[] stops = {primary, secondary, accent, highlight, shadow};
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float t = ((float) Math.sin(x / (float) SIZE * (float) Math.PI * 2f) * 0.5f + 0.5f + y / (float) SIZE) / 2f;
                    float scaled = t * (stops.length - 1);
                    int i1 = Math.min((int) scaled, stops.length - 2), i2 = i1 + 1;
                    int col = lerpColor(stops[i1], stops[i2], scaled - i1);
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * 0.7f)));
                }
            }

            case HONEYCOMB -> {
                int hexH = Math.max(3, (int) (8 * sc));
                int hexW = (int) (hexH * 1.732f);
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    int row = y / hexH;
                    int offX = (row % 2 == 0) ? 0 : hexW / 2;
                    int wx = ((x + offX) % hexW + hexW) % hexW;
                    int wy = y % hexH;
                    float cxH = wx - hexW / 2f, cyH = wy - hexH / 2f;
                    float edge = Math.max(Math.abs(cxH) / (hexW / 2f),
                        Math.max(Math.abs(cyH) / (hexH / 2f),
                            Math.abs(cxH / (hexW / 2f) + cyH / (hexH / 2f)) * 0.67f));
                    if (edge > 0.75f) {
                        img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)),
                            shadow, alpha * Math.min(1f, (edge - 0.75f) * 6f))));
                    } else {
                        int cellIdx = (row + ((x + offX) / hexW)) % 3;
                        int col = cellIdx == 0 ? highlight : cellIdx == 1 ? secondary : primary;
                        img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * 0.35f)));
                    }
                }
            }

            case FEATHERS -> {
                int arcH = Math.max(4, (int) (10 * sc));
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    int row = y / arcH;
                    int offX = (row % 2 == 0) ? 0 : SIZE / 6;
                    int period = SIZE / 3;
                    int wx = ((x + offX) % period + period) % period;
                    float localY = y % arcH;
                    float cxF = wx / (float) period - 0.5f;
                    float arc = 1f - 4f * cxF * cxF;
                    float tF = localY / arcH;
                    if (tF < arc * 0.9f) {
                        float barb = (float) Math.sin(tF * (float) Math.PI * 4f + cxF * 8f) * 0.5f + 0.5f;
                        int col = barb > 0.5f ? highlight : secondary;
                        img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * 0.5f * arc)));
                    }
                }
            }

            case FLORA -> {
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float n1 = seamlessTurbulence(x, y, 0.15f * sc, 0.15f * sc, seed, 3);
                    float n2 = seamlessValueNoise(x, y, 0.3f * sc, 0.3f * sc, seed + 100L);
                    boolean leaf = n1 > 0.52f && n1 < 0.68f;
                    boolean vein = Math.abs(n2 - 0.5f) < 0.04f && leaf;
                    if (vein) img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), shadow, alpha * 0.8f)));
                    else if (leaf) img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), primary, alpha * 0.6f)));
                    else if (n1 > 0.45f) img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), secondary, alpha * 0.25f)));
                }
            }

            case ZIGZAG -> {
                int freq = Math.max(2, 4 * sc);
                int bandH = Math.max(3, 6 * sc);
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float angle = (x / (float) SIZE) * (float) Math.PI * 2f;
                    float wave = ((float) Math.asin((float) Math.sin(angle * freq + y * 0.3f))
                        / ((float) Math.PI / 2f) + 1f) / 2f;
                    int band = (int) ((y + wave * bandH) / bandH);
                    int col = band % 2 == 0 ? primary : secondary;
                    float edge = Math.abs(wave - 0.5f) * 2f;
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)),
                        col, alpha * (0.4f + edge * 0.3f))));
                }
            }

            case CROSSHATCH -> {
                int spacing = Math.max(3, 6 * sc);
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float a1 = (x / (float) SIZE) * (float) Math.PI * 2f;
                    float line1 = Math.abs((float) Math.sin(a1 * (SIZE / (float) spacing) + y * 0.5f));
                    float line2 = Math.abs((float) Math.sin(a1 * (SIZE / (float) spacing) - y * 0.5f + 1.0f));
                    if (line1 < 0.12f)
                        img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), primary, alpha * 0.7f)));
                    else if (line2 < 0.12f)
                        img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), secondary, alpha * 0.6f)));
                }
            }

            case SMOKE -> {
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float n1 = seamlessTurbulence(x, y, 0.08f * sc, 0.08f * sc, seed, 4);
                    float n2 = seamlessTurbulence(x, y, 0.16f * sc, 0.16f * sc, seed + 77L, 3);
                    float blend = n1 * 0.6f + n2 * 0.4f;
                    int col = blend > 0.55f ? highlight : (blend > 0.4f ? primary : secondary);
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col, alpha * blend * 0.8f)));
                }
            }

            case TRIBAL -> {
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float n = seamlessValueNoise(x, y, 0.2f * sc, 0.2f * sc, seed);
                    float angle = (x / (float) SIZE) * (float) Math.PI * 2f;
                    float band = (float) Math.sin(angle * 3f + n * 4f)
                               * (float) Math.sin(y / (float) SIZE * (float) Math.PI * 2f + n * 3f);
                    if (Math.abs(band) < 0.15f)
                        img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), primary, alpha * 0.8f)));
                    else if (Math.abs(band) < 0.25f)
                        img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), accent, alpha * 0.4f)));
                }
            }

            case FRACTAL -> {
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    float val = 0, amp = 1f, freq = 0.15f * sc;
                    for (int o = 0; o < 5; o++) {
                        val += seamlessValueNoise(x, y, freq, freq, seed + o * 31L) * amp;
                        amp *= 0.5f; freq *= 2f;
                    }
                    val /= 1.9375f;
                    float sharp = val > 0.55f ? 1f : (val > 0.45f ? (val - 0.45f) * 10f : 0f);
                    int col = sharp > 0.5f ? highlight : primary;
                    img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), col,
                        alpha * (0.3f + sharp * 0.5f))));
                }
            }

            default -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  STEP 8 — MICRO-TEXTURE (seamless)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void applyMicroTexture(NativeImage img, CollectibleDNA dna) {
        long ms = dna.seed() + 424242L;
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int cur = fromAbgr(img.getColor(x, y));
            float factor;
            switch (dna.microTexture()) {
                case GRANULAR -> factor = seamlessValueNoise(x, y, 0.8f, 0.8f, ms) * 0.18f - 0.09f;
                case METALLIC -> {
                    float n = seamlessValueNoise(x, y, 0.4f, 0.4f, ms);
                    float a = (x / (float) SIZE) * (float) Math.PI * 2f;
                    factor = (float) Math.sin(n * 20f + (float) Math.sin(a) * SIZE * 0.3f / ((float) Math.PI * 2f)) * 0.12f;
                }
                case ICY -> {
                    float n = seamlessTurbulence(x, y, 0.5f, 0.5f, ms, 2);
                    factor = n > 0.6f ? (n - 0.6f) * 0.4f : -0.05f;
                }
                case SILK -> {
                    float n = seamlessValueNoise(x, y, 0.1f, 0.1f, ms);
                    float a = (x / (float) SIZE) * (float) Math.PI * 2f;
                    factor = ((float) Math.sin((float) Math.sin(a) * SIZE * 0.25f / ((float) Math.PI * 2f) + n * 8f)
                        + (float) Math.sin(y * 0.25f) * 0.5f) * 0.07f;
                }
                case STONE -> factor = (seamlessValueNoise(x, y, 1.2f, 1.2f, ms) - 0.5f) * 0.22f;
                case SAND -> {
                    float n = seamlessValueNoise(x, y, 1.5f, 1.5f, ms);
                    factor = n > 0.7f ? 0.12f : (n < 0.3f ? -0.08f : 0f);
                }
                case CARBON -> {
                    boolean dark = (((x >> 1) + (y >> 1)) % 2 == 0);
                    float n = seamlessValueNoise(x, y, 0.9f, 0.9f, ms);
                    factor = (dark ? -0.08f : 0.04f) + n * 0.06f;
                }
                default -> factor = 0f;
            }
            if (factor != 0f) {
                int r = clamp(((cur >> 16) & 0xFF) + (int) (factor * 255), 0, 255);
                int g = clamp(((cur >> 8) & 0xFF) + (int) (factor * 255), 0, 255);
                int b = clamp((cur & 0xFF) + (int) (factor * 255), 0, 255);
                img.setColor(x, y, toAbgr(0xFF000000 | (r << 16) | (g << 8) | b));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  EMISSIVE LAYER (per-pixel computation matching JS)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void generateEmissive(NativeImage img, CollectibleDNA dna, long frameSeed) {
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) img.setColor(x, y, 0x00000000);
        if (dna.emissive() == EmissiveType.NONE || dna.emissiveDensity() == 0) return;

        int glowColor = dna.getActiveColors()[5];
        int emColor   = brighten(glowColor, 0.4f);
        int es = (int) (dna.seed() + 787878L + frameSeed);
        float density = dna.emissiveDensity() / 100f;

        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            float intensity = 0;
            switch (dna.emissive()) {

                case SPOTS -> {
                    intensity = emCellDot(x, y, 5, 37, density * 0.6f, 2.2f, density, es);
                }

                case LINES -> {
                    float angle = (x / (float) SIZE) * (float) Math.PI * 2f;
                    float wave = (float) Math.sin(angle * 4f + seamlessValueNoise(x, y, 0.15f, 0.15f, es) * 3f);
                    float threshold = 0.1f + density * 0.15f;
                    intensity = Math.abs(wave) < threshold ? (1f - Math.abs(wave) / threshold) * density : 0;
                }

                case RUNES -> {
                    int cellSz = 10;
                    int cxC = (int) Math.floor((float) x / cellSz);
                    int cyC = (int) Math.floor((float) y / cellSz);
                    float lx = ((x % cellSz) + cellSz) % cellSz / (float) cellSz;
                    float ly = (y % cellSz) / (float) cellSz;
                    SeededRandom rng = new SeededRandom(es + ((cxC % 8 + 8) % 8) * 47 + cyC * 83);
                    if (rng.nextFloat() < density * 0.5f) {
                        int pat = rng.nextInt(7);
                        float lw = 0.13f;
                        boolean on = false;
                        switch (pat) {
                            case 0 -> on = Math.abs(lx - 0.5f) < lw;
                            case 1 -> on = Math.abs(ly - 0.5f) < lw;
                            case 2 -> on = Math.abs(lx - 0.5f) < lw || Math.abs(ly - 0.5f) < lw;
                            case 3 -> on = Math.abs(lx - ly) < lw * 1.41f;
                            case 4 -> on = Math.abs(lx + ly - 1f) < lw * 1.41f;
                            case 5 -> on = Math.abs(lx - ly) < lw * 1.41f || Math.abs(lx + ly - 1f) < lw * 1.41f;
                            case 6 -> {
                                float cdist = (float) Math.sqrt((lx - 0.5f) * (lx - 0.5f) + (ly - 0.5f) * (ly - 0.5f));
                                on = Math.abs(cdist - 0.35f) < lw;
                            }
                        }
                        float margin = Math.min(Math.min(lx, 1f - lx), Math.min(ly, 1f - ly));
                        intensity = on ? density * Math.min(1f, margin / 0.08f) : 0;
                    }
                }

                case STARS -> {
                    int cellSz = 8;
                    float maxI = 0;
                    for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) {
                        int cx = (int) Math.floor((float) x / cellSz) + ox;
                        int cy = (int) Math.floor((float) y / cellSz) + oy;
                        SeededRandom rng = new SeededRandom(es + ((cx % 12 + 12) % 12) * 53 + cy * 97);
                        if (rng.nextFloat() > density * 0.4f) continue;
                        float sx = (cx + 0.2f + rng.nextFloat() * 0.6f) * cellSz;
                        float sy = (cy + 0.2f + rng.nextFloat() * 0.6f) * cellSz;
                        float dx = x - sx;
                        if (dx > SIZE / 2f) dx -= SIZE; else if (dx < -SIZE / 2f) dx += SIZE;
                        float dy = y - sy;
                        float dist = (float) Math.sqrt(dx * dx + dy * dy);
                        if (dist < 0.7f) maxI = Math.max(maxI, density);
                        else if (dist < 2.8f) maxI = Math.max(maxI, density * (float) Math.pow(1f - (dist - 0.7f) / 2.1f, 2));
                    }
                    intensity = maxI;
                }

                case AURORA -> {
                    float n = seamlessTurbulence(x, y, 0.1f, 0.1f, es, 3);
                    float wave = (float) Math.sin(y / (float) SIZE * (float) Math.PI * 3f + n * 4f);
                    intensity = Math.max(0f, wave * 0.5f + 0.5f) * density * n;
                }

                case VEINS -> {
                    float n = seamlessTurbulence(x, y, 0.15f, 0.15f, es, 4);
                    float n2 = seamlessValueNoise(x, y, 0.3f, 0.3f, es + 55);
                    float thickness = 0.06f + n2 * 0.03f;
                    float diff = Math.abs(n - 0.5f);
                    intensity = diff < thickness ? density * (float) Math.pow(1f - diff / thickness, 0.6f) : 0;
                }

                case SPARKS -> {
                    int cellSz = 10;
                    float maxI = 0;
                    for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) {
                        int cx = (int) Math.floor((float) x / cellSz) + ox;
                        int cy = (int) Math.floor((float) y / cellSz) + oy;
                        SeededRandom rng = new SeededRandom(es + ((cx % 10 + 10) % 10) * 71 + cy * 199);
                        if (rng.nextFloat() > density * 0.25f) continue;
                        float spX = (cx + 0.1f + rng.nextFloat() * 0.8f) * cellSz;
                        float spY = (cy + 0.1f + rng.nextFloat() * 0.8f) * cellSz;
                        float dx = x - spX;
                        if (dx > SIZE / 2f) dx -= SIZE; else if (dx < -SIZE / 2f) dx += SIZE;
                        float dy = y - spY;
                        float dist = (float) Math.sqrt(dx * dx + dy * dy);
                        if (dist < 0.5f) maxI = Math.max(maxI, density);
                        else if (dist < 2.2f) maxI = Math.max(maxI, density * 0.6f * (1f - (dist - 0.5f) / 1.7f));
                    }
                    intensity = maxI;
                }

                case CRACKS -> {
                    float n1 = seamlessTurbulence(x, y, 0.15f, 0.15f, es, 2);
                    float n2 = seamlessValueNoise(x, y, 0.4f, 0.4f, es + 99);
                    boolean crack = Math.abs(n1 - n2) < 0.04f;
                    intensity = crack ? density * (1f - Math.abs(n1 - n2) / 0.04f) : 0;
                }

                case PULSE -> {
                    float n = seamlessValueNoise(x, y, 0.08f, 0.08f, es);
                    float ring = (float) Math.sin(n * (float) Math.PI * 8f);
                    intensity = Math.abs(ring) > 0.85f ? density * (Math.abs(ring) - 0.85f) / 0.15f : 0;
                }

                case CIRCUIT -> {
                    int cellSize = 8;
                    int cxM = ((x % cellSize) + cellSize) % cellSize;
                    int cyM = y % cellSize;
                    boolean onGrid = cxM == 0 || cyM == 0;
                    float n = seamlessValueNoise(x, y, 0.3f, 0.3f, es);
                    intensity = (onGrid && n > 0.4f) ? density * 0.8f : (n > 0.85f ? density : 0);
                }

                case RIPPLE -> {
                    float maxI = 0;
                    for (int ci = 0; ci < 4; ci++) {
                        SeededRandom rng = new SeededRandom(es + ci * 317);
                        float rcx = rng.nextFloat() * SIZE;
                        float rcy = 0.15f * SIZE + rng.nextFloat() * 0.7f * SIZE;
                        float dx = x - rcx;
                        if (dx > SIZE / 2f) dx -= SIZE; else if (dx < -SIZE / 2f) dx += SIZE;
                        float dy = y - rcy;
                        float dist = (float) Math.sqrt(dx * dx + dy * dy);
                        float ring = (float) Math.sin(dist * 0.7f);
                        if (Math.abs(ring) > 0.8f)
                            maxI = Math.max(maxI, density * (Math.abs(ring) - 0.8f) / 0.2f
                                * Math.max(0f, 1f - dist / (SIZE * 0.6f)));
                    }
                    intensity = maxI;
                }

                case FLAME -> {
                    float n1 = seamlessTurbulence(x, y, 0.12f, 0.08f, es, 3);
                    float n2 = seamlessValueNoise(x, y, 0.2f, 0.06f, es + 33);
                    float rise = 1f - y / (float) SIZE;
                    boolean streak = Math.abs(n1 - 0.5f) < 0.05f + n2 * 0.04f;
                    intensity = streak ? density * rise * (1f - Math.abs(n1 - 0.5f) / (0.05f + n2 * 0.04f)) : 0;
                }

                case NEBULA -> {
                    float n1 = seamlessTurbulence(x, y, 0.08f, 0.08f, es, 4);
                    float n2 = seamlessValueNoise(x, y, 0.12f, 0.12f, es + 77);
                    float combined = n1 * 0.6f + n2 * 0.4f;
                    intensity = combined > 0.45f ? density * (float) Math.pow((combined - 0.45f) / 0.55f, 0.8f) : 0;
                }

                case MATRIX -> {
                    int colW = 4;
                    int col = (int) Math.floor((float) x / colW);
                    SeededRandom rng = new SeededRandom(es + ((col % 20 + 20) % 20) * 59);
                    if (rng.nextFloat() < density * 0.5f) {
                        float speed = 0.3f + rng.nextFloat() * 0.4f;
                        float offset = rng.nextFloat() * SIZE;
                        float t = ((y + offset) * speed) % SIZE;
                        boolean bright = t < SIZE * 0.25f;
                        int cxM = ((x % colW) + colW) % colW;
                        boolean centered = cxM >= 1 && cxM < colW - 1;
                        intensity = (bright && centered) ? density * (1f - t / (SIZE * 0.25f)) : 0;
                    }
                }

                case DIAMONDS -> {
                    int sz = 7;
                    float lx = ((x % sz) + sz) % sz / (float) sz - 0.5f;
                    float ly = (y % sz) / (float) sz - 0.5f;
                    float diamond = Math.abs(lx) + Math.abs(ly);
                    boolean onEdge = Math.abs(diamond - 0.4f) < 0.06f;
                    float n = seamlessValueNoise(x, y, 0.3f, 0.3f, es);
                    intensity = (onEdge && n > (0.5f - density * 0.3f))
                        ? density * (1f - Math.abs(diamond - 0.4f) / 0.06f) : 0;
                }

                default -> intensity = 0;
            }

            intensity = Math.min(1f, Math.max(0f, intensity));
            if (intensity > 0) {
                setEmissivePixel(img, x, y, emColor, intensity);
            }
        }
    }

    /** Cell-based dot check with horizontal wrapping (matches JS _emCellDot). */
    private static float emCellDot(int x, int y, int cellSz, int seedOff, float threshold,
                                    float maxRad, float density, int es) {
        float maxI = 0;
        for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) {
            int cx = (int) Math.floor((float) x / cellSz) + ox;
            int cy = (int) Math.floor((float) y / cellSz) + oy;
            int wcx = ((cx % 20) + 20) % 20;
            SeededRandom rng = new SeededRandom(es + wcx * seedOff + cy * 131);
            if (rng.nextFloat() > threshold) continue;
            float dotX = (cx + 0.25f + rng.nextFloat() * 0.5f) * cellSz;
            float dotY = (cy + 0.25f + rng.nextFloat() * 0.5f) * cellSz;
            float rad = maxRad * (0.5f + rng.nextFloat() * 0.5f);
            float dx = x - dotX;
            if (dx > SIZE / 2f) dx -= SIZE; else if (dx < -SIZE / 2f) dx += SIZE;
            float dy = y - dotY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < rad) maxI = Math.max(maxI, (1f - dist / rad) * density);
        }
        return maxI;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  DRAWING PRIMITIVES
    // ═══════════════════════════════════════════════════════════════════════════

    private static void drawEllipse(NativeImage img, int cx, int cy, int rx, int ry, int color, float alpha) {
        for (int y = cy - ry; y <= cy + ry; y++) for (int x = cx - rx; x <= cx + rx; x++) {
            if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) continue;
            float dx = (float) (x - cx) / rx, dy = (float) (y - cy) / ry;
            float d = dx * dx + dy * dy;
            if (d <= 1f) img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), color, alpha * (1f - d * 0.4f))));
        }
    }

    private static void drawLine(NativeImage img, int x1, int y1, int x2, int y2, int color, float alpha) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, err = dx - dy, x = x1, y = y1;
        while (true) {
            if (x >= 0 && y >= 0 && x < SIZE && y < SIZE)
                img.setColor(x, y, toAbgr(blendColor(fromAbgr(img.getColor(x, y)), color, alpha)));
            if (x == x2 && y == y2) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 < dx) { err += dx; y += sy; }
        }
    }

    // ── Wrapped drawing (horizontal wrapping for cylindrical UV) ─────────────

    private static void drawEllipseWrapped(NativeImage img, int cx, int cy, int rx, int ry, int color, float alpha) {
        drawEllipse(img, cx, cy, rx, ry, color, alpha);
        if (cx - rx < 0) drawEllipse(img, cx + SIZE, cy, rx, ry, color, alpha);
        if (cx + rx >= SIZE) drawEllipse(img, cx - SIZE, cy, rx, ry, color, alpha);
    }

    private static void setPixelWrapped(NativeImage img, int x, int y, int argb) {
        if (y < 0 || y >= SIZE) return;
        int wx = ((x % SIZE) + SIZE) % SIZE;
        img.setColor(wx, y, toAbgr(argb));
    }

    private static void drawLineWrapped(NativeImage img, int x1, int y1, int x2, int y2, int color, float alpha) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, err = dx - dy, x = x1, y = y1;
        while (true) {
            int wx = ((x % SIZE) + SIZE) % SIZE;
            if (y >= 0 && y < SIZE)
                img.setColor(wx, y, toAbgr(blendColor(fromAbgr(img.getColor(wx, y)), color, alpha)));
            if (x == x2 && y == y2) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 < dx) { err += dx; y += sy; }
        }
    }

    // ── Emissive drawing ─────────────────────────────────────────────────────

    private static void setEmissivePixel(NativeImage img, int x, int y, int color, float alpha) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;
        int ea = (fromAbgr(img.getColor(x, y)) >> 24) & 0xFF;
        int na = (int) (alpha * 255);
        if (na <= ea) return;
        img.setColor(x, y, toAbgr((na << 24) | ((color >> 16) & 0xFF) << 16 | ((color >> 8) & 0xFF) << 8 | (color & 0xFF)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  NOISE FUNCTIONS (32-bit hash matching JS)
    // ═══════════════════════════════════════════════════════════════════════════

    /** 32-bit hash matching JS _hash() — uses splitmix-style mixing. */
    private static float hash(int x, int y, long seed) {
        int h = (int) seed ^ (x * 1234567891) ^ (y * 987654321);
        h = (h ^ (h >>> 16)) * 0x85ebca6b;
        h = (h ^ (h >>> 13)) * 0xc2b2ae35;
        return Integer.toUnsignedLong(h ^ (h >>> 16)) / 4294967296f;
    }

    private static float valueNoise(float x, float y, long seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float fx = x - xi, fy = y - yi;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
        float h00 = hash(xi, yi, seed), h10 = hash(xi + 1, yi, seed);
        float h01 = hash(xi, yi + 1, seed), h11 = hash(xi + 1, yi + 1, seed);
        return h00 + (h10 - h00) * fx + (h01 - h00) * fy + (h00 - h10 - h01 + h11) * fx * fy;
    }

    private static float turbulence(float x, float y, long seed, int oct) {
        float s = 0, a = 1f, f = 1f, m = 0;
        for (int i = 0; i < oct; i++) {
            s += Math.abs(valueNoise(x * f, y * f, seed + i * 7919L) * 2f - 1f) * a;
            m += a; a *= 0.5f; f *= 2f;
        }
        return s / m;
    }

    private static float veinNoise(float x, float y, long seed) {
        return (1f - Math.abs(valueNoise(x, y, seed) * 2f - 1f)) * 0.6f
             + (1f - Math.abs(valueNoise(x * 2, y * 2, seed + 13) * 2f - 1f)) * 0.25f
             + (1f - Math.abs(valueNoise(x * 4, y * 4, seed + 31) * 2f - 1f)) * 0.15f;
    }

    // ── Seamless noise (torus-mapped for cylindrical UV wrapping) ────────────

    /** Seamless 2D value noise via torus mapping — x wraps at SIZE, y is linear. */
    private static float seamlessValueNoise(int x, int y, float xScale, float yScale, long seed) {
        float angle = (x / (float) SIZE) * (float) Math.PI * 2f;
        float r = SIZE * xScale / ((float) Math.PI * 2f);
        float cx = (float) Math.cos(angle) * r, sx = (float) Math.sin(angle) * r;
        float ny = y * yScale;
        return (valueNoise(cx + 50f, ny + 10f, seed) + valueNoise(sx + 50f, ny + 30f, seed + 7919L)) * 0.5f;
    }

    private static float seamlessTurbulence(int x, int y, float xScale, float yScale, long seed, int oct) {
        float s = 0, a = 1f, f = 1f, m = 0;
        for (int i = 0; i < oct; i++) {
            s += Math.abs(seamlessValueNoise(x, y, xScale * f, yScale * f, seed + i * 7919L) * 2f - 1f) * a;
            m += a; a *= 0.5f; f *= 2f;
        }
        return s / m;
    }

    private static float seamlessVeinNoise(int x, int y, float xScale, float yScale, long seed) {
        return (1f - Math.abs(seamlessValueNoise(x, y, xScale, yScale, seed) * 2f - 1f)) * 0.6f
             + (1f - Math.abs(seamlessValueNoise(x, y, xScale * 2f, yScale * 2f, seed + 13L) * 2f - 1f)) * 0.25f
             + (1f - Math.abs(seamlessValueNoise(x, y, xScale * 4f, yScale * 4f, seed + 31L) * 2f - 1f)) * 0.15f;
    }

    private static float hexGrid(float x, float y) {
        float s3 = 1.732f, hx = (x - y / s3) / 2f, hz = y * s3 / 2f;
        float rx = Math.round(hx), rz = Math.round(hz);
        return 1f - (float) Math.hypot(hx - rx, hz - rz) * 2f;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  COLOR MATH (all ARGB int format)
    // ═══════════════════════════════════════════════════════════════════════════

    private static int lerpColor(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
        int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int blendColor(int dst, int src, float alpha) { return lerpColor(dst, src, alpha); }

    private static int brighten(int c, float a) {
        return (0xFF << 24)
             | (Math.min(255, (int) (((c >> 16) & 0xFF) + 255 * a)) << 16)
             | (Math.min(255, (int) (((c >> 8) & 0xFF) + 255 * a)) << 8)
             |  Math.min(255, (int) ((c & 0xFF) + 255 * a));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    public static int toAbgr(int argb) {
        return ((argb >> 24) & 0xFF) << 24 | (argb & 0xFF) << 16 | ((argb >> 8) & 0xFF) << 8 | ((argb >> 16) & 0xFF);
    }

    public static int fromAbgr(int abgr) {
        return ((abgr >> 24) & 0xFF) << 24 | (abgr & 0xFF) << 16 | ((abgr >> 8) & 0xFF) << 8 | ((abgr >> 16) & 0xFF);
    }
}
