package com.cheeseguard.data;

/**
 * Server-wide configuration for a zone tag.
 * Stored in config/cheeseguard/tag_configs.json.
 */
public class TagConfig {

    private String id;              // Tag identifier (lowercase, e.g., "shop")
    private String label;           // Display name (optional, defaults to id)
    private String color;           // Hex color (e.g., "#f5a623") or null for hash-based default
    private int ownershipLimit;     // Max zones with this tag per player (0 = unlimited)

    public TagConfig(String id, String label, String color, int ownershipLimit) {
        this.id = id;
        this.label = label;
        this.color = color;
        this.ownershipLimit = Math.max(0, ownershipLimit);
    }

    // ── Getters / Setters ──────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLabel() { return label != null && !label.isEmpty() ? label : id; }
    public void setLabel(String label) { this.label = label; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public int getOwnershipLimit() { return ownershipLimit; }
    public void setOwnershipLimit(int ownershipLimit) { this.ownershipLimit = Math.max(0, ownershipLimit); }

    /**
     * Returns the display color for this tag. If no color is configured,
     * computes a deterministic color from the tag ID hash.
     */
    public String getDisplayColor() {
        if (color != null && !color.isEmpty()) return color;
        return hashColor(id);
    }

    /**
     * Deterministic color hash: tag string -> HSL -> hex.
     * Produces readable, distinct colors for different tags.
     */
    public static String hashColor(String tag) {
        if (tag == null || tag.isEmpty()) return "#6b7280";
        int hash = tag.hashCode() & 0x7FFFFFFF;
        float hue = (hash % 360) / 360f;
        float saturation = 0.65f;
        float lightness = 0.55f;
        return hslToHex(hue, saturation, lightness);
    }

    private static String hslToHex(float h, float s, float l) {
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs((h * 6f) % 2f - 1f));
        float m = l - c / 2f;
        float r, g, b;
        int sector = (int) (h * 6f) % 6;
        switch (sector) {
            case 0:  r = c; g = x; b = 0; break;
            case 1:  r = x; g = c; b = 0; break;
            case 2:  r = 0; g = c; b = x; break;
            case 3:  r = 0; g = x; b = c; break;
            case 4:  r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return String.format("#%02x%02x%02x", ri, gi, bi);
    }
}
