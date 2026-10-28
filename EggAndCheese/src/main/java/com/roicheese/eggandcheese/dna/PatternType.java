package com.roicheese.eggandcheese.dna;

public enum PatternType {
    SPOTS        ("Taches"),
    STRIPES      ("Rayures"),
    MARBLE       ("Marbre"),
    GEOMETRIC    ("Géométrique"),
    VEINS        ("Veines"),
    CONSTELLATION("Constellation"),
    CRYSTALS     ("Cristaux"),
    BUBBLES      ("Bulles"),
    WAVES        ("Vagues"),
    MOSAIC       ("Mosaïque"),
    // New patterns
    SCALES       ("Écailles"),
    CRACKS       ("Fissures"),
    GALAXY       ("Galaxie"),
    RUNES        ("Runes"),
    SPLATTER     ("Éclaboussures"),
    CIRCUITS     ("Circuits"),
    TIEDYE       ("Tie-dye"),
    GRADIENT     ("Dégradé"),
    HONEYCOMB    ("Nid d'abeille"),
    FEATHERS     ("Plumes"),
    FLORA        ("Flore"),
    ZIGZAG       ("Zigzag"),
    CROSSHATCH   ("Hachures"),
    SMOKE        ("Fumée"),
    TRIBAL       ("Tribal"),
    FRACTAL      ("Fractal");

    private final String displayName;
    PatternType(String dn) { this.displayName = dn; }
    public String getDisplayName() { return displayName; }
}
