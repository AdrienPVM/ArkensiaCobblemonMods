package com.roicheese.eggandcheese.dna;

/**
 * Iconographic stamp drawn as a third layer over the texture.
 * Distinct from patterns — these are recognisable shapes (star, moon, etc.)
 */
public enum StampType {
    NONE         ("Aucun"),
    STAR         ("Étoile"),
    MOON         ("Lune"),
    HEART        ("Cœur"),
    SPIRAL       ("Spirale"),
    DIAMOND      ("Diamant"),
    RUNE_TRIPLE  ("Triple rune"),
    ARROW        ("Flèche"),
    CROWN        ("Couronne"),
    INFINITY     ("Infini");

    private final String displayName;
    StampType(String dn) { this.displayName = dn; }
    public String getDisplayName() { return displayName; }
}
