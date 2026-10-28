package com.roicheese.eggandcheese.dna;

public enum RarityType {
    COMMON    ("Commun",     0xFFAAAAAA, 1, false, false),
    UNCOMMON  ("Peu commun", 0xFF55FF55, 1, false, false),
    RARE      ("Rare",       0xFF5599FF, 2, false, false),
    EPIC      ("Épique",     0xFFAA00FF, 2, true,  false),
    LEGENDARY ("Légendaire", 0xFFFFAA00, 3, true,  true),
    MYTHIC    ("Mythique",   0xFFFF55FF, 3, true,  true);

    private final String  displayName;
    private final int     color;
    private final int     patternLayers;
    private final boolean hasBorder;
    private final boolean animated;  // emissive frames cycle for LEGENDARY+

    RarityType(String dn, int color, int layers, boolean border, boolean animated) {
        this.displayName   = dn;
        this.color         = color;
        this.patternLayers = layers;
        this.hasBorder     = border;
        this.animated      = animated;
    }

    public String  getDisplayName()   { return displayName; }
    public int     getColor()         { return color; }
    public int     getPatternLayers() { return patternLayers; }
    public boolean hasBorder()        { return hasBorder; }
    public boolean isAnimated()       { return animated; }
}
