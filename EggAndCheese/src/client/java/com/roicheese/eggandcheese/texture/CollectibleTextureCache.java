package com.roicheese.eggandcheese.texture;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.roicheese.eggandcheese.dna.CollectibleDNA;
import com.roicheese.eggandcheese.dna.EmissiveType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.HashMap;
import java.util.Map;

/**
 * Caches procedurally generated textures keyed by DNA.
 *
 * Stores 3 textures per DNA:
 *   color    — the base color map
 *   emissF0  — emissive frame 0 (always shown)
 *   emissF1  — emissive frame 1 (alternated every second for animated rarities)
 *
 * Frame selection is done by callers via getEmissiveTexture(dna, frameIndex).
 */
public class CollectibleTextureCache {

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("EggAndCheese/TextureCache");

    private record Entry(Identifier color, Identifier emissF0, Identifier emissF1) {}

    private static final Map<String, Entry> CACHE = new HashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    public static Identifier getColorTexture(CollectibleDNA dna) {
        return getOrCreate(dna).color();
    }

    /**
     * @param frame 0 or 1 — animated rarities (LEGENDARY/MYTHIC) alternate frames every second.
     */
    public static Identifier getEmissiveTexture(CollectibleDNA dna, int frame) {
        if (dna.emissive() == EmissiveType.NONE || dna.emissiveDensity() == 0) return null;
        Entry e = getOrCreate(dna);
        return frame == 1 ? e.emissF1() : e.emissF0();
    }

    /**
     * Returns the emissive texture for the given DNA.
     * Single stable frame — no flickering frame-alternation.
     * The emissive texture itself has smooth patterns (aurora scrolls, vein flows)
     * that look animated due to GPU bloom; no hard per-second switching.
     */
    public static Identifier getEmissiveTexture(CollectibleDNA dna) {
        return getEmissiveTexture(dna, 0);
    }

    public static void invalidate(CollectibleDNA dna) {
        Entry entry = CACHE.remove(dna.cacheKey());
        if (entry == null) return;
        var tm = MinecraftClient.getInstance().getTextureManager();
        tm.destroyTexture(entry.color());
        tm.destroyTexture(entry.emissF0());
        tm.destroyTexture(entry.emissF1());
    }

    public static void invalidateAll() {
        var tm = MinecraftClient.getInstance().getTextureManager();
        for (Entry e : CACHE.values()) {
            tm.destroyTexture(e.color());
            tm.destroyTexture(e.emissF0());
            tm.destroyTexture(e.emissF1());
        }
        CACHE.clear();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static Entry getOrCreate(CollectibleDNA dna) {
        String key = dna.cacheKey();
        Entry cached = CACHE.get(key);
        if (cached != null) return cached;

        Entry entry;
        try {
            NativeImage[] imgs = ProceduralTextureGenerator.generate(dna);
            // Use the full key hash to ensure uniqueness — no truncation that could collide
            String safe = sanitize(key);
            Identifier colorId  = Identifier.of("eggandcheese", "dynamic/color_"   + safe);
            Identifier emissId0 = Identifier.of("eggandcheese", "dynamic/emiss0_"  + safe);
            Identifier emissId1 = Identifier.of("eggandcheese", "dynamic/emiss1_"  + safe);
            var tm = MinecraftClient.getInstance().getTextureManager();
            NativeImageBackedTexture colorTex  = new NativeImageBackedTexture(imgs[0]);
            NativeImageBackedTexture emissTex0 = new NativeImageBackedTexture(imgs[1]);
            NativeImageBackedTexture emissTex1 = new NativeImageBackedTexture(imgs[2]);
            tm.registerTexture(colorId,  colorTex);
            tm.registerTexture(emissId0, emissTex0);
            tm.registerTexture(emissId1, emissTex1);
            // Match Three.js: wrapS=RepeatWrapping (seamless cylinder), wrapT=ClampToEdge
            setTextureWrap(colorTex);
            setTextureWrap(emissTex0);
            setTextureWrap(emissTex1);
            entry = new Entry(colorId, emissId0, emissId1);
        } catch (Exception e) {
            // Fallback to a safe magenta "error" texture so the game doesn't crash
            LOGGER.error("[EggAndCheese] Texture generation failed for key {}: {}", key, e.getMessage());
            entry = getOrCreateFallback(key);
        }
        CACHE.put(key, entry);
        return entry;
    }

    private static Entry getOrCreateFallback(String key) {
        String safe = "fallback_" + Math.abs(key.hashCode());
        Identifier fallbackId = Identifier.of("eggandcheese", "dynamic/fallback_" + safe);
        try {
            NativeImage img = new NativeImage(ProceduralTextureGenerator.SIZE, ProceduralTextureGenerator.SIZE, false);
            // Fill with a visible magenta-black checkerboard so errors are obvious
            for (int y = 0; y < ProceduralTextureGenerator.SIZE; y++)
                for (int x = 0; x < ProceduralTextureGenerator.SIZE; x++) {
                    boolean checker = ((x / 4 + y / 4) % 2 == 0);
                    img.setColor(x, y, checker ? 0xFF0000FF : 0xFFFF00FF); // ABGR
                }
            var tm = MinecraftClient.getInstance().getTextureManager();
            tm.registerTexture(fallbackId, new NativeImageBackedTexture(img));
        } catch (Exception ignored) {}
        return new Entry(fallbackId, fallbackId, fallbackId);
    }

    /** Set GL_REPEAT on S (horizontal wrap) and GL_CLAMP_TO_EDGE on T — matches Three.js previewer. */
    private static void setTextureWrap(NativeImageBackedTexture tex) {
        RenderSystem.recordRenderCall(() -> {
            GlStateManager._bindTexture(tex.getGlId());
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        });
    }

    private static String sanitize(String key) {
        // Use a deterministic short hash to avoid ID length issues and collisions
        long h = key.hashCode() * 0x9E3779B97F4A7C15L;
        return "t" + Long.toUnsignedString(h, 36); // base-36, ~13 chars, always unique
    }
}
