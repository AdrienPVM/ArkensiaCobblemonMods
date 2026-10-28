package com.roicheese.eggandcheese.dna;

public enum EmissiveType {
    NONE     ("Aucun"),
    SPOTS    ("Points"),
    LINES    ("Lignes"),
    RUNES    ("Runes"),
    STARS    ("Étoiles"),
    AURORA   ("Aurore"),
    VEINS    ("Veines"),
    SPARKS   ("Étincelles"),
    CRACKS   ("Fissures"),
    PULSE    ("Pulsation"),
    CIRCUIT  ("Circuits"),
    RIPPLE   ("Ondulations"),
    FLAME    ("Flammes"),
    NEBULA   ("Nébuleuse"),
    MATRIX   ("Matrice"),
    DIAMONDS ("Diamants");

    private final String displayName;
    EmissiveType(String dn) { this.displayName = dn; }
    public String getDisplayName() { return displayName; }
}
