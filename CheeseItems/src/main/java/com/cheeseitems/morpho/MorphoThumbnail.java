package com.cheeseitems.morpho;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds and caches downscaled thumbnails of morpho catalog images so that gallery
 * cards on the public store don't have to download the full-resolution PNG (often
 * 1-2 MB each). The lightbox/purchase modal continues to load the original via
 * {@code /image} for full quality.
 *
 * Thumbnails sit next to the original — {@code <id>.thumb.png} — and are
 * regenerated whenever the source PNG's modification time is newer. The catalog
 * also wipes the thumbnail when {@code saveImage}/{@code deleteImage} is called,
 * so admin uploads invalidate it immediately.
 *
 * Quality strategy:
 * - Lossless PNG output (no JPEG ringing, transparency preserved).
 * - Multi-step bilinear halving when the source is more than 2× the target size,
 *   then a final bicubic + render-quality pass. This is the Filthy Rich Clients
 *   trick that avoids the aliasing you get from a single Graphics2D drawImage
 *   when downscaling beyond ~2×.
 */
public final class MorphoThumbnail {
    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");

    /** 480 covers gallery cards up to ~240 CSS px even at 2× device pixel ratio. */
    private static final int MAX_DIM = 480;

    private MorphoThumbnail() {}

    /**
     * Returns the path to a cached thumbnail of {@code original}, generating it on
     * demand. If the source image is already smaller than {@link #MAX_DIM} or any
     * step of the pipeline fails, the original path is returned unchanged so the
     * caller still serves a valid image.
     */
    public static Path getOrCreate(Path original) {
        try {
            if (!Files.exists(original)) return original;

            String name = original.getFileName().toString();
            if (!name.endsWith(".png")) return original;
            Path thumb = original.resolveSibling(name.substring(0, name.length() - 4) + ".thumb.png");

            // Cache hit: thumb exists and is at least as new as the source.
            if (Files.exists(thumb)
                    && Files.getLastModifiedTime(thumb).toMillis() >= Files.getLastModifiedTime(original).toMillis()) {
                return thumb;
            }

            BufferedImage src = ImageIO.read(original.toFile());
            if (src == null) return original;
            int sw = src.getWidth(), sh = src.getHeight();

            // Already small enough — no point caching a copy.
            if (sw <= MAX_DIM && sh <= MAX_DIM) return original;

            double scale = Math.min((double) MAX_DIM / sw, (double) MAX_DIM / sh);
            int tw = Math.max(1, (int) Math.round(sw * scale));
            int th = Math.max(1, (int) Math.round(sh * scale));

            // Step-down (halving) pass for quality on large downscales.
            BufferedImage current = src;
            int cw = sw, ch = sh;
            while (cw > tw * 2 && ch > th * 2) {
                cw = Math.max(tw, cw / 2);
                ch = Math.max(th, ch / 2);
                BufferedImage step = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = step.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(current, 0, 0, cw, ch, null);
                g.dispose();
                current = step;
            }

            // Final pass — bicubic for the cleanest edges.
            BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(current, 0, 0, tw, th, null);
            g.dispose();

            ImageIO.write(out, "png", thumb.toFile());
            return thumb;
        } catch (Exception e) {
            LOGGER.warn("[Morpho] Thumbnail generation failed for {}: {}", original, e.getMessage());
            return original;
        }
    }

    /** Removes the cached thumbnail next to {@code original}, if any. */
    public static void invalidate(Path original) {
        try {
            String name = original.getFileName().toString();
            if (!name.endsWith(".png")) return;
            Path thumb = original.resolveSibling(name.substring(0, name.length() - 4) + ".thumb.png");
            Files.deleteIfExists(thumb);
        } catch (Exception ignored) { /* best-effort */ }
    }
}
