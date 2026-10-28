package com.roicheese.eggandcheese.dna;

/**
 * High-frequency surface micro-texture layered on top of everything else.
 * Simulates material properties: grainy, metallic, icy, etc.
 */
public enum MicroTextureType {
    NONE      ("Aucune"),
    GRANULAR  ("Granuleux"),
    METALLIC  ("Métallique"),
    ICY       ("Glacé"),
    SILK      ("Soie"),
    STONE     ("Pierre"),
    SAND      ("Sable"),
    CARBON    ("Carbone");

    private final String displayName;
    MicroTextureType(String dn) { this.displayName = dn; }
    public String getDisplayName() { return displayName; }
}
