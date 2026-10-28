package com.roicheese.eggandcheese.dna;

public enum CollectibleType {
    EGG("egg"),
    CHEESE("cheese");

    private final String id;
    CollectibleType(String id) { this.id = id; }
    public String getId() { return id; }

    public static CollectibleType fromId(String id) {
        for (CollectibleType t : values()) if (t.id.equals(id)) return t;
        return EGG;
    }
}
