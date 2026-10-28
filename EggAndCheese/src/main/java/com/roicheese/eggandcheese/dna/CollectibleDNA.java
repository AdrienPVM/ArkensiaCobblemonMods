package com.roicheese.eggandcheese.dna;

import net.minecraft.nbt.NbtCompound;
import java.util.Random;

/**
 * Immutable DNA — defines the complete procedural appearance of a collectible.
 *
 * Visual flags bitmask (visualFlags field):
 *   bit 0  (0x01) — directional lighting (fake sphere shading)
 *   bit 1  (0x02) — specular highlight (top-left gloss blob)
 *   bit 2  (0x04) — emboss surface relief (Sobel-based chiseled look on pattern edges)
 *   bit 3  (0x08) — varnish layer (glossy diagonal sheen)
 *   bit 4  (0x10) — rim glow (emissive colored halo at the silhouette edge)
 *
 * customPalette:
 *   "" (empty) = use palette.getColors()
 *   "RRGGBB,RRGGBB,...×6" = 6 hex colors overriding the base palette
 */
public record CollectibleDNA(
    long             seed,
    PaletteType      palette,
    PatternType      pattern,
    int              patternScale,      // 1–4
    PatternType      secondaryPattern,
    int              patternBlend,      // 0–100
    EmissiveType     emissive,
    int              emissiveDensity,   // 0–100
    RarityType       rarity,
    StampType        stamp,
    MicroTextureType microTexture,
    int              visualFlags,       // bitmask, see above
    int              varnishLevel,      // 0–100 (only relevant when FLAG_VARNISH set)
    String           customPalette      // "" or "C0,C1,C2,C3,C4,C5" (6 hex RRGGBB)
) {
    // ── Visual flag constants ─────────────────────────────────────────────────
    public static final int FLAG_LIGHTING  = 0x01;
    public static final int FLAG_SPECULAR  = 0x02;
    public static final int FLAG_EMBOSS    = 0x04; // was FLAG_OUTLINE — now emboss relief
    public static final int FLAG_VARNISH   = 0x08;
    public static final int FLAG_RIM_GLOW  = 0x10; // was FLAG_EDGE_GLOW — renamed to RIM_GLOW

    // Legacy compat aliases
    public static final int FLAG_OUTLINE   = FLAG_EMBOSS;
    public static final int FLAG_EDGE_GLOW = FLAG_RIM_GLOW;

    public boolean hasFlag(int flag) { return (visualFlags & flag) != 0; }

    // ── Active colors (custom or palette) ────────────────────────────────────
    public int[] getActiveColors() {
        if (customPalette != null && !customPalette.isEmpty()) {
            String[] parts = customPalette.split(",");
            if (parts.length == 6) {
                try {
                    int[] colors = new int[6];
                    for (int i = 0; i < 6; i++)
                        colors[i] = (int)(Long.parseLong(parts[i].trim(), 16)) | 0xFF000000;
                    return colors;
                } catch (NumberFormatException ignored) {}
            }
        }
        return palette.getColors();
    }

    public boolean hasCustomPalette() {
        return customPalette != null && !customPalette.isEmpty();
    }

    /** Build a customPalette string from 6 colors (ARGB or RGB, alpha stripped). */
    public static String buildCustomPalette(int[] colors) {
        if (colors == null || colors.length != 6) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format("%06X", colors[i] & 0xFFFFFF));
        }
        return sb.toString();
    }

    // ── Serialisation ────────────────────────────────────────────────────────

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putLong  ("seed",             seed);
        nbt.putString("palette",          palette.name());
        nbt.putString("pattern",          pattern.name());
        nbt.putInt   ("patternScale",     patternScale);
        nbt.putString("secondaryPattern", secondaryPattern.name());
        nbt.putInt   ("patternBlend",     patternBlend);
        nbt.putString("emissive",         emissive.name());
        nbt.putInt   ("emissiveDensity",  emissiveDensity);
        nbt.putString("rarity",           rarity.name());
        nbt.putString("stamp",            stamp.name());
        nbt.putString("microTexture",     microTexture.name());
        nbt.putInt   ("visualFlags",      visualFlags);
        nbt.putInt   ("varnishLevel",     varnishLevel);
        if (customPalette != null && !customPalette.isEmpty())
            nbt.putString("customPalette", customPalette);
        return nbt;
    }

    public static CollectibleDNA fromNbt(NbtCompound nbt) {
        if (nbt == null || !nbt.contains("seed")) return generate();
        long             seed      = nbt.getLong  ("seed");
        PaletteType      palette   = safeEnum(PaletteType.values(),      nbt.getString("palette"),          PaletteType.WARM);
        PatternType      pattern   = safeEnum(PatternType.values(),      nbt.getString("pattern"),          PatternType.SPOTS);
        int              scale     = clamp(nbt.getInt("patternScale"), 1, 4);
        PatternType      secPat    = safeEnum(PatternType.values(),      nbt.getString("secondaryPattern"), PatternType.MARBLE);
        int              blend     = clamp(nbt.getInt("patternBlend"),  0, 100);
        EmissiveType     emissive  = safeEnum(EmissiveType.values(),     nbt.getString("emissive"),         EmissiveType.NONE);
        int              density   = clamp(nbt.getInt("emissiveDensity"), 0, 100);
        RarityType       rarity    = safeEnum(RarityType.values(),       nbt.getString("rarity"),           RarityType.COMMON);
        StampType        stamp     = safeEnum(StampType.values(),        nbt.getString("stamp"),            StampType.NONE);
        MicroTextureType micro     = safeEnum(MicroTextureType.values(), nbt.getString("microTexture"),     MicroTextureType.NONE);
        int              flags     = nbt.getInt("visualFlags");
        int              varnish   = clamp(nbt.getInt("varnishLevel"), 0, 100);
        String           custom    = nbt.contains("customPalette") ? nbt.getString("customPalette") : "";
        return new CollectibleDNA(seed, palette, pattern, scale, secPat, blend,
                                  emissive, density, rarity, stamp, micro, flags, varnish, custom);
    }

    // ── DNA Code ─────────────────────────────────────────────────────────────
    // Format: EAC-{seed_hex16}-{PAL}.{PAT}.{SC}.{SECPAT}.{BLD}.{EMIS}.{DEN}.{RAR}.{STAMP}.{MICRO}.{FLAGS_HEX}.{VAR}[;{customPalette}]

    public String toDnaCode() {
        String base = "EAC-" + String.format("%016X", seed) + "-"
            + palette.name()          + "."
            + pattern.name()          + "."
            + patternScale            + "."
            + secondaryPattern.name() + "."
            + patternBlend            + "."
            + emissive.name()         + "."
            + emissiveDensity         + "."
            + rarity.name()           + "."
            + stamp.name()            + "."
            + microTexture.name()     + "."
            + String.format("%02X", visualFlags) + "."
            + varnishLevel;
        if (customPalette != null && !customPalette.isEmpty())
            base += ";" + customPalette;
        return base;
    }

    public static CollectibleDNA fromDnaCode(String code) {
        try {
            if (!code.startsWith("EAC-")) return null;
            // Extract optional custom palette after ';'
            String custom = "";
            int semiIdx = code.indexOf(';');
            if (semiIdx > 0) { custom = code.substring(semiIdx + 1); code = code.substring(0, semiIdx); }
            int dashIdx = code.indexOf('-', 4);
            if (dashIdx < 0) return null;
            long seed = Long.parseUnsignedLong(code.substring(4, dashIdx), 16);
            String[] p = code.substring(dashIdx + 1).split("\\.");
            if (p.length < 12) return null;
            PaletteType      pal    = safeEnum(PaletteType.values(),      p[0],  PaletteType.WARM);
            PatternType      pat    = safeEnum(PatternType.values(),      p[1],  PatternType.SPOTS);
            int              sc     = clamp(Integer.parseInt(p[2]), 1, 4);
            PatternType      sec    = safeEnum(PatternType.values(),      p[3],  PatternType.MARBLE);
            int              bld    = clamp(Integer.parseInt(p[4]), 0, 100);
            EmissiveType     emis   = safeEnum(EmissiveType.values(),     p[5],  EmissiveType.NONE);
            int              den    = clamp(Integer.parseInt(p[6]), 0, 100);
            RarityType       rar    = safeEnum(RarityType.values(),       p[7],  RarityType.COMMON);
            StampType        stamp  = safeEnum(StampType.values(),        p[8],  StampType.NONE);
            MicroTextureType micro  = safeEnum(MicroTextureType.values(), p[9],  MicroTextureType.NONE);
            int              flags  = Integer.parseInt(p[10], 16);
            int              varnish = clamp(Integer.parseInt(p[11]), 0, 100);
            return new CollectibleDNA(seed, pal, pat, sc, sec, bld, emis, den, rar, stamp, micro, flags, varnish, custom);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Random generation ────────────────────────────────────────────────────

    public static CollectibleDNA randomDNA(long seed) {
        Random rng = new Random(seed);
        PaletteType      palette  = PaletteType.values() [rng.nextInt(PaletteType.values().length)];
        PatternType      pattern  = PatternType.values() [rng.nextInt(PatternType.values().length)];
        PatternType      secPat   = PatternType.values() [rng.nextInt(PatternType.values().length)];
        EmissiveType     emissive = EmissiveType.values()[rng.nextInt(EmissiveType.values().length)];
        StampType        stamp    = rng.nextInt(3) == 0
                                    ? StampType.values()[1 + rng.nextInt(StampType.values().length - 1)]
                                    : StampType.NONE;
        MicroTextureType micro    = rng.nextInt(3) == 0
                                    ? MicroTextureType.values()[1 + rng.nextInt(MicroTextureType.values().length - 1)]
                                    : MicroTextureType.NONE;
        int scale   = rng.nextInt(4) + 1;
        int blend   = 15 + rng.nextInt(55);
        int density = 20 + rng.nextInt(60);

        // Visual flags: lighting+specular always on, others random
        int flags = FLAG_LIGHTING | FLAG_SPECULAR;
        if (rng.nextBoolean()) flags |= FLAG_EMBOSS;
        if (rng.nextInt(3) == 0) flags |= FLAG_VARNISH;
        if (rng.nextInt(4) == 0) flags |= FLAG_RIM_GLOW;
        int varnish = 30 + rng.nextInt(50);

        // Rarity weighted
        int r = rng.nextInt(100);
        RarityType rarity = r < 40 ? RarityType.COMMON
                          : r < 65 ? RarityType.UNCOMMON
                          : r < 83 ? RarityType.RARE
                          : r < 93 ? RarityType.EPIC
                          : r < 98 ? RarityType.LEGENDARY
                          :          RarityType.MYTHIC;

        return new CollectibleDNA(seed, palette, pattern, scale, secPat, blend,
                                  emissive, density, rarity, stamp, micro, flags, varnish, "");
    }

    public static CollectibleDNA generate() { return randomDNA(new Random().nextLong()); }

    public String cacheKey() {
        return seed + "_" + palette + "_" + pattern + "_" + patternScale + "_"
             + secondaryPattern + "_" + patternBlend + "_" + emissive + "_"
             + emissiveDensity + "_" + rarity + "_" + stamp + "_" + microTexture
             + "_" + visualFlags + "_" + varnishLevel
             + (customPalette != null && !customPalette.isEmpty() ? "_" + customPalette : "");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static <T extends Enum<T>> T safeEnum(T[] values, String name, T def) {
        if (name == null || name.isEmpty()) return def;
        for (T v : values) if (v.name().equals(name)) return v;
        return def;
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
