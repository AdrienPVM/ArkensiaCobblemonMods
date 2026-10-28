package com.roicheese.eggandcheese.dna;

/**
 * 6-color palette: [0]=primary [1]=secondary [2]=highlight [3]=shadow [4]=accent [5]=glow
 */
public enum PaletteType {
    WARM      ("Chaleureux",  new int[]{0xFFE07030,0xFFFF9050,0xFFFFD080,0xFFBB4010,0xFFFFB040,0xFFFF6010}),
    COOL      ("Froid",       new int[]{0xFF3060E0,0xFF50A0FF,0xFF80D0FF,0xFF1030A0,0xFF00FFDD,0xFF2080FF}),
    NATURE    ("Nature",      new int[]{0xFF40A030,0xFF70C050,0xFFA0D060,0xFF105010,0xFFD4E830,0xFF00FF80}),
    DARK      ("Sombre",      new int[]{0xFF301040,0xFF602080,0xFF9050D0,0xFF100020,0xFFCC00FF,0xFF8800CC}),
    PASTEL    ("Pastel",      new int[]{0xFFFFB8C8,0xFFB8D8FF,0xFFB8FFD0,0xFFFFE8B8,0xFFE8B8FF,0xFFFF88BB}),
    FIRE      ("Feu",         new int[]{0xFFFF2000,0xFFFF8000,0xFFFFD000,0xFFCC1000,0xFFFF5500,0xFFFFFF00}),
    ICE       ("Glace",       new int[]{0xFFA0E8FF,0xFFD0F8FF,0xFF80C0FF,0xFF4090D0,0xFFFFFFFF,0xFF00DDFF}),
    MYSTIC    ("Mystique",    new int[]{0xFF8000FF,0xFFFF00C0,0xFFCC60FF,0xFF4000AA,0xFFFF80FF,0xFFBB00FF}),
    GOLD      ("Or",          new int[]{0xFFFFD700,0xFFFFA500,0xFFFFEC8B,0xFFA07000,0xFFFFFF80,0xFFFFCC00}),
    CANDY     ("Bonbon",      new int[]{0xFFFF69B4,0xFFFF1493,0xFFFFB6C1,0xFFCC1070,0xFFFF88FF,0xFFFF00AA}),
    NEBULA    ("Nébuleuse",   new int[]{0xFF0A0A2E,0xFF3B006E,0xFF7B2FBE,0xFF050510,0xFFFF6B9D,0xFF00FFCC}),
    LAVA      ("Lave",        new int[]{0xFF1A0000,0xFFCC2200,0xFFFF8800,0xFF0D0000,0xFFFFDD00,0xFFFF4400}),
    FOREST    ("Forêt",       new int[]{0xFF1B3A1B,0xFF2D6A2D,0xFF8BC34A,0xFF0A1A0A,0xFFCDDC39,0xFF76FF03}),
    ARCTIC    ("Arctique",    new int[]{0xFFF0F8FF,0xFFB0C8E0,0xFFFFFFFF,0xFF607890,0xFF88CCFF,0xFF00AAFF}),
    CORRUPTED ("Corrompu",    new int[]{0xFF1A001A,0xFF660066,0xFFCC00CC,0xFF000000,0xFFFF00FF,0xFFAA00AA}),
    CELESTIAL ("Céleste",     new int[]{0xFF000820,0xFF001050,0xFF4488FF,0xFF000010,0xFFFFEE88,0xFF88CCFF}),
    NEON      ("Néon",        new int[]{0xFF0D0D0D,0xFF00FFFF,0xFFFF00FF,0xFF000000,0xFF00FF00,0xFFFF00FF}),
    RAINBOW   ("Arc-en-ciel", new int[]{0xFFFF0000,0xFF00CC00,0xFF0000FF,0xFF800000,0xFFFFFF00,0xFFFF8800}),
    OCEAN     ("Océan",       new int[]{0xFF005577,0xFF0088AA,0xFF00CCDD,0xFF003344,0xFF44EEFF,0xFF00AACC}),
    SUNSET    ("Crépuscule",  new int[]{0xFFFF4500,0xFFFF6347,0xFFFFD700,0xFFCC3300,0xFFFF8C00,0xFFFF1493}),
    AUTUMN    ("Automne",     new int[]{0xFF8B4513,0xFFCD853F,0xFFDAA520,0xFF5C2E00,0xFFFF6600,0xFFFFA500}),
    SPRING    ("Printemps",   new int[]{0xFF88CC44,0xFFAADD66,0xFFFFFFAA,0xFF558833,0xFFFF88CC,0xFF66FF66}),
    CYBERPUNK ("Cyberpunk",   new int[]{0xFF0D0D0D,0xFFFF0055,0xFF00FFCC,0xFF000000,0xFFFF00AA,0xFF00CCFF}),
    STEAMPUNK ("Steampunk",   new int[]{0xFF8B6914,0xFFCD9B1D,0xFFEEC900,0xFF5C4500,0xFFB87333,0xFFC5A000}),
    DESERT    ("Désert",      new int[]{0xFFDEB887,0xFFF5DEB3,0xFFFFE4C4,0xFFA0825A,0xFFFF9933,0xFFFFCC66}),
    VOLCANIC  ("Volcanique",  new int[]{0xFF2A0000,0xFF880000,0xFFFF4400,0xFF110000,0xFFFFAA00,0xFFFF2200}),
    AURORA    ("Aurore",      new int[]{0xFF004466,0xFF00CC88,0xFF88FF44,0xFF002233,0xFFFF44AA,0xFF00FFAA}),
    AMETHYST  ("Améthyste",   new int[]{0xFF6A0DAD,0xFF9B30FF,0xFFDA70D6,0xFF3B0066,0xFFE040FB,0xFFBB44FF}),
    JADE      ("Jade",        new int[]{0xFF00A86B,0xFF50C878,0xFF98FB98,0xFF005533,0xFF00FF7F,0xFF66FFAA}),
    RUST      ("Rouille",     new int[]{0xFFB7410E,0xFFCC5500,0xFFE07020,0xFF802A00,0xFFFF7733,0xFFDD4400}),
    BLUEBERRY ("Myrtille",    new int[]{0xFF4169E1,0xFF6495ED,0xFF87CEEB,0xFF1C3D8F,0xFF88BBFF,0xFF3366FF}),
    PEACH     ("Pêche",       new int[]{0xFFFFDAB9,0xFFFFCBA4,0xFFFFE5CC,0xFFCC9977,0xFFFF9999,0xFFFFBB88}),
    TOXIC     ("Toxique",     new int[]{0xFF003300,0xFF00CC00,0xFF88FF00,0xFF001100,0xFFCCFF00,0xFF00FF44}),
    CUSTOM    ("Personnalisé", new int[]{0xFFE07030,0xFFFF9050,0xFFFFD080,0xFFBB4010,0xFFFFB040,0xFFFF6010});

    private final String displayName;
    private final int[]  colors;

    PaletteType(String dn, int[] c) { this.displayName = dn; this.colors = c; }

    public String getDisplayName() { return displayName; }
    public int[]  getColors()      { return colors; }
    public int    getPrimary()     { return colors[0]; }
    public int    getSecondary()   { return colors[1]; }
    public int    getHighlight()   { return colors[2]; }
    public int    getShadow()      { return colors[3]; }
    public int    getAccent()      { return colors[4]; }
    public int    getGlow()        { return colors[5]; }
}
